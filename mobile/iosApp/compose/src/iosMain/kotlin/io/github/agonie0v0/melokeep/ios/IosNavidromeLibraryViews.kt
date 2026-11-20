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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.agonie0v0.melokeep.shared.MeloNavidromeDeletionPreview
import kotlinx.coroutines.launch
import kotlin.random.Random

private val NAV_VINYL_DISC_COLORS = listOf(Color(0xFF1D1E24), Color(0xFF0A0B0F))
private val NAV_VINYL_GROOVE_FACTORS = floatArrayOf(0.92f, 0.80f, 0.68f, 0.56f, 0.44f)
private val NAV_VINYL_GROOVE_STROKE = Stroke(width = 1.2f)
private val NAV_VINYL_LABEL_STROKE = Stroke(width = 1.5f)
private val NAV_POSTER_SCRIM_BRUSH = Brush.verticalGradient(
	colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.42f)),
)

@Composable
internal fun IosLibNavidromeSection(
	subTab: IosLibNavSubTab,
	albumCardsMode: Boolean,
	songCardsMode: Boolean,
	libState: IosLibraryState,
	subsonicManager: IosSubsonicManager,
	audioPlayer: IosAudioPlayer,
	currentTrackId: String?,
	offlineIds: Set<String>,
	downloadingProgress: Map<String, Float>,
	onSelectSubTab: (IosLibNavSubTab) -> Unit,
	onToggleAlbumCardsMode: () -> Unit,
	onToggleSongCardsMode: () -> Unit,
	onOpenDailyMix: () -> Unit,
	onOpenFavorites: () -> Unit,
	onOpenAlbum: (IosAlbumSummary) -> Unit,
	onOpenArtist: (IosArtistSummary) -> Unit,
	onOpenPlaylist: (IosPlaylistSummary) -> Unit,
	onCreatePlaylist: () -> Unit,
	onOpenAddToPlaylist: (List<IosTrackItem>) -> Unit,
	onRequestPhysicalDelete: ((IosTrackItem) -> Unit)?,
	onShowNotice: (String) -> Unit,
	bottomPadding: PaddingValues,
	onRecordSearchResult: () -> Unit = {},
) {
	val scope = rememberCoroutineScope()

	var albumSortName by rememberSaveable { mutableStateOf(IosLibAlbumSort.RECENT.name) }
	val albumSort = remember(albumSortName) {
		IosLibAlbumSort.entries.firstOrNull { it.name == albumSortName } ?: IosLibAlbumSort.RECENT
	}
	var albumReversed by rememberSaveable { mutableStateOf(false) }
	var albumRandomSeed by remember { mutableIntStateOf(1) }
	var albumSortSheetOpen by rememberSaveable { mutableStateOf(false) }

	var songSortName by rememberSaveable { mutableStateOf(IosLibSongSort.FREQUENT.name) }
	val songSort = remember(songSortName) {
		IosLibSongSort.entries.firstOrNull { it.name == songSortName } ?: IosLibSongSort.FREQUENT
	}
	var songReversed by rememberSaveable { mutableStateOf(false) }
	var songRandomSeed by remember { mutableIntStateOf(1) }
	var songSortSheetOpen by rememberSaveable { mutableStateOf(false) }

	if (libState.searchQuery.isNotBlank()) {
		NavSearchResultsContent(
			libState = libState,
			subsonicManager = subsonicManager,
			audioPlayer = audioPlayer,
			currentTrackId = currentTrackId,
			offlineIds = offlineIds,
			downloadingProgress = downloadingProgress,
			onOpenAlbum = onOpenAlbum,
			onOpenArtist = onOpenArtist,
			onOpenAddToPlaylist = onOpenAddToPlaylist,
			onRequestPhysicalDelete = onRequestPhysicalDelete,
			onShowNotice = onShowNotice,
			onRecordSearchResult = onRecordSearchResult,
			bottomPadding = bottomPadding,
		)
		return
	}

	when (subTab) {
		IosLibNavSubTab.OVERVIEW -> NavOverviewContent(
			libState = libState,
			subsonicManager = subsonicManager,
			audioPlayer = audioPlayer,
			currentTrackId = currentTrackId,
			offlineIds = offlineIds,
			downloadingProgress = downloadingProgress,
			onViewAllAlbums = { onSelectSubTab(IosLibNavSubTab.ALBUMS) },
			onViewAllPlaylists = { onSelectSubTab(IosLibNavSubTab.PLAYLISTS) },
			onViewAllArtists = { onSelectSubTab(IosLibNavSubTab.ARTISTS) },
			onViewAllSongs = { onSelectSubTab(IosLibNavSubTab.SONGS) },
			onOpenDailyMix = onOpenDailyMix,
			onOpenFavorites = onOpenFavorites,
			onOpenAlbum = onOpenAlbum,
			onOpenArtist = onOpenArtist,
			onOpenPlaylist = onOpenPlaylist,
			onOpenAddToPlaylist = onOpenAddToPlaylist,
			onRequestPhysicalDelete = onRequestPhysicalDelete,
			onShowNotice = onShowNotice,
			bottomPadding = bottomPadding,
		)

		IosLibNavSubTab.ALBUMS -> {
			val sortedAlbums = remember(
				libState.allAlbums,
				libState.recentAlbums,
				libState.newestAlbums,
				libState.frequentAlbums,
				libState.randomAlbums,
				albumSort,
				albumReversed,
				albumRandomSeed,
			) {
				sortNavAlbums(
					allAlbums = libState.allAlbums.ifEmpty { libState.newestAlbums },
					recentAlbums = libState.recentAlbums,
					newestAlbums = libState.newestAlbums,
					frequentAlbums = libState.frequentAlbums,
					sort = albumSort,
					reversed = albumReversed,
					randomSeed = albumRandomSeed,
				)
			}

			NavAlbumsSubContent(
				albums = sortedAlbums,
				loading = libState.loadingOverview,
				cardsMode = albumCardsMode,
				sortingLabel = albumSort.label,
				onOpenSortSheet = { albumSortSheetOpen = true },
				onToggleViewMode = onToggleAlbumCardsMode,
				onOpenAlbum = onOpenAlbum,
				bottomPadding = bottomPadding,
			)

			if (albumSortSheetOpen) {
				IosLibSortSheet(
					title = "专辑排序",
					entries = IosLibAlbumSort.entries,
					label = { it.label },
					selected = albumSort,
					reversed = albumReversed,
					cardsMode = albumCardsMode,
					onSelect = { next ->
						if (next == IosLibAlbumSort.RANDOM) albumRandomSeed += 1
						albumSortName = next.name
					},
					onToggleReversed = { albumReversed = !albumReversed },
					onSetCardsMode = { target ->
						if (target != albumCardsMode) onToggleAlbumCardsMode()
					},
					onDismiss = { albumSortSheetOpen = false },
				)
			}
		}

		IosLibNavSubTab.PLAYLISTS -> NavPlaylistsSubContent(
			playlists = libState.playlists,
			loading = libState.loadingOverview,
			onCreatePlaylist = onCreatePlaylist,
			onOpenPlaylist = onOpenPlaylist,
			onPlayPlaylist = { pl ->
				scope.launch {
					subsonicManager.fetchPlaylistDetail(pl.id).onSuccess { (_, tracks) ->
						if (tracks.isNotEmpty()) {
							audioPlayer.playQueue(tracks, 0)
						} else {
							onOpenPlaylist(pl)
						}
					}.onFailure {
						onOpenPlaylist(pl)
					}
				}
			},
			onDeletePlaylist = { pl ->
				scope.launch { subsonicManager.deletePlaylist(pl.id) }
			},
			bottomPadding = bottomPadding,
		)

		IosLibNavSubTab.ARTISTS -> NavArtistsSubContent(
			artists = libState.artists,
			loading = libState.loadingOverview,
			onOpenArtist = onOpenArtist,
			bottomPadding = bottomPadding,
		)

		IosLibNavSubTab.SONGS -> {
			LaunchedEffect(libState.activeServer?.id) {
				subsonicManager.loadAllSongs(forceRefresh = false)
			}

			val sortedSongs = remember(libState.allSongs, songSort, songReversed, songRandomSeed) {
				sortNavSongs(
					songs = libState.allSongs,
					sort = songSort,
					reversed = songReversed,
					randomSeed = songRandomSeed,
				)
			}

			NavSongsSubContent(
				songs = sortedSongs,
				loading = libState.loadingAllSongs,
				allSongsLoaded = libState.allSongsLoaded,
				cardsMode = songCardsMode,
				sortingLabel = songSort.label,
				subsonicManager = subsonicManager,
				audioPlayer = audioPlayer,
				currentTrackId = currentTrackId,
				starredIds = libState.starredIds,
				unstarredIds = libState.unstarredIds,
				offlineIds = offlineIds,
				downloadingProgress = downloadingProgress,
				onOpenSortSheet = { songSortSheetOpen = true },
				onToggleViewMode = onToggleSongCardsMode,
				onRefresh = { subsonicManager.loadAllSongs(forceRefresh = true) },
				onOpenAddToPlaylist = onOpenAddToPlaylist,
				onRequestPhysicalDelete = onRequestPhysicalDelete,
				onShowNotice = onShowNotice,
				bottomPadding = bottomPadding,
			)

			if (songSortSheetOpen) {
				IosLibSortSheet(
					title = "歌曲排序",
					entries = IosLibSongSort.entries,
					label = { it.label },
					selected = songSort,
					reversed = songReversed,
					cardsMode = songCardsMode,
					onSelect = { next ->
						if (next == IosLibSongSort.RANDOM) songRandomSeed += 1
						songSortName = next.name
					},
					onToggleReversed = { songReversed = !songReversed },
					onSetCardsMode = { target ->
						if (target != songCardsMode) onToggleSongCardsMode()
					},
					onDismiss = { songSortSheetOpen = false },
				)
			}
		}
	}
}

private fun sortNavAlbums(
	allAlbums: List<IosAlbumSummary>,
	recentAlbums: List<IosAlbumSummary>,
	newestAlbums: List<IosAlbumSummary>,
	frequentAlbums: List<IosAlbumSummary>,
	sort: IosLibAlbumSort,
	reversed: Boolean,
	randomSeed: Int,
): List<IosAlbumSummary> {
	if (allAlbums.isEmpty()) return emptyList()
	val base = when (sort) {
		IosLibAlbumSort.RECENT -> {
			val rank = recentAlbums.withIndex().associate { (i, a) -> a.id to i }
			allAlbums.sortedWith(
				compareBy<IosAlbumSummary> { rank[it.id] ?: Int.MAX_VALUE }
					.thenByDescending { it.createdAtIso }
					.thenBy { it.name.lowercase() },
			)
		}

		IosLibAlbumSort.NEWEST -> {
			val rank = newestAlbums.withIndex().associate { (i, a) -> a.id to i }
			allAlbums.sortedWith(
				compareBy<IosAlbumSummary> { rank[it.id] ?: Int.MAX_VALUE }
					.thenByDescending { it.createdAtIso }
					.thenBy { it.name.lowercase() },
			)
		}

		IosLibAlbumSort.FREQUENT -> {
			val rank = frequentAlbums.withIndex().associate { (i, a) -> a.id to i }
			allAlbums.sortedWith(
				compareBy<IosAlbumSummary> { rank[it.id] ?: Int.MAX_VALUE }
					.thenByDescending { it.playCount }
					.thenBy { it.name.lowercase() },
			)
		}

		IosLibAlbumSort.NAME -> allAlbums.sortedBy { it.name.lowercase() }
		IosLibAlbumSort.ARTIST -> allAlbums.sortedWith(
			compareBy<IosAlbumSummary> { it.artist.lowercase() }.thenBy { it.name.lowercase() },
		)

		IosLibAlbumSort.RATING -> allAlbums.sortedWith(
			compareByDescending<IosAlbumSummary> { it.userRating ?: 0 }.thenBy { it.name.lowercase() },
		)

		IosLibAlbumSort.RANDOM -> allAlbums.shuffled(Random(randomSeed * 31 + allAlbums.size))
		IosLibAlbumSort.YEAR -> allAlbums.sortedWith(
			compareByDescending<IosAlbumSummary> { it.year ?: Int.MIN_VALUE }.thenBy { it.name.lowercase() },
		)

		IosLibAlbumSort.GENRE -> allAlbums.sortedWith(
			compareBy<IosAlbumSummary> { it.genre?.lowercase().orEmpty().ifBlank { "\uFFFF" } }
				.thenBy { it.name.lowercase() },
		)
	}
	return if (reversed && sort != IosLibAlbumSort.RANDOM) base.asReversed() else base
}

private fun sortNavSongs(
	songs: List<IosTrackItem>,
	sort: IosLibSongSort,
	reversed: Boolean,
	randomSeed: Int,
): List<IosTrackItem> {
	if (songs.isEmpty()) return emptyList()
	val base = when (sort) {
		IosLibSongSort.FREQUENT -> songs.sortedWith(
			compareByDescending<IosTrackItem> { it.playCount }.thenBy { it.title.lowercase() },
		)

		IosLibSongSort.NEWEST -> songs.asReversed()
		IosLibSongSort.RANDOM -> songs.shuffled(Random(randomSeed * 37 + songs.size))
		IosLibSongSort.RATING -> songs.sortedWith(
			compareByDescending<IosTrackItem> { it.userRating ?: 0 }.thenBy { it.title.lowercase() },
		)

		IosLibSongSort.YEAR -> songs.sortedWith(
			compareByDescending<IosTrackItem> { it.year ?: Int.MIN_VALUE }.thenBy { it.title.lowercase() },
		)

		IosLibSongSort.NAME -> songs.sortedBy { it.title.lowercase() }
		IosLibSongSort.ARTIST -> songs.sortedWith(
			compareBy<IosTrackItem> { it.artist.lowercase() }.thenBy { it.title.lowercase() },
		)

		IosLibSongSort.GENRE -> songs.sortedWith(
			compareBy<IosTrackItem> { it.genre?.lowercase().orEmpty().ifBlank { "\uFFFF" } }
				.thenBy { it.title.lowercase() },
		)
	}
	return if (reversed && sort != IosLibSongSort.RANDOM) base.asReversed() else base
}

@Composable
private fun NavOverviewContent(
	libState: IosLibraryState,
	subsonicManager: IosSubsonicManager,
	audioPlayer: IosAudioPlayer,
	currentTrackId: String?,
	offlineIds: Set<String>,
	downloadingProgress: Map<String, Float>,
	onViewAllAlbums: () -> Unit,
	onViewAllPlaylists: () -> Unit,
	onViewAllArtists: () -> Unit,
	onViewAllSongs: () -> Unit,
	onOpenDailyMix: () -> Unit,
	onOpenFavorites: () -> Unit,
	onOpenAlbum: (IosAlbumSummary) -> Unit,
	onOpenArtist: (IosArtistSummary) -> Unit,
	onOpenPlaylist: (IosPlaylistSummary) -> Unit,
	onOpenAddToPlaylist: (List<IosTrackItem>) -> Unit,
	onRequestPhysicalDelete: ((IosTrackItem) -> Unit)?,
	onShowNotice: (String) -> Unit,
	bottomPadding: PaddingValues,
) {
	val scope = rememberCoroutineScope()
	val recentCarousel = remember(libState.newestAlbums, libState.recentAlbums) {
		libState.newestAlbums.ifEmpty { libState.recentAlbums }.take(12)
	}
	val previewPlaylists = remember(libState.playlists) { libState.playlists.take(6) }
	val playlistRows = remember(previewPlaylists) { (previewPlaylists.size + 1) / 2 }
	val previewArtists = remember(libState.artists) { libState.artists.take(12) }
	val previewSongs = remember(libState.allSongs, libState.starredSongs) {
		(libState.allSongs.ifEmpty { libState.starredSongs }).take(6)
	}

	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(
			top = 8.dp,
			bottom = bottomPadding.calculateBottomPadding() + 20.dp,
		),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		// 1. 猜你喜欢 / 我喜欢 极简双列快捷卡
		item(key = "nav-overview-quick", contentType = "quick-cards") {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp),
				horizontalArrangement = Arrangement.spacedBy(10.dp),
			) {
				NavQuickTile(
					title = "猜你喜欢",
					count = null,
					icon = Icons.Rounded.AutoAwesome,
					accentColor = MaterialTheme.colorScheme.primary,
					onClick = onOpenDailyMix,
					modifier = Modifier.weight(1f),
				)
				NavQuickTile(
					title = "我喜欢",
					count = libState.starredSongs.size.takeIf { it > 0 },
					icon = Icons.Rounded.Favorite,
					accentColor = MaterialTheme.colorScheme.error,
					onClick = onOpenFavorites,
					modifier = Modifier.weight(1f),
				)
			}
		}

		// 2. 最近入库黑胶画廊横滑卡
		item(key = "nav-overview-recent-header", contentType = "section-header") {
			IosLibSectionHeader(
				title = "最近入库",
				actionLabel = if (recentCarousel.isNotEmpty()) "全部" else null,
				onActionClick = onViewAllAlbums,
				modifier = Modifier.padding(top = 6.dp),
			)
		}
		item(key = "nav-overview-recent-row", contentType = "carousel") {
			if (recentCarousel.isEmpty()) {
				IosQuietEmptyState(
					message = if (libState.loadingOverview) "正在加载曲库…" else "暂无最近入库专辑",
					icon = Icons.Rounded.Album,
				)
			} else {
				LazyRow(
					contentPadding = PaddingValues(horizontal = 16.dp),
					horizontalArrangement = Arrangement.spacedBy(14.dp),
				) {
					items(
						items = recentCarousel,
						key = { it.id },
						contentType = { "vinyl-album" },
					) { album ->
						NavVinylAlbumCard(
							album = album,
							onClick = { onOpenAlbum(album) },
						)
					}
				}
			}
		}

		// 3. 歌单海报网格
		if (previewPlaylists.isNotEmpty()) {
			item(key = "nav-overview-playlists-header", contentType = "section-header") {
				IosLibSectionHeader(
					title = "歌单",
					count = "${libState.playlists.size}",
					actionLabel = "全部",
					onActionClick = onViewAllPlaylists,
					modifier = Modifier.padding(top = 6.dp),
				)
			}
			items(
				count = playlistRows,
				key = { rowIdx -> "nav-ov-pl-row-${previewPlaylists[rowIdx * 2].id}" },
				contentType = { "playlist-grid-row" },
			) { rowIdx ->
				val left = previewPlaylists[rowIdx * 2]
				val right = previewPlaylists.getOrNull(rowIdx * 2 + 1)
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 16.dp),
					horizontalArrangement = Arrangement.spacedBy(12.dp),
				) {
					NavPlaylistPosterCard(
						playlist = left,
						onClick = { onOpenPlaylist(left) },
						onPlayClick = {
							scope.launch {
								subsonicManager.fetchPlaylistDetail(left.id).onSuccess { (_, tracks) ->
									if (tracks.isNotEmpty()) audioPlayer.playQueue(tracks, 0)
									else onOpenPlaylist(left)
								}
							}
						},
						modifier = Modifier.weight(1f),
					)
					if (right != null) {
						NavPlaylistPosterCard(
							playlist = right,
							onClick = { onOpenPlaylist(right) },
							onPlayClick = {
								scope.launch {
									subsonicManager.fetchPlaylistDetail(right.id).onSuccess { (_, tracks) ->
										if (tracks.isNotEmpty()) audioPlayer.playQueue(tracks, 0)
										else onOpenPlaylist(right)
									}
								}
							},
							modifier = Modifier.weight(1f),
						)
					} else {
						Spacer(Modifier.weight(1f))
					}
				}
			}
		}

		// 4. 歌手横滑圆形头像条
		if (previewArtists.isNotEmpty()) {
			item(key = "nav-overview-artists-header", contentType = "section-header") {
				IosLibSectionHeader(
					title = "歌手",
					count = "${libState.artists.size}",
					actionLabel = "全部",
					onActionClick = onViewAllArtists,
					modifier = Modifier.padding(top = 6.dp),
				)
			}
			item(key = "nav-overview-artists-row", contentType = "carousel") {
				LazyRow(
					contentPadding = PaddingValues(horizontal = 16.dp),
					horizontalArrangement = Arrangement.spacedBy(14.dp),
				) {
					items(
						items = previewArtists,
						key = { it.id },
						contentType = { "artist-avatar" },
					) { artist ->
						Column(
							modifier = Modifier
								.width(76.dp)
								.clip(RoundedCornerShape(12.dp))
								.clickable { onOpenArtist(artist) },
							horizontalAlignment = Alignment.CenterHorizontally,
							verticalArrangement = Arrangement.spacedBy(6.dp),
						) {
							CoverArtBox(
								coverUrl = artist.coverUrl,
								size = 64.dp,
								cornerRadius = 32.dp,
								modifier = Modifier.border(IosHairlineWidth, iosLibHairlineBorderColor(), CircleShape),
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

		// 5. 最近曲目分组卡（Apple Notes 极简样式）
		if (previewSongs.isNotEmpty()) {
			item(key = "nav-overview-songs-header", contentType = "section-header") {
				IosLibSectionHeader(
					title = "最近曲目",
					actionLabel = "全部",
					onActionClick = onViewAllSongs,
					modifier = Modifier.padding(top = 6.dp),
				)
			}
			item(key = "nav-overview-songs-card", contentType = "track-group") {
				IosGroupedCard(modifier = Modifier.padding(horizontal = 16.dp)) {
					previewSongs.forEachIndexed { idx, track ->
						IosLibTrackRowContent(
							track = track,
							isPlaying = track.id == currentTrackId,
							isStarred = subsonicManager.isStarred(track),
							isOffline = track.id in offlineIds,
							downloadProgress = downloadingProgress[track.id],
							multiSelectMode = false,
							isSelected = false,
							onClick = { audioPlayer.playQueue(previewSongs, idx) },
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
						if (idx < previewSongs.lastIndex) {
							IosHairlineDivider(startIndent = 66.dp)
						}
					}
				}
			}
		}
	}
}

@Composable
private fun NavQuickTile(
	title: String,
	count: Int?,
	icon: ImageVector,
	accentColor: Color,
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
				tint = accentColor,
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

@Composable
private fun NavVinylAlbumCard(
	album: IosAlbumSummary,
	onClick: () -> Unit,
) {
	val primaryAccent = MaterialTheme.colorScheme.primary
	val goldAccent = MaterialTheme.colorScheme.meloGoldText
	val subtleBorder = iosLibHairlineBorderColor()

	Surface(
		shape = RoundedCornerShape(16.dp),
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = BorderStroke(IosHairlineWidth, subtleBorder),
		modifier = Modifier
			.width(192.dp)
			.clip(RoundedCornerShape(16.dp))
			.clickable(onClick = onClick),
	) {
		Column(
			modifier = Modifier.padding(11.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.height(132.dp),
				contentAlignment = Alignment.CenterStart,
			) {
				Spacer(
					modifier = Modifier
						.size(118.dp)
						.align(Alignment.CenterEnd)
						.offset(x = (-2).dp)
						.drawWithCache {
							val r = size.minDimension / 2f
							val center = Offset(r, r)
							val discBrush = Brush.radialGradient(
								colors = NAV_VINYL_DISC_COLORS,
								center = center,
								radius = r,
							)
							val grooveColor = Color.White.copy(alpha = 0.08f)
							val labelFillColor = goldAccent.copy(alpha = 0.85f)
							val labelRingColor = primaryAccent.copy(alpha = 0.35f)
							val holeColor = Color(0xFF0B0C10)
							onDrawBehind {
								drawCircle(brush = discBrush, radius = r, center = center)
								for (factor in NAV_VINYL_GROOVE_FACTORS) {
									drawCircle(
										color = grooveColor,
										radius = r * factor,
										center = center,
										style = NAV_VINYL_GROOVE_STROKE,
									)
								}
								drawCircle(color = labelFillColor, radius = r * 0.28f, center = center)
								drawCircle(
									color = labelRingColor,
									radius = r * 0.22f,
									center = center,
									style = NAV_VINYL_LABEL_STROKE,
								)
								drawCircle(color = holeColor, radius = r * 0.08f, center = center)
							}
						},
				)

				CoverArtBox(
					coverUrl = album.coverUrl,
					size = 130.dp,
					cornerRadius = 12.dp,
					modifier = Modifier.border(IosHairlineWidth, subtleBorder, RoundedCornerShape(12.dp)),
				)
			}

			Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Text(
					text = album.name,
					style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				val sub = remember(album.id, album.artist, album.year, album.songCount) {
					listOfNotNull(
						album.artist.takeIf { it.isNotBlank() },
						album.year?.toString(),
					).joinToString(" · ").ifBlank { "${album.songCount} 首" }
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

@Composable
private fun NavPlaylistPosterCard(
	playlist: IosPlaylistSummary,
	onClick: () -> Unit,
	onPlayClick: () -> Unit,
	modifier: Modifier = Modifier,
	onDeleteClick: (() -> Unit)? = null,
) {
	Surface(
		shape = RoundedCornerShape(14.dp),
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = iosLibHairlineBorder(),
		modifier = modifier
			.clip(RoundedCornerShape(14.dp))
			.clickable(onClick = onClick),
	) {
		Column {
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.aspectRatio(1.12f),
			) {
				CoverArtBox(
					coverUrl = playlist.coverUrl,
					modifier = Modifier.fillMaxSize(),
					cornerRadius = 0.dp,
				)
				Box(
					modifier = Modifier
						.fillMaxSize()
						.background(NAV_POSTER_SCRIM_BRUSH),
				)
				Surface(
					shape = CircleShape,
					color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
					contentColor = MaterialTheme.colorScheme.onPrimary,
					modifier = Modifier
						.align(Alignment.BottomEnd)
						.padding(8.dp)
						.size(30.dp)
						.clip(CircleShape)
						.clickable(onClick = onPlayClick),
				) {
					Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
						Icon(
							imageVector = Icons.Rounded.PlayArrow,
							contentDescription = "播放歌单",
							modifier = Modifier.size(16.dp),
						)
					}
				}
				if (onDeleteClick != null) {
					Surface(
						shape = CircleShape,
						color = Color.Black.copy(alpha = 0.45f),
						contentColor = Color.White,
						modifier = Modifier
							.align(Alignment.TopEnd)
							.padding(6.dp)
							.size(26.dp)
							.clip(CircleShape)
							.clickable(onClick = onDeleteClick),
					) {
						Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
							Icon(
								imageVector = Icons.Rounded.Delete,
								contentDescription = "删除歌单",
								modifier = Modifier.size(14.dp),
							)
						}
					}
				}
			}
			Column(
				modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
				verticalArrangement = Arrangement.spacedBy(2.dp),
			) {
				Text(
					text = playlist.name.ifBlank { "未命名歌单" },
					style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				Text(
					text = "${playlist.songCount} 首",
					style = MaterialTheme.typography.labelSmall.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
				)
			}
		}
	}
}

@Composable
private fun NavAlbumsSubContent(
	albums: List<IosAlbumSummary>,
	loading: Boolean,
	cardsMode: Boolean,
	sortingLabel: String,
	onOpenSortSheet: () -> Unit,
	onToggleViewMode: () -> Unit,
	onOpenAlbum: (IosAlbumSummary) -> Unit,
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
		item(key = "nav-albums-toolbar", contentType = "toolbar") {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(bottom = 10.dp),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = if (albums.isNotEmpty()) "${albums.size} 张" else "专辑",
					style = MaterialTheme.typography.labelLarge.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					softWrap = false,
				)
				Row(
					horizontalArrangement = Arrangement.spacedBy(6.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					NavToolbarSortPill(label = sortingLabel, onClick = onOpenSortSheet)
					NavToolbarViewToggle(cardsMode = cardsMode, onClick = onToggleViewMode)
				}
			}
		}

		if (loading && albums.isEmpty()) {
			item(key = "nav-albums-loading", contentType = "loading") {
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.padding(vertical = 48.dp),
					contentAlignment = Alignment.Center,
				) {
					CircularProgressIndicator()
				}
			}
		} else if (albums.isEmpty()) {
			item(key = "nav-albums-empty", contentType = "empty") {
				IosQuietEmptyState(message = "暂无专辑", icon = Icons.Rounded.Album)
			}
		} else if (cardsMode) {
			val rowCount = (albums.size + 1) / 2
			items(
				count = rowCount,
				key = { rowIdx -> "nav-album-grid-${albums[rowIdx * 2].id}" },
				contentType = { "album-grid-row" },
			) { rowIdx ->
				val left = albums[rowIdx * 2]
				val right = albums.getOrNull(rowIdx * 2 + 1)
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(bottom = 12.dp),
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
		} else {
			itemsIndexed(
				items = albums,
				key = { _, album -> album.id },
				contentType = { _, _ -> "album-row" },
			) { idx, album ->
				val isFirst = idx == 0
				val isLast = idx == albums.lastIndex
				val meta = remember(album.id, album.artist, album.year, album.songCount) {
					listOfNotNull(
						album.artist.takeIf { it.isNotBlank() },
						album.year?.toString(),
						"${album.songCount} 首",
					).joinToString(" · ")
				}
				Surface(
					shape = iosLibGroupedItemShape(isFirst, isLast),
					color = MaterialTheme.colorScheme.surfaceContainer,
					modifier = Modifier
						.fillMaxWidth()
						.clip(iosLibGroupedItemShape(isFirst, isLast))
						.clickable { onOpenAlbum(album) },
				) {
					Column {
						Row(
							modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(12.dp),
						) {
							CoverArtBox(coverUrl = album.coverUrl, size = 46.dp, cornerRadius = 10.dp)
							Column(
								modifier = Modifier.weight(1f),
								verticalArrangement = Arrangement.spacedBy(2.dp),
							) {
								Text(
									text = album.name,
									style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
								)
								Text(
									text = meta,
									style = MaterialTheme.typography.bodySmall.tabularNums(),
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
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
							IosHairlineDivider(startIndent = 70.dp)
						}
					}
				}
			}
		}
	}
}

@Composable
private fun NavPlaylistsSubContent(
	playlists: List<IosPlaylistSummary>,
	loading: Boolean,
	onCreatePlaylist: () -> Unit,
	onOpenPlaylist: (IosPlaylistSummary) -> Unit,
	onPlayPlaylist: (IosPlaylistSummary) -> Unit,
	onDeletePlaylist: (IosPlaylistSummary) -> Unit,
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
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		item(key = "nav-playlists-toolbar", contentType = "toolbar") {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = if (playlists.isNotEmpty()) "${playlists.size} 个歌单" else "歌单",
					style = MaterialTheme.typography.labelLarge.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				IosLibDockPill(
					label = "新建歌单",
					icon = Icons.Rounded.Add,
					emphasized = true,
					onClick = onCreatePlaylist,
				)
			}
		}

		if (loading && playlists.isEmpty()) {
			item(key = "nav-playlists-loading", contentType = "loading") {
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.padding(vertical = 48.dp),
					contentAlignment = Alignment.Center,
				) {
					CircularProgressIndicator()
				}
			}
		} else if (playlists.isEmpty()) {
			item(key = "nav-playlists-empty", contentType = "empty") {
				IosQuietEmptyState(
					message = "暂无歌单",
					icon = Icons.Rounded.QueueMusic,
					actionLabel = "新建歌单",
					onAction = onCreatePlaylist,
				)
			}
		} else {
			val rowCount = (playlists.size + 1) / 2
			items(
				count = rowCount,
				key = { rowIdx -> "nav-pl-grid-${playlists[rowIdx * 2].id}" },
				contentType = { "playlist-grid-row" },
			) { rowIdx ->
				val left = playlists[rowIdx * 2]
				val right = playlists.getOrNull(rowIdx * 2 + 1)
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.spacedBy(12.dp),
				) {
					NavPlaylistPosterCard(
						playlist = left,
						onClick = { onOpenPlaylist(left) },
						onPlayClick = { onPlayPlaylist(left) },
						onDeleteClick = { onDeletePlaylist(left) },
						modifier = Modifier.weight(1f),
					)
					if (right != null) {
						NavPlaylistPosterCard(
							playlist = right,
							onClick = { onOpenPlaylist(right) },
							onPlayClick = { onPlayPlaylist(right) },
							onDeleteClick = { onDeletePlaylist(right) },
							modifier = Modifier.weight(1f),
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
private fun NavArtistsSubContent(
	artists: List<IosArtistSummary>,
	loading: Boolean,
	onOpenArtist: (IosArtistSummary) -> Unit,
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
		item(key = "nav-artists-header", contentType = "header") {
			Text(
				text = if (artists.isNotEmpty()) "${artists.size} 位歌手" else "歌手",
				style = MaterialTheme.typography.labelLarge.tabularNums(),
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(bottom = 10.dp),
			)
		}

		if (loading && artists.isEmpty()) {
			item(key = "nav-artists-loading", contentType = "loading") {
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.padding(vertical = 48.dp),
					contentAlignment = Alignment.Center,
				) {
					CircularProgressIndicator()
				}
			}
		} else if (artists.isEmpty()) {
			item(key = "nav-artists-empty", contentType = "empty") {
				IosQuietEmptyState(message = "暂无歌手", icon = Icons.Rounded.Person)
			}
		} else {
			itemsIndexed(
				items = artists,
				key = { _, artist -> artist.id },
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
							Column(
								modifier = Modifier.weight(1f),
								verticalArrangement = Arrangement.spacedBy(2.dp),
							) {
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

@Composable
private fun NavSongsSubContent(
	songs: List<IosTrackItem>,
	loading: Boolean,
	allSongsLoaded: Boolean,
	cardsMode: Boolean,
	sortingLabel: String,
	subsonicManager: IosSubsonicManager,
	audioPlayer: IosAudioPlayer,
	currentTrackId: String?,
	starredIds: Set<String>,
	unstarredIds: Set<String>,
	offlineIds: Set<String>,
	downloadingProgress: Map<String, Float>,
	onOpenSortSheet: () -> Unit,
	onToggleViewMode: () -> Unit,
	onRefresh: () -> Unit,
	onOpenAddToPlaylist: (List<IosTrackItem>) -> Unit,
	onRequestPhysicalDelete: ((IosTrackItem) -> Unit)?,
	onShowNotice: (String) -> Unit,
	bottomPadding: PaddingValues,
) {
	val scope = rememberCoroutineScope()
	var multiSelecting by rememberSaveable { mutableStateOf(false) }
	val selectedIds = remember { mutableStateMapOf<String, Boolean>() }
	var fetchingAllForSelection by remember { mutableStateOf(false) }

	val selectedTracks by remember(songs) {
		derivedStateOf { songs.filter { selectedIds[it.id] == true } }
	}
	val hasOfflineRemovable by remember(selectedTracks, offlineIds, downloadingProgress) {
		derivedStateOf {
			selectedTracks.any { it.id in offlineIds || it.id in downloadingProgress }
		}
	}

	Box(modifier = Modifier.fillMaxSize()) {
		LazyColumn(
			modifier = Modifier.fillMaxSize(),
			contentPadding = PaddingValues(
				start = 16.dp,
				end = 16.dp,
				top = 8.dp,
				bottom = bottomPadding.calculateBottomPadding() + if (multiSelecting) 120.dp else 20.dp,
			),
			verticalArrangement = Arrangement.spacedBy(0.dp),
		) {
			// 单行舒展工具栏：左侧计数 + 右侧多选/简洁中文排序/视图切换（绝不挤折行）
			item(key = "nav-songs-toolbar", contentType = "toolbar") {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(bottom = 10.dp),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						text = when {
							(loading || fetchingAllForSelection) && songs.isEmpty() -> "加载中…"
							songs.isNotEmpty() -> "${songs.size} 首"
							else -> "歌曲"
						},
						style = MaterialTheme.typography.labelLarge.tabularNums(),
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						softWrap = false,
					)
					Row(
						horizontalArrangement = Arrangement.spacedBy(6.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Surface(
							shape = CircleShape,
							color = if (multiSelecting) {
								MaterialTheme.colorScheme.primaryContainer
							} else {
								MaterialTheme.colorScheme.surfaceContainer
							},
							border = iosLibHairlineBorder(),
							modifier = Modifier
								.height(32.dp)
								.clip(CircleShape)
								.clickable {
									multiSelecting = !multiSelecting
									if (!multiSelecting) selectedIds.clear()
								},
						) {
							Box(
								contentAlignment = Alignment.Center,
								modifier = Modifier.padding(horizontal = 11.dp),
							) {
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
						NavToolbarSortPill(label = sortingLabel, onClick = onOpenSortSheet)
						NavToolbarViewToggle(cardsMode = cardsMode, onClick = onToggleViewMode)
					}
				}
			}

			if (loading && songs.isEmpty()) {
				item(key = "nav-songs-loading", contentType = "loading") {
					Box(
						modifier = Modifier
							.fillMaxWidth()
							.padding(vertical = 48.dp),
						contentAlignment = Alignment.Center,
					) {
						CircularProgressIndicator()
					}
				}
			} else if (songs.isEmpty()) {
				item(key = "nav-songs-empty", contentType = "empty") {
					IosQuietEmptyState(
						message = "暂无曲目",
						icon = Icons.Rounded.LibraryMusic,
						actionLabel = "刷新",
						onAction = onRefresh,
					)
				}
			} else if (cardsMode) {
				val rowCount = (songs.size + 1) / 2
				items(
					count = rowCount,
					key = { rowIdx -> "nav-song-grid-${songs[rowIdx * 2].id}" },
					contentType = { "song-grid-row" },
				) { rowIdx ->
					val leftIdx = rowIdx * 2
					val rightIdx = leftIdx + 1
					val left = songs[leftIdx]
					val right = songs.getOrNull(rightIdx)
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(bottom = 12.dp),
						horizontalArrangement = Arrangement.spacedBy(12.dp),
					) {
						NavSongCardTile(
							track = left,
							isPlaying = left.id == currentTrackId,
							isOffline = left.id in offlineIds,
							isSelecting = multiSelecting,
							isSelected = selectedIds[left.id] == true,
							onClick = {
								if (multiSelecting) {
									selectedIds[left.id] = selectedIds[left.id] != true
								} else {
									audioPlayer.playQueue(songs, leftIdx)
								}
							},
							onLongClick = {
								multiSelecting = true
								selectedIds[left.id] = true
							},
							modifier = Modifier.weight(1f),
						)
						if (right != null) {
							NavSongCardTile(
								track = right,
								isPlaying = right.id == currentTrackId,
								isOffline = right.id in offlineIds,
								isSelecting = multiSelecting,
								isSelected = selectedIds[right.id] == true,
								onClick = {
									if (multiSelecting) {
										selectedIds[right.id] = selectedIds[right.id] != true
									} else {
										audioPlayer.playQueue(songs, rightIdx)
									}
								},
								onLongClick = {
									multiSelecting = true
									selectedIds[right.id] = true
								},
								modifier = Modifier.weight(1f),
							)
						} else {
							Spacer(Modifier.weight(1f))
						}
					}
				}
			} else {
				itemsIndexed(
					items = songs,
					key = { idx, track -> "nav-song-${track.id}-$idx" },
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
								isStarred = (track.rawId in starredIds) || (track.isStarred && track.rawId !in unstarredIds),
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

		// 全库真实无截断多选悬浮条
		AnimatedVisibility(
			visible = multiSelecting,
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
				totalCount = songs.size,
				hasOfflineRemovable = hasOfflineRemovable,
				onSelectAll = {
					if (!allSongsLoaded) {
						scope.launch {
							fetchingAllForSelection = true
							try {
								val full = subsonicManager.fetchAllSongs(forceRefresh = false)
								full.forEach { selectedIds[it.id] = true }
							} finally {
								fetchingAllForSelection = false
							}
						}
					} else if (songs.isNotEmpty() && selectedTracks.size >= songs.size) {
						selectedIds.clear()
					} else {
						songs.forEach { selectedIds[it.id] = true }
					}
				},
				onExit = {
					multiSelecting = false
					selectedIds.clear()
				},
				onPlaySelected = {
					val picked = selectedTracks
					if (picked.isNotEmpty()) {
						audioPlayer.playQueue(picked, 0)
						multiSelecting = false
						selectedIds.clear()
					}
				},
				onDownloadSelected = {
					val picked = selectedTracks
					if (picked.isNotEmpty()) {
						audioPlayer.downloadTracksOffline(picked)
						onShowNotice(iosLibOfflineQueueNotice(picked.size))
						multiSelecting = false
						selectedIds.clear()
					}
				},
				onAddToPlaylist = {
					val picked = selectedTracks
					if (picked.isNotEmpty()) {
						onOpenAddToPlaylist(picked)
						multiSelecting = false
						selectedIds.clear()
					}
				},
				onDeleteOfflineSelected = {
					val picked = selectedTracks
					picked.forEach { t ->
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
private fun NavSongCardTile(
	track: IosTrackItem,
	isPlaying: Boolean,
	isOffline: Boolean,
	isSelecting: Boolean,
	isSelected: Boolean,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val qualityTag = remember(track.id, track.bitDepth, track.fileExtension, track.bitRate, track.lossless) {
		iosLibFormatQuietQuality(track.bitDepth, track.fileExtension, track.bitRate, track.lossless)
	}
	val subtitle = remember(track.id, track.artist, qualityTag) {
		listOfNotNull(
			track.artist.takeIf { it.isNotBlank() } ?: "未知歌手",
			qualityTag,
		).joinToString(" · ")
	}
	Surface(
		shape = RoundedCornerShape(14.dp),
		color = when {
			isSelecting && isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f)
			isPlaying -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
			else -> MaterialTheme.colorScheme.surfaceContainer
		},
		border = BorderStroke(
			IosHairlineWidth,
			when {
				isSelecting && isSelected -> MaterialTheme.colorScheme.primary
				isPlaying -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
				else -> iosLibHairlineBorderColor()
			},
		),
		modifier = modifier
			.clip(RoundedCornerShape(14.dp))
			.combinedClickable(onClick = onClick, onLongClick = onLongClick),
	) {
		Column(
			modifier = Modifier.padding(9.dp),
			verticalArrangement = Arrangement.spacedBy(7.dp),
		) {
			Box(modifier = Modifier.fillMaxWidth()) {
				CoverArtBox(
					coverUrl = track.coverUrl,
					modifier = Modifier
						.fillMaxWidth()
						.aspectRatio(1f),
					cornerRadius = 10.dp,
				)
				if (isSelecting) {
					Surface(
						shape = CircleShape,
						color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.55f),
						modifier = Modifier
							.align(Alignment.TopEnd)
							.padding(6.dp)
							.size(22.dp),
					) {
						Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
							if (isSelected) {
								Icon(
									imageVector = Icons.Rounded.Check,
									contentDescription = "已勾选",
									tint = MaterialTheme.colorScheme.onPrimary,
									modifier = Modifier.size(13.dp),
								)
							}
						}
					}
				}
				if (isOffline) {
					Surface(
						shape = CircleShape,
						color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
						modifier = Modifier
							.align(Alignment.BottomStart)
							.padding(6.dp)
							.size(22.dp),
					) {
						Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
							Icon(
								imageVector = Icons.Rounded.OfflinePin,
								contentDescription = "已离线",
								tint = MaterialTheme.colorScheme.primary,
								modifier = Modifier.size(13.dp),
							)
						}
					}
				}
			}
			Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Text(
					text = track.title.ifBlank { "未知歌曲" },
					style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
					color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				Text(
					text = subtitle,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}

@Composable
private fun NavToolbarSortPill(
	label: String,
	onClick: () -> Unit,
) {
	Surface(
		shape = CircleShape,
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = iosLibHairlineBorder(),
		modifier = Modifier
			.height(32.dp)
			.clip(CircleShape)
			.clickable(onClick = onClick),
	) {
		Row(
			modifier = Modifier.padding(horizontal = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(4.dp),
		) {
			Icon(
				imageVector = Icons.Rounded.SwapVert,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(14.dp),
			)
			Text(
				text = label,
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				softWrap = false,
			)
		}
	}
}

@Composable
private fun NavToolbarViewToggle(
	cardsMode: Boolean,
	onClick: () -> Unit,
) {
	Surface(
		shape = CircleShape,
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = iosLibHairlineBorder(),
		modifier = Modifier
			.size(32.dp)
			.clip(CircleShape)
			.clickable(onClick = onClick),
	) {
		Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
			Icon(
				imageVector = if (cardsMode) Icons.Rounded.ViewAgenda else Icons.Rounded.GridView,
				contentDescription = if (cardsMode) "切换列表视图" else "切换卡片视图",
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(15.dp),
			)
		}
	}
}

@Composable
private fun NavSearchResultsContent(
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
	bottomPadding: PaddingValues,
	onRecordSearchResult: () -> Unit = {},
) {
	val scope = rememberCoroutineScope()
	val hasAny = libState.searchArtists.isNotEmpty() ||
		libState.searchAlbums.isNotEmpty() ||
		libState.searchSongs.isNotEmpty()

	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = 8.dp,
			bottom = bottomPadding.calculateBottomPadding() + 20.dp,
		),
		verticalArrangement = Arrangement.spacedBy(10.dp),
	) {
		if (libState.searching) {
			item(key = "nav-search-loading", contentType = "loading") {
				LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
			}
		}

		if (!libState.searching && !hasAny) {
			item(key = "nav-search-empty", contentType = "empty") {
				IosQuietEmptyState(
					message = "未找到与「${libState.searchQuery}」匹配的内容",
					icon = Icons.Rounded.LibraryMusic,
				)
			}
		}

		if (libState.searchArtists.isNotEmpty()) {
			item(key = "nav-search-artists-header", contentType = "section-header") {
				IosSectionLabel(title = "歌手 · ${libState.searchArtists.size}")
			}
			item(key = "nav-search-artists-row", contentType = "carousel") {
				LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					items(
						items = libState.searchArtists,
						key = { it.id },
						contentType = { "artist-chip" },
					) { artist ->
						AssistChip(
							onClick = {
								onRecordSearchResult()
								onOpenArtist(artist)
							},
							label = { Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
							leadingIcon = {
								Icon(Icons.Rounded.Person, contentDescription = null, modifier = Modifier.size(16.dp))
							},
						)
					}
				}
			}
		}

		if (libState.searchAlbums.isNotEmpty()) {
			item(key = "nav-search-albums-header", contentType = "section-header") {
				IosSectionLabel(title = "专辑 · ${libState.searchAlbums.size}")
			}
			item(key = "nav-search-albums-row", contentType = "carousel") {
				LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
					items(
						items = libState.searchAlbums,
						key = { it.id },
						contentType = { "album-card" },
					) { album ->
						IosLibAlbumGridTile(
							album = album,
							onClick = {
								onRecordSearchResult()
								onOpenAlbum(album)
							},
							modifier = Modifier.width(142.dp),
						)
					}
				}
			}
		}

		if (libState.searchSongs.isNotEmpty()) {
			item(key = "nav-search-songs-header", contentType = "section-header") {
				IosSectionLabel(title = "歌曲 · ${libState.searchSongs.size}")
			}
			item(key = "nav-search-songs-group", contentType = "track-group") {
				IosGroupedCard {
					libState.searchSongs.forEachIndexed { idx, track ->
						IosLibTrackRowContent(
							track = track,
							isPlaying = track.id == currentTrackId,
							isStarred = subsonicManager.isStarred(track),
							isOffline = track.id in offlineIds,
							downloadProgress = downloadingProgress[track.id],
							multiSelectMode = false,
							isSelected = false,
							onClick = {
								onRecordSearchResult()
								audioPlayer.playQueue(libState.searchSongs, idx)
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
						if (idx < libState.searchSongs.lastIndex) {
							IosHairlineDivider(startIndent = 66.dp)
						}
					}
				}
			}
		}
	}
}

// =========================================================================
// Navidrome 服务器多账号管理 / 加入歌单 / 物理文件删除对话框
// =========================================================================

@Composable
internal fun IosLibNavidromeServerSheet(
	servers: List<NavidromeServerProfile>,
	activeServerId: String?,
	initialEditing: NavidromeServerProfile?,
	onSwitchServer: (String) -> Unit,
	onEditServer: (NavidromeServerProfile) -> Unit,
	onRemoveServer: (String) -> Unit,
	onTestAndSave: suspend (name: String, url: String, user: String, pass: String, editId: String?) -> Result<NavidromeServerProfile>,
	onDismiss: () -> Unit,
) {
	val scope = rememberCoroutineScope()
	var showForm by remember(initialEditing) { mutableStateOf(servers.isEmpty() || initialEditing != null) }
	var name by remember(initialEditing) { mutableStateOf(initialEditing?.name.orEmpty()) }
	var url by remember(initialEditing) { mutableStateOf(initialEditing?.url.orEmpty()) }
	var username by remember(initialEditing) { mutableStateOf(initialEditing?.username.orEmpty()) }
	var password by remember(initialEditing) { mutableStateOf(initialEditing?.password.orEmpty()) }
	var testing by remember { mutableStateOf(false) }
	var errorMsg by remember { mutableStateOf<String?>(null) }

	ModalBottomSheet(onDismissRequest = onDismiss) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.navigationBarsPadding()
				.imePadding()
				.padding(horizontal = 20.dp, vertical = 12.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text("Navidrome 服务器", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
				if (!showForm) {
					TextButton(onClick = { showForm = true }) { Text("新增账号") }
				}
			}

			if (servers.isNotEmpty() && !showForm) {
				IosGroupedCard {
					servers.forEachIndexed { idx, srv ->
						val isCurrent = srv.id == activeServerId
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.clickable { onSwitchServer(srv.id) }
								.padding(horizontal = 12.dp, vertical = 10.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(10.dp),
						) {
							Icon(
								imageVector = if (isCurrent) Icons.Rounded.Check else Icons.Rounded.Dns,
								contentDescription = null,
								tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
								modifier = Modifier.size(18.dp),
							)
							Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
								Text(
									text = srv.name,
									style = MaterialTheme.typography.titleSmall.copy(
										fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
									),
									color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
								)
								Text(
									text = "${srv.username} · ${srv.url}",
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
								)
							}
							IconButton(
								onClick = {
									onEditServer(srv)
									showForm = true
								},
								modifier = Modifier.size(32.dp),
							) {
								Icon(Icons.Rounded.Edit, contentDescription = "编辑", modifier = Modifier.size(17.dp))
							}
							IconButton(
								onClick = { onRemoveServer(srv.id) },
								modifier = Modifier.size(32.dp),
							) {
								Icon(
									Icons.Rounded.Delete,
									contentDescription = "移除",
									tint = MaterialTheme.colorScheme.error,
									modifier = Modifier.size(17.dp),
								)
							}
						}
						if (idx < servers.lastIndex) {
							IosHairlineDivider(startIndent = 40.dp)
						}
					}
				}
			}

			if (showForm) {
				OutlinedTextField(
					value = name,
					onValueChange = { name = it },
					label = { Text("别名（可选）") },
					singleLine = true,
					shape = RoundedCornerShape(12.dp),
					modifier = Modifier.fillMaxWidth(),
				)
				OutlinedTextField(
					value = url,
					onValueChange = { url = it },
					label = { Text("服务器地址（如 https://music.example.com）") },
					singleLine = true,
					shape = RoundedCornerShape(12.dp),
					modifier = Modifier.fillMaxWidth(),
				)
				OutlinedTextField(
					value = username,
					onValueChange = { username = it },
					label = { Text("用户名") },
					singleLine = true,
					shape = RoundedCornerShape(12.dp),
					modifier = Modifier.fillMaxWidth(),
				)
				OutlinedTextField(
					value = password,
					onValueChange = { password = it },
					label = { Text("密码") },
					visualTransformation = PasswordVisualTransformation(),
					singleLine = true,
					shape = RoundedCornerShape(12.dp),
					modifier = Modifier.fillMaxWidth(),
				)
				if (errorMsg != null) {
					Text(errorMsg!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
				}
				Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
					if (servers.isNotEmpty()) {
						OutlinedButton(onClick = { showForm = false }, modifier = Modifier.weight(1f)) {
							Text("返回列表")
						}
					}
					Button(
						onClick = {
							scope.launch {
								testing = true
								errorMsg = null
								val res = onTestAndSave(name, url, username, password, initialEditing?.id)
								testing = false
								res.onSuccess { onDismiss() }
									.onFailure { errorMsg = it.message ?: "连接失败" }
							}
						},
						enabled = !testing,
						modifier = Modifier.weight(1f),
					) {
						if (testing) {
							CircularProgressIndicator(
								modifier = Modifier.size(18.dp),
								strokeWidth = 2.dp,
								color = MaterialTheme.colorScheme.onPrimary,
							)
						} else {
							Text("连接并保存")
						}
					}
				}
			}
			Spacer(Modifier.height(14.dp))
		}
	}
}

@Composable
internal fun IosLibNavAddToPlaylistSheet(
	playlists: List<IosPlaylistSummary>,
	trackCount: Int,
	onSelectPlaylist: (IosPlaylistSummary) -> Unit,
	onCreateAndAdd: (String) -> Unit,
	onDismiss: () -> Unit,
) {
	var newName by remember { mutableStateOf("") }
	ModalBottomSheet(onDismissRequest = onDismiss) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.navigationBarsPadding()
				.imePadding()
				.padding(horizontal = 20.dp, vertical = 12.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Text("加入歌单（$trackCount 首）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
						if (clean.isNotEmpty()) onCreateAndAdd(clean)
					},
					enabled = newName.isNotBlank(),
				) {
					Text("新建")
				}
			}
			IosHairlineDivider()
			if (playlists.isEmpty()) {
				IosQuietEmptyState(message = "暂无歌单", icon = Icons.Rounded.QueueMusic)
			} else {
				LazyColumn(
					modifier = Modifier
						.fillMaxWidth()
						.height(260.dp),
					verticalArrangement = Arrangement.spacedBy(0.dp),
				) {
					itemsIndexed(
						items = playlists,
						key = { _, p -> p.id },
						contentType = { _, _ -> "playlist-pick" },
					) { idx, pl ->
						val isFirst = idx == 0
						val isLast = idx == playlists.lastIndex
						Surface(
							shape = iosLibGroupedItemShape(isFirst, isLast),
							color = MaterialTheme.colorScheme.surfaceContainer,
							modifier = Modifier
								.fillMaxWidth()
								.clip(iosLibGroupedItemShape(isFirst, isLast))
								.clickable { onSelectPlaylist(pl) },
						) {
							Column {
								Row(
									modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.spacedBy(10.dp),
								) {
									CoverArtBox(coverUrl = pl.coverUrl, size = 40.dp, cornerRadius = 8.dp)
									Column(modifier = Modifier.weight(1f)) {
										Text(
											text = pl.name,
											style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
											maxLines = 1,
											overflow = TextOverflow.Ellipsis,
										)
										Text(
											text = "${pl.songCount} 首",
											style = MaterialTheme.typography.bodySmall.tabularNums(),
											color = MaterialTheme.colorScheme.onSurfaceVariant,
										)
									}
								}
								if (!isLast) {
									IosHairlineDivider(startIndent = 62.dp)
								}
							}
						}
					}
				}
			}
			Spacer(Modifier.height(12.dp))
		}
	}
}

@Composable
internal fun IosLibNavPhysicalDeleteDialog(
	track: IosTrackItem,
	preview: MeloNavidromeDeletionPreview?,
	loadingPreview: Boolean,
	deleting: Boolean,
	onRetryPreview: () -> Unit,
	onDismiss: () -> Unit,
	onConfirmDelete: (String) -> Unit,
) {
	AlertDialog(
		onDismissRequest = { if (!deleting) onDismiss() },
		icon = {
			Icon(
				imageVector = Icons.Rounded.Delete,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.error,
			)
		},
		title = { Text("从磁盘删除源文件") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
				Text(
					text = "${track.title} · ${track.artist}",
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.SemiBold,
				)
				when {
					loadingPreview -> {
						Row(
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(10.dp),
						) {
							CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
							Text(
								text = "正在查询磁盘路径…",
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					}

					preview != null -> {
						Surface(
							shape = RoundedCornerShape(12.dp),
							color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
							border = BorderStroke(IosHairlineWidth, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
							modifier = Modifier.fillMaxWidth(),
						) {
							Column(
								modifier = Modifier.padding(10.dp),
								verticalArrangement = Arrangement.spacedBy(4.dp),
							) {
								Text(
									text = "服务器磁盘文件路径",
									style = MaterialTheme.typography.labelSmall,
									fontWeight = FontWeight.SemiBold,
									color = MaterialTheme.colorScheme.error,
								)
								Text(
									text = preview.path.ifBlank { "未知路径" },
									style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
									color = MaterialTheme.colorScheme.onSurface,
								)
							}
						}
						Text(
							text = "仅删除音频原文件并自动触发扫库，删除后无法恢复。",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}

					else -> {
						Text(
							text = "未能获取到物理文件删除预览。",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}
			}
		},
		confirmButton = {
			when {
				preview != null -> {
					Button(
						onClick = { onConfirmDelete(preview.revision) },
						enabled = !deleting && preview.revision.isNotBlank(),
						colors = ButtonDefaults.buttonColors(
							containerColor = MaterialTheme.colorScheme.error,
							contentColor = MaterialTheme.colorScheme.onError,
						),
					) {
						if (deleting) {
							CircularProgressIndicator(
								modifier = Modifier.size(14.dp),
								strokeWidth = 2.dp,
								color = MaterialTheme.colorScheme.onError,
							)
							Spacer(Modifier.width(6.dp))
							Text("正在删除…")
						} else {
							Text("确认删除")
						}
					}
				}

				!loadingPreview -> {
					Button(onClick = onRetryPreview) { Text("重试") }
				}
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss, enabled = !deleting) {
				Text("取消")
			}
		},
	)
}
