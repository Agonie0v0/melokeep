/*
 * Navic / MeloKeep, a Subsonic music streaming & studio app for Android
 * Copyright (c) 2026 paige & MeloKeep Swarm
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.melokeep

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import coil3.ImageLoader
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import io.github.agonie0v0.melokeep.shared.MeloStudioController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import paige.navic.database.entity.DownloadStatus
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.playback.SleepTimerManager
import paige.navic.domain.manager.playback.SleepTimerMode
import paige.navic.domain.manager.playback.StreamCacheManager
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.manager.session.DownloadManager
import paige.navic.domain.manager.session.SessionManager
import paige.navic.domain.manager.session.SessionState
import paige.navic.domain.model.settings.ThemeMode
import paige.navic.ui.component.sheet.SleepTimerSheet
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.filled.Settings
import paige.navic.ui.icons.outlined.Bedtime
import paige.navic.ui.icons.outlined.ChevronForward
import paige.navic.ui.icons.outlined.Lyrics
import paige.navic.ui.icons.outlined.Refresh
import paige.navic.ui.navigation.Screen
import paige.navic.ui.theme.MeloBottomChromeClearance
import paige.navic.ui.theme.MeloStudioPresets
import paige.navic.ui.theme.positive
import paige.navic.ui.theme.tabularNums
import paige.navic.ui.util.label
import paige.navic.util.toFileSize

/**
 * 第四 Tab「我的」：极简 Apple Notes / Linear 分组工作台。
 */
@Composable
fun MeloHubProfileScreen(
	onOpenOnboarding: () -> Unit = {},
	onOpenPhoneDownloads: (() -> Unit)? = null
) {
	val meloSettingsStore = koinInject<MeloSettingsStore>()
	val studioController = koinInject<MeloStudioController>()
	val sessionManager = koinInject<SessionManager>()
	val sleepTimerManager = koinInject<SleepTimerManager>()
	val downloadManager = koinInject<DownloadManager>()
	val streamCacheManager = koinInject<StreamCacheManager>()
	val imageLoader = koinInject<ImageLoader>()
	val preferenceManager = koinInject<PreferenceManager>()
	val backStack = LocalNavStack.current

	val meloConfig by meloSettingsStore.config.collectAsStateWithLifecycle()
	val studioPreset by meloSettingsStore.studioThemePreset.collectAsStateWithLifecycle()
	val sessionState by sessionManager.sessionState.collectAsStateWithLifecycle()
	val studioState by studioController.state.collectAsStateWithLifecycle()
	val sleepTimerMode by sleepTimerManager.mode.collectAsStateWithLifecycle()

	val allDownloads by downloadManager.allDownloads.collectAsStateWithLifecycle(emptyList())
	val downloadCount by downloadManager.downloadCount.collectAsStateWithLifecycle(0)
	val downloadSizeBytes by downloadManager.downloadSize.collectAsStateWithLifecycle(0L)
	val isDownloadingLibrary by downloadManager.isDownloadingLibrary.collectAsStateWithLifecycle(false)

	val downloadedEntities = remember(allDownloads) {
		allDownloads.filter { it.status == DownloadStatus.DOWNLOADED }
	}
	val activePhoneDownloadsCount = remember(allDownloads) {
		allDownloads.count { it.status == DownloadStatus.DOWNLOADING }
	}
	val totalOfflineCount = maxOf(downloadCount, downloadedEntities.size)

	var streamCacheBytes by remember { mutableLongStateOf(0L) }
	var imageCacheBytes by remember { mutableLongStateOf(0L) }

	LaunchedEffect(backStack.lastOrNull(), downloadSizeBytes, totalOfflineCount) {
		val (stream, image) = withContext(Dispatchers.IO) {
			streamCacheManager.size to (imageLoader.diskCache?.size ?: 0L)
		}
		streamCacheBytes = stream
		imageCacheBytes = image
	}

	val tempCacheBytes = streamCacheBytes + imageCacheBytes

	var sleepTimerOpen by rememberSaveable { mutableStateOf(false) }
	var phoneDownloadsSheetOpen by rememberSaveable { mutableStateOf(false) }
	var connectionSheetOpen by rememberSaveable { mutableStateOf(false) }
	var lyricsSheetOpen by rememberSaveable { mutableStateOf(false) }

	val openSettingsRoot = dropUnlessResumed {
		if (backStack.lastOrNull() !is Screen.Settings.Root) {
			backStack.add(Screen.Settings.Root)
		}
	}
	val openStorageDetail = dropUnlessResumed {
		if (backStack.lastOrNull() != Screen.Settings.DataStorage) {
			backStack.add(Screen.Settings.DataStorage)
		}
	}

	Column(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colorScheme.background)
	) {
		MeloCleanTopBar(
			title = "我的",
			applyStatusBarPadding = true,
			trailing = {
				Surface(
					onClick = openSettingsRoot,
					shape = CircleShape,
					color = MaterialTheme.colorScheme.surfaceContainer,
					border = BorderStroke(
						width = MeloHairlineWidth,
						color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
					)
				) {
					Row(
						modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(5.dp)
					) {
						Icon(
							imageVector = Icons.Filled.Settings,
							contentDescription = "打开设置",
							modifier = Modifier.size(15.dp),
							tint = MaterialTheme.colorScheme.primary
						)
						Text(
							text = "设置",
							style = MaterialTheme.typography.labelMedium,
							fontWeight = FontWeight.Medium,
							color = MaterialTheme.colorScheme.onSurface
						)
					}
				}
			}
		)

		LazyColumn(
			modifier = Modifier
				.weight(1f)
				.fillMaxWidth(),
			contentPadding = PaddingValues(
				start = 16.dp,
				end = 16.dp,
				top = 8.dp,
				bottom = MeloBottomChromeClearance + 16.dp
			),
			verticalArrangement = Arrangement.spacedBy(20.dp)
		) {
			// 1. 服务连接分组
			item(key = "hub-services", contentType = "hub-services") {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					MeloSectionLabel(
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
									.padding(horizontal = 4.dp, vertical = 2.dp)
							)
						}
					)

					MeloGroupedCard {
						val meloDetail = when {
							meloConfig.isAuthenticated && meloConfig.authEnabled && meloConfig.username.isNotBlank() ->
								"${formatCompactHost(meloConfig.baseUrl)} · ${meloConfig.username}"
							meloConfig.isAuthenticated ->
								formatCompactHost(meloConfig.baseUrl)
							else -> "未连接"
						}
						HubServiceRow(
							title = "MeloKeep",
							detail = meloDetail,
							connected = meloConfig.isAuthenticated,
							onClick = onOpenOnboarding
						)

						MeloHairlineDivider(startIndent = 34.dp)

						val navidromeConnected = sessionState !is SessionState.Unauthenticated
						val navidromeDetail = when (sessionState) {
							is SessionState.Authenticated -> {
								val host = formatCompactHost(sessionManager.instanceUrl)
								val user = sessionManager.username.trim()
								if (user.isNotEmpty()) "$host · $user" else host
							}
							is SessionState.Restoring -> "正在连接…"
							SessionState.Unauthenticated -> "未连接"
						}
						HubServiceRow(
							title = "Navidrome",
							detail = navidromeDetail,
							connected = navidromeConnected,
							onClick = { connectionSheetOpen = true }
						)

						if (meloConfig.isAuthenticated) {
							MeloHairlineDivider(startIndent = 34.dp)
							HubActionRow(
								icon = Icons.Outlined.Refresh,
								title = "扫描 Navidrome 曲库",
								value = if (studioState.scanningNavidrome) "扫描中…" else "",
								enabled = !studioState.scanningNavidrome,
								onClick = studioController::triggerNavidromeScan
							)
						}
					}
				}
			}

			// 2. 工具与存储分组
			item(key = "hub-tools", contentType = "hub-tools") {
				val downloadingActive = isDownloadingLibrary || activePhoneDownloadsCount > 0
				val offlineValue = when {
					downloadingActive && activePhoneDownloadsCount > 0 ->
						"缓存中 $activePhoneDownloadsCount · $totalOfflineCount 首"
					downloadingActive -> "缓存中…"
					totalOfflineCount > 0 ->
						"$totalOfflineCount 首 · ${downloadSizeBytes.toFileSize()}"
					else -> "0 首"
				}

				val timerActive = sleepTimerMode !is SleepTimerMode.Disabled
				val timerValue = when (val mode = sleepTimerMode) {
					is SleepTimerMode.Time -> sleepTimerManager.timeLeft?.label().orEmpty()
					is SleepTimerMode.Songs -> "剩余 ${mode.remaining} 首"
					is SleepTimerMode.EndOfQueue -> "播完当前队列"
					SleepTimerMode.Disabled -> "关闭"
				}

				val playerOffset = formatCompactOffsetShort(preferenceManager.playerLyricsOffsetMs)
				val statusBarOffset = formatCompactOffsetShort(preferenceManager.statusBarLyricsOffsetMs)

				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					MeloSectionLabel(title = "工具与存储")

					MeloGroupedCard {
						HubActionRow(
							icon = MeloIcons.PhoneAndroid,
							title = "本机离线缓存",
							value = offlineValue,
							valueHighlight = downloadingActive,
							onClick = {
								if (onOpenPhoneDownloads != null) {
									onOpenPhoneDownloads()
								} else {
									phoneDownloadsSheetOpen = true
								}
							}
						)

						MeloHairlineDivider(startIndent = 46.dp)

						HubActionRow(
							icon = Icons.Outlined.Bedtime,
							title = "睡眠定时",
							value = timerValue,
							valueHighlight = timerActive,
							onClick = { sleepTimerOpen = true }
						)

						MeloHairlineDivider(startIndent = 46.dp)

						HubActionRow(
							icon = Icons.Outlined.Lyrics,
							title = "歌词偏移",
							value = "$playerOffset / $statusBarOffset ms",
							onClick = { lyricsSheetOpen = true }
						)

						MeloHairlineDivider(startIndent = 46.dp)

						HubActionRow(
							icon = MeloIcons.CleaningServices,
							title = "临时缓存",
							value = tempCacheBytes.toFileSize(),
							onClick = openStorageDetail
						)
					}
				}
			}

			// 3. 外观分组
			item(key = "hub-appearance", contentType = "hub-appearance") {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					MeloSectionLabel(title = "外观")

					Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
						MeloStudioPresets.all.chunked(2).forEach { rowPresets ->
							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.spacedBy(8.dp)
							) {
								rowPresets.forEach { preset ->
									val selected = studioPreset == preset.id
									HubThemePresetChip(
										label = compactPresetLabel(preset),
										accentColor = preset.previewAccent,
										selected = selected,
										onClick = { meloSettingsStore.setStudioThemePreset(preset.id) },
										modifier = Modifier.weight(1f)
									)
								}
								if (rowPresets.size == 1) {
									Spacer(modifier = Modifier.weight(1f))
								}
							}
						}

						if (studioPreset == MeloStudioPresets.DYNAMIC) {
							MeloGroupedCard {
								Row(
									modifier = Modifier
										.fillMaxWidth()
										.padding(6.dp),
									horizontalArrangement = Arrangement.spacedBy(6.dp)
								) {
									listOf(
										ThemeMode.System to "跟随系统",
										ThemeMode.Dark to "深色",
										ThemeMode.Light to "浅色"
									).forEach { (mode, label) ->
										val isCurrent = preferenceManager.themeMode == mode
										Surface(
											onClick = { preferenceManager.themeMode = mode },
											shape = RoundedCornerShape(10.dp),
											color = if (isCurrent) {
												MaterialTheme.colorScheme.primary
											} else {
												Color.Transparent
											},
											modifier = Modifier.weight(1f)
										) {
											Box(
												modifier = Modifier.padding(vertical = 7.dp),
												contentAlignment = Alignment.Center
											) {
												Text(
													text = label,
													style = MaterialTheme.typography.labelMedium,
													fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
													color = if (isCurrent) {
														MaterialTheme.colorScheme.onPrimary
													} else {
														MaterialTheme.colorScheme.onSurfaceVariant
													}
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
			item(key = "hub-version", contentType = "hub-version") {
				Text(
					text = "MeloKeep v1.0.15",
					style = MaterialTheme.typography.labelSmall.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
					textAlign = TextAlign.Center,
					modifier = Modifier
						.fillMaxWidth()
						.padding(top = 4.dp, bottom = 8.dp)
				)
			}
		}
	}

	if (sleepTimerOpen) {
		SleepTimerSheet(onDismissRequest = { sleepTimerOpen = false })
	}
	MeloPhoneDownloadCenterSheet(
		presented = phoneDownloadsSheetOpen,
		onDismissRequest = { phoneDownloadsSheetOpen = false }
	)
	MeloNavidromeConnectionSheet(
		presented = connectionSheetOpen,
		onDismissRequest = { connectionSheetOpen = false }
	)
	MeloLyricsAndStageSheet(
		presented = lyricsSheetOpen,
		onDismissRequest = { lyricsSheetOpen = false }
	)
}

@Composable
private fun HubServiceRow(
	title: String,
	detail: String,
	connected: Boolean,
	onClick: () -> Unit
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(horizontal = 14.dp, vertical = 13.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp)
	) {
		Box(
			modifier = Modifier
				.size(8.dp)
				.clip(CircleShape)
				.background(
					if (connected) {
						MaterialTheme.colorScheme.positive
					} else {
						MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
					}
				)
		)

		Column(
			modifier = Modifier.weight(1f),
			verticalArrangement = Arrangement.spacedBy(2.dp)
		) {
			Text(
				text = title,
				style = MaterialTheme.typography.bodyMedium,
				fontWeight = FontWeight.Medium,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
			Text(
				text = detail,
				style = MaterialTheme.typography.bodySmall.tabularNums(),
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
		}

		Icon(
			imageVector = Icons.Outlined.ChevronForward,
			contentDescription = null,
			tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
			modifier = Modifier.size(17.dp)
		)
	}
}

@Composable
private fun HubActionRow(
	icon: ImageVector,
	title: String,
	value: String,
	valueHighlight: Boolean = false,
	enabled: Boolean = true,
	onClick: () -> Unit
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(enabled = enabled, onClick = onClick)
			.padding(horizontal = 14.dp, vertical = 13.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp)
	) {
		Icon(
			imageVector = icon,
			contentDescription = null,
			tint = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.size(20.dp)
		)

		Text(
			text = title,
			style = MaterialTheme.typography.bodyMedium,
			fontWeight = FontWeight.Medium,
			color = MaterialTheme.colorScheme.onSurface,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f)
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
				overflow = TextOverflow.Ellipsis
			)
		}

		Icon(
			imageVector = Icons.Outlined.ChevronForward,
			contentDescription = null,
			tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
			modifier = Modifier.size(17.dp)
		)
	}
}

@Composable
private fun HubThemePresetChip(
	label: String,
	accentColor: Color,
	selected: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier
) {
	Surface(
		onClick = onClick,
		shape = RoundedCornerShape(12.dp),
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = BorderStroke(
			width = if (selected) 1.25.dp else MeloHairlineWidth,
			color = if (selected) {
				MaterialTheme.colorScheme.primary
			} else {
				MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
			}
		),
		modifier = modifier
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 14.dp, vertical = 12.dp),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically
		) {
			Text(
				text = label,
				style = MaterialTheme.typography.bodyMedium,
				fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f)
			)
			Box(
				modifier = Modifier
					.size(14.dp)
					.clip(CircleShape)
					.background(accentColor)
			)
		}
	}
}

private fun compactPresetLabel(descriptor: MeloStudioPresets.Descriptor): String = when (descriptor.id) {
	MeloStudioPresets.OBSIDIAN -> "曜石黑"
	MeloStudioPresets.VINYL_GOLD -> "流金黑"
	MeloStudioPresets.PURE_LIGHT -> "纯净白"
	MeloStudioPresets.DYNAMIC -> "跟随封面"
	else -> descriptor.title
}

private fun formatCompactHost(rawUrl: String): String {
	return rawUrl.trim()
		.removePrefix("https://")
		.removePrefix("http://")
		.trimEnd('/')
		.ifBlank { "未配置" }
}

private fun formatCompactOffsetShort(offsetMs: Int): String = when {
	offsetMs > 0 -> "+$offsetMs"
	offsetMs < 0 -> "$offsetMs"
	else -> "0"
}
