package io.github.agonie0v0.melokeep.shared

import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

		listOf(
			"pure-light",
			"warm-vinyl",
			"nordic-frost",
			"rose-mist",
			"obsidian",
			"vinyl-gold",
			"midnight-aurora",
			"cyber-amethyst",
			"dynamic",
		).forEach { preset ->
			reloaded.setStudioThemePreset(preset)
			assertEquals(preset, reloaded.studioThemePreset.value)
			assertEquals(preset, MeloSettingsStore(backing).studioThemePreset.value)
		}

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

	@Test
	fun settingsStoreSearchAndLibraryHistoryManageMax20AndSingleRemoval() {
		val backing = InMemorySettings()
		val store = MeloSettingsStore(backing)
		assertEquals(20, MeloSettingsStore.MAX_SEARCH_HISTORY)
		assertTrue(store.searchHistory.value.isEmpty())
		assertTrue(store.librarySearchHistory.value.isEmpty())

		// Record 22 distinct online search queries -> capped at 20, latest first
		for (i in 1..22) {
			store.recordSearchHistory("  query-$i  ")
		}
		assertEquals(20, store.searchHistory.value.size)
		assertEquals("query-22", store.searchHistory.value.first())
		assertEquals("query-3", store.searchHistory.value.last())

		// Deduplicate case-insensitively and move to front
		store.recordSearchHistory("QUERY-10")
		assertEquals(20, store.searchHistory.value.size)
		assertEquals("QUERY-10", store.searchHistory.value.first())

		// Blank removal is a no-op; matching query is removed case-insensitively and persisted
		store.removeSearchHistory("   ")
		assertEquals(20, store.searchHistory.value.size)
		store.removeSearchHistory(" query-10 ")
		assertEquals(19, store.searchHistory.value.size)
		assertFalse(store.searchHistory.value.any { it.equals("query-10", ignoreCase = true) })

		val reloadedOnline = MeloSettingsStore(backing)
		assertEquals(19, reloadedOnline.searchHistory.value.size)
		assertFalse(reloadedOnline.searchHistory.value.any { it.equals("query-10", ignoreCase = true) })

		// Library search history: record, deduplicate, cap 20, single remove, persist under melo_library_search_history, clear
		for (i in 1..22) {
			store.recordLibrarySearchHistory("  lib-$i  ")
		}
		assertEquals(20, store.librarySearchHistory.value.size)
		assertEquals("lib-22", store.librarySearchHistory.value.first())
		assertEquals("lib-3", store.librarySearchHistory.value.last())
		assertTrue(backing.hasKey("melo_library_search_history"))

		store.recordLibrarySearchHistory("LIB-5")
		assertEquals(20, store.librarySearchHistory.value.size)
		assertEquals("LIB-5", store.librarySearchHistory.value.first())

		store.removeLibrarySearchHistory("   ")
		assertEquals(20, store.librarySearchHistory.value.size)
		store.removeLibrarySearchHistory(" lib-5 ")
		assertEquals(19, store.librarySearchHistory.value.size)
		assertFalse(store.librarySearchHistory.value.any { it.equals("lib-5", ignoreCase = true) })

		val reloadedLib = MeloSettingsStore(backing)
		assertEquals(19, reloadedLib.librarySearchHistory.value.size)
		assertFalse(reloadedLib.librarySearchHistory.value.any { it.equals("lib-5", ignoreCase = true) })

		reloadedLib.clearLibrarySearchHistory()
		assertTrue(reloadedLib.librarySearchHistory.value.isEmpty())
		assertFalse(backing.hasKey("melo_library_search_history"))
	}

	@Test
	fun searchStreamHandlesCrossDispatcherEmissionWithoutFlowInvariantViolation() = runTest {
		val store = MeloSettingsStore(InMemorySettings())
		store.saveSession(
			baseUrl = "https://melo.example.com",
			username = "",
			bearerToken = "",
			tokenId = "",
			mediaToken = "",
			mediaTokenExpiresAt = 0L,
			authEnabled = false,
		)

		val sseScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		val engine = MockEngine { request ->
			val path = request.url.encodedPath
			val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
			when {
				request.method == HttpMethod.Post && path == "/api/search/jobs" -> respond(
					content = """{"id":"job-cross-thread"}""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Get && path == "/api/search/jobs/job-cross-thread/events" -> {
					val byteChannel = ByteChannel(autoFlush = true)
					sseScope.launch(Dispatchers.IO) {
						try {
							byteChannel.writeStringUtf8(
								"id: 1\nevent: candidate\ndata: {\"id\":\"t-1\",\"source\":\"AudiusMusicClient\",\"source_id\":\"1\",\"title\":\"Cross Thread Song\",\"artist\":\"Synth\",\"album\":\"Live\",\"duration\":180.0,\"qualities\":[],\"playback\":{\"state\":\"ready\",\"url\":\"/api/stream/t-1\"}}\n\n"
							)
							byteChannel.writeStringUtf8(
								"id: 2\nevent: source\ndata: {\"id\":\"AudiusMusicClient\",\"status\":{\"state\":\"complete\",\"count\":1}}\n\n"
							)
							byteChannel.writeStringUtf8(
								"id: 3\nevent: done\ndata: {\"cancelled\":false}\n\n"
							)
						} finally {
							byteChannel.close()
						}
					}
					respond(
						content = byteChannel,
						status = HttpStatusCode.OK,
						headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
					)
				}
				request.method == HttpMethod.Delete && path == "/api/search/jobs/job-cross-thread" -> respond(
					content = """{"cancelled":true}""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
			}
		}

		val client = MeloKeepApiClient(store, engine)
		val collectorDispatcher = Dispatchers.Default.limitedParallelism(1)
		val events = withContext(collectorDispatcher) {
			withTimeout(5_000L) {
				client.searchStream("Cross Thread Song", sources = listOf("AudiusMusicClient")).toList()
			}
		}

		assertEquals(3, events.size)
		val cand = assertIs<MeloSearchEvent.Candidate>(events[0])
		assertEquals("t-1", cand.track.id)
		assertEquals("https://melo.example.com/api/stream/t-1", cand.track.playback.url)
		assertIs<MeloSearchEvent.Source>(events[1])
		assertIs<MeloSearchEvent.Done>(events[2])

		sseScope.cancel()
		client.close()
	}

	@Test
	fun studioControllerSupportsScopeDirSearchCandidatesKeepExistingApplyStatusesAndPolling() = runTest {
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

		var createdScopeDir: String? = null
		var createdPathsSize = -1
		var searchQuerySent = ""
		var searchItemSent = ""
		var searchSourcesSent: List<String> = emptyList()
		var previewChoicesSent: Map<String, String> = emptyMap()
		var jobDetailPollCount = 0
		var jobsListPollCount = 0
		var nextApplyStatus = "applying"
		var scanCount = 0
		var pollListPhase = "initial"

		val pendingItem = MeloLibraryJobItem(
			id = "item-review",
			name = "mystery.flac",
			status = "needs_review",
			before = mapOf("title" to "Old Title", "artist" to "Old Artist"),
		)

		val engine = MockEngine { request ->
			val path = request.url.encodedPath
			val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
			when {
				request.method == HttpMethod.Get && path == "/api/downloads" -> respond(
					content = "[]",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Post && path == "/api/library/jobs" -> {
					val reqObj = meloJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
					createdPathsSize = reqObj["paths"]?.jsonArray?.size ?: -1
					createdScopeDir = reqObj["scope_dir"]?.jsonPrimitive?.contentOrNull
					respond(
						content = """{"id":"job-scope","action":"both","status":"scanning","total":5,"processed":0,"detail":"正在扫描分析（0 / 5）"}""",
						status = HttpStatusCode.OK,
						headers = jsonHeaders,
					)
				}
				request.method == HttpMethod.Get && path == "/api/library/jobs" -> {
					jobsListPollCount++
					val status = when (pollListPhase) {
						"applying_bg" -> if (jobsListPollCount >= 4) "complete" else "applying"
						else -> if (jobsListPollCount >= 2) "needs_review" else "scanning"
					}
					respond(
						content = """{"jobs":[{"id":"job-scope","action":"both","status":"$status","total":5,"processed":5}],"has_more":false,"total":1}""",
						status = HttpStatusCode.OK,
						headers = jsonHeaders,
					)
				}
				request.method == HttpMethod.Get && path == "/api/library/jobs/job-scope" -> {
					jobDetailPollCount++
					val jobStatus = if (jobDetailPollCount >= 1) "needs_review" else "scanning"
					val itemJson = meloJson.encodeToString(MeloLibraryJobItem.serializer(), pendingItem)
					respond(
						content = """{"id":"job-scope","action":"both","status":"$jobStatus","total":1,"processed":1,"items":[$itemJson]}""",
						status = HttpStatusCode.OK,
						headers = jsonHeaders,
					)
				}
				request.method == HttpMethod.Post && path == "/api/library/jobs/job-scope/search" -> {
					val reqObj = meloJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
					searchItemSent = reqObj["item"]?.jsonPrimitive?.contentOrNull.orEmpty()
					searchQuerySent = reqObj["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
					searchSourcesSent = reqObj["sources"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
					respond(
						content = """{"id":"job-scope","action":"both","status":"needs_review","total":1,"processed":1,"items":[{"id":"item-review","name":"mystery.flac","status":"needs_review","candidates":[{"id":"cand-new","source":"ITunesMusicClient","title":"Found Song","artist":"Real Artist","album":"Real Album","score":0.95,"reasons":[],"conflicts":[]}]}]}""",
						status = HttpStatusCode.OK,
						headers = jsonHeaders,
					)
				}
				request.method == HttpMethod.Post && path == "/api/library/jobs/job-scope/preview" -> {
					val reqObj = meloJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
					previewChoicesSent = reqObj["choices"]?.jsonObject
						?.mapValues { (_, v) -> v.jsonPrimitive.contentOrNull.orEmpty() }
						.orEmpty()
					respond(
						content = """{"id":"job-scope","action":"both","status":"preview_ready","total":1,"processed":1,"items":[{"id":"item-review","name":"mystery.flac","status":"preview_ready","chosen":"existing"}]}""",
						status = HttpStatusCode.OK,
						headers = jsonHeaders,
					)
				}
				request.method == HttpMethod.Post && path == "/api/library/jobs/job-scope/apply" -> {
					when (nextApplyStatus) {
						"error_400" -> respond(
							content = """{"detail":"仍有 1 首曲目待确认候选"}""",
							status = HttpStatusCode.BadRequest,
							headers = jsonHeaders,
						)
						else -> respond(
							content = """{"id":"job-scope","action":"both","status":"$nextApplyStatus","total":1,"processed":1}""",
							status = HttpStatusCode.OK,
							headers = jsonHeaders,
						)
					}
				}
				request.method == HttpMethod.Post && path == "/api/navidrome/scan" -> {
					scanCount++
					respond("""{"scanning":true,"count":$scanCount,"folderCount":1}""", HttpStatusCode.OK, jsonHeaders)
				}
				else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
			}
		}

		val client = MeloKeepApiClient(store, engine)
		val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		val controller = MeloStudioController(client, controllerScope)

		// Validation when both scopeDir == null and paths is empty
		controller.createScrapeJob(paths = emptyList(), scopeDir = null)
		assertEquals("请先选择需要刮削或整理的文件", controller.state.value.errorMessage)
		controller.clearError()

		// scopeDir = "" allows empty paths and sends scope_dir = ""
		controller.createScrapeJob(paths = emptyList(), action = "both", autoApply = false, scopeDir = "")
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { !it.jobsLoading && it.activeJobDetail?.id == "job-scope" }
			}
		}
		assertEquals(0, createdPathsSize)
		assertEquals("", createdScopeDir)
		assertEquals("scanning", controller.state.value.activeJobDetail?.status)

		// Polling updates both libraryJobs and activeJobDetail when scanning
		controller.startDownloadPolling(intervalMs = 800L)
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first {
					it.activeJobDetail?.status == "needs_review" &&
						it.libraryJobs.firstOrNull { j -> j.id == "job-scope" }?.status == "needs_review"
				}
			}
		}
		controller.stopPolling()
		assertTrue(jobsListPollCount >= 1)
		assertTrue(jobDetailPollCount >= 1)

		// Manual candidate search updates activeJobDetail, libraryJobs and noticeMessage
		controller.searchJobItemCandidates(
			jobId = "job-scope",
			itemId = "item-review",
			query = "  Found Song  ",
			sources = listOf("ITunesMusicClient"),
		)
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { !it.jobsLoading && it.noticeMessage == "已重新搜索候选" }
			}
		}
		assertEquals("item-review", searchItemSent)
		assertEquals("Found Song", searchQuerySent)
		assertEquals(listOf("ITunesMusicClient"), searchSourcesSent)
		assertEquals(
			"cand-new",
			controller.state.value.activeJobDetail?.items?.firstOrNull()?.candidates?.firstOrNull()?.id,
		)

		// When activeJobDetail is cleared and libraryJobs only holds summary (no items),
		// keepExistingForPendingItems fetches detail first and submits "existing" choices
		controller.clearJobDetail()
		previewChoicesSent = emptyMap()
		controller.keepExistingForPendingItems("job-scope")
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first {
					!it.jobsLoading &&
						it.libraryJobs.firstOrNull { j -> j.id == "job-scope" }?.status == "preview_ready"
				}
			}
		}
		assertEquals(mapOf("item-review" to "existing"), previewChoicesSent)
		assertEquals("已为 1 首待确认曲目保留现有标签", controller.state.value.noticeMessage)

		// Calling keepExistingForPendingItems when no needs_review items exist gives clear notice
		controller.keepExistingForPendingItems("job-scope")
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { !it.jobsLoading && it.noticeMessage == "当前没有待确认候选的曲目" }
			}
		}

		// applyScrapeJob status notices & Navidrome scan gating
		nextApplyStatus = "applying"
		controller.applyScrapeJob("job-scope", autoTriggerScan = true)
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { !it.jobsLoading && it.activeJobDetail?.status == "applying" }
			}
		}
		assertEquals("已提交后台写入，正在归档处理...", controller.state.value.noticeMessage)
		assertEquals(0, scanCount)

		// Even with activeJobDetail cleared, polling refreshes libraryJobs while any job is in "applying"
		controller.clearJobDetail()
		pollListPhase = "applying_bg"
		controller.startDownloadPolling(intervalMs = 800L)
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first {
					it.libraryJobs.firstOrNull { j -> j.id == "job-scope" }?.status == "complete"
				}
			}
		}
		controller.stopPolling()

		nextApplyStatus = "needs_review"
		controller.applyScrapeJob("job-scope", autoTriggerScan = true)
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { !it.jobsLoading && it.activeJobDetail?.status == "needs_review" }
			}
		}
		assertEquals("仍有曲目待确认候选，请先选择后再归档", controller.state.value.noticeMessage)
		assertEquals(0, scanCount)

		nextApplyStatus = "partial"
		controller.applyScrapeJob("job-scope", autoTriggerScan = true)
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first {
					!it.jobsLoading &&
						it.activeJobDetail?.status == "partial" &&
						it.scanStatus?.count == 1L
				}
			}
		}
		assertEquals("部分条目已完成归档", controller.state.value.noticeMessage)
		assertEquals(1, scanCount)

		nextApplyStatus = "complete"
		controller.applyScrapeJob("job-scope", autoTriggerScan = true)
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first {
					!it.jobsLoading &&
						it.activeJobDetail?.status == "complete" &&
						it.scanStatus?.count == 2L
				}
			}
		}
		assertEquals("归档完成", controller.state.value.noticeMessage)
		assertEquals(2, scanCount)

		nextApplyStatus = "error_400"
		controller.applyScrapeJob("job-scope", autoTriggerScan = true)
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { !it.jobsLoading && it.errorMessage == "仍有 1 首曲目待确认候选" }
			}
		}

		// SearchController removeHistory removes single entry cleanly
		val searchController = MeloSearchController(client, controllerScope)
		store.recordSearchHistory("Alpha")
		store.recordSearchHistory("Beta")
		searchController.removeHistory("alpha")
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				searchController.state.first { it.searchHistory == listOf("Beta") }
			}
		}

		controllerScope.cancel()
		client.close()
	}

	@Test
	fun refreshLibraryApiAndStudioControllerReloadWorkAndHandleErrors() = runTest {
		val store = MeloSettingsStore(InMemorySettings())
		var refreshCalledCount = 0
		var filesCalledCount = 0
		var refreshShouldFail = false
		var includeUnhealthy = false

		val engine = MockEngine { request ->
			val path = request.url.encodedPath
			val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
			when {
				request.method == HttpMethod.Post && path == "/api/library/refresh" -> {
					refreshCalledCount++
					assertEquals("1", request.headers["X-MeloKeep-Request"])
					assertEquals("Bearer tok-refresh", request.headers[HttpHeaders.Authorization])
					if (refreshShouldFail) {
						respond(
							content = """{"detail":"曲库正在扫描中"}""",
							status = HttpStatusCode.Conflict,
							headers = jsonHeaders,
						)
					} else {
						val unhealthyJson = if (includeUnhealthy) """["/offline/nas"]""" else "[]"
						respond(
							content = """{"roots_scanned":1,"scanned_files":42,"unchanged_files":40,"updated_or_new":2,"marked_missing":1,"unhealthy_roots":$unhealthyJson}""",
							status = HttpStatusCode.OK,
							headers = jsonHeaders,
						)
					}
				}
				request.method == HttpMethod.Get && path == "/api/library/files" -> {
					filesCalledCount++
					assertEquals("1", request.headers["X-MeloKeep-Request"])
					assertEquals("Bearer tok-refresh", request.headers[HttpHeaders.Authorization])
					respond(
						content = """{"items":[{"path":"asset:new1","name":"new1.flac","size":1024,"root":"/music","folder":"Fresh","track":{"id":"local-new1","source":"local","source_id":"new1","title":"New Track","artist":"Artist","album":"Fresh","duration":200.0,"cover":"/api/files/cover?path=asset%3Anew1","qualities":[],"playback":{"state":"ready","url":"/api/files/audio?path=asset%3Anew1"}}}],"next_cursor":null,"index_revision":99,"indexing":false,"folders":[{"root":"/music","folder":"Fresh"}]}""",
						status = HttpStatusCode.OK,
						headers = jsonHeaders,
					)
				}
				else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
			}
		}

		val client = MeloKeepApiClient(store, engine)
		val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		val controller = MeloStudioController(client, controllerScope)

		// Unconfigured / unauthenticated returns failure immediately without calling API
		var unauthResult: Result<MeloLibraryRefreshResult>? = null
		controller.refreshLibraryAndReload { unauthResult = it }
		val capturedUnauth = assertNotNull(unauthResult)
		assertTrue(capturedUnauth.isFailure)
		assertEquals(0, refreshCalledCount)

		// Configure authenticated session
		store.saveSession(
			baseUrl = "https://melo.example.com",
			username = "admin",
			bearerToken = "tok-refresh",
			tokenId = "tid-refresh",
			mediaToken = "1800000000.sig",
			mediaTokenExpiresAt = 1800000000L,
			authEnabled = true,
		)

		// Direct API test
		val directRes = client.refreshLibrary()
		assertEquals(1, directRes.rootsScanned)
		assertEquals(42, directRes.scannedFiles)
		assertEquals(40, directRes.unchangedFiles)
		assertEquals(2, directRes.updatedOrNew)
		assertEquals(1, directRes.markedMissing)
		assertTrue(directRes.unhealthyRoots.isEmpty())

		// Controller refreshLibraryAndReload with unhealthyRoots reloads files and sets noticeMessage
		includeUnhealthy = true
		val successDeferred = CompletableDeferred<Result<MeloLibraryRefreshResult>>()
		controller.refreshLibraryAndReload(query = "New", folder = "Fresh") {
			successDeferred.complete(it)
		}
		val successResult = withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				val res = successDeferred.await()
				controller.state.first {
					!it.filesLoading && it.libraryFiles.size == 1
				}
				res
			}
		}
		assertTrue(successResult.isSuccess)
		assertEquals(2, refreshCalledCount)
		assertEquals(1, filesCalledCount)
		assertEquals("New", controller.state.value.fileQuery)
		assertEquals("Fresh", controller.state.value.currentFolder)
		assertEquals(listOf("Fresh"), controller.state.value.libraryFolders.map { it.folder })
		assertEquals("local-new1", controller.state.value.libraryFiles.single().track?.id)
		assertTrue(
			controller.state.value.noticeMessage?.contains("已刷新曲库：共 42 首（新增/变更 2，移除 1）") == true
		)
		assertTrue(controller.state.value.noticeMessage?.contains("/offline/nas") == true)

		// Controller failure surfaces errorMessage and invokes onCompleted failure
		refreshShouldFail = true
		val failDeferred = CompletableDeferred<Result<MeloLibraryRefreshResult>>()
		controller.refreshLibraryAndReload { failDeferred.complete(it) }
		val failResult = withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				val res = failDeferred.await()
				controller.state.first {
					!it.filesLoading && it.errorMessage == "曲库正在扫描中"
				}
				res
			}
		}
		assertTrue(failResult.isFailure)

		controllerScope.cancel()
		client.close()
	}

	@Test
	fun displayNameAndEffectiveNamePersistAcrossSessionAndSettingsController() = runTest {
		val backing = InMemorySettings()
		val store = MeloSettingsStore(backing)

		// Default effectiveName is "MeloKeep" when displayName is blank
		assertEquals("", store.currentConfig().displayName)
		assertEquals("MeloKeep", store.currentConfig().effectiveName)
		assertEquals("MeloKeep", MeloServerConfig(displayName = "   ").effectiveName)

		// setDisplayName trims and persists
		store.setDisplayName("  家庭影音云  ")
		assertEquals("家庭影音云", store.currentConfig().displayName)
		assertEquals("家庭影音云", store.currentConfig().effectiveName)
		assertEquals("家庭影音云", backing.getString("melokeep.display_name", ""))

		// saveSession without explicit displayName preserves existing custom name
		store.saveSession(
			baseUrl = "192.168.1.10:8765",
			username = "admin",
			bearerToken = "tok-1",
			tokenId = "tid-1",
			mediaToken = "m-1",
			mediaTokenExpiresAt = 1800000000L,
			authEnabled = true,
			enabled = true,
		)
		assertEquals("家庭影音云", store.currentConfig().displayName)
		assertEquals("家庭影音云", store.currentConfig().effectiveName)

		// Reloading from backing store restores displayName
		val reloaded = MeloSettingsStore(backing)
		assertEquals("家庭影音云", reloaded.currentConfig().displayName)
		assertEquals("家庭影音云", reloaded.currentConfig().effectiveName)

		// clearSession(keepServerUrl = true) keeps displayName
		reloaded.clearSession(keepServerUrl = true)
		assertEquals("http://192.168.1.10:8765", reloaded.currentConfig().baseUrl)
		assertEquals("家庭影音云", reloaded.currentConfig().displayName)
		assertEquals("家庭影音云", reloaded.currentConfig().effectiveName)

		// MeloSettingsController.testAndLogin & updateDisplayName
		val engine = MockEngine { request ->
			val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
			when (request.url.encodedPath) {
				"/api/health" -> respond(
					"""{"ok":true,"version":"1.2.0","sources":4}""",
					HttpStatusCode.OK,
					jsonHeaders,
				)
				"/api/auth/login" -> respond(
					"""{"success":true,"auth_enabled":true,"token":"tok-2","token_id":"tid-2","media_token":"m-2","media_token_expires_at":1900000000}""",
					HttpStatusCode.OK,
					jsonHeaders,
				)
				"/api/config" -> respond(
					"""{"sources":["AudiusMusicClient"],"available_sources":[]}""",
					HttpStatusCode.OK,
					jsonHeaders,
				)
				"/api/auth/devices" -> respond("""{"devices":[]}""", HttpStatusCode.OK, jsonHeaders)
				else -> respond("{}", HttpStatusCode.OK, jsonHeaders)
			}
		}
		val client = MeloKeepApiClient(reloaded, engine)
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		val controller = MeloSettingsController(client, scope)

		controller.testAndLogin(
			baseUrl = "192.168.1.10:8765",
			username = "admin",
			password = "pwd",
			displayName = " 工作室曲库 ",
		)
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { !it.testingConnection && it.serverConfig.displayName == "工作室曲库" }
			}
		}
		assertEquals("工作室曲库", controller.state.value.serverConfig.effectiveName)

		controller.updateDisplayName("NAS 主力源")
		assertEquals("NAS 主力源", controller.state.value.serverConfig.displayName)
		assertEquals("NAS 主力源", controller.state.value.serverConfig.effectiveName)

		controller.updateDisplayName("   ")
		assertEquals("", controller.state.value.serverConfig.displayName)
		assertEquals("MeloKeep", controller.state.value.serverConfig.effectiveName)

		// clearSession(keepServerUrl = false) and reset() clear displayName
		reloaded.setDisplayName("Temp")
		reloaded.clearSession(keepServerUrl = false)
		assertEquals("", reloaded.currentConfig().displayName)
		assertEquals("MeloKeep", reloaded.currentConfig().effectiveName)

		scope.cancel()
		client.close()
	}

	@Test
	fun semVerCompareReleaseAssetMatchersAndUpdateCheckerWork() = runTest {
		// SemVer comparisons
		assertTrue(MeloSemVer.compare("v1.0.3", "v1.0.2") > 0)
		assertTrue(MeloSemVer.compare("v1.0.2", "v1.0.3") < 0)
		assertEquals(0, MeloSemVer.compare("v1.2.0", "1.2"))
		assertEquals(0, MeloSemVer.compare("v1.2.0+20261121", "1.2.0+build1"))
		assertTrue(MeloSemVer.compare("1.1.0", "1.1.0-rc1") > 0)
		assertTrue(MeloSemVer.compare("1.1.0-rc1", "1.1.0") < 0)
		assertTrue(MeloSemVer.compare("1.1.0-rc2", "1.1.0-rc1") > 0)
		assertTrue(MeloSemVer.compare("1.1.0-rc10", "1.1.0-rc2") > 0)
		assertTrue(MeloSemVer.compare("1.1.0-beta.1", "1.1.0-alpha.9") > 0)
		assertTrue(MeloSemVer.compare("1.0.0-alpha.1", "1.0.0-alpha") > 0)
		assertTrue(MeloSemVer.isNewer("v2.0.0", "1.9.9"))
		assertFalse(MeloSemVer.isNewer("v1.0.0-rc1", "1.0.0"))

		// Asset matching for Android APK and iOS IPA
		val releaseWithMixedAssets = MeloGitHubRelease(
			tagName = "v1.3.0",
			name = "MeloKeep v1.3.0",
			assets = listOf(
				MeloGitHubAsset(
					name = "MeloKeep-android-debug.apk",
					downloadUrl = "https://github.com/Agonie0v0/melokeep/releases/download/v1.3.0/MeloKeep-android-debug.apk",
					size = 48_000_000L,
				),
				MeloGitHubAsset(
					name = "MeloKeep-empty-url.apk",
					downloadUrl = "   ",
					size = 100L,
				),
				MeloGitHubAsset(
					name = "MeloKeep-android-release.apk",
					downloadUrl = "https://github.com/Agonie0v0/melokeep/releases/download/v1.3.0/MeloKeep-android-release.apk",
					size = 16_000_000L,
					contentType = "application/vnd.android.package-archive",
				),
				MeloGitHubAsset(
					name = "MeloKeep-iOS-unsigned.ipa",
					downloadUrl = "https://github.com/Agonie0v0/melokeep/releases/download/v1.3.0/MeloKeep-iOS-unsigned.ipa",
					size = 24_000_000L,
					contentType = "application/octet-stream",
				),
			),
		)
		val pickedApk = assertNotNull(releaseWithMixedAssets.findAndroidApkAsset())
		assertEquals("MeloKeep-android-release.apk", pickedApk.name)
		val pickedIpa = assertNotNull(releaseWithMixedAssets.findIosIpaAsset())
		assertEquals("MeloKeep-iOS-unsigned.ipa", pickedIpa.name)

		val releaseWithoutMobileAssets = MeloGitHubRelease(
			tagName = "v1.3.0",
			assets = listOf(
				MeloGitHubAsset(name = "source.tar.gz", downloadUrl = "https://example.com/source.tar.gz"),
			),
		)
		assertNull(releaseWithoutMobileAssets.findAndroidApkAsset())
		assertNull(releaseWithoutMobileAssets.findIosIpaAsset())

		// MeloAppUpdateChecker with MockEngine
		var capturedAccept: String? = null
		var capturedUa: String? = null
		var capturedUrl: String? = null
		var shouldFail403 = false
		val engine = MockEngine { request ->
			capturedUrl = request.url.toString()
			capturedAccept = request.headers[HttpHeaders.Accept]
			capturedUa = request.headers[HttpHeaders.UserAgent]
			val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
			if (shouldFail403) {
				respond(
					content = """{"message":"API rate limit exceeded"}""",
					status = HttpStatusCode.Forbidden,
					headers = jsonHeaders,
				)
			} else {
				respond(
					content = """
						{
						  "tag_name": "v1.4.0",
						  "name": "MeloKeep 1.4.0",
						  "html_url": "https://github.com/Agonie0v0/melokeep/releases/tag/v1.4.0",
						  "body": "### 新增\n- 支持应用内一键更新",
						  "published_at": "2026-11-21T00:00:00Z",
						  "assets": [
						    {
						      "name": "MeloKeep-v1.4.0.apk",
						      "browser_download_url": "https://github.com/Agonie0v0/melokeep/releases/download/v1.4.0/MeloKeep-v1.4.0.apk",
						      "size": 15857935,
						      "content_type": "application/vnd.android.package-archive"
						    },
						    {
						      "name": "MeloKeep-v1.4.0.ipa",
						      "browser_download_url": "https://github.com/Agonie0v0/melokeep/releases/download/v1.4.0/MeloKeep-v1.4.0.ipa",
						      "size": 21000000,
						      "content_type": "application/octet-stream"
						    }
						  ]
						}
					""".trimIndent(),
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
			}
		}
		val httpClient = HttpClient(engine) {
			expectSuccess = false
		}
		val latest = MeloAppUpdateChecker.fetchLatestRelease(httpClient)
		assertEquals("https://api.github.com/repos/Agonie0v0/melokeep/releases/latest", capturedUrl)
		assertEquals("application/vnd.github+json", capturedAccept)
		assertEquals("MeloKeep-Mobile", capturedUa)
		assertEquals("v1.4.0", latest.tagName)
		assertEquals("MeloKeep 1.4.0", latest.name)
		assertEquals("https://github.com/Agonie0v0/melokeep/releases/tag/v1.4.0", latest.htmlUrl)
		assertEquals("2026-11-21T00:00:00Z", latest.publishedAt)
		assertEquals(2, latest.assets.size)
		assertEquals("MeloKeep-v1.4.0.apk", latest.findAndroidApkAsset()?.name)
		assertEquals(15857935L, latest.findAndroidApkAsset()?.size)
		assertEquals("MeloKeep-v1.4.0.ipa", latest.findIosIpaAsset()?.name)

		shouldFail403 = true
		val err = assertFailsWith<MeloApiException> {
			MeloAppUpdateChecker.fetchLatestRelease(httpClient)
		}
		assertEquals(403, err.statusCode)
		assertTrue(err.message.contains("获取 GitHub 最新版本失败"))
		assertTrue(err.message.contains("可在 Web 或连接 MeloKeep 服务端后配置 GitHub Token 避免限流"))

		httpClient.close()
	}

	@Test
	fun updateCheckerSelectsLatestStableReleaseMatchingPlatformAssetAndFallsBackCleanly() = runTest {
		val requestedUrls = mutableListOf<String>()
		val capturedAccepts = mutableListOf<String?>()
		val capturedUas = mutableListOf<String?>()
		val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

		// Case 1: draft (v1.0.13 w/ apk+ipa), prerelease (v1.0.12-rc.1 w/ apk+ipa),
		// stable w/o assets (v1.0.11), stable w/ apk+ipa (v1.0.10), older stable w/ apk+ipa (v1.0.8)
		var responseMode = "mixed"
		val engine = MockEngine { request ->
			val url = request.url.toString()
			requestedUrls += url
			capturedAccepts += request.headers[HttpHeaders.Accept]
			capturedUas += request.headers[HttpHeaders.UserAgent]
			when {
				url.endsWith("/releases?per_page=20") && responseMode == "mixed" -> respond(
					content = """
						[
						  {
						    "tag_name": "v1.0.13",
						    "name": "Draft 1.0.13",
						    "html_url": "https://github.com/Agonie0v0/melokeep/releases/tag/v1.0.13",
						    "draft": true,
						    "prerelease": false,
						    "assets": [
						      {
						        "name": "MeloKeep-v1.0.13-android-release.apk",
						        "browser_download_url": "https://example.com/v1.0.13.apk",
						        "size": 16000000
						      },
						      {
						        "name": "MeloKeep-v1.0.13-ios.ipa",
						        "browser_download_url": "https://example.com/v1.0.13.ipa",
						        "size": 22000000
						      }
						    ]
						  },
						  {
						    "tag_name": "v1.0.12-rc.1",
						    "name": "RC 1.0.12-rc.1",
						    "html_url": "https://github.com/Agonie0v0/melokeep/releases/tag/v1.0.12-rc.1",
						    "draft": false,
						    "prerelease": true,
						    "assets": [
						      {
						        "name": "MeloKeep-v1.0.12-rc.1-android-release.apk",
						        "browser_download_url": "https://example.com/v1.0.12-rc.1.apk",
						        "size": 16000000
						      },
						      {
						        "name": "MeloKeep-v1.0.12-rc.1-ios.ipa",
						        "browser_download_url": "https://example.com/v1.0.12-rc.1.ipa",
						        "size": 22000000
						      }
						    ]
						  },
						  {
						    "tag_name": "v1.0.11",
						    "name": "Stable 1.0.11 without assets",
						    "html_url": "https://github.com/Agonie0v0/melokeep/releases/tag/v1.0.11",
						    "draft": false,
						    "prerelease": false,
						    "assets": []
						  },
						  {
						    "tag_name": "v1.0.10",
						    "name": "Stable 1.0.10",
						    "html_url": "https://github.com/Agonie0v0/melokeep/releases/tag/v1.0.10",
						    "draft": false,
						    "prerelease": false,
						    "assets": [
						      {
						        "name": "MeloKeep-v1.0.10-android-release.apk",
						        "browser_download_url": "https://example.com/v1.0.10.apk",
						        "size": 15000000
						      },
						      {
						        "name": "MeloKeep-v1.0.10-ios.ipa",
						        "browser_download_url": "https://example.com/v1.0.10.ipa",
						        "size": 21000000
						      }
						    ]
						  },
						  {
						    "tag_name": "v1.0.8",
						    "name": "Stable 1.0.8",
						    "html_url": "https://github.com/Agonie0v0/melokeep/releases/tag/v1.0.8",
						    "draft": false,
						    "prerelease": false,
						    "assets": [
						      {
						        "name": "MeloKeep-v1.0.8-android-release.apk",
						        "browser_download_url": "https://example.com/v1.0.8.apk",
						        "size": 14000000
						      },
						      {
						        "name": "MeloKeep-v1.0.8-ios.ipa",
						        "browser_download_url": "https://example.com/v1.0.8.ipa",
						        "size": 20000000
						      }
						    ]
						  }
						]
					""".trimIndent(),
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)

				url.endsWith("/releases?per_page=20") && responseMode == "no_stable_assets" -> respond(
					content = """
						[
						  {
						    "tag_name": "v1.0.13",
						    "draft": true,
						    "prerelease": false,
						    "assets": [
						      { "name": "MeloKeep-v1.0.13.apk", "browser_download_url": "https://example.com/v1.0.13.apk" },
						      { "name": "MeloKeep-v1.0.13.ipa", "browser_download_url": "https://example.com/v1.0.13.ipa" }
						    ]
						  },
						  {
						    "tag_name": "v1.0.12-rc.1",
						    "draft": false,
						    "prerelease": true,
						    "assets": [
						      { "name": "MeloKeep-v1.0.12-rc.1.apk", "browser_download_url": "https://example.com/v1.0.12-rc.1.apk" },
						      { "name": "MeloKeep-v1.0.12-rc.1.ipa", "browser_download_url": "https://example.com/v1.0.12-rc.1.ipa" }
						    ]
						  },
						  {
						    "tag_name": "v1.0.10",
						    "draft": false,
						    "prerelease": false,
						    "assets": []
						  },
						  {
						    "tag_name": "v1.0.11",
						    "draft": false,
						    "prerelease": false,
						    "assets": [
						      { "name": "source.tar.gz", "browser_download_url": "https://example.com/source.tar.gz" }
						    ]
						  }
						]
					""".trimIndent(),
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)

				url.endsWith("/releases?per_page=20") && responseMode == "no_stable" -> respond(
					content = """
						[
						  { "tag_name": "v1.0.13", "draft": true, "prerelease": false, "assets": [] },
						  { "tag_name": "v1.0.12-rc.1", "draft": false, "prerelease": true, "assets": [] }
						]
					""".trimIndent(),
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)

				url.endsWith("/releases/latest") -> respond(
					content = """
						{
						  "tag_name": "v1.0.9",
						  "name": "Fallback Latest",
						  "html_url": "https://github.com/Agonie0v0/melokeep/releases/tag/v1.0.9",
						  "assets": []
						}
					""".trimIndent(),
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)

				else -> respond(
					content = """{"message":"Not Found"}""",
					status = HttpStatusCode.NotFound,
					headers = jsonHeaders,
				)
			}
		}

		val httpClient = HttpClient(engine) { expectSuccess = false }
		val androidRelease = MeloAppUpdateChecker.fetchLatestAndroidRelease(httpClient)
		assertEquals("v1.0.10", androidRelease.tagName)
		assertNotNull(androidRelease.findAndroidApkAsset())
		assertEquals("MeloKeep-v1.0.10-android-release.apk", androidRelease.findAndroidApkAsset()?.name)

		val iosRelease = MeloAppUpdateChecker.fetchLatestIosRelease(httpClient)
		assertEquals("v1.0.10", iosRelease.tagName)
		assertNotNull(iosRelease.findIosIpaAsset())
		assertEquals("MeloKeep-v1.0.10-ios.ipa", iosRelease.findIosIpaAsset()?.name)

		assertTrue(requestedUrls.all { it == "https://api.github.com/repos/Agonie0v0/melokeep/releases?per_page=20" })
		assertTrue(capturedAccepts.all { it == "application/vnd.github+json" })
		assertTrue(capturedUas.all { it == "MeloKeep-Mobile" })

		// Also verify MeloKeepApiClient convenience delegates
		val apiClient = MeloKeepApiClient(MeloSettingsStore(InMemorySettings()), engine)
		assertEquals("v1.0.10", apiClient.fetchLatestAndroidGitHubRelease().tagName)
		assertEquals("v1.0.10", apiClient.fetchLatestIosGitHubRelease().tagName)
		apiClient.close()

		// Case 2: No stable release carries matching platform asset -> falls back to highest-version stable release (v1.0.11)
		responseMode = "no_stable_assets"
		val androidFallback = MeloAppUpdateChecker.fetchLatestAndroidRelease(httpClient)
		assertEquals("v1.0.11", androidFallback.tagName)
		assertNull(androidFallback.findAndroidApkAsset())

		val iosFallback = MeloAppUpdateChecker.fetchLatestIosRelease(httpClient)
		assertEquals("v1.0.11", iosFallback.tagName)
		assertNull(iosFallback.findIosIpaAsset())

		// Case 3: No stable releases at all -> falls back to /releases/latest
		responseMode = "no_stable"
		val latestFallback = MeloAppUpdateChecker.fetchLatestIosRelease(httpClient)
		assertEquals("v1.0.9", latestFallback.tagName)
		assertEquals("https://api.github.com/repos/Agonie0v0/melokeep/releases/latest", requestedUrls.last())

		httpClient.close()
	}

	@Test
	fun hasAndroidAndIosUpdateForEnforceAssetStableAndNewerVersion() {
		val apkAsset = MeloGitHubAsset(
			name = "MeloKeep-v1.0.13-android.apk",
			downloadUrl = "https://example.com/MeloKeep-v1.0.13-android.apk",
			size = 15_000_000L,
		)
		val ipaAsset = MeloGitHubAsset(
			name = "MeloKeep-v1.0.13-ios-unsigned.ipa",
			downloadUrl = "https://example.com/MeloKeep-v1.0.13-ios-unsigned.ipa",
			size = 21_000_000L,
		)

		val sameVersionBoth = MeloGitHubRelease(
			tagName = "v1.0.13",
			assets = listOf(apkAsset, ipaAsset),
		)
		assertFalse(sameVersionBoth.hasAndroidUpdateFor("1.0.13"))
		assertFalse(sameVersionBoth.hasAndroidUpdateFor("v1.0.13"))
		assertFalse(sameVersionBoth.hasIosUpdateFor("1.0.13"))
		assertFalse(sameVersionBoth.hasIosUpdateFor("v1.0.13"))

		val olderVersionBoth = MeloGitHubRelease(
			tagName = "v1.0.12",
			assets = listOf(apkAsset, ipaAsset),
		)
		assertFalse(olderVersionBoth.hasAndroidUpdateFor("1.0.13"))
		assertFalse(olderVersionBoth.hasIosUpdateFor("1.0.13"))

		val higherWebOnlyRelease = MeloGitHubRelease(
			tagName = "v1.0.17",
			assets = listOf(
				MeloGitHubAsset(
					name = "melokeep-web-v1.0.17.tar.gz",
					downloadUrl = "https://example.com/melokeep-web-v1.0.17.tar.gz",
				),
			),
		)
		assertFalse(higherWebOnlyRelease.hasAndroidUpdateFor("1.0.13"))
		assertFalse(higherWebOnlyRelease.hasIosUpdateFor("1.0.13"))

		val higherDraft = MeloGitHubRelease(
			tagName = "v1.0.18",
			draft = true,
			assets = listOf(apkAsset, ipaAsset),
		)
		assertFalse(higherDraft.hasAndroidUpdateFor("1.0.13"))
		assertFalse(higherDraft.hasIosUpdateFor("1.0.13"))

		val higherPrerelease = MeloGitHubRelease(
			tagName = "v1.0.18-rc.1",
			prerelease = true,
			assets = listOf(apkAsset, ipaAsset),
		)
		assertFalse(higherPrerelease.hasAndroidUpdateFor("1.0.13"))
		assertFalse(higherPrerelease.hasIosUpdateFor("1.0.13"))

		val androidOnlyHigher = MeloGitHubRelease(
			tagName = "v1.0.14",
			assets = listOf(apkAsset),
		)
		assertTrue(androidOnlyHigher.hasAndroidUpdateFor("1.0.13"))
		assertFalse(androidOnlyHigher.hasIosUpdateFor("1.0.13"))

		val iosOnlyHigher = MeloGitHubRelease(
			tagName = "v1.0.14",
			assets = listOf(ipaAsset),
		)
		assertFalse(iosOnlyHigher.hasAndroidUpdateFor("1.0.13"))
		assertTrue(iosOnlyHigher.hasIosUpdateFor("1.0.13"))
	}

	@Test
	fun fetchLatestReleaseWithAssetOrNullPaginatesSkipsWebOnlyAndReturnsNullWhenAbsent() = runTest {
		val requestedUrls = mutableListOf<String>()
		val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
		var mode = "page2_hit"

		val engine = MockEngine { request ->
			val url = request.url.toString()
			requestedUrls += url
			val page = request.url.parameters["page"]?.toIntOrNull() ?: 1
			val perPage = request.url.parameters["per_page"]?.toIntOrNull() ?: 100
			if (url.endsWith("/releases/latest")) {
				return@MockEngine respond(
					content = """{"tag_name":"v1.0.99","assets":[]}""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
			}

			val body = when (mode) {
				"page2_hit" -> when (page) {
					1 -> """
						[
						  {
						    "tag_name": "v1.0.17",
						    "name": "Web Update 1.0.17",
						    "draft": false,
						    "prerelease": false,
						    "assets": [
						      { "name": "melokeep-web-v1.0.17.tar.gz", "browser_download_url": "https://example.com/web-1.0.17.tar.gz" }
						    ]
						  },
						  {
						    "tag_name": "v1.0.16",
						    "draft": true,
						    "prerelease": false,
						    "assets": [
						      { "name": "MeloKeep-v1.0.16-android.apk", "browser_download_url": "https://example.com/v1.0.16.apk" },
						      { "name": "MeloKeep-v1.0.16-ios-unsigned.ipa", "browser_download_url": "https://example.com/v1.0.16.ipa" }
						    ]
						  }
						]
					""".trimIndent()
					2 -> """
						[
						  {
						    "tag_name": "v1.0.15-rc.1",
						    "draft": false,
						    "prerelease": true,
						    "assets": [
						      { "name": "MeloKeep-v1.0.15-rc.1-android.apk", "browser_download_url": "https://example.com/v1.0.15-rc.1.apk" },
						      { "name": "MeloKeep-v1.0.15-rc.1-ios-unsigned.ipa", "browser_download_url": "https://example.com/v1.0.15-rc.1.ipa" }
						    ]
						  },
						  {
						    "tag_name": "v1.0.13",
						    "name": "Mobile 1.0.13",
						    "draft": false,
						    "prerelease": false,
						    "assets": [
						      { "name": "MeloKeep-v1.0.13-android.apk", "browser_download_url": "https://example.com/v1.0.13.apk", "size": 16000000 },
						      { "name": "MeloKeep-v1.0.13-ios-unsigned.ipa", "browser_download_url": "https://example.com/v1.0.13.ipa", "size": 22000000 }
						    ]
						  }
						]
					""".trimIndent()
					else -> "[]"
				}

				"platform_split" -> when (page) {
					1 -> """
						[
						  {
						    "tag_name": "v1.0.17",
						    "draft": false,
						    "prerelease": false,
						    "assets": []
						  },
						  {
						    "tag_name": "v1.0.14",
						    "draft": false,
						    "prerelease": false,
						    "assets": [
						      { "name": "MeloKeep-v1.0.14-android.apk", "browser_download_url": "https://example.com/v1.0.14.apk" }
						    ]
						  }
						]
					""".trimIndent()
					2 -> """
						[
						  {
						    "tag_name": "v1.0.12",
						    "draft": false,
						    "prerelease": false,
						    "assets": [
						      { "name": "MeloKeep-v1.0.12-ios-unsigned.ipa", "browser_download_url": "https://example.com/v1.0.12.ipa" }
						    ]
						  }
						]
					""".trimIndent()
					else -> "[]"
				}

				"short_web_only" -> """
					[
					  { "tag_name": "v1.0.17", "draft": false, "prerelease": false, "assets": [] },
					  { "tag_name": "v1.0.16", "draft": false, "prerelease": false, "assets": [] }
					]
				""".trimIndent()

				"all_pages_web_only" -> when (page) {
					1 -> """
						[
						  { "tag_name": "v1.0.17", "draft": false, "prerelease": false, "assets": [] },
						  { "tag_name": "v1.0.16", "draft": false, "prerelease": false, "assets": [] }
						]
					""".trimIndent()
					2 -> """
						[
						  { "tag_name": "v1.0.15", "draft": false, "prerelease": false, "assets": [] },
						  { "tag_name": "v1.0.14", "draft": false, "prerelease": false, "assets": [] }
						]
					""".trimIndent()
					else -> """
						[
						  { "tag_name": "v1.0.13", "draft": false, "prerelease": false, "assets": [] },
						  { "tag_name": "v1.0.12", "draft": false, "prerelease": false, "assets": [] }
						]
					""".trimIndent()
				}

				else -> "[]"
			}
			assertTrue(perPage in 1..100)
			respond(content = body, status = HttpStatusCode.OK, headers = jsonHeaders)
		}

		val httpClient = HttpClient(engine) { expectSuccess = false }

		// 1. Page 1 has only pure-web and draft releases; page 2 has prerelease and v1.0.13 w/ APK+IPA
		requestedUrls.clear()
		val androidPaged = MeloAppUpdateChecker.fetchLatestAndroidReleaseOrNull(httpClient, perPage = 2)
		assertNotNull(androidPaged)
		assertEquals("v1.0.13", androidPaged.tagName)
		assertFalse(androidPaged.hasAndroidUpdateFor("1.0.13"))
		assertTrue(androidPaged.hasAndroidUpdateFor("1.0.12"))
		assertEquals(
			listOf(
				"https://api.github.com/repos/Agonie0v0/melokeep/releases?per_page=2&page=1",
				"https://api.github.com/repos/Agonie0v0/melokeep/releases?per_page=2&page=2",
			),
			requestedUrls,
		)

		requestedUrls.clear()
		val iosPaged = MeloAppUpdateChecker.fetchLatestIosReleaseOrNull(httpClient, perPage = 2)
		assertNotNull(iosPaged)
		assertEquals("v1.0.13", iosPaged.tagName)
		assertFalse(iosPaged.hasIosUpdateFor("1.0.13"))
		assertTrue(iosPaged.hasIosUpdateFor("1.0.12"))
		assertEquals(2, requestedUrls.size)

		// 2. Platform-specific split: Android hits v1.0.14 on page 1; iOS skips Android-only v1.0.14 and hits v1.0.12 on page 2
		mode = "platform_split"
		requestedUrls.clear()
		val splitAndroid = MeloAppUpdateChecker.fetchLatestAndroidReleaseOrNull(httpClient, perPage = 2)
		assertEquals("v1.0.14", splitAndroid?.tagName)
		assertEquals(1, requestedUrls.size)

		requestedUrls.clear()
		val splitIos = MeloAppUpdateChecker.fetchLatestIosReleaseOrNull(httpClient, perPage = 2)
		assertEquals("v1.0.12", splitIos?.tagName)
		assertEquals(2, requestedUrls.size)

		// 3. Short page (< perPage) with only web releases stops immediately and returns null (never falls back to /releases/latest)
		mode = "short_web_only"
		requestedUrls.clear()
		assertNull(MeloAppUpdateChecker.fetchLatestAndroidReleaseOrNull(httpClient))
		assertNull(MeloAppUpdateChecker.fetchLatestIosReleaseOrNull(httpClient))
		assertEquals(
			listOf(
				"https://api.github.com/repos/Agonie0v0/melokeep/releases?per_page=100&page=1",
				"https://api.github.com/repos/Agonie0v0/melokeep/releases?per_page=100&page=1",
			),
			requestedUrls,
		)

		val apiClient = MeloKeepApiClient(MeloSettingsStore(InMemorySettings()), engine)
		assertNull(apiClient.fetchLatestAndroidGitHubReleaseOrNull())
		assertNull(apiClient.fetchLatestIosGitHubReleaseOrNull())
		apiClient.close()

		// 4. Exhausting all maxPages with web-only releases returns null
		mode = "all_pages_web_only"
		requestedUrls.clear()
		assertNull(MeloAppUpdateChecker.fetchLatestAndroidReleaseOrNull(httpClient, maxPages = 3, perPage = 2))
		assertEquals(
			listOf(
				"https://api.github.com/repos/Agonie0v0/melokeep/releases?per_page=2&page=1",
				"https://api.github.com/repos/Agonie0v0/melokeep/releases?per_page=2&page=2",
				"https://api.github.com/repos/Agonie0v0/melokeep/releases?per_page=2&page=3",
			),
			requestedUrls,
		)
		assertTrue(requestedUrls.none { it.endsWith("/releases/latest") })

		// 5. Empty releases list returns null
		mode = "empty"
		requestedUrls.clear()
		assertNull(MeloAppUpdateChecker.fetchLatestIosReleaseOrNull(httpClient))
		assertEquals(1, requestedUrls.size)

		httpClient.close()
	}

	@Test
	fun qualityDeserializesSizeFieldsAndFormatsFriendlyPresentations() {
		// 1. Deserialize MeloQuality with and without size_bytes / size_label
		val trackWithSizesJson = """
			{
			  "id": "trk-size-1",
			  "title": "Mountain Echo",
			  "artist": "Open Lab",
			  "duration": 240.0,
			  "size_bytes": 33973862,
			  "size_label": "32.4 MB",
			  "qualities": [
			    {
			      "id": "q-flac",
			      "label": "FLAC 无损 verified",
			      "codec": "flac",
			      "bitrate": 980,
			      "lossless": true,
			      "availability": "verified",
			      "evidence": "probed_audio",
			      "size_bytes": 33973862,
			      "size_label": "32.4 MB"
			    },
			    {
			      "id": "q-mp3-legacy",
			      "label": "320k MP3",
			      "codec": "mp3",
			      "bitrate": 320,
			      "lossless": false,
			      "availability": "candidate",
			      "evidence": "declared"
			    }
			  ],
			  "playback": {"state": "ready"}
			}
		""".trimIndent()
		val decodedTrack = meloJson.decodeFromString<MeloTrack>(trackWithSizesJson)
		assertEquals(2, decodedTrack.qualities.size)
		assertEquals(33973862L, decodedTrack.qualities[0].sizeBytes)
		assertEquals("32.4 MB", decodedTrack.qualities[0].sizeLabel)
		assertNull(decodedTrack.qualities[1].sizeBytes)
		assertNull(decodedTrack.qualities[1].sizeLabel)

		// 2. formatBytes integer rounding across B / KB / MB / GB
		assertEquals("0 B", MeloQualityFormatter.formatBytes(-10L))
		assertEquals("0 B", MeloQualityFormatter.formatBytes(0L))
		assertEquals("512 B", MeloQualityFormatter.formatBytes(512L))
		assertEquals("1 KB", MeloQualityFormatter.formatBytes(1024L))
		assertEquals("2 KB", MeloQualityFormatter.formatBytes(1536L))
		assertEquals("512 KB", MeloQualityFormatter.formatBytes(512L * 1024L))
		assertEquals("1.0 MB", MeloQualityFormatter.formatBytes(1024L * 1024L))
		assertEquals("32.4 MB", MeloQualityFormatter.formatBytes(33_973_862L))
		assertEquals("1.00 GB", MeloQualityFormatter.formatBytes(1024L * 1024L * 1024L))
		assertEquals("1.23 GB", MeloQualityFormatter.formatBytes(1_320_702_443L))
		assertEquals("2.00 GB", MeloQualityFormatter.formatBytes(2L * 1024L * 1024L * 1024L - 1L))

		// 3. Size resolution & estimation ("约 XX MB")
		val qFlacPres = MeloQualityFormatter.formatQuality(decodedTrack.qualities[0], decodedTrack)
		assertEquals("无损音质 · FLAC", qFlacPres.title)
		assertEquals("980 kbps", qFlacPres.bitrateText)
		assertEquals("32.4 MB", qFlacPres.sizeText)
		assertEquals("980 kbps · 32.4 MB", qFlacPres.subtitle)
		assertTrue(qFlacPres.isLossless)

		// Multi-quality track: second quality lacks explicit size -> must estimate from duration & bitrate, NOT track.sizeBytes
		val qMp3Pres = MeloQualityFormatter.formatQuality(decodedTrack.qualities[1], decodedTrack)
		assertEquals("极高音质 · MP3", qMp3Pres.title)
		assertEquals("320 kbps", qMp3Pres.bitrateText)
		assertEquals("约 9.2 MB", qMp3Pres.sizeText)
		assertEquals("320 kbps · 约 9.2 MB", qMp3Pres.subtitle)
		assertFalse(qMp3Pres.isLossless)

		// Single-quality track falls back to track.sizeBytes / track.sizeLabel
		val singleQualityTrack = MeloTrack(
			id = "trk-single",
			duration = 200.0,
			sizeBytes = 12_582_912L,
			qualities = listOf(MeloQuality(id = "q-single", label = "MP3", codec = "mp3", bitrate = 320)),
		)
		assertEquals(
			"12.0 MB",
			MeloQualityFormatter.resolveQualitySizeText(singleQualityTrack.qualities.single(), singleQualityTrack),
		)
		val singleLabelTrack = MeloTrack(
			id = "trk-single-label",
			duration = 200.0,
			sizeLabel = "  18.5 MB  ",
			qualities = listOf(MeloQuality(id = "q-lbl", label = "FLAC", codec = "flac", lossless = true)),
		)
		assertEquals(
			"18.5 MB",
			MeloQualityFormatter.resolveQualitySizeText(singleLabelTrack.qualities.single(), singleLabelTrack),
		)

		// Lossless track without bitrate defaults to 950 kbps for track & quality size estimation
		val losslessEstTrack = MeloTrack(
			id = "trk-lossless-est",
			duration = 240.0,
			qualities = listOf(
				MeloQuality(id = "q-l", label = "FLAC", codec = "flac", lossless = true),
				MeloQuality(id = "q-m", label = "128k", codec = "mp3", bitrate = 128),
			),
		)
		assertEquals("约 27.2 MB", MeloQualityFormatter.resolveTrackSizeText(losslessEstTrack))
		assertEquals(
			"约 27.2 MB",
			MeloQualityFormatter.resolveQualitySizeText(losslessEstTrack.qualities[0], losslessEstTrack),
		)
		assertEquals("32.4 MB", MeloQualityFormatter.resolveTrackSizeText(decodedTrack))
		assertNull(MeloQualityFormatter.resolveTrackSizeText(MeloTrack(id = "trk-empty", duration = 0.0)))

		// 4. All quality tiers, preview/login flags, codec extraction from label, and internal-word exclusion
		val internalWords = listOf(
			"verified",
			"candidate",
			"resolved",
			"full",
			"probed_audio",
			"declared",
			"engine",
			"probe",
			"catalog_claim",
		)
		val baseTrack = MeloTrack(id = "trk-tiers", duration = 240.0)

		val masterQ = MeloQuality(
			id = "q-master",
			label = "臻品母带 Spatial verified",
			codec = "",
			bitrate = 2400,
			lossless = true,
			availability = "verified",
			evidence = "probed_audio",
			sizeLabel = " 85.0 MB ",
		)
		val masterPres = MeloQualityFormatter.formatQuality(masterQ, baseTrack)
		assertEquals("超清母带", masterPres.title)
		assertEquals("2400 kbps · 85.0 MB", masterPres.subtitle)
		assertTrue(masterPres.isLossless)

		val hiResQ = MeloQuality(
			id = "q-hires",
			label = "Hi-Res 24bit probed_audio",
			codec = "",
			bitrate = 1412,
			lossless = false,
			availability = "candidate",
			evidence = "catalog_claim",
		)
		val hiResPres = MeloQualityFormatter.formatQuality(hiResQ, baseTrack)
		assertEquals("高解析无损", hiResPres.title)
		assertTrue(hiResPres.isLossless)

		val wavQ = MeloQuality(
			id = "q-wav",
			label = "WAV 无损 resolved engine",
			codec = "",
			bitrate = null,
			lossless = false,
			availability = "preview_only",
			evidence = "probe",
		)
		val wavPres = MeloQualityFormatter.formatQuality(wavQ, baseTrack)
		assertEquals("无损音质 · WAV", wavPres.title)
		assertTrue(wavPres.isLossless)
		assertTrue(wavPres.isPreviewOnly)
		assertFalse(wavPres.needsLogin)

		val highQ = MeloQuality(
			id = "q-high",
			label = "192k AAC 试听",
			codec = "",
			bitrate = 192,
			lossless = false,
			availability = "login_required",
			evidence = "declared",
		)
		val highPres = MeloQualityFormatter.formatQuality(highQ, baseTrack)
		assertEquals("较高音质 · AAC", highPres.title)
		assertEquals("192 kbps · 约 5.5 MB", highPres.subtitle)
		assertFalse(highPres.isLossless)
		assertTrue(highPres.isPreviewOnly)
		assertTrue(highPres.needsLogin)

		val stdQ = MeloQuality(
			id = "q-std",
			label = "标准 OGG",
			codec = "",
			bitrate = 128,
			lossless = false,
			availability = "full",
			evidence = "engine",
		)
		val stdPres = MeloQualityFormatter.formatQuality(stdQ, baseTrack)
		assertEquals("标准音质 · OGG", stdPres.title)
		assertEquals("128 kbps · 约 3.7 MB", stdPres.subtitle)

		val dirtyOnlyQ = MeloQuality(
			id = "q-dirty",
			label = "verified · probed_audio · declared · candidate · engine · resolved · full · probe · catalog_claim",
			codec = "verified",
			bitrate = null,
			lossless = false,
			availability = "verified",
			evidence = "catalog_claim",
		)
		val dirtyPres = MeloQualityFormatter.formatQuality(dirtyOnlyQ, baseTrack)
		assertEquals("标准音质", dirtyPres.title)
		assertEquals("", dirtyPres.subtitle)

		val customLabelQ = MeloQuality(
			id = "q-custom",
			label = "官方臻选 (verified)",
			codec = "mp3",
			bitrate = null,
			lossless = false,
		)
		val customPres = MeloQualityFormatter.formatQuality(customLabelQ, baseTrack)
		assertEquals("官方臻选 · MP3", customPres.title)

		val codecOnlyFallbackQ = MeloQuality(
			id = "q-codec-dup",
			label = "MP3",
			codec = "mp3",
			bitrate = null,
			lossless = false,
		)
		assertEquals("标准音质 · MP3", MeloQualityFormatter.formatQuality(codecOnlyFallbackQ, baseTrack).title)

		val allPresentations = listOf(
			qFlacPres,
			qMp3Pres,
			masterPres,
			hiResPres,
			wavPres,
			highPres,
			stdPres,
			dirtyPres,
			customPres,
		)
		for (pres in allPresentations) {
			val titleLower = pres.title.lowercase()
			val subLower = pres.subtitle.lowercase()
			for (word in internalWords) {
				assertFalse(word in titleLower, "Internal word '$word' leaked into title: '${pres.title}'")
				assertFalse(word in subLower, "Internal word '$word' leaked into subtitle: '${pres.subtitle}'")
			}
		}
	}

	@Test
	fun normalizeBaseUrlNormalizesDomainsLanUrlsSubpathsAndArtifacts() {
		assertEquals("", MeloSettingsStore.normalizeBaseUrl(""))
		assertEquals("", MeloSettingsStore.normalizeBaseUrl("   \n\t  "))
		assertEquals("", MeloSettingsStore.normalizeBaseUrl("https://"))
		assertEquals("", MeloSettingsStore.normalizeBaseUrl("/login"))

		// Plain domains default to https:// while preserving path case
		assertEquals("https://melo.example.com", MeloSettingsStore.normalizeBaseUrl("melo.example.com"))
		assertEquals("https://melo.example.com/Music", MeloSettingsStore.normalizeBaseUrl("Melo.Example.COM/Music/"))
		assertEquals("https://melo.example.com:443", MeloSettingsStore.normalizeBaseUrl("melo.example.com:443/"))

		// LAN / IP / localhost / non-443 ports default to http://
		assertEquals("http://192.168.31.15:8765", MeloSettingsStore.normalizeBaseUrl("192.168.31.15:8765/"))
		assertEquals("http://192.168.1.10", MeloSettingsStore.normalizeBaseUrl("192.168.1.10"))
		assertEquals("http://[::1]", MeloSettingsStore.normalizeBaseUrl("[::1]"))
		assertEquals("http://[::1]:8765", MeloSettingsStore.normalizeBaseUrl("[::1]:8765/"))
		assertEquals("http://localhost", MeloSettingsStore.normalizeBaseUrl("localhost"))
		assertEquals("http://localhost:8765", MeloSettingsStore.normalizeBaseUrl("localhost:8765/"))
		assertEquals("http://nas.local", MeloSettingsStore.normalizeBaseUrl("nas.local/"))
		assertEquals("http://music.lan", MeloSettingsStore.normalizeBaseUrl("music.lan"))
		assertEquals("http://box.example.com:8765", MeloSettingsStore.normalizeBaseUrl("box.example.com:8765"))
		assertEquals("http://example.com:80", MeloSettingsStore.normalizeBaseUrl("example.com:80"))

		// Cleans full-width punctuation, control chars, query, fragment, and trailing web/health suffixes
		assertEquals(
			"http://192.168.31.15:8765",
			MeloSettingsStore.normalizeBaseUrl(" 192.168.31.15：8765／index.html?foo=1#top \n"),
		)
		assertEquals(
			"https://melo.example.com/MeloKeep",
			MeloSettingsStore.normalizeBaseUrl("HTTPS://Melo.Example.COM/MeloKeep/LOGIN/?next=/#sec"),
		)
		assertEquals(
			"https://melo.example.com/subPath",
			MeloSettingsStore.normalizeBaseUrl("melo.example.com/subPath/API/Health?v=2"),
		)
		assertEquals(
			"http://melo.example.com/melokeep",
			MeloSettingsStore.normalizeBaseUrl("http：//melo.example.com／melokeep／Index.html"),
		)
	}

	@Test
	fun probeServerHandlesHttpsFallbackRedirectsHtmlAndDiagnostics() = runTest {
		val store = MeloSettingsStore(InMemorySettings())

		// 1. Plain domain: HTTPS fails with connection error -> auto-falls back to HTTP
		val fallbackRequests = mutableListOf<String>()
		val fallbackEngine = MockEngine { request ->
			val url = request.url.toString()
			fallbackRequests += url
			when (url) {
				"https://vps.example.com/api/health" -> throw RuntimeException("Connection refused")
				"http://vps.example.com/api/health" -> respond(
					content = """{"ok":true,"backend":"go","version":"1.5.0","sources":6}""",
					status = HttpStatusCode.OK,
					headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
				)
				else -> respond("{}", HttpStatusCode.NotFound)
			}
		}
		val fallbackClient = MeloKeepApiClient(store, fallbackEngine)
		val fallbackProbe = fallbackClient.probeServer("vps.example.com")
		assertEquals("http://vps.example.com", fallbackProbe.resolvedBaseUrl)
		assertEquals("1.5.0", fallbackProbe.health.version)
		assertEquals(
			listOf("https://vps.example.com/api/health", "http://vps.example.com/api/health"),
			fallbackRequests,
		)
		fallbackClient.close()

		// 2. HTTP 301 redirect to HTTPS subpath -> resolvedBaseUrl becomes https://.../melokeep
		val redirectEngine = MockEngine { request ->
			val url = request.url.toString()
			when (url) {
				"http://proxy.example.com/api/health" -> respond(
					content = "",
					status = HttpStatusCode.MovedPermanently,
					headers = headersOf(HttpHeaders.Location, "https://proxy.example.com/MeloKeep/api/health"),
				)
				"https://proxy.example.com/MeloKeep/api/health" -> respond(
					content = """{"ok":true,"version":"1.6.0","sources":5}""",
					status = HttpStatusCode.OK,
					headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
				)
				else -> respond("{}", HttpStatusCode.NotFound)
			}
		}
		val redirectClient = MeloKeepApiClient(store, redirectEngine)
		val redirectProbe = redirectClient.probeServer("http://proxy.example.com")
		assertEquals("https://proxy.example.com/MeloKeep", redirectProbe.resolvedBaseUrl)
		assertEquals("1.6.0", redirectProbe.health.version)
		redirectClient.close()

		// 3. HTML response throws clear Chinese diagnostic without downgrading to HTTP
		var htmlFallbackAttempted = false
		val htmlEngine = MockEngine { request ->
			if (request.url.toString().startsWith("http://")) {
				htmlFallbackAttempted = true
			}
			respond(
				content = "<!DOCTYPE html><html><head><title>Login</title></head><body>Web UI</body></html>",
				status = HttpStatusCode.OK,
				headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()),
			)
		}
		val htmlClient = MeloKeepApiClient(store, htmlEngine)
		val htmlErr = assertFailsWith<MeloApiException> {
			htmlClient.probeServer("spa.example.com")
		}
		assertFalse(htmlFallbackAttempted)
		assertTrue(htmlErr.message.contains("返回的是网页而非 MeloKeep API"))
		assertTrue(htmlErr.message.contains("https://spa.example.com/api/health"))
		htmlClient.close()

		// 4. All candidates fail -> keeps primary candidate's SSL/TLS diagnostic error
		val sslEngine = MockEngine { request ->
			val url = request.url.toString()
			if (url.startsWith("https://")) {
				throw RuntimeException("javax.net.ssl.SSLHandshakeException: PKIX path building failed")
			} else {
				throw RuntimeException("Connection refused")
			}
		}
		val sslClient = MeloKeepApiClient(store, sslEngine)
		val sslErr = assertFailsWith<MeloApiException> {
			sslClient.probeServer("selfsigned.example.com")
		}
		assertTrue(sslErr.message.contains("HTTPS 证书校验失败"))
		assertTrue(sslErr.message.contains("https://selfsigned.example.com/api/health"))
		sslClient.close()

		// 5. Case-insensitive baseUrl prefix handling in resolveMediaUrl + refreshHttpClient with factory
		store.saveSession(
			baseUrl = "https://melo.example.com",
			username = "u",
			bearerToken = "b",
			tokenId = "t",
			mediaToken = "mtok",
			mediaTokenExpiresAt = 1900000000L,
			authEnabled = true,
		)
		var engineCreateCount = 0
		val factoryClient = MeloKeepApiClient(
			settingsStore = store,
			httpClientEngineFactory = {
				engineCreateCount++
				MockEngine {
					respond(
						content = """{"ok":true,"version":"1.0.0","sources":1}""",
						status = HttpStatusCode.OK,
						headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
					)
				}
			},
		)
		assertEquals(
			"HTTPS://MELO.EXAMPLE.COM/api/stream/trk-1?media_token=mtok",
			factoryClient.resolveMediaUrl("HTTPS://MELO.EXAMPLE.COM/api/stream/trk-1"),
		)
		assertEquals(1, engineCreateCount)
		factoryClient.refreshHttpClient()
		assertEquals(2, engineCreateCount)
		assertTrue(factoryClient.health().ok)
		factoryClient.close()
	}

	@Test
	fun loginFollowsRedirectsAndPersistsResolvedHttpsBaseUrl() = runTest {
		val store = MeloSettingsStore(InMemorySettings())
		val postUrls = mutableListOf<String>()
		val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

		val engine = MockEngine { request ->
			val url = request.url.toString()
			if (request.method == HttpMethod.Post) {
				postUrls += url
			}
			when (url) {
				"http://vps.example.com/api/auth/login" -> respond(
					content = "",
					status = HttpStatusCode.MovedPermanently,
					headers = headersOf(HttpHeaders.Location, "https://vps.example.com/api/auth/login"),
				)
				"https://vps.example.com/api/auth/login" -> respond(
					content = """{"success":true,"auth_enabled":true,"token":"vps-tok","token_id":"tid-vps","media_token":"mt-vps","media_token_expires_at":1900000000}""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
			}
		}

		val client = MeloKeepApiClient(store, engine)
		val resp = client.login(
			baseUrl = "http://vps.example.com",
			username = "admin",
			password = "secret",
			displayName = "VPS 音乐盒",
		)
		assertTrue(resp.success)
		assertEquals(
			listOf("http://vps.example.com/api/auth/login", "https://vps.example.com/api/auth/login"),
			postUrls,
		)
		assertEquals("https://vps.example.com", store.currentConfig().baseUrl)
		assertEquals("vps-tok", store.currentConfig().bearerToken)
		assertEquals("VPS 音乐盒", store.currentConfig().displayName)
		assertEquals(1, store.profiles.value.size)
		assertEquals("https://vps.example.com", store.profiles.value.single().config.baseUrl)
		client.close()
	}

	@Test
	fun settingsStoreAndControllerSupportMigrationAndMultiProfileSwitching() = runTest {
		// 1. Auto-migration from legacy flat keys
		val backing = InMemorySettings()
		backing.putString("melokeep.base_url", "192.168.1.5:8765/")
		backing.putString("melokeep.username", "nas-user")
		backing.putString("melokeep.bearer_token", "tok-nas")
		backing.putString("melokeep.token_id", "tid-nas")
		backing.putString("melokeep.media_token", "mt-nas")
		backing.putLong("melokeep.media_token_expires_at", 1900000000L)
		backing.putBoolean("melokeep.enabled", true)
		backing.putBoolean("melokeep.auth_enabled", true)
		backing.putString("melokeep.display_name", "家里 NAS")

		val store = MeloSettingsStore(backing)
		assertEquals(1, store.profiles.value.size)
		assertEquals("default", store.activeProfileId.value)
		assertEquals("http://192.168.1.5:8765", store.currentConfig().baseUrl)
		assertEquals("家里 NAS", store.currentConfig().displayName)
		assertTrue(backing.hasKey(MeloSettingsStore.KEY_SERVER_PROFILES))
		assertEquals("default", backing.getString(MeloSettingsStore.KEY_ACTIVE_PROFILE_ID, ""))

		// 2. Controller adds a second server profile with createNew = true without overwriting the first
		val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
		val engine = MockEngine { request ->
			val host = request.url.host
			val path = request.url.encodedPath
			when {
				path == "/api/health" && host == "vps.example.com" -> respond(
					"""{"ok":true,"version":"2.0.0","sources":8}""",
					HttpStatusCode.OK,
					jsonHeaders,
				)
				path == "/api/auth/login" && host == "vps.example.com" -> respond(
					"""{"success":true,"auth_enabled":true,"token":"tok-vps","token_id":"tid-vps","media_token":"mt-vps","media_token_expires_at":1950000000}""",
					HttpStatusCode.OK,
					jsonHeaders,
				)
				path == "/api/config" && host == "vps.example.com" -> respond(
					"""{"sources":["AudiusMusicClient"],"available_sources":[],"results_per_source":30}""",
					HttpStatusCode.OK,
					jsonHeaders,
				)
				path == "/api/config" && host == "192.168.1.5" -> respond(
					"""{"sources":["FMAMusicClient"],"available_sources":[],"results_per_source":15}""",
					HttpStatusCode.OK,
					jsonHeaders,
				)
				path == "/api/auth/devices" -> respond("""{"devices":[]}""", HttpStatusCode.OK, jsonHeaders)
				else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
			}
		}

		val client = MeloKeepApiClient(store, engine)
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		val controller = MeloSettingsController(client, scope)

		controller.testAndLogin(
			baseUrl = "vps.example.com",
			username = "vps-admin",
			password = "secret",
			displayName = "VPS 盒子",
			createNew = true,
		)
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { !it.testingConnection && it.profiles.size == 2 }
			}
		}

		val vpsProfileId = assertNotNull(controller.state.value.activeProfileId)
		assertTrue(vpsProfileId != "default")
		assertEquals("https://vps.example.com", controller.state.value.serverConfig.baseUrl)
		assertEquals("tok-vps", controller.state.value.serverConfig.bearerToken)
		assertEquals("VPS 盒子", controller.state.value.serverConfig.displayName)
		assertEquals(30, controller.state.value.engineConfig?.resultsPerSource)

		// Reloading store preserves both profiles and active selection
		val reloadedStore = MeloSettingsStore(backing)
		assertEquals(2, reloadedStore.profiles.value.size)
		assertEquals(vpsProfileId, reloadedStore.activeProfileId.value)
		assertEquals("tok-nas", reloadedStore.profiles.value.first { it.id == "default" }.config.bearerToken)
		assertEquals("tok-vps", reloadedStore.profiles.value.first { it.id == vpsProfileId }.config.bearerToken)

		// 3. Switch back to "default" profile -> preserves token and reloads NAS config
		controller.switchProfile("default")
		assertEquals("default", controller.state.value.activeProfileId)
		assertEquals("http://192.168.1.5:8765", controller.state.value.serverConfig.baseUrl)
		assertEquals("tok-nas", controller.state.value.serverConfig.bearerToken)
		assertEquals("已切换到：家里 NAS", controller.state.value.noticeMessage)
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { !it.loadingConfig && it.engineConfig?.resultsPerSource == 15 }
			}
		}

		// 4. Delete currently active profile ("default") -> auto-falls back to VPS profile
		controller.deleteProfile("default")
		assertEquals(1, controller.state.value.profiles.size)
		assertEquals(vpsProfileId, controller.state.value.activeProfileId)
		assertEquals("https://vps.example.com", controller.state.value.serverConfig.baseUrl)
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { !it.loadingConfig && it.engineConfig?.resultsPerSource == 30 }
			}
		}

		// 5. Delete last remaining profile -> clears activeProfileId & config cleanly without resurrecting on reload
		controller.deleteProfile(vpsProfileId)
		assertTrue(controller.state.value.profiles.isEmpty())
		assertNull(controller.state.value.activeProfileId)
		assertEquals("", controller.state.value.serverConfig.baseUrl)
		assertTrue(MeloSettingsStore(backing).profiles.value.isEmpty())

		scope.cancel()
		client.close()
	}

	@Test
	fun deleteDownloadSupportsDeleteFileParamLegacyResponsesAndLibraryRefresh() = runTest {
		val store = MeloSettingsStore(InMemorySettings())
		store.saveSession(
			baseUrl = "https://melo.example.com",
			username = "admin",
			bearerToken = "tok-dl",
			tokenId = "tid-dl",
			mediaToken = "",
			mediaTokenExpiresAt = 0L,
			authEnabled = true,
		)

		val recordedUrls = mutableListOf<String>()
		var libraryFetchCount = 0
		var jobsOnServer = listOf(
			MeloDownloadJob(
				id = "dl-1",
				track = MeloTrack(id = "trk-1", title = "Track A", artist = "Artist A"),
				status = "complete",
				file = "/music/Track A.flac",
			),
			MeloDownloadJob(
				id = "dl-2",
				track = MeloTrack(id = "trk-2", title = "Track B", artist = "Artist B"),
				status = "complete",
				file = "/music/Track B.flac",
			),
			MeloDownloadJob(
				id = "dl-3",
				track = MeloTrack(id = "trk-3", title = "Track C", artist = "Artist C"),
				status = "complete",
				file = null,
			),
			MeloDownloadJob(
				id = "dl-4",
				track = MeloTrack(id = "trk-4", title = "Track D", artist = "Artist D"),
				status = "downloading",
				file = null,
			),
		)

		val engine = MockEngine { request ->
			val path = request.url.encodedPath
			val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
			recordedUrls += "${request.method.value} ${request.url}"
			when {
				request.method == HttpMethod.Get && path == "/api/downloads" -> respond(
					content = """
						[
						  {"id":"dl-1","track":{"id":"trk-1","title":"Track A","artist":"Artist A"},"status":"complete","file":"/music/Track A.flac"},
						  {"id":"dl-2","track":{"id":"trk-2","title":"Track B","artist":"Artist B"},"status":"complete","file":"/music/Track B.flac"},
						  {"id":"dl-3","track":{"id":"trk-3","title":"Track C","artist":"Artist C"},"status":"complete","file":null},
						  {"id":"dl-4","track":{"id":"trk-4","title":"Track D","artist":"Artist D"},"status":"downloading","file":null}
						]
					""".trimIndent(),
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Get && path == "/api/library/files" -> {
					libraryFetchCount++
					val itemsJson = if (libraryFetchCount == 1) {
						"""[{"path":"asset:a1","name":"sample.flac","size":1234,"root":"/music","folder":""}]"""
					} else {
						"[]"
					}
					respond(
						content = """{"items":$itemsJson,"next_cursor":null,"index_revision":$libraryFetchCount,"indexing":false,"folders":[]}""",
						status = HttpStatusCode.OK,
						headers = jsonHeaders,
					)
				}
				request.method == HttpMethod.Delete && path == "/api/downloads/dl-keep" -> {
					assertNull(request.url.parameters["delete_file"])
					respond("""{"removed":true}""", HttpStatusCode.OK, jsonHeaders)
				}
				request.method == HttpMethod.Delete && path == "/api/downloads/dl-empty" -> {
					assertEquals("1", request.url.parameters["delete_file"])
					respond("", HttpStatusCode.NoContent)
				}
				request.method == HttpMethod.Delete && path == "/api/downloads/dl-1" -> {
					assertEquals("1", request.url.parameters["delete_file"])
					jobsOnServer = jobsOnServer.filterNot { it.id == "dl-1" }
					respond(
						content = """{"removed":true,"file_deleted":true,"track_ids":["asset:a1"]}""",
						status = HttpStatusCode.OK,
						headers = jsonHeaders,
					)
				}
				request.method == HttpMethod.Delete && path == "/api/downloads/dl-2" -> {
					assertEquals("1", request.url.parameters["delete_file"])
					jobsOnServer = jobsOnServer.filterNot { it.id == "dl-2" }
					respond(
						content = """{"removed":true,"file_deleted":false,"track_ids":[]}""",
						status = HttpStatusCode.OK,
						headers = jsonHeaders,
					)
				}
				request.method == HttpMethod.Delete && path == "/api/downloads/dl-3" -> {
					assertNull(request.url.parameters["delete_file"])
					jobsOnServer = jobsOnServer.filterNot { it.id == "dl-3" }
					respond(
						content = """{"removed":true,"file_deleted":false,"track_ids":[]}""",
						status = HttpStatusCode.OK,
						headers = jsonHeaders,
					)
				}
				else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
			}
		}

		val client = MeloKeepApiClient(store, engine)

		// 1. Direct ApiClient tests for deleteFile=false, empty 204 body, and deleteFile=true
		val keepRes = client.deleteDownload("dl-keep", deleteFile = false)
		assertTrue(keepRes.removed)
		assertFalse(keepRes.fileDeleted)
		assertTrue(keepRes.trackIds.isEmpty())

		val emptyRes = client.deleteDownload("dl-empty", deleteFile = true)
		assertTrue(emptyRes.removed)
		assertFalse(emptyRes.fileDeleted)
		assertTrue(emptyRes.trackIds.isEmpty())

		// 2. Controller tests: delete single with file_deleted=true refreshes cached libraryFiles
		val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		val controller = MeloStudioController(client, controllerScope)
		controller.loadLibraryFiles(reset = true)
		controller.refreshDownloads()
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { it.libraryFiles.size == 1 && it.downloads.size == 4 }
			}
		}

		controller.deleteDownload("dl-1", deleteFile = true)
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { s ->
					s.downloads.none { it.id == "dl-1" } &&
						s.noticeMessage == "已删除下载记录，并删除本地文件" &&
						s.libraryFiles.isEmpty()
				}
			}
		}
		assertEquals(2, libraryFetchCount)

		// 3. clearCompletedDownloads(deleteFiles=true) passes delete_file=1 only when job.file is non-blank
		controller.clearNotice()
		controller.clearCompletedDownloads(deleteFiles = true)
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { s ->
					s.downloads.map { it.id } == listOf("dl-4") &&
						s.noticeMessage?.contains("已清理 2 条") == true
				}
			}
		}
		assertTrue(recordedUrls.any { it == "DELETE https://melo.example.com/api/downloads/dl-2?delete_file=1" })
		assertTrue(recordedUrls.any { it == "DELETE https://melo.example.com/api/downloads/dl-3" })

		controllerScope.cancel()
		client.close()
	}

	@Test
	fun removeTracksFromLocalPlaylistSupportsBatchEndpointAnd404Fallback() = runTest {
		val store = MeloSettingsStore(InMemorySettings())
		store.saveSession(
			baseUrl = "https://melo.example.com",
			username = "admin",
			bearerToken = "tok-pl",
			tokenId = "tid-pl",
			mediaToken = "",
			mediaTokenExpiresAt = 0L,
			authEnabled = true,
		)

		var serverMode = "batch"
		var playlistTracks = listOf(
			MeloTrack(id = "trk-1", source = "local", title = "Track A", artist = "Artist A"),
			MeloTrack(id = "trk-2", source = "local", title = "Track B", artist = "Artist B"),
			MeloTrack(id = "trk-3", source = "local", title = "Track C", artist = "Artist C"),
		)
		val legacyDeletedIds = mutableListOf<String>()

		val engine = MockEngine { request ->
			val path = request.url.encodedPath
			val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
			fun playlistJson(): String {
				val tracksJson = playlistTracks.joinToString(prefix = "[", postfix = "]") { t ->
					"""{"id":"${t.id}","source":"local","title":"${t.title}","artist":"${t.artist}","qualities":[],"playback":{"state":"ready"}}"""
				}
				return """{"id":"pl-1","name":"Test Playlist","source":"custom","tracks":$tracksJson,"detail":"","task_id":"pl-1"}"""
			}
			when {
				request.method == HttpMethod.Get && path == "/api/playlists" -> respond(
					content = """[{"id":"pl-1","name":"Test Playlist","source":"custom","track_count":${playlistTracks.size},"issue_count":0}]""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Get && path == "/api/playlists/pl-1" -> respond(
					content = playlistJson(),
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Post && path == "/api/playlists/pl-1/tracks/remove" -> {
					if (serverMode == "legacy_404") {
						respond("""{"detail":"Not Found"}""", HttpStatusCode.NotFound, jsonHeaders)
					} else {
						val body = meloJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
						val ids = body["track_ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
						assertEquals(listOf("trk-1", "trk-2"), ids)
						playlistTracks = playlistTracks.filterNot { it.id in ids }
						respond(playlistJson(), HttpStatusCode.OK, jsonHeaders)
					}
				}
				request.method == HttpMethod.Delete && path.startsWith("/api/playlists/pl-1/tracks/") -> {
					val tid = path.substringAfterLast('/')
					legacyDeletedIds += tid
					playlistTracks = playlistTracks.filterNot { it.id == tid }
					respond(playlistJson(), HttpStatusCode.OK, jsonHeaders)
				}
				else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
			}
		}

		val client = MeloKeepApiClient(store, engine)
		val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		val controller = MeloLocalLibraryController(client, controllerScope)

		controller.refreshPlaylists()
		controller.loadPlaylistDetail("pl-1")
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { it.playlists.size == 1 && it.activePlaylist?.tracks?.size == 3 }
			}
		}

		// 1. New server batch endpoint deduplicates and filters blank IDs
		controller.removeTracksFromPlaylist("pl-1", listOf("trk-1", "", "   ", "trk-2", "trk-1"))
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { !it.mutating && it.activePlaylist?.tracks?.map { t -> t.id } == listOf("trk-3") }
			}
		}
		assertEquals(1, controller.state.value.playlists.first { it.id == "pl-1" }.trackCount)
		assertEquals("已从歌单移除 2 首歌曲", controller.state.value.noticeMessage)

		// 2. Legacy server 404 triggers fallback loop to single-item DELETE
		serverMode = "legacy_404"
		playlistTracks = listOf(
			MeloTrack(id = "trk-1", source = "local", title = "Track A", artist = "Artist A"),
			MeloTrack(id = "trk-2", source = "local", title = "Track B", artist = "Artist B"),
			MeloTrack(id = "trk-3", source = "local", title = "Track C", artist = "Artist C"),
		)
		val fallbackResult = client.removeTracksFromLocalPlaylist("pl-1", listOf("trk-1", "trk-3", "trk-1", ""))
		assertEquals(listOf("trk-1", "trk-3"), legacyDeletedIds)
		assertEquals(listOf("trk-2"), fallbackResult.tracks.map { it.id })

		controllerScope.cancel()
		client.close()
	}

	@Test
	fun serverAppUpdateProxySettingsFallbackAndErrorPassthroughWork() = runTest {
		val store = MeloSettingsStore(InMemorySettings())
		store.saveSession(
			baseUrl = "https://melo.example.com",
			username = "admin",
			bearerToken = "tok-upd",
			tokenId = "tid-upd",
			mediaToken = "",
			mediaTokenExpiresAt = 0L,
			authEnabled = true,
		)

		var serverMode = "ok"
		var tokenSavedOnServer = false
		val recordedServerRequests = mutableListOf<String>()
		val recordedGithubUrls = mutableListOf<String>()
		val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

		val serverEngine = MockEngine { request ->
			val path = request.url.encodedPath
			recordedServerRequests += "${request.method.value} ${request.url}"
			when {
				request.method == HttpMethod.Get && path == "/api/system/update/settings" -> {
					assertEquals("Bearer tok-upd", request.headers[HttpHeaders.Authorization])
					respond(
						content = if (tokenSavedOnServer) {
							"""{"github_token_configured":true,"github_token_source":"settings","github_token_saved":true}"""
						} else {
							"""{"github_token_configured":false,"github_token_source":"","github_token_saved":false}"""
						},
						status = HttpStatusCode.OK,
						headers = jsonHeaders,
					)
				}
				request.method == HttpMethod.Put && path == "/api/system/update/settings" -> {
					assertEquals("Bearer tok-upd", request.headers[HttpHeaders.Authorization])
					val body = meloJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
					val clearFlag = body["clear_github_token"]?.jsonPrimitive?.contentOrNull == "true"
					tokenSavedOnServer = if (clearFlag) {
						assertNull(body["github_token"])
						false
					} else {
						assertEquals("ghp_server_token_123", body["github_token"]?.jsonPrimitive?.contentOrNull)
						true
					}
					respond(
						content = if (tokenSavedOnServer) {
							"""{"github_token_configured":true,"github_token_source":"settings","github_token_saved":true}"""
						} else {
							"""{"github_token_configured":true,"github_token_source":"env","github_token_saved":false}"""
						},
						status = HttpStatusCode.OK,
						headers = jsonHeaders,
					)
				}
				request.method == HttpMethod.Get && path == "/api/system/update/app" -> {
					assertEquals("Bearer tok-upd", request.headers[HttpHeaders.Authorization])
					val platform = request.url.parameters["platform"]
					when (serverMode) {
						"ok" -> {
							val assetName = if (platform == "ios") "MeloKeep-v2.1.0.ipa" else "MeloKeep-v2.1.0.apk"
							val assetUrl = "https://github.com/Agonie0v0/melokeep/releases/download/v2.1.0/$assetName"
							respond(
								content = """
									{
									  "platform": "$platform",
									  "repo": "Agonie0v0/melokeep",
									  "release": {
									    "tag_name": "v2.1.0",
									    "name": "MeloKeep 2.1.0",
									    "html_url": "https://github.com/Agonie0v0/melokeep/releases/tag/v2.1.0",
									    "body": "Server proxied release",
									    "published_at": "2026-11-25T00:00:00Z",
									    "draft": false,
									    "prerelease": false,
									    "assets": [
									      {
									        "name": "$assetName",
									        "browser_download_url": "$assetUrl",
									        "size": 1234567,
									        "content_type": "application/octet-stream"
									      }
									    ]
									  },
									  "checked_at": "2026-11-25T10:00:00Z",
									  "cached": false,
									  "error": "",
									  "github_token_configured": true,
									  "github_token_source": "settings",
									  "github_token_saved": true
									}
								""".trimIndent(),
								status = HttpStatusCode.OK,
								headers = jsonHeaders,
							)
						}
						"server_error_field" -> respond(
							content = """
								{
								  "platform": "$platform",
								  "repo": "Agonie0v0/melokeep",
								  "release": null,
								  "checked_at": "2026-11-25T10:00:00Z",
								  "cached": false,
								  "error": "GitHub Token 无效或已过期（HTTP 401）",
								  "github_token_configured": true,
								  "github_token_source": "settings",
								  "github_token_saved": true
								}
							""".trimIndent(),
							status = HttpStatusCode.OK,
							headers = jsonHeaders,
						)
						"not_found" -> respond("""{"detail":"Not Found"}""", HttpStatusCode.NotFound, jsonHeaders)
						else -> respond("{}", HttpStatusCode.InternalServerError, jsonHeaders)
					}
				}
				else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
			}
		}

		val fallbackEngine = MockEngine { request ->
			recordedGithubUrls += request.url.toString()
			respond(
				content = """
					[
					  {
					    "tag_name": "v2.0.5",
					    "name": "Direct GitHub 2.0.5",
					    "html_url": "https://github.com/Agonie0v0/melokeep/releases/tag/v2.0.5",
					    "body": "Direct fallback release",
					    "published_at": "2026-11-24T00:00:00Z",
					    "draft": false,
					    "prerelease": false,
					    "assets": [
					      {
					        "name": "MeloKeep-v2.0.5.apk",
					        "browser_download_url": "https://github.com/Agonie0v0/melokeep/releases/download/v2.0.5/MeloKeep-v2.0.5.apk",
					        "size": 999999
					      },
					      {
					        "name": "MeloKeep-v2.0.5.ipa",
					        "browser_download_url": "https://github.com/Agonie0v0/melokeep/releases/download/v2.0.5/MeloKeep-v2.0.5.ipa",
					        "size": 1111111
					      }
					    ]
					  }
					]
				""".trimIndent(),
				status = HttpStatusCode.OK,
				headers = jsonHeaders,
			)
		}

		val client = MeloKeepApiClient(store, serverEngine)
		val fallbackHttpClient = HttpClient(fallbackEngine) { expectSuccess = false }
		val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		val settingsController = MeloSettingsController(client, controllerScope)

		// 1. GET / PUT /api/system/update/settings
		val initialSettings = client.fetchSystemUpdateSettings()
		assertFalse(initialSettings.githubTokenConfigured)
		assertFalse(initialSettings.githubTokenSaved)

		val savedSettings = settingsController.saveSystemUpdateSettings(githubToken = "  ghp_server_token_123  ")
		assertTrue(savedSettings.githubTokenConfigured)
		assertEquals("settings", savedSettings.githubTokenSource)
		assertTrue(savedSettings.githubTokenSaved)

		val fetchedAfterSave = settingsController.fetchSystemUpdateSettings()
		assertTrue(fetchedAfterSave.githubTokenSaved)

		val clearedSettings = client.saveSystemUpdateSettings(clearGitHubToken = true)
		assertTrue(clearedSettings.githubTokenConfigured)
		assertEquals("env", clearedSettings.githubTokenSource)
		assertFalse(clearedSettings.githubTokenSaved)

		// 2. Server-proxied update resolution succeeds (both Android force=true and iOS via controller)
		val androidResolved = client.resolveLatestAppRelease(
			platform = "android",
			force = true,
			fallbackHttpClient = fallbackHttpClient,
		)
		assertTrue(androidResolved.viaServer)
		assertEquals("v2.1.0", androidResolved.release?.tagName)
		assertEquals(true, androidResolved.tokenSettings?.githubTokenSaved)
		assertTrue(recordedServerRequests.any { it == "GET https://melo.example.com/api/system/update/app?platform=android&force=1" })
		assertTrue(recordedGithubUrls.isEmpty())

		val iosResolved = settingsController.resolveIosAppUpdate(force = false)
		assertTrue(iosResolved.viaServer)
		assertEquals("v2.1.0", iosResolved.release?.tagName)
		assertNotNull(iosResolved.release?.findIosIpaAsset())
		assertTrue(recordedServerRequests.any { it == "GET https://melo.example.com/api/system/update/app?platform=ios" })

		// 3. Server error field in 200 payload throws MeloApiException(502, resp.error) without falling back
		serverMode = "server_error_field"
		val serverErr = assertFailsWith<MeloApiException> {
			client.resolveLatestAppRelease(
				platform = "android",
				force = true,
				fallbackHttpClient = fallbackHttpClient,
			)
		}
		assertEquals(502, serverErr.statusCode)
		assertEquals("GitHub Token 无效或已过期（HTTP 401）", serverErr.message)
		assertTrue(recordedGithubUrls.isEmpty())

		// 4. 404 on /api/system/update/app silently falls back to direct MeloAppUpdateChecker
		serverMode = "not_found"
		val fallbackResolved = client.resolveLatestAppRelease(
			platform = "android",
			force = false,
			fallbackHttpClient = fallbackHttpClient,
		)
		assertFalse(fallbackResolved.viaServer)
		assertNull(fallbackResolved.tokenSettings)
		assertEquals("v2.0.5", fallbackResolved.release?.tagName)
		assertEquals(1, recordedGithubUrls.size)

		// 5. Disconnected client goes directly to MeloAppUpdateChecker without calling server
		store.clearSession(keepServerUrl = false)
		recordedServerRequests.clear()
		recordedGithubUrls.clear()
		val directResolved = client.resolveLatestAppRelease(
			platform = "android",
			force = true,
			fallbackHttpClient = fallbackHttpClient,
		)
		assertFalse(directResolved.viaServer)
		assertNull(directResolved.tokenSettings)
		assertEquals("v2.0.5", directResolved.release?.tagName)
		assertTrue(recordedServerRequests.isEmpty())
		assertEquals(1, recordedGithubUrls.size)

		controllerScope.cancel()
		fallbackHttpClient.close()
		client.close()
	}

	@Test
	fun dailyRecommendModelsDeserializeWithDefaultsAndNullCoercion() {
		val dailyJson = """
			{
			  "date": "2026-11-26",
			  "refresh_seq": 2,
			  "target_count": 30,
			  "status": "ready",
			  "job_id": "",
			  "mode": "ai",
			  "curator_note": "晚风与城市霓虹的交界处",
			  "style_tags": ["Dream Pop", "Synthwave"],
			  "generated_at": 1764120000000,
			  "items": [
			    {
			      "track": {
			        "id": "rec-1",
			        "source": "AudiusMusicClient",
			        "source_id": "a1",
			        "title": "Neon Drift",
			        "artist": "City Echo",
			        "album": "Night Drive",
			        "duration": 215.0,
			        "cover": "/api/files/cover?path=rec1",
			        "qualities": [],
			        "playback": {"state": "ready", "url": "/api/stream/rec-1"}
			      },
			      "reason": "贴合你近期常听的复古合成器氛围",
			      "category": "discover",
			      "in_library": false
			    }
			  ]
			}
		""".trimIndent()
		val daily = meloJson.decodeFromString<MeloDailyRecommendation>(dailyJson)
		assertEquals("2026-11-26", daily.date)
		assertEquals(2, daily.refreshSeq)
		assertEquals(30, daily.targetCount)
		assertEquals("ready", daily.status)
		assertEquals("ai", daily.mode)
		assertEquals("晚风与城市霓虹的交界处", daily.curatorNote)
		assertEquals(listOf("Dream Pop", "Synthwave"), daily.styleTags)
		assertEquals(1764120000000L, daily.generatedAt)
		assertEquals(1, daily.items.size)
		assertEquals("rec-1", daily.items[0].track.id)
		assertEquals("discover", daily.items[0].category)
		assertFalse(daily.items[0].inLibrary)

		val nullArraysJson = """
			{
			  "date": "2026-11-26",
			  "status": "empty",
			  "style_tags": null,
			  "items": null
			}
		""".trimIndent()
		val emptyDaily = meloJson.decodeFromString<MeloDailyRecommendation>(nullArraysJson)
		assertEquals("empty", emptyDaily.status)
		assertTrue(emptyDaily.styleTags.isEmpty())
		assertTrue(emptyDaily.items.isEmpty())

		val statusPayload = meloJson.decodeFromString<MeloDailyRecommendStatusPayload>(
			"""{"stage":"matching","message":"正在匹配 12/30","matched":12,"target":30}"""
		)
		assertEquals("matching", statusPayload.stage)
		assertEquals(12, statusPayload.matched)

		val metaPayload = meloJson.decodeFromString<MeloDailyRecommendMetaPayload>(
			"""{"date":"2026-11-26","refresh_seq":1,"mode":"rule","curator_note":"规则精选","style_tags":["City Pop"],"target_count":30}"""
		)
		assertEquals("rule", metaPayload.mode)
		assertEquals(listOf("City Pop"), metaPayload.styleTags)

		val donePayload = meloJson.decodeFromString<MeloDailyRecommendDonePayload>(
			"""{"daily":$dailyJson,"cancelled":false}"""
		)
		assertFalse(donePayload.cancelled)
		assertEquals(1, donePayload.daily.items.size)
	}

	@Test
	fun apiClientDailyRecommendationCachedAndSseStreamWithUrlResolutionWork() = runTest {
		val store = MeloSettingsStore(InMemorySettings())
		store.saveSession(
			baseUrl = "https://melo.example.com",
			username = "admin",
			bearerToken = "tok-rec",
			tokenId = "tid-rec",
			mediaToken = "1800000000.recsig",
			mediaTokenExpiresAt = 1800000000L,
			authEnabled = true,
		)

		val cancelledJobIds = mutableListOf<String>()
		val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

		val engine = MockEngine { request ->
			val path = request.url.encodedPath
			when {
				request.method == HttpMethod.Get && path == "/api/recommendations/daily" -> respond(
					content = """{"date":"2026-11-26","refresh_seq":1,"target_count":30,"status":"ready","mode":"ai","curator_note":"今日私藏","style_tags":["Indie"],"items":[{"track":{"id":"t-cached","source":"local","title":"Cached Song","artist":"Artist","cover":"/api/files/cover?path=c1","qualities":[],"playback":{"state":"ready","url":"/api/files/audio?path=c1"}},"reason":"曲库私藏","category":"familiar","in_library":true}]}""",
					status = HttpStatusCode.OK,
					headers = jsonHeaders,
				)
				request.method == HttpMethod.Post && path == "/api/recommendations/daily/start" -> {
					val body = meloJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
					val force = body["force"]?.jsonPrimitive?.contentOrNull == "true"
					if (!force) {
						respond(
							content = """{"date":"2026-11-26","refresh_seq":1,"target_count":30,"status":"ready","job_id":"","mode":"ai","curator_note":"缓存推荐","style_tags":["Lo-Fi"],"items":[{"track":{"id":"t-ready","source":"AudiusMusicClient","title":"Ready Track","artist":"Artist R","cover":"/api/files/cover?path=r1","qualities":[],"playback":{"state":"ready","url":"/api/stream/t-ready"}},"reason":"昨日余韵","category":"discover","in_library":false}]}""",
							status = HttpStatusCode.OK,
							headers = jsonHeaders,
						)
					} else {
						respond(
							content = """{"date":"2026-11-26","refresh_seq":2,"target_count":30,"status":"generating","job_id":"rec-job-9"}""",
							status = HttpStatusCode.OK,
							headers = jsonHeaders,
						)
					}
				}
				request.method == HttpMethod.Get && path == "/api/recommendations/daily/jobs/rec-job-9/events" -> {
					val sse = buildString {
						append(": keepalive\r\n\r\n")
						append("id: 1\nevent: status\ndata: {\"stage\":\"profiling\",\"message\":\"正在分析听歌品味\",\"matched\":0,\"target\":30}\n\n")
						append("id: 2\nevent: meta\ndata: {\"date\":\"2026-11-26\",\"refresh_seq\":2,\"mode\":\"ai\",\"curator_note\":\"晨间律动\",\"style_tags\":[\"Jazz\",\"Funk\"],\"target_count\":30}\n\n")
						append("id: 3\nevent: item\ndata: {\"index\":0,\"item\":{\"track\":{\"id\":\"t-sse-1\",\"source\":\"AudiusMusicClient\",\"title\":\"Sunrise Groove\",\"artist\":\"Brass Lab\",\"cover\":\"/api/files/cover?path=s1\",\"qualities\":[],\"playback\":{\"state\":\"ready\",\"url\":\"/api/stream/t-sse-1\"}},\"reason\":\"晨间唤醒\",\"category\":\"discover\",\"in_library\":false}}\n\n")
						append("id: 4\nevent: error\ndata: {\"detail\":\"部分源超时\"}\n\n")
						append("id: 5\nevent: done\ndata: {\"cancelled\":false,\"daily\":{\"date\":\"2026-11-26\",\"refresh_seq\":2,\"target_count\":30,\"status\":\"ready\",\"mode\":\"ai\",\"curator_note\":\"晨间律动\",\"style_tags\":[\"Jazz\",\"Funk\"],\"items\":[{\"track\":{\"id\":\"t-sse-1\",\"source\":\"AudiusMusicClient\",\"title\":\"Sunrise Groove\",\"artist\":\"Brass Lab\",\"cover\":\"/api/files/cover?path=s1\",\"qualities\":[],\"playback\":{\"state\":\"ready\",\"url\":\"/api/stream/t-sse-1\"}},\"reason\":\"晨间唤醒\",\"category\":\"discover\",\"in_library\":false}]}}\n\n")
					}
					respond(
						content = sse,
						status = HttpStatusCode.OK,
						headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
					)
				}
				request.method == HttpMethod.Delete && path == "/api/recommendations/daily/jobs/rec-job-9" -> {
					cancelledJobIds += "rec-job-9"
					respond("""{"cancelled":true}""", HttpStatusCode.OK, jsonHeaders)
				}
				else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
			}
		}

		val client = MeloKeepApiClient(store, engine)

		val snapshot = client.getDailyRecommendation()
		assertEquals("ready", snapshot.status)
		assertEquals(
			"https://melo.example.com/api/files/cover?path=c1&media_token=1800000000.recsig",
			snapshot.items.single().track.cover,
		)
		assertEquals(
			"https://melo.example.com/api/files/audio?path=c1&media_token=1800000000.recsig",
			snapshot.items.single().track.playback.url,
		)

		val cached = client.startDailyRecommendation(force = false)
		assertEquals("ready", cached.status)
		assertEquals("", cached.jobId)
		assertEquals(
			"https://melo.example.com/api/stream/t-ready?media_token=1800000000.recsig",
			cached.items.single().track.playback.url,
		)

		val started = client.startDailyRecommendation(
			force = true,
			seeds = listOf(MeloRecommendSeed(title = "Blue Train", artist = "John Coltrane", weight = 1.5)),
		)
		assertEquals("generating", started.status)
		assertEquals("rec-job-9", started.jobId)

		val events = client.streamDailyRecommendationEvents(started.jobId).toList()
		assertEquals(5, events.size)
		assertEquals("正在分析听歌品味", assertIs<MeloDailyRecommendEvent.Status>(events[0]).payload.message)
		assertEquals(listOf("Jazz", "Funk"), assertIs<MeloDailyRecommendEvent.Meta>(events[1]).payload.styleTags)
		val itemEv = assertIs<MeloDailyRecommendEvent.Item>(events[2])
		assertEquals(
			"https://melo.example.com/api/stream/t-sse-1?media_token=1800000000.recsig",
			itemEv.payload.item.track.playback.url,
		)
		assertEquals("部分源超时", assertIs<MeloDailyRecommendEvent.Error>(events[3]).detail)
		val doneEv = assertIs<MeloDailyRecommendEvent.Done>(events[4])
		assertEquals(
			"https://melo.example.com/api/files/cover?path=s1&media_token=1800000000.recsig",
			doneEv.payload.daily.items.single().track.cover,
		)

		client.cancelDailyRecommendationJob("rec-job-9")
		assertEquals(listOf("rec-job-9"), cancelledJobIds)
		client.close()
	}

	@Test
	fun dailyRecommendControllerHandlesCacheStreamForceStopDownloadAndHistory() = runTest {
		val store = MeloSettingsStore(InMemorySettings())
		store.saveSession(
			baseUrl = "https://melo.example.com",
			username = "admin",
			bearerToken = "tok-ctrl",
			tokenId = "tid-ctrl",
			mediaToken = "1800000000.sig",
			mediaTokenExpiresAt = 1800000000L,
			authEnabled = true,
		)

		val recordedHistoryTitles = mutableListOf<String>()
		val recordedDownloadIds = mutableListOf<String>()
		val cancelledJobs = mutableListOf<String>()
		val startForces = mutableListOf<Boolean>()
		val startSeedsCount = mutableListOf<Int>()
		var historyShouldFail = false
		val firstHistoryDone = CompletableDeferred<Unit>()
		val failedHistoryDone = CompletableDeferred<Unit>()
		val slowStreamGate = CompletableDeferred<Unit>()
		val slowStreamStarted = CompletableDeferred<Unit>()
		val stopDeleteDone = CompletableDeferred<Unit>()
		val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

		val engine = MockEngine { request ->
			val path = request.url.encodedPath
			when {
				request.method == HttpMethod.Post && path == "/api/recommendations/daily/start" -> {
					val body = meloJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
					val force = body["force"]?.jsonPrimitive?.contentOrNull == "true"
					val seedsCount = body["seeds"]?.jsonArray?.size ?: 0
					startForces += force
					startSeedsCount += seedsCount
					if (!force) {
						respond(
							content = """{"date":"2026-11-26","refresh_seq":1,"target_count":30,"status":"ready","job_id":"","mode":"rule","curator_note":"今日缓存歌单","style_tags":["Acoustic"],"items":[{"track":{"id":"tr-cached","source":"AudiusMusicClient","title":"Warm Morning","artist":"Folk Trio","qualities":[],"playback":{"state":"ready","url":"/api/stream/tr-cached"}},"reason":"规则推荐","category":"discover","in_library":false},{"track":{"id":"tr-local","source":"local","title":"Local Song","artist":"Local Artist","qualities":[],"playback":{"state":"ready","url":"/api/files/audio?path=l1"}},"reason":"曲库熟悉","category":"familiar","in_library":true}]}""",
							status = HttpStatusCode.OK,
							headers = jsonHeaders,
						)
					} else {
						val jobId = if (startForces.count { it } == 1) "job-stream-1" else "job-stop-2"
						respond(
							content = """{"date":"2026-11-26","refresh_seq":2,"target_count":30,"status":"generating","job_id":"$jobId"}""",
							status = HttpStatusCode.OK,
							headers = jsonHeaders,
						)
					}
				}
				request.method == HttpMethod.Get && path == "/api/recommendations/daily/jobs/job-stream-1/events" -> {
					val sse = buildString {
						append("event: status\ndata: {\"stage\":\"matching\",\"message\":\"已匹配 1/30\",\"matched\":1,\"target\":30}\n\n")
						append("event: meta\ndata: {\"date\":\"2026-11-26\",\"refresh_seq\":2,\"mode\":\"ai\",\"curator_note\":\"夜色与电子节拍\",\"style_tags\":[\"Electronic\",\"Chill\"],\"target_count\":30}\n\n")
						append("event: item\ndata: {\"index\":0,\"item\":{\"track\":{\"id\":\"tr-new-1\",\"source\":\"AudiusMusicClient\",\"title\":\"Night Pulse\",\"artist\":\"Synth Duo\",\"qualities\":[],\"playback\":{\"state\":\"ready\",\"url\":\"/api/stream/tr-new-1\"}},\"reason\":\"夜听偏爱\",\"category\":\"discover\",\"in_library\":false}}\n\n")
						append("event: item\ndata: {\"index\":0,\"item\":{\"track\":{\"id\":\"tr-new-1\",\"source\":\"AudiusMusicClient\",\"title\":\"Night Pulse\",\"artist\":\"Synth Duo\",\"qualities\":[],\"playback\":{\"state\":\"ready\",\"url\":\"/api/stream/tr-new-1\"}},\"reason\":\"夜听偏爱\",\"category\":\"discover\",\"in_library\":false}}\n\n")
						append("event: done\ndata: {\"cancelled\":false,\"daily\":{\"date\":\"2026-11-26\",\"refresh_seq\":2,\"target_count\":30,\"status\":\"ready\",\"mode\":\"ai\",\"curator_note\":\"夜色与电子节拍\",\"style_tags\":[\"Electronic\",\"Chill\"],\"items\":[{\"track\":{\"id\":\"tr-new-1\",\"source\":\"AudiusMusicClient\",\"title\":\"Night Pulse\",\"artist\":\"Synth Duo\",\"qualities\":[],\"playback\":{\"state\":\"ready\",\"url\":\"/api/stream/tr-new-1\"}},\"reason\":\"夜听偏爱\",\"category\":\"discover\",\"in_library\":false}]}}\n\n")
					}
					respond(
						content = sse,
						status = HttpStatusCode.OK,
						headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
					)
				}
				request.method == HttpMethod.Get && path == "/api/recommendations/daily/jobs/job-stop-2/events" -> {
					val channel = ByteChannel(autoFlush = true)
					slowStreamStarted.complete(Unit)
					val sseWriterScope = CoroutineScope(Dispatchers.IO)
					sseWriterScope.launch {
						channel.writeStringUtf8("event: status\ndata: {\"stage\":\"ai\",\"message\":\"正在生成灵感\",\"matched\":0,\"target\":30}\n\n")
						slowStreamGate.await()
						channel.close()
					}
					respond(
						content = channel,
						status = HttpStatusCode.OK,
						headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
					)
				}
				request.method == HttpMethod.Delete && path.startsWith("/api/recommendations/daily/jobs/") -> {
					cancelledJobs += path.substringAfterLast('/')
					slowStreamGate.complete(Unit)
					stopDeleteDone.complete(Unit)
					respond("""{"cancelled":true}""", HttpStatusCode.OK, jsonHeaders)
				}
				request.method == HttpMethod.Post && path == "/api/downloads" -> {
					val arr = meloJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonArray
					val ids = arr.mapNotNull { it.jsonObject["track_id"]?.jsonPrimitive?.contentOrNull }
					recordedDownloadIds += ids
					val respJson = ids.joinToString(prefix = "[", postfix = "]") { tid ->
						"""{"id":"dl-$tid","track":{"id":"$tid","title":"Downloaded"},"status":"queued"}"""
					}
					respond(respJson, HttpStatusCode.OK, jsonHeaders)
				}
				request.method == HttpMethod.Post && path == "/api/recommendations/history" -> {
					if (historyShouldFail) {
						failedHistoryDone.complete(Unit)
						respond("""{"detail":"server error"}""", HttpStatusCode.InternalServerError, jsonHeaders)
					} else {
						val body = meloJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
						recordedHistoryTitles += body["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
						firstHistoryDone.complete(Unit)
						respond("""{"ok":true,"count":${recordedHistoryTitles.size}}""", HttpStatusCode.OK, jsonHeaders)
					}
				}
				else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
			}
		}

		val client = MeloKeepApiClient(store, engine)
		val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		val controller = MeloDailyRecommendController(client, controllerScope)

		// 1. Cached ready path (force = false)
		controller.loadOrGenerate(force = false)
		val cachedState = withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { !it.loading && it.items.size == 2 }
			}
		}
		assertEquals("rule", cachedState.mode)
		assertEquals("今日缓存歌单", cachedState.curatorNote)
		assertEquals(listOf("Acoustic"), cachedState.styleTags)
		assertFalse(cachedState.streaming)

		// 2. Force refresh path with seedsProvider & SSE deduplication
		controller.loadOrGenerate(force = true) {
			listOf(MeloRecommendSeed(title = "Seed Track", artist = "Seed Artist", weight = 2.0))
		}
		val streamedState = withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { !it.loading && !it.streaming && it.refreshSeq == 2 && it.items.size == 1 }
			}
		}
		assertEquals("ai", streamedState.mode)
		assertEquals("夜色与电子节拍", streamedState.curatorNote)
		assertEquals(listOf("Electronic", "Chill"), streamedState.styleTags)
		assertEquals("tr-new-1", streamedState.items.single().track.id)
		assertEquals(listOf(false, true), startForces)
		assertEquals(listOf(0, 1), startSeedsCount)

		// 3. enqueueDownloadToLibrary filters out local/navidrome/blank tracks
		controller.enqueueDownloadToLibrary(
			listOf(
				MeloTrack(id = "", source = "AudiusMusicClient", title = "Blank"),
				MeloTrack(id = "loc-1", source = "local", title = "Local"),
				MeloTrack(id = "nd-1", source = "navidrome", title = "Navidrome"),
				streamedState.items.single().track,
			)
		)
		val enqueuedState = withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { "tr-new-1" in it.enqueuedTrackIds && it.noticeMessage != null }
			}
		}
		assertEquals(listOf("tr-new-1"), recordedDownloadIds)
		assertTrue(enqueuedState.noticeMessage!!.contains("Night Pulse"))
		controller.clearNotice()
		assertNull(controller.state.value.noticeMessage)

		// 4. reportListeningHistory succeeds and silently ignores blank title / server failures
		controller.reportListeningHistory(MeloListeningHistoryRequest(title = "   "))
		controller.reportListeningHistory(
			MeloListeningHistoryRequest(
				trackId = "tr-new-1",
				title = "Night Pulse",
				artist = "Synth Duo",
				source = "melokeep",
				durationMs = 200_000L,
				playedMs = 95_000L,
			)
		)
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				firstHistoryDone.await()
			}
		}
		historyShouldFail = true
		controller.reportListeningHistory(
			MeloListeningHistoryRequest(title = "Failure Ignored", artist = "Tester")
		)
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				failedHistoryDone.await()
			}
		}
		assertEquals(listOf("Night Pulse"), recordedHistoryTitles)
		assertNull(controller.state.value.errorMessage)

		// 5. stopGeneration cancels active coroutine and calls DELETE /api/recommendations/daily/jobs/{jobId}
		controller.loadOrGenerate(force = true)
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				slowStreamStarted.await()
				controller.state.first {
					it.streaming && it.activeJobId == "job-stop-2" && it.stageMessage == "正在生成灵感"
				}
			}
		}
		controller.stopGeneration()
		withContext(Dispatchers.Default) {
			withTimeout(5_000L) {
				controller.state.first { !it.streaming && it.activeJobId == null }
				stopDeleteDone.await()
			}
		}
		assertEquals(listOf("job-stop-2"), cancelledJobs)

		controllerScope.cancel()
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