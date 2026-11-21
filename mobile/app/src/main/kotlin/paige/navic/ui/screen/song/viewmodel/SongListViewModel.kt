/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.screen.song.viewmodel

import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.manager.session.connectivity.ReachabilityFlow
import paige.navic.domain.manager.session.SessionManager
import paige.navic.domain.manager.session.SessionState
import paige.navic.domain.model.DomainFilter
import paige.navic.domain.model.DomainSong
import paige.navic.domain.model.DomainSongListType
import paige.navic.domain.model.toBitmask
import paige.navic.domain.model.toDomainFilters
import paige.navic.domain.repository.SongRepository
import paige.navic.ui.core.UiState
import paige.navic.ui.viewmodel.Selectable
import paige.navic.ui.viewmodel.SelectableViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class SongListViewModel(
	initialListType: DomainSongListType? = null,
	initialFilters: Set<DomainFilter>? = null,
	private val repository: SongRepository,
	private val sessionManager: SessionManager,
	private val preferenceManager: PreferenceManager,
	reachability: ReachabilityFlow
) : SelectableViewModel<DomainSong>() {

	val selectedSorting = MutableStateFlow(initialListType ?: preferenceManager.songSortType)

	val selectedReversed = MutableStateFlow(preferenceManager.songSortReversed)

	val selectedFilters = MutableStateFlow(
		initialFilters ?: preferenceManager.songFilters.toDomainFilters()
	)

	private val _isRefreshing = MutableStateFlow(false)
	val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

	private val refreshSignal = MutableStateFlow(0)

	val isReachable = reachability.isReachable
	private var randomSongIds = emptyList<String>()

	private val pagedItems: Flow<PagingData<DomainSong>> =
		combine(selectedSorting, selectedReversed, selectedFilters, refreshSignal) { sort, reversed, filters, _ ->
			Triple(sort, reversed, filters)
		}
			.flatMapLatest { (sort, reversed, filters) ->
				repository.getLocalData(
					listType = sort,
					reversed = reversed,
					filters = filters,
					onRandomOrder = { randomSongIds = it }
				)
			}
			.cachedIn(viewModelScope)

	suspend fun getSelectionOrder(): List<String> =
		if (selectedSorting.value == DomainSongListType.Random) randomSongIds
		else repository.getSongIds(selectedSorting.value, selectedReversed.value, selectedFilters.value)

	suspend fun getSelectedSongs(ids: List<String>): List<DomainSong> = repository.getSongsByIds(ids)

	init {
		setSuccess(Selectable(items = pagedItems, selected = null))
		viewModelScope.launch {
			sessionManager.sessionState.collect {
				if (it is SessionState.Authenticated) refreshSongs(false)
			}
		}
	}

	fun refreshSongs(fullRefresh: Boolean) {
		if (!fullRefresh) {
			if (selectedSorting.value == DomainSongListType.Random) {
				refreshSignal.value++
			}
			if (uiState.value.data?.items == null) {
				setSuccess(Selectable(items = pagedItems, selected = selected))
			} else if (uiState.value is UiState.Error) {
				clearError()
			}
			return
		}
		if (_isRefreshing.value) return
		viewModelScope.launch {
			_isRefreshing.value = true
			setLoading()
			try {
				repository.syncLocalData()
				setSuccess(Selectable(items = pagedItems, selected = selected))
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				updateState { UiState.Error(e, Selectable(items = pagedItems, selected = it.data?.selected)) }
			} finally {
				_isRefreshing.value = false
			}
		}
	}

	fun starSong(isStarred: Boolean) {
		launch {
			val selection = selected ?: return@launch
			if (isStarred) {
				repository.starSong(selection.id)
			} else {
				repository.unstarSong(selection.id)
			}
		}
	}

	fun rateSelectedSong(rating: Int) {
		launch {
			val selection = selected ?: return@launch
			repository.rateSong(selection.id, rating)
		}
	}

	fun setSorting(sorting: DomainSongListType) {
		selectedSorting.value = sorting
		preferenceManager.songSortType = sorting
	}

	fun setReversed(reversed: Boolean) {
		selectedReversed.value = reversed
		preferenceManager.songSortReversed = reversed
	}

	fun toggleFilter(filter: DomainFilter) {
		val current = selectedFilters.value
		val newFilters = if (current.contains(filter)) {
			current - filter
		} else {
			current + filter
		}
		selectedFilters.value = newFilters
		preferenceManager.songFilters = newFilters.toBitmask()
	}
}
