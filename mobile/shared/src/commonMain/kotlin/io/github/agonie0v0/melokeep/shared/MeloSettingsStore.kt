package io.github.agonie0v0.melokeep.shared

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MeloSettingsStore(
	private val settings: Settings = Settings(),
) {
	private val json = Json {
		ignoreUnknownKeys = true
		encodeDefaults = true
	}

	private val _config = MutableStateFlow(readPersistedConfig())
	val config: StateFlow<MeloServerConfig> = _config.asStateFlow()

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
		val normalized = newConfig.copy(
			baseUrl = normalizeBaseUrl(newConfig.baseUrl),
			displayName = newConfig.displayName.trim(),
		)
		persistConfig(normalized)
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
		displayName: String = _config.value.displayName,
	): MeloServerConfig = saveConfig(
		MeloServerConfig(
			baseUrl = baseUrl,
			username = username.trim(),
			bearerToken = bearerToken.trim(),
			tokenId = tokenId.trim(),
			mediaToken = mediaToken.trim(),
			mediaTokenExpiresAt = mediaTokenExpiresAt,
			enabled = enabled,
			authEnabled = authEnabled,
			displayName = displayName.trim(),
		)
	)

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
		saveConfig(MeloServerConfig())
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

	private fun readPersistedConfig(): MeloServerConfig {
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

	private fun persistConfig(cfg: MeloServerConfig) {
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
		private val SUPPORTED_THEME_PRESETS = setOf("obsidian", "vinyl-gold", "pure-light", "dynamic")

		fun normalizeBaseUrl(raw: String): String {
			val trimmed = raw.trim().trimEnd('/')
			if (trimmed.isEmpty()) return ""
			return if ("://" in trimmed) trimmed else "http://$trimmed"
		}
	}
}
