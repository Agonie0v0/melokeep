/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.component.sheet

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import paige.navic.R
import paige.navic.domain.model.DomainFilter
import paige.navic.domain.model.settings.ListViewMode
import paige.navic.melokeep.MeloGroupedCard
import paige.navic.melokeep.MeloHairlineDivider
import paige.navic.melokeep.MeloHairlineWidth
import paige.navic.melokeep.MeloSectionLabel
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.Check
import paige.navic.ui.icons.outlined.Download
import paige.navic.ui.icons.outlined.ListArrow
import paige.navic.ui.icons.outlined.Star

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SortSheet(
	entries: ImmutableList<T>,
	label: @Composable (T) -> String,
	selectedSorting: T,
	onSetSorting: (T) -> Unit,
	selectedReversed: Boolean? = null,
	onSetReversed: ((Boolean) -> Unit)? = null,
	selectedViewMode: ListViewMode? = null,
	onSetViewMode: ((ListViewMode) -> Unit)? = null,
	selectedFilters: Set<DomainFilter> = emptySet(),
	onToggleFilter: ((DomainFilter) -> Unit)? = null,
	onDismissRequest: () -> Unit
) {
	ModalBottomSheet(
		onDismissRequest = onDismissRequest,
		dragHandle = {
			Box(
				modifier = Modifier
					.padding(top = 10.dp, bottom = 6.dp)
					.size(width = 36.dp, height = 4.dp)
					.clip(CircleShape)
					.background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f))
			)
		},
		containerColor = MaterialTheme.colorScheme.background,
		sheetState = rememberBottomSheetState(
			initialValue = SheetValue.Hidden,
			enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
		),
		contentWindowInsets = {
			BottomSheetDefaults.modalWindowInsets.add(
				WindowInsets(left = 4.dp, right = 4.dp)
			)
		}
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.verticalScroll(rememberScrollState())
				.navigationBarsPadding()
				.padding(horizontal = 16.dp),
			verticalArrangement = Arrangement.spacedBy(14.dp)
		) {
			Text(
				text = stringResource(R.string.title_sort_by),
				style = MaterialTheme.typography.titleLarge,
				fontWeight = FontWeight.Bold,
				color = MaterialTheme.colorScheme.onSurface,
				modifier = Modifier.padding(horizontal = 4.dp)
			)

			MeloGroupedCard(
				modifier = Modifier.selectableGroup()
			) {
				entries.forEachIndexed { index, sorting ->
					val isSelected = sorting == selectedSorting

					Row(
						modifier = Modifier
							.fillMaxWidth()
							.selectable(
								selected = isSelected,
								onClick = { onSetSorting(sorting) },
								role = Role.RadioButton
							)
							.padding(horizontal = 16.dp, vertical = 13.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(12.dp)
					) {
						Text(
							text = label(sorting),
							style = MaterialTheme.typography.bodyMedium,
							fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
							color = if (isSelected) {
								MaterialTheme.colorScheme.primary
							} else {
								MaterialTheme.colorScheme.onSurface
							},
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
							modifier = Modifier.weight(1f)
						)
						if (isSelected) {
							Icon(
								imageVector = Icons.Outlined.Check,
								contentDescription = null,
								tint = MaterialTheme.colorScheme.primary,
								modifier = Modifier.size(18.dp)
							)
						}
					}

					if (index < entries.lastIndex) {
						MeloHairlineDivider(startIndent = 16.dp)
					}
				}
			}

			if (onToggleFilter != null) {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					MeloSectionLabel(title = "筛选")
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.spacedBy(8.dp)
					) {
						DomainFilter.entries.forEach { filter ->
							val isSelected = selectedFilters.contains(filter)
							Surface(
								onClick = { onToggleFilter(filter) },
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
								Row(
									modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.spacedBy(6.dp)
								) {
									Icon(
										imageVector = when (filter) {
											DomainFilter.Starred -> Icons.Outlined.Star
											DomainFilter.Downloaded -> Icons.Outlined.Download
										},
										contentDescription = null,
										tint = if (isSelected) {
											MaterialTheme.colorScheme.onPrimary
										} else {
											MaterialTheme.colorScheme.onSurfaceVariant
										},
										modifier = Modifier.size(16.dp)
									)
									Text(
										text = stringResource(
											when (filter) {
												DomainFilter.Starred -> R.string.option_sort_starred
												DomainFilter.Downloaded -> R.string.option_sort_downloaded
											}
										),
										style = MaterialTheme.typography.labelMedium.copy(
											fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
										),
										color = if (isSelected) {
											MaterialTheme.colorScheme.onPrimary
										} else {
											MaterialTheme.colorScheme.onSurfaceVariant
										},
										maxLines = 1
									)
								}
							}
						}
					}
				}
			}

			if (selectedViewMode != null && onSetViewMode != null) {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					MeloSectionLabel(title = stringResource(R.string.option_list_view_mode))
					SortSegmentedCapsule(
						options = ListViewMode.entries.map { mode ->
							SortSegmentOption(
								label = stringResource(mode.displayName),
								icon = mode.icon,
								selected = selectedViewMode == mode,
								onClick = { onSetViewMode(mode) }
							)
						}
					)
				}
			}

			if (selectedReversed != null && onSetReversed != null) {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					MeloSectionLabel(title = stringResource(R.string.title_direction))
					SortSegmentedCapsule(
						options = listOf(
							SortSegmentOption(
								label = stringResource(R.string.option_sort_ascending),
								icon = Icons.Outlined.ListArrow,
								iconRotation = 180f,
								selected = !selectedReversed,
								onClick = { onSetReversed(false) }
							),
							SortSegmentOption(
								label = stringResource(R.string.option_sort_descending),
								icon = Icons.Outlined.ListArrow,
								selected = selectedReversed,
								onClick = { onSetReversed(true) }
							)
						)
					)
				}
			}

			Spacer(Modifier.height(14.dp))
		}
	}
}

private data class SortSegmentOption(
	val label: String,
	val icon: ImageVector,
	val iconRotation: Float = 0f,
	val selected: Boolean,
	val onClick: () -> Unit
)

@Composable
private fun SortSegmentedCapsule(
	options: List<SortSegmentOption>
) {
	Surface(
		shape = CircleShape,
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		border = BorderStroke(
			width = MeloHairlineWidth,
			color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
		),
		modifier = Modifier.fillMaxWidth()
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(3.dp)
				.selectableGroup(),
			horizontalArrangement = Arrangement.spacedBy(4.dp)
		) {
			options.forEach { option ->
				Box(
					modifier = Modifier
						.weight(1f)
						.clip(CircleShape)
						.background(
							if (option.selected) MaterialTheme.colorScheme.primary else Color.Transparent
						)
						.clickable(onClick = option.onClick)
						.padding(vertical = 9.dp, horizontal = 10.dp),
					contentAlignment = Alignment.Center
				) {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(6.dp)
					) {
						Icon(
							imageVector = option.icon,
							contentDescription = null,
							tint = if (option.selected) {
								MaterialTheme.colorScheme.onPrimary
							} else {
								MaterialTheme.colorScheme.onSurfaceVariant
							},
							modifier = Modifier
								.size(16.dp)
								.then(
									if (option.iconRotation != 0f) {
										Modifier.rotate(option.iconRotation)
									} else {
										Modifier
									}
								)
						)
						Text(
							text = option.label,
							style = MaterialTheme.typography.labelMedium.copy(
								fontWeight = if (option.selected) FontWeight.SemiBold else FontWeight.Medium
							),
							color = if (option.selected) {
								MaterialTheme.colorScheme.onPrimary
							} else {
								MaterialTheme.colorScheme.onSurfaceVariant
							},
							maxLines = 1,
							overflow = TextOverflow.Ellipsis
						)
					}
				}
			}
		}
	}
}
