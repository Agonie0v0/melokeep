@file:OptIn(
	ExperimentalMaterial3Api::class,
	ExperimentalFoundationApi::class,
	ExperimentalLayoutApi::class,
)

package io.github.agonie0v0.melokeep.ios

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private enum class IosDeviceSubTab(val label: String) {
	SONGS("单曲"),
	ARTISTS("歌手"),
	ALBUMS("专辑"),
	FOLDERS("文件夹"),
}

@Composable
internal fun IosLibDeviceLocalSection(
	deviceState: IosDeviceMusicState,
	searchQuery: String,
	audioPlayer: IosAudioPlayer,
	currentTrackId: String?,
	bottomPadding: PaddingValues,
) {
	var subTabName by rememberSaveable { mutableStateOf(IosDeviceSubTab.SONGS.name) }
	val subTab = remember(subTabName) {
		IosDeviceSubTab.entries.firstOrNull { it.name == subTabName } ?: IosDeviceSubTab.SONGS
	}

	var openedArtistName by rememberSaveable { mutableStateOf<String?>(null) }
	var openedAlbumName by rememberSaveable { mutableStateOf<String?>(null) }
	var openedFolderPath by rememberSaveable { mutableStateOf<String?>(null) }

	var sortMenuOpen by remember { mutableStateOf(false) }
	var showManageSheet by rememberSaveable { mutableStateOf(false) }

	var multiSelectMode by rememberSaveable { mutableStateOf(false) }
	val selectedIds = remember { mutableStateMapOf<String, Boolean>() }

	var infoDialogTrack by remember { mutableStateOf<IosTrackItem?>(null) }
	var pendingDeleteTracks by remember { mutableStateOf<List<IosTrackItem>?>(null) }

	val normalizedQuery = searchQuery.trim()

	val filteredSongs = remember(deviceState.allTracks, normalizedQuery) {
		if (normalizedQuery.isBlank()) {
			deviceState.allTracks
		} else {
			deviceState.allTracks.filter { t ->
				t.title.contains(normalizedQuery, ignoreCase = true) ||
					t.artist.contains(normalizedQuery, ignoreCase = true) ||
					t.album.contains(normalizedQuery, ignoreCase = true) ||
					t.rawId.contains(normalizedQuery, ignoreCase = true)
			}
		}
	}

	val filteredArtists = remember(deviceState.artists, normalizedQuery) {
		if (normalizedQuery.isBlank()) {
			deviceState.artists
		} else {
			deviceState.artists.filter { artist ->
				artist.name.contains(normalizedQuery, ignoreCase = true) ||
					artist.tracks.any { t ->
						t.title.contains(normalizedQuery, ignoreCase = true) ||
							t.album.contains(normalizedQuery, ignoreCase = true)
					}
			}
		}
	}

	val filteredAlbums = remember(deviceState.albums, normalizedQuery) {
		if (normalizedQuery.isBlank()) {
			deviceState.albums
		} else {
			deviceState.albums.filter { album ->
				album.name.contains(normalizedQuery, ignoreCase = true) ||
					album.artist.contains(normalizedQuery, ignoreCase = true) ||
					album.tracks.any { t -> t.title.contains(normalizedQuery, ignoreCase = true) }
			}
		}
	}

	val filteredFolders = remember(deviceState.visibleFolders, normalizedQuery) {
		if (normalizedQuery.isBlank()) {
			deviceState.visibleFolders
		} else {
			deviceState.visibleFolders.filter { folder ->
				folder.name.contains(normalizedQuery, ignoreCase = true) ||
					folder.path.contains(normalizedQuery, ignoreCase = true) ||
					folder.tracks.any { t ->
						t.title.contains(normalizedQuery, ignoreCase = true) ||
							t.artist.contains(normalizedQuery, ignoreCase = true)
					}
			}
		}
	}

	val activeArtist = remember(deviceState.artists, openedArtistName) {
		openedArtistName?.let { name -> deviceState.artists.firstOrNull { it.name == name } }
	}
	val activeAlbum = remember(deviceState.albums, openedAlbumName) {
		openedAlbumName?.let { name -> deviceState.albums.firstOrNull { it.name == name } }
	}
	val activeFolder = remember(deviceState.visibleFolders, openedFolderPath) {
		openedFolderPath?.let { path -> deviceState.visibleFolders.firstOrNull { it.path == path } }
	}

	val isViewingTrackList = when (subTab) {
		IosDeviceSubTab.SONGS -> true
		IosDeviceSubTab.ARTISTS -> activeArtist != null
		IosDeviceSubTab.ALBUMS -> activeAlbum != null
		IosDeviceSubTab.FOLDERS -> activeFolder != null
	}

	val displayedTracks = remember(
		subTab,
		filteredSongs,
		activeArtist,
		activeAlbum,
		activeFolder,
		normalizedQuery,
	) {
		val base = when (subTab) {
			IosDeviceSubTab.SONGS -> filteredSongs
			IosDeviceSubTab.ARTISTS -> activeArtist?.tracks.orEmpty()
			IosDeviceSubTab.ALBUMS -> activeAlbum?.tracks.orEmpty()
			IosDeviceSubTab.FOLDERS -> activeFolder?.tracks.orEmpty()
		}
		if (subTab == IosDeviceSubTab.SONGS || normalizedQuery.isBlank()) {
			base
		} else {
			base.filter { t ->
				t.title.contains(normalizedQuery, ignoreCase = true) ||
					t.artist.contains(normalizedQuery, ignoreCase = true) ||
					t.album.contains(normalizedQuery, ignoreCase = true)
			}
		}
	}

	LaunchedEffect(subTab, openedArtistName, openedAlbumName, openedFolderPath) {
		selectedIds.clear()
		if (!isViewingTrackList) {
			multiSelectMode = false
		}
	}

	LaunchedEffect(displayedTracks) {
		val validIds = displayedTracks.mapTo(HashSet()) { it.id }
		val staleKeys = selectedIds.keys.filter { it !in validIds }
		staleKeys.forEach { selectedIds.remove(it) }
	}

	val selectedTracks = remember(displayedTracks, selectedIds.size, multiSelectMode) {
		if (!multiSelectMode) emptyList()
		else displayedTracks.filter { selectedIds[it.id] == true }
	}
	val showBatchDock = multiSelectMode && isViewingTrackList && displayedTracks.isNotEmpty()
	val hasActiveFilterRules = deviceState.excludedFolders.isNotEmpty() ||
		deviceState.hiddenCount > 0 ||
		deviceState.filteredOutCount > 0

	Box(modifier = Modifier.fillMaxSize()) {
		LazyColumn(
			modifier = Modifier.fillMaxSize(),
			contentPadding = PaddingValues(
				start = 16.dp,
				end = 16.dp,
				top = 8.dp,
				bottom = bottomPadding.calculateBottomPadding() + if (showBatchDock) 116.dp else 18.dp,
			),
			verticalArrangement = Arrangement.spacedBy(0.dp),
		) {
			if (deviceState.scanning) {
				item(key = "ios-dev-scanning") {
					LinearProgressIndicator(
						modifier = Modifier
							.fillMaxWidth()
							.padding(bottom = 8.dp),
					)
				}
			}

			// 1. 四维子标签：单曲 | 歌手 | 专辑 | 文件夹 + 曲目总数
			item(key = "ios-dev-subtabs") {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(bottom = 10.dp),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically,
				) {
					Row(
						modifier = Modifier
							.weight(1f)
							.horizontalScroll(rememberScrollState()),
						horizontalArrangement = Arrangement.spacedBy(6.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						IosDeviceSubTab.entries.forEach { tab ->
							val selected = subTab == tab
							Surface(
								onClick = {
									if (subTab != tab) {
										subTabName = tab.name
										openedArtistName = null
										openedAlbumName = null
										openedFolderPath = null
										multiSelectMode = false
										selectedIds.clear()
									}
								},
								shape = CircleShape,
								color = if (selected) {
									MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
								} else {
									MaterialTheme.colorScheme.surfaceContainer
								},
								border = BorderStroke(
									IosHairlineWidth,
									if (selected) {
										MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
									} else {
										iosLibHairlineBorderColor()
									},
								),
							) {
								Text(
									text = tab.label,
									style = MaterialTheme.typography.labelMedium.copy(
										fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
									),
									color = if (selected) {
										MaterialTheme.colorScheme.primary
									} else {
										MaterialTheme.colorScheme.onSurfaceVariant
									},
									maxLines = 1,
									softWrap = false,
									modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
								)
							}
						}
					}

					Spacer(Modifier.width(8.dp))

					val countLabel = when {
						isViewingTrackList -> "${displayedTracks.size} 首"
						subTab == IosDeviceSubTab.ARTISTS -> "${filteredArtists.size} 位"
						subTab == IosDeviceSubTab.ALBUMS -> "${filteredAlbums.size} 张"
						else -> "${filteredFolders.size} 目录"
					}
					Text(
						text = countLabel,
						style = MaterialTheme.typography.labelMedium.tabularNums(),
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
					)
				}
			}

			// 2. 二级面包屑返回条（歌手 / 专辑 / 文件夹）
			if (subTab == IosDeviceSubTab.ARTISTS && activeArtist != null) {
				item(key = "ios-dev-artist-breadcrumb") {
					IosDeviceDrilldownHeader(
						title = activeArtist.name,
						subtitle = "${activeArtist.albumCount} 张专辑 · ${activeArtist.tracks.size} 首歌曲",
						onBack = { openedArtistName = null },
					)
				}
			} else if (subTab == IosDeviceSubTab.ALBUMS && activeAlbum != null) {
				item(key = "ios-dev-album-breadcrumb") {
					IosDeviceDrilldownHeader(
						title = activeAlbum.name,
						subtitle = "${activeAlbum.artist} · ${activeAlbum.tracks.size} 首歌曲",
						onBack = { openedAlbumName = null },
					)
				}
			} else if (subTab == IosDeviceSubTab.FOLDERS && activeFolder != null) {
				item(key = "ios-dev-folder-breadcrumb") {
					IosDeviceDrilldownHeader(
						title = activeFolder.name,
						subtitle = activeFolder.path,
						onBack = { openedFolderPath = null },
					)
				}
			}

			// 3. 操作栏：播放全部、随机、排序、多选、扫描与目录
			item(key = "ios-dev-action-bar") {
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.padding(bottom = 10.dp),
					verticalArrangement = Arrangement.spacedBy(8.dp),
				) {
					if (isViewingTrackList && displayedTracks.isNotEmpty()) {
						Row(
							modifier = Modifier.fillMaxWidth(),
							horizontalArrangement = Arrangement.spacedBy(8.dp),
						) {
							FilledTonalButton(
								onClick = { audioPlayer.playQueue(displayedTracks, 0) },
								modifier = Modifier.weight(1f),
								shape = RoundedCornerShape(12.dp),
							) {
								Icon(Icons.Rounded.PlaylistPlay, contentDescription = null, modifier = Modifier.size(18.dp))
								Spacer(Modifier.width(6.dp))
								Text("播放全部 (${displayedTracks.size})", maxLines = 1)
							}
							OutlinedButton(
								onClick = { audioPlayer.playQueue(displayedTracks.shuffled(), 0) },
								modifier = Modifier.weight(1f),
								shape = RoundedCornerShape(12.dp),
							) {
								Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(17.dp))
								Spacer(Modifier.width(6.dp))
								Text("随机", maxLines = 1)
							}
						}
					}

					Row(
						modifier = Modifier
							.fillMaxWidth()
							.horizontalScroll(rememberScrollState()),
						horizontalArrangement = Arrangement.spacedBy(8.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Box {
							IosDeviceActionPill(
								icon = Icons.Rounded.SwapVert,
								label = "排序 · ${IosDeviceSortOrder.label(deviceState.sortOrder)}",
								active = deviceState.sortOrder != IosDeviceSortOrder.TITLE,
								onClick = { sortMenuOpen = true },
							)
							DropdownMenu(
								expanded = sortMenuOpen,
								onDismissRequest = { sortMenuOpen = false },
							) {
								IosDeviceSortOrder.all.forEach { order ->
									val selected = deviceState.sortOrder == order
									DropdownMenuItem(
										text = {
											Text(
												text = IosDeviceSortOrder.label(order),
												fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
												color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
											)
										},
										trailingIcon = {
											if (selected) {
												Icon(
													imageVector = Icons.Rounded.Check,
													contentDescription = null,
													tint = MaterialTheme.colorScheme.primary,
													modifier = Modifier.size(16.dp),
												)
											}
										},
										onClick = {
											sortMenuOpen = false
											IosDeviceMusicManager.setSortOrder(order)
										},
									)
								}
							}
						}

						if (isViewingTrackList && displayedTracks.isNotEmpty()) {
							IosDeviceActionPill(
								icon = Icons.Rounded.DoneAll,
								label = if (multiSelectMode) "取消多选" else "多选",
								active = multiSelectMode,
								onClick = {
									val next = !multiSelectMode
									multiSelectMode = next
									if (!next) selectedIds.clear()
								},
							)
						}

						IosDeviceActionPill(
							icon = Icons.Rounded.Tune,
							label = if (deviceState.filteredOutCount > 0) {
								"扫描与目录 · 已过滤 ${deviceState.filteredOutCount}"
							} else {
								"扫描与目录"
							},
							active = hasActiveFilterRules,
							showDot = deviceState.excludedFolders.isNotEmpty() || deviceState.hiddenCount > 0,
							onClick = { showManageSheet = true },
						)
					}
				}
			}

			// 4. 内容列表区
			when {
				subTab == IosDeviceSubTab.ARTISTS && activeArtist == null -> {
					if (filteredArtists.isEmpty() && !deviceState.scanning) {
						item(key = "ios-dev-artists-empty") {
							IosDeviceLocalEmptyState(
								deviceState = deviceState,
								normalizedQuery = normalizedQuery,
								emptySearchMessage = "没有匹配的本地歌手",
								onOpenManageSheet = { showManageSheet = true },
							)
						}
					} else {
						itemsIndexed(
							items = filteredArtists,
							key = { _, artist -> "ios_dev_artist_${artist.name}" },
						) { idx, artist ->
							IosDeviceGroupRow(
								icon = Icons.Rounded.Person,
								title = artist.name,
								subtitle = "${artist.albumCount} 张专辑 · ${artist.tracks.size} 首",
								isFirst = idx == 0,
								isLast = idx == filteredArtists.lastIndex,
								onClick = { openedArtistName = artist.name },
								onPlayAll = {
									if (artist.tracks.isNotEmpty()) {
										audioPlayer.playQueue(artist.tracks, 0)
									}
								},
							)
							if (idx < filteredArtists.lastIndex) {
								IosHairlineDivider(startIndent = 64.dp)
							}
						}
					}
				}

				subTab == IosDeviceSubTab.ALBUMS && activeAlbum == null -> {
					if (filteredAlbums.isEmpty() && !deviceState.scanning) {
						item(key = "ios-dev-albums-empty") {
							IosDeviceLocalEmptyState(
								deviceState = deviceState,
								normalizedQuery = normalizedQuery,
								emptySearchMessage = "没有匹配的本地专辑",
								onOpenManageSheet = { showManageSheet = true },
							)
						}
					} else {
						itemsIndexed(
							items = filteredAlbums,
							key = { _, album -> "ios_dev_album_${album.name}" },
						) { idx, album ->
							IosDeviceGroupRow(
								icon = Icons.Rounded.Album,
								title = album.name,
								subtitle = "${album.artist} · ${album.tracks.size} 首",
								isFirst = idx == 0,
								isLast = idx == filteredAlbums.lastIndex,
								onClick = { openedAlbumName = album.name },
								onPlayAll = {
									if (album.tracks.isNotEmpty()) {
										audioPlayer.playQueue(album.tracks, 0)
									}
								},
							)
							if (idx < filteredAlbums.lastIndex) {
								IosHairlineDivider(startIndent = 64.dp)
							}
						}
					}
				}

				subTab == IosDeviceSubTab.FOLDERS && activeFolder == null -> {
					if (filteredFolders.isEmpty() && !deviceState.scanning) {
						item(key = "ios-dev-folders-empty") {
							IosDeviceLocalEmptyState(
								deviceState = deviceState,
								normalizedQuery = normalizedQuery,
								emptySearchMessage = "没有匹配的本地目录",
								onOpenManageSheet = { showManageSheet = true },
							)
						}
					} else {
						itemsIndexed(
							items = filteredFolders,
							key = { _, folder -> "ios_dev_folder_${folder.path}" },
						) { idx, folder ->
							IosDeviceGroupRow(
								icon = Icons.Rounded.Folder,
								title = folder.name,
								subtitle = "${folder.path} · ${folder.tracks.size} 首",
								isFirst = idx == 0,
								isLast = idx == filteredFolders.lastIndex,
								onClick = { openedFolderPath = folder.path },
								onPlayAll = {
									if (folder.tracks.isNotEmpty()) {
										audioPlayer.playQueue(folder.tracks, 0)
									}
								},
							)
							if (idx < filteredFolders.lastIndex) {
								IosHairlineDivider(startIndent = 64.dp)
							}
						}
					}
				}

				else -> {
					if (displayedTracks.isEmpty() && !deviceState.scanning) {
						item(key = "ios-dev-tracks-empty") {
							IosDeviceLocalEmptyState(
								deviceState = deviceState,
								normalizedQuery = normalizedQuery,
								emptySearchMessage = "没有匹配的本地曲目",
								onOpenManageSheet = { showManageSheet = true },
							)
						}
					} else {
						itemsIndexed(
							items = displayedTracks,
							key = { _, track -> track.id },
						) { idx, track ->
							val isPlaying = currentTrackId == track.id
							val isSelected = selectedIds[track.id] == true
							var menuOpen by remember { mutableStateOf(false) }

							Surface(
								shape = iosLibGroupedItemShape(
									isFirst = idx == 0,
									isLast = idx == displayedTracks.lastIndex,
								),
								color = when {
									isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.11f)
									isPlaying -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
									else -> MaterialTheme.colorScheme.surfaceContainer
								},
								modifier = Modifier
									.fillMaxWidth()
									.combinedClickable(
										onClick = {
											if (multiSelectMode) {
												if (isSelected) {
													selectedIds.remove(track.id)
												} else {
													selectedIds[track.id] = true
												}
											} else {
												audioPlayer.playQueue(displayedTracks, idx)
											}
										},
										onLongClick = {
											if (!multiSelectMode) {
												multiSelectMode = true
											}
											selectedIds[track.id] = true
										},
									),
							) {
								Row(
									modifier = Modifier
										.fillMaxWidth()
										.padding(horizontal = 12.dp, vertical = 9.dp),
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.spacedBy(10.dp),
								) {
									if (multiSelectMode) {
										Checkbox(
											checked = isSelected,
											onCheckedChange = { checked ->
												if (checked) {
													selectedIds[track.id] = true
												} else {
													selectedIds.remove(track.id)
												}
											},
										)
									} else {
										Box(
											modifier = Modifier
												.size(42.dp)
												.clip(RoundedCornerShape(9.dp))
												.background(MaterialTheme.colorScheme.surfaceContainerHigh),
											contentAlignment = Alignment.Center,
										) {
											Icon(
												imageVector = Icons.Rounded.MusicNote,
												contentDescription = null,
												tint = if (isPlaying) {
													MaterialTheme.colorScheme.primary
												} else {
													MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
												},
												modifier = Modifier.size(18.dp),
											)
										}
									}

									Column(modifier = Modifier.weight(1f)) {
										Text(
											text = track.title,
											style = MaterialTheme.typography.bodyMedium,
											fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Medium,
											color = if (isPlaying) MaterialTheme.colorScheme.primary else Color.Unspecified,
											maxLines = 1,
											overflow = TextOverflow.Ellipsis,
										)
										val parts = buildList {
											if (track.artist.isNotBlank()) add(track.artist)
											track.fileExtension?.uppercase()?.let(::add)
											if (track.durationMs > 0L) add(formatDurationMs(track.durationMs))
											if (track.sizeBytes > 0L) add(formatIosDeviceSize(track.sizeBytes))
										}
										Text(
											text = parts.joinToString(" · "),
											style = MaterialTheme.typography.bodySmall.tabularNums(),
											color = MaterialTheme.colorScheme.onSurfaceVariant,
											maxLines = 1,
											overflow = TextOverflow.Ellipsis,
										)
									}

									if (!multiSelectMode) {
										Box {
											IconButton(
												onClick = { menuOpen = true },
												modifier = Modifier.size(32.dp),
											) {
												Icon(
													imageVector = Icons.Rounded.MoreVert,
													contentDescription = "更多",
													tint = MaterialTheme.colorScheme.onSurfaceVariant,
													modifier = Modifier.size(18.dp),
												)
											}
											DropdownMenu(
												expanded = menuOpen,
												onDismissRequest = { menuOpen = false },
											) {
												DropdownMenuItem(
													text = { Text("立即播放") },
													leadingIcon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
													onClick = {
														menuOpen = false
														audioPlayer.playQueue(displayedTracks, idx)
													},
												)
												DropdownMenuItem(
													text = { Text("下一首播放") },
													leadingIcon = { Icon(Icons.Rounded.QueueMusic, contentDescription = null) },
													onClick = {
														menuOpen = false
														audioPlayer.playNext(track)
													},
												)
												DropdownMenuItem(
													text = { Text("加入队列") },
													leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, contentDescription = null) },
													onClick = {
														menuOpen = false
														audioPlayer.appendToQueue(listOf(track))
													},
												)
												DropdownMenuItem(
													text = { Text("歌曲信息") },
													leadingIcon = { Icon(Icons.Rounded.LibraryMusic, contentDescription = null) },
													onClick = {
														menuOpen = false
														infoDialogTrack = track
													},
												)
												DropdownMenuItem(
													text = { Text("从列表隐藏") },
													leadingIcon = { Icon(Icons.Rounded.Close, contentDescription = null) },
													onClick = {
														menuOpen = false
														IosDeviceMusicManager.hideTracks(setOf(track.id))
													},
												)
												HorizontalDivider()
												DropdownMenuItem(
													text = {
														Text(
															text = "删除文件",
															color = MaterialTheme.colorScheme.error,
														)
													},
													leadingIcon = {
														Icon(
															Icons.Rounded.Delete,
															contentDescription = null,
															tint = MaterialTheme.colorScheme.error,
														)
													},
													onClick = {
														menuOpen = false
														pendingDeleteTracks = listOf(track)
													},
												)
											}
										}
									}
								}
							}
							if (idx < displayedTracks.lastIndex) {
								IosHairlineDivider(startIndent = if (multiSelectMode) 56.dp else 64.dp)
							}
						}
					}
				}
			}
		}

		// 底部多选批量操作条
		AnimatedVisibility(
			visible = showBatchDock,
			enter = slideInVertically(tween(180)) { it / 2 } + fadeIn(tween(140)),
			exit = slideOutVertically(tween(140)) { it / 2 } + fadeOut(tween(100)),
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.padding(
					start = 16.dp,
					end = 16.dp,
					bottom = bottomPadding.calculateBottomPadding() + 8.dp,
				),
		) {
			IosDeviceBatchActionDock(
				selectedCount = selectedTracks.size,
				totalCount = displayedTracks.size,
				onToggleSelectAll = {
					if (selectedTracks.size >= displayedTracks.size && displayedTracks.isNotEmpty()) {
						selectedIds.clear()
					} else {
						displayedTracks.forEach { selectedIds[it.id] = true }
					}
				},
				onExit = {
					multiSelectMode = false
					selectedIds.clear()
				},
				onPlayNow = {
					if (selectedTracks.isNotEmpty()) {
						audioPlayer.playQueue(selectedTracks, 0)
						multiSelectMode = false
						selectedIds.clear()
					}
				},
				onPlayNext = {
					if (selectedTracks.isNotEmpty()) {
						selectedTracks.asReversed().forEach { audioPlayer.playNext(it) }
						multiSelectMode = false
						selectedIds.clear()
					}
				},
				onAppendQueue = {
					if (selectedTracks.isNotEmpty()) {
						audioPlayer.appendToQueue(selectedTracks)
						multiSelectMode = false
						selectedIds.clear()
					}
				},
				onHideFromList = {
					if (selectedTracks.isNotEmpty()) {
						IosDeviceMusicManager.hideTracks(selectedTracks.mapTo(HashSet()) { it.id })
						multiSelectMode = false
						selectedIds.clear()
					}
				},
				onRequestDeleteFiles = {
					if (selectedTracks.isNotEmpty()) {
						pendingDeleteTracks = selectedTracks
					}
				},
			)
		}
	}

	// 「扫描与目录管理」ModalBottomSheet
	if (showManageSheet) {
		IosDeviceScanAndFolderSheet(
			deviceState = deviceState,
			onDismiss = { showManageSheet = false },
			onRescan = { IosDeviceMusicManager.refresh() },
			onImportAudio = {
				showManageSheet = false
				IosDeviceMusicManager.presentAudioPicker()
			},
			onImportFolder = {
				showManageSheet = false
				IosDeviceMusicManager.presentFolderPicker()
			},
			onToggleFolderExcluded = { path, excluded ->
				IosDeviceMusicManager.setFolderExcluded(path, excluded)
			},
			onSelectMinDuration = { sec ->
				IosDeviceMusicManager.setMinDurationSec(sec)
			},
			onRestoreHidden = {
				IosDeviceMusicManager.restoreHiddenTracks()
			},
		)
	}

	// 歌曲信息弹窗
	infoDialogTrack?.let { track ->
		val addedSec = deviceState.addedAtById[track.id] ?: 0L
		AlertDialog(
			onDismissRequest = { infoDialogTrack = null },
			containerColor = MaterialTheme.colorScheme.surfaceContainer,
			title = {
				Text(
					text = "歌曲信息",
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.SemiBold,
				)
			},
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					IosDeviceInfoRow("歌曲名称", track.title)
					IosDeviceInfoRow("歌手", track.artist.ifBlank { "未知歌手" })
					IosDeviceInfoRow("专辑", track.album.ifBlank { "未知专辑" })
					IosDeviceInfoRow("音频格式", track.fileExtension?.uppercase()?.ifBlank { null } ?: "未知")
					IosDeviceInfoRow("文件大小", if (track.sizeBytes > 0L) formatIosDeviceSize(track.sizeBytes) else "未知")
					IosDeviceInfoRow("曲目时长", if (track.durationMs > 0L) formatDurationMs(track.durationMs) else "未知")
					if (addedSec > 0L) {
						IosDeviceInfoRow("修改时间戳", "$addedSec")
					}
					IosDeviceInfoRow("文件路径", track.rawId)
				}
			},
			confirmButton = {
				TextButton(onClick = { infoDialogTrack = null }) {
					Text("关闭")
				}
			},
		)
	}

	// 删除本地文件确认弹窗
	pendingDeleteTracks?.let { targets ->
		val summary = if (targets.size == 1) {
			"确定永久删除「${targets.first().title}」的本地音频文件吗？此操作不可撤销。"
		} else {
			"确定永久删除选中的 ${targets.size} 首本地音频文件吗？此操作不可撤销。"
		}
		AlertDialog(
			onDismissRequest = { pendingDeleteTracks = null },
			containerColor = MaterialTheme.colorScheme.surfaceContainer,
			title = { Text("删除本地文件") },
			text = { Text(summary) },
			confirmButton = {
				Button(
					onClick = {
						IosDeviceMusicManager.deleteTracks(targets)
						pendingDeleteTracks = null
						multiSelectMode = false
						selectedIds.clear()
					},
					colors = ButtonDefaults.buttonColors(
						containerColor = MaterialTheme.colorScheme.error,
						contentColor = MaterialTheme.colorScheme.onError,
					),
				) {
					Text("确认删除")
				}
			},
			dismissButton = {
				TextButton(onClick = { pendingDeleteTracks = null }) {
					Text("取消")
				}
			},
		)
	}
}

@Composable
private fun IosDeviceDrilldownHeader(
	title: String,
	subtitle: String,
	onBack: () -> Unit,
) {
	Surface(
		modifier = Modifier
			.fillMaxWidth()
			.padding(bottom = 10.dp),
		shape = RoundedCornerShape(14.dp),
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = iosLibHairlineBorder(),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 8.dp, vertical = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			IconButton(
				onClick = onBack,
				modifier = Modifier.size(34.dp),
			) {
				Icon(
					imageVector = Icons.Rounded.ArrowBack,
					contentDescription = "返回",
					modifier = Modifier.size(18.dp),
				)
			}
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = title,
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.SemiBold,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				Text(
					text = subtitle,
					style = MaterialTheme.typography.labelSmall.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}

@Composable
private fun IosDeviceActionPill(
	icon: ImageVector,
	label: String,
	active: Boolean = false,
	showDot: Boolean = false,
	onClick: () -> Unit,
) {
	Surface(
		onClick = onClick,
		shape = CircleShape,
		color = if (active) {
			MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
		} else {
			MaterialTheme.colorScheme.surfaceContainer
		},
		border = BorderStroke(
			IosHairlineWidth,
			if (active) {
				MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
			} else {
				iosLibHairlineBorderColor()
			},
		),
	) {
		Row(
			modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(5.dp),
		) {
			Icon(
				imageVector = icon,
				contentDescription = null,
				tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(14.dp),
			)
			Text(
				text = label,
				style = MaterialTheme.typography.labelMedium.tabularNums(),
				fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
				color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				softWrap = false,
			)
			if (showDot) {
				Box(
					modifier = Modifier
						.size(6.dp)
						.clip(CircleShape)
						.background(MaterialTheme.colorScheme.primary),
				)
			}
		}
	}
}

@Composable
private fun IosDeviceGroupRow(
	icon: ImageVector,
	title: String,
	subtitle: String,
	isFirst: Boolean,
	isLast: Boolean,
	onClick: () -> Unit,
	onPlayAll: () -> Unit,
) {
	Surface(
		shape = iosLibGroupedItemShape(isFirst = isFirst, isLast = isLast),
		color = MaterialTheme.colorScheme.surfaceContainer,
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 12.dp, vertical = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Box(
				modifier = Modifier
					.size(40.dp)
					.clip(RoundedCornerShape(10.dp))
					.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
				contentAlignment = Alignment.Center,
			) {
				Icon(
					imageVector = icon,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier.size(20.dp),
				)
			}
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = title,
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = FontWeight.Medium,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				Text(
					text = subtitle,
					style = MaterialTheme.typography.bodySmall.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
			IconButton(
				onClick = onPlayAll,
				modifier = Modifier.size(32.dp),
			) {
				Icon(
					imageVector = Icons.Rounded.PlaylistPlay,
					contentDescription = "播放全部",
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier.size(18.dp),
				)
			}
			Icon(
				imageVector = Icons.Rounded.ChevronRight,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
				modifier = Modifier.size(16.dp),
			)
		}
	}
}

@Composable
private fun IosDeviceLocalEmptyState(
	deviceState: IosDeviceMusicState,
	normalizedQuery: String,
	emptySearchMessage: String,
	onOpenManageSheet: () -> Unit,
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = 28.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		when {
			normalizedQuery.isNotBlank() -> {
				IosQuietEmptyState(
					message = emptySearchMessage,
					icon = Icons.Rounded.LibraryMusic,
				)
			}
			deviceState.totalRawCount > 0 -> {
				IosQuietEmptyState(
					message = "扫描到 ${deviceState.totalRawCount} 个文件，均已被当前过滤规则屏蔽或隐藏",
					icon = Icons.Rounded.Tune,
					actionLabel = "打开扫描与目录管理",
					onAction = onOpenManageSheet,
				)
			}
			else -> {
				IosQuietEmptyState(
					message = "暂无本地音乐，可导入音频/文件夹，或通过 iOS「文件」App 将音乐放入 MeloKeep 目录",
					icon = Icons.Rounded.Folder,
				)
				Row(
					horizontalArrangement = Arrangement.spacedBy(10.dp),
				) {
					FilledTonalButton(
						onClick = { IosDeviceMusicManager.presentAudioPicker() },
						shape = RoundedCornerShape(12.dp),
					) {
						Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
						Spacer(Modifier.width(4.dp))
						Text("导入音频")
					}
					OutlinedButton(
						onClick = { IosDeviceMusicManager.presentFolderPicker() },
						shape = RoundedCornerShape(12.dp),
					) {
						Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
						Spacer(Modifier.width(4.dp))
						Text("选择文件夹导入")
					}
				}
			}
		}
	}
}

@Composable
private fun IosDeviceBatchActionDock(
	selectedCount: Int,
	totalCount: Int,
	onToggleSelectAll: () -> Unit,
	onExit: () -> Unit,
	onPlayNow: () -> Unit,
	onPlayNext: () -> Unit,
	onAppendQueue: () -> Unit,
	onHideFromList: () -> Unit,
	onRequestDeleteFiles: () -> Unit,
) {
	val isAllSelected = totalCount > 0 && selectedCount >= totalCount
	val hasSelection = selectedCount > 0
	Surface(
		shape = RoundedCornerShape(18.dp),
		color = MaterialTheme.colorScheme.meloGlassSurface,
		border = iosLibHairlineBorder(),
		shadowElevation = 8.dp,
		modifier = Modifier.fillMaxWidth(),
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 12.dp, vertical = 10.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = "已选 $selectedCount / $totalCount 首",
					style = MaterialTheme.typography.labelLarge.tabularNums(),
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.onSurface,
				)
				Row(
					horizontalArrangement = Arrangement.spacedBy(4.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Surface(
						shape = CircleShape,
						color = MaterialTheme.colorScheme.surfaceContainerHigh,
						border = iosLibHairlineBorder(),
						modifier = Modifier
							.clip(CircleShape)
							.clickable(onClick = onToggleSelectAll),
					) {
						Row(
							modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(4.dp),
						) {
							Icon(
								imageVector = if (isAllSelected) Icons.Rounded.CheckBox else Icons.Rounded.DoneAll,
								contentDescription = null,
								modifier = Modifier.size(14.dp),
								tint = MaterialTheme.colorScheme.primary,
							)
							Text(
								text = if (isAllSelected) "取消全选" else "全选",
								style = MaterialTheme.typography.labelMedium,
								color = MaterialTheme.colorScheme.primary,
								maxLines = 1,
								softWrap = false,
							)
						}
					}
					IconButton(onClick = onExit, modifier = Modifier.size(28.dp)) {
						Icon(
							imageVector = Icons.Rounded.Close,
							contentDescription = "退出多选",
							modifier = Modifier.size(16.dp),
						)
					}
				}
			}

			Row(
				modifier = Modifier
					.fillMaxWidth()
					.horizontalScroll(rememberScrollState()),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				IosLibDockPill(
					label = "立即播放",
					icon = Icons.Rounded.PlayArrow,
					emphasized = true,
					onClick = { if (hasSelection) onPlayNow() },
				)
				IosLibDockPill(
					label = "下一首",
					icon = Icons.Rounded.PlaylistPlay,
					onClick = { if (hasSelection) onPlayNext() },
				)
				IosLibDockPill(
					label = "加入队列",
					icon = Icons.Rounded.PlaylistAdd,
					onClick = { if (hasSelection) onAppendQueue() },
				)
				IosLibDockPill(
					label = "从列表移除",
					icon = Icons.Rounded.Close,
					onClick = { if (hasSelection) onHideFromList() },
				)
				Surface(
					shape = CircleShape,
					color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
					contentColor = MaterialTheme.colorScheme.error,
					border = BorderStroke(IosHairlineWidth, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
					modifier = Modifier
						.clip(CircleShape)
						.clickable(enabled = hasSelection, onClick = onRequestDeleteFiles),
				) {
					Row(
						modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(4.dp),
					) {
						Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
						Text(
							text = "删除文件",
							style = MaterialTheme.typography.labelMedium,
							maxLines = 1,
							softWrap = false,
						)
					}
				}
			}
		}
	}
}

@Composable
private fun IosDeviceScanAndFolderSheet(
	deviceState: IosDeviceMusicState,
	onDismiss: () -> Unit,
	onRescan: () -> Unit,
	onImportAudio: () -> Unit,
	onImportFolder: () -> Unit,
	onToggleFolderExcluded: (path: String, excluded: Boolean) -> Unit,
	onSelectMinDuration: (Int) -> Unit,
	onRestoreHidden: () -> Unit,
) {
	val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

	ModalBottomSheet(
		onDismissRequest = onDismiss,
		sheetState = sheetState,
		containerColor = MaterialTheme.colorScheme.background,
		contentColor = MaterialTheme.colorScheme.onSurface,
		dragHandle = null,
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.navigationBarsPadding(),
		) {
			IosCleanTopBar(
				title = "扫描与目录管理",
				onBack = onDismiss,
			)

			LazyColumn(
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(max = 520.dp),
				contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
				verticalArrangement = Arrangement.spacedBy(16.dp),
			) {
				// 1. 重新扫描 & 收录统计
				item(key = "sheet-scan-summary") {
					IosGroupedCard {
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.padding(horizontal = 14.dp, vertical = 12.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(12.dp),
						) {
							Column(
								modifier = Modifier.weight(1f),
								verticalArrangement = Arrangement.spacedBy(3.dp),
							) {
								Text(
									text = "已收录 ${deviceState.allTracks.size} 首 · 共扫描 ${deviceState.totalRawCount} 首",
									style = MaterialTheme.typography.bodyMedium.tabularNums(),
									fontWeight = FontWeight.Medium,
								)
								val filteredText = if (deviceState.filteredOutCount > 0) {
									"已过滤 ${deviceState.filteredOutCount} 首（短音频 / 屏蔽目录 / 隐藏）"
								} else {
									"涉及 ${deviceState.allFolders.size} 个本地目录"
								}
								Text(
									text = filteredText,
									style = MaterialTheme.typography.bodySmall.tabularNums(),
									color = MaterialTheme.colorScheme.onSurfaceVariant,
								)
							}
							FilledTonalButton(
								onClick = onRescan,
								enabled = !deviceState.scanning,
								shape = CircleShape,
								contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
								modifier = Modifier.height(34.dp),
							) {
								if (deviceState.scanning) {
									CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
									Spacer(Modifier.width(6.dp))
									Text("扫描中")
								} else {
									Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
									Spacer(Modifier.width(4.dp))
									Text("重新扫描")
								}
							}
						}
					}
				}

				// 2. 短音频过滤胶囊 & 恢复已隐藏
				item(key = "sheet-duration-filter") {
					Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
						IosSectionLabel(title = "智能过滤")
						IosGroupedCard {
							Column(
								modifier = Modifier
									.fillMaxWidth()
									.padding(horizontal = 14.dp, vertical = 12.dp),
								verticalArrangement = Arrangement.spacedBy(8.dp),
							) {
								Text(
									text = "短音频过滤",
									style = MaterialTheme.typography.bodyMedium,
									fontWeight = FontWeight.Medium,
								)
								FlowRow(
									horizontalArrangement = Arrangement.spacedBy(8.dp),
									verticalArrangement = Arrangement.spacedBy(8.dp),
								) {
									listOf(
										0 to "关闭",
										30 to "< 30秒",
										60 to "< 60秒",
									).forEach { (sec, label) ->
										val selected = deviceState.minDurationSec == sec
										Surface(
											onClick = { onSelectMinDuration(sec) },
											shape = CircleShape,
											color = if (selected) {
												MaterialTheme.colorScheme.primary
											} else {
												MaterialTheme.colorScheme.surfaceContainerHigh
											},
											border = if (selected) null else iosLibHairlineBorder(),
										) {
											Text(
												text = label,
												style = MaterialTheme.typography.labelMedium,
												fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
												color = if (selected) {
													MaterialTheme.colorScheme.onPrimary
												} else {
													MaterialTheme.colorScheme.onSurface
												},
												modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
											)
										}
									}
								}
								Text(
									text = "仅对成功读取时长的音频生效，避免未解析时长的罕见格式被误过滤。",
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
								)
							}

							if (deviceState.hiddenCount > 0) {
								IosHairlineDivider(startIndent = 14.dp)
								Row(
									modifier = Modifier
										.fillMaxWidth()
										.clickable(onClick = onRestoreHidden)
										.padding(horizontal = 14.dp, vertical = 12.dp),
									horizontalArrangement = Arrangement.SpaceBetween,
									verticalAlignment = Alignment.CenterVertically,
								) {
									Text(
										text = "恢复已隐藏曲目",
										style = MaterialTheme.typography.bodyMedium,
										fontWeight = FontWeight.Medium,
									)
									Text(
										text = "恢复 ${deviceState.hiddenCount} 首",
										style = MaterialTheme.typography.labelMedium.tabularNums(),
										fontWeight = FontWeight.SemiBold,
										color = MaterialTheme.colorScheme.primary,
									)
								}
							}
						}
					}
				}

				// 3. 目录勾选列表
				item(key = "sheet-folder-header") {
					val includedCount = deviceState.allFolders.count { !it.excluded }
					IosSectionLabel(
						title = "目录展示 (${includedCount}/${deviceState.allFolders.size})",
					)
				}

				if (deviceState.allFolders.isEmpty()) {
					item(key = "sheet-folders-empty") {
						IosGroupedCard {
							IosQuietEmptyState(
								message = "暂未扫描到音频目录，点击下方按钮导入音频或文件夹",
								icon = Icons.Rounded.Folder,
							)
						}
					}
				} else {
					item(key = "sheet-folders-list") {
						IosGroupedCard {
							deviceState.allFolders.forEachIndexed { idx, folder ->
								val included = !folder.excluded
								Row(
									modifier = Modifier
										.fillMaxWidth()
										.clickable { onToggleFolderExcluded(folder.path, included) }
										.padding(horizontal = 14.dp, vertical = 10.dp),
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.spacedBy(10.dp),
								) {
									Checkbox(
										checked = included,
										onCheckedChange = { checked ->
											onToggleFolderExcluded(folder.path, !checked)
										},
									)
									Column(modifier = Modifier.weight(1f)) {
										Text(
											text = folder.name,
											style = MaterialTheme.typography.bodyMedium,
											fontWeight = FontWeight.Medium,
											color = if (included) {
												MaterialTheme.colorScheme.onSurface
											} else {
												MaterialTheme.colorScheme.onSurfaceVariant
											},
											maxLines = 1,
											overflow = TextOverflow.Ellipsis,
										)
										Text(
											text = "${folder.path} · ${folder.rawTrackCount} 首",
											style = MaterialTheme.typography.bodySmall.tabularNums(),
											color = MaterialTheme.colorScheme.onSurfaceVariant,
											maxLines = 1,
											overflow = TextOverflow.Ellipsis,
										)
									}
								}
								if (idx < deviceState.allFolders.lastIndex) {
									IosHairlineDivider(startIndent = 52.dp)
								}
							}
						}
					}
				}
			}

			// 4. 底部导入操作栏
			IosHairlineDivider()
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp, vertical = 12.dp),
				verticalArrangement = Arrangement.spacedBy(6.dp),
			) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.spacedBy(10.dp),
				) {
					FilledTonalButton(
						onClick = onImportAudio,
						modifier = Modifier.weight(1f),
						shape = RoundedCornerShape(12.dp),
					) {
						Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
						Spacer(Modifier.width(4.dp))
						Text("导入音频", maxLines = 1)
					}
					OutlinedButton(
						onClick = onImportFolder,
						modifier = Modifier.weight(1f),
						shape = RoundedCornerShape(12.dp),
					) {
						Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
						Spacer(Modifier.width(4.dp))
						Text("选择文件夹导入", maxLines = 1)
					}
				}
				Text(
					text = "支持从 iOS「文件」导入目录树与伴随 .lrc / .txt 歌词文件",
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

@Composable
private fun IosDeviceInfoRow(
	label: String,
	value: String,
) {
	Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
		Text(
			text = label,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Text(
			text = value,
			style = MaterialTheme.typography.bodySmall.tabularNums(),
			color = MaterialTheme.colorScheme.onSurface,
		)
	}
}

private fun formatIosDeviceSize(bytes: Long): String {
	if (bytes <= 0L) return "0 B"
	val kb = bytes / 1024.0
	if (kb < 1024.0) return "${kb.toInt()} KB"
	val mb = kb / 1024.0
	val whole = mb.toInt()
	val tenths = ((mb - whole) * 10.0).toInt().coerceIn(0, 9)
	return "$whole.$tenths MB"
}
