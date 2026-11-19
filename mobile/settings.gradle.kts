/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:Suppress("UnstableApiUsage")

rootProject.name = "MeloKeep"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
	repositories {
		google {
			mavenContent {
				includeGroupAndSubgroups("androidx")
				includeGroupAndSubgroups("com.android")
				includeGroupAndSubgroups("com.google")
			}
		}
		mavenCentral()
		gradlePluginPortal()
	}
}

dependencyResolutionManagement {
	repositories {
		google {
			mavenContent {
				includeGroupAndSubgroups("androidx")
				includeGroupAndSubgroups("com.android")
				includeGroupAndSubgroups("com.google")
			}
		}
		mavenCentral()
		maven {
			name = "Central Portal Snapshots"
			url = uri("https://central.sonatype.com/repository/maven-snapshots/")
			content {
				includeGroupAndSubgroups("dev.zt64.subsonic")
			}
		}
		maven {
			name = "Jitpack"
			url = uri("https://jitpack.io")
			content {
				includeGroupAndSubgroups("com.github.Nightdavisao")
			}
		}
	}
}

include(":app")
include(":shared")
include(":iosCompose")
project(":iosCompose").projectDir = file("iosApp/compose")
includeBuild("vendor/subsonic-kotlin")
