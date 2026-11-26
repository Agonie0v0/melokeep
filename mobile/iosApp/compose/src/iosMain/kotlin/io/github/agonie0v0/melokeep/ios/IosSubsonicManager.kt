@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.agonie0v0.melokeep.ios

import com.russhwolf.settings.Settings
import dev.zt64.subsonic.api.model.Album
import dev.zt64.subsonic.api.model.AlbumListType
import dev.zt64.subsonic.api.model.Artist
import dev.zt64.subsonic.api.model.Playlist
import dev.zt64.subsonic.api.model.Song
import dev.zt64.subsonic.client.SubsonicAuth
import dev.zt64.subsonic.client.SubsonicClient
import io.github.agonie0v0.melokeep.shared.MeloKeepApiClient
import io.github.agonie0v0.melokeep.shared.MeloListeningHistoryRequest
import io.github.agonie0v0.melokeep.shared.MeloTrack
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import kotlin.math.roundToLong
import kotlin.random.Random

@Serializable
enum class IosTrackSource {
	NAVIDROME,
	MELOKEEP,
	DEVICE,
}

@Serializable
data class IosTrackItem(
	val id: String,
	val rawId: String,
	val sourceType: IosTrackSource,
	val title: String,
	val artist: String,
	val album: String,
	val albumId: String? = null,
	val artistId: String? = null,
	val durationSec: Double = 0.0,
	val streamUrl: String? = null,
	val coverUrl: String? = null,
	val coverArtId: String? = null,
	val bitRate: Int? = null,
	val bitDepth: Int? = null,
	val sampleRate: Int? = null,
	val fileExtension: String? = null,
	val trackGainDb: Float? = null,
	val albumGainDb: Float? = null,
	val fallbackGainDb: Float? = null,
	val isStarred: Boolean = false,
	val serverId: String? = null,
	val qualityLabel: String? = null,
	val lossless: Boolean = false,
	val year: Int? = null,
	val genre: String? = null,
	val playCount: Int = 0,
	val userRating: Int? = null,
	val trackNumber: Int? = null,
	val sizeBytes: Long = 0L,
) {
	val durationMs: Long
		get() = (durationSec * 1000.0).roundToLong().coerceAtLeast(0L)

	val displayFormatBadge: String
		get() {
			val ext = fileExtension?.uppercase()?.takeIf { it.isNotBlank() }
				?: if (lossless) "FLAC" else "AUDIO"
			val parts = buildList {
				add(ext)
				if ((bitDepth ?: 0) > 16) add("${bitDepth}bit")
				sampleRate?.takeIf { it > 0 }?.let { sr ->
					val khz = sr / 1000.0
					add(if (sr % 1000 == 0) "${sr / 1000} kHz" else "${((khz * 10.0).roundToLong() / 10.0)} kHz")
				}
				bitRate?.takeIf { it > 0 }?.let { add("${it} kbps") }
			}
			return parts.joinToString(" • ")
		}
}

data class IosLyricLine(
	val timeMs: Long?,
	val text: String,
)

data class IosLyricsResult(
	val lines: List<IosLyricLine>,
	val synced: Boolean,
	val provider: String = "",
)

@Serializable
data class NavidromeServerProfile(
	val id: String,
	val name: String,
	val url: String,
	val username: String,
	val password: String,
)

@Serializable
enum class ReplayGainMode {
	TRACK,
	ALBUM,
	OFF,
}

@Serializable
enum class IosThemeMode {
	SYSTEM,
	DARK,
	LIGHT,
}

@Serializable
data class IosPlaybackPrefs(
	val maxBitRate: Int = 0,
	val transcodeFormat: String = "raw",
	val replayGainMode: ReplayGainMode = ReplayGainMode.OFF,
	val replayGainPreampDb: Float = 6.0f,
	val replayGainDefaultMigrated: Boolean = false,
	val lyricsOffsetMs: Int = 0,
	val lyricsFontSizeSp: Float = 22f,
	val lyricsLineSpacingDp: Float = 16f,
	val lyricsActiveColorHex: String = "",
	val lyricsInactiveAlpha: Float = 0.48f,
	val lyricsAlign: String = "center",
	val themeMode: IosThemeMode = IosThemeMode.SYSTEM,
	val amoledBlack: Boolean = false,
	val hideBottomBarOnScroll: Boolean = true,
	val hidePlayerIfIdle: Boolean = true,
)

internal fun normalizeIosLyricsHexOrEmpty(raw: String): String {
	val clean = raw.trim().removePrefix("#").uppercase()
	if (clean.isEmpty()) return ""
	if (!clean.all { it in '0'..'9' || it in 'A'..'F' }) return ""
	return when (clean.length) {
		6, 8 -> "#$clean"
		else -> ""
	}
}

internal fun sanitizeIosPlaybackPrefs(prefs: IosPlaybackPrefs): IosPlaybackPrefs {
	val safePreamp = if (prefs.replayGainPreampDb.isNaN()) 6.0f else prefs.replayGainPreampDb.coerceIn(-6f, 12f)
	val safeFontSp = if (prefs.lyricsFontSizeSp.isNaN()) 22f else prefs.lyricsFontSizeSp.coerceIn(16f, 34f)
	val safeLineSpacingDp = if (prefs.lyricsLineSpacingDp.isNaN()) 16f else prefs.lyricsLineSpacingDp.coerceIn(4f, 36f)
	val safeAlpha = if (prefs.lyricsInactiveAlpha.isNaN()) 0.48f else prefs.lyricsInactiveAlpha.coerceIn(0.2f, 0.85f)
	val safeAlign = if (prefs.lyricsAlign.trim().lowercase() == "start") "start" else "center"
	return prefs.copy(
		replayGainPreampDb = safePreamp,
		lyricsOffsetMs = prefs.lyricsOffsetMs.coerceIn(-5000, 5000),
		lyricsFontSizeSp = safeFontSp,
		lyricsLineSpacingDp = safeLineSpacingDp,
		lyricsActiveColorHex = normalizeIosLyricsHexOrEmpty(prefs.lyricsActiveColorHex),
		lyricsInactiveAlpha = safeAlpha,
		lyricsAlign = safeAlign,
	)
}

data class IosAlbumSummary(
	val id: String,
	val name: String,
	val artist: String,
	val artistId: String?,
	val year: Int?,
	val coverArtId: String?,
	val coverUrl: String?,
	val songCount: Int,
	val durationSec: Double,
	val isStarred: Boolean,
	val genre: String? = null,
	val playCount: Int = 0,
	val userRating: Int? = null,
	val createdAtIso: String = "",
)

data class IosArtistSummary(
	val id: String,
	val name: String,
	val albumCount: Int,
	val coverArtId: String?,
	val coverUrl: String?,
	val artistImageUrl: String? = null,
	val isStarred: Boolean = false,
)

data class IosArtistExtraInfo(
	val topSongs: List<IosTrackItem>,
	val similarArtists: List<IosArtistSummary>,
	val imageUrl: String? = null,
)

data class IosPlaylistSummary(
	val id: String,
	val name: String,
	val owner: String?,
	val songCount: Int,
	val durationSec: Double,
	val coverArtId: String?,
	val coverUrl: String?,
	val comment: String? = null,
)

data class IosLibraryState(
	val servers: List<NavidromeServerProfile> = emptyList(),
	val activeServerId: String? = null,
	val loadingOverview: Boolean = false,
	val recentAlbums: List<IosAlbumSummary> = emptyList(),
	val newestAlbums: List<IosAlbumSummary> = emptyList(),
	val randomAlbums: List<IosAlbumSummary> = emptyList(),
	val frequentAlbums: List<IosAlbumSummary> = emptyList(),
	val allAlbums: List<IosAlbumSummary> = emptyList(),
	val allSongs: List<IosTrackItem> = emptyList(),
	val loadingAllSongs: Boolean = false,
	val allSongsLoaded: Boolean = false,
	val artists: List<IosArtistSummary> = emptyList(),
	val playlists: List<IosPlaylistSummary> = emptyList(),
	val starredSongs: List<IosTrackItem> = emptyList(),
	val starredAlbums: List<IosAlbumSummary> = emptyList(),
	val starredArtists: List<IosArtistSummary> = emptyList(),
	val genres: List<String> = emptyList(),
	val starredIds: Set<String> = emptySet(),
	val unstarredIds: Set<String> = emptySet(),
	val dailyMixTracks: List<IosTrackItem> = emptyList(),
	val dailyMixNote: String = "",
	val dailyMixLoading: Boolean = false,
	val aiCurating: Boolean = false,
	val searchQuery: String = "",
	val searching: Boolean = false,
	val searchAlbums: List<IosAlbumSummary> = emptyList(),
	val searchArtists: List<IosArtistSummary> = emptyList(),
	val searchSongs: List<IosTrackItem> = emptyList(),
	val errorMessage: String? = null,
	val noticeMessage: String? = null,
) {
	val activeServer: NavidromeServerProfile?
		get() = servers.firstOrNull { it.id == activeServerId } ?: servers.firstOrNull()
}

class IosSubsonicManager(
	private val settings: Settings,
	val meloApiClient: MeloKeepApiClient,
	private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
	private val json = Json {
		ignoreUnknownKeys = true
		encodeDefaults = true
		coerceInputValues = true
	}

	val rawHttpClient: HttpClient = HttpClient(Darwin) {
		install(HttpTimeout) {
			connectTimeoutMillis = 15_000L
			socketTimeoutMillis = 60_000L
			requestTimeoutMillis = 60_000L
		}
	}

	private val _prefs = MutableStateFlow(readPrefs())
	val prefs: StateFlow<IosPlaybackPrefs> = _prefs.asStateFlow()

	private val _state = MutableStateFlow(loadInitialLibraryState())
	val state: StateFlow<IosLibraryState> = _state.asStateFlow()

	private var client: SubsonicClient? = null
	private var clientServerId: String? = null

	init {
		rebuildClient()
		if (_state.value.activeServer != null) {
			refreshOverview()
			loadOrGenerateDailyMix(false)
		}
	}

	private fun loadInitialLibraryState(): IosLibraryState {
		val servers = readServers()
		val activeId = settings.getString(KEY_ACTIVE_SERVER_ID, "").takeIf { id ->
			id.isNotBlank() && servers.any { it.id == id }
		} ?: servers.firstOrNull()?.id
		return IosLibraryState(
			servers = servers,
			activeServerId = activeId,
		)
	}

	private fun readServers(): List<NavidromeServerProfile> {
		val raw = settings.getString(KEY_SERVERS_JSON, "")
		if (raw.isBlank()) return emptyList()
		return runCatching { json.decodeFromString<List<NavidromeServerProfile>>(raw) }
			.getOrDefault(emptyList())
	}

	private fun persistServers(servers: List<NavidromeServerProfile>, activeId: String?) {
		runCatching { settings.putString(KEY_SERVERS_JSON, json.encodeToString(servers)) }
		settings.putString(KEY_ACTIVE_SERVER_ID, activeId.orEmpty())
	}

	private fun readPrefs(): IosPlaybackPrefs {
		val raw = settings.getString(KEY_PLAYBACK_PREFS, "")
		if (raw.isBlank()) {
			val initial = IosPlaybackPrefs(replayGainDefaultMigrated = true)
			runCatching { settings.putString(KEY_PLAYBACK_PREFS, json.encodeToString(initial)) }
			return initial
		}
		val decoded = runCatching { json.decodeFromString<IosPlaybackPrefs>(raw) }
			.getOrDefault(IosPlaybackPrefs())
		val sanitized = sanitizeIosPlaybackPrefs(decoded)
		if (!sanitized.replayGainDefaultMigrated) {
			val migratedMode = if (sanitized.replayGainMode == ReplayGainMode.TRACK) {
				ReplayGainMode.OFF
			} else {
				sanitized.replayGainMode
			}
			val migrated = sanitized.copy(
				replayGainMode = migratedMode,
				replayGainDefaultMigrated = true,
			)
			runCatching { settings.putString(KEY_PLAYBACK_PREFS, json.encodeToString(migrated)) }
			return migrated
		}
		return sanitized
	}

	fun updatePrefs(transform: (IosPlaybackPrefs) -> IosPlaybackPrefs) {
		val updated = sanitizeIosPlaybackPrefs(transform(_prefs.value)).let { next ->
			if (next.replayGainDefaultMigrated) next else next.copy(replayGainDefaultMigrated = true)
		}
		_prefs.value = updated
		runCatching { settings.putString(KEY_PLAYBACK_PREFS, json.encodeToString(updated)) }
	}

	private fun rebuildClient() {
		val server = _state.value.activeServer
		if (server == null) {
			client?.close()
			client = null
			clientServerId = null
			return
		}
		if (clientServerId == server.id && client != null) return
		client?.close()
		client = createSubsonicClient(server)
		clientServerId = server.id
	}

	private fun createSubsonicClient(server: NavidromeServerProfile): SubsonicClient {
		return SubsonicClient(
			baseUrl = normalizeUrl(server.url),
			auth = SubsonicAuth.Token(
				username = server.username.trim(),
				password = server.password,
			),
			client = "MeloKeep-iOS",
			engine = Darwin.create(),
		)
	}

	suspend fun testAndSaveServer(
		name: String,
		url: String,
		username: String,
		password: String,
		editingServerId: String? = null,
	): Result<NavidromeServerProfile> {
		val normalizedUrl = normalizeUrl(url)
		val cleanUser = username.trim()
		if (normalizedUrl.isBlank() || cleanUser.isBlank() || password.isBlank()) {
			return Result.failure(IllegalArgumentException("请完整填写服务器地址、用户名和密码"))
		}
		val id = editingServerId ?: "srv_${Random.nextInt(100000, 999999)}"
		val profile = NavidromeServerProfile(
			id = id,
			name = name.trim().ifBlank { normalizedUrl.removePrefix("https://").removePrefix("http://") },
			url = normalizedUrl,
			username = cleanUser,
			password = password,
		)
		val testClient = createSubsonicClient(profile)
		return try {
			testClient.ping()
			testClient.close()
			val currentServers = _state.value.servers
			val nextServers = if (currentServers.any { it.id == profile.id }) {
				currentServers.map { if (it.id == profile.id) profile else it }
			} else {
				currentServers + profile
			}
			persistServers(nextServers, profile.id)
			client?.close()
			client = null
			clientServerId = null
			_state.update {
				it.copy(
					servers = nextServers,
					activeServerId = profile.id,
					errorMessage = null,
					noticeMessage = "已连接并记住服务器：${profile.name}",
				)
			}
			rebuildClient()
			refreshOverview()
			Result.success(profile)
		} catch (e: CancellationException) {
			testClient.close()
			throw e
		} catch (e: Throwable) {
			testClient.close()
			val msg = e.message ?: "无法连接 Subsonic/Navidrome 服务器"
			_state.update { it.copy(errorMessage = msg) }
			Result.failure(e)
		}
	}

	fun switchServer(serverId: String) {
		val target = _state.value.servers.firstOrNull { it.id == serverId } ?: return
		persistServers(_state.value.servers, target.id)
		client?.close()
		client = null
		clientServerId = null
		_state.update {
			it.copy(
				activeServerId = target.id,
				recentAlbums = emptyList(),
				newestAlbums = emptyList(),
				randomAlbums = emptyList(),
				frequentAlbums = emptyList(),
				allAlbums = emptyList(),
				allSongs = emptyList(),
				loadingAllSongs = false,
				allSongsLoaded = false,
				artists = emptyList(),
				playlists = emptyList(),
				starredSongs = emptyList(),
				starredAlbums = emptyList(),
				starredArtists = emptyList(),
				genres = emptyList(),
				starredIds = emptySet(),
				unstarredIds = emptySet(),
				noticeMessage = "已切换至：${target.name}",
			)
		}
		rebuildClient()
		refreshOverview()
	}

	fun removeServer(serverId: String) {
		val remaining = _state.value.servers.filterNot { it.id == serverId }
		val nextActive = if (_state.value.activeServerId == serverId) {
			remaining.firstOrNull()?.id
		} else {
			_state.value.activeServerId
		}
		persistServers(remaining, nextActive)
		client?.close()
		client = null
		clientServerId = null
		_state.update {
			it.copy(
				servers = remaining,
				activeServerId = nextActive,
				recentAlbums = emptyList(),
				newestAlbums = emptyList(),
				randomAlbums = emptyList(),
				frequentAlbums = emptyList(),
				allAlbums = emptyList(),
				allSongs = emptyList(),
				loadingAllSongs = false,
				allSongsLoaded = false,
				artists = emptyList(),
				playlists = emptyList(),
				starredSongs = emptyList(),
				starredAlbums = emptyList(),
				starredArtists = emptyList(),
				genres = emptyList(),
				starredIds = emptySet(),
				unstarredIds = emptySet(),
			)
		}
		rebuildClient()
		if (nextActive != null) {
			refreshOverview()
		}
	}

	fun refreshAll() {
		refreshOverview()
	}

	fun refreshOverview() {
		if (client == null) return
		scope.launch {
			refreshOverviewSuspend()
		}
	}

	suspend fun refreshOverviewSuspend() {
		val activeClient = client ?: return
		val activeServerId = _state.value.activeServer?.id
		val prevState = _state.value
		_state.update { it.copy(loadingOverview = true, errorMessage = null) }
		try {
			val prevAlbumIds = prevState.allAlbums.map { it.id }.toSet()
			val (newestAlbums, artists) = coroutineScope {
				val recentDeferred = async { runCatching { activeClient.getAlbumsID3(AlbumListType.Recent, size = 20, offset = 0) }.getOrDefault(emptyList()) }
				val newestDeferred = async { runCatching { activeClient.getAlbumsID3(AlbumListType.Newest, size = 50, offset = 0) }.getOrDefault(emptyList()) }
				val randomDeferred = async { runCatching { activeClient.getAlbumsID3(AlbumListType.Random, size = 20, offset = 0) }.getOrDefault(emptyList()) }
				val frequentDeferred = async { runCatching { activeClient.getAlbumsID3(AlbumListType.Frequent, size = 20, offset = 0) }.getOrDefault(emptyList()) }
				val artistsDeferred = async { runCatching { activeClient.getArtists().index.flatMap { it.artists } }.getOrDefault(emptyList()) }
				val playlistsDeferred = async { runCatching { activeClient.getPlaylists() }.getOrDefault(emptyList()) }
				val starredDeferred = async { runCatching { activeClient.getStarredID3() }.getOrNull() }
				val genresDeferred = async { runCatching { activeClient.getGenres().map { it.name }.filter { it.isNotBlank() }.distinct().sorted() }.getOrDefault(emptyList()) }

				val recent = recentDeferred.await().map { it.toSummary(activeClient) }
				val newestRaw = newestDeferred.await()
				val newest = newestRaw.map { it.toSummary(activeClient) }
				val random = randomDeferred.await().map { it.toSummary(activeClient) }
				val frequent = frequentDeferred.await().map { it.toSummary(activeClient) }
				val artistsRaw = artistsDeferred.await()
				val artistSummaries = artistsRaw.map { it.toSummary(activeClient) }
				val playlists = playlistsDeferred.await().map { it.toSummary(activeClient) }
				val starred = starredDeferred.await()
				val genres = genresDeferred.await()
				val starredSongs = starred?.songs.orEmpty().map { it.toIosTrack(activeClient, activeServerId) }
				val starredAlbums = starred?.albums.orEmpty().map { it.toSummary(activeClient) }
				val starredArtists = starred?.artists.orEmpty().map { it.toSummary(activeClient) }
				val starredIdSet = buildSet {
					starredSongs.forEach { add(it.rawId) }
					starredAlbums.forEach { add(it.id) }
					starredArtists.forEach { add(it.id) }
				}

				val mergedAlbums = if (prevState.allAlbums.isEmpty()) {
					newest
				} else {
					val existingById = prevState.allAlbums.associateBy { it.id }.toMutableMap()
					(recent + newest + random + frequent).forEach { existingById[it.id] = it }
					existingById.values.toList()
				}

				_state.update {
					it.copy(
						loadingOverview = false,
						recentAlbums = recent,
						newestAlbums = newest,
						randomAlbums = random,
						frequentAlbums = frequent,
						allAlbums = mergedAlbums,
						artists = artistSummaries,
						playlists = playlists,
						starredSongs = starredSongs,
						starredAlbums = starredAlbums,
						starredArtists = starredArtists,
						genres = genres,
						starredIds = starredIdSet,
						unstarredIds = emptySet(),
					)
				}
				newestRaw to artistSummaries
			}

			val expectedAlbumCount = artists.sumOf { it.albumCount }
			val newAlbumIds = newestAlbums.filter { it.id !in prevAlbumIds }
			val needBackgroundAlbumSync = prevState.allAlbums.isEmpty() ||
				newAlbumIds.isNotEmpty() ||
				(expectedAlbumCount > 0 && kotlin.math.abs(expectedAlbumCount - prevState.allAlbums.size) > 0)

			if (needBackgroundAlbumSync) {
				scope.launch {
					if (prevState.allAlbums.isEmpty() ||
						(expectedAlbumCount > 0 && kotlin.math.abs(expectedAlbumCount - _state.value.allAlbums.size) > newestAlbums.size)
					) {
						val fullAlbums = runCatching { fetchAllAlbumsPaginated(activeClient) }.getOrNull()
						if (!fullAlbums.isNullOrEmpty()) {
							val fullSummaries = fullAlbums.map { it.toSummary(activeClient) }
							_state.update { it.copy(allAlbums = fullSummaries) }
						}
					}
					if (_state.value.allSongsLoaded && newAlbumIds.isNotEmpty()) {
						val semaphore = Semaphore(8)
						val newTracks = coroutineScope {
							newAlbumIds.take(40).map { album ->
								async {
									semaphore.withPermit {
										runCatching {
											activeClient.getAlbum(album.id).songs.map {
												it.toIosTrack(activeClient, activeServerId)
											}
										}.getOrDefault(emptyList())
									}
								}
							}.awaitAll().flatten()
						}
						if (newTracks.isNotEmpty()) {
							_state.update { cur ->
								val existingIds = cur.allSongs.mapTo(hashSetOf()) { it.id }
								val additions = newTracks.filter { existingIds.add(it.id) }
								if (additions.isEmpty()) cur else cur.copy(allSongs = additions + cur.allSongs)
							}
						}
					}
				}
			}
		} catch (e: CancellationException) {
			_state.update { it.copy(loadingOverview = false) }
			throw e
		} catch (e: Throwable) {
			_state.update {
				it.copy(
					loadingOverview = false,
					errorMessage = e.message ?: "加载 Navidrome 曲库失败",
				)
			}
		}
	}

	private suspend fun fetchAllAlbumsPaginated(
		activeClient: SubsonicClient,
		type: AlbumListType = AlbumListType.AlphabeticalByName,
	): List<Album> {
		val pageSize = 500
		var offset = 0
		val seen = hashSetOf<String>()
		val all = mutableListOf<Album>()
		while (offset < 50_000) {
			val batch = activeClient.getAlbumsID3(type, size = pageSize, offset = offset)
			if (batch.isEmpty()) break
			val fresh = batch.filter { seen.add(it.id) }
			if (fresh.isEmpty()) break
			all.addAll(fresh)
			if (batch.size < pageSize) break
			offset += pageSize
		}
		return all
	}

	fun loadAllSongs(forceRefresh: Boolean = false) {
		if (!forceRefresh && (_state.value.loadingAllSongs || (_state.value.allSongsLoaded && _state.value.allSongs.isNotEmpty()))) {
			return
		}
		scope.launch {
			fetchAllSongs(forceRefresh = forceRefresh)
		}
	}

	suspend fun fetchAllSongs(forceRefresh: Boolean = false): List<IosTrackItem> {
		val activeClient = client ?: return emptyList()
		val activeServerId = _state.value.activeServer?.id
		if (!forceRefresh && _state.value.allSongsLoaded && _state.value.allSongs.isNotEmpty()) {
			return _state.value.allSongs
		}
		_state.update { it.copy(loadingAllSongs = true) }
		return try {
			val seenIds = hashSetOf<String>()
			val collected = mutableListOf<IosTrackItem>()

			// 策略 1：分页拉满 searchID3(query = "")，Navidrome/OpenSubsonic 原生支持全库无截断分页
			val pageSize = 500
			var offset = 0
			while (offset < 200_000) {
				val batch = runCatching {
					activeClient.searchID3(
						query = "",
						artistCount = 0,
						albumCount = 0,
						songCount = pageSize,
						songOffset = offset,
					).songs
				}.getOrDefault(emptyList())
				if (batch.isEmpty()) break
				var addedInPage = 0
				for (song in batch) {
					val item = song.toIosTrack(activeClient, activeServerId)
					if (seenIds.add(item.id)) {
						collected.add(item)
						addedInPage += 1
					}
				}
				if (addedInPage == 0 || batch.size < pageSize) break
				offset += pageSize
			}

			// 策略 2：若服务器不支持空字符串 search3，则分页拉全专辑并并发拉取所有专辑内曲目
			if (collected.isEmpty()) {
				val albums = fetchAllAlbumsPaginated(activeClient)
				if (albums.isNotEmpty()) {
					val semaphore = Semaphore(12)
					val albumSongs = coroutineScope {
						albums.map { album ->
							async {
								semaphore.withPermit {
									runCatching {
										activeClient.getAlbum(album.id).songs.map {
											it.toIosTrack(activeClient, activeServerId)
										}
									}.getOrDefault(emptyList())
								}
							}
						}.awaitAll()
					}
					for (list in albumSongs) {
						for (item in list) {
							if (seenIds.add(item.id)) {
								collected.add(item)
							}
						}
					}
				}
			}

			// 补齐已收藏歌曲（防止漏项）
			for (starred in _state.value.starredSongs) {
				if (seenIds.add(starred.id)) {
					collected.add(starred)
				}
			}

			val finalList = collected.toList()
			_state.update {
				it.copy(
					allSongs = finalList,
					loadingAllSongs = false,
					allSongsLoaded = true,
				)
			}
			finalList
		} catch (e: CancellationException) {
			_state.update { it.copy(loadingAllSongs = false) }
			throw e
		} catch (e: Throwable) {
			_state.update {
				it.copy(
					loadingAllSongs = false,
					errorMessage = e.message ?: "拉取全部歌曲失败",
				)
			}
			_state.value.allSongs
		}
	}

	fun searchLibrary(query: String) {
		_state.update { it.copy(searchQuery = query) }
		val trimmed = query.trim()
		if (trimmed.isEmpty()) {
			_state.update {
				it.copy(
					searching = false,
					searchAlbums = emptyList(),
					searchArtists = emptyList(),
					searchSongs = emptyList(),
				)
			}
			return
		}
		val activeClient = client ?: return
		val activeServerId = _state.value.activeServer?.id
		scope.launch {
			_state.update { it.copy(searching = true, errorMessage = null) }
			try {
				val res = activeClient.searchID3(
					query = trimmed,
					artistCount = 20,
					albumCount = 30,
					songCount = 50,
				)
				_state.update {
					it.copy(
						searching = false,
						searchArtists = res.artists.map { a -> a.toSummary(activeClient) },
						searchAlbums = res.albums.map { a -> a.toSummary(activeClient) },
						searchSongs = res.songs.map { s -> s.toIosTrack(activeClient, activeServerId) },
					)
				}
			} catch (e: CancellationException) {
				_state.update { it.copy(searching = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						searching = false,
						errorMessage = e.message ?: "曲库搜索失败",
					)
				}
			}
		}
	}

	suspend fun fetchAlbumDetail(albumId: String): Result<Pair<IosAlbumSummary, List<IosTrackItem>>> {
		val activeClient = client ?: return Result.failure(IllegalStateException("未连接 Navidrome 服务器"))
		val activeServerId = _state.value.activeServer?.id
		return try {
			val album = activeClient.getAlbum(albumId)
			val summary = album.toSummary(activeClient)
			val tracks = album.songs.map { it.toIosTrack(activeClient, activeServerId) }
			Result.success(summary to tracks)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			Result.failure(e)
		}
	}

	suspend fun fetchArtistDetail(artistId: String): Result<Pair<IosArtistSummary, List<IosAlbumSummary>>> {
		val activeClient = client ?: return Result.failure(IllegalStateException("未连接 Navidrome 服务器"))
		return try {
			val artist = activeClient.getArtist(artistId)
			val summary = artist.toSummary(activeClient)
			val albums = artist.album.map { it.toSummary(activeClient) }
			Result.success(summary to albums)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			Result.failure(e)
		}
	}

	suspend fun fetchArtistExtraInfo(
		artistId: String,
		artistName: String,
	): IosArtistExtraInfo {
		val activeClient = client ?: return IosArtistExtraInfo(emptyList(), emptyList(), null)
		val activeServerId = _state.value.activeServer?.id
		return try {
			coroutineScope {
				val topSongsDeferred = async {
					runCatching {
						if (artistName.isBlank()) emptyList()
						else activeClient.getTopSongs(artistName, count = 20).map { it.toIosTrack(activeClient, activeServerId) }
					}.getOrDefault(emptyList())
				}
				val infoDeferred = async {
					runCatching {
						activeClient.getArtistInfoID3(artistId, maxSimilar = 12)
					}.getOrNull()
				}
				val info = infoDeferred.await()
				val infoImageUrl = info?.let {
					sanitizeIosArtistImageUrl(it.largeImageUrl)
						?: sanitizeIosArtistImageUrl(it.mediumImageUrl)
						?: sanitizeIosArtistImageUrl(it.smallImageUrl)
				}
				val sims = info?.similarArtists.orEmpty().map { it.toSummary(activeClient) }
				IosArtistExtraInfo(
					topSongs = topSongsDeferred.await(),
					similarArtists = sims,
					imageUrl = infoImageUrl,
				)
			}
		} catch (e: CancellationException) {
			throw e
		} catch (_: Throwable) {
			IosArtistExtraInfo(emptyList(), emptyList(), null)
		}
	}

	@Suppress("unused")
	suspend fun fetchArtistTopSongsAndSimilar(
		artistId: String,
		artistName: String,
	): Pair<List<IosTrackItem>, List<IosArtistSummary>> {
		val info = fetchArtistExtraInfo(artistId, artistName)
		return info.topSongs to info.similarArtists
	}

	suspend fun toggleAlbumStar(albumId: String, currentlyStarred: Boolean): Result<Boolean> {
		val activeClient = client ?: return Result.failure(IllegalStateException("未连接 Navidrome"))
		val nextStarred = !currentlyStarred
		return try {
			if (nextStarred) {
				activeClient.star(albumId)
			} else {
				activeClient.unstar(albumId)
			}
			_state.update { current ->
				val nextIds = if (nextStarred) current.starredIds + albumId else current.starredIds - albumId
				val nextUnstarred = if (nextStarred) current.unstarredIds - albumId else current.unstarredIds + albumId
				val nextStarredAlbums = if (nextStarred) {
					val target = current.allAlbums.firstOrNull { it.id == albumId }
						?: current.newestAlbums.firstOrNull { it.id == albumId }
					if (target != null && current.starredAlbums.none { it.id == albumId }) {
						listOf(target.copy(isStarred = true)) + current.starredAlbums
					} else {
						current.starredAlbums
					}
				} else {
					current.starredAlbums.filterNot { it.id == albumId }
				}
				current.copy(
					starredIds = nextIds,
					unstarredIds = nextUnstarred,
					starredAlbums = nextStarredAlbums,
					allAlbums = current.allAlbums.map { if (it.id == albumId) it.copy(isStarred = nextStarred) else it },
					noticeMessage = if (nextStarred) "已收藏专辑" else "已取消收藏专辑",
				)
			}
			Result.success(nextStarred)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			_state.update { it.copy(errorMessage = e.message ?: "专辑收藏操作失败") }
			Result.failure(e)
		}
	}

	suspend fun toggleArtistStar(artistId: String, currentlyStarred: Boolean): Result<Boolean> {
		val activeClient = client ?: return Result.failure(IllegalStateException("未连接 Navidrome"))
		val nextStarred = !currentlyStarred
		return try {
			if (nextStarred) {
				activeClient.star(artistId)
			} else {
				activeClient.unstar(artistId)
			}
			_state.update { current ->
				val nextIds = if (nextStarred) current.starredIds + artistId else current.starredIds - artistId
				val nextUnstarred = if (nextStarred) current.unstarredIds - artistId else current.unstarredIds + artistId
				val nextStarredArtists = if (nextStarred) {
					val target = current.artists.firstOrNull { it.id == artistId }
					if (target != null && current.starredArtists.none { it.id == artistId }) {
						listOf(target.copy(isStarred = true)) + current.starredArtists
					} else {
						current.starredArtists
					}
				} else {
					current.starredArtists.filterNot { it.id == artistId }
				}
				current.copy(
					starredIds = nextIds,
					unstarredIds = nextUnstarred,
					starredArtists = nextStarredArtists,
					artists = current.artists.map { if (it.id == artistId) it.copy(isStarred = nextStarred) else it },
					noticeMessage = if (nextStarred) "已关注歌手" else "已取消关注歌手",
				)
			}
			Result.success(nextStarred)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			_state.update { it.copy(errorMessage = e.message ?: "歌手关注操作失败") }
			Result.failure(e)
		}
	}

	suspend fun fetchPlaylistDetail(playlistId: String): Result<Pair<IosPlaylistSummary, List<IosTrackItem>>> {
		val activeClient = client ?: return Result.failure(IllegalStateException("未连接 Navidrome 服务器"))
		val activeServerId = _state.value.activeServer?.id
		return try {
			val playlist = activeClient.getPlaylist(playlistId)
			val summary = playlist.toSummary(activeClient)
			val tracks = playlist.songs.map { it.toIosTrack(activeClient, activeServerId) }
			Result.success(summary to tracks)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			Result.failure(e)
		}
	}

	suspend fun toggleStar(track: IosTrackItem): Result<Boolean> {
		if (track.sourceType != IosTrackSource.NAVIDROME) {
			return Result.failure(IllegalStateException("仅 Navidrome 曲目支持云端收藏"))
		}
		val activeClient = client ?: return Result.failure(IllegalStateException("未连接 Navidrome"))
		val nextStarred = !isStarred(track)
		return try {
			if (nextStarred) {
				activeClient.star(track.rawId)
			} else {
				activeClient.unstar(track.rawId)
			}
			_state.update { current ->
				val nextIds = if (nextStarred) current.starredIds + track.rawId else current.starredIds - track.rawId
				val nextUnstarred = if (nextStarred) current.unstarredIds - track.rawId else current.unstarredIds + track.rawId
				val nextSongs = if (nextStarred) {
					val updated = track.copy(isStarred = true)
					if (current.starredSongs.any { it.rawId == track.rawId }) current.starredSongs else listOf(updated) + current.starredSongs
				} else {
					current.starredSongs.filterNot { it.rawId == track.rawId }
				}
				current.copy(
					starredIds = nextIds,
					unstarredIds = nextUnstarred,
					starredSongs = nextSongs,
					noticeMessage = if (nextStarred) "已添加到我喜欢" else "已取消我喜欢",
				)
			}
			Result.success(nextStarred)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			_state.update { it.copy(errorMessage = e.message ?: "收藏操作失败") }
			Result.failure(e)
		}
	}

	suspend fun startScan(): Result<Pair<Boolean, Int>> {
		val activeClient = client ?: return Result.failure<Pair<Boolean, Int>>(
			IllegalStateException("未连接 Navidrome")
		).also {
			_state.update { it.copy(errorMessage = "未连接 Navidrome") }
		}
		return try {
			var permitted = true
			var scanCount = 0
			var isScanning = false
			try {
				val status = activeClient.startScan()
				scanCount = status.count
				isScanning = status.scanning
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				val msg = e.message.orEmpty()
				val isUnauthorized = msg.contains("not authorized", ignoreCase = true) ||
					msg.contains("unauthorized", ignoreCase = true) ||
					msg.contains("code 50", ignoreCase = true) ||
					msg.contains("403")
				if (!isUnauthorized) {
					throw e
				}
				permitted = false
			}
			if (permitted && isScanning) {
				delay(800)
			}
			refreshOverviewSuspend()
			_state.update {
				it.copy(
					errorMessage = null,
					noticeMessage = when {
						permitted && scanCount > 0 -> "已触发 Navidrome 扫库并刷新曲库（当前 $scanCount 首）"
						permitted -> "已触发 Navidrome 扫库并刷新曲库"
						else -> "已刷新获取最新 Navidrome 曲库（当前账号无服务端扫盘权限，依赖服务端自动监听）"
					},
				)
			}
			Result.success(isScanning to scanCount)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			_state.update { it.copy(errorMessage = e.message ?: "刷新 Navidrome 曲库失败") }
			Result.failure(e)
		}
	}

	suspend fun createPlaylist(name: String, songIds: List<String>): Result<IosPlaylistSummary> {
		val activeClient = client ?: return Result.failure(IllegalStateException("未连接 Navidrome"))
		val cleanName = name.trim()
		if (cleanName.isEmpty()) return Result.failure(IllegalArgumentException("歌单名称不能为空"))
		return try {
			val created = activeClient.createPlaylist(cleanName, songIds)
			val summary = created.toSummary(activeClient)
			_state.update { current ->
				current.copy(
					playlists = listOf(summary) + current.playlists.filterNot { it.id == summary.id },
					noticeMessage = "已创建歌单「${summary.name}」",
				)
			}
			Result.success(summary)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			_state.update { it.copy(errorMessage = e.message ?: "创建歌单失败") }
			Result.failure(e)
		}
	}

	suspend fun addSongsToPlaylist(playlistId: String, songIds: List<String>): Result<Unit> {
		val activeClient = client ?: return Result.failure(IllegalStateException("未连接 Navidrome"))
		if (songIds.isEmpty()) return Result.success(Unit)
		return try {
			activeClient.updatePlaylist(id = playlistId, songIdsToAdd = songIds)
			refreshPlaylistsOnly()
			_state.update { it.copy(noticeMessage = "已添加 ${songIds.size} 首歌曲到歌单") }
			Result.success(Unit)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			_state.update { it.copy(errorMessage = e.message ?: "加入歌单失败") }
			Result.failure(e)
		}
	}

	suspend fun removeSongFromPlaylist(playlistId: String, index: Int): Result<Unit> {
		if (index < 0) return Result.success(Unit)
		val activeClient = client ?: return Result.failure(IllegalStateException("未连接 Navidrome"))
		return try {
			activeClient.updatePlaylist(id = playlistId, songIndicesToRemove = listOf(index))
			refreshPlaylistsOnly()
			_state.update { it.copy(noticeMessage = "已从歌单移除歌曲") }
			Result.success(Unit)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			_state.update { it.copy(errorMessage = e.message ?: "移除歌曲失败") }
			Result.failure(e)
		}
	}

	suspend fun removeSongsFromPlaylist(playlistId: String, indices: List<Int>): Result<Unit> {
		val cleanIndices = indices.filter { it >= 0 }.distinct().sortedDescending()
		if (cleanIndices.isEmpty()) return Result.success(Unit)
		val activeClient = client ?: return Result.failure(IllegalStateException("未连接 Navidrome"))
		return try {
			activeClient.updatePlaylist(id = playlistId, songIndicesToRemove = cleanIndices)
			refreshPlaylistsOnly()
			_state.update { it.copy(noticeMessage = "已从歌单移除 ${cleanIndices.size} 首歌曲") }
			Result.success(Unit)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			_state.update { it.copy(errorMessage = e.message ?: "移除歌曲失败") }
			Result.failure(e)
		}
	}

	suspend fun renamePlaylist(playlistId: String, newName: String): Result<Unit> {
		val activeClient = client ?: return Result.failure(IllegalStateException("未连接 Navidrome"))
		val clean = newName.trim()
		if (clean.isEmpty()) return Result.failure(IllegalArgumentException("名称不能为空"))
		return try {
			activeClient.updatePlaylist(id = playlistId, name = clean)
			refreshPlaylistsOnly()
			_state.update { it.copy(noticeMessage = "歌单已重命名为「$clean」") }
			Result.success(Unit)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			_state.update { it.copy(errorMessage = e.message ?: "重命名失败") }
			Result.failure(e)
		}
	}

	suspend fun deletePlaylist(playlistId: String): Result<Unit> {
		val activeClient = client ?: return Result.failure(IllegalStateException("未连接 Navidrome"))
		return try {
			activeClient.deletePlaylist(playlistId)
			_state.update { current ->
				current.copy(
					playlists = current.playlists.filterNot { it.id == playlistId },
					noticeMessage = "歌单已删除",
				)
			}
			Result.success(Unit)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			_state.update { it.copy(errorMessage = e.message ?: "删除歌单失败") }
			Result.failure(e)
		}
	}

	private fun getTodayDateString(): String {
		val formatter = NSDateFormatter().apply {
			dateFormat = "yyyy-MM-dd"
		}
		return formatter.stringFromDate(NSDate())
	}

	fun loadOrGenerateDailyMix(forceRegenerate: Boolean = false) {
		val today = getTodayDateString()
		val tracksKey = KEY_DAILY_MIX_TRACKS_PREFIX + today
		val noteKey = KEY_DAILY_MIX_NOTE_PREFIX + today

		if (!forceRegenerate) {
			val cachedTracksJson = settings.getString(tracksKey, "")
			val cachedNote = settings.getString(noteKey, "")
			if (cachedTracksJson.isNotBlank()) {
				val cachedTracks = runCatching {
					json.decodeFromString<List<IosTrackItem>>(cachedTracksJson)
				}.getOrDefault(emptyList())
				if (cachedTracks.isNotEmpty()) {
					_state.update {
						it.copy(
							dailyMixTracks = cachedTracks,
							dailyMixNote = cachedNote,
							dailyMixLoading = false,
						)
					}
					return
				}
			}
		}

		val activeClient = client
		if (activeClient == null) {
			_state.update {
				it.copy(
					dailyMixTracks = emptyList(),
					dailyMixNote = "",
					dailyMixLoading = false,
				)
			}
			return
		}

		val activeServerId = _state.value.activeServer?.id
		scope.launch {
			_state.update { it.copy(dailyMixLoading = true) }
			try {
				val starred = _state.value.starredSongs.ifEmpty {
					runCatching {
						activeClient.getStarredID3()?.songs.orEmpty().map { it.toIosTrack(activeClient, activeServerId) }
					}.getOrDefault(emptyList())
				}
				val randoms = fetchRandomTrackPool(
					activeClient = activeClient,
					activeServerId = activeServerId,
					albumCount = 8,
					songTarget = 40,
				)

				// 混合策略：优先从我喜欢中取 10 首，从随机库中取 18 首，去重后取 25 首
				val pool = (starred.shuffled().take(10) + randoms.shuffled().take(18))
					.distinctBy { it.id }
					.shuffled()
					.take(25)

				val finalTracks = if (pool.isNotEmpty()) pool else randoms.take(25)
				val note = settings.getString(noteKey, "")

				if (finalTracks.isNotEmpty()) {
					runCatching {
						settings.putString(tracksKey, json.encodeToString(finalTracks))
					}
				}

				_state.update {
					it.copy(
						dailyMixTracks = finalTracks,
						dailyMixNote = note,
						dailyMixLoading = false,
					)
				}
			} catch (e: CancellationException) {
				_state.update { it.copy(dailyMixLoading = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						dailyMixLoading = false,
						errorMessage = "刷新推荐失败：${e.message ?: "网络异常"}",
					)
				}
			}
		}
	}

	fun triggerAiCurate() {
		val tracks = _state.value.dailyMixTracks
		if (tracks.isEmpty()) {
			_state.update { it.copy(noticeMessage = "暂无可策展的推荐曲目") }
			return
		}
		val today = getTodayDateString()
		val noteKey = KEY_DAILY_MIX_NOTE_PREFIX + today

		scope.launch {
			_state.update { it.copy(aiCurating = true) }
			try {
				val sampleTracks = tracks.take(8).joinToString("; ") { "${it.title} - ${it.artist}" }
				val prompt = "请分析以下听歌曲目，生成一句简洁凝练的音乐风格导语（不超过30字）及3个风格标签（如 #复古律动）：\n$sampleTracks"
				val created = meloApiClient.createAgentSession()
				var currentSession = meloApiClient.sendAgentMessage(sessionId = created.id, message = prompt)
				var attempts = 0
				while (currentSession.status in setOf("running", "waiting_tasks", "stopping") && attempts < 20) {
					delay(1000)
					attempts += 1
					currentSession = meloApiClient.getAgentSession(created.id)
				}
				val reply = currentSession.messages
					.lastOrNull { it.role.lowercase() != "user" && it.content.isNotBlank() }
					?.content
				if (!reply.isNullOrBlank()) {
					val clean = reply.lines().filter { it.isNotBlank() }.joinToString(" ").trim()
					settings.putString(noteKey, clean)
					_state.update {
						it.copy(
							dailyMixNote = clean,
							aiCurating = false,
							noticeMessage = "AI 策展已完成",
						)
					}
				} else {
					_state.update {
						it.copy(
							aiCurating = false,
							noticeMessage = "AI 暂未返回导语",
						)
					}
				}
			} catch (e: CancellationException) {
				_state.update { it.copy(aiCurating = false) }
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						aiCurating = false,
						errorMessage = e.message ?: "AI 策展失败",
					)
				}
			}
		}
	}

	suspend fun fetchAllSongsForOffline(): List<IosTrackItem> {
		return fetchAllSongs(forceRefresh = false)
	}

	private suspend fun fetchRandomTrackPool(
		activeClient: SubsonicClient,
		activeServerId: String?,
		albumCount: Int,
		songTarget: Int,
	): List<IosTrackItem> = coroutineScope {
		val collected = mutableListOf<IosTrackItem>()
		val randomAlbums = runCatching {
			activeClient.getAlbumsID3(AlbumListType.Random, size = albumCount, offset = 0)
		}.getOrDefault(emptyList())

		if (randomAlbums.isNotEmpty()) {
			val jobs = randomAlbums.map { album ->
				async {
					runCatching {
						activeClient.getAlbum(album.id).songs.map { it.toIosTrack(activeClient, activeServerId) }
					}.getOrDefault(emptyList())
				}
			}
			for (job in jobs) {
				collected += job.await()
			}
		}

		if (collected.size < songTarget) {
			val searchSongs = runCatching {
				activeClient.searchID3(
					query = "",
					artistCount = 0,
					albumCount = 0,
					songCount = songTarget,
				).songs.map { it.toIosTrack(activeClient, activeServerId) }
			}.getOrDefault(emptyList())
			collected += searchSongs
		}

		collected.distinctBy { it.id }.shuffled().take(songTarget)
	}

	private suspend fun refreshPlaylistsOnly() {
		val activeClient = client ?: return
		runCatching {
			val list = activeClient.getPlaylists().map { it.toSummary(activeClient) }
			_state.update { it.copy(playlists = list) }
		}
	}

	fun isStarred(track: IosTrackItem): Boolean {
		val current = _state.value
		return (track.rawId in current.starredIds) || (track.isStarred && track.rawId !in current.unstarredIds)
	}

	suspend fun resolveStreamUrl(track: IosTrackItem): String? {
		return when (track.sourceType) {
			IosTrackSource.NAVIDROME -> {
				val activeClient = client ?: return track.streamUrl
				val currentPrefs = _prefs.value
				val format = currentPrefs.transcodeFormat.takeIf { it.isNotBlank() && it != "raw" }
				activeClient.getStreamUrl(
					id = track.rawId,
					maxBitRate = currentPrefs.maxBitRate,
					format = format,
				)
			}
			IosTrackSource.MELOKEEP -> {
				runCatching { meloApiClient.refreshMediaTokenIfNeeded() }
				val cached = track.streamUrl?.takeIf { it.isNotBlank() }
				if (cached != null) {
					meloApiClient.resolveMediaUrl(cached)
				} else {
					val resolved = meloApiClient.resolveTrackPlayback(track.rawId)
					meloApiClient.resolveMediaUrl(resolved.playback.url)
				}
			}
			IosTrackSource.DEVICE -> track.streamUrl ?: track.rawId
		}
	}

	fun buildCoverUrl(coverArtIdOrUrl: String?, size: Int = 512): String? {
		if (coverArtIdOrUrl.isNullOrBlank()) return null
		if (coverArtIdOrUrl.startsWith("http://", ignoreCase = true) ||
			coverArtIdOrUrl.startsWith("https://", ignoreCase = true) ||
			coverArtIdOrUrl.startsWith("/")
		) {
			return meloApiClient.resolveMediaUrl(coverArtIdOrUrl)
		}
		return client?.getCoverArtUrl(coverArtIdOrUrl, size = size.toString(), auth = true)
	}

	suspend fun fetchLyrics(track: IosTrackItem): IosLyricsResult {
		return when (track.sourceType) {
			IosTrackSource.MELOKEEP -> {
				val raw = runCatching { meloApiClient.getTrackLyrics(track.rawId) }.getOrDefault("")
				parseRawLyrics(raw, provider = "MeloKeep")
			}
			IosTrackSource.DEVICE -> {
				val audioPath = track.rawId
				val base = audioPath.substringBeforeLast('.', "")
				val candidates = if (base.isNotBlank()) {
					listOf("$base.lrc", "$base.LRC", "$base.txt")
				} else {
					emptyList()
				}
				val fm = NSFileManager.defaultManager
				val raw = candidates.firstNotNullOfOrNull { lrcPath ->
					if (fm.fileExistsAtPath(lrcPath)) {
						NSString.stringWithContentsOfFile(
							lrcPath,
							encoding = NSUTF8StringEncoding,
							error = null,
						)
					} else {
						null
					}
				}
				if (!raw.isNullOrBlank()) {
					parseRawLyrics(raw, provider = "本地歌词")
				} else {
					IosLyricsResult(emptyList(), false)
				}
			}
			IosTrackSource.NAVIDROME -> {
				val activeClient = client ?: return IosLyricsResult(emptyList(), false)
				val structured = runCatching { activeClient.getLyrics(track.rawId) }.getOrDefault(emptyList())
				val preferred = structured.firstOrNull { it.synced && it.lines.isNotEmpty() }
					?: structured.firstOrNull { it.lines.isNotEmpty() }
				if (preferred != null) {
					val lines = preferred.lines.map { line ->
						IosLyricLine(
							timeMs = line.start?.toLong()?.plus(preferred.offset.toLong()),
							text = line.value,
						)
					}.filter { it.text.isNotBlank() }
					return IosLyricsResult(
						lines = lines,
						synced = preferred.synced && lines.any { it.timeMs != null },
						provider = "OpenSubsonic",
					)
				}
				val fallback = runCatching {
					activeClient.getLyrics(track.artist, track.title)
				}.getOrNull()
				if (fallback != null && fallback.value.isNotBlank()) {
					parseRawLyrics(fallback.value, provider = "Subsonic")
				} else {
					IosLyricsResult(emptyList(), false)
				}
			}
		}
	}

	suspend fun scrobble(
		track: IosTrackItem,
		submission: Boolean,
		playedMs: Long = 0L,
		durationMsOverride: Long = 0L,
	) {
		if (track.sourceType == IosTrackSource.NAVIDROME) {
			val activeClient = client
			if (activeClient != null) {
				runCatching {
					activeClient.scrobble(id = track.rawId, submission = submission)
				}
			}
		}
		if (submission && meloApiClient.settingsStore.currentConfig().isAuthenticated && track.title.isNotBlank()) {
			val sourceName = when (track.sourceType) {
				IosTrackSource.NAVIDROME -> "navidrome"
				IosTrackSource.MELOKEEP -> "melokeep"
				IosTrackSource.DEVICE -> "device"
			}
			val effectiveDurationMs = if (track.durationMs > 0L) {
				track.durationMs
			} else {
				durationMsOverride.coerceAtLeast(0L)
			}
			scope.launch {
				runCatching {
					meloApiClient.reportListeningHistory(
						MeloListeningHistoryRequest(
							trackId = track.rawId,
							title = track.title,
							artist = track.artist,
							album = track.album,
							genre = track.genre.orEmpty(),
							source = sourceName,
							durationMs = effectiveDurationMs,
							playedMs = playedMs.coerceAtLeast(0L),
						)
					)
				}
			}
		}
	}

	fun clearMessages() {
		_state.update { it.copy(errorMessage = null, noticeMessage = null) }
	}

	fun meloTrackToIosTrack(track: MeloTrack): IosTrackItem {
		val primary = track.primaryQuality
		val resolvedCover = meloApiClient.resolveMediaUrl(track.cover)
		val resolvedUrl = meloApiClient.resolveMediaUrl(track.playback.url)
		return IosTrackItem(
			id = "mk:${track.id}",
			rawId = track.id,
			sourceType = IosTrackSource.MELOKEEP,
			title = track.title.ifBlank { "未知歌曲" },
			artist = track.artist.ifBlank { "未知歌手" },
			album = track.album.ifBlank { track.source.uppercase() },
			durationSec = track.duration,
			streamUrl = resolvedUrl,
			coverUrl = resolvedCover,
			coverArtId = resolvedCover,
			bitRate = track.bestBitrate,
			fileExtension = track.codecLabel ?: if (track.hasLossless) "flac" else "mp3",
			qualityLabel = primary?.label,
			lossless = track.hasLossless,
		)
	}

	private fun Song.toIosTrack(activeClient: SubsonicClient, serverId: String?): IosTrackItem {
		val coverUrl = coverArtId?.let { activeClient.getCoverArtUrl(it, size = "512", auth = true) }
		val ext = fileExtension?.lowercase()
		return IosTrackItem(
			id = id,
			rawId = id,
			sourceType = IosTrackSource.NAVIDROME,
			title = title,
			artist = displayArtist ?: artistName ?: "未知歌手",
			album = albumTitle ?: "未知专辑",
			albumId = albumId,
			artistId = artistId,
			durationSec = duration?.inWholeMilliseconds?.div(1000.0) ?: 0.0,
			streamUrl = null,
			coverUrl = coverUrl,
			coverArtId = coverArtId,
			bitRate = bitRate,
			bitDepth = bitDepth,
			sampleRate = sampleRate,
			fileExtension = fileExtension,
			trackGainDb = replayGain?.trackGain,
			albumGainDb = replayGain?.albumGain,
			fallbackGainDb = replayGain?.fallbackGain ?: replayGain?.baseGain,
			isStarred = starredAt != null,
			serverId = serverId,
			lossless = ext in LOSSLESS_EXTENSIONS || (bitDepth ?: 0) > 16,
			year = year,
			genre = genre ?: genres.firstOrNull(),
			playCount = playCount,
			userRating = userRating,
			trackNumber = trackNumber,
		)
	}

	private fun Album.toSummary(activeClient: SubsonicClient): IosAlbumSummary {
		return IosAlbumSummary(
			id = id,
			name = name ?: "未知专辑",
			artist = displayArtist ?: artistName ?: "未知歌手",
			artistId = artistId,
			year = year,
			coverArtId = coverArtId,
			coverUrl = coverArtId?.let { activeClient.getCoverArtUrl(it, size = "512", auth = true) },
			songCount = songCount,
			durationSec = duration?.inWholeMilliseconds?.div(1000.0) ?: 0.0,
			isStarred = starredAt != null,
			genre = genre ?: genres.firstOrNull(),
			playCount = playCount,
			userRating = userRating,
			createdAtIso = createdAt.toString(),
		)
	}

	private fun Artist.toSummary(activeClient: SubsonicClient): IosArtistSummary {
		val cleanArtistImageUrl = sanitizeIosArtistImageUrl(artistImageUrl)
		val subsonicCoverUrl = coverArtId?.let { activeClient.getCoverArtUrl(it, size = "512", auth = true) }
		return IosArtistSummary(
			id = id,
			name = name,
			albumCount = albumCount,
			coverArtId = coverArtId,
			coverUrl = subsonicCoverUrl ?: cleanArtistImageUrl,
			artistImageUrl = cleanArtistImageUrl,
			isStarred = starredAt != null,
		)
	}

	private fun Playlist.toSummary(activeClient: SubsonicClient): IosPlaylistSummary {
		return IosPlaylistSummary(
			id = id,
			name = name,
			owner = owner,
			songCount = songCount,
			durationSec = duration.inWholeMilliseconds / 1000.0,
			coverArtId = coverArtId,
			coverUrl = coverArtId?.let { activeClient.getCoverArtUrl(it, size = "512", auth = true) },
			comment = comment,
		)
	}

	companion object {
		private const val KEY_SERVERS_JSON = "ios.navidrome_servers_v1"
		private const val KEY_ACTIVE_SERVER_ID = "ios.navidrome_active_server_v1"
		private const val KEY_PLAYBACK_PREFS = "ios.playback_prefs_v1"
		private const val KEY_DAILY_MIX_TRACKS_PREFIX = "ios.daily_mix_tracks_"
		private const val KEY_DAILY_MIX_NOTE_PREFIX = "ios.daily_mix_note_"
		private val LOSSLESS_EXTENSIONS = setOf("flac", "alac", "wav", "aiff", "dsf", "dff", "ape", "wv")
		private val LRC_TAG_REGEX = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

		fun normalizeUrl(raw: String): String {
			val trimmed = raw.trim().trimEnd('/')
			if (trimmed.isEmpty()) return ""
			return if ("://" in trimmed) trimmed else "https://$trimmed"
		}

		fun parseRawLyrics(rawText: String, provider: String = ""): IosLyricsResult {
			val trimmed = rawText.trim()
			if (trimmed.isEmpty()) return IosLyricsResult(emptyList(), false, provider)

			val timed = mutableListOf<IosLyricLine>()
			val plain = mutableListOf<IosLyricLine>()

			trimmed.lines().forEach { line ->
				val matches = LRC_TAG_REGEX.findAll(line).toList()
				if (matches.isNotEmpty()) {
					val textOnly = LRC_TAG_REGEX.replace(line, "").trim()
					if (textOnly.isNotEmpty()) {
						matches.forEach { m ->
							val min = m.groupValues[1].toLongOrNull() ?: 0L
							val sec = m.groupValues[2].toLongOrNull() ?: 0L
							val fracRaw = m.groupValues[3]
							val msPart = when (fracRaw.length) {
								0 -> 0L
								1 -> (fracRaw.toLongOrNull() ?: 0L) * 100L
								2 -> (fracRaw.toLongOrNull() ?: 0L) * 10L
								else -> fracRaw.take(3).toLongOrNull() ?: 0L
							}
							val timeMs = (min * 60L + sec) * 1000L + msPart
							timed.add(IosLyricLine(timeMs = timeMs, text = textOnly))
						}
					}
				} else {
					val clean = line.trim()
					if (clean.isNotEmpty() && !(clean.startsWith("[") && clean.endsWith("]"))) {
						plain.add(IosLyricLine(timeMs = null, text = clean))
					}
				}
			}

			return if (timed.isNotEmpty()) {
				IosLyricsResult(
					lines = timed.sortedBy { it.timeMs ?: 0L },
					synced = true,
					provider = provider,
				)
			} else {
				IosLyricsResult(
					lines = plain,
					synced = false,
					provider = provider,
				)
			}
		}
	}
}
