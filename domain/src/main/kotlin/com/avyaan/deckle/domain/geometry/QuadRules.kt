package com.avyaan.deckle.domain.geometry

/**
 * Decides whether a detected four-point contour is plausibly a page (requirements: "Quad detection").
 * Called by the OpenCV adapter for each candidate; kept here so the rules are JVM-testable.
 */
public object QuadRules {

    public fun isPlausiblePage(q: Quad, aspect: Float): Boolean =
        QuadGeometry.isConvex(q) &&
            QuadGeometry.areaFraction(q) >= DetectionTuning.MIN_AREA_FRACTION &&
            QuadGeometry.maxOppositeSideRatio(q, aspect) <= DetectionTuning.MAX_OPPOSITE_SIDE_RATIO

    /** Looser rule for user-edited crops: convex, inside the image, and not collapsed. */
    public fun isValidCrop(q: Quad): Boolean =
        q.corners.all { it.x in 0f..1f && it.y in 0f..1f } &&
            QuadGeometry.isConvex(q) &&
            QuadGeometry.areaFraction(q) >= DetectionTuning.MIN_CROP_AREA_FRACTION
}
