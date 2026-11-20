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
import io.github.agonie0v0.melokeep.shared.MeloAppUpdateChecker
import io.github.agonie0v0.melokeep.shared.MeloGitHubRelease
import io.github.agonie0v0.melokeep.shared.MeloSemVer
import io.github.agonie0v0.melokeep.shared.MeloSettingsController
import io.github.agonie0v0.melokeep.shared.MeloSettingsUiState
import io.github.agonie0v0.melokeep.shared.findIosIpaAsset
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
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

@Composable
internal fun IosAppUpdateDialog(
	currentVersion: String,
	initialRelease: MeloGitHubRelease? = null,
	onReleaseLoaded: (MeloGitHubRelease) -> Unit = {},
	onShowNotice: (String) -> Unit = {},
	onDismiss: () -> Unit,
) {
	val scope = rememberCoroutineScope()
	val clipboard = LocalClipboardManager.current

	var release by remember(initialRelease) { mutableStateOf(initialRelease) }
	var loading by remember { mutableStateOf(initialRelease == null) }
	var errorMessage by remember { mutableStateOf<String?>(null) }
	var launchFeedback by remember { mutableStateOf<String?>(null) }

	val checkNow: () -> Unit = {
		if (!loading) {
			loading = true
			errorMessage = null
			launchFeedback = null
			scope.launch {
				try {
					val fetched = MeloAppUpdateChecker.fetchLatestRelease()
					release = fetched
					onReleaseLoaded(fetched)
				} catch (e: CancellationException) {
					throw e
				} catch (e: Throwable) {
					errorMessage = e.message?.takeIf { it.isNotBlank() } ?: "检查更新失败，请稍后重试"
				} finally {
					loading = false
				}
			}
		}
	}

	LaunchedEffect(Unit) {
		if (release == null) {
			try {
				val fetched = MeloAppUpdateChecker.fetchLatestRelease()
				release = fetched
				onReleaseLoaded(fetched)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				errorMessage = e.message?.takeIf { it.isNotBlank() } ?: "检查更新失败，请稍后重试"
			} finally {
				loading = false
			}
		}
	}

	val activeRelease = release
	val hasUpdate = activeRelease != null && MeloSemVer.isNewer(activeRelease.tagName, currentVersion)
	val ipaAsset = remember(activeRelease) { activeRelease?.findIosIpaAsset() }
	val displayCurrentVersion = normalizeIosVersionLabel(currentVersion, fallback = "1.0.0")
	val displayReleaseTag = activeRelease?.tagName?.let { normalizeIosVersionLabel(it) }.orEmpty()

	AlertDialog(
		onDismissRequest = onDismiss,
		containerColor = MaterialTheme.colorScheme.surfaceContainer,
		title = {
			Text(
				text = when {
					loading && activeRelease == null -> "正在检查更新"
					hasUpdate -> "发现新版本 $displayReleaseTag"
					activeRelease != null -> "版本检查"
					else -> "应用更新"
				},
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.SemiBold,
			)
		},
		text = {
			Column(
				modifier = Modifier.fillMaxWidth(),
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
					if (activeRelease != null) {
						Surface(
							shape = CircleShape,
							color = if (hasUpdate) {
								MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
							} else {
								MaterialTheme.colorScheme.surfaceContainerHigh
							},
						) {
							Text(
								text = if (hasUpdate) "最新 $displayReleaseTag" else "已是最新 $displayReleaseTag",
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
								text = "正在拉取 GitHub Release…",
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

						if (ipaAsset != null) {
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
										modifier = Modifier.weight(1f),
									) {
										Text("复制链接", maxLines = 1, overflow = TextOverflow.Ellipsis)
									}
								}
							}
						} else {
							Text(
								text = "该版本暂未附带 .ipa 安装包，可前往 GitHub Release 页面查看。",
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}

						OutlinedButton(
							onClick = {
								if (!iosOpenExternalUrl(activeRelease.htmlUrl)) {
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

						Text(
							text = "说明：受 iOS 沙盒限制无法静默覆盖安装，请通过 SideStore / AltStore 安装，或下载 IPA 手动重签安装。",
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
						)
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
				onClick = checkNow,
				enabled = !loading,
			) {
				Text("重新检查")
			}
		},
	)
}
