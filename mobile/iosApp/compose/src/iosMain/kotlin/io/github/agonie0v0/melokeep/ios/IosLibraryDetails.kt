@file:OptIn(
	ExperimentalMaterial3Api::class,
	ExperimentalLayoutApi::class,
	ExperimentalFoundationApi::class,
)

package io.github.agonie0v0.melokeep.ios

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.agonie0v0.melokeep.shared.MeloDailyRecommendController
import io.github.agonie0v0.melokeep.shared.MeloDailyRecommendItem
import io.github.agonie0v0.melokeep.shared.MeloDailyRecommendUiState
import io.github.agonie0v0.melokeep.shared.MeloRecommendSeed
import io.github.agonie0v0.melokeep.shared.MeloTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter

// =========================================================================
// 1. 原生专辑详情页
// =========================================================================

@Composable
internal fun IosLibAlbumDetailScreen(
	albumId: String,
	albumTitle: String,
	refreshSeq: Int,
	subsonicManager: IosSubsonicManager,
	audioPlayer: IosAudioPlayer,
	currentTrackId: String?,
	starredIds: Set<String>,
	unstarredIds: Set<String>,
	offlineIds: Set<String>,
	downloadingProgress: Map<String, Float>,
	onOpenArtist: (artistId: String, artistName: String) -> Unit,
	onOpenAddToPlaylist: (List<IosTrackItem>) -> Unit,
	onRequestPhysicalDelete: ((IosTrackItem) -> Unit)?,
	onShowNotice: (String) -> Unit,
	onBack: () -> Unit,
	bottomPadding: PaddingValues,
) {
	val scope = rememberCoroutineScope()
	var album by remember(albumId) { mutableStateOf<IosAlbumSummary?>(null) }
	var tracks by remember(albumId) { mutableStateOf<List<IosTrackItem>>(emptyList()) }
	var loading by remember(albumId) { mutableStateOf(true) }
	var error by remember(albumId) { mutableStateOf<String?>(null) }

	var multiSelecting by remember(albumId) { mutableStateOf(false) }
	val selectedIds = remember(albumId) { mutableStateMapOf<String, Boolean>() }

	LaunchedEffect(albumId, refreshSeq) {
		if (tracks.isEmpty()) loading = true
		subsonicManager.fetchAlbumDetail(albumId).onSuccess { (a, t) ->
			album = a
			tracks = t
			error = null
		}.onFailure {
			error = it.message ?: "加载专辑失败"
		}
		loading = false
	}

	val isAlbumStarred = remember(album, starredIds, unstarredIds) {
		val id = album?.id ?: albumId
		(id in starredIds) || ((album?.isStarred == true) && id !in unstarredIds)
	}
	val selectedTracks by remember(tracks) {
		derivedStateOf { tracks.filter { selectedIds[it.id] == true } }
	}
	val hasOfflineRemovable by remember(selectedTracks, offlineIds, downloadingProgress) {
		derivedStateOf { selectedTracks.any { it.id in offlineIds || it.id in downloadingProgress } }
	}

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colorScheme.background),
	) {
		Column(modifier = Modifier.fillMaxSize()) {
			IosCleanTopBar(
				title = "专辑",
				onBack = onBack,
				trailing = {
					IosLibHeaderCircleButton(
						icon = if (isAlbumStarred) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
						contentDescription = if (isAlbumStarred) "取消收藏专辑" else "收藏专辑",
						active = isAlbumStarred,
						onClick = {
							scope.launch { subsonicManager.toggleAlbumStar(albumId, isAlbumStarred) }
						},
					)
				},
			)

			when {
				loading && tracks.isEmpty() -> {
					Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
						CircularProgressIndicator()
					}
				}

				error != null && tracks.isEmpty() -> {
					Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
						IosQuietEmptyState(
							message = error ?: "加载专辑失败",
							icon = Icons.Rounded.Album,
							actionLabel = "返回",
							onAction = onBack,
						)
					}
				}

				else -> {
					val headerTopColor = MaterialTheme.colorScheme.surfaceContainerHigh
					val headerBgColor = MaterialTheme.colorScheme.background
					val headerBrush = remember(headerTopColor, headerBgColor) {
						Brush.verticalGradient(
							colors = listOf(headerTopColor.copy(alpha = 0.65f), headerBgColor),
						)
					}
					val totalDurationMs = remember(tracks) { tracks.sumOf { it.durationMs } }

					LazyColumn(
						modifier = Modifier.fillMaxSize(),
						contentPadding = PaddingValues(
							top = 0.dp,
							bottom = bottomPadding.calculateBottomPadding() + if (multiSelecting) 120.dp else 20.dp,
						),
						verticalArrangement = Arrangement.spacedBy(0.dp),
					) {
						item(key = "album-hero", contentType = "header") {
							Column(
								modifier = Modifier
									.fillMaxWidth()
									.background(headerBrush)
									.padding(horizontal = 16.dp, vertical = 12.dp),
								horizontalAlignment = Alignment.CenterHorizontally,
								verticalArrangement = Arrangement.spacedBy(12.dp),
							) {
								CoverArtBox(
									coverUrl = album?.coverUrl,
									size = 164.dp,
									cornerRadius = 16.dp,
									modifier = Modifier.border(
										IosHairlineWidth,
										iosLibHairlineBorderColor(),
										RoundedCornerShape(16.dp),
									),
								)

								Column(
									horizontalAlignment = Alignment.CenterHorizontally,
									verticalArrangement = Arrangement.spacedBy(4.dp),
									modifier = Modifier.padding(horizontal = 12.dp),
								) {
									Text(
										text = album?.name ?: albumTitle,
										style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
										textAlign = TextAlign.Center,
										maxLines = 2,
										overflow = TextOverflow.Ellipsis,
									)
									val artistName = album?.artist.orEmpty()
									val artistId = album?.artistId
									Row(
										horizontalArrangement = Arrangement.spacedBy(6.dp),
										verticalAlignment = Alignment.CenterVertically,
									) {
										if (artistName.isNotBlank()) {
											Text(
												text = artistName,
												style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
												color = if (!artistId.isNullOrBlank()) {
													MaterialTheme.colorScheme.primary
												} else {
													MaterialTheme.colorScheme.onSurfaceVariant
												},
												modifier = if (!artistId.isNullOrBlank()) {
													Modifier.clickable { onOpenArtist(artistId, artistName) }
												} else {
													Modifier
												},
											)
										}
										album?.year?.takeIf { it > 0 }?.let { yr ->
											if (artistName.isNotBlank()) {
												Text("·", color = MaterialTheme.colorScheme.outline)
											}
											Text(
												text = "$yr",
												style = MaterialTheme.typography.bodyMedium.tabularNums(),
												color = MaterialTheme.colorScheme.onSurfaceVariant,
											)
										}
									}
									val durText = if (totalDurationMs > 0L) " · ${formatDurationMs(totalDurationMs)}" else ""
									Text(
										text = "共 ${tracks.size} 首$durText",
										style = MaterialTheme.typography.labelMedium.tabularNums(),
										color = MaterialTheme.colorScheme.onSurfaceVariant,
									)
								}

								DetailCollectionActionBar(
									enabled = tracks.isNotEmpty(),
									multiSelecting = multiSelecting,
									onPlayAll = { if (tracks.isNotEmpty()) audioPlayer.playQueue(tracks, 0) },
									onOfflineAll = {
										if (tracks.isNotEmpty()) {
											audioPlayer.downloadTracksOffline(tracks)
											onShowNotice(iosLibOfflineQueueNotice(tracks.size))
										}
									},
									onShuffle = { if (tracks.isNotEmpty()) audioPlayer.playQueue(tracks.shuffled(), 0) },
									onToggleMultiSelect = {
										multiSelecting = !multiSelecting
										if (!multiSelecting) selectedIds.clear()
									},
								)
							}
						}

						if (tracks.isEmpty()) {
							item(key = "album-empty", contentType = "empty") {
								IosQuietEmptyState(message = "该专辑暂无曲目", icon = Icons.Rounded.Album)
							}
						} else {
							itemsIndexed(
								items = tracks,
								key = { idx, t -> "alb-${t.id}-$idx" },
								contentType = { _, _ -> "track" },
							) { idx, track ->
								val isSelected = selectedIds[track.id] == true
								val isFirst = idx == 0
								val isLast = idx == tracks.lastIndex
								val rowShape = iosLibGroupedItemShape(isFirst, isLast)
								val rowColor = when {
									multiSelecting && isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
									currentTrackId == track.id -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
									else -> MaterialTheme.colorScheme.surfaceContainer
								}
								Surface(
									shape = rowShape,
									color = rowColor,
									modifier = Modifier
										.fillMaxWidth()
										.padding(horizontal = 16.dp),
								) {
									Column {
										IosLibTrackRowContent(
											track = track,
											isPlaying = track.id == currentTrackId,
											isStarred = (track.rawId in starredIds) || (track.isStarred && track.rawId !in unstarredIds),
											isOffline = track.id in offlineIds,
											downloadProgress = downloadingProgress[track.id],
											multiSelectMode = multiSelecting,
											isSelected = isSelected,
											trackIndex = track.trackNumber?.takeIf { it > 0 } ?: (idx + 1),
											showCover = false,
											showAlbumInSubtitle = false,
											onClick = {
												if (multiSelecting) {
													selectedIds[track.id] = !isSelected
												} else {
													audioPlayer.playQueue(tracks, idx)
												}
											},
											onLongClick = {
												multiSelecting = true
												selectedIds[track.id] = true
											},
											onPlayNext = {
												audioPlayer.playNext(track)
												onShowNotice("已设为下一首播放")
											},
											onToggleStar = { scope.launch { subsonicManager.toggleStar(track) } },
											onDownloadOffline = {
												audioPlayer.downloadTrackOffline(track)
												onShowNotice(iosLibOfflineQueueNotice(1))
											},
											onCancelOffline = { audioPlayer.cancelDownload(track.id) },
											onDeleteOffline = { audioPlayer.deleteOfflineTrack(track.id) },
											onAddToPlaylist = { onOpenAddToPlaylist(listOf(track)) },
											onRequestPhysicalDelete = onRequestPhysicalDelete?.let { fn -> { fn(track) } },
											swipeEnabled = !multiSelecting,
											swipeShape = rowShape,
											swipeContainerColor = rowColor,
											onSwipeAppendToQueue = {
												audioPlayer.appendToQueue(listOf(track))
												onShowNotice("已加入待播队尾")
											},
											onSwipePlayNext = {
												audioPlayer.playNext(track)
												onShowNotice("已设为下一首播放")
											},
										)
										if (!isLast) {
											IosHairlineDivider(startIndent = if (multiSelecting) 56.dp else 46.dp)
										}
									}
								}
							}
						}
					}
				}
			}
		}

		androidx.compose.animation.AnimatedVisibility(
			visible = multiSelecting && tracks.isNotEmpty(),
			enter = slideInVertically { it } + fadeIn(),
			exit = slideOutVertically { it } + fadeOut(),
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.padding(
					start = 16.dp,
					end = 16.dp,
					bottom = bottomPadding.calculateBottomPadding() + 8.dp,
				),
		) {
			IosLibBatchSelectionDock(
				selectedCount = selectedTracks.size,
				totalCount = tracks.size,
				hasOfflineRemovable = hasOfflineRemovable,
				onSelectAll = {
					if (selectedTracks.size >= tracks.size) selectedIds.clear()
					else tracks.forEach { selectedIds[it.id] = true }
				},
				onExit = {
					multiSelecting = false
					selectedIds.clear()
				},
				onPlaySelected = {
					if (selectedTracks.isNotEmpty()) {
						audioPlayer.playQueue(selectedTracks, 0)
						multiSelecting = false
						selectedIds.clear()
					}
				},
				onDownloadSelected = {
					if (selectedTracks.isNotEmpty()) {
						audioPlayer.downloadTracksOffline(selectedTracks)
						onShowNotice(iosLibOfflineQueueNotice(selectedTracks.size))
						multiSelecting = false
						selectedIds.clear()
					}
				},
				onAddToPlaylist = {
					if (selectedTracks.isNotEmpty()) {
						onOpenAddToPlaylist(selectedTracks)
						multiSelecting = false
						selectedIds.clear()
					}
				},
				onDeleteOfflineSelected = {
					selectedTracks.forEach { t ->
						if (t.id in downloadingProgress) audioPlayer.cancelDownload(t.id)
						if (t.id in offlineIds) audioPlayer.deleteOfflineTrack(t.id)
					}
					onShowNotice("已移除所选曲目的手机离线缓存")
					multiSelecting = false
					selectedIds.clear()
				},
			)
		}
	}
}

// =========================================================================
// 2. 原生歌单详情页
// =========================================================================

@Composable
internal fun IosLibPlaylistDetailScreen(
	playlistId: String,
	playlistName: String,
	refreshSeq: Int,
	subsonicManager: IosSubsonicManager,
	audioPlayer: IosAudioPlayer,
	currentTrackId: String?,
	starredIds: Set<String>,
	unstarredIds: Set<String>,
	offlineIds: Set<String>,
	downloadingProgress: Map<String, Float>,
	onOpenAddToPlaylist: (List<IosTrackItem>) -> Unit,
	onRequestPhysicalDelete: ((IosTrackItem) -> Unit)?,
	onShowNotice: (String) -> Unit,
	onBack: () -> Unit,
	bottomPadding: PaddingValues,
	onDeleted: (() -> Unit)? = null,
) {
	val scope = rememberCoroutineScope()
	var playlist by remember(playlistId) { mutableStateOf<IosPlaylistSummary?>(null) }
	var tracks by remember(playlistId) { mutableStateOf<List<IosTrackItem>>(emptyList()) }
	var loading by remember(playlistId) { mutableStateOf(true) }
	var showRenameDialog by remember(playlistId) { mutableStateOf(false) }
	var showDeletePlaylistConfirm by remember(playlistId) { mutableStateOf(false) }
	var showBatchRemoveConfirm by remember(playlistId) { mutableStateOf(false) }
	var actionInFlight by remember(playlistId) { mutableStateOf(false) }
	var detailNotice by remember(playlistId) { mutableStateOf<Pair<String, Boolean>?>(null) }

	var multiSelecting by remember(playlistId) { mutableStateOf(false) }
	val selectedIndices = remember(playlistId) { mutableStateMapOf<Int, Boolean>() }

	fun reload(showSpinner: Boolean = true) {
		scope.launch {
			if (showSpinner) loading = true
			subsonicManager.fetchPlaylistDetail(playlistId).onSuccess { (pl, list) ->
				playlist = pl
				tracks = list
			}.onFailure { err ->
				if (tracks.isNotEmpty()) {
					detailNotice = (err.message ?: "刷新歌单失败") to true
				}
			}
			loading = false
		}
	}

	LaunchedEffect(detailNotice) {
		if (detailNotice != null) {
			delay(3000)
			detailNotice = null
		}
	}

	LaunchedEffect(playlistId, refreshSeq) {
		multiSelecting = false
		selectedIndices.clear()
		showBatchRemoveConfirm = false
		reload(showSpinner = tracks.isEmpty())
	}

	LaunchedEffect(tracks) {
		if (tracks.isEmpty()) {
			multiSelecting = false
			selectedIndices.clear()
			showBatchRemoveConfirm = false
		} else {
			selectedIndices.keys.filter { it !in tracks.indices }.forEach { selectedIndices.remove(it) }
		}
	}

	val pickedIndices by remember(tracks) {
		derivedStateOf { tracks.indices.filter { selectedIndices[it] == true } }
	}
	val selectedTracks by remember(tracks) {
		derivedStateOf { pickedIndices.mapNotNull { tracks.getOrNull(it) } }
	}
	val hasOfflineRemovable by remember(selectedTracks, offlineIds, downloadingProgress) {
		derivedStateOf { selectedTracks.any { it.id in offlineIds || it.id in downloadingProgress } }
	}

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colorScheme.background),
	) {
		Column(modifier = Modifier.fillMaxSize()) {
			IosCleanTopBar(
				title = "歌单",
				onBack = onBack,
				trailing = {
					IosLibHeaderCircleButton(
						icon = Icons.Rounded.Edit,
						contentDescription = "重命名歌单",
						onClick = { showRenameDialog = true },
					)
					IosLibHeaderCircleButton(
						icon = Icons.Rounded.Delete,
						contentDescription = "删除歌单",
						tint = MaterialTheme.colorScheme.error,
						onClick = { showDeletePlaylistConfirm = true },
					)
				},
			)

			detailNotice?.let { (message, isError) ->
				Surface(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 16.dp, vertical = 4.dp),
					shape = RoundedCornerShape(12.dp),
					color = if (isError) {
						MaterialTheme.colorScheme.errorContainer
					} else {
						MaterialTheme.colorScheme.surfaceContainerHigh
					},
					border = BorderStroke(
						IosHairlineWidth,
						if (isError) {
							MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
						} else {
							iosLibHairlineBorderColor()
						},
					),
				) {
					Row(
						modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(8.dp),
					) {
						Text(
							text = message,
							style = MaterialTheme.typography.bodySmall,
							color = if (isError) {
								MaterialTheme.colorScheme.onErrorContainer
							} else {
								MaterialTheme.colorScheme.onSurface
							},
							modifier = Modifier.weight(1f),
							maxLines = 2,
							overflow = TextOverflow.Ellipsis,
						)
						Box(
							modifier = Modifier
								.size(22.dp)
								.clip(CircleShape)
								.clickable { detailNotice = null },
							contentAlignment = Alignment.Center,
						) {
							Icon(
								imageVector = Icons.Rounded.Close,
								contentDescription = "关闭提示",
								modifier = Modifier.size(14.dp),
								tint = if (isError) {
									MaterialTheme.colorScheme.onErrorContainer
								} else {
									MaterialTheme.colorScheme.onSurfaceVariant
								},
							)
						}
					}
				}
			}

			if (loading && tracks.isEmpty()) {
				Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
					CircularProgressIndicator()
				}
			} else {
				val headerTopColor = MaterialTheme.colorScheme.surfaceContainerHigh
				val headerBgColor = MaterialTheme.colorScheme.background
				val headerBrush = remember(headerTopColor, headerBgColor) {
					Brush.verticalGradient(
						colors = listOf(headerTopColor.copy(alpha = 0.65f), headerBgColor),
					)
				}
				val totalDurationMs = remember(tracks) { tracks.sumOf { it.durationMs } }

				LazyColumn(
					modifier = Modifier.fillMaxSize(),
					contentPadding = PaddingValues(
						top = 0.dp,
						bottom = bottomPadding.calculateBottomPadding() + if (multiSelecting) 120.dp else 20.dp,
					),
					verticalArrangement = Arrangement.spacedBy(0.dp),
				) {
					item(key = "playlist-hero", contentType = "header") {
						Column(
							modifier = Modifier
								.fillMaxWidth()
								.background(headerBrush)
								.padding(horizontal = 16.dp, vertical = 12.dp),
							horizontalAlignment = Alignment.CenterHorizontally,
							verticalArrangement = Arrangement.spacedBy(12.dp),
						) {
							CoverArtBox(
								coverUrl = playlist?.coverUrl,
								size = 156.dp,
								cornerRadius = 16.dp,
								modifier = Modifier.border(
									IosHairlineWidth,
									iosLibHairlineBorderColor(),
									RoundedCornerShape(16.dp),
								),
							)

							Column(
								horizontalAlignment = Alignment.CenterHorizontally,
								verticalArrangement = Arrangement.spacedBy(4.dp),
								modifier = Modifier.padding(horizontal = 12.dp),
							) {
								Text(
									text = playlist?.name ?: playlistName,
									style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
									textAlign = TextAlign.Center,
									maxLines = 2,
									overflow = TextOverflow.Ellipsis,
								)
								playlist?.owner?.takeIf { it.isNotBlank() }?.let { owner ->
									Text(
										text = owner,
										style = MaterialTheme.typography.bodyMedium,
										color = MaterialTheme.colorScheme.onSurfaceVariant,
									)
								}
								val durText = if (totalDurationMs > 0L) " · ${formatDurationMs(totalDurationMs)}" else ""
								Text(
									text = "共 ${tracks.size} 首$durText",
									style = MaterialTheme.typography.labelMedium.tabularNums(),
									color = MaterialTheme.colorScheme.onSurfaceVariant,
								)
							}

							DetailCollectionActionBar(
								enabled = tracks.isNotEmpty(),
								multiSelecting = multiSelecting,
								onPlayAll = { if (tracks.isNotEmpty()) audioPlayer.playQueue(tracks, 0) },
								onOfflineAll = {
									if (tracks.isNotEmpty()) {
										audioPlayer.downloadTracksOffline(tracks)
										onShowNotice(iosLibOfflineQueueNotice(tracks.size))
									}
								},
								onShuffle = { if (tracks.isNotEmpty()) audioPlayer.playQueue(tracks.shuffled(), 0) },
								onToggleMultiSelect = {
									multiSelecting = !multiSelecting
									if (!multiSelecting) selectedIndices.clear()
								},
							)
						}
					}

					if (tracks.isEmpty()) {
						item(key = "playlist-empty", contentType = "empty") {
							IosQuietEmptyState(message = "歌单暂无曲目", icon = Icons.Rounded.QueueMusic)
						}
					} else {
						itemsIndexed(
							items = tracks,
							key = { idx, t -> "pl-${t.id}-$idx" },
							contentType = { _, _ -> "track" },
						) { idx, track ->
							val isSelected = selectedIndices[idx] == true
							val isFirst = idx == 0
							val isLast = idx == tracks.lastIndex
							val rowShape = iosLibGroupedItemShape(isFirst, isLast)
							val rowColor = when {
								multiSelecting && isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
								currentTrackId == track.id -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
								else -> MaterialTheme.colorScheme.surfaceContainer
							}
							Surface(
								shape = rowShape,
								color = rowColor,
								modifier = Modifier
									.fillMaxWidth()
									.padding(horizontal = 16.dp),
							) {
								Column {
									IosLibTrackRowContent(
										track = track,
										isPlaying = track.id == currentTrackId,
										isStarred = (track.rawId in starredIds) || (track.isStarred && track.rawId !in unstarredIds),
										isOffline = track.id in offlineIds,
										downloadProgress = downloadingProgress[track.id],
										multiSelectMode = multiSelecting,
										isSelected = isSelected,
										onClick = {
											if (multiSelecting) {
												selectedIndices[idx] = !isSelected
											} else {
												audioPlayer.playQueue(tracks, idx)
											}
										},
										onLongClick = {
											multiSelecting = true
											selectedIndices[idx] = true
										},
										onPlayNext = {
											audioPlayer.playNext(track)
											onShowNotice("已设为下一首播放")
										},
										onToggleStar = { scope.launch { subsonicManager.toggleStar(track) } },
										onDownloadOffline = {
											audioPlayer.downloadTrackOffline(track)
											onShowNotice(iosLibOfflineQueueNotice(1))
										},
										onCancelOffline = { audioPlayer.cancelDownload(track.id) },
										onDeleteOffline = { audioPlayer.deleteOfflineTrack(track.id) },
										onAddToPlaylist = { onOpenAddToPlaylist(listOf(track)) },
										onRemoveFromPlaylist = {
											scope.launch {
												subsonicManager.removeSongFromPlaylist(playlistId, idx)
													.onSuccess {
														selectedIndices.clear()
														reload(showSpinner = false)
														val msg = "已从歌单移除歌曲"
														detailNotice = msg to false
														onShowNotice(msg)
													}
													.onFailure { err ->
														val msg = err.message ?: "移除歌曲失败"
														detailNotice = msg to true
														onShowNotice(msg)
													}
											}
										},
										onRequestPhysicalDelete = onRequestPhysicalDelete?.let { fn -> { fn(track) } },
										swipeEnabled = !multiSelecting,
										swipeShape = rowShape,
										swipeContainerColor = rowColor,
										onSwipeAppendToQueue = {
											audioPlayer.appendToQueue(listOf(track))
											onShowNotice("已加入待播队尾")
										},
										onSwipePlayNext = {
											audioPlayer.playNext(track)
											onShowNotice("已设为下一首播放")
										},
									)
									if (!isLast) {
										IosHairlineDivider(startIndent = if (multiSelecting) 104.dp else 66.dp)
									}
								}
							}
						}
					}
				}
			}
		}

		androidx.compose.animation.AnimatedVisibility(
			visible = multiSelecting && tracks.isNotEmpty(),
			enter = slideInVertically { it } + fadeIn(),
			exit = slideOutVertically { it } + fadeOut(),
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.padding(
					start = 16.dp,
					end = 16.dp,
					bottom = bottomPadding.calculateBottomPadding() + 8.dp,
				),
		) {
			IosLibBatchSelectionDock(
				selectedCount = pickedIndices.size,
				totalCount = tracks.size,
				hasOfflineRemovable = hasOfflineRemovable,
				onSelectAll = {
					if (pickedIndices.size >= tracks.size) selectedIndices.clear()
					else tracks.indices.forEach { selectedIndices[it] = true }
				},
				onExit = {
					multiSelecting = false
					selectedIndices.clear()
				},
				onPlaySelected = {
					if (selectedTracks.isNotEmpty()) {
						audioPlayer.playQueue(selectedTracks, 0)
						multiSelecting = false
						selectedIndices.clear()
					}
				},
				onDownloadSelected = {
					if (selectedTracks.isNotEmpty()) {
						audioPlayer.downloadTracksOffline(selectedTracks)
						onShowNotice(iosLibOfflineQueueNotice(selectedTracks.size))
						multiSelecting = false
						selectedIndices.clear()
					}
				},
				onAddToPlaylist = {
					if (selectedTracks.isNotEmpty()) {
						onOpenAddToPlaylist(selectedTracks)
						multiSelecting = false
						selectedIndices.clear()
					}
				},
				onDeleteOfflineSelected = {
					selectedTracks.forEach { t ->
						if (t.id in downloadingProgress) audioPlayer.cancelDownload(t.id)
						if (t.id in offlineIds) audioPlayer.deleteOfflineTrack(t.id)
					}
					onShowNotice("已移除所选曲目的手机离线缓存")
					multiSelecting = false
					selectedIndices.clear()
				},
				onRemoveFromPlaylistSelected = {
					if (pickedIndices.isNotEmpty()) {
						showBatchRemoveConfirm = true
					}
				},
			)
		}
	}

	if (showBatchRemoveConfirm && pickedIndices.isNotEmpty()) {
		val displayName = playlist?.name?.takeIf { it.isNotBlank() } ?: playlistName
		val removeCount = pickedIndices.size
		AlertDialog(
			onDismissRequest = { if (!actionInFlight) showBatchRemoveConfirm = false },
			icon = {
				Icon(
					imageVector = Icons.Rounded.Delete,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.error,
				)
			},
			title = { Text("移出歌单") },
			text = {
				Text("确定将选中的 $removeCount 首歌曲从「$displayName」中移出吗？仅从歌单移除，不会删除曲库源文件。")
			},
			confirmButton = {
				Button(
					onClick = {
						val targetIndices = pickedIndices.toList()
						if (targetIndices.isEmpty()) {
							showBatchRemoveConfirm = false
						} else {
							scope.launch {
								actionInFlight = true
								try {
									subsonicManager.removeSongsFromPlaylist(playlistId, targetIndices)
										.onSuccess {
											showBatchRemoveConfirm = false
											multiSelecting = false
											selectedIndices.clear()
											reload(showSpinner = false)
											val msg = "已从歌单移出 ${targetIndices.size} 首歌曲"
											detailNotice = msg to false
											onShowNotice(msg)
										}
										.onFailure { err ->
											showBatchRemoveConfirm = false
											val msg = err.message ?: "移出歌单失败"
											detailNotice = msg to true
											onShowNotice(msg)
										}
								} finally {
									actionInFlight = false
								}
							}
						}
					},
					enabled = !actionInFlight,
					colors = ButtonDefaults.buttonColors(
						containerColor = MaterialTheme.colorScheme.error,
						contentColor = MaterialTheme.colorScheme.onError,
					),
				) {
					Text(if (actionInFlight) "移出中…" else "移出")
				}
			},
			dismissButton = {
				TextButton(
					onClick = { showBatchRemoveConfirm = false },
					enabled = !actionInFlight,
				) {
					Text("取消")
				}
			},
		)
	}

	if (showDeletePlaylistConfirm) {
		val displayName = playlist?.name?.takeIf { it.isNotBlank() } ?: playlistName
		AlertDialog(
			onDismissRequest = { if (!actionInFlight) showDeletePlaylistConfirm = false },
			icon = {
				Icon(
					imageVector = Icons.Rounded.Delete,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.error,
				)
			},
			title = { Text("删除歌单") },
			text = {
				Text("确定删除歌单「$displayName」吗？此操作仅删除歌单本身，不会删除曲库源文件。")
			},
			confirmButton = {
				Button(
					onClick = {
						scope.launch {
							actionInFlight = true
							try {
								subsonicManager.deletePlaylist(playlistId)
									.onSuccess {
										showDeletePlaylistConfirm = false
										onShowNotice("已删除歌单「$displayName」")
										onDeleted?.invoke() ?: onBack()
									}
									.onFailure { err ->
										showDeletePlaylistConfirm = false
										val msg = err.message ?: "删除歌单失败"
										detailNotice = msg to true
										onShowNotice(msg)
									}
							} finally {
								actionInFlight = false
							}
						}
					},
					enabled = !actionInFlight,
					colors = ButtonDefaults.buttonColors(
						containerColor = MaterialTheme.colorScheme.error,
						contentColor = MaterialTheme.colorScheme.onError,
					),
				) {
					Text(if (actionInFlight) "删除中…" else "删除")
				}
			},
			dismissButton = {
				TextButton(
					onClick = { showDeletePlaylistConfirm = false },
					enabled = !actionInFlight,
				) {
					Text("取消")
				}
			},
		)
	}

	if (showRenameDialog) {
		var name by remember { mutableStateOf(playlist?.name ?: playlistName) }
		AlertDialog(
			onDismissRequest = { showRenameDialog = false },
			title = { Text("重命名歌单") },
			text = {
				OutlinedTextField(
					value = name,
					onValueChange = { name = it },
					singleLine = true,
					shape = RoundedCornerShape(12.dp),
					modifier = Modifier.fillMaxWidth(),
				)
			},
			confirmButton = {
				Button(
					onClick = {
						scope.launch {
							subsonicManager.renamePlaylist(playlistId, name)
								.onSuccess {
									showRenameDialog = false
									reload(showSpinner = false)
									val msg = "歌单已重命名为「${name.trim()}」"
									detailNotice = msg to false
									onShowNotice(msg)
								}
								.onFailure { err ->
									showRenameDialog = false
									val msg = err.message ?: "重命名歌单失败"
									detailNotice = msg to true
									onShowNotice(msg)
								}
						}
					},
					enabled = name.trim().isNotEmpty(),
				) {
					Text("保存")
				}
			},
			dismissButton = {
				TextButton(onClick = { showRenameDialog = false }) { Text("取消") }
			},
		)
	}
}

// =========================================================================
// 3. 原生歌手详情页（圆形头像 + 热门作品 + 全部专辑 + 相似歌手）
// =========================================================================

@Composable
internal fun IosLibArtistDetailScreen(
	artistId: String,
	artistName: String,
	subsonicManager: IosSubsonicManager,
	audioPlayer: IosAudioPlayer,
	currentTrackId: String?,
	starredIds: Set<String>,
	unstarredIds: Set<String>,
	offlineIds: Set<String>,
	downloadingProgress: Map<String, Float>,
	onOpenAlbum: (IosAlbumSummary) -> Unit,
	onOpenArtist: (IosArtistSummary) -> Unit,
	onOpenAddToPlaylist: (List<IosTrackItem>) -> Unit,
	onRequestPhysicalDelete: ((IosTrackItem) -> Unit)?,
	onShowNotice: (String) -> Unit,
	onBack: () -> Unit,
	bottomPadding: PaddingValues,
) {
	val scope = rememberCoroutineScope()
	var artist by remember(artistId) { mutableStateOf<IosArtistSummary?>(null) }
	var artistInfoImageUrl by remember(artistId) { mutableStateOf<String?>(null) }
	var albums by remember(artistId) { mutableStateOf<List<IosAlbumSummary>>(emptyList()) }
	var topSongs by remember(artistId) { mutableStateOf<List<IosTrackItem>>(emptyList()) }
	var similarArtists by remember(artistId) { mutableStateOf<List<IosArtistSummary>>(emptyList()) }
	var loading by remember(artistId) { mutableStateOf(true) }

	var multiSelecting by remember(artistId) { mutableStateOf(false) }
	val selectedIds = remember(artistId) { mutableStateMapOf<String, Boolean>() }

	LaunchedEffect(artistId) {
		loading = true
		var resolvedName = artistName
		subsonicManager.fetchArtistDetail(artistId).onSuccess { (a, list) ->
			artist = a
			albums = list
			if (a.name.isNotBlank()) resolvedName = a.name
		}
		val extra = subsonicManager.fetchArtistExtraInfo(artistId, resolvedName)
		topSongs = extra.topSongs
		similarArtists = extra.similarArtists
		artistInfoImageUrl = extra.imageUrl
		loading = false
	}

	val isArtistStarred = remember(artist, starredIds, unstarredIds) {
		val id = artist?.id ?: artistId
		(id in starredIds) || ((artist?.isStarred == true) && id !in unstarredIds)
	}
	val selectedTopSongs by remember(topSongs) {
		derivedStateOf { topSongs.filter { selectedIds[it.id] == true } }
	}
	val hasOfflineRemovable by remember(selectedTopSongs, offlineIds, downloadingProgress) {
		derivedStateOf { selectedTopSongs.any { it.id in offlineIds || it.id in downloadingProgress } }
	}

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colorScheme.background),
	) {
		Column(modifier = Modifier.fillMaxSize()) {
			IosCleanTopBar(
				title = "歌手",
				onBack = onBack,
				trailing = {
					IosLibHeaderCircleButton(
						icon = if (isArtistStarred) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
						contentDescription = if (isArtistStarred) "取消关注歌手" else "关注歌手",
						active = isArtistStarred,
						onClick = {
							scope.launch { subsonicManager.toggleArtistStar(artistId, isArtistStarred) }
						},
					)
				},
			)

			if (loading && albums.isEmpty() && topSongs.isEmpty()) {
				Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
					CircularProgressIndicator()
				}
			} else {
				val headerTopColor = MaterialTheme.colorScheme.surfaceContainerHigh
				val headerBgColor = MaterialTheme.colorScheme.background
				val headerBrush = remember(headerTopColor, headerBgColor) {
					Brush.verticalGradient(
						colors = listOf(headerTopColor.copy(alpha = 0.65f), headerBgColor),
					)
				}

				LazyColumn(
					modifier = Modifier.fillMaxSize(),
					contentPadding = PaddingValues(
						top = 0.dp,
						bottom = bottomPadding.calculateBottomPadding() + if (multiSelecting) 120.dp else 20.dp,
					),
					verticalArrangement = Arrangement.spacedBy(0.dp),
				) {
					item(key = "artist-hero", contentType = "header") {
						Column(
							modifier = Modifier
								.fillMaxWidth()
								.background(headerBrush)
								.padding(horizontal = 16.dp, vertical = 12.dp),
							horizontalAlignment = Alignment.CenterHorizontally,
							verticalArrangement = Arrangement.spacedBy(12.dp),
						) {
							IosArtistAvatar(
								artistName = artist?.name ?: artistName,
								coverUrl = artist?.coverUrl,
								artistImageUrl = artist?.artistImageUrl ?: artistInfoImageUrl,
								size = 132.dp,
								border = BorderStroke(IosHairlineWidth, iosLibHairlineBorderColor()),
							)

							Column(
								horizontalAlignment = Alignment.CenterHorizontally,
								verticalArrangement = Arrangement.spacedBy(4.dp),
							) {
								Text(
									text = artist?.name ?: artistName,
									style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
									textAlign = TextAlign.Center,
								)
								val statsText = if (topSongs.isNotEmpty()) {
									"${albums.size} 专辑 · ${topSongs.size} 首热门"
								} else {
									"${albums.size} 张专辑"
								}
								Text(
									text = statsText,
									style = MaterialTheme.typography.labelMedium.tabularNums(),
									color = MaterialTheme.colorScheme.onSurfaceVariant,
								)
							}

							if (topSongs.isNotEmpty()) {
								Row(
									modifier = Modifier.fillMaxWidth(),
									horizontalArrangement = Arrangement.spacedBy(10.dp),
								) {
									Button(
										onClick = { audioPlayer.playQueue(topSongs, 0) },
										shape = CircleShape,
										contentPadding = PaddingValues(horizontal = 12.dp),
										modifier = Modifier
											.weight(1f)
											.height(42.dp),
									) {
										Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(17.dp))
										Spacer(Modifier.width(4.dp))
										Text("播放热门", maxLines = 1, softWrap = false)
									}
									FilledTonalButton(
										onClick = {
											audioPlayer.downloadTracksOffline(topSongs)
											onShowNotice(iosLibOfflineQueueNotice(topSongs.size))
										},
										shape = CircleShape,
										contentPadding = PaddingValues(horizontal = 12.dp),
										modifier = Modifier
											.weight(1f)
											.height(42.dp),
									) {
										Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
										Spacer(Modifier.width(4.dp))
										Text("离线热门", maxLines = 1, softWrap = false)
									}
								}
							}
						}
					}

					if (topSongs.isNotEmpty()) {
						item(key = "artist-top-header", contentType = "section-header") {
							Row(
								modifier = Modifier
									.fillMaxWidth()
									.padding(horizontal = 16.dp, vertical = 10.dp),
								horizontalArrangement = Arrangement.SpaceBetween,
								verticalAlignment = Alignment.CenterVertically,
							) {
								Text(
									text = "热门作品 · ${topSongs.size}",
									style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
								)
								IosLibDockPill(
									label = if (multiSelecting) "退出" else "多选",
									icon = if (multiSelecting) Icons.Rounded.Close else Icons.Rounded.DoneAll,
									onClick = {
										multiSelecting = !multiSelecting
										if (!multiSelecting) selectedIds.clear()
									},
								)
							}
						}

						itemsIndexed(
							items = topSongs,
							key = { idx, s -> "top-${s.id}-$idx" },
							contentType = { _, _ -> "track" },
						) { idx, track ->
							val isSelected = selectedIds[track.id] == true
							val isFirst = idx == 0
							val isLast = idx == topSongs.lastIndex
							val rowShape = iosLibGroupedItemShape(isFirst, isLast)
							val rowColor = when {
								multiSelecting && isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
								currentTrackId == track.id -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
								else -> MaterialTheme.colorScheme.surfaceContainer
							}
							Surface(
								shape = rowShape,
								color = rowColor,
								modifier = Modifier
									.fillMaxWidth()
									.padding(horizontal = 16.dp),
							) {
								Column {
									IosLibTrackRowContent(
										track = track,
										isPlaying = track.id == currentTrackId,
										isStarred = (track.rawId in starredIds) || (track.isStarred && track.rawId !in unstarredIds),
										isOffline = track.id in offlineIds,
										downloadProgress = downloadingProgress[track.id],
										multiSelectMode = multiSelecting,
										isSelected = isSelected,
										trackIndex = idx + 1,
										onClick = {
											if (multiSelecting) {
												selectedIds[track.id] = !isSelected
											} else {
												audioPlayer.playQueue(topSongs, idx)
											}
										},
										onLongClick = {
											multiSelecting = true
											selectedIds[track.id] = true
										},
										onPlayNext = {
											audioPlayer.playNext(track)
											onShowNotice("已设为下一首播放")
										},
										onToggleStar = { scope.launch { subsonicManager.toggleStar(track) } },
										onDownloadOffline = {
											audioPlayer.downloadTrackOffline(track)
											onShowNotice(iosLibOfflineQueueNotice(1))
										},
										onCancelOffline = { audioPlayer.cancelDownload(track.id) },
										onDeleteOffline = { audioPlayer.deleteOfflineTrack(track.id) },
										onAddToPlaylist = { onOpenAddToPlaylist(listOf(track)) },
										onRequestPhysicalDelete = onRequestPhysicalDelete?.let { fn -> { fn(track) } },
										swipeEnabled = !multiSelecting,
										swipeShape = rowShape,
										swipeContainerColor = rowColor,
										onSwipeAppendToQueue = {
											audioPlayer.appendToQueue(listOf(track))
											onShowNotice("已加入待播队尾")
										},
										onSwipePlayNext = {
											audioPlayer.playNext(track)
											onShowNotice("已设为下一首播放")
										},
									)
									if (!isLast) {
										IosHairlineDivider(startIndent = if (multiSelecting) 104.dp else 94.dp)
									}
								}
							}
						}
					}

					if (albums.isNotEmpty()) {
						item(key = "artist-albums-header", contentType = "section-header") {
							Text(
								text = "全部专辑 · ${albums.size}",
								style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
								modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 10.dp),
							)
						}
						val rowCount = (albums.size + 1) / 2
						items(
							count = rowCount,
							key = { rowIdx -> "art-alb-row-${albums[rowIdx * 2].id}" },
							contentType = { "album-grid-row" },
						) { rowIdx ->
							val left = albums[rowIdx * 2]
							val right = albums.getOrNull(rowIdx * 2 + 1)
							Row(
								modifier = Modifier
									.fillMaxWidth()
									.padding(horizontal = 16.dp, vertical = 6.dp),
								horizontalArrangement = Arrangement.spacedBy(12.dp),
							) {
								IosLibAlbumGridTile(
									album = left,
									onClick = { onOpenAlbum(left) },
									modifier = Modifier.weight(1f),
								)
								if (right != null) {
									IosLibAlbumGridTile(
										album = right,
										onClick = { onOpenAlbum(right) },
										modifier = Modifier.weight(1f),
									)
								} else {
									Spacer(Modifier.weight(1f))
								}
							}
						}
					} else if (topSongs.isEmpty()) {
						item(key = "artist-empty", contentType = "empty") {
							IosQuietEmptyState(message = "暂无作品", icon = Icons.Rounded.Person)
						}
					}

					if (similarArtists.isNotEmpty()) {
						item(key = "artist-similar-section", contentType = "carousel") {
							Column(
								modifier = Modifier
									.fillMaxWidth()
									.padding(top = 20.dp, bottom = 8.dp),
								verticalArrangement = Arrangement.spacedBy(10.dp),
							) {
								Text(
									text = "相似歌手",
									style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
									modifier = Modifier.padding(horizontal = 16.dp),
								)
								LazyRow(
									contentPadding = PaddingValues(horizontal = 16.dp),
									horizontalArrangement = Arrangement.spacedBy(14.dp),
								) {
									items(
										items = similarArtists,
										key = { it.id },
										contentType = { "similar-artist" },
									) { sim ->
										Column(
											modifier = Modifier
												.width(76.dp)
												.clip(RoundedCornerShape(12.dp))
												.clickable { onOpenArtist(sim) },
											horizontalAlignment = Alignment.CenterHorizontally,
											verticalArrangement = Arrangement.spacedBy(6.dp),
										) {
											IosArtistAvatar(
												artistName = sim.name,
												coverUrl = sim.coverUrl,
												artistImageUrl = sim.artistImageUrl,
												size = 64.dp,
												border = BorderStroke(IosHairlineWidth, iosLibHairlineBorderColor()),
											)
											Text(
												text = sim.name,
												style = MaterialTheme.typography.labelSmall,
												textAlign = TextAlign.Center,
												maxLines = 1,
												overflow = TextOverflow.Ellipsis,
											)
										}
									}
								}
							}
						}
					}
				}
			}
		}

		androidx.compose.animation.AnimatedVisibility(
			visible = multiSelecting && topSongs.isNotEmpty(),
			enter = slideInVertically { it } + fadeIn(),
			exit = slideOutVertically { it } + fadeOut(),
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.padding(
					start = 16.dp,
					end = 16.dp,
					bottom = bottomPadding.calculateBottomPadding() + 8.dp,
				),
		) {
			IosLibBatchSelectionDock(
				selectedCount = selectedTopSongs.size,
				totalCount = topSongs.size,
				hasOfflineRemovable = hasOfflineRemovable,
				onSelectAll = {
					if (selectedTopSongs.size >= topSongs.size) selectedIds.clear()
					else topSongs.forEach { selectedIds[it.id] = true }
				},
				onExit = {
					multiSelecting = false
					selectedIds.clear()
				},
				onPlaySelected = {
					if (selectedTopSongs.isNotEmpty()) {
						audioPlayer.playQueue(selectedTopSongs, 0)
						multiSelecting = false
						selectedIds.clear()
					}
				},
				onDownloadSelected = {
					if (selectedTopSongs.isNotEmpty()) {
						audioPlayer.downloadTracksOffline(selectedTopSongs)
						onShowNotice(iosLibOfflineQueueNotice(selectedTopSongs.size))
						multiSelecting = false
						selectedIds.clear()
					}
				},
				onAddToPlaylist = {
					if (selectedTopSongs.isNotEmpty()) {
						onOpenAddToPlaylist(selectedTopSongs)
						multiSelecting = false
						selectedIds.clear()
					}
				},
				onDeleteOfflineSelected = {
					selectedTopSongs.forEach { t ->
						if (t.id in downloadingProgress) audioPlayer.cancelDownload(t.id)
						if (t.id in offlineIds) audioPlayer.deleteOfflineTrack(t.id)
					}
					onShowNotice("已移除所选曲目的手机离线缓存")
					multiSelecting = false
					selectedIds.clear()
				},
			)
		}
	}
}

// =========================================================================
// 4. 原生 Navidrome「我喜欢」详情页（共 N 首 + 三栏切换 + 全量全选）
// =========================================================================

private enum class FavSubTab(val label: String) {
	SONGS("歌曲"),
	ALBUMS("专辑"),
	ARTISTS("歌手"),
}

@Composable
internal fun IosLibNavFavoritesScreen(
	libState: IosLibraryState,
	subsonicManager: IosSubsonicManager,
	audioPlayer: IosAudioPlayer,
	currentTrackId: String?,
	offlineIds: Set<String>,
	downloadingProgress: Map<String, Float>,
	onOpenAlbum: (IosAlbumSummary) -> Unit,
	onOpenArtist: (IosArtistSummary) -> Unit,
	onOpenAddToPlaylist: (List<IosTrackItem>) -> Unit,
	onRequestPhysicalDelete: ((IosTrackItem) -> Unit)?,
	onShowNotice: (String) -> Unit,
	onBack: () -> Unit,
	bottomPadding: PaddingValues,
) {
	val scope = rememberCoroutineScope()
	var subTabName by rememberSaveable { mutableStateOf(FavSubTab.SONGS.name) }
	val subTab = remember(subTabName) {
		FavSubTab.entries.firstOrNull { it.name == subTabName } ?: FavSubTab.SONGS
	}
	var multiSelecting by remember { mutableStateOf(false) }
	val selectedIds = remember { mutableStateMapOf<String, Boolean>() }

	val songs = libState.starredSongs
	val albums = libState.starredAlbums
	val artists = libState.starredArtists

	val selectedSongs by remember(songs) {
		derivedStateOf { songs.filter { selectedIds[it.id] == true } }
	}
	val hasOfflineRemovable by remember(selectedSongs, offlineIds, downloadingProgress) {
		derivedStateOf { selectedSongs.any { it.id in offlineIds || it.id in downloadingProgress } }
	}

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colorScheme.background),
	) {
		Column(modifier = Modifier.fillMaxSize()) {
			IosCleanTopBar(
				title = "我喜欢",
				onBack = onBack,
			)

			// 三栏切换胶囊
			Surface(
				shape = CircleShape,
				color = MaterialTheme.colorScheme.surfaceContainerHigh,
				border = iosLibHairlineBorder(),
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp, vertical = 4.dp),
			) {
				Row(
					modifier = Modifier.padding(3.dp),
					horizontalArrangement = Arrangement.spacedBy(4.dp),
				) {
					FavSubTab.entries.forEach { entry ->
						val selected = subTab == entry
						Box(
							modifier = Modifier
								.weight(1f)
								.clip(CircleShape)
								.background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
								.clickable {
									subTabName = entry.name
									multiSelecting = false
									selectedIds.clear()
								}
								.padding(vertical = 7.dp),
							contentAlignment = Alignment.Center,
						) {
							Text(
								text = entry.label,
								style = MaterialTheme.typography.labelMedium.copy(
									fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
								),
								color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
								maxLines = 1,
								softWrap = false,
							)
						}
					}
				}
			}

			when (subTab) {
				FavSubTab.SONGS -> LazyColumn(
					modifier = Modifier.fillMaxSize(),
					contentPadding = PaddingValues(
						start = 16.dp,
						end = 16.dp,
						top = 8.dp,
						bottom = bottomPadding.calculateBottomPadding() + if (multiSelecting) 120.dp else 20.dp,
					),
					verticalArrangement = Arrangement.spacedBy(0.dp),
				) {
					item(key = "fav-songs-bar", contentType = "toolbar") {
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.padding(bottom = 10.dp),
							horizontalArrangement = Arrangement.spacedBy(10.dp),
							verticalAlignment = Alignment.CenterVertically,
						) {
							Text(
								text = "共 ${songs.size} 首",
								style = MaterialTheme.typography.labelLarge.tabularNums(),
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								maxLines = 1,
								softWrap = false,
							)
							if (songs.isNotEmpty()) {
								Row(
									modifier = Modifier
										.weight(1f)
										.horizontalScroll(rememberScrollState()),
									horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
									verticalAlignment = Alignment.CenterVertically,
								) {
									IosLibDockPill(
										label = "全部离线",
										icon = Icons.Rounded.CloudDownload,
										onClick = {
											audioPlayer.downloadTracksOffline(songs)
											onShowNotice(iosLibOfflineQueueNotice(songs.size))
										},
									)
									IosLibDockPill(
										label = if (multiSelecting) "退出" else "多选",
										icon = if (multiSelecting) Icons.Rounded.Close else Icons.Rounded.DoneAll,
										emphasized = multiSelecting,
										onClick = {
											multiSelecting = !multiSelecting
											if (!multiSelecting) selectedIds.clear()
										},
									)
									IosLibDockPill(
										label = "播放全部",
										icon = Icons.Rounded.PlayArrow,
										emphasized = true,
										onClick = { audioPlayer.playQueue(songs, 0) },
									)
								}
							}
						}
					}

					if (songs.isEmpty()) {
						item(key = "fav-songs-empty", contentType = "empty") {
							IosQuietEmptyState(message = "暂无喜欢的歌曲", icon = Icons.Rounded.Favorite)
						}
					} else {
						itemsIndexed(
							items = songs,
							key = { idx, s -> "fav-song-${s.id}-$idx" },
							contentType = { _, _ -> "track" },
						) { idx, track ->
							val isSelected = selectedIds[track.id] == true
							val isFirst = idx == 0
							val isLast = idx == songs.lastIndex
							val rowShape = iosLibGroupedItemShape(isFirst, isLast)
							val rowColor = when {
								multiSelecting && isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
								currentTrackId == track.id -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
								else -> MaterialTheme.colorScheme.surfaceContainer
							}
							Surface(
								shape = rowShape,
								color = rowColor,
								modifier = Modifier.fillMaxWidth(),
							) {
								Column {
									IosLibTrackRowContent(
										track = track,
										isPlaying = track.id == currentTrackId,
										isStarred = subsonicManager.isStarred(track),
										isOffline = track.id in offlineIds,
										downloadProgress = downloadingProgress[track.id],
										multiSelectMode = multiSelecting,
										isSelected = isSelected,
										onClick = {
											if (multiSelecting) {
												selectedIds[track.id] = !isSelected
											} else {
												audioPlayer.playQueue(songs, idx)
											}
										},
										onLongClick = {
											multiSelecting = true
											selectedIds[track.id] = true
										},
										onPlayNext = {
											audioPlayer.playNext(track)
											onShowNotice("已设为下一首播放")
										},
										onToggleStar = { scope.launch { subsonicManager.toggleStar(track) } },
										onDownloadOffline = {
											audioPlayer.downloadTrackOffline(track)
											onShowNotice(iosLibOfflineQueueNotice(1))
										},
										onCancelOffline = { audioPlayer.cancelDownload(track.id) },
										onDeleteOffline = { audioPlayer.deleteOfflineTrack(track.id) },
										onAddToPlaylist = { onOpenAddToPlaylist(listOf(track)) },
										onRequestPhysicalDelete = onRequestPhysicalDelete?.let { fn -> { fn(track) } },
										swipeEnabled = !multiSelecting,
										swipeShape = rowShape,
										swipeContainerColor = rowColor,
										onSwipeAppendToQueue = {
											audioPlayer.appendToQueue(listOf(track))
											onShowNotice("已加入待播队尾")
										},
										onSwipePlayNext = {
											audioPlayer.playNext(track)
											onShowNotice("已设为下一首播放")
										},
									)
									if (!isLast) {
										IosHairlineDivider(startIndent = if (multiSelecting) 104.dp else 66.dp)
									}
								}
							}
						}
					}
				}

				FavSubTab.ALBUMS -> LazyColumn(
					modifier = Modifier.fillMaxSize(),
					contentPadding = PaddingValues(
						start = 16.dp,
						end = 16.dp,
						top = 8.dp,
						bottom = bottomPadding.calculateBottomPadding() + 20.dp,
					),
					verticalArrangement = Arrangement.spacedBy(12.dp),
				) {
					if (albums.isEmpty()) {
						item(key = "fav-albums-empty", contentType = "empty") {
							IosQuietEmptyState(message = "暂无收藏专辑", icon = Icons.Rounded.Album)
						}
					} else {
						item(key = "fav-albums-count", contentType = "header") {
							Text(
								text = "共 ${albums.size} 张",
								style = MaterialTheme.typography.labelLarge.tabularNums(),
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
						val rowCount = (albums.size + 1) / 2
						items(
							count = rowCount,
							key = { rowIdx -> "fav-alb-row-${albums[rowIdx * 2].id}" },
							contentType = { "album-grid-row" },
						) { rowIdx ->
							val left = albums[rowIdx * 2]
							val right = albums.getOrNull(rowIdx * 2 + 1)
							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.spacedBy(12.dp),
							) {
								IosLibAlbumGridTile(
									album = left,
									onClick = { onOpenAlbum(left) },
									modifier = Modifier.weight(1f),
								)
								if (right != null) {
									IosLibAlbumGridTile(
										album = right,
										onClick = { onOpenAlbum(right) },
										modifier = Modifier.weight(1f),
									)
								} else {
									Spacer(Modifier.weight(1f))
								}
							}
						}
					}
				}

				FavSubTab.ARTISTS -> LazyColumn(
					modifier = Modifier.fillMaxSize(),
					contentPadding = PaddingValues(
						start = 16.dp,
						end = 16.dp,
						top = 8.dp,
						bottom = bottomPadding.calculateBottomPadding() + 20.dp,
					),
					verticalArrangement = Arrangement.spacedBy(0.dp),
				) {
					if (artists.isEmpty()) {
						item(key = "fav-artists-empty", contentType = "empty") {
							IosQuietEmptyState(message = "暂无关注歌手", icon = Icons.Rounded.Person)
						}
					} else {
						item(key = "fav-artists-count", contentType = "header") {
							Text(
								text = "共 ${artists.size} 位",
								style = MaterialTheme.typography.labelLarge.tabularNums(),
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								modifier = Modifier.padding(bottom = 10.dp),
							)
						}
						itemsIndexed(
							items = artists,
							key = { _, artist -> "fav-art-${artist.id}" },
							contentType = { _, _ -> "artist" },
						) { idx, artist ->
							val isFirst = idx == 0
							val isLast = idx == artists.lastIndex
							Surface(
								shape = iosLibGroupedItemShape(isFirst, isLast),
								color = MaterialTheme.colorScheme.surfaceContainer,
								modifier = Modifier
									.fillMaxWidth()
									.clip(iosLibGroupedItemShape(isFirst, isLast))
									.clickable { onOpenArtist(artist) },
							) {
								Column {
									Row(
										modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
										verticalAlignment = Alignment.CenterVertically,
										horizontalArrangement = Arrangement.spacedBy(12.dp),
									) {
										IosArtistAvatar(
											artistName = artist.name,
											coverUrl = artist.coverUrl,
											artistImageUrl = artist.artistImageUrl,
											size = 44.dp,
										)
										Column(modifier = Modifier.weight(1f)) {
											Text(
												text = artist.name,
												style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
												maxLines = 1,
												overflow = TextOverflow.Ellipsis,
											)
											Text(
												text = "${artist.albumCount} 张专辑",
												style = MaterialTheme.typography.bodySmall.tabularNums(),
												color = MaterialTheme.colorScheme.onSurfaceVariant,
											)
										}
										Icon(
											imageVector = Icons.Rounded.ChevronRight,
											contentDescription = null,
											tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
											modifier = Modifier.size(16.dp),
										)
									}
									if (!isLast) {
										IosHairlineDivider(startIndent = 68.dp)
									}
								}
							}
						}
					}
				}
			}
		}

		androidx.compose.animation.AnimatedVisibility(
			visible = subTab == FavSubTab.SONGS && multiSelecting && songs.isNotEmpty(),
			enter = slideInVertically { it } + fadeIn(),
			exit = slideOutVertically { it } + fadeOut(),
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.padding(
					start = 16.dp,
					end = 16.dp,
					bottom = bottomPadding.calculateBottomPadding() + 8.dp,
				),
		) {
			IosLibBatchSelectionDock(
				selectedCount = selectedSongs.size,
				totalCount = songs.size,
				hasOfflineRemovable = hasOfflineRemovable,
				onSelectAll = {
					if (selectedSongs.size >= songs.size) selectedIds.clear()
					else songs.forEach { selectedIds[it.id] = true }
				},
				onExit = {
					multiSelecting = false
					selectedIds.clear()
				},
				onPlaySelected = {
					if (selectedSongs.isNotEmpty()) {
						audioPlayer.playQueue(selectedSongs, 0)
						multiSelecting = false
						selectedIds.clear()
					}
				},
				onDownloadSelected = {
					if (selectedSongs.isNotEmpty()) {
						audioPlayer.downloadTracksOffline(selectedSongs)
						onShowNotice(iosLibOfflineQueueNotice(selectedSongs.size))
						multiSelecting = false
						selectedIds.clear()
					}
				},
				onAddToPlaylist = {
					if (selectedSongs.isNotEmpty()) {
						onOpenAddToPlaylist(selectedSongs)
						multiSelecting = false
						selectedIds.clear()
					}
				},
				onDeleteOfflineSelected = {
					selectedSongs.forEach { t ->
						if (t.id in downloadingProgress) audioPlayer.cancelDownload(t.id)
						if (t.id in offlineIds) audioPlayer.deleteOfflineTrack(t.id)
					}
					onShowNotice("已移除所选曲目的手机离线缓存")
					multiSelecting = false
					selectedIds.clear()
				},
			)
		}
	}
}

@Composable
private fun DetailCollectionActionBar(
	enabled: Boolean,
	multiSelecting: Boolean,
	onPlayAll: () -> Unit,
	onOfflineAll: () -> Unit,
	onShuffle: () -> Unit,
	onToggleMultiSelect: () -> Unit,
) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Button(
			onClick = onPlayAll,
			enabled = enabled,
			shape = CircleShape,
			contentPadding = PaddingValues(horizontal = 12.dp),
			modifier = Modifier
				.weight(1f)
				.height(42.dp),
		) {
			Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(17.dp))
			Spacer(Modifier.width(4.dp))
			Text("播放全部", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
		}

		Surface(
			shape = CircleShape,
			color = MaterialTheme.colorScheme.surfaceContainer,
			border = iosLibHairlineBorder(),
			modifier = Modifier
				.weight(1f)
				.height(42.dp)
				.clip(CircleShape)
				.clickable(enabled = enabled, onClick = onOfflineAll),
		) {
			Row(
				modifier = Modifier.padding(horizontal = 10.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.Center,
			) {
				Icon(
					imageVector = Icons.Rounded.CloudDownload,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier.size(16.dp),
				)
				Spacer(Modifier.width(4.dp))
				Text(
					text = "离线全辑",
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.onSurface,
					maxLines = 1,
					softWrap = false,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}

		Surface(
			shape = CircleShape,
			color = MaterialTheme.colorScheme.surfaceContainer,
			border = iosLibHairlineBorder(),
			modifier = Modifier
				.size(42.dp)
				.clip(CircleShape)
				.clickable(enabled = enabled, onClick = onShuffle),
		) {
			Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
				Icon(Icons.Rounded.Shuffle, contentDescription = "随机播放", modifier = Modifier.size(18.dp))
			}
		}

		Surface(
			shape = CircleShape,
			color = if (multiSelecting) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
			border = iosLibHairlineBorder(),
			modifier = Modifier
				.size(42.dp)
				.clip(CircleShape)
				.clickable(enabled = enabled, onClick = onToggleMultiSelect),
		) {
			Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
				Icon(
					imageVector = if (multiSelecting) Icons.Rounded.Close else Icons.Rounded.DoneAll,
					contentDescription = "多选",
					tint = if (multiSelecting) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
					modifier = Modifier.size(18.dp),
				)
			}
		}
	}
}

// =========================================================================
// 5. 猜你喜欢抽屉与共享每日推荐页面（保留既有公开签名 `IosDailyMixSheet`）
// =========================================================================

private data class IosDailyDateParts(
	val month: Int,
	val day: Int,
	val weekday: String,
)

private data class IosDailyDisplayRow(
	val key: String,
	val iosTrack: IosTrackItem,
	val meloItem: MeloDailyRecommendItem? = null,
)

private fun parseDailyDateParts(rawDate: String): IosDailyDateParts {
	val clean = rawDate.trim().ifBlank {
		NSDateFormatter().apply { dateFormat = "yyyy-MM-dd" }.stringFromDate(NSDate())
	}
	val parts = clean.split('-')
	val year = parts.getOrNull(0)?.toIntOrNull() ?: 0
	val month = (parts.getOrNull(1)?.toIntOrNull() ?: 1).coerceIn(1, 12)
	val day = (parts.getOrNull(2)?.toIntOrNull() ?: 1).coerceIn(1, 31)
	return IosDailyDateParts(
		month = month,
		day = day,
		weekday = computeDailyWeekdayLabel(year, month, day),
	)
}

private fun computeDailyWeekdayLabel(year: Int, month: Int, day: Int): String {
	if (year < 1900 || month !in 1..12 || day !in 1..31) return ""
	val offsets = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
	val y = if (month < 3) year - 1 else year
	val w = (y + y / 4 - y / 100 + y / 400 + offsets[month - 1] + day) % 7
	return when (w) {
		0 -> "周日"
		1 -> "周一"
		2 -> "周二"
		3 -> "周三"
		4 -> "周四"
		5 -> "周五"
		6 -> "周六"
		else -> ""
	}
}

private fun buildDailyRecommendSeeds(
	starredSongs: List<IosTrackItem>,
	localFavorites: List<MeloTrack>,
): List<MeloRecommendSeed> {
	val seen = HashSet<String>()
	val out = mutableListOf<MeloRecommendSeed>()

	fun appendSeed(title: String, artist: String, album: String, genre: String, weight: Double) {
		val cleanTitle = title.trim()
		if (cleanTitle.isEmpty()) return
		val key = "${cleanTitle.lowercase()}|${artist.trim().lowercase()}"
		if (!seen.add(key)) return
		out.add(
			MeloRecommendSeed(
				title = cleanTitle,
				artist = artist.trim(),
				album = album.trim(),
				genre = genre.trim(),
				weight = weight,
			)
		)
	}

	localFavorites.take(15).forEach { track ->
		appendSeed(track.title, track.artist, track.album, "", 1.2)
	}
	starredSongs.take(15).forEach { song ->
		appendSeed(song.title, song.artist, song.album, song.genre.orEmpty(), 1.0)
	}
	return out.take(24)
}

private fun canEnqueueDailyTrackToLibrary(
	item: MeloDailyRecommendItem,
	enqueuedIds: Set<String>,
): Boolean {
	if (item.inLibrary) return false
	val id = item.track.id.trim()
	if (id.isEmpty() || id in enqueuedIds) return false
	val src = item.track.source.trim().lowercase()
	return src != "local" && src != "navidrome" && src != "device"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IosDailyMixSheet(
	libState: IosLibraryState,
	audioPlayer: IosAudioPlayer,
	subsonicManager: IosSubsonicManager,
	onDismiss: () -> Unit,
	dailyRecommendController: MeloDailyRecommendController? = null,
) {
	ModalBottomSheet(
		onDismissRequest = onDismiss,
		shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
		containerColor = MaterialTheme.colorScheme.surface,
	) {
		IosDailyRecommendContent(
			libState = libState,
			audioPlayer = audioPlayer,
			subsonicManager = subsonicManager,
			dailyRecommendController = dailyRecommendController,
			nestedInSheet = true,
			onBackOrDismiss = onDismiss,
			fallbackTracks = libState.dailyMixTracks,
			fallbackNote = libState.dailyMixNote,
			fallbackLoading = libState.dailyMixLoading,
			fallbackAiCurating = libState.aiCurating,
			onFallbackInitialLoad = {
				if (libState.dailyMixTracks.isEmpty()) {
					subsonicManager.loadOrGenerateDailyMix(forceRegenerate = false)
				}
			},
			onFallbackRefresh = {
				subsonicManager.loadOrGenerateDailyMix(forceRegenerate = true)
			},
			onFallbackAiCurate = { subsonicManager.triggerAiCurate() },
		)
	}
}

@Composable
internal fun IosDailyRecommendContent(
	libState: IosLibraryState,
	audioPlayer: IosAudioPlayer,
	subsonicManager: IosSubsonicManager,
	dailyRecommendController: MeloDailyRecommendController?,
	nestedInSheet: Boolean,
	onBackOrDismiss: () -> Unit,
	modifier: Modifier = Modifier,
	bottomPadding: PaddingValues = PaddingValues(0.dp),
	localFavorites: List<MeloTrack> = emptyList(),
	localFavoriteIds: Set<String> = emptySet(),
	onToggleLocalFavorite: ((MeloTrack) -> Unit)? = null,
	onAddToLocalPlaylist: ((List<String>) -> Unit)? = null,
	fallbackTracks: List<IosTrackItem> = libState.dailyMixTracks,
	fallbackMeloTracksById: Map<String, MeloTrack> = emptyMap(),
	fallbackNote: String = libState.dailyMixNote,
	fallbackLoading: Boolean = libState.dailyMixLoading,
	fallbackAiCurating: Boolean = libState.aiCurating,
	onFallbackInitialLoad: (() -> Unit)? = null,
	onFallbackRefresh: () -> Unit = { subsonicManager.loadOrGenerateDailyMix(forceRegenerate = true) },
	onFallbackAiCurate: (() -> Unit)? = null,
	onShowNotice: ((String) -> Unit)? = null,
) {
	val meloConfig by subsonicManager.meloApiClient.settingsStore.config.collectAsState()
	val useMeloDaily = meloConfig.isAuthenticated && dailyRecommendController != null
	val emptyDailyFlow = remember { MutableStateFlow(MeloDailyRecommendUiState()) }
	val dailyState by (dailyRecommendController?.state ?: emptyDailyFlow).collectAsState()

	val playerState by audioPlayer.state.collectAsState()
	val scope = rememberCoroutineScope()
	var localNotice by remember { mutableStateOf<String?>(null) }
	var multiSelecting by remember { mutableStateOf(false) }
	val selectedIds = remember { mutableStateMapOf<String, Boolean>() }

	val latestLocalFavorites by rememberUpdatedState(localFavorites)
	val buildSeeds: suspend () -> List<MeloRecommendSeed> = {
		buildDailyRecommendSeeds(
			starredSongs = subsonicManager.state.value.starredSongs,
			localFavorites = latestLocalFavorites,
		)
	}

	LaunchedEffect(useMeloDaily, dailyRecommendController) {
		if (useMeloDaily && dailyRecommendController != null) {
			val cur = dailyRecommendController.state.value
			if (cur.items.isEmpty() && !cur.loading && !cur.streaming) {
				dailyRecommendController.loadOrGenerate(
					force = false,
					seedsProvider = buildSeeds,
				)
			}
		} else {
			onFallbackInitialLoad?.invoke()
		}
	}

	val displayRows = remember(useMeloDaily, dailyState.items, fallbackTracks) {
		if (useMeloDaily) {
			dailyState.items.mapIndexed { idx, item ->
				val mapped = subsonicManager.meloTrackToIosTrack(item.track)
				IosDailyDisplayRow(
					key = "melo-${item.track.id.ifBlank { mapped.id }}-$idx",
					iosTrack = mapped,
					meloItem = item,
				)
			}
		} else {
			fallbackTracks.mapIndexed { idx, track ->
				IosDailyDisplayRow(
					key = "fallback-${track.id}-$idx",
					iosTrack = track,
					meloItem = null,
				)
			}
		}
	}
	val tracks = remember(displayRows) { displayRows.map { it.iosTrack } }

	val offlineIds = playerState.offlineTracks.keys
	val downloadingProgress = playerState.downloadingProgress
	val selectedRows by remember(displayRows) {
		derivedStateOf { displayRows.filter { selectedIds[it.iosTrack.id] == true } }
	}
	val selectedTracks by remember(selectedRows) {
		derivedStateOf { selectedRows.map { it.iosTrack } }
	}
	val hasOfflineRemovable by remember(selectedTracks, offlineIds, downloadingProgress) {
		derivedStateOf { selectedTracks.any { it.id in offlineIds || it.id in downloadingProgress } }
	}
	val selectedImportableTracks by remember(selectedRows, dailyState.enqueuedTrackIds) {
		derivedStateOf {
			selectedRows.mapNotNull { row ->
				val item = row.meloItem ?: return@mapNotNull null
				if (canEnqueueDailyTrackToLibrary(item, dailyState.enqueuedTrackIds)) item.track else null
			}
		}
	}
	val batchImportTracks = remember(useMeloDaily, dailyState.items, dailyState.enqueuedTrackIds) {
		if (!useMeloDaily) {
			emptyList()
		} else {
			dailyState.items
				.filter { canEnqueueDailyTrackToLibrary(it, dailyState.enqueuedTrackIds) }
				.map { it.track }
		}
	}

	LaunchedEffect(tracks) {
		if (tracks.isEmpty()) {
			multiSelecting = false
			selectedIds.clear()
		} else {
			val validIds = tracks.mapTo(HashSet()) { it.id }
			selectedIds.keys.filter { it !in validIds }.forEach { selectedIds.remove(it) }
		}
	}

	LaunchedEffect(localNotice) {
		if (localNotice != null) {
			delay(3200)
			localNotice = null
		}
	}

	LaunchedEffect(dailyState.noticeMessage) {
		if (!dailyState.noticeMessage.isNullOrBlank()) {
			delay(3200)
			dailyRecommendController?.clearNotice()
		}
	}

	val emitNotice: (String) -> Unit = { msg ->
		localNotice = msg
		onShowNotice?.invoke(msg)
	}

	val isLoading = if (useMeloDaily) dailyState.loading else fallbackLoading
	val isStreaming = useMeloDaily && dailyState.streaming
	val activeNote = if (useMeloDaily) dailyState.curatorNote else fallbackNote
	val dateParts = remember(useMeloDaily, dailyState.date) {
		parseDailyDateParts(if (useMeloDaily) dailyState.date else "")
	}

	val activeError = if (useMeloDaily) dailyState.errorMessage?.takeIf { it.isNotBlank() } else null
	val activeNotice = localNotice?.takeIf { it.isNotBlank() }
		?: if (useMeloDaily) dailyState.noticeMessage?.takeIf { it.isNotBlank() } else null
	val bannerText = activeError ?: activeNotice
	val bannerIsError = activeError != null

	Box(
		modifier = modifier
			.fillMaxWidth()
			.then(if (nestedInSheet) Modifier.navigationBarsPadding() else Modifier.fillMaxSize()),
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.then(if (nestedInSheet) Modifier else Modifier.fillMaxSize()),
		) {
			if (nestedInSheet) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.SpaceBetween,
				) {
					Text(
						text = "每日推荐",
						style = MaterialTheme.typography.titleLarge,
						fontWeight = FontWeight.Bold,
					)
					IconButton(onClick = onBackOrDismiss, modifier = Modifier.size(34.dp)) {
						Icon(Icons.Rounded.Close, contentDescription = "关闭", modifier = Modifier.size(18.dp))
					}
				}
			} else {
				IosCleanTopBar(
					title = "每日推荐",
					onBack = onBackOrDismiss,
					trailing = {
						if (useMeloDaily && isStreaming) {
							IosLibHeaderCircleButton(
								icon = Icons.Rounded.Close,
								contentDescription = "停止生成",
								active = true,
								onClick = { dailyRecommendController?.stopGeneration() },
							)
						} else {
							IosLibHeaderCircleButton(
								icon = Icons.Rounded.Refresh,
								contentDescription = "换一批",
								onClick = {
									if (useMeloDaily) {
										if (!isLoading) {
											dailyRecommendController?.loadOrGenerate(
												force = true,
												seedsProvider = buildSeeds,
											)
										}
									} else if (!isLoading) {
										onFallbackRefresh()
									}
								},
							)
						}
					},
				)
			}

			val listContentBottom = if (nestedInSheet) {
				if (multiSelecting) 124.dp else 18.dp
			} else {
				bottomPadding.calculateBottomPadding() + if (multiSelecting) 124.dp else 20.dp
			}
			val listModifier = if (nestedInSheet) {
				Modifier
					.fillMaxWidth()
					.heightIn(max = 560.dp)
			} else {
				Modifier
					.weight(1f)
					.fillMaxWidth()
			}

			LazyColumn(
				modifier = listModifier,
				contentPadding = PaddingValues(
					start = 16.dp,
					end = 16.dp,
					top = if (nestedInSheet) 4.dp else 6.dp,
					bottom = listContentBottom,
				),
				verticalArrangement = Arrangement.spacedBy(0.dp),
			) {
				item(key = "daily-hero-card", contentType = "hero") {
					IosDailyRecommendHeroCard(
						dateParts = dateParts,
						useMeloDaily = useMeloDaily,
						refreshSeq = dailyState.refreshSeq,
						mode = dailyState.mode,
						styleTags = dailyState.styleTags,
						curatorNote = activeNote,
						itemCount = displayRows.size,
						targetCount = dailyState.targetCount,
						loading = isLoading,
						streaming = isStreaming,
						stageMessage = dailyState.stageMessage,
						matchedCount = dailyState.matchedCount,
						fallbackAiCurating = fallbackAiCurating,
						onFallbackAiCurate = if (!useMeloDaily && tracks.isNotEmpty()) onFallbackAiCurate else null,
					)
					Spacer(Modifier.height(10.dp))
				}

				if (bannerText != null) {
					item(key = "daily-banner", contentType = "banner") {
						IosDailyRecommendBanner(
							text = bannerText,
							isError = bannerIsError,
							onDismiss = {
								if (bannerIsError) {
									dailyRecommendController?.clearError()
								} else {
									localNotice = null
									dailyRecommendController?.clearNotice()
								}
							},
						)
						Spacer(Modifier.height(10.dp))
					}
				}

				item(key = "daily-actions", contentType = "actions") {
					IosDailyRecommendActionBar(
						hasTracks = tracks.isNotEmpty(),
						useMeloDaily = useMeloDaily,
						isLoading = isLoading,
						isStreaming = isStreaming,
						multiSelecting = multiSelecting,
						importableCount = batchImportTracks.size,
						onPlayAll = { if (tracks.isNotEmpty()) audioPlayer.playQueue(tracks, 0) },
						onRefreshOrStop = {
							if (useMeloDaily) {
								if (isStreaming) {
									dailyRecommendController?.stopGeneration()
								} else {
									dailyRecommendController?.loadOrGenerate(
										force = true,
										seedsProvider = buildSeeds,
									)
								}
							} else {
								onFallbackRefresh()
							}
						},
						onBatchImport = {
							if (batchImportTracks.isNotEmpty()) {
								dailyRecommendController?.enqueueDownloadToLibrary(batchImportTracks)
							}
						},
						onOfflineAll = {
							if (tracks.isNotEmpty()) {
								audioPlayer.downloadTracksOffline(tracks)
								emitNotice(iosLibOfflineQueueNotice(tracks.size))
							}
						},
						onToggleMultiSelect = {
							multiSelecting = !multiSelecting
							if (!multiSelecting) selectedIds.clear()
						},
					)
					Spacer(Modifier.height(12.dp))
				}

				if (displayRows.isEmpty() && (isLoading || isStreaming)) {
					item(key = "daily-loading", contentType = "loading") {
						Box(
							modifier = Modifier
								.fillMaxWidth()
								.height(200.dp),
							contentAlignment = Alignment.Center,
						) {
							CircularProgressIndicator()
						}
					}
				} else if (displayRows.isEmpty()) {
					item(key = "daily-empty", contentType = "empty") {
						IosQuietEmptyState(
							message = "暂无推荐曲目",
							icon = Icons.Rounded.AutoAwesome,
							actionLabel = "换一批",
							onAction = {
								if (useMeloDaily) {
									dailyRecommendController?.loadOrGenerate(
										force = true,
										seedsProvider = buildSeeds,
									)
								} else {
									onFallbackRefresh()
								}
							},
						)
					}
				} else {
					itemsIndexed(
						items = displayRows,
						key = { _, row -> row.key },
						contentType = { _, row -> if (row.meloItem != null) "daily-melo-track" else "daily-fallback-track" },
					) { idx, row ->
						val track = row.iosTrack
						val meloItem = row.meloItem
						val isSelected = selectedIds[track.id] == true
						val isFirst = idx == 0
						val isLast = idx == displayRows.lastIndex
						val rowShape = iosLibGroupedItemShape(isFirst, isLast)
						val rowColor = when {
							multiSelecting && isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
							track.id == playerState.currentTrack?.id -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
							else -> MaterialTheme.colorScheme.surfaceContainer
						}

						Surface(
							shape = rowShape,
							color = rowColor,
							modifier = Modifier.fillMaxWidth(),
						) {
							Column {
								if (meloItem != null) {
									val trackId = meloItem.track.id.trim()
									val isEnqueued = trackId.isNotEmpty() && trackId in dailyState.enqueuedTrackIds
									val canEnqueue = canEnqueueDailyTrackToLibrary(meloItem, dailyState.enqueuedTrackIds)
									val canLocalFavorite = meloItem.inLibrary &&
										trackId.isNotEmpty() &&
										meloItem.track.source.lowercase() != "navidrome" &&
										onToggleLocalFavorite != null
									val canLocalPlaylist = meloItem.inLibrary &&
										trackId.isNotEmpty() &&
										meloItem.track.source.lowercase() != "navidrome" &&
										onAddToLocalPlaylist != null

									IosDailyRecommendTrackRow(
										item = meloItem,
										track = track,
										trackIndex = idx + 1,
										isPlaying = track.id == playerState.currentTrack?.id,
										isOffline = track.id in offlineIds,
										downloadProgress = downloadingProgress[track.id],
										multiSelectMode = multiSelecting,
										isSelected = isSelected,
										isEnqueued = isEnqueued,
										canEnqueue = canEnqueue,
										isStarred = if (canLocalFavorite) trackId in localFavoriteIds else false,
										onClick = {
											if (multiSelecting) {
												selectedIds[track.id] = !isSelected
											} else {
												audioPlayer.playQueue(tracks, idx)
											}
										},
										onLongClick = {
											multiSelecting = true
											selectedIds[track.id] = true
										},
										onEnqueueToLibrary = {
											dailyRecommendController?.enqueueDownloadToLibrary(listOf(meloItem.track))
										},
										onPlayNext = {
											audioPlayer.playNext(track)
											emitNotice("已设为下一首播放")
										},
										onAppendToQueue = {
											audioPlayer.appendToQueue(listOf(track))
											emitNotice("已加入待播队尾")
										},
										onDownloadOffline = {
											audioPlayer.downloadTrackOffline(track)
											emitNotice(iosLibOfflineQueueNotice(1))
										},
										onCancelOffline = { audioPlayer.cancelDownload(track.id) },
										onDeleteOffline = { audioPlayer.deleteOfflineTrack(track.id) },
										onToggleFavorite = if (canLocalFavorite) {
											{ onToggleLocalFavorite?.invoke(meloItem.track) }
										} else {
											null
										},
										onAddToPlaylist = if (canLocalPlaylist) {
											{ onAddToLocalPlaylist?.invoke(listOf(trackId)) }
										} else {
											null
										},
										swipeEnabled = !multiSelecting,
										swipeShape = rowShape,
										swipeContainerColor = rowColor,
									)
								} else {
									val fallbackMelo = fallbackMeloTracksById[track.id]
									val useLocalFav = track.sourceType != IosTrackSource.NAVIDROME &&
										fallbackMelo != null &&
										onToggleLocalFavorite != null &&
										fallbackMelo.id.isNotBlank()
									val isStarred = when {
										track.sourceType == IosTrackSource.NAVIDROME -> subsonicManager.isStarred(track)
										useLocalFav -> fallbackMelo!!.id in localFavoriteIds
										else -> false
									}
									val showStarBtn = track.sourceType == IosTrackSource.NAVIDROME || useLocalFav
									val canAddPlaylist = onAddToLocalPlaylist != null &&
										fallbackMelo != null &&
										fallbackMelo.id.isNotBlank()

									IosLibTrackRowContent(
										track = track,
										isPlaying = track.id == playerState.currentTrack?.id,
										isStarred = isStarred,
										isOffline = track.id in offlineIds,
										downloadProgress = downloadingProgress[track.id],
										multiSelectMode = multiSelecting,
										isSelected = isSelected,
										trackIndex = idx + 1,
										showStarButton = showStarBtn,
										onClick = {
											if (multiSelecting) {
												selectedIds[track.id] = !isSelected
											} else {
												audioPlayer.playQueue(tracks, idx)
											}
										},
										onLongClick = {
											multiSelecting = true
											selectedIds[track.id] = true
										},
										onPlayNext = {
											audioPlayer.playNext(track)
											emitNotice("已设为下一首播放")
										},
										onToggleStar = {
											if (track.sourceType == IosTrackSource.NAVIDROME) {
												scope.launch { subsonicManager.toggleStar(track) }
											} else if (useLocalFav && fallbackMelo != null) {
												onToggleLocalFavorite?.invoke(fallbackMelo)
											}
										},
										onDownloadOffline = {
											audioPlayer.downloadTrackOffline(track)
											emitNotice(iosLibOfflineQueueNotice(1))
										},
										onCancelOffline = { audioPlayer.cancelDownload(track.id) },
										onDeleteOffline = { audioPlayer.deleteOfflineTrack(track.id) },
										onAddToPlaylist = if (canAddPlaylist) {
											{ onAddToLocalPlaylist?.invoke(listOf(fallbackMelo!!.id)) }
										} else {
											null
										},
										onRequestPhysicalDelete = null,
										swipeEnabled = !multiSelecting,
										swipeShape = rowShape,
										swipeContainerColor = rowColor,
										onSwipeAppendToQueue = {
											audioPlayer.appendToQueue(listOf(track))
											emitNotice("已加入待播队尾")
										},
										onSwipePlayNext = {
											audioPlayer.playNext(track)
											emitNotice("已设为下一首播放")
										},
									)
								}
								if (!isLast) {
									IosHairlineDivider(startIndent = if (multiSelecting) 132.dp else 94.dp)
								}
							}
						}
					}
				}
			}
		}

		AnimatedVisibility(
			visible = multiSelecting && tracks.isNotEmpty(),
			enter = slideInVertically { it } + fadeIn(),
			exit = slideOutVertically { it } + fadeOut(),
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.padding(
					start = 16.dp,
					end = 16.dp,
					bottom = if (nestedInSheet) 12.dp else bottomPadding.calculateBottomPadding() + 8.dp,
				),
		) {
			IosLibBatchSelectionDock(
				selectedCount = selectedTracks.size,
				totalCount = tracks.size,
				hasOfflineRemovable = hasOfflineRemovable,
				onSelectAll = {
					if (selectedTracks.size >= tracks.size) selectedIds.clear()
					else tracks.forEach { selectedIds[it.id] = true }
				},
				onExit = {
					multiSelecting = false
					selectedIds.clear()
				},
				onPlaySelected = {
					if (selectedTracks.isNotEmpty()) {
						audioPlayer.playQueue(selectedTracks, 0)
						multiSelecting = false
						selectedIds.clear()
					}
				},
				onDownloadSelected = {
					if (selectedTracks.isNotEmpty()) {
						audioPlayer.downloadTracksOffline(selectedTracks)
						emitNotice(iosLibOfflineQueueNotice(selectedTracks.size))
						multiSelecting = false
						selectedIds.clear()
					}
				},
				onAddToPlaylist = null,
				onDeleteOfflineSelected = {
					selectedTracks.forEach { t ->
						if (t.id in downloadingProgress) audioPlayer.cancelDownload(t.id)
						if (t.id in offlineIds) audioPlayer.deleteOfflineTrack(t.id)
					}
					emitNotice("已移除所选曲目的手机离线缓存")
					multiSelecting = false
					selectedIds.clear()
				},
				onDownloadToLibrarySelected = if (useMeloDaily && selectedImportableTracks.isNotEmpty()) {
					{
						dailyRecommendController?.enqueueDownloadToLibrary(selectedImportableTracks)
						multiSelecting = false
						selectedIds.clear()
					}
				} else {
					null
				},
			)
		}
	}
}

@Composable
private fun IosDailyRecommendHeroCard(
	dateParts: IosDailyDateParts,
	useMeloDaily: Boolean,
	refreshSeq: Int,
	mode: String,
	styleTags: List<String>,
	curatorNote: String,
	itemCount: Int,
	targetCount: Int,
	loading: Boolean,
	streaming: Boolean,
	stageMessage: String,
	matchedCount: Int,
	fallbackAiCurating: Boolean,
	onFallbackAiCurate: (() -> Unit)?,
) {
	IosGroupedCard {
		Column(
			modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(12.dp),
			) {
				// 网易云风格日历日期角标
				Surface(
					shape = RoundedCornerShape(14.dp),
					color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
					border = BorderStroke(IosHairlineWidth, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
					modifier = Modifier.size(width = 56.dp, height = 58.dp),
				) {
					Column(
						modifier = Modifier.fillMaxSize().padding(vertical = 5.dp),
						horizontalAlignment = Alignment.CenterHorizontally,
						verticalArrangement = Arrangement.Center,
					) {
						Text(
							text = "${dateParts.month}月",
							style = MaterialTheme.typography.labelSmall.copy(
								fontSize = 10.sp,
								fontWeight = FontWeight.SemiBold,
							),
							color = MaterialTheme.colorScheme.primary,
							maxLines = 1,
						)
						Text(
							text = dateParts.day.toString().padStart(2, '0'),
							style = MaterialTheme.typography.titleLarge.copy(
								fontSize = 23.sp,
								fontWeight = FontWeight.Bold,
								lineHeight = 25.sp,
							).tabularNums(),
							color = MaterialTheme.colorScheme.onPrimaryContainer,
							maxLines = 1,
						)
					}
				}

				Column(
					modifier = Modifier.weight(1f),
					verticalArrangement = Arrangement.spacedBy(4.dp),
				) {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(6.dp),
					) {
						Text(
							text = if (useMeloDaily) "每日全网推荐" else "每日猜你喜欢",
							style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
						if (useMeloDaily) {
							val normalizedMode = mode.trim().lowercase()
							if (normalizedMode == "ai" || normalizedMode == "rule") {
								val isAi = normalizedMode == "ai"
								Surface(
									shape = CircleShape,
									color = if (isAi) {
										MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
									} else {
										MaterialTheme.colorScheme.surfaceContainerHigh
									},
									border = BorderStroke(
										IosHairlineWidth,
										if (isAi) {
											MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
										} else {
											iosLibHairlineBorderColor()
										},
									),
								) {
									Row(
										modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
										verticalAlignment = Alignment.CenterVertically,
										horizontalArrangement = Arrangement.spacedBy(3.dp),
									) {
										if (isAi) {
											Icon(
												imageVector = Icons.Rounded.AutoAwesome,
												contentDescription = null,
												tint = MaterialTheme.colorScheme.primary,
												modifier = Modifier.size(11.dp),
											)
										}
										Text(
											text = if (isAi) "AI 推荐" else "规则推荐",
											style = MaterialTheme.typography.labelSmall.copy(
												fontSize = 10.sp,
												fontWeight = FontWeight.SemiBold,
											),
											color = if (isAi) {
												MaterialTheme.colorScheme.primary
											} else {
												MaterialTheme.colorScheme.onSurfaceVariant
											},
											maxLines = 1,
										)
									}
								}
							}
						}
					}

					val countSummary = if (useMeloDaily && (streaming || loading)) {
						"${itemCount}/${targetCount} 首"
					} else {
						"共 $itemCount 首"
					}
					val batchText = if (useMeloDaily && refreshSeq > 1) " · 第 $refreshSeq 批" else ""
					val weekdayText = if (dateParts.weekday.isNotBlank()) " · ${dateParts.weekday}" else ""
					Text(
						text = "$countSummary$batchText$weekdayText",
						style = MaterialTheme.typography.labelMedium.tabularNums(),
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}

				if (onFallbackAiCurate != null) {
					FilledTonalButton(
						onClick = onFallbackAiCurate,
						enabled = !fallbackAiCurating,
						shape = CircleShape,
						contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
						modifier = Modifier.height(30.dp),
					) {
						if (fallbackAiCurating) {
							CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.6.dp)
							Spacer(Modifier.width(4.dp))
							Text("策展中…", fontSize = 11.sp)
						} else {
							Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(13.dp))
							Spacer(Modifier.width(4.dp))
							Text("AI 策展", fontSize = 11.sp)
						}
					}
				}
			}

			if (useMeloDaily && styleTags.isNotEmpty()) {
				FlowRow(
					horizontalArrangement = Arrangement.spacedBy(6.dp),
					verticalArrangement = Arrangement.spacedBy(6.dp),
				) {
					styleTags.take(6).forEach { rawTag ->
						val clean = rawTag.trim().removePrefix("#")
						if (clean.isNotEmpty()) {
							Surface(
								shape = CircleShape,
								color = MaterialTheme.colorScheme.surfaceContainerHigh,
								border = iosLibHairlineBorder(),
							) {
								Text(
									text = "#$clean",
									style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
									color = MaterialTheme.colorScheme.primary,
									maxLines = 1,
									modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
								)
							}
						}
					}
				}
			}

			if (curatorNote.isNotBlank()) {
				Text(
					text = curatorNote,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurface,
				)
			}

			if (useMeloDaily && (loading || streaming)) {
				Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.SpaceBetween,
						verticalAlignment = Alignment.CenterVertically,
					) {
						Text(
							text = stageMessage.ifBlank {
								if (loading) "正在启动今日推荐…" else "正在全网匹配曲目…"
							},
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
							modifier = Modifier.weight(1f),
						)
						Spacer(Modifier.width(8.dp))
						Text(
							text = "$matchedCount/$targetCount",
							style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold).tabularNums(),
							color = MaterialTheme.colorScheme.primary,
						)
					}
					if (streaming && targetCount > 0 && matchedCount > 0) {
						LinearProgressIndicator(
							progress = { (matchedCount.toFloat() / targetCount.toFloat()).coerceIn(0.03f, 1f) },
							modifier = Modifier
								.fillMaxWidth()
								.height(3.dp)
								.clip(CircleShape),
						)
					} else {
						LinearProgressIndicator(
							modifier = Modifier
								.fillMaxWidth()
								.height(3.dp)
								.clip(CircleShape),
						)
					}
				}
			}
		}
	}
}

@Composable
private fun IosDailyRecommendBanner(
	text: String,
	isError: Boolean,
	onDismiss: () -> Unit,
) {
	Surface(
		shape = RoundedCornerShape(12.dp),
		color = if (isError) {
			MaterialTheme.colorScheme.errorContainer
		} else {
			MaterialTheme.colorScheme.surfaceContainerHigh
		},
		border = BorderStroke(
			IosHairlineWidth,
			if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.35f) else iosLibHairlineBorderColor(),
		),
		modifier = Modifier.fillMaxWidth(),
	) {
		Row(
			modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Text(
				text = text,
				style = MaterialTheme.typography.bodySmall,
				color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
				modifier = Modifier.weight(1f),
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
			Box(
				modifier = Modifier
					.size(22.dp)
					.clip(CircleShape)
					.clickable(onClick = onDismiss),
				contentAlignment = Alignment.Center,
			) {
				Icon(
					imageVector = Icons.Rounded.Close,
					contentDescription = "关闭提示",
					modifier = Modifier.size(14.dp),
					tint = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

@Composable
private fun IosDailyRecommendActionBar(
	hasTracks: Boolean,
	useMeloDaily: Boolean,
	isLoading: Boolean,
	isStreaming: Boolean,
	multiSelecting: Boolean,
	importableCount: Int,
	onPlayAll: () -> Unit,
	onRefreshOrStop: () -> Unit,
	onBatchImport: () -> Unit,
	onOfflineAll: () -> Unit,
	onToggleMultiSelect: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.horizontalScroll(rememberScrollState()),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Button(
			onClick = onPlayAll,
			enabled = hasTracks,
			shape = CircleShape,
			contentPadding = PaddingValues(horizontal = 14.dp),
			modifier = Modifier.height(40.dp),
		) {
			Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(17.dp))
			Spacer(Modifier.width(4.dp))
			Text("播放全部", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
		}

		FilledTonalButton(
			onClick = onRefreshOrStop,
			enabled = isStreaming || !isLoading,
			shape = CircleShape,
			contentPadding = PaddingValues(horizontal = 12.dp),
			modifier = Modifier.height(40.dp),
		) {
			when {
				isStreaming -> {
					Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(15.dp))
					Spacer(Modifier.width(4.dp))
					Text("停止", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
				}
				isLoading -> {
					CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.8.dp)
				}
				else -> {
					Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
					Spacer(Modifier.width(4.dp))
					Text("换一批", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
				}
			}
		}

		if (useMeloDaily && hasTracks) {
			val canBatchImport = importableCount > 0 && !isLoading
			Surface(
				shape = CircleShape,
				color = if (canBatchImport) {
					MaterialTheme.colorScheme.surfaceContainer
				} else {
					MaterialTheme.colorScheme.meloPositive.copy(alpha = 0.12f)
				},
				border = BorderStroke(
					IosHairlineWidth,
					if (canBatchImport) iosLibHairlineBorderColor() else MaterialTheme.colorScheme.meloPositive.copy(alpha = 0.28f),
				),
				modifier = Modifier
					.height(40.dp)
					.clip(CircleShape)
					.clickable(enabled = canBatchImport, onClick = onBatchImport),
			) {
				Row(
					modifier = Modifier.padding(horizontal = 12.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(4.dp),
				) {
					Icon(
						imageVector = if (importableCount > 0) Icons.Rounded.LibraryMusic else Icons.Rounded.Check,
						contentDescription = null,
						tint = if (importableCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.meloPositive,
						modifier = Modifier.size(16.dp),
					)
					Text(
						text = if (importableCount > 0) "批量入库 ($importableCount)" else "全部已入库",
						style = MaterialTheme.typography.labelMedium,
						color = if (importableCount > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.meloPositive,
						maxLines = 1,
						softWrap = false,
					)
				}
			}
		}

		Surface(
			shape = CircleShape,
			color = MaterialTheme.colorScheme.surfaceContainer,
			border = iosLibHairlineBorder(),
			modifier = Modifier
				.height(40.dp)
				.clip(CircleShape)
				.clickable(enabled = hasTracks, onClick = onOfflineAll),
		) {
			Row(
				modifier = Modifier.padding(horizontal = 12.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(4.dp),
			) {
				Icon(
					imageVector = Icons.Rounded.CloudDownload,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier.size(16.dp),
				)
				Text(
					text = "整单离线",
					style = MaterialTheme.typography.labelMedium,
					maxLines = 1,
					softWrap = false,
				)
			}
		}

		Surface(
			shape = CircleShape,
			color = if (multiSelecting) {
				MaterialTheme.colorScheme.primaryContainer
			} else {
				MaterialTheme.colorScheme.surfaceContainer
			},
			border = iosLibHairlineBorder(),
			modifier = Modifier
				.height(40.dp)
				.clip(CircleShape)
				.clickable(enabled = hasTracks, onClick = onToggleMultiSelect),
		) {
			Row(
				modifier = Modifier.padding(horizontal = 12.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(4.dp),
			) {
				Icon(
					imageVector = if (multiSelecting) Icons.Rounded.Close else Icons.Rounded.DoneAll,
					contentDescription = null,
					tint = if (multiSelecting) {
						MaterialTheme.colorScheme.onPrimaryContainer
					} else {
						MaterialTheme.colorScheme.primary
					},
					modifier = Modifier.size(16.dp),
				)
				Text(
					text = if (multiSelecting) "退出" else "多选",
					style = MaterialTheme.typography.labelMedium,
					color = if (multiSelecting) {
						MaterialTheme.colorScheme.onPrimaryContainer
					} else {
						MaterialTheme.colorScheme.onSurface
					},
					maxLines = 1,
					softWrap = false,
				)
			}
		}
	}
}

@Composable
private fun IosDailyRecommendTrackRow(
	item: MeloDailyRecommendItem,
	track: IosTrackItem,
	trackIndex: Int,
	isPlaying: Boolean,
	isOffline: Boolean,
	downloadProgress: Float?,
	multiSelectMode: Boolean,
	isSelected: Boolean,
	isEnqueued: Boolean,
	canEnqueue: Boolean,
	isStarred: Boolean,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	onEnqueueToLibrary: () -> Unit,
	onPlayNext: () -> Unit,
	onAppendToQueue: () -> Unit,
	onDownloadOffline: () -> Unit,
	onCancelOffline: () -> Unit,
	onDeleteOffline: () -> Unit,
	onToggleFavorite: (() -> Unit)? = null,
	onAddToPlaylist: (() -> Unit)? = null,
	swipeEnabled: Boolean,
	swipeShape: Shape,
	swipeContainerColor: Color,
) {
	var menuExpanded by remember { mutableStateOf(false) }

	val qualityTag = remember(track.id, track.bitDepth, track.fileExtension, track.bitRate, track.lossless) {
		iosLibFormatQuietQuality(track.bitDepth, track.fileExtension, track.bitRate, track.lossless)
	}
	val sourceName = remember(item.track.source) {
		item.track.source.takeIf { it.isNotBlank() }?.let { translateIosSourceName(it) }
	}
	val subtitleText = remember(track.id, track.artist, sourceName, qualityTag) {
		listOfNotNull(
			track.artist.takeIf { it.isNotBlank() },
			sourceName,
			qualityTag,
		).joinToString(" · ")
	}

	val rowBody: @Composable () -> Unit = {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.combinedClickable(
					onClick = onClick,
					onLongClick = onLongClick,
				)
				.padding(horizontal = 12.dp, vertical = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(10.dp),
		) {
			if (multiSelectMode) {
				Checkbox(
					checked = isSelected,
					onCheckedChange = { onClick() },
				)
			} else {
				Text(
					text = trackIndex.toString().padStart(2, '0'),
					style = MaterialTheme.typography.labelMedium.tabularNums(),
					color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
					fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Normal,
					textAlign = TextAlign.Center,
					modifier = Modifier.width(24.dp),
				)
			}

			CoverArtBox(
				coverUrl = track.coverUrl,
				size = 44.dp,
				cornerRadius = 9.dp,
			)

			Column(
				modifier = Modifier.weight(1f),
				verticalArrangement = Arrangement.spacedBy(3.dp),
			) {
				Text(
					text = track.title.ifBlank { "未知歌曲" },
					style = MaterialTheme.typography.titleSmall.copy(
						fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Medium,
					),
					color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)

				if (isOffline || subtitleText.isNotBlank()) {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(4.dp),
					) {
						if (isOffline) {
							Icon(
								imageVector = Icons.Rounded.OfflinePin,
								contentDescription = "已离线",
								tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
								modifier = Modifier.size(12.dp),
							)
						}
						if (subtitleText.isNotBlank()) {
							Text(
								text = subtitleText,
								style = MaterialTheme.typography.bodySmall.tabularNums(),
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
							)
						}
					}
				}

				FlowRow(
					horizontalArrangement = Arrangement.spacedBy(5.dp),
					verticalArrangement = Arrangement.spacedBy(4.dp),
				) {
					val badgeColor = if (item.inLibrary || isEnqueued) {
						MaterialTheme.colorScheme.meloPositive
					} else {
						MaterialTheme.colorScheme.primary
					}
					val badgeLabel = when {
						item.inLibrary -> "已在曲库"
						isEnqueued -> "已入库"
						else -> "全网发现"
					}
					Surface(
						shape = RoundedCornerShape(6.dp),
						color = badgeColor.copy(alpha = 0.12f),
						border = BorderStroke(IosHairlineWidth, badgeColor.copy(alpha = 0.28f)),
					) {
						Text(
							text = badgeLabel,
							style = MaterialTheme.typography.labelSmall.copy(
								fontSize = 10.sp,
								fontWeight = FontWeight.SemiBold,
							),
							color = badgeColor,
							maxLines = 1,
							modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
						)
					}

					if (item.reason.isNotBlank()) {
						Surface(
							shape = RoundedCornerShape(6.dp),
							color = MaterialTheme.colorScheme.surfaceContainerHigh,
							border = iosLibHairlineBorder(),
						) {
							Text(
								text = item.reason,
								style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
								modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
							)
						}
					}
				}
			}

			if (!multiSelectMode) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(2.dp),
				) {
					if (!item.inLibrary && (canEnqueue || isEnqueued)) {
						val pillBg = if (isEnqueued) {
							MaterialTheme.colorScheme.meloPositive.copy(alpha = 0.12f)
						} else {
							MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
						}
						val pillFg = if (isEnqueued) {
							MaterialTheme.colorScheme.meloPositive
						} else {
							MaterialTheme.colorScheme.primary
						}
						Surface(
							modifier = Modifier
								.height(26.dp)
								.clip(CircleShape)
								.clickable(enabled = canEnqueue) { onEnqueueToLibrary() },
							shape = CircleShape,
							color = pillBg,
							border = BorderStroke(IosHairlineWidth, pillFg.copy(alpha = 0.28f)),
						) {
							Row(
								modifier = Modifier
									.fillMaxHeight()
									.padding(horizontal = 8.dp),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(3.dp),
							) {
								Icon(
									imageVector = if (isEnqueued) Icons.Rounded.Check else Icons.Rounded.CloudDownload,
									contentDescription = null,
									tint = pillFg,
									modifier = Modifier.size(12.dp),
								)
								Text(
									text = if (isEnqueued) "已入库" else "入库",
									style = MaterialTheme.typography.labelSmall.copy(
										fontSize = 11.sp,
										fontWeight = FontWeight.SemiBold,
									),
									color = pillFg,
									maxLines = 1,
								)
							}
						}
					}

					Box {
						IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
							Icon(
								imageVector = Icons.Rounded.MoreVert,
								contentDescription = "更多操作",
								tint = MaterialTheme.colorScheme.onSurfaceVariant,
								modifier = Modifier.size(18.dp),
							)
						}
						DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
							DropdownMenuItem(
								text = { Text("下一首播放") },
								leadingIcon = { Icon(Icons.Rounded.PlaylistPlay, contentDescription = null) },
								onClick = {
									menuExpanded = false
									onPlayNext()
								},
							)
							DropdownMenuItem(
								text = { Text("加入待播队尾") },
								leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, contentDescription = null) },
								onClick = {
									menuExpanded = false
									onAppendToQueue()
								},
							)
							if (canEnqueue) {
								DropdownMenuItem(
									text = { Text("下载入库 (MeloKeep)") },
									leadingIcon = { Icon(Icons.Rounded.LibraryMusic, contentDescription = null) },
									onClick = {
										menuExpanded = false
										onEnqueueToLibrary()
									},
								)
							}
							if (onToggleFavorite != null) {
								DropdownMenuItem(
									text = { Text(if (isStarred) "取消喜欢" else "加入我喜欢") },
									leadingIcon = {
										Icon(
											imageVector = if (isStarred) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
											contentDescription = null,
										)
									},
									onClick = {
										menuExpanded = false
										onToggleFavorite()
									},
								)
							}
							if (onAddToPlaylist != null) {
								DropdownMenuItem(
									text = { Text("加入歌单") },
									leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, contentDescription = null) },
									onClick = {
										menuExpanded = false
										onAddToPlaylist()
									},
								)
							}
							when {
								downloadProgress != null -> {
									DropdownMenuItem(
										text = { Text("取消离线下载") },
										leadingIcon = { Icon(Icons.Rounded.Close, contentDescription = null) },
										onClick = {
											menuExpanded = false
											onCancelOffline()
										},
									)
								}
								!isOffline -> {
									DropdownMenuItem(
										text = { Text("下载到手机离线") },
										leadingIcon = { Icon(Icons.Rounded.CloudDownload, contentDescription = null) },
										onClick = {
											menuExpanded = false
											onDownloadOffline()
										},
									)
								}
								else -> {
									DropdownMenuItem(
										text = { Text("移除手机离线") },
										leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
										onClick = {
											menuExpanded = false
											onDeleteOffline()
										},
									)
								}
							}
						}
					}
				}
			}
		}
	}

	if (swipeEnabled && !multiSelectMode) {
		IosSwipeableSongRow(
			enabled = true,
			shape = swipeShape,
			containerColor = swipeContainerColor,
			onAppendToQueue = onAppendToQueue,
			onPlayNext = onPlayNext,
			content = rowBody,
		)
	} else {
		rowBody()
	}
}
