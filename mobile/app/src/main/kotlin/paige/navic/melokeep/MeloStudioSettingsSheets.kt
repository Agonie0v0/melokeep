/*
 * Navic / MeloKeep, a Subsonic music streaming & studio app for Android
 * Copyright (c) 2026 paige & MeloKeep Swarm
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.melokeep

import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import coil3.ImageLoader
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import io.github.agonie0v0.melokeep.shared.hasAndroidUpdateFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalSnackBarState
import paige.navic.domain.manager.playback.EqualizerManager
import paige.navic.domain.manager.playback.StreamCacheManager
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.manager.session.SessionManager
import paige.navic.domain.manager.session.SessionState
import paige.navic.domain.manager.session.SyncManager
import paige.navic.domain.model.settings.BottomBarCollapseMode
import paige.navic.domain.model.settings.CoverArtShape
import paige.navic.domain.model.settings.EqualizerMode
import paige.navic.domain.model.settings.OfflineMode
import paige.navic.domain.model.settings.ReplayGainMode
import paige.navic.domain.model.settings.StreamingQuality
import paige.navic.domain.model.settings.ThemeMode
import paige.navic.domain.model.settings.UpdateCheckFrequency
import paige.navic.ui.component.sheet.ChangelogViewModel
import paige.navic.ui.component.sheet.UpdateCheckFeedback
import paige.navic.ui.component.sheet.formatReleaseTag
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.filled.BottomNavigation
import paige.navic.ui.icons.filled.Headphones
import paige.navic.ui.icons.filled.Palette
import paige.navic.ui.icons.outlined.ChevronForward
import paige.navic.ui.icons.outlined.Info
import paige.navic.ui.icons.outlined.Lyrics
import paige.navic.ui.icons.outlined.Refresh
import paige.navic.ui.icons.outlined.Statistics
import paige.navic.ui.navigation.Screen
import paige.navic.ui.theme.MeloStudioPresets
import paige.navic.ui.theme.tabularNums
import paige.navic.util.toFileSize
import kotlin.math.roundToInt

// =============================================================================
// 1. 统一设置首页与共享 Profile Hub 主体
// =============================================================================

@Composable
fun MeloStudioSettingsHomeScreen(
	onBack: (() -> Unit)? = null
) {
	val backStack = LocalNavStack.current
	val resolvedBack = SettingsRememberBackAction(onBack)
	val openOnboarding = dropUnlessResumed {
		if (backStack.lastOrNull() != Screen.MeloOnboarding) {
			backStack.add(Screen.MeloOnboarding)
		}
	}
	val openPhoneDownloads = dropUnlessResumed {
		if (backStack.lastOrNull() != Screen.PhoneDownloads) {
			backStack.add(Screen.PhoneDownloads)
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
		MeloProfileHubBody(
			modifier = Modifier
				.fillMaxSize()
				.padding(innerPadding)
				.background(MaterialTheme.colorScheme.background),
			bottomContentPadding = 28.dp,
			onOpenOnboarding = openOnboarding,
			onOpenPhoneDownloads = openPhoneDownloads
		)
	}
}

@Composable
internal fun MeloProfileHubBody(
	modifier: Modifier = Modifier,
	bottomContentPadding: Dp,
	onOpenOnboarding: () -> Unit,
	onOpenPhoneDownloads: (() -> Unit)? = null
) {
	val meloSettingsStore = koinInject<MeloSettingsStore>()
	val sessionManager = koinInject<SessionManager>()
	val syncManager = koinInject<SyncManager>()
	val equalizerManager = koinInject<EqualizerManager>()
	val streamCacheManager = koinInject<StreamCacheManager>()
	val imageLoader = koinInject<ImageLoader>()
	val preferenceManager = koinInject<PreferenceManager>()
	val backStack = LocalNavStack.current
	val snackBarState = LocalSnackBarState.current
	val context = LocalContext.current
	val lifecycleOwner = LocalLifecycleOwner.current

	val activityOwner = LocalActivity.current as? ViewModelStoreOwner
	val changelogViewModel = if (activityOwner != null) {
		koinViewModel<ChangelogViewModel>(viewModelStoreOwner = activityOwner)
	} else {
		koinViewModel<ChangelogViewModel>()
	}

	val meloConfig by meloSettingsStore.config.collectAsStateWithLifecycle()
	val studioPreset by meloSettingsStore.studioThemePreset.collectAsStateWithLifecycle()
	val sessionState by sessionManager.sessionState.collectAsStateWithLifecycle()
	val syncState by syncManager.syncState.collectAsStateWithLifecycle()
	val eqConfig by equalizerManager.config.collectAsStateWithLifecycle()
	val checkFeedback by changelogViewModel.checkFeedback.collectAsStateWithLifecycle()
	val latestRelease by changelogViewModel.latestRelease.collectAsStateWithLifecycle()
	val appVersionName = rememberMeloVersionName()
	val positiveColor = rememberThemePositiveColor()

	var streamCacheBytes by remember { mutableLongStateOf(0L) }
	var imageCacheBytes by remember { mutableLongStateOf(0L) }
	var cacheSizeLoaded by remember { mutableStateOf(false) }
	var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

	var currentSheet by rememberSaveable { mutableStateOf<String?>(null) }
	var initialServerTab by rememberSaveable { mutableIntStateOf(0) }

	DisposableEffect(lifecycleOwner) {
		val observer = LifecycleEventObserver { _, event ->
			if (event == Lifecycle.Event.ON_RESUME) {
				hasOverlayPermission = Settings.canDrawOverlays(context)
			}
		}
		lifecycleOwner.lifecycle.addObserver(observer)
		onDispose {
			lifecycleOwner.lifecycle.removeObserver(observer)
		}
	}

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
	val cacheDisplayText = if (cacheSizeLoaded) tempCacheBytes.toFileSize() else "…"
	val updateTag = latestRelease
		?.takeIf { it.hasAndroidUpdateFor(appVersionName) }
		?.tagName
		?.takeIf { it.isNotBlank() }
	val isChecking = checkFeedback is UpdateCheckFeedback.Checking
	val normalizedNewTag = updateTag?.let(::formatReleaseTag)

	val navidromeConnected = sessionState !is SessionState.Unauthenticated
	val navidromeStatusText = when (sessionState) {
		is SessionState.Authenticated -> "已连接"
		is SessionState.Restoring -> "连接中"
		SessionState.Unauthenticated -> "未连接"
	}
	val navidromeDisplayName = preferenceManager.navidromeServerName.trim().ifBlank { "Navidrome" }

	// 功能域底托柔和色调
	val serviceContainer = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
	val serviceTint = MaterialTheme.colorScheme.primary
	val audioContainer = positiveColor.copy(alpha = 0.13f)
	val audioTint = positiveColor
	val appearanceContainer = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)
	val appearanceTint = MaterialTheme.colorScheme.tertiary
	val systemContainer = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.11f)
	val systemTint = MaterialTheme.colorScheme.onSurfaceVariant

	LazyColumn(
		modifier = modifier,
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = 8.dp,
			bottom = bottomContentPadding
		),
		verticalArrangement = Arrangement.spacedBy(20.dp)
	) {
		// 顶部双服务状态总览卡
		item(key = "hub-overview", contentType = "hub-overview") {
			HubServicesOverviewCard(
				meloName = meloConfig.effectiveName,
				meloConnected = meloConfig.isAuthenticated,
				navidromeName = navidromeDisplayName,
				navidromeConnected = navidromeConnected,
				navidromeRestoring = sessionState is SessionState.Restoring,
				navidromeStatusText = navidromeStatusText,
				cacheText = cacheDisplayText,
				appVersionName = appVersionName,
				hasNewVersion = !isChecking && normalizedNewTag != null,
				positiveColor = positiveColor,
				onOpenOnboarding = onOpenOnboarding,
				onOpenMeloKeep = {
					initialServerTab = 0
					currentSheet = "servers"
				},
				onOpenNavidrome = {
					initialServerTab = 1
					currentSheet = "servers"
				},
				onOpenVersion = { currentSheet = "version" }
			)
		}

		// 1. 服务与同步
		item(key = "hub-services-sync", contentType = "hub-group") {
			val meloShort = if (meloConfig.isAuthenticated) "MeloKeep 已连" else "MeloKeep 未连"
			val naviShort = when (sessionState) {
				is SessionState.Authenticated -> "Navidrome 已连"
				is SessionState.Restoring -> "Navidrome 连接中"
				SessionState.Unauthenticated -> "Navidrome 未连"
			}
			val connectionSummary = when {
				meloConfig.isAuthenticated && sessionState is SessionState.Authenticated -> "MeloKeep · Navidrome 已连接"
				else -> "$meloShort · $naviShort"
			}
			val offlineLabel = when (preferenceManager.offlineMode) {
				OfflineMode.Auto -> "在线模式"
				OfflineMode.Forced -> "离线模式"
				OfflineMode.NoWiFi -> "仅 Wi-Fi"
			}
			val syncLabel = when {
				syncState.isSyncing -> "同步中"
				preferenceManager.lastFullSyncTime > 0L -> "已索引"
				else -> "待同步"
			}

			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "服务与同步")
				MeloGroupedCard {
					HubSettingsRow(
						icon = MeloIcons.Cloud,
						iconTint = serviceTint,
						iconContainerColor = serviceContainer,
						title = "服务器连接",
						value = connectionSummary,
						onClick = {
							initialServerTab = if (meloConfig.isAuthenticated || !navidromeConnected) 0 else 1
							currentSheet = "servers"
						}
					)
					MeloHairlineDivider(startIndent = 58.dp)
					HubSettingsRow(
						icon = Icons.Outlined.Refresh,
						iconTint = serviceTint,
						iconContainerColor = serviceContainer,
						title = "曲库与同步",
						value = "$offlineLabel · $syncLabel",
						onClick = { currentSheet = "sync" }
					)
					if (meloConfig.isAuthenticated) {
						MeloHairlineDivider(startIndent = 58.dp)
						HubSettingsRow(
							icon = MeloIcons.Bolt,
							iconTint = serviceTint,
							iconContainerColor = serviceContainer,
							title = "音源与代理",
							value = "服务端与音源配置",
							onClick = {
								if (backStack.lastOrNull() != Screen.Settings.MeloKeep) {
									backStack.add(Screen.Settings.MeloKeep)
								}
							}
						)
					}
				}
			}
		}

		// 2. 播放与音效
		item(key = "hub-audio-effects", contentType = "hub-group") {
			val wifiTag = SettingsQualityShortTag(preferenceManager.streamingQualityWifi)
			val gaplessTag = if (preferenceManager.gaplessPlayback) "无缝开" else "无缝关"
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
			val soundSummary = listOfNotNull(rgSummary, eqSummary)
				.joinToString(" · ")
				.ifBlank { "已关闭" }

			val fontSizeLabel = "${preferenceManager.lyricsFontSizeSp.coerceIn(18f, 40f).roundToInt()}sp"
			val desktopLyricsActive = preferenceManager.desktopLyricsEnabled && hasOverlayPermission
			val offsetMs = preferenceManager.playerLyricsOffsetMs
			val offsetLabel = when {
				offsetMs > 0 -> "+${offsetMs}ms"
				offsetMs < 0 -> "${offsetMs}ms"
				else -> "0ms"
			}
			val lyricsSummary = if (desktopLyricsActive) {
				"桌面歌词已开 · $fontSizeLabel"
			} else {
				"$fontSizeLabel · 偏移 $offsetLabel"
			}

			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "播放与音效")
				MeloGroupedCard {
					HubSettingsRow(
						icon = Icons.Filled.Headphones,
						iconTint = audioTint,
						iconContainerColor = audioContainer,
						title = "音质与播放",
						value = "Wi-Fi $wifiTag · $gaplessTag",
						onClick = { currentSheet = "playback" }
					)
					MeloHairlineDivider(startIndent = 58.dp)
					HubSettingsRow(
						icon = Icons.Outlined.Statistics,
						iconTint = audioTint,
						iconContainerColor = audioContainer,
						title = "音效与增益",
						value = soundSummary,
						onClick = { currentSheet = "sound" }
					)
					MeloHairlineDivider(startIndent = 58.dp)
					HubSettingsRow(
						icon = Icons.Outlined.Lyrics,
						iconTint = serviceTint,
						iconContainerColor = serviceContainer,
						title = "歌词与桌面歌词",
						value = lyricsSummary,
						onClick = { currentSheet = "lyrics" }
					)
				}
			}
		}

		// 3. 外观与交互
		item(key = "hub-appearance", contentType = "hub-group") {
			val presetDescriptor = remember(studioPreset) {
				MeloStudioPresets.all.firstOrNull { it.id == studioPreset }
					?: MeloStudioPresets.all.first()
			}
			val presetShortName = compactHubPresetLabel(presetDescriptor)
			val themeModeLabel = when (preferenceManager.themeMode) {
				ThemeMode.System -> "跟随系统"
				ThemeMode.Dark -> "深色"
				ThemeMode.Light -> "浅色"
			}
			val themeSummary = if (studioPreset == MeloStudioPresets.DYNAMIC) {
				"$presetShortName · $themeModeLabel"
			} else {
				presetShortName
			}
			val interfaceSummary = if (preferenceManager.bottomBarCollapseMode == BottomBarCollapseMode.OnScroll) {
				"下滑隐藏底栏"
			} else {
				"底栏常驻"
			}

			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "外观与交互")
				MeloGroupedCard {
					HubSettingsRow(
						icon = Icons.Filled.Palette,
						iconTint = appearanceTint,
						iconContainerColor = appearanceContainer,
						title = "主题与外观",
						value = themeSummary,
						accentDotColor = presetDescriptor.previewAccent,
						onClick = { currentSheet = "theme" }
					)
					MeloHairlineDivider(startIndent = 58.dp)
					HubSettingsRow(
						icon = Icons.Filled.BottomNavigation,
						iconTint = appearanceTint,
						iconContainerColor = appearanceContainer,
						title = "界面与播放页",
						value = interfaceSummary,
						onClick = { currentSheet = "interface" }
					)
				}
			}
		}

		// 4. 存储与系统
		item(key = "hub-storage-system", contentType = "hub-group") {
			val versionSummary = when {
				isChecking -> "检查中…"
				normalizedNewTag != null -> "发现 $normalizedNewTag"
				else -> "当前 v$appVersionName"
			}

			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "存储与系统")
				MeloGroupedCard {
					HubSettingsRow(
						icon = MeloIcons.Folder,
						iconTint = systemTint,
						iconContainerColor = systemContainer,
						title = "存储与缓存",
						value = "缓存 $cacheDisplayText",
						onClick = { currentSheet = "storage" }
					)
					MeloHairlineDivider(startIndent = 58.dp)
					HubSettingsRow(
						icon = Icons.Outlined.Info,
						iconTint = systemTint,
						iconContainerColor = systemContainer,
						title = "版本与更新",
						value = versionSummary,
						badgeDotColor = if (!isChecking && normalizedNewTag != null) {
							MaterialTheme.colorScheme.error
						} else {
							null
						},
						valueHighlight = !isChecking && normalizedNewTag != null,
						onClick = { currentSheet = "version" }
					)
				}
			}
		}

		// 底部版本尾注
		item(key = "hub-version-footer", contentType = "hub-version") {
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

	MeloServerConnectionsSheet(
		presented = currentSheet == "servers",
		initialTab = initialServerTab,
		onDismissRequest = { currentSheet = null }
	)
	MeloLibrarySyncSheet(
		presented = currentSheet == "sync",
		onDismissRequest = { currentSheet = null }
	)
	MeloPlaybackAndQualitySheet(
		presented = currentSheet == "playback",
		onDismissRequest = { currentSheet = null }
	)
	MeloSoundAndGainSheet(
		presented = currentSheet == "sound",
		onDismissRequest = { currentSheet = null }
	)
	MeloLyricsSettingsSheet(
		presented = currentSheet == "lyrics",
		onDismissRequest = { currentSheet = null }
	)
	MeloThemeAppearanceSheet(
		presented = currentSheet == "theme",
		onDismissRequest = { currentSheet = null }
	)
	MeloInterfaceGesturesSheet(
		presented = currentSheet == "interface",
		onDismissRequest = { currentSheet = null }
	)
	MeloStorageCacheSheet(
		presented = currentSheet == "storage",
		onOpenPhoneDownloads = onOpenPhoneDownloads,
		onCacheCleared = {
			streamCacheBytes = 0L
			imageCacheBytes = 0L
		},
		onDismissRequest = { currentSheet = null }
	)
	MeloVersionUpdateSheet(
		presented = currentSheet == "version",
		onDismissRequest = { currentSheet = null }
	)
}

@Composable
private fun HubServicesOverviewCard(
	meloName: String,
	meloConnected: Boolean,
	navidromeName: String,
	navidromeConnected: Boolean,
	navidromeRestoring: Boolean,
	navidromeStatusText: String,
	cacheText: String,
	appVersionName: String,
	hasNewVersion: Boolean,
	positiveColor: Color,
	@Suppress("UNUSED_PARAMETER") onOpenOnboarding: () -> Unit,
	onOpenMeloKeep: () -> Unit,
	onOpenNavidrome: () -> Unit,
	onOpenVersion: () -> Unit
) {
	val navidromeOnline = navidromeConnected && !navidromeRestoring
	val onlineCount = (if (meloConnected) 1 else 0) + (if (navidromeOnline) 1 else 0)
	val summaryText = when {
		onlineCount == 2 -> "双服务在线"
		onlineCount == 1 -> "单服务在线"
		navidromeRestoring -> "正在恢复连接"
		else -> "尚未连接"
	}
	val summaryAccent = when {
		onlineCount == 2 -> positiveColor
		onlineCount == 1 || navidromeRestoring -> MaterialTheme.colorScheme.primary
		else -> MaterialTheme.colorScheme.onSurfaceVariant
	}
	val summaryDotColor = when {
		onlineCount == 2 -> positiveColor
		onlineCount == 1 || navidromeRestoring -> MaterialTheme.colorScheme.primary
		else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)
	}
	val summaryBg = when {
		onlineCount > 0 || navidromeRestoring -> summaryAccent.copy(alpha = 0.11f)
		else -> MaterialTheme.colorScheme.surfaceContainerHigh
	}
	val summaryBorder = when {
		onlineCount > 0 || navidromeRestoring -> summaryAccent.copy(alpha = 0.28f)
		else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
	}

	MeloGroupedCard {
		Column(modifier = Modifier.fillMaxWidth()) {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp, vertical = 14.dp),
				verticalArrangement = Arrangement.spacedBy(12.dp)
			) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically
				) {
					Text(
						text = "连接与状态",
						style = MaterialTheme.typography.titleSmall,
						fontWeight = FontWeight.SemiBold,
						color = MaterialTheme.colorScheme.onSurface
					)
					Surface(
						shape = CircleShape,
						color = summaryBg,
						border = BorderStroke(MeloHairlineWidth, summaryBorder)
					) {
						Row(
							modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(5.dp)
						) {
							Box(
								modifier = Modifier
									.size(6.dp)
									.clip(CircleShape)
									.background(summaryDotColor)
							)
							Text(
								text = summaryText,
								style = MaterialTheme.typography.labelSmall,
								fontWeight = FontWeight.Medium,
								color = summaryAccent,
								maxLines = 1
							)
						}
					}
				}

				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.spacedBy(10.dp)
				) {
					HubServiceStatusPill(
						title = meloName,
						statusText = if (meloConnected) "已连接" else "未连接",
						dotColor = if (meloConnected) {
							positiveColor
						} else {
							MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
						},
						highlighted = meloConnected,
						positiveColor = positiveColor,
						onClick = onOpenMeloKeep,
						modifier = Modifier.weight(1f)
					)

					HubServiceStatusPill(
						title = navidromeName,
						statusText = navidromeStatusText,
						dotColor = when {
							navidromeRestoring -> MaterialTheme.colorScheme.primary
							navidromeConnected -> positiveColor
							else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
						},
						highlighted = navidromeConnected && !navidromeRestoring,
						positiveColor = positiveColor,
						onClick = onOpenNavidrome,
						modifier = Modifier.weight(1f)
					)
				}
			}

			MeloHairlineDivider()

			Row(
				modifier = Modifier
					.fillMaxWidth()
					.clickable(onClick = onOpenVersion)
					.padding(horizontal = 16.dp, vertical = 10.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween
			) {
				Text(
					text = "缓存 $cacheText · 当前 v$appVersionName",
					style = MaterialTheme.typography.labelMedium.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f)
				)
				if (hasNewVersion) {
					Spacer(modifier = Modifier.width(8.dp))
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(5.dp)
					) {
						Box(
							modifier = Modifier
								.size(6.dp)
								.clip(CircleShape)
								.background(MaterialTheme.colorScheme.error)
						)
						Text(
							text = "发现新版本",
							style = MaterialTheme.typography.labelMedium,
							fontWeight = FontWeight.SemiBold,
							color = MaterialTheme.colorScheme.primary
						)
					}
				}
			}
		}
	}
}

@Composable
private fun HubServiceStatusPill(
	title: String,
	statusText: String,
	dotColor: Color,
	highlighted: Boolean,
	positiveColor: Color,
	onClick: () -> Unit,
	modifier: Modifier = Modifier
) {
	Surface(
		onClick = onClick,
		shape = RoundedCornerShape(12.dp),
		color = if (highlighted) {
			positiveColor.copy(alpha = 0.08f)
		} else {
			MaterialTheme.colorScheme.surfaceContainerHigh
		},
		border = BorderStroke(
			width = MeloHairlineWidth,
			color = if (highlighted) {
				positiveColor.copy(alpha = 0.34f)
			} else {
				MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
			}
		),
		modifier = modifier
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 12.dp, vertical = 10.dp),
			verticalArrangement = Arrangement.spacedBy(4.dp)
		) {
			Text(
				text = title,
				style = MaterialTheme.typography.bodyMedium,
				fontWeight = FontWeight.SemiBold,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(6.dp)
			) {
				Box(
					modifier = Modifier
						.size(7.dp)
						.clip(CircleShape)
						.background(dotColor)
				)
				Text(
					text = statusText,
					style = MaterialTheme.typography.labelSmall,
					color = if (highlighted) positiveColor else MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
			}
		}
	}
}

@Composable
internal fun HubSettingsRow(
	icon: ImageVector,
	iconTint: Color,
	iconContainerColor: Color,
	title: String,
	onClick: () -> Unit,
	subtitle: String? = null,
	value: String? = null,
	accentDotColor: Color? = null,
	badgeDotColor: Color? = null,
	valueHighlight: Boolean = false
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(horizontal = 14.dp, vertical = 12.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp)
	) {
		Box(
			modifier = Modifier
				.size(32.dp)
				.clip(RoundedCornerShape(9.dp))
				.background(iconContainerColor),
			contentAlignment = Alignment.Center
		) {
			Icon(
				imageVector = icon,
				contentDescription = null,
				tint = iconTint,
				modifier = Modifier.size(18.dp)
			)
		}

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
			if (!subtitle.isNullOrBlank()) {
				Text(
					text = subtitle,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
			}
		}

		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp)
		) {
			if (badgeDotColor != null) {
				Box(
					modifier = Modifier
						.size(7.dp)
						.clip(CircleShape)
						.background(badgeDotColor)
				)
			}
			if (!value.isNullOrBlank()) {
				Text(
					text = value,
					style = MaterialTheme.typography.bodySmall.tabularNums(),
					fontWeight = if (valueHighlight) FontWeight.SemiBold else FontWeight.Normal,
					color = if (valueHighlight) {
						MaterialTheme.colorScheme.primary
					} else {
						MaterialTheme.colorScheme.onSurfaceVariant
					},
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.widthIn(max = 152.dp)
				)
			}
			if (accentDotColor != null) {
				Box(
					modifier = Modifier
						.size(10.dp)
						.clip(CircleShape)
						.background(accentDotColor)
						.border(
							width = MeloHairlineWidth,
							color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
							shape = CircleShape
						)
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
}

private fun compactHubPresetLabel(descriptor: MeloStudioPresets.Descriptor): String = when (descriptor.id) {
	MeloStudioPresets.OBSIDIAN -> "曜石录音室"
	MeloStudioPresets.VINYL_GOLD -> "流金黑胶"
	MeloStudioPresets.MIDNIGHT_AURORA -> "午夜极光"
	MeloStudioPresets.CYBER_AMETHYST -> "暗紫霓虹"
	MeloStudioPresets.PURE_LIGHT -> "纯净晨雾"
	MeloStudioPresets.WARM_VINYL -> "暖调黑胶纸"
	MeloStudioPresets.NORDIC_FROST -> "北欧冰川"
	MeloStudioPresets.ROSE_MIST -> "雾樱漫音"
	MeloStudioPresets.DYNAMIC -> "跟随封面"
	else -> descriptor.title
}

// =============================================================================
// 2. 新层级二级 Sheet 集合
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeloServerConnectionsSheet(
	presented: Boolean,
	initialTab: Int = 0,
	onDismissRequest: () -> Unit
) {
	if (!presented) return
	val meloSettingsStore = koinInject<MeloSettingsStore>()
	val sessionManager = koinInject<SessionManager>()
	val preferenceManager = koinInject<PreferenceManager>()
	val meloConfig by meloSettingsStore.config.collectAsStateWithLifecycle()
	val sessionState by sessionManager.sessionState.collectAsStateWithLifecycle()
	val positiveColor = rememberThemePositiveColor()

	var selectedTab by rememberSaveable(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 1)) }
	val navidromeConnected = sessionState !is SessionState.Unauthenticated
	val navidromeLabel = preferenceManager.navidromeServerName.trim().ifBlank { "Navidrome" }

	SettingsSheetShell(
		title = "服务器连接",
		onDismissRequest = onDismissRequest,
		imePadding = true
	) {
		Surface(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 6.dp),
			shape = RoundedCornerShape(12.dp),
			color = MaterialTheme.colorScheme.surfaceContainer,
			border = BorderStroke(
				width = MeloHairlineWidth,
				color = MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
			)
		) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(4.dp),
				horizontalArrangement = Arrangement.spacedBy(6.dp)
			) {
				ServerTabSegmentPill(
					label = meloConfig.effectiveName,
					connected = meloConfig.isAuthenticated,
					selected = selectedTab == 0,
					positiveColor = positiveColor,
					onClick = { selectedTab = 0 },
					modifier = Modifier.weight(1f)
				)
				ServerTabSegmentPill(
					label = navidromeLabel,
					connected = navidromeConnected,
					selected = selectedTab == 1,
					positiveColor = positiveColor,
					onClick = { selectedTab = 1 },
					modifier = Modifier.weight(1f)
				)
			}
		}

		if (selectedTab == 0) {
			SettingsMeloKeepConnectionPanel(
				modifier = Modifier
					.fillMaxWidth()
					.weight(1f, fill = false),
				nestedInSheet = true
			)
		} else {
			SettingsNavidromeConnectionPanel(
				modifier = Modifier
					.fillMaxWidth()
					.weight(1f, fill = false),
				nestedInSheet = true
			)
		}
	}
}

@Composable
private fun ServerTabSegmentPill(
	label: String,
	connected: Boolean,
	selected: Boolean,
	positiveColor: Color,
	onClick: () -> Unit,
	modifier: Modifier = Modifier
) {
	Surface(
		onClick = onClick,
		shape = RoundedCornerShape(9.dp),
		color = if (selected) {
			MaterialTheme.colorScheme.primary
		} else {
			Color.Transparent
		},
		modifier = modifier
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 10.dp, vertical = 8.dp),
			horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
			verticalAlignment = Alignment.CenterVertically
		) {
			Box(
				modifier = Modifier
					.size(6.dp)
					.clip(CircleShape)
					.background(
						when {
							selected && connected -> MaterialTheme.colorScheme.onPrimary
							selected -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.45f)
							connected -> positiveColor
							else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
						}
					)
			)
			Text(
				text = label,
				style = MaterialTheme.typography.labelLarge,
				fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
				color = if (selected) {
					MaterialTheme.colorScheme.onPrimary
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeloLibrarySyncSheet(
	presented: Boolean,
	onDismissRequest: () -> Unit
) {
	if (!presented) return
	SettingsSheetShell(
		title = "曲库与同步",
		onDismissRequest = onDismissRequest
	) {
		SettingsLibrarySyncPanel(
			modifier = Modifier.fillMaxWidth(),
			nestedInSheet = true
		)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeloPlaybackAndQualitySheet(
	presented: Boolean,
	onDismissRequest: () -> Unit
) {
	if (!presented) return
	SettingsSheetShell(
		title = "音质与播放",
		onDismissRequest = onDismissRequest
	) {
		SettingsPlaybackAndQualityPanel(
			modifier = Modifier.fillMaxWidth(),
			nestedInSheet = true
		)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeloSoundAndGainSheet(
	presented: Boolean,
	onDismissRequest: () -> Unit
) {
	if (!presented) return
	SettingsSheetShell(
		title = "音效与增益",
		onDismissRequest = onDismissRequest
	) {
		SettingsSoundAndGainPanel(
			modifier = Modifier.fillMaxWidth(),
			nestedInSheet = true
		)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeloLyricsSettingsSheet(
	presented: Boolean,
	onDismissRequest: () -> Unit
) {
	if (!presented) return
	SettingsSheetShell(
		title = "歌词与桌面歌词",
		onDismissRequest = onDismissRequest,
		imePadding = true
	) {
		SettingsLyricsSettingsPanel(
			modifier = Modifier.fillMaxWidth(),
			nestedInSheet = true
		)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeloThemeAppearanceSheet(
	presented: Boolean,
	onDismissRequest: () -> Unit
) {
	if (!presented) return
	SettingsSheetShell(
		title = "主题与外观",
		onDismissRequest = onDismissRequest
	) {
		SettingsThemeAppearancePanel(
			modifier = Modifier.fillMaxWidth(),
			nestedInSheet = true
		)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeloInterfaceGesturesSheet(
	presented: Boolean,
	onDismissRequest: () -> Unit
) {
	if (!presented) return
	SettingsSheetShell(
		title = "界面与播放页",
		onDismissRequest = onDismissRequest
	) {
		SettingsInterfaceGesturesPanel(
			modifier = Modifier.fillMaxWidth(),
			nestedInSheet = true
		)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeloStorageCacheSheet(
	presented: Boolean,
	onOpenPhoneDownloads: (() -> Unit)? = null,
	onCacheCleared: () -> Unit = {},
	onDismissRequest: () -> Unit
) {
	if (!presented) return
	SettingsSheetShell(
		title = "存储与缓存",
		onDismissRequest = onDismissRequest
	) {
		SettingsStorageCachePanel(
			modifier = Modifier.fillMaxWidth(),
			nestedInSheet = true,
			onOpenPhoneDownloads = onOpenPhoneDownloads?.let { action ->
				{
					onDismissRequest()
					action()
				}
			},
			onCacheCleared = onCacheCleared
		)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeloVersionUpdateSheet(
	presented: Boolean,
	onDismissRequest: () -> Unit
) {
	if (!presented) return
	val activityOwner = LocalActivity.current as? ViewModelStoreOwner
	val changelogViewModel = if (activityOwner != null) {
		koinViewModel<ChangelogViewModel>(viewModelStoreOwner = activityOwner)
	} else {
		koinViewModel<ChangelogViewModel>()
	}
	val appVersionName = rememberMeloVersionName()

	SettingsSheetShell(
		title = "版本与更新",
		onDismissRequest = onDismissRequest
	) {
		SettingsVersionUpdatePanel(
			changelogViewModel = changelogViewModel,
			appVersionName = appVersionName,
			modifier = Modifier.fillMaxWidth(),
			nestedInSheet = true
		)
	}
}

// =============================================================================
// 3. 向后兼容子面板 Screen & Sheet 外壳（100% 保留既有公开函数签名）
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
		title = "歌词与桌面歌词",
		onBack = onBack,
		imePadding = true
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
	MeloLyricsSettingsSheet(
		presented = presented,
		onDismissRequest = onDismissRequest
	)
}

@Composable
fun MeloAudioEngineScreen(onBack: (() -> Unit)? = null) {
	SettingsScreenShell(
		title = "音质与播放",
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
	MeloPlaybackAndQualitySheet(
		presented = presented,
		onDismissRequest = onDismissRequest
	)
}

@Composable
fun MeloStorageAndAboutScreen(onBack: (() -> Unit)? = null) {
	SettingsScreenShell(
		title = "曲库与同步",
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
	MeloLibrarySyncSheet(
		presented = presented,
		onDismissRequest = onDismissRequest
	)
}

@Composable
fun MeloEqualizerScreen(onBack: (() -> Unit)? = null) {
	SettingsScreenShell(
		title = "音效与增益",
		onBack = onBack
	) { modifier ->
		SettingsSoundAndGainPanel(modifier = modifier)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeloEqualizerSheet(
	presented: Boolean,
	onDismissRequest: () -> Unit
) {
	MeloSoundAndGainSheet(
		presented = presented,
		onDismissRequest = onDismissRequest
	)
}

// =============================================================================
// 4. 跨设置文件共享辅助组件（统一 Settings 前缀防命名冲突）
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
	content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
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
	onCheckedChange: (Boolean) -> Unit,
	subtitle: String? = null,
	enabled: Boolean = true
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(enabled = enabled) { onCheckedChange(!checked) }
			.padding(horizontal = 16.dp, vertical = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween
	) {
		Column(
			modifier = Modifier.weight(1f),
			verticalArrangement = Arrangement.spacedBy(2.dp)
		) {
			Text(
				text = title,
				style = MaterialTheme.typography.bodyMedium,
				fontWeight = FontWeight.Medium,
				color = if (enabled) {
					MaterialTheme.colorScheme.onSurface
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
			if (!subtitle.isNullOrBlank()) {
				Text(
					text = subtitle,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis
				)
			}
		}
		Spacer(modifier = Modifier.width(12.dp))
		Switch(
			checked = checked,
			enabled = enabled,
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
	val positiveColor = rememberThemePositiveColor()
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
						if (statusConnected) positiveColor
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
