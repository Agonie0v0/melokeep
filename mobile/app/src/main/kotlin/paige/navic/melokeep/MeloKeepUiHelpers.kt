package paige.navic.melokeep

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.agonie0v0.melokeep.shared.MeloTrack
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalSnackBarState
import paige.navic.ui.navigation.Screen
import paige.navic.ui.theme.defaultFont
import paige.navic.ui.theme.meloGoldBorder
import paige.navic.ui.theme.meloGoldContainer
import paige.navic.ui.theme.meloGoldText
import paige.navic.ui.theme.positive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 为 MeloKeep 原生页提供与 Navic 一致的 Material Symbols 矢量图标补充。
 */
internal object MeloIcons {
	val Photo: ImageVector by lazy {
		buildSymbol("MeloIcons.Photo") {
			moveTo(200f, 840f)
			quadToRelative(-33f, 0f, -56.5f, -23.5f)
			reflectiveQuadTo(120f, 760f)
			verticalLineToRelative(-560f)
			quadToRelative(0f, -33f, 23.5f, -56.5f)
			reflectiveQuadTo(200f, 120f)
			horizontalLineToRelative(560f)
			quadToRelative(33f, 0f, 56.5f, 23.5f)
			reflectiveQuadTo(840f, 200f)
			verticalLineToRelative(560f)
			quadToRelative(0f, 33f, -23.5f, 56.5f)
			reflectiveQuadTo(760f, 840f)
			close()
			moveTo(200f, 760f)
			horizontalLineToRelative(560f)
			verticalLineToRelative(-560f)
			horizontalLineTo(200f)
			close()
			moveTo(240f, 680f)
			horizontalLineToRelative(480f)
			lineTo(570f, 480f)
			lineTo(450f, 640f)
			lineToRelative(-90f, -120f)
			close()
		}
	}

	val Stop: ImageVector by lazy {
		buildSymbol("MeloIcons.Stop") {
			moveTo(240f, 720f)
			verticalLineToRelative(-480f)
			horizontalLineToRelative(480f)
			verticalLineToRelative(480f)
			close()
		}
	}

	val Pause: ImageVector by lazy {
		buildSymbol("MeloIcons.Pause") {
			moveTo(520f, 760f)
			verticalLineToRelative(-560f)
			horizontalLineToRelative(160f)
			verticalLineToRelative(560f)
			close()
			moveTo(280f, 760f)
			verticalLineToRelative(-560f)
			horizontalLineToRelative(160f)
			verticalLineToRelative(560f)
			close()
		}
	}

	val PlayArrow: ImageVector by lazy {
		buildSymbol("MeloIcons.PlayArrow") {
			moveTo(320f, 760f)
			verticalLineToRelative(-560f)
			lineToRelative(440f, 280f)
			close()
		}
	}

	val Folder: ImageVector by lazy {
		buildSymbol("MeloIcons.Folder") {
			moveTo(160f, 800f)
			quadToRelative(-33f, 0f, -56.5f, -23.5f)
			reflectiveQuadTo(80f, 720f)
			verticalLineToRelative(-480f)
			quadToRelative(0f, -33f, 23.5f, -56.5f)
			reflectiveQuadTo(160f, 160f)
			horizontalLineToRelative(240f)
			lineToRelative(80f, 80f)
			horizontalLineToRelative(320f)
			quadToRelative(33f, 0f, 56.5f, 23.5f)
			reflectiveQuadTo(880f, 320f)
			verticalLineToRelative(400f)
			quadToRelative(0f, 33f, -23.5f, 56.5f)
			reflectiveQuadTo(800f, 800f)
			close()
			moveTo(160f, 720f)
			horizontalLineToRelative(640f)
			verticalLineToRelative(-400f)
			horizontalLineTo(447f)
			lineToRelative(-80f, -80f)
			horizontalLineTo(160f)
			close()
		}
	}

	val Sparkles: ImageVector by lazy {
		buildSymbol("MeloIcons.Sparkles") {
			moveTo(760f, 320f)
			lineToRelative(-50f, -110f)
			lineToRelative(-110f, -50f)
			lineToRelative(110f, -50f)
			lineToRelative(50f, -110f)
			lineToRelative(50f, 110f)
			lineToRelative(110f, 50f)
			lineToRelative(-110f, 50f)
			close()
			moveTo(760f, 960f)
			lineToRelative(-50f, -110f)
			lineToRelative(-110f, -50f)
			lineToRelative(110f, -50f)
			lineToRelative(50f, -110f)
			lineToRelative(50f, 110f)
			lineToRelative(110f, 50f)
			lineToRelative(-110f, 50f)
			close()
			moveTo(360f, 760f)
			lineToRelative(-90f, -190f)
			lineToRelative(-190f, -90f)
			lineToRelative(190f, -90f)
			lineToRelative(90f, -190f)
			lineToRelative(90f, 190f)
			lineToRelative(190f, 90f)
			lineToRelative(-190f, 90f)
			close()
		}
	}

	val Cloud: ImageVector by lazy {
		buildSymbol("MeloIcons.Cloud") {
			moveTo(260f, 800f)
			quadToRelative(-91f, 0f, -155.5f, -63f)
			reflectiveQuadTo(40f, 583f)
			quadToRelative(0f, -78f, 47f, -139f)
			reflectiveQuadToRelative(123f, -78f)
			quadToRelative(25f, -92f, 100f, -149f)
			reflectiveQuadToRelative(170f, -57f)
			quadToRelative(117f, 0f, 198.5f, 81.5f)
			reflectiveQuadTo(760f, 440f)
			quadToRelative(69f, 8f, 114.5f, 59.5f)
			reflectiveQuadTo(920f, 620f)
			quadToRelative(0f, 75f, -52.5f, 127.5f)
			reflectiveQuadTo(740f, 800f)
			close()
			moveTo(260f, 720f)
			horizontalLineToRelative(480f)
			quadToRelative(42f, 0f, 71f, -29f)
			reflectiveQuadToRelative(29f, -71f)
			quadToRelative(0f, -42f, -29f, -71f)
			reflectiveQuadToRelative(-71f, -29f)
			horizontalLineToRelative(-60f)
			verticalLineToRelative(-80f)
			quadToRelative(0f, -83f, -58.5f, -141.5f)
			reflectiveQuadTo(480f, 240f)
			quadToRelative(-83f, 0f, -141.5f, 58.5f)
			reflectiveQuadTo(280f, 440f)
			horizontalLineToRelative(-20f)
			quadToRelative(-58f, 0f, -99f, 41f)
			reflectiveQuadToRelative(-41f, 99f)
			quadToRelative(0f, 58f, 41f, 99f)
			reflectiveQuadToRelative(99f, 41f)
			close()
		}
	}

	val Build: ImageVector by lazy {
		buildSymbol("MeloIcons.Build") {
			moveTo(756f, 840f)
			lineTo(537f, 621f)
			lineToRelative(84f, -84f)
			lineToRelative(219f, 219f)
			quadToRelative(12f, 12f, 12f, 28f)
			reflectiveQuadToRelative(-12f, 28f)
			lineToRelative(-28f, 28f)
			quadToRelative(-12f, 12f, -28f, 12f)
			reflectiveQuadToRelative(-28f, -12f)
			close()
			moveTo(148f, 828f)
			lineToRelative(-28f, -28f)
			quadToRelative(-12f, -12f, -12f, -28f)
			reflectiveQuadToRelative(12f, -28f)
			lineToRelative(273f, -273f)
			quadToRelative(-20f, -51f, -10.5f, -109.5f)
			reflectiveQuadTo(426f, 256f)
			quadToRelative(45f, -45f, 108f, -57.5f)
			reflectiveQuadTo(656f, 214f)
			lineTo(552f, 318f)
			lineToRelative(90f, 90f)
			lineToRelative(104f, -104f)
			quadToRelative(28f, 62f, 15.5f, 125f)
			reflectiveQuadTo(704f, 534f)
			quadToRelative(-46f, 46f, -105f, 55f)
			reflectiveQuadTo(489f, 578f)
			lineTo(204f, 828f)
			quadToRelative(-12f, 12f, -28f, 12f)
			reflectiveQuadToRelative(-28f, -12f)
			close()
		}
	}

	val OpenInNew: ImageVector by lazy {
		buildSymbol("MeloIcons.OpenInNew") {
			moveTo(200f, 840f)
			quadToRelative(-33f, 0f, -56.5f, -23.5f)
			reflectiveQuadTo(120f, 760f)
			verticalLineToRelative(-560f)
			quadToRelative(0f, -33f, 23.5f, -56.5f)
			reflectiveQuadTo(200f, 120f)
			horizontalLineToRelative(280f)
			verticalLineToRelative(80f)
			horizontalLineTo(200f)
			verticalLineToRelative(560f)
			horizontalLineToRelative(560f)
			verticalLineToRelative(-280f)
			horizontalLineToRelative(80f)
			verticalLineToRelative(280f)
			quadToRelative(0f, 33f, -23.5f, 56.5f)
			reflectiveQuadTo(760f, 840f)
			close()
			moveTo(388f, 628f)
			lineToRelative(-56f, -56f)
			lineToRelative(372f, -372f)
			horizontalLineTo(560f)
			verticalLineToRelative(-80f)
			horizontalLineToRelative(280f)
			verticalLineToRelative(280f)
			horizontalLineToRelative(-80f)
			verticalLineToRelative(-144f)
			close()
		}
	}

	val Visibility: ImageVector by lazy {
		buildSymbol("MeloIcons.Visibility") {
			moveTo(480f, 640f)
			quadToRelative(75f, 0f, 127.5f, -52.5f)
			reflectiveQuadTo(660f, 460f)
			quadToRelative(0f, -75f, -52.5f, -127.5f)
			reflectiveQuadTo(480f, 280f)
			quadToRelative(-75f, 0f, -127.5f, 52.5f)
			reflectiveQuadTo(300f, 460f)
			quadToRelative(0f, 75f, 52.5f, 127.5f)
			reflectiveQuadTo(480f, 640f)
			close()
			moveTo(480f, 568f)
			quadToRelative(-45f, 0f, -76.5f, -31.5f)
			reflectiveQuadTo(372f, 460f)
			quadToRelative(0f, -45f, 31.5f, -76.5f)
			reflectiveQuadTo(480f, 352f)
			quadToRelative(45f, 0f, 76.5f, 31.5f)
			reflectiveQuadTo(588f, 460f)
			quadToRelative(0f, 45f, -31.5f, 76.5f)
			reflectiveQuadTo(480f, 568f)
			close()
			moveTo(480f, 760f)
			quadToRelative(-146f, 0f, -266f, -81.5f)
			reflectiveQuadTo(40f, 460f)
			quadToRelative(54f, -137f, 174f, -218.5f)
			reflectiveQuadTo(480f, 160f)
			quadToRelative(146f, 0f, 266f, 81.5f)
			reflectiveQuadTo(920f, 460f)
			quadToRelative(-54f, 137f, -174f, 218.5f)
			reflectiveQuadTo(480f, 760f)
			close()
		}
	}

	val VisibilityOff: ImageVector by lazy {
		buildSymbol("MeloIcons.VisibilityOff") {
			moveTo(644f, 532f)
			lineToRelative(-58f, -58f)
			quadToRelative(9f, -47f, -27f, -88f)
			reflectiveQuadToRelative(-93f, -32f)
			lineToRelative(-58f, -58f)
			quadToRelative(17f, -8f, 34.5f, -12f)
			reflectiveQuadToRelative(37.5f, -4f)
			quadToRelative(75f, 0f, 127.5f, 52.5f)
			reflectiveQuadTo(660f, 460f)
			quadToRelative(0f, 20f, -4f, 37.5f)
			reflectiveQuadTo(644f, 532f)
			close()
			moveTo(772f, 658f)
			lineToRelative(-58f, -56f)
			quadToRelative(38f, -29f, 67.5f, -63.5f)
			reflectiveQuadTo(832f, 460f)
			quadToRelative(-50f, -101f, -143.5f, -160.5f)
			reflectiveQuadTo(480f, 240f)
			quadToRelative(-29f, 0f, -57f, 4f)
			reflectiveQuadToRelative(-55f, 12f)
			lineToRelative(-62f, -62f)
			quadToRelative(41f, -17f, 84f, -25.5f)
			reflectiveQuadToRelative(90f, -8.5f)
			quadToRelative(151f, 0f, 269f, 83.5f)
			reflectiveQuadTo(920f, 460f)
			quadToRelative(-23f, 59f, -60.5f, 109.3f)
			reflectiveQuadTo(772f, 658f)
			close()
			moveTo(792f, 904f)
			lineTo(624f, 738f)
			quadToRelative(-35f, 11f, -70.5f, 16.5f)
			reflectiveQuadTo(480f, 760f)
			quadToRelative(-151f, 0f, -269f, -83.5f)
			reflectiveQuadTo(40f, 460f)
			quadToRelative(21f, -53f, 53f, -98.5f)
			reflectiveQuadToRelative(73f, -81.5f)
			lineTo(56f, 168f)
			lineToRelative(56f, -56f)
			lineToRelative(736f, 736f)
			close()
		}
	}

	val SelectAll: ImageVector by lazy {
		buildSymbol("MeloIcons.SelectAll") {
			moveTo(280f, 680f)
			verticalLineToRelative(-400f)
			horizontalLineToRelative(400f)
			verticalLineToRelative(400f)
			close()
			moveTo(360f, 600f)
			horizontalLineToRelative(240f)
			verticalLineToRelative(-240f)
			horizontalLineTo(360f)
			close()
			moveTo(120f, 360f)
			verticalLineToRelative(-80f)
			horizontalLineToRelative(80f)
			verticalLineToRelative(80f)
			close()
			moveTo(120f, 520f)
			verticalLineToRelative(-80f)
			horizontalLineToRelative(80f)
			verticalLineToRelative(80f)
			close()
			moveTo(120f, 680f)
			verticalLineToRelative(-80f)
			horizontalLineToRelative(80f)
			verticalLineToRelative(80f)
			close()
			moveTo(120f, 840f)
			verticalLineToRelative(-80f)
			horizontalLineToRelative(80f)
			verticalLineToRelative(80f)
			close()
			moveTo(120f, 200f)
			verticalLineToRelative(-80f)
			horizontalLineToRelative(80f)
			verticalLineToRelative(80f)
			close()
			moveTo(280f, 200f)
			verticalLineToRelative(-80f)
			horizontalLineToRelative(80f)
			verticalLineToRelative(80f)
			close()
			moveTo(440f, 200f)
			verticalLineToRelative(-80f)
			horizontalLineToRelative(80f)
			verticalLineToRelative(80f)
			close()
			moveTo(600f, 200f)
			verticalLineToRelative(-80f)
			horizontalLineToRelative(80f)
			verticalLineToRelative(80f)
			close()
			moveTo(760f, 200f)
			verticalLineToRelative(-80f)
			horizontalLineToRelative(80f)
			verticalLineToRelative(80f)
			close()
			moveTo(760f, 360f)
			verticalLineToRelative(-80f)
			horizontalLineToRelative(80f)
			verticalLineToRelative(80f)
			close()
			moveTo(760f, 520f)
			verticalLineToRelative(-80f)
			horizontalLineToRelative(80f)
			verticalLineToRelative(80f)
			close()
			moveTo(760f, 680f)
			verticalLineToRelative(-80f)
			horizontalLineToRelative(80f)
			verticalLineToRelative(80f)
			close()
			moveTo(760f, 840f)
			verticalLineToRelative(-80f)
			horizontalLineToRelative(80f)
			verticalLineToRelative(80f)
			close()
			moveTo(280f, 840f)
			verticalLineToRelative(-80f)
			horizontalLineToRelative(80f)
			verticalLineToRelative(80f)
			close()
			moveTo(440f, 840f)
			verticalLineToRelative(-80f)
			horizontalLineToRelative(80f)
			verticalLineToRelative(80f)
			close()
			moveTo(600f, 840f)
			verticalLineToRelative(-80f)
			horizontalLineToRelative(80f)
			verticalLineToRelative(80f)
			close()
		}
	}

	val Bolt: ImageVector by lazy {
		buildSymbol("MeloIcons.Bolt") {
			moveTo(320f, 880f)
			lineToRelative(40f, -280f)
			horizontalLineTo(160f)
			lineToRelative(360f, -520f)
			horizontalLineToRelative(80f)
			lineToRelative(-40f, 320f)
			horizontalLineToRelative(240f)
			lineTo(400f, 880f)
			close()
		}
	}

	val CleaningServices: ImageVector by lazy {
		buildSymbol("MeloIcons.CleaningServices") {
			moveTo(160f, 880f)
			quadToRelative(-17f, 0f, -28.5f, -11.5f)
			reflectiveQuadTo(120f, 840f)
			verticalLineToRelative(-200f)
			quadToRelative(0f, -50f, 35f, -85f)
			reflectiveQuadToRelative(85f, -35f)
			horizontalLineToRelative(120f)
			verticalLineToRelative(-320f)
			quadToRelative(0f, -50f, 35f, -85f)
			reflectiveQuadToRelative(85f, -35f)
			quadToRelative(50f, 0f, 85f, 35f)
			reflectiveQuadToRelative(35f, 85f)
			verticalLineToRelative(320f)
			horizontalLineToRelative(120f)
			quadToRelative(50f, 0f, 85f, 35f)
			reflectiveQuadToRelative(35f, 85f)
			verticalLineToRelative(200f)
			quadToRelative(0f, 17f, -11.5f, 28.5f)
			reflectiveQuadTo(800f, 880f)
			close()
			moveTo(440f, 520f)
			horizontalLineToRelative(80f)
			verticalLineToRelative(-320f)
			quadToRelative(0f, -17f, -11.5f, -28.5f)
			reflectiveQuadTo(480f, 160f)
			quadToRelative(-17f, 0f, -28.5f, 11.5f)
			reflectiveQuadTo(440f, 200f)
			close()
			moveTo(200f, 800f)
			horizontalLineToRelative(120f)
			verticalLineToRelative(-120f)
			horizontalLineToRelative(80f)
			verticalLineToRelative(120f)
			horizontalLineToRelative(160f)
			verticalLineToRelative(-120f)
			horizontalLineToRelative(80f)
			verticalLineToRelative(120f)
			horizontalLineToRelative(120f)
			verticalLineToRelative(-160f)
			quadToRelative(0f, -17f, -11.5f, -28.5f)
			reflectiveQuadTo(720f, 600f)
			horizontalLineTo(240f)
			quadToRelative(-17f, 0f, -28.5f, 11.5f)
			reflectiveQuadTo(200f, 640f)
			close()
		}
	}

	val Restore: ImageVector by lazy {
		buildSymbol("MeloIcons.Restore") {
			moveTo(480f, 840f)
			quadToRelative(-138f, 0f, -240.5f, -91.5f)
			reflectiveQuadTo(122f, 520f)
			horizontalLineToRelative(82f)
			quadToRelative(14f, 104f, 92.5f, 172f)
			reflectiveQuadTo(480f, 760f)
			quadToRelative(117f, 0f, 198.5f, -81.5f)
			reflectiveQuadTo(760f, 480f)
			quadToRelative(0f, -117f, -81.5f, -198.5f)
			reflectiveQuadTo(480f, 200f)
			quadToRelative(-69f, 0f, -129f, 32f)
			reflectiveQuadToRelative(-101f, 88f)
			horizontalLineToRelative(110f)
			verticalLineToRelative(80f)
			horizontalLineTo(120f)
			verticalLineToRelative(-240f)
			horizontalLineToRelative(80f)
			verticalLineToRelative(94f)
			quadToRelative(51f, -64f, 124.5f, -99f)
			reflectiveQuadTo(480f, 120f)
			quadToRelative(75f, 0f, 140.5f, 28.5f)
			reflectiveQuadToRelative(114f, 77f)
			quadToRelative(48.5f, 48.5f, 77f, 114f)
			reflectiveQuadTo(840f, 480f)
			quadToRelative(0f, 75f, -28.5f, 140.5f)
			reflectiveQuadToRelative(-77f, 114f)
			quadToRelative(-48.5f, 48.5f, -114f, 77f)
			reflectiveQuadTo(480f, 840f)
			close()
			moveTo(592f, 648f)
			lineTo(440f, 496f)
			verticalLineToRelative(-216f)
			horizontalLineToRelative(80f)
			verticalLineToRelative(184f)
			lineToRelative(128f, 128f)
			close()
		}
	}

	val PhoneAndroid: ImageVector by lazy {
		buildSymbol("MeloIcons.PhoneAndroid") {
			moveTo(320f, 880f)
			quadToRelative(-33f, 0f, -56.5f, -23.5f)
			reflectiveQuadTo(240f, 800f)
			verticalLineToRelative(-640f)
			quadToRelative(0f, -33f, 23.5f, -56.5f)
			reflectiveQuadTo(320f, 80f)
			horizontalLineToRelative(320f)
			quadToRelative(33f, 0f, 56.5f, 23.5f)
			reflectiveQuadTo(720f, 160f)
			verticalLineToRelative(640f)
			quadToRelative(0f, 33f, -23.5f, 56.5f)
			reflectiveQuadTo(640f, 880f)
			close()
			moveTo(320f, 720f)
			horizontalLineToRelative(320f)
			verticalLineToRelative(-480f)
			horizontalLineTo(320f)
			close()
			moveTo(480f, 820f)
			quadToRelative(17f, 0f, 28.5f, -11.5f)
			reflectiveQuadTo(520f, 780f)
			quadToRelative(0f, -17f, -11.5f, -28.5f)
			reflectiveQuadTo(480f, 740f)
			quadToRelative(-17f, 0f, -28.5f, 11.5f)
			reflectiveQuadTo(440f, 780f)
			quadToRelative(0f, 17f, 11.5f, 28.5f)
			reflectiveQuadTo(480f, 820f)
			close()
		}
	}

	private inline fun buildSymbol(
		name: String,
		block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit
	): ImageVector = ImageVector.Builder(
		name = name,
		defaultWidth = 24.dp,
		defaultHeight = 24.dp,
		viewportWidth = 960f,
		viewportHeight = 960f
	).apply {
		path(fill = SolidColor(Color(0xFFE3E3E3)), pathBuilder = block)
	}.build()
}

@Composable
internal fun rememberOpenMeloSettings(onOpenSettings: (() -> Unit)?): () -> Unit {
	val backStack = LocalNavStack.current
	return onOpenSettings ?: {
		if (backStack.lastOrNull() != Screen.Settings.Root) {
			backStack.add(Screen.Settings.Root)
		}
	}
}

@Composable
internal fun MeloFeedbackObserver(
	errorMessage: String?,
	noticeMessage: String?,
	onClearError: () -> Unit,
	onClearNotice: () -> Unit
) {
	val snackBarState = LocalSnackBarState.current
	LaunchedEffect(errorMessage) {
		val msg = errorMessage?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
		snackBarState.currentSnackbarData?.dismiss()
		snackBarState.showSnackbar(msg)
		onClearError()
	}
	LaunchedEffect(noticeMessage) {
		val msg = noticeMessage?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
		snackBarState.currentSnackbarData?.dismiss()
		snackBarState.showSnackbar(msg)
		onClearNotice()
	}
}

@Composable
internal fun MeloUnconfiguredCard(
	title: String,
	description: String,
	onOpenSettings: () -> Unit,
	contentPadding: PaddingValues = PaddingValues(0.dp),
	modifier: Modifier = Modifier
) {
	Box(
		modifier = modifier
			.fillMaxSize()
			.padding(contentPadding)
			.padding(horizontal = 24.dp),
		contentAlignment = Alignment.Center
	) {
		MeloGroupedCard(
			modifier = Modifier.widthIn(max = 380.dp)
		) {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 22.dp, vertical = 24.dp),
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.spacedBy(10.dp)
			) {
				Icon(
					imageVector = MeloIcons.Cloud,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
					modifier = Modifier.size(30.dp)
				)
				Text(
					text = title,
					style = MaterialTheme.typography.titleMedium.copy(
						fontFamily = defaultFont(grade = 100),
						fontWeight = FontWeight.SemiBold
					),
					textAlign = TextAlign.Center
				)
				if (description.isNotBlank()) {
					Text(
						text = description,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						textAlign = TextAlign.Center
					)
				}
				Spacer(Modifier.height(4.dp))
				Button(
					onClick = onOpenSettings,
					shape = CircleShape
				) {
					Text("前往配置")
				}
			}
		}
	}
}

/**
 * 顶栏右侧精致在线连接状态胶囊。
 */
@Composable
internal fun MeloConnectionStatusPill(
	isConnected: Boolean,
	label: String = if (isConnected) "在线" else "未连接",
	onClick: () -> Unit,
	modifier: Modifier = Modifier
) {
	val dotColor = if (isConnected) {
		MaterialTheme.colorScheme.positive
	} else {
		MaterialTheme.colorScheme.error
	}
	Surface(
		modifier = modifier
			.clip(CircleShape)
			.clickable(onClick = onClick),
		shape = CircleShape,
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = BorderStroke(
			MeloHairlineWidth,
			MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
		)
	) {
		Row(
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(5.dp)
		) {
			Box(
				modifier = Modifier
					.size(6.dp)
					.background(dotColor, CircleShape)
			)
			Text(
				text = label,
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
		}
	}
}

/**
 * 音质微章（用于 Hi-Res / FLAC 无损与 HQ 区分）。
 */
@Composable
internal fun MeloQualityGoldBadge(
	label: String,
	isLossless: Boolean,
	modifier: Modifier = Modifier
) {
	val bg = if (isLossless) {
		MaterialTheme.colorScheme.meloGoldContainer.copy(alpha = 0.72f)
	} else {
		MaterialTheme.colorScheme.surfaceContainerHigh
	}
	val fg = if (isLossless) {
		MaterialTheme.colorScheme.meloGoldText
	} else {
		MaterialTheme.colorScheme.onSurfaceVariant
	}
	val border = if (isLossless) {
		BorderStroke(MeloHairlineWidth, MaterialTheme.colorScheme.meloGoldBorder.copy(alpha = 0.55f))
	} else {
		BorderStroke(MeloHairlineWidth, MaterialTheme.colorScheme.outline.copy(alpha = 0.30f))
	}
	Surface(
		modifier = modifier,
		shape = RoundedCornerShape(5.dp),
		color = bg,
		border = border
	) {
		Text(
			text = label,
			style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
			color = fg,
			maxLines = 1,
			modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
		)
	}
}

/**
 * 提取易读的音质微章标签及是否无损判定。
 */
internal fun extractTrackQualityBadge(track: MeloTrack): Pair<String, Boolean>? {
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

internal fun extractQualityBadgeFromLabel(
	qualityLabel: String,
	track: MeloTrack? = null
): Pair<String, Boolean>? {
	val lower = qualityLabel.lowercase()
	val isHiRes = "hi-res" in lower || "24bit" in lower || "母带" in lower || "臻品" in lower
	val isLossless = isHiRes ||
		"flac" in lower ||
		"wav" in lower ||
		"alac" in lower ||
		"ape" in lower ||
		"无损" in lower ||
		(track?.hasLossless == true)
	val label = qualityLabel.trim().ifBlank {
		track?.let { extractTrackQualityBadge(it)?.first }.orEmpty()
	}
	if (label.isBlank()) return null
	return label to isLossless
}

internal fun formatMeloStatusLabel(status: String): String = when (status.trim().lowercase()) {
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

internal fun formatEpochTimestamp(epochSeconds: Long): String {
	if (epochSeconds <= 0L) return ""
	val millis = if (epochSeconds < 10_000_000_000L) epochSeconds * 1000L else epochSeconds
	return runCatching {
		SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
	}.getOrDefault("")
}

internal fun formatMeloJsonTimestamp(element: JsonElement?): String {
	val primitive = element as? JsonPrimitive ?: return ""
	primitive.longOrNull?.let { return formatEpochTimestamp(it) }
	val raw = primitive.contentOrNull?.trim().orEmpty()
	if (raw.isEmpty()) return ""
	raw.toLongOrNull()?.let { return formatEpochTimestamp(it) }
	return raw.replace('T', ' ').take(16)
}

internal fun translateMeloTagKey(key: String): String = when (key.trim().lowercase()) {
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

internal fun translateMeloSourceName(source: String): String = when (source.trim().lowercase()) {
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
