plugins {
	alias(libs.plugins.kotlin.multiplatform)
	alias(libs.plugins.android.kotlin.multiplatform.library)
	alias(libs.plugins.kotlin.serialization)
}

kotlin {
	jvmToolchain(21)

	android {
		namespace = "io.github.agonie0v0.melokeep.shared"
		compileSdk = libs.versions.android.compileSdk.get().toInt()
		minSdk = libs.versions.android.minSdk.get().toInt()

		withHostTest {}
	}

	iosArm64()
	iosSimulatorArm64()

	sourceSets {
		commonMain.dependencies {
			api(libs.ktor.client.core)
			implementation(libs.ktor.client.contentNegotiation)
			implementation(libs.ktor.serialization.json)
			api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
			api(libs.kotlinx.serialization.json)
			api(libs.kotlinx.datetime)
			api(libs.multiplatformSettings)
		}

		androidMain.dependencies {
			implementation(libs.ktor.client.okhttp)
		}

		iosMain.dependencies {
			implementation("io.ktor:ktor-client-darwin:${libs.versions.ktor.get()}")
		}

		commonTest.dependencies {
			implementation(kotlin("test"))
			implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
			implementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}")
		}
	}
}
