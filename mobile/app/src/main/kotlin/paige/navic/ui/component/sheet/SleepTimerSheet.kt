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
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import paige.navic.R
import paige.navic.domain.manager.playback.SleepTimerManager
import paige.navic.domain.manager.playback.SleepTimerMode
import paige.navic.melokeep.MeloGroupedCard
import paige.navic.melokeep.MeloHairlineWidth
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.Bedtime
import paige.navic.ui.icons.outlined.Close
import paige.navic.ui.icons.outlined.Queue
import paige.navic.ui.icons.outlined.QueuePlayNext
import paige.navic.ui.theme.positive
import paige.navic.ui.theme.tabularNums
import paige.navic.ui.util.label
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

enum class SleepTimerTab {
	TIME,
	SONGS
}

val durations = listOf(
	5.minutes,
	10.minutes,
	15.minutes,
	30.minutes,
	45.minutes,
	1.hours,
)

val songCounts = listOf(1, 2, 3, 5, 10)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
	onDismissRequest: (confirmed: Boolean) -> Unit
) {
	val sleepTimerManager = koinInject<SleepTimerManager>()
	val currentMode by sleepTimerManager.mode.collectAsStateWithLifecycle()

	var selectedTab by rememberSaveable(currentMode) {
		mutableStateOf(
			when (currentMode) {
				is SleepTimerMode.Songs, SleepTimerMode.EndOfQueue -> SleepTimerTab.SONGS
				else -> SleepTimerTab.TIME
			}
		)
	}

	var tickCounter by remember { mutableLongStateOf(0L) }
	LaunchedEffect(currentMode) {
		if (currentMode is SleepTimerMode.Time) {
			while (true) {
				delay(1000L)
				tickCounter++
			}
		}
	}

	val activeBadgeText = remember(currentMode, tickCounter) {
		when (val mode = currentMode) {
			is SleepTimerMode.Time -> sleepTimerManager.timeLeft?.let(::formatSleepTimerCountdown) ?: "定时运行中"
			is SleepTimerMode.Songs -> "剩余 ${mode.remaining} 首"
			is SleepTimerMode.EndOfQueue -> "队列结束后"
			SleepTimerMode.Disabled -> null
		}
	}

	ModalBottomSheet(
		onDismissRequest = { onDismissRequest(false) },
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
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 4.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween
			) {
				Text(
					text = "睡眠定时",
					style = MaterialTheme.typography.titleLarge,
					fontWeight = FontWeight.Bold,
					color = MaterialTheme.colorScheme.onSurface
				)

				if (activeBadgeText != null) {
					Surface(
						shape = CircleShape,
						color = MaterialTheme.colorScheme.positive.copy(alpha = 0.14f),
						border = BorderStroke(
							width = MeloHairlineWidth,
							color = MaterialTheme.colorScheme.positive.copy(alpha = 0.42f)
						)
					) {
						Text(
							text = activeBadgeText,
							style = MaterialTheme.typography.labelMedium.copy(
								fontWeight = FontWeight.SemiBold
							).tabularNums(),
							color = MaterialTheme.colorScheme.positive,
							maxLines = 1,
							modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
						)
					}
				}
			}

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
						.padding(3.dp),
					horizontalArrangement = Arrangement.spacedBy(4.dp)
				) {
					SleepTimerTab.entries.forEach { tab ->
						val selected = selectedTab == tab
						val tabLabel = when (tab) {
							SleepTimerTab.TIME -> stringResource(R.string.title_sleep_timer_time)
							SleepTimerTab.SONGS -> stringResource(R.string.title_sleep_timer_songs)
						}
						Box(
							modifier = Modifier
								.weight(1f)
								.clip(CircleShape)
								.background(
									if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
								)
								.clickable { selectedTab = tab }
								.padding(vertical = 8.dp),
							contentAlignment = Alignment.Center
						) {
							Text(
								text = tabLabel,
								style = MaterialTheme.typography.labelMedium.copy(
									fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
								),
								color = if (selected) {
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

			when (selectedTab) {
				SleepTimerTab.TIME -> {
					FlowRow(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.spacedBy(8.dp),
						verticalArrangement = Arrangement.spacedBy(8.dp),
						maxItemsInEachRow = 3
					) {
						durations.forEach { duration ->
							SleepTimerTileCard(
								label = duration.label(),
								icon = Icons.Outlined.Bedtime,
								isActive = false,
								onClick = {
									sleepTimerManager.startTimer(duration)
									onDismissRequest(true)
								},
								modifier = Modifier.weight(1f)
							)
						}
					}
				}

				SleepTimerTab.SONGS -> {
					Column(
						modifier = Modifier.fillMaxWidth(),
						verticalArrangement = Arrangement.spacedBy(8.dp)
					) {
						SleepTimerTileCard(
							label = stringResource(R.string.action_sleep_timer_queue),
							icon = Icons.Outlined.QueuePlayNext,
							isActive = currentMode is SleepTimerMode.EndOfQueue,
							onClick = {
								sleepTimerManager.startEndOfQueueTimer()
								onDismissRequest(true)
							},
							modifier = Modifier.fillMaxWidth()
						)

						FlowRow(
							modifier = Modifier.fillMaxWidth(),
							horizontalArrangement = Arrangement.spacedBy(8.dp),
							verticalArrangement = Arrangement.spacedBy(8.dp),
							maxItemsInEachRow = 3
						) {
							songCounts.forEach { count ->
								SleepTimerTileCard(
									label = pluralStringResource(
										R.plurals.count_songs,
										count,
										count
									),
									icon = Icons.Outlined.Queue,
									isActive = (currentMode as? SleepTimerMode.Songs)?.remaining == count,
									onClick = {
										sleepTimerManager.startSongsTimer(count)
										onDismissRequest(true)
									},
									modifier = Modifier.weight(1f)
								)
							}
						}
					}
				}
			}

			if (currentMode !is SleepTimerMode.Disabled) {
				MeloGroupedCard {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.clickable {
								sleepTimerManager.stopTimer()
								onDismissRequest(true)
							}
							.padding(horizontal = 16.dp, vertical = 14.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
					) {
						Icon(
							imageVector = Icons.Outlined.Close,
							contentDescription = null,
							tint = MaterialTheme.colorScheme.error,
							modifier = Modifier.size(18.dp)
						)
						Text(
							text = stringResource(R.string.action_disable_sleep_timer),
							style = MaterialTheme.typography.bodyMedium.copy(
								fontWeight = FontWeight.SemiBold
							),
							color = MaterialTheme.colorScheme.error
						)
					}
				}
			}

			Spacer(Modifier.height(14.dp))
		}
	}
}

@Composable
private fun SleepTimerTileCard(
	label: String,
	icon: ImageVector,
	isActive: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier
) {
	val containerColor = if (isActive) {
		MaterialTheme.colorScheme.primaryContainer
	} else {
		MaterialTheme.colorScheme.surfaceContainer
	}
	val contentColor = if (isActive) {
		MaterialTheme.colorScheme.onPrimaryContainer
	} else {
		MaterialTheme.colorScheme.onSurface
	}
	val iconColor = if (isActive) {
		MaterialTheme.colorScheme.primary
	} else {
		MaterialTheme.colorScheme.onSurfaceVariant
	}
	val borderColor = if (isActive) {
		MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
	} else {
		MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
	}

	Surface(
		onClick = onClick,
		modifier = modifier,
		shape = RoundedCornerShape(14.dp),
		color = containerColor,
		border = BorderStroke(MeloHairlineWidth, borderColor)
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 12.dp, vertical = 14.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
		) {
			Icon(
				imageVector = icon,
				contentDescription = null,
				tint = iconColor,
				modifier = Modifier.size(20.dp)
			)
			Text(
				text = label,
				style = MaterialTheme.typography.labelLarge.copy(
					fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium
				).tabularNums(),
				color = contentColor,
				textAlign = TextAlign.Center,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
		}
	}
}

private fun formatSleepTimerCountdown(duration: Duration): String {
	val totalSeconds = duration.inWholeSeconds.coerceAtLeast(0L)
	val hours = totalSeconds / 3600L
	val minutes = (totalSeconds % 3600L) / 60L
	val seconds = totalSeconds % 60L
	return when {
		hours > 0L -> String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
		else -> String.format(Locale.US, "%02d:%02d", minutes, seconds)
	}
}
