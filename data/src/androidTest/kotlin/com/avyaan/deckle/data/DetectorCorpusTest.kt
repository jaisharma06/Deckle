package com.avyaan.deckle.data

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avyaan.deckle.data.imaging.engine.OpenCvStatus
import com.avyaan.deckle.data.imaging.opencv.OpenCvPageDetector
import com.avyaan.deckle.domain.fakes.NoopLogger
import com.avyaan.deckle.domain.geometry.Quad
import com.avyaan.deckle.domain.common.DispatcherProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.math.hypot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real detector (OpenCV, on the device) over a corpus of photos with known page corners and
 * reports the hit rate. The corpus lives in `assets/detector-private/` (git-ignored: built from personal
 * scans); without it the test is skipped. A hit = every corner within [TOLERANCE] of the truth.
 */
@RunWith(AndroidJUnit4::class)
class DetectorCorpusTest {

    @Serializable
    private data class Case(val file: String, val quad: List<List<Float>>)

    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
        override val imaging = Dispatchers.Unconfined
        override val preview = Dispatchers.Unconfined
    }

    @Test
    fun detectsPagesInTheCorpus() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val names = assets.list(DIR).orEmpty()
        assumeTrue("no private detector corpus on this machine", "cases.json" in names)
        OpenCvStatus.init()
        assertThat(OpenCvStatus.isAvailable).isTrue()

        val cases = Json.decodeFromString<List<Case>>(assets.open("$DIR/cases.json").bufferedReader().readText())
        val detector = OpenCvPageDetector(dispatchers, NoopLogger)
        val work = File(instrumentation.targetContext.cacheDir, "corpus").apply { mkdirs() }

        val misses = mutableListOf<String>()
        for (case in cases) {
            val file = File(work, case.file)
            assets.open("$DIR/${case.file}").use { input -> file.outputStream().use { input.copyTo(it) } }
            val found = detector.detect(file.path)
            val error = found?.let { maxCornerError(it, case.quad) }
            val hit = error != null && error <= TOLERANCE
            Log.i(TAG, "%-24s %s".format(case.file, if (found == null) "MISS (nothing found)" else if (hit) "hit  (err %.3f)".format(error) else "MISS (err %.3f)".format(error)))
            if (!hit) misses += case.file
        }
        val rate = (cases.size - misses.size).toFloat() / cases.size
        Log.i(TAG, "hit rate %d/%d = %.0f%%".format(cases.size - misses.size, cases.size, rate * 100))
        assertThat(rate).isAtLeast(MIN_HIT_RATE)
    }

    private fun maxCornerError(q: Quad, truth: List<List<Float>>): Float =
        q.corners.zip(truth).maxOf { (c, t) -> hypot(c.x - t[0], c.y - t[1]) }

    private companion object {
        const val DIR = "detector-private"
        const val TAG = "DetectorCorpus"
        const val TOLERANCE = 0.05f
        /** 15/15 when the detector was rewritten; the run's log shows each case. */
        const val MIN_HIT_RATE = 0.9f
    }
}
