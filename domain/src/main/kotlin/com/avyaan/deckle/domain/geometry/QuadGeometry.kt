package com.avyaan.deckle.domain.geometry

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pure geometry on normalised quads.
 *
 * Normalised coordinates squash the image into a unit square, so any *length* must be corrected by the
 * image aspect ratio (width / height). Areas and orientation do not need it.
 */
public object QuadGeometry {

    /** Area as a fraction of the image area (shoelace formula). Aspect-independent. */
    public fun areaFraction(q: Quad): Float {
        val c = q.corners
        var sum = 0f
        for (i in c.indices) {
            val a = c[i]
            val b = c[(i + 1) % c.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2f
    }

    /** Length in "image height" units: x is scaled by [aspect] so lengths are comparable in any direction. */
    public fun length(a: PointN, b: PointN, aspect: Float): Float = hypot((b.x - a.x) * aspect, b.y - a.y)

    /**
     * True when the four corners, in order, turn the same way at every vertex.
     * For four vertices this also guarantees the outline does not cross itself.
     */
    public fun isConvex(q: Quad): Boolean {
        val c = q.corners
        var sign = 0
        for (i in c.indices) {
            val a = c[i]
            val b = c[(i + 1) % 4]
            val d = c[(i + 2) % 4]
            val cross = (b.x - a.x) * (d.y - b.y) - (b.y - a.y) * (d.x - b.x)
            if (abs(cross) < 1e-9f) return false
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Largest ratio between the two pairs of opposite sides, >= 1. */
    public fun maxOppositeSideRatio(q: Quad, aspect: Float): Float {
        val top = length(q.tl, q.tr, aspect)
        val bottom = length(q.bl, q.br, aspect)
        val left = length(q.tl, q.bl, aspect)
        val right = length(q.tr, q.br, aspect)
        return max(ratio(top, bottom), ratio(left, right))
    }

    private fun ratio(a: Float, b: Float): Float {
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        return if (lo <= 0f) Float.POSITIVE_INFINITY else hi / lo
    }

    /**
     * Dewarped output size in pixels for a quad on an image of [image] size:
     * width = max(|TR−TL|, |BR−BL|), height = max(|BL−TL|, |BR−TR|).
     */
    public fun outputSize(q: Quad, image: ImageSize): ImageSize {
        fun px(a: PointN, b: PointN) = hypot((b.x - a.x) * image.width, (b.y - a.y) * image.height)
        val w = max(px(q.tl, q.tr), px(q.bl, q.br))
        val h = max(px(q.tl, q.bl), px(q.tr, q.br))
        return ImageSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1))
    }

    /** Largest corner-to-corner displacement between two quads, in frame-width units. */
    public fun maxCornerDistance(a: Quad, b: Quad, aspect: Float): Float {
        var m = 0f
        val ca = a.corners
        val cb = b.corners
        for (i in 0 until 4) {
            // Divide by aspect: lengths here are in frame-*width* units, as the requirements state.
            m = max(m, length(ca[i], cb[i], aspect) / aspect)
        }
        return m
    }
}
