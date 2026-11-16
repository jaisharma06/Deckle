package com.avyaan.deckle.domain.geometry

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The live outline must sit still on a still page, yet follow a real move. */
class QuadTrackerTest {
    private val aspect = 0.75f
    private val page = Quad(PointN(0.2f, 0.2f), PointN(0.8f, 0.2f), PointN(0.8f, 0.8f), PointN(0.2f, 0.8f))

    private fun Quad.shifted(dx: Float, dy: Float = 0f) =
        Quad(tl + PointN(dx, dy), tr + PointN(dx, dy), br + PointN(dx, dy), bl + PointN(dx, dy))

    @Test fun `frame-to-frame noise does not move the outline`() {
        val tracker = QuadTracker()
        val first = tracker.offer(page, aspect)
        val noise = listOf(0.002f, -0.002f, 0.001f, -0.001f, 0.002f, -0.002f)
        val shown = noise.map { tracker.offer(page.shifted(it, -it), aspect) }
        shown.forEach { assertThat(it).isEqualTo(first) }
    }

    @Test fun `a single stray outline is ignored`() {
        val tracker = QuadTracker()
        tracker.offer(page, aspect)
        val stray = Quad(PointN(0.05f, 0.4f), PointN(0.5f, 0.35f), PointN(0.55f, 0.95f), PointN(0.1f, 0.9f))
        assertThat(tracker.offer(stray, aspect)).isEqualTo(page)
        assertThat(tracker.offer(page, aspect)).isEqualTo(page)
    }

    @Test fun `a page that really moved is followed after a few frames`() {
        val tracker = QuadTracker()
        tracker.offer(page, aspect)
        val moved = page.shifted(0.15f)
        val shown = List(DetectionTuning.TRACK_CONFIRM_FRAMES) { tracker.offer(moved, aspect) }
        assertThat(shown.last()).isEqualTo(moved)
        assertThat(shown.first()).isEqualTo(page)
    }

    @Test fun `a small real move is followed smoothly and converges`() {
        val tracker = QuadTracker()
        tracker.offer(page, aspect)
        val target = page.shifted(0.03f)
        val firstStep = tracker.offer(target, aspect)!!
        assertThat(firstStep.tl.x).isGreaterThan(page.tl.x)
        assertThat(firstStep.tl.x).isLessThan(target.tl.x)
        var last = firstStep
        repeat(30) { last = tracker.offer(target, aspect)!! }
        assertThat(QuadGeometry.maxCornerDistance(last, target, aspect)).isAtMost(DetectionTuning.TRACK_DEADBAND)
    }

    @Test fun `brief dropouts keep the outline, a lasting loss clears it`() {
        val tracker = QuadTracker()
        tracker.offer(page, aspect)
        repeat(DetectionTuning.TRACK_HOLD_MISSING_FRAMES) { assertThat(tracker.offer(null, aspect)).isEqualTo(page) }
        assertThat(tracker.offer(null, aspect)).isNull()
    }

    @Test fun `reset forgets the page`() {
        val tracker = QuadTracker()
        tracker.offer(page, aspect)
        tracker.reset()
        assertThat(tracker.offer(null, aspect)).isNull()
        val elsewhere = page.shifted(0.15f)
        assertThat(tracker.offer(elsewhere, aspect)).isEqualTo(elsewhere)
    }
}
