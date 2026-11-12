package com.avyaan.deckle.domain.geometry

/**
 * Live-detection state (FR-13 and the stability gate), as a sealed state machine.
 *
 * Why a state machine instead of booleans: `isStable`, `isCapturing`, `hasFired` allow illegal combinations
 * (e.g. firing while unstable). Here every state is legal by construction and [AutoCaptureMachine.next] is a
 * pure function, tested as a transition table.
 */
public sealed interface AutoCaptureState {
    /** The quad to draw, if any. */
    public val quad: Quad?

    public data object Searching : AutoCaptureState {
        override val quad: Quad? get() = null
    }

    /** A page is visible but still moving. */
    public data class Tracking(override val quad: Quad) : AutoCaptureState

    /** Stable since [sinceMs]; with auto-capture on, fires after the hold time. */
    public data class Holding(override val quad: Quad, val sinceMs: Long) : AutoCaptureState {
        public fun progress(nowMs: Long): Float =
            ((nowMs - sinceMs).toFloat() / DetectionTuning.AUTO_CAPTURE_HOLD_MS).coerceIn(0f, 1f)
    }

    /** Auto-capture decided to fire. The capture layer takes the picture and reports back. */
    public data class Firing(override val quad: Quad) : AutoCaptureState

    /** Just captured; won't fire again until time has passed *and* the page has changed. */
    public data class Cooldown(
        override val quad: Quad?,
        val capturedQuad: Quad,
        val sinceMs: Long,
        val pageChanged: Boolean,
    ) : AutoCaptureState
}

public sealed interface AutoCaptureInput {
    /** One detection result from the live lane. [stable] comes from [StabilityGate]. */
    public data class Detection(val quad: Quad?, val stable: Boolean, val aspect: Float) : AutoCaptureInput

    /** A picture was taken (manually or because we fired). */
    public data object CaptureStarted : AutoCaptureInput
}

public object AutoCaptureMachine {

    public fun next(
        state: AutoCaptureState,
        input: AutoCaptureInput,
        nowMs: Long,
        autoCaptureEnabled: Boolean,
    ): AutoCaptureState = when (input) {
        AutoCaptureInput.CaptureStarted ->
            AutoCaptureState.Cooldown(state.quad, state.quad ?: Quad.FULL, nowMs, pageChanged = state.quad == null)
        is AutoCaptureInput.Detection -> onDetection(state, input, nowMs, autoCaptureEnabled)
    }

    private fun onDetection(
        state: AutoCaptureState,
        d: AutoCaptureInput.Detection,
        nowMs: Long,
        auto: Boolean,
    ): AutoCaptureState {
        if (state is AutoCaptureState.Firing) return state          // waiting for CaptureStarted
        if (state is AutoCaptureState.Cooldown) return cooldown(state, d, nowMs)

        val quad = d.quad ?: return AutoCaptureState.Searching
        if (!d.stable) return AutoCaptureState.Tracking(quad)

        val since = (state as? AutoCaptureState.Holding)?.sinceMs ?: nowMs
        val held = nowMs - since >= DetectionTuning.AUTO_CAPTURE_HOLD_MS
        return if (auto && held) AutoCaptureState.Firing(quad) else AutoCaptureState.Holding(quad, since)
    }

    private fun cooldown(s: AutoCaptureState.Cooldown, d: AutoCaptureInput.Detection, nowMs: Long): AutoCaptureState {
        val changed = s.pageChanged || d.quad == null ||
            QuadGeometry.maxCornerDistance(d.quad, s.capturedQuad, d.aspect) > DetectionTuning.NEW_PAGE_MOVEMENT
        val elapsed = nowMs - s.sinceMs >= DetectionTuning.AUTO_CAPTURE_COOLDOWN_MS
        return if (changed && elapsed) {
            d.quad?.let(AutoCaptureState::Tracking) ?: AutoCaptureState.Searching
        } else {
            s.copy(quad = d.quad, pageChanged = changed)
        }
    }
}
