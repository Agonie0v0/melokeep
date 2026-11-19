plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.plugins.kotlin.multiplatform)
    implementation(libs.plugins.publish)
    implementation(libs.plugins.ktlint)
    implementation(libs.plugins.compatibility)
}

gradlePlugin {
    plugins.register("kmp-configuration") {
        id = "kmp-configuration"
        implementationClass = "dev.zt64.subsonic.gradle.KmpConfigurationPlugin"
    }
}

fun DependencyHandler.implementation(dependency: Provider<PluginDependency>) {
    implementation(dependency.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" })
}