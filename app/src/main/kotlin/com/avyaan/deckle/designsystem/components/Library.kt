package com.avyaan.deckle.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.avyaan.deckle.R
import com.avyaan.deckle.designsystem.icons.DeckleIcons
import com.avyaan.deckle.designsystem.theme.DeckleMotion
import com.avyaan.deckle.designsystem.theme.LocalReducedMotion
import com.avyaan.deckle.designsystem.theme.Spacing
import com.avyaan.deckle.designsystem.theme.deckleSpring
import com.avyaan.deckle.domain.document.Snippet
import java.io.File

/** Pill-shaped search field (spec 06 §1). A plain field rather than M3 SearchBar: results live in the grid itself. */
@Composable
fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    Surface(modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(start = Spacing.l, end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Icon(DeckleIcons.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(Spacing.m))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(stringResource(R.string.library_search_hint), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AnimatedVisibility(query.isNotEmpty(), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                IconButton(onClick = { onQueryChange(""); focus.clearFocus() }) {
                    Icon(DeckleIcons.Close, stringResource(R.string.library_clear_search))
                }
            }
        }
    }
}

/** Data a library card needs; built by the ViewModel. */
data class DocumentCardModel(
    val id: String,
    val title: String,
    val pageCount: Int,
    val subtitle: String,
    val coverPath: String?,
    val snippet: Snippet?,
)

/**
 * A document in the grid (FR-1): paper-like cover with a page-count badge, title, date — or the matching
 * text snippet while searching (FR-3). Selected cards shrink slightly and gain a check that pops in.
 * A freshly created document glows briefly so the eye finds it (spec 05 §4.7).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentCard(
    model: DocumentCardModel,
    selected: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverModifier: Modifier = Modifier,
    size: TileSize = TileSize.LARGE,
) {
    val interaction = remember { MutableInteractionSource() }
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(if (selected) 0.93f else 1f, deckleSpring(DeckleMotion.Spring.Pop), label = "sel")
    val shape = MaterialTheme.shapes.medium
    val coverLabel = stringResource(R.string.cd_document_cover, model.title)
    val glow = if (highlighted && !reduced) {
        val t = rememberInfiniteTransition(label = "glow")
        t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "glowA").value
    } else if (highlighted) 1f else 0f

    Column(
        modifier
            .semantics { this.selected = selected }
            .combinedClickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick, onLongClick = onLongClick)
            .pressScale(interaction),
    ) {
        Box(
            coverModifier
                .fillMaxWidth()
                .aspectRatio(0.77f)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .paperShadow(shape)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .then(
                    when {
                        selected -> Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape)
                        glow > 0f -> Modifier.border(3.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = glow), shape)
                        else -> Modifier
                    },
                ),
        ) {
            if (model.coverPath != null) {
                AsyncImage(
                    model = File(model.coverPath),
                    contentDescription = coverLabel,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize().shimmer().semantics { contentDescription = coverLabel })
            }
            if (size != TileSize.SMALL) CountBadge(model.pageCount, Modifier.align(Alignment.BottomEnd).padding(Spacing.s))
            androidx.compose.animation.AnimatedVisibility(
                selected,
                enter = scaleIn(deckleSpring(DeckleMotion.Spring.Pop)) + fadeIn(),
                exit = scaleOut() + fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.s),
            ) { SelectionCheck() }
        }
        Spacer(Modifier.height(if (size == TileSize.LARGE) Spacing.s else Spacing.xs))
        Text(
            model.title,
            style = size.titleStyle(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Spacing.xs),
        )
        val snippet = model.snippet
        if (size == TileSize.SMALL) {
            // Small tiles: title only, so many fit per row.
        } else if (snippet != null) {
            Text(
                snippet.annotated(SpanStyle(background = MaterialTheme.colorScheme.primaryContainer, fontWeight = FontWeight.SemiBold)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Spacing.xs),
            )
        } else {
            Text(
                model.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = Spacing.xs),
            )
        }
    }
}

fun Snippet.annotated(highlight: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    highlights.forEach { r ->
        val start = r.first.coerceIn(0, text.length)
        val end = (r.last + 1).coerceIn(start, text.length)
        if (end > start) addStyle(highlight, start, end)
    }
}

/**
 * The one primary action (FR-5): bottom-centre, labelled while the list is at the top and collapsing to an
 * icon as the user scrolls, so it never hides the last row of covers.
 */
@Composable
fun ScanFab(expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val label = stringResource(R.string.library_scan)
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 6.dp,
        modifier = modifier.height(56.dp).pressScale(interaction, 0.94f).semantics { contentDescription = label },
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(DeckleIcons.Camera, null, Modifier.size(24.dp))
            AnimatedVisibility(expanded, enter = expandHorizontally() + fadeIn(), exit = shrinkHorizontally() + fadeOut()) {
                Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = Spacing.m, end = Spacing.xs))
            }
        }
    }
}
