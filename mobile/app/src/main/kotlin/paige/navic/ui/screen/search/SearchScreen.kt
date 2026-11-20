/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.screen.search

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.insert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import paige.navic.R
import paige.navic.di.LocalBottomBarScrollManager
import paige.navic.di.LocalSizeClass
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.manager.session.DownloadManager
import paige.navic.domain.model.DomainAlbum
import paige.navic.domain.model.DomainAlbumListType
import paige.navic.domain.model.DomainArtist
import paige.navic.domain.model.DomainArtistListType
import paige.navic.domain.model.DomainSong
import paige.navic.domain.model.DomainSongCollection
import paige.navic.domain.model.settings.BottomBarVisibilityMode
import paige.navic.domain.model.settings.ListViewMode
import paige.navic.playback.MediaPlayer
import paige.navic.ui.component.common.ContentUnavailable
import paige.navic.ui.component.common.ErrorBox
import paige.navic.ui.component.dialog.DeleteDownloadTarget
import paige.navic.ui.component.dialog.QueueDuplicateDialog
import paige.navic.ui.component.dialog.ShareDialog
import paige.navic.ui.component.dialog.rememberDownloadDeletionHandler
import paige.navic.ui.component.layout.ArtGrid
import paige.navic.ui.component.layout.RootBottomBar
import paige.navic.ui.component.layout.artGridPlaceholder
import paige.navic.ui.component.layout.horizontalSection
import paige.navic.ui.core.UiState
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.Close
import paige.navic.ui.icons.outlined.History
import paige.navic.ui.navigation.PersistentViewModelStoreOwner
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screen.album.component.AlbumListScreenGridItem
import paige.navic.ui.screen.album.viewmodel.AlbumListViewModel
import paige.navic.ui.screen.artist.ArtistListScreenGridItem
import paige.navic.ui.screen.artist.viewmodel.ArtistListViewModel
import paige.navic.ui.screen.search.component.SearchScreenChips
import paige.navic.ui.screen.search.component.SearchScreenTopBar
import paige.navic.ui.screen.search.viewmodel.SearchViewModel
import paige.navic.ui.screen.song.component.SongListScreenItem
import paige.navic.ui.viewmodel.RootViewModel

enum class SearchCategory(@StringRes val res: Int) {
	ALL(R.string.title_all),
	SONGS(R.string.title_songs),
	ALBUMS(R.string.title_albums),
	ARTISTS(R.string.title_artists)
}

// TODO: clean this up, holy shit
@Composable
fun SearchScreen(
	nested: Boolean
) {
	val preferenceManager = koinInject<PreferenceManager>()
	val downloadManager = koinInject<DownloadManager>()
	val allDownloads by downloadManager.allDownloads.collectAsStateWithLifecycle(initialValue = emptyList())
	val downloadsMap = remember(allDownloads) { allDownloads.associateBy { it.songId } }

	val viewModel = koinViewModel<SearchViewModel>(
		viewModelStoreOwner = if (nested) {
			LocalViewModelStoreOwner.current!!
		} else {
			koinInject<PersistentViewModelStoreOwner>()
		}
	)
	val selectedSong by viewModel.selectedSong.collectAsStateWithLifecycle()
	val selectedSongIsStarred by viewModel.selectedSongIsStarred.collectAsStateWithLifecycle()
	val selectedSongRating by viewModel.selectedSongRating.collectAsStateWithLifecycle()

	val artistListViewModel = koinViewModel<ArtistListViewModel> {
		parametersOf(DomainArtistListType.AlphabeticalByName)
	}
	val artistListSelection by artistListViewModel.uiState.collectAsStateWithLifecycle()

	val albumListViewModel = koinViewModel<AlbumListViewModel> {
		parametersOf(DomainAlbumListType.AlphabeticalByName)
	}
	val albumListSelection by albumListViewModel.uiState.collectAsStateWithLifecycle()

	val query = viewModel.searchQuery
	val state by viewModel.searchState.collectAsStateWithLifecycle()
	val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()

	val player = koinInject<MediaPlayer>()

	var selectedCategory by remember { mutableStateOf(SearchCategory.ALL) }
	var songToQueue by remember { mutableStateOf<DomainSong?>(null) }
	var shareId by remember { mutableStateOf<String?>(null) }
	var manageHistory by remember { mutableStateOf(false) }
	var clearHistoryConfirmOpen by remember { mutableStateOf(false) }
	val downloadDeletionHandler = rememberDownloadDeletionHandler()

	LaunchedEffect(searchHistory.isEmpty()) {
		if (searchHistory.isEmpty()) {
			manageHistory = false
		}
	}

	val recordCurrentQuery: () -> Unit = {
		val currentText = query.text.toString().trim()
		if (currentText.isNotEmpty()) {
			viewModel.addToSearchHistory(currentText)
		}
	}

	val rootViewModel = koinViewModel<RootViewModel>()
	LaunchedEffect(Unit) {
		rootViewModel.events.collect { event ->
			if (event is RootViewModel.Event.ScrollToTop) {
				viewModel.gridState.animateScrollToItem(0)
			}
		}
	}

	Scaffold(
		topBar = {
			Column(
				modifier = Modifier
					.background(MaterialTheme.colorScheme.surface)
					.padding(
						TopAppBarDefaults.windowInsets.asPaddingValues()
					)
			) {
				SearchScreenTopBar(
					query = query,
					nested = nested,
					onSearch = { submittedQuery ->
						viewModel.addToSearchHistory(submittedQuery)
					}
				)
				SearchScreenChips(
					selectedCategory = selectedCategory,
					onCategorySelect = { selectedCategory = it }
				)
			}
		},
		bottomBar = {
			val sizeClass = LocalSizeClass.current
			val scrollManager = LocalBottomBarScrollManager.current
			val preferVisible =
				preferenceManager.bottomBarVisibilityMode == BottomBarVisibilityMode.AllScreens
			if (!nested
				|| (sizeClass.widthSizeClass < WindowWidthSizeClass.Medium && preferVisible)
			) {
				RootBottomBar(scrolled = scrollManager.isTriggered)
			}
		}
	) { contentPadding ->
		AnimatedContent(
			state,
			modifier = Modifier.fillMaxSize()
		) { uiState ->
			when (uiState) {
				is UiState.Loading -> ArtGrid(
					contentPadding = contentPadding,
					selectedViewMode = ListViewMode.List
				) {
					artGridPlaceholder(viewMode = ListViewMode.List)
				}

				is UiState.Error -> ErrorBox(uiState, padding = contentPadding)
				is UiState.Success -> {
					val results = uiState.data
					val showAll = selectedCategory == SearchCategory.ALL
					val albums =
						if (showAll || selectedCategory == SearchCategory.ALBUMS) results.filterIsInstance<DomainAlbum>() else emptyList()
					val artists =
						if (showAll || selectedCategory == SearchCategory.ARTISTS) results.filterIsInstance<DomainArtist>() else emptyList()
					val songs =
						if (showAll || selectedCategory == SearchCategory.SONGS) results.filterIsInstance<DomainSong>() else emptyList()

					if (query.text.isNotBlank() && albums.isEmpty() && artists.isEmpty() && songs.isEmpty()) {
						ContentUnavailable(
							label = stringResource(R.string.info_no_search_results)
						)
					}

					LazyVerticalGrid(
						modifier = Modifier.fillMaxSize(),
						columns = GridCells.Fixed(2),
						contentPadding = contentPadding,
						state = viewModel.gridState,
						verticalArrangement = Arrangement.spacedBy(8.dp)
					) {
						if (query.text.isNotBlank()) {
							if (songs.isNotEmpty()) {
								item(span = { GridItemSpan(maxLineSpan) }) {
									Text(
										stringResource(R.string.title_songs),
										style = MaterialTheme.typography.headlineSmall,
										modifier = Modifier.padding(
											horizontal = 16.dp,
											vertical = 8.dp
										)
									)
								}
								val displaySongs = if (showAll) songs.take(10) else songs
								items(
									displaySongs.size,
									span = { GridItemSpan(maxLineSpan) }
								) { index ->
									val song = displaySongs[index]
									val download = downloadsMap[song.id]

									SongListScreenItem(
										modifier = Modifier.animateItem(),
										song = song,
										selected = selectedSong == song,
										starred = if (selectedSong == song) selectedSongIsStarred else song.starredAt != null,
										rating = if (selectedSong == song) selectedSongRating else (song.userRating
											?: 0),
										onSelect = {
											recordCurrentQuery()
											viewModel.selectSong(song)
										},
										onDeselect = { viewModel.clearSelectedSong() },
										onSetStarred = { viewModel.starSelectedSong(it) },
										onSetShareId = { shareId = it },
										onPlayNext = {
											recordCurrentQuery()
											if (player.checkDuplicate(song.id, preferenceManager)) {
												songToQueue = song
											} else {
												player.playNextSingle(song)
											}
										},
										onAddToQueue = {
											recordCurrentQuery()
											if (player.checkDuplicate(song.id, preferenceManager)) {
												songToQueue = song
											} else {
												player.addToQueueSingle(song)
											}
										},
										onClick = {
											recordCurrentQuery()
											player.playNow(song)
										},
										onSetRating = { viewModel.rateSelectedSong(it) },
										download = download,
										onDownload = { downloadManager.downloadSong(song.id) },
										onCancelDownload = { downloadManager.cancelDownload(song.id) },
										onDeleteDownload = {
											downloadDeletionHandler.requestDelete(DeleteDownloadTarget.Single(song.id, song.title))
										}
									)
								}
							}

							horizontalSection(
								title = R.string.title_albums,
								destination = Screen.AlbumList(true),
								state = albums,
								seeAll = false
							) { album ->
								AlbumListScreenGridItem(
									modifier = Modifier
										.animateItem(fadeInSpec = null)
										.width(150.dp),
									tab = "search",
									album = album,
									selected = album == albumListSelection.data?.selected,
									starred = albumListSelection.data?.selected?.starredAt != null,
									onSelect = {
										recordCurrentQuery()
										albumListViewModel.selected = album
									},
									onDeselect = {
										albumListViewModel.selected = null
									},
									onSetStarred = { albumListViewModel.starAlbum(it) },
									onSetShareId = { },
									onPlayNext = {
										recordCurrentQuery()
										player.playNext(album as DomainSongCollection)
									},
									onAddToQueue = {
										recordCurrentQuery()
										player.addToQueue(album as DomainSongCollection)
									},
									rating = albumListSelection.data?.selected?.userRating ?: 0,
									onSetRating = { albumListViewModel.setRating(it) },
									onOpen = recordCurrentQuery
								)
							}

							horizontalSection(
								title = R.string.title_artists,
								destination = Screen.ArtistList(true),
								state = artists,
								seeAll = false
							) { artist ->
								ArtistListScreenGridItem(
									modifier = Modifier
										.animateItem(fadeInSpec = null)
										.width(150.dp),
									tab = "search",
									artist = artist,
									selected = artist == artistListSelection.data?.selected,
									selectedArtistAlbums = emptyList(),
									starred = artistListSelection.data?.selected?.starredAt != null,
									onSelect = {
										recordCurrentQuery()
										artistListViewModel.selectArtist(artist)
									},
									onDeselect = { artistListViewModel.clearSelection() },
									onSetStarred = { artistListViewModel.starArtist(it) },
									onPlayNext = {
										recordCurrentQuery()
										artistListViewModel.playArtistAlbumsNext(player)
									},
									onAddToQueue = {
										recordCurrentQuery()
										artistListViewModel.addArtistAlbumsToQueue(
											player
										)
									},
									onOpen = recordCurrentQuery
								)
							}
						} else {
							if (searchHistory.isNotEmpty()) {
								item(span = { GridItemSpan(maxLineSpan) }) {
									Row(
										modifier = Modifier
											.fillMaxWidth()
											.padding(horizontal = 20.dp, vertical = 10.dp),
										verticalAlignment = Alignment.CenterVertically,
										horizontalArrangement = Arrangement.SpaceBetween
									) {
										Text(
											text = stringResource(R.string.action_search_history),
											style = MaterialTheme.typography.titleMedium,
											color = MaterialTheme.colorScheme.primary
										)
										Row(
											verticalAlignment = Alignment.CenterVertically,
											horizontalArrangement = Arrangement.spacedBy(8.dp)
										) {
											Text(
												text = if (manageHistory) "完成" else "管理",
												style = MaterialTheme.typography.labelLarge,
												color = MaterialTheme.colorScheme.primary,
												modifier = Modifier
													.clip(CircleShape)
													.clickable { manageHistory = !manageHistory }
													.padding(horizontal = 8.dp, vertical = 4.dp)
											)
											Text(
												text = "清空",
												style = MaterialTheme.typography.labelLarge,
												color = MaterialTheme.colorScheme.error,
												modifier = Modifier
													.clip(CircleShape)
													.clickable { clearHistoryConfirmOpen = true }
													.padding(horizontal = 8.dp, vertical = 4.dp)
											)
										}
									}
								}
								item(span = { GridItemSpan(maxLineSpan) }) {
									FlowRow(
										modifier = Modifier
											.fillMaxWidth()
											.padding(horizontal = 20.dp, vertical = 4.dp),
										horizontalArrangement = Arrangement.spacedBy(8.dp),
										verticalArrangement = Arrangement.spacedBy(8.dp)
									) {
										searchHistory.forEach { historyItem ->
											Surface(
												onClick = {
													if (manageHistory) {
														viewModel.removeFromSearchHistory(historyItem)
													} else {
														query.clearText()
														query.edit { insert(0, historyItem) }
														viewModel.addToSearchHistory(historyItem)
													}
												},
												shape = CircleShape,
												color = MaterialTheme.colorScheme.surfaceContainer,
												border = BorderStroke(
													width = 1.dp,
													color = if (manageHistory) {
														MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
													} else {
														MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
													}
												)
											) {
												Row(
													modifier = Modifier.padding(
														start = 12.dp,
														end = if (manageHistory) 8.dp else 12.dp,
														top = 7.dp,
														bottom = 7.dp
													),
													verticalAlignment = Alignment.CenterVertically,
													horizontalArrangement = Arrangement.spacedBy(6.dp)
												) {
													Icon(
														imageVector = Icons.Outlined.History,
														contentDescription = null,
														tint = MaterialTheme.colorScheme.onSurfaceVariant,
														modifier = Modifier.size(14.dp)
													)
													Text(
														text = historyItem,
														style = MaterialTheme.typography.bodySmall,
														color = MaterialTheme.colorScheme.onSurface,
														maxLines = 1,
														overflow = TextOverflow.Ellipsis
													)
													if (manageHistory) {
														Icon(
															imageVector = Icons.Outlined.Close,
															contentDescription = stringResource(R.string.action_remove_from_history),
															tint = MaterialTheme.colorScheme.onSurfaceVariant,
															modifier = Modifier.size(14.dp)
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
				}
			}
		}
	}

	if (clearHistoryConfirmOpen) {
		AlertDialog(
			onDismissRequest = { clearHistoryConfirmOpen = false },
			title = { Text("清空搜索历史") },
			text = { Text("确认清空所有曲库搜索历史记录吗？") },
			confirmButton = {
				Button(
					onClick = {
						clearHistoryConfirmOpen = false
						manageHistory = false
						viewModel.clearSearchHistory()
					},
					colors = ButtonDefaults.buttonColors(
						containerColor = MaterialTheme.colorScheme.error,
						contentColor = MaterialTheme.colorScheme.onError
					)
				) {
					Text("清空")
				}
			},
			dismissButton = {
				TextButton(onClick = { clearHistoryConfirmOpen = false }) {
					Text("取消")
				}
			}
		)
	}

	QueueDuplicateDialog(
		song = songToQueue,
		onDismissRequest = { songToQueue = null }
	)

	ShareDialog(
		id = shareId,
		onDismissRequest = {
			shareId = null
			viewModel.clearSelectedSong()
		}
	)
}
