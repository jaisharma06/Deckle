package com.avyaan.deckle.feature.crop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avyaan.deckle.R
import com.avyaan.deckle.designsystem.components.CropCanvas
import com.avyaan.deckle.designsystem.icons.DeckleIcons
import com.avyaan.deckle.designsystem.theme.CameraColors
import com.avyaan.deckle.designsystem.theme.CameraTheme
import com.avyaan.deckle.designsystem.theme.Spacing
import com.avyaan.deckle.designsystem.theme.rememberHaptics
import com.avyaan.deckle.feature.capture.FrozenFrame
import com.avyaan.deckle.ui.ObserveEffects
import com.avyaan.deckle.ui.SecureWindow
import com.avyaan.deckle.ui.rememberImageBitmap

@Composable
fun CropRoute(
    onRetake: () -> Unit,
    onDone: (sessionId: String, captureId: String, fromReview: Boolean) -> Unit,
    vm: CropViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    // The freeze-frame bridges the gap until preview.jpg exists (spec 03 §6.1); taken once.
    val frozen = remember { if (state.mode == CropMode.NEW_CAPTURE) FrozenFrame.take() else null }
    val loaded by rememberImageBitmap(state.imagePath, LOUPE_SOURCE_EDGE)
    val image = loaded ?: frozen

    ObserveEffects(vm.effects) { effect ->
        when (effect) {
            CropEffect.GrabFeedback -> haptics.gestureStart()
            CropEffect.SnapFeedback -> haptics.tick()
            CropEffect.RejectFeedback -> haptics.reject()
            CropEffect.Retaken -> onRetake()
            is CropEffect.Done -> { haptics.confirm(); onDone(effect.sessionId, effect.captureId, effect.fromReview) }
            is CropEffect.Message -> snackbar.showSnackbar(effect.text.resolve(context))
        }
    }
    SecureWindow(state.secure)

    CameraTheme {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                    if (state.mode == CropMode.RECROP) {
                        IconButton(onClick = onRetake) { Icon(DeckleIcons.Back, stringResource(R.string.action_back), tint = Color.White) }
                    } else {
                        Spacer(Modifier.width(Spacing.l))
                    }
                    Text(stringResource(R.string.crop_title), style = MaterialTheme.typography.titleMedium, color = Color.White)
                }
                AnimatedVisibility(state.edgesNotFound && state.isFullPage, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Surface(shape = RoundedCornerShape(50), color = CameraColors.Chrome, contentColor = Color.White, modifier = Modifier.padding(top = Spacing.s)) {
                        Text(stringResource(R.string.crop_not_found), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s))
                    }
                }
                CropCanvas(
                    image = image,
                    quad = state.quad,
                    rotation = state.rotation,
                    activeHandle = state.activeHandle,
                    rejections = state.rejections,
                    onDragStart = { vm.onEvent(CropEvent.DragStart(it)) },
                    onDrag = { vm.onEvent(CropEvent.DragBy(it)) },
                    onDragEnd = { vm.onEvent(CropEvent.DragEnd) },
                    onNudge = { c, d -> vm.onEvent(CropEvent.Nudge(c, d)) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                BottomBar(state, vm::onEvent)
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
        }
    }
}

@Composable
private fun BottomBar(state: CropUiState, onEvent: (CropEvent) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(CameraColors.Chrome)
            .navigationBarsPadding()
            .padding(horizontal = Spacing.s, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.mode == CropMode.NEW_CAPTURE) {
            TextButton(onClick = { onEvent(CropEvent.Retake) }) {
                Text(stringResource(if (state.imported) R.string.crop_cancel_import else R.string.crop_retake), color = Color.White)
            }
        }
        IconButton(onClick = { onEvent(CropEvent.RotateLeft) }) { Icon(DeckleIcons.RotateLeft, stringResource(R.string.crop_rotate_left), tint = Color.White) }
        IconButton(onClick = { onEvent(CropEvent.RotateRight) }) { Icon(DeckleIcons.RotateRight, stringResource(R.string.crop_rotate_right), tint = Color.White) }
        // Full page ⇄ Auto (FR-23): one tap away from skipping the crop.
        val showAuto = state.isFullPage && state.canToggleAuto
        val toggleDescription = stringResource(if (showAuto) R.string.crop_auto_detect else R.string.crop_use_full_page)
        TextButton(
            onClick = { onEvent(CropEvent.ToggleFullPage) },
            enabled = showAuto || !state.isFullPage,
            modifier = Modifier.semantics { contentDescription = toggleDescription },
        ) {
            Text(
                stringResource(if (showAuto) R.string.crop_auto else R.string.crop_full_page),
                color = if (showAuto || !state.isFullPage) CameraColors.Ring else Color.White.copy(alpha = 0.4f),
            )
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = { onEvent(CropEvent.Confirm) }, enabled = !state.saving) {
            if (state.saving) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text(stringResource(if (state.mode == CropMode.RECROP) R.string.crop_save else R.string.action_next))
            }
        }
    }
}

/** Enough resolution for a sharp 2.5× loupe without holding a full 3000 px source in memory. */
private const val LOUPE_SOURCE_EDGE = 2000
