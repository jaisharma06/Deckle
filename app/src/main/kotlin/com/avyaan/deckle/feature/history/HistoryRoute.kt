package com.avyaan.deckle.feature.history

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avyaan.deckle.R
import com.avyaan.deckle.designsystem.components.ConfirmDialog
import com.avyaan.deckle.designsystem.components.DeckleTopBar
import com.avyaan.deckle.designsystem.components.EmptyState
import com.avyaan.deckle.designsystem.icons.DeckleIcons
import com.avyaan.deckle.designsystem.theme.Spacing
import com.avyaan.deckle.domain.export.ExportDestination
import com.avyaan.deckle.domain.export.ExportFormat
import com.avyaan.deckle.domain.export.ExportRecord
import com.avyaan.deckle.ui.ObserveEffects
import com.avyaan.deckle.ui.RelativeDate
import com.avyaan.deckle.ui.SecureWindow
import com.avyaan.deckle.ui.openOutput
import com.avyaan.deckle.ui.outputsAvailable
import com.avyaan.deckle.ui.shareFiles
import kotlinx.coroutines.launch

@Composable
fun HistoryRoute(
    onBack: () -> Unit,
    onExportAgain: (documentId: String) -> Unit,
    vm: HistoryViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    ObserveEffects(vm.effects) { effect ->
        when (effect) {
            is HistoryEffect.OpenExport -> onExportAgain(effect.documentId)
            is HistoryEffect.Message -> snackbar.showSnackbar(effect.text.resolve(context))
        }
    }
    SecureWindow(state.secure)

    // Files can disappear after the export (cache cleanup, the user deleting them): check before handing
    // a dead link to another app, and say what happened instead.
    fun withOutputs(record: ExportRecord, action: () -> Unit) {
        scope.launch {
            if (context.outputsAvailable(record.outputs)) action()
            else snackbar.showSnackbar(resources.getString(R.string.history_unavailable))
        }
    }

    Scaffold(
        topBar = {
            DeckleTopBar(
                title = stringResource(R.string.history_title),
                onBack = onBack,
                actions = {
                    if (state.records.isNotEmpty()) {
                        TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.history_clear)) }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        AnimatedContent(state.loading to state.records.isEmpty(), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "history") { (loading, empty) ->
            when {
                loading -> Box(Modifier.fillMaxSize())
                empty -> EmptyState(
                    title = stringResource(R.string.history_empty_title),
                    body = stringResource(R.string.history_empty_body),
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = Spacing.l, end = Spacing.l, top = padding.calculateTopPadding() + Spacing.s, bottom = padding.calculateBottomPadding() + Spacing.l),
                    verticalArrangement = Arrangement.spacedBy(Spacing.m),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.records, key = { it.id }) { record ->
                        HistoryCard(
                            record = record,
                            onShare = { withOutputs(record) { context.shareFiles(record.outputs, record.mimeType) } },
                            onOpen = {
                                withOutputs(record) {
                                    if (!context.openOutput(record.outputs.first(), record.mimeType)) {
                                        scope.launch { snackbar.showSnackbar(resources.getString(R.string.history_no_app)) }
                                    }
                                }
                            },
                            onExportAgain = { vm.onEvent(HistoryEvent.ExportAgain(record)) },
                            onRemove = { vm.onEvent(HistoryEvent.Remove(record.id)) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.history_clear),
            body = stringResource(R.string.history_clear_body),
            confirmLabel = stringResource(R.string.history_clear),
            destructive = true,
            onConfirm = { confirmClear = false; vm.onEvent(HistoryEvent.Clear) },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun HistoryCard(
    record: ExportRecord,
    onShare: () -> Unit,
    onOpen: () -> Unit,
    onExportAgain: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val title = when (record.titles.size) {
        0 -> stringResource(R.string.app_name)
        1 -> record.titles.single()
        else -> pluralStringResource(R.plurals.history_more_documents, record.titles.size - 1, record.titles.first(), record.titles.size - 1)
    }
    val details = listOfNotNull(
        pluralStringResource(R.plurals.page_count, record.pageCount, record.pageCount),
        record.format.name,
        stringResource(record.destination.label()),
        stringResource(R.string.history_protected).takeIf { record.passwordProtected },
    ).joinToString(" · ")

    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(start = Spacing.l, top = Spacing.m, bottom = Spacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(record.format.icon(), null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.width(Spacing.m))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    Text(RelativeDate.format(context, record.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(DeckleIcons.More, stringResource(R.string.action_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.history_remove)) },
                            leadingIcon = { Icon(DeckleIcons.Delete, null) },
                            onClick = { menu = false; onRemove() },
                        )
                    }
                }
            }
            Spacer(Modifier.size(Spacing.s))
            // Scrolls sideways rather than squeezing labels on narrow screens.
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(end = Spacing.l),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                HistoryAction(DeckleIcons.Share, R.string.history_share, onShare)
                if (record.outputs.size == 1) HistoryAction(DeckleIcons.Eye, R.string.history_open, onOpen)
                if (record.documentIds.size == 1) HistoryAction(DeckleIcons.Forward, R.string.history_export_again, onExportAgain)
            }
        }
    }
}

@Composable
private fun HistoryAction(icon: ImageVector, label: Int, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.xs)) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.s))
        Text(stringResource(label), maxLines = 1, softWrap = false)
    }
}

private fun ExportFormat.icon(): ImageVector = when (this) {
    ExportFormat.PDF -> DeckleIcons.Pages
    ExportFormat.JPEG, ExportFormat.PNG -> DeckleIcons.Gallery
}

private fun ExportDestination.label(): Int = when (this) {
    ExportDestination.SHARE -> R.string.history_dest_share
    ExportDestination.FILE -> R.string.history_dest_file
    ExportDestination.FOLDER -> R.string.history_dest_folder
}
