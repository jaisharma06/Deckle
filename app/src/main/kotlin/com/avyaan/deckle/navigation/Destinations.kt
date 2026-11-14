package com.avyaan.deckle.navigation

import kotlinx.serialization.Serializable

/** Type-safe routes (spec 01 §6). Routes carry IDs only, never objects. */
@Serializable data object LibraryDest

/** One library folder; the same screen as [LibraryDest]. The property name is LibraryViewModel.KEY_FOLDER. */
@Serializable data class FolderDest(val folderId: String)

@Serializable data class CaptureDest(
    val sessionId: String? = null,
    val targetDocumentId: String? = null,
    val insertAfterPageId: String? = null,
    /** A new document from this scan lands in this folder. */
    val folderId: String? = null,
)

@Serializable data class CropDest(
    val sessionId: String,
    val captureId: String,
    val fromReview: Boolean = false,
    /** A gallery image: "Retake" reads "Cancel". */
    val imported: Boolean = false,
    /** Started from the Library (no camera screen behind it): cancelling the last image discards the session. */
    val discardEmptySession: Boolean = false,
)

/** Re-crop of a committed page (FR-42). */
@Serializable data class RecropDest(val pageId: String)

/** NV-40…44: mark up, sign or redact one page. */
@Serializable data class MarkupDest(val pageId: String)

@Serializable data class ReviewDest(val sessionId: String, val captureId: String? = null)

@Serializable data class DocumentDest(val documentId: String, val openExport: Boolean = false)

@Serializable data class ViewerDest(val documentId: String, val pageId: String)

@Serializable data object HistoryDest

@Serializable data object TrashDest

@Serializable data object SettingsDest

@Serializable data object AboutDest

/** Navigation results passed back through the library's SavedStateHandle. */
object NavResults {
    const val HIGHLIGHT_DOCUMENT = "highlight_document"
}
