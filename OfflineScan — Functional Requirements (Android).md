# OfflineScan — Functional Requirements (Android)

Nov 11, 2026 · @Jai Sharma

This specifies a production-ready Android document scanner — capture, edge detection, perspective correction, OCR and searchable-PDF export — that runs with **no network permission in the manifest**. Every dependency is bundled in the APK; nothing is downloaded at runtime, which rules out ML Kit's Document Scanner and forces an OpenCV-based capture pipeline.

`OfflineScan` is a working name and `com.avyaan.offlinescan` a working application ID; both are placeholders to confirm before scaffolding.

## Scope and constraints

v1 is a single-user, single-device scanner: point the camera at a page, get a cropped and cleaned image, stack pages into a document, export a searchable PDF to local storage or a share sheet. Everything runs on the device.

**The offline rule is architectural, not a setting.** `android.permission.INTERNET` is absent from the manifest. Any dependency that reaches the network at runtime is therefore unusable, which has three consequences Claude Code must respect:

- ML Kit's Document Scanner (`play-services-mlkit-document-scanner`) is **excluded**. It requires Google Play Services and downloads its module on first use. The capture pipeline is built on OpenCV instead.
- ML Kit Text Recognition must use the **bundled** artifact (`com.google.mlkit:text-recognition`), not the `play-services-mlkit-*` variant. The bundled models are statically linked at build time and work immediately after install.
- No crash reporting, analytics, remote config, ads, or font/asset downloads. Any such library is a spec violation even if it degrades gracefully.

The app must also work on a device with Google Play Services absent entirely, which makes the constraint testable on a de-Googled emulator image.

Out of scope for v1 is listed in full near the end of this document; the short version is no accounts, no cloud, no sync, no sharing between devices.

## Technology stack

Native Kotlin, single module per layer. Versions below are the ones to pin; where a row says *confirm latest*, resolve the newest stable at scaffold time and record it.

| Concern | Dependency | Version | Why this one |
| --- | --- | --- | --- |
| Language | Kotlin | 2.1.x (confirm latest) | Coroutines for the pipeline, no Java sources |
| UI | Jetpack Compose + Material 3 | BOM, confirm latest | Single-activity, Navigation Compose |
| Camera | `androidx.camera:camera-camera2`, `-lifecycle`, `-view` | 1.4.x (confirm latest) | `ImageAnalysis` for live edge preview, `ImageCapture` for the still |
| Computer vision | `org.opencv:opencv` | `4.14.0` | AAR on Maven Central since 4.9.0; no SDK unzip, no NDK config. Apache 2.0. Stay on the 4.x line — 5.0 is too new to carry a production build |
| OCR (Latin) | `com.google.mlkit:text-recognition` | `16.0.1` | **Bundled** variant: models linked at build time, works with no Play Services |
| OCR (Devanagari) | `com.google.mlkit:text-recognition-devanagari` | `16.0.1` | Optional second script; adds \~4 MB. Include only if Hindi/Marathi scanning is wanted in v1 |
| PDF writing | `com.tom-roush:pdfbox-android` | 2.0.27.0 | Apache 2.0 Android port of PDFBox. Needed because Android's own `PdfDocument` cannot write an invisible text layer |
| Persistence | `androidx.room` (runtime, ktx, compiler) | 2.7.x (confirm latest) | Document and page metadata; images live on disk, not in the DB |
| Settings | `androidx.datastore:datastore-preferences` | confirm latest | Typed prefs, no `SharedPreferences` |
| DI | Hilt | 2.5x (confirm latest) | Constructor injection across layers |
| Background work | `androidx.work:work-runtime-ktx` | confirm latest | OCR and PDF generation survive process death |
| Image loading | Coil | 3.x (confirm latest) | Thumbnail grid; must be configured with no network fetcher |

**SDK levels:** `minSdk 26`, `targetSdk 36`, `compileSdk 36`. API 26 is the floor because it gives adaptive icons, `ImageDecoder` and a clean scoped-storage story; CameraX and ML Kit both support lower, but the extra compatibility work is not worth it.

**Do not add:** Firebase (any artifact), Tesseract/`tess-two` (slower and worse than ML Kit, and needs `.traineddata` files shipped or downloaded), iText (AGPL — incompatible with a closed-source release), any `play-services-mlkit-*` artifact.

## Architecture

&#91;embedded content: module architecture · 4 layers, one-way dependencies\]

Three Gradle modules: `:app` (Compose UI and ViewModels), `:domain` (use cases, pure Kotlin, no Android imports so it unit-tests on the JVM), `:data` (Room, file storage, DataStore, and the OpenCV/ML Kit/PDFBox wrappers).

The boundary that matters is between `:domain` and `:data`. Use cases take and return plain Kotlin types — a page is a file path plus a corner quad, never a `Bitmap` or an OpenCV `Mat`. `Mat` instances are created and released inside `:data`, never handed upward, because they are native allocations outside the GC's view and leaking them crashes the app after a few dozen pages.

Every long operation is a `suspend` function on `Dispatchers.Default`, cancellable, and reports progress through a `Flow`. Nothing CPU-bound runs on the main thread.

## Image processing pipeline

&#91;embedded content: capture pipeline · live detection loop and the one-shot full-resolution path\]

The split between the two lanes is the single most important performance decision in the app. Running Canny on a 12 MP frame at preview rate will drop the camera to a slideshow and heat the device; running it on a 480 px copy costs a few milliseconds.

**Quad detection (`DetectPageUseCase`).** Grayscale, Gaussian blur, Canny, then `findContours` with `RETR_LIST` and `CHAIN_APPROX_SIMPLE`. Sort contours by area descending, take the first whose `approxPolyDP` (epsilon = 2% of arc length) yields exactly four points and passes `isContourConvex`. Reject any quad smaller than 20% of frame area or whose opposite-side length ratio exceeds 4:1 — those are table edges and window frames, not pages. If nothing qualifies, emit no quad rather than a bad one; the overlay then shows nothing and the user can still shoot freehand.

**Stability gate.** Hold the last five detected quads. Treat detection as stable when every corner has moved less than 2% of frame width across all five. Only then draw the overlay solid and enable auto-capture, if the user has it on. This is what stops the overlay flickering between the page and a shadow.

**Dewarp (`DewarpUseCase`).** Order the four corners top-left, top-right, bottom-right, bottom-left by sorting on `x + y` and `x − y`; this ordering step is where most implementations break, producing rotated or mirrored output. Output width is `max(‖TR−TL‖, ‖BR−BL‖)`, output height `max(‖BL−TL‖, ‖BR−TR‖)`. Then `getPerspectiveTransform` followed by `warpPerspective` with `INTER_LINEAR`.

**Shadow flattening**, applied before every enhancement mode including Original: dilate the grayscale image with a 9×9 kernel, median-blur the result at kernel 21 to get a background estimate, then take the per-pixel absolute difference and invert. This removes the gradient cast by the phone's own shadow and costs roughly 80 ms on a 3000 px page.

**Memory.** Wrap every `Mat` in a `use { }` block or release it in a `finally`. Decode the captured JPEG with `inSampleSize` such that the long edge stays at or under 3000 px before converting to `Mat` — a full 108 MP sensor image will exhaust the heap on a mid-range device.

## Functional requirements

Each item is written so it can be turned into a test. Where a number appears it is a requirement, not a suggestion.

### Library — the launch screen

- **FR-1** Show all documents as a grid of cover thumbnails, newest first, with title, page count and date.
- **FR-2** Offer sort by date created, date modified, or title; the choice persists across launches.
- **FR-3** A search field filters documents by title **and** by OCR text content; results show the matching snippet.
- **FR-4** Long-press enters selection mode for multi-select delete, merge and export.
- **FR-5** A prominent floating action button starts a new scan; it is reachable one-handed on a 6.7-inch device.
- **FR-6** Empty state explains what the app does and offers a single "Scan your first document" action.
- **FR-7** Deleting a document moves it to a Recently Deleted bucket held for 30 days, then purged on next launch.

### Capture

- **FR-10** Full-bleed camera preview with the detected page outline drawn over it, per the pipeline above.
- **FR-11** Shutter button, flash toggle (off / on / auto, persisted), and a batch-mode toggle.
- **FR-12** In batch mode the app returns to the preview immediately after each shot and shows a running page count with a thumbnail of the last page.
- **FR-13** Auto-capture, user-toggleable and off by default: fire the shutter when the quad has been stable for 1.2 seconds.
- **FR-14** Tap-to-focus with a visible focus indicator; pinch to zoom.
- **FR-15** An "Import" action brings in existing images from the system photo picker and runs them through the same pipeline from the detection step onward.
- **FR-16** Support both portrait and landscape; the quad overlay must follow device rotation without a reset.
- **FR-17** If the camera permission is denied, show an explanation and a button into system settings — never a blank preview.
- **FR-18** Capture must remain responsive while a previous page is still being processed; processing is queued, not blocking.

### Corner editor

- **FR-20** Show the captured image with four draggable corner handles pre-positioned at the detected quad.
- **FR-21** A magnifier loupe follows the dragged handle so the user can see under their finger.
- **FR-22** Handles are constrained to the image bounds and cannot cross to form a self-intersecting shape.
- **FR-23** If detection found no quad, the handles start at the image corners and the user adjusts from there. The editor is skippable with a "Use full page" action.
- **FR-24** Offer 90-degree rotate left and right, applied before dewarping.
- **FR-25** "Retake" discards the capture and returns to the preview.

### Page review and enhancement

- **FR-30** After dewarping, show the result full-screen with the enhancement mode selector.
- **FR-31** Four enhancement modes, switchable at any time and re-applied from the dewarped original, never from an already-enhanced image:
  1. **Original** — shadow flattening only.
  2. **Colour** — shadow flattening, then CLAHE on the L channel in LAB space, plus a mild saturation lift.
  3. **Greyscale** — shadow flattening, then conversion to grey with contrast normalisation.
  4. **Black & white** — adaptive thresholding (Gaussian, block 21, C 10), tuned for text documents and whiteboards.
- **FR-32** Mode preview must appear in under 400 ms on a 3000 px page; compute on a downscaled copy for the preview and apply at full resolution on commit.
- **FR-33** Manual brightness and contrast sliders, applied on top of the chosen mode, with a reset.
- **FR-34** "Add page" returns to capture within the same document; "Done" saves and opens the document.

### Document detail

- **FR-40** Page thumbnails in a reorderable grid; drag to reorder, with the new order persisted immediately.
- **FR-41** Tap a page for a full-screen pager with swipe navigation and pinch zoom.
- **FR-42** Per-page actions: re-crop, re-enhance, rotate, delete, insert a new page after.
- **FR-43** Rename the document inline. Default title is `Scan YYYY-MM-DD HH:mm`.
- **FR-44** Show per-page OCR status: queued, running, done, or failed.
- **FR-45** "Copy text" puts the full extracted text of the document on the clipboard.

### Export and share

- **FR-50** Export as PDF: all pages, or the current selection.
- **FR-51** PDF page size follows the source aspect ratio by default; offer A4 and Letter as fitted alternatives, with the image letterboxed, never stretched.
- **FR-52** Offer three JPEG quality presets — small, medium, original — and show the estimated output size before export.
- **FR-53** Export as individual JPEG or PNG files to a user-chosen folder via the Storage Access Framework.
- **FR-54** Hand the result to the system share sheet via a `FileProvider` URI; the app itself never transmits anything.
- **FR-55** Optional PDF password protection using PDFBox's AES-256 encryption; the password is never stored.
- **FR-56** Export runs in a foreground `Worker` with a progress notification and survives the app being backgrounded.

### Settings

- **FR-70** Default enhancement mode, default export quality, default PDF page size.
- **FR-71** Auto-capture on/off, flash default, save-originals on/off.
- **FR-72** OCR language selection, limited to the scripts bundled at build time.
- **FR-73** Storage usage display with a "Clear cache" action that removes derived files but never source pages.
- **FR-74** An "About" screen stating plainly that the app has no network access and no data leaves the device.

## OCR and searchable PDF

A searchable PDF is the page image with an invisible text layer positioned over it. Android has no API for this, which is why PDFBox is in the stack.

### Recognition

- **FR-60** OCR runs on the **shadow-flattened greyscale** version of the page, regardless of which enhancement mode the user chose for display. Black & white thresholding destroys the stroke detail ML Kit uses and measurably lowers accuracy on small type.
- **FR-61** Recognition uses `TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)` from the bundled artifact. The app must function with Play Services absent.
- **FR-62** Walk the result tree `Text → TextBlock → Line` and keep each line's text, `boundingBox`, and recognition confidence. Element-level (word) boxes are not needed and roughly triple the stored payload.
- **FR-63** Persist the line list as JSON in the `pages` table, with bounding boxes stored **normalised to 0–1** against the page image dimensions, so a later re-crop or re-export does not invalidate them.
- **FR-64** OCR is queued on `WorkManager` as a unique work request per page, constrained only by battery-not-low. It must never block capture or UI.
- **FR-65** A page whose OCR fails is marked failed and is retried once on next app launch, then left alone. Export still works; the PDF simply has no text layer for that page.

### PDF assembly

- **FR-66** Call `PDFBoxResourceLoader.init(applicationContext)` once in `Application.onCreate`. Without it, font loading throws at export time and the failure looks unrelated.
- **FR-67** For each page: create a `PDPage` sized to the image aspect ratio, add the JPEG with `JPEGFactory.createFromStream` (this stores the JPEG bytes directly with a DCT filter rather than re-encoding), and draw it to fill the page.
- **FR-68** Then, for each OCR line, write invisible text: `beginText()`, `setRenderingMode(RenderingMode.NEITHER)`, position with `newLineAtOffset`, `showText`, `endText`.
- **FR-69** Two coordinate conversions must both be applied, and both are easy to get wrong. PDF's origin is bottom-left while image coordinates are top-left, so `pdfY = pageHeight − (boxBottom × pageHeight)`. Font size is chosen so the drawn string spans the OCR box: start from the box height, then correct by the ratio of box width to `font.getStringWidth(text) / 1000f × size`. Skip any line whose computed size falls below 1 pt.

### Fonts

Font choice is the constraint that decides which scripts can be made searchable.

| Script | Font | Notes |
| --- | --- | --- |
| Latin | Helvetica (`PDType1Font`) | One of the standard 14; nothing to embed, no size cost |
| Devanagari and others | Embedded Noto subset via `PDType0Font.load` | Adds roughly 300 KB to the APK per script, and more to each exported PDF |

If Devanagari OCR is in scope, the matching Noto font must ship in `assets/`. `PDType0Font.load` throws on any character the font cannot encode, so wrap each `showText` call and drop the offending line rather than failing the whole export — one unmappable character must not cost the user their document.

## Data model

Image bytes never go in the database. Room holds metadata and paths; the files live on internal storage. A row that loses its file is a recoverable inconsistency, a 40 MB BLOB in SQLite is not.

### `documents`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | TEXT, PK | UUID string |
| `title` | TEXT | Defaults to `Scan YYYY-MM-DD HH:mm` |
| `createdAt` | INTEGER | Epoch millis |
| `modifiedAt` | INTEGER | Touched on any page change |
| `coverPageId` | TEXT, nullable | Defaults to the first page |
| `deletedAt` | INTEGER, nullable | Non-null means in Recently Deleted; purge after 30 days |

### `pages`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | TEXT, PK | UUID string |
| `documentId` | TEXT, FK, indexed | `onDelete = CASCADE` |
| `orderIndex` | INTEGER | Sparse, stepped by 1000, so reordering rewrites one row not all |
| `sourcePath` | TEXT, nullable | Original capture; null when "save originals" is off |
| `processedPath` | TEXT | Dewarped and enhanced page |
| `thumbPath` | TEXT | 320 px long edge |
| `cornersJson` | TEXT | Four points, normalised 0–1, for re-cropping |
| `enhancementMode` | TEXT | Enum name |
| `rotationDegrees` | INTEGER | 0, 90, 180 or 270 |
| `widthPx`, `heightPx` | INTEGER | Of `processedPath` |
| `ocrStatus` | TEXT | `QUEUED`, `RUNNING`, `DONE`, `FAILED` |
| `ocrLinesJson` | TEXT, nullable | Lines with normalised boxes and confidence |
| `ocrPlainText` | TEXT, nullable | Flattened text, the FTS source |

### `pages_fts`

An `@Fts4(contentEntity = PageEntity::class)` table over `ocrPlainText`, which is what FR-3 searches. Rebuild it in a migration if the OCR format ever changes.

### Files on disk

Everything sits under `context.filesDir`, which is private to the app and excluded from other apps' reach without root:

```
files/docs/<documentId>/source/<pageId>.jpg
files/docs/<documentId>/pages/<pageId>.jpg
files/docs/<documentId>/thumbs/<pageId>.jpg
cache/exports/<documentId>.pdf
```

Exports go to `cacheDir` and are shared through a `FileProvider` with a `cache-path` entry; they are disposable and the OS may reclaim them. Deleting a document deletes its whole folder in the same transaction that removes the rows.

**Migrations:** ship `exportSchema = true` and commit the generated JSON from the first release. Every schema change needs a written `Migration`; `fallbackToDestructiveMigration` must not appear anywhere in the codebase, because for this app it means silently deleting the user's only copy of their documents.

## Non-functional requirements

All targets are stated against a **reference device**: a mid-range phone with an 8-core 2.2 GHz SoC and 6 GB RAM running Android 13, scanning a printed A4 page in office lighting. Claude Code should record the actual device used when it reports measurements.

| Area | Requirement | Target | How to verify |
| --- | --- | --- | --- |
| Startup | Cold start to live preview | ≤ 1.5 s | Macrobenchmark, `StartupTimingMetric` |
| Preview | Detection loop frame rate | ≥ 15 fps sustained | Frame timing over a 60 s session |
| Capture | Shutter press to corner editor shown | ≤ 800 ms | Instrumented trace |
| Processing | Dewarp plus enhance, 3000 px page | ≤ 1.2 s | Benchmark on a fixture image |
| OCR | One page, Latin script | ≤ 2.5 s | Benchmark on a fixture image |
| Export | 10-page searchable PDF | ≤ 6 s | Instrumented test |
| Memory | Peak heap over a 50-page document | ≤ 400 MB, zero OOM | Memory profiler, stress test |
| APK | Per-ABI split, arm64-v8a | ≤ 45 MB | `apkanalyzer` in CI |
| Stability | Crash-free sessions | ≥ 99.5% | Manual soak testing; no remote crash reporting is permitted |
| Battery | 20-page scan session | ≤ 4% battery | Battery Historian |
| Accessibility | All actions reachable with TalkBack | No unlabelled controls | Accessibility Scanner, clean run |
| Accessibility | Touch targets and contrast | ≥ 48 dp, ≥ 4.5:1 text contrast | Accessibility Scanner |
| Theming | Light and dark | Both complete | Visual review of every screen |
| Localisation | All strings externalised | No hardcoded user-facing text | Lint `HardcodedText` as an error |

**Rotation and process death.** Every screen must restore its full state after a configuration change and after process death with "Don't keep activities" enabled. A half-finished scan is the case that usually breaks: an in-progress capture must either survive or fail cleanly back to the library, never crash or leave an orphaned file.

**Degradation, not failure.** On a device where OpenCV's native library fails to load, the app must still capture, let the user crop manually, and export — with automatic detection disabled and a one-line explanation. It must not crash at startup.

## Permissions and privacy

### The complete permission set

| Permission | Why | Notes |
| --- | --- | --- |
| `CAMERA` | Capture | Requested in context, with a rationale screen |
| `POST_NOTIFICATIONS` | Export progress on API 33+ | Requested only when the user first exports |

That is the entire list. No storage permissions: import goes through the Photo Picker and export through the Storage Access Framework, both of which grant per-file access without a permission prompt. Declare `<uses-feature android:name="android.hardware.camera.any" android:required="true" />`.

### Keeping the app offline

Absence of `INTERNET` is not automatic — the manifest merger will happily add it if any dependency declares it. Three things enforce the rule:

1. Explicitly strip it in `AndroidManifest.xml`, so a transitive dependency cannot reintroduce it:

```xml
<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
```

2. A CI step that reads the **merged** manifest from the release build and fails if either string appears. Checking the source manifest is not enough; the merge is where it would creep in.
3. A dependency allowlist review on every version bump. Anything pulling in OkHttp, Retrofit, Firebase or Play Services is rejected.

### Backup

Set `android:allowBackup="false"`. With it left at the default, Android Auto Backup copies the app's internal storage to the user's Google Drive — which would send every scanned document off the device through a path the app never asked for and the user never sees. If backup is wanted later it has to be an explicit, user-initiated local export, not the platform default.

Also set `android:dataExtractionRules` and `android:fullBackupContent` to exclude `files/docs/` so that a future change of mind about backup cannot silently include scans.

### Other privacy requirements

- **FR-80** Set `FLAG_SECURE` on the capture, page review and document detail screens, so scanned content does not appear in the recent-apps thumbnail or in screenshots. Make this a user-facing setting, defaulting to on.
- **FR-81** Strip EXIF GPS tags from any imported image before processing.
- **FR-82** No analytics, no advertising ID access, no `ANDROID_ID` read.
- **FR-83** The Play Data Safety form declares no data collected and no data shared.

## Build configuration and delivery

### Native libraries are the size problem

The OpenCV AAR carries native libraries for four ABIs and is the single largest thing in the build — the 4.10 artifact alone is about 107 MB. Shipping all four to every device is unacceptable, so:

- Release builds restrict ABIs to `arm64-v8a` and `armeabi-v7a`. Drop `x86` and `x86_64`, which exist only for emulators and a handful of Chromebooks.
- Debug builds keep `x86_64` so the project still runs on a standard emulator. Set this with a `ndk { abiFilters }` block that differs per build type.
- Distribute as an **Android App Bundle**; Play then serves one ABI per device. If a direct-download APK is also needed, configure `splits { abi { isEnable = true; isUniversalApk = false } }`.
- Measure the per-ABI size in CI with `apkanalyzer` and fail the build above the 45 MB threshold from the previous section.

### Initialisation order

`Application.onCreate` must, in order: initialise OpenCV (`OpenCVLoader.initLocal()` on 4.9+ — confirm the method name against the pinned version, it replaced the older `initDebug()`), then `PDFBoxResourceLoader.init(applicationContext)`. If OpenCV fails to load, record it in a flag the UI reads, and continue into the degraded mode described above rather than throwing.

### Shrinking

- `isMinifyEnabled = true` and `isShrinkResources = true` for release.
- OpenCV's Java classes are reached from JNI, so add a keep rule for `org.opencv.**`.
- ML Kit ships consumer ProGuard rules; do not add your own unless a missing-class error proves otherwise.
- PDFBox-Android loads font and resource files reflectively. Keep `com.tom_roush.pdfbox.**` and verify an export actually works in a **release** build — this is the classic failure that only appears after minification and never in debug.

### Project hygiene

- Gradle version catalog (`libs.versions.toml`) for every dependency. No version literals in build files.
- Gradle wrapper pinned and committed; `dependencyLocking` enabled so an offline-critical dependency cannot drift.
- Release signing config reads the keystore path and passwords from environment variables. No secrets in the repository.
- Generate a Baseline Profile for the capture flow; it is worth roughly 20–30% off cold start.
- Lint runs with `warningsAsErrors = true`; `HardcodedText`, `UnusedResources` and `MissingPermission` are errors.

### Testing layout

| Layer | Framework | What it covers |
| --- | --- | --- |
| `:domain` | JUnit 5, pure JVM | Corner ordering, quad validation, coordinate maths, PDF layout arithmetic |
| `:data` | Robolectric and instrumented | Room DAOs and migrations, file store, OpenCV wrappers against fixture images |
| `:app` | Compose UI tests | Navigation, state restoration, permission denial paths |
| Pipeline | Instrumented, fixture corpus | 20 reference photographs with hand-marked ground-truth corners |

That fixture corpus is worth building before the pipeline, not after. It should cover a white page on a light table, a page at a steep angle, a page with the phone's shadow across it, a crumpled receipt, a whiteboard, and a page with no detectable border. Detection accuracy is then a measurable number rather than an impression.

## Acceptance criteria

The build is production ready when every one of these passes on a physical device, in a **release** build.

- [ ] Airplane mode on, Wi-Fi and data off: a full scan, OCR, and searchable-PDF export all complete normally.
- [ ] The app installs and runs on an emulator image without Google Play Services.
- [ ] The merged release manifest contains neither `INTERNET` nor `ACCESS_NETWORK_STATE`.
- [ ] `android:allowBackup` is `false` and the data extraction rules exclude `files/docs/`.
- [ ] A 20-page document scans end to end without a crash, an OOM, or a visible pause over 2 seconds.
- [ ] An exported PDF opens in Adobe Acrobat and in Chrome, and text search inside it finds a word from the middle of page 1.
- [ ] Selecting text in the exported PDF highlights a region that visually matches the printed words.
- [ ] Every page of a 10-page export has a text layer, confirmed by `pdftotext` on the output.
- [ ] Rotating the device during capture, cropping, and review loses no state.
- [ ] With "Don't keep activities" on, every screen restores correctly.
- [ ] Denying the camera permission shows the rationale screen, and granting it later recovers without a restart.
- [ ] Detection succeeds on at least 18 of the 20 fixture images, within 2% corner error.
- [ ] Accessibility Scanner reports no errors on any screen.
- [ ] Dark mode is complete and has no unreadable text.
- [ ] The per-ABI release APK is at or under 45 MB.
- [ ] A PDF export works correctly in a minified release build, not just in debug.
- [ ] Deleting a document removes both its rows and its folder; no orphaned files remain.
- [ ] A Room migration from the shipped schema to the current one runs without data loss.

## Deferred to v2

Listed so Claude Code does not build them, and does not design around them either.

| Not in v1 | Why it is out |
| --- | --- |
| Accounts, cloud sync, cross-device | Contradicts the offline rule outright |
| Signature capture and placement | A feature in its own right; needs its own interaction design |
| Annotation and markup | Same |
| ID card mode, two sides composed on one page | Straightforward later, but it is a distinct capture flow |
| Curved-page dewarping for books | Needs a cylindrical surface model, not a homography. Materially harder than everything else here |
| Table extraction to CSV | Needs structure recognition well beyond line-level OCR |
| Barcode and QR scanning | Easy to add later with ML Kit's bundled barcode artifact, but it is not scanning |
| Business card to contact | Depends on entity extraction |
| MRC or JBIG2 compression | What commercial scanners use for small files; a large project and PDFBox has no support |
| Automatic script detection for OCR | Bundled models must be chosen at build time, so the user picks instead |
| Tablet and foldable layouts | v1 targets phone portrait and landscape. Do not build adaptive two-pane layouts, but do not hardcode dimensions that would block them |

One thing worth deciding before scaffolding rather than after: whether Devanagari OCR is in v1. It is the only item on this list that changes the build configuration, because the bundled model and the embedded Noto font both have to be present at compile time.

## Sources

Version numbers and API behaviour were checked against these pages on 11 November 2026.

- [Recognize text in images with ML Kit on Android](https://developers.google.com/ml-kit/vision/text-recognition/android) — bundled coordinates `com.google.mlkit:text-recognition:16.0.1` and `text-recognition-devanagari:16.0.1`, and the unbundled `play-services-mlkit-*` alternatives.
- [ML Kit installation paths](https://developers.google.com/ml-kit/tips/installation-paths) — bundled models are statically linked at build time; unbundled models are downloaded and managed by Google Play Services.
- [OpenCV releases](https://github.com/opencv/opencv/releases) — 4.14.0 is the current 4.x release; 5.0.0 shipped in June 2026. The Android AAR has been on Maven Central since 4.9.0.
- [Android Development with OpenCV](https://docs.opencv.org/4.x/d5/df8/tutorial_dev_with_OCV_on_Android.html) — Gradle dependency form for the Maven Central package.
- [OpenCV4Android usage models](https://opencv.org/opencv4android-usage-models/) — why the AAR is preferred over the SDK distribution when contrib modules are not needed.
- [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) — current release `com.tom-roush:pdfbox-android:2.0.27.0`, Apache 2.0, and the required resource-loader initialisation.
- [Convert scanned documents to searchable PDF in Java with OCR](https://www.dynamsoft.com/codepool/convert-scanned-documents-to-searchable-pdf-java.html) — the invisible text-layer technique using PDFBox's `RenderingMode.NEITHER`.

### Two things to confirm before scaffolding

1. **Is Devanagari OCR in v1?** It is the one decision that changes the build configuration, because both the bundled model and the embedded Noto font must be present at compile time.
2. **Working name and application ID.** `OfflineScan` and `com.avyaan.offlinescan` are placeholders used throughout this document.
