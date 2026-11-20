/*
 * Navic / MeloKeep, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.screen.nowPlaying.component.controls

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import paige.navic.R
import paige.navic.database.entity.DownloadStatus
import paige.navic.di.LocalSnackBarState
import paige.navic.domain.manager.session.DownloadManager
import paige.navic.melokeep.MeloTrackMapper
import paige.navic.playback.MediaPlayer
import paige.navic.ui.component.dialog.DeleteDownloadTarget
import paige.navic.ui.component.dialog.rememberDownloadDeletionHandler
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.Check
import paige.navic.ui.icons.outlined.Download

@Composable
fun NowPlayingDownloadButton() {
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
	val status = download?.status

	IconButton(
		onClick = {
			val current = song ?: return@IconButton
			when (status) {
				DownloadStatus.DOWNLOADING -> {
					downloadManager.cancelDownload(current.id)
					scope.launch {
						snackBarState.currentSnackbarData?.dismiss()
						snackBarState.showSnackbar("已取消离线下载：${current.title}")
					}
				}
				DownloadStatus.DOWNLOADED -> {
					deletionHandler.requestDelete(DeleteDownloadTarget.Single(current.id, current.title))
				}
				else -> {
					if (current.isExternal || MeloTrackMapper.isMeloSongId(current.id)) {
						downloadManager.downloadExternalSong(current)
					} else {
						downloadManager.downloadSong(current.id)
					}
					scope.launch {
						snackBarState.currentSnackbarData?.dismiss()
						snackBarState.showSnackbar("已加入离线下载：${current.title}")
					}
				}
			}
		},
		colors = IconButtonDefaults.filledTonalIconButtonColors(),
		modifier = Modifier.size(32.dp),
		enabled = song != null
	) {
		when (status) {
			DownloadStatus.DOWNLOADING -> {
				val progress = download.progress
				if (progress > 0f) {
					CircularProgressIndicator(
						progress = { progress.coerceIn(0f, 1f) },
						modifier = Modifier.size(18.dp),
						strokeWidth = 2.dp
					)
				} else {
					CircularProgressIndicator(
						modifier = Modifier.size(18.dp),
						strokeWidth = 2.dp
					)
				}
			}
			DownloadStatus.DOWNLOADED -> {
				Icon(
					imageVector = Icons.Outlined.Check,
					contentDescription = stringResource(R.string.info_downloaded),
					tint = MaterialTheme.colorScheme.primary
				)
			}
			DownloadStatus.FAILED -> {
				Icon(
					imageVector = Icons.Outlined.Download,
					contentDescription = stringResource(R.string.action_download),
					tint = MaterialTheme.colorScheme.error
				)
			}
			else -> {
				Icon(
					imageVector = Icons.Outlined.Download,
					contentDescription = stringResource(R.string.action_download),
					tint = LocalContentColor.current
				)
			}
		}
	}
}
