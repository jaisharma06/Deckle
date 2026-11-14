plugins {
    alias(libs.plugins.deckle.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.avyaan.deckle"

    defaultConfig {
        applicationId = "com.avyaanstudios.deckle"
        // Local builds use these; the release workflow passes values derived from the git tag (v1.2.3 → 10203).
        versionCode = providers.gradleProperty("deckle.versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("deckle.versionName").orNull ?: "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        // Release keys come from the environment only; nothing secret is committed (spec 07 §3.4).
        create("release") {
            val path = System.getenv("DECKLE_KEYSTORE_PATH")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("DECKLE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DECKLE_KEY_ALIAS")
                keyPassword = System.getenv("DECKLE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val useDebugKey = providers.gradleProperty("deckle.signing").orNull == "debug"
            signingConfig = if (System.getenv("DECKLE_KEYSTORE_PATH") != null) signingConfigs.getByName("release")
            else if (useDebugKey) signingConfigs.getByName("debug")
            else null
        }
    }

    androidResources {
        localeFilters += listOf("en")
    }

    // One APK per CPU type instead of one carrying every ABI's native libraries: OpenCV and the OCR engine
    // are ~36 MB of .so per ABI, and a phone only ever uses one set. Play builds from the bundle anyway.
    // x86_64 (emulators) only on request: ./gradlew ... -Pdeckle.emulator=true
    // Per-ABI APKs keep sideloaded/debug installs small. App bundles can't be built with them (AGP issue
    // 402800800) and don't need them: Play delivers per-ABI splits from the bundle itself.
    val bundling = gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }
    if (bundling) defaultConfig.ndk.abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
    splits {
        abi {
            isEnable = !bundling
            reset()
            include("arm64-v8a", "armeabi-v7a")
            if (providers.gradleProperty("deckle.emulator").orNull == "true") include("x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        // Compressed native libraries: the APK file shrinks by ~20 MB (OpenCV alone 24.6 → ~9 MB); the
        // system unpacks them once at install time.
        jniLibs.useLegacyPackaging = true
        // BouncyCastle post-quantum parameter tables (~4 MB): unused by PDF AES-256; R8 already drops the classes.
        resources.excludes += listOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "org/bouncycastle/pqc/**")
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))   // only :app/di may reference :data classes (spec 01 §1)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.coil.compose)
    implementation(libs.reorderable)
    implementation(libs.work.runtime.ktx)
    implementation(libs.room.runtime)       // DatabaseModule builds the DB
    implementation(libs.androidx.documentfile)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(testFixtures(project(":domain")))
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.compose.ui.test.manifest)
}
