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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.Pause
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.agonie0v0.melokeep.shared.MeloKeepApiClient
import io.github.agonie0v0.melokeep.shared.MeloLibraryFile
import io.github.agonie0v0.melokeep.shared.MeloLibraryFolder
import io.github.agonie0v0.melokeep.shared.MeloLocalLibraryController
import io.github.agonie0v0.melokeep.shared.MeloLocalLibraryUiState
import io.github.agonie0v0.melokeep.shared.MeloLocalPlaylistSummary
import io.github.agonie0v0.melokeep.shared.MeloStudioController
import io.github.agonie0v0.melokeep.shared.MeloStudioUiState
import io.github.agonie0v0.melokeep.shared.MeloTrack
import kotlin.random.Random

private data class LocalAlbumGroup(
	val name: String,
	val artist: String,
	val cover: String?,
	val items: List<IosLibLocalTrackEntry>,
)

private data class LocalArtistGroup(
	val name: String,
	val cover: String?,
	val albumCount: Int,
	val items: List<IosLibLocalTrackEntry>,
)

private data class LocalTagFieldDef(
	val key: String,
	val fallbackLabel: String,
	val placeholder: String,
	val isCore: Boolean,
)

private const val LOCAL_UNKNOWN_ALBUM = "未知专辑"
private const val LOCAL_UNKNOWN_ARTIST = "未知歌手"

/**
 * 与 Go 后端 `media.TagFields`（17 项标准字段）严格对齐的字段元数据。
 */
private val LOCAL_17_TAG_FIELDS = listOf(
	LocalTagFieldDef("title", "标题", "输入曲目标题", isCore = true),
	LocalTagFieldDef("artist", "歌手", "输入演唱者", isCore = true),
	LocalTagFieldDef("album", "专辑", "输入专辑名称", isCore = true),
	LocalTagFieldDef("album_artist", "专辑艺人", "输入专辑主艺人", isCore = true),
	LocalTagFieldDef("date", "发行日期", "例如 2024 或 2024-05-01", isCore = true),
	LocalTagFieldDef("track_number", "音轨号", "例如 1 或 1/12", isCore = true),
	LocalTagFieldDef("disc_number", "碟号", "例如 1 或 1/2", isCore = true),
	LocalTagFieldDef("genre", "流派", "例如 Pop / Rock", isCore = false),
	LocalTagFieldDef("composer", "作曲", "输入作曲者", isCore = false),
	LocalTagFieldDef("lyricist", "作词", "输入作词者", isCore = false),
	LocalTagFieldDef("comment", "备注", "补充备注信息", isCore = false),
	LocalTagFieldDef("language", "语言", "例如 zho / eng / jpn", isCore = false),
	LocalTagFieldDef("copyright", "版权", "输入版权声明", isCore = false),
	LocalTagFieldDef("publisher", "发行商", "输入唱片公司或厂牌", isCore = false),
	LocalTagFieldDef("grouping", "分组", "输入曲风或专辑分组", isCore = false),
	LocalTagFieldDef("bpm", "BPM", "节拍速度，例如 120", isCore = false),
	LocalTagFieldDef("isrc", "ISRC", "国际标准录音代码", isCore = false),
)

private fun IosLibLocalTrackEntry.albumName(): String =
	track.album.trim().ifBlank { LOCAL_UNKNOWN_ALBUM }

private fun IosLibLocalTrackEntry.artistName(): String =
	track.artist.trim().ifBlank { LOCAL_UNKNOWN_ARTIST }

private fun groupLocalAlbums(items: List<IosLibLocalTrackEntry>): List<LocalAlbumGroup> =
	items.groupBy { it.albumName() }
		.map { (name, list) ->
			val artist = list.groupingBy { it.artistName() }.eachCount()
				.maxByOrNull { it.value }?.key ?: LOCAL_UNKNOWN_ARTIST
			LocalAlbumGroup(
				name = name,
				artist = artist,
				cover = list.firstNotNullOfOrNull { it.iosTrack.coverUrl?.takeIf(String::isNotBlank) },
				items = list,
			)
		}
		.sortedBy { it.name.lowercase() }

private fun groupLocalArtists(items: List<IosLibLocalTrackEntry>): List<LocalArtistGroup> =
	items.groupBy { it.artistName() }
		.map { (name, list) ->
			LocalArtistGroup(
				name = name,
				cover = list.firstNotNullOfOrNull { it.iosTrack.coverUrl?.takeIf(String::isNotBlank) },
				albumCount = list.mapTo(HashSet()) { it.albumName() }.size,
				items = list,
			)
		}
		.sortedBy { it.name.lowercase() }

private fun buildIosLibLocalEntries(
	files: List<MeloLibraryFile>,
	subsonicManager: IosSubsonicManager,
	apiClient: MeloKeepApiClient,
): List<IosLibLocalTrackEntry> = files.map { file ->
	val track = file.track ?: iosLibBuildFallbackMeloTrack(file, apiClient)
	val iosTrack = iosLibMapFileToIosTrack(file, subsonicManager, apiClient)
	IosLibLocalTrackEntry(track = track, iosTrack = iosTrack)
}

@Composable
internal fun IosLibLocalSection(
	subTab: IosLibLocalSubTab,
	onOpenRoute: (IosLibLocalDetailRoute) -> Unit,
	onSelectSubTab: (IosLibLocalSubTab) -> Unit,
	studioState: MeloStudioUiState,
	studioController: MeloStudioController,
	localState: MeloLocalLibraryUiState,
	localController: MeloLocalLibraryController,
	subsonicManager: IosSubsonicManager,
	audioPlayer: IosAudioPlayer,
	apiClient: MeloKeepApiClient,
	currentTrackId: String?,
	isPlaybackPaused: Boolean,
	offlineIds: Set<String>,
	downloadingProgress: Map<String, Float>,
	onRequestAddToLocalPlaylist: (List<String>) -> Unit,
	onRenameFolder: (IosLibCloudChildFolder) -> Unit,
	onEditTags: (MeloLibraryFile) -> Unit,
	onRefreshFiles: () -> Unit,
	onShowNotice: (String) -> Unit,
	onRecordSearchResult: (() -> Unit)? = null,
	bottomPadding: PaddingValues,
) {
	val allItems = remember(studioState.libraryFiles) {
		buildIosLibLocalEntries(studioState.libraryFiles, subsonicManager, apiClient)
	}
	val albums = remember(allItems) { groupLocalAlbums(allItems) }
	val artists = remember(allItems) { groupLocalArtists(allItems) }
	val folderCount = remember(studioState.libraryFolders) {
		studioState.libraryFolders.count { iosLibNormalizeFolder(it.folder).isNotEmpty() }
	}

	var createPlaylistOpen by remember { mutableStateOf(false) }
	var renameTarget by remember { mutableStateOf<MeloLocalPlaylistSummary?>(null) }
	var deleteTarget by remember { mutableStateOf<MeloLocalPlaylistSummary?>(null) }

	val hasMore = !studioState.nextCursor.isNullOrBlank()
	val toggleFavorite: (MeloTrack) -> Unit = { localController.toggleFavorite(it) }

	PullToRefreshBox(
		isRefreshing = studioState.filesLoading || localState.loading,
		onRefresh = onRefreshFiles,
		modifier = Modifier.fillMaxSize(),
	) {
		when (subTab) {
			IosLibLocalSubTab.OVERVIEW -> LocalOverviewContent(
				allItems = allItems,
				albums = albums,
				artists = artists,
				folderCount = folderCount,
				localState = localState,
				apiClient = apiClient,
				audioPlayer = audioPlayer,
				currentTrackId = currentTrackId,
				offlineIds = offlineIds,
				downloadingProgress = downloadingProgress,
				filesLoading = studioState.filesLoading,
				onOpenRoute = onOpenRoute,
				onSelectSubTab = onSelectSubTab,
				onToggleFavorite = toggleFavorite,
				onRequestAddToLocalPlaylist = onRequestAddToLocalPlaylist,
				onShowNotice = onShowNotice,
				onRefresh = onRefreshFiles,
				bottomPadding = bottomPadding,
			)

			IosLibLocalSubTab.SONGS -> LocalTrackListView(
				items = allItems,
				favoriteIds = localState.favoriteIds,
				audioPlayer = audioPlayer,
				currentTrackId = currentTrackId,
				offlineIds = offlineIds,
				downloadingProgress = downloadingProgress,
				emptyLabel = if (studioState.fileQuery.isNotBlank()) {
					"未找到匹配「${studioState.fileQuery}」的曲目"
				} else {
					"本地库暂无已索引曲目"
				},
				loading = studioState.filesLoading,
				showOfflineAll = false,
				hasMore = hasMore,
				onLoadMore = studioController::loadMoreLibraryFiles,
				onToggleFavorite = toggleFavorite,
				onRequestAddToLocalPlaylist = onRequestAddToLocalPlaylist,
				onShowNotice = onShowNotice,
				onRecordSearchResult = onRecordSearchResult,
				bottomPadding = bottomPadding,
			)

			IosLibLocalSubTab.ALBUMS -> LocalAlbumsContent(
				albums = albums,
				loading = studioState.filesLoading,
				hasMore = hasMore,
				onLoadMore = studioController::loadMoreLibraryFiles,
				onOpenAlbum = {
					onRecordSearchResult?.invoke()
					onOpenRoute(IosLibLocalDetailRoute.Album(it.name))
				},
				bottomPadding = bottomPadding,
			)

			IosLibLocalSubTab.ARTISTS -> LocalArtistsContent(
				artists = artists,
				loading = studioState.filesLoading,
				hasMore = hasMore,
				onLoadMore = studioController::loadMoreLibraryFiles,
				onOpenArtist = {
					onRecordSearchResult?.invoke()
					onOpenRoute(IosLibLocalDetailRoute.Artist(it.name))
				},
				bottomPadding = bottomPadding,
			)

			IosLibLocalSubTab.PLAYLISTS -> LocalPlaylistsContent(
				playlists = localState.playlists,
				loading = localState.loading,
				apiClient = apiClient,
				onCreate = { createPlaylistOpen = true },
				onOpen = { onOpenRoute(IosLibLocalDetailRoute.Playlist(it.id)) },
				onRename = { renameTarget = it },
				onDelete = { deleteTarget = it },
				bottomPadding = bottomPadding,
			)

			IosLibLocalSubTab.FOLDERS -> LocalServerFilesSection(
				studioState = studioState,
				apiClient = apiClient,
				subsonicManager = subsonicManager,
				audioPlayer = audioPlayer,
				currentTrackId = currentTrackId,
				isPlaybackPaused = isPlaybackPaused,
				offlineIds = offlineIds,
				downloadingProgress = downloadingProgress,
				onNavigateFolder = { folder ->
					studioController.loadLibraryFiles(
						query = studioState.fileQuery,
						folder = folder,
						reset = true,
					)
				},
				onLongClickFolder = onRenameFolder,
				onToggleSelect = studioController::toggleFileSelection,
				onSelectAll = studioController::selectAllFiles,
				onClearSelection = studioController::clearFileSelection,
				onEditTags = { file ->
					onRecordSearchResult?.invoke()
					onEditTags(file)
				},
				onLoadMore = studioController::loadMoreLibraryFiles,
				onShowNotice = onShowNotice,
				onRecordSearchResult = onRecordSearchResult,
				bottomPadding = bottomPadding,
			)
		}
	}

	if (createPlaylistOpen) {
		LocalPlaylistNameDialog(
			title = "新建歌单",
			confirmLabel = "创建",
			initial = "",
			onDismiss = { createPlaylistOpen = false },
			onConfirm = { name ->
				createPlaylistOpen = false
				localController.createPlaylist(name)
			},
		)
	}

	renameTarget?.let { target ->
		LocalPlaylistNameDialog(
			title = "重命名歌单",
			confirmLabel = "保存",
			initial = target.name,
			onDismiss = { renameTarget = null },
			onConfirm = { name ->
				renameTarget = null
				localController.renamePlaylist(target.id, name)
			},
		)
	}

	deleteTarget?.let { target ->
		LocalPlaylistDeleteConfirmDialog(
			target = target,
			onDismiss = { deleteTarget = null },
			onConfirm = {
				deleteTarget = null
				localController.deletePlaylist(target.id)
			},
		)
	}
}

@Composable
internal fun IosLibLocalDetailScreen(
	route: IosLibLocalDetailRoute,
	studioState: MeloStudioUiState,
	localState: MeloLocalLibraryUiState,
	localController: MeloLocalLibraryController,
	subsonicManager: IosSubsonicManager,
	audioPlayer: IosAudioPlayer,
	apiClient: MeloKeepApiClient,
	currentTrackId: String?,
	offlineIds: Set<String>,
	downloadingProgress: Map<String, Float>,
	onRequestAddToLocalPlaylist: (List<String>) -> Unit,
	onShowNotice: (String) -> Unit,
	onBack: () -> Unit,
	bottomPadding: PaddingValues,
) {
	val allItems = remember(studioState.libraryFiles) {
		buildIosLibLocalEntries(studioState.libraryFiles, subsonicManager, apiClient)
	}
	val favoriteItems = remember(localState.favorites) {
		localState.favorites.map { track ->
			IosLibLocalTrackEntry(
				track = track,
				iosTrack = iosLibMapMeloTrackToIosTrack(track, subsonicManager),
			)
		}
	}
	val albums = remember(allItems) { groupLocalAlbums(allItems) }
	val artists = remember(allItems) { groupLocalArtists(allItems) }
	var dailyMixSeed by remember { mutableIntStateOf(0) }
	val dailyMixItems = remember(allItems, dailyMixSeed) {
		allItems.shuffled(Random(dailyMixSeed * 31 + allItems.size)).take(30)
	}

	var renameTarget by remember { mutableStateOf<MeloLocalPlaylistSummary?>(null) }
	var deleteTarget by remember { mutableStateOf<MeloLocalPlaylistSummary?>(null) }
	val toggleFavorite: (MeloTrack) -> Unit = { localController.toggleFavorite(it) }

	LaunchedEffect(route) {
		when (route) {
			is IosLibLocalDetailRoute.Playlist -> localController.loadPlaylistDetail(route.id)
			IosLibLocalDetailRoute.Favorites -> localController.refreshFavorites()
			else -> localController.clearActivePlaylist()
		}
	}

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colorScheme.background),
	) {
		when (route) {
			IosLibLocalDetailRoute.Favorites -> Column(Modifier.fillMaxSize()) {
				IosCleanTopBar(
					title = "我喜欢",
					onBack = onBack,
				)
				LocalTrackListView(
					items = favoriteItems,
					favoriteIds = localState.favoriteIds,
					audioPlayer = audioPlayer,
					currentTrackId = currentTrackId,
					offlineIds = offlineIds,
					downloadingProgress = downloadingProgress,
					emptyLabel = "暂无喜欢的歌曲",
					loading = localState.loading,
					onToggleFavorite = toggleFavorite,
					onRequestAddToLocalPlaylist = onRequestAddToLocalPlaylist,
					onShowNotice = onShowNotice,
					bottomPadding = bottomPadding,
					modifier = Modifier.weight(1f),
				)
			}

			IosLibLocalDetailRoute.DailyMix -> Column(Modifier.fillMaxSize()) {
				IosCleanTopBar(
					title = "猜你喜欢",
					onBack = onBack,
					trailing = {
						IosLibHeaderCircleButton(
							icon = Icons.Rounded.Refresh,
							contentDescription = "换一批",
							onClick = { dailyMixSeed += 1 },
						)
					},
				)
				LocalTrackListView(
					items = dailyMixItems,
					favoriteIds = localState.favoriteIds,
					audioPlayer = audioPlayer,
					currentTrackId = currentTrackId,
					offlineIds = offlineIds,
					downloadingProgress = downloadingProgress,
					emptyLabel = "暂无推荐曲目",
					loading = studioState.filesLoading,
					onToggleFavorite = toggleFavorite,
					onRequestAddToLocalPlaylist = onRequestAddToLocalPlaylist,
					onShowNotice = onShowNotice,
					bottomPadding = bottomPadding,
					modifier = Modifier.weight(1f),
				)
			}

			is IosLibLocalDetailRoute.Playlist -> {
				val summary = remember(localState.playlists, route.id) {
					localState.playlists.firstOrNull { it.id == route.id }
				}
				val active = localState.activePlaylist?.takeIf { it.id == route.id }
				val playlistItems = remember(active) {
					active?.tracks.orEmpty().map { track ->
						IosLibLocalTrackEntry(
							track = track,
							iosTrack = iosLibMapMeloTrackToIosTrack(track, subsonicManager),
						)
					}
				}
				val playlistName = active?.name?.takeIf { it.isNotBlank() } ?: summary?.name ?: "歌单"
				val target = summary ?: MeloLocalPlaylistSummary(id = route.id, name = playlistName)
				Column(Modifier.fillMaxSize()) {
					IosCleanTopBar(
						title = playlistName,
						onBack = onBack,
						trailing = {
							IosLibHeaderCircleButton(
								icon = Icons.Rounded.Edit,
								contentDescription = "重命名歌单",
								onClick = { renameTarget = target },
							)
							IosLibHeaderCircleButton(
								icon = Icons.Rounded.Delete,
								contentDescription = "删除歌单",
								onClick = { deleteTarget = target },
							)
						},
					)
					LocalTrackListView(
						items = playlistItems,
						favoriteIds = localState.favoriteIds,
						audioPlayer = audioPlayer,
						currentTrackId = currentTrackId,
						offlineIds = offlineIds,
						downloadingProgress = downloadingProgress,
						emptyLabel = "歌单暂无曲目",
						loading = localState.playlistDetailLoading,
						onToggleFavorite = toggleFavorite,
						onRequestAddToLocalPlaylist = onRequestAddToLocalPlaylist,
						onRemoveFromPlaylist = { track ->
							localController.removeTrackFromPlaylist(route.id, track.id)
						},
						onShowNotice = onShowNotice,
						bottomPadding = bottomPadding,
						modifier = Modifier.weight(1f),
					)
				}
			}

			is IosLibLocalDetailRoute.Album -> {
				val group = remember(albums, route.name) {
					albums.firstOrNull { it.name == route.name }
				}
				Column(Modifier.fillMaxSize()) {
					IosCleanTopBar(
						title = route.name,
						onBack = onBack,
					)
					LocalTrackListView(
						items = group?.items.orEmpty(),
						favoriteIds = localState.favoriteIds,
						audioPlayer = audioPlayer,
						currentTrackId = currentTrackId,
						offlineIds = offlineIds,
						downloadingProgress = downloadingProgress,
						emptyLabel = "该专辑暂无曲目",
						loading = studioState.filesLoading,
						onToggleFavorite = toggleFavorite,
						onRequestAddToLocalPlaylist = onRequestAddToLocalPlaylist,
						onShowNotice = onShowNotice,
						bottomPadding = bottomPadding,
						modifier = Modifier.weight(1f),
					)
				}
			}

			is IosLibLocalDetailRoute.Artist -> {
				val group = remember(artists, route.name) {
					artists.firstOrNull { it.name == route.name }
				}
				Column(Modifier.fillMaxSize()) {
					IosCleanTopBar(
						title = route.name,
						onBack = onBack,
					)
					if (group != null) {
						Surface(
							shape = RoundedCornerShape(14.dp),
							color = MaterialTheme.colorScheme.surfaceContainer,
							modifier = Modifier
								.fillMaxWidth()
								.padding(horizontal = 16.dp, vertical = 6.dp),
						) {
							Row(
								modifier = Modifier.padding(12.dp),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(12.dp),
							) {
								IosArtistAvatar(
									artistName = group.name,
									coverUrl = group.cover,
									size = 52.dp,
									border = BorderStroke(IosHairlineWidth, iosLibHairlineBorderColor()),
									preferMeloAvatar = true,
								)
								Column(
									modifier = Modifier.weight(1f),
									verticalArrangement = Arrangement.spacedBy(2.dp),
								) {
									Text(
										text = group.name,
										style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
										maxLines = 1,
										overflow = TextOverflow.Ellipsis,
									)
									Text(
										text = "${group.albumCount} 张专辑 · ${group.items.size} 首",
										style = MaterialTheme.typography.bodySmall.tabularNums(),
										color = MaterialTheme.colorScheme.onSurfaceVariant,
									)
								}
							}
						}
					}
					LocalTrackListView(
						items = group?.items.orEmpty(),
						favoriteIds = localState.favoriteIds,
						audioPlayer = audioPlayer,
						currentTrackId = currentTrackId,
						offlineIds = offlineIds,
						downloadingProgress = downloadingProgress,
						emptyLabel = "该歌手暂无曲目",
						loading = studioState.filesLoading,
						onToggleFavorite = toggleFavorite,
						onRequestAddToLocalPlaylist = onRequestAddToLocalPlaylist,
						onShowNotice = onShowNotice,
						bottomPadding = bottomPadding,
						modifier = Modifier.weight(1f),
					)
				}
			}
		}
	}

	renameTarget?.let { target ->
		LocalPlaylistNameDialog(
			title = "重命名歌单",
			confirmLabel = "保存",
			initial = target.name,
			onDismiss = { renameTarget = null },
			onConfirm = { name ->
				renameTarget = null
				localController.renamePlaylist(target.id, name)
			},
		)
	}

	deleteTarget?.let { target ->
		LocalPlaylistDeleteConfirmDialog(
			target = target,
			onDismiss = { deleteTarget = null },
			onConfirm = {
				deleteTarget = null
				localController.deletePlaylist(target.id)
				if ((route as? IosLibLocalDetailRoute.Playlist)?.id == target.id) {
					onBack()
				}
			},
		)
	}
}

@Composable
private fun LocalPlaylistDeleteConfirmDialog(
	target: MeloLocalPlaylistSummary,
	onDismiss: () -> Unit,
	onConfirm: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		icon = {
			Icon(
				imageVector = Icons.Rounded.Delete,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.error,
			)
		},
		title = { Text("删除歌单") },
		text = { Text("确认删除「${target.name}」吗？仅删除歌单，不删除音乐文件。") },
		confirmButton = {
			Button(
				onClick = onConfirm,
				colors = ButtonDefaults.buttonColors(
					containerColor = MaterialTheme.colorScheme.error,
					contentColor = MaterialTheme.colorScheme.onError,
				),
			) {
				Text("删除")
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text("取消") }
		},
	)
}

@Composable
private fun LocalQuickTile(
	icon: ImageVector,
	title: String,
	count: Int?,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Surface(
		shape = RoundedCornerShape(14.dp),
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = iosLibHairlineBorder(),
		modifier = modifier
			.clip(RoundedCornerShape(14.dp))
			.clickable(onClick = onClick),
	) {
		Row(
			modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Icon(
				imageVector = icon,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.primary,
				modifier = Modifier.size(18.dp),
			)
			Text(
				text = title,
				style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
			if (count != null) {
				Text(
					text = "$count",
					style = MaterialTheme.typography.bodyMedium.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					softWrap = false,
				)
			} else {
				Icon(
					imageVector = Icons.Rounded.ChevronRight,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
					modifier = Modifier.size(16.dp),
				)
			}
		}
	}
}

/**
 * 极简本地库首页：
 * 1 行紧凑统计条 + 2×2 苹果备忘录式 0.5.dp 发丝功能卡（砍掉 Hero 大卡与解释副文案）+ 首屏内容直出。
 */
@Composable
private fun LocalOverviewContent(
	allItems: List<IosLibLocalTrackEntry>,
	albums: List<LocalAlbumGroup>,
	artists: List<LocalArtistGroup>,
	folderCount: Int,
	localState: MeloLocalLibraryUiState,
	apiClient: MeloKeepApiClient,
	audioPlayer: IosAudioPlayer,
	currentTrackId: String?,
	offlineIds: Set<String>,
	downloadingProgress: Map<String, Float>,
	filesLoading: Boolean,
	onOpenRoute: (IosLibLocalDetailRoute) -> Unit,
	onSelectSubTab: (IosLibLocalSubTab) -> Unit,
	onToggleFavorite: (MeloTrack) -> Unit,
	onRequestAddToLocalPlaylist: (List<String>) -> Unit,
	onShowNotice: (String) -> Unit,
	onRefresh: () -> Unit,
	bottomPadding: PaddingValues,
) {
	val previewSongs = remember(allItems) { allItems.take(6) }
	val previewPlaylists = remember(localState.playlists) { localState.playlists.take(10) }
	val previewAlbums = remember(albums) { albums.take(12) }
	val previewArtists = remember(artists) { artists.take(12) }
	val allIosTracks = remember(allItems) { allItems.map { it.iosTrack } }

	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(
			top = 4.dp,
			bottom = bottomPadding.calculateBottomPadding() + 20.dp,
		),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		// 1. 极简单行统计条（N 首 · M 专辑 · K 歌手 + 右侧刷新图标）
		item(key = "local-stats-bar", contentType = "stats-bar") {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = if (filesLoading) {
						"正在扫描刷新曲库…"
					} else {
						"${allItems.size} 首 · ${albums.size} 专辑 · ${artists.size} 歌手"
					},
					style = MaterialTheme.typography.bodySmall.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f),
				)
				IconButton(
					onClick = onRefresh,
					enabled = !filesLoading,
					modifier = Modifier.size(32.dp),
				) {
					if (filesLoading) {
						CircularProgressIndicator(
							modifier = Modifier.size(15.dp),
							strokeWidth = 1.8.dp,
							color = MaterialTheme.colorScheme.primary,
						)
					} else {
						Icon(
							imageVector = Icons.Rounded.Refresh,
							contentDescription = "刷新本地库",
							tint = MaterialTheme.colorScheme.primary,
							modifier = Modifier.size(17.dp),
						)
					}
				}
			}
		}

		// 2. 2×2 苹果备忘录式 0.5.dp 发丝功能卡（我喜欢 / 歌单 / 猜你喜欢 / 文件夹）
		item(key = "local-quick-grid", contentType = "quick-grid") {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp),
				verticalArrangement = Arrangement.spacedBy(10.dp),
			) {
				Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
					LocalQuickTile(
						icon = Icons.Rounded.Favorite,
						title = "我喜欢",
						count = localState.favorites.size,
						onClick = { onOpenRoute(IosLibLocalDetailRoute.Favorites) },
						modifier = Modifier.weight(1f),
					)
					LocalQuickTile(
						icon = Icons.Rounded.QueueMusic,
						title = "歌单",
						count = localState.playlists.size,
						onClick = { onSelectSubTab(IosLibLocalSubTab.PLAYLISTS) },
						modifier = Modifier.weight(1f),
					)
				}
				Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
					LocalQuickTile(
						icon = Icons.Rounded.AutoAwesome,
						title = "猜你喜欢",
						count = null,
						onClick = { onOpenRoute(IosLibLocalDetailRoute.DailyMix) },
						modifier = Modifier.weight(1f),
					)
					LocalQuickTile(
						icon = Icons.Rounded.Folder,
						title = "文件夹",
						count = folderCount.takeIf { it > 0 },
						onClick = { onSelectSubTab(IosLibLocalSubTab.FOLDERS) },
						modifier = Modifier.weight(1f),
					)
				}
			}
		}

		// 3. 专辑预览
		if (previewAlbums.isNotEmpty()) {
			item(key = "local-albums-header", contentType = "section-header") {
				IosLibSectionHeader(
					title = "专辑",
					actionLabel = "全部",
					onActionClick = { onSelectSubTab(IosLibLocalSubTab.ALBUMS) },
					modifier = Modifier.padding(top = 6.dp),
				)
			}
			item(key = "local-albums-row", contentType = "carousel") {
				LazyRow(
					contentPadding = PaddingValues(horizontal = 16.dp),
					horizontalArrangement = Arrangement.spacedBy(12.dp),
				) {
					items(
						items = previewAlbums,
						key = { it.name },
						contentType = { "album-card" },
					) { album ->
						LocalAlbumCard(
							group = album,
							modifier = Modifier.width(140.dp),
							onClick = { onOpenRoute(IosLibLocalDetailRoute.Album(album.name)) },
						)
					}
				}
			}
		}

		// 4. 最近添加曲目（分组圆角 + 0.5.dp 发丝线）
		if (previewSongs.isNotEmpty()) {
			item(key = "local-songs-header", contentType = "section-header") {
				IosLibSectionHeader(
					title = "最近添加",
					actionLabel = "全部",
					onActionClick = { onSelectSubTab(IosLibLocalSubTab.SONGS) },
					modifier = Modifier.padding(top = 6.dp),
				)
			}
			item(key = "local-songs-group", contentType = "track-group") {
				IosGroupedCard(modifier = Modifier.padding(horizontal = 16.dp)) {
					previewSongs.forEachIndexed { idx, entry ->
						val isFirst = idx == 0
						val isLast = idx == previewSongs.lastIndex
						val rowColor = if (currentTrackId == entry.iosTrack.id) {
							MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
						} else {
							MaterialTheme.colorScheme.surfaceContainer
						}
						IosLibTrackRowContent(
							track = entry.iosTrack,
							isPlaying = currentTrackId == entry.iosTrack.id,
							isStarred = entry.track.id in localState.favoriteIds,
							isOffline = entry.iosTrack.id in offlineIds,
							downloadProgress = downloadingProgress[entry.iosTrack.id],
							multiSelectMode = false,
							isSelected = false,
							onClick = { audioPlayer.playQueue(allIosTracks, idx) },
							onPlayNext = {
								audioPlayer.playNext(entry.iosTrack)
								onShowNotice("已设为下一首播放")
							},
							onToggleStar = { onToggleFavorite(entry.track) },
							onDownloadOffline = {
								audioPlayer.downloadTrackOffline(entry.iosTrack)
								onShowNotice(iosLibOfflineQueueNotice(1))
							},
							onCancelOffline = { audioPlayer.cancelDownload(entry.iosTrack.id) },
							onDeleteOffline = { audioPlayer.deleteOfflineTrack(entry.iosTrack.id) },
							onAddToPlaylist = {
								if (entry.track.id.isNotBlank()) onRequestAddToLocalPlaylist(listOf(entry.track.id))
							},
							swipeEnabled = true,
							swipeShape = iosLibGroupedItemShape(isFirst, isLast),
							swipeContainerColor = rowColor,
							onSwipeAppendToQueue = {
								audioPlayer.appendToQueue(listOf(entry.iosTrack))
								onShowNotice("已加入待播队尾")
							},
							onSwipePlayNext = {
								audioPlayer.playNext(entry.iosTrack)
								onShowNotice("已设为下一首播放")
							},
						)
						if (idx < previewSongs.lastIndex) {
							IosHairlineDivider(startIndent = 66.dp)
						}
					}
				}
			}
		} else if (!filesLoading) {
			item(key = "local-empty", contentType = "empty") {
				IosQuietEmptyState(
					message = "本地库暂无已索引曲目",
					icon = Icons.Rounded.LibraryMusic,
				)
			}
		}

		// 5. 本地歌单预览
		if (previewPlaylists.isNotEmpty()) {
			item(key = "local-playlists-header", contentType = "section-header") {
				IosLibSectionHeader(
					title = "歌单",
					actionLabel = "全部",
					onActionClick = { onSelectSubTab(IosLibLocalSubTab.PLAYLISTS) },
					modifier = Modifier.padding(top = 6.dp),
				)
			}
			item(key = "local-playlists-row", contentType = "carousel") {
				LazyRow(
					contentPadding = PaddingValues(horizontal = 16.dp),
					horizontalArrangement = Arrangement.spacedBy(12.dp),
				) {
					items(
						items = previewPlaylists,
						key = { it.id },
						contentType = { "playlist-card" },
					) { playlist ->
						val playlistCover = remember(playlist.cover) {
							playlist.cover?.takeIf { it.isNotBlank() }?.let { apiClient.resolveMediaUrl(it) }
						}
						Column(
							modifier = Modifier
								.width(116.dp)
								.clip(RoundedCornerShape(12.dp))
								.clickable { onOpenRoute(IosLibLocalDetailRoute.Playlist(playlist.id)) },
							verticalArrangement = Arrangement.spacedBy(5.dp),
						) {
							CoverArtBox(
								coverUrl = playlistCover,
								modifier = Modifier
									.fillMaxWidth()
									.aspectRatio(1f),
								cornerRadius = 12.dp,
							)
							Text(
								text = playlist.name.ifBlank { "未命名歌单" },
								style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
							)
							Text(
								text = "${playlist.trackCount} 首",
								style = MaterialTheme.typography.labelSmall.tabularNums(),
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					}
				}
			}
		}

		// 6. 歌手预览
		if (previewArtists.isNotEmpty()) {
			item(key = "local-artists-header", contentType = "section-header") {
				IosLibSectionHeader(
					title = "歌手",
					actionLabel = "全部",
					onActionClick = { onSelectSubTab(IosLibLocalSubTab.ARTISTS) },
					modifier = Modifier.padding(top = 6.dp),
				)
			}
			item(key = "local-artists-row", contentType = "carousel") {
				LazyRow(
					contentPadding = PaddingValues(horizontal = 16.dp),
					horizontalArrangement = Arrangement.spacedBy(14.dp),
				) {
					items(
						items = previewArtists,
						key = { it.name },
						contentType = { "artist-avatar" },
					) { artist ->
						Column(
							modifier = Modifier
								.width(76.dp)
								.clip(RoundedCornerShape(12.dp))
								.clickable { onOpenRoute(IosLibLocalDetailRoute.Artist(artist.name)) },
							horizontalAlignment = Alignment.CenterHorizontally,
							verticalArrangement = Arrangement.spacedBy(6.dp),
						) {
							IosArtistAvatar(
								artistName = artist.name,
								coverUrl = artist.cover,
								size = 64.dp,
								border = BorderStroke(IosHairlineWidth, iosLibHairlineBorderColor()),
								preferMeloAvatar = true,
							)
							Text(
								text = artist.name,
								style = MaterialTheme.typography.labelMedium,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
								textAlign = TextAlign.Center,
							)
						}
					}
				}
			}
		}
	}
}

@Composable
private fun LocalAlbumCard(
	group: LocalAlbumGroup,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier
			.clip(RoundedCornerShape(14.dp))
			.clickable(onClick = onClick),
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		CoverArtBox(
			coverUrl = group.cover,
			modifier = Modifier
				.fillMaxWidth()
				.aspectRatio(1f),
			cornerRadius = 14.dp,
		)
		Text(
			text = group.name,
			style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
		Text(
			text = group.artist,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

@Composable
private fun LocalAlbumsContent(
	albums: List<LocalAlbumGroup>,
	loading: Boolean,
	hasMore: Boolean,
	onLoadMore: () -> Unit,
	onOpenAlbum: (LocalAlbumGroup) -> Unit,
	bottomPadding: PaddingValues,
) {
	val rows = remember(albums) { albums.chunked(2) }
	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = 8.dp,
			bottom = bottomPadding.calculateBottomPadding() + 20.dp,
		),
		verticalArrangement = Arrangement.spacedBy(14.dp),
	) {
		item(key = "local-albums-count", contentType = "header") {
			Text(
				text = "${albums.size} 张专辑",
				style = MaterialTheme.typography.labelLarge.tabularNums(),
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		if (albums.isEmpty()) {
			item(key = "local-albums-empty", contentType = "empty") {
				if (loading) {
					Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
						CircularProgressIndicator()
					}
				} else {
					IosQuietEmptyState(message = "本地库暂无专辑", icon = Icons.Rounded.Album)
				}
			}
		} else {
			itemsIndexed(
				items = rows,
				key = { idx, row -> "local-album-row-$idx-${row.first().name}" },
				contentType = { _, _ -> "album-grid-row" },
			) { _, row ->
				Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
					row.forEach { album ->
						LocalAlbumCard(
							group = album,
							modifier = Modifier.weight(1f),
							onClick = { onOpenAlbum(album) },
						)
					}
					if (row.size == 1) Spacer(Modifier.weight(1f))
				}
			}
			if (hasMore) {
				item(key = "local-albums-more", contentType = "load-more") {
					LocalLoadMoreButton(loading = loading, onClick = onLoadMore)
				}
			}
		}
	}
}

@Composable
private fun LocalArtistsContent(
	artists: List<LocalArtistGroup>,
	loading: Boolean,
	hasMore: Boolean,
	onLoadMore: () -> Unit,
	onOpenArtist: (LocalArtistGroup) -> Unit,
	bottomPadding: PaddingValues,
) {
	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = 8.dp,
			bottom = bottomPadding.calculateBottomPadding() + 20.dp,
		),
		verticalArrangement = Arrangement.spacedBy(0.dp),
	) {
		item(key = "local-artists-count", contentType = "header") {
			Text(
				text = "${artists.size} 位歌手",
				style = MaterialTheme.typography.labelLarge.tabularNums(),
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(bottom = 10.dp),
			)
		}
		if (artists.isEmpty()) {
			item(key = "local-artists-empty", contentType = "empty") {
				if (loading) {
					Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
						CircularProgressIndicator()
					}
				} else {
					IosQuietEmptyState(message = "本地库暂无歌手", icon = Icons.Rounded.Person)
				}
			}
		} else {
			itemsIndexed(
				items = artists,
				key = { _, artist -> "local-artist-${artist.name}" },
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
								coverUrl = artist.cover,
								size = 44.dp,
								preferMeloAvatar = true,
							)
							Column(modifier = Modifier.weight(1f)) {
								Text(
									text = artist.name,
									style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
								)
								Text(
									text = "${artist.albumCount} 专辑 · ${artist.items.size} 首",
									style = MaterialTheme.typography.bodySmall.tabularNums(),
									color = MaterialTheme.colorScheme.onSurfaceVariant,
								)
							}
							Icon(
								imageVector = Icons.Rounded.ChevronRight,
								contentDescription = null,
								tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
								modifier = Modifier.size(16.dp),
							)
						}
						if (!isLast) {
							IosHairlineDivider(startIndent = 68.dp)
						}
					}
				}
			}
			if (hasMore) {
				item(key = "local-artists-more", contentType = "load-more") {
					LocalLoadMoreButton(loading = loading, onClick = onLoadMore)
				}
			}
		}
	}
}

@Composable
private fun LocalPlaylistsContent(
	playlists: List<MeloLocalPlaylistSummary>,
	loading: Boolean,
	apiClient: MeloKeepApiClient,
	onCreate: () -> Unit,
	onOpen: (MeloLocalPlaylistSummary) -> Unit,
	onRename: (MeloLocalPlaylistSummary) -> Unit,
	onDelete: (MeloLocalPlaylistSummary) -> Unit,
	bottomPadding: PaddingValues,
) {
	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = 8.dp,
			bottom = bottomPadding.calculateBottomPadding() + 20.dp,
		),
		verticalArrangement = Arrangement.spacedBy(0.dp),
	) {
		item(key = "local-playlists-toolbar", contentType = "toolbar") {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(bottom = 10.dp),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = "${playlists.size} 个歌单",
					style = MaterialTheme.typography.labelLarge.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				IosLibDockPill(
					label = "新建歌单",
					icon = Icons.Rounded.Add,
					emphasized = true,
					onClick = onCreate,
				)
			}
		}
		if (playlists.isEmpty()) {
			item(key = "local-playlists-empty", contentType = "empty") {
				if (loading) {
					Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
						CircularProgressIndicator()
					}
				} else {
					IosQuietEmptyState(
						message = "暂无本地歌单",
						icon = Icons.Rounded.QueueMusic,
						actionLabel = "新建歌单",
						onAction = onCreate,
					)
				}
			}
		} else {
			itemsIndexed(
				items = playlists,
				key = { _, playlist -> "local-playlist-${playlist.id}" },
				contentType = { _, _ -> "playlist" },
			) { idx, playlist ->
				val isFirst = idx == 0
				val isLast = idx == playlists.lastIndex
				val coverUrl = remember(playlist.cover) {
					playlist.cover?.takeIf { it.isNotBlank() }?.let { apiClient.resolveMediaUrl(it) }
				}
				Surface(
					shape = iosLibGroupedItemShape(isFirst, isLast),
					color = MaterialTheme.colorScheme.surfaceContainer,
					modifier = Modifier
						.fillMaxWidth()
						.clip(iosLibGroupedItemShape(isFirst, isLast))
						.clickable { onOpen(playlist) },
				) {
					Column {
						Row(
							modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(12.dp),
						) {
							CoverArtBox(coverUrl = coverUrl, size = 46.dp, cornerRadius = 10.dp)
							Column(modifier = Modifier.weight(1f)) {
								Text(
									text = playlist.name.ifBlank { "未命名歌单" },
									style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
								)
								Text(
									text = "${playlist.trackCount} 首" +
										(playlist.source.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
									style = MaterialTheme.typography.bodySmall.tabularNums(),
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
								)
							}
							IconButton(onClick = { onRename(playlist) }, modifier = Modifier.size(34.dp)) {
								Icon(
									Icons.Rounded.Edit,
									contentDescription = "重命名",
									tint = MaterialTheme.colorScheme.onSurfaceVariant,
									modifier = Modifier.size(17.dp),
								)
							}
							IconButton(onClick = { onDelete(playlist) }, modifier = Modifier.size(34.dp)) {
								Icon(
									Icons.Rounded.Delete,
									contentDescription = "删除",
									tint = MaterialTheme.colorScheme.error,
									modifier = Modifier.size(17.dp),
								)
							}
						}
						if (!isLast) {
							IosHairlineDivider(startIndent = 70.dp)
						}
					}
				}
			}
		}
	}
}

@Composable
private fun LocalTrackListView(
	items: List<IosLibLocalTrackEntry>,
	favoriteIds: Set<String>,
	audioPlayer: IosAudioPlayer,
	currentTrackId: String?,
	offlineIds: Set<String>,
	downloadingProgress: Map<String, Float>,
	emptyLabel: String,
	loading: Boolean,
	onToggleFavorite: (MeloTrack) -> Unit,
	onRequestAddToLocalPlaylist: (List<String>) -> Unit,
	onShowNotice: (String) -> Unit,
	bottomPadding: PaddingValues,
	modifier: Modifier = Modifier,
	showOfflineAll: Boolean = true,
	hasMore: Boolean = false,
	onLoadMore: (() -> Unit)? = null,
	onRemoveFromPlaylist: ((MeloTrack) -> Unit)? = null,
	onRecordSearchResult: (() -> Unit)? = null,
) {
	var multi by remember { mutableStateOf(false) }
	val selected = remember { mutableStateMapOf<String, Boolean>() }
	val iosTracks = remember(items) { items.map { it.iosTrack } }

	val selectedItems by remember(items) {
		derivedStateOf { items.filter { selected[it.iosTrack.id] == true } }
	}
	val hasOfflineRemovable by remember(selectedItems, offlineIds, downloadingProgress) {
		derivedStateOf {
			selectedItems.any { it.iosTrack.id in offlineIds || it.iosTrack.id in downloadingProgress }
		}
	}

	Box(modifier = modifier.fillMaxSize()) {
		LazyColumn(
			modifier = Modifier.fillMaxSize(),
			contentPadding = PaddingValues(
				start = 16.dp,
				end = 16.dp,
				top = 8.dp,
				bottom = bottomPadding.calculateBottomPadding() + if (multi) 120.dp else 20.dp,
			),
			verticalArrangement = Arrangement.spacedBy(0.dp),
		) {
			item(key = "local-list-toolbar", contentType = "toolbar") {
				Column(
					modifier = Modifier.padding(bottom = 10.dp),
					verticalArrangement = Arrangement.spacedBy(8.dp),
				) {
					Text(
						text = "共 ${items.size} 首",
						style = MaterialTheme.typography.labelLarge.tabularNums(),
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					Row(
						modifier = Modifier.horizontalScroll(rememberScrollState()),
						horizontalArrangement = Arrangement.spacedBy(8.dp),
					) {
						IosLibDockPill(
							label = "播放全部",
							icon = Icons.Rounded.PlayArrow,
							emphasized = true,
							onClick = { if (iosTracks.isNotEmpty()) audioPlayer.playQueue(iosTracks, 0) },
						)
						IosLibDockPill(
							label = "随机",
							icon = Icons.Rounded.Shuffle,
							onClick = { if (iosTracks.isNotEmpty()) audioPlayer.playQueue(iosTracks.shuffled(), 0) },
						)
						if (showOfflineAll) {
							IosLibDockPill(
								label = "离线全部",
								icon = Icons.Rounded.CloudDownload,
								onClick = {
									if (iosTracks.isNotEmpty()) {
										audioPlayer.downloadTracksOffline(iosTracks)
										onShowNotice(iosLibOfflineQueueNotice(iosTracks.size))
									}
								},
							)
						}
						IosLibDockPill(
							label = if (multi) "退出" else "多选",
							icon = if (multi) Icons.Rounded.Close else Icons.Rounded.DoneAll,
							onClick = {
								multi = !multi
								if (!multi) selected.clear()
							},
						)
					}
				}
			}

			if (items.isEmpty()) {
				item(key = "local-list-empty", contentType = "empty") {
					if (loading) {
						Box(
							Modifier.fillMaxWidth().padding(vertical = 40.dp),
							contentAlignment = Alignment.Center,
						) {
							CircularProgressIndicator()
						}
					} else {
						IosQuietEmptyState(message = emptyLabel, icon = Icons.Rounded.LibraryMusic)
					}
				}
			} else {
				itemsIndexed(
					items = items,
					key = { idx, item -> "local-track-$idx-${item.iosTrack.id}" },
					contentType = { _, _ -> "track" },
				) { idx, entry ->
					val isSelected = selected[entry.iosTrack.id] == true
					val isFirst = idx == 0
					val isLast = idx == items.lastIndex
					val rowShape = iosLibGroupedItemShape(isFirst, isLast)
					val rowColor = when {
						multi && isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
						currentTrackId == entry.iosTrack.id -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
						else -> MaterialTheme.colorScheme.surfaceContainer
					}
					Surface(
						shape = rowShape,
						color = rowColor,
						modifier = Modifier.fillMaxWidth(),
					) {
						Column {
							IosLibTrackRowContent(
								track = entry.iosTrack,
								isPlaying = currentTrackId == entry.iosTrack.id,
								isStarred = entry.track.id in favoriteIds,
								isOffline = entry.iosTrack.id in offlineIds,
								downloadProgress = downloadingProgress[entry.iosTrack.id],
								multiSelectMode = multi,
								isSelected = isSelected,
								onClick = {
									if (multi) {
										selected[entry.iosTrack.id] = !isSelected
									} else {
										onRecordSearchResult?.invoke()
										audioPlayer.playQueue(iosTracks, idx)
									}
								},
								onLongClick = {
									multi = true
									selected[entry.iosTrack.id] = true
								},
								onPlayNext = {
									audioPlayer.playNext(entry.iosTrack)
									onShowNotice("已设为下一首播放")
								},
								onToggleStar = { onToggleFavorite(entry.track) },
								onDownloadOffline = {
									audioPlayer.downloadTrackOffline(entry.iosTrack)
									onShowNotice(iosLibOfflineQueueNotice(1))
								},
								onCancelOffline = { audioPlayer.cancelDownload(entry.iosTrack.id) },
								onDeleteOffline = { audioPlayer.deleteOfflineTrack(entry.iosTrack.id) },
								onAddToPlaylist = {
									if (entry.track.id.isNotBlank()) onRequestAddToLocalPlaylist(listOf(entry.track.id))
								},
								onRemoveFromPlaylist = onRemoveFromPlaylist?.let { fn -> { fn(entry.track) } },
								swipeEnabled = !multi,
								swipeShape = rowShape,
								swipeContainerColor = rowColor,
								onSwipeAppendToQueue = {
									audioPlayer.appendToQueue(listOf(entry.iosTrack))
									onShowNotice("已加入待播队尾")
								},
								onSwipePlayNext = {
									audioPlayer.playNext(entry.iosTrack)
									onShowNotice("已设为下一首播放")
								},
							)
							if (!isLast) {
								IosHairlineDivider(startIndent = if (multi) 104.dp else 66.dp)
							}
						}
					}
				}
				if (hasMore && onLoadMore != null) {
					item(key = "local-list-more", contentType = "load-more") {
						LocalLoadMoreButton(loading = loading, onClick = onLoadMore)
					}
				}
			}
		}

		androidx.compose.animation.AnimatedVisibility(
			visible = multi,
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
				selectedCount = selectedItems.size,
				totalCount = items.size,
				hasOfflineRemovable = hasOfflineRemovable,
				onSelectAll = {
					if (selectedItems.size == items.size && items.isNotEmpty()) {
						selected.clear()
					} else {
						items.forEach { selected[it.iosTrack.id] = true }
					}
				},
				onExit = {
					multi = false
					selected.clear()
				},
				onPlaySelected = {
					val picked = selectedItems.map { it.iosTrack }
					if (picked.isNotEmpty()) {
						audioPlayer.playQueue(picked, 0)
						multi = false
						selected.clear()
					}
				},
				onDownloadSelected = {
					val picked = selectedItems.map { it.iosTrack }
					if (picked.isNotEmpty()) {
						audioPlayer.downloadTracksOffline(picked)
						onShowNotice(iosLibOfflineQueueNotice(picked.size))
						multi = false
						selected.clear()
					}
				},
				onAddToPlaylist = {
					val ids = selectedItems.mapNotNull { it.track.id.takeIf(String::isNotBlank) }
					if (ids.isNotEmpty()) {
						onRequestAddToLocalPlaylist(ids)
						multi = false
						selected.clear()
					}
				},
				onDeleteOfflineSelected = {
					selectedItems.forEach { entry ->
						val id = entry.iosTrack.id
						if (id in downloadingProgress) audioPlayer.cancelDownload(id)
						if (id in offlineIds) audioPlayer.deleteOfflineTrack(id)
					}
					onShowNotice("已移除所选曲目的手机离线缓存")
					multi = false
					selected.clear()
				},
			)
		}
	}
}

@Composable
private fun LocalLoadMoreButton(loading: Boolean, onClick: () -> Unit) {
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = 12.dp),
		contentAlignment = Alignment.Center,
	) {
		OutlinedButton(
			onClick = onClick,
			enabled = !loading,
			shape = CircleShape,
		) {
			if (loading) {
				CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
				Spacer(Modifier.width(8.dp))
			}
			Text(if (loading) "正在加载…" else "加载更多")
		}
	}
}

// =========================================================================
// 本地库「文件夹」：MeloKeep 目录浏览、音频试听与 17 项标签锁管理
// =========================================================================

@Composable
private fun LocalServerFilesSection(
	studioState: MeloStudioUiState,
	apiClient: MeloKeepApiClient,
	subsonicManager: IosSubsonicManager,
	audioPlayer: IosAudioPlayer,
	currentTrackId: String?,
	isPlaybackPaused: Boolean,
	offlineIds: Set<String>,
	downloadingProgress: Map<String, Float>,
	onNavigateFolder: (String?) -> Unit,
	onLongClickFolder: (IosLibCloudChildFolder) -> Unit,
	onToggleSelect: (String) -> Unit,
	onSelectAll: () -> Unit,
	onClearSelection: () -> Unit,
	onEditTags: (MeloLibraryFile) -> Unit,
	onLoadMore: () -> Unit,
	onShowNotice: (String) -> Unit,
	onRecordSearchResult: (() -> Unit)? = null,
	bottomPadding: PaddingValues,
) {
	val currentClean = remember(studioState.currentFolder) {
		iosLibNormalizeFolder(studioState.currentFolder)
	}
	val pathSegments = remember(currentClean) {
		if (currentClean.isEmpty()) emptyList() else currentClean.split('/').filter { it.isNotBlank() }
	}
	val childFolders = remember(studioState.libraryFolders, currentClean) {
		iosLibExtractImmediateSubFolders(studioState.libraryFolders, currentClean)
	}
	val hasMultiSelection = studioState.selectedFilePaths.isNotEmpty()

	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = 8.dp,
			bottom = bottomPadding.calculateBottomPadding() + if (hasMultiSelection) 120.dp else 20.dp,
		),
		verticalArrangement = Arrangement.spacedBy(0.dp),
	) {
		// 路径面包屑
		item(key = "files-breadcrumb", contentType = "breadcrumb") {
			LazyRow(
				modifier = Modifier.padding(bottom = 10.dp),
				horizontalArrangement = Arrangement.spacedBy(6.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				item(key = "crumb-root", contentType = "crumb") {
					LocalBreadcrumbPill(
						label = "根目录",
						active = pathSegments.isEmpty(),
						onClick = { onNavigateFolder(null) },
					)
				}
				itemsIndexed(
					items = pathSegments,
					key = { idx, seg -> "crumb-$idx-$seg" },
					contentType = { _, _ -> "crumb" },
				) { idx, segment ->
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(6.dp),
					) {
						Icon(
							imageVector = Icons.Rounded.ChevronRight,
							contentDescription = null,
							tint = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.size(15.dp),
						)
						val targetPath = remember(pathSegments, idx) {
							pathSegments.take(idx + 1).joinToString("/")
						}
						LocalBreadcrumbPill(
							label = segment,
							active = idx == pathSegments.lastIndex,
							onClick = { onNavigateFolder(targetPath) },
						)
					}
				}
			}
		}

		// 子文件夹 2 列卡片网格
		if (childFolders.isNotEmpty()) {
			item(key = "files-subfolders-header", contentType = "section-header") {
				IosSectionLabel(
					title = "子目录 · ${childFolders.size}",
					modifier = Modifier.padding(bottom = 8.dp),
				)
			}
			val folderRowCount = (childFolders.size + 1) / 2
			items(
				count = folderRowCount,
				key = { rowIdx -> "subfolder-row-$currentClean-${childFolders[rowIdx * 2].fullPath}" },
				contentType = { "folder-grid-row" },
			) { rowIdx ->
				val left = childFolders[rowIdx * 2]
				val right = childFolders.getOrNull(rowIdx * 2 + 1)
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(bottom = 10.dp),
					horizontalArrangement = Arrangement.spacedBy(10.dp),
				) {
					LocalFolderCard(
						entry = left,
						onClick = { onNavigateFolder(left.fullPath) },
						onRename = { onLongClickFolder(left) },
						modifier = Modifier.weight(1f),
					)
					if (right != null) {
						LocalFolderCard(
							entry = right,
							onClick = { onNavigateFolder(right.fullPath) },
							onRename = { onLongClickFolder(right) },
							modifier = Modifier.weight(1f),
						)
					} else {
						Spacer(Modifier.weight(1f))
					}
				}
			}
		}

		// 文件列表标题与全选
		item(key = "files-list-header", contentType = "section-header") {
			IosSectionLabel(
				title = "音频文件 · ${studioState.libraryFiles.size}",
				modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
				trailing = if (studioState.libraryFiles.isNotEmpty()) {
					{
						val allSelected = studioState.selectedFilePaths.size == studioState.libraryFiles.size
						Text(
							text = if (allSelected) "取消全选" else "全选",
							style = MaterialTheme.typography.labelMedium,
							color = MaterialTheme.colorScheme.primary,
							modifier = Modifier
								.clip(CircleShape)
								.clickable { if (allSelected) onClearSelection() else onSelectAll() }
								.padding(horizontal = 6.dp, vertical = 2.dp),
						)
					}
				} else null,
			)
		}

		if (studioState.filesLoading && studioState.libraryFiles.isEmpty()) {
			item(key = "files-loading", contentType = "loading") {
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.padding(vertical = 42.dp),
					contentAlignment = Alignment.Center,
				) {
					CircularProgressIndicator()
				}
			}
		} else if (studioState.libraryFiles.isEmpty()) {
			item(key = "files-empty", contentType = "empty") {
				IosQuietEmptyState(
					message = if (studioState.fileQuery.isNotBlank()) {
						"未找到匹配「${studioState.fileQuery}」的音频文件"
					} else {
						"当前目录下暂无音频文件"
					},
					icon = Icons.Rounded.Folder,
				)
			}
		} else {
			itemsIndexed(
				items = studioState.libraryFiles,
				key = { _, file -> file.path },
				contentType = { _, _ -> "file" },
			) { idx, file ->
				val isFirst = idx == 0
				val isLast = idx == studioState.libraryFiles.lastIndex
				val iosTrack = remember(file.path, file.track?.id) {
					iosLibMapFileToIosTrack(file, subsonicManager, apiClient)
				}
				val isPlayingThis = currentTrackId == iosTrack.id
				val checked = file.path in studioState.selectedFilePaths
				val swipeEnabled = studioState.selectedFilePaths.isEmpty()
				val rowShape = iosLibGroupedItemShape(isFirst, isLast)
				val rowColor = when {
					checked -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
					isPlayingThis -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
					else -> MaterialTheme.colorScheme.surfaceContainer
				}
				Surface(
					shape = rowShape,
					color = rowColor,
					modifier = Modifier.fillMaxWidth(),
				) {
					Column {
						LocalAudioFileRowContent(
							file = file,
							coverUrl = iosTrack.coverUrl,
							checked = checked,
							isCurrentTrack = isPlayingThis,
							isActivelyPlaying = isPlayingThis && !isPlaybackPaused,
							isOffline = iosTrack.id in offlineIds,
							downloadProgress = downloadingProgress[iosTrack.id],
							onToggleCheck = { onToggleSelect(file.path) },
							onPlay = {
								onRecordSearchResult?.invoke()
								val queue = studioState.libraryFiles.map { item ->
									iosLibMapFileToIosTrack(item, subsonicManager, apiClient)
								}
								audioPlayer.playQueue(queue, idx)
							},
							onPlayNext = {
								audioPlayer.playNext(iosTrack)
								onShowNotice("已设为下一首播放")
							},
							onDownload = {
								audioPlayer.downloadTrackOffline(iosTrack)
								onShowNotice(iosLibOfflineQueueNotice(1))
							},
							onCancelDownload = { audioPlayer.cancelDownload(iosTrack.id) },
							onDeleteOffline = { audioPlayer.deleteOfflineTrack(iosTrack.id) },
							onEditTags = { onEditTags(file) },
							swipeEnabled = swipeEnabled,
							swipeShape = rowShape,
							swipeContainerColor = rowColor,
							onSwipeAppendToQueue = {
								audioPlayer.appendToQueue(listOf(iosTrack))
								onShowNotice("已加入待播队尾")
							},
							onSwipePlayNext = {
								audioPlayer.playNext(iosTrack)
								onShowNotice("已设为下一首播放")
							},
						)
						if (!isLast) {
							IosHairlineDivider(startIndent = 100.dp)
						}
					}
				}
			}

			if (!studioState.nextCursor.isNullOrBlank()) {
				item(key = "files-load-more", contentType = "load-more") {
					LocalLoadMoreButton(loading = studioState.filesLoading, onClick = onLoadMore)
				}
			}
		}
	}
}

@Composable
private fun LocalBreadcrumbPill(
	label: String,
	active: Boolean,
	onClick: () -> Unit,
) {
	Surface(
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
		modifier = Modifier
			.clip(CircleShape)
			.clickable(onClick = onClick),
	) {
		Text(
			text = label,
			style = MaterialTheme.typography.labelMedium.copy(
				fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
			),
			color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
			maxLines = 1,
			softWrap = false,
		)
	}
}

@Composable
private fun LocalFolderCard(
	entry: IosLibCloudChildFolder,
	onClick: () -> Unit,
	onRename: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Surface(
		shape = RoundedCornerShape(14.dp),
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = iosLibHairlineBorder(),
		modifier = modifier
			.clip(RoundedCornerShape(14.dp))
			.combinedClickable(onClick = onClick, onLongClick = onRename),
	) {
		Row(
			modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 11.dp, bottom = 11.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Icon(
				imageVector = Icons.Rounded.Folder,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.primary,
				modifier = Modifier.size(18.dp),
			)
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = entry.name,
					style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				if (entry.root.isNotBlank()) {
					Text(
						text = entry.root.substringAfterLast('/').substringAfterLast('\\').ifBlank { entry.root },
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
			IconButton(onClick = onRename, modifier = Modifier.size(28.dp)) {
				Icon(
					imageVector = Icons.Rounded.Edit,
					contentDescription = "重命名文件夹",
					tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
					modifier = Modifier.size(15.dp),
				)
			}
		}
	}
}

/**
 * 极简本地音频文件行：无刺眼金色描边 FLAC 徽标，将格式、体积、锁数融入安静次行元数据。
 */
@Composable
private fun LocalAudioFileRowContent(
	file: MeloLibraryFile,
	coverUrl: String?,
	checked: Boolean,
	isCurrentTrack: Boolean,
	isActivelyPlaying: Boolean,
	isOffline: Boolean,
	downloadProgress: Float?,
	onToggleCheck: () -> Unit,
	onPlay: () -> Unit,
	onPlayNext: () -> Unit,
	onDownload: () -> Unit,
	onCancelDownload: () -> Unit,
	onDeleteOffline: () -> Unit,
	onEditTags: () -> Unit,
	swipeEnabled: Boolean = false,
	swipeShape: Shape = RectangleShape,
	swipeContainerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
	onSwipeAppendToQueue: (() -> Unit)? = null,
	onSwipePlayNext: (() -> Unit)? = null,
) {
	var menuExpanded by remember { mutableStateOf(false) }

	val displayTitle = remember(file) {
		file.metadata["title"]?.takeIf { it.isNotBlank() }
			?: file.track?.title?.takeIf { it.isNotBlank() }
			?: file.name.substringBeforeLast('.').ifBlank { file.name }
	}
	val ext = remember(file.name, file.track?.codecLabel) {
		file.track?.codecLabel?.takeIf { it.isNotBlank() }
			?: file.name.substringAfterLast('.', "").lowercase()
	}
	val quietMeta = remember(file, ext) {
		val artist = file.metadata["artist"]?.takeIf { it.isNotBlank() }
			?: file.track?.artist?.takeIf { it.isNotBlank() }
		val album = file.metadata["album"]?.takeIf { it.isNotBlank() }
			?: file.track?.album?.takeIf { it.isNotBlank() }
		val codec = ext.uppercase().takeIf { it.isNotBlank() }
		val size = if (file.size > 0L) formatBytesHuman(file.size) else null
		val lockHint = if (file.lockedFields.isNotEmpty()) "已锁 ${file.lockedFields.size}" else null
		listOfNotNull(artist, album, codec, size, lockHint).joinToString(" · ").ifBlank { file.name }
	}

	val rowBody: @Composable () -> Unit = {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.combinedClickable(onClick = onPlay, onLongClick = onToggleCheck)
				.padding(start = 4.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Checkbox(
				checked = checked,
				onCheckedChange = { onToggleCheck() },
			)

			Box(
				modifier = Modifier
					.size(42.dp)
					.clip(RoundedCornerShape(9.dp))
					.background(MaterialTheme.colorScheme.surfaceContainerHigh),
				contentAlignment = Alignment.Center,
			) {
				if (coverUrl != null) {
					CoverArtBox(coverUrl = coverUrl, size = 42.dp, cornerRadius = 9.dp)
				} else if (!isCurrentTrack) {
					Icon(
						imageVector = Icons.Rounded.MusicNote,
						contentDescription = null,
						tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
						modifier = Modifier.size(18.dp),
					)
				}
				if (isCurrentTrack) {
					Box(
						modifier = Modifier
							.fillMaxSize()
							.background(Color.Black.copy(alpha = 0.45f)),
						contentAlignment = Alignment.Center,
					) {
						Icon(
							imageVector = if (isActivelyPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
							contentDescription = null,
							tint = Color.White,
							modifier = Modifier.size(16.dp),
						)
					}
				}
			}

			Column(
				modifier = Modifier.weight(1f),
				verticalArrangement = Arrangement.spacedBy(2.dp),
			) {
				Text(
					text = displayTitle,
					style = MaterialTheme.typography.titleSmall.copy(
						fontWeight = if (isCurrentTrack) FontWeight.SemiBold else FontWeight.Medium,
					),
					color = if (isCurrentTrack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
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
					Text(
						text = quietMeta,
						style = MaterialTheme.typography.bodySmall.tabularNums(),
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
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
						text = { Text("编辑标签") },
						leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
						onClick = {
							menuExpanded = false
							onEditTags()
						},
					)
					DropdownMenuItem(
						text = { Text("下一首播放") },
						leadingIcon = { Icon(Icons.Rounded.PlaylistPlay, contentDescription = null) },
						onClick = {
							menuExpanded = false
							onPlayNext()
						},
					)
					when {
						downloadProgress != null -> {
							DropdownMenuItem(
								text = { Text("取消离线下载") },
								leadingIcon = { Icon(Icons.Rounded.Close, contentDescription = null) },
								onClick = {
									menuExpanded = false
									onCancelDownload()
								},
							)
						}
						!isOffline -> {
							DropdownMenuItem(
								text = { Text("下载到手机离线") },
								leadingIcon = { Icon(Icons.Rounded.CloudDownload, contentDescription = null) },
								onClick = {
									menuExpanded = false
									onDownload()
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

	if (swipeEnabled && (onSwipeAppendToQueue != null || onSwipePlayNext != null)) {
		IosSwipeableSongRow(
			enabled = true,
			shape = swipeShape,
			containerColor = swipeContainerColor,
			onAppendToQueue = onSwipeAppendToQueue,
			onPlayNext = onSwipePlayNext,
			content = rowBody,
		)
	} else {
		rowBody()
	}
}

// =========================================================================
// 本地库批处理悬浮坞 / 歌单选择器 / 文件夹与 17 项标签弹窗
// =========================================================================

@Composable
internal fun IosLibLocalFolderBatchDock(
	selectedCount: Int,
	allSelected: Boolean,
	busy: Boolean,
	onToggleSelectAll: () -> Unit,
	onClear: () -> Unit,
	onScrape: () -> Unit,
	onOffline: () -> Unit,
	onAddToPlaylist: () -> Unit,
	onMove: () -> Unit,
	onDelete: () -> Unit,
) {
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
					text = "已选 $selectedCount 项",
					style = MaterialTheme.typography.labelLarge.tabularNums(),
					fontWeight = FontWeight.SemiBold,
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
							.clickable(enabled = !busy, onClick = onToggleSelectAll),
					) {
						Row(
							modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(4.dp),
						) {
							Icon(
								imageVector = if (allSelected) Icons.Rounded.CheckBox else Icons.Rounded.DoneAll,
								contentDescription = null,
								modifier = Modifier.size(14.dp),
								tint = MaterialTheme.colorScheme.primary,
							)
							Text(
								text = if (allSelected) "取消全选" else "全选",
								style = MaterialTheme.typography.labelMedium,
								color = MaterialTheme.colorScheme.primary,
								maxLines = 1,
								softWrap = false,
							)
						}
					}
					IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
						Icon(Icons.Rounded.Close, contentDescription = "退出多选", modifier = Modifier.size(16.dp))
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
					label = "去刮削",
					icon = Icons.Rounded.AutoAwesome,
					emphasized = true,
					onClick = { if (!busy) onScrape() },
				)
				IosLibDockPill(
					label = "离线",
					icon = Icons.Rounded.CloudDownload,
					onClick = { if (!busy) onOffline() },
				)
				IosLibDockPill(
					label = "加歌单",
					icon = Icons.Rounded.PlaylistAdd,
					onClick = { if (!busy) onAddToPlaylist() },
				)
				IosLibDockPill(
					label = "移动",
					icon = Icons.Rounded.DriveFileMove,
					onClick = { if (!busy) onMove() },
				)
				Surface(
					shape = CircleShape,
					color = MaterialTheme.colorScheme.errorContainer,
					contentColor = MaterialTheme.colorScheme.onErrorContainer,
					modifier = Modifier
						.clip(CircleShape)
						.clickable(enabled = !busy, onClick = onDelete),
				) {
					Row(
						modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(4.dp),
					) {
						Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
						Text("删除", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
					}
				}
			}
		}
	}
}

@Composable
internal fun IosLibLocalPlaylistPickerSheet(
	trackCount: Int,
	playlists: List<MeloLocalPlaylistSummary>,
	onDismiss: () -> Unit,
	onPick: (String) -> Unit,
	onCreate: (String) -> Unit,
) {
	var newName by remember { mutableStateOf("") }
	ModalBottomSheet(onDismissRequest = onDismiss) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.navigationBarsPadding()
				.imePadding()
				.padding(horizontal = 20.dp)
				.padding(bottom = 24.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Text("加入本地歌单（$trackCount 首）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
				OutlinedTextField(
					value = newName,
					onValueChange = { newName = it },
					label = { Text("新建歌单并加入") },
					singleLine = true,
					shape = RoundedCornerShape(12.dp),
					modifier = Modifier.weight(1f),
				)
				Button(
					onClick = {
						val clean = newName.trim()
						if (clean.isNotEmpty()) onCreate(clean)
					},
					enabled = newName.trim().isNotEmpty(),
				) {
					Text("新建")
				}
			}
			IosHairlineDivider()
			if (playlists.isEmpty()) {
				IosQuietEmptyState(message = "暂无本地歌单", icon = Icons.Rounded.QueueMusic)
			} else {
				LazyColumn(
					modifier = Modifier
						.fillMaxWidth()
						.heightIn(max = 260.dp),
					verticalArrangement = Arrangement.spacedBy(0.dp),
				) {
					itemsIndexed(
						items = playlists,
						key = { _, p -> p.id },
						contentType = { _, _ -> "local-pl-pick" },
					) { idx, pl ->
						val isFirst = idx == 0
						val isLast = idx == playlists.lastIndex
						Surface(
							shape = iosLibGroupedItemShape(isFirst, isLast),
							color = MaterialTheme.colorScheme.surfaceContainer,
							modifier = Modifier
								.fillMaxWidth()
								.clip(iosLibGroupedItemShape(isFirst, isLast))
								.clickable { onPick(pl.id) },
						) {
							Column {
								Row(
									modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.spacedBy(10.dp),
								) {
									Icon(
										imageVector = Icons.Rounded.QueueMusic,
										contentDescription = null,
										tint = MaterialTheme.colorScheme.primary,
										modifier = Modifier.size(20.dp),
									)
									Column(modifier = Modifier.weight(1f)) {
										Text(
											text = pl.name.ifBlank { "未命名歌单" },
											style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
											maxLines = 1,
											overflow = TextOverflow.Ellipsis,
										)
										Text(
											text = "${pl.trackCount} 首",
											style = MaterialTheme.typography.bodySmall.tabularNums(),
											color = MaterialTheme.colorScheme.onSurfaceVariant,
										)
									}
								}
								if (!isLast) {
									IosHairlineDivider(startIndent = 42.dp)
								}
							}
						}
					}
				}
			}
		}
	}
}

@Composable
private fun LocalPlaylistNameDialog(
	title: String,
	confirmLabel: String,
	initial: String,
	onDismiss: () -> Unit,
	onConfirm: (String) -> Unit,
) {
	var nameInput by remember(initial) { mutableStateOf(initial) }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(title) },
		text = {
			OutlinedTextField(
				value = nameInput,
				onValueChange = { nameInput = it },
				singleLine = true,
				shape = RoundedCornerShape(12.dp),
				label = { Text("歌单名称") },
				modifier = Modifier.fillMaxWidth(),
			)
		},
		confirmButton = {
			Button(
				onClick = { onConfirm(nameInput.trim()) },
				enabled = nameInput.trim().isNotEmpty(),
			) {
				Text(confirmLabel)
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text("取消") }
		},
	)
}

@Composable
internal fun IosLibCreateFolderDialog(
	parentFolder: String,
	onDismiss: () -> Unit,
	onConfirm: (String) -> Unit,
) {
	var nameInput by remember { mutableStateOf("") }
	AlertDialog(
		onDismissRequest = onDismiss,
		icon = {
			Icon(
				imageVector = Icons.Rounded.CreateNewFolder,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.primary,
			)
		},
		title = { Text("新建目录") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
				Text(
					text = if (parentFolder.isBlank()) "在根目录创建子目录" else "在「$parentFolder」下创建子目录",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				OutlinedTextField(
					value = nameInput,
					onValueChange = { nameInput = it },
					singleLine = true,
					shape = RoundedCornerShape(12.dp),
					label = { Text("目录名称") },
					modifier = Modifier.fillMaxWidth(),
				)
			}
		},
		confirmButton = {
			Button(
				onClick = { onConfirm(nameInput) },
				enabled = nameInput.trim().isNotEmpty(),
			) {
				Text("创建")
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text("取消") }
		},
	)
}

@Composable
internal fun IosLibRenameFolderDialog(
	folder: IosLibCloudChildFolder,
	onDismiss: () -> Unit,
	onConfirm: (String) -> Unit,
) {
	var nameInput by remember(folder.fullPath) { mutableStateOf(folder.name) }
	AlertDialog(
		onDismissRequest = onDismiss,
		icon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
		title = { Text("重命名文件夹") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
				Text(
					text = "原路径：${folder.fullPath}",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				OutlinedTextField(
					value = nameInput,
					onValueChange = { nameInput = it },
					singleLine = true,
					shape = RoundedCornerShape(12.dp),
					label = { Text("新名称") },
					modifier = Modifier.fillMaxWidth(),
				)
			}
		},
		confirmButton = {
			Button(
				onClick = { onConfirm(nameInput) },
				enabled = nameInput.trim().isNotEmpty() && nameInput.trim() != folder.name,
			) {
				Text("保存")
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text("取消") }
		},
	)
}

@Composable
internal fun IosLibFileTagEditorSheet(
	file: MeloLibraryFile,
	onDismiss: () -> Unit,
	onSave: (Map<String, String>) -> Unit,
	onUnlockAll: () -> Unit,
) {
	val initialValues = remember(file.path, file.metadata) {
		LOCAL_17_TAG_FIELDS.associate { def -> def.key to file.metadata[def.key].orEmpty() }
	}
	val draft = remember(file.path) {
		mutableStateMapOf<String, String>().apply { putAll(initialValues) }
	}
	var showExtended by rememberSaveable(file.path) { mutableStateOf(true) }
	val isEditable = file.editable != false

	ModalBottomSheet(onDismissRequest = onDismiss) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.verticalScroll(rememberScrollState())
				.navigationBarsPadding()
				.imePadding()
				.padding(horizontal = 20.dp)
				.padding(bottom = 28.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
					Text(
						text = "编辑标签（17 项标准字段）",
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.Bold,
					)
					Text(
						text = file.name,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
				IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
					Icon(Icons.Rounded.Close, contentDescription = "关闭", modifier = Modifier.size(18.dp))
				}
			}

			if (!isEditable) {
				Surface(
					shape = RoundedCornerShape(12.dp),
					color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
					modifier = Modifier.fillMaxWidth(),
				) {
					Text(
						text = "当前音频格式暂不支持直接写入内嵌标签。",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onErrorContainer,
						modifier = Modifier.padding(12.dp),
					)
				}
			}

			IosGroupedCard {
				Column(
					modifier = Modifier.padding(12.dp),
					verticalArrangement = Arrangement.spacedBy(8.dp),
				) {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(6.dp),
					) {
						Icon(
							imageVector = if (file.lockedFields.isNotEmpty()) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
							contentDescription = null,
							tint = if (file.lockedFields.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.size(16.dp),
						)
						Text(
							text = if (file.lockedFields.isNotEmpty()) {
								"已锁定 ${file.lockedFields.size} 项防刮削覆盖"
							} else {
								"保存修改后将自动锁定对应字段"
							},
							style = MaterialTheme.typography.labelMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}

					if (file.lockedFields.isNotEmpty()) {
						FlowRow(
							horizontalArrangement = Arrangement.spacedBy(6.dp),
							verticalArrangement = Arrangement.spacedBy(6.dp),
						) {
							file.lockedFields.forEach { fieldKey ->
								Surface(
									shape = CircleShape,
									color = MaterialTheme.colorScheme.surfaceContainerHigh,
									border = iosLibHairlineBorder(),
								) {
									Row(
										modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
										verticalAlignment = Alignment.CenterVertically,
										horizontalArrangement = Arrangement.spacedBy(4.dp),
									) {
										Icon(
											imageVector = Icons.Rounded.Lock,
											contentDescription = null,
											tint = MaterialTheme.colorScheme.primary,
											modifier = Modifier.size(11.dp),
										)
										Text(
											text = resolveLocalTagLabel(fieldKey),
											style = MaterialTheme.typography.labelSmall,
											color = MaterialTheme.colorScheme.onSurface,
										)
									}
								}
							}
						}

						OutlinedButton(
							onClick = onUnlockAll,
							shape = RoundedCornerShape(12.dp),
							modifier = Modifier.fillMaxWidth(),
						) {
							Icon(Icons.Rounded.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
							Spacer(Modifier.width(6.dp))
							Text("解除全部锁定")
						}
					}
				}
			}

			val coreFields = remember { LOCAL_17_TAG_FIELDS.filter { it.isCore } }
			val extendedFields = remember { LOCAL_17_TAG_FIELDS.filterNot { it.isCore } }

			IosSectionLabel(title = "核心字段 · ${coreFields.size}")
			coreFields.forEach { def ->
				LocalTagFieldInput(
					def = def,
					value = draft[def.key].orEmpty(),
					locked = def.key in file.lockedFields,
					enabled = isEditable,
					onValueChange = { draft[def.key] = it },
				)
			}

			Row(
				modifier = Modifier
					.fillMaxWidth()
					.clip(RoundedCornerShape(10.dp))
					.clickable { showExtended = !showExtended }
					.padding(vertical = 4.dp),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = "扩展字段 · ${extendedFields.size}",
					style = MaterialTheme.typography.labelLarge,
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Icon(
					imageVector = if (showExtended) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
					contentDescription = if (showExtended) "收起" else "展开",
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}

			androidx.compose.animation.AnimatedVisibility(visible = showExtended) {
				Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
					extendedFields.forEach { def ->
						LocalTagFieldInput(
							def = def,
							value = draft[def.key].orEmpty(),
							locked = def.key in file.lockedFields,
							enabled = isEditable,
							onValueChange = { draft[def.key] = it },
						)
					}
				}
			}

			Spacer(Modifier.height(4.dp))

			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(10.dp),
			) {
				OutlinedButton(
					onClick = onDismiss,
					shape = RoundedCornerShape(12.dp),
					modifier = Modifier
						.weight(1f)
						.height(44.dp),
				) {
					Text("取消")
				}
				Button(
					onClick = {
						val changed = buildMap {
							LOCAL_17_TAG_FIELDS.forEach { def ->
								val nextVal = draft[def.key].orEmpty()
								val prevVal = initialValues[def.key].orEmpty()
								if (nextVal != prevVal) put(def.key, nextVal)
							}
						}
						onSave(changed)
					},
					enabled = isEditable,
					shape = RoundedCornerShape(12.dp),
					modifier = Modifier
						.weight(1.4f)
						.height(44.dp),
				) {
					Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
					Spacer(Modifier.width(6.dp))
					Text("保存并锁定")
				}
			}
		}
	}
}

@Composable
private fun LocalTagFieldInput(
	def: LocalTagFieldDef,
	value: String,
	locked: Boolean,
	enabled: Boolean,
	onValueChange: (String) -> Unit,
) {
	val fieldLabel = remember(def.key) { resolveLocalTagLabel(def.key, def.fallbackLabel) }
	OutlinedTextField(
		modifier = Modifier.fillMaxWidth(),
		value = value,
		onValueChange = onValueChange,
		enabled = enabled,
		singleLine = def.key != "comment",
		maxLines = if (def.key == "comment") 3 else 1,
		shape = RoundedCornerShape(12.dp),
		label = {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(4.dp),
			) {
				Text(fieldLabel)
				if (locked) {
					Icon(
						imageVector = Icons.Rounded.Lock,
						contentDescription = "已锁定",
						tint = MaterialTheme.colorScheme.primary,
						modifier = Modifier.size(13.dp),
					)
				}
			}
		},
		placeholder = { Text(def.placeholder) },
	)
}

private fun resolveLocalTagLabel(fieldKey: String, fallback: String? = null): String {
	val translated = translateIosTagKey(fieldKey)
	if (translated != fieldKey) return translated
	return fallback ?: LOCAL_17_TAG_FIELDS.firstOrNull { it.key == fieldKey }?.fallbackLabel ?: fieldKey
}

@Composable
internal fun IosLibMoveFilesSheet(
	folders: List<MeloLibraryFolder>,
	defaultRoot: String,
	currentFolder: String,
	selectedCount: Int,
	onDismiss: () -> Unit,
	onConfirmMove: (targetRoot: String, targetFolder: String) -> Unit,
) {
	val distinctRoots = remember(folders, defaultRoot) {
		(folders.mapNotNull { it.root.takeIf(String::isNotBlank) } + listOfNotNull(defaultRoot.takeIf(String::isNotBlank)))
			.distinct()
	}
	val targetOptions = remember(folders, distinctRoots) {
		buildList {
			val rootChoices = distinctRoots.ifEmpty { listOf("") }
			rootChoices.forEach { r -> add(MeloLibraryFolder(root = r, folder = "")) }
			folders
				.filter { it.folder.isNotBlank() && it.folder != "." }
				.distinctBy { "${it.root}\u0000${iosLibNormalizeFolder(it.folder)}" }
				.forEach { add(it.copy(folder = iosLibNormalizeFolder(it.folder))) }
		}
	}

	ModalBottomSheet(onDismissRequest = onDismiss) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.navigationBarsPadding()
				.padding(horizontal = 20.dp)
				.padding(bottom = 24.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Text(
				text = "移动已选 $selectedCount 个文件到…",
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.Bold,
			)
			LazyColumn(
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(max = 320.dp),
				verticalArrangement = Arrangement.spacedBy(0.dp),
			) {
				itemsIndexed(
					items = targetOptions,
					key = { _, item -> "${item.root}\u0000${item.folder}" },
					contentType = { _, _ -> "move-target" },
				) { idx, option ->
					val isFirst = idx == 0
					val isLast = idx == targetOptions.lastIndex
					val isCurrent = iosLibNormalizeFolder(option.folder) == currentFolder
					val label = if (option.folder.isBlank()) {
						val rootName = option.root.substringAfterLast('/').substringAfterLast('\\')
						if (distinctRoots.size > 1 && rootName.isNotBlank()) "根目录 ($rootName)" else "根目录"
					} else {
						option.folder
					}
					Surface(
						shape = iosLibGroupedItemShape(isFirst, isLast),
						color = if (isCurrent) {
							MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
						} else {
							MaterialTheme.colorScheme.surfaceContainer
						},
						modifier = Modifier
							.fillMaxWidth()
							.clip(iosLibGroupedItemShape(isFirst, isLast))
							.clickable {
								onConfirmMove(option.root.ifBlank { defaultRoot }, option.folder)
							},
					) {
						Column {
							Row(
								modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(10.dp),
							) {
								Icon(
									imageVector = Icons.Rounded.Folder,
									contentDescription = null,
									tint = MaterialTheme.colorScheme.primary,
									modifier = Modifier.size(18.dp),
								)
								Column(modifier = Modifier.weight(1f)) {
									Text(
										text = label,
										style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
										maxLines = 1,
										overflow = TextOverflow.Ellipsis,
									)
									if (isCurrent) {
										Text(
											text = "当前所在目录",
											style = MaterialTheme.typography.bodySmall,
											color = MaterialTheme.colorScheme.onSurfaceVariant,
										)
									}
								}
							}
							if (!isLast) {
								IosHairlineDivider(startIndent = 40.dp)
							}
						}
					}
				}
			}
			OutlinedButton(
				onClick = onDismiss,
				shape = RoundedCornerShape(12.dp),
				modifier = Modifier
					.fillMaxWidth()
					.height(42.dp),
			) {
				Text("取消")
			}
		}
	}
}

@Composable
internal fun IosLibDeleteFilesConfirmDialog(
	selectedCount: Int,
	onDismiss: () -> Unit,
	onConfirm: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		icon = {
			Icon(
				imageVector = Icons.Rounded.Delete,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.error,
			)
		},
		title = { Text("确认删除所选文件") },
		text = {
			Text("将永久删除选中的 $selectedCount 个本地音频文件，删除后不可恢复。")
		},
		confirmButton = {
			Button(
				onClick = onConfirm,
				colors = ButtonDefaults.buttonColors(
					containerColor = MaterialTheme.colorScheme.error,
					contentColor = MaterialTheme.colorScheme.onError,
				),
			) {
				Text("永久删除")
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text("取消") }
		},
	)
}
