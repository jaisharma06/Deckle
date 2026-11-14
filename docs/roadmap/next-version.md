# Deckle — Next Version Roadmap

**Status:** backlog, not yet designed · **Date:** 2026-11-14 · **Follows:** 1.0.1

Features planned after 1.0.1. Each one is built **one at a time, on request**: before building, it gets a short
design pass (what exactly it does, edge cases, UI), then tests, then implementation. Nothing here is committed
to an order beyond the "Suggested order" at the end.

## Ground rules every feature must respect

- **Offline only.** No `INTERNET` permission, no account, no cloud. The CI manifest gate stays as it is.
  A feature that can't work fully on the device is out of scope (see "Parked" at the end).
- **Private by default.** Nothing leaves the phone unless the user exports or shares it.
- **Fits the existing design.** Calm, tactile UI; every action gets motion plus haptic feedback (spec 05).
- **Degrade, never crash.** Every feature needs a defined fallback when its model or processing step fails.

## Already in 1.0 (so these become extensions, not new builds)

Folders · merge documents · sort · full-text search over OCR · auto-capture · searchable PDF text layer ·
password-protected PDFs · A4 / Letter / Auto page sizes · multi-select of pages in a saved document ·
gallery import · secure screens (no screenshots).

---

## 1. Quick wins

| ID | Feature | What it does | Notes |
|---|---|---|---|
| NV-01 | **Multi-select in the scan review (batch)** | While reviewing a fresh batch: long-press to select several pages, then delete, rotate, apply a filter, or export just those. | The saved document screen already has this; the review screen doesn't. The user asked for this. |
| NV-02 | **Duplicate page** | Copy a page within its document, right after the original. | Copies files under new revisions; OCR result is copied, not re-run. |
| NV-03 | **Pin favourites** | Pin documents or folders to the top of the library. | One column plus a sort tweak. |
| NV-04 | **Library filters** | Filter chips: "Has text", "OCR pending", page count, date range. | Builds on the existing sort and search. |
| NV-05 | **Capture sound and haptics settings** | Separate on/off switches for the shutter sound and haptics. | Respect system silent mode. |

## 2. Smarter scanning

| ID | Feature | What it does | Notes |
|---|---|---|---|
| NV-10 | **Blur warning** | After capture, flag a blurry shot: "This page looks blurry — retake?" | Laplacian variance in OpenCV; threshold tuned on the private detector set. |
| NV-11 | **ID card mode** | Scan the front, then the back; both are laid out on one A4 page. | New capture mode; new composite page type or export layout. |
| NV-12 | **Book mode** | Detect an open book, split it into left and right pages, and flatten the spine curve. | Hardest imaging item; the curve flattening can come in a second step. |
| NV-13 | **Finger and shadow removal** | Remove thumbs holding the page edge and soft phone shadows. | Edge inpainting plus shadow normalisation; optional per page. |
| NV-14 | **Auto-capture improvements** | Faster "hold steady", and auto-capture for page 2+ of a batch without tapping. | Extends the existing auto-capture. |

## 3. Text and smart features (on-device OCR)

| ID | Feature | What it does | Notes |
|---|---|---|---|
| NV-20 | **Highlight search hits on the page** | Opening a search result jumps to the page and highlights the matched words on the image. | OCR line boxes are already stored. |
| NV-21 | **Copy text from a region** | Drag a box on a page to copy only that text. | Uses the stored OCR boxes, so no new OCR run is needed. |
| NV-22 | **Smart titles** | Suggest a name from the text, e.g. "Electricity bill – Oct 2026". | Rule-based on the first lines, dates and keywords; the user confirms. |
| NV-23 | **Detect dates, amounts, phones, emails** | Tappable chips: add a calendar reminder, call, copy, or email. | ML Kit Entity Extraction only if its model can be **bundled**; otherwise regex. |
| NV-24 | **Devanagari OCR (Hindi / Marathi)** | Recognise Devanagari text and include it in the PDF text layer. | Already deferred to v2 in spec 04 §2.4. Needs the bundled model and a Noto font. |

## 4. Organize

| ID | Feature | What it does | Notes |
|---|---|---|---|
| NV-30 | **Tags** | Coloured labels (Bills, IDs, Medical…) that work across folders. | Room migration: tag table plus a join table. |
| NV-31 | **Smart collections** | Collections that fill themselves, e.g. "Receipts this month", "Signed", "Expiring soon". | Saved queries over tags, dates and detected entities. |
| NV-32 | **Expiry reminders** | Set an expiry date on a document (passport, insurance); get notified 90/30/7 days before. | WorkManager plus a local notification. Needs the notification permission flow. |

## 5. Editing and signing

| ID | Feature | What it does | Notes |
|---|---|---|---|
| NV-40 | **Sign documents** | Draw a signature once, save it, then place, resize and rotate it on any page. | The signature is stored encrypted on the device. It is burned in on export, never into the original. |
| NV-41 | **Annotate** | Pen, highlighter, text box. | Stored as a separate vector layer on top of the image, so it can be edited and undone. |
| NV-42 | **Redact** | Black out areas (ID numbers, addresses). Also removes the OCR text under them from the PDF text layer. | Must be a true redaction: the pixels and the text are gone from the export, not just covered. |
| NV-43 | **Watermark on export** | Add text such as "Copy for bank KYC only" across the pages. | Export option; diagonal, semi-transparent. |
| NV-44 | **Fill a scanned form** | Tap a spot to type text onto the page. | Same layer system as NV-41. |

## 6. Export and share

| ID | Feature | What it does | Notes |
|---|---|---|---|
| NV-50 | **Compress to a target size** | "Make it under 2 MB" for government and bank portals. | Binary search on JPEG quality and resolution; show the estimated size before exporting. |
| NV-51 | **More page sizes and margins** | Legal, A5, "fit to image", plus a margin option. | Extends the existing `PageSizePolicy`. |
| NV-52 | **Long-image export** | All pages stitched into one tall JPEG, good for WhatsApp. | Watch memory: stream it in strips. |
| NV-53 | **Share presets** | Saved one-tap exports, e.g. "Accountant: PDF, grayscale, under 5 MB". | Stored in settings. |
| NV-54 | **Strip metadata on export** | Remove EXIF/GPS from images and the device name from PDFs. On by default. | Mostly a check that it already holds, plus a setting. |

## 7. Privacy and security

| ID | Feature | What it does | Notes |
|---|---|---|---|
| NV-60 | **App lock** | Fingerprint, face, or device PIN to open Deckle, with an auto-lock timeout. | `androidx.biometric`. Works fully offline. |
| NV-61 | **Hidden vault** | A locked, encrypted folder whose documents don't appear in the library or search. | Files encrypted with an Android Keystore key. Needs a careful recovery story. |
| NV-62 | **Encrypted backup and restore** | Export everything to one password-protected backup file, stored wherever the user chooses; restore from it later. | Uses the system file picker (SAF), so it needs no `INTERNET` permission. This is the answer to "how do I move to a new phone?". |

## 8. Platform polish

| ID | Feature | What it does | Notes |
|---|---|---|---|
| NV-70 | **Home-screen widget** | "Scan now" plus recent documents. | Jetpack Glance. |
| NV-71 | **Quick Settings tile** | One tap from the notification shade into the camera. | `TileService`. |
| NV-72 | **Import from the share sheet** | Share photos or PDFs from Gallery or WhatsApp into Deckle; they get cleaned up into a document. | `ACTION_SEND` / `ACTION_SEND_MULTIPLE` intent filters. Imported PDFs are rasterised to pages. |
| NV-73 | **Material You colours** | Optional dynamic colour from the wallpaper. Deckle's paper palette stays the default. | A settings toggle. |
| NV-74 | **Dark reading mode in the viewer** | Inverts page colours when reading at night. | Display only, never applied to exports. |

---

## Build order (agreed 2026-11-14)

Phases run in this order. Each phase starts only after the previous one is confirmed working on the phone.

| Phase | Features | Status |
|---|---|---|
| **1. Quick wins** | NV-01 multi-select in review · NV-02 duplicate page · NV-04 library filters (plus a page-count sort) · NV-05 capture sound and haptics settings · merge (already exists, quick check only) | Built, awaiting phone check |
| **2. Smarter scanning** | NV-14 auto-capture improvements · NV-12 book mode · NV-11 ID card mode · NV-13 finger and shadow removal · NV-10 blur warning | Built; not yet on the phone (connection dropped). Book mode passes its device test; finger fix and blur threshold await a device re-run |
| **3. Organize** | NV-30 tags (folders already exist) · NV-03 pin favourites · NV-31 smart collections · NV-32 expiry reminders | — |
| **4. Editing and signing** | NV-40 sign · NV-41 annotate · NV-42 redact · NV-43 watermark · NV-44 fill a scanned form | — |
| **5. Export and share** | NV-50 compress to a target size · NV-51 more page sizes and margins · password PDFs (already exist, quick check only) · NV-52 long image / share as images · NV-53 share presets | — |

Sections 3 (text features), 7 (privacy and security) and 8 (platform polish) aren't scheduled yet. They come after
phase 5 unless you move them up.

## Parked (conflicts with "offline only")

- **Offline translation (ML Kit Translate).** Its language models are downloaded at runtime, which needs internet.
  It could come back only if the models can be bundled in the APK, and they are large (about 30 MB per language).
- **Cloud sync / Google Drive backup through an API.** NV-62 covers the need without an account or network access.

## Tracking

When a feature is picked up, open a GitHub issue titled `NV-xx: <name>` and link it here. Its design goes in
`docs/superpowers/specs/` before any code.
