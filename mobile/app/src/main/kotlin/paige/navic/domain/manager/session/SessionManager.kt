/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.domain.manager.session

import com.russhwolf.settings.Settings
import com.russhwolf.settings.set
import dev.zt64.subsonic.api.model.Role
import dev.zt64.subsonic.api.model.SubsonicErrorCode
import dev.zt64.subsonic.api.model.SubsonicException
import dev.zt64.subsonic.api.model.User
import dev.zt64.subsonic.client.SubsonicAuth
import dev.zt64.subsonic.client.SubsonicClient
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.util.Logger
import paige.navic.util.TTLLruCache
import paige.navic.util.getEncryptedString
import paige.navic.util.insertEncryptedString
import paige.navic.util.network.NavicDnsResolver
import paige.navic.util.network.buildKtorEngine
import paige.navic.util.network.createOkHttpClient
import paige.navic.util.network.getProxyUrlOrNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit

sealed interface SessionState {
	data object Unauthenticated : SessionState
	data object Restoring : SessionState
	data class Authenticated(
		val currentUser: User?,
		/* use this to check the music server being used */
		val serverInfo: Any? = null
	) : SessionState
}

class SessionManager(
	private val settings: Settings,
	private val preferenceManager: PreferenceManager,
	private val dnsResolver: NavicDnsResolver
) {
	val ttlLruCache = TTLLruCache<String, String>(
		maxSize = 500,
		defaultTtlMillis = 30.minutes.toLong(DurationUnit.MILLISECONDS)
	)

	private companion object {
		const val TAG = "SessionManager"
		const val USER_AGENT = "Navic"
	}

	private val hasStoredCredentials: Boolean
		get() = username.isNotBlank() && password.isNotBlank() && instanceUrl.isNotBlank()

	private val _state = MutableStateFlow(
		if (hasStoredCredentials) SessionState.Restoring else SessionState.Unauthenticated
	)
	val sessionState: StateFlow<SessionState> = _state.asStateFlow()

	private val _supportsTranscoding = MutableStateFlow(
		settings.getBoolean("supportsTranscoding", false)
	)
	val supportsTranscoding: StateFlow<Boolean> = _supportsTranscoding.asStateFlow()

	private val mutex = Mutex()
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	var username: String
		get() = settings.getString("username", "")
		private set(value) {
			settings["username"] = value
		}

	var password: String
		get() = settings.getEncryptedString("password", "")
		private set(value) {
			settings.insertEncryptedString("password", value)
		}

	var instanceUrl: String
		get() = settings.getString("instanceUrl", "")
		private set(value) {
			settings["instanceUrl"] = value
		}

	var api: SubsonicClient = createClient(
		instanceUrl = instanceUrl,
		username = username,
		password = password
	)
		private set

	init {
		if (hasStoredCredentials) {
			scope.launch {
				try {
					login(instanceUrl, username, password)
				} catch (e: SubsonicException) {
					if (e.code == SubsonicErrorCode.WRONG_USERNAME_OR_PASSWORD) {
						Logger.e(TAG, "Credentials invalid, logging out", e)
						logout()
					} else {
						Logger.w(TAG, "SubsonicException during restoration: $e")
						_state.value = SessionState.Authenticated(currentUser = null, serverInfo = null)
					}
				} catch (e: Exception) {
					Logger.w(TAG, "Failed to reach server during restoration, treating as offline authenticated", e)
					_state.value = SessionState.Authenticated(
						currentUser = null,
						serverInfo = null
					)
				}
			}
		}
	}

	private fun createClient(
		instanceUrl: String,
		username: String,
		password: String
	) = SubsonicClient.Companion(
		baseUrl = instanceUrl,
		auth = SubsonicAuth.Token(
			username = username,
			password = password,
		),
		client = USER_AGENT,
		clientConfig = {
			install(UserAgent) {
				agent = USER_AGENT
			}

			val customHeaders = preferenceManager.customHeadersMap()
			if (customHeaders.isNotEmpty()) {
				defaultRequest {
					customHeaders.forEach { (key, value) -> header(key, value) }
				}
			}
		},
		engine = buildKtorEngine(
			createOkHttpClient(
				proxyUrl = preferenceManager.proxyUrl.getProxyUrlOrNull(),
				trustAllCerts = preferenceManager.dangerousSslNoopEnabled,
				dnsResolver
			)
		)
	)

	@Throws(SubsonicException::class)
	suspend fun login(
		instanceUrl: String,
		username: String,
		password: String
	) {
		val client = createClient(instanceUrl, username, password)

		val pingResult = client.ping()
		Logger.i(TAG, "Ping result: $pingResult")

		val user = fetchCurrentUser(username, client)

		val extensions = runCatching {
			client.getOpenSubsonicExtensions()
		}.getOrDefault(emptyList())
		_supportsTranscoding.value = extensions.any { it.name == "transcoding" }
		settings["supportsTranscoding"] = _supportsTranscoding.value

		this.instanceUrl = instanceUrl
		this.username = username
		this.password = password

		api = client
		_state.value = SessionState.Authenticated(
			currentUser = user,
			serverInfo = pingResult
		)
	}

	fun logout() {
		settings["username"] = null
		settings.remove("password")
		_supportsTranscoding.value = false
		settings["supportsTranscoding"] = false
		ttlLruCache.clear()
		_state.value = SessionState.Unauthenticated
	}

	fun refreshClient() {
		api = createClient(
			instanceUrl = instanceUrl,
			username = username,
			password = password,
		)
	}

	fun updateInstanceUrl(url: String) {
		val rawUrl = url.trim().removeSuffix("/")
		val formattedUrl = if (rawUrl.startsWith("https://") || rawUrl.startsWith("http://")) {
			rawUrl
		} else {
			"https://$rawUrl"
		}
		if (formattedUrl.isNotBlank() && formattedUrl != instanceUrl) {
			instanceUrl = formattedUrl
			refreshClient()
		}
	}

	fun getCoverArtUrl(coverArtId: String) = if (
		coverArtId.startsWith("http://", ignoreCase = true) ||
		coverArtId.startsWith("https://", ignoreCase = true) ||
		coverArtId.startsWith("content://", ignoreCase = true) ||
		coverArtId.startsWith("file://", ignoreCase = true)
	) {
		coverArtId
	} else {
		api.getCoverArtUrl(
			coverArtId,
			auth = true,
			size = "${preferenceManager.coverArtQuality.value}"
		)
	}

	// TODO: use getTokenInfo instead to retrieve the username, will be required when implementing API key support
	private suspend fun fetchCurrentUser(
		username: String = this.username,
		client: SubsonicClient = api
	): User = mutex.withLock {
		// TODO: custom exception instead of the generic "Exception"
		if (username.isBlank()) {
			throw Exception("Failed to get current user because the username is blank")
		}

		client.getUser(username)
	}

	data class DirectNavidromeScanResult(
		val scanning: Boolean = true,
		val count: Long? = null,
		val serverScanPermitted: Boolean = true
	)

	suspend fun triggerLibraryScan(): DirectNavidromeScanResult = kotlinx.coroutines.withContext(Dispatchers.IO) {
		val base = instanceUrl.trim().trimEnd('/')
		val user = username.trim()
		val pass = password
		if (base.isEmpty() || user.isEmpty()) {
			throw IllegalStateException("Navidrome 未登录或连接信息不完整")
		}
		val salt = (1..12)
			.map { "0123456789abcdef".random() }
			.joinToString("")
		val tokenBytes = java.security.MessageDigest.getInstance("MD5")
			.digest((pass + salt).toByteArray(Charsets.UTF_8))
		val token = tokenBytes.joinToString("") { "%02x".format(it) }
		val encodedUser = java.net.URLEncoder.encode(user, "UTF-8")
		val encodedClient = java.net.URLEncoder.encode(USER_AGENT, "UTF-8")
		val url = "$base/rest/startScan.view?u=$encodedUser&t=$token&s=$salt&v=1.16.1&c=$encodedClient&f=json"

		val okHttp = createOkHttpClient(
			proxyUrl = preferenceManager.proxyUrl.getProxyUrlOrNull(),
			trustAllCerts = preferenceManager.dangerousSslNoopEnabled,
			dnsResolver = dnsResolver
		)
		val reqBuilder = okhttp3.Request.Builder()
			.url(url)
			.header("User-Agent", USER_AGENT)
		preferenceManager.customHeadersMap().forEach { (k, v) ->
			reqBuilder.header(k, v)
		}
		okHttp.newCall(reqBuilder.build()).execute().use { response ->
			if (response.code == 403) {
				return@withContext DirectNavidromeScanResult(
					scanning = false,
					count = null,
					serverScanPermitted = false
				)
			}
			if (!response.isSuccessful) {
				throw IllegalStateException("Navidrome 扫库请求失败 (HTTP ${response.code})")
			}
			val bodyText = response.body?.string().orEmpty()
			val rootObj = runCatching {
				kotlinx.serialization.json.Json.parseToJsonElement(bodyText) as? kotlinx.serialization.json.JsonObject
			}.getOrNull()
			val subResp = rootObj?.get("subsonic-response") as? kotlinx.serialization.json.JsonObject
			val status = (subResp?.get("status") as? kotlinx.serialization.json.JsonPrimitive)?.content
			if (status != null && status != "ok") {
				val errObj = subResp["error"] as? kotlinx.serialization.json.JsonObject
				val errCode = (errObj?.get("code") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
				val errMsg = (errObj?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content
				val isUnauthorized = errCode == 50 ||
					(errMsg?.contains("not authorized", ignoreCase = true) == true) ||
					(errMsg?.contains("unauthorized", ignoreCase = true) == true)
				if (isUnauthorized) {
					return@withContext DirectNavidromeScanResult(
						scanning = false,
						count = null,
						serverScanPermitted = false
					)
				}
				throw IllegalStateException(errMsg?.takeIf { it.isNotBlank() } ?: "Navidrome 拒绝了扫库请求")
			}
			val scanObj = subResp?.get("scanStatus") as? kotlinx.serialization.json.JsonObject
			val scanning = (scanObj?.get("scanning") as? kotlinx.serialization.json.JsonPrimitive)
				?.content?.toBooleanStrictOrNull() ?: true
			val count = (scanObj?.get("count") as? kotlinx.serialization.json.JsonPrimitive)
				?.content?.toLongOrNull()
			DirectNavidromeScanResult(
				scanning = scanning,
				count = count,
				serverScanPermitted = true
			)
		}
	}
}

fun User.hasRole(role: Role): Boolean {
	return this.roles.contains(role)
}

fun User.canShare(): Boolean {
	return this.hasRole(Role.SHARE)
}

fun SessionManager.canUserShare(): Boolean {
	return (sessionState.value as? SessionState.Authenticated)?.currentUser?.canShare() == true
}
