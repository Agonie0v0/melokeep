/*
 * Navic / MeloKeep, a Subsonic music streaming & studio app for Android
 * Copyright (c) 2026 paige & MeloKeep Swarm
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.melokeep

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.di.LocalSnackBarState
import paige.navic.domain.manager.playback.EqualizerManager
import paige.navic.domain.manager.playback.StreamCacheManager
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.manager.runtime.StorageManager
import paige.navic.domain.manager.session.SessionManager
import paige.navic.domain.manager.session.SessionState
import paige.navic.domain.model.settings.BottomBarCollapseMode
import paige.navic.domain.model.settings.BottomBarVisibilityMode
import paige.navic.domain.model.settings.CoverArtShape
import paige.navic.domain.model.settings.EqualizerMode
import paige.navic.domain.model.settings.ReplayGainMode
import paige.navic.domain.model.settings.ThemeMode
import paige.navic.domain.model.settings.UpdateCheckFrequency
import paige.navic.ui.component.sheet.ChangelogViewModel
import paige.navic.ui.component.sheet.UpdateCheckFeedback
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.ChevronForward
import paige.navic.ui.theme.MeloBottomChromeClearance
import paige.navic.ui.theme.MeloStudioPresets
import paige.navic.ui.theme.tabularNums
import paige.navic.util.toFileSize
import kotlin.math.roundToInt

/**
 * 第四 Tab「我的」：深度合并设置后的 5 大分组极简工作台。
 */
@Composable
fun MeloHubProfileScreen(
	onOpenOnboarding: () -> Unit = {},
	@Suppress("UNUSED_PARAMETER")
	onOpenPhoneDownloads: (() -> Unit)? = null
) {
	val meloSettingsStore = koinInject<MeloSettingsStore>()
	val sessionManager = koinInject<SessionManager>()
	val equalizerManager = koinInject<EqualizerManager>()
	val streamCacheManager = koinInject<StreamCacheManager>()
	val storageManager = koinInject<StorageManager>()
	val imageLoader = koinInject<ImageLoader>()
	val preferenceManager = koinInject<PreferenceManager>()
	val snackBarState = LocalSnackBarState.current
	val scope = rememberCoroutineScope()

	val activityOwner = LocalActivity.current as? ViewModelStoreOwner
	val changelogViewModel = if (activityOwner != null) {
		koinViewModel<ChangelogViewModel>(viewModelStoreOwner = activityOwner)
	} else {
		koinViewModel<ChangelogViewModel>()
	}

	val meloConfig by meloSettingsStore.config.collectAsStateWithLifecycle()
	val studioPreset by meloSettingsStore.studioThemePreset.collectAsStateWithLifecycle()
	val sessionState by sessionManager.sessionState.collectAsStateWithLifecycle()
	val eqConfig by equalizerManager.config.collectAsStateWithLifecycle()
	val checkFeedback by changelogViewModel.checkFeedback.collectAsStateWithLifecycle()
	val availableRelease by changelogViewModel.release.collectAsStateWithLifecycle()
	val appVersionName = rememberMeloVersionName()

	var streamCacheBytes by remember { mutableLongStateOf(0L) }
	var imageCacheBytes by remember { mutableLongStateOf(0L) }
	var cacheSizeLoaded by remember { mutableStateOf(false) }
	var clearingCache by remember { mutableStateOf(false) }

	var currentSheet by rememberSaveable { mutableStateOf<String?>(null) }

	// 兼容旧配置为 ManualOnly 的用户：当自动检查开启时自动归一回 Daily
	LaunchedEffect(preferenceManager.checkForUpdates, preferenceManager.updateCheckFrequency) {
		if (preferenceManager.checkForUpdates &&
			preferenceManager.updateCheckFrequency == UpdateCheckFrequency.ManualOnly
		) {
			preferenceManager.updateCheckFrequency = UpdateCheckFrequency.Daily
		}
	}

	LaunchedEffect(currentSheet) {
		val (stream, image) = withContext(Dispatchers.IO) {
			streamCacheManager.size to (imageLoader.diskCache?.size ?: 0L)
		}
		streamCacheBytes = stream
		imageCacheBytes = image
		cacheSizeLoaded = true
	}

	LaunchedEffect(checkFeedback) {
		when (val fb = checkFeedback) {
			is UpdateCheckFeedback.UpToDate -> {
				snackBarState.currentSnackbarData?.dismiss()
				snackBarState.showSnackbar("当前已是最新版本 (v$appVersionName)")
			}
			is UpdateCheckFeedback.Error -> {
				snackBarState.currentSnackbarData?.dismiss()
				snackBarState.showSnackbar(fb.message)
			}
			else -> {}
		}
	}

	val tempCacheBytes = streamCacheBytes + imageCacheBytes

	Column(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colorScheme.background)
	) {
		MeloCleanTopBar(
			title = "我的",
			applyStatusBarPadding = true
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
			// 1. 服务与连接
			item(key = "hub-services", contentType = "hub-services") {
				val navidromeConnected = sessionState !is SessionState.Unauthenticated
				val navidromeStatus = when (sessionState) {
					is SessionState.Authenticated -> "已连接"
					is SessionState.Restoring -> "连接中"
					SessionState.Unauthenticated -> "未连接"
				}
				val navidromeDisplayName = preferenceManager.navidromeServerName.trim().ifBlank { "Navidrome" }

				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					MeloSectionLabel(
						title = "服务与连接",
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
						SettingsNavRow(
							title = meloConfig.effectiveName,
							value = if (meloConfig.isAuthenticated) "已连接" else "未连接",
							statusConnected = meloConfig.isAuthenticated,
							onClick = { currentSheet = "melokeep" }
						)

						MeloHairlineDivider(startIndent = 30.dp)

						SettingsNavRow(
							title = navidromeDisplayName,
							value = navidromeStatus,
							statusConnected = navidromeConnected,
							onClick = { currentSheet = "navidrome" }
						)
					}
				}
			}

			// 2. 播放与音频（仅保留 3 行：无缝播放、音质与增益、歌词与播放页）
			item(key = "hub-audio", contentType = "hub-audio") {
				val wifiTag = SettingsQualityShortTag(preferenceManager.streamingQualityWifi)
				val cellTag = SettingsQualityShortTag(preferenceManager.streamingQualityCellular)
				val rgSummary = when (preferenceManager.replayGainMode) {
					ReplayGainMode.Off -> null
					ReplayGainMode.Track -> "单曲增益"
					ReplayGainMode.Album -> "专辑增益"
					ReplayGainMode.Dynamic -> "动态增益"
				}
				val eqSummary = when (eqConfig.mode) {
					EqualizerMode.BuiltIn -> "内置 EQ"
					EqualizerMode.External -> "系统音效"
					EqualizerMode.Disabled -> null
				}
				val audioEngineSummary = listOfNotNull(
					"Wi-Fi $wifiTag · 移动 $cellTag",
					rgSummary,
					eqSummary
				).joinToString(" · ")

				val fontSizeLabel = "${preferenceManager.lyricsFontSizeSp.coerceIn(18f, 40f).roundToInt()}sp"
				val offsetMs = preferenceManager.playerLyricsOffsetMs
				val offsetLabel = when {
					offsetMs > 0 -> "+${offsetMs}ms"
					offsetMs < 0 -> "${offsetMs}ms"
					else -> "0ms"
				}

				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					MeloSectionLabel(title = "播放与音频")

					MeloGroupedCard {
						SettingsToggleRow(
							title = "无缝播放",
							checked = preferenceManager.gaplessPlayback,
							onCheckedChange = { preferenceManager.gaplessPlayback = it }
						)

						MeloHairlineDivider(startIndent = 16.dp)

						SettingsNavRow(
							title = "音质与增益",
							value = audioEngineSummary,
							onClick = { currentSheet = "audio" }
						)

						MeloHairlineDivider(startIndent = 16.dp)

						SettingsNavRow(
							title = "歌词与播放页",
							value = "$fontSizeLabel · 偏移 $offsetLabel",
							onClick = { currentSheet = "lyrics" }
						)
					}
				}
			}

			// 3. 外观与交互
			item(key = "hub-appearance", contentType = "hub-appearance") {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					MeloSectionLabel(title = "外观与交互")

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

						MeloGroupedCard {
							SettingsToggleRow(
								title = "下滑隐藏底栏",
								checked = preferenceManager.bottomBarCollapseMode == BottomBarCollapseMode.OnScroll,
								onCheckedChange = { enabled ->
									preferenceManager.bottomBarCollapseMode = if (enabled) {
										BottomBarCollapseMode.OnScroll
									} else {
										BottomBarCollapseMode.Never
									}
								}
							)

							MeloHairlineDivider(startIndent = 16.dp)

							SettingsToggleRow(
								title = "二级页显示播放栏",
								checked = preferenceManager.bottomBarVisibilityMode == BottomBarVisibilityMode.AllScreens,
								onCheckedChange = { showAll ->
									preferenceManager.bottomBarVisibilityMode = if (showAll) {
										BottomBarVisibilityMode.AllScreens
									} else {
										BottomBarVisibilityMode.Default
									}
								}
							)

							MeloHairlineDivider(startIndent = 16.dp)

							Column(
								modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
								verticalArrangement = Arrangement.spacedBy(8.dp)
							) {
								Row(
									modifier = Modifier.fillMaxWidth(),
									horizontalArrangement = Arrangement.SpaceBetween,
									verticalAlignment = Alignment.CenterVertically
								) {
									Text(
										text = "封面圆角",
										style = MaterialTheme.typography.bodyMedium,
										fontWeight = FontWeight.Medium
									)
									Text(
										text = SettingsCoverShapeLabel(preferenceManager.coverArtShape),
										style = MaterialTheme.typography.bodySmall,
										color = MaterialTheme.colorScheme.onSurfaceVariant
									)
								}
								Row(
									modifier = Modifier.fillMaxWidth(),
									horizontalArrangement = Arrangement.spacedBy(6.dp)
								) {
									CoverArtShape.entries.forEach { shape ->
										SettingsChoicePill(
											label = SettingsCoverShapeLabel(shape),
											selected = preferenceManager.coverArtShape == shape,
											onClick = { preferenceManager.coverArtShape = shape },
											modifier = Modifier.weight(1f)
										)
									}
								}
							}
						}
					}
				}
			}

			// 4. 存储与离线
			item(key = "hub-storage", contentType = "hub-storage") {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					MeloSectionLabel(title = "存储与离线")

					MeloGroupedCard {
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.padding(horizontal = 16.dp, vertical = 12.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(10.dp)
						) {
							Text(
								text = "临时缓存",
								style = MaterialTheme.typography.bodyMedium,
								fontWeight = FontWeight.Medium,
								modifier = Modifier.weight(1f)
							)
							Text(
								text = if (cacheSizeLoaded) tempCacheBytes.toFileSize() else "…",
								style = MaterialTheme.typography.bodySmall.tabularNums(),
								color = MaterialTheme.colorScheme.onSurfaceVariant
							)
							Text(
								text = if (clearingCache) "清理中" else "清理",
								style = MaterialTheme.typography.labelMedium,
								color = if (tempCacheBytes > 0L && !clearingCache) {
									MaterialTheme.colorScheme.primary
								} else {
									MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
								},
								modifier = Modifier
									.clip(RoundedCornerShape(6.dp))
									.clickable(enabled = !clearingCache && tempCacheBytes > 0L) {
										clearingCache = true
										scope.launch {
											withContext(Dispatchers.IO) {
												streamCacheManager.clear()
												imageLoader.memoryCache?.clear()
												imageLoader.diskCache?.clear()
												storageManager.clearCoverArts()
											}
											streamCacheBytes = 0L
											imageCacheBytes = 0L
											clearingCache = false
											snackBarState.currentSnackbarData?.dismiss()
											snackBarState.showSnackbar("已清理临时缓存")
										}
									}
									.padding(horizontal = 6.dp, vertical = 3.dp)
							)
						}

						MeloHairlineDivider(startIndent = 16.dp)

						SettingsNavRow(
							title = "离线模式与索引同步",
							onClick = { currentSheet = "storage" }
						)
					}
				}
			}

			// 5. 更新与关于
			item(key = "hub-update", contentType = "hub-update") {
				val updateTag = (checkFeedback as? UpdateCheckFeedback.UpdateAvailable)?.tag
					?: availableRelease?.tagName?.takeIf { it.isNotBlank() }
				val isChecking = checkFeedback is UpdateCheckFeedback.Checking
				val normalizedNewTag = updateTag?.let { "v" + it.trim().removePrefix("v").removePrefix("V") }

				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					MeloSectionLabel(title = "更新与关于")

					MeloGroupedCard {
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.clickable(enabled = !isChecking) {
									changelogViewModel.checkForUpdates(
										currentVersion = appVersionName,
										manual = true
									)
								}
								.padding(horizontal = 16.dp, vertical = 13.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(8.dp)
						) {
							Text(
								text = "检查更新",
								style = MaterialTheme.typography.bodyMedium,
								fontWeight = FontWeight.Medium,
								color = MaterialTheme.colorScheme.onSurface,
								modifier = Modifier.weight(1f)
							)
							when {
								isChecking -> {
									CircularProgressIndicator(
										modifier = Modifier.size(14.dp),
										strokeWidth = 1.8.dp
									)
									Text(
										text = "检查中…",
										style = MaterialTheme.typography.bodySmall,
										color = MaterialTheme.colorScheme.onSurfaceVariant
									)
								}
								normalizedNewTag != null -> {
									Box(
										modifier = Modifier
											.size(7.dp)
											.clip(CircleShape)
											.background(MaterialTheme.colorScheme.error)
									)
									Text(
										text = "发现新版本 $normalizedNewTag",
										style = MaterialTheme.typography.bodySmall.tabularNums(),
										fontWeight = FontWeight.SemiBold,
										color = MaterialTheme.colorScheme.primary
									)
								}
								else -> {
									Text(
										text = "当前 v$appVersionName",
										style = MaterialTheme.typography.bodySmall.tabularNums(),
										color = MaterialTheme.colorScheme.onSurfaceVariant
									)
								}
							}
							Icon(
								imageVector = Icons.Outlined.ChevronForward,
								contentDescription = null,
								tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
								modifier = Modifier.size(17.dp)
							)
						}

						MeloHairlineDivider(startIndent = 16.dp)

						SettingsToggleRow(
							title = "自动检查更新",
							checked = preferenceManager.checkForUpdates,
							onCheckedChange = { enabled ->
								preferenceManager.checkForUpdates = enabled
								if (enabled && preferenceManager.updateCheckFrequency == UpdateCheckFrequency.ManualOnly) {
									preferenceManager.updateCheckFrequency = UpdateCheckFrequency.Daily
								}
							}
						)

						AnimatedVisibility(visible = preferenceManager.checkForUpdates) {
							Column(
								modifier = Modifier
									.fillMaxWidth()
									.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
								verticalArrangement = Arrangement.spacedBy(6.dp)
							) {
								Row(
									modifier = Modifier.fillMaxWidth(),
									horizontalArrangement = Arrangement.spacedBy(6.dp)
								) {
									listOf(
										UpdateCheckFrequency.OnEveryLaunch to "每次启动",
										UpdateCheckFrequency.Daily to "每天",
										UpdateCheckFrequency.Weekly to "每周"
									).forEach { (freq, label) ->
										SettingsChoicePill(
											label = label,
											selected = preferenceManager.updateCheckFrequency == freq,
											onClick = { preferenceManager.updateCheckFrequency = freq },
											modifier = Modifier.weight(1f)
										)
									}
								}
							}
						}
					}
				}
			}

			// 6. 极简版本尾注
			item(key = "hub-version", contentType = "hub-version") {
				Text(
					text = "MeloKeep v$appVersionName",
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

	MeloKeepConnectionSheet(
		presented = currentSheet == "melokeep",
		onDismissRequest = { currentSheet = null }
	)
	MeloNavidromeConnectionSheet(
		presented = currentSheet == "navidrome",
		onDismissRequest = { currentSheet = null }
	)
	MeloAudioEngineSheet(
		presented = currentSheet == "audio",
		onDismissRequest = { currentSheet = null }
	)
	MeloLyricsAndStageSheet(
		presented = currentSheet == "lyrics",
		onDismissRequest = { currentSheet = null }
	)
	MeloStorageAndAboutSheet(
		presented = currentSheet == "storage",
		onDismissRequest = { currentSheet = null }
	)
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
