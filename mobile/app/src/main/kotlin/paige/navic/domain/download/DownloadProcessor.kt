/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.domain.download

import dev.zt64.subsonic.api.model.SubsonicException
import dev.zt64.subsonic.api.model.SubsonicResponse
import io.github.agonie0v0.melokeep.shared.MeloKeepApiClient
import io.github.agonie0v0.melokeep.shared.MeloServerConfig
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import paige.navic.database.dao.AlbumDao
import paige.navic.database.dao.DownloadDao
import paige.navic.database.dao.LyricDao
import paige.navic.database.dao.SongDao
import paige.navic.database.entity.DownloadEntity
import paige.navic.database.entity.DownloadStatus
import paige.navic.database.entity.LyricEntity
import paige.navic.database.mapper.toDomainModel
import paige.navic.database.mapper.toEntity
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.manager.runtime.StorageManager
import paige.navic.domain.manager.session.SessionManager
import paige.navic.domain.model.DomainSong
import paige.navic.domain.repository.LyricsRepository
import paige.navic.melokeep.MeloTrackMapper
import paige.navic.util.Logger
import paige.navic.util.network.NavicDnsResolver
import paige.navic.util.network.createHttpClientWithPreferences

class DownloadCancelledException : Exception("Download was cancelled")

class DownloadProcessor(
	private val downloadDao: DownloadDao,
	private val albumDao: AlbumDao,
	private val songDao: SongDao,
	private val storageManager: StorageManager,
	private val lyricsRepository: LyricsRepository,
	private val lyricDao: LyricDao,
	private val sessionManager: SessionManager,
	private val preferenceManager: PreferenceManager,
	private val meloApiClient: MeloKeepApiClient,
	dnsResolver: NavicDnsResolver
) {
	private val client = createHttpClientWithPreferences(preferenceManager, dnsResolver).apply {
		config {
			HttpResponseValidator {
				validateResponse { response ->
					when (response.status.value) {
						in 400..499 -> throw ClientRequestException(
							response,
							cachedResponseText = response.bodyAsText()
						)

						in 500..599 -> throw ServerResponseException(
							response,
							cachedResponseText = response.bodyAsText()
						)
					}

					val contentType = response.headers["content-type"]

					if (contentType == ContentType.Application.Json.contentType) {
						try {
							val subsonicResponse =
								Json.decodeFromString<SubsonicResponse<Any>>(response.bodyAsText())

							if (subsonicResponse is SubsonicResponse.Error) {
								throw SubsonicException(
									subsonicResponse.error.message,
									subsonicResponse.error.code
								)
							}
						} catch (e: Exception) {
							if (e is SubsonicException) throw e
							// probably not our business, let something else handle the exception
						}
					}
				}
			}
		}
	}

	suspend fun download(songId: String, onProgress: (suspend (Float) -> Unit)? = null) {
		requireQueued(songId)

		val song = resolveSong(songId)
		val seenCovers = mutableSetOf<String>()

		cacheCoverArt(song.coverArtId, seenCovers)
		cacheAlbumCoverArt(song.albumId, seenCovers)
		cacheLyrics(song)
		downloadAudioFile(song, onProgress)
	}

	private fun isMeloSong(song: DomainSong): Boolean =
		song.isExternal || MeloTrackMapper.isMeloSongId(song.id)

	private fun isOwnMeloServerUrl(url: String, cfg: MeloServerConfig): Boolean {
		val base = cfg.baseUrl.trimEnd('/')
		if (base.isEmpty()) return false
		return url.equals(base, ignoreCase = true) ||
			url.startsWith("$base/", ignoreCase = true)
	}

	private fun HttpRequestBuilder.applyMeloAuthIfOwnServer(url: String) {
		val cfg = meloApiClient.settingsStore.currentConfig()
		if (isOwnMeloServerUrl(url, cfg)) {
			header("X-MeloKeep-Request", "1")
			if (cfg.bearerToken.isNotBlank()) {
				header(HttpHeaders.Authorization, "Bearer ${cfg.bearerToken}")
			}
		}
	}

	private suspend fun resolveSong(songId: String): DomainSong {
		songDao.getSongById(songId)?.let { return it.toDomainModel() }

		if (MeloTrackMapper.isMeloSongId(songId)) {
			val rawId = MeloTrackMapper.toRawTrackId(songId)
			val meloTrack = meloApiClient.resolveTrackPlayback(rawId)
			val domainSong = MeloTrackMapper.toDomainSong(meloTrack)
			songDao.insertSong(domainSong.toEntity())
			return domainSong
		}

		val remote = sessionManager.api.getSong(songId).toEntity().toDomainModel()
		songDao.insertSong(remote.toEntity())
		return remote
	}

	private suspend fun requireQueued(songId: String) {
		currentCoroutineContext().ensureActive()

		if (downloadDao.getDownloadById(songId)?.status != DownloadStatus.DOWNLOADING) {
			throw DownloadCancelledException()
		}
	}

	private suspend fun cacheCoverArt(coverId: String?, seen: MutableSet<String>) {
		if (coverId == null || !seen.add(coverId)) return
		// shortcut: an existing file wins, so changing cover-art quality only affects future downloads
		if (storageManager.getCoverArtFile(coverId) != null) return

		try {
			Logger.i("DownloadProcessor", "saving cover art for $coverId")
			val isExternalCover = coverId.startsWith("http://", ignoreCase = true) ||
				coverId.startsWith("https://", ignoreCase = true) ||
				coverId.startsWith("/")

			val bytes = if (isExternalCover) {
				runCatching { meloApiClient.refreshMediaTokenIfNeeded() }
				val resolvedCoverUrl = meloApiClient.resolveMediaUrl(coverId)
					?: return
				client.get(resolvedCoverUrl) {
					applyMeloAuthIfOwnServer(resolvedCoverUrl)
				}.bodyAsBytes()
			} else {
				sessionManager.api.getCoverArt(
					coverId,
					size = preferenceManager.coverArtQuality.value.toString()
				)
			}

			if (!storageManager.saveCoverArt(coverId, bytes)) {
				Logger.w("DownloadProcessor", "could not save cover art for $coverId")
			} else {
				Logger.i("DownloadProcessor", "saved cover art for $coverId")
			}
		} catch (e: Exception) {
			if (e is CancellationException) throw e
			Logger.e("DownloadProcessor", "Failed to save cover art for $coverId", e)
		}
	}

	private suspend fun cacheAlbumCoverArt(albumId: String?, seen: MutableSet<String>) {
		if (albumId == null) return

		try {
			val albumWithSongs = albumDao.getAlbumById(albumId)
			val albumCoverId = albumWithSongs?.album?.coverArtId

			if (albumCoverId != null) {
				Logger.i(
					"DownloadProcessor",
					"Found album cover $albumCoverId for album $albumId"
				)
				cacheCoverArt(albumCoverId, seen)
			}
		} catch (e: Exception) {
			if (e is CancellationException) throw e
			Logger.e(
				"DownloadProcessor",
				"Failed to cache album cover art for album $albumId",
				e
			)
		}
	}

	private suspend fun cacheLyrics(song: DomainSong) {
		val songId = song.id
		Logger.i("DownloadProcessor", "caching lyrics for $songId")
		try {
			if (isMeloSong(song)) {
				if (lyricDao.getLyrics(songId) != null) return
				val rawLyrics = meloApiClient.getTrackLyrics(MeloTrackMapper.toRawTrackId(songId))
					.trim()
					.takeIf { it.isNotBlank() }
				if (rawLyrics != null) {
					lyricDao.insertLyrics(
						LyricEntity(
							songId = songId,
							rawContent = rawLyrics,
							providerName = "MELOKEEP"
						)
					)
					Logger.i("DownloadProcessor", "cached MeloKeep lyrics for $songId")
				}
				return
			}

			val lyricsResult = lyricsRepository.fetchLyrics(songId)
			if (lyricsResult?.rawContent != null) {
				lyricDao.insertLyrics(
					LyricEntity(
						songId = songId,
						rawContent = lyricsResult.rawContent,
						providerName = lyricsResult.providerName
					)
				)
				Logger.i("DownloadProcessor", "cached lyrics for $songId")
			}
		} catch (e: Exception) {
			if (e is CancellationException) throw e
			Logger.e("DownloadProcessor", "Failed to cache lyrics for $songId", e)
		}
	}

	private suspend fun downloadAudioFile(
		song: DomainSong,
		onProgress: (suspend (Float) -> Unit)?
	) {
		val isMeloSong = isMeloSong(song)

		val bitrate = if (preferenceManager.isAdvancedDownloadTranscodingActive) {
			preferenceManager.customDownloadMaxBitrate
		} else {
			preferenceManager.downloadQuality.bitrate
		}
		val container = if (preferenceManager.isAdvancedDownloadTranscodingActive) {
			preferenceManager.customDownloadFormat
		} else {
			preferenceManager.downloadQuality.container
		}

		val isOriginal = if (isMeloSong) true else (container.isNullOrBlank() && bitrate <= 0)
		val extension = if (isMeloSong) {
			song.fileExtension?.trim()?.takeIf { it.isNotBlank() } ?: "mp3"
		} else {
			container?.takeIf { it.isNotBlank() } ?: song.fileExtension ?: "bin"
		}
		val finalPath = storageManager.getDownloadPath(
			song.id,
			song.albumId ?: song.id,
			extension
		)
		val tempPath = storageManager.getTempDownloadPath(finalPath)

		storageManager.deleteTempFile(finalPath)

		val initialTarget = if (isMeloSong) {
			runCatching { meloApiClient.refreshMediaTokenIfNeeded() }
			val fromFilePath = song.filePath
				?.trim()
				?.takeIf { it.isNotBlank() }
				?.let { meloApiClient.resolveMediaUrl(it) }
			if (fromFilePath != null) {
				fromFilePath to true
			} else {
				val rawId = MeloTrackMapper.toRawTrackId(song.id)
				val resolved = meloApiClient.resolveTrackPlayback(rawId).playback.url
					?.let { meloApiClient.resolveMediaUrl(it) }
					?: throw IllegalStateException("MeloKeep 曲目 ${song.id} 缺少可下载音频地址")
				resolved to false
			}
		} else {
			sessionManager.api.getStreamUrl(
				id = song.id,
				maxBitRate = bitrate,
				format = container?.takeIf { it.isNotBlank() },
				// if this is true u get "stream was reset: INTERNAL_ERROR" for some reason
				estimateContentLength = false
			) to false
		}

		suspend fun executeStreamDownload(targetUrl: String): String? {
			var lastProgress = 0f
			var detectedMimeType = song.mimeType
			val request = client.prepareRequest(targetUrl) {
				method = HttpMethod.Get
				if (isMeloSong) {
					applyMeloAuthIfOwnServer(targetUrl)
				}
				onDownload { bytesSentTotal, contentLength ->
					val validLength = contentLength?.takeIf { it > 0L }
					if (validLength != null) {
						val progress = (bytesSentTotal.toDouble() / validLength.toDouble())
							.toFloat()
							.coerceIn(0f, 1f)

						if (progress - lastProgress >= 0.01f || progress == 1f) {
							lastProgress = progress
							requireQueued(song.id)
							Logger.i("DownloadProcessor", "downloading ${song.id} $progress")

							downloadDao.updateProgress(
								song.id,
								DownloadStatus.DOWNLOADING,
								progress
							)
							onProgress?.invoke(progress)
						}
					} else {
						requireQueued(song.id)
					}
				}
			}

			request.execute { response ->
				detectedMimeType = response.headers[HttpHeaders.ContentType] ?: detectedMimeType
				Logger.i("DownloadProcessor", "writing download for ${song.id}")
				storageManager.saveFile(tempPath, response.bodyAsChannel())
				Logger.i("DownloadProcessor", "wrote download for ${song.id}")
			}
			return detectedMimeType
		}

		try {
			val mimeType = try {
				executeStreamDownload(initialTarget.first)
			} catch (e: ClientRequestException) {
				if (isMeloSong && initialTarget.second && e.response.status.value in setOf(401, 403, 404, 410)) {
					storageManager.deleteTempFile(finalPath)
					runCatching { meloApiClient.refreshMediaTokenIfNeeded(force = true) }
					val rawId = MeloTrackMapper.toRawTrackId(song.id)
					val refreshedUrl = meloApiClient.resolveTrackPlayback(rawId).playback.url
						?.let { meloApiClient.resolveMediaUrl(it) }
						?: throw e
					executeStreamDownload(refreshedUrl)
				} else {
					throw e
				}
			}

			requireQueued(song.id)

			if (!storageManager.commitFile(tempPath, finalPath)) {
				throw IllegalStateException("Could not move download into place for ${song.id}")
			}

			downloadDao.insertDownload(
				DownloadEntity(
					song.id,
					DownloadStatus.DOWNLOADED,
					1f,
					finalPath,
					storageManager.getFileSize(finalPath),
					isOriginal,
					mimeType
				)
			)
		} catch (e: Exception) {
			storageManager.deleteTempFile(finalPath)
			throw e
		}
	}
}
