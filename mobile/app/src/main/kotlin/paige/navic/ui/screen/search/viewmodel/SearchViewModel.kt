/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.screen.search.viewmodel

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import paige.navic.domain.manager.session.connectivity.ReachabilityFlow
import paige.navic.domain.manager.session.DownloadManager
import paige.navic.domain.model.DomainSong
import paige.navic.domain.repository.SearchRepository
import paige.navic.domain.repository.SongRepository
import paige.navic.ui.core.UiState
import kotlin.time.Duration.Companion.milliseconds

@OptIn(FlowPreview::class)
class SearchViewModel(
	private val repository: SearchRepository,
	private val songRepository: SongRepository,
	private val settingsStore: MeloSettingsStore,
	reachability: ReachabilityFlow,
	downloadManager: DownloadManager
) : ViewModel() {
	val searchState: StateFlow<UiState<List<Any>>>
		field = MutableStateFlow<UiState<List<Any>>>(UiState.Success(emptyList()))

	val searchHistory: StateFlow<List<String>> = settingsStore.librarySearchHistory

	val selectedSong: StateFlow<DomainSong?>
		field = MutableStateFlow(null)

	val selectedSongIsStarred: StateFlow<Boolean>
		field = MutableStateFlow(false)

	val selectedSongRating: StateFlow<Int>
		field = MutableStateFlow(0)

	val searchQuery = TextFieldState()

	val isReachable = reachability.isReachable
	val downloadedSongs = downloadManager.downloadedSongs

	val gridState = LazyGridState()

	init {
		viewModelScope.launch {
			snapshotFlow { searchQuery.text }
				.debounce(300.milliseconds)
				.collectLatest { queryText ->
					val query = queryText.toString()
					if (query.isBlank()) {
						searchState.value = UiState.Success(emptyList())
					} else {
						searchState.value = UiState.Loading()
						try {
							searchState.value = UiState.Success(repository.search(query))
						} catch (e: Exception) {
							if (e !is CancellationException) {
								searchState.value = UiState.Error(e)
							}
						}
					}
				}
		}
	}

	fun addToSearchHistory(query: String) {
		settingsStore.recordLibrarySearchHistory(query)
	}

	fun removeFromSearchHistory(query: String) {
		settingsStore.removeLibrarySearchHistory(query)
	}

	fun clearSearchHistory() {
		settingsStore.clearLibrarySearchHistory()
	}

	fun selectSong(song: DomainSong) {
		viewModelScope.launch {
			selectedSong.value = song
			selectedSongIsStarred.value = songRepository.isSongStarred(song.id)
			selectedSongRating.value = songRepository.getSongRating(song.id)
		}
	}

	fun starSelectedSong(starred: Boolean) {
		viewModelScope.launch {
			val selection = selectedSong.value ?: return@launch
			runCatching {
				if (starred) {
					songRepository.starSong(selection.id)
				} else {
					songRepository.unstarSong(selection.id)
				}
				selectedSongIsStarred.value = starred
			}
		}
	}

	fun rateSelectedSong(rating: Int) {
		viewModelScope.launch {
			val selection = selectedSong.value ?: return@launch
			runCatching {
				songRepository.rateSong(selection.id, rating)
				selectedSongRating.value = rating
			}
		}
	}

	fun clearSelectedSong() {
		selectedSong.value = null
	}
}
