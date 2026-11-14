package com.avyaan.deckle.feature.review

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.avyaan.deckle.designsystem.components.SelectionCheck
import com.avyaan.deckle.designsystem.theme.deckleSpring
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
            is ReviewEffect.PagesDeleted -> scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val n = effect.captureIds.size
                val result = snackbar.showSnackbar(
                    if (n == 1) resources.getString(R.string.review_page_deleted) else resources.getQuantityString(R.plurals.review_pages_deleted, n, n),
                    actionLabel = resources.getString(R.string.action_undo),
                    duration = SnackbarDuration.Long,
                )
                vm.onEvent(if (result == SnackbarResult.ActionPerformed) ReviewEvent.UndoDelete(effect.captureIds) else ReviewEvent.ConfirmDelete(effect.captureIds))
            }
        }
    }
    SecureWindow(state.secure)
    BackHandler(state.selecting) { vm.onEvent(ReviewEvent.SetSelecting(false)) }

    CameraTheme {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Column(Modifier.fillMaxSize()) {
                AnimatedContent(state.selecting, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "bar") { selecting ->
                    if (selecting) {
                        SelectionTopRow(
                            state,
                            onClose = { vm.onEvent(ReviewEvent.SetSelecting(false)) },
                            onSelectAll = { haptics.tick(); vm.onEvent(ReviewEvent.SelectAll) },
                            onDelete = { haptics.confirm(); vm.onEvent(ReviewEvent.DeleteSelected) },
                        )
                    } else {
                        TopRow(
                            state, onBack,
                            onSelect = { haptics.select(); vm.onEvent(ReviewEvent.SetSelecting(true)) },
                            onDelete = { haptics.confirm(); vm.onEvent(ReviewEvent.DeletePage) },
                        )
                    }
                }
                when {
                    state.pages.isEmpty() -> Spacer(Modifier.weight(1f))
                    state.selecting -> SelectionGrid(state, Modifier.weight(1f)) { haptics.tick(); vm.onEvent(ReviewEvent.ToggleSelect(it)) }
                    else -> PagePager(state, Modifier.weight(1f)) { vm.onEvent(ReviewEvent.PageChanged(it)) }
                }
                if (state.selecting) SelectionControls(state, vm::onEvent, onTick = haptics::tick)
                else Controls(state, vm::onEvent, onTick = haptics::tick)
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 180.dp))
        }
    }
}

@Composable
private fun TopRow(state: ReviewUiState, onBack: () -> Unit, onSelect: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(DeckleIcons.Back, stringResource(R.string.action_back), tint = Color.White) }
            AnimatedContent(state.currentIndex to state.pages.size, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "pos", modifier = Modifier.weight(1f)) { (i, n) ->
                if (n > 0) Text(stringResource(R.string.review_page_of, i + 1, n), style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
            if (state.pages.size > 1) {
                IconButton(onClick = onSelect) { Icon(DeckleIcons.Grid, stringResource(R.string.review_select_pages), tint = Color.White) }
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

@Composable
private fun SelectionTopRow(state: ReviewUiState, onClose: () -> Unit, onSelectAll: () -> Unit, onDelete: () -> Unit) {
    val canSelectMore = state.selection.size < state.pages.size
    val canDelete = state.selection.isNotEmpty()
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(DeckleIcons.Close, stringResource(R.string.action_close), tint = Color.White) }
            Text(
                pluralStringResource(R.plurals.library_selected, state.selection.size, state.selection.size),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSelectAll, enabled = canSelectMore) {
                Text(stringResource(R.string.action_select_all), color = if (canSelectMore) CameraColors.Ring else Color.White.copy(alpha = 0.4f))
            }
            IconButton(onClick = onDelete, enabled = canDelete) {
                Icon(DeckleIcons.Delete, stringResource(R.string.action_delete), tint = if (canDelete) Color.White else Color.White.copy(alpha = 0.4f))
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

/** NV-01: every page of the scan at once; tap to pick. */
@Composable
private fun SelectionGrid(state: ReviewUiState, modifier: Modifier, onToggle: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(Spacing.l),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(state.pages, key = { it.id }) { page ->
            val index = state.pages.indexOf(page)
            val selected = page.id in state.selection
            val turn by animateFloatAsState(page.pendingTurns * 90f, deckleTween(DeckleMotion.MEDIUM), label = "turn")
            val shrink by animateFloatAsState(if (selected) 0.92f else 1f, deckleSpring(DeckleMotion.Spring.Pop), label = "pick")
            val label = stringResource(R.string.cd_page_number, index + 1)
            Column(Modifier.animateItem(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.77f)
                        .graphicsLayer { scaleX = shrink; scaleY = shrink }
                        .clip(MaterialTheme.shapes.small)
                        .background(Color.White.copy(alpha = 0.08f))
                        .then(if (selected) Modifier.border(3.dp, CameraColors.Ring, MaterialTheme.shapes.small) else Modifier)
                        .semantics { contentDescription = label; this.selected = selected }
                        .clickable { onToggle(page.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (page.displayPath != null) {
                        AsyncImage(
                            model = File(page.displayPath),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(Spacing.xs).graphicsLayer { rotationZ = turn },
                        )
                    } else {
                        Box(Modifier.fillMaxSize().shimmer())
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        selected,
                        enter = scaleIn(deckleSpring(DeckleMotion.Spring.Pop)) + fadeIn(),
                        exit = scaleOut() + fadeOut(),
                    ) { SelectionCheck() }
                }
                Text(
                    (index + 1).toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
        }
    }
}

/** Filter and rotate for the selected pages. Dimmed until something is selected. */
@Composable
private fun SelectionControls(state: ReviewUiState, onEvent: (ReviewEvent) -> Unit, onTick: () -> Unit) {
    val current = state.current ?: return
    val enabled = state.selection.isNotEmpty()
    Column(
        Modifier
            .fillMaxWidth()
            .background(CameraColors.Chrome)
            .navigationBarsPadding()
            .padding(top = Spacing.m, bottom = Spacing.m)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
    ) {
        FilterStrip(
            selected = state.selectionMode ?: current.mode,
            thumbs = state.filterThumbs,
            onSelect = { if (enabled) { onTick(); onEvent(ReviewEvent.FilterSelected(it)) } },
            contentColor = Color.White,
        )
        Spacer(Modifier.height(Spacing.s))
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            ToolButton(DeckleIcons.RotateRight, R.string.review_rotate) { if (enabled) { onTick(); onEvent(ReviewEvent.RotateSelected) } }
        }
    }
}
