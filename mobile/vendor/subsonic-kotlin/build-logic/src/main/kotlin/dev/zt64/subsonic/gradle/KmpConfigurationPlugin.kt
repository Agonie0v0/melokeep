package dev.zt64.subsonic.gradle

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jlleitschuh.gradle.ktlint.KtlintExtension

@Suppress("unused")
private fun <T : Any> Property<T>.assign(value: T) = set(value)

private val Project.libs
    get(): VersionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

@Suppress("unused")
fun Project.publish(
    artifactId: String,
    block: MavenPublishBaseExtension.() -> Unit = {}
) {
    apply(plugin = "com.vanniktech.maven.publish")

    val path = "zt64/subsonic-kotlin"

    extensions.configure<MavenPublishBaseExtension> {
        publishToMavenCentral()

        val hasSigningKey = providers
            .environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey")
            .orElse(providers.gradleProperty("signing.keyId"))
            .orNull != null

        if (hasSigningKey) {
            signAllPublications()
        }

        pom {
            name = "subsonic-kotlin"
            description = "Kotlin Multiplatform library for the Subsonic API"
            inceptionYear = "2026"
            url = "https://github.com/$path"

            licenses {
                license {
                    name = "MIT License"
                    url = "https://opensource.org/licenses/MIT"
                }
            }

            developers {
                developer {
                    id = "zt64"
                    name = "zt64"
                    url = "https://zt64.dev"
                }
            }

            scm {
                url = "https://github.com/$path"
                connection = "scm:git:github.com/$path.git"
                developerConnection = "scm:git:ssh://github.com/$path.git"
            }
        }

        block()
    }
}

@Suppress("unused")
class KmpConfigurationPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        configureKmp(target)
        configureMaintenance(target)
    }

    @OptIn(ExperimentalWasmDsl::class)
    private fun configureKmp(target: Project) {
        target.apply(plugin = "org.jetbrains.kotlin.multiplatform")
        target.extensions.configure<KotlinMultiplatformExtension> {
            explicitApi()
            jvmToolchain(21)

            jvm()

            // These tests kinda suck and are very error-prone so not sure what the best action is
            listOf(js(), wasmJs()).forEach { jsTargetDsl ->
                with(jsTargetDsl) {
                    nodejs {
                        testTask {
                            failOnNoDiscoveredTests = false
                        }
                    }

                    // Browser is annoying so I'll just leave it disabled
                    // browser {
                    //     testTask {
                    //         failOnNoDiscoveredTests = false
                    //     }
                    // }
                }
            }

            linuxArm64()
            linuxX64()
            mingwX64()
            apple()

            compilerOptions {
                freeCompilerArgs.add("-Xconsistent-data-class-copy-visibility")
            }
        }
    }

    private fun KotlinMultiplatformExtension.apple(configure: KotlinNativeTarget.() -> Unit = {}) {
        val isMacOs = System.getProperty("os.name").lowercase().contains("mac")

        if (!isMacOs) return

        listOf(
            iosX64(),
            iosArm64(),
            iosSimulatorArm64(),
            macosArm64(),
            tvosArm64(),
            tvosSimulatorArm64(),
            watchosArm32(),
            watchosArm64(),
            watchosSimulatorArm64()
        ).forEach(configure)
    }

    private fun configureMaintenance(target: Project) {
        target.apply {
            plugin("org.jlleitschuh.gradle.ktlint")
            // this uses a Gradle API that is deprecated in 9.x.?
            // skip it when consumed as a composite build
            if (target.gradle.parent == null) {
                plugin("org.jetbrains.kotlinx.binary-compatibility-validator")
            }
        }

        target.configure<KtlintExtension> {
            version = target.libs.findVersion("ktlint").get().toString()
        }
    }
}