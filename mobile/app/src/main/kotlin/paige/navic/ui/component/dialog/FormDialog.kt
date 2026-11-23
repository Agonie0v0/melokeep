/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.component.dialog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.dropUnlessResumed
import kotlinx.coroutines.delay
import paige.navic.R
import paige.navic.melokeep.MeloHairlineWidth
import paige.navic.ui.component.common.ErrorCodeBlock
import paige.navic.ui.component.common.SegmentedListButtonDefaults
import paige.navic.ui.component.sheet.ModalBottomSheet
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.Check
import paige.navic.ui.icons.outlined.Copy
import paige.navic.ui.icons.outlined.Error
import paige.navic.ui.icons.outlined.KeyboardArrowDown
import paige.navic.ui.navigation.PredictiveBackState
import paige.navic.ui.navigation.rememberPredictiveBackState
import paige.navic.ui.theme.positive
import kotlin.time.Duration.Companion.seconds

@Composable
fun FormDialog(
	width: Dp = 300.dp,
	contentGap: Dp = 12.dp,
	verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
	onDismissRequest: () -> Unit,
	icon: @Composable () -> Unit = {},
	title: @Composable () -> Unit = {},
	action: @Composable () -> Unit = {},
	buttons: @Composable ColumnScope.() -> Unit = {},
	content: @Composable () -> Unit
) {
	var predictiveBackState by remember { mutableStateOf<PredictiveBackState?>(null) }

	BasicAlertDialog(
		modifier = Modifier
			.fillMaxSize()
			.graphicsLayer {
				predictiveBackState?.let { predictiveBackState ->
					translationX = predictiveBackState.offsetX
					translationY = predictiveBackState.offsetY
					scaleX = predictiveBackState.scale
					scaleY = predictiveBackState.scale
				}
			},
		onDismissRequest = onDismissRequest,
		properties = DialogProperties(
			dismissOnBackPress = false,
			dismissOnClickOutside = false,
			usePlatformDefaultWidth = false
		)
	) {
		predictiveBackState = rememberPredictiveBackState(
			onBackCompleted = onDismissRequest
		)

		Box(
			modifier = Modifier.fillMaxSize(),
			contentAlignment = Alignment.Center
		) {
			Box(
				modifier = Modifier.matchParentSize().clickable(
					indication = null,
					interactionSource = null,
					onClick = dropUnlessResumed {
						onDismissRequest()
					}
				)
			)

			Surface(
				modifier = Modifier
					.requiredWidthIn(max = width)
					.wrapContentHeight()
					.verticalScroll(rememberScrollState()),
				shape = RoundedCornerShape(22.dp),
				color = MaterialTheme.colorScheme.surfaceContainerHigh,
				border = BorderStroke(
					width = MeloHairlineWidth,
					color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
				),
				tonalElevation = 0.dp
			) {
				Box {
					Box(Modifier.align(Alignment.TopEnd).padding(16.dp)) {
						CompositionLocalProvider(
							LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
						) {
							action()
						}
					}
					Column(
						modifier = Modifier
							.padding(16.dp)
							.fillMaxWidth(),
						horizontalAlignment = Alignment.CenterHorizontally,
						verticalArrangement = verticalArrangement
					) {
						Spacer(Modifier.height(12.dp))
						CompositionLocalProvider(
							LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
							content = icon
						)
						CompositionLocalProvider(
							LocalTextStyle provides MaterialTheme.typography.titleLarge
								.copy(fontWeight = FontWeight.Bold),
							content = title
						)
						CompositionLocalProvider(
							LocalTextStyle provides MaterialTheme.typography.bodyMedium,
							LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
						) {
							Column(
								modifier = Modifier
									.heightIn(max = 400.dp)
									.verticalScroll(rememberScrollState()),
								horizontalAlignment = Alignment.CenterHorizontally
							) {
								content()
							}
						}
						Spacer(Modifier.height(contentGap))
						Column(
							modifier = Modifier.fillMaxWidth(),
							verticalArrangement = Arrangement.spacedBy(SegmentedListButtonDefaults.SegmentedGap)
						) {
							buttons()
						}
					}
				}
			}
		}
	}
}

@Composable
internal fun MeloSheetDragHandle() {
	Box(
		modifier = Modifier
			.padding(top = 10.dp, bottom = 6.dp)
			.size(width = 36.dp, height = 4.dp)
			.clip(CircleShape)
			.background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f))
	)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MeloDialogBottomSheet(
	onDismissRequest: () -> Unit,
	sheetTitle: String,
	dismissEnabled: Boolean = true,
	content: @Composable ColumnScope.() -> Unit
) {
	ModalBottomSheet(
		onDismissRequest = { if (dismissEnabled) onDismissRequest() },
		dragHandle = { MeloSheetDragHandle() },
		containerColor = MaterialTheme.colorScheme.background,
		sheetGesturesEnabled = dismissEnabled,
		sheetState = rememberBottomSheetState(
			initialValue = SheetValue.Hidden,
			enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
		),
		contentWindowInsets = {
			BottomSheetDefaults.modalWindowInsets.add(
				WindowInsets(left = 4.dp, right = 4.dp)
			)
		},
		sheetTitle = sheetTitle
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.navigationBarsPadding()
				.imePadding()
				.verticalScroll(rememberScrollState())
				.padding(horizontal = 16.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp)
		) {
			content()
			Spacer(Modifier.height(12.dp))
		}
	}
}

@Composable
internal fun MeloSheetHeader(
	icon: ImageVector,
	title: String,
	subtitle: String? = null,
	isDestructive: Boolean = false
) {
	val badgeColor = if (isDestructive) {
		MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
	} else {
		MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
	}
	val badgeBorder = if (isDestructive) {
		MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
	} else {
		MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
	}
	val iconTint = if (isDestructive) {
		MaterialTheme.colorScheme.error
	} else {
		MaterialTheme.colorScheme.primary
	}

	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp)
	) {
		Surface(
			modifier = Modifier.size(40.dp),
			shape = RoundedCornerShape(12.dp),
			color = badgeColor,
			border = BorderStroke(MeloHairlineWidth, badgeBorder)
		) {
			Box(contentAlignment = Alignment.Center) {
				Icon(
					imageVector = icon,
					contentDescription = null,
					tint = iconTint,
					modifier = Modifier.size(20.dp)
				)
			}
		}
		Column(
			modifier = Modifier.weight(1f),
			verticalArrangement = Arrangement.spacedBy(2.dp)
		) {
			Text(
				text = title,
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.SemiBold,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis
			)
			if (!subtitle.isNullOrBlank()) {
				Text(
					text = subtitle,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
		}
	}
}

@Composable
internal fun MeloSheetActionButtons(
	cancelLabel: String,
	confirmLabel: String,
	onCancel: () -> Unit,
	onConfirm: () -> Unit,
	isDestructive: Boolean = false,
	isLoading: Boolean = false,
	cancelEnabled: Boolean = !isLoading,
	confirmEnabled: Boolean = !isLoading
) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(10.dp)
	) {
		OutlinedButton(
			onClick = onCancel,
			enabled = cancelEnabled,
			modifier = Modifier
				.weight(1f)
				.height(46.dp),
			shape = RoundedCornerShape(12.dp)
		) {
			Text(
				text = cancelLabel,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
		}
		Button(
			onClick = onConfirm,
			enabled = confirmEnabled,
			colors = if (isDestructive) {
				ButtonDefaults.buttonColors(
					containerColor = MaterialTheme.colorScheme.error,
					contentColor = MaterialTheme.colorScheme.onError
				)
			} else {
				ButtonDefaults.buttonColors()
			},
			modifier = Modifier
				.weight(1f)
				.height(46.dp),
			shape = RoundedCornerShape(12.dp)
		) {
			if (isLoading) {
				CircularProgressIndicator(
					modifier = Modifier.size(18.dp),
					strokeWidth = 2.dp,
					color = if (isDestructive) {
						MaterialTheme.colorScheme.onError
					} else {
						MaterialTheme.colorScheme.onPrimary
					}
				)
				Spacer(Modifier.width(8.dp))
			}
			Text(
				text = confirmLabel,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
		}
	}
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MeloDiagnosticErrorCard(
	error: Throwable,
	modifier: Modifier = Modifier
) {
	@Suppress("DEPRECATION")
	val clipboard = LocalClipboardManager.current
	val haptics = LocalHapticFeedback.current
	val stackTrace = remember(error) { error.stackTraceToString() }
	val summary = remember(error) {
		error.localizedMessage?.trim()?.takeIf { it.isNotBlank() }
			?: error::class.simpleName
			?: "操作失败"
	}
	var detailsExpanded by rememberSaveable(stackTrace) { mutableStateOf(false) }
	var copied by remember(stackTrace) { mutableStateOf(false) }
	val chevronRotation by animateFloatAsState(
		targetValue = if (detailsExpanded) 180f else 0f,
		animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()
	)

	LaunchedEffect(copied) {
		if (copied) {
			delay(2.seconds)
			copied = false
		}
	}

	Surface(
		modifier = modifier.fillMaxWidth(),
		shape = RoundedCornerShape(14.dp),
		color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f),
		border = BorderStroke(
			width = MeloHairlineWidth,
			color = MaterialTheme.colorScheme.error.copy(alpha = 0.38f)
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
				horizontalArrangement = Arrangement.spacedBy(8.dp)
			) {
				Icon(
					imageVector = Icons.Outlined.Error,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.error,
					modifier = Modifier.size(17.dp)
				)
				Text(
					text = stringResource(R.string.info_error),
					style = MaterialTheme.typography.labelLarge,
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.onSurface,
					modifier = Modifier.weight(1f)
				)
				Surface(
					onClick = {
						haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
						clipboard.setText(AnnotatedString(stackTrace))
						copied = true
					},
					shape = CircleShape,
					color = MaterialTheme.colorScheme.surfaceContainer,
					border = BorderStroke(
						width = MeloHairlineWidth,
						color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
					)
				) {
					Row(
						modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(4.dp)
					) {
						Icon(
							imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.Copy,
							contentDescription = null,
							tint = if (copied) {
								MaterialTheme.colorScheme.positive
							} else {
								MaterialTheme.colorScheme.onSurfaceVariant
							},
							modifier = Modifier.size(13.dp)
						)
						Text(
							text = if (copied) "已复制" else "复制诊断",
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
					}
				}
			}

			Text(
				text = summary,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 3,
				overflow = TextOverflow.Ellipsis
			)

			Row(
				modifier = Modifier
					.clip(RoundedCornerShape(6.dp))
					.clickable { detailsExpanded = !detailsExpanded }
					.padding(vertical = 2.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(4.dp)
			) {
				Text(
					text = if (detailsExpanded) "收起技术详情" else "查看技术详情",
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
				Icon(
					imageVector = Icons.Outlined.KeyboardArrowDown,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier
						.size(16.dp)
						.rotate(chevronRotation)
				)
			}

			AnimatedVisibility(visible = detailsExpanded) {
				ErrorCodeBlock(error)
			}
		}
	}
}
