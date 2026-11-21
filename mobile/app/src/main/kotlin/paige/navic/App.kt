/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic

import android.annotation.SuppressLint
import android.app.Activity
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy.Companion.detailPane
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy.Companion.listPane
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.NavDisplay.popTransitionSpec
import androidx.navigation3.ui.NavDisplay.predictivePopTransitionSpec
import androidx.navigation3.ui.NavDisplay.transitionSpec
import androidx.savedstate.serialization.SavedStateConfiguration
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import io.github.agonie0v0.melokeep.shared.MeloStudioController
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.di.LocalBottomBarScrollManager
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalSharedTransitionScope
import paige.navic.di.LocalSizeClass
import paige.navic.di.LocalSnackBarState
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.manager.session.SessionManager
import paige.navic.domain.manager.session.SessionState
import paige.navic.domain.manager.ui.BottomBarScrollManager
import paige.navic.domain.manager.ui.SnackBarManager
import paige.navic.domain.model.settings.BottomBarCollapseMode
import paige.navic.domain.model.settings.BottomBarVisibilityMode
import paige.navic.domain.model.settings.ExplicitContentPlayback
import paige.navic.generated.BuildInfo
import paige.navic.melokeep.MeloAiScreen
import paige.navic.melokeep.MeloArtistDetailScreen
import paige.navic.melokeep.MeloAudioEngineScreen
import paige.navic.melokeep.MeloCollectionDetailScreen
import paige.navic.melokeep.MeloDailyMixScreen
import paige.navic.melokeep.MeloDiscoverScreen
import paige.navic.melokeep.MeloEqualizerScreen
import paige.navic.melokeep.MeloLikedSongsScreen
import paige.navic.melokeep.MeloLyricsAndStageScreen
import paige.navic.melokeep.MeloNavidromeConnectionScreen
import paige.navic.melokeep.MeloOnboardingScreen
import paige.navic.melokeep.MeloPhoneDownloadCenterScreen
import paige.navic.melokeep.MeloSettingsScreen
import paige.navic.melokeep.MeloStorageAndAboutScreen
import paige.navic.melokeep.MeloStudioScreen
import paige.navic.melokeep.MeloStudioSettingsHomeScreen
import paige.navic.playback.MediaPlayer
import paige.navic.ui.component.layout.MeloPersistentBottomOverlay
import paige.navic.ui.component.layout.MeloPersistentRootHost
import paige.navic.ui.component.layout.MeloPersistentSideRail
import paige.navic.ui.component.layout.MeloRootTab
import paige.navic.ui.component.sheet.ChangelogSheet
import paige.navic.ui.component.snackbar.NavicSnackBar
import paige.navic.ui.component.snackbar.SwipeableSnackBarHost
import paige.navic.ui.navigation.BottomSheetSceneStrategy
import paige.navic.ui.navigation.NowPlayingSceneStrategy
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screen.album.AlbumListScreen
import paige.navic.ui.screen.artist.ArtistListScreen
import paige.navic.ui.screen.chat.ChatScreen
import paige.navic.ui.screen.genre.GenreDetailScreen
import paige.navic.ui.screen.genre.GenreListScreen
import paige.navic.ui.screen.imageView.ImageViewScreen
import paige.navic.ui.screen.lyrics.LyricsScreen
import paige.navic.ui.screen.nowPlaying.NowPlayingScreen
import paige.navic.ui.screen.nowPlaying.PlaybackSpeedScreen
import paige.navic.ui.screen.playlist.PlaylistListScreen
import paige.navic.ui.screen.queue.QueueScreen
import paige.navic.ui.screen.radio.RadioListScreen
import paige.navic.ui.screen.search.SearchScreen
import paige.navic.ui.screen.settings.AudioEffectsScreen
import paige.navic.ui.screen.settings.BottomBarScreen
import paige.navic.ui.screen.settings.FontsScreen
import paige.navic.ui.screen.settings.SettingsAlternativeAddressesScreen
import paige.navic.ui.screen.settings.SettingsAppIconScreen
import paige.navic.ui.screen.settings.SettingsAppearanceScreen
import paige.navic.ui.screen.settings.SettingsCustomHeadersScreen
import paige.navic.ui.screen.settings.SettingsDeveloperScreen
import paige.navic.ui.screen.settings.SettingsDownloadQualityScreen
import paige.navic.ui.screen.settings.SettingsLogsScreen
import paige.navic.ui.screen.settings.SettingsStreamingQualityScreen
import paige.navic.ui.screen.settings.SettingsThemesScreen
import paige.navic.ui.screen.share.ShareListScreen
import paige.navic.ui.screen.song.SongDetailScreen
import paige.navic.ui.screen.song.SongDetailSheet
import paige.navic.ui.screen.song.SongListScreen
import paige.navic.ui.screen.stats.StatisticsScreen
import paige.navic.ui.theme.MeloBottomChromeClearance
import paige.navic.ui.theme.NavicTheme
import paige.navic.ui.viewmodel.RootViewModel

@OptIn(ExperimentalSerializationApi::class)
private val config = SavedStateConfiguration {
	serializersModule = SerializersModule {
		polymorphic(NavKey::class) {
			subclassesOfSealed<Screen>()
		}
	}
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun App() {
	val density = LocalDensity.current
	val activity = LocalActivity.current!!
	val context = LocalContext.current
	val resources = LocalResources.current

	val sizeClass = calculateWindowSizeClass(activity)
	val sessionManager = koinInject<SessionManager>()
	val preferenceManager = koinInject<PreferenceManager>()
	val meloSettingsStore = koinInject<MeloSettingsStore>()
	val studioController = koinInject<MeloStudioController>()
	val player = koinInject<MediaPlayer>()
	val rootViewModel = koinViewModel<RootViewModel>()

	val sessionState by sessionManager.sessionState.collectAsStateWithLifecycle()
	val meloConfig by meloSettingsStore.config.collectAsStateWithLifecycle()
	val onboardingDismissed by meloSettingsStore.onboardingDismissed.collectAsStateWithLifecycle()
	val hasCurrentSong by remember(player) {
		player.uiState
			.map { it.currentSong != null }
			.distinctUntilChanged()
	}.collectAsStateWithLifecycle(initialValue = player.uiState.value.currentSong != null)

	LaunchedEffect(meloConfig.isAuthenticated) {
		if (meloConfig.isAuthenticated) {
			studioController.refreshDownloads(silent = true)
			studioController.refreshLibraryJobs()
		}
	}

	// 打破强制 Subsonic 登录锁门：任一服务已就绪或用户已跳过引导即可直达主工作台
	val initialRoute = remember {
		val meloReady = meloConfig.isAuthenticated
		val subsonicReady = sessionState !is SessionState.Unauthenticated
		if (meloReady || subsonicReady || onboardingDismissed) {
			Screen.Library()
		} else {
			Screen.MeloOnboarding
		}
	}
	val backStack = rememberNavBackStack(config, initialRoute)

	val activeRootTabState = rememberSaveable { mutableStateOf(MeloRootTab.LIBRARY) }
	val rootStateHolder = rememberSaveableStateHolder()

	val snackBarState = remember { SnackbarHostState() }
	val snackBarManager = koinInject<SnackBarManager>()

	LaunchedEffect(Unit) {
		snackBarManager.events.collectLatest { event ->
			snackBarState.currentSnackbarData?.dismiss()
			snackBarState.showSnackbar(
				resources.getString(event.resource, *event.args.toTypedArray())
			)
		}
	}

	val scrollManager = remember {
		BottomBarScrollManager(with(density) { 50.dp.toPx() })
	}

	var appStarted by rememberSaveable { mutableStateOf(false) }

	LaunchedEffect(Unit) {
		if (!appStarted) {
			appStarted = true
			if (preferenceManager.explicitContentPlayback == ExplicitContentPlayback.SkipForThisSession) {
				preferenceManager.explicitContentPlayback = ExplicitContentPlayback.Allowed
			}
		}
	}

	var lastBackPressMs by remember { mutableLongStateOf(0L) }
	var exitConfirmToast by remember { mutableStateOf<Toast?>(null) }
	DisposableEffect(Unit) {
		onDispose {
			exitConfirmToast?.cancel()
			exitConfirmToast = null
		}
	}

	val topEntry = backStack.lastOrNull()
	val collapseMode = preferenceManager.bottomBarCollapseMode
	LaunchedEffect(collapseMode) {
		if (collapseMode != BottomBarCollapseMode.OnScroll) {
			scrollManager.reset()
		}
	}
	LaunchedEffect(topEntry, activeRootTabState.value) {
		scrollManager.reset()
		lastBackPressMs = 0L
	}

	val hideOnScroll = collapseMode == BottomBarCollapseMode.OnScroll && scrollManager.isTriggered
	val isAtRootShell = topEntry is Screen.Library
	val isCompact = sizeClass.widthSizeClass < WindowWidthSizeClass.Medium
	val showSideRail = !isCompact &&
		Screen.Login !in backStack &&
		Screen.MeloOnboarding !in backStack
	// 路由层逻辑可见性：与滚动折叠解耦，底栏/播放舱滚动时仅做 GPU 位移而不销毁组件
	val showNavDock = isAtRootShell && isCompact
	val isOverlayOrFullscreen = topEntry is Screen.MeloOnboarding ||
		topEntry is Screen.Login ||
		topEntry is Screen.NowPlaying ||
		topEntry is Screen.Lyrics ||
		topEntry is Screen.ImageView ||
		topEntry is Screen.Queue ||
		topEntry is Screen.PlaybackSpeed ||
		topEntry is Screen.SongDetailSheet ||
		topEntry is Screen.Settings ||
		topEntry is Screen.PhoneDownloads ||
		topEntry is Screen.DailyMix
	val showPlayerDeck = !isOverlayOrFullscreen &&
		(isAtRootShell || preferenceManager.bottomBarVisibilityMode == BottomBarVisibilityMode.AllScreens) &&
		(hasCurrentSong || !preferenceManager.hideIfIdle)

	val selectRootTab: (MeloRootTab) -> Unit = remember(backStack, rootViewModel, scrollManager) {
		{ targetTab ->
			scrollManager.reset()
			val currentlyAtRoot = backStack.lastOrNull() is Screen.Library
			if (!currentlyAtRoot) {
				while (backStack.size > 1) {
					backStack.removeLastOrNull()
				}
				if (backStack.lastOrNull() !is Screen.Library) {
					backStack.clear()
					backStack.add(Screen.Library())
				}
			}
			if (activeRootTabState.value == targetTab && currentlyAtRoot) {
				rootViewModel.requestScrollToTop()
			} else {
				activeRootTabState.value = targetTab
			}
		}
	}

	SharedTransitionLayout {
		CompositionLocalProvider(
			LocalNavStack provides backStack,
			LocalSnackBarState provides snackBarState,
			LocalSharedTransitionScope provides this@SharedTransitionLayout,
			LocalBottomBarScrollManager provides scrollManager,
			LocalSizeClass provides sizeClass
		) {
			NavicTheme {
				Row(modifier = Modifier.fillMaxSize()) {
					if (showSideRail) {
						MeloPersistentSideRail(
							activeTabState = activeRootTabState,
							isAtRoot = isAtRootShell,
							onSelectTab = selectRootTab
						)
					}
					@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
					Scaffold(
						modifier = Modifier
							.weight(1f)
							.nestedScroll(scrollManager.connection),
						snackbarHost = {
							val hasActiveSnackbar = snackBarState.currentSnackbarData != null
							val targetBottomPadding = when {
								hasActiveSnackbar && hideOnScroll -> 8.dp
								showNavDock && showPlayerDeck -> MeloBottomChromeClearance
								showNavDock -> 84.dp
								showPlayerDeck -> 92.dp
								topEntry is Screen.MeloOnboarding -> 88.dp
								else -> 8.dp
							}
							val snackbarBottomPadding by animateDpAsState(
								targetValue = targetBottomPadding,
								animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
								label = "SnackbarBottomPadding"
							)
							Box(
								modifier = Modifier
									.windowInsetsPadding(WindowInsets.navigationBars)
									.padding(bottom = snackbarBottomPadding)
							) {
								SwipeableSnackBarHost(hostState = snackBarState) { snackBarData, offset ->
									NavicSnackBar(
										snackBarData = snackBarData,
										modifier = Modifier.offset(x = offset)
									)
								}
							}
						}
					) { _ ->
						Box(modifier = Modifier.fillMaxSize()) {
							// 注册在 NavDisplay 之前，确保内层子页面（如曲库多选/本地子详情）BackHandler 自动优先拦截；
							// 仅当真正处于一级主界面且无内层拦截时，启用 2 秒二次确认防误退
							BackHandler(enabled = isAtRootShell && backStack.size == 1) {
								val now = SystemClock.elapsedRealtime()
								if (lastBackPressMs > 0L && now - lastBackPressMs <= 2000L) {
									exitConfirmToast?.cancel()
									exitConfirmToast = null
									lastBackPressMs = 0L
									((context as? Activity) ?: activity).finish()
								} else {
									lastBackPressMs = now
									exitConfirmToast?.cancel()
									exitConfirmToast = Toast.makeText(
										context,
										"再滑一次退出 MeloKeep",
										Toast.LENGTH_SHORT
									).also { it.show() }
								}
							}

							NavDisplay(
								modifier = Modifier
									.fillMaxSize()
									.then(
										if (showSideRail) {
											Modifier.consumeWindowInsets(
												WindowInsets.safeDrawing.only(WindowInsetsSides.Start)
											)
										} else {
											Modifier
										}
									),
								backStack = backStack,
								sceneStrategies = listOf(
									remember { NowPlayingSceneStrategy() },
									remember { BottomSheetSceneStrategy() },
									rememberListDetailSceneStrategy()
								),
								entryDecorators = listOf(
									rememberSaveableStateHolderNavEntryDecorator(),
									rememberViewModelStoreNavEntryDecorator()
								),
								onBack = {
									if (backStack.size >= 2) {
										backStack.removeLastOrNull()
									}
								},
								entryProvider = entryProvider(
									backStack = backStack,
									activeRootTabState = activeRootTabState,
									onSelectRootTab = selectRootTab,
									rootStateHolder = rootStateHolder,
									onDismissOnboarding = {
										meloSettingsStore.setOnboardingDismissed(true)
										if (backStack.size > 1) {
											backStack.removeLastOrNull()
										} else {
											backStack.clear()
											backStack.add(Screen.Library())
										}
									}
								),
								sharedTransitionScope = this@SharedTransitionLayout,
								transitionSpec = {
									(slideInHorizontally(
										animationSpec = tween(durationMillis = 200, easing = EaseOutCubic),
										initialOffsetX = { fullWidth -> (fullWidth * 0.18f).toInt() }
									) + fadeIn(
										animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)
									)) togetherWith (slideOutHorizontally(
										animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
										targetOffsetX = { fullWidth -> -(fullWidth * 0.08f).toInt() }
									) + fadeOut(
										animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing)
									))
								},
								popTransitionSpec = {
									(slideInHorizontally(
										animationSpec = tween(durationMillis = 200, easing = EaseOutCubic),
										initialOffsetX = { fullWidth -> -(fullWidth * 0.12f).toInt() }
									) + fadeIn(
										animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
									)) togetherWith (slideOutHorizontally(
										animationSpec = tween(durationMillis = 200, easing = EaseOutCubic),
										targetOffsetX = { fullWidth -> (fullWidth * 0.2f).toInt() }
									) + fadeOut(
										animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing)
									))
								},
								predictivePopTransitionSpec = {
									if (preferenceManager.enablePredictiveBackAnimations) {
										slideInHorizontally(
											animationSpec = tween(durationMillis = 200, easing = EaseOutCubic),
											initialOffsetX = { -it }
										) togetherWith slideOutHorizontally(
											animationSpec = tween(durationMillis = 200, easing = EaseOutCubic),
											targetOffsetX = { it }
										)
									} else {
										ContentTransform(EnterTransition.None, ExitTransition.None)
									}
								}
							)

							MeloPersistentBottomOverlay(
								modifier = Modifier.align(Alignment.BottomCenter),
								showNavDock = showNavDock,
								showPlayerDeck = showPlayerDeck,
								scrollCollapsed = hideOnScroll,
								activeTabState = activeRootTabState,
								onSelectTab = selectRootTab
							)
						}
					}
				}
				if (!BuildInfo.FDROID) {
					ChangelogSheet()
				}
			}
		}
	}
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3Api::class)
private fun entryProvider(
	backStack: NavBackStack<NavKey>,
	activeRootTabState: State<MeloRootTab>,
	onSelectRootTab: (MeloRootTab) -> Unit,
	rootStateHolder: SaveableStateHolder,
	onDismissOnboarding: () -> Unit
): (NavKey) -> (NavEntry<NavKey>) {
	val fadeSpec = ContentTransform(
		fadeIn(tween(160, easing = FastOutSlowInEasing)),
		fadeOut(tween(120, easing = FastOutSlowInEasing))
	)

	val navtabMetadata = if (backStack.size == 1)
		listPane("root")
			.plus(transitionSpec { fadeSpec })
			.plus(popTransitionSpec { fadeSpec })
			.plus(predictivePopTransitionSpec { fadeSpec })
	else listPane("root")
	val onboardingMetadata = transitionSpec {
		ContentTransform(fadeIn(tween(180)), fadeOut(tween(80)))
	}
		.plus(popTransitionSpec { fadeSpec })
		.plus(predictivePopTransitionSpec { fadeSpec })
	val imageViewMetadata = transitionSpec { ContentTransform(fadeIn(), ExitTransition.None) }
		.plus(popTransitionSpec { ContentTransform(EnterTransition.None, fadeOut()) })
		.plus(predictivePopTransitionSpec { ContentTransform(EnterTransition.None, fadeOut()) })

	return androidx.navigation3.runtime.entryProvider {
		// 持久化 4-Tab 单壳层主工作台
		entry<Screen.Library>(metadata = navtabMetadata) {
			MeloPersistentRootHost(
				activeTabState = activeRootTabState,
				onSelectTab = onSelectRootTab,
				stateHolder = rootStateHolder
			)
		}

		// 双模连接引导
		entry<Screen.MeloOnboarding>(metadata = onboardingMetadata) {
			MeloOnboardingScreen(onFinished = onDismissOnboarding)
		}

		// 嵌套或直达的 MeloKeep 独立页面入口
		entry<Screen.MeloDiscover>(metadata = navtabMetadata) { key ->
			MeloDiscoverScreen(
				nested = key.nested,
				onOpenSettings = { backStack.add(Screen.Settings.MeloKeep) }
			)
		}
		entry<Screen.MeloStudio>(metadata = navtabMetadata) { key ->
			MeloStudioScreen(
				nested = key.nested,
				onOpenSettings = { backStack.add(Screen.Settings.MeloKeep) }
			)
		}
		entry<Screen.MeloAi>(metadata = navtabMetadata) { key ->
			MeloAiScreen(
				nested = key.nested,
				onOpenSettings = { backStack.add(Screen.Settings.MeloKeep) }
			)
		}
		entry<Screen.Starred>(metadata = navtabMetadata) {
			MeloLikedSongsScreen()
		}
		entry<Screen.AlbumList>(metadata = navtabMetadata) { key ->
			AlbumListScreen(key.nested, key.listType)
		}
		entry<Screen.PlaylistList>(metadata = navtabMetadata) { key ->
			PlaylistListScreen(key.nested)
		}
		entry<Screen.ArtistList>(metadata = navtabMetadata) { key ->
			ArtistListScreen(key.nested, key.listType)
		}
		entry<Screen.GenreList>(metadata = navtabMetadata) { key ->
			GenreListScreen(key.nested)
		}
		entry<Screen.GenreDetail> { key ->
			GenreDetailScreen(key.genreName)
		}
		entry<Screen.SongList>(metadata = navtabMetadata) { key ->
			SongListScreen(key.nested, key.listType)
		}

		entry<Screen.RadioList>(metadata = navtabMetadata) { key ->
			RadioListScreen(key.nested)
		}

		// misc
		entry<Screen.Login> {
			MeloNavidromeConnectionScreen()
		}
		entry<Screen.PhoneDownloads>(metadata = detailPane("root")) {
			MeloPhoneDownloadCenterScreen()
		}
		entry<Screen.DailyMix>(metadata = detailPane("root")) {
			MeloDailyMixScreen()
		}
		entry<Screen.ImageView>(metadata = imageViewMetadata) { key ->
			ImageViewScreen(
				coverArtId = key.coverArtId,
				title = key.title,
				sharedTransitionKey = key.sharedTransitionKey
			)
		}
		entry<Screen.Chat> {
			ChatScreen()
		}
		entry<Screen.NowPlaying>(
			metadata = NowPlayingSceneStrategy.bottomSheet(maxWidth = Dp.Unspecified)
		) {
			NowPlayingScreen()
		}
		entry<Screen.Lyrics>(metadata = NowPlayingSceneStrategy.bottomSheet(isTransparent = true)) {
			val player = koinInject<MediaPlayer>()
			val song by remember(player) {
				player.uiState.map { it.currentSong }.distinctUntilChanged()
			}.collectAsStateWithLifecycle(null)
			LyricsScreen(song)
		}
		entry<Screen.Queue>(metadata = BottomSheetSceneStrategy.bottomSheet()) {
			QueueScreen()
		}
		entry<Screen.PlaybackSpeed>(metadata = BottomSheetSceneStrategy.bottomSheet()) {
			PlaybackSpeedScreen()
		}
		entry<Screen.CollectionDetail>(metadata = detailPane("root")) { key ->
			MeloCollectionDetailScreen(key.collectionId, key.tab)
		}
		entry<Screen.SongDetailScreen> { key ->
			SongDetailScreen(
				songId = key.songId,
				initialCoverArtId = key.coverArtId
			)
		}
		entry<Screen.SongDetailSheet>(
			metadata = { key ->
				BottomSheetSceneStrategy.bottomSheet(coverArtId = key.coverArtId)
			}
		) { key ->
			SongDetailSheet(
				songId = key.songId,
				initialCoverArtId = key.coverArtId
			)
		}
		entry<Screen.Search>(metadata = navtabMetadata) { key ->
			SearchScreen(key.nested)
		}
		entry<Screen.ShareList> {
			ShareListScreen()
		}
		entry<Screen.ArtistDetail> { key ->
			MeloArtistDetailScreen(key.artist)
		}

		entry<Screen.Statistics>(metadata = navtabMetadata) { key ->
			StatisticsScreen(key.nested)
		}

		// settings
		entry<Screen.Settings.Root>(metadata = listPane("settings")) {
			MeloStudioSettingsHomeScreen()
		}
		entry<Screen.Settings.MeloKeep>(metadata = detailPane("settings")) {
			MeloSettingsScreen()
		}
		entry<Screen.Settings.Appearance>(metadata = detailPane("settings")) {
			SettingsAppearanceScreen()
		}
		entry<Screen.Settings.BottomAppBar>(metadata = detailPane("settings")) {
			BottomBarScreen()
		}
		entry<Screen.Settings.NowPlaying>(metadata = detailPane("settings")) {
			MeloLyricsAndStageScreen()
		}
		entry<Screen.Settings.Playback>(metadata = detailPane("settings")) {
			MeloAudioEngineScreen()
		}
		entry<Screen.Settings.Effects>(metadata = detailPane("settings")) {
			AudioEffectsScreen()
		}
		entry<Screen.Settings.Developer>(metadata = detailPane("settings")) {
			SettingsDeveloperScreen()
		}
		entry<Screen.Settings.About>(metadata = detailPane("settings")) {
			MeloStorageAndAboutScreen()
		}
		entry<Screen.Settings.DataStorage>(metadata = detailPane("settings")) {
			MeloStorageAndAboutScreen()
		}
		entry<Screen.Settings.Fonts> {
			FontsScreen()
		}
		entry<Screen.Settings.Themes> {
			SettingsThemesScreen()
		}
		entry<Screen.Settings.ConnectionOptions> {
			MeloNavidromeConnectionScreen()
		}
		entry<Screen.Settings.AlternativeAddresses> {
			SettingsAlternativeAddressesScreen()
		}
		entry<Screen.Settings.CustomHeaders> {
			SettingsCustomHeadersScreen()
		}
		entry<Screen.Settings.StreamingQuality> {
			SettingsStreamingQualityScreen()
		}
		entry<Screen.Settings.DownloadQuality> {
			SettingsDownloadQualityScreen()
		}
		entry<Screen.Settings.Logs> {
			SettingsLogsScreen()
		}
		entry<Screen.Settings.AppIcon>(metadata = detailPane("settings")) {
			SettingsAppIconScreen()
		}
		entry<Screen.Settings.Equalizer> {
			MeloEqualizerScreen()
		}
	}
}
