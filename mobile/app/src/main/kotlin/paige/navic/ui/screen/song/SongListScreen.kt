/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.screen.song

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import paige.navic.R
import paige.navic.R.string.info_no_songs
import paige.navic.di.LocalBottomBarScrollManager
import paige.navic.di.LocalSizeClass
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.manager.session.DownloadManager
import paige.navic.domain.model.DomainSong
import paige.navic.domain.model.DomainSongListType
import paige.navic.domain.model.settings.BottomBarVisibilityMode
import paige.navic.domain.model.settings.ListViewMode
import paige.navic.playback.MediaPlayer
import paige.navic.ui.component.common.ContentUnavailable
import paige.navic.ui.component.common.ErrorBox
import paige.navic.ui.component.common.TooltipBox
import paige.navic.ui.component.dialog.DeleteDownloadTarget
import paige.navic.ui.component.dialog.QueueDuplicateDialog
import paige.navic.ui.component.dialog.ShareDialog
import paige.navic.ui.component.dialog.rememberDownloadDeletionHandler
import paige.navic.ui.component.layout.ArtGridPlaceholder
import paige.navic.ui.component.layout.NestedTopBar
import paige.navic.ui.component.layout.PullToRefreshBox
import paige.navic.ui.component.layout.RootBottomBar
import paige.navic.ui.component.layout.RootTopBar
import paige.navic.ui.component.layout.TopBarButton
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.Check
import paige.navic.ui.icons.outlined.Checklist
import paige.navic.ui.icons.outlined.Close
import paige.navic.ui.icons.outlined.Queue
import paige.navic.ui.icons.outlined.PlaylistAdd
import paige.navic.ui.screen.playlist.dialog.PlaylistUpdateDialog
import paige.navic.ui.core.UiState
import paige.navic.ui.navigation.PersistentViewModelStoreOwner
import paige.navic.ui.screen.song.component.SongListScreenItem
import paige.navic.ui.screen.song.component.SongListScreenSortButton
import paige.navic.ui.screen.song.viewmodel.SongListViewModel
import paige.navic.ui.util.withoutTop
import paige.navic.ui.viewmodel.RootViewModel

@Composable
fun SongListScreen(
	nested: Boolean,
	listType: DomainSongListType? = null
) {
	val viewModel = koinViewModel<SongListViewModel>(
		key = listType.toString(),
		parameters = { parametersOf(listType) },
		viewModelStoreOwner = if (nested) {
			LocalViewModelStoreOwner.current!!
		} else {
			koinInject<PersistentViewModelStoreOwner>()
		}
	)
	val preferenceManager = koinInject<PreferenceManager>()
	val player = koinInject<MediaPlayer>()
	val state by viewModel.uiState.collectAsStateWithLifecycle()
	val lazyPagingItems = state.data?.items?.collectAsLazyPagingItems()
	val downloadManager = koinInject<DownloadManager>()

	val selectedSorting by viewModel.selectedSorting.collectAsStateWithLifecycle()
	val selectedReversed by viewModel.selectedReversed.collectAsStateWithLifecycle()
	val selectedFilters by viewModel.selectedFilters.collectAsStateWithLifecycle()
	val allDownloads by downloadManager.allDownloads.collectAsStateWithLifecycle(
		initialValue = emptyList()
	)

	var shareId by remember { mutableStateOf<String?>(null) }
	var songToQueue by remember { mutableStateOf<DomainSong?>(null) }
	var selecting by rememberSaveable { mutableStateOf(false) }
	var selectedIds by rememberSaveable { mutableStateOf(listOf<String>()) }
	var selectionOrder by remember { mutableStateOf(listOf<String>()) }
	var preparingSelection by remember { mutableStateOf(false) }
	var selectionError by remember { mutableStateOf<Exception?>(null) }
	var playlistIds by remember { mutableStateOf<List<String>?>(null) }
	var queueSongs by remember { mutableStateOf<List<DomainSong>?>(null) }
	val selectedIdSet = remember(selectedIds) { selectedIds.toSet() }
	val scope = rememberCoroutineScope()
	val finishSelection = {
		selecting = false
		selectedIds = emptyList<String>()
		selectionError = null
	}
	LaunchedEffect(selecting) {
		if (selecting) {
			preparingSelection = true
			try {
				selectionOrder = viewModel.getSelectionOrder()
				val availableIds = selectionOrder.toSet()
				selectedIds = selectedIds.filter { it in availableIds }
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				selectionError = e
			} finally {
				preparingSelection = false
			}
		}
	}
	BackHandler(selecting && playlistIds == null && queueSongs == null && !preparingSelection) { finishSelection() }
	fun addSelectionToQueue() {
		scope.launch {
			preparingSelection = true
			selectionError = null
			try {
				val songs = viewModel.getSelectedSongs(selectionOrder.filter { it in selectedIdSet })
				if (songs.any { player.checkDuplicate(it.id, preferenceManager) }) {
					queueSongs = songs
				} else {
					player.addToQueue(songs)
					finishSelection()
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				selectionError = e
			} finally {
				preparingSelection = false
			}
		}
	}
	val downloadDeletionHandler = rememberDownloadDeletionHandler()
	val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

	val actions: @Composable RowScope.() -> Unit = {
		val selectLabel = stringResource(R.string.action_select_songs)
		TooltipBox(selectLabel) {
			if (nested) {
				TopBarButton(onClick = { viewModel.clear(); selecting = true }, enabled = (lazyPagingItems?.itemCount ?: 0) > 0) {
					Icon(Icons.Outlined.Checklist, selectLabel)
				}
			} else {
				IconButton(onClick = { viewModel.clear(); selecting = true }, enabled = (lazyPagingItems?.itemCount ?: 0) > 0) {
					Icon(Icons.Outlined.Checklist, selectLabel)
				}
			}
		}
		SongListScreenSortButton(
			nested = nested,
			selectedSorting = selectedSorting,
			onSetSorting = { viewModel.setSorting(it) },
			selectedReversed = selectedReversed,
			onSetReversed = { viewModel.setReversed(it) },
			selectedFilters = selectedFilters,
			onToggleFilter = { viewModel.toggleFilter(it) }
		)
	}

	val rootViewModel = koinViewModel<RootViewModel>()
	val listState = rememberLazyListState()
	LaunchedEffect(Unit) {
		rootViewModel.events.collect { event ->
			if (event is RootViewModel.Event.ScrollToTop) {
				listState.animateScrollToItem(0)
			}
		}
	}

	val downloadsMap = remember(allDownloads) { allDownloads.associateBy { it.songId } }

	Scaffold(
		topBar = {
			if (selecting) {
				Column {
					NestedTopBar(
						title = { Text(stringResource(R.string.info_selected_songs, selectedIds.size)) },
						navigationAction = {
							TooltipBox(stringResource(R.string.action_cancel)) {
								TopBarButton(
									modifier = Modifier.padding(start = 20.dp, end = 13.dp),
									onClick = { finishSelection() }, enabled = !preparingSelection
								) { Icon(Icons.Outlined.Close, stringResource(R.string.action_cancel)) }
							}
						},
						actions = {
							val allSelected = selectionOrder.isNotEmpty() && selectionOrder.all { it in selectedIdSet }
							val label = stringResource(if (allSelected) R.string.action_deselect_all_songs else R.string.action_select_all_songs)
							TooltipBox(label) {
								IconToggleButton(
									checked = allSelected,
									enabled = !preparingSelection && selectionOrder.isNotEmpty(),
									onCheckedChange = { selectedIds = if (allSelected) emptyList() else selectionOrder }
								) { Icon(if (allSelected) Icons.Outlined.Check else Icons.Outlined.Checklist, label) }
							}
						}
					)
					FlowRow(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						TooltipBox(stringResource(R.string.action_add_to_queue)) {
							FilledTonalIconButton(enabled = selectedIds.isNotEmpty() && !preparingSelection, onClick = { addSelectionToQueue() }) {
								Icon(Icons.Outlined.Queue, stringResource(R.string.action_add_to_queue))
							}
						}
						TooltipBox(stringResource(R.string.action_add_to_playlist)) {
							FilledTonalIconButton(enabled = selectedIds.isNotEmpty() && !preparingSelection, onClick = {
								playlistIds = selectionOrder.filter { it in selectedIdSet }
							}) { Icon(Icons.Outlined.PlaylistAdd, stringResource(R.string.action_add_to_playlist)) }
						}
					}
					selectionError?.let { ErrorBox(UiState.Error<Unit>(it)) }
				}
			} else if (!nested) {
				RootTopBar(
					title = { Text(stringResource(R.string.title_songs)) },
					scrollBehavior = scrollBehavior,
					actions = actions
				)
			} else {
				NestedTopBar(
					title = { Text(stringResource(R.string.title_songs)) },
					actions = actions
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
	) { innerPadding ->
		val isLoading = state is UiState.Loading || lazyPagingItems?.loadState?.refresh is LoadState.Loading
		val itemCount = lazyPagingItems?.itemCount ?: 0

		PullToRefreshBox(
			modifier = Modifier
				.padding(top = innerPadding.calculateTopPadding())
				.background(MaterialTheme.colorScheme.surface),
			finished = !isLoading,
			onRefresh = { finishSelection(); viewModel.refreshSongs(true) },
			key = state
		) {
			LazyColumn(
				modifier = if (!nested)
					Modifier
						.fillMaxSize()
						.nestedScroll(scrollBehavior.nestedScrollConnection)
				else Modifier.fillMaxSize(),
				contentPadding = innerPadding.withoutTop(),
				verticalArrangement = if (itemCount == 0 && !isLoading)
					Arrangement.Center
				else Arrangement.spacedBy(12.dp),
				state = listState
			) {
				if (itemCount > 0 && lazyPagingItems != null) {
					items(itemCount) { index ->
						val song = lazyPagingItems[index] ?: return@items

						val download = downloadsMap[song.id]
						SongListScreenItem(
							modifier = Modifier.animateItem(),
							song = song,
							selected = !selecting && song == state.data?.selected,
							selectionMode = selecting,
							checked = song.id in selectedIdSet,
							starred = song.starredAt != null,
							rating = song.userRating ?: 0,
							onSelect = {
								if (selecting) {
									if (!preparingSelection) selectedIds = if (song.id in selectedIdSet) selectedIds - song.id else selectedIds + song.id
								} else viewModel.selected = song
							},
							onDeselect = {
								viewModel.clear()
							},
							onSetStarred = {
								viewModel.starSong(it)
							},
							onSetShareId = {
								shareId = it
							},
							onPlayNext = {
								if (player.checkDuplicate(song.id, preferenceManager)) {
									songToQueue = song
								} else {
									player.playNextSingle(song)
								}
							},
							onAddToQueue = {
								if (player.checkDuplicate(song.id, preferenceManager)) {
									songToQueue = song
								} else {
									player.addToQueueSingle(song)
								}
							},
							onClick = {
								if (selecting) {
									if (!preparingSelection) selectedIds = if (song.id in selectedIdSet) selectedIds - song.id else selectedIds + song.id
								} else player.playNow(song)
							},
							onSetRating = { it: Int -> viewModel.rateSelectedSong(it) },
							download = download,
							onDownload = {
								downloadManager.downloadSong(song.id)
							},
							onCancelDownload = {
								downloadManager.cancelDownload(song.id)
							},
							onDeleteDownload = {
								downloadDeletionHandler.requestDelete(DeleteDownloadTarget.Single(song.id, song.title))
							}
						)
					}
				} else if (isLoading) {
					items(10) {
						ArtGridPlaceholder(viewMode = ListViewMode.List)
					}
				} else {
					item {
						ContentUnavailable(
							label = stringResource(info_no_songs)
						)
					}
				}
			}
		}
	}

	playlistIds?.let { ids ->
		PlaylistUpdateDialog(
			songIds = ids.toImmutableList(),
			onDismissRequest = { playlistIds = null },
			onSuccess = { finishSelection() }
		)
	}
	queueSongs?.let { songs ->
		QueueDuplicateDialog(
			showDialog = true,
			onDismissRequest = { queueSongs = null },
			onConfirm = { player.addToQueue(songs); finishSelection() }
		)
	}
	ShareDialog(
		id = shareId,
		onDismissRequest = { shareId = null }
	)

	QueueDuplicateDialog(
		song = songToQueue,
		onDismissRequest = { songToQueue = null }
	)
}
