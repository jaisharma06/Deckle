package com.avyaan.deckle.feature.markup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.avyaan.deckle.R
import com.avyaan.deckle.data.markup.MarkupPainter
import com.avyaan.deckle.designsystem.components.ConfirmDialog
import com.avyaan.deckle.designsystem.icons.DeckleIcons
import com.avyaan.deckle.designsystem.theme.CameraColors
import com.avyaan.deckle.designsystem.theme.CameraTheme
import com.avyaan.deckle.designsystem.theme.Spacing
import com.avyaan.deckle.designsystem.theme.rememberHaptics
import com.avyaan.deckle.domain.geometry.RectN
import com.avyaan.deckle.domain.markup.MarkupGeometry
import com.avyaan.deckle.domain.markup.MarkupItem
import com.avyaan.deckle.domain.markup.SavedSignature
import com.avyaan.deckle.ui.ObserveEffects
import com.avyaan.deckle.ui.SecureWindow
import java.io.File
import kotlin.math.abs

/** Pen and highlighter widths, as fractions of the page width. */
private const val PEN_WIDTH = 0.0045f
private const val HIGHLIGHTER_WIDTH = 0.026f
private const val TEXT_SIZE = 0.032f

private val PEN_COLORS = listOf(MarkupItem.INK_BLACK, MarkupItem.INK_BLUE, MarkupItem.INK_RED, MarkupItem.INK_GREEN)
private val HIGHLIGHT_COLORS = listOf(MarkupItem.HIGHLIGHT_YELLOW, MarkupItem.HIGHLIGHT_GREEN, MarkupItem.HIGHLIGHT_PINK)

/** Where a text mark is being typed: a new one at ([x], [y]), or an existing one ([editId]). */
private data class TextRequest(val x: Float, val y: Float, val editId: String? = null, val initial: String = "")

/** An item being dragged/pinched, in screen pixels and gesture units, before it is committed. */
private data class Live(val id: String, val pan: Offset, val zoom: Float, val rotation: Float)

@Composable
fun MarkupRoute(onClose: () -> Unit, vm: MarkupViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var signing by rememberSaveable { mutableStateOf(false) }
    var drawingSignature by rememberSaveable { mutableStateOf(false) }
    var textRequest by remember { mutableStateOf<TextRequest?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    ObserveEffects(vm.effects) { effect ->
        when (effect) {
            MarkupEffect.Close -> onClose()
            is MarkupEffect.Message -> message = effect.text.resolve(context)
        }
    }
    SecureWindow(state.secure)
    val close = { if (state.dirty) confirmDiscard = true else onClose() }
    BackHandler(onBack = close)

    CameraTheme {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Row(
                Modifier.fillMaxWidth().background(CameraColors.Scrim).statusBarsPadding().padding(horizontal = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = close) { Icon(DeckleIcons.Close, stringResource(R.string.action_close), tint = Color.White) }
                Text(stringResource(R.string.markup_title), color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { haptics.tick(); vm.onEvent(MarkupEvent.Undo) }, enabled = state.canUndo) {
                    Icon(DeckleIcons.Undo, stringResource(R.string.markup_undo), tint = if (state.canUndo) Color.White else Color.White.copy(alpha = 0.35f))
                }
                IconButton(onClick = { haptics.tick(); vm.onEvent(MarkupEvent.Redo) }, enabled = state.canRedo) {
                    Icon(DeckleIcons.Redo, stringResource(R.string.markup_redo), tint = if (state.canRedo) Color.White else Color.White.copy(alpha = 0.35f))
                }
                TextButton(onClick = { haptics.confirm(); vm.onEvent(MarkupEvent.Save) }, enabled = !state.loading && !state.missing) {
                    Text(stringResource(R.string.action_done), color = CameraColors.Ring)
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth().padding(Spacing.m), contentAlignment = Alignment.Center) {
                when {
                    state.loading -> CircularProgressIndicator(color = CameraColors.Ring)
                    state.missing -> Text(stringResource(R.string.error_not_found), color = Color.White)
                    else -> Editor(
                        state = state,
                        onEvent = vm::onEvent,
                        newId = vm::newId,
                        onText = { textRequest = it },
                        onStroke = haptics::tick,
                    )
                }
            }

            Toolbars(
                state = state,
                onEvent = { haptics.tick(); vm.onEvent(it) },
                onSign = { signing = true },
                onEditText = { item -> textRequest = TextRequest(item.x, item.y, item.id, item.text) },
            )
        }

        textRequest?.let { req ->
            TextDialog(
                initial = req.initial,
                onDone = { text ->
                    textRequest = null
                    if (req.editId != null) vm.onEvent(MarkupEvent.EditText(req.editId, text))
                    else if (text.isNotBlank()) vm.onEvent(MarkupEvent.Add(MarkupItem.Text(vm.newId(), req.x, req.y, text.trimEnd(), TEXT_SIZE, state.penColor)))
                },
                onDismiss = { textRequest = null },
            )
        }
        if (confirmDiscard) {
            ConfirmDialog(
                title = stringResource(R.string.markup_discard_title),
                body = stringResource(R.string.markup_discard_body),
                confirmLabel = stringResource(R.string.markup_discard),
                destructive = true,
                onConfirm = { confirmDiscard = false; onClose() },
                onDismiss = { confirmDiscard = false },
            )
        }
        message?.let { text ->
            AlertDialog(onDismissRequest = { message = null }, text = { Text(text) }, confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(R.string.action_ok)) } })
        }
    }

    if (signing) {
        SignatureSheet(
            signatures = state.signatures,
            onPick = { signing = false; haptics.confirm(); vm.onEvent(MarkupEvent.PlaceSignature(it.ink)) },
            onDelete = { vm.onEvent(MarkupEvent.DeleteSignature(it.id)) },
            onDrawNew = { signing = false; drawingSignature = true },
            onDismiss = { signing = false },
        )
    }
    if (drawingSignature) {
        SignaturePad(
            onSave = { strokes -> drawingSignature = false; haptics.confirm(); vm.onEvent(MarkupEvent.SaveSignature(strokes)) },
            onDismiss = { drawingSignature = false },
        )
    }
}

@Composable
private fun Editor(
    state: MarkupUiState,
    onEvent: (MarkupEvent) -> Unit,
    newId: () -> String,
    onText: (TextRequest) -> Unit,
    onStroke: () -> Unit,
) {
    val painter = remember { MarkupPainter() }
    val stroke = remember { mutableStateListOf<Offset>() }
    var rect by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    var live by remember { mutableStateOf<Live?>(null) }
    val aspect = state.aspect
    val measure: (MarkupItem.Text) -> Pair<Float, Float> = { painter.textSize(it, aspect) }
    val selectedColor = MaterialTheme.colorScheme.primary

    FittedPage(state.widthPx, state.heightPx) {
        AsyncImage(File(state.imagePath), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(state.tool, state.markup, state.selectedId, state.penColor, state.highlightColor) {
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    fun norm(o: Offset) = Offset((o.x / w).coerceIn(0f, 1f), (o.y / h).coerceIn(0f, 1f))
                    fun hit(o: Offset): MarkupItem? {
                        val p = norm(o)
                        val padX = 14.dp.toPx() / w
                        val padY = 14.dp.toPx() / h
                        return state.markup.items.lastOrNull { item ->
                            val b = MarkupGeometry.bounds(item, aspect, measure)
                            p.x in (b.left - padX)..(b.right + padX) && p.y in (b.top - padY)..(b.bottom + padY)
                        }
                    }
                    when (state.tool) {
                        MarkupTool.PEN, MarkupTool.HIGHLIGHTER -> awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            stroke.clear(); stroke.add(down.position)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                if ((change.position - stroke.last()).getDistance() > 1.5f) stroke.add(change.position)
                                change.consume()
                            }
                            val highlighter = state.tool == MarkupTool.HIGHLIGHTER
                            val points = stroke.flatMap { val n = norm(it); listOf(n.x, n.y) }
                            stroke.clear()
                            onStroke()
                            onEvent(
                                MarkupEvent.Add(
                                    MarkupItem.Ink(
                                        newId(), points,
                                        if (highlighter) state.highlightColor else state.penColor,
                                        if (highlighter) HIGHLIGHTER_WIDTH else PEN_WIDTH,
                                        highlighter,
                                    ),
                                ),
                            )
                        }
                        MarkupTool.REDACT -> awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            rect = down.position to down.position
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                rect = down.position to change.position
                                change.consume()
                            }
                            val (a, b) = rect ?: return@awaitEachGesture
                            rect = null
                            val na = norm(a); val nb = norm(b)
                            if (abs(na.x - nb.x) > 0.01f && abs(na.y - nb.y) > 0.005f) {
                                onStroke()
                                onEvent(MarkupEvent.Add(MarkupItem.Redaction(newId(), na.x, na.y, nb.x, nb.y)))
                            }
                        }
                        MarkupTool.TEXT -> awaitEachGesture {
                            val down = awaitFirstDown()
                            val up = waitForUp(down.id) ?: return@awaitEachGesture
                            if ((up - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
                            val existing = hit(down.position) as? MarkupItem.Text
                            if (existing != null) {
                                onText(TextRequest(existing.x, existing.y, existing.id, existing.text))
                            } else {
                                val p = norm(down.position)
                                // The tap marks where the first line sits, so put the text's top half a line above it.
                                onText(TextRequest(p.x, (p.y - TEXT_SIZE / aspect * 0.6f).coerceAtLeast(0f)))
                            }
                        }
                        MarkupTool.SELECT -> awaitEachGesture {
                            val down = awaitFirstDown()
                            val target = hit(down.position)
                            onEvent(MarkupEvent.Select(target?.id))
                            if (target == null) return@awaitEachGesture
                            down.consume()
                            var pan = Offset.Zero
                            var zoom = 1f
                            var rotation = 0f
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.none { it.pressed }) break
                                pan += event.calculatePan()
                                zoom *= event.calculateZoom()
                                rotation += event.calculateRotation()
                                live = Live(target.id, pan, zoom, rotation)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                            live = null
                            onEvent(MarkupEvent.Transform(target.id, pan.x / w, pan.y / h, zoom, rotation))
                        }
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val moving = live?.let { l ->
                state.markup.items.firstOrNull { it.id == l.id }?.let { item ->
                    MarkupGeometry.transform(MarkupGeometry.translate(item, l.pan.x / w, l.pan.y / h), l.zoom, l.rotation)
                }
            }
            drawIntoCanvas { canvas ->
                painter.draw(canvas.nativeCanvas, state.markup, w, h, skip = live?.id)
                moving?.let { painter.drawItem(canvas.nativeCanvas, it, w, h) }
            }
            // The stroke being drawn.
            if (stroke.size > 1) {
                val highlighter = state.tool == MarkupTool.HIGHLIGHTER
                val path = Path().apply {
                    moveTo(stroke[0].x, stroke[0].y)
                    for (i in 1 until stroke.size) lineTo(stroke[i].x, stroke[i].y)
                }
                drawPath(
                    path,
                    Color(if (highlighter) state.highlightColor else state.penColor),
                    style = Stroke(
                        width = (if (highlighter) HIGHLIGHTER_WIDTH else PEN_WIDTH) * w,
                        cap = if (highlighter) StrokeCap.Square else StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
            rect?.let { (a, b) ->
                drawRect(Color.Black.copy(alpha = 0.85f), Offset(minOf(a.x, b.x), minOf(a.y, b.y)), Size(abs(a.x - b.x), abs(a.y - b.y)))
            }
            // Selection outline.
            val selected = moving ?: state.selected
            if (selected != null && state.tool == MarkupTool.SELECT) {
                val r: RectN = MarkupGeometry.bounds(selected, aspect, measure)
                val pad = 6.dp.toPx()
                drawRect(
                    selectedColor,
                    Offset(r.left * w - pad, r.top * h - pad),
                    Size(r.width * w + 2 * pad, r.height * h + 2 * pad),
                    style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                )
            }
        }
    }
}

/** Waits for [id] to lift; its last position, or null when the gesture was taken over by another. */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.waitForUp(id: androidx.compose.ui.input.pointer.PointerId): Offset? {
    var last: Offset? = null
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == id } ?: return null
        if (change.isConsumed) return null
        last = change.position
        if (!change.pressed) return last
    }
}

@Composable
private fun Toolbars(
    state: MarkupUiState,
    onEvent: (MarkupEvent) -> Unit,
    onSign: () -> Unit,
    onEditText: (MarkupItem.Text) -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(CameraColors.Scrim).navigationBarsPadding().padding(Spacing.s)) {
        val selected = state.selected
        if (selected != null && state.tool == MarkupTool.SELECT) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                if (selected is MarkupItem.Text) TextButton(onClick = { onEditText(selected) }) {
                    Icon(DeckleIcons.Edit, null, tint = Color.White)
                    Spacer(Modifier.width(Spacing.xs))
                    Text(stringResource(R.string.markup_edit_text), color = Color.White)
                }
                TextButton(onClick = { onEvent(MarkupEvent.DeleteSelected) }) {
                    Icon(DeckleIcons.Delete, null, tint = Color.White)
                    Spacer(Modifier.width(Spacing.xs))
                    Text(stringResource(R.string.action_delete), color = Color.White)
                }
            }
        } else {
            val colours = when (state.tool) {
                MarkupTool.PEN, MarkupTool.TEXT -> PEN_COLORS
                MarkupTool.HIGHLIGHTER -> HIGHLIGHT_COLORS
                else -> emptyList()
            }
            val current = if (state.tool == MarkupTool.HIGHLIGHTER) state.highlightColor else state.penColor
            if (colours.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.m, Alignment.CenterHorizontally)) {
                    colours.forEachIndexed { i, c ->
                        val label = stringResource(R.string.markup_colour, i + 1)
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(c or 0xFF000000))
                                .border(if (c == current) 3.dp else 1.dp, if (c == current) Color.White else Color.White.copy(alpha = 0.4f), CircleShape)
                                .semantics { contentDescription = label; this.selected = c == current }
                                .clickable { onEvent(MarkupEvent.SetColor(c)) },
                        )
                    }
                }
            }
            Text(
                stringResource(state.tool.hint()),
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Tool(DeckleIcons.Pen, R.string.markup_pen, state.tool == MarkupTool.PEN) { onEvent(MarkupEvent.SetTool(MarkupTool.PEN)) }
            Tool(DeckleIcons.Highlighter, R.string.markup_highlighter, state.tool == MarkupTool.HIGHLIGHTER) { onEvent(MarkupEvent.SetTool(MarkupTool.HIGHLIGHTER)) }
            Tool(DeckleIcons.TextTool, R.string.markup_text, state.tool == MarkupTool.TEXT) { onEvent(MarkupEvent.SetTool(MarkupTool.TEXT)) }
            Tool(DeckleIcons.Signature, R.string.markup_sign, false, onSign)
            Tool(DeckleIcons.Redact, R.string.markup_redact, state.tool == MarkupTool.REDACT) { onEvent(MarkupEvent.SetTool(MarkupTool.REDACT)) }
            Tool(DeckleIcons.Select, R.string.markup_select, state.tool == MarkupTool.SELECT) { onEvent(MarkupEvent.SetTool(MarkupTool.SELECT)) }
        }
    }
}

private fun MarkupTool.hint(): Int = when (this) {
    MarkupTool.PEN -> R.string.markup_hint_pen
    MarkupTool.HIGHLIGHTER -> R.string.markup_hint_highlighter
    MarkupTool.TEXT -> R.string.markup_hint_text
    MarkupTool.REDACT -> R.string.markup_hint_redact
    MarkupTool.SELECT -> R.string.markup_hint_select
}

@Composable
private fun RowScope.Tool(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, active: Boolean, onClick: () -> Unit) {
    val tint = if (active) CameraColors.Ring else Color.White
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = Spacing.xs),
        modifier = Modifier.weight(1f).semantics { selected = active },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = tint)
            Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TextDialog(initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial.isEmpty()) R.string.markup_text_add else R.string.markup_edit_text)) },
        text = {
            OutlinedTextField(text, { text = it.take(MAX_TEXT) }, placeholder = { Text(stringResource(R.string.markup_text_hint)) }, minLines = 1, maxLines = 4, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text(stringResource(R.string.action_done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val MAX_TEXT = 300

/** NV-40: saved signatures (encrypted on this phone) plus "draw a new one". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignatureSheet(
    signatures: List<SavedSignature>,
    onPick: (SavedSignature) -> Unit,
    onDelete: (SavedSignature) -> Unit,
    onDrawNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    val painter = remember { MarkupPainter() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.l).padding(bottom = Spacing.l)) {
            Text(stringResource(R.string.signature_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.signature_private),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Spacing.xs),
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.padding(vertical = Spacing.s)) {
                items(signatures, key = { it.id }) { sig ->
                    val label = stringResource(R.string.signature_use)
                    Surface(
                        onClick = { onPick(sig) },
                        shape = MaterialTheme.shapes.medium,
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Canvas(Modifier.weight(1f).height(72.dp).padding(Spacing.m)) {
                                val scale = minOf(size.width, size.height / sig.ink.aspect)
                                drawIntoCanvas { c ->
                                    c.nativeCanvas.save()
                                    c.nativeCanvas.translate((size.width - scale) / 2f, (size.height - scale * sig.ink.aspect) / 2f)
                                    painter.drawInk(c.nativeCanvas, sig.ink, scale, MarkupItem.INK_BLUE, centred = false)
                                    c.nativeCanvas.restore()
                                }
                            }
                            IconButton(onClick = { onDelete(sig) }) { Icon(DeckleIcons.Delete, stringResource(R.string.signature_delete), tint = Color.Black.copy(alpha = 0.6f)) }
                        }
                    }
                }
            }
            Button(onClick = onDrawNew, modifier = Modifier.fillMaxWidth()) {
                Icon(DeckleIcons.Signature, null)
                Spacer(Modifier.width(Spacing.s))
                Text(stringResource(R.string.signature_new))
            }
        }
    }
}

/** Draw a signature with a finger. Strokes are handed back in pad pixels and normalised by the domain. */
@Composable
private fun SignaturePad(onSave: (List<List<Float>>) -> Unit, onDismiss: () -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val current = remember { mutableStateListOf<Offset>() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().padding(Spacing.l)) {
            Column(Modifier.padding(Spacing.l)) {
                Text(stringResource(R.string.signature_draw), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(Spacing.m))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2.2f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium),
                ) {
                    Text(
                        stringResource(R.string.signature_sign_here),
                        color = Color.Black.copy(alpha = 0.25f),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.BottomStart).padding(Spacing.m),
                    )
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                current.clear(); current.add(down.position)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    current.add(change.position)
                                    change.consume()
                                }
                                if (current.size > 1) strokes.add(current.toList())
                                current.clear()
                            }
                        },
                    ) {
                        val ink = Color(MarkupItem.INK_BLUE)
                        (strokes + listOf(current.toList())).forEach { s ->
                            if (s.size < 2) return@forEach
                            val path = Path().apply { moveTo(s[0].x, s[0].y); s.drop(1).forEach { lineTo(it.x, it.y) } }
                            drawPath(path, ink, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = Spacing.m), horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.End)) {
                    TextButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) { Text(stringResource(R.string.signature_clear)) }
                    OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    Button(onClick = { onSave(strokes.map { s -> s.flatMap { listOf(it.x, it.y) } }) }, enabled = strokes.isNotEmpty()) {
                        Text(stringResource(R.string.signature_save))
                    }
                }
            }
        }
    }
}
