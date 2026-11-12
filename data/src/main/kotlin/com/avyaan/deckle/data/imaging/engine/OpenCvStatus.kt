package com.avyaan.deckle.data.imaging.engine

import org.opencv.android.OpenCVLoader

/**
 * Records whether OpenCV's native library loaded. Initialised first in `Application.onCreate`.
 * A failure is not fatal: the app runs in degraded mode (no auto-detect, framework crop and filters).
 */
public object OpenCvStatus {
    @Volatile
    public var isAvailable: Boolean = false
        private set

    @Volatile
    public var failure: String? = null
        private set

    public fun init() {
        if (isAvailable) return
        isAvailable = try {
            OpenCVLoader.initLocal()
        } catch (t: Throwable) {   // UnsatisfiedLinkError on an unsupported ABI must not crash startup
            failure = t.javaClass.simpleName
            false
        }
        if (!isAvailable && failure == null) failure = "initLocal returned false"
    }
}
