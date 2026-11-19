/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import paige.navic.R
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.model.settings.FontOption

private val defaultTypography = Typography()

@Composable
fun googleSans(
	grade: Int = 0,
	width: Float = 100f,
	round: Float = 0f
): FontFamily {
	// 约束圆角变体参数，摒弃夸张圆胖造型，保证录音室干练现代的字形轮廓
	val crispRound = round.coerceAtMost(12f)
	val font = Font(
		R.font.google_sans,
		variationSettings = FontVariation.Settings(
			FontVariation.grade(grade),
			FontVariation.width(width.coerceIn(96f, 102f)),
			FontVariation.Setting("ROND", crispRound)
		)
	)
	return remember(grade, width, crispRound) { FontFamily(font) }
}

@Composable
fun defaultFont(
	grade: Int = 0,
	width: Float = 100f,
	round: Float = 0f
): FontFamily {
	val preferenceManager = koinInject<PreferenceManager>()
	val googleSans = googleSans(grade, width, round)
	return remember(preferenceManager.font, preferenceManager.fontPath, grade, width) {
		when (preferenceManager.font) {
			FontOption.System -> FontFamily.Default
			FontOption.GoogleSans -> googleSans
			FontOption.Custom -> FontFamily.Default
		}
	}
}

@Composable
fun typography(): Typography {
	val fontFamily = defaultFont()
	return Typography(
		displayLarge = defaultTypography.displayLarge.copy(
			fontFamily = fontFamily,
			fontWeight = FontWeight.SemiBold,
			letterSpacing = (-0.5).sp
		),
		displayLargeEmphasized = defaultTypography.displayLargeEmphasized.copy(
			fontFamily = fontFamily,
			fontWeight = FontWeight.Bold,
			letterSpacing = (-0.5).sp
		),
		displayMedium = defaultTypography.displayMedium.copy(
			fontFamily = fontFamily,
			fontWeight = FontWeight.SemiBold,
			letterSpacing = (-0.3).sp
		),
		displayMediumEmphasized = defaultTypography.displayMediumEmphasized.copy(
			fontFamily = fontFamily,
			fontWeight = FontWeight.Bold,
			letterSpacing = (-0.3).sp
		),
		displaySmall = defaultTypography.displaySmall.copy(
			fontFamily = fontFamily,
			fontWeight = FontWeight.SemiBold,
			letterSpacing = (-0.2).sp
		),
		displaySmallEmphasized = defaultTypography.displaySmallEmphasized.copy(
			fontFamily = fontFamily,
			fontWeight = FontWeight.Bold,
			letterSpacing = (-0.2).sp
		),

		headlineLarge = defaultTypography.headlineLarge.copy(
			fontFamily = fontFamily,
			fontWeight = FontWeight.SemiBold,
			letterSpacing = (-0.25).sp
		),
		headlineLargeEmphasized = defaultTypography.headlineLargeEmphasized.copy(
			fontFamily = fontFamily,
			fontWeight = FontWeight.Bold,
			letterSpacing = (-0.25).sp
		),
		headlineMedium = defaultTypography.headlineMedium.copy(
			fontFamily = fontFamily,
			fontWeight = FontWeight.SemiBold,
			letterSpacing = (-0.2).sp
		),
		headlineMediumEmphasized = defaultTypography.headlineMediumEmphasized.copy(
			fontFamily = fontFamily,
			fontWeight = FontWeight.Bold,
			letterSpacing = (-0.2).sp
		),
		headlineSmall = defaultTypography.headlineSmall.copy(
			fontFamily = fontFamily,
			fontWeight = FontWeight.SemiBold,
			letterSpacing = (-0.15).sp
		),
		headlineSmallEmphasized = defaultTypography.headlineSmallEmphasized.copy(
			fontFamily = fontFamily,
			fontWeight = FontWeight.Bold,
			letterSpacing = (-0.15).sp
		),

		titleLarge = defaultTypography.titleLarge.copy(
			fontFamily = fontFamily,
			fontWeight = FontWeight.SemiBold,
			letterSpacing = (-0.15).sp
		),
		titleLargeEmphasized = defaultTypography.titleLargeEmphasized.copy(
			fontFamily = fontFamily,
			fontWeight = FontWeight.Bold,
			letterSpacing = (-0.15).sp
		),
		titleMedium = defaultTypography.titleMedium.copy(
			fontFamily = fontFamily,
			fontWeight = FontWeight.SemiBold,
			letterSpacing = 0.sp
		),
		titleMediumEmphasized = defaultTypography.titleMediumEmphasized.copy(
			fontFamily = fontFamily,
			fontWeight = FontWeight.Bold,
			letterSpacing = 0.sp
		),
		titleSmall = defaultTypography.titleSmall.copy(
			fontFamily = fontFamily,
			fontWeight = FontWeight.Medium,
			letterSpacing = 0.05.sp
		),
		titleSmallEmphasized = defaultTypography.titleSmallEmphasized.copy(
			fontFamily = fontFamily,
			fontWeight = FontWeight.SemiBold,
			letterSpacing = 0.05.sp
		),

		bodyLarge = defaultTypography.bodyLarge.copy(
			fontFamily = fontFamily,
			letterSpacing = 0.1.sp
		),
		bodyLargeEmphasized = defaultTypography.bodyLargeEmphasized.copy(
			fontFamily = fontFamily,
			fontWeight = FontWeight.Medium,
			letterSpacing = 0.1.sp
		),
		bodyMedium = defaultTypography.bodyMedium.copy(
			fontFamily = fontFamily,
			letterSpacing = 0.15.sp
		),
		bodyMediumEmphasized = defaultTypography.bodyMediumEmphasized.copy(
			fontFamily = fontFamily,
			fontWeight = FontWeight.Medium,
			letterSpacing = 0.15.sp
		),
		bodySmall = defaultTypography.bodySmall.copy(
			fontFamily = fontFamily,
			letterSpacing = 0.2.sp
		),
		bodySmallEmphasized = defaultTypography.bodySmallEmphasized.copy(
			fontFamily = fontFamily,
			fontWeight = FontWeight.Medium,
			letterSpacing = 0.2.sp
		),

		labelLarge = defaultTypography.labelLarge.copy(
			fontFamily = fontFamily,
			fontWeight = FontWeight.Medium,
			letterSpacing = 0.1.sp
		),
		labelLargeEmphasized = defaultTypography.labelLargeEmphasized.copy(
			fontFamily = fontFamily,
			fontWeight = FontWeight.SemiBold,
			letterSpacing = 0.1.sp
		),
		labelMedium = defaultTypography.labelMedium.copy(
			fontFamily = fontFamily,
			fontWeight = FontWeight.Medium,
			letterSpacing = 0.25.sp
		),
		labelMediumEmphasized = defaultTypography.labelMediumEmphasized.copy(
			fontFamily = fontFamily,
			fontWeight = FontWeight.SemiBold,
			letterSpacing = 0.25.sp
		),
		labelSmall = defaultTypography.labelSmall.copy(
			fontFamily = fontFamily,
			fontWeight = FontWeight.Medium,
			letterSpacing = 0.3.sp
		),
		labelSmallEmphasized = defaultTypography.labelSmallEmphasized.copy(
			fontFamily = fontFamily,
			fontWeight = FontWeight.SemiBold,
			letterSpacing = 0.3.sp
		)
	)
}
