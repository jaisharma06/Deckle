package com.avyaan.deckle.ui.labels

import androidx.annotation.StringRes
import com.avyaan.deckle.R
import com.avyaan.deckle.domain.export.PageSizePolicy
import com.avyaan.deckle.domain.export.QualityPreset
import com.avyaan.deckle.domain.imaging.EnhancementMode
import com.avyaan.deckle.domain.settings.FlashMode

/** Domain enums → user words. Kept in one place so Settings, Review and Export always agree. */
@StringRes
fun EnhancementMode.label(): Int = when (this) {
    EnhancementMode.ORIGINAL -> R.string.filter_original
    EnhancementMode.COLOUR -> R.string.filter_colour
    EnhancementMode.GREYSCALE -> R.string.filter_greyscale
    EnhancementMode.BLACK_AND_WHITE -> R.string.filter_bw
    EnhancementMode.MAGIC -> R.string.filter_magic
    EnhancementMode.LIGHTEN -> R.string.filter_lighten
    EnhancementMode.PHOTO -> R.string.filter_photo
    EnhancementMode.ECO -> R.string.filter_eco
    EnhancementMode.VIVID -> R.string.filter_vivid
    EnhancementMode.WARM -> R.string.filter_warm
    EnhancementMode.COOL -> R.string.filter_cool
    EnhancementMode.SEPIA -> R.string.filter_sepia
    EnhancementMode.VINTAGE -> R.string.filter_vintage
    EnhancementMode.MATTE -> R.string.filter_matte
    EnhancementMode.NOIR -> R.string.filter_noir
    EnhancementMode.SKETCH -> R.string.filter_sketch
    EnhancementMode.BLUEPRINT -> R.string.filter_blueprint
    EnhancementMode.NEGATIVE -> R.string.filter_negative
}

@StringRes
fun FlashMode.label(): Int = when (this) {
    FlashMode.OFF -> R.string.capture_flash_off
    FlashMode.ON -> R.string.capture_flash_on
    FlashMode.AUTO -> R.string.capture_flash_auto
}

@StringRes
fun QualityPreset.label(): Int = when (this) {
    QualityPreset.SMALL -> R.string.export_quality_small
    QualityPreset.MEDIUM -> R.string.export_quality_medium
    QualityPreset.ORIGINAL -> R.string.export_quality_original
}

@StringRes
fun PageSizePolicy.label(): Int = when (this) {
    PageSizePolicy.AUTO -> R.string.export_size_auto
    PageSizePolicy.A4 -> R.string.export_size_a4
    PageSizePolicy.LETTER -> R.string.export_size_letter
}
