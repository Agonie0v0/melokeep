package paige.navic.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * 底部常驻悬浮播放舱与 4-Tab 导航坞的安全避让高度。
 */
val MeloBottomChromeClearance = 148.dp

/**
 * MeloKeep Studio 高定主题预设标识。
 */
object MeloStudioPresets {
	const val OBSIDIAN = "obsidian"
	const val VINYL_GOLD = "vinyl-gold"
	const val PURE_LIGHT = "pure-light"
	const val DYNAMIC = "dynamic"

	data class Descriptor(
		val id: String,
		val title: String,
		val subtitle: String,
		val previewBackground: Color,
		val previewContainer: Color,
		val previewAccent: Color,
		val isDark: Boolean
	)

	val all = listOf(
		Descriptor(
			id = OBSIDIAN,
			title = "曜石录音室",
			subtitle = "深空炭黑 · 电光冰蓝",
			previewBackground = Color(0xFF090B10),
			previewContainer = Color(0xFF121620),
			previewAccent = Color(0xFF60A5FA),
			isDark = true
		),
		Descriptor(
			id = VINYL_GOLD,
			title = "流金黑胶",
			subtitle = "纯黑母带 · 香槟流金",
			previewBackground = Color(0xFF050507),
			previewContainer = Color(0xFF111115),
			previewAccent = Color(0xFFEAB308),
			isDark = true
		),
		Descriptor(
			id = PURE_LIGHT,
			title = "纯净晨雾",
			subtitle = "雾白画布 · 晴空湛蓝",
			previewBackground = Color(0xFFF3F5F9),
			previewContainer = Color(0xFFFFFFFF),
			previewAccent = Color(0xFF2563EB),
			isDark = false
		),
		Descriptor(
			id = DYNAMIC,
			title = "动态壁纸色",
			subtitle = "跟随系统与封面取色",
			previewBackground = Color(0xFF151922),
			previewContainer = Color(0xFF232A3B),
			previewAccent = Color(0xFF38BDF8),
			isDark = true
		)
	)
}

val ObsidianStudioColorScheme: ColorScheme = darkColorScheme(
	primary = Color(0xFF60A5FA),
	onPrimary = Color(0xFF06162B),
	primaryContainer = Color(0xFF1C3554),
	onPrimaryContainer = Color(0xFFDCEBFF),
	secondary = Color(0xFF93C5FD),
	onSecondary = Color(0xFF091A30),
	secondaryContainer = Color(0xFF192438),
	onSecondaryContainer = Color(0xFFDBEAFE),
	tertiary = Color(0xFFF59E0B),
	onTertiary = Color(0xFF261900),
	tertiaryContainer = Color(0xFF33260A),
	onTertiaryContainer = Color(0xFFFDE68A),
	background = Color(0xFF090B10),
	onBackground = Color(0xFFF8FAFC),
	surface = Color(0xFF090B10),
	onSurface = Color(0xFFF8FAFC),
	surfaceVariant = Color(0xFF191F2E),
	onSurfaceVariant = Color(0xFF94A3B8),
	surfaceDim = Color(0xFF06070B),
	surfaceBright = Color(0xFF22293C),
	surfaceContainerLowest = Color(0xFF06080C),
	surfaceContainerLow = Color(0xFF0E1118),
	surfaceContainer = Color(0xFF121620),
	surfaceContainerHigh = Color(0xFF191F2E),
	surfaceContainerHighest = Color(0xFF222A3D),
	outline = Color(0xFF334155),
	outlineVariant = Color(0xFF1E293B),
	error = Color(0xFFF87171),
	onError = Color(0xFF3B0808),
	errorContainer = Color(0xFF451214),
	onErrorContainer = Color(0xFFFEE2E2),
	inverseSurface = Color(0xFFF8FAFC),
	inverseOnSurface = Color(0xFF0F172A),
	inversePrimary = Color(0xFF2563EB)
)

val VinylGoldStudioColorScheme: ColorScheme = darkColorScheme(
	primary = Color(0xFFEAB308),
	onPrimary = Color(0xFF1D1500),
	primaryContainer = Color(0xFF3A2C06),
	onPrimaryContainer = Color(0xFFFEF08A),
	secondary = Color(0xFFF59E0B),
	onSecondary = Color(0xFF241600),
	secondaryContainer = Color(0xFF282214),
	onSecondaryContainer = Color(0xFFFDE68A),
	tertiary = Color(0xFF60A5FA),
	onTertiary = Color(0xFF071A30),
	tertiaryContainer = Color(0xFF19273D),
	onTertiaryContainer = Color(0xFFDBEAFE),
	background = Color(0xFF050507),
	onBackground = Color(0xFFFAFAF9),
	surface = Color(0xFF050507),
	onSurface = Color(0xFFFAFAF9),
	surfaceVariant = Color(0xFF1A191F),
	onSurfaceVariant = Color(0xFFA8A29E),
	surfaceDim = Color(0xFF030304),
	surfaceBright = Color(0xFF26242D),
	surfaceContainerLowest = Color(0xFF030305),
	surfaceContainerLow = Color(0xFF0B0B0E),
	surfaceContainer = Color(0xFF111115),
	surfaceContainerHigh = Color(0xFF1A191F),
	surfaceContainerHighest = Color(0xFF24222A),
	outline = Color(0xFF44403C),
	outlineVariant = Color(0xFF262320),
	error = Color(0xFFF87171),
	onError = Color(0xFF3B0808),
	errorContainer = Color(0xFF451214),
	onErrorContainer = Color(0xFFFEE2E2),
	inverseSurface = Color(0xFFFAFAF9),
	inverseOnSurface = Color(0xFF141312),
	inversePrimary = Color(0xFFA16207)
)

val PureLightStudioColorScheme: ColorScheme = lightColorScheme(
	primary = Color(0xFF2563EB),
	onPrimary = Color(0xFFFFFFFF),
	primaryContainer = Color(0xFFDBEAFE),
	onPrimaryContainer = Color(0xFF1E3A8A),
	secondary = Color(0xFFD97706),
	onSecondary = Color(0xFFFFFFFF),
	secondaryContainer = Color(0xFFFEF3C7),
	onSecondaryContainer = Color(0xFF78350F),
	tertiary = Color(0xFF0284C7),
	onTertiary = Color(0xFFFFFFFF),
	tertiaryContainer = Color(0xFFE0F2FE),
	onTertiaryContainer = Color(0xFF0C4A6E),
	background = Color(0xFFF3F5F9),
	onBackground = Color(0xFF0F172A),
	surface = Color(0xFFF3F5F9),
	onSurface = Color(0xFF0F172A),
	surfaceVariant = Color(0xFFEAF0F6),
	onSurfaceVariant = Color(0xFF64748B),
	surfaceDim = Color(0xFFE4E9F0),
	surfaceBright = Color(0xFFFFFFFF),
	surfaceContainerLowest = Color(0xFFFFFFFF),
	surfaceContainerLow = Color(0xFFF8FAFC),
	surfaceContainer = Color(0xFFFFFFFF),
	surfaceContainerHigh = Color(0xFFEAF0F6),
	surfaceContainerHighest = Color(0xFFDFE7F0),
	outline = Color(0xFFCBD5E1),
	outlineVariant = Color(0xFFE2E8F0),
	error = Color(0xFFDC2626),
	onError = Color(0xFFFFFFFF),
	errorContainer = Color(0xFFFEE2E2),
	onErrorContainer = Color(0xFF7F1D1D),
	inverseSurface = Color(0xFF0F172A),
	inverseOnSurface = Color(0xFFF8FAFC),
	inversePrimary = Color(0xFF93C5FD)
)

internal fun resolveStaticStudioColorScheme(preset: String): ColorScheme? = when (preset) {
	MeloStudioPresets.OBSIDIAN -> ObsidianStudioColorScheme
	MeloStudioPresets.VINYL_GOLD -> VinylGoldStudioColorScheme
	MeloStudioPresets.PURE_LIGHT -> PureLightStudioColorScheme
	else -> null
}

private val ColorScheme.isMeloDark: Boolean
	get() = background.luminance() < 0.5f

val ColorScheme.meloGoldText: Color
	@Composable
	get() = if (isMeloDark) Color(0xFFFACC15) else Color(0xFFB45309)

val ColorScheme.meloGoldContainer: Color
	@Composable
	get() = if (isMeloDark) Color(0xFFEAB308).copy(alpha = 0.16f) else Color(0xFFFEF3C7)

val ColorScheme.meloGoldBorder: Color
	@Composable
	get() = if (isMeloDark) Color(0xFFFACC15).copy(alpha = 0.34f) else Color(0xFFD97706).copy(alpha = 0.28f)

val ColorScheme.meloGlassSurface: Color
	@Composable
	get() = if (isMeloDark) {
		MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f)
	} else {
		MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f)
	}

val ColorScheme.meloSubtleBorder: Color
	@Composable
	get() = if (isMeloDark) {
		Color.White.copy(alpha = 0.09f)
	} else {
		Color(0xFF0F172A).copy(alpha = 0.08f)
	}

fun TextStyle.tabularNums(): TextStyle = copy(fontFeatureSettings = "tnum")
