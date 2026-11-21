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
 * MeloKeep Studio 高定主题预设标识（与 Web `themes.css` 一一对应，共 9 套）。
 */
object MeloStudioPresets {
	const val OBSIDIAN = "obsidian"
	const val VINYL_GOLD = "vinyl-gold"
	const val MIDNIGHT_AURORA = "midnight-aurora"
	const val CYBER_AMETHYST = "cyber-amethyst"

	const val PURE_LIGHT = "pure-light"
	const val WARM_VINYL = "warm-vinyl"
	const val NORDIC_FROST = "nordic-frost"
	const val ROSE_MIST = "rose-mist"

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

	val darkPresets = listOf(
		Descriptor(
			id = OBSIDIAN,
			title = "曜石录音室",
			subtitle = "深空炭黑 · 电光冰蓝",
			previewBackground = Color(0xFF090B10),
			previewContainer = Color(0xFF12151E),
			previewAccent = Color(0xFF60A5FA),
			isDark = true
		),
		Descriptor(
			id = VINYL_GOLD,
			title = "流金黑胶",
			subtitle = "纯黑母带 · 香槟流金",
			previewBackground = Color(0xFF060608),
			previewContainer = Color(0xFF121216),
			previewAccent = Color(0xFFEAB308),
			isDark = true
		),
		Descriptor(
			id = MIDNIGHT_AURORA,
			title = "午夜极光",
			subtitle = "深海玄青 · 翡翠极光",
			previewBackground = Color(0xFF061114),
			previewContainer = Color(0xFF0C1B1F),
			previewAccent = Color(0xFF10B981),
			isDark = true
		),
		Descriptor(
			id = CYBER_AMETHYST,
			title = "暗紫霓虹",
			subtitle = "深空紫夜 · 霓虹紫晶",
			previewBackground = Color(0xFF0B0816),
			previewContainer = Color(0xFF141026),
			previewAccent = Color(0xFFA78BFA),
			isDark = true
		)
	)

	val lightPresets = listOf(
		Descriptor(
			id = PURE_LIGHT,
			title = "纯净晨雾",
			subtitle = "雾白画布 · 晴空湛蓝",
			previewBackground = Color(0xFFF4F6FA),
			previewContainer = Color(0xFFFFFFFF),
			previewAccent = Color(0xFF2563EB),
			isDark = false
		),
		Descriptor(
			id = WARM_VINYL,
			title = "暖调黑胶纸",
			subtitle = "米白信纸 · 焦糖琥珀",
			previewBackground = Color(0xFFF5EFE6),
			previewContainer = Color(0xFFFFFCF7),
			previewAccent = Color(0xFFC2591B),
			isDark = false
		),
		Descriptor(
			id = NORDIC_FROST,
			title = "北欧冰川",
			subtitle = "冷冽冰蓝 · 极地湖青",
			previewBackground = Color(0xFFEDF4F7),
			previewContainer = Color(0xFFFAFDFF),
			previewAccent = Color(0xFF0284C7),
			isDark = false
		),
		Descriptor(
			id = ROSE_MIST,
			title = "雾樱漫音",
			subtitle = "樱粉晨霜 · 绯红玫瑰",
			previewBackground = Color(0xFFF9F1F4),
			previewContainer = Color(0xFFFFFBFC),
			previewAccent = Color(0xFFDB2777),
			isDark = false
		)
	)

	val dynamicPresets = listOf(
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

	val all = darkPresets + lightPresets + dynamicPresets

	fun isLightPreset(preset: String): Boolean =
		preset == PURE_LIGHT || preset == WARM_VINYL || preset == NORDIC_FROST || preset == ROSE_MIST
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
	surfaceVariant = Color(0xFF1A1F2D),
	onSurfaceVariant = Color(0xFF94A3B8),
	surfaceDim = Color(0xFF06070B),
	surfaceBright = Color(0xFF242A3D),
	surfaceContainerLowest = Color(0xFF06080C),
	surfaceContainerLow = Color(0xFF0E1118),
	surfaceContainer = Color(0xFF12151E),
	surfaceContainerHigh = Color(0xFF1A1F2D),
	surfaceContainerHighest = Color(0xFF242A3D),
	outline = Color(0xFF334155),
	outlineVariant = Color(0xFF232A3C),
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
	secondary = Color(0xFFFACC15),
	onSecondary = Color(0xFF241600),
	secondaryContainer = Color(0xFF282214),
	onSecondaryContainer = Color(0xFFFDE68A),
	tertiary = Color(0xFF60A5FA),
	onTertiary = Color(0xFF071A30),
	tertiaryContainer = Color(0xFF19273D),
	onTertiaryContainer = Color(0xFFDBEAFE),
	background = Color(0xFF060608),
	onBackground = Color(0xFFFAFAF9),
	surface = Color(0xFF060608),
	onSurface = Color(0xFFFAFAF9),
	surfaceVariant = Color(0xFF1C1B20),
	onSurfaceVariant = Color(0xFFA8A29E),
	surfaceDim = Color(0xFF030304),
	surfaceBright = Color(0xFF27252C),
	surfaceContainerLowest = Color(0xFF030305),
	surfaceContainerLow = Color(0xFF0C0C0F),
	surfaceContainer = Color(0xFF121216),
	surfaceContainerHigh = Color(0xFF1C1B20),
	surfaceContainerHighest = Color(0xFF27252C),
	outline = Color(0xFF44403C),
	outlineVariant = Color(0xFF292524),
	error = Color(0xFFF87171),
	onError = Color(0xFF3B0808),
	errorContainer = Color(0xFF451214),
	onErrorContainer = Color(0xFFFEE2E2),
	inverseSurface = Color(0xFFFAFAF9),
	inverseOnSurface = Color(0xFF141312),
	inversePrimary = Color(0xFFA16207)
)

val MidnightAuroraStudioColorScheme: ColorScheme = darkColorScheme(
	primary = Color(0xFF10B981),
	onPrimary = Color(0xFF032218),
	primaryContainer = Color(0xFF0D3D30),
	onPrimaryContainer = Color(0xFFA7F3D0),
	secondary = Color(0xFF34D399),
	onSecondary = Color(0xFF042419),
	secondaryContainer = Color(0xFF112E34),
	onSecondaryContainer = Color(0xFFD1FAE5),
	tertiary = Color(0xFF38BDF8),
	onTertiary = Color(0xFF05202E),
	tertiaryContainer = Color(0xFF123243),
	onTertiaryContainer = Color(0xFFBAE6FD),
	background = Color(0xFF061114),
	onBackground = Color(0xFFECFDF5),
	surface = Color(0xFF061114),
	onSurface = Color(0xFFECFDF5),
	surfaceVariant = Color(0xFF13282E),
	onSurfaceVariant = Color(0xFF86A9A2),
	surfaceDim = Color(0xFF040B0D),
	surfaceBright = Color(0xFF1B363D),
	surfaceContainerLowest = Color(0xFF040B0D),
	surfaceContainerLow = Color(0xFF09161A),
	surfaceContainer = Color(0xFF0C1B1F),
	surfaceContainerHigh = Color(0xFF13282E),
	surfaceContainerHighest = Color(0xFF1B363D),
	outline = Color(0xFF284F56),
	outlineVariant = Color(0xFF173439),
	error = Color(0xFFFB7185),
	onError = Color(0xFF3A0712),
	errorContainer = Color(0xFF4C111E),
	onErrorContainer = Color(0xFFFFE4E6),
	inverseSurface = Color(0xFFECFDF5),
	inverseOnSurface = Color(0xFF061114),
	inversePrimary = Color(0xFF047857)
)

val CyberAmethystStudioColorScheme: ColorScheme = darkColorScheme(
	primary = Color(0xFFA78BFA),
	onPrimary = Color(0xFF1B0E3D),
	primaryContainer = Color(0xFF332263),
	onPrimaryContainer = Color(0xFFEDE9FE),
	secondary = Color(0xFFC4B5FD),
	onSecondary = Color(0xFF22134A),
	secondaryContainer = Color(0xFF241D40),
	onSecondaryContainer = Color(0xFFEDE9FE),
	tertiary = Color(0xFFF472B6),
	onTertiary = Color(0xFF3D0924),
	tertiaryContainer = Color(0xFF4D1535),
	onTertiaryContainer = Color(0xFFFCE7F3),
	background = Color(0xFF0B0816),
	onBackground = Color(0xFFF5F3FF),
	surface = Color(0xFF0B0816),
	onSurface = Color(0xFFF5F3FF),
	surfaceVariant = Color(0xFF1E1836),
	onSurfaceVariant = Color(0xFFA197C2),
	surfaceDim = Color(0xFF070510),
	surfaceBright = Color(0xFF2A2249),
	surfaceContainerLowest = Color(0xFF070510),
	surfaceContainerLow = Color(0xFF100C1E),
	surfaceContainer = Color(0xFF141026),
	surfaceContainerHigh = Color(0xFF1E1836),
	surfaceContainerHighest = Color(0xFF2A2249),
	outline = Color(0xFF40366B),
	outlineVariant = Color(0xFF292246),
	error = Color(0xFFFB7185),
	onError = Color(0xFF3A0712),
	errorContainer = Color(0xFF4C111E),
	onErrorContainer = Color(0xFFFFE4E6),
	inverseSurface = Color(0xFFF5F3FF),
	inverseOnSurface = Color(0xFF0B0816),
	inversePrimary = Color(0xFF6D28D9)
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
	background = Color(0xFFF4F6FA),
	onBackground = Color(0xFF0F172A),
	surface = Color(0xFFF4F6FA),
	onSurface = Color(0xFF0F172A),
	surfaceVariant = Color(0xFFEBEFF6),
	onSurfaceVariant = Color(0xFF52637A),
	surfaceDim = Color(0xFFE4EAF3),
	surfaceBright = Color(0xFFFFFFFF),
	surfaceContainerLowest = Color(0xFFFFFFFF),
	surfaceContainerLow = Color(0xFFF9FAFD),
	surfaceContainer = Color(0xFFFFFFFF),
	surfaceContainerHigh = Color(0xFFEBEFF6),
	surfaceContainerHighest = Color(0xFFE4EAF3),
	outline = Color(0xFFC5D0E0),
	outlineVariant = Color(0xFFDBE3EE),
	error = Color(0xFFDC2626),
	onError = Color(0xFFFFFFFF),
	errorContainer = Color(0xFFFEE2E2),
	onErrorContainer = Color(0xFF7F1D1D),
	inverseSurface = Color(0xFF0F172A),
	inverseOnSurface = Color(0xFFF8FAFC),
	inversePrimary = Color(0xFF93C5FD)
)

val WarmVinylStudioColorScheme: ColorScheme = lightColorScheme(
	primary = Color(0xFFC2591B),
	onPrimary = Color(0xFFFFFFFF),
	primaryContainer = Color(0xFFFCE4D4),
	onPrimaryContainer = Color(0xFF5E2204),
	secondary = Color(0xFF8C5A3C),
	onSecondary = Color(0xFFFFFFFF),
	secondaryContainer = Color(0xFFF2E2D4),
	onSecondaryContainer = Color(0xFF3E2212),
	tertiary = Color(0xFFB45309),
	onTertiary = Color(0xFFFFFFFF),
	tertiaryContainer = Color(0xFFFEF3C7),
	onTertiaryContainer = Color(0xFF692D04),
	background = Color(0xFFF5EFE6),
	onBackground = Color(0xFF292018),
	surface = Color(0xFFF5EFE6),
	onSurface = Color(0xFF292018),
	surfaceVariant = Color(0xFFEDE4D6),
	onSurfaceVariant = Color(0xFF6E5D4F),
	surfaceDim = Color(0xFFE5D9C6),
	surfaceBright = Color(0xFFFFFCF7),
	surfaceContainerLowest = Color(0xFFFFFFFF),
	surfaceContainerLow = Color(0xFFFAF5EE),
	surfaceContainer = Color(0xFFFFFCF7),
	surfaceContainerHigh = Color(0xFFEDE4D6),
	surfaceContainerHighest = Color(0xFFE5D9C6),
	outline = Color(0xFFD2C3AF),
	outlineVariant = Color(0xFFE4D8C8),
	error = Color(0xFFC81E1E),
	onError = Color(0xFFFFFFFF),
	errorContainer = Color(0xFFFEE2E2),
	onErrorContainer = Color(0xFF7F1D1D),
	inverseSurface = Color(0xFF292018),
	inverseOnSurface = Color(0xFFFAF5EE),
	inversePrimary = Color(0xFFF59E6C)
)

val NordicFrostStudioColorScheme: ColorScheme = lightColorScheme(
	primary = Color(0xFF0284C7),
	onPrimary = Color(0xFFFFFFFF),
	primaryContainer = Color(0xFFD6EFFC),
	onPrimaryContainer = Color(0xFF074063),
	secondary = Color(0xFF2563EB),
	onSecondary = Color(0xFFFFFFFF),
	secondaryContainer = Color(0xFFDBEAFE),
	onSecondaryContainer = Color(0xFF1E3A8A),
	tertiary = Color(0xFF047857),
	onTertiary = Color(0xFFFFFFFF),
	tertiaryContainer = Color(0xFFD1FAE5),
	onTertiaryContainer = Color(0xFF064E3B),
	background = Color(0xFFEDF4F7),
	onBackground = Color(0xFF0C1E2C),
	surface = Color(0xFFEDF4F7),
	onSurface = Color(0xFF0C1E2C),
	surfaceVariant = Color(0xFFE1EDF3),
	onSurfaceVariant = Color(0xFF4B677D),
	surfaceDim = Color(0xFFD5E6EF),
	surfaceBright = Color(0xFFFAFDFF),
	surfaceContainerLowest = Color(0xFFFFFFFF),
	surfaceContainerLow = Color(0xFFF3F9FC),
	surfaceContainer = Color(0xFFFAFDFF),
	surfaceContainerHigh = Color(0xFFE1EDF3),
	surfaceContainerHighest = Color(0xFFD5E6EF),
	outline = Color(0xFFB7CFDC),
	outlineVariant = Color(0xFFCFE0EA),
	error = Color(0xFFDC2626),
	onError = Color(0xFFFFFFFF),
	errorContainer = Color(0xFFFEE2E2),
	onErrorContainer = Color(0xFF7F1D1D),
	inverseSurface = Color(0xFF0C1E2C),
	inverseOnSurface = Color(0xFFF3F9FC),
	inversePrimary = Color(0xFF7DD3FC)
)

val RoseMistStudioColorScheme: ColorScheme = lightColorScheme(
	primary = Color(0xFFDB2777),
	onPrimary = Color(0xFFFFFFFF),
	primaryContainer = Color(0xFFFCE0ED),
	onPrimaryContainer = Color(0xFF70103D),
	secondary = Color(0xFFA855F7),
	onSecondary = Color(0xFFFFFFFF),
	secondaryContainer = Color(0xFFF3E8FF),
	onSecondaryContainer = Color(0xFF4C1D95),
	tertiary = Color(0xFFC2410C),
	onTertiary = Color(0xFFFFFFFF),
	tertiaryContainer = Color(0xFFFFEDD5),
	onTertiaryContainer = Color(0xFF7C2D12),
	background = Color(0xFFF9F1F4),
	onBackground = Color(0xFF2A121E),
	surface = Color(0xFFF9F1F4),
	onSurface = Color(0xFF2A121E),
	surfaceVariant = Color(0xFFF3E4EB),
	onSurfaceVariant = Color(0xFF765164),
	surfaceDim = Color(0xFFEBD6E0),
	surfaceBright = Color(0xFFFFFBFC),
	surfaceContainerLowest = Color(0xFFFFFFFF),
	surfaceContainerLow = Color(0xFFFCF6F8),
	surfaceContainer = Color(0xFFFFFBFC),
	surfaceContainerHigh = Color(0xFFF3E4EB),
	surfaceContainerHighest = Color(0xFFEBD6E0),
	outline = Color(0xFFDCBFC9),
	outlineVariant = Color(0xFFECD7E0),
	error = Color(0xFFDC2626),
	onError = Color(0xFFFFFFFF),
	errorContainer = Color(0xFFFEE2E2),
	onErrorContainer = Color(0xFF7F1D1D),
	inverseSurface = Color(0xFF2A121E),
	inverseOnSurface = Color(0xFFFCF6F8),
	inversePrimary = Color(0xFFF9A8D4)
)

internal fun resolveStaticStudioColorScheme(
	preset: String,
	amoled: Boolean = false
): ColorScheme? {
	val base = when (preset) {
		MeloStudioPresets.OBSIDIAN -> ObsidianStudioColorScheme
		MeloStudioPresets.VINYL_GOLD -> VinylGoldStudioColorScheme
		MeloStudioPresets.MIDNIGHT_AURORA -> MidnightAuroraStudioColorScheme
		MeloStudioPresets.CYBER_AMETHYST -> CyberAmethystStudioColorScheme
		MeloStudioPresets.PURE_LIGHT -> PureLightStudioColorScheme
		MeloStudioPresets.WARM_VINYL -> WarmVinylStudioColorScheme
		MeloStudioPresets.NORDIC_FROST -> NordicFrostStudioColorScheme
		MeloStudioPresets.ROSE_MIST -> RoseMistStudioColorScheme
		else -> null
	} ?: return null

	return if (amoled && base.isMeloDark) {
		base.copy(
			background = Color.Black,
			surface = Color.Black,
			surfaceDim = Color.Black,
			surfaceContainerLowest = Color.Black,
			surfaceContainerLow = Color(0xFF060608)
		)
	} else {
		base
	}
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
