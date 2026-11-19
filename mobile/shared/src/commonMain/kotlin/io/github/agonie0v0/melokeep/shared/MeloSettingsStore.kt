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

	private val _searchHistory = MutableStateFlow(readSearchHistory())
	val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

	private val _studioThemePreset = MutableStateFlow(readStudioThemePreset())
	val studioThemePreset: StateFlow<String> = _studioThemePreset.asStateFlow()

	private val _onboardingDismissed = MutableStateFlow(readOnboardingDismissed())
	val onboardingDismissed: StateFlow<Boolean> = _onboardingDismissed.asStateFlow()

	fun currentConfig(): MeloServerConfig = _config.value

	fun saveConfig(newConfig: MeloServerConfig): MeloServerConfig {
		val normalized = newConfig.copy(baseUrl = normalizeBaseUrl(newConfig.baseUrl))
		persistConfig(normalized)
		_config.value = normalized
		return normalized
	}

	fun updateConfig(transform: (MeloServerConfig) -> MeloServerConfig): MeloServerConfig {
		val updated = transform(_config.value)
		return saveConfig(updated)
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

	fun recordSearchHistory(keyword: String, maxEntries: Int = 12): List<String> {
		val trimmed = keyword.trim()
		if (trimmed.isEmpty()) return _searchHistory.value
		val next = (listOf(trimmed) + _searchHistory.value.filterNot { it.equals(trimmed, ignoreCase = true) })
			.take(maxEntries.coerceAtLeast(1))
		_searchHistory.value = next
		runCatching {
			settings.putString(KEY_SEARCH_HISTORY, json.encodeToString(next))
		}
		return next
	}

	fun clearSearchHistory() {
		_searchHistory.value = emptyList()
		settings.remove(KEY_SEARCH_HISTORY)
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
		return MeloServerConfig(
			baseUrl = baseUrl,
			username = username,
			bearerToken = bearerToken,
			tokenId = tokenId,
			mediaToken = mediaToken,
			mediaTokenExpiresAt = mediaTokenExpiresAt,
			enabled = enabled,
			authEnabled = authEnabled,
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
	}

	private fun readSearchHistory(): List<String> {
		val raw = settings.getString(KEY_SEARCH_HISTORY, "")
		if (raw.isBlank()) return emptyList()
		return runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
	}

	private fun readStudioThemePreset(): String {
		val raw = settings.getString(KEY_STUDIO_THEME_PRESET, DEFAULT_STUDIO_THEME_PRESET)
		return raw.trim().takeIf { it in SUPPORTED_THEME_PRESETS } ?: DEFAULT_STUDIO_THEME_PRESET
	}

	private fun readOnboardingDismissed(): Boolean {
		return settings.getBoolean(KEY_ONBOARDING_DISMISSED, false)
	}

	companion object {
		private const val KEY_BASE_URL = "melokeep.base_url"
		private const val KEY_USERNAME = "melokeep.username"
		private const val KEY_BEARER_TOKEN = "melokeep.bearer_token"
		private const val KEY_TOKEN_ID = "melokeep.token_id"
		private const val KEY_MEDIA_TOKEN = "melokeep.media_token"
		private const val KEY_MEDIA_TOKEN_EXPIRES_AT = "melokeep.media_token_expires_at"
		private const val KEY_ENABLED = "melokeep.enabled"
		private const val KEY_AUTH_ENABLED = "melokeep.auth_enabled"
		private const val KEY_SEARCH_HISTORY = "melokeep.search_history"
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
