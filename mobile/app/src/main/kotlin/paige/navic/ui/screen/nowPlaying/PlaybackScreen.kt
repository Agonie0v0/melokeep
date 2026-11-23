/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.screen.nowPlaying

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import paige.navic.R
import paige.navic.melokeep.MeloGroupedCard
import paige.navic.melokeep.MeloHairlineDivider
import paige.navic.melokeep.MeloHairlineWidth
import paige.navic.playback.MediaPlayer
import paige.navic.ui.theme.tabularNums
import kotlin.math.abs
import kotlin.math.round

@Composable
fun PlaybackSpeedScreen() {
	val player = koinInject<MediaPlayer>()
	val playerState by player.uiState.collectAsStateWithLifecycle()

	val selectedSpeed = playerState.playbackSpeed
	val playbackSpeeds = remember {
		listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
	}

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.verticalScroll(rememberScrollState())
			.navigationBarsPadding()
			.padding(horizontal = 16.dp, vertical = 12.dp),
		verticalArrangement = Arrangement.spacedBy(14.dp)
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween
		) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp)
			) {
				Text(
					text = "倍速与音调",
					style = MaterialTheme.typography.titleLarge,
					fontWeight = FontWeight.Bold,
					color = MaterialTheme.colorScheme.onSurface
				)
				Surface(
					shape = CircleShape,
					color = MaterialTheme.colorScheme.primaryContainer,
					border = BorderStroke(
						width = MeloHairlineWidth,
						color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
					)
				) {
					Text(
						text = "${selectedSpeed}x",
						style = MaterialTheme.typography.labelMedium.copy(
							fontWeight = FontWeight.SemiBold
						).tabularNums(),
						color = MaterialTheme.colorScheme.onPrimaryContainer,
						maxLines = 1,
						modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
					)
				}
			}

			if (abs(selectedSpeed - 1.0f) > 0.005f) {
				Surface(
					onClick = { player.setPlaybackSpeed(1.0f) },
					shape = CircleShape,
					color = MaterialTheme.colorScheme.surfaceContainer,
					border = BorderStroke(
						width = MeloHairlineWidth,
						color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
					)
				) {
					Text(
						text = "恢复 1.0x",
						style = MaterialTheme.typography.labelMedium.copy(
							fontWeight = FontWeight.Medium
						).tabularNums(),
						color = MaterialTheme.colorScheme.primary,
						maxLines = 1,
						modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp)
					)
				}
			}
		}

		MeloGroupedCard {
			Column(modifier = Modifier.fillMaxWidth()) {
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 14.dp, vertical = 12.dp),
					verticalArrangement = Arrangement.spacedBy(4.dp)
				) {
					Slider(
						value = selectedSpeed,
						onValueChange = { newValue ->
							val snappedValue = round(newValue * 100) / 100f
							player.setPlaybackSpeed(snappedValue)
						},
						valueRange = 0.5f..2.0f,
						modifier = Modifier.fillMaxWidth(),
						colors = SliderDefaults.colors(
							thumbColor = MaterialTheme.colorScheme.primary,
							activeTrackColor = MaterialTheme.colorScheme.primary,
							inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
						)
					)

					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(horizontal = 4.dp),
						horizontalArrangement = Arrangement.SpaceBetween
					) {
						Text(
							text = "0.5x",
							style = MaterialTheme.typography.labelSmall.tabularNums(),
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
						Text(
							text = "1.0x",
							style = MaterialTheme.typography.labelSmall.tabularNums(),
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
						Text(
							text = "2.0x",
							style = MaterialTheme.typography.labelSmall.tabularNums(),
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
					}
				}

				MeloHairlineDivider(startIndent = 14.dp)

				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 12.dp, vertical = 12.dp),
					horizontalArrangement = Arrangement.spacedBy(6.dp),
					verticalAlignment = Alignment.CenterVertically
				) {
					playbackSpeeds.forEach { speed ->
						val isSelected = abs(selectedSpeed - speed) < 0.005f
						Surface(
							onClick = { player.setPlaybackSpeed(speed) },
							modifier = Modifier.weight(1f),
							shape = CircleShape,
							color = if (isSelected) {
								MaterialTheme.colorScheme.primary
							} else {
								MaterialTheme.colorScheme.surfaceContainerHigh
							},
							border = if (isSelected) {
								null
							} else {
								BorderStroke(
									width = MeloHairlineWidth,
									color = MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
								)
							}
						) {
							Box(
								modifier = Modifier.padding(vertical = 8.dp),
								contentAlignment = Alignment.Center
							) {
								Text(
									text = "${speed}x",
									style = MaterialTheme.typography.labelSmall.copy(
										fontSize = 11.sp,
										fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
									).tabularNums(),
									color = if (isSelected) {
										MaterialTheme.colorScheme.onPrimary
									} else {
										MaterialTheme.colorScheme.onSurface
									},
									maxLines = 1,
									softWrap = false
								)
							}
						}
					}
				}
			}
		}

		MeloGroupedCard {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.clickable {
						player.setPreservePitch(!playerState.preservePitch)
					}
					.padding(horizontal = 14.dp, vertical = 10.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween
			) {
				Text(
					text = stringResource(R.string.option_preserve_pitch),
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = FontWeight.Medium,
					color = MaterialTheme.colorScheme.onSurface,
					modifier = Modifier.weight(1f)
				)
				Switch(
					checked = playerState.preservePitch,
					onCheckedChange = player::setPreservePitch
				)
			}
		}

		Spacer(Modifier.height(6.dp))
	}
}
