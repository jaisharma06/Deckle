package com.avyaan.deckle.domain.geometry

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private fun p(x: Float, y: Float) = PointN(x, y)

class CornerOrderingTest {
    private val tl = p(0.1f, 0.1f)
    private val tr = p(0.9f, 0.15f)
    private val br = p(0.85f, 0.9f)
    private val bl = p(0.12f, 0.88f)

    @Test fun `orders any permutation of a normal page`() {
        val expected = Quad(tl, tr, br, bl)
        listOf(
            listOf(tl, tr, br, bl), listOf(br, bl, tl, tr), listOf(tr, tl, bl, br), listOf(bl, br, tr, tl),
        ).forEach { assertThat(CornerOrdering.order(it)).isEqualTo(expected) }
    }

    @Test fun `diamond at 45 degrees falls back to angular ordering and stays clockwise`() {
        val top = p(0.5f, 0.1f); val right = p(0.9f, 0.5f); val bottom = p(0.5f, 0.9f); val left = p(0.1f, 0.5f)
        val q = CornerOrdering.order(listOf(bottom, left, top, right))
        assertThat(QuadGeometry.isConvex(q)).isTrue()
        // Clockwise on screen: each next corner is reached by turning right.
        val c = q.corners
        val idx = c.indexOf(top)
        assertThat(c[(idx + 1) % 4]).isEqualTo(right)
        assertThat(c[(idx + 2) % 4]).isEqualTo(bottom)
    }

    @Test fun `rejects wrong point count`() {
        assertThrows<IllegalArgumentException> { CornerOrdering.order(listOf(tl, tr, br)) }
    }
}

class QuadRulesTest {
    @Test fun `full frame is a plausible page`() {
        assertThat(QuadRules.isPlausiblePage(Quad.FULL, aspect = 0.75f)).isTrue()
    }

    @Test fun `quad smaller than 7 percent is rejected`() {
        val small = Quad(p(0.4f, 0.4f), p(0.6f, 0.4f), p(0.6f, 0.6f), p(0.4f, 0.6f)) // 4 %
        assertThat(QuadRules.isPlausiblePage(small, 1f)).isFalse()
    }

    @Test fun `area boundary at exactly 7 percent is accepted`() {
        val q = Quad(p(0f, 0f), p(0.25f, 0f), p(0.25f, 0.28f), p(0f, 0.28f)) // 0.07
        assertThat(QuadGeometry.areaFraction(q)).isWithin(1e-6f).of(0.07f)
        assertThat(QuadRules.isPlausiblePage(q, 1f)).isTrue()
    }

    @Test fun `opposite sides beyond 4 to 1 are rejected`() {
        // top side 1.0 wide, bottom side 0.2 wide → 5:1
        val q = Quad(p(0f, 0f), p(1f, 0f), p(0.6f, 1f), p(0.4f, 1f))
        assertThat(QuadGeometry.maxOppositeSideRatio(q, 1f)).isGreaterThan(4f)
        assertThat(QuadRules.isPlausiblePage(q, 1f)).isFalse()
    }

    @Test fun `self-intersecting bow-tie is not convex`() {
        val bowTie = Quad(p(0f, 0f), p(1f, 1f), p(1f, 0f), p(0f, 1f))
        assertThat(QuadGeometry.isConvex(bowTie)).isFalse()
    }

    @Test fun `output size uses the longer of each pair of opposite sides`() {
        val q = Quad(p(0f, 0f), p(1f, 0f), p(0.9f, 1f), p(0.1f, 1f))
        val size = QuadGeometry.outputSize(q, ImageSize(1000, 2000))
        assertThat(size.width).isEqualTo(1000)
        // Left/right sides are slanted: hypot(0.1 × 1000, 1 × 2000) ≈ 2002.5 px.
        assertThat(size.height).isEqualTo(2002)
    }
}

class QuadRotationTest {
    private val q = Quad(p(0.1f, 0.2f), p(0.8f, 0.1f), p(0.9f, 0.9f), p(0.2f, 0.8f))

    @Test fun `four clockwise turns are the identity`() {
        var r = q
        repeat(4) { r = QuadRotation.rotate(r, Rotation.R90) }
        assertThat(r.corners.zip(q.corners).all { (a, b) -> kotlin.math.abs(a.x - b.x) < 1e-6 && kotlin.math.abs(a.y - b.y) < 1e-6 }).isTrue()
    }

    @Test fun `rotated quad keeps corner roles meaningful`() {
        val r = QuadRotation.rotate(Quad.FULL, Rotation.R90)
        assertThat(r).isEqualTo(Quad.FULL)
        val rotated = QuadRotation.rotate(q, Rotation.R90)
        assertThat(CornerOrdering.order(rotated.corners)).isEqualTo(rotated)
    }

    @Test fun `rect rotation keeps left less than right`() {
        val r = QuadRotation.rotate(RectN(0.1f, 0.2f, 0.4f, 0.3f), Rotation.R90)
        assertThat(r.left).isLessThan(r.right)
        assertThat(r.top).isLessThan(r.bottom)
        assertThat(r.left).isWithin(1e-6f).of(0.7f)
        assertThat(r.top).isWithin(1e-6f).of(0.1f)
    }
}

class StabilityGateTest {
    private val base = Quad(p(0.1f, 0.1f), p(0.9f, 0.1f), p(0.9f, 0.9f), p(0.1f, 0.9f))
    private fun shifted(dx: Float) = Quad(base.tl + p(dx, 0f), base.tr + p(dx, 0f), base.br + p(dx, 0f), base.bl + p(dx, 0f))

    @Test fun `stable only after five steady detections`() {
        val gate = StabilityGate()
        repeat(4) { assertThat(gate.offer(base, 1f)).isFalse() }
        assertThat(gate.offer(shifted(0.005f), 1f)).isTrue()
    }

    @Test fun `movement of 2 percent or more breaks stability`() {
        val gate = StabilityGate()
        repeat(4) { gate.offer(base, 1f) }
        assertThat(gate.offer(shifted(0.025f), 1f)).isFalse()
    }

    @Test fun `a missed detection clears the window`() {
        val gate = StabilityGate()
        repeat(5) { gate.offer(base, 1f) }
        assertThat(gate.offer(null, 1f)).isFalse()
        assertThat(gate.offer(base, 1f)).isFalse()
    }
}

class AutoCaptureMachineTest {
    private val quad = Quad(p(0.1f, 0.1f), p(0.9f, 0.1f), p(0.9f, 0.9f), p(0.1f, 0.9f))
    private fun det(q: Quad?, stable: Boolean) = AutoCaptureInput.Detection(q, stable, aspect = 0.75f)
    private fun next(s: AutoCaptureState, i: AutoCaptureInput, now: Long, auto: Boolean = true) =
        AutoCaptureMachine.next(s, i, now, auto)

    @Nested inner class Transitions {
        @Test fun `searching to tracking to holding`() {
            val t = next(AutoCaptureState.Searching, det(quad, false), 0)
            assertThat(t).isInstanceOf(AutoCaptureState.Tracking::class.java)
            val h = next(t, det(quad, true), 100)
            assertThat(h).isEqualTo(AutoCaptureState.Holding(quad, 100))
        }

        @Test fun `holding keeps its start time`() {
            val h = next(AutoCaptureState.Holding(quad, 100), det(quad, true), 900)
            assertThat((h as AutoCaptureState.Holding).sinceMs).isEqualTo(100)
            assertThat(h.progress(700)).isWithin(1e-3f).of(0.5f)
        }

        @Test fun `fires after exactly 1200 ms when auto is on`() {
            assertThat(next(AutoCaptureState.Holding(quad, 0), det(quad, true), 1_199))
                .isInstanceOf(AutoCaptureState.Holding::class.java)
            assertThat(next(AutoCaptureState.Holding(quad, 0), det(quad, true), 1_200))
                .isEqualTo(AutoCaptureState.Firing(quad))
        }

        @Test fun `never fires when auto is off`() {
            assertThat(next(AutoCaptureState.Holding(quad, 0), det(quad, true), 10_000, auto = false))
                .isInstanceOf(AutoCaptureState.Holding::class.java)
        }

        @Test fun `losing stability goes back to tracking, losing the page to searching`() {
            assertThat(next(AutoCaptureState.Holding(quad, 0), det(quad, false), 500))
                .isEqualTo(AutoCaptureState.Tracking(quad))
            assertThat(next(AutoCaptureState.Holding(quad, 0), det(null, false), 500))
                .isEqualTo(AutoCaptureState.Searching)
        }

        @Test fun `firing waits for capture started`() {
            val f = AutoCaptureState.Firing(quad)
            assertThat(next(f, det(null, false), 10)).isEqualTo(f)
            assertThat(next(f, AutoCaptureInput.CaptureStarted, 20)).isInstanceOf(AutoCaptureState.Cooldown::class.java)
        }
    }

    @Nested inner class Cooldown {
        private val cooling = AutoCaptureState.Cooldown(quad, quad, sinceMs = 0, pageChanged = false)

        @Test fun `same page never re-arms even after the cooldown time`() {
            val s = next(cooling, det(quad, true), 5_000)
            assertThat(s).isInstanceOf(AutoCaptureState.Cooldown::class.java)
        }

        @Test fun `page removed then time elapsed re-arms`() {
            val gone = next(cooling, det(null, false), 500)
            assertThat((gone as AutoCaptureState.Cooldown).pageChanged).isTrue()
            assertThat(next(gone, det(quad, false), 1_600)).isEqualTo(AutoCaptureState.Tracking(quad))
        }

        @Test fun `page changed but too soon stays in cooldown`() {
            val s = next(cooling, det(null, false), 1_000)
            assertThat(s).isInstanceOf(AutoCaptureState.Cooldown::class.java)
        }
    }
}

class CropConstraintTest {
    private val q = Quad(p(0.1f, 0.1f), p(0.9f, 0.1f), p(0.9f, 0.9f), p(0.1f, 0.9f))

    @Test fun `corner drag moves the corner`() {
        val m = CropConstraint.drag(q, CropHandle.CornerHandle(Corner.TOP_LEFT), p(0.05f, 0.05f))
        assertThat(m.rejected).isFalse()
        assertThat(m.quad.tl).isEqualTo(p(0.15f, 0.15f))
    }

    @Test fun `corner is clamped to image bounds`() {
        val m = CropConstraint.drag(q, CropHandle.CornerHandle(Corner.TOP_LEFT), p(-1f, -1f))
        assertThat(m.quad.tl).isEqualTo(p(0f, 0f))
    }

    @Test fun `crossing over the opposite corner is rejected and keeps the last valid quad`() {
        val m = CropConstraint.drag(q, CropHandle.CornerHandle(Corner.TOP_LEFT), p(0.85f, 0.85f))
        assertThat(m.rejected).isTrue()
        assertThat(m.quad).isEqualTo(q)
    }

    @Test fun `edge drag moves both corners along the normal only`() {
        val m = CropConstraint.drag(q, CropHandle.EdgeHandle(Edge.TOP), p(0.3f, 0.05f))
        assertThat(m.rejected).isFalse()
        assertThat(m.quad.tl.x).isWithin(1e-6f).of(0.1f)  // sideways component ignored
        assertThat(m.quad.tl.y).isWithin(1e-6f).of(0.15f)
        assertThat(m.quad.tr.y).isWithin(1e-6f).of(0.15f)
    }

    @Test fun `nudge moves by the accessibility step`() {
        val m = CropConstraint.nudge(q, Corner.BOTTOM_RIGHT, NudgeDirection.UP)
        assertThat(m.quad.br.y).isWithin(1e-6f).of(0.9f - DetectionTuning.NUDGE_STEP)
    }
}
