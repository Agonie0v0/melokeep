/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.screen.album.viewmodel

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import paige.navic.database.dao.LetterCount
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.manager.session.SessionManager
import paige.navic.domain.manager.session.SessionState
import paige.navic.domain.model.DomainAlbum
import paige.navic.domain.model.DomainAlbumListType
import paige.navic.domain.model.DomainFilter
import paige.navic.domain.model.toBitmask
import paige.navic.domain.model.toDomainFilters
import paige.navic.domain.repository.AlbumRepository
import paige.navic.ui.core.UiState
import paige.navic.ui.viewmodel.Selectable
import paige.navic.ui.viewmodel.SelectableViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class AlbumListViewModel(
	initialListType: DomainAlbumListType? = null,
	initialFilters: Set<DomainFilter>? = null,
	private val repository: AlbumRepository,
	private val sessionManager: SessionManager,
	private val preferenceManager: PreferenceManager
) : SelectableViewModel<DomainAlbum>() {
	val listType: StateFlow<DomainAlbumListType>
		field = MutableStateFlow(initialListType ?: preferenceManager.albumSortType)

	val selectedReversed: StateFlow<Boolean>
		field = MutableStateFlow(preferenceManager.albumSortReversed)

	val selectedFilters: StateFlow<Set<DomainFilter>>
		field = MutableStateFlow(
			initialFilters ?: preferenceManager.albumFilters.toDomainFilters()
		)

	private val _isRefreshing = MutableStateFlow(false)
	val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

	private val refreshSignal = MutableStateFlow(0)

	val letterCounts: StateFlow<List<LetterCount>> =
		combine(selectedReversed, selectedFilters) { reversed, filters -> reversed to filters }
			.flatMapLatest { (reversed, filters) ->
				repository.getAlbumLetterCounts(reversed, filters)
			}
			.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

	val gridState = LazyGridState()

	private val pagedItems: Flow<PagingData<DomainAlbum>> =
		combine(listType, selectedReversed, selectedFilters, refreshSignal) { sort, reversed, filters, _ ->
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
				if (it is SessionState.Authenticated) refreshAlbums(false)
			}
		}
	}

	fun refreshAlbums(fullRefresh: Boolean) {
		if (!fullRefresh) {
			if (listType.value == DomainAlbumListType.Random) {
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

	fun starAlbum(isStarred: Boolean) {
		launch {
			val selection = selected ?: return@launch
			if (isStarred) repository.starAlbum(selection.id) else repository.unstarAlbum(selection.id)
		}
	}

	fun setRating(newRating: Int) {
		launch {
			val selection = selected ?: return@launch
			repository.rateAlbum(selection.id, newRating)
		}
	}

	fun setListType(newListType: DomainAlbumListType) {
		listType.value = newListType
		preferenceManager.albumSortType = newListType
	}

	fun setReversed(reversed: Boolean) {
		selectedReversed.value = reversed
		preferenceManager.albumSortReversed = reversed
	}

	fun toggleFilter(filter: DomainFilter) {
		val current = selectedFilters.value
		val newFilters = if (current.contains(filter)) {
			current - filter
		} else {
			current + filter
		}
		selectedFilters.value = newFilters
		preferenceManager.albumFilters = newFilters.toBitmask()
	}
}
