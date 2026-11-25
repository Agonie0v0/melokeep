/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.screen.collection.viewmodel

import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import paige.navic.R
import paige.navic.database.mapper.toDomainModel
import paige.navic.domain.manager.session.connectivity.ReachabilityFlow
import paige.navic.domain.manager.session.SessionManager
import paige.navic.domain.manager.session.SessionState
import paige.navic.domain.manager.ui.SnackBarManager
import paige.navic.domain.model.DomainAlbum
import paige.navic.domain.model.DomainAlbumInfo
import paige.navic.domain.model.DomainSong
import paige.navic.domain.model.DomainSongCollection
import paige.navic.domain.repository.AlbumRepository
import paige.navic.domain.repository.CollectionRepository
import paige.navic.domain.repository.SongRepository
import paige.navic.ui.viewmodel.AsyncViewModel

data class CollectionViewState(
	val collection: DomainSongCollection? = null,
	val starred: Boolean = false,
	val rating: Int = 0,
	val albumInfo: DomainAlbumInfo? = null,
	val selectedSong: DomainSong? = null,
	val selectedIndex: Int? = null,
	val selectedAlbum: DomainAlbum? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionDetailViewModel(
	private val collectionId: String,
	private val repository: CollectionRepository,
	private val songRepository: SongRepository,
	private val albumRepository: AlbumRepository,
	private val sessionManager: SessionManager,
	private val snackBarManager: SnackBarManager,
	reachability: ReachabilityFlow
) : AsyncViewModel<CollectionViewState>() {

	init {
		execute {
			CollectionViewState(
				collection = repository.getLocalData(collectionId)
			)
		}
	}

	val listState = LazyListState()

	val isReachable = reachability.isReachable

	val pagedSongs: Flow<PagingData<DomainSong>> = repository
		.getPagedPlaylistSongs(collectionId)
		.cachedIn(viewModelScope)

	val otherAlbums = uiState
		.map { state -> (state.data?.collection as? DomainAlbum) }
		.flatMapLatest { album ->
			album?.let { repository.getOtherAlbums(it.artistId, it.id) } ?: emptyFlow()
		}
		.stateIn(
			scope = viewModelScope,
			started = SharingStarted.Lazily,
			initialValue = emptyList()
		)

	init {
		launch {
			sessionManager.sessionState.collect { if (it is SessionState.Authenticated) refreshCollection(false) }
		}
	}

	fun refreshCollection(fullRefresh: Boolean) = launch {
		val collection = repository.getCollection(fullRefresh, collectionId)
		val state = if (collection is DomainAlbum) {
			CollectionViewState(
				collection = collection,
				starred = albumRepository.isAlbumStarred(collection.id),
				rating = albumRepository.getAlbumRating(collection.id),
				albumInfo = repository.getAlbumInfo(collectionId).toDomainModel()
			)
		} else {
			CollectionViewState(collection = collection)
		}
		setSuccess(state)
	}

	fun selectSong(song: DomainSong, index: Int) = updateData {
		it.copy(
			selectedSong = song,
			selectedIndex = index
		)
	}

	fun selectAlbum(album: DomainAlbum) = updateData {
		it.copy(
			selectedAlbum = album
		)
	}

	fun clearSelection() = updateData {
		it.copy(selectedSong = null, selectedAlbum = null)
	}

	fun removeFromPlaylist() {
		val index = uiState.value.data?.selectedIndex ?: return
		launch {
			repository.removeSongFromPlaylist(collectionId, index)
			snackBarManager.notify(R.string.notice_removed_from_playlist)
			refreshCollection(true)
		}
		clearSelection()
	}

	fun removeSongAtIndex(index: Int) {
		if (index < 0) return
		viewModelScope.launch {
			try {
				repository.removeSongsFromPlaylist(collectionId, listOf(index))
				snackBarManager.notify(R.string.notice_removed_from_playlist)
				refreshCollection(true)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				notifyOperationFailure(e, "从歌单移除歌曲失败")
			}
		}
	}

	fun removeSongsAtIndices(indices: List<Int>) {
		val cleanIndices = indices.filter { it >= 0 }.distinct()
		if (cleanIndices.isEmpty()) return
		viewModelScope.launch {
			try {
				repository.removeSongsFromPlaylist(collectionId, cleanIndices)
				if (cleanIndices.size == 1) {
					snackBarManager.notify(R.string.notice_removed_from_playlist)
				} else {
					snackBarManager.notify(R.string.notice_removed_songs_from_playlist, cleanIndices.size)
				}
				refreshCollection(true)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				notifyOperationFailure(e, "批量移出歌单失败")
			}
		}
	}

	fun renamePlaylist(name: String) {
		val cleanName = name.trim()
		if (cleanName.isEmpty()) {
			snackBarManager.notify(R.string.notice_error_message, "歌单名称不能为空")
			return
		}
		viewModelScope.launch {
			try {
				repository.renamePlaylist(collectionId, cleanName)
				snackBarManager.notify(R.string.notice_renamed_playlist, cleanName)
				refreshCollection(true)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				notifyOperationFailure(e, "重命名歌单失败")
			}
		}
	}

	fun deletePlaylist(onDeleted: () -> Unit) {
		viewModelScope.launch {
			try {
				repository.deletePlaylist(collectionId)
				snackBarManager.notify(R.string.notice_deleted_playlist)
				onDeleted()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				notifyOperationFailure(e, "删除歌单失败")
			}
		}
	}

	private fun notifyOperationFailure(error: Throwable, fallback: String) {
		val detail = error.message?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
		snackBarManager.notify(R.string.notice_error_message, detail)
	}

	fun starSelectedSong() = launch {
		val selection = uiState.value.data?.selectedSong ?: return@launch
		songRepository.starSong(selection.id)
		refreshCollection(false)
	}

	fun unstarSelectedSong() = launch {
		val selection = uiState.value.data?.selectedSong ?: return@launch
		songRepository.unstarSong(selection.id)
		refreshCollection(false)
	}

	fun rateSelectedSong(rating: Int) = launch {
		val selection = uiState.value.data?.selectedSong ?: return@launch
		songRepository.rateSong(selection.id, rating)
	}

	fun rateAlbum(newRating: Int) = launch {
		(uiState.value.data?.collection as? DomainAlbum)?.let { album ->
			albumRepository.rateAlbum(album.id, newRating)
		}
	}

	fun starAlbum(starred: Boolean) {
		launch {
			val collection = data?.collection
			if (collection !is DomainAlbum) return@launch
			if (starred) {
				albumRepository.starAlbum(collection.id)
			} else {
				albumRepository.unstarAlbum(collection.id)
			}
			refreshCollection(false)
		}
	}

	fun rateSelectedAlbum(rating: Int) {
		launch {
			data?.selectedAlbum?.let { albumRepository.rateAlbum(it.id, rating) }
		}
	}

	fun starSelectedAlbum(starred: Boolean) {
		launch {
			val album = data?.selectedAlbum ?: return@launch
			if (starred) {
				albumRepository.starAlbum(album.id)
			} else {
				albumRepository.unstarAlbum(album.id)
			}
		}
	}

}
