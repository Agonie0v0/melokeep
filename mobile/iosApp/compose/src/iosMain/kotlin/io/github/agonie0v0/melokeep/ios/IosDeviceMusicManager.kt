@file:OptIn(ExperimentalForeignApi::class)

package io.github.agonie0v0.melokeep.ios

import com.russhwolf.settings.Settings
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.AVFoundation.*
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeAudio
import platform.UniformTypeIdentifiers.UTTypeFolder
import platform.darwin.NSObject

internal object IosDeviceSortOrder {
	const val TITLE = "title"
	const val ADDED = "added"
	const val ARTIST = "artist"
	const val SIZE = "size"
	const val DURATION = "duration"

	val all = listOf(TITLE, ADDED, ARTIST, SIZE, DURATION)

	fun label(order: String): String = when (order) {
		ADDED -> "添加时间"
		ARTIST -> "歌手"
		SIZE -> "文件大小"
		DURATION -> "时长"
		else -> "歌名"
	}
}

internal data class IosDeviceFolderGroup(
	val path: String,
	val name: String,
	val tracks: List<IosTrackItem>,
	val excluded: Boolean = false,
	val rawTrackCount: Int = tracks.size,
)

internal data class IosDeviceArtistGroup(
	val name: String,
	val tracks: List<IosTrackItem>,
	val albumCount: Int,
)

internal data class IosDeviceAlbumGroup(
	val name: String,
	val artist: String,
	val tracks: List<IosTrackItem>,
)

internal data class IosDeviceMusicState(
	val scanning: Boolean = false,
	val folders: List<IosDeviceFolderGroup> = emptyList(),
	val allFolders: List<IosDeviceFolderGroup> = emptyList(),
	val visibleFolders: List<IosDeviceFolderGroup> = emptyList(),
	val allTracks: List<IosTrackItem> = emptyList(),
	val artists: List<IosDeviceArtistGroup> = emptyList(),
	val albums: List<IosDeviceAlbumGroup> = emptyList(),
	val excludedFolders: Set<String> = emptySet(),
	val hiddenIds: Set<String> = emptySet(),
	val minDurationSec: Int = 60,
	val sortOrder: String = IosDeviceSortOrder.TITLE,
	val hiddenCount: Int = 0,
	val filteredOutCount: Int = 0,
	val totalRawCount: Int = 0,
	val addedAtById: Map<String, Long> = emptyMap(),
	val noticeMessage: String? = null,
	val errorMessage: String? = null,
)

private data class ScannedDeviceTrack(
	val track: IosTrackItem,
	val folderPath: String,
	val folderDisplayName: String,
	val addedAtEpochSec: Long,
)

private data class ExtractedAudioMeta(
	val durationSec: Double = 0.0,
	val title: String? = null,
	val artist: String? = null,
	val album: String? = null,
)

internal object IosDeviceMusicManager {
	private const val KEY_EXCLUDED_FOLDERS = "ios.device_music.excluded_folders_v1"
	private const val KEY_HIDDEN_IDS = "ios.device_music.hidden_ids_v1"
	private const val KEY_MIN_DURATION_SEC = "ios.device_music.min_duration_sec_v1"
	private const val KEY_SORT_ORDER = "ios.device_music.sort_order_v1"

	private val settings: Settings = Settings()
	private val json = Json {
		ignoreUnknownKeys = true
		encodeDefaults = true
	}

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	private val stateMutex = Mutex()
	private var scannedEntries: List<ScannedDeviceTrack> = emptyList()
	private var hasScannedOnce: Boolean = false

	private val _state = MutableStateFlow(loadInitialState())
	val state: StateFlow<IosDeviceMusicState> = _state.asStateFlow()

	// Retain active UIKit delegate while document picker is presented.
	private var activePickerDelegate: NSObject? = null

	private val audioExtensions = setOf(
		"mp3", "flac", "wav", "m4a", "aac", "ogg", "opus",
		"alac", "aiff", "aif", "ape", "wv", "dsf", "webm",
	)
	private val sidecarLyricsExtensions = setOf("lrc", "txt")

	private fun loadInitialState(): IosDeviceMusicState {
		val excluded = readStringSet(KEY_EXCLUDED_FOLDERS)
		val hidden = readStringSet(KEY_HIDDEN_IDS)
		val minDur = settings.getInt(KEY_MIN_DURATION_SEC, 60).coerceAtLeast(0)
		val rawSort = settings.getString(KEY_SORT_ORDER, IosDeviceSortOrder.TITLE)
		val sortOrder = if (rawSort in IosDeviceSortOrder.all) rawSort else IosDeviceSortOrder.TITLE
		return IosDeviceMusicState(
			excludedFolders = excluded,
			hiddenIds = hidden,
			minDurationSec = minDur,
			sortOrder = sortOrder,
		)
	}

	private fun readStringSet(key: String): Set<String> {
		val raw = settings.getString(key, "")
		if (raw.isBlank()) return emptySet()
		return runCatching { json.decodeFromString<Set<String>>(raw) }.getOrDefault(emptySet())
	}

	private fun writeStringSet(key: String, value: Set<String>) {
		if (value.isEmpty()) {
			settings.remove(key)
		} else {
			runCatching { settings.putString(key, json.encodeToString(value)) }
		}
	}

	private fun documentsRootPath(): String {
		return NSSearchPathForDirectoriesInDomains(
			NSDocumentDirectory,
			NSUserDomainMask,
			true,
		).firstOrNull() as? String ?: ""
	}

	private fun importedRootPath(): String {
		val docs = documentsRootPath()
		val dir = "$docs/本地音乐"
		NSFileManager.defaultManager.createDirectoryAtPath(
			path = dir,
			withIntermediateDirectories = true,
			attributes = null,
			error = null,
		)
		return dir
	}

	private fun findPresenterViewController(): UIViewController? {
		var current = IosImagePicker.rootHostViewController ?: return null
		while (current.presentedViewController != null) {
			current = current.presentedViewController!!
		}
		return current
	}

	fun refresh() {
		scope.launch {
			stateMutex.withLock {
				scanDocumentsLocked()
			}
		}
	}

	fun clearMessages() {
		_state.update { it.copy(noticeMessage = null, errorMessage = null) }
	}

	fun setFolderExcluded(folderPath: String, excluded: Boolean) {
		scope.launch {
			stateMutex.withLock {
				val current = _state.value.excludedFolders
				val next = if (excluded) current + folderPath else current - folderPath
				if (next == current) return@withLock
				writeStringSet(KEY_EXCLUDED_FOLDERS, next)
				rebuildStateLocked(excludedFolders = next)
			}
		}
	}

	fun setMinDurationSec(sec: Int) {
		val safeSec = sec.coerceAtLeast(0)
		scope.launch {
			stateMutex.withLock {
				if (_state.value.minDurationSec == safeSec) return@withLock
				settings.putInt(KEY_MIN_DURATION_SEC, safeSec)
				rebuildStateLocked(minDurationSec = safeSec)
			}
		}
	}

	fun setSortOrder(order: String) {
		val safeOrder = if (order in IosDeviceSortOrder.all) order else IosDeviceSortOrder.TITLE
		scope.launch {
			stateMutex.withLock {
				if (_state.value.sortOrder == safeOrder) return@withLock
				settings.putString(KEY_SORT_ORDER, safeOrder)
				rebuildStateLocked(sortOrder = safeOrder)
			}
		}
	}

	fun hideTracks(ids: Set<String>) {
		if (ids.isEmpty()) return
		scope.launch {
			stateMutex.withLock {
				val next = _state.value.hiddenIds + ids
				writeStringSet(KEY_HIDDEN_IDS, next)
				rebuildStateLocked(
					hiddenIds = next,
					notice = "已从列表隐藏 ${ids.size} 首歌曲（可在扫描与目录管理中恢复）",
				)
			}
		}
	}

	fun restoreHiddenTracks() {
		scope.launch {
			stateMutex.withLock {
				if (_state.value.hiddenIds.isEmpty()) return@withLock
				writeStringSet(KEY_HIDDEN_IDS, emptySet())
				rebuildStateLocked(
					hiddenIds = emptySet(),
					notice = "已恢复全部已隐藏曲目",
				)
			}
		}
	}

	fun deleteTrack(track: IosTrackItem) {
		deleteTracks(listOf(track))
	}

	fun deleteTracks(tracks: List<IosTrackItem>) {
		if (tracks.isEmpty()) return
		scope.launch {
			stateMutex.withLock {
				val fm = NSFileManager.defaultManager
				val deletedIds = hashSetOf<String>()
				var deletedCount = 0
				tracks.forEach { track ->
					val path = track.rawId
					val removed = if (path.isNotBlank() && fm.fileExistsAtPath(path)) {
						fm.removeItemAtPath(path, error = null)
					} else {
						true
					}
					if (removed) {
						deletedIds += track.id
						deletedCount++
					}
				}
				if (deletedIds.isNotEmpty()) {
					scannedEntries = scannedEntries.filterNot { it.track.id in deletedIds }
					val nextHidden = _state.value.hiddenIds - deletedIds
					if (nextHidden != _state.value.hiddenIds) {
						writeStringSet(KEY_HIDDEN_IDS, nextHidden)
					}
					val noticeText = if (deletedCount == 1) {
						"已删除：${tracks.first().title}"
					} else {
						"已删除 $deletedCount 首本地歌曲"
					}
					if (!hasScannedOnce) {
						scanDocumentsLocked(notice = noticeText)
					} else {
						rebuildStateLocked(
							hiddenIds = nextHidden,
							notice = noticeText,
						)
					}
				} else {
					_state.update { it.copy(errorMessage = "删除本地文件失败") }
				}
			}
		}
	}

	fun presentAudioPicker() {
		val presenter = findPresenterViewController()
			?: run {
				_state.update { it.copy(errorMessage = "无法打开系统文件选择器") }
				return
			}

		val picker = UIDocumentPickerViewController(
			forOpeningContentTypes = listOf(UTTypeAudio),
			asCopy = true,
		).apply {
			allowsMultipleSelection = true
		}

		val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
			override fun documentPicker(
				controller: UIDocumentPickerViewController,
				didPickDocumentsAtURLs: List<*>,
			) {
				activePickerDelegate = null
				val urls = didPickDocumentsAtURLs.mapNotNull { it as? NSURL }
				scope.launch {
					importPickedFiles(urls)
				}
			}

			override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
				activePickerDelegate = null
			}
		}

		activePickerDelegate = delegate
		picker.delegate = delegate
		presenter.presentViewController(picker, animated = true, completion = null)
	}

	fun presentFolderPicker() {
		val presenter = findPresenterViewController()
			?: run {
				_state.update { it.copy(errorMessage = "无法打开系统文件夹选择器") }
				return
			}

		// Folders on iOS require asCopy = false paired with security-scoped access.
		val picker = UIDocumentPickerViewController(
			forOpeningContentTypes = listOf(UTTypeFolder),
			asCopy = false,
		).apply {
			allowsMultipleSelection = false
		}

		val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
			override fun documentPicker(
				controller: UIDocumentPickerViewController,
				didPickDocumentsAtURLs: List<*>,
			) {
				activePickerDelegate = null
				val urls = didPickDocumentsAtURLs.mapNotNull { it as? NSURL }
				scope.launch {
					importPickedFolders(urls)
				}
			}

			override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
				activePickerDelegate = null
			}
		}

		activePickerDelegate = delegate
		picker.delegate = delegate
		presenter.presentViewController(picker, animated = true, completion = null)
	}

	private suspend fun importPickedFiles(urls: List<NSURL>) {
		if (urls.isEmpty()) return
		val fm = NSFileManager.defaultManager
		val targetDir = importedRootPath()
		var importedCount = 0

		urls.forEach { url ->
			val srcPath = url.path ?: return@forEach
			val fileName = srcPath.substringAfterLast('/').ifBlank { return@forEach }
			val destPath = "$targetDir/$fileName"
			if (fm.fileExistsAtPath(destPath)) {
				fm.removeItemAtPath(destPath, error = null)
			}
			if (fm.copyItemAtPath(srcPath, toPath = destPath, error = null)) {
				importedCount++
			}
		}

		stateMutex.withLock {
			scanDocumentsLocked(
				notice = if (importedCount > 0) "已导入 $importedCount 首音频到本地曲库" else "未导入有效音频文件",
			)
		}
	}

	private suspend fun importPickedFolders(urls: List<NSURL>) {
		if (urls.isEmpty()) return
		val fm = NSFileManager.defaultManager
		val targetRoot = importedRootPath()
		var importedCount = 0

		urls.forEach { folderUrl ->
			val accessed = folderUrl.startAccessingSecurityScopedResource()
			try {
				val srcRoot = folderUrl.path ?: return@forEach
				val folderName = srcRoot.trimEnd('/').substringAfterLast('/').ifBlank { "导入目录" }
				val subpaths = fm.subpathsOfDirectoryAtPath(srcRoot, error = null) ?: return@forEach
				subpaths.forEach { relAny ->
					val rel = relAny as? String ?: return@forEach
					if (rel.startsWith(".") || rel.contains("/.")) return@forEach
					val ext = rel.substringAfterLast('.', "").lowercase()
					if (ext !in audioExtensions && ext !in sidecarLyricsExtensions) return@forEach

					val srcFile = "$srcRoot/$rel"
					val attrs = fm.attributesOfItemAtPath(srcFile, error = null)
					if (attrs?.get(NSFileType) != NSFileTypeRegular) return@forEach

					val destFile = "$targetRoot/$folderName/$rel"
					val destParent = destFile.substringBeforeLast('/')
					fm.createDirectoryAtPath(
						path = destParent,
						withIntermediateDirectories = true,
						attributes = null,
						error = null,
					)
					if (fm.fileExistsAtPath(destFile)) {
						fm.removeItemAtPath(destFile, error = null)
					}
					if (fm.copyItemAtPath(srcFile, toPath = destFile, error = null)) {
						if (ext in audioExtensions) importedCount++
					}
				}
			} finally {
				if (accessed) {
					folderUrl.stopAccessingSecurityScopedResource()
				}
			}
		}

		stateMutex.withLock {
			scanDocumentsLocked(
				notice = if (importedCount > 0) "已导入文件夹内 $importedCount 首歌曲" else "所选文件夹中未发现支持的音频文件",
			)
		}
	}

	private fun scanDocumentsLocked(notice: String? = null) {
		_state.update { it.copy(scanning = true, errorMessage = null) }
		val docsPath = documentsRootPath()
		if (docsPath.isBlank()) {
			scannedEntries = emptyList()
			hasScannedOnce = true
			rebuildStateLocked(scanning = false, notice = notice)
			return
		}

		val fm = NSFileManager.defaultManager
		val subpaths = fm.subpathsOfDirectoryAtPath(docsPath, error = null).orEmpty()
		val scanned = mutableListOf<ScannedDeviceTrack>()

		subpaths.forEach { relAny ->
			val rel = (relAny as? String)?.trimStart('/') ?: return@forEach
			if (rel.isBlank()) return@forEach
			if (rel == "offline_tracks" || rel.startsWith("offline_tracks/")) return@forEach
			if (rel.split('/').any { it.startsWith(".") }) return@forEach

			val fullPath = "$docsPath/$rel"
			val attrs = fm.attributesOfItemAtPath(fullPath, error = null) ?: return@forEach
			if (attrs[NSFileType] != NSFileTypeRegular) return@forEach

			val ext = rel.substringAfterLast('.', "").lowercase()
			if (ext !in audioExtensions) return@forEach

			val sizeBytes = (attrs[NSFileSize] as? NSNumber)?.longValue ?: 0L
			val addedEpochSec = (attrs[NSFileModificationDate] as? NSDate)?.timeIntervalSince1970?.toLong() ?: 0L
			val fileName = rel.substringAfterLast('/')
			val rawBaseName = fileName.substringBeforeLast('.').ifBlank { fileName }
			val (parsedArtist, parsedTitle) = parseTitleAndArtist(rawBaseName)
			val folderPath = rel.substringBeforeLast('/', "").ifBlank { "根目录" }
			val folderDisplayName = folderPath.substringAfterLast('/').ifBlank { folderPath }
			val meta = extractAudioMetadata(fullPath)

			val track = IosTrackItem(
				id = "device:$rel",
				rawId = fullPath,
				sourceType = IosTrackSource.DEVICE,
				title = meta.title ?: parsedTitle,
				artist = meta.artist ?: parsedArtist,
				album = meta.album ?: folderDisplayName,
				durationSec = meta.durationSec,
				streamUrl = fullPath,
				fileExtension = ext,
				lossless = ext in IosLibLosslessExtensions,
				sizeBytes = sizeBytes,
			)
			scanned += ScannedDeviceTrack(
				track = track,
				folderPath = folderPath,
				folderDisplayName = folderDisplayName,
				addedAtEpochSec = addedEpochSec,
			)
		}

		scannedEntries = scanned
		hasScannedOnce = true
		rebuildStateLocked(scanning = false, notice = notice)
	}

	private fun rebuildStateLocked(
		excludedFolders: Set<String> = _state.value.excludedFolders,
		hiddenIds: Set<String> = _state.value.hiddenIds,
		minDurationSec: Int = _state.value.minDurationSec,
		sortOrder: String = _state.value.sortOrder,
		scanning: Boolean = false,
		notice: String? = null,
	) {
		val comparator = buildSortComparator(sortOrder)
		val rawFolderBuckets = linkedMapOf<String, MutableList<ScannedDeviceTrack>>()
		scannedEntries.forEach { entry ->
			rawFolderBuckets.getOrPut(entry.folderPath) { mutableListOf() }.add(entry)
		}

		val visibleEntries = scannedEntries.filter { entry ->
			val excludedByFolder = entry.folderPath in excludedFolders
			val hiddenByUser = entry.track.id in hiddenIds
			val filteredByDuration = minDurationSec > 0 &&
				entry.track.durationSec > 0.0 &&
				entry.track.durationSec < minDurationSec.toDouble()
			!excludedByFolder && !hiddenByUser && !filteredByDuration
		}.sortedWith(comparator)

		val visibleSet = visibleEntries.mapTo(HashSet(visibleEntries.size)) { it.track.id }

		val allFolders = rawFolderBuckets.entries
			.map { (path, rawList) ->
				val display = path.substringAfterLast('/').ifBlank { path }
				val isExcluded = path in excludedFolders
				val visibleInFolder = if (isExcluded) {
					emptyList()
				} else {
					rawList
						.filter { it.track.id in visibleSet }
						.sortedWith(comparator)
						.map { it.track }
				}
				IosDeviceFolderGroup(
					path = path,
					name = display,
					tracks = visibleInFolder,
					excluded = isExcluded,
					rawTrackCount = rawList.size,
				)
			}
			.sortedBy { it.path.lowercase() }

		val visibleFolders = allFolders.filter { !it.excluded && it.tracks.isNotEmpty() }
		val allTracks = visibleEntries.map { it.track }

		val artists = allTracks
			.groupBy { it.artist.trim().ifBlank { "未知歌手" } }
			.map { (artistName, list) ->
				val albumCount = list.mapTo(HashSet()) {
					it.album.trim().ifBlank { "未知专辑" }
				}.size
				IosDeviceArtistGroup(
					name = artistName,
					tracks = list,
					albumCount = albumCount,
				)
			}
			.sortedBy { it.name.lowercase() }

		val albums = allTracks
			.groupBy { it.album.trim().ifBlank { "未知专辑" } }
			.map { (albumName, list) ->
				val mainArtist = list
					.groupingBy { it.artist.trim().ifBlank { "未知歌手" } }
					.eachCount()
					.maxByOrNull { it.value }
					?.key ?: "未知歌手"
				IosDeviceAlbumGroup(
					name = albumName,
					artist = mainArtist,
					tracks = list,
				)
			}
			.sortedBy { it.name.lowercase() }

		val addedAtById = scannedEntries.associate { it.track.id to it.addedAtEpochSec }
		val activeHiddenCount = scannedEntries.count { it.track.id in hiddenIds }
		val totalRaw = scannedEntries.size
		val filteredOut = (totalRaw - allTracks.size).coerceAtLeast(0)

		_state.update { current ->
			current.copy(
				scanning = scanning,
				folders = visibleFolders,
				allFolders = allFolders,
				visibleFolders = visibleFolders,
				allTracks = allTracks,
				artists = artists,
				albums = albums,
				excludedFolders = excludedFolders,
				hiddenIds = hiddenIds,
				minDurationSec = minDurationSec,
				sortOrder = sortOrder,
				hiddenCount = activeHiddenCount,
				filteredOutCount = filteredOut,
				totalRawCount = totalRaw,
				addedAtById = addedAtById,
				noticeMessage = notice ?: current.noticeMessage,
			)
		}
	}

	private fun buildSortComparator(sortOrder: String): Comparator<ScannedDeviceTrack> = when (sortOrder) {
		IosDeviceSortOrder.ADDED -> compareByDescending<ScannedDeviceTrack> { it.addedAtEpochSec }
			.thenBy { it.track.title.lowercase() }
		IosDeviceSortOrder.ARTIST -> compareBy<ScannedDeviceTrack> { it.track.artist.lowercase() }
			.thenBy { it.track.title.lowercase() }
		IosDeviceSortOrder.SIZE -> compareByDescending<ScannedDeviceTrack> { it.track.sizeBytes }
			.thenBy { it.track.title.lowercase() }
		IosDeviceSortOrder.DURATION -> compareByDescending<ScannedDeviceTrack> { it.track.durationSec }
			.thenBy { it.track.title.lowercase() }
		else -> compareBy<ScannedDeviceTrack> { it.track.title.lowercase() }
	}

	private fun extractAudioMetadata(fullPath: String): ExtractedAudioMeta {
		return runCatching {
			val url = NSURL.fileURLWithPath(fullPath)
			val asset = AVURLAsset.URLAssetWithURL(url, options = null)
			val rawDuration = CMTimeGetSeconds(asset.duration)
			val durationSec = if (rawDuration.isFinite() && rawDuration > 0.0) rawDuration else 0.0

			var metaTitle: String? = null
			var metaArtist: String? = null
			var metaAlbum: String? = null
			asset.commonMetadata.forEach { raw ->
				val item = raw as? AVMetadataItem ?: return@forEach
				val key = item.commonKey ?: return@forEach
				val value = item.stringValue?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
				when (key) {
					AVMetadataCommonKeyTitle -> if (metaTitle == null) metaTitle = value
					AVMetadataCommonKeyArtist -> if (metaArtist == null) metaArtist = value
					AVMetadataCommonKeyAlbumName -> if (metaAlbum == null) metaAlbum = value
				}
			}
			ExtractedAudioMeta(
				durationSec = durationSec,
				title = metaTitle,
				artist = metaArtist,
				album = metaAlbum,
			)
		}.getOrDefault(ExtractedAudioMeta())
	}

	private fun parseTitleAndArtist(baseName: String): Pair<String, String> {
		val sep = baseName.indexOf(" - ")
		return if (sep in 1 until baseName.length - 3) {
			val artist = baseName.substring(0, sep).trim()
			val title = baseName.substring(sep + 3).trim()
			(artist.ifBlank { "未知歌手" }) to (title.ifBlank { baseName })
		} else {
			"未知歌手" to baseName
		}
	}
}
