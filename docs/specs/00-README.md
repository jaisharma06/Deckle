# Deckle — Technical Specification

**Status:** v1 design, ready for implementation · **Date:** 2026-11-11
**Source of truth for *what*:** `OfflineScan — Functional Requirements (Android).md` (FR-n numbers below refer to it)
**This folder is the source of truth for *how*.**

Deckle is an offline-only Android document scanner: capture → edge detection → perspective correction → enhancement → OCR → searchable PDF. No `INTERNET` permission, no Play Services dependency, everything bundled.

> **Name (confirmed).** The product is **Deckle** (the rough, untrimmed edge of handmade paper); application ID `com.avyaanstudios.deckle` (Kotlin packages are `com.avyaan.deckle`). This replaces the requirements' placeholders `OfflineScan` / `com.avyaan.offlinescan` everywhere.
>
> **OCR scope (confirmed).** v1 recognises **Latin script only**. Hindi/Marathi (Devanagari) is deferred to v2: no Devanagari model, no Noto font, no build flag.

---

## Reading order

| # | Document | Read it when you are… |
|---|---|---|
| 01 | [Architecture](01-architecture.md) | Starting anything. Modules, layers, SOLID mapping, every design pattern used and *why*, error/concurrency conventions |
| 02 | [Domain model and data layer](02-domain-and-data.md) | Writing use cases, repositories, Room, files, settings |
| 03 | [Imaging pipeline](03-imaging-pipeline.md) | Working on camera, detection, dewarp, enhancement, OpenCV memory |
| 04 | [OCR, PDF and background work](04-ocr-pdf-export.md) | Working on ML Kit, PDFBox, WorkManager, export/share |
| 05 | [UX and design system](05-ux-design-system.md) | Building any UI: theme, motion, haptics, components, feedback rules |
| 06 | [Screen specifications](06-screens.md) | Building a specific screen: state, events, layout, animation choreography |
| 07 | [Build, quality and testing](07-build-quality-testing.md) | Gradle, CI gates, lint, test strategy, offline enforcement |
| 08 | [Implementation plan](08-implementation-plan.md) | Executing: ordered milestones, task list, FR traceability matrix |

An implementer (human or Claude) should be able to build the whole app from 01 → 08 without re-reading the requirements, but the requirements doc wins on any conflict about *behaviour*; these docs win on *structure*.

---

## Design principles (the short version)

1. **Offline is architectural.** Enforced by the manifest, a CI gate on the merged manifest, and a dependency allowlist — not by convention.
2. **Pure domain.** `:domain` is plain Kotlin. Geometry, ordering, stability, PDF layout maths — all JVM-testable without an emulator.
3. **Native memory never escapes `:data`.** No `Mat`, `Bitmap` or `PDDocument` crosses a layer boundary. Pages are file paths plus normalised geometry.
4. **One reason per pattern.** Every design pattern in this codebase has a written justification in [01 §4](01-architecture.md#4-design-patterns--used-and-rejected). Patterns without one are not used.
5. **The UI feels physical.** Paper flies into the stack when you shoot; corners have weight when you drag them; nothing pops in without motion; every action gets visible *and* haptic acknowledgement. See [05](05-ux-design-system.md).
6. **Degrade, never crash.** OpenCV failing to load, OCR failing, a font missing a glyph — each has a defined, user-visible degraded path.

---

## Decisions made in these specs (beyond the requirements)

The requirements left these open or under-specified. Each is a deliberate choice; revisit only with reason.

| # | Decision | Reason | Where |
|---|---|---|---|
| D-1 | **Latin-only OCR in v1** (confirmed). No script setting, no script/font strategy layer: one recognizer, Helvetica only | Devanagari deferred to v2. With a single script, a strategy map would be speculative generality; v2 introduces it when the second script arrives (04 §2.4) | 04 §2.3-2.4 |
| D-2 | Add `basePath` column to `pages` (dewarped, un-enhanced image) | FR-31 requires re-applying enhancement "from the dewarped original"; FR-60 requires OCR on a flattened grey version independent of display mode. With "save originals" off there is no source to re-derive from. | 02 §3.2 |
| D-3 | Add `brightness`, `contrast` columns to `pages` | FR-33 adjustments must survive re-enhance and re-export | 02 §3.2 |
| D-4 | Introduce a **staging area** `files/staging/<sessionId>/` for in-progress scans | Satisfies "an in-progress capture must either survive or fail cleanly… never leave an orphaned file" and FR-18 queueing | 02 §5, 03 §6 |
| D-5 | Two imaging engines (OpenCV, Android-framework fallback) behind an **Abstract Factory** | The degraded mode in the requirements needs manual crop and export to keep working when OpenCV fails; `Matrix.setPolyToPoly` gives a framework perspective warp | 01 §4, 03 §8 |
| D-6 | Single activity declares `configChanges` for orientation/size | FR-16: the quad overlay must follow rotation "without a reset"; avoiding activity recreation avoids a camera rebind. State restoration is still tested via process death. | 01 §6, 03 §2 |
| D-7 | Foreground service types `mediaProcessing` (API 35+) / `dataSync` (API 26–34) for the export worker | FR-56 requires a foreground worker; API 34+ requires a declared type. This adds `FOREGROUND_SERVICE*` normal permissions — they are not network permissions. | 04 §5 |
| D-8 | Merged-manifest **permission allowlist snapshot** in CI (not only the INTERNET deny check) | WorkManager and others merge in `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, etc. A snapshot makes every new permission a reviewed diff. | 07 §4 |
| D-9 | Review screen is always a **pager over the scan session** (single mode = a session that grows by "Add page") | One screen for single and batch flows; fewer states, one code path | 06 §5 |
| D-10 | UX additions inspired by CamScanner/Adobe Scan: mid-edge crop handles, "Apply filter to all", undo snackbars, animated auto-capture ring | Small, high-value, not in the v2 deferral list | 05, 06 |
| D-11 | Bundled font: **Inter** (OFL) for UI | Downloadable fonts are forbidden (offline); a bundled variable font keeps typography consistent across OEMs | 05 §2 |
| D-12 | No Lottie; all illustrations/animations are Compose `Canvas` + vector drawables | Fewer dependencies, zero network risk, smaller APK | 05 §5 |

---

## Glossary

| Term | Meaning |
|---|---|
| **Quad** | Four corner points of a page, normalised to 0–1 against the image they were found in, ordered TL, TR, BR, BL |
| **Source** | The captured/imported photo, upright, long edge ≤ 3000 px, EXIF stripped |
| **Base** | Source after rotation + perspective correction. No enhancement. Input to every enhancement and to OCR |
| **Processed** | Base after shadow flattening + enhancement mode + brightness/contrast. What the user sees and what is exported |
| **Thumb** | Processed downscaled to 320 px long edge |
| **Scan session** | An in-progress capture flow, living in staging until committed into a document |
| **Commit** | Atomically moving a scan session's pages into a document (files + rows) |
| **Engine** | A family of imaging implementations (OpenCV or Framework fallback) |
