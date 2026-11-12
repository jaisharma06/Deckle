package com.avyaan.deckle.domain.ocr

import com.avyaan.deckle.domain.common.DeckleError
import com.avyaan.deckle.domain.common.Outcome
import com.avyaan.deckle.domain.imaging.PageEnhancer
import com.avyaan.deckle.domain.page.PageFileStore
import com.avyaan.deckle.domain.page.PageRepository
import javax.inject.Inject

/**
 * One page's OCR (FR-60–65). Runs inside the OCR worker.
 * Recognition reads the shadow-flattened greyscale of the *base* image, whatever filter the user chose (FR-60).
 */
public class RunOcrUseCase @Inject constructor(
    private val pages: PageRepository,
    private val enhancer: PageEnhancer,
    private val recognizer: TextRecognizer,
    private val files: PageFileStore,
) {
    public suspend operator fun invoke(pageId: String): Outcome<Unit> {
        val page = pages.get(pageId) ?: return Outcome.Success(Unit)   // deleted while queued: nothing to do
        if (page.ocr.status == OcrStatus.DONE) return Outcome.Success(Unit)
        val attempts = page.ocr.attempts + 1
        pages.setOcr(pageId, OcrState(OcrStatus.RUNNING, lines = null, attempts = attempts), plainText = null)

        val grey = files.cachePath("ocr/$pageId.jpg")
        return try {
            val result = when (val prepared = enhancer.ocrInput(page.basePath, grey)) {
                is Outcome.Failure -> prepared
                is Outcome.Success -> recognizer.recognize(grey)
            }
            when (result) {
                is Outcome.Success -> {
                    pages.setOcr(pageId, OcrState(OcrStatus.DONE, result.value, attempts), result.value.toPlainText())
                    Outcome.Success(Unit)
                }
                is Outcome.Failure -> {
                    // FR-65: mark failed; the retry happens once on next launch, not via WorkManager backoff.
                    pages.setOcr(pageId, OcrState(OcrStatus.FAILED, null, attempts), plainText = null)
                    Outcome.Failure(result.error)
                }
            }
        } finally {
            files.delete(grey)
        }
    }
}

/** Manual "Try again" from the failed badge. */
public class RetryOcrUseCase @Inject constructor(
    private val pages: PageRepository,
    private val scheduler: OcrScheduler,
) {
    public suspend operator fun invoke(pageId: String): Outcome<Unit> {
        val page = pages.get(pageId) ?: return Outcome.Failure(DeckleError.NotFound)
        pages.setOcr(pageId, OcrState(OcrStatus.QUEUED, null, page.ocr.attempts), plainText = null)
        scheduler.enqueue(listOf(pageId))
        return Outcome.Success(Unit)
    }
}
