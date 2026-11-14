package com.avyaan.deckle.domain.document

import com.avyaan.deckle.domain.reminder.ExpiryPolicy
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * NV-31: collections that fill themselves. Each is a saved query over every live document, whatever
 * folder it sits in. Tags are collections too ([LibraryFilter.tagId]).
 */
public enum class SmartCollection {
    PINNED,
    /** Expires within [ExpiryPolicy.SOON_DAYS] days, or already expired. */
    EXPIRING,
    /** Scanned this calendar month: receipts, bills. */
    THIS_MONTH,
    /** Carries a placed signature (NV-40). */
    SIGNED,
}

/** What the library is narrowed to. At most one collection or tag at a time; "Has text" combines with both. */
public data class LibraryFilter(
    val textOnly: Boolean = false,
    val collection: SmartCollection? = null,
    val tagId: String? = null,
) {
    /** A collection or tag looks across every folder; "Has text" alone keeps to the current level. */
    val libraryWide: Boolean get() = collection != null || tagId != null
    val active: Boolean get() = textOnly || libraryWide

    public companion object {
        public val NONE: LibraryFilter = LibraryFilter()
    }
}

public object SmartCollections {
    public fun matches(doc: DocumentSummary, filter: LibraryFilter, now: Instant, zone: ZoneId): Boolean {
        if (filter.textOnly && !doc.hasText) return false
        if (filter.tagId != null && filter.tagId !in doc.tagIds) return false
        return when (filter.collection) {
            null -> true
            SmartCollection.PINNED -> doc.pinned
            SmartCollection.EXPIRING -> doc.expiresAt?.let { ExpiryPolicy.daysLeft(it, now, zone) <= ExpiryPolicy.SOON_DAYS } == true
            SmartCollection.THIS_MONTH -> YearMonth.from(doc.createdAt.atZone(zone)) == YearMonth.from(now.atZone(zone))
            SmartCollection.SIGNED -> doc.signed
        }
    }

    /** Expiring documents read best soonest-first; the others keep the library's sort. */
    public fun order(docs: List<DocumentSummary>, filter: LibraryFilter): List<DocumentSummary> =
        if (filter.collection == SmartCollection.EXPIRING) docs.sortedBy { it.expiresAt } else docs

    /** The library's sort applied in memory (collections gather documents from every folder). */
    public fun comparator(sort: SortOrder): Comparator<DocumentSummary> = when (sort) {
        SortOrder.CREATED_DESC -> compareByDescending { it.createdAt }
        SortOrder.MODIFIED_DESC -> compareByDescending { it.modifiedAt }
        SortOrder.TITLE_ASC -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        SortOrder.PAGES_DESC -> compareByDescending<DocumentSummary> { it.pageCount }.thenByDescending { it.createdAt }
    }

    /** NV-03: pinned items first; a stable sort keeps the chosen order within each group. */
    public fun pinnedFirst(docs: List<DocumentSummary>): List<DocumentSummary> = docs.sortedByDescending { it.pinned }
}
