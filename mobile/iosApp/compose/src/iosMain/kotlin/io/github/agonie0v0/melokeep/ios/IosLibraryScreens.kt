@file:OptIn(
	ExperimentalMaterial3Api::class,
	ExperimentalLayoutApi::class,
	ExperimentalFoundationApi::class,
)

package io.github.agonie0v0.melokeep.ios

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.ViewAgenda
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.agonie0v0.melokeep.shared.MeloKeepApiClient
import io.github.agonie0v0.melokeep.shared.MeloLibraryFile
import io.github.agonie0v0.melokeep.shared.MeloLibraryFolder
import io.github.agonie0v0.melokeep.shared.MeloLocalLibraryController
import io.github.agonie0v0.melokeep.shared.MeloPlaybackInfo
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import io.github.agonie0v0.melokeep.shared.MeloStudioController
import io.github.agonie0v0.melokeep.shared.MeloStudioTab
import io.github.agonie0v0.melokeep.shared.MeloTrack
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.launch

internal enum class IosLibSourceMode(val label: String) {
	NAVIDROME("Navidrome"),
	LOCAL("Melokeep"),
	DEVICE("手机本地"),
}

internal enum class IosLibNavSubTab(val label: String) {
	OVERVIEW("概览"),
	ALBUMS("专辑"),
	PLAYLISTS("歌单"),
	ARTISTS("歌手"),
	SONGS("歌曲"),
}

internal enum class IosLibLocalSubTab(val label: String) {
	OVERVIEW("概览"),
	SONGS("歌曲"),
	ALBUMS("专辑"),
	ARTISTS("歌手"),
	PLAYLISTS("歌单"),
	FOLDERS("文件夹"),
}

internal enum class IosLibAlbumSort(val label: String) {
	RECENT("最近"),
	NEWEST("最新"),
	FREQUENT("常听"),
	NAME("名称"),
	ARTIST("歌手"),
	RATING("评分"),
	RANDOM("随机"),
	YEAR("年份"),
	GENRE("流派"),
}

internal enum class IosLibSongSort(val label: String) {
	FREQUENT("常听"),
	NEWEST("最新"),
	RANDOM("随机"),
	RATING("评分"),
	YEAR("年份"),
	NAME("名称"),
	ARTIST("歌手"),
	GENRE("流派"),
}

internal sealed interface IosLibNavDetailRoute {
	data class Album(val albumId: String, val albumTitle: String) : IosLibNavDetailRoute
	data class Artist(val artistId: String, val artistName: String) : IosLibNavDetailRoute
	data class Playlist(val playlistId: String, val playlistName: String) : IosLibNavDetailRoute
	data object Favorites : IosLibNavDetailRoute
}

internal sealed interface IosLibLocalDetailRoute {
	data object Favorites : IosLibLocalDetailRoute
	data object DailyMix : IosLibLocalDetailRoute
	data class Playlist(val id: String) : IosLibLocalDetailRoute
	data class Album(val name: String) : IosLibLocalDetailRoute
	data class Artist(val name: String) : IosLibLocalDetailRoute
}

internal data class IosLibCloudChildFolder(
	val root: String,
	val name: String,
	val fullPath: String,
)

internal data class IosLibLocalTrackEntry(
	val track: MeloTrack,
	val iosTrack: IosTrackItem,
)

internal val IosLibLosslessExtensions = setOf("flac", "wav", "alac", "ape", "dsf", "dff", "aiff", "aif", "wv")

private sealed interface IosLibPageState {
	data object Root : IosLibPageState
	data class NavDetail(val route: IosLibNavDetailRoute) : IosLibPageState
	data class LocalDetail(val route: IosLibLocalDetailRoute) : IosLibPageState
}

/**
 * 平等双库「曲库」主入口：Navidrome 库与本地库并列。
 * 等宽双库胶囊（216.dp）、无右上角离线按钮、真实全库多选与极简视觉层次。
 */
@Composable
fun IosLibraryRoute(
	subsonicManager: IosSubsonicManager,
	audioPlayer: IosAudioPlayer,
	studioController: MeloStudioController,
	settingsStore: MeloSettingsStore,
	apiClient: MeloKeepApiClient,
	onOpenSettings: () -> Unit,
	bottomPadding: PaddingValues,
	localLibraryController: MeloLocalLibraryController? = null,
	onNavigateToStudioScrape: (() -> Unit)? = null,
) {
	val scope = rememberCoroutineScope()
	val fallbackLocalController = remember(apiClient, localLibraryController, scope) {
		if (localLibraryController == null) MeloLocalLibraryController(apiClient, scope) else null
	}
	val activeLocalController = localLibraryController ?: fallbackLocalController!!

	val libState by subsonicManager.state.collectAsState()
	val playerState by audioPlayer.state.collectAsState()
	val studioState by studioController.state.collectAsState()
	val localState by activeLocalController.state.collectAsState()
	val meloConfig by settingsStore.config.collectAsState()
	val librarySearchHistory by settingsStore.librarySearchHistory.collectAsState()

	val isNavidromeConnected = libState.activeServer != null
	val isMeloConnected = meloConfig.isAuthenticated

	var sourceModeName by rememberSaveable {
		mutableStateOf(
			if (subsonicManager.state.value.activeServer == null && settingsStore.currentConfig().isAuthenticated) {
				IosLibSourceMode.LOCAL.name
			} else {
				IosLibSourceMode.NAVIDROME.name
			},
		)
	}
	val sourceMode = remember(sourceModeName) {
		IosLibSourceMode.entries.firstOrNull { it.name == sourceModeName } ?: IosLibSourceMode.NAVIDROME
	}

	var navSubTabName by rememberSaveable { mutableStateOf(IosLibNavSubTab.OVERVIEW.name) }
	val navSubTab = remember(navSubTabName) {
		IosLibNavSubTab.entries.firstOrNull { it.name == navSubTabName } ?: IosLibNavSubTab.OVERVIEW
	}

	var localSubTabName by rememberSaveable { mutableStateOf(IosLibLocalSubTab.OVERVIEW.name) }
	val localSubTab = remember(localSubTabName) {
		IosLibLocalSubTab.entries.firstOrNull { it.name == localSubTabName } ?: IosLibLocalSubTab.OVERVIEW
	}

	var navDetailRoute by remember { mutableStateOf<IosLibNavDetailRoute?>(null) }
	var localDetailRoute by remember { mutableStateOf<IosLibLocalDetailRoute?>(null) }
	var navMutationSeq by remember { mutableStateOf(0) }

	// 视图模式与排序状态
	var albumCardsMode by rememberSaveable { mutableStateOf(true) }
	var songCardsMode by rememberSaveable { mutableStateOf(false) }

	// 搜索栏开关
	var navSearchOpen by rememberSaveable { mutableStateOf(false) }
	var localSearchOpen by rememberSaveable { mutableStateOf(false) }
	var localSearchDraft by rememberSaveable { mutableStateOf(studioState.fileQuery) }
	var deviceSearchOpen by rememberSaveable { mutableStateOf(false) }
	var deviceSearchQuery by rememberSaveable { mutableStateOf("") }
	val deviceMusicState by IosDeviceMusicManager.state.collectAsState()
	var libHistoryManageMode by remember { mutableStateOf(false) }
	var showClearLibHistoryConfirm by remember { mutableStateOf(false) }

	LaunchedEffect(sourceMode) {
		if (sourceMode == IosLibSourceMode.DEVICE &&
			deviceMusicState.allTracks.isEmpty() &&
			!deviceMusicState.scanning
		) {
			IosDeviceMusicManager.refresh()
		}
	}

	LaunchedEffect(librarySearchHistory.isEmpty()) {
		if (librarySearchHistory.isEmpty()) {
			libHistoryManageMode = false
		}
	}

	// 弹窗与抽屉
	var showServerDialog by remember { mutableStateOf(false) }
	var editingServer by remember { mutableStateOf<NavidromeServerProfile?>(null) }
	var navPlaylistTargetSongs by remember { mutableStateOf<List<IosTrackItem>?>(null) }
	var showCreateNavPlaylistDialog by remember { mutableStateOf(false) }
	var showDailyMixSheet by remember { mutableStateOf(false) }

	// Navidrome 物理源文件删除
	var pendingPhysicalDeleteTrack by remember { mutableStateOf<IosTrackItem?>(null) }
	var confirmDeletingTrackRawId by remember { mutableStateOf<String?>(null) }

	// 本地库弹窗与操作
	var localPlaylistPickerIds by remember { mutableStateOf<List<String>?>(null) }
	var mkdirDialogOpen by remember { mutableStateOf(false) }
	var renameFolderTarget by remember { mutableStateOf<IosLibCloudChildFolder?>(null) }
	var editingFilePath by remember { mutableStateOf<String?>(null) }
	var moveFilesDialogOpen by remember { mutableStateOf(false) }
	var deleteFilesConfirmOpen by remember { mutableStateOf(false) }
	var inlineNotice by remember { mutableStateOf<String?>(null) }

	val folderArg = if (localSubTab == IosLibLocalSubTab.FOLDERS) studioState.currentFolder else null
	val reloadLocalFiles: () -> Unit = {
		studioController.refreshLibraryAndReload(
			query = studioState.fileQuery,
			folder = folderArg,
		) {
			activeLocalController.refreshAll()
		}
	}

	LaunchedEffect(libState.activeServer?.id, isMeloConnected) {
		if (isMeloConnected) {
			studioController.checkNavidromeCapabilities()
		}
	}

	LaunchedEffect(sourceMode, isMeloConnected) {
		if (sourceMode == IosLibSourceMode.LOCAL && isMeloConnected) {
			activeLocalController.refreshAll()
		}
	}

	LaunchedEffect(sourceMode, isMeloConnected, localSubTab) {
		if (sourceMode != IosLibSourceMode.LOCAL || !isMeloConnected) return@LaunchedEffect
		val needsRootReload = localSubTab != IosLibLocalSubTab.FOLDERS && !studioState.currentFolder.isNullOrBlank()
		if (needsRootReload || (studioState.libraryFiles.isEmpty() && !studioState.filesLoading)) {
			studioController.loadLibraryFiles(
				query = studioState.fileQuery,
				folder = if (localSubTab == IosLibLocalSubTab.FOLDERS) studioState.currentFolder else null,
				reset = true,
			)
		}
	}

	LaunchedEffect(studioState.fileQuery) {
		if (localSearchDraft != studioState.fileQuery) {
			localSearchDraft = studioState.fileQuery
		}
	}

	LaunchedEffect(studioState.navidromeDeletingTrackId) {
		if (confirmDeletingTrackRawId != null && studioState.navidromeDeletingTrackId == null) {
			confirmDeletingTrackRawId = null
		}
	}

	LaunchedEffect(studioState.noticeMessage) {
		val msg = studioState.noticeMessage?.takeIf { it.isNotBlank() }
		if (msg != null) inlineNotice = msg
	}

	LaunchedEffect(localState.noticeMessage) {
		val msg = localState.noticeMessage?.takeIf { it.isNotBlank() }
		if (msg != null) {
			inlineNotice = msg
			activeLocalController.clearNotice()
		}
	}

	val triggerPhysicalDelete: ((IosTrackItem) -> Unit)? = if (isMeloConnected && studioState.navidromeCanDelete) {
		{ track ->
			if (track.sourceType == IosTrackSource.NAVIDROME && track.rawId.isNotBlank()) {
				confirmDeletingTrackRawId = null
				pendingPhysicalDeleteTrack = track
				studioController.requestNavidromeSongDeletion(track.rawId)
			}
		}
	} else {
		null
	}

	val selectedFolderFiles = remember(studioState.libraryFiles, studioState.selectedFilePaths) {
		studioState.libraryFiles.filter { it.path in studioState.selectedFilePaths }
	}

	val recordActiveNavSearchIfNeeded: () -> Unit = {
		val q = libState.searchQuery.trim()
		if (q.isNotBlank()) {
			settingsStore.recordLibrarySearchHistory(q)
		}
	}
	val recordActiveLocalSearchIfNeeded: () -> Unit = {
		val q = (localSearchDraft.ifBlank { studioState.fileQuery }).trim()
		if (q.isNotBlank()) {
			settingsStore.recordLibrarySearchHistory(q)
		}
	}

	val activeNavDetail = if (sourceMode == IosLibSourceMode.NAVIDROME) navDetailRoute else null
	val activeLocalDetail = if (sourceMode == IosLibSourceMode.LOCAL && isMeloConnected) localDetailRoute else null
	val pageState: IosLibPageState = when {
		activeNavDetail != null -> IosLibPageState.NavDetail(activeNavDetail)
		activeLocalDetail != null -> IosLibPageState.LocalDetail(activeLocalDetail)
		else -> IosLibPageState.Root
	}

	AnimatedContent(
		targetState = pageState,
		transitionSpec = {
			if (targetState != IosLibPageState.Root && initialState == IosLibPageState.Root) {
				(slideInHorizontally(tween(180)) { it / 5 } + fadeIn(tween(180))) togetherWith
					(slideOutHorizontally(tween(180)) { -it / 6 } + fadeOut(tween(140)))
			} else if (targetState == IosLibPageState.Root && initialState != IosLibPageState.Root) {
				(slideInHorizontally(tween(180)) { -it / 5 } + fadeIn(tween(180))) togetherWith
					(slideOutHorizontally(tween(180)) { it / 5 } + fadeOut(tween(140)))
			} else {
				fadeIn(tween(160)) togetherWith fadeOut(tween(140))
			}
		},
		contentKey = { state ->
			when (state) {
				IosLibPageState.Root -> "root"
				is IosLibPageState.NavDetail -> "nav-${state.route}"
				is IosLibPageState.LocalDetail -> "local-${state.route}"
			}
		},
		label = "IosLibraryPageTransition",
	) { page ->
		when (page) {
			is IosLibPageState.NavDetail -> when (val active = page.route) {
				is IosLibNavDetailRoute.Album -> IosLibAlbumDetailScreen(
					albumId = active.albumId,
					albumTitle = active.albumTitle,
					refreshSeq = navMutationSeq,
					subsonicManager = subsonicManager,
					audioPlayer = audioPlayer,
					currentTrackId = playerState.currentTrack?.id,
					starredIds = libState.starredIds,
					unstarredIds = libState.unstarredIds,
					offlineIds = playerState.offlineTracks.keys,
					downloadingProgress = playerState.downloadingProgress,
					onOpenArtist = { id, name -> navDetailRoute = IosLibNavDetailRoute.Artist(id, name) },
					onOpenAddToPlaylist = { tracks -> navPlaylistTargetSongs = tracks },
					onRequestPhysicalDelete = triggerPhysicalDelete,
					onShowNotice = { inlineNotice = it },
					onBack = { navDetailRoute = null },
					bottomPadding = bottomPadding,
				)

				is IosLibNavDetailRoute.Artist -> IosLibArtistDetailScreen(
					artistId = active.artistId,
					artistName = active.artistName,
					subsonicManager = subsonicManager,
					audioPlayer = audioPlayer,
					currentTrackId = playerState.currentTrack?.id,
					starredIds = libState.starredIds,
					unstarredIds = libState.unstarredIds,
					offlineIds = playerState.offlineTracks.keys,
					downloadingProgress = playerState.downloadingProgress,
					onOpenAlbum = { album -> navDetailRoute = IosLibNavDetailRoute.Album(album.id, album.name) },
					onOpenArtist = { artist -> navDetailRoute = IosLibNavDetailRoute.Artist(artist.id, artist.name) },
					onOpenAddToPlaylist = { tracks -> navPlaylistTargetSongs = tracks },
					onRequestPhysicalDelete = triggerPhysicalDelete,
					onShowNotice = { inlineNotice = it },
					onBack = { navDetailRoute = null },
					bottomPadding = bottomPadding,
				)

				is IosLibNavDetailRoute.Playlist -> IosLibPlaylistDetailScreen(
					playlistId = active.playlistId,
					playlistName = active.playlistName,
					refreshSeq = navMutationSeq,
					subsonicManager = subsonicManager,
					audioPlayer = audioPlayer,
					currentTrackId = playerState.currentTrack?.id,
					starredIds = libState.starredIds,
					unstarredIds = libState.unstarredIds,
					offlineIds = playerState.offlineTracks.keys,
					downloadingProgress = playerState.downloadingProgress,
					onOpenAddToPlaylist = { tracks -> navPlaylistTargetSongs = tracks },
					onRequestPhysicalDelete = triggerPhysicalDelete,
					onShowNotice = { inlineNotice = it },
					onBack = { navDetailRoute = null },
					bottomPadding = bottomPadding,
				)

				IosLibNavDetailRoute.Favorites -> IosLibNavFavoritesScreen(
					libState = libState,
					subsonicManager = subsonicManager,
					audioPlayer = audioPlayer,
					currentTrackId = playerState.currentTrack?.id,
					offlineIds = playerState.offlineTracks.keys,
					downloadingProgress = playerState.downloadingProgress,
					onOpenAlbum = { album -> navDetailRoute = IosLibNavDetailRoute.Album(album.id, album.name) },
					onOpenArtist = { artist -> navDetailRoute = IosLibNavDetailRoute.Artist(artist.id, artist.name) },
					onOpenAddToPlaylist = { tracks -> navPlaylistTargetSongs = tracks },
					onRequestPhysicalDelete = triggerPhysicalDelete,
					onShowNotice = { inlineNotice = it },
					onBack = { navDetailRoute = null },
					bottomPadding = bottomPadding,
				)
			}

			is IosLibPageState.LocalDetail -> IosLibLocalDetailScreen(
				route = page.route,
				studioState = studioState,
				localState = localState,
				localController = activeLocalController,
				subsonicManager = subsonicManager,
				audioPlayer = audioPlayer,
				apiClient = apiClient,
				currentTrackId = playerState.currentTrack?.id,
				offlineIds = playerState.offlineTracks.keys,
				downloadingProgress = playerState.downloadingProgress,
				onRequestAddToLocalPlaylist = { localPlaylistPickerIds = it },
				onShowNotice = { inlineNotice = it },
				onBack = { localDetailRoute = null },
				bottomPadding = bottomPadding,
			)

			IosLibPageState.Root -> Box(
				modifier = Modifier
					.fillMaxSize()
					.background(MaterialTheme.colorScheme.background),
			) {
				Column(modifier = Modifier.fillMaxSize()) {
					IosLibTopHeader(
						sourceMode = sourceMode,
						onSelectSourceMode = { next ->
							if (next != sourceMode) {
								studioController.clearFileSelection()
								libHistoryManageMode = false
								sourceModeName = next.name
							}
						},
						isNavidromeConnected = isNavidromeConnected,
						hasSavedServers = libState.servers.isNotEmpty(),
						isMeloConnected = isMeloConnected,
						navSearchOpen = navSearchOpen,
						navSearchQuery = libState.searchQuery,
						onNavSearchQueryChange = { subsonicManager.searchLibrary(it) },
						onToggleNavSearch = {
							val next = !navSearchOpen
							navSearchOpen = next
							libHistoryManageMode = false
							if (!next && libState.searchQuery.isNotBlank()) {
								subsonicManager.searchLibrary("")
							}
						},
						onNavSearchSubmit = {
							val q = libState.searchQuery.trim()
							if (q.isNotBlank()) {
								subsonicManager.searchLibrary(q)
								settingsStore.recordLibrarySearchHistory(q)
							}
						},
						onSelectNavSearchHistory = { kw ->
							subsonicManager.searchLibrary(kw)
							settingsStore.recordLibrarySearchHistory(kw)
						},
						onOpenServerSheet = {
							editingServer = null
							showServerDialog = true
						},
						onOpenCreateFolder = { mkdirDialogOpen = true },
						localSearchOpen = localSearchOpen,
						localSearchInput = localSearchDraft,
						onLocalSearchInputChange = { localSearchDraft = it },
						onToggleLocalSearch = {
							val next = !localSearchOpen
							localSearchOpen = next
							libHistoryManageMode = false
							if (!next && studioState.fileQuery.isNotBlank()) {
								localSearchDraft = ""
								studioController.loadLibraryFiles(
									query = "",
									folder = folderArg,
									reset = true,
								)
							}
						},
						onLocalSearchSubmit = {
							val q = localSearchDraft.trim()
							if (q.isNotBlank()) {
								settingsStore.recordLibrarySearchHistory(q)
							}
							studioController.loadLibraryFiles(
								query = q,
								folder = folderArg,
								reset = true,
							)
						},
						onLocalSearchClear = {
							localSearchDraft = ""
							studioController.loadLibraryFiles(
								query = "",
								folder = folderArg,
								reset = true,
							)
						},
						onSelectLocalSearchHistory = { kw ->
							localSearchDraft = kw
							settingsStore.recordLibrarySearchHistory(kw)
							studioController.loadLibraryFiles(
								query = kw,
								folder = folderArg,
								reset = true,
							)
						},
						searchHistory = librarySearchHistory,
						historyManageMode = libHistoryManageMode,
						onToggleHistoryManage = { libHistoryManageMode = !libHistoryManageMode },
						onRemoveSearchHistory = { settingsStore.removeLibrarySearchHistory(it) },
						onRequestClearSearchHistory = { showClearLibHistoryConfirm = true },
						navSubTab = navSubTab,
						onSelectNavSubTab = { navSubTabName = it.name },
						localSubTab = localSubTab,
						onSelectLocalSubTab = {
							localSubTabName = it.name
							localDetailRoute = null
						},
						deviceSearchOpen = deviceSearchOpen,
						deviceSearchQuery = deviceSearchQuery,
						onDeviceSearchQueryChange = { deviceSearchQuery = it },
						onToggleDeviceSearch = {
							val next = !deviceSearchOpen
							deviceSearchOpen = next
							if (!next) deviceSearchQuery = ""
						},
						onRefreshDeviceMusic = { IosDeviceMusicManager.refresh() },
						onImportDeviceFolder = { IosDeviceMusicManager.presentFolderPicker() },
					)

					// 紧凑提示横幅
					val errorText = studioState.errorMessage?.takeIf { it.isNotBlank() }
						?: localState.errorMessage?.takeIf { it.isNotBlank() }
						?: deviceMusicState.errorMessage?.takeIf { it.isNotBlank() }
					val feedbackText = errorText
						?: inlineNotice?.takeIf { it.isNotBlank() }
						?: deviceMusicState.noticeMessage?.takeIf { it.isNotBlank() }
					val isFeedbackError = errorText != null
					if (feedbackText != null) {
						Surface(
							modifier = Modifier
								.fillMaxWidth()
								.padding(horizontal = 16.dp, vertical = 4.dp),
							shape = RoundedCornerShape(12.dp),
							color = if (isFeedbackError) {
								MaterialTheme.colorScheme.errorContainer
							} else {
								MaterialTheme.colorScheme.surfaceContainerHigh
							},
							border = BorderStroke(
								IosHairlineWidth,
								if (isFeedbackError) {
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
									text = feedbackText,
									style = MaterialTheme.typography.bodySmall,
									color = if (isFeedbackError) {
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
										.clickable {
											if (isFeedbackError) {
												studioController.clearError()
												activeLocalController.clearError()
												IosDeviceMusicManager.clearMessages()
											} else {
												inlineNotice = null
												studioController.clearNotice()
												activeLocalController.clearNotice()
												IosDeviceMusicManager.clearMessages()
											}
										},
									contentAlignment = Alignment.Center,
								) {
									Icon(
										imageVector = Icons.Rounded.Close,
										contentDescription = "关闭提示",
										modifier = Modifier.size(14.dp),
										tint = if (isFeedbackError) {
											MaterialTheme.colorScheme.onErrorContainer
										} else {
											MaterialTheme.colorScheme.onSurfaceVariant
										},
									)
								}
							}
						}
					}

					AnimatedContent(
						targetState = sourceMode,
						transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(110)) },
						modifier = Modifier.weight(1f),
						label = "IosUnifiedLibrarySourceSwitch",
					) { activeSource ->
						when (activeSource) {
							IosLibSourceMode.NAVIDROME -> {
								if (!isNavidromeConnected) {
									NavidromeUnconnectedView(
										onConfigureNavidrome = {
											editingServer = null
											showServerDialog = true
										},
										onSwitchToLocal = { sourceModeName = IosLibSourceMode.LOCAL.name },
										bottomPadding = bottomPadding,
									)
								} else {
									IosLibNavidromeSection(
										subTab = navSubTab,
										albumCardsMode = albumCardsMode,
										songCardsMode = songCardsMode,
										libState = libState,
										subsonicManager = subsonicManager,
										audioPlayer = audioPlayer,
										currentTrackId = playerState.currentTrack?.id,
										offlineIds = playerState.offlineTracks.keys,
										downloadingProgress = playerState.downloadingProgress,
										onSelectSubTab = { navSubTabName = it.name },
										onToggleAlbumCardsMode = { albumCardsMode = !albumCardsMode },
										onToggleSongCardsMode = { songCardsMode = !songCardsMode },
										onOpenDailyMix = { showDailyMixSheet = true },
										onOpenFavorites = { navDetailRoute = IosLibNavDetailRoute.Favorites },
										onOpenAlbum = { navDetailRoute = IosLibNavDetailRoute.Album(it.id, it.name) },
										onOpenArtist = { navDetailRoute = IosLibNavDetailRoute.Artist(it.id, it.name) },
										onOpenPlaylist = { navDetailRoute = IosLibNavDetailRoute.Playlist(it.id, it.name) },
										onCreatePlaylist = { showCreateNavPlaylistDialog = true },
										onOpenAddToPlaylist = { tracks -> navPlaylistTargetSongs = tracks },
										onRequestPhysicalDelete = triggerPhysicalDelete,
										onShowNotice = { inlineNotice = it },
										bottomPadding = bottomPadding,
										onRecordSearchResult = recordActiveNavSearchIfNeeded,
									)
								}
							}

							IosLibSourceMode.LOCAL -> {
								if (!isMeloConnected) {
									Box(
										modifier = Modifier
											.fillMaxSize()
											.padding(bottomPadding),
										contentAlignment = Alignment.Center,
									) {
										IosQuietEmptyState(
											message = "未连接 MeloKeep 本地服务",
											icon = Icons.Rounded.Folder,
											actionLabel = "前往配置",
											onAction = onOpenSettings,
										)
									}
								} else {
									IosLibLocalSection(
										subTab = localSubTab,
										onOpenRoute = { route ->
											recordActiveLocalSearchIfNeeded()
											localDetailRoute = route
										},
										onSelectSubTab = {
											localSubTabName = it.name
											localDetailRoute = null
										},
										studioState = studioState,
										studioController = studioController,
										localState = localState,
										localController = activeLocalController,
										subsonicManager = subsonicManager,
										audioPlayer = audioPlayer,
										apiClient = apiClient,
										currentTrackId = playerState.currentTrack?.id,
										isPlaybackPaused = !playerState.isPlaying,
										offlineIds = playerState.offlineTracks.keys,
										downloadingProgress = playerState.downloadingProgress,
										onRequestAddToLocalPlaylist = { localPlaylistPickerIds = it },
										onRenameFolder = { renameFolderTarget = it },
										onEditTags = { file ->
											recordActiveLocalSearchIfNeeded()
											editingFilePath = file.path
										},
										onRefreshFiles = reloadLocalFiles,
										onShowNotice = { inlineNotice = it },
										onRecordSearchResult = recordActiveLocalSearchIfNeeded,
										bottomPadding = bottomPadding,
									)
								}
							}

							IosLibSourceMode.DEVICE -> {
								IosLibDeviceLocalSection(
									deviceState = deviceMusicState,
									searchQuery = deviceSearchQuery,
									audioPlayer = audioPlayer,
									currentTrackId = playerState.currentTrack?.id,
									bottomPadding = bottomPadding,
								)
							}
						}
					}
				}

				// 本地库「文件夹」多选批处理悬浮坞（去刮削 / 离线 / 加歌单 / 移动 / 删除）
				androidx.compose.animation.AnimatedVisibility(
					visible = sourceMode == IosLibSourceMode.LOCAL &&
						isMeloConnected &&
						localSubTab == IosLibLocalSubTab.FOLDERS &&
						studioState.selectedFilePaths.isNotEmpty(),
					enter = fadeIn(tween(150)) + slideInVertically(tween(180)) { it / 2 },
					exit = fadeOut(tween(110)) + slideOutVertically(tween(140)) { it / 2 },
					modifier = Modifier
						.align(Alignment.BottomCenter)
						.padding(
							start = 16.dp,
							end = 16.dp,
							bottom = bottomPadding.calculateBottomPadding() + 8.dp,
						),
				) {
					IosLibLocalFolderBatchDock(
						selectedCount = studioState.selectedFilePaths.size,
						allSelected = studioState.libraryFiles.isNotEmpty() &&
							studioState.selectedFilePaths.size == studioState.libraryFiles.size,
						busy = studioState.filesLoading || studioState.jobsLoading,
						onToggleSelectAll = {
							if (studioState.selectedFilePaths.size == studioState.libraryFiles.size) {
								studioController.clearFileSelection()
							} else {
								studioController.selectAllFiles()
							}
						},
						onClear = studioController::clearFileSelection,
						onScrape = {
							val count = studioState.selectedFilePaths.size
							studioController.createScrapeJob(
								paths = studioState.selectedFilePaths.toList(),
								action = "both",
								autoApply = false,
							)
							if (onNavigateToStudioScrape != null) {
								onNavigateToStudioScrape()
							} else {
								studioController.selectTab(MeloStudioTab.LIBRARY)
								inlineNotice = "已将 $count 个文件送往工坊生成刮削预览"
							}
						},
						onOffline = {
							val tracks = selectedFolderFiles.map { file ->
								iosLibMapFileToIosTrack(file, subsonicManager, apiClient)
							}
							if (tracks.isNotEmpty()) {
								audioPlayer.downloadTracksOffline(tracks)
								inlineNotice = iosLibOfflineQueueNotice(tracks.size)
								studioController.clearFileSelection()
							}
						},
						onAddToPlaylist = {
							val ids = selectedFolderFiles.map { file ->
								(file.track ?: iosLibBuildFallbackMeloTrack(file, apiClient)).id
							}.filter { it.isNotBlank() }
							if (ids.isNotEmpty()) {
								localPlaylistPickerIds = ids
							}
						},
						onMove = { moveFilesDialogOpen = true },
						onDelete = { deleteFilesConfirmOpen = true },
					)
				}
			}
		}
	}

	// 猜你喜欢抽屉
	if (showDailyMixSheet) {
		IosDailyMixSheet(
			libState = libState,
			audioPlayer = audioPlayer,
			subsonicManager = subsonicManager,
			onDismiss = { showDailyMixSheet = false },
		)
	}

	// Navidrome 服务器多账号管理弹窗
	if (showServerDialog) {
		IosLibNavidromeServerSheet(
			servers = libState.servers,
			activeServerId = libState.activeServerId,
			initialEditing = editingServer,
			onSwitchServer = {
				subsonicManager.switchServer(it)
				showServerDialog = false
			},
			onEditServer = { srv -> editingServer = srv },
			onRemoveServer = { subsonicManager.removeServer(it) },
			onTestAndSave = { name, url, user, pass, editId ->
				subsonicManager.testAndSaveServer(name, url, user, pass, editId)
			},
			onDismiss = {
				editingServer = null
				showServerDialog = false
			},
		)
	}

	// 加入/新建 Navidrome 歌单弹窗
	navPlaylistTargetSongs?.let { targetTracks ->
		IosLibNavAddToPlaylistSheet(
			playlists = libState.playlists,
			trackCount = targetTracks.size,
			onSelectPlaylist = { pl ->
				scope.launch {
					val ids = targetTracks.filter { it.sourceType == IosTrackSource.NAVIDROME }.map { it.rawId }
					if (ids.isNotEmpty()) {
						val res = subsonicManager.addSongsToPlaylist(pl.id, ids)
						if (res.isSuccess) navPlaylistTargetSongs = null
					} else {
						navPlaylistTargetSongs = null
					}
				}
			},
			onCreateAndAdd = { newName ->
				scope.launch {
					val ids = targetTracks.filter { it.sourceType == IosTrackSource.NAVIDROME }.map { it.rawId }
					val res = subsonicManager.createPlaylist(newName, ids)
					if (res.isSuccess) navPlaylistTargetSongs = null
				}
			},
			onDismiss = { navPlaylistTargetSongs = null },
		)
	}

	// 新建 Navidrome 歌单对话框
	if (showCreateNavPlaylistDialog) {
		var nameInput by remember { mutableStateOf("") }
		AlertDialog(
			onDismissRequest = { showCreateNavPlaylistDialog = false },
			title = { Text("新建歌单") },
			text = {
				OutlinedTextField(
					value = nameInput,
					onValueChange = { nameInput = it },
					label = { Text("歌单名称") },
					singleLine = true,
					shape = RoundedCornerShape(12.dp),
					modifier = Modifier.fillMaxWidth(),
				)
			},
			confirmButton = {
				Button(
					onClick = {
						val clean = nameInput.trim()
						if (clean.isNotEmpty()) {
							scope.launch {
								subsonicManager.createPlaylist(clean, emptyList())
								showCreateNavPlaylistDialog = false
							}
						}
					},
					enabled = nameInput.trim().isNotEmpty(),
				) {
					Text("创建")
				}
			},
			dismissButton = {
				TextButton(onClick = { showCreateNavPlaylistDialog = false }) { Text("取消") }
			},
		)
	}

	// Navidrome 物理源文件安全删除确认框
	pendingPhysicalDeleteTrack?.let { deletingTrack ->
		val isMatching = studioState.navidromeDeletingTrackId == deletingTrack.rawId ||
			confirmDeletingTrackRawId == deletingTrack.rawId
		val preview = studioState.navidromeDeletePreview.takeIf { isMatching }
		val loadingPreview = studioState.navidromeDeletingTrackId == deletingTrack.rawId &&
			studioState.navidromeDeletePreview == null &&
			confirmDeletingTrackRawId != deletingTrack.rawId
		val deleting = confirmDeletingTrackRawId == deletingTrack.rawId &&
			studioState.navidromeDeletingTrackId == deletingTrack.rawId

		IosLibNavPhysicalDeleteDialog(
			track = deletingTrack,
			preview = preview,
			loadingPreview = loadingPreview,
			deleting = deleting,
			onRetryPreview = {
				confirmDeletingTrackRawId = null
				studioController.requestNavidromeSongDeletion(deletingTrack.rawId)
			},
			onDismiss = {
				confirmDeletingTrackRawId = null
				pendingPhysicalDeleteTrack = null
				studioController.dismissNavidromeSongDeletion()
			},
			onConfirmDelete = { rev ->
				confirmDeletingTrackRawId = deletingTrack.rawId
				studioController.confirmNavidromeSongDeletion(
					sourceSongId = deletingTrack.rawId,
					revision = rev,
					onDeleted = {
						confirmDeletingTrackRawId = null
						pendingPhysicalDeleteTrack = null
						navMutationSeq += 1
						subsonicManager.refreshOverview()
						inlineNotice = "已从磁盘删除《${deletingTrack.title}》"
					},
				)
			},
		)
	}

	// 本地库加入歌单选择器
	localPlaylistPickerIds?.let { ids ->
		IosLibLocalPlaylistPickerSheet(
			trackCount = ids.size,
			playlists = localState.playlists,
			onDismiss = { localPlaylistPickerIds = null },
			onPick = { playlistId ->
				activeLocalController.addTracksToPlaylist(playlistId, ids)
				localPlaylistPickerIds = null
				studioController.clearFileSelection()
			},
			onCreate = { name ->
				activeLocalController.createPlaylist(name, ids)
				localPlaylistPickerIds = null
				studioController.clearFileSelection()
			},
		)
	}

	// 新建文件夹弹窗
	if (mkdirDialogOpen) {
		val cleanParent = iosLibNormalizeFolder(studioState.currentFolder)
		val resolvedRoot = iosLibResolveRootForFolder(
			folders = studioState.libraryFolders,
			files = studioState.libraryFiles,
			folderPath = cleanParent,
		)
		IosLibCreateFolderDialog(
			parentFolder = cleanParent,
			onDismiss = { mkdirDialogOpen = false },
			onConfirm = { newName ->
				mkdirDialogOpen = false
				studioController.createSubFolder(
					root = resolvedRoot,
					parent = cleanParent,
					name = newName,
				)
			},
		)
	}

	// 重命名文件夹弹窗
	renameFolderTarget?.let { folder ->
		IosLibRenameFolderDialog(
			folder = folder,
			onDismiss = { renameFolderTarget = null },
			onConfirm = { newName ->
				renameFolderTarget = null
				studioController.renameFolder(
					root = folder.root,
					folder = folder.fullPath,
					newName = newName,
				)
			},
		)
	}

	// 17 项音频标签编辑与防覆盖锁定抽屉
	editingFilePath?.let { path ->
		val liveFile = studioState.libraryFiles.firstOrNull { it.path == path }
		if (liveFile == null) {
			editingFilePath = null
		} else {
			IosLibFileTagEditorSheet(
				file = liveFile,
				onDismiss = { editingFilePath = null },
				onSave = { changedMap ->
					if (changedMap.isNotEmpty()) {
						studioController.updateFileTags(liveFile.path, changedMap)
					}
					editingFilePath = null
				},
				onUnlockAll = {
					studioController.unlockFileTags(liveFile.path)
				},
			)
		}
	}

	// 批量移动文件抽屉
	if (moveFilesDialogOpen) {
		val currentClean = iosLibNormalizeFolder(studioState.currentFolder)
		IosLibMoveFilesSheet(
			folders = studioState.libraryFolders,
			defaultRoot = iosLibResolveRootForFolder(
				folders = studioState.libraryFolders,
				files = studioState.libraryFiles,
				folderPath = currentClean,
			),
			currentFolder = currentClean,
			selectedCount = studioState.selectedFilePaths.size,
			onDismiss = { moveFilesDialogOpen = false },
			onConfirmMove = { targetRoot, targetFolder ->
				moveFilesDialogOpen = false
				studioController.moveSelectedFiles(
					targetRoot = targetRoot,
					targetFolder = targetFolder,
				)
			},
		)
	}

	// 批量永久删除文件确认框
	if (deleteFilesConfirmOpen) {
		IosLibDeleteFilesConfirmDialog(
			selectedCount = studioState.selectedFilePaths.size,
			onDismiss = { deleteFilesConfirmOpen = false },
			onConfirm = {
				deleteFilesConfirmOpen = false
				studioController.deleteSelectedFiles()
			},
		)
	}

	if (showClearLibHistoryConfirm) {
		AlertDialog(
			onDismissRequest = { showClearLibHistoryConfirm = false },
			title = { Text("清空搜索历史") },
			text = { Text("确定清空全部 ${librarySearchHistory.size} 条曲库搜索历史吗？") },
			confirmButton = {
				TextButton(
					onClick = {
						settingsStore.clearLibrarySearchHistory()
						libHistoryManageMode = false
						showClearLibHistoryConfirm = false
					},
					colors = ButtonDefaults.textButtonColors(
						contentColor = MaterialTheme.colorScheme.error,
					),
				) {
					Text("清空")
				}
			},
			dismissButton = {
				TextButton(onClick = { showClearLibHistoryConfirm = false }) {
					Text("取消")
				}
			},
		)
	}
}

// =========================================================================
// 顶部等宽双库胶囊顶栏（216.dp 固定等宽 + 无右上角离线按钮）
// =========================================================================

@Composable
private fun IosLibTopHeader(
	sourceMode: IosLibSourceMode,
	onSelectSourceMode: (IosLibSourceMode) -> Unit,
	isNavidromeConnected: Boolean,
	hasSavedServers: Boolean,
	isMeloConnected: Boolean,
	navSearchOpen: Boolean,
	navSearchQuery: String,
	onNavSearchQueryChange: (String) -> Unit,
	onToggleNavSearch: () -> Unit,
	onNavSearchSubmit: () -> Unit,
	onSelectNavSearchHistory: (String) -> Unit,
	onOpenServerSheet: () -> Unit,
	onOpenCreateFolder: () -> Unit,
	localSearchOpen: Boolean,
	localSearchInput: String,
	onLocalSearchInputChange: (String) -> Unit,
	onToggleLocalSearch: () -> Unit,
	onLocalSearchSubmit: () -> Unit,
	onLocalSearchClear: () -> Unit,
	onSelectLocalSearchHistory: (String) -> Unit,
	searchHistory: List<String>,
	historyManageMode: Boolean,
	onToggleHistoryManage: () -> Unit,
	onRemoveSearchHistory: (String) -> Unit,
	onRequestClearSearchHistory: () -> Unit,
	navSubTab: IosLibNavSubTab,
	onSelectNavSubTab: (IosLibNavSubTab) -> Unit,
	localSubTab: IosLibLocalSubTab,
	onSelectLocalSubTab: (IosLibLocalSubTab) -> Unit,
	deviceSearchOpen: Boolean,
	deviceSearchQuery: String,
	onDeviceSearchQueryChange: (String) -> Unit,
	onToggleDeviceSearch: () -> Unit,
	onRefreshDeviceMusic: () -> Unit,
	onImportDeviceFolder: () -> Unit,
) {
	val focusManager = LocalFocusManager.current
	val localSearchable = localSubTab == IosLibLocalSubTab.FOLDERS ||
		localSubTab == IosLibLocalSubTab.SONGS ||
		localSubTab == IosLibLocalSubTab.ALBUMS ||
		localSubTab == IosLibLocalSubTab.ARTISTS

	Surface(
		color = MaterialTheme.colorScheme.background,
		modifier = Modifier.fillMaxWidth(),
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(top = 8.dp, bottom = 6.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			// 第一行：等宽 216.dp 双库切换胶囊 + 右侧静谧动作（无右上角离线按钮）
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				Surface(
					shape = CircleShape,
					color = MaterialTheme.colorScheme.surfaceContainerHigh,
					border = iosLibHairlineBorder(),
					modifier = Modifier
						.weight(1f)
						.widthIn(max = 312.dp),
				) {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(3.dp),
						horizontalArrangement = Arrangement.spacedBy(2.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						IosLibSourceMode.entries.forEach { mode ->
							val selected = sourceMode == mode
							val pillBg = if (selected) {
								MaterialTheme.colorScheme.primary
							} else {
								Color.Transparent
							}
							val pillFg = if (selected) {
								MaterialTheme.colorScheme.onPrimary
							} else {
								MaterialTheme.colorScheme.onSurfaceVariant
							}
							Box(
								modifier = Modifier
									.weight(1f)
									.clip(CircleShape)
									.background(pillBg)
									.clickable { onSelectSourceMode(mode) }
									.padding(horizontal = 4.dp, vertical = 6.dp),
								contentAlignment = Alignment.Center,
							) {
								Text(
									text = mode.label,
									style = MaterialTheme.typography.labelMedium.copy(
										fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
									),
									color = pillFg,
									textAlign = TextAlign.Center,
									maxLines = 1,
									softWrap = false,
									overflow = TextOverflow.Ellipsis,
								)
							}
						}
					}
				}

				Spacer(Modifier.width(8.dp))

				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(6.dp),
				) {
					when (sourceMode) {
						IosLibSourceMode.NAVIDROME -> {
							IosLibHeaderCircleButton(
								icon = if (hasSavedServers) Icons.Rounded.SwapHoriz else Icons.Rounded.Dns,
								contentDescription = if (hasSavedServers) "切换服务器" else "配置服务器",
								onClick = onOpenServerSheet,
							)

							if (isNavidromeConnected) {
								IosLibHeaderCircleButton(
									icon = if (navSearchOpen) Icons.Rounded.Close else Icons.Rounded.Search,
									contentDescription = "搜索 Navidrome 库",
									onClick = onToggleNavSearch,
									active = navSearchOpen,
								)
							}
						}

						IosLibSourceMode.LOCAL -> {
							if (isMeloConnected) {
								if (localSubTab == IosLibLocalSubTab.FOLDERS) {
									IosLibHeaderCircleButton(
										icon = Icons.Rounded.Add,
										contentDescription = "新建目录",
										onClick = onOpenCreateFolder,
									)
								}
								if (localSearchable) {
									IosLibHeaderCircleButton(
										icon = if (localSearchOpen) Icons.Rounded.Close else Icons.Rounded.Search,
										contentDescription = "搜索本地库",
										onClick = onToggleLocalSearch,
										active = localSearchOpen,
									)
								}
							}
						}

						IosLibSourceMode.DEVICE -> {
							IosLibHeaderCircleButton(
								icon = Icons.Rounded.Folder,
								contentDescription = "导入文件夹",
								onClick = onImportDeviceFolder,
							)
							IosLibHeaderCircleButton(
								icon = Icons.Rounded.Refresh,
								contentDescription = "刷新手机本地音乐",
								onClick = onRefreshDeviceMusic,
							)
							IosLibHeaderCircleButton(
								icon = if (deviceSearchOpen) Icons.Rounded.Close else Icons.Rounded.Search,
								contentDescription = "搜索手机本地音乐",
								onClick = onToggleDeviceSearch,
								active = deviceSearchOpen,
							)
						}
					}
				}
			}

			// 第二行：Navidrome 库子栏目
			androidx.compose.animation.AnimatedVisibility(visible = sourceMode == IosLibSourceMode.NAVIDROME && isNavidromeConnected) {
				IosLibHeaderSubTabRow(
					tabs = IosLibNavSubTab.entries,
					selected = navSubTab,
					label = { it.label },
					onSelect = onSelectNavSubTab,
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 16.dp),
				)
			}

			// 第二行：本地库子栏目
			androidx.compose.animation.AnimatedVisibility(visible = sourceMode == IosLibSourceMode.LOCAL && isMeloConnected) {
				IosLibHeaderSubTabRow(
					tabs = IosLibLocalSubTab.entries,
					selected = localSubTab,
					label = { it.label },
					onSelect = onSelectLocalSubTab,
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 16.dp),
				)
			}

			// 第三行：Navidrome 搜索栏与搜索历史
			androidx.compose.animation.AnimatedVisibility(visible = sourceMode == IosLibSourceMode.NAVIDROME && isNavidromeConnected && navSearchOpen) {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					IosLibCompactSearchBar(
						value = navSearchQuery,
						placeholder = "搜索歌曲、专辑或歌手…",
						onValueChange = onNavSearchQueryChange,
						onClear = { onNavSearchQueryChange("") },
						onSubmit = {
							focusManager.clearFocus()
							onNavSearchSubmit()
						},
						showSubmitButton = true,
					)
					if (navSearchQuery.isBlank() && searchHistory.isNotEmpty()) {
						IosLibSearchHistoryPanel(
							history = searchHistory,
							manageMode = historyManageMode,
							onToggleManageMode = onToggleHistoryManage,
							onRequestClearAll = onRequestClearSearchHistory,
							onSelectQuery = { kw ->
								focusManager.clearFocus()
								onSelectNavSearchHistory(kw)
							},
							onRemoveQuery = onRemoveSearchHistory,
						)
					}
				}
			}

			// 第三行：本地库搜索栏与搜索历史
			androidx.compose.animation.AnimatedVisibility(
				visible = sourceMode == IosLibSourceMode.LOCAL &&
					isMeloConnected &&
					localSearchOpen &&
					localSearchable,
			) {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					IosLibCompactSearchBar(
						value = localSearchInput,
						placeholder = "搜索歌名、歌手、专辑或路径…",
						onValueChange = onLocalSearchInputChange,
						onClear = onLocalSearchClear,
						onSubmit = {
							focusManager.clearFocus()
							onLocalSearchSubmit()
						},
						showSubmitButton = true,
					)
					if (localSearchInput.isBlank() && searchHistory.isNotEmpty()) {
						IosLibSearchHistoryPanel(
							history = searchHistory,
							manageMode = historyManageMode,
							onToggleManageMode = onToggleHistoryManage,
							onRequestClearAll = onRequestClearSearchHistory,
							onSelectQuery = { kw ->
								focusManager.clearFocus()
								onSelectLocalSearchHistory(kw)
							},
							onRemoveQuery = onRemoveSearchHistory,
						)
					}
				}
			}

			// 第三行：手机本地音乐搜索栏
			androidx.compose.animation.AnimatedVisibility(
				visible = sourceMode == IosLibSourceMode.DEVICE && deviceSearchOpen,
			) {
				IosLibCompactSearchBar(
					value = deviceSearchQuery,
					placeholder = "搜索手机本地歌曲、歌手或目录…",
					onValueChange = onDeviceSearchQueryChange,
					onClear = { onDeviceSearchQueryChange("") },
					onSubmit = { focusManager.clearFocus() },
					showSubmitButton = false,
				)
			}
		}
	}
}

@Composable
private fun IosLibSearchHistoryPanel(
	history: List<String>,
	manageMode: Boolean,
	onToggleManageMode: () -> Unit,
	onRequestClearAll: () -> Unit,
	onSelectQuery: (String) -> Unit,
	onRemoveQuery: (String) -> Unit,
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp),
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			Text(
				text = "最近搜索",
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Row(
				horizontalArrangement = Arrangement.spacedBy(10.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = if (manageMode) "完成" else "管理",
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.primary,
					modifier = Modifier
						.clip(CircleShape)
						.clickable(onClick = onToggleManageMode)
						.padding(horizontal = 6.dp, vertical = 2.dp),
				)
				Text(
					text = "清空",
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.error,
					modifier = Modifier
						.clip(CircleShape)
						.clickable(onClick = onRequestClearAll)
						.padding(horizontal = 6.dp, vertical = 2.dp),
				)
			}
		}
		FlowRow(
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			history.forEach { kw ->
				Surface(
					onClick = {
						if (manageMode) {
							onRemoveQuery(kw)
						} else {
							onSelectQuery(kw)
						}
					},
					shape = CircleShape,
					color = if (manageMode) {
						MaterialTheme.colorScheme.surfaceContainerHigh
					} else {
						MaterialTheme.colorScheme.surfaceContainer
					},
					border = iosLibHairlineBorder(),
				) {
					Row(
						modifier = Modifier.padding(
							start = 11.dp,
							end = if (manageMode) 8.dp else 11.dp,
							top = 5.dp,
							bottom = 5.dp,
						),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(4.dp),
					) {
						Text(
							text = kw,
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurface,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
						if (manageMode) {
							Icon(
								imageVector = Icons.Rounded.Close,
								contentDescription = "删除此历史",
								tint = MaterialTheme.colorScheme.onSurfaceVariant,
								modifier = Modifier.size(13.dp),
							)
						}
					}
				}
			}
		}
	}
}

@Composable
private fun <T> IosLibHeaderSubTabRow(
	tabs: List<T>,
	selected: T,
	label: (T) -> String,
	onSelect: (T) -> Unit,
	modifier: Modifier = Modifier,
) {
	LazyRow(
		modifier = modifier,
		horizontalArrangement = Arrangement.spacedBy(6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		items(
			items = tabs,
			key = { label(it) },
			contentType = { "subtab" },
		) { tab ->
			val isSelected = tab == selected
			Surface(
				shape = CircleShape,
				color = if (isSelected) {
					MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
				} else {
					Color.Transparent
				},
				border = if (isSelected) {
					BorderStroke(IosHairlineWidth, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
				} else {
					null
				},
				modifier = Modifier
					.clip(CircleShape)
					.clickable { onSelect(tab) },
			) {
				Text(
					text = label(tab),
					style = MaterialTheme.typography.labelMedium.copy(
						fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
					),
					color = if (isSelected) {
						MaterialTheme.colorScheme.primary
					} else {
						MaterialTheme.colorScheme.onSurfaceVariant
					},
					modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
					maxLines = 1,
					softWrap = false,
				)
			}
		}
	}
}

@Composable
private fun IosLibCompactSearchBar(
	value: String,
	placeholder: String,
	onValueChange: (String) -> Unit,
	onClear: () -> Unit,
	onSubmit: () -> Unit,
	showSubmitButton: Boolean,
) {
	Surface(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp),
		shape = CircleShape,
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = iosLibHairlineBorder(),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.height(40.dp)
				.padding(start = 14.dp, end = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(
				imageVector = Icons.Rounded.Search,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(16.dp),
			)
			Spacer(Modifier.width(8.dp))
			Box(
				modifier = Modifier.weight(1f),
				contentAlignment = Alignment.CenterStart,
			) {
				if (value.isEmpty()) {
					Text(
						text = placeholder,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
				BasicTextField(
					value = value,
					onValueChange = onValueChange,
					singleLine = true,
					textStyle = TextStyle(
						color = MaterialTheme.colorScheme.onSurface,
						fontSize = 14.sp,
					),
					cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
					keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
					keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
					modifier = Modifier.fillMaxWidth(),
				)
			}
			if (value.isNotEmpty()) {
				IconButton(
					onClick = onClear,
					modifier = Modifier.size(30.dp),
				) {
					Icon(
						imageVector = Icons.Rounded.Close,
						contentDescription = "清除",
						modifier = Modifier.size(15.dp),
					)
				}
			}
			if (showSubmitButton) {
				FilledTonalButton(
					onClick = onSubmit,
					contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
					modifier = Modifier.height(28.dp),
				) {
					Text("搜索", style = MaterialTheme.typography.labelMedium)
				}
			}
		}
	}
}

@Composable
private fun NavidromeUnconnectedView(
	onConfigureNavidrome: () -> Unit,
	onSwitchToLocal: () -> Unit,
	bottomPadding: PaddingValues,
) {
	Box(
		modifier = Modifier
			.fillMaxSize()
			.padding(horizontal = 24.dp)
			.padding(bottomPadding),
		contentAlignment = Alignment.Center,
	) {
		Column(
			modifier = Modifier.widthIn(max = 360.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			IosQuietEmptyState(
				message = "未连接 Navidrome 服务",
				icon = Icons.Rounded.LibraryMusic,
			)
			Button(
				onClick = onConfigureNavidrome,
				modifier = Modifier
					.fillMaxWidth()
					.height(44.dp),
				shape = RoundedCornerShape(12.dp),
			) {
				Text("配置 Navidrome")
			}
			OutlinedButton(
				onClick = onSwitchToLocal,
				modifier = Modifier
					.fillMaxWidth()
					.height(42.dp),
				shape = RoundedCornerShape(12.dp),
			) {
				Text("切换至 Melokeep")
			}
		}
	}
}

// =========================================================================
// 曲库拆分文件间共享极简原语与纯 Kotlin 工具函数（统一 IosLib / iosLib 前缀）
// =========================================================================

@Composable
internal fun iosLibHairlineBorderColor(): Color =
	MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)

@Composable
internal fun iosLibHairlineBorder(): BorderStroke =
	BorderStroke(IosHairlineWidth, iosLibHairlineBorderColor())

internal fun iosLibGroupedItemShape(isFirst: Boolean, isLast: Boolean): Shape = when {
	isFirst && isLast -> RoundedCornerShape(14.dp)
	isFirst -> RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
	isLast -> RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
	else -> RectangleShape
}

internal fun iosLibFormatQuietQuality(
	bitDepth: Int?,
	fileExtension: String?,
	bitRate: Int?,
	lossless: Boolean = false,
): String? {
	val ext = fileExtension?.lowercase().orEmpty()
	return when {
		(bitDepth ?: 0) >= 24 -> "HI-RES"
		ext in IosLibLosslessExtensions -> ext.uppercase()
		lossless -> "FLAC"
		(bitRate ?: 0) >= 700 -> "LOSSLESS"
		else -> null
	}
}

internal fun iosLibFormatLocalQuietQuality(track: MeloTrack): String? {
	val hasHiRes = track.qualities.any { q ->
		q.label.contains("hi-res", ignoreCase = true) ||
			q.label.contains("24", ignoreCase = true) ||
			q.label.contains("母带") ||
			q.codec.contains("24", ignoreCase = true)
	}
	if (hasHiRes) return "HI-RES"
	if (track.hasLossless) {
		return track.codecLabel?.uppercase()?.takeIf { it.isNotBlank() } ?: "FLAC"
	}
	return null
}

internal fun iosLibOfflineQueueNotice(count: Int): String =
	"已将 $count 首歌曲加入手机离线队列（可在工坊 → 下载到手机查看）"

internal fun iosLibNormalizeFolder(raw: String?): String {
	val cleaned = raw.orEmpty().replace('\\', '/').trim().trim('/')
	return if (cleaned == ".") "" else cleaned
}

internal fun iosLibExtractImmediateSubFolders(
	folders: List<MeloLibraryFolder>,
	currentFolder: String,
): List<IosLibCloudChildFolder> {
	val seen = linkedMapOf<String, IosLibCloudChildFolder>()
	folders.forEach { folderObj ->
		val norm = iosLibNormalizeFolder(folderObj.folder)
		if (norm.isEmpty()) return@forEach
		val childName = when {
			currentFolder.isEmpty() -> norm.substringBefore('/')
			norm.startsWith("$currentFolder/") -> norm.removePrefix("$currentFolder/").substringBefore('/')
			else -> return@forEach
		}
		if (childName.isBlank()) return@forEach
		val childFullPath = if (currentFolder.isEmpty()) childName else "$currentFolder/$childName"
		if (childFullPath !in seen) {
			seen[childFullPath] = IosLibCloudChildFolder(
				root = folderObj.root,
				name = childName,
				fullPath = childFullPath,
			)
		}
	}
	return seen.values.sortedBy { it.name.lowercase() }
}

internal fun iosLibResolveRootForFolder(
	folders: List<MeloLibraryFolder>,
	files: List<MeloLibraryFile>,
	folderPath: String,
): String {
	if (folderPath.isNotEmpty()) {
		folders.firstOrNull {
			val norm = iosLibNormalizeFolder(it.folder)
			norm == folderPath || norm.startsWith("$folderPath/")
		}?.root?.takeIf { it.isNotBlank() }?.let { return it }
	}
	return files.firstOrNull { it.root.isNotBlank() }?.root
		?: folders.firstOrNull { it.root.isNotBlank() }?.root
		?: ""
}

internal fun iosLibBuildFallbackMeloTrack(
	file: MeloLibraryFile,
	apiClient: MeloKeepApiClient,
): MeloTrack {
	val encodedPath = file.path.encodeURLParameter()
	val audioUrl = apiClient.resolveMediaUrl("/api/files/audio?path=$encodedPath")
	val ext = file.name.substringAfterLast('.', "").lowercase()
	return MeloTrack(
		id = file.path,
		source = "local",
		sourceId = file.path.removePrefix("asset:"),
		title = file.metadata["title"]?.takeIf { it.isNotBlank() }
			?: file.name.substringBeforeLast('.').ifBlank { file.name },
		artist = file.metadata["artist"].orEmpty(),
		album = file.metadata["album"].orEmpty(),
		sizeBytes = file.size,
		playback = MeloPlaybackInfo(
			state = "ready",
			url = audioUrl,
			codec = ext.takeIf { it.isNotBlank() },
		),
	)
}

internal fun iosLibMapMeloTrackToIosTrack(
	track: MeloTrack,
	subsonicManager: IosSubsonicManager,
): IosTrackItem {
	val mapped = subsonicManager.meloTrackToIosTrack(track)
	val normalizedRawId = if (mapped.rawId.startsWith("asset:") || mapped.rawId.startsWith("file:")) {
		mapped.rawId
	} else {
		mapped.rawId
	}
	return mapped.copy(
		id = "mk:$normalizedRawId",
		rawId = normalizedRawId,
	)
}

internal fun iosLibMapFileToIosTrack(
	file: MeloLibraryFile,
	subsonicManager: IosSubsonicManager,
	apiClient: MeloKeepApiClient,
): IosTrackItem {
	val encodedPath = file.path.encodeURLParameter()
	val fallbackAudioUrl = apiClient.resolveMediaUrl("/api/files/audio?path=$encodedPath")
	val track = file.track
	if (track != null) {
		val mapped = subsonicManager.meloTrackToIosTrack(track)
		val normalizedRawId = if (mapped.rawId.startsWith("asset:") || mapped.rawId.startsWith("file:")) {
			mapped.rawId
		} else {
			"asset:${mapped.rawId}"
		}
		return mapped.copy(
			id = "mk:$normalizedRawId",
			rawId = normalizedRawId,
			streamUrl = mapped.streamUrl?.takeIf { it.isNotBlank() } ?: fallbackAudioUrl,
		)
	}
	val ext = file.name.substringAfterLast('.', "").lowercase()
	val normalizedRawId = if (file.path.startsWith("asset:")) file.path else "asset:${file.path}"
	val isLossless = ext in IosLibLosslessExtensions
	return IosTrackItem(
		id = "mk:$normalizedRawId",
		rawId = normalizedRawId,
		sourceType = IosTrackSource.MELOKEEP,
		title = file.metadata["title"]?.takeIf { it.isNotBlank() }
			?: file.name.substringBeforeLast('.').ifBlank { file.name },
		artist = file.metadata["artist"]?.takeIf { it.isNotBlank() } ?: "未知歌手",
		album = file.metadata["album"]?.takeIf { it.isNotBlank() }
			?: iosLibNormalizeFolder(file.folder).ifBlank { "本地库" },
		durationSec = 0.0,
		streamUrl = fallbackAudioUrl,
		coverUrl = null,
		coverArtId = null,
		fileExtension = ext.takeIf { it.isNotBlank() },
		lossless = isLossless,
	)
}

@Composable
internal fun IosLibHeaderCircleButton(
	icon: ImageVector,
	contentDescription: String,
	onClick: () -> Unit,
	active: Boolean = false,
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
			.size(34.dp)
			.clip(CircleShape)
			.clickable(onClick = onClick),
	) {
		Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
			Icon(
				imageVector = icon,
				contentDescription = contentDescription,
				tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
				modifier = Modifier.size(17.dp),
			)
		}
	}
}

@Composable
internal fun IosLibSectionHeader(
	title: String,
	modifier: Modifier = Modifier,
	count: String? = null,
	actionLabel: String? = null,
	onActionClick: (() -> Unit)? = null,
) {
	Row(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.CenterVertically,
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp),
		) {
			Text(
				text = title,
				style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
				color = MaterialTheme.colorScheme.onSurface,
			)
			if (!count.isNullOrBlank()) {
				Text(
					text = count,
					style = MaterialTheme.typography.labelMedium.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
		if (actionLabel != null && onActionClick != null) {
			Text(
				text = actionLabel,
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier
					.clip(CircleShape)
					.clickable(onClick = onActionClick)
					.padding(horizontal = 6.dp, vertical = 3.dp),
			)
		}
	}
}

@Composable
internal fun IosLibDockPill(
	label: String,
	icon: ImageVector,
	onClick: () -> Unit,
	emphasized: Boolean = false,
) {
	Surface(
		shape = CircleShape,
		color = if (emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
		contentColor = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
		border = iosLibHairlineBorder(),
		modifier = Modifier
			.clip(CircleShape)
			.clickable(onClick = onClick),
	) {
		Row(
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(4.dp),
		) {
			Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
			Text(
				text = label,
				style = MaterialTheme.typography.labelMedium,
				maxLines = 1,
				softWrap = false,
			)
		}
	}
}

@Composable
internal fun IosLibOfflineStatusIcon(
	isOffline: Boolean,
	downloadProgress: Float?,
	onDownload: () -> Unit,
	onCancel: () -> Unit,
	onDelete: () -> Unit,
) {
	when {
		downloadProgress != null -> {
			IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
				CircularProgressIndicator(
					progress = { downloadProgress.coerceIn(0.05f, 1f) },
					modifier = Modifier.size(16.dp),
					strokeWidth = 2.dp,
				)
			}
		}

		isOffline -> {
			IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
				Icon(
					imageVector = Icons.Rounded.OfflinePin,
					contentDescription = "已离线",
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier.size(17.dp),
				)
			}
		}

		else -> {
			IconButton(onClick = onDownload, modifier = Modifier.size(32.dp)) {
				Icon(
					imageVector = Icons.Rounded.CloudDownload,
					contentDescription = "离线到手机",
					tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
					modifier = Modifier.size(17.dp),
				)
			}
		}
	}
}

/**
 * 歌曲条目左右滑动入队容器：
 * - 右滑 (StartToEnd)：加入待播队尾
 * - 左滑 (EndToStart)：设为下一首播放
 * 完成后通过 snapTo(Settled) 归位，多选状态下禁用手势。
 */
@Composable
internal fun IosSwipeableSongRow(
	enabled: Boolean = true,
	shape: Shape = RectangleShape,
	containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
	onAppendToQueue: (() -> Unit)?,
	onPlayNext: (() -> Unit)?,
	content: @Composable () -> Unit,
) {
	if (!enabled || (onAppendToQueue == null && onPlayNext == null)) {
		Surface(
			shape = shape,
			color = containerColor,
			modifier = Modifier.fillMaxWidth(),
		) {
			content()
		}
		return
	}

	val scope = rememberCoroutineScope()
	val haptics = LocalHapticFeedback.current
	val dismissState = rememberSwipeToDismissBoxState()
	var triggeredHaptic by remember { mutableStateOf(false) }
	val targetValue = dismissState.targetValue

	LaunchedEffect(targetValue) {
		if (targetValue != SwipeToDismissBoxValue.Settled && !triggeredHaptic) {
			haptics.performHapticFeedback(HapticFeedbackType.LongPress)
			triggeredHaptic = true
		} else if (targetValue == SwipeToDismissBoxValue.Settled) {
			triggeredHaptic = false
		}
	}

	Box(
		modifier = Modifier
			.fillMaxWidth()
			.clip(shape),
	) {
		SwipeToDismissBox(
			state = dismissState,
			enableDismissFromStartToEnd = onAppendToQueue != null,
			enableDismissFromEndToStart = onPlayNext != null,
			gesturesEnabled = enabled,
			onDismiss = { value ->
				when (value) {
					SwipeToDismissBoxValue.StartToEnd -> onAppendToQueue?.invoke()
					SwipeToDismissBoxValue.EndToStart -> onPlayNext?.invoke()
					SwipeToDismissBoxValue.Settled -> Unit
				}
				scope.launch {
					dismissState.snapTo(SwipeToDismissBoxValue.Settled)
				}
			},
			backgroundContent = {
				val direction = dismissState.dismissDirection
				val isStartToEnd = direction == SwipeToDismissBoxValue.StartToEnd
				val isEndToStart = direction == SwipeToDismissBoxValue.EndToStart
				val bgColor = when {
					isStartToEnd -> MaterialTheme.colorScheme.secondaryContainer
					isEndToStart -> MaterialTheme.colorScheme.primaryContainer
					else -> Color.Transparent
				}
				val fgColor = when {
					isStartToEnd -> MaterialTheme.colorScheme.onSecondaryContainer
					isEndToStart -> MaterialTheme.colorScheme.onPrimaryContainer
					else -> Color.Transparent
				}
				Row(
					modifier = Modifier
						.fillMaxSize()
						.background(bgColor)
						.padding(horizontal = 20.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = if (isEndToStart) Arrangement.End else Arrangement.Start,
				) {
					if (isStartToEnd) {
						Icon(
							imageVector = Icons.Rounded.PlaylistAdd,
							contentDescription = null,
							tint = fgColor,
							modifier = Modifier.size(18.dp),
						)
						Spacer(Modifier.width(6.dp))
						Text(
							text = "加入队尾",
							style = MaterialTheme.typography.labelLarge,
							fontWeight = FontWeight.SemiBold,
							color = fgColor,
						)
					} else if (isEndToStart) {
						Text(
							text = "下一首播放",
							style = MaterialTheme.typography.labelLarge,
							fontWeight = FontWeight.SemiBold,
							color = fgColor,
						)
						Spacer(Modifier.width(6.dp))
						Icon(
							imageVector = Icons.Rounded.PlaylistPlay,
							contentDescription = null,
							tint = fgColor,
							modifier = Modifier.size(18.dp),
						)
					}
				}
			},
		) {
			Surface(
				shape = shape,
				color = containerColor,
				modifier = Modifier.fillMaxWidth(),
			) {
				content()
			}
		}
	}
}

/**
 * 极简静谧曲目行：彻底移除刺眼金色描边 FLAC 徽标，将音质自然融入次行元数据；
 * 右侧移除单独离线按钮，改为在副标题起始处展示极简 OfflinePin 微标，并通过左右滑动快捷入队。
 */
@Composable
internal fun IosLibTrackRowContent(
	track: IosTrackItem,
	isPlaying: Boolean,
	isStarred: Boolean,
	isOffline: Boolean,
	downloadProgress: Float?,
	multiSelectMode: Boolean,
	isSelected: Boolean,
	onClick: () -> Unit,
	onLongClick: (() -> Unit)? = null,
	onPlayNext: () -> Unit,
	onToggleStar: () -> Unit,
	onDownloadOffline: () -> Unit,
	onCancelOffline: () -> Unit,
	onDeleteOffline: () -> Unit,
	onAddToPlaylist: (() -> Unit)? = null,
	onRemoveFromPlaylist: (() -> Unit)? = null,
	onRequestPhysicalDelete: (() -> Unit)? = null,
	trackIndex: Int? = null,
	showCover: Boolean = true,
	showStarButton: Boolean = true,
	showAlbumInSubtitle: Boolean = true,
	swipeEnabled: Boolean = false,
	swipeShape: Shape = RectangleShape,
	swipeContainerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
	onSwipeAppendToQueue: (() -> Unit)? = null,
	onSwipePlayNext: (() -> Unit)? = null,
) {
	var menuExpanded by remember { mutableStateOf(false) }

	val qualityTag = remember(track.id, track.bitDepth, track.fileExtension, track.bitRate, track.lossless) {
		iosLibFormatQuietQuality(track.bitDepth, track.fileExtension, track.bitRate, track.lossless)
	}
	val subtitleText = remember(track.id, track.artist, track.album, track.durationMs, qualityTag, showAlbumInSubtitle) {
		val parts = buildList {
			track.artist.takeIf { it.isNotBlank() }?.let { add(it) }
			if (showAlbumInSubtitle) {
				track.album.takeIf { it.isNotBlank() }?.let { add(it) }
			} else if (track.durationMs > 0L) {
				add(formatDurationMs(track.durationMs))
			}
			qualityTag?.let { add(it) }
		}
		if (parts.isNotEmpty()) parts.joinToString(" · ")
		else if (track.durationMs > 0L) formatDurationMs(track.durationMs) else ""
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
			} else if (trackIndex != null) {
				Text(
					text = "$trackIndex",
					style = MaterialTheme.typography.labelMedium.tabularNums(),
					color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
					fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Normal,
					textAlign = TextAlign.Center,
					modifier = Modifier.width(24.dp),
				)
			}

			if (showCover) {
				CoverArtBox(
					coverUrl = track.coverUrl,
					size = 44.dp,
					cornerRadius = 9.dp,
				)
			}

			Column(
				modifier = Modifier.weight(1f),
				verticalArrangement = Arrangement.spacedBy(2.dp),
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
			}

			if (!multiSelectMode) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(0.dp),
				) {
					if (showStarButton) {
						IconButton(onClick = onToggleStar, modifier = Modifier.size(32.dp)) {
							Icon(
								imageVector = if (isStarred) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
								contentDescription = if (isStarred) "取消喜欢" else "加入我喜欢",
								tint = if (isStarred) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
								modifier = Modifier.size(16.dp),
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
								text = { Text("下一首播放") },
								leadingIcon = { Icon(Icons.Rounded.PlaylistPlay, contentDescription = null) },
								onClick = {
									menuExpanded = false
									onPlayNext()
								},
							)
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
							if (onRemoveFromPlaylist != null) {
								DropdownMenuItem(
									text = { Text("从此歌单移除") },
									leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
									onClick = {
										menuExpanded = false
										onRemoveFromPlaylist()
									},
								)
							}
							if (onRequestPhysicalDelete != null) {
								HorizontalDivider()
								DropdownMenuItem(
									text = {
										Text(
											text = "从磁盘删除源文件",
											color = MaterialTheme.colorScheme.error,
										)
									},
									leadingIcon = {
										Icon(
											imageVector = Icons.Rounded.Delete,
											contentDescription = null,
											tint = MaterialTheme.colorScheme.error,
										)
									},
									onClick = {
										menuExpanded = false
										onRequestPhysicalDelete()
									},
								)
							}
						}
					}
				}
			}
		}
	}

	if (swipeEnabled && !multiSelectMode && (onSwipeAppendToQueue != null || onSwipePlayNext != null)) {
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

@Composable
internal fun IosLibBatchSelectionDock(
	selectedCount: Int,
	totalCount: Int,
	hasOfflineRemovable: Boolean,
	onSelectAll: () -> Unit,
	onExit: () -> Unit,
	onPlaySelected: () -> Unit,
	onDownloadSelected: () -> Unit,
	onAddToPlaylist: (() -> Unit)? = null,
	onDeleteOfflineSelected: (() -> Unit)? = null,
) {
	val isAllSelected = totalCount > 0 && selectedCount >= totalCount
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
							.clickable(onClick = onSelectAll),
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
					label = "播放",
					icon = Icons.Rounded.PlayArrow,
					emphasized = true,
					onClick = { if (selectedCount > 0) onPlaySelected() },
				)
				IosLibDockPill(
					label = "离线",
					icon = Icons.Rounded.CloudDownload,
					onClick = { if (selectedCount > 0) onDownloadSelected() },
				)
				if (onAddToPlaylist != null) {
					IosLibDockPill(
						label = "加歌单",
						icon = Icons.Rounded.PlaylistAdd,
						onClick = { if (selectedCount > 0) onAddToPlaylist() },
					)
				}
				if (hasOfflineRemovable && onDeleteOfflineSelected != null) {
					IosLibDockPill(
						label = "删离线",
						icon = Icons.Rounded.Delete,
						onClick = { if (selectedCount > 0) onDeleteOfflineSelected() },
					)
				}
			}
		}
	}
}

@Composable
internal fun <T> IosLibSortSheet(
	title: String,
	entries: List<T>,
	label: (T) -> String,
	selected: T,
	reversed: Boolean,
	cardsMode: Boolean,
	onSelect: (T) -> Unit,
	onToggleReversed: () -> Unit,
	onSetCardsMode: (Boolean) -> Unit,
	onDismiss: () -> Unit,
) {
	ModalBottomSheet(onDismissRequest = onDismiss) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.navigationBarsPadding()
				.padding(horizontal = 20.dp)
				.padding(bottom = 24.dp),
			verticalArrangement = Arrangement.spacedBy(14.dp),
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = title,
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.SemiBold,
				)
				Surface(
					shape = CircleShape,
					color = MaterialTheme.colorScheme.surfaceContainerHigh,
					border = iosLibHairlineBorder(),
					modifier = Modifier
						.clip(CircleShape)
						.clickable(onClick = onToggleReversed),
				) {
					Row(
						modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(4.dp),
					) {
						Icon(
							imageVector = if (reversed) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
							contentDescription = null,
							modifier = Modifier.size(14.dp),
							tint = MaterialTheme.colorScheme.primary,
						)
						Text(
							text = if (reversed) "升序" else "降序",
							style = MaterialTheme.typography.labelMedium,
							color = MaterialTheme.colorScheme.onSurface,
							maxLines = 1,
							softWrap = false,
						)
					}
				}
			}

			FlowRow(
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				entries.forEach { entry ->
					val active = entry == selected
					Surface(
						shape = CircleShape,
						color = if (active) {
							MaterialTheme.colorScheme.primary
						} else {
							MaterialTheme.colorScheme.surfaceContainer
						},
						border = if (active) null else iosLibHairlineBorder(),
						modifier = Modifier
							.clip(CircleShape)
							.clickable { onSelect(entry) },
					) {
						Text(
							text = label(entry),
							style = MaterialTheme.typography.labelMedium.copy(
								fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
							),
							color = if (active) {
								MaterialTheme.colorScheme.onPrimary
							} else {
								MaterialTheme.colorScheme.onSurface
							},
							maxLines = 1,
							softWrap = false,
							modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
						)
					}
				}
			}

			IosHairlineDivider()

			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = "显示模式",
					style = MaterialTheme.typography.labelLarge,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					IosLibDockPill(
						label = "卡片",
						icon = Icons.Rounded.GridView,
						emphasized = cardsMode,
						onClick = { onSetCardsMode(true) },
					)
					IosLibDockPill(
						label = "列表",
						icon = Icons.Rounded.ViewAgenda,
						emphasized = !cardsMode,
						onClick = { onSetCardsMode(false) },
					)
				}
			}
		}
	}
}

@Composable
internal fun IosLibAlbumGridTile(
	album: IosAlbumSummary,
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
		Column(
			modifier = Modifier.padding(9.dp),
			verticalArrangement = Arrangement.spacedBy(7.dp),
		) {
			CoverArtBox(
				coverUrl = album.coverUrl,
				modifier = Modifier
					.fillMaxWidth()
					.aspectRatio(1f),
				cornerRadius = 10.dp,
			)
			Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Text(
					text = album.name,
					style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				val sub = remember(album.id, album.artist, album.year) {
					listOfNotNull(album.artist.takeIf { it.isNotBlank() }, album.year?.toString()).joinToString(" · ")
				}
				Text(
					text = sub,
					style = MaterialTheme.typography.bodySmall.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}
