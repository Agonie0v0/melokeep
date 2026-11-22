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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import io.github.agonie0v0.melokeep.shared.MeloAppUpdateChecker
import io.github.agonie0v0.melokeep.shared.MeloGitHubRelease
import io.github.agonie0v0.melokeep.shared.MeloSemVer
import io.github.agonie0v0.melokeep.shared.MeloSettingsController
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import io.github.agonie0v0.melokeep.shared.MeloStudioController
import kotlinx.coroutines.CancellationException

// =============================================================================
// 第四 Tab「我的」：双服务状态总览 + 4 大分组工作台
// =============================================================================

@Composable
fun IosHubProfileRoute(
	settingsStore: MeloSettingsStore,
	settingsController: MeloSettingsController,
	@Suppress("UNUSED_PARAMETER") studioController: MeloStudioController,
	subsonicManager: IosSubsonicManager,
	audioPlayer: IosAudioPlayer,
	@Suppress("UNUSED_PARAMETER") onOpenOnboarding: () -> Unit,
	@Suppress("UNUSED_PARAMETER") onOpenSleepTimer: () -> Unit = {},
	bottomPadding: PaddingValues = PaddingValues(0.dp),
	onOpenPhoneDownloads: (() -> Unit)? = null,
) {
	val settingsState by settingsController.state.collectAsState()
	val studioPreset by settingsStore.studioThemePreset.collectAsState()
	val libState by subsonicManager.state.collectAsState()
	val prefs by subsonicManager.prefs.collectAsState()
	val offlineState by audioPlayer.offlineState.collectAsState()

	val platformContext = LocalPlatformContext.current
	val imageLoader = remember(platformContext) { SingletonImageLoader.get(platformContext) }
	val hostNavBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
	val meloCfg = settingsState.serverConfig
	val activeServer = libState.activeServer
	val meloConnected = meloCfg.isAuthenticated
	val navidromeConnected = activeServer != null
	val appVersion = remember { IosSettingsResolveAppVersion() }
	val displayCurrentVersion = remember(appVersion) {
		normalizeIosVersionLabel(appVersion, fallback = "1.0.0")
	}

	var showServerSheet by rememberSaveable { mutableStateOf(false) }
	var serverSheetInitialTab by rememberSaveable { mutableIntStateOf(0) }
	var showSourcesSheet by rememberSaveable { mutableStateOf(false) }
	var showAudioEngineSheet by rememberSaveable { mutableStateOf(false) }
	var showLyricsSheet by rememberSaveable { mutableStateOf(false) }
	var showThemeSheet by rememberSaveable { mutableStateOf(false) }
	var showStorageSheet by rememberSaveable { mutableStateOf(false) }
	var confirmClearCacheOpen by rememberSaveable { mutableStateOf(false) }
	var cacheRefreshTick by remember { mutableIntStateOf(0) }
	var cacheClearHint by remember { mutableStateOf<String?>(null) }

	var showUpdateDialog by rememberSaveable { mutableStateOf(false) }
	var latestRelease by remember { mutableStateOf<MeloGitHubRelease?>(null) }

	val tempCacheBytes = remember(cacheRefreshTick, imageLoader) {
		val coilDisk = runCatching { imageLoader.diskCache?.size ?: 0L }.getOrDefault(0L)
		val playerTemp = audioPlayer.estimateTempCacheBytes()
		(coilDisk + playerTemp).coerceAtLeast(0L)
	}
	val hasNewVersion = latestRelease != null && MeloSemVer.isNewer(latestRelease!!.tagName, appVersion)
	val displayLatestTag = latestRelease?.tagName?.let { normalizeIosVersionLabel(it) }.orEmpty()

	LaunchedEffect(appVersion) {
		if (latestRelease == null) {
			try {
				latestRelease = MeloAppUpdateChecker.fetchLatestIosRelease()
			} catch (e: CancellationException) {
				throw e
			} catch (_: Throwable) {
				// 后台静默检查失败时不打扰用户
			}
		}
	}

	LaunchedEffect(meloConnected) {
		if (meloConnected && settingsState.engineConfig == null && !settingsState.loadingConfig) {
			settingsController.loadConfig()
		}
	}

	Column(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colorScheme.background),
	) {
		IosCleanTopBar(title = "我的")

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
			// 顶部双服务状态总览卡
			item(key = "ios-hub-overview", contentType = "hub-overview") {
				val connectedCount = (if (meloConnected) 1 else 0) + (if (navidromeConnected) 1 else 0)
				val statusBadgeText = when (connectedCount) {
					2 -> "双服务在线"
					1 -> "单服务在线"
					else -> "尚未连接"
				}
				val meloTitle = meloCfg.effectiveName.ifBlank { "MeloKeep" }
				val navidromeTitle = activeServer?.name?.trim()?.ifBlank { "Navidrome" } ?: "Navidrome"

				IosGroupedCard {
					Column(modifier = Modifier.fillMaxWidth()) {
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.padding(horizontal = 14.dp, vertical = 12.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.SpaceBetween,
						) {
							Text(
								text = "服务状态",
								style = MaterialTheme.typography.titleSmall,
								fontWeight = FontWeight.SemiBold,
								color = MaterialTheme.colorScheme.onSurface,
							)
							Surface(
								shape = CircleShape,
								color = if (connectedCount > 0) {
									MaterialTheme.colorScheme.meloPositive.copy(alpha = 0.14f)
								} else {
									MaterialTheme.colorScheme.surfaceContainerHigh
								},
							) {
								Text(
									text = statusBadgeText,
									style = MaterialTheme.typography.labelSmall,
									fontWeight = FontWeight.Medium,
									color = if (connectedCount > 0) {
										MaterialTheme.colorScheme.meloPositive
									} else {
										MaterialTheme.colorScheme.onSurfaceVariant
									},
									modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
								)
							}
						}

						Row(
							modifier = Modifier
								.fillMaxWidth()
								.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
							horizontalArrangement = Arrangement.spacedBy(10.dp),
						) {
							IosHubOverviewServiceCapsule(
								title = meloTitle,
								statusLabel = if (meloConnected) "已连接" else "未连接",
								connected = meloConnected,
								onClick = {
									serverSheetInitialTab = 0
									showServerSheet = true
								},
								modifier = Modifier.weight(1f),
							)
							IosHubOverviewServiceCapsule(
								title = navidromeTitle,
								statusLabel = if (navidromeConnected) "已连接" else "未连接",
								connected = navidromeConnected,
								onClick = {
									serverSheetInitialTab = 1
									showServerSheet = true
								},
								modifier = Modifier.weight(1f),
							)
						}

						IosHairlineDivider()

						Row(
							modifier = Modifier
								.fillMaxWidth()
								.padding(horizontal = 14.dp, vertical = 10.dp),
							horizontalArrangement = Arrangement.SpaceBetween,
							verticalAlignment = Alignment.CenterVertically,
						) {
							Text(
								text = "缓存 ${cacheClearHint ?: formatBytesHuman(tempCacheBytes)}",
								style = MaterialTheme.typography.labelMedium.tabularNums(),
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								modifier = Modifier
									.clip(RoundedCornerShape(6.dp))
									.clickable {
										cacheClearHint = null
										showStorageSheet = true
									}
									.padding(horizontal = 4.dp, vertical = 2.dp),
							)

							Text(
								text = if (hasNewVersion) "发现 $displayLatestTag" else "当前 $displayCurrentVersion",
								style = MaterialTheme.typography.labelMedium.tabularNums(),
								fontWeight = if (hasNewVersion) FontWeight.SemiBold else FontWeight.Normal,
								color = if (hasNewVersion) {
									MaterialTheme.colorScheme.primary
								} else {
									MaterialTheme.colorScheme.onSurfaceVariant
								},
								modifier = Modifier
									.clip(RoundedCornerShape(6.dp))
									.clickable { showUpdateDialog = true }
									.padding(horizontal = 4.dp, vertical = 2.dp),
							)
						}
					}
				}
			}

			// 分组 1：服务与同步
			item(key = "ios-hub-group-services", contentType = "hub-group") {
				val serverSummary = when {
					meloConnected && navidromeConnected -> "双服务已连"
					meloConnected -> "MeloKeep"
					navidromeConnected -> "Navidrome"
					else -> "未连接"
				}
				val engCfg = settingsState.engineConfig
				val sourcesSummary = if (engCfg != null) {
					val srcPart = "${engCfg.sources.size} 源"
					val proxyPart = engCfg.proxyUrl.takeIf { it.isNotBlank() }?.let { "已配代理" }
					listOfNotNull(srcPart, proxyPart).joinToString(" · ")
				} else {
					"加载中"
				}

				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					IosSectionLabel(title = "服务与同步")
					IosGroupedCard {
						IosHubActionRow(
							icon = Icons.Rounded.Dns,
							iconTint = MaterialTheme.colorScheme.primary,
							title = "服务器连接",
							value = serverSummary,
							valueHighlight = meloConnected || navidromeConnected,
							onClick = {
								serverSheetInitialTab = if (!meloConnected && navidromeConnected) 1 else 0
								showServerSheet = true
							},
						)
						if (meloConnected) {
							IosHairlineDivider(startIndent = 56.dp)
							IosHubActionRow(
								icon = Icons.Rounded.TravelExplore,
								iconTint = MaterialTheme.colorScheme.secondary,
								title = "音源与代理",
								value = sourcesSummary,
								onClick = { showSourcesSheet = true },
							)
						}
					}
				}
			}

			// 分组 2：播放与音效
			item(key = "ios-hub-group-playback", contentType = "hub-group") {
				val brTag = if (prefs.maxBitRate <= 0) "无损" else "${prefs.maxBitRate}k"
				val rgTag = when (prefs.replayGainMode) {
					ReplayGainMode.TRACK -> "单曲增益"
					ReplayGainMode.ALBUM -> "专辑增益"
					ReplayGainMode.OFF -> null
				}
				val audioValue = listOfNotNull(brTag, rgTag).joinToString(" · ")
				val lyricsSummary = "${prefs.lyricsFontSizeSp.toInt()}sp · " +
					if (prefs.lyricsAlign == "start") "左对齐" else "居中"

				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					IosSectionLabel(title = "播放与音效")
					IosGroupedCard {
						IosHubActionRow(
							icon = Icons.Rounded.HighQuality,
							iconTint = MaterialTheme.colorScheme.primary,
							title = "音质与增益",
							value = audioValue,
							onClick = { showAudioEngineSheet = true },
						)
						IosHairlineDivider(startIndent = 56.dp)
						IosHubActionRow(
							icon = Icons.Rounded.MusicNote,
							iconTint = MaterialTheme.colorScheme.tertiary,
							title = "歌词设置",
							value = lyricsSummary,
							onClick = { showLyricsSheet = true },
						)
					}
				}
			}

			// 分组 3：外观与交互
			item(key = "ios-hub-group-appearance", contentType = "hub-group") {
				val presetDescriptor = remember(studioPreset) {
					MeloStudioPresets.all.firstOrNull { it.id == studioPreset }
				}
				val presetLabel = presetDescriptor?.title ?: compactIosPresetLabel(studioPreset)
				val modeTag = if (studioPreset == MeloStudioPresets.DYNAMIC) {
					when (prefs.themeMode) {
						IosThemeMode.SYSTEM -> "跟随系统"
						IosThemeMode.DARK -> "深色"
						IosThemeMode.LIGHT -> "浅色"
					}
				} else {
					null
				}
				val isLightPreset = MeloStudioPresets.isLightPreset(studioPreset)
				val amoledTag = if (prefs.amoledBlack && !isLightPreset) "纯黑" else null
				val themeSummary = listOfNotNull(presetLabel, modeTag, amoledTag).joinToString(" · ")

				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					IosSectionLabel(title = "外观与交互")
					IosGroupedCard {
						IosHubActionRow(
							icon = Icons.Rounded.AutoAwesome,
							iconTint = presetDescriptor?.previewAccent ?: MaterialTheme.colorScheme.primary,
							title = "主题与外观",
							value = themeSummary,
							badgeDotColor = presetDescriptor?.previewAccent,
							onClick = { showThemeSheet = true },
						)
					}
				}
			}

			// 分组 4：存储与系统
			item(key = "ios-hub-group-storage-system", contentType = "hub-group") {
				val storageSummary = cacheClearHint ?: buildString {
					append(formatBytesHuman(tempCacheBytes))
					if (offlineState.offlineTracks.isNotEmpty()) {
						append(" · ${offlineState.offlineTracks.size} 首离线")
					}
				}

				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					IosSectionLabel(title = "存储与系统")
					IosGroupedCard {
						IosHubActionRow(
							icon = Icons.Rounded.Folder,
							iconTint = MaterialTheme.colorScheme.secondary,
							title = "存储与缓存",
							value = storageSummary,
							valueHighlight = cacheClearHint != null,
							onClick = {
								cacheClearHint = null
								showStorageSheet = true
							},
						)
						IosHairlineDivider(startIndent = 56.dp)
						IosHubActionRow(
							icon = Icons.Rounded.Refresh,
							iconTint = if (hasNewVersion) {
								MaterialTheme.colorScheme.primary
							} else {
								MaterialTheme.colorScheme.onSurfaceVariant
							},
							title = "版本与更新",
							value = if (hasNewVersion) "发现 $displayLatestTag" else "当前 $displayCurrentVersion",
							valueHighlight = hasNewVersion,
							badgeDotColor = if (hasNewVersion) MaterialTheme.colorScheme.primary else null,
							onClick = { showUpdateDialog = true },
						)
					}
				}
			}

			item(key = "ios-hub-version", contentType = "hub-version") {
				Text(
					text = "MeloKeep $displayCurrentVersion",
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

	// 弹层与确认窗体系
	if (showServerSheet) {
		IosSettingsServerConnectionsSheet(
			initialTab = serverSheetInitialTab,
			settingsState = settingsState,
			settingsController = settingsController,
			servers = libState.servers,
			activeServerId = libState.activeServerId,
			hostNavBottom = hostNavBottom,
			onSwitchServer = { id -> subsonicManager.switchServer(id) },
			onRemoveServer = { id -> subsonicManager.removeServer(id) },
			onTestAndSaveNavidrome = { name, url, user, pass, editId ->
				subsonicManager.testAndSaveServer(name, url, user, pass, editId)
			},
			onDismiss = { showServerSheet = false },
		)
	}

	IosSettingsSourcesProxySheet(
		settingsController = settingsController,
		settingsState = settingsState,
		presented = showSourcesSheet && meloConnected,
		onDismissRequest = { showSourcesSheet = false },
	)

	IosSettingsAudioEngineSheet(
		audioPlayer = audioPlayer,
		subsonicManager = subsonicManager,
		presented = showAudioEngineSheet,
		onDismissRequest = { showAudioEngineSheet = false },
	)

	IosSettingsLyricsSheet(
		audioPlayer = audioPlayer,
		subsonicManager = subsonicManager,
		presented = showLyricsSheet,
		onDismissRequest = { showLyricsSheet = false },
	)

	IosSettingsThemeAppearanceSheet(
		settingsStore = settingsStore,
		subsonicManager = subsonicManager,
		hostNavBottom = hostNavBottom,
		presented = showThemeSheet,
		onDismissRequest = { showThemeSheet = false },
	)

	IosSettingsStorageSheet(
		tempCacheBytes = tempCacheBytes,
		offlineTrackCount = offlineState.offlineTracks.size,
		offlineTotalBytes = offlineState.totalOfflineBytes,
		cacheClearHint = cacheClearHint,
		onRequestClearCache = {
			cacheClearHint = null
			confirmClearCacheOpen = true
		},
		onOpenPhoneDownloads = onOpenPhoneDownloads,
		hostNavBottom = hostNavBottom,
		presented = showStorageSheet,
		onDismissRequest = { showStorageSheet = false },
	)

	if (confirmClearCacheOpen) {
		AlertDialog(
			onDismissRequest = { confirmClearCacheOpen = false },
			containerColor = MaterialTheme.colorScheme.surfaceContainer,
			title = { Text("清理临时缓存") },
			text = {
				Text("当前临时缓存约 ${formatBytesHuman(tempCacheBytes)}（封面图片与网络临时文件），清理不会删除已离线缓存的歌曲。")
			},
			confirmButton = {
				Button(
					onClick = {
						confirmClearCacheOpen = false
						runCatching { imageLoader.memoryCache?.clear() }
						runCatching { imageLoader.diskCache?.clear() }
						audioPlayer.clearTempCache()
						cacheRefreshTick += 1
						cacheClearHint = "已清理"
					},
				) {
					Text("确认清理")
				}
			},
			dismissButton = {
				TextButton(onClick = { confirmClearCacheOpen = false }) {
					Text("取消")
				}
			},
		)
	}

	if (showUpdateDialog) {
		IosAppUpdateDialog(
			currentVersion = appVersion,
			initialRelease = latestRelease,
			onReleaseLoaded = { fetched -> latestRelease = fetched },
			onShowNotice = { cacheClearHint = null; subsonicManager.clearMessages() },
			onDismiss = { showUpdateDialog = false },
		)
	}
}

@Composable
private fun IosHubOverviewServiceCapsule(
	title: String,
	statusLabel: String,
	connected: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Surface(
		onClick = onClick,
		shape = RoundedCornerShape(12.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
		border = BorderStroke(
			width = IosHairlineWidth,
			color = if (connected) {
				MaterialTheme.colorScheme.meloPositive.copy(alpha = 0.35f)
			} else {
				MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
			},
		),
		modifier = modifier,
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 12.dp, vertical = 10.dp),
			verticalArrangement = Arrangement.spacedBy(4.dp),
		) {
			Text(
				text = title,
				style = MaterialTheme.typography.bodyMedium,
				fontWeight = FontWeight.Medium,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(6.dp),
			) {
				Box(
					modifier = Modifier
						.size(7.dp)
						.clip(CircleShape)
						.background(
							if (connected) {
								MaterialTheme.colorScheme.meloPositive
							} else {
								MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
							},
						),
				)
				Text(
					text = statusLabel,
					style = MaterialTheme.typography.labelSmall,
					color = if (connected) {
						MaterialTheme.colorScheme.meloPositive
					} else {
						MaterialTheme.colorScheme.onSurfaceVariant
					},
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}

@Composable
private fun IosHubActionRow(
	icon: ImageVector,
	iconTint: Color,
	title: String,
	value: String,
	valueHighlight: Boolean = false,
	badgeDotColor: Color? = null,
	enabled: Boolean = true,
	onClick: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(enabled = enabled, onClick = onClick)
			.padding(horizontal = 14.dp, vertical = 12.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Box(
			modifier = Modifier
				.size(30.dp)
				.clip(RoundedCornerShape(8.dp))
				.background(iconTint.copy(alpha = 0.14f)),
			contentAlignment = Alignment.Center,
		) {
			Icon(
				imageVector = icon,
				contentDescription = null,
				tint = iconTint,
				modifier = Modifier.size(17.dp),
			)
		}

		Text(
			text = title,
			style = MaterialTheme.typography.bodyMedium,
			fontWeight = FontWeight.Medium,
			color = MaterialTheme.colorScheme.onSurface,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)

		if (badgeDotColor != null) {
			Box(
				modifier = Modifier
					.size(8.dp)
					.clip(CircleShape)
					.background(badgeDotColor),
			)
		}

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

private fun compactIosPresetLabel(presetId: String): String = when (presetId) {
	MeloStudioPresets.OBSIDIAN -> "曜石录音室"
	MeloStudioPresets.VINYL_GOLD -> "流金黑胶"
	MeloStudioPresets.MIDNIGHT_AURORA -> "午夜极光"
	MeloStudioPresets.CYBER_AMETHYST -> "暗紫霓虹"
	MeloStudioPresets.PURE_LIGHT -> "纯净晨雾"
	MeloStudioPresets.WARM_VINYL -> "暖调黑胶纸"
	MeloStudioPresets.NORDIC_FROST -> "北欧冰川"
	MeloStudioPresets.ROSE_MIST -> "雾樱漫音"
	MeloStudioPresets.DYNAMIC -> "动态壁纸色"
	else -> presetId
}
