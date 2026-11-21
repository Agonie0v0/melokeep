package io.github.agonie0v0.melokeep.ios

import io.github.agonie0v0.melokeep.shared.MeloSourceLabelRegistry
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.agonie0v0.melokeep.shared.MeloServerConfig
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import io.github.agonie0v0.melokeep.shared.MeloTrack
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.encodeURLQueryComponent
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.jetbrains.skia.Image as SkiaImage
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 底部常驻悬浮播放舱与 4-Tab 悬浮导航坞的安全避让高度。
 */
val MeloBottomChromeClearance = 152.dp

/**
 * MeloKeep Studio 高定主题预设标识与元数据（与 Web `themes.css` / Android 同步，共 9 套）。
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
		val isDark: Boolean,
	)

	val darkPresets = listOf(
		Descriptor(
			id = OBSIDIAN,
			title = "曜石录音室",
			subtitle = "深空炭黑 · 电光冰蓝",
			previewBackground = Color(0xFF090B10),
			previewContainer = Color(0xFF12151E),
			previewAccent = Color(0xFF60A5FA),
			isDark = true,
		),
		Descriptor(
			id = VINYL_GOLD,
			title = "流金黑胶",
			subtitle = "纯黑母带 · 香槟流金",
			previewBackground = Color(0xFF060608),
			previewContainer = Color(0xFF121216),
			previewAccent = Color(0xFFEAB308),
			isDark = true,
		),
		Descriptor(
			id = MIDNIGHT_AURORA,
			title = "午夜极光",
			subtitle = "深海玄青 · 翡翠极光",
			previewBackground = Color(0xFF061114),
			previewContainer = Color(0xFF0C1B1F),
			previewAccent = Color(0xFF10B981),
			isDark = true,
		),
		Descriptor(
			id = CYBER_AMETHYST,
			title = "暗紫霓虹",
			subtitle = "深空紫夜 · 霓虹紫晶",
			previewBackground = Color(0xFF0B0816),
			previewContainer = Color(0xFF141026),
			previewAccent = Color(0xFFA78BFA),
			isDark = true,
		),
	)

	val lightPresets = listOf(
		Descriptor(
			id = PURE_LIGHT,
			title = "纯净晨雾",
			subtitle = "雾白画布 · 晴空湛蓝",
			previewBackground = Color(0xFFF4F6FA),
			previewContainer = Color(0xFFFFFFFF),
			previewAccent = Color(0xFF2563EB),
			isDark = false,
		),
		Descriptor(
			id = WARM_VINYL,
			title = "暖调黑胶纸",
			subtitle = "米白信纸 · 焦糖琥珀",
			previewBackground = Color(0xFFF5EFE6),
			previewContainer = Color(0xFFFFFCF7),
			previewAccent = Color(0xFFC2591B),
			isDark = false,
		),
		Descriptor(
			id = NORDIC_FROST,
			title = "北欧冰川",
			subtitle = "冷冽冰蓝 · 极地湖青",
			previewBackground = Color(0xFFEDF4F7),
			previewContainer = Color(0xFFFAFDFF),
			previewAccent = Color(0xFF0284C7),
			isDark = false,
		),
		Descriptor(
			id = ROSE_MIST,
			title = "雾樱漫音",
			subtitle = "樱粉晨霜 · 绯红玫瑰",
			previewBackground = Color(0xFFF9F1F4),
			previewContainer = Color(0xFFFFFBFC),
			previewAccent = Color(0xFFDB2777),
			isDark = false,
		),
	)

	val dynamicPresets = listOf(
		Descriptor(
			id = DYNAMIC,
			title = "动态壁纸色",
			subtitle = "跟随系统与封面取色",
			previewBackground = Color(0xFF151922),
			previewContainer = Color(0xFF232A3B),
			previewAccent = Color(0xFF38BDF8),
			isDark = true,
		),
	)

	val all = darkPresets + lightPresets + dynamicPresets

	fun isLightPreset(id: String): Boolean =
		id == PURE_LIGHT || id == WARM_VINYL || id == NORDIC_FROST || id == ROSE_MIST
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
	inversePrimary = Color(0xFF2563EB),
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
	inversePrimary = Color(0xFFA16207),
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
	inversePrimary = Color(0xFF93C5FD),
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
	inversePrimary = Color(0xFF047857),
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
	inversePrimary = Color(0xFF6D28D9),
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
	inversePrimary = Color(0xFFF59E6C),
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
	inversePrimary = Color(0xFF7DD3FC),
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
	inversePrimary = Color(0xFFF9A8D4),
)

internal fun resolveStaticStudioColorScheme(
	id: String,
	amoled: Boolean = false,
): ColorScheme? {
	val base = when (id) {
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
			surfaceContainerLow = Color(0xFF060608),
		)
	} else {
		base
	}
}

private val ColorScheme.isMeloDark: Boolean
	get() = background.luminance() < 0.5f

val ColorScheme.meloGoldText: Color
	get() = if (isMeloDark) Color(0xFFFACC15) else Color(0xFFB45309)

val ColorScheme.meloGoldContainer: Color
	get() = if (isMeloDark) Color(0xFFEAB308).copy(alpha = 0.16f) else Color(0xFFFEF3C7)

val ColorScheme.meloGoldBorder: Color
	get() = if (isMeloDark) Color(0xFFFACC15).copy(alpha = 0.34f) else Color(0xFFD97706).copy(alpha = 0.28f)

val ColorScheme.meloGlassSurface: Color
	get() = if (isMeloDark) {
		surfaceContainer.copy(alpha = 0.95f)
	} else {
		surfaceContainer.copy(alpha = 0.97f)
	}

val ColorScheme.meloSubtleBorder: Color
	get() = if (isMeloDark) {
		Color.White.copy(alpha = 0.09f)
	} else {
		Color(0xFF0F172A).copy(alpha = 0.08f)
	}

val ColorScheme.meloPositive: Color
	get() = if (isMeloDark) Color(0xFF4ADE80) else Color(0xFF15803D)

fun TextStyle.tabularNums(): TextStyle = copy(fontFeatureSettings = "tnum")

private val DefaultSeedColor = Color(0xFF5B7CFA)

private val seedColorCache = mutableMapOf<String, Color>()

@Composable
fun rememberExtractedSeedColor(
	coverUrl: String?,
	subsonicManager: IosSubsonicManager,
): Color {
	var targetColor by remember { mutableStateOf(DefaultSeedColor) }

	LaunchedEffect(coverUrl) {
		if (coverUrl.isNullOrBlank()) {
			targetColor = DefaultSeedColor
			return@LaunchedEffect
		}
		seedColorCache[coverUrl]?.let {
			targetColor = it
			return@LaunchedEffect
		}
		val extracted = withContext(Dispatchers.Default) {
			extractDominantColor(coverUrl, subsonicManager)
		}
		if (extracted != null) {
			if (seedColorCache.size >= 48) seedColorCache.clear()
			seedColorCache[coverUrl] = extracted
			targetColor = extracted
		} else {
			targetColor = DefaultSeedColor
		}
	}

	val animated by animateColorAsState(
		targetValue = targetColor,
		animationSpec = tween(durationMillis = 650),
	)
	return animated
}

private suspend fun extractDominantColor(
	url: String,
	subsonicManager: IosSubsonicManager,
): Color? {
	val bytes = runCatching {
		subsonicManager.rawHttpClient.prepareGet(url).execute { resp ->
			if (!resp.status.isSuccess()) return@execute null
			val channel = resp.bodyAsChannel()
			val chunks = mutableListOf<ByteArray>()
			val buf = ByteArray(16 * 1024)
			var total = 0
			while (!channel.isClosedForRead && total < 2 * 1024 * 1024) {
				val read = channel.readAvailable(buf, 0, buf.size)
				if (read <= 0) break
				chunks.add(buf.copyOf(read))
				total += read
			}
			val out = ByteArray(total)
			var pos = 0
			chunks.forEach { c ->
				c.copyInto(out, pos)
				pos += c.size
			}
			out
		}
	}.getOrNull() ?: return null
	if (bytes.isEmpty()) return null

	return runCatching {
		val skiaImage = SkiaImage.makeFromEncoded(bytes)
		val bitmap = skiaImage.toComposeImageBitmap()
		val w = bitmap.width
		val h = bitmap.height
		if (w <= 0 || h <= 0) return@runCatching null
		val pixels = IntArray(w * h)
		bitmap.readPixels(pixels)

		val step = max(1, (w * h) / 400)
		var bestScore = -1f
		var bestColor: Color? = null
		var sumR = 0f
		var sumG = 0f
		var sumB = 0f
		var count = 0

		var i = 0
		while (i < pixels.size) {
			val argb = pixels[i]
			val a = (argb ushr 24) and 0xFF
			if (a > 180) {
				val r = ((argb ushr 16) and 0xFF) / 255f
				val g = ((argb ushr 8) and 0xFF) / 255f
				val b = (argb and 0xFF) / 255f
				sumR += r
				sumG += g
				sumB += b
				count++

				val maxC = max(r, max(g, b))
				val minC = min(r, min(g, b))
				val delta = maxC - minC
				val luma = 0.299f * r + 0.587f * g + 0.114f * b
				val sat = if (maxC > 0f) delta / maxC else 0f
				val lumaWeight = if (luma in 0.14f..0.86f) 1.0f else 0.35f
				val score = sat * 2.2f + lumaWeight * 0.6f
				if (score > bestScore && delta > 0.08f) {
					bestScore = score
					bestColor = Color(r, g, b, 1f)
				}
			}
			i += step
		}
		bestColor ?: if (count > 0) {
			Color(sumR / count, sumG / count, sumB / count, 1f)
		} else {
			null
		}
	}.getOrNull()
}

fun buildMeloColorScheme(
	seed: Color,
	isDark: Boolean,
	amoled: Boolean,
): ColorScheme {
	return if (isDark) {
		val bg = if (amoled) Color(0xFF000000) else lerp(Color(0xFF0E1016), seed, 0.08f)
		val surface = if (amoled) Color(0xFF06070A) else lerp(Color(0xFF13161E), seed, 0.10f)
		val surfaceContainer = if (amoled) Color(0xFF12141A) else lerp(Color(0xFF1C202B), seed, 0.14f)
		val surfaceContainerHigh = if (amoled) Color(0xFF1B1E27) else lerp(Color(0xFF252A38), seed, 0.16f)
		val primary = lerp(seed, Color.White, 0.36f)
		val primaryContainer = lerp(seed, Color.Black, 0.52f)
		val secondary = lerp(seed, Color(0xFFAEC6FF), 0.45f)
		val secondaryContainer = lerp(secondary, Color.Black, 0.60f)
		val tertiary = lerp(seed, Color(0xFFF3B8FF), 0.38f)

		darkColorScheme(
			primary = primary,
			onPrimary = Color(0xFF0B1020),
			primaryContainer = primaryContainer,
			onPrimaryContainer = lerp(seed, Color.White, 0.82f),
			secondary = secondary,
			onSecondary = Color(0xFF101524),
			secondaryContainer = secondaryContainer,
			onSecondaryContainer = Color(0xFFE6ECFF),
			tertiary = tertiary,
			onTertiary = Color(0xFF1F0C24),
			background = bg,
			onBackground = Color(0xFFF2F4FA),
			surface = surface,
			onSurface = Color(0xFFF2F4FA),
			surfaceVariant = surfaceContainerHigh,
			onSurfaceVariant = Color(0xFFB8C0D4),
			surfaceContainerLowest = bg,
			surfaceContainerLow = surface,
			surfaceContainer = surfaceContainer,
			surfaceContainerHigh = surfaceContainerHigh,
			surfaceContainerHighest = lerp(surfaceContainerHigh, Color.White, 0.06f),
			outline = Color(0xFF3E465C),
			outlineVariant = Color(0xFF272D3C),
			error = Color(0xFFFF6B6B),
			onError = Color(0xFF2B0004),
		)
	} else {
		val bg = lerp(Color(0xFFF7F8FC), seed, 0.04f)
		val surface = lerp(Color(0xFFFFFFFF), seed, 0.03f)
		val surfaceContainer = lerp(Color(0xFFEEF1F8), seed, 0.07f)
		val surfaceContainerHigh = lerp(Color(0xFFE4E9F4), seed, 0.10f)
		val primary = lerp(seed, Color(0xFF102050), 0.18f)
		val primaryContainer = lerp(seed, Color.White, 0.78f)
		val secondary = lerp(seed, Color(0xFF3D5AFE), 0.35f)
		val secondaryContainer = lerp(secondary, Color.White, 0.82f)

		lightColorScheme(
			primary = primary,
			onPrimary = Color.White,
			primaryContainer = primaryContainer,
			onPrimaryContainer = lerp(seed, Color.Black, 0.65f),
			secondary = secondary,
			onSecondary = Color.White,
			secondaryContainer = secondaryContainer,
			onSecondaryContainer = Color(0xFF101C3D),
			background = bg,
			onBackground = Color(0xFF141824),
			surface = surface,
			onSurface = Color(0xFF141824),
			surfaceVariant = surfaceContainerHigh,
			onSurfaceVariant = Color(0xFF535B70),
			surfaceContainerLowest = Color.White,
			surfaceContainerLow = lerp(Color.White, seed, 0.04f),
			surfaceContainer = surfaceContainer,
			surfaceContainerHigh = surfaceContainerHigh,
			surfaceContainerHighest = lerp(surfaceContainerHigh, Color.Black, 0.05f),
			outline = Color(0xFF9AA3BA),
			outlineVariant = Color(0xFFD5DBE8),
			error = Color(0xFFD93838),
			onError = Color.White,
		)
	}
}

@Composable
fun MeloKeepIosTheme(
	studioPreset: String = MeloStudioPresets.OBSIDIAN,
	themeMode: IosThemeMode = IosThemeMode.SYSTEM,
	amoledBlack: Boolean = false,
	seedColor: Color,
	forceDark: Boolean = false,
	content: @Composable () -> Unit,
) {
	val systemDark = isSystemInDarkTheme()
	val isDark = forceDark || when (themeMode) {
		IosThemeMode.SYSTEM -> systemDark
		IosThemeMode.DARK -> true
		IosThemeMode.LIGHT -> false
	}
	val scheme = remember(studioPreset, seedColor, isDark, amoledBlack, forceDark) {
		if (studioPreset == MeloStudioPresets.DYNAMIC) {
			buildMeloColorScheme(seedColor, isDark, amoledBlack)
		} else {
			val staticScheme = resolveStaticStudioColorScheme(studioPreset, amoledBlack)
			when {
				staticScheme == null -> resolveStaticStudioColorScheme(MeloStudioPresets.OBSIDIAN, amoledBlack)
					?: ObsidianStudioColorScheme
				forceDark && !staticScheme.isMeloDark ->
					resolveStaticStudioColorScheme(MeloStudioPresets.OBSIDIAN, amoledBlack)
						?: ObsidianStudioColorScheme
				else -> staticScheme
			}
		}
	}
	MaterialTheme(
		colorScheme = scheme,
		typography = Typography(),
		content = content,
	)
}

/**
 * 轻量双色径向渐变声场背景（无逐帧位图模糊死循环，丝滑省电）。
 */
@Composable
fun BlendBackground(
	coverUrl: String?,
	seedColor: Color,
	isPaused: Boolean,
	modifier: Modifier = Modifier,
) {
	val baseBg = MaterialTheme.colorScheme.background
	val isLight = baseBg.luminance() >= 0.5f

	val primaryAccent by animateColorAsState(
		targetValue = seedColor,
		animationSpec = tween(durationMillis = 700),
		label = "BlendPrimaryAccent",
	)
	val secondaryAccent by animateColorAsState(
		targetValue = lerp(seedColor, if (isLight) Color(0xFF2563EB) else Color(0xFF60A5FA), 0.35f),
		animationSpec = tween(durationMillis = 700),
		label = "BlendSecondaryAccent",
	)
	val pulseTarget by animateFloatAsState(
		targetValue = if (isLight) {
			if (isPaused) 0.12f else 0.20f
		} else {
			if (isPaused) 0.20f else 0.36f
		},
		animationSpec = tween(durationMillis = 600),
		label = "BlendPulseOpacity",
	)
	val driftTransition = rememberInfiniteTransition(label = "BlendDrift")
	val drift by driftTransition.animateFloat(
		initialValue = 0f,
		targetValue = 1f,
		animationSpec = infiniteRepeatable(
			animation = tween(durationMillis = 12000),
			repeatMode = RepeatMode.Reverse,
		),
		label = "BlendDriftFactor",
	)

	val hasCover = !coverUrl.isNullOrBlank()
	Canvas(
		modifier = modifier
			.fillMaxSize()
			.background(baseBg),
	) {
		val w = size.width
		val h = size.height
		val topCenter = Offset(
			x = w * (0.28f + 0.14f * drift),
			y = h * (0.20f + 0.06f * drift),
		)
		val bottomCenter = Offset(
			x = w * (0.78f - 0.16f * drift),
			y = h * (0.64f - 0.08f * drift),
		)
		drawRect(
			brush = Brush.radialGradient(
				colors = listOf(
					primaryAccent.copy(alpha = if (hasCover) pulseTarget else pulseTarget * 0.72f),
					Color.Transparent,
				),
				center = topCenter,
				radius = max(w, h) * 0.85f,
			),
		)
		drawRect(
			brush = Brush.radialGradient(
				colors = listOf(
					secondaryAccent.copy(alpha = pulseTarget * 0.65f),
					Color.Transparent,
				),
				center = bottomCenter,
				radius = max(w, h) * 0.78f,
			),
		)

		val maskColors = if (isLight) {
			listOf(
				baseBg.copy(alpha = 0.08f),
				baseBg.copy(alpha = 0.18f),
				baseBg.copy(alpha = 0.32f),
			)
		} else {
			listOf(
				baseBg.copy(alpha = 0.12f),
				baseBg.copy(alpha = 0.28f),
				baseBg.copy(alpha = 0.48f),
			)
		}
		drawRect(
			brush = Brush.verticalGradient(
				colors = maskColors,
			),
		)
	}
}

@Composable
fun CoverArtBox(
	coverUrl: String?,
	modifier: Modifier = Modifier,
	size: Dp? = null,
	cornerRadius: Dp = 12.dp,
	contentDescription: String? = null,
) {
	val sizedModifier = if (size != null) modifier.size(size) else modifier
	Surface(
		modifier = sizedModifier,
		shape = RoundedCornerShape(cornerRadius),
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		border = BorderStroke(
			width = IosHairlineWidth,
			color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
		),
	) {
		Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
			Icon(
				imageVector = Icons.Rounded.MusicNote,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
				modifier = Modifier.size(if (size != null && size < 64.dp) 22.dp else 36.dp),
			)
			if (!coverUrl.isNullOrBlank()) {
				AsyncImage(
					model = coverUrl,
					contentDescription = contentDescription,
					contentScale = ContentScale.Crop,
					modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(cornerRadius)),
				)
			}
		}
	}
}

private const val LASTFM_DEFAULT_ARTIST_STAR_HASH = "2a96cbd8b46e442fc41c2b86b821562f"

private val IOS_ARTIST_PLACEHOLDER_NAMES = setOf(
	"未知歌手",
	"unknown artist",
	"various artists",
	"群星",
)

internal fun sanitizeIosArtistImageUrl(raw: String?): String? {
	val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
	if (trimmed.contains(LASTFM_DEFAULT_ARTIST_STAR_HASH, ignoreCase = true)) return null
	return trimmed
}

val LocalIosMeloSettingsStore = staticCompositionLocalOf<MeloSettingsStore?> { null }

private data class IosArtistAvatarCandidate(
	val url: String,
	val cacheKey: String?,
	val attachMeloHeaders: Boolean,
)

/**
 * 统一歌手头像组件：
 * 1. 优先尝试 Navidrome `artistImageUrl` / `coverUrl`（当 [preferMeloAvatar] 为 true 时优先 MeloKeep `/api/artists/avatar`）；
 * 2. 主源缺失、为 Last.fm 默认占位或 Coil 加载失败时，若配置了 MeloKeep 服务则自动切换 `/api/artists/avatar?name=...`（附 `Authorization` / `media_token`）；
 * 3. 无可用图片时显示主题色首字或人像圆形占位，杜绝大面积空白圈。
 */
@Composable
fun IosArtistAvatar(
	artistName: String,
	coverUrl: String? = null,
	artistImageUrl: String? = null,
	size: Dp = 44.dp,
	modifier: Modifier = Modifier,
	shape: Shape = CircleShape,
	border: BorderStroke? = BorderStroke(
		width = IosHairlineWidth,
		color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
	),
	contentDescription: String? = artistName,
	preferMeloAvatar: Boolean = false,
	settingsStore: MeloSettingsStore? = LocalIosMeloSettingsStore.current,
) {
	val emptyConfigFlow = remember { MutableStateFlow(MeloServerConfig()) }
	val meloConfig by (settingsStore?.config ?: emptyConfigFlow).collectAsState()
	val platformContext = LocalPlatformContext.current
	val trimmedName = remember(artistName) { artistName.trim() }

	val candidates = remember(trimmedName, coverUrl, artistImageUrl, preferMeloAvatar, meloConfig) {
		val cleanBase = meloConfig.baseUrl.trim().trimEnd('/')
		val meloReady = meloConfig.isAuthenticated ||
			(meloConfig.isConfigured && meloConfig.mediaToken.isNotBlank())

		fun buildPrimaryCandidate(raw: String?, prefix: String): IosArtistAvatarCandidate? {
			val sanitized = sanitizeIosArtistImageUrl(raw) ?: return null
			val isRelativeApi = sanitized.startsWith("/api/", ignoreCase = true) ||
				sanitized.startsWith("/downloads/", ignoreCase = true) ||
				sanitized.startsWith("api/", ignoreCase = true) ||
				sanitized.startsWith("downloads/", ignoreCase = true)
			val isOwnMelo = isRelativeApi ||
				(cleanBase.isNotEmpty() && sanitized.startsWith(cleanBase, ignoreCase = true))
			val resolved = if (isOwnMelo) {
				if (!meloReady) return null
				iosStudioResolveMediaUrl(sanitized, meloConfig) ?: return null
			} else {
				sanitized
			}
			val cacheKey = if (isOwnMelo) {
				"$prefix:${resolved.substringBefore("?media_token=").substringBefore("&media_token=")}"
			} else {
				null
			}
			return IosArtistAvatarCandidate(
				url = resolved,
				cacheKey = cacheKey,
				attachMeloHeaders = isOwnMelo,
			)
		}

		val externalCandidate = buildPrimaryCandidate(artistImageUrl, "artist_img")
		val coverCandidate = buildPrimaryCandidate(coverUrl, "melo_cover")

		val meloAvatarCandidate = if (
			meloReady &&
			cleanBase.isNotEmpty() &&
			trimmedName.isNotEmpty() &&
			trimmedName.lowercase() !in IOS_ARTIST_PLACEHOLDER_NAMES
		) {
			val avatarPath = "$cleanBase/api/artists/avatar?name=${trimmedName.encodeURLQueryComponent()}"
			val resolved = iosStudioResolveMediaUrl(avatarPath, meloConfig) ?: avatarPath
			IosArtistAvatarCandidate(
				url = resolved,
				cacheKey = "melo_artist_avatar:${trimmedName.lowercase()}",
				attachMeloHeaders = true,
			)
		} else {
			null
		}

		val ordered = if (preferMeloAvatar) {
			listOfNotNull(externalCandidate, meloAvatarCandidate, coverCandidate)
		} else {
			listOfNotNull(externalCandidate, coverCandidate, meloAvatarCandidate)
		}
		ordered.distinctBy { it.url }
	}

	var candidateIndex by remember(candidates) { mutableIntStateOf(0) }
	var allFailed by remember(candidates) { mutableStateOf(candidates.isEmpty()) }
	var imageLoaded by remember(candidates) { mutableStateOf(false) }

	val activeCandidate = if (!allFailed) candidates.getOrNull(candidateIndex) else null
	val request = remember(activeCandidate, meloConfig.bearerToken, platformContext) {
		activeCandidate?.let { candidate ->
			val headersBuilder = NetworkHeaders.Builder()
			var hasHeaders = false
			if (candidate.attachMeloHeaders) {
				headersBuilder.add("X-MeloKeep-Request", "1")
				if (meloConfig.bearerToken.isNotBlank()) {
					headersBuilder.add("Authorization", "Bearer ${meloConfig.bearerToken}")
				}
				hasHeaders = true
			}
			ImageRequest.Builder(platformContext)
				.data(candidate.url)
				.apply {
					candidate.cacheKey?.let { key ->
						memoryCacheKey(key)
						diskCacheKey(key)
					}
					if (hasHeaders) {
						httpHeaders(headersBuilder.build())
					}
				}
				.diskCachePolicy(CachePolicy.ENABLED)
				.memoryCachePolicy(CachePolicy.ENABLED)
				.crossfade(120)
				.build()
		}
	}

	val paletteIndex = remember(trimmedName) {
		(trimmedName.lowercase().hashCode() and 0x7fffffff) % 4
	}
	val (fallbackBgColor, fallbackFgColor) = when (paletteIndex) {
		0 -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
		1 -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
		2 -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
		else -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.primary
	}
	val initialGlyph = remember(trimmedName) {
		trimmedName.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString()
	}

	Surface(
		modifier = modifier.size(size),
		shape = shape,
		color = fallbackBgColor,
		border = border,
	) {
		Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
			if (!imageLoaded) {
				if (initialGlyph != null) {
					Text(
						text = initialGlyph,
						style = MaterialTheme.typography.titleMedium.copy(
							fontSize = (size.value * 0.42f).coerceIn(11f, 50f).sp,
							fontWeight = FontWeight.Bold,
						),
						color = fallbackFgColor,
						maxLines = 1,
					)
				} else {
					Icon(
						imageVector = Icons.Rounded.Person,
						contentDescription = null,
						tint = fallbackFgColor,
						modifier = Modifier.size((size * 0.50f).coerceIn(12.dp, 54.dp)),
					)
				}
			}
			if (request != null) {
				AsyncImage(
					model = request,
					contentDescription = contentDescription,
					contentScale = ContentScale.Crop,
					onSuccess = { imageLoaded = true },
					onError = {
						imageLoaded = false
						if (candidateIndex + 1 < candidates.size) {
							candidateIndex += 1
						} else {
							allFailed = true
						}
					},
					modifier = Modifier.fillMaxSize().clip(shape),
				)
			}
		}
	}
}

fun segmentedShapeFor(index: Int, total: Int): Shape {
	return when {
		total <= 1 -> RoundedCornerShape(14.dp)
		index == 0 -> RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
		index == total - 1 -> RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 14.dp, bottomEnd = 14.dp)
		else -> RoundedCornerShape(4.dp)
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NavicSegmentedRow(
	title: String,
	subtitle: String? = null,
	shape: Shape = RoundedCornerShape(14.dp),
	selected: Boolean = false,
	onClick: () -> Unit,
	onLongClick: (() -> Unit)? = null,
	leading: @Composable (() -> Unit)? = null,
	trailing: @Composable (() -> Unit)? = null,
	modifier: Modifier = Modifier,
) {
	val container = if (selected) {
		MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f)
	} else {
		MaterialTheme.colorScheme.surfaceContainer
	}
	val contentColor = if (selected) {
		MaterialTheme.colorScheme.onPrimaryContainer
	} else {
		MaterialTheme.colorScheme.onSurface
	}
	Surface(
		modifier = modifier
			.fillMaxWidth()
			.clip(shape)
			.combinedClickable(
				onClick = onClick,
				onLongClick = onLongClick,
			),
		shape = shape,
		color = container,
		contentColor = contentColor,
		border = BorderStroke(
			width = IosHairlineWidth,
			color = if (selected) {
				MaterialTheme.colorScheme.primary.copy(alpha = 0.40f)
			} else {
				MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
			},
		),
	) {
		Row(
			modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			if (leading != null) {
				leading()
				Spacer(Modifier.width(12.dp))
			}
			Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Text(
					text = title,
					style = MaterialTheme.typography.bodyLarge,
					fontWeight = FontWeight.Medium,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				if (!subtitle.isNullOrBlank()) {
					Text(
						text = subtitle,
						style = MaterialTheme.typography.bodySmall,
						color = if (selected) {
							contentColor.copy(alpha = 0.85f)
						} else {
							MaterialTheme.colorScheme.onSurfaceVariant
						},
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
			if (trailing != null) {
				Spacer(Modifier.width(8.dp))
				trailing()
			}
		}
	}
}

/**
 * 在线连接状态胶囊（0.5.dp 发丝边框）。
 */
@Composable
fun IosConnectionStatusPill(
	isConnected: Boolean,
	label: String = if (isConnected) "在线" else "未连接",
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val dotColor = if (isConnected) {
		MaterialTheme.colorScheme.meloPositive
	} else {
		MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)
	}
	Surface(
		modifier = modifier
			.clip(CircleShape)
			.clickable(onClick = onClick),
		shape = CircleShape,
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = BorderStroke(IosHairlineWidth, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
	) {
		Row(
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(5.dp),
		) {
			Box(
				modifier = Modifier
					.size(6.dp)
					.background(dotColor, CircleShape),
			)
			Text(
				text = label,
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

/**
 * 音质微章（0.5.dp 发丝描边）。
 */
@Composable
fun IosQualityGoldBadge(
	label: String,
	isLossless: Boolean,
	modifier: Modifier = Modifier,
) {
	val bg = if (isLossless) {
		MaterialTheme.colorScheme.meloGoldContainer
	} else {
		MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
	}
	val fg = if (isLossless) {
		MaterialTheme.colorScheme.meloGoldText
	} else {
		MaterialTheme.colorScheme.onSecondaryContainer
	}
	val border = if (isLossless) {
		BorderStroke(IosHairlineWidth, MaterialTheme.colorScheme.meloGoldBorder)
	} else {
		BorderStroke(IosHairlineWidth, MaterialTheme.colorScheme.outline.copy(alpha = 0.30f))
	}
	Surface(
		modifier = modifier,
		shape = RoundedCornerShape(6.dp),
		color = bg,
		border = border,
	) {
		Text(
			text = label,
			style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
			color = fg,
			maxLines = 1,
			modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
		)
	}
}

/**
 * 未配置 MeloKeep 服务时的极简空状态引导卡。
 */
@Composable
fun IosStudioUnconfiguredCard(
	title: String,
	description: String,
	actionLabel: String = "前往配置",
	onAction: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Box(
		modifier = modifier
			.fillMaxSize()
			.padding(horizontal = 24.dp),
		contentAlignment = Alignment.Center,
	) {
		Surface(
			modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth(),
			color = MaterialTheme.colorScheme.surfaceContainer,
			border = BorderStroke(IosHairlineWidth, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
			shape = RoundedCornerShape(14.dp),
		) {
			Column(
				modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.spacedBy(10.dp),
			) {
				Box(
					modifier = Modifier
						.size(44.dp)
						.background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
					contentAlignment = Alignment.Center,
				) {
					Icon(
						imageVector = Icons.Rounded.Dns,
						contentDescription = null,
						tint = MaterialTheme.colorScheme.primary,
						modifier = Modifier.size(22.dp),
					)
				}
				Text(
					text = title,
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.SemiBold,
					textAlign = TextAlign.Center,
				)
				Text(
					text = description,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					textAlign = TextAlign.Center,
				)
				Spacer(Modifier.height(4.dp))
				Button(
					onClick = onAction,
					shape = RoundedCornerShape(12.dp),
				) {
					Text(actionLabel)
				}
			}
		}
	}
}

fun extractIosTrackQualityBadge(track: MeloTrack): Pair<String, Boolean>? {
	val hasHiRes = track.qualities.any { q ->
		q.label.contains("hi-res", ignoreCase = true) ||
			q.label.contains("24", ignoreCase = true) ||
			q.label.contains("母带") ||
			q.label.contains("臻品") ||
			q.codec.contains("24", ignoreCase = true)
	}
	if (hasHiRes) return "Hi-Res" to true
	if (track.hasLossless) {
		val codec = track.codecLabel?.uppercase()?.takeIf { it in setOf("FLAC", "WAV", "ALAC", "DSD", "APE") }
		return (codec ?: "FLAC") to true
	}
	val bitrate = track.bestBitrate ?: 0
	if (bitrate >= 320) return "HQ ${bitrate}k" to false
	if (bitrate > 0) return "${bitrate}k" to false
	return null
}

fun formatIosStatusLabel(status: String): String = when (status.trim().lowercase()) {
	"queued" -> "排队中"
	"resolving" -> "解析中"
	"downloading" -> "下载中"
	"verifying" -> "校验中"
	"publishing" -> "写入曲库中"
	"processing" -> "处理中"
	"pause_requested" -> "正在暂停"
	"paused" -> "已暂停"
	"cancel_requested" -> "正在取消"
	"cancelled" -> "已取消"
	"retry_wait" -> "等待重试"
	"complete", "completed" -> "已完成"
	"failed", "error" -> "失败"
	"needs_attention" -> "需人工处理"
	"scanning" -> "扫描刮削中"
	"identifying" -> "识别匹配中"
	"tagging" -> "写入标签中"
	"organizing" -> "整理目录中"
	"indexing" -> "更新索引中"
	"restoring" -> "正在回滚"
	"restored" -> "已回滚"
	"preview_ready", "ready" -> "预览就绪"
	"needs_review" -> "待确认候选"
	"applying" -> "正在归档"
	"partial" -> "部分完成"
	"skipped" -> "已跳过"
	"running" -> "运行中"
	"waiting_tasks" -> "等待后台任务"
	"stopping" -> "正在停止"
	"stopped", "interrupted" -> "已停止"
	"needs_decision" -> "待确认"
	"idle" -> "空闲"
	"pending" -> "等待中"
	"blocked" -> "已阻塞"
	"exists" -> "曲库已存在"
	"searching" -> "搜索中"
	else -> status.ifBlank { "未知" }
}

fun translateIosTagKey(key: String): String = when (key.trim().lowercase()) {
	"title" -> "标题"
	"artist" -> "歌手"
	"album" -> "专辑"
	"album_artist", "albumartist" -> "专辑艺人"
	"track", "track_number" -> "音轨号"
	"disc", "disc_number" -> "碟号"
	"date", "year" -> "发行日期"
	"genre" -> "流派"
	"composer" -> "作曲"
	"lyricist" -> "作词"
	"comment" -> "备注"
	"lyrics" -> "歌词"
	"isrc" -> "ISRC"
	"publisher", "label" -> "唱片公司"
	"copyright" -> "版权"
	"bpm" -> "BPM"
	"grouping" -> "分组"
	else -> key
}

fun translateIosSourceName(source: String): String = translateIosSourceName(source, emptyMap())

fun translateIosSourceName(source: String, sourceLabels: Map<String, String>): String {
	MeloSourceLabelRegistry.resolve(source, sourceLabels)?.let { return it }
	return when (source.trim().lowercase()) {
		"audius", "audiusmusicclient" -> "Audius"
		"fma", "fmamusicclient" -> "FMA"
		"jamendo", "jamendomusicclient" -> "Jamendo"
		"ccmixter", "ccmixtermusicclient" -> "ccMixter"
		"opengameart", "opengameartmusicclient" -> "OpenGameArt"
		"wikimedia", "wikimediacommons", "wikimediacommonsmusicclient" -> "Wikimedia Commons"
		"itunes", "itunesmusicclient" -> "iTunes"
		"musicbrainz" -> "MusicBrainz"
		"acoustid" -> "AcoustID"
		"lrclib" -> "LRCLIB"
		"local" -> "本地曲库"
		else -> source.ifBlank { "未知音源" }
	}
}

fun formatIosJsonTimestamp(element: JsonElement?): String {
	val primitive = element as? JsonPrimitive ?: return ""
	primitive.longOrNull?.let { return formatIosEpochSeconds(it) }
	val raw = primitive.contentOrNull?.trim().orEmpty()
	if (raw.isEmpty()) return ""
	raw.toLongOrNull()?.let { return formatIosEpochSeconds(it) }
	return raw.replace('T', ' ').take(16)
}

/**
 * 纯 Kotlin（无 JVM 平台依赖）公历转换：将 Unix Epoch 秒或毫秒格式化为 "MM-dd HH:mm" (+08:00)。
 */
private fun formatIosEpochSeconds(epochSeconds: Long): String {
	if (epochSeconds <= 0L) return ""
	val rawSec = if (epochSeconds > 10_000_000_000L) epochSeconds / 1000L else epochSeconds
	val localSec = rawSec + 8L * 3600L
	val days = localSec.floorDiv(86400L)
	val remSec = localSec.mod(86400L)
	val hour = (remSec / 3600L).toInt()
	val minute = ((remSec % 3600L) / 60L).toInt()

	val z = days + 719468L
	val era = (if (z >= 0L) z else z - 146096L) / 146097L
	val doe = z - era * 146097L
	val yoe = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L
	val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
	val mp = (5L * doy + 2L) / 153L
	val day = (doy - (153L * mp + 2L) / 5L + 1L).toInt()
	val month = (mp + (if (mp < 10L) 3L else -9L)).toInt()

	return "${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')} " +
		"${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
}

fun formatDurationMs(ms: Long): String {
	val totalSec = (ms / 1000L).coerceAtLeast(0L)
	val hours = totalSec / 3600L
	val minutes = (totalSec % 3600L) / 60L
	val seconds = totalSec % 60L
	return if (hours > 0L) {
		"${hours}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
	} else {
		"${minutes}:${seconds.toString().padStart(2, '0')}"
	}
}

fun formatBytesHuman(bytes: Long): String {
	val safe = bytes.coerceAtLeast(0L)
	return when {
		safe < 1024L -> "$safe B"
		safe < 1024L * 1024L -> "${(safe * 10L / 1024L) / 10.0} KB"
		safe < 1024L * 1024L * 1024L -> {
			val mb10 = (safe * 10.0 / (1024.0 * 1024.0)).roundToInt()
			"${mb10 / 10.0} MB"
		}
		else -> {
			val gb100 = (safe * 100.0 / (1024.0 * 1024.0 * 1024.0)).roundToInt()
			"${gb100 / 100.0} GB"
		}
	}
}
