package com.avyaan.deckle.domain.settings

import com.avyaan.deckle.domain.document.SortOrder
import com.avyaan.deckle.domain.export.PageSizePolicy
import com.avyaan.deckle.domain.export.QualityPreset
import com.avyaan.deckle.domain.imaging.EnhancementMode
import kotlinx.coroutines.flow.Flow

public enum class FlashMode { OFF, ON, AUTO;
    public fun next(): FlashMode = entries[(ordinal + 1) % entries.size]
}

/** What the shutter does: one page, many pages, both sides of an ID card, or a two-page book spread. */
public enum class CaptureMode { SINGLE, BATCH, ID_CARD, BOOK }

/** How the library lists items, like a file manager's "View" menu. */
public enum class LibraryLayout { LARGE_GRID, GRID, SMALL_GRID, LIST, DETAILS }

public data class AppSettings(
    val sortOrder: SortOrder = SortOrder.CREATED_DESC,
    val libraryLayout: LibraryLayout = LibraryLayout.GRID,
    val defaultMode: EnhancementMode = EnhancementMode.COLOUR,
    val defaultQuality: QualityPreset = QualityPreset.MEDIUM,
    val defaultPageSize: PageSizePolicy = PageSizePolicy.AUTO,
    val autoCapture: Boolean = false,
    val flash: FlashMode = FlashMode.AUTO,
    val captureMode: CaptureMode = CaptureMode.SINGLE,
    val saveOriginals: Boolean = true,
    val secureScreens: Boolean = true,
    val hasSeenCaptureTips: Boolean = false,
    /** NV-05: shutter click on capture. Some regions require it; then it plays regardless. */
    val shutterSound: Boolean = true,
    /** NV-05: haptic feedback across the app (the system haptics setting still applies on top). */
    val vibration: Boolean = true,
    /** NV-13: cover fingertips at the page edge in the document filters (Magic, Colour, B&W). */
    val removeFingers: Boolean = true,
) {
    /** Batch, ID card and book scans all stay in the camera between shots. */
    val staysInCamera: Boolean get() = captureMode != CaptureMode.SINGLE
}

public interface SettingsRepository {
    public val settings: Flow<AppSettings>
    public suspend fun update(transform: (AppSettings) -> AppSettings)
}
