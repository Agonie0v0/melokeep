/*
 * Navic / MeloKeep, a Subsonic music streaming & studio app for iOS
 * Copyright (c) 2026 paige & MeloKeep Swarm
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package io.github.agonie0v0.melokeep.ios

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private enum class IosPhoneSourceFilter(val label: String) {
	ALL("全部"),
	NAVIDROME("Navidrome"),
	LOCAL("Melokeep"),
}

private enum class IosPhoneItemStatus {
	DOWNLOADING,
	DOWNLOADED,
	FAILED,
}

private data class IosPhoneDisplayItem(
	val track: IosTrackItem,
	val status: IosPhoneItemStatus,
	val sizeBytes: Long = 0L,
	val progress: Float = 0f,
	val downloadedBytes: Long = 0L,
	val totalBytes: Long = 0L,
	val error: String? = null,
) {
	val isLocalSource: Boolean
		get() = track.sourceType == IosTrackSource.MELOKEEP
}

// =============================================================================
// 1. 可嵌入手机离线下载中心主内容（供工坊「下载到手机」与抽屉复用）
// =============================================================================

@Composable
fun IosPhoneDownloadCenterContent(
	audioPlayer: IosAudioPlayer,
	subsonicManager: IosSubsonicManager,
	embeddedInStudio: Boolean = false,
	bottomClearance: Dp = MeloBottomChromeClearance,
) {
	val playerState by audioPlayer.state.collectAsState()
	val offlineState by audioPlayer.offlineState.collectAsState()
	val scope = rememberCoroutineScope()

	val offlineRecords = remember(playerState.offlineTracks) {
		playerState.offlineTracks.values.toList().sortedByDescending { it.savedAtEpochSec }
	}
	val activeDownloads = offlineState.activeDownloads
	val failedDownloads = offlineState.failedDownloads
	val totalOfflineBytes = remember(playerState.offlineTracks) {
		audioPlayer.totalOfflineBytes()
	}

	var sourceFilterName by rememberSaveable { mutableStateOf(IosPhoneSourceFilter.ALL.name) }
	val sourceFilter = remember(sourceFilterName) {
		IosPhoneSourceFilter.entries.firstOrNull { it.name == sourceFilterName } ?: IosPhoneSourceFilter.ALL
	}
	val setSourceFilter: (IosPhoneSourceFilter) -> Unit = { target ->
		sourceFilterName = target.name
	}
	// 0: 下载中, 1: 已离线, 2: 失败
	var selectedTab by rememberSaveable {
		mutableIntStateOf(if (activeDownloads.isNotEmpty()) 0 else 1)
	}
	var multiSelectMode by rememberSaveable { mutableStateOf(false) }
	val selectedIds = remember { mutableStateMapOf<String, Boolean>() }

	var showClearConfirmDialog by rememberSaveable { mutableStateOf(false) }
	var showBatchDeleteConfirmDialog by rememberSaveable { mutableStateOf(false) }
	var isBatchDownloadingAll by rememberSaveable { mutableStateOf(false) }
	var batchQueueJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

	val allDownloadingItems = remember(activeDownloads) {
		activeDownloads.map { task ->
			IosPhoneDisplayItem(
				track = task.track,
				status = IosPhoneItemStatus.DOWNLOADING,
				progress = task.progress,
				downloadedBytes = task.downloadedBytes,
				totalBytes = task.totalBytes,
			)
		}
	}
	val allDownloadedItems = remember(offlineRecords) {
		offlineRecords.map { rec ->
			IosPhoneDisplayItem(
				track = rec.track,
				status = IosPhoneItemStatus.DOWNLOADED,
				sizeBytes = rec.sizeBytes,
			)
		}
	}
	val allFailedItems = remember(failedDownloads) {
		failedDownloads.map { task ->
			IosPhoneDisplayItem(
				track = task.track,
				status = IosPhoneItemStatus.FAILED,
				error = task.error,
			)
		}
	}

	val allItemsCombined = remember(allDownloadingItems, allDownloadedItems, allFailedItems) {
		allDownloadingItems + allDownloadedItems + allFailedItems
	}
	val allTotalCount = allItemsCombined.size
	val navidromeTotalCount = remember(allItemsCombined) {
		allItemsCombined.count { !it.isLocalSource }
	}
	val localTotalCount = remember(allItemsCombined) {
		allItemsCombined.count { it.isLocalSource }
	}

	fun filterBySource(items: List<IosPhoneDisplayItem>): List<IosPhoneDisplayItem> = when (sourceFilter) {
		IosPhoneSourceFilter.ALL -> items
		IosPhoneSourceFilter.NAVIDROME -> items.filter { !it.isLocalSource }
		IosPhoneSourceFilter.LOCAL -> items.filter { it.isLocalSource }
	}

	val downloadingList = remember(allDownloadingItems, sourceFilter) { filterBySource(allDownloadingItems) }
	val downloadedList = remember(allDownloadedItems, sourceFilter) { filterBySource(allDownloadedItems) }
	val failedList = remember(allFailedItems, sourceFilter) { filterBySource(allFailedItems) }

	val activeItems = when (selectedTab) {
		0 -> downloadingList
		1 -> downloadedList
		else -> failedList
	}

	val hasNonDownloadedTasks = allDownloadingItems.isNotEmpty() || allFailedItems.isNotEmpty()
	val showStatusFilterRow = hasNonDownloadedTasks || selectedTab != 1

	val checkedItems by remember(activeItems, multiSelectMode) {
		derivedStateOf {
			if (!multiSelectMode) emptyList()
			else activeItems.filter { selectedIds[it.track.id] == true }
		}
	}

	LaunchedEffect(selectedTab, sourceFilter) {
		selectedIds.clear()
	}

	val batchBarVisible = multiSelectMode && activeItems.isNotEmpty()

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colorScheme.background),
	) {
		LazyColumn(
			modifier = Modifier.fillMaxSize(),
			contentPadding = PaddingValues(
				start = 16.dp,
				end = 16.dp,
				top = if (embeddedInStudio) 8.dp else 6.dp,
				bottom = bottomClearance + if (batchBarVisible) 96.dp else 16.dp,
			),
		) {
			// 1. 本机存储卡片（IosGroupedCard + 0.5dp 竖向发丝线三栏指标）
			item(key = "ios-phone-storage-card", contentType = "dashboard") {
				IosPhoneStorageOverviewCard(
					offlineCount = offlineRecords.size,
					totalOfflineBytes = totalOfflineBytes,
					freeSpaceBytes = offlineState.freeSpaceBytes,
					activeDownloadCount = activeDownloads.size,
					isBatchQueuing = isBatchDownloadingAll,
					onPrimaryAction = {
						if (activeDownloads.isNotEmpty() || isBatchDownloadingAll) {
							batchQueueJob?.cancel()
							batchQueueJob = null
							isBatchDownloadingAll = false
							audioPlayer.cancelAllDownloads()
						} else {
							isBatchDownloadingAll = true
							batchQueueJob = scope.launch(Dispatchers.Default) {
								try {
									val allSongs = subsonicManager.fetchAllSongsForOffline()
									val unOffline = allSongs.filter { !audioPlayer.isTrackOffline(it.id) }
									if (unOffline.isNotEmpty()) {
										audioPlayer.downloadTracksOffline(unOffline)
									}
								} finally {
									isBatchDownloadingAll = false
									batchQueueJob = null
								}
							}
						}
					},
					onRequestClear = { showClearConfirmDialog = true },
					canClear = offlineRecords.isNotEmpty(),
					modifier = Modifier.padding(bottom = 14.dp),
				)
			}

			// 2. 紧凑来源筛选行 + 快捷播放/多选
			item(key = "ios-phone-source-bar", contentType = "filter-bar") {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(bottom = if (showStatusFilterRow || batchBarVisible) 8.dp else 10.dp),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically,
				) {
					Row(
						modifier = Modifier
							.weight(1f)
							.horizontalScroll(rememberScrollState()),
						horizontalArrangement = Arrangement.spacedBy(14.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						IosPhoneSourceFilterText(
							label = IosPhoneSourceFilter.ALL.label,
							count = allTotalCount,
							selected = sourceFilter == IosPhoneSourceFilter.ALL,
							onClick = { setSourceFilter(IosPhoneSourceFilter.ALL) },
						)
						IosPhoneSourceFilterText(
							label = IosPhoneSourceFilter.NAVIDROME.label,
							count = navidromeTotalCount,
							selected = sourceFilter == IosPhoneSourceFilter.NAVIDROME,
							onClick = { setSourceFilter(IosPhoneSourceFilter.NAVIDROME) },
						)
						IosPhoneSourceFilterText(
							label = IosPhoneSourceFilter.LOCAL.label,
							count = localTotalCount,
							selected = sourceFilter == IosPhoneSourceFilter.LOCAL,
							onClick = { setSourceFilter(IosPhoneSourceFilter.LOCAL) },
						)
					}

					Spacer(modifier = Modifier.width(8.dp))

					Row(
						horizontalArrangement = Arrangement.spacedBy(10.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						if (!multiSelectMode && !showStatusFilterRow && downloadedList.isNotEmpty()) {
							Text(
								text = "播放全部",
								style = MaterialTheme.typography.labelLarge,
								fontWeight = FontWeight.Medium,
								color = MaterialTheme.colorScheme.primary,
								maxLines = 1,
								softWrap = false,
								modifier = Modifier
									.clip(RoundedCornerShape(6.dp))
									.clickable {
										audioPlayer.playQueue(downloadedList.map { it.track }, 0)
									}
									.padding(horizontal = 4.dp, vertical = 4.dp),
							)
						}

						Text(
							text = if (multiSelectMode) "完成" else "多选",
							style = MaterialTheme.typography.labelLarge.copy(
								fontWeight = if (multiSelectMode) FontWeight.SemiBold else FontWeight.Medium,
							),
							color = MaterialTheme.colorScheme.primary,
							maxLines = 1,
							softWrap = false,
							modifier = Modifier
								.clip(RoundedCornerShape(6.dp))
								.clickable {
									val next = !multiSelectMode
									multiSelectMode = next
									if (!next) selectedIds.clear()
								}
								.padding(horizontal = 4.dp, vertical = 4.dp),
						)
					}
				}
			}

			// 3. 状态筛选行（有下载中/失败任务或非默认页时展示）
			if (showStatusFilterRow) {
				item(key = "ios-phone-status-bar", contentType = "tab-bar") {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(bottom = if (batchBarVisible) 8.dp else 10.dp),
						horizontalArrangement = Arrangement.SpaceBetween,
						verticalAlignment = Alignment.CenterVertically,
					) {
						Row(
							modifier = Modifier
								.weight(1f)
								.horizontalScroll(rememberScrollState()),
							horizontalArrangement = Arrangement.spacedBy(6.dp),
							verticalAlignment = Alignment.CenterVertically,
						) {
							IosPhoneStatusPill(
								label = "下载中 ${downloadingList.size}",
								selected = selectedTab == 0,
								dotColor = if (downloadingList.isNotEmpty()) MaterialTheme.colorScheme.primary else null,
								onClick = { selectedTab = 0 },
							)
							IosPhoneStatusPill(
								label = "已离线 ${downloadedList.size}",
								selected = selectedTab == 1,
								onClick = { selectedTab = 1 },
							)
							IosPhoneStatusPill(
								label = "失败 ${failedList.size}",
								selected = selectedTab == 2,
								dotColor = if (failedList.isNotEmpty()) MaterialTheme.colorScheme.error else null,
								onClick = { selectedTab = 2 },
							)
						}

						if (!multiSelectMode) {
							when (selectedTab) {
								0 -> if (downloadingList.isNotEmpty()) {
									Spacer(modifier = Modifier.width(8.dp))
									Text(
										text = "全部取消",
										style = MaterialTheme.typography.labelMedium,
										color = MaterialTheme.colorScheme.error,
										maxLines = 1,
										softWrap = false,
										modifier = Modifier
											.clip(RoundedCornerShape(6.dp))
											.clickable {
												audioPlayer.cancelDownloads(downloadingList.map { it.track.id })
											}
											.padding(horizontal = 4.dp, vertical = 4.dp),
									)
								}
								1 -> if (downloadedList.isNotEmpty()) {
									Spacer(modifier = Modifier.width(8.dp))
									Text(
										text = "播放全部",
										style = MaterialTheme.typography.labelMedium,
										color = MaterialTheme.colorScheme.primary,
										maxLines = 1,
										softWrap = false,
										modifier = Modifier
											.clip(RoundedCornerShape(6.dp))
											.clickable {
												audioPlayer.playQueue(downloadedList.map { it.track }, 0)
											}
											.padding(horizontal = 4.dp, vertical = 4.dp),
									)
								}
								else -> if (failedList.isNotEmpty()) {
									Spacer(modifier = Modifier.width(8.dp))
									Text(
										text = "全部重试",
										style = MaterialTheme.typography.labelMedium,
										color = MaterialTheme.colorScheme.primary,
										maxLines = 1,
										softWrap = false,
										modifier = Modifier
											.clip(RoundedCornerShape(6.dp))
											.clickable {
												audioPlayer.retryFailedDownloads(failedList.map { it.track.id })
											}
											.padding(horizontal = 4.dp, vertical = 4.dp),
									)
								}
							}
						}
					}
				}
			}

			// 4. 多选工具条
			if (batchBarVisible) {
				item(key = "ios-phone-multiselect-bar", contentType = "multiselect-bar") {
					IosPhoneMultiSelectToolbar(
						selectedCount = checkedItems.size,
						totalCount = activeItems.size,
						onToggleSelectAll = {
							val allChecked = checkedItems.size == activeItems.size
							if (allChecked) {
								selectedIds.clear()
							} else {
								activeItems.forEach { selectedIds[it.track.id] = true }
							}
						},
						onInvertSelection = {
							activeItems.forEach { item ->
								val was = selectedIds[item.track.id] == true
								if (was) selectedIds.remove(item.track.id) else selectedIds[item.track.id] = true
							}
						},
						onExit = {
							multiSelectMode = false
							selectedIds.clear()
						},
						modifier = Modifier.padding(bottom = 10.dp),
					)
				}
			}

			// 5. 曲目列表或静谧空态
			if (activeItems.isEmpty()) {
				item(key = "ios-phone-empty", contentType = "empty") {
					val emptyMessage = when (selectedTab) {
						0 -> "暂无下载中的曲目"
						1 -> when (sourceFilter) {
							IosPhoneSourceFilter.LOCAL -> "暂无 Melokeep 离线曲目"
							IosPhoneSourceFilter.NAVIDROME -> "暂无 Navidrome 离线曲目"
							IosPhoneSourceFilter.ALL -> "暂无离线曲目"
						}
						else -> "暂无失败任务"
					}
					val canResetSource = selectedTab == 1 &&
						sourceFilter != IosPhoneSourceFilter.ALL &&
						allTotalCount > 0
					IosQuietEmptyState(
						message = emptyMessage,
						icon = when (selectedTab) {
							0, 1 -> Icons.Rounded.CloudDownload
							else -> Icons.Rounded.Check
						},
						actionLabel = if (canResetSource) "查看全部" else null,
						onAction = if (canResetSource) {
							{ setSourceFilter(IosPhoneSourceFilter.ALL) }
						} else {
							null
						},
						modifier = Modifier.padding(vertical = 8.dp),
					)
				}
			} else {
				val lastIndex = activeItems.lastIndex
				val playableQueue = downloadedList.map { it.track }
				itemsIndexed(
					items = activeItems,
					key = { _, item -> "${item.track.id}-${item.status.name}" },
					contentType = { _, item -> "ios-phone-download-${item.status.name}" },
				) { index, item ->
					val isChecked = selectedIds[item.track.id] == true
					IosPhoneDownloadItemRow(
						item = item,
						isFirst = index == 0,
						isLast = index == lastIndex,
						multiSelectMode = multiSelectMode,
						checked = isChecked,
						onToggleSelect = {
							if (isChecked) {
								selectedIds.remove(item.track.id)
							} else {
								selectedIds[item.track.id] = true
							}
						},
						onEnterMultiSelect = {
							multiSelectMode = true
							selectedIds[item.track.id] = true
						},
						onPlay = {
							val startIdx = playableQueue.indexOfFirst { it.id == item.track.id }.coerceAtLeast(0)
							audioPlayer.playQueue(playableQueue.ifEmpty { listOf(item.track) }, startIdx)
						},
						onCancel = { audioPlayer.cancelDownload(item.track.id) },
						onRetry = { audioPlayer.retryFailedDownload(item.track.id) },
						onDelete = { audioPlayer.deleteOfflineTrack(item.track.id) },
					)
				}
			}
		}

		// 底部多选批处理悬浮操作栏
		AnimatedVisibility(
			visible = batchBarVisible,
			enter = slideInVertically(tween(200)) { it } + fadeIn(tween(150)),
			exit = slideOutVertically(tween(160)) { it } + fadeOut(tween(120)),
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.navigationBarsPadding()
				.padding(
					start = 16.dp,
					end = 16.dp,
					bottom = if (embeddedInStudio) bottomClearance else 16.dp,
				),
		) {
			IosPhoneBatchSelectionDock(
				checkedItems = checkedItems,
				onCancelDownloading = { ids ->
					audioPlayer.cancelDownloads(ids)
					selectedIds.clear()
					multiSelectMode = false
				},
				onRetryFailed = { ids ->
					audioPlayer.retryFailedDownloads(ids)
					selectedIds.clear()
					multiSelectMode = false
				},
				onRemoveFailed = { ids ->
					audioPlayer.cancelDownloads(ids)
					selectedIds.clear()
					multiSelectMode = false
				},
				onPlaySelected = { tracks ->
					audioPlayer.playQueue(tracks, 0)
					selectedIds.clear()
					multiSelectMode = false
				},
				onRequestBatchDelete = { showBatchDeleteConfirmDialog = true },
			)
		}
	}

	if (showClearConfirmDialog) {
		AlertDialog(
			onDismissRequest = { showClearConfirmDialog = false },
			title = { Text("清空本机离线缓存") },
			text = {
				Text("确定要删除当前设备上的全部 ${offlineRecords.size} 首离线音乐吗？此操作不会影响服务器原音频。")
			},
			confirmButton = {
				TextButton(
					onClick = {
						audioPlayer.clearAllOfflineTracks()
						selectedIds.clear()
						multiSelectMode = false
						showClearConfirmDialog = false
					},
				) {
					Text("确定清空", color = MaterialTheme.colorScheme.error)
				}
			},
			dismissButton = {
				TextButton(onClick = { showClearConfirmDialog = false }) {
					Text("取消")
				}
			},
		)
	}

	if (showBatchDeleteConfirmDialog) {
		val checkedDownloadedIds = remember(checkedItems) {
			checkedItems
				.filter { it.status == IosPhoneItemStatus.DOWNLOADED }
				.map { it.track.id }
		}
		AlertDialog(
			onDismissRequest = { showBatchDeleteConfirmDialog = false },
			title = { Text("批量删除离线文件") },
			text = {
				Text("确定要删除选中的 ${checkedDownloadedIds.size} 首离线音乐吗？仅清理本机缓存，不影响服务器原文件。")
			},
			confirmButton = {
				TextButton(
					onClick = {
						if (checkedDownloadedIds.isNotEmpty()) {
							audioPlayer.deleteOfflineTracks(checkedDownloadedIds)
						}
						selectedIds.clear()
						multiSelectMode = false
						showBatchDeleteConfirmDialog = false
					},
				) {
					Text("删除离线", color = MaterialTheme.colorScheme.error)
				}
			},
			dismissButton = {
				TextButton(onClick = { showBatchDeleteConfirmDialog = false }) {
					Text("取消")
				}
			},
		)
	}
}

// =============================================================================
// 2. 兼容抽屉封装（100% 保留既有双签名）
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IosPhoneDownloadCenterSheet(
	audioPlayer: IosAudioPlayer,
	subsonicManager: IosSubsonicManager,
	hostNavBottom: Dp = 0.dp,
	onDismiss: () -> Unit,
) {
	val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
				.height(620.dp),
		) {
			IosCleanTopBar(
				title = "本机离线缓存",
				onBack = onDismiss,
			)
			Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
				IosPhoneDownloadCenterContent(
					audioPlayer = audioPlayer,
					subsonicManager = subsonicManager,
					embeddedInStudio = false,
					bottomClearance = maxOf(hostNavBottom, 16.dp) + 20.dp,
				)
			}
		}
	}
}

@Composable
fun IosPhoneDownloadCenterSheet(
	audioPlayer: IosAudioPlayer,
	subsonicManager: IosSubsonicManager,
	presented: Boolean,
	onDismissRequest: () -> Unit,
) {
	if (presented) {
		IosPhoneDownloadCenterSheet(
			audioPlayer = audioPlayer,
			subsonicManager = subsonicManager,
			hostNavBottom = 0.dp,
			onDismiss = onDismissRequest,
		)
	}
}

// =============================================================================
// 3. 私有子组件（仅本文件使用）
// =============================================================================

@Composable
private fun IosPhoneStorageOverviewCard(
	offlineCount: Int,
	totalOfflineBytes: Long,
	freeSpaceBytes: Long,
	activeDownloadCount: Int,
	isBatchQueuing: Boolean,
	onPrimaryAction: () -> Unit,
	onRequestClear: () -> Unit,
	canClear: Boolean,
	modifier: Modifier = Modifier,
) {
	val isDownloading = activeDownloadCount > 0
	IosGroupedCard(modifier = modifier) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 12.dp),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = "本机存储",
				style = MaterialTheme.typography.titleSmall,
				fontWeight = FontWeight.SemiBold,
				color = MaterialTheme.colorScheme.onSurface,
			)
			Surface(
				shape = RoundedCornerShape(8.dp),
				color = MaterialTheme.colorScheme.surfaceContainerHigh,
			) {
				Text(
					text = "iPhone 本地",
					style = MaterialTheme.typography.labelSmall,
					fontWeight = FontWeight.Medium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
				)
			}
		}

		IosHairlineDivider(modifier = Modifier.padding(horizontal = 16.dp))

		val verticalHairlineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 8.dp, vertical = 14.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			IosPhoneStorageMetric(
				value = "$offlineCount 首",
				label = "已离线",
				modifier = Modifier.weight(1f),
			)
			Box(
				modifier = Modifier
					.width(IosHairlineWidth)
					.height(28.dp)
					.background(verticalHairlineColor),
			)
			IosPhoneStorageMetric(
				value = formatBytesHuman(totalOfflineBytes),
				label = "已占用",
				accent = true,
				modifier = Modifier.weight(1f),
			)
			Box(
				modifier = Modifier
					.width(IosHairlineWidth)
					.height(28.dp)
					.background(verticalHairlineColor),
			)
			IosPhoneStorageMetric(
				value = if (freeSpaceBytes > 0L) formatBytesHuman(freeSpaceBytes) else "—",
				label = "可用",
				modifier = Modifier.weight(1f),
			)
		}

		IosHairlineDivider(modifier = Modifier.padding(horizontal = 16.dp))

		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 14.dp, vertical = 12.dp),
			horizontalArrangement = Arrangement.spacedBy(10.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Surface(
				onClick = onPrimaryAction,
				shape = RoundedCornerShape(11.dp),
				color = if (isDownloading || isBatchQueuing) {
					MaterialTheme.colorScheme.errorContainer
				} else {
					MaterialTheme.colorScheme.primary
				},
				modifier = Modifier
					.weight(1f)
					.height(40.dp),
			) {
				Row(
					modifier = Modifier.fillMaxSize(),
					horizontalArrangement = Arrangement.Center,
					verticalAlignment = Alignment.CenterVertically,
				) {
					if (isBatchQueuing) {
						CircularProgressIndicator(
							modifier = Modifier.size(15.dp),
							strokeWidth = 2.dp,
							color = MaterialTheme.colorScheme.onErrorContainer,
						)
						Spacer(Modifier.width(6.dp))
					}
					Text(
						text = when {
							isBatchQueuing -> "取消排队…"
							isDownloading -> "取消下载 ($activeDownloadCount)"
							else -> "离线全部曲库"
						},
						style = MaterialTheme.typography.labelLarge,
						fontWeight = FontWeight.SemiBold,
						color = if (isDownloading || isBatchQueuing) {
							MaterialTheme.colorScheme.onErrorContainer
						} else {
							MaterialTheme.colorScheme.onPrimary
						},
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}

			Surface(
				onClick = onRequestClear,
				enabled = canClear,
				shape = RoundedCornerShape(11.dp),
				color = MaterialTheme.colorScheme.surfaceContainerHigh,
				modifier = Modifier.height(40.dp),
			) {
				Box(
					modifier = Modifier.padding(horizontal = 18.dp),
					contentAlignment = Alignment.Center,
				) {
					Text(
						text = "清空",
						style = MaterialTheme.typography.labelLarge,
						fontWeight = FontWeight.Medium,
						color = if (canClear) {
							MaterialTheme.colorScheme.error
						} else {
							MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
						},
						maxLines = 1,
						softWrap = false,
					)
				}
			}
		}
	}
}

@Composable
private fun IosPhoneStorageMetric(
	value: String,
	label: String,
	accent: Boolean = false,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier.padding(horizontal = 4.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(3.dp),
	) {
		Text(
			text = value,
			style = MaterialTheme.typography.titleMedium.tabularNums(),
			fontWeight = FontWeight.Bold,
			color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
		Text(
			text = label,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

@Composable
private fun IosPhoneSourceFilterText(
	label: String,
	count: Int,
	selected: Boolean,
	onClick: () -> Unit,
) {
	Text(
		text = "$label $count",
		style = MaterialTheme.typography.labelLarge.tabularNums().copy(
			fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
		),
		color = if (selected) {
			MaterialTheme.colorScheme.onSurface
		} else {
			MaterialTheme.colorScheme.onSurfaceVariant
		},
		maxLines = 1,
		softWrap = false,
		modifier = Modifier
			.clip(RoundedCornerShape(6.dp))
			.clickable(onClick = onClick)
			.padding(horizontal = 2.dp, vertical = 4.dp),
	)
}

@Composable
private fun IosPhoneStatusPill(
	label: String,
	selected: Boolean,
	dotColor: Color? = null,
	onClick: () -> Unit,
) {
	Surface(
		onClick = onClick,
		shape = CircleShape,
		color = if (selected) {
			MaterialTheme.colorScheme.primaryContainer
		} else {
			MaterialTheme.colorScheme.surfaceContainer
		},
		border = if (selected) {
			null
		} else {
			BorderStroke(
				width = IosHairlineWidth,
				color = MaterialTheme.colorScheme.outline.copy(alpha = 0.30f),
			)
		},
	) {
		Row(
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(5.dp),
		) {
			if (dotColor != null) {
				Box(
					modifier = Modifier
						.size(6.dp)
						.clip(CircleShape)
						.background(dotColor),
				)
			}
			Text(
				text = label,
				style = MaterialTheme.typography.labelSmall.tabularNums().copy(
					fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
				),
				color = if (selected) {
					MaterialTheme.colorScheme.onPrimaryContainer
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
				maxLines = 1,
				softWrap = false,
			)
		}
	}
}

@Composable
private fun IosPhoneMultiSelectToolbar(
	selectedCount: Int,
	totalCount: Int,
	onToggleSelectAll: () -> Unit,
	onInvertSelection: () -> Unit,
	onExit: () -> Unit,
	modifier: Modifier = Modifier,
) {
	IosGroupedCard(modifier = modifier) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 14.dp, vertical = 8.dp),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = "已选 $selectedCount / $totalCount 首",
				style = MaterialTheme.typography.labelMedium.tabularNums(),
				fontWeight = FontWeight.SemiBold,
				color = MaterialTheme.colorScheme.onSurface,
			)
			Row(
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = if (selectedCount == totalCount) "全不选" else "全选",
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.primary,
					modifier = Modifier
						.clip(RoundedCornerShape(6.dp))
						.clickable(onClick = onToggleSelectAll)
						.padding(horizontal = 4.dp, vertical = 4.dp),
				)
				Text(
					text = "反选",
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.primary,
					modifier = Modifier
						.clip(RoundedCornerShape(6.dp))
						.clickable(onClick = onInvertSelection)
						.padding(horizontal = 4.dp, vertical = 4.dp),
				)
				Text(
					text = "退出",
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier
						.clip(RoundedCornerShape(6.dp))
						.clickable(onClick = onExit)
						.padding(horizontal = 4.dp, vertical = 4.dp),
				)
			}
		}
	}
}

@Composable
private fun IosPhoneDownloadItemRow(
	item: IosPhoneDisplayItem,
	isFirst: Boolean,
	isLast: Boolean,
	multiSelectMode: Boolean,
	checked: Boolean,
	onToggleSelect: () -> Unit,
	onEnterMultiSelect: () -> Unit,
	onPlay: () -> Unit,
	onCancel: () -> Unit,
	onRetry: () -> Unit,
	onDelete: () -> Unit,
) {
	val itemShape = when {
		isFirst && isLast -> RoundedCornerShape(14.dp)
		isFirst -> RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
		isLast -> RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
		else -> RectangleShape
	}
	val rowBackground = if (multiSelectMode && checked) {
		MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
	} else {
		MaterialTheme.colorScheme.surfaceContainer
	}
	val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
	val sourceLabel = if (item.isLocalSource) "本地库" else "Navidrome"

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clip(itemShape)
			.background(rowBackground)
			.drawWithContent {
				drawContent()
				val strokePx = IosHairlineWidth.toPx()
				if (size.width <= strokePx || size.height <= strokePx) return@drawWithContent
				val half = strokePx / 2f
				val radiusPx = (14.dp.toPx() - half).coerceAtLeast(0f)
				when {
					isFirst && isLast -> {
						drawRoundRect(
							color = borderColor,
							topLeft = Offset(half, half),
							size = Size(size.width - strokePx, size.height - strokePx),
							cornerRadius = CornerRadius(radiusPx, radiusPx),
							style = Stroke(width = strokePx),
						)
					}
					isFirst -> {
						drawRoundRect(
							color = borderColor,
							topLeft = Offset(half, half),
							size = Size(size.width - strokePx, size.height + radiusPx * 2f),
							cornerRadius = CornerRadius(radiusPx, radiusPx),
							style = Stroke(width = strokePx),
						)
					}
					isLast -> {
						drawRoundRect(
							color = borderColor,
							topLeft = Offset(half, -radiusPx * 2f),
							size = Size(size.width - strokePx, size.height + radiusPx * 2f - half),
							cornerRadius = CornerRadius(radiusPx, radiusPx),
							style = Stroke(width = strokePx),
						)
					}
					else -> {
						drawLine(
							color = borderColor,
							start = Offset(half, 0f),
							end = Offset(half, size.height),
							strokeWidth = strokePx,
						)
						drawLine(
							color = borderColor,
							start = Offset(size.width - half, 0f),
							end = Offset(size.width - half, size.height),
							strokeWidth = strokePx,
						)
					}
				}
			}
			.combinedClickable(
				onClick = {
					if (multiSelectMode) {
						onToggleSelect()
					} else if (item.status == IosPhoneItemStatus.DOWNLOADED) {
						onPlay()
					}
				},
				onLongClick = onEnterMultiSelect,
			),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(
					start = 14.dp,
					end = if (multiSelectMode) 14.dp else 8.dp,
					top = 12.dp,
					bottom = 12.dp,
				),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			if (multiSelectMode) {
				Box(
					modifier = Modifier
						.size(20.dp)
						.clip(CircleShape)
						.background(if (checked) MaterialTheme.colorScheme.primary else Color.Transparent)
						.border(
							width = 1.5.dp,
							color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
							shape = CircleShape,
						),
					contentAlignment = Alignment.Center,
				) {
					if (checked) {
						Icon(
							imageVector = Icons.Rounded.Check,
							contentDescription = null,
							tint = MaterialTheme.colorScheme.onPrimary,
							modifier = Modifier.size(13.dp),
						)
					}
				}
			}

			CoverArtBox(
				coverUrl = item.track.coverUrl,
				size = 42.dp,
				cornerRadius = 9.dp,
			)

			Column(
				modifier = Modifier.weight(1f),
				verticalArrangement = Arrangement.spacedBy(3.dp),
			) {
				Text(
					text = item.track.title,
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.onSurface,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)

				val metaLine = remember(item) {
					buildString {
						append(item.track.artist.ifBlank { "未知歌手" })
						when (item.status) {
							IosPhoneItemStatus.DOWNLOADED -> {
								if (item.sizeBytes > 0L) {
									append(" · ")
									append(formatBytesHuman(item.sizeBytes))
								}
								append(" · ")
								append(sourceLabel)
							}
							IosPhoneItemStatus.DOWNLOADING -> {
								if (item.totalBytes > 0L) {
									append(" · ")
									append(formatBytesHuman(item.downloadedBytes))
									append(" / ")
									append(formatBytesHuman(item.totalBytes))
								} else if (item.downloadedBytes > 0L) {
									append(" · ")
									append(formatBytesHuman(item.downloadedBytes))
								}
								append(" · ")
								append(sourceLabel)
							}
							IosPhoneItemStatus.FAILED -> {
								append(" · ")
								append(item.error?.takeIf { it.isNotBlank() } ?: "下载失败")
							}
						}
					}
				}

				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(6.dp),
				) {
					if (item.status == IosPhoneItemStatus.FAILED) {
						Box(
							modifier = Modifier
								.size(6.dp)
								.clip(CircleShape)
								.background(MaterialTheme.colorScheme.error),
						)
					} else if (item.status == IosPhoneItemStatus.DOWNLOADING) {
						Box(
							modifier = Modifier
								.size(6.dp)
								.clip(CircleShape)
								.background(MaterialTheme.colorScheme.primary),
						)
					}
					Text(
						text = metaLine,
						style = MaterialTheme.typography.bodySmall.tabularNums(),
						color = if (item.status == IosPhoneItemStatus.FAILED) {
							MaterialTheme.colorScheme.error
						} else {
							MaterialTheme.colorScheme.onSurfaceVariant
						},
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}

				if (item.status == IosPhoneItemStatus.DOWNLOADING) {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(top = 3.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(8.dp),
					) {
						LinearProgressIndicator(
							progress = { item.progress.coerceIn(0f, 1f) },
							modifier = Modifier
								.weight(1f)
								.height(3.dp)
								.clip(CircleShape),
							color = MaterialTheme.colorScheme.primary,
							trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
						)
						Text(
							text = "${(item.progress * 100).toInt()}%",
							style = MaterialTheme.typography.labelSmall.tabularNums(),
							color = MaterialTheme.colorScheme.primary,
						)
					}
				}
			}

			if (!multiSelectMode) {
				when (item.status) {
					IosPhoneItemStatus.DOWNLOADING -> {
						IconButton(
							onClick = onCancel,
							modifier = Modifier.size(32.dp),
						) {
							Icon(
								imageVector = Icons.Rounded.Close,
								contentDescription = "取消下载",
								tint = MaterialTheme.colorScheme.error,
								modifier = Modifier.size(18.dp),
							)
						}
					}
					IosPhoneItemStatus.DOWNLOADED -> {
						Box {
							var menuExpanded by remember { mutableStateOf(false) }
							IconButton(
								onClick = { menuExpanded = true },
								modifier = Modifier.size(32.dp),
							) {
								Icon(
									imageVector = Icons.Rounded.MoreVert,
									contentDescription = "更多操作",
									tint = MaterialTheme.colorScheme.onSurfaceVariant,
									modifier = Modifier.size(18.dp),
								)
							}
							DropdownMenu(
								expanded = menuExpanded,
								onDismissRequest = { menuExpanded = false },
							) {
								DropdownMenuItem(
									text = { Text("播放") },
									leadingIcon = {
										Icon(
											imageVector = Icons.Rounded.PlayArrow,
											contentDescription = null,
											tint = MaterialTheme.colorScheme.primary,
											modifier = Modifier.size(18.dp),
										)
									},
									onClick = {
										menuExpanded = false
										onPlay()
									},
								)
								DropdownMenuItem(
									text = { Text("多选管理") },
									leadingIcon = {
										Icon(
											imageVector = Icons.Rounded.DoneAll,
											contentDescription = null,
											tint = MaterialTheme.colorScheme.onSurfaceVariant,
											modifier = Modifier.size(18.dp),
										)
									},
									onClick = {
										menuExpanded = false
										onEnterMultiSelect()
									},
								)
								DropdownMenuItem(
									text = { Text("删除离线文件", color = MaterialTheme.colorScheme.error) },
									leadingIcon = {
										Icon(
											imageVector = Icons.Rounded.Delete,
											contentDescription = null,
											tint = MaterialTheme.colorScheme.error,
											modifier = Modifier.size(18.dp),
										)
									},
									onClick = {
										menuExpanded = false
										onDelete()
									},
								)
							}
						}
					}
					IosPhoneItemStatus.FAILED -> {
						Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
							IconButton(
								onClick = onRetry,
								modifier = Modifier.size(32.dp),
							) {
								Icon(
									imageVector = Icons.Rounded.Refresh,
									contentDescription = "重试",
									tint = MaterialTheme.colorScheme.primary,
									modifier = Modifier.size(18.dp),
								)
							}
							IconButton(
								onClick = onCancel,
								modifier = Modifier.size(32.dp),
							) {
								Icon(
									imageVector = Icons.Rounded.Close,
									contentDescription = "移除失败项",
									tint = MaterialTheme.colorScheme.error,
									modifier = Modifier.size(18.dp),
								)
							}
						}
					}
				}
			}
		}

		if (!isLast) {
			IosHairlineDivider(startIndent = 16.dp)
		}
	}
}

@Composable
private fun IosPhoneBatchSelectionDock(
	checkedItems: List<IosPhoneDisplayItem>,
	onCancelDownloading: (List<String>) -> Unit,
	onRetryFailed: (List<String>) -> Unit,
	onRemoveFailed: (List<String>) -> Unit,
	onPlaySelected: (List<IosTrackItem>) -> Unit,
	onRequestBatchDelete: () -> Unit,
) {
	val checkedDownloading = remember(checkedItems) {
		checkedItems.filter { it.status == IosPhoneItemStatus.DOWNLOADING }
	}
	val checkedDownloaded = remember(checkedItems) {
		checkedItems.filter { it.status == IosPhoneItemStatus.DOWNLOADED }
	}
	val checkedFailed = remember(checkedItems) {
		checkedItems.filter { it.status == IosPhoneItemStatus.FAILED }
	}
	val totalSize = remember(checkedDownloaded) {
		checkedDownloaded.sumOf { it.sizeBytes }
	}

	Surface(
		shape = RoundedCornerShape(16.dp),
		color = MaterialTheme.colorScheme.meloGlassSurface,
		border = BorderStroke(
			width = IosHairlineWidth,
			color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
		),
		shadowElevation = 4.dp,
		modifier = Modifier.fillMaxWidth(),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 14.dp, vertical = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Text(
					text = "已选 ${checkedItems.size} 首",
					style = MaterialTheme.typography.labelLarge.tabularNums(),
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.onSurface,
				)
				if (totalSize > 0L) {
					Text(
						text = formatBytesHuman(totalSize),
						style = MaterialTheme.typography.labelSmall.tabularNums(),
						color = MaterialTheme.colorScheme.primary,
					)
				}
			}

			Row(
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				if (checkedDownloading.isNotEmpty()) {
					Button(
						onClick = { onCancelDownloading(checkedDownloading.map { it.track.id }) },
						colors = ButtonDefaults.buttonColors(
							containerColor = MaterialTheme.colorScheme.error,
							contentColor = MaterialTheme.colorScheme.onError,
						),
						shape = CircleShape,
						contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
					) {
						Icon(Icons.Rounded.Close, null, modifier = Modifier.size(16.dp))
						Spacer(Modifier.width(4.dp))
						Text("取消 (${checkedDownloading.size})", maxLines = 1)
					}
				}

				if (checkedFailed.isNotEmpty()) {
					Button(
						onClick = { onRetryFailed(checkedFailed.map { it.track.id }) },
						shape = CircleShape,
						contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
					) {
						Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp))
						Spacer(Modifier.width(4.dp))
						Text("重试 (${checkedFailed.size})", maxLines = 1)
					}
					Surface(
						onClick = { onRemoveFailed(checkedFailed.map { it.track.id }) },
						shape = CircleShape,
						color = MaterialTheme.colorScheme.errorContainer,
						contentColor = MaterialTheme.colorScheme.onErrorContainer,
					) {
						Text(
							text = "移除",
							style = MaterialTheme.typography.labelMedium,
							maxLines = 1,
							modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
						)
					}
				}

				if (checkedDownloaded.isNotEmpty()) {
					Surface(
						onClick = { onPlaySelected(checkedDownloaded.map { it.track }) },
						shape = CircleShape,
						color = MaterialTheme.colorScheme.primaryContainer,
						contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
					) {
						Row(
							modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(4.dp),
						) {
							Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(15.dp))
							Text("播放", style = MaterialTheme.typography.labelMedium, maxLines = 1)
						}
					}

					Button(
						onClick = onRequestBatchDelete,
						colors = ButtonDefaults.buttonColors(
							containerColor = MaterialTheme.colorScheme.error,
							contentColor = MaterialTheme.colorScheme.onError,
						),
						shape = CircleShape,
						contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
					) {
						Icon(Icons.Rounded.Delete, null, modifier = Modifier.size(16.dp))
						Spacer(Modifier.width(4.dp))
						Text("删除 (${checkedDownloaded.size})", maxLines = 1)
					}
				}

				if (checkedItems.isEmpty()) {
					Text(
						text = "请勾选曲目以批量操作",
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
		}
	}
}
