/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.component.snackbar

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import paige.navic.R
import paige.navic.di.LocalSnackBarState
import paige.navic.ui.component.common.ErrorCodeBlock
import paige.navic.ui.component.dialog.MeloDialogBottomSheet
import paige.navic.ui.component.dialog.MeloSheetHeader
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.Error
import paige.navic.util.Logger

@Composable
fun ErrorSnackBar(
	error: Throwable?,
	onClearError: () -> Unit
) {
	if (error == null) return

	val snackBarState = LocalSnackBarState.current
	val resources = LocalResources.current
	var visible by rememberSaveable { mutableStateOf(false) }

	LaunchedEffect(error) {
		if (visible) return@LaunchedEffect
		val result = snackBarState.showSnackbar(
			message = resources.getString(R.string.info_error),
			actionLabel = resources.getString(R.string.info_error_show),
			duration = SnackbarDuration.Long
		)
		if (result == SnackbarResult.ActionPerformed) {
			visible = true
			Logger.e("ErrorSnackBar", "Printing stack trace for error", error)
		} else {
			onClearError()
		}
	}

	if (!visible) return

	val summary = remember(error) {
		error.localizedMessage?.trim()?.takeIf { it.isNotBlank() }
			?: error::class.simpleName
	}

	MeloDialogBottomSheet(
		onDismissRequest = {
			visible = false
			onClearError()
		},
		sheetTitle = stringResource(R.string.info_error)
	) {
		MeloSheetHeader(
			icon = Icons.Outlined.Error,
			title = stringResource(R.string.info_error),
			subtitle = summary,
			isDestructive = true
		)
		ErrorCodeBlock(error)
		Button(
			onClick = {
				visible = false
				onClearError()
			},
			modifier = Modifier
				.fillMaxWidth()
				.height(46.dp),
			shape = RoundedCornerShape(12.dp)
		) {
			Text(stringResource(R.string.action_ok))
		}
	}
}
