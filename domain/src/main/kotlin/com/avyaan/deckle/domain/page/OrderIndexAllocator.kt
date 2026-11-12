package com.avyaan.deckle.domain.page

/**
 * Sparse page ordering, stepped by 1000, so moving one page rewrites one row, not all of them.
 */
public object OrderIndexAllocator {
    public const val STEP: Long = 1_000L

    public sealed interface Allocation {
        /** Use these indices as-is. */
        public data class Indices(val values: List<Long>) : Allocation
        /** No room between neighbours: renumber the document (rare), then allocate again. */
        public data object Renumber : Allocation
    }

    /** First indices for a brand-new document. */
    public fun initial(count: Int): List<Long> = List(count) { (it + 1) * STEP }

    /** Index for a page placed between [before] and [after] (either may be null for the ends). */
    public fun between(before: Long?, after: Long?): Long? = when {
        before == null && after == null -> STEP
        before == null -> if (after!! > 1) after / 2 else null
        after == null -> before + STEP
        after - before >= 2 -> before + (after - before) / 2
        else -> null
    }

    /** [count] new indices inserted between [before] and [after], evenly spread. */
    public fun allocate(before: Long?, after: Long?, count: Int): Allocation {
        if (count == 0) return Allocation.Indices(emptyList())
        if (after == null) {
            val start = before ?: 0L
            return Allocation.Indices(List(count) { start + (it + 1) * STEP })
        }
        val low = before ?: 0L
        val gap = after - low
        if (gap <= count) return Allocation.Renumber
        val step = gap / (count + 1)
        return Allocation.Indices(List(count) { low + (it + 1) * step })
    }

    /** Fresh, evenly spaced indices for an ordered list of page ids. */
    public fun renumber(orderedIds: List<String>): Map<String, Long> =
        orderedIds.withIndex().associate { (i, id) -> id to (i + 1) * STEP }

    /**
     * Index for moving a page to [toIndex] in [ordered] (the list *with the page removed*).
     * Returns null when neighbours have no gap and the caller must renumber.
     */
    public fun forMove(ordered: List<Long>, toIndex: Int): Long? {
        val before = ordered.getOrNull(toIndex - 1)
        val after = ordered.getOrNull(toIndex)
        return between(before, after)
    }
}
