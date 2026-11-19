/*
 * Navic / MeloKeep, a Subsonic music streaming & studio app for Android
 * Copyright (c) 2026 paige & MeloKeep Swarm
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.melokeep

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.ArrowBack

internal val MeloHairlineWidth: Dp = 0.5.dp

@Composable
internal fun MeloSectionLabel(
	title: String,
	modifier: Modifier = Modifier,
	trailing: (@Composable () -> Unit)? = null,
) {
	Row(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = 4.dp),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.CenterVertically
	) {
		Text(
			text = title,
			style = MaterialTheme.typography.labelMedium.copy(
				fontSize = 12.sp,
				fontWeight = FontWeight.Medium
			),
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f, fill = false)
		)
		if (trailing != null) {
			Spacer(modifier = Modifier.width(8.dp))
			trailing()
		}
	}
}

@Composable
internal fun MeloGroupedCard(
	modifier: Modifier = Modifier,
	content: @Composable ColumnScope.() -> Unit,
) {
	val shape = RoundedCornerShape(14.dp)
	Surface(
		modifier = modifier.fillMaxWidth(),
		shape = shape,
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = BorderStroke(
			width = MeloHairlineWidth,
			color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
		)
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.clip(shape),
			content = content
		)
	}
}

@Composable
internal fun MeloHairlineDivider(
	modifier: Modifier = Modifier,
	startIndent: Dp = 0.dp,
) {
	HorizontalDivider(
		modifier = modifier.padding(start = startIndent),
		thickness = MeloHairlineWidth,
		color = MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
	)
}

@Composable
internal fun MeloQuietEmptyState(
	message: String,
	modifier: Modifier = Modifier,
	icon: ImageVector? = null,
	actionLabel: String? = null,
	onAction: (() -> Unit)? = null,
) {
	Column(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = 24.dp, vertical = 32.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
	) {
		if (icon != null) {
			Icon(
				imageVector = icon,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
				modifier = Modifier.size(28.dp)
			)
		}
		Text(
			text = message,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = TextAlign.Center,
			modifier = Modifier.widthIn(max = 320.dp)
		)
		if (!actionLabel.isNullOrBlank() && onAction != null) {
			Spacer(modifier = Modifier.height(2.dp))
			Surface(
				onClick = onAction,
				shape = CircleShape,
				color = MaterialTheme.colorScheme.surfaceContainer,
				border = BorderStroke(
					width = MeloHairlineWidth,
					color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
				)
			) {
				Text(
					text = actionLabel,
					style = MaterialTheme.typography.labelMedium,
					fontWeight = FontWeight.Medium,
					color = MaterialTheme.colorScheme.primary,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
				)
			}
		}
	}
}

@Composable
internal fun MeloCleanTopBar(
	title: String,
	modifier: Modifier = Modifier,
	onBack: (() -> Unit)? = null,
	applyStatusBarPadding: Boolean = true,
	trailing: (@Composable RowScope.() -> Unit)? = null,
) {
	Surface(
		modifier = modifier.fillMaxWidth(),
		color = MaterialTheme.colorScheme.background,
		tonalElevation = 0.dp
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.then(
					if (applyStatusBarPadding) {
						Modifier.windowInsetsPadding(WindowInsets.statusBars)
					} else {
						Modifier
					}
				)
				.padding(horizontal = 16.dp, vertical = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp)
		) {
			if (onBack != null) {
				Surface(
					onClick = onBack,
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
							contentDescription = "返回",
							modifier = Modifier.size(18.dp),
							tint = MaterialTheme.colorScheme.onSurface
						)
					}
				}
			}

			Text(
				text = title,
				style = if (onBack == null) {
					MaterialTheme.typography.headlineSmall
				} else {
					MaterialTheme.typography.titleLarge
				},
				fontWeight = FontWeight.Bold,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f)
			)

			if (trailing != null) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(8.dp),
					content = trailing
				)
			}
		}
	}
}
