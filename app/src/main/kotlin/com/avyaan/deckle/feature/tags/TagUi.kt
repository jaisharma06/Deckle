package com.avyaan.deckle.feature.tags

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.avyaan.deckle.R
import com.avyaan.deckle.designsystem.icons.DeckleIcons
import com.avyaan.deckle.designsystem.theme.Spacing
import com.avyaan.deckle.domain.tag.Tag
import com.avyaan.deckle.domain.tag.TagColor
import com.avyaan.deckle.domain.tag.TagRules
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class TagUi(val id: String, val name: String, val color: TagColor)

fun Tag.toUi() = TagUi(id, name, color)

/** Mid-tone swatches that read on both the light paper theme and the dark one. */
fun TagColor.swatch(): Color = when (this) {
    TagColor.CLAY -> Color(0xFFC0603A)
    TagColor.AMBER -> Color(0xFFCF9418)
    TagColor.OLIVE -> Color(0xFF7D8B2E)
    TagColor.SAGE -> Color(0xFF4F9A6C)
    TagColor.TEAL -> Color(0xFF268A8A)
    TagColor.SKY -> Color(0xFF3A7DC2)
    TagColor.INDIGO -> Color(0xFF5A5FC8)
    TagColor.PLUM -> Color(0xFF9A4E9A)
}

@Composable
fun TagDot(color: TagColor, modifier: Modifier = Modifier, size: Int = 10) {
    Box(modifier.size(size.dp).clip(CircleShape).background(color.swatch()))
}

/**
 * Picks tags. With [selected] it toggles (a document's tags); without, one tap applies the tag and closes
 * (tagging a library selection). A name typed at the top creates a tag, or reuses one with that name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagPickerSheet(
    tags: ImmutableList<TagUi>,
    selected: Set<String>?,
    onPick: (TagUi) -> Unit,
    onCreate: (String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = Spacing.l)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.l), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tags_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (tags.isNotEmpty()) TextButton(onClick = onManage) { Text(stringResource(R.string.tags_manage)) }
            }
            NewTagField(onCreate, Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s))
            if (tags.isEmpty()) {
                Text(
                    stringResource(R.string.tags_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.l),
                )
            }
            LazyColumn {
                items(tags, key = { it.id }) { tag ->
                    val on = selected?.contains(tag.id) == true
                    ListItem(
                        headlineContent = { Text(tag.name) },
                        leadingContent = { TagDot(tag.color, size = 14) },
                        trailingContent = { if (selected != null) Checkbox(checked = on, onCheckedChange = { onPick(tag) }) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.semantics { this.selected = on }.clickable { onPick(tag) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NewTagField(onCreate: (String) -> Unit, modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf("") }
    val submit = {
        if (TagRules.clean(text) != null) {
            onCreate(text)
            text = ""
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { if (it.length <= TagRules.MAX_LENGTH) text = it },
        placeholder = { Text(stringResource(R.string.tags_new_hint)) },
        leadingIcon = { Icon(DeckleIcons.Tag, null) },
        trailingIcon = {
            IconButton(onClick = submit, enabled = TagRules.clean(text) != null) { Icon(DeckleIcons.Add, stringResource(R.string.tags_add)) }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Rename, recolour or delete tags. Deleting a tag never deletes documents. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagManagerSheet(
    tags: ImmutableList<TagUi>,
    onEdit: (id: String, name: String, color: TagColor) -> Unit,
    onDelete: (id: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = Spacing.l)) {
            Text(stringResource(R.string.tags_manage), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = Spacing.l))
            Text(
                stringResource(R.string.tags_manage_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
            )
            LazyColumn {
                items(tags, key = { it.id }) { tag ->
                    ListItem(
                        headlineContent = { Text(tag.name) },
                        leadingContent = { TagDot(tag.color, size = 14) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { editing = tag.id }) { Icon(DeckleIcons.Edit, stringResource(R.string.tags_edit)) }
                                IconButton(onClick = { onDelete(tag.id) }) { Icon(DeckleIcons.Delete, stringResource(R.string.tags_delete)) }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
    tags.firstOrNull { it.id == editing }?.let { tag ->
        TagEditDialog(tag, onSave = { name, color -> editing = null; onEdit(tag.id, name, color) }, onDismiss = { editing = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagEditDialog(tag: TagUi, onSave: (String, TagColor) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(tag.name) }
    var color by rememberSaveable { mutableStateOf(tag.color) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tags_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                OutlinedTextField(name, { if (it.length <= TagRules.MAX_LENGTH) name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    TagColor.entries.forEach { c ->
                        val label = stringResource(R.string.tags_colour, c.ordinal + 1)
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(c.swatch())
                                .then(if (c == color) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .semantics { contentDescription = label; selected = c == color }
                                .clickable { color = c },
                            contentAlignment = Alignment.Center,
                        ) { if (c == color) Icon(DeckleIcons.Check, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, color) }, enabled = TagRules.clean(name) != null) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** A row of a document's tags, for the document screen header. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagRow(tags: List<TagUi>, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        tags.forEach { tag ->
            Row(
                Modifier
                    .clip(MaterialTheme.shapes.small)
                    .background(tag.color.swatch().copy(alpha = 0.16f))
                    .padding(horizontal = Spacing.s, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TagDot(tag.color, size = 8)
                Spacer(Modifier.width(Spacing.xs))
                Text(tag.name, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
