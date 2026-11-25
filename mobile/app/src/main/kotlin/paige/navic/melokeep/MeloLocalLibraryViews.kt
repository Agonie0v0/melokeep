@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package paige.navic.melokeep

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.agonie0v0.melokeep.shared.MeloKeepApiClient
import io.github.agonie0v0.melokeep.shared.MeloLibraryFile
import io.github.agonie0v0.melokeep.shared.MeloLocalLibraryController
import io.github.agonie0v0.melokeep.shared.MeloLocalLibraryUiState
import io.github.agonie0v0.melokeep.shared.MeloLocalPlaylistSummary
import io.github.agonie0v0.melokeep.shared.MeloStudioController
import io.github.agonie0v0.melokeep.shared.MeloStudioUiState
import io.github.agonie0v0.melokeep.shared.MeloTrack
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import paige.navic.database.entity.DownloadEntity
import paige.navic.database.entity.DownloadStatus
import paige.navic.di.LocalSnackBarState
import paige.navic.domain.manager.session.DownloadManager
import paige.navic.domain.model.DomainSong
import paige.navic.playback.MediaPlayer
import paige.navic.ui.component.common.CoverArt
import paige.navic.ui.component.dialog.DeleteDownloadTarget
import paige.navic.ui.component.dialog.rememberDownloadDeletionHandler
import paige.navic.ui.component.layout.PullToRefreshBox
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.filled.Play
import paige.navic.ui.icons.filled.Star
import paige.navic.ui.icons.outlined.Add
import paige.navic.ui.icons.outlined.Album
import paige.navic.ui.icons.outlined.Artist
import paige.navic.ui.icons.outlined.ArrowBack
import paige.navic.ui.icons.outlined.Checklist
import paige.navic.ui.icons.outlined.ChevronForward
import paige.navic.ui.icons.outlined.Close
import paige.navic.ui.icons.outlined.Delete
import paige.navic.ui.icons.outlined.Download
import paige.navic.ui.icons.outlined.Edit
import paige.navic.ui.icons.outlined.LibraryMusic
import paige.navic.ui.icons.outlined.MoreVert
import paige.navic.ui.icons.outlined.Note
import paige.navic.ui.icons.outlined.PlaylistRemove
import paige.navic.ui.icons.outlined.Refresh
import paige.navic.ui.icons.outlined.Shuffle
import paige.navic.ui.icons.outlined.Star
import paige.navic.ui.theme.MeloBottomChromeClearance
import paige.navic.ui.theme.defaultFont
import paige.navic.ui.theme.meloGoldText
import paige.navic.ui.theme.tabularNums
import paige.navic.util.toFileSize

private data class LocalAlbumGroup(
	val name: String,
	val artist: String,
	val cover: String?,
	val items: List<LibraryLocalTrackItem>
)

private data class LocalArtistGroup(
	val name: String,
	val cover: String?,
	val albumCount: Int,
	val items: List<LibraryLocalTrackItem>
)

private const val LOCAL_UNKNOWN_ALBUM = "未知专辑"
private const val LOCAL_UNKNOWN_ARTIST = "未知歌手"

private fun MeloTrack.toLocalItem(): LibraryLocalTrackItem =
	LibraryLocalTrackItem(this, MeloTrackMapper.toDomainSong(this))

private fun LibraryLocalTrackItem.albumName(): String =
	track.album.trim().ifBlank { LOCAL_UNKNOWN_ALBUM }

private fun LibraryLocalTrackItem.artistName(): String =
	track.artist.trim().ifBlank { LOCAL_UNKNOWN_ARTIST }

private fun groupLocalAlbums(items: List<LibraryLocalTrackItem>): List<LocalAlbumGroup> =
	items.groupBy { it.albumName() }
		.map { (name, list) ->
			val artist = list.groupingBy { it.artistName() }.eachCount()
				.maxByOrNull { it.value }?.key ?: LOCAL_UNKNOWN_ARTIST
			LocalAlbumGroup(
				name = name,
				artist = artist,
				cover = list.firstNotNullOfOrNull { it.song.coverArtId?.takeIf { c -> c.isNotBlank() } },
				items = list
			)
		}
		.sortedBy { it.name.lowercase() }

private fun groupLocalArtists(items: List<LibraryLocalTrackItem>): List<LocalArtistGroup> =
	items.groupBy { it.artistName() }
		.map { (name, list) ->
			LocalArtistGroup(
				name = name,
				cover = list.firstNotNullOfOrNull { it.song.coverArtId?.takeIf { c -> c.isNotBlank() } },
				albumCount = list.mapTo(HashSet()) { it.albumName() }.size,
				items = list
			)
		}
		.sortedBy { it.name.lowercase() }

private data class LocalIndexedCatalog(
	val allItems: List<LibraryLocalTrackItem>,
	val albums: List<LocalAlbumGroup>,
	val artists: List<LocalArtistGroup>,
	val folderCount: Int
)

@Composable
private fun rememberLocalIndexedCatalog(
	studioState: MeloStudioUiState,
	apiClient: MeloKeepApiClient
): LocalIndexedCatalog {
	val allItems = remember(studioState.libraryFiles) {
		studioState.libraryFiles.map { file ->
			val track = file.track ?: libraryBuildFallbackMeloTrack(file, apiClient)
			LibraryLocalTrackItem(track, MeloTrackMapper.toDomainSong(track))
		}
	}
	val albums = remember(allItems) { groupLocalAlbums(allItems) }
	val artists = remember(allItems) { groupLocalArtists(allItems) }
	val folderCount = remember(studioState.libraryFolders) {
		studioState.libraryFolders.count { libraryNormalizeFolder(it.folder).isNotEmpty() }
	}
	return remember(allItems, albums, artists, folderCount) {
		LocalIndexedCatalog(
			allItems = allItems,
			albums = albums,
			artists = artists,
			folderCount = folderCount
		)
	}
}

@Composable
private fun LocalPlaylistMutationDialogs(
	renameTarget: MeloLocalPlaylistSummary?,
	deleteTarget: MeloLocalPlaylistSummary?,
	onDismissRename: () -> Unit,
	onDismissDelete: () -> Unit,
	onConfirmRename: (MeloLocalPlaylistSummary, String) -> Unit,
	onConfirmDelete: (MeloLocalPlaylistSummary) -> Unit
) {
	renameTarget?.let { target ->
		LibraryLocalPlaylistNameDialog(
			title = "重命名歌单",
			confirmLabel = "保存",
			initial = target.name,
			onDismiss = onDismissRename,
			onConfirm = { name -> onConfirmRename(target, name) }
		)
	}

	deleteTarget?.let { target ->
		AlertDialog(
			onDismissRequest = onDismissDelete,
			icon = {
				Icon(
					imageVector = Icons.Outlined.Delete,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.error
				)
			},
			title = { Text("删除歌单") },
			text = { Text("确认删除「${target.name}」吗？仅删除歌单，不删除音乐文件。") },
			confirmButton = {
				Button(
					onClick = { onConfirmDelete(target) },
					colors = ButtonDefaults.buttonColors(
						containerColor = MaterialTheme.colorScheme.error,
						contentColor = MaterialTheme.colorScheme.onError
					)
				) {
					Text("删除")
				}
			},
			dismissButton = {
				TextButton(onClick = onDismissDelete) {
					Text("取消")
				}
			}
		)
	}
}

@Composable
internal fun LibraryLocalSection(
	subTab: LibraryLocalSubTab,
	onOpenRoute: (LibraryLocalDetailRoute) -> Unit,
	onSelectSubTab: (LibraryLocalSubTab) -> Unit,
	studioState: MeloStudioUiState,
	studioController: MeloStudioController,
	localState: MeloLocalLibraryUiState,
	localController: MeloLocalLibraryController,
	downloadsMap: Map<String, DownloadEntity>,
	currentPlayingPair: Pair<String?, Boolean>,
	hostNavBottom: Dp,
	onRequestAddToLocalPlaylist: (List<String>) -> Unit,
	onRenameFolder: (LibraryFolderChildEntry) -> Unit,
	onEditTags: (MeloLibraryFile) -> Unit,
	onRefreshFiles: () -> Unit
) {
	val apiClient = koinInject<MeloKeepApiClient>()
	val player = koinInject<MediaPlayer>()
	val downloadManager = koinInject<DownloadManager>()
	val deletionHandler = rememberDownloadDeletionHandler()
	val scope = rememberCoroutineScope()

	val catalog = rememberLocalIndexedCatalog(studioState, apiClient)
	val allItems = catalog.allItems
	val albums = catalog.albums
	val artists = catalog.artists
	val folderCount = catalog.folderCount

	var createPlaylistOpen by remember { mutableStateOf(false) }
	var renameTarget by remember { mutableStateOf<MeloLocalPlaylistSummary?>(null) }
	var deleteTarget by remember { mutableStateOf<MeloLocalPlaylistSummary?>(null) }

	val currentPlayingSongId = currentPlayingPair.first
	val hasMore = !studioState.nextCursor.isNullOrBlank()
	val toggleFavorite: (MeloTrack) -> Unit = { localController.toggleFavorite(it) }
	val downloadOne: (DomainSong) -> Unit = { s ->
		scope.launch { queueSongsOffline(downloadManager, listOf(s)) }
	}
	val cancelOne: (DomainSong) -> Unit = { s -> downloadManager.cancelDownload(s.id) }
	val deleteOne: (DomainSong) -> Unit = { s ->
		deletionHandler.requestDelete(DeleteDownloadTarget.Single(s.id, s.title))
	}

	Box(modifier = Modifier.fillMaxSize()) {
		when (subTab) {
			LibraryLocalSubTab.OVERVIEW -> PullToRefreshBox(
				modifier = Modifier.fillMaxSize(),
				finished = !(studioState.filesLoading || localState.loading),
				onRefresh = onRefreshFiles,
				key = listOf(studioState.filesLoading, localState.loading, allItems.size, localState.playlists.size)
			) {
				LocalOverviewContent(
					allItems = allItems,
					albums = albums,
					artists = artists,
					folderCount = folderCount,
					localState = localState,
					downloadsMap = downloadsMap,
					currentPlayingSongId = currentPlayingSongId,
					filesLoading = studioState.filesLoading,
					onOpenRoute = onOpenRoute,
					onSelectSubTab = onSelectSubTab,
					onPlaySongs = { songs, idx -> player.playNow(songs, idx) },
					onToggleFavorite = toggleFavorite,
					onDownloadSong = downloadOne,
					onCancelSong = cancelOne,
					onDeleteOffline = deleteOne,
					onRefresh = onRefreshFiles
				)
			}

			LibraryLocalSubTab.SONGS -> PullToRefreshBox(
				modifier = Modifier.fillMaxSize(),
				finished = !studioState.filesLoading,
				onRefresh = onRefreshFiles,
				key = listOf(studioState.filesLoading, allItems.size)
			) {
				LocalTrackListView(
					items = allItems,
					downloadsMap = downloadsMap,
					favoriteIds = localState.favoriteIds,
					currentPlayingSongId = currentPlayingSongId,
					hostNavBottom = hostNavBottom,
					emptyLabel = if (studioState.fileQuery.isNotBlank()) {
						"未找到匹配「${studioState.fileQuery}」的曲目"
					} else {
						"本地库暂无已索引曲目"
					},
					loading = studioState.filesLoading,
					title = null,
					showOfflineAll = false,
					hasMore = hasMore,
					onLoadMore = studioController::loadMoreLibraryFiles,
					onToggleFavorite = toggleFavorite,
					onDownloadSong = downloadOne,
					onCancelSong = cancelOne,
					onDeleteOffline = deleteOne,
					onRequestAddToLocalPlaylist = onRequestAddToLocalPlaylist
				)
			}

			LibraryLocalSubTab.ALBUMS -> PullToRefreshBox(
				modifier = Modifier.fillMaxSize(),
				finished = !studioState.filesLoading,
				onRefresh = onRefreshFiles,
				key = listOf(studioState.filesLoading, albums.size)
			) {
				LocalAlbumsContent(
					albums = albums,
					loading = studioState.filesLoading,
					hasMore = hasMore,
					onLoadMore = studioController::loadMoreLibraryFiles,
					onOpenAlbum = { onOpenRoute(LibraryLocalDetailRoute.Album(it.name)) }
				)
			}

			LibraryLocalSubTab.ARTISTS -> PullToRefreshBox(
				modifier = Modifier.fillMaxSize(),
				finished = !studioState.filesLoading,
				onRefresh = onRefreshFiles,
				key = listOf(studioState.filesLoading, artists.size)
			) {
				LocalArtistsContent(
					artists = artists,
					loading = studioState.filesLoading,
					hasMore = hasMore,
					onLoadMore = studioController::loadMoreLibraryFiles,
					onOpenArtist = { onOpenRoute(LibraryLocalDetailRoute.Artist(it.name)) }
				)
			}

			LibraryLocalSubTab.PLAYLISTS -> PullToRefreshBox(
				modifier = Modifier.fillMaxSize(),
				finished = !localState.loading,
				onRefresh = onRefreshFiles,
				key = listOf(localState.loading, localState.playlists.size)
			) {
				LocalPlaylistsContent(
					playlists = localState.playlists,
					loading = localState.loading,
					onCreate = { createPlaylistOpen = true },
					onOpen = { onOpenRoute(LibraryLocalDetailRoute.Playlist(it.id)) },
					onRename = { renameTarget = it },
					onDelete = { deleteTarget = it }
				)
			}

			LibraryLocalSubTab.FOLDERS -> LocalServerFilesSection(
				studioState = studioState,
				currentPlayingSongId = currentPlayingSongId,
				isPlaybackPaused = currentPlayingPair.second,
				onRefresh = onRefreshFiles,
				onNavigateFolder = { folder ->
					studioController.loadLibraryFiles(
						query = studioState.fileQuery,
						folder = folder,
						reset = true
					)
				},
				onLongClickFolder = onRenameFolder,
				onToggleSelect = studioController::toggleFileSelection,
				onSelectAll = studioController::selectAllFiles,
				onClearSelection = studioController::clearFileSelection,
				onPlayFile = { file ->
					val track = file.track ?: libraryBuildFallbackMeloTrack(file, apiClient)
					player.playNow(MeloTrackMapper.toDomainSong(track))
				},
				onEditTags = onEditTags,
				onLoadMore = studioController::loadMoreLibraryFiles,
				downloadsMap = downloadsMap,
				onDownloadFile = { file ->
					val track = file.track ?: libraryBuildFallbackMeloTrack(file, apiClient)
					downloadOne(MeloTrackMapper.toDomainSong(track))
				},
				onCancelFileDownload = { file ->
					val track = file.track ?: libraryBuildFallbackMeloTrack(file, apiClient)
					cancelOne(MeloTrackMapper.toDomainSong(track))
				},
				onDeleteFileOffline = { file ->
					val track = file.track ?: libraryBuildFallbackMeloTrack(file, apiClient)
					deleteOne(MeloTrackMapper.toDomainSong(track))
				}
			)
		}
	}

	if (createPlaylistOpen) {
		LibraryLocalPlaylistNameDialog(
			title = "新建歌单",
			confirmLabel = "创建",
			initial = "",
			onDismiss = { createPlaylistOpen = false },
			onConfirm = { name ->
				createPlaylistOpen = false
				localController.createPlaylist(name)
			}
		)
	}

	LocalPlaylistMutationDialogs(
		renameTarget = renameTarget,
		deleteTarget = deleteTarget,
		onDismissRename = { renameTarget = null },
		onDismissDelete = { deleteTarget = null },
		onConfirmRename = { target, name ->
			renameTarget = null
			localController.renamePlaylist(target.id, name)
		},
		onConfirmDelete = { target ->
			deleteTarget = null
			localController.deletePlaylist(target.id)
		}
	)
}

@Composable
internal fun LibraryLocalDetailView(
	route: LibraryLocalDetailRoute,
	onBack: () -> Unit,
	studioState: MeloStudioUiState,
	localState: MeloLocalLibraryUiState,
	localController: MeloLocalLibraryController,
	downloadsMap: Map<String, DownloadEntity>,
	currentPlayingPair: Pair<String?, Boolean>,
	hostNavBottom: Dp,
	onRequestAddToLocalPlaylist: (List<String>) -> Unit
) {
	val apiClient = koinInject<MeloKeepApiClient>()
	val downloadManager = koinInject<DownloadManager>()
	val deletionHandler = rememberDownloadDeletionHandler()
	val scope = rememberCoroutineScope()

	val catalog = rememberLocalIndexedCatalog(studioState, apiClient)
	val allItems = catalog.allItems
	val albums = catalog.albums
	val artists = catalog.artists
	val favoriteItems = remember(localState.favorites) { localState.favorites.map { it.toLocalItem() } }
	var dailyMixSeed by remember { mutableIntStateOf(0) }
	val dailyMixItems = remember(allItems, dailyMixSeed) {
		allItems.shuffled(kotlin.random.Random(dailyMixSeed * 31 + allItems.size)).take(30)
	}

	var renameTarget by remember { mutableStateOf<MeloLocalPlaylistSummary?>(null) }
	var deleteTarget by remember { mutableStateOf<MeloLocalPlaylistSummary?>(null) }

	val currentPlayingSongId = currentPlayingPair.first
	val toggleFavorite: (MeloTrack) -> Unit = { localController.toggleFavorite(it) }
	val downloadOne: (DomainSong) -> Unit = { s ->
		scope.launch { queueSongsOffline(downloadManager, listOf(s)) }
	}
	val cancelOne: (DomainSong) -> Unit = { s -> downloadManager.cancelDownload(s.id) }
	val deleteOne: (DomainSong) -> Unit = { s ->
		deletionHandler.requestDelete(DeleteDownloadTarget.Single(s.id, s.title))
	}

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colorScheme.background)
	) {
		when (route) {
			LibraryLocalDetailRoute.Favorites -> Column(Modifier.fillMaxSize()) {
				LocalDetailHeader(
					title = "我喜欢",
					subtitle = "${favoriteItems.size} 首",
					cover = null,
					onBack = onBack
				)
				LocalTrackListView(
					modifier = Modifier.weight(1f),
					items = favoriteItems,
					downloadsMap = downloadsMap,
					favoriteIds = localState.favoriteIds,
					currentPlayingSongId = currentPlayingSongId,
					hostNavBottom = hostNavBottom,
					emptyLabel = "暂无喜欢的歌曲",
					loading = localState.loading,
					onToggleFavorite = toggleFavorite,
					onDownloadSong = downloadOne,
					onCancelSong = cancelOne,
					onDeleteOffline = deleteOne,
					onRequestAddToLocalPlaylist = onRequestAddToLocalPlaylist
				)
			}

			LibraryLocalDetailRoute.DailyMix -> Column(Modifier.fillMaxSize()) {
				LocalDetailHeader(
					title = "猜你喜欢",
					subtitle = "${dailyMixItems.size} 首",
					cover = null,
					onBack = onBack,
					actions = {
						IconButton(onClick = { dailyMixSeed += 1 }) {
							Icon(Icons.Outlined.Refresh, contentDescription = "换一批")
						}
					}
				)
				LocalTrackListView(
					modifier = Modifier.weight(1f),
					items = dailyMixItems,
					downloadsMap = downloadsMap,
					favoriteIds = localState.favoriteIds,
					currentPlayingSongId = currentPlayingSongId,
					hostNavBottom = hostNavBottom,
					emptyLabel = "暂无推荐曲目",
					loading = studioState.filesLoading,
					onToggleFavorite = toggleFavorite,
					onDownloadSong = downloadOne,
					onCancelSong = cancelOne,
					onDeleteOffline = deleteOne,
					onRequestAddToLocalPlaylist = onRequestAddToLocalPlaylist
				)
			}

			is LibraryLocalDetailRoute.Playlist -> {
				val summary = remember(localState.playlists, route.id) {
					localState.playlists.firstOrNull { it.id == route.id }
				}
				val active = localState.activePlaylist?.takeIf { it.id == route.id }
				val playlistItems = remember(active) { active?.tracks.orEmpty().map { it.toLocalItem() } }
				val playlistName = active?.name?.takeIf { it.isNotBlank() } ?: summary?.name ?: "歌单"
				val target = summary ?: MeloLocalPlaylistSummary(id = route.id, name = playlistName)
				Column(Modifier.fillMaxSize()) {
					LocalDetailHeader(
						title = playlistName,
						subtitle = "${playlistItems.size} 首",
						cover = summary?.cover,
						onBack = onBack,
						actions = {
							IconButton(onClick = { renameTarget = target }) {
								Icon(Icons.Outlined.Edit, contentDescription = "重命名歌单")
							}
							IconButton(onClick = { deleteTarget = target }) {
								Icon(
									Icons.Outlined.Delete,
									contentDescription = "删除歌单",
									tint = MaterialTheme.colorScheme.error
								)
							}
						}
					)
					LocalTrackListView(
						modifier = Modifier.weight(1f),
						items = playlistItems,
						downloadsMap = downloadsMap,
						favoriteIds = localState.favoriteIds,
						currentPlayingSongId = currentPlayingSongId,
						hostNavBottom = hostNavBottom,
						emptyLabel = "歌单暂无曲目",
						loading = localState.playlistDetailLoading,
						onToggleFavorite = toggleFavorite,
						onDownloadSong = downloadOne,
						onCancelSong = cancelOne,
						onDeleteOffline = deleteOne,
						onRequestAddToLocalPlaylist = onRequestAddToLocalPlaylist,
						onRemoveFromPlaylist = { track ->
							localController.removeTrackFromPlaylist(route.id, track.id)
						},
						removePlaylistTitle = playlistName,
						onRemoveTracksFromPlaylist = { tracks ->
							localController.removeTracksFromPlaylist(route.id, tracks.map { it.id })
						}
					)
				}
			}

			is LibraryLocalDetailRoute.Album -> {
				val group = remember(albums, route.name) {
					albums.firstOrNull { it.name == route.name }
				}
				Column(Modifier.fillMaxSize()) {
					LocalDetailHeader(
						title = route.name,
						subtitle = group?.let { "${it.artist} · ${it.items.size} 首" },
						cover = group?.cover,
						onBack = onBack
					)
					LocalTrackListView(
						modifier = Modifier.weight(1f),
						items = group?.items.orEmpty(),
						downloadsMap = downloadsMap,
						favoriteIds = localState.favoriteIds,
						currentPlayingSongId = currentPlayingSongId,
						hostNavBottom = hostNavBottom,
						emptyLabel = "该专辑暂无曲目",
						loading = studioState.filesLoading,
						onToggleFavorite = toggleFavorite,
						onDownloadSong = downloadOne,
						onCancelSong = cancelOne,
						onDeleteOffline = deleteOne,
						onRequestAddToLocalPlaylist = onRequestAddToLocalPlaylist
					)
				}
			}

			is LibraryLocalDetailRoute.Artist -> {
				val group = remember(artists, route.name) {
					artists.firstOrNull { it.name == route.name }
				}
				Column(Modifier.fillMaxSize()) {
					LocalDetailHeader(
						title = route.name,
						subtitle = group?.let { "${it.albumCount} 专辑 · ${it.items.size} 首" },
						cover = group?.cover,
						onBack = onBack,
						leadingAvatar = {
							MeloArtistAvatar(
								artistName = route.name,
								coverArtId = group?.cover,
								preferMeloAvatar = true,
								modifier = Modifier.size(40.dp),
								shape = CircleShape
							)
						}
					)
					LocalTrackListView(
						modifier = Modifier.weight(1f),
						items = group?.items.orEmpty(),
						downloadsMap = downloadsMap,
						favoriteIds = localState.favoriteIds,
						currentPlayingSongId = currentPlayingSongId,
						hostNavBottom = hostNavBottom,
						emptyLabel = "该歌手暂无曲目",
						loading = studioState.filesLoading,
						onToggleFavorite = toggleFavorite,
						onDownloadSong = downloadOne,
						onCancelSong = cancelOne,
						onDeleteOffline = deleteOne,
						onRequestAddToLocalPlaylist = onRequestAddToLocalPlaylist
					)
				}
			}
		}
	}

	LocalPlaylistMutationDialogs(
		renameTarget = renameTarget,
		deleteTarget = deleteTarget,
		onDismissRename = { renameTarget = null },
		onDismissDelete = { deleteTarget = null },
		onConfirmRename = { target, name ->
			renameTarget = null
			localController.renamePlaylist(target.id, name)
		},
		onConfirmDelete = { target ->
			deleteTarget = null
			localController.deletePlaylist(target.id)
			if ((route as? LibraryLocalDetailRoute.Playlist)?.id == target.id) {
				onBack()
			}
		}
	)
}

@Composable
private fun LocalDetailHeader(
	title: String,
	subtitle: String?,
	cover: String?,
	onBack: () -> Unit,
	leadingAvatar: (@Composable () -> Unit)? = null,
	actions: @Composable RowScope.() -> Unit = {}
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.statusBarsPadding()
			.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp)
	) {
		IconButton(onClick = onBack) {
			Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
		}
		if (leadingAvatar != null) {
			leadingAvatar()
		} else if (!cover.isNullOrBlank()) {
			CoverArt(
				coverArtId = cover,
				modifier = Modifier.size(40.dp),
				shape = RoundedCornerShape(9.dp)
			)
		}
		Column(
			modifier = Modifier.weight(1f),
			verticalArrangement = Arrangement.spacedBy(1.dp)
		) {
			Text(
				text = title,
				style = MaterialTheme.typography.titleMedium.copy(
					fontFamily = defaultFont(grade = 100),
					fontWeight = FontWeight.SemiBold
				),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
			if (!subtitle.isNullOrBlank()) {
				Text(
					text = subtitle,
					style = MaterialTheme.typography.labelMedium.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
			}
		}
		actions()
	}
}

@Composable
private fun LocalQuickTile(
	icon: ImageVector,
	title: String,
	count: Int?,
	onClick: () -> Unit,
	modifier: Modifier = Modifier
) {
	Surface(
		onClick = onClick,
		modifier = modifier,
		shape = RoundedCornerShape(14.dp),
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = libraryHairlineBorder()
	) {
		Row(
			modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(10.dp)
		) {
			Icon(
				imageVector = icon,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.primary,
				modifier = Modifier.size(18.dp)
			)
			Text(
				text = title,
				style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f)
			)
			if (count != null) {
				Text(
					text = "$count",
					style = MaterialTheme.typography.bodyMedium.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					softWrap = false
				)
			} else {
				Icon(
					imageVector = Icons.Outlined.ChevronForward,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
					modifier = Modifier.size(16.dp)
				)
			}
		}
	}
}

@Composable
private fun LocalOverviewContent(
	allItems: List<LibraryLocalTrackItem>,
	albums: List<LocalAlbumGroup>,
	artists: List<LocalArtistGroup>,
	folderCount: Int,
	localState: MeloLocalLibraryUiState,
	downloadsMap: Map<String, DownloadEntity>,
	currentPlayingSongId: String?,
	filesLoading: Boolean,
	onOpenRoute: (LibraryLocalDetailRoute) -> Unit,
	onSelectSubTab: (LibraryLocalSubTab) -> Unit,
	onPlaySongs: (List<DomainSong>, Int) -> Unit,
	onToggleFavorite: (MeloTrack) -> Unit,
	onDownloadSong: (DomainSong) -> Unit,
	onCancelSong: (DomainSong) -> Unit,
	onDeleteOffline: (DomainSong) -> Unit,
	onRefresh: () -> Unit
) {
	val previewSongs = remember(allItems) { allItems.take(6) }
	val previewPlaylists = remember(localState.playlists) { localState.playlists.take(10) }
	val previewAlbums = remember(albums) { albums.take(12) }
	val previewArtists = remember(artists) { artists.take(12) }
	val allSongs = remember(allItems) { allItems.map { it.song } }

	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(top = 4.dp, bottom = MeloBottomChromeClearance),
		verticalArrangement = Arrangement.spacedBy(12.dp)
	) {
		// 1. 极简单行统计条（替代 Hero 标语大卡与 5 枚胶囊）
		item(key = "local-stats-bar", contentType = "stats-bar") {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically
			) {
				Text(
					text = if (filesLoading && allItems.isEmpty()) {
						"正在同步曲库…"
					} else {
						"${allItems.size} 首 · ${albums.size} 专辑 · ${artists.size} 歌手"
					},
					style = MaterialTheme.typography.bodySmall.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f)
				)
				IconButton(
					onClick = onRefresh,
					enabled = !filesLoading,
					modifier = Modifier.size(32.dp)
				) {
					Icon(
						imageVector = Icons.Outlined.Refresh,
						contentDescription = "刷新本地库",
						tint = MaterialTheme.colorScheme.primary,
						modifier = Modifier.size(17.dp)
					)
				}
			}
		}

		// 2. 苹果备忘录式 2x2 功能分类卡（图标 + 标题 + 数量）
		item(key = "local-quick-grid", contentType = "quick-grid") {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp),
				verticalArrangement = Arrangement.spacedBy(10.dp)
			) {
				Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
					LocalQuickTile(
						icon = Icons.Outlined.Star,
						title = "我喜欢",
						count = localState.favorites.size,
						onClick = { onOpenRoute(LibraryLocalDetailRoute.Favorites) },
						modifier = Modifier.weight(1f)
					)
					LocalQuickTile(
						icon = Icons.Outlined.LibraryMusic,
						title = "歌单",
						count = localState.playlists.size,
						onClick = { onSelectSubTab(LibraryLocalSubTab.PLAYLISTS) },
						modifier = Modifier.weight(1f)
					)
				}
				Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
					LocalQuickTile(
						icon = MeloIcons.Sparkles,
						title = "猜你喜欢",
						count = null,
						onClick = { onOpenRoute(LibraryLocalDetailRoute.DailyMix) },
						modifier = Modifier.weight(1f)
					)
					LocalQuickTile(
						icon = MeloIcons.Folder,
						title = "文件夹",
						count = folderCount.takeIf { it > 0 },
						onClick = { onSelectSubTab(LibraryLocalSubTab.FOLDERS) },
						modifier = Modifier.weight(1f)
					)
				}
			}
		}

		// 3. 专辑预览（首屏直出）
		if (previewAlbums.isNotEmpty()) {
			item(key = "local-albums-header", contentType = "section-header") {
				LibrarySectionHeader(
					title = "专辑",
					actionLabel = "全部",
					onActionClick = { onSelectSubTab(LibraryLocalSubTab.ALBUMS) },
					modifier = Modifier.padding(top = 6.dp)
				)
			}
			item(key = "local-albums-row", contentType = "carousel") {
				LazyRow(
					contentPadding = PaddingValues(horizontal = 16.dp),
					horizontalArrangement = Arrangement.spacedBy(12.dp)
				) {
					items(
						items = previewAlbums,
						key = { it.name },
						contentType = { "album-card" }
					) { album ->
						LocalAlbumCard(
							group = album,
							modifier = Modifier.width(142.dp),
							onClick = { onOpenRoute(LibraryLocalDetailRoute.Album(album.name)) }
						)
					}
				}
			}
		}

		// 4. 最近添加曲目（首屏直出分组卡）
		if (previewSongs.isNotEmpty()) {
			item(key = "local-songs-header", contentType = "section-header") {
				LibrarySectionHeader(
					title = "最近添加",
					actionLabel = "全部",
					onActionClick = { onSelectSubTab(LibraryLocalSubTab.SONGS) },
					modifier = Modifier.padding(top = 6.dp)
				)
			}
			item(key = "local-songs-group", contentType = "track-group") {
				MeloGroupedCard(modifier = Modifier.padding(horizontal = 16.dp)) {
					previewSongs.forEachIndexed { idx, item ->
						LocalTrackRowContent(
							item = item,
							download = downloadsMap[item.song.id],
							isPlaying = currentPlayingSongId == item.song.id,
							isFavorite = item.track.id in localState.favoriteIds,
							selecting = false,
							selected = false,
							showMore = false,
							onClick = { onPlaySongs(allSongs, idx) },
							onLongClick = {},
							onToggleFavorite = { onToggleFavorite(item.track) },
							onDownload = { onDownloadSong(item.song) },
							onCancelDownload = { onCancelSong(item.song) },
							onDeleteOffline = { onDeleteOffline(item.song) },
							onMore = {}
						)
						if (idx < previewSongs.lastIndex) {
							MeloHairlineDivider(startIndent = 64.dp)
						}
					}
				}
			}
		} else if (!filesLoading) {
			item(key = "local-empty", contentType = "empty") {
				MeloQuietEmptyState(
					message = "本地库暂无已索引曲目",
					icon = Icons.Outlined.LibraryMusic
				)
			}
		}

		// 5. 本地歌单预览
		if (previewPlaylists.isNotEmpty()) {
			item(key = "local-playlists-header", contentType = "section-header") {
				LibrarySectionHeader(
					title = "歌单",
					actionLabel = "全部",
					onActionClick = { onSelectSubTab(LibraryLocalSubTab.PLAYLISTS) },
					modifier = Modifier.padding(top = 6.dp)
				)
			}
			item(key = "local-playlists-row", contentType = "carousel") {
				LazyRow(
					contentPadding = PaddingValues(horizontal = 16.dp),
					horizontalArrangement = Arrangement.spacedBy(12.dp)
				) {
					items(
						items = previewPlaylists,
						key = { it.id },
						contentType = { "playlist-card" }
					) { playlist ->
						Column(
							modifier = Modifier
								.width(116.dp)
								.clip(RoundedCornerShape(12.dp))
								.clickable { onOpenRoute(LibraryLocalDetailRoute.Playlist(playlist.id)) },
							verticalArrangement = Arrangement.spacedBy(5.dp)
						) {
							CoverArt(
								coverArtId = playlist.cover,
								modifier = Modifier.fillMaxWidth(),
								shape = RoundedCornerShape(12.dp)
							)
							Text(
								text = playlist.name.ifBlank { "未命名歌单" },
								style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
								maxLines = 1,
								overflow = TextOverflow.Ellipsis
							)
							Text(
								text = "${playlist.trackCount} 首",
								style = MaterialTheme.typography.labelSmall.tabularNums(),
								color = MaterialTheme.colorScheme.onSurfaceVariant
							)
						}
					}
				}
			}
		}

		// 6. 歌手预览
		if (previewArtists.isNotEmpty()) {
			item(key = "local-artists-header", contentType = "section-header") {
				LibrarySectionHeader(
					title = "歌手",
					actionLabel = "全部",
					onActionClick = { onSelectSubTab(LibraryLocalSubTab.ARTISTS) },
					modifier = Modifier.padding(top = 6.dp)
				)
			}
			item(key = "local-artists-row", contentType = "carousel") {
				LazyRow(
					contentPadding = PaddingValues(horizontal = 16.dp),
					horizontalArrangement = Arrangement.spacedBy(14.dp)
				) {
					items(
						items = previewArtists,
						key = { it.name },
						contentType = { "artist-avatar" }
					) { artist ->
						Column(
							modifier = Modifier
								.width(80.dp)
								.clip(RoundedCornerShape(12.dp))
								.clickable { onOpenRoute(LibraryLocalDetailRoute.Artist(artist.name)) },
							horizontalAlignment = Alignment.CenterHorizontally,
							verticalArrangement = Arrangement.spacedBy(6.dp)
						) {
							MeloArtistAvatar(
								artistName = artist.name,
								coverArtId = artist.cover,
								preferMeloAvatar = true,
								modifier = Modifier
									.size(68.dp)
									.border(MeloHairlineWidth, libraryHairlineBorderColor(), CircleShape),
								shape = CircleShape
							)
							Text(
								text = artist.name,
								style = MaterialTheme.typography.labelMedium,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
								textAlign = TextAlign.Center
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
	modifier: Modifier = Modifier
) {
	Column(
		modifier = modifier
			.clip(RoundedCornerShape(14.dp))
			.clickable(onClick = onClick),
		verticalArrangement = Arrangement.spacedBy(6.dp)
	) {
		CoverArt(
			coverArtId = group.cover,
			modifier = Modifier.fillMaxWidth(),
			shape = RoundedCornerShape(14.dp)
		)
		Text(
			text = group.name,
			style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis
		)
		Text(
			text = group.artist,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis
		)
	}
}

@Composable
private fun LocalAlbumsContent(
	albums: List<LocalAlbumGroup>,
	loading: Boolean,
	hasMore: Boolean,
	onLoadMore: () -> Unit,
	onOpenAlbum: (LocalAlbumGroup) -> Unit
) {
	val rows = remember(albums) { albums.chunked(2) }
	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = 8.dp,
			bottom = MeloBottomChromeClearance
		),
		verticalArrangement = Arrangement.spacedBy(14.dp)
	) {
		item(key = "local-albums-count", contentType = "header") {
			Text(
				text = "${albums.size} 张专辑",
				style = MaterialTheme.typography.labelLarge.tabularNums(),
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
		}
		if (albums.isEmpty()) {
			item(key = "local-albums-empty", contentType = "empty") {
				if (loading) {
					Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
						CircularProgressIndicator()
					}
				} else {
					MeloQuietEmptyState(
						message = "本地库暂无专辑",
						icon = Icons.Outlined.Album
					)
				}
			}
		} else {
			itemsIndexed(
				items = rows,
				key = { idx, row -> "local-album-row-$idx-${row.first().name}" },
				contentType = { _, _ -> "album-grid-row" }
			) { _, row ->
				Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
					row.forEach { album ->
						LocalAlbumCard(
							group = album,
							modifier = Modifier.weight(1f),
							onClick = { onOpenAlbum(album) }
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
	onOpenArtist: (LocalArtistGroup) -> Unit
) {
	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = 8.dp,
			bottom = MeloBottomChromeClearance
		),
		verticalArrangement = Arrangement.spacedBy(0.dp)
	) {
		item(key = "local-artists-count", contentType = "header") {
			Text(
				text = "${artists.size} 位歌手",
				style = MaterialTheme.typography.labelLarge.tabularNums(),
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(bottom = 10.dp)
			)
		}
		if (artists.isEmpty()) {
			item(key = "local-artists-empty", contentType = "empty") {
				if (loading) {
					Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
						CircularProgressIndicator()
					}
				} else {
					MeloQuietEmptyState(
						message = "本地库暂无歌手",
						icon = Icons.Outlined.Artist
					)
				}
			}
		} else {
			itemsIndexed(
				items = artists,
				key = { _, artist -> "local-artist-${artist.name}" },
				contentType = { _, _ -> "artist" }
			) { idx, artist ->
				val isFirst = idx == 0
				val isLast = idx == artists.lastIndex
				Surface(
					onClick = { onOpenArtist(artist) },
					shape = libraryGroupedItemShape(isFirst, isLast),
					color = MaterialTheme.colorScheme.surfaceContainer,
					modifier = Modifier.fillMaxWidth()
				) {
					Column {
						Row(
							modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(12.dp)
						) {
							MeloArtistAvatar(
								artistName = artist.name,
								coverArtId = artist.cover,
								preferMeloAvatar = true,
								modifier = Modifier.size(46.dp),
								shape = CircleShape
							)
							Column(modifier = Modifier.weight(1f)) {
								Text(
									text = artist.name,
									style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
									maxLines = 1,
									overflow = TextOverflow.Ellipsis
								)
								Text(
									text = "${artist.albumCount} 专辑 · ${artist.items.size} 首",
									style = MaterialTheme.typography.bodySmall.tabularNums(),
									color = MaterialTheme.colorScheme.onSurfaceVariant
								)
							}
							Icon(
								imageVector = Icons.Outlined.ChevronForward,
								contentDescription = null,
								tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
								modifier = Modifier.size(16.dp)
							)
						}
						if (!isLast) {
							MeloHairlineDivider(startIndent = 70.dp)
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
private fun LocalLoadMoreButton(loading: Boolean, onClick: () -> Unit) {
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = 12.dp),
		contentAlignment = Alignment.Center
	) {
		OutlinedButton(
			onClick = onClick,
			enabled = !loading,
			shape = CircleShape
		) {
			if (loading) {
				CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
				Spacer(Modifier.width(8.dp))
			}
			Text(if (loading) "正在加载…" else "加载更多")
		}
	}
}

@Composable
private fun LocalPlaylistsContent(
	playlists: List<MeloLocalPlaylistSummary>,
	loading: Boolean,
	onCreate: () -> Unit,
	onOpen: (MeloLocalPlaylistSummary) -> Unit,
	onRename: (MeloLocalPlaylistSummary) -> Unit,
	onDelete: (MeloLocalPlaylistSummary) -> Unit
) {
	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = 8.dp,
			bottom = MeloBottomChromeClearance
		),
		verticalArrangement = Arrangement.spacedBy(0.dp)
	) {
		item(key = "local-playlists-toolbar", contentType = "toolbar") {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(bottom = 10.dp),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically
			) {
				Text(
					text = "${playlists.size} 个歌单",
					style = MaterialTheme.typography.labelLarge.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
				FilledTonalButton(
					onClick = onCreate,
					shape = CircleShape,
					contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
					modifier = Modifier.height(32.dp)
				) {
					Icon(Icons.Outlined.Add, null, modifier = Modifier.size(14.dp))
					Spacer(Modifier.width(4.dp))
					Text("新建歌单", style = MaterialTheme.typography.labelMedium)
				}
			}
		}
		if (playlists.isEmpty()) {
			item(key = "local-playlists-empty", contentType = "empty") {
				if (loading) {
					Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
						CircularProgressIndicator()
					}
				} else {
					MeloQuietEmptyState(
						message = "暂无本地歌单",
						icon = Icons.Outlined.LibraryMusic,
						actionLabel = "新建歌单",
						onAction = onCreate
					)
				}
			}
		} else {
			itemsIndexed(
				items = playlists,
				key = { _, playlist -> "local-playlist-${playlist.id}" },
				contentType = { _, _ -> "playlist" }
			) { idx, playlist ->
				val isFirst = idx == 0
				val isLast = idx == playlists.lastIndex
				Surface(
					onClick = { onOpen(playlist) },
					shape = libraryGroupedItemShape(isFirst, isLast),
					color = MaterialTheme.colorScheme.surfaceContainer,
					modifier = Modifier.fillMaxWidth()
				) {
					Column {
						Row(
							modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(12.dp)
						) {
							CoverArt(
								coverArtId = playlist.cover,
								modifier = Modifier.size(48.dp),
								shape = RoundedCornerShape(10.dp)
							)
							Column(modifier = Modifier.weight(1f)) {
								Text(
									text = playlist.name.ifBlank { "未命名歌单" },
									style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
									maxLines = 1,
									overflow = TextOverflow.Ellipsis
								)
								Text(
									text = "${playlist.trackCount} 首" +
										(playlist.source.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
									style = MaterialTheme.typography.bodySmall.tabularNums(),
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis
								)
							}
							IconButton(onClick = { onRename(playlist) }, modifier = Modifier.size(34.dp)) {
								Icon(
									Icons.Outlined.Edit,
									contentDescription = "重命名",
									tint = MaterialTheme.colorScheme.onSurfaceVariant,
									modifier = Modifier.size(17.dp)
								)
							}
							IconButton(onClick = { onDelete(playlist) }, modifier = Modifier.size(34.dp)) {
								Icon(
									Icons.Outlined.Delete,
									contentDescription = "删除",
									tint = MaterialTheme.colorScheme.error,
									modifier = Modifier.size(17.dp)
								)
							}
						}
						if (!isLast) {
							MeloHairlineDivider(startIndent = 72.dp)
						}
					}
				}
			}
		}
	}
}

@Composable
private fun LocalTrackRowContent(
	item: LibraryLocalTrackItem,
	download: DownloadEntity?,
	isPlaying: Boolean,
	isFavorite: Boolean,
	selecting: Boolean,
	selected: Boolean,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	onToggleFavorite: () -> Unit,
	onDownload: () -> Unit,
	onCancelDownload: () -> Unit,
	onDeleteOffline: () -> Unit,
	onMore: () -> Unit,
	modifier: Modifier = Modifier,
	showMore: Boolean = true
) {
	val song = item.song
	val qualityLabel = remember(item.track.id, item.track.hasLossless, item.track.codecLabel, item.track.qualities) {
		libraryFormatLocalQuietQuality(item.track)
	}
	val subtitleText = remember(item.track.id, item.track.artist, item.track.album, qualityLabel) {
		listOfNotNull(
			item.track.artist.takeIf { it.isNotBlank() },
			item.track.album.takeIf { it.isNotBlank() },
			qualityLabel
		).joinToString(" · ").ifBlank { "未知歌手" }
	}
	val isDownloaded = download?.status == DownloadStatus.DOWNLOADED
	val rowBgColor = when {
		selecting && selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
		isPlaying -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
		else -> MaterialTheme.colorScheme.surfaceContainer
	}

	MeloSwipeableSongRow(
		song = song,
		selectionMode = selecting,
		modifier = modifier.fillMaxWidth()
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.background(MaterialTheme.colorScheme.surfaceContainer)
				.background(rowBgColor)
				.combinedClickable(onClick = onClick, onLongClick = onLongClick)
				.padding(horizontal = 12.dp, vertical = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(11.dp)
		) {
			if (selecting) {
				Checkbox(checked = selected, onCheckedChange = { onClick() })
			}
			CoverArt(
				coverArtId = song.coverArtId,
				modifier = Modifier.size(44.dp),
				shape = RoundedCornerShape(9.dp)
			)
			Column(
				modifier = Modifier.weight(1f),
				verticalArrangement = Arrangement.spacedBy(2.dp)
			) {
				Text(
					text = song.title,
					style = MaterialTheme.typography.titleSmall.copy(
						fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Medium
					),
					color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(4.dp)
				) {
					if (isDownloaded) {
						MeloDownloadedTinyBadge()
					}
					Text(
						text = subtitleText,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis
					)
				}
			}
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(0.dp)
			) {
				IconButton(onClick = onToggleFavorite, modifier = Modifier.size(32.dp)) {
					Icon(
						imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
						contentDescription = if (isFavorite) "取消喜欢" else "加入我喜欢",
						tint = if (isFavorite) MaterialTheme.colorScheme.meloGoldText else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
						modifier = Modifier.size(16.dp)
					)
				}
				if (showMore) {
					IconButton(onClick = onMore, modifier = Modifier.size(32.dp)) {
						Icon(
							imageVector = Icons.Outlined.MoreVert,
							contentDescription = "更多操作",
							tint = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.size(18.dp)
						)
					}
				}
			}
		}
	}
}

@Composable
private fun LocalTrackListView(
	items: List<LibraryLocalTrackItem>,
	downloadsMap: Map<String, DownloadEntity>,
	favoriteIds: Set<String>,
	currentPlayingSongId: String?,
	hostNavBottom: Dp,
	emptyLabel: String,
	loading: Boolean,
	onToggleFavorite: (MeloTrack) -> Unit,
	onDownloadSong: (DomainSong) -> Unit,
	onCancelSong: (DomainSong) -> Unit,
	onDeleteOffline: (DomainSong) -> Unit,
	onRequestAddToLocalPlaylist: (List<String>) -> Unit,
	modifier: Modifier = Modifier,
	title: String? = null,
	showOfflineAll: Boolean = true,
	hasMore: Boolean = false,
	onLoadMore: (() -> Unit)? = null,
	onRemoveFromPlaylist: ((MeloTrack) -> Unit)? = null,
	removePlaylistTitle: String? = null,
	onRemoveTracksFromPlaylist: ((List<MeloTrack>) -> Unit)? = null
) {
	val player = koinInject<MediaPlayer>()
	val downloadManager = koinInject<DownloadManager>()
	val snackBarState = LocalSnackBarState.current
	val scope = rememberCoroutineScope()

	var multi by remember { mutableStateOf(false) }
	val selected = remember { mutableStateMapOf<String, Boolean>() }
	var sheetItem by remember { mutableStateOf<LibraryLocalTrackItem?>(null) }
	var pendingBatchRemoveTracks by remember { mutableStateOf<List<MeloTrack>?>(null) }

	val songs = remember(items) { items.map { it.song } }

	LaunchedEffect(items) {
		val validIds = items.mapTo(HashSet()) { it.song.id }
		val staleIds = selected.keys.filter { it !in validIds }
		staleIds.forEach { selected.remove(it) }
	}

	BackHandler(enabled = multi) {
		multi = false
		selected.clear()
	}

	Box(modifier = modifier.fillMaxSize()) {
		LazyColumn(
			modifier = Modifier.fillMaxSize(),
			contentPadding = PaddingValues(
				start = 16.dp,
				end = 16.dp,
				top = 8.dp,
				bottom = MeloBottomChromeClearance + if (multi) 132.dp else 0.dp
			),
			verticalArrangement = Arrangement.spacedBy(0.dp)
		) {
			item(key = "local-list-toolbar", contentType = "toolbar") {
				Column(
					modifier = Modifier.padding(bottom = 10.dp),
					verticalArrangement = Arrangement.spacedBy(8.dp)
				) {
					Text(
						text = (title?.let { "$it · " } ?: "") + "${items.size} 首",
						style = MaterialTheme.typography.labelLarge.tabularNums(),
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
					Row(
						modifier = Modifier.horizontalScroll(rememberScrollState()),
						horizontalArrangement = Arrangement.spacedBy(8.dp)
					) {
						LibraryDockPill(
							label = "播放全部",
							icon = Icons.Filled.Play,
							emphasized = true,
							onClick = { if (songs.isNotEmpty()) player.playNow(songs, 0) }
						)
						LibraryDockPill(
							label = "随机",
							icon = Icons.Outlined.Shuffle,
							onClick = { if (songs.isNotEmpty()) player.playNow(songs.shuffled(), 0) }
						)
						if (showOfflineAll) {
							LibraryDockPill(
								label = "离线全部",
								icon = Icons.Outlined.Download,
								onClick = {
									if (songs.isNotEmpty()) {
										scope.launch {
											queueSongsOffline(downloadManager, songs)
											snackBarState.currentSnackbarData?.dismiss()
											snackBarState.showSnackbar("已将 ${songs.size} 首歌曲加入手机离线队列（可在工坊 → 下载到手机查看）")
										}
									}
								}
							)
						}
						LibraryDockPill(
							label = if (multi) "退出" else "多选",
							icon = if (multi) Icons.Outlined.Close else Icons.Outlined.Checklist,
							onClick = {
								multi = !multi
								if (!multi) selected.clear()
							}
						)
					}
				}
			}

			if (items.isEmpty()) {
				item(key = "local-list-empty", contentType = "empty") {
					if (loading) {
						Box(
							Modifier
								.fillMaxWidth()
								.padding(vertical = 40.dp),
							contentAlignment = Alignment.Center
						) {
							CircularProgressIndicator()
						}
					} else {
						MeloQuietEmptyState(
							message = emptyLabel,
							icon = Icons.Outlined.LibraryMusic
						)
					}
				}
			} else {
				itemsIndexed(
					items = items,
					key = { idx, item -> "local-track-$idx-${item.song.id}" },
					contentType = { _, _ -> "track" }
				) { idx, item ->
					val isSelected = selected[item.song.id] == true
					val isFirst = idx == 0
					val isLast = idx == items.lastIndex
					Surface(
						modifier = Modifier.fillMaxWidth(),
						shape = libraryGroupedItemShape(isFirst, isLast),
						color = when {
							multi && isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
							currentPlayingSongId == item.song.id -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
							else -> MaterialTheme.colorScheme.surfaceContainer
						}
					) {
						Column {
							LocalTrackRowContent(
								item = item,
								download = downloadsMap[item.song.id],
								isPlaying = currentPlayingSongId == item.song.id,
								isFavorite = item.track.id in favoriteIds,
								selecting = multi,
								selected = isSelected,
								onClick = {
									if (multi) {
										selected[item.song.id] = !isSelected
									} else {
										player.playNow(songs, idx)
									}
								},
								onLongClick = {
									multi = true
									selected[item.song.id] = true
								},
								onToggleFavorite = { onToggleFavorite(item.track) },
								onDownload = { onDownloadSong(item.song) },
								onCancelDownload = { onCancelSong(item.song) },
								onDeleteOffline = { onDeleteOffline(item.song) },
								onMore = { sheetItem = item }
							)
							if (!isLast) {
								MeloHairlineDivider(startIndent = if (multi) 104.dp else 64.dp)
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

		AnimatedVisibility(
			visible = multi,
			enter = slideInVertically { it } + fadeIn(),
			exit = slideOutVertically { it } + fadeOut(),
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.navigationBarsPadding()
				.padding(bottom = rememberBatchDockBottomClearance())
				.padding(horizontal = 16.dp)
		) {
			val selectedItems by remember(items) {
				derivedStateOf { items.filter { selected[it.song.id] == true } }
			}
			val hasOfflineRemovable = remember(selectedItems, downloadsMap) {
				selectedItems.any {
					val st = downloadsMap[it.song.id]?.status
					st == DownloadStatus.DOWNLOADED || st == DownloadStatus.DOWNLOADING
				}
			}
			StudioBatchSelectionDock(
				selectedCount = selectedItems.size,
				totalCount = items.size,
				hasOfflineRemovable = hasOfflineRemovable,
				onSelectAll = {
					if (selectedItems.size == items.size && items.isNotEmpty()) {
						selected.clear()
					} else {
						items.forEach { selected[it.song.id] = true }
					}
				},
				onClear = { selected.clear() },
				onExit = {
					multi = false
					selected.clear()
				},
				onDownloadSelected = {
					val picked = selectedItems.map { it.song }
					if (picked.isNotEmpty()) {
						scope.launch {
							queueSongsOffline(downloadManager, picked)
							snackBarState.currentSnackbarData?.dismiss()
							snackBarState.showSnackbar("已将 ${picked.size} 首歌曲加入手机离线队列（可在工坊 → 下载到手机查看）")
						}
						multi = false
						selected.clear()
					}
				},
				onAddToPlaylist = {
					val ids = selectedItems.map { it.track.id }
					if (ids.isNotEmpty()) onRequestAddToLocalPlaylist(ids)
				},
				onPlaySelected = {
					val picked = selectedItems.map { it.song }
					if (picked.isNotEmpty()) {
						player.playNow(picked, 0)
						multi = false
						selected.clear()
					}
				},
				onDeleteOfflineSelected = {
					removeSongsOffline(downloadManager, downloadsMap, selectedItems.map { it.song })
					multi = false
					selected.clear()
				},
				onRemoveFromPlaylistSelected = onRemoveTracksFromPlaylist?.let {
					{
						val picked = selectedItems.map { item -> item.track }
						if (picked.isNotEmpty()) {
							pendingBatchRemoveTracks = picked
						}
					}
				}
			)
		}
	}

	pendingBatchRemoveTracks?.let { targets ->
		val playlistLabel = removePlaylistTitle?.takeIf { it.isNotBlank() }?.let { "「$it」" } ?: "当前歌单"
		AlertDialog(
			onDismissRequest = { pendingBatchRemoveTracks = null },
			icon = {
				Icon(
					imageVector = Icons.Outlined.PlaylistRemove,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.error
				)
			},
			title = { Text("移出歌单") },
			text = {
				Text("确认从$playlistLabel 移除选中的 ${targets.size} 首歌曲吗？仅从歌单移除，不会删除音乐文件。")
			},
			confirmButton = {
				Button(
					onClick = {
						pendingBatchRemoveTracks = null
						multi = false
						selected.clear()
						onRemoveTracksFromPlaylist?.invoke(targets)
					},
					colors = ButtonDefaults.buttonColors(
						containerColor = MaterialTheme.colorScheme.error,
						contentColor = MaterialTheme.colorScheme.onError
					)
				) {
					Text("确认移除")
				}
			},
			dismissButton = {
				TextButton(onClick = { pendingBatchRemoveTracks = null }) {
					Text("取消")
				}
			}
		)
	}

	sheetItem?.let { item ->
		val st = downloadsMap[item.song.id]?.status
		LibraryLocalTrackActionSheet(
			item = item,
			downloadStatus = st,
			isFavorite = item.track.id in favoriteIds,
			hostNavBottom = hostNavBottom,
			onDismiss = { sheetItem = null },
			onPlayNow = {
				player.playNow(item.song)
				sheetItem = null
			},
			onPlayNext = {
				player.playNextSingle(item.song)
				sheetItem = null
			},
			onAddToQueue = {
				player.addToQueueSingle(item.song)
				sheetItem = null
			},
			onToggleFavorite = {
				onToggleFavorite(item.track)
				sheetItem = null
			},
			onAddToPlaylist = {
				onRequestAddToLocalPlaylist(listOf(item.track.id))
				sheetItem = null
			},
			onDownload = {
				onDownloadSong(item.song)
				sheetItem = null
			},
			onCancelDownload = {
				onCancelSong(item.song)
				sheetItem = null
			},
			onDeleteOffline = {
				onDeleteOffline(item.song)
				sheetItem = null
			},
			onRemoveFromPlaylist = onRemoveFromPlaylist?.let { remove ->
				{
					remove(item.track)
					sheetItem = null
				}
			}
		)
	}
}

// ================================
// 本地库「文件夹」：MeloKeep 目录管理
// ================================

@Composable
private fun LocalServerFilesSection(
	studioState: MeloStudioUiState,
	currentPlayingSongId: String?,
	isPlaybackPaused: Boolean,
	onRefresh: () -> Unit,
	onNavigateFolder: (String?) -> Unit,
	onLongClickFolder: (LibraryFolderChildEntry) -> Unit,
	onToggleSelect: (String) -> Unit,
	onSelectAll: () -> Unit,
	onClearSelection: () -> Unit,
	onPlayFile: (MeloLibraryFile) -> Unit,
	onEditTags: (MeloLibraryFile) -> Unit,
	onLoadMore: () -> Unit,
	downloadsMap: Map<String, DownloadEntity> = emptyMap(),
	onDownloadFile: (MeloLibraryFile) -> Unit = {},
	onCancelFileDownload: (MeloLibraryFile) -> Unit = {},
	onDeleteFileOffline: (MeloLibraryFile) -> Unit = {}
) {
	val apiClient = koinInject<MeloKeepApiClient>()
	val currentClean = remember(studioState.currentFolder) {
		libraryNormalizeFolder(studioState.currentFolder)
	}
	val pathSegments = remember(currentClean) {
		if (currentClean.isEmpty()) emptyList() else currentClean.split('/').filter { it.isNotBlank() }
	}
	val childFolders = remember(studioState.libraryFolders, currentClean) {
		libraryExtractImmediateChildFolders(studioState.libraryFolders, currentClean)
	}

	PullToRefreshBox(
		modifier = Modifier.fillMaxSize(),
		finished = !studioState.filesLoading,
		onRefresh = onRefresh,
		key = listOf(studioState.filesLoading, studioState.libraryFiles.size, currentClean)
	) {
		LazyColumn(
			modifier = Modifier.fillMaxSize(),
			contentPadding = PaddingValues(
				start = 16.dp,
				end = 16.dp,
				top = 8.dp,
				bottom = if (studioState.selectedFilePaths.isNotEmpty()) {
					MeloBottomChromeClearance + 76.dp
				} else {
					MeloBottomChromeClearance
				}
			),
			verticalArrangement = Arrangement.spacedBy(0.dp)
		) {
			// 横向可点击路径面包屑
			item(key = "files-breadcrumb", contentType = "breadcrumb") {
				LazyRow(
					modifier = Modifier.padding(bottom = 10.dp),
					horizontalArrangement = Arrangement.spacedBy(6.dp),
					verticalAlignment = Alignment.CenterVertically
				) {
					item(key = "crumb-root", contentType = "crumb") {
						val isAtRoot = pathSegments.isEmpty()
						BreadcrumbPill(
							label = "根目录",
							active = isAtRoot,
							onClick = { onNavigateFolder(null) }
						)
					}
					itemsIndexed(
						items = pathSegments,
						key = { idx, seg -> "crumb-$idx-$seg" },
						contentType = { _, _ -> "crumb" }
					) { idx, segment ->
						Row(
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(6.dp)
						) {
							Text(
								text = "›",
								style = MaterialTheme.typography.labelLarge,
								color = MaterialTheme.colorScheme.onSurfaceVariant
							)
							val targetPath = remember(pathSegments, idx) {
								pathSegments.take(idx + 1).joinToString("/")
							}
							BreadcrumbPill(
								label = segment,
								active = idx == pathSegments.lastIndex,
								onClick = { onNavigateFolder(targetPath) }
							)
						}
					}
				}
			}

			// 当前层级子文件夹卡片网格
			if (childFolders.isNotEmpty()) {
				item(key = "files-subfolders-header", contentType = "section-header") {
					MeloSectionLabel(
						title = "子目录 · ${childFolders.size}",
						modifier = Modifier.padding(bottom = 8.dp)
					)
				}

				val folderRowCount = (childFolders.size + 1) / 2
				items(
					count = folderRowCount,
					key = { rowIdx -> "subfolder-row-$currentClean-${childFolders[rowIdx * 2].fullPath}" },
					contentType = { "folder-grid-row" }
				) { rowIdx ->
					val left = childFolders[rowIdx * 2]
					val right = childFolders.getOrNull(rowIdx * 2 + 1)
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(bottom = 10.dp),
						horizontalArrangement = Arrangement.spacedBy(10.dp)
					) {
						ServerFolderCard(
							entry = left,
							modifier = Modifier.weight(1f),
							onClick = { onNavigateFolder(left.fullPath) },
							onLongClick = { onLongClickFolder(left) }
						)
						if (right != null) {
							ServerFolderCard(
								entry = right,
								modifier = Modifier.weight(1f),
								onClick = { onNavigateFolder(right.fullPath) },
								onLongClick = { onLongClickFolder(right) }
							)
						} else {
							Spacer(Modifier.weight(1f))
						}
					}
				}
			}

			// 文件列表计数与全选栏
			item(key = "files-list-header", contentType = "section-header") {
				MeloSectionLabel(
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
									.padding(horizontal = 6.dp, vertical = 2.dp)
							)
						}
					} else null
				)
			}

			if (studioState.filesLoading && studioState.libraryFiles.isEmpty()) {
				item(key = "files-loading", contentType = "loading") {
					Box(
						modifier = Modifier
							.fillMaxWidth()
							.padding(vertical = 42.dp),
						contentAlignment = Alignment.Center
					) {
						CircularProgressIndicator()
					}
				}
			} else if (studioState.libraryFiles.isEmpty()) {
				item(key = "files-empty", contentType = "empty") {
					MeloQuietEmptyState(
						message = if (studioState.fileQuery.isNotBlank()) {
							"未找到匹配「${studioState.fileQuery}」的音频文件"
						} else {
							"当前目录下暂无音频文件"
						},
						icon = MeloIcons.Folder
					)
				}
			} else {
				itemsIndexed(
					items = studioState.libraryFiles,
					key = { _, it -> it.path },
					contentType = { _, _ -> "file" }
				) { idx, file ->
					val isFirst = idx == 0
					val isLast = idx == studioState.libraryFiles.lastIndex
					val expectedSongId = remember(file.path, file.track?.id) {
						val rawId = file.track?.id?.takeIf { it.isNotBlank() } ?: file.path
						if (rawId.startsWith(MeloTrackMapper.ID_PREFIX)) rawId else "${MeloTrackMapper.ID_PREFIX}$rawId"
					}
					val fileSong = remember(file, apiClient) {
						val track = file.track ?: libraryBuildFallbackMeloTrack(file, apiClient)
						MeloTrackMapper.toDomainSong(track)
					}
					val isPlayingThis = currentPlayingSongId == expectedSongId
					val checked = file.path in studioState.selectedFilePaths
					val rowShape = libraryGroupedItemShape(isFirst, isLast)
					Surface(
						modifier = Modifier.fillMaxWidth(),
						shape = rowShape,
						color = when {
							checked -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
							isPlayingThis -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
							else -> MaterialTheme.colorScheme.surfaceContainer
						}
					) {
						Column {
							ServerAudioFileRowContent(
								file = file,
								song = fileSong,
								selectionMode = studioState.selectedFilePaths.isNotEmpty(),
								rowShape = rowShape,
								checked = checked,
								isCurrentTrack = isPlayingThis,
								isActivelyPlaying = isPlayingThis && !isPlaybackPaused,
								onToggleCheck = { onToggleSelect(file.path) },
								onPlay = { onPlayFile(file) },
								onEditTags = { onEditTags(file) },
								download = downloadsMap[expectedSongId],
								onDownload = { onDownloadFile(file) },
								onCancelDownload = { onCancelFileDownload(file) },
								onDeleteOffline = { onDeleteFileOffline(file) }
							)
							if (!isLast) {
								MeloHairlineDivider(startIndent = 104.dp)
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
}

@Composable
private fun BreadcrumbPill(
	label: String,
	active: Boolean,
	onClick: () -> Unit
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
			MeloHairlineWidth,
			if (active) {
				MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
			} else {
				libraryHairlineBorderColor()
			}
		)
	) {
		Text(
			text = label,
			style = MaterialTheme.typography.labelMedium.copy(
				fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
			),
			color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
			maxLines = 1,
			softWrap = false
		)
	}
}

@Composable
private fun ServerFolderCard(
	entry: LibraryFolderChildEntry,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	modifier: Modifier = Modifier
) {
	Surface(
		modifier = modifier.combinedClickable(
			onClick = onClick,
			onLongClick = onLongClick
		),
		shape = RoundedCornerShape(14.dp),
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = libraryHairlineBorder()
	) {
		Row(
			modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
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
					text = entry.name,
					style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
				if (entry.root.isNotBlank()) {
					Text(
						text = entry.root.substringAfterLast('/').substringAfterLast('\\').ifBlank { entry.root },
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

@Composable
private fun ServerAudioFileRowContent(
	file: MeloLibraryFile,
	song: DomainSong,
	selectionMode: Boolean,
	rowShape: androidx.compose.ui.graphics.Shape,
	checked: Boolean,
	isCurrentTrack: Boolean,
	isActivelyPlaying: Boolean,
	onToggleCheck: () -> Unit,
	onPlay: () -> Unit,
	onEditTags: () -> Unit,
	download: DownloadEntity? = null,
	onDownload: () -> Unit = {},
	onCancelDownload: () -> Unit = {},
	onDeleteOffline: () -> Unit = {}
) {
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
		val size = if (file.size > 0L) file.size.toFileSize() else null
		val lockHint = if (file.lockedFields.isNotEmpty()) "已锁 ${file.lockedFields.size}" else null
		listOfNotNull(artist, album, codec, size, lockHint).joinToString(" · ").ifBlank { file.name }
	}
	val isDownloaded = download?.status == DownloadStatus.DOWNLOADED
	val rowBgColor = when {
		checked -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
		isCurrentTrack -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
		else -> MaterialTheme.colorScheme.surfaceContainer
	}

	MeloSwipeableSongRow(
		song = song,
		selectionMode = selectionMode,
		shape = rowShape,
		modifier = Modifier.fillMaxWidth()
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.background(MaterialTheme.colorScheme.surfaceContainer)
				.background(rowBgColor)
				.combinedClickable(
					onClick = onPlay,
					onLongClick = onToggleCheck
				)
				.padding(start = 4.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp)
		) {
			Checkbox(
				checked = checked,
				onCheckedChange = { onToggleCheck() }
			)

			val coverArtUrl = file.track?.cover?.trim()?.takeIf { it.isNotEmpty() }
			Box(
				modifier = Modifier
					.size(42.dp)
					.clip(RoundedCornerShape(9.dp))
					.background(MaterialTheme.colorScheme.surfaceContainerHigh),
				contentAlignment = Alignment.Center
			) {
				if (coverArtUrl != null) {
					CoverArt(
						coverArtId = coverArtUrl,
						modifier = Modifier.fillMaxSize(),
						shape = RoundedCornerShape(9.dp)
					)
				} else if (!isCurrentTrack) {
					Icon(
						imageVector = Icons.Outlined.Note,
						contentDescription = null,
						tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
						modifier = Modifier.size(18.dp)
					)
				}
				if (isCurrentTrack) {
					Box(
						modifier = Modifier
							.fillMaxSize()
							.clip(RoundedCornerShape(9.dp))
							.background(Color.Black.copy(alpha = 0.45f)),
						contentAlignment = Alignment.Center
					) {
						Icon(
							imageVector = if (isActivelyPlaying) MeloIcons.Pause else MeloIcons.PlayArrow,
							contentDescription = null,
							tint = Color.White,
							modifier = Modifier.size(16.dp)
						)
					}
				}
			}

			Column(
				modifier = Modifier.weight(1f),
				verticalArrangement = Arrangement.spacedBy(2.dp)
			) {
				Text(
					text = displayTitle,
					style = MaterialTheme.typography.titleSmall.copy(
						fontWeight = if (isCurrentTrack) FontWeight.SemiBold else FontWeight.Medium
					),
					color = if (isCurrentTrack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(4.dp)
				) {
					if (isDownloaded) {
						MeloDownloadedTinyBadge()
					}
					Text(
						text = quietMeta,
						style = MaterialTheme.typography.bodySmall.tabularNums(),
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis
					)
				}
			}

			IconButton(
				onClick = onEditTags,
				modifier = Modifier.size(32.dp)
			) {
				Icon(
					imageVector = Icons.Outlined.Edit,
					contentDescription = "编辑标签",
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.size(17.dp)
				)
			}
		}
	}
}
