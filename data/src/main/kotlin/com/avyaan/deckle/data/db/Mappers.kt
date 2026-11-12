package com.avyaan.deckle.data.db

import com.avyaan.deckle.domain.document.Document
import com.avyaan.deckle.domain.document.DocumentSummary
import com.avyaan.deckle.domain.folder.Folder
import com.avyaan.deckle.domain.document.SnippetParser
import com.avyaan.deckle.domain.geometry.Quad
import com.avyaan.deckle.domain.geometry.Rotation
import com.avyaan.deckle.domain.imaging.Adjustments
import com.avyaan.deckle.domain.imaging.EnhancementMode
import com.avyaan.deckle.domain.ocr.OcrLine
import com.avyaan.deckle.domain.ocr.OcrState
import com.avyaan.deckle.domain.ocr.OcrStatus
import com.avyaan.deckle.domain.page.Page
import java.time.Instant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Explicit, testable mapping between rows and domain models.
 * Unknown enum names fall back to safe defaults so a future rename can't brick the library.
 */
internal object Mappers {
    private val json = Json { ignoreUnknownKeys = true }
    private val linesSerializer = ListSerializer(OcrLine.serializer())

    fun DocumentEntity.toDomain() = Document(
        id = id,
        title = title,
        createdAt = Instant.ofEpochMilli(createdAt),
        modifiedAt = Instant.ofEpochMilli(modifiedAt),
        coverPageId = coverPageId,
        deletedAt = deletedAt?.let(Instant::ofEpochMilli),
        folderId = folderId,
    )

    fun FolderEntity.toDomain() = Folder(id, name, parentId, Instant.ofEpochMilli(createdAt), Instant.ofEpochMilli(modifiedAt))

    fun Folder.toEntity() = FolderEntity(id, name, parentId, createdAt.toEpochMilli(), modifiedAt.toEpochMilli())

    fun DocumentSummaryRow.toDomain() = DocumentSummary(
        id = id,
        title = title,
        pageCount = pageCount,
        createdAt = Instant.ofEpochMilli(createdAt),
        modifiedAt = Instant.ofEpochMilli(modifiedAt),
        deletedAt = deletedAt?.let(Instant::ofEpochMilli),
        coverThumbPath = coverThumbPath,
        snippet = snippet?.let(SnippetParser::parse),
    )

    fun PageEntity.toDomain() = Page(
        id = id,
        documentId = documentId,
        orderIndex = orderIndex,
        sourcePath = sourcePath,
        basePath = basePath,
        processedPath = processedPath,
        thumbPath = thumbPath,
        corners = runCatching { json.decodeFromString(Quad.serializer(), cornersJson) }.getOrDefault(Quad.FULL),
        rotation = Rotation.fromDegrees(rotationDegrees),
        mode = enumOr(enhancementMode, EnhancementMode.COLOUR),
        adjustments = Adjustments(brightness, contrast),
        widthPx = widthPx,
        heightPx = heightPx,
        ocr = OcrState(enumOr(ocrStatus, OcrStatus.QUEUED), decodeLines(ocrLinesJson), ocrAttempts),
        name = name,
    )

    fun Page.toEntity(plainText: String?) = PageEntity(
        id = id,
        documentId = documentId,
        orderIndex = orderIndex,
        sourcePath = sourcePath,
        basePath = basePath,
        processedPath = processedPath,
        thumbPath = thumbPath,
        cornersJson = json.encodeToString(Quad.serializer(), corners),
        enhancementMode = mode.name,
        rotationDegrees = rotation.degrees,
        brightness = adjustments.brightness,
        contrast = adjustments.contrast,
        widthPx = widthPx,
        heightPx = heightPx,
        ocrStatus = ocr.status.name,
        ocrAttempts = ocr.attempts,
        ocrLinesJson = encodeLines(ocr.lines),
        name = name,
        ocrPlainText = plainText,
    )

    fun encodeLines(lines: List<OcrLine>?): String? = lines?.let { json.encodeToString(linesSerializer, it) }

    private fun decodeLines(s: String?): List<OcrLine>? = s?.let { runCatching { json.decodeFromString(linesSerializer, it) }.getOrNull() }

    private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
