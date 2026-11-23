package io.github.agonie0v0.melokeep.shared

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock

class MeloSettingsStore(
	private val settings: Settings = Settings(),
) {
	private val json = Json {
		ignoreUnknownKeys = true
		encodeDefaults = true
	}

	private val _profiles = MutableStateFlow<List<MeloServerProfile>>(emptyList())
	val profiles: StateFlow<List<MeloServerProfile>> = _profiles.asStateFlow()

	private val _activeProfileId = MutableStateFlow<String?>(null)
	val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

	private val _config = MutableStateFlow(MeloServerConfig())
	val config: StateFlow<MeloServerConfig> = _config.asStateFlow()

	init {
		loadAndMigrateProfiles()
	}

	private val _searchHistory = MutableStateFlow(readHistory(KEY_SEARCH_HISTORY))
	val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

	private val _librarySearchHistory = MutableStateFlow(readHistory(KEY_LIBRARY_SEARCH_HISTORY))
	val librarySearchHistory: StateFlow<List<String>> = _librarySearchHistory.asStateFlow()

	private val _studioThemePreset = MutableStateFlow(readStudioThemePreset())
	val studioThemePreset: StateFlow<String> = _studioThemePreset.asStateFlow()

	private val _onboardingDismissed = MutableStateFlow(readOnboardingDismissed())
	val onboardingDismissed: StateFlow<Boolean> = _onboardingDismissed.asStateFlow()

	fun currentConfig(): MeloServerConfig = _config.value

	fun saveConfig(newConfig: MeloServerConfig): MeloServerConfig {
		val normalized = normalizeServerConfig(newConfig)
		val currentProfiles = _profiles.value
		val activeId = _activeProfileId.value
		if (activeId != null && currentProfiles.any { it.id == activeId }) {
			if (normalized.baseUrl.isNotBlank()) {
				val updatedProfiles = currentProfiles.map { profile ->
					if (profile.id == activeId) profile.copy(config = normalized) else profile
				}
				_profiles.value = updatedProfiles
				persistProfiles(updatedProfiles)
			} else {
				val remaining = currentProfiles.filterNot { it.id == activeId }
				_profiles.value = remaining
				persistProfiles(remaining)
				_activeProfileId.value = null
				settings.remove(KEY_ACTIVE_PROFILE_ID)
			}
		} else if (normalized.baseUrl.isNotBlank()) {
			val newId = if (currentProfiles.isEmpty()) {
				DEFAULT_PROFILE_ID
			} else {
				generateUniqueProfileId(currentProfiles)
			}
			val updatedProfiles = currentProfiles + MeloServerProfile(id = newId, config = normalized)
			_profiles.value = updatedProfiles
			_activeProfileId.value = newId
			persistProfiles(updatedProfiles)
			settings.putString(KEY_ACTIVE_PROFILE_ID, newId)
		}
		persistFlatConfig(normalized)
		_config.value = normalized
		return normalized
	}

	fun updateConfig(transform: (MeloServerConfig) -> MeloServerConfig): MeloServerConfig {
		val updated = transform(_config.value)
		return saveConfig(updated)
	}

	fun setDisplayName(name: String): MeloServerConfig = updateConfig {
		it.copy(displayName = name.trim())
	}

	fun saveSession(
		baseUrl: String,
		username: String,
		bearerToken: String,
		tokenId: String,
		mediaToken: String,
		mediaTokenExpiresAt: Long,
		authEnabled: Boolean,
		enabled: Boolean = true,
		displayName: String? = null,
		profileId: String? = null,
		createNew: Boolean = false,
	): MeloServerConfig {
		val cleanProfileId = profileId?.trim()?.takeIf { it.isNotEmpty() }
		val currentProfiles = _profiles.value
		val activeId = _activeProfileId.value
		val resolvedDisplayName = displayName?.trim() ?: when {
			createNew -> ""
			cleanProfileId != null -> currentProfiles.firstOrNull { it.id == cleanProfileId }?.config?.displayName.orEmpty()
			else -> _config.value.displayName
		}
		val normalized = normalizeServerConfig(
			MeloServerConfig(
				baseUrl = baseUrl,
				username = username.trim(),
				bearerToken = bearerToken.trim(),
				tokenId = tokenId.trim(),
				mediaToken = mediaToken.trim(),
				mediaTokenExpiresAt = mediaTokenExpiresAt,
				enabled = enabled,
				authEnabled = authEnabled,
				displayName = resolvedDisplayName,
			)
		)
		if (normalized.baseUrl.isBlank()) {
			return saveConfig(normalized)
		}

		val targetId: String
		val updatedProfiles: List<MeloServerProfile>
		when {
			createNew -> {
				targetId = generateUniqueProfileId(currentProfiles)
				updatedProfiles = currentProfiles + MeloServerProfile(id = targetId, config = normalized)
			}
			cleanProfileId != null -> {
				targetId = cleanProfileId
				updatedProfiles = if (currentProfiles.any { it.id == cleanProfileId }) {
					currentProfiles.map { if (it.id == cleanProfileId) it.copy(config = normalized) else it }
				} else {
					currentProfiles + MeloServerProfile(id = cleanProfileId, config = normalized)
				}
			}
			activeId != null && currentProfiles.any { it.id == activeId } -> {
				targetId = activeId
				updatedProfiles = currentProfiles.map {
					if (it.id == activeId) it.copy(config = normalized) else it
				}
			}
			else -> {
				val matched = currentProfiles.firstOrNull {
					it.config.baseUrl.equals(normalized.baseUrl, ignoreCase = true)
				}
				if (matched != null) {
					targetId = matched.id
					updatedProfiles = currentProfiles.map {
						if (it.id == matched.id) it.copy(config = normalized) else it
					}
				} else {
					targetId = if (currentProfiles.isEmpty()) {
						DEFAULT_PROFILE_ID
					} else {
						generateUniqueProfileId(currentProfiles)
					}
					updatedProfiles = currentProfiles + MeloServerProfile(id = targetId, config = normalized)
				}
			}
		}

		_profiles.value = updatedProfiles
		_activeProfileId.value = targetId
		persistProfiles(updatedProfiles)
		settings.putString(KEY_ACTIVE_PROFILE_ID, targetId)
		persistFlatConfig(normalized)
		_config.value = normalized
		return normalized
	}

	fun switchProfile(profileId: String): MeloServerConfig? {
		val target = _profiles.value.firstOrNull { it.id == profileId } ?: return null
		_activeProfileId.value = target.id
		settings.putString(KEY_ACTIVE_PROFILE_ID, target.id)
		persistFlatConfig(target.config)
		_config.value = target.config
		return target.config
	}

	fun deleteProfile(profileId: String): MeloServerConfig {
		val currentProfiles = _profiles.value
		val remaining = currentProfiles.filterNot { it.id == profileId }
		if (remaining.size == currentProfiles.size) {
			return _config.value
		}
		_profiles.value = remaining
		persistProfiles(remaining)

		val currentActiveId = _activeProfileId.value
		if (currentActiveId == profileId || (currentActiveId != null && remaining.none { it.id == currentActiveId })) {
			if (remaining.isNotEmpty()) {
				val nextActive = remaining.first()
				_activeProfileId.value = nextActive.id
				settings.putString(KEY_ACTIVE_PROFILE_ID, nextActive.id)
				persistFlatConfig(nextActive.config)
				_config.value = nextActive.config
				return nextActive.config
			} else {
				_activeProfileId.value = null
				settings.remove(KEY_ACTIVE_PROFILE_ID)
				val empty = MeloServerConfig()
				persistFlatConfig(empty)
				_config.value = empty
				return empty
			}
		}
		return _config.value
	}

	fun updateMediaToken(mediaToken: String, expiresAt: Long): MeloServerConfig = updateConfig { current ->
		current.copy(
			mediaToken = mediaToken.trim(),
			mediaTokenExpiresAt = expiresAt,
		)
	}

	fun clearSession(keepServerUrl: Boolean = true): MeloServerConfig = updateConfig { current ->
		if (keepServerUrl) {
			current.copy(
				bearerToken = "",
				tokenId = "",
				mediaToken = "",
				mediaTokenExpiresAt = 0L,
				enabled = false,
			)
		} else {
			MeloServerConfig()
		}
	}

	fun reset() {
		_profiles.value = emptyList()
		_activeProfileId.value = null
		settings.remove(KEY_SERVER_PROFILES)
		settings.remove(KEY_ACTIVE_PROFILE_ID)
		val empty = MeloServerConfig()
		persistFlatConfig(empty)
		_config.value = empty
	}

	fun recordSearchHistory(keyword: String, maxEntries: Int = MAX_SEARCH_HISTORY): List<String> =
		recordHistory(
			key = KEY_SEARCH_HISTORY,
			flow = _searchHistory,
			query = keyword,
			maxEntries = maxEntries,
		)

	fun removeSearchHistory(query: String) {
		removeHistory(
			key = KEY_SEARCH_HISTORY,
			flow = _searchHistory,
			query = query,
		)
	}

	fun clearSearchHistory() {
		clearHistory(key = KEY_SEARCH_HISTORY, flow = _searchHistory)
	}

	fun recordLibrarySearchHistory(query: String) {
		recordHistory(
			key = KEY_LIBRARY_SEARCH_HISTORY,
			flow = _librarySearchHistory,
			query = query,
			maxEntries = MAX_SEARCH_HISTORY,
		)
	}

	fun removeLibrarySearchHistory(query: String) {
		removeHistory(
			key = KEY_LIBRARY_SEARCH_HISTORY,
			flow = _librarySearchHistory,
			query = query,
		)
	}

	fun clearLibrarySearchHistory() {
		clearHistory(key = KEY_LIBRARY_SEARCH_HISTORY, flow = _librarySearchHistory)
	}

	fun setStudioThemePreset(preset: String) {
		val normalized = preset.trim().takeIf { it in SUPPORTED_THEME_PRESETS } ?: DEFAULT_STUDIO_THEME_PRESET
		settings.putString(KEY_STUDIO_THEME_PRESET, normalized)
		_studioThemePreset.value = normalized
	}

	fun setOnboardingDismissed(dismissed: Boolean) {
		settings.putBoolean(KEY_ONBOARDING_DISMISSED, dismissed)
		_onboardingDismissed.value = dismissed
	}

	private fun loadAndMigrateProfiles() {
		val legacyConfig = readPersistedFlatConfig()
		val rawProfiles = settings.getString(KEY_SERVER_PROFILES, "")
		val parsedProfiles = if (rawProfiles.isNotBlank()) {
			runCatching { json.decodeFromString<List<MeloServerProfile>>(rawProfiles) }
				.getOrDefault(emptyList())
				.mapNotNull { profile ->
					val cleanId = profile.id.trim()
					val normalizedCfg = normalizeServerConfig(profile.config)
					if (cleanId.isEmpty() || normalizedCfg.baseUrl.isBlank()) {
						null
					} else {
						MeloServerProfile(id = cleanId, config = normalizedCfg)
					}
				}
				.distinctBy { it.id }
		} else {
			emptyList()
		}

		if (parsedProfiles.isNotEmpty()) {
			val savedActiveId = settings.getString(KEY_ACTIVE_PROFILE_ID, "").trim().takeIf { it.isNotEmpty() }
			val activeProfile = parsedProfiles.firstOrNull { it.id == savedActiveId } ?: parsedProfiles.first()
			_profiles.value = parsedProfiles
			_activeProfileId.value = activeProfile.id
			_config.value = activeProfile.config
			if (savedActiveId != activeProfile.id) {
				settings.putString(KEY_ACTIVE_PROFILE_ID, activeProfile.id)
			}
			return
		}

		if (legacyConfig.baseUrl.isNotBlank()) {
			val migrated = MeloServerProfile(id = DEFAULT_PROFILE_ID, config = legacyConfig)
			val initialProfiles = listOf(migrated)
			_profiles.value = initialProfiles
			_activeProfileId.value = DEFAULT_PROFILE_ID
			_config.value = legacyConfig
			persistProfiles(initialProfiles)
			settings.putString(KEY_ACTIVE_PROFILE_ID, DEFAULT_PROFILE_ID)
			persistFlatConfig(legacyConfig)
		} else {
			_profiles.value = emptyList()
			_activeProfileId.value = null
			_config.value = legacyConfig
		}
	}

	private fun normalizeServerConfig(cfg: MeloServerConfig): MeloServerConfig = cfg.copy(
		baseUrl = normalizeBaseUrl(cfg.baseUrl),
		displayName = cfg.displayName.trim(),
	)

	private fun generateUniqueProfileId(existing: List<MeloServerProfile>): String {
		val existingIds = existing.mapTo(mutableSetOf()) { it.id }
		val ts = Clock.System.now().toEpochMilliseconds()
		var suffix = existing.size + 1
		var candidate = "srv-$ts-$suffix"
		while (candidate in existingIds) {
			suffix++
			candidate = "srv-$ts-$suffix"
		}
		return candidate
	}

	private fun readPersistedFlatConfig(): MeloServerConfig {
		val baseUrl = normalizeBaseUrl(settings.getString(KEY_BASE_URL, ""))
		val username = settings.getString(KEY_USERNAME, "")
		val bearerToken = settings.getString(KEY_BEARER_TOKEN, "")
		val tokenId = settings.getString(KEY_TOKEN_ID, "")
		val mediaToken = settings.getString(KEY_MEDIA_TOKEN, "")
		val mediaTokenExpiresAt = settings.getLong(KEY_MEDIA_TOKEN_EXPIRES_AT, 0L)
		val enabled = settings.getBoolean(KEY_ENABLED, false)
		val authEnabled = settings.getBoolean(KEY_AUTH_ENABLED, bearerToken.isNotBlank())
		val displayName = settings.getString(KEY_DISPLAY_NAME, "").trim()
		return MeloServerConfig(
			baseUrl = baseUrl,
			username = username,
			bearerToken = bearerToken,
			tokenId = tokenId,
			mediaToken = mediaToken,
			mediaTokenExpiresAt = mediaTokenExpiresAt,
			enabled = enabled,
			authEnabled = authEnabled,
			displayName = displayName,
		)
	}

	private fun persistFlatConfig(cfg: MeloServerConfig) {
		settings.putString(KEY_BASE_URL, cfg.baseUrl)
		settings.putString(KEY_USERNAME, cfg.username)
		settings.putString(KEY_BEARER_TOKEN, cfg.bearerToken)
		settings.putString(KEY_TOKEN_ID, cfg.tokenId)
		settings.putString(KEY_MEDIA_TOKEN, cfg.mediaToken)
		settings.putLong(KEY_MEDIA_TOKEN_EXPIRES_AT, cfg.mediaTokenExpiresAt)
		settings.putBoolean(KEY_ENABLED, cfg.enabled)
		settings.putBoolean(KEY_AUTH_ENABLED, cfg.authEnabled)
		settings.putString(KEY_DISPLAY_NAME, cfg.displayName.trim())
	}

	private fun persistProfiles(profiles: List<MeloServerProfile>) {
		if (profiles.isEmpty()) {
			settings.remove(KEY_SERVER_PROFILES)
			return
		}
		runCatching {
			settings.putString(KEY_SERVER_PROFILES, json.encodeToString(profiles))
		}
	}

	private fun recordHistory(
		key: String,
		flow: MutableStateFlow<List<String>>,
		query: String,
		maxEntries: Int,
	): List<String> {
		val trimmed = query.trim()
		if (trimmed.isEmpty()) return flow.value
		val next = (listOf(trimmed) + flow.value.filterNot { it.trim().equals(trimmed, ignoreCase = true) })
			.take(maxEntries.coerceAtLeast(1))
		flow.value = next
		persistHistory(key, next)
		return next
	}

	private fun removeHistory(
		key: String,
		flow: MutableStateFlow<List<String>>,
		query: String,
	) {
		val trimmed = query.trim()
		if (trimmed.isEmpty()) return
		val current = flow.value
		val next = current.filterNot { it.trim().equals(trimmed, ignoreCase = true) }
		if (next == current) return
		flow.value = next
		persistHistory(key, next)
	}

	private fun clearHistory(
		key: String,
		flow: MutableStateFlow<List<String>>,
	) {
		flow.value = emptyList()
		settings.remove(key)
	}

	private fun readHistory(key: String): List<String> {
		val raw = settings.getString(key, "")
		if (raw.isBlank()) return emptyList()
		return runCatching { json.decodeFromString<List<String>>(raw) }
			.getOrDefault(emptyList())
			.map { it.trim() }
			.filter { it.isNotEmpty() }
	}

	private fun persistHistory(key: String, entries: List<String>) {
		if (entries.isEmpty()) {
			settings.remove(key)
			return
		}
		runCatching {
			settings.putString(key, json.encodeToString(entries))
		}
	}

	private fun readStudioThemePreset(): String {
		val raw = settings.getString(KEY_STUDIO_THEME_PRESET, DEFAULT_STUDIO_THEME_PRESET)
		return raw.trim().takeIf { it in SUPPORTED_THEME_PRESETS } ?: DEFAULT_STUDIO_THEME_PRESET
	}

	private fun readOnboardingDismissed(): Boolean {
		return settings.getBoolean(KEY_ONBOARDING_DISMISSED, false)
	}

	companion object {
		const val MAX_SEARCH_HISTORY = 20
		const val KEY_SERVER_PROFILES = "melokeep.server_profiles"
		const val KEY_ACTIVE_PROFILE_ID = "melokeep.active_profile_id"
		private const val DEFAULT_PROFILE_ID = "default"
		private const val KEY_BASE_URL = "melokeep.base_url"
		private const val KEY_USERNAME = "melokeep.username"
		private const val KEY_BEARER_TOKEN = "melokeep.bearer_token"
		private const val KEY_TOKEN_ID = "melokeep.token_id"
		private const val KEY_MEDIA_TOKEN = "melokeep.media_token"
		private const val KEY_MEDIA_TOKEN_EXPIRES_AT = "melokeep.media_token_expires_at"
		private const val KEY_ENABLED = "melokeep.enabled"
		private const val KEY_AUTH_ENABLED = "melokeep.auth_enabled"
		private const val KEY_DISPLAY_NAME = "melokeep.display_name"
		private const val KEY_SEARCH_HISTORY = "melokeep.search_history"
		private const val KEY_LIBRARY_SEARCH_HISTORY = "melo_library_search_history"
		private const val KEY_STUDIO_THEME_PRESET = "melo_studio_theme_preset"
		private const val KEY_ONBOARDING_DISMISSED = "melo_onboarding_dismissed"
		private const val DEFAULT_STUDIO_THEME_PRESET = "obsidian"
		private val SUPPORTED_THEME_PRESETS = setOf(
			"obsidian",
			"vinyl-gold",
			"midnight-aurora",
			"cyber-amethyst",
			"pure-light",
			"warm-vinyl",
			"nordic-frost",
			"rose-mist",
			"dynamic",
		)

		fun normalizeBaseUrl(raw: String): String {
			var cleaned = buildString(raw.length) {
				for (ch in raw) {
					when {
						ch == '\uFF1A' -> append(':')
						ch == '\uFF0F' -> append('/')
						ch.isISOControl() -> {}
						ch == '\u200B' || ch == '\u200C' || ch == '\u200D' || ch == '\uFEFF' -> {}
						else -> append(ch)
					}
				}
			}.trim()
			if (cleaned.isEmpty()) return ""

			cleaned = cleaned.substringBefore('#').substringBefore('?').trim()
			if (cleaned.isEmpty()) return ""

			val schemeSepIdx = cleaned.indexOf("://")
			val explicitScheme: String?
			val remainder: String
			if (schemeSepIdx >= 0) {
				explicitScheme = cleaned.substring(0, schemeSepIdx).trim().lowercase()
				if (explicitScheme.isEmpty()) return ""
				remainder = cleaned.substring(schemeSepIdx + 3).trimStart('/')
			} else {
				explicitScheme = null
				remainder = cleaned
			}
			if (remainder.isEmpty()) return ""

			val slashIdx = remainder.indexOf('/')
			val rawHostPort = if (slashIdx >= 0) remainder.substring(0, slashIdx) else remainder
			val rawPath = if (slashIdx >= 0) remainder.substring(slashIdx) else ""
			val lowerHostPort = rawHostPort.trim().lowercase()
			if (lowerHostPort.isEmpty()) return ""
			val cleanHostPort = if (lowerHostPort.count { it == ':' } == 1 && lowerHostPort.endsWith(':')) {
				lowerHostPort.dropLast(1)
			} else {
				lowerHostPort
			}
			if (cleanHostPort.isEmpty()) return ""

			var path = rawPath.trimEnd('/')
			while (true) {
				val stripped = when {
					path.endsWith("/api/health", ignoreCase = true) -> path.dropLast("/api/health".length)
					path.endsWith("/index.html", ignoreCase = true) -> path.dropLast("/index.html".length)
					path.endsWith("/login", ignoreCase = true) -> path.dropLast("/login".length)
					else -> break
				}
				path = stripped.trimEnd('/')
			}

			val scheme = explicitScheme ?: if (isDirectOrLanHostPort(cleanHostPort)) "http" else "https"
			return "$scheme://$cleanHostPort$path"
		}

		private fun isDirectOrLanHostPort(hostPort: String): Boolean {
			val authority = hostPort.substringAfterLast('@')
			if (authority.startsWith("[")) return true
			if (authority.count { it == ':' } >= 2) return true

			val colonIdx = authority.lastIndexOf(':')
			val host: String
			val port: Int?
			if (colonIdx >= 0) {
				host = authority.substring(0, colonIdx)
				port = authority.substring(colonIdx + 1).toIntOrNull()
			} else {
				host = authority
				port = null
			}
			if (isIpv4Address(host)) return true
			if (host == "localhost" || host.endsWith(".localhost")) return true
			if (host.endsWith(".local") || host.endsWith(".lan")) return true
			if (port != null && port != 443) return true
			return false
		}

		private fun isIpv4Address(host: String): Boolean {
			val parts = host.split('.')
			if (parts.size != 4) return false
			return parts.all { part ->
				part.length in 1..3 &&
					part.all { it.isDigit() } &&
					(part.toIntOrNull() in 0..255)
			}
		}
	}
}
