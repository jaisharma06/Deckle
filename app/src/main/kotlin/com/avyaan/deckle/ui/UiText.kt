package com.avyaan.deckle.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import com.avyaan.deckle.R
import com.avyaan.deckle.domain.common.DeckleError

/**
 * User-facing text produced by ViewModels. Always a resource, never a raw string, so lint's HardcodedText
 * stays meaningful and locale changes just work (spec 01 §5).
 */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText

    fun resolve(context: Context): String = when (this) {
        is Res -> context.getString(id, *args.toTypedArray())
        is Plural -> context.resources.getQuantityString(id, count, *args.toTypedArray())
    }
}

@Composable
fun UiText.asString(): String = resolve(LocalContext.current)

/** The one place errors become words (spec 05 §9). */
fun DeckleError.toUiText(): UiText = UiText.Res(
    when (this) {
        DeckleError.StorageFull -> R.string.error_storage_full
        DeckleError.ImageDecodeFailed, is DeckleError.FileMissing -> R.string.error_image
        DeckleError.OriginalNotSaved -> R.string.error_original_not_saved
        DeckleError.NotFound -> R.string.error_not_found
        is DeckleError.OcrFailed -> R.string.error_ocr
        is DeckleError.ExportFailed -> R.string.error_export
        DeckleError.ImagingUnavailable, DeckleError.Cancelled, is DeckleError.Unexpected -> R.string.error_generic
    },
)
