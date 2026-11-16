package com.avyaan.deckle.domain.geometry

/**
 * Steadies the live outline. Every camera frame is detected from scratch, so the raw quad shakes by a few
 * pixels and now and then jumps to a near-tied outline (a shadow, the table edge). Drawing that raw makes the
 * overlay crawl even when page and phone are still. This sits between the detector and everything that uses
 * the quad:
 * - moves smaller than [deadband] are noise and ignored;
 * - moves up to [jump] are followed smoothly ([smoothing] of the way each frame);
 * - a bigger jump is believed only once it repeats for [confirmFrames] frames, so one stray outline never shows;
 * - a page lost for up to [holdMissing] frames stays put rather than blinking out.
 *
 * Distances are fractions of frame width, as in [QuadGeometry.maxCornerDistance]. Not thread-safe: owned by
 * the capture ViewModel.
 */
public class QuadTracker(
    private val smoothing: Float = DetectionTuning.TRACK_SMOOTHING,
    private val deadband: Float = DetectionTuning.TRACK_DEADBAND,
    private val jump: Float = DetectionTuning.TRACK_JUMP,
    private val confirmFrames: Int = DetectionTuning.TRACK_CONFIRM_FRAMES,
    private val holdMissing: Int = DetectionTuning.TRACK_HOLD_MISSING_FRAMES,
) {
    private var shown: Quad? = null
    private var candidate: Quad? = null
    private var candidateFrames = 0
    private var missingFrames = 0

    /** Takes this frame's raw detection (null = none) and returns the quad to show and use. */
    public fun offer(raw: Quad?, aspect: Float): Quad? {
        if (raw == null) {
            clearCandidate()
            if (shown != null && ++missingFrames > holdMissing) shown = null
            return shown
        }
        missingFrames = 0
        val current = shown ?: return raw.also { shown = it }

        val distance = QuadGeometry.maxCornerDistance(current, raw, aspect)
        if (distance > jump) {
            // A different outline. Follow it as it moves, but switch only once it has lasted.
            val pending = candidate
            candidateFrames = if (pending != null && QuadGeometry.maxCornerDistance(pending, raw, aspect) <= jump) candidateFrames + 1 else 1
            candidate = raw
            if (candidateFrames >= confirmFrames) {
                shown = raw
                clearCandidate()
            }
            return shown
        }
        clearCandidate()
        if (distance < deadband) return current
        return blend(current, raw).also { shown = it }
    }

    public fun reset() {
        shown = null
        missingFrames = 0
        clearCandidate()
    }

    private fun clearCandidate() {
        candidate = null
        candidateFrames = 0
    }

    private fun blend(from: Quad, to: Quad): Quad {
        fun mix(a: PointN, b: PointN) = PointN(a.x + (b.x - a.x) * smoothing, a.y + (b.y - a.y) * smoothing)
        return Quad(mix(from.tl, to.tl), mix(from.tr, to.tr), mix(from.br, to.br), mix(from.bl, to.bl))
    }
}
