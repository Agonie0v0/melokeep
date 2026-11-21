/*
 * Navic / MeloKeep, a Subsonic music streaming & studio app for Android
 * Copyright (c) 2026 paige & MeloKeep Swarm
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.melokeep

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import paige.navic.database.entity.DownloadEntity
import paige.navic.database.entity.DownloadStatus
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.manager.runtime.StorageManager
import paige.navic.domain.manager.session.DownloadManager
import paige.navic.domain.model.DomainSong
import paige.navic.domain.model.settings.DownloadStorage
import paige.navic.domain.repository.SongRepository
import paige.navic.playback.MediaPlayer
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.filled.Play
import paige.navic.ui.icons.outlined.Check
import paige.navic.ui.icons.outlined.Checklist
import paige.navic.ui.icons.outlined.Close
import paige.navic.ui.icons.outlined.Delete
import paige.navic.ui.icons.outlined.Download
import paige.navic.ui.icons.outlined.MoreVert
import paige.navic.ui.icons.outlined.Refresh
import paige.navic.ui.theme.MeloBottomChromeClearance
import paige.navic.ui.theme.meloGlassSurface
import paige.navic.ui.theme.tabularNums
import paige.navic.util.toFileSize

private enum class PhoneDownloadSourceFilter(val label: String) {
	ALL("全部"),
	NAVIDROME("Navidrome"),
	LOCAL("Melokeep")
}

private data class DownloadSourceCounts(
	val navidromeTotal: Int,
	val localTotal: Int
)

private data class DownloadStatusBuckets(
	val downloading: List<DownloadEntity>,
	val downloaded: List<DownloadEntity>,
	val failed: List<DownloadEntity>
)

/**
 * 本机离线缓存中心全屏页面（路由节点接入）。
 */
@Composable
fun MeloPhoneDownloadCenterScreen(
	onBack: (() -> Unit)? = null
) {
	val backStack = LocalNavStack.current
	val resolvedBack: (() -> Unit)? = onBack ?: if (backStack.size > 1) {
		{ backStack.removeLastOrNull(); Unit }
	} else {
		null
	}

	Scaffold(
		modifier = Modifier.fillMaxSize(),
		containerColor = MaterialTheme.colorScheme.background,
		topBar = {
			MeloCleanTopBar(
				title = "本机离线缓存",
				onBack = resolvedBack,
				applyStatusBarPadding = true
			)
		}
	) { innerPadding ->
		MeloPhoneDownloadCenterContent(
			modifier = Modifier
				.fillMaxSize()
				.padding(innerPadding),
			nestedInSheet = false
		)
	}
}

/**
 * 本机离线缓存中心半屏抽屉（供曲库与我的页面快捷唤起）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeloPhoneDownloadCenterSheet(
	presented: Boolean,
	onDismissRequest: () -> Unit
) {
	if (!presented) return

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
		) {
			MeloCleanTopBar(
				title = "本机离线缓存",
				onBack = onDismissRequest,
				applyStatusBarPadding = false
			)
			MeloPhoneDownloadCenterContent(
				modifier = Modifier.fillMaxWidth(),
				nestedInSheet = true
			)
		}
	}
}

/**
 * 离线下载中心主内容，负责本机存储概览、来源过滤、全库缓存、状态筛选与单选/多选批量管理。
 */
@Composable
fun MeloPhoneDownloadCenterContent(
	modifier: Modifier = Modifier,
	nestedInSheet: Boolean = false,
	embeddedInStudio: Boolean = false,
	bottomClearance: Dp = MeloBottomChromeClearance + 16.dp
) {
	val downloadManager = koinInject<DownloadManager>()
	val songRepository = koinInject<SongRepository>()
	val player = koinInject<MediaPlayer>()
	val storageManager = koinInject<StorageManager>()
	val preferenceManager = koinInject<PreferenceManager>()
	val scope = rememberCoroutineScope()

	val allDownloads by downloadManager.allDownloads.collectAsStateWithLifecycle(emptyList())
	val downloadCount by downloadManager.downloadCount.collectAsStateWithLifecycle(0)
	val downloadSizeBytes by downloadManager.downloadSize.collectAsStateWithLifecycle(0L)
	val isDownloadingLibrary by downloadManager.isDownloadingLibrary.collectAsStateWithLifecycle(false)
	val libraryProgress by downloadManager.libraryDownloadProgress.collectAsStateWithLifecycle(0f)

	val hasExternalStorage = remember { storageManager.isExternalStorageAvailable() }
	var pendingMigrationStorage by remember { mutableStateOf<DownloadStorage?>(null) }
	var showClearConfirmDialog by rememberSaveable { mutableStateOf(false) }
	var showBatchDeleteConfirmDialog by rememberSaveable { mutableStateOf(false) }

	// 来源筛选：全部 / Navidrome / 本地库
	var sourceFilter by rememberSaveable { mutableStateOf(PhoneDownloadSourceFilter.ALL) }
	// 0: 下载中, 1: 已离线, 2: 失败
	var selectedTab by rememberSaveable { mutableIntStateOf(1) }

	// 多选管理状态
	var multiSelectMode by rememberSaveable { mutableStateOf(false) }
	val selectedIds = remember { mutableStateMapOf<String, Boolean>() }

	BackHandler(enabled = multiSelectMode) {
		multiSelectMode = false
		selectedIds.clear()
	}

	val allTotalCount = allDownloads.size
	val sourceCounts = remember(allDownloads) {
		var navTotal = 0
		var localTotal = 0
		allDownloads.forEach { entity ->
			if (MeloTrackMapper.isMeloSongId(entity.songId)) {
				localTotal++
			} else {
				navTotal++
			}
		}
		DownloadSourceCounts(
			navidromeTotal = navTotal,
			localTotal = localTotal
		)
	}
	val navidromeTotalCount = sourceCounts.navidromeTotal
	val localTotalCount = sourceCounts.localTotal

	val hasNonDownloadedTasks = remember(allDownloads) {
		allDownloads.any {
			it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.FAILED
		}
	}

	val sourceFilteredDownloads = remember(allDownloads, sourceFilter) {
		when (sourceFilter) {
			PhoneDownloadSourceFilter.ALL -> allDownloads
			PhoneDownloadSourceFilter.NAVIDROME -> allDownloads.filter { !MeloTrackMapper.isMeloSongId(it.songId) }
			PhoneDownloadSourceFilter.LOCAL -> allDownloads.filter { MeloTrackMapper.isMeloSongId(it.songId) }
		}
	}

	val statusBuckets = remember(sourceFilteredDownloads) {
		val downloading = ArrayList<DownloadEntity>()
		val downloaded = ArrayList<DownloadEntity>()
		val failed = ArrayList<DownloadEntity>()
		sourceFilteredDownloads.forEach { entity ->
			when (entity.status) {
				DownloadStatus.DOWNLOADING -> downloading.add(entity)
				DownloadStatus.DOWNLOADED -> downloaded.add(entity)
				DownloadStatus.FAILED -> failed.add(entity)
				else -> Unit
			}
		}
		DownloadStatusBuckets(
			downloading = downloading,
			downloaded = downloaded,
			failed = failed
		)
	}
	val downloadingList = statusBuckets.downloading
	val downloadedList = statusBuckets.downloaded
	val failedList = statusBuckets.failed

	val activeDownloadEntities = when (selectedTab) {
		0 -> downloadingList
		1 -> downloadedList
		else -> failedList
	}

	val showStatusFilterRow = hasNonDownloadedTasks || selectedTab != 1

	val checkedEntities by remember(activeDownloadEntities) {
		derivedStateOf {
			if (!multiSelectMode) emptyList()
			else activeDownloadEntities.filter { selectedIds[it.songId] == true }
		}
	}

	// 切换视图时同步清理不可见勾选项
	LaunchedEffect(selectedTab, sourceFilter) {
		selectedIds.clear()
	}

	// 仅在曲目 ID 集合变化时查询数据库，避免下载进度刷新反复触发 IO 查询
	val activeSongIds = remember(activeDownloadEntities) {
		activeDownloadEntities.map { it.songId }
	}
	var songMap by remember { mutableStateOf<Map<String, DomainSong>>(emptyMap()) }
	LaunchedEffect(activeSongIds) {
		if (activeSongIds.isNotEmpty()) {
			withContext(Dispatchers.IO) {
				val songs = songRepository.getSongsByIds(activeSongIds)
				songMap = songs.associateBy { it.id }
			}
		} else {
			songMap = emptyMap()
		}
	}

	val freeSpaceFormatted = remember(downloadSizeBytes) {
		storageManager.getCurrentFreeSpace().toFileSize()
	}

	val batchBarVisible = multiSelectMode && activeDownloadEntities.isNotEmpty()
	val isInternalStorage = preferenceManager.downloadStorage == DownloadStorage.Internal

	Box(
		modifier = modifier.background(MaterialTheme.colorScheme.background)
	) {
		LazyColumn(
			modifier = if (nestedInSheet) Modifier.fillMaxWidth() else Modifier.fillMaxSize(),
			contentPadding = PaddingValues(
				start = 16.dp,
				end = 16.dp,
				top = if (embeddedInStudio) 8.dp else 6.dp,
				bottom = (if (nestedInSheet) 32.dp else bottomClearance) +
					if (batchBarVisible) 88.dp else 0.dp
			)
		) {
			// 1. 本机存储紧凑概览卡
			item(key = "phone-storage-dashboard", contentType = "dashboard") {
				val hasActiveDownloads = isDownloadingLibrary || allDownloads.any {
					it.status == DownloadStatus.DOWNLOADING
				}
				PhoneStorageOverviewCard(
					downloadCount = downloadCount,
					downloadSizeFormatted = downloadSizeBytes.toFileSize(),
					freeSpaceFormatted = freeSpaceFormatted,
					isInternalStorage = isInternalStorage,
					hasExternalStorage = hasExternalStorage,
					isDownloadingLibrary = hasActiveDownloads,
					libraryProgress = libraryProgress,
					onSelectStorage = { target ->
						if (preferenceManager.downloadStorage != target) {
							if (downloadCount > 0) {
								pendingMigrationStorage = target
							} else {
								preferenceManager.downloadStorage = target
							}
						}
					},
					onToggleLibraryDownload = {
						if (!hasActiveDownloads) {
							downloadManager.downloadEntireLibrary()
						} else {
							downloadManager.cancelAllActiveDownloads()
						}
					},
					onRequestClear = { showClearConfirmDialog = true },
					modifier = Modifier.padding(bottom = 14.dp)
				)
			}

			// 2. 来源筛选行 + 多选入口
			item(key = "downloads-source-filter-bar", contentType = "filter-bar") {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(
							bottom = if (showStatusFilterRow || batchBarVisible) 8.dp else 10.dp
						),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically
				) {
					Row(
						modifier = Modifier
							.weight(1f)
							.horizontalScroll(rememberScrollState()),
						horizontalArrangement = Arrangement.spacedBy(14.dp),
						verticalAlignment = Alignment.CenterVertically
					) {
						SourceFilterItem(
							label = PhoneDownloadSourceFilter.ALL.label,
							count = allTotalCount,
							selected = sourceFilter == PhoneDownloadSourceFilter.ALL,
							onClick = { sourceFilter = PhoneDownloadSourceFilter.ALL }
						)
						SourceFilterItem(
							label = PhoneDownloadSourceFilter.NAVIDROME.label,
							count = navidromeTotalCount,
							selected = sourceFilter == PhoneDownloadSourceFilter.NAVIDROME,
							onClick = { sourceFilter = PhoneDownloadSourceFilter.NAVIDROME }
						)
						SourceFilterItem(
							label = PhoneDownloadSourceFilter.LOCAL.label,
							count = localTotalCount,
							selected = sourceFilter == PhoneDownloadSourceFilter.LOCAL,
							onClick = { sourceFilter = PhoneDownloadSourceFilter.LOCAL }
						)
					}

					Spacer(modifier = Modifier.width(10.dp))

					Text(
						text = if (multiSelectMode) "完成" else "多选",
						style = MaterialTheme.typography.labelLarge.copy(
							fontWeight = if (multiSelectMode) FontWeight.SemiBold else FontWeight.Medium
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
							.padding(horizontal = 4.dp, vertical = 4.dp)
					)
				}
			}

			// 3. 状态筛选条（仅在存在下载中/失败项或位于非已离线标签时紧凑展示，空闲态不占行）
			if (showStatusFilterRow) {
				item(key = "downloads-tab-bar", contentType = "tab-bar") {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(bottom = if (batchBarVisible) 8.dp else 10.dp),
						horizontalArrangement = Arrangement.SpaceBetween,
						verticalAlignment = Alignment.CenterVertically
					) {
						Row(
							modifier = Modifier
								.weight(1f)
								.horizontalScroll(rememberScrollState()),
							horizontalArrangement = Arrangement.spacedBy(6.dp),
							verticalAlignment = Alignment.CenterVertically
						) {
							DownloadStatusPill(
								label = "下载中 ${downloadingList.size}",
								selected = selectedTab == 0,
								dotColor = if (downloadingList.isNotEmpty()) MaterialTheme.colorScheme.primary else null,
								onClick = { selectedTab = 0 }
							)
							DownloadStatusPill(
								label = "已离线 ${downloadedList.size}",
								selected = selectedTab == 1,
								onClick = { selectedTab = 1 }
							)
							DownloadStatusPill(
								label = "失败 ${failedList.size}",
								selected = selectedTab == 2,
								dotColor = if (failedList.isNotEmpty()) MaterialTheme.colorScheme.error else null,
								onClick = { selectedTab = 2 }
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
												downloadManager.cancelCollectionDownload(downloadingList.map { it.songId })
											}
											.padding(horizontal = 4.dp, vertical = 4.dp)
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
												val songs = downloadedList.mapNotNull { songMap[it.songId] }
												if (songs.isNotEmpty()) {
													player.playNow(songs, 0)
												}
											}
											.padding(horizontal = 4.dp, vertical = 4.dp)
									)
								}
								2 -> if (failedList.isNotEmpty()) {
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
												scope.launch {
													downloadManager.downloadCollection(failedList.map { it.songId })
												}
											}
											.padding(horizontal = 4.dp, vertical = 4.dp)
									)
								}
							}
						}
					}
				}
			}

			// 4. 多选工具条：全选 / 反选 / 退出
			if (batchBarVisible) {
				item(key = "downloads-multiselect-bar", contentType = "multiselect-bar") {
					PhoneMultiSelectToolbar(
						selectedCount = checkedEntities.size,
						totalCount = activeDownloadEntities.size,
						onToggleSelectAll = {
							val allChecked = checkedEntities.size == activeDownloadEntities.size
							if (allChecked) {
								selectedIds.clear()
							} else {
								activeDownloadEntities.forEach { selectedIds[it.songId] = true }
							}
						},
						onInvertSelection = {
							activeDownloadEntities.forEach { entity ->
								val was = selectedIds[entity.songId] == true
								if (was) selectedIds.remove(entity.songId) else selectedIds[entity.songId] = true
							}
						},
						onExit = {
							multiSelectMode = false
							selectedIds.clear()
						},
						modifier = Modifier.padding(bottom = 10.dp)
					)
				}
			}

			// 5. 歌曲项列表（干净分组卡视觉 + 0.5dp 发丝线分割，保持 LazyColumn 虚拟化）
			if (activeDownloadEntities.isEmpty()) {
				item(key = "empty-download-state", contentType = "empty") {
					val emptyMessage = when (selectedTab) {
						0 -> "暂无下载中的曲目"
						1 -> when (sourceFilter) {
							PhoneDownloadSourceFilter.LOCAL -> "暂无 Melokeep 离线曲目"
							PhoneDownloadSourceFilter.NAVIDROME -> "暂无 Navidrome 离线曲目"
							PhoneDownloadSourceFilter.ALL -> "暂无离线曲目"
						}
						else -> "暂无失败任务"
					}
					val canResetSource = selectedTab == 1 &&
						sourceFilter != PhoneDownloadSourceFilter.ALL &&
						allTotalCount > 0
					MeloQuietEmptyState(
						message = emptyMessage,
						icon = when (selectedTab) {
							0, 1 -> Icons.Outlined.Download
							else -> Icons.Outlined.Check
						},
						actionLabel = if (canResetSource) "查看全部" else null,
						onAction = if (canResetSource) {
							{ sourceFilter = PhoneDownloadSourceFilter.ALL }
						} else {
							null
						},
						modifier = Modifier.padding(vertical = 8.dp)
					)
				}
			} else {
				val lastIndex = activeDownloadEntities.lastIndex
				itemsIndexed(
					items = activeDownloadEntities,
					key = { _, entity -> "${entity.songId}-${entity.status}" },
					contentType = { _, entity -> "download-${entity.status.name}" }
				) { index, entity ->
					val song = songMap[entity.songId]
					val isMelo = MeloTrackMapper.isMeloSongId(entity.songId)
					val title = song?.title ?: "歌曲 #${MeloTrackMapper.toRawTrackId(entity.songId).take(8)}"
					val artist = song?.artistName ?: "未知歌手"
					val isChecked = selectedIds[entity.songId] == true

					PhoneDownloadItemRow(
						title = title,
						artist = artist,
						isLocalSource = isMelo,
						entity = entity,
						isFirst = index == 0,
						isLast = index == lastIndex,
						multiSelectMode = multiSelectMode,
						checked = isChecked,
						onToggleSelect = {
							if (isChecked) {
								selectedIds.remove(entity.songId)
							} else {
								selectedIds[entity.songId] = true
							}
						},
						onEnterMultiSelect = {
							multiSelectMode = true
							selectedIds[entity.songId] = true
						},
						onPlay = {
							if (song != null) {
								val allSongs = downloadedList.mapNotNull { songMap[it.songId] }
								val songIndex = allSongs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
								player.playNow(allSongs.ifEmpty { listOf(song) }, songIndex)
							}
						},
						onCancel = { downloadManager.cancelDownload(entity.songId) },
						onRetry = { downloadManager.downloadSong(entity.songId) },
						onDelete = { downloadManager.deleteDownload(entity.songId) }
					)
				}
			}
		}

		// Studio 底部多选批处理悬浮操作栏
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
					bottom = rememberBatchDockBottomClearance(nestedInSheet = nestedInSheet)
				)
		) {
			PhoneBatchSelectionDock(
				checkedEntities = checkedEntities,
				songMap = songMap,
				onCancelDownloading = { ids ->
					downloadManager.cancelCollectionDownload(ids)
					selectedIds.clear()
					multiSelectMode = false
				},
				onRetryFailed = { ids ->
					scope.launch {
						downloadManager.downloadCollection(ids)
						selectedIds.clear()
						multiSelectMode = false
					}
				},
				onRemoveFailed = { ids ->
					downloadManager.cancelCollectionDownload(ids)
					selectedIds.clear()
					multiSelectMode = false
				},
				onPlaySelected = { songs ->
					player.playNow(songs, 0)
					selectedIds.clear()
					multiSelectMode = false
				},
				onRequestBatchDelete = { showBatchDeleteConfirmDialog = true }
			)
		}
	}

	// 弹窗 1：存储位置迁移确认
	pendingMigrationStorage?.let { target ->
		AlertDialog(
			onDismissRequest = { pendingMigrationStorage = null },
			title = { Text("迁移离线缓存") },
			text = {
				Text("检测到当前手机中已有 $downloadCount 首离线歌曲（约 ${downloadSizeBytes.toFileSize()}）。是否将这些文件迁移至${if (target == DownloadStorage.Internal) "内部存储" else "SD 卡"}？")
			},
			confirmButton = {
				TextButton(
					onClick = {
						preferenceManager.downloadStorage = target
						downloadManager.migrateDownloads(target)
						pendingMigrationStorage = null
					}
				) {
					Text("迁移现有文件")
				}
			},
			dismissButton = {
				TextButton(
					onClick = {
						preferenceManager.downloadStorage = target
						pendingMigrationStorage = null
					}
				) {
					Text("仅更改新路径")
				}
			}
		)
	}

	// 弹窗 2：清空离线缓存二次确认
	if (showClearConfirmDialog) {
		AlertDialog(
			onDismissRequest = { showClearConfirmDialog = false },
			title = { Text("清空手机离线缓存") },
			text = {
				Text("确定要删除当前手机上的全部 $downloadCount 首离线音乐及封面缓存吗？此操作不可撤销，且不会影响 Navidrome 与本地库服务器上的音频原件。")
			},
			confirmButton = {
				TextButton(
					onClick = {
						downloadManager.clearAllDownloads()
						selectedIds.clear()
						multiSelectMode = false
						showClearConfirmDialog = false
					}
				) {
					Text("确定清空", color = MaterialTheme.colorScheme.error)
				}
			},
			dismissButton = {
				TextButton(onClick = { showClearConfirmDialog = false }) {
					Text("取消")
				}
			}
		)
	}

	// 弹窗 3：批量删除已选离线文件二次确认
	if (showBatchDeleteConfirmDialog) {
		val checkedDownloadedIds = remember(checkedEntities) {
			checkedEntities
				.filter { it.status == DownloadStatus.DOWNLOADED }
				.map { it.songId }
		}
		AlertDialog(
			onDismissRequest = { showBatchDeleteConfirmDialog = false },
			title = { Text("批量删除离线文件") },
			text = {
				Text("确定要从手机删除选中的 ${checkedDownloadedIds.size} 首离线音乐吗？仅清理手机本机缓存，不会删除服务器原文件。")
			},
			confirmButton = {
				TextButton(
					onClick = {
						if (checkedDownloadedIds.isNotEmpty()) {
							downloadManager.deleteDownloadedCollection(checkedDownloadedIds)
						}
						selectedIds.clear()
						multiSelectMode = false
						showBatchDeleteConfirmDialog = false
					}
				) {
					Text("删除离线", color = MaterialTheme.colorScheme.error)
				}
			},
			dismissButton = {
				TextButton(onClick = { showBatchDeleteConfirmDialog = false }) {
					Text("取消")
				}
			}
		)
	}
}

@Composable
private fun PhoneStorageOverviewCard(
	downloadCount: Int,
	downloadSizeFormatted: String,
	freeSpaceFormatted: String,
	isInternalStorage: Boolean,
	hasExternalStorage: Boolean,
	isDownloadingLibrary: Boolean,
	libraryProgress: Float,
	onSelectStorage: (DownloadStorage) -> Unit,
	onToggleLibraryDownload: () -> Unit,
	onRequestClear: () -> Unit,
	modifier: Modifier = Modifier
) {
	MeloGroupedCard(modifier = modifier) {
		// 顶行：标题 + 内部 / SD 卡分段胶囊
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 12.dp),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically
		) {
			Text(
				text = "本机存储",
				style = MaterialTheme.typography.titleSmall,
				fontWeight = FontWeight.SemiBold,
				color = MaterialTheme.colorScheme.onSurface
			)

			if (hasExternalStorage || !isInternalStorage) {
				Row(
					modifier = Modifier
						.clip(RoundedCornerShape(10.dp))
						.background(MaterialTheme.colorScheme.surfaceContainerHigh)
						.padding(2.dp),
					horizontalArrangement = Arrangement.spacedBy(2.dp),
					verticalAlignment = Alignment.CenterVertically
				) {
					StorageOptionPill(
						label = "内部",
						selected = isInternalStorage,
						onClick = { onSelectStorage(DownloadStorage.Internal) }
					)
					StorageOptionPill(
						label = "SD 卡",
						selected = !isInternalStorage,
						enabled = hasExternalStorage,
						onClick = {
							if (hasExternalStorage) {
								onSelectStorage(DownloadStorage.External)
							}
						}
					)
				}
			} else {
				Surface(
					shape = RoundedCornerShape(8.dp),
					color = MaterialTheme.colorScheme.surfaceContainerHigh
				) {
					Text(
						text = "内部存储",
						style = MaterialTheme.typography.labelSmall,
						fontWeight = FontWeight.Medium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
					)
				}
			}
		}

		MeloHairlineDivider(modifier = Modifier.padding(horizontal = 16.dp))

		// 中间：0.5dp 发丝线分隔的三栏数值行
		val verticalHairlineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 8.dp, vertical = 14.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			StorageMetricColumn(
				value = "$downloadCount 首",
				label = "已离线",
				modifier = Modifier.weight(1f)
			)
			Box(
				modifier = Modifier
					.width(MeloHairlineWidth)
					.height(28.dp)
					.background(verticalHairlineColor)
			)
			StorageMetricColumn(
				value = downloadSizeFormatted,
				label = "已占用",
				accent = true,
				modifier = Modifier.weight(1f)
			)
			Box(
				modifier = Modifier
					.width(MeloHairlineWidth)
					.height(28.dp)
					.background(verticalHairlineColor)
			)
			StorageMetricColumn(
				value = freeSpaceFormatted,
				label = "可用",
				modifier = Modifier.weight(1f)
			)
		}

		// 全库下载细进度条（仅在进行中展示）
		AnimatedVisibility(
			visible = isDownloadingLibrary,
			enter = fadeIn(),
			exit = fadeOut()
		) {
			LibraryDownloadProgressSection(
				libraryProgress = libraryProgress,
				modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
			)
		}

		MeloHairlineDivider(modifier = Modifier.padding(horizontal = 16.dp))

		// 底行：主按钮 + 轻量清空按钮
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 14.dp, vertical = 12.dp),
			horizontalArrangement = Arrangement.spacedBy(10.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			Surface(
				onClick = onToggleLibraryDownload,
				shape = RoundedCornerShape(11.dp),
				color = if (isDownloadingLibrary) {
					MaterialTheme.colorScheme.errorContainer
				} else {
					MaterialTheme.colorScheme.primary
				},
				modifier = Modifier
					.weight(1f)
					.height(40.dp)
			) {
				Box(
					modifier = Modifier.fillMaxSize(),
					contentAlignment = Alignment.Center
				) {
					Text(
						text = if (isDownloadingLibrary) "取消全库离线" else "离线全部曲库",
						style = MaterialTheme.typography.labelLarge,
						fontWeight = FontWeight.SemiBold,
						color = if (isDownloadingLibrary) {
							MaterialTheme.colorScheme.onErrorContainer
						} else {
							MaterialTheme.colorScheme.onPrimary
						},
						maxLines = 1,
						overflow = TextOverflow.Ellipsis
					)
				}
			}

			Surface(
				onClick = onRequestClear,
				shape = RoundedCornerShape(11.dp),
				color = MaterialTheme.colorScheme.surfaceContainerHigh,
				modifier = Modifier.height(40.dp)
			) {
				Box(
					modifier = Modifier.padding(horizontal = 18.dp),
					contentAlignment = Alignment.Center
				) {
					Text(
						text = "清空",
						style = MaterialTheme.typography.labelLarge,
						fontWeight = FontWeight.Medium,
						color = MaterialTheme.colorScheme.error,
						maxLines = 1,
						softWrap = false
					)
				}
			}
		}
	}
}

@Composable
private fun StorageMetricColumn(
	value: String,
	label: String,
	accent: Boolean = false,
	modifier: Modifier = Modifier
) {
	Column(
		modifier = modifier.padding(horizontal = 4.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(3.dp)
	) {
		Text(
			text = value,
			style = MaterialTheme.typography.titleMedium.tabularNums(),
			fontWeight = FontWeight.Bold,
			color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis
		)
		Text(
			text = label,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis
		)
	}
}

@Composable
private fun StorageOptionPill(
	label: String,
	selected: Boolean,
	enabled: Boolean = true,
	onClick: () -> Unit
) {
	Surface(
		onClick = onClick,
		enabled = enabled,
		shape = RoundedCornerShape(8.dp),
		color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
	) {
		Text(
			text = label,
			style = MaterialTheme.typography.labelSmall,
			fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
			color = when {
				!enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
				selected -> MaterialTheme.colorScheme.onPrimary
				else -> MaterialTheme.colorScheme.onSurfaceVariant
			},
			maxLines = 1,
			softWrap = false,
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
		)
	}
}

@Composable
private fun LibraryDownloadProgressSection(
	libraryProgress: Float,
	modifier: Modifier = Modifier
) {
	val animatedLibraryProgress by animateFloatAsState(
		targetValue = libraryProgress.coerceIn(0f, 1f),
		animationSpec = tween(durationMillis = 350, easing = EaseOut),
		label = "LibraryDownloadProgress"
	)
	Column(
		modifier = modifier.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(5.dp)
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically
		) {
			Text(
				text = "正在离线全部曲库…",
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.primary
			)
			Text(
				text = "${(animatedLibraryProgress * 100).toInt()}%",
				style = MaterialTheme.typography.labelSmall.tabularNums(),
				color = MaterialTheme.colorScheme.primary,
				fontWeight = FontWeight.SemiBold
			)
		}
		LinearProgressIndicator(
			progress = { animatedLibraryProgress },
			modifier = Modifier
				.fillMaxWidth()
				.height(4.dp)
				.clip(CircleShape),
			color = MaterialTheme.colorScheme.primary,
			trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
		)
	}
}

@Composable
private fun SourceFilterItem(
	label: String,
	count: Int,
	selected: Boolean,
	onClick: () -> Unit
) {
	Text(
		text = "$label $count",
		style = MaterialTheme.typography.labelLarge.tabularNums().copy(
			fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
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
			.padding(horizontal = 2.dp, vertical = 4.dp)
	)
}

@Composable
private fun DownloadStatusPill(
	label: String,
	selected: Boolean,
	dotColor: Color? = null,
	onClick: () -> Unit
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
				width = MeloHairlineWidth,
				color = MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
			)
		}
	) {
		Row(
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(5.dp)
		) {
			if (dotColor != null) {
				Box(
					modifier = Modifier
						.size(6.dp)
						.clip(CircleShape)
						.background(dotColor)
				)
			}
			Text(
				text = label,
				style = MaterialTheme.typography.labelSmall.tabularNums().copy(
					fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
				),
				color = if (selected) {
					MaterialTheme.colorScheme.onPrimaryContainer
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
				maxLines = 1,
				softWrap = false
			)
		}
	}
}

@Composable
private fun PhoneMultiSelectToolbar(
	selectedCount: Int,
	totalCount: Int,
	onToggleSelectAll: () -> Unit,
	onInvertSelection: () -> Unit,
	onExit: () -> Unit,
	modifier: Modifier = Modifier
) {
	MeloGroupedCard(modifier = modifier) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 14.dp, vertical = 8.dp),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically
		) {
			Text(
				text = "已选 $selectedCount / $totalCount 首",
				style = MaterialTheme.typography.labelMedium.tabularNums(),
				fontWeight = FontWeight.SemiBold,
				color = MaterialTheme.colorScheme.onSurface
			)
			Row(
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				Text(
					text = if (selectedCount == totalCount) "全不选" else "全选",
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.primary,
					modifier = Modifier
						.clip(RoundedCornerShape(6.dp))
						.clickable(onClick = onToggleSelectAll)
						.padding(horizontal = 4.dp, vertical = 4.dp)
				)
				Text(
					text = "反选",
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.primary,
					modifier = Modifier
						.clip(RoundedCornerShape(6.dp))
						.clickable(onClick = onInvertSelection)
						.padding(horizontal = 4.dp, vertical = 4.dp)
				)
				Text(
					text = "退出",
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier
						.clip(RoundedCornerShape(6.dp))
						.clickable(onClick = onExit)
						.padding(horizontal = 4.dp, vertical = 4.dp)
				)
			}
		}
	}
}

@Composable
private fun PhoneDownloadItemRow(
	title: String,
	artist: String,
	isLocalSource: Boolean,
	entity: DownloadEntity,
	isFirst: Boolean,
	isLast: Boolean,
	multiSelectMode: Boolean,
	checked: Boolean,
	onToggleSelect: () -> Unit,
	onEnterMultiSelect: () -> Unit,
	onPlay: () -> Unit,
	onCancel: () -> Unit,
	onRetry: () -> Unit,
	onDelete: () -> Unit
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
	val sourceLabel = if (isLocalSource) "本地库" else "Navidrome"

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clip(itemShape)
			.background(rowBackground)
			.drawWithContent {
				drawContent()
				val strokePx = MeloHairlineWidth.toPx()
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
							style = Stroke(width = strokePx)
						)
					}
					isFirst -> {
						drawRoundRect(
							color = borderColor,
							topLeft = Offset(half, half),
							size = Size(size.width - strokePx, size.height + radiusPx * 2f),
							cornerRadius = CornerRadius(radiusPx, radiusPx),
							style = Stroke(width = strokePx)
						)
					}
					isLast -> {
						drawRoundRect(
							color = borderColor,
							topLeft = Offset(half, -radiusPx * 2f),
							size = Size(size.width - strokePx, size.height + radiusPx * 2f - half),
							cornerRadius = CornerRadius(radiusPx, radiusPx),
							style = Stroke(width = strokePx)
						)
					}
					else -> {
						drawLine(
							color = borderColor,
							start = Offset(half, 0f),
							end = Offset(half, size.height),
							strokeWidth = strokePx
						)
						drawLine(
							color = borderColor,
							start = Offset(size.width - half, 0f),
							end = Offset(size.width - half, size.height),
							strokeWidth = strokePx
						)
					}
				}
			}
			.combinedClickable(
				onClick = {
					if (multiSelectMode) {
						onToggleSelect()
					} else if (entity.status == DownloadStatus.DOWNLOADED) {
						onPlay()
					}
				},
				onLongClick = onEnterMultiSelect
			)
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(
					start = 16.dp,
					end = if (multiSelectMode) 16.dp else 8.dp,
					top = 13.dp,
					bottom = 13.dp
				),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp)
		) {
			if (multiSelectMode) {
				Box(
					modifier = Modifier
						.size(20.dp)
						.clip(CircleShape)
						.background(if (checked) MaterialTheme.colorScheme.primary else Color.Transparent)
						.border(
							width = 1.5.dp,
							color = if (checked) {
								MaterialTheme.colorScheme.primary
							} else {
								MaterialTheme.colorScheme.outline
							},
							shape = CircleShape
						),
					contentAlignment = Alignment.Center
				) {
					if (checked) {
						Icon(
							imageVector = Icons.Outlined.Check,
							contentDescription = null,
							tint = MaterialTheme.colorScheme.onPrimary,
							modifier = Modifier.size(13.dp)
						)
					}
				}
			}

			// 曲目与安静内联元数据
			Column(
				modifier = Modifier.weight(1f),
				verticalArrangement = Arrangement.spacedBy(3.dp)
			) {
				Text(
					text = title,
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.onSurface,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)

				val metaLine = remember(artist, sourceLabel, entity.status, entity.fileSize) {
					buildString {
						append(artist)
						when (entity.status) {
							DownloadStatus.DOWNLOADED -> {
								val entitySize = entity.fileSize ?: 0L
								if (entitySize > 0L) {
									append(" · ")
									append(entitySize.toFileSize())
								}
								append(" · ")
								append(sourceLabel)
							}
							DownloadStatus.FAILED -> {
								append(" · 下载失败 · ")
								append(sourceLabel)
							}
							else -> {
								append(" · ")
								append(sourceLabel)
							}
						}
					}
				}

				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(6.dp)
				) {
					if (entity.status == DownloadStatus.FAILED) {
						Box(
							modifier = Modifier
								.size(6.dp)
								.clip(CircleShape)
								.background(MaterialTheme.colorScheme.error)
						)
					} else if (entity.status == DownloadStatus.DOWNLOADING) {
						Box(
							modifier = Modifier
								.size(6.dp)
								.clip(CircleShape)
								.background(MaterialTheme.colorScheme.primary)
						)
					}
					Text(
						text = metaLine,
						style = MaterialTheme.typography.bodySmall.tabularNums(),
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis
					)
				}

				if (entity.status == DownloadStatus.DOWNLOADING) {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(top = 4.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(8.dp)
					) {
						if (entity.progress in 0f..1f) {
							LinearProgressIndicator(
								progress = { entity.progress.coerceIn(0f, 1f) },
								modifier = Modifier
									.weight(1f)
									.height(3.dp)
									.clip(CircleShape),
								color = MaterialTheme.colorScheme.primary,
								trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
							)
							Text(
								text = "${(entity.progress * 100).toInt()}%",
								style = MaterialTheme.typography.labelSmall.tabularNums(),
								color = MaterialTheme.colorScheme.primary
							)
						} else {
							LinearProgressIndicator(
								modifier = Modifier
									.weight(1f)
									.height(3.dp)
									.clip(CircleShape),
								color = MaterialTheme.colorScheme.primary,
								trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
							)
							Text(
								text = "下载中",
								style = MaterialTheme.typography.labelSmall,
								color = MaterialTheme.colorScheme.primary
							)
						}
					}
				}
			}

			// 行尾动作（非多选模式下展示）
			if (!multiSelectMode) {
				when (entity.status) {
					DownloadStatus.DOWNLOADING -> {
						IconButton(
							onClick = onCancel,
							modifier = Modifier.size(32.dp)
						) {
							Icon(
								imageVector = Icons.Outlined.Close,
								contentDescription = "取消下载",
								tint = MaterialTheme.colorScheme.error,
								modifier = Modifier.size(18.dp)
							)
						}
					}
					DownloadStatus.DOWNLOADED -> {
						Box {
							var menuExpanded by remember { mutableStateOf(false) }
							IconButton(
								onClick = { menuExpanded = true },
								modifier = Modifier.size(32.dp)
							) {
								Icon(
									imageVector = Icons.Outlined.MoreVert,
									contentDescription = "更多操作",
									tint = MaterialTheme.colorScheme.onSurfaceVariant,
									modifier = Modifier.size(18.dp)
								)
							}
							DropdownMenu(
								expanded = menuExpanded,
								onDismissRequest = { menuExpanded = false }
							) {
								DropdownMenuItem(
									text = { Text("播放") },
									leadingIcon = {
										Icon(
											imageVector = Icons.Filled.Play,
											contentDescription = null,
											tint = MaterialTheme.colorScheme.primary,
											modifier = Modifier.size(18.dp)
										)
									},
									onClick = {
										menuExpanded = false
										onPlay()
									}
								)
								DropdownMenuItem(
									text = { Text("多选管理") },
									leadingIcon = {
										Icon(
											imageVector = Icons.Outlined.Checklist,
											contentDescription = null,
											tint = MaterialTheme.colorScheme.onSurfaceVariant,
											modifier = Modifier.size(18.dp)
										)
									},
									onClick = {
										menuExpanded = false
										onEnterMultiSelect()
									}
								)
								DropdownMenuItem(
									text = { Text("删除离线文件", color = MaterialTheme.colorScheme.error) },
									leadingIcon = {
										Icon(
											imageVector = Icons.Outlined.Delete,
											contentDescription = null,
											tint = MaterialTheme.colorScheme.error,
											modifier = Modifier.size(18.dp)
										)
									},
									onClick = {
										menuExpanded = false
										onDelete()
									}
								)
							}
						}
					}
					DownloadStatus.FAILED -> {
						Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
							IconButton(
								onClick = onRetry,
								modifier = Modifier.size(32.dp)
							) {
								Icon(
									imageVector = Icons.Outlined.Refresh,
									contentDescription = "重试",
									tint = MaterialTheme.colorScheme.primary,
									modifier = Modifier.size(18.dp)
								)
							}
							IconButton(
								onClick = onCancel,
								modifier = Modifier.size(32.dp)
							) {
								Icon(
									imageVector = Icons.Outlined.Close,
									contentDescription = "移除失败项",
									tint = MaterialTheme.colorScheme.error,
									modifier = Modifier.size(18.dp)
								)
							}
						}
					}
					else -> Unit
				}
			}
		}

		if (!isLast) {
			MeloHairlineDivider(startIndent = 16.dp)
		}
	}
}

@Composable
private fun PhoneBatchSelectionDock(
	checkedEntities: List<DownloadEntity>,
	songMap: Map<String, DomainSong>,
	onCancelDownloading: (List<String>) -> Unit,
	onRetryFailed: (List<String>) -> Unit,
	onRemoveFailed: (List<String>) -> Unit,
	onPlaySelected: (List<DomainSong>) -> Unit,
	onRequestBatchDelete: () -> Unit
) {
	val checkedBuckets = remember(checkedEntities) {
		val downloading = ArrayList<DownloadEntity>()
		val downloaded = ArrayList<DownloadEntity>()
		val failed = ArrayList<DownloadEntity>()
		checkedEntities.forEach { entity ->
			when (entity.status) {
				DownloadStatus.DOWNLOADING -> downloading.add(entity)
				DownloadStatus.DOWNLOADED -> downloaded.add(entity)
				DownloadStatus.FAILED -> failed.add(entity)
				else -> Unit
			}
		}
		DownloadStatusBuckets(downloading, downloaded, failed)
	}
	val checkedDownloading = checkedBuckets.downloading
	val checkedDownloaded = checkedBuckets.downloaded
	val checkedFailed = checkedBuckets.failed
	val totalSize = remember(checkedDownloaded) {
		checkedDownloaded.sumOf { it.fileSize ?: 0L }
	}
	val pickedSongs = remember(checkedDownloaded, songMap) {
		checkedDownloaded.mapNotNull { songMap[it.songId] }
	}

	Surface(
		shape = RoundedCornerShape(16.dp),
		color = MaterialTheme.colorScheme.meloGlassSurface,
		border = BorderStroke(
			width = MeloHairlineWidth,
			color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
		),
		shadowElevation = 8.dp,
		modifier = Modifier.fillMaxWidth()
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 14.dp, vertical = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween
		) {
			Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Text(
					text = "已选 ${checkedEntities.size} 首",
					style = MaterialTheme.typography.labelLarge.tabularNums(),
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.onSurface
				)
				if (totalSize > 0L) {
					Text(
						text = totalSize.toFileSize(),
						style = MaterialTheme.typography.labelSmall.tabularNums(),
						color = MaterialTheme.colorScheme.primary
					)
				}
			}

			Row(
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				if (checkedDownloading.isNotEmpty()) {
					Button(
						onClick = { onCancelDownloading(checkedDownloading.map { it.songId }) },
						colors = ButtonDefaults.buttonColors(
							containerColor = MaterialTheme.colorScheme.error,
							contentColor = MaterialTheme.colorScheme.onError
						),
						shape = CircleShape,
						contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
					) {
						Icon(Icons.Outlined.Close, null, modifier = Modifier.size(16.dp))
						Spacer(Modifier.width(4.dp))
						Text("批量取消 (${checkedDownloading.size})", maxLines = 1)
					}
				}

				if (checkedFailed.isNotEmpty()) {
					Button(
						onClick = { onRetryFailed(checkedFailed.map { it.songId }) },
						shape = CircleShape,
						contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
					) {
						Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(16.dp))
						Spacer(Modifier.width(4.dp))
						Text("批量重试 (${checkedFailed.size})", maxLines = 1)
					}

					Surface(
						onClick = { onRemoveFailed(checkedFailed.map { it.songId }) },
						shape = CircleShape,
						color = MaterialTheme.colorScheme.errorContainer,
						contentColor = MaterialTheme.colorScheme.onErrorContainer
					) {
						Text(
							text = "移除",
							style = MaterialTheme.typography.labelMedium,
							maxLines = 1,
							modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
						)
					}
				}

				if (checkedDownloaded.isNotEmpty()) {
					if (pickedSongs.isNotEmpty()) {
						Surface(
							onClick = { onPlaySelected(pickedSongs) },
							shape = CircleShape,
							color = MaterialTheme.colorScheme.primaryContainer,
							contentColor = MaterialTheme.colorScheme.onPrimaryContainer
						) {
							Row(
								modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(4.dp)
							) {
								Icon(Icons.Filled.Play, null, modifier = Modifier.size(15.dp))
								Text("播放", style = MaterialTheme.typography.labelMedium, maxLines = 1)
							}
						}
					}

					Button(
						onClick = onRequestBatchDelete,
						colors = ButtonDefaults.buttonColors(
							containerColor = MaterialTheme.colorScheme.error,
							contentColor = MaterialTheme.colorScheme.onError
						),
						shape = CircleShape,
						contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
					) {
						Icon(Icons.Outlined.Delete, null, modifier = Modifier.size(16.dp))
						Spacer(Modifier.width(4.dp))
						Text("删除 (${checkedDownloaded.size})", maxLines = 1)
					}
				}

				if (checkedEntities.isEmpty()) {
					Text(
						text = "请勾选曲目以批量操作",
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
				}
			}
		}
	}
}
