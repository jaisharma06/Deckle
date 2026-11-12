# 07 — Build, Quality and Testing

## 1. Project skeleton

```
Deckle/
├── settings.gradle.kts            rootProject.name = "Deckle"; include(":app", ":domain", ":data", ":benchmark")
├── gradle/libs.versions.toml      every dependency + plugin version (no literals in build files)
├── gradle.properties              org.gradle.configuration-cache=true
├── build-logic/                   convention plugins: deckle.android.app, deckle.android.library, deckle.jvm.library, deckle.compose
├── app/   domain/   data/   benchmark/   (Macrobenchmark + Baseline Profile generator)
├── fixtures/corpus/               20 reference photos + corners.json ground truth (Git LFS)
├── tools/ci/                      check-merged-manifest.sh, check-apk-size.sh, check-forbidden.sh
└── docs/specs/                    these documents
```

Convention plugins keep each module's `build.gradle.kts` to a dozen lines and make SDK levels, lint and Kotlin options identical everywhere.

## 2. Versions (`libs.versions.toml`)

Pin per the requirements; "confirm latest" items are resolved at scaffold time and recorded here with the date.

```toml
[versions]
kotlin = "2.1.x"            # confirm latest stable at scaffold
agp = "8.x"                 # confirm latest stable supporting compileSdk 36
compose-bom = "…"           # confirm latest
camerax = "1.4.x"           # confirm latest
opencv = "4.14.0"           # pinned by requirements — stay on 4.x
mlkit-text = "16.0.1"
pdfbox-android = "2.0.27.0"
room = "2.7.x"
datastore = "…"
hilt = "2.5x"
work = "…"
coil = "3.x"
navigation = "…"            # type-safe routes need ≥ 2.8
lifecycle = "…"             # SavedStateHandle.getMutableStateFlow needs ≥ 2.9
kotlinx-serialization = "…"
kotlinx-collections-immutable = "…"
reorderable = "…"           # sh.calvin.reorderable
junit5 = "…"; robolectric = "…"; turbine = "…"; truth = "…"

[libraries]
opencv = { module = "org.opencv:opencv", version.ref = "opencv" }
mlkit-text = { module = "com.google.mlkit:text-recognition", version.ref = "mlkit-text" }
pdfbox-android = { module = "com.tom-roush:pdfbox-android", version.ref = "pdfbox-android" }
coil-compose = { module = "io.coil-kt.coil3:coil-compose", version.ref = "coil" }   # NO coil-network-* artifact
# …
```

**Coil 3 has no network fetcher unless a `coil-network-*` artifact is added** — so "configured with no network fetcher" is satisfied by *not adding it*, and the forbidden-dependency check (§4) fails the build if one appears. The app's `ImageLoader` is built with `components { }` limited to file decoding and a `DiskCache` in `cache/coil`.

`dependencyLocking { lockAllConfigurations() }` in every module; lockfiles committed. Gradle wrapper pinned with `distributionSha256Sum`.

## 3. Build configuration

### 3.1 `:app`

```kotlin
android {
    namespace = "com.avyaan.deckle"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.AvyaanStudios.Deckle"
        minSdk = 26; targetSdk = 36
    }
    buildTypes {
        debug   { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = true; isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")        // reads env vars, see §3.4
        }
    }
    // Every build type: one APK per ABI. x86_64 (emulators) only with -Pdeckle.emulator=true.
    splits { abi { isEnable = true; reset(); include("arm64-v8a", "armeabi-v7a"); isUniversalApk = false } }
    androidResources { localeFilters += listOf("en") }   // extend as translations land
    lint { warningsAsErrors = true; error += listOf("HardcodedText", "UnusedResources", "MissingPermission") }
    packaging {
        jniLibs.useLegacyPackaging = true   // compressed .so in the APK: OpenCV 24.6 → ~9 MB
        resources.excludes += listOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/*.kotlin_module")
    }
}
```

(The `splits` block only affects direct APK builds; the App Bundle is the primary artifact.) Measured: arm64 release APK 21.9 MB, arm64 debug APK 40 MB (unshrunk code). Before splits and compressed libraries: 68 MB release, 136 MB debug.

### 3.2 Manifest essentials

```xml
<manifest xmlns:tools="http://schemas.android.com/tools">
    <uses-feature android:name="android.hardware.camera.any" android:required="true" />
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING" />

    <!-- Offline rule: strip anything a dependency tries to merge in -->
    <uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" tools:node="remove" />
    <uses-permission android:name="com.google.android.gms.permission.AD_ID" tools:node="remove" />

    <application
        android:name=".DeckleApplication"
        android:allowBackup="false"
        android:fullBackupContent="@xml/backup_rules"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:localeConfig="@xml/locales_config" …>
        <activity android:name=".MainActivity" android:exported="true"
            android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"
            android:windowSoftInputMode="adjustResize" />
        <provider android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.exports" android:exported="false" android:grantUriPermissions="true">
            <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/file_paths" />
        </provider>
        <provider android:name="androidx.startup.InitializationProvider" android:authorities="${applicationId}.androidx-startup" tools:node="merge">
            <meta-data android:name="androidx.work.WorkManagerInitializer" tools:node="remove" />
        </provider>
        <service android:name="androidx.work.impl.foreground.SystemForegroundService"
            android:foregroundServiceType="dataSync|mediaProcessing" tools:node="merge" />
    </application>
</manifest>
```

`backup_rules.xml` / `data_extraction_rules.xml` exclude `domain="file" path="docs/"` (and `staging/`) for both cloud-backup and device-transfer.

### 3.3 R8 rules (`proguard-rules.pro`)

```
-keep class org.opencv.** { *; }                     # reached from JNI
-keep class com.tom_roush.pdfbox.** { *; }           # reflective resource/font loading
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.pdfbox.filter.JPXFilter       # optional JPEG2000 codec not shipped
-dontwarn org.bouncycastle.**                        # only if R8 reports it; verify AES-256 export in release
-assumenosideeffects class android.util.Log { public static int d(...); public static int v(...); }
```

No ML Kit rules (it ships consumer rules). Acceptance: an encrypted, searchable PDF export works in a **minified release** build (instrumented test runs against `release` with a debug-signed key in CI).

### 3.4 Signing
`signingConfigs.release` reads `DECKLE_KEYSTORE_PATH`, `DECKLE_KEYSTORE_PASSWORD`, `DECKLE_KEY_ALIAS`, `DECKLE_KEY_PASSWORD` from the environment; if absent, the release task fails with a clear message (local release builds use a throwaway key via `-Pdeckle.signing=debug`).

## 4. CI gates (all must pass on every PR)

| Gate | Implementation | Fails when |
|---|---|---|
| **Merged-manifest network check** | `tools/ci/check-merged-manifest.sh` greps `app/build/intermediates/merged_manifests/release/**/AndroidManifest.xml` (or `aapt2 dump permissions` on the release APK) | `android.permission.INTERNET` or `ACCESS_NETWORK_STATE` present |
| **Permission allowlist snapshot (D-8)** | Dump merged permissions, diff against `tools/ci/expected-permissions.txt` | Any added or removed permission (forces a reviewed diff) |
| **Forbidden dependencies** | `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` piped to a grep list | Any of: `com.google.firebase`, `com.google.android.gms:play-services-mlkit`, `com.squareup.okhttp3`, `com.squareup.retrofit2`, `io.coil-kt.coil3:coil-network`, `com.itextpdf`, `tess-two`, `com.google.android.gms:play-services-ads`, `oss-licenses`, `text-recognition-devanagari` (v2 only) |
| **Reviewed exceptions** | `tools/ci/allowed-exceptions.txt` | The bundled `com.google.mlkit:text-recognition:16.0.1` itself depends on `play-services-mlkit-text-recognition(-common)` and `firebase-{annotations,components,encoders,encoders-json}` (API surface and component wiring; the model and native pipeline ship in the APK). These exact artifacts are exempt; anything else under the forbidden prefixes still fails. ML Kit's `datatransport` logger is present but cannot send without INTERNET. |
| **Transitive allowlist** | Same output compared to `tools/ci/allowed-groups.txt` | A new Maven group appears (forces the allowlist review from the requirements) |
| **No destructive migration** | `grep -r fallbackToDestructiveMigration` over sources | Any hit |
| **Domain purity** | `:domain` is a JVM module (compile-time) + an architecture test (§5) | `:app` UI code imports `com.avyaan.deckle.data` outside `di/` |
| **APK size** | `tools/ci/check-apk-size.sh`: compressed size of the release APK without `lib/armeabi-v7a` (equivalent to the arm64 split; `bundletool` + `apkanalyzer` when the AAB is built). BouncyCastle `org/bouncycastle/pqc/**` tables are excluded from packaging (~4 MB, unused). | arm64-v8a APK > 45 MB |
| **Lint** | `./gradlew lintRelease` | Any warning (warningsAsErrors) |
| **Unit + Robolectric tests** | `./gradlew test` | Failure |
| **Schema committed** | `git diff --exit-code data/schemas` after build | Generated schema differs from committed |

Instrumented, benchmark and fixture-pipeline suites run nightly on a physical reference device (or Firebase-free device farm — must not be Firebase Test Lab if that would require adding Firebase artifacts; a local runner or Gradle Managed Devices for emulator-safe suites).

## 5. Test strategy

| Layer | Framework | Covers | Examples |
|---|---|---|---|
| `:domain` | JUnit 5, Truth, Turbine, coroutines-test | All pure logic and use cases with fake ports | `CornerOrdering` (incl. 45° diamond), `QuadRules` (area/ratio boundaries at exactly 20 % and 4:1), `StabilityGate`, `AutoCaptureMachine` transition table, `OrderIndexAllocator` (renumber trigger), `QuadRotation` (4 rotations = identity), `PdfLayout` (auto/A4/Letter, letterbox offsets, FR-69 y-flip, < 1 pt skip), `SearchQuery` sanitising, `CommitScanSessionUseCase` rollback, `DefaultTitle` |
| `:data` (JVM) | Robolectric | Room DAOs, FTS search + snippet parsing, migrations (`MigrationTestHelper` from v1 schema), `JsonScanSessionStore` atomicity, tombstone protocol, settings mapping | "Deleting a document removes rows and folder", "crash between tombstone and DB delete is resolved by janitor" |
| `:data` (device) | AndroidJUnit4 | OpenCV wrappers on fixtures, ML Kit on a fixture page, PDFBox output parsed back with PDFBox `PDFTextStripper` (text present, positions within boxes) | Encrypted PDF opens with the password and not without |
| `:app` | Compose UI tests (`createAndroidComposeRule`), Hilt testing | Navigation, permission denial path, state restoration (`StateRestorationTester`), selection mode, crop accessibility actions | "Deny camera → rationale → grant → camera without restart" |
| Process death | Instrumented with `ActivityScenario.recreate()` + "Don't keep activities" device setting in nightly | Each screen restores; mid-scan session resumes from library banner | |
| Pipeline accuracy | Instrumented, `fixtures/corpus` | Detection ≥ 18/20 within 2 % corner error (mean corner distance / image diagonal); reports per-image numbers as a CSV artifact | White page on light table, steep angle, shadow, crumpled receipt, whiteboard, no border |
| Performance | Macrobenchmark + Microbenchmark | NFR table: cold start ≤ 1.5 s (`StartupTimingMetric`), detection fps ≥ 15 (`FrameTimingMetric` over 60 s), dewarp+enhance ≤ 1.2 s, OCR ≤ 2.5 s, 10-page export ≤ 6 s | Baseline Profile generated from the capture flow journey |
| Memory | Instrumented stress | 50-page session: peak Java heap ≤ 400 MB, native heap returns to baseline ±20 MB, zero OOM | |
| Offline | Manual + scripted | Airplane mode end-to-end; emulator image **without Google Play** end-to-end | Acceptance checklist |

### 5.1 Fakes, not mocks

Domain tests use hand-written fakes (`FakePageRepository` backed by a `MutableStateFlow<Map>`, `FakeClock`, `SequentialIdGenerator`, `FakePageEnhancer` that copies files). Fakes live in a `:domain` `testFixtures` source set so `:data` and `:app` tests reuse them. Mocking libraries are not used for domain ports — fakes exercise real behaviour and survive refactors.

### 5.2 Architecture test

A small JVM test in `:app` (Konsist) asserts:
- classes in `..feature..` never import `com.avyaan.deckle.data..`;
- every `*UseCase` in `:domain` has exactly one public function named `invoke`;
- every `*ViewModel` exposes `state: StateFlow<*>` and `fun onEvent(...)`.

## 6. Acceptance checklist mapping

Every acceptance criterion in the requirements maps to an automated test or a scripted manual step:

| Acceptance criterion | Verified by |
|---|---|
| Airplane mode: scan, OCR, PDF all work | Manual script `docs/qa/offline-run.md` on reference device (release build) |
| Runs without Play Services | Nightly on a non-Google AOSP emulator image |
| Merged manifest has no INTERNET/ACCESS_NETWORK_STATE | CI gate §4 |
| `allowBackup=false`, extraction rules exclude docs | Robolectric manifest test + CI grep |
| 20-page scan, no crash/OOM/pause > 2 s | Instrumented stress test + `FrameTimingMetric` jank check |
| PDF opens in Acrobat & Chrome; search finds a mid-page-1 word | Manual + `pdftotext` check in nightly |
| Selection highlight matches printed words | Manual visual check; automated proxy: PDFTextStripper positions within 5 % of OCR boxes |
| Every page of 10-page export has text layer | `pdftotext` per page non-empty, nightly |
| Rotation during capture/crop/review loses no state | Compose UI test with `setRequestedOrientation` loop |
| Don't keep activities restores every screen | Nightly suite |
| Camera denial → rationale → later grant recovers | Compose UI test |
| Detection ≥ 18/20 within 2 % | Fixture suite |
| Accessibility Scanner clean | `AccessibilityChecks.enable()` in Espresso/Compose tests + manual scanner pass |
| Dark mode complete | Screenshot tests (Roborazzi) light+dark for every screen |
| arm64 APK ≤ 45 MB | CI gate |
| PDF export in minified release | Nightly instrumented test against release |
| Delete removes rows + folder | Robolectric test |
| Room migration without data loss | `MigrationTestHelper` test for every migration from v1 |
