package com.avyaan.deckle.domain.geometry

/** Every detection number from the requirements, named once. */
public object DetectionTuning {
    /**
     * Reject quads smaller than 7 % of the frame. The spec's 20 % missed receipts and pages held at arm's
     * length; the detector's edge-support check now does the job of rejecting stray shapes (measured on the
     * on-device corpus, see DetectorCorpusTest).
     */
    public const val MIN_AREA_FRACTION: Float = 0.07f

    /** Reject quads whose opposite sides differ by more than 4:1. */
    public const val MAX_OPPOSITE_SIDE_RATIO: Float = 4f

    /** approxPolyDP epsilon = 2 % of contour arc length; coarser steps are tried when that gives more than four corners. */
    public const val APPROX_EPSILON_FRACTION: Double = 0.02

    /** A detected outline must lie on image edges along at least this fraction of its length. */
    public const val MIN_EDGE_SUPPORT: Float = 0.55f

    /** Stability gate: last five detections… */
    public const val STABILITY_WINDOW: Int = 5

    /** …every corner within 2 % of frame width across all of them. */
    public const val STABILITY_TOLERANCE: Float = 0.02f

    /**
     * FR-13: fire after the quad has been stable this long. NV-14 cut it from 1.2 s to 0.8 s: the stability
     * gate already needs five steady frames (~0.3 s) before holding starts, so 0.8 s is ~1.1 s in total.
     */
    public const val AUTO_CAPTURE_HOLD_MS: Long = 800L

    /** After an auto-capture, wait this long and for the page to change before arming again (UX addition). */
    public const val AUTO_CAPTURE_COOLDOWN_MS: Long = 1_000L   // NV-14: was 1.5 s; the page must still change

    /** Live outline ([QuadTracker]): corner moves below this (≈3 px at 480 px) are detection noise. */
    public const val TRACK_DEADBAND: Float = 0.006f

    /** Live outline: share of the remaining distance covered per frame when following a small move. */
    public const val TRACK_SMOOTHING: Float = 0.35f

    /** Live outline: a move bigger than this is a different outline and must repeat before it shows. */
    public const val TRACK_JUMP: Float = 0.06f

    /** Live outline: frames a different outline must last before the overlay switches to it. */
    public const val TRACK_CONFIRM_FRAMES: Int = 3

    /** Live outline: frames without a page before the outline is dropped. */
    public const val TRACK_HOLD_MISSING_FRAMES: Int = 4

    /** A corner moving this far (fraction of frame width) means "a new page". */
    public const val NEW_PAGE_MOVEMENT: Float = 0.10f

    /** Live detection runs on a copy with this long edge. */
    public const val ANALYSIS_LONG_EDGE_PX: Int = 480

    /** Captured stills are downscaled so the long edge is at most this. */
    public const val MAX_SOURCE_LONG_EDGE_PX: Int = 3000

    /** Crop editor: smallest quad the user may make (prevents collapsing to a line). */
    public const val MIN_CROP_AREA_FRACTION: Float = 0.01f

    /** Crop editor: accessibility nudge step. */
    public const val NUDGE_STEP: Float = 0.02f
}
