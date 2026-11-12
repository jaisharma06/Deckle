package com.avyaan.deckle.domain.geometry

/**
 * Rotates normalised geometry along with its image, in quarter turns clockwise.
 * Used to rotate OCR boxes when a page is rotated (no re-OCR needed) and to rotate a crop quad
 * stored against the unrotated source.
 */
public object QuadRotation {

    /** One clockwise quarter turn: (x, y) → (1 − y, x). */
    public fun rotateCw(p: PointN): PointN = PointN(1f - p.y, p.x)

    public fun rotate(p: PointN, rotation: Rotation): PointN {
        var r = p
        repeat(rotation.ordinal) { r = rotateCw(r) }
        return r
    }

    /** Rotating moves corner roles too: after one clockwise turn the old bottom-left becomes the top-left. */
    public fun rotate(q: Quad, rotation: Rotation): Quad {
        var r = q
        repeat(rotation.ordinal) {
            r = Quad(tl = rotateCw(r.bl), tr = rotateCw(r.tl), br = rotateCw(r.tr), bl = rotateCw(r.br))
        }
        return r
    }

    public fun rotate(rect: RectN, rotation: Rotation): RectN {
        val a = rotate(PointN(rect.left, rect.top), rotation)
        val b = rotate(PointN(rect.right, rect.bottom), rotation)
        return RectN(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
    }
}
