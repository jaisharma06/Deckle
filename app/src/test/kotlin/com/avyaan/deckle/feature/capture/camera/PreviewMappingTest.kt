package com.avyaan.deckle.feature.capture.camera

import com.avyaan.deckle.domain.geometry.PointN
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PreviewMappingTest {

    @Test
    fun `same aspect maps corners to view corners`() {
        val m = PreviewMapping(viewWidth = 300f, viewHeight = 400f, imageWidth = 480f, imageHeight = 640f)
        assertThat(m.toViewX(PointN(0f, 0f))).isWithin(0.01f).of(0f)
        assertThat(m.toViewY(PointN(1f, 1f))).isWithin(0.01f).of(400f)
        assertThat(m.toViewX(PointN(1f, 1f))).isWithin(0.01f).of(300f)
    }

    @Test
    fun `taller view crops the image sides (FILL_CENTER)`() {
        // 3:4 image in a 9:16 view: the image is scaled to the view height and cropped left/right.
        val m = PreviewMapping(viewWidth = 900f, viewHeight = 1600f, imageWidth = 480f, imageHeight = 640f)
        val scale = 1600f / 640f
        val offsetX = (900f - 480f * scale) / 2f
        assertThat(m.toViewX(PointN(0f, 0f))).isWithin(0.01f).of(offsetX)
        assertThat(offsetX).isLessThan(0f)
        assertThat(m.toViewY(PointN(0f, 0.5f))).isWithin(0.01f).of(800f)
        // Centre stays centre.
        assertThat(m.toViewX(PointN(0.5f, 0.5f))).isWithin(0.01f).of(450f)
    }

    @Test
    fun `wider view crops top and bottom`() {
        val m = PreviewMapping(viewWidth = 1000f, viewHeight = 1000f, imageWidth = 480f, imageHeight = 640f)
        assertThat(m.toViewY(PointN(0f, 0f))).isLessThan(0f)
        assertThat(m.toViewX(PointN(0f, 0f))).isWithin(0.01f).of(0f)
    }

    @Test
    fun `toNormalized inverts toView`() {
        val m = PreviewMapping(900f, 1600f, 480f, 640f)
        val p = PointN(0.27f, 0.81f)
        val back = m.toNormalized(m.toViewX(p), m.toViewY(p))
        assertThat(back.x).isWithin(1e-4f).of(p.x)
        assertThat(back.y).isWithin(1e-4f).of(p.y)
    }

    @Test
    fun `upright size swaps axes for quarter turns`() {
        assertThat(PreviewMapping.uprightSize(640, 480, 90)).isEqualTo(480 to 640)
        assertThat(PreviewMapping.uprightSize(640, 480, 270)).isEqualTo(480 to 640)
        assertThat(PreviewMapping.uprightSize(640, 480, 180)).isEqualTo(640 to 480)
        assertThat(PreviewMapping.uprightSize(640, 480, 0)).isEqualTo(640 to 480)
    }
}
