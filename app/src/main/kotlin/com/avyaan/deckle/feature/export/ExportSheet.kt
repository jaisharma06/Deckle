package com.avyaan.deckle.feature.export

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.pluralStringResource
import com.avyaan.deckle.domain.export.ExportArchive
import com.avyaan.deckle.ui.persistAccess
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avyaan.deckle.R
import com.avyaan.deckle.designsystem.components.shimmer
import com.avyaan.deckle.designsystem.icons.DeckleIcons
import com.avyaan.deckle.designsystem.theme.DeckleMotion
import com.avyaan.deckle.designsystem.theme.LocalReducedMotion
import com.avyaan.deckle.designsystem.theme.Spacing
import com.avyaan.deckle.designsystem.theme.rememberHaptics
import com.avyaan.deckle.designsystem.theme.tabular
import com.avyaan.deckle.domain.export.ExportFormat
import com.avyaan.deckle.domain.export.ExportTarget
import com.avyaan.deckle.domain.export.PageSizePolicy
import com.avyaan.deckle.domain.export.QualityPreset
import com.avyaan.deckle.ui.ObserveEffects
import com.avyaan.deckle.ui.formatBytes
import com.avyaan.deckle.ui.labels.label
import com.avyaan.deckle.ui.shareFiles
import kotlinx.coroutines.delay

/**
 * FR-50…56 export sheet. Rows that don't apply to the chosen format collapse away; during export the
 * buttons give way to an inline progress bar, and success draws a check before the share sheet opens.
 * The sheet can be dismissed at any time — the export carries on with its notification.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    documentIds: List<String>,
    selectedPageIds: List<String>,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    archive: ExportArchive? = null,
    vm: ExportViewModel = hiltViewModel(key = "export"),
) {
    LaunchedEffect(documentIds, selectedPageIds, archive) { vm.start(documentIds, selectedPageIds, archive) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val haptics = rememberHaptics()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Denial never blocks export (FR-56); go ahead either way.
        pendingAction?.invoke()
        pendingAction = null
    }
    fun withNotificationPermission(action: () -> Unit) {
        val needs = Build.VERSION.SDK_INT >= 33 && !askedNotifications &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needs) {
            askedNotifications = true
            pendingAction = action
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let { context.persistAccess(it); vm.onEvent(ExportEvent.SaveTo(ExportTarget.File(it.toString()))) }
    }
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportArchive.MIME)) { uri ->
        uri?.let { context.persistAccess(it); vm.onEvent(ExportEvent.SaveTo(ExportTarget.File(it.toString()))) }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { context.persistAccess(it); vm.onEvent(ExportEvent.SaveTo(ExportTarget.Folder(it.toString()))) }
    }

    ObserveEffects(vm.effects) { effect ->
        when (effect) {
            is ExportEffect.PickFile -> if (effect.zip) pickZip.launch(effect.suggestedName) else pickFile.launch(effect.suggestedName)
            ExportEffect.PickFolder -> pickFolder.launch(null)
            is ExportEffect.ShareFiles -> { haptics.confirm(); context.shareFiles(effect.paths, effect.mimeType) }
            is ExportEffect.Message -> onMessage(effect.text.resolve(context))
            ExportEffect.Close -> { delay(DeckleMotion.DELIBERATE.toLong()); onDismiss() }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl)
                .navigationBarsPadding()
                .padding(bottom = Spacing.l),
        ) {
            val zip = state.archive
            if (zip == null) {
                Text(stringResource(R.string.export_title), style = MaterialTheme.typography.titleLarge)
            } else {
                Text(stringResource(R.string.export_folder_title, zip.name), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    pluralStringResource(R.plurals.export_folder_body, state.documentCount, state.documentCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(Spacing.l))

            if (zip == null) {
                OptionRow(label = R.string.export_format) {
                    Segmented(listOf(ExportFormat.PDF, ExportFormat.JPEG, ExportFormat.PNG), state.format, { it.name.replace("JPEG", "JPG") }) {
                        vm.onEvent(ExportEvent.SetFormat(it))
                    }
                }
            }
            if (state.selectedPages > 0) {
                OptionRow(label = R.string.export_pages) {
                    Segmented(
                        listOf(PageScope.ALL, PageScope.SELECTED),
                        state.scope,
                        {
                            if (it == PageScope.ALL) resources.getString(R.string.export_pages_all, state.totalPages)
                            else resources.getString(R.string.export_pages_selected, state.selectedPages)
                        },
                    ) { vm.onEvent(ExportEvent.SetScope(it)) }
                }
            }
            AnimatedVisibility(state.isPdf, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                OptionRow(label = R.string.export_page_size) {
                    Segmented(PageSizePolicy.entries, state.pageSize, { resources.getString(it.label()) }) { vm.onEvent(ExportEvent.SetPageSize(it)) }
                }
            }
            AnimatedVisibility(state.format != ExportFormat.PNG, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                OptionRow(label = R.string.export_quality) {
                    Segmented(QualityPreset.entries, state.quality, { resources.getString(it.label()) }) { vm.onEvent(ExportEvent.SetQuality(it)) }
                }
            }
            // FR-52 estimate, with rolling digits.
            Box(Modifier.padding(vertical = Spacing.xs).height(20.dp)) {
                AnimatedContent(state.estimateBytes, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "est") { bytes ->
                    if (bytes == null) Box(Modifier.width(72.dp).height(16.dp).shimmer())
                    else Text(stringResource(R.string.export_estimate, context.formatBytes(bytes)), style = MaterialTheme.typography.bodyMedium.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            AnimatedVisibility(state.isPdf, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                PasswordSection(state, vm::onEvent)
            }
            Spacer(Modifier.height(Spacing.l))

            AnimatedContent(
                when {
                    state.done -> 2
                    state.busy -> 1
                    else -> 0
                },
                transitionSpec = { fadeIn(tween(DeckleMotion.MEDIUM)) togetherWith fadeOut(tween(DeckleMotion.SHORT)) },
                label = "actions",
            ) { phase ->
                when (phase) {
                    0 -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                        OutlinedButton(onClick = { withNotificationPermission { vm.onEvent(ExportEvent.Save) } }, enabled = state.canExport, modifier = Modifier.weight(1f)) {
                            Icon(DeckleIcons.Save, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(Spacing.s))
                            Text(stringResource(R.string.export_save))
                        }
                        Button(onClick = { withNotificationPermission { vm.onEvent(ExportEvent.Share) } }, enabled = state.canExport, modifier = Modifier.weight(1f)) {
                            Icon(DeckleIcons.Share, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(Spacing.s))
                            Text(stringResource(R.string.export_share))
                        }
                    }
                    1 -> Column(Modifier.fillMaxWidth().height(56.dp), verticalArrangement = Arrangement.Center) {
                        val r = state.running
                        if (r != null) {
                            LinearProgressIndicator(progress = { r.done.toFloat() / r.total }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(Spacing.s))
                            Text(stringResource(R.string.export_progress, r.done, r.total), style = MaterialTheme.typography.labelLarge.tabular)
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    else -> Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) { DrawnCheck() }
                }
            }
            if (Build.VERSION.SDK_INT >= 33 && !askedNotifications && !state.busy &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                Text(
                    stringResource(R.string.export_notification_rationale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.s),
                )
            }
        }
    }
}

@Composable
private fun OptionRow(label: Int, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.s)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Spacing.xs))
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label(option), maxLines = 1) }
        }
    }
}

@Composable
private fun PasswordSection(state: ExportUiState, onEvent: (ExportEvent) -> Unit) {
    var visible by rememberSaveable { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            Icon(DeckleIcons.Lock, null)
            Spacer(Modifier.width(Spacing.m))
            Text(stringResource(R.string.export_password), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = state.passwordOn, onCheckedChange = { onEvent(ExportEvent.SetPasswordOn(it)) }, enabled = !state.busy)
        }
        AnimatedVisibility(state.passwordOn, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                val transformation = if (visible) VisualTransformation.None else PasswordVisualTransformation()
                val toggle: @Composable () -> Unit = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) DeckleIcons.EyeOff else DeckleIcons.Eye,
                            stringResource(if (visible) R.string.export_hide_password else R.string.export_show_password),
                        )
                    }
                }
                OutlinedTextField(
                    value = state.password,
                    onValueChange = { onEvent(ExportEvent.SetPassword(it)) },
                    label = { Text(stringResource(R.string.export_password_field)) },
                    singleLine = true,
                    visualTransformation = transformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = toggle,
                    isError = state.password.isNotEmpty() && state.passwordProblem == PasswordProblem.TOO_SHORT,
                    supportingText = if (state.password.isNotEmpty() && state.passwordProblem == PasswordProblem.TOO_SHORT) {
                        { Text(stringResource(R.string.export_password_short)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.confirm,
                    onValueChange = { onEvent(ExportEvent.SetConfirm(it)) },
                    label = { Text(stringResource(R.string.export_password_confirm)) },
                    singleLine = true,
                    visualTransformation = transformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = state.confirm.isNotEmpty() && state.passwordProblem == PasswordProblem.MISMATCH,
                    supportingText = if (state.confirm.isNotEmpty() && state.passwordProblem == PasswordProblem.MISMATCH) {
                        { Text(stringResource(R.string.export_password_mismatch)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Success: a check mark that draws its own stroke (spec 05 §4.7). */
@Composable
private fun DrawnCheck() {
    val reduced = LocalReducedMotion.current
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(DeckleMotion.DELIBERATE, easing = DeckleMotion.Decelerate)) }
    val bg = MaterialTheme.colorScheme.primary
    val fg = MaterialTheme.colorScheme.onPrimary
    val done = stringResource(R.string.export_done)
    Box(Modifier.size(56.dp).background(bg, CircleShape).semantics { contentDescription = done }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(28.dp)) {
            val a = Offset(size.width * 0.12f, size.height * 0.52f)
            val b = Offset(size.width * 0.4f, size.height * 0.78f)
            val c = Offset(size.width * 0.9f, size.height * 0.22f)
            val p = progress.value
            val first = (p / 0.4f).coerceAtMost(1f)
            val second = ((p - 0.4f) / 0.6f).coerceIn(0f, 1f)
            val path = Path().apply {
                moveTo(a.x, a.y)
                lineTo(a.x + (b.x - a.x) * first, a.y + (b.y - a.y) * first)
                if (second > 0f) lineTo(b.x + (c.x - b.x) * second, b.y + (c.y - b.y) * second)
            }
            drawPath(path, fg, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
