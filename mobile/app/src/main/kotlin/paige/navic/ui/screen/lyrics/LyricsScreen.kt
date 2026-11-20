/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.screen.lyrics

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalSheetState
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.model.DomainSong
import paige.navic.domain.model.lyrics.LyricsResult
import paige.navic.domain.model.settings.ToolbarPosition
import paige.navic.playback.MediaPlayer
import paige.navic.ui.component.common.ErrorBox
import paige.navic.ui.component.layout.SheetScaffold
import paige.navic.ui.core.UiState
import paige.navic.ui.navigation.PersistentViewModelStoreOwner
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screen.lyrics.component.LyricsScreenContent
import paige.navic.ui.screen.lyrics.component.LyricsScreenLoadingView
import paige.navic.ui.screen.lyrics.component.LyricsScreenPlaceholder
import paige.navic.ui.screen.lyrics.component.LyricsScreenToolbar
import paige.navic.ui.screen.lyrics.component.LyricsStyleBottomSheet
import paige.navic.ui.screen.lyrics.dialog.LyricsShareSheet
import paige.navic.ui.screen.lyrics.viewmodel.LyricsScreenViewModel
import paige.navic.ui.theme.tabularNums
import paige.navic.ui.util.KeepScreenOn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsScreen(
	song: DomainSong?
) {
	val backStack = LocalNavStack.current

	val player = koinInject<MediaPlayer>()

	val viewModel = koinViewModel<LyricsScreenViewModel>(
		key = song?.id,
		parameters = { parametersOf(song?.id) },
		viewModelStoreOwner = koinInject<PersistentViewModelStoreOwner>()
	)
	val lyricsState by viewModel.lyricsState.collectAsStateWithLifecycle()

	val preferenceManager = koinInject<PreferenceManager>()
	var isSelecting by rememberSaveable { mutableStateOf(false) }
	val selectedIndices = rememberSaveable { mutableStateListOf<Int>() }
	var wasPlayingBeforeSelection by rememberSaveable { mutableStateOf(false) }
	var shareSheetOpen by rememberSaveable { mutableStateOf(false) }
	var moreMenuExpanded by rememberSaveable { mutableStateOf(false) }
	var styleSheetOpen by rememberSaveable { mutableStateOf(false) }

	val song = song ?: return LyricsScreenPlaceholder(
		onRefresh = { viewModel.refreshResults() }
	)
	val duration = song.duration

	val spatialSpec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
	val effectSpec = MaterialTheme.motionScheme.slowEffectsSpec<Float>()

	val toggleIsSelecting = {
		if (isSelecting) {
			isSelecting = false
			selectedIndices.clear()
			if (wasPlayingBeforeSelection) {
				player.resume()
			}
		} else {
			wasPlayingBeforeSelection = !player.uiState.value.isPaused
			player.pause()
			isSelecting = true
		}
	}

	val sheetState = LocalSheetState.current
	val closeScope = rememberCoroutineScope()
	val animateToDismiss = {
		closeScope.launch {
			sheetState.hide()
		}.invokeOnCompletion {
			if (!sheetState.isVisible) {
				backStack.remove(Screen.Lyrics)
			}
		}
	}

	if (preferenceManager.lyricsKeepAlive) {
		KeepScreenOn()
	}

	SheetScaffold(
		toolbar = { windowInsets ->
			LyricsScreenToolbar(
				onDismissRequest = { animateToDismiss() },
				onShare = { shareSheetOpen = true },
				isSelecting = isSelecting,
				toggleIsSelecting = toggleIsSelecting,
				windowInsets = windowInsets,
				selectedIndices = selectedIndices.toImmutableList(),
				onOpenStyleSheet = { styleSheetOpen = true },
				onOpenMoreMenu = { moreMenuExpanded = true },
				moreMenuContent = {
					// 歌词时间轴微调收纳进右上角 ⋯ 更多菜单内，不在顶栏暴露一级按钮
					DropdownMenu(
						expanded = moreMenuExpanded,
						onDismissRequest = { moreMenuExpanded = false }
					) {
						val lyricsOffsetMs = preferenceManager.playerLyricsOffsetMs
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
									onClick = {
										preferenceManager.playerLyricsOffsetMs =
											(preferenceManager.playerLyricsOffsetMs - 100).coerceIn(-5000, 5000)
									},
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
									onClick = {
										preferenceManager.playerLyricsOffsetMs = 0
									},
									shape = RoundedCornerShape(10.dp),
									color = MaterialTheme.colorScheme.primaryContainer
								) {
									val offsetText = if (lyricsOffsetMs > 0) "+${lyricsOffsetMs}ms" else "${lyricsOffsetMs}ms"
									Text(
										text = if (lyricsOffsetMs == 0) "0ms 重置" else "$offsetText 重置",
										style = MaterialTheme.typography.labelSmall.tabularNums(),
										color = MaterialTheme.colorScheme.onPrimaryContainer,
										modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
									)
								}
								Surface(
									onClick = {
										preferenceManager.playerLyricsOffsetMs =
											(preferenceManager.playerLyricsOffsetMs + 100).coerceIn(-5000, 5000)
									},
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
							text = { Text("刷新歌词") },
							onClick = {
								moreMenuExpanded = false
								viewModel.refreshResults()
							}
						)
					}
				}
			)
		},
		toolbarPosition = ToolbarPosition.Top
	) { contentPadding ->
		AnimatedContent(
			targetState = lyricsState,
			modifier = Modifier.fillMaxSize(),
			transitionSpec = {
				ContentTransform(
					targetContentEnter = fadeIn(effectSpec) + scaleIn(spatialSpec, 0.8f),
					initialContentExit = fadeOut(effectSpec) + scaleOut(spatialSpec)
				)
			},
		) { lyricsState ->
			when (lyricsState) {
				is UiState.Error -> ErrorBox(
					error = lyricsState,
					modifier = Modifier.wrapContentSize(),
					onRetry = { viewModel.refreshResults() }
				)

				is UiState.Loading -> LyricsScreenLoadingView()
				is UiState.Success -> {
					LyricsProgressSyncedContent(
						player = player,
						preferenceManager = preferenceManager,
						data = lyricsState.data,
						onRefresh = { viewModel.refreshResults() },
						isSelecting = isSelecting,
						selectedIndices = selectedIndices.toImmutableList(),
						onAddSelectedIndex = { idx -> selectedIndices.add(idx) },
						onRemoveSelectedIndex = { idx -> selectedIndices.remove(idx) },
						onRestartAtIndex = { idx ->
							selectedIndices.clear()
							selectedIndices.add(idx)
						},
						duration = duration,
						contentPadding = contentPadding
					)
				}
			}
		}

		if (shareSheetOpen) {
			val lyricsList = lyricsState.data?.lines?.map { line ->
				(line.time?.inWholeMilliseconds ?: 0L) to line.text
			}

			if (lyricsList != null) {
				val sortedIndices = selectedIndices.sorted()
				val stringsToShare = sortedIndices.mapNotNull { index ->
					lyricsList.getOrNull(index)?.second
				}.toImmutableList()

				LyricsShareSheet(
					song = song,
					selectedLyrics = stringsToShare,
					onDismiss = { shareSheetOpen = false },
					onShare = {
						shareSheetOpen = false
						isSelecting = false
						selectedIndices.clear()
					}
				)
			}
		}

		LyricsStyleBottomSheet(
			presented = styleSheetOpen,
			onDismissRequest = { styleSheetOpen = false }
		)
	}
}

@Composable
private fun LyricsProgressSyncedContent(
	player: MediaPlayer,
	preferenceManager: PreferenceManager,
	data: LyricsResult?,
	onRefresh: () -> Unit,
	isSelecting: Boolean,
	selectedIndices: ImmutableList<Int>,
	onAddSelectedIndex: (Int) -> Unit,
	onRemoveSelectedIndex: (Int) -> Unit,
	onRestartAtIndex: (Int) -> Unit,
	duration: Duration,
	contentPadding: PaddingValues
) {
	val progressState by player.progressFlow.collectAsStateWithLifecycle()
	val lyricsOffsetMs = preferenceManager.playerLyricsOffsetMs
	val currentDuration = (duration * progressState.toDouble() + lyricsOffsetMs.milliseconds)
		.coerceAtLeast(Duration.ZERO)

	LyricsScreenContent(
		data = data,
		onRefresh = onRefresh,
		isSelecting = isSelecting,
		selectedIndices = selectedIndices,
		onAddSelectedIndex = onAddSelectedIndex,
		onRemoveSelectedIndex = onRemoveSelectedIndex,
		onRestartAtIndex = onRestartAtIndex,
		duration = duration,
		currentDuration = currentDuration,
		contentPadding = contentPadding
	)
}
