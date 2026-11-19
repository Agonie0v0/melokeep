/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.screen.queue.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.model.DomainExplicitStatus
import paige.navic.domain.model.DomainSong
import paige.navic.domain.model.settings.ExplicitContentPlayback
import paige.navic.ui.component.common.CoverArt
import paige.navic.ui.component.common.MarqueeText
import paige.navic.ui.component.common.SwipeToDismissBox
import paige.navic.ui.component.common.TooltipBox
import paige.navic.ui.component.common.Waveform
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.filled.Note
import paige.navic.ui.icons.outlined.Delete
import paige.navic.ui.icons.outlined.DragHandle
import paige.navic.ui.icons.outlined.Lock
import paige.navic.ui.icons.outlined.Offline
import paige.navic.ui.icons.outlined.QueuePlayNext
import paige.navic.ui.theme.meloGoldBorder
import paige.navic.ui.theme.meloSubtleBorder
import paige.navic.ui.theme.tabularNums
import paige.navic.ui.util.buildSongInfoString
import paige.navic.util.toHoursMinutesSeconds

@Composable
fun QueueScreenItem(
	index: Int,
	count: Int,
	song: DomainSong,
	isPlaying: Boolean,
	isSelected: Boolean,
	isDragging: Boolean,
	onDrag: () -> Unit,
	onClick: () -> Unit,
	onRemove: () -> Unit,
	canReorder: Boolean = true,
	onPlayNext: (() -> Unit)? = null,
	isOffline: Boolean = false,
	isDownloaded: Boolean = false
) {
	val preferenceManager = koinInject<PreferenceManager>()
	val isExplicit = song.explicitStatus == DomainExplicitStatus.Explicit
		&& preferenceManager.explicitContentPlayback != ExplicitContentPlayback.Allowed
	val maybeUnavailable = isOffline && !isDownloaded

	val elevation by animateDpAsState(
		targetValue = if (isDragging) 10.dp else 0.dp,
		animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
		label = "QueueCardElevation"
	)

	val dismissState = rememberSwipeToDismissBoxState()
	val scope = rememberCoroutineScope()
	val cardShape = RoundedCornerShape(16.dp)

	val containerColor = when {
		isDragging -> MaterialTheme.colorScheme.surfaceContainerHighest
		isSelected -> MaterialTheme.colorScheme.surfaceContainerHigh
		else -> MaterialTheme.colorScheme.surfaceContainer
	}
	val borderColor = when {
		isPlaying -> MaterialTheme.colorScheme.primary
		isSelected -> MaterialTheme.colorScheme.meloGoldBorder
		else -> MaterialTheme.colorScheme.meloSubtleBorder
	}
	val borderWidth = if (isSelected) 1.25.dp else 1.dp

	SwipeToDismissBox(
		state = dismissState,
		onDismiss = {
			onRemove()
			scope.launch {
				dismissState.reset()
			}
		},
		backgroundContent = {
			Box(
				modifier = Modifier
					.fillMaxSize()
					.clip(cardShape)
					.background(MaterialTheme.colorScheme.errorContainer)
					.padding(horizontal = 20.dp)
			) {
				Icon(
					imageVector = Icons.Outlined.Delete,
					contentDescription = "移出队列",
					tint = MaterialTheme.colorScheme.onErrorContainer,
					modifier = Modifier.align(
						when (dismissState.dismissDirection) {
							SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
							else -> Alignment.CenterEnd
						}
					)
				)
			}
		},
		content = {
			Surface(
				onClick = onClick,
				enabled = !isExplicit,
				modifier = Modifier
					.fillMaxWidth()
					.alpha(if (isExplicit) 0.5f else 1f),
				shape = cardShape,
				color = containerColor,
				border = BorderStroke(borderWidth, borderColor),
				shadowElevation = elevation
			) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 10.dp, vertical = 10.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(12.dp)
				) {
					Box(
						modifier = Modifier.size(48.dp),
						contentAlignment = Alignment.Center
					) {
						CoverArt(
							modifier = Modifier.size(48.dp),
							coverArtId = song.coverArtId,
							shape = RoundedCornerShape(12.dp)
						)
						if (song.coverArtId == null) {
							Icon(
								imageVector = Icons.Filled.Note,
								contentDescription = null,
								tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
								modifier = Modifier.size(20.dp)
							)
						}
					}

					Column(
						modifier = Modifier.weight(1f),
						verticalArrangement = Arrangement.spacedBy(3.dp)
					) {
						val titleColor = if (isSelected) {
							MaterialTheme.colorScheme.primary
						} else {
							MaterialTheme.colorScheme.onSurface
						}
						val titleStyle = MaterialTheme.typography.bodyMedium.copy(
							fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
							color = titleColor
						)
						if (isPlaying) {
							MarqueeText(
								text = song.title,
								style = titleStyle
							)
						} else {
							Text(
								text = song.title,
								style = titleStyle,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis
							)
						}

						val durationLabel = song.duration
							.takeIf { it.inWholeSeconds > 0 }
							?.toHoursMinutesSeconds()
						Row(
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(6.dp)
						) {
							if (song.artists.isNotEmpty()) {
								Text(
									text = buildSongInfoString(
										song,
										showExternal = false,
										showYear = false,
										showAlbum = false
									),
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									modifier = Modifier.weight(1f, fill = false),
									maxLines = 1,
									overflow = TextOverflow.Ellipsis
								)
							} else {
								Text(
									text = song.artistName.orEmpty().ifBlank { "未知音乐人" },
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									modifier = Modifier.weight(1f, fill = false),
									maxLines = 1,
									overflow = TextOverflow.Ellipsis
								)
							}

							if (durationLabel != null) {
								Text(
									text = "· $durationLabel",
									style = MaterialTheme.typography.labelSmall.tabularNums(),
									color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
									maxLines = 1
								)
							}
						}
					}

					Row(
						horizontalArrangement = Arrangement.spacedBy(4.dp),
						verticalAlignment = Alignment.CenterVertically
					) {
						if (isExplicit) {
							Icon(
								imageVector = Icons.Outlined.Lock,
								contentDescription = "受限内容",
								tint = MaterialTheme.colorScheme.onSurfaceVariant,
								modifier = Modifier.size(18.dp)
							)
						}
						if (maybeUnavailable) {
							Icon(
								imageVector = Icons.Outlined.Offline,
								contentDescription = "离线不可用",
								tint = MaterialTheme.colorScheme.error,
								modifier = Modifier.size(18.dp)
							)
						}
						if (isSelected) {
							Surface(
								shape = RoundedCornerShape(8.dp),
								color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
							) {
								Waveform(
									isPlaying = isPlaying,
									modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
								)
							}
						}
						if (onPlayNext != null) {
							TooltipBox("下一首播放") {
								IconButton(
									onClick = onPlayNext,
									enabled = !isExplicit,
									modifier = Modifier.size(36.dp)
								) {
									Icon(
										imageVector = Icons.Outlined.QueuePlayNext,
										contentDescription = "下一首播放",
										tint = MaterialTheme.colorScheme.primary,
										modifier = Modifier.size(19.dp)
									)
								}
							}
						}
						if (canReorder) {
							Box(
								modifier = Modifier
									.size(36.dp)
									.clip(CircleShape)
									.pointerInput(onDrag) {
										awaitEachGesture {
											awaitFirstDown(requireUnconsumed = false)
											onDrag()
										}
									},
								contentAlignment = Alignment.Center
							) {
								Icon(
									imageVector = Icons.Outlined.DragHandle,
									contentDescription = "拖动排序",
									tint = MaterialTheme.colorScheme.onSurfaceVariant,
									modifier = Modifier.size(20.dp)
								)
							}
						}
					}
				}
			}
		}
	)
}
