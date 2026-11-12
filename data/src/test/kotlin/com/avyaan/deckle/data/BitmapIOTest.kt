package com.avyaan.deckle.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.avyaan.deckle.data.imaging.common.BitmapIO
import com.avyaan.deckle.domain.imaging.ImageInput
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real JPEG decoding (native graphics), as the camera hands it over: a file with an EXIF orientation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BitmapIOTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val io = BitmapIO(context)

    private fun cameraJpeg(width: Int, height: Int, orientation: Int): File {
        val file = File(context.cacheDir, "shot-$orientation.jpg")
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
        return file
    }

    @Test
    fun `decodes a camera file`() {
        val file = cameraJpeg(400, 300, ExifInterface.ORIENTATION_NORMAL)
        val bmp = io.decodeUpright(ImageInput.FilePath(file.path), maxLongEdge = 4000)
        assertThat(bmp).isNotNull()
        assertThat(bmp!!.width to bmp.height).isEqualTo(400 to 300)
    }

    @Test
    fun `applies EXIF rotation and the long-edge cap`() {
        val file = cameraJpeg(400, 300, ExifInterface.ORIENTATION_ROTATE_90)
        val bmp = io.decodeUpright(ImageInput.FilePath(file.path), maxLongEdge = 200)
        assertThat(bmp).isNotNull()
        assertThat(bmp!!.width to bmp.height).isEqualTo(150 to 200)
    }

    @Test
    fun `missing file is null, not a crash`() {
        assertThat(io.decodeUpright(ImageInput.FilePath(File(context.cacheDir, "nope.jpg").path), 4000)).isNull()
    }
}
