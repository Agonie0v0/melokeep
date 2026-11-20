package io.github.agonie0v0.melokeep.shared

import com.russhwolf.settings.Settings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class InMemorySettings : Settings {
	private val map = mutableMapOf<String, Any>()

	override val keys: Set<String> get() = map.keys.toSet()
	override val size: Int get() = map.size

	override fun clear() = map.clear()
	override fun remove(key: String) {
		map.remove(key)
	}

	override fun hasKey(key: String): Boolean = key in map

	override fun putInt(key: String, value: Int) {
		map[key] = value
	}

	override fun getInt(key: String, defaultValue: Int): Int = map[key] as? Int ?: defaultValue
	override fun getIntOrNull(key: String): Int? = map[key] as? Int

	override fun putLong(key: String, value: Long) {
		map[key] = value
	}

	override fun getLong(key: String, defaultValue: Long): Long = map[key] as? Long ?: defaultValue
	override fun getLongOrNull(key: String): Long? = map[key] as? Long

	override fun putString(key: String, value: String) {
		map[key] = value
	}

	override fun getString(key: String, defaultValue: String): String = map[key] as? String ?: defaultValue
	override fun getStringOrNull(key: String): String? = map[key] as? String

	override fun putFloat(key: String, value: Float) {
		map[key] = value
	}

	override fun getFloat(key: String, defaultValue: Float): Float = map[key] as? Float ?: defaultValue
	override fun getFloatOrNull(key: String): Float? = map[key] as? Float

	override fun putDouble(key: String, value: Double) {
		map[key] = value
	}

	override fun getDouble(key: String, defaultValue: Double): Double = map[key] as? Double ?: defaultValue
	override fun getDoubleOrNull(key: String): Double? = map[key] as? Double

	override fun putBoolean(key: String, value: Boolean) {
		map[key] = value
	}

	override fun getBoolean(key: String, defaultValue: Boolean): Boolean = map[key] as? Boolean ?: defaultValue
	override fun getBooleanOrNull(key: String): Boolean? = map[key] as? Boolean
}

class MeloKeepSharedTest {

	@Test
	fun settingsStorePersistsAndNormalizesConfig() {
		val backing = InMemorySettings()
		val store = MeloSettingsStore(backing)
		store.saveSession(
			baseUrl = "192.168.31.15:8765/",
			username = "admin",
			bearerToken = "tok-123",
			tokenId = "dev-1",
			mediaToken = "999999.abcd",
			mediaTokenExpiresAt = 999999L,
			authEnabled = true,
			enabled = true,
		)

		val reloaded = MeloSettingsStore(backing).currentConfig()
		assertEquals("http://192.168.31.15:8765", reloaded.baseUrl)
		assertEquals("admin", reloaded.username)
		assertEquals("tok-123", reloaded.bearerToken)
		assertEquals("dev-1", reloaded.tokenId)
		assertEquals("999999.abcd", reloaded.mediaToken)
		assertEquals(999999L, reloaded.mediaTokenExpiresAt)
		assertTrue(reloaded.enabled)
		assertTrue(reloaded.isAuthenticated)
	}

	@Test
	fun resolveMediaUrlHandlesRelativeProtectedThirdPartyAndIds() {
		val store = MeloSettingsStore(InMemorySettings())
		store.saveSession(
			baseUrl = "https://melo.example.com/",
			username = "alice",
			bearerToken = "b-tok",
			tokenId = "tid",
			mediaToken = "1700000000.deadbeef",
			mediaTokenExpiresAt = 1700000000L,
			authEnabled = true,
		)
		val client = MeloKeepApiClient(store, MockEngine { respond("") })

		assertNull(client.resolveMediaUrl(null))
		assertNull(client.resolveMediaUrl("   "))
		assertEquals(
			"https://melo.example.com/api/stream/trk-1?media_token=1700000000.deadbeef",
			client.resolveMediaUrl("/api/stream/trk-1"),
		)
		assertEquals(
			"https://melo.example.com/api/files/audio?path=asset%3A1&media_token=1700000000.deadbeef",
			client.resolveMediaUrl("/api/files/audio?path=asset%3A1"),
		)
		assertEquals(
			"https://melo.example.com/downloads/abc123?media_token=1700000000.deadbeef",
			client.resolveMediaUrl("/downloads/abc123"),
		)
		assertEquals(
			"https://melo.example.com/api/stream/trk-1?media_token=already",
			client.resolveMediaUrl("/api/stream/trk-1?media_token=already"),
		)
		assertEquals(
			"https://cdn.example.org/cover.jpg",
			client.resolveMediaUrl("https://cdn.example.org/cover.jpg"),
		)
		assertEquals(
			"file:///storage/emulated/0/Music/song.flac",
			client.resolveMediaUrl("file:///storage/emulated/0/Music/song.flac"),
		)
		assertEquals(
			"al-987",
			client.resolveMediaUrl("al-987"),
		)
		client.close()
	}

	@Test
	fun modelsDeserializeBackendDtoPayloadsSafely() {
		val trackJson = """
			{
			  "id": "cand-1",
			  "source": "AudiusMusicClient",
			  "source_id": "aud-185668",
			  "title": "Aurora Drift",
			  "artist": "CC Collective",
			  "album": "Open Horizons",
			  "duration": 299.5,
			  "cover": "/api/files/cover?path=asset%3A1",
			  "qualities": [
			    {
			      "id": "q-flac",
			      "label": "FLAC 无损",
			      "codec": "flac",
			      "bitrate": 980,
			      "lossless": true,
			      "availability": "ready",
			      "evidence": "api"
			    }
			  ],
			  "playback": {
			    "state": "ready",
			    "url": "/api/stream/cand-1",
			    "codec": "flac",
			    "bitrate": 980,
			    "detail": "可直接试听",
			    "preview_only": false
			  }
			}
		""".trimIndent()
		val track = meloJson.decodeFromString<MeloTrack>(trackJson)
		assertEquals("cand-1", track.id)
		assertEquals(299500L, track.durationMs)
		assertTrue(track.hasLossless)
		assertEquals(980, track.bestBitrate)

		val sessionJson = """
			{
			  "id": "sess-1",
			  "title": "导入歌单",
			  "status": "complete",
			  "detail": "已完成",
			  "plan": {
			    "goal": "识别截图",
			    "steps": [{"title": "OCR", "status": "complete"}]
			  },
			  "messages": [
			    {"role": "user", "content": "导入这首歌", "has_image": true, "created_at": 1700000000}
			  ],
			  "events": [
			    {"id": "ev-1", "turn_id": "t1", "created_at": 1700000001, "tool": "search_music", "status": "complete", "detail": "命中 3 首", "result": {"count": 3}}
			  ]
			}
		""".trimIndent()
		val session = meloJson.decodeFromString<MeloAgentSession>(sessionJson)
		assertEquals("sess-1", session.id)
		assertEquals(1, session.messages.size)
		assertTrue(session.messages.first().hasImage)
		assertNotNull(session.events.first().result)

		val configJson = """
			{
			  "available_sources": [
			    {
			      "id": "AudiusMusicClient",
			      "label": "Audius",
			      "category": "开放许可",
			      "engine_name": "AudiusMusicClient",
			      "origin": "builtin",
			      "version": "1.0.0",
			      "description": "开放许可流媒体音源",
			      "proxy_recommended": true,
			      "init_error": null,
			      "capability": {"tier": "full", "note": "", "search": true, "playlist": true, "lyrics": false, "metadata": true, "episodes": false}
			    },
			    {
			      "id": "sample-plugin",
			      "label": "Sample Plugin",
			      "category": "插件音源",
			      "engine_name": "sample-plugin",
			      "origin": "plugin",
			      "plugin_id": "sample-plugin",
			      "version": "0.2.0",
			      "description": "社区扩展插件示例",
			      "proxy_recommended": false,
			      "init_error": null,
			      "capability": {"tier": "full", "note": "", "search": true, "playlist": false, "lyrics": true, "metadata": true, "episodes": true}
			    }
			  ],
			  "sources": ["AudiusMusicClient", "FMAMusicClient", "ITunesMusicClient"],
			  "results_per_source": 20,
			  "search_timeout_seconds": 15,
			  "playback_timeout_seconds": 15,
			  "download_concurrency": 2,
			  "download_layout": "source",
			  "lyrics_mode": "both",
			  "embed_metadata": true,
			  "source_settings": {"sample-plugin": {"cookie": "[已隐藏]", "proxy": ""}}
			}
		""".trimIndent()
		val cfg = meloJson.decodeFromString<MeloEngineConfig>(configJson)
		assertEquals(listOf("AudiusMusicClient", "FMAMusicClient", "ITunesMusicClient"), cfg.sources)
		assertEquals(2, cfg.availableSources.size)
		val builtinSrc = cfg.availableSources[0]
		assertEquals("Audius", builtinSrc.label)
		assertEquals("builtin", builtinSrc.origin)
		assertEquals("1.0.0", builtinSrc.version)
		val builtinCap = assertNotNull(builtinSrc.capability)
		assertTrue(builtinCap.metadata)
		assertFalse(builtinCap.lyrics)
		val pluginSrc = cfg.availableSources[1]
		assertEquals("plugin", pluginSrc.origin)
		assertEquals("sample-plugin", pluginSrc.pluginId)
		assertEquals("0.2.0", pluginSrc.version)
		assertEquals("社区扩展插件示例", pluginSrc.description)
		val pluginCap = assertNotNull(pluginSrc.capability)
		assertTrue(pluginCap.lyrics)
		assertTrue(pluginCap.episodes)

		// Verify backward compatibility when legacy JSON omits new fields or carries unknown keys
		val legacyConfigJson = """
			{
			  "available_sources": [
			    {
			      "id": "FMAMusicClient",
			      "label": "FMA",
			      "category": "开放许可",
			      "proxy_recommended": false,
			      "capability": {"tier": "full", "note": "", "search": true, "playlist": false, "legacy_extra_flag": true}
			    }
			  ],
			  "sources": ["FMAMusicClient"],
			  "legacy_unused_field": "ignored"
			}
		""".trimIndent()
		val legacyCfg = meloJson.decodeFromString<MeloEngineConfig>(legacyConfigJson)
		assertEquals(listOf("FMAMusicClient"), legacyCfg.sources)
		val legacySrc = legacyCfg.availableSources.single()
		assertEquals("", legacySrc.origin)
		assertEquals("", legacySrc.pluginId)
		assertEquals(false, legacySrc.capability?.lyrics)
		assertEquals(false, legacySrc.capability?.metadata)
		assertEquals(false, legacySrc.capability?.episodes)
	}

	@Test
	fun sseParserHandlesAllEventTypesKeepaliveAndCrLf() = runTest {
		val ssePayload = buildString {
			append(": keepalive\r\n\r\n")
			append("id: 1\r\n")
			append("event: candidate\r\n")
			append("data: {\"id\":\"t0\",\"source\":\"FMAMusicClient\",\"source_id\":\"s0\",\"title\":\"Morning Breeze\",\"artist\":\"Free Sound Lab\",\"album\":\"Open Archive\",\"duration\":269.0,\"qualities\":[],\"playback\":{\"state\":\"pending\"}}\r\n\r\n")
			append("id: 2\n")
			append("event: track\n")
			append("data: {\"id\":\"t1\",\"source\":\"AudiusMusicClient\",\"source_id\":\"s1\",\"title\":\"Starlight Echo\",")
			append("\"artist\":\"CC Collective\",\"album\":\"Open Horizons\",\"duration\":223.0,\"qualities\":[],\"playback\":{\"state\":\"ready\",\"url\":\"/api/stream/t1\"}}\n\n")
			append("id: 3\n")
			append("event: source\n")
			append("data: {\"id\":\"AudiusMusicClient\",\"status\":{\"state\":\"complete\",\"count\":1}}\n\n")
			append("id: 4\n")
			append("event: error\n")
			append("data: {\"detail\":\"上游限流\"}\n\n")
			append("id: 5\n")
			append("event: done\n")
			append("data: {\"cancelled\":false}\n\n")
		}
		val channel = ByteReadChannel(ssePayload.encodeToByteArray())
		val events = mutableListOf<MeloSearchEvent>()
		MeloKeepApiClient.parseSseChannel(channel) { events.add(it) }

		assertEquals(5, events.size)
		val c0 = assertIs<MeloSearchEvent.Candidate>(events[0])
		assertEquals("t0", c0.track.id)
		assertEquals("1", c0.eventId)

		val t1 = assertIs<MeloSearchEvent.Track>(events[1])
		assertEquals("Starlight Echo", t1.track.title)

		val src = assertIs<MeloSearchEvent.Source>(events[2])
		assertEquals("AudiusMusicClient", src.sourceId)
		assertEquals(1, src.status.count)

		val err = assertIs<MeloSearchEvent.Error>(events[3])
		assertEquals("上游限流", err.detail)

		val done = assertIs<MeloSearchEvent.Done>(events[4])
		assertFalse(done.cancelled)
	}

	@Test
	fun apiClientLoginSearchStreamAndDeleteCleanupWorkWithMockEngine() = runTest {
		val store = MeloSettingsStore(InMemorySettings())
		val recordedRequests = mutableListOf<Pair<HttpMethod, String>>()
		var deletedJob = false

		val engine = MockEngine { request ->
			recordedRequests += request.method to request.url.encodedPath
			when {
				request.method == HttpMethod.Post && request.url.encodedPath == "/api/auth/login" -> {
					assertEquals("1", request.headers["X-MeloKeep-Request"])
					respond(
						content = """{"success":true,"auth_enabled":true,"token":"dev-tok","token_id":"tid-9","expires_at":1800000000,"media_token":"1800000000.beef","media_token_expires_at":1800000000}""",
						status = HttpStatusCode.OK,
						headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
					)
				}
				request.method == HttpMethod.Post && request.url.encodedPath == "/api/search/jobs" -> {
					assertEquals("Bearer dev-tok", request.headers[HttpHeaders.Authorization])
					respond(
						content = """{"id":"job-42"}""",
						status = HttpStatusCode.OK,
						headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
					)
				}
				request.method == HttpMethod.Get && request.url.encodedPath == "/api/search/jobs/job-42/events" -> {
					assertEquals("Bearer dev-tok", request.headers[HttpHeaders.Authorization])
					val sse = "id: 1\nevent: track\ndata: {\"id\":\"trk-1\",\"source\":\"AudiusMusicClient\",\"source_id\":\"1\",\"title\":\"Nocturne in C\",\"artist\":\"Open Piano\",\"album\":\"Public Domain Works\",\"duration\":226.0,\"cover\":\"/api/files/cover?path=a\",\"qualities\":[],\"playback\":{\"state\":\"ready\",\"url\":\"/api/stream/trk-1\"}}\n\nid: 2\nevent: done\ndata: {\"cancelled\":false}\n\n"
					respond(
						content = sse,
						status = HttpStatusCode.OK,
						headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
					)
				}
				request.method == HttpMethod.Delete && request.url.encodedPath == "/api/search/jobs/job-42" -> {
					deletedJob = true
					respond(
						content = """{"cancelled":true}""",
						status = HttpStatusCode.OK,
						headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
					)
				}
				else -> respond("{}", HttpStatusCode.NotFound)
			}
		}

		val client = MeloKeepApiClient(store, engine, nowEpochSeconds = { 1700000000L })
		val loginResp = client.login("http://127.0.0.1:8765", "admin", "secret", "Pixel 9")
		assertTrue(loginResp.success)
		assertEquals("dev-tok", store.currentConfig().bearerToken)

		val events = client.searchStream("Nocturne in C", sources = listOf("AudiusMusicClient")).toList()
		assertEquals(2, events.size)
		val trackEv = assertIs<MeloSearchEvent.Track>(events[0])
		assertEquals(
			"http://127.0.0.1:8765/api/stream/trk-1?media_token=1800000000.beef",
			trackEv.track.playback.url,
		)
		assertEquals(
			"http://127.0.0.1:8765/api/files/cover?path=a&media_token=1800000000.beef",
			trackEv.track.cover,
		)
		assertTrue(deletedJob, "searchStream should clean up search job with DELETE on completion")

		// Verify cancellation cleanup with flow.take(1)
		deletedJob = false
		val partialEvents = client.searchStream("Nocturne in C", sources = listOf("AudiusMusicClient")).take(1).toList()
		assertEquals(1, partialEvents.size)
		assertTrue(deletedJob, "searchStream should clean up search job with DELETE on cancellation")
		client.close()
	}

	@Test
	fun searchControllerFiltersAndManagesMultiSelectState() = runTest {
		val store = MeloSettingsStore(InMemorySettings())
		store.saveSession(
			baseUrl = "http://127.0.0.1:8765",
			username = "",
			bearerToken = "",
			tokenId = "",
			mediaToken = "",
			mediaTokenExpiresAt = 0L,
			authEnabled = false,
		)
		val engine = MockEngine { request ->
			when {
				request.method == HttpMethod.Post && request.url.encodedPath == "/api/search/jobs" -> {
					respond(
						content = """{"id":"job-ctrl"}""",
						status = HttpStatusCode.OK,
						headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
					)
				}
				request.method == HttpMethod.Get && request.url.encodedPath == "/api/search/jobs/job-ctrl/events" -> {
					val sse = buildString {
						append("event: track\n")
						append("data: {\"id\":\"t-lossless\",\"source\":\"AudiusMusicClient\",\"source_id\":\"1\",\"title\":\"无损曲\",\"artist\":\"A\",\"album\":\"B\",\"duration\":200.0,\"qualities\":[{\"id\":\"q1\",\"label\":\"FLAC\",\"codec\":\"flac\",\"bitrate\":990,\"lossless\":true,\"availability\":\"ready\",\"evidence\":\"api\"}],\"playback\":{\"state\":\"ready\",\"url\":\"/api/stream/t-lossless\"}}\n\n")
						append("event: track\n")
						append("data: {\"id\":\"t-mp3\",\"source\":\"ITunesMusicClient\",\"source_id\":\"2\",\"title\":\"标准曲\",\"artist\":\"A\",\"album\":\"B\",\"duration\":180.0,\"qualities\":[{\"id\":\"q2\",\"label\":\"128k\",\"codec\":\"mp3\",\"bitrate\":128,\"lossless\":false,\"availability\":\"ready\",\"evidence\":\"api\"}],\"playback\":{\"state\":\"ready\",\"url\":\"/api/stream/t-mp3\"}}\n\n")
						append("event: done\n")
						append("data: {\"cancelled\":false}\n\n")
					}
					respond(
						content = sse,
						status = HttpStatusCode.OK,
						headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
					)
				}
				else -> respond("{}", HttpStatusCode.OK)
			}
		}

		val client = MeloKeepApiClient(store, engine)
		val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		val controller = MeloSearchController(client, controllerScope)
		controller.search(keyword = "测试", sources = listOf("AudiusMusicClient", "ITunesMusicClient"))
		val settled = withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { !it.searching && it.tracks.size == 2 }
			}
		}

		assertFalse(settled.searching)
		assertEquals(2, settled.tracks.size)
		controller.setQualityFilter(MeloQualityFilter.LOSSLESS)
		assertEquals(listOf("t-lossless"), controller.state.value.filteredTracks.map { it.id })
		controller.toggleMultiSelect(true)
		controller.selectAllFiltered()
		assertEquals(setOf("t-lossless"), controller.state.value.selectedTrackIds)
		controllerScope.cancel()
		client.close()
	}

	@Test
	fun settingsStoreStudioThemePresetAndOnboardingDismissedPersist() {
		val backing = InMemorySettings()
		val store = MeloSettingsStore(backing)
		assertEquals("obsidian", store.studioThemePreset.value)
		assertFalse(store.onboardingDismissed.value)

		store.setStudioThemePreset("vinyl-gold")
		store.setOnboardingDismissed(true)

		assertEquals("vinyl-gold", store.studioThemePreset.value)
		assertTrue(store.onboardingDismissed.value)
		assertEquals("vinyl-gold", backing.getString("melo_studio_theme_preset", ""))
		assertTrue(backing.getBoolean("melo_onboarding_dismissed", false))

		val reloaded = MeloSettingsStore(backing)
		assertEquals("vinyl-gold", reloaded.studioThemePreset.value)
		assertTrue(reloaded.onboardingDismissed.value)

		reloaded.setStudioThemePreset("pure-light")
		assertEquals("pure-light", reloaded.studioThemePreset.value)
		reloaded.setStudioThemePreset("dynamic")
		assertEquals("dynamic", reloaded.studioThemePreset.value)
		reloaded.setStudioThemePreset("invalid-preset")
		assertEquals("obsidian", reloaded.studioThemePreset.value)
	}

	@Test
	fun groupedTracksGroupsNormalizedTitleArtistAndSortsLosslessFirst() = runTest {
		val tracks = listOf(
			MeloTrack(
				id = "t-mp3-128",
				source = "FMAMusicClient",
				title = "  Moonlight Walk ",
				artist = "Open Synth",
				qualities = listOf(MeloQuality(id = "q1", label = "128k", codec = "mp3", bitrate = 128, lossless = false)),
			),
			MeloTrack(
				id = "t-flac-990",
				source = "ITunesMusicClient",
				title = "Moonlight Walk",
				artist = " Open Synth ",
				qualities = listOf(MeloQuality(id = "q2", label = "FLAC", codec = "flac", bitrate = 990, lossless = true)),
			),
			MeloTrack(
				id = "t-other-artist",
				source = "AudiusMusicClient",
				title = "Moonlight Walk",
				artist = "CC Ensemble",
				qualities = listOf(MeloQuality(id = "q3", label = "320k", codec = "mp3", bitrate = 320, lossless = false)),
			),
			MeloTrack(
				id = "t-flac-1411",
				source = "AudiusMusicClient",
				title = "Moonlight Walk",
				artist = "Open Synth",
				qualities = listOf(MeloQuality(id = "q4", label = "Hi-Res", codec = "flac", bitrate = 1411, lossless = true)),
			),
			MeloTrack(
				id = "t-mp3-320",
				source = "sample-plugin",
				title = "Moonlight Walk",
				artist = "Open Synth",
				qualities = listOf(MeloQuality(id = "q5", label = "320k", codec = "mp3", bitrate = 320, lossless = false)),
			),
		)

		val groups = groupTracksByTitleAndArtist(tracks)
		assertEquals(2, groups.size)
		assertEquals("moonlight walk|open synth", groups[0].groupKey)
		assertEquals("t-flac-1411", groups[0].primary.id)
		assertEquals(
			listOf("t-flac-990", "t-mp3-320", "t-mp3-128"),
			groups[0].alternatives.map { it.id },
		)
		assertEquals("moonlight walk|cc ensemble", groups[1].groupKey)
		assertEquals("t-other-artist", groups[1].primary.id)
		assertTrue(groups[1].alternatives.isEmpty())

		// Verify SearchController SSE batching, quality filtering on groupedTracks & enqueuedTrackIds
		val store = MeloSettingsStore(InMemorySettings())
		store.saveSession(
			baseUrl = "http://127.0.0.1:8765",
			username = "",
			bearerToken = "",
			tokenId = "",
			mediaToken = "",
			mediaTokenExpiresAt = 0L,
			authEnabled = false,
		)
		val engine = MockEngine { request ->
			when {
				request.method == HttpMethod.Post && request.url.encodedPath == "/api/search/jobs" -> respond(
					content = """{"id":"job-grp"}""",
					status = HttpStatusCode.OK,
					headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
				)
				request.method == HttpMethod.Get && request.url.encodedPath == "/api/search/jobs/job-grp/events" -> {
					val sse = buildString {
						append("event: candidate\n")
						append("data: {\"id\":\"t-low\",\"source\":\"ITunesMusicClient\",\"source_id\":\"1\",\"title\":\"Solar Wind\",\"artist\":\"CC Collective\",\"album\":\"Open Horizons\",\"duration\":226.0,\"qualities\":[{\"id\":\"q1\",\"label\":\"320k\",\"codec\":\"mp3\",\"bitrate\":320,\"lossless\":false,\"availability\":\"ready\",\"evidence\":\"api\"}],\"playback\":{\"state\":\"ready\",\"url\":\"/api/stream/t-low\"}}\n\n")
						append("event: track\n")
						append("data: {\"id\":\"t-hi\",\"source\":\"AudiusMusicClient\",\"source_id\":\"2\",\"title\":\" Solar Wind \",\"artist\":\"CC Collective\",\"album\":\"Open Horizons\",\"duration\":226.0,\"qualities\":[{\"id\":\"q2\",\"label\":\"FLAC\",\"codec\":\"flac\",\"bitrate\":980,\"lossless\":true,\"availability\":\"ready\",\"evidence\":\"api\"}],\"playback\":{\"state\":\"ready\",\"url\":\"/api/stream/t-hi\"}}\n\n")
						append("event: track\n")
						append("data: {\"id\":\"t-solo\",\"source\":\"AudiusMusicClient\",\"source_id\":\"3\",\"title\":\"Ambient Tide\",\"artist\":\"Free Sound Lab\",\"album\":\"Archive Vol.1\",\"duration\":267.0,\"qualities\":[{\"id\":\"q3\",\"label\":\"128k\",\"codec\":\"mp3\",\"bitrate\":128,\"lossless\":false,\"availability\":\"ready\",\"evidence\":\"api\"}],\"playback\":{\"state\":\"ready\",\"url\":\"/api/stream/t-solo\"}}\n\n")
						append("event: source\n")
						append("data: {\"id\":\"AudiusMusicClient\",\"status\":{\"state\":\"complete\",\"count\":2}}\n\n")
						append("event: done\n")
						append("data: {\"cancelled\":false}\n\n")
					}
					respond(
						content = sse,
						status = HttpStatusCode.OK,
						headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
					)
				}
				request.method == HttpMethod.Post && request.url.encodedPath == "/api/downloads" -> {
					val bodyText = request.body.toByteArray().decodeToString()
					val arr = meloJson.parseToJsonElement(bodyText).jsonArray
					val respJson = arr.joinToString(prefix = "[", postfix = "]") { item ->
						val tid = item.jsonObject["track_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
						"""{"id":"dl-$tid","track":{"id":"$tid","title":"曲目"},"status":"queued"}"""
					}
					respond(
						content = respJson,
						status = HttpStatusCode.OK,
						headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
					)
				}
				else -> respond("{}", HttpStatusCode.OK)
			}
		}

		val client = MeloKeepApiClient(store, engine)
		val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		val controller = MeloSearchController(client, controllerScope)
		controller.search(keyword = "Solar Wind", sources = listOf("AudiusMusicClient", "ITunesMusicClient"))
		val settled = withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { !it.searching && it.tracks.size == 3 }
			}
		}

		assertEquals(2, settled.groupedTracks.size)
		assertEquals("solar wind|cc collective", settled.groupedTracks[0].groupKey)
		assertEquals("t-hi", settled.groupedTracks[0].primary.id)
		assertEquals(listOf("t-low"), settled.groupedTracks[0].alternatives.map { it.id })
		assertEquals("complete", settled.sourceStatuses["AudiusMusicClient"]?.state)

		controller.setQualityFilter(MeloQualityFilter.LOSSLESS)
		assertEquals(1, controller.state.value.groupedTracks.size)
		assertEquals("t-hi", controller.state.value.groupedTracks[0].primary.id)
		assertTrue(controller.state.value.groupedTracks[0].alternatives.isEmpty())

		controller.setQualityFilter(MeloQualityFilter.ALL)
		val firstTrack = controller.state.value.groupedTracks[0].primary
		controller.enqueueDownload(firstTrack).getOrThrow()
		assertTrue("t-hi" in controller.state.value.enqueuedTrackIds)

		controller.toggleMultiSelect(true)
		controller.toggleTrackSelection("t-solo")
		controller.enqueueSelectedDownloads().getOrThrow()
		assertEquals(setOf("t-hi", "t-solo"), controller.state.value.enqueuedTrackIds)

		controllerScope.cancel()
		client.close()
	}

	@Test
	fun newLibraryAndNavidromeApisEncodeAndDecodeCorrectly() = runTest {
		val store = MeloSettingsStore(InMemorySettings())
		store.saveSession(
			baseUrl = "https://melo.example.com",
			username = "admin",
			bearerToken = "tok-xyz",
			tokenId = "tid-1",
			mediaToken = "1800000000.sig",
			mediaTokenExpiresAt = 1800000000L,
			authEnabled = true,
		)

		val engine = MockEngine { request ->
			val path = request.url.encodedPath
			val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
			when {
				request.method == HttpMethod.Post && path == "/api/files/mkdir" -> {
					val body = meloJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
					assertEquals("/music", body["root"]?.jsonPrimitive?.contentOrNull)
					assertEquals("OpenArtist", body["parent"]?.jsonPrimitive?.contentOrNull)
					assertEquals("Albums", body["name"]?.jsonPrimitive?.contentOrNull)
					respond("""{"root":"/music","folder":"OpenArtist/Albums"}""", HttpStatusCode.OK, jsonHeaders)
				}
				request.method == HttpMethod.Post && path == "/api/files/rename" -> {
					val body = meloJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
					assertEquals("/music", body["root"]?.jsonPrimitive?.contentOrNull)
					assertEquals("OpenArtist/Albums", body["folder"]?.jsonPrimitive?.contentOrNull)
					assertEquals("精选", body["new_name"]?.jsonPrimitive?.contentOrNull)
					respond("""{"root":"/music","folder":"OpenArtist/精选"}""", HttpStatusCode.OK, jsonHeaders)
				}
				request.method == HttpMethod.Post && path == "/api/files/move" -> {
					val body = meloJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
					assertEquals("/music", body["target_root"]?.jsonPrimitive?.contentOrNull)
					assertEquals("OpenArtist/精选", body["target_folder"]?.jsonPrimitive?.contentOrNull)
					assertEquals(1, body["files"]?.jsonArray?.size)
					assertEquals(1, body["folders"]?.jsonArray?.size)
					respond("""{"moved":2,"failed":null}""", HttpStatusCode.OK, jsonHeaders)
				}
				request.method == HttpMethod.Post && path == "/api/files/batch-delete" -> {
					val body = meloJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
					assertEquals(1, body["files"]?.jsonArray?.size)
					assertEquals(1, body["folders"]?.jsonArray?.size)
					respond(
						"""{"deleted_files":1,"deleted_folders":1,"track_ids":["asset:a1","local-a1"],"failed":null}""",
						HttpStatusCode.OK,
						jsonHeaders,
					)
				}
				request.method == HttpMethod.Patch && path == "/api/files/tags" -> {
					assertEquals("asset:a1", request.url.parameters["path"])
					val body = meloJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
					assertEquals("晨雾", body["title"]?.jsonPrimitive?.contentOrNull)
					respond(
						"""{"path":"asset:a1","name":"晨雾.flac","size":12345,"root":"/music","folder":"OpenArtist","metadata":{"title":"晨雾"},"locked_fields":["title"],"track":{"id":"local-a1","title":"晨雾","cover":"/api/files/cover?path=asset%3Aa1","qualities":[],"playback":{"state":"ready","url":"/api/files/audio?path=asset%3Aa1"}}}""",
						HttpStatusCode.OK,
						jsonHeaders,
					)
				}
				request.method == HttpMethod.Delete && path == "/api/library/files/locks" -> {
					assertEquals("asset:a1", request.url.parameters["path"])
					respond("""{"unlocked":true}""", HttpStatusCode.OK, jsonHeaders)
				}
				request.method == HttpMethod.Post && path == "/api/library/jobs/job-9/preview" -> {
					val body = meloJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
					assertEquals("cand-2", body["choices"]?.jsonObject?.get("item-1")?.jsonPrimitive?.contentOrNull)
					respond("""{"id":"job-9","action":"both","status":"ready","total":1,"processed":1}""", HttpStatusCode.OK, jsonHeaders)
				}
				request.method == HttpMethod.Get && path == "/api/library/versions" -> {
					assertEquals("asset:a1", request.url.parameters["path"])
					respond(
						"""{"versions":[{"job_id":"job-9","created_at":1700000100,"action":"scrape","status":"complete","before":{"title":"旧标题"},"after":{"title":"新标题"},"restorable":true}]}""",
						HttpStatusCode.OK,
						jsonHeaders,
					)
				}
				request.method == HttpMethod.Post && path == "/api/library/jobs/job-9/restore" -> {
					val body = meloJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
					assertEquals("item-1", body["items"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull)
					respond("""{"id":"job-9","status":"restored"}""", HttpStatusCode.OK, jsonHeaders)
				}
				request.method == HttpMethod.Get && path == "/api/navidrome/capabilities" -> {
					respond("""{"enabled":true,"delete_songs":true,"auto_scan":true}""", HttpStatusCode.OK, jsonHeaders)
				}
				request.method == HttpMethod.Get && path == "/api/navidrome/songs/nd-1/deletion" -> {
					respond("""{"id":"nd-1","path":"/music/OpenArtist/晨雾.flac","revision":"rev-9"}""", HttpStatusCode.OK, jsonHeaders)
				}
				request.method == HttpMethod.Delete && path == "/api/navidrome/songs/nd-1" -> {
					assertEquals("rev-9", request.url.parameters["revision"])
					respond(
						"""{"deleted":true,"track_ids":["navidrome-nd-1","asset:a1"],"warning":null,"scan":{"scanning":true,"count":100,"folderCount":4}}""",
						HttpStatusCode.OK,
						jsonHeaders,
					)
				}
				else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
			}
		}

		val client = MeloKeepApiClient(store, engine, nowEpochSeconds = { 1700000000L })

		val createdDir = client.createFolder("/music", "OpenArtist", "Albums")
		assertEquals("OpenArtist/Albums", createdDir.folder)

		val renamedDir = client.renameFolder("/music", "OpenArtist/Albums", "精选")
		assertEquals("OpenArtist/精选", renamedDir.folder)

		val moveRes = client.moveEntries(
			files = listOf("asset:a1"),
			folders = listOf(MeloLibraryFolder(root = "/music", folder = "Old")),
			targetRoot = "/music",
			targetFolder = "OpenArtist/精选",
		)
		assertEquals(2, moveRes.moved)
		assertTrue(moveRes.failed.isEmpty())

		val delRes = client.batchDeleteEntries(
			files = listOf("asset:a1"),
			folders = listOf(MeloLibraryFolder(root = "/music", folder = "Temp")),
		)
		assertEquals(listOf("asset:a1", "local-a1"), delRes.trackIds)
		assertTrue(delRes.failed.isEmpty())

		val patchedFile = client.updateFileTags("asset:a1", mapOf("title" to "晨雾"))
		assertEquals(listOf("title"), patchedFile.lockedFields)
		assertEquals(
			"https://melo.example.com/api/files/cover?path=asset%3Aa1&media_token=1800000000.sig",
			patchedFile.track?.cover,
		)
		assertEquals(
			"https://melo.example.com/api/files/audio?path=asset%3Aa1&media_token=1800000000.sig",
			patchedFile.track?.playback?.url,
		)

		assertTrue(client.unlockFileTags("asset:a1"))

		val previewJob = client.previewScrapeJob("job-9", mapOf("item-1" to "cand-2"))
		assertEquals("ready", previewJob.status)

		val versions = client.getFileTagVersions("asset:a1")
		assertEquals(1, versions.size)
		assertEquals("job-9", versions[0].jobId)
		assertEquals("旧标题", versions[0].before["title"])
		assertTrue(versions[0].restorable)

		assertTrue(client.restoreJobItems("job-9", listOf("item-1")))

		val caps = client.getNavidromeCapabilities()
		assertTrue(caps.deleteSongs)

		val delPreview = client.previewNavidromeSongDeletion("nd-1")
		assertEquals("/music/OpenArtist/晨雾.flac", delPreview.path)
		assertEquals("rev-9", delPreview.revision)

		val ndDelRes = client.deleteNavidromeSong("nd-1", "rev-9")
		assertEquals(listOf("navidrome-nd-1", "asset:a1"), ndDelRes.trackIds)
		assertTrue(ndDelRes.scan?.scanning == true)
		assertEquals(100L, ndDelRes.scan?.count)

		client.close()
	}

	@Test
	fun studioControllerActiveTaskCountBatchControlsAndNavidromeDeletionWork() = runTest {
		val uiState = MeloStudioUiState(
			downloads = listOf(
				MeloDownloadJob(id = "d1", status = "downloading"),
				MeloDownloadJob(id = "d2", status = "queued"),
				MeloDownloadJob(id = "d3", status = "paused"),
				MeloDownloadJob(id = "d4", status = "complete"),
				MeloDownloadJob(id = "d5", status = "failed"),
			),
			libraryJobs = listOf(
				MeloLibraryJob(id = "j1", status = "scanning"),
				MeloLibraryJob(id = "j2", status = "needs_review"),
				MeloLibraryJob(id = "j3", status = "ready"),
				MeloLibraryJob(id = "j4", status = "complete"),
			),
		)
		assertEquals(2, uiState.activeDownloadCount)
		assertEquals(3, uiState.activeScrapeCount)
		assertEquals(5, uiState.activeTaskCount)

		val store = MeloSettingsStore(InMemorySettings())
		store.saveSession(
			baseUrl = "http://127.0.0.1:8765",
			username = "",
			bearerToken = "",
			tokenId = "",
			mediaToken = "",
			mediaTokenExpiresAt = 0L,
			authEnabled = false,
		)
		var scanTriggered = false
		val engine = MockEngine { request ->
			val path = request.url.encodedPath
			val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
			when {
				request.method == HttpMethod.Get && path == "/api/downloads" -> respond(
					content = """[{"id":"d1","status":"downloading"},{"id":"d2","status":"paused"},{"id":"d3","status":"failed"},{"id":"d4","status":"complete"}]""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Post && path == "/api/downloads/d1/pause" -> respond(
					content = """{"id":"d1","status":"paused"}""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Post && path == "/api/downloads/d2/resume" -> respond(
					content = """{"id":"d2","status":"queued"}""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Post && path == "/api/downloads/d3/retry" -> respond(
					content = """{"id":"d3","status":"queued"}""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Delete && path == "/api/downloads/d4" -> respond(
					content = """{"removed":true}""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Post && path == "/api/library/jobs/lj-1/apply" -> respond(
					content = """{"id":"lj-1","action":"both","status":"complete","total":1,"processed":1}""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Post && path == "/api/navidrome/scan" -> {
					scanTriggered = true
					respond(
						content = """{"scanning":true,"count":88,"folderCount":2}""",
						status = HttpStatusCode.OK,
						headers = jsonHeaders,
					)
				}
				request.method == HttpMethod.Get && path == "/api/navidrome/capabilities" -> respond(
					content = """{"delete_songs":true}""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Get && path == "/api/navidrome/songs/nd-9/deletion" -> respond(
					content = """{"path":"/music/test.flac","revision":"rev-1"}""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Delete && path == "/api/navidrome/songs/nd-9" -> respond(
					content = """{"track_ids":["navidrome-nd-9"],"scan":{"scanning":true,"count":87,"folderCount":2}}""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				else -> respond("{}", HttpStatusCode.OK, jsonHeaders)
			}
		}

		val client = MeloKeepApiClient(store, engine)
		val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		val controller = MeloStudioController(client, controllerScope)

		controller.refreshDownloads()
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { it.downloads.size == 4 }
			}
		}

		controller.pauseAllDownloads()
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { s -> s.downloads.first { it.id == "d1" }.status == "paused" }
			}
		}

		controller.resumeAllDownloads()
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { s -> s.downloads.first { it.id == "d2" }.status == "queued" }
			}
		}

		controller.retryFailedDownloads()
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { s -> s.downloads.first { it.id == "d3" }.status == "queued" }
			}
		}

		controller.clearCompletedDownloads()
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { s -> s.downloads.none { it.id == "d4" } }
			}
		}

		controller.applyScrapeJob("lj-1", autoTriggerScan = true)
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { it.activeJobDetail?.status == "complete" && it.scanStatus?.count == 88L }
			}
		}
		assertTrue(scanTriggered)

		controller.checkNavidromeCapabilities()
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { it.navidromeCanDelete }
			}
		}

		controller.requestNavidromeSongDeletion("nd-9")
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { it.navidromeDeletePreview?.revision == "rev-1" }
			}
		}

		var deletedCallbackCalled = false
		controller.confirmNavidromeSongDeletion("nd-9", "rev-1") {
			deletedCallbackCalled = true
		}
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { it.navidromeDeletePreview == null && it.navidromeDeletingTrackId == null && it.scanStatus?.count == 87L }
			}
		}
		assertTrue(deletedCallbackCalled)

		controllerScope.cancel()
		client.close()
	}

	@Test
	fun studioUiStateCountsAreIsolatedAndLibraryJobCleanupWorks() = runTest {
		val onlyDownloads = MeloStudioUiState(
			downloads = listOf(
				MeloDownloadJob(id = "d1", status = "downloading"),
				MeloDownloadJob(id = "d2", status = "complete"),
			),
			libraryJobs = listOf(
				MeloLibraryJob(id = "j1", status = "complete"),
			),
		)
		assertEquals(1, onlyDownloads.activeDownloadCount)
		assertEquals(0, onlyDownloads.activeScrapeCount)
		assertEquals(1, onlyDownloads.activeTaskCount)

		val onlyScrapes = MeloStudioUiState(
			downloads = listOf(
				MeloDownloadJob(id = "d1", status = "complete"),
			),
			libraryJobs = listOf(
				MeloLibraryJob(id = "j1", status = "needs_review"),
				MeloLibraryJob(id = "j2", status = "preview_ready"),
			),
		)
		assertEquals(0, onlyScrapes.activeDownloadCount)
		assertEquals(2, onlyScrapes.activeScrapeCount)
		assertEquals(2, onlyScrapes.activeTaskCount)

		val deleteDto = meloJson.decodeFromString<MeloDeleteLibraryJobsResult>(
			"""{"deleted":2,"deleted_ids":["j1","j2"],"skipped_running":null}"""
		)
		assertEquals(2, deleteDto.deleted)
		assertEquals(listOf("j1", "j2"), deleteDto.deletedIds)
		assertTrue(deleteDto.skippedRunning.isEmpty())

		val store = MeloSettingsStore(InMemorySettings())
		store.saveSession(
			baseUrl = "http://127.0.0.1:8765",
			username = "",
			bearerToken = "",
			tokenId = "",
			mediaToken = "",
			mediaTokenExpiresAt = 0L,
			authEnabled = false,
		)

		var scrapeCreatedWithDownloadId = ""
		var serverJobs = listOf(
			MeloLibraryJob(id = "lj-old", action = "both", status = "preview_ready"),
			MeloLibraryJob(id = "lj-run", action = "both", status = "scanning"),
			MeloLibraryJob(id = "lj-done", action = "both", status = "complete"),
			MeloLibraryJob(id = "lj-fail", action = "both", status = "failed"),
		)

		val engine = MockEngine { request ->
			val path = request.url.encodedPath
			val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
			when {
				request.method == HttpMethod.Get && path == "/api/library/jobs" -> {
					val bodyJson = serverJobs.joinToString(prefix = """{"jobs":[""", postfix = """],"has_more":false,"total":${serverJobs.size}}""") { job ->
						"""{"id":"${job.id}","action":"${job.action}","status":"${job.status}"}"""
					}
					respond(bodyJson, HttpStatusCode.OK, jsonHeaders)
				}
				request.method == HttpMethod.Post && path == "/api/library/jobs" -> {
					val reqObj = meloJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
					scrapeCreatedWithDownloadId = reqObj["source_download_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
					val newJob = MeloLibraryJob(id = "lj-new", action = "both", status = "preview_ready")
					serverJobs = listOf(newJob) + serverJobs.filterNot { it.id == "lj-old" }
					respond("""{"id":"lj-new","action":"both","status":"preview_ready"}""", HttpStatusCode.OK, jsonHeaders)
				}
				request.method == HttpMethod.Delete && path == "/api/library/jobs/lj-new" -> {
					serverJobs = serverJobs.filterNot { it.id == "lj-new" }
					respond("""{"deleted":true,"id":"lj-new"}""", HttpStatusCode.OK, jsonHeaders)
				}
				request.method == HttpMethod.Delete && path == "/api/library/jobs/lj-run" -> {
					respond("""{"detail":"任务正在运行中，请先取消后再删除"}""", HttpStatusCode.Conflict, jsonHeaders)
				}
				request.method == HttpMethod.Post && path == "/api/library/jobs/batch-delete" -> {
					val reqObj = meloJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
					val mode = reqObj["mode"]?.jsonPrimitive?.contentOrNull
					if (mode == "finished") {
						val finishedIds = serverJobs.filter { it.status in setOf("complete", "failed") }.map { it.id }
						serverJobs = serverJobs.filterNot { it.id in finishedIds }
						val idsJson = finishedIds.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
						respond("""{"deleted":${finishedIds.size},"deleted_ids":$idsJson,"skipped_running":[]}""", HttpStatusCode.OK, jsonHeaders)
					} else {
						val requestedIds = reqObj["ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
						val runningIds = requestedIds.filter { id -> serverJobs.any { it.id == id && it.status == "scanning" } }
						val deletableIds = requestedIds.filterNot { it in runningIds }
						serverJobs = serverJobs.filterNot { it.id in deletableIds }
						val delJson = deletableIds.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
						val skipJson = runningIds.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
						respond("""{"deleted":${deletableIds.size},"deleted_ids":$delJson,"skipped_running":$skipJson}""", HttpStatusCode.OK, jsonHeaders)
					}
				}
				else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
			}
		}

		val client = MeloKeepApiClient(store, engine)
		val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		val controller = MeloStudioController(client, controllerScope)

		controller.refreshLibraryJobs()
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { it.libraryJobs.size == 4 }
			}
		}
		assertEquals(2, controller.state.value.activeScrapeCount)
		assertEquals(0, controller.state.value.activeDownloadCount)

		controller.createScrapeJob(
			paths = listOf("/music/song.flac"),
			action = "both",
			autoApply = false,
			sourceDownloadId = "dl-77",
		)
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { it.activeJobDetail?.id == "lj-new" && it.libraryJobs.none { j -> j.id == "lj-old" } }
			}
		}
		assertEquals("dl-77", scrapeCreatedWithDownloadId)

		controller.toggleJobSelection("lj-new")
		controller.toggleJobSelection("lj-run")
		assertEquals(setOf("lj-new", "lj-run"), controller.state.value.selectedJobIds)

		controller.selectAllJobs(listOf("lj-new", "lj-done"))
		assertEquals(setOf("lj-new", "lj-done"), controller.state.value.selectedJobIds)
		controller.clearJobSelection()
		assertTrue(controller.state.value.selectedJobIds.isEmpty())

		// Deleting running job surfaces backend 409 error
		controller.deleteLibraryJob("lj-run")
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { it.errorMessage?.contains("正在运行中") == true }
			}
		}
		assertTrue(controller.state.value.libraryJobs.any { it.id == "lj-run" })

		// Single delete removes job and clears activeJobDetail if matching
		controller.deleteLibraryJob("lj-new")
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { s -> s.libraryJobs.none { it.id == "lj-new" } && s.activeJobDetail == null }
			}
		}

		// Batch delete deletes non-running and skips running
		controller.toggleJobSelection("lj-fail")
		controller.toggleJobSelection("lj-run")
		controller.batchDeleteLibraryJobs(listOf("lj-fail", "lj-run"))
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { s ->
					!s.jobsLoading &&
						s.libraryJobs.none { it.id == "lj-fail" } &&
						s.libraryJobs.any { it.id == "lj-run" }
				}
			}
		}
		assertFalse("lj-fail" in controller.state.value.selectedJobIds)

		// Clear finished deletes remaining complete/failed jobs
		controller.clearFinishedLibraryJobs()
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { s -> !s.jobsLoading && s.libraryJobs.none { it.id == "lj-done" } }
			}
		}
		assertEquals(listOf("lj-run"), controller.state.value.libraryJobs.map { it.id })

		controllerScope.cancel()
		client.close()
	}

	@Test
	fun localLibraryFavoritesAndPlaylistsApisDecodeAndResolveUrls() = runTest {
		val store = MeloSettingsStore(InMemorySettings())
		store.saveSession(
			baseUrl = "https://melo.example.com",
			username = "admin",
			bearerToken = "tok-xyz",
			tokenId = "tid-1",
			mediaToken = "1800000000.sig",
			mediaTokenExpiresAt = 1800000000L,
			authEnabled = true,
		)

		val recorded = mutableListOf<Pair<HttpMethod, String>>()
		val engine = MockEngine { request ->
			val path = request.url.encodedPath
			recorded += request.method to path
			val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
			when {
				request.method == HttpMethod.Get && path == "/api/favorites" -> respond(
					content = """[{"id":"trk-1","source":"local","source_id":"a1","title":"星海漫游","artist":"CC Collective","album":"Open Horizons","duration":299.0,"cover":"/api/files/cover?path=asset%3Aa1","qualities":[],"playback":{"state":"ready","url":"/api/files/audio?path=asset%3Aa1"}}]""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Put && path == "/api/favorites/trk-2" -> respond(
					content = """{"saved":true}""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Delete && path == "/api/favorites/trk-2" -> respond(
					content = """{"saved":true}""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Get && path == "/api/playlists" -> respond(
					content = """[{"revision":"1","owner":"admin","id":"pl-1","name":"夜色歌单","source":"custom","source_url":"","created_at":1700000000,"detail":"共 1 首","task_id":"pl-1","track_count":1,"issue_count":0,"cover":"/api/files/cover?path=asset%3Aa1"}]""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Post && path == "/api/playlists" -> {
					val body = meloJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
					assertEquals("新歌单", body["name"]?.jsonPrimitive?.contentOrNull)
					assertEquals(listOf("trk-1"), body["track_ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull })
					respond(
						content = """{"id":"pl-2","name":"新歌单","source":"custom","source_url":null,"created_at":1700000100,"tracks":[{"id":"trk-1","source":"local","source_id":"a1","title":"星海漫游","artist":"CC Collective","album":"Open Horizons","duration":299.0,"cover":"/api/files/cover?path=asset%3Aa1","qualities":[],"playback":{"state":"ready","url":"/api/files/audio?path=asset%3Aa1"}}],"detail":"已创建","task_id":"pl-2"}""",
						status = HttpStatusCode.OK,
						headers = jsonHeaders,
					)
				}
				request.method == HttpMethod.Get && path == "/api/playlists/pl-2" -> respond(
					content = """{"id":"pl-2","name":"新歌单","source":"custom","source_url":"","created_at":1700000100,"tracks":[{"id":"trk-1","source":"local","source_id":"a1","title":"星海漫游","artist":"CC Collective","album":"Open Horizons","duration":299.0,"cover":"/api/files/cover?path=asset%3Aa1","qualities":[],"playback":{"state":"ready","url":"/api/files/audio?path=asset%3Aa1"}}],"detail":"","task_id":"pl-2"}""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Post && path == "/api/playlists/pl-2/tracks" -> {
					val body = meloJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
					assertEquals(listOf("trk-2"), body["track_ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull })
					respond(
						content = """{"id":"pl-2","name":"新歌单","source":"custom","created_at":1700000100,"tracks":[{"id":"trk-1","source":"local","source_id":"a1","title":"星海漫游","artist":"CC Collective","album":"Open Horizons","duration":299.0,"qualities":[],"playback":{"state":"ready","url":"/api/files/audio?path=asset%3Aa1"}},{"id":"trk-2","source":"local","source_id":"a2","title":"晨光微风","artist":"CC Collective","album":"Morning Breeze","duration":269.0,"qualities":[],"playback":{"state":"ready","url":"/api/files/audio?path=asset%3Aa2"}}],"detail":"已加入 1 首","task_id":"pl-2"}""",
						status = HttpStatusCode.OK,
						headers = jsonHeaders,
					)
				}
				request.method == HttpMethod.Patch && path == "/api/playlists/pl-2" -> {
					val body = meloJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
					assertEquals("深夜电台", body["name"]?.jsonPrimitive?.contentOrNull)
					respond(
						content = """{"id":"pl-2","name":"深夜电台","source":"custom","created_at":1700000100,"tracks":[],"detail":"","task_id":"pl-2"}""",
						status = HttpStatusCode.OK,
						headers = jsonHeaders,
					)
				}
				request.method == HttpMethod.Delete && path == "/api/playlists/pl-2/tracks/trk-1" -> respond(
					content = """{"id":"pl-2","name":"深夜电台","source":"custom","created_at":1700000100,"tracks":[{"id":"trk-2","source":"local","source_id":"a2","title":"晨光微风","artist":"CC Collective","album":"Morning Breeze","duration":269.0,"qualities":[],"playback":{"state":"ready","url":"/api/files/audio?path=asset%3Aa2"}}],"detail":"","task_id":"pl-2"}""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Delete && path == "/api/playlists/pl-2" -> respond(
					content = """{"deleted":true}""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
			}
		}

		val client = MeloKeepApiClient(store, engine, nowEpochSeconds = { 1700000000L })

		val favs = client.getFavorites()
		assertEquals(1, favs.size)
		assertEquals(
			"https://melo.example.com/api/files/cover?path=asset%3Aa1&media_token=1800000000.sig",
			favs[0].cover,
		)
		assertEquals(
			"https://melo.example.com/api/files/audio?path=asset%3Aa1&media_token=1800000000.sig",
			favs[0].playback.url,
		)
		assertTrue(client.addFavorite("trk-2"))
		assertTrue(client.removeFavorite("trk-2"))

		val playlists = client.getLocalPlaylists()
		assertEquals(1, playlists.size)
		assertEquals("夜色歌单", playlists[0].name)
		assertEquals(
			"https://melo.example.com/api/files/cover?path=asset%3Aa1&media_token=1800000000.sig",
			playlists[0].cover,
		)

		val created = client.createLocalPlaylist("新歌单", listOf("trk-1"))
		assertEquals("pl-2", created.id)
		assertNull(created.sourceUrl)
		assertEquals(
			"https://melo.example.com/api/files/audio?path=asset%3Aa1&media_token=1800000000.sig",
			created.tracks.first().playback.url,
		)

		val detail = client.getLocalPlaylistDetail("pl-2")
		assertEquals("pl-2", detail.id)
		assertEquals(1, detail.tracks.size)

		val added = client.addTracksToLocalPlaylist("pl-2", listOf("trk-2"))
		assertEquals(2, added.tracks.size)

		val renamed = client.renameLocalPlaylist("pl-2", "深夜电台")
		assertEquals("深夜电台", renamed.name)

		val afterRemove = client.removeTrackFromLocalPlaylist("pl-2", "trk-1")
		assertEquals(listOf("trk-2"), afterRemove.tracks.map { it.id })

		assertTrue(client.deleteLocalPlaylist("pl-2"))
		assertTrue((HttpMethod.Delete to "/api/playlists/pl-2") in recorded)

		client.close()
	}

	@Test
	fun localLibraryControllerManagesFavoritesPlaylistsAndErrors() = runTest {
		val store = MeloSettingsStore(InMemorySettings())
		store.saveSession(
			baseUrl = "http://127.0.0.1:8765",
			username = "",
			bearerToken = "",
			tokenId = "",
			mediaToken = "",
			mediaTokenExpiresAt = 0L,
			authEnabled = false,
		)

		val track1 = MeloTrack(id = "trk-1", source = "local", title = "星海漫游", artist = "CC Collective", cover = "/api/files/cover?path=a1")
		val track2 = MeloTrack(id = "trk-2", source = "local", title = "晨光微风", artist = "CC Collective", cover = "/api/files/cover?path=a2")

		val engine = MockEngine { request ->
			val path = request.url.encodedPath
			val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
			when {
				request.method == HttpMethod.Get && path == "/api/favorites" -> respond(
					content = """[{"id":"trk-1","source":"local","title":"星海漫游","artist":"CC Collective","cover":"/api/files/cover?path=a1","qualities":[],"playback":{"state":"ready","url":"/api/files/audio?path=a1"}}]""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Put && path == "/api/favorites/trk-2" -> respond(
					content = """{"saved":true}""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Delete && path == "/api/favorites/trk-1" -> respond(
					content = """{"saved":true}""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Get && path == "/api/playlists" -> respond(
					content = """[{"id":"pl-1","name":"默认本地歌单","source":"custom","track_count":1,"issue_count":0}]""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Post && path == "/api/playlists" -> respond(
					content = """{"id":"pl-new","name":"公路电台","source":"custom","tracks":[{"id":"trk-2","source":"local","title":"晨光微风","artist":"CC Collective","cover":"/api/files/cover?path=a2","qualities":[],"playback":{"state":"ready"}}],"detail":"已创建歌单“公路电台”，共 1 首曲目。","task_id":"pl-new"}""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Get && path == "/api/playlists/pl-1" -> respond(
					content = """{"id":"pl-1","name":"默认本地歌单","source":"custom","tracks":[{"id":"trk-1","source":"local","title":"星海漫游","artist":"CC Collective","qualities":[],"playback":{"state":"ready"}}],"task_id":"pl-1"}""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Patch && path == "/api/playlists/pl-new" -> respond(
					content = """{"id":"pl-new","name":"夜间电台","source":"custom","tracks":[{"id":"trk-2","source":"local","title":"晨光微风","artist":"CC Collective","cover":"/api/files/cover?path=a2","qualities":[],"playback":{"state":"ready"}}],"task_id":"pl-new"}""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Post && path == "/api/playlists/pl-new/tracks" -> respond(
					content = """{"id":"pl-new","name":"夜间电台","source":"custom","tracks":[{"id":"trk-2","source":"local","title":"晨光微风","artist":"CC Collective","cover":"/api/files/cover?path=a2","qualities":[],"playback":{"state":"ready"}},{"id":"trk-1","source":"local","title":"星海漫游","artist":"CC Collective","qualities":[],"playback":{"state":"ready"}}],"detail":"已向歌单“夜间电台”加入 1 首曲目，共 2 首。","task_id":"pl-new"}""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Delete && path == "/api/playlists/pl-new/tracks/trk-2" -> respond(
					content = """{"id":"pl-new","name":"夜间电台","source":"custom","tracks":[{"id":"trk-1","source":"local","title":"星海漫游","artist":"CC Collective","qualities":[],"playback":{"state":"ready"}}],"task_id":"pl-new"}""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Delete && path == "/api/playlists/pl-new" -> respond(
					content = """{"deleted":true}""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
			}
		}

		val client = MeloKeepApiClient(store, engine)
		val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		val controller = MeloLocalLibraryController(client, controllerScope)

		controller.refreshAll()
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { !it.loading && it.favorites.size == 1 && it.playlists.size == 1 }
			}
		}
		assertTrue(controller.isFavorite("trk-1"))
		assertFalse(controller.isFavorite("trk-2"))

		// Toggle favorite on track2 (add) and track1 (remove)
		controller.toggleFavorite(track2)
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { !it.mutating && "trk-2" in it.favoriteIds }
			}
		}
		assertTrue(controller.isFavorite("trk-2"))

		controller.toggleFavorite(track1)
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { !it.mutating && "trk-1" !in it.favoriteIds }
			}
		}
		assertFalse(controller.isFavorite("trk-1"))

		// Validation error on blank playlist name
		controller.createPlaylist("   ")
		assertEquals("请输入歌单名称", controller.state.value.errorMessage)
		controller.clearError()
		assertNull(controller.state.value.errorMessage)

		// Create playlist
		controller.createPlaylist("公路电台", listOf("trk-2"))
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { !it.mutating && it.activePlaylist?.id == "pl-new" }
			}
		}
		assertEquals(2, controller.state.value.playlists.size)
		assertEquals(1, controller.state.value.playlists.first { it.id == "pl-new" }.trackCount)

		// Rename playlist
		controller.renamePlaylist("pl-new", "夜间电台")
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { !it.mutating && it.activePlaylist?.name == "夜间电台" }
			}
		}
		assertEquals("夜间电台", controller.state.value.playlists.first { it.id == "pl-new" }.name)

		// Add tracks to playlist
		controller.addTracksToPlaylist("pl-new", listOf("trk-1"))
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { !it.mutating && it.activePlaylist?.tracks?.size == 2 }
			}
		}
		assertEquals(2, controller.state.value.playlists.first { it.id == "pl-new" }.trackCount)

		// Remove track from playlist
		controller.removeTrackFromPlaylist("pl-new", "trk-2")
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { !it.mutating && it.activePlaylist?.tracks?.map { t -> t.id } == listOf("trk-1") }
			}
		}
		assertEquals(1, controller.state.value.playlists.first { it.id == "pl-new" }.trackCount)

		// Delete playlist clears activePlaylist
		controller.deletePlaylist("pl-new")
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { !it.mutating && it.activePlaylist == null && it.playlists.none { p -> p.id == "pl-new" } }
			}
		}

		// Load playlist detail and clear
		controller.loadPlaylistDetail("pl-1")
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { !it.playlistDetailLoading && it.activePlaylist?.id == "pl-1" }
			}
		}
		controller.clearActivePlaylist()
		assertNull(controller.state.value.activePlaylist)
		controller.clearNotice()
		assertNull(controller.state.value.noticeMessage)

		controllerScope.cancel()
		client.close()
	}

	@Test
	fun getConfigAndUpdateConfigMergeAndOmitRemovedFields() = runTest {
		val store = MeloSettingsStore(InMemorySettings())
		store.saveSession(
			baseUrl = "https://melo.example.com",
			username = "admin",
			bearerToken = "tok-cfg",
			tokenId = "tid-1",
			mediaToken = "",
			mediaTokenExpiresAt = 0L,
			authEnabled = true,
		)

		var putCoreBody = ""
		var putEngineBody = ""
		val engine = MockEngine { request ->
			val path = request.url.encodedPath
			val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
			when {
				request.method == HttpMethod.Get && path == "/api/config" -> respond(
					content = """
						{
						  "available_sources": [
						    {
						      "id": "AudiusMusicClient",
						      "label": "Audius",
						      "origin": "builtin",
						      "capability": {"tier": "full", "search": true, "playlist": true, "lyrics": false, "metadata": true, "episodes": false}
						    },
						    {
						      "id": "sample-plugin",
						      "label": "Sample Plugin",
						      "origin": "plugin",
						      "plugin_id": "sample-plugin",
						      "version": "1.0.0",
						      "description": "示例插件"
						    }
						  ],
						  "sources": ["AudiusMusicClient", "FMAMusicClient", "ITunesMusicClient"],
						  "results_per_source": 20
						}
					""".trimIndent(),
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Get && path == "/api/engine/config" -> respond(
					content = """{"proxy_url":"http://127.0.0.1:7890","proxy_scope":"recommended","proxy_sources":["AudiusMusicClient"]}""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Put && path == "/api/config" -> {
					putCoreBody = request.body.toByteArray().decodeToString()
					respond("""{"saved":true,"restart_required":false}""", HttpStatusCode.OK, jsonHeaders)
				}
				request.method == HttpMethod.Put && path == "/api/engine/config" -> {
					putEngineBody = request.body.toByteArray().decodeToString()
					respond("""{"saved":true,"restart_required":false}""", HttpStatusCode.OK, jsonHeaders)
				}
				else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
			}
		}

		val client = MeloKeepApiClient(store, engine)
		val cfg = client.getConfig()
		assertEquals(listOf("AudiusMusicClient", "FMAMusicClient", "ITunesMusicClient"), cfg.sources)
		assertEquals("http://127.0.0.1:7890", cfg.proxyUrl)
		assertEquals("recommended", cfg.proxyScope)
		assertEquals(listOf("AudiusMusicClient"), cfg.proxySources)
		assertEquals("plugin", cfg.availableSources[1].origin)
		assertEquals("sample-plugin", cfg.availableSources[1].pluginId)

		val saveResp = client.updateConfig(cfg)
		assertTrue(saveResp.saved)
		assertTrue(putCoreBody.isNotBlank())
		assertTrue(putEngineBody.isNotBlank())
		val coreKeys = meloJson.parseToJsonElement(putCoreBody).jsonObject.keys
		val engineKeys = meloJson.parseToJsonElement(putEngineBody).jsonObject.keys
		assertEquals(
			setOf(
				"sources",
				"results_per_source",
				"search_timeout_seconds",
				"playback_timeout_seconds",
				"download_concurrency",
				"download_layout",
				"lyrics_mode",
				"embed_metadata",
				"source_settings",
			),
			coreKeys,
		)
		assertEquals(
			setOf(
				"sources",
				"results_per_source",
				"search_timeout_seconds",
				"download_layout",
				"lyrics_mode",
				"embed_metadata",
				"source_settings",
				"proxy_url",
				"proxy_scope",
				"proxy_sources",
			),
			engineKeys,
		)
		client.close()
	}
}

class MeloSourceLabelRegistryTest {
	@kotlin.test.Test
	fun resolvesDynamicLabelsCaseInsensitively() {
		MeloSourceLabelRegistry.clearForTest()
		try {
			MeloSourceLabelRegistry.register(
				listOf(
					MeloAvailableSource(
						id = "sample-plugin",
						engineName = "SampleEngine",
						pluginId = "community-sample",
						label = "示例插件音源",
					),
					MeloAvailableSource(id = "blank-label", label = "  "),
				)
			)
			assertEquals("示例插件音源", MeloSourceLabelRegistry.resolve("sample-plugin"))
			assertEquals("示例插件音源", MeloSourceLabelRegistry.resolve(" SAMPLE-Plugin "))
			assertEquals("示例插件音源", MeloSourceLabelRegistry.resolve("sampleengine"))
			assertEquals("示例插件音源", MeloSourceLabelRegistry.resolve("Community-Sample"))
			assertEquals("覆盖", MeloSourceLabelRegistry.resolve("sample-plugin", mapOf("sample-plugin" to "覆盖")))
			assertEquals("覆盖2", MeloSourceLabelRegistry.resolve("Sample-Plugin", mapOf("SAMPLE-PLUGIN" to "覆盖2")))
			assertEquals("示例插件音源", MeloSourceLabelRegistry.resolve("sample-plugin", mapOf("sample-plugin" to " ")))
			assertNull(MeloSourceLabelRegistry.resolve("unknown-source"))
			assertNull(MeloSourceLabelRegistry.resolve("blank-label"))
			assertNull(MeloSourceLabelRegistry.resolve("   "))
		} finally {
			MeloSourceLabelRegistry.clearForTest()
		}
	}
}