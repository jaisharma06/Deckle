package com.avyaan.deckle.feature.export

import com.avyaan.deckle.domain.export.EstimateExportSizeUseCase
import com.avyaan.deckle.domain.export.ExportProgress
import com.avyaan.deckle.domain.export.ExportRequest
import com.avyaan.deckle.domain.export.ExportScheduler
import com.avyaan.deckle.domain.export.JpegSizeProbe
import com.avyaan.deckle.domain.fakes.FakeDocumentRepository
import com.avyaan.deckle.domain.fakes.FakeFileStore
import com.avyaan.deckle.domain.fakes.FakePageRepository
import com.avyaan.deckle.domain.fakes.FakeSettingsRepository
import com.avyaan.deckle.domain.fakes.SequentialIds
import com.avyaan.deckle.domain.geometry.Quad
import com.avyaan.deckle.domain.geometry.Rotation
import com.avyaan.deckle.domain.imaging.Adjustments
import com.avyaan.deckle.domain.imaging.EnhancementMode
import com.avyaan.deckle.domain.ocr.OcrState
import com.avyaan.deckle.domain.page.Page
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sheet's ViewModel lives as long as the document screen, not the sheet. Exporting one page of a batch
 * and then opening the sheet again (for the same page or the whole document) must offer a fresh export.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExportViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val scheduler = object : ExportScheduler {
        val requests = mutableListOf<ExportRequest>()
        val progress = MutableStateFlow<Map<String, ExportProgress>>(emptyMap())
        override fun enqueue(request: ExportRequest, password: CharArray?) {
            requests += request
            progress.value += request.exportId to ExportProgress.Queued
        }
        override fun observe(exportId: String) = progress.map { it[exportId] }.filterNotNull()
        override fun cancel(exportId: String) = Unit
        fun finish(exportId: String) { progress.value += exportId to ExportProgress.Succeeded(listOf("out.pdf"), "application/pdf") }
    }

    private fun page(id: String, order: Long) = Page(
        id = id, documentId = "d1", orderIndex = order, sourcePath = null, basePath = "b/$id", processedPath = "o/$id",
        thumbPath = "t/$id", corners = Quad.FULL, rotation = Rotation.R0, mode = EnhancementMode.COLOUR,
        adjustments = Adjustments.NEUTRAL, widthPx = 100, heightPx = 100, ocr = OcrState.QUEUED,
    )

    private suspend fun viewModel(): ExportViewModel {
        val pages = FakePageRepository().apply { insert(listOf(page("p1", 0), page("p2", 1), page("p3", 2))) }
        val probe = object : JpegSizeProbe { override suspend fun encodedSize(imagePath: String, quality: com.avyaan.deckle.domain.export.QualityPreset) = 1_000L }
        return ExportViewModel(
            documents = FakeDocumentRepository(pages),
            pages = pages,
            settings = FakeSettingsRepository(),
            estimate = EstimateExportSizeUseCase(FakeFileStore(), probe),
            scheduler = scheduler,
            ids = SequentialIds("export"),
        )
    }

    @Test
    fun `after exporting one page, reopening the sheet for the same page allows another export`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.start(listOf("d1"), listOf("p2"))
        advanceUntilIdle()
        vm.onEvent(ExportEvent.Share)
        scheduler.finish(scheduler.requests.single().exportId)
        advanceUntilIdle()

        vm.start(listOf("d1"), listOf("p2"))     // the sheet is opened again
        advanceUntilIdle()
        assertThat(vm.state.value.canExport).isTrue()
        vm.onEvent(ExportEvent.Share)
        assertThat(scheduler.requests).hasSize(2)
        assertThat(scheduler.requests.last().pageIds).containsExactly("p2")
    }

    @Test
    fun `reopening while an export is still running keeps showing its progress`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.start(listOf("d1"), listOf("p2"))
        advanceUntilIdle()
        vm.onEvent(ExportEvent.Share)
        vm.start(listOf("d1"), listOf("p2"))
        advanceUntilIdle()
        assertThat(vm.state.value.busy).isTrue()
    }
}
