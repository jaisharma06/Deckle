package com.avyaan.deckle.domain.geometry

import kotlin.math.atan2

/**
 * Orders four unordered points into TL, TR, BR, BL.
 *
 * This step is where most scanner implementations break (rotated or mirrored output), so it lives in the
 * domain and is tested exhaustively. Image coordinates: y grows downwards.
 */
public object CornerOrdering {

    public fun order(points: List<PointN>): Quad {
        require(points.size == 4) { "A quad needs exactly 4 points, got ${points.size}" }
        return bySumAndDifference(points) ?: byAngle(points)
    }

    /** TL = min(x+y), BR = max(x+y), TR = max(x−y), BL = min(x−y). Null when two roles collide. */
    private fun bySumAndDifference(p: List<PointN>): Quad? {
        val tl = p.minBy { it.x + it.y }
        val br = p.maxBy { it.x + it.y }
        val tr = p.maxBy { it.x - it.y }
        val bl = p.minBy { it.x - it.y }
        val distinct = setOf(tl, tr, br, bl).size == 4
        return if (distinct) Quad(tl, tr, br, bl) else null
    }

    /**
     * Fallback for degenerate cases such as a page rotated exactly 45°: walk clockwise around the centroid
     * (increasing atan2 is clockwise on screen because y points down), starting from the top-left-most point.
     */
    private fun byAngle(p: List<PointN>): Quad {
        val cx = p.sumOf { it.x.toDouble() } / 4
        val cy = p.sumOf { it.y.toDouble() } / 4
        val clockwise = p.sortedBy { atan2(it.y - cy, it.x - cx) }
        val start = clockwise.indexOf(clockwise.minBy { it.x + it.y })
        val r = List(4) { clockwise[(start + it) % 4] }
        return Quad(r[0], r[1], r[2], r[3])
    }
}
