/*
 * Navic / MeloKeep, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.icons.brand

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import paige.navic.ui.icons.Icons

val Icons.Brand.Navic: ImageVector
	get() {
		if (_Navic != null) {
			return _Navic!!
		}
		_Navic = ImageVector.Builder(
			name = "Brand.Navic",
			defaultWidth = 32.dp,
			defaultHeight = 32.dp,
			viewportWidth = 32f,
			viewportHeight = 32f
		).apply {
			val stroke = SolidColor(Color.White)
			val bars = listOf(
				Triple(6.5f, 14f, 18f),
				Triple(10.3f, 10f, 22f),
				Triple(14.1f, 7f, 25f),
				Triple(17.9f, 12f, 20f),
				Triple(21.7f, 9f, 23f),
				Triple(25.5f, 14f, 18f)
			)
			bars.forEach { (x, y1, y2) ->
				path(
					stroke = stroke,
					strokeLineWidth = 2.4f,
					strokeLineCap = StrokeCap.Round,
					strokeLineJoin = StrokeJoin.Round
				) {
					moveTo(x, y1)
					lineTo(x, y2)
				}
			}
		}.build()

		return _Navic!!
	}

@Suppress("ObjectPropertyName")
private var _Navic: ImageVector? = null
