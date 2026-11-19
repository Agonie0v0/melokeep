@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package paige.navic.melokeep

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import io.github.agonie0v0.melokeep.shared.MeloAvailableSource
import io.github.agonie0v0.melokeep.shared.MeloQuality
import io.github.agonie0v0.melokeep.shared.MeloQualityFilter
import io.github.agonie0v0.melokeep.shared.MeloSearchController
import io.github.agonie0v0.melokeep.shared.MeloSearchUiState
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import io.github.agonie0v0.melokeep.shared.MeloSourceStatus
import io.github.agonie0v0.melokeep.shared.MeloTrack
import io.github.agonie0v0.melokeep.shared.MeloTrackGroup
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalSnackBarState
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.model.DomainSong
import paige.navic.playback.MediaPlayer
import paige.navic.ui.component.common.CoverArt
import paige.navic.ui.component.common.MarqueeText
import paige.navic.ui.component.common.Waveform
import paige.navic.ui.component.sheet.ModalBottomSheet
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.filled.Settings
import paige.navic.ui.icons.outlined.Check
import paige.navic.ui.icons.outlined.Checklist
import paige.navic.ui.icons.outlined.Close
import paige.navic.ui.icons.outlined.Download
import paige.navic.ui.icons.outlined.KeyboardArrowDown
import paige.navic.ui.icons.outlined.QueuePlayNext
import paige.navic.ui.icons.outlined.Refresh
import paige.navic.ui.icons.outlined.Search
import paige.navic.ui.theme.MeloBottomChromeClearance
import paige.navic.ui.theme.defaultFont
import paige.navic.ui.theme.positive
import paige.navic.ui.theme.tabularNums
import paige.navic.util.toHoursMinutesSeconds

private val QUICK_HOT_KEYWORDS = listOf(
	"Ambient",
	"Lo-Fi",
	"Synthwave",
	"Piano",
	"Acoustic",
	"Electronic",
	"Jazz",
	"Classical"
)

private enum class DiscoverMode {
	SEARCH,
	AI
}

private val DISCOVER_MODE_TABS = listOf(
	DiscoverMode.SEARCH to "全网搜歌",
	DiscoverMode.AI to "AI 助手"
)

/**
 * 探索页（双模融合：全网多源搜歌 + AI 助手）：
 * - 极简顶栏 + [全网搜歌 | AI 助手] 双模发丝胶囊零销毁保活切换
 * - 同名曲目折叠分组（groupedTracks）、音源筛选与多选批量入库
 * - 精准局部订阅播放器状态防止进度跳秒重组，底部预留 MeloBottomChromeClearance
 */
@Composable
fun MeloDiscoverScreen(
	nested: Boolean = false,
	onOpenSettings: (() -> Unit)? = null
) {
	val controller = koinInject<MeloSearchController>()
	val settingsStore = koinInject<MeloSettingsStore>()
	val player = koinInject<MediaPlayer>()
	val preferenceManager = koinInject<PreferenceManager>()
	val backStack = LocalNavStack.current
	val snackBarState = LocalSnackBarState.current
	val focusManager = LocalFocusManager.current
	val scope = rememberCoroutineScope()

	val state by controller.state.collectAsStateWithLifecycle()
	val serverConfig by settingsStore.config.collectAsStateWithLifecycle()
	val openSettings = rememberOpenMeloSettings(onOpenSettings)
	val popBack = dropUnlessResumed {
		if (backStack.size > 1) {
			backStack.removeLastOrNull()
		}
	}

	// 性能铁律：仅订阅 currentSong.id 与 isPaused，阻断播放进度跳秒引发列表重组
	val playbackTrackState by remember(player) {
		player.uiState
			.map { it.currentSong?.id to it.isPaused }
			.distinctUntilChanged()
	}.collectAsStateWithLifecycle(initialValue = null to true)
	val (currentSongId, isPlayerPaused) = playbackTrackState

	var mode by rememberSaveable { mutableStateOf(DiscoverMode.SEARCH) }
	var hasVisitedSearch by rememberSaveable { mutableStateOf(mode == DiscoverMode.SEARCH) }
	var hasVisitedAi by rememberSaveable { mutableStateOf(mode == DiscoverMode.AI) }
	var versionSheetTrack by remember { mutableStateOf<MeloTrack?>(null) }
	var sourcePickerOpen by remember { mutableStateOf(false) }
	var sourceDetailsExpanded by rememberSaveable { mutableStateOf(false) }
	var aiSessionSheetOpen by remember { mutableStateOf(false) }

	LaunchedEffect(mode) {
		when (mode) {
			DiscoverMode.SEARCH -> hasVisitedSearch = true
			DiscoverMode.AI -> hasVisitedAi = true
		}
	}

	MeloFeedbackObserver(
		errorMessage = state.errorMessage,
		noticeMessage = state.noticeMessage,
		onClearError = controller::clearError,
		onClearNotice = controller::clearNotice
	)

	LaunchedEffect(serverConfig.isAuthenticated) {
		if (serverConfig.isAuthenticated) {
			controller.loadSources()
		}
	}

	val submitSearch: (String, Boolean) -> Unit = { kw, refresh ->
		focusManager.clearFocus()
		controller.updateKeyword(kw)
		controller.search(keyword = kw, refresh = refresh)
	}

	val navBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
	val searchBottomClearance = if (nested) 24.dp + navBottomPadding else MeloBottomChromeClearance + navBottomPadding
	val aiBottomClearance = if (nested) 16.dp else MeloBottomChromeClearance

	Surface(
		modifier = Modifier.fillMaxSize(),
		color = MaterialTheme.colorScheme.background
	) {
		Column(modifier = Modifier.fillMaxSize()) {
			DiscoverUnifiedTopBar(
				nested = nested,
				mode = mode,
				isAuthenticated = serverConfig.isAuthenticated,
				onBack = popBack,
				onSelectMode = { nextMode ->
					if (mode != nextMode) {
						focusManager.clearFocus()
						mode = nextMode
					}
				},
				onOpenSettings = openSettings
			)

			// 懒加载常驻双模槽位：首次访问时挂载，切换搜索/AI 瞬时复用且保留滚动位置与输入状态
			val showSearch = mode == DiscoverMode.SEARCH
			val showAi = mode == DiscoverMode.AI
			val mountSearch = hasVisitedSearch || showSearch
			val mountAi = hasVisitedAi || showAi

			Box(
				modifier = Modifier
					.weight(1f)
					.fillMaxWidth()
			) {
				if (mountSearch) {
					DiscoverKeepAliveSlot(visible = showSearch) {
						if (!serverConfig.isAuthenticated) {
							Box(
								modifier = Modifier
									.fillMaxSize()
									.padding(bottom = searchBottomClearance),
								contentAlignment = Alignment.Center
							) {
								MeloQuietEmptyState(
									message = "未连接 MeloKeep 服务",
									icon = MeloIcons.Cloud,
									actionLabel = "前往设置",
									onAction = openSettings
								)
							}
						} else {
							DiscoverSearchModeBody(
								state = state,
								bottomClearance = searchBottomClearance,
								nested = nested,
								coverShape = preferenceManager.coverArtShape.decreasedShape,
								currentSongId = currentSongId,
								isPlayerPaused = isPlayerPaused,
								sourceDetailsExpanded = sourceDetailsExpanded,
								onToggleSourceDetails = { sourceDetailsExpanded = !sourceDetailsExpanded },
								onKeywordChange = controller::updateKeyword,
								onSearch = { submitSearch(state.keyword, false) },
								onForceRefresh = { submitSearch(state.keyword, true) },
								onStopSearch = controller::stopSearch,
								onClearKeyword = { controller.updateKeyword("") },
								onQualityChange = controller::setQualityFilter,
								onOpenSourcePicker = { sourcePickerOpen = true },
								onToggleMultiSelect = { controller.toggleMultiSelect() },
								onExitMultiSelect = { controller.toggleMultiSelect(false) },
								onSelectAllFiltered = controller::selectAllFiltered,
								onClearSelection = controller::clearSelection,
								onBatchDownload = {
									scope.launch {
										controller.enqueueSelectedDownloads()
									}
								},
								onClearHistory = controller::clearHistory,
								onPickHistoryOrHot = { kw -> submitSearch(kw, false) },
								onPlayTracks = { songs, startIndex ->
									player.playNow(songs, startIndex)
								},
								onTogglePlayPause = { player.togglePlay() },
								onPlayNext = { song -> player.playNextSingle(song) },
								onToggleTrackSelection = controller::toggleTrackSelection,
								onEnterMultiSelectWithTrack = { trackId ->
									if (!state.multiSelectMode) {
										controller.toggleMultiSelect(true)
									}
									controller.toggleTrackSelection(trackId)
								},
								onOpenDownloadSheet = { track ->
									versionSheetTrack = track
								}
							)
						}
					}
				}

				if (mountAi) {
					DiscoverKeepAliveSlot(visible = showAi) {
						MeloAiAssistantContent(
							nested = nested,
							bottomClearance = aiBottomClearance,
							onOpenSettings = openSettings,
							sessionSheetOpen = aiSessionSheetOpen,
							onSessionSheetOpenChange = { aiSessionSheetOpen = it },
							onSearchTrackInDiscover = { query ->
								focusManager.clearFocus()
								mode = DiscoverMode.SEARCH
								controller.updateKeyword(query)
								controller.search(keyword = query, refresh = false)
							}
						)
					}
				}
			}
		}
	}

	versionSheetTrack?.let { targetTrack ->
		TrackVersionsBottomSheet(
			track = targetTrack,
			controller = controller,
			hostNavBottom = navBottomPadding,
			onDismissRequest = { versionSheetTrack = null },
			onDownloadSubmitted = { title, qualityLabel ->
				versionSheetTrack = null
				scope.launch {
					snackBarState.currentSnackbarData?.dismiss()
					snackBarState.showSnackbar("已提交下载：$title（$qualityLabel）")
				}
			}
		)
	}

	if (sourcePickerOpen) {
		SourcePickerBottomSheet(
			availableSources = state.availableSources,
			selectedSources = state.selectedSources,
			hostNavBottom = navBottomPadding,
			onToggleSource = controller::toggleSource,
			onSelectAll = {
				controller.setSelectedSources(state.availableSources.map { it.id })
			},
			onClearAll = { controller.setSelectedSources(emptyList()) },
			onDismissRequest = { sourcePickerOpen = false }
		)
	}
}

@Composable
private fun DiscoverKeepAliveSlot(
	visible: Boolean,
	modifier: Modifier = Modifier,
	content: @Composable () -> Unit
) {
	Layout(
		content = content,
		modifier = modifier
	) { measurables, constraints ->
		if (!visible) {
			layout(0, 0) {}
		} else {
			val childConstraints = if (constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
				Constraints.fixed(constraints.maxWidth, constraints.maxHeight)
			} else {
				constraints
			}
			val placeables = measurables.map { it.measure(childConstraints) }
			val width = placeables.maxOfOrNull { it.width }
				?.coerceIn(constraints.minWidth, constraints.maxWidth)
				?: constraints.minWidth
			val height = placeables.maxOfOrNull { it.height }
				?.coerceIn(constraints.minHeight, constraints.maxHeight)
				?: constraints.minHeight
			layout(width, height) {
				placeables.forEach { it.place(0, 0) }
			}
		}
	}
}

@Composable
private fun DiscoverUnifiedTopBar(
	nested: Boolean,
	mode: DiscoverMode,
	isAuthenticated: Boolean,
	onBack: () -> Unit,
	onSelectMode: (DiscoverMode) -> Unit,
	onOpenSettings: () -> Unit
) {
	Column(modifier = Modifier.fillMaxWidth()) {
		MeloCleanTopBar(
			title = "探索",
			onBack = if (nested) onBack else null,
			trailing = {
				DiscoverConnectionPill(
					isAuthenticated = isAuthenticated,
					onClick = onOpenSettings
				)
			}
		)
		DiscoverDualModePill(
			mode = mode,
			onSelectMode = onSelectMode,
			modifier = Modifier
				.fillMaxWidth()
				.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
		)
	}
}

@Composable
private fun DiscoverConnectionPill(
	isAuthenticated: Boolean,
	onClick: () -> Unit
) {
	Surface(
		onClick = onClick,
		shape = CircleShape,
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = BorderStroke(
			width = MeloHairlineWidth,
			color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
		)
	) {
		Row(
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp)
		) {
			Box(
				modifier = Modifier
					.size(6.dp)
					.background(
						color = if (isAuthenticated) {
							MaterialTheme.colorScheme.positive
						} else {
							MaterialTheme.colorScheme.error
						},
						shape = CircleShape
					)
			)
			Text(
				text = if (isAuthenticated) "在线" else "离线",
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1
			)
			Icon(
				imageVector = Icons.Filled.Settings,
				contentDescription = "MeloKeep 设置",
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(14.dp)
			)
		}
	}
}

@Composable
private fun DiscoverDualModePill(
	mode: DiscoverMode,
	onSelectMode: (DiscoverMode) -> Unit,
	modifier: Modifier = Modifier
) {
	Surface(
		modifier = modifier,
		shape = CircleShape,
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = BorderStroke(
			width = MeloHairlineWidth,
			color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
		)
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(3.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(3.dp)
		) {
			DISCOVER_MODE_TABS.forEach { (tabMode, label) ->
				val selected = mode == tabMode
				Surface(
					modifier = Modifier
						.weight(1f)
						.clip(CircleShape)
						.clickable { onSelectMode(tabMode) },
					shape = CircleShape,
					color = if (selected) {
						MaterialTheme.colorScheme.primary
					} else {
						Color.Transparent
					}
				) {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(horizontal = 10.dp, vertical = 7.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)
					) {
						if (tabMode == DiscoverMode.AI) {
							Icon(
								imageVector = MeloIcons.Sparkles,
								contentDescription = null,
								tint = if (selected) {
									MaterialTheme.colorScheme.onPrimary
								} else {
									MaterialTheme.colorScheme.onSurfaceVariant
								},
								modifier = Modifier.size(14.dp)
							)
						}
						Text(
							text = label,
							style = MaterialTheme.typography.labelMedium.copy(
								fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
							),
							color = if (selected) {
								MaterialTheme.colorScheme.onPrimary
							} else {
								MaterialTheme.colorScheme.onSurfaceVariant
							},
							maxLines = 1
						)
					}
				}
			}
		}
	}
}

@Composable
private fun DiscoverSearchModeBody(
	state: MeloSearchUiState,
	bottomClearance: Dp,
	nested: Boolean,
	coverShape: Shape,
	currentSongId: String?,
	isPlayerPaused: Boolean,
	sourceDetailsExpanded: Boolean,
	onToggleSourceDetails: () -> Unit,
	onKeywordChange: (String) -> Unit,
	onSearch: () -> Unit,
	onForceRefresh: () -> Unit,
	onStopSearch: () -> Unit,
	onClearKeyword: () -> Unit,
	onQualityChange: (MeloQualityFilter) -> Unit,
	onOpenSourcePicker: () -> Unit,
	onToggleMultiSelect: () -> Unit,
	onExitMultiSelect: () -> Unit,
	onSelectAllFiltered: () -> Unit,
	onClearSelection: () -> Unit,
	onBatchDownload: () -> Unit,
	onClearHistory: () -> Unit,
	onPickHistoryOrHot: (String) -> Unit,
	onPlayTracks: (List<DomainSong>, Int) -> Unit,
	onTogglePlayPause: () -> Unit,
	onPlayNext: (DomainSong) -> Unit,
	onToggleTrackSelection: (String) -> Unit,
	onEnterMultiSelectWithTrack: (String) -> Unit,
	onOpenDownloadSheet: (MeloTrack) -> Unit
) {
	val listState = rememberLazyListState()
	val expandedGroups = remember(state.keyword) {
		mutableStateMapOf<String, Boolean>()
	}

	val groupedTracks = state.groupedTracks
	val primaryTracks = remember(groupedTracks) { groupedTracks.map { it.primary } }
	val primarySongs = remember(primaryTracks) { MeloTrackMapper.toDomainSongs(primaryTracks) }
	val filteredTracksCount = remember(state.tracks, state.qualityFilter) {
		state.filteredTracks.size
	}

	val sourceLabels = remember(state.availableSources) {
		state.availableSources.associate { it.id to it.label.ifBlank { it.id } }
	}

	val totalBottomPadding = bottomClearance + if (state.multiSelectMode) 68.dp else 0.dp

	Box(modifier = Modifier.fillMaxSize()) {
		Column(modifier = Modifier.fillMaxSize()) {
			DiscoverUnifiedSearchCapsule(
				keyword = state.keyword,
				searching = state.searching,
				selectedSourceCount = state.selectedSources.size,
				totalSourceCount = state.availableSources.size,
				onKeywordChange = onKeywordChange,
				onSearch = onSearch,
				onStop = onStopSearch,
				onClear = onClearKeyword,
				onOpenSourcePicker = onOpenSourcePicker
			)

			DiscoverFilterAndStatusStrip(
				qualityFilter = state.qualityFilter,
				multiSelectMode = state.multiSelectMode,
				hasResults = state.tracks.isNotEmpty(),
				searching = state.searching,
				sourceStatuses = state.sourceStatuses,
				totalTracksCount = filteredTracksCount,
				sourceDetailsExpanded = sourceDetailsExpanded,
				onQualityChange = onQualityChange,
				onToggleMultiSelect = onToggleMultiSelect,
				onToggleSourceDetails = onToggleSourceDetails
			)

			AnimatedVisibility(visible = sourceDetailsExpanded && state.sourceStatuses.isNotEmpty()) {
				SourceProgressRow(
					statuses = state.sourceStatuses,
					sourceLabels = sourceLabels
				)
			}

			when {
				state.tracks.isEmpty() && !state.searching -> {
					LazyColumn(
						modifier = Modifier.weight(1f),
						contentPadding = PaddingValues(
							start = 16.dp,
							end = 16.dp,
							top = 8.dp,
							bottom = totalBottomPadding
						),
						verticalArrangement = Arrangement.spacedBy(18.dp)
					) {
						if (state.searchHistory.isNotEmpty()) {
							item(key = "search-history", contentType = "search-history") {
								Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
									MeloSectionLabel(
										title = "最近搜索",
										trailing = {
											Text(
												text = "清空",
												style = MaterialTheme.typography.labelMedium,
												color = MaterialTheme.colorScheme.primary,
												modifier = Modifier
													.clip(CircleShape)
													.clickable(onClick = onClearHistory)
													.padding(horizontal = 6.dp, vertical = 2.dp)
											)
										}
									)
									FlowRow(
										horizontalArrangement = Arrangement.spacedBy(8.dp),
										verticalArrangement = Arrangement.spacedBy(8.dp)
									) {
										state.searchHistory.forEach { item ->
											DiscoverQuietTagChip(
												label = item,
												onClick = { onPickHistoryOrHot(item) }
											)
										}
									}
								}
							}
						}

						item(key = "hot-keywords", contentType = "hot-keywords") {
							Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
								MeloSectionLabel(title = "快捷热搜")
								FlowRow(
									horizontalArrangement = Arrangement.spacedBy(8.dp),
									verticalArrangement = Arrangement.spacedBy(8.dp)
								) {
									QUICK_HOT_KEYWORDS.forEach { hot ->
										DiscoverQuietTagChip(
											label = hot,
											onClick = { onPickHistoryOrHot(hot) }
										)
									}
								}
							}
						}

						if (state.keyword.isNotBlank()) {
							item(key = "empty-hint", contentType = "empty") {
								MeloQuietEmptyState(
									message = "未找到「${state.keyword}」相关曲目",
									icon = Icons.Outlined.Search,
									actionLabel = "强制刷新",
									onAction = onForceRefresh
								)
							}
						}
					}
				}

				groupedTracks.isEmpty() && state.searching -> {
					Box(
						modifier = Modifier
							.weight(1f)
							.fillMaxWidth()
							.padding(bottom = totalBottomPadding),
						contentAlignment = Alignment.Center
					) {
						Column(
							horizontalAlignment = Alignment.CenterHorizontally,
							verticalArrangement = Arrangement.spacedBy(10.dp)
						) {
							CircularProgressIndicator(
								modifier = Modifier.size(24.dp),
								strokeWidth = 2.dp
							)
							Text(
								text = "正在检索「${state.keyword}」…",
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant
							)
						}
					}
				}

				groupedTracks.isEmpty() -> {
					Box(
						modifier = Modifier
							.weight(1f)
							.fillMaxWidth()
							.padding(bottom = totalBottomPadding),
						contentAlignment = Alignment.Center
					) {
						if (state.tracks.isEmpty()) {
							MeloQuietEmptyState(
								message = "未找到相关曲目",
								icon = Icons.Outlined.Search,
								actionLabel = "重试",
								onAction = onForceRefresh
							)
						} else {
							MeloQuietEmptyState(
								message = "当前音质下无匹配（共 ${state.tracks.size} 首）",
								icon = Icons.Outlined.Search,
								actionLabel = "查看全部",
								onAction = { onQualityChange(MeloQualityFilter.ALL) }
							)
						}
					}
				}

				else -> {
					LazyColumn(
						modifier = Modifier.weight(1f),
						state = listState,
						contentPadding = PaddingValues(
							start = 16.dp,
							end = 16.dp,
							top = 4.dp,
							bottom = totalBottomPadding
						)
					) {
						if (state.keyword.isNotBlank() && !state.searching) {
							item(key = "result-summary", contentType = "header") {
								MeloSectionLabel(
									title = "${groupedTracks.size} 组曲目 · ${filteredTracksCount} 个版本",
									modifier = Modifier.padding(bottom = 8.dp),
									trailing = {
										Row(
											modifier = Modifier
												.clip(CircleShape)
												.clickable(onClick = onForceRefresh)
												.padding(horizontal = 6.dp, vertical = 2.dp),
											verticalAlignment = Alignment.CenterVertically,
											horizontalArrangement = Arrangement.spacedBy(3.dp)
										) {
											Icon(
												imageVector = Icons.Outlined.Refresh,
												contentDescription = null,
												tint = MaterialTheme.colorScheme.primary,
												modifier = Modifier.size(13.dp)
											)
											Text(
												text = "刷新",
												style = MaterialTheme.typography.labelMedium,
												color = MaterialTheme.colorScheme.primary
											)
										}
									}
								)
							}
						}

						itemsIndexed(
							items = groupedTracks,
							key = { _, group -> group.groupKey },
							contentType = { _, _ -> "track-group" }
						) { index, group ->
							val primarySong = primarySongs[index]
							val isExpanded = expandedGroups[group.groupKey] == true
							DiscoverTrackGroupCard(
								group = group,
								primarySong = primarySong,
								coverShape = coverShape,
								isFirst = index == 0,
								isLast = index == groupedTracks.lastIndex,
								multiSelectMode = state.multiSelectMode,
								selectedTrackIds = state.selectedTrackIds,
								enqueuedTrackIds = state.enqueuedTrackIds,
								currentSongId = currentSongId,
								isPlayerPaused = isPlayerPaused,
								expanded = isExpanded,
								onToggleExpand = {
									expandedGroups[group.groupKey] = !isExpanded
								},
								onClickPrimary = {
									if (state.multiSelectMode) {
										onToggleTrackSelection(group.primary.id)
									} else if (currentSongId == primarySong.id) {
										onTogglePlayPause()
									} else {
										onPlayTracks(primarySongs, index)
									}
								},
								onLongClickPrimary = {
									onEnterMultiSelectWithTrack(group.primary.id)
								},
								onClickAlternative = { altTrack ->
									if (state.multiSelectMode) {
										onToggleTrackSelection(altTrack.id)
									} else {
										val altSong = MeloTrackMapper.toDomainSong(altTrack)
										if (currentSongId == altSong.id) {
											onTogglePlayPause()
										} else {
											val groupTracks = listOf(group.primary) + group.alternatives
											val groupSongs = MeloTrackMapper.toDomainSongs(groupTracks)
											val altIdx = groupTracks.indexOfFirst { it.id == altTrack.id }.coerceAtLeast(0)
											onPlayTracks(groupSongs, altIdx)
										}
									}
								},
								onPlayNextTrack = { track ->
									onPlayNext(MeloTrackMapper.toDomainSong(track))
								},
								onOpenDownloadSheet = onOpenDownloadSheet
							)
						}
					}
				}
			}
		}

		// 底部多选批处理坞（避让底栏，有正在播放的悬浮播放舱时自动抬升）
		val multiSelectBottomPadding = when {
			nested -> 16.dp
			currentSongId != null -> MeloBottomChromeClearance
			else -> 86.dp
		}
		AnimatedVisibility(
			visible = state.multiSelectMode,
			enter = slideInVertically { it } + fadeIn(),
			exit = slideOutVertically { it } + fadeOut(),
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.navigationBarsPadding()
				.padding(horizontal = 16.dp)
				.padding(bottom = multiSelectBottomPadding)
		) {
			MultiSelectGlassDock(
				selectedCount = state.selectedTrackIds.size,
				filteredCount = filteredTracksCount,
				onSelectAll = onSelectAllFiltered,
				onClearSelection = onClearSelection,
				onExitMultiSelect = onExitMultiSelect,
				onBatchDownload = onBatchDownload
			)
		}
	}
}

@Composable
private fun DiscoverQuietTagChip(
	label: String,
	onClick: () -> Unit
) {
	Surface(
		onClick = onClick,
		shape = CircleShape,
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = BorderStroke(
			width = MeloHairlineWidth,
			color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
		)
	) {
		Text(
			text = label,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurface,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
		)
	}
}

@Composable
private fun DiscoverUnifiedSearchCapsule(
	keyword: String,
	searching: Boolean,
	selectedSourceCount: Int,
	totalSourceCount: Int,
	onKeywordChange: (String) -> Unit,
	onSearch: () -> Unit,
	onStop: () -> Unit,
	onClear: () -> Unit,
	onOpenSourcePicker: () -> Unit
) {
	Surface(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 2.dp),
		shape = RoundedCornerShape(14.dp),
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = BorderStroke(
			width = MeloHairlineWidth,
			color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
		)
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.height(44.dp)
				.padding(start = 12.dp, end = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp)
		) {
			Icon(
				imageVector = Icons.Outlined.Search,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(18.dp)
			)
			Box(
				modifier = Modifier.weight(1f),
				contentAlignment = Alignment.CenterStart
			) {
				if (keyword.isEmpty()) {
					Text(
						text = "歌名、歌手、专辑或分享链接",
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
						maxLines = 1,
						overflow = TextOverflow.Ellipsis
					)
				}
				BasicTextField(
					value = keyword,
					onValueChange = onKeywordChange,
					singleLine = true,
					textStyle = TextStyle(
						color = MaterialTheme.colorScheme.onSurface,
						fontFamily = defaultFont(),
						fontSize = MaterialTheme.typography.bodyMedium.fontSize
					),
					cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
					keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
					keyboardActions = KeyboardActions(onSearch = { onSearch() }),
					modifier = Modifier.fillMaxWidth()
				)
			}

			if (keyword.isNotEmpty()) {
				IconButton(
					onClick = onClear,
					modifier = Modifier.size(28.dp)
				) {
					Icon(
						imageVector = Icons.Outlined.Close,
						contentDescription = "清空",
						tint = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.size(16.dp)
					)
				}
			}

			if (totalSourceCount > 0) {
				val srcCountText = if (selectedSourceCount in 1 until totalSourceCount) {
					"$selectedSourceCount/$totalSourceCount 源"
				} else {
					"$totalSourceCount 源"
				}
				Surface(
					onClick = onOpenSourcePicker,
					shape = CircleShape,
					color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.75f)
				) {
					Row(
						modifier = Modifier.padding(start = 8.dp, end = 5.dp, top = 4.dp, bottom = 4.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(2.dp)
					) {
						Text(
							text = srcCountText,
							style = MaterialTheme.typography.labelSmall.tabularNums(),
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							maxLines = 1
						)
						Icon(
							imageVector = Icons.Outlined.KeyboardArrowDown,
							contentDescription = "筛选音源",
							tint = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.size(13.dp)
						)
					}
				}
			}

			if (searching) {
				IconButton(
					onClick = onStop,
					modifier = Modifier.size(32.dp)
				) {
					Icon(
						imageVector = MeloIcons.Stop,
						contentDescription = "停止搜索",
						tint = MaterialTheme.colorScheme.error,
						modifier = Modifier.size(18.dp)
					)
				}
			} else {
				val canSearch = keyword.isNotBlank()
				Surface(
					onClick = onSearch,
					enabled = canSearch,
					shape = CircleShape,
					color = if (canSearch) {
						MaterialTheme.colorScheme.primary
					} else {
						MaterialTheme.colorScheme.surfaceContainerHigh
					}
				) {
					Text(
						text = "搜索",
						style = MaterialTheme.typography.labelMedium,
						fontWeight = FontWeight.Medium,
						color = if (canSearch) {
							MaterialTheme.colorScheme.onPrimary
						} else {
							MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
						},
						modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
					)
				}
			}
		}
	}
}

@Composable
private fun DiscoverFilterAndStatusStrip(
	qualityFilter: MeloQualityFilter,
	multiSelectMode: Boolean,
	hasResults: Boolean,
	searching: Boolean,
	sourceStatuses: Map<String, MeloSourceStatus>,
	totalTracksCount: Int,
	sourceDetailsExpanded: Boolean,
	onQualityChange: (MeloQualityFilter) -> Unit,
	onToggleMultiSelect: () -> Unit,
	onToggleSourceDetails: () -> Unit
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp)
	) {
		Row(
			modifier = Modifier
				.weight(1f)
				.horizontalScroll(rememberScrollState()),
			horizontalArrangement = Arrangement.spacedBy(4.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			CompactFilterPill(
				label = "无损优先",
				selected = qualityFilter == MeloQualityFilter.LOSSLESS,
				onClick = { onQualityChange(MeloQualityFilter.LOSSLESS) }
			)
			CompactFilterPill(
				label = "全部",
				selected = qualityFilter == MeloQualityFilter.ALL,
				onClick = { onQualityChange(MeloQualityFilter.ALL) }
			)
			CompactFilterPill(
				label = "高品",
				selected = qualityFilter == MeloQualityFilter.HIGH,
				onClick = { onQualityChange(MeloQualityFilter.HIGH) }
			)
			if (hasResults || multiSelectMode) {
				CompactFilterPill(
					label = if (multiSelectMode) "退出多选" else "多选",
					selected = multiSelectMode,
					leadingIcon = Icons.Outlined.Checklist,
					onClick = onToggleMultiSelect
				)
			}
		}

		if (sourceStatuses.isNotEmpty() || searching) {
			val totalSources = sourceStatuses.size
			val doneSources = remember(sourceStatuses) {
				sourceStatuses.values.count {
					val s = it.state.lowercase()
					s == "complete" || s == "done" || s == "stopped" || s == "error" || s == "failed"
				}
			}
			Surface(
				onClick = onToggleSourceDetails,
				shape = CircleShape,
				color = if (sourceDetailsExpanded) {
					MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
				} else {
					MaterialTheme.colorScheme.surfaceContainer
				},
				border = BorderStroke(
					width = MeloHairlineWidth,
					color = if (sourceDetailsExpanded) {
						MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
					} else {
						MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
					}
				)
			) {
				Row(
					modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(4.dp)
				) {
					if (searching) {
						CircularProgressIndicator(
							modifier = Modifier.size(10.dp),
							strokeWidth = 1.6.dp
						)
					} else {
						Box(
							modifier = Modifier
								.size(6.dp)
								.background(MaterialTheme.colorScheme.positive, CircleShape)
						)
					}
					Text(
						text = "$doneSources/$totalSources · ${totalTracksCount}首",
						style = MaterialTheme.typography.labelSmall.tabularNums(),
						color = if (sourceDetailsExpanded) {
							MaterialTheme.colorScheme.primary
						} else {
							MaterialTheme.colorScheme.onSurfaceVariant
						},
						maxLines = 1
					)
					Icon(
						imageVector = Icons.Outlined.KeyboardArrowDown,
						contentDescription = null,
						tint = if (sourceDetailsExpanded) {
							MaterialTheme.colorScheme.primary
						} else {
							MaterialTheme.colorScheme.onSurfaceVariant
						},
						modifier = Modifier
							.size(13.dp)
							.scale(scaleX = 1f, scaleY = if (sourceDetailsExpanded) -1f else 1f)
					)
				}
			}
		}
	}
}

@Composable
private fun CompactFilterPill(
	label: String,
	selected: Boolean,
	leadingIcon: ImageVector? = null,
	onClick: () -> Unit
) {
	Surface(
		onClick = onClick,
		shape = CircleShape,
		color = if (selected) {
			MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
		} else {
			Color.Transparent
		}
	) {
		Row(
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(4.dp)
		) {
			if (leadingIcon != null) {
				Icon(
					imageVector = leadingIcon,
					contentDescription = null,
					tint = if (selected) {
						MaterialTheme.colorScheme.primary
					} else {
						MaterialTheme.colorScheme.onSurfaceVariant
					},
					modifier = Modifier.size(14.dp)
				)
			}
			Text(
				text = label,
				style = MaterialTheme.typography.labelMedium.copy(
					fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
				),
				color = if (selected) {
					MaterialTheme.colorScheme.primary
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
				maxLines = 1
			)
		}
	}
}

@Composable
private fun SourceProgressRow(
	statuses: Map<String, MeloSourceStatus>,
	sourceLabels: Map<String, String>
) {
	val statusEntries = remember(statuses) { statuses.entries.toList() }
	LazyRow(
		modifier = Modifier
			.fillMaxWidth()
			.padding(bottom = 6.dp),
		contentPadding = PaddingValues(horizontal = 16.dp),
		horizontalArrangement = Arrangement.spacedBy(6.dp)
	) {
		items(
			items = statusEntries,
			key = { it.key },
			contentType = { "source-status" }
		) { (sourceId, status) ->
			val label = sourceLabels[sourceId] ?: translateMeloSourceName(sourceId)
			val stateLower = status.state.lowercase()
			val isError = stateLower == "error" || stateLower == "failed"
			val isSearching = stateLower == "searching"
			val isDone = stateLower == "complete" || stateLower == "done"
			Surface(
				shape = CircleShape,
				color = MaterialTheme.colorScheme.surfaceContainer,
				border = BorderStroke(
					width = MeloHairlineWidth,
					color = if (isError) {
						MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
					} else {
						MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
					}
				)
			) {
				Row(
					modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(5.dp)
				) {
					when {
						isSearching -> {
							CircularProgressIndicator(
								modifier = Modifier.size(10.dp),
								strokeWidth = 1.6.dp,
								color = MaterialTheme.colorScheme.primary
							)
						}
						isDone -> {
							Box(
								modifier = Modifier
									.size(6.dp)
									.background(MaterialTheme.colorScheme.positive, CircleShape)
							)
						}
						isError -> {
							Box(
								modifier = Modifier
									.size(6.dp)
									.background(MaterialTheme.colorScheme.error, CircleShape)
							)
						}
						else -> {
							Box(
								modifier = Modifier
									.size(6.dp)
									.background(MaterialTheme.colorScheme.outlineVariant, CircleShape)
							)
						}
					}
					val countSuffix = status.count?.let { " · $it" }.orEmpty()
					Text(
						text = "$label$countSuffix",
						style = MaterialTheme.typography.labelSmall.tabularNums(),
						color = if (isError) {
							MaterialTheme.colorScheme.error
						} else {
							MaterialTheme.colorScheme.onSurfaceVariant
						},
						maxLines = 1
					)
				}
			}
		}
	}
}

@Composable
private fun MultiSelectGlassDock(
	selectedCount: Int,
	filteredCount: Int,
	onSelectAll: () -> Unit,
	onClearSelection: () -> Unit,
	onExitMultiSelect: () -> Unit,
	onBatchDownload: () -> Unit
) {
	Surface(
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(16.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		border = BorderStroke(
			width = MeloHairlineWidth,
			color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
		),
		tonalElevation = 0.dp,
		shadowElevation = 4.dp
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 14.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween
		) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(6.dp)
			) {
				Text(
					text = "已选 $selectedCount/$filteredCount",
					style = MaterialTheme.typography.labelLarge.tabularNums(),
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.onSurface
				)
				TextButton(
					onClick = if (selectedCount >= filteredCount && filteredCount > 0) onClearSelection else onSelectAll,
					contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
					modifier = Modifier.height(30.dp)
				) {
					Text(if (selectedCount >= filteredCount && filteredCount > 0) "全不选" else "全选")
				}
			}

			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(6.dp)
			) {
				Button(
					onClick = onBatchDownload,
					enabled = selectedCount > 0,
					contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
					modifier = Modifier.height(34.dp)
				) {
					Icon(
						imageVector = Icons.Outlined.Download,
						contentDescription = null,
						modifier = Modifier.size(16.dp)
					)
					Spacer(Modifier.width(4.dp))
					Text("批量入库")
				}
				IconButton(
					onClick = onExitMultiSelect,
					modifier = Modifier.size(32.dp)
				) {
					Icon(
						imageVector = Icons.Outlined.Close,
						contentDescription = "退出多选",
						modifier = Modifier.size(18.dp)
					)
				}
			}
		}
	}
}

@Composable
private fun DiscoverTrackGroupCard(
	group: MeloTrackGroup,
	primarySong: DomainSong,
	coverShape: Shape,
	isFirst: Boolean,
	isLast: Boolean,
	multiSelectMode: Boolean,
	selectedTrackIds: Set<String>,
	enqueuedTrackIds: Set<String>,
	currentSongId: String?,
	isPlayerPaused: Boolean,
	expanded: Boolean,
	onToggleExpand: () -> Unit,
	onClickPrimary: () -> Unit,
	onLongClickPrimary: () -> Unit,
	onClickAlternative: (MeloTrack) -> Unit,
	onPlayNextTrack: (MeloTrack) -> Unit,
	onOpenDownloadSheet: (MeloTrack) -> Unit
) {
	val primary = group.primary
	val isCurrentPrimary = currentSongId == primarySong.id
	val isPrimaryChecked = primary.id in selectedTrackIds
	val isPrimaryEnqueued = primary.id in enqueuedTrackIds
	val primaryQuality = remember(primary.id, primary.qualities, primary.playback) {
		extractTrackQualityBadge(primary)
	}
	val primaryMetaText = remember(
		primary.id,
		primary.artist,
		primary.album,
		primary.durationMs,
		primary.source,
		primary.sizeLabel,
		primaryQuality
	) {
		buildList {
			primary.artist.trim().takeIf { it.isNotEmpty() }?.let(::add)
			primary.album.trim().takeIf { it.isNotEmpty() }?.let(::add)
			if (primary.durationMs > 0L) {
				add(primarySong.duration.toHoursMinutesSeconds())
			}
			primary.source.trim().takeIf { it.isNotEmpty() }?.let {
				add(translateMeloSourceName(it))
			}
			primaryQuality?.first?.takeIf { it.isNotBlank() }?.let(::add)
			primary.sizeLabel?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
		}.joinToString(" · ").ifEmpty { "全网音源" }
	}
	val altSourcesText = remember(group.groupKey, group.alternatives) {
		val altSources = group.alternatives
			.map { translateMeloSourceName(it.source) }
			.distinct()
			.take(3)
			.joinToString(" · ")
		if (altSources.isNotEmpty()) {
			"其他 ${group.alternatives.size} 个音源 · $altSources"
		} else {
			"其他 ${group.alternatives.size} 个备选音源"
		}
	}

	val shape = RoundedCornerShape(
		topStart = if (isFirst) 14.dp else 0.dp,
		topEnd = if (isFirst) 14.dp else 0.dp,
		bottomStart = if (isLast) 14.dp else 0.dp,
		bottomEnd = if (isLast) 14.dp else 0.dp
	)
	val rowIndent = if (multiSelectMode) 52.dp else 70.dp

	Surface(
		modifier = Modifier.fillMaxWidth(),
		shape = shape,
		color = MaterialTheme.colorScheme.surfaceContainer
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.then(
					if (isPrimaryChecked || isCurrentPrimary) {
						Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
					} else {
						Modifier
					}
				)
		) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.combinedClickable(
						onClick = onClickPrimary,
						onLongClick = onLongClickPrimary
					)
					.padding(horizontal = 12.dp, vertical = 10.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(12.dp)
			) {
				if (multiSelectMode) {
					Checkbox(checked = isPrimaryChecked, onCheckedChange = null)
				} else {
					CoverArt(
						coverArtId = primarySong.coverArtId,
						modifier = Modifier.size(46.dp),
						shape = coverShape
					)
				}

				Column(
					modifier = Modifier.weight(1f),
					verticalArrangement = Arrangement.spacedBy(2.dp)
				) {
					MarqueeText(
						text = primarySong.title,
						style = MaterialTheme.typography.bodyMedium.copy(
							fontWeight = FontWeight.Medium,
							color = if (isCurrentPrimary) {
								MaterialTheme.colorScheme.primary
							} else {
								MaterialTheme.colorScheme.onSurface
							}
						)
					)
					MarqueeText(
						text = primaryMetaText,
						style = MaterialTheme.typography.bodySmall.tabularNums().copy(
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
					)
				}

				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(2.dp)
				) {
					if (isCurrentPrimary) {
						Waveform(
							modifier = Modifier.padding(end = 4.dp),
							isPlaying = !isPlayerPaused
						)
					}
					if (isPrimaryEnqueued) {
						Icon(
							imageVector = Icons.Outlined.Check,
							contentDescription = "已入库",
							tint = MaterialTheme.colorScheme.positive,
							modifier = Modifier
								.padding(end = 2.dp)
								.size(15.dp)
						)
					}
					if (!multiSelectMode) {
						IconButton(
							onClick = { onPlayNextTrack(primary) },
							modifier = Modifier.size(32.dp)
						) {
							Icon(
								imageVector = Icons.Outlined.QueuePlayNext,
								contentDescription = "下一首播放",
								tint = MaterialTheme.colorScheme.onSurfaceVariant,
								modifier = Modifier.size(18.dp)
							)
						}
						IconButton(
							onClick = { onOpenDownloadSheet(primary) },
							modifier = Modifier.size(32.dp)
						) {
							Icon(
								imageVector = Icons.Outlined.Download,
								contentDescription = "下载入库",
								tint = if (isPrimaryEnqueued) {
									MaterialTheme.colorScheme.positive
								} else {
									MaterialTheme.colorScheme.primary
								},
								modifier = Modifier.size(18.dp)
							)
						}
					}
				}
			}

			if (group.alternatives.isNotEmpty()) {
				val arrowScale = if (expanded) -1f else 1f
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.clickable(onClick = onToggleExpand)
						.padding(start = rowIndent, end = 12.dp, top = 0.dp, bottom = 8.dp),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically
				) {
					Text(
						text = altSourcesText,
						style = MaterialTheme.typography.labelSmall.tabularNums(),
						color = if (expanded) {
							MaterialTheme.colorScheme.primary
						} else {
							MaterialTheme.colorScheme.onSurfaceVariant
						},
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.weight(1f)
					)
					Icon(
						imageVector = Icons.Outlined.KeyboardArrowDown,
						contentDescription = if (expanded) "收起备选音源" else "展开备选音源",
						tint = if (expanded) {
							MaterialTheme.colorScheme.primary
						} else {
							MaterialTheme.colorScheme.onSurfaceVariant
						},
						modifier = Modifier
							.size(15.dp)
							.scale(scaleX = 1f, scaleY = arrowScale)
					)
				}

				AnimatedVisibility(visible = expanded) {
					Column(
						modifier = Modifier
							.fillMaxWidth()
							.background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.7f))
					) {
						group.alternatives.forEachIndexed { altIdx, alt ->
							key(alt.id.ifBlank { "alt-$altIdx" }) {
								val altSongId = "${MeloTrackMapper.ID_PREFIX}${alt.id}"
								val isCurrentAlt = currentSongId == altSongId
								MeloHairlineDivider(startIndent = rowIndent)
								AlternativeTrackRow(
									track = alt,
									startIndent = rowIndent,
									multiSelectMode = multiSelectMode,
									checked = alt.id in selectedTrackIds,
									enqueued = alt.id in enqueuedTrackIds,
									isCurrent = isCurrentAlt,
									isPlaying = isCurrentAlt && !isPlayerPaused,
									onClick = { onClickAlternative(alt) },
									onPlayNext = { onPlayNextTrack(alt) },
									onOpenDownloadSheet = { onOpenDownloadSheet(alt) }
								)
							}
						}
					}
				}
			}

			if (!isLast) {
				MeloHairlineDivider(startIndent = rowIndent)
			}
		}
	}
}

@Composable
private fun AlternativeTrackRow(
	track: MeloTrack,
	startIndent: Dp,
	multiSelectMode: Boolean,
	checked: Boolean,
	enqueued: Boolean,
	isCurrent: Boolean,
	isPlaying: Boolean,
	onClick: () -> Unit,
	onPlayNext: () -> Unit,
	onOpenDownloadSheet: () -> Unit
) {
	val qualityBadge = remember(track.id, track.qualities, track.playback) {
		extractTrackQualityBadge(track)
	}
	val sourceName = remember(track.source) { translateMeloSourceName(track.source) }
	val inlineText = remember(track.id, track.album, track.sizeLabel, sourceName, qualityBadge) {
		listOfNotNull(
			sourceName.takeIf { it.isNotBlank() },
			track.album.takeIf { it.isNotBlank() },
			qualityBadge?.first?.takeIf { it.isNotBlank() },
			track.sizeLabel?.takeIf { it.isNotBlank() }
		).joinToString(" · ")
	}
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(
				start = if (multiSelectMode) 12.dp else startIndent,
				end = 12.dp,
				top = 8.dp,
				bottom = 8.dp
			),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp)
	) {
		if (multiSelectMode) {
			Checkbox(checked = checked, onCheckedChange = null)
		}

		Text(
			text = inlineText,
			style = MaterialTheme.typography.bodySmall.tabularNums(),
			color = if (isCurrent) {
				MaterialTheme.colorScheme.primary
			} else {
				MaterialTheme.colorScheme.onSurfaceVariant
			},
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f)
		)

		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(2.dp)
		) {
			if (isCurrent) {
				Waveform(
					modifier = Modifier.padding(end = 4.dp),
					isPlaying = isPlaying
				)
			}
			if (enqueued) {
				Icon(
					imageVector = Icons.Outlined.Check,
					contentDescription = "已入库",
					tint = MaterialTheme.colorScheme.positive,
					modifier = Modifier.size(14.dp)
				)
			}
			if (!multiSelectMode) {
				IconButton(
					onClick = onPlayNext,
					modifier = Modifier.size(30.dp)
				) {
					Icon(
						imageVector = Icons.Outlined.QueuePlayNext,
						contentDescription = "下一首播放",
						tint = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.size(16.dp)
					)
				}
				IconButton(
					onClick = onOpenDownloadSheet,
					modifier = Modifier.size(30.dp)
				) {
					Icon(
						imageVector = Icons.Outlined.Download,
						contentDescription = "下载此版本",
						tint = if (enqueued) MaterialTheme.colorScheme.positive else MaterialTheme.colorScheme.primary,
						modifier = Modifier.size(16.dp)
					)
				}
			}
		}
	}
}

@Composable
private fun TrackVersionsBottomSheet(
	track: MeloTrack,
	controller: MeloSearchController,
	hostNavBottom: Dp,
	onDismissRequest: () -> Unit,
	onDownloadSubmitted: (title: String, qualityLabel: String) -> Unit
) {
	var loading by remember(track.id) { mutableStateOf(true) }
	var detailedTrack by remember(track.id) { mutableStateOf(track) }
	var errorText by remember(track.id) { mutableStateOf<String?>(null) }
	var submittingQualityId by remember(track.id) { mutableStateOf<String?>(null) }
	var reloadTrigger by remember(track.id) { mutableIntStateOf(0) }
	val scope = rememberCoroutineScope()

	LaunchedEffect(track.id, reloadTrigger) {
		loading = true
		errorText = null
		val result = controller.fetchTrackVersions(track.id)
		result.onSuccess { fetched ->
			detailedTrack = fetched
			loading = false
		}.onFailure { err ->
			errorText = err.message ?: "获取音质版本失败"
			loading = false
		}
	}

	ModalBottomSheet(
		onDismissRequest = onDismissRequest,
		sheetState = rememberBottomSheetState(
			initialValue = SheetValue.Hidden,
			enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
		),
		sheetTitle = "选择下载音质"
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp)
				.padding(bottom = maxOf(hostNavBottom, 16.dp) + 16.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp)
		) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(12.dp)
			) {
				CoverArt(
					coverArtId = detailedTrack.cover,
					modifier = Modifier.size(46.dp)
				)
				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = detailedTrack.title.ifBlank { "未知曲目" },
						style = MaterialTheme.typography.titleMedium,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis
					)
					Text(
						text = listOfNotNull(
							detailedTrack.artist.takeIf { it.isNotBlank() },
							detailedTrack.source.takeIf { it.isNotBlank() }?.let(::translateMeloSourceName)
						).joinToString(" · "),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis
					)
				}
			}

			MeloHairlineDivider()

			when {
				loading -> {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(vertical = 24.dp),
						horizontalArrangement = Arrangement.Center,
						verticalAlignment = Alignment.CenterVertically
					) {
						CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
						Spacer(Modifier.width(10.dp))
						Text(
							text = "检测可用音质…",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
					}
				}

				errorText != null && detailedTrack.qualities.isEmpty() -> {
					Column(
						modifier = Modifier.fillMaxWidth(),
						verticalArrangement = Arrangement.spacedBy(10.dp)
					) {
						Text(
							text = errorText.orEmpty(),
							color = MaterialTheme.colorScheme.error,
							style = MaterialTheme.typography.bodySmall
						)
						Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
							OutlinedButton(onClick = { reloadTrigger++ }) {
								Text("重试")
							}
							Button(
								enabled = submittingQualityId == null,
								onClick = {
									submittingQualityId = "__default__"
									scope.launch {
										val res = controller.enqueueDownload(detailedTrack, null)
										submittingQualityId = null
										if (res.isSuccess) {
											onDownloadSubmitted(detailedTrack.title, "默认音质")
										}
									}
								}
							) {
								Text("按默认音质下载")
							}
						}
					}
				}

				else -> {
					val qualities: List<MeloQuality> = detailedTrack.qualities
					if (qualities.isEmpty()) {
						MeloQuietEmptyState(
							message = "未返回可选音质列表",
							actionLabel = "按默认最佳音质下载",
							onAction = {
								if (submittingQualityId == null) {
									submittingQualityId = "__default__"
									scope.launch {
										val res = controller.enqueueDownload(detailedTrack, null)
										submittingQualityId = null
										if (res.isSuccess) {
											onDownloadSubmitted(detailedTrack.title, "默认最佳音质")
										}
									}
								}
							}
						)
					} else {
						MeloGroupedCard {
							qualities.forEachIndexed { index, quality ->
								val displayLabel = quality.label.ifBlank {
									listOfNotNull(
										quality.codec.takeIf { it.isNotBlank() }?.uppercase(),
										quality.bitrate?.let { "${it}k" }
									).joinToString(" ").ifBlank { quality.id }
								}
								val subParts = buildList {
									quality.codec.takeIf { it.isNotBlank() }?.uppercase()?.let(::add)
									quality.bitrate?.let { add("$it kbps") }
									quality.availability.takeIf { it.isNotBlank() }?.let(::add)
									quality.evidence.takeIf { it.isNotBlank() }?.let(::add)
								}
								Row(
									modifier = Modifier
										.fillMaxWidth()
										.clickable(enabled = submittingQualityId == null) {
											submittingQualityId = quality.id
											scope.launch {
												val res = controller.enqueueDownload(detailedTrack, quality.id)
												submittingQualityId = null
												if (res.isSuccess) {
													onDownloadSubmitted(detailedTrack.title, displayLabel)
												}
											}
										}
										.padding(horizontal = 14.dp, vertical = 12.dp),
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.spacedBy(10.dp)
								) {
									Column(
										modifier = Modifier.weight(1f),
										verticalArrangement = Arrangement.spacedBy(2.dp)
									) {
										Row(
											verticalAlignment = Alignment.CenterVertically,
											horizontalArrangement = Arrangement.spacedBy(6.dp)
										) {
											Text(
												text = displayLabel,
												style = MaterialTheme.typography.bodyMedium,
												fontWeight = FontWeight.Medium,
												color = MaterialTheme.colorScheme.onSurface
											)
											if (quality.lossless) {
												Surface(
													shape = RoundedCornerShape(6.dp),
													color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
												) {
													Text(
														text = "无损",
														style = MaterialTheme.typography.labelSmall,
														color = MaterialTheme.colorScheme.primary,
														modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
													)
												}
											}
										}
										if (subParts.isNotEmpty()) {
											Text(
												text = subParts.joinToString(" · "),
												style = MaterialTheme.typography.bodySmall.tabularNums(),
												color = MaterialTheme.colorScheme.onSurfaceVariant,
												maxLines = 1,
												overflow = TextOverflow.Ellipsis
											)
										}
									}
									if (submittingQualityId == quality.id) {
										CircularProgressIndicator(
											modifier = Modifier.size(18.dp),
											strokeWidth = 2.dp
										)
									} else {
										Icon(
											imageVector = Icons.Outlined.Download,
											contentDescription = "下载",
											tint = MaterialTheme.colorScheme.primary,
											modifier = Modifier.size(19.dp)
										)
									}
								}
								if (index < qualities.lastIndex) {
									MeloHairlineDivider(startIndent = 14.dp)
								}
							}
						}
					}
				}
			}
		}
	}
}

@Composable
private fun SourcePickerBottomSheet(
	availableSources: List<MeloAvailableSource>,
	selectedSources: List<String>,
	hostNavBottom: Dp,
	onToggleSource: (String) -> Unit,
	onSelectAll: () -> Unit,
	onClearAll: () -> Unit,
	onDismissRequest: () -> Unit
) {
	ModalBottomSheet(
		onDismissRequest = onDismissRequest,
		sheetState = rememberBottomSheetState(
			initialValue = SheetValue.Hidden,
			enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
		),
		sheetTitle = "搜索音源"
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp)
				.padding(bottom = maxOf(hostNavBottom, 16.dp) + 16.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp)
		) {
			MeloSectionLabel(
				title = "已选 ${selectedSources.size}/${availableSources.size}",
				trailing = {
					Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
						Text(
							text = "全选",
							style = MaterialTheme.typography.labelMedium,
							color = MaterialTheme.colorScheme.primary,
							modifier = Modifier
								.clip(CircleShape)
								.clickable(onClick = onSelectAll)
								.padding(horizontal = 4.dp, vertical = 2.dp)
						)
						Text(
							text = "清空",
							style = MaterialTheme.typography.labelMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier
								.clip(CircleShape)
								.clickable(onClick = onClearAll)
								.padding(horizontal = 4.dp, vertical = 2.dp)
						)
					}
				}
			)

			val selectedSourceSet = remember(selectedSources) { selectedSources.toSet() }
			Surface(
				modifier = Modifier.fillMaxWidth(),
				shape = RoundedCornerShape(14.dp),
				color = MaterialTheme.colorScheme.surfaceContainer,
				border = BorderStroke(
					width = MeloHairlineWidth,
					color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
				)
			) {
				LazyColumn(
					modifier = Modifier
						.fillMaxWidth()
						.height(340.dp)
				) {
					itemsIndexed(
						items = availableSources,
						key = { _, src -> src.id },
						contentType = { _, _ -> "source-option" }
					) { idx, source ->
						val checked = source.id in selectedSourceSet
						val subtitle = listOfNotNull(
							source.id,
							if (source.origin.equals("plugin", ignoreCase = true)) "插件" else null,
							source.category.takeIf { it.isNotBlank() },
							source.capability?.tier?.takeIf { it.isNotBlank() }
						).joinToString(" · ")
						Column(modifier = Modifier.fillMaxWidth()) {
							Row(
								modifier = Modifier
									.fillMaxWidth()
									.clickable { onToggleSource(source.id) }
									.padding(horizontal = 14.dp, vertical = 10.dp),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(10.dp)
							) {
								Column(
									modifier = Modifier.weight(1f),
									verticalArrangement = Arrangement.spacedBy(2.dp)
								) {
									Text(
										text = source.label.ifBlank { source.id },
										style = MaterialTheme.typography.bodyMedium,
										fontWeight = FontWeight.Medium,
										color = MaterialTheme.colorScheme.onSurface,
										maxLines = 1,
										overflow = TextOverflow.Ellipsis
									)
									Text(
										text = subtitle,
										style = MaterialTheme.typography.bodySmall,
										color = MaterialTheme.colorScheme.onSurfaceVariant,
										maxLines = 1,
										overflow = TextOverflow.Ellipsis
									)
								}
								Checkbox(checked = checked, onCheckedChange = null)
							}
							if (idx < availableSources.lastIndex) {
								MeloHairlineDivider(startIndent = 14.dp)
							}
						}
					}
				}
			}
		}
	}
}
