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
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.agonie0v0.melokeep.shared.MeloStudioController
import io.github.agonie0v0.melokeep.shared.MeloStudioUiState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import paige.navic.R
import paige.navic.database.dao.SongDao
import paige.navic.database.entity.DownloadEntity
import paige.navic.database.entity.DownloadStatus
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalSnackBarState
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.manager.session.DownloadManager
import paige.navic.domain.manager.session.SessionManager
import paige.navic.domain.manager.session.canUserShare
import paige.navic.domain.model.DomainAlbum
import paige.navic.domain.model.DomainAlbumListType
import paige.navic.domain.model.DomainArtist
import paige.navic.domain.model.DomainPlaylist
import paige.navic.domain.model.DomainSong
import paige.navic.domain.model.DomainSongCollection
import paige.navic.domain.model.DomainSongListType
import paige.navic.domain.model.settings.ListViewMode
import paige.navic.domain.repository.SongRepository
import paige.navic.playback.MediaPlayer
import paige.navic.ui.component.common.CoverArt
import paige.navic.ui.component.dialog.DeleteDownloadTarget
import paige.navic.ui.component.dialog.DeletionDialog
import paige.navic.ui.component.dialog.DeletionTarget
import paige.navic.ui.component.dialog.QueueDuplicateDialog
import paige.navic.ui.component.dialog.ShareDialog
import paige.navic.ui.component.dialog.rememberDownloadDeletionHandler
import paige.navic.ui.component.layout.PullToRefreshBox
import paige.navic.ui.component.sheet.ArtistSheet
import paige.navic.ui.component.sheet.CollectionSheet
import paige.navic.ui.component.sheet.SortSheet
import paige.navic.ui.core.UiState
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.filled.Play
import paige.navic.ui.icons.filled.Star
import paige.navic.ui.icons.outlined.Add
import paige.navic.ui.icons.outlined.Album
import paige.navic.ui.icons.outlined.Artist
import paige.navic.ui.icons.outlined.Check
import paige.navic.ui.icons.outlined.ChevronForward
import paige.navic.ui.icons.outlined.Grid
import paige.navic.ui.icons.outlined.LibraryMusic
import paige.navic.ui.icons.outlined.List
import paige.navic.ui.icons.outlined.MoreVert
import paige.navic.ui.icons.outlined.Sort
import paige.navic.ui.icons.outlined.Star
import paige.navic.ui.navigation.PersistentViewModelStoreOwner
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screen.album.viewmodel.AlbumListViewModel
import paige.navic.ui.screen.artist.viewmodel.ArtistListViewModel
import paige.navic.ui.screen.playlist.dialog.PlaylistCreateDialog
import paige.navic.ui.screen.playlist.dialog.PlaylistUpdateDialog
import paige.navic.ui.screen.playlist.viewmodel.PlaylistListViewModel
import paige.navic.ui.screen.song.viewmodel.SongListViewModel
import paige.navic.ui.theme.MeloBottomChromeClearance
import paige.navic.ui.theme.defaultFont
import paige.navic.ui.theme.meloGoldContainer
import paige.navic.ui.theme.meloGoldText
import paige.navic.ui.theme.positive
import paige.navic.ui.theme.tabularNums
import paige.navic.ui.util.label
import paige.navic.util.toHoursMinutesSeconds

private val VINYL_DISC_COLORS = listOf(Color(0xFF1D1E24), Color(0xFF0A0B0F))
private val VINYL_GROOVE_FACTORS = floatArrayOf(0.92f, 0.80f, 0.68f, 0.56f, 0.44f)
private val VINYL_GROOVE_STROKE = Stroke(width = 1.2f)
private val VINYL_LABEL_STROKE = Stroke(width = 1.5f)
private val POSTER_SCRIM_BRUSH = Brush.verticalGradient(
	colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.42f))
)

@Composable
internal fun LibraryNavidromeStreamingSection(
	subTab: LibraryNavidromeSubTab,
	albumCardsMode: Boolean,
	songCardsMode: Boolean,
	isMeloConnected: Boolean,
	studioState: MeloStudioUiState,
	studioController: MeloStudioController,
	downloadsMap: Map<String, DownloadEntity>,
	currentPlayingPair: Pair<String?, Boolean>,
	hostNavBottom: Dp,
	onSelectSubTab: (LibraryNavidromeSubTab) -> Unit,
	onToggleAlbumCardsMode: () -> Unit,
	onToggleSongCardsMode: () -> Unit,
	onOpenDailyMix: () -> Unit = {}
) {
	val persistentStoreOwner = koinInject<PersistentViewModelStoreOwner>()
	val sessionManager = koinInject<SessionManager>()
	val player = koinInject<MediaPlayer>()
	val downloadManager = koinInject<DownloadManager>()
	val preferenceManager = koinInject<PreferenceManager>()
	val songRepository = koinInject<SongRepository>()
	val songDao = koinInject<SongDao>()
	val backStack = LocalNavStack.current
	val snackBarState = LocalSnackBarState.current
	val scope = rememberCoroutineScope()
	val downloadDeletionHandler = rememberDownloadDeletionHandler()

	// 复用现有 PersistentViewModelStoreOwner 下的 ViewModel
	val recentAlbumsVm = koinViewModel<AlbumListViewModel>(
		key = "meloUnifiedRecentAlbums",
		parameters = { parametersOf(DomainAlbumListType.Recent) },
		viewModelStoreOwner = persistentStoreOwner
	)
	val albumsVm = koinViewModel<AlbumListViewModel>(
		key = "meloUnifiedAllAlbums",
		viewModelStoreOwner = persistentStoreOwner
	)
	val playlistsVm = koinViewModel<PlaylistListViewModel>(
		key = "meloUnifiedPlaylists",
		viewModelStoreOwner = persistentStoreOwner
	)
	val artistsVm = koinViewModel<ArtistListViewModel>(
		key = "meloUnifiedArtists",
		viewModelStoreOwner = persistentStoreOwner
	)
	val songsVm = koinViewModel<SongListViewModel>(
		key = "meloUnifiedSongs",
		viewModelStoreOwner = persistentStoreOwner
	)

	val recentAlbumsState by recentAlbumsVm.uiState.collectAsStateWithLifecycle()
	val albumsState by albumsVm.uiState.collectAsStateWithLifecycle()
	val playlistsState by playlistsVm.uiState.collectAsStateWithLifecycle()
	val artistsState by artistsVm.uiState.collectAsStateWithLifecycle()
	val songsState by songsVm.uiState.collectAsStateWithLifecycle()

	val recentAlbumsPaging = recentAlbumsState.data?.items?.collectAsLazyPagingItems()
	val albumsPaging = albumsState.data?.items?.collectAsLazyPagingItems()
	val playlistsPaging = playlistsState.data?.items?.collectAsLazyPagingItems()
	val artistsPaging = artistsState.data?.items?.collectAsLazyPagingItems()
	val songsPaging = songsState.data?.items?.collectAsLazyPagingItems()

	var shareId by remember { mutableStateOf<String?>(null) }
	var playlistDialogSongIds by remember { mutableStateOf<List<String>?>(null) }
	var playlistDeletionId by remember { mutableStateOf<String?>(null) }
	var createPlaylistOpen by rememberSaveable { mutableStateOf(false) }
	var duplicateQueuePending by remember { mutableStateOf<Pair<DomainSong, Boolean>?>(null) }
	var actionSheetSong by remember { mutableStateOf<DomainSong?>(null) }
	var physicalDeleteSong by remember { mutableStateOf<DomainSong?>(null) }
	var confirmDeletingSongId by remember { mutableStateOf<String?>(null) }
	var albumSortSheetOpen by rememberSaveable { mutableStateOf(false) }
	var songSortSheetOpen by rememberSaveable { mutableStateOf(false) }

	LaunchedEffect(studioState.navidromeDeletingTrackId) {
		if (confirmDeletingSongId != null && studioState.navidromeDeletingTrackId == null) {
			confirmDeletingSongId = null
		}
	}

	val openSongMoreMenu: (DomainSong) -> Unit = { song ->
		actionSheetSong = song
	}

	when (subTab) {
		LibraryNavidromeSubTab.OVERVIEW -> {
			val isLoading = recentAlbumsState is UiState.Loading ||
				playlistsState is UiState.Loading ||
				artistsState is UiState.Loading
			PullToRefreshBox(
				modifier = Modifier.fillMaxSize(),
				finished = !isLoading,
				onRefresh = {
					recentAlbumsVm.refreshAlbums(true)
					playlistsVm.refreshPlaylists(true)
					artistsVm.refreshArtists(true)
					songsVm.refreshSongs(false)
				},
				key = listOf(recentAlbumsState, playlistsState, artistsState)
			) {
				NavidromeOverviewContent(
					recentAlbums = recentAlbumsPaging,
					playlists = playlistsPaging,
					artists = artistsPaging,
					songs = songsPaging,
					downloadsMap = downloadsMap,
					currentPlayingSongId = currentPlayingPair.first,
					onViewAllAlbums = { onSelectSubTab(LibraryNavidromeSubTab.ALBUMS) },
					onViewAllPlaylists = { onSelectSubTab(LibraryNavidromeSubTab.PLAYLISTS) },
					onViewAllArtists = { onSelectSubTab(LibraryNavidromeSubTab.ARTISTS) },
					onViewAllSongs = { onSelectSubTab(LibraryNavidromeSubTab.SONGS) },
					onAlbumClick = { album ->
						backStack.add(Screen.CollectionDetail(album.id, "library"))
					},
					onAlbumLongClick = { album ->
						recentAlbumsVm.selected = album
					},
					onPlaylistClick = { playlist ->
						backStack.add(Screen.CollectionDetail(playlist.id, "playlists"))
					},
					onPlaylistLongClick = { playlist ->
						playlistsVm.selected = playlist
					},
					onPlaylistPlay = { playlist ->
						if (playlist.songs.isNotEmpty()) {
							player.playNow(playlist)
						} else {
							backStack.add(Screen.CollectionDetail(playlist.id, "playlists"))
						}
					},
					onArtistClick = { artist ->
						backStack.add(Screen.ArtistDetail(artist.id))
					},
					onArtistLongClick = { artist ->
						artistsVm.selectArtist(artist)
					},
					onOpenDailyMix = onOpenDailyMix,
					onOpenStarred = {
						backStack.add(Screen.Starred(nested = true))
					},
					onSongClick = { song, snapshot, idx ->
						if (idx in snapshot.indices) {
							player.playNow(snapshot, idx)
						} else {
							player.playNow(song)
						}
					},
					onSongMoreClick = openSongMoreMenu
				)
			}
		}

		LibraryNavidromeSubTab.ALBUMS -> {
			val selectedSorting by albumsVm.listType.collectAsStateWithLifecycle()
			val selectedReversed by albumsVm.selectedReversed.collectAsStateWithLifecycle()
			val selectedFilters by albumsVm.selectedFilters.collectAsStateWithLifecycle()
			val isLoading = albumsState is UiState.Loading ||
				albumsPaging?.loadState?.refresh is LoadState.Loading

			PullToRefreshBox(
				modifier = Modifier.fillMaxSize(),
				finished = !isLoading,
				onRefresh = { albumsVm.refreshAlbums(true) },
				key = albumsState
			) {
				NavidromeAlbumsSubContent(
					albums = albumsPaging,
					isLoading = isLoading,
					cardsMode = albumCardsMode,
					sortingLabel = selectedSorting.label(),
					hasActiveFilter = selectedFilters.isNotEmpty(),
					onOpenSortSheet = { albumSortSheetOpen = true },
					onToggleViewMode = onToggleAlbumCardsMode,
					onAlbumClick = { album ->
						backStack.add(Screen.CollectionDetail(album.id, "albums"))
					},
					onAlbumMoreClick = { album ->
						albumsVm.selected = album
					}
				)
			}

			if (albumSortSheetOpen) {
				val entries = remember {
					persistentListOf(
						DomainAlbumListType.Recent,
						DomainAlbumListType.Newest,
						DomainAlbumListType.Frequent,
						DomainAlbumListType.AlphabeticalByName,
						DomainAlbumListType.AlphabeticalByArtist,
						DomainAlbumListType.Highest,
						DomainAlbumListType.Random,
						DomainAlbumListType.Year
					)
				}
				SortSheet(
					entries = entries,
					label = { it.label() },
					selectedSorting = selectedSorting,
					onSetSorting = { albumsVm.setListType(it) },
					selectedReversed = selectedReversed,
					onSetReversed = { albumsVm.setReversed(it) },
					selectedViewMode = if (albumCardsMode) ListViewMode.Grid else ListViewMode.List,
					onSetViewMode = { mode ->
						val nextCards = mode == ListViewMode.Grid
						if (nextCards != albumCardsMode) {
							onToggleAlbumCardsMode()
						}
					},
					selectedFilters = selectedFilters,
					onToggleFilter = { albumsVm.toggleFilter(it) },
					onDismissRequest = { albumSortSheetOpen = false }
				)
			}
		}

		LibraryNavidromeSubTab.PLAYLISTS -> {
			val isLoading = playlistsState is UiState.Loading ||
				playlistsPaging?.loadState?.refresh is LoadState.Loading
			PullToRefreshBox(
				modifier = Modifier.fillMaxSize(),
				finished = !isLoading,
				onRefresh = { playlistsVm.refreshPlaylists(true) },
				key = playlistsState
			) {
				NavidromePlaylistsSubContent(
					playlists = playlistsPaging,
					isLoading = isLoading,
					onCreatePlaylist = { createPlaylistOpen = true },
					onPlaylistClick = { playlist ->
						backStack.add(Screen.CollectionDetail(playlist.id, "playlists"))
					},
					onPlaylistMoreClick = { playlist ->
						playlistsVm.selected = playlist
					},
					onPlaylistPlay = { playlist ->
						if (playlist.songs.isNotEmpty()) {
							player.playNow(playlist)
						} else {
							backStack.add(Screen.CollectionDetail(playlist.id, "playlists"))
						}
					}
				)
			}
		}

		LibraryNavidromeSubTab.ARTISTS -> {
			val isLoading = artistsState is UiState.Loading ||
				artistsPaging?.loadState?.refresh is LoadState.Loading
			PullToRefreshBox(
				modifier = Modifier.fillMaxSize(),
				finished = !isLoading,
				onRefresh = { artistsVm.refreshArtists(true) },
				key = artistsState
			) {
				NavidromeArtistsSubContent(
					artists = artistsPaging,
					isLoading = isLoading,
					onArtistClick = { artist ->
						backStack.add(Screen.ArtistDetail(artist.id))
					},
					onArtistMoreClick = { artist ->
						artistsVm.selectArtist(artist)
					}
				)
			}
		}

		LibraryNavidromeSubTab.SONGS -> {
			val selectedSorting by songsVm.selectedSorting.collectAsStateWithLifecycle()
			val selectedReversed by songsVm.selectedReversed.collectAsStateWithLifecycle()
			val selectedFilters by songsVm.selectedFilters.collectAsStateWithLifecycle()
			val isLoading = songsState is UiState.Loading ||
				songsPaging?.loadState?.refresh is LoadState.Loading

			PullToRefreshBox(
				modifier = Modifier.fillMaxSize(),
				finished = !isLoading,
				onRefresh = { songsVm.refreshSongs(true) },
				key = songsState
			) {
				NavidromeSongsSubContent(
					songs = songsPaging,
					songsVm = songsVm,
					selectionRefreshKey = Triple(selectedSorting, selectedReversed, selectedFilters),
					isLoading = isLoading,
					cardsMode = songCardsMode,
					sortingLabel = selectedSorting.label(),
					hasActiveFilter = selectedFilters.isNotEmpty(),
					downloadsMap = downloadsMap,
					currentPlayingSongId = currentPlayingPair.first,
					onOpenSortSheet = { songSortSheetOpen = true },
					onToggleViewMode = onToggleSongCardsMode,
					onSongClick = { song, snapshot, idx ->
						if (idx in snapshot.indices) {
							player.playNow(snapshot, idx)
						} else {
							player.playNow(song)
						}
					},
					onSongMoreClick = openSongMoreMenu,
					onBatchAddToPlaylist = { ids -> playlistDialogSongIds = ids }
				)
			}

			if (songSortSheetOpen) {
				val entries = remember {
					persistentListOf(
						DomainSongListType.FrequentlyPlayed,
						DomainSongListType.Newest,
						DomainSongListType.Random,
						DomainSongListType.Rating,
						DomainSongListType.Year
					)
				}
				SortSheet(
					entries = entries,
					label = { it.label() },
					selectedSorting = selectedSorting,
					onSetSorting = { songsVm.setSorting(it) },
					selectedReversed = selectedReversed,
					onSetReversed = { songsVm.setReversed(it) },
					selectedViewMode = if (songCardsMode) ListViewMode.Grid else ListViewMode.List,
					onSetViewMode = { mode ->
						val nextCards = mode == ListViewMode.Grid
						if (nextCards != songCardsMode) {
							onToggleSongCardsMode()
						}
					},
					selectedFilters = selectedFilters,
					onToggleFilter = { songsVm.toggleFilter(it) },
					onDismissRequest = { songSortSheetOpen = false }
				)
			}
		}
	}

	recentAlbumsState.data?.selected?.let { selectedRecentAlbum ->
		CollectionSheet(
			onDismissRequest = { recentAlbumsVm.selected = null },
			collection = selectedRecentAlbum,
			onShare = { shareId = selectedRecentAlbum.id },
			onPlayNext = { player.playNext(selectedRecentAlbum as DomainSongCollection) },
			onAddToQueue = { player.addToQueue(selectedRecentAlbum as DomainSongCollection) },
			starred = selectedRecentAlbum.starredAt != null,
			onSetStarred = { recentAlbumsVm.starAlbum(it) },
			onAddAllToPlaylist = {
				playlistDialogSongIds = selectedRecentAlbum.songs.map { it.id }
			},
			onViewArtist = {
				backStack.add(Screen.ArtistDetail(selectedRecentAlbum.artistId))
			},
			rating = selectedRecentAlbum.userRating ?: 0,
			onSetRating = { recentAlbumsVm.setRating(it) }
		)
	}

	albumsState.data?.selected?.let { selectedAlbum ->
		CollectionSheet(
			onDismissRequest = { albumsVm.selected = null },
			collection = selectedAlbum,
			onShare = { shareId = selectedAlbum.id },
			onPlayNext = { player.playNext(selectedAlbum as DomainSongCollection) },
			onAddToQueue = { player.addToQueue(selectedAlbum as DomainSongCollection) },
			starred = selectedAlbum.starredAt != null,
			onSetStarred = { albumsVm.starAlbum(it) },
			onAddAllToPlaylist = {
				playlistDialogSongIds = selectedAlbum.songs.map { it.id }
			},
			onViewArtist = {
				backStack.add(Screen.ArtistDetail(selectedAlbum.artistId))
			},
			rating = selectedAlbum.userRating ?: 0,
			onSetRating = { albumsVm.setRating(it) }
		)
	}

	playlistsState.data?.selected?.let { selectedPlaylist ->
		CollectionSheet(
			onDismissRequest = { playlistsVm.clear() },
			collection = selectedPlaylist,
			onShare = { shareId = selectedPlaylist.id },
			onDelete = { playlistDeletionId = selectedPlaylist.id },
			onPlayNext = { player.playNext(selectedPlaylist as DomainSongCollection) },
			onAddToQueue = { player.addToQueue(selectedPlaylist as DomainSongCollection) },
			onAddAllToPlaylist = {
				playlistDialogSongIds = selectedPlaylist.songs.map { it.id }
			}
		)
	}

	artistsState.data?.selected?.let { selectedArtist ->
		ArtistSheet(
			onDismissRequest = { artistsVm.clearSelection() },
			artist = selectedArtist,
			onPlayNext = { artistsVm.playArtistAlbumsNext(player) },
			onAddToQueue = { artistsVm.addArtistAlbumsToQueue(player) },
			starred = selectedArtist.starredAt != null,
			onSetStarred = { artistsVm.starArtist(it) }
		)
	}

	actionSheetSong?.let { song ->
		val dl = downloadsMap[song.id]
		LibraryNavidromeSongActionSheet(
			song = song,
			downloadStatus = dl?.status ?: DownloadStatus.NOT_DOWNLOADED,
			canShare = sessionManager.canUserShare(),
			canDeletePhysicalFile = isMeloConnected && studioState.navidromeCanDelete,
			hostNavBottom = hostNavBottom,
			onDismiss = { actionSheetSong = null },
			onPlayNow = {
				player.playNow(song)
				actionSheetSong = null
			},
			onPlayNext = {
				if (player.checkDuplicate(song.id, preferenceManager)) {
					duplicateQueuePending = song to true
				} else {
					player.playNextSingle(song)
				}
				actionSheetSong = null
			},
			onAddToQueue = {
				if (player.checkDuplicate(song.id, preferenceManager)) {
					duplicateQueuePending = song to false
				} else {
					player.addToQueueSingle(song)
				}
				actionSheetSong = null
			},
			onToggleStar = { targetStar ->
				actionSheetSong = null
				scope.launch {
					runCatching {
						if (targetStar) {
							songRepository.starSong(song.id)
						} else {
							songRepository.unstarSong(song.id)
						}
					}.onSuccess {
						songsVm.refreshSongs(false)
						snackBarState.currentSnackbarData?.dismiss()
						snackBarState.showSnackbar(
							if (targetStar) "已加入我喜欢：${song.title}" else "已移出我喜欢：${song.title}"
						)
					}.onFailure { err ->
						snackBarState.currentSnackbarData?.dismiss()
						snackBarState.showSnackbar(err.message ?: "更新红心状态失败")
					}
				}
			},
			onAddToPlaylist = {
				playlistDialogSongIds = listOf(song.id)
				actionSheetSong = null
			},
			onDownload = {
				downloadManager.downloadSong(song.id)
				actionSheetSong = null
			},
			onCancelDownload = {
				downloadManager.cancelDownload(song.id)
				actionSheetSong = null
			},
			onDeleteDownload = {
				downloadDeletionHandler.requestDelete(
					DeleteDownloadTarget.Single(song.id, song.title)
				)
				actionSheetSong = null
			},
			onViewAlbum = song.albumId?.let { albumId ->
				{
					actionSheetSong = null
					backStack.add(Screen.CollectionDetail(albumId, "library"))
				}
			},
			onViewArtist = if (song.artistId.isNotBlank()) {
				{
					actionSheetSong = null
					backStack.add(Screen.ArtistDetail(song.artistId))
				}
			} else null,
			onTrackInfo = {
				actionSheetSong = null
				backStack.add(Screen.SongDetailScreen(song.id, song.coverArtId))
			},
			onShare = {
				shareId = song.id
				actionSheetSong = null
			},
			onRequestPhysicalDelete = {
				actionSheetSong = null
				confirmDeletingSongId = null
				physicalDeleteSong = song
				studioController.requestNavidromeSongDeletion(song.id)
			}
		)
	}

	physicalDeleteSong?.let { song ->
		val isMatchingPreview = studioState.navidromeDeletingTrackId == song.id || confirmDeletingSongId == song.id
		val preview = studioState.navidromeDeletePreview.takeIf { isMatchingPreview }
		val isLoadingPreview = studioState.navidromeDeletingTrackId == song.id &&
			studioState.navidromeDeletePreview == null &&
			confirmDeletingSongId != song.id
		val isDeletingConfirm = confirmDeletingSongId == song.id &&
			studioState.navidromeDeletingTrackId == song.id

		LibraryNavidromePhysicalDeleteSheet(
			song = song,
			preview = preview,
			loadingPreview = isLoadingPreview,
			deleting = isDeletingConfirm,
			hostNavBottom = hostNavBottom,
			onRetryPreview = {
				confirmDeletingSongId = null
				studioController.requestNavidromeSongDeletion(song.id)
			},
			onDismiss = {
				confirmDeletingSongId = null
				physicalDeleteSong = null
				studioController.dismissNavidromeSongDeletion()
			},
			onConfirmDelete = { rev ->
				confirmDeletingSongId = song.id
				studioController.confirmNavidromeSongDeletion(
					sourceSongId = song.id,
					revision = rev,
					onDeleted = {
						confirmDeletingSongId = null
						physicalDeleteSong = null
						scope.launch {
							runCatching { songDao.deleteSong(song.id) }
							songsVm.refreshSongs(false)
							albumsVm.refreshAlbums(false)
							recentAlbumsVm.refreshAlbums(false)
						}
					}
				)
			}
		)
	}

	playlistDialogSongIds?.let { ids ->
		PlaylistUpdateDialog(
			songIds = ids.toPersistentList(),
			onDismissRequest = { playlistDialogSongIds = null }
		)
	}

	ShareDialog(
		id = shareId,
		onDismissRequest = { shareId = null }
	)

	DeletionDialog(
		target = playlistDeletionId?.let { DeletionTarget.Playlist(it) },
		onDismissRequest = { playlistDeletionId = null },
		onRefresh = { playlistsVm.refreshPlaylists(true) }
	)

	if (createPlaylistOpen) {
		PlaylistCreateDialog(
			onDismissRequest = { createPlaylistOpen = false },
			onRefresh = { playlistsVm.refreshPlaylists(true) }
		)
	}

	QueueDuplicateDialog(
		song = duplicateQueuePending?.first,
		onDismissRequest = { duplicateQueuePending = null },
		onConfirm = duplicateQueuePending?.let { (targetSong, isPlayNext) ->
			{
				if (isPlayNext) {
					player.playNextSingle(targetSong)
				} else {
					player.addToQueueSingle(targetSong)
				}
			}
		}
	)
}

@Composable
private fun NavidromeOverviewContent(
	recentAlbums: LazyPagingItems<DomainAlbum>?,
	playlists: LazyPagingItems<DomainPlaylist>?,
	artists: LazyPagingItems<DomainArtist>?,
	songs: LazyPagingItems<DomainSong>?,
	downloadsMap: Map<String, DownloadEntity>,
	currentPlayingSongId: String?,
	onViewAllAlbums: () -> Unit,
	onViewAllPlaylists: () -> Unit,
	onViewAllArtists: () -> Unit,
	onViewAllSongs: () -> Unit,
	onAlbumClick: (DomainAlbum) -> Unit,
	onAlbumLongClick: (DomainAlbum) -> Unit,
	onPlaylistClick: (DomainPlaylist) -> Unit,
	onPlaylistLongClick: (DomainPlaylist) -> Unit,
	onPlaylistPlay: (DomainPlaylist) -> Unit,
	onArtistClick: (DomainArtist) -> Unit,
	onArtistLongClick: (DomainArtist) -> Unit,
	onOpenDailyMix: () -> Unit,
	onOpenStarred: () -> Unit,
	onSongClick: (DomainSong, List<DomainSong>, Int) -> Unit,
	onSongMoreClick: (DomainSong) -> Unit
) {
	val albumCount = recentAlbums?.itemCount ?: 0
	val downloadManager = koinInject<DownloadManager>()
	val downloadDeletionHandler = rememberDownloadDeletionHandler()
	val scope = rememberCoroutineScope()
	val playlistCount = playlists?.itemCount ?: 0
	val artistCount = artists?.itemCount ?: 0
	val songCount = songs?.itemCount ?: 0
	val playlistDisplayCount = minOf(playlistCount, 8)
	val playlistRows = (playlistDisplayCount + 1) / 2
	val songsSnapshot = remember(songs?.itemSnapshotList) {
		songs?.itemSnapshotList?.items.orEmpty()
	}

	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(
			top = 8.dp,
			bottom = MeloBottomChromeClearance
		),
		verticalArrangement = Arrangement.spacedBy(12.dp)
	) {
		// 1. 猜你喜欢 / 我喜欢 极简双列快捷卡
		item(key = "overview-smart-cards", contentType = "smart-cards") {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp),
				horizontalArrangement = Arrangement.spacedBy(10.dp)
			) {
				NavidromeQuickCard(
					modifier = Modifier.weight(1f),
					title = "猜你喜欢",
					icon = MeloIcons.Sparkles,
					accentColor = MaterialTheme.colorScheme.primary,
					onClick = onOpenDailyMix
				)
				NavidromeQuickCard(
					modifier = Modifier.weight(1f),
					title = "我喜欢",
					icon = Icons.Outlined.Star,
					accentColor = MaterialTheme.colorScheme.meloGoldText,
					onClick = onOpenStarred
				)
			}
		}

		// 2. 最近入库黑胶画廊横滑卡
		item(key = "overview-recent-vinyl-header", contentType = "section-header") {
			LibrarySectionHeader(
				title = "最近入库",
				actionLabel = if (albumCount > 0) "全部" else null,
				onActionClick = onViewAllAlbums,
				modifier = Modifier.padding(top = 6.dp)
			)
		}

		item(key = "overview-recent-vinyl-carousel", contentType = "carousel") {
			if (recentAlbums == null || albumCount == 0) {
				MeloQuietEmptyState(
					message = "暂无最近入库专辑",
					icon = Icons.Outlined.Album
				)
			} else {
				LazyRow(
					contentPadding = PaddingValues(horizontal = 16.dp),
					horizontalArrangement = Arrangement.spacedBy(14.dp)
				) {
					items(
						count = minOf(albumCount, 12),
						key = { idx -> recentAlbums.peek(idx)?.id ?: "recent-$idx" },
						contentType = { "vinyl-album" }
					) { index ->
						val album = recentAlbums[index] ?: return@items
						VinylGalleryAlbumCard(
							album = album,
							onClick = { onAlbumClick(album) },
							onLongClick = { onAlbumLongClick(album) }
						)
					}
				}
			}
		}

		// 3. 歌单海报网格
		item(key = "overview-playlists-header", contentType = "section-header") {
			LibrarySectionHeader(
				title = "歌单",
				count = if (playlistCount > 0) "$playlistCount" else null,
				actionLabel = if (playlistCount > 0) "全部" else null,
				onActionClick = onViewAllPlaylists,
				modifier = Modifier.padding(top = 6.dp)
			)
		}

		if (playlists == null || playlistCount == 0) {
			item(key = "overview-playlists-empty", contentType = "empty") {
				MeloQuietEmptyState(
					message = "暂无歌单",
					icon = Icons.Outlined.LibraryMusic
				)
			}
		} else {
			items(
				count = playlistRows,
				key = { rowIdx ->
					playlists.peek(rowIdx * 2)?.id?.let { "playlist-row-$it" } ?: "playlist-row-$rowIdx"
				},
				contentType = { "playlist-grid-row" }
			) { rowIndex ->
				val leftIdx = rowIndex * 2
				val rightIdx = leftIdx + 1
				val leftPlaylist = playlists[leftIdx]
				val rightPlaylist = if (rightIdx < playlistDisplayCount) playlists[rightIdx] else null

				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 16.dp),
					horizontalArrangement = Arrangement.spacedBy(12.dp)
				) {
					if (leftPlaylist != null) {
						PlaylistPosterCard(
							playlist = leftPlaylist,
							modifier = Modifier.weight(1f),
							onClick = { onPlaylistClick(leftPlaylist) },
							onLongClick = { onPlaylistLongClick(leftPlaylist) },
							onPlayClick = { onPlaylistPlay(leftPlaylist) }
						)
					} else {
						Spacer(Modifier.weight(1f))
					}
					if (rightPlaylist != null) {
						PlaylistPosterCard(
							playlist = rightPlaylist,
							modifier = Modifier.weight(1f),
							onClick = { onPlaylistClick(rightPlaylist) },
							onLongClick = { onPlaylistLongClick(rightPlaylist) },
							onPlayClick = { onPlaylistPlay(rightPlaylist) }
						)
					} else {
						Spacer(Modifier.weight(1f))
					}
				}
			}
		}

		// 4. 歌手横滑头像条
		if (artists != null && artistCount > 0) {
			item(key = "overview-artists-header", contentType = "section-header") {
				LibrarySectionHeader(
					title = "歌手",
					count = "$artistCount",
					actionLabel = "全部",
					onActionClick = onViewAllArtists,
					modifier = Modifier.padding(top = 6.dp)
				)
			}
			item(key = "overview-artists-row", contentType = "carousel") {
				LazyRow(
					contentPadding = PaddingValues(horizontal = 16.dp),
					horizontalArrangement = Arrangement.spacedBy(14.dp)
				) {
					items(
						count = minOf(artistCount, 12),
						key = { idx -> artists.peek(idx)?.id ?: "artist-$idx" },
						contentType = { "artist-avatar" }
					) { idx ->
						val artist = artists[idx] ?: return@items
						Column(
							modifier = Modifier
								.width(80.dp)
								.combinedClickable(
									onClick = { onArtistClick(artist) },
									onLongClick = { onArtistLongClick(artist) }
								),
							horizontalAlignment = Alignment.CenterHorizontally,
							verticalArrangement = Arrangement.spacedBy(6.dp)
						) {
							CoverArt(
								coverArtId = artist.coverArtId,
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

		// 5. 曲目速览（Apple Notes 极简分组列表）
		if (songs != null && songCount > 0) {
			item(key = "overview-songs-header", contentType = "section-header") {
				LibrarySectionHeader(
					title = "最近曲目",
					actionLabel = "全部",
					onActionClick = onViewAllSongs,
					modifier = Modifier.padding(top = 6.dp)
				)
			}
			val previewCount = minOf(songCount, 6)
			item(key = "overview-songs-group", contentType = "track-group") {
				MeloGroupedCard(modifier = Modifier.padding(horizontal = 16.dp)) {
					for (idx in 0 until previewCount) {
						val song = songs[idx] ?: continue
						NavidromeSongRowContent(
							song = song,
							download = downloadsMap[song.id],
							isPlaying = currentPlayingSongId == song.id,
							onClick = { onSongClick(song, songsSnapshot, idx) },
							onLongClick = { onSongMoreClick(song) },
							onDownload = { scope.launch { queueSongsOffline(downloadManager, listOf(song)) } },
							onCancelDownload = { downloadManager.cancelDownload(song.id) },
							onDeleteOffline = {
								downloadDeletionHandler.requestDelete(DeleteDownloadTarget.Single(song.id, song.title))
							},
							onMoreClick = { onSongMoreClick(song) }
						)
						if (idx < previewCount - 1) {
							MeloHairlineDivider(startIndent = 68.dp)
						}
					}
				}
			}
		}
	}
}

@Composable
private fun NavidromeQuickCard(
	title: String,
	icon: ImageVector,
	accentColor: Color,
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
				tint = accentColor,
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
			Icon(
				imageVector = Icons.Outlined.ChevronForward,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
				modifier = Modifier.size(16.dp)
			)
		}
	}
}

/**
 * 带黑胶唱片微露视觉细节的横滑画廊大卡。
 */
@Composable
private fun VinylGalleryAlbumCard(
	album: DomainAlbum,
	onClick: () -> Unit,
	onLongClick: () -> Unit
) {
	val primaryAccent = MaterialTheme.colorScheme.primary
	val goldAccent = MaterialTheme.colorScheme.meloGoldText
	val subtleBorder = libraryHairlineBorderColor()

	Surface(
		modifier = Modifier
			.width(196.dp)
			.combinedClickable(
				onClick = onClick,
				onLongClick = onLongClick
			),
		shape = RoundedCornerShape(16.dp),
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = BorderStroke(MeloHairlineWidth, subtleBorder)
	) {
		Column(
			modifier = Modifier.padding(11.dp),
			verticalArrangement = Arrangement.spacedBy(9.dp)
		) {
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.height(136.dp),
				contentAlignment = Alignment.CenterStart
			) {
				// 右侧微露黑胶唱片圆盘（使用 drawWithCache 缓存径向渐变与静态常量，避免滚动逐帧分配）
				Spacer(
					modifier = Modifier
						.size(122.dp)
						.align(Alignment.CenterEnd)
						.offset(x = (-2).dp)
						.drawWithCache {
							val r = size.minDimension / 2f
							val center = Offset(r, r)
							val discBrush = Brush.radialGradient(
								colors = VINYL_DISC_COLORS,
								center = center,
								radius = r
							)
							val grooveColor = Color.White.copy(alpha = 0.08f)
							val labelFillColor = goldAccent.copy(alpha = 0.85f)
							val labelRingColor = primaryAccent.copy(alpha = 0.35f)
							val holeColor = Color(0xFF0B0C10)
							onDrawBehind {
								drawCircle(
									brush = discBrush,
									radius = r,
									center = center
								)
								for (factor in VINYL_GROOVE_FACTORS) {
									drawCircle(
										color = grooveColor,
										radius = r * factor,
										center = center,
										style = VINYL_GROOVE_STROKE
									)
								}
								drawCircle(
									color = labelFillColor,
									radius = r * 0.28f,
									center = center
								)
								drawCircle(
									color = labelRingColor,
									radius = r * 0.22f,
									center = center,
									style = VINYL_LABEL_STROKE
								)
								drawCircle(
									color = holeColor,
									radius = r * 0.08f,
									center = center
								)
							}
						}
				)

				CoverArt(
					coverArtId = album.coverArtId,
					modifier = Modifier
						.size(134.dp)
						.border(MeloHairlineWidth, subtleBorder, RoundedCornerShape(12.dp)),
					shape = RoundedCornerShape(12.dp),
					shadowElevation = 4.dp
				)
			}

			Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Text(
					text = album.name ?: "未知专辑",
					style = MaterialTheme.typography.titleSmall.copy(
						fontFamily = defaultFont(grade = 100),
						fontWeight = FontWeight.SemiBold
					),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
				val sub = remember(album.id, album.artistName, album.year, album.songCount) {
					listOfNotNull(
						album.artistName?.takeIf { it.isNotBlank() },
						album.year?.toString()
					).joinToString(" · ").ifBlank { "${album.songCount} 首" }
				}
				Text(
					text = sub,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
			}
		}
	}
}

@Composable
private fun PlaylistPosterCard(
	playlist: DomainPlaylist,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	onPlayClick: () -> Unit,
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
		Column {
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.aspectRatio(1.12f)
			) {
				CoverArt(
					coverArtId = playlist.coverArtId,
					modifier = Modifier.fillMaxSize(),
					square = false,
					shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
				)
				Box(
					modifier = Modifier
						.fillMaxSize()
						.background(POSTER_SCRIM_BRUSH)
				)
				Surface(
					onClick = onPlayClick,
					shape = CircleShape,
					color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
					contentColor = MaterialTheme.colorScheme.onPrimary,
					modifier = Modifier
						.align(Alignment.BottomEnd)
						.padding(8.dp)
						.size(30.dp)
				) {
					Box(contentAlignment = Alignment.Center) {
						Icon(
							imageVector = Icons.Filled.Play,
							contentDescription = "播放歌单",
							modifier = Modifier.size(15.dp)
						)
					}
				}
			}
			Column(
				modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
				verticalArrangement = Arrangement.spacedBy(2.dp)
			) {
				Text(
					text = playlist.name ?: "未命名歌单",
					style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
				Text(
					text = pluralStringResource(R.plurals.count_songs, playlist.songCount, playlist.songCount),
					style = MaterialTheme.typography.labelSmall.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1
				)
			}
		}
	}
}

@Composable
private fun NavidromeAlbumsSubContent(
	albums: LazyPagingItems<DomainAlbum>?,
	isLoading: Boolean,
	cardsMode: Boolean,
	sortingLabel: String,
	hasActiveFilter: Boolean,
	onOpenSortSheet: () -> Unit,
	onToggleViewMode: () -> Unit,
	onAlbumClick: (DomainAlbum) -> Unit,
	onAlbumMoreClick: (DomainAlbum) -> Unit
) {
	val itemCount = albums?.itemCount ?: 0

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
		item(key = "albums-toolbar", contentType = "toolbar") {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(bottom = 10.dp),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically
			) {
				Text(
					text = if (itemCount > 0) "$itemCount 张" else "专辑",
					style = MaterialTheme.typography.labelLarge.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					softWrap = false
				)
				Row(
					horizontalArrangement = Arrangement.spacedBy(6.dp),
					verticalAlignment = Alignment.CenterVertically
				) {
					Surface(
						onClick = onOpenSortSheet,
						shape = CircleShape,
						color = if (hasActiveFilter) {
							MaterialTheme.colorScheme.primaryContainer
						} else {
							MaterialTheme.colorScheme.surfaceContainer
						},
						border = libraryHairlineBorder()
					) {
						Row(
							modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(4.dp)
						) {
							Icon(Icons.Outlined.Sort, null, modifier = Modifier.size(14.dp))
							Text(
								text = sortingLabel,
								style = MaterialTheme.typography.labelMedium,
								maxLines = 1,
								softWrap = false
							)
						}
					}
					Surface(
						onClick = onToggleViewMode,
						shape = CircleShape,
						color = MaterialTheme.colorScheme.surfaceContainer,
						border = libraryHairlineBorder(),
						modifier = Modifier.size(32.dp)
					) {
						Box(contentAlignment = Alignment.Center) {
							Icon(
								imageVector = if (cardsMode) Icons.Outlined.List else Icons.Outlined.Grid,
								contentDescription = if (cardsMode) "切换列表视图" else "切换卡片视图",
								tint = MaterialTheme.colorScheme.onSurfaceVariant,
								modifier = Modifier.size(15.dp)
							)
						}
					}
				}
			}
		}

		if (albums == null || (itemCount == 0 && isLoading)) {
			item(key = "albums-loading", contentType = "loading") {
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.padding(vertical = 48.dp),
					contentAlignment = Alignment.Center
				) {
					CircularProgressIndicator()
				}
			}
		} else if (itemCount == 0) {
			item(key = "albums-empty", contentType = "empty") {
				MeloQuietEmptyState(
					message = "暂无专辑",
					icon = Icons.Outlined.Album
				)
			}
		} else if (cardsMode) {
			val rowCount = (itemCount + 1) / 2
			items(
				count = rowCount,
				key = { rowIdx ->
					albums.peek(rowIdx * 2)?.id?.let { "album-grid-row-$it" } ?: "album-grid-row-$rowIdx"
				},
				contentType = { "album-grid-row" }
			) { rowIdx ->
				val leftIdx = rowIdx * 2
				val rightIdx = leftIdx + 1
				val left = albums[leftIdx]
				val right = if (rightIdx < itemCount) albums[rightIdx] else null
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(bottom = 12.dp),
					horizontalArrangement = Arrangement.spacedBy(12.dp)
				) {
					if (left != null) {
						AlbumCardGridTile(
							album = left,
							modifier = Modifier.weight(1f),
							onClick = { onAlbumClick(left) },
							onMoreClick = { onAlbumMoreClick(left) }
						)
					} else {
						Spacer(Modifier.weight(1f))
					}
					if (right != null) {
						AlbumCardGridTile(
							album = right,
							modifier = Modifier.weight(1f),
							onClick = { onAlbumClick(right) },
							onMoreClick = { onAlbumMoreClick(right) }
						)
					} else {
						Spacer(Modifier.weight(1f))
					}
				}
			}
		} else {
			items(
				count = itemCount,
				key = { idx -> albums.peek(idx)?.id ?: "album-list-$idx" },
				contentType = { "album-row" }
			) { idx ->
				val album = albums[idx] ?: return@items
				val isFirst = idx == 0
				val isLast = idx == itemCount - 1
				val meta = remember(album.id, album.artistName, album.year, album.songCount) {
					listOfNotNull(
						album.artistName?.takeIf { it.isNotBlank() },
						album.year?.toString(),
						"${album.songCount} 首"
					).joinToString(" · ")
				}
				Surface(
					modifier = Modifier
						.fillMaxWidth()
						.combinedClickable(
							onClick = { onAlbumClick(album) },
							onLongClick = { onAlbumMoreClick(album) }
						),
					shape = libraryGroupedItemShape(isFirst, isLast),
					color = MaterialTheme.colorScheme.surfaceContainer
				) {
					Column {
						Row(
							modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(12.dp)
						) {
							CoverArt(
								coverArtId = album.coverArtId,
								modifier = Modifier.size(48.dp),
								shape = RoundedCornerShape(10.dp)
							)
							Column(
								modifier = Modifier.weight(1f),
								verticalArrangement = Arrangement.spacedBy(2.dp)
							) {
								Text(
									text = album.name ?: "未知专辑",
									style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
									maxLines = 1,
									overflow = TextOverflow.Ellipsis
								)
								Text(
									text = meta,
									style = MaterialTheme.typography.bodySmall.tabularNums(),
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis
								)
							}
							IconButton(
								onClick = { onAlbumMoreClick(album) },
								modifier = Modifier.size(32.dp)
							) {
								Icon(
									imageVector = Icons.Outlined.MoreVert,
									contentDescription = "专辑选项",
									tint = MaterialTheme.colorScheme.onSurfaceVariant,
									modifier = Modifier.size(18.dp)
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
private fun AlbumCardGridTile(
	album: DomainAlbum,
	onClick: () -> Unit,
	onMoreClick: () -> Unit,
	modifier: Modifier = Modifier
) {
	Surface(
		modifier = modifier.combinedClickable(
			onClick = onClick,
			onLongClick = onMoreClick
		),
		shape = RoundedCornerShape(14.dp),
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = libraryHairlineBorder()
	) {
		Column(
			modifier = Modifier.padding(9.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp)
		) {
			Box(modifier = Modifier.fillMaxWidth()) {
				CoverArt(
					coverArtId = album.coverArtId,
					modifier = Modifier.fillMaxWidth(),
					shape = RoundedCornerShape(10.dp)
				)
				if (album.starredAt != null) {
					Surface(
						shape = CircleShape,
						color = MaterialTheme.colorScheme.meloGoldContainer,
						modifier = Modifier
							.align(Alignment.TopEnd)
							.padding(6.dp)
					) {
						Icon(
							imageVector = Icons.Filled.Star,
							contentDescription = null,
							tint = MaterialTheme.colorScheme.meloGoldText,
							modifier = Modifier
								.padding(4.dp)
								.size(11.dp)
						)
					}
				}
			}
			Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Text(
					text = album.name ?: "未知专辑",
					style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
				val subtitle = remember(album.id, album.artistName, album.year) {
					listOfNotNull(album.artistName, album.year?.toString()).joinToString(" · ")
				}
				Text(
					text = subtitle,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
			}
		}
	}
}

@Composable
private fun NavidromePlaylistsSubContent(
	playlists: LazyPagingItems<DomainPlaylist>?,
	isLoading: Boolean,
	onCreatePlaylist: () -> Unit,
	onPlaylistClick: (DomainPlaylist) -> Unit,
	onPlaylistMoreClick: (DomainPlaylist) -> Unit,
	onPlaylistPlay: (DomainPlaylist) -> Unit
) {
	val itemCount = playlists?.itemCount ?: 0
	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = 8.dp,
			bottom = MeloBottomChromeClearance
		),
		verticalArrangement = Arrangement.spacedBy(12.dp)
	) {
		item(key = "playlists-toolbar", contentType = "toolbar") {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically
			) {
				Text(
					text = if (itemCount > 0) "$itemCount 个歌单" else "歌单",
					style = MaterialTheme.typography.labelLarge.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
				FilledTonalButton(
					onClick = onCreatePlaylist,
					contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
					modifier = Modifier.height(32.dp)
				) {
					Icon(Icons.Outlined.Add, null, modifier = Modifier.size(14.dp))
					Spacer(Modifier.width(4.dp))
					Text("新建歌单", style = MaterialTheme.typography.labelMedium)
				}
			}
		}

		if (playlists == null || (itemCount == 0 && isLoading)) {
			item(key = "playlists-loading", contentType = "loading") {
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.padding(vertical = 48.dp),
					contentAlignment = Alignment.Center
				) {
					CircularProgressIndicator()
				}
			}
		} else if (itemCount == 0) {
			item(key = "playlists-empty", contentType = "empty") {
				MeloQuietEmptyState(
					message = "暂无歌单",
					icon = Icons.Outlined.LibraryMusic,
					actionLabel = "新建歌单",
					onAction = onCreatePlaylist
				)
			}
		} else {
			val rowCount = (itemCount + 1) / 2
			items(
				count = rowCount,
				key = { rowIdx ->
					playlists.peek(rowIdx * 2)?.id?.let { "all-playlist-row-$it" } ?: "all-playlist-row-$rowIdx"
				},
				contentType = { "playlist-grid-row" }
			) { rowIdx ->
				val leftIdx = rowIdx * 2
				val rightIdx = leftIdx + 1
				val left = playlists[leftIdx]
				val right = if (rightIdx < itemCount) playlists[rightIdx] else null
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.spacedBy(12.dp)
				) {
					if (left != null) {
						PlaylistPosterCard(
							playlist = left,
							modifier = Modifier.weight(1f),
							onClick = { onPlaylistClick(left) },
							onLongClick = { onPlaylistMoreClick(left) },
							onPlayClick = { onPlaylistPlay(left) }
						)
					} else {
						Spacer(Modifier.weight(1f))
					}
					if (right != null) {
						PlaylistPosterCard(
							playlist = right,
							modifier = Modifier.weight(1f),
							onClick = { onPlaylistClick(right) },
							onLongClick = { onPlaylistMoreClick(right) },
							onPlayClick = { onPlaylistPlay(right) }
						)
					} else {
						Spacer(Modifier.weight(1f))
					}
				}
			}
		}
	}
}

@Composable
private fun NavidromeArtistsSubContent(
	artists: LazyPagingItems<DomainArtist>?,
	isLoading: Boolean,
	onArtistClick: (DomainArtist) -> Unit,
	onArtistMoreClick: (DomainArtist) -> Unit
) {
	val itemCount = artists?.itemCount ?: 0
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
		item(key = "artists-header", contentType = "header") {
			Text(
				text = if (itemCount > 0) "$itemCount 位歌手" else "歌手",
				style = MaterialTheme.typography.labelLarge.tabularNums(),
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(bottom = 10.dp)
			)
		}

		if (artists == null || (itemCount == 0 && isLoading)) {
			item(key = "artists-loading", contentType = "loading") {
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.padding(vertical = 48.dp),
					contentAlignment = Alignment.Center
				) {
					CircularProgressIndicator()
				}
			}
		} else if (itemCount == 0) {
			item(key = "artists-empty", contentType = "empty") {
				MeloQuietEmptyState(
					message = "暂无歌手",
					icon = Icons.Outlined.Artist
				)
			}
		} else {
			items(
				count = itemCount,
				key = { idx -> artists.peek(idx)?.id ?: "artist-$idx" },
				contentType = { "artist" }
			) { idx ->
				val artist = artists[idx] ?: return@items
				val isFirst = idx == 0
				val isLast = idx == itemCount - 1
				Surface(
					modifier = Modifier
						.fillMaxWidth()
						.combinedClickable(
							onClick = { onArtistClick(artist) },
							onLongClick = { onArtistMoreClick(artist) }
						),
					shape = libraryGroupedItemShape(isFirst, isLast),
					color = MaterialTheme.colorScheme.surfaceContainer
				) {
					Column {
						Row(
							modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(12.dp)
						) {
							CoverArt(
								coverArtId = artist.coverArtId,
								modifier = Modifier.size(46.dp),
								shape = CircleShape
							)
							Column(
								modifier = Modifier.weight(1f),
								verticalArrangement = Arrangement.spacedBy(2.dp)
							) {
								Text(
									text = artist.name,
									style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
									maxLines = 1,
									overflow = TextOverflow.Ellipsis
								)
								Text(
									text = pluralStringResource(R.plurals.count_albums, artist.albumCount, artist.albumCount),
									style = MaterialTheme.typography.bodySmall.tabularNums(),
									color = MaterialTheme.colorScheme.onSurfaceVariant
								)
							}
							IconButton(
								onClick = { onArtistMoreClick(artist) },
								modifier = Modifier.size(32.dp)
							) {
								Icon(
									imageVector = Icons.Outlined.MoreVert,
									contentDescription = "歌手选项",
									tint = MaterialTheme.colorScheme.onSurfaceVariant,
									modifier = Modifier.size(18.dp)
								)
							}
						}
						if (!isLast) {
							MeloHairlineDivider(startIndent = 70.dp)
						}
					}
				}
			}
		}
	}
}

@Composable
private fun NavidromeSongsSubContent(
	songs: LazyPagingItems<DomainSong>?,
	songsVm: SongListViewModel,
	selectionRefreshKey: Any,
	isLoading: Boolean,
	cardsMode: Boolean,
	sortingLabel: String,
	hasActiveFilter: Boolean,
	downloadsMap: Map<String, DownloadEntity>,
	currentPlayingSongId: String?,
	onOpenSortSheet: () -> Unit,
	onToggleViewMode: () -> Unit,
	onSongClick: (DomainSong, List<DomainSong>, Int) -> Unit,
	onSongMoreClick: (DomainSong) -> Unit,
	onBatchAddToPlaylist: (List<String>) -> Unit = {}
) {
	val itemCount = songs?.itemCount ?: 0
	val snapshot = remember(songs?.itemSnapshotList) {
		songs?.itemSnapshotList?.items.orEmpty()
	}
	val downloadManager = koinInject<DownloadManager>()
	val player = koinInject<MediaPlayer>()
	val downloadDeletionHandler = rememberDownloadDeletionHandler()
	val snackBarState = LocalSnackBarState.current
	val scope = rememberCoroutineScope()

	var isMultiSelecting by rememberSaveable { mutableStateOf(false) }
	val selectedSongIds = remember { mutableStateMapOf<String, Boolean>() }
	var allSongIds by remember { mutableStateOf<List<String>>(emptyList()) }
	var isLoadingSelectionIds by remember { mutableStateOf(false) }

	// 全库无截断多选索引加载（严禁截断）
	LaunchedEffect(isMultiSelecting, selectionRefreshKey, itemCount) {
		if (!isMultiSelecting) {
			allSongIds = emptyList()
			isLoadingSelectionIds = false
			return@LaunchedEffect
		}
		isLoadingSelectionIds = true
		try {
			val ids = withContext(Dispatchers.IO) { songsVm.getSelectionOrder() }
			allSongIds = ids
			if (ids.isNotEmpty() && selectedSongIds.isNotEmpty()) {
				val validIds = ids.toHashSet()
				val staleIds = selectedSongIds.keys.filter { it !in validIds }
				staleIds.forEach { selectedSongIds.remove(it) }
			}
		} finally {
			isLoadingSelectionIds = false
		}
	}

	BackHandler(enabled = isMultiSelecting) {
		isMultiSelecting = false
		selectedSongIds.clear()
	}

	Box(modifier = Modifier.fillMaxSize()) {
		LazyColumn(
			modifier = Modifier.fillMaxSize(),
			contentPadding = PaddingValues(
				start = 16.dp,
				end = 16.dp,
				top = 8.dp,
				bottom = MeloBottomChromeClearance + if (isMultiSelecting) 132.dp else 0.dp
			),
			verticalArrangement = Arrangement.spacedBy(0.dp)
		) {
			item(key = "songs-toolbar", contentType = "toolbar") {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(bottom = 10.dp),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically
				) {
					Text(
						text = when {
							isMultiSelecting && isLoadingSelectionIds -> "加载中…"
							isMultiSelecting && allSongIds.isNotEmpty() -> "${allSongIds.size} 首"
							itemCount > 0 -> "$itemCount 首"
							else -> "全部歌曲"
						},
						style = MaterialTheme.typography.labelLarge.tabularNums(),
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						softWrap = false
					)
					Row(
						horizontalArrangement = Arrangement.spacedBy(6.dp),
						verticalAlignment = Alignment.CenterVertically
					) {
						Surface(
							onClick = {
								isMultiSelecting = !isMultiSelecting
								if (!isMultiSelecting) selectedSongIds.clear()
							},
							shape = CircleShape,
							color = if (isMultiSelecting) {
								MaterialTheme.colorScheme.primaryContainer
							} else {
								MaterialTheme.colorScheme.surfaceContainer
							},
							border = libraryHairlineBorder()
						) {
							Text(
								text = if (isMultiSelecting) "退出" else "多选",
								style = MaterialTheme.typography.labelMedium,
								color = if (isMultiSelecting) {
									MaterialTheme.colorScheme.onPrimaryContainer
								} else {
									MaterialTheme.colorScheme.onSurface
								},
								maxLines = 1,
								softWrap = false,
								modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
							)
						}
						Surface(
							onClick = onOpenSortSheet,
							shape = CircleShape,
							color = if (hasActiveFilter) {
								MaterialTheme.colorScheme.primaryContainer
							} else {
								MaterialTheme.colorScheme.surfaceContainer
							},
							border = libraryHairlineBorder()
						) {
							Row(
								modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(4.dp)
							) {
								Icon(Icons.Outlined.Sort, null, modifier = Modifier.size(14.dp))
								Text(
									text = sortingLabel,
									style = MaterialTheme.typography.labelMedium,
									maxLines = 1,
									softWrap = false
								)
							}
						}
						Surface(
							onClick = onToggleViewMode,
							shape = CircleShape,
							color = MaterialTheme.colorScheme.surfaceContainer,
							border = libraryHairlineBorder(),
							modifier = Modifier.size(32.dp)
						) {
							Box(contentAlignment = Alignment.Center) {
								Icon(
									imageVector = if (cardsMode) Icons.Outlined.List else Icons.Outlined.Grid,
									contentDescription = if (cardsMode) "切换列表视图" else "切换卡片视图",
									tint = MaterialTheme.colorScheme.onSurfaceVariant,
									modifier = Modifier.size(15.dp)
								)
							}
						}
					}
				}
			}

			if (songs == null || (itemCount == 0 && isLoading)) {
				item(key = "songs-loading", contentType = "loading") {
					Box(
						modifier = Modifier
							.fillMaxWidth()
							.padding(vertical = 48.dp),
						contentAlignment = Alignment.Center
					) {
						CircularProgressIndicator()
					}
				}
			} else if (itemCount == 0) {
				item(key = "songs-empty", contentType = "empty") {
					MeloQuietEmptyState(
						message = "暂无曲目",
						icon = Icons.Outlined.LibraryMusic
					)
				}
			} else if (cardsMode) {
				val rowCount = (itemCount + 1) / 2
				items(
					count = rowCount,
					key = { rowIdx ->
						songs.peek(rowIdx * 2)?.id?.let { "song-grid-row-$it" } ?: "song-grid-row-$rowIdx"
					},
					contentType = { "track-grid-row" }
				) { rowIdx ->
					val leftIdx = rowIdx * 2
					val rightIdx = leftIdx + 1
					val left = songs[leftIdx]
					val right = if (rightIdx < itemCount) songs[rightIdx] else null
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(bottom = 12.dp),
						horizontalArrangement = Arrangement.spacedBy(12.dp)
					) {
						if (left != null) {
							val isLeftSelected = selectedSongIds[left.id] == true
							NavidromeSongCardTile(
								song = left,
								download = downloadsMap[left.id],
								isPlaying = currentPlayingSongId == left.id,
								isSelecting = isMultiSelecting,
								isSelected = isLeftSelected,
								modifier = Modifier.weight(1f),
								onClick = {
									if (isMultiSelecting) {
										selectedSongIds[left.id] = !isLeftSelected
									} else {
										onSongClick(left, snapshot, snapshot.indexOfFirst { it.id == left.id })
									}
								},
								onLongClick = {
									isMultiSelecting = true
									selectedSongIds[left.id] = true
								},
								onMoreClick = { onSongMoreClick(left) }
							)
						} else {
							Spacer(Modifier.weight(1f))
						}
						if (right != null) {
							val isRightSelected = selectedSongIds[right.id] == true
							NavidromeSongCardTile(
								song = right,
								download = downloadsMap[right.id],
								isPlaying = currentPlayingSongId == right.id,
								isSelecting = isMultiSelecting,
								isSelected = isRightSelected,
								modifier = Modifier.weight(1f),
								onClick = {
									if (isMultiSelecting) {
										selectedSongIds[right.id] = !isRightSelected
									} else {
										onSongClick(right, snapshot, snapshot.indexOfFirst { it.id == right.id })
									}
								},
								onLongClick = {
									isMultiSelecting = true
									selectedSongIds[right.id] = true
								},
								onMoreClick = { onSongMoreClick(right) }
							)
						} else {
							Spacer(Modifier.weight(1f))
						}
					}
				}
			} else {
				items(
					count = itemCount,
					key = { idx -> songs.peek(idx)?.id ?: "song-$idx" },
					contentType = { "track" }
				) { idx ->
					val song = songs[idx] ?: return@items
					val isSongSelected = selectedSongIds[song.id] == true
					val isFirst = idx == 0
					val isLast = idx == itemCount - 1
					Surface(
						modifier = Modifier.fillMaxWidth(),
						shape = libraryGroupedItemShape(isFirst, isLast),
						color = when {
							isMultiSelecting && isSongSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
							currentPlayingSongId == song.id -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
							else -> MaterialTheme.colorScheme.surfaceContainer
						}
					) {
						Column {
							NavidromeSongRowContent(
								song = song,
								download = downloadsMap[song.id],
								isPlaying = currentPlayingSongId == song.id,
								isSelecting = isMultiSelecting,
								isSelected = isSongSelected,
								onCheckedChange = { selectedSongIds[song.id] = it },
								onClick = {
									if (isMultiSelecting) {
										selectedSongIds[song.id] = !isSongSelected
									} else {
										onSongClick(song, snapshot, snapshot.indexOfFirst { it.id == song.id })
									}
								},
								onLongClick = {
									isMultiSelecting = true
									selectedSongIds[song.id] = true
								},
								onDownload = { scope.launch { queueSongsOffline(downloadManager, listOf(song)) } },
								onCancelDownload = { downloadManager.cancelDownload(song.id) },
								onDeleteOffline = {
									downloadDeletionHandler.requestDelete(DeleteDownloadTarget.Single(song.id, song.title))
								},
								onMoreClick = { onSongMoreClick(song) }
							)
							if (!isLast) {
								MeloHairlineDivider(startIndent = if (isMultiSelecting) 104.dp else 68.dp)
							}
						}
					}
				}
			}
		}

		// 多选 Studio 批控悬浮条
		AnimatedVisibility(
			visible = isMultiSelecting,
			enter = slideInVertically { it } + fadeIn(),
			exit = slideOutVertically { it } + fadeOut(),
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.navigationBarsPadding()
				.padding(bottom = rememberBatchDockBottomClearance())
				.padding(horizontal = 16.dp)
		) {
			val selectedOrderedIds by remember(allSongIds) {
				derivedStateOf {
					if (allSongIds.isNotEmpty()) {
						allSongIds.filter { selectedSongIds[it] == true }
					} else {
						selectedSongIds.entries.filter { it.value }.map { it.key }
					}
				}
			}
			val totalCount = if (allSongIds.isNotEmpty()) allSongIds.size else itemCount
			val hasOfflineRemovable = remember(selectedOrderedIds, downloadsMap) {
				selectedOrderedIds.any { id ->
					val st = downloadsMap[id]?.status
					st == DownloadStatus.DOWNLOADED || st == DownloadStatus.DOWNLOADING
				}
			}
			StudioBatchSelectionDock(
				selectedCount = selectedOrderedIds.size,
				totalCount = totalCount,
				hasOfflineRemovable = hasOfflineRemovable,
				onSelectAll = {
					when {
						allSongIds.isNotEmpty() && selectedOrderedIds.size >= allSongIds.size -> {
							selectedSongIds.clear()
						}
						allSongIds.isNotEmpty() -> {
							selectedSongIds.putAll(allSongIds.associateWith { true })
						}
						else -> {
							scope.launch {
								isLoadingSelectionIds = true
								try {
									val ids = withContext(Dispatchers.IO) { songsVm.getSelectionOrder() }
									allSongIds = ids
									if (ids.isNotEmpty()) {
										val allSelected = ids.all { selectedSongIds[it] == true }
										if (allSelected) {
											selectedSongIds.clear()
										} else {
											selectedSongIds.putAll(ids.associateWith { true })
										}
									}
								} finally {
									isLoadingSelectionIds = false
								}
							}
						}
					}
				},
				onClear = { selectedSongIds.clear() },
				onExit = {
					isMultiSelecting = false
					selectedSongIds.clear()
				},
				onDownloadSelected = {
					val pickedIds = selectedOrderedIds
					if (pickedIds.isNotEmpty()) {
						scope.launch {
							withContext(Dispatchers.IO) {
								downloadManager.downloadCollection(pickedIds)
							}
							snackBarState.currentSnackbarData?.dismiss()
							snackBarState.showSnackbar("已将 ${pickedIds.size} 首歌曲加入手机离线队列（可在工坊 → 下载到手机查看）")
						}
						isMultiSelecting = false
						selectedSongIds.clear()
					}
				},
				onAddToPlaylist = {
					val ids = selectedOrderedIds
					if (ids.isNotEmpty()) onBatchAddToPlaylist(ids)
				},
				onPlaySelected = {
					val pickedIds = selectedOrderedIds
					if (pickedIds.isNotEmpty()) {
						scope.launch {
							val picked = withContext(Dispatchers.IO) {
								songsVm.getSelectedSongs(pickedIds)
							}
							if (picked.isNotEmpty()) {
								player.playNow(picked, 0)
							}
						}
						isMultiSelecting = false
						selectedSongIds.clear()
					}
				},
				onDeleteOfflineSelected = {
					val pickedIds = selectedOrderedIds
					removeSongIdsOffline(downloadManager, downloadsMap, pickedIds)
					if (pickedIds.isNotEmpty()) {
						scope.launch {
							snackBarState.currentSnackbarData?.dismiss()
							snackBarState.showSnackbar("已移除所选曲目的手机离线缓存")
						}
					}
					isMultiSelecting = false
					selectedSongIds.clear()
				}
			)
		}
	}
}

@Composable
private fun NavidromeSongRowContent(
	song: DomainSong,
	download: DownloadEntity?,
	isPlaying: Boolean,
	onClick: () -> Unit,
	onMoreClick: () -> Unit,
	modifier: Modifier = Modifier,
	isSelecting: Boolean = false,
	isSelected: Boolean = false,
	onCheckedChange: ((Boolean) -> Unit)? = null,
	onLongClick: (() -> Unit)? = null,
	onDownload: () -> Unit = {},
	onCancelDownload: () -> Unit = {},
	onDeleteOffline: () -> Unit = {}
) {
	val qualityTag = remember(song.id, song.bitDepth, song.fileExtension, song.bitRate) {
		libraryFormatQuietQuality(song.bitDepth, song.fileExtension, song.bitRate)
	}
	val subtitleText = remember(song.id, song.artistName, song.albumTitle, song.duration, qualityTag) {
		val primaryMeta = listOfNotNull(
			song.artistName?.takeIf { it.isNotBlank() },
			song.albumTitle?.takeIf { it.isNotBlank() },
			qualityTag
		)
		if (primaryMeta.isNotEmpty()) {
			primaryMeta.joinToString(" · ")
		} else {
			song.duration.takeIf { it.inWholeSeconds > 0 }?.toHoursMinutesSeconds().orEmpty()
		}
	}

	Row(
		modifier = modifier
			.fillMaxWidth()
			.combinedClickable(
				onClick = onClick,
				onLongClick = onLongClick ?: onMoreClick
			)
			.padding(horizontal = 12.dp, vertical = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(11.dp)
	) {
		if (isSelecting) {
			Checkbox(
				checked = isSelected,
				onCheckedChange = onCheckedChange
			)
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
			if (subtitleText.isNotBlank()) {
				Text(
					text = subtitleText,
					style = MaterialTheme.typography.bodySmall.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
			}
		}

		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(2.dp)
		) {
			OfflineSongStatusIcon(
				status = download?.status,
				onDownloadClick = onDownload,
				onDeleteClick = onDeleteOffline,
				onCancelDownloadingClick = onCancelDownload
			)
			IconButton(
				onClick = onMoreClick,
				modifier = Modifier.size(32.dp)
			) {
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

@Composable
private fun NavidromeSongCardTile(
	song: DomainSong,
	download: DownloadEntity?,
	isPlaying: Boolean,
	onClick: () -> Unit,
	onMoreClick: () -> Unit,
	modifier: Modifier = Modifier,
	isSelecting: Boolean = false,
	isSelected: Boolean = false,
	onLongClick: (() -> Unit)? = null
) {
	val qualityTag = remember(song.id, song.bitDepth, song.fileExtension, song.bitRate) {
		libraryFormatQuietQuality(song.bitDepth, song.fileExtension, song.bitRate)
	}
	val subtitle = remember(song.id, song.artistName, qualityTag) {
		listOfNotNull(
			song.artistName?.takeIf { it.isNotBlank() } ?: "未知歌手",
			qualityTag
		).joinToString(" · ")
	}

	Surface(
		modifier = modifier.combinedClickable(
			onClick = onClick,
			onLongClick = onLongClick ?: onMoreClick
		),
		shape = RoundedCornerShape(14.dp),
		color = if (isSelecting && isSelected) {
			MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f)
		} else if (isPlaying) {
			MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
		} else {
			MaterialTheme.colorScheme.surfaceContainer
		},
		border = BorderStroke(
			MeloHairlineWidth,
			if (isSelecting && isSelected) {
				MaterialTheme.colorScheme.primary
			} else if (isPlaying) {
				MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
			} else {
				libraryHairlineBorderColor()
			}
		)
	) {
		Column(
			modifier = Modifier.padding(9.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp)
		) {
			Box(modifier = Modifier.fillMaxWidth()) {
				CoverArt(
					coverArtId = song.coverArtId,
					modifier = Modifier.fillMaxWidth(),
					shape = RoundedCornerShape(10.dp)
				)
				if (isSelecting) {
					Surface(
						shape = CircleShape,
						color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.55f),
						modifier = Modifier
							.align(Alignment.TopEnd)
							.padding(6.dp)
							.size(24.dp)
					) {
						Box(contentAlignment = Alignment.Center) {
							if (isSelected) {
								Icon(
									imageVector = Icons.Outlined.Check,
									contentDescription = "已勾选",
									tint = MaterialTheme.colorScheme.onPrimary,
									modifier = Modifier.size(14.dp)
								)
							}
						}
					}
				} else {
					Surface(
						onClick = onMoreClick,
						shape = CircleShape,
						color = Color.Black.copy(alpha = 0.48f),
						contentColor = Color.White,
						modifier = Modifier
							.align(Alignment.TopEnd)
							.padding(6.dp)
							.size(28.dp)
					) {
						Box(contentAlignment = Alignment.Center) {
							Icon(
								imageVector = Icons.Outlined.MoreVert,
								contentDescription = "更多操作",
								modifier = Modifier.size(15.dp)
							)
						}
					}
				}
				if (download?.status == DownloadStatus.DOWNLOADED) {
					Surface(
						shape = CircleShape,
						color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
						modifier = Modifier
							.align(Alignment.BottomStart)
							.padding(6.dp)
					) {
						Icon(
							imageVector = Icons.Outlined.Check,
							contentDescription = null,
							tint = MaterialTheme.colorScheme.positive,
							modifier = Modifier
								.padding(4.dp)
								.size(12.dp)
						)
					}
				}
			}
			Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Text(
					text = song.title,
					style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
				Text(
					text = subtitle,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
			}
		}
	}
}
