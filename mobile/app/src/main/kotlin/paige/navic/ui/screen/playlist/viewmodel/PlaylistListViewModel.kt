/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.screen.playlist.viewmodel

import androidx.compose.foundation.lazy.grid.LazyGridState
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
import paige.navic.domain.manager.session.SessionManager
import paige.navic.domain.manager.session.SessionState
import paige.navic.domain.model.DomainFilter
import paige.navic.domain.model.DomainPlaylist
import paige.navic.domain.model.DomainPlaylistListType
import paige.navic.domain.model.toBitmask
import paige.navic.domain.model.toDomainFilters
import paige.navic.domain.repository.PlaylistRepository
import paige.navic.ui.core.UiState
import paige.navic.ui.viewmodel.Selectable
import paige.navic.ui.viewmodel.SelectableViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistListViewModel(
	private val repository: PlaylistRepository,
	private val sessionManager: SessionManager,
	private val preferenceManager: PreferenceManager
) : SelectableViewModel<DomainPlaylist>() {

	val selectedSorting: StateFlow<DomainPlaylistListType>
		field = MutableStateFlow(preferenceManager.playlistSortType)

	val selectedReversed: StateFlow<Boolean>
		field = MutableStateFlow(preferenceManager.playlistSortReversed)

	val selectedFilters: StateFlow<Set<DomainFilter>>
		field = MutableStateFlow(preferenceManager.playlistFilters.toDomainFilters())

	private val _isRefreshing = MutableStateFlow(false)
	val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

	private val refreshSignal = MutableStateFlow(0)

	val gridState = LazyGridState()

	private val pagedItems: Flow<PagingData<DomainPlaylist>> =
		combine(selectedSorting, selectedReversed, selectedFilters, refreshSignal) { sort, reversed, filters, _ ->
			Triple(sort, reversed, filters)
		}
			.flatMapLatest { (sort, reversed, filters) ->
				repository.getLocalData(sort, reversed, filters)
			}
			.cachedIn(viewModelScope)

	init {
		setSuccess(Selectable(items = pagedItems, selected = null))
		viewModelScope.launch {
			sessionManager.sessionState.collect {
				if (it is SessionState.Authenticated) refreshPlaylists(false)
			}
		}
	}

	fun refreshPlaylists(fullRefresh: Boolean) {
		if (!fullRefresh) {
			if (selectedSorting.value == DomainPlaylistListType.Random) {
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

	fun setSorting(sorting: DomainPlaylistListType) {
		selectedSorting.value = sorting
		preferenceManager.playlistSortType = sorting
	}

	fun setReversed(reversed: Boolean) {
		selectedReversed.value = reversed
		preferenceManager.playlistSortReversed = reversed
	}

	fun toggleFilter(filter: DomainFilter) {
		val current = selectedFilters.value
		val newFilters = if (current.contains(filter)) {
			current - filter
		} else {
			current + filter
		}
		selectedFilters.value = newFilters
		preferenceManager.playlistFilters = newFilters.toBitmask()
	}

}
