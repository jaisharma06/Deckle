package com.avyaan.deckle.data.markup

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.avyaan.deckle.domain.markup.MarkupItem
import com.avyaan.deckle.domain.markup.PageMarkup
import com.avyaan.deckle.domain.markup.SignatureInk

/**
 * Draws a page's markup onto an [android.graphics.Canvas] covering the page at [width] × [height] pixels.
 * The editor (through Compose's native canvas), the viewer and the export all use this one painter, so what
 * the user sees is exactly what gets exported. Not thread-safe: each user keeps its own instance.
 */
public class MarkupPainter {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL) }

    /** [skip] leaves one item out (the editor draws the item being dragged itself). */
    public fun draw(canvas: Canvas, markup: PageMarkup?, width: Float, height: Float, skip: String? = null) {
        markup ?: return
        // Highlighter first so pen, text and signatures sit on top of it; redactions last so nothing shows through.
        val ordered = markup.items.sortedBy {
            when (it) {
                is MarkupItem.Ink -> if (it.highlighter) 0 else 1
                is MarkupItem.Text, is MarkupItem.Signature -> 1
                is MarkupItem.Redaction -> 2
            }
        }
        ordered.forEach { if (it.id != skip) drawItem(canvas, it, width, height) }
    }

    public fun drawItem(canvas: Canvas, item: MarkupItem, width: Float, height: Float) {
        when (item) {
            is MarkupItem.Ink -> {
                if (item.points.size < 2) return
                stroke.color = item.color.toInt()
                stroke.strokeWidth = item.width * width
                stroke.strokeCap = if (item.highlighter) Paint.Cap.SQUARE else Paint.Cap.ROUND
                if (item.points.size == 2) {
                    fill.color = item.color.toInt()
                    canvas.drawCircle(item.points[0] * width, item.points[1] * height, stroke.strokeWidth / 2f, fill)
                } else {
                    canvas.drawPath(path(item.points, width, height), stroke)
                }
            }
            is MarkupItem.Text -> {
                text.color = item.color.toInt()
                text.textSize = item.size * width
                val lines = item.text.split('\n')
                lines.forEachIndexed { i, line ->
                    val baseline = item.y * height + text.textSize * (i * LINE_HEIGHT + 1f) - text.descent() / 2f
                    canvas.drawText(line, item.x * width, baseline, text)
                }
            }
            is MarkupItem.Signature -> {
                val scale = item.width * width
                canvas.save()
                canvas.translate(item.cx * width, item.cy * height)
                canvas.rotate(item.rotation)
                drawInk(canvas, item.ink, scale, item.color, centred = true)
                canvas.restore()
            }
            is MarkupItem.Redaction -> {
                fill.color = 0xFF000000.toInt()
                val r = item.rect
                canvas.drawRect(r.left * width, r.top * height, r.right * width, r.bottom * height, fill)
            }
        }
    }

    /** A signature [scale] pixels wide; [centred] puts its middle at the origin, otherwise its top-left. */
    public fun drawInk(canvas: Canvas, ink: SignatureInk, scale: Float, color: Long, centred: Boolean) {
        stroke.color = color.toInt()
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeWidth = (SIGNATURE_STROKE * scale).coerceAtLeast(1f)
        val dx = if (centred) -0.5f else 0f
        val dy = if (centred) -ink.aspect / 2f else 0f
        ink.strokes.forEach { s ->
            if (s.size < 4) return@forEach
            val p = Path()
            p.moveTo((s[0] + dx) * scale, (s[1] + dy) * scale)
            var i = 2
            while (i + 1 < s.size) {
                p.lineTo((s[i] + dx) * scale, (s[i + 1] + dy) * scale)
                i += 2
            }
            canvas.drawPath(p, stroke)
        }
    }

    /** Text size and box of a text mark, normalised (width / page width, height / page height). */
    public fun textSize(item: MarkupItem.Text, pageAspect: Float): Pair<Float, Float> {
        text.textSize = item.size * 1000f
        val lines = item.text.split('\n')
        val w = lines.maxOf { text.measureText(it) } / 1000f
        val h = item.size * (lines.size * LINE_HEIGHT) / pageAspect
        return w to h
    }

    /** NV-43: [label] repeated diagonally across the page, light enough to read the page through. */
    public fun drawWatermark(canvas: Canvas, label: String, width: Float, height: Float) {
        if (label.isBlank()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x40B71C1C
            textSize = minOf(width, height) * 0.06f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val step = paint.measureText(label) + paint.textSize * 2f
        val rowGap = paint.textSize * 4f
        val diag = kotlin.math.hypot(width, height)
        canvas.save()
        canvas.rotate(-35f, width / 2f, height / 2f)
        var y = height / 2f - diag / 2f
        var row = 0
        while (y < height / 2f + diag / 2f) {
            var x = width / 2f - diag / 2f - (row % 2) * step / 2f
            while (x < width / 2f + diag / 2f) {
                canvas.drawText(label, x, y, paint)
                x += step
            }
            y += rowGap
            row++
        }
        canvas.restore()
    }

    private fun path(points: List<Float>, width: Float, height: Float) = Path().apply {
        moveTo(points[0] * width, points[1] * height)
        var i = 2
        while (i + 1 < points.size) {
            lineTo(points[i] * width, points[i + 1] * height)
            i += 2
        }
    }

    private companion object {
        /** Signature stroke width as a fraction of the signature's own width. */
        const val SIGNATURE_STROKE = 0.016f
        const val LINE_HEIGHT = 1.25f
    }
}
