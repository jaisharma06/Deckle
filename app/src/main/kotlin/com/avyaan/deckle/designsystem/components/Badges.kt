package com.avyaan.deckle.designsystem.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.avyaan.deckle.R
import com.avyaan.deckle.designsystem.icons.DeckleIcons
import com.avyaan.deckle.designsystem.theme.LocalReducedMotion
import com.avyaan.deckle.designsystem.theme.tabular
import com.avyaan.deckle.domain.ocr.OcrStatus

/** Page-count pill on a cover. */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.extraSmall, color = Color.Black.copy(alpha = 0.6f), contentColor = Color.White) {
        AnimatedContent(count, label = "count") { n ->
            Text(n.toString(), style = MaterialTheme.typography.labelMedium.tabular, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}

/**
 * FR-44 per-page OCR status. Queued/running/failed are always visible; "done" is shown as a check that the
 * caller hides after a moment so the grid stays clean (spec 05 §4.7).
 */
@Composable
fun OcrBadge(status: OcrStatus, modifier: Modifier = Modifier) {
    val label = stringResource(
        when (status) {
            OcrStatus.QUEUED -> R.string.ocr_queued
            OcrStatus.RUNNING -> R.string.ocr_running
            OcrStatus.DONE -> R.string.ocr_done
            OcrStatus.FAILED -> R.string.ocr_failed
        },
    )
    val container = when (status) {
        OcrStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
        OcrStatus.DONE -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when (status) {
        OcrStatus.FAILED -> MaterialTheme.colorScheme.onErrorContainer
        OcrStatus.DONE -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier
            .size(24.dp)
            .background(container, CircleShape)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(status, transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) }, label = "ocr") { s ->
            when (s) {
                OcrStatus.QUEUED -> Icon(DeckleIcons.Clock, null, Modifier.size(14.dp), tint = content)
                OcrStatus.RUNNING -> Spinner(content)
                OcrStatus.DONE -> Icon(DeckleIcons.Check, null, Modifier.size(14.dp), tint = content)
                OcrStatus.FAILED -> Icon(DeckleIcons.Warning, null, Modifier.size(14.dp), tint = content)
            }
        }
    }
}

@Composable
private fun Spinner(color: Color) {
    val reduced = LocalReducedMotion.current
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Icon(DeckleIcons.Restore, null, Modifier.size(14.dp).rotate(if (reduced) 0f else angle), tint = color)
}

/** A selection check that pops in with overshoot. */
@Composable
fun SelectionCheck(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 28.dp) {
    val label = stringResource(R.string.cd_selected)
    Box(
        modifier
            .size(size)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(DeckleIcons.Check, null, Modifier.size(size * 0.64f), tint = MaterialTheme.colorScheme.onPrimary)
    }
}

/** For decisions the user must make (irreversible actions). Prefer undo snackbars otherwise. */
@Composable
fun ConfirmDialog(
    title: String,
    body: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismissLabel: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = body?.let { { Text(it) } },
        shape = RoundedCornerShape(28.dp),
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel ?: stringResource(R.string.action_cancel)) } },
    )
}
