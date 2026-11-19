/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.component.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import paige.navic.R
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.Note

@Composable
fun ContentUnavailable(
	modifier: Modifier = Modifier.fillMaxSize(),
	label: String = stringResource(R.string.info_content_unavailable),
	color: Color = MaterialTheme.colorScheme.onSurface
) {
	Column(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = 24.dp, vertical = 28.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
	) {
		Icon(
			imageVector = Icons.Outlined.Note,
			contentDescription = null,
			modifier = Modifier.size(28.dp),
			tint = color.copy(alpha = 0.42f)
		)
		Text(
			text = label,
			style = MaterialTheme.typography.bodyMedium,
			color = color.copy(alpha = 0.68f),
			textAlign = TextAlign.Center,
			modifier = Modifier.widthIn(max = 320.dp)
		)
	}
}
