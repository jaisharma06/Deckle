package com.avyaan.deckle.domain.geometry

/**
 * Holds the last five detections and reports "stable" only when every corner has moved less than 2 % of
 * the frame width across all of them. This is what stops the overlay flickering between a page and a shadow.
 *
 * Not thread-safe: owned by one caller (the capture ViewModel).
 */
public class StabilityGate(
    private val window: Int = DetectionTuning.STABILITY_WINDOW,
    private val tolerance: Float = DetectionTuning.STABILITY_TOLERANCE,
) {
    private val recent = ArrayDeque<Quad>(window)

    /** Adds a detection (null = nothing found, which clears the window) and returns whether it is now stable. */
    public fun offer(quad: Quad?, aspect: Float): Boolean {
        if (quad == null) {
            recent.clear()
            return false
        }
        if (recent.size == window) recent.removeFirst()
        recent.addLast(quad)
        return isStable(aspect)
    }

    public fun reset(): Unit = recent.clear()

    private fun isStable(aspect: Float): Boolean {
        if (recent.size < window) return false
        for (i in 0 until recent.size) {
            for (j in i + 1 until recent.size) {
                if (QuadGeometry.maxCornerDistance(recent[i], recent[j], aspect) >= tolerance) return false
            }
        }
        return true
    }
}
