package paige.navic.ui.icons.outlined

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import paige.navic.ui.icons.Icons

val Icons.Outlined.Checklist: ImageVector by lazy {
	ImageVector.Builder("Outlined.Checklist", 24.dp, 24.dp, 24f, 24f).apply {
		path(stroke = SolidColor(Color(0xFFE3E3E3)), strokeLineWidth = 2f,
			strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
			moveTo(3f, 6f); lineTo(5f, 8f); lineTo(9f, 4f)
			moveTo(12f, 6f); lineTo(21f, 6f)
			moveTo(3f, 16f); lineTo(5f, 18f); lineTo(9f, 14f)
			moveTo(12f, 16f); lineTo(21f, 16f)
		}
	}.build()
}
