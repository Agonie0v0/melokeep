/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.screen.nowPlaying.component.controls

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import paige.navic.R
import paige.navic.database.entity.DownloadStatus
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalSnackBarState
import paige.navic.domain.manager.session.DownloadManager
import paige.navic.domain.model.DomainPlaylist
import paige.navic.melokeep.MeloTrackMapper
import paige.navic.playback.MediaPlayer
import paige.navic.ui.component.dialog.DeleteDownloadTarget
import paige.navic.ui.component.dialog.ShareDialog
import paige.navic.ui.component.dialog.rememberDownloadDeletionHandler
import paige.navic.ui.component.sheet.SleepTimerSheet
import paige.navic.ui.component.sheet.SongSheet
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.MoreHoriz
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screen.playlist.dialog.PlaylistUpdateDialog
import paige.navic.ui.theme.NavicTheme
import paige.navic.ui.util.rememberColorSchemeForCurrentSong

@Composable
fun NowPlayingMoreButton(
	songRating: Int,
	onSetSongRating: (Int) -> Unit
) {
	val backStack = LocalNavStack.current
	val player = koinInject<MediaPlayer>()
	val downloadManager = koinInject<DownloadManager>()
	val snackBarState = LocalSnackBarState.current
	val deletionHandler = rememberDownloadDeletionHandler()
	val scope = rememberCoroutineScope()
	val playerState by player.uiState.collectAsStateWithLifecycle()
	val allDownloads by downloadManager.allDownloads.collectAsStateWithLifecycle(initialValue = emptyList())
	val song = playerState.currentSong
	val download = remember(song?.id, allDownloads) {
		song?.let { current -> allDownloads.firstOrNull { it.songId == current.id } }
	}
	var expanded by remember { mutableStateOf(false) }
	var sleepTimerSheetShown by rememberSaveable { mutableStateOf(false) }
	var playlistDialogShown by rememberSaveable { mutableStateOf(false) }
	var shareId by remember { mutableStateOf<String?>(null) }
	val colorScheme = rememberColorSchemeForCurrentSong()

	IconButton(
		onClick = {
			expanded = true
		},
		colors = IconButtonDefaults.filledTonalIconButtonColors(),
		modifier = Modifier.size(32.dp),
		enabled = song != null
	) {
		Icon(
			imageVector = Icons.Outlined.MoreHoriz,
			contentDescription = stringResource(R.string.action_more)
		)
	}

	if (expanded && song != null) {
		NavicTheme(colorScheme) {
			SongSheet(
				onDismissRequest = { expanded = false },
				song = song,
				isPlaylist = playerState.currentCollection is DomainPlaylist,
				onViewAlbum = dropUnlessResumed {
					playerState.currentCollection?.let { collection ->
						backStack.remove(Screen.NowPlaying)
						backStack.add(Screen.CollectionDetail(collection.id, ""))
					}
				},
				onViewArtist = dropUnlessResumed {
					backStack.remove(Screen.NowPlaying)
					backStack.add(Screen.ArtistDetail(song.artistId))
				},
				onShare = {
					shareId = song.id
				},
				onAddToPlaylist = if (!song.id.startsWith("device:") && !song.id.startsWith("radio_")) {
					{ playlistDialogShown = true }
				} else {
					null
				},
				onTrackInfo = dropUnlessResumed {
					expanded = false
					backStack.add(Screen.SongDetailSheet(songId = song.id, coverArtId = song.coverArtId))
				},
				rating = songRating,
				onSetRating = onSetSongRating,
				downloadStatus = download?.status ?: DownloadStatus.NOT_DOWNLOADED,
				onDownload = {
					if (song.isExternal || MeloTrackMapper.isMeloSongId(song.id)) {
						downloadManager.downloadExternalSong(song)
					} else {
						downloadManager.downloadSong(song.id)
					}
					scope.launch {
						snackBarState.currentSnackbarData?.dismiss()
						snackBarState.showSnackbar("已加入离线下载：${song.title}")
					}
				},
				onCancelDownload = {
					downloadManager.cancelDownload(song.id)
				},
				onDeleteDownload = {
					expanded = false
					deletionHandler.requestDelete(DeleteDownloadTarget.Single(song.id, song.title))
				},
				showSleepTimer = true,
				onSleepTimer = {
					expanded = false
					sleepTimerSheetShown = true
				},
				showPlaybackSpeed = true,
				onPlaybackSpeed = {
					expanded = false
					backStack.add(Screen.PlaybackSpeed)
				}
			)
		}
	}

	if (sleepTimerSheetShown) {
		NavicTheme(colorScheme) {
			SleepTimerSheet(
				onDismissRequest = { sleepTimerSheetShown = false }
			)
		}
	}

	if (playlistDialogShown && song != null) {
		NavicTheme(colorScheme) {
			PlaylistUpdateDialog(
				songIds = persistentListOf(song.id),
				onDismissRequest = { playlistDialogShown = false }
			)
		}
	}

	NavicTheme(colorScheme) {
		ShareDialog(
			id = shareId,
			onDismissRequest = { shareId = null }
		)
	}
}
