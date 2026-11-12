package com.avyaan.deckle.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import com.avyaan.deckle.designsystem.theme.LocalReducedMotion

/**
 * Shared-element plumbing (spec 01 §6): the NavHost provides both scopes once; leaf composables opt in with
 * [sharedElement] without threading scopes through every signature.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Keys are namespaced strings so a document cover and its first page tile can morph into each other. */
object SharedKeys {
    fun cover(documentId: String) = "cover-$documentId"
    fun title(documentId: String) = "title-$documentId"
    fun page(pageId: String) = "page-$pageId"
    const val SCAN_FAB = "scan-fab"
}

/** No-op when there is no transition in progress, or when the user has reduced motion. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedElement(key: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalNavAnimatedScope.current ?: return this
    if (LocalReducedMotion.current) return this
    return with(shared) {
        this@sharedElement.sharedElement(rememberSharedContentState(key), animatedVisibilityScope = animated)
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedBounds(key: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalNavAnimatedScope.current ?: return this
    if (LocalReducedMotion.current) return this
    return with(shared) {
        this@sharedBounds.sharedBounds(rememberSharedContentState(key), animatedVisibilityScope = animated)
    }
}
