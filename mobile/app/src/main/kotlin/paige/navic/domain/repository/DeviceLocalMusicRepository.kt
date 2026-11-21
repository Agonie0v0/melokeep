package paige.navic.domain.repository

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import paige.navic.domain.manager.runtime.PermissionManager
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.model.DomainExplicitStatus
import paige.navic.domain.model.DomainSong
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

data class DeviceAudioFolder(
	val path: String,
	val name: String,
	val songs: List<DomainSong>,
	val coverArtId: String?,
	val excluded: Boolean = false,
	val isSaf: Boolean = false,
	val safTreeUri: String? = null,
	val rawSongCount: Int = songs.size
)

data class DeviceAudioArtist(
	val name: String,
	val songs: List<DomainSong>,
	val albumCount: Int,
	val coverArtId: String?
)

data class DeviceAudioAlbum(
	val name: String,
	val artist: String,
	val songs: List<DomainSong>,
	val coverArtId: String?
)

data class DeviceSafFolder(
	val treeUri: String,
	val name: String,
	val songCount: Int,
	val accessible: Boolean = true
)

data class DeviceMusicState(
	val loading: Boolean = false,
	val hasPermission: Boolean = false,
	val allFolders: List<DeviceAudioFolder> = emptyList(),
	val visibleFolders: List<DeviceAudioFolder> = emptyList(),
	val safFolders: List<DeviceSafFolder> = emptyList(),
	val allSongs: List<DomainSong> = emptyList(),
	val artists: List<DeviceAudioArtist> = emptyList(),
	val albums: List<DeviceAudioAlbum> = emptyList(),
	val minDurationSec: Int = 60,
	val minSizeKb: Int = 0,
	val sortOrder: String = DeviceLocalMusicRepository.SORT_TITLE,
	val hiddenCount: Int = 0,
	val filteredOutCount: Int = 0,
	val lastScanSummary: String = "",
	val error: String? = null
) {
	val folders: List<DeviceAudioFolder>
		get() = visibleFolders
}

class DeviceLocalMusicRepository(
	private val context: Context,
	private val permissionManager: PermissionManager,
	private val preferenceManager: PreferenceManager
) {
	private data class ScannedLocalEntry(
		val song: DomainSong,
		val folderPath: String,
		val folderName: String,
		val isSaf: Boolean,
		val safTreeUri: String?,
		val dateAddedSec: Long,
		val rawPath: String?,
		val sidecarUri: Uri?
	)

	private data class SafExtractedMeta(
		val title: String,
		val artist: String,
		val album: String,
		val durationMs: Long,
		val bitRate: Int?,
		val year: Int?,
		val trackNumber: Int?
	)

	private data class ScanSnapshot(
		val entries: List<ScannedLocalEntry>,
		val safFolders: List<DeviceSafFolder>
	)

	private val _state = MutableStateFlow(
		DeviceMusicState(
			hasPermission = permissionManager.hasAudioStoragePermission(),
			minDurationSec = preferenceManager.deviceMusicMinDurationSec.coerceAtLeast(0),
			minSizeKb = preferenceManager.deviceMusicMinSizeKb.coerceAtLeast(0),
			sortOrder = normalizeSortOrder(preferenceManager.deviceMusicSortOrder),
			hiddenCount = preferenceManager.deviceMusicHiddenIds.size
		)
	)
	val state: StateFlow<DeviceMusicState> = _state.asStateFlow()

	private val refreshMutex = Mutex()
	private val stateLock = Any()

	@Volatile
	private var rawEntries: List<ScannedLocalEntry> = emptyList()

	@Volatile
	private var safFoldersSnapshot: List<DeviceSafFolder> = emptyList()

	private val songsById = ConcurrentHashMap<String, DomainSong>()
	private val rawPathById = ConcurrentHashMap<String, String>()
	private val safSidecarUriBySongId = ConcurrentHashMap<String, Uri>()
	private val dateAddedSecById = ConcurrentHashMap<String, Long>()
	private val safMetadataCache = ConcurrentHashMap<String, SafExtractedMeta>()

	fun hasPermission(): Boolean {
		val granted = permissionManager.hasAudioStoragePermission()
		if (_state.value.hasPermission != granted) {
			_state.value = _state.value.copy(hasPermission = granted)
		}
		return granted
	}

	fun getSongById(songId: String): DomainSong? = songsById[songId]

	fun getSongRawPath(songId: String): String? = rawPathById[songId]

	fun getSongDateAddedSec(songId: String): Long? = dateAddedSecById[songId]

	fun readSidecarLyrics(songId: String): String? {
		safSidecarUriBySongId[songId]?.let { uri ->
			val text = readTextFromContentUri(uri)
			if (!text.isNullOrBlank()) return text
		}

		val rawPath = rawPathById[songId]?.takeIf { it.isNotBlank() } ?: return null
		val audioFile = File(rawPath)
		val parent = audioFile.parentFile ?: return null
		val baseName = audioFile.nameWithoutExtension.takeIf { it.isNotBlank() } ?: return null
		val candidates = listOf(
			File(parent, "$baseName.lrc"),
			File(parent, "$baseName.LRC"),
			File(parent, "$baseName.txt")
		)
		for (candidate in candidates) {
			if (candidate.exists() && candidate.isFile && candidate.canRead() && candidate.length() in 1L..MAX_LYRICS_BYTES) {
				val text = runCatching { candidate.readText(Charsets.UTF_8) }.getOrNull()
				if (!text.isNullOrBlank()) return text
			}
		}
		return null
	}

	suspend fun refresh(forceRescan: Boolean = false) {
		refreshMutex.withLock {
			val granted = permissionManager.hasAudioStoragePermission()
			val safUris = preferenceManager.deviceMusicSafTreeUris
			if (!granted && safUris.isEmpty()) {
				synchronized(stateLock) {
					rawEntries = emptyList()
					safFoldersSnapshot = emptyList()
					songsById.clear()
					rawPathById.clear()
					safSidecarUriBySongId.clear()
					dateAddedSecById.clear()
					_state.value = DeviceMusicState(
						loading = false,
						hasPermission = false,
						minDurationSec = preferenceManager.deviceMusicMinDurationSec.coerceAtLeast(0),
						minSizeKb = preferenceManager.deviceMusicMinSizeKb.coerceAtLeast(0),
						sortOrder = normalizeSortOrder(preferenceManager.deviceMusicSortOrder),
						hiddenCount = preferenceManager.deviceMusicHiddenIds.size,
						lastScanSummary = ""
					)
				}
				return
			}

			if (forceRescan) {
				safMetadataCache.clear()
			}

			_state.value = _state.value.copy(
				loading = true,
				hasPermission = granted,
				error = null
			)

			runCatching {
				scanAllSources(includeMediaStore = granted, safTreeUris = safUris)
			}.onSuccess { snapshot ->
				synchronized(stateLock) {
					rawEntries = snapshot.entries
					safFoldersSnapshot = snapshot.safFolders
					rebuildLookupMaps(snapshot.entries)
					recomputeStateLocked(hasPermission = granted, error = null)
				}
			}.onFailure { err ->
				_state.value = _state.value.copy(
					loading = false,
					hasPermission = granted,
					error = err.message ?: "读取手机本地音乐失败"
				)
			}
		}
	}

	suspend fun addSafFolder(treeUri: Uri) {
		val resolver = context.contentResolver
		runCatching {
			resolver.takePersistableUriPermission(
				treeUri,
				Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
			)
		}.onFailure {
			runCatching {
				resolver.takePersistableUriPermission(
					treeUri,
					Intent.FLAG_GRANT_READ_URI_PERMISSION
				)
			}
		}
		val uriString = treeUri.toString()
		if (uriString !in preferenceManager.deviceMusicSafTreeUris) {
			preferenceManager.deviceMusicSafTreeUris = preferenceManager.deviceMusicSafTreeUris + uriString
		}
		refresh(forceRescan = false)
	}

	suspend fun removeSafFolder(treeUriString: String) {
		val parsedUri = runCatching { Uri.parse(treeUriString) }.getOrNull()
		if (parsedUri != null) {
			runCatching {
				context.contentResolver.releasePersistableUriPermission(
					parsedUri,
					Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
				)
			}.onFailure {
				runCatching {
					context.contentResolver.releasePersistableUriPermission(
						parsedUri,
						Intent.FLAG_GRANT_READ_URI_PERMISSION
					)
				}
			}
		}
		val pathsToUnexclude = rawEntries
			.asSequence()
			.filter { it.safTreeUri == treeUriString }
			.mapTo(HashSet()) { it.folderPath }
		if (pathsToUnexclude.isNotEmpty()) {
			preferenceManager.deviceMusicExcludedFolders =
				preferenceManager.deviceMusicExcludedFolders - pathsToUnexclude
		}
		preferenceManager.deviceMusicSafTreeUris = preferenceManager.deviceMusicSafTreeUris - treeUriString
		refresh(forceRescan = false)
	}

	fun setFolderExcluded(folderPath: String, excluded: Boolean) {
		val current = preferenceManager.deviceMusicExcludedFolders
		val updated = if (excluded) current + folderPath else current - folderPath
		if (updated != current) {
			preferenceManager.deviceMusicExcludedFolders = updated
		}
		synchronized(stateLock) {
			recomputeStateLocked(hasPermission = permissionManager.hasAudioStoragePermission())
		}
	}

	fun setMinDurationSec(sec: Int) {
		val clamped = sec.coerceAtLeast(0)
		if (preferenceManager.deviceMusicMinDurationSec != clamped) {
			preferenceManager.deviceMusicMinDurationSec = clamped
		}
		synchronized(stateLock) {
			recomputeStateLocked(hasPermission = permissionManager.hasAudioStoragePermission())
		}
	}

	fun setMinSizeKb(kb: Int) {
		val clamped = kb.coerceAtLeast(0)
		if (preferenceManager.deviceMusicMinSizeKb != clamped) {
			preferenceManager.deviceMusicMinSizeKb = clamped
		}
		synchronized(stateLock) {
			recomputeStateLocked(hasPermission = permissionManager.hasAudioStoragePermission())
		}
	}

	fun setSortOrder(order: String) {
		val normalized = normalizeSortOrder(order)
		if (preferenceManager.deviceMusicSortOrder != normalized) {
			preferenceManager.deviceMusicSortOrder = normalized
		}
		synchronized(stateLock) {
			recomputeStateLocked(hasPermission = permissionManager.hasAudioStoragePermission())
		}
	}

	fun hideSongs(ids: Set<String>) {
		if (ids.isEmpty()) return
		val updated = preferenceManager.deviceMusicHiddenIds + ids
		preferenceManager.deviceMusicHiddenIds = updated
		synchronized(stateLock) {
			recomputeStateLocked(hasPermission = permissionManager.hasAudioStoragePermission())
		}
	}

	fun restoreHiddenSongs() {
		if (preferenceManager.deviceMusicHiddenIds.isEmpty()) return
		preferenceManager.deviceMusicHiddenIds = emptySet()
		synchronized(stateLock) {
			recomputeStateLocked(hasPermission = permissionManager.hasAudioStoragePermission())
		}
	}

	suspend fun deleteSongFile(song: DomainSong): Boolean {
		return deleteSongFiles(listOf(song)) > 0
	}

	suspend fun deleteSongFiles(songs: List<DomainSong>): Int = withContext(Dispatchers.IO) {
		if (songs.isEmpty()) return@withContext 0
		val deletedIds = HashSet<String>()
		for (song in songs) {
			if (deleteSingleBackingFile(song)) {
				deletedIds.add(song.id)
			}
		}
		if (deletedIds.isNotEmpty()) {
			synchronized(stateLock) {
				val remaining = rawEntries.filterNot { it.song.id in deletedIds }
				rawEntries = remaining
				val remainingByTree = remaining
					.filter { it.safTreeUri != null }
					.groupingBy { it.safTreeUri!! }
					.eachCount()
				safFoldersSnapshot = safFoldersSnapshot.map { saf ->
					saf.copy(songCount = remainingByTree[saf.treeUri] ?: 0)
				}
				deletedIds.forEach { id ->
					songsById.remove(id)
					rawPathById.remove(id)
					safSidecarUriBySongId.remove(id)
					dateAddedSecById.remove(id)
				}
				val currentHidden = preferenceManager.deviceMusicHiddenIds
				if (currentHidden.any { it in deletedIds }) {
					preferenceManager.deviceMusicHiddenIds = currentHidden - deletedIds
				}
				recomputeStateLocked(hasPermission = permissionManager.hasAudioStoragePermission())
			}
		}
		deletedIds.size
	}

	private fun deleteSingleBackingFile(song: DomainSong): Boolean {
		val uriString = song.filePath?.takeIf { it.isNotBlank() }
		val parsedUri = uriString?.let { runCatching { Uri.parse(it) }.getOrNull() }

		if (song.id.startsWith(SAF_ID_PREFIX)) {
			if (parsedUri == null) return false
			val deletedViaDocuments = runCatching {
				DocumentsContract.deleteDocument(context.contentResolver, parsedUri)
			}.getOrDefault(false)
			if (deletedViaDocuments) return true
			return runCatching {
				context.contentResolver.delete(parsedUri, null, null) > 0
			}.getOrDefault(false)
		}

		if (parsedUri != null) {
			val deletedViaResolver = runCatching {
				context.contentResolver.delete(parsedUri, null, null) > 0
			}.getOrDefault(false)
			if (deletedViaResolver) return true
		}

		val rawPath = rawPathById[song.id]?.takeIf { it.isNotBlank() } ?: return false
		val file = File(rawPath)
		val fileDeleted = file.exists() && runCatching { file.delete() }.getOrDefault(false)
		if (fileDeleted && parsedUri != null) {
			runCatching { context.contentResolver.delete(parsedUri, null, null) }
		}
		return fileDeleted
	}

	private fun rebuildLookupMaps(entries: List<ScannedLocalEntry>) {
		songsById.clear()
		rawPathById.clear()
		safSidecarUriBySongId.clear()
		dateAddedSecById.clear()
		entries.forEach { entry ->
			val id = entry.song.id
			songsById[id] = entry.song
			dateAddedSecById[id] = entry.dateAddedSec
			if (!entry.rawPath.isNullOrBlank()) {
				rawPathById[id] = entry.rawPath
			}
			if (entry.sidecarUri != null) {
				safSidecarUriBySongId[id] = entry.sidecarUri
			}
		}
	}

	private fun recomputeStateLocked(
		hasPermission: Boolean,
		error: String? = null
	) {
		val excludedFolders = preferenceManager.deviceMusicExcludedFolders
		val minDurationSec = preferenceManager.deviceMusicMinDurationSec.coerceAtLeast(0)
		val minSizeKb = preferenceManager.deviceMusicMinSizeKb.coerceAtLeast(0)
		val hiddenIds = preferenceManager.deviceMusicHiddenIds
		val sortOrder = normalizeSortOrder(preferenceManager.deviceMusicSortOrder)

		val minDurationMs = minDurationSec * 1000L
		val minSizeBytes = minSizeKb * 1024L

		val rawBuckets = linkedMapOf<String, MutableList<ScannedLocalEntry>>()
		rawEntries.forEach { entry ->
			rawBuckets.getOrPut(entry.folderPath) { mutableListOf() }.add(entry)
		}

		val visibleEntries = rawEntries.filter { entry ->
			if (entry.folderPath in excludedFolders) return@filter false
			if (entry.song.id in hiddenIds) return@filter false
			val durationMs = entry.song.duration.inWholeMilliseconds
			if (minDurationMs > 0L && durationMs in 1L until minDurationMs) return@filter false
			val fileSize = entry.song.fileSize
			if (minSizeBytes > 0L && fileSize in 1L until minSizeBytes) return@filter false
			true
		}

		val sortedVisibleEntries = sortEntries(visibleEntries, sortOrder)
		val sortedSongs = sortedVisibleEntries.map { it.song }

		val visibleEntriesByFolder = linkedMapOf<String, MutableList<ScannedLocalEntry>>()
		sortedVisibleEntries.forEach { entry ->
			visibleEntriesByFolder.getOrPut(entry.folderPath) { mutableListOf() }.add(entry)
		}

		val allFolders = rawBuckets.entries
			.map { (folderPath, rawBucket) ->
				val sample = rawBucket.first()
				val visibleInFolder = visibleEntriesByFolder[folderPath].orEmpty().map { it.song }
				DeviceAudioFolder(
					path = folderPath,
					name = sample.folderName,
					songs = visibleInFolder,
					coverArtId = visibleInFolder.firstNotNullOfOrNull { it.coverArtId }
						?: rawBucket.firstNotNullOfOrNull { it.song.coverArtId },
					excluded = folderPath in excludedFolders,
					isSaf = sample.isSaf,
					safTreeUri = sample.safTreeUri,
					rawSongCount = rawBucket.size
				)
			}
			.sortedWith(
				compareBy<DeviceAudioFolder> { !it.isSaf }
					.thenBy { it.path.lowercase() }
			)

		val visibleFolders = allFolders.filter { !it.excluded && it.songs.isNotEmpty() }

		val artists = sortedSongs
			.groupBy { it.artistName?.trim()?.ifBlank { UNKNOWN_ARTIST } ?: UNKNOWN_ARTIST }
			.map { (artistName, artistSongs) ->
				val albumCount = artistSongs.mapTo(HashSet()) {
					it.albumTitle?.trim()?.ifBlank { DEFAULT_ALBUM } ?: DEFAULT_ALBUM
				}.size
				DeviceAudioArtist(
					name = artistName,
					songs = artistSongs,
					albumCount = albumCount,
					coverArtId = artistSongs.firstNotNullOfOrNull { it.coverArtId?.takeIf { c -> c.isNotBlank() } }
				)
			}
			.sortedWith(
				compareBy<DeviceAudioArtist> { it.name == UNKNOWN_ARTIST }
					.thenBy { it.name.lowercase() }
			)

		val albums = sortedSongs
			.groupBy { it.albumTitle?.trim()?.ifBlank { DEFAULT_ALBUM } ?: DEFAULT_ALBUM }
			.map { (albumName, albumSongs) ->
				val primaryArtist = albumSongs
					.groupingBy { it.artistName?.trim()?.ifBlank { UNKNOWN_ARTIST } ?: UNKNOWN_ARTIST }
					.eachCount()
					.maxByOrNull { it.value }
					?.key ?: UNKNOWN_ARTIST
				DeviceAudioAlbum(
					name = albumName,
					artist = primaryArtist,
					songs = albumSongs,
					coverArtId = albumSongs.firstNotNullOfOrNull { it.coverArtId?.takeIf { c -> c.isNotBlank() } }
				)
			}
			.sortedWith(
				compareBy<DeviceAudioAlbum> { it.name == DEFAULT_ALBUM }
					.thenBy { it.name.lowercase() }
			)

		val rawTotal = rawEntries.size
		val visibleTotal = sortedSongs.size
		val filteredOutCount = (rawTotal - visibleTotal).coerceAtLeast(0)
		val hiddenCount = hiddenIds.size

		val summary = buildString {
			append("已收录 $visibleTotal 首")
			if (filteredOutCount > 0) {
				append(" · 过滤 $filteredOutCount 首短音频/屏蔽目录")
			}
		}

		_state.value = DeviceMusicState(
			loading = false,
			hasPermission = hasPermission,
			allFolders = allFolders,
			visibleFolders = visibleFolders,
			safFolders = safFoldersSnapshot,
			allSongs = sortedSongs,
			artists = artists,
			albums = albums,
			minDurationSec = minDurationSec,
			minSizeKb = minSizeKb,
			sortOrder = sortOrder,
			hiddenCount = hiddenCount,
			filteredOutCount = filteredOutCount,
			lastScanSummary = summary,
			error = error
		)
	}

	private fun sortEntries(
		entries: List<ScannedLocalEntry>,
		sortOrder: String
	): List<ScannedLocalEntry> {
		return when (sortOrder) {
			SORT_ADDED -> entries.sortedWith(
				compareByDescending<ScannedLocalEntry> { it.dateAddedSec }
					.thenBy { it.song.title.lowercase() }
			)
			SORT_ARTIST -> entries.sortedWith(
				compareBy<ScannedLocalEntry> {
					val artist = it.song.artistName.orEmpty()
					artist == UNKNOWN_ARTIST || artist.isBlank()
				}
					.thenBy { it.song.artistName.orEmpty().lowercase() }
					.thenBy { it.song.albumTitle.orEmpty().lowercase() }
					.thenBy { it.song.trackNumber ?: Int.MAX_VALUE }
					.thenBy { it.song.title.lowercase() }
			)
			SORT_SIZE -> entries.sortedWith(
				compareByDescending<ScannedLocalEntry> { it.song.fileSize }
					.thenBy { it.song.title.lowercase() }
			)
			SORT_DURATION -> entries.sortedWith(
				compareByDescending<ScannedLocalEntry> { it.song.duration.inWholeMilliseconds }
					.thenBy { it.song.title.lowercase() }
			)
			else -> entries.sortedWith(
				compareBy<ScannedLocalEntry> { it.song.title.lowercase() }
					.thenBy { it.song.artistName.orEmpty().lowercase() }
			)
		}
	}

	private suspend fun scanAllSources(
		includeMediaStore: Boolean,
		safTreeUris: Set<String>
	): ScanSnapshot = withContext(Dispatchers.IO) {
		val allEntries = ArrayList<ScannedLocalEntry>()
		val safFolders = ArrayList<DeviceSafFolder>()
		val safPrimaryRelPaths = HashSet<String>()

		for (treeUriString in safTreeUris) {
			val treeUri = runCatching { Uri.parse(treeUriString) }.getOrNull() ?: continue
			val (treeEntries, safMeta) = scanSafTree(treeUri, treeUriString, safPrimaryRelPaths)
			allEntries.addAll(treeEntries)
			safFolders.add(safMeta)
		}

		if (includeMediaStore) {
			val mediaStoreEntries = queryMediaStoreAudio(safPrimaryRelPaths)
			allEntries.addAll(mediaStoreEntries)
		}

		ScanSnapshot(
			entries = allEntries,
			safFolders = safFolders.sortedBy { it.name.lowercase() }
		)
	}

	private fun scanSafTree(
		treeUri: Uri,
		treeUriString: String,
		safPrimaryRelPaths: MutableSet<String>
	): Pair<List<ScannedLocalEntry>, DeviceSafFolder> {
		val rootDocId = runCatching {
			DocumentsContract.getTreeDocumentId(treeUri)
		}.getOrNull()

		if (rootDocId.isNullOrBlank()) {
			return emptyList<ScannedLocalEntry>() to DeviceSafFolder(
				treeUri = treeUriString,
				name = "自定义目录",
				songCount = 0,
				accessible = false
			)
		}

		val rootName = querySafRootDisplayName(treeUri, rootDocId)
		val entries = ArrayList<ScannedLocalEntry>()
		val visitedDocIds = HashSet<String>()
		val queue = ArrayDeque<Pair<String, String>>()
		queue.addLast(rootDocId to rootName)

		var accessible = false
		val rootDocUri = runCatching {
			DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
		}.getOrNull()
		if (rootDocUri != null) {
			accessible = runCatching {
				context.contentResolver.query(
					rootDocUri,
					arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
					null,
					null,
					null
				)?.use { true } ?: false
			}.getOrDefault(false)
		}

		while (queue.isNotEmpty()) {
			val (parentDocId, relFolder) = queue.removeFirst()
			if (!visitedDocIds.add(parentDocId)) continue

			val childrenUri = runCatching {
				DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
			}.getOrNull() ?: continue

			data class RawSafChild(
				val docId: String,
				val displayName: String,
				val mimeType: String?,
				val sizeBytes: Long,
				val lastModifiedMs: Long
			)

			val children = ArrayList<RawSafChild>()
			val queryOk = runCatching {
				context.contentResolver.query(
					childrenUri,
					arrayOf(
						DocumentsContract.Document.COLUMN_DOCUMENT_ID,
						DocumentsContract.Document.COLUMN_DISPLAY_NAME,
						DocumentsContract.Document.COLUMN_MIME_TYPE,
						DocumentsContract.Document.COLUMN_SIZE,
						DocumentsContract.Document.COLUMN_LAST_MODIFIED
					),
					null,
					null,
					null
				)?.use { cursor ->
					accessible = true
					val docIdCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
					val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
					val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
					val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
					val modCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

					while (cursor.moveToNext()) {
						val docId = if (docIdCol >= 0) cursor.getString(docIdCol).orEmpty().trim() else ""
						val displayName = if (nameCol >= 0) cursor.getString(nameCol).orEmpty().trim() else ""
						if (docId.isBlank() || displayName.isBlank()) continue
						if (displayName.startsWith(".trashed-")) continue

						val mimeType = if (mimeCol >= 0) cursor.getString(mimeCol)?.trim() else null
						val sizeBytes = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) {
							cursor.getLong(sizeCol).coerceAtLeast(0L)
						} else {
							0L
						}
						val lastModifiedMs = if (modCol >= 0 && !cursor.isNull(modCol)) {
							cursor.getLong(modCol).coerceAtLeast(0L)
						} else {
							0L
						}
						children.add(RawSafChild(docId, displayName, mimeType, sizeBytes, lastModifiedMs))
					}
				}
			}.isSuccess

			if (!queryOk) continue

			// 先索引当前目录下的旁挂歌词文件（.lrc 优先于 .txt）
			val sidecarByBaseName = HashMap<String, Pair<Int, Uri>>()
			for (child in children) {
				if (child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) continue
				val ext = child.displayName.substringAfterLast('.', "").lowercase()
				val priority = when (ext) {
					"lrc" -> 0
					"txt" -> 1
					else -> -1
				}
				if (priority < 0) continue
				if (child.sizeBytes > MAX_LYRICS_BYTES) continue
				val base = child.displayName.substringBeforeLast('.').trim().lowercase()
				if (base.isEmpty()) continue
				val docUri = runCatching {
					DocumentsContract.buildDocumentUriUsingTree(treeUri, child.docId)
				}.getOrNull() ?: continue
				val existing = sidecarByBaseName[base]
				if (existing == null || priority < existing.first) {
					sidecarByBaseName[base] = priority to docUri
				}
			}

			val folderPath = "SAF/${relFolder.trim('/')}"
			val folderDisplayName = relFolder.substringAfterLast('/').ifBlank { rootName }

			for (child in children) {
				if (child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
					if (child.displayName == ".thumbnails" || child.displayName == ".Trash") continue
					val nextRel = "$relFolder/${child.displayName}".trim('/')
					queue.addLast(child.docId to nextRel)
					continue
				}

				val ext = child.displayName.substringAfterLast('.', "")
					.lowercase()
					.takeIf { it.isNotBlank() }

				if (ext in SKIPPED_NON_AUDIO_EXTS) continue
				val isAudioMime = child.mimeType?.startsWith("audio/", ignoreCase = true) == true
				val isAudioExt = ext != null && ext in SUPPORTED_AUDIO_EXTS
				if (!isAudioMime && !isAudioExt) continue

				val docUri = runCatching {
					DocumentsContract.buildDocumentUriUsingTree(treeUri, child.docId)
				}.getOrNull() ?: continue
				val uriStr = docUri.toString()

				if (treeUri.authority == EXTERNAL_STORAGE_AUTHORITY && child.docId.startsWith("primary:")) {
					val primaryRel = child.docId.removePrefix("primary:").replace('\\', '/').trim('/')
					if (primaryRel.isNotEmpty()) {
						safPrimaryRelPaths.add(primaryRel.lowercase())
					}
				}

				val extracted = extractSafAudioMetadata(
					docUri = docUri,
					uriStr = uriStr,
					displayName = child.displayName,
					sizeBytes = child.sizeBytes,
					lastModifiedMs = child.lastModifiedMs
				)

				val songId = "$SAF_ID_PREFIX${uriStr.hashCode().toUInt()}"
				val baseLower = child.displayName.substringBeforeLast('.').trim().lowercase()
				val sidecarUri = sidecarByBaseName[baseLower]?.second

				val song = DomainSong(
					id = songId,
					title = extracted.title,
					artistName = extracted.artist,
					artistId = "",
					albumTitle = extracted.album,
					albumId = null,
					parentId = folderPath,
					comment = null,
					trackNumber = extracted.trackNumber,
					discNumber = null,
					isrc = emptyList(),
					year = extracted.year,
					genre = null,
					genres = emptyList(),
					moods = emptyList(),
					duration = extracted.durationMs.milliseconds,
					bpm = null,
					contributors = emptyList(),
					playCount = 0,
					userRating = null,
					averageRating = null,
					bitRate = extracted.bitRate,
					bitDepth = null,
					sampleRate = null,
					audioChannelCount = null,
					replayGain = null,
					fileSize = child.sizeBytes,
					fileExtension = ext,
					mimeType = child.mimeType,
					filePath = uriStr,
					starredAt = null,
					coverArtId = null,
					musicBrainzId = null,
					explicitStatus = DomainExplicitStatus.Unknown,
					artists = emptyList(),
					albumArtists = emptyList(),
					isExternal = true
				)

				entries.add(
					ScannedLocalEntry(
						song = song,
						folderPath = folderPath,
						folderName = folderDisplayName,
						isSaf = true,
						safTreeUri = treeUriString,
						dateAddedSec = (child.lastModifiedMs / 1000L).coerceAtLeast(0L),
						rawPath = null,
						sidecarUri = sidecarUri
					)
				)
			}
		}

		return entries to DeviceSafFolder(
			treeUri = treeUriString,
			name = rootName,
			songCount = entries.size,
			accessible = accessible
		)
	}

	private fun querySafRootDisplayName(treeUri: Uri, rootDocId: String): String {
		val rootDocUri = runCatching {
			DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
		}.getOrNull()
		if (rootDocUri != null) {
			val queried = runCatching {
				context.contentResolver.query(
					rootDocUri,
					arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
					null,
					null,
					null
				)?.use { cursor ->
					if (cursor.moveToFirst()) {
						val idx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
						if (idx >= 0) cursor.getString(idx)?.trim() else null
					} else {
						null
					}
				}
			}.getOrNull()
			if (!queried.isNullOrBlank()) return queried
		}
		return rootDocId
			.substringAfterLast(':')
			.substringAfterLast('/')
			.trim()
			.ifBlank { "自定义目录" }
	}

	private fun extractSafAudioMetadata(
		docUri: Uri,
		uriStr: String,
		displayName: String,
		sizeBytes: Long,
		lastModifiedMs: Long
	): SafExtractedMeta {
		val cacheKey = "$uriStr|$lastModifiedMs|$sizeBytes"
		safMetadataCache[cacheKey]?.let { return it }

		var metaTitle = ""
		var metaArtist = ""
		var metaAlbum = ""
		var durationMs = 0L
		var bitRateKbps: Int? = null
		var year: Int? = null
		var trackNum: Int? = null

		val retriever = MediaMetadataRetriever()
		try {
			retriever.setDataSource(context, docUri)
			metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty().trim()
			metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty().trim()
			metaAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty().trim()
			durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
				?.toLongOrNull()
				?.coerceAtLeast(0L)
				?: 0L
			bitRateKbps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
				?.toIntOrNull()
				?.takeIf { it > 0 }
				?.let { it / 1000 }
				?.takeIf { it > 0 }
			year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
				?.trim()
				?.take(4)
				?.toIntOrNull()
				?.takeIf { it in 1900..2100 }
			trackNum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
				?.substringBefore('/')
				?.trim()
				?.toIntOrNull()
				?.takeIf { it > 0 }
		} catch (_: Exception) {
			// 回退至文件名解析
		} finally {
			runCatching { retriever.release() }
		}

		val baseName = displayName.substringBeforeLast('.').trim().ifBlank { displayName }
		val cleanMetaTitle = metaTitle.takeIf { it.isNotBlank() && it != "<unknown>" }
		val cleanMetaArtist = metaArtist.takeIf { it.isNotBlank() && it != "<unknown>" }
		val cleanMetaAlbum = metaAlbum.takeIf { it.isNotBlank() && it != "<unknown>" }

		var resolvedTitle = cleanMetaTitle
		var resolvedArtist = cleanMetaArtist
		if (resolvedTitle == null) {
			val splitParts = baseName.split(" - ", limit = 2)
			if (splitParts.size == 2 && splitParts[0].isNotBlank() && splitParts[1].isNotBlank() && resolvedArtist == null) {
				resolvedArtist = splitParts[0].trim()
				resolvedTitle = splitParts[1].trim()
			} else {
				resolvedTitle = baseName.ifBlank { UNKNOWN_TITLE }
			}
		}

		val extracted = SafExtractedMeta(
			title = resolvedTitle,
			artist = resolvedArtist ?: UNKNOWN_ARTIST,
			album = cleanMetaAlbum ?: DEFAULT_ALBUM,
			durationMs = durationMs,
			bitRate = bitRateKbps,
			year = year,
			trackNumber = trackNum
		)
		safMetadataCache[cacheKey] = extracted
		return extracted
	}

	private fun queryMediaStoreAudio(
		safPrimaryRelPaths: Set<String>
	): List<ScannedLocalEntry> {
		val entries = ArrayList<ScannedLocalEntry>()

		val projection = buildList {
			add(MediaStore.Audio.Media._ID)
			add(MediaStore.Audio.Media.TITLE)
			add(MediaStore.Audio.Media.ARTIST)
			add(MediaStore.Audio.Media.ALBUM)
			add(MediaStore.Audio.Media.ALBUM_ID)
			add(MediaStore.Audio.Media.DURATION)
			add(MediaStore.Audio.Media.SIZE)
			add(MediaStore.Audio.Media.MIME_TYPE)
			add(MediaStore.Audio.Media.TRACK)
			add(MediaStore.Audio.Media.YEAR)
			add(MediaStore.Audio.Media.DATE_ADDED)
			add(MediaStore.Audio.Media.DISPLAY_NAME)
			@Suppress("DEPRECATION")
			add(MediaStore.Audio.Media.DATA)
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				add(MediaStore.Audio.Media.RELATIVE_PATH)
			}
		}.toTypedArray()

		val selection = "${MediaStore.Audio.Media.DURATION} > 0"
		val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

		context.contentResolver.query(
			MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
			projection,
			selection,
			null,
			sortOrder
		)?.use { cursor ->
			val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
			val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
			val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
			val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
			val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
			val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
			val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
			val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
			val trackCol = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)
			val yearCol = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR)
			val dateAddedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
			val displayCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
			@Suppress("DEPRECATION")
			val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
			val relPathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
			} else {
				-1
			}

			while (cursor.moveToNext()) {
				val mediaId = cursor.getLong(idCol)
				if (mediaId <= 0L) continue

				val rawData = if (dataCol >= 0) cursor.getString(dataCol).orEmpty().trim() else ""
				val relPath = if (relPathCol >= 0) cursor.getString(relPathCol).orEmpty().trim() else ""
				val displayName = if (displayCol >= 0) cursor.getString(displayCol).orEmpty().trim() else ""
				val mimeType = if (mimeCol >= 0) cursor.getString(mimeCol)?.trim() else null

				val ext = displayName.substringAfterLast('.', "")
					.ifBlank { rawData.substringAfterLast('.', "") }
					.lowercase()
					.takeIf { it.isNotBlank() }

				if (ext in SKIPPED_NON_AUDIO_EXTS) continue

				if (safPrimaryRelPaths.isNotEmpty()) {
					val candidateRel = buildPrimaryRelPath(relPath, displayName, rawData)
					if (candidateRel != null && candidateRel.lowercase() in safPrimaryRelPaths) {
						continue
					}
				}

				val rawTitle = if (titleCol >= 0) cursor.getString(titleCol).orEmpty().trim() else ""
				val title = rawTitle.takeIf { it.isNotBlank() && it != "<unknown>" }
					?: displayName.substringBeforeLast('.').ifBlank { UNKNOWN_TITLE }
				val rawArtist = if (artistCol >= 0) cursor.getString(artistCol).orEmpty().trim() else ""
				val artist = rawArtist.takeIf { it.isNotBlank() && it != "<unknown>" } ?: UNKNOWN_ARTIST
				val rawAlbum = if (albumCol >= 0) cursor.getString(albumCol).orEmpty().trim() else ""
				val album = rawAlbum.takeIf { it.isNotBlank() && it != "<unknown>" } ?: DEFAULT_ALBUM

				val folderPath = resolveFolderPath(relPath, rawData)
				val folderName = folderPath.substringAfterLast('/').ifBlank { folderPath }
				val albumId = if (albumIdCol >= 0) cursor.getLong(albumIdCol) else 0L
				val durationMs = if (durationCol >= 0) cursor.getLong(durationCol).coerceAtLeast(0L) else 0L
				val sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol).coerceAtLeast(0L) else 0L
				val rawTrack = if (trackCol >= 0) cursor.getInt(trackCol) else 0
				val trackNum = (rawTrack % 1000).takeIf { it > 0 }
				val year = if (yearCol >= 0) cursor.getInt(yearCol).takeIf { it in 1900..2100 } else null
				val dateAddedSec = if (dateAddedCol >= 0) cursor.getLong(dateAddedCol).coerceAtLeast(0L) else 0L

				val songId = "device:$mediaId"
				val contentUri = ContentUris.withAppendedId(
					MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
					mediaId
				).toString()
				val coverArtUri = if (albumId > 0L) {
					"content://media/external/audio/albumart/$albumId"
				} else {
					null
				}

				val song = DomainSong(
					id = songId,
					title = title,
					artistName = artist,
					artistId = "",
					albumTitle = album,
					albumId = if (albumId > 0L) "device_album:$albumId" else null,
					parentId = folderPath,
					comment = null,
					trackNumber = trackNum,
					discNumber = null,
					isrc = emptyList(),
					year = year,
					genre = null,
					genres = emptyList(),
					moods = emptyList(),
					duration = durationMs.milliseconds,
					bpm = null,
					contributors = emptyList(),
					playCount = 0,
					userRating = null,
					averageRating = null,
					bitRate = null,
					bitDepth = null,
					sampleRate = null,
					audioChannelCount = null,
					replayGain = null,
					fileSize = sizeBytes,
					fileExtension = ext,
					mimeType = mimeType,
					filePath = contentUri,
					starredAt = null,
					coverArtId = coverArtUri,
					musicBrainzId = null,
					explicitStatus = DomainExplicitStatus.Unknown,
					artists = emptyList(),
					albumArtists = emptyList(),
					isExternal = true
				)

				entries.add(
					ScannedLocalEntry(
						song = song,
						folderPath = folderPath,
						folderName = folderName,
						isSaf = false,
						safTreeUri = null,
						dateAddedSec = dateAddedSec,
						rawPath = rawData.takeIf { it.isNotBlank() },
						sidecarUri = null
					)
				)
			}
		}

		return entries
	}

	private fun buildPrimaryRelPath(relPath: String, displayName: String, rawData: String): String? {
		val normData = rawData.replace('\\', '/')
		when {
			normData.startsWith("/storage/emulated/0/") -> {
				val stripped = normData.removePrefix("/storage/emulated/0/").trim('/')
				if (stripped.isNotBlank()) return stripped
			}
			normData.startsWith("/sdcard/") -> {
				val stripped = normData.removePrefix("/sdcard/").trim('/')
				if (stripped.isNotBlank()) return stripped
			}
		}
		val cleanRel = relPath.replace('\\', '/').trim('/')
		val cleanName = displayName.trim('/')
		return when {
			cleanRel.isNotBlank() && cleanName.isNotBlank() -> "$cleanRel/$cleanName"
			cleanName.isNotBlank() -> cleanName
			else -> null
		}
	}

	private fun readTextFromContentUri(uri: Uri): String? {
		return runCatching {
			context.contentResolver.openInputStream(uri)?.use { input ->
				val out = ByteArrayOutputStream()
				val buf = ByteArray(8192)
				var total = 0
				while (total < MAX_LYRICS_BYTES.toInt()) {
					val read = input.read(buf, 0, minOf(buf.size, MAX_LYRICS_BYTES.toInt() - total))
					if (read <= 0) break
					out.write(buf, 0, read)
					total += read
				}
				out.toByteArray().toString(Charsets.UTF_8)
			}
		}.getOrNull()
	}

	private fun resolveFolderPath(relPath: String, rawData: String): String {
		val normalizedRel = relPath.replace('\\', '/').trim('/')
		if (normalizedRel.isNotBlank()) return normalizedRel

		val normalizedData = rawData.replace('\\', '/')
		val parent = normalizedData.substringBeforeLast('/', "")
		if (parent.isNotBlank()) {
			val stripped = parent
				.removePrefix("/storage/emulated/0/")
				.removePrefix("/sdcard/")
				.trim('/')
			if (stripped.isNotBlank()) return stripped
		}
		return "根目录"
	}

	private fun normalizeSortOrder(order: String): String {
		return when (order.trim().lowercase()) {
			SORT_ADDED -> SORT_ADDED
			SORT_ARTIST -> SORT_ARTIST
			SORT_SIZE -> SORT_SIZE
			SORT_DURATION -> SORT_DURATION
			else -> SORT_TITLE
		}
	}

	companion object {
		const val SORT_TITLE = "title"
		const val SORT_ADDED = "added"
		const val SORT_ARTIST = "artist"
		const val SORT_SIZE = "size"
		const val SORT_DURATION = "duration"

		private const val SAF_ID_PREFIX = "device:saf:"
		private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
		private const val UNKNOWN_TITLE = "未知曲目"
		private const val UNKNOWN_ARTIST = "未知歌手"
		private const val DEFAULT_ALBUM = "手机本地"
		private const val MAX_LYRICS_BYTES = 512_000L

		private val SKIPPED_NON_AUDIO_EXTS = setOf(
			"lrc", "jpg", "jpeg", "png", "webp", "txt", "nfo", "cue", "pdf", "zip", "db", "nomedia"
		)
		private val SUPPORTED_AUDIO_EXTS = setOf(
			"mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "alac", "ape",
			"wma", "dsf", "dff", "aiff", "aif", "wv", "amr", "3gp", "mka", "webm"
		)
	}
}
