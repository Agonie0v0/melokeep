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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import paige.navic.R
import paige.navic.database.entity.DownloadStatus
import paige.navic.domain.model.DomainArtist
import paige.navic.domain.model.settings.DownloadDeletionType
import paige.navic.melokeep.MeloGroupedCard
import paige.navic.melokeep.MeloHairlineDivider
import paige.navic.melokeep.MeloHairlineWidth
import paige.navic.ui.component.common.CoverArt
import paige.navic.ui.component.common.MarqueeText
import paige.navic.ui.component.dialog.DeleteDownloadTarget
import paige.navic.ui.component.dialog.LinkConfirmationDialog
import paige.navic.ui.component.dialog.rememberDownloadDeletionHandler
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.brand.LastFM
import paige.navic.ui.icons.brand.MusicBrainz
import paige.navic.ui.icons.filled.Artist
import paige.navic.ui.icons.filled.Star
import paige.navic.ui.icons.outlined.Check
import paige.navic.ui.icons.outlined.ChevronForward
import paige.navic.ui.icons.outlined.Close
import paige.navic.ui.icons.outlined.Download
import paige.navic.ui.icons.outlined.DownloadOff
import paige.navic.ui.icons.outlined.PlaylistAdd
import paige.navic.ui.icons.outlined.Queue
import paige.navic.ui.icons.outlined.QueuePlayNext
import paige.navic.ui.icons.outlined.Star
import paige.navic.ui.theme.positive
import paige.navic.ui.theme.tabularNums

@Immutable
private data class ArtistQuickAction(
	val label: String,
	val icon: ImageVector,
	val contentColor: Color,
	val onClick: () -> Unit
)

@Immutable
private data class ArtistSheetActionItem(
	val title: String,
	val icon: ImageVector,
	val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistSheet(
	onDismissRequest: () -> Unit,
	artist: DomainArtist,
	onPlayNext: (() -> Unit)? = null,
	onAddToQueue: (() -> Unit)? = null,
	onAddAllToPlaylist: (() -> Unit)? = null,
	starred: Boolean? = null,
	onSetStarred: ((Boolean) -> Unit)? = null,
	downloadStatus: DownloadStatus? = null,
	onDownloadAll: (() -> Unit)? = null,
	onCancelDownloadAll: (() -> Unit)? = null,
	onDeleteDownloadAll: (() -> Unit)? = null,
) {
	val downloadDeletionHandler = rememberDownloadDeletionHandler()
	val haptics = LocalHapticFeedback.current
	var linkToOpen by rememberSaveable { mutableStateOf<String?>(null) }

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
		var playlistPlacedInQuick = false
		val quickActions = buildList {
			if (starred != null && onSetStarred != null) {
				add(
					ArtistQuickAction(
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

			if (downloadStatus != null || onDownloadAll != null) {
				val offlineAction = when (downloadStatus) {
					DownloadStatus.DOWNLOADING -> ArtistQuickAction(
						label = "取消下载",
						icon = Icons.Outlined.Close,
						contentColor = MaterialTheme.colorScheme.primary,
						onClick = {
							onCancelDownloadAll?.invoke()
							onDismissRequest()
						}
					)

					DownloadStatus.DOWNLOADED -> ArtistQuickAction(
						label = "已离线",
						icon = Icons.Outlined.Check,
						contentColor = MaterialTheme.colorScheme.positive,
						onClick = {
							downloadDeletionHandler.requestDelete(
								target = DeleteDownloadTarget.Collection(
									name = artist.name,
									songIds = emptyList(),
									type = DownloadDeletionType.ARTIST
								),
								onConfirmed = {
									onDeleteDownloadAll?.invoke()
									onDismissRequest()
								}
							)
						}
					)

					DownloadStatus.FAILED -> ArtistQuickAction(
						label = "重试下载",
						icon = Icons.Outlined.DownloadOff,
						contentColor = MaterialTheme.colorScheme.error,
						onClick = {
							onDownloadAll?.invoke()
							onDismissRequest()
						}
					)

					else -> ArtistQuickAction(
						label = "离线下载",
						icon = Icons.Outlined.Download,
						contentColor = MaterialTheme.colorScheme.onSurface,
						onClick = {
							onDownloadAll?.invoke()
							onDismissRequest()
						}
					)
				}
				add(offlineAction)
			}

			if (onPlayNext != null) {
				add(
					ArtistQuickAction(
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

			if (onAddToQueue != null) {
				add(
					ArtistQuickAction(
						label = "加队列",
						icon = Icons.Outlined.Queue,
						contentColor = MaterialTheme.colorScheme.onSurface,
						onClick = {
							onAddToQueue()
							onDismissRequest()
						}
					)
				)
			}

			if (size < 4 && onAddAllToPlaylist != null) {
				playlistPlacedInQuick = true
				add(
					ArtistQuickAction(
						label = "加歌单",
						icon = Icons.Outlined.PlaylistAdd,
						contentColor = MaterialTheme.colorScheme.onSurface,
						onClick = {
							onAddAllToPlaylist()
							onDismissRequest()
						}
					)
				)
			}
		}

		val actionItems = buildList {
			if (!playlistPlacedInQuick && onAddAllToPlaylist != null) {
				add(
					ArtistSheetActionItem(
						title = stringResource(R.string.action_add_to_playlist),
						icon = Icons.Outlined.PlaylistAdd,
						onClick = {
							onAddAllToPlaylist()
							onDismissRequest()
						}
					)
				)
			}

			if (artist.lastFmUrl != null) {
				add(
					ArtistSheetActionItem(
						title = stringResource(R.string.action_view_on_lastfm),
						icon = Icons.Brand.LastFM,
						onClick = {
							linkToOpen = artist.lastFmUrl
						}
					)
				)
			}

			if (artist.musicBrainzId != null) {
				add(
					ArtistSheetActionItem(
						title = stringResource(R.string.action_view_on_musicbrainz),
						icon = Icons.Brand.MusicBrainz,
						onClick = {
							linkToOpen = "https://musicbrainz.org/artist/${artist.musicBrainzId}"
						}
					)
				)
			}
		}

		Column(
			modifier = Modifier
				.fillMaxWidth()
				.verticalScroll(rememberScrollState())
				.navigationBarsPadding()
				.padding(horizontal = 16.dp),
			verticalArrangement = Arrangement.spacedBy(14.dp)
		) {
			MeloGroupedCard {
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
							coverArtId = artist.coverArtId,
							modifier = Modifier.size(56.dp),
							shape = RoundedCornerShape(10.dp)
						)
						if (artist.coverArtId == null) {
							Icon(
								imageVector = Icons.Filled.Artist,
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
							text = artist.name,
							style = MaterialTheme.typography.titleMedium.copy(
								fontWeight = FontWeight.SemiBold,
								color = MaterialTheme.colorScheme.onSurface
							)
						)
						Text(
							text = pluralStringResource(
								R.plurals.count_albums,
								artist.albumCount,
								artist.albumCount
							),
							style = MaterialTheme.typography.bodySmall.tabularNums(),
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis
						)
					}
				}
			}

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
					ArtistSheetActionRow(
						title = single.label,
						icon = single.icon,
						contentTint = single.contentColor,
						iconTint = single.contentColor,
						onClick = single.onClick
					)
				}
			}

			if (actionItems.isNotEmpty()) {
				MeloGroupedCard {
					actionItems.forEachIndexed { index, item ->
						ArtistSheetActionRow(
							title = item.title,
							icon = item.icon,
							contentTint = MaterialTheme.colorScheme.onSurface,
							iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
							onClick = item.onClick
						)

						if (index < actionItems.lastIndex) {
							MeloHairlineDivider(startIndent = 46.dp)
						}
					}
				}
			}

			Spacer(Modifier.height(14.dp))
		}
	}

	LinkConfirmationDialog(
		linkToOpen = linkToOpen,
		onDismissRequest = { linkToOpen = null }
	)
}

@Composable
private fun ArtistSheetActionRow(
	title: String,
	icon: ImageVector,
	contentTint: Color,
	iconTint: Color,
	onClick: () -> Unit
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(horizontal = 14.dp, vertical = 13.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp)
	) {
		Icon(
			imageVector = icon,
			contentDescription = null,
			tint = iconTint,
			modifier = Modifier.size(19.dp)
		)
		Text(
			text = title,
			style = MaterialTheme.typography.bodyMedium,
			fontWeight = FontWeight.Medium,
			color = contentTint,
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
