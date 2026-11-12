# 04 — OCR, Searchable PDF, Export and Background Work

## 1. Ports

```kotlin
interface TextRecognizer {                       // :domain/ocr
    suspend fun recognize(greyImagePath: String): Outcome<List<OcrLine>>   // Latin script; boxes normalised 0..1
}

interface OcrScheduler {
    fun enqueue(pageIds: List<String>)           // unique per page, KEEP
    fun cancel(pageId: String)
}

interface PdfComposer {                          // :domain/export
    suspend fun compose(request: PdfRequest, out: String, progress: (done: Int, total: Int) -> Unit): Outcome<Long /*bytes*/>
}

interface ImageFileExporter {                    // FR-53 via SAF
    suspend fun export(pages: List<Page>, format: ImageFormat, quality: QualityPreset, treeUri: String,
                       baseName: String, progress: (Int, Int) -> Unit): Outcome<Int>
}

interface ExportScheduler {
    fun enqueue(request: ExportRequest): String                       // returns exportId
    fun observe(exportId: String): Flow<ExportProgress>
    fun cancel(exportId: String)
}
```

---

## 2. OCR

### 2.1 Flow (FR-60–65)

```mermaid
sequenceDiagram
    participant C as CommitScanSession / Recrop
    participant S as OcrScheduler (WorkManager)
    participant W as OcrWorker
    participant E as PageEnhancer
    participant R as TextRecognizer (ML Kit bundled)
    participant P as PageRepository
    C->>S: enqueue(pageId)  [unique "ocr-<pageId>", KEEP, battery-not-low]
    S->>W: doWork()
    W->>P: setOcr(RUNNING, attempts+1)
    W->>E: ocrInput(basePath) → cache/ocr/<pageId>.jpg   (flattened grey, FR-60)
    W->>R: recognize(path)
    R-->>W: lines (normalised)
    W->>P: setOcr(DONE, lines, plainText)  → FTS updated by trigger
    Note over W,P: on failure: setOcr(FAILED); Result.success() (we retry ourselves at next launch, FR-65)
```

- **Why `Result.success()` on failure:** FR-65 specifies the retry policy ("once on next app launch, then left alone"). Letting WorkManager's backoff also retry would double the policy. Unique work + `ocrAttempts` counter keeps it exact.
- Constraint: `setRequiresBatteryNotLow(true)` only (FR-64). Not expedited.
- The worker deletes its `cache/ocr/` temp file in `finally`.
- If the page was deleted while queued, the worker finds no row and returns success silently.

### 2.2 `MlKitTextRecognizer`

```kotlin
@Singleton
class MlKitTextRecognizer @Inject constructor() : TextRecognizer {
    // FR-61: bundled Latin model; created lazily, reused for the process lifetime
    private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun recognize(greyImagePath: String): Outcome<List<OcrLine>> {
        val bitmap = decodeArgb(greyImagePath)            // ML Kit needs a Bitmap; ≤3000 px already
        return try {
            val text = client.process(InputImage.fromBitmap(bitmap, 0)).await()
            Outcome.Success(text.toLines(bitmap.width, bitmap.height))
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) { Outcome.Failure(DeckleError.OcrFailed(e.javaClass.simpleName)) }
          finally { bitmap.recycle() }
    }
}

private fun Text.toLines(w: Int, h: Int): List<OcrLine> =             // FR-62: lines only, not elements
    textBlocks.flatMap { it.lines }.mapNotNull { line ->
        val b = line.boundingBox ?: return@mapNotNull null
        OcrLine(line.text, RectN(b.left / w.toFloat(), b.top / h.toFloat(), b.right / w.toFloat(), b.bottom / h.toFloat()), line.confidence)
    }
```

- `await()` is **our own** tiny `suspendCancellableCoroutine` bridge over `Task.addOnSuccessListener/addOnFailureListener`, not `kotlinx-coroutines-play-services` — one fewer artifact to audit for network behaviour.
- Only `com.google.mlkit:text-recognition` (bundled Latin) is a dependency. It covers English and other Latin-script languages.
- Plain text = lines joined with `\n` in reading order (ML Kit block order), blocks separated by `\n\n`.

### 2.3 Script scope (D-1)

v1 is **Latin only**. There is no script setting and no strategy layer: `MlKitTextRecognizer` talks to one client, and the PDF uses one font (§3.3).

### 2.4 Adding Devanagari in v2 (not to be built now)

Recorded so v1 doesn't block it, not so v1 designs around it. v2 would add `text-recognition-devanagari`, ship `NotoSansDevanagari-Regular.ttf` in `assets/`, persist the script per page (an `ocrScript` column via a written Migration), and *then* introduce `RecognizerStrategy` / `PdfFontStrategy`, because there would be a second implementation to choose between.

### 2.5 Third-party telemetry check

ML Kit bundled artifacts may transitively include `com.google.android.datatransport` (usage logging). With `INTERNET` stripped from the merged manifest these calls cannot leave the device, but the dependency allowlist review (07 §4) must record that this transitive dependency exists, was inspected, and is inert offline. If an excluded-transitive build proves stable on the fixture suite, prefer excluding it.

---

## 3. Searchable PDF (`PdfBoxComposer`)

### 3.1 Request and layout maths (pure domain)

```kotlin
data class PdfRequest(
    val pages: List<PdfPageInput>,          // processedPath, widthPx, heightPx, ocrLines
    val pageSize: PageSizePolicy,           // Auto | A4 | Letter   (FR-51)
    val quality: QualityPreset,             // SMALL | MEDIUM | ORIGINAL (FR-52)
    val password: CharArray?,               // FR-55; zeroed by the worker after use; never persisted
)

sealed interface PageSizePolicy { data object Auto : PageSizePolicy; data object A4 : PageSizePolicy; data object Letter : PageSizePolicy }

enum class QualityPreset(val maxLongEdge: Int?, val jpegQuality: Int?) {
    SMALL(1600, 60), MEDIUM(2400, 75), ORIGINAL(null, null)  // ORIGINAL = embed processed.jpg bytes untouched
}
```

`PdfLayout` (domain, JVM-tested) computes for each page:

| Policy | Page size (pt) | Image rect |
|---|---|---|
| `Auto` | Long edge = 842 pt (A4 long edge), short edge by image aspect | Fills page |
| `A4` | 595 × 842, orientation matches image (landscape image → 842 × 595) | Letterboxed: uniform scale = min(pw/iw, ph/ih), centred. **Never stretched.** |
| `Letter` | 612 × 792, orientation matches image | Same |

Per OCR line, in PDF space (origin bottom-left, FR-69):

```
boxL = imgX + line.left  · imgW
boxW = (line.right − line.left) · imgW
boxH = (line.bottom − line.top) · imgH
baselineY = imgY + imgH − line.bottom · imgH        // == pageHeight − boxBottom·pageHeight when image fills page
size0 = boxH
textW = font.stringWidth(text) / 1000 · size0
size  = size0 · (boxW / textW)                      // FR-69 width correction
skip if size < 1 pt or text.isBlank()
```

`imgX/imgY` are non-zero only for letterboxed pages — the requirements' formula is the `Auto` special case. `PdfLayout` takes a `(String) -> Float` width function so it stays independent of PDFBox.

### 3.2 Composition loop

```kotlin
PDDocument().use { doc ->
    request.pages.forEachIndexed { i, input ->
        coroutineContext.ensureActive()
        val layout = pdfLayout.page(input, request.pageSize)
        val page = PDPage(PDRectangle(layout.pageW, layout.pageH)).also(doc::addPage)
        val image = jpegBytesFor(input, request.quality).use { JPEGFactory.createFromStream(doc, it) }   // FR-67: DCT passthrough
        PDPageContentStream(doc, page).use { cs ->
            val font = PDType1Font.HELVETICA                 // Latin only (D-1); standard 14, nothing embedded
            cs.drawImage(image, layout.imgX, layout.imgY, layout.imgW, layout.imgH)
            layout.lines(input.ocrLines, font::widthPer1000).forEach { placed ->
                writeInvisibleLine(cs, font, placed)                       // FR-68, guarded per line
            }
        }
        progress(i + 1, request.pages.size)
    }
    request.password?.let { doc.protect(aes256Policy(it)) }             // FR-55
    doc.save(outTmp); rename(outTmp, out)
}

private fun writeInvisibleLine(cs: PDPageContentStream, font: PDFont, p: PlacedLine) {
    val text = font.encodableSubset(p.text) ?: return        // strip unencodable chars first; null if nothing left
    try {
        cs.beginText()
        cs.setFont(font, p.size)
        cs.setRenderingMode(RenderingMode.NEITHER)
        cs.newLineAtOffset(p.x, p.baselineY)
        cs.showText(text)
        cs.endText()
    } catch (e: IllegalArgumentException) {                  // unmappable glyph: drop this line only
        runCatching { cs.endText() }
    }
}
```

- **`jpegBytesFor`:** `ORIGINAL` streams `processed.jpg` directly (no re-encode). `SMALL`/`MEDIUM` decode with `inSampleSize`, scale to `maxLongEdge` if larger, re-encode at `jpegQuality` into a `ByteArrayOutputStream` (one page at a time, so memory is bounded).
- **`encodableSubset`:** for each code point, keep it if `font.encode` succeeds (cache per-char results in a small map per export); replace failures with a space. This preserves searchability of the rest of a line (e.g. a "₹" in an otherwise Latin line) — stronger than the requirement's "drop the line", which remains the fallback via the `catch`.
- **Encryption (FR-55):** `StandardProtectionPolicy(ownerPassword = random 32-byte hex, userPassword = String(password), AccessPermission())` with `encryptionKeyLength = 256`. Password `CharArray` is zeroed in `finally`. Never logged, never in `WorkData` (see §5.2).

### 3.3 Font

The text layer uses `PDType1Font.HELVETICA` (standard 14: nothing embedded, no size cost). Characters outside its WinAnsi encoding (e.g. "₹") are replaced by `encodableSubset`, so the rest of the line stays searchable.

### 3.4 Size estimate (FR-52)

`EstimateExportSizeUseCase` (domain) with a `JpegSizeProbe` port:
- `ORIGINAL`: Σ file sizes of `processed.jpg` + ~2 KB/page PDF overhead + text layer (~40 B per OCR line).
- `SMALL`/`MEDIUM`: re-encode the **cover page** at the preset (one probe, cached per document+preset), compute bytes-per-pixel, multiply by Σ target pixel counts of all pages.
- Shown as "≈ 3.4 MB", recomputed when preset or selection changes; displays a shimmer while probing (≤ 300 ms).

---

## 4. Image export (FR-53)

`ActivityResultContracts.OpenDocumentTree` → persistable URI permission not taken (one-shot). `DocumentFile.fromTreeUri(...).createFile("image/jpeg", "<title> - 01.jpg")`. JPEG uses the quality preset; PNG encodes losslessly from `processed.jpg` (and ignores quality — the quality row is hidden for PNG). Filenames are zero-padded to the page count's width and sanitised (`/\:*?"<>|` → `_`).

---

## 5. Background work

### 5.1 Workers

| Worker | Unique name / policy | Constraints | Foreground | Output |
|---|---|---|---|---|
| `OcrWorker` | `ocr-<pageId>`, `KEEP` | battery not low | No | — (writes DB) |
| `ExportWorker` | `export-<exportId>`, `REPLACE` | none | **Yes** (FR-56) | `KEY_OUTPUT_PATH`, `KEY_BYTES` or `KEY_COUNT` |

Both are `@HiltWorker` `CoroutineWorker`s with `@AssistedInject`. `DeckleApplication` implements `Configuration.Provider` with `HiltWorkerFactory`; the default `WorkManagerInitializer` is removed from the merged manifest via `tools:node="remove"` on the `androidx.startup` provider meta-data.

### 5.2 Passing the export request

`WorkData` is persisted to WorkManager's database, so it must not hold the PDF password. The request (minus password) is serialised to JSON in `cacheDir/export-requests/<exportId>.json`; the password is held **only in memory** in an `@Singleton ExportSecrets` map keyed by `exportId` and removed when read. If the process dies before the worker reads it, the worker fails with `DeckleError.ExportFailed("password-lost")` and the UI asks the user to export again — the password is never written to disk (FR-55).

### 5.3 Foreground info (D-7)

```kotlin
override suspend fun getForegroundInfo() = ForegroundInfo(
    NOTIFICATION_ID_EXPORT, notifications.exportProgress(title, done = 0, total),
    if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
    else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
)
```

Manifest: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_MEDIA_PROCESSING`, and `SystemForegroundService` declared with `foregroundServiceType="dataSync|mediaProcessing"` via `tools:node="merge"`.

Notification channel `export` (importance LOW, no sound). Progress updated per page via `setForeground`/`setProgress`. On success the notification becomes "PDF ready · Tap to share"(opens the app to the share sheet via a `PendingIntent` to `MainActivity` with the exportId). If `POST_NOTIFICATIONS` is denied, the export still runs; only the notification is invisible.

### 5.4 Share and save (FR-54)

- **Share:** `FileProvider` authority `${applicationId}.exports`, `res/xml/file_paths.xml` with **only** `<cache-path name="exports" path="exports/"/>`. Intent `ACTION_SEND` (or `ACTION_SEND_MULTIPLE` for images), `FLAG_GRANT_READ_URI_PERMISSION`, `Intent.createChooser`. The app transmits nothing; the user picks the receiver.
- **Save to device:** `ActivityResultContracts.CreateDocument("application/pdf")` with suggested name `<title>.pdf`; copy from cache on `Dispatchers.IO`.

### 5.5 Export progress to UI

`WorkManagerExportScheduler.observe(id)` maps `getWorkInfoByIdFlow` to:

```kotlin
sealed interface ExportProgress {
    data object Queued : ExportProgress
    data class Running(val done: Int, val total: Int) : ExportProgress
    data class Succeeded(val path: String, val bytes: Long) : ExportProgress
    data class Failed(val error: DeckleError) : ExportProgress
}
```
