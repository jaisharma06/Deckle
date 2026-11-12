package com.avyaan.deckle.domain.scan

import com.avyaan.deckle.domain.common.ApplicationScope
import com.avyaan.deckle.domain.common.DispatcherProvider
import com.avyaan.deckle.domain.common.Logger
import com.avyaan.deckle.domain.geometry.Quad
import com.avyaan.deckle.domain.imaging.ImageInput
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Producer–consumer queue for captured pages (FR-18).
 *
 * Why a single consumer: only one full-resolution page is in memory at a time, which is what keeps a
 * fast batch session under the heap budget. Submitting never suspends, so the shutter is never blocked.
 */
@Singleton
public class ProcessingQueue @Inject constructor(
    private val process: ProcessCaptureUseCase,
    @ApplicationScope scope: CoroutineScope,
    dispatchers: DispatcherProvider,
    private val logger: Logger,
) {
    public sealed interface Job {
        public val sessionId: String
        public val captureId: String

        /** Decode, straighten, downscale, strip EXIF, detect edges. */
        public data class Normalize(
            override val sessionId: String,
            override val captureId: String,
            val input: ImageInput,
            val liveQuad: Quad?,
        ) : Job

        /** Dewarp and enhance with the capture's current settings. */
        public data class Render(override val sessionId: String, override val captureId: String) : Job
    }

    private val jobs = Channel<Job>(Channel.UNLIMITED)

    /**
     * Jobs waiting in [jobs]. A duplicate submission (e.g. Review re-queuing unfinished work on entry) is
     * dropped; a job leaves this set just before it runs, so an edit made mid-render still queues a fresh one.
     */
    private val pending: MutableSet<Job> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    init {
        scope.launch(dispatchers.imaging) {
            for (job in jobs) {
                pending.remove(job)
                try {
                    when (job) {
                        is Job.Normalize -> process.normalize(job.sessionId, job.captureId, job.input, job.liveQuad)
                        is Job.Render -> process.render(job.sessionId, job.captureId)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One bad page must not stop the queue.
                    logger.warn(TAG, "Processing failed for ${job.captureId}", e)
                    process.markFailed(job.sessionId, job.captureId)
                }
            }
        }
    }

    public fun submit(job: Job) {
        if (pending.add(job)) jobs.trySend(job)
    }

    private companion object {
        const val TAG = "ProcessingQueue"
    }
}
