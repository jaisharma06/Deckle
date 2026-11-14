package com.avyaan.deckle.feature.review

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.avyaan.deckle.R
import com.avyaan.deckle.designsystem.components.AdjustPanel
import com.avyaan.deckle.designsystem.components.FilterStrip
import com.avyaan.deckle.designsystem.components.Zoomable
import com.avyaan.deckle.designsystem.components.shimmer
import com.avyaan.deckle.designsystem.icons.DeckleIcons
import com.avyaan.deckle.designsystem.theme.CameraColors
import com.avyaan.deckle.designsystem.theme.CameraTheme
import com.avyaan.deckle.designsystem.theme.DeckleMotion
import com.avyaan.deckle.designsystem.theme.Spacing
import com.avyaan.deckle.designsystem.theme.deckleTween
import com.avyaan.deckle.designsystem.theme.rememberHaptics
import com.avyaan.deckle.domain.scan.StagedStatus
import com.avyaan.deckle.ui.ObserveEffects
import com.avyaan.deckle.ui.SecureWindow
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun ReviewRoute(
    onBack: () -> Unit,
    onAddPage: (sessionId: String) -> Unit,
    onCrop: (sessionId: String, captureId: String) -> Unit,
    onFinished: (documentId: String) -> Unit,
    onDiscarded: () -> Unit,
    vm: ReviewViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    ObserveEffects(vm.effects) { effect ->
        when (effect) {
            is ReviewEffect.OpenCrop -> onCrop(effect.sessionId, effect.captureId)
            is ReviewEffect.OpenCapture -> onAddPage(effect.sessionId)
            is ReviewEffect.Finished -> { haptics.confirm(); onFinished(effect.documentId) }
            ReviewEffect.Discarded -> onDiscarded()
            is ReviewEffect.Message -> snackbar.showSnackbar(effect.text.resolve(context))
            is ReviewEffect.PageDeleted -> scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val result = snackbar.showSnackbar(
                    resources.getString(R.string.review_page_deleted),
                    actionLabel = resources.getString(R.string.action_undo),
                    duration = SnackbarDuration.Long,
                )
                vm.onEvent(if (result == SnackbarResult.ActionPerformed) ReviewEvent.UndoDelete(effect.captureId) else ReviewEvent.ConfirmDelete(effect.captureId))
            }
        }
    }
    SecureWindow(state.secure)

    CameraTheme {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Column(Modifier.fillMaxSize()) {
                TopRow(state, onBack, onDelete = { haptics.confirm(); vm.onEvent(ReviewEvent.DeletePage) })
                if (state.pages.isNotEmpty()) {
                    PagePager(state, Modifier.weight(1f)) { vm.onEvent(ReviewEvent.PageChanged(it)) }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Controls(state, vm::onEvent, onTick = haptics::tick)
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 180.dp))
        }
    }
}

@Composable
private fun TopRow(state: ReviewUiState, onBack: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(DeckleIcons.Back, stringResource(R.string.action_back), tint = Color.White) }
            AnimatedContent(state.currentIndex to state.pages.size, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "pos", modifier = Modifier.weight(1f)) { (i, n) ->
                if (n > 0) Text(stringResource(R.string.review_page_of, i + 1, n), style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
            IconButton(onClick = onDelete, enabled = state.current != null) {
                Icon(DeckleIcons.Delete, stringResource(R.string.review_delete_page), tint = Color.White)
            }
        }
        // Thin progress line, only when a preview is slow (spec 05 §4.5).
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (state.previewBusy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = CameraColors.Ring, trackColor = Color.Transparent)
        }
    }
}

@Composable
private fun PagePager(state: ReviewUiState, modifier: Modifier, onPageChanged: (Int) -> Unit) {
    val pager = rememberPagerState(initialPage = state.currentIndex) { state.pages.size }
    var zoomed by remember { mutableStateOf(false) }
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect(onPageChanged) }
    LaunchedEffect(state.currentIndex) { if (pager.currentPage != state.currentIndex) pager.scrollToPage(state.currentIndex) }

    HorizontalPager(
        state = pager,
        key = { state.pages.getOrNull(it)?.id ?: it },
        userScrollEnabled = !zoomed,
        pageSpacing = Spacing.l,
        modifier = modifier.fillMaxWidth(),
    ) { index ->
        val page = state.pages.getOrNull(index) ?: return@HorizontalPager
        // Rotation is shown optimistically until the new render lands; odd turns shrink to fit.
        val turn by animateFloatAsState(page.pendingTurns * 90f, deckleTween(DeckleMotion.MEDIUM), label = "turn")
        val fit by animateFloatAsState(if (page.pendingTurns % 2 == 1) 0.75f else 1f, deckleTween(DeckleMotion.MEDIUM), label = "fit")
        Box(Modifier.fillMaxSize().padding(Spacing.l), contentAlignment = Alignment.Center) {
            when {
                page.status == StagedStatus.FAILED -> Text(
                    stringResource(R.string.review_failed),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                )
                page.displayPath == null -> Box(Modifier.fillMaxWidth(0.8f).fillMaxSize(0.8f).shimmer())
                else -> Zoomable(Modifier.fillMaxSize(), onZoomChanged = { zoomed = it }) {
                    AsyncImage(
                        model = File(page.displayPath),
                        contentDescription = stringResource(R.string.cd_page_number, index + 1),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = turn; scaleX = fit; scaleY = fit },
                    )
                }
            }
        }
    }
}

@Composable
private fun Controls(state: ReviewUiState, onEvent: (ReviewEvent) -> Unit, onTick: () -> Unit) {
    val current = state.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(CameraColors.Chrome)
            .navigationBarsPadding()
            .padding(top = Spacing.m, bottom = Spacing.m),
    ) {
        if (current != null) {
            FilterStrip(
                selected = current.mode,
                thumbs = state.filterThumbs,
                onSelect = { onTick(); onEvent(ReviewEvent.SelectFilter(it)) },
                contentColor = Color.White,
            )
            Spacer(Modifier.height(Spacing.s))
            // Equal slots with the label under the icon: fits every font size without wrapping or clipping.
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                ToolButton(DeckleIcons.Crop, R.string.review_crop) { onEvent(ReviewEvent.Crop) }
                ToolButton(DeckleIcons.RotateRight, R.string.review_rotate) { onEvent(ReviewEvent.Rotate) }
                ToolButton(DeckleIcons.Tune, R.string.review_adjust) { onEvent(ReviewEvent.SetAdjustOpen(!state.adjustOpen)) }
                if (state.pages.size > 1) {
                    ToolButton(DeckleIcons.Pages, R.string.review_apply_all, tint = CameraColors.Ring) {
                        onTick(); onEvent(ReviewEvent.ApplyToAll)
                    }
                }
            }
            AnimatedVisibility(state.adjustOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                AdjustPanel(
                    value = current.adjustments,
                    onPreview = { onEvent(ReviewEvent.AdjustPreview(it)) },
                    onCommit = { onEvent(ReviewEvent.AdjustCommit(it)) },
                    onDetent = onTick,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { onEvent(ReviewEvent.AddPage) }) {
                Icon(DeckleIcons.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.s))
                Text(stringResource(R.string.review_add_page), color = Color.White)
            }
            Spacer(Modifier.weight(1f))
            // Never a blocking dialog: the button itself says what it's waiting for (spec 06 §5).
            Button(onClick = { onEvent(ReviewEvent.Done) }, enabled = state.canFinish && !state.committing) {
                AnimatedContent(state.pendingCount > 0 || state.committing, label = "done") { waiting ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (waiting) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(Spacing.s))
                            Text(
                                if (state.pendingCount > 0) pluralStringResource(R.plurals.review_finishing, state.pendingCount, state.pendingCount)
                                else stringResource(R.string.action_done),
                            )
                        } else {
                            Icon(DeckleIcons.Check, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(Spacing.s))
                            Text(stringResource(R.string.action_done))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: Int,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = Spacing.xs),
        modifier = Modifier.weight(1f),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(22.dp), tint = tint)
            Text(
                stringResource(label),
                color = tint,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
