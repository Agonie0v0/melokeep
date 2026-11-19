@file:OptIn(
	ExperimentalMaterial3Api::class,
	ExperimentalFoundationApi::class,
	ExperimentalLayoutApi::class,
)

package io.github.agonie0v0.melokeep.ios

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.agonie0v0.melokeep.shared.MeloAgentEvent
import io.github.agonie0v0.melokeep.shared.MeloAgentMessage
import io.github.agonie0v0.melokeep.shared.MeloAgentPlan
import io.github.agonie0v0.melokeep.shared.MeloAgentSession
import io.github.agonie0v0.melokeep.shared.MeloAgentSessionSummary
import io.github.agonie0v0.melokeep.shared.MeloAiController
import io.github.agonie0v0.melokeep.shared.MeloAiUiState
import io.github.agonie0v0.melokeep.shared.MeloAvailableSource
import io.github.agonie0v0.melokeep.shared.MeloKeepApiClient
import io.github.agonie0v0.melokeep.shared.MeloQualityFilter
import io.github.agonie0v0.melokeep.shared.MeloSearchController
import io.github.agonie0v0.melokeep.shared.MeloSearchUiState
import io.github.agonie0v0.melokeep.shared.MeloSourceStatus
import io.github.agonie0v0.melokeep.shared.MeloTrack
import io.github.agonie0v0.melokeep.shared.MeloTrackGroup
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal enum class IosDiscoverMode(val label: String) {
	SEARCH("全网搜歌"),
	AI("AI 助手"),
}

private val IOS_DISCOVER_HOT_KEYWORDS = listOf(
	"Ambient",
	"Lo-Fi",
	"Synthwave",
	"Piano",
	"Acoustic",
	"Electronic",
	"Jazz",
	"Classical",
)

private val iosDiscoverAiJson = Json {
	ignoreUnknownKeys = true
	isLenient = true
	coerceInputValues = true
}

private val iosDiscoverPrettyJson = Json {
	prettyPrint = true
}

private val IOS_DISCOVER_AI_TOOL_LABELS = mapOf(
	"search_music" to "全网检索音乐",
	"list_playlists" to "查询本地歌单",
	"read_playlist" to "读取歌单曲目",
	"create_playlist" to "创建本地歌单",
	"add_to_playlist" to "加入本地歌单",
	"download_music" to "提交下载任务",
	"get_status" to "检查队列状态",
	"get_task_status" to "查询任务进度",
	"browse_library" to "检索服务器曲库",
	"preview_organize" to "生成刮削整理预览",
	"list_project_operations" to "查询可用操作",
	"query_project" to "查询系统状态",
	"execute_project" to "执行系统操作",
	"wait_for_tasks" to "等待后台任务",
	"prepare_import" to "整理识别清单",
	"update_plan" to "更新执行计划",
	"request_decision" to "等待用户确认",
	"recover_download" to "恢复下载或整理",
)

private val IOS_DISCOVER_AI_QUICK_PROMPTS = listOf(
	"识别截图歌单并全网查找",
	"搜索氛围电子与纯音乐专辑",
	"检查最近下载任务状态",
	"整理并刮削未归档音乐",
)

private val IOS_DISCOVER_BOOK_TITLE_REGEX = Regex("《([^》]{1,40})》")
private val IOS_DISCOVER_DASH_TRACK_REGEX = Regex(
	"(?m)^(?:\\d+[.、]\\s*|[-*•]\\s*)?\\*{0,2}([^\\n\\-—:：]{1,36})\\*{0,2}\\s*[-—]\\s*([^\\n（(]{1,28})",
)

// ==========================================
// 探索双模保活容器与极简顶栏
// ==========================================

@Composable
internal fun IosDiscoverKeepAliveSlot(
	visible: Boolean,
	modifier: Modifier = Modifier,
	content: @Composable () -> Unit,
) {
	Layout(
		content = content,
		modifier = modifier,
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
internal fun IosDiscoverUnauthenticatedEmpty(
	onGoToSettings: () -> Unit,
) {
	IosQuietEmptyState(
		message = "未连接 MeloKeep 服务",
		icon = Icons.Outlined.Cloud,
		actionLabel = "前往设置",
		onAction = onGoToSettings,
	)
}

@Composable
internal fun IosDiscoverTopHeader(
	discoverMode: IosDiscoverMode,
	onModeChange: (IosDiscoverMode) -> Unit,
	isConnected: Boolean,
	onGoToSettings: () -> Unit,
) {
	Column(modifier = Modifier.fillMaxWidth()) {
		IosCleanTopBar(
			title = "探索",
			trailing = {
				IosDiscoverConnectionPill(
					isConnected = isConnected,
					onClick = onGoToSettings,
				)
			},
		)

		Surface(
			modifier = Modifier
				.fillMaxWidth()
				.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
			shape = CircleShape,
			color = MaterialTheme.colorScheme.surfaceContainer,
			border = BorderStroke(
				width = IosHairlineWidth,
				color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
			),
		) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(3.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(3.dp),
			) {
				IosDiscoverMode.entries.forEach { mode ->
					val selected = discoverMode == mode
					val fg = if (selected) {
						MaterialTheme.colorScheme.onPrimary
					} else {
						MaterialTheme.colorScheme.onSurfaceVariant
					}
					Surface(
						modifier = Modifier
							.weight(1f)
							.clip(CircleShape)
							.clickable { onModeChange(mode) },
						shape = CircleShape,
						color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
					) {
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.padding(horizontal = 10.dp, vertical = 7.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
						) {
							if (mode == IosDiscoverMode.AI) {
								Icon(
									imageVector = Icons.Outlined.AutoAwesome,
									contentDescription = null,
									tint = fg,
									modifier = Modifier.size(14.dp),
								)
							}
							Text(
								text = mode.label,
								style = MaterialTheme.typography.labelMedium.copy(
									fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
								),
								color = fg,
								maxLines = 1,
								softWrap = false,
							)
						}
					}
				}
			}
		}
	}
}

@Composable
private fun IosDiscoverConnectionPill(
	isConnected: Boolean,
	onClick: () -> Unit,
) {
	Surface(
		onClick = onClick,
		shape = CircleShape,
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = BorderStroke(
			width = IosHairlineWidth,
			color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
		),
	) {
		Row(
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp),
		) {
			Box(
				modifier = Modifier
					.size(6.dp)
					.background(
						color = if (isConnected) {
							MaterialTheme.colorScheme.meloPositive
						} else {
							MaterialTheme.colorScheme.error
						},
						shape = CircleShape,
					),
			)
			Text(
				text = if (isConnected) "在线" else "离线",
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
			)
			Icon(
				imageVector = Icons.Outlined.Settings,
				contentDescription = "MeloKeep 设置",
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(14.dp),
			)
		}
	}
}

// ==========================================
// 1A. 全网搜歌子模式（极简发丝搜索舱 + 安静分组曲目列表）
// ==========================================

@Composable
internal fun IosDiscoverSearchContent(
	searchState: MeloSearchUiState,
	enqueuedIds: Set<String>,
	currentTrackId: String?,
	isPlayerPlaying: Boolean,
	searchController: MeloSearchController,
	subsonicManager: IosSubsonicManager,
	audioPlayer: IosAudioPlayer,
	safeBottomDp: Dp,
) {
	val scope = rememberCoroutineScope()
	val focusManager = LocalFocusManager.current
	val listState = rememberLazyListState()

	var showSourcesSheet by remember { mutableStateOf(false) }
	var showSourceStatuses by rememberSaveable { mutableStateOf(false) }
	var versionSheetTrack by remember { mutableStateOf<MeloTrack?>(null) }
	var loadingVersions by remember { mutableStateOf(false) }
	val expandedGroups = remember(searchState.keyword) { mutableStateMapOf<String, Boolean>() }

	val groupedList = searchState.groupedTracks
	val primaryTracks = remember(groupedList) { groupedList.map { it.primary } }
	val primaryIosQueue = remember(primaryTracks) {
		primaryTracks.map { subsonicManager.meloTrackToIosTrack(it) }
	}
	val filteredTracksCount = remember(searchState.tracks, searchState.qualityFilter) {
		searchState.filteredTracks.size
	}
	val sourceLabels = remember(searchState.availableSources) {
		searchState.availableSources.associate { it.id to it.label.ifBlank { it.id } }
	}

	fun submitSearch(keyword: String, refresh: Boolean = false) {
		focusManager.clearFocus()
		searchController.updateKeyword(keyword)
		searchController.search(keyword = keyword, refresh = refresh)
	}

	fun playMeloTrack(track: MeloTrack) {
		val targetId = "mk:${track.id}"
		if (currentTrackId == targetId) {
			audioPlayer.togglePlayPause()
			return
		}
		scope.launch {
			val resolved = searchController.resolvePlayback(track.id).getOrNull() ?: track
			val mapped = subsonicManager.meloTrackToIosTrack(resolved)
			val baseQueue = primaryIosQueue.toMutableList()
			val existingIndex = baseQueue.indexOfFirst { it.id == mapped.id }
			if (existingIndex >= 0) {
				baseQueue[existingIndex] = mapped
				audioPlayer.playQueue(baseQueue, existingIndex)
			} else {
				audioPlayer.playQueue(listOf(mapped) + baseQueue, 0)
			}
		}
	}

	fun playNextMeloTrack(track: MeloTrack) {
		scope.launch {
			val resolved = searchController.resolvePlayback(track.id).getOrNull() ?: track
			val item = subsonicManager.meloTrackToIosTrack(resolved)
			audioPlayer.playNext(item)
		}
	}

	fun openQualitySheet(track: MeloTrack) {
		versionSheetTrack = track
		loadingVersions = true
		scope.launch {
			searchController.fetchTrackVersions(track.id).onSuccess { detailed ->
				versionSheetTrack = detailed
			}
			loadingVersions = false
		}
	}

	val totalBottomPadding = safeBottomDp + if (searchState.multiSelectMode) 76.dp else 16.dp

	Box(modifier = Modifier.fillMaxSize()) {
		Column(modifier = Modifier.fillMaxSize()) {
			IosDiscoverSearchCapsule(
				keyword = searchState.keyword,
				searching = searchState.searching,
				selectedSourceCount = searchState.selectedSources.size,
				totalSourceCount = searchState.availableSources.size,
				onKeywordChange = { searchController.updateKeyword(it) },
				onSearch = { submitSearch(searchState.keyword, refresh = false) },
				onStop = { searchController.stopSearch() },
				onClear = { searchController.updateKeyword("") },
				onOpenSourcePicker = { showSourcesSheet = true },
			)

			IosDiscoverFilterAndStatusStrip(
				qualityFilter = searchState.qualityFilter,
				multiSelectMode = searchState.multiSelectMode,
				hasResults = searchState.tracks.isNotEmpty(),
				searching = searchState.searching,
				sourceStatuses = searchState.sourceStatuses,
				totalTracksCount = filteredTracksCount,
				sourceDetailsExpanded = showSourceStatuses,
				onQualityChange = { searchController.setQualityFilter(it) },
				onToggleMultiSelect = { searchController.toggleMultiSelect() },
				onToggleSourceDetails = { showSourceStatuses = !showSourceStatuses },
			)

			AnimatedVisibility(visible = showSourceStatuses && searchState.sourceStatuses.isNotEmpty()) {
				IosDiscoverSourceProgressRow(
					statuses = searchState.sourceStatuses,
					sourceLabels = sourceLabels,
				)
			}

			when {
				searchState.tracks.isEmpty() && !searchState.searching -> {
					LazyColumn(
						modifier = Modifier
							.weight(1f)
							.fillMaxWidth(),
						contentPadding = PaddingValues(
							start = 16.dp,
							end = 16.dp,
							top = 8.dp,
							bottom = totalBottomPadding,
						),
						verticalArrangement = Arrangement.spacedBy(18.dp),
					) {
						if (searchState.searchHistory.isNotEmpty()) {
							item(key = "search-history", contentType = "search-history") {
								Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
									IosSectionLabel(
										title = "最近搜索",
										trailing = {
											Text(
												text = "清空",
												style = MaterialTheme.typography.labelMedium,
												color = MaterialTheme.colorScheme.primary,
												modifier = Modifier
													.clip(CircleShape)
													.clickable { searchController.clearHistory() }
													.padding(horizontal = 6.dp, vertical = 2.dp),
											)
										},
									)
									FlowRow(
										horizontalArrangement = Arrangement.spacedBy(8.dp),
										verticalArrangement = Arrangement.spacedBy(8.dp),
									) {
										searchState.searchHistory.forEach { kw ->
											IosDiscoverQuietTagChip(
												label = kw,
												onClick = { submitSearch(kw, refresh = false) },
											)
										}
									}
								}
							}
						}

						item(key = "hot-keywords", contentType = "hot-keywords") {
							Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
								IosSectionLabel(title = "快捷热搜")
								FlowRow(
									horizontalArrangement = Arrangement.spacedBy(8.dp),
									verticalArrangement = Arrangement.spacedBy(8.dp),
								) {
									IOS_DISCOVER_HOT_KEYWORDS.forEach { hot ->
										IosDiscoverQuietTagChip(
											label = hot,
											onClick = { submitSearch(hot, refresh = false) },
										)
									}
								}
							}
						}

						if (searchState.keyword.isNotBlank()) {
							item(key = "empty-hint", contentType = "empty") {
								IosQuietEmptyState(
									message = "未找到「${searchState.keyword}」相关曲目",
									icon = Icons.Outlined.Search,
									actionLabel = "强制刷新",
									onAction = { submitSearch(searchState.keyword, refresh = true) },
								)
							}
						}
					}
				}

				groupedList.isEmpty() && searchState.searching -> {
					Box(
						modifier = Modifier
							.weight(1f)
							.fillMaxWidth()
							.padding(bottom = totalBottomPadding),
						contentAlignment = Alignment.Center,
					) {
						Column(
							horizontalAlignment = Alignment.CenterHorizontally,
							verticalArrangement = Arrangement.spacedBy(10.dp),
						) {
							CircularProgressIndicator(
								modifier = Modifier.size(24.dp),
								strokeWidth = 2.dp,
							)
							Text(
								text = "正在检索「${searchState.keyword}」…",
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					}
				}

				groupedList.isEmpty() -> {
					Box(
						modifier = Modifier
							.weight(1f)
							.fillMaxWidth()
							.padding(bottom = totalBottomPadding),
						contentAlignment = Alignment.Center,
					) {
						if (searchState.tracks.isEmpty()) {
							IosQuietEmptyState(
								message = "未找到相关曲目",
								icon = Icons.Outlined.Search,
								actionLabel = "重试",
								onAction = { submitSearch(searchState.keyword, refresh = true) },
							)
						} else {
							IosQuietEmptyState(
								message = "当前音质下无匹配（共 ${searchState.tracks.size} 首）",
								icon = Icons.Outlined.Search,
								actionLabel = "查看全部",
								onAction = { searchController.setQualityFilter(MeloQualityFilter.ALL) },
							)
						}
					}
				}

				else -> {
					LazyColumn(
						state = listState,
						modifier = Modifier
							.weight(1f)
							.fillMaxWidth(),
						contentPadding = PaddingValues(
							start = 16.dp,
							end = 16.dp,
							top = 4.dp,
							bottom = totalBottomPadding,
						),
					) {
						if (searchState.keyword.isNotBlank() && !searchState.searching) {
							item(key = "result-summary", contentType = "header") {
								IosSectionLabel(
									title = "${groupedList.size} 组曲目 · ${filteredTracksCount} 个版本",
									modifier = Modifier.padding(bottom = 8.dp),
									trailing = {
										Row(
											modifier = Modifier
												.clip(CircleShape)
												.clickable { submitSearch(searchState.keyword, refresh = true) }
												.padding(horizontal = 6.dp, vertical = 2.dp),
											verticalAlignment = Alignment.CenterVertically,
											horizontalArrangement = Arrangement.spacedBy(3.dp),
										) {
											Icon(
												imageVector = Icons.Outlined.Refresh,
												contentDescription = null,
												tint = MaterialTheme.colorScheme.primary,
												modifier = Modifier.size(13.dp),
											)
											Text(
												text = "刷新",
												style = MaterialTheme.typography.labelMedium,
												color = MaterialTheme.colorScheme.primary,
											)
										}
									},
								)
							}
						}

						itemsIndexed(
							items = groupedList,
							key = { idx, group -> group.groupKey.ifBlank { "${group.primary.id}_$idx" } },
							contentType = { _, _ -> "track-group" },
						) { index, group ->
							val groupKey = group.groupKey.ifBlank { group.primary.id }
							val isExpanded = expandedGroups[groupKey] == true
							IosDiscoverTrackGroupRow(
								group = group,
								isFirst = index == 0,
								isLast = index == groupedList.lastIndex,
								expanded = isExpanded,
								onToggleExpand = { expandedGroups[groupKey] = !isExpanded },
								multiSelectMode = searchState.multiSelectMode,
								selectedTrackIds = searchState.selectedTrackIds,
								enqueuedIds = enqueuedIds,
								currentTrackId = currentTrackId,
								isPlayerPlaying = isPlayerPlaying,
								resolvingTrackId = searchState.resolvingTrackId,
								apiClient = subsonicManager.meloApiClient,
								onToggleSelect = { trackId -> searchController.toggleTrackSelection(trackId) },
								onEnterMultiSelectWithTrack = { trackId ->
									if (!searchState.multiSelectMode) {
										searchController.toggleMultiSelect(true)
									}
									searchController.toggleTrackSelection(trackId)
								},
								onPlay = ::playMeloTrack,
								onPlayNext = ::playNextMeloTrack,
								onOpenDownloadSheet = ::openQualitySheet,
							)
						}
					}
				}
			}
		}

		// 多选底部批处理坞
		AnimatedVisibility(
			visible = searchState.multiSelectMode,
			enter = slideInVertically { it } + fadeIn(),
			exit = slideOutVertically { it } + fadeOut(),
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.padding(horizontal = 16.dp)
				.padding(bottom = safeBottomDp + 8.dp),
		) {
			IosDiscoverMultiSelectDock(
				selectedCount = searchState.selectedTrackIds.size,
				filteredCount = filteredTracksCount,
				onSelectAll = { searchController.selectAllFiltered() },
				onClearSelection = { searchController.clearSelection() },
				onExitMultiSelect = { searchController.toggleMultiSelect(false) },
				onBatchDownload = { scope.launch { searchController.enqueueSelectedDownloads() } },
			)
		}
	}

	if (showSourcesSheet) {
		IosDiscoverSourceSelectorSheet(
			sources = searchState.availableSources,
			selectedSources = searchState.selectedSources,
			onToggleSource = { searchController.toggleSource(it) },
			onSelectAll = { searchController.setSelectedSources(searchState.availableSources.map { it.id }) },
			onClearAll = { searchController.setSelectedSources(emptyList()) },
			onDismiss = { showSourcesSheet = false },
		)
	}

	val sheetTrack = versionSheetTrack
	if (sheetTrack != null) {
		IosDiscoverTrackQualitySheet(
			track = sheetTrack,
			loadingVersions = loadingVersions,
			isEnqueued = sheetTrack.id in enqueuedIds,
			apiClient = subsonicManager.meloApiClient,
			onDismiss = { versionSheetTrack = null },
			onEnqueueDefault = {
				scope.launch { searchController.enqueueDownload(sheetTrack) }
				versionSheetTrack = null
			},
			onEnqueueQuality = { qualityId ->
				scope.launch { searchController.enqueueDownload(sheetTrack, qualityId) }
				versionSheetTrack = null
			},
		)
	}
}

@Composable
private fun IosDiscoverQuietTagChip(
	label: String,
	onClick: () -> Unit,
) {
	Surface(
		onClick = onClick,
		shape = CircleShape,
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = BorderStroke(
			width = IosHairlineWidth,
			color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
		),
	) {
		Text(
			text = label,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurface,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
		)
	}
}

@Composable
private fun IosDiscoverSearchCapsule(
	keyword: String,
	searching: Boolean,
	selectedSourceCount: Int,
	totalSourceCount: Int,
	onKeywordChange: (String) -> Unit,
	onSearch: () -> Unit,
	onStop: () -> Unit,
	onClear: () -> Unit,
	onOpenSourcePicker: () -> Unit,
) {
	Surface(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 2.dp),
		shape = RoundedCornerShape(14.dp),
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = BorderStroke(
			width = IosHairlineWidth,
			color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
		),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.height(44.dp)
				.padding(start = 12.dp, end = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp),
		) {
			Icon(
				imageVector = Icons.Outlined.Search,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(18.dp),
			)
			Box(
				modifier = Modifier.weight(1f),
				contentAlignment = Alignment.CenterStart,
			) {
				if (keyword.isEmpty()) {
					Text(
						text = "歌名、歌手、专辑或分享链接",
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
				BasicTextField(
					value = keyword,
					onValueChange = onKeywordChange,
					singleLine = true,
					textStyle = MaterialTheme.typography.bodyMedium.copy(
						color = MaterialTheme.colorScheme.onSurface,
					),
					cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
					keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
					keyboardActions = KeyboardActions(onSearch = { onSearch() }),
					modifier = Modifier.fillMaxWidth(),
				)
			}

			if (keyword.isNotEmpty()) {
				IconButton(
					onClick = onClear,
					modifier = Modifier.size(28.dp),
				) {
					Icon(
						imageVector = Icons.Outlined.Close,
						contentDescription = "清空",
						tint = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.size(16.dp),
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
					color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.75f),
				) {
					Row(
						modifier = Modifier.padding(start = 8.dp, end = 5.dp, top = 4.dp, bottom = 4.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(2.dp),
					) {
						Text(
							text = srcCountText,
							style = MaterialTheme.typography.labelSmall.tabularNums(),
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							maxLines = 1,
						)
						Icon(
							imageVector = Icons.Outlined.ExpandMore,
							contentDescription = "筛选音源",
							tint = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.size(13.dp),
						)
					}
				}
			}

			if (searching) {
				IconButton(
					onClick = onStop,
					modifier = Modifier.size(32.dp),
				) {
					Icon(
						imageVector = Icons.Outlined.Stop,
						contentDescription = "停止搜索",
						tint = MaterialTheme.colorScheme.error,
						modifier = Modifier.size(18.dp),
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
					},
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
						modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
					)
				}
			}
		}
	}
}

@Composable
private fun IosDiscoverFilterAndStatusStrip(
	qualityFilter: MeloQualityFilter,
	multiSelectMode: Boolean,
	hasResults: Boolean,
	searching: Boolean,
	sourceStatuses: Map<String, MeloSourceStatus>,
	totalTracksCount: Int,
	sourceDetailsExpanded: Boolean,
	onQualityChange: (MeloQualityFilter) -> Unit,
	onToggleMultiSelect: () -> Unit,
	onToggleSourceDetails: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Row(
			modifier = Modifier
				.weight(1f)
				.horizontalScroll(rememberScrollState()),
			horizontalArrangement = Arrangement.spacedBy(4.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			IosDiscoverCompactFilterPill(
				label = "无损优先",
				selected = qualityFilter == MeloQualityFilter.LOSSLESS,
				onClick = { onQualityChange(MeloQualityFilter.LOSSLESS) },
			)
			IosDiscoverCompactFilterPill(
				label = "全部",
				selected = qualityFilter == MeloQualityFilter.ALL,
				onClick = { onQualityChange(MeloQualityFilter.ALL) },
			)
			IosDiscoverCompactFilterPill(
				label = "高品",
				selected = qualityFilter == MeloQualityFilter.HIGH,
				onClick = { onQualityChange(MeloQualityFilter.HIGH) },
			)
			if (hasResults || multiSelectMode) {
				IosDiscoverCompactFilterPill(
					label = if (multiSelectMode) "退出多选" else "多选",
					selected = multiSelectMode,
					leadingIcon = Icons.Outlined.DoneAll,
					onClick = onToggleMultiSelect,
				)
			}
		}

		if (sourceStatuses.isNotEmpty() || searching) {
			val totalSources = sourceStatuses.size
			val doneSources = remember(sourceStatuses) {
				sourceStatuses.values.count {
					val s = it.state.lowercase()
					s == "complete" || s == "completed" || s == "done" || s == "stopped" || s == "error" || s == "failed"
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
					width = IosHairlineWidth,
					color = if (sourceDetailsExpanded) {
						MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
					} else {
						MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
					},
				),
			) {
				Row(
					modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(4.dp),
				) {
					if (searching) {
						CircularProgressIndicator(
							modifier = Modifier.size(10.dp),
							strokeWidth = 1.6.dp,
						)
					} else {
						Box(
							modifier = Modifier
								.size(6.dp)
								.background(MaterialTheme.colorScheme.meloPositive, CircleShape),
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
						maxLines = 1,
					)
					Icon(
						imageVector = Icons.Outlined.ExpandMore,
						contentDescription = null,
						tint = if (sourceDetailsExpanded) {
							MaterialTheme.colorScheme.primary
						} else {
							MaterialTheme.colorScheme.onSurfaceVariant
						},
						modifier = Modifier
							.size(13.dp)
							.scale(scaleX = 1f, scaleY = if (sourceDetailsExpanded) -1f else 1f),
					)
				}
			}
		}
	}
}

@Composable
private fun IosDiscoverCompactFilterPill(
	label: String,
	selected: Boolean,
	leadingIcon: ImageVector? = null,
	onClick: () -> Unit,
) {
	Surface(
		onClick = onClick,
		shape = CircleShape,
		color = if (selected) {
			MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
		} else {
			Color.Transparent
		},
	) {
		Row(
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(4.dp),
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
					modifier = Modifier.size(14.dp),
				)
			}
			Text(
				text = label,
				style = MaterialTheme.typography.labelMedium.copy(
					fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
				),
				color = if (selected) {
					MaterialTheme.colorScheme.primary
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
				maxLines = 1,
			)
		}
	}
}

@Composable
private fun IosDiscoverSourceProgressRow(
	statuses: Map<String, MeloSourceStatus>,
	sourceLabels: Map<String, String>,
) {
	val statusEntries = remember(statuses) { statuses.entries.toList() }
	LazyRow(
		modifier = Modifier
			.fillMaxWidth()
			.padding(bottom = 6.dp),
		contentPadding = PaddingValues(horizontal = 16.dp),
		horizontalArrangement = Arrangement.spacedBy(6.dp),
	) {
		items(
			items = statusEntries,
			key = { it.key },
			contentType = { "source-status" },
		) { (sourceId, status) ->
			val label = sourceLabels[sourceId] ?: translateIosSourceName(sourceId)
			val stateLower = status.state.lowercase()
			val isError = stateLower == "error" || stateLower == "failed"
			val isSearching = stateLower == "searching"
			val isDone = stateLower == "complete" || stateLower == "completed" || stateLower == "done"
			Surface(
				shape = CircleShape,
				color = MaterialTheme.colorScheme.surfaceContainer,
				border = BorderStroke(
					width = IosHairlineWidth,
					color = if (isError) {
						MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
					} else {
						MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
					},
				),
			) {
				Row(
					modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(5.dp),
				) {
					when {
						isSearching -> {
							CircularProgressIndicator(
								modifier = Modifier.size(10.dp),
								strokeWidth = 1.6.dp,
								color = MaterialTheme.colorScheme.primary,
							)
						}
						isDone -> {
							Box(
								modifier = Modifier
									.size(6.dp)
									.background(MaterialTheme.colorScheme.meloPositive, CircleShape),
							)
						}
						isError -> {
							Box(
								modifier = Modifier
									.size(6.dp)
									.background(MaterialTheme.colorScheme.error, CircleShape),
							)
						}
						else -> {
							Box(
								modifier = Modifier
									.size(6.dp)
									.background(MaterialTheme.colorScheme.outlineVariant, CircleShape),
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
						maxLines = 1,
					)
				}
			}
		}
	}
}

@Composable
private fun IosDiscoverTrackGroupRow(
	group: MeloTrackGroup,
	isFirst: Boolean,
	isLast: Boolean,
	expanded: Boolean,
	onToggleExpand: () -> Unit,
	multiSelectMode: Boolean,
	selectedTrackIds: Set<String>,
	enqueuedIds: Set<String>,
	currentTrackId: String?,
	isPlayerPlaying: Boolean,
	resolvingTrackId: String?,
	apiClient: MeloKeepApiClient,
	onToggleSelect: (String) -> Unit,
	onEnterMultiSelectWithTrack: (String) -> Unit,
	onPlay: (MeloTrack) -> Unit,
	onPlayNext: (MeloTrack) -> Unit,
	onOpenDownloadSheet: (MeloTrack) -> Unit,
) {
	val primary = group.primary
	val isCurrent = currentTrackId == "mk:${primary.id}"
	val isSelected = primary.id in selectedTrackIds
	val isEnqueued = primary.id in enqueuedIds
	val resolvedCover = remember(primary.cover) { apiClient.resolveMediaUrl(primary.cover) }
	val primaryQuality = remember(primary.id, primary.qualities, primary.playback) {
		extractIosTrackQualityBadge(primary)?.first
	}
	val primaryMetaText = remember(
		primary.id,
		primary.artist,
		primary.album,
		primary.duration,
		primary.source,
		primary.sizeLabel,
		primaryQuality,
	) {
		buildList {
			primary.artist.trim().takeIf { it.isNotEmpty() }?.let(::add)
			primary.album.trim().takeIf { it.isNotEmpty() }?.let(::add)
			if (primary.duration > 0.0) {
				add(formatDurationMs((primary.duration * 1000.0).toLong()))
			}
			primary.source.trim().takeIf { it.isNotEmpty() }?.let {
				add(translateIosSourceName(it))
			}
			primaryQuality?.takeIf { it.isNotBlank() }?.let(::add)
			primary.sizeLabel?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
		}.joinToString(" · ").ifEmpty { "全网音源" }
	}
	val altSourcesText = remember(group.groupKey, group.alternatives) {
		val altSources = group.alternatives
			.map { translateIosSourceName(it.source) }
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
		bottomEnd = if (isLast) 14.dp else 0.dp,
	)
	val rowIndent = if (multiSelectMode) 52.dp else 70.dp

	Surface(
		modifier = Modifier
			.fillMaxWidth()
			.animateContentSize(),
		shape = shape,
		color = MaterialTheme.colorScheme.surfaceContainer,
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.then(
					if (isSelected || isCurrent) {
						Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
					} else {
						Modifier
					},
				),
		) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.combinedClickable(
						onClick = {
							if (multiSelectMode) onToggleSelect(primary.id) else onPlay(primary)
						},
						onLongClick = { onEnterMultiSelectWithTrack(primary.id) },
					)
					.padding(horizontal = 12.dp, vertical = 10.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(12.dp),
			) {
				if (multiSelectMode) {
					Checkbox(checked = isSelected, onCheckedChange = null)
				} else {
					CoverArtBox(
						coverUrl = resolvedCover,
						size = 46.dp,
						cornerRadius = 10.dp,
					)
				}

				Column(
					modifier = Modifier.weight(1f),
					verticalArrangement = Arrangement.spacedBy(2.dp),
				) {
					Text(
						text = primary.title.ifBlank { "未知曲目" },
						style = MaterialTheme.typography.bodyMedium,
						fontWeight = FontWeight.Medium,
						color = if (isCurrent) {
							MaterialTheme.colorScheme.primary
						} else {
							MaterialTheme.colorScheme.onSurface
						},
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
					Text(
						text = primaryMetaText,
						style = MaterialTheme.typography.bodySmall.tabularNums(),
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}

				if (!multiSelectMode) {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(2.dp),
					) {
						IconButton(
							onClick = { onPlay(primary) },
							modifier = Modifier.size(32.dp),
						) {
							if (resolvingTrackId == primary.id) {
								CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 1.8.dp)
							} else {
								Icon(
									imageVector = if (isCurrent && isPlayerPlaying) {
										Icons.Outlined.Pause
									} else {
										Icons.Outlined.PlayArrow
									},
									contentDescription = "试听",
									tint = if (isCurrent) {
										MaterialTheme.colorScheme.primary
									} else {
										MaterialTheme.colorScheme.onSurfaceVariant
									},
									modifier = Modifier.size(19.dp),
								)
							}
						}
						IconButton(
							onClick = { onPlayNext(primary) },
							modifier = Modifier.size(32.dp),
						) {
							Icon(
								imageVector = Icons.Outlined.PlaylistPlay,
								contentDescription = "下一首播放",
								tint = MaterialTheme.colorScheme.onSurfaceVariant,
								modifier = Modifier.size(18.dp),
							)
						}
						IconButton(
							onClick = { onOpenDownloadSheet(primary) },
							modifier = Modifier.size(32.dp),
						) {
							Icon(
								imageVector = if (isEnqueued) {
									Icons.Outlined.CloudDone
								} else {
									Icons.Outlined.CloudDownload
								},
								contentDescription = "选音质下载",
								tint = if (isEnqueued) {
									MaterialTheme.colorScheme.meloPositive
								} else {
									MaterialTheme.colorScheme.primary
								},
								modifier = Modifier.size(18.dp),
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
					verticalAlignment = Alignment.CenterVertically,
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
						modifier = Modifier.weight(1f),
					)
					Icon(
						imageVector = Icons.Outlined.ExpandMore,
						contentDescription = if (expanded) "收起备选音源" else "展开备选音源",
						tint = if (expanded) {
							MaterialTheme.colorScheme.primary
						} else {
							MaterialTheme.colorScheme.onSurfaceVariant
						},
						modifier = Modifier
							.size(15.dp)
							.scale(scaleX = 1f, scaleY = arrowScale),
					)
				}

				AnimatedVisibility(visible = expanded) {
					Column(
						modifier = Modifier
							.fillMaxWidth()
							.background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.7f)),
					) {
						group.alternatives.forEachIndexed { altIdx, alt ->
							key(alt.id.ifBlank { "alt-$altIdx" }) {
								val isCurrentAlt = currentTrackId == "mk:${alt.id}"
								IosHairlineDivider(startIndent = rowIndent)
								IosDiscoverAlternativeTrackRow(
									track = alt,
									startIndent = rowIndent,
									multiSelectMode = multiSelectMode,
									checked = alt.id in selectedTrackIds,
									enqueued = alt.id in enqueuedIds,
									isCurrent = isCurrentAlt,
									isPlaying = isCurrentAlt && isPlayerPlaying,
									onClick = {
										if (multiSelectMode) onToggleSelect(alt.id) else onPlay(alt)
									},
									onPlayNext = { onPlayNext(alt) },
									onOpenDownloadSheet = { onOpenDownloadSheet(alt) },
								)
							}
						}
					}
				}
			}

			if (!isLast) {
				IosHairlineDivider(startIndent = rowIndent)
			}
		}
	}
}

@Composable
private fun IosDiscoverAlternativeTrackRow(
	track: MeloTrack,
	startIndent: Dp,
	multiSelectMode: Boolean,
	checked: Boolean,
	enqueued: Boolean,
	isCurrent: Boolean,
	isPlaying: Boolean,
	onClick: () -> Unit,
	onPlayNext: () -> Unit,
	onOpenDownloadSheet: () -> Unit,
) {
	val qualityBadge = remember(track.id, track.qualities, track.playback) {
		extractIosTrackQualityBadge(track)?.first
	}
	val inlineText = remember(track.id, track.source, track.album, track.sizeLabel, qualityBadge) {
		listOfNotNull(
			translateIosSourceName(track.source).takeIf { it.isNotBlank() },
			track.album.takeIf { it.isNotBlank() },
			qualityBadge?.takeIf { it.isNotBlank() },
			track.sizeLabel?.takeIf { it.isNotBlank() },
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
				bottom = 8.dp,
			),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		if (multiSelectMode) {
			Checkbox(checked = checked, onCheckedChange = null)
		}
		Text(
			text = inlineText.ifBlank { track.title },
			style = MaterialTheme.typography.bodySmall.tabularNums(),
			color = if (isCurrent) {
				MaterialTheme.colorScheme.primary
			} else {
				MaterialTheme.colorScheme.onSurfaceVariant
			},
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		if (!multiSelectMode) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(2.dp),
			) {
				IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
					Icon(
						imageVector = if (isCurrent && isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
						contentDescription = "试听备选",
						tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.size(17.dp),
					)
				}
				IconButton(onClick = onPlayNext, modifier = Modifier.size(30.dp)) {
					Icon(
						imageVector = Icons.Outlined.PlaylistPlay,
						contentDescription = "下一首播放",
						tint = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.size(16.dp),
					)
				}
				IconButton(onClick = onOpenDownloadSheet, modifier = Modifier.size(30.dp)) {
					Icon(
						imageVector = if (enqueued) Icons.Outlined.CloudDone else Icons.Outlined.CloudDownload,
						contentDescription = "下载备选",
						tint = if (enqueued) MaterialTheme.colorScheme.meloPositive else MaterialTheme.colorScheme.primary,
						modifier = Modifier.size(16.dp),
					)
				}
			}
		}
	}
}

@Composable
private fun IosDiscoverMultiSelectDock(
	selectedCount: Int,
	filteredCount: Int,
	onSelectAll: () -> Unit,
	onClearSelection: () -> Unit,
	onExitMultiSelect: () -> Unit,
	onBatchDownload: () -> Unit,
) {
	Surface(
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(16.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		border = BorderStroke(
			width = IosHairlineWidth,
			color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
		),
		shadowElevation = 4.dp,
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 14.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(6.dp),
			) {
				Text(
					text = "已选 $selectedCount/$filteredCount",
					style = MaterialTheme.typography.labelLarge.tabularNums(),
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.onSurface,
				)
				TextButton(
					onClick = if (selectedCount >= filteredCount && filteredCount > 0) onClearSelection else onSelectAll,
					contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
					modifier = Modifier.height(30.dp),
				) {
					Text(if (selectedCount >= filteredCount && filteredCount > 0) "全不选" else "全选")
				}
			}

			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(6.dp),
			) {
				Button(
					onClick = onBatchDownload,
					enabled = selectedCount > 0,
					shape = CircleShape,
					contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
					modifier = Modifier.height(34.dp),
				) {
					Icon(
						imageVector = Icons.Outlined.CloudDownload,
						contentDescription = null,
						modifier = Modifier.size(16.dp),
					)
					Spacer(Modifier.width(4.dp))
					Text("批量入库", style = MaterialTheme.typography.labelMedium)
				}
				IconButton(
					onClick = onExitMultiSelect,
					modifier = Modifier.size(32.dp),
				) {
					Icon(
						imageVector = Icons.Outlined.Close,
						contentDescription = "退出多选",
						modifier = Modifier.size(18.dp),
					)
				}
			}
		}
	}
}

@Composable
private fun IosDiscoverSourceSelectorSheet(
	sources: List<MeloAvailableSource>,
	selectedSources: List<String>,
	onToggleSource: (String) -> Unit,
	onSelectAll: () -> Unit,
	onClearAll: () -> Unit,
	onDismiss: () -> Unit,
) {
	val selectedSourceSet = remember(selectedSources) { selectedSources.toSet() }
	ModalBottomSheet(
		onDismissRequest = onDismiss,
		containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp)
				.padding(bottom = 24.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Text(
				text = "搜索音源",
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.Bold,
			)
			IosSectionLabel(
				title = if (selectedSources.isEmpty()) {
					"当前使用全部默认音源 (${sources.size})"
				} else {
					"已选 ${selectedSources.size}/${sources.size}"
				},
				trailing = {
					Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
						Text(
							text = "全选",
							style = MaterialTheme.typography.labelMedium,
							color = MaterialTheme.colorScheme.primary,
							modifier = Modifier
								.clip(CircleShape)
								.clickable(onClick = onSelectAll)
								.padding(horizontal = 4.dp, vertical = 2.dp),
						)
						Text(
							text = "全部默认",
							style = MaterialTheme.typography.labelMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier
								.clip(CircleShape)
								.clickable(onClick = onClearAll)
								.padding(horizontal = 4.dp, vertical = 2.dp),
						)
					}
				},
			)

			Surface(
				modifier = Modifier.fillMaxWidth(),
				shape = RoundedCornerShape(14.dp),
				color = MaterialTheme.colorScheme.surfaceContainer,
				border = BorderStroke(
					width = IosHairlineWidth,
					color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
				),
			) {
				LazyColumn(
					modifier = Modifier
						.fillMaxWidth()
						.heightIn(max = 340.dp),
				) {
					itemsIndexed(
						items = sources,
						key = { _, src -> src.id },
						contentType = { _, _ -> "source-option" },
					) { idx, source ->
						val checked = selectedSources.isEmpty() || source.id in selectedSourceSet
						val subtitle = listOfNotNull(
							source.id,
							if (source.origin.equals("plugin", ignoreCase = true)) "插件" else null,
							source.category.takeIf { it.isNotBlank() },
							if (source.proxyRecommended) "建议代理" else null,
							source.initError?.takeIf { it.isNotBlank() }?.let { "异常: $it" },
						).joinToString(" · ")
						Column(modifier = Modifier.fillMaxWidth()) {
							Row(
								modifier = Modifier
									.fillMaxWidth()
									.clickable { onToggleSource(source.id) }
									.padding(horizontal = 14.dp, vertical = 10.dp),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(10.dp),
							) {
								Column(
									modifier = Modifier.weight(1f),
									verticalArrangement = Arrangement.spacedBy(2.dp),
								) {
									Text(
										text = source.label.ifBlank { translateIosSourceName(source.id) },
										style = MaterialTheme.typography.bodyMedium,
										fontWeight = FontWeight.Medium,
										color = MaterialTheme.colorScheme.onSurface,
										maxLines = 1,
										overflow = TextOverflow.Ellipsis,
									)
									Text(
										text = subtitle,
										style = MaterialTheme.typography.bodySmall,
										color = MaterialTheme.colorScheme.onSurfaceVariant,
										maxLines = 1,
										overflow = TextOverflow.Ellipsis,
									)
								}
								Checkbox(checked = checked, onCheckedChange = null)
							}
							if (idx < sources.lastIndex) {
								IosHairlineDivider(startIndent = 14.dp)
							}
						}
					}
				}
			}
		}
	}
}

@Composable
private fun IosDiscoverTrackQualitySheet(
	track: MeloTrack,
	loadingVersions: Boolean,
	isEnqueued: Boolean,
	apiClient: MeloKeepApiClient,
	onDismiss: () -> Unit,
	onEnqueueDefault: () -> Unit,
	onEnqueueQuality: (String) -> Unit,
) {
	val resolvedCover = remember(track.cover) { apiClient.resolveMediaUrl(track.cover) }
	ModalBottomSheet(
		onDismissRequest = onDismiss,
		containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp)
				.padding(bottom = 24.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(12.dp),
			) {
				CoverArtBox(
					coverUrl = resolvedCover,
					size = 46.dp,
					cornerRadius = 10.dp,
				)
				Column(
					modifier = Modifier.weight(1f),
					verticalArrangement = Arrangement.spacedBy(2.dp),
				) {
					Text(
						text = track.title.ifBlank { "未知曲目" },
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.SemiBold,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
					Text(
						text = listOfNotNull(
							track.artist.takeIf { it.isNotBlank() },
							track.source.takeIf { it.isNotBlank() }?.let(::translateIosSourceName),
						).joinToString(" · "),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
				if (isEnqueued) {
					IosStudioStatusDot(
						label = "已入库",
						color = MaterialTheme.colorScheme.meloPositive,
					)
				}
			}

			IosHairlineDivider()

			if (loadingVersions) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(vertical = 12.dp),
					horizontalArrangement = Arrangement.Center,
					verticalAlignment = Alignment.CenterVertically,
				) {
					CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
					Spacer(Modifier.width(8.dp))
					Text(
						text = "检测可用音质…",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}

			if (track.qualities.isEmpty() && !loadingVersions) {
				IosQuietEmptyState(
					message = "未返回可选音质列表",
					actionLabel = "按默认最佳音质下载",
					onAction = onEnqueueDefault,
				)
			} else if (track.qualities.isNotEmpty()) {
				IosGroupedCard {
					track.qualities.forEachIndexed { idx, q ->
						val displayLabel = q.label.ifBlank {
							listOfNotNull(
								q.codec.takeIf { it.isNotBlank() }?.uppercase(),
								q.bitrate?.let { "${it}k" },
							).joinToString(" ").ifBlank { q.id }
						}
						val detailParts = listOfNotNull(
							q.codec.takeIf { it.isNotBlank() }?.uppercase(),
							q.bitrate?.let { "$it kbps" },
							q.availability.takeIf { it.isNotBlank() },
							q.evidence.takeIf { it.isNotBlank() },
						).joinToString(" · ")
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.clickable { onEnqueueQuality(q.id) }
								.padding(horizontal = 14.dp, vertical = 12.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(10.dp),
						) {
							Column(
								modifier = Modifier.weight(1f),
								verticalArrangement = Arrangement.spacedBy(2.dp),
							) {
								Row(
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.spacedBy(6.dp),
								) {
									Text(
										text = displayLabel,
										style = MaterialTheme.typography.bodyMedium,
										fontWeight = FontWeight.Medium,
										color = MaterialTheme.colorScheme.onSurface,
									)
									if (q.lossless) {
										Surface(
											shape = RoundedCornerShape(6.dp),
											color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
										) {
											Text(
												text = "无损",
												style = MaterialTheme.typography.labelSmall,
												color = MaterialTheme.colorScheme.primary,
												modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
											)
										}
									}
								}
								if (detailParts.isNotBlank()) {
									Text(
										text = detailParts,
										style = MaterialTheme.typography.bodySmall.tabularNums(),
										color = MaterialTheme.colorScheme.onSurfaceVariant,
										maxLines = 1,
										overflow = TextOverflow.Ellipsis,
									)
								}
							}
							Icon(
								imageVector = Icons.Outlined.CloudDownload,
								contentDescription = "下载",
								tint = MaterialTheme.colorScheme.primary,
								modifier = Modifier.size(18.dp),
							)
						}
						if (idx < track.qualities.lastIndex) {
							IosHairlineDivider(startIndent = 14.dp)
						}
					}
				}
			}
		}
	}
}

// ==========================================
// 1B. AI 助手子模式（多会话管理 + 轨迹胶囊 + 音乐微卡片 + 相册识图）
// ==========================================

private data class IosDiscoverAiTurnBlock(
	val key: String,
	val turnId: String,
	val userMessage: MeloAgentMessage?,
	val assistantMessages: List<MeloAgentMessage>,
	val events: List<MeloAgentEvent>,
	val isLatest: Boolean,
)

@Composable
internal fun IosDiscoverAiContent(
	aiState: MeloAiUiState,
	recentSearchTracks: List<MeloTrack>,
	enqueuedIds: Set<String>,
	currentTrackId: String?,
	isPlayerPlaying: Boolean,
	aiController: MeloAiController,
	searchController: MeloSearchController,
	subsonicManager: IosSubsonicManager,
	audioPlayer: IosAudioPlayer,
	safeBottomDp: Dp,
	onJumpToSearch: (String) -> Unit,
) {
	val scope = rememberCoroutineScope()
	val focusManager = LocalFocusManager.current
	val listState = rememberLazyListState()

	var input by rememberSaveable { mutableStateOf("") }
	var pickedImageDataUrl by remember { mutableStateOf<String?>(null) }
	var pickerError by remember { mutableStateOf<String?>(null) }
	var sessionSheetOpen by remember { mutableStateOf(false) }

	DisposableEffect(Unit) {
		aiController.loadSessions()
		onDispose { aiController.stopPolling() }
	}

	val session = aiState.currentSession
	val turns = remember(session?.id, session?.messages, session?.events) {
		buildIosDiscoverAiTurns(session)
	}

	LaunchedEffect(turns.size, session?.partial, session?.id, aiState.isRunning) {
		val total = listState.layoutInfo.totalItemsCount
		if (turns.isNotEmpty() && total > 0) {
			listState.animateScrollToItem(total - 1)
		}
	}

	fun playAiTrack(track: MeloTrack, groupTracks: List<MeloTrack>) {
		val targetId = "mk:${track.id}"
		if (currentTrackId == targetId) {
			audioPlayer.togglePlayPause()
			return
		}
		scope.launch {
			val resolved = searchController.resolvePlayback(track.id).getOrNull() ?: track
			val mapped = subsonicManager.meloTrackToIosTrack(resolved)
			val queue = groupTracks.map { item ->
				if (item.id == resolved.id) mapped else subsonicManager.meloTrackToIosTrack(item)
			}
			val startIdx = queue.indexOfFirst { it.id == mapped.id }.coerceAtLeast(0)
			audioPlayer.playQueue(queue.ifEmpty { listOf(mapped) }, startIdx)
		}
	}

	fun sendPrompt(text: String, imageUrl: String? = null) {
		val clean = text.trim()
		if (clean.isEmpty() && imageUrl == null) return
		focusManager.clearFocus()
		aiController.sendMessage(
			message = clean.ifBlank { "请识别图片中的歌单" },
			imageDataUrl = imageUrl,
		)
	}

	fun openPhotoPicker() {
		pickerError = null
		IosImagePicker.pickImageAsDataUrl(
			onResult = { url ->
				if (url != null) {
					pickedImageDataUrl = url
					if (input.isBlank()) {
						input = "请识别这张歌单截图里的歌曲并帮我搜索入库"
					}
				}
			},
			onError = { err -> pickerError = err },
		)
	}

	val imeBottomDp = with(LocalDensity.current) { WindowInsets.ime.getBottom(this).toDp() }
	val inputBottomAvoidDp = maxOf(safeBottomDp, imeBottomDp)

	Column(
		modifier = Modifier
			.fillMaxSize()
			.padding(bottom = inputBottomAvoidDp),
	) {
		// 极简发丝会话切换条
		IosDiscoverAiSessionStrip(
			sessions = aiState.sessions,
			currentSessionId = session?.id,
			onSelect = { aiController.selectSession(it) },
			onNewSession = { aiController.createSession() },
			onOpenManager = { sessionSheetOpen = true },
		)

		if (session == null || (turns.isEmpty() && session.partial.isBlank() && !aiState.isRunning)) {
			Box(
				modifier = Modifier
					.weight(1f)
					.fillMaxWidth(),
				contentAlignment = Alignment.Center,
			) {
				if (aiState.loadingSessions) {
					CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
				} else {
					Column(
						modifier = Modifier
							.fillMaxWidth()
							.verticalScroll(rememberScrollState())
							.padding(horizontal = 16.dp, vertical = 12.dp),
						verticalArrangement = Arrangement.spacedBy(16.dp),
					) {
						IosQuietEmptyState(
							message = "文字指令或传图识别歌单",
							icon = Icons.Outlined.AutoAwesome,
							actionLabel = "相册选图",
							onAction = ::openPhotoPicker,
						)

						Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
							IosSectionLabel(title = "快捷提问")
							IosGroupedCard {
								IOS_DISCOVER_AI_QUICK_PROMPTS.forEachIndexed { idx, prompt ->
									Row(
										modifier = Modifier
											.fillMaxWidth()
											.clickable { input = prompt }
											.padding(horizontal = 14.dp, vertical = 12.dp),
										verticalAlignment = Alignment.CenterVertically,
										horizontalArrangement = Arrangement.spacedBy(10.dp),
									) {
										Icon(
											imageVector = Icons.Outlined.AutoAwesome,
											contentDescription = null,
											tint = MaterialTheme.colorScheme.onSurfaceVariant,
											modifier = Modifier.size(15.dp),
										)
										Text(
											text = prompt,
											style = MaterialTheme.typography.bodyMedium,
											color = MaterialTheme.colorScheme.onSurface,
											maxLines = 1,
											overflow = TextOverflow.Ellipsis,
											modifier = Modifier.weight(1f),
										)
									}
									if (idx < IOS_DISCOVER_AI_QUICK_PROMPTS.lastIndex) {
										IosHairlineDivider(startIndent = 39.dp)
									}
								}
							}
						}
					}
				}
			}
		} else {
			LazyColumn(
				state = listState,
				modifier = Modifier
					.weight(1f)
					.fillMaxWidth(),
				contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
				verticalArrangement = Arrangement.spacedBy(12.dp),
			) {
				items(
					items = turns,
					key = { it.key },
					contentType = { "ai-turn" },
				) { turn ->
					IosDiscoverAiTurnItem(
						turn = turn,
						plan = if (turn.isLatest) session.plan else null,
						isRunning = turn.isLatest && aiState.isRunning,
						apiClient = subsonicManager.meloApiClient,
						recentSearchTracks = recentSearchTracks,
						enqueuedIds = enqueuedIds,
						currentTrackId = currentTrackId,
						isPlayerPlaying = isPlayerPlaying,
						onPlayTrack = ::playAiTrack,
						onDownloadTrack = { track ->
							scope.launch { searchController.enqueueDownload(track) }
						},
						onJumpToSearch = onJumpToSearch,
					)
				}

				if (session.partial.isNotBlank() || aiState.isRunning) {
					item(key = "ai-streaming-status", contentType = "ai-streaming") {
						IosDiscoverAiStreamingBubble(
							partial = session.partial,
							detail = session.detail,
							status = session.status,
							onStop = { aiController.stopCurrentSession() },
						)
					}
				}

				if (session.status.lowercase() in setOf("stopped", "interrupted")) {
					item(key = "ai-resume-banner", contentType = "ai-resume") {
						IosGroupedCard {
							Row(
								modifier = Modifier
									.fillMaxWidth()
									.padding(horizontal = 14.dp, vertical = 10.dp),
								horizontalArrangement = Arrangement.SpaceBetween,
								verticalAlignment = Alignment.CenterVertically,
							) {
								Text(
									text = "会话已暂停 · ${formatIosStatusLabel(session.status)}",
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
								)
								Text(
									text = "继续生成",
									style = MaterialTheme.typography.labelMedium,
									fontWeight = FontWeight.Medium,
									color = MaterialTheme.colorScheme.primary,
									modifier = Modifier
										.clip(CircleShape)
										.clickable { aiController.resumeCurrentSession() }
										.padding(horizontal = 8.dp, vertical = 4.dp),
								)
							}
						}
					}
				}
			}
		}

		// 选图附件条
		if (pickedImageDataUrl != null) {
			Surface(
				shape = RoundedCornerShape(12.dp),
				color = MaterialTheme.colorScheme.surfaceContainer,
				border = BorderStroke(
					width = IosHairlineWidth,
					color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
				),
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 14.dp, vertical = 4.dp),
			) {
				Row(
					modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.SpaceBetween,
				) {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(8.dp),
					) {
						Icon(
							imageVector = Icons.Outlined.Image,
							contentDescription = null,
							tint = MaterialTheme.colorScheme.primary,
							modifier = Modifier.size(16.dp),
						)
						Text(
							text = "已附加歌单截图",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
					IconButton(
						onClick = { pickedImageDataUrl = null },
						modifier = Modifier.size(26.dp),
					) {
						Icon(
							imageVector = Icons.Outlined.Close,
							contentDescription = "移除图片",
							tint = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.size(15.dp),
						)
					}
				}
			}
		}

		val errorText = pickerError ?: aiState.errorMessage
		if (!errorText.isNullOrBlank()) {
			Text(
				text = errorText,
				color = MaterialTheme.colorScheme.error,
				style = MaterialTheme.typography.bodySmall,
				modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
			)
		}

		// 多模态发丝悬浮输入舱
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 14.dp, vertical = 6.dp),
			verticalAlignment = Alignment.Bottom,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Surface(
				modifier = Modifier.weight(1f),
				shape = RoundedCornerShape(22.dp),
				color = MaterialTheme.colorScheme.surfaceContainer,
				border = BorderStroke(
					width = IosHairlineWidth,
					color = MaterialTheme.colorScheme.outline.copy(alpha = 0.40f),
				),
			) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(start = 4.dp, end = 14.dp, top = 3.dp, bottom = 3.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					IconButton(
						onClick = ::openPhotoPicker,
						enabled = !aiState.sending,
						modifier = Modifier.size(38.dp),
					) {
						Icon(
							imageVector = Icons.Outlined.Image,
							contentDescription = "从相册选图识图导歌单",
							tint = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.size(20.dp),
						)
					}
					Box(
						modifier = Modifier
							.weight(1f)
							.heightIn(min = 38.dp, max = 104.dp)
							.padding(vertical = 6.dp),
						contentAlignment = Alignment.CenterStart,
					) {
						if (input.isEmpty()) {
							Text(
								text = "输入指令或附歌单截图…",
								style = MaterialTheme.typography.bodyMedium,
								color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
							)
						}
						BasicTextField(
							value = input,
							onValueChange = { input = it },
							maxLines = 4,
							textStyle = MaterialTheme.typography.bodyMedium.copy(
								color = MaterialTheme.colorScheme.onSurface,
							),
							cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
							keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
							keyboardActions = KeyboardActions(
								onSend = {
									val img = pickedImageDataUrl
									if (input.isNotBlank() || img != null) {
										sendPrompt(input, img)
										input = ""
										pickedImageDataUrl = null
									}
								},
							),
							modifier = Modifier.fillMaxWidth(),
						)
					}
				}
			}

			if (aiState.isRunning) {
				FilledTonalIconButton(
					onClick = { aiController.stopCurrentSession() },
					colors = IconButtonDefaults.filledTonalIconButtonColors(
						containerColor = MaterialTheme.colorScheme.errorContainer,
						contentColor = MaterialTheme.colorScheme.onErrorContainer,
					),
					modifier = Modifier.size(44.dp),
				) {
					Icon(
						imageVector = Icons.Outlined.Stop,
						contentDescription = "停止生成",
						modifier = Modifier.size(20.dp),
					)
				}
			} else {
				val canSend = !aiState.sending && (input.isNotBlank() || pickedImageDataUrl != null)
				FilledTonalIconButton(
					onClick = {
						val img = pickedImageDataUrl
						sendPrompt(input, img)
						input = ""
						pickedImageDataUrl = null
					},
					enabled = canSend,
					colors = IconButtonDefaults.filledTonalIconButtonColors(
						containerColor = MaterialTheme.colorScheme.primary,
						contentColor = MaterialTheme.colorScheme.onPrimary,
						disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
						disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
					),
					modifier = Modifier.size(44.dp),
				) {
					if (aiState.sending) {
						CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
					} else {
						Icon(
							imageVector = Icons.Outlined.Send,
							contentDescription = "发送",
							modifier = Modifier.size(19.dp),
						)
					}
				}
			}
		}
	}

	if (sessionSheetOpen) {
		IosDiscoverAiSessionSheet(
			sessions = aiState.sessions,
			currentSessionId = session?.id,
			onSelectSession = { id ->
				aiController.selectSession(id)
				sessionSheetOpen = false
			},
			onCreateSession = {
				aiController.createSession()
				sessionSheetOpen = false
			},
			onDeleteSession = { id -> aiController.deleteSession(id) },
			onDismiss = { sessionSheetOpen = false },
		)
	}
}

@Composable
private fun IosDiscoverAiSessionStrip(
	sessions: List<MeloAgentSessionSummary>,
	currentSessionId: String?,
	onSelect: (String) -> Unit,
	onNewSession: () -> Unit,
	onOpenManager: () -> Unit,
) {
	LazyRow(
		modifier = Modifier
			.fillMaxWidth()
			.padding(top = 2.dp, bottom = 6.dp),
		contentPadding = PaddingValues(horizontal = 16.dp),
		horizontalArrangement = Arrangement.spacedBy(6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		items(
			items = sessions,
			key = { it.id },
			contentType = { "ai-session-pill" },
		) { summary ->
			IosDiscoverAiSessionPill(
				label = summary.title.ifBlank { "新对话" },
				selected = summary.id == currentSessionId,
				onClick = { onSelect(summary.id) },
			)
		}
		item(key = "ai-new-session", contentType = "ai-session-action") {
			IosDiscoverAiSessionPill(
				label = "新会话",
				selected = false,
				leadingIcon = Icons.Outlined.Add,
				onClick = onNewSession,
			)
		}
		item(key = "ai-manage-sessions", contentType = "ai-session-action") {
			IosDiscoverAiSessionPill(
				label = "全部",
				selected = false,
				leadingIcon = Icons.Outlined.Forum,
				onClick = onOpenManager,
			)
		}
	}
}

@Composable
private fun IosDiscoverAiSessionPill(
	label: String,
	selected: Boolean,
	leadingIcon: ImageVector? = null,
	onClick: () -> Unit,
) {
	Surface(
		onClick = onClick,
		shape = CircleShape,
		color = if (selected) {
			MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
		} else {
			MaterialTheme.colorScheme.surfaceContainer
		},
		border = BorderStroke(
			width = IosHairlineWidth,
			color = if (selected) {
				MaterialTheme.colorScheme.primary.copy(alpha = 0.40f)
			} else {
				MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
			},
		),
	) {
		Row(
			modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(4.dp),
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
					modifier = Modifier.size(13.dp),
				)
			}
			Text(
				text = label,
				style = MaterialTheme.typography.labelMedium.copy(
					fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
				),
				color = if (selected) {
					MaterialTheme.colorScheme.primary
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.widthIn(max = 132.dp),
			)
		}
	}
}

@Composable
private fun IosDiscoverAiTurnItem(
	turn: IosDiscoverAiTurnBlock,
	plan: MeloAgentPlan?,
	isRunning: Boolean,
	apiClient: MeloKeepApiClient,
	recentSearchTracks: List<MeloTrack>,
	enqueuedIds: Set<String>,
	currentTrackId: String?,
	isPlayerPlaying: Boolean,
	onPlayTrack: (MeloTrack, List<MeloTrack>) -> Unit,
	onDownloadTrack: (MeloTrack) -> Unit,
	onJumpToSearch: (String) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		turn.userMessage?.let { userMsg ->
			IosDiscoverAiMessageBubble(message = userMsg)
		}

		if (turn.events.isNotEmpty() || (plan != null && plan.steps.isNotEmpty())) {
			IosDiscoverAiTrajectoryCapsule(
				turnKey = turn.key,
				events = turn.events,
				plan = plan,
				isRunning = isRunning,
			)
		}

		turn.assistantMessages.forEach { assistantMsg ->
			IosDiscoverAiMessageBubble(message = assistantMsg)
		}

		val extractedTracks = remember(turn.events, turn.assistantMessages, recentSearchTracks) {
			resolveIosDiscoverTurnMusicMatches(
				events = turn.events,
				assistantMessages = turn.assistantMessages,
				recentSearchTracks = recentSearchTracks,
				apiClient = apiClient,
			)
		}
		val textSuggestions = remember(turn.assistantMessages, extractedTracks) {
			if (extractedTracks.isNotEmpty()) {
				emptyList()
			} else {
				extractIosDiscoverSongSuggestions(turn.assistantMessages)
			}
		}

		if (extractedTracks.isNotEmpty()) {
			Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
				IosSectionLabel(title = "关联曲目 · ${extractedTracks.size}")
				LazyRow(
					horizontalArrangement = Arrangement.spacedBy(8.dp),
					contentPadding = PaddingValues(horizontal = 2.dp),
				) {
					items(
						items = extractedTracks.take(12),
						key = { "ai-track-${turn.key}-${it.id}" },
						contentType = { "ai-music-card" },
					) { track ->
						val isCurrent = currentTrackId == "mk:${track.id}"
						IosDiscoverAiMusicMicroCard(
							track = track,
							isCurrent = isCurrent,
							isPlaying = isCurrent && isPlayerPlaying,
							isEnqueued = track.id in enqueuedIds,
							onPlayClick = { onPlayTrack(track, extractedTracks) },
							onDownloadClick = { onDownloadTrack(track) },
						)
					}
				}
			}
		} else if (textSuggestions.isNotEmpty()) {
			Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
				IosSectionLabel(title = "推荐搜索")
				LazyRow(
					horizontalArrangement = Arrangement.spacedBy(8.dp),
					contentPadding = PaddingValues(horizontal = 2.dp),
				) {
					items(
						items = textSuggestions,
						key = { "ai-suggest-${turn.key}-$it" },
						contentType = { "ai-suggest-chip" },
					) { query ->
						Surface(
							onClick = { onJumpToSearch(query) },
							shape = CircleShape,
							color = MaterialTheme.colorScheme.surfaceContainer,
							border = BorderStroke(
								width = IosHairlineWidth,
								color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
							),
						) {
							Row(
								modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(5.dp),
							) {
								Icon(
									imageVector = Icons.Outlined.Search,
									contentDescription = null,
									tint = MaterialTheme.colorScheme.primary,
									modifier = Modifier.size(14.dp),
								)
								Text(
									text = query,
									style = MaterialTheme.typography.labelMedium,
									color = MaterialTheme.colorScheme.onSurface,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
									modifier = Modifier.widthIn(max = 180.dp),
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
private fun IosDiscoverAiMessageBubble(message: MeloAgentMessage) {
	val isUser = message.role.equals("user", ignoreCase = true)
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
	) {
		Surface(
			shape = if (isUser) {
				RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
			} else {
				RoundedCornerShape(14.dp)
			},
			color = if (isUser) {
				MaterialTheme.colorScheme.primaryContainer
			} else {
				MaterialTheme.colorScheme.surfaceContainer
			},
			border = if (isUser) {
				null
			} else {
				BorderStroke(
					width = IosHairlineWidth,
					color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
				)
			},
			modifier = Modifier.widthIn(max = 324.dp),
		) {
			Column(
				modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
				verticalArrangement = Arrangement.spacedBy(6.dp),
			) {
				if (message.hasImage) {
					Surface(
						shape = CircleShape,
						color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
						border = BorderStroke(
							width = IosHairlineWidth,
							color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
						),
					) {
						Row(
							modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(4.dp),
						) {
							Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(12.dp))
							Text("歌单图片", style = MaterialTheme.typography.labelSmall)
						}
					}
				}
				if (message.content.isNotBlank()) {
					Text(
						text = message.content,
						style = MaterialTheme.typography.bodyMedium,
						color = if (isUser) {
							MaterialTheme.colorScheme.onPrimaryContainer
						} else {
							MaterialTheme.colorScheme.onSurface
						},
					)
				}
			}
		}
	}
}

@Composable
private fun IosDiscoverAiStreamingBubble(
	partial: String,
	detail: String,
	status: String,
	onStop: () -> Unit,
) {
	Surface(
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(14.dp),
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = BorderStroke(
			width = IosHairlineWidth,
			color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
		),
	) {
		Column(
			modifier = Modifier.padding(13.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 1.8.dp)
					Text(
						text = detail.ifBlank { formatIosStatusLabel(status) },
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.primary,
					)
				}
				Row(
					modifier = Modifier
						.clip(CircleShape)
						.clickable(onClick = onStop)
						.padding(horizontal = 6.dp, vertical = 3.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(3.dp),
				) {
					Icon(
						imageVector = Icons.Outlined.Stop,
						contentDescription = null,
						modifier = Modifier.size(14.dp),
						tint = MaterialTheme.colorScheme.error,
					)
					Text(
						text = "停止",
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.error,
					)
				}
			}
			if (partial.isNotBlank()) {
				Text(
					text = partial,
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurface,
				)
			}
		}
	}
}

@Composable
private fun IosDiscoverAiTrajectoryCapsule(
	turnKey: String,
	events: List<MeloAgentEvent>,
	plan: MeloAgentPlan?,
	isRunning: Boolean,
) {
	var expanded by rememberSaveable(turnKey) { mutableStateOf(false) }
	val arrowScale by animateFloatAsState(
		targetValue = if (expanded) -1f else 1f,
		animationSpec = tween(durationMillis = 200),
		label = "IosAiTrajectoryArrow",
	)
	val stepCount = maxOf(events.size, plan?.steps?.size ?: 0)
	val runningEvent = events.lastOrNull { it.status.lowercase() in setOf("running", "waiting_tasks") }
	val hasFailure = events.any { it.status.lowercase() in setOf("failed", "error", "operation_unknown") }
	val headerLabel = when {
		runningEvent != null -> "执行中 · ${IOS_DISCOVER_AI_TOOL_LABELS[runningEvent.tool] ?: runningEvent.tool.ifBlank { "处理中" }}"
		isRunning -> "分析中…"
		events.isNotEmpty() -> "已执行 ${events.size} 步"
		plan != null -> "计划 · ${plan.steps.size} 步"
		else -> "执行轨迹"
	}

	Surface(
		shape = RoundedCornerShape(12.dp),
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = BorderStroke(
			width = IosHairlineWidth,
			color = if (hasFailure) {
				MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
			} else {
				MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
			},
		),
		modifier = Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(12.dp))
			.clickable { expanded = !expanded }
			.animateContentSize(),
	) {
		Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				when {
					runningEvent != null || isRunning -> {
						CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 1.8.dp)
					}
					hasFailure -> {
						Box(
							modifier = Modifier
								.size(6.dp)
								.background(MaterialTheme.colorScheme.error, CircleShape),
						)
					}
					else -> {
						Icon(
							imageVector = Icons.Outlined.AutoAwesome,
							contentDescription = null,
							tint = MaterialTheme.colorScheme.primary,
							modifier = Modifier.size(14.dp),
						)
					}
				}
				Text(
					text = headerLabel,
					style = MaterialTheme.typography.labelMedium.tabularNums(),
					color = MaterialTheme.colorScheme.onSurface,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f),
				)
				if (stepCount > 0) {
					Text(
						text = "$stepCount 步",
						style = MaterialTheme.typography.labelSmall.tabularNums(),
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				Icon(
					imageVector = Icons.Outlined.ExpandMore,
					contentDescription = "展开执行轨迹",
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier
						.size(16.dp)
						.scale(scaleX = 1f, scaleY = arrowScale),
				)
			}

			AnimatedVisibility(visible = expanded) {
				Column(
					modifier = Modifier.padding(top = 8.dp),
					verticalArrangement = Arrangement.spacedBy(8.dp),
				) {
					IosHairlineDivider()

					if (plan != null && plan.steps.isNotEmpty()) {
						Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
							if (plan.goal.isNotBlank()) {
								Text(
									text = "目标：${plan.goal}",
									style = MaterialTheme.typography.labelSmall,
									color = MaterialTheme.colorScheme.primary,
								)
							}
							plan.steps.forEach { step ->
								val done = step.status.lowercase() in setOf("complete", "completed", "done")
								val running = step.status.lowercase() == "running"
								Row(
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.spacedBy(6.dp),
								) {
									when {
										running -> CircularProgressIndicator(
											modifier = Modifier.size(11.dp),
											strokeWidth = 1.6.dp,
										)
										done -> Icon(
											imageVector = Icons.Outlined.Check,
											contentDescription = null,
											tint = MaterialTheme.colorScheme.meloPositive,
											modifier = Modifier.size(13.dp),
										)
										else -> Box(
											modifier = Modifier
												.size(6.dp)
												.background(MaterialTheme.colorScheme.outlineVariant, CircleShape),
										)
									}
									Text(
										text = step.title,
										style = MaterialTheme.typography.bodySmall,
										color = if (done) {
											MaterialTheme.colorScheme.onSurfaceVariant
										} else {
											MaterialTheme.colorScheme.onSurface
										},
									)
								}
							}
						}
					}

					events.forEachIndexed { idx, event ->
						if (idx > 0) {
							IosHairlineDivider()
						}
						IosDiscoverAiEventStepRow(index = idx + 1, event = event)
					}
				}
			}
		}
	}
}

@Composable
private fun IosDiscoverAiEventStepRow(
	index: Int,
	event: MeloAgentEvent,
) {
	var showRawJson by rememberSaveable(event.id, event.turnId, index) { mutableStateOf(false) }
	val statusLower = event.status.lowercase()
	val statusColor = when (statusLower) {
		"complete", "completed" -> MaterialTheme.colorScheme.meloPositive
		"failed", "error", "operation_unknown" -> MaterialTheme.colorScheme.error
		else -> MaterialTheme.colorScheme.primary
	}
	val toolTitle = IOS_DISCOVER_AI_TOOL_LABELS[event.tool]
		?: listOfNotNull(
			event.tool.takeIf { it.isNotBlank() },
			event.operation.takeIf { it.isNotBlank() },
		).joinToString(" · ").ifBlank { "工具执行" }

	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Box(
				modifier = Modifier
					.size(6.dp)
					.background(statusColor, CircleShape),
			)
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = "$index. $toolTitle",
					style = MaterialTheme.typography.labelMedium.tabularNums(),
					color = MaterialTheme.colorScheme.onSurface,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				if (event.detail.isNotBlank()) {
					Text(
						text = event.detail,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
			Text(
				text = formatIosStatusLabel(event.status),
				style = MaterialTheme.typography.labelSmall,
				color = statusColor,
			)
		}

		val resultObj = event.result
		if (resultObj != null) {
			Text(
				text = if (showRawJson) "收起输出" else "原始输出",
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.primary,
				modifier = Modifier
					.padding(start = 14.dp)
					.clip(CircleShape)
					.clickable { showRawJson = !showRawJson }
					.padding(horizontal = 4.dp, vertical = 2.dp),
			)
			AnimatedVisibility(visible = showRawJson) {
				val pretty = remember(resultObj) {
					runCatching {
						iosDiscoverPrettyJson.encodeToString(JsonObject.serializer(), resultObj)
					}.getOrDefault(resultObj.toString())
				}
				Surface(
					shape = RoundedCornerShape(10.dp),
					color = MaterialTheme.colorScheme.surfaceContainerLow,
					border = BorderStroke(
						width = IosHairlineWidth,
						color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
					),
				) {
					Text(
						text = pretty,
						style = MaterialTheme.typography.bodySmall.copy(
							fontFamily = FontFamily.Monospace,
						),
						modifier = Modifier
							.fillMaxWidth()
							.padding(10.dp),
					)
				}
			}
		}
	}
}

@Composable
private fun IosDiscoverAiMusicMicroCard(
	track: MeloTrack,
	isCurrent: Boolean,
	isPlaying: Boolean,
	isEnqueued: Boolean,
	onPlayClick: () -> Unit,
	onDownloadClick: () -> Unit,
) {
	val qualityLabel = remember(track.id, track.qualities, track.playback) {
		extractIosTrackQualityBadge(track)?.first
	}
	val metaLine = remember(track.id, track.artist, track.source, qualityLabel) {
		listOfNotNull(
			track.artist.takeIf { it.isNotBlank() },
			track.source.takeIf { it.isNotBlank() }?.let(::translateIosSourceName),
			qualityLabel?.takeIf { it.isNotBlank() },
		).joinToString(" · ").ifBlank { "全网音源" }
	}

	Surface(
		modifier = Modifier.width(248.dp),
		shape = RoundedCornerShape(14.dp),
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = BorderStroke(
			width = IosHairlineWidth,
			color = if (isCurrent) {
				MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
			} else {
				MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
			},
		),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.clickable(onClick = onPlayClick)
				.padding(10.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(10.dp),
		) {
			CoverArtBox(
				coverUrl = track.cover,
				size = 42.dp,
				cornerRadius = 9.dp,
			)
			Column(
				modifier = Modifier.weight(1f),
				verticalArrangement = Arrangement.spacedBy(2.dp),
			) {
				Text(
					text = track.title.ifBlank { "未知曲目" },
					style = MaterialTheme.typography.labelLarge,
					fontWeight = FontWeight.Medium,
					color = if (isCurrent) {
						MaterialTheme.colorScheme.primary
					} else {
						MaterialTheme.colorScheme.onSurface
					},
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				Text(
					text = metaLine,
					style = MaterialTheme.typography.bodySmall.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(2.dp),
			) {
				IconButton(
					onClick = onPlayClick,
					modifier = Modifier.size(30.dp),
				) {
					Icon(
						imageVector = if (isCurrent && isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
						contentDescription = if (isCurrent && isPlaying) "暂停试听" else "试听",
						tint = MaterialTheme.colorScheme.primary,
						modifier = Modifier.size(18.dp),
					)
				}
				IconButton(
					onClick = onDownloadClick,
					modifier = Modifier.size(30.dp),
				) {
					Icon(
						imageVector = if (isEnqueued) Icons.Outlined.CloudDone else Icons.Outlined.CloudDownload,
						contentDescription = if (isEnqueued) "已入库" else "投递下载",
						tint = if (isEnqueued) {
							MaterialTheme.colorScheme.meloPositive
						} else {
							MaterialTheme.colorScheme.onSurfaceVariant
						},
						modifier = Modifier.size(17.dp),
					)
				}
			}
		}
	}
}

@Composable
private fun IosDiscoverAiSessionSheet(
	sessions: List<MeloAgentSessionSummary>,
	currentSessionId: String?,
	onSelectSession: (String) -> Unit,
	onCreateSession: () -> Unit,
	onDeleteSession: (String) -> Unit,
	onDismiss: () -> Unit,
) {
	ModalBottomSheet(
		onDismissRequest = onDismiss,
		containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp)
				.padding(bottom = 24.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Text(
				text = "AI 对话会话",
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.Bold,
			)
			IosSectionLabel(
				title = "共 ${sessions.size} 个会话",
				trailing = {
					Surface(
						onClick = onCreateSession,
						shape = CircleShape,
						color = MaterialTheme.colorScheme.primary,
					) {
						Row(
							modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(4.dp),
						) {
							Icon(
								imageVector = Icons.Outlined.Add,
								contentDescription = null,
								tint = MaterialTheme.colorScheme.onPrimary,
								modifier = Modifier.size(14.dp),
							)
							Text(
								text = "新建会话",
								style = MaterialTheme.typography.labelMedium,
								color = MaterialTheme.colorScheme.onPrimary,
							)
						}
					}
				},
			)

			if (sessions.isEmpty()) {
				IosQuietEmptyState(
					message = "暂无对话记录",
					icon = Icons.Outlined.Forum,
				)
			} else {
				Surface(
					modifier = Modifier.fillMaxWidth(),
					shape = RoundedCornerShape(14.dp),
					color = MaterialTheme.colorScheme.surfaceContainer,
					border = BorderStroke(
						width = IosHairlineWidth,
						color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
					),
				) {
					LazyColumn(
						modifier = Modifier
							.fillMaxWidth()
							.heightIn(max = 340.dp),
					) {
						itemsIndexed(
							items = sessions,
							key = { _, s -> s.id },
							contentType = { _, _ -> "ai-session-row" },
						) { idx, session ->
							val isCurrent = session.id == currentSessionId
							Column(modifier = Modifier.fillMaxWidth()) {
								Row(
									modifier = Modifier
										.fillMaxWidth()
										.then(
											if (isCurrent) {
												Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f))
											} else {
												Modifier
											},
										)
										.clickable { onSelectSession(session.id) }
										.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.spacedBy(10.dp),
								) {
									if (isCurrent) {
										Box(
											modifier = Modifier
												.size(6.dp)
												.background(MaterialTheme.colorScheme.primary, CircleShape),
										)
									}
									Column(
										modifier = Modifier.weight(1f),
										verticalArrangement = Arrangement.spacedBy(2.dp),
									) {
										Text(
											text = session.title.ifBlank { "未命名对话" },
											style = MaterialTheme.typography.bodyMedium,
											fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
											color = if (isCurrent) {
												MaterialTheme.colorScheme.primary
											} else {
												MaterialTheme.colorScheme.onSurface
											},
											maxLines = 1,
											overflow = TextOverflow.Ellipsis,
										)
										Text(
											text = formatIosStatusLabel(session.status),
											style = MaterialTheme.typography.bodySmall,
											color = MaterialTheme.colorScheme.onSurfaceVariant,
											maxLines = 1,
											overflow = TextOverflow.Ellipsis,
										)
									}
									IconButton(
										onClick = { onDeleteSession(session.id) },
										modifier = Modifier.size(34.dp),
									) {
										Icon(
											imageVector = Icons.Outlined.Delete,
											contentDescription = "删除会话",
											tint = MaterialTheme.colorScheme.error,
											modifier = Modifier.size(18.dp),
										)
									}
								}
								if (idx < sessions.lastIndex) {
									IosHairlineDivider(startIndent = 14.dp)
								}
							}
						}
					}
				}
			}
		}
	}
}

// ==========================================
// AI 会话轮次归并 & 曲目/建议提取（纯 Kotlin，零 JVM 依赖）
// ==========================================

private fun buildIosDiscoverAiTurns(session: MeloAgentSession?): List<IosDiscoverAiTurnBlock> {
	if (session == null) return emptyList()
	val turnMeta = mutableListOf<Pair<String, MeloAgentMessage?>>()
	val turnAssistants = mutableListOf<MutableList<MeloAgentMessage>>()
	val turnEvents = mutableListOf<MutableList<MeloAgentEvent>>()

	session.messages.forEachIndexed { idx, msg ->
		if (msg.role.equals("user", ignoreCase = true)) {
			val tid = msg.turnId.ifBlank { "turn-$idx" }
			turnMeta.add(tid to msg)
			turnAssistants.add(mutableListOf())
			turnEvents.add(mutableListOf())
		} else {
			val lastIdx = turnMeta.lastIndex
			if (lastIdx >= 0 && (msg.turnId.isBlank() || turnMeta[lastIdx].first == msg.turnId)) {
				turnAssistants[lastIdx].add(msg)
			} else {
				val tid = msg.turnId.ifBlank { "assistant-$idx" }
				turnMeta.add(tid to null)
				turnAssistants.add(mutableListOf(msg))
				turnEvents.add(mutableListOf())
			}
		}
	}

	val indexByTurnId = mutableMapOf<String, Int>()
	turnMeta.forEachIndexed { index, (tid, _) ->
		if (tid.isNotBlank() && tid !in indexByTurnId) {
			indexByTurnId[tid] = index
		}
	}

	val unassignedEvents = mutableListOf<MeloAgentEvent>()
	session.events.forEach { ev ->
		val targetIdx = if (ev.turnId.isNotBlank()) indexByTurnId[ev.turnId] else null
		if (targetIdx != null) {
			turnEvents[targetIdx].add(ev)
		} else if (turnMeta.isNotEmpty() && turnMeta.count { it.second != null } <= 1) {
			turnEvents.last().add(ev)
		} else {
			unassignedEvents.add(ev)
		}
	}

	if (unassignedEvents.isNotEmpty()) {
		turnMeta.add("tail-events-${unassignedEvents.first().id}" to null)
		turnAssistants.add(mutableListOf())
		turnEvents.add(unassignedEvents)
	}

	return turnMeta.indices.map { idx ->
		val (tid, userMsg) = turnMeta[idx]
		IosDiscoverAiTurnBlock(
			key = "turn-$idx-$tid",
			turnId = tid,
			userMessage = userMsg,
			assistantMessages = turnAssistants[idx],
			events = turnEvents[idx],
			isLatest = idx == turnMeta.lastIndex,
		)
	}
}

private fun resolveIosDiscoverTurnMusicMatches(
	events: List<MeloAgentEvent>,
	assistantMessages: List<MeloAgentMessage>,
	recentSearchTracks: List<MeloTrack>,
	apiClient: MeloKeepApiClient,
): List<MeloTrack> {
	val collected = LinkedHashMap<String, MeloTrack>()

	fun addTrack(raw: MeloTrack) {
		if (raw.id.isBlank() || raw.title.isBlank() || raw.id in collected) return
		collected[raw.id] = raw.copy(
			cover = apiClient.resolveMediaUrl(raw.cover),
			playback = raw.playback.copy(url = apiClient.resolveMediaUrl(raw.playback.url)),
		)
	}

	events.forEach { ev ->
		val rootObj = ev.result ?: return@forEach
		val dataObj = (rootObj["data"] as? JsonObject) ?: rootObj
		listOfNotNull(dataObj["tracks"] as? JsonArray, rootObj["tracks"] as? JsonArray).forEach { arr ->
			arr.forEach { el ->
				val obj = el as? JsonObject ?: return@forEach
				runCatching { iosDiscoverAiJson.decodeFromJsonElement(MeloTrack.serializer(), obj) }
					.getOrNull()
					?.let(::addTrack)
			}
		}
		val downloadsArr = (dataObj["downloads"] as? JsonArray) ?: (rootObj["downloads"] as? JsonArray)
		downloadsArr?.forEach { el ->
			val trackObj = (el as? JsonObject)?.get("track") as? JsonObject ?: return@forEach
			runCatching { iosDiscoverAiJson.decodeFromJsonElement(MeloTrack.serializer(), trackObj) }
				.getOrNull()
				?.let(::addTrack)
		}
	}

	if (collected.isEmpty() && recentSearchTracks.isNotEmpty() && assistantMessages.isNotEmpty()) {
		val lowerText = assistantMessages.joinToString("\n") { it.content }.lowercase()
		if (lowerText.isNotBlank()) {
			recentSearchTracks.forEach { track ->
				val t = track.title.trim().lowercase()
				if (t.length >= 2 && t in lowerText) {
					addTrack(track)
				}
			}
		}
	}

	return collected.values.toList()
}

private fun extractIosDiscoverSongSuggestions(messages: List<MeloAgentMessage>): List<String> {
	if (messages.isEmpty()) return emptyList()
	val text = messages.joinToString("\n") { it.content }
	val results = LinkedHashSet<String>()
	IOS_DISCOVER_BOOK_TITLE_REGEX.findAll(text).forEach { match ->
		val title = match.groupValues[1].trim()
		if (title.length in 2..30) results.add(title)
	}
	if (results.isEmpty()) {
		IOS_DISCOVER_DASH_TRACK_REGEX.findAll(text).forEach { match ->
			val title = match.groupValues[1].trim()
			val artist = match.groupValues[2].trim()
			if (title.length in 2..28 && artist.isNotBlank() && !title.startsWith("#")) {
				results.add("$title $artist")
			}
		}
	}
	return results.take(4)
}
