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

    /** FR-13: fire after the quad has been stable for 1.2 s. */
    public const val AUTO_CAPTURE_HOLD_MS: Long = 1_200L

    /** After an auto-capture, wait this long and for the page to change before arming again (UX addition). */
    public const val AUTO_CAPTURE_COOLDOWN_MS: Long = 1_500L

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
