package com.avyaan.deckle.designsystem.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.avyaan.deckle.R
import com.avyaan.deckle.designsystem.theme.DeckleMotion
import com.avyaan.deckle.designsystem.theme.Spacing
import com.avyaan.deckle.designsystem.theme.deckleSpring
import com.avyaan.deckle.designsystem.theme.tabular
import com.avyaan.deckle.domain.imaging.Adjustments
import com.avyaan.deckle.domain.imaging.EnhancementMode
import com.avyaan.deckle.ui.labels.label
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * FR-31 filter strip (spec 05 §4.5): real previews of the current page, radio semantics. The selected
 * thumb gains a primary ring and grows slightly. Scrolls horizontally; the selection is kept in view
 * (e.g. when the page's saved filter is near the end of the list).
 */
@Composable
fun FilterStrip(
    selected: EnhancementMode,
    thumbs: Map<EnhancementMode, String>,
    onSelect: (EnhancementMode) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = Spacing.l),
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val modes = EnhancementMode.entries
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (modes.indexOf(selected) - 1).coerceAtLeast(0))
    LaunchedEffect(selected) {
        val index = modes.indexOf(selected)
        val visible = listState.layoutInfo.visibleItemsInfo
        val fullyVisible = visible.any { it.index == index && it.offset >= 0 && it.offset + it.size <= listState.layoutInfo.viewportEndOffset }
        if (!fullyVisible) listState.animateScrollToItem((index - 1).coerceAtLeast(0))
    }
    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth().selectableGroup(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        items(modes, key = { it.name }) { mode ->
            val isSelected = mode == selected
            val scale by animateFloatAsState(if (isSelected) 1.06f else 1f, deckleSpring(DeckleMotion.Spring.Pop), label = "s")
            Column(
                Modifier.selectable(isSelected, role = Role.RadioButton) { onSelect(mode) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .padding(top = Spacing.xs)
                        .size(64.dp)
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .then(if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier),
                ) {
                    // Crossfade from shimmer to the real thumbnail as each preview finishes rendering.
                    Crossfade(thumbs[mode], animationSpec = tween(DeckleMotion.SHORT), label = "thumb") { path ->
                        if (path != null) {
                            AsyncImage(File(path), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            Box(Modifier.fillMaxSize().shimmer())
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    stringResource(mode.label()),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else contentColor,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * FR-33 brightness and contrast, shown as −100…+100 with a detent at 0 (haptic tick when crossing it).
 * [onPreview] fires while dragging; [onCommit] on release or Reset.
 */
@Composable
fun AdjustPanel(
    value: Adjustments,
    onPreview: (Adjustments) -> Unit,
    onCommit: (Adjustments) -> Unit,
    onDetent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var brightness by remember(value.brightness) { mutableFloatStateOf(value.brightness) }
    var contrast by remember(value.contrast) { mutableFloatStateOf(value.contrast) }
    Column(modifier.fillMaxWidth().padding(horizontal = Spacing.l)) {
        AdjustSlider(R.string.adjust_brightness, brightness, { brightness = it; onPreview(Adjustments(it, contrast)) }, { onCommit(Adjustments(brightness, contrast)) }, onDetent)
        AdjustSlider(R.string.adjust_contrast, contrast, { contrast = it; onPreview(Adjustments(brightness, it)) }, { onCommit(Adjustments(brightness, contrast)) }, onDetent)
        TextButton(
            onClick = { brightness = 0f; contrast = 0f; onCommit(Adjustments.NEUTRAL) },
            enabled = brightness != 0f || contrast != 0f,
            modifier = Modifier.align(Alignment.End),
        ) { Text(stringResource(R.string.action_reset)) }
    }
}

@Composable
private fun AdjustSlider(label: Int, value: Float, onChange: (Float) -> Unit, onDone: () -> Unit, onDetent: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(96.dp))
        Slider(
            value = value,
            onValueChange = { raw ->
                // Detent: values near zero stick to zero, with a tick as we land there.
                val v = if (abs(raw) < DETENT) 0f else raw
                if (v == 0f && value != 0f) onDetent()
                onChange(v)
            },
            onValueChangeFinished = onDone,
            valueRange = -1f..1f,
            modifier = Modifier.weight(1f),
        )
        Text(
            (value * 100).roundToInt().let { if (it > 0) "+$it" else "$it" },
            style = MaterialTheme.typography.labelLarge.tabular,
            modifier = Modifier.width(44.dp).padding(start = Spacing.s),
        )
    }
}

private const val DETENT = 0.04f
