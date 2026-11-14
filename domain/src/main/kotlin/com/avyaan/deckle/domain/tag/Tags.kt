package com.avyaan.deckle.domain.tag

import com.avyaan.deckle.domain.common.Clock
import com.avyaan.deckle.domain.common.IdGenerator
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * NV-30: a coloured label that works across folders ("Bills", "IDs", "Medical").
 * A document can carry any number of tags; deleting a tag only removes the label, never documents.
 */
public data class Tag(
    val id: String,
    val name: String,
    val color: TagColor,
    val createdAt: Instant,
)

/** A fixed palette, so tags stay readable on the paper theme in light and dark mode. Stored by name. */
public enum class TagColor { CLAY, AMBER, OLIVE, SAGE, TEAL, SKY, INDIGO, PLUM }

public interface TagRepository {
    /** All tags, alphabetical. */
    public fun observeAll(): Flow<List<Tag>>
    public suspend fun all(): List<Tag>
    public suspend fun create(tag: Tag)
    public suspend fun update(id: String, name: String, color: TagColor)
    public suspend fun delete(id: String)
    public fun observeTagsOf(documentId: String): Flow<Set<String>>
    /** Replaces the document's tags with [tagIds]. */
    public suspend fun setTags(documentId: String, tagIds: Set<String>)
    /** Adds [tagId] to every document in [documentIds] (library multi-select). */
    public suspend fun addTag(documentIds: List<String>, tagId: String)
}

public object TagRules {
    public const val MAX_LENGTH: Int = 32

    /** Trimmed, inner whitespace collapsed; null when nothing is left. */
    public fun clean(raw: String): String? =
        raw.trim().replace(Regex("\\s+"), " ").take(MAX_LENGTH).trim().takeIf { it.isNotEmpty() }

    /** The next colour nobody uses yet, cycling once all are taken. */
    public fun nextColor(existing: List<Tag>): TagColor {
        val used = existing.map { it.color }.toSet()
        return TagColor.entries.firstOrNull { it !in used } ?: TagColor.entries[existing.size % TagColor.entries.size]
    }
}

/**
 * Creates a tag, or returns the existing one with the same name (case-insensitive), so typing "bills"
 * twice never makes two "Bills" chips. Null for a blank name.
 */
public class CreateTagUseCase @Inject constructor(
    private val tags: TagRepository,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    public suspend operator fun invoke(raw: String, color: TagColor? = null): Tag? {
        val name = TagRules.clean(raw) ?: return null
        val existing = tags.all()
        existing.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
        val tag = Tag(ids.newId(), name, color ?: TagRules.nextColor(existing), clock.now())
        tags.create(tag)
        return tag
    }
}

/** Renames or recolours. False when the name is blank or already used by another tag. */
public class EditTagUseCase @Inject constructor(private val tags: TagRepository) {
    public suspend operator fun invoke(id: String, raw: String, color: TagColor): Boolean {
        val name = TagRules.clean(raw) ?: return false
        if (tags.all().any { it.id != id && it.name.equals(name, ignoreCase = true) }) return false
        tags.update(id, name, color)
        return true
    }
}

public class DeleteTagUseCase @Inject constructor(private val tags: TagRepository) {
    public suspend operator fun invoke(id: String): Unit = tags.delete(id)
}

public class SetDocumentTagsUseCase @Inject constructor(private val tags: TagRepository) {
    public suspend operator fun invoke(documentId: String, tagIds: Set<String>): Unit = tags.setTags(documentId, tagIds)
}

public class TagDocumentsUseCase @Inject constructor(private val tags: TagRepository) {
    public suspend operator fun invoke(documentIds: List<String>, tagId: String) {
        if (documentIds.isNotEmpty()) tags.addTag(documentIds, tagId)
    }
}
