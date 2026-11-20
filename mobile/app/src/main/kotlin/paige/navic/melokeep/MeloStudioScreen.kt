@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package paige.navic.melokeep

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.agonie0v0.melokeep.shared.MeloDownloadJob
import io.github.agonie0v0.melokeep.shared.MeloLibraryJob
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import io.github.agonie0v0.melokeep.shared.MeloStudioController
import io.github.agonie0v0.melokeep.shared.MeloStudioTab
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import paige.navic.database.entity.DownloadStatus
import paige.navic.di.LocalSnackBarState
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.manager.session.DownloadManager
import paige.navic.domain.manager.session.SessionManager
import paige.navic.domain.manager.session.SessionState
import paige.navic.playback.MediaPlayer
import paige.navic.ui.component.layout.NestedTopBarDefaults
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.Refresh
import paige.navic.ui.theme.MeloBottomChromeClearance
import paige.navic.ui.theme.defaultFont
import paige.navic.ui.theme.tabularNums

/**
 * Android 端工坊三栏独立路由枚举（不改动 shared 层 [MeloStudioTab] 以保持 iOS 100% 兼容）。
 */
enum class MeloStudioWorkspaceTab(val label: String) {
	SERVER_DOWNLOADS("下载到库"),
	PHONE_OFFLINE("下载到手机"),
	SCRAPE_JOBS("刮削整理")
}

internal enum class StudioScrapeJobFilter(val label: String) {
	ALL("全部"),
	REVIEW("待审核"),
	RUNNING("进行中"),
	DONE("已完成"),
	ERROR("异常")
}

internal val STUDIO_DOWNLOAD_ACTIVE_STATES = setOf(
	"queued", "resolving", "downloading", "verifying", "publishing", "retry_wait", "processing"
)
internal val STUDIO_DOWNLOAD_PAUSED_STATES = setOf("paused", "pause_requested")
internal val STUDIO_DOWNLOAD_FAILED_STATES = setOf("failed", "cancelled", "needs_attention", "error")
internal val STUDIO_DOWNLOAD_DONE_STATES = setOf("complete", "completed")

internal val STUDIO_SCRAPE_REVIEW_STATES = setOf("preview_ready", "ready", "needs_review")
internal val STUDIO_SCRAPE_RUNNING_STATES = setOf(
	"queued", "scanning", "applying", "running", "identifying", "tagging", "organizing", "indexing", "restoring"
)
internal val STUDIO_SCRAPE_DONE_STATES = setOf("complete", "completed", "restored")
internal val STUDIO_SCRAPE_ERROR_STATES = setOf("failed", "partial", "cancelled", "needs_attention", "paused")
internal val STUDIO_SCRAPE_FINISHED_STATES = STUDIO_SCRAPE_DONE_STATES + setOf("failed", "partial", "cancelled")

internal fun studioIsScrapeJobRunning(status: String): Boolean =
	status.trim().lowercase() in STUDIO_SCRAPE_RUNNING_STATES

internal fun studioIsScrapeJobFinished(status: String): Boolean =
	status.trim().lowercase() in STUDIO_SCRAPE_FINISHED_STATES

internal fun studioFindAssociatedLibraryJob(
	downloadJob: MeloDownloadJob?,
	filePath: String?,
	libraryJobs: List<MeloLibraryJob>,
	ignoredJobIds: Set<String> = emptySet()
): MeloLibraryJob? {
	val procId = downloadJob?.processing?.id?.takeIf { it.isNotBlank() && it !in ignoredJobIds }
	if (procId != null) {
		libraryJobs.firstOrNull { it.id == procId }?.let { return it }
	}
	val normalizedPath = filePath?.trim()?.takeIf { it.isNotBlank() } ?: return null
	return libraryJobs
		.filter { job ->
			job.id !in ignoredJobIds && job.items.any { item ->
				item.originalPath == normalizedPath || item.path == normalizedPath
			}
		}
		.maxByOrNull { it.updatedAt }
}

internal fun studioGroupedItemShape(index: Int, totalCount: Int): RoundedCornerShape {
	val radius = 14.dp
	return when {
		totalCount <= 1 -> RoundedCornerShape(radius)
		index == 0 -> RoundedCornerShape(topStart = radius, topEnd = radius)
		index == totalCount - 1 -> RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
		else -> RoundedCornerShape(0.dp)
	}
}

/**
 * MeloKeep 统一工坊页面：云端「下载到库」队列批控 + 本机「下载到手机」离线管理 + 「刮削整理」候选与回滚。
 * 当 [nested] 为 false 时由外层持久化壳层承载播放舱与导航坞，列表底部留白统一使用 MeloBottomChromeClearance。
 */
@Composable
fun MeloStudioScreen(
	nested: Boolean = false,
	isActiveTab: Boolean = true,
	jumpRequest: Pair<Int, MeloStudioWorkspaceTab>? = null,
	onConsumeJumpRequest: () -> Unit = {},
	onOpenSettings: (() -> Unit)? = null
) {
	val controller = koinInject<MeloStudioController>()
	val settingsStore = koinInject<MeloSettingsStore>()
	val sessionManager = koinInject<SessionManager>()
	val downloadManager = koinInject<DownloadManager>()
	val player = koinInject<MediaPlayer>()
	val preferenceManager = koinInject<PreferenceManager>()
	val snackBarState = LocalSnackBarState.current
	val scope = rememberCoroutineScope()
	val openSettings = rememberOpenMeloSettings(onOpenSettings)

	val state by controller.state.collectAsStateWithLifecycle()
	val serverConfig by settingsStore.config.collectAsStateWithLifecycle()
	val sessionState by sessionManager.sessionState.collectAsStateWithLifecycle()
	var directScanningNavidrome by remember { mutableStateOf(false) }
	var directScanCount by remember { mutableStateOf<Long?>(null) }

	val phoneDownloadingCount by remember(downloadManager) {
		downloadManager.allDownloads
			.map { list -> list.count { it.status == DownloadStatus.DOWNLOADING } }
			.distinctUntilChanged()
	}.collectAsStateWithLifecycle(initialValue = 0)
	val phoneDownloadedCount by downloadManager.downloadCount
		.collectAsStateWithLifecycle(initialValue = 0)

	// 性能铁律：仅订阅 currentSong.id 与 isPaused，阻断播放进度跳秒触发重组
	val playbackTrackState by remember(player) {
		player.uiState
			.map { it.currentSong?.id to it.isPaused }
			.distinctUntilChanged()
	}.collectAsStateWithLifecycle(initialValue = null to true)
	val (currentSongId, isPlayerPaused) = playbackTrackState

	var workspaceTab by rememberSaveable { mutableStateOf(MeloStudioWorkspaceTab.SERVER_DOWNLOADS) }
	var jobFilter by rememberSaveable { mutableStateOf(StudioScrapeJobFilter.ALL) }
	var jobMultiSelectMode by rememberSaveable { mutableStateOf(false) }
	var filePickerOpen by remember { mutableStateOf(false) }
	var diffSheetVisible by remember { mutableStateOf(false) }
	var autoOpenNextJobDetail by remember { mutableStateOf(false) }
	var recentlyDeletedJobIds by remember { mutableStateOf(emptySet<String>()) }

	val selectWorkspaceTab: (MeloStudioWorkspaceTab) -> Unit = { target ->
		workspaceTab = target
		when (target) {
			MeloStudioWorkspaceTab.SERVER_DOWNLOADS -> controller.selectTab(MeloStudioTab.DOWNLOADS)
			MeloStudioWorkspaceTab.SCRAPE_JOBS -> controller.selectTab(MeloStudioTab.LIBRARY)
			MeloStudioWorkspaceTab.PHONE_OFFLINE -> Unit
		}
	}

	LaunchedEffect(jumpRequest?.first, jumpRequest?.second) {
		val target = jumpRequest?.second ?: return@LaunchedEffect
		selectWorkspaceTab(target)
		onConsumeJumpRequest()
	}

	MeloFeedbackObserver(
		errorMessage = state.errorMessage,
		noticeMessage = state.noticeMessage,
		onClearError = controller::clearError,
		onClearNotice = controller::clearNotice
	)

	// 轮询节能：仅在工坊活跃、服务端已认证且停留在云端栏位时启动 2s 轮询，切到纯本机离线时停止轮询
	DisposableEffect(serverConfig.isAuthenticated, isActiveTab, workspaceTab) {
		if (serverConfig.isAuthenticated && isActiveTab && workspaceTab != MeloStudioWorkspaceTab.PHONE_OFFLINE) {
			controller.refreshDownloads(silent = true)
			controller.startDownloadPolling(2_000L)
		} else {
			controller.stopPolling()
		}
		onDispose {
			controller.stopPolling()
		}
	}

	LaunchedEffect(serverConfig.isAuthenticated, isActiveTab, workspaceTab) {
		if (serverConfig.isAuthenticated && isActiveTab && workspaceTab != MeloStudioWorkspaceTab.PHONE_OFFLINE) {
			controller.refreshLibraryJobs()
			if (workspaceTab == MeloStudioWorkspaceTab.SCRAPE_JOBS && state.libraryFiles.isEmpty()) {
				controller.loadLibraryFiles(reset = true)
			}
		}
	}

	LaunchedEffect(state.downloads) {
		if (recentlyDeletedJobIds.isNotEmpty()) {
			val activeProcIds = state.downloads.mapNotNull { it.processing?.id }.toSet()
			recentlyDeletedJobIds = recentlyDeletedJobIds.intersect(activeProcIds)
		}
	}

	LaunchedEffect(state.activeJobDetail?.id, autoOpenNextJobDetail) {
		if (autoOpenNextJobDetail && state.activeJobDetail != null) {
			diffSheetVisible = true
			autoOpenNextJobDetail = false
		}
	}

	val navBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
	val bottomClearance = if (nested) 24.dp + navBottomPadding else MeloBottomChromeClearance + navBottomPadding

	Surface(
		modifier = Modifier.fillMaxSize(),
		color = MaterialTheme.colorScheme.background
	) {
		Column(modifier = Modifier.fillMaxSize()) {
			StudioUnifiedTopBar(
				nested = nested,
				isAuthenticated = serverConfig.isAuthenticated || sessionState is SessionState.Authenticated,
				workspaceTab = workspaceTab,
				activeDownloadCount = state.activeDownloadCount,
				phoneDownloadingCount = phoneDownloadingCount,
				phoneDownloadedCount = phoneDownloadedCount,
				activeScrapeCount = state.activeScrapeCount,
				scanningNavidrome = directScanningNavidrome || state.scanningNavidrome,
				scanCount = directScanCount ?: state.scanStatus?.count,
				onSelectTab = selectWorkspaceTab,
				onTriggerScan = {
					when {
						sessionState is SessionState.Authenticated -> {
							if (!directScanningNavidrome) {
								directScanningNavidrome = true
								scope.launch {
									runCatching { sessionManager.triggerLibraryScan() }
										.onSuccess { res ->
											directScanningNavidrome = false
											if (res.count != null && res.count > 0L) {
												directScanCount = res.count
											}
											snackBarState.currentSnackbarData?.dismiss()
											val suffix = res.count?.takeIf { it > 0L }?.let { "（$it 首）" }.orEmpty()
											snackBarState.showSnackbar("已触发 Navidrome 扫库$suffix")
										}
										.onFailure { err ->
											directScanningNavidrome = false
											snackBarState.currentSnackbarData?.dismiss()
											snackBarState.showSnackbar(err.message ?: "触发 Navidrome 扫库失败")
										}
								}
							}
						}
						serverConfig.isAuthenticated -> {
							controller.triggerNavidromeScan()
						}
						else -> {
							scope.launch {
								snackBarState.currentSnackbarData?.dismiss()
								snackBarState.showSnackbar("请先连接 Navidrome 服务")
							}
						}
					}
				}
			)

			when (workspaceTab) {
				MeloStudioWorkspaceTab.PHONE_OFFLINE -> {
					MeloPhoneDownloadCenterContent(
						modifier = Modifier
							.fillMaxWidth()
							.weight(1f),
						nestedInSheet = false,
						embeddedInStudio = true,
						bottomClearance = bottomClearance
					)
				}

				MeloStudioWorkspaceTab.SERVER_DOWNLOADS -> {
					if (!serverConfig.isAuthenticated) {
						StudioUnauthenticatedPlaceholder(
							message = "尚未连接 MeloKeep 服务",
							onOpenSettings = openSettings,
							onOpenPhoneOffline = { selectWorkspaceTab(MeloStudioWorkspaceTab.PHONE_OFFLINE) },
							bottomClearance = bottomClearance,
							modifier = Modifier.weight(1f)
						)
					} else {
						StudioDownloadsTabContent(
							downloads = state.downloads,
							libraryJobs = state.libraryJobs,
							ignoredLibraryJobIds = recentlyDeletedJobIds,
							loading = state.downloadsLoading,
							bottomClearance = bottomClearance,
							coverShape = preferenceManager.coverArtShape.decreasedShape,
							currentSongId = currentSongId,
							isPlayerPaused = isPlayerPaused,
							onRefresh = { controller.refreshDownloads(silent = false) },
							onPauseAll = controller::pauseAllDownloads,
							onResumeAll = controller::resumeAllDownloads,
							onRetryFailed = controller::retryFailedDownloads,
							onClearCompleted = controller::clearCompletedDownloads,
							onControl = controller::controlDownload,
							onDelete = controller::deleteDownload,
							onPreviewPlay = { job ->
								val song = MeloTrackMapper.toDomainSong(job.track)
								if (currentSongId == song.id) {
									player.togglePlay()
								} else {
									player.playNow(song)
								}
							},
							onGoScrape = { filePath, downloadId ->
								val dlJob = state.downloads.firstOrNull { it.id == downloadId }
								val linkedJob = studioFindAssociatedLibraryJob(
									downloadJob = dlJob,
									filePath = filePath,
									libraryJobs = state.libraryJobs,
									ignoredJobIds = recentlyDeletedJobIds
								)
								val linkedId = linkedJob?.id
									?: dlJob?.processing?.id?.takeIf { it.isNotBlank() && it !in recentlyDeletedJobIds }
								val linkedStatus = (linkedJob?.status
									?: dlJob?.processing?.takeIf { it.id !in recentlyDeletedJobIds }?.status)
									.orEmpty()
									.trim()
									.lowercase()
								if (linkedId != null &&
									linkedStatus in (STUDIO_SCRAPE_REVIEW_STATES + STUDIO_SCRAPE_RUNNING_STATES + setOf("complete", "completed"))
								) {
									controller.clearJobDetail()
									diffSheetVisible = true
									controller.loadLibraryJobDetail(linkedId)
									workspaceTab = MeloStudioWorkspaceTab.SCRAPE_JOBS
									controller.selectTab(MeloStudioTab.LIBRARY)
								} else {
									controller.clearJobDetail()
									autoOpenNextJobDetail = true
									controller.createScrapeJob(
										paths = listOf(filePath),
										action = "both",
										autoApply = false,
										sourceDownloadId = downloadId
									)
									workspaceTab = MeloStudioWorkspaceTab.SCRAPE_JOBS
									controller.selectTab(MeloStudioTab.LIBRARY)
								}
							},
							onRescrape = { filePath, downloadId ->
								controller.clearJobDetail()
								autoOpenNextJobDetail = true
								controller.createScrapeJob(
									paths = listOf(filePath),
									action = "both",
									autoApply = false,
									sourceDownloadId = downloadId
								)
								workspaceTab = MeloStudioWorkspaceTab.SCRAPE_JOBS
								controller.selectTab(MeloStudioTab.LIBRARY)
							}
						)
					}
				}

				MeloStudioWorkspaceTab.SCRAPE_JOBS -> {
					if (!serverConfig.isAuthenticated) {
						StudioUnauthenticatedPlaceholder(
							message = "尚未连接 MeloKeep 服务",
							onOpenSettings = openSettings,
							onOpenPhoneOffline = { selectWorkspaceTab(MeloStudioWorkspaceTab.PHONE_OFFLINE) },
							bottomClearance = bottomClearance,
							modifier = Modifier.weight(1f)
						)
					} else {
						StudioLibraryScrapeTabContent(
							state = state,
							jobFilter = jobFilter,
							multiSelectMode = jobMultiSelectMode,
							nested = nested,
							hasPlayingTrack = currentSongId != null,
							bottomClearance = bottomClearance,
							onFilterChange = { nextFilter ->
								jobFilter = nextFilter
								if (jobMultiSelectMode) {
									controller.clearJobSelection()
								}
							},
							onOpenFilePicker = {
								if (state.libraryFiles.isEmpty()) {
									controller.loadLibraryFiles(reset = true)
								}
								filePickerOpen = true
							},
							onRefreshJobs = controller::refreshLibraryJobs,
							onToggleMultiSelect = {
								val next = !jobMultiSelectMode
								jobMultiSelectMode = next
								if (!next) {
									controller.clearJobSelection()
								}
							},
							onEnterMultiSelectWithJob = { jobId ->
								jobMultiSelectMode = true
								controller.clearJobSelection()
								controller.toggleJobSelection(jobId)
							},
							onToggleJobSelection = controller::toggleJobSelection,
							onSelectAllJobs = controller::selectAllJobs,
							onClearJobSelection = controller::clearJobSelection,
							onClearFinishedJobs = {
								val finishedIds = state.libraryJobs
									.filter { studioIsScrapeJobFinished(it.status) }
									.map { it.id }
								recentlyDeletedJobIds = recentlyDeletedJobIds + finishedIds
								controller.clearFinishedLibraryJobs()
								controller.refreshDownloads(silent = true)
							},
							onDeleteSingleJob = { jobId ->
								recentlyDeletedJobIds = recentlyDeletedJobIds + jobId
								controller.deleteLibraryJob(jobId)
								controller.refreshDownloads(silent = true)
							},
							onBatchDeleteSelected = { ids ->
								recentlyDeletedJobIds = recentlyDeletedJobIds + ids
								controller.batchDeleteLibraryJobs(ids)
								jobMultiSelectMode = false
								controller.refreshDownloads(silent = true)
							},
							onOpenJobDetail = { jobId ->
								diffSheetVisible = true
								controller.loadLibraryJobDetail(jobId)
							},
							onApplyJob = { jobId ->
								controller.applyScrapeJob(jobId, emptyMap(), autoTriggerScan = true)
							},
							onCancelJob = controller::cancelLibraryJob,
							onRetryJob = controller::retryLibraryJob,
							onShowRunningDeleteHint = {
								scope.launch {
									snackBarState.currentSnackbarData?.dismiss()
									snackBarState.showSnackbar("任务正在运行中，请先取消后再删除")
								}
							}
						)
					}
				}
			}
		}
	}

	if (filePickerOpen) {
		StudioLibraryFilePickerSheet(
			state = state,
			hostNavBottom = navBottomPadding,
			onDismissRequest = { filePickerOpen = false },
			onQuerySubmit = { query ->
				controller.loadLibraryFiles(query = query, folder = state.currentFolder, reset = true)
			},
			onFolderSelect = { folder ->
				val nextFolder = if (state.currentFolder == folder) null else folder
				controller.loadLibraryFiles(query = state.fileQuery, folder = nextFolder, reset = true)
			},
			onLoadMore = controller::loadMoreLibraryFiles,
			onToggleFile = controller::toggleFileSelection,
			onSelectAllFiles = controller::selectAllFiles,
			onClearSelection = controller::clearFileSelection,
			onPreviewScrape = {
				controller.clearJobDetail()
				autoOpenNextJobDetail = true
				controller.createScrapeJob(action = "both", autoApply = false)
				filePickerOpen = false
			},
			onDirectOrganize = {
				controller.clearJobDetail()
				autoOpenNextJobDetail = true
				controller.createScrapeJob(action = "both", autoApply = true)
				filePickerOpen = false
			},
			onScrapeScope = { autoApply ->
				controller.clearJobDetail()
				autoOpenNextJobDetail = true
				controller.createScrapeJob(
					paths = emptyList(),
					scopeDir = state.currentFolder.orEmpty(),
					action = "both",
					autoApply = autoApply
				)
				filePickerOpen = false
			}
		)
	}

	if (diffSheetVisible) {
		StudioScrapeDiffSheet(
			job = state.activeJobDetail,
			jobsLoading = state.jobsLoading,
			errorMessage = state.errorMessage,
			noticeMessage = state.noticeMessage,
			fileTagVersions = state.fileTagVersions,
			hostNavBottom = navBottomPadding,
			onDismissRequest = {
				diffSheetVisible = false
				controller.clearJobDetail()
			},
			onClearError = controller::clearError,
			onClearNotice = controller::clearNotice,
			onPreviewCandidate = { jobId, itemKey, candidateId ->
				controller.previewCandidateChoice(jobId, itemKey, candidateId)
			},
			onSearchCandidates = { jobId, itemId, query ->
				controller.searchJobItemCandidates(jobId, itemId, query)
			},
			onKeepExistingPending = { jobId ->
				controller.keepExistingForPendingItems(jobId)
			},
			onLoadFileVersions = controller::loadFileVersions,
			onRestoreFileVersion = { jobId, itemId ->
				controller.restoreFileVersion(jobId, itemId)
			},
			onApplyJob = { jobId, choices, autoTriggerScan ->
				controller.applyScrapeJob(
					id = jobId,
					choices = choices,
					autoTriggerScan = autoTriggerScan
				)
			},
			onCancelJob = controller::cancelLibraryJob,
			onRetryJob = controller::retryLibraryJob
		)
	}
}

@Composable
private fun StudioUnifiedTopBar(
	nested: Boolean,
	isAuthenticated: Boolean,
	workspaceTab: MeloStudioWorkspaceTab,
	activeDownloadCount: Int,
	phoneDownloadingCount: Int,
	phoneDownloadedCount: Int,
	activeScrapeCount: Int,
	scanningNavidrome: Boolean,
	scanCount: Long?,
	onSelectTab: (MeloStudioWorkspaceTab) -> Unit,
	onTriggerScan: () -> Unit
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.statusBarsPadding()
			.padding(top = 8.dp, bottom = 10.dp),
		verticalArrangement = Arrangement.spacedBy(10.dp)
	) {
		// 第一行：干净单行标题「工坊」+ 右侧扫库胶囊（无副标题）
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(start = if (nested) 4.dp else 16.dp, end = 16.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween
		) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(6.dp),
				modifier = Modifier.weight(1f)
			) {
				if (nested) {
					NestedTopBarDefaults.NavigationAction()
				}
				Text(
					text = "工坊",
					style = MaterialTheme.typography.headlineSmall.copy(
						fontFamily = defaultFont(grade = 100),
						fontWeight = FontWeight.Bold
					),
					color = MaterialTheme.colorScheme.onSurface,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
			}

			Spacer(Modifier.width(8.dp))
			Surface(
				modifier = Modifier
					.clip(CircleShape)
					.clickable(enabled = !scanningNavidrome, onClick = onTriggerScan),
				shape = CircleShape,
				color = MaterialTheme.colorScheme.surfaceContainer,
				border = BorderStroke(
					width = MeloHairlineWidth,
					color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
				)
			) {
					Row(
						modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(5.dp)
					) {
						if (scanningNavidrome) {
							CircularProgressIndicator(
								modifier = Modifier.size(13.dp),
								strokeWidth = 1.8.dp
							)
							Text(
								text = "扫库中",
								style = MaterialTheme.typography.labelSmall,
								color = MaterialTheme.colorScheme.primary,
								maxLines = 1,
								softWrap = false
							)
						} else {
							Icon(
								imageVector = Icons.Outlined.Refresh,
								contentDescription = "扫库",
								tint = MaterialTheme.colorScheme.primary,
								modifier = Modifier.size(14.dp)
							)
							val scanLabel = if (scanCount != null && scanCount > 0L) {
								"扫库 · $scanCount"
							} else {
								"扫库"
							}
							Text(
								text = scanLabel,
								style = MaterialTheme.typography.labelSmall.tabularNums(),
								color = MaterialTheme.colorScheme.onSurface,
								maxLines = 1,
								softWrap = false
							)
						}
					}
				}
		}

		// 第二行：全宽严格等宽 3 段式分段胶囊（0.5.dp 发丝描边）
		Surface(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp),
			shape = CircleShape,
			color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
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
				horizontalArrangement = Arrangement.spacedBy(2.dp)
			) {
				MeloStudioWorkspaceTab.entries.forEach { tab ->
					val selected = workspaceTab == tab
					val badgeText: String?
					val isHighlightBadge: Boolean
					when (tab) {
						MeloStudioWorkspaceTab.SERVER_DOWNLOADS -> {
							badgeText = activeDownloadCount.takeIf { it > 0 }?.let { if (it > 99) "99+" else "$it" }
							isHighlightBadge = true
						}
						MeloStudioWorkspaceTab.PHONE_OFFLINE -> {
							when {
								phoneDownloadingCount > 0 -> {
									badgeText = if (phoneDownloadingCount > 99) "99+" else "$phoneDownloadingCount"
									isHighlightBadge = true
								}
								phoneDownloadedCount > 0 -> {
									badgeText = if (phoneDownloadedCount > 999) "999+" else "$phoneDownloadedCount"
									isHighlightBadge = false
								}
								else -> {
									badgeText = null
									isHighlightBadge = false
								}
							}
						}
						MeloStudioWorkspaceTab.SCRAPE_JOBS -> {
							badgeText = activeScrapeCount.takeIf { it > 0 }?.let { if (it > 99) "99+" else "$it" }
							isHighlightBadge = true
						}
					}

					Surface(
						modifier = Modifier
							.weight(1f)
							.clip(CircleShape)
							.clickable { onSelectTab(tab) },
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
								.padding(horizontal = 4.dp, vertical = 7.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.Center
						) {
							Text(
								text = tab.label,
								style = MaterialTheme.typography.labelMedium.copy(
									fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
								),
								color = if (selected) {
									MaterialTheme.colorScheme.onPrimary
								} else {
									MaterialTheme.colorScheme.onSurfaceVariant
								},
								maxLines = 1,
								softWrap = false,
								overflow = TextOverflow.Ellipsis,
								modifier = Modifier.weight(1f, fill = false)
							)
							if (badgeText != null) {
								Spacer(Modifier.width(3.dp))
								val badgeBg = when {
									selected && isHighlightBadge -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f)
									selected -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f)
									isHighlightBadge -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
									else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
								}
								val badgeFg = when {
									selected && isHighlightBadge -> MaterialTheme.colorScheme.onPrimary
									selected -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.92f)
									isHighlightBadge -> MaterialTheme.colorScheme.primary
									else -> MaterialTheme.colorScheme.onSurfaceVariant
								}
								Box(
									modifier = Modifier
										.clip(CircleShape)
										.background(badgeBg)
										.padding(horizontal = 5.dp, vertical = 1.dp),
									contentAlignment = Alignment.Center
								) {
									Text(
										text = badgeText,
										style = MaterialTheme.typography.labelSmall.copy(
											fontSize = 10.sp,
											lineHeight = 12.sp,
											fontWeight = if (isHighlightBadge) FontWeight.Bold else FontWeight.SemiBold
										).tabularNums(),
										color = badgeFg,
										maxLines = 1,
										softWrap = false
									)
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
private fun StudioUnauthenticatedPlaceholder(
	message: String,
	onOpenSettings: () -> Unit,
	onOpenPhoneOffline: () -> Unit,
	bottomClearance: Dp,
	modifier: Modifier = Modifier
) {
	Box(
		modifier = modifier
			.fillMaxSize()
			.padding(bottom = bottomClearance),
		contentAlignment = Alignment.Center
	) {
		Column(
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(8.dp)
		) {
			MeloQuietEmptyState(
				message = message,
				icon = MeloIcons.Cloud,
				actionLabel = "前往配置 MeloKeep",
				onAction = onOpenSettings
			)
			FilledTonalButton(
				onClick = onOpenPhoneOffline,
				shape = CircleShape
			) {
				Icon(
					imageVector = MeloIcons.PhoneAndroid,
					contentDescription = null,
					modifier = Modifier.size(15.dp)
				)
				Spacer(Modifier.width(6.dp))
				Text(
					text = "查看本机离线音乐",
					maxLines = 1,
					softWrap = false
				)
			}
		}
	}
}

/**
 * 工坊队列克制状态指示：6.dp 状态圆点 + 简洁短文案，替代厚重胶囊边框。
 */
@Composable
internal fun StudioStatusDot(
	label: String,
	color: Color,
	modifier: Modifier = Modifier
) {
	Row(
		modifier = modifier,
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(5.dp)
	) {
		Box(
			modifier = Modifier
				.size(6.dp)
				.clip(CircleShape)
				.background(color)
		)
		Text(
			text = label,
			style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
			color = color,
			maxLines = 1,
			softWrap = false
		)
	}
}

@Composable
internal fun StudioBatchControlPill(
	icon: ImageVector,
	label: String,
	enabled: Boolean,
	tint: Color? = null,
	onClick: () -> Unit
) {
	val fg = when {
		!enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.40f)
		tint != null -> tint
		else -> MaterialTheme.colorScheme.onSurface
	}
	Surface(
		modifier = Modifier
			.clip(CircleShape)
			.clickable(enabled = enabled, onClick = onClick),
		shape = CircleShape,
		color = if (enabled) {
			MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f)
		} else {
			MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.50f)
		},
		border = BorderStroke(
			width = MeloHairlineWidth,
			color = MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
		)
	) {
		Row(
			modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(4.dp)
		) {
			Icon(
				imageVector = icon,
				contentDescription = null,
				tint = fg,
				modifier = Modifier.size(13.dp)
			)
			Text(
				text = label,
				style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
				color = fg,
				maxLines = 1,
				softWrap = false
			)
		}
	}
}
