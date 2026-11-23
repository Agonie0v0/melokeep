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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import paige.navic.R
import paige.navic.database.entity.DownloadStatus
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.manager.session.DownloadManager
import paige.navic.domain.manager.session.SessionManager
import paige.navic.domain.manager.session.canUserShare
import paige.navic.domain.model.DomainAlbum
import paige.navic.domain.model.DomainAlbumInfo
import paige.navic.domain.model.DomainPlaylist
import paige.navic.domain.model.DomainSongCollection
import paige.navic.melokeep.MeloGroupedCard
import paige.navic.melokeep.MeloHairlineDivider
import paige.navic.melokeep.MeloHairlineWidth
import paige.navic.ui.component.common.CoverArt
import paige.navic.ui.component.common.MarqueeText
import paige.navic.ui.component.common.RatingRow
import paige.navic.ui.component.dialog.DeleteDownloadTarget
import paige.navic.ui.component.dialog.LinkConfirmationDialog
import paige.navic.ui.component.dialog.rememberDownloadDeletionHandler
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.brand.LastFM
import paige.navic.ui.icons.brand.MusicBrainz
import paige.navic.ui.icons.filled.Note
import paige.navic.ui.icons.filled.Star
import paige.navic.ui.icons.outlined.Artist
import paige.navic.ui.icons.outlined.Check
import paige.navic.ui.icons.outlined.ChevronForward
import paige.navic.ui.icons.outlined.Close
import paige.navic.ui.icons.outlined.Download
import paige.navic.ui.icons.outlined.DownloadOff
import paige.navic.ui.icons.outlined.PlaylistAdd
import paige.navic.ui.icons.outlined.PlaylistRemove
import paige.navic.ui.icons.outlined.Queue
import paige.navic.ui.icons.outlined.QueuePlayNext
import paige.navic.ui.icons.outlined.Share
import paige.navic.ui.icons.outlined.Star
import paige.navic.ui.theme.positive
import paige.navic.ui.theme.tabularNums

@Immutable
private data class CollectionQuickAction(
	val label: String,
	val icon: ImageVector,
	val contentColor: Color,
	val enabled: Boolean = true,
	val onClick: () -> Unit
)

@Immutable
private data class CollectionSheetActionItem(
	val title: String,
	val icon: ImageVector,
	val isDestructive: Boolean = false,
	val enabled: Boolean = true,
	val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionSheet(
	onDismissRequest: () -> Unit,
	collection: DomainSongCollection,
	albumInfo: DomainAlbumInfo? = null,
	onShare: (() -> Unit)? = null,
	onPlayNext: (() -> Unit)? = null,
	onAddToQueue: (() -> Unit)? = null,
	onAddAllToPlaylist: (() -> Unit)? = null,
	onViewArtist: (() -> Unit)? = null,
	starred: Boolean? = null,
	onSetStarred: ((Boolean) -> Unit)? = null,
	onDelete: (() -> Unit)? = null,
	rating: Int? = null,
	onSetRating: ((Int) -> Unit)? = null
) {
	val downloadManager = koinInject<DownloadManager>()
	val preferenceManager = koinInject<PreferenceManager>()
	val sessionManager = koinInject<SessionManager>()
	val scope = rememberCoroutineScope()
	val haptics = LocalHapticFeedback.current
	val downloadStatus by downloadManager
		.getCollectionDownloadStatus(collection.songs.map { it.id })
		.collectAsState(initial = DownloadStatus.NOT_DOWNLOADED)

	var linkToOpen by rememberSaveable { mutableStateOf<String?>(null) }
	val downloadDeletionHandler = rememberDownloadDeletionHandler()
	val hasSongs = collection.songs.isNotEmpty()

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
		val offlineAction = when (downloadStatus) {
			DownloadStatus.DOWNLOADING -> CollectionQuickAction(
				label = "取消下载",
				icon = Icons.Outlined.Close,
				contentColor = MaterialTheme.colorScheme.primary,
				onClick = {
					downloadManager.cancelCollectionDownload(collection.songs.map { it.id })
					onDismissRequest()
				}
			)

			DownloadStatus.DOWNLOADED -> CollectionQuickAction(
				label = "已离线",
				icon = Icons.Outlined.Check,
				contentColor = MaterialTheme.colorScheme.positive,
				onClick = {
					downloadDeletionHandler.requestDelete(
						target = DeleteDownloadTarget.Collection.of(collection),
						onConfirmed = { onDismissRequest() }
					)
				}
			)

			DownloadStatus.FAILED -> CollectionQuickAction(
				label = "重试下载",
				icon = Icons.Outlined.DownloadOff,
				contentColor = MaterialTheme.colorScheme.error,
				onClick = {
					scope.launch {
						downloadManager.downloadCollection(collection.songs.map { it.id })
					}
					onDismissRequest()
				}
			)

			else -> CollectionQuickAction(
				label = "离线下载",
				icon = Icons.Outlined.Download,
				contentColor = MaterialTheme.colorScheme.onSurface,
				onClick = {
					scope.launch {
						downloadManager.downloadCollection(collection.songs.map { it.id })
					}
					onDismissRequest()
				}
			)
		}

		var playlistPlacedInQuick = false
		val quickActions = buildList {
			if (starred != null && onSetStarred != null) {
				add(
					CollectionQuickAction(
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

			add(offlineAction)

			if (onPlayNext != null) {
				add(
					CollectionQuickAction(
						label = "下一首",
						icon = Icons.Outlined.QueuePlayNext,
						contentColor = MaterialTheme.colorScheme.onSurface,
						enabled = hasSongs,
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
					CollectionQuickAction(
						label = "加队列",
						icon = Icons.Outlined.Queue,
						contentColor = MaterialTheme.colorScheme.onSurface,
						enabled = hasSongs,
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
					CollectionQuickAction(
						label = "加歌单",
						icon = Icons.Outlined.PlaylistAdd,
						contentColor = MaterialTheme.colorScheme.onSurface,
						enabled = hasSongs,
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
					CollectionSheetActionItem(
						title = stringResource(R.string.action_add_to_playlist),
						icon = Icons.Outlined.PlaylistAdd,
						enabled = hasSongs,
						onClick = {
							onAddAllToPlaylist()
							onDismissRequest()
						}
					)
				)
			}

			if (onViewArtist != null) {
				add(
					CollectionSheetActionItem(
						title = stringResource(R.string.action_view_artist),
						icon = Icons.Outlined.Artist,
						onClick = {
							onViewArtist()
							onDismissRequest()
						}
					)
				)
			}

			if (onShare != null && sessionManager.canUserShare()) {
				add(
					CollectionSheetActionItem(
						title = stringResource(R.string.action_share),
						icon = Icons.Outlined.Share,
						onClick = {
							onShare()
							onDismissRequest()
						}
					)
				)
			}

			if (albumInfo?.lastFmUrl != null) {
				add(
					CollectionSheetActionItem(
						title = stringResource(R.string.action_view_on_lastfm),
						icon = Icons.Brand.LastFM,
						onClick = {
							linkToOpen = albumInfo.lastFmUrl
						}
					)
				)
			}

			if (albumInfo?.musicBrainzId != null) {
				add(
					CollectionSheetActionItem(
						title = stringResource(R.string.action_view_on_musicbrainz),
						icon = Icons.Brand.MusicBrainz,
						onClick = {
							linkToOpen = "https://musicbrainz.org/release/${albumInfo.musicBrainzId}"
						}
					)
				)
			}

			if (onDelete != null) {
				add(
					CollectionSheetActionItem(
						title = stringResource(R.string.action_delete),
						icon = Icons.Outlined.PlaylistRemove,
						isDestructive = true,
						onClick = {
							onDelete()
							onDismissRequest()
						}
					)
				)
			}
		}

		val subtitleText = listOfNotNull(
			(collection as? DomainAlbum)?.artistName?.takeIf { it.isNotBlank() },
			(collection as? DomainPlaylist)?.comment?.takeIf { it.isNotBlank() },
			(collection as? DomainAlbum)?.genre?.takeIf { it.isNotBlank() },
			(collection as? DomainAlbum)?.year?.takeIf { it > 0 }?.toString(),
			collection.songCount.let {
				pluralStringResource(R.plurals.count_songs, it, it)
			}
		).joinToString(" · ")

		Column(
			modifier = Modifier
				.fillMaxWidth()
				.verticalScroll(rememberScrollState())
				.navigationBarsPadding()
				.padding(horizontal = 16.dp),
			verticalArrangement = Arrangement.spacedBy(14.dp)
		) {
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
								coverArtId = collection.coverArtId,
								modifier = Modifier.size(56.dp),
								shape = RoundedCornerShape(10.dp)
							)
							if (collection.coverArtId == null) {
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
								text = collection.name.orEmpty(),
								style = MaterialTheme.typography.titleMedium.copy(
									fontWeight = FontWeight.SemiBold,
									color = MaterialTheme.colorScheme.onSurface
								)
							)
							if (subtitleText.isNotEmpty()) {
								MarqueeText(
									text = subtitleText,
									style = MaterialTheme.typography.bodySmall.copy(
										color = MaterialTheme.colorScheme.onSurfaceVariant
									).tabularNums()
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

			if (quickActions.size >= 2) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.spacedBy(8.dp)
				) {
					quickActions.forEach { action ->
						Surface(
							onClick = action.onClick,
							enabled = action.enabled,
							modifier = Modifier
								.weight(1f)
								.alpha(if (action.enabled) 1f else 0.42f),
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
					CollectionSheetActionRow(
						title = single.label,
						icon = single.icon,
						contentTint = single.contentColor,
						iconTint = single.contentColor,
						enabled = single.enabled,
						onClick = single.onClick
					)
				}
			}

			if (actionItems.isNotEmpty()) {
				MeloGroupedCard {
					actionItems.forEachIndexed { index, item ->
						val contentTint = if (item.isDestructive) {
							MaterialTheme.colorScheme.error
						} else {
							MaterialTheme.colorScheme.onSurface
						}
						val iconTint = if (item.isDestructive) {
							MaterialTheme.colorScheme.error
						} else {
							MaterialTheme.colorScheme.onSurfaceVariant
						}

						CollectionSheetActionRow(
							title = item.title,
							icon = item.icon,
							contentTint = contentTint,
							iconTint = iconTint,
							enabled = item.enabled,
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
private fun CollectionSheetActionRow(
	title: String,
	icon: ImageVector,
	contentTint: Color,
	iconTint: Color,
	enabled: Boolean,
	onClick: () -> Unit
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(enabled = enabled, onClick = onClick)
			.alpha(if (enabled) 1f else 0.42f)
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
