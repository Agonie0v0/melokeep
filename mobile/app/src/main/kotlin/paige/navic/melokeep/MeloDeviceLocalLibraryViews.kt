@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package paige.navic.melokeep

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
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
import paige.navic.di.LocalSnackBarState
import paige.navic.domain.manager.runtime.PermissionManager
import paige.navic.domain.model.DomainPlaylist
import paige.navic.domain.model.DomainSong
import paige.navic.domain.repository.DeviceAudioAlbum
import paige.navic.domain.repository.DeviceAudioArtist
import paige.navic.domain.repository.DeviceAudioFolder
import paige.navic.domain.repository.DeviceLocalMusicRepository
import paige.navic.domain.repository.DeviceMusicState
import paige.navic.playback.MediaPlayer
import paige.navic.ui.component.common.CoverArt
import paige.navic.ui.component.layout.PullToRefreshBox
import paige.navic.ui.component.sheet.ModalBottomSheet
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.Add
import paige.navic.ui.icons.outlined.Album
import paige.navic.ui.icons.outlined.ArrowBack
import paige.navic.ui.icons.outlined.Check
import paige.navic.ui.icons.outlined.Checklist
import paige.navic.ui.icons.outlined.ChevronForward
import paige.navic.ui.icons.outlined.Close
import paige.navic.ui.icons.outlined.Delete
import paige.navic.ui.icons.outlined.Info
import paige.navic.ui.icons.outlined.LibraryMusic
import paige.navic.ui.icons.outlined.MoreVert
import paige.navic.ui.icons.outlined.Note
import paige.navic.ui.icons.outlined.PlaylistAdd
import paige.navic.ui.icons.outlined.PlaylistPlay
import paige.navic.ui.icons.outlined.PlaylistRemove
import paige.navic.ui.icons.outlined.QueuePlayNext
import paige.navic.ui.icons.outlined.Refresh
import paige.navic.ui.icons.outlined.Shuffle
import paige.navic.ui.icons.outlined.Sort
import paige.navic.ui.theme.MeloBottomChromeClearance
import paige.navic.ui.theme.meloGlassSurface
import paige.navic.ui.theme.tabularNums
import paige.navic.util.toFileSize
import paige.navic.util.toHoursMinutesSeconds
import kotlin.time.Duration
import kotlin.time.Instant

private enum class DeviceLocalSubTab(val label: String) {
	SONGS("单曲"),
	ARTISTS("歌手"),
	ALBUMS("专辑"),
	FOLDERS("文件夹")
}

private enum class DeviceSortOption(
	val key: String,
	val label: String,
	val shortLabel: String
) {
	TITLE(DeviceLocalMusicRepository.SORT_TITLE, "按歌名 (A-Z)", "歌名"),
	ADDED(DeviceLocalMusicRepository.SORT_ADDED, "按添加时间 (最新优先)", "添加"),
	ARTIST(DeviceLocalMusicRepository.SORT_ARTIST, "按歌手", "歌手"),
	SIZE(DeviceLocalMusicRepository.SORT_SIZE, "按文件大小 (从大到小)", "大小"),
	DURATION(DeviceLocalMusicRepository.SORT_DURATION, "按时长 (从长到短)", "时长");

	companion object {
		fun fromKey(key: String): DeviceSortOption =
			entries.firstOrNull { it.key == key } ?: TITLE
	}
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
	val snackBarState = LocalSnackBarState.current
	val lifecycleOwner = LocalLifecycleOwner.current
	val scope = rememberCoroutineScope()

	val deviceState by deviceRepo.state.collectAsStateWithLifecycle()

	var subTab by rememberSaveable { mutableStateOf(DeviceLocalSubTab.SONGS) }
	var openedArtistName by rememberSaveable { mutableStateOf<String?>(null) }
	var openedAlbumName by rememberSaveable { mutableStateOf<String?>(null) }
	var openedFolderPath by rememberSaveable { mutableStateOf<String?>(null) }

	var multiSelectMode by rememberSaveable { mutableStateOf(false) }
	val selectedIds = remember { mutableStateMapOf<String, Boolean>() }

	var sortMenuExpanded by remember { mutableStateOf(false) }
	var scanSheetOpen by rememberSaveable { mutableStateOf(false) }
	var infoSong by remember { mutableStateOf<DomainSong?>(null) }
	var deleteTargets by remember { mutableStateOf<List<DomainSong>?>(null) }

	val safFolderLauncher = rememberLauncherForActivityResult(
		contract = ActivityResultContracts.OpenDocumentTree()
	) { uri ->
		if (uri != null) {
			scope.launch {
				deviceRepo.addSafFolder(uri)
				snackBarState.currentSnackbarData?.dismiss()
				snackBarState.showSnackbar("已添加自定义文件夹并完成扫描")
			}
		}
	}

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

	val normalizedQuery = searchQuery.trim()
	val filteredAllSongs = remember(deviceState.allSongs, normalizedQuery) {
		filterSongsByQuery(deviceState.allSongs, normalizedQuery)
	}
	val filteredArtists = remember(deviceState.artists, normalizedQuery) {
		if (normalizedQuery.isBlank()) {
			deviceState.artists
		} else {
			deviceState.artists.filter { artist ->
				artist.name.contains(normalizedQuery, ignoreCase = true) ||
					artist.songs.any { songMatchesQuery(it, normalizedQuery) }
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
					album.songs.any { songMatchesQuery(it, normalizedQuery) }
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
					folder.songs.any { songMatchesQuery(it, normalizedQuery) }
			}
		}
	}

	val activeArtist = remember(deviceState.artists, openedArtistName) {
		openedArtistName?.let { target -> deviceState.artists.firstOrNull { it.name == target } }
	}
	val activeAlbum = remember(deviceState.albums, openedAlbumName) {
		openedAlbumName?.let { target -> deviceState.albums.firstOrNull { it.name == target } }
	}
	val activeFolder = remember(deviceState.visibleFolders, openedFolderPath) {
		openedFolderPath?.let { target -> deviceState.visibleFolders.firstOrNull { it.path == target } }
	}

	val isViewingSongList = when (subTab) {
		DeviceLocalSubTab.SONGS -> true
		DeviceLocalSubTab.ARTISTS -> activeArtist != null
		DeviceLocalSubTab.ALBUMS -> activeAlbum != null
		DeviceLocalSubTab.FOLDERS -> activeFolder != null
	}

	val currentSongs = remember(
		subTab,
		filteredAllSongs,
		activeArtist,
		activeAlbum,
		activeFolder,
		normalizedQuery
	) {
		when (subTab) {
			DeviceLocalSubTab.SONGS -> filteredAllSongs
			DeviceLocalSubTab.ARTISTS -> filterSongsByQuery(activeArtist?.songs.orEmpty(), normalizedQuery)
			DeviceLocalSubTab.ALBUMS -> filterSongsByQuery(activeAlbum?.songs.orEmpty(), normalizedQuery)
			DeviceLocalSubTab.FOLDERS -> filterSongsByQuery(activeFolder?.songs.orEmpty(), normalizedQuery)
		}
	}

	val actionSongs = if (isViewingSongList) currentSongs else filteredAllSongs

	LaunchedEffect(isViewingSongList) {
		if (!isViewingSongList && multiSelectMode) {
			multiSelectMode = false
			selectedIds.clear()
		}
	}

	LaunchedEffect(currentSongs) {
		if (multiSelectMode) {
			val validIds = currentSongs.mapTo(HashSet()) { it.id }
			val stale = selectedIds.keys.filter { it !in validIds }
			stale.forEach { selectedIds.remove(it) }
		}
	}

	BackHandler(
		enabled = multiSelectMode ||
			(subTab == DeviceLocalSubTab.ARTISTS && openedArtistName != null) ||
			(subTab == DeviceLocalSubTab.ALBUMS && openedAlbumName != null) ||
			(subTab == DeviceLocalSubTab.FOLDERS && openedFolderPath != null)
	) {
		when {
			multiSelectMode -> {
				multiSelectMode = false
				selectedIds.clear()
			}
			subTab == DeviceLocalSubTab.ARTISTS && openedArtistName != null -> openedArtistName = null
			subTab == DeviceLocalSubTab.ALBUMS && openedAlbumName != null -> openedAlbumName = null
			subTab == DeviceLocalSubTab.FOLDERS && openedFolderPath != null -> openedFolderPath = null
		}
	}

	if (!deviceState.hasPermission && deviceState.safFolders.isEmpty() && deviceState.allSongs.isEmpty()) {
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
					message = "需要访问手机音频权限，或直接添加指定文件夹扫描本地音乐",
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
				FilledTonalButton(
					onClick = { safFolderLauncher.launch(null) },
					modifier = Modifier
						.fillMaxWidth()
						.height(44.dp),
					shape = RoundedCornerShape(12.dp)
				) {
					Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
					Spacer(Modifier.width(6.dp))
					Text("添加手机文件夹 (无需全部权限)")
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

	val currentSortOption = DeviceSortOption.fromKey(deviceState.sortOrder)
	val excludedFolderCount = remember(deviceState.allFolders) {
		deviceState.allFolders.count { it.excluded }
	}
	val safFolderCount = deviceState.safFolders.size

	Box(modifier = modifier.fillMaxSize()) {
		Column(modifier = Modifier.fillMaxSize()) {
			// 第一行：四维子标签（单曲 | 歌手 | 专辑 | 文件夹）+ 统计数字
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp, vertical = 4.dp),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically
			) {
				Row(
					modifier = Modifier
						.weight(1f)
						.horizontalScroll(rememberScrollState()),
					horizontalArrangement = Arrangement.spacedBy(6.dp)
				) {
					DeviceLocalSubTab.entries.forEach { tab ->
						val selected = subTab == tab
						Surface(
							onClick = {
								if (subTab != tab) {
									subTab = tab
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
								maxLines = 1,
								softWrap = false,
								modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
							)
						}
					}
				}

				Spacer(Modifier.width(8.dp))

				Text(
					text = when (subTab) {
						DeviceLocalSubTab.SONGS -> "${deviceState.allSongs.size} 首"
						DeviceLocalSubTab.ARTISTS -> "${deviceState.artists.size} 歌手"
						DeviceLocalSubTab.ALBUMS -> "${deviceState.albums.size} 专辑"
						DeviceLocalSubTab.FOLDERS -> "${deviceState.visibleFolders.size} 目录"
					},
					style = MaterialTheme.typography.labelMedium.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					softWrap = false
				)
			}

			// 第二行：紧凑操作栏（播放全部 / 随机 / 排序 / 多选 / 扫描与目录）
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.horizontalScroll(rememberScrollState())
					.padding(horizontal = 16.dp, vertical = 6.dp),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				DeviceCompactActionPill(
					label = "播放全部 (${actionSongs.size})",
					icon = MeloIcons.PlayArrow,
					emphasized = actionSongs.isNotEmpty(),
					enabled = actionSongs.isNotEmpty(),
					onClick = {
						if (actionSongs.isNotEmpty()) {
							player.playNow(actionSongs, 0)
						}
					}
				)

				DeviceCompactActionPill(
					label = "随机",
					icon = Icons.Outlined.Shuffle,
					enabled = actionSongs.isNotEmpty(),
					onClick = {
						if (actionSongs.isNotEmpty()) {
							player.playNow(actionSongs.shuffled(), 0)
						}
					}
				)

				Box {
					DeviceCompactActionPill(
						label = "排序·${currentSortOption.shortLabel}",
						icon = Icons.Outlined.Sort,
						onClick = { sortMenuExpanded = true }
					)
					DropdownMenu(
						expanded = sortMenuExpanded,
						onDismissRequest = { sortMenuExpanded = false }
					) {
						DeviceSortOption.entries.forEach { option ->
							val isCurrent = option == currentSortOption
							DropdownMenuItem(
								text = {
									Text(
										text = option.label,
										fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
										color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified
									)
								},
								trailingIcon = if (isCurrent) {
									{
										Icon(
											imageVector = Icons.Outlined.Check,
											contentDescription = null,
											tint = MaterialTheme.colorScheme.primary,
											modifier = Modifier.size(16.dp)
										)
									}
								} else {
									null
								},
								onClick = {
									sortMenuExpanded = false
									deviceRepo.setSortOrder(option.key)
								}
							)
						}
					}
				}

				if (isViewingSongList && currentSongs.isNotEmpty()) {
					DeviceCompactActionPill(
						label = if (multiSelectMode) "退出" else "多选",
						icon = if (multiSelectMode) Icons.Outlined.Close else Icons.Outlined.Checklist,
						active = multiSelectMode,
						onClick = {
							multiSelectMode = !multiSelectMode
							if (!multiSelectMode) selectedIds.clear()
						}
					)
				}

				DeviceScanButtonWithBadge(
					safCount = safFolderCount,
					excludedCount = excludedFolderCount,
					filteredOutCount = deviceState.filteredOutCount,
					onClick = { scanSheetOpen = true }
				)
			}

			PullToRefreshBox(
				modifier = Modifier
					.weight(1f)
					.fillMaxWidth(),
				finished = !deviceState.loading,
				onRefresh = { scope.launch { deviceRepo.refresh() } },
				key = listOf(
					deviceState.loading,
					deviceState.allSongs.size,
					subTab,
					openedArtistName,
					openedAlbumName,
					openedFolderPath
				)
			) {
				LazyColumn(
					modifier = Modifier.fillMaxSize(),
					contentPadding = PaddingValues(
						start = 16.dp,
						end = 16.dp,
						top = 4.dp,
						bottom = MeloBottomChromeClearance + hostNavBottom + if (multiSelectMode) 136.dp else 0.dp
					),
					verticalArrangement = Arrangement.spacedBy(0.dp)
				) {
					if (!deviceState.hasPermission && deviceState.safFolders.isNotEmpty()) {
						item(key = "dev-saf-only-banner") {
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
										.padding(horizontal = 12.dp, vertical = 10.dp),
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.spacedBy(10.dp)
								) {
									Text(
										text = "未开启媒体库权限，当前仅展示自定义文件夹歌曲",
										style = MaterialTheme.typography.bodySmall,
										color = MaterialTheme.colorScheme.onSurfaceVariant,
										modifier = Modifier.weight(1f)
									)
									TextButton(
										onClick = {
											scope.launch {
												if (permissionManager.requestAudioStoragePermission()) {
													deviceRepo.refresh()
												}
											}
										},
										contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
										modifier = Modifier.height(30.dp)
									) {
										Text("授权")
									}
								}
							}
						}
					}

					val errorMsg = deviceState.error
					if (!errorMsg.isNullOrBlank()) {
						item(key = "dev-error") {
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

					when {
						subTab == DeviceLocalSubTab.ARTISTS && activeArtist == null -> {
							if (filteredArtists.isEmpty() && !deviceState.loading) {
								item(key = "dev-artists-empty") {
									DeviceLocalEmptyPlaceholder(
										normalizedQuery = normalizedQuery,
										emptyMessage = "暂无匹配的本地歌手",
										onRescan = { scope.launch { deviceRepo.refresh() } },
										onOpenManage = { scanSheetOpen = true }
									)
								}
							} else {
								itemsIndexed(
									items = filteredArtists,
									key = { _, artist -> "dev_artist_${artist.name}" }
								) { idx, artist ->
									DeviceArtistRow(
										artist = artist,
										shape = libraryGroupedItemShape(
											isFirst = idx == 0,
											isLast = idx == filteredArtists.lastIndex
										),
										onClick = { openedArtistName = artist.name }
									)
									if (idx < filteredArtists.lastIndex) {
										MeloHairlineDivider(startIndent = 68.dp)
									}
								}
							}
						}

						subTab == DeviceLocalSubTab.ALBUMS && activeAlbum == null -> {
							if (filteredAlbums.isEmpty() && !deviceState.loading) {
								item(key = "dev-albums-empty") {
									DeviceLocalEmptyPlaceholder(
										normalizedQuery = normalizedQuery,
										emptyMessage = "暂无匹配的本地专辑",
										onRescan = { scope.launch { deviceRepo.refresh() } },
										onOpenManage = { scanSheetOpen = true }
									)
								}
							} else {
								val rows = filteredAlbums.chunked(2)
								itemsIndexed(
									items = rows,
									key = { idx, row -> "dev_album_row_${idx}_${row.first().name}" }
								) { idx, row ->
									Row(
										modifier = Modifier
											.fillMaxWidth()
											.padding(bottom = if (idx < rows.lastIndex) 14.dp else 0.dp),
										horizontalArrangement = Arrangement.spacedBy(12.dp)
									) {
										row.forEach { album ->
											DeviceAlbumGridCard(
												album = album,
												modifier = Modifier.weight(1f),
												onClick = { openedAlbumName = album.name }
											)
										}
										if (row.size == 1) {
											Spacer(Modifier.weight(1f))
										}
									}
								}
							}
						}

						subTab == DeviceLocalSubTab.FOLDERS && activeFolder == null -> {
							if (filteredFolders.isEmpty() && !deviceState.loading) {
								item(key = "dev-folders-empty") {
									DeviceLocalEmptyPlaceholder(
										normalizedQuery = normalizedQuery,
										emptyMessage = "未扫描到可展示的本地音频目录",
										onRescan = { scope.launch { deviceRepo.refresh() } },
										onOpenManage = { scanSheetOpen = true }
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
										MeloHairlineDivider(startIndent = 64.dp)
									}
								}
							}
						}

						else -> {
							when {
								subTab == DeviceLocalSubTab.ARTISTS && activeArtist != null -> {
									item(key = "dev-artist-breadcrumb") {
										DeviceDetailBreadcrumb(
											title = activeArtist.name,
											subtitle = "${activeArtist.albumCount} 张专辑 · ${currentSongs.size} 首",
											onBack = {
												multiSelectMode = false
												selectedIds.clear()
												openedArtistName = null
											}
										)
									}
								}
								subTab == DeviceLocalSubTab.ALBUMS && activeAlbum != null -> {
									item(key = "dev-album-breadcrumb") {
										DeviceDetailBreadcrumb(
											title = activeAlbum.name,
											subtitle = "${activeAlbum.artist} · ${currentSongs.size} 首",
											onBack = {
												multiSelectMode = false
												selectedIds.clear()
												openedAlbumName = null
											}
										)
									}
								}
								subTab == DeviceLocalSubTab.FOLDERS && activeFolder != null -> {
									item(key = "dev-folder-breadcrumb") {
										DeviceDetailBreadcrumb(
											title = activeFolder.name,
											subtitle = buildString {
												append(activeFolder.path)
												if (activeFolder.isSaf) append(" · 自定义目录")
											},
											onBack = {
												multiSelectMode = false
												selectedIds.clear()
												openedFolderPath = null
											}
										)
									}
								}
							}

							if (currentSongs.isEmpty() && !deviceState.loading) {
								item(key = "dev-songs-empty") {
									val filteredAllMsg = if (normalizedQuery.isBlank()) {
										if (deviceState.filteredOutCount > 0) {
											"暂无符合条件的曲目（已过滤 ${deviceState.filteredOutCount} 首短音频或屏蔽目录）"
										} else {
											"暂无可播放的手机本地歌曲"
										}
									} else {
										"没有匹配的歌曲"
									}
									DeviceLocalEmptyPlaceholder(
										normalizedQuery = normalizedQuery,
										emptyMessage = filteredAllMsg,
										onRescan = { scope.launch { deviceRepo.refresh() } },
										onOpenManage = { scanSheetOpen = true }
									)
								}
							} else {
								itemsIndexed(
									items = currentSongs,
									key = { _, song -> "dev_song_${song.id}" }
								) { index, song ->
									val isCurrent = currentPlayingPair.first == song.id
									val isChecked = selectedIds[song.id] == true
									DeviceSongRow(
										song = song,
										isPlaying = isCurrent,
										multiSelectMode = multiSelectMode,
										checked = isChecked,
										shape = libraryGroupedItemShape(
											isFirst = index == 0,
											isLast = index == currentSongs.lastIndex
										),
										onClick = {
											if (multiSelectMode) {
												selectedIds[song.id] = !isChecked
											} else {
												player.playNow(currentSongs, index)
											}
										},
										onLongClick = {
											multiSelectMode = true
											selectedIds[song.id] = true
										},
										onPlay = { player.playNow(currentSongs, index) },
										onPlayNext = { player.playNextSingle(song) },
										onAddToQueue = { player.addToQueueSingle(song) },
										onShowInfo = { infoSong = song },
										onHideSong = {
											deviceRepo.hideSongs(setOf(song.id))
											scope.launch {
												snackBarState.currentSnackbarData?.dismiss()
												snackBarState.showSnackbar("已从本地列表隐藏，可在「扫描与目录」恢复")
											}
										},
										onRequestDeleteFile = { deleteTargets = listOf(song) }
									)
									if (index < currentSongs.lastIndex) {
										MeloHairlineDivider(startIndent = if (multiSelectMode) 100.dp else 68.dp)
									}
								}
							}
						}
					}
				}
			}
		}

		// 底部多选批处理悬浮操作栏
		AnimatedVisibility(
			visible = multiSelectMode && isViewingSongList,
			enter = slideInVertically { it } + fadeIn(),
			exit = slideOutVertically { it } + fadeOut(),
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.navigationBarsPadding()
				.padding(bottom = rememberBatchDockBottomClearance())
				.padding(horizontal = 16.dp)
		) {
			val selectedSongs by remember(currentSongs) {
				derivedStateOf { currentSongs.filter { selectedIds[it.id] == true } }
			}
			DeviceBatchSelectionDock(
				selectedCount = selectedSongs.size,
				totalCount = currentSongs.size,
				onSelectAll = {
					if (selectedSongs.size == currentSongs.size && currentSongs.isNotEmpty()) {
						selectedIds.clear()
					} else {
						currentSongs.forEach { selectedIds[it.id] = true }
					}
				},
				onExit = {
					multiSelectMode = false
					selectedIds.clear()
				},
				onPlaySelected = {
					if (selectedSongs.isNotEmpty()) {
						player.playNow(selectedSongs, 0)
						multiSelectMode = false
						selectedIds.clear()
					}
				},
				onPlayNextSelected = {
					if (selectedSongs.isNotEmpty()) {
						player.playNext(buildBatchDevicePlaylist(selectedSongs))
						multiSelectMode = false
						selectedIds.clear()
					}
				},
				onAddToQueueSelected = {
					if (selectedSongs.isNotEmpty()) {
						player.addToQueue(selectedSongs)
						multiSelectMode = false
						selectedIds.clear()
					}
				},
				onHideSelected = {
					val ids = selectedSongs.mapTo(HashSet()) { it.id }
					if (ids.isNotEmpty()) {
						deviceRepo.hideSongs(ids)
						multiSelectMode = false
						selectedIds.clear()
						scope.launch {
							snackBarState.currentSnackbarData?.dismiss()
							snackBarState.showSnackbar("已移除 ${ids.size} 首，可在「扫描与目录」恢复")
						}
					}
				},
				onRequestDeleteSelected = {
					if (selectedSongs.isNotEmpty()) {
						deleteTargets = selectedSongs
					}
				}
			)
		}
	}

	if (scanSheetOpen) {
		DeviceScanAndFoldersSheet(
			deviceState = deviceState,
			hostNavBottom = hostNavBottom,
			onDismiss = { scanSheetOpen = false },
			onRescan = { scope.launch { deviceRepo.refresh(forceRescan = true) } },
			onAddSafFolder = { safFolderLauncher.launch(null) },
			onRemoveSafFolder = { treeUri -> scope.launch { deviceRepo.removeSafFolder(treeUri) } },
			onToggleFolderExcluded = { folderPath, excluded ->
				deviceRepo.setFolderExcluded(folderPath, excluded)
			},
			onSelectMinDuration = { sec -> deviceRepo.setMinDurationSec(sec) },
			onToggleSmallFileFilter = { enabled ->
				deviceRepo.setMinSizeKb(if (enabled) 500 else 0)
			},
			onRestoreHiddenSongs = {
				deviceRepo.restoreHiddenSongs()
				scope.launch {
					snackBarState.currentSnackbarData?.dismiss()
					snackBarState.showSnackbar("已恢复全部隐藏曲目")
				}
			}
		)
	}

	infoSong?.let { song ->
		DeviceSongInfoDialog(
			song = song,
			rawPath = deviceRepo.getSongRawPath(song.id),
			onDismiss = { infoSong = null }
		)
	}

	deleteTargets?.let { targets ->
		DeviceDeleteConfirmDialog(
			songs = targets,
			onDismiss = { deleteTargets = null },
			onConfirm = {
				deleteTargets = null
				scope.launch {
					val deletedCount = deviceRepo.deleteSongFiles(targets)
					if (multiSelectMode) {
						multiSelectMode = false
						selectedIds.clear()
					}
					snackBarState.currentSnackbarData?.dismiss()
					val msg = when {
						deletedCount == targets.size -> "已从手机永久删除 $deletedCount 个文件"
						deletedCount > 0 -> "已删除 $deletedCount 个文件，另有 ${targets.size - deletedCount} 个受系统权限限制未能删除"
						else -> "删除失败：系统存储限制或未授权删除，可改用「从列表隐藏」"
					}
					snackBarState.showSnackbar(msg)
				}
			}
		)
	}
}

@Composable
private fun DeviceCompactActionPill(
	label: String,
	icon: ImageVector,
	onClick: () -> Unit,
	emphasized: Boolean = false,
	active: Boolean = false,
	enabled: Boolean = true
) {
	val containerColor = when {
		!enabled -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f)
		emphasized -> MaterialTheme.colorScheme.primaryContainer
		active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
		else -> MaterialTheme.colorScheme.surfaceContainer
	}
	val contentColor = when {
		!enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
		emphasized -> MaterialTheme.colorScheme.onPrimaryContainer
		active -> MaterialTheme.colorScheme.primary
		else -> MaterialTheme.colorScheme.onSurface
	}
	Surface(
		onClick = onClick,
		enabled = enabled,
		shape = CircleShape,
		color = containerColor,
		contentColor = contentColor,
		border = BorderStroke(
			MeloHairlineWidth,
			if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else libraryHairlineBorderColor()
		)
	) {
		Row(
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(4.dp)
		) {
			Icon(
				imageVector = icon,
				contentDescription = null,
				modifier = Modifier.size(14.dp)
			)
			Text(
				text = label,
				style = MaterialTheme.typography.labelMedium.tabularNums(),
				fontWeight = if (emphasized || active) FontWeight.SemiBold else FontWeight.Normal,
				maxLines = 1,
				softWrap = false
			)
		}
	}
}

@Composable
private fun DeviceScanButtonWithBadge(
	safCount: Int,
	excludedCount: Int,
	filteredOutCount: Int,
	onClick: () -> Unit
) {
	val hasHighlight = safCount > 0 || excludedCount > 0 || filteredOutCount > 0
	val badgeText = when {
		safCount > 0 && excludedCount > 0 -> "+$safCount · -$excludedCount"
		safCount > 0 -> "+$safCount"
		excludedCount > 0 -> "屏蔽$excludedCount"
		else -> null
	}
	Surface(
		onClick = onClick,
		shape = CircleShape,
		color = if (hasHighlight) {
			MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
		} else {
			MaterialTheme.colorScheme.surfaceContainer
		},
		border = BorderStroke(
			MeloHairlineWidth,
			if (hasHighlight) {
				MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
			} else {
				libraryHairlineBorderColor()
			}
		)
	) {
		Row(
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(5.dp)
		) {
			Icon(
				imageVector = MeloIcons.Folder,
				contentDescription = null,
				tint = if (hasHighlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
				modifier = Modifier.size(14.dp)
			)
			Text(
				text = "扫描与目录",
				style = MaterialTheme.typography.labelMedium,
				fontWeight = if (hasHighlight) FontWeight.Medium else FontWeight.Normal,
				color = if (hasHighlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				softWrap = false
			)
			if (badgeText != null) {
				Surface(
					shape = CircleShape,
					color = MaterialTheme.colorScheme.primary
				) {
					Text(
						text = badgeText,
						style = MaterialTheme.typography.labelSmall.tabularNums(),
						color = MaterialTheme.colorScheme.onPrimary,
						maxLines = 1,
						softWrap = false,
						modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
					)
				}
			} else if (filteredOutCount > 0) {
				Box(
					modifier = Modifier
						.size(6.dp)
						.clip(CircleShape)
						.background(MaterialTheme.colorScheme.primary)
				)
			}
		}
	}
}

@Composable
private fun DeviceDetailBreadcrumb(
	title: String,
	subtitle: String,
	onBack: () -> Unit
) {
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
				onClick = onBack,
				modifier = Modifier.size(34.dp)
			) {
				Icon(
					imageVector = Icons.Outlined.ArrowBack,
					contentDescription = "返回上一级",
					modifier = Modifier.size(18.dp)
				)
			}
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = title,
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.SemiBold,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
				Text(
					text = subtitle,
					style = MaterialTheme.typography.labelSmall.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
			}
		}
	}
}

@Composable
private fun DeviceLocalEmptyPlaceholder(
	normalizedQuery: String,
	emptyMessage: String,
	onRescan: () -> Unit,
	onOpenManage: () -> Unit
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = 36.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(10.dp)
	) {
		MeloQuietEmptyState(
			message = if (normalizedQuery.isBlank()) emptyMessage else "没有匹配「$normalizedQuery」的内容",
			icon = Icons.Outlined.LibraryMusic,
			actionLabel = if (normalizedQuery.isBlank()) "重新扫描" else null,
			onAction = if (normalizedQuery.isBlank()) onRescan else null
		)
		if (normalizedQuery.isBlank()) {
			OutlinedButton(
				onClick = onOpenManage,
				shape = CircleShape
			) {
				Icon(MeloIcons.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
				Spacer(Modifier.width(6.dp))
				Text("扫描与目录管理")
			}
		}
	}
}

@Composable
private fun DeviceArtistRow(
	artist: DeviceAudioArtist,
	shape: Shape,
	onClick: () -> Unit
) {
	Surface(
		shape = shape,
		color = MaterialTheme.colorScheme.surfaceContainer,
		modifier = Modifier
			.fillMaxWidth()
			.clip(shape)
			.clickable(onClick = onClick)
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 12.dp, vertical = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp)
		) {
			MeloArtistAvatar(
				artistName = artist.name,
				coverArtId = artist.coverArtId,
				modifier = Modifier
					.size(44.dp)
					.border(MeloHairlineWidth, libraryHairlineBorderColor(), CircleShape),
				shape = CircleShape
			)

			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = artist.name,
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = FontWeight.Medium,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
				Text(
					text = "${artist.albumCount} 张专辑 · ${artist.songs.size} 首",
					style = MaterialTheme.typography.bodySmall.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
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
private fun DeviceAlbumGridCard(
	album: DeviceAudioAlbum,
	onClick: () -> Unit,
	modifier: Modifier = Modifier
) {
	Column(
		modifier = modifier
			.clip(RoundedCornerShape(14.dp))
			.clickable(onClick = onClick),
		verticalArrangement = Arrangement.spacedBy(6.dp)
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.aspectRatio(1f)
				.clip(RoundedCornerShape(14.dp))
				.background(MaterialTheme.colorScheme.surfaceContainerHigh)
				.border(MeloHairlineWidth, libraryHairlineBorderColor(), RoundedCornerShape(14.dp)),
			contentAlignment = Alignment.Center
		) {
			Icon(
				imageVector = Icons.Outlined.Album,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
				modifier = Modifier.size(36.dp)
			)
			if (!album.coverArtId.isNullOrBlank()) {
				CoverArt(
					coverArtId = album.coverArtId,
					modifier = Modifier.fillMaxSize(),
					shape = RoundedCornerShape(14.dp)
				)
			}
		}

		Text(
			text = album.name,
			style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis
		)
		Text(
			text = "${album.artist} · ${album.songs.size} 首",
			style = MaterialTheme.typography.labelSmall.tabularNums(),
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis
		)
	}
}

@Composable
private fun DeviceFolderRow(
	folder: DeviceAudioFolder,
	shape: Shape,
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
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(6.dp)
				) {
					Text(
						text = folder.name,
						style = MaterialTheme.typography.bodyMedium,
						fontWeight = FontWeight.Medium,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.weight(1f, fill = false)
					)
					if (folder.isSaf) {
						Surface(
							shape = RoundedCornerShape(5.dp),
							color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
						) {
							Text(
								text = "自定义",
								style = MaterialTheme.typography.labelSmall,
								color = MaterialTheme.colorScheme.primary,
								modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
							)
						}
					}
				}
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
	multiSelectMode: Boolean,
	checked: Boolean,
	shape: Shape,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	onPlay: () -> Unit,
	onPlayNext: () -> Unit,
	onAddToQueue: () -> Unit,
	onShowInfo: () -> Unit,
	onHideSong: () -> Unit,
	onRequestDeleteFile: () -> Unit
) {
	var menuOpen by remember { mutableStateOf(false) }

	val bgColor = when {
		multiSelectMode && checked -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
		isPlaying -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
		else -> MaterialTheme.colorScheme.surfaceContainer
	}

	MeloSwipeableSongRow(
		song = song,
		selectionMode = multiSelectMode,
		shape = shape,
		modifier = Modifier
			.fillMaxWidth()
			.clip(shape)
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.background(MaterialTheme.colorScheme.surfaceContainer)
				.background(bgColor)
				.combinedClickable(
					onClick = onClick,
					onLongClick = onLongClick
				)
				.padding(horizontal = 12.dp, vertical = 9.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(11.dp)
		) {
			if (multiSelectMode) {
				Checkbox(
					checked = checked,
					onCheckedChange = { onClick() }
				)
			}

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
					color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
				val metaParts = buildList {
					song.artistName?.takeIf { it.isNotBlank() }?.let(::add)
					song.fileExtension?.uppercase()?.let(::add)
					if (song.fileSize > 0L) add(song.fileSize.toFileSize())
					if (song.duration > Duration.ZERO) add(song.duration.toHoursMinutesSeconds())
				}
				Text(
					text = metaParts.joinToString(" · "),
					style = MaterialTheme.typography.bodySmall.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
			}

			if (!multiSelectMode) {
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
							leadingIcon = { Icon(Icons.Outlined.PlaylistPlay, contentDescription = null) },
							onClick = {
								menuOpen = false
								onPlay()
							}
						)
						DropdownMenuItem(
							text = { Text("下一首播放") },
							leadingIcon = { Icon(Icons.Outlined.QueuePlayNext, contentDescription = null) },
							onClick = {
								menuOpen = false
								onPlayNext()
							}
						)
						DropdownMenuItem(
							text = { Text("加入队列") },
							leadingIcon = { Icon(Icons.Outlined.PlaylistAdd, contentDescription = null) },
							onClick = {
								menuOpen = false
								onAddToQueue()
							}
						)
						DropdownMenuItem(
							text = { Text("歌曲信息") },
							leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
							onClick = {
								menuOpen = false
								onShowInfo()
							}
						)
						DropdownMenuItem(
							text = { Text("从列表隐藏") },
							leadingIcon = { Icon(Icons.Outlined.PlaylistRemove, contentDescription = null) },
							onClick = {
								menuOpen = false
								onHideSong()
							}
						)
						DropdownMenuItem(
							text = { Text("删除本地文件", color = MaterialTheme.colorScheme.error) },
							leadingIcon = {
								Icon(
									imageVector = Icons.Outlined.Delete,
									contentDescription = null,
									tint = MaterialTheme.colorScheme.error
								)
							},
							onClick = {
								menuOpen = false
								onRequestDeleteFile()
							}
						)
					}
				}
			}
		}
	}
}

@Composable
private fun DeviceBatchSelectionDock(
	selectedCount: Int,
	totalCount: Int,
	onSelectAll: () -> Unit,
	onExit: () -> Unit,
	onPlaySelected: () -> Unit,
	onPlayNextSelected: () -> Unit,
	onAddToQueueSelected: () -> Unit,
	onHideSelected: () -> Unit,
	onRequestDeleteSelected: () -> Unit
) {
	Surface(
		shape = RoundedCornerShape(20.dp),
		color = MaterialTheme.colorScheme.meloGlassSurface,
		border = libraryHairlineBorder(),
		shadowElevation = 10.dp,
		modifier = Modifier.fillMaxWidth()
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 14.dp, vertical = 10.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp)
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween
			) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(6.dp)
				) {
					IconButton(
						onClick = onExit,
						modifier = Modifier.size(28.dp)
					) {
						Icon(
							imageVector = Icons.Outlined.Close,
							contentDescription = "退出多选",
							modifier = Modifier.size(16.dp)
						)
					}
					Text(
						text = "已选 $selectedCount / $totalCount 首",
						style = MaterialTheme.typography.labelLarge.tabularNums(),
						fontWeight = FontWeight.SemiBold,
						color = MaterialTheme.colorScheme.onSurface
					)
				}

				TextButton(
					onClick = onSelectAll,
					contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
					modifier = Modifier.height(30.dp)
				) {
					Icon(
						imageVector = MeloIcons.SelectAll,
						contentDescription = null,
						modifier = Modifier.size(14.dp)
					)
					Spacer(Modifier.width(4.dp))
					Text(
						text = if (selectedCount == totalCount && totalCount > 0) "取消全选" else "全选",
						style = MaterialTheme.typography.labelMedium
					)
				}
			}

			Row(
				modifier = Modifier
					.fillMaxWidth()
					.horizontalScroll(rememberScrollState()),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				val enabled = selectedCount > 0

				Button(
					onClick = onPlaySelected,
					enabled = enabled,
					shape = CircleShape,
					contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
					modifier = Modifier.height(36.dp)
				) {
					Icon(MeloIcons.PlayArrow, null, modifier = Modifier.size(15.dp))
					Spacer(Modifier.width(4.dp))
					Text("立即播放", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
				}

				FilledTonalButton(
					onClick = onPlayNextSelected,
					enabled = enabled,
					shape = CircleShape,
					contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
					modifier = Modifier.height(36.dp)
				) {
					Icon(Icons.Outlined.QueuePlayNext, null, modifier = Modifier.size(15.dp))
					Spacer(Modifier.width(4.dp))
					Text("下一首", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
				}

				FilledTonalButton(
					onClick = onAddToQueueSelected,
					enabled = enabled,
					shape = CircleShape,
					contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
					modifier = Modifier.height(36.dp)
				) {
					Icon(Icons.Outlined.PlaylistAdd, null, modifier = Modifier.size(15.dp))
					Spacer(Modifier.width(4.dp))
					Text("加入队列", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
				}

				OutlinedButton(
					onClick = onHideSelected,
					enabled = enabled,
					shape = CircleShape,
					contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
					modifier = Modifier.height(36.dp)
				) {
					Icon(Icons.Outlined.PlaylistRemove, null, modifier = Modifier.size(15.dp))
					Spacer(Modifier.width(4.dp))
					Text("从列表移除", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
				}

				OutlinedButton(
					onClick = onRequestDeleteSelected,
					enabled = enabled,
					shape = CircleShape,
					colors = ButtonDefaults.outlinedButtonColors(
						contentColor = MaterialTheme.colorScheme.error
					),
					border = BorderStroke(
						MeloHairlineWidth,
						if (enabled) MaterialTheme.colorScheme.error.copy(alpha = 0.5f) else libraryHairlineBorderColor()
					),
					contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
					modifier = Modifier.height(36.dp)
				) {
					Icon(Icons.Outlined.Delete, null, modifier = Modifier.size(15.dp))
					Spacer(Modifier.width(4.dp))
					Text("删除文件", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
				}
			}
		}
	}
}

@Composable
private fun DeviceScanAndFoldersSheet(
	deviceState: DeviceMusicState,
	hostNavBottom: Dp,
	onDismiss: () -> Unit,
	onRescan: () -> Unit,
	onAddSafFolder: () -> Unit,
	onRemoveSafFolder: (String) -> Unit,
	onToggleFolderExcluded: (String, Boolean) -> Unit,
	onSelectMinDuration: (Int) -> Unit,
	onToggleSmallFileFilter: (Boolean) -> Unit,
	onRestoreHiddenSongs: () -> Unit
) {
	ModalBottomSheet(
		onDismissRequest = onDismiss,
		sheetState = rememberBottomSheetState(
			initialValue = SheetValue.Hidden,
			enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
		),
		sheetTitle = "扫描与目录管理"
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.heightIn(max = 620.dp)
				.verticalScroll(rememberScrollState())
				.padding(horizontal = 16.dp)
				.padding(bottom = maxOf(hostNavBottom, 16.dp) + 16.dp),
			verticalArrangement = Arrangement.spacedBy(14.dp)
		) {
			// 1. 扫描总览与一键重新扫描
			MeloGroupedCard {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 14.dp, vertical = 12.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(12.dp)
				) {
					Column(
						modifier = Modifier.weight(1f),
						verticalArrangement = Arrangement.spacedBy(2.dp)
					) {
						Text(
							text = "本地曲库扫描",
							style = MaterialTheme.typography.titleSmall,
							fontWeight = FontWeight.SemiBold
						)
						Text(
							text = deviceState.lastScanSummary.ifBlank {
								"已收录 ${deviceState.allSongs.size} 首"
							},
							style = MaterialTheme.typography.bodySmall.tabularNums(),
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
					}
					Button(
						onClick = onRescan,
						enabled = !deviceState.loading,
						shape = CircleShape,
						contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
					) {
						if (deviceState.loading) {
							CircularProgressIndicator(
								modifier = Modifier.size(15.dp),
								strokeWidth = 2.dp,
								color = MaterialTheme.colorScheme.onPrimary
							)
						} else {
							Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
						}
						Spacer(Modifier.width(5.dp))
						Text(if (deviceState.loading) "扫描中…" else "一键重新扫描", maxLines = 1)
					}
				}
			}

			// 2. 自定义手机文件夹 (SAF)
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(
					title = "自定义文件夹 (${deviceState.safFolders.size})",
					trailing = {
						FilledTonalButton(
							onClick = onAddSafFolder,
							shape = CircleShape,
							contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
							modifier = Modifier.height(30.dp)
						) {
							Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(14.dp))
							Spacer(Modifier.width(4.dp))
							Text("添加手机文件夹", style = MaterialTheme.typography.labelMedium)
						}
					}
				)

				MeloGroupedCard {
					if (deviceState.safFolders.isEmpty()) {
						Text(
							text = "未添加自定义文件夹。可补充扫描 SD 卡、下载目录或含 .nomedia 的文件夹。",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
						)
					} else {
						deviceState.safFolders.forEachIndexed { idx, saf ->
							Row(
								modifier = Modifier
									.fillMaxWidth()
									.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(10.dp)
							) {
								Icon(
									imageVector = MeloIcons.Folder,
									contentDescription = null,
									tint = MaterialTheme.colorScheme.primary,
									modifier = Modifier.size(18.dp)
								)
								Column(modifier = Modifier.weight(1f)) {
									Text(
										text = saf.name,
										style = MaterialTheme.typography.bodyMedium,
										fontWeight = FontWeight.Medium,
										maxLines = 1,
										overflow = TextOverflow.Ellipsis
									)
									Text(
										text = if (saf.accessible) "${saf.songCount} 首音频" else "权限已失效，请重新添加",
										style = MaterialTheme.typography.bodySmall.tabularNums(),
										color = if (saf.accessible) {
											MaterialTheme.colorScheme.onSurfaceVariant
										} else {
											MaterialTheme.colorScheme.error
										}
									)
								}
								IconButton(
									onClick = { onRemoveSafFolder(saf.treeUri) },
									modifier = Modifier.size(32.dp)
								) {
									Icon(
										imageVector = Icons.Outlined.Close,
										contentDescription = "移除自定义目录",
										tint = MaterialTheme.colorScheme.onSurfaceVariant,
										modifier = Modifier.size(16.dp)
									)
								}
							}
							if (idx < deviceState.safFolders.lastIndex) {
								MeloHairlineDivider(startIndent = 42.dp)
							}
						}
					}
				}
			}

			// 3. 发现目录勾选（屏蔽录音/通知/微信等杂音目录）
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				val includedCount = deviceState.allFolders.count { !it.excluded }
				MeloSectionLabel(
					title = "扫描目录勾选 ($includedCount/${deviceState.allFolders.size})",
					trailing = {
						Text(
							text = "取消勾选可屏蔽杂音目录",
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
					}
				)
				MeloGroupedCard {
					if (deviceState.allFolders.isEmpty()) {
						Text(
							text = "暂无已发现的音频文件夹",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
						)
					} else {
						deviceState.allFolders.forEachIndexed { idx, folder ->
							val included = !folder.excluded
							Row(
								modifier = Modifier
									.fillMaxWidth()
									.clickable { onToggleFolderExcluded(folder.path, included) }
									.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(10.dp)
							) {
								Column(modifier = Modifier.weight(1f)) {
									Row(
										verticalAlignment = Alignment.CenterVertically,
										horizontalArrangement = Arrangement.spacedBy(6.dp)
									) {
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
											modifier = Modifier.weight(1f, fill = false)
										)
										if (folder.isSaf) {
											Surface(
												shape = RoundedCornerShape(4.dp),
												color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
											) {
												Text(
													text = "SAF",
													style = MaterialTheme.typography.labelSmall,
													color = MaterialTheme.colorScheme.primary,
													modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
												)
											}
										}
									}
									Text(
										text = "${folder.path} · ${folder.rawSongCount} 首",
										style = MaterialTheme.typography.bodySmall.tabularNums(),
										color = MaterialTheme.colorScheme.onSurfaceVariant,
										maxLines = 1,
										overflow = TextOverflow.Ellipsis
									)
								}
								Checkbox(
									checked = included,
									onCheckedChange = { checked ->
										onToggleFolderExcluded(folder.path, !checked)
									}
								)
							}
							if (idx < deviceState.allFolders.lastIndex) {
								MeloHairlineDivider(startIndent = 14.dp)
							}
						}
					}
				}
			}

			// 4. 智能过滤与隐藏曲目恢复
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "智能过滤")
				MeloGroupedCard {
					Column(
						modifier = Modifier
							.fillMaxWidth()
							.padding(horizontal = 14.dp, vertical = 12.dp),
						verticalArrangement = Arrangement.spacedBy(10.dp)
					) {
						Text(
							text = "过滤短音频（自动排除通话录音、提示音与片段）",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
						Row(
							modifier = Modifier.fillMaxWidth(),
							horizontalArrangement = Arrangement.spacedBy(8.dp)
						) {
							listOf(
								0 to "关闭",
								30 to "< 30秒",
								60 to "< 60秒"
							).forEach { (sec, label) ->
								val selected = deviceState.minDurationSec == sec
								Surface(
									onClick = { onSelectMinDuration(sec) },
									modifier = Modifier.weight(1f),
									shape = CircleShape,
									color = if (selected) {
										MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
									} else {
										MaterialTheme.colorScheme.surfaceContainerHigh
									},
									border = BorderStroke(
										MeloHairlineWidth,
										if (selected) {
											MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
										} else {
											libraryHairlineBorderColor()
										}
									)
								) {
									Box(
										modifier = Modifier.padding(vertical = 8.dp),
										contentAlignment = Alignment.Center
									) {
										Text(
											text = label,
											style = MaterialTheme.typography.labelMedium,
											fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
											color = if (selected) {
												MaterialTheme.colorScheme.primary
											} else {
												MaterialTheme.colorScheme.onSurface
											}
										)
									}
								}
							}
						}
					}

					MeloHairlineDivider(startIndent = 14.dp)

					val smallFileFiltered = deviceState.minSizeKb >= 500
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.clickable { onToggleSmallFileFilter(!smallFileFiltered) }
							.padding(horizontal = 14.dp, vertical = 10.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(12.dp)
					) {
						Column(modifier = Modifier.weight(1f)) {
							Text(
								text = "过滤小文件 (< 500 KB)",
								style = MaterialTheme.typography.bodyMedium,
								fontWeight = FontWeight.Medium
							)
							Text(
								text = "屏蔽极小铃声或语音消息碎片",
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant
							)
						}
						Switch(
							checked = smallFileFiltered,
							onCheckedChange = onToggleSmallFileFilter
						)
					}

					if (deviceState.hiddenCount > 0) {
						MeloHairlineDivider(startIndent = 14.dp)
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.clickable(onClick = onRestoreHiddenSongs)
								.padding(horizontal = 14.dp, vertical = 12.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(10.dp)
						) {
							Icon(
								imageVector = MeloIcons.Restore,
								contentDescription = null,
								tint = MaterialTheme.colorScheme.primary,
								modifier = Modifier.size(18.dp)
							)
							Text(
								text = "恢复已隐藏曲目 (${deviceState.hiddenCount} 首)",
								style = MaterialTheme.typography.bodyMedium,
								fontWeight = FontWeight.Medium,
								color = MaterialTheme.colorScheme.primary,
								modifier = Modifier.weight(1f)
							)
						}
					}
				}
			}
		}
	}
}

@Composable
private fun DeviceSongInfoDialog(
	song: DomainSong,
	rawPath: String?,
	onDismiss: () -> Unit
) {
	val isSaf = song.id.startsWith("device:saf:")
	val formatText = listOfNotNull(
		song.fileExtension?.uppercase()?.takeIf { it.isNotBlank() },
		song.mimeType?.takeIf { it.isNotBlank() }
	).joinToString(" · ").ifBlank { "未知" }
	val durationText = if (song.duration > Duration.ZERO) {
		song.duration.toHoursMinutesSeconds()
	} else {
		"未知"
	}
	val sizeText = if (song.fileSize > 0L) song.fileSize.toFileSize() else "未知"
	val pathText = rawPath?.takeIf { it.isNotBlank() }
		?: song.filePath?.takeIf { it.isNotBlank() }
		?: song.parentId?.takeIf { it.isNotBlank() }
		?: "未知"

	AlertDialog(
		onDismissRequest = onDismiss,
		icon = {
			Icon(
				imageVector = Icons.Outlined.Info,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.primary
			)
		},
		title = {
			Text(
				text = song.title,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis
			)
		},
		text = {
			SelectionContainer {
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.verticalScroll(rememberScrollState()),
					verticalArrangement = Arrangement.spacedBy(6.dp)
				) {
					DeviceInfoLine("歌手", song.artistName?.takeIf { it.isNotBlank() } ?: "未知歌手")
					DeviceInfoLine("专辑", song.albumTitle?.takeIf { it.isNotBlank() } ?: "手机本地")
					DeviceInfoLine("格式", formatText)
					DeviceInfoLine("体积", sizeText)
					DeviceInfoLine("时长", durationText)
					song.bitRate?.takeIf { it > 0 }?.let { DeviceInfoLine("码率", "$it kbps") }
					song.year?.let { DeviceInfoLine("年份", it.toString()) }
					DeviceInfoLine("目录", song.parentId?.takeIf { it.isNotBlank() } ?: "根目录")
					DeviceInfoLine("来源", if (isSaf) "自定义文件夹 (SAF)" else "系统媒体库")
					DeviceInfoLine("路径", pathText)
				}
			}
		},
		confirmButton = {
			TextButton(onClick = onDismiss) {
				Text("关闭")
			}
		}
	)
}

@Composable
private fun DeviceInfoLine(label: String, value: String) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(10.dp),
		verticalAlignment = Alignment.Top
	) {
		Text(
			text = label,
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.width(42.dp)
		)
		Text(
			text = value,
			style = MaterialTheme.typography.bodySmall.tabularNums(),
			color = MaterialTheme.colorScheme.onSurface,
			modifier = Modifier.weight(1f)
		)
	}
}

@Composable
private fun DeviceDeleteConfirmDialog(
	songs: List<DomainSong>,
	onDismiss: () -> Unit,
	onConfirm: () -> Unit
) {
	val isSingle = songs.size == 1
	AlertDialog(
		onDismissRequest = onDismiss,
		icon = {
			Icon(
				imageVector = Icons.Outlined.Delete,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.error
			)
		},
		title = { Text(if (isSingle) "确认删除本地文件" else "确认批量删除本地文件") },
		text = {
			Text(
				if (isSingle) {
					"将永久删除「${songs.first().title}」对应的手机本地文件，删除后无法恢复。如仅不想在列表看到它，可选择「从列表隐藏」。"
				} else {
					"将永久删除选中的 ${songs.size} 个手机本地音频文件，删除后无法恢复。"
				}
			)
		},
		confirmButton = {
			Button(
				onClick = onConfirm,
				colors = ButtonDefaults.buttonColors(
					containerColor = MaterialTheme.colorScheme.error,
					contentColor = MaterialTheme.colorScheme.onError
				)
			) {
				Text("永久删除")
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) {
				Text("取消")
			}
		}
	)
}

private fun filterSongsByQuery(
	songs: List<DomainSong>,
	normalizedQuery: String
): List<DomainSong> {
	if (normalizedQuery.isBlank()) return songs
	return songs.filter { songMatchesQuery(it, normalizedQuery) }
}

private fun songMatchesQuery(song: DomainSong, normalizedQuery: String): Boolean {
	return song.title.contains(normalizedQuery, ignoreCase = true) ||
		(song.artistName?.contains(normalizedQuery, ignoreCase = true) == true) ||
		(song.albumTitle?.contains(normalizedQuery, ignoreCase = true) == true) ||
		(song.parentId?.contains(normalizedQuery, ignoreCase = true) == true) ||
		(song.fileExtension?.contains(normalizedQuery, ignoreCase = true) == true)
}

private fun buildBatchDevicePlaylist(songs: List<DomainSong>): DomainPlaylist {
	val epoch = Instant.fromEpochMilliseconds(0L)
	val totalDuration = songs.fold(Duration.ZERO) { acc, song -> acc + song.duration }
	return DomainPlaylist(
		id = "device_batch_next",
		name = "本地批量队列",
		owner = null,
		comment = null,
		coverArtId = songs.firstNotNullOfOrNull { it.coverArtId },
		songCount = songs.size,
		duration = totalDuration,
		createdAt = epoch,
		modifiedAt = epoch,
		public = false,
		readOnly = true,
		allowedUsers = emptyList(),
		validUntil = null,
		songs = songs
	)
}
