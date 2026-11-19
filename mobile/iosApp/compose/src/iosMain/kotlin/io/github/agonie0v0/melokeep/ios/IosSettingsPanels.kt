/*
 * Navic / MeloKeep, a Subsonic music streaming & studio app for iOS
 * Copyright (c) 2026 paige & MeloKeep Swarm
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.agonie0v0.melokeep.ios

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.agonie0v0.melokeep.shared.MeloSettingsController
import io.github.agonie0v0.melokeep.shared.MeloSettingsUiState
import kotlinx.coroutines.launch
import platform.Foundation.NSBundle
import kotlin.math.roundToInt

private const val IOS_LYRICS_OFFSET_MIN_MS = -5000
private const val IOS_LYRICS_OFFSET_MAX_MS = 5000
private const val IOS_LYRICS_OFFSET_STEP_MS = 100
private const val IOS_LYRICS_OFFSET_SLIDER_STEPS =
	(IOS_LYRICS_OFFSET_MAX_MS - IOS_LYRICS_OFFSET_MIN_MS) / IOS_LYRICS_OFFSET_STEP_MS - 1

// =============================================================================
// 1. 公共独立抽屉：歌词偏移 (IosLyricsAndStageSheet)
// =============================================================================

@Composable
fun IosLyricsAndStageSheet(
	audioPlayer: IosAudioPlayer,
	subsonicManager: IosSubsonicManager,
	presented: Boolean,
	onDismissRequest: () -> Unit,
) {
	if (!presented) return

	val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
	val playerState by audioPlayer.state.collectAsState()
	val prefs by subsonicManager.prefs.collectAsState()
	val currentOffset = playerState.lyricsOffsetMs.takeIf { it == prefs.lyricsOffsetMs } ?: prefs.lyricsOffsetMs

	ModalBottomSheet(
		onDismissRequest = onDismissRequest,
		sheetState = sheetState,
		containerColor = MaterialTheme.colorScheme.background,
		contentColor = MaterialTheme.colorScheme.onSurface,
		dragHandle = null,
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(bottom = 32.dp),
		) {
			IosCleanTopBar(
				title = "歌词偏移",
				onBack = onDismissRequest,
			)

			Column(
				modifier = Modifier
					.fillMaxWidth()
					.verticalScroll(rememberScrollState())
					.padding(horizontal = 16.dp, vertical = 8.dp),
				verticalArrangement = Arrangement.spacedBy(18.dp),
			) {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					IosSectionLabel(title = "播放页")
					IosGroupedCard {
						IosSettingsLyricsOffsetControl(
							title = "播放页歌词",
							offsetMs = currentOffset,
							onOffsetChange = { next ->
								audioPlayer.setLyricsOffset(next)
							},
							modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
						)
					}
				}
			}
		}
	}
}

// =============================================================================
// 2. 公共独立抽屉：音质与播放 (IosAudioEngineSheet)
// =============================================================================

@Composable
fun IosAudioEngineSheet(
	audioPlayer: IosAudioPlayer,
	subsonicManager: IosSubsonicManager,
	presented: Boolean,
	onDismissRequest: () -> Unit,
) {
	if (!presented) return

	val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
	val prefs by subsonicManager.prefs.collectAsState()

	ModalBottomSheet(
		onDismissRequest = onDismissRequest,
		sheetState = sheetState,
		containerColor = MaterialTheme.colorScheme.background,
		contentColor = MaterialTheme.colorScheme.onSurface,
		dragHandle = null,
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(bottom = 32.dp),
		) {
			IosCleanTopBar(
				title = "音质与播放",
				onBack = onDismissRequest,
			)

			Column(
				modifier = Modifier
					.fillMaxWidth()
					.verticalScroll(rememberScrollState())
					.padding(horizontal = 16.dp, vertical = 8.dp),
				verticalArrangement = Arrangement.spacedBy(18.dp),
			) {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					IosSectionLabel(title = "音质")
					IosGroupedCard {
						Column(
							modifier = Modifier.padding(16.dp),
							verticalArrangement = Arrangement.spacedBy(8.dp),
						) {
							Text(
								text = "流媒体码率上限",
								style = MaterialTheme.typography.bodyMedium,
								fontWeight = FontWeight.Medium,
							)
							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.spacedBy(6.dp),
							) {
								listOf(
									0 to "无损",
									320 to "320k",
									192 to "192k",
									128 to "128k",
								).forEach { (br, label) ->
									IosSettingsChoicePill(
										label = label,
										selected = prefs.maxBitRate == br,
										onClick = { subsonicManager.updatePrefs { it.copy(maxBitRate = br) } },
										modifier = Modifier.weight(1f),
									)
								}
							}
						}
					}
				}

				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					IosSectionLabel(title = "ReplayGain")
					IosGroupedCard {
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.padding(12.dp),
							horizontalArrangement = Arrangement.spacedBy(6.dp),
						) {
							listOf(
								ReplayGainMode.TRACK to "单曲",
								ReplayGainMode.ALBUM to "专辑",
								ReplayGainMode.OFF to "关闭",
							).forEach { (mode, label) ->
								IosSettingsChoicePill(
									label = label,
									selected = prefs.replayGainMode == mode,
									onClick = {
										subsonicManager.updatePrefs { it.copy(replayGainMode = mode) }
										audioPlayer.refreshReplayGain()
									},
									modifier = Modifier.weight(1f),
								)
							}
						}
					}
				}

				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					IosSectionLabel(title = "显示")
					IosGroupedCard {
						IosSettingsToggleRow(
							title = "AMOLED 纯黑",
							checked = prefs.amoledBlack,
							onCheckedChange = { checked ->
								subsonicManager.updatePrefs { it.copy(amoledBlack = checked) }
							},
						)
					}
				}
			}
		}
	}
}

// =============================================================================
// 3. 公共独立抽屉：音源与代理 (IosSourcesAndProxySheet)
// =============================================================================

@Composable
fun IosSourcesAndProxySheet(
	settingsController: MeloSettingsController,
	settingsState: MeloSettingsUiState,
	presented: Boolean,
	onDismissRequest: () -> Unit,
) {
	if (!presented) return

	val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
	val engCfg = settingsState.engineConfig
	var proxyUrl by remember(engCfg?.proxyUrl) { mutableStateOf(engCfg?.proxyUrl.orEmpty()) }
	var proxyScope by remember(engCfg?.proxyScope) { mutableStateOf(engCfg?.proxyScope?.ifBlank { "all" } ?: "all") }
	var sourceSearchQuery by rememberSaveable { mutableStateOf("") }
	var sourceCategoryFilter by rememberSaveable { mutableStateOf("ALL") }

	LaunchedEffect(presented, settingsState.serverConfig.isConfigured) {
		if (presented && settingsState.serverConfig.isConfigured && engCfg == null) {
			settingsController.loadConfig()
		}
	}

	ModalBottomSheet(
		onDismissRequest = onDismissRequest,
		sheetState = sheetState,
		containerColor = MaterialTheme.colorScheme.background,
		contentColor = MaterialTheme.colorScheme.onSurface,
		dragHandle = null,
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.height(640.dp)
				.imePadding(),
		) {
			IosCleanTopBar(
				title = "音源与代理",
				onBack = onDismissRequest,
				trailing = {
					if (settingsState.serverConfig.isConfigured) {
						IconButton(
							onClick = { settingsController.loadConfig() },
							enabled = !settingsState.loadingConfig,
						) {
							if (settingsState.loadingConfig) {
								CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
							} else {
								Icon(
									imageVector = Icons.Rounded.Refresh,
									contentDescription = "刷新配置",
									modifier = Modifier.size(19.dp),
								)
							}
						}
					}
				},
			)

			if (engCfg == null) {
				Box(
					modifier = Modifier
						.weight(1f)
						.fillMaxWidth()
						.padding(horizontal = 16.dp),
					contentAlignment = Alignment.Center,
				) {
					if (settingsState.loadingConfig) {
						CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
					} else {
						IosQuietEmptyState(
							message = if (settingsState.serverConfig.isConfigured) {
								"暂未获取到服务端引擎配置"
							} else {
								"请先连接 MeloKeep 服务"
							},
							actionLabel = if (settingsState.serverConfig.isConfigured) "重新加载" else null,
							onAction = if (settingsState.serverConfig.isConfigured) {
								{ settingsController.loadConfig() }
							} else {
								null
							},
						)
					}
				}
			} else {
				val allSources = engCfg.availableSources
				val enabledSet = remember(engCfg.sources) { engCfg.sources.toSet() }
				val categories = remember(allSources) {
					allSources.mapNotNull { it.category.trim().takeIf { c -> c.isNotEmpty() } }.distinct()
				}
				val filteredSources = remember(allSources, enabledSet, sourceSearchQuery, sourceCategoryFilter) {
					val q = sourceSearchQuery.trim().lowercase()
					allSources.filter { src ->
						val matchesCategory = when (sourceCategoryFilter) {
							"ALL" -> true
							"ENABLED" -> src.id in enabledSet
							else -> src.category.equals(sourceCategoryFilter, ignoreCase = true)
						}
						val matchesQuery = q.isEmpty() ||
							src.id.lowercase().contains(q) ||
							src.label.lowercase().contains(q) ||
							src.category.lowercase().contains(q) ||
							src.pluginId.lowercase().contains(q) ||
							src.description.lowercase().contains(q)
						matchesCategory && matchesQuery
					}
				}

				LazyColumn(
					modifier = Modifier
						.weight(1f)
						.fillMaxWidth(),
					contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 36.dp),
					verticalArrangement = Arrangement.spacedBy(16.dp),
				) {
					// 1. 代理与元数据
					item(key = "sources-proxy-card", contentType = "proxy") {
						Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
							IosSectionLabel(title = "代理与引擎")
							IosGroupedCard {
								Column(
									modifier = Modifier.padding(16.dp),
									verticalArrangement = Arrangement.spacedBy(10.dp),
								) {
									OutlinedTextField(
										value = proxyUrl,
										onValueChange = { proxyUrl = it },
										label = { Text("全局代理 URL") },
										placeholder = { Text("http://127.0.0.1:7890") },
										singleLine = true,
										shape = RoundedCornerShape(12.dp),
										modifier = Modifier.fillMaxWidth(),
									)

									Row(
										modifier = Modifier.fillMaxWidth(),
										horizontalArrangement = Arrangement.spacedBy(8.dp),
										verticalAlignment = Alignment.CenterVertically,
									) {
										FlowRow(
											modifier = Modifier.weight(1f),
											horizontalArrangement = Arrangement.spacedBy(8.dp),
											verticalArrangement = Arrangement.spacedBy(6.dp),
										) {
											listOf(
												"all" to "全部音源",
												"recommended" to "仅需代理",
											).forEach { (scopeKey, label) ->
												FilterChip(
													selected = proxyScope == scopeKey,
													onClick = { proxyScope = scopeKey },
													shape = CircleShape,
													label = { Text(label, maxLines = 1) },
												)
											}
										}

										FilledTonalButton(
											onClick = {
												settingsController.saveEngineSettings(
													proxyUrl = proxyUrl.trim(),
													proxyScope = proxyScope,
												)
											},
											enabled = !settingsState.savingConfig,
											shape = CircleShape,
											contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
											modifier = Modifier.height(34.dp),
										) {
											if (settingsState.savingConfig) {
												CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
											} else {
												Text("保存")
											}
										}
									}
								}

								IosHairlineDivider(startIndent = 16.dp)

								IosSettingsToggleRow(
									title = "自动内嵌封面与标签",
									checked = engCfg.embedMetadata,
									enabled = !settingsState.savingConfig,
									onCheckedChange = { checked ->
										settingsController.saveEngineSettings(embedMetadata = checked)
									},
								)
							}
						}
					}

					// 2. 音源搜索与分类筛选
					item(key = "sources-filter-header", contentType = "filter") {
						Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
							IosSectionLabel(
								title = "音源",
								trailing = {
									Text(
										text = "${enabledSet.size}/${allSources.size}",
										style = MaterialTheme.typography.labelSmall.tabularNums(),
										color = MaterialTheme.colorScheme.onSurfaceVariant,
									)
								},
							)

							OutlinedTextField(
								modifier = Modifier.fillMaxWidth(),
								value = sourceSearchQuery,
								onValueChange = { sourceSearchQuery = it },
								singleLine = true,
								shape = RoundedCornerShape(12.dp),
								placeholder = { Text("搜索音源 (${allSources.size})") },
								leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
								trailingIcon = {
									if (sourceSearchQuery.isNotEmpty()) {
										IconButton(onClick = { sourceSearchQuery = "" }) {
											Icon(Icons.Rounded.Close, contentDescription = "清除", modifier = Modifier.size(18.dp))
										}
									}
								},
							)

							LazyRow(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.spacedBy(6.dp),
							) {
								item(key = "ios-src-cat-all") {
									FilterChip(
										selected = sourceCategoryFilter == "ALL",
										onClick = { sourceCategoryFilter = "ALL" },
										shape = CircleShape,
										label = { Text("全部 ${allSources.size}", style = MaterialTheme.typography.labelSmall.tabularNums()) },
									)
								}
								item(key = "ios-src-cat-enabled") {
									FilterChip(
										selected = sourceCategoryFilter == "ENABLED",
										onClick = { sourceCategoryFilter = "ENABLED" },
										shape = CircleShape,
										label = { Text("已启用 ${enabledSet.size}", style = MaterialTheme.typography.labelSmall.tabularNums()) },
									)
								}
								items(categories, key = { "ios-src-cat-$it" }) { cat ->
									FilterChip(
										selected = sourceCategoryFilter == cat,
										onClick = { sourceCategoryFilter = cat },
										shape = CircleShape,
										label = { Text(cat, style = MaterialTheme.typography.labelSmall) },
									)
								}
							}
						}
					}

					// 3. 音源列表
					if (filteredSources.isEmpty()) {
						item(key = "sources-empty", contentType = "empty") {
							IosGroupedCard {
								IosQuietEmptyState(
									message = "无匹配音源",
									actionLabel = "重置筛选",
									onAction = {
										sourceSearchQuery = ""
										sourceCategoryFilter = "ALL"
									},
								)
							}
						}
					} else {
						item(key = "sources-list-card", contentType = "sources-card") {
							IosGroupedCard {
								filteredSources.forEachIndexed { idx, src ->
									val isEnabled = src.id in enabledSet
									val meta = buildList {
										add(src.id)
										if (src.origin.equals("plugin", ignoreCase = true)) add("插件")
										src.category.takeIf { it.isNotBlank() }?.let(::add)
										src.version.takeIf { it.isNotBlank() }?.let { add("v${it.removePrefix("v")}") }
										src.engineName.takeIf { it.isNotBlank() }?.let(::add)
										if (src.proxyRecommended) add("建议代理")
										src.initError?.takeIf { it.isNotBlank() }?.let { add("异常: $it") }
									}.joinToString(" · ")

									Row(
										modifier = Modifier
											.fillMaxWidth()
											.clickable(enabled = !settingsState.savingConfig) {
												settingsController.toggleSourceEnabled(src.id)
											}
											.padding(horizontal = 16.dp, vertical = 10.dp),
										verticalAlignment = Alignment.CenterVertically,
										horizontalArrangement = Arrangement.spacedBy(12.dp),
									) {
										Column(
											modifier = Modifier.weight(1f),
											verticalArrangement = Arrangement.spacedBy(2.dp),
										) {
											Text(
												text = src.label.ifBlank { src.id },
												style = MaterialTheme.typography.bodyMedium,
												fontWeight = FontWeight.Medium,
												maxLines = 1,
												overflow = TextOverflow.Ellipsis,
											)
											Text(
												text = meta,
												style = MaterialTheme.typography.bodySmall,
												color = if (!src.initError.isNullOrBlank()) {
													MaterialTheme.colorScheme.error
												} else {
													MaterialTheme.colorScheme.onSurfaceVariant
												},
												maxLines = 1,
												overflow = TextOverflow.Ellipsis,
											)
										}

										Switch(
											checked = isEnabled,
											enabled = !settingsState.savingConfig,
											onCheckedChange = { settingsController.toggleSourceEnabled(src.id) },
											colors = SwitchDefaults.colors(
												checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
												checkedTrackColor = MaterialTheme.colorScheme.primary,
											),
										)
									}

									if (idx < filteredSources.lastIndex) {
										IosHairlineDivider(startIndent = 16.dp)
									}
								}
							}
						}
					}
				}
			}
		}
	}
}

// =============================================================================
// 4. 统一设置首页抽屉（供「我的」顶栏「设置」按钮唤起）
// =============================================================================

@Composable
internal fun IosSettingsHomeSheet(
	settingsState: MeloSettingsUiState,
	audioPlayer: IosAudioPlayer,
	subsonicManager: IosSubsonicManager,
	presented: Boolean,
	onOpenMeloConnection: () -> Unit,
	onOpenNavidromeServers: () -> Unit,
	onOpenOnboarding: () -> Unit,
	onOpenAudioEngine: () -> Unit,
	onOpenSources: () -> Unit,
	onDismissRequest: () -> Unit,
) {
	if (!presented) return

	val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
	val playerState by audioPlayer.state.collectAsState()
	val prefs by subsonicManager.prefs.collectAsState()
	val libState by subsonicManager.state.collectAsState()
	val meloCfg = settingsState.serverConfig
	val appVersion = remember { IosSettingsResolveAppVersion() }

	ModalBottomSheet(
		onDismissRequest = onDismissRequest,
		sheetState = sheetState,
		containerColor = MaterialTheme.colorScheme.background,
		contentColor = MaterialTheme.colorScheme.onSurface,
		dragHandle = null,
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.height(620.dp),
		) {
			IosCleanTopBar(
				title = "设置",
				onBack = onDismissRequest,
			)

			LazyColumn(
				modifier = Modifier
					.weight(1f)
					.fillMaxWidth(),
				contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
				verticalArrangement = Arrangement.spacedBy(20.dp),
			) {
				// 1. 歌词偏移
				item(key = "ios-settings-lyrics") {
					Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
						IosSectionLabel(title = "歌词偏移")
						IosGroupedCard {
							IosSettingsLyricsOffsetControl(
								title = "播放页歌词",
								offsetMs = playerState.lyricsOffsetMs,
								onOffsetChange = { audioPlayer.setLyricsOffset(it) },
								modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
							)
						}
					}
				}

				// 2. 音频与显示
				item(key = "ios-settings-audio") {
					val brTag = if (prefs.maxBitRate <= 0) "无损" else "${prefs.maxBitRate}k"
					val rgTag = when (prefs.replayGainMode) {
						ReplayGainMode.TRACK -> "单曲增益"
						ReplayGainMode.ALBUM -> "专辑增益"
						ReplayGainMode.OFF -> "关闭"
					}
					Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
						IosSectionLabel(title = "音频与显示")
						IosGroupedCard {
							IosSettingsNavRow(
								title = "音质与 ReplayGain",
								value = "$brTag · $rgTag",
								onClick = {
									onDismissRequest()
									onOpenAudioEngine()
								},
							)
							IosHairlineDivider(startIndent = 16.dp)
							IosSettingsToggleRow(
								title = "AMOLED 纯黑",
								checked = prefs.amoledBlack,
								onCheckedChange = { checked ->
									subsonicManager.updatePrefs { it.copy(amoledBlack = checked) }
								},
							)
						}
					}
				}

				// 3. 服务
				item(key = "ios-settings-services") {
					Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
						IosSectionLabel(title = "服务")
						IosGroupedCard {
							IosSettingsNavRow(
								title = "MeloKeep 连接与设备",
								value = if (meloCfg.isAuthenticated) "已连接" else "未连接",
								statusConnected = meloCfg.isAuthenticated,
								onClick = {
									onDismissRequest()
									onOpenMeloConnection()
								},
							)
							IosHairlineDivider(startIndent = 16.dp)
							IosSettingsNavRow(
								title = "Navidrome 服务器",
								value = if (libState.activeServer != null) "已连接" else "未连接",
								statusConnected = libState.activeServer != null,
								onClick = {
									onDismissRequest()
									onOpenNavidromeServers()
								},
							)
							IosHairlineDivider(startIndent = 16.dp)
							IosSettingsNavRow(
								title = "连接向导",
								onClick = {
									onDismissRequest()
									onOpenOnboarding()
								},
							)
							IosHairlineDivider(startIndent = 16.dp)
							IosSettingsNavRow(
								title = "音源与代理",
								onClick = {
									onDismissRequest()
									onOpenSources()
								},
							)
						}
					}
				}

				// 4. 极简版本尾注
				item(key = "ios-settings-version") {
					Text(
						text = "MeloKeep v$appVersion",
						style = MaterialTheme.typography.labelSmall.tabularNums(),
						color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
						textAlign = TextAlign.Center,
						modifier = Modifier
							.fillMaxWidth()
							.padding(top = 4.dp),
					)
				}
			}
		}
	}
}

// =============================================================================
// 5. MeloKeep 连接与设备管理抽屉
// =============================================================================

@Composable
internal fun IosSettingsMeloConnectionSheet(
	settingsState: MeloSettingsUiState,
	settingsController: MeloSettingsController,
	hostNavBottom: Dp,
	onDismiss: () -> Unit,
) {
	val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
	var serverUrl by remember(settingsState.serverConfig.baseUrl) { mutableStateOf(settingsState.serverConfig.baseUrl) }
	var username by remember(settingsState.serverConfig.username) { mutableStateOf(settingsState.serverConfig.username) }
	var password by remember { mutableStateOf("") }
	var passVisible by remember { mutableStateOf(false) }

	LaunchedEffect(settingsState.serverConfig.isConfigured) {
		if (settingsState.serverConfig.isConfigured) {
			settingsController.refreshDevices()
		}
	}

	ModalBottomSheet(
		onDismissRequest = onDismiss,
		sheetState = sheetState,
		containerColor = MaterialTheme.colorScheme.background,
		contentColor = MaterialTheme.colorScheme.onSurface,
		dragHandle = null,
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.imePadding(),
		) {
			IosCleanTopBar(
				title = "MeloKeep 连接",
				onBack = onDismiss,
			)

			Column(
				modifier = Modifier
					.fillMaxWidth()
					.verticalScroll(rememberScrollState())
					.padding(horizontal = 16.dp)
					.padding(top = 6.dp, bottom = maxOf(hostNavBottom, 16.dp) + 20.dp),
				verticalArrangement = Arrangement.spacedBy(18.dp),
			) {
				IosGroupedCard {
					Column(
						modifier = Modifier.padding(16.dp),
						verticalArrangement = Arrangement.spacedBy(10.dp),
					) {
						OutlinedTextField(
							value = serverUrl,
							onValueChange = { serverUrl = it },
							label = { Text("服务器地址") },
							placeholder = { Text("http://192.168.1.10:8765") },
							singleLine = true,
							shape = RoundedCornerShape(12.dp),
							modifier = Modifier.fillMaxWidth(),
						)

						Row(
							modifier = Modifier
								.fillMaxWidth()
								.horizontalScroll(rememberScrollState()),
							horizontalArrangement = Arrangement.spacedBy(6.dp),
						) {
							listOf(
								"http://127.0.0.1:8765" to "127.0.0.1",
								"http://localhost:8765" to "localhost",
								"http://192.168." to "192.168.*",
							).forEach { (endpoint, label) ->
								Surface(
									shape = CircleShape,
									color = MaterialTheme.colorScheme.surfaceContainerHigh,
									border = BorderStroke(IosHairlineWidth, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
									modifier = Modifier
										.clip(CircleShape)
										.clickable { serverUrl = endpoint },
								) {
									Text(
										text = label,
										style = MaterialTheme.typography.labelSmall.tabularNums(),
										color = MaterialTheme.colorScheme.onSurfaceVariant,
										modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
									)
								}
							}
						}

						Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
							OutlinedTextField(
								value = username,
								onValueChange = { username = it },
								label = { Text("用户名") },
								placeholder = { Text("可选") },
								singleLine = true,
								shape = RoundedCornerShape(12.dp),
								modifier = Modifier.weight(1f),
							)
							OutlinedTextField(
								value = password,
								onValueChange = { password = it },
								label = { Text("密码") },
								placeholder = { Text("可选") },
								visualTransformation = if (passVisible) VisualTransformation.None else PasswordVisualTransformation(),
								trailingIcon = {
									IconButton(onClick = { passVisible = !passVisible }) {
										Icon(
											imageVector = if (passVisible) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
											contentDescription = null,
											modifier = Modifier.size(18.dp),
										)
									}
								},
								singleLine = true,
								shape = RoundedCornerShape(12.dp),
								modifier = Modifier.weight(1f),
							)
						}

						settingsState.errorMessage?.let {
							Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
						}
						settingsState.connectionMessage?.let {
							Text(it, color = MaterialTheme.colorScheme.meloPositive, style = MaterialTheme.typography.bodySmall)
						}

						Row(
							modifier = Modifier.fillMaxWidth(),
							horizontalArrangement = Arrangement.spacedBy(8.dp),
						) {
							Button(
								onClick = {
									settingsController.testAndLogin(
										baseUrl = serverUrl.trim(),
										username = username.trim(),
										password = password,
										deviceName = "MeloKeep iOS",
									)
								},
								enabled = !settingsState.testingConnection && serverUrl.isNotBlank(),
								shape = RoundedCornerShape(12.dp),
								modifier = Modifier.weight(1f),
							) {
								if (settingsState.testingConnection) {
									CircularProgressIndicator(
										modifier = Modifier.size(16.dp),
										strokeWidth = 2.dp,
										color = MaterialTheme.colorScheme.onPrimary,
									)
									Spacer(Modifier.width(8.dp))
								}
								Text("测试并保存")
							}

							if (settingsState.serverConfig.isConfigured) {
								OutlinedButton(
									onClick = { settingsController.logout() },
									shape = RoundedCornerShape(12.dp),
									border = BorderStroke(IosHairlineWidth, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
									colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
								) {
									Text("退出")
								}
							}
						}
					}
				}

				AnimatedVisibility(visible = settingsState.devices.isNotEmpty()) {
					Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
						IosSectionLabel(
							title = "已授权设备",
							trailing = {
								Text(
									text = "${settingsState.devices.size} 台",
									style = MaterialTheme.typography.labelSmall.tabularNums(),
									color = MaterialTheme.colorScheme.onSurfaceVariant,
								)
							},
						)
						IosGroupedCard {
							settingsState.devices.forEachIndexed { idx, device ->
								Row(
									modifier = Modifier
										.fillMaxWidth()
										.padding(horizontal = 16.dp, vertical = 11.dp),
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.spacedBy(12.dp),
								) {
									Column(
										modifier = Modifier.weight(1f),
										verticalArrangement = Arrangement.spacedBy(2.dp),
									) {
										Text(
											text = if (device.current) {
												"${device.deviceName.ifBlank { "未知设备" }} · 当前"
											} else {
												device.deviceName.ifBlank { "未知设备" }
											},
											style = MaterialTheme.typography.bodyMedium,
											fontWeight = FontWeight.Medium,
											maxLines = 1,
											overflow = TextOverflow.Ellipsis,
										)
										Text(
											text = "ID ${device.id}",
											style = MaterialTheme.typography.bodySmall.tabularNums(),
											color = MaterialTheme.colorScheme.onSurfaceVariant,
											maxLines = 1,
											overflow = TextOverflow.Ellipsis,
										)
									}
									if (!device.current) {
										Text(
											text = "吊销",
											style = MaterialTheme.typography.labelMedium,
											color = MaterialTheme.colorScheme.error,
											modifier = Modifier
												.clip(RoundedCornerShape(6.dp))
												.clickable { settingsController.revokeDevice(device.id) }
												.padding(horizontal = 8.dp, vertical = 4.dp),
										)
									}
								}
								if (idx < settingsState.devices.lastIndex) {
									IosHairlineDivider(startIndent = 16.dp)
								}
							}
						}
					}
				}
			}
		}
	}
}

// =============================================================================
// 6. Navidrome 多节点管理抽屉
// =============================================================================

@Composable
internal fun IosSettingsNavidromeServerSheet(
	servers: List<NavidromeServerProfile>,
	activeServerId: String?,
	hostNavBottom: Dp,
	onSwitchServer: (String) -> Unit,
	onRemoveServer: (String) -> Unit,
	onTestAndSave: suspend (name: String, url: String, user: String, pass: String, editId: String?) -> Result<NavidromeServerProfile>,
	onDismiss: () -> Unit,
) {
	val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
	val scope = rememberCoroutineScope()
	var showAddDialog by remember { mutableStateOf(false) }
	var editTarget by remember { mutableStateOf<NavidromeServerProfile?>(null) }
	var nameInput by remember { mutableStateOf("") }
	var urlInput by remember { mutableStateOf("") }
	var userInput by remember { mutableStateOf("") }
	var passInput by remember { mutableStateOf("") }
	var saving by remember { mutableStateOf(false) }
	var saveError by remember { mutableStateOf<String?>(null) }

	fun openEditor(server: NavidromeServerProfile?) {
		editTarget = server
		nameInput = server?.name.orEmpty()
		urlInput = server?.url.orEmpty()
		userInput = server?.username.orEmpty()
		passInput = server?.password.orEmpty()
		saveError = null
		showAddDialog = true
	}

	ModalBottomSheet(
		onDismissRequest = onDismiss,
		sheetState = sheetState,
		containerColor = MaterialTheme.colorScheme.background,
		contentColor = MaterialTheme.colorScheme.onSurface,
		dragHandle = null,
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(bottom = maxOf(hostNavBottom, 16.dp) + 20.dp),
		) {
			IosCleanTopBar(
				title = "Navidrome 服务器",
				onBack = onDismiss,
				trailing = {
					Surface(
						onClick = { openEditor(null) },
						shape = CircleShape,
						color = MaterialTheme.colorScheme.surfaceContainer,
						border = BorderStroke(IosHairlineWidth, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
					) {
						Row(
							modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(4.dp),
						) {
							Icon(
								imageVector = Icons.Rounded.Add,
								contentDescription = null,
								tint = MaterialTheme.colorScheme.primary,
								modifier = Modifier.size(15.dp),
							)
							Text(
								text = "添加",
								style = MaterialTheme.typography.labelMedium,
								fontWeight = FontWeight.Medium,
								color = MaterialTheme.colorScheme.onSurface,
							)
						}
					}
				},
			)

			Column(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp, vertical = 8.dp),
			) {
				if (servers.isEmpty()) {
					IosGroupedCard {
						IosQuietEmptyState(
							message = "尚未配置 Navidrome 服务器",
							actionLabel = "添加节点",
							onAction = { openEditor(null) },
						)
					}
				} else {
					IosGroupedCard {
						LazyColumn(
							modifier = Modifier.fillMaxWidth().height(280.dp),
						) {
							itemsIndexed(servers, key = { _, s -> s.id }) { index, server ->
								val isActive = server.id == activeServerId
								Row(
									modifier = Modifier
										.fillMaxWidth()
										.clickable { if (!isActive) onSwitchServer(server.id) }
										.padding(horizontal = 16.dp, vertical = 12.dp),
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.spacedBy(10.dp),
								) {
									Box(
										modifier = Modifier
											.size(8.dp)
											.clip(CircleShape)
											.background(
												if (isActive) MaterialTheme.colorScheme.meloPositive
												else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
											),
									)
									Column(
										modifier = Modifier.weight(1f),
										verticalArrangement = Arrangement.spacedBy(2.dp),
									) {
										Text(
											text = if (isActive) "${server.name} · 当前" else server.name,
											style = MaterialTheme.typography.bodyMedium,
											fontWeight = FontWeight.Medium,
											maxLines = 1,
											overflow = TextOverflow.Ellipsis,
										)
										Text(
											text = "${server.url} · ${server.username}",
											style = MaterialTheme.typography.bodySmall,
											color = MaterialTheme.colorScheme.onSurfaceVariant,
											maxLines = 1,
											overflow = TextOverflow.Ellipsis,
										)
									}
									Row(verticalAlignment = Alignment.CenterVertically) {
										if (!isActive) {
											Text(
												text = "切换",
												style = MaterialTheme.typography.labelMedium,
												color = MaterialTheme.colorScheme.primary,
												modifier = Modifier
													.clip(RoundedCornerShape(6.dp))
													.clickable { onSwitchServer(server.id) }
													.padding(horizontal = 8.dp, vertical = 4.dp),
											)
										}
										IconButton(onClick = { openEditor(server) }, modifier = Modifier.size(32.dp)) {
											Icon(Icons.Rounded.Edit, contentDescription = "编辑", modifier = Modifier.size(16.dp))
										}
										IconButton(onClick = { onRemoveServer(server.id) }, modifier = Modifier.size(32.dp)) {
											Icon(
												Icons.Rounded.Delete,
												contentDescription = "删除",
												modifier = Modifier.size(16.dp),
												tint = MaterialTheme.colorScheme.error,
											)
										}
									}
								}
								if (index < servers.lastIndex) {
									IosHairlineDivider(startIndent = 34.dp)
								}
							}
						}
					}
				}
			}
		}
	}

	if (showAddDialog) {
		AlertDialog(
			onDismissRequest = { if (!saving) showAddDialog = false },
			title = { Text(if (editTarget != null) "编辑 Navidrome 服务器" else "添加 Navidrome 服务器") },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					OutlinedTextField(
						value = nameInput,
						onValueChange = { nameInput = it },
						label = { Text("别名（可选）") },
						singleLine = true,
						enabled = !saving,
						shape = RoundedCornerShape(12.dp),
					)
					OutlinedTextField(
						value = urlInput,
						onValueChange = { urlInput = it },
						label = { Text("服务器地址") },
						placeholder = { Text("http://192.168.1.10:4533") },
						singleLine = true,
						enabled = !saving,
						shape = RoundedCornerShape(12.dp),
					)
					OutlinedTextField(
						value = userInput,
						onValueChange = { userInput = it },
						label = { Text("用户名") },
						singleLine = true,
						enabled = !saving,
						shape = RoundedCornerShape(12.dp),
					)
					OutlinedTextField(
						value = passInput,
						onValueChange = { passInput = it },
						label = { Text("密码") },
						singleLine = true,
						enabled = !saving,
						shape = RoundedCornerShape(12.dp),
					)
					if (!saveError.isNullOrBlank()) {
						Text(saveError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
					}
				}
			},
			confirmButton = {
				TextButton(
					onClick = {
						if (urlInput.isNotBlank() && userInput.isNotBlank() && !saving) {
							scope.launch {
								saving = true
								saveError = null
								val result = onTestAndSave(
									nameInput.ifBlank { "Navidrome" },
									urlInput.trim(),
									userInput.trim(),
									passInput,
									editTarget?.id,
								)
								saving = false
								result.onSuccess {
									showAddDialog = false
								}.onFailure { err ->
									saveError = err.message ?: "连接或验证失败"
								}
							}
						}
					},
					enabled = !saving && urlInput.isNotBlank() && userInput.isNotBlank(),
				) {
					Text(if (saving) "测试中…" else "保存")
				}
			},
			dismissButton = {
				TextButton(onClick = { if (!saving) showAddDialog = false }, enabled = !saving) {
					Text("取消")
				}
			},
		)
	}
}

// =============================================================================
// 7. 跨设置文件共享原语（统一 IosSettings 前缀）
// =============================================================================

@Composable
internal fun IosSettingsToggleRow(
	title: String,
	checked: Boolean,
	onCheckedChange: (Boolean) -> Unit,
	enabled: Boolean = true,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(enabled = enabled) { onCheckedChange(!checked) }
			.padding(horizontal = 16.dp, vertical = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Text(
			text = title,
			style = MaterialTheme.typography.bodyMedium,
			fontWeight = FontWeight.Medium,
			color = MaterialTheme.colorScheme.onSurface,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		Spacer(modifier = Modifier.width(12.dp))
		Switch(
			checked = checked,
			enabled = enabled,
			onCheckedChange = onCheckedChange,
			colors = SwitchDefaults.colors(
				checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
				checkedTrackColor = MaterialTheme.colorScheme.primary,
			),
		)
	}
}

@Composable
internal fun IosSettingsNavRow(
	title: String,
	value: String? = null,
	statusConnected: Boolean? = null,
	onClick: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(horizontal = 16.dp, vertical = 13.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		if (statusConnected != null) {
			Box(
				modifier = Modifier
					.size(7.dp)
					.clip(CircleShape)
					.background(
						if (statusConnected) MaterialTheme.colorScheme.meloPositive
						else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
					),
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
		if (!value.isNullOrBlank()) {
			Text(
				text = value,
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
internal fun IosSettingsChoicePill(
	label: String,
	selected: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Surface(
		onClick = onClick,
		shape = RoundedCornerShape(10.dp),
		color = if (selected) {
			MaterialTheme.colorScheme.primary
		} else {
			MaterialTheme.colorScheme.surfaceContainerHigh
		},
		modifier = modifier,
	) {
		Box(
			modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
			contentAlignment = Alignment.Center,
		) {
			Text(
				text = label,
				style = MaterialTheme.typography.labelSmall,
				fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
				color = if (selected) {
					MaterialTheme.colorScheme.onPrimary
				} else {
					MaterialTheme.colorScheme.onSurface
				},
				maxLines = 1,
				softWrap = false,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

@Composable
internal fun IosSettingsStepPill(
	label: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	highlighted: Boolean = false,
) {
	Surface(
		onClick = onClick,
		shape = RoundedCornerShape(8.dp),
		color = if (highlighted) {
			MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
		} else {
			MaterialTheme.colorScheme.surfaceContainerHigh
		},
		border = BorderStroke(
			width = IosHairlineWidth,
			color = if (highlighted) {
				MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
			} else {
				MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
			},
		),
		modifier = modifier,
	) {
		Box(
			modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
			contentAlignment = Alignment.Center,
		) {
			Text(
				text = label,
				style = MaterialTheme.typography.labelSmall.tabularNums(),
				fontWeight = FontWeight.Medium,
				color = if (highlighted) {
					MaterialTheme.colorScheme.primary
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
				maxLines = 1,
				softWrap = false,
				overflow = TextOverflow.Clip,
			)
		}
	}
}

@Composable
internal fun IosSettingsLyricsOffsetControl(
	title: String,
	offsetMs: Int,
	onOffsetChange: (Int) -> Unit,
	modifier: Modifier = Modifier,
) {
	val clampedOffset = offsetMs.coerceIn(IOS_LYRICS_OFFSET_MIN_MS, IOS_LYRICS_OFFSET_MAX_MS)

	Column(
		modifier = modifier.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = title,
				style = MaterialTheme.typography.bodyMedium,
				fontWeight = FontWeight.Medium,
				color = MaterialTheme.colorScheme.onSurface,
			)
			Text(
				text = IosSettingsFormatOffsetLabel(offsetMs),
				style = MaterialTheme.typography.labelMedium.tabularNums(),
				fontWeight = FontWeight.SemiBold,
				color = if (offsetMs != 0) {
					MaterialTheme.colorScheme.primary
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
			)
		}

		Slider(
			value = clampedOffset.toFloat(),
			onValueChange = { raw ->
				val stepped = ((raw / IOS_LYRICS_OFFSET_STEP_MS.toFloat()).roundToInt() * IOS_LYRICS_OFFSET_STEP_MS)
					.coerceIn(IOS_LYRICS_OFFSET_MIN_MS, IOS_LYRICS_OFFSET_MAX_MS)
				if (stepped != offsetMs) {
					onOffsetChange(stepped)
				}
			},
			valueRange = IOS_LYRICS_OFFSET_MIN_MS.toFloat()..IOS_LYRICS_OFFSET_MAX_MS.toFloat(),
			steps = IOS_LYRICS_OFFSET_SLIDER_STEPS,
			modifier = Modifier.fillMaxWidth(),
			colors = SliderDefaults.colors(
				thumbColor = MaterialTheme.colorScheme.primary,
				activeTrackColor = MaterialTheme.colorScheme.primary,
				activeTickColor = Color.Transparent,
				inactiveTickColor = Color.Transparent,
			),
		)

		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(6.dp),
		) {
			IosSettingsStepPill(
				label = "-500",
				onClick = {
					onOffsetChange((offsetMs - 500).coerceIn(IOS_LYRICS_OFFSET_MIN_MS, IOS_LYRICS_OFFSET_MAX_MS))
				},
				modifier = Modifier.weight(1f),
			)
			IosSettingsStepPill(
				label = "-100",
				onClick = {
					onOffsetChange((offsetMs - 100).coerceIn(IOS_LYRICS_OFFSET_MIN_MS, IOS_LYRICS_OFFSET_MAX_MS))
				},
				modifier = Modifier.weight(1f),
			)
			IosSettingsStepPill(
				label = "重置",
				highlighted = offsetMs != 0,
				onClick = { onOffsetChange(0) },
				modifier = Modifier.weight(0.95f),
			)
			IosSettingsStepPill(
				label = "+100",
				onClick = {
					onOffsetChange((offsetMs + 100).coerceIn(IOS_LYRICS_OFFSET_MIN_MS, IOS_LYRICS_OFFSET_MAX_MS))
				},
				modifier = Modifier.weight(1f),
			)
			IosSettingsStepPill(
				label = "+500",
				onClick = {
					onOffsetChange((offsetMs + 500).coerceIn(IOS_LYRICS_OFFSET_MIN_MS, IOS_LYRICS_OFFSET_MAX_MS))
				},
				modifier = Modifier.weight(1f),
			)
		}
	}
}

internal fun IosSettingsFormatOffsetLabel(offsetMs: Int): String = when {
	offsetMs > 0 -> "+$offsetMs ms"
	offsetMs < 0 -> "$offsetMs ms"
	else -> "0 ms"
}

internal fun IosSettingsResolveAppVersion(): String {
	val raw = NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String
	return raw?.trim()?.takeIf { it.isNotEmpty() } ?: "1.0.0"
}
