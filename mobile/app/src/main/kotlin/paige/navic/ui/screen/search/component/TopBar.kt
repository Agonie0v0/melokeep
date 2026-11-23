/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.screen.search.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import paige.navic.R
import paige.navic.di.LocalNavStack
import paige.navic.melokeep.MeloHairlineWidth
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.ArrowBack
import paige.navic.ui.icons.outlined.Close
import paige.navic.ui.icons.outlined.Search
import paige.navic.ui.theme.defaultFont

@Composable
fun SearchScreenTopBar(
	query: TextFieldState,
	nested: Boolean,
	onSearch: (String) -> Unit
) {
	val backStack = LocalNavStack.current
	val focusManager = LocalFocusManager.current
	val focusRequester = remember { FocusRequester() }

	LaunchedEffect(Unit) {
		focusRequester.requestFocus()
	}

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.statusBarsPadding()
			.padding(horizontal = 16.dp, vertical = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(10.dp)
	) {
		if (nested) {
			Surface(
				onClick = {
					focusManager.clearFocus(true)
					if (backStack.size > 1) backStack.removeLastOrNull()
				},
				shape = CircleShape,
				color = MaterialTheme.colorScheme.surfaceContainer,
				border = BorderStroke(
					width = MeloHairlineWidth,
					color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
				),
				modifier = Modifier.size(38.dp)
			) {
				Box(
					modifier = Modifier.fillMaxSize(),
					contentAlignment = Alignment.Center
				) {
					Icon(
						imageVector = Icons.Outlined.ArrowBack,
						contentDescription = stringResource(R.string.action_navigate_back),
						modifier = Modifier.size(18.dp),
						tint = MaterialTheme.colorScheme.onSurface
					)
				}
			}
		}

		Surface(
			modifier = Modifier
				.weight(1f)
				.height(44.dp),
			shape = CircleShape,
			color = MaterialTheme.colorScheme.surfaceContainer,
			border = BorderStroke(
				width = MeloHairlineWidth,
				color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
			)
		) {
			Row(
				modifier = Modifier
					.fillMaxSize()
					.padding(start = 14.dp, end = 6.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp)
			) {
				Icon(
					imageVector = Icons.Outlined.Search,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.size(18.dp)
				)

				BasicTextField(
					state = query,
					modifier = Modifier
						.weight(1f)
						.focusRequester(focusRequester),
					lineLimits = TextFieldLineLimits.SingleLine,
					keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
					onKeyboardAction = {
						focusManager.clearFocus()
						if (query.text.isNotBlank()) {
							onSearch(query.text.toString())
						}
					},
					textStyle = MaterialTheme.typography.bodyMedium.copy(
						color = MaterialTheme.colorScheme.onSurface,
						fontFamily = defaultFont()
					),
					cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
					decorator = { innerTextField ->
						Box(contentAlignment = Alignment.CenterStart) {
							if (query.text.isEmpty()) {
								Text(
									text = stringResource(R.string.title_search),
									style = MaterialTheme.typography.bodyMedium,
									color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
									maxLines = 1,
									overflow = TextOverflow.Ellipsis
								)
							}
							innerTextField()
						}
					}
				)

				if (query.text.isNotEmpty()) {
					IconButton(
						onClick = { query.clearText() },
						modifier = Modifier.size(32.dp)
					) {
						Icon(
							imageVector = Icons.Outlined.Close,
							contentDescription = stringResource(R.string.action_clear_search),
							tint = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.size(16.dp)
						)
					}
				}
			}
		}
	}
}
