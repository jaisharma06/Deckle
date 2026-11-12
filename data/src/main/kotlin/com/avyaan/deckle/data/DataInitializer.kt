package com.avyaan.deckle.data

import android.content.Context
import com.avyaan.deckle.data.imaging.engine.OpenCvStatus
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * Native/library initialisation that must run before Hilt builds anything (requirements: "Initialisation
 * order"). Lives in :data so :app never touches OpenCV or PDFBox types directly.
 */
public object DataInitializer {
    public fun init(context: Context) {
        // 1. OpenCV — failure is recorded, never thrown; the UI reads it and degrades.
        OpenCvStatus.init()
        // 2. PDFBox resources — without this, font loading throws at export time.
        PDFBoxResourceLoader.init(context.applicationContext)
    }
}
