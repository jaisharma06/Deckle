package com.avyaan.deckle.feature.viewer

import com.avyaan.deckle.designsystem.components.RenameDialog
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.avyaan.deckle.R
import com.avyaan.deckle.designsystem.components.AdjustPanel
import com.avyaan.deckle.designsystem.components.FilterStrip
import com.avyaan.deckle.designsystem.components.Zoomable
import com.avyaan.deckle.designsystem.icons.DeckleIcons
import com.avyaan.deckle.designsystem.theme.CameraColors
import com.avyaan.deckle.designsystem.theme.CameraTheme
import com.avyaan.deckle.designsystem.theme.DeckleMotion
import com.avyaan.deckle.designsystem.theme.LocalReducedMotion
import com.avyaan.deckle.designsystem.theme.Spacing
import com.avyaan.deckle.designsystem.theme.rememberHaptics
import com.avyaan.deckle.feature.export.ExportSheet
import com.avyaan.deckle.feature.markup.MarkedPage
import com.avyaan.deckle.ui.ObserveEffects
import com.avyaan.deckle.ui.SecureWindow
import com.avyaan.deckle.ui.SharedKeys
import com.avyaan.deckle.ui.sharedElement
import java.io.File
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerRoute(
    onBack: () -> Unit,
    onRecrop: (pageId: String) -> Unit,
    onInsertAfter: (documentId: String, pageId: String) -> Unit,
    onMarkup: (pageId: String) -> Unit,
    vm: ViewerViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val haptics = rememberHaptics()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var renaming by rememberSaveable { mutableStateOf(false) }
    var exporting by rememberSaveable { mutableStateOf(false) }

    ObserveEffects(vm.effects) { effect ->
        when (effect) {
            is ViewerEffect.OpenRecrop -> onRecrop(effect.pageId)
            is ViewerEffect.OpenMarkup -> onMarkup(effect.pageId)
            is ViewerEffect.OpenInsert -> onInsertAfter(effect.documentId, effect.afterPageId)
            is ViewerEffect.Message -> snackbar.showSnackbar(effect.text.resolve(context))
            ViewerEffect.Exit -> onBack()
            is ViewerEffect.PageDeleted -> scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val r = snackbar.showSnackbar(resources.getString(R.string.review_page_deleted), resources.getString(R.string.action_undo), duration = SnackbarDuration.Long)
                vm.onEvent(if (r == SnackbarResult.ActionPerformed) ViewerEvent.UndoDelete(effect.pageId) else ViewerEvent.ConfirmDelete(effect.pageId))
            }
        }
    }
    SecureWindow(state.secure)

    CameraTheme {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (state.pages.isNotEmpty()) Pager(state, vm::onEvent)

            AnimatedVisibility(state.chromeVisible, enter = fadeIn(tween(DeckleMotion.SHORT)), exit = fadeOut(tween(DeckleMotion.SHORT))) {
                Box(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().background(CameraColors.Scrim).statusBarsPadding().padding(horizontal = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) { Icon(DeckleIcons.Back, stringResource(R.string.action_back), tint = Color.White) }
                        if (state.pages.isNotEmpty()) {
                            val position = stringResource(R.string.viewer_position, state.currentIndex + 1, state.pages.size)
                            // A named page shows its name, with the position underneath.
                            Column(Modifier.weight(1f)) {
                                val name = state.current?.name
                                Text(name ?: position, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (name != null) Text(position, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                            }
                            IconButton(onClick = { renaming = true }) { Icon(DeckleIcons.Edit, stringResource(R.string.page_rename), tint = Color.White) }
                            IconButton(onClick = { exporting = true }) { Icon(DeckleIcons.Share, stringResource(R.string.page_export), tint = Color.White) }
                        }
                    }
                    Row(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(CameraColors.Scrim).navigationBarsPadding().padding(Spacing.s),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        val current = state.current
                        ToolButton(DeckleIcons.Crop, R.string.review_crop) { vm.onEvent(ViewerEvent.Recrop) }
                        ToolButton(DeckleIcons.Filter, R.string.viewer_filter) { vm.onEvent(ViewerEvent.SetFilterOpen(true)) }
                        ToolButton(DeckleIcons.Signature, R.string.viewer_markup, enabled = current != null) { vm.onEvent(ViewerEvent.Markup) }
                        ToolButton(DeckleIcons.RotateRight, R.string.review_rotate, enabled = state.busyPageId == null) { haptics.tick(); vm.onEvent(ViewerEvent.Rotate) }
                        ToolButton(DeckleIcons.Insert, R.string.viewer_insert) { vm.onEvent(ViewerEvent.InsertAfter) }
                        ToolButton(DeckleIcons.Delete, R.string.action_delete, enabled = current != null) { haptics.confirm(); vm.onEvent(ViewerEvent.Delete) }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
        }

        val current = state.current
        if (renaming && current != null) {
            RenameDialog(
                title = stringResource(R.string.page_rename),
                initial = current.name.orEmpty(),
                hint = stringResource(R.string.page_name_hint, state.currentIndex + 1),
                onConfirm = { vm.onEvent(ViewerEvent.Rename(it)); renaming = false },
                onDismiss = { renaming = false },
            )
        }
        if (state.filterOpen && current != null) {
            ModalBottomSheet(onDismissRequest = { vm.onEvent(ViewerEvent.SetFilterOpen(false)) }) {
                var mode by remember(current.id) { mutableStateOf(current.mode) }
                var adjustments by remember(current.id) { mutableStateOf(current.adjustments) }
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = Spacing.l)) {
                    FilterStrip(mode, state.filterThumbs, { haptics.tick(); mode = it; vm.onEvent(ViewerEvent.ApplyFilter(it, adjustments)) })
                    AdjustPanel(
                        value = adjustments,
                        onPreview = {},
                        onCommit = { adjustments = it; vm.onEvent(ViewerEvent.ApplyFilter(mode, it)) },
                        onDetent = haptics::tick,
                        modifier = Modifier.padding(top = Spacing.l),
                    )
                }
            }
        }
    }

    // Outside CameraTheme: the sheet uses the app's normal colours.
    val exportPage = state.current
    if (exporting && exportPage != null) {
        ExportSheet(
            documentIds = listOf(vm.documentId),
            selectedPageIds = listOf(exportPage.id),
            onDismiss = { exporting = false },
            onMessage = { scope.launch { snackbar.showSnackbar(it) } },
        )
    }
}

@Composable
private fun Pager(state: ViewerUiState, onEvent: (ViewerEvent) -> Unit) {
    val pager = rememberPagerState(initialPage = state.currentIndex) { state.pages.size }
    var zoomed by remember { mutableStateOf(false) }
    val reduced = LocalReducedMotion.current
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { onEvent(ViewerEvent.PageChanged(it)) } }
    LaunchedEffect(state.currentIndex) { if (pager.currentPage != state.currentIndex) pager.animateScrollToPage(state.currentIndex) }

    HorizontalPager(pager, key = { state.pages.getOrNull(it)?.id ?: it }, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()) { index ->
        val page = state.pages.getOrNull(index) ?: return@HorizontalPager
        val busy = state.busyPageId == page.id
        // Rotate: an animated quarter turn while the new render is made; it resets when the new image lands.
        val turn = remember(page.imagePath) { Animatable(0f) }
        LaunchedEffect(busy) { if (busy && !reduced) turn.animateTo(90f, tween(DeckleMotion.MEDIUM)) }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Zoomable(Modifier.fillMaxSize(), onTap = { onEvent(ViewerEvent.ToggleChrome) }, onZoomChanged = { zoomed = it }) {
                // Fitted to the page so the markup overlay lines up with the image (NV-41).
                MarkedPage(
                    imagePath = page.imagePath,
                    widthPx = page.widthPx,
                    heightPx = page.heightPx,
                    markup = page.markup,
                    contentDescription = stringResource(R.string.cd_page_number, index + 1),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { rotationZ = turn.value; val s = 1f - 0.25f * (turn.value / 90f); scaleX = s; scaleY = s },
                    imageModifier = Modifier.sharedElement(SharedKeys.page(page.id)),
                )
            }
            if (busy) CircularProgressIndicator(Modifier.size(32.dp), color = CameraColors.Ring)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: Int,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    // Equal shares of the bar and a one-line label: with the default button padding, five tools left
    // "Delete" too narrow and it wrapped as "Delet / e".
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = Spacing.xs),
        modifier = Modifier.weight(1f),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = if (enabled) Color.White else Color.White.copy(alpha = 0.4f))
            Text(
                stringResource(label),
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
