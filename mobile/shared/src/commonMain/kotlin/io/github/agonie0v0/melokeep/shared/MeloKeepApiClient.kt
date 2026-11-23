package io.github.agonie0v0.melokeep.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.LineEnding
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock

internal val meloJson = Json {
	ignoreUnknownKeys = true
	isLenient = true
	coerceInputValues = true
	encodeDefaults = true
	explicitNulls = false
}

class MeloKeepApiClient(
	val settingsStore: MeloSettingsStore,
	httpClientEngine: HttpClientEngine? = null,
	private val nowEpochSeconds: () -> Long = { Clock.System.now().epochSeconds },
	private val httpClientEngineFactory: (() -> HttpClientEngine)? = null,
) {
	private val fixedEngine: HttpClientEngine? = httpClientEngine

	@kotlin.concurrent.Volatile
	private var httpClient: HttpClient = buildHttpClient()

	private fun buildHttpClient(): HttpClient {
		val engine = httpClientEngineFactory?.invoke() ?: fixedEngine ?: createPlatformHttpClientEngine()
		return HttpClient(engine) {
			expectSuccess = false
			install(ContentNegotiation) {
				json(meloJson)
			}
			install(HttpTimeout) {
				connectTimeoutMillis = 15_000L
				socketTimeoutMillis = 120_000L
				requestTimeoutMillis = 120_000L
			}
		}
	}

	fun refreshHttpClient() {
		val previous = httpClient
		httpClient = buildHttpClient()
		if (fixedEngine == null || httpClientEngineFactory != null) {
			runCatching { previous.close() }
		}
	}

	fun close() {
		httpClient.close()
	}

	suspend fun probeServer(baseUrl: String? = null): MeloProbeResult {
		val rawInput = (baseUrl ?: settingsStore.currentConfig().baseUrl)
			.replace('\uFF1A', ':')
			.replace('\uFF0F', '/')
			.trim()
		val normalized = MeloSettingsStore.normalizeBaseUrl(rawInput)
		if (normalized.isEmpty()) {
			throw MeloApiException(
				0,
				if (baseUrl != null) "请输入 MeloKeep 服务器地址" else "尚未配置 MeloKeep 服务器地址",
			)
		}
		val hasExplicitScheme = rawInput.contains("://")
		val candidates = if (!hasExplicitScheme && normalized.startsWith("https://")) {
			listOf(normalized, "http://" + normalized.removePrefix("https://"))
		} else {
			listOf(normalized)
		}

		var primaryFailure: MeloApiException? = null
		for ((index, candidateBase) in candidates.withIndex()) {
			try {
				return probeSingleCandidate(candidateBase)
			} catch (e: CancellationException) {
				throw e
			} catch (e: MeloApiException) {
				// Non-zero HTTP status indicates an HTTP server responded; do not downgrade HTTPS to HTTP.
				if (e.statusCode > 0) {
					throw e
				}
				if (primaryFailure == null) {
					primaryFailure = e
				}
				if (index == candidates.lastIndex) {
					throw primaryFailure
				}
			} catch (e: Throwable) {
				val url = "$candidateBase/api/health"
				val wrapped = MeloApiException(
					statusCode = 0,
					message = buildNetworkErrorMessage(url, e),
					detail = buildTechnicalNetworkDetail(url, e),
				)
				if (primaryFailure == null) {
					primaryFailure = wrapped
				}
				if (index == candidates.lastIndex) {
					throw primaryFailure
				}
			}
		}
		throw primaryFailure ?: MeloApiException(0, "无法连接服务器 ($normalized/api/health)")
	}

	private suspend fun probeSingleCandidate(candidateBase: String): MeloProbeResult {
		var currentUrl = "$candidateBase/api/health"
		var response: HttpResponse? = null
		for (hop in 0..MAX_REDIRECT_HOPS) {
			val res = try {
				httpClient.get(currentUrl) {
					applyMeloHeaders()
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				throw MeloApiException(
					statusCode = 0,
					message = buildNetworkErrorMessage(currentUrl, e),
					detail = buildTechnicalNetworkDetail(currentUrl, e),
				)
			}
			val statusCode = res.status.value
			if (statusCode in REDIRECT_STATUS_CODES) {
				val location = res.headers[HttpHeaders.Location]?.trim()
				if (!location.isNullOrBlank() && hop < MAX_REDIRECT_HOPS) {
					currentUrl = resolveRedirectUrl(currentUrl, location)
					continue
				}
			}
			response = res
			break
		}

		val finalResponse = response ?: throw MeloApiException(0, "重定向次数过多 ($currentUrl)")
		val finalUrl = finalResponse.call.request.url.toString().ifBlank { currentUrl }
		val statusCode = finalResponse.status.value
		val bodyText = try {
			finalResponse.bodyAsText()
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			throw MeloApiException(
				statusCode = 0,
				message = buildNetworkErrorMessage(finalUrl, e),
				detail = buildTechnicalNetworkDetail(finalUrl, e),
			)
		}

		if (statusCode in REDIRECT_STATUS_CODES) {
			throw MeloApiException(
				statusCode = statusCode,
				message = parseApiErrorMessage(bodyText, statusCode, finalResponse.headers[HttpHeaders.Location]),
			)
		}

		if (isHtmlBody(bodyText)) {
			if (statusCode in 500..599) {
				throw MeloApiException(
					statusCode = statusCode,
					message = "反代或网关返回错误页面 (HTTP $statusCode)，请检查反代配置或后端运行状态",
				)
			}
			throw MeloApiException(
				statusCode = statusCode,
				message = "地址 ($finalUrl) 返回的是网页而非 MeloKeep API，请检查反代路径是否正确映射",
			)
		}

		if (!finalResponse.status.isSuccess()) {
			throw MeloApiException(
				statusCode = statusCode,
				message = parseApiErrorMessage(bodyText, statusCode, finalResponse.headers[HttpHeaders.Location]),
			)
		}

		val info = try {
			if (bodyText.isBlank()) {
				MeloHealthInfo()
			} else {
				meloJson.decodeFromString<MeloHealthInfo>(bodyText)
			}
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			throw MeloApiException(
				statusCode = statusCode,
				message = "地址 ($finalUrl) 返回数据格式无效，请确认是否为 MeloKeep 服务",
				detail = e.message ?: "JSON decode error",
			)
		}

		if (!info.ok) {
			throw MeloApiException(
				statusCode = statusCode,
				message = "服务器返回异常健康状态 ($finalUrl)",
			)
		}

		val resolvedBaseUrl = MeloSettingsStore.normalizeBaseUrl(finalUrl).ifBlank { candidateBase }
		return MeloProbeResult(
			health = info,
			resolvedBaseUrl = resolvedBaseUrl,
		)
	}

	suspend fun health(baseUrl: String? = null): MeloHealthInfo = probeServer(baseUrl).health

	suspend fun fetchLatestGitHubRelease(
		repo: String = MeloAppUpdateChecker.DEFAULT_REPO,
	): MeloGitHubRelease = MeloAppUpdateChecker.fetchLatestRelease(httpClient, repo)

	suspend fun fetchLatestAndroidGitHubRelease(
		repo: String = MeloAppUpdateChecker.DEFAULT_REPO,
	): MeloGitHubRelease = MeloAppUpdateChecker.fetchLatestAndroidRelease(httpClient, repo)

	suspend fun fetchLatestIosGitHubRelease(
		repo: String = MeloAppUpdateChecker.DEFAULT_REPO,
	): MeloGitHubRelease = MeloAppUpdateChecker.fetchLatestIosRelease(httpClient, repo)

	suspend fun fetchLatestAndroidGitHubReleaseOrNull(
		repo: String = MeloAppUpdateChecker.DEFAULT_REPO,
	): MeloGitHubRelease? = MeloAppUpdateChecker.fetchLatestAndroidReleaseOrNull(httpClient, repo)

	suspend fun fetchLatestIosGitHubReleaseOrNull(
		repo: String = MeloAppUpdateChecker.DEFAULT_REPO,
	): MeloGitHubRelease? = MeloAppUpdateChecker.fetchLatestIosReleaseOrNull(httpClient, repo)

	suspend fun authStatus(baseUrl: String? = null, bearerToken: String? = null): MeloAuthStatus {
		val url = buildUrl("/api/auth/status", baseUrl)
		val response = httpClient.get(url) {
			applyMeloHeaders(tokenOverride = bearerToken)
		}
		return decodeResponse(response)
	}

	suspend fun login(
		baseUrl: String,
		username: String = "",
		password: String = "",
		deviceName: String = "MeloKeep Mobile",
		displayName: String? = null,
		profileId: String? = null,
		createNew: Boolean = false,
	): MeloLoginResponse {
		val normalizedBase = MeloSettingsStore.normalizeBaseUrl(baseUrl)
		if (normalizedBase.isEmpty()) {
			throw MeloApiException(0, "请输入 MeloKeep 服务器地址")
		}
		var currentBase = normalizedBase
		var currentUrl = "$currentBase/api/auth/login"
		var finalResponse: HttpResponse? = null

		for (hop in 0..MAX_REDIRECT_HOPS) {
			val res = try {
				httpClient.post(currentUrl) {
					applyMeloHeaders(tokenOverride = "")
					contentType(ContentType.Application.Json)
					setBody(
						MeloLoginRequest(
							username = username,
							password = password,
							clientType = "app",
							deviceName = deviceName.ifBlank { "MeloKeep Mobile" },
						)
					)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				throw MeloApiException(
					statusCode = 0,
					message = buildNetworkErrorMessage(currentUrl, e),
					detail = buildTechnicalNetworkDetail(currentUrl, e),
				)
			}

			val statusCode = res.status.value
			if (statusCode in LOGIN_REDIRECT_STATUS_CODES) {
				val location = res.headers[HttpHeaders.Location]?.trim()
				if (!location.isNullOrBlank() && hop < MAX_REDIRECT_HOPS) {
					val resolvedUrl = resolveRedirectUrl(currentUrl, location)
					val (nextBase, nextPostUrl) = resolveLoginRedirectTarget(resolvedUrl, currentBase)
					currentBase = nextBase
					currentUrl = nextPostUrl
					continue
				}
			}
			finalResponse = res
			break
		}

		val response = finalResponse ?: throw MeloApiException(0, "登录重定向次数过多 ($currentUrl)")
		val actualUrl = response.call.request.url.toString().ifBlank { currentUrl }
		val effectiveBaseUrl = extractBaseUrlFromLoginUrl(actualUrl) ?: currentBase

		val loginResp: MeloLoginResponse = decodeResponse(response)
		if (!loginResp.success) {
			throw MeloApiException(response.status.value, "登录失败，请检查账号或密码")
		}
		settingsStore.saveSession(
			baseUrl = effectiveBaseUrl,
			username = if (loginResp.authEnabled) username else "",
			bearerToken = loginResp.token,
			tokenId = loginResp.tokenId,
			mediaToken = loginResp.mediaToken,
			mediaTokenExpiresAt = loginResp.mediaTokenExpiresAt,
			authEnabled = loginResp.authEnabled,
			enabled = true,
			displayName = displayName,
			profileId = profileId,
			createNew = createNew,
		)
		return loginResp
	}

	suspend fun logout() {
		val cfg = settingsStore.currentConfig()
		if (cfg.baseUrl.isNotBlank()) {
			runCatching {
				val response = httpClient.post("${cfg.baseUrl}/api/auth/logout") {
					applyMeloHeaders(tokenOverride = cfg.bearerToken)
				}
				if (response.status.isSuccess()) {
					response.bodyAsText()
				}
			}
		}
		settingsStore.clearSession(keepServerUrl = true)
	}

	suspend fun refreshMediaTokenIfNeeded(force: Boolean = false): String {
		val cfg = settingsStore.currentConfig()
		if (!cfg.enabled || cfg.baseUrl.isBlank()) {
			return ""
		}
		val now = nowEpochSeconds()
		if (!force && cfg.mediaToken.isNotBlank() && cfg.mediaTokenExpiresAt - now > MEDIA_TOKEN_REFRESH_BUFFER_SECONDS) {
			return cfg.mediaToken
		}
		if (cfg.bearerToken.isNotBlank()) {
			val response = httpClient.post("${cfg.baseUrl}/api/auth/media-token") {
				applyMeloHeaders(tokenOverride = cfg.bearerToken)
			}
			val refreshed: MeloMediaTokenResponse = decodeResponse(response)
			settingsStore.updateMediaToken(refreshed.mediaToken, refreshed.expiresAt)
			return refreshed.mediaToken
		}
		val status = authStatus(cfg.baseUrl, cfg.bearerToken)
		if (status.mediaToken.isNotBlank()) {
			settingsStore.updateMediaToken(status.mediaToken, status.mediaTokenExpiresAt)
			return status.mediaToken
		}
		return ""
	}

	suspend fun listDevices(): List<MeloDeviceToken> {
		val response = httpClient.get(buildUrl("/api/auth/devices")) {
			applyMeloHeaders()
		}
		return decodeResponse<MeloDevicesResponse>(response).devices
	}

	suspend fun revokeDevice(deviceId: String): Boolean {
		val response = httpClient.delete(buildUrl("/api/auth/devices/${deviceId.encodeURLPathPart()}")) {
			applyMeloHeaders()
		}
		ensureSuccess(response)
		return true
	}

	fun resolveMediaUrl(rawPathOrUrl: String?): String? {
		val trimmed = rawPathOrUrl?.trim() ?: return null
		if (trimmed.isEmpty()) return null
		val cfg = settingsStore.currentConfig()
		val base = cfg.baseUrl.trimEnd('/')

		val hasKnownScheme = trimmed.startsWith("http://", ignoreCase = true) ||
			trimmed.startsWith("https://", ignoreCase = true)
		val hasNonHttpScheme = trimmed.startsWith("data:", ignoreCase = true) ||
			trimmed.startsWith("blob:", ignoreCase = true) ||
			trimmed.startsWith("file:", ignoreCase = true) ||
			trimmed.startsWith("content:", ignoreCase = true) ||
			trimmed.startsWith("asset:", ignoreCase = true) ||
			trimmed.startsWith("capacitor:", ignoreCase = true)

		if (hasNonHttpScheme) {
			return trimmed
		}

		val isRelativeApiOrDownload = trimmed.startsWith("/api/") ||
			trimmed.startsWith("/downloads/") ||
			trimmed.startsWith("api/") ||
			trimmed.startsWith("downloads/")

		if (!hasKnownScheme && !isRelativeApiOrDownload) {
			// Preserve bare IDs (e.g. Subsonic coverArtId) untouched.
			return trimmed
		}

		val fullUrl = when {
			hasKnownScheme -> trimmed
			base.isNotEmpty() -> {
				val normalizedPath = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
				"$base$normalizedPath"
			}
			else -> if (trimmed.startsWith("/")) trimmed else "/$trimmed"
		}

		val isOwnServerUrl = if (hasKnownScheme) {
			base.isNotEmpty() &&
				fullUrl.startsWith(base, ignoreCase = true) &&
				(fullUrl.length == base.length || fullUrl[base.length] in charArrayOf('/', '?', '#'))
		} else {
			true
		}
		if (!isOwnServerUrl || cfg.mediaToken.isBlank()) {
			return fullUrl
		}

		val relativePathPart = if (hasKnownScheme && base.isNotEmpty()) {
			if (fullUrl.startsWith(base, ignoreCase = true)) {
				fullUrl.substring(base.length)
			} else {
				fullUrl.removePrefix(base)
			}
		} else {
			if (trimmed.startsWith("/")) trimmed else "/$trimmed"
		}
		val pathOnly = relativePathPart.substringBefore('?').substringBefore('#')
		if (!isProtectedMediaPath(pathOnly)) {
			return fullUrl
		}
		if (hasQueryParam(fullUrl, "media_token")) {
			return fullUrl
		}
		return appendQueryParam(fullUrl, "media_token", cfg.mediaToken)
	}

	fun searchStream(
		keyword: String,
		sources: List<String> = emptyList(),
		refresh: Boolean = false,
		limit: Int? = null,
	): Flow<MeloSearchEvent> = channelFlow {
		val trimmedKeyword = keyword.trim()
		if (trimmedKeyword.isEmpty()) {
			return@channelFlow
		}
		val base = requireBaseUrl()
		val createResp = httpClient.post("$base/api/search/jobs") {
			applyMeloHeaders()
			contentType(ContentType.Application.Json)
			setBody(
				MeloSearchCreateRequest(
					keyword = trimmedKeyword,
					sources = sources,
					refresh = refresh,
					limit = limit,
				)
			)
		}
		val jobId = decodeResponse<MeloSearchCreateResponse>(createResp).id
		if (jobId.isBlank()) {
			throw MeloApiException(createResp.status.value, "创建搜索任务失败：未返回任务 ID")
		}

		try {
			httpClient.prepareGet("$base/api/search/jobs/${jobId.encodeURLPathPart()}/events") {
				applyMeloHeaders()
				header(HttpHeaders.Accept, "text/event-stream")
				timeout {
					requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
					socketTimeoutMillis = 120_000L
				}
			}.execute { response ->
				if (!response.status.isSuccess()) {
					val errBody = runCatching { response.bodyAsText() }.getOrDefault("")
					throw MeloApiException(
						response.status.value,
						parseApiErrorMessage(errBody, response.status.value, response.headers[HttpHeaders.Location]),
					)
				}
				val channel = response.bodyAsChannel()
				var shouldStop = false
				parseSseChannel(channel) { rawEvent ->
					if (shouldStop) return@parseSseChannel
					val resolvedEvent = when (rawEvent) {
						is MeloSearchEvent.Candidate -> rawEvent.copy(track = rawEvent.track.withResolvedUrls())
						is MeloSearchEvent.Track -> rawEvent.copy(track = rawEvent.track.withResolvedUrls())
						else -> rawEvent
					}
					send(resolvedEvent)
					if (resolvedEvent is MeloSearchEvent.Done) {
						shouldStop = true
					}
				}
			}
		} finally {
			withContext(NonCancellable) {
				runCatching { deleteSearchJob(jobId) }
			}
		}
	}.flowOn(Dispatchers.IO)

	suspend fun cancelSearchJob(jobId: String) {
		deleteSearchJob(jobId)
	}

	private suspend fun deleteSearchJob(jobId: String) {
		if (jobId.isBlank()) return
		val base = settingsStore.currentConfig().baseUrl
		if (base.isBlank()) return
		val response = httpClient.delete("$base/api/search/jobs/${jobId.encodeURLPathPart()}") {
			applyMeloHeaders()
		}
		if (response.status.isSuccess()) {
			response.bodyAsText()
		}
	}

	suspend fun resolveTrackPlayback(sid: String): MeloTrack {
		runCatching { refreshMediaTokenIfNeeded() }
		val response = httpClient.post(buildUrl("/api/tracks/${sid.encodeURLPathPart()}/playback")) {
			applyMeloHeaders()
		}
		return decodeResponse<MeloTrack>(response).withResolvedUrls()
	}

	suspend fun getTrackVersions(sid: String): MeloTrack {
		runCatching { refreshMediaTokenIfNeeded() }
		val response = httpClient.post(buildUrl("/api/tracks/${sid.encodeURLPathPart()}/versions")) {
			applyMeloHeaders()
		}
		return decodeResponse<MeloTrack>(response).withResolvedUrls()
	}

	suspend fun getTrackLyrics(sid: String): String {
		val response = httpClient.get(buildUrl("/api/tracks/${sid.encodeURLPathPart()}/lyrics")) {
			applyMeloHeaders()
		}
		return decodeResponse<MeloLyricsResponse>(response).text
	}

	suspend fun getTrackEpisodes(sid: String): List<MeloTrack> {
		val response = httpClient.get(buildUrl("/api/tracks/${sid.encodeURLPathPart()}/episodes")) {
			applyMeloHeaders()
		}
		return decodeResponse<List<MeloTrack>>(response).map { it.withResolvedUrls() }
	}

	suspend fun getDownloads(): List<MeloDownloadJob> {
		val response = httpClient.get(buildUrl("/api/downloads")) {
			applyMeloHeaders()
		}
		return decodeResponse<List<MeloDownloadJob>>(response).map { job ->
			job.copy(track = job.track.withResolvedUrls())
		}
	}

	suspend fun enqueueDownloads(
		tracks: List<MeloTrack>,
		qualityMap: Map<String, String> = emptyMap(),
	): List<MeloDownloadJob> {
		if (tracks.isEmpty()) return emptyList()
		val items = tracks.map { track ->
			val qualityId = qualityMap[track.id]
				?: track.qualities.firstOrNull()?.id
				?: ""
			MeloDownloadEnqueueItem(
				trackId = track.id,
				qualityId = qualityId,
			)
		}
		val response = httpClient.post(buildUrl("/api/downloads")) {
			applyMeloHeaders()
			contentType(ContentType.Application.Json)
			setBody(items)
		}
		return decodeResponse<List<MeloDownloadJob>>(response).map { job ->
			job.copy(track = job.track.withResolvedUrls())
		}
	}

	suspend fun controlDownload(id: String, action: String): MeloDownloadJob {
		val response = httpClient.post(
			buildUrl("/api/downloads/${id.encodeURLPathPart()}/${action.encodeURLPathPart()}")
		) {
			applyMeloHeaders()
		}
		val job: MeloDownloadJob = decodeResponse(response)
		return job.copy(track = job.track.withResolvedUrls())
	}

	suspend fun deleteDownload(id: String): Boolean {
		val response = httpClient.delete(buildUrl("/api/downloads/${id.encodeURLPathPart()}")) {
			applyMeloHeaders()
		}
		ensureSuccess(response)
		return true
	}

	suspend fun getLibraryFiles(
		cursor: String? = null,
		limit: Int = 100,
		query: String? = null,
		root: String? = null,
		folder: String? = null,
		sort: String? = null,
	): MeloLibraryFilesPage {
		val response = httpClient.get(buildUrl("/api/library/files")) {
			applyMeloHeaders()
			parameter("limit", limit.coerceIn(1, 500))
			if (!cursor.isNullOrBlank()) parameter("cursor", cursor)
			if (!query.isNullOrBlank()) parameter("query", query)
			if (!root.isNullOrBlank()) parameter("root", root)
			if (!folder.isNullOrBlank()) parameter("folder", folder)
			if (!sort.isNullOrBlank()) parameter("sort", sort)
		}
		val page: MeloLibraryFilesPage = decodeResponse(response)
		return page.copy(
			items = page.items.map { item ->
				item.copy(track = item.track?.withResolvedUrls())
			}
		)
	}

	suspend fun refreshLibrary(): MeloLibraryRefreshResult {
		val response = httpClient.post(buildUrl("/api/library/refresh")) {
			applyMeloHeaders()
		}
		return decodeResponse(response)
	}

	suspend fun getLibraryJobs(
		offset: Int = 0,
		size: Int = 30,
		status: String = "all",
	): MeloLibraryJobsPage {
		val response = httpClient.get(buildUrl("/api/library/jobs")) {
			applyMeloHeaders()
			parameter("offset", offset.coerceAtLeast(0))
			parameter("size", size.coerceIn(1, 100))
			parameter("status", status.ifBlank { "all" })
		}
		return decodeResponse(response)
	}

	suspend fun getLibraryJobDetail(id: String): MeloLibraryJob {
		val response = httpClient.get(buildUrl("/api/library/jobs/${id.encodeURLPathPart()}")) {
			applyMeloHeaders()
		}
		return decodeResponse(response)
	}

	suspend fun createScrapeJob(
		paths: List<String>,
		action: String = "both",
		scopeDir: String? = null,
		sources: List<String>? = null,
		overwrite: Boolean? = null,
		autoApply: Boolean = false,
		sourceDownloadId: String? = null,
	): MeloLibraryJob {
		val response = httpClient.post(buildUrl("/api/library/jobs")) {
			applyMeloHeaders()
			contentType(ContentType.Application.Json)
			setBody(
				MeloScrapeJobRequest(
					paths = paths,
					action = action,
					scopeDir = scopeDir,
					sources = sources,
					overwrite = overwrite,
					autoApply = autoApply,
					sourceDownloadId = sourceDownloadId?.trim()?.takeIf { it.isNotEmpty() },
				)
			)
		}
		return decodeResponse(response)
	}

	suspend fun applyScrapeJob(
		id: String,
		choices: Map<String, String> = emptyMap(),
	): MeloLibraryJob {
		val response = httpClient.post(buildUrl("/api/library/jobs/${id.encodeURLPathPart()}/apply")) {
			applyMeloHeaders()
			if (choices.isNotEmpty()) {
				contentType(ContentType.Application.Json)
				setBody(MeloLibraryApplyRequest(choices = choices))
			}
		}
		return decodeResponse(response)
	}

	suspend fun cancelLibraryJob(id: String): MeloLibraryJob {
		val response = httpClient.post(buildUrl("/api/library/jobs/${id.encodeURLPathPart()}/cancel")) {
			applyMeloHeaders()
		}
		return decodeResponse(response)
	}

	suspend fun retryLibraryJob(id: String): MeloLibraryJob {
		val response = httpClient.post(buildUrl("/api/library/jobs/${id.encodeURLPathPart()}/retry")) {
			applyMeloHeaders()
		}
		return decodeResponse(response)
	}

	suspend fun deleteLibraryJob(id: String): Boolean {
		val response = httpClient.delete(buildUrl("/api/library/jobs/${id.encodeURLPathPart()}")) {
			applyMeloHeaders()
		}
		ensureSuccess(response)
		return true
	}

	suspend fun batchDeleteLibraryJobs(
		ids: List<String> = emptyList(),
		mode: String? = null,
	): MeloDeleteLibraryJobsResult {
		val response = httpClient.post(buildUrl("/api/library/jobs/batch-delete")) {
			applyMeloHeaders()
			contentType(ContentType.Application.Json)
			setBody(
				MeloBatchDeleteLibraryJobsRequest(
					ids = ids,
					mode = mode?.trim()?.takeIf { it.isNotEmpty() },
				)
			)
		}
		return decodeResponse(response)
	}

	suspend fun createFolder(root: String, parent: String, name: String): MeloFolderMutationResult {
		val response = httpClient.post(buildUrl("/api/files/mkdir")) {
			applyMeloHeaders()
			contentType(ContentType.Application.Json)
			setBody(
				MeloFolderMkdirRequest(
					root = root,
					parent = parent,
					name = name,
				)
			)
		}
		return decodeResponse(response)
	}

	suspend fun renameFolder(root: String, folder: String, newName: String): MeloFolderMutationResult {
		val response = httpClient.post(buildUrl("/api/files/rename")) {
			applyMeloHeaders()
			contentType(ContentType.Application.Json)
			setBody(
				MeloFolderRenameRequest(
					root = root,
					folder = folder,
					newName = newName,
				)
			)
		}
		return decodeResponse(response)
	}

	suspend fun moveEntries(
		files: List<String> = emptyList(),
		folders: List<MeloLibraryFolder> = emptyList(),
		targetRoot: String,
		targetFolder: String,
	): MeloMoveResult {
		val response = httpClient.post(buildUrl("/api/files/move")) {
			applyMeloHeaders()
			contentType(ContentType.Application.Json)
			setBody(
				MeloMoveEntriesRequest(
					files = files,
					folders = folders,
					targetRoot = targetRoot,
					targetFolder = targetFolder,
				)
			)
		}
		return decodeResponse(response)
	}

	suspend fun batchDeleteEntries(
		files: List<String> = emptyList(),
		folders: List<MeloLibraryFolder> = emptyList(),
	): MeloBatchDeleteResult {
		val response = httpClient.post(buildUrl("/api/files/batch-delete")) {
			applyMeloHeaders()
			contentType(ContentType.Application.Json)
			setBody(
				MeloBatchDeleteRequest(
					files = files,
					folders = folders,
				)
			)
		}
		return decodeResponse(response)
	}

	suspend fun updateFileTags(path: String, tags: Map<String, String>): MeloLibraryFile {
		val response = httpClient.patch(buildUrl("/api/files/tags")) {
			applyMeloHeaders()
			parameter("path", path)
			contentType(ContentType.Application.Json)
			setBody(tags)
		}
		val updated: MeloLibraryFile = decodeResponse(response)
		return updated.copy(track = updated.track?.withResolvedUrls())
	}

	suspend fun unlockFileTags(path: String): Boolean {
		val response = httpClient.delete(buildUrl("/api/library/files/locks")) {
			applyMeloHeaders()
			parameter("path", path)
		}
		ensureSuccess(response)
		return true
	}

	suspend fun previewScrapeJob(id: String, choices: Map<String, String>): MeloLibraryJob {
		val response = httpClient.post(buildUrl("/api/library/jobs/${id.encodeURLPathPart()}/preview")) {
			applyMeloHeaders()
			contentType(ContentType.Application.Json)
			setBody(MeloLibraryApplyRequest(choices = choices))
		}
		return decodeResponse(response)
	}

	suspend fun searchScrapeJobCandidates(
		jobId: String,
		itemId: String,
		query: String,
		sources: List<String>? = null,
	): MeloLibraryJob {
		val response = httpClient.post(buildUrl("/api/library/jobs/${jobId.encodeURLPathPart()}/search")) {
			applyMeloHeaders()
			contentType(ContentType.Application.Json)
			setBody(
				MeloLibraryCandidateSearchRequest(
					item = itemId,
					query = query.trim(),
					sources = sources?.takeIf { it.isNotEmpty() },
				)
			)
		}
		return decodeResponse(response)
	}

	suspend fun getFileTagVersions(path: String): List<MeloTagVersion> {
		val response = httpClient.get(buildUrl("/api/library/versions")) {
			applyMeloHeaders()
			parameter("path", path)
		}
		return decodeResponse<MeloTagVersionsResponse>(response).versions
	}

	suspend fun restoreJobItems(jobId: String, itemIds: List<String>): Boolean {
		val response = httpClient.post(buildUrl("/api/library/jobs/${jobId.encodeURLPathPart()}/restore")) {
			applyMeloHeaders()
			if (itemIds.isNotEmpty()) {
				contentType(ContentType.Application.Json)
				setBody(MeloLibraryRestoreRequest(items = itemIds))
			}
		}
		ensureSuccess(response)
		return true
	}

	suspend fun getFavorites(): List<MeloTrack> {
		val response = httpClient.get(buildUrl("/api/favorites")) {
			applyMeloHeaders()
		}
		return decodeResponse<List<MeloTrack>>(response).map { it.withResolvedUrls() }
	}

	suspend fun addFavorite(trackId: String): Boolean {
		val response = httpClient.put(buildUrl("/api/favorites/${trackId.encodeURLPathPart()}")) {
			applyMeloHeaders()
		}
		ensureSuccess(response)
		return true
	}

	suspend fun removeFavorite(trackId: String): Boolean {
		val response = httpClient.delete(buildUrl("/api/favorites/${trackId.encodeURLPathPart()}")) {
			applyMeloHeaders()
		}
		ensureSuccess(response)
		return true
	}

	suspend fun getLocalPlaylists(): List<MeloLocalPlaylistSummary> {
		val response = httpClient.get(buildUrl("/api/playlists")) {
			applyMeloHeaders()
		}
		return decodeResponse<List<MeloLocalPlaylistSummary>>(response).map { it.withResolvedUrls() }
	}

	suspend fun createLocalPlaylist(
		name: String,
		trackIds: List<String> = emptyList(),
	): MeloLocalPlaylistDetail {
		val response = httpClient.post(buildUrl("/api/playlists")) {
			applyMeloHeaders()
			contentType(ContentType.Application.Json)
			setBody(
				MeloLocalPlaylistCreateRequest(
					name = name.trim(),
					trackIds = trackIds,
				)
			)
		}
		return decodeResponse<MeloLocalPlaylistDetail>(response).withResolvedUrls()
	}

	suspend fun getLocalPlaylistDetail(id: String): MeloLocalPlaylistDetail {
		val response = httpClient.get(buildUrl("/api/playlists/${id.encodeURLPathPart()}")) {
			applyMeloHeaders()
		}
		return decodeResponse<MeloLocalPlaylistDetail>(response).withResolvedUrls()
	}

	suspend fun addTracksToLocalPlaylist(
		playlistId: String,
		trackIds: List<String>,
	): MeloLocalPlaylistDetail {
		val response = httpClient.post(buildUrl("/api/playlists/${playlistId.encodeURLPathPart()}/tracks")) {
			applyMeloHeaders()
			contentType(ContentType.Application.Json)
			setBody(MeloLocalPlaylistTracksRequest(trackIds = trackIds))
		}
		return decodeResponse<MeloLocalPlaylistDetail>(response).withResolvedUrls()
	}

	suspend fun renameLocalPlaylist(
		playlistId: String,
		name: String,
	): MeloLocalPlaylistDetail {
		val response = httpClient.patch(buildUrl("/api/playlists/${playlistId.encodeURLPathPart()}")) {
			applyMeloHeaders()
			contentType(ContentType.Application.Json)
			setBody(MeloLocalPlaylistRenameRequest(name = name.trim()))
		}
		return decodeResponse<MeloLocalPlaylistDetail>(response).withResolvedUrls()
	}

	suspend fun deleteLocalPlaylist(playlistId: String): Boolean {
		val response = httpClient.delete(buildUrl("/api/playlists/${playlistId.encodeURLPathPart()}")) {
			applyMeloHeaders()
		}
		ensureSuccess(response)
		return true
	}

	suspend fun removeTrackFromLocalPlaylist(
		playlistId: String,
		trackId: String,
	): MeloLocalPlaylistDetail {
		val response = httpClient.delete(
			buildUrl("/api/playlists/${playlistId.encodeURLPathPart()}/tracks/${trackId.encodeURLPathPart()}")
		) {
			applyMeloHeaders()
		}
		return decodeResponse<MeloLocalPlaylistDetail>(response).withResolvedUrls()
	}

	suspend fun getNavidromeCapabilities(): MeloNavidromeCapabilities {
		val response = httpClient.get(buildUrl("/api/navidrome/capabilities")) {
			applyMeloHeaders()
		}
		return decodeResponse(response)
	}

	suspend fun previewNavidromeSongDeletion(sourceSongId: String): MeloNavidromeDeletionPreview {
		val response = httpClient.get(
			buildUrl("/api/navidrome/songs/${sourceSongId.encodeURLPathPart()}/deletion")
		) {
			applyMeloHeaders()
		}
		return decodeResponse(response)
	}

	suspend fun deleteNavidromeSong(sourceSongId: String, revision: String): MeloNavidromeDeleteResult {
		val response = httpClient.delete(
			buildUrl("/api/navidrome/songs/${sourceSongId.encodeURLPathPart()}")
		) {
			applyMeloHeaders()
			parameter("revision", revision)
		}
		return decodeResponse(response)
	}

	suspend fun triggerNavidromeScan(): MeloNavidromeScanStatus {
		val response = httpClient.post(buildUrl("/api/navidrome/scan")) {
			applyMeloHeaders()
		}
		return decodeResponse(response)
	}

	suspend fun getNavidromeScanStatus(): MeloNavidromeScanStatus {
		val response = httpClient.get(buildUrl("/api/navidrome/scan")) {
			applyMeloHeaders()
		}
		return decodeResponse(response)
	}

	suspend fun getAgentSessions(): List<MeloAgentSessionSummary> {
		val response = httpClient.get(buildUrl("/api/agent/sessions")) {
			applyMeloHeaders()
		}
		return decodeResponse(response)
	}

	suspend fun createAgentSession(): MeloAgentSession {
		val response = httpClient.post(buildUrl("/api/agent/sessions")) {
			applyMeloHeaders()
		}
		return decodeResponse(response)
	}

	suspend fun getAgentSession(id: String): MeloAgentSession {
		val response = httpClient.get(buildUrl("/api/agent/sessions/${id.encodeURLPathPart()}")) {
			applyMeloHeaders()
		}
		return decodeResponse(response)
	}

	suspend fun deleteAgentSession(id: String): Boolean {
		val response = httpClient.delete(buildUrl("/api/agent/sessions/${id.encodeURLPathPart()}")) {
			applyMeloHeaders()
		}
		ensureSuccess(response)
		return true
	}

	suspend fun sendAgentMessage(
		sessionId: String,
		message: String,
		imageDataUrl: String? = null,
		allowActions: Boolean? = null,
		trackIds: List<String>? = null,
	): MeloAgentSession {
		val response = httpClient.post(
			buildUrl("/api/agent/sessions/${sessionId.encodeURLPathPart()}/messages")
		) {
			applyMeloHeaders()
			contentType(ContentType.Application.Json)
			setBody(
				MeloAgentTurnRequest(
					message = message,
					image = imageDataUrl?.takeIf { it.isNotBlank() },
					allowActions = allowActions,
					trackIds = trackIds?.takeIf { it.isNotEmpty() },
				)
			)
		}
		return decodeResponse(response)
	}

	suspend fun stopAgentSession(sessionId: String): MeloAgentSession {
		val response = httpClient.post(buildUrl("/api/agent/sessions/${sessionId.encodeURLPathPart()}/stop")) {
			applyMeloHeaders()
		}
		return decodeResponse(response)
	}

	suspend fun resumeAgentSession(sessionId: String): MeloAgentSession {
		val response = httpClient.post(buildUrl("/api/agent/sessions/${sessionId.encodeURLPathPart()}/resume")) {
			applyMeloHeaders()
		}
		return decodeResponse(response)
	}

	suspend fun getConfig(): MeloEngineConfig {
		val configResp = httpClient.get(buildUrl("/api/config")) {
			applyMeloHeaders()
		}
		val baseCfg: MeloEngineConfig = decodeResponse(configResp)
		val engineCfg = runCatching {
			val r = httpClient.get(buildUrl("/api/engine/config")) {
				applyMeloHeaders()
			}
			if (r.status.isSuccess()) decodeResponse<MeloEngineConfig>(r) else null
		}.getOrNull()

		val merged = if (engineCfg != null) {
			baseCfg.copy(
				proxyUrl = engineCfg.proxyUrl,
				proxyScope = engineCfg.proxyScope,
				proxySources = engineCfg.proxySources,
			)
		} else {
			baseCfg
		}
		MeloSourceLabelRegistry.register(merged.availableSources)
		return merged
	}

	suspend fun updateConfig(request: MeloConfigUpdateRequest): MeloConfigSaveResponse {
		val hasEngineSpecificFields = request.proxyUrl != null ||
			request.proxyScope != null ||
			request.proxySources != null

		val hasCoreFields = request.sources != null ||
			request.resultsPerSource != null ||
			request.searchTimeoutSeconds != null ||
			request.playbackTimeoutSeconds != null ||
			request.downloadConcurrency != null ||
			request.downloadLayout != null ||
			request.lyricsMode != null ||
			request.embedMetadata != null ||
			request.sourceSettings != null

		var saveResp = MeloConfigSaveResponse(saved = true)

		if (hasCoreFields) {
			val resolvedSources = if (!request.sources.isNullOrEmpty()) {
				request.sources
			} else {
				getConfig().sources
			}
			val coreReq = request.copy(
				sources = resolvedSources,
				proxyUrl = null,
				proxyScope = null,
				proxySources = null,
			)
			val resp = httpClient.put(buildUrl("/api/config")) {
				applyMeloHeaders()
				contentType(ContentType.Application.Json)
				setBody(coreReq)
			}
			saveResp = decodeResponse(resp)
		}

		if (hasEngineSpecificFields) {
			val engineReq = MeloConfigUpdateRequest(
				sources = request.sources?.takeIf { it.isNotEmpty() },
				resultsPerSource = request.resultsPerSource,
				searchTimeoutSeconds = request.searchTimeoutSeconds,
				downloadLayout = request.downloadLayout,
				lyricsMode = request.lyricsMode,
				embedMetadata = request.embedMetadata,
				sourceSettings = request.sourceSettings,
				proxyUrl = request.proxyUrl,
				proxyScope = request.proxyScope,
				proxySources = request.proxySources,
			)
			val resp = httpClient.put(buildUrl("/api/engine/config")) {
				applyMeloHeaders()
				contentType(ContentType.Application.Json)
				setBody(engineReq)
			}
			saveResp = decodeResponse(resp)
		}

		return saveResp
	}

	suspend fun updateConfig(config: MeloEngineConfig): MeloConfigSaveResponse = updateConfig(
		MeloConfigUpdateRequest(
			sources = config.sources,
			resultsPerSource = config.resultsPerSource,
			searchTimeoutSeconds = config.searchTimeoutSeconds,
			playbackTimeoutSeconds = config.playbackTimeoutSeconds,
			downloadConcurrency = config.downloadConcurrency,
			downloadLayout = config.downloadLayout,
			lyricsMode = config.lyricsMode,
			embedMetadata = config.embedMetadata,
			sourceSettings = config.sourceSettings,
			proxyUrl = config.proxyUrl,
			proxyScope = config.proxyScope,
			proxySources = config.proxySources,
		)
	)

	private fun MeloTrack.withResolvedUrls(): MeloTrack {
		val resolvedCover = resolveMediaUrl(cover)
		val resolvedPlaybackUrl = resolveMediaUrl(playback.url)
		return copy(
			cover = resolvedCover,
			playback = playback.copy(url = resolvedPlaybackUrl),
		)
	}

	private fun MeloLocalPlaylistSummary.withResolvedUrls(): MeloLocalPlaylistSummary =
		copy(cover = resolveMediaUrl(cover))

	private fun MeloLocalPlaylistDetail.withResolvedUrls(): MeloLocalPlaylistDetail =
		copy(tracks = tracks.map { it.withResolvedUrls() })

	private fun requireBaseUrl(overrideBaseUrl: String? = null): String {
		val candidate = if (overrideBaseUrl != null) {
			MeloSettingsStore.normalizeBaseUrl(overrideBaseUrl)
		} else {
			settingsStore.currentConfig().baseUrl.trimEnd('/')
		}
		if (candidate.isEmpty()) {
			throw MeloApiException(0, "尚未配置 MeloKeep 服务器地址")
		}
		return candidate
	}

	private fun buildUrl(path: String, overrideBaseUrl: String? = null): String {
		val base = requireBaseUrl(overrideBaseUrl)
		val cleanPath = if (path.startsWith("/")) path else "/$path"
		return "$base$cleanPath"
	}

	private fun HttpRequestBuilder.applyMeloHeaders(tokenOverride: String? = null) {
		header("X-MeloKeep-Request", "1")
		val token = tokenOverride ?: settingsStore.currentConfig().bearerToken
		if (token.isNotBlank()) {
			header(HttpHeaders.Authorization, "Bearer $token")
		}
	}

	private suspend inline fun <reified T> decodeResponse(response: HttpResponse): T {
		val text = response.bodyAsText()
		val statusCode = response.status.value
		if (!response.status.isSuccess()) {
			throw MeloApiException(
				statusCode = statusCode,
				message = parseApiErrorMessage(text, statusCode, response.headers[HttpHeaders.Location]),
			)
		}
		if (isHtmlBody(text)) {
			throw MeloApiException(
				statusCode = statusCode,
				message = "反代或网关返回错误页面 (HTTP $statusCode)，请检查反代配置或后端运行状态",
			)
		}
		if (text.isBlank()) {
			return meloJson.decodeFromString("{}")
		}
		return try {
			meloJson.decodeFromString<T>(text)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			throw MeloApiException(
				statusCode = statusCode,
				message = "服务返回数据解析失败: ${e.message ?: "格式错误"}",
			)
		}
	}

	private suspend fun ensureSuccess(response: HttpResponse) {
		val text = response.bodyAsText()
		val statusCode = response.status.value
		if (!response.status.isSuccess()) {
			throw MeloApiException(
				statusCode = statusCode,
				message = parseApiErrorMessage(text, statusCode, response.headers[HttpHeaders.Location]),
			)
		}
	}

	companion object {
		private const val MEDIA_TOKEN_REFRESH_BUFFER_SECONDS = 300L
		private const val MAX_REDIRECT_HOPS = 3
		private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
		private val LOGIN_REDIRECT_STATUS_CODES = setOf(301, 302, 307, 308)

		internal fun isHtmlBody(rawBody: String): Boolean {
			val head = rawBody.trimStart().take(1024)
			if (head.isEmpty()) return false
			return head.contains("<!doctype", ignoreCase = true) || head.contains("<html", ignoreCase = true)
		}

		private fun resolveRedirectUrl(currentUrl: String, location: String): String {
			val loc = location.trim()
			if (loc.startsWith("http://", ignoreCase = true) || loc.startsWith("https://", ignoreCase = true)) {
				return loc
			}
			val schemeSep = currentUrl.indexOf("://")
			if (schemeSep < 0) return loc
			val scheme = currentUrl.substring(0, schemeSep).lowercase()
			if (loc.startsWith("//")) {
				return "$scheme:$loc"
			}
			val origin = extractOrigin(currentUrl) ?: return loc
			return if (loc.startsWith("/")) {
				"$origin$loc"
			} else {
				val pathPart = currentUrl.substringBefore('#').substringBefore('?')
				val lastSlash = pathPart.lastIndexOf('/')
				val parentDir = if (lastSlash > schemeSep + 2) pathPart.substring(0, lastSlash) else origin
				"$parentDir/$loc"
			}
		}

		private fun extractOrigin(url: String): String? {
			val schemeSep = url.indexOf("://")
			if (schemeSep < 0) return null
			val scheme = url.substring(0, schemeSep).lowercase()
			val afterScheme = url.substring(schemeSep + 3)
			val slashIdx = afterScheme.indexOf('/')
			val authority = if (slashIdx >= 0) afterScheme.substring(0, slashIdx) else afterScheme
			if (authority.isEmpty()) return null
			return "$scheme://$authority"
		}

		private fun resolveLoginRedirectTarget(resolvedUrl: String, fallbackBase: String): Pair<String, String> {
			val clean = resolvedUrl.substringBefore('#').substringBefore('?')
			if (clean.endsWith("/api/auth/login", ignoreCase = true)) {
				val nextBase = MeloSettingsStore.normalizeBaseUrl(clean.dropLast("/api/auth/login".length))
					.ifBlank { fallbackBase }
				return nextBase to resolvedUrl
			}
			val origin = extractOrigin(clean)
			if (origin != null && clean.trimEnd('/') == origin) {
				val nextBase = MeloSettingsStore.normalizeBaseUrl(clean).ifBlank { fallbackBase }
				return nextBase to "$nextBase/api/auth/login"
			}
			val nextBase = MeloSettingsStore.normalizeBaseUrl(clean).ifBlank { fallbackBase }
			return nextBase to resolvedUrl
		}

		private fun extractBaseUrlFromLoginUrl(url: String): String? {
			val clean = url.substringBefore('#').substringBefore('?')
			if (clean.endsWith("/api/auth/login", ignoreCase = true)) {
				return MeloSettingsStore.normalizeBaseUrl(clean.dropLast("/api/auth/login".length))
					.takeIf { it.isNotBlank() }
			}
			return null
		}

		private fun collectErrorDiagnosticText(throwable: Throwable): String {
			val parts = mutableListOf<String>()
			var current: Throwable? = throwable
			var depth = 0
			while (current != null && depth < 6) {
				current::class.simpleName?.let { parts += it }
				current.message?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
				current = current.cause
				depth++
			}
			return parts.joinToString(" | ")
		}

		private fun buildTechnicalNetworkDetail(url: String, throwable: Throwable): String {
			val typeName = throwable::class.simpleName ?: "Exception"
			val msg = throwable.message?.trim().orEmpty()
			return if (msg.isNotEmpty()) "$typeName: $msg ($url)" else "$typeName ($url)"
		}

		internal fun buildNetworkErrorMessage(url: String, throwable: Throwable): String {
			val combined = collectErrorDiagnosticText(throwable).lowercase()
			return when {
				combined.contains("ssl") ||
					combined.contains("tls") ||
					combined.contains("certificate") ||
					combined.contains("certpath") ||
					combined.contains("trust anchor") ||
					combined.contains("handshake") ||
					combined.contains("pkix") ||
					combined.contains("x509") ->
					"HTTPS 证书校验失败，若使用自签名证书请开启“忽略自签名 SSL 证书” ($url)"
				combined.contains("unknownhost") ||
					combined.contains("unknown host") ||
					combined.contains("dns") ||
					combined.contains("resolve") ||
					combined.contains("nodename") ||
					combined.contains("name or service not known") ||
					combined.contains("eai_") ->
					"域名解析失败 ($url)"
				combined.contains("timeout") ||
					combined.contains("timed out") ||
					combined.contains("connect") ||
					combined.contains("refused") ||
					combined.contains("unreachable") ||
					combined.contains("reset") ||
					combined.contains("eof") ||
					combined.contains("broken pipe") ||
					combined.contains("socket") ->
					"连接超时或端口未开放 ($url)"
				else -> {
					val rawMsg = throwable.message?.trim().takeIf { !it.isNullOrBlank() }
						?: throwable::class.simpleName
						?: "网络异常"
					"无法连接服务器：$rawMsg ($url)"
				}
			}
		}

		private fun isProtectedMediaPath(pathOnly: String): Boolean {
			return pathOnly.startsWith("/api/stream/") ||
				pathOnly.startsWith("/api/navidrome/stream/") ||
				pathOnly.startsWith("/api/navidrome/cover/") ||
				pathOnly == "/api/files/audio" ||
				pathOnly == "/api/files/cover" ||
				pathOnly == "/api/files/content" ||
				pathOnly.startsWith("/downloads/") ||
				pathOnly.startsWith("/api/")
		}

		private fun hasQueryParam(url: String, key: String): Boolean {
			val qIndex = url.indexOf('?')
			if (qIndex < 0) return false
			val afterQ = url.substring(qIndex + 1).substringBefore('#')
			return afterQ.split('&').any { param ->
				val name = param.substringBefore('=')
				name == key
			}
		}

		private fun appendQueryParam(url: String, key: String, value: String): String {
			val hashIndex = url.indexOf('#')
			val mainPart = if (hashIndex >= 0) url.substring(0, hashIndex) else url
			val fragmentPart = if (hashIndex >= 0) url.substring(hashIndex) else ""
			val separator = if ('?' in mainPart) "&" else "?"
			return "$mainPart$separator$key=${value.encodeURLParameter()}$fragmentPart"
		}

		internal fun parseApiErrorMessage(
			rawBody: String,
			statusCode: Int,
			location: String? = null,
		): String {
			if (statusCode in REDIRECT_STATUS_CODES) {
				val loc = location?.trim()?.takeIf { it.isNotEmpty() }
				return if (loc != null) {
					"请求被重定向 ($statusCode -> $loc)，请检查反代配置或服务器地址"
				} else {
					"请求被重定向 ($statusCode)，请检查反代配置或服务器地址"
				}
			}
			val trimmed = rawBody.trim()
			if (isHtmlBody(trimmed)) {
				return "反代或网关返回错误页面 (HTTP $statusCode)，请检查反代配置或后端运行状态"
			}
			if (trimmed.isEmpty()) {
				return "请求失败 ($statusCode)"
			}
			val parsed = runCatching { meloJson.parseToJsonElement(trimmed) }.getOrNull()
			if (parsed is JsonObject) {
				val detailEl = parsed["detail"]
				val extracted = extractDetailText(detailEl)
				if (extracted.isNotBlank()) return extracted
				val msgEl = parsed["message"]
				if (msgEl is JsonPrimitive) {
					msgEl.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
				}
			}
			return trimmed.take(300)
		}

		private fun extractDetailText(el: JsonElement?): String = when (el) {
			null -> ""
			is JsonPrimitive -> el.contentOrNull.orEmpty()
			is JsonArray -> el.mapNotNull { item ->
				when (item) {
					is JsonPrimitive -> item.contentOrNull
					is JsonObject -> item["msg"]?.jsonPrimitive?.contentOrNull
						?: item["message"]?.jsonPrimitive?.contentOrNull
						?: item["detail"]?.jsonPrimitive?.contentOrNull
					else -> null
				}?.takeIf { it.isNotBlank() }
			}.joinToString("；")
			is JsonObject -> el["detail"]?.let { extractDetailText(it) }
				?.takeIf { it.isNotBlank() }
				?: el["message"]?.let { extractDetailText(it) }.orEmpty()
		}

		internal suspend fun parseSseChannel(
			channel: ByteReadChannel,
			emitEvent: suspend (MeloSearchEvent) -> Unit,
		) {
			var eventType = ""
			var eventId: String? = null
			val dataLines = mutableListOf<String>()

			suspend fun flushFrame() {
				if (dataLines.isEmpty() && eventType.isEmpty()) {
					eventId = null
					return
				}
				val data = dataLines.joinToString("\n")
				val parsed = parseSseEvent(eventType, data, eventId)
				eventType = ""
				eventId = null
				dataLines.clear()
				if (parsed != null) {
					emitEvent(parsed)
				}
			}

			while (true) {
				val line = channel.readLine(LineEnding.Lenient) ?: break
				if (line.isEmpty()) {
					flushFrame()
					continue
				}
				if (line.startsWith(":")) {
					continue
				}
				val colonIdx = line.indexOf(':')
				val field = if (colonIdx >= 0) line.substring(0, colonIdx) else line
				val rawValue = if (colonIdx >= 0) line.substring(colonIdx + 1) else ""
				val value = if (rawValue.startsWith(" ")) rawValue.substring(1) else rawValue
				when (field) {
					"event" -> eventType = value.trim()
					"data" -> dataLines.add(value)
					"id" -> eventId = value.trim()
				}
			}
			flushFrame()
		}

		internal fun parseSseEvent(
			eventName: String,
			data: String,
			eventId: String? = null,
		): MeloSearchEvent? {
			val normalizedName = eventName.trim().lowercase()
			if (normalizedName == "keepalive") return null
			return when (normalizedName) {
				"candidate" -> {
					if (data.isBlank()) return null
					runCatching {
						MeloSearchEvent.Candidate(
							track = meloJson.decodeFromString<MeloTrack>(data),
							eventId = eventId,
						)
					}.getOrNull()
				}
				"track", "" -> {
					if (data.isBlank()) return null
					runCatching {
						MeloSearchEvent.Track(
							track = meloJson.decodeFromString<MeloTrack>(data),
							eventId = eventId,
						)
					}.getOrNull()
				}
				"source" -> {
					if (data.isBlank()) return null
					runCatching {
						val payload = meloJson.decodeFromString<MeloSourceEventPayload>(data)
						MeloSearchEvent.Source(
							sourceId = payload.id,
							status = payload.status,
							eventId = eventId,
						)
					}.getOrNull()
				}
				"done" -> {
					val payload = if (data.isBlank()) {
						MeloDoneEventPayload()
					} else {
						runCatching { meloJson.decodeFromString<MeloDoneEventPayload>(data) }
							.getOrDefault(MeloDoneEventPayload())
					}
					MeloSearchEvent.Done(
						cancelled = payload.cancelled,
						eventId = eventId,
					)
				}
				"error" -> {
					val detail = parseApiErrorMessage(data, 500)
					MeloSearchEvent.Error(
						detail = detail.ifBlank { "搜索服务出现异常" },
						eventId = eventId,
					)
				}
				else -> null
			}
		}
	}
}
