package com.avyaan.deckle.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.TransformOrigin
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.toRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.avyaan.deckle.designsystem.theme.DeckleMotion
import com.avyaan.deckle.designsystem.theme.LocalReducedMotion
import com.avyaan.deckle.feature.about.AboutRoute
import com.avyaan.deckle.feature.capture.CaptureRoute
import com.avyaan.deckle.feature.crop.CropRoute
import com.avyaan.deckle.feature.document.DocumentRoute
import com.avyaan.deckle.feature.history.HistoryRoute
import com.avyaan.deckle.feature.library.LibraryRoute
import com.avyaan.deckle.feature.review.ReviewRoute
import com.avyaan.deckle.feature.settings.SettingsRoute
import com.avyaan.deckle.feature.trash.TrashRoute
import com.avyaan.deckle.feature.viewer.ViewerRoute
import com.avyaan.deckle.feature.markup.MarkupRoute
import androidx.compose.runtime.LaunchedEffect
import com.avyaan.deckle.ui.LocalNavAnimatedScope
import com.avyaan.deckle.ui.LocalSharedTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope

/**
 * App navigation. The whole host sits in a SharedTransitionLayout so any screen can opt into shared
 * elements via `Modifier.sharedElement(key)` (spec 05 §3.3).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun DeckleNavHost(
    nav: NavHostController = rememberNavController(),
    /** NV-32: a reminder was tapped; open that document once. */
    openDocumentId: String? = null,
    onDocumentOpened: () -> Unit = {},
) {
    val reduced = LocalReducedMotion.current
    LaunchedEffect(openDocumentId) {
        val id = openDocumentId ?: return@LaunchedEffect
        nav.popBackStack<LibraryDest>(inclusive = false)
        nav.navigate(DocumentDest(id))
        onDocumentOpened()
    }
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            NavHost(
                navController = nav,
                startDestination = LibraryDest,
                enterTransition = { sharedAxisEnter(reduced) },
                exitTransition = { sharedAxisExit(reduced) },
                popEnterTransition = { sharedAxisPopEnter(reduced) },
                popExitTransition = { sharedAxisPopExit(reduced) },
            ) {
                composable<LibraryDest> { Scoped { Library(nav) } }
                composable<FolderDest> { Scoped { Library(nav) } }
                composable<CaptureDest>(
                    // Camera grows out of the Scan button (container-transform feel), and sinks back on exit.
                    enterTransition = { if (reduced) fadeIn(tween(120)) else scaleIn(tween(DeckleMotion.LONG, easing = DeckleMotion.Standard), 0.6f, TransformOrigin(0.5f, 0.92f)) + fadeIn(tween(DeckleMotion.MEDIUM)) },
                    popExitTransition = { if (reduced) fadeOut(tween(120)) else scaleOut(tween(DeckleMotion.MEDIUM, easing = DeckleMotion.Accelerate), 0.7f, TransformOrigin(0.5f, 0.92f)) + fadeOut(tween(DeckleMotion.MEDIUM)) },
                    exitTransition = { fadeOut(tween(DeckleMotion.SHORT)) },
                    popEnterTransition = { fadeIn(tween(DeckleMotion.MEDIUM)) },
                ) {
                    Scoped {
                        CaptureRoute(
                            onClose = { nav.popBackStack() },
                            onOpenCrop = { s, c, imported -> nav.navigate(CropDest(s, c, imported = imported)) },
                            onOpenReview = { s -> nav.navigate(ReviewDest(s)) { popUpTo<CaptureDest> { inclusive = true } } },
                        )
                    }
                }
                composable<CropDest>(enterTransition = { fadeIn(tween(DeckleMotion.MEDIUM)) }, popExitTransition = { fadeOut(tween(DeckleMotion.SHORT)) }) {
                    Scoped {
                        CropRoute(
                            onRetake = { nav.popBackStack() },
                            onDone = { session, capture, fromReview ->
                                when {
                                    fromReview -> nav.popBackStack()
                                    // From the camera: Capture leaves the stack. From a Library gallery import there
                                    // is no Capture, so only the crop editor does.
                                    runCatching { nav.getBackStackEntry<CaptureDest>() }.isSuccess ->
                                        nav.navigate(ReviewDest(session, capture)) { popUpTo<CaptureDest> { inclusive = true } }
                                    else -> nav.navigate(ReviewDest(session, capture)) { popUpTo<CropDest> { inclusive = true } }
                                }
                            },
                        )
                    }
                }
                composable<RecropDest>(enterTransition = { fadeIn(tween(DeckleMotion.MEDIUM)) }, popExitTransition = { fadeOut(tween(DeckleMotion.SHORT)) }) {
                    Scoped { CropRoute(onRetake = { nav.popBackStack() }, onDone = { _, _, _ -> nav.popBackStack() }) }
                }
                composable<ReviewDest> {
                    Scoped {
                        ReviewRoute(
                            onBack = { nav.popBackStack() },
                            // Review leaves the stack; Capture returns to a fresh Review when the user is done.
                            onAddPage = { sessionId -> nav.navigate(CaptureDest(sessionId = sessionId)) { popUpTo<ReviewDest> { inclusive = true } } },
                            onCrop = { s, c -> nav.navigate(CropDest(s, c, fromReview = true)) },
                            onFinished = { documentId ->
                                // Back from the new document returns to where the scan started (the library or
                                // the folder it was started in), which is the entry right below Review.
                                val below = nav.previousBackStackEntry
                                below?.savedStateHandle?.set(NavResults.HIGHLIGHT_DOCUMENT, documentId)
                                if (below?.destination?.hasRoute<DocumentDest>() == true && below.toRoute<DocumentDest>().documentId == documentId) {
                                    nav.popBackStack()
                                } else {
                                    nav.navigate(DocumentDest(documentId)) { popUpTo<ReviewDest> { inclusive = true } }
                                }
                            },
                            onDiscarded = { nav.popBackStack<LibraryDest>(inclusive = false) },
                        )
                    }
                }
                composable<DocumentDest> {
                    Scoped {
                        DocumentRoute(
                            onBack = { nav.popBackStack() },
                            onOpenPage = { doc, page -> nav.navigate(ViewerDest(doc, page)) },
                            onAddPages = { doc, after -> nav.navigate(CaptureDest(targetDocumentId = doc, insertAfterPageId = after)) },
                            onRecrop = { pageId -> nav.navigate(RecropDest(pageId)) },
                            onMarkup = { pageId -> nav.navigate(MarkupDest(pageId)) },
                        )
                    }
                }
                composable<ViewerDest> {
                    Scoped {
                        ViewerRoute(
                            onBack = { nav.popBackStack() },
                            onRecrop = { pageId -> nav.navigate(RecropDest(pageId)) },
                            onInsertAfter = { doc, page -> nav.navigate(CaptureDest(targetDocumentId = doc, insertAfterPageId = page)) },
                            onMarkup = { pageId -> nav.navigate(MarkupDest(pageId)) },
                        )
                    }
                }
                composable<MarkupDest>(enterTransition = { fadeIn(tween(DeckleMotion.MEDIUM)) }, popExitTransition = { fadeOut(tween(DeckleMotion.SHORT)) }) {
                    Scoped { MarkupRoute(onClose = { nav.popBackStack() }) }
                }
                composable<TrashDest> { Scoped { TrashRoute(onBack = { nav.popBackStack() }) } }
                composable<HistoryDest> {
                    Scoped {
                        HistoryRoute(
                            onBack = { nav.popBackStack() },
                            onExportAgain = { id -> nav.navigate(DocumentDest(id, openExport = true)) },
                        )
                    }
                }
                composable<SettingsDest> { Scoped { SettingsRoute(onBack = { nav.popBackStack() }, onAbout = { nav.navigate(AboutDest) }) } }
                composable<AboutDest> { Scoped { AboutRoute(onBack = { nav.popBackStack() }) } }
            }
        }
    }
}

/** The library root and every folder share one screen; folders stack so Back walks up the tree. */
@Composable
private fun Library(nav: NavHostController) {
    LibraryRoute(
        // Single-top: a quick double tap must not stack two camera screens.
        onScan = { folderId -> nav.navigate(CaptureDest(folderId = folderId)) { launchSingleTop = true } },
        onResume = { sessionId -> nav.navigate(ReviewDest(sessionId)) },
        onOpenDocument = { id, export -> nav.navigate(DocumentDest(id, openExport = export)) },
        onOpenTrash = { nav.navigate(TrashDest) },
        onOpenSettings = { nav.navigate(SettingsDest) },
        onOpenHistory = { nav.navigate(HistoryDest) },
        onOpenCrop = { s, c -> nav.navigate(CropDest(s, c, imported = true, discardEmptySession = true)) },
        onOpenFolder = { id -> nav.navigate(FolderDest(id)) },
        onJumpToFolder = { path ->
            // Breadcrumbs and search hits: rebuild the stack as Library › … › target so Back walks up.
            nav.popBackStack<LibraryDest>(inclusive = false)
            path.forEach { nav.navigate(FolderDest(it)) }
        },
        onBack = { nav.popBackStack() },
    )
}

/** Provides this destination's AnimatedVisibilityScope to shared-element modifiers below it. */
@Composable
private fun AnimatedVisibilityScope.Scoped(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalNavAnimatedScope provides this, content = content)
}

// Material shared-axis X: 8 % slide + fade (spec 05 §3.3).
private const val AXIS_FRACTION = 0.08f

private fun AnimatedContentTransitionScope<NavBackStackEntry>.sharedAxisEnter(reduced: Boolean): EnterTransition =
    if (reduced) fadeIn(tween(120))
    else slideInHorizontally(tween(DeckleMotion.LONG, easing = DeckleMotion.Standard)) { (it * AXIS_FRACTION).toInt() } +
        fadeIn(tween(DeckleMotion.MEDIUM, delayMillis = 60))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.sharedAxisExit(reduced: Boolean): ExitTransition =
    if (reduced) fadeOut(tween(120))
    else slideOutHorizontally(tween(DeckleMotion.LONG, easing = DeckleMotion.Standard)) { -(it * AXIS_FRACTION).toInt() } +
        fadeOut(tween(DeckleMotion.SHORT))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.sharedAxisPopEnter(reduced: Boolean): EnterTransition =
    if (reduced) fadeIn(tween(120))
    else slideInHorizontally(tween(DeckleMotion.LONG, easing = DeckleMotion.Standard)) { -(it * AXIS_FRACTION).toInt() } +
        fadeIn(tween(DeckleMotion.MEDIUM, delayMillis = 60))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.sharedAxisPopExit(reduced: Boolean): ExitTransition =
    if (reduced) fadeOut(tween(120))
    else slideOutHorizontally(tween(DeckleMotion.LONG, easing = DeckleMotion.Standard)) { (it * AXIS_FRACTION).toInt() } +
        fadeOut(tween(DeckleMotion.SHORT))
