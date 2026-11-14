package com.avyaan.deckle.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.avyaan.deckle.data.settings.DataStoreSettingsRepository
import com.avyaan.deckle.domain.document.SortOrder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun soundVibrationAndPageCountSort_areOnByDefault_andPersist() = runTest {
        val repo = DataStoreSettingsRepository(context)
        val defaults = repo.settings.first()
        assertThat(defaults.shutterSound).isTrue()
        assertThat(defaults.vibration).isTrue()

        repo.update { it.copy(shutterSound = false, vibration = false, sortOrder = SortOrder.PAGES_DESC) }

        val saved = repo.settings.first()
        assertThat(saved.shutterSound).isFalse()
        assertThat(saved.vibration).isFalse()
        assertThat(saved.sortOrder).isEqualTo(SortOrder.PAGES_DESC)
    }
}
