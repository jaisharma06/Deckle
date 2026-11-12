package com.avyaan.deckle.domain.settings

import com.avyaan.deckle.domain.document.SortOrder
import com.avyaan.deckle.domain.export.PageSizePolicy
import com.avyaan.deckle.domain.export.QualityPreset
import com.avyaan.deckle.domain.imaging.EnhancementMode
import kotlinx.coroutines.flow.Flow

public enum class FlashMode { OFF, ON, AUTO;
    public fun next(): FlashMode = entries[(ordinal + 1) % entries.size]
}

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
    val batchMode: Boolean = false,
    val saveOriginals: Boolean = true,
    val secureScreens: Boolean = true,
    val hasSeenCaptureTips: Boolean = false,
)

public interface SettingsRepository {
    public val settings: Flow<AppSettings>
    public suspend fun update(transform: (AppSettings) -> AppSettings)
}
