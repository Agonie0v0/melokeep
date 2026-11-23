/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.component.dialog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.R
import paige.navic.di.LocalSnackBarState
import paige.navic.melokeep.MeloGroupedCard
import paige.navic.melokeep.MeloHairlineDivider
import paige.navic.ui.component.common.DurationPicker
import paige.navic.ui.core.UiState
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.Share
import paige.navic.ui.screen.share.viewmodel.ShareDialogViewModel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

@Composable
fun ShareDialog(
	id: String?,
	showDialog: Boolean = id != null,
	onDismissRequest: () -> Unit
) {
	val viewModel = koinViewModel<ShareDialogViewModel>()

	// There is not an elegant cross-platform way of making a ClipEntry yet this is deprecated lmao
	@Suppress("DEPRECATION")
	val clipboard = LocalClipboardManager.current

	val resources = LocalResources.current
	val snackBarState = LocalSnackBarState.current
	val state by viewModel.state.collectAsStateWithLifecycle()

	var expiry by remember(id) { mutableStateOf<Duration?>(null) }
	var submittedForThisOpen by remember(showDialog, id) { mutableStateOf(false) }

	val isLoading = state is UiState.Loading && submittedForThisOpen
	val error = (state as? UiState.Error)?.error?.takeIf { submittedForThisOpen }

	LaunchedEffect(state, showDialog, id) {
		if (submittedForThisOpen && showDialog && id != null && state is UiState.Success) {
			val link = (state as? UiState.Success<String?>)?.data ?: return@LaunchedEffect
			val currentExpiry = expiry
			submittedForThisOpen = false
			onDismissRequest()
			viewModel.viewModelScope.launch {
				clipboard.setText(AnnotatedString(link))
				snackBarState.showSnackbar(
					message = buildString {
						append(resources.getString(R.string.notice_copied))
						currentExpiry?.let {
							append(
								"\n" + resources.getString(
									R.string.notice_expiry, it.toString()
								)
							)
						}
					}
				)
			}
		}
	}

	if (showDialog && id != null) {
		val checked = expiry != null
		val interactionSource = remember { MutableInteractionSource() }

		MeloDialogBottomSheet(
			onDismissRequest = onDismissRequest,
			sheetTitle = stringResource(R.string.title_create_share),
			dismissEnabled = !isLoading
		) {
			MeloSheetHeader(
				icon = Icons.Outlined.Share,
				title = stringResource(R.string.title_create_share)
			)

			error?.let { err ->
				MeloDiagnosticErrorCard(err)
			}

			MeloGroupedCard {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.clickable(
							enabled = !isLoading,
							interactionSource = interactionSource,
							onClick = { expiry = if (!checked) 1.hours else null }
						)
						.padding(horizontal = 14.dp, vertical = 10.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.SpaceBetween
				) {
					Text(
						text = stringResource(R.string.option_share_expires),
						style = MaterialTheme.typography.bodyMedium,
						fontWeight = FontWeight.Medium,
						color = MaterialTheme.colorScheme.onSurface
					)
					Switch(
						checked = checked,
						onCheckedChange = null,
						enabled = !isLoading,
						interactionSource = interactionSource
					)
				}

				AnimatedVisibility(visible = checked) {
					Column(modifier = Modifier.fillMaxWidth()) {
						MeloHairlineDivider(startIndent = 14.dp)
						Box(
							modifier = Modifier
								.fillMaxWidth()
								.padding(horizontal = 14.dp, vertical = 12.dp),
							contentAlignment = Alignment.Center
						) {
							DurationPicker(
								duration = expiry ?: 1.hours,
								onDurationChange = { expiry = it },
								enabled = !isLoading
							)
						}
					}
				}
			}

			MeloSheetActionButtons(
				cancelLabel = stringResource(R.string.action_cancel),
				confirmLabel = stringResource(R.string.action_share),
				onCancel = onDismissRequest,
				onConfirm = {
					submittedForThisOpen = true
					viewModel.share(id, expiry)
				},
				isLoading = isLoading
			)
		}
	}
}
