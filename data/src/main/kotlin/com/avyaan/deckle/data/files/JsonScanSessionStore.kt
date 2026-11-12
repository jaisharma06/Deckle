package com.avyaan.deckle.data.files

import com.avyaan.deckle.domain.common.DispatcherProvider
import com.avyaan.deckle.domain.common.Logger
import com.avyaan.deckle.domain.scan.ScanSession
import com.avyaan.deckle.domain.scan.ScanSessionStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Scan sessions as `files/staging/<id>/session.json` (spec 02 §5).
 * In memory: one StateFlow per session for the UI. On disk: rewritten atomically after every update.
 */
@Singleton
public class JsonScanSessionStore @Inject constructor(
    private val layout: FileLayout,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) : ScanSessionStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val flows = ConcurrentHashMap<String, MutableStateFlow<ScanSession?>>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    override fun observe(sessionId: String): Flow<ScanSession?> {
        val flow = flowFor(sessionId)
        return flow.onStart { if (flow.value == null) flow.value = read(sessionId) }
    }

    override suspend fun get(sessionId: String): ScanSession? =
        flows[sessionId]?.value ?: read(sessionId)?.also { flowFor(sessionId).value = it }

    override suspend fun create(id: String, createdAtMs: Long, targetDocumentId: String?, insertAfterPageId: String?): ScanSession {
        val session = ScanSession(id, createdAtMs, targetDocumentId, insertAfterPageId)
        lock(id).withLock { write(session) }
        return session
    }

    override suspend fun update(sessionId: String, transform: (ScanSession) -> ScanSession): ScanSession? =
        lock(sessionId).withLock {
            val current = get(sessionId) ?: return@withLock null
            transform(current).also { write(it) }
        }

    override suspend fun discard(sessionId: String) {
        lock(sessionId).withLock {
            withContext(dispatchers.io) { layout.sessionDir(sessionId).deleteRecursively() }
            flows[sessionId]?.value = null
        }
    }

    override suspend fun listAll(): List<ScanSession> = withContext(dispatchers.io) {
        layout.stagingRoot.listFiles()?.filter { it.isDirectory }?.mapNotNull { get(it.name) } ?: emptyList()
    }

    private suspend fun write(session: ScanSession) {
        withContext(dispatchers.io) {
            AtomicFiles.write(layout.sessionJson(session.id)) { it.write(json.encodeToString(ScanSession.serializer(), session).toByteArray()) }
        }
        flowFor(session.id).value = session
    }

    private suspend fun read(sessionId: String): ScanSession? = withContext(dispatchers.io) {
        val file: File = layout.sessionJson(sessionId)
        if (!file.exists()) return@withContext null
        try {
            json.decodeFromString(ScanSession.serializer(), file.readText())
        } catch (e: Exception) {
            logger.warn(TAG, "Corrupt session $sessionId; discarding", e)
            null
        }
    }

    private fun flowFor(id: String) = flows.getOrPut(id) { MutableStateFlow(null) }
    private fun lock(id: String) = locks.getOrPut(id) { Mutex() }

    private companion object {
        const val TAG = "SessionStore"
    }
}
