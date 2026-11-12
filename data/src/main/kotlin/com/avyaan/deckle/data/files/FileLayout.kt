package com.avyaan.deckle.data.files

import android.content.Context
import com.avyaan.deckle.domain.page.PageFileKind
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only class that builds paths (spec 02 §4.1). Everything lives in app-private storage.
 *
 * ```
 * files/docs/<documentId>/{source,base,pages,thumbs}/<pageId>[-<rev>].jpg
 * files/staging/<sessionId>/session.json and <captureId>.<kind>.jpg
 * files/.<documentId>.deleting/          tombstone during permanent delete
 * cache/exports/  cache/preview/  cache/ocr/  cache/coil/
 * ```
 */
@Singleton
public class FileLayout @Inject constructor(@ApplicationContext context: Context) {
    public val filesRoot: File = context.filesDir
    public val cacheRoot: File = context.cacheDir
    public val docsRoot: File = File(filesRoot, "docs")
    public val stagingRoot: File = File(filesRoot, "staging")
    public val exportsDir: File = File(cacheRoot, EXPORTS)
    public val previewDir: File = File(cacheRoot, "preview")

    public fun documentDir(documentId: String): File = File(docsRoot, safe(documentId))

    public fun pageFile(documentId: String, pageId: String, kind: PageFileKind, revision: String?): File {
        val name = safe(pageId) + (revision?.let { "-" + safe(it) } ?: "") + ".jpg"
        return File(File(documentDir(documentId), kind.folder), name)
    }

    public fun sessionDir(sessionId: String): File = File(stagingRoot, safe(sessionId))
    public fun sessionJson(sessionId: String): File = File(sessionDir(sessionId), "session.json")
    public fun stagingFile(sessionId: String, captureId: String, kind: String): File =
        File(sessionDir(sessionId), "${safe(captureId)}.${safe(kind)}.jpg")

    public fun tombstone(documentId: String): File = File(filesRoot, ".${safe(documentId)}.deleting")
    public fun cacheFile(relative: String): File = File(cacheRoot, relative)

    /** IDs are UUIDs we generate, but never let one escape its folder. */
    private fun safe(segment: String): String {
        require(segment.isNotEmpty() && segment.none { it == '/' || it == '\\' } && segment != "." && segment != "..") {
            "Unsafe path segment"
        }
        return segment
    }

    public companion object {
        public const val EXPORTS: String = "exports"
        public const val TOMBSTONE_SUFFIX: String = ".deleting"
    }
}
