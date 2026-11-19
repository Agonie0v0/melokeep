plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.publish) apply false
}

allprojects {
    group = "dev.zt64.subsonic"
    version = providers.gradleProperty("VERSION_NAME").orElse("0.0.0-local").get()
}