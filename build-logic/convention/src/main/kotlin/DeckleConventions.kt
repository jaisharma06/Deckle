import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions

/** Shared SDK levels and Kotlin settings. One place to change them (spec 07 §1). */
internal object DeckleSdk {
    const val MIN = 26
    const val TARGET = 36
    const val COMPILE = 36
    val JAVA = JavaVersion.VERSION_17
    val JVM_TARGET = JvmTarget.JVM_17
}

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun KotlinJvmCompilerOptions.applyDeckleDefaults() {
    jvmTarget.set(DeckleSdk.JVM_TARGET)
    freeCompilerArgs.addAll(
        "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        "-Xjvm-default=all",
    )
}

/** Strict lint is opt-in locally and always on in CI: `-Pdeckle.strictLint=true`. */
internal fun Project.strictLint(): Boolean =
    providers.gradleProperty("deckle.strictLint").orNull?.toBoolean() ?: false

internal fun Project.configureAndroidCommon(ext: CommonExtension<*, *, *, *, *, *>) {
    ext.compileSdk = DeckleSdk.COMPILE
    ext.defaultConfig.minSdk = DeckleSdk.MIN
    ext.compileOptions {
        sourceCompatibility = DeckleSdk.JAVA
        targetCompatibility = DeckleSdk.JAVA
    }
    ext.lint {
        warningsAsErrors = strictLint()
        abortOnError = true
        error += setOf("HardcodedText", "MissingPermission")
        if (strictLint()) error += "UnusedResources"
    }
    ext.testOptions.unitTests.isIncludeAndroidResources = true
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}

/** JUnit 4 + Robolectric tests for Android modules, run on the JUnit Platform. */
internal fun Project.addAndroidUnitTestDependencies() {
    dependencies {
        add("testImplementation", libs.findLibrary("junit4").get())
        add("testImplementation", libs.findLibrary("truth").get())
        add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
        add("testImplementation", libs.findLibrary("turbine").get())
        add("testImplementation", libs.findLibrary("robolectric").get())
        add("testImplementation", libs.findLibrary("androidx-test-core").get())
        add("testRuntimeOnly", "org.junit.vintage:junit-vintage-engine:${libs.findVersion("junit5").get()}")
        add("testRuntimeOnly", libs.findLibrary("junit5-platform-launcher").get())
    }
}
