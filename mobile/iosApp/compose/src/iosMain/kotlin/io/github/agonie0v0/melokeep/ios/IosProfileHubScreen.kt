/*
 * Navic / MeloKeep, a Subsonic music streaming & studio app for iOS
 * Copyright (c) 2026 paige & MeloKeep Swarm
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.agonie0v0.melokeep.ios

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.agonie0v0.melokeep.shared.MeloSettingsController
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import io.github.agonie0v0.melokeep.shared.MeloStudioController

// =============================================================================
// 第四 Tab「我的」：极简 Apple Notes / Linear 分组工作台
// =============================================================================

@Composable
fun IosHubProfileRoute(
	settingsStore: MeloSettingsStore,
	settingsController: MeloSettingsController,
	studioController: MeloStudioController,
	subsonicManager: IosSubsonicManager,
	audioPlayer: IosAudioPlayer,
	onOpenOnboarding: () -> Unit,
	onOpenSleepTimer: () -> Unit = {},
	bottomPadding: PaddingValues = PaddingValues(0.dp),
	onOpenPhoneDownloads: (() -> Unit)? = null,
) {
	val settingsState by settingsController.state.collectAsState()
	val studioState by studioController.state.collectAsState()
	val studioPreset by settingsStore.studioThemePreset.collectAsState()
	val libState by subsonicManager.state.collectAsState()
	val prefs by subsonicManager.prefs.collectAsState()
	val playerState by audioPlayer.state.collectAsState()
	val offlineState by audioPlayer.offlineState.collectAsState()

	val hostNavBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
	val engCfg = settingsState.engineConfig
	val meloCfg = settingsState.serverConfig
	val activeServer = libState.activeServer
	val appVersion = remember { IosSettingsResolveAppVersion() }

	var showSettingsHomeSheet by rememberSaveable { mutableStateOf(false) }
	var showMeloSheet by rememberSaveable { mutableStateOf(false) }
	var showNavidromeSheet by rememberSaveable { mutableStateOf(false) }
	var showPhoneDownloadsSheet by rememberSaveable { mutableStateOf(false) }
	var showAudioEngineSheet by rememberSaveable { mutableStateOf(false) }
	var showSourcesSheet by rememberSaveable { mutableStateOf(false) }

	val offlineCount = playerState.offlineTracks.size
	val totalOfflineBytes = remember(playerState.offlineTracks) {
		audioPlayer.totalOfflineBytes()
	}
	val activePhoneDownloadCount = offlineState.activeDownloads.size
	val downloadingActive = activePhoneDownloadCount > 0 || playerState.downloadingProgress.isNotEmpty()

	LaunchedEffect(meloCfg.isConfigured) {
		if (meloCfg.isConfigured && engCfg == null) {
			settingsController.loadConfig()
		}
	}

	Column(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colorScheme.background),
	) {
		IosCleanTopBar(
			title = "我的",
			trailing = {
				Surface(
					onClick = { showSettingsHomeSheet = true },
					shape = CircleShape,
					color = MaterialTheme.colorScheme.surfaceContainer,
					border = BorderStroke(
						width = IosHairlineWidth,
						color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
					),
				) {
					Row(
						modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(5.dp),
					) {
						Icon(
							imageVector = Icons.Rounded.Settings,
							contentDescription = "打开设置",
							modifier = Modifier.size(15.dp),
							tint = MaterialTheme.colorScheme.primary,
						)
						Text(
							text = "设置",
							style = MaterialTheme.typography.labelMedium,
							fontWeight = FontWeight.Medium,
							color = MaterialTheme.colorScheme.onSurface,
						)
					}
				}
			},
		)

		LazyColumn(
			modifier = Modifier
				.weight(1f)
				.fillMaxWidth(),
			contentPadding = PaddingValues(
				start = 16.dp,
				end = 16.dp,
				top = 8.dp,
				bottom = bottomPadding.calculateBottomPadding() + 20.dp,
			),
			verticalArrangement = Arrangement.spacedBy(20.dp),
		) {
			// 1. 服务
			item(key = "ios-hub-services", contentType = "hub-services") {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					IosSectionLabel(
						title = "服务",
						trailing = {
							Text(
								text = "连接向导",
								style = MaterialTheme.typography.labelMedium,
								fontWeight = FontWeight.Medium,
								color = MaterialTheme.colorScheme.primary,
								modifier = Modifier
									.clip(RoundedCornerShape(6.dp))
									.clickable(onClick = onOpenOnboarding)
									.padding(horizontal = 4.dp, vertical = 2.dp),
							)
						},
					)

					IosGroupedCard {
						val meloDetail = when {
							meloCfg.isAuthenticated && meloCfg.authEnabled && meloCfg.username.isNotBlank() ->
								"${formatCompactHostUrl(meloCfg.baseUrl)} · ${meloCfg.username}"
							meloCfg.isAuthenticated ->
								formatCompactHostUrl(meloCfg.baseUrl)
							else -> "未连接"
						}
						IosHubServiceRow(
							title = "MeloKeep",
							detail = meloDetail,
							connected = meloCfg.isAuthenticated,
							onClick = { showMeloSheet = true },
						)

						IosHairlineDivider(startIndent = 34.dp)

						val navidromeDetail = if (activeServer != null) {
							val host = activeServer.name.ifBlank { formatCompactHostUrl(activeServer.url) }
							if (activeServer.username.isNotBlank()) "$host · ${activeServer.username}" else host
						} else {
							"未连接"
						}
						IosHubServiceRow(
							title = "Navidrome",
							detail = navidromeDetail,
							connected = activeServer != null,
							onClick = { showNavidromeSheet = true },
						)

						if (meloCfg.isAuthenticated) {
							IosHairlineDivider(startIndent = 34.dp)
							val scanValue = when {
								studioState.scanningNavidrome -> "扫描中…"
								studioState.scanStatus != null && studioState.scanStatus?.scanning == true -> "扫描中…"
								studioState.scanStatus != null && (studioState.scanStatus?.count ?: 0) > 0 ->
									"${studioState.scanStatus?.count} 首"
								else -> ""
							}
							IosHubActionRow(
								icon = Icons.Rounded.Refresh,
								title = "扫描 Navidrome 曲库",
								value = scanValue,
								enabled = !studioState.scanningNavidrome,
								onClick = { studioController.triggerNavidromeScan() },
							)
						}
					}
				}
			}

			// 2. 工具与存储
			item(key = "ios-hub-tools", contentType = "hub-tools") {
				val offlineValue = when {
					downloadingActive && activePhoneDownloadCount > 0 ->
						"缓存中 $activePhoneDownloadCount · $offlineCount 首"
					downloadingActive -> "缓存中…"
					offlineCount > 0 ->
						"$offlineCount 首 · ${formatBytesHuman(totalOfflineBytes)}"
					else -> "0 首"
				}

				val sleepTimer = playerState.sleepTimer
				val timerValue = when {
					!sleepTimer.active -> "关闭"
					sleepTimer.stopAfterCurrent -> "播完当前曲目"
					else -> formatDurationMs(sleepTimer.remainingSeconds * 1000L)
				}

				val brTag = if (prefs.maxBitRate <= 0) "无损" else "${prefs.maxBitRate}k"
				val rgTag = when (prefs.replayGainMode) {
					ReplayGainMode.TRACK -> "单曲增益"
					ReplayGainMode.ALBUM -> "专辑增益"
					ReplayGainMode.OFF -> null
				}
				val audioValue = listOfNotNull(brTag, rgTag).joinToString(" · ")
				val sourcesValue = when {
					!meloCfg.isConfigured -> "未连接"
					engCfg != null -> "${engCfg.sources.size}/${engCfg.availableSources.size}"
					else -> ""
				}

				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					IosSectionLabel(title = "工具与存储")

					IosGroupedCard {
						IosHubActionRow(
							icon = Icons.Rounded.OfflinePin,
							title = "本机离线缓存",
							value = offlineValue,
							valueHighlight = downloadingActive,
							onClick = {
								if (onOpenPhoneDownloads != null) {
									onOpenPhoneDownloads()
								} else {
									showPhoneDownloadsSheet = true
								}
							},
						)

						IosHairlineDivider(startIndent = 46.dp)

						IosHubActionRow(
							icon = Icons.Rounded.Bedtime,
							title = "睡眠定时",
							value = timerValue,
							valueHighlight = sleepTimer.active,
							onClick = onOpenSleepTimer,
						)

						IosHairlineDivider(startIndent = 46.dp)

						IosHubActionRow(
							icon = Icons.Rounded.HighQuality,
							title = "音质与播放",
							value = audioValue,
							onClick = { showAudioEngineSheet = true },
						)

						IosHairlineDivider(startIndent = 46.dp)

						IosHubActionRow(
							icon = Icons.Rounded.Settings,
							title = "音源与代理",
							value = sourcesValue,
							onClick = {
								if (meloCfg.isConfigured) {
									if (engCfg == null) settingsController.loadConfig()
									showSourcesSheet = true
								} else {
									showMeloSheet = true
								}
							},
						)
					}
				}
			}

			// 3. 外观
			item(key = "ios-hub-appearance", contentType = "hub-appearance") {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					IosSectionLabel(title = "外观")

					Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
						MeloStudioPresets.all.chunked(2).forEach { rowPresets ->
							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.spacedBy(8.dp),
							) {
								rowPresets.forEach { preset ->
									val selected = studioPreset == preset.id
									IosHubThemePresetChip(
										label = compactIosPresetLabel(preset),
										accentColor = preset.previewAccent,
										selected = selected,
										onClick = { settingsStore.setStudioThemePreset(preset.id) },
										modifier = Modifier.weight(1f),
									)
								}
								if (rowPresets.size == 1) {
									Spacer(modifier = Modifier.weight(1f))
								}
							}
						}

						if (studioPreset == MeloStudioPresets.DYNAMIC) {
							IosGroupedCard {
								Row(
									modifier = Modifier
										.fillMaxWidth()
										.padding(6.dp),
									horizontalArrangement = Arrangement.spacedBy(6.dp),
								) {
									listOf(
										IosThemeMode.SYSTEM to "跟随系统",
										IosThemeMode.DARK to "深色",
										IosThemeMode.LIGHT to "浅色",
									).forEach { (mode, label) ->
										val isCurrent = prefs.themeMode == mode
										Surface(
											onClick = { subsonicManager.updatePrefs { it.copy(themeMode = mode) } },
											shape = RoundedCornerShape(10.dp),
											color = if (isCurrent) {
												MaterialTheme.colorScheme.primary
											} else {
												Color.Transparent
											},
											modifier = Modifier.weight(1f),
										) {
											Box(
												modifier = Modifier.padding(vertical = 7.dp),
												contentAlignment = Alignment.Center,
											) {
												Text(
													text = label,
													style = MaterialTheme.typography.labelMedium,
													fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
													color = if (isCurrent) {
														MaterialTheme.colorScheme.onPrimary
													} else {
														MaterialTheme.colorScheme.onSurfaceVariant
													},
												)
											}
										}
									}
								}
							}
						}
					}
				}
			}

			// 4. 极简版本尾注
			item(key = "ios-hub-version", contentType = "hub-version") {
				Text(
					text = "MeloKeep v$appVersion",
					style = MaterialTheme.typography.labelSmall.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
					textAlign = TextAlign.Center,
					modifier = Modifier
						.fillMaxWidth()
						.padding(top = 4.dp, bottom = 8.dp),
				)
			}
		}
	}

	// 呼出弹层体系
	IosSettingsHomeSheet(
		settingsState = settingsState,
		audioPlayer = audioPlayer,
		subsonicManager = subsonicManager,
		presented = showSettingsHomeSheet,
		onOpenMeloConnection = { showMeloSheet = true },
		onOpenNavidromeServers = { showNavidromeSheet = true },
		onOpenOnboarding = onOpenOnboarding,
		onOpenAudioEngine = { showAudioEngineSheet = true },
		onOpenSources = {
			if (meloCfg.isConfigured) {
				if (engCfg == null) settingsController.loadConfig()
				showSourcesSheet = true
			} else {
				showMeloSheet = true
			}
		},
		onDismissRequest = { showSettingsHomeSheet = false },
	)

	if (showMeloSheet) {
		IosSettingsMeloConnectionSheet(
			settingsState = settingsState,
			settingsController = settingsController,
			hostNavBottom = hostNavBottom,
			onDismiss = { showMeloSheet = false },
		)
	}

	if (showNavidromeSheet) {
		IosSettingsNavidromeServerSheet(
			servers = libState.servers,
			activeServerId = libState.activeServerId,
			hostNavBottom = hostNavBottom,
			onSwitchServer = { id ->
				subsonicManager.switchServer(id)
				showNavidromeSheet = false
			},
			onRemoveServer = { id -> subsonicManager.removeServer(id) },
			onTestAndSave = { name, url, user, pass, editId ->
				subsonicManager.testAndSaveServer(name, url, user, pass, editId)
			},
			onDismiss = { showNavidromeSheet = false },
		)
	}

	if (showPhoneDownloadsSheet) {
		IosPhoneDownloadCenterSheet(
			audioPlayer = audioPlayer,
			subsonicManager = subsonicManager,
			hostNavBottom = hostNavBottom,
			onDismiss = { showPhoneDownloadsSheet = false },
		)
	}

	IosAudioEngineSheet(
		audioPlayer = audioPlayer,
		subsonicManager = subsonicManager,
		presented = showAudioEngineSheet,
		onDismissRequest = { showAudioEngineSheet = false },
	)

	IosSourcesAndProxySheet(
		settingsController = settingsController,
		settingsState = settingsState,
		presented = showSourcesSheet,
		onDismissRequest = { showSourcesSheet = false },
	)
}

@Composable
private fun IosHubServiceRow(
	title: String,
	detail: String,
	connected: Boolean,
	onClick: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(horizontal = 14.dp, vertical = 13.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Box(
			modifier = Modifier
				.size(8.dp)
				.clip(CircleShape)
				.background(
					if (connected) {
						MaterialTheme.colorScheme.meloPositive
					} else {
						MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
					},
				),
		)

		Column(
			modifier = Modifier.weight(1f),
			verticalArrangement = Arrangement.spacedBy(2.dp),
		) {
			Text(
				text = title,
				style = MaterialTheme.typography.bodyMedium,
				fontWeight = FontWeight.Medium,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = detail,
				style = MaterialTheme.typography.bodySmall.tabularNums(),
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}

		Icon(
			imageVector = Icons.Rounded.ChevronRight,
			contentDescription = null,
			tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
			modifier = Modifier.size(17.dp),
		)
	}
}

@Composable
private fun IosHubActionRow(
	icon: ImageVector,
	title: String,
	value: String,
	valueHighlight: Boolean = false,
	enabled: Boolean = true,
	onClick: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(enabled = enabled, onClick = onClick)
			.padding(horizontal = 14.dp, vertical = 13.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Icon(
			imageVector = icon,
			contentDescription = null,
			tint = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.size(20.dp),
		)

		Text(
			text = title,
			style = MaterialTheme.typography.bodyMedium,
			fontWeight = FontWeight.Medium,
			color = MaterialTheme.colorScheme.onSurface,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)

		if (value.isNotBlank()) {
			Text(
				text = value,
				style = MaterialTheme.typography.bodySmall.tabularNums(),
				color = if (valueHighlight) {
					MaterialTheme.colorScheme.primary
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}

		Icon(
			imageVector = Icons.Rounded.ChevronRight,
			contentDescription = null,
			tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
			modifier = Modifier.size(17.dp),
		)
	}
}

@Composable
private fun IosHubThemePresetChip(
	label: String,
	accentColor: Color,
	selected: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Surface(
		onClick = onClick,
		shape = RoundedCornerShape(12.dp),
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = BorderStroke(
			width = if (selected) 1.25.dp else IosHairlineWidth,
			color = if (selected) {
				MaterialTheme.colorScheme.primary
			} else {
				MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
			},
		),
		modifier = modifier,
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 14.dp, vertical = 12.dp),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = label,
				style = MaterialTheme.typography.bodyMedium,
				fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
			Box(
				modifier = Modifier
					.size(14.dp)
					.clip(CircleShape)
					.background(accentColor),
			)
		}
	}
}

private fun compactIosPresetLabel(descriptor: MeloStudioPresets.Descriptor): String = when (descriptor.id) {
	MeloStudioPresets.OBSIDIAN -> "曜石黑"
	MeloStudioPresets.VINYL_GOLD -> "流金黑"
	MeloStudioPresets.PURE_LIGHT -> "纯净白"
	MeloStudioPresets.DYNAMIC -> "跟随封面"
	else -> descriptor.title
}

private fun formatCompactHostUrl(rawUrl: String): String {
	return rawUrl.trim()
		.removePrefix("https://")
		.removePrefix("http://")
		.trimEnd('/')
		.ifBlank { "未配置" }
}
