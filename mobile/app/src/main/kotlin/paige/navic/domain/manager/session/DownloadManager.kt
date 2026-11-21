/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.domain.manager.session

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import paige.navic.database.dao.DownloadDao
import paige.navic.database.dao.SongDao
import paige.navic.database.entity.DownloadEntity
import paige.navic.database.entity.DownloadStatus
import paige.navic.database.mapper.toEntity
import paige.navic.domain.manager.runtime.NotificationIds
import paige.navic.domain.manager.runtime.NotificationManager
import paige.navic.domain.manager.runtime.StorageManager
import paige.navic.domain.model.DomainSong
import paige.navic.domain.model.settings.DownloadStorage
import paige.navic.melokeep.MeloTrackMapper
import paige.navic.util.Logger
import paige.navic.work.DownloadMigrationWorker
import paige.navic.work.DownloadWorker
import paige.navic.work.WorkKeys
import paige.navic.work.WorkNames
import java.io.File
import java.util.concurrent.TimeUnit

private const val SQLITE_BATCH_SIZE = 500

class DownloadManager(
	private val downloadDao: DownloadDao,
	private val songDao: SongDao,
	private val storageManager: StorageManager,
	private val notificationManager: NotificationManager,
	private val sessionManager: SessionManager,
	context: Context
) {
	private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
	private val workManager = WorkManager.getInstance(context)
	private val enqueueMutex = Mutex()
	private val cancelGeneration = AtomicInteger(0)
	@Volatile
	private var libraryEnqueueJob: Job? = null

	val allDownloads = downloadDao.getAllDownloads().map { it.toImmutableList() }
	val downloadCount = downloadDao.getDownloadsCount()
	val downloadSize = downloadDao.getDownloadedFilePathsFlow().map { paths ->
		paths.sumOf { storageManager.getFileSize(it) }
	}.flowOn(Dispatchers.IO)

	val downloadedSongs: StateFlow<Map<String, String>>
		field = MutableStateFlow(emptyMap())

	val isDownloadingLibrary: StateFlow<Boolean>
		field = MutableStateFlow(false)

	val libraryDownloadProgress: StateFlow<Float>
		field = MutableStateFlow(0f)

	init {
		scope.launch {
			downloadDao.getDownloadedEntitiesFlow().collectLatest { downloads ->
				downloadedSongs.value = downloads
					.associate { it.songId to it.filePath!! }
			}
		}

		scope.launch {
			downloadDao.getDownloadsCount(DownloadStatus.DOWNLOADING).collectLatest { downloadingCount ->
				if (downloadingCount == 0 && libraryEnqueueJob?.isActive != true) {
					isDownloadingLibrary.value = false
					libraryDownloadProgress.value = 0f
					notificationManager.cancelNotification(NotificationIds.DOWNLOAD_LIBRARY)
				}
			}
		}

		scope.launch {
			workManager.getWorkInfosForUniqueWorkFlow(WorkNames.DOWNLOAD_QUEUE)
				.collectLatest { infos ->
					val running = infos.lastOrNull { it.state == WorkInfo.State.RUNNING }
					val pending = infos.any {
						it.state == WorkInfo.State.ENQUEUED ||
							it.state == WorkInfo.State.BLOCKED
					}

					if (running != null) {
						libraryDownloadProgress.value = running.progress
							.getFloat(WorkKeys.PROGRESS, 0f)
					} else if (!pending && infos.isNotEmpty() && libraryEnqueueJob?.isActive != true) {
						val hasQueuedInDb = downloadDao.countDownloadsByStatus(DownloadStatus.DOWNLOADING) > 0
						if (!hasQueuedInDb) {
							isDownloadingLibrary.value = false
							libraryDownloadProgress.value = 0f
							notificationManager.cancelNotification(NotificationIds.DOWNLOAD_LIBRARY)
						}
					}
				}
		}

		scope.launch {
			sessionManager.sessionState
				.filterIsInstance<SessionState.Authenticated>()
				.collect {
					if (downloadDao.getDownloadsByStatus(DownloadStatus.DOWNLOADING).isNotEmpty()) {
						enqueueWorker()
					}
				}
		}

		// 即使未登录 Navidrome（仅配置 MeloKeep），重启后也要恢复未完成的本地库/MeloKeep 离线任务
		scope.launch {
			val pending = downloadDao.getDownloadsByStatus(DownloadStatus.DOWNLOADING)
			if (pending.any { MeloTrackMapper.isMeloSongId(it.songId) }) {
				enqueueWorker()
			}
		}
	}

	suspend fun getDownloadedFilePath(songId: String): String? {
		return downloadDao.getDownloadById(songId)
			?.takeIf { it.status == DownloadStatus.DOWNLOADED }
			?.filePath
	}

	fun getCoverArtFile(coverArtId: String): File? {
		return storageManager.getCoverArtFile(coverArtId)
	}

	fun downloadSong(songId: String) {
		scope.launch {
			enqueueSongs(listOf(songId))
		}
	}

	suspend fun downloadCollection(collection: List<String>) {
		enqueueSongs(collection)
	}

	suspend fun downloadExternalSongs(songs: List<DomainSong>): Int {
		if (songs.isEmpty()) return 0
		val normalized = songs.distinctBy { it.id }
		normalized
			.map { it.toEntity() }
			.chunked(SQLITE_BATCH_SIZE)
			.forEach { chunk -> songDao.insertSongs(chunk) }
		return enqueueSongs(normalized.map { it.id })
	}

	fun downloadExternalSong(song: DomainSong) {
		scope.launch {
			downloadExternalSongs(listOf(song))
		}
	}

	fun downloadEntireLibrary() {
		if (isDownloadingLibrary.value) return

		val expectedGen = cancelGeneration.get()
		isDownloadingLibrary.value = true
		libraryDownloadProgress.value = 0f

		libraryEnqueueJob?.cancel()
		val job = scope.launch {
			try {
				val ids = songDao.getAllSongIds()
					.filterNot { it.startsWith("device:") }

				if (ids.isEmpty() || cancelGeneration.get() != expectedGen) {
					isDownloadingLibrary.value = false
					libraryDownloadProgress.value = if (ids.isEmpty()) 1f else 0f
					return@launch
				}

				val enqueued = enqueueSongs(ids, expectedGen = expectedGen)
				if (cancelGeneration.get() != expectedGen) {
					isDownloadingLibrary.value = false
					libraryDownloadProgress.value = 0f
					return@launch
				}
				if (enqueued == 0 && downloadDao.countDownloadsByStatus(DownloadStatus.DOWNLOADING) == 0) {
					isDownloadingLibrary.value = false
					libraryDownloadProgress.value = 1f
				}
			} catch (e: kotlinx.coroutines.CancellationException) {
				isDownloadingLibrary.value = false
				libraryDownloadProgress.value = 0f
				throw e
			} catch (e: Exception) {
				Logger.e("DownloadManager", "Failed to queue library download", e)
				isDownloadingLibrary.value = false
				libraryDownloadProgress.value = 0f
			}
		}
		libraryEnqueueJob = job
		job.invokeOnCompletion {
			if (libraryEnqueueJob === job) {
				libraryEnqueueJob = null
			}
		}
	}

	private suspend fun enqueueSongs(
		songIds: List<String>,
		expectedGen: Int? = null
	): Int = enqueueMutex.withLock {
		if (songIds.isEmpty()) return 0

		val distinctIds = songIds.distinct().filterNot { it.startsWith("device:") }
		if (distinctIds.isEmpty()) return 0

		val existing = distinctIds
			.chunked(SQLITE_BATCH_SIZE)
			.flatMap { chunk ->
				coroutineContext.ensureActive()
				if (expectedGen != null && cancelGeneration.get() != expectedGen) return 0
				downloadDao.getActiveOrCompletedDownloadIds(chunk)
			}
			.toSet()

		val newIds = distinctIds.filter { it !in existing }
		if (newIds.isEmpty()) return 0

		newIds
			.map { DownloadEntity(it, DownloadStatus.DOWNLOADING, 0f) }
			.chunked(SQLITE_BATCH_SIZE)
			.forEach { chunk ->
				coroutineContext.ensureActive()
				if (expectedGen != null && cancelGeneration.get() != expectedGen) {
					downloadDao.deleteDownloadsByStatus(DownloadStatus.DOWNLOADING)
					return 0
				}
				downloadDao.insertDownloads(chunk)
			}

		coroutineContext.ensureActive()
		if (expectedGen != null && cancelGeneration.get() != expectedGen) {
			downloadDao.deleteDownloadsByStatus(DownloadStatus.DOWNLOADING)
			return 0
		}

		enqueueWorker()
		return newIds.size
	}

	private fun enqueueWorker() {
		val request = OneTimeWorkRequestBuilder<DownloadWorker>()
			.setConstraints(
				Constraints.Builder()
					.setRequiredNetworkType(NetworkType.CONNECTED)
					.build()
			)
			.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
			.addTag(WorkNames.DOWNLOAD_TAG)
			.build()

		workManager.enqueueUniqueWork(
			WorkNames.DOWNLOAD_QUEUE,
			ExistingWorkPolicy.KEEP,
			request
		)
	}

	fun cancelAllActiveDownloads() {
		cancelGeneration.incrementAndGet()
		isDownloadingLibrary.value = false
		libraryDownloadProgress.value = 0f

		scope.launch {
			cancelActiveDownloadsInternal()
		}
	}

	private suspend fun cancelActiveDownloadsInternal() {
		cancelGeneration.incrementAndGet()
		libraryEnqueueJob?.let { job ->
			libraryEnqueueJob = null
			job.cancelAndJoin()
		}
		enqueueMutex.withLock {
			workManager.cancelUniqueWork(WorkNames.DOWNLOAD_QUEUE).await()
			workManager.cancelAllWorkByTag(WorkNames.DOWNLOAD_TAG).await()
			downloadDao.deleteDownloadsByStatus(DownloadStatus.DOWNLOADING)
			storageManager.clearTempDownloads()
			notificationManager.cancelNotification(NotificationIds.DOWNLOAD_LIBRARY)
			isDownloadingLibrary.value = false
			libraryDownloadProgress.value = 0f
		}
	}

	private suspend fun cancelDownloadInternal(songId: String) {
		val existing = downloadDao.getDownloadById(songId) ?: return

		when (existing.status) {
			DownloadStatus.DOWNLOADING -> {
				downloadDao.deleteDownload(songId)
				storageManager.deleteSongFiles(songId)
			}

			DownloadStatus.FAILED -> {
				downloadDao.deleteDownload(songId)
				storageManager.deleteSongFiles(songId)
			}

			else -> Unit
		}
	}

	fun cancelDownload(songId: String) {
		scope.launch {
			cancelDownloadInternal(songId)
			if (downloadDao.countDownloadsByStatus(DownloadStatus.DOWNLOADING) == 0 &&
				libraryEnqueueJob?.isActive != true
			) {
				cancelActiveDownloadsInternal()
			}
		}
	}

	fun cancelCollectionDownload(songIds: Collection<String>) {
		val targetIds = songIds.distinct()
		if (targetIds.isEmpty()) return
		cancelGeneration.incrementAndGet()
		scope.launch {
			libraryEnqueueJob?.let { job ->
				libraryEnqueueJob = null
				job.cancelAndJoin()
			}
			val failedIds = enqueueMutex.withLock {
				val failed = mutableListOf<String>()
				targetIds.chunked(SQLITE_BATCH_SIZE).forEach { chunk ->
					failed += downloadDao.getFailedDownloadIds(chunk)
					downloadDao.deleteDownloadsByIdsAndStatus(chunk, DownloadStatus.DOWNLOADING)
					downloadDao.deleteDownloadsByIdsAndStatus(chunk, DownloadStatus.FAILED)
				}
				failed
			}
			failedIds.forEach { songId ->
				storageManager.deleteSongFiles(songId)
			}
			storageManager.clearTempDownloads()
			if (downloadDao.countDownloadsByStatus(DownloadStatus.DOWNLOADING) == 0) {
				cancelActiveDownloadsInternal()
			}
		}
	}

	private suspend fun deleteDownloadInternal(songId: String) {
		val existing = downloadDao.getDownloadById(songId)
		val coverArtId = songDao.getSongById(songId)?.coverArtId
		downloadDao.deleteDownload(songId)

		val filePath = existing?.filePath
		if (filePath != null) {
			storageManager.deleteFile(filePath)
			storageManager.deleteTempFile(filePath)
		} else {
			storageManager.deleteSongFiles(songId)
		}

		if (existing != null && coverArtId != null &&
			downloadDao.countDownloadedSongsWithCoverArt(coverArtId) == 0
		) {
			storageManager.deleteCoverArt(coverArtId)
		}
	}

	fun deleteDownload(songId: String) {
		scope.launch {
			deleteDownloadInternal(songId)
		}
	}

	fun deleteDownloadedCollection(songIds: Collection<String>) {
		val targetIds = songIds.distinct()
		if (targetIds.isEmpty()) return
		scope.launch {
			for (songId in targetIds) {
				deleteDownloadInternal(songId)
			}
		}
	}

	fun getCollectionDownloadStatus(songIds: List<String>): Flow<DownloadStatus> {
		if (songIds.isEmpty()) return flowOf(DownloadStatus.NOT_DOWNLOADED)
		val targetIdSet = songIds.toSet()
		val downloadsFlow: Flow<List<DownloadEntity>> = if (targetIdSet.size > SQLITE_BATCH_SIZE) {
			allDownloads.map { downloads -> downloads.filter { it.songId in targetIdSet } }
		} else {
			downloadDao.getDownloadsForSongsFlow(targetIdSet)
		}
		return downloadsFlow.map { collectionDownloads ->
			when {
				collectionDownloads.isEmpty() -> DownloadStatus.NOT_DOWNLOADED
				collectionDownloads.any {
					it.status == DownloadStatus.DOWNLOADING
				} -> DownloadStatus.DOWNLOADING

				collectionDownloads.any { it.status == DownloadStatus.FAILED } ->
					DownloadStatus.FAILED

				(collectionDownloads.size == targetIdSet.size &&
					collectionDownloads.all { it.status == DownloadStatus.DOWNLOADED })
					-> DownloadStatus.DOWNLOADED

				else -> DownloadStatus.NOT_DOWNLOADED
			}
		}
	}

	fun clearAllDownloads() {
		isDownloadingLibrary.value = false
		libraryDownloadProgress.value = 0f

		scope.launch {
			cancelActiveDownloadsInternal()
			storageManager.clearDownloads()
			storageManager.clearCoverArts()
			downloadDao.clearAllDownloads()
			Logger.i("DownloadManager", "cleared all downloads")
		}
	}

	fun migrateDownloads(target: DownloadStorage) {
		scope.launch {
			val request = OneTimeWorkRequestBuilder<DownloadMigrationWorker>()
				.setInputData(workDataOf(WorkKeys.STORAGE to target.name))
				.addTag(WorkNames.DOWNLOAD_TAG)
				.build()

			workManager.enqueueUniqueWork(
				WorkNames.DOWNLOAD_MIGRATION,
				ExistingWorkPolicy.REPLACE,
				request
			)

			Logger.i("DownloadManager", "started download migration to $target")
		}
	}
}
