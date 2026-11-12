package com.avyaan.deckle.domain.geometry

import kotlin.math.hypot

/** What the user is dragging in the crop editor: a corner, or a mid-edge handle that moves a whole side. */
public sealed interface CropHandle {
    public data class CornerHandle(val corner: Corner) : CropHandle
    public data class EdgeHandle(val edge: Edge) : CropHandle
}

public enum class Edge(public val a: Corner, public val b: Corner) {
    TOP(Corner.TOP_LEFT, Corner.TOP_RIGHT),
    RIGHT(Corner.TOP_RIGHT, Corner.BOTTOM_RIGHT),
    BOTTOM(Corner.BOTTOM_RIGHT, Corner.BOTTOM_LEFT),
    LEFT(Corner.BOTTOM_LEFT, Corner.TOP_LEFT),
}

public enum class NudgeDirection(public val dx: Float, public val dy: Float) {
    UP(0f, -1f), DOWN(0f, 1f), LEFT(-1f, 0f), RIGHT(1f, 0f)
}

/** Result of a crop edit: the quad to show, and whether the requested move was refused. */
public data class CropMove(val quad: Quad, val rejected: Boolean)

/**
 * FR-22: handles stay inside the image and can never form a self-intersecting (or concave) shape.
 * An invalid move keeps the last valid quad and reports `rejected = true` so the UI can shake.
 */
public object CropConstraint {

    public fun drag(quad: Quad, handle: CropHandle, delta: PointN): CropMove = when (handle) {
        is CropHandle.CornerHandle -> moveCorner(quad, handle.corner, delta)
        is CropHandle.EdgeHandle -> moveEdge(quad, handle.edge, delta)
    }

    public fun nudge(quad: Quad, corner: Corner, direction: NudgeDirection): CropMove {
        val step = DetectionTuning.NUDGE_STEP
        return moveCorner(quad, corner, PointN(direction.dx * step, direction.dy * step))
    }

    private fun moveCorner(quad: Quad, corner: Corner, delta: PointN): CropMove {
        val candidate = quad.with(corner, (quad[corner] + delta).clamped())
        return accept(quad, candidate)
    }

    /** Moves both corners of an edge along the edge's normal, so dragging a side feels like pushing it. */
    private fun moveEdge(quad: Quad, edge: Edge, delta: PointN): CropMove {
        val a = quad[edge.a]
        val b = quad[edge.b]
        val ex = b.x - a.x
        val ey = b.y - a.y
        val len = hypot(ex, ey)
        if (len < 1e-6f) return CropMove(quad, rejected = true)
        val nx = -ey / len
        val ny = ex / len
        val along = delta.x * nx + delta.y * ny
        val move = PointN(nx * along, ny * along)
        val candidate = quad
            .with(edge.a, (a + move).clamped())
            .with(edge.b, (b + move).clamped())
        return accept(quad, candidate)
    }

    private fun accept(previous: Quad, candidate: Quad): CropMove =
        if (QuadRules.isValidCrop(candidate)) CropMove(candidate, rejected = false)
        else CropMove(previous, rejected = true)
}
