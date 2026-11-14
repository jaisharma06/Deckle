package com.avyaan.deckle.data.markup

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.avyaan.deckle.data.files.AtomicFiles
import com.avyaan.deckle.data.files.FileLayout
import com.avyaan.deckle.domain.common.DispatcherProvider
import com.avyaan.deckle.domain.common.IdGenerator
import com.avyaan.deckle.domain.common.Logger
import com.avyaan.deckle.domain.markup.SavedSignature
import com.avyaan.deckle.domain.markup.SignatureInk
import com.avyaan.deckle.domain.markup.SignatureStore
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Seals and opens small blobs. The real one uses a hardware-backed Android Keystore key. */
public interface SecretBox {
    public fun seal(plain: ByteArray): ByteArray
    public fun open(sealed: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a key that never leaves the Android Keystore (and can't be exported or backed up).
 * Output: 12-byte IV followed by the ciphertext and tag. If the key is ever lost (e.g. the user clears the
 * app's data), old files simply fail to open and are skipped.
 */
public class KeystoreSecretBox @Inject constructor() : SecretBox {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "deckle.signatures"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

/** NV-40: saved signatures, one encrypted file each under `files/signatures/`. Newest first. */
@Singleton
public class EncryptedSignatureStore @Inject constructor(
    layout: FileLayout,
    private val box: SecretBox,
    private val ids: IdGenerator,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) : SignatureStore {
    private val dir = File(layout.filesRoot, "signatures")
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun list(): List<SavedSignature> = withContext(dispatchers.io) {
        dir.listFiles { f -> f.name.endsWith(EXT) }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .mapNotNull { file ->
                runCatching {
                    val ink = json.decodeFromString(SignatureInk.serializer(), box.open(file.readBytes()).decodeToString())
                    SavedSignature(file.name.removeSuffix(EXT), ink)
                }.onFailure { logger.warn(TAG, "Skipping unreadable signature", it) }.getOrNull()
            }
    }

    override suspend fun save(ink: SignatureInk): SavedSignature = withContext(dispatchers.io) {
        dir.mkdirs()
        val id = ids.newId()
        val sealed = box.seal(json.encodeToString(SignatureInk.serializer(), ink).encodeToByteArray())
        AtomicFiles.write(File(dir, id + EXT)) { it.write(sealed) }
        // Keep the list short: a few signatures (full, initials) is all anyone needs.
        dir.listFiles { f -> f.name.endsWith(EXT) }.orEmpty().sortedByDescending { it.lastModified() }.drop(MAX_SAVED).forEach { it.delete() }
        SavedSignature(id, ink)
    }

    override suspend fun delete(id: String): Unit = withContext(dispatchers.io) {
        File(dir, id.filter { it.isLetterOrDigit() || it == '-' } + EXT).delete()
        Unit
    }

    private companion object {
        const val TAG = "SignatureStore"
        const val EXT = ".sig"
        const val MAX_SAVED = 5
    }
}
