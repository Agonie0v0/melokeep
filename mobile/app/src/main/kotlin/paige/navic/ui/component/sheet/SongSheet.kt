/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.component.sheet

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import io.github.agonie0v0.melokeep.shared.MeloSourceLabelRegistry
import org.koin.compose.koinInject
import paige.navic.database.entity.DownloadStatus
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.playback.SleepTimerManager
import paige.navic.domain.manager.playback.SleepTimerMode
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.manager.session.SessionManager
import paige.navic.domain.manager.session.canUserShare
import paige.navic.domain.model.DomainExplicitStatus
import paige.navic.domain.model.DomainSong
import paige.navic.melokeep.MeloGroupedCard
import paige.navic.melokeep.MeloHairlineDivider
import paige.navic.melokeep.MeloHairlineWidth
import paige.navic.melokeep.MeloTrackMapper
import paige.navic.melokeep.translateMeloSourceName
import paige.navic.ui.component.common.CoverArt
import paige.navic.ui.component.common.MarqueeText
import paige.navic.ui.component.common.RatingRow
import paige.navic.ui.component.dialog.DeleteDownloadTarget
import paige.navic.ui.component.dialog.rememberDownloadDeletionHandler
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.filled.Note
import paige.navic.ui.icons.filled.Star
import paige.navic.ui.icons.outlined.Album
import paige.navic.ui.icons.outlined.Artist
import paige.navic.ui.icons.outlined.Bedtime
import paige.navic.ui.icons.outlined.Check
import paige.navic.ui.icons.outlined.ChevronForward
import paige.navic.ui.icons.outlined.Close
import paige.navic.ui.icons.outlined.Download
import paige.navic.ui.icons.outlined.DownloadOff
import paige.navic.ui.icons.outlined.Info
import paige.navic.ui.icons.outlined.PlaylistAdd
import paige.navic.ui.icons.outlined.PlaylistRemove
import paige.navic.ui.icons.outlined.Queue
import paige.navic.ui.icons.outlined.QueuePlayNext
import paige.navic.ui.icons.outlined.Share
import paige.navic.ui.icons.outlined.Speed
import paige.navic.ui.icons.outlined.Star
import paige.navic.ui.navigation.Screen
import paige.navic.ui.theme.MeloStudioPresets
import paige.navic.ui.theme.NavicTheme
import paige.navic.ui.theme.positive
import paige.navic.ui.theme.tabularNums
import paige.navic.ui.util.InlineExplicitIcon
import paige.navic.ui.util.buildSongInfoString
import paige.navic.ui.util.rememberColorSchemeFromCoverArt
import paige.navic.util.toHoursMinutesSeconds
import java.util.Locale
import kotlin.time.Duration

@Immutable
private data class SongQuickAction(
	val label: String,
	val icon: ImageVector,
	val contentColor: Color,
	val onClick: () -> Unit
)

@Immutable
private data class SongSheetActionItem(
	val title: String,
	val icon: ImageVector,
	val trailingText: String? = null,
	val accentColor: Color? = null,
	val isDestructive: Boolean = false,
	val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongSheet(
	onDismissRequest: () -> Unit,
	song: DomainSong,
	isPlaylist: Boolean = false,
	starred: Boolean? = null,
	onSetStarred: ((Boolean) -> Unit)? = null,
	onShare: (() -> Unit)? = null,
	onPlayNext: (() -> Unit)? = null,
	onAddToQueue: (() -> Unit)? = null,
	onTrackInfo: (() -> Unit)? = null,
	onViewAlbum: (() -> Unit)? = null,
	onViewArtist: (() -> Unit)? = null,
	onAddToPlaylist: (() -> Unit)? = null,
	onRemoveFromPlaylist: (() -> Unit)? = null,
	downloadStatus: DownloadStatus? = null,
	onDownload: (() -> Unit)? = null,
	onCancelDownload: (() -> Unit)? = null,
	onDeleteDownload: (() -> Unit)? = null,
	rating: Int? = null,
	onSetRating: ((Int) -> Unit)? = null,
	showSleepTimer: Boolean = false,
	onSleepTimer: (() -> Unit)? = null,
	showPlaybackSpeed: Boolean = false,
	onPlaybackSpeed: (() -> Unit)? = null,
	useSongTheme: Boolean = true
) {
	val preferenceManager = koinInject<PreferenceManager>()
	val sessionManager = koinInject<SessionManager>()
	val meloSettingsStore = koinInject<MeloSettingsStore>()
	val sleepTimerManager = koinInject<SleepTimerManager>()
	val sleepTimerMode by sleepTimerManager.mode.collectAsStateWithLifecycle()
	val studioPreset by meloSettingsStore.studioThemePreset.collectAsStateWithLifecycle()
	val downloadDeletionHandler = rememberDownloadDeletionHandler()
	val haptics = LocalHapticFeedback.current
	val backStack = LocalNavStack.current

	val colorScheme = if (useSongTheme && studioPreset == MeloStudioPresets.DYNAMIC) {
		rememberColorSchemeFromCoverArt(song.coverArtId)
	} else {
		null
	}

	NavicTheme(colorScheme) {
		ModalBottomSheet(
			onDismissRequest = onDismissRequest,
			dragHandle = {
				Box(
					modifier = Modifier
						.padding(top = 10.dp, bottom = 6.dp)
						.size(width = 36.dp, height = 4.dp)
						.clip(CircleShape)
						.background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f))
				)
			},
			containerColor = MaterialTheme.colorScheme.background,
			sheetState = rememberBottomSheetState(
				initialValue = SheetValue.Hidden,
				enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
			),
			contentWindowInsets = {
				BottomSheetDefaults.modalWindowInsets.add(
					WindowInsets(left = 4.dp, right = 4.dp)
				)
			}
		) {
			val inlineMeta = remember(
				song.fileExtension,
				song.mimeType,
				song.bitDepth,
				song.sampleRate,
				song.bitRate,
				song.duration,
				song.isExternal,
				song.genre,
				song.id
			) {
				buildList {
					val ext = song.fileExtension?.takeIf { it.isNotBlank() }?.uppercase()
						?: song.mimeType?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.uppercase()
					if (ext != null) add(ext)
					val specParts = buildList {
						song.bitDepth?.takeIf { it > 0 }?.let { add("${it}bit") }
						song.sampleRate?.takeIf { it > 0 }?.let { add(formatSampleRateCompact(it)) }
					}
					when {
						specParts.isNotEmpty() -> add(specParts.joinToString("/"))
						song.bitRate != null && song.bitRate > 0 -> add("${song.bitRate}k")
					}
					song.duration.takeIf { it.inWholeSeconds > 0 }?.toHoursMinutesSeconds()?.let(::add)
					val sourceLabel = when {
						song.id.startsWith("device:") -> "手机本地"
						song.id.startsWith("radio_") -> "电台"
						MeloTrackMapper.isMeloSongId(song.id) -> {
							val rawId = MeloTrackMapper.toRawTrackId(song.id)
							if (
								rawId.startsWith("asset:") ||
								rawId.startsWith("file:") ||
								song.genre.equals("local", ignoreCase = true)
							) {
								"MeloKeep 曲库"
							} else {
								val rawSource = song.genre?.trim().orEmpty()
								if (rawSource.isNotEmpty()) {
									MeloSourceLabelRegistry.resolve(rawSource)
										?: translateMeloSourceName(rawSource).takeIf {
											it.isNotBlank() && it != "未知音源"
										}
										?: rawSource
								} else {
									"在线音源"
								}
							}
						}
						song.isExternal -> "在线音源"
						else -> null
					}
					sourceLabel?.let(::add)
				}.joinToString(" · ")
			}

			val quickActions = buildList {
				if (starred != null && onSetStarred != null) {
					add(
						SongQuickAction(
							label = if (starred) "已喜欢" else "喜欢",
							icon = if (starred) Icons.Filled.Star else Icons.Outlined.Star,
							contentColor = if (starred) {
								MaterialTheme.colorScheme.primary
							} else {
								MaterialTheme.colorScheme.onSurface
							},
							onClick = {
								haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
								onSetStarred(!starred)
								onDismissRequest()
							}
						)
					)
				}

				if (downloadStatus != null || onDownload != null) {
					val offlineAction = when (downloadStatus) {
						DownloadStatus.DOWNLOADING -> SongQuickAction(
							label = "取消下载",
							icon = Icons.Outlined.Close,
							contentColor = MaterialTheme.colorScheme.primary,
							onClick = {
								onCancelDownload?.invoke()
								onDismissRequest()
							}
						)

						DownloadStatus.DOWNLOADED -> SongQuickAction(
							label = "已离线",
							icon = Icons.Outlined.Check,
							contentColor = MaterialTheme.colorScheme.positive,
							onClick = {
								downloadDeletionHandler.requestDelete(
									target = DeleteDownloadTarget.Single(song.id, song.title),
									onConfirmed = {
										onDeleteDownload?.invoke()
										onDismissRequest()
									}
								)
							}
						)

						DownloadStatus.FAILED -> SongQuickAction(
							label = "重试下载",
							icon = Icons.Outlined.DownloadOff,
							contentColor = MaterialTheme.colorScheme.error,
							onClick = {
								onDownload?.invoke()
								onDismissRequest()
							}
						)

						else -> SongQuickAction(
							label = "离线下载",
							icon = Icons.Outlined.Download,
							contentColor = MaterialTheme.colorScheme.onSurface,
							onClick = {
								onDownload?.invoke()
								onDismissRequest()
							}
						)
					}
					add(offlineAction)
				}

				if (onPlayNext != null) {
					add(
						SongQuickAction(
							label = "下一首",
							icon = Icons.Outlined.QueuePlayNext,
							contentColor = MaterialTheme.colorScheme.onSurface,
							onClick = {
								haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
								onPlayNext()
								onDismissRequest()
							}
						)
					)
				}

				if (onAddToPlaylist != null) {
					add(
						SongQuickAction(
							label = if (isPlaylist) "转存" else "加歌单",
							icon = Icons.Outlined.PlaylistAdd,
							contentColor = MaterialTheme.colorScheme.onSurface,
							onClick = {
								onAddToPlaylist()
								onDismissRequest()
							}
						)
					)
				}
			}

			val queueAndPlaylistItems = buildList {
				if (onAddToQueue != null) {
					add(
						SongSheetActionItem(
							title = "加入播放队列",
							icon = Icons.Outlined.Queue,
							onClick = {
								onAddToQueue()
								onDismissRequest()
							}
						)
					)
				}
				if (onRemoveFromPlaylist != null && isPlaylist) {
					add(
						SongSheetActionItem(
							title = "从歌单移除",
							icon = Icons.Outlined.PlaylistRemove,
							isDestructive = true,
							onClick = {
								onRemoveFromPlaylist()
								onDismissRequest()
							}
						)
					)
				}
			}

			val metadataItems = buildList {
				if (onViewAlbum != null) {
					add(
						SongSheetActionItem(
							title = "查看专辑",
							icon = Icons.Outlined.Album,
							onClick = {
								onViewAlbum()
								onDismissRequest()
							}
						)
					)
				}
				if (onViewArtist != null) {
					add(
						SongSheetActionItem(
							title = "查看歌手",
							icon = Icons.Outlined.Artist,
							onClick = {
								onViewArtist()
								onDismissRequest()
							}
						)
					)
				}
				if (onTrackInfo != null) {
					add(
						SongSheetActionItem(
							title = "歌曲信息",
							icon = Icons.Outlined.Info,
							onClick = {
								onDismissRequest()
								onTrackInfo()
							}
						)
					)
				}
			}

			val sleepTimerActive = sleepTimerMode !is SleepTimerMode.Disabled
			val sleepTimerText = when (val mode = sleepTimerMode) {
				is SleepTimerMode.Time -> sleepTimerManager.timeLeft?.let(::formatSleepTimerRemaining)
				is SleepTimerMode.Songs -> "剩余 ${mode.remaining} 首"
				is SleepTimerMode.EndOfQueue -> "队列结束后"
				else -> null
			}
			val playbackAndShareItems = buildList {
				if (showSleepTimer) {
					add(
						SongSheetActionItem(
							title = "睡眠定时",
							icon = Icons.Outlined.Bedtime,
							trailingText = sleepTimerText,
							accentColor = if (sleepTimerActive) MaterialTheme.colorScheme.positive else null,
							onClick = { onSleepTimer?.invoke() }
						)
					)
				}
				if (showPlaybackSpeed) {
					add(
						SongSheetActionItem(
							title = "倍速与音调",
							icon = Icons.Outlined.Speed,
							onClick = dropUnlessResumed {
								onPlaybackSpeed?.invoke()
							}
						)
					)
				}
				if (onShare != null && sessionManager.canUserShare()) {
					add(
						SongSheetActionItem(
							title = "分享",
							icon = Icons.Outlined.Share,
							onClick = {
								onShare()
								onDismissRequest()
							}
						)
					)
				}
			}

			Column(
				modifier = Modifier
					.fillMaxWidth()
					.verticalScroll(rememberScrollState())
					.padding(horizontal = 16.dp),
				verticalArrangement = Arrangement.spacedBy(12.dp)
			) {
				// 1. 歌曲头卡
				MeloGroupedCard {
					Column {
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.padding(14.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(12.dp)
						) {
							Box(
								modifier = Modifier.size(56.dp),
								contentAlignment = Alignment.Center
							) {
								CoverArt(
									coverArtId = song.coverArtId,
									modifier = Modifier.size(56.dp),
									shape = RoundedCornerShape(10.dp)
								)
								if (song.coverArtId == null) {
									Icon(
										imageVector = Icons.Filled.Note,
										contentDescription = null,
										tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
										modifier = Modifier.size(22.dp)
									)
								}
							}

							Column(
								modifier = Modifier.weight(1f),
								verticalArrangement = Arrangement.spacedBy(2.dp)
							) {
								MarqueeText(
									text = buildAnnotatedString {
										append(song.title)
										if (song.explicitStatus == DomainExplicitStatus.Explicit) {
											append(" ")
											appendInlineContent("InlineExplicitIcon")
										}
									},
									inlineContent = InlineExplicitIcon,
									style = MaterialTheme.typography.titleMedium.copy(
										fontWeight = FontWeight.SemiBold,
										color = MaterialTheme.colorScheme.onSurface
									)
								)

								if (song.artists.isNotEmpty()) {
									MarqueeText(
										text = buildSongInfoString(
											song = song,
											showExternal = false,
											showAlbum = !song.albumTitle.isNullOrBlank(),
											onClickArtist = {
												onDismissRequest()
												backStack.add(Screen.ArtistDetail(it))
											}
										),
										style = MaterialTheme.typography.bodySmall.copy(
											color = MaterialTheme.colorScheme.onSurfaceVariant
										)
									)
								} else {
									val fallbackSubtitle = listOfNotNull(
										song.artistName?.takeIf { it.isNotBlank() },
										song.albumTitle?.takeIf { it.isNotBlank() }
									).joinToString(" · ")
									if (fallbackSubtitle.isNotEmpty()) {
										Text(
											text = fallbackSubtitle,
											style = MaterialTheme.typography.bodySmall,
											color = MaterialTheme.colorScheme.onSurfaceVariant,
											maxLines = 1,
											overflow = TextOverflow.Ellipsis
										)
									}
								}

								if (inlineMeta.isNotEmpty()) {
									Text(
										text = inlineMeta,
										style = MaterialTheme.typography.labelSmall.tabularNums(),
										color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
										maxLines = 1,
										overflow = TextOverflow.Ellipsis
									)
								}
							}
						}

						if (rating != null && onSetRating != null && preferenceManager.enableRatings) {
							MeloHairlineDivider(startIndent = 14.dp)
							Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
								RatingRow(
									rating = rating,
									setRating = onSetRating
								)
							}
						}
					}
				}

				// 2. 快捷操作行
				if (quickActions.size >= 2) {
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.spacedBy(8.dp)
					) {
						quickActions.forEach { action ->
							Surface(
								onClick = action.onClick,
								modifier = Modifier.weight(1f),
								shape = RoundedCornerShape(12.dp),
								color = MaterialTheme.colorScheme.surfaceContainer,
								border = BorderStroke(
									width = MeloHairlineWidth,
									color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
								)
							) {
								Column(
									modifier = Modifier
										.fillMaxWidth()
										.heightIn(min = 60.dp)
										.padding(horizontal = 6.dp, vertical = 10.dp),
									horizontalAlignment = Alignment.CenterHorizontally,
									verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically)
								) {
									Icon(
										imageVector = action.icon,
										contentDescription = action.label,
										tint = action.contentColor,
										modifier = Modifier.size(19.dp)
									)
									Text(
										text = action.label,
										style = MaterialTheme.typography.labelSmall.copy(
											fontWeight = FontWeight.Medium,
											fontSize = 11.sp
										),
										color = action.contentColor,
										maxLines = 1,
										overflow = TextOverflow.Ellipsis,
										textAlign = TextAlign.Center
									)
								}
							}
						}
					}
				} else if (quickActions.size == 1) {
					val single = quickActions.first()
					MeloGroupedCard {
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.clickable(onClick = single.onClick)
								.padding(horizontal = 14.dp, vertical = 13.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(12.dp)
						) {
							Icon(
								imageVector = single.icon,
								contentDescription = null,
								tint = single.contentColor,
								modifier = Modifier.size(19.dp)
							)
							Text(
								text = single.label,
								style = MaterialTheme.typography.bodyMedium,
								fontWeight = FontWeight.Medium,
								color = single.contentColor,
								modifier = Modifier.weight(1f),
								maxLines = 1,
								overflow = TextOverflow.Ellipsis
							)
							Icon(
								imageVector = Icons.Outlined.ChevronForward,
								contentDescription = null,
								tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
								modifier = Modifier.size(17.dp)
							)
						}
					}
				}

				// 3. 分组菜单
				if (queueAndPlaylistItems.isNotEmpty()) {
					SongSheetActionGroup(items = queueAndPlaylistItems)
				}
				if (metadataItems.isNotEmpty()) {
					SongSheetActionGroup(items = metadataItems)
				}
				if (playbackAndShareItems.isNotEmpty()) {
					SongSheetActionGroup(items = playbackAndShareItems)
				}

				Spacer(Modifier.height(14.dp))
			}
		}
	}
}

@Composable
private fun SongSheetActionGroup(
	items: List<SongSheetActionItem>
) {
	MeloGroupedCard {
		items.forEachIndexed { index, item ->
			val contentTint = when {
				item.isDestructive -> MaterialTheme.colorScheme.error
				item.accentColor != null -> item.accentColor
				else -> MaterialTheme.colorScheme.onSurface
			}
			val iconTint = when {
				item.isDestructive -> MaterialTheme.colorScheme.error
				item.accentColor != null -> item.accentColor
				else -> MaterialTheme.colorScheme.onSurfaceVariant
			}

			Row(
				modifier = Modifier
					.fillMaxWidth()
					.clickable(onClick = item.onClick)
					.padding(horizontal = 14.dp, vertical = 13.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(12.dp)
			) {
				Icon(
					imageVector = item.icon,
					contentDescription = null,
					tint = iconTint,
					modifier = Modifier.size(19.dp)
				)

				Text(
					text = item.title,
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = FontWeight.Medium,
					color = contentTint,
					modifier = Modifier.weight(1f),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)

				if (!item.trailingText.isNullOrBlank()) {
					Text(
						text = item.trailingText,
						style = MaterialTheme.typography.bodySmall.tabularNums(),
						color = item.accentColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis
					)
				}

				Icon(
					imageVector = Icons.Outlined.ChevronForward,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
					modifier = Modifier.size(17.dp)
				)
			}

			if (index < items.lastIndex) {
				MeloHairlineDivider(startIndent = 45.dp)
			}
		}
	}
}

private fun formatSampleRateCompact(sampleRate: Int): String {
	return if (sampleRate >= 1000) {
		if (sampleRate % 1000 == 0) {
			"${sampleRate / 1000}kHz"
		} else {
			String.format(Locale.US, "%.1fkHz", sampleRate / 1000.0)
		}
	} else {
		"${sampleRate}Hz"
	}
}

private fun formatSleepTimerRemaining(duration: Duration): String {
	val hours = duration.inWholeHours
	val minutes = duration.inWholeMinutes % 60
	return when {
		hours > 0 && minutes > 0 -> "${hours}小时${minutes}分"
		hours > 0 -> "${hours}小时"
		else -> "${minutes.coerceAtLeast(1)}分钟"
	}
}
