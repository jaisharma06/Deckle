package com.avyaan.deckle.domain.markup

import com.avyaan.deckle.domain.common.Clock
import com.avyaan.deckle.domain.common.IdGenerator
import com.avyaan.deckle.domain.document.DocumentRepository
import com.avyaan.deckle.domain.geometry.RectN
import com.avyaan.deckle.domain.ocr.OcrLine
import com.avyaan.deckle.domain.page.PageRepository
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * NV-40…44: a page's editable layer. It is stored as data next to the page, drawn over the image in the
 * app, and burned into exported copies only; the scanned image itself is never changed, so every mark can
 * be moved, edited, undone or removed later.
 *
 * All positions are normalised to the processed image: x and y in 0…1 of its width and height. Sizes
 * (stroke widths, text size, signature width) are fractions of the image width, so a mark keeps its
 * look at any export resolution.
 */
@Serializable
public data class PageMarkup(val items: List<MarkupItem> = emptyList()) {
    val isEmpty: Boolean get() = items.isEmpty()
    val redactions: List<MarkupItem.Redaction> get() = items.filterIsInstance<MarkupItem.Redaction>()
    val signed: Boolean get() = items.any { it is MarkupItem.Signature }

    public companion object {
        public val EMPTY: PageMarkup = PageMarkup()
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        public fun encode(markup: PageMarkup?): String? = markup?.takeUnless { it.isEmpty }?.let { json.encodeToString(serializer(), it) }

        /** Unreadable markup (a newer app version, corruption) is dropped, never a crash. */
        public fun decode(value: String?): PageMarkup? =
            value?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }?.takeUnless { it.isEmpty }
    }
}

@Serializable
public sealed interface MarkupItem {
    public val id: String

    /** NV-41: pen or highlighter. [points] is x0, y0, x1, y1, … */
    @Serializable
    @SerialName("ink")
    public data class Ink(
        override val id: String,
        val points: List<Float>,
        val color: Long,
        val width: Float,
        val highlighter: Boolean = false,
    ) : MarkupItem

    /** NV-41/44: typed text; ([x], [y]) is the top-left of the first line. */
    @Serializable
    @SerialName("text")
    public data class Text(
        override val id: String,
        val x: Float,
        val y: Float,
        val text: String,
        val size: Float,
        val color: Long,
    ) : MarkupItem

    /**
     * NV-40: a placed signature. The ink is copied in, so the page stays signed even if the saved signature
     * is later deleted. ([cx], [cy]) is the centre, [width] a fraction of the page width.
     */
    @Serializable
    @SerialName("signature")
    public data class Signature(
        override val id: String,
        val ink: SignatureInk,
        val cx: Float,
        val cy: Float,
        val width: Float,
        val rotation: Float = 0f,
        val color: Long = INK_BLUE,
    ) : MarkupItem

    /** NV-42: an area blacked out in exports, with the OCR text under it removed from the PDF text layer. */
    @Serializable
    @SerialName("redact")
    public data class Redaction(
        override val id: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) : MarkupItem {
        public val rect: RectN get() = RectN(minOf(left, right), minOf(top, bottom), maxOf(left, right), maxOf(top, bottom))
    }

    public companion object {
        public const val INK_BLACK: Long = 0xFF1B1B1F
        public const val INK_BLUE: Long = 0xFF1A3D8F
        public const val INK_RED: Long = 0xFFC62828
        public const val INK_GREEN: Long = 0xFF2E7D32
        public const val HIGHLIGHT_YELLOW: Long = 0x66FFE14D
        public const val HIGHLIGHT_GREEN: Long = 0x6680E27E
        public const val HIGHLIGHT_PINK: Long = 0x66FF8AC4
    }
}

/**
 * A drawn signature, normalised to its own bounding box: x in 0…1, y in 0…[aspect] (height / width).
 * Each stroke is x0, y0, x1, y1, …
 */
@Serializable
public data class SignatureInk(val strokes: List<List<Float>>, val aspect: Float) {
    public companion object {
        /**
         * Normalises raw strokes (any coordinates) into a box. Null when there is nothing to sign with:
         * no strokes, or a single dot.
         */
        public fun from(raw: List<List<Float>>): SignatureInk? {
            val xs = raw.flatMap { s -> s.filterIndexed { i, _ -> i % 2 == 0 } }
            val ys = raw.flatMap { s -> s.filterIndexed { i, _ -> i % 2 == 1 } }
            if (xs.isEmpty()) return null
            val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
            val w = maxX - minX
            val h = maxY - minY
            if (w < 1e-3f && h < 1e-3f) return null
            val scale = 1f / maxOf(w, 1e-3f)
            val strokes = raw.filter { it.size >= 2 }.map { s ->
                s.mapIndexed { i, v -> if (i % 2 == 0) (v - minX) * scale else (v - minY) * scale }
            }
            return SignatureInk(strokes, aspect = maxOf(h * scale, 0.05f))
        }
    }
}

/** NV-40: a reusable signature, kept encrypted on the device. */
public data class SavedSignature(val id: String, val ink: SignatureInk)

public interface SignatureStore {
    public suspend fun list(): List<SavedSignature>
    public suspend fun save(ink: SignatureInk): SavedSignature
    public suspend fun delete(id: String)
}

/** Pure geometry used by the editor, the export renderer and redaction. */
public object MarkupGeometry {
    /** A signature's corners in page coordinates (normalised), after rotation; [pageAspect] = height / width. */
    public fun signatureCorners(s: MarkupItem.Signature, pageAspect: Float): List<Pair<Float, Float>> {
        val halfW = s.width / 2f
        val halfH = s.width * s.ink.aspect / 2f / pageAspect
        val a = Math.toRadians(s.rotation.toDouble())
        val c = cos(a).toFloat(); val sn = sin(a).toFloat()
        return listOf(-halfW to -halfH, halfW to -halfH, halfW to halfH, -halfW to halfH).map { (dx, dy) ->
            // Rotate in page pixels (x scaled by 1, y by pageAspect) so the angle looks right on the page.
            val px = dx; val py = dy * pageAspect
            val rx = px * c - py * sn
            val ry = px * sn + py * c
            (s.cx + rx) to (s.cy + ry / pageAspect)
        }
    }

    /** Axis-aligned bounds of an item, for hit testing and selection. */
    public fun bounds(item: MarkupItem, pageAspect: Float, measureText: (MarkupItem.Text) -> Pair<Float, Float>): RectN = when (item) {
        is MarkupItem.Ink -> {
            val xs = item.points.filterIndexed { i, _ -> i % 2 == 0 }
            val ys = item.points.filterIndexed { i, _ -> i % 2 == 1 }
            val pad = item.width / 2f
            RectN(xs.min() - pad, ys.min() - pad / pageAspect, xs.max() + pad, ys.max() + pad / pageAspect)
        }
        is MarkupItem.Text -> {
            val (w, h) = measureText(item)
            RectN(item.x, item.y, item.x + w, item.y + h)
        }
        is MarkupItem.Signature -> {
            val pts = signatureCorners(item, pageAspect)
            RectN(pts.minOf { it.first }, pts.minOf { it.second }, pts.maxOf { it.first }, pts.maxOf { it.second })
        }
        is MarkupItem.Redaction -> item.rect
    }

    public fun overlaps(a: RectN, b: RectN): Boolean = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    /** Moves an item by (dx, dy) in normalised page coordinates. */
    public fun translate(item: MarkupItem, dx: Float, dy: Float): MarkupItem = when (item) {
        is MarkupItem.Ink -> item.copy(points = item.points.mapIndexed { i, v -> if (i % 2 == 0) v + dx else v + dy })
        is MarkupItem.Text -> item.copy(x = item.x + dx, y = item.y + dy)
        is MarkupItem.Signature -> item.copy(cx = item.cx + dx, cy = item.cy + dy)
        is MarkupItem.Redaction -> item.copy(left = item.left + dx, right = item.right + dx, top = item.top + dy, bottom = item.bottom + dy)
    }

    /** Pinch: scales signatures and text about their own position, and turns signatures. Others are unchanged. */
    public fun transform(item: MarkupItem, zoom: Float, rotationDegrees: Float): MarkupItem = when (item) {
        is MarkupItem.Signature -> item.copy(width = (item.width * zoom).coerceIn(MIN_SIGNATURE_WIDTH, 1.5f), rotation = item.rotation + rotationDegrees)
        is MarkupItem.Text -> item.copy(size = (item.size * zoom).coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE))
        else -> item
    }

    public const val MIN_SIGNATURE_WIDTH: Float = 0.08f
    public const val MIN_TEXT_SIZE: Float = 0.012f
    public const val MAX_TEXT_SIZE: Float = 0.2f
}

/**
 * NV-42: the OCR lines that may go into an exported text layer. A line touched by any redaction is
 * dropped whole: keeping its unredacted half would leak how long the hidden part is and often part of it.
 */
public object Redaction {
    public fun visibleLines(lines: List<OcrLine>, markup: PageMarkup?): List<OcrLine> {
        val boxes = markup?.redactions?.map { it.rect }.orEmpty()
        if (boxes.isEmpty()) return lines
        return lines.filter { line -> boxes.none { MarkupGeometry.overlaps(it, line.box) } }
    }
}

/** Saves a page's markup. Touches the document, so "Last modified" reflects the edit. */
public class SaveMarkupUseCase @Inject constructor(
    private val pages: PageRepository,
    private val documents: DocumentRepository,
    private val clock: Clock,
) {
    public suspend operator fun invoke(pageId: String, markup: PageMarkup) {
        val page = pages.get(pageId) ?: return
        pages.setMarkup(pageId, markup.takeUnless { it.isEmpty })
        documents.touch(page.documentId, clock.now())
    }
}

public class SaveSignatureUseCase @Inject constructor(private val store: SignatureStore) {
    /** Null when the drawing is empty. */
    public suspend operator fun invoke(rawStrokes: List<List<Float>>): SavedSignature? =
        SignatureInk.from(rawStrokes)?.let { store.save(it) }
}

/** Ids for new marks; kept here so the editor doesn't need a UUID dependency. */
public class MarkupIds @Inject constructor(private val ids: IdGenerator) {
    public fun next(): String = ids.newId()
}
