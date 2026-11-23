/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.R
import paige.navic.database.dao.PlaylistDao
import paige.navic.database.entity.SyncActionType
import paige.navic.domain.manager.session.SessionManager
import paige.navic.domain.manager.session.SyncManager
import paige.navic.domain.manager.ui.SnackBarManager
import paige.navic.melokeep.MeloGroupedCard
import paige.navic.ui.core.UiState
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.Delete
import paige.navic.ui.icons.outlined.Error

sealed interface DeletionTarget {
	data class Playlist(val id: String) : DeletionTarget
	data class Share(val id: String) : DeletionTarget
	data class Radio(val id: String) : DeletionTarget
}

class DeletionViewModel(
	private val syncManager: SyncManager,
	private val playlistDao: PlaylistDao,
	private val sessionManager: SessionManager,
	private val snackBarManager: SnackBarManager
) : ViewModel() {
	val state: StateFlow<UiState<Nothing?>>
		field = MutableStateFlow<UiState<Nothing?>>(UiState.Success(null))

	private val _events = Channel<Event>()
	val events = _events.receiveAsFlow()

	fun clearError() {
		if (state.value is UiState.Error) {
			state.value = UiState.Success(null)
		}
	}

	fun delete(target: DeletionTarget) {
		if (state.value is UiState.Loading) return
		viewModelScope.launch {
			state.value = UiState.Loading()
			try {
				val noticeRes = when (target) {
					is DeletionTarget.Playlist -> {
						try {
							sessionManager.api.deletePlaylist(target.id)
						} catch (e: CancellationException) {
							throw e
						} catch (_: Exception) {
							syncManager.enqueueAction(
								actionType = SyncActionType.DELETE_PLAYLIST,
								itemId = target.id
							)
						}
						playlistDao.deletePlaylistWithSongs(target.id)
						R.string.notice_deleted_playlist
					}

					is DeletionTarget.Share -> {
						sessionManager.api.deleteShare(target.id)
						R.string.notice_deleted_share
					}

					is DeletionTarget.Radio -> {
						sessionManager.api.deleteInternetRadioStation(target.id)
						R.string.notice_deleted_radio
					}
				}
				state.value = UiState.Success(null)
				snackBarManager.notify(noticeRes)
				_events.send(Event.Dismiss)
			} catch (e: CancellationException) {
				throw e
			} catch (error: Exception) {
				state.value = UiState.Error(error = error)
			}
		}
	}

	sealed class Event {
		object Dismiss : Event()
	}
}

@Composable
fun DeletionDialog(
	target: DeletionTarget?,
	onDismissRequest: () -> Unit,
	onRefresh: (() -> Unit)? = null
) {
	val viewModel = koinViewModel<DeletionViewModel>()
	val state by viewModel.state.collectAsStateWithLifecycle()

	LaunchedEffect(target) {
		viewModel.clearError()
	}

	LaunchedEffect(viewModel) {
		viewModel.events.collect { event ->
			when (event) {
				is DeletionViewModel.Event.Dismiss -> {
					onDismissRequest()
					onRefresh?.invoke()
				}
			}
		}
	}

	if (target != null) {
		val titleRes = when (target) {
			is DeletionTarget.Playlist -> R.string.title_delete_playlist
			is DeletionTarget.Share -> R.string.title_delete_share
			is DeletionTarget.Radio -> R.string.title_delete_radio
		}
		val isLoading = state is UiState.Loading

		MeloDialogBottomSheet(
			onDismissRequest = onDismissRequest,
			sheetTitle = stringResource(titleRes),
			dismissEnabled = !isLoading
		) {
			MeloSheetHeader(
				icon = Icons.Outlined.Delete,
				title = stringResource(titleRes),
				isDestructive = true
			)

			MeloGroupedCard {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(14.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(10.dp)
				) {
					Icon(
						imageVector = Icons.Outlined.Error,
						contentDescription = null,
						tint = MaterialTheme.colorScheme.error,
						modifier = Modifier.size(18.dp)
					)
					Text(
						text = stringResource(R.string.info_action_is_permanent),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
				}
			}

			(state as? UiState.Error)?.error?.let { error ->
				MeloDiagnosticErrorCard(error)
			}

			MeloSheetActionButtons(
				cancelLabel = stringResource(R.string.action_cancel),
				confirmLabel = stringResource(R.string.action_delete),
				onCancel = onDismissRequest,
				onConfirm = { viewModel.delete(target) },
				isDestructive = true,
				isLoading = isLoading
			)
		}
	}
}
