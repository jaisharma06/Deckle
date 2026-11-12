# Google Play listing: Deckle

Copy each block into Play Console in the order the Console asks for it. The character limits are Google's, and every block below fits them.

---

## 1. Main store listing

### App name (max 30)
```
Deckle: Offline PDF Scanner
```

### Short description (max 80)
```
Scan documents to clean, searchable PDFs. 100% offline. No account, no ads.
```

### Full description (max 4000)
```
Deckle turns paper into clean, searchable PDFs, and it never goes online.

No account. No cloud. No ads. No tracking. Deckle doesn't even have internet access, so your documents physically cannot leave your phone unless you share them yourself. Bills, IDs, contracts, receipts, notes: they stay yours.

SCAN IN SECONDS
• Live edge detection finds the page for you
• Auto-capture takes the shot the moment you hold steady
• Batch mode for multi-page documents
• Import photos from your gallery too
• Precise crop editor with magnifier, rotation and re-crop at any time

MAKE IT LOOK PERFECT
• 18 filters: Magic, Colour, Lighten, Black & White, Greyscale, Eco, Photo, Vivid, Warm, Cool, Sepia, Vintage, Matte, Noir, Sketch, Blueprint, Negative and Original
• Removes shadows and uneven lighting from paper pages
• Leaves photos and screenshots looking natural

FIND ANY WORD
• On-device text recognition (OCR) for English and other Latin-script languages
• Exported PDFs are searchable
• Copy text straight from any page

STAY ORGANISED
• Folders and sub-folders
• Move many documents at once
• Large, medium and small icons, list and details views
• Search across every document and folder
• Rename, merge, reorder and rotate pages
• Trash with undo, so nothing is lost by accident

SHARE YOUR WAY
• PDF (A4, Letter or fit to image), JPEG or PNG
• Password-protected PDFs
• Export a whole folder as a ZIP of PDFs
• Export history: share, open or export again in one tap

PRIVATE BY DESIGN
• Works fully offline, everywhere, including in flight mode
• No sign-up, no analytics, no advertising ID
• Optionally hide your scans from screenshots and the recent-apps screen
• Only asks for the camera (and, optionally, notifications for export progress)

Deckle is named after the deckle edge, the rough, untrimmed edge of handmade paper.

Scan it. Keep it. Share it only when you choose.
```

### Release notes for v1.0.0 (max 500)
```
Welcome to Deckle, a document scanner that never goes online.
• Auto-detect and auto-capture pages
• 18 filters with shadow removal
• Searchable PDFs with on-device OCR
• Folders, sub-folders and five library views
• Password-protected PDFs and folder ZIP export
```

---

## 2. Categorisation and contact

| Field | Value |
|---|---|
| App or game | App |
| Category | **Productivity** (Business also works) |
| Tags (pick up to 5) | Document scanner, PDF, Productivity, Office, Utilities |
| Email | _your support email (required, shown publicly)_ |
| Website | `https://jaisharma06.github.io/Deckle/` |
| Privacy policy URL | `https://jaisharma06.github.io/Deckle/privacy/` |

---

## 3. App content (Policy > App content)

**Privacy policy:** the URL from §5.

**Ads:** No, the app does not contain ads.

**App access:** All functionality is available without special access. There is no login.

**Content rating questionnaire:** category *Utility, Productivity, Communication, or Other*. Answer **No** to every question. There is no violence, user-to-user communication, sharing of location, purchases or gambling. Expected rating: **Everyone / PEGI 3**.

**Target audience:** 18+ (or 13+). Picking under-13 pulls in the Families policy, which you don't need.

**News app:** No.

**Government app:** No.

**Financial features:** "My app doesn't provide any financial features".

**Health:** No health features.

### Data safety form
| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | (skipped: nothing collected) |
| Do you provide a way for users to request that their data is deleted? | (skipped) |

This is accurate because nothing is transmitted. Google defines "collected" as data sent off the device. Data processed only on the device doesn't count, and neither does data the user shares through the share sheet.

### Foreground service declaration
Play asks apps that use foreground-service types to justify them. Deckle declares `dataSync` and `mediaProcessing` for exports.
- **Type:** Data sync, Media processing
- **Description:** "When the user exports a large document or a folder (PDF/ZIP), the export continues as a foreground service with a progress notification so it completes if the user leaves the app. It is started only by an explicit user action and stops when the file is written."
- **Video:** a 30-second screen recording of exporting a folder, leaving the app and seeing the progress notification. Upload it unlisted to YouTube and paste the link.

---

## 4. Graphics checklist

Ready-made: `docs/play-store/graphics/play-icon-512.png` and `feature-graphic-1024x500.png`. Both are generated together with the app icon by `python tools/icon/icon.py . docs/play-store/graphics`; change the colours or shapes there.

| Asset | Spec | Notes |
|---|---|---|
| App icon | 512 × 512 PNG, 32-bit, ≤ 1 MB | Full square, no rounded corners or shadow. Google applies the mask. |
| Feature graphic | 1024 × 500 PNG/JPG | Required. Shown at the top of the listing. |
| Phone screenshots | 2–8 images, 16:9 or 9:16, 320–3840 px per side | At least 4 at ≥ 1080 px to be eligible for featuring. |
| Tablet screenshots | optional | Only if you want the tablet listing. |

Suggested screenshot order, each with a short caption bar on top:
1. Live detection outline on a page: "Finds the page for you"
2. Before/after filter comparison: "Clean scans, no shadows"
3. Library in grid view with folders: "Everything organised"
4. OCR text selected on a page: "Searchable, copyable text"
5. Export sheet with the password option: "PDF, JPEG, PNG, ZIP"
6. Settings privacy section: "100% offline. No account."

To capture clean screenshots: `adb shell settings put global sysui_demo_allowed 1`, then turn on demo mode in Developer options (full battery, fixed clock, no notifications).

---

## 5. Hosting the privacy policy

Done. The policy is served by GitHub Pages from the `gh-pages` branch:
- Site: <https://jaisharma06.github.io/Deckle/>
- Policy: <https://jaisharma06.github.io/Deckle/privacy/>

`PRIVACY.md` on `main` is the source. If you change it, update `privacy/index.html` on `gh-pages` to match.

---

## 6. Release steps

1. **Create an upload key**, once, and keep it backed up safely:
   `keytool -genkeypair -v -keystore upload.jks -alias deckle -keyalg RSA -keysize 4096 -validity 10000`
2. **Build the bundle:** set the `DECKLE_KEYSTORE_*` environment variables (see README), then run `./gradlew bundleRelease`. The output is `app/build/outputs/bundle/release/app-release.aab`.
3. **Play App Signing:** accept the default. Google holds the app signing key and you keep the upload key.
4. **Closed testing first.** New *personal* developer accounts must run a closed test with **at least 12 testers opted in for 14 continuous days** before production access is granted. Friends and family with Android phones work; add their Gmail addresses to a tester list.
5. **Production:** after the test period, apply for production access in the Console, then roll out, starting at 20% if you like.
6. Every later update needs `versionCode` increased in `app/build.gradle.kts`.

The application ID `com.AvyaanStudios.Deckle` is permanent once the first bundle is uploaded.
