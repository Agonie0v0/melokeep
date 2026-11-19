package paige.navic.melokeep

import io.github.agonie0v0.melokeep.shared.MeloTrack
import paige.navic.domain.model.DomainExplicitStatus
import paige.navic.domain.model.DomainSong
import paige.navic.domain.model.DomainSongArtist
import kotlin.time.Duration.Companion.milliseconds

/**
 * 将 `:shared` 层的 [MeloTrack] 映射为 Navic 原生播放器及 UI 使用的 [DomainSong]。
 */
object MeloTrackMapper {
	const val ID_PREFIX = "mk:"

	fun isMeloSongId(songId: String): Boolean = songId.startsWith(ID_PREFIX)

	fun toRawTrackId(songId: String): String = songId.removePrefix(ID_PREFIX)

	fun toDomainSong(track: MeloTrack): DomainSong {
		val normalizedId = if (track.id.startsWith(ID_PREFIX)) {
			track.id
		} else {
			"$ID_PREFIX${track.id}"
		}
		val artistText = track.artist.trim().takeIf { it.isNotEmpty() }
		val albumText = track.album.trim().takeIf { it.isNotEmpty() }
			?: track.source.trim().takeIf { it.isNotEmpty() }
		val extension = extractFileExtension(track)
		val mime = extension?.let(::extensionToMimeType)
		val bitRate = track.bestBitrate?.takeIf { it > 0 }
		val bitDepth = when {
			track.qualities.any { q ->
				q.label.contains("24", ignoreCase = true) ||
					q.codec.contains("24", ignoreCase = true)
			} -> 24
			track.hasLossless -> 16
			else -> null
		}
		val artistsList = if (artistText != null) {
			listOf(
				DomainSongArtist(
					id = "${ID_PREFIX}artist:$artistText",
					name = artistText
				)
			)
		} else {
			emptyList()
		}
		val sourceComment = listOfNotNull(
			track.source.trim().takeIf { it.isNotEmpty() },
			track.sizeLabel?.trim()?.takeIf { it.isNotEmpty() }
		).joinToString(" · ").takeIf { it.isNotEmpty() }

		return DomainSong(
			id = normalizedId,
			title = track.title.trim().ifEmpty { "未知曲目" },
			artistName = artistText,
			artistId = artistsList.firstOrNull()?.id.orEmpty(),
			albumTitle = albumText,
			albumId = null,
			parentId = null,
			comment = sourceComment,
			trackNumber = track.playlistIndex,
			discNumber = null,
			isrc = emptyList(),
			year = null,
			genre = track.source.trim().takeIf { it.isNotEmpty() },
			genres = listOfNotNull(track.source.trim().takeIf { it.isNotEmpty() }),
			moods = emptyList(),
			duration = track.durationMs.milliseconds,
			bpm = null,
			contributors = emptyList(),
			playCount = 0,
			userRating = null,
			averageRating = null,
			bitRate = bitRate,
			bitDepth = bitDepth,
			sampleRate = null,
			audioChannelCount = 2,
			replayGain = null,
			fileSize = track.sizeBytes ?: 0L,
			fileExtension = extension,
			mimeType = mime,
			filePath = track.playback.url?.trim()?.takeIf { it.isNotEmpty() },
			starredAt = null,
			coverArtId = track.cover?.trim()?.takeIf { it.isNotEmpty() },
			musicBrainzId = null,
			explicitStatus = DomainExplicitStatus.Unknown,
			artists = artistsList,
			albumArtists = emptyList(),
			isExternal = true
		)
	}

	fun toDomainSongs(tracks: List<MeloTrack>): List<DomainSong> = tracks.map(::toDomainSong)

	private fun extractFileExtension(track: MeloTrack): String? {
		val rawCodec = track.codecLabel?.trim()?.lowercase().orEmpty()
		val candidates = listOf(
			rawCodec,
			track.primaryQuality?.label?.trim()?.lowercase().orEmpty(),
			track.playback.url?.substringBefore('?')?.substringAfterLast('.', "")?.lowercase().orEmpty()
		)
		for (candidate in candidates) {
			if (candidate.isEmpty()) continue
			when {
				"flac" in candidate -> return "flac"
				"alac" in candidate -> return "m4a"
				"wav" in candidate -> return "wav"
				"ape" in candidate -> return "ape"
				"dsd" in candidate || "dsf" in candidate -> return "dsf"
				"opus" in candidate -> return "opus"
				"ogg" in candidate || "vorbis" in candidate -> return "ogg"
				"aac" in candidate || "m4a" in candidate -> return "m4a"
				"mp3" in candidate || "mpeg" in candidate -> return "mp3"
				candidate.length in 2..5 && candidate.all { it.isLetterOrDigit() } -> return candidate
			}
		}
		return if (track.hasLossless) "flac" else "mp3"
	}

	private fun extensionToMimeType(ext: String): String = when (ext.lowercase()) {
		"flac" -> "audio/flac"
		"wav" -> "audio/wav"
		"m4a", "aac" -> "audio/mp4"
		"ogg" -> "audio/ogg"
		"opus" -> "audio/opus"
		"ape" -> "audio/x-ape"
		"dsf" -> "audio/x-dsf"
		else -> "audio/mpeg"
	}
}
