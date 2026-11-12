package com.avyaan.deckle.domain.common

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher

/** Time source. Injected so time-dependent rules (auto-capture, 30-day purge) are testable. */
public interface Clock {
    public fun now(): Instant
    public fun zone(): ZoneId
}

/** Monotonic milliseconds for timing UI rules (auto-capture hold). Not wall-clock. */
public fun interface MonotonicClock {
    public fun millis(): Long
}

public fun interface IdGenerator {
    public fun newId(): String
}

/**
 * Dispatchers are injected, never referenced statically (spec 01 §7).
 * [imaging] runs one full-resolution job at a time to bound peak memory.
 */
public interface DispatcherProvider {
    public val main: CoroutineDispatcher
    public val io: CoroutineDispatcher
    public val default: CoroutineDispatcher
    public val imaging: CoroutineDispatcher
    public val preview: CoroutineDispatcher
}

/** Qualifier for the process-lifetime coroutine scope (survives screens, not process death). */
@javax.inject.Qualifier
@Retention(AnnotationRetention.RUNTIME)
public annotation class ApplicationScope

/** Short random suffix for file revisions, so caches never show a stale image. */
public fun IdGenerator.newRevision(): String = newId().replace("-", "").take(8)

/** Logging port. Release builds strip debug logs; nothing is ever sent anywhere. */
public interface Logger {
    public fun debug(tag: String, message: String)
    public fun warn(tag: String, message: String, error: Throwable? = null)
}
