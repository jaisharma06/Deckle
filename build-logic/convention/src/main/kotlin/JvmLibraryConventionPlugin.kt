import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** Pure-Kotlin module (`:domain`). Android is not on the classpath, so an Android import is a compile error. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("java-test-fixtures")

        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = DeckleSdk.JAVA
            targetCompatibility = DeckleSdk.JAVA
        }
        extensions.configure<KotlinJvmProjectExtension> {
            explicitApi()
            compilerOptions.applyDeckleDefaults()
        }
        tasks.withType<Test>().configureEach { useJUnitPlatform() }

        dependencies {
            add("testImplementation", libs.findLibrary("junit5-api").get())
            add("testImplementation", libs.findLibrary("junit5-params").get())
            add("testImplementation", libs.findLibrary("truth").get())
            add("testImplementation", libs.findLibrary("turbine").get())
            add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
            add("testRuntimeOnly", libs.findLibrary("junit5-engine").get())
            add("testRuntimeOnly", libs.findLibrary("junit5-platform-launcher").get())
        }
    }
}
