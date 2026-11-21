/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.screen.artist.viewmodel

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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import paige.navic.database.dao.AlbumDao
import paige.navic.database.dao.LetterCount
import paige.navic.database.mapper.toDomainModel
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.manager.session.SessionManager
import paige.navic.domain.manager.session.SessionState
import paige.navic.domain.model.DomainArtist
import paige.navic.domain.model.DomainArtistListType
import paige.navic.domain.model.DomainFilter
import paige.navic.domain.model.toBitmask
import paige.navic.domain.model.toDomainFilters
import paige.navic.domain.repository.ArtistRepository
import paige.navic.playback.MediaPlayer
import paige.navic.ui.core.UiState
import paige.navic.ui.viewmodel.Selectable
import paige.navic.ui.viewmodel.SelectableViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class ArtistListViewModel(
	initialListType: DomainArtistListType? = null,
	initialFilters: Set<DomainFilter>? = null,
	private val repository: ArtistRepository,
	private val albumDao: AlbumDao,
	private val sessionManager: SessionManager,
	private val preferenceManager: PreferenceManager
) : SelectableViewModel<DomainArtist>() {

	val listType: StateFlow<DomainArtistListType>
		field = MutableStateFlow(initialListType ?: preferenceManager.artistSortType)

	val selectedFilters: StateFlow<Set<DomainFilter>>
		field = MutableStateFlow(
			initialFilters ?: preferenceManager.artistFilters.toDomainFilters()
		)

	private val _isRefreshing = MutableStateFlow(false)
	val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

	private val refreshSignal = MutableStateFlow(0)

	val letterCounts: StateFlow<List<LetterCount>> =
		selectedFilters
			.flatMapLatest { filters -> repository.getArtistLetterCounts(filters) }
			.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

	val gridState = LazyGridState()

	private val pagedItems: Flow<PagingData<DomainArtist>> =
		combine(listType, selectedFilters, refreshSignal) { sort, filters, _ -> sort to filters }
			.flatMapLatest { (sort, filters) ->
				repository.getLocalData(sort, filters)
			}
			.cachedIn(viewModelScope)

	init {
		setSuccess(Selectable(items = pagedItems, selected = null))
		viewModelScope.launch {
			sessionManager.sessionState.collect {
				if (it is SessionState.Authenticated) refreshArtists(false)
			}
		}
	}

	fun refreshArtists(fullRefresh: Boolean) {
		if (!fullRefresh) {
			if (listType.value == DomainArtistListType.Random) {
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

	fun selectArtist(artist: DomainArtist?) {
		selected = artist
	}

	fun clearSelection() = selectArtist(null)

	fun starArtist(isStarred: Boolean) {
		launch {
			val artist = selected ?: return@launch
			if (isStarred) repository.starArtist(artist.id) else repository.unstarArtist(artist.id)
		}
	}

	fun addArtistAlbumsToQueue(player: MediaPlayer) {
		val artist = selected ?: return
		viewModelScope.launch {
			val artistAlbums =
				albumDao.getAlbumsByArtist(artist.id).firstOrNull() ?: emptyList()
			artistAlbums.map { it.toDomainModel() }.forEach { album ->
				player.addToQueue(album)
			}
		}
	}

	fun playArtistAlbumsNext(player: MediaPlayer) {
		val artist = selected ?: return
		launch {
			val artistAlbums =
				albumDao.getAlbumsByArtist(artist.id).firstOrNull() ?: emptyList()
			artistAlbums.map { it.toDomainModel() }.forEach { album ->
				player.playNext(album)
			}
		}
	}

	fun setListType(newListType: DomainArtistListType) {
		listType.value = newListType
		preferenceManager.artistSortType = newListType
	}

	fun toggleFilter(filter: DomainFilter) {
		val current = selectedFilters.value
		val newFilters = if (current.contains(filter)) {
			current - filter
		} else {
			current + filter
		}
		selectedFilters.value = newFilters
		preferenceManager.artistFilters = newFilters.toBitmask()
	}

}
