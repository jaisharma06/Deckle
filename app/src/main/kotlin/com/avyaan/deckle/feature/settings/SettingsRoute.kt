package com.avyaan.deckle.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avyaan.deckle.R
import com.avyaan.deckle.designsystem.components.ConfirmDialog
import com.avyaan.deckle.designsystem.components.DeckleTopBar
import com.avyaan.deckle.designsystem.icons.DeckleIcons
import com.avyaan.deckle.designsystem.theme.DeckleMotion
import com.avyaan.deckle.designsystem.theme.Spacing
import com.avyaan.deckle.designsystem.theme.deckleTween
import com.avyaan.deckle.designsystem.theme.rememberHaptics
import com.avyaan.deckle.domain.export.PageSizePolicy
import com.avyaan.deckle.domain.export.QualityPreset
import com.avyaan.deckle.domain.imaging.EnhancementMode
import com.avyaan.deckle.domain.settings.FlashMode
import com.avyaan.deckle.ui.ObserveEffects
import com.avyaan.deckle.ui.formatBytes
import com.avyaan.deckle.ui.labels.label

@Composable
fun SettingsRoute(onBack: () -> Unit, onAbout: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val haptics = rememberHaptics()
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val s = state.settings
    val update: ((com.avyaan.deckle.domain.settings.AppSettings) -> com.avyaan.deckle.domain.settings.AppSettings) -> Unit = {
        haptics.tick()
        vm.onEvent(SettingsEvent.Update(it))
    }

    ObserveEffects(vm.effects) { effect ->
        when (effect) {
            SettingsEffect.CacheCleared -> snackbar.showSnackbar(resources.getString(R.string.settings_cache_cleared))
        }
    }

    Scaffold(
        topBar = { DeckleTopBar(stringResource(R.string.settings_title), onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = Spacing.xxl)) {
            Group(R.string.settings_group_scanning)
            ChoiceRow(R.string.settings_default_filter, s.defaultMode, EnhancementMode.entries, { it.label() }) { v -> update { it.copy(defaultMode = v) } }
            SwitchRow(R.string.settings_auto_capture, R.string.settings_auto_capture_desc, s.autoCapture) { v -> update { it.copy(autoCapture = v) } }
            ChoiceRow(R.string.settings_flash, s.flash, FlashMode.entries, { it.label() }) { v -> update { it.copy(flash = v) } }
            SwitchRow(R.string.settings_save_originals, R.string.settings_save_originals_desc, s.saveOriginals) { v -> update { it.copy(saveOriginals = v) } }

            Group(R.string.settings_group_text)
            ListItem(headlineContent = { Text(stringResource(R.string.settings_ocr_language)) }, leadingContent = { Icon(DeckleIcons.Text, null) })

            Group(R.string.settings_group_export)
            ChoiceRow(R.string.settings_default_quality, s.defaultQuality, QualityPreset.entries, { it.label() }) { v -> update { it.copy(defaultQuality = v) } }
            ChoiceRow(R.string.settings_default_page_size, s.defaultPageSize, PageSizePolicy.entries, { it.label() }) { v -> update { it.copy(defaultPageSize = v) } }

            Group(R.string.settings_group_privacy)
            SwitchRow(R.string.settings_secure, null, s.secureScreens) { v -> update { it.copy(secureScreens = v) } }

            Group(R.string.settings_group_storage)
            StorageRow(state)
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_clear_cache)) },
                supportingContent = { Text(stringResource(R.string.settings_clear_cache_desc)) },
                modifier = Modifier.clickable { confirmClear = true },
            )

            HorizontalDivider(Modifier.padding(top = Spacing.xl))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_about)) },
                leadingContent = { Icon(DeckleIcons.Shield, null) },
                trailingContent = { Icon(DeckleIcons.Forward, null) },
                modifier = Modifier.clickable(onClick = onAbout),
            )
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.settings_clear_cache),
            body = stringResource(R.string.settings_clear_cache_desc),
            confirmLabel = stringResource(R.string.settings_clear_cache),
            onConfirm = { confirmClear = false; vm.onEvent(SettingsEvent.ClearCache) },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun Group(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.xl, bottom = Spacing.xs),
    )
}

@Composable
private fun SwitchRow(title: Int, subtitle: Int?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = subtitle?.let { { Text(stringResource(it)) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.clickable(role = Role.Switch) { onChange(!checked) },
    )
}

@Composable
private fun <T> ChoiceRow(title: Int, value: T, options: List<T>, label: (T) -> Int, onChoose: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(stringResource(title)) },
            supportingContent = { Text(stringResource(label(value)), color = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.clickable(role = Role.DropdownList) { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, offset = androidx.compose.ui.unit.DpOffset(Spacing.l, 0.dp)) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(label(option))) },
                    trailingIcon = { if (option == value) Icon(DeckleIcons.Check, null) },
                    onClick = { open = false; onChoose(option) },
                )
            }
        }
    }
}

/** FR-73: two figures and a stacked bar; the cache segment animates down to zero after clearing. */
@Composable
private fun StorageRow(state: SettingsUiState) {
    val context = LocalContext.current
    val usage = state.storage
    val docs = usage?.documentsBytes ?: 0L
    val cache = usage?.cacheBytes ?: 0L
    val total = (docs + cache).coerceAtLeast(1L)
    val docsFraction by animateFloatAsState(docs.toFloat() / total, deckleTween(DeckleMotion.LONG), label = "docs")
    val cacheFraction by animateFloatAsState(cache.toFloat() / total, deckleTween(DeckleMotion.LONG), label = "cache")

    Column(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m)) {
        AnimatedVisibility(usage != null, enter = fadeIn()) {
            Text(
                stringResource(R.string.settings_storage_usage, context.formatBytes(docs), context.formatBytes(cache)),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        val track = MaterialTheme.colorScheme.surfaceContainerHighest
        val docsColor = MaterialTheme.colorScheme.primary
        val cacheColor = MaterialTheme.colorScheme.tertiary
        Canvas(Modifier.padding(top = Spacing.s).fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
            drawRect(track)
            val docsW = size.width * docsFraction
            drawRect(docsColor, size = Size(docsW, size.height))
            drawRect(cacheColor, topLeft = Offset(docsW, 0f), size = Size(size.width * cacheFraction, size.height))
        }
    }
}
