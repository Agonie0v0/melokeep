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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

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
								Surface(
									shape = iosLibGroupedItemShape(isFirst, isLast),
									color = when {
										multiSelecting && isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
										currentTrackId == track.id -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
										else -> MaterialTheme.colorScheme.surfaceContainer
									},
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
											onPlayNext = { audioPlayer.playNext(track) },
											onToggleStar = { scope.launch { subsonicManager.toggleStar(track) } },
											onDownloadOffline = {
												audioPlayer.downloadTrackOffline(track)
												onShowNotice(iosLibOfflineQueueNotice(1))
											},
											onCancelOffline = { audioPlayer.cancelDownload(track.id) },
											onDeleteOffline = { audioPlayer.deleteOfflineTrack(track.id) },
											onAddToPlaylist = { onOpenAddToPlaylist(listOf(track)) },
											onRequestPhysicalDelete = onRequestPhysicalDelete?.let { fn -> { fn(track) } },
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

		AnimatedVisibility(
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
) {
	val scope = rememberCoroutineScope()
	var playlist by remember(playlistId) { mutableStateOf<IosPlaylistSummary?>(null) }
	var tracks by remember(playlistId) { mutableStateOf<List<IosTrackItem>>(emptyList()) }
	var loading by remember(playlistId) { mutableStateOf(true) }
	var showRenameDialog by remember { mutableStateOf(false) }

	var multiSelecting by remember(playlistId) { mutableStateOf(false) }
	val selectedIds = remember(playlistId) { mutableStateMapOf<String, Boolean>() }

	fun reload(showSpinner: Boolean = true) {
		scope.launch {
			if (showSpinner) loading = true
			subsonicManager.fetchPlaylistDetail(playlistId).onSuccess { (pl, list) ->
				playlist = pl
				tracks = list
			}
			loading = false
		}
	}

	LaunchedEffect(playlistId, refreshSeq) { reload(showSpinner = tracks.isEmpty()) }

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
				title = "歌单",
				onBack = onBack,
				trailing = {
					IosLibHeaderCircleButton(
						icon = Icons.Rounded.Edit,
						contentDescription = "重命名歌单",
						onClick = { showRenameDialog = true },
					)
				},
			)

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
									if (!multiSelecting) selectedIds.clear()
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
							val isSelected = selectedIds[track.id] == true
							val isFirst = idx == 0
							val isLast = idx == tracks.lastIndex
							Surface(
								shape = iosLibGroupedItemShape(isFirst, isLast),
								color = when {
									multiSelecting && isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
									currentTrackId == track.id -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
									else -> MaterialTheme.colorScheme.surfaceContainer
								},
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
												selectedIds[track.id] = !isSelected
											} else {
												audioPlayer.playQueue(tracks, idx)
											}
										},
										onLongClick = {
											multiSelecting = true
											selectedIds[track.id] = true
										},
										onPlayNext = { audioPlayer.playNext(track) },
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
												reload(showSpinner = false)
											}
										},
										onRequestPhysicalDelete = onRequestPhysicalDelete?.let { fn -> { fn(track) } },
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

		AnimatedVisibility(
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
							showRenameDialog = false
							reload(showSpinner = false)
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
		val (tops, sims) = subsonicManager.fetchArtistTopSongsAndSimilar(artistId, resolvedName)
		topSongs = tops
		similarArtists = sims
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
							CoverArtBox(
								coverUrl = artist?.coverUrl,
								size = 132.dp,
								cornerRadius = 66.dp,
								modifier = Modifier.border(IosHairlineWidth, iosLibHairlineBorderColor(), CircleShape),
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
							Surface(
								shape = iosLibGroupedItemShape(isFirst, isLast),
								color = when {
									multiSelecting && isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
									currentTrackId == track.id -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
									else -> MaterialTheme.colorScheme.surfaceContainer
								},
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
										onPlayNext = { audioPlayer.playNext(track) },
										onToggleStar = { scope.launch { subsonicManager.toggleStar(track) } },
										onDownloadOffline = {
											audioPlayer.downloadTrackOffline(track)
											onShowNotice(iosLibOfflineQueueNotice(1))
										},
										onCancelOffline = { audioPlayer.cancelDownload(track.id) },
										onDeleteOffline = { audioPlayer.deleteOfflineTrack(track.id) },
										onAddToPlaylist = { onOpenAddToPlaylist(listOf(track)) },
										onRequestPhysicalDelete = onRequestPhysicalDelete?.let { fn -> { fn(track) } },
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
											CoverArtBox(
												coverUrl = sim.coverUrl,
												size = 64.dp,
												cornerRadius = 32.dp,
												modifier = Modifier.border(IosHairlineWidth, iosLibHairlineBorderColor(), CircleShape),
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

		AnimatedVisibility(
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
							Surface(
								shape = iosLibGroupedItemShape(isFirst, isLast),
								color = when {
									multiSelecting && isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
									currentTrackId == track.id -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
									else -> MaterialTheme.colorScheme.surfaceContainer
								},
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
										onPlayNext = { audioPlayer.playNext(track) },
										onToggleStar = { scope.launch { subsonicManager.toggleStar(track) } },
										onDownloadOffline = {
											audioPlayer.downloadTrackOffline(track)
											onShowNotice(iosLibOfflineQueueNotice(1))
										},
										onCancelOffline = { audioPlayer.cancelDownload(track.id) },
										onDeleteOffline = { audioPlayer.deleteOfflineTrack(track.id) },
										onAddToPlaylist = { onOpenAddToPlaylist(listOf(track)) },
										onRequestPhysicalDelete = onRequestPhysicalDelete?.let { fn -> { fn(track) } },
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
										CoverArtBox(coverUrl = artist.coverUrl, size = 44.dp, cornerRadius = 22.dp)
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

		AnimatedVisibility(
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
// 5. 猜你喜欢抽屉（100% 保留既有公开签名 `IosDailyMixSheet`）
// =========================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IosDailyMixSheet(
	libState: IosLibraryState,
	audioPlayer: IosAudioPlayer,
	subsonicManager: IosSubsonicManager,
	onDismiss: () -> Unit,
) {
	val tracks = libState.dailyMixTracks
	val isCurating = libState.aiCurating
	val isLoading = libState.dailyMixLoading
	val playerState by audioPlayer.state.collectAsState()
	val scope = rememberCoroutineScope()
	var sheetNotice by remember { mutableStateOf<String?>(null) }
	var multiSelecting by remember { mutableStateOf(false) }
	val selectedIds = remember { mutableStateMapOf<String, Boolean>() }

	val offlineIds = playerState.offlineTracks.keys
	val downloadingProgress = playerState.downloadingProgress
	val selectedTracks by remember(tracks) {
		derivedStateOf { tracks.filter { selectedIds[it.id] == true } }
	}
	val hasOfflineRemovable by remember(selectedTracks, offlineIds, downloadingProgress) {
		derivedStateOf { selectedTracks.any { it.id in offlineIds || it.id in downloadingProgress } }
	}

	LaunchedEffect(Unit) {
		if (tracks.isEmpty()) {
			subsonicManager.loadOrGenerateDailyMix(forceRegenerate = false)
		}
	}

	LaunchedEffect(tracks) {
		if (tracks.isEmpty()) {
			multiSelecting = false
			selectedIds.clear()
		} else {
			val validIds = tracks.map { it.id }.toSet()
			selectedIds.keys.filter { it !in validIds }.forEach { selectedIds.remove(it) }
		}
	}

	ModalBottomSheet(
		onDismissRequest = onDismiss,
		shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
		containerColor = MaterialTheme.colorScheme.surface,
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.navigationBarsPadding(),
		) {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp),
				verticalArrangement = Arrangement.spacedBy(12.dp),
			) {
			// 极简顶栏（无冗长副文案、无颜文字/emoji）
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				Text(
					text = "猜你喜欢",
					style = MaterialTheme.typography.titleLarge,
					fontWeight = FontWeight.Bold,
				)
				IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
					Icon(Icons.Rounded.Close, contentDescription = "关闭", modifier = Modifier.size(18.dp))
				}
			}

			// 简洁摘要与 AI 策展卡
			IosGroupedCard {
				Column(
					modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
					verticalArrangement = Arrangement.spacedBy(8.dp),
				) {
					Row(
						modifier = Modifier.fillMaxWidth(),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.SpaceBetween,
					) {
						Text(
							text = "共 ${tracks.size} 首",
							style = MaterialTheme.typography.labelLarge.tabularNums(),
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)

						FilledTonalButton(
							onClick = { subsonicManager.triggerAiCurate() },
							enabled = !isCurating && tracks.isNotEmpty(),
							shape = CircleShape,
							contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
							modifier = Modifier.height(30.dp),
						) {
							if (isCurating) {
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

					if (libState.dailyMixNote.isNotBlank()) {
						Text(
							text = libState.dailyMixNote,
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurface,
						)
					}
				}
			}

			sheetNotice?.let { notice ->
				Surface(
					shape = RoundedCornerShape(10.dp),
					color = MaterialTheme.colorScheme.surfaceContainerHigh,
					border = iosLibHairlineBorder(),
					modifier = Modifier.fillMaxWidth(),
				) {
					Text(
						text = notice,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurface,
						modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
					)
				}
			}

			// 快捷动作条：播放全部 + 换一批 + 整单离线 + 多选
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.horizontalScroll(rememberScrollState()),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Button(
					onClick = { if (tracks.isNotEmpty()) audioPlayer.playQueue(tracks, 0) },
					enabled = tracks.isNotEmpty(),
					shape = CircleShape,
					contentPadding = PaddingValues(horizontal = 14.dp),
					modifier = Modifier.height(40.dp),
				) {
					Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(17.dp))
					Spacer(Modifier.width(4.dp))
					Text("播放全部", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
				}

				FilledTonalButton(
					onClick = { subsonicManager.loadOrGenerateDailyMix(forceRegenerate = true) },
					enabled = !isLoading,
					shape = CircleShape,
					contentPadding = PaddingValues(horizontal = 12.dp),
					modifier = Modifier.height(40.dp),
				) {
					if (isLoading) {
						CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.8.dp)
					} else {
						Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
						Spacer(Modifier.width(4.dp))
						Text("换一批", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
					}
				}

				Surface(
					shape = CircleShape,
					color = MaterialTheme.colorScheme.surfaceContainer,
					border = iosLibHairlineBorder(),
					modifier = Modifier
						.height(40.dp)
						.clip(CircleShape)
						.clickable(enabled = tracks.isNotEmpty()) {
							audioPlayer.downloadTracksOffline(tracks)
							sheetNotice = iosLibOfflineQueueNotice(tracks.size)
						},
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
						.clickable(enabled = tracks.isNotEmpty()) {
							multiSelecting = !multiSelecting
							if (!multiSelecting) selectedIds.clear()
						},
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

			// 歌曲列表（分组圆角 + 0.5.dp 发丝线）
			if (isLoading && tracks.isEmpty()) {
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.height(200.dp),
					contentAlignment = Alignment.Center,
				) {
					CircularProgressIndicator()
				}
			} else if (tracks.isEmpty()) {
				IosQuietEmptyState(
					message = "暂无推荐曲目",
					icon = Icons.Rounded.AutoAwesome,
				)
			} else {
				LazyColumn(
					modifier = Modifier
						.fillMaxWidth()
						.heightIn(max = 420.dp),
					contentPadding = PaddingValues(bottom = if (multiSelecting) 112.dp else 10.dp),
					verticalArrangement = Arrangement.spacedBy(0.dp),
				) {
					itemsIndexed(
						items = tracks,
						key = { idx, t -> "daily-${t.id}-$idx" },
						contentType = { _, _ -> "track" },
					) { idx, track ->
						val isSelected = selectedIds[track.id] == true
						val isFirst = idx == 0
						val isLast = idx == tracks.lastIndex
						Surface(
							shape = iosLibGroupedItemShape(isFirst, isLast),
							color = when {
								multiSelecting && isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
								track.id == playerState.currentTrack?.id -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
								else -> MaterialTheme.colorScheme.surfaceContainer
							},
							modifier = Modifier.fillMaxWidth(),
						) {
							Column {
								IosLibTrackRowContent(
									track = track,
									isPlaying = track.id == playerState.currentTrack?.id,
									isStarred = subsonicManager.isStarred(track),
									isOffline = track.id in offlineIds,
									downloadProgress = downloadingProgress[track.id],
									multiSelectMode = multiSelecting,
									isSelected = isSelected,
									trackIndex = idx + 1,
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
									onPlayNext = { audioPlayer.playNext(track) },
									onToggleStar = { scope.launch { subsonicManager.toggleStar(track) } },
									onDownloadOffline = {
										audioPlayer.downloadTrackOffline(track)
										sheetNotice = iosLibOfflineQueueNotice(1)
									},
									onCancelOffline = { audioPlayer.cancelDownload(track.id) },
									onDeleteOffline = { audioPlayer.deleteOfflineTrack(track.id) },
									onAddToPlaylist = null,
									onRequestPhysicalDelete = null,
								)
								if (!isLast) {
									IosHairlineDivider(startIndent = if (multiSelecting) 132.dp else 94.dp)
								}
							}
						}
					}
				}
			}

			Spacer(Modifier.height(10.dp))
			}

			androidx.compose.animation.AnimatedVisibility(
				visible = multiSelecting && tracks.isNotEmpty(),
				enter = slideInVertically { it } + fadeIn(),
				exit = slideOutVertically { it } + fadeOut(),
				modifier = Modifier
					.align(Alignment.BottomCenter)
					.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
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
							sheetNotice = iosLibOfflineQueueNotice(selectedTracks.size)
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
						sheetNotice = "已移除所选曲目的手机离线缓存"
						multiSelecting = false
						selectedIds.clear()
					},
				)
			}
		}
	}
}
