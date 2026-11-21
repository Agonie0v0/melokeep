/*
 * Navic / MeloKeep, a Subsonic music streaming & studio app for iOS
 * Copyright (c) 2026 paige & MeloKeep Swarm
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(
	ExperimentalMaterial3Api::class,
	ExperimentalLayoutApi::class,
	ExperimentalFoundationApi::class,
)

package io.github.agonie0v0.melokeep.ios

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.agonie0v0.melokeep.shared.MeloAiController
import io.github.agonie0v0.melokeep.shared.MeloKeepApiClient
import io.github.agonie0v0.melokeep.shared.MeloLocalLibraryController
import io.github.agonie0v0.melokeep.shared.MeloSearchController
import io.github.agonie0v0.melokeep.shared.MeloSettingsController
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import io.github.agonie0v0.melokeep.shared.MeloStudioController
import io.github.agonie0v0.melokeep.shared.MeloTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 方案 B 四大主工作台 Tab：曲库 · 探索 · 工坊 · 我的。
 */
enum class IosRootTab(val label: String, val icon: ImageVector) {
	LIBRARY("曲库", Icons.Rounded.LibraryMusic),
	DISCOVER("探索", Icons.Rounded.TravelExplore),
	STUDIO("工坊", Icons.Rounded.Build),
	ME("我的", Icons.Rounded.Person),
}

@Composable
fun MeloKeepIosApp(
	subsonicManager: IosSubsonicManager,
	audioPlayer: IosAudioPlayer,
	searchController: MeloSearchController,
	studioController: MeloStudioController,
	aiController: MeloAiController,
	settingsController: MeloSettingsController,
	settingsStore: MeloSettingsStore,
	apiClient: MeloKeepApiClient,
) {
	val scope = rememberCoroutineScope()
	val localLibraryController = remember(apiClient, scope) {
		MeloLocalLibraryController(apiClient, scope)
	}

	val prefs by subsonicManager.prefs.collectAsState()
	val libState by subsonicManager.state.collectAsState()
	val playerState by audioPlayer.state.collectAsState()
	val searchState by searchController.state.collectAsState()
	val studioState by studioController.state.collectAsState()
	val localLibraryState by localLibraryController.state.collectAsState()
	val aiState by aiController.state.collectAsState()
	val settingsState by settingsController.state.collectAsState()
	val meloConfig by settingsStore.config.collectAsState()
	val studioPreset by settingsStore.studioThemePreset.collectAsState()
	val onboardingDismissed by settingsStore.onboardingDismissed.collectAsState()

	var currentTab by remember { mutableStateOf(IosRootTab.LIBRARY) }
	var nowPlayingExpanded by remember { mutableStateOf(false) }
	var forceShowOnboarding by remember { mutableStateOf(false) }
	var showGlobalSleepSheet by remember { mutableStateOf(false) }
	var studioJumpRequest by remember { mutableStateOf<IosStudioWorkspaceTab?>(null) }

	// 4-Tab 懒加载零销毁保活槽位
	val mountedTabs = remember {
		mutableStateListOf<IosRootTab>().apply { add(IosRootTab.LIBRARY) }
	}
	LaunchedEffect(currentTab) {
		if (currentTab !in mountedTabs) {
			mountedTabs.add(currentTab)
		}
	}

	val stateHolder = rememberSaveableStateHolder()

	// 仅当双模服务均未绑定且尚未跳过引导时弹出引导页
	val needAutoOnboarding = !meloConfig.isAuthenticated &&
		libState.activeServer == null &&
		!onboardingDismissed
	val showOnboarding = forceShowOnboarding || needAutoOnboarding

	// 登录后静默预拉取工坊任务角标、本地库与 Navidrome 能力
	LaunchedEffect(meloConfig.isConfigured) {
		if (meloConfig.isConfigured) {
			studioController.refreshDownloads(silent = true)
			studioController.refreshLibraryJobs()
			studioController.checkNavidromeCapabilities()
			localLibraryController.refreshAll()
		}
	}

	val currentTrack = playerState.currentTrack
	val activeCoverUrl = currentTrack?.coverUrl ?: subsonicManager.buildCoverUrl(currentTrack?.coverArtId, size = 256)
	val seedColor = rememberExtractedSeedColor(activeCoverUrl, subsonicManager)

	val snackbarHostState = remember { SnackbarHostState() }

	val latestBannerMessage = playerState.errorMessage
		?: playerState.noticeMessage
		?: libState.errorMessage
		?: libState.noticeMessage
		?: searchState.errorMessage
		?: searchState.noticeMessage
		?: studioState.errorMessage
		?: studioState.noticeMessage
		?: localLibraryState.errorMessage
		?: localLibraryState.noticeMessage
		?: aiState.errorMessage
		?: aiState.noticeMessage
		?: settingsState.noticeMessage

	LaunchedEffect(latestBannerMessage) {
		val msg = latestBannerMessage ?: return@LaunchedEffect
		snackbarHostState.showSnackbar(msg)
		audioPlayer.clearMessages()
		subsonicManager.clearMessages()
		searchController.clearError()
		searchController.clearNotice()
		studioController.clearError()
		studioController.clearNotice()
		localLibraryController.clearError()
		localLibraryController.clearNotice()
		aiController.clearError()
		aiController.clearNotice()
		settingsController.clearNotice()
	}

	val activeDownloadIds = remember(studioState.downloads) {
		studioState.downloads
			.filter { it.status != "failed" && it.status != "cancelled" }
			.mapNotNull { it.track.id.takeIf(String::isNotBlank) }
			.toSet()
	}

	val enqueueOnlineTrack: (IosTrackItem) -> Unit = { track ->
		val rawId = track.rawId
		if (rawId.isNotBlank()) {
			scope.launch {
				val cachedTrack = searchState.tracks.firstOrNull { it.id == rawId }
					?: MeloTrack(
						id = rawId,
						title = track.title,
						artist = track.artist,
						album = track.album,
						duration = track.durationSec,
						cover = track.coverUrl,
					)
				val res = searchController.enqueueDownload(cachedTrack)
				if (res.isSuccess) {
					studioController.refreshDownloads(silent = true)
				}
			}
		}
	}

	MeloKeepIosTheme(
		studioPreset = studioPreset,
		themeMode = prefs.themeMode,
		amoledBlack = prefs.amoledBlack,
		seedColor = seedColor,
	) {
		CompositionLocalProvider(LocalIosMeloSettingsStore provides settingsStore) {
			val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
			val chromeBottomPadding = PaddingValues(
				bottom = navBottom + if (currentTrack != null) MeloBottomChromeClearance else 88.dp,
			)
			val scrollThresholdPx = with(LocalDensity.current) { 52.dp.toPx() }
			val bottomBarScrollManager = remember(scrollThresholdPx) {
				IosBottomBarScrollManager(scrollThresholdPx)
			}
			val hideOnScroll = prefs.hideBottomBarOnScroll && !showOnboarding && !nowPlayingExpanded
			LaunchedEffect(currentTab, showOnboarding, nowPlayingExpanded, prefs.hideBottomBarOnScroll) {
				bottomBarScrollManager.reset()
			}
			val bottomChromeHiddenByScroll = hideOnScroll && bottomBarScrollManager.isTriggered
			val bgColor = MaterialTheme.colorScheme.background

			Box(
				modifier = Modifier
					.fillMaxSize()
					.background(bgColor),
			) {
				// 4-Tab 零销毁保活主工作台：首次访问挂载，切 Tab 仅 GPU alpha + zIndex 切换
				Box(
					modifier = Modifier
						.fillMaxSize()
						.statusBarsPadding()
						.then(
							if (hideOnScroll) Modifier.nestedScroll(bottomBarScrollManager.connection)
							else Modifier,
						),
				) {
				IosRootTab.entries.forEach { tab ->
					val isSelected = tab == currentTab
					val shouldMount = isSelected || tab in mountedTabs
					if (shouldMount) {
						key(tab) {
							val tabAlphaState = animateFloatAsState(
								targetValue = if (isSelected) 1f else 0f,
								animationSpec = tween(durationMillis = 140),
								label = "IosRootTabAlpha_${tab.name}",
							)

							Box(
								modifier = Modifier
									.fillMaxSize()
									.zIndex(if (isSelected) 1f else 0f)
									.graphicsLayer { alpha = tabAlphaState.value }
									.background(bgColor)
									.then(
										if (isSelected) {
											Modifier
										} else {
											Modifier
												.clearAndSetSemantics { hideFromAccessibility() }
												.pointerInput(Unit) {
													awaitPointerEventScope {
														while (true) {
															awaitPointerEvent(PointerEventPass.Initial)
																.changes
																.forEach { it.consume() }
														}
													}
												}
										},
									),
							) {
								stateHolder.SaveableStateProvider(tab.name) {
									when (tab) {
										IosRootTab.LIBRARY -> IosLibraryRoute(
											subsonicManager = subsonicManager,
											audioPlayer = audioPlayer,
											studioController = studioController,
											settingsStore = settingsStore,
											apiClient = apiClient,
											onOpenSettings = { currentTab = IosRootTab.ME },
											bottomPadding = chromeBottomPadding,
											localLibraryController = localLibraryController,
											onNavigateToStudioScrape = {
												studioJumpRequest = IosStudioWorkspaceTab.SCRAPE_JOBS
												currentTab = IosRootTab.STUDIO
											},
										)

										IosRootTab.DISCOVER -> IosDiscoverRoute(
											searchController = searchController,
											aiController = aiController,
											settingsController = settingsController,
											settingsStore = settingsStore,
											studioController = studioController,
											subsonicManager = subsonicManager,
											audioPlayer = audioPlayer,
											onGoToSettings = { currentTab = IosRootTab.ME },
											bottomPadding = chromeBottomPadding,
										)

										IosRootTab.STUDIO -> IosStudioRoute(
											studioController = studioController,
											settingsController = settingsController,
											settingsStore = settingsStore,
											onGoToSettings = { currentTab = IosRootTab.ME },
											bottomPadding = chromeBottomPadding,
											audioPlayer = audioPlayer,
											subsonicManager = subsonicManager,
											jumpRequest = studioJumpRequest,
											onConsumeJumpRequest = { studioJumpRequest = null },
										)

										IosRootTab.ME -> IosHubProfileRoute(
											settingsStore = settingsStore,
											settingsController = settingsController,
											studioController = studioController,
											subsonicManager = subsonicManager,
											audioPlayer = audioPlayer,
											onOpenOnboarding = { forceShowOnboarding = true },
											onOpenSleepTimer = { showGlobalSleepSheet = true },
											bottomPadding = chromeBottomPadding,
											onOpenPhoneDownloads = {
												studioJumpRequest = IosStudioWorkspaceTab.PHONE_OFFLINE
												currentTab = IosRootTab.STUDIO
											},
										)
									}
								}
							}
						}
					}
				}
			}

			// 全局常驻悬浮底部叠加层：发丝质感灵动播放舱 + 4-Tab 导航坞
			AnimatedVisibility(
				visible = !showOnboarding,
				modifier = Modifier.align(Alignment.BottomCenter),
				enter = fadeIn(tween(180, delayMillis = 90)) + slideInVertically(tween(180, delayMillis = 90)) { it / 2 },
				exit = fadeOut(tween(90)),
			) {
				IosPersistentBottomOverlay(
					currentTrack = currentTrack,
					isPlaying = playerState.isPlaying,
					isLoading = playerState.isLoading,
					progressFlow = audioPlayer.progressFlow,
					offlineStateFlow = audioPlayer.offlineState,
					meloAuthenticated = meloConfig.isAuthenticated,
					enqueuedIds = searchState.enqueuedTrackIds,
					activeDownloadIds = activeDownloadIds,
					serverActiveTasks = studioState.activeTaskCount,
					currentTab = currentTab,
					scrollCollapsed = bottomChromeHiddenByScroll,
					onSelectTab = { currentTab = it },
					onOpenNowPlaying = { nowPlayingExpanded = true },
					onTogglePlayPause = { audioPlayer.togglePlayPause() },
					onNext = { audioPlayer.next() },
					onPrevious = { audioPlayer.previous() },
					onEnqueueIngest = enqueueOnlineTrack,
				)
			}

			// 统一双模连接引导全屏叠加层
			AnimatedVisibility(
				visible = showOnboarding,
				enter = fadeIn(tween(180)),
				exit = fadeOut(tween(120)),
			) {
				IosOnboardingOverlay(
					settingsStore = settingsStore,
					settingsController = settingsController,
					apiClient = apiClient,
					subsonicManager = subsonicManager,
					canNavigateBack = forceShowOnboarding && !needAutoOnboarding,
					onDismiss = { forceShowOnboarding = false },
				)
			}

			// 全屏沉浸播放舞台
			AnimatedVisibility(
				visible = nowPlayingExpanded && currentTrack != null,
				enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
				exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
			) {
				if (currentTrack != null) {
					val isLightPreset = MeloStudioPresets.isLightPreset(studioPreset)
					val forceDark = if (isLightPreset) false else (studioPreset != MeloStudioPresets.DYNAMIC)
					MeloKeepIosTheme(
						studioPreset = studioPreset,
						themeMode = if (isLightPreset) IosThemeMode.LIGHT else prefs.themeMode,
						amoledBlack = if (isLightPreset) false else prefs.amoledBlack,
						seedColor = seedColor,
						forceDark = forceDark,
					) {
						NowPlayingFullScreenSheet(
							track = currentTrack,
							playerState = playerState,
							isStarred = subsonicManager.isStarred(currentTrack),
							isOffline = audioPlayer.isTrackOffline(currentTrack.id),
							meloAuthenticated = meloConfig.isAuthenticated,
							enqueuedIds = searchState.enqueuedTrackIds,
							activeDownloadIds = activeDownloadIds,
							seedColor = seedColor,
							subsonicManager = subsonicManager,
							audioPlayer = audioPlayer,
							onEnqueueIngest = enqueueOnlineTrack,
							onShowNotice = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
							onDismiss = { nowPlayingExpanded = false },
						)
					}
				}
			}

			// 全局睡眠定时抽屉（供「我的」工作台触发）
			if (showGlobalSleepSheet) {
				IosSleepTimerModalSheet(
					sleepTimer = playerState.sleepTimer,
					hostNavBottom = navBottom,
					onSetSleepTimer = { min, stopAfter ->
						audioPlayer.setSleepTimer(minutes = min, stopAfterCurrent = stopAfter)
					},
					onDismiss = { showGlobalSleepSheet = false },
				)
			}

			// 底部智能避让 SnackbarHost
			val snackbarBottom = when {
				nowPlayingExpanded -> navBottom + 24.dp
				showOnboarding -> navBottom + 84.dp
				bottomChromeHiddenByScroll -> navBottom + 16.dp
				currentTrack != null -> navBottom + MeloBottomChromeClearance + 4.dp
				else -> navBottom + 90.dp
			}
			SnackbarHost(
				hostState = snackbarHostState,
				modifier = Modifier
					.align(Alignment.BottomCenter)
					.padding(horizontal = 12.dp)
					.padding(bottom = snackbarBottom),
			)
		}
		}
	}
}

private class IosBottomBarScrollManager(thresholdPx: Float) {
	var isTriggered by mutableStateOf(false)
	private var accumulator = 0f
	private var lastToggleTimeMs = 0L

	private val hideThresholdPx: Float = thresholdPx.coerceAtLeast(16f)
	private val showThresholdPx: Float = (thresholdPx * 0.35f).coerceAtLeast(12f)

	fun reset() {
		isTriggered = false
		accumulator = 0f
		lastToggleTimeMs = 0L
	}

	private fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

	private fun isWithinCooldown(cooldownMs: Long): Boolean {
		val last = lastToggleTimeMs
		if (last == 0L) return false
		val elapsed = nowMs() - last
		return elapsed in 0L until cooldownMs
	}

	val connection = object : NestedScrollConnection {
		override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
			if (!isTriggered) return Offset.Zero
			val deltaY = available.y
			if (abs(deltaY) < 1f || abs(available.x) > abs(deltaY) * 1.15f) {
				return Offset.Zero
			}
			if (source == NestedScrollSource.SideEffect && isWithinCooldown(220L)) {
				return Offset.Zero
			}
			if (deltaY < 0f) {
				accumulator = 0f
				return Offset.Zero
			}
			accumulator += deltaY
			if (accumulator >= showThresholdPx) {
				isTriggered = false
				accumulator = 0f
				lastToggleTimeMs = nowMs()
			}
			return Offset.Zero
		}

		override fun onPostScroll(
			consumed: Offset,
			available: Offset,
			source: NestedScrollSource,
		): Offset {
			if (isTriggered) return Offset.Zero
			val deltaY = consumed.y
			if (abs(deltaY) < 1f || abs(consumed.x) > abs(deltaY) * 1.15f) {
				return Offset.Zero
			}
			if (source == NestedScrollSource.SideEffect && isWithinCooldown(220L)) {
				return Offset.Zero
			}
			if (deltaY > 0f) {
				accumulator = 0f
				return Offset.Zero
			}
			accumulator += deltaY
			if (accumulator <= -hideThresholdPx) {
				isTriggered = true
				accumulator = 0f
				lastToggleTimeMs = nowMs()
			}
			return Offset.Zero
		}
	}
}

@Composable
private fun IosPersistentBottomOverlay(
	currentTrack: IosTrackItem?,
	isPlaying: Boolean,
	isLoading: Boolean,
	progressFlow: StateFlow<IosPlaybackProgress>,
	offlineStateFlow: StateFlow<IosPhoneOfflineState>,
	meloAuthenticated: Boolean,
	enqueuedIds: Set<String>,
	activeDownloadIds: Set<String>,
	serverActiveTasks: Int,
	currentTab: IosRootTab,
	scrollCollapsed: Boolean = false,
	onSelectTab: (IosRootTab) -> Unit,
	onOpenNowPlaying: () -> Unit,
	onTogglePlayPause: () -> Unit,
	onNext: () -> Unit,
	onPrevious: () -> Unit,
	onEnqueueIngest: (IosTrackItem) -> Unit,
) {
	var overlayHeightPx by remember { mutableFloatStateOf(0f) }
	val collapseProgress by animateFloatAsState(
		targetValue = if (scrollCollapsed) 1f else 0f,
		animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
		label = "IosBottomOverlayScrollCollapse",
	)
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.onSizeChanged { overlayHeightPx = it.height.toFloat() }
			.graphicsLayer {
				val travelY = if (overlayHeightPx > 0f) overlayHeightPx + 16f else 420f
				translationY = collapseProgress * travelY
				alpha = (1f - collapseProgress * 1.15f).coerceIn(0f, 1f)
			}
			.then(
				if (scrollCollapsed) {
					Modifier.clearAndSetSemantics { hideFromAccessibility() }
				} else {
					Modifier
				},
			)
			.background(
				Brush.verticalGradient(
					0.0f to Color.Transparent,
					0.25f to MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
					0.50f to MaterialTheme.colorScheme.background,
					1.0f to MaterialTheme.colorScheme.background,
				),
			)
			.navigationBarsPadding()
			.padding(horizontal = 12.dp)
			.padding(bottom = 10.dp),
		contentAlignment = Alignment.BottomCenter,
	) {
		Column(
			modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			AnimatedVisibility(
				visible = currentTrack != null,
				enter = fadeIn(tween(160)) + slideInVertically(tween(180)) { it / 2 },
				exit = fadeOut(tween(120)) + slideOutVertically(tween(140)) { it / 2 },
			) {
				if (currentTrack != null) {
					DetachedMiniPlayer(
						track = currentTrack,
						isPlaying = isPlaying,
						isLoading = isLoading,
						progressFlow = progressFlow,
						meloAuthenticated = meloAuthenticated,
						enqueuedIds = enqueuedIds,
						activeDownloadIds = activeDownloadIds,
						onClick = onOpenNowPlaying,
						onTogglePlayPause = onTogglePlayPause,
						onNext = onNext,
						onPrevious = onPrevious,
						onEnqueueIngest = { onEnqueueIngest(currentTrack) },
					)
				}
			}

			MeloIosNavDock(
				currentTab = currentTab,
				serverActiveTasks = serverActiveTasks,
				offlineStateFlow = offlineStateFlow,
				onSelectTab = onSelectTab,
			)
		}
	}
}

@Composable
private fun DetachedMiniPlayer(
	track: IosTrackItem,
	isPlaying: Boolean,
	isLoading: Boolean,
	progressFlow: StateFlow<IosPlaybackProgress>,
	meloAuthenticated: Boolean,
	enqueuedIds: Set<String>,
	activeDownloadIds: Set<String>,
	onClick: () -> Unit,
	onTogglePlayPause: () -> Unit,
	onNext: () -> Unit,
	onPrevious: () -> Unit,
	onEnqueueIngest: () -> Unit,
) {
	var totalDragX by remember { mutableFloatStateOf(0f) }

	val rawId = track.rawId
	val showIngest = track.sourceType == IosTrackSource.MELOKEEP &&
		rawId.isNotBlank() &&
		!rawId.startsWith("file:") &&
		!rawId.startsWith("asset:") &&
		meloAuthenticated
	val isEnqueued = rawId in enqueuedIds || rawId in activeDownloadIds

	val miniQualityLabel = remember(track.id, track.bitDepth, track.sampleRate, track.fileExtension, track.bitRate, track.lossless) {
		val extLower = track.fileExtension?.lowercase()
		when {
			(track.bitDepth ?: 0) >= 24 || (track.sampleRate ?: 0) >= 88200 -> "HI-RES"
			extLower in setOf("flac", "wav", "alac", "ape", "dsf") -> extLower?.uppercase() ?: "FLAC"
			track.lossless || (track.bitRate ?: 0) >= 700 -> "FLAC"
			else -> null
		}
	}
	val subtitleDisplay = remember(track.artist, track.album, miniQualityLabel) {
		listOfNotNull(
			track.artist.takeIf { it.isNotBlank() },
			miniQualityLabel,
		).joinToString(" · ").ifBlank { track.album }
	}

	val shape = RoundedCornerShape(20.dp)
	Surface(
		modifier = Modifier
			.fillMaxWidth()
			.clip(shape)
			.clickable(onClick = onClick)
			.pointerInput(track.id) {
				detectHorizontalDragGestures(
					onDragEnd = {
						if (totalDragX < -70f) onNext()
						else if (totalDragX > 70f) onPrevious()
						totalDragX = 0f
					},
					onDragCancel = { totalDragX = 0f },
					onHorizontalDrag = { _, dragAmount -> totalDragX += dragAmount },
				)
			},
		shape = shape,
		color = MaterialTheme.colorScheme.meloGlassSurface,
		border = BorderStroke(IosHairlineWidth, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
		shadowElevation = 4.dp,
	) {
		Column(modifier = Modifier.fillMaxWidth()) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 10.dp, vertical = 8.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				CoverArtBox(
					coverUrl = track.coverUrl,
					size = 44.dp,
					cornerRadius = 11.dp,
				)
				Spacer(Modifier.width(10.dp))
				Column(
					modifier = Modifier.weight(1f),
					verticalArrangement = Arrangement.spacedBy(2.dp),
				) {
					Text(
						text = track.title,
						style = MaterialTheme.typography.bodyMedium,
						fontWeight = FontWeight.SemiBold,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
					Text(
						text = subtitleDisplay,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}

				if (showIngest) {
					Spacer(Modifier.width(6.dp))
					val bg = if (isEnqueued) {
						MaterialTheme.colorScheme.meloPositive.copy(alpha = 0.12f)
					} else {
						MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
					}
					val fg = if (isEnqueued) {
						MaterialTheme.colorScheme.meloPositive
					} else {
						MaterialTheme.colorScheme.primary
					}
					Surface(
						modifier = Modifier
							.height(26.dp)
							.clip(CircleShape)
							.clickable(enabled = !isEnqueued) { onEnqueueIngest() },
						shape = CircleShape,
						color = bg,
						border = BorderStroke(IosHairlineWidth, fg.copy(alpha = 0.28f)),
					) {
						Row(
							modifier = Modifier.fillMaxHeight().padding(horizontal = 9.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(3.dp),
						) {
							Icon(
								imageVector = if (isEnqueued) Icons.Rounded.Check else Icons.Rounded.CloudDownload,
								contentDescription = null,
								tint = fg,
								modifier = Modifier.size(12.dp),
							)
							Text(
								text = if (isEnqueued) "已入库" else "入库",
								style = MaterialTheme.typography.labelSmall,
								fontWeight = FontWeight.SemiBold,
								color = fg,
								maxLines = 1,
							)
						}
					}
				}

				IconButton(onClick = onTogglePlayPause) {
					if (isLoading) {
						CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
					} else {
						Icon(
							imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
							contentDescription = if (isPlaying) "暂停" else "播放",
						)
					}
				}
				IconButton(onClick = onNext) {
					Icon(Icons.Rounded.SkipNext, contentDescription = "下一首")
				}
			}

			// 独立隔离于父重组之外的底部 2.dp 进度条
			IosMiniPlayerProgressBar(progressFlow = progressFlow)
		}
	}
}

@Composable
private fun IosMiniPlayerProgressBar(
	progressFlow: StateFlow<IosPlaybackProgress>,
	modifier: Modifier = Modifier,
) {
	val progress by progressFlow.collectAsState()
	val animatedFractionState = animateFloatAsState(
		targetValue = progress.fraction,
		animationSpec = tween(durationMillis = 180),
		label = "IosMiniPlayerProgress",
	)
	val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
	val startColor = MaterialTheme.colorScheme.primary
	val endColor = MaterialTheme.colorScheme.meloGoldText

	Spacer(
		modifier = modifier
			.fillMaxWidth()
			.height(2.dp)
			.drawWithCache {
				val progressBrush = Brush.horizontalGradient(listOf(startColor, endColor))
				onDrawBehind {
					drawRect(color = trackColor)
					val fraction = animatedFractionState.value
					if (fraction > 0f) {
						drawRect(
							brush = progressBrush,
							size = Size(width = size.width * fraction, height = size.height),
						)
					}
				}
			},
	)
}

@Composable
private fun MeloIosNavDock(
	currentTab: IosRootTab,
	serverActiveTasks: Int,
	offlineStateFlow: StateFlow<IosPhoneOfflineState>,
	onSelectTab: (IosRootTab) -> Unit,
	modifier: Modifier = Modifier,
) {
	val offlineState by offlineStateFlow.collectAsState()
	val totalStudioBadge = serverActiveTasks + offlineState.activeDownloads.size
	val dockShape = RoundedCornerShape(24.dp)

	Surface(
		modifier = modifier
			.fillMaxWidth()
			.height(62.dp),
		shape = dockShape,
		color = MaterialTheme.colorScheme.meloGlassSurface,
		border = BorderStroke(IosHairlineWidth, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
		shadowElevation = 4.dp,
	) {
		Row(
			modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 5.dp),
			horizontalArrangement = Arrangement.SpaceEvenly,
			verticalAlignment = Alignment.CenterVertically,
		) {
			IosRootTab.entries.forEach { tab ->
				val selected = tab == currentTab
				val interactionSource = remember { MutableInteractionSource() }
				val pillBg by animateColorAsState(
					targetValue = if (selected) {
						MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
					} else {
						Color.Transparent
					},
					animationSpec = tween(150),
					label = "IosDockTabBg_${tab.name}",
				)
				val fg by animateColorAsState(
					targetValue = if (selected) {
						MaterialTheme.colorScheme.primary
					} else {
						MaterialTheme.colorScheme.onSurfaceVariant
					},
					animationSpec = tween(150),
					label = "IosDockTabFg_${tab.name}",
				)

				Box(
					modifier = Modifier
						.weight(1f)
						.fillMaxHeight()
						.clip(RoundedCornerShape(18.dp))
						.background(pillBg)
						.clickable(
							interactionSource = interactionSource,
							indication = null,
							onClick = { onSelectTab(tab) },
						),
					contentAlignment = Alignment.Center,
				) {
					Column(
						horizontalAlignment = Alignment.CenterHorizontally,
						verticalArrangement = Arrangement.spacedBy(2.dp),
					) {
						Box(contentAlignment = Alignment.TopEnd) {
							Icon(
								imageVector = tab.icon,
								contentDescription = tab.label,
								tint = fg,
								modifier = Modifier.size(22.dp),
							)
							if (tab == IosRootTab.STUDIO && totalStudioBadge > 0) {
								Box(
									modifier = Modifier
										.offset(x = 10.dp, y = (-3).dp)
										.clip(CircleShape)
										.background(MaterialTheme.colorScheme.primary)
										.padding(horizontal = 4.dp, vertical = 1.dp),
								) {
									Text(
										text = if (totalStudioBadge > 99) "99+" else "$totalStudioBadge",
										style = MaterialTheme.typography.labelSmall.copy(
											fontSize = 9.sp,
											fontWeight = FontWeight.Bold,
										).tabularNums(),
										color = MaterialTheme.colorScheme.onPrimary,
										maxLines = 1,
									)
								}
							}
						}
						Text(
							text = tab.label,
							style = MaterialTheme.typography.labelSmall.copy(
								fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
							),
							color = fg,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
					}
				}
			}
		}
	}
}

@Composable
private fun NowPlayingFullScreenSheet(
	track: IosTrackItem,
	playerState: IosPlayerUiState,
	isStarred: Boolean,
	isOffline: Boolean,
	meloAuthenticated: Boolean,
	enqueuedIds: Set<String>,
	activeDownloadIds: Set<String>,
	seedColor: Color,
	subsonicManager: IosSubsonicManager,
	audioPlayer: IosAudioPlayer,
	onEnqueueIngest: (IosTrackItem) -> Unit,
	onShowNotice: (String) -> Unit,
	onDismiss: () -> Unit,
) {
	val scope = rememberCoroutineScope()
	val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
	val prefs by subsonicManager.prefs.collectAsState()
	var showLyricsMode by remember { mutableStateOf(false) }
	var showQueueSheet by remember { mutableStateOf(false) }
	var showSleepSheet by remember { mutableStateOf(false) }
	var showSpeedSheet by remember { mutableStateOf(false) }
	var showLyricsSettingsSheet by remember { mutableStateOf(false) }
	var confirmDeleteOfflineTrack by remember { mutableStateOf<IosTrackItem?>(null) }
	var moreMenuOpen by remember { mutableStateOf(false) }
	var verticalSwipeOffset by remember { mutableFloatStateOf(0f) }

	val coverUrl = track.coverUrl ?: subsonicManager.buildCoverUrl(track.coverArtId, size = 1024)
	val artworkScale by animateFloatAsState(
		targetValue = if (playerState.isPlaying) 1.0f else 0.90f,
		animationSpec = spring(
			dampingRatio = Spring.DampingRatioMediumBouncy,
			stiffness = Spring.StiffnessLow,
		),
		label = "IosArtworkBreathScale",
	)

	Surface(
		modifier = Modifier
			.fillMaxSize()
			.pointerInput(Unit) {
				detectVerticalDragGestures(
					onDragEnd = {
						if (verticalSwipeOffset > 150f) onDismiss()
						verticalSwipeOffset = 0f
					},
					onDragCancel = { verticalSwipeOffset = 0f },
					onVerticalDrag = { _, dragAmount ->
						if (dragAmount > 0f) verticalSwipeOffset += dragAmount
					},
				)
			},
		color = MaterialTheme.colorScheme.background,
	) {
		Box(modifier = Modifier.fillMaxSize()) {
			BlendBackground(
				coverUrl = coverUrl,
				seedColor = seedColor,
				isPaused = !playerState.isPlaying,
			)

			Column(
				modifier = Modifier
					.fillMaxSize()
					.statusBarsPadding()
					.navigationBarsPadding()
					.padding(horizontal = 20.dp, vertical = 10.dp),
			) {
				// 顶栏：收起 + [封面 | 歌词] 胶囊 + 歌词偏移菜单（歌词态）+ 队列
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically,
				) {
					IconButton(onClick = onDismiss) {
						Icon(
							Icons.Rounded.KeyboardArrowDown,
							contentDescription = "收起播放页",
							tint = MaterialTheme.colorScheme.onSurface,
						)
					}

					Surface(
						shape = CircleShape,
						color = MaterialTheme.colorScheme.meloGlassSurface,
						border = BorderStroke(IosHairlineWidth, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
					) {
						Row(
							modifier = Modifier.padding(3.dp),
							horizontalArrangement = Arrangement.spacedBy(2.dp),
							verticalAlignment = Alignment.CenterVertically,
						) {
							listOf(false to "封面", true to "歌词").forEach { (isLyrics, label) ->
								val selected = showLyricsMode == isLyrics
								Box(
									modifier = Modifier
										.clip(CircleShape)
										.background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
										.clickable { showLyricsMode = isLyrics }
										.padding(horizontal = 14.dp, vertical = 5.dp),
								) {
									Text(
										text = label,
										style = MaterialTheme.typography.labelMedium,
										fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
										color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
									)
								}
							}
						}
					}

					Row(
						horizontalArrangement = Arrangement.spacedBy(4.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						if (showLyricsMode) {
							IconButton(
								onClick = { showLyricsSettingsSheet = true },
								modifier = Modifier.size(36.dp),
							) {
								Icon(
									imageVector = Icons.Rounded.Tune,
									contentDescription = "歌词样式与设置",
									tint = MaterialTheme.colorScheme.onSurface,
								)
							}
							Box {
								IconButton(onClick = { moreMenuOpen = true }, modifier = Modifier.size(36.dp)) {
									Icon(
										imageVector = Icons.Rounded.MoreHoriz,
										contentDescription = "歌词设置",
										tint = MaterialTheme.colorScheme.onSurface,
									)
								}
								DropdownMenu(
									expanded = moreMenuOpen,
									onDismissRequest = { moreMenuOpen = false },
								) {
									Column(
										modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
										verticalArrangement = Arrangement.spacedBy(8.dp),
									) {
										Text(
											text = "歌词偏移",
											style = MaterialTheme.typography.labelMedium,
											color = MaterialTheme.colorScheme.onSurfaceVariant,
										)
										Row(
											horizontalArrangement = Arrangement.spacedBy(6.dp),
											verticalAlignment = Alignment.CenterVertically,
										) {
											IosSettingsStepPill(
												label = "-100",
												onClick = { audioPlayer.adjustLyricsOffset(-100) },
												modifier = Modifier.widthIn(min = 54.dp),
											)
											IosSettingsStepPill(
												label = if (playerState.lyricsOffsetMs == 0) {
													"0ms"
												} else {
													IosSettingsFormatOffsetLabel(playerState.lyricsOffsetMs)
												},
												highlighted = playerState.lyricsOffsetMs != 0,
												onClick = { audioPlayer.resetLyricsOffset() },
												modifier = Modifier.widthIn(min = 64.dp),
											)
											IosSettingsStepPill(
												label = "+100",
												onClick = { audioPlayer.adjustLyricsOffset(100) },
												modifier = Modifier.widthIn(min = 54.dp),
											)
										}
									}
								}
							}
						}

						IconButton(onClick = { showQueueSheet = true }, modifier = Modifier.size(36.dp)) {
							Icon(
								Icons.Rounded.QueueMusic,
								contentDescription = "播放队列",
								tint = MaterialTheme.colorScheme.onSurface,
							)
						}
					}
				}

				// 中部舞台：专辑封面 OR 同步歌词
				Box(
					modifier = Modifier
						.weight(1f)
						.fillMaxWidth()
						.padding(vertical = 8.dp),
					contentAlignment = Alignment.Center,
				) {
					if (showLyricsMode) {
						NowPlayingLyricsPanel(
							lyrics = playerState.lyrics,
							loading = playerState.lyricsLoading,
							progressFlow = audioPlayer.progressFlow,
							offsetMs = playerState.lyricsOffsetMs,
							prefs = prefs,
							onSeekTo = { audioPlayer.seekTo(it) },
						)
					} else {
						Box(
							modifier = Modifier
								.fillMaxWidth()
								.aspectRatio(1f)
								.padding(12.dp)
								.graphicsLayer {
									scaleX = artworkScale
									scaleY = artworkScale
								}
								.shadow(16.dp, RoundedCornerShape(22.dp))
								.clip(RoundedCornerShape(22.dp))
								.clickable { showLyricsMode = true },
						) {
							CoverArtBox(
								coverUrl = coverUrl,
								modifier = Modifier.fillMaxSize(),
								cornerRadius = 22.dp,
							)
						}
					}
				}

				// 下部控制区
				Column(
					modifier = Modifier.fillMaxWidth(),
					verticalArrangement = Arrangement.spacedBy(8.dp),
				) {
					val rawId = track.rawId
					val showIngest = track.sourceType == IosTrackSource.MELOKEEP &&
						rawId.isNotBlank() &&
						!rawId.startsWith("file:") &&
						!rawId.startsWith("asset:") &&
						meloAuthenticated
					val isEnqueued = rawId in enqueuedIds || rawId in activeDownloadIds

					// 曲名 + 歌手专辑 + 收藏/离线/入库操作
					Row(
						modifier = Modifier.fillMaxWidth(),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(8.dp),
					) {
						Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
							Text(
								text = track.title,
								style = MaterialTheme.typography.titleLarge,
								fontWeight = FontWeight.Bold,
								color = MaterialTheme.colorScheme.onBackground,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
							)
							Text(
								text = listOfNotNull(
									track.artist.takeIf { it.isNotBlank() },
									track.album.takeIf { it.isNotBlank() },
								).joinToString(" · "),
								style = MaterialTheme.typography.bodyMedium,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
							)
						}
						Row(
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(4.dp),
						) {
							if (showIngest) {
								val bg = if (isEnqueued) {
									MaterialTheme.colorScheme.meloPositive.copy(alpha = 0.14f)
								} else {
									MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
								}
								val fg = if (isEnqueued) {
									MaterialTheme.colorScheme.meloPositive
								} else {
									MaterialTheme.colorScheme.primary
								}
								Surface(
									modifier = Modifier
										.height(28.dp)
										.clip(CircleShape)
										.clickable(enabled = !isEnqueued) { onEnqueueIngest(track) },
									shape = CircleShape,
									color = bg,
									border = BorderStroke(IosHairlineWidth, fg.copy(alpha = 0.30f)),
								) {
									Row(
										modifier = Modifier.fillMaxHeight().padding(horizontal = 10.dp),
										verticalAlignment = Alignment.CenterVertically,
										horizontalArrangement = Arrangement.spacedBy(4.dp),
									) {
										Icon(
											imageVector = if (isEnqueued) Icons.Rounded.Check else Icons.Rounded.CloudDownload,
											contentDescription = null,
											tint = fg,
											modifier = Modifier.size(13.dp),
										)
										Text(
											text = if (isEnqueued) "已入库" else "入库",
											style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
											color = fg,
											maxLines = 1,
										)
									}
								}
							}
							val downloadProgress = playerState.downloadingProgress[track.id]
							when {
								downloadProgress != null -> {
									IconButton(
										onClick = {
											audioPlayer.cancelDownload(track.id)
											onShowNotice("已取消离线缓存：${track.title}")
										},
									) {
										CircularProgressIndicator(
											progress = { downloadProgress.coerceIn(0.05f, 1f) },
											modifier = Modifier.size(18.dp),
											strokeWidth = 2.dp,
										)
									}
								}

								isOffline -> {
									IconButton(onClick = { confirmDeleteOfflineTrack = track }) {
										Icon(
											imageVector = Icons.Rounded.OfflinePin,
											contentDescription = "已离线，点击删除缓存",
											tint = MaterialTheme.colorScheme.primary,
										)
									}
								}

								track.sourceType == IosTrackSource.DEVICE -> Unit

								else -> {
									IconButton(
										onClick = {
											audioPlayer.downloadTrackOffline(track)
											onShowNotice(iosLibOfflineQueueNotice(1))
										},
									) {
										Icon(
											imageVector = Icons.Rounded.CloudDownload,
											contentDescription = "离线到手机",
											tint = MaterialTheme.colorScheme.onSurfaceVariant,
										)
									}
								}
							}
							if (track.sourceType == IosTrackSource.NAVIDROME) {
								IconButton(onClick = { scope.launch { subsonicManager.toggleStar(track) } }) {
									Icon(
										imageVector = if (isStarred) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
										contentDescription = "收藏",
										tint = if (isStarred) Color(0xFFFF5C75) else MaterialTheme.colorScheme.onSurfaceVariant,
									)
								}
							}
						}
					}

					// 高频进度滑块隔离块（独立订阅 progressFlow）
					NowPlayingProgressControls(
						progressFlow = audioPlayer.progressFlow,
						formatBadge = track.displayFormatBadge,
						onSeekTo = { audioPlayer.seekTo(it) },
					)

					// 主控播按键区
					Row(
						modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
						horizontalArrangement = Arrangement.SpaceEvenly,
						verticalAlignment = Alignment.CenterVertically,
					) {
						IconButton(onClick = { audioPlayer.toggleShuffle() }) {
							Icon(
								Icons.Rounded.Shuffle,
								contentDescription = "随机播放",
								tint = if (playerState.shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
						IconButton(onClick = { audioPlayer.previous() }, modifier = Modifier.size(48.dp)) {
							Icon(Icons.Rounded.SkipPrevious, contentDescription = "上一首", tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(34.dp))
						}
						FilledIconButton(
							onClick = { audioPlayer.togglePlayPause() },
							modifier = Modifier.size(66.dp),
							shape = CircleShape,
							colors = IconButtonDefaults.filledIconButtonColors(
								containerColor = MaterialTheme.colorScheme.primary,
								contentColor = MaterialTheme.colorScheme.onPrimary,
							),
						) {
							if (playerState.isLoading) {
								CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.onPrimary)
							} else {
								Icon(
									imageVector = if (playerState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
									contentDescription = if (playerState.isPlaying) "暂停" else "播放",
									modifier = Modifier.size(34.dp),
								)
							}
						}
						IconButton(onClick = { audioPlayer.next() }, modifier = Modifier.size(48.dp)) {
							Icon(Icons.Rounded.SkipNext, contentDescription = "下一首", tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(34.dp))
						}
						IconButton(onClick = { audioPlayer.cycleRepeatMode() }) {
							val repeatIcon = if (playerState.repeatMode == IosRepeatMode.ONE) {
								Icons.Rounded.RepeatOne
							} else {
								Icons.Rounded.Repeat
							}
							val repeatTint = if (playerState.repeatMode != IosRepeatMode.OFF) {
								MaterialTheme.colorScheme.primary
							} else {
								MaterialTheme.colorScheme.onSurfaceVariant
							}
							Icon(repeatIcon, contentDescription = "循环模式", tint = repeatTint)
						}
					}

					// 底部辅助按钮行
					Row(
						modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
						horizontalArrangement = Arrangement.SpaceEvenly,
						verticalAlignment = Alignment.CenterVertically,
					) {
						TextButton(onClick = { showSpeedSheet = true }) {
							Icon(Icons.Rounded.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
							Spacer(Modifier.width(4.dp))
							Text("${playerState.playbackSpeed}x", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge.tabularNums())
						}
						TextButton(onClick = { showSleepSheet = true }) {
							val timerActive = playerState.sleepTimer.active
							val timerLabel = when {
								!timerActive -> "定时"
								playerState.sleepTimer.stopAfterCurrent -> "播完本首"
								else -> "${(playerState.sleepTimer.remainingSeconds / 60L).coerceAtLeast(1L)}m"
							}
							Icon(
								Icons.Rounded.Bedtime,
								contentDescription = null,
								tint = if (timerActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
								modifier = Modifier.size(18.dp),
							)
							Spacer(Modifier.width(4.dp))
							Text(
								text = timerLabel,
								color = if (timerActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
								style = MaterialTheme.typography.labelLarge.tabularNums(),
							)
						}
						TextButton(onClick = { showQueueSheet = true }) {
							Icon(Icons.Rounded.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
							Spacer(Modifier.width(4.dp))
							Text("队列 (${playerState.queue.size})", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge.tabularNums())
						}
					}
				}
			}
		}
	}

	if (showQueueSheet) {
		QueueManagerSheet(
			queue = playerState.queue,
			currentIndex = playerState.currentIndex,
			hostNavBottom = navBottom,
			onPlayAtIndex = { idx -> audioPlayer.skipToQueueIndex(idx) },
			onPlayNextMove = { idx ->
				val target = (playerState.currentIndex + 1).coerceAtMost(playerState.queue.lastIndex)
				val adjustedTarget = if (idx < target) target - 1 else target
				audioPlayer.moveQueueItem(idx, adjustedTarget)
			},
			onMoveUp = { idx -> if (idx > 0) audioPlayer.moveQueueItem(idx, idx - 1) },
			onMoveDown = { idx -> if (idx < playerState.queue.lastIndex) audioPlayer.moveQueueItem(idx, idx + 1) },
			onRemove = { idx -> audioPlayer.removeQueueItem(idx) },
			onClear = {
				audioPlayer.clearQueue()
				showQueueSheet = false
				onDismiss()
			},
			onDismiss = { showQueueSheet = false },
		)
	}

	if (showSleepSheet) {
		IosSleepTimerModalSheet(
			sleepTimer = playerState.sleepTimer,
			hostNavBottom = navBottom,
			onSetSleepTimer = { min, stopAfter ->
				audioPlayer.setSleepTimer(minutes = min, stopAfterCurrent = stopAfter)
			},
			onDismiss = { showSleepSheet = false },
		)
	}

	if (showSpeedSheet) {
		IosSpeedModalSheet(
			playbackSpeed = playerState.playbackSpeed,
			hostNavBottom = navBottom,
			onSetSpeed = { audioPlayer.setPlaybackSpeed(it) },
			onDismiss = { showSpeedSheet = false },
		)
	}

	IosLyricsAndStageSheet(
		audioPlayer = audioPlayer,
		subsonicManager = subsonicManager,
		presented = showLyricsSettingsSheet,
		onDismissRequest = { showLyricsSettingsSheet = false },
	)

	confirmDeleteOfflineTrack?.let { target ->
		AlertDialog(
			onDismissRequest = { confirmDeleteOfflineTrack = null },
			title = { Text("移除离线缓存") },
			text = { Text("确定删除「${target.title}」的手机离线缓存文件吗？") },
			confirmButton = {
				TextButton(
					onClick = {
						audioPlayer.deleteOfflineTrack(target.id)
						confirmDeleteOfflineTrack = null
					},
					colors = ButtonDefaults.textButtonColors(
						contentColor = MaterialTheme.colorScheme.error,
					),
				) {
					Text("移除")
				}
			},
			dismissButton = {
				TextButton(onClick = { confirmDeleteOfflineTrack = null }) {
					Text("取消")
				}
			},
		)
	}
}

@Composable
private fun NowPlayingProgressControls(
	progressFlow: StateFlow<IosPlaybackProgress>,
	formatBadge: String,
	onSeekTo: (Long) -> Unit,
) {
	val progress by progressFlow.collectAsState()
	var isDraggingSlider by remember { mutableStateOf(false) }
	var sliderPositionMs by remember { mutableFloatStateOf(0f) }

	val duration = progress.durationMs.coerceAtLeast(1L).toFloat()
	val displayPos = if (isDraggingSlider) {
		sliderPositionMs
	} else {
		progress.positionMs.toFloat().coerceIn(0f, duration)
	}

	Column(modifier = Modifier.fillMaxWidth()) {
		Slider(
			value = displayPos,
			onValueChange = {
				isDraggingSlider = true
				sliderPositionMs = it
			},
			onValueChangeFinished = {
				isDraggingSlider = false
				onSeekTo(sliderPositionMs.roundToLong())
			},
			valueRange = 0f..duration,
			colors = SliderDefaults.colors(
				thumbColor = MaterialTheme.colorScheme.primary,
				activeTrackColor = MaterialTheme.colorScheme.primary,
				inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
			),
		)
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = formatDurationMs(displayPos.roundToLong()),
				style = MaterialTheme.typography.labelSmall.tabularNums(),
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Text(
				text = formatBadge,
				style = MaterialTheme.typography.labelSmall.tabularNums(),
				color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = formatDurationMs(progress.durationMs),
				style = MaterialTheme.typography.labelSmall.tabularNums(),
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

/**
 * 纯净同步歌词面板（独立订阅 progressFlow 隔离重组）。
 */
@Composable
private fun NowPlayingLyricsPanel(
	lyrics: IosLyricsResult,
	loading: Boolean,
	progressFlow: StateFlow<IosPlaybackProgress>,
	offsetMs: Int,
	prefs: IosPlaybackPrefs,
	onSeekTo: (Long) -> Unit,
) {
	val progress by progressFlow.collectAsState()
	val listState = rememberLazyListState()
	val density = LocalDensity.current
	var isScrollCooldown by remember { mutableStateOf(false) }
	LaunchedEffect(listState.isScrollInProgress) {
		if (listState.isScrollInProgress) {
			isScrollCooldown = true
		} else if (isScrollCooldown) {
			delay(350)
			isScrollCooldown = false
		}
	}

	val clampedFontSp = prefs.lyricsFontSizeSp.coerceIn(16f, 34f)
	val clampedLineSpacingDp = prefs.lyricsLineSpacingDp.coerceIn(4f, 36f)
	val inactiveFontSp = (clampedFontSp - 4f).coerceAtLeast(13f)
	val clampedInactiveAlpha = prefs.lyricsInactiveAlpha.coerceIn(0.2f, 0.85f)
	val resolvedAlign = if (prefs.lyricsAlign == "start") TextAlign.Start else TextAlign.Center
	val customActiveColor = remember(prefs.lyricsActiveColorHex) {
		parseIosLyricsHexColorOrNull(prefs.lyricsActiveColorHex)
	}
	val activeColor = customActiveColor ?: MaterialTheme.colorScheme.onBackground
	val inactiveColor = MaterialTheme.colorScheme.onBackground.copy(alpha = clampedInactiveAlpha)

	val activeIndex = if (lyrics.synced) {
		val effectivePosMs = progress.positionMs - offsetMs
		lyrics.lines.indexOfLast { line ->
			line.timeMs != null && effectivePosMs + 200L >= line.timeMs
		}
	} else {
		-1
	}

	LaunchedEffect(activeIndex) {
		if (!lyrics.synced || activeIndex < 0) return@LaunchedEffect
		val layoutInfo = listState.layoutInfo
		val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIndex }
		if (itemInfo != null) {
			val itemCenter = itemInfo.offset + itemInfo.size / 2
			val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
			val distance = itemCenter - viewportCenter
			val threshold = with(density) { 20.dp.toPx() }
			if (abs(distance) > threshold) {
				listState.animateScrollBy(distance.toFloat())
			}
		} else {
			listState.animateScrollToItem(activeIndex, scrollOffset = -200)
		}
	}

	when {
		loading -> {
			Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
			}
		}

		lyrics.lines.isEmpty() -> {
			Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				IosQuietEmptyState(
					message = "暂无歌词",
					icon = Icons.Rounded.MusicNote,
				)
			}
		}

		else -> {
			LazyColumn(
				state = listState,
				modifier = Modifier.fillMaxSize(),
				contentPadding = PaddingValues(top = 24.dp, bottom = 72.dp, start = 8.dp, end = 8.dp),
				verticalArrangement = Arrangement.spacedBy(clampedLineSpacingDp.dp),
			) {
				itemsIndexed(lyrics.lines, key = { idx, line -> "${idx}_${line.timeMs ?: -1L}" }) { idx, line ->
					val isActive = !lyrics.synced || idx == activeIndex
					val targetTimeMs = line.timeMs
					val latestOnSeekTo by rememberUpdatedState(onSeekTo)
					var textLayoutResult by remember(line.text, clampedFontSp, resolvedAlign) {
						mutableStateOf<TextLayoutResult?>(null)
					}
					Text(
						text = line.text,
						fontSize = if (isActive) clampedFontSp.sp else inactiveFontSp.sp,
						lineHeight = if (isActive) (clampedFontSp * 1.36f).sp else (inactiveFontSp * 1.36f).sp,
						fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
						color = if (isActive) activeColor else inactiveColor,
						textAlign = resolvedAlign,
						onTextLayout = { textLayoutResult = it },
						modifier = Modifier
							.fillMaxWidth()
							.padding(vertical = 4.dp, horizontal = 8.dp)
							.pointerInput(targetTimeMs, offsetMs) {
								if (targetTimeMs == null) return@pointerInput
								val slopPx = 8.dp.toPx()
								detectTapGestures { offset ->
									if (listState.isScrollInProgress || isScrollCooldown) {
										return@detectTapGestures
									}
									val layout = textLayoutResult ?: return@detectTapGestures
									for (lineIndex in 0 until layout.lineCount) {
										val left = layout.getLineLeft(lineIndex) - slopPx
										val right = layout.getLineRight(lineIndex) + slopPx
										val top = layout.getLineTop(lineIndex) - slopPx
										val bottom = layout.getLineBottom(lineIndex) + slopPx
										if (offset.x in left..right && offset.y in top..bottom) {
											latestOnSeekTo((targetTimeMs + offsetMs).coerceAtLeast(0L))
											break
										}
									}
								}
							},
					)
				}
			}
		}
	}
}

@Composable
private fun IosSleepTimerModalSheet(
	sleepTimer: IosSleepTimerState,
	hostNavBottom: Dp,
	onSetSleepTimer: (minutes: Int, stopAfterCurrent: Boolean) -> Unit,
	onDismiss: () -> Unit,
) {
	ModalBottomSheet(
		onDismissRequest = onDismiss,
		containerColor = MaterialTheme.colorScheme.background,
		contentColor = MaterialTheme.colorScheme.onSurface,
		dragHandle = null,
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(bottom = maxOf(hostNavBottom, 16.dp) + 16.dp),
		) {
			IosCleanTopBar(title = "睡眠定时", onBack = onDismiss)
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp, vertical = 8.dp),
				verticalArrangement = Arrangement.spacedBy(12.dp),
			) {
				IosGroupedCard {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(12.dp),
						horizontalArrangement = Arrangement.spacedBy(6.dp),
					) {
						listOf(15, 30, 45, 60).forEach { min ->
							IosSettingsChoicePill(
								label = "${min} 分钟",
								selected = false,
								onClick = {
									onSetSleepTimer(min, false)
									onDismiss()
								},
								modifier = Modifier.weight(1f),
							)
						}
					}
				}
				Button(
					onClick = {
						onSetSleepTimer(0, true)
						onDismiss()
					},
					shape = RoundedCornerShape(12.dp),
					modifier = Modifier.fillMaxWidth(),
				) {
					Text("播完当前歌曲后停止")
				}
				if (sleepTimer.active) {
					OutlinedButton(
						onClick = {
							onSetSleepTimer(0, false)
							onDismiss()
						},
						shape = RoundedCornerShape(12.dp),
						border = BorderStroke(IosHairlineWidth, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
						modifier = Modifier.fillMaxWidth(),
					) {
						Text("关闭睡眠定时")
					}
				}
			}
		}
	}
}

@Composable
private fun IosSpeedModalSheet(
	playbackSpeed: Float,
	hostNavBottom: Dp,
	onSetSpeed: (Float) -> Unit,
	onDismiss: () -> Unit,
) {
	ModalBottomSheet(
		onDismissRequest = onDismiss,
		containerColor = MaterialTheme.colorScheme.background,
		contentColor = MaterialTheme.colorScheme.onSurface,
		dragHandle = null,
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(bottom = maxOf(hostNavBottom, 16.dp) + 16.dp),
		) {
			IosCleanTopBar(title = "播放倍速", onBack = onDismiss)
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp, vertical = 8.dp),
				verticalArrangement = Arrangement.spacedBy(12.dp),
			) {
				IosGroupedCard {
					Column(
						modifier = Modifier.padding(16.dp),
						verticalArrangement = Arrangement.spacedBy(10.dp),
					) {
						Row(
							modifier = Modifier.fillMaxWidth(),
							horizontalArrangement = Arrangement.SpaceBetween,
							verticalAlignment = Alignment.CenterVertically,
						) {
							Text("当前倍速", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
							Text(
								text = "${playbackSpeed}x",
								style = MaterialTheme.typography.labelLarge.tabularNums(),
								fontWeight = FontWeight.SemiBold,
								color = MaterialTheme.colorScheme.primary,
							)
						}
						Slider(
							value = playbackSpeed,
							onValueChange = { raw ->
								val snapped = (raw * 20f).roundToInt() / 20f
								onSetSpeed(snapped)
							},
							valueRange = 0.5f..2.0f,
						)
						FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
							listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
								FilterChip(
									selected = abs(playbackSpeed - spd) < 0.01f,
									onClick = { onSetSpeed(spd) },
									shape = CircleShape,
									label = { Text("${spd}x") },
								)
							}
						}
					}
				}
			}
		}
	}
}

@Composable
private fun QueueManagerSheet(
	queue: List<IosTrackItem>,
	currentIndex: Int,
	hostNavBottom: Dp,
	onPlayAtIndex: (Int) -> Unit,
	onPlayNextMove: (Int) -> Unit,
	onMoveUp: (Int) -> Unit,
	onMoveDown: (Int) -> Unit,
	onRemove: (Int) -> Unit,
	onClear: () -> Unit,
	onDismiss: () -> Unit,
) {
	var query by remember { mutableStateOf("") }
	val indexedMatches = remember(queue, query) {
		val q = query.trim().lowercase()
		queue.mapIndexed { idx, track -> idx to track }.filter { (_, t) ->
			q.isEmpty() ||
				t.title.lowercase().contains(q) ||
				t.artist.lowercase().contains(q) ||
				t.album.lowercase().contains(q)
		}
	}

	ModalBottomSheet(
		onDismissRequest = onDismiss,
		containerColor = MaterialTheme.colorScheme.background,
		contentColor = MaterialTheme.colorScheme.onSurface,
		dragHandle = null,
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(bottom = maxOf(hostNavBottom, 16.dp) + 16.dp),
		) {
			IosCleanTopBar(
				title = "播放队列 (${queue.size})",
				onBack = onDismiss,
				trailing = {
					if (queue.isNotEmpty()) {
						Text(
							text = "清空",
							style = MaterialTheme.typography.labelLarge,
							color = MaterialTheme.colorScheme.error,
							modifier = Modifier
								.clip(RoundedCornerShape(6.dp))
								.clickable(onClick = onClear)
								.padding(horizontal = 8.dp, vertical = 4.dp),
						)
					}
				},
			)

			Column(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp),
				verticalArrangement = Arrangement.spacedBy(10.dp),
			) {
				OutlinedTextField(
					value = query,
					onValueChange = { query = it },
					modifier = Modifier.fillMaxWidth(),
					placeholder = { Text("检索队列中的歌名 / 歌手 / 专辑") },
					leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
					trailingIcon = {
						if (query.isNotEmpty()) {
							IconButton(onClick = { query = "" }) {
								Icon(Icons.Rounded.Close, contentDescription = "清除", modifier = Modifier.size(18.dp))
							}
						}
					},
					singleLine = true,
					shape = RoundedCornerShape(12.dp),
				)

				if (indexedMatches.isEmpty()) {
					IosQuietEmptyState(
						message = if (queue.isEmpty()) "队列为空" else "无匹配曲目",
						icon = Icons.Rounded.QueueMusic,
					)
				} else {
					LazyColumn(
						modifier = Modifier.fillMaxWidth().height(360.dp),
						verticalArrangement = Arrangement.spacedBy(4.dp),
					) {
						itemsIndexed(
							indexedMatches,
							key = { _, pair -> "${pair.first}_${pair.second.id}" },
							contentType = { _, _ -> "queue-item" },
						) { rowPos, (origIdx, item) ->
							val isCurrent = origIdx == currentIndex
							NavicSegmentedRow(
								title = item.title,
								subtitle = "${item.artist} · ${item.album}",
								shape = segmentedShapeFor(rowPos, indexedMatches.size),
								selected = isCurrent,
								leading = { CoverArtBox(coverUrl = item.coverUrl, size = 40.dp, cornerRadius = 8.dp) },
								trailing = {
									Row(verticalAlignment = Alignment.CenterVertically) {
										if (!isCurrent && origIdx != currentIndex + 1) {
											IconButton(onClick = { onPlayNextMove(origIdx) }) {
												Icon(Icons.Rounded.PlaylistPlay, contentDescription = "下一首播放")
											}
										}
										if (query.isBlank()) {
											IconButton(onClick = { onMoveUp(origIdx) }, enabled = origIdx > 0) {
												Icon(Icons.Rounded.ArrowUpward, contentDescription = "上移", modifier = Modifier.size(18.dp))
											}
											IconButton(onClick = { onMoveDown(origIdx) }, enabled = origIdx < queue.lastIndex) {
												Icon(Icons.Rounded.ArrowDownward, contentDescription = "下移", modifier = Modifier.size(18.dp))
											}
										}
										IconButton(onClick = { onRemove(origIdx) }) {
											Icon(Icons.Rounded.Close, contentDescription = "移除", modifier = Modifier.size(18.dp))
										}
									}
								},
								onClick = { onPlayAtIndex(origIdx) },
							)
						}
					}
				}
			}
		}
	}
}
