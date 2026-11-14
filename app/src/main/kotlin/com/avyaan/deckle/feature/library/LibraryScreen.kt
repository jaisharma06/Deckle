package com.avyaan.deckle.feature.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avyaan.deckle.R
import com.avyaan.deckle.designsystem.components.ConfirmDialog
import com.avyaan.deckle.designsystem.components.DetailsHeader
import com.avyaan.deckle.designsystem.components.DetailsRow
import com.avyaan.deckle.designsystem.components.DocumentCard
import com.avyaan.deckle.designsystem.components.EmptyState
import com.avyaan.deckle.designsystem.components.FolderCard
import com.avyaan.deckle.designsystem.components.FolderCardModel
import com.avyaan.deckle.designsystem.components.LibraryRow
import com.avyaan.deckle.designsystem.components.RenameDialog
import com.avyaan.deckle.designsystem.components.RowThumb
import com.avyaan.deckle.designsystem.components.ScanFab
import com.avyaan.deckle.designsystem.components.SearchField
import com.avyaan.deckle.designsystem.components.SkeletonCard
import com.avyaan.deckle.designsystem.components.TileSize
import com.avyaan.deckle.designsystem.icons.DeckleIcons
import com.avyaan.deckle.designsystem.theme.Spacing
import com.avyaan.deckle.designsystem.theme.rememberHaptics
import com.avyaan.deckle.domain.document.SortOrder
import com.avyaan.deckle.domain.export.ExportArchive
import com.avyaan.deckle.domain.settings.LibraryLayout
import com.avyaan.deckle.feature.export.ExportSheet
import com.avyaan.deckle.ui.ObserveEffects
import com.avyaan.deckle.ui.RelativeDate
import com.avyaan.deckle.ui.SecureWindow
import com.avyaan.deckle.ui.SharedKeys
import com.avyaan.deckle.ui.sharedElement
import kotlinx.coroutines.launch

/**
 * The library (FR-1…7) and, with a folder id, one folder of it. Both use this route: each folder is its
 * own back-stack entry, so system Back walks up the tree like a file manager.
 */
@Composable
fun LibraryRoute(
    onScan: (folderId: String?) -> Unit,
    onResume: (sessionId: String) -> Unit,
    onOpenDocument: (id: String, export: Boolean) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenCrop: (sessionId: String, captureId: String) -> Unit,
    /** Steps into a child folder of this level. */
    onOpenFolder: (id: String) -> Unit,
    /** Jumps to a folder anywhere (breadcrumbs, search hits): its root-first id chain; empty = the root. */
    onJumpToFolder: (path: List<String>) -> Unit,
    onBack: () -> Unit,
    vm: LibraryViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    // The system photo picker needs no storage permission.
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_IMPORT)) { uris ->
        vm.onEvent(LibraryEvent.Imported(uris.map { it.toString() }))
    }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var folderExport by remember { mutableStateOf<Pair<List<String>, ExportArchive>?>(null) }

    LifecycleResumeEffect(Unit) {
        vm.onEvent(LibraryEvent.Resumed)
        onPauseOrDispose { }
    }
    ObserveEffects(vm.effects) { effect ->
        when (effect) {
            is LibraryEffect.OpenCapture -> onScan(effect.folderId)
            is LibraryEffect.OpenReview -> onResume(effect.sessionId)
            is LibraryEffect.OpenCrop -> onOpenCrop(effect.sessionId, effect.captureId)
            is LibraryEffect.OpenDocument -> onOpenDocument(effect.id, effect.export)
            is LibraryEffect.OpenFolder -> if (effect.jump) onJumpToFolder(effect.path) else onOpenFolder(effect.path.last())
            is LibraryEffect.ExportFolder -> folderExport = effect.documentIds to effect.archive
            is LibraryEffect.ShowMessage -> {
                snackbar.currentSnackbarData?.dismiss()
                val result = snackbar.showSnackbar(
                    message = effect.text.resolve(context),
                    actionLabel = if (effect.undo) resources.getString(R.string.action_undo) else null,
                    withDismissAction = false,
                )
                if (result == SnackbarResult.ActionPerformed) vm.onEvent(LibraryEvent.UndoDelete)
            }
        }
    }
    SecureWindow(state.secure)
    LibraryScreen(
        state = state,
        onEvent = vm::onEvent,
        snackbar = snackbar,
        onOpenTrash = onOpenTrash,
        onOpenSettings = onOpenSettings,
        onOpenHistory = onOpenHistory,
        onImport = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onBack = onBack,
        onOpenCrumb = { index -> onJumpToFolder(state.path.take(index + 1).map { it.id }) },
        moveExclusions = vm::moveExclusions,
    )

    folderExport?.let { (ids, archive) ->
        ExportSheet(
            documentIds = ids,
            selectedPageIds = emptyList(),
            archive = archive,
            onDismiss = { folderExport = null },
            onMessage = { scope.launch { snackbar.showSnackbar(it) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onEvent: (LibraryEvent) -> Unit,
    snackbar: SnackbarHostState,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit = {},
    onOpenCrumb: (index: Int) -> Unit = {},
    moveExclusions: () -> Set<String> = { emptySet() },
) {
    val haptics = rememberHaptics()
    val grid = rememberLazyGridState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    var confirmMerge by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var newFolder by rememberSaveable { mutableStateOf(false) }
    var renamingFolder by rememberSaveable { mutableStateOf(false) }
    var moving by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = state.selecting) { onEvent(LibraryEvent.ClearSelection) }
    BackHandler(enabled = !state.selecting && state.query.isNotEmpty()) { onEvent(LibraryEvent.QueryChanged("")) }

    val fabExpanded by remember { derivedStateOf { grid.firstVisibleItemIndex == 0 } }
    val showFab = state.content !is LibraryContent.Empty && !state.selecting

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AnimatedContent(
                state.selecting,
                transitionSpec = { (fadeIn() + slideInVertically { -it / 3 }) togetherWith (fadeOut() + slideOutVertically { -it / 3 }) },
                label = "topbar",
            ) { selecting ->
                if (selecting) {
                    SelectionTopBar(
                        state = state,
                        onClose = { onEvent(LibraryEvent.ClearSelection) },
                        onMove = { moving = true },
                        onMerge = { confirmMerge = true },
                        onExport = { onEvent(LibraryEvent.ExportSelected) },
                        onRename = { renamingFolder = true },
                        onDelete = {
                            haptics.confirm()
                            // Folders need a decision about their contents; documents alone just go to Trash (undoable).
                            if (state.folderSelection.isNotEmpty()) confirmDelete = true else onEvent(LibraryEvent.DeleteSelected())
                        },
                    )
                } else {
                    LibraryTopBar(
                        title = state.folderName,
                        layout = state.layout,
                        scrollBehavior = scrollBehavior,
                        onBack = onBack,
                        onImport = onImport,
                        onLayout = { onEvent(LibraryEvent.LayoutChanged(it)) },
                        onNewFolder = { newFolder = true },
                        onOpenHistory = onOpenHistory,
                        onOpenTrash = onOpenTrash,
                        onOpenSettings = onOpenSettings,
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = if (showFab) 72.dp else 0.dp)) },
        floatingActionButton = {
            AnimatedVisibility(showFab, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                ScanFab(
                    expanded = fabExpanded,
                    onClick = { haptics.tick(); onEvent(LibraryEvent.ScanClicked) },
                    modifier = Modifier.sharedElement(SharedKeys.SCAN_FAB),
                )
            }
        },
        floatingActionButtonPosition = androidx.compose.material3.FabPosition.Center,
    ) { padding ->
        AnimatedContent(
            targetState = state.content is LibraryContent.Empty,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "content",
            modifier = Modifier.fillMaxSize().padding(padding),
        ) { empty ->
            if (empty) {
                // An interrupted first scan must stay reachable even before any document exists.
                Column(Modifier.fillMaxSize()) {
                    state.resumableSession?.let { session ->
                        ResumeBanner(
                            session,
                            { onEvent(LibraryEvent.ResumeSession) },
                            { onEvent(LibraryEvent.DiscardSession) },
                            Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                        )
                    }
                EmptyState(
                    title = stringResource(R.string.library_empty_title),
                    body = stringResource(R.string.library_empty_body),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    action = {
                        Button(onClick = { haptics.tick(); onEvent(LibraryEvent.ScanClicked) }) {
                            Icon(DeckleIcons.Camera, null)
                            Spacer(Modifier.width(Spacing.s))
                            Text(stringResource(R.string.library_scan_first))
                        }
                    },
                )
                }
            } else {
                LibraryGrid(state, onEvent, grid, haptics::select, onOpenCrumb, onNewFolder = { newFolder = true })
            }
        }
    }

    if (confirmMerge) {
        ConfirmDialog(
            title = pluralStringResource(R.plurals.library_merge_title, state.selection.size, state.selection.size),
            body = stringResource(R.string.library_merge_body),
            confirmLabel = stringResource(R.string.library_merge),
            onConfirm = { confirmMerge = false; onEvent(LibraryEvent.MergeSelected) },
            onDismiss = { confirmMerge = false },
        )
    }
    if (confirmDelete) {
        DeleteFoldersDialog(
            count = state.folderSelection.size,
            onChoose = { keep -> confirmDelete = false; onEvent(LibraryEvent.DeleteSelected(keepFolderContents = keep)) },
            onDismiss = { confirmDelete = false },
        )
    }
    if (newFolder) {
        RenameDialog(
            title = stringResource(R.string.folder_new),
            initial = "",
            hint = stringResource(R.string.folder_name_hint),
            onConfirm = { name -> newFolder = false; onEvent(LibraryEvent.CreateFolder(state.folderId, name)) },
            onDismiss = { newFolder = false },
        )
    }
    if (renamingFolder) {
        val folderId = state.folderSelection.singleOrNull()
        val current = state.allFolders.firstOrNull { it.id == folderId }
        if (current == null) {
            renamingFolder = false
        } else {
            RenameDialog(
                title = stringResource(R.string.folder_rename),
                initial = current.name,
                hint = stringResource(R.string.folder_name_hint),
                onConfirm = { name -> renamingFolder = false; onEvent(LibraryEvent.RenameFolder(current.id, name)) },
                onDismiss = { renamingFolder = false },
            )
        }
    }
    if (moving) {
        if (!state.selecting) {
            moving = false
        } else {
            MoveSheet(
                allFolders = state.allFolders,
                excluded = remember(state.folderSelection, state.allFolders) { moveExclusions() },
                startFolderId = state.folderId,
                onMove = { target -> moving = false; onEvent(LibraryEvent.MoveSelected(target)) },
                onCreateFolder = { parent, name -> onEvent(LibraryEvent.CreateFolder(parent, name)) },
                onDismiss = { moving = false },
            )
        }
    }
}

@Composable
private fun LibraryGrid(
    state: LibraryUiState,
    onEvent: (LibraryEvent) -> Unit,
    grid: LazyGridState,
    onSelectHaptic: () -> Unit,
    onOpenCrumb: (Int) -> Unit,
    onNewFolder: () -> Unit,
) {
    val context = LocalContext.current
    val tile = state.layout.tileSize()
    val rows = tile == null
    LazyVerticalGrid(
        columns = if (rows) GridCells.Fixed(1) else GridCells.Adaptive(tile!!.minCell),
        state = grid,
        contentPadding = PaddingValues(start = Spacing.l, end = Spacing.l, bottom = 112.dp),
        horizontalArrangement = Arrangement.spacedBy(if (tile == TileSize.LARGE) Spacing.l else Spacing.m),
        verticalArrangement = Arrangement.spacedBy(
            when (state.layout) {
                LibraryLayout.LARGE_GRID -> Spacing.xl
                LibraryLayout.GRID -> Spacing.l
                LibraryLayout.SMALL_GRID -> Spacing.m
                LibraryLayout.LIST -> Spacing.xs
                LibraryLayout.DETAILS -> 0.dp
            },
        ),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(top = Spacing.s)) {
                SearchField(state.query, { onEvent(LibraryEvent.QueryChanged(it)) })
                if (state.path.isNotEmpty() && state.query.isEmpty()) Breadcrumbs(state, onOpenCrumb, Modifier.padding(top = Spacing.s))
                AnimatedVisibility(state.query.isEmpty() && !state.selecting, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    SortChip(state.sort, { onEvent(LibraryEvent.SortChanged(it)) }, Modifier.padding(top = Spacing.m))
                }
            }
        }
        state.resumableSession?.let { session ->
            item(key = "resume", span = { GridItemSpan(maxLineSpan) }) {
                ResumeBanner(session, { onEvent(LibraryEvent.ResumeSession) }, { onEvent(LibraryEvent.DiscardSession) }, Modifier.animateItem())
            }
        }
        if (state.layout == LibraryLayout.DETAILS && state.content is LibraryContent.Items) {
            item(key = "details-header", span = { GridItemSpan(maxLineSpan) }) { DetailsHeader() }
        }
        when (val content = state.content) {
            LibraryContent.Loading -> if (rows) Unit else items(4, key = { "skeleton-$it" }) { SkeletonCard() }
            is LibraryContent.NoResults -> item(key = "noresults", span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(
                    title = stringResource(R.string.library_no_results, content.query),
                    body = stringResource(R.string.library_no_results_body),
                    illustration = {},
                    modifier = Modifier.padding(top = Spacing.xxxl).animateItem(),
                )
            }
            LibraryContent.FolderEmpty -> item(key = "folderempty", span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(
                    title = stringResource(R.string.folder_empty_title),
                    body = stringResource(R.string.folder_empty_body),
                    illustration = {},
                    modifier = Modifier.padding(top = Spacing.xxxl).animateItem(),
                    action = {
                        TextButton(onClick = onNewFolder) {
                            Icon(DeckleIcons.FolderAdd, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(Spacing.s))
                            Text(stringResource(R.string.folder_new))
                        }
                    },
                )
            }
            is LibraryContent.Items -> {
                items(content.folders, key = { "f-${it.id}" }) { folder ->
                    val subtitle = folder.subtitle()
                    val selected = folder.id in state.folderSelection
                    val onClick = { onEvent(LibraryEvent.FolderClicked(folder.id)) }
                    val onLongClick = { onSelectHaptic(); onEvent(LibraryEvent.FolderLongPressed(folder.id)) }
                    when (state.layout) {
                        LibraryLayout.LIST -> LibraryRow(RowThumb.Folder, folder.name, subtitle, selected, false, onClick, onLongClick, Modifier.animateItem())
                        LibraryLayout.DETAILS -> DetailsRow(
                            RowThumb.Folder, folder.name,
                            pluralStringResource(R.plurals.folder_items, folder.documentCount + folder.folderCount, folder.documentCount + folder.folderCount),
                            RelativeDate.format(context, folder.date), selected, false, onClick, onLongClick, Modifier.animateItem(),
                        )
                        else -> FolderCard(FolderCardModel(folder.id, folder.name, subtitle), selected, onClick, onLongClick, Modifier.animateItem(), tile!!)
                    }
                }
                items(content.documents, key = { it.card.id }) { item ->
                    val date = RelativeDate.format(context, item.date)
                    val card = item.card.copy(subtitle = date)
                    val selected = card.id in state.selection
                    val highlighted = card.id == state.highlightDocumentId
                    val onClick = { onEvent(LibraryEvent.CardClicked(card.id)) }
                    val onLongClick = { onSelectHaptic(); onEvent(LibraryEvent.CardLongPressed(card.id)) }
                    val pages = pluralStringResource(R.plurals.page_count, card.pageCount, card.pageCount)
                    when (state.layout) {
                        LibraryLayout.LIST -> LibraryRow(
                            RowThumb.Cover(card.coverPath), card.title, "$pages · $date", selected, highlighted, onClick, onLongClick, Modifier.animateItem(),
                        )
                        LibraryLayout.DETAILS -> DetailsRow(
                            RowThumb.Cover(card.coverPath), card.title, pages, date, selected, highlighted, onClick, onLongClick, Modifier.animateItem(),
                        )
                        else -> DocumentCard(
                            model = card,
                            selected = selected,
                            highlighted = highlighted,
                            onClick = onClick,
                            onLongClick = onLongClick,
                            modifier = Modifier.animateItem(),
                            coverModifier = Modifier.sharedElement(SharedKeys.cover(card.id)),
                            size = tile!!,
                        )
                    }
                }
            }
            LibraryContent.Empty -> Unit
        }
    }
}

private fun LibraryLayout.tileSize(): TileSize? = when (this) {
    LibraryLayout.LARGE_GRID -> TileSize.LARGE
    LibraryLayout.GRID -> TileSize.MEDIUM
    LibraryLayout.SMALL_GRID -> TileSize.SMALL
    LibraryLayout.LIST, LibraryLayout.DETAILS -> null
}

@Composable
private fun FolderItem.subtitle(): String {
    if (documentCount == 0 && folderCount == 0) return stringResource(R.string.folder_empty_items)
    return listOfNotNull(
        pluralStringResource(R.plurals.folder_documents, documentCount, documentCount).takeIf { documentCount > 0 },
        pluralStringResource(R.plurals.folder_folders, folderCount, folderCount).takeIf { folderCount > 0 },
    ).joinToString(" · ")
}

/** "Library › Taxes › 2025": every crumb but the last jumps back up the tree. */
@Composable
private fun Breadcrumbs(state: LibraryUiState, onOpenCrumb: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState(), reverseScrolling = true),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onOpenCrumb(-1) }, contentPadding = PaddingValues(horizontal = Spacing.s)) {
            Text(stringResource(R.string.library_root), maxLines = 1, softWrap = false)
        }
        state.path.forEachIndexed { i, folder ->
            Icon(DeckleIcons.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            val last = i == state.path.lastIndex
            TextButton(onClick = { onOpenCrumb(i) }, enabled = !last, contentPadding = PaddingValues(horizontal = Spacing.s)) {
                Text(
                    folder.name,
                    maxLines = 1,
                    softWrap = false,
                    color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryTopBar(
    title: String?,
    layout: LibraryLayout,
    scrollBehavior: TopAppBarScrollBehavior,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onLayout: (LibraryLayout) -> Unit,
    onNewFolder: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var viewMenu by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Text(
                title ?: stringResource(R.string.app_name),
                style = if (title == null) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            if (title != null) IconButton(onClick = onBack) { Icon(DeckleIcons.Back, stringResource(R.string.action_back)) }
        },
        actions = {
            IconButton(onClick = onImport) { Icon(DeckleIcons.Gallery, stringResource(R.string.library_import)) }
            Box {
                IconButton(onClick = { viewMenu = true }) { Icon(layout.icon(), stringResource(R.string.library_view)) }
                DropdownMenu(expanded = viewMenu, onDismissRequest = { viewMenu = false }) {
                    LibraryLayout.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(stringResource(option.label())) },
                            leadingIcon = { Icon(option.icon(), null) },
                            trailingIcon = { if (option == layout) Icon(DeckleIcons.Check, null) },
                            onClick = { viewMenu = false; onLayout(option) },
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(DeckleIcons.More, stringResource(R.string.action_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.folder_new)) },
                        leadingIcon = { Icon(DeckleIcons.FolderAdd, null) },
                        onClick = { menu = false; onNewFolder() },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_menu_history)) },
                        leadingIcon = { Icon(DeckleIcons.Clock, null) },
                        onClick = { menu = false; onOpenHistory() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_menu_trash)) },
                        leadingIcon = { Icon(DeckleIcons.Delete, null) },
                        onClick = { menu = false; onOpenTrash() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_menu_settings)) },
                        leadingIcon = { Icon(DeckleIcons.Settings, null) },
                        onClick = { menu = false; onOpenSettings() },
                    )
                }
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

private fun LibraryLayout.icon(): ImageVector = when (this) {
    LibraryLayout.LARGE_GRID -> DeckleIcons.GridLarge
    LibraryLayout.GRID -> DeckleIcons.Grid
    LibraryLayout.SMALL_GRID -> DeckleIcons.GridSmall
    LibraryLayout.LIST -> DeckleIcons.ListView
    LibraryLayout.DETAILS -> DeckleIcons.Details
}

private fun LibraryLayout.label(): Int = when (this) {
    LibraryLayout.LARGE_GRID -> R.string.layout_large
    LibraryLayout.GRID -> R.string.layout_grid
    LibraryLayout.SMALL_GRID -> R.string.layout_small
    LibraryLayout.LIST -> R.string.layout_list
    LibraryLayout.DETAILS -> R.string.layout_details
}

/** Icon-only actions (labels in the content descriptions and an overflow), so nothing wraps on narrow phones. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    state: LibraryUiState,
    onClose: () -> Unit,
    onMove: () -> Unit,
    onMerge: () -> Unit,
    onExport: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val docs = state.selection.size
    val folders = state.folderSelection.size
    val canExport = (docs == 1 && folders == 0) || (folders == 1 && docs == 0)
    val canMerge = docs >= 2 && folders == 0
    val canRename = folders == 1 && docs == 0
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(DeckleIcons.Close, stringResource(R.string.action_close)) } },
        title = {
            AnimatedContent(state.selectedCount, transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) }, label = "count") { n ->
                Text(pluralStringResource(R.plurals.library_selected, n, n), maxLines = 1)
            }
        },
        actions = {
            IconButton(onClick = onMove) { Icon(DeckleIcons.FolderMove, stringResource(R.string.library_move)) }
            IconButton(onClick = onExport, enabled = canExport) { Icon(DeckleIcons.Share, stringResource(R.string.library_export)) }
            IconButton(onClick = onDelete) { Icon(DeckleIcons.Delete, stringResource(R.string.action_delete)) }
            if (canMerge || canRename) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(DeckleIcons.More, stringResource(R.string.action_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (canRename) DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_rename)) },
                            leadingIcon = { Icon(DeckleIcons.Edit, null) },
                            onClick = { menu = false; onRename() },
                        )
                        if (canMerge) DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_merge)) },
                            leadingIcon = { Icon(DeckleIcons.Pages, null) },
                            onClick = { menu = false; onMerge() },
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        windowInsets = TopAppBarDefaults.windowInsets,
    )
}

/** The user decides what happens to a folder's contents (keep them one level up, or trash them). */
@Composable
private fun DeleteFoldersDialog(count: Int, onChoose: (keepContents: Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(DeckleIcons.Delete, null) },
        title = { Text(pluralStringResource(R.plurals.folder_delete_title, count, count)) },
        text = {
            Column {
                Text(stringResource(R.string.folder_delete_body))
                Spacer(Modifier.size(Spacing.m))
                DeleteOption(R.string.folder_delete_keep, R.string.folder_delete_keep_body, DeckleIcons.FolderMove, destructive = false) { onChoose(true) }
                DeleteOption(R.string.folder_delete_all, R.string.folder_delete_all_body, DeckleIcons.Delete, destructive = true) { onChoose(false) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun DeleteOption(title: Int, body: Int, icon: ImageVector, destructive: Boolean, onClick: () -> Unit) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        ListItem(
            headlineContent = { Text(stringResource(title), color = color) },
            supportingContent = { Text(stringResource(body)) },
            leadingContent = { Icon(icon, null, tint = color) },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        )
    }
}

@Composable
private fun SortChip(sort: SortOrder, onSort: (SortOrder) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(sort.label())
    val description = stringResource(R.string.library_sort_label, label)
    Box(modifier) {
        AssistChip(
            onClick = { open = true },
            label = { Text(label) },
            modifier = Modifier.semantics { contentDescription = description },
            leadingIcon = { Icon(DeckleIcons.Sort, null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortOrder.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.label())) },
                    trailingIcon = { if (option == sort) Icon(DeckleIcons.Check, null) },
                    onClick = { open = false; onSort(option) },
                )
            }
        }
    }
}

private fun SortOrder.label(): Int = when (this) {
    SortOrder.CREATED_DESC -> R.string.library_sort_created
    SortOrder.MODIFIED_DESC -> R.string.library_sort_modified
    SortOrder.TITLE_ASC -> R.string.library_sort_title
}

/** Spec 02 §5.2: an unfinished scan survives process death and is offered back here. */
@Composable
private fun ResumeBanner(session: ResumableSessionUi, onResume: () -> Unit, onDiscard: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onResume,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = Spacing.l, end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Icon(DeckleIcons.Pages, null)
            Spacer(Modifier.width(Spacing.m))
            Text(
                pluralStringResource(R.plurals.library_resume, session.pageCount, session.pageCount),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).padding(vertical = Spacing.l),
            )
            IconButton(onClick = onDiscard) { Icon(DeckleIcons.Close, stringResource(R.string.library_resume_discard)) }
            Icon(DeckleIcons.Forward, null, Modifier.padding(end = Spacing.m))
        }
    }
}

private const val MAX_IMPORT = 50
