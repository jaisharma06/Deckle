package com.avyaan.deckle.domain.geometry

import kotlinx.serialization.Serializable

/** A point normalised to 0..1 against the image it was found in. */
@Serializable
public data class PointN(val x: Float, val y: Float) {
    public operator fun plus(o: PointN): PointN = PointN(x + o.x, y + o.y)
    public operator fun minus(o: PointN): PointN = PointN(x - o.x, y - o.y)
    public fun clamped(): PointN = PointN(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
}

/** A rectangle normalised to 0..1. Used for OCR line boxes. */
@Serializable
public data class RectN(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Four page corners, normalised, always in the order top-left, top-right, bottom-right, bottom-left. */
@Serializable
public data class Quad(val tl: PointN, val tr: PointN, val br: PointN, val bl: PointN) {
    val corners: List<PointN> get() = listOf(tl, tr, br, bl)

    public fun with(corner: Corner, point: PointN): Quad = when (corner) {
        Corner.TOP_LEFT -> copy(tl = point)
        Corner.TOP_RIGHT -> copy(tr = point)
        Corner.BOTTOM_RIGHT -> copy(br = point)
        Corner.BOTTOM_LEFT -> copy(bl = point)
    }

    public operator fun get(corner: Corner): PointN = when (corner) {
        Corner.TOP_LEFT -> tl
        Corner.TOP_RIGHT -> tr
        Corner.BOTTOM_RIGHT -> br
        Corner.BOTTOM_LEFT -> bl
    }

    public companion object {
        public val FULL: Quad = Quad(PointN(0f, 0f), PointN(1f, 0f), PointN(1f, 1f), PointN(0f, 1f))
    }
}

public enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT }

/** Quarter-turn rotations, clockwise. */
public enum class Rotation(public val degrees: Int) {
    R0(0), R90(90), R180(180), R270(270);

    public fun clockwise(): Rotation = entries[(ordinal + 1) % 4]
    public fun counterClockwise(): Rotation = entries[(ordinal + 3) % 4]
    public val swapsAxes: Boolean get() = this == R90 || this == R270

    public companion object {
        public fun fromDegrees(degrees: Int): Rotation =
            entries.firstOrNull { it.degrees == ((degrees % 360) + 360) % 360 } ?: R0
    }
}

/** Pixel dimensions. */
public data class ImageSize(val width: Int, val height: Int) {
    val aspect: Float get() = width.toFloat() / height.toFloat()
    val longEdge: Int get() = maxOf(width, height)
    public fun rotated(rotation: Rotation): ImageSize = if (rotation.swapsAxes) ImageSize(height, width) else this
}
