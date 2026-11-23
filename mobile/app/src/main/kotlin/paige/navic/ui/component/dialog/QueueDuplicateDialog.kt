/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.component.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import paige.navic.R
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.model.DomainSong
import paige.navic.melokeep.MeloGroupedCard
import paige.navic.playback.MediaPlayer
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.PlaylistAdd

@Composable
fun QueueDuplicateDialog(
	song: DomainSong? = null,
	showDialog: Boolean = song != null,
	onDismissRequest: () -> Unit,
	onConfirm: (() -> Unit)? = null
) {
	val preferenceManager = koinInject<PreferenceManager>()
	val player = koinInject<MediaPlayer>()

	if (showDialog) {
		var dontShowAgain by remember(showDialog) {
			mutableStateOf(preferenceManager.shushQueueDuplicateDialog)
		}
		val interactionSource = remember { MutableInteractionSource() }

		MeloDialogBottomSheet(
			onDismissRequest = onDismissRequest,
			sheetTitle = stringResource(R.string.title_confirm)
		) {
			MeloSheetHeader(
				icon = Icons.Outlined.PlaylistAdd,
				title = stringResource(R.string.title_confirm)
			)

			Text(
				text = stringResource(R.string.notice_queue_duplicate),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)

			MeloGroupedCard {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.clickable(
							interactionSource = interactionSource,
							role = Role.Checkbox,
							onClick = {
								dontShowAgain = !dontShowAgain
								preferenceManager.shushQueueDuplicateDialog = dontShowAgain
							}
						)
						.padding(horizontal = 14.dp, vertical = 10.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.SpaceBetween
				) {
					Text(
						text = stringResource(R.string.action_dont_show_again),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurface
					)
					Checkbox(
						checked = dontShowAgain,
						interactionSource = interactionSource,
						onCheckedChange = null
					)
				}
			}

			MeloSheetActionButtons(
				cancelLabel = stringResource(R.string.action_cancel),
				confirmLabel = stringResource(R.string.action_ok),
				onCancel = onDismissRequest,
				onConfirm = {
					if (onConfirm != null) {
						onConfirm()
					} else if (song != null) {
						player.addToQueueSingle(song)
					}
					onDismissRequest()
				}
			)
		}
	}
}
