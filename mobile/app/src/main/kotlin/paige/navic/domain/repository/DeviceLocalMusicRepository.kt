package paige.navic.domain.repository

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import paige.navic.domain.manager.runtime.PermissionManager
import paige.navic.domain.model.DomainExplicitStatus
import paige.navic.domain.model.DomainSong
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

data class DeviceAudioFolder(
	val path: String,
	val name: String,
	val songs: List<DomainSong>,
	val coverArtId: String?
)

data class DeviceMusicState(
	val loading: Boolean = false,
	val hasPermission: Boolean = false,
	val folders: List<DeviceAudioFolder> = emptyList(),
	val allSongs: List<DomainSong> = emptyList(),
	val error: String? = null
)

class DeviceLocalMusicRepository(
	private val context: Context,
	private val permissionManager: PermissionManager
) {
	private val _state = MutableStateFlow(
		DeviceMusicState(hasPermission = permissionManager.hasAudioStoragePermission())
	)
	val state: StateFlow<DeviceMusicState> = _state.asStateFlow()

	private val songsById = ConcurrentHashMap<String, DomainSong>()
	private val rawPathById = ConcurrentHashMap<String, String>()

	fun hasPermission(): Boolean {
		val granted = permissionManager.hasAudioStoragePermission()
		if (_state.value.hasPermission != granted) {
			_state.value = _state.value.copy(hasPermission = granted)
		}
		return granted
	}

	fun getSongById(songId: String): DomainSong? = songsById[songId]

	fun readSidecarLyrics(songId: String): String? {
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
			if (candidate.exists() && candidate.isFile && candidate.canRead() && candidate.length() in 1L..512_000L) {
				val text = runCatching { candidate.readText(Charsets.UTF_8) }.getOrNull()
				if (!text.isNullOrBlank()) return text
			}
		}
		return null
	}

	suspend fun refresh() {
		val granted = permissionManager.hasAudioStoragePermission()
		if (!granted) {
			_state.value = DeviceMusicState(
				loading = false,
				hasPermission = false,
				folders = emptyList(),
				allSongs = emptyList(),
				error = null
			)
			return
		}

		_state.value = _state.value.copy(loading = true, hasPermission = true, error = null)
		runCatching {
			queryDeviceAudio()
		}.onSuccess { (songs, rawPaths, folders) ->
			songsById.clear()
			rawPathById.clear()
			songs.forEach { song ->
				songsById[song.id] = song
			}
			rawPathById.putAll(rawPaths)
			_state.value = DeviceMusicState(
				loading = false,
				hasPermission = true,
				folders = folders,
				allSongs = songs,
				error = null
			)
		}.onFailure { err ->
			_state.value = _state.value.copy(
				loading = false,
				hasPermission = true,
				error = err.message ?: "读取手机本地音乐失败"
			)
		}
	}

	private suspend fun queryDeviceAudio(): Triple<List<DomainSong>, Map<String, String>, List<DeviceAudioFolder>> =
		withContext(Dispatchers.IO) {
			val songs = mutableListOf<DomainSong>()
			val rawPaths = HashMap<String, String>()
			val folderBuckets = linkedMapOf<String, MutableList<DomainSong>>()

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

					val rawTitle = if (titleCol >= 0) cursor.getString(titleCol).orEmpty().trim() else ""
					val title = rawTitle.ifBlank {
						displayName.substringBeforeLast('.').ifBlank { "未知曲目" }
					}
					val rawArtist = if (artistCol >= 0) cursor.getString(artistCol).orEmpty().trim() else ""
					val artist = rawArtist.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "未知歌手"
					val rawAlbum = if (albumCol >= 0) cursor.getString(albumCol).orEmpty().trim() else ""
					val album = rawAlbum.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "手机本地"

					val folderPath = resolveFolderPath(relPath, rawData)
					val albumId = if (albumIdCol >= 0) cursor.getLong(albumIdCol) else 0L
					val durationMs = if (durationCol >= 0) cursor.getLong(durationCol).coerceAtLeast(0L) else 0L
					val sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol).coerceAtLeast(0L) else 0L
					val rawTrack = if (trackCol >= 0) cursor.getInt(trackCol) else 0
					val trackNum = (rawTrack % 1000).takeIf { it > 0 }
					val year = if (yearCol >= 0) cursor.getInt(yearCol).takeIf { it in 1900..2100 } else null

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
					songs += song
					if (rawData.isNotBlank()) {
						rawPaths[songId] = rawData
					}
					folderBuckets.getOrPut(folderPath) { mutableListOf() }.add(song)
				}
			}

			val folders = folderBuckets.entries
				.map { (folderPath, bucket) ->
					val sortedSongs = bucket.sortedWith(
						compareBy<DomainSong> { it.trackNumber ?: Int.MAX_VALUE }
							.thenBy { it.title.lowercase() }
					)
					val displayFolderName = folderPath.substringAfterLast('/').ifBlank { folderPath }
					DeviceAudioFolder(
						path = folderPath,
						name = displayFolderName,
						songs = sortedSongs,
						coverArtId = sortedSongs.firstNotNullOfOrNull { it.coverArtId }
					)
				}
				.sortedBy { it.path.lowercase() }

			Triple(songs, rawPaths, folders)
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

	private companion object {
		val SKIPPED_NON_AUDIO_EXTS = setOf("lrc", "jpg", "jpeg", "png", "txt", "nfo", "cue")
	}
}
