package com.avyaan.deckle.data.common

import android.util.Log
import com.avyaan.deckle.domain.common.Clock
import com.avyaan.deckle.domain.common.IdGenerator
import com.avyaan.deckle.domain.common.Logger
import com.avyaan.deckle.domain.common.MonotonicClock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

public class SystemClock @Inject constructor() : Clock {
    override fun now(): Instant = Instant.now()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

public class ElapsedRealtimeClock @Inject constructor() : MonotonicClock {
    override fun millis(): Long = android.os.SystemClock.elapsedRealtime()
}

public class UuidGenerator @Inject constructor() : IdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}

/** Logcat only. Release builds strip debug calls via R8; nothing is ever transmitted. */
public class AndroidLogger @Inject constructor() : Logger {
    override fun debug(tag: String, message: String) {
        Log.d("Deckle/$tag", message)
    }

    override fun warn(tag: String, message: String, error: Throwable?) {
        Log.w("Deckle/$tag", message, error)
    }
}
