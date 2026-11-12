package com.avyaan.deckle.designsystem.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.avyaan.deckle.R
import com.avyaan.deckle.designsystem.icons.DeckleIcons

/** The standard secondary-screen bar: back arrow, one-line title, optional actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeckleTopBar(
    title: @Composable () -> Unit,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
) {
    TopAppBar(
        title = title,
        navigationIcon = { IconButton(onClick = onBack) { Icon(DeckleIcons.Back, stringResource(R.string.action_back)) } },
        actions = actions,
        scrollBehavior = scrollBehavior,
        colors = colors,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeckleTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) =
    DeckleTopBar(title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onBack = onBack, actions = actions)
