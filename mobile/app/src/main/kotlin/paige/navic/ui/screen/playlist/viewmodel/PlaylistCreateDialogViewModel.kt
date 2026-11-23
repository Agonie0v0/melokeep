/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.screen.playlist.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.agonie0v0.melokeep.shared.MeloKeepApiClient
import io.github.agonie0v0.melokeep.shared.MeloLocalLibraryController
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import paige.navic.R
import paige.navic.database.dao.PlaylistDao
import paige.navic.database.mapper.toDomainModel
import paige.navic.database.mapper.toEntity
import paige.navic.domain.manager.session.SessionManager
import paige.navic.domain.manager.session.SessionState
import paige.navic.domain.manager.ui.SnackBarManager
import paige.navic.domain.model.DomainPlaylist

data class PlaylistCreateUiState(
	val backend: PlaylistTargetBackend,
	val allowBackendSwitch: Boolean = false,
	val supportedSongCount: Int = 0,
	val unsupportedOnly: Boolean = false,
	val name: String = "",
	val submitting: Boolean = false,
	val error: PlaylistOperationError? = null
)

class PlaylistCreateDialogViewModel(
	private val songIds: List<String>,
	private val targetBackend: PlaylistTargetBackend?,
	private val playlistDao: PlaylistDao,
	private val sessionManager: SessionManager,
	private val snackBarManager: SnackBarManager,
	private val meloApiClient: MeloKeepApiClient,
	private val meloSettingsStore: MeloSettingsStore,
	private val meloLocalController: MeloLocalLibraryController
) : ViewModel() {

	private val supportedSongIds = filterSupportedPlaylistSongIds(songIds)
	private val unsupportedOnly = songIds.isNotEmpty() && supportedSongIds.isEmpty()

	private val _uiState = MutableStateFlow(buildInitialState())
	val uiState: StateFlow<PlaylistCreateUiState> = _uiState.asStateFlow()

	private val _events = Channel<Event>(Channel.BUFFERED)
	val events = _events.receiveAsFlow()

	private fun buildInitialState(): PlaylistCreateUiState {
		val navConnected = sessionManager.sessionState.value !is SessionState.Unauthenticated
		val meloConnected = meloSettingsStore.currentConfig().isAuthenticated
		val allowSwitch = targetBackend == null && songIds.isEmpty() && navConnected && meloConnected
		val initialBackend = when {
			targetBackend != null -> targetBackend
			supportedSongIds.isNotEmpty() -> resolvePlaylistTargetBackend(supportedSongIds)
			navConnected -> PlaylistTargetBackend.NAVIDROME
			meloConnected -> PlaylistTargetBackend.MELOKEEP
			else -> PlaylistTargetBackend.NAVIDROME
		}
		val mappedIds = mapSongIdsForBackend(supportedSongIds, initialBackend)
		val hasUnsupportedOnly = unsupportedOnly || (songIds.isNotEmpty() && mappedIds.isEmpty())
		return PlaylistCreateUiState(
			backend = initialBackend,
			allowBackendSwitch = allowSwitch,
			supportedSongCount = mappedIds.size,
			unsupportedOnly = hasUnsupportedOnly,
			name = "",
			submitting = false,
			error = if (hasUnsupportedOnly) buildUnsupportedSongsError("创建歌单", songIds) else null
		)
	}

	fun resetForOpen() {
		if (_uiState.value.submitting) return
		_uiState.value = buildInitialState()
	}

	fun updateName(value: String) {
		if (_uiState.value.submitting) return
		_uiState.update { current ->
			current.copy(
				name = value,
				error = if (current.unsupportedOnly) current.error else null
			)
		}
	}

	fun selectBackend(backend: PlaylistTargetBackend) {
		val current = _uiState.value
		if (!current.allowBackendSwitch || current.submitting || current.backend == backend) return
		val mappedIds = mapSongIdsForBackend(supportedSongIds, backend)
		_uiState.update {
			it.copy(
				backend = backend,
				supportedSongCount = mappedIds.size,
				error = null
			)
		}
	}

	fun create() {
		val snapshot = _uiState.value
		val trimmedName = snapshot.name.trim()
		if (trimmedName.isEmpty() || snapshot.submitting || snapshot.unsupportedOnly) return

		val backend = snapshot.backend
		when (backend) {
			PlaylistTargetBackend.MELOKEEP -> {
				val cfg = meloSettingsStore.currentConfig()
				if (!cfg.isAuthenticated) {
					_uiState.update {
						it.copy(
							error = buildConnectionMissingError(
								backend = PlaylistTargetBackend.MELOKEEP,
								actionLabel = "创建歌单",
								diagnosticContext = "enabled=${cfg.enabled}, baseUrl=${cfg.baseUrl.ifBlank { "(空)" }}"
							)
						)
					}
					return
				}
			}

			PlaylistTargetBackend.NAVIDROME -> {
				if (sessionManager.sessionState.value is SessionState.Unauthenticated) {
					_uiState.update {
						it.copy(
							error = buildConnectionMissingError(
								backend = PlaylistTargetBackend.NAVIDROME,
								actionLabel = "创建歌单",
								diagnosticContext = "instanceUrl=${sessionManager.instanceUrl.ifBlank { "(空)" }}"
							)
						)
					}
					return
				}
			}
		}

		val targetSongIds = mapSongIdsForBackend(supportedSongIds, backend)

		viewModelScope.launch {
			_uiState.update { it.copy(submitting = true, error = null) }
			try {
				when (backend) {
					PlaylistTargetBackend.MELOKEEP -> {
						val created = meloApiClient.createLocalPlaylist(
							name = trimmedName,
							trackIds = targetSongIds
						)
						meloLocalController.refreshPlaylists()
						val displayName = created.name.ifBlank { trimmedName }
						snackBarManager.notify(R.string.notice_created_playlist, displayName)
						_uiState.update { it.copy(submitting = false) }
						_events.send(
							Event.Dismiss(
								playlist = null,
								backend = PlaylistTargetBackend.MELOKEEP
							)
						)
					}

					PlaylistTargetBackend.NAVIDROME -> {
						val playlist = sessionManager.api.createPlaylist(
							name = trimmedName,
							songIds = targetSongIds
						)
						playlistDao.insertPlaylist(playlist.toEntity())
						val domainPlaylist = playlistDao.getPlaylistById(playlist.id)?.toDomainModel()
						val displayName = playlist.name.ifBlank { trimmedName }
						snackBarManager.notify(R.string.notice_created_playlist, displayName)
						_uiState.update { it.copy(submitting = false) }
						_events.send(
							Event.Dismiss(
								playlist = domainPlaylist,
								backend = PlaylistTargetBackend.NAVIDROME
							)
						)
					}
				}
			} catch (e: CancellationException) {
				_uiState.update { it.copy(submitting = false) }
				throw e
			} catch (e: Throwable) {
				_uiState.update {
					it.copy(
						submitting = false,
						error = toPlaylistOperationError(
							backend = backend,
							actionLabel = "创建歌单",
							throwable = e,
							diagnosticContext = "name=$trimmedName, trackCount=${targetSongIds.size}"
						)
					)
				}
			}
		}
	}

	sealed class Event {
		data class Dismiss(
			val playlist: DomainPlaylist?,
			val backend: PlaylistTargetBackend
		) : Event()
	}
}
