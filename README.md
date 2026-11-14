# Deckle

**A document scanner for Android that never goes online.**

Deckle turns paper into clean, searchable PDFs using only your phone. It has no account, no cloud, no ads and no analytics. The app doesn't even request the internet permission. Every scan, page and piece of recognised text stays on your device until you choose to share it.

> *Deckle* is the rough, untrimmed edge of handmade paper.

---

## Features

**Scanning**
- Live page detection with auto-capture. It finds the page edges and takes the shot when you hold steady.
- Batch mode for multi-page documents. You can also import photos from the gallery.
- Crop editor with draggable corners, a magnifier and rotation. You can re-crop any page later from the saved original.

**Clean-up**
- 18 filters: Magic, Colour, Lighten, Photo, Vivid, Warm, Cool, Sepia, Vintage, Matte, Greyscale, Noir, Black & White, Eco, Sketch, Blueprint, Negative and Original.
- Shadow and uneven lighting removal on paper pages. Photos and screenshots are left as they are.

**Text recognition (OCR)**
- Reads English and other Latin-script languages on the device.
- Exported PDFs are searchable, and you can copy text from any page.

**Organise**
- Folders and sub-folders. You can move many documents at once.
- Library views: large, medium and small icons, plus list and details.
- Search across all documents and folders. Rename, merge, reorder and rotate pages.
- A trash with undo, so deletes are recoverable.

**Export and share**
- PDF (A4, Letter or fit-to-image), JPEG or PNG, with quality settings.
- Password-protected PDFs.
- Export a whole folder as a ZIP of PDFs.
- An export history where you can share, open or re-export.

**Privacy**
- No `INTERNET` permission. It is removed from the merged manifest and checked in CI.
- Optionally hides scans from screenshots and the recent-apps screen.
- Runtime permissions: camera, plus notifications for export progress. Notifications are optional.

## Screenshots

_Add screenshots to `docs/screenshots/` and link them here._

## Tech stack

| Area | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose, Material 3, MVI view models |
| DI | Hilt |
| Camera | CameraX |
| Imaging | OpenCV (page detection, perspective, filters) |
| OCR | ML Kit Text Recognition (bundled model, works offline) |
| PDF | PdfBox-Android |
| Storage | Room, DataStore, WorkManager for background exports |

### Modules

```
:domain   pure Kotlin. Models, use cases, repository interfaces (no Android)
:data     Room, OpenCV, ML Kit, PDF/ZIP export: implementations of :domain interfaces
:app      Compose UI, navigation, DI wiring
```

`:domain` has no Android dependencies, so business rules are tested with plain JUnit. The design is documented in [`docs/specs/`](docs/specs/00-README.md).

## Building

Requirements: JDK 17 and the Android SDK (compileSdk 36). minSdk is 26 (Android 8.0).

```bash
./gradlew assembleDebug          # per-ABI APKs in app/build/outputs/apk/debug/
./gradlew test                   # unit tests (domain, data, app)
./gradlew lintRelease            # lint gate used by CI
./gradlew bundleRelease          # Play Store bundle (.aab)
```

The APKs are split per ABI (arm64-v8a, armeabi-v7a), because OpenCV's native libraries dominate the size. Play delivers the right split automatically from the bundle.

### Release signing

Release builds read the upload key from environment variables. Nothing secret is committed.

On Windows, `tools/release/build-bundle.ps1` asks for the password and does all of this for you.

```bash
export DECKLE_KEYSTORE_PATH=/path/to/upload.jks
export DECKLE_KEYSTORE_PASSWORD=...  DECKLE_KEY_ALIAS=...  DECKLE_KEY_PASSWORD=...
./gradlew bundleRelease
```

## Project checks

- `tools/ci/check-forbidden.sh` blocks network, analytics and ads libraries in the release classpath.
- `tools/ci/check-merged-manifest.sh` checks that no network permission is present and that the permissions match the snapshot.
- `tools/ci/check-apk-size.sh` sets a size budget for the release APK.

## CI/CD

- **CI** (`.github/workflows/ci.yml`) runs on every push to `main` and on every pull request. It runs the tests, `lintRelease` and a debug-signed R8 release build, then all of the project checks above plus a check that Room schemas are committed. The APKs are uploaded as build artifacts.
- **Release** (`.github/workflows/release.yml`) runs when you push a `vMAJOR.MINOR.PATCH` tag, or manually. It builds a bundle signed with the upload key and checks that it isn't debug-signed. Then it uploads the bundle and R8 mapping to Google Play, using the release notes in `distribution/whatsnew/`. The version comes from the tag: `v1.2.3` becomes versionName 1.2.3 and versionCode 10203.

```bash
git tag v1.0.1 && git push origin v1.0.1    # → internal testing track
```

The release workflow needs these repository secrets: `DECKLE_UPLOAD_KEYSTORE_BASE64`, `DECKLE_KEYSTORE_PASSWORD`, `DECKLE_KEY_ALIAS`, `DECKLE_KEY_PASSWORD` and `PLAY_SERVICE_ACCOUNT_JSON`. An optional variable, `PLAY_RELEASE_STATUS`, controls the release status. Use `draft` until the app's first release is published, then `completed`.

## Privacy

See [PRIVACY.md](PRIVACY.md) (published at <https://jaisharma06.github.io/Deckle/privacy/>). In short: Deckle collects nothing, because it has no way to send anything.

## License

_Choose a license (e.g. Apache-2.0 or GPL-3.0) and add a `LICENSE` file._ Note that bundled third-party libraries keep their own licenses (OpenCV: Apache-2.0, PdfBox-Android: Apache-2.0).
