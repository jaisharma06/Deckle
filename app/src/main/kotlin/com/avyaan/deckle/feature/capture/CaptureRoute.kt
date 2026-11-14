package com.avyaan.deckle.feature.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.avyaan.deckle.R
import com.avyaan.deckle.designsystem.components.ConfirmDialog
import com.avyaan.deckle.designsystem.components.FocusRing
import com.avyaan.deckle.designsystem.components.HintChip
import com.avyaan.deckle.designsystem.components.ModeToggle
import com.avyaan.deckle.designsystem.components.QuadOverlay
import com.avyaan.deckle.designsystem.components.QuadStyle
import com.avyaan.deckle.designsystem.components.ShutterButton
import com.avyaan.deckle.designsystem.components.ThumbStack
import com.avyaan.deckle.designsystem.icons.DeckleIcons
import com.avyaan.deckle.designsystem.theme.CameraColors
import com.avyaan.deckle.designsystem.theme.CameraTheme
import com.avyaan.deckle.designsystem.theme.DeckleMotion
import com.avyaan.deckle.designsystem.theme.LocalReducedMotion
import com.avyaan.deckle.designsystem.theme.Spacing
import com.avyaan.deckle.designsystem.theme.rememberHaptics
import com.avyaan.deckle.domain.geometry.AutoCaptureState
import com.avyaan.deckle.domain.settings.FlashMode
import com.avyaan.deckle.feature.capture.camera.CameraController
import com.avyaan.deckle.feature.capture.camera.DetectionAnalyzer
import com.avyaan.deckle.feature.capture.camera.PreviewMapping
import com.avyaan.deckle.ui.ObserveEffects
import com.avyaan.deckle.ui.SecureWindow
import com.avyaan.deckle.ui.findActivity
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CaptureRoute(
    onClose: () -> Unit,
    onOpenCrop: (sessionId: String, captureId: String, imported: Boolean) -> Unit,
    onOpenReview: (sessionId: String) -> Unit,
    vm: CaptureViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasCameraPermission()) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        val activity = context.findActivity()
        permanentlyDenied = !ok && activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
    }
    // Returning from system settings: swap to the camera with a fade, no restart (FR-17).
    LifecycleResumeEffect(Unit) {
        granted = context.hasCameraPermission()
        onPauseOrDispose { }
    }

    val state by vm.state.collectAsStateWithLifecycle()
    SecureWindow(state.settings.secureScreens)

    CameraTheme {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AnimatedContent(granted, transitionSpec = { fadeIn(tween(DeckleMotion.MEDIUM)) togetherWith fadeOut(tween(DeckleMotion.SHORT)) }, label = "perm") { ok ->
                if (ok) {
                    CaptureScreen(vm, onClose, onOpenCrop, onOpenReview)
                } else {
                    CameraPermissionScreen(
                        permanentlyDenied = permanentlyDenied,
                        onRequest = { launcher.launch(Manifest.permission.CAMERA) },
                        onOpenSettings = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                        onClose = { vm.onEvent(CaptureEvent.Close) },
                    )
                }
            }
        }
    }
    // Exit effects are handled here too so Close works from the permission screen.
    if (!granted) {
        ObserveEffects(vm.effects) { if (it is CaptureEffect.Exit) onClose() }
    }
}

@Composable
private fun CaptureScreen(
    vm: CaptureViewModel,
    onClose: () -> Unit,
    onOpenCrop: (String, String, Boolean) -> Unit,
    onOpenReview: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val density = LocalDensity.current

    val controller = remember { CameraController(context) }
    DisposableEffect(controller) { onDispose { controller.release() } }
    val zoom by controller.zoom.collectAsStateWithLifecycle()
    val hasFlash by controller.hasFlash.collectAsStateWithLifecycle()

    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusKey by remember { mutableIntStateOf(0) }
    var zoomVisibleKey by remember { mutableIntStateOf(0) }
    var showZoom by remember { mutableStateOf(false) }
    val flashOverlay = remember { Animatable(0f) }
    var flying by remember { mutableStateOf<ImageBitmap?>(null) }
    val flight = remember { Animatable(0f) }
    var confirmDiscard by remember { mutableStateOf<Int?>(null) }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_IMPORT)) { uris ->
        vm.onEvent(CaptureEvent.Imported(uris.map(Uri::toString)))
    }

    // Bind while STARTED; unbind on stop (spec 03 §2 lifecycle).
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val analyzer = DetectionAnalyzer(vm.detector) { vm.onEvent(CaptureEvent.Detection(it)) }
            try {
                controller.bind(lifecycleOwner, analyzer, vm.state.value.settings.flash)
                awaitCancellation()
            } finally {
                controller.unbind()
            }
        }
    }
    LaunchedEffect(state.settings.flash) { controller.setFlash(state.settings.flash) }
    val shutter = rememberShutterSound()

    // "Page detected" for TalkBack when the outline settles (spec 05 §8).
    val stable = state.detection is AutoCaptureState.Holding
    val pageDetected = stringResource(R.string.capture_page_detected)
    LaunchedEffect(stable) { if (stable) view.announceForAccessibility(pageDetected) }

    ObserveEffects(vm.effects) { effect ->
        when (effect) {
            CaptureEffect.TakePicture -> {
                haptics.confirm()
                shutter.play(vm.state.value.settings.shutterSound)
                scope.launch {
                    flashOverlay.snapTo(0.6f)
                    flashOverlay.animateTo(0f, tween(DeckleMotion.SHORT))
                }
                val frame = controller.snapshot()?.asImageBitmap()
                if (vm.state.value.settings.batchMode) {
                    if (!reduced && frame != null) scope.launch {
                        flying = frame
                        flight.snapTo(0f)
                        flight.animateTo(1f, tween(DeckleMotion.DELIBERATE, easing = DeckleMotion.Standard))
                        flying = null
                    }
                } else {
                    FrozenFrame.put(frame)
                }
                runCatching { controller.takePicture() }
                    .onSuccess { vm.onEvent(CaptureEvent.PictureTaken(it.path)) }
                    .onFailure { vm.onEvent(CaptureEvent.PictureFailed) }
            }
            is CaptureEffect.OpenCrop -> onOpenCrop(effect.sessionId, effect.captureId, effect.imported)
            is CaptureEffect.OpenReview -> onOpenReview(effect.sessionId)
            is CaptureEffect.ConfirmDiscard -> confirmDiscard = effect.count
            CaptureEffect.Exit -> onClose()
        }
    }
    BackHandler { vm.onEvent(CaptureEvent.Close) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // The preview is shown at the camera's own 3:4 shape, never cropped to fill a tall screen: what
        // the user sees is exactly the still's field of view, so the freeze-frame snapshot, the live quad
        // and the captured image all share one frame (the crop editor depends on this).
        Box(Modifier.align(Alignment.Center).aspectRatio(PREVIEW_ASPECT)) {
            AndroidView(
                factory = { controller.previewView },
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewSize = it }
                    .pointerInput(Unit) {
                        detectTapGestures { p ->
                            controller.focus(p.x, p.y)
                            focusPoint = p
                            focusKey++
                        }
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoomChange, _ ->
                            if (zoomChange != 1f) {
                                controller.zoomBy(zoomChange)
                                showZoom = true
                                zoomVisibleKey++
                            }
                        }
                    },
            )

            if (state.frameWidth > 0 && viewSize.width > 0) {
                val mapping = remember(viewSize, state.frameWidth, state.frameHeight) {
                    PreviewMapping(viewSize.width.toFloat(), viewSize.height.toFloat(), state.frameWidth.toFloat(), state.frameHeight.toFloat())
                }
                QuadOverlay(
                    quad = state.detection.quad,
                    style = if (state.detection is AutoCaptureState.Holding || state.detection is AutoCaptureState.Firing) QuadStyle.STABLE else QuadStyle.TRACKING,
                    toView = { Offset(mapping.toViewX(it), mapping.toViewY(it)) },
                )
            }
            FocusRing(focusPoint, focusKey)
        }

        // Shutter flash.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = flashOverlay.value }.background(Color.White))

        // Top controls.
        Column(Modifier.fillMaxWidth().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                ChromeIconButton(DeckleIcons.Close, stringResource(R.string.capture_close)) { vm.onEvent(CaptureEvent.Close) }
                Spacer(Modifier.weight(1f))
                if (hasFlash) {
                    val flash = state.settings.flash
                    ChromeIconButton(
                        icon = when (flash) {
                            FlashMode.OFF -> DeckleIcons.FlashOff
                            FlashMode.ON -> DeckleIcons.Flash
                            FlashMode.AUTO -> DeckleIcons.FlashAuto
                        },
                        label = stringResource(
                            when (flash) {
                                FlashMode.OFF -> R.string.capture_flash_off
                                FlashMode.ON -> R.string.capture_flash_on
                                FlashMode.AUTO -> R.string.capture_flash_auto
                            },
                        ),
                    ) { haptics.tick(); vm.onEvent(CaptureEvent.CycleFlash) }
                }
                if (state.detectorAvailable) {
                    AutoToggle(state.settings.autoCapture) { haptics.tick(); vm.onEvent(CaptureEvent.ToggleAuto) }
                }
            }
            HintChip(state.hint?.let { stringResource(it.text()) }, Modifier.padding(top = Spacing.s))
            AnimatedVisibility(showZoom, enter = fadeIn(), exit = fadeOut()) {
                Surface(shape = RoundedCornerShape(50), color = CameraColors.Chrome, contentColor = Color.White, modifier = Modifier.padding(top = Spacing.s)) {
                    Text(stringResource(R.string.capture_zoom, zoom), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs))
                }
            }
        }
        LaunchedEffect(zoomVisibleKey) {
            if (showZoom) {
                delay(1_000)
                showZoom = false
            }
        }

        // Bottom controls.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(CameraColors.Scrim)
                .navigationBarsPadding()
                .padding(bottom = Spacing.l, top = Spacing.s),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ModeToggle(state.settings.batchMode, { haptics.tick(); vm.onEvent(CaptureEvent.SetBatch(it)) })
            Spacer(Modifier.height(Spacing.m))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.xl),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    ChromeIconButton(DeckleIcons.Gallery, stringResource(R.string.capture_import)) {
                        pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }
                ShutterButton(
                    progress = if (state.settings.autoCapture) state.holdProgress else 0f,
                    enabled = state.sessionId != null && !state.isCapturing,
                    onClick = { vm.onEvent(CaptureEvent.ShutterPressed) },
                )
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.animation.AnimatedVisibility(state.captureCount > 0, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                        ThumbStack(state.recentThumbs, state.captureCount, onClick = { vm.onEvent(CaptureEvent.OpenReview) })
                    }
                }
            }
            AnimatedVisibility(state.settings.batchMode && state.captureCount > 0) {
                TextButton(onClick = { vm.onEvent(CaptureEvent.OpenReview) }) {
                    Text(stringResource(R.string.action_next), color = CameraColors.Ring)
                    Icon(DeckleIcons.Forward, null, tint = CameraColors.Ring)
                }
            }
        }

        // Batch: the shot shrinks and arcs into the thumbnail stack (spec 05 §4.2).
        flying?.let { bitmap ->
            if (viewSize.width > 0) {
                val p = flight.value
                val start = Offset(viewSize.width / 2f, viewSize.height / 2f)
                // The stack sits ~64 dp in from the right edge and ~110 dp up from the bottom.
                val end = with(density) { Offset(viewSize.width - 64.dp.toPx(), viewSize.height - 110.dp.toPx()) }
                val control = Offset(end.x, start.y)
                val inv = 1 - p
                val x = inv * inv * start.x + 2 * inv * p * control.x + p * p * end.x
                val y = inv * inv * start.y + 2 * inv * p * control.y + p * p * end.y
                val scale = 0.6f - 0.5f * p
                Image(
                    bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale; scaleY = scale
                            translationX = x - start.x
                            translationY = y - start.y
                            alpha = if (p > 0.85f) (1 - p) / 0.15f else 1f
                        },
                )
            }
        }

        if (state.showTips) {
            TipsCard(state.detectorAvailable, Modifier.align(Alignment.Center)) { vm.onEvent(CaptureEvent.DismissTips) }
        }
    }

    confirmDiscard?.let { count ->
        ConfirmDialog(
            title = pluralStringResource(R.plurals.capture_discard_title, count, count),
            body = null,
            confirmLabel = stringResource(R.string.action_discard),
            dismissLabel = stringResource(R.string.capture_keep_scanning),
            destructive = true,
            onConfirm = { confirmDiscard = null; vm.onEvent(CaptureEvent.DiscardConfirmed) },
            onDismiss = { confirmDiscard = null },
        )
    }
}

@Composable
private fun ChromeIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).background(CameraColors.Chrome, RoundedCornerShape(24.dp))) {
        Icon(icon, label, tint = Color.White)
    }
}

@Composable
private fun AutoToggle(on: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (on) R.string.capture_auto_on else R.string.capture_auto_off)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (on) CameraColors.Ring else CameraColors.Chrome,
        contentColor = if (on) Color.Black else Color.White,
        modifier = Modifier.padding(start = Spacing.s).height(36.dp).semantics { contentDescription = label },
    ) {
        Box(Modifier.padding(horizontal = Spacing.m), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.capture_auto).uppercase(), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** First-run coach marks (spec 06 §2), shown once. */
@Composable
private fun TipsCard(detectorAvailable: Boolean, modifier: Modifier, onDismiss: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = CameraColors.Chrome, contentColor = Color.White, modifier = modifier.padding(Spacing.xl)) {
        Column(Modifier.padding(Spacing.xl)) {
            if (detectorAvailable) {
                Text(stringResource(R.string.capture_tip_detect), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(Spacing.m))
            } else {
                Text(stringResource(R.string.engine_degraded), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(Spacing.m))
            }
            Text(stringResource(R.string.capture_tip_batch), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(Spacing.s))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.action_got_it), color = CameraColors.Ring)
            }
        }
    }
}

private fun CaptureHint.text(): Int = when (this) {
    CaptureHint.LOOKING -> R.string.capture_hint_looking
    CaptureHint.TIP -> R.string.capture_hint_tip
    CaptureHint.HOLD -> R.string.capture_hint_hold
    CaptureHint.CAPTURING -> R.string.capture_hint_capturing
    CaptureHint.READY -> R.string.capture_hint_ready
    CaptureHint.NO_DETECTOR -> R.string.capture_hint_no_detector
    CaptureHint.BATCH -> R.string.capture_hint_batch
}

private fun android.content.Context.hasCameraPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private const val MAX_IMPORT = 50

/** Portrait 3:4, matching the ViewPort in [CameraController]. */
private const val PREVIEW_ASPECT = 3f / 4f

/** NV-05: the system shutter click, loaded once per screen. */
private class ShutterSound {
    private val sound = android.media.MediaActionSound().apply { load(android.media.MediaActionSound.SHUTTER_CLICK) }

    /** Plays when [wanted], or always where the region requires a shutter sound (API 33+ can tell us). */
    fun play(wanted: Boolean) {
        val required = android.os.Build.VERSION.SDK_INT >= 33 && android.media.MediaActionSound.mustPlayShutterSound()
        if (wanted || required) sound.play(android.media.MediaActionSound.SHUTTER_CLICK)
    }

    fun release() = sound.release()
}

@Composable
private fun rememberShutterSound(): ShutterSound {
    val shutter = remember { ShutterSound() }
    androidx.compose.runtime.DisposableEffect(shutter) { onDispose { shutter.release() } }
    return shutter
}
