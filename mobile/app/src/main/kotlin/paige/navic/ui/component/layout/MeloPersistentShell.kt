package paige.navic.ui.component.layout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import io.github.agonie0v0.melokeep.shared.MeloSearchController
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import io.github.agonie0v0.melokeep.shared.MeloStudioController
import io.github.agonie0v0.melokeep.shared.MeloTrack
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import paige.navic.database.entity.DownloadStatus
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalSnackBarState
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.manager.session.DownloadManager
import paige.navic.domain.model.DomainSong
import paige.navic.melokeep.MeloDiscoverScreen
import paige.navic.melokeep.MeloHubProfileScreen
import paige.navic.melokeep.MeloStudioScreen
import paige.navic.melokeep.MeloStudioWorkspaceTab
import paige.navic.melokeep.MeloTrackMapper
import paige.navic.melokeep.MeloUnifiedLibraryScreen
import paige.navic.playback.MediaPlayer
import paige.navic.ui.component.common.CoverArt
import paige.navic.ui.component.common.MarqueeText
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.filled.Headphones
import paige.navic.ui.icons.filled.LibraryMusic
import paige.navic.ui.icons.filled.Note
import paige.navic.ui.icons.filled.SkipNext
import paige.navic.ui.icons.filled.SkipPrevious
import paige.navic.ui.icons.outlined.AccountCircle
import paige.navic.ui.icons.outlined.Check
import paige.navic.ui.icons.outlined.Download
import paige.navic.ui.icons.outlined.LibraryMusic
import paige.navic.ui.navigation.Screen
import paige.navic.ui.theme.meloGlassSurface
import paige.navic.ui.theme.meloGoldText
import paige.navic.ui.theme.meloSubtleBorder
import paige.navic.ui.theme.positive
import paige.navic.ui.theme.tabularNums
import paige.navic.ui.util.playPauseIconPainter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 方案 B 持久化单壳层四大主 Tab。
 */
enum class MeloRootTab(
	val label: String,
	val selectedIcon: ImageVector,
	val unselectedIcon: ImageVector
) {
	LIBRARY(
		label = "曲库",
		selectedIcon = Icons.Filled.LibraryMusic,
		unselectedIcon = Icons.Outlined.LibraryMusic
	),
	DISCOVER(
		label = "探索",
		selectedIcon = Icons.Filled.Headphones,
		unselectedIcon = Icons.Filled.Headphones
	),
	STUDIO(
		label = "工坊",
		selectedIcon = Icons.Outlined.Download,
		unselectedIcon = Icons.Outlined.Download
	),
	ME(
		label = "我的",
		selectedIcon = Icons.Outlined.AccountCircle,
		unselectedIcon = Icons.Outlined.AccountCircle
	)
}

@Immutable
private data class DeckTrackSnapshot(
	val song: DomainSong? = null,
	val isPaused: Boolean = true,
	val isLoading: Boolean = false
)

/**
 * 4-Tab 懒加载真常驻零销毁宿主。
 * 每个根 Tab 仅在首次访问时挂载一次，之后永久常驻内存槽位（配合 [SaveableStateHolder] 兜底配置变更），
 * 切换仅通过纯 GPU `graphicsLayer { alpha }`（140ms）与 `zIndex` 瞬切，彻底消除子树销毁重建卡顿。
 */
@Composable
fun MeloPersistentRootHost(
	activeTabState: State<MeloRootTab>,
	onSelectTab: (MeloRootTab) -> Unit,
	stateHolder: SaveableStateHolder,
	modifier: Modifier = Modifier
) {
	val backStack = LocalNavStack.current
	val activeTab by activeTabState
	val mountedTabs = remember {
		mutableStateListOf<MeloRootTab>().apply { add(activeTabState.value) }
	}
	var studioJumpRequest by remember { mutableStateOf<Pair<Int, MeloStudioWorkspaceTab>?>(null) }

	LaunchedEffect(activeTab) {
		if (activeTab !in mountedTabs) {
			mountedTabs.add(activeTab)
		}
	}

	val backgroundColor = MaterialTheme.colorScheme.background

	Box(
		modifier = modifier
			.fillMaxSize()
			.background(backgroundColor)
	) {
		MeloRootTab.entries.forEach { tab ->
			val isSelected = tab == activeTab
			val shouldMount = isSelected || tab in mountedTabs
			if (shouldMount) {
				key(tab) {
					val tabAlphaState = animateFloatAsState(
						targetValue = if (isSelected) 1f else 0f,
						animationSpec = tween(durationMillis = 140),
						label = "MeloRootTabAlpha_${tab.name}"
					)

					Box(
						modifier = Modifier
							.fillMaxSize()
							.zIndex(if (isSelected) 1f else 0f)
							.graphicsLayer { alpha = tabAlphaState.value }
							.background(backgroundColor)
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
								}
							)
					) {
						stateHolder.SaveableStateProvider(tab.name) {
							when (tab) {
								MeloRootTab.LIBRARY -> MeloUnifiedLibraryScreen(
									onOpenMeloSettings = { backStack.add(Screen.Settings.MeloKeep) },
									onNavigateToStudioScrape = {
										studioJumpRequest = (studioJumpRequest?.first ?: 0) + 1 to MeloStudioWorkspaceTab.SCRAPE_JOBS
										onSelectTab(MeloRootTab.STUDIO)
									}
								)

								MeloRootTab.DISCOVER -> MeloDiscoverScreen(
									nested = false,
									onOpenSettings = { backStack.add(Screen.Settings.MeloKeep) }
								)

								MeloRootTab.STUDIO -> MeloStudioScreen(
									nested = false,
									isActiveTab = (activeTab == MeloRootTab.STUDIO),
									jumpRequest = studioJumpRequest,
									onConsumeJumpRequest = { studioJumpRequest = null },
									onOpenSettings = { backStack.add(Screen.Settings.MeloKeep) }
								)

								MeloRootTab.ME -> MeloHubProfileScreen(
									onOpenOnboarding = { backStack.add(Screen.MeloOnboarding) },
									onOpenPhoneDownloads = {
										studioJumpRequest = (studioJumpRequest?.first ?: 0) + 1 to MeloStudioWorkspaceTab.PHONE_OFFLINE
										onSelectTab(MeloRootTab.STUDIO)
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

/**
 * 全局常驻底部悬浮叠加层：上层为灵动播放舱 [MeloPlayerDeck]，下层为 4-Tab 毛玻璃悬浮坞 [MeloNavDock]。
 * [showNavDock] 与 [showPlayerDeck] 仅表达路由层可见性；滚动折叠由 [scrollCollapsed] 驱动纯 GPU
 * `graphicsLayer` 位移与透明过渡，滑动全程 0 Recomposition、0 Relayout、零组件销毁。
 */
@Composable
fun MeloPersistentBottomOverlay(
	showNavDock: Boolean,
	showPlayerDeck: Boolean,
	activeTabState: State<MeloRootTab>,
	onSelectTab: (MeloRootTab) -> Unit,
	modifier: Modifier = Modifier,
	scrollCollapsed: Boolean = false
) {
	val collapseFractionState = animateFloatAsState(
		targetValue = if (scrollCollapsed) 1f else 0f,
		animationSpec = spring(
			dampingRatio = Spring.DampingRatioNoBouncy,
			stiffness = Spring.StiffnessMediumLow
		),
		label = "MeloBottomOverlayCollapse"
	)
	val isFullyCollapsed by remember {
		derivedStateOf { collapseFractionState.value > 0.98f }
	}

	AnimatedVisibility(
		visible = showNavDock || showPlayerDeck,
		modifier = modifier.fillMaxWidth(),
		enter = slideInVertically(tween(200)) { it } + fadeIn(tween(180)),
		exit = slideOutVertically(tween(160)) { it } + fadeOut(tween(140)),
		label = "MeloBottomOverlayVisibility"
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.graphicsLayer {
					val fraction = collapseFractionState.value
					translationY = if (fraction > 0.98f) size.height else size.height * fraction
					alpha = (1f - fraction * 1.15f).coerceIn(0f, 1f)
				}
				.then(
					if (isFullyCollapsed) {
						Modifier.clearAndSetSemantics { hideFromAccessibility() }
					} else {
						Modifier
					}
				)
				.background(
					Brush.verticalGradient(
						0.0f to Color.Transparent,
						0.25f to MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
						0.50f to MaterialTheme.colorScheme.background,
						1.0f to MaterialTheme.colorScheme.background
					)
				)
				.windowInsetsPadding(WindowInsets.navigationBars)
				.padding(horizontal = 12.dp)
				.padding(bottom = 10.dp),
			contentAlignment = Alignment.BottomCenter
		) {
			Column(
				modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.spacedBy(8.dp)
			) {
				AnimatedVisibility(
					visible = showPlayerDeck,
					enter = slideInVertically(tween(200)) { it } + fadeIn(tween(180)),
					exit = slideOutVertically(tween(160)) { it } + fadeOut(tween(140))
				) {
					MeloPlayerDeck()
				}

				AnimatedVisibility(
					visible = showNavDock,
					enter = slideInVertically(tween(200)) { it } + fadeIn(tween(180)),
					exit = slideOutVertically(tween(160)) { it } + fadeOut(tween(140))
				) {
					MeloNavDock(
						activeTab = activeTabState.value,
						onSelectTab = onSelectTab
					)
				}
			}
		}
	}
}

/**
 * 悬浮灵动播放舱：高清小封面、曲名歌手、无损金标、左右横滑切歌触感反馈、
 * 在线 `mk:` 曲目「入库 / 已入库」胶囊按钮、播放暂停圆钮以及底部 2.dp 渐变进度细线。
 */
@Composable
fun MeloPlayerDeck(
	modifier: Modifier = Modifier
) {
	val player = koinInject<MediaPlayer>()
	val preferenceManager = koinInject<PreferenceManager>()
	val meloSettingsStore = koinInject<MeloSettingsStore>()
	val searchController = koinInject<MeloSearchController>()
	val studioController = koinInject<MeloStudioController>()
	val backStack = LocalNavStack.current
	val snackBarState = LocalSnackBarState.current
	val haptics = LocalHapticFeedback.current
	val scope = rememberCoroutineScope()

	val snapshot by remember(player) {
		player.uiState
			.map { DeckTrackSnapshot(it.currentSong, it.isPaused, it.isLoading) }
			.distinctUntilChanged()
	}.collectAsStateWithLifecycle(DeckTrackSnapshot())

	val meloConfig by meloSettingsStore.config.collectAsStateWithLifecycle()
	val enqueuedIds by remember(searchController) {
		searchController.state.map { it.enqueuedTrackIds }.distinctUntilChanged()
	}.collectAsStateWithLifecycle(emptySet())
	val activeDownloadIds by remember(studioController) {
		studioController.state
			.map { s ->
				s.downloads
					.filter { it.status != "failed" && it.status != "cancelled" }
					.mapNotNull { it.track.id.takeIf(String::isNotBlank) }
					.toSet()
			}
			.distinctUntilChanged()
	}.collectAsStateWithLifecycle(emptySet())

	val song = snapshot.song
	if (song == null && preferenceManager.hideIfIdle) return

	var enqueuingThisSong by remember(song?.id) { mutableStateOf(false) }

	val rawTrackId = remember(song?.id) {
		song?.id?.takeIf { MeloTrackMapper.isMeloSongId(it) }?.let(MeloTrackMapper::toRawTrackId)
	}
	val showIngestButton = song != null &&
		rawTrackId != null &&
		!rawTrackId.startsWith("file:") &&
		!rawTrackId.startsWith("asset:") &&
		meloConfig.isAuthenticated
	val isEnqueued = rawTrackId != null && (rawTrackId in enqueuedIds || rawTrackId in activeDownloadIds)

	val losslessBadge = remember(song?.id, song?.bitDepth, song?.fileExtension, song?.bitRate) {
		if (song == null) null
		else when {
			(song.bitDepth ?: 0) >= 24 -> "HI-RES"
			song.fileExtension?.lowercase() in setOf("flac", "wav", "alac", "ape", "dsf") ->
				song.fileExtension?.uppercase() ?: "FLAC"
			(song.bitRate ?: 0) >= 700 -> "LOSSLESS"
			else -> null
		}
	}

	val swipeThresholdPx = 150f
	val offsetX = remember { Animatable(0f) }
	var thresholdHapticFired by remember { mutableStateOf(false) }
	val shape = RoundedCornerShape(20.dp)

	val openNowPlaying = dropUnlessResumed {
		if (song != null && Screen.NowPlaying !in backStack) {
			backStack.add(Screen.NowPlaying)
		}
	}

	Box(
		modifier = modifier.fillMaxWidth(),
		contentAlignment = Alignment.Center
	) {
		// 滑动底层上一首/下一首微指示
		val swipeProgress = (abs(offsetX.value) / swipeThresholdPx).coerceIn(0f, 1f)
		if (swipeProgress > 0.05f) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 20.dp),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically
			) {
				Icon(
					imageVector = Icons.Filled.SkipPrevious,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier
						.size(24.dp)
						.alpha(if (offsetX.value > 0f) swipeProgress else 0f)
				)
				Icon(
					imageVector = Icons.Filled.SkipNext,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier
						.size(24.dp)
						.alpha(if (offsetX.value < 0f) swipeProgress else 0f)
				)
			}
		}

		Surface(
			modifier = Modifier
				.fillMaxWidth()
				.offset { IntOffset(offsetX.value.roundToInt(), 0) }
				.draggable(
					enabled = song != null && preferenceManager.swipeToSkip,
					orientation = Orientation.Horizontal,
					state = rememberDraggableState { delta ->
						val nextOffset = (offsetX.value + delta).coerceIn(-240f, 240f)
						scope.launch {
							offsetX.snapTo(nextOffset)
						}
						val past = abs(nextOffset) >= swipeThresholdPx
						if (past && !thresholdHapticFired) {
							thresholdHapticFired = true
							haptics.performHapticFeedback(HapticFeedbackType.LongPress)
						} else if (!past) {
							thresholdHapticFired = false
						}
					},
					onDragStopped = {
						scope.launch {
							val current = offsetX.value
							when {
								current <= -swipeThresholdPx -> {
									haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
									player.next()
								}
								current >= swipeThresholdPx -> {
									haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
									player.previous()
								}
							}
							thresholdHapticFired = false
							offsetX.animateTo(
								targetValue = 0f,
								animationSpec = spring(
									dampingRatio = Spring.DampingRatioLowBouncy,
									stiffness = Spring.StiffnessMediumLow
								)
							)
						}
					}
				),
			shape = shape,
			color = MaterialTheme.colorScheme.meloGlassSurface,
			border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.meloSubtleBorder),
			shadowElevation = 4.dp,
			onClick = openNowPlaying
		) {
			Box(modifier = Modifier.fillMaxWidth()) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(start = 10.dp, end = 10.dp, top = 9.dp, bottom = 11.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(10.dp)
				) {
					// 封面区
					Box(
						modifier = Modifier.size(44.dp),
						contentAlignment = Alignment.Center
					) {
						CoverArt(
							coverArtId = song?.coverArtId,
							modifier = Modifier.fillMaxSize(),
							shape = RoundedCornerShape(12.dp)
						)
						if (song?.coverArtId == null) {
							Icon(
								imageVector = Icons.Filled.Note,
								contentDescription = null,
								tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
								modifier = Modifier.size(20.dp)
							)
						}
						if (snapshot.isLoading) {
							Box(
								modifier = Modifier
									.fillMaxSize()
									.clip(RoundedCornerShape(12.dp))
									.background(Color.Black.copy(alpha = 0.35f)),
								contentAlignment = Alignment.Center
							) {
								CircularProgressIndicator(
									modifier = Modifier.size(20.dp),
									strokeWidth = 2.dp,
									color = Color.White
								)
							}
						}
					}

					// 曲名 + 歌手 · 音质内联元数据
					Column(
						modifier = Modifier.weight(1f),
						verticalArrangement = Arrangement.spacedBy(2.dp)
					) {
						MarqueeText(
							text = song?.title ?: "暂未播放曲目",
							style = MaterialTheme.typography.bodyMedium.copy(
								fontWeight = FontWeight.SemiBold
							)
						)

						val artistBase = remember(song?.id, song?.artistName, song?.artists) {
							song?.artists?.joinToString(", ") { it.name }?.ifBlank { null }
								?: song?.artistName
								?: "轻触曲库或探索开始聆听"
						}
						val subtitleDisplay = remember(artistBase, losslessBadge) {
							if (losslessBadge != null) "$artistBase · $losslessBadge" else artistBase
						}
						Text(
							text = subtitleDisplay,
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis
						)
					}

					// 在线试听一键入库胶囊
					if (showIngestButton && rawTrackId != null) {
						val containerColor = if (isEnqueued) {
							MaterialTheme.colorScheme.positive.copy(alpha = 0.12f)
						} else {
							MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
						}
						val contentColor = if (isEnqueued) {
							MaterialTheme.colorScheme.positive
						} else {
							MaterialTheme.colorScheme.primary
						}
						val borderColor = if (isEnqueued) {
							MaterialTheme.colorScheme.positive.copy(alpha = 0.28f)
						} else {
							MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
						}
						Surface(
							onClick = {
								if (!isEnqueued && !enqueuingThisSong) {
									enqueuingThisSong = true
									haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
									scope.launch {
										val cachedTrack = searchController.state.value.tracks
											.firstOrNull { it.id == rawTrackId }
											?: MeloTrack(
												id = rawTrackId,
												title = song.title,
												artist = song.artistName.orEmpty(),
												album = song.albumTitle.orEmpty(),
												duration = song.duration.inWholeMilliseconds / 1000.0,
												cover = song.coverArtId
											)
										val result = searchController.enqueueDownload(cachedTrack)
										enqueuingThisSong = false
										result.onSuccess {
											studioController.refreshDownloads(silent = true)
											snackBarState.currentSnackbarData?.dismiss()
											snackBarState.showSnackbar("已加入下载入库队列：${song.title}")
										}
									}
								}
							},
							shape = CircleShape,
							color = containerColor,
							border = BorderStroke(0.5.dp, borderColor)
						) {
							Row(
								modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(3.dp)
							) {
								if (enqueuingThisSong) {
									CircularProgressIndicator(
										modifier = Modifier.size(12.dp),
										strokeWidth = 1.5.dp,
										color = contentColor
									)
								} else {
									Icon(
										imageVector = if (isEnqueued) Icons.Outlined.Check else Icons.Outlined.Download,
										contentDescription = null,
										tint = contentColor,
										modifier = Modifier.size(13.dp)
									)
								}
								Text(
									text = if (isEnqueued) "已入库" else "入库",
									style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
									color = contentColor,
									maxLines = 1
								)
							}
						}
					}

					// 播放/暂停圆钮
					Surface(
						onClick = {
							if (song != null) {
								haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
								player.togglePlay()
							}
						},
						enabled = song != null,
						shape = CircleShape,
						color = if (song != null) {
							MaterialTheme.colorScheme.primary
						} else {
							MaterialTheme.colorScheme.surfaceContainerHighest
						},
						contentColor = if (song != null) {
							MaterialTheme.colorScheme.onPrimary
						} else {
							MaterialTheme.colorScheme.onSurfaceVariant
						},
						modifier = Modifier.size(38.dp)
					) {
						Box(contentAlignment = Alignment.Center) {
							Icon(
								painter = playPauseIconPainter(snapshot.isPaused),
								contentDescription = if (snapshot.isPaused) "播放" else "暂停",
								modifier = Modifier.size(20.dp)
							)
						}
					}
				}

				// 底部 2.dp 渐变进度细线（完全隔离于父重组之外）
				MeloDeckProgressBar(
					player = player,
					hasSong = song != null,
					modifier = Modifier.align(Alignment.BottomStart)
				)
			}
		}
	}
}

@Composable
private fun MeloDeckProgressBar(
	player: MediaPlayer,
	hasSong: Boolean,
	modifier: Modifier = Modifier
) {
	val progress by player.progressFlow.collectAsStateWithLifecycle()

	val animatedProgressState = animateFloatAsState(
		targetValue = if (hasSong) progress.coerceIn(0f, 1f) else 0f,
		animationSpec = tween(durationMillis = 180),
		label = "DeckProgress"
	)

	val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
	val startColor = MaterialTheme.colorScheme.primary
	val endColor = MaterialTheme.colorScheme.meloGoldText

	Spacer(
		modifier = modifier
			.fillMaxWidth()
			.height(2.dp)
			.drawWithCache {
				val progressBrush = Brush.horizontalGradient(
					colors = listOf(startColor, endColor)
				)
				onDrawBehind {
					drawRect(color = trackColor)
					val fraction = animatedProgressState.value
					if (fraction > 0f) {
						drawRect(
							brush = progressBrush,
							size = Size(width = size.width * fraction, height = size.height)
						)
					}
				}
			}
	)
}

/**
 * 方案 B 4-Tab 毛玻璃悬浮坞（曲库 · 探索 · 工坊 · 我的），支持工坊实时数字角标。
 */
@Composable
fun MeloNavDock(
	activeTab: MeloRootTab,
	onSelectTab: (MeloRootTab) -> Unit,
	modifier: Modifier = Modifier
) {
	val studioController = koinInject<MeloStudioController>()
	val downloadManager = koinInject<DownloadManager>()
	val activeTaskCount by remember(studioController) {
		studioController.state.map { it.activeTaskCount }.distinctUntilChanged()
	}.collectAsStateWithLifecycle(0)
	val phoneDownloadingCount by remember(downloadManager) {
		downloadManager.allDownloads
			.map { list -> list.count { it.status == DownloadStatus.DOWNLOADING } }
			.distinctUntilChanged()
	}.collectAsStateWithLifecycle(0)
	val totalStudioBadge = activeTaskCount + phoneDownloadingCount
	val haptics = LocalHapticFeedback.current

	Surface(
		modifier = modifier.fillMaxWidth(),
		shape = RoundedCornerShape(24.dp),
		color = MaterialTheme.colorScheme.meloGlassSurface,
		border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.meloSubtleBorder),
		shadowElevation = 5.dp
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 6.dp, vertical = 5.dp),
			horizontalArrangement = Arrangement.spacedBy(4.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			MeloRootTab.entries.forEach { tab ->
				val selected = activeTab == tab
				val pillColor by animateColorAsState(
					targetValue = if (selected) {
						MaterialTheme.colorScheme.primary.copy(alpha = 0.11f)
					} else {
						Color.Transparent
					},
					animationSpec = tween(150),
					label = "DockTabBg"
				)
				val contentColor by animateColorAsState(
					targetValue = if (selected) {
						MaterialTheme.colorScheme.primary
					} else {
						MaterialTheme.colorScheme.onSurfaceVariant
					},
					animationSpec = tween(150),
					label = "DockTabTint"
				)

				Box(
					modifier = Modifier
						.weight(1f)
						.clip(RoundedCornerShape(20.dp))
						.background(pillColor)
						.clickable(
							interactionSource = remember { MutableInteractionSource() },
							indication = null
						) {
							haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
							onSelectTab(tab)
						}
						.padding(vertical = 7.dp),
					contentAlignment = Alignment.Center
				) {
					Column(
						horizontalAlignment = Alignment.CenterHorizontally,
						verticalArrangement = Arrangement.spacedBy(2.dp)
					) {
						Box(contentAlignment = Alignment.TopEnd) {
							Icon(
								imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
								contentDescription = tab.label,
								tint = contentColor,
								modifier = Modifier.size(21.dp)
							)
							if (tab == MeloRootTab.STUDIO && totalStudioBadge > 0) {
								val badgeText = if (totalStudioBadge > 99) "99+" else totalStudioBadge.toString()
								Box(
									modifier = Modifier
										.offset(x = 10.dp, y = (-4).dp)
										.clip(CircleShape)
										.background(MaterialTheme.colorScheme.primary)
										.border(1.dp, MaterialTheme.colorScheme.surface, CircleShape)
										.padding(horizontal = 4.dp, vertical = 1.dp),
									contentAlignment = Alignment.Center
								) {
									Text(
										text = badgeText,
										style = MaterialTheme.typography.labelSmall.copy(
											fontSize = 9.sp,
											fontWeight = FontWeight.Bold
										).tabularNums(),
										color = MaterialTheme.colorScheme.onPrimary,
										maxLines = 1
									)
								}
							}
						}
						Text(
							text = tab.label,
							style = MaterialTheme.typography.labelSmall.copy(
								fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
							),
							color = contentColor,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis
						)
					}
				}
			}
		}
	}
}

/**
 * 宽屏/平板侧边导航轨，与 4-Tab 方案 B 对齐。
 */
@Composable
fun MeloPersistentSideRail(
	activeTabState: State<MeloRootTab>,
	isAtRoot: Boolean,
	onSelectTab: (MeloRootTab) -> Unit,
	modifier: Modifier = Modifier
) {
	val activeTab by activeTabState
	val studioController = koinInject<MeloStudioController>()
	val downloadManager = koinInject<DownloadManager>()
	val activeTaskCount by remember(studioController) {
		studioController.state.map { it.activeTaskCount }.distinctUntilChanged()
	}.collectAsStateWithLifecycle(0)
	val phoneDownloadingCount by remember(downloadManager) {
		downloadManager.allDownloads
			.map { list -> list.count { it.status == DownloadStatus.DOWNLOADING } }
			.distinctUntilChanged()
	}.collectAsStateWithLifecycle(0)
	val totalStudioBadge = activeTaskCount + phoneDownloadingCount
	val backStack = LocalNavStack.current
	val player = koinInject<MediaPlayer>()
	val snapshot by remember(player) {
		player.uiState
			.map { DeckTrackSnapshot(it.currentSong, it.isPaused, it.isLoading) }
			.distinctUntilChanged()
	}.collectAsStateWithLifecycle(DeckTrackSnapshot())

	NavigationRail(
		modifier = modifier,
		containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
		header = {
			Spacer(Modifier.height(12.dp))
			Surface(
				onClick = dropUnlessResumed {
					if (snapshot.song != null && Screen.NowPlaying !in backStack) {
						backStack.add(Screen.NowPlaying)
					}
				},
				shape = RoundedCornerShape(14.dp),
				color = MaterialTheme.colorScheme.surfaceContainerHigh,
				modifier = Modifier.size(48.dp)
			) {
				Box(contentAlignment = Alignment.Center) {
					CoverArt(
						coverArtId = snapshot.song?.coverArtId,
						modifier = Modifier.fillMaxSize(),
						shape = RoundedCornerShape(14.dp)
					)
					if (snapshot.song?.coverArtId == null) {
						Icon(
							imageVector = Icons.Filled.Note,
							contentDescription = null,
							tint = MaterialTheme.colorScheme.onSurfaceVariant
						)
					}
				}
			}
		}
	) {
		Spacer(Modifier.height(12.dp))
		MeloRootTab.entries.forEach { tab ->
			val selected = isAtRoot && activeTab == tab
			NavigationRailItem(
				selected = selected,
				onClick = { onSelectTab(tab) },
				icon = {
					Box(contentAlignment = Alignment.TopEnd) {
						Icon(
							imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
							contentDescription = tab.label
						)
						if (tab == MeloRootTab.STUDIO && totalStudioBadge > 0) {
							Box(
								modifier = Modifier
									.offset(x = 8.dp, y = (-4).dp)
									.clip(CircleShape)
									.background(MaterialTheme.colorScheme.primary)
									.padding(horizontal = 4.dp, vertical = 1.dp)
							) {
								Text(
									text = if (totalStudioBadge > 99) "99+" else "$totalStudioBadge",
									style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp).tabularNums(),
									color = MaterialTheme.colorScheme.onPrimary
								)
							}
						}
					}
				},
				label = { Text(tab.label) }
			)
		}
	}
}
