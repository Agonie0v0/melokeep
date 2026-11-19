/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.screen.nowPlaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import io.github.agonie0v0.melokeep.shared.MeloSearchController
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import io.github.agonie0v0.melokeep.shared.MeloStudioController
import io.github.agonie0v0.melokeep.shared.MeloTrack
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import paige.navic.R
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalSheetState
import paige.navic.di.LocalSizeClass
import paige.navic.di.LocalSnackBarState
import paige.navic.domain.manager.playback.SleepTimerManager
import paige.navic.domain.manager.playback.SleepTimerMode
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.model.DomainSong
import paige.navic.domain.model.lyrics.LyricsResult
import paige.navic.domain.model.settings.NowPlayingBackgroundStyle
import paige.navic.domain.model.settings.ToolbarPosition
import paige.navic.melokeep.MeloTrackMapper
import paige.navic.playback.MediaPlayer
import paige.navic.playback.PlaybackUtils
import paige.navic.ui.component.common.BlendBackground
import paige.navic.ui.component.common.ErrorBox
import paige.navic.ui.component.layout.SheetScaffold
import paige.navic.ui.component.layout.TopBarButton
import paige.navic.ui.component.sheet.SleepTimerSheet
import paige.navic.ui.component.toolbar.SheetActionButton
import paige.navic.ui.core.UiState
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.filled.SkipNext
import paige.navic.ui.icons.filled.SkipPrevious
import paige.navic.ui.icons.outlined.Bedtime
import paige.navic.ui.icons.outlined.Check
import paige.navic.ui.icons.outlined.Download
import paige.navic.ui.icons.outlined.KeyboardArrowDown
import paige.navic.ui.icons.outlined.List
import paige.navic.ui.icons.outlined.MoreHoriz
import paige.navic.ui.navigation.PersistentViewModelStoreOwner
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screen.lyrics.component.LyricsScreenContent
import paige.navic.ui.screen.lyrics.component.LyricsScreenLoadingView
import paige.navic.ui.screen.lyrics.component.LyricsScreenPlaceholder
import paige.navic.ui.screen.lyrics.viewmodel.LyricsScreenViewModel
import paige.navic.ui.screen.nowPlaying.component.controls.NowPlayingArtworkPager
import paige.navic.ui.screen.nowPlaying.component.rows.NowPlayingControlsRow
import paige.navic.ui.screen.nowPlaying.viewmodel.NowPlayingViewModel
import paige.navic.ui.theme.meloGlassSurface
import paige.navic.ui.theme.meloGoldBorder
import paige.navic.ui.theme.meloGoldContainer
import paige.navic.ui.theme.meloGoldText
import paige.navic.ui.theme.meloSubtleBorder
import paige.navic.ui.theme.positive
import paige.navic.ui.theme.tabularNums
import paige.navic.ui.util.playPauseIconPainter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private enum class NowPlayingStageMode {
	COVER,
	LYRICS
}

private data class NowPlayingUiSlice(
	val currentSong: DomainSong?,
	val isPaused: Boolean,
	val playbackMimeType: String?,
	val playbackBitDepth: Int?,
	val playbackSampleRate: Int?,
	val playbackBitrate: Int?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen() {
	val preferenceManager = koinInject<PreferenceManager>()
	val player = koinInject<MediaPlayer>()
	val sleepTimerManager = koinInject<SleepTimerManager>()
	val backStack = LocalNavStack.current

	val currentScreen = backStack.lastOrNull()
	val isPlayerCurrent = currentScreen is Screen.NowPlaying ||
		currentScreen is Screen.Queue ||
		currentScreen is Screen.PlaybackSpeed ||
		currentScreen is Screen.SongDetailSheet

	val playerSlice by remember(player) {
		player.uiState
			.map { state ->
				NowPlayingUiSlice(
					currentSong = state.currentSong,
					isPaused = state.isPaused,
					playbackMimeType = state.playbackMimeType,
					playbackBitDepth = state.playbackBitDepth,
					playbackSampleRate = state.playbackSampleRate,
					playbackBitrate = state.playbackBitrate
				)
			}
			.distinctUntilChanged()
	}.collectAsStateWithLifecycle(
		initialValue = player.uiState.value.let { state ->
			NowPlayingUiSlice(
				currentSong = state.currentSong,
				isPaused = state.isPaused,
				playbackMimeType = state.playbackMimeType,
				playbackBitDepth = state.playbackBitDepth,
				playbackSampleRate = state.playbackSampleRate,
				playbackBitrate = state.playbackBitrate
			)
		}
	)
	val song = playerSlice.currentSong
	val sleepTimerMode by sleepTimerManager.mode.collectAsStateWithLifecycle()

	val viewModel = koinViewModel<NowPlayingViewModel> { parametersOf(player) }
	val songIsStarred by viewModel.songIsStarred.collectAsStateWithLifecycle()
	val songRating by viewModel.songRating.collectAsStateWithLifecycle()

	var stageMode by rememberSaveable { mutableStateOf(NowPlayingStageMode.COVER) }
	var lyricsMounted by rememberSaveable { mutableStateOf(false) }
	if (stageMode == NowPlayingStageMode.LYRICS && !lyricsMounted) {
		lyricsMounted = true
	}
	var sleepTimerOpen by rememberSaveable { mutableStateOf(false) }
	val lyricsOffsetMs = preferenceManager.playerLyricsOffsetMs

	val sheetState = LocalSheetState.current
	val closeScope = rememberCoroutineScope()
	val animateToDismiss = dropUnlessResumed {
		closeScope.launch {
			sheetState.hide()
		}.invokeOnCompletion {
			if (!sheetState.isVisible) {
				backStack.remove(Screen.NowPlaying)
			}
		}
	}

	val toolbarPosition = if (stageMode == NowPlayingStageMode.LYRICS) {
		ToolbarPosition.Top
	} else {
		preferenceManager.nowPlayingToolbarPosition
	}

	SheetScaffold(
		toolbarPosition = toolbarPosition,
		toolbar = { windowInsets ->
			NowPlayingStageToolbar(
				modifier = Modifier.alpha(if (isPlayerCurrent) 1f else 0f),
				windowInsets = windowInsets,
				stageMode = stageMode,
				sleepTimerMode = sleepTimerMode,
				sleepTimerLabel = sleepTimerManager.timeLeft?.let { "${it.inWholeMinutes.coerceAtLeast(1)}m" },
				lyricsOffsetMs = lyricsOffsetMs,
				onDismiss = animateToDismiss,
				onSelectStage = { stageMode = it },
				onOpenSleepTimer = { sleepTimerOpen = true },
				onOpenQueue = dropUnlessResumed { backStack.add(Screen.Queue) },
				onOpenFullLyrics = dropUnlessResumed { backStack.add(Screen.Lyrics) },
				onAdjustLyricsOffset = { delta ->
					preferenceManager.playerLyricsOffsetMs =
						(preferenceManager.playerLyricsOffsetMs + delta).coerceIn(-5000, 5000)
				},
				onResetLyricsOffset = {
					preferenceManager.playerLyricsOffsetMs = 0
				}
			)
		}
	) { contentPadding ->
		Box(
			Modifier
				.fillMaxSize()
				.background(MaterialTheme.colorScheme.background)
		) {
			if (preferenceManager.nowPlayingBackgroundStyle == NowPlayingBackgroundStyle.Dynamic) {
				BlendBackground(
					coverArtId = song?.coverArtId,
					isPaused = playerSlice.isPaused || currentScreen is Screen.Queue
				)
			}
			if (!isPlayerCurrent) return@Box

			val showLyrics = stageMode == NowPlayingStageMode.LYRICS
			val showLyricsState = rememberUpdatedState(showLyrics)
			val lyricsAlphaState = animateFloatAsState(
				targetValue = if (showLyrics) 1f else 0f,
				animationSpec = tween(durationMillis = 140),
				label = "NowPlayingStageAlpha"
			)
			val coverPlaced = remember {
				derivedStateOf { !showLyricsState.value || lyricsAlphaState.value < 0.999f }
			}
			val lyricsPlaced = remember {
				derivedStateOf { showLyricsState.value || lyricsAlphaState.value > 0.001f }
			}

			Box(modifier = Modifier.fillMaxSize()) {
				Box(
					modifier = Modifier
						.fillMaxSize()
						.zIndex(if (!showLyrics) 1f else 0f)
						.layout { measurable, constraints ->
							if (!coverPlaced.value) {
								layout(0, 0) {}
							} else {
								val placeable = measurable.measure(constraints)
								layout(placeable.width, placeable.height) {
									placeable.place(0, 0)
								}
							}
						}
						.graphicsLayer {
							alpha = 1f - lyricsAlphaState.value
						}
				) {
					NowPlayingCoverStage(
						contentPadding = contentPadding,
						toolbarPosition = preferenceManager.nowPlayingToolbarPosition,
						song = song,
						isPaused = playerSlice.isPaused,
						playbackMimeType = playerSlice.playbackMimeType,
						playbackBitDepth = playerSlice.playbackBitDepth,
						playbackSampleRate = playerSlice.playbackSampleRate,
						playbackBitrate = playerSlice.playbackBitrate,
						songIsStarred = songIsStarred,
						onSetSongIsStarred = viewModel::starSong,
						songRating = songRating,
						onSetSongRating = viewModel::rateSong
					)
				}

				if (lyricsMounted) {
					Box(
						modifier = Modifier
							.fillMaxSize()
							.zIndex(if (showLyrics) 1f else 0f)
							.layout { measurable, constraints ->
								if (!lyricsPlaced.value) {
									layout(0, 0) {}
								} else {
									val placeable = measurable.measure(constraints)
									layout(placeable.width, placeable.height) {
										placeable.place(0, 0)
									}
								}
							}
							.graphicsLayer {
								alpha = lyricsAlphaState.value
							}
					) {
						NowPlayingInlineLyricsStage(
							player = player,
							song = song,
							isActive = showLyrics,
							isPaused = playerSlice.isPaused,
							lyricsOffsetMs = lyricsOffsetMs,
							contentPadding = contentPadding,
							onTogglePlay = player::togglePlay,
							onPrevious = player::previous,
							onNext = player::next
						)
					}
				}
			}
		}
	}

	if (sleepTimerOpen) {
		SleepTimerSheet(onDismissRequest = { sleepTimerOpen = false })
	}
}

@Composable
private fun NowPlayingStageToolbar(
	windowInsets: WindowInsets,
	stageMode: NowPlayingStageMode,
	sleepTimerMode: SleepTimerMode,
	sleepTimerLabel: String?,
	lyricsOffsetMs: Int,
	onDismiss: () -> Unit,
	onSelectStage: (NowPlayingStageMode) -> Unit,
	onOpenSleepTimer: () -> Unit,
	onOpenQueue: () -> Unit,
	onOpenFullLyrics: () -> Unit,
	onAdjustLyricsOffset: (Int) -> Unit,
	onResetLyricsOffset: () -> Unit,
	modifier: Modifier = Modifier
) {
	val sizeClass = LocalSizeClass.current
	val isLandscape = sizeClass.widthSizeClass >= WindowWidthSizeClass.Medium
	var moreMenuOpen by remember { mutableStateOf(false) }

	Row(
		modifier = modifier
			.fillMaxWidth()
			.windowInsetsPadding(windowInsets)
			.padding(horizontal = 16.dp, vertical = if (isLandscape) 4.dp else 18.dp),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.CenterVertically
	) {
		TopBarButton(
			onClick = onDismiss,
			content = {
				Icon(
					imageVector = Icons.Outlined.KeyboardArrowDown,
					contentDescription = stringResource(R.string.action_navigate_back)
				)
			}
		)

		// 顶部轻量「封面 | 歌词」切换胶囊
		Surface(
			shape = CircleShape,
			color = MaterialTheme.colorScheme.meloGlassSurface,
			border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.meloSubtleBorder)
		) {
			Row(
				modifier = Modifier.padding(3.dp),
				horizontalArrangement = Arrangement.spacedBy(2.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				listOf(
					NowPlayingStageMode.COVER to "封面",
					NowPlayingStageMode.LYRICS to "歌词"
				).forEach { (tab, label) ->
					val selected = stageMode == tab
					Box(
						modifier = Modifier
							.clip(CircleShape)
							.background(
								if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
							)
							.clickable { onSelectStage(tab) }
							.padding(horizontal = 14.dp, vertical = 6.dp)
					) {
						Text(
							text = label,
							style = MaterialTheme.typography.labelMedium,
							fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
							color = if (selected) {
								MaterialTheme.colorScheme.onPrimary
							} else {
								MaterialTheme.colorScheme.onSurfaceVariant
							}
						)
					}
				}
			}
		}

		// 右侧动作组：🌙 定时微透胶囊 + (歌词模式下 ⋯ 微调菜单) + 队列按钮
		Row(
			horizontalArrangement = Arrangement.spacedBy(6.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			val timerActive = sleepTimerMode !is SleepTimerMode.Disabled
			Surface(
				onClick = onOpenSleepTimer,
				shape = CircleShape,
				color = if (timerActive) {
					MaterialTheme.colorScheme.meloGoldContainer
				} else {
					MaterialTheme.colorScheme.meloGlassSurface
				},
				border = BorderStroke(
					0.8.dp,
					if (timerActive) {
						MaterialTheme.colorScheme.meloGoldBorder
					} else {
						MaterialTheme.colorScheme.meloSubtleBorder
					}
				)
			) {
				Row(
					modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(4.dp)
				) {
					Icon(
						imageVector = Icons.Outlined.Bedtime,
						contentDescription = "睡眠定时",
						tint = if (timerActive) {
							MaterialTheme.colorScheme.meloGoldText
						} else {
							MaterialTheme.colorScheme.onSurfaceVariant
						},
						modifier = Modifier.size(15.dp)
					)
					val timerText = when (sleepTimerMode) {
						is SleepTimerMode.Time -> sleepTimerLabel ?: "定时"
						is SleepTimerMode.Songs -> "${sleepTimerMode.remaining}首"
						is SleepTimerMode.EndOfQueue -> "队尾"
						SleepTimerMode.Disabled -> "定时"
					}
					Text(
						text = timerText,
						style = MaterialTheme.typography.labelSmall.tabularNums(),
						color = if (timerActive) {
							MaterialTheme.colorScheme.meloGoldText
						} else {
							MaterialTheme.colorScheme.onSurfaceVariant
						}
					)
				}
			}

			if (stageMode == NowPlayingStageMode.LYRICS) {
				Box {
					TopBarButton(
						onClick = { moreMenuOpen = true },
						content = {
							Icon(
								imageVector = Icons.Outlined.MoreHoriz,
								contentDescription = "歌词更多选项"
							)
						}
					)

					DropdownMenu(
						expanded = moreMenuOpen,
						onDismissRequest = { moreMenuOpen = false }
					) {
						Column(
							modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
							verticalArrangement = Arrangement.spacedBy(8.dp)
						) {
							Text(
								text = "播放页歌词校准",
								style = MaterialTheme.typography.labelMedium,
								color = MaterialTheme.colorScheme.onSurfaceVariant
							)
							Row(
								horizontalArrangement = Arrangement.spacedBy(6.dp),
								verticalAlignment = Alignment.CenterVertically
							) {
								Surface(
									onClick = { onAdjustLyricsOffset(-100) },
									shape = RoundedCornerShape(10.dp),
									color = MaterialTheme.colorScheme.surfaceContainerHigh
								) {
									Text(
										text = "−100ms",
										style = MaterialTheme.typography.labelSmall.tabularNums(),
										modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
									)
								}
								Surface(
									onClick = onResetLyricsOffset,
									shape = RoundedCornerShape(10.dp),
									color = MaterialTheme.colorScheme.primaryContainer
								) {
									val offsetLabel = if (lyricsOffsetMs > 0) "+${lyricsOffsetMs}ms" else "${lyricsOffsetMs}ms"
									Text(
										text = if (lyricsOffsetMs == 0) "0ms 重置" else "$offsetLabel 重置",
										style = MaterialTheme.typography.labelSmall.tabularNums(),
										color = MaterialTheme.colorScheme.onPrimaryContainer,
										modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
									)
								}
								Surface(
									onClick = { onAdjustLyricsOffset(100) },
									shape = RoundedCornerShape(10.dp),
									color = MaterialTheme.colorScheme.surfaceContainerHigh
								) {
									Text(
										text = "+100ms",
										style = MaterialTheme.typography.labelSmall.tabularNums(),
										modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
									)
								}
							}
						}
						HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
						DropdownMenuItem(
							text = { Text("全屏歌词与金句分享") },
							onClick = {
								moreMenuOpen = false
								onOpenFullLyrics()
							}
						)
					}
				}
			}

			SheetActionButton(
				icon = Icons.Outlined.List,
				contentDescription = stringResource(R.string.action_queue),
				onClick = onOpenQueue,
				isStartRounded = true,
				isEndRounded = true
			)
		}
	}
}

@Composable
private fun NowPlayingCoverStage(
	contentPadding: PaddingValues,
	toolbarPosition: ToolbarPosition,
	song: DomainSong?,
	isPaused: Boolean,
	playbackMimeType: String?,
	playbackBitDepth: Int?,
	playbackSampleRate: Int?,
	playbackBitrate: Int?,
	songIsStarred: Boolean,
	onSetSongIsStarred: (Boolean) -> Unit,
	songRating: Int,
	onSetSongRating: (Int) -> Unit
) {
	// 播放 1.0f / 暂停 0.90f 单层弹簧缩放呼吸动效
	val artworkScale by animateFloatAsState(
		targetValue = if (isPaused) 0.90f else 1.0f,
		animationSpec = spring(
			dampingRatio = Spring.DampingRatioMediumBouncy,
			stiffness = Spring.StiffnessLow
		),
		label = "ArtworkBreathScale"
	)

	BoxWithConstraints(
		modifier = Modifier
			.padding(horizontal = 8.dp)
			.fillMaxSize()
	) {
		val isLandscape = maxWidth > maxHeight
		val padding = when {
			isLandscape -> contentPadding
			toolbarPosition == ToolbarPosition.Top -> contentPadding.plus(
				PaddingValues(bottom = 28.dp)
			)
			toolbarPosition == ToolbarPosition.Bottom -> contentPadding.plus(
				PaddingValues(top = 28.dp)
			)
			else -> contentPadding
		}

		if (isLandscape) {
			Row(
				modifier = Modifier
					.fillMaxSize()
					.padding(padding),
				horizontalArrangement = Arrangement.SpaceEvenly,
				verticalAlignment = Alignment.CenterVertically
			) {
				Box(
					modifier = Modifier
						.weight(1f)
						.fillMaxHeight()
						.graphicsLayer {
							scaleX = artworkScale
							scaleY = artworkScale
						},
					contentAlignment = Alignment.Center
				) {
					NowPlayingArtworkPager(
						modifier = Modifier.fillMaxSize(),
						isLandscape = true
					)
				}
				Column(
					modifier = Modifier
						.weight(1f)
						.fillMaxHeight(),
					verticalArrangement = Arrangement.Center,
					horizontalAlignment = Alignment.CenterHorizontally
				) {
					AcousticSpecAndIngestStrip(
						song = song,
						playbackMimeType = playbackMimeType,
						playbackBitDepth = playbackBitDepth,
						playbackSampleRate = playbackSampleRate,
						playbackBitrate = playbackBitrate,
						modifier = Modifier.padding(bottom = 8.dp)
					)
					NowPlayingControlsRow(
						modifier = Modifier.fillMaxWidth(),
						isLandscape = true,
						songIsStarred = songIsStarred,
						onSetSongIsStarred = onSetSongIsStarred,
						songRating = songRating,
						onSetSongRating = onSetSongRating
					)
				}
			}
		} else {
			Column(
				modifier = Modifier
					.fillMaxSize()
					.padding(padding),
				horizontalAlignment = Alignment.CenterHorizontally
			) {
				Box(
					modifier = Modifier
						.weight(1f)
						.fillMaxWidth()
						.graphicsLayer {
							scaleX = artworkScale
							scaleY = artworkScale
						},
					contentAlignment = Alignment.Center
				) {
					NowPlayingArtworkPager(
						modifier = Modifier.fillMaxSize(),
						isLandscape = false
					)
				}

				Column(
					modifier = Modifier
						.fillMaxWidth()
						.padding(bottom = 8.dp),
					horizontalAlignment = Alignment.CenterHorizontally
				) {
					AcousticSpecAndIngestStrip(
						song = song,
						playbackMimeType = playbackMimeType,
						playbackBitDepth = playbackBitDepth,
						playbackSampleRate = playbackSampleRate,
						playbackBitrate = playbackBitrate,
						modifier = Modifier.padding(bottom = 8.dp)
					)

					NowPlayingControlsRow(
						modifier = Modifier.fillMaxWidth(),
						isLandscape = false,
						songIsStarred = songIsStarred,
						onSetSongIsStarred = onSetSongIsStarred,
						songRating = songRating,
						onSetSongRating = onSetSongRating
					)
				}
			}
		}
	}
}

@Composable
private fun AcousticSpecAndIngestStrip(
	song: DomainSong?,
	playbackMimeType: String?,
	playbackBitDepth: Int?,
	playbackSampleRate: Int?,
	playbackBitrate: Int?,
	modifier: Modifier = Modifier
) {
	if (song == null) return

	val meloSettingsStore = koinInject<MeloSettingsStore>()
	val searchController = koinInject<MeloSearchController>()
	val studioController = koinInject<MeloStudioController>()
	val snackBarState = LocalSnackBarState.current
	val haptics = LocalHapticFeedback.current
	val scope = rememberCoroutineScope()

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

	val rawTrackId = remember(song.id) {
		song.id.takeIf { MeloTrackMapper.isMeloSongId(it) }?.let(MeloTrackMapper::toRawTrackId)
	}
	val showIngest = rawTrackId != null &&
		!rawTrackId.startsWith("file:") &&
		!rawTrackId.startsWith("asset:") &&
		meloConfig.isAuthenticated
	val isEnqueued = rawTrackId != null && (rawTrackId in enqueuedIds || rawTrackId in activeDownloadIds)
	var enqueuing by remember(song.id) { mutableStateOf(false) }

	val formatName = remember(playbackMimeType, song.fileExtension) {
		playbackMimeType?.let(PlaybackUtils::getPrettyFormatName)?.uppercase()
			?: song.fileExtension?.uppercase()
			?: "AUDIO"
	}
	val resolvedBitDepth = playbackBitDepth?.takeIf { it > 0 } ?: song.bitDepth
	val resolvedSampleRate = playbackSampleRate?.takeIf { it > 0 } ?: song.sampleRate
	val resolvedBitrateKbps = (playbackBitrate?.takeIf { it > 0 }?.div(1000))
		?: song.bitRate?.takeIf { it > 0 }

	val tierBadge = remember(formatName, resolvedBitDepth, resolvedSampleRate, resolvedBitrateKbps) {
		when {
			(resolvedBitDepth ?: 0) >= 24 || (resolvedSampleRate ?: 0) >= 88200 -> "HI-RES 无损"
			formatName in setOf("FLAC", "ALAC", "WAV", "APE", "DSF") -> "$formatName 无损"
			(resolvedBitrateKbps ?: 0) >= 700 -> "无损音质"
			else -> null
		}
	}

	if (tierBadge == null && !(showIngest && rawTrackId != null)) return

	Row(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp),
		horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
		verticalAlignment = Alignment.CenterVertically
	) {
		// 高定声学身份金标（固定 26.dp 高度，与右侧可点胶囊像素级齐平，左对齐曲名）
		if (tierBadge != null) {
			Surface(
				modifier = Modifier.height(26.dp),
				shape = CircleShape,
				color = MaterialTheme.colorScheme.meloGoldContainer,
				border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.meloGoldBorder)
			) {
				Box(
					modifier = Modifier
						.fillMaxHeight()
						.padding(horizontal = 11.dp),
					contentAlignment = Alignment.Center
				) {
					Text(
						text = tierBadge,
						style = MaterialTheme.typography.labelSmall.copy(
							fontWeight = FontWeight.Bold
						),
						color = MaterialTheme.colorScheme.meloGoldText,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis
					)
				}
			}
		}

		// 在线 mk: 曲目一键「入库」按钮（不用 Surface(onClick) 避免 Material3 注入 48.dp 最小交互高度撑高错位）
		if (showIngest && rawTrackId != null) {
			val containerColor = if (isEnqueued) {
				MaterialTheme.colorScheme.positive.copy(alpha = 0.16f)
			} else {
				MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
			}
			val contentColor = if (isEnqueued) {
				MaterialTheme.colorScheme.positive
			} else {
				MaterialTheme.colorScheme.onPrimaryContainer
			}
			Surface(
				modifier = Modifier
					.height(26.dp)
					.clip(CircleShape)
					.clickable(enabled = !isEnqueued && !enqueuing) {
						enqueuing = true
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
							enqueuing = false
							result.onSuccess {
								studioController.refreshDownloads(silent = true)
								snackBarState.currentSnackbarData?.dismiss()
								snackBarState.showSnackbar("已提交入库：${song.title}")
							}
						}
					},
				shape = CircleShape,
				color = containerColor,
				border = BorderStroke(0.8.dp, contentColor.copy(alpha = 0.35f))
			) {
				Row(
					modifier = Modifier
						.fillMaxHeight()
						.padding(horizontal = 11.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(4.dp)
				) {
					if (enqueuing) {
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
						text = if (isEnqueued) "已入库" else "一键入库",
						style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
						color = contentColor,
						maxLines = 1
					)
				}
			}
		}
	}
}

@Composable
private fun NowPlayingInlineLyricsStage(
	player: MediaPlayer,
	song: DomainSong?,
	isActive: Boolean,
	isPaused: Boolean,
	lyricsOffsetMs: Int,
	contentPadding: PaddingValues,
	onTogglePlay: () -> Unit,
	onPrevious: () -> Unit,
	onNext: () -> Unit
) {
	val viewModel = koinViewModel<LyricsScreenViewModel>(
		key = song?.id,
		parameters = { parametersOf(song?.id) },
		viewModelStoreOwner = koinInject<PersistentViewModelStoreOwner>()
	)
	val lyricsState by viewModel.lyricsState.collectAsStateWithLifecycle()

	Column(
		modifier = Modifier
			.fillMaxSize()
			.padding(top = contentPadding.calculateTopPadding() + 8.dp)
	) {
		Box(
			modifier = Modifier
				.weight(1f)
				.fillMaxWidth(),
			contentAlignment = Alignment.Center
		) {
			if (song == null) {
				LyricsScreenPlaceholder(onRefresh = { viewModel.refreshResults() })
			} else {
				val duration = song.duration

				AnimatedContent(
					targetState = lyricsState,
					modifier = Modifier.fillMaxSize(),
					label = "NowPlayingInlineLyricsContent"
				) { state ->
					when (state) {
						is UiState.Loading -> LyricsScreenLoadingView()
						is UiState.Error -> Box(
							modifier = Modifier.fillMaxSize(),
							contentAlignment = Alignment.Center
						) {
							ErrorBox(
								error = state,
								onRetry = { viewModel.refreshResults() }
							)
						}
						is UiState.Success -> {
							InlineLyricsProgressHost(
								player = player,
								isActive = isActive,
								data = state.data,
								duration = duration,
								lyricsOffsetMs = lyricsOffsetMs,
								onRefresh = { viewModel.refreshResults() }
							)
						}
					}
				}
			}
		}

		// 歌词舞台底部精简控播栏
		Surface(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 20.dp)
				.padding(bottom = contentPadding.calculateBottomPadding() + 18.dp),
			shape = RoundedCornerShape(24.dp),
			color = MaterialTheme.colorScheme.meloGlassSurface,
			border = BorderStroke(1.dp, MaterialTheme.colorScheme.meloSubtleBorder)
		) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 14.dp, vertical = 10.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween
			) {
				Column(
					modifier = Modifier.weight(1f),
					verticalArrangement = Arrangement.spacedBy(2.dp)
				) {
					Text(
						text = song?.title ?: "暂未播放",
						style = MaterialTheme.typography.titleSmall,
						fontWeight = FontWeight.SemiBold,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis
					)
					Text(
						text = song?.artistName ?: "--",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis
					)
				}

				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(6.dp)
				) {
					IconButton(onClick = onPrevious, enabled = song != null) {
						Icon(
							imageVector = Icons.Filled.SkipPrevious,
							contentDescription = stringResource(R.string.action_previous_song)
						)
					}
					Surface(
						onClick = onTogglePlay,
						enabled = song != null,
						shape = CircleShape,
						color = MaterialTheme.colorScheme.primary,
						contentColor = MaterialTheme.colorScheme.onPrimary,
						modifier = Modifier.size(40.dp)
					) {
						Box(contentAlignment = Alignment.Center) {
							Icon(
								painter = playPauseIconPainter(isPaused),
								contentDescription = null,
								modifier = Modifier.size(22.dp)
							)
						}
					}
					IconButton(onClick = onNext, enabled = song != null) {
						Icon(
							imageVector = Icons.Filled.SkipNext,
							contentDescription = stringResource(R.string.action_next_song)
						)
					}
				}
			}
		}
	}
}

@Composable
private fun InlineLyricsProgressHost(
	player: MediaPlayer,
	isActive: Boolean,
	data: LyricsResult?,
	duration: Duration,
	lyricsOffsetMs: Int,
	onRefresh: () -> Unit
) {
	val progress = if (isActive) {
		player.progressFlow.collectAsStateWithLifecycle().value
	} else {
		player.progressFlow.value
	}
	val currentDuration = (duration * progress.toDouble() + lyricsOffsetMs.milliseconds)
		.coerceAtLeast(Duration.ZERO)

	LyricsScreenContent(
		data = data,
		onRefresh = onRefresh,
		isSelecting = false,
		selectedIndices = persistentListOf(),
		onAddSelectedIndex = {},
		onRemoveSelectedIndex = {},
		onRestartAtIndex = {},
		duration = duration,
		currentDuration = currentDuration,
		contentPadding = PaddingValues(top = 12.dp, bottom = 20.dp)
	)
}
