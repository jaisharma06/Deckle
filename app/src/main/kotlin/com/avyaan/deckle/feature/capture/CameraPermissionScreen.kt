package com.avyaan.deckle.feature.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.avyaan.deckle.R
import com.avyaan.deckle.designsystem.icons.DeckleIcons
import com.avyaan.deckle.designsystem.theme.Spacing

/**
 * FR-17: shown instead of a blank camera. When the permission is permanently denied the button opens the
 * app's system settings; the route re-checks on resume and fades to the camera without a restart.
 */
@Composable
fun CameraPermissionScreen(permanentlyDenied: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        IconButton(onClick = onClose, modifier = Modifier.statusBarsPadding().padding(Spacing.s)) {
            Icon(DeckleIcons.Close, stringResource(R.string.capture_close))
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = Spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(DeckleIcons.Camera, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(Spacing.xl))
            Text(stringResource(R.string.permission_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Spacing.m))
            Text(
                stringResource(if (permanentlyDenied) R.string.permission_denied_body else R.string.permission_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.xl))
            if (permanentlyDenied) {
                Button(onClick = onOpenSettings) { Text(stringResource(R.string.action_open_settings)) }
            } else {
                Button(onClick = onRequest) { Text(stringResource(R.string.permission_allow)) }
            }
        }
    }
}
