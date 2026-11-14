package com.avyaan.deckle.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.avyaan.deckle.domain.document.SortOrder
import com.avyaan.deckle.domain.export.PageSizePolicy
import com.avyaan.deckle.domain.export.QualityPreset
import com.avyaan.deckle.domain.imaging.EnhancementMode
import com.avyaan.deckle.domain.settings.AppSettings
import com.avyaan.deckle.domain.settings.FlashMode
import com.avyaan.deckle.domain.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Typed settings (FR-2, FR-11, FR-70–72, FR-80). Keys ↔ fields mapped in exactly one place;
 * defaults applied on read; unknown enum names fall back to defaults.
 */
@Singleton
public class DataStoreSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) : SettingsRepository {
    private val store = context.dataStore

    override val settings: Flow<AppSettings> = store.data.map(::read).distinctUntilChanged()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs -> write(prefs, transform(read(prefs))) }
    }

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            sortOrder = p[Keys.SORT].toEnum(d.sortOrder),
            libraryLayout = p[Keys.LAYOUT].toEnum(d.libraryLayout),
            defaultMode = p[Keys.MODE].toEnum(d.defaultMode),
            defaultQuality = p[Keys.QUALITY].toEnum(d.defaultQuality),
            defaultPageSize = p[Keys.PAGE_SIZE].toEnum(d.defaultPageSize),
            autoCapture = p[Keys.AUTO_CAPTURE] ?: d.autoCapture,
            flash = p[Keys.FLASH].toEnum(d.flash),
            batchMode = p[Keys.BATCH] ?: d.batchMode,
            saveOriginals = p[Keys.SAVE_ORIGINALS] ?: d.saveOriginals,
            secureScreens = p[Keys.SECURE] ?: d.secureScreens,
            hasSeenCaptureTips = p[Keys.TIPS_SEEN] ?: d.hasSeenCaptureTips,
            shutterSound = p[Keys.SHUTTER_SOUND] ?: d.shutterSound,
            vibration = p[Keys.VIBRATION] ?: d.vibration,
        )
    }

    private fun write(p: MutablePreferences, s: AppSettings) {
        p[Keys.SORT] = s.sortOrder.name
        p[Keys.LAYOUT] = s.libraryLayout.name
        p[Keys.MODE] = s.defaultMode.name
        p[Keys.QUALITY] = s.defaultQuality.name
        p[Keys.PAGE_SIZE] = s.defaultPageSize.name
        p[Keys.AUTO_CAPTURE] = s.autoCapture
        p[Keys.FLASH] = s.flash.name
        p[Keys.BATCH] = s.batchMode
        p[Keys.SAVE_ORIGINALS] = s.saveOriginals
        p[Keys.SECURE] = s.secureScreens
        p[Keys.TIPS_SEEN] = s.hasSeenCaptureTips
        p[Keys.SHUTTER_SOUND] = s.shutterSound
        p[Keys.VIBRATION] = s.vibration
    }

    private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
        this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    private object Keys {
        val SORT = stringPreferencesKey("sort_order")
        val LAYOUT = stringPreferencesKey("library_layout")
        val MODE = stringPreferencesKey("default_mode")
        val QUALITY = stringPreferencesKey("default_quality")
        val PAGE_SIZE = stringPreferencesKey("default_page_size")
        val AUTO_CAPTURE = booleanPreferencesKey("auto_capture")
        val FLASH = stringPreferencesKey("flash")
        val BATCH = booleanPreferencesKey("batch_mode")
        val SAVE_ORIGINALS = booleanPreferencesKey("save_originals")
        val SECURE = booleanPreferencesKey("secure_screens")
        val TIPS_SEEN = booleanPreferencesKey("capture_tips_seen")
        val SHUTTER_SOUND = booleanPreferencesKey("shutter_sound")
        val VIBRATION = booleanPreferencesKey("vibration")
    }

}
