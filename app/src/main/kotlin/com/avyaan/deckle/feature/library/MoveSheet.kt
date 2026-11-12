package com.avyaan.deckle.feature.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.avyaan.deckle.R
import com.avyaan.deckle.designsystem.components.RenameDialog
import com.avyaan.deckle.designsystem.icons.DeckleIcons
import com.avyaan.deckle.designsystem.theme.Spacing
import com.avyaan.deckle.domain.folder.Folder

/**
 * "Move to…" (folders feature): browse the folder tree from the library root and drop the selection into
 * any folder. Folders being moved — and everything below them — are greyed out, since a folder can't go
 * inside itself. A folder can be created on the spot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveSheet(
    allFolders: List<Folder>,
    excluded: Set<String>,
    startFolderId: String?,
    onMove: (target: String?) -> Unit,
    onCreateFolder: (parentId: String?, name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var browsing by rememberSaveable { mutableStateOf(startFolderId?.takeIf { it !in excluded }) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val byId = allFolders.associateBy { it.id }
    // The folder being browsed may vanish (deleted elsewhere): fall back to the root.
    val current = browsing?.let(byId::get)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = Spacing.l)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                if (current != null) {
                    IconButton(onClick = { browsing = current.parentId }) { Icon(DeckleIcons.Back, stringResource(R.string.action_back)) }
                } else {
                    Spacer(Modifier.width(Spacing.l))
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.move_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        current?.name ?: stringResource(R.string.library_root),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { creating = true }) { Icon(DeckleIcons.FolderAdd, stringResource(R.string.folder_new)) }
            }
            AnimatedContent(
                current?.id,
                transitionSpec = { (slideInHorizontally { it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut()) },
                label = "move-level",
            ) { levelId ->
                // Computed per level, so the outgoing level keeps showing its own folders while it slides away.
                val children = allFolders.filter { it.parentId == levelId }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp)) {
                    if (children.isEmpty()) {
                        Text(
                            stringResource(R.string.move_no_subfolders),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center).padding(Spacing.xl),
                        )
                    } else {
                        LazyColumn {
                            items(children, key = { it.id }) { folder ->
                                val blocked = folder.id in excluded
                                ListItem(
                                    headlineContent = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    leadingContent = { Icon(DeckleIcons.Folder, null) },
                                    trailingContent = { if (!blocked) Icon(DeckleIcons.ChevronRight, null, Modifier.size(20.dp)) },
                                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                    modifier = Modifier
                                        .alpha(if (blocked) 0.38f else 1f)
                                        .then(if (blocked) Modifier else Modifier.clickable { browsing = folder.id }),
                                )
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.s), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_cancel), maxLines = 1) }
                Button(onClick = { onMove(current?.id) }, modifier = Modifier.weight(1f)) {
                    Icon(DeckleIcons.FolderMove, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.s))
                    Text(stringResource(R.string.move_here), maxLines = 1, softWrap = false)
                }
            }
        }
    }

    if (creating) {
        RenameDialog(
            title = stringResource(R.string.folder_new),
            initial = "",
            hint = stringResource(R.string.folder_name_hint),
            onConfirm = { name -> creating = false; onCreateFolder(current?.id, name) },
            onDismiss = { creating = false },
        )
    }
}
