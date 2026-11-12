# 08 — Implementation Plan

Milestones are ordered so that each one ends with something **runnable and tested**, and so risky native work (OpenCV, PDFBox in release) is proven early. Within a milestone, tasks are listed in build order. Use TDD for everything in `:domain` and for DAOs.

> Confirmed: name **Deckle**, application ID `com.AvyaanStudios.Deckle` (Kotlin packages stay lower-case `com.avyaan.deckle`), Latin-only OCR (no Devanagari in v1). Nothing blocks M0.

---

## M0 — Scaffold and guard rails (offline from commit #1)

1. Gradle skeleton: modules, `build-logic` convention plugins, version catalog, dependency locking, wrapper checksum.
2. `:app` manifest with permission removals, `allowBackup=false`, backup/extraction rules, FileProvider, WorkManager initializer removal.
3. CI scripts: merged-manifest check, permission snapshot, forbidden/allowlisted dependencies, destructive-migration grep, APK size, lint-as-errors.
4. `DeckleApplication`: `OpenCvStatus.init()`, `PDFBoxResourceLoader.init`, Hilt, `Configuration.Provider`.
5. Design system shell: theme tokens (colour, Inter type, shapes), `DeckleMotion`, `DeckleHaptics`, `LocalReducedMotion`, contrast unit test.
6. `MainActivity` + `NavHost` with placeholder routes inside `SharedTransitionLayout`.

**Done when:** release AAB builds, all CI gates green, app launches to an empty Library placeholder on device and on a no-Play emulator.

## M1 — Domain core (pure JVM)

1. `common/`: `Outcome`, `DeckleError`, `Clock`, `IdGenerator`, `DispatcherProvider`.
2. `geometry/`: `PointN`, `Quad`, `CornerOrdering`, `QuadGeometry`, `QuadRules`, `StabilityGate`, `AutoCaptureMachine`, `QuadRotation`, `CropConstraint`, `DetectionTuning`.
3. `page/OrderIndexAllocator`, `document/DefaultTitle`, `document/SearchQuery`.
4. `export/PdfLayout`, `QualityPreset`, `PageSizePolicy`.
5. All ports from 02 §2, 03 §3, 04 §1. `testFixtures` fakes.

**Done when:** `./gradlew :domain:test` green with every row of the `:domain` test table in 07 §5.

## M2 — Persistence and files

1. Room entities, DAOs, FTS, mappers, `RoomTransactionRunner`, exported schema v1 committed.
2. `FileLayout`, atomic writer, `AndroidPageFileStore` with tombstone protocol.
3. `RoomDocumentRepository`, `RoomPageRepository` (search + snippet parsing).
4. `DataStoreSettingsRepository`.
5. `JsonScanSessionStore`.
6. `RunStartupMaintenanceUseCase` + janitor.

**Done when:** Robolectric suite covers delete-rows-and-folder, crash-recovery of tombstones, FTS search with snippet, session store atomic update.

## M3 — Imaging engine (riskiest; prove it early)

1. Fixture corpus (20 images + `corners.json`) **before** the detector (requirements' advice).
2. `MatScope`; `BitmapNormalizer` (decode ≤3000, EXIF rotate, strip EXIF); `ThumbnailMaker`.
3. `OpenCvPageDetector` (frame + path); fixture accuracy test → tune to ≥ 18/20.
4. `OpenCvPerspectiveCorrector`; `ShadowFlattener`; eight `EnhancementFilter`s; `OpenCvPageEnhancer` incl. `preview()` and `ocrInput()`.
5. `FrameworkEngine` (NoOp detector, `setPolyToPoly` corrector, `ColorMatrix` enhancer); `EngineSelector`; Hilt `ImagingModule`.
6. Micro-benchmarks: dewarp+enhance ≤ 1.2 s, preview < 400 ms; 50-page native-memory stress test.

**Done when:** fixture accuracy and both benchmarks pass on the reference device; forcing `OpenCvStatus` unavailable still yields a warped, enhanced JPEG.

## M4 — Capture flow end-to-end (no OCR yet)

1. `CameraController` (UseCaseGroup + ViewPort), `DetectionAnalyzer`, `PreviewMapping`.
2. `ProcessingQueue`, `ProcessCaptureUseCase`.
3. Capture screen: overlay, shutter, flash, auto-capture ring, batch stack, hint chip, tap-focus, zoom, import, permission gate.
4. Crop editor: `CropCanvas`, handles, mid-edge handles, loupe, snapping, constraints, rotate, full-page toggle, accessibility actions.
5. Review pager: filter strip, adjust sheet, apply-to-all, add page, done.
6. `CommitScanSessionUseCase`; Library banner for resumable sessions.
7. Shared-element and container transitions for FAB→Capture→Crop→Review.

**Done when:** a user can scan single and batch documents and they're saved; rotation and process death mid-scan both recover; capture→crop ≤ 800 ms; detection ≥ 15 fps.

## M5 — Library and document management

1. Library: grid, sort, search (title only until M6), selection mode, merge, soft delete + undo, empty state, glow-on-new.
2. Document detail: reorderable grid, rename, add pages, per-page status badges, overflow.
3. Page viewer: zoomable pager, re-crop, re-enhance, rotate, insert after, delete + undo.
4. Recently deleted screen; 30-day purge on launch.

**Done when:** all FR-1…7 and FR-40…43 behaviours pass their Compose UI tests.

## M6 — OCR

1. `MlKitTextRecognizer` (bundled Latin), `Task.await` bridge.
2. `OcrWorker`, `WorkManagerOcrScheduler`, retry-once-on-launch logic.
3. Wire FTS search into Library; snippets with highlights.
4. OCR status badges (FR-44); Copy text (FR-45).

**Done when:** OCR ≤ 2.5 s per page on the reference device; library search finds a word from page text; failed pages retry once and only once.

## M7 — Export

1. `PdfBoxComposer` (Helvetica text layer), `encodableSubset`, AES-256.
2. `EstimateExportSizeUseCase` + `JpegSizeProbe`.
3. `ExportWorker` (foreground, typed), `ExportSecrets`, `WorkManagerExportScheduler`, notification channel.
4. Export sheet UI, notification-permission prompt, share sheet, SAF save, JPEG/PNG folder export.

**Done when:** 10-page searchable PDF ≤ 6 s; `pdftotext` finds text on every page; encrypted PDF requires the password; **all of this in a minified release build**.

## M8 — Settings, privacy, polish

1. Settings screen (all of FR-70…73), About (FR-74), licences page generation.
2. `SecureWindow` on capture/crop/review/document/viewer, driven by setting (FR-80).
3. Coach marks; predictive back; reduced-motion pass across every animation.
4. Screenshot tests (light/dark/font-scale 2/RTL) for every screen; Accessibility Scanner pass.
5. Baseline Profile; startup benchmark ≤ 1.5 s.

**Done when:** every row of the acceptance checklist (07 §6) is green.

---

## FR traceability matrix

| FR | Spec section | Milestone |
|---|---|---|
| FR-1, 2 | 06 §1, 02 §3.4 | M5 |
| FR-3 | 06 §1, 02 §3.3–3.4, 02 §1.1 `SearchQuery` | M5 (title), M6 (text) |
| FR-4 | 06 §1 | M5 |
| FR-5, 6 | 06 §1, 05 §7 | M5 |
| FR-7 | 06 §1, §9; 02 §6 | M5 |
| FR-10, 11, 14, 16 | 06 §2, 03 §2 | M4 |
| FR-12, 18 | 06 §2, 03 §6 | M4 |
| FR-13 | 02 §1.2, 05 §4.2 | M4 |
| FR-15 | 03 §6.3 | M4 |
| FR-17 | 06 §2–3 | M4 |
| FR-20–25 | 06 §4, 05 §4.4, 02 §1.1 `CropConstraint` | M4 |
| FR-30–34 | 06 §5, 03 §7.2 | M4 |
| FR-40–45 | 06 §6–7, 03 §9 | M5 (40–43), M6 (44–45) |
| FR-50–56 | 06 §8, 04 §3–5 | M7 |
| FR-60–65 | 04 §2 | M6 |
| FR-66–69 | 04 §3, 02 §6 | M7 (init in M0) |
| FR-70–74 | 06 §10, 02 §7 | M8 |
| FR-80 | 06 §2, §10 | M8 |
| FR-81 | 03 §6.3 (re-encode strips EXIF) | M3 |
| FR-82, 83 | 07 §3.2, §4 | M0 |
| NFRs | 07 §5 | M3 (processing), M4 (preview/capture), M6, M7, M8 (startup) |

---

## Definition of done (per task)

- Code follows 01 §5 (UDF contract) and §10 (conventions); no new pattern without a row in 01 §4.
- Unit tests for new domain logic; UI tests for new user-visible behaviour.
- Strings externalised; light + dark + font-scale 2 previews present.
- Animations go through `DeckleMotion` and respect reduced motion.
- No new dependency without updating the allowlist and stating why in the PR.
- All CI gates green.
