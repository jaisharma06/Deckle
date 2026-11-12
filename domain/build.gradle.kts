// Pure Kotlin. No Android, OpenCV, ML Kit or PDFBox may ever be added here (spec 01 §1).
plugins {
    alias(libs.plugins.deckle.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.javax.inject)

    testFixturesApi(libs.kotlinx.coroutines.core)
    testFixturesApi(libs.kotlinx.coroutines.test)
}
