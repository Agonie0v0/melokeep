/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.component.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import paige.navic.R
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.Queue
import paige.navic.ui.icons.outlined.QueuePlayNext

/**
 * `SwipeToDismissBox` but it has haptics.
 */
@Composable
fun SwipeToDismissBox(
	state: SwipeToDismissBoxState,
	backgroundContent: @Composable RowScope.() -> Unit,
	modifier: Modifier = Modifier,
	enableDismissFromStartToEnd: Boolean = true,
	enableDismissFromEndToStart: Boolean = true,
	gesturesEnabled: Boolean = true,
	onDismiss: (SwipeToDismissBoxValue) -> Unit = {},
	content: @Composable RowScope.() -> Unit,
) {
	val haptics = LocalHapticFeedback.current

	LaunchedEffect(state.targetValue) {
		if (state.targetValue != SwipeToDismissBoxValue.Settled) {
			haptics.performHapticFeedback(HapticFeedbackType.LongPress)
		}
	}

	androidx.compose.material3.SwipeToDismissBox(
		state = state,
		backgroundContent = backgroundContent,
		modifier = modifier,
		enableDismissFromStartToEnd = enableDismissFromStartToEnd,
		enableDismissFromEndToStart = enableDismissFromEndToStart,
		gesturesEnabled = gesturesEnabled,
		onDismiss = onDismiss,
		content = content
	)
}

/**
 * 歌曲条目左右滑入队背景：右滑（StartToEnd）加入队尾（secondaryContainer），左滑（EndToStart）下一首播放（primaryContainer）。
 */
@Composable
fun SongQueueSwipeBackground(
	dismissDirection: SwipeToDismissBoxValue,
	shape: Shape,
	modifier: Modifier = Modifier
) {
	val backgroundColor = when (dismissDirection) {
		SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.secondaryContainer
		SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.primaryContainer
		SwipeToDismissBoxValue.Settled -> Color.Transparent
	}
	Box(
		modifier = modifier
			.fillMaxSize()
			.clip(shape)
			.background(backgroundColor)
			.padding(horizontal = 20.dp)
	) {
		when (dismissDirection) {
			SwipeToDismissBoxValue.StartToEnd -> {
				val label = stringResource(R.string.action_add_to_queue)
				val contentColor = MaterialTheme.colorScheme.onSecondaryContainer
				Row(
					modifier = Modifier.align(Alignment.CenterStart),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(8.dp)
				) {
					Icon(
						imageVector = Icons.Outlined.Queue,
						contentDescription = label,
						tint = contentColor
					)
					Text(
						text = label,
						color = contentColor,
						style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
						maxLines = 1,
						overflow = TextOverflow.Ellipsis
					)
				}
			}

			SwipeToDismissBoxValue.EndToStart -> {
				val label = stringResource(R.string.action_play_next)
				val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
				Row(
					modifier = Modifier.align(Alignment.CenterEnd),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(8.dp)
				) {
					Text(
						text = label,
						color = contentColor,
						style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
						maxLines = 1,
						overflow = TextOverflow.Ellipsis
					)
					Icon(
						imageVector = Icons.Outlined.QueuePlayNext,
						contentDescription = label,
						tint = contentColor
					)
				}
			}

			SwipeToDismissBoxValue.Settled -> {}
		}
	}
}
