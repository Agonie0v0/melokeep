/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.component.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import paige.navic.melokeep.MeloHairlineWidth
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.Check
import paige.navic.ui.icons.outlined.Copy
import paige.navic.ui.theme.positive
import kotlin.time.Duration.Companion.seconds

@Composable
fun ErrorCodeBlock(error: Throwable) {

	@Suppress("DEPRECATION")
	val clipboard = LocalClipboardManager.current
	val haptics = LocalHapticFeedback.current
	val stackTrace = remember(error) { error.stackTraceToString() }
	var copied by remember(error) { mutableStateOf(false) }

	LaunchedEffect(copied) {
		if (copied) {
			delay(2.seconds)
			copied = false
		}
	}

	Surface(
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(12.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		border = BorderStroke(
			width = MeloHairlineWidth,
			color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
		)
	) {
		Box(modifier = Modifier.fillMaxWidth()) {
			SelectionContainer(
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(max = 260.dp)
					.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 48.dp)
					.verticalScroll(rememberScrollState())
					.horizontalScroll(rememberScrollState())
			) {
				Text(
					text = stackTrace,
					fontFamily = FontFamily.Monospace,
					fontSize = 11.sp,
					lineHeight = 15.sp,
					color = MaterialTheme.colorScheme.onSurface
				)
			}
			Surface(
				onClick = {
					haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
					clipboard.setText(AnnotatedString(stackTrace))
					copied = true
				},
				modifier = Modifier
					.align(Alignment.TopEnd)
					.padding(8.dp)
					.size(32.dp),
				shape = CircleShape,
				color = MaterialTheme.colorScheme.surfaceContainer,
				border = BorderStroke(
					width = MeloHairlineWidth,
					color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
				)
			) {
				Box(contentAlignment = Alignment.Center) {
					Icon(
						imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.Copy,
						contentDescription = if (copied) "已复制" else "复制日志",
						tint = if (copied) {
							MaterialTheme.colorScheme.positive
						} else {
							MaterialTheme.colorScheme.onSurfaceVariant
						},
						modifier = Modifier.size(16.dp)
					)
				}
			}
		}
	}
}
