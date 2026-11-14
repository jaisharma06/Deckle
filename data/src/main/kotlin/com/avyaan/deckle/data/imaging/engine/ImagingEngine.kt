package com.avyaan.deckle.data.imaging.engine

import com.avyaan.deckle.data.imaging.framework.FrameworkBookSplitter
import com.avyaan.deckle.data.imaging.framework.FrameworkPageEnhancer
import com.avyaan.deckle.data.imaging.framework.FrameworkPerspectiveCorrector
import com.avyaan.deckle.data.imaging.framework.NoOpPageDetector
import com.avyaan.deckle.data.imaging.opencv.OpenCvBookSplitter
import com.avyaan.deckle.data.imaging.opencv.OpenCvPageDetector
import com.avyaan.deckle.data.imaging.opencv.OpenCvPageEnhancer
import com.avyaan.deckle.data.imaging.opencv.OpenCvPerspectiveCorrector
import com.avyaan.deckle.data.imaging.opencv.OpenCvSharpnessMeter
import com.avyaan.deckle.domain.imaging.BookSplitter
import com.avyaan.deckle.domain.imaging.PageDetector
import com.avyaan.deckle.domain.imaging.PageEnhancer
import com.avyaan.deckle.domain.imaging.PerspectiveCorrector
import com.avyaan.deckle.domain.imaging.SharpnessMeter
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Abstract Factory (D-5). Why: if OpenCV fails to load, detector, warp and filters must *all* switch to
 * non-OpenCV implementations together — mixing families would call into an unloaded native library.
 * One factory, chosen once at startup, guarantees a consistent family.
 * Consumers never see this type: Hilt exposes only the narrow ports it produces.
 */
public interface ImagingEngine {
    public val kind: Kind
    public fun detector(): PageDetector
    public fun corrector(): PerspectiveCorrector
    public fun enhancer(): PageEnhancer
    public fun sharpness(): SharpnessMeter
    public fun books(): BookSplitter

    public enum class Kind { OPENCV, FRAMEWORK }
}

public class OpenCvEngine @Inject constructor(
    private val detector: Provider<OpenCvPageDetector>,
    private val corrector: Provider<OpenCvPerspectiveCorrector>,
    private val enhancer: Provider<OpenCvPageEnhancer>,
    private val sharpness: Provider<OpenCvSharpnessMeter>,
    private val books: Provider<OpenCvBookSplitter>,
) : ImagingEngine {
    override val kind: ImagingEngine.Kind = ImagingEngine.Kind.OPENCV
    override fun detector(): PageDetector = detector.get()
    override fun corrector(): PerspectiveCorrector = corrector.get()
    override fun enhancer(): PageEnhancer = enhancer.get()
    override fun sharpness(): SharpnessMeter = sharpness.get()
    override fun books(): BookSplitter = books.get()
}

public class FrameworkEngine @Inject constructor(
    private val detector: Provider<NoOpPageDetector>,
    private val corrector: Provider<FrameworkPerspectiveCorrector>,
    private val enhancer: Provider<FrameworkPageEnhancer>,
    private val books: Provider<FrameworkBookSplitter>,
) : ImagingEngine {
    override val kind: ImagingEngine.Kind = ImagingEngine.Kind.FRAMEWORK
    override fun detector(): PageDetector = detector.get()
    override fun corrector(): PerspectiveCorrector = corrector.get()
    override fun enhancer(): PageEnhancer = enhancer.get()
    /** Degraded mode: no blur warnings rather than unreliable ones. */
    override fun sharpness(): SharpnessMeter = SharpnessMeter { null }
    override fun books(): BookSplitter = books.get()
}

/** `Provider`s ensure the OpenCV engine is never even constructed when the native library is missing. */
@Singleton
public class EngineSelector @Inject constructor(
    private val opencv: Provider<OpenCvEngine>,
    private val framework: Provider<FrameworkEngine>,
) {
    public val engine: ImagingEngine by lazy { if (OpenCvStatus.isAvailable) opencv.get() else framework.get() }
}
