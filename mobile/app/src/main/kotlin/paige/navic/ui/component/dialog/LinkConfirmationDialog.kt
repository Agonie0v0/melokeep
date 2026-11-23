/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.component.dialog

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import paige.navic.R
import paige.navic.domain.manager.ui.LinkManager
import paige.navic.melokeep.MeloGroupedCard
import paige.navic.melokeep.MeloIcons

@Composable
fun LinkConfirmationDialog(
	linkToOpen: String? = null,
	onDismissRequest: () -> Unit
) {
	val linkManager = koinInject<LinkManager>()

	if (linkToOpen != null) {
		MeloDialogBottomSheet(
			onDismissRequest = onDismissRequest,
			sheetTitle = stringResource(R.string.title_link_confirmation)
		) {
			MeloSheetHeader(
				icon = MeloIcons.OpenInNew,
				title = stringResource(R.string.title_link_confirmation),
				subtitle = stringResource(R.string.notice_link_confirmation)
			)

			MeloGroupedCard {
				Text(
					text = linkToOpen,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
					style = MaterialTheme.typography.bodySmall.copy(
						fontFamily = FontFamily.Monospace
					),
					color = MaterialTheme.colorScheme.onSurface,
					modifier = Modifier
						.fillMaxWidth()
						.padding(14.dp)
				)
			}

			MeloSheetActionButtons(
				cancelLabel = stringResource(R.string.action_cancel),
				confirmLabel = stringResource(R.string.action_visit_site),
				onCancel = onDismissRequest,
				onConfirm = {
					linkManager.openLink(linkToOpen)
					onDismissRequest()
				}
			)
		}
	}
}
