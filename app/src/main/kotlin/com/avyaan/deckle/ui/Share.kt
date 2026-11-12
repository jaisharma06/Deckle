package com.avyaan.deckle.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import androidx.core.content.FileProvider
import com.avyaan.deckle.BuildConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Export outputs are either absolute paths of files in our cache (shared exports) or content URIs the
 * user chose with the system file picker (saved exports). These helpers accept both.
 */

private fun Context.toShareableUri(output: String): Uri =
    if (output.startsWith("content://")) Uri.parse(output)
    else FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.exports", File(output))

/**
 * FR-54: hands exported files to the system share sheet. The app transmits nothing itself — the user
 * picks the receiver.
 */
fun Context.shareFiles(outputs: List<String>, mimeType: String) {
    val uris = outputs.map(::toShareableUri)
    if (uris.isEmpty()) return
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }.apply {
        type = mimeType
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Opens one output in a viewer app. Returns false when no installed app can show it. */
fun Context.openOutput(output: String, mimeType: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(toShareableUri(output), mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

/**
 * True when every output can still be read: cache files may have been cleaned up, and a saved file may
 * have been deleted or moved by the user since.
 */
suspend fun Context.outputsAvailable(outputs: List<String>): Boolean = withContext(Dispatchers.IO) {
    outputs.isNotEmpty() && outputs.all { output ->
        if (!output.startsWith("content://")) return@all File(output).isFile
        runCatching { contentResolver.openFileDescriptor(Uri.parse(output), "r")?.use { true } ?: false }.getOrDefault(false)
    }
}

/**
 * Keeps read/write access to a file or folder the user picked for an export, across restarts, so Export
 * history can open and share it later. Not every provider offers persistable grants; that only means
 * History may later report the file as unavailable.
 */
fun Context.persistAccess(uri: Uri) {
    runCatching {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
}

/** FR-45: scans can be private, so the clip is marked sensitive (hidden from the clipboard preview, API 33+). */
fun Context.copySensitiveText(text: String) {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(null, text)
    if (Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    clipboard.setPrimaryClip(clip)
}
