@file:OptIn(ExperimentalForeignApi::class)

package io.github.agonie0v0.melokeep.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeAudio
import platform.UniformTypeIdentifiers.UTTypeFolder
import platform.darwin.NSObject

internal data class IosDeviceFolderGroup(
	val path: String,
	val name: String,
	val tracks: List<IosTrackItem>,
)

internal data class IosDeviceMusicState(
	val scanning: Boolean = false,
	val folders: List<IosDeviceFolderGroup> = emptyList(),
	val allTracks: List<IosTrackItem> = emptyList(),
	val noticeMessage: String? = null,
	val errorMessage: String? = null,
)

internal object IosDeviceMusicManager {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	private val _state = MutableStateFlow(IosDeviceMusicState())
	val state: StateFlow<IosDeviceMusicState> = _state.asStateFlow()

	// Retain active UIKit delegate while document picker is presented.
	private var activePickerDelegate: NSObject? = null

	private val audioExtensions = setOf(
		"mp3", "flac", "wav", "m4a", "aac", "ogg", "opus",
		"alac", "aiff", "aif", "ape", "wv", "dsf", "webm",
	)
	private val sidecarLyricsExtensions = setOf("lrc", "txt")

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

	fun refresh() {
		scope.launch {
			scanDocuments()
		}
	}

	fun clearMessages() {
		_state.update { it.copy(noticeMessage = null, errorMessage = null) }
	}

	fun deleteTrack(track: IosTrackItem) {
		scope.launch {
			val path = track.rawId
			if (path.isNotBlank() && NSFileManager.defaultManager.fileExistsAtPath(path)) {
				NSFileManager.defaultManager.removeItemAtPath(path, error = null)
			}
			scanDocuments(notice = "已删除：${track.title}")
		}
	}

	fun presentAudioPicker() {
		val presenter = IosImagePicker.rootHostViewController
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
		val presenter = IosImagePicker.rootHostViewController
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

		scanDocuments(
			notice = if (importedCount > 0) "已导入 $importedCount 首音频到手机本地曲库" else "未导入有效音频文件",
		)
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

		scanDocuments(
			notice = if (importedCount > 0) "已导入文件夹内 $importedCount 首歌曲" else "所选文件夹中未发现支持的音频文件",
		)
	}

	private fun scanDocuments(notice: String? = null) {
		_state.update { it.copy(scanning = true, errorMessage = null) }
		val docsPath = documentsRootPath()
		if (docsPath.isBlank()) {
			_state.update {
				it.copy(scanning = false, folders = emptyList(), allTracks = emptyList(), noticeMessage = notice)
			}
			return
		}

		val fm = NSFileManager.defaultManager
		val subpaths = fm.subpathsOfDirectoryAtPath(docsPath, error = null).orEmpty()
		val bucket = linkedMapOf<String, MutableList<IosTrackItem>>()
		val tracks = mutableListOf<IosTrackItem>()

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
			val fileName = rel.substringAfterLast('/')
			val rawBaseName = fileName.substringBeforeLast('.').ifBlank { fileName }
			val (parsedArtist, parsedTitle) = parseTitleAndArtist(rawBaseName)
			val folderPath = rel.substringBeforeLast('/', "").ifBlank { "根目录" }
			val folderDisplayName = folderPath.substringAfterLast('/').ifBlank { folderPath }

			val track = IosTrackItem(
				id = "device:$rel",
				rawId = fullPath,
				sourceType = IosTrackSource.DEVICE,
				title = parsedTitle,
				artist = parsedArtist,
				album = folderDisplayName,
				durationSec = 0.0,
				fileExtension = ext,
				sizeBytes = sizeBytes,
				lossless = ext in IosLibLosslessExtensions,
				streamUrl = fullPath,
			)
			tracks += track
			bucket.getOrPut(folderPath) { mutableListOf() }.add(track)
		}

		val sortedTracks = tracks.sortedBy { it.title.lowercase() }
		val folders = bucket.entries
			.map { (path, list) ->
				val display = path.substringAfterLast('/').ifBlank { path }
				IosDeviceFolderGroup(
					path = path,
					name = display,
					tracks = list.sortedBy { it.title.lowercase() },
				)
			}
			.sortedBy { it.path.lowercase() }

		_state.update {
			it.copy(
				scanning = false,
				folders = folders,
				allTracks = sortedTracks,
				noticeMessage = notice ?: it.noticeMessage,
			)
		}
	}

	private fun parseTitleAndArtist(baseName: String): Pair<String, String> {
		val sep = baseName.indexOf(" - ")
		return if (sep in 1 until baseName.length - 3) {
			val artist = baseName.substring(0, sep).trim()
			val title = baseName.substring(sep + 3).trim()
			(artist.ifBlank { "手机本地" }) to (title.ifBlank { baseName })
		} else {
			"手机本地" to baseName
		}
	}
}
