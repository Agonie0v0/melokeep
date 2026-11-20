@file:OptIn(
	ExperimentalMaterial3Api::class,
	ExperimentalFoundationApi::class,
	ExperimentalLayoutApi::class,
)

package io.github.agonie0v0.melokeep.ios

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneIphone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.agonie0v0.melokeep.shared.MeloAiController
import io.github.agonie0v0.melokeep.shared.MeloDownloadJob
import io.github.agonie0v0.melokeep.shared.MeloLibraryJob
import io.github.agonie0v0.melokeep.shared.MeloSearchController
import io.github.agonie0v0.melokeep.shared.MeloServerConfig
import io.github.agonie0v0.melokeep.shared.MeloSettingsController
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import io.github.agonie0v0.melokeep.shared.MeloStudioController
import io.github.agonie0v0.melokeep.shared.MeloStudioTab
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * iOS 工坊三栏工作区枚举：云端下载到库 · 本机下载到手机 · 曲库刮削整理。
 */
enum class IosStudioWorkspaceTab(val label: String) {
	SERVER_DOWNLOADS("下载到库"),
	PHONE_OFFLINE("下载到手机"),
	SCRAPE_JOBS("刮削整理"),
}

internal enum class IosStudioScrapeFilter(val label: String) {
	ALL("全部"),
	REVIEW("待审核"),
	RUNNING("进行中"),
	DONE("已完成"),
	ERROR("异常"),
}

internal val IosStudioDownloadActiveStates = setOf(
	"queued",
	"resolving",
	"downloading",
	"verifying",
	"publishing",
	"retry_wait",
	"processing",
	"pending",
)
internal val IosStudioDownloadPausedStates = setOf("paused", "pause_requested")
internal val IosStudioDownloadFailedStates = setOf("failed", "cancelled", "needs_attention", "error")
internal val IosStudioDownloadDoneStates = setOf("complete", "completed")

internal val IosStudioScrapeReviewStates = setOf("preview_ready", "ready", "needs_review")
internal val IosStudioScrapeRunningStates = setOf(
	"queued",
	"scanning",
	"applying",
	"running",
	"identifying",
	"tagging",
	"organizing",
	"indexing",
	"restoring",
)
internal val IosStudioScrapeDoneStates = setOf("complete", "completed", "restored")
internal val IosStudioScrapeErrorStates = setOf("failed", "partial", "cancelled", "needs_attention", "paused")
internal val IosStudioScrapeFinishedStates = IosStudioScrapeDoneStates + setOf("failed", "partial", "cancelled")

internal fun iosStudioIsScrapeRunning(status: String): Boolean =
	status.trim().lowercase() in IosStudioScrapeRunningStates

internal fun iosStudioIsScrapeFinished(status: String): Boolean =
	status.trim().lowercase() in IosStudioScrapeFinishedStates

internal fun iosStudioFindAssociatedLibraryJob(
	downloadJob: MeloDownloadJob?,
	filePath: String?,
	libraryJobs: List<MeloLibraryJob>,
	ignoredJobIds: Set<String> = emptySet(),
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

internal fun iosStudioGroupedItemShape(index: Int, totalCount: Int): RoundedCornerShape {
	val radius = 14.dp
	return when {
		totalCount <= 1 -> RoundedCornerShape(radius)
		index == 0 -> RoundedCornerShape(topStart = radius, topEnd = radius)
		index == totalCount - 1 -> RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
		else -> RoundedCornerShape(0.dp)
	}
}

internal fun iosStudioResolveMediaUrl(rawPathOrUrl: String?, cfg: MeloServerConfig): String? {
	val trimmed = rawPathOrUrl?.trim() ?: return null
	if (trimmed.isEmpty()) return null
	val hasHttp = trimmed.startsWith("http://", ignoreCase = true) ||
		trimmed.startsWith("https://", ignoreCase = true)
	val hasOtherScheme = trimmed.startsWith("data:", ignoreCase = true) ||
		trimmed.startsWith("blob:", ignoreCase = true) ||
		trimmed.startsWith("file:", ignoreCase = true)
	if (hasOtherScheme) return trimmed
	val base = cfg.baseUrl.trimEnd('/')
	val isRelativeApi = trimmed.startsWith("/api/") || trimmed.startsWith("/downloads/") ||
		trimmed.startsWith("api/") || trimmed.startsWith("downloads/")
	if (!hasHttp && !isRelativeApi) return trimmed
	val fullUrl = when {
		hasHttp -> trimmed
		base.isNotEmpty() -> "$base${if (trimmed.startsWith("/")) trimmed else "/$trimmed"}"
		else -> if (trimmed.startsWith("/")) trimmed else "/$trimmed"
	}
	if (cfg.mediaToken.isBlank()) return fullUrl
	val isProtected = fullUrl.contains("/api/") || fullUrl.contains("/downloads/")
	if (!isProtected || fullUrl.contains("media_token=")) return fullUrl
	val sep = if ('?' in fullUrl) '&' else '?'
	return "$fullUrl${sep}media_token=${cfg.mediaToken}"
}

internal fun iosStudioNormalizeFolder(raw: String?): String {
	val cleaned = raw.orEmpty().replace('\\', '/').trim().trim('/')
	return if (cleaned == ".") "" else cleaned
}

/**
 * 工坊克制状态指示：6.dp Linear 状态圆点 + 短标签。
 */
@Composable
internal fun IosStudioStatusDot(
	label: String,
	color: Color,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier,
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(5.dp),
	) {
		Box(
			modifier = Modifier
				.size(6.dp)
				.clip(CircleShape)
				.background(color),
		)
		Text(
			text = label,
			style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
			color = color,
			maxLines = 1,
			softWrap = false,
		)
	}
}

/**
 * 工坊队列发丝描边批控胶囊。
 */
@Composable
internal fun IosStudioBatchControlPill(
	icon: ImageVector,
	label: String,
	enabled: Boolean,
	tint: Color? = null,
	onClick: () -> Unit,
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
			width = IosHairlineWidth,
			color = MaterialTheme.colorScheme.outline.copy(alpha = 0.30f),
		),
	) {
		Row(
			modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(4.dp),
		) {
			Icon(
				imageVector = icon,
				contentDescription = null,
				tint = fg,
				modifier = Modifier.size(13.dp),
			)
			Text(
				text = label,
				style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
				color = fg,
				maxLines = 1,
				softWrap = false,
			)
		}
	}
}

// ==========================================
// 1. 探索入口（全网搜歌 + AI 助手双模融合）
// ==========================================

@Composable
fun IosDiscoverRoute(
	searchController: MeloSearchController,
	aiController: MeloAiController,
	settingsController: MeloSettingsController,
	settingsStore: MeloSettingsStore,
	studioController: MeloStudioController,
	subsonicManager: IosSubsonicManager,
	audioPlayer: IosAudioPlayer,
	onGoToSettings: () -> Unit,
	bottomPadding: PaddingValues,
) {
	val serverConfig by settingsStore.config.collectAsState()
	val settingsState by settingsController.state.collectAsState()
	val searchState by searchController.state.collectAsState()
	val aiState by aiController.state.collectAsState()
	val studioState by studioController.state.collectAsState()
	val focusManager = LocalFocusManager.current

	// 仅订阅正在播放曲目 ID 与播放态，避免进度跳秒引发列表高频重组
	val playbackSnapshot by remember(audioPlayer) {
		audioPlayer.state
			.map { it.currentTrack?.id to it.isPlaying }
			.distinctUntilChanged()
	}.collectAsState(initial = null to false)
	val (currentTrackId, isPlayerPlaying) = playbackSnapshot

	var discoverModeName by rememberSaveable { mutableStateOf(IosDiscoverMode.SEARCH.name) }
	val discoverMode = remember(discoverModeName) {
		IosDiscoverMode.entries.firstOrNull { it.name == discoverModeName } ?: IosDiscoverMode.SEARCH
	}
	var hasVisitedSearch by rememberSaveable { mutableStateOf(true) }
	var hasVisitedAi by rememberSaveable { mutableStateOf(false) }

	LaunchedEffect(discoverMode) {
		when (discoverMode) {
			IosDiscoverMode.SEARCH -> hasVisitedSearch = true
			IosDiscoverMode.AI -> hasVisitedAi = true
		}
	}

	val safeBottomDp = bottomPadding.calculateBottomPadding().let {
		if (it > 0.dp) it else MeloBottomChromeClearance
	}
	val isConnected = serverConfig.isAuthenticated && settingsState.errorMessage == null
	val activeNavServerFlow = remember(subsonicManager) {
		subsonicManager?.state
			?.map { it.activeServer }
			?.distinctUntilChanged()
			?: flowOf(null)
	}
	val activeNavServer = activeNavServerFlow.collectAsState(initial = null).value
	var directNavScanning by remember { mutableStateOf(false) }
	var directScanCount by remember { mutableStateOf<Long?>(null) }
	var directScanWarning by remember { mutableStateOf<String?>(null) }

	LaunchedEffect(serverConfig.isAuthenticated) {
		if (serverConfig.isAuthenticated) {
			if (searchState.availableSources.isEmpty()) {
				searchController.loadSources()
			}
			if (studioState.downloads.isEmpty()) {
				studioController.refreshDownloads(silent = true)
			}
		}
	}

	val activeDownloadIds = remember(studioState.downloads) {
		studioState.downloads.mapNotNullTo(mutableSetOf()) { it.track.id.takeIf(String::isNotBlank) }
	}
	val allEnqueuedIds = remember(searchState.enqueuedTrackIds, activeDownloadIds) {
		searchState.enqueuedTrackIds + activeDownloadIds
	}

	Column(modifier = Modifier.fillMaxSize()) {
		IosDiscoverTopHeader(
			discoverMode = discoverMode,
			onModeChange = { next ->
				if (discoverMode != next) {
					focusManager.clearFocus()
					discoverModeName = next.name
				}
			},
			isConnected = isConnected,
			onGoToSettings = onGoToSettings,
		)

		val showSearch = discoverMode == IosDiscoverMode.SEARCH
		val showAi = discoverMode == IosDiscoverMode.AI
		val mountSearch = hasVisitedSearch || showSearch
		val mountAi = hasVisitedAi || showAi

		Box(
			modifier = Modifier
				.weight(1f)
				.fillMaxWidth(),
		) {
			if (mountSearch) {
				IosDiscoverKeepAliveSlot(visible = showSearch) {
					if (!serverConfig.isAuthenticated) {
						Box(
							modifier = Modifier
								.fillMaxSize()
								.padding(bottom = safeBottomDp),
							contentAlignment = Alignment.Center,
						) {
							IosDiscoverUnauthenticatedEmpty(onGoToSettings = onGoToSettings)
						}
					} else {
						IosDiscoverSearchContent(
							searchState = searchState,
							enqueuedIds = allEnqueuedIds,
							currentTrackId = currentTrackId,
							isPlayerPlaying = isPlayerPlaying,
							searchController = searchController,
							settingsStore = settingsStore,
							subsonicManager = subsonicManager,
							audioPlayer = audioPlayer,
							safeBottomDp = safeBottomDp,
						)
					}
				}
			}

			if (mountAi) {
				IosDiscoverKeepAliveSlot(visible = showAi) {
					if (!serverConfig.isAuthenticated) {
						Box(
							modifier = Modifier
								.fillMaxSize()
								.padding(bottom = safeBottomDp),
							contentAlignment = Alignment.Center,
						) {
							IosDiscoverUnauthenticatedEmpty(onGoToSettings = onGoToSettings)
						}
					} else {
						IosDiscoverAiContent(
							aiState = aiState,
							recentSearchTracks = searchState.tracks,
							enqueuedIds = allEnqueuedIds,
							currentTrackId = currentTrackId,
							isPlayerPlaying = isPlayerPlaying,
							aiController = aiController,
							searchController = searchController,
							subsonicManager = subsonicManager,
							audioPlayer = audioPlayer,
							safeBottomDp = safeBottomDp,
							onJumpToSearch = { keyword ->
								focusManager.clearFocus()
								discoverModeName = IosDiscoverMode.SEARCH.name
								searchController.updateKeyword(keyword)
								searchController.search(keyword = keyword)
							},
						)
					}
				}
			}
		}
	}
}

// ==========================================
// 2. 工坊入口（下载到库 · 下载到手机 · 刮削整理）
// ==========================================

@Composable
fun IosStudioRoute(
	studioController: MeloStudioController,
	settingsController: MeloSettingsController,
	settingsStore: MeloSettingsStore,
	onGoToSettings: () -> Unit,
	bottomPadding: PaddingValues,
	audioPlayer: IosAudioPlayer? = null,
	subsonicManager: IosSubsonicManager? = null,
	jumpRequest: IosStudioWorkspaceTab? = null,
	onConsumeJumpRequest: () -> Unit = {},
) {
	val serverConfig by settingsStore.config.collectAsState()
	val settingsState by settingsController.state.collectAsState()
	val state by studioController.state.collectAsState()
	val scope = rememberCoroutineScope()

	// 局部订阅本机离线下载数量与当前播放态，阻断高频跳秒重组
	val phoneCountsFlow = remember(audioPlayer) {
		audioPlayer?.offlineState
			?.map { it.activeDownloads.size to it.offlineTracks.size }
			?.distinctUntilChanged()
			?: flowOf(0 to 0)
	}
	val (phoneDownloadingCount, phoneDownloadedCount) = phoneCountsFlow
		.collectAsState(initial = 0 to 0).value

	val playbackFlow = remember(audioPlayer) {
		audioPlayer?.state
			?.map { it.currentTrack?.id to it.isPlaying }
			?.distinctUntilChanged()
			?: flowOf(null to false)
	}
	val (currentTrackId, isPlayerPlaying) = playbackFlow
		.collectAsState(initial = null to false).value

	var workspaceTabName by rememberSaveable { mutableStateOf(IosStudioWorkspaceTab.SERVER_DOWNLOADS.name) }
	val workspaceTab = remember(workspaceTabName) {
		IosStudioWorkspaceTab.entries.firstOrNull { it.name == workspaceTabName }
			?: IosStudioWorkspaceTab.SERVER_DOWNLOADS
	}
	var jobFilterName by rememberSaveable { mutableStateOf(IosStudioScrapeFilter.ALL.name) }
	val jobFilter = remember(jobFilterName) {
		IosStudioScrapeFilter.entries.firstOrNull { it.name == jobFilterName }
			?: IosStudioScrapeFilter.ALL
	}
	var jobMultiSelectMode by rememberSaveable { mutableStateOf(false) }
	var filePickerOpen by remember { mutableStateOf(false) }
	var diffSheetVisible by remember { mutableStateOf(false) }
	var autoOpenNextJobDetail by remember { mutableStateOf(false) }
	var recentlyDeletedJobIds by remember { mutableStateOf(emptySet<String>()) }
	var runningDeleteHint by remember { mutableStateOf<String?>(null) }

	val safeBottomDp = bottomPadding.calculateBottomPadding().let {
		if (it > 0.dp) it else MeloBottomChromeClearance
	}
	val isConnected = serverConfig.isAuthenticated && settingsState.errorMessage == null
	val activeNavServerFlow = remember(subsonicManager) {
		subsonicManager?.state
			?.map { it.activeServer }
			?.distinctUntilChanged()
			?: flowOf(null)
	}
	val activeNavServer = activeNavServerFlow.collectAsState(initial = null).value
	var directNavScanning by remember { mutableStateOf(false) }
	var directScanCount by remember { mutableStateOf<Long?>(null) }
	var directScanWarning by remember { mutableStateOf<String?>(null) }

	val selectWorkspaceTab: (IosStudioWorkspaceTab) -> Unit = { target ->
		workspaceTabName = target.name
		runningDeleteHint = null
		when (target) {
			IosStudioWorkspaceTab.SERVER_DOWNLOADS -> {
				if (serverConfig.isAuthenticated) {
					studioController.selectTab(MeloStudioTab.DOWNLOADS)
				}
			}
			IosStudioWorkspaceTab.SCRAPE_JOBS -> {
				if (serverConfig.isAuthenticated) {
					studioController.selectTab(MeloStudioTab.LIBRARY)
				}
			}
			IosStudioWorkspaceTab.PHONE_OFFLINE -> Unit
		}
	}

	LaunchedEffect(jumpRequest) {
		val target = jumpRequest ?: return@LaunchedEffect
		selectWorkspaceTab(target)
		onConsumeJumpRequest()
	}

	// 轮询节能：仅停留在云端栏位且已认证时启动 2s 轮询，切换到本机离线时停止轮询
	DisposableEffect(serverConfig.isAuthenticated, workspaceTab) {
		if (serverConfig.isAuthenticated && workspaceTab != IosStudioWorkspaceTab.PHONE_OFFLINE) {
			studioController.refreshDownloads(silent = true)
			studioController.startDownloadPolling(2_000L)
		} else {
			studioController.stopPolling()
		}
		onDispose { studioController.stopPolling() }
	}

	LaunchedEffect(serverConfig.isAuthenticated, workspaceTab) {
		if (serverConfig.isAuthenticated && workspaceTab != IosStudioWorkspaceTab.PHONE_OFFLINE) {
			studioController.refreshLibraryJobs()
			if (workspaceTab == IosStudioWorkspaceTab.SCRAPE_JOBS && state.libraryFiles.isEmpty()) {
				studioController.loadLibraryFiles(reset = true)
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

	val normalizedFolders = remember(state.libraryFolders) {
		state.libraryFolders
			.map { iosStudioNormalizeFolder(it.folder) }
			.filter { it.isNotBlank() && it != "." }
			.distinct()
	}

	Column(modifier = Modifier.fillMaxSize()) {
		IosStudioTopHeader(
			workspaceTab = workspaceTab,
			activeDownloadCount = state.activeDownloadCount,
			phoneDownloadingCount = phoneDownloadingCount,
			phoneDownloadedCount = phoneDownloadedCount,
			activeScrapeCount = state.activeScrapeCount,
			scanningNavidrome = state.scanningNavidrome || directNavScanning,
			scanCount = directScanCount ?: state.scanStatus?.count,
			onSelectTab = selectWorkspaceTab,
			onTriggerScan = {
				directScanWarning = null
				when {
					subsonicManager != null && activeNavServer != null -> {
						if (!directNavScanning) {
							scope.launch {
								directNavScanning = true
								try {
									val result = subsonicManager.startScan()
									result.getOrNull()?.let { (_, count) ->
										if (count > 0) directScanCount = count.toLong()
									}
								} finally {
									directNavScanning = false
								}
							}
						}
					}
					serverConfig.isAuthenticated -> {
						studioController.triggerNavidromeScan()
					}
					else -> {
						directScanWarning = "请先连接 Navidrome 服务"
					}
				}
			},
		)

		directScanWarning?.let { warn ->
			Surface(
				shape = RoundedCornerShape(12.dp),
				color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp, vertical = 4.dp)
					.clickable { directScanWarning = null },
			) {
				Text(
					text = warn,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onErrorContainer,
					modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
				)
			}
		}

		Box(
			modifier = Modifier
				.weight(1f)
				.fillMaxWidth(),
		) {
			when (workspaceTab) {
				IosStudioWorkspaceTab.PHONE_OFFLINE -> {
					if (audioPlayer != null && subsonicManager != null) {
						IosPhoneDownloadCenterContent(
							audioPlayer = audioPlayer,
							subsonicManager = subsonicManager,
							embeddedInStudio = true,
							bottomClearance = safeBottomDp,
						)
					} else {
						Box(
							modifier = Modifier
								.fillMaxSize()
								.padding(bottom = safeBottomDp),
							contentAlignment = Alignment.Center,
						) {
							IosQuietEmptyState(
								message = "本机离线音乐中心暂不可用",
								icon = Icons.Outlined.PhoneIphone,
							)
						}
					}
				}

				IosStudioWorkspaceTab.SERVER_DOWNLOADS -> {
					if (!serverConfig.isAuthenticated) {
						IosStudioUnauthenticatedView(
							description = "连接 MeloKeep 服务端后即可管理全网下载入库队列，本机离线音乐无需登录。",
							onGoToSettings = onGoToSettings,
							onOpenPhoneOffline = { selectWorkspaceTab(IosStudioWorkspaceTab.PHONE_OFFLINE) },
							safeBottomDp = safeBottomDp,
						)
					} else {
						IosStudioDownloadsContent(
							downloads = state.downloads,
							libraryJobs = state.libraryJobs,
							ignoredLibraryJobIds = recentlyDeletedJobIds,
							loading = state.downloadsLoading,
							serverConfig = serverConfig,
							safeBottomDp = safeBottomDp,
							currentTrackId = currentTrackId,
							isPlayerPlaying = isPlayerPlaying,
							canPreviewPlay = audioPlayer != null && subsonicManager != null,
							onRefresh = { studioController.refreshDownloads(silent = false) },
							onPauseAll = { studioController.pauseAllDownloads() },
							onResumeAll = { studioController.resumeAllDownloads() },
							onRetryFailed = { studioController.retryFailedDownloads() },
							onClearCompleted = { studioController.clearCompletedDownloads() },
							onControl = { id, action -> studioController.controlDownload(id, action) },
							onDelete = { id -> studioController.deleteDownload(id) },
							onPreviewPlay = { job ->
								if (audioPlayer != null && subsonicManager != null && job.track.id.isNotBlank()) {
									val targetId = "mk:${job.track.id}"
									if (currentTrackId == targetId) {
										audioPlayer.togglePlayPause()
									} else {
										scope.launch {
											val resolved = runCatching {
												subsonicManager.meloApiClient.resolveTrackPlayback(job.track.id)
											}.getOrNull() ?: job.track
											val mapped = subsonicManager.meloTrackToIosTrack(resolved)
											audioPlayer.playQueue(listOf(mapped), 0)
										}
									}
								}
							},
							onGoScrape = { filePath, downloadId ->
								val dlJob = state.downloads.firstOrNull { it.id == downloadId }
								val linkedJob = iosStudioFindAssociatedLibraryJob(
									downloadJob = dlJob,
									filePath = filePath,
									libraryJobs = state.libraryJobs,
									ignoredJobIds = recentlyDeletedJobIds,
								)
								val linkedId = linkedJob?.id
									?: dlJob?.processing?.id?.takeIf { it.isNotBlank() && it !in recentlyDeletedJobIds }
								val linkedStatus = (linkedJob?.status
									?: dlJob?.processing?.takeIf { it.id !in recentlyDeletedJobIds }?.status)
									.orEmpty()
									.trim()
									.lowercase()
								if (linkedId != null &&
									linkedStatus in (IosStudioScrapeReviewStates + IosStudioScrapeRunningStates + setOf("complete", "completed"))
								) {
									studioController.clearJobDetail()
									diffSheetVisible = true
									studioController.loadLibraryJobDetail(linkedId)
									workspaceTabName = IosStudioWorkspaceTab.SCRAPE_JOBS.name
									studioController.selectTab(MeloStudioTab.LIBRARY)
								} else {
									studioController.clearJobDetail()
									autoOpenNextJobDetail = true
									studioController.createScrapeJob(
										paths = listOf(filePath),
										action = "both",
										autoApply = false,
										sourceDownloadId = downloadId,
									)
									workspaceTabName = IosStudioWorkspaceTab.SCRAPE_JOBS.name
									studioController.selectTab(MeloStudioTab.LIBRARY)
								}
							},
							onRescrape = { filePath, downloadId ->
								studioController.clearJobDetail()
								autoOpenNextJobDetail = true
								studioController.createScrapeJob(
									paths = listOf(filePath),
									action = "both",
									autoApply = false,
									sourceDownloadId = downloadId,
								)
								workspaceTabName = IosStudioWorkspaceTab.SCRAPE_JOBS.name
								studioController.selectTab(MeloStudioTab.LIBRARY)
							},
						)
					}
				}

				IosStudioWorkspaceTab.SCRAPE_JOBS -> {
					if (!serverConfig.isAuthenticated) {
						IosStudioUnauthenticatedView(
							description = "连接 MeloKeep 服务端后即可执行标签刮削、候选对比与版本回滚，本机离线音乐无需登录。",
							onGoToSettings = onGoToSettings,
							onOpenPhoneOffline = { selectWorkspaceTab(IosStudioWorkspaceTab.PHONE_OFFLINE) },
							safeBottomDp = safeBottomDp,
						)
					} else {
						IosStudioScrapeContent(
							state = state,
							jobFilter = jobFilter,
							multiSelectMode = jobMultiSelectMode,
							runningDeleteHint = runningDeleteHint,
							safeBottomDp = safeBottomDp,
							onDismissRunningHint = { runningDeleteHint = null },
							onFilterChange = { nextFilter ->
								jobFilterName = nextFilter.name
								if (jobMultiSelectMode) {
									studioController.clearJobSelection()
								}
							},
							onOpenFilePicker = {
								if (state.libraryFiles.isEmpty()) {
									studioController.loadLibraryFiles(reset = true)
								}
								filePickerOpen = true
							},
							onRefreshJobs = { studioController.refreshLibraryJobs() },
							onToggleMultiSelect = {
								val next = !jobMultiSelectMode
								jobMultiSelectMode = next
								runningDeleteHint = null
								if (!next) {
									studioController.clearJobSelection()
								}
							},
							onEnterMultiSelectWithJob = { jobId ->
								jobMultiSelectMode = true
								runningDeleteHint = null
								studioController.clearJobSelection()
								studioController.toggleJobSelection(jobId)
							},
							onToggleJobSelection = { jobId -> studioController.toggleJobSelection(jobId) },
							onSelectAllJobs = { ids -> studioController.selectAllJobs(ids) },
							onClearJobSelection = { studioController.clearJobSelection() },
							onClearFinishedJobs = {
								val finishedIds = state.libraryJobs
									.filter { iosStudioIsScrapeFinished(it.status) }
									.map { it.id }
								recentlyDeletedJobIds = recentlyDeletedJobIds + finishedIds
								studioController.clearFinishedLibraryJobs()
								studioController.refreshDownloads(silent = true)
							},
							onDeleteSingleJob = { jobId ->
								recentlyDeletedJobIds = recentlyDeletedJobIds + jobId
								studioController.deleteLibraryJob(jobId)
								studioController.refreshDownloads(silent = true)
							},
							onBatchDeleteSelected = { ids ->
								recentlyDeletedJobIds = recentlyDeletedJobIds + ids
								studioController.batchDeleteLibraryJobs(ids)
								jobMultiSelectMode = false
								studioController.refreshDownloads(silent = true)
							},
							onOpenJobDetail = { jobId ->
								diffSheetVisible = true
								studioController.loadLibraryJobDetail(jobId)
							},
							onApplyJob = { jobId ->
								studioController.applyScrapeJob(jobId, emptyMap(), autoTriggerScan = true)
							},
							onCancelJob = { jobId -> studioController.cancelLibraryJob(jobId) },
							onRetryJob = { jobId -> studioController.retryLibraryJob(jobId) },
							onShowRunningDeleteHint = {
								runningDeleteHint = "任务正在运行中，请先取消后再删除"
							},
						)
					}
				}
			}
		}
	}

	if (filePickerOpen) {
		IosStudioLibraryFilePickerSheet(
			state = state,
			normalizedFolders = normalizedFolders,
			onDismiss = { filePickerOpen = false },
			onQuerySubmit = { query ->
				studioController.loadLibraryFiles(query = query, folder = state.currentFolder, reset = true)
			},
			onFolderSelect = { folder ->
				val normalizedCurrent = iosStudioNormalizeFolder(state.currentFolder)
				val nextFolder = if (folder.isBlank() || normalizedCurrent == folder) null else folder
				studioController.loadLibraryFiles(query = state.fileQuery, folder = nextFolder, reset = true)
			},
			onLoadMore = { studioController.loadMoreLibraryFiles() },
			onToggleFile = { path -> studioController.toggleFileSelection(path) },
			onSelectAllFiles = { studioController.selectAllFiles() },
			onClearSelection = { studioController.clearFileSelection() },
			onPreviewScrape = {
				studioController.clearJobDetail()
				autoOpenNextJobDetail = true
				studioController.createScrapeJob(action = "both", autoApply = false)
				filePickerOpen = false
			},
			onDirectOrganize = {
				studioController.clearJobDetail()
				autoOpenNextJobDetail = true
				studioController.createScrapeJob(action = "both", autoApply = true)
				filePickerOpen = false
			},
			onScopeScrape = {
				studioController.clearJobDetail()
				autoOpenNextJobDetail = true
				studioController.createScrapeJob(
					paths = emptyList(),
					action = "both",
					autoApply = false,
					scopeDir = state.currentFolder.orEmpty(),
				)
				filePickerOpen = false
			},
		)
	}

	if (diffSheetVisible) {
		IosStudioScrapeDiffSheet(
			job = state.activeJobDetail,
			jobsLoading = state.jobsLoading,
			fileTagVersions = state.fileTagVersions,
			errorMessage = state.errorMessage,
			noticeMessage = state.noticeMessage,
			onDismissError = { studioController.clearError() },
			onDismissNotice = { studioController.clearNotice() },
			onDismiss = {
				diffSheetVisible = false
				studioController.clearJobDetail()
			},
			onRefreshJob = { jobId -> studioController.loadLibraryJobDetail(jobId) },
			onPreviewCandidate = { jobId, itemKey, candidateId ->
				studioController.previewCandidateChoice(jobId, itemKey, candidateId)
			},
			onKeepExistingForPending = { jobId ->
				studioController.keepExistingForPendingItems(jobId)
			},
			onSearchItemCandidates = { jobId, itemId, query ->
				studioController.searchJobItemCandidates(
					jobId = jobId,
					itemId = itemId,
					query = query,
				)
			},
			onLoadFileVersions = { path -> studioController.loadFileVersions(path) },
			onRestoreFileVersion = { versionJobId, itemId ->
				studioController.restoreFileVersion(versionJobId, itemId)
			},
			onApplyJob = { jobId, choices, autoScan ->
				studioController.applyScrapeJob(jobId, choices = choices, autoTriggerScan = autoScan)
			},
			onCancelJob = { jobId -> studioController.cancelLibraryJob(jobId) },
			onRetryJob = { jobId -> studioController.retryLibraryJob(jobId) },
		)
	}
}

@Composable
private fun IosStudioUnauthenticatedView(
	description: String,
	onGoToSettings: () -> Unit,
	onOpenPhoneOffline: () -> Unit,
	safeBottomDp: Dp,
) {
	Column(
		modifier = Modifier
			.fillMaxSize()
			.padding(bottom = safeBottomDp + 12.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		IosStudioUnconfiguredCard(
			title = "尚未连接 MeloKeep 服务",
			description = description,
			actionLabel = "前往配置",
			onAction = onGoToSettings,
			modifier = Modifier.weight(1f),
		)
		FilledTonalButton(
			onClick = onOpenPhoneOffline,
			shape = CircleShape,
		) {
			Icon(
				imageVector = Icons.Outlined.PhoneIphone,
				contentDescription = null,
				modifier = Modifier.size(15.dp),
			)
			Spacer(Modifier.width(6.dp))
			Text(
				text = "查看本机离线音乐",
				maxLines = 1,
				softWrap = false,
			)
		}
	}
}
