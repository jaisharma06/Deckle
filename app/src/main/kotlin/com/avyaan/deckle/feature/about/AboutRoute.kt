package com.avyaan.deckle.feature.about

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.avyaan.deckle.BuildConfig
import com.avyaan.deckle.R
import com.avyaan.deckle.designsystem.components.DeckleTopBar
import com.avyaan.deckle.designsystem.theme.DeckleMotion
import com.avyaan.deckle.designsystem.theme.LocalReducedMotion
import com.avyaan.deckle.designsystem.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** FR-74: the privacy promise, stated plainly. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutRoute(onBack: () -> Unit) {
    var showLicences by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = { DeckleTopBar("", onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(Spacing.xl))
            ShieldPaperIllustration()
            Spacer(Modifier.height(Spacing.xxl))
            Text(stringResource(R.string.about_headline), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Spacing.l))
            Text(
                stringResource(R.string.about_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.xxl))
            Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(Spacing.l))
            OutlinedButton(onClick = { showLicences = true }) { Text(stringResource(R.string.about_licenses)) }
            Spacer(Modifier.height(Spacing.xxl))
        }
    }
    if (showLicences) {
        ModalBottomSheet(onDismissRequest = { showLicences = false }) { LicencesText() }
    }
}

/** Bundled notices (assets/licenses). No oss-licenses plugin: it would pull in Play Services. */
@Composable
private fun LicencesText() {
    val context = LocalContext.current
    val text by produceState("") {
        value = withContext(Dispatchers.IO) {
            listOf("NOTICES.txt", "Inter-OFL.txt").joinToString("\n\n────────\n\n") { name ->
                runCatching { context.assets.open("licenses/$name").bufferedReader().use { it.readText() } }.getOrDefault("")
            }
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl, vertical = Spacing.l),
    )
}

/** A page tucked behind a shield; the check mark draws itself once (static with reduced motion). */
@Composable
private fun ShieldPaperIllustration() {
    val reduced = LocalReducedMotion.current
    val check = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduced) check.animateTo(1f, tween(DeckleMotion.DELIBERATE, delayMillis = 200, easing = DeckleMotion.Decelerate)) }
    val paper = MaterialTheme.colorScheme.surfaceContainerLowest
    val outline = MaterialTheme.colorScheme.outlineVariant
    val shield = MaterialTheme.colorScheme.primary
    val onShield = MaterialTheme.colorScheme.onPrimary

    Canvas(Modifier.size(150.dp)) {
        val w = size.width
        // Paper, slightly rotated feel via offset
        val sheet = Size(w * 0.5f, w * 0.64f)
        val o = Offset(w * 0.12f, w * 0.1f)
        drawRoundRect(paper, o, sheet, CornerRadius(w * 0.03f))
        drawRoundRect(outline, o, sheet, CornerRadius(w * 0.03f), style = Stroke(w * 0.012f))
        repeat(4) { i ->
            val y = o.y + sheet.height * (0.2f + i * 0.15f)
            drawLine(outline, Offset(o.x + sheet.width * 0.15f, y), Offset(o.x + sheet.width * 0.8f, y), w * 0.022f, StrokeCap.Round)
        }
        // Shield
        val sx = w * 0.42f; val sy = w * 0.32f; val sw = w * 0.46f; val sh = w * 0.56f
        val path = Path().apply {
            moveTo(sx + sw / 2, sy)
            lineTo(sx + sw, sy + sh * 0.16f)
            cubicTo(sx + sw, sy + sh * 0.62f, sx + sw * 0.72f, sy + sh * 0.88f, sx + sw / 2, sy + sh)
            cubicTo(sx + sw * 0.28f, sy + sh * 0.88f, sx, sy + sh * 0.62f, sx, sy + sh * 0.16f)
            close()
        }
        drawPath(path, shield)
        // Check mark, drawn progressively
        val a = Offset(sx + sw * 0.28f, sy + sh * 0.5f)
        val b = Offset(sx + sw * 0.45f, sy + sh * 0.66f)
        val c = Offset(sx + sw * 0.74f, sy + sh * 0.36f)
        val p = check.value
        val stroke = Stroke(w * 0.04f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val first = (p / 0.4f).coerceAtMost(1f)
        val second = ((p - 0.4f) / 0.6f).coerceIn(0f, 1f)
        val checkPath = Path().apply {
            moveTo(a.x, a.y)
            lineTo(a.x + (b.x - a.x) * first, a.y + (b.y - a.y) * first)
            if (second > 0f) lineTo(b.x + (c.x - b.x) * second, b.y + (c.y - b.y) * second)
        }
        drawPath(checkPath, onShield, style = stroke)
    }
}
