package com.avyaan.deckle.feature.trash

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.avyaan.deckle.R
import com.avyaan.deckle.designsystem.components.ConfirmDialog
import com.avyaan.deckle.designsystem.components.DeckleTopBar
import com.avyaan.deckle.designsystem.components.EmptyState
import com.avyaan.deckle.designsystem.components.SelectionCheck
import com.avyaan.deckle.designsystem.icons.DeckleIcons
import com.avyaan.deckle.designsystem.theme.DeckleMotion
import com.avyaan.deckle.designsystem.theme.deckleSpring
import com.avyaan.deckle.designsystem.theme.rememberHaptics
import com.avyaan.deckle.ui.ObserveEffects
import java.io.File

private enum class Confirm { NONE, SELECTED, ALL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashRoute(onBack: () -> Unit, vm: TrashViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var confirm by rememberSaveable { mutableStateOf(Confirm.NONE) }
    val selecting = state.selection.isNotEmpty()

    ObserveEffects(vm.effects) { effect ->
        when (effect) {
            is TrashEffect.ShowMessage -> snackbar.showSnackbar(effect.text.resolve(context))
        }
    }
    BackHandler(selecting) { vm.onEvent(TrashEvent.ClearSelection) }

    Scaffold(
        topBar = {
            AnimatedContent(selecting, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "bar") { sel ->
                if (sel) {
                    DeckleTopBar(
                        title = { Text(pluralStringResource(R.plurals.library_selected, state.selection.size, state.selection.size)) },
                        onBack = { vm.onEvent(TrashEvent.ClearSelection) },
                        actions = {
                            TextButton(onClick = { vm.onEvent(TrashEvent.RestoreSelected) }) { Text(stringResource(R.string.action_restore)) }
                            TextButton(onClick = { confirm = Confirm.SELECTED }) {
                                Text(stringResource(R.string.trash_delete_forever), color = MaterialTheme.colorScheme.error)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    )
                } else {
                    DeckleTopBar(
                        title = stringResource(R.string.trash_title),
                        onBack = onBack,
                        actions = {
                            if (state.items.isNotEmpty()) {
                                TextButton(onClick = { confirm = Confirm.ALL }) { Text(stringResource(R.string.trash_empty_action)) }
                            }
                        },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!state.loading && state.items.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.trash_empty_title),
                body = stringResource(R.string.trash_empty_body),
                illustration = {
                    Icon(DeckleIcons.Delete, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.outline)
                },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
                items(state.items, key = { it.card.id }) { item ->
                    val selected = item.card.id in state.selection
                    ListItem(
                        headlineContent = { Text(item.card.title, maxLines = 1) },
                        supportingContent = {
                            Text(
                                pluralStringResource(R.plurals.trash_days_left, item.daysLeft, item.daysLeft),
                                color = if (item.daysLeft <= 3) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        leadingContent = {
                            Box(Modifier.size(48.dp, 62.dp).clip(MaterialTheme.shapes.extraSmall).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                                item.card.coverPath?.let { AsyncImage(File(it), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                                AnimatedVisibility(selected, enter = scaleIn(deckleSpring(DeckleMotion.Spring.Pop)) + fadeIn(), exit = scaleOut() + fadeOut(), modifier = Modifier.align(Alignment.Center)) {
                                    SelectionCheck()
                                }
                            }
                        },
                        trailingContent = {
                            if (!selecting) {
                                IconButton(onClick = { haptics.tick(); vm.onEvent(TrashEvent.RestoreOne(item.card.id)) }) {
                                    Icon(DeckleIcons.Restore, stringResource(R.string.action_restore))
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
                        modifier = Modifier
                            .animateItem()
                            .semantics { this.selected = selected }
                            .clickable { haptics.select(); vm.onEvent(TrashEvent.Toggle(item.card.id)) },
                    )
                }
            }
        }
    }

    if (confirm != Confirm.NONE) {
        ConfirmDialog(
            title = stringResource(R.string.trash_delete_forever_title),
            body = stringResource(R.string.trash_delete_forever_body),
            confirmLabel = stringResource(R.string.trash_delete_forever),
            destructive = true,
            onConfirm = {
                haptics.confirm()
                vm.onEvent(if (confirm == Confirm.ALL) TrashEvent.EmptyTrash else TrashEvent.DeleteSelectedForever)
                confirm = Confirm.NONE
            },
            onDismiss = { confirm = Confirm.NONE },
        )
    }
}
