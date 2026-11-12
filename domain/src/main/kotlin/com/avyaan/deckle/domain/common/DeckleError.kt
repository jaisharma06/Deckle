package com.avyaan.deckle.domain.common

/** Expected failures. Each one has exactly one user-facing message in the app layer. */
public sealed interface DeckleError {
    public data object StorageFull : DeckleError
    public data class FileMissing(val path: String) : DeckleError
    public data object ImageDecodeFailed : DeckleError
    public data object ImagingUnavailable : DeckleError
    public data object NotFound : DeckleError
    public data object OriginalNotSaved : DeckleError
    public data class OcrFailed(val cause: String) : DeckleError
    public data class ExportFailed(val cause: String) : DeckleError
    public data object Cancelled : DeckleError
    public data class Unexpected(val cause: Throwable) : DeckleError
}
