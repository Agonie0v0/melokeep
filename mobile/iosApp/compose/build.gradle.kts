plugins {
	alias(libs.plugins.kotlin.multiplatform)
	alias(libs.plugins.compose.multiplatform)
	alias(libs.plugins.compose.compiler)
	alias(libs.plugins.kotlin.serialization)
}

kotlin {
	listOf(
		iosArm64(),
		iosSimulatorArm64(),
	).forEach { iosTarget ->
		iosTarget.binaries.framework {
			baseName = "ComposeApp"
			isStatic = true
			linkerOpts("-lsqlite3", "-lz", "-liconv")
		}
	}

	sourceSets {
		iosMain.dependencies {
			val composeVersion = libs.versions.compose.get()
			implementation(project(":shared"))
			implementation(libs.subsonicKotlin)
			implementation("org.jetbrains.compose.runtime:runtime:$composeVersion")
			implementation("org.jetbrains.compose.foundation:foundation:$composeVersion")
			implementation("org.jetbrains.compose.material3:material3:$composeVersion")
			implementation("org.jetbrains.compose.ui:ui:$composeVersion")
			implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
			implementation(libs.coil.compose)
			implementation(libs.coil.network.ktor3)
			implementation("io.ktor:ktor-client-darwin:${libs.versions.ktor.get()}")
			implementation(libs.multiplatformSettings)
			implementation(libs.kotlinx.serialization.json)
			implementation(libs.kotlinx.datetime)
			implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
		}
	}
}
