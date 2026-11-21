/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.theme

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import org.koin.compose.koinInject
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.model.settings.AnimationStyle
import paige.navic.domain.model.settings.Theme
import paige.navic.domain.model.settings.ThemeMode
import paige.navic.util.PlatformSupport

@Composable
fun NavicTheme(
	colorScheme: ColorScheme? = null,
	content: @Composable () -> Unit
) {
	val preferenceManager = koinInject<PreferenceManager>()
	val meloSettingsStore = koinInject<MeloSettingsStore>()
	val studioPreset by meloSettingsStore.studioThemePreset.collectAsStateWithLifecycle()

	val dynamicOrLegacyScheme = when (studioPreset) {
		MeloStudioPresets.DYNAMIC -> {
			val fallbackTheme = if (PlatformSupport.SUPPORTS_DYNAMIC_THEME) {
				Theme.Dynamic
			} else {
				preferenceManager.theme
			}
			fallbackTheme.colorScheme()
		}
		else -> resolveStaticStudioColorScheme(
			preset = studioPreset,
			amoled = preferenceManager.amoled
		) ?: preferenceManager.theme.colorScheme()
	}

	val effectiveScheme = colorScheme ?: dynamicOrLegacyScheme

	val motionScheme = remember(preferenceManager.animationStyle) {
		when (preferenceManager.animationStyle) {
			AnimationStyle.Expressive -> MotionScheme.expressive()
			AnimationStyle.Standard -> MotionScheme.standard()
		}
	}
	val inDarkTheme = isSystemInDarkTheme() || !PlatformSupport.SUPPORTS_DARK_THEME
	val isDark = if (colorScheme != null || studioPreset != MeloStudioPresets.DYNAMIC) {
		effectiveScheme.background.luminance() < 0.5f
	} else {
		when (preferenceManager.themeMode) {
			ThemeMode.System -> inDarkTheme
			ThemeMode.Dark -> true
			ThemeMode.Light -> false
		}
	}

	val activity = LocalActivity.current as? ComponentActivity
	val transparentScrim = Color.Transparent.toArgb()

	SideEffect {
		val barStyle = SystemBarStyle.auto(
			darkScrim = transparentScrim,
			lightScrim = transparentScrim,
			detectDarkMode = { isDark }
		)
		activity?.enableEdgeToEdge(
			statusBarStyle = barStyle,
			navigationBarStyle = barStyle
		)
	}

	MaterialExpressiveTheme(
		colorScheme = effectiveScheme,
		motionScheme = motionScheme,
		typography = typography(),
		content = content
	)
}
