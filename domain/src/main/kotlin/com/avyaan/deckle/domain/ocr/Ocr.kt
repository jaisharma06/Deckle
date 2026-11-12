package com.avyaan.deckle.domain.ocr

import com.avyaan.deckle.domain.common.Outcome
import com.avyaan.deckle.domain.geometry.RectN
import kotlinx.serialization.Serializable

/** FR-44. */
public enum class OcrStatus { QUEUED, RUNNING, DONE, FAILED }

/** FR-62/63: one recognised line, its box normalised to the page image, and recognition confidence. */
@Serializable
public data class OcrLine(val text: String, val box: RectN, val confidence: Float)

public data class OcrState(val status: OcrStatus, val lines: List<OcrLine>?, val attempts: Int) {
    public companion object {
        public val QUEUED: OcrState = OcrState(OcrStatus.QUEUED, lines = null, attempts = 0)
        /** FR-65: one initial attempt plus one retry on the next launch. */
        public const val MAX_ATTEMPTS: Int = 2
    }
}

/** Plain text of a page, as stored for full-text search: lines joined by newlines. */
public fun List<OcrLine>.toPlainText(): String = joinToString("\n") { it.text }

/** Bundled Latin-script recognizer (FR-61). */
public interface TextRecognizer {
    public suspend fun recognize(greyImagePath: String): Outcome<List<OcrLine>>
}

/** FR-64: unique background work per page. Enqueueing an already-queued page is a no-op. */
public interface OcrScheduler {
    public fun enqueue(pageIds: List<String>)
    public fun cancel(pageId: String)
}
