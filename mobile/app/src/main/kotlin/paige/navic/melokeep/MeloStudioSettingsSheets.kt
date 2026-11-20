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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import coil3.ImageLoader
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import paige.navic.di.LocalNavStack
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
import paige.navic.domain.model.settings.StreamingQuality
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.ChevronForward
import paige.navic.ui.navigation.Screen
import paige.navic.ui.theme.positive
import paige.navic.ui.theme.tabularNums
import paige.navic.util.toFileSize
import kotlin.math.roundToInt

// =============================================================================
// 1. 统一设置首页（Apple Notes / Linear 极简分组）
// =============================================================================

@Composable
fun MeloStudioSettingsHomeScreen(
	onBack: (() -> Unit)? = null
) {
	val preferenceManager = koinInject<PreferenceManager>()
	val sessionManager = koinInject<SessionManager>()
	val meloSettingsStore = koinInject<MeloSettingsStore>()
	val equalizerManager = koinInject<EqualizerManager>()
	val streamCacheManager = koinInject<StreamCacheManager>()
	val storageManager = koinInject<StorageManager>()
	val imageLoader = koinInject<ImageLoader>()
	val backStack = LocalNavStack.current
	val snackBarState = LocalSnackBarState.current
	val scope = rememberCoroutineScope()

	val sessionState by sessionManager.sessionState.collectAsStateWithLifecycle()
	val meloConfig by meloSettingsStore.config.collectAsStateWithLifecycle()
	val eqConfig by equalizerManager.config.collectAsStateWithLifecycle()

	var currentSheet by rememberSaveable { mutableStateOf<String?>(null) }
	var streamCacheBytes by remember { mutableLongStateOf(0L) }
	var imageCacheBytes by remember { mutableLongStateOf(0L) }
	var cacheSizeLoaded by remember { mutableStateOf(false) }
	var clearingCache by remember { mutableStateOf(false) }

	LaunchedEffect(currentSheet) {
		val (streamSize, imgSize) = withContext(Dispatchers.IO) {
			streamCacheManager.size to (imageLoader.diskCache?.size ?: 0L)
		}
		streamCacheBytes = streamSize
		imageCacheBytes = imgSize
		cacheSizeLoaded = true
	}

	val resolvedBack = SettingsRememberBackAction(onBack)
	val openOnboarding = dropUnlessResumed {
		if (backStack.lastOrNull() != Screen.MeloOnboarding) {
			backStack.add(Screen.MeloOnboarding)
		}
	}
	val openMeloSources = dropUnlessResumed {
		if (backStack.lastOrNull() != Screen.Settings.MeloKeep) {
			backStack.add(Screen.Settings.MeloKeep)
		}
	}

	Scaffold(
		modifier = Modifier.fillMaxSize(),
		containerColor = MaterialTheme.colorScheme.background,
		topBar = {
			MeloCleanTopBar(
				title = "设置",
				onBack = resolvedBack,
				applyStatusBarPadding = true
			)
		}
	) { innerPadding ->
		LazyColumn(
			modifier = Modifier
				.fillMaxSize()
				.padding(innerPadding)
				.background(MaterialTheme.colorScheme.background),
			contentPadding = PaddingValues(
				start = 16.dp,
				end = 16.dp,
				top = 8.dp,
				bottom = 28.dp
			),
			verticalArrangement = Arrangement.spacedBy(20.dp)
		) {
			// 1. 界面
			item(key = "section-ui", contentType = "settings-section") {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					MeloSectionLabel(title = "界面")
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
					}
				}
			}

			// 2. 歌词与播放页（合并原重复偏移卡片与二级歌词入口）
			item(key = "section-lyrics", contentType = "settings-section") {
				val fontSizeLabel = "${preferenceManager.lyricsFontSizeSp.coerceIn(18f, 40f).roundToInt()}sp"
				val offsetMs = preferenceManager.playerLyricsOffsetMs
				val offsetLabel = when {
					offsetMs > 0 -> "+${offsetMs}ms"
					offsetMs < 0 -> "${offsetMs}ms"
					else -> "0ms"
				}
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					MeloSectionLabel(title = "歌词与播放页")
					MeloGroupedCard {
						SettingsNavRow(
							title = "歌词与播放页",
							value = "$fontSizeLabel · 偏移 $offsetLabel",
							onClick = { currentSheet = "lyrics" }
						)
					}
				}
			}

			// 3. 音频
			item(key = "section-audio", contentType = "settings-section") {
				val wifiTag = SettingsQualityShortTag(preferenceManager.streamingQualityWifi)
				val cellTag = SettingsQualityShortTag(preferenceManager.streamingQualityCellular)
				val eqSummary = when (eqConfig.mode) {
					EqualizerMode.BuiltIn -> "内置 EQ"
					EqualizerMode.External -> "系统音效"
					EqualizerMode.Disabled -> "关闭"
				}
				val rgSummary = when (preferenceManager.replayGainMode) {
					ReplayGainMode.Off -> null
					ReplayGainMode.Track -> "单曲增益"
					ReplayGainMode.Album -> "专辑增益"
					ReplayGainMode.Dynamic -> "动态增益"
				}
				val eqValue = listOfNotNull(eqSummary, rgSummary).joinToString(" · ")

				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					MeloSectionLabel(title = "音频")
					MeloGroupedCard {
						SettingsToggleRow(
							title = "无缝播放",
							checked = preferenceManager.gaplessPlayback,
							onCheckedChange = { preferenceManager.gaplessPlayback = it }
						)

						MeloHairlineDivider(startIndent = 16.dp)

						SettingsNavRow(
							title = "流媒体与离线音质",
							value = "Wi-Fi $wifiTag · 移动 $cellTag",
							onClick = { currentSheet = "audio" }
						)

						MeloHairlineDivider(startIndent = 16.dp)

						SettingsNavRow(
							title = "均衡器与增益",
							value = eqValue,
							onClick = { currentSheet = "equalizer" }
						)
					}
				}
			}

			// 4. 服务
			item(key = "section-services", contentType = "settings-section") {
				val navidromeConnected = sessionState !is SessionState.Unauthenticated
				val navidromeStatus = when (sessionState) {
					is SessionState.Authenticated -> "已连接"
					is SessionState.Restoring -> "连接中"
					SessionState.Unauthenticated -> "未连接"
				}

				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					MeloSectionLabel(title = "服务")
					MeloGroupedCard {
						SettingsNavRow(
							title = meloConfig.effectiveName,
							value = if (meloConfig.isAuthenticated) "已连接" else "未连接",
							statusConnected = meloConfig.isAuthenticated,
							onClick = { currentSheet = "melokeep" }
						)

						MeloHairlineDivider(startIndent = 16.dp)

						SettingsNavRow(
							title = preferenceManager.navidromeServerName.trim().ifBlank { "Navidrome" },
							value = navidromeStatus,
							statusConnected = navidromeConnected,
							onClick = { currentSheet = "connection" }
						)

						MeloHairlineDivider(startIndent = 16.dp)

						SettingsNavRow(
							title = "连接向导",
							onClick = openOnboarding
						)

						MeloHairlineDivider(startIndent = 16.dp)

						SettingsNavRow(
							title = "音源与代理",
							onClick = openMeloSources
						)
					}
				}
			}

			// 5. 存储与同步
			item(key = "section-storage", contentType = "settings-section") {
				val totalCacheBytes = streamCacheBytes + imageCacheBytes
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					MeloSectionLabel(title = "存储与同步")
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
								text = if (cacheSizeLoaded) totalCacheBytes.toFileSize() else "…",
								style = MaterialTheme.typography.bodySmall.tabularNums(),
								color = MaterialTheme.colorScheme.onSurfaceVariant
							)
							if (totalCacheBytes > 0L) {
								Text(
									text = if (clearingCache) "清理中" else "清理",
									style = MaterialTheme.typography.labelMedium,
									color = MaterialTheme.colorScheme.primary,
									modifier = Modifier
										.clip(RoundedCornerShape(6.dp))
										.clickable(enabled = !clearingCache) {
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
						}

						MeloHairlineDivider(startIndent = 16.dp)

						SettingsNavRow(
							title = "离线模式与索引同步",
							onClick = { currentSheet = "storage" }
						)
					}
				}
			}

			// 6. 极简版本尾注
			item(key = "section-version", contentType = "settings-version") {
				val appVersionName = rememberMeloVersionName()
				Text(
					text = "MeloKeep v$appVersionName",
					style = MaterialTheme.typography.labelSmall.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
					textAlign = TextAlign.Center,
					modifier = Modifier
						.fillMaxWidth()
						.padding(top = 4.dp)
				)
			}
		}
	}

	MeloKeepConnectionSheet(
		presented = currentSheet == "melokeep",
		onDismissRequest = { currentSheet = null }
	)
	MeloNavidromeConnectionSheet(
		presented = currentSheet == "connection",
		onDismissRequest = { currentSheet = null }
	)
	MeloLyricsAndStageSheet(
		presented = currentSheet == "lyrics",
		onDismissRequest = { currentSheet = null }
	)
	MeloAudioEngineSheet(
		presented = currentSheet == "audio",
		onDismissRequest = { currentSheet = null }
	)
	MeloEqualizerSheet(
		presented = currentSheet == "equalizer",
		onDismissRequest = { currentSheet = null }
	)
	MeloStorageAndAboutSheet(
		presented = currentSheet == "storage",
		onDismissRequest = { currentSheet = null }
	)
}

// =============================================================================
// 2. 子面板 Screen & Sheet 外壳（100% 保留既有公开函数签名）
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeloKeepConnectionSheet(
	presented: Boolean,
	onDismissRequest: () -> Unit
) {
	if (!presented) return
	SettingsSheetShell(
		title = "MeloKeep 连接",
		onDismissRequest = onDismissRequest,
		imePadding = true
	) {
		SettingsMeloKeepConnectionPanel(
			modifier = Modifier.fillMaxWidth(),
			nestedInSheet = true
		)
	}
}

@Composable
fun MeloNavidromeConnectionScreen(onBack: (() -> Unit)? = null) {
	SettingsScreenShell(
		title = "Navidrome 连接",
		onBack = onBack,
		imePadding = true
	) { modifier ->
		SettingsNavidromeConnectionPanel(modifier = modifier)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeloNavidromeConnectionSheet(
	presented: Boolean,
	onDismissRequest: () -> Unit
) {
	if (!presented) return
	SettingsSheetShell(
		title = "Navidrome 连接",
		onDismissRequest = onDismissRequest,
		imePadding = true
	) {
		SettingsNavidromeConnectionPanel(
			modifier = Modifier.fillMaxWidth(),
			nestedInSheet = true
		)
	}
}

@Composable
fun MeloLyricsAndStageScreen(onBack: (() -> Unit)? = null) {
	SettingsScreenShell(
		title = "歌词与播放页",
		onBack = onBack
	) { modifier ->
		SettingsLyricsAndStagePanel(modifier = modifier)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeloLyricsAndStageSheet(
	presented: Boolean,
	onDismissRequest: () -> Unit
) {
	if (!presented) return
	SettingsSheetShell(
		title = "歌词与播放页",
		onDismissRequest = onDismissRequest
	) {
		SettingsLyricsAndStagePanel(
			modifier = Modifier.fillMaxWidth(),
			nestedInSheet = true
		)
	}
}

@Composable
fun MeloAudioEngineScreen(onBack: (() -> Unit)? = null) {
	SettingsScreenShell(
		title = "音质与增益",
		onBack = onBack
	) { modifier ->
		SettingsAudioEnginePanel(modifier = modifier)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeloAudioEngineSheet(
	presented: Boolean,
	onDismissRequest: () -> Unit
) {
	if (!presented) return
	SettingsSheetShell(
		title = "音质与增益",
		onDismissRequest = onDismissRequest
	) {
		SettingsAudioEnginePanel(
			modifier = Modifier.fillMaxWidth(),
			nestedInSheet = true
		)
	}
}

@Composable
fun MeloStorageAndAboutScreen(onBack: (() -> Unit)? = null) {
	SettingsScreenShell(
		title = "离线模式与索引同步",
		onBack = onBack
	) { modifier ->
		SettingsStorageAndAboutPanel(modifier = modifier)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeloStorageAndAboutSheet(
	presented: Boolean,
	onDismissRequest: () -> Unit
) {
	if (!presented) return
	SettingsSheetShell(
		title = "离线模式与索引同步",
		onDismissRequest = onDismissRequest
	) {
		SettingsStorageAndAboutPanel(
			modifier = Modifier.fillMaxWidth(),
			nestedInSheet = true
		)
	}
}

@Composable
fun MeloEqualizerScreen(onBack: (() -> Unit)? = null) {
	SettingsScreenShell(
		title = "均衡器",
		onBack = onBack
	) { modifier ->
		SettingsEqualizerPanel(modifier = modifier)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeloEqualizerSheet(
	presented: Boolean,
	onDismissRequest: () -> Unit
) {
	if (!presented) return
	SettingsSheetShell(
		title = "均衡器",
		onDismissRequest = onDismissRequest
	) {
		SettingsEqualizerPanel(
			modifier = Modifier.fillMaxWidth(),
			nestedInSheet = true
		)
	}
}

// =============================================================================
// 3. 跨设置文件共享辅助组件（统一 Settings 前缀防命名冲突）
// =============================================================================

@Composable
private fun SettingsScreenShell(
	title: String,
	onBack: (() -> Unit)?,
	imePadding: Boolean = false,
	content: @Composable (Modifier) -> Unit
) {
	val resolvedBack = SettingsRememberBackAction(onBack)
	Scaffold(
		modifier = Modifier.fillMaxSize(),
		containerColor = MaterialTheme.colorScheme.background,
		topBar = {
			MeloCleanTopBar(
				title = title,
				onBack = resolvedBack,
				applyStatusBarPadding = true
			)
		}
	) { innerPadding ->
		content(
			Modifier
				.fillMaxSize()
				.padding(innerPadding)
				.then(if (imePadding) Modifier.imePadding() else Modifier)
		)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheetShell(
	title: String,
	onDismissRequest: () -> Unit,
	imePadding: Boolean = false,
	content: @Composable () -> Unit
) {
	val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
	ModalBottomSheet(
		onDismissRequest = onDismissRequest,
		sheetState = sheetState,
		containerColor = MaterialTheme.colorScheme.background,
		contentColor = MaterialTheme.colorScheme.onSurface,
		dragHandle = null
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.windowInsetsPadding(WindowInsets.statusBars)
				.then(if (imePadding) Modifier.imePadding() else Modifier.navigationBarsPadding())
		) {
			MeloCleanTopBar(
				title = title,
				onBack = onDismissRequest,
				applyStatusBarPadding = false
			)
			content()
		}
	}
}

@Composable
internal fun SettingsRememberBackAction(onBack: (() -> Unit)?): (() -> Unit)? {
	val backStack = LocalNavStack.current
	val popAction = dropUnlessResumed {
		if (backStack.size > 1) {
			backStack.removeLastOrNull()
		}
	}
	return onBack ?: if (backStack.size > 1) popAction else null
}

@Composable
internal fun SettingsToggleRow(
	title: String,
	checked: Boolean,
	onCheckedChange: (Boolean) -> Unit
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable { onCheckedChange(!checked) }
			.padding(horizontal = 16.dp, vertical = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween
	) {
		Text(
			text = title,
			style = MaterialTheme.typography.bodyMedium,
			fontWeight = FontWeight.Medium,
			color = MaterialTheme.colorScheme.onSurface,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f)
		)
		Spacer(modifier = Modifier.width(12.dp))
		Switch(
			checked = checked,
			onCheckedChange = onCheckedChange,
			colors = SwitchDefaults.colors(
				checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
				checkedTrackColor = MaterialTheme.colorScheme.primary
			)
		)
	}
}

@Composable
internal fun SettingsNavRow(
	title: String,
	value: String? = null,
	statusConnected: Boolean? = null,
	onClick: () -> Unit
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(horizontal = 16.dp, vertical = 13.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp)
	) {
		if (statusConnected != null) {
			Box(
				modifier = Modifier
					.size(7.dp)
					.clip(CircleShape)
					.background(
						if (statusConnected) MaterialTheme.colorScheme.positive
						else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
					)
			)
		}
		Text(
			text = title,
			style = MaterialTheme.typography.bodyMedium,
			fontWeight = FontWeight.Medium,
			color = MaterialTheme.colorScheme.onSurface,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f)
		)
		if (!value.isNullOrBlank()) {
			Text(
				text = value,
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
internal fun SettingsChoicePill(
	label: String,
	selected: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier
) {
	Surface(
		onClick = onClick,
		shape = RoundedCornerShape(10.dp),
		color = if (selected) {
			MaterialTheme.colorScheme.primary
		} else {
			MaterialTheme.colorScheme.surfaceContainerHigh
		},
		modifier = modifier
	) {
		Box(
			modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
			contentAlignment = Alignment.Center
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
				overflow = TextOverflow.Ellipsis
			)
		}
	}
}

@Composable
internal fun SettingsStepPill(
	label: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	highlighted: Boolean = false
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
			width = MeloHairlineWidth,
			color = if (highlighted) {
				MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
			} else {
				MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
			}
		),
		modifier = modifier
	) {
		Box(
			modifier = Modifier.padding(horizontal = 2.dp, vertical = 6.dp),
			contentAlignment = Alignment.Center
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
				overflow = TextOverflow.Clip
			)
		}
	}
}

internal fun SettingsCoverShapeLabel(shape: CoverArtShape): String = when (shape) {
	CoverArtShape.Square -> "方形"
	CoverArtShape.Soft -> "微圆角"
	CoverArtShape.Curved -> "圆角"
	CoverArtShape.Circle -> "圆形"
}

internal fun SettingsQualityShortTag(quality: StreamingQuality): String = when (quality) {
	StreamingQuality.Low -> "80k"
	StreamingQuality.Medium -> "128k"
	StreamingQuality.High -> "192k"
	StreamingQuality.Lossless -> "无损"
}

internal fun SettingsFormatOffsetLabel(offsetMs: Int): String = when {
	offsetMs > 0 -> "+$offsetMs ms"
	offsetMs < 0 -> "$offsetMs ms"
	else -> "0 ms"
}

internal fun SettingsFormatGain(value: Float): String {
	val rounded = (value * 10f).roundToInt() / 10f
	return when {
		rounded > 0f -> "+${rounded} dB"
		rounded < 0f -> "${rounded} dB"
		else -> "0.0 dB"
	}
}
