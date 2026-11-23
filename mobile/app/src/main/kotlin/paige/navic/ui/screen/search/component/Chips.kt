/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.screen.search.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import paige.navic.melokeep.MeloHairlineWidth
import paige.navic.ui.screen.search.SearchCategory

@Composable
fun SearchScreenChips(
	selectedCategory: SearchCategory,
	onCategorySelect: (SearchCategory) -> Unit
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.horizontalScroll(rememberScrollState())
			.padding(horizontal = 16.dp, vertical = 4.dp)
			.selectableGroup(),
		horizontalArrangement = Arrangement.spacedBy(8.dp)
	) {
		SearchCategory.entries.forEach { category ->
			val isSelected = category == selectedCategory
			Surface(
				onClick = { onCategorySelect(category) },
				shape = CircleShape,
				color = if (isSelected) {
					MaterialTheme.colorScheme.primary
				} else {
					MaterialTheme.colorScheme.surfaceContainer
				},
				border = if (isSelected) {
					null
				} else {
					BorderStroke(
						width = MeloHairlineWidth,
						color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
					)
				},
				modifier = Modifier.semantics {
					selected = isSelected
				}
			) {
				Text(
					text = stringResource(category.res),
					style = MaterialTheme.typography.labelMedium.copy(
						fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
					),
					color = if (isSelected) {
						MaterialTheme.colorScheme.onPrimary
					} else {
						MaterialTheme.colorScheme.onSurfaceVariant
					},
					maxLines = 1,
					modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
				)
			}
		}
	}
}
