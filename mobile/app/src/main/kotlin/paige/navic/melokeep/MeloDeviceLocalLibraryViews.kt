@file:OptIn(ExperimentalMaterial3Api::class)

package paige.navic.melokeep

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import paige.navic.domain.manager.runtime.PermissionManager
import paige.navic.domain.model.DomainSong
import paige.navic.domain.repository.DeviceAudioFolder
import paige.navic.domain.repository.DeviceLocalMusicRepository
import paige.navic.playback.MediaPlayer
import paige.navic.ui.component.common.CoverArt
import paige.navic.ui.component.layout.PullToRefreshBox
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.ArrowBack
import paige.navic.ui.icons.outlined.ChevronForward
import paige.navic.ui.icons.outlined.LibraryMusic
import paige.navic.ui.icons.outlined.MoreVert
import paige.navic.ui.icons.outlined.Note
import paige.navic.ui.icons.outlined.PlaylistAdd
import paige.navic.ui.icons.outlined.PlaylistPlay
import paige.navic.ui.icons.outlined.QueuePlayNext
import paige.navic.ui.icons.outlined.Shuffle
import paige.navic.ui.theme.MeloBottomChromeClearance
import paige.navic.ui.theme.tabularNums
import paige.navic.util.toFileSize

private enum class DeviceLocalSubTab(val label: String) {
	FOLDERS("文件夹"),
	SONGS("全部歌曲")
}

@Composable
internal fun MeloDeviceLocalLibraryView(
	searchQuery: String,
	currentPlayingPair: Pair<String?, Boolean>,
	hostNavBottom: Dp,
	modifier: Modifier = Modifier
) {
	val deviceRepo = koinInject<DeviceLocalMusicRepository>()
	val permissionManager = koinInject<PermissionManager>()
	val player = koinInject<MediaPlayer>()
	val lifecycleOwner = LocalLifecycleOwner.current
	val scope = rememberCoroutineScope()

	val deviceState by deviceRepo.state.collectAsStateWithLifecycle()

	var subTab by rememberSaveable { mutableStateOf(DeviceLocalSubTab.FOLDERS) }
	var openedFolderPath by rememberSaveable { mutableStateOf<String?>(null) }

	LaunchedEffect(Unit) {
		deviceRepo.refresh()
	}

	DisposableEffect(lifecycleOwner) {
		val observer = LifecycleEventObserver { _, event ->
			if (event == Lifecycle.Event.ON_RESUME) {
				if (deviceRepo.hasPermission() && deviceState.allSongs.isEmpty() && !deviceState.loading) {
					scope.launch { deviceRepo.refresh() }
				}
			}
		}
		lifecycleOwner.lifecycle.addObserver(observer)
		onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
	}

	BackHandler(enabled = openedFolderPath != null && subTab == DeviceLocalSubTab.FOLDERS) {
		openedFolderPath = null
	}

	val normalizedQuery = searchQuery.trim()
	val filteredFolders = remember(deviceState.folders, normalizedQuery) {
		if (normalizedQuery.isBlank()) {
			deviceState.folders
		} else {
			deviceState.folders.filter { folder ->
				folder.name.contains(normalizedQuery, ignoreCase = true) ||
					folder.path.contains(normalizedQuery, ignoreCase = true) ||
					folder.songs.any { s ->
						s.title.contains(normalizedQuery, ignoreCase = true) ||
							(s.artistName?.contains(normalizedQuery, ignoreCase = true) == true)
					}
			}
		}
	}
	val filteredAllSongs = remember(deviceState.allSongs, normalizedQuery) {
		if (normalizedQuery.isBlank()) {
			deviceState.allSongs
		} else {
			deviceState.allSongs.filter { s ->
				s.title.contains(normalizedQuery, ignoreCase = true) ||
					(s.artistName?.contains(normalizedQuery, ignoreCase = true) == true) ||
					(s.albumTitle?.contains(normalizedQuery, ignoreCase = true) == true) ||
					(s.parentId?.contains(normalizedQuery, ignoreCase = true) == true)
			}
		}
	}
	val activeFolder = remember(deviceState.folders, openedFolderPath) {
		openedFolderPath?.let { target -> deviceState.folders.firstOrNull { it.path == target } }
	}
	val displayedFolderSongs = remember(activeFolder, normalizedQuery) {
		val base = activeFolder?.songs.orEmpty()
		if (normalizedQuery.isBlank()) {
			base
		} else {
			base.filter { s ->
				s.title.contains(normalizedQuery, ignoreCase = true) ||
					(s.artistName?.contains(normalizedQuery, ignoreCase = true) == true) ||
					(s.albumTitle?.contains(normalizedQuery, ignoreCase = true) == true)
			}
		}
	}

	if (!deviceState.hasPermission) {
		Box(
			modifier = modifier
				.fillMaxSize()
				.padding(horizontal = 24.dp)
				.padding(bottom = MeloBottomChromeClearance + hostNavBottom),
			contentAlignment = Alignment.Center
		) {
			Column(
				modifier = Modifier.widthIn(max = 360.dp),
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.spacedBy(12.dp)
			) {
				MeloQuietEmptyState(
					message = "需要访问手机音频权限以浏览并播放本地文件夹歌曲",
					icon = MeloIcons.Folder
				)
				Button(
					onClick = {
						scope.launch {
							if (permissionManager.requestAudioStoragePermission()) {
								deviceRepo.refresh()
							}
						}
					},
					modifier = Modifier
						.fillMaxWidth()
						.height(44.dp),
					shape = RoundedCornerShape(12.dp)
				) {
					Text("授权访问手机音频")
				}
				OutlinedButton(
					onClick = { permissionManager.openPermissionsSettings() },
					modifier = Modifier
						.fillMaxWidth()
						.height(42.dp),
					shape = RoundedCornerShape(12.dp)
				) {
					Text("前往系统权限设置")
				}
			}
		}
		return
	}

	PullToRefreshBox(
		modifier = modifier.fillMaxSize(),
		finished = !deviceState.loading,
		onRefresh = { scope.launch { deviceRepo.refresh() } },
		key = listOf(deviceState.loading, deviceState.allSongs.size, openedFolderPath, subTab)
	) {
		LazyColumn(
			modifier = Modifier.fillMaxSize(),
			contentPadding = PaddingValues(
				start = 16.dp,
				end = 16.dp,
				top = 8.dp,
				bottom = MeloBottomChromeClearance + hostNavBottom
			),
			verticalArrangement = Arrangement.spacedBy(0.dp)
		) {
			item(key = "device-subtabs-header") {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(bottom = 10.dp),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically
				) {
					Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						DeviceLocalSubTab.entries.forEach { tab ->
							val selected = subTab == tab
							Surface(
								onClick = {
									subTab = tab
									if (tab == DeviceLocalSubTab.SONGS) {
										openedFolderPath = null
									}
								},
								shape = CircleShape,
								color = if (selected) {
									MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
								} else {
									MaterialTheme.colorScheme.surfaceContainer
								},
								border = BorderStroke(
									MeloHairlineWidth,
									if (selected) {
										MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
									} else {
										libraryHairlineBorderColor()
									}
								)
							) {
								Text(
									text = tab.label,
									style = MaterialTheme.typography.labelMedium.copy(
										fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
									),
									color = if (selected) {
										MaterialTheme.colorScheme.primary
									} else {
										MaterialTheme.colorScheme.onSurfaceVariant
									},
									modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
								)
							}
						}
					}

					Text(
						text = "${deviceState.folders.size} 目录 · ${deviceState.allSongs.size} 首",
						style = MaterialTheme.typography.labelMedium.tabularNums(),
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
				}
			}

			val errorMsg = deviceState.error
			if (!errorMsg.isNullOrBlank()) {
				item(key = "device-error") {
					Surface(
						modifier = Modifier
							.fillMaxWidth()
							.padding(bottom = 10.dp),
						shape = RoundedCornerShape(12.dp),
						color = MaterialTheme.colorScheme.errorContainer
					) {
						Text(
							text = errorMsg,
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onErrorContainer,
							modifier = Modifier.padding(12.dp)
						)
					}
				}
			}

			if (subTab == DeviceLocalSubTab.FOLDERS && activeFolder == null) {
				if (filteredFolders.isEmpty() && !deviceState.loading) {
					item(key = "device-folders-empty") {
						MeloQuietEmptyState(
							message = if (normalizedQuery.isBlank()) "未扫描到手机本地音频文件" else "没有匹配的文件夹或歌曲",
							icon = MeloIcons.Folder,
							actionLabel = if (normalizedQuery.isBlank()) "重新扫描" else null,
							onAction = if (normalizedQuery.isBlank()) {
								{ scope.launch { deviceRepo.refresh() } }
							} else {
								null
							},
							modifier = Modifier
								.fillMaxWidth()
								.padding(vertical = 48.dp)
						)
					}
				} else {
					itemsIndexed(
						items = filteredFolders,
						key = { _, folder -> "dev_folder_${folder.path}" }
					) { idx, folder ->
						DeviceFolderRow(
							folder = folder,
							shape = libraryGroupedItemShape(
								isFirst = idx == 0,
								isLast = idx == filteredFolders.lastIndex
							),
							onOpen = { openedFolderPath = folder.path },
							onPlayFolder = {
								if (folder.songs.isNotEmpty()) {
									player.playNow(folder.songs, 0)
								}
							}
						)
						if (idx < filteredFolders.lastIndex) {
							MeloHairlineDivider(startIndent = 58.dp)
						}
					}
				}
			} else {
				val currentSongs = if (subTab == DeviceLocalSubTab.FOLDERS) {
					displayedFolderSongs
				} else {
					filteredAllSongs
				}

				if (activeFolder != null && subTab == DeviceLocalSubTab.FOLDERS) {
					item(key = "device-folder-breadcrumb") {
						Surface(
							modifier = Modifier
								.fillMaxWidth()
								.padding(bottom = 10.dp),
							shape = RoundedCornerShape(14.dp),
							color = MaterialTheme.colorScheme.surfaceContainer,
							border = libraryHairlineBorder()
						) {
							Row(
								modifier = Modifier
									.fillMaxWidth()
									.padding(horizontal = 8.dp, vertical = 6.dp),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(8.dp)
							) {
								IconButton(
									onClick = { openedFolderPath = null },
									modifier = Modifier.size(34.dp)
								) {
									Icon(
										imageVector = Icons.Outlined.ArrowBack,
										contentDescription = "返回文件夹列表",
										modifier = Modifier.size(18.dp)
									)
								}
								Column(modifier = Modifier.weight(1f)) {
									Text(
										text = activeFolder.name,
										style = MaterialTheme.typography.titleSmall,
										fontWeight = FontWeight.SemiBold,
										maxLines = 1,
										overflow = TextOverflow.Ellipsis
									)
									Text(
										text = activeFolder.path,
										style = MaterialTheme.typography.labelSmall,
										color = MaterialTheme.colorScheme.onSurfaceVariant,
										maxLines = 1,
										overflow = TextOverflow.Ellipsis
									)
								}
							}
						}
					}
				}

				if (currentSongs.isNotEmpty()) {
					item(key = "device-songs-actions") {
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.padding(bottom = 10.dp),
							horizontalArrangement = Arrangement.spacedBy(8.dp)
						) {
							FilledTonalButton(
								onClick = { player.playNow(currentSongs, 0) },
								modifier = Modifier.weight(1f),
								shape = RoundedCornerShape(12.dp)
							) {
								Icon(
									imageVector = Icons.Outlined.PlaylistPlay,
									contentDescription = null,
									modifier = Modifier.size(18.dp)
								)
								Spacer(Modifier.width(6.dp))
								Text("播放全部 (${currentSongs.size})")
							}
							OutlinedButton(
								onClick = { player.playNow(currentSongs.shuffled(), 0) },
								modifier = Modifier.weight(1f),
								shape = RoundedCornerShape(12.dp)
							) {
								Icon(
									imageVector = Icons.Outlined.Shuffle,
									contentDescription = null,
									modifier = Modifier.size(17.dp)
								)
								Spacer(Modifier.width(6.dp))
								Text("随机播放")
							}
						}
					}
				}

				if (currentSongs.isEmpty() && !deviceState.loading) {
					item(key = "device-songs-empty") {
						MeloQuietEmptyState(
							message = if (normalizedQuery.isBlank()) "暂无可播放的手机本地歌曲" else "没有匹配的歌曲",
							icon = Icons.Outlined.LibraryMusic,
							modifier = Modifier
								.fillMaxWidth()
								.padding(vertical = 48.dp)
						)
					}
				} else {
					itemsIndexed(
						items = currentSongs,
						key = { _, song -> song.id }
					) { index, song ->
						val isCurrent = currentPlayingPair.first == song.id
						DeviceSongRow(
							song = song,
							isPlaying = isCurrent,
							shape = libraryGroupedItemShape(
								isFirst = index == 0,
								isLast = index == currentSongs.lastIndex
							),
							onPlay = { player.playNow(currentSongs, index) },
							onPlayNext = { player.playNextSingle(song) },
							onAddToQueue = { player.addToQueueSingle(song) }
						)
						if (index < currentSongs.lastIndex) {
							MeloHairlineDivider(startIndent = 64.dp)
						}
					}
				}
			}
		}
	}
}

@Composable
private fun DeviceFolderRow(
	folder: DeviceAudioFolder,
	shape: androidx.compose.ui.graphics.Shape,
	onOpen: () -> Unit,
	onPlayFolder: () -> Unit
) {
	Surface(
		shape = shape,
		color = MaterialTheme.colorScheme.surfaceContainer,
		modifier = Modifier
			.fillMaxWidth()
			.clip(shape)
			.clickable(onClick = onOpen)
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 12.dp, vertical = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp)
		) {
			Box(
				modifier = Modifier
					.size(40.dp)
					.clip(RoundedCornerShape(10.dp))
					.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
				contentAlignment = Alignment.Center
			) {
				Icon(
					imageVector = MeloIcons.Folder,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier.size(20.dp)
				)
			}
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = folder.name,
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = FontWeight.Medium,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
				Text(
					text = "${folder.path} · ${folder.songs.size} 首",
					style = MaterialTheme.typography.bodySmall.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
			}
			IconButton(
				onClick = onPlayFolder,
				modifier = Modifier.size(32.dp)
			) {
				Icon(
					imageVector = Icons.Outlined.PlaylistPlay,
					contentDescription = "播放此文件夹",
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier.size(18.dp)
				)
			}
			Icon(
				imageVector = Icons.Outlined.ChevronForward,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
				modifier = Modifier.size(16.dp)
			)
		}
	}
}

@Composable
private fun DeviceSongRow(
	song: DomainSong,
	isPlaying: Boolean,
	shape: androidx.compose.ui.graphics.Shape,
	onPlay: () -> Unit,
	onPlayNext: () -> Unit,
	onAddToQueue: () -> Unit
) {
	var menuOpen by remember { mutableStateOf(false) }

	Surface(
		shape = shape,
		color = if (isPlaying) {
			MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
		} else {
			MaterialTheme.colorScheme.surfaceContainer
		},
		modifier = Modifier
			.fillMaxWidth()
			.clip(shape)
			.clickable(onClick = onPlay)
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 12.dp, vertical = 9.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp)
		) {
			Box(
				modifier = Modifier
					.size(44.dp)
					.clip(RoundedCornerShape(9.dp))
					.background(MaterialTheme.colorScheme.surfaceContainerHigh),
				contentAlignment = Alignment.Center
			) {
				Icon(
					imageVector = Icons.Outlined.Note,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
					modifier = Modifier.size(18.dp)
				)
				if (!song.coverArtId.isNullOrBlank()) {
					CoverArt(
						coverArtId = song.coverArtId,
						modifier = Modifier.fillMaxSize(),
						shape = RoundedCornerShape(9.dp)
					)
				}
			}

			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = song.title,
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Medium,
					color = if (isPlaying) MaterialTheme.colorScheme.primary else Color.Unspecified,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
				val metaParts = buildList {
					song.artistName?.takeIf { it.isNotBlank() }?.let(::add)
					song.fileExtension?.uppercase()?.let(::add)
					if (song.fileSize > 0L) add(song.fileSize.toFileSize())
				}
				Text(
					text = metaParts.joinToString(" · "),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
			}

			Box {
				IconButton(
					onClick = { menuOpen = true },
					modifier = Modifier.size(32.dp)
				) {
					Icon(
						imageVector = Icons.Outlined.MoreVert,
						contentDescription = "更多",
						tint = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.size(18.dp)
					)
				}
				DropdownMenu(
					expanded = menuOpen,
					onDismissRequest = { menuOpen = false }
				) {
					DropdownMenuItem(
						text = { Text("立即播放") },
						leadingIcon = {
							Icon(Icons.Outlined.PlaylistPlay, contentDescription = null)
						},
						onClick = {
							menuOpen = false
							onPlay()
						}
					)
					DropdownMenuItem(
						text = { Text("下一首播放") },
						leadingIcon = {
							Icon(Icons.Outlined.QueuePlayNext, contentDescription = null)
						},
						onClick = {
							menuOpen = false
							onPlayNext()
						}
					)
					DropdownMenuItem(
						text = { Text("加入队列") },
						leadingIcon = {
							Icon(Icons.Outlined.PlaylistAdd, contentDescription = null)
						},
						onClick = {
							menuOpen = false
							onAddToQueue()
						}
					)
				}
			}
		}
	}
}
