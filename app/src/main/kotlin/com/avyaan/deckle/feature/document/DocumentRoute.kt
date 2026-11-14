package com.avyaan.deckle.feature.document

import com.avyaan.deckle.designsystem.components.RenameDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.avyaan.deckle.R
import com.avyaan.deckle.designsystem.components.ConfirmDialog
import com.avyaan.deckle.designsystem.components.DeckleTopBar
import com.avyaan.deckle.designsystem.components.EmptyState
import com.avyaan.deckle.designsystem.components.OcrBadge
import com.avyaan.deckle.designsystem.components.SelectionCheck
import com.avyaan.deckle.designsystem.components.paperShadow
import com.avyaan.deckle.designsystem.icons.DeckleIcons
import com.avyaan.deckle.designsystem.theme.DeckleMotion
import com.avyaan.deckle.designsystem.theme.Spacing
import com.avyaan.deckle.designsystem.theme.deckleSpring
import com.avyaan.deckle.designsystem.theme.rememberHaptics
import com.avyaan.deckle.domain.ocr.OcrStatus
import com.avyaan.deckle.feature.export.ExportSheet
import com.avyaan.deckle.ui.ObserveEffects
import com.avyaan.deckle.ui.RelativeDate
import com.avyaan.deckle.ui.SecureWindow
import com.avyaan.deckle.ui.SharedKeys
import com.avyaan.deckle.ui.copySensitiveText
import com.avyaan.deckle.ui.sharedElement
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentRoute(
    onBack: () -> Unit,
    onOpenPage: (documentId: String, pageId: String) -> Unit,
    onAddPages: (documentId: String, afterPageId: String?) -> Unit,
    onRecrop: (pageId: String) -> Unit,
    vm: DocumentViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var exportOpen by rememberSaveable { mutableStateOf(false) }
    var exportSelected by rememberSaveable { mutableStateOf(false) }
    /** Set when one page is exported from its long-press menu. */
    var exportPage by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var failedSheetPage by rememberSaveable { mutableStateOf<String?>(null) }
    var renameShake by remember { mutableStateOf(0) }
    var renamingPage by rememberSaveable { mutableStateOf<String?>(null) }

    // Library → "Export" opens straight into the sheet, once.
    LaunchedEffect(Unit) { if (vm.consumeOpenExport()) exportOpen = true }

    ObserveEffects(vm.effects) { effect ->
        when (effect) {
            is DocumentEffect.OpenPage -> onOpenPage(effect.documentId, effect.pageId)
            is DocumentEffect.OpenCapture -> onAddPages(effect.documentId, effect.afterPageId)
            is DocumentEffect.OpenRecrop -> onRecrop(effect.pageId)
            is DocumentEffect.CopyToClipboard -> {
                context.copySensitiveText(effect.text)
                haptics.confirm()
                snackbar.showSnackbar(effect.message.resolve(context))
            }
            is DocumentEffect.Message -> snackbar.showSnackbar(effect.text.resolve(context))
            DocumentEffect.RenameRejected -> { haptics.reject(); renameShake++ }
            DocumentEffect.Deleted -> onBack()
        }
    }
    SecureWindow(state.secure)
    BackHandler(state.selecting) { vm.onEvent(DocumentEvent.SetSelecting(false)) }

    Scaffold(
        topBar = {
            AnimatedContent(state.selecting, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "bar") { selecting ->
                if (selecting) {
                    DeckleTopBar(
                        title = { Text(pluralStringResource(R.plurals.library_selected, state.selection.size, state.selection.size)) },
                        onBack = { vm.onEvent(DocumentEvent.SetSelecting(false)) },
                        actions = {
                            IconButton(onClick = { exportPage = null; exportSelected = true; exportOpen = true }, enabled = state.selection.isNotEmpty()) {
                                Icon(DeckleIcons.Share, stringResource(R.string.library_export))
                            }
                            IconButton(onClick = { haptics.confirm(); vm.onEvent(DocumentEvent.DeleteSelectedPages) }, enabled = state.selection.isNotEmpty()) {
                                Icon(DeckleIcons.Delete, stringResource(R.string.action_delete))
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    )
                } else {
                    DeckleTopBar(
                        title = { EditableTitle(state.title, renameShake) { vm.onEvent(DocumentEvent.Rename(it)) } },
                        onBack = onBack,
                        actions = { OverflowMenu(onSelect = { vm.onEvent(DocumentEvent.SetSelecting(true)) }, onDelete = { confirmDelete = true }) },
                    )
                }
            }
        },
        bottomBar = {
            if (!state.selecting) {
                ActionBar(
                    onAdd = { vm.onEvent(DocumentEvent.AddPages) },
                    onCopy = { vm.onEvent(DocumentEvent.CopyText) },
                    onShare = { exportPage = null; exportSelected = false; exportOpen = true },
                    onExport = { exportPage = null; exportSelected = false; exportOpen = true },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.modifiedAt?.let { modified ->
                Text(
                    pluralStringResource(R.plurals.page_count, state.pages.size, state.pages.size) + " · " +
                        stringResource(R.string.document_edited, RelativeDate.format(context, modified)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.l),
                )
            }
            if (state.missing && !state.loading) {
                EmptyState(stringResource(R.string.error_not_found), "", illustration = {}, modifier = Modifier.fillMaxSize())
            } else {
                PageGrid(
                    state, vm,
                    onFailedTapped = { failedSheetPage = it },
                    onRename = { renamingPage = it },
                    onExport = { exportPage = it; exportSelected = false; exportOpen = true },
                    onSelectHaptic = haptics::select,
                )
            }
        }
    }

    if (exportOpen) {
        ExportSheet(
            documentIds = listOf(vm.documentId),
            selectedPageIds = exportPage?.let(::listOf) ?: if (exportSelected) vm.selectedInOrder() else emptyList(),
            onDismiss = { exportOpen = false },
            onMessage = { scope.launch { snackbar.showSnackbar(it) } },
        )
    }
    renamingPage?.let { pageId ->
        val index = state.pages.indexOfFirst { it.id == pageId }
        val page = state.pages.getOrNull(index)
        if (page == null) {
            renamingPage = null
        } else {
            RenameDialog(
                title = stringResource(R.string.page_rename),
                initial = page.name.orEmpty(),
                hint = stringResource(R.string.page_name_hint, index + 1),
                onConfirm = { vm.onEvent(DocumentEvent.RenamePage(pageId, it)); renamingPage = null },
                onDismiss = { renamingPage = null },
            )
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.document_delete),
            body = stringResource(R.string.trash_empty_body),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = { confirmDelete = false; vm.onEvent(DocumentEvent.Delete) },
            onDismiss = { confirmDelete = false },
        )
    }
    failedSheetPage?.let { pageId ->
        ModalBottomSheet(onDismissRequest = { failedSheetPage = null }) {
            Column(Modifier.fillMaxWidth().padding(Spacing.xl).navigationBarsPadding()) {
                Text(stringResource(R.string.error_ocr), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(Spacing.l))
                Button(onClick = { vm.onEvent(DocumentEvent.RetryOcr(pageId)); failedSheetPage = null }) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}

@Composable
private fun PageGrid(
    state: DocumentUiState,
    vm: DocumentViewModel,
    onFailedTapped: (String) -> Unit,
    onRename: (String) -> Unit,
    onExport: (String) -> Unit,
    onSelectHaptic: () -> Unit,
) {
    val grid = rememberLazyGridState()
    val reorder = rememberReorderableLazyGridState(grid) { from, to ->
        vm.onEvent(DocumentEvent.Move(from.index, to.index))
    }
    val earlier = stringResource(R.string.action_move_earlier)
    val later = stringResource(R.string.action_move_later)

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = grid,
        contentPadding = PaddingValues(Spacing.l),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(state.pages, key = { it.id }) { page ->
            val index = state.pages.indexOf(page)
            ReorderableItem(reorder, key = page.id) { dragging ->
                val lift by animateFloatAsState(if (dragging) 1.05f else 1f, deckleSpring(DeckleMotion.Spring.Lift), label = "lift")
                val elevation by animateDpAsState(if (dragging) 8.dp else 0.dp, deckleSpring(DeckleMotion.Spring.Lift), label = "elev")
                PageTile(
                    page = page,
                    number = index + 1,
                    isCover = page.id == state.coverPageId,
                    selected = page.id in state.selection,
                    selecting = state.selecting,
                    onClick = { vm.onEvent(DocumentEvent.PageClicked(page.id)) },
                    onSetCover = { vm.onEvent(DocumentEvent.SetCover(page.id)) },
                    onCrop = { vm.onEvent(DocumentEvent.CropPage(page.id)) },
                    onRename = { onRename(page.id) },
                    onExport = { onExport(page.id) },
                    onFailedTapped = { onFailedTapped(page.id) },
                    modifier = Modifier
                        .graphicsLayer { scaleX = lift; scaleY = lift }
                        .shadow(elevation, MaterialTheme.shapes.small)
                        .longPressDraggableHandle(
                            enabled = !state.selecting,
                            onDragStarted = { onSelectHaptic() },
                            onDragStopped = { vm.onEvent(DocumentEvent.Drop(page.id)) },
                        )
                        .semantics {
                            customActions = listOf(
                                CustomAccessibilityAction(earlier) { vm.onEvent(DocumentEvent.MoveBy(page.id, -1)); true },
                                CustomAccessibilityAction(later) { vm.onEvent(DocumentEvent.MoveBy(page.id, 1)); true },
                            )
                        },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageTile(
    page: PageTileUi,
    number: Int,
    isCover: Boolean,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onSetCover: () -> Unit,
    onCrop: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onFailedTapped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val pageNumber = stringResource(R.string.cd_page_number, number)
    val label = page.name?.let { "$it, $pageNumber" } ?: pageNumber
    // "Done" shows a check briefly, then fades so the grid stays clean (spec 05 §4.7).
    var showDone by remember(page.ocr) { mutableStateOf(page.ocr == OcrStatus.DONE) }
    LaunchedEffect(page.ocr) { if (page.ocr == OcrStatus.DONE) { delay(1_500); showDone = false } }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.77f)
                .paperShadow(MaterialTheme.shapes.small)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small) else Modifier)
                .sharedElement(SharedKeys.page(page.id))
                .semantics { contentDescription = label }
                .combinedClickable(onClick = onClick, onLongClick = { if (!selecting) menu = true }),
        ) {
            AsyncImage(File(page.thumbPath), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            val badge = page.ocr != OcrStatus.DONE || showDone
            androidx.compose.animation.AnimatedVisibility(badge, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut(), modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.xs)) {
                Box(if (page.ocr == OcrStatus.FAILED) Modifier.clickable(onClick = onFailedTapped) else Modifier) { OcrBadge(page.ocr) }
            }
            androidx.compose.animation.AnimatedVisibility(selected, enter = scaleIn(deckleSpring(DeckleMotion.Spring.Pop)) + fadeIn(), exit = scaleOut() + fadeOut(), modifier = Modifier.align(Alignment.Center)) {
                SelectionCheck()
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.page_crop)) }, leadingIcon = { Icon(DeckleIcons.Crop, null) }, onClick = { menu = false; onCrop() })
                DropdownMenuItem(text = { Text(stringResource(R.string.page_rename)) }, leadingIcon = { Icon(DeckleIcons.Edit, null) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text(stringResource(R.string.page_export)) }, leadingIcon = { Icon(DeckleIcons.Share, null) }, onClick = { menu = false; onExport() })
                DropdownMenuItem(text = { Text(stringResource(R.string.document_set_cover)) }, leadingIcon = { Icon(DeckleIcons.Pages, null) }, onClick = { menu = false; onSetCover() }, enabled = !isCover)
            }
        }
        // Named pages show their name; others their number. Long names end in an ellipsis.
        AnimatedContent(page.name ?: number.toString(), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "n") { text ->
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

/** FR-43: tap the title to rename inline; Done saves; a blank title shakes and reverts. */
@Composable
private fun EditableTitle(title: String, shakeKey: Int, onRename: (String) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var field by remember(title, editing) { mutableStateOf(TextFieldValue(title, TextRange(title.length))) }
    val focus = remember { FocusRequester() }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(shakeKey) {
        if (shakeKey == 0) return@LaunchedEffect
        repeat(3) { shake.animateTo(1f, tween(30)); shake.animateTo(-1f, tween(60)) }
        shake.animateTo(0f, tween(30))
    }
    Box(Modifier.offset { androidx.compose.ui.unit.IntOffset((shake.value * 6.dp.toPx()).toInt(), 0) }) {
        if (editing) {
            // onFocusChanged also reports the initial, unfocused state; only a loss after real focus means "done".
            var hadFocus by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { focus.requestFocus() }
            val underline by animateFloatAsState(1f, tween(DeckleMotion.MEDIUM), label = "u")
            val fieldLabel = stringResource(R.string.document_title_field)
            Column {
                BasicTextField(
                    value = field,
                    onValueChange = { field = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onRename(field.text); editing = false }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .onFocusChanged {
                            if (it.isFocused) hadFocus = true
                            else if (hadFocus && editing) { onRename(field.text); editing = false }
                        }
                        .semantics { contentDescription = fieldLabel },
                )
                HorizontalDivider(Modifier.fillMaxWidth(underline), thickness = 2.dp, color = MaterialTheme.colorScheme.primary)
            }
        } else {
            Row(Modifier.clickable { editing = true }, verticalAlignment = Alignment.CenterVertically) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(Spacing.s))
                Icon(DeckleIcons.Edit, stringResource(R.string.document_rename), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun OverflowMenu(onSelect: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(DeckleIcons.More, stringResource(R.string.action_more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.document_select_pages)) }, leadingIcon = { Icon(DeckleIcons.Check, null) }, onClick = { open = false; onSelect() })
            DropdownMenuItem(text = { Text(stringResource(R.string.document_delete)) }, leadingIcon = { Icon(DeckleIcons.Delete, null) }, onClick = { open = false; onDelete() })
        }
    }
}

/** Bottom actions; Export PDF is the primary one (spec 06 §6). */
@Composable
/**
 * Scrolls sideways when the actions don't fit (narrow phones, large font sizes), so no label is ever
 * squeezed or wrapped. Export is the primary action and comes first.
 */
private fun ActionBar(onAdd: () -> Unit, onCopy: () -> Unit, onShare: () -> Unit, onExport: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.m, vertical = Spacing.s),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onExport,
                contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.s),
                modifier = Modifier.widthIn(min = BAR_BUTTON_MIN_WIDTH),
            ) {
                Icon(DeckleIcons.Save, null, Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.s))
                Text(stringResource(R.string.document_export_pdf), maxLines = 1, softWrap = false)
            }
            BarAction(DeckleIcons.Share, R.string.document_share, onShare)
            BarAction(DeckleIcons.Add, R.string.document_add, onAdd)
            BarAction(DeckleIcons.Copy, R.string.document_copy_text, onCopy)
        }
    }
}

@Composable
private fun BarAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.s),
        modifier = Modifier.widthIn(min = BAR_BUTTON_MIN_WIDTH),
    ) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.s))
        Text(stringResource(label), maxLines = 1, softWrap = false)
    }
}

private val BAR_BUTTON_MIN_WIDTH = 112.dp
