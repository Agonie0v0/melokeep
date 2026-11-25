package io.github.agonie0v0.melokeep.shared

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class MeloQualityFilter {
	ALL,
	LOSSLESS,
	HIGH,
}

internal fun filterTracksByQuality(
	tracks: List<MeloTrack>,
	qualityFilter: MeloQualityFilter,
): List<MeloTrack> = when (qualityFilter) {
	MeloQualityFilter.ALL -> tracks
	MeloQualityFilter.LOSSLESS -> tracks.filter { it.hasLossless }
	MeloQualityFilter.HIGH -> tracks.filter { track ->
		track.hasLossless || (track.bestBitrate ?: 0) >= 320
	}
}

internal fun groupTracksByTitleAndArtist(tracks: List<MeloTrack>): List<MeloTrackGroup> {
	if (tracks.isEmpty()) return emptyList()
	val buckets = linkedMapOf<String, MutableList<MeloTrack>>()
	for (track in tracks) {
		val key = "${track.title.trim().lowercase()}|${track.artist.trim().lowercase()}"
		buckets.getOrPut(key) { mutableListOf() }.add(track)
	}
	return buckets.entries.map { (groupKey, items) ->
		val sorted = if (items.size == 1) {
			items
		} else {
			items.sortedWith(
				compareByDescending<MeloTrack> { it.hasLossless }
					.thenByDescending { it.bestBitrate ?: Int.MIN_VALUE }
			)
		}
		MeloTrackGroup(
			groupKey = groupKey,
			primary = sorted.first(),
			alternatives = sorted.drop(1),
		)
	}
}

data class MeloSearchUiState(
	val keyword: String = "",
	val searching: Boolean = false,
	val tracks: List<MeloTrack> = emptyList(),
	val sourceStatuses: Map<String, MeloSourceStatus> = emptyMap(),
	val availableSources: List<MeloAvailableSource> = emptyList(),
	val selectedSources: List<String> = emptyList(),
	val qualityFilter: MeloQualityFilter = MeloQualityFilter.ALL,
	val searchHistory: List<String> = emptyList(),
	val multiSelectMode: Boolean = false,
	val selectedTrackIds: Set<String> = emptySet(),
	val resolvingTrackId: String? = null,
	val activeJobId: String? = null,
	val errorMessage: String? = null,
	val noticeMessage: String? = null,
	val groupedTracks: List<MeloTrackGroup> = emptyList(),
	val enqueuedTrackIds: Set<String> = emptySet(),
) {
	val filteredTracks: List<MeloTrack>
		get() = filterTracksByQuality(tracks, qualityFilter)
}

class MeloSearchController(
	private val apiClient: MeloKeepApiClient,
	private val scope: CoroutineScope,
) {
	private val _state = MutableStateFlow(
		MeloSearchUiState(searchHistory = apiClient.settingsStore.searchHistory.value)
	)
	val state: StateFlow<MeloSearchUiState> = _state.asStateFlow()

	private var searchJob: Job? = null

	init {
		scope.launch {
			apiClient.settingsStore.searchHistory.collect { history ->
				_state.update { it.copy(searchHistory = history) }
			}
		}
	}

	fun loadSources() {
		if (!apiClient.settingsStore.currentConfig().isConfigured) return
		scope.launch {
			try {
				val cfg = apiClient.getConfig()
				_state.update { current ->
					current.copy(
						availableSources = cfg.availableSources,
						selectedSources = if (current.selectedSources.isEmpty()) cfg.sources else current.selectedSources,
					)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (_: Throwable) {
				// Ignore silent pre-load failure; search errors will surface explicitly.
			}
		}
	}

	fun updateKeyword(keyword: String) {
		_state.update { it.copy(keyword = keyword) }
	}

	fun setQualityFilter(filter: MeloQualityFilter) {
		_state.update { current ->
			current.copy(
				qualityFilter = filter,
				groupedTracks = groupTracksByTitleAndArtist(filterTracksByQuality(current.tracks, filter)),
			)
		}
	}

	fun toggleSource(sourceId: String) {
		_state.update { current ->
			val next = if (sourceId in current.selectedSources) {
				current.selectedSources - sourceId
			} else {
				current.selectedSources + sourceId
			}
			current.copy(selectedSources = next)
		}
	}

	fun setSelectedSources(sources: List<String>) {
		_state.update { it.copy(selectedSources = sources.distinct()) }
	}

	fun search(
		keyword: String = _state.value.keyword,
		sources: List<String> = _state.value.selectedSources,
		refresh: Boolean = false,
	) {
		val trimmed = keyword.trim()
		if (trimmed.isEmpty()) {
			_state.update { it.copy(errorMessage = "请输入搜索关键词") }
			return
		}
		searchJob?.cancel()
		apiClient.settingsStore.recordSearchHistory(trimmed)

		val initialStatuses = sources.associateWith { MeloSourceStatus(state = "searching") }
		_state.update {
			it.copy(
				keyword = trimmed,
				searching = true,
				tracks = emptyList(),
				groupedTracks = emptyList(),
				sourceStatuses = initialStatuses,
				selectedTrackIds = emptySet(),
				errorMessage = null,
				noticeMessage = null,
			)
		}

		searchJob = scope.launch {
			val seenIds = mutableSetOf<String>()
			val bufferMutex = Mutex()
			val pendingTracks = mutableListOf<MeloTrack>()
			val pendingSources = mutableMapOf<String, MeloSourceStatus>()

			suspend fun flushBuffered(
				setSearchingFalse: Boolean = false,
				settledCancelled: Boolean? = null,
				errorDetail: String? = null,
			) {
				bufferMutex.withLock {
					val tracksBatch = if (pendingTracks.isEmpty()) {
						emptyList()
					} else {
						pendingTracks.toList().also { pendingTracks.clear() }
					}
					val sourcesBatch = if (pendingSources.isEmpty()) {
						emptyMap()
					} else {
						pendingSources.toMap().also { pendingSources.clear() }
					}
					if (tracksBatch.isEmpty() &&
						sourcesBatch.isEmpty() &&
						!setSearchingFalse &&
						settledCancelled == null &&
						errorDetail == null
					) {
						return@withLock
					}
					_state.update { current ->
						val mergedTracks = if (tracksBatch.isEmpty()) {
							current.tracks
						} else {
							current.tracks + tracksBatch
						}
						var mergedSources = if (sourcesBatch.isEmpty()) {
							current.sourceStatuses
						} else {
							current.sourceStatuses + sourcesBatch
						}
						if (settledCancelled != null) {
							mergedSources = mergedSources.mapValues { (_, st) ->
								if (st.state == "searching") {
									st.copy(state = if (settledCancelled) "stopped" else "complete")
								} else {
									st
								}
							}
						}
						val nextGrouped = if (tracksBatch.isEmpty()) {
							current.groupedTracks
						} else {
							groupTracksByTitleAndArtist(
								filterTracksByQuality(mergedTracks, current.qualityFilter)
							)
						}
						current.copy(
							tracks = mergedTracks,
							groupedTracks = nextGrouped,
							sourceStatuses = mergedSources,
							searching = if (setSearchingFalse) false else current.searching,
							errorMessage = errorDetail ?: current.errorMessage,
						)
					}
				}
			}

			val flushJob = launch {
				while (isActive) {
					delay(SEARCH_STREAM_BATCH_WINDOW_MS)
					flushBuffered()
				}
			}

			try {
				var receivedTerminalEvent = false
				apiClient.searchStream(
					keyword = trimmed,
					sources = sources,
					refresh = refresh,
				).collect { event ->
					when (event) {
						is MeloSearchEvent.Candidate -> {
							if (seenIds.add(event.track.id)) {
								bufferMutex.withLock {
									pendingTracks.add(event.track)
								}
							}
						}
						is MeloSearchEvent.Track -> {
							if (seenIds.add(event.track.id)) {
								bufferMutex.withLock {
									pendingTracks.add(event.track)
								}
							}
						}
						is MeloSearchEvent.Source -> {
							bufferMutex.withLock {
								pendingSources[event.sourceId] = event.status
							}
						}
						is MeloSearchEvent.Done -> {
							receivedTerminalEvent = true
							flushJob.cancel()
							flushBuffered(
								setSearchingFalse = true,
								settledCancelled = event.cancelled,
							)
						}
						is MeloSearchEvent.Error -> {
							receivedTerminalEvent = true
							flushJob.cancel()
							flushBuffered(
								setSearchingFalse = true,
								errorDetail = event.detail,
							)
						}
					}
				}
				flushJob.cancel()
				if (!receivedTerminalEvent) {
					flushBuffered(setSearchingFalse = true)
				}
			} catch (e: CancellationException) {
				flushJob.cancel()
				_state.update { it.copy(searching = false) }
				throw e
			} catch (e: Throwable) {
				flushJob.cancel()
				flushBuffered(
					setSearchingFalse = true,
					errorDetail = e.message ?: "搜索失败",
				)
			}
		}
	}

	fun stopSearch() {
		searchJob?.cancel()
		searchJob = null
		_state.update { current ->
			val settled = current.sourceStatuses.mapValues { (_, st) ->
				if (st.state == "searching") st.copy(state = "stopped") else st
			}
			current.copy(searching = false, sourceStatuses = settled)
		}
	}

	fun removeHistory(query: String) {
		apiClient.settingsStore.removeSearchHistory(query)
	}

	fun clearHistory() {
		apiClient.settingsStore.clearSearchHistory()
	}

	fun toggleMultiSelect(enabled: Boolean? = null) {
		_state.update { current ->
			val next = enabled ?: !current.multiSelectMode
			current.copy(
				multiSelectMode = next,
				selectedTrackIds = if (next) current.selectedTrackIds else emptySet(),
			)
		}
	}

	fun toggleTrackSelection(trackId: String) {
		_state.update { current ->
			val next = if (trackId in current.selectedTrackIds) {
				current.selectedTrackIds - trackId
			} else {
				current.selectedTrackIds + trackId
			}
			current.copy(selectedTrackIds = next)
		}
	}

	fun selectAllFiltered() {
		_state.update { current ->
			current.copy(selectedTrackIds = current.filteredTracks.map { it.id }.toSet())
		}
	}

	fun clearSelection() {
		_state.update { it.copy(selectedTrackIds = emptySet()) }
	}

	suspend fun resolvePlayback(trackId: String): Result<MeloTrack> {
		_state.update { it.copy(resolvingTrackId = trackId, errorMessage = null) }
		return try {
			val resolved = apiClient.resolveTrackPlayback(trackId)
			_state.update { current ->
				val nextTracks = current.tracks.map { if (it.id == resolved.id) resolved else it }
				current.copy(
					resolvingTrackId = null,
					tracks = nextTracks,
					groupedTracks = groupTracksByTitleAndArtist(
						filterTracksByQuality(nextTracks, current.qualityFilter)
					),
				)
			}
			Result.success(resolved)
		} catch (e: CancellationException) {
			_state.update { it.copy(resolvingTrackId = null) }
			throw e
		} catch (e: Throwable) {
			_state.update {
				it.copy(
					resolvingTrackId = null,
					errorMessage = e.message ?: "解析播放地址失败",
				)
			}
			Result.failure(e)
		}
	}

	suspend fun fetchTrackVersions(trackId: String): Result<MeloTrack> {
		return try {
			val detailed = apiClient.getTrackVersions(trackId)
			_state.update { current ->
				val nextTracks = current.tracks.map { if (it.id == detailed.id) detailed else it }
				current.copy(
					tracks = nextTracks,
					groupedTracks = groupTracksByTitleAndArtist(
						filterTracksByQuality(nextTracks, current.qualityFilter)
					),
				)
			}
			Result.success(detailed)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			_state.update { it.copy(errorMessage = e.message ?: "获取音质列表失败") }
			Result.failure(e)
		}
	}

	suspend fun enqueueDownload(track: MeloTrack, qualityId: String? = null): Result<List<MeloDownloadJob>> {
		return try {
			val map = if (!qualityId.isNullOrBlank()) mapOf(track.id to qualityId) else emptyMap()
			val created = apiClient.enqueueDownloads(listOf(track), map)
			val addedIds = buildSet {
				if (track.id.isNotBlank()) add(track.id)
				created.forEach { job ->
					if (job.track.id.isNotBlank()) add(job.track.id)
				}
			}
			_state.update {
				it.copy(
					enqueuedTrackIds = it.enqueuedTrackIds + addedIds,
					noticeMessage = "已加入下载队列：${track.title}",
				)
			}
			Result.success(created)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			_state.update { it.copy(errorMessage = e.message ?: "提交下载失败") }
			Result.failure(e)
		}
	}

	suspend fun enqueueSelectedDownloads(): Result<List<MeloDownloadJob>> {
		val current = _state.value
		val picked = current.tracks.filter { it.id in current.selectedTrackIds }
		if (picked.isEmpty()) {
			return Result.success(emptyList())
		}
		return try {
			val created = apiClient.enqueueDownloads(picked)
			val addedIds = buildSet {
				picked.forEach { t -> if (t.id.isNotBlank()) add(t.id) }
				created.forEach { job ->
					if (job.track.id.isNotBlank()) add(job.track.id)
				}
			}
			_state.update {
				it.copy(
					multiSelectMode = false,
					selectedTrackIds = emptySet(),
					enqueuedTrackIds = it.enqueuedTrackIds + addedIds,
					noticeMessage = "已提交 ${created.size} 首歌曲到下载队列",
				)
			}
			Result.success(created)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			_state.update { it.copy(errorMessage = e.message ?: "批量下载失败") }
			Result.failure(e)
		}
	}

	fun clearError() {
		_state.update { it.copy(errorMessage = null) }
	}

	fun clearNotice() {
		_state.update { it.copy(noticeMessage = null) }
	}

	companion object {
		private const val SEARCH_STREAM_BATCH_WINDOW_MS = 80L
	}
}

enum class MeloStudioTab {
	DOWNLOADS,
	LIBRARY,
}

data class MeloStudioUiState(
	val activeTab: MeloStudioTab = MeloStudioTab.DOWNLOADS,
	val downloads: List<MeloDownloadJob> = emptyList(),
	val downloadsLoading: Boolean = false,
	val libraryFiles: List<MeloLibraryFile> = emptyList(),
	val libraryFolders: List<MeloLibraryFolder> = emptyList(),
	val nextCursor: String? = null,
	val currentFolder: String? = null,
	val fileQuery: String = "",
	val filesLoading: Boolean = false,
	val selectedFilePaths: Set<String> = emptySet(),
	val libraryJobs: List<MeloLibraryJob> = emptyList(),
	val activeJobDetail: MeloLibraryJob? = null,
	val jobsLoading: Boolean = false,
	val scanningNavidrome: Boolean = false,
	val scanStatus: MeloNavidromeScanStatus? = null,
	val errorMessage: String? = null,
	val noticeMessage: String? = null,
	val navidromeCanDelete: Boolean = false,
	val navidromeDeletePreview: MeloNavidromeDeletionPreview? = null,
	val navidromeDeletingTrackId: String? = null,
	val fileTagVersions: Map<String, List<MeloTagVersion>> = emptyMap(),
	val selectedJobIds: Set<String> = emptySet(),
) {
	val activeDownloadCount: Int
		get() = downloads.count { it.status in ACTIVE_DOWNLOAD_STATUSES }

	val activeScrapeCount: Int
		get() = libraryJobs.count { it.status in ACTIVE_LIBRARY_JOB_STATUSES }

	val activeTaskCount: Int
		get() = activeDownloadCount + activeScrapeCount

	companion object {
		val ACTIVE_DOWNLOAD_STATUSES = setOf(
			"queued",
			"resolving",
			"retry_wait",
			"downloading",
			"verifying",
			"publishing",
			"pending",
			"processing",
		)
		val RUNNING_LIBRARY_JOB_STATUSES = setOf(
			"queued",
			"scanning",
			"identifying",
			"tagging",
			"organizing",
			"indexing",
			"applying",
			"restoring",
		)
		val ACTIVE_LIBRARY_JOB_STATUSES = RUNNING_LIBRARY_JOB_STATUSES + setOf(
			"preview_ready",
			"ready",
			"needs_review",
		)
	}
}

class MeloStudioController(
	private val apiClient: MeloKeepApiClient,
	private val scope: CoroutineScope,
) {
	private val _state = MutableStateFlow(MeloStudioUiState())
	val state: StateFlow<MeloStudioUiState> = _state.asStateFlow()

	private var pollingJob: Job? = null
	private var hasLoadedLibraryFiles: Boolean = false

	fun selectTab(tab: MeloStudioTab) {
		_state.update { it.copy(activeTab = tab) }
		when (tab) {
			MeloStudioTab.DOWNLOADS -> refreshDownloads()
			MeloStudioTab.LIBRARY -> {
				if (_state.value.libraryFiles.isEmpty()) {
					loadLibraryFiles(reset = true)
				}
				refreshLibraryJobs()
			}
		}
	}

	fun refreshDownloads(silent: Boolean = false) {
		if (!apiClient.settingsStore.currentConfig().isConfigured) return
		scope.launch {
			if (!silent) {
				_state.update { it.copy(downloadsLoading = true, errorMessage = null) }
			}
			try {
				val list = apiClient.getDownloads()
				_state.update { it.copy(downloads = list, downloadsLoading = false) }
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						downloadsLoading = false,
						errorMessage = if (silent) it.errorMessage else (e.message ?: "刷新下载队列失败"),
					)
				}
			}
		}
	}

	fun startDownloadPolling(intervalMs: Long = 2_000L) {
		pollingJob?.cancel()
		pollingJob = scope.launch {
			while (isActive) {
				if (apiClient.settingsStore.currentConfig().isConfigured) {
					try {
						val list = apiClient.getDownloads()
						_state.update { it.copy(downloads = list) }
					} catch (e: CancellationException) {
						throw e
					} catch (_: Throwable) {
					}
					val snapshot = _state.value
					val hasRunningJob = snapshot.libraryJobs.any {
						it.status in MeloStudioUiState.RUNNING_LIBRARY_JOB_STATUSES
					}
					val activeDetail = snapshot.activeJobDetail
					val activeDetailRunning = activeDetail != null &&
						activeDetail.status in MeloStudioUiState.RUNNING_LIBRARY_JOB_STATUSES

					if (hasRunningJob) {
						try {
							val page = apiClient.getLibraryJobs()
							_state.update { current ->
								val currentDetail = current.activeJobDetail
								val mergedJobs = page.jobs.map { summary ->
									if (currentDetail != null &&
										summary.id == currentDetail.id &&
										summary.items.isEmpty() &&
										currentDetail.items.isNotEmpty() &&
										summary.status == currentDetail.status
									) {
										currentDetail
									} else {
										summary
									}
								}
								current.copy(libraryJobs = mergedJobs)
							}
						} catch (e: CancellationException) {
							throw e
						} catch (_: Throwable) {
						}
					}

					if (activeDetailRunning) {
						try {
							val detail = apiClient.getLibraryJobDetail(activeDetail.id)
							_state.update { current ->
								mergeJobIntoState(current, detail)
							}
						} catch (e: CancellationException) {
							throw e
						} catch (_: Throwable) {
						}
					}
				}
				delay(intervalMs.coerceAtLeast(800L))
			}
		}
	}

	fun stopPolling() {
		pollingJob?.cancel()
		pollingJob = null
	}

	fun controlDownload(id: String, action: String) {
		scope.launch {
			try {
				val updated = apiClient.controlDownload(id, action)
				_state.update { current ->
					current.copy(
						downloads = current.downloads.map { if (it.id == updated.id) updated else it },
					)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				_state.update { it.copy(errorMessage = e.message ?: "操作下载任务失败") }
			}
		}
	}

	fun deleteDownload(id: String, deleteFile: Boolean = false) {
		scope.launch {
			try {
				val res = apiClient.deleteDownload(id, deleteFile)
				val notice = when {
					deleteFile && res.fileDeleted -> "已删除下载记录，并删除本地文件"
					deleteFile -> "已删除下载记录（本地文件已不存在）"
					else -> "已删除下载记录"
				}
				_state.update { current ->
					current.copy(
						downloads = current.downloads.filterNot { it.id == id },
						noticeMessage = notice,
					)
				}
				if (res.fileDeleted) {
					refreshLibraryFilesIfLoaded()
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				_state.update { it.copy(errorMessage = e.message ?: "删除下载任务失败") }
			}
		}
	}

	fun pauseAllDownloads() {
		val targets = _state.value.downloads.filter { it.status in MeloStudioUiState.ACTIVE_DOWNLOAD_STATUSES }
		batchControlDownloads(
			targets = targets,
			action = "pause",
			emptyNotice = "没有可暂停的下载任务",
			successMessage = { count -> "已暂停 $count 个下载任务" },
			failureFallback = "批量暂停下载任务失败",
		)
	}

	fun resumeAllDownloads() {
		val targets = _state.value.downloads.filter { it.status == "paused" }
		batchControlDownloads(
			targets = targets,
			action = "resume",
			emptyNotice = "没有可继续的下载任务",
			successMessage = { count -> "已继续 $count 个下载任务" },
			failureFallback = "批量继续下载任务失败",
		)
	}

	fun retryFailedDownloads() {
		val targets = _state.value.downloads.filter { it.status == "failed" || it.status == "error" }
		batchControlDownloads(
			targets = targets,
			action = "retry",
			emptyNotice = "没有失败的下载任务需要重试",
			successMessage = { count -> "已重试 $count 个失败下载任务" },
			failureFallback = "批量重试下载任务失败",
		)
	}

	fun clearCompletedDownloads(deleteFiles: Boolean = false) {
		val targets = _state.value.downloads.filter {
			val status = it.status.trim().lowercase()
			status == "complete" || status == "completed" || status == "cancelled"
		}
		if (targets.isEmpty()) {
			_state.update { it.copy(noticeMessage = "没有可清理的已完成下载记录") }
			return
		}
		scope.launch {
			val removedIds = mutableSetOf<String>()
			var deletedFilesCount = 0
			var firstError: String? = null
			for (job in targets) {
				try {
					val shouldDeleteFile = deleteFiles && !job.file.isNullOrBlank()
					val res = apiClient.deleteDownload(job.id, shouldDeleteFile)
					removedIds += job.id
					if (res.fileDeleted) {
						deletedFilesCount++
					}
				} catch (e: CancellationException) {
					throw e
				} catch (e: Throwable) {
					if (firstError == null) {
						firstError = e.message ?: "清理下载记录失败"
					}
				}
			}
			val notice = when {
				removedIds.isEmpty() -> null
				deleteFiles && deletedFilesCount > 0 ->
					"已清理 ${removedIds.size} 条已结束下载记录，并删除 $deletedFilesCount 个本地文件"
				deleteFiles ->
					"已清理 ${removedIds.size} 条已结束下载记录（本地文件已不存在或未产生文件）"
				else ->
					"已清理 ${removedIds.size} 条已结束下载记录"
			}
			_state.update { current ->
				current.copy(
					downloads = current.downloads.filterNot { it.id in removedIds },
					noticeMessage = notice ?: current.noticeMessage,
					errorMessage = if (removedIds.isEmpty()) {
						firstError ?: "清理下载记录失败"
					} else {
						firstError
					},
				)
			}
			if (deletedFilesCount > 0) {
				refreshLibraryFilesIfLoaded()
			}
		}
	}

	private fun batchControlDownloads(
		targets: List<MeloDownloadJob>,
		action: String,
		emptyNotice: String,
		successMessage: (Int) -> String,
		failureFallback: String,
	) {
		if (targets.isEmpty()) {
			_state.update { it.copy(noticeMessage = emptyNotice) }
			return
		}
		scope.launch {
			val updatedMap = mutableMapOf<String, MeloDownloadJob>()
			var firstError: String? = null
			for (job in targets) {
				try {
					val updated = apiClient.controlDownload(job.id, action)
					updatedMap[updated.id] = updated
				} catch (e: CancellationException) {
					throw e
				} catch (e: Throwable) {
					if (firstError == null) {
						firstError = e.message ?: failureFallback
					}
				}
			}
			_state.update { current ->
				val nextDownloads = if (updatedMap.isEmpty()) {
					current.downloads
				} else {
					current.downloads.map { updatedMap[it.id] ?: it }
				}
				current.copy(
					downloads = nextDownloads,
					noticeMessage = if (updatedMap.isNotEmpty()) successMessage(updatedMap.size) else current.noticeMessage,
					errorMessage = if (updatedMap.isEmpty()) (firstError ?: failureFallback) else firstError,
				)
			}
		}
	}

	fun triggerNavidromeScan() {
		scope.launch {
			_state.update { it.copy(scanningNavidrome = true, errorMessage = null) }
			try {
				val status = apiClient.triggerNavidromeScan()
				_state.update {
					it.copy(
						scanningNavidrome = false,
						scanStatus = status,
						noticeMessage = if (status.scanning) "已触发 Navidrome 扫库" else "Navidrome 扫库已启动",
					)
				}
			} catch (e: CancellationException) {
				_state.update { it.copy(scanningNavidrome = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						scanningNavidrome = false,
						errorMessage = e.message ?: "触发 Navidrome 扫库失败",
					)
				}
			}
		}
	}

	fun loadLibraryFiles(
		query: String? = _state.value.fileQuery,
		folder: String? = _state.value.currentFolder,
		reset: Boolean = true,
	) {
		if (!apiClient.settingsStore.currentConfig().isConfigured) return
		scope.launch {
			fetchLibraryFilesInternal(
				query = query,
				folder = folder,
				reset = reset,
			)
		}
	}

	fun refreshLibraryAndReload(
		query: String? = _state.value.fileQuery,
		folder: String? = _state.value.currentFolder,
		onCompleted: ((Result<MeloLibraryRefreshResult>) -> Unit)? = null,
	) {
		if (!apiClient.settingsStore.currentConfig().isAuthenticated) {
			onCompleted?.invoke(Result.failure(MeloApiException(0, "尚未配置或未登录 MeloKeep 服务")))
			return
		}
		scope.launch {
			_state.update { it.copy(filesLoading = true, errorMessage = null) }
			try {
				val res = apiClient.refreshLibrary()
				val unhealthyNote = res.unhealthyRoots.takeIf { it.isNotEmpty() }
					?.let { "（${it.size} 个目录不可用：${it.joinToString("、")}）" }
					.orEmpty()
				_state.update {
					it.copy(
						noticeMessage = "已刷新曲库：共 ${res.scannedFiles} 首（新增/变更 ${res.updatedOrNew}，移除 ${res.markedMissing}）$unhealthyNote",
					)
				}
				fetchLibraryFilesInternal(
					query = query,
					folder = folder,
					reset = true,
				)
				onCompleted?.invoke(Result.success(res))
			} catch (e: CancellationException) {
				_state.update { it.copy(filesLoading = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						filesLoading = false,
						errorMessage = e.message ?: "刷新曲库失败",
					)
				}
				onCompleted?.invoke(Result.failure(e))
			}
		}
	}

	private suspend fun refreshLibraryFilesIfLoaded() {
		val snapshot = _state.value
		val shouldRefresh = hasLoadedLibraryFiles ||
			snapshot.libraryFiles.isNotEmpty() ||
			snapshot.libraryFolders.isNotEmpty() ||
			snapshot.activeTab == MeloStudioTab.LIBRARY
		if (!shouldRefresh || !apiClient.settingsStore.currentConfig().isConfigured) {
			return
		}
		fetchLibraryFilesInternal(
			query = snapshot.fileQuery,
			folder = snapshot.currentFolder,
			reset = true,
			silent = true,
		)
	}

	private suspend fun fetchLibraryFilesInternal(
		query: String?,
		folder: String?,
		reset: Boolean,
		silent: Boolean = false,
	) {
		val cleanQuery = query.orEmpty()
		val cursor = if (reset) null else _state.value.nextCursor
		_state.update {
			it.copy(
				fileQuery = cleanQuery,
				currentFolder = folder,
				filesLoading = if (silent) it.filesLoading else true,
				errorMessage = if (silent) it.errorMessage else null,
			)
		}
		try {
			val page = apiClient.getLibraryFiles(
				cursor = cursor,
				limit = 100,
				query = cleanQuery.takeIf { it.isNotBlank() },
				folder = folder?.takeIf { it.isNotBlank() },
			)
			hasLoadedLibraryFiles = true
			_state.update { current ->
				val merged = if (reset) page.items else current.libraryFiles + page.items
				current.copy(
					libraryFiles = merged,
					libraryFolders = if (page.folders.isNotEmpty() || reset) page.folders else current.libraryFolders,
					nextCursor = page.nextCursor,
					filesLoading = false,
				)
			}
		} catch (e: CancellationException) {
			_state.update { it.copy(filesLoading = false) }
			throw e
		} catch (e: Throwable) {
			_state.update {
				it.copy(
					filesLoading = false,
					errorMessage = if (silent) it.errorMessage else (e.message ?: "加载曲库文件失败"),
				)
			}
		}
	}

	fun loadMoreLibraryFiles() {
		if (_state.value.nextCursor.isNullOrBlank() || _state.value.filesLoading) return
		loadLibraryFiles(reset = false)
	}

	fun toggleFileSelection(path: String) {
		_state.update { current ->
			val next = if (path in current.selectedFilePaths) {
				current.selectedFilePaths - path
			} else {
				current.selectedFilePaths + path
			}
			current.copy(selectedFilePaths = next)
		}
	}

	fun selectAllFiles() {
		_state.update { current ->
			current.copy(selectedFilePaths = current.libraryFiles.map { it.path }.toSet())
		}
	}

	fun clearFileSelection() {
		_state.update { it.copy(selectedFilePaths = emptySet()) }
	}

	fun createSubFolder(
		root: String = "",
		parent: String = _state.value.currentFolder.orEmpty(),
		name: String,
	) {
		val cleanName = name.trim()
		if (cleanName.isEmpty()) {
			_state.update { it.copy(errorMessage = "请输入文件夹名称") }
			return
		}
		val currentState = _state.value
		val resolvedRoot = root.trim().ifBlank {
			val cleanParent = parent.trim().trim('/')
			currentState.libraryFolders.firstOrNull {
				cleanParent.isNotEmpty() && (it.folder == cleanParent || it.folder.startsWith("$cleanParent/"))
			}?.root
				?: currentState.libraryFiles.firstOrNull { it.root.isNotBlank() }?.root
				?: currentState.libraryFolders.firstOrNull { it.root.isNotBlank() }?.root
				?: ""
		}
		scope.launch {
			_state.update { it.copy(errorMessage = null) }
			try {
				val created = apiClient.createFolder(
					root = resolvedRoot,
					parent = parent.trim().trim('/'),
					name = cleanName,
				)
				_state.update { current ->
					val newFolder = MeloLibraryFolder(root = created.root, folder = created.folder)
					val mergedFolders = (current.libraryFolders + newFolder)
						.distinctBy { "${it.root}\u0000${it.folder}" }
					current.copy(
						libraryFolders = mergedFolders,
						noticeMessage = "已新建文件夹：${created.folder}",
					)
				}
				loadLibraryFiles(
					query = _state.value.fileQuery,
					folder = _state.value.currentFolder,
					reset = true,
				)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				_state.update { it.copy(errorMessage = e.message ?: "新建文件夹失败") }
			}
		}
	}

	fun renameFolder(root: String, folder: String, newName: String) {
		val cleanName = newName.trim()
		if (cleanName.isEmpty()) {
			_state.update { it.copy(errorMessage = "请输入新的文件夹名称") }
			return
		}
		scope.launch {
			_state.update { it.copy(errorMessage = null) }
			try {
				val renamed = apiClient.renameFolder(
					root = root,
					folder = folder,
					newName = cleanName,
				)
				val currentFolder = _state.value.currentFolder
				val nextCurrentFolder = when {
					currentFolder == null -> null
					currentFolder == folder -> renamed.folder
					currentFolder.startsWith("$folder/") -> renamed.folder + currentFolder.removePrefix(folder)
					else -> currentFolder
				}
				_state.update {
					it.copy(
						currentFolder = nextCurrentFolder,
						noticeMessage = "已重命名文件夹为：${renamed.folder}",
					)
				}
				loadLibraryFiles(
					query = _state.value.fileQuery,
					folder = nextCurrentFolder,
					reset = true,
				)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				_state.update { it.copy(errorMessage = e.message ?: "重命名文件夹失败") }
			}
		}
	}

	fun moveSelectedFiles(targetRoot: String, targetFolder: String) {
		val selected = _state.value.selectedFilePaths.toList()
		if (selected.isEmpty()) {
			_state.update { it.copy(errorMessage = "请先选择需要移动的文件") }
			return
		}
		scope.launch {
			_state.update { it.copy(filesLoading = true, errorMessage = null) }
			try {
				val result = apiClient.moveEntries(
					files = selected,
					folders = emptyList(),
					targetRoot = targetRoot,
					targetFolder = targetFolder,
				)
				val partialWarning = result.failed.takeIf { it.isNotEmpty() }
					?.let { "部分移动失败：${it.joinToString("；")}" }
				_state.update {
					it.copy(
						filesLoading = false,
						selectedFilePaths = emptySet(),
						noticeMessage = partialWarning ?: "已移动 ${result.moved} 个文件",
					)
				}
				loadLibraryFiles(
					query = _state.value.fileQuery,
					folder = _state.value.currentFolder,
					reset = true,
				)
			} catch (e: CancellationException) {
				_state.update { it.copy(filesLoading = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						filesLoading = false,
						errorMessage = e.message ?: "移动文件失败",
					)
				}
			}
		}
	}

	fun deleteSelectedFiles() {
		val selected = _state.value.selectedFilePaths.toList()
		if (selected.isEmpty()) {
			_state.update { it.copy(errorMessage = "请先选择需要删除的文件") }
			return
		}
		scope.launch {
			_state.update { it.copy(filesLoading = true, errorMessage = null) }
			try {
				val result = apiClient.batchDeleteEntries(
					files = selected,
					folders = emptyList(),
				)
				val partialWarning = result.failed.takeIf { it.isNotEmpty() }
					?.let { "部分删除失败：${it.joinToString("；")}" }
				_state.update { current ->
					current.copy(
						filesLoading = false,
						selectedFilePaths = emptySet(),
						libraryFiles = current.libraryFiles.filterNot { it.path in selected },
						noticeMessage = partialWarning ?: "已删除选中的 ${selected.size} 个文件",
					)
				}
				loadLibraryFiles(
					query = _state.value.fileQuery,
					folder = _state.value.currentFolder,
					reset = true,
				)
			} catch (e: CancellationException) {
				_state.update { it.copy(filesLoading = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						filesLoading = false,
						errorMessage = e.message ?: "批量删除文件失败",
					)
				}
			}
		}
	}

	fun updateFileTags(path: String, tags: Map<String, String>) {
		if (path.isBlank()) return
		scope.launch {
			_state.update { it.copy(errorMessage = null) }
			try {
				val updated = apiClient.updateFileTags(path, tags)
				_state.update { current ->
					current.copy(
						libraryFiles = current.libraryFiles.map { file ->
							if (file.path == path || file.path == updated.path) updated else file
						},
						noticeMessage = "已保存标签并锁定修改字段",
					)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				_state.update { it.copy(errorMessage = e.message ?: "更新文件标签失败") }
			}
		}
	}

	fun unlockFileTags(path: String) {
		if (path.isBlank()) return
		scope.launch {
			_state.update { it.copy(errorMessage = null) }
			try {
				apiClient.unlockFileTags(path)
				_state.update { current ->
					current.copy(
						libraryFiles = current.libraryFiles.map { file ->
							if (file.path == path) file.copy(lockedFields = emptyList()) else file
						},
						noticeMessage = "已解除全部标签锁定",
					)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				_state.update { it.copy(errorMessage = e.message ?: "解除标签锁定失败") }
			}
		}
	}

	fun refreshLibraryJobs() {
		if (!apiClient.settingsStore.currentConfig().isConfigured) return
		scope.launch {
			_state.update { it.copy(jobsLoading = true) }
			try {
				val page = apiClient.getLibraryJobs()
				_state.update { it.copy(libraryJobs = page.jobs, jobsLoading = false) }
			} catch (e: CancellationException) {
				_state.update { it.copy(jobsLoading = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						jobsLoading = false,
						errorMessage = e.message ?: "获取刮削任务失败",
					)
				}
			}
		}
	}

	fun loadLibraryJobDetail(id: String) {
		scope.launch {
			try {
				val detail = apiClient.getLibraryJobDetail(id)
				_state.update { current ->
					current.copy(
						activeJobDetail = detail,
						libraryJobs = current.libraryJobs.map { if (it.id == detail.id) detail else it },
					)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				_state.update { it.copy(errorMessage = e.message ?: "加载任务详情失败") }
			}
		}
	}

	fun clearJobDetail() {
		_state.update { it.copy(activeJobDetail = null) }
	}

	fun createScrapeJob(
		paths: List<String> = _state.value.selectedFilePaths.toList(),
		action: String = "both",
		autoApply: Boolean = false,
		sourceDownloadId: String? = null,
		scopeDir: String? = null,
	) {
		if (scopeDir == null && paths.isEmpty()) {
			_state.update { it.copy(errorMessage = "请先选择需要刮削或整理的文件") }
			return
		}
		val effectivePaths = if (scopeDir != null) emptyList() else paths
		scope.launch {
			_state.update { it.copy(jobsLoading = true, errorMessage = null) }
			try {
				val job = apiClient.createScrapeJob(
					paths = effectivePaths,
					action = action,
					scopeDir = scopeDir,
					autoApply = autoApply,
					sourceDownloadId = sourceDownloadId,
				)
				val refreshedJobs = runCatching { apiClient.getLibraryJobs().jobs }
					.getOrNull()
					?.takeIf { jobs -> jobs.any { it.id == job.id } }
					?.map { if (it.id == job.id) job else it }
				_state.update { current ->
					current.copy(
						jobsLoading = false,
						selectedFilePaths = emptySet(),
						activeJobDetail = job,
						libraryJobs = refreshedJobs ?: (listOf(job) + current.libraryJobs.filterNot { it.id == job.id }),
						noticeMessage = if (autoApply) "已发起整理归档任务" else "已发起刮削预览任务",
					)
				}
			} catch (e: CancellationException) {
				_state.update { it.copy(jobsLoading = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						jobsLoading = false,
						errorMessage = e.message ?: "创建刮削任务失败",
					)
				}
			}
		}
	}

	fun previewCandidateChoice(jobId: String, itemKey: String, candidateId: String) {
		scope.launch {
			_state.update { it.copy(errorMessage = null) }
			try {
				val updated = apiClient.previewScrapeJob(jobId, mapOf(itemKey to candidateId))
				_state.update { current ->
					mergeJobIntoState(current, updated)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				_state.update { it.copy(errorMessage = e.message ?: "切换候选版本预览失败") }
			}
		}
	}

	fun searchJobItemCandidates(
		jobId: String,
		itemId: String,
		query: String,
		sources: List<String>? = null,
	) {
		if (jobId.isBlank() || itemId.isBlank()) return
		val cleanQuery = query.trim()
		if (cleanQuery.isEmpty()) {
			_state.update { it.copy(errorMessage = "请输入搜索关键词") }
			return
		}
		scope.launch {
			_state.update { it.copy(jobsLoading = true, errorMessage = null) }
			try {
				val updated = apiClient.searchScrapeJobCandidates(
					jobId = jobId,
					itemId = itemId,
					query = cleanQuery,
					sources = sources,
				)
				_state.update { current ->
					mergeJobIntoState(current, updated).copy(
						jobsLoading = false,
						noticeMessage = "已重新搜索候选",
					)
				}
			} catch (e: CancellationException) {
				_state.update { it.copy(jobsLoading = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						jobsLoading = false,
						errorMessage = e.message ?: "重新搜索候选失败",
					)
				}
			}
		}
	}

	fun keepExistingForPendingItems(jobId: String) {
		if (jobId.isBlank()) return
		scope.launch {
			_state.update { it.copy(jobsLoading = true, errorMessage = null) }
			try {
				val snapshot = _state.value
				val localJob = snapshot.activeJobDetail?.takeIf { it.id == jobId && it.items.isNotEmpty() }
					?: snapshot.libraryJobs.firstOrNull { it.id == jobId && it.items.isNotEmpty() }
				val job = localJob ?: apiClient.getLibraryJobDetail(jobId)
				val pendingIds = job.items
					.filter { it.status == "needs_review" && it.id.isNotBlank() }
					.map { it.id }
				if (pendingIds.isEmpty()) {
					_state.update { current ->
						mergeJobIntoState(current, job).copy(
							jobsLoading = false,
							noticeMessage = "当前没有待确认候选的曲目",
						)
					}
					return@launch
				}
				val choices = pendingIds.associateWith { "existing" }
				val updated = apiClient.previewScrapeJob(jobId, choices)
				_state.update { current ->
					mergeJobIntoState(current, updated).copy(
						jobsLoading = false,
						noticeMessage = "已为 ${pendingIds.size} 首待确认曲目保留现有标签",
					)
				}
			} catch (e: CancellationException) {
				_state.update { it.copy(jobsLoading = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						jobsLoading = false,
						errorMessage = e.message ?: "批量保留现有标签失败",
					)
				}
			}
		}
	}

	fun applyScrapeJob(
		id: String,
		choices: Map<String, String> = emptyMap(),
		autoTriggerScan: Boolean = true,
	) {
		scope.launch {
			_state.update { it.copy(jobsLoading = true, errorMessage = null) }
			try {
				val updated = apiClient.applyScrapeJob(id, choices)
				val notice = when (updated.status) {
					"applying" -> "已提交后台写入，正在归档处理..."
					"complete" -> "归档完成"
					"partial" -> "部分条目已完成归档"
					"needs_review" -> "仍有曲目待确认候选，请先选择后再归档"
					else -> "已确认应用归档"
				}
				_state.update { current ->
					current.copy(
						jobsLoading = false,
						activeJobDetail = updated,
						libraryJobs = current.libraryJobs.map { if (it.id == updated.id) updated else it },
						noticeMessage = notice,
					)
				}
				if (autoTriggerScan && updated.status in setOf("complete", "partial")) {
					try {
						val scan = apiClient.triggerNavidromeScan()
						_state.update { it.copy(scanStatus = scan) }
					} catch (e: CancellationException) {
						throw e
					} catch (_: Throwable) {
						// 静默触发 Navidrome 扫库失败时不阻塞归档成功状态
					}
				}
			} catch (e: CancellationException) {
				_state.update { it.copy(jobsLoading = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						jobsLoading = false,
						errorMessage = e.message ?: "应用归档失败",
					)
				}
			}
		}
	}

	fun loadFileVersions(path: String) {
		if (path.isBlank()) return
		scope.launch {
			_state.update { it.copy(errorMessage = null) }
			try {
				val versions = apiClient.getFileTagVersions(path)
				_state.update { current ->
					current.copy(
						fileTagVersions = current.fileTagVersions + (path to versions),
					)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				_state.update { it.copy(errorMessage = e.message ?: "加载标签历史版本失败") }
			}
		}
	}

	fun restoreFileVersion(jobId: String, itemId: String) {
		scope.launch {
			_state.update { it.copy(jobsLoading = true, errorMessage = null) }
			try {
				apiClient.restoreJobItems(jobId, listOf(itemId))
				_state.update { current ->
					current.copy(
						jobsLoading = false,
						fileTagVersions = emptyMap(),
						noticeMessage = "已恢复文件至该历史版本",
					)
				}
				try {
					val detail = apiClient.getLibraryJobDetail(jobId)
					_state.update { current ->
						current.copy(
							activeJobDetail = if (current.activeJobDetail?.id == detail.id) detail else current.activeJobDetail,
							libraryJobs = current.libraryJobs.map { if (it.id == detail.id) detail else it },
						)
					}
				} catch (e: CancellationException) {
					throw e
				} catch (_: Throwable) {
					// 忽略附属刷新失败
				}
				refreshLibraryJobs()
			} catch (e: CancellationException) {
				_state.update { it.copy(jobsLoading = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						jobsLoading = false,
						errorMessage = e.message ?: "回滚历史版本失败",
					)
				}
			}
		}
	}

	fun checkNavidromeCapabilities() {
		if (!apiClient.settingsStore.currentConfig().isConfigured) return
		scope.launch {
			try {
				val caps = apiClient.getNavidromeCapabilities()
				_state.update { it.copy(navidromeCanDelete = caps.deleteSongs) }
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						navidromeCanDelete = false,
						errorMessage = e.message ?: "检测 Navidrome 删除权限失败",
					)
				}
			}
		}
	}

	fun requestNavidromeSongDeletion(sourceSongId: String) {
		if (sourceSongId.isBlank()) return
		scope.launch {
			_state.update {
				it.copy(
					navidromeDeletingTrackId = sourceSongId,
					navidromeDeletePreview = null,
					errorMessage = null,
				)
			}
			try {
				val preview = apiClient.previewNavidromeSongDeletion(sourceSongId)
				_state.update {
					it.copy(
						navidromeDeletePreview = preview,
						navidromeDeletingTrackId = sourceSongId,
					)
				}
			} catch (e: CancellationException) {
				_state.update { it.copy(navidromeDeletingTrackId = null) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						navidromeDeletePreview = null,
						navidromeDeletingTrackId = null,
						errorMessage = e.message ?: "获取歌曲物理路径预览失败",
					)
				}
			}
		}
	}

	fun dismissNavidromeSongDeletion() {
		_state.update {
			it.copy(
				navidromeDeletePreview = null,
				navidromeDeletingTrackId = null,
			)
		}
	}

	fun confirmNavidromeSongDeletion(
		sourceSongId: String,
		revision: String,
		onDeleted: () -> Unit = {},
	) {
		scope.launch {
			_state.update {
				it.copy(
					navidromeDeletingTrackId = sourceSongId,
					errorMessage = null,
				)
			}
			try {
				val result = apiClient.deleteNavidromeSong(sourceSongId, revision)
				_state.update { current ->
					current.copy(
						navidromeDeletePreview = null,
						navidromeDeletingTrackId = null,
						scanStatus = result.scan ?: current.scanStatus,
						noticeMessage = result.warning?.takeIf { it.isNotBlank() }
							?: "音乐文件已删除，Navidrome 将在扫描后更新曲库",
					)
				}
				onDeleted()
			} catch (e: CancellationException) {
				_state.update { it.copy(navidromeDeletingTrackId = null) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						navidromeDeletePreview = null,
						navidromeDeletingTrackId = null,
						errorMessage = e.message ?: "删除 Navidrome 歌曲失败",
					)
				}
			}
		}
	}

	fun cancelLibraryJob(id: String) {
		scope.launch {
			try {
				val updated = apiClient.cancelLibraryJob(id)
				_state.update { current ->
					current.copy(
						activeJobDetail = if (current.activeJobDetail?.id == updated.id) updated else current.activeJobDetail,
						libraryJobs = current.libraryJobs.map { if (it.id == updated.id) updated else it },
					)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				_state.update { it.copy(errorMessage = e.message ?: "取消任务失败") }
			}
		}
	}

	fun retryLibraryJob(id: String) {
		scope.launch {
			try {
				val updated = apiClient.retryLibraryJob(id)
				_state.update { current ->
					current.copy(
						activeJobDetail = if (current.activeJobDetail?.id == updated.id) updated else current.activeJobDetail,
						libraryJobs = current.libraryJobs.map { if (it.id == updated.id) updated else it },
					)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				_state.update { it.copy(errorMessage = e.message ?: "重试任务失败") }
			}
		}
	}

	fun deleteLibraryJob(id: String) {
		if (id.isBlank()) return
		scope.launch {
			_state.update { it.copy(errorMessage = null) }
			try {
				apiClient.deleteLibraryJob(id)
				_state.update { current ->
					current.copy(
						libraryJobs = current.libraryJobs.filterNot { it.id == id },
						selectedJobIds = current.selectedJobIds - id,
						activeJobDetail = if (current.activeJobDetail?.id == id) null else current.activeJobDetail,
						noticeMessage = "已删除刮削整理记录",
					)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				_state.update { it.copy(errorMessage = e.message ?: "删除刮削记录失败") }
			}
		}
	}

	fun batchDeleteLibraryJobs(ids: List<String> = _state.value.selectedJobIds.toList()) {
		val uniqueIds = ids.filter { it.isNotBlank() }.distinct()
		if (uniqueIds.isEmpty()) {
			_state.update { it.copy(errorMessage = "请先选择需要删除的刮削记录") }
			return
		}
		scope.launch {
			_state.update { it.copy(jobsLoading = true, errorMessage = null) }
			try {
				val result = apiClient.batchDeleteLibraryJobs(ids = uniqueIds, mode = null)
				val deletedSet = result.deletedIds.toSet()
				val skippedCount = result.skippedRunning.size
				val notice = when {
					deletedSet.isNotEmpty() && skippedCount > 0 ->
						"已删除 ${deletedSet.size} 条记录，跳过 $skippedCount 个运行中任务"
					deletedSet.isNotEmpty() ->
						"已删除 ${deletedSet.size} 条刮削记录"
					skippedCount > 0 ->
						"所选 $skippedCount 个任务正在运行中，请先取消后再删除"
					else -> "没有可删除的刮削记录"
				}
				_state.update { current ->
					current.copy(
						jobsLoading = false,
						libraryJobs = current.libraryJobs.filterNot { it.id in deletedSet },
						selectedJobIds = current.selectedJobIds - deletedSet,
						activeJobDetail = if (current.activeJobDetail?.id in deletedSet) null else current.activeJobDetail,
						noticeMessage = notice,
					)
				}
			} catch (e: CancellationException) {
				_state.update { it.copy(jobsLoading = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						jobsLoading = false,
						errorMessage = e.message ?: "批量删除刮削记录失败",
					)
				}
			}
		}
	}

	fun clearFinishedLibraryJobs() {
		scope.launch {
			_state.update { it.copy(jobsLoading = true, errorMessage = null) }
			try {
				val result = apiClient.batchDeleteLibraryJobs(mode = "finished")
				val deletedSet = result.deletedIds.toSet()
				val removedCount = if (deletedSet.isNotEmpty()) deletedSet.size else result.deleted
				_state.update { current ->
					current.copy(
						jobsLoading = false,
						libraryJobs = current.libraryJobs.filterNot { it.id in deletedSet },
						selectedJobIds = current.selectedJobIds - deletedSet,
						activeJobDetail = if (current.activeJobDetail?.id in deletedSet) null else current.activeJobDetail,
						noticeMessage = if (removedCount > 0) {
							"已清理 $removedCount 条已结束刮削记录"
						} else {
							"没有可清理的已结束刮削记录"
						},
					)
				}
			} catch (e: CancellationException) {
				_state.update { it.copy(jobsLoading = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						jobsLoading = false,
						errorMessage = e.message ?: "清理已结束刮削记录失败",
					)
				}
			}
		}
	}

	fun toggleJobSelection(id: String) {
		if (id.isBlank()) return
		_state.update { current ->
			val next = if (id in current.selectedJobIds) {
				current.selectedJobIds - id
			} else {
				current.selectedJobIds + id
			}
			current.copy(selectedJobIds = next)
		}
	}

	fun selectAllJobs(ids: Collection<String> = _state.value.libraryJobs.map { it.id }) {
		_state.update { current ->
			current.copy(selectedJobIds = ids.filter { it.isNotBlank() }.toSet())
		}
	}

	fun clearJobSelection() {
		_state.update { it.copy(selectedJobIds = emptySet()) }
	}

	fun clearError() {
		_state.update { it.copy(errorMessage = null) }
	}

	fun clearNotice() {
		_state.update { it.copy(noticeMessage = null) }
	}

	private fun mergeJobIntoState(current: MeloStudioUiState, updated: MeloLibraryJob): MeloStudioUiState =
		current.copy(
			activeJobDetail = if (current.activeJobDetail?.id == updated.id) updated else current.activeJobDetail,
			libraryJobs = current.libraryJobs.map { if (it.id == updated.id) updated else it },
		)
}

data class MeloAiUiState(
	val sessions: List<MeloAgentSessionSummary> = emptyList(),
	val currentSession: MeloAgentSession? = null,
	val loadingSessions: Boolean = false,
	val sending: Boolean = false,
	val polling: Boolean = false,
	val errorMessage: String? = null,
	val noticeMessage: String? = null,
) {
	val isRunning: Boolean
		get() = currentSession?.status in ACTIVE_AGENT_STATUSES

	companion object {
		val ACTIVE_AGENT_STATUSES = setOf("running", "waiting_tasks", "stopping")
	}
}

class MeloAiController(
	private val apiClient: MeloKeepApiClient,
	private val scope: CoroutineScope,
) {
	private val _state = MutableStateFlow(MeloAiUiState())
	val state: StateFlow<MeloAiUiState> = _state.asStateFlow()

	private var pollingJob: Job? = null

	fun loadSessions(selectFirstIfEmpty: Boolean = true) {
		if (!apiClient.settingsStore.currentConfig().isConfigured) return
		scope.launch {
			_state.update { it.copy(loadingSessions = true, errorMessage = null) }
			try {
				val list = apiClient.getAgentSessions()
				_state.update { it.copy(sessions = list, loadingSessions = false) }
				val currentId = _state.value.currentSession?.id
				val targetId = when {
					currentId != null && list.any { it.id == currentId } -> currentId
					selectFirstIfEmpty && list.isNotEmpty() -> list.first().id
					else -> null
				}
				if (targetId != null) {
					selectSession(targetId)
				}
			} catch (e: CancellationException) {
				_state.update { it.copy(loadingSessions = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						loadingSessions = false,
						errorMessage = e.message ?: "加载 AI 会话列表失败",
					)
				}
			}
		}
	}

	fun createSession() {
		scope.launch {
			_state.update { it.copy(loadingSessions = true, errorMessage = null) }
			try {
				val created = apiClient.createAgentSession()
				val summary = MeloAgentSessionSummary(
					id = created.id,
					title = created.title,
					status = created.status,
				)
				_state.update { current ->
					current.copy(
						loadingSessions = false,
						currentSession = created,
						sessions = listOf(summary) + current.sessions.filterNot { it.id == created.id },
					)
				}
				syncPollingForSession(created)
			} catch (e: CancellationException) {
				_state.update { it.copy(loadingSessions = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						loadingSessions = false,
						errorMessage = e.message ?: "新建 AI 会话失败",
					)
				}
			}
		}
	}

	fun selectSession(sessionId: String) {
		scope.launch {
			_state.update { it.copy(errorMessage = null) }
			try {
				val session = apiClient.getAgentSession(sessionId)
				applyUpdatedSession(session)
				syncPollingForSession(session)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				_state.update { it.copy(errorMessage = e.message ?: "加载会话详情失败") }
			}
		}
	}

	fun deleteSession(sessionId: String) {
		scope.launch {
			try {
				apiClient.deleteAgentSession(sessionId)
				val wasCurrent = _state.value.currentSession?.id == sessionId
				if (wasCurrent) stopPolling()
				_state.update { current ->
					val remaining = current.sessions.filterNot { it.id == sessionId }
					current.copy(
						sessions = remaining,
						currentSession = if (wasCurrent) null else current.currentSession,
						noticeMessage = "已删除会话",
					)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				_state.update { it.copy(errorMessage = e.message ?: "删除会话失败") }
			}
		}
	}

	fun sendMessage(
		message: String,
		imageDataUrl: String? = null,
		allowActions: Boolean = true,
		trackIds: List<String> = emptyList(),
	) {
		val trimmed = message.trim()
		if (trimmed.isEmpty() && imageDataUrl.isNullOrBlank()) {
			_state.update { it.copy(errorMessage = "请输入指令或选择图片") }
			return
		}
		scope.launch {
			_state.update { it.copy(sending = true, errorMessage = null) }
			try {
				val targetSessionId = _state.value.currentSession?.id ?: apiClient.createAgentSession().let { created ->
					applyUpdatedSession(created)
					created.id
				}
				val updated = apiClient.sendAgentMessage(
					sessionId = targetSessionId,
					message = trimmed,
					imageDataUrl = imageDataUrl,
					allowActions = allowActions,
					trackIds = trackIds,
				)
				applyUpdatedSession(updated)
				_state.update { it.copy(sending = false) }
				syncPollingForSession(updated)
			} catch (e: CancellationException) {
				_state.update { it.copy(sending = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						sending = false,
						errorMessage = e.message ?: "发送消息失败",
					)
				}
			}
		}
	}

	fun stopCurrentSession() {
		val id = _state.value.currentSession?.id ?: return
		scope.launch {
			try {
				val updated = apiClient.stopAgentSession(id)
				applyUpdatedSession(updated)
				syncPollingForSession(updated)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				_state.update { it.copy(errorMessage = e.message ?: "停止会话失败") }
			}
		}
	}

	fun resumeCurrentSession() {
		val id = _state.value.currentSession?.id ?: return
		scope.launch {
			try {
				val updated = apiClient.resumeAgentSession(id)
				applyUpdatedSession(updated)
				syncPollingForSession(updated)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				_state.update { it.copy(errorMessage = e.message ?: "恢复会话失败") }
			}
		}
	}

	fun stopPolling() {
		pollingJob?.cancel()
		pollingJob = null
		_state.update { it.copy(polling = false) }
	}

	private fun syncPollingForSession(session: MeloAgentSession) {
		if (session.status !in MeloAiUiState.ACTIVE_AGENT_STATUSES) {
			stopPolling()
			return
		}
		if (pollingJob?.isActive == true) return
		pollingJob = scope.launch {
			_state.update { it.copy(polling = true) }
			try {
				while (isActive) {
					delay(1_200L)
					val activeId = _state.value.currentSession?.id ?: break
					val latest = apiClient.getAgentSession(activeId)
					applyUpdatedSession(latest)
					if (latest.status !in MeloAiUiState.ACTIVE_AGENT_STATUSES) {
						break
					}
				}
			} catch (e: CancellationException) {
				throw e
			} catch (_: Throwable) {
				// Stop background polling silently on transient disconnect.
			} finally {
				_state.update { it.copy(polling = false) }
			}
		}
	}

	private fun applyUpdatedSession(session: MeloAgentSession) {
		_state.update { current ->
			val summary = MeloAgentSessionSummary(
				id = session.id,
				title = session.title,
				status = session.status,
			)
			val existingIndex = current.sessions.indexOfFirst { it.id == session.id }
			val updatedList = if (existingIndex >= 0) {
				current.sessions.map { if (it.id == session.id) summary else it }
			} else {
				listOf(summary) + current.sessions
			}
			current.copy(
				currentSession = session,
				sessions = updatedList,
			)
		}
	}

	fun clearError() {
		_state.update { it.copy(errorMessage = null) }
	}

	fun clearNotice() {
		_state.update { it.copy(noticeMessage = null) }
	}
}

data class MeloSettingsUiState(
	val serverConfig: MeloServerConfig = MeloServerConfig(),
	val profiles: List<MeloServerProfile> = emptyList(),
	val activeProfileId: String? = null,
	val engineConfig: MeloEngineConfig? = null,
	val devices: List<MeloDeviceToken> = emptyList(),
	val testingConnection: Boolean = false,
	val loadingConfig: Boolean = false,
	val savingConfig: Boolean = false,
	val connectionMessage: String? = null,
	val errorMessage: String? = null,
	val noticeMessage: String? = null,
)

class MeloSettingsController(
	private val apiClient: MeloKeepApiClient,
	private val scope: CoroutineScope,
) {
	private val _state = MutableStateFlow(
		MeloSettingsUiState(
			serverConfig = apiClient.settingsStore.currentConfig(),
			profiles = apiClient.settingsStore.profiles.value,
			activeProfileId = apiClient.settingsStore.activeProfileId.value,
		)
	)
	val state: StateFlow<MeloSettingsUiState> = _state.asStateFlow()

	init {
		scope.launch {
			apiClient.settingsStore.config.collect { cfg ->
				_state.update { it.copy(serverConfig = cfg) }
			}
		}
		scope.launch {
			apiClient.settingsStore.profiles.collect { list ->
				_state.update { it.copy(profiles = list) }
			}
		}
		scope.launch {
			apiClient.settingsStore.activeProfileId.collect { id ->
				_state.update { it.copy(activeProfileId = id) }
			}
		}
	}

	fun updateDisplayName(name: String) {
		val updated = apiClient.settingsStore.setDisplayName(name)
		_state.update {
			it.copy(
				serverConfig = updated,
				profiles = apiClient.settingsStore.profiles.value,
				activeProfileId = apiClient.settingsStore.activeProfileId.value,
				noticeMessage = "已更新服务名称为：${updated.effectiveName}",
			)
		}
	}

	fun testAndLogin(
		baseUrl: String,
		username: String = "",
		password: String = "",
		deviceName: String = "MeloKeep Mobile",
		displayName: String? = null,
		profileId: String? = null,
		createNew: Boolean = false,
	) {
		scope.launch {
			_state.update {
				it.copy(
					testingConnection = true,
					errorMessage = null,
					connectionMessage = null,
				)
			}
			try {
				val probe = apiClient.probeServer(baseUrl)
				val loginResp = apiClient.login(
					baseUrl = probe.resolvedBaseUrl,
					username = username,
					password = password,
					deviceName = deviceName,
					displayName = displayName,
					profileId = profileId,
					createNew = createNew,
				)
				val cfg = apiClient.getConfig()
				val devices = runCatching { apiClient.listDevices() }.getOrDefault(emptyList())
				val msg = if (loginResp.authEnabled) {
					"已连接 MeloKeep ${probe.health.version}（${probe.health.sources} 个音源），登录成功"
				} else {
					"已连接 MeloKeep ${probe.health.version}（免密码模式，${probe.health.sources} 个音源）"
				}
				_state.update {
					it.copy(
						testingConnection = false,
						serverConfig = apiClient.settingsStore.currentConfig(),
						profiles = apiClient.settingsStore.profiles.value,
						activeProfileId = apiClient.settingsStore.activeProfileId.value,
						engineConfig = cfg,
						devices = devices,
						connectionMessage = msg,
						noticeMessage = msg,
					)
				}
			} catch (e: CancellationException) {
				_state.update { it.copy(testingConnection = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						testingConnection = false,
						errorMessage = e.message ?: "连接或登录失败",
					)
				}
			}
		}
	}

	fun switchProfile(profileId: String) {
		val switched = apiClient.settingsStore.switchProfile(profileId) ?: run {
			_state.update { it.copy(errorMessage = "未找到对应的服务器配置") }
			return
		}
		_state.update {
			it.copy(
				serverConfig = switched,
				profiles = apiClient.settingsStore.profiles.value,
				activeProfileId = apiClient.settingsStore.activeProfileId.value,
				engineConfig = null,
				devices = emptyList(),
				connectionMessage = null,
				errorMessage = null,
				noticeMessage = "已切换到：${switched.effectiveName}",
			)
		}
		if (switched.isAuthenticated) {
			loadConfig()
		}
	}

	fun deleteProfile(profileId: String) {
		val prevActiveId = _state.value.activeProfileId
		val nextConfig = apiClient.settingsStore.deleteProfile(profileId)
		val nextActiveId = apiClient.settingsStore.activeProfileId.value
		val activeChanged = prevActiveId == profileId || nextActiveId != prevActiveId
		_state.update {
			it.copy(
				serverConfig = nextConfig,
				profiles = apiClient.settingsStore.profiles.value,
				activeProfileId = nextActiveId,
				engineConfig = if (activeChanged) null else it.engineConfig,
				devices = if (activeChanged) emptyList() else it.devices,
				connectionMessage = if (activeChanged) null else it.connectionMessage,
				errorMessage = null,
				noticeMessage = if (activeChanged && nextConfig.isConfigured) {
					"已删除服务器配置，已切换到：${nextConfig.effectiveName}"
				} else {
					"已删除服务器配置"
				},
			)
		}
		if (activeChanged && nextConfig.isAuthenticated) {
			loadConfig()
		}
	}

	fun logout() {
		scope.launch {
			try {
				apiClient.logout()
				_state.update {
					it.copy(
						engineConfig = null,
						devices = emptyList(),
						connectionMessage = "已退出 MeloKeep 登录",
						noticeMessage = "已退出登录",
					)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				_state.update { it.copy(errorMessage = e.message ?: "退出登录失败") }
			}
		}
	}

	fun loadConfig() {
		val current = apiClient.settingsStore.currentConfig()
		if (!current.isConfigured) return
		val targetProfileId = apiClient.settingsStore.activeProfileId.value
		scope.launch {
			_state.update { it.copy(loadingConfig = true, errorMessage = null) }
			try {
				val cfg = apiClient.getConfig()
				val devices = runCatching { apiClient.listDevices() }.getOrDefault(emptyList())
				if (apiClient.settingsStore.activeProfileId.value != targetProfileId) {
					return@launch
				}
				_state.update {
					it.copy(
						loadingConfig = false,
						engineConfig = cfg,
						devices = devices,
					)
				}
			} catch (e: CancellationException) {
				_state.update { it.copy(loadingConfig = false) }
				throw e
			} catch (e: Throwable) {
				if (apiClient.settingsStore.activeProfileId.value != targetProfileId) {
					return@launch
				}
				_state.update {
					it.copy(
						loadingConfig = false,
						errorMessage = e.message ?: "加载配置失败",
					)
				}
			}
		}
	}

	fun toggleSourceEnabled(sourceId: String) {
		val cfg = _state.value.engineConfig ?: return
		val nextSources = if (sourceId in cfg.sources) {
			cfg.sources - sourceId
		} else {
			cfg.sources + sourceId
		}
		if (nextSources.isEmpty()) {
			_state.update { it.copy(errorMessage = "至少保留一个启用的音源") }
			return
		}
		saveEngineSettings(sources = nextSources)
	}

	fun saveEngineSettings(
		sources: List<String>? = null,
		proxyUrl: String? = null,
		proxyScope: String? = null,
		proxySources: List<String>? = null,
		resultsPerSource: Int? = null,
		searchTimeoutSeconds: Int? = null,
		playbackTimeoutSeconds: Int? = null,
		downloadConcurrency: Int? = null,
		downloadLayout: String? = null,
		lyricsMode: String? = null,
		embedMetadata: Boolean? = null,
	) {
		scope.launch {
			_state.update { it.copy(savingConfig = true, errorMessage = null) }
			try {
				apiClient.updateConfig(
					MeloConfigUpdateRequest(
						sources = sources,
						resultsPerSource = resultsPerSource,
						searchTimeoutSeconds = searchTimeoutSeconds,
						playbackTimeoutSeconds = playbackTimeoutSeconds,
						downloadConcurrency = downloadConcurrency,
						downloadLayout = downloadLayout,
						lyricsMode = lyricsMode,
						embedMetadata = embedMetadata,
						proxyUrl = proxyUrl,
						proxyScope = proxyScope,
						proxySources = proxySources,
					)
				)
				val refreshed = apiClient.getConfig()
				_state.update {
					it.copy(
						savingConfig = false,
						engineConfig = refreshed,
						noticeMessage = "配置已保存",
					)
				}
			} catch (e: CancellationException) {
				_state.update { it.copy(savingConfig = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						savingConfig = false,
						errorMessage = e.message ?: "保存配置失败",
					)
				}
			}
		}
	}

	fun refreshDevices() {
		if (!apiClient.settingsStore.currentConfig().isConfigured) return
		scope.launch {
			try {
				val list = apiClient.listDevices()
				_state.update { it.copy(devices = list) }
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				_state.update { it.copy(errorMessage = e.message ?: "刷新设备列表失败") }
			}
		}
	}

	fun revokeDevice(deviceId: String) {
		scope.launch {
			try {
				apiClient.revokeDevice(deviceId)
				_state.update { current ->
					current.copy(
						devices = current.devices.filterNot { it.id == deviceId },
						noticeMessage = "已吊销设备令牌",
					)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				_state.update { it.copy(errorMessage = e.message ?: "吊销设备失败") }
			}
		}
	}

	suspend fun resolveIosAppUpdate(force: Boolean = false): MeloResolvedAppUpdate =
		apiClient.resolveLatestAppRelease(platform = "ios", force = force)

	suspend fun fetchSystemUpdateSettings(): MeloSystemUpdateSettings =
		apiClient.fetchSystemUpdateSettings()

	suspend fun saveSystemUpdateSettings(
		githubToken: String? = null,
		clearGitHubToken: Boolean = false,
	): MeloSystemUpdateSettings = apiClient.saveSystemUpdateSettings(
		githubToken = githubToken,
		clearGitHubToken = clearGitHubToken,
	)

	fun clearError() {
		_state.update { it.copy(errorMessage = null) }
	}

	fun clearNotice() {
		_state.update { it.copy(noticeMessage = null) }
	}
}

data class MeloLocalLibraryUiState(
	val favorites: List<MeloTrack> = emptyList(),
	val favoriteIds: Set<String> = emptySet(),
	val playlists: List<MeloLocalPlaylistSummary> = emptyList(),
	val activePlaylist: MeloLocalPlaylistDetail? = null,
	val loading: Boolean = false,
	val playlistDetailLoading: Boolean = false,
	val mutating: Boolean = false,
	val errorMessage: String? = null,
	val noticeMessage: String? = null,
)

class MeloLocalLibraryController(
	private val apiClient: MeloKeepApiClient,
	private val scope: CoroutineScope,
) {
	private val _state = MutableStateFlow(MeloLocalLibraryUiState())
	val state: StateFlow<MeloLocalLibraryUiState> = _state.asStateFlow()

	fun refreshAll() {
		if (!apiClient.settingsStore.currentConfig().isConfigured) return
		scope.launch {
			_state.update { it.copy(loading = true, errorMessage = null) }
			try {
				val (favs, lists) = coroutineScope {
					val favsDeferred = async { apiClient.getFavorites() }
					val listsDeferred = async { apiClient.getLocalPlaylists() }
					favsDeferred.await() to listsDeferred.await()
				}
				_state.update {
					it.copy(
						favorites = favs,
						favoriteIds = favs.mapNotNull { track -> track.id.takeIf(String::isNotBlank) }.toSet(),
						playlists = lists,
						loading = false,
					)
				}
			} catch (e: CancellationException) {
				_state.update { it.copy(loading = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						loading = false,
						errorMessage = e.message ?: "加载本地收藏与歌单失败",
					)
				}
			}
		}
	}

	fun refreshFavorites() {
		if (!apiClient.settingsStore.currentConfig().isConfigured) return
		scope.launch {
			_state.update { it.copy(loading = true, errorMessage = null) }
			try {
				val favs = apiClient.getFavorites()
				_state.update {
					it.copy(
						favorites = favs,
						favoriteIds = favs.mapNotNull { track -> track.id.takeIf(String::isNotBlank) }.toSet(),
						loading = false,
					)
				}
			} catch (e: CancellationException) {
				_state.update { it.copy(loading = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						loading = false,
						errorMessage = e.message ?: "加载我喜欢列表失败",
					)
				}
			}
		}
	}

	fun toggleFavorite(track: MeloTrack) {
		val trackId = track.id.trim()
		if (trackId.isEmpty()) return
		scope.launch {
			_state.update { it.copy(mutating = true, errorMessage = null) }
			val wasFavorite = isFavorite(trackId)
			try {
				if (wasFavorite) {
					apiClient.removeFavorite(trackId)
					_state.update { current ->
						current.copy(
							favorites = current.favorites.filterNot { it.id == trackId },
							favoriteIds = current.favoriteIds - trackId,
							mutating = false,
							noticeMessage = "已取消喜欢：${track.title.ifBlank { "曲目" }}",
						)
					}
				} else {
					apiClient.addFavorite(trackId)
					_state.update { current ->
						current.copy(
							favorites = listOf(track) + current.favorites.filterNot { it.id == trackId },
							favoriteIds = current.favoriteIds + trackId,
							mutating = false,
							noticeMessage = "已添加到我喜欢：${track.title.ifBlank { "曲目" }}",
						)
					}
				}
			} catch (e: CancellationException) {
				_state.update { it.copy(mutating = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						mutating = false,
						errorMessage = e.message ?: if (wasFavorite) "取消喜欢失败" else "添加到我喜欢失败",
					)
				}
			}
		}
	}

	fun isFavorite(trackId: String): Boolean {
		return trackId.isNotBlank() && trackId in _state.value.favoriteIds
	}

	fun refreshPlaylists() {
		if (!apiClient.settingsStore.currentConfig().isConfigured) return
		scope.launch {
			_state.update { it.copy(loading = true, errorMessage = null) }
			try {
				val lists = apiClient.getLocalPlaylists()
				_state.update { it.copy(playlists = lists, loading = false) }
			} catch (e: CancellationException) {
				_state.update { it.copy(loading = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						loading = false,
						errorMessage = e.message ?: "加载本地歌单失败",
					)
				}
			}
		}
	}

	fun loadPlaylistDetail(id: String) {
		if (id.isBlank()) return
		scope.launch {
			_state.update { it.copy(playlistDetailLoading = true, errorMessage = null) }
			try {
				val detail = apiClient.getLocalPlaylistDetail(id)
				_state.update { current ->
					current.copy(
						activePlaylist = detail,
						playlists = current.playlists.map { summary ->
							if (summary.id == detail.id) summary.mergedWith(detail) else summary
						},
						playlistDetailLoading = false,
					)
				}
			} catch (e: CancellationException) {
				_state.update { it.copy(playlistDetailLoading = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						playlistDetailLoading = false,
						errorMessage = e.message ?: "加载歌单详情失败",
					)
				}
			}
		}
	}

	fun clearActivePlaylist() {
		_state.update { it.copy(activePlaylist = null, playlistDetailLoading = false) }
	}

	fun createPlaylist(name: String, trackIds: List<String> = emptyList()) {
		val cleanName = name.trim()
		if (cleanName.isEmpty()) {
			_state.update { it.copy(errorMessage = "请输入歌单名称") }
			return
		}
		scope.launch {
			_state.update { it.copy(mutating = true, errorMessage = null) }
			try {
				val created = apiClient.createLocalPlaylist(
					name = cleanName,
					trackIds = trackIds.filter { it.isNotBlank() }.distinct(),
				)
				val summary = created.toSummary()
				_state.update { current ->
					current.copy(
						playlists = listOf(summary) + current.playlists.filterNot { it.id == created.id },
						activePlaylist = created,
						mutating = false,
						noticeMessage = created.detail.ifBlank { "已创建歌单“${created.name}”" },
					)
				}
			} catch (e: CancellationException) {
				_state.update { it.copy(mutating = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						mutating = false,
						errorMessage = e.message ?: "创建歌单失败",
					)
				}
			}
		}
	}

	fun renamePlaylist(id: String, name: String) {
		if (id.isBlank()) return
		val cleanName = name.trim()
		if (cleanName.isEmpty()) {
			_state.update { it.copy(errorMessage = "请输入歌单名称") }
			return
		}
		scope.launch {
			_state.update { it.copy(mutating = true, errorMessage = null) }
			try {
				val updated = apiClient.renameLocalPlaylist(id, cleanName)
				_state.update { current ->
					current.copy(
						playlists = current.playlists.map { summary ->
							if (summary.id == updated.id) summary.mergedWith(updated) else summary
						},
						activePlaylist = if (current.activePlaylist?.id == updated.id) updated else current.activePlaylist,
						mutating = false,
						noticeMessage = "已重命名歌单为“${updated.name}”",
					)
				}
			} catch (e: CancellationException) {
				_state.update { it.copy(mutating = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						mutating = false,
						errorMessage = e.message ?: "重命名歌单失败",
					)
				}
			}
		}
	}

	fun deletePlaylist(id: String) {
		if (id.isBlank()) return
		scope.launch {
			_state.update { it.copy(mutating = true, errorMessage = null) }
			try {
				apiClient.deleteLocalPlaylist(id)
				_state.update { current ->
					current.copy(
						playlists = current.playlists.filterNot { it.id == id },
						activePlaylist = if (current.activePlaylist?.id == id) null else current.activePlaylist,
						mutating = false,
						noticeMessage = "已删除歌单",
					)
				}
			} catch (e: CancellationException) {
				_state.update { it.copy(mutating = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						mutating = false,
						errorMessage = e.message ?: "删除歌单失败",
					)
				}
			}
		}
	}

	fun addTracksToPlaylist(playlistId: String, trackIds: List<String>) {
		if (playlistId.isBlank()) return
		val cleanIds = trackIds.filter { it.isNotBlank() }.distinct()
		if (cleanIds.isEmpty()) {
			_state.update { it.copy(errorMessage = "请先选择要加入歌单的曲目") }
			return
		}
		scope.launch {
			_state.update { it.copy(mutating = true, errorMessage = null) }
			try {
				val updated = apiClient.addTracksToLocalPlaylist(playlistId, cleanIds)
				_state.update { current ->
					val hasSummary = current.playlists.any { it.id == updated.id }
					val nextPlaylists = if (hasSummary) {
						current.playlists.map { summary ->
							if (summary.id == updated.id) summary.mergedWith(updated) else summary
						}
					} else {
						listOf(updated.toSummary()) + current.playlists
					}
					current.copy(
						playlists = nextPlaylists,
						activePlaylist = if (current.activePlaylist?.id == updated.id) updated else current.activePlaylist,
						mutating = false,
						noticeMessage = updated.detail.ifBlank { "已加入歌单“${updated.name}”" },
					)
				}
			} catch (e: CancellationException) {
				_state.update { it.copy(mutating = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						mutating = false,
						errorMessage = e.message ?: "加入歌单失败",
					)
				}
			}
		}
	}

	fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
		if (playlistId.isBlank() || trackId.isBlank()) return
		scope.launch {
			_state.update { it.copy(mutating = true, errorMessage = null) }
			try {
				val updated = apiClient.removeTrackFromLocalPlaylist(playlistId, trackId)
				_state.update { current ->
					current.copy(
						playlists = current.playlists.map { summary ->
							if (summary.id == updated.id) summary.mergedWith(updated) else summary
						},
						activePlaylist = if (current.activePlaylist?.id == updated.id) updated else current.activePlaylist,
						mutating = false,
						noticeMessage = "已从歌单移除曲目",
					)
				}
			} catch (e: CancellationException) {
				_state.update { it.copy(mutating = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						mutating = false,
						errorMessage = e.message ?: "移除曲目失败",
					)
				}
			}
		}
	}

	fun removeTracksFromPlaylist(playlistId: String, trackIds: List<String>) {
		if (playlistId.isBlank()) return
		val cleanIds = trackIds.filter { it.isNotBlank() }.distinct()
		if (cleanIds.isEmpty()) {
			_state.update { it.copy(errorMessage = "请先选择要移除的曲目") }
			return
		}
		scope.launch {
			_state.update { it.copy(mutating = true, errorMessage = null) }
			try {
				val updated = apiClient.removeTracksFromLocalPlaylist(playlistId, cleanIds)
				_state.update { current ->
					current.copy(
						playlists = current.playlists.map { summary ->
							if (summary.id == updated.id) summary.mergedWith(updated) else summary
						},
						activePlaylist = if (current.activePlaylist?.id == updated.id) updated else current.activePlaylist,
						mutating = false,
						noticeMessage = "已从歌单移除 ${cleanIds.size} 首歌曲",
					)
				}
			} catch (e: CancellationException) {
				_state.update { it.copy(mutating = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						mutating = false,
						errorMessage = e.message ?: "批量移除曲目失败",
					)
				}
			}
		}
	}

	fun clearError() {
		_state.update { it.copy(errorMessage = null) }
	}

	fun clearNotice() {
		_state.update { it.copy(noticeMessage = null) }
	}

	private fun MeloLocalPlaylistDetail.toSummary(): MeloLocalPlaylistSummary =
		MeloLocalPlaylistSummary(
			id = id,
			name = name,
			source = source,
			sourceUrl = sourceUrl,
			createdAt = createdAt,
			detail = detail,
			taskId = taskId,
			trackCount = tracks.size,
			issueCount = 0,
			cover = tracks.firstOrNull { !it.cover.isNullOrBlank() }?.cover,
		)

	private fun MeloLocalPlaylistSummary.mergedWith(detail: MeloLocalPlaylistDetail): MeloLocalPlaylistSummary =
		copy(
			name = detail.name.ifBlank { name },
			source = detail.source.ifBlank { source },
			sourceUrl = detail.sourceUrl ?: sourceUrl,
			detail = detail.detail.ifBlank { this.detail },
			taskId = detail.taskId.ifBlank { taskId },
			trackCount = detail.tracks.size,
			cover = detail.tracks.firstOrNull { !it.cover.isNullOrBlank() }?.cover,
		)
}
