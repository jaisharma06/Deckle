package com.avyaan.deckle.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.avyaan.deckle.R
import com.avyaan.deckle.designsystem.icons.DeckleIcons
import com.avyaan.deckle.designsystem.theme.DeckleMotion
import com.avyaan.deckle.designsystem.theme.Spacing
import com.avyaan.deckle.designsystem.theme.deckleSpring
import java.io.File

/** Tile sizes for the library's grid layouts (large covers → compact thumbnails). */
enum class TileSize(val minCell: Dp) {
    LARGE(156.dp), MEDIUM(108.dp), SMALL(80.dp);

    @Composable
    fun titleStyle(): TextStyle = when (this) {
        LARGE -> MaterialTheme.typography.titleSmall
        MEDIUM -> MaterialTheme.typography.labelLarge
        SMALL -> MaterialTheme.typography.labelMedium
    }
}

/** A library folder as the screen shows it. */
data class FolderCardModel(
    val id: String,
    val name: String,
    /** "3 documents · 1 folder", already formatted. */
    val subtitle: String,
)

/** Folder tile for the grid layouts: the same footprint as a document cover, so rows stay aligned. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderCard(
    model: FolderCardModel,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: TileSize = TileSize.LARGE,
) {
    val interaction = remember { MutableInteractionSource() }
    val scale by animateFloatAsState(if (selected) 0.93f else 1f, deckleSpring(DeckleMotion.Spring.Pop), label = "sel")
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier
            .semantics { this.selected = selected }
            .combinedClickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick, onLongClick = onLongClick)
            .pressScale(interaction),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.77f)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(shape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                DeckleIcons.Folder,
                null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.fillMaxSize(0.42f),
            )
            SelectionMark(selected, Modifier.align(Alignment.TopEnd).padding(Spacing.s))
        }
        Spacer(Modifier.height(if (size == TileSize.LARGE) Spacing.s else Spacing.xs))
        Text(model.name, style = size.titleStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = Spacing.xs))
        if (size != TileSize.SMALL) {
            Text(
                model.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Spacing.xs),
            )
        }
    }
}

/** What goes in the leading slot of a list/details row. */
sealed interface RowThumb {
    data class Cover(val path: String?) : RowThumb
    data object Folder : RowThumb
}

/**
 * One row of the List layout: thumbnail, name, one line of detail. Selection tints the row and swaps
 * the thumbnail's corner for a check, like a file manager.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryRow(
    thumb: RowThumb,
    title: String,
    subtitle: String,
    selected: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RowFrame(selected, highlighted, onClick, onLongClick, modifier.heightIn(min = 68.dp)) {
        Thumb(thumb, selected, 44.dp)
        Spacer(Modifier.width(Spacing.l))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (thumb is RowThumb.Folder) Icon(DeckleIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

/** Column header of the Details layout. */
@Composable
fun DetailsHeader(modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.s, vertical = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(28.dp + Spacing.m))
            HeaderCell(stringResource(R.string.library_col_name), Modifier.weight(1f))
            HeaderCell(stringResource(R.string.library_col_size), Modifier.width(DETAILS_SIZE), TextAlign.End)
            HeaderCell(stringResource(R.string.library_col_date), Modifier.width(DETAILS_DATE).padding(start = Spacing.m), TextAlign.End)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** One dense row of the Details layout: name | pages or items | date, like Explorer's Details view. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailsRow(
    thumb: RowThumb,
    name: String,
    size: String,
    date: String,
    selected: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RowFrame(selected, highlighted, onClick, onLongClick, modifier.heightIn(min = 48.dp)) {
        Thumb(thumb, selected, 28.dp)
        Spacer(Modifier.width(Spacing.m))
        Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(size, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.width(DETAILS_SIZE))
        Text(date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.width(DETAILS_DATE).padding(start = Spacing.m))
    }
}

private val DETAILS_SIZE = 64.dp
private val DETAILS_DATE = 104.dp

@Composable
private fun HeaderCell(text: String, modifier: Modifier, align: TextAlign = TextAlign.Start) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = align, maxLines = 1, modifier = modifier)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowFrame(
    selected: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    val tint = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        highlighted -> MaterialTheme.colorScheme.tertiaryContainer
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    Row(
        modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .clip(shape)
            .background(tint)
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = Spacing.s, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        content = content,
    )
}

@Composable
private fun Thumb(thumb: RowThumb, selected: Boolean, size: Dp) {
    val shape = MaterialTheme.shapes.small
    Box(Modifier.size(width = size, height = size * 1.25f)) {
        when (thumb) {
            is RowThumb.Cover -> Box(
                Modifier.fillMaxSize().paperShadow(shape, 1.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerLowest),
            ) {
                if (thumb.path != null) AsyncImage(File(thumb.path), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            RowThumb.Folder -> Box(
                Modifier.fillMaxSize().clip(shape).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(DeckleIcons.Folder, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.fillMaxSize(0.62f))
            }
        }
        SelectionMark(selected, Modifier.align(Alignment.BottomEnd).padding(1.dp), small = true)
    }
}

@Composable
private fun SelectionMark(selected: Boolean, modifier: Modifier, small: Boolean = false) {
    AnimatedVisibility(selected, enter = scaleIn(deckleSpring(DeckleMotion.Spring.Pop)) + fadeIn(), exit = scaleOut() + fadeOut(), modifier = modifier) {
        SelectionCheck(size = if (small) 16.dp else 28.dp)
    }
}
