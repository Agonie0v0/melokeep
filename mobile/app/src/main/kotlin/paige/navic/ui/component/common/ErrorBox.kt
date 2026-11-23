/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.component.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import paige.navic.R
import paige.navic.melokeep.MeloHairlineWidth
import paige.navic.ui.core.UiState
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.Error
import paige.navic.ui.icons.outlined.KeyboardArrowDown
import paige.navic.ui.icons.outlined.Refresh
import paige.navic.util.Logger

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> ErrorBox(
	error: UiState.Error<T>,
	padding: PaddingValues = PaddingValues(12.dp),
	bottomPadding: Dp = 24.dp,
	onRetry: (() -> Unit)? = null,
	modifier: Modifier = Modifier
) {
	var expanded by rememberSaveable { mutableStateOf(false) }
	val chevronRotation by animateFloatAsState(
		targetValue = if (expanded) 180f else 0f,
		animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()
	)
	val reason = remember(error.error) {
		error.error.localizedMessage?.trim()?.takeIf { it.isNotBlank() }
			?: error.error::class.simpleName
			?: "未知错误"
	}

	LaunchedEffect(error.error) {
		Logger.e("ErrorBox", "Printing stack trace for error", error.error)
	}

	Surface(
		modifier = modifier
			.padding(padding)
			.padding(bottom = bottomPadding)
			.fillMaxWidth(),
		shape = RoundedCornerShape(16.dp),
		color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.24f),
		border = BorderStroke(
			width = MeloHairlineWidth,
			color = MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
		)
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(14.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp)
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(10.dp)
			) {
				Surface(
					modifier = Modifier.size(34.dp),
					shape = RoundedCornerShape(10.dp),
					color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
				) {
					Box(contentAlignment = Alignment.Center) {
						Icon(
							imageVector = Icons.Outlined.Error,
							contentDescription = null,
							tint = MaterialTheme.colorScheme.error,
							modifier = Modifier.size(18.dp)
						)
					}
				}

				Column(
					modifier = Modifier.weight(1f),
					verticalArrangement = Arrangement.spacedBy(2.dp)
				) {
					Text(
						text = stringResource(R.string.info_error),
						style = MaterialTheme.typography.titleSmall,
						fontWeight = FontWeight.SemiBold,
						color = MaterialTheme.colorScheme.onSurface,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis
					)
					Text(
						text = reason,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis
					)
				}

				Row(
					horizontalArrangement = Arrangement.spacedBy(6.dp),
					verticalAlignment = Alignment.CenterVertically
				) {
					if (onRetry != null) {
						Surface(
							onClick = onRetry,
							shape = CircleShape,
							color = MaterialTheme.colorScheme.surfaceContainer,
							border = BorderStroke(
								width = MeloHairlineWidth,
								color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
							)
						) {
							Row(
								modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(4.dp)
							) {
								Icon(
									imageVector = Icons.Outlined.Refresh,
									contentDescription = "重试",
									tint = MaterialTheme.colorScheme.primary,
									modifier = Modifier.size(15.dp)
								)
								Text(
									text = "重试",
									style = MaterialTheme.typography.labelMedium,
									fontWeight = FontWeight.Medium,
									color = MaterialTheme.colorScheme.primary,
									maxLines = 1
								)
							}
						}
					}

					Surface(
						onClick = { expanded = !expanded },
						shape = CircleShape,
						color = MaterialTheme.colorScheme.surfaceContainer,
						border = BorderStroke(
							width = MeloHairlineWidth,
							color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
						),
						modifier = Modifier.size(30.dp)
					) {
						Box(contentAlignment = Alignment.Center) {
							Icon(
								imageVector = Icons.Outlined.KeyboardArrowDown,
								contentDescription = if (expanded) "收起详情" else "展开详情",
								tint = MaterialTheme.colorScheme.onSurfaceVariant,
								modifier = Modifier
									.size(16.dp)
									.rotate(chevronRotation)
							)
						}
					}
				}
			}

			AnimatedVisibility(visible = expanded) {
				ErrorCodeBlock(error.error)
			}
		}
	}
}
