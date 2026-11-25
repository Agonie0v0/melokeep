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
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.OfflinePin
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.agonie0v0.melokeep.shared.MeloApiException
import io.github.agonie0v0.melokeep.shared.MeloGitHubRelease
import io.github.agonie0v0.melokeep.shared.MeloSettingsController
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import io.github.agonie0v0.melokeep.shared.MeloSettingsUiState
import io.github.agonie0v0.melokeep.shared.MeloSystemUpdateSettings
import io.github.agonie0v0.melokeep.shared.findIosIpaAsset
import io.github.agonie0v0.melokeep.shared.hasIosUpdateFor
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import platform.Foundation.NSBundle
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import kotlin.math.abs
import kotlin.math.roundToInt

private const val IOS_LYRICS_OFFSET_MIN_MS = -5000
private const val IOS_LYRICS_OFFSET_MAX_MS = 5000
private const val IOS_LYRICS_OFFSET_STEP_MS = 100
private const val IOS_LYRICS_OFFSET_SLIDER_STEPS =
	(IOS_LYRICS_OFFSET_MAX_MS - IOS_LYRICS_OFFSET_MIN_MS) / IOS_LYRICS_OFFSET_STEP_MS - 1

private val IOS_LYRICS_COLOR_PRESETS = listOf(
	"" to "跟随主题",
	"#FFFFFF" to "纯白",
	"#60A5FA" to "冰蓝",
	"#FACC15" to "流金",
	"#4ADE80" to "翡翠",
	"#FB7185" to "珊瑚",
	"#A78BFA" to "紫罗兰",
)

// =============================================================================
// 1. 公共独立抽屉：歌词设置 (IosLyricsAndStageSheet)
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
				.imePadding()
				.padding(bottom = 32.dp),
		) {
			IosCleanTopBar(
				title = "歌词设置",
				onBack = onDismissRequest,
			)

			Column(
				modifier = Modifier
					.fillMaxWidth()
					.verticalScroll(rememberScrollState())
					.padding(horizontal = 16.dp, vertical = 8.dp),
				verticalArrangement = Arrangement.spacedBy(18.dp),
			) {
				IosSettingsLyricsSectionCard(
					offsetMs = currentOffset,
					prefs = prefs,
					onOffsetChange = { next -> audioPlayer.setLyricsOffset(next) },
					onUpdatePrefs = { transform -> subsonicManager.updatePrefs(transform) },
					onResetAll = { resetIosLyricsPreferences(audioPlayer, subsonicManager) },
				)
			}
		}
	}
}

// =============================================================================
// 2. 公共独立抽屉：音质与增益 (IosAudioEngineSheet)
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
				title = "音质与增益",
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
						Column(modifier = Modifier.fillMaxWidth()) {
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
											subsonicManager.updatePrefs {
												it.copy(
													replayGainMode = mode,
													replayGainDefaultMigrated = true,
												)
											}
											audioPlayer.refreshReplayGain()
										},
										modifier = Modifier.weight(1f),
									)
								}
							}

							IosHairlineDivider(startIndent = 16.dp)

							val clampedPreamp = prefs.replayGainPreampDb.coerceIn(-6f, 12f)
							Column(
								modifier = Modifier
									.fillMaxWidth()
									.padding(horizontal = 16.dp, vertical = 12.dp),
								verticalArrangement = Arrangement.spacedBy(6.dp),
							) {
								Row(
									modifier = Modifier.fillMaxWidth(),
									horizontalArrangement = Arrangement.SpaceBetween,
									verticalAlignment = Alignment.CenterVertically,
								) {
									Text(
										text = "前级增益 (Preamp)",
										style = MaterialTheme.typography.bodyMedium,
										fontWeight = FontWeight.Medium,
									)
									Row(
										verticalAlignment = Alignment.CenterVertically,
										horizontalArrangement = Arrangement.spacedBy(8.dp),
									) {
										Text(
											text = formatIosDbLabel(clampedPreamp),
											style = MaterialTheme.typography.labelMedium.tabularNums(),
											fontWeight = FontWeight.SemiBold,
											color = if (prefs.replayGainMode != ReplayGainMode.OFF) {
												MaterialTheme.colorScheme.primary
											} else {
												MaterialTheme.colorScheme.onSurfaceVariant
											},
										)
										if (abs(clampedPreamp - 6.0f) > 0.05f) {
											Text(
												text = "重置",
												style = MaterialTheme.typography.labelSmall,
												color = MaterialTheme.colorScheme.primary,
												modifier = Modifier
													.clip(RoundedCornerShape(6.dp))
													.clickable {
														subsonicManager.updatePrefs { it.copy(replayGainPreampDb = 6.0f) }
														audioPlayer.refreshReplayGain()
													}
													.padding(horizontal = 4.dp, vertical = 2.dp),
											)
										}
									}
								}
								Slider(
									value = clampedPreamp,
									onValueChange = { raw ->
										val stepped = ((raw * 2f).roundToInt() / 2f).coerceIn(-6f, 12f)
										if (abs(stepped - clampedPreamp) >= 0.05f) {
											subsonicManager.updatePrefs { it.copy(replayGainPreampDb = stepped) }
											audioPlayer.refreshReplayGain()
										}
									},
									valueRange = -6f..12f,
									steps = 35,
									modifier = Modifier.fillMaxWidth(),
									colors = SliderDefaults.colors(
										thumbColor = MaterialTheme.colorScheme.primary,
										activeTrackColor = MaterialTheme.colorScheme.primary,
										activeTickColor = Color.Transparent,
										inactiveTickColor = Color.Transparent,
									),
								)
								Text(
									text = if (prefs.replayGainMode == ReplayGainMode.OFF) {
										"当前 ReplayGain 已关闭，曲目按原始音量无衰减播放；开启单曲或专辑模式后将叠加此前级增益（默认 +6.0 dB）。"
									} else {
										"范围 -6.0 dB ~ +12.0 dB（默认 +6.0 dB），用于补偿音轨响度衰减。"
									},
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
								)
							}
						}
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
				// 1. 歌词设置（统一归拢偏移与样式自定义）
				item(key = "ios-settings-lyrics") {
					Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
						IosSectionLabel(title = "歌词设置")
						IosSettingsLyricsSectionCard(
							offsetMs = playerState.lyricsOffsetMs,
							prefs = prefs,
							onOffsetChange = { audioPlayer.setLyricsOffset(it) },
							onUpdatePrefs = { transform -> subsonicManager.updatePrefs(transform) },
							onResetAll = { resetIosLyricsPreferences(audioPlayer, subsonicManager) },
						)
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
						text = "MeloKeep ${normalizeIosVersionLabel(appVersion, fallback = "1.0.0")}",
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
			IosMeloConnectionBody(
				settingsState = settingsState,
				settingsController = settingsController,
				hostNavBottom = hostNavBottom,
			)
		}
	}
}

@Composable
private fun IosMeloConnectionBody(
	settingsState: MeloSettingsUiState,
	settingsController: MeloSettingsController,
	hostNavBottom: Dp,
) {
	var displayName by remember(settingsState.serverConfig.displayName) {
		mutableStateOf(settingsState.serverConfig.displayName)
	}
	var serverUrl by remember(settingsState.serverConfig.baseUrl) { mutableStateOf(settingsState.serverConfig.baseUrl) }
	var username by remember(settingsState.serverConfig.username) { mutableStateOf(settingsState.serverConfig.username) }
	var password by remember { mutableStateOf("") }
	var passVisible by remember { mutableStateOf(false) }

	LaunchedEffect(settingsState.serverConfig.isConfigured) {
		if (settingsState.serverConfig.isConfigured) {
			settingsController.refreshDevices()
		}
	}

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
							value = displayName,
							onValueChange = { displayName = it },
							label = { Text("服务名称（可选，如 家里 NAS）") },
							placeholder = { Text("MeloKeep") },
							singleLine = true,
							shape = RoundedCornerShape(12.dp),
							modifier = Modifier.fillMaxWidth(),
						)

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
										displayName = displayName.trim(),
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
								if (displayName.trim() != settingsState.serverConfig.displayName) {
									OutlinedButton(
										onClick = { settingsController.updateDisplayName(displayName.trim()) },
										shape = RoundedCornerShape(12.dp),
									) {
										Text("保存名称")
									}
								}
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

				androidx.compose.animation.AnimatedVisibility(visible = settingsState.devices.isNotEmpty()) {
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

// =============================================================================
// 6. Navidrome 多节点管理 & 统一服务器连接抽屉
// =============================================================================

@Stable
private class IosNavidromeEditorState {
	var showDialog by mutableStateOf(false)
	var editTarget by mutableStateOf<NavidromeServerProfile?>(null)
	var nameInput by mutableStateOf("")
	var urlInput by mutableStateOf("")
	var userInput by mutableStateOf("")
	var passInput by mutableStateOf("")
	var saving by mutableStateOf(false)
	var saveError by mutableStateOf<String?>(null)

	fun openEditor(server: NavidromeServerProfile?) {
		editTarget = server
		nameInput = server?.name.orEmpty()
		urlInput = server?.url.orEmpty()
		userInput = server?.username.orEmpty()
		passInput = server?.password.orEmpty()
		saveError = null
		showDialog = true
	}
}

@Composable
private fun rememberNavidromeEditorState(): IosNavidromeEditorState = remember { IosNavidromeEditorState() }

@Composable
private fun IosNavidromeAddButton(onClick: () -> Unit) {
	Surface(
		onClick = onClick,
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
}

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
	val editorState = rememberNavidromeEditorState()

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
					IosNavidromeAddButton(onClick = { editorState.openEditor(null) })
				},
			)
			IosNavidromeServerBody(
				servers = servers,
				activeServerId = activeServerId,
				editorState = editorState,
				onSwitchServer = onSwitchServer,
				onRemoveServer = onRemoveServer,
			)
		}
	}

	IosNavidromeEditorDialog(
		editorState = editorState,
		scope = scope,
		onTestAndSave = onTestAndSave,
	)
}

@Composable
private fun IosNavidromeServerBody(
	servers: List<NavidromeServerProfile>,
	activeServerId: String?,
	editorState: IosNavidromeEditorState,
	onSwitchServer: (String) -> Unit,
	onRemoveServer: (String) -> Unit,
) {
	fun openEditor(server: NavidromeServerProfile?) = editorState.openEditor(server)

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

@Composable
private fun IosNavidromeEditorDialog(
	editorState: IosNavidromeEditorState,
	scope: CoroutineScope,
	onTestAndSave: suspend (name: String, url: String, user: String, pass: String, editId: String?) -> Result<NavidromeServerProfile>,
) {
	if (!editorState.showDialog) return
	AlertDialog(
		onDismissRequest = { if (!editorState.saving) editorState.showDialog = false },
		title = { Text(if (editorState.editTarget != null) "编辑 Navidrome 服务器" else "添加 Navidrome 服务器") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				OutlinedTextField(
					value = editorState.nameInput,
					onValueChange = { editorState.nameInput = it },
					label = { Text("别名（可选）") },
					singleLine = true,
					enabled = !editorState.saving,
					shape = RoundedCornerShape(12.dp),
				)
				OutlinedTextField(
					value = editorState.urlInput,
					onValueChange = { editorState.urlInput = it },
					label = { Text("服务器地址") },
					placeholder = { Text("http://192.168.1.10:4533") },
					singleLine = true,
					enabled = !editorState.saving,
					shape = RoundedCornerShape(12.dp),
				)
				OutlinedTextField(
					value = editorState.userInput,
					onValueChange = { editorState.userInput = it },
					label = { Text("用户名") },
					singleLine = true,
					enabled = !editorState.saving,
					shape = RoundedCornerShape(12.dp),
				)
				OutlinedTextField(
					value = editorState.passInput,
					onValueChange = { editorState.passInput = it },
					label = { Text("密码") },
					singleLine = true,
					enabled = !editorState.saving,
					shape = RoundedCornerShape(12.dp),
				)
				if (!editorState.saveError.isNullOrBlank()) {
					Text(
						editorState.saveError.orEmpty(),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.error,
					)
				}
			}
		},
		confirmButton = {
			TextButton(
				onClick = {
					if (editorState.urlInput.isNotBlank() && editorState.userInput.isNotBlank() && !editorState.saving) {
						scope.launch {
							editorState.saving = true
							editorState.saveError = null
							val result = onTestAndSave(
								editorState.nameInput.ifBlank { "Navidrome" },
								editorState.urlInput.trim(),
								editorState.userInput.trim(),
								editorState.passInput,
								editorState.editTarget?.id,
							)
							editorState.saving = false
							result.onSuccess {
								editorState.showDialog = false
							}.onFailure { err ->
								editorState.saveError = err.message ?: "连接或验证失败"
							}
						}
					}
				},
				enabled = !editorState.saving && editorState.urlInput.isNotBlank() && editorState.userInput.isNotBlank(),
			) {
				Text(if (editorState.saving) "测试中…" else "保存")
			}
		},
		dismissButton = {
			TextButton(
				onClick = { if (!editorState.saving) editorState.showDialog = false },
				enabled = !editorState.saving,
			) {
				Text("取消")
			}
		},
	)
}

@Composable
internal fun IosSettingsServerConnectionsSheet(
	initialTab: Int = 0,
	settingsState: MeloSettingsUiState,
	settingsController: MeloSettingsController,
	servers: List<NavidromeServerProfile>,
	activeServerId: String?,
	hostNavBottom: Dp,
	onSwitchServer: (String) -> Unit,
	onRemoveServer: (String) -> Unit,
	onTestAndSaveNavidrome: suspend (name: String, url: String, user: String, pass: String, editId: String?) -> Result<NavidromeServerProfile>,
	onDismiss: () -> Unit,
) {
	val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
	val scope = rememberCoroutineScope()
	val editorState = rememberNavidromeEditorState()
	var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 1)) }
	val meloConnected = settingsState.serverConfig.isAuthenticated
	val navidromeConnected = activeServerId != null && servers.any { it.id == activeServerId }

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
				title = "服务器连接",
				onBack = onDismiss,
				trailing = {
					if (selectedTab == 1) {
						IosNavidromeAddButton(onClick = { editorState.openEditor(null) })
					}
				},
			)

			Surface(
				shape = RoundedCornerShape(12.dp),
				color = MaterialTheme.colorScheme.surfaceContainer,
				border = BorderStroke(IosHairlineWidth, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp, vertical = 6.dp),
			) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(4.dp),
					horizontalArrangement = Arrangement.spacedBy(4.dp),
				) {
					listOf(
						Triple(0, "MeloKeep", meloConnected),
						Triple(1, "Navidrome", navidromeConnected),
					).forEach { (tabIdx, tabTitle, connected) ->
						val active = selectedTab == tabIdx
						Surface(
							onClick = { selectedTab = tabIdx },
							shape = RoundedCornerShape(9.dp),
							color = if (active) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
							border = if (active) {
								BorderStroke(IosHairlineWidth, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
							} else {
								null
							},
							modifier = Modifier.weight(1f),
						) {
							Row(
								modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
								horizontalArrangement = Arrangement.Center,
								verticalAlignment = Alignment.CenterVertically,
							) {
								Box(
									modifier = Modifier
										.size(7.dp)
										.clip(CircleShape)
										.background(
											if (connected) MaterialTheme.colorScheme.meloPositive
											else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
										),
								)
								Spacer(Modifier.width(6.dp))
								Text(
									text = tabTitle,
									style = MaterialTheme.typography.labelLarge,
									fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
									color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
								)
							}
						}
					}
				}
			}

			when (selectedTab) {
				0 -> IosMeloConnectionBody(
					settingsState = settingsState,
					settingsController = settingsController,
					hostNavBottom = hostNavBottom,
				)
				else -> Box(
					modifier = Modifier
						.fillMaxWidth()
						.padding(bottom = maxOf(hostNavBottom, 16.dp) + 20.dp),
				) {
					IosNavidromeServerBody(
						servers = servers,
						activeServerId = activeServerId,
						editorState = editorState,
						onSwitchServer = onSwitchServer,
						onRemoveServer = onRemoveServer,
					)
				}
			}
		}
	}

	IosNavidromeEditorDialog(
		editorState = editorState,
		scope = scope,
		onTestAndSave = onTestAndSaveNavidrome,
	)
}

// =============================================================================
// 6b. 统一命名别名抽屉与主题外观 / 存储缓存抽屉
// =============================================================================

@Composable
internal fun IosSettingsSourcesProxySheet(
	settingsController: MeloSettingsController,
	settingsState: MeloSettingsUiState,
	presented: Boolean,
	onDismissRequest: () -> Unit,
) {
	IosSourcesAndProxySheet(
		settingsController = settingsController,
		settingsState = settingsState,
		presented = presented,
		onDismissRequest = onDismissRequest,
	)
}

@Composable
internal fun IosSettingsAudioEngineSheet(
	audioPlayer: IosAudioPlayer,
	subsonicManager: IosSubsonicManager,
	presented: Boolean,
	onDismissRequest: () -> Unit,
) {
	IosAudioEngineSheet(
		audioPlayer = audioPlayer,
		subsonicManager = subsonicManager,
		presented = presented,
		onDismissRequest = onDismissRequest,
	)
}

@Composable
internal fun IosSettingsLyricsSheet(
	audioPlayer: IosAudioPlayer,
	subsonicManager: IosSubsonicManager,
	presented: Boolean,
	onDismissRequest: () -> Unit,
) {
	IosLyricsAndStageSheet(
		audioPlayer = audioPlayer,
		subsonicManager = subsonicManager,
		presented = presented,
		onDismissRequest = onDismissRequest,
	)
}

@Composable
internal fun IosSettingsThemeAppearanceSheet(
	settingsStore: MeloSettingsStore,
	subsonicManager: IosSubsonicManager,
	hostNavBottom: Dp,
	presented: Boolean,
	onDismissRequest: () -> Unit,
) {
	if (!presented) return

	val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
	val studioPreset by settingsStore.studioThemePreset.collectAsState()
	val prefs by subsonicManager.prefs.collectAsState()
	val activeDescriptor = remember(studioPreset) {
		MeloStudioPresets.all.firstOrNull { it.id == studioPreset }
	}
	val isDynamicPreset = studioPreset == MeloStudioPresets.DYNAMIC

	ModalBottomSheet(
		onDismissRequest = onDismissRequest,
		sheetState = sheetState,
		containerColor = MaterialTheme.colorScheme.background,
		contentColor = MaterialTheme.colorScheme.onSurface,
		dragHandle = null,
	) {
		Column(modifier = Modifier.fillMaxWidth()) {
			IosCleanTopBar(
				title = "主题与外观",
				onBack = onDismissRequest,
			)

			Column(
				modifier = Modifier
					.fillMaxWidth()
					.verticalScroll(rememberScrollState())
					.padding(horizontal = 16.dp)
					.padding(top = 6.dp, bottom = maxOf(hostNavBottom, 16.dp) + 20.dp),
				verticalArrangement = Arrangement.spacedBy(18.dp),
			) {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					IosSectionLabel(title = "深色主题")
					IosThemePresetGrid(
						presets = MeloStudioPresets.darkPresets,
						selectedPresetId = studioPreset,
						onSelect = { settingsStore.setStudioThemePreset(it) },
					)
				}

				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					IosSectionLabel(title = "浅色主题")
					IosThemePresetGrid(
						presets = MeloStudioPresets.lightPresets,
						selectedPresetId = studioPreset,
						onSelect = { settingsStore.setStudioThemePreset(it) },
					)
				}

				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					IosSectionLabel(title = "动态自适应")
					IosThemePresetGrid(
						presets = MeloStudioPresets.dynamicPresets,
						selectedPresetId = studioPreset,
						onSelect = { settingsStore.setStudioThemePreset(it) },
					)
				}

				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					IosSectionLabel(title = "深浅色模式")
					IosGroupedCard {
						Column(
							modifier = Modifier
								.fillMaxWidth()
								.padding(10.dp),
							verticalArrangement = Arrangement.spacedBy(8.dp),
						) {
							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.spacedBy(6.dp),
							) {
								listOf(
									IosThemeMode.SYSTEM to "跟随系统",
									IosThemeMode.DARK to "深色",
									IosThemeMode.LIGHT to "浅色",
								).forEach { (mode, label) ->
									val isCurrent = isDynamicPreset && prefs.themeMode == mode
									Surface(
										onClick = {
											if (!isDynamicPreset) {
												settingsStore.setStudioThemePreset(MeloStudioPresets.DYNAMIC)
											}
											subsonicManager.updatePrefs { it.copy(themeMode = mode) }
										},
										shape = RoundedCornerShape(10.dp),
										color = if (isCurrent) {
											MaterialTheme.colorScheme.primary
										} else {
											MaterialTheme.colorScheme.surfaceContainerHigh
										},
										modifier = Modifier.weight(1f),
									) {
										Box(
											modifier = Modifier.padding(vertical = 8.dp),
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
							if (!isDynamicPreset && activeDescriptor != null) {
								Text(
									text = "当前预设「${activeDescriptor.title}」固定为${if (activeDescriptor.isDark) "深色" else "浅色"}；选择模式将切换至动态壁纸色",
									style = MaterialTheme.typography.labelSmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									modifier = Modifier.padding(horizontal = 4.dp),
								)
							}
						}
					}
				}

				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					IosSectionLabel(title = "显示增强")
					IosGroupedCard {
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.clickable {
									subsonicManager.updatePrefs { it.copy(amoledBlack = !it.amoledBlack) }
								}
								.padding(horizontal = 16.dp, vertical = 12.dp),
							horizontalArrangement = Arrangement.SpaceBetween,
							verticalAlignment = Alignment.CenterVertically,
						) {
							Column(
								modifier = Modifier.weight(1f),
								verticalArrangement = Arrangement.spacedBy(2.dp),
							) {
								Text(
									text = "AMOLED 纯黑",
									style = MaterialTheme.typography.bodyMedium,
									fontWeight = FontWeight.Medium,
									color = MaterialTheme.colorScheme.onSurface,
								)
								Text(
									text = "深色模式下使用纯黑背景以降低 OLED 功耗",
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
								)
							}
							Switch(
								checked = prefs.amoledBlack,
								onCheckedChange = { checked ->
									subsonicManager.updatePrefs { it.copy(amoledBlack = checked) }
								},
								colors = SwitchDefaults.colors(
									checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
									checkedTrackColor = MaterialTheme.colorScheme.primary,
								),
							)
						}
						IosHairlineDivider(startIndent = 16.dp)
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.clickable {
									subsonicManager.updatePrefs {
										it.copy(hideBottomBarOnScroll = !it.hideBottomBarOnScroll)
									}
								}
								.padding(horizontal = 16.dp, vertical = 12.dp),
							horizontalArrangement = Arrangement.SpaceBetween,
							verticalAlignment = Alignment.CenterVertically,
						) {
							Column(
								modifier = Modifier.weight(1f),
								verticalArrangement = Arrangement.spacedBy(2.dp),
							) {
								Text(
									text = "下滑隐藏底栏",
									style = MaterialTheme.typography.bodyMedium,
									fontWeight = FontWeight.Medium,
									color = MaterialTheme.colorScheme.onSurface,
								)
								Text(
									text = "下滑列表时自动收起播放舱与底栏，上滑恢复",
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
								)
							}
							Switch(
								checked = prefs.hideBottomBarOnScroll,
								onCheckedChange = { checked ->
									subsonicManager.updatePrefs { it.copy(hideBottomBarOnScroll = checked) }
								},
								colors = SwitchDefaults.colors(
									checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
									checkedTrackColor = MaterialTheme.colorScheme.primary,
								),
							)
						}
						IosHairlineDivider(startIndent = 16.dp)
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.clickable {
									subsonicManager.updatePrefs {
										it.copy(hidePlayerIfIdle = !it.hidePlayerIfIdle)
									}
								}
								.padding(horizontal = 16.dp, vertical = 12.dp),
							horizontalArrangement = Arrangement.SpaceBetween,
							verticalAlignment = Alignment.CenterVertically,
						) {
							Column(
								modifier = Modifier.weight(1f),
								verticalArrangement = Arrangement.spacedBy(2.dp),
							) {
								Text(
									text = "无播放时隐藏播放栏",
									style = MaterialTheme.typography.bodyMedium,
									fontWeight = FontWeight.Medium,
									color = MaterialTheme.colorScheme.onSurface,
								)
								Text(
									text = "未播放或暂停时自动隐藏底部播放栏",
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
								)
							}
							Switch(
								checked = prefs.hidePlayerIfIdle,
								onCheckedChange = { checked ->
									subsonicManager.updatePrefs { it.copy(hidePlayerIfIdle = checked) }
								},
								colors = SwitchDefaults.colors(
									checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
									checkedTrackColor = MaterialTheme.colorScheme.primary,
								),
							)
						}
					}
				}
			}
		}
	}
}

@Composable
private fun IosThemePresetGrid(
	presets: List<MeloStudioPresets.Descriptor>,
	selectedPresetId: String,
	onSelect: (String) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
		presets.chunked(2).forEach { rowPresets ->
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(10.dp),
			) {
				rowPresets.forEach { preset ->
					val selected = selectedPresetId == preset.id
					Surface(
						onClick = { onSelect(preset.id) },
						shape = RoundedCornerShape(14.dp),
						color = MaterialTheme.colorScheme.surfaceContainer,
						border = BorderStroke(
							width = if (selected) 1.5.dp else IosHairlineWidth,
							color = if (selected) {
								MaterialTheme.colorScheme.primary
							} else {
								MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
							},
						),
						modifier = Modifier.weight(1f),
					) {
						Column(
							modifier = Modifier.padding(12.dp),
							verticalArrangement = Arrangement.spacedBy(8.dp),
						) {
							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.SpaceBetween,
								verticalAlignment = Alignment.CenterVertically,
							) {
								Row(
									horizontalArrangement = Arrangement.spacedBy(4.dp),
									verticalAlignment = Alignment.CenterVertically,
								) {
									Box(
										modifier = Modifier
											.size(14.dp)
											.clip(CircleShape)
											.background(preset.previewBackground)
											.border(
												IosHairlineWidth,
												MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
												CircleShape,
											),
									)
									Box(
										modifier = Modifier
											.size(14.dp)
											.clip(CircleShape)
											.background(preset.previewContainer)
											.border(
												IosHairlineWidth,
												MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
												CircleShape,
											),
									)
									Box(
										modifier = Modifier
											.size(14.dp)
											.clip(CircleShape)
											.background(preset.previewAccent),
									)
								}
								if (selected) {
									Icon(
										imageVector = Icons.Rounded.Check,
										contentDescription = "已选中",
										tint = MaterialTheme.colorScheme.primary,
										modifier = Modifier.size(16.dp),
									)
								}
							}
							Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
								Text(
									text = preset.title,
									style = MaterialTheme.typography.bodyMedium,
									fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
									color = MaterialTheme.colorScheme.onSurface,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
								)
								Text(
									text = preset.subtitle,
									style = MaterialTheme.typography.labelSmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
								)
							}
						}
					}
				}
				if (rowPresets.size == 1) {
					Spacer(modifier = Modifier.weight(1f))
				}
			}
		}
	}
}

@Composable
internal fun IosSettingsStorageSheet(
	tempCacheBytes: Long,
	offlineTrackCount: Int,
	offlineTotalBytes: Long,
	cacheClearHint: String?,
	onRequestClearCache: () -> Unit,
	onOpenPhoneDownloads: (() -> Unit)?,
	hostNavBottom: Dp,
	presented: Boolean,
	onDismissRequest: () -> Unit,
) {
	if (!presented) return

	val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

	ModalBottomSheet(
		onDismissRequest = onDismissRequest,
		sheetState = sheetState,
		containerColor = MaterialTheme.colorScheme.background,
		contentColor = MaterialTheme.colorScheme.onSurface,
		dragHandle = null,
	) {
		Column(modifier = Modifier.fillMaxWidth()) {
			IosCleanTopBar(
				title = "存储与缓存",
				onBack = onDismissRequest,
			)

			Column(
				modifier = Modifier
					.fillMaxWidth()
					.verticalScroll(rememberScrollState())
					.padding(horizontal = 16.dp)
					.padding(top = 6.dp, bottom = maxOf(hostNavBottom, 16.dp) + 20.dp),
				verticalArrangement = Arrangement.spacedBy(18.dp),
			) {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					IosSectionLabel(title = "临时缓存")
					IosGroupedCard {
						IosSettingsNavRow(
							title = "临时缓存占用",
							value = cacheClearHint ?: formatBytesHuman(tempCacheBytes),
							onClick = onRequestClearCache,
						)
						IosHairlineDivider(startIndent = 16.dp)
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.clickable(onClick = onRequestClearCache)
								.padding(horizontal = 16.dp, vertical = 12.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(12.dp),
						) {
							Icon(
								imageVector = Icons.Rounded.Delete,
								contentDescription = null,
								tint = MaterialTheme.colorScheme.error,
								modifier = Modifier.size(18.dp),
							)
							Column(
								modifier = Modifier.weight(1f),
								verticalArrangement = Arrangement.spacedBy(2.dp),
							) {
								Text(
									text = "清理临时缓存",
									style = MaterialTheme.typography.bodyMedium,
									fontWeight = FontWeight.Medium,
									color = MaterialTheme.colorScheme.error,
								)
								Text(
									text = "清理封面图与网络缓冲，不影响已离线保存的歌曲",
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
								)
							}
						}
					}
				}

				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					IosSectionLabel(title = "离线曲库")
					IosGroupedCard {
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.padding(horizontal = 16.dp, vertical = 13.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(12.dp),
						) {
							Icon(
								imageVector = Icons.Rounded.OfflinePin,
								contentDescription = null,
								tint = MaterialTheme.colorScheme.primary,
								modifier = Modifier.size(18.dp),
							)
							Text(
								text = "已离线曲目",
								style = MaterialTheme.typography.bodyMedium,
								fontWeight = FontWeight.Medium,
								color = MaterialTheme.colorScheme.onSurface,
								modifier = Modifier.weight(1f),
							)
							Text(
								text = "$offlineTrackCount 首 · ${formatBytesHuman(offlineTotalBytes)}",
								style = MaterialTheme.typography.bodySmall.tabularNums(),
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
						if (onOpenPhoneDownloads != null) {
							IosHairlineDivider(startIndent = 16.dp)
							Row(
								modifier = Modifier
									.fillMaxWidth()
									.clickable {
										onDismissRequest()
										onOpenPhoneDownloads()
									}
									.padding(horizontal = 16.dp, vertical = 13.dp),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(12.dp),
							) {
								Icon(
									imageVector = Icons.Rounded.Folder,
									contentDescription = null,
									tint = MaterialTheme.colorScheme.onSurfaceVariant,
									modifier = Modifier.size(18.dp),
								)
								Column(
									modifier = Modifier.weight(1f),
									verticalArrangement = Arrangement.spacedBy(2.dp),
								) {
									Text(
										text = "管理手机离线曲库",
										style = MaterialTheme.typography.bodyMedium,
										fontWeight = FontWeight.Medium,
										color = MaterialTheme.colorScheme.onSurface,
									)
									Text(
										text = "查看下载任务、离线文件大小或批量清理",
										style = MaterialTheme.typography.bodySmall,
										color = MaterialTheme.colorScheme.onSurfaceVariant,
									)
								}
								Icon(
									imageVector = Icons.Rounded.ChevronRight,
									contentDescription = null,
									tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
									modifier = Modifier.size(18.dp),
								)
							}
						}
					}
				}
			}
		}
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

internal fun formatIosDbLabel(db: Float): String {
	val tenths = (db * 10f).roundToInt()
	val sign = when {
		tenths > 0 -> "+"
		tenths < 0 -> "-"
		else -> ""
	}
	val absTenths = abs(tenths)
	return "$sign${absTenths / 10}.${absTenths % 10} dB"
}

internal fun parseIosLyricsHexColorOrNull(raw: String): Color? {
	val clean = raw.trim().removePrefix("#")
	if (clean.length != 6 && clean.length != 8) return null
	val parsed = clean.toLongOrNull(16) ?: return null
	val argb = if (clean.length == 6) {
		0xFF000000L or parsed
	} else {
		parsed
	}
	return Color(argb)
}

internal fun resetIosLyricsPreferences(
	audioPlayer: IosAudioPlayer,
	subsonicManager: IosSubsonicManager,
) {
	audioPlayer.setLyricsOffset(0)
	subsonicManager.updatePrefs {
		it.copy(
			lyricsOffsetMs = 0,
			lyricsFontSizeSp = 22f,
			lyricsLineSpacingDp = 16f,
			lyricsActiveColorHex = "",
			lyricsInactiveAlpha = 0.48f,
			lyricsAlign = "center",
		)
	}
}

@Composable
internal fun IosSettingsLyricsSectionCard(
	offsetMs: Int,
	prefs: IosPlaybackPrefs,
	onOffsetChange: (Int) -> Unit,
	onUpdatePrefs: ((IosPlaybackPrefs) -> IosPlaybackPrefs) -> Unit,
	onResetAll: () -> Unit,
) {
	IosGroupedCard {
		IosSettingsLyricsOffsetControl(
			title = "时间偏移",
			offsetMs = offsetMs,
			onOffsetChange = onOffsetChange,
			modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
		)
		IosHairlineDivider(startIndent = 16.dp)
		IosSettingsLyricsStyleControl(
			prefs = prefs,
			offsetMs = offsetMs,
			onUpdatePrefs = onUpdatePrefs,
			onResetAll = onResetAll,
			modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
		)
	}
}

@Composable
internal fun IosSettingsLyricsStyleControl(
	prefs: IosPlaybackPrefs,
	offsetMs: Int,
	onUpdatePrefs: ((IosPlaybackPrefs) -> IosPlaybackPrefs) -> Unit,
	onResetAll: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val clampedFontSp = prefs.lyricsFontSizeSp.coerceIn(16f, 34f)
	val clampedLineSpacingDp = prefs.lyricsLineSpacingDp.coerceIn(4f, 36f)
	val inactiveFontSp = (clampedFontSp - 4f).coerceAtLeast(13f)
	val clampedInactiveAlpha = prefs.lyricsInactiveAlpha.coerceIn(0.2f, 0.85f)
	val currentAlign = if (prefs.lyricsAlign == "start") "start" else "center"
	val currentHex = normalizeIosLyricsHexOrEmpty(prefs.lyricsActiveColorHex)
	val activePreviewColor = parseIosLyricsHexColorOrNull(currentHex) ?: MaterialTheme.colorScheme.onSurface
	val previewAlign = if (currentAlign == "start") TextAlign.Start else TextAlign.Center

	var hexInput by remember(currentHex) { mutableStateOf(currentHex) }
	var hexError by remember { mutableStateOf<String?>(null) }

	val isDefault = offsetMs == 0 &&
		abs(clampedFontSp - 22f) < 0.05f &&
		abs(clampedLineSpacingDp - 16f) < 0.05f &&
		currentHex.isEmpty() &&
		abs(clampedInactiveAlpha - 0.48f) < 0.005f &&
		currentAlign == "center"

	Column(
		modifier = modifier.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		// 实时样式预览
		Surface(
			shape = RoundedCornerShape(12.dp),
			color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
			border = BorderStroke(IosHairlineWidth, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
			modifier = Modifier.fillMaxWidth(),
		) {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 12.dp, vertical = 10.dp),
				verticalArrangement = Arrangement.spacedBy(4.dp),
			) {
				Text(
					text = "正在高亮显示当前歌词",
					fontSize = clampedFontSp.sp,
					lineHeight = (clampedFontSp * 1.36f).sp,
					fontWeight = FontWeight.Bold,
					color = activePreviewColor,
					textAlign = previewAlign,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.fillMaxWidth(),
				)
				Text(
					text = "非高亮歌词预览效果",
					fontSize = inactiveFontSp.sp,
					lineHeight = (inactiveFontSp * 1.36f).sp,
					fontWeight = FontWeight.Medium,
					color = MaterialTheme.colorScheme.onSurface.copy(alpha = clampedInactiveAlpha),
					textAlign = previewAlign,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.fillMaxWidth(),
				)
			}
		}

		// 字号 16..34 sp
		Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = "歌词字号",
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = FontWeight.Medium,
				)
				Text(
					text = "${clampedFontSp.roundToInt()} sp",
					style = MaterialTheme.typography.labelMedium.tabularNums(),
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.primary,
				)
			}
			Slider(
				value = clampedFontSp,
				onValueChange = { raw ->
					val stepped = raw.roundToInt().toFloat().coerceIn(16f, 34f)
					if (abs(stepped - clampedFontSp) >= 0.5f) {
						onUpdatePrefs { it.copy(lyricsFontSizeSp = stepped) }
					}
				},
				valueRange = 16f..34f,
				steps = 17,
				modifier = Modifier.fillMaxWidth(),
				colors = SliderDefaults.colors(
					thumbColor = MaterialTheme.colorScheme.primary,
					activeTrackColor = MaterialTheme.colorScheme.primary,
					activeTickColor = Color.Transparent,
					inactiveTickColor = Color.Transparent,
				),
			)
		}

		// 歌词行间距 (4dp - 36dp)
		Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = "歌词行间距",
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = FontWeight.Medium,
				)
				Text(
					text = "${clampedLineSpacingDp.roundToInt()} dp",
					style = MaterialTheme.typography.labelMedium.tabularNums(),
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.primary,
				)
			}
			Slider(
				value = clampedLineSpacingDp,
				onValueChange = { raw ->
					val stepped = raw.roundToInt().toFloat().coerceIn(4f, 36f)
					if (abs(stepped - clampedLineSpacingDp) >= 0.5f) {
						onUpdatePrefs { it.copy(lyricsLineSpacingDp = stepped) }
					}
				},
				valueRange = 4f..36f,
				steps = 31,
				modifier = Modifier.fillMaxWidth(),
				colors = SliderDefaults.colors(
					thumbColor = MaterialTheme.colorScheme.primary,
					activeTrackColor = MaterialTheme.colorScheme.primary,
					activeTickColor = Color.Transparent,
					inactiveTickColor = Color.Transparent,
				),
			)
		}

		// 高亮颜色
		Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = "高亮颜色",
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = FontWeight.Medium,
				)
				Text(
					text = currentHex.ifBlank { "跟随主题" },
					style = MaterialTheme.typography.labelMedium.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}

			FlowRow(
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				IOS_LYRICS_COLOR_PRESETS.forEach { (hex, label) ->
					val selected = currentHex.equals(hex, ignoreCase = true)
					val swatchColor = parseIosLyricsHexColorOrNull(hex)
					Surface(
						onClick = {
							hexError = null
							hexInput = hex
							onUpdatePrefs { it.copy(lyricsActiveColorHex = hex) }
						},
						shape = CircleShape,
						color = if (selected) {
							MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
						} else {
							MaterialTheme.colorScheme.surfaceContainerHigh
						},
						border = BorderStroke(
							width = if (selected) 1.25.dp else IosHairlineWidth,
							color = if (selected) {
								MaterialTheme.colorScheme.primary
							} else {
								MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
							},
						),
					) {
						Row(
							modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(6.dp),
						) {
							if (swatchColor != null) {
								Box(
									modifier = Modifier
										.size(13.dp)
										.clip(CircleShape)
										.background(swatchColor)
										.border(
											width = IosHairlineWidth,
											color = if (swatchColor.luminance() > 0.85f) {
												MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
											} else {
												Color.Transparent
											},
											shape = CircleShape,
										),
									contentAlignment = Alignment.Center,
								) {
									if (selected) {
										Icon(
											imageVector = Icons.Rounded.Check,
											contentDescription = null,
											tint = if (swatchColor.luminance() > 0.6f) Color.Black else Color.White,
											modifier = Modifier.size(9.dp),
										)
									}
								}
							} else if (selected) {
								Icon(
									imageVector = Icons.Rounded.Check,
									contentDescription = null,
									tint = MaterialTheme.colorScheme.primary,
									modifier = Modifier.size(13.dp),
								)
							}
							Text(
								text = label,
								style = MaterialTheme.typography.labelSmall,
								fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
								color = if (selected) {
									MaterialTheme.colorScheme.primary
								} else {
									MaterialTheme.colorScheme.onSurface
								},
							)
						}
					}
				}
			}

			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				OutlinedTextField(
					value = hexInput,
					onValueChange = {
						hexInput = it
						hexError = null
					},
					singleLine = true,
					shape = RoundedCornerShape(10.dp),
					placeholder = { Text("自定义 Hex，如 #60A5FA", maxLines = 1, overflow = TextOverflow.Ellipsis) },
					modifier = Modifier.weight(1f),
				)
				FilledTonalButton(
					onClick = {
						val trimmed = hexInput.trim()
						if (trimmed.isEmpty()) {
							hexError = null
							onUpdatePrefs { it.copy(lyricsActiveColorHex = "") }
						} else {
							val normalized = normalizeIosLyricsHexOrEmpty(trimmed)
							if (normalized.isEmpty()) {
								hexError = "请输入 6 位或 8 位十六进制颜色，如 #FFFFFF"
							} else {
								hexError = null
								hexInput = normalized
								onUpdatePrefs { it.copy(lyricsActiveColorHex = normalized) }
							}
						}
					},
					shape = RoundedCornerShape(10.dp),
				) {
					Text("应用")
				}
			}
			if (!hexError.isNullOrBlank()) {
				Text(
					text = hexError.orEmpty(),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.error,
				)
			}
		}

		// 非高亮透明度 0.2..0.85
		Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = "非高亮透明度",
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = FontWeight.Medium,
				)
				Text(
					text = "${(clampedInactiveAlpha * 100f).roundToInt()}%",
					style = MaterialTheme.typography.labelMedium.tabularNums(),
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.primary,
				)
			}
			Slider(
				value = clampedInactiveAlpha,
				onValueChange = { raw ->
					val rounded = ((raw * 100f).roundToInt() / 100f).coerceIn(0.2f, 0.85f)
					if (abs(rounded - clampedInactiveAlpha) >= 0.005f) {
						onUpdatePrefs { it.copy(lyricsInactiveAlpha = rounded) }
					}
				},
				valueRange = 0.2f..0.85f,
				modifier = Modifier.fillMaxWidth(),
				colors = SliderDefaults.colors(
					thumbColor = MaterialTheme.colorScheme.primary,
					activeTrackColor = MaterialTheme.colorScheme.primary,
				),
			)
		}

		// 对齐方式
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = "对齐方式",
				style = MaterialTheme.typography.bodyMedium,
				fontWeight = FontWeight.Medium,
			)
			Row(
				modifier = Modifier.width(160.dp),
				horizontalArrangement = Arrangement.spacedBy(6.dp),
			) {
				IosSettingsChoicePill(
					label = "居中",
					selected = currentAlign == "center",
					onClick = { onUpdatePrefs { it.copy(lyricsAlign = "center") } },
					modifier = Modifier.weight(1f),
				)
				IosSettingsChoicePill(
					label = "左对齐",
					selected = currentAlign == "start",
					onClick = { onUpdatePrefs { it.copy(lyricsAlign = "start") } },
					modifier = Modifier.weight(1f),
				)
			}
		}

		// 恢复默认
		OutlinedButton(
			onClick = {
				hexError = null
				hexInput = ""
				onResetAll()
			},
			enabled = !isDefault,
			shape = RoundedCornerShape(10.dp),
			border = BorderStroke(
				width = IosHairlineWidth,
				color = MaterialTheme.colorScheme.outline.copy(alpha = if (isDefault) 0.25f else 0.45f),
			),
			modifier = Modifier.fillMaxWidth(),
		) {
			Text("恢复默认")
		}
	}
}

internal fun IosSettingsResolveAppVersion(): String {
	val raw = NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String
	return raw?.trim()
		?.removePrefix("v")
		?.removePrefix("V")
		?.takeIf { it.isNotEmpty() }
		?: "1.0.0"
}

internal fun normalizeIosVersionLabel(raw: String, fallback: String = ""): String {
	val clean = raw.trim().removePrefix("v").removePrefix("V")
	return when {
		clean.isNotEmpty() -> "v$clean"
		fallback.isNotEmpty() -> "v${fallback.trim().removePrefix("v").removePrefix("V")}"
		else -> ""
	}
}

internal fun iosOpenExternalUrl(rawUrl: String): Boolean {
	val trimmed = rawUrl.trim()
	if (trimmed.isEmpty()) return false
	val nsUrl = NSURL.URLWithString(trimmed) ?: return false
	return runCatching {
		UIApplication.sharedApplication.openURL(
			nsUrl,
			options = emptyMap<Any?, Any?>(),
			completionHandler = null,
		)
	}.isSuccess
}

private fun formatIosServerTokenBadge(settings: MeloSystemUpdateSettings?): Pair<String, Boolean> {
	if (settings == null) return "未获取到状态" to false
	return when {
		settings.githubTokenSaved || settings.githubTokenSource == "settings" ->
			"已配置（已保存）" to true
		settings.githubTokenConfigured && settings.githubTokenSource == "env" ->
			"已配置（环境变量）" to true
		settings.githubTokenConfigured ->
			"已配置" to true
		else ->
			"未配置（60 次/小时）" to false
	}
}

@Composable
internal fun IosAppUpdateDialog(
	currentVersion: String,
	settingsController: MeloSettingsController,
	initialRelease: MeloGitHubRelease? = null,
	initialTokenSettings: MeloSystemUpdateSettings? = null,
	initialViaServer: Boolean = false,
	onReleaseLoaded: (MeloGitHubRelease?) -> Unit = {},
	onTokenSettingsLoaded: (MeloSystemUpdateSettings?) -> Unit = {},
	onViaServerChanged: (Boolean) -> Unit = {},
	onShowNotice: (String) -> Unit = {},
	onDismiss: () -> Unit,
) {
	val scope = rememberCoroutineScope()
	val clipboard = LocalClipboardManager.current
	val settingsState by settingsController.state.collectAsState()
	val meloConnected = settingsState.serverConfig.isAuthenticated

	var release by remember(initialRelease) { mutableStateOf(initialRelease) }
	var checkedOnce by remember { mutableStateOf(initialRelease != null) }
	var loading by remember { mutableStateOf(initialRelease == null) }
	var errorMessage by remember { mutableStateOf<String?>(null) }
	var launchFeedback by remember { mutableStateOf<String?>(null) }
	var tokenSettings by remember(initialTokenSettings) { mutableStateOf(initialTokenSettings) }
	var checkedViaServer by remember(initialViaServer) { mutableStateOf(initialViaServer) }
	var tokenInput by rememberSaveable { mutableStateOf("") }
	var tokenVisible by rememberSaveable { mutableStateOf(false) }
	var tokenSubmitting by remember { mutableStateOf(false) }
	var tokenFeedback by remember { mutableStateOf<String?>(null) }
	var tokenFeedbackIsError by remember { mutableStateOf(false) }

	suspend fun executeUpdateCheck(forceCheck: Boolean) {
		loading = true
		errorMessage = null
		launchFeedback = null
		try {
			val resolved = settingsController.resolveIosAppUpdate(force = forceCheck)
			release = resolved.release
			checkedOnce = true
			checkedViaServer = resolved.viaServer
			onViaServerChanged(resolved.viaServer)
			if (resolved.viaServer && resolved.tokenSettings != null) {
				tokenSettings = resolved.tokenSettings
				onTokenSettingsLoaded(resolved.tokenSettings)
			}
			onReleaseLoaded(resolved.release)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			errorMessage = e.message?.takeIf { it.isNotBlank() } ?: "检查更新失败，请稍后重试"
			checkedOnce = true
			if (meloConnected) {
				val serverError = e is MeloApiException && e.statusCode == 502
				checkedViaServer = serverError
				onViaServerChanged(serverError)
				try {
					val refreshed = settingsController.fetchSystemUpdateSettings()
					tokenSettings = refreshed
					onTokenSettingsLoaded(refreshed)
				} catch (ce: CancellationException) {
					throw ce
				} catch (_: Throwable) {
					// 忽略旧版服务端或暂不可达时的刷新失败
				}
			}
		} finally {
			loading = false
		}
	}

	val runUpdateCheck: (Boolean) -> Unit = { forceCheck ->
		if (!loading) {
			scope.launch {
				executeUpdateCheck(forceCheck)
			}
		}
	}

	LaunchedEffect(Unit) {
		if (!checkedOnce) {
			executeUpdateCheck(forceCheck = false)
		}
	}

	LaunchedEffect(meloConnected) {
		if (meloConnected) {
			if (tokenSettings == null) {
				try {
					val fetchedSettings = settingsController.fetchSystemUpdateSettings()
					tokenSettings = fetchedSettings
					onTokenSettingsLoaded(fetchedSettings)
				} catch (e: CancellationException) {
					throw e
				} catch (_: Throwable) {
					// 旧版服务端不支持该接口时静默忽略
				}
			}
		} else {
			tokenSettings = null
			checkedViaServer = false
			tokenFeedback = null
			tokenFeedbackIsError = false
			onTokenSettingsLoaded(null)
			onViaServerChanged(false)
		}
	}

	val activeRelease = release
	val hasUpdate = activeRelease?.hasIosUpdateFor(currentVersion) == true
	val ipaAsset = remember(activeRelease) { activeRelease?.findIosIpaAsset() }
	val displayCurrentVersion = normalizeIosVersionLabel(currentVersion, fallback = "1.0.0")
	val displayReleaseTag = activeRelease?.tagName?.let { normalizeIosVersionLabel(it) }.orEmpty()
	val releasePageUrl = activeRelease?.htmlUrl?.takeIf { it.isNotBlank() }
		?: "https://github.com/Agonie0v0/melokeep/releases"

	AlertDialog(
		onDismissRequest = onDismiss,
		containerColor = MaterialTheme.colorScheme.surfaceContainer,
		title = {
			Text(
				text = when {
					loading && activeRelease == null -> "正在检查更新"
					errorMessage != null -> "检查更新失败"
					hasUpdate -> "发现新版本 $displayReleaseTag"
					activeRelease != null -> "已是最新版本"
					checkedOnce -> "尚无带 IPA 的已发布版本"
					else -> "应用更新"
				},
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.SemiBold,
			)
		},
		text = {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(12.dp),
			) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						text = "当前版本 $displayCurrentVersion",
						style = MaterialTheme.typography.bodySmall.tabularNums(),
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					if (!loading && errorMessage == null && (activeRelease != null || checkedOnce)) {
						val badgeText = when {
							hasUpdate -> "最新 $displayReleaseTag"
							activeRelease != null -> "已是最新 $displayReleaseTag"
							else -> "尚无 IPA 版本"
						}
						Surface(
							shape = CircleShape,
							color = if (hasUpdate) {
								MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
							} else {
								MaterialTheme.colorScheme.surfaceContainerHigh
							},
						) {
							Text(
								text = badgeText,
								style = MaterialTheme.typography.labelSmall.tabularNums(),
								fontWeight = FontWeight.SemiBold,
								color = if (hasUpdate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
								modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
							)
						}
					}
				}

				when {
					loading -> {
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.padding(vertical = 12.dp),
							horizontalArrangement = Arrangement.Center,
							verticalAlignment = Alignment.CenterVertically,
						) {
							CircularProgressIndicator(
								modifier = Modifier.size(18.dp),
								strokeWidth = 2.dp,
							)
							Spacer(Modifier.width(10.dp))
							Text(
								text = if (meloConnected) "正在检查版本更新…" else "正在拉取 GitHub Release…",
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					}

					errorMessage != null -> {
						Text(
							text = errorMessage.orEmpty(),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.error,
						)
					}

					activeRelease != null -> {
						val notes = activeRelease.body.trim().ifBlank { "暂无更新说明" }
						Surface(
							shape = RoundedCornerShape(12.dp),
							color = MaterialTheme.colorScheme.surfaceContainerHigh,
							border = BorderStroke(IosHairlineWidth, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
							modifier = Modifier.fillMaxWidth(),
						) {
							Column(
								modifier = Modifier
									.fillMaxWidth()
									.heightIn(max = 200.dp)
									.verticalScroll(rememberScrollState())
									.padding(12.dp),
								verticalArrangement = Arrangement.spacedBy(6.dp),
							) {
								if (activeRelease.name.isNotBlank() && activeRelease.name != activeRelease.tagName) {
									Text(
										text = activeRelease.name,
										style = MaterialTheme.typography.labelLarge,
										fontWeight = FontWeight.SemiBold,
										color = MaterialTheme.colorScheme.onSurface,
									)
								}
								Text(
									text = notes,
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
								)
							}
						}

						if (hasUpdate && ipaAsset != null) {
							val encodedIpa = ipaAsset.downloadUrl.encodeURLParameter()
							val sidestoreUrl = "sidestore://install?url=$encodedIpa"
							val altstoreUrl = "altstore://install?url=$encodedIpa"

							Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
								Button(
									onClick = {
										if (!iosOpenExternalUrl(sidestoreUrl)) {
											launchFeedback = "未能拉起 SideStore，请确认已安装 SideStore 或尝试直接下载 IPA"
										}
									},
									shape = RoundedCornerShape(10.dp),
									modifier = Modifier.fillMaxWidth(),
								) {
									Text("用 SideStore 安装")
								}

								FilledTonalButton(
									onClick = {
										if (!iosOpenExternalUrl(altstoreUrl)) {
											launchFeedback = "未能拉起 AltStore，请确认已安装 AltStore 或尝试直接下载 IPA"
										}
									},
									shape = RoundedCornerShape(10.dp),
									modifier = Modifier.fillMaxWidth(),
								) {
									Text("用 AltStore 安装")
								}

								Row(
									modifier = Modifier.fillMaxWidth(),
									horizontalArrangement = Arrangement.spacedBy(8.dp),
								) {
									OutlinedButton(
										onClick = {
											if (!iosOpenExternalUrl(ipaAsset.downloadUrl)) {
												launchFeedback = "无法打开 IPA 下载地址"
											}
										},
										shape = RoundedCornerShape(10.dp),
										contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
										modifier = Modifier.weight(1f),
									) {
										Text("直接下载 IPA", maxLines = 1, overflow = TextOverflow.Ellipsis)
									}
									OutlinedButton(
										onClick = {
											clipboard.setText(AnnotatedString(ipaAsset.downloadUrl))
											launchFeedback = "已复制 IPA 下载链接"
											onShowNotice("已复制 IPA 下载链接")
										},
										shape = RoundedCornerShape(10.dp),
										contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
										modifier = Modifier.weight(1f),
									) {
										Text("复制链接", maxLines = 1, overflow = TextOverflow.Ellipsis)
									}
								}
							}
						} else {
							Text(
								text = "当前已是最新 iOS 客户端版本，无需重新下载安装",
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}

						OutlinedButton(
							onClick = {
								if (!iosOpenExternalUrl(releasePageUrl)) {
									launchFeedback = "无法打开 GitHub Release 页面"
								}
							},
							shape = RoundedCornerShape(10.dp),
							modifier = Modifier.fillMaxWidth(),
						) {
							Text("前往 GitHub Release 页面")
						}

						launchFeedback?.let { fb ->
							Text(
								text = fb,
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.primary,
							)
						}

						if (hasUpdate && ipaAsset != null) {
							Text(
								text = "说明：受 iOS 沙盒限制无法静默覆盖安装，请通过 SideStore / AltStore 安装，或下载 IPA 手动重签安装。",
								style = MaterialTheme.typography.labelSmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
							)
						}
					}

					checkedOnce -> {
						Text(
							text = "尚无带 IPA 的已发布版本，当前已是最新 iOS 客户端版本，无需重新下载安装。",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)

						OutlinedButton(
							onClick = {
								if (!iosOpenExternalUrl(releasePageUrl)) {
									launchFeedback = "无法打开 GitHub Release 页面"
								}
							},
							shape = RoundedCornerShape(10.dp),
							modifier = Modifier.fillMaxWidth(),
						) {
							Text("前往 GitHub Release 页面")
						}

						launchFeedback?.let { fb ->
							Text(
								text = fb,
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.primary,
							)
						}
					}
				}

				if (meloConnected) {
					val (tokenBadgeText, tokenConfigured) = formatIosServerTokenBadge(tokenSettings)
					Surface(
						shape = RoundedCornerShape(12.dp),
						color = MaterialTheme.colorScheme.surfaceContainerHigh,
						border = BorderStroke(
							IosHairlineWidth,
							MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
						),
						modifier = Modifier.fillMaxWidth(),
					) {
						Column(
							modifier = Modifier
								.fillMaxWidth()
								.padding(12.dp),
							verticalArrangement = Arrangement.spacedBy(8.dp),
						) {
							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.SpaceBetween,
								verticalAlignment = Alignment.CenterVertically,
							) {
								Text(
									text = "服务端 GitHub Token",
									style = MaterialTheme.typography.labelLarge,
									fontWeight = FontWeight.SemiBold,
									color = MaterialTheme.colorScheme.onSurface,
								)
								Surface(
									shape = CircleShape,
									color = if (tokenConfigured) {
										MaterialTheme.colorScheme.meloPositive.copy(alpha = 0.14f)
									} else {
										MaterialTheme.colorScheme.surfaceContainer
									},
								) {
									Text(
										text = tokenBadgeText,
										style = MaterialTheme.typography.labelSmall,
										fontWeight = FontWeight.Medium,
										color = if (tokenConfigured) {
											MaterialTheme.colorScheme.meloPositive
										} else {
											MaterialTheme.colorScheme.onSurfaceVariant
										},
										modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
									)
								}
							}

							Text(
								text = buildString {
									append("配置在当前 MeloKeep 服务端，Web 端与已连接的手机端共用（不回传明文），可提升至 5,000 次/小时。")
									if (checkedViaServer) {
										append(" 本次已通过服务端检测。")
									}
								},
								style = MaterialTheme.typography.labelSmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)

							OutlinedTextField(
								value = tokenInput,
								onValueChange = { tokenInput = it },
								singleLine = true,
								enabled = !tokenSubmitting && !loading,
								label = { Text("GitHub Token") },
								placeholder = {
									Text(
										if (tokenSettings?.githubTokenSaved == true) {
											"已保存（输入新令牌覆盖）"
										} else {
											"ghp_... 或 github_pat_..."
										}
									)
								},
								visualTransformation = if (tokenVisible) {
									VisualTransformation.None
								} else {
									PasswordVisualTransformation()
								},
								trailingIcon = {
									IconButton(onClick = { tokenVisible = !tokenVisible }) {
										Icon(
											imageVector = if (tokenVisible) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
											contentDescription = null,
											modifier = Modifier.size(18.dp),
										)
									}
								},
								shape = RoundedCornerShape(10.dp),
								modifier = Modifier.fillMaxWidth(),
							)

							tokenFeedback?.let { fb ->
								Text(
									text = fb,
									style = MaterialTheme.typography.labelSmall,
									color = if (tokenFeedbackIsError) {
										MaterialTheme.colorScheme.error
									} else {
										MaterialTheme.colorScheme.meloPositive
									},
								)
							}

							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.spacedBy(8.dp),
								verticalAlignment = Alignment.CenterVertically,
							) {
								Button(
									onClick = {
										val trimmed = tokenInput.trim()
										if (trimmed.isEmpty() || tokenSubmitting || loading) return@Button
										tokenSubmitting = true
										tokenFeedback = null
										tokenFeedbackIsError = false
										scope.launch {
											var savedOk = false
											try {
												val updated = settingsController.saveSystemUpdateSettings(
													githubToken = trimmed,
													clearGitHubToken = false,
												)
												tokenSettings = updated
												onTokenSettingsLoaded(updated)
												tokenInput = ""
												tokenFeedback = "已保存到服务端并重新检测"
												tokenFeedbackIsError = false
												onShowNotice("已保存服务端 GitHub Token")
												savedOk = true
											} catch (e: CancellationException) {
												throw e
											} catch (e: Throwable) {
												tokenFeedback = e.message?.takeIf { it.isNotBlank() } ?: "保存失败"
												tokenFeedbackIsError = true
											} finally {
												tokenSubmitting = false
											}
											if (savedOk) {
												runUpdateCheck(true)
											}
										}
									},
									enabled = !tokenSubmitting && !loading && tokenInput.isNotBlank(),
									shape = RoundedCornerShape(10.dp),
									modifier = Modifier.weight(1f),
								) {
									if (tokenSubmitting) {
										CircularProgressIndicator(
											modifier = Modifier.size(14.dp),
											strokeWidth = 2.dp,
											color = MaterialTheme.colorScheme.onPrimary,
										)
										Spacer(Modifier.width(6.dp))
										Text("保存中…")
									} else {
										Text("保存并检测", maxLines = 1, overflow = TextOverflow.Ellipsis)
									}
								}

								if (tokenSettings?.githubTokenSaved == true) {
									OutlinedButton(
										onClick = {
											if (tokenSubmitting || loading) return@OutlinedButton
											tokenSubmitting = true
											tokenFeedback = null
											tokenFeedbackIsError = false
											scope.launch {
												var clearedOk = false
												try {
													val updated = settingsController.saveSystemUpdateSettings(
														clearGitHubToken = true,
													)
													tokenSettings = updated
													onTokenSettingsLoaded(updated)
													tokenFeedback = "已清空服务端保存的令牌"
													tokenFeedbackIsError = false
													onShowNotice("已清空服务端 GitHub Token")
													clearedOk = true
												} catch (e: CancellationException) {
													throw e
												} catch (e: Throwable) {
													tokenFeedback = e.message?.takeIf { it.isNotBlank() } ?: "清空失败"
													tokenFeedbackIsError = true
												} finally {
													tokenSubmitting = false
												}
												if (clearedOk) {
													runUpdateCheck(true)
												}
											}
										},
										enabled = !tokenSubmitting && !loading,
										shape = RoundedCornerShape(10.dp),
										border = BorderStroke(
											IosHairlineWidth,
											MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
										),
										colors = ButtonDefaults.outlinedButtonColors(
											contentColor = MaterialTheme.colorScheme.error,
										),
									) {
										Text("清空")
									}
								}
							}
						}
					}
				}
			}
		},
		confirmButton = {
			TextButton(onClick = onDismiss) {
				Text("关闭")
			}
		},
		dismissButton = {
			TextButton(
				onClick = { runUpdateCheck(true) },
				enabled = !loading && !tokenSubmitting,
			) {
				Text("重新检查")
			}
		},
	)
}
