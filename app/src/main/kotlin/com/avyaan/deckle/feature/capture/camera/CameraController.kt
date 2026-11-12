package com.avyaan.deckle.feature.capture.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Rational
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.avyaan.deckle.domain.settings.FlashMode
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import android.util.Size as AndroidSize

/**
 * Owns CameraX binding for the capture screen (spec 03 §2). Our own class — not
 * `androidx.camera.view.CameraController` — because we need a shared [ViewPort] across three use cases
 * and full control over analysis.
 *
 * Every stream is 4:3 under one 4:3 ViewPort, so Preview, ImageAnalysis and ImageCapture see the same
 * field of view. That is what lets a live quad stand in for the still when still-lane detection fails.
 */
class CameraController(private val context: Context) {

    val previewView: PreviewView = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var analysis: ImageAnalysis? = null

    private val _zoom = MutableStateFlow(1f)
    val zoom: StateFlow<Float> = _zoom
    private val _hasFlash = MutableStateFlow(false)
    val hasFlash: StateFlow<Boolean> = _hasFlash

    private val orientation = object : OrientationEventListener(context) {
        override fun onOrientationChanged(degrees: Int) {
            if (degrees == ORIENTATION_UNKNOWN) return
            val rotation = when (degrees) {
                in 45 until 135 -> Surface.ROTATION_270
                in 135 until 225 -> Surface.ROTATION_180
                in 225 until 315 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
            // FR-16: stills and analysis follow the device; the activity itself isn't recreated (D-6).
            imageCapture?.targetRotation = rotation
            analysis?.targetRotation = rotation
        }
    }

    suspend fun bind(owner: LifecycleOwner, analyzer: ImageAnalysis.Analyzer, flash: FlashMode) {
        val provider = provider ?: awaitProvider().also { provider = it }
        val ratio = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        val preview = Preview.Builder().setResolutionSelector(ratio.build()).build()
            .also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(AndroidSize(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build(),
            )
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisExecutor, analyzer) }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(AndroidSize(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build(),
            )
            .setFlashMode(flash.toCameraX())
            .build()

        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val viewPort = ViewPort.Builder(Rational(3, 4), rotation).setScaleType(ViewPort.FILL_CENTER).build()
        val group = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(preview)
            .addUseCase(analysis)
            .addUseCase(capture)
            .build()

        provider.unbindAll()
        val camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group)
        this.camera = camera
        this.imageCapture = capture
        this.analysis = analysis
        _hasFlash.value = camera.cameraInfo.hasFlashUnit()
        _zoom.value = camera.cameraInfo.zoomState.value?.zoomRatio ?: 1f
        orientation.enable()
    }

    fun unbind() {
        orientation.disable()
        provider?.unbindAll()
        camera = null
        imageCapture = null
        analysis = null
    }

    fun release() {
        unbind()
        analysisExecutor.shutdown()
    }

    fun setFlash(mode: FlashMode) {
        imageCapture?.flashMode = mode.toCameraX()
    }

    /** FR-14 tap-to-focus: AF + AE at the tapped point, auto-cancel after 3 s. */
    fun focus(viewX: Float, viewY: Float) {
        val camera = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(viewX, viewY)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        camera.cameraControl.startFocusAndMetering(action)
    }

    /** Pinch-to-zoom, clamped to what the lens supports. */
    fun zoomBy(factor: Float) {
        val camera = camera ?: return
        val state = camera.cameraInfo.zoomState.value ?: return
        val target = (state.zoomRatio * factor).coerceIn(state.minZoomRatio, state.maxZoomRatio)
        _zoom.value = target
        camera.cameraControl.setZoomRatio(target)
    }

    /** The frame currently on screen, used as the freeze-frame while the still is processed (spec 03 §6.1). */
    fun snapshot(): Bitmap? = previewView.bitmap

    /**
     * Takes a still into a temp file. Writing to a file (rather than in-memory) is what makes CameraX
     * record the orientation in EXIF reliably; the normaliser then applies it.
     */
    suspend fun takePicture(): File {
        val capture = imageCapture ?: error("Camera not bound")
        val dir = File(context.cacheDir, "capture").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        return suspendCancellableCoroutine { cont ->
            capture.takePicture(
                options,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) = cont.resume(file)
                    override fun onError(exception: ImageCaptureException) {
                        file.delete()
                        cont.resumeWithException(exception)
                    }
                },
            )
        }
    }

    private suspend fun awaitProvider(): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(context))
    }

    private fun FlashMode.toCameraX(): Int = when (this) {
        FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
        FlashMode.ON -> ImageCapture.FLASH_MODE_ON
        FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
    }
}
