plugins {
    alias(libs.plugins.deckle.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.avyaan.deckle.data"
    // MigrationTest (Robolectric) reads the exported schemas as assets.
    sourceSets.getByName("test").assets.srcDir("$projectDir/schemas")
    // On-device tests: OpenCV's native code can't run in JVM unit tests.
    defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
}

room {
    // Committed to git from the first release; every change needs a written Migration (spec 02 §3).
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.documentfile)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)

    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Offline-critical native/ML/PDF stack. Reviewed against the dependency allowlist (spec 07 §4).
    implementation(libs.opencv)
    implementation(libs.mlkit.text)
    implementation(libs.pdfbox.android)

    testImplementation(testFixtures(project(":domain")))
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)

    androidTestImplementation(testFixtures(project(":domain")))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.serialization.json)
}
