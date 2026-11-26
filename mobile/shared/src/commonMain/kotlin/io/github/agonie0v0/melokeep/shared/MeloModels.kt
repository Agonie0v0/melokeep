package io.github.agonie0v0.melokeep.shared

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class MeloApiException(
	val statusCode: Int,
	override val message: String,
	val detail: String = message,
) : Exception(message) {
	val isUnauthorized: Boolean
		get() = statusCode == 401
}

@Serializable
data class MeloServerConfig(
	@SerialName("base_url")
	val baseUrl: String = "",
	val username: String = "",
	@SerialName("bearer_token")
	val bearerToken: String = "",
	@SerialName("token_id")
	val tokenId: String = "",
	@SerialName("media_token")
	val mediaToken: String = "",
	@SerialName("media_token_expires_at")
	val mediaTokenExpiresAt: Long = 0L,
	val enabled: Boolean = false,
	@SerialName("auth_enabled")
	val authEnabled: Boolean = false,
	@SerialName("display_name")
	val displayName: String = "",
) {
	val isConfigured: Boolean
		get() = enabled && baseUrl.isNotBlank()

	val isAuthenticated: Boolean
		get() = isConfigured && (!authEnabled || bearerToken.isNotBlank())

	val effectiveName: String
		get() = displayName.trim().ifBlank { "MeloKeep" }
}

@Serializable
data class MeloServerProfile(
	val id: String,
	val config: MeloServerConfig = MeloServerConfig(),
)

@Serializable
data class MeloHealthInfo(
	val ok: Boolean = false,
	val backend: String = "",
	val engine: String = "",
	val version: String = "",
	val sources: Int = 0,
	@SerialName("in_container")
	val inContainer: Boolean = false,
)

data class MeloProbeResult(
	val health: MeloHealthInfo,
	val resolvedBaseUrl: String,
)

@Serializable
data class MeloAuthStatus(
	val required: Boolean = false,
	val authenticated: Boolean = false,
	@SerialName("media_token")
	val mediaToken: String = "",
	@SerialName("media_token_expires_at")
	val mediaTokenExpiresAt: Long = 0L,
)

@Serializable
data class MeloLoginRequest(
	val username: String = "",
	val password: String = "",
	@SerialName("client_type")
	val clientType: String = "app",
	@SerialName("device_name")
	val deviceName: String = "MeloKeep Mobile",
)

@Serializable
data class MeloLoginResponse(
	val success: Boolean = false,
	@SerialName("auth_enabled")
	val authEnabled: Boolean = false,
	val token: String = "",
	@SerialName("token_id")
	val tokenId: String = "",
	@SerialName("expires_at")
	val expiresAt: Long = 0L,
	@SerialName("media_token")
	val mediaToken: String = "",
	@SerialName("media_token_expires_at")
	val mediaTokenExpiresAt: Long = 0L,
)

@Serializable
data class MeloMediaTokenResponse(
	@SerialName("media_token")
	val mediaToken: String = "",
	@SerialName("expires_at")
	val expiresAt: Long = 0L,
)

@Serializable
data class MeloDeviceToken(
	val id: String = "",
	@SerialName("user_id")
	val userId: String = "",
	@SerialName("device_name")
	val deviceName: String = "",
	@SerialName("created_at")
	val createdAt: Long = 0L,
	@SerialName("last_used_at")
	val lastUsedAt: Long = 0L,
	@SerialName("expires_at")
	val expiresAt: Long = 0L,
	val current: Boolean = false,
)

@Serializable
data class MeloDevicesResponse(
	val devices: List<MeloDeviceToken> = emptyList(),
)

@Serializable
data class MeloQuality(
	val id: String = "",
	val label: String = "",
	val codec: String = "",
	val bitrate: Int? = null,
	val lossless: Boolean = false,
	val availability: String = "",
	val evidence: String = "",
	@SerialName("size_bytes")
	val sizeBytes: Long? = null,
	@SerialName("size_label")
	val sizeLabel: String? = null,
)

@Serializable
data class MeloPlaybackInfo(
	val state: String = "",
	val url: String? = null,
	val codec: String? = null,
	val bitrate: Int? = null,
	val detail: String? = null,
	@SerialName("preview_only")
	val previewOnly: Boolean = false,
)

@Serializable
data class MeloTrack(
	@SerialName("playlist_index")
	val playlistIndex: Int? = null,
	val id: String = "",
	val source: String = "",
	@SerialName("source_id")
	val sourceId: String = "",
	val title: String = "",
	val artist: String = "",
	val album: String = "",
	val duration: Double = 0.0,
	val cover: String? = null,
	@SerialName("size_bytes")
	val sizeBytes: Long? = null,
	@SerialName("size_label")
	val sizeLabel: String? = null,
	val episodes: Int = 0,
	@SerialName("root_source")
	val rootSource: String = "",
	val qualities: List<MeloQuality> = emptyList(),
	val playback: MeloPlaybackInfo = MeloPlaybackInfo(),
) {
	val durationMs: Long
		get() = (duration * 1000.0).toLong().coerceAtLeast(0L)

	val primaryQuality: MeloQuality?
		get() = qualities.firstOrNull()

	val hasLossless: Boolean
		get() = qualities.any { it.lossless }

	val bestBitrate: Int?
		get() = playback.bitrate ?: qualities.mapNotNull { it.bitrate }.maxOrNull()

	val codecLabel: String?
		get() = playback.codec?.takeIf { it.isNotBlank() }
			?: primaryQuality?.codec?.takeIf { it.isNotBlank() }
}

@Serializable
data class MeloLyricsResponse(
	val text: String = "",
)

@Serializable
data class MeloSearchCreateRequest(
	val keyword: String,
	val sources: List<String> = emptyList(),
	val refresh: Boolean = false,
	val limit: Int? = null,
)

@Serializable
data class MeloSearchCreateResponse(
	val id: String = "",
)

@Serializable
data class MeloSourceStatus(
	val state: String = "",
	val count: Int? = null,
	val detail: String = "",
)

@Serializable
data class MeloSourceEventPayload(
	val id: String = "",
	val status: MeloSourceStatus = MeloSourceStatus(),
)

@Serializable
data class MeloDoneEventPayload(
	val cancelled: Boolean = false,
)

sealed interface MeloSearchEvent {
	val eventId: String?

	data class Candidate(
		val track: MeloTrack,
		override val eventId: String? = null,
	) : MeloSearchEvent

	data class Track(
		val track: MeloTrack,
		override val eventId: String? = null,
	) : MeloSearchEvent

	data class Source(
		val sourceId: String,
		val status: MeloSourceStatus,
		override val eventId: String? = null,
	) : MeloSearchEvent

	data class Done(
		val cancelled: Boolean = false,
		override val eventId: String? = null,
	) : MeloSearchEvent

	data class Error(
		val detail: String,
		override val eventId: String? = null,
	) : MeloSearchEvent
}

@Serializable
data class MeloDownloadProcessing(
	val id: String = "",
	val status: String = "",
	val detail: String = "",
)

@Serializable
data class MeloDownloadJob(
	val id: String = "",
	val track: MeloTrack = MeloTrack(),
	@SerialName("quality_id")
	val qualityId: String = "",
	@SerialName("quality_label")
	val qualityLabel: String = "",
	@SerialName("download_source")
	val downloadSource: String = "",
	val status: String = "",
	val bytes: Long = 0L,
	@SerialName("total_bytes")
	val totalBytes: Long? = null,
	val detail: String = "",
	val file: String? = null,
	val processing: MeloDownloadProcessing? = null,
) {
	val progress: Float
		get() {
			val total = totalBytes ?: return if (status == "complete") 1f else 0f
			if (total <= 0L) return if (status == "complete") 1f else 0f
			return (bytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
		}
}

@Serializable
data class MeloDownloadEnqueueItem(
	@SerialName("track_id")
	val trackId: String,
	@SerialName("quality_id")
	val qualityId: String = "",
	@SerialName("force_new")
	val forceNew: Boolean = false,
	@SerialName("intent_nonce")
	val intentNonce: String = "",
	@SerialName("allow_preview")
	val allowPreview: Boolean = false,
)

@Serializable
data class MeloDeleteDownloadResponse(
	val removed: Boolean = true,
	@SerialName("file_deleted")
	val fileDeleted: Boolean = false,
	@SerialName("track_ids")
	val trackIds: List<String> = emptyList(),
)

@Serializable
data class MeloLibraryFile(
	val path: String = "",
	val name: String = "",
	val size: Long = 0L,
	val root: String = "",
	val folder: String = "",
	val track: MeloTrack? = null,
	val editable: Boolean? = null,
	val metadata: Map<String, String> = emptyMap(),
	@SerialName("locked_fields")
	val lockedFields: List<String> = emptyList(),
)

@Serializable
data class MeloLibraryFolder(
	val root: String = "",
	val folder: String = "",
)

@Serializable
data class MeloLibraryFilesPage(
	val items: List<MeloLibraryFile> = emptyList(),
	@SerialName("next_cursor")
	val nextCursor: String? = null,
	@SerialName("index_revision")
	val indexRevision: Long = 0L,
	val indexing: Boolean = false,
	val folders: List<MeloLibraryFolder> = emptyList(),
)

@Serializable
data class MeloLibraryRefreshResult(
	@SerialName("roots_scanned") val rootsScanned: Int = 0,
	@SerialName("scanned_files") val scannedFiles: Int = 0,
	@SerialName("unchanged_files") val unchangedFiles: Int = 0,
	@SerialName("updated_or_new") val updatedOrNew: Int = 0,
	@SerialName("marked_missing") val markedMissing: Int = 0,
	@SerialName("unhealthy_roots") val unhealthyRoots: List<String> = emptyList(),
)

@Serializable
data class MeloMetadataCandidate(
	val id: String = "",
	val source: String = "",
	val title: String = "",
	val artist: String = "",
	val album: String = "",
	val score: Double = 0.0,
	val reasons: List<String> = emptyList(),
	val conflicts: List<String> = emptyList(),
	@SerialName("field_sources")
	val fieldSources: Map<String, String> = emptyMap(),
)

@Serializable
data class MeloLibraryJobOutput(
	val path: String = "",
	val start: Double? = null,
	val end: Double? = null,
)

@Serializable
data class MeloLibraryJobArtwork(
	val artist: String = "",
	val source: String = "",
	val url: String = "",
)

@Serializable
data class MeloLibraryJobItem(
	val id: String = "",
	val path: String = "",
	val name: String = "",
	@SerialName("original_path")
	val originalPath: String = "",
	val target: String = "",
	val status: String = "",
	val before: Map<String, String> = emptyMap(),
	val after: Map<String, String> = emptyMap(),
	val candidates: List<MeloMetadataCandidate> = emptyList(),
	val chosen: String? = null,
	val locks: List<String> = emptyList(),
	val warnings: List<String> = emptyList(),
	val error: String = "",
	@SerialName("operation_id")
	val operationId: String = "",
	val quality: Map<String, Double?> = emptyMap(),
	val outputs: List<MeloLibraryJobOutput> = emptyList(),
	val artwork: MeloLibraryJobArtwork? = null,
)

@Serializable
data class MeloLibraryJobSettings(
	val sources: List<String> = emptyList(),
	val overwrite: Boolean = false,
	val fields: List<String> = emptyList(),
)

@Serializable
data class MeloLibraryDuplicate(
	val id: String = "",
	val name: String = "",
	val path: String = "",
)

@Serializable
data class MeloLibraryJob(
	val id: String = "",
	val action: String = "",
	val status: String = "",
	@SerialName("created_at")
	val createdAt: Long = 0L,
	@SerialName("updated_at")
	val updatedAt: Long = 0L,
	val total: Int = 0,
	val processed: Int = 0,
	val detail: String = "",
	val items: List<MeloLibraryJobItem> = emptyList(),
	val warnings: List<String> = emptyList(),
	val settings: MeloLibraryJobSettings? = null,
	val duplicates: List<List<MeloLibraryDuplicate>> = emptyList(),
)

@Serializable
data class MeloLibraryJobsPage(
	val jobs: List<MeloLibraryJob> = emptyList(),
	@SerialName("has_more")
	val hasMore: Boolean = false,
	val total: Int = 0,
)

@Serializable
data class MeloScrapeJobRequest(
	val paths: List<String>,
	val action: String = "both",
	@SerialName("scope_dir")
	val scopeDir: String? = null,
	val sources: List<String>? = null,
	val overwrite: Boolean? = null,
	@SerialName("auto_apply")
	val autoApply: Boolean = false,
	val changes: Map<String, String>? = null,
	@SerialName("source_download_id")
	val sourceDownloadId: String? = null,
)

@Serializable
data class MeloLibraryApplyRequest(
	val choices: Map<String, String> = emptyMap(),
)

@Serializable
internal data class MeloLibraryCandidateSearchRequest(
	val item: String,
	val query: String,
	val sources: List<String>? = null,
)

@Serializable
data class MeloNavidromeScanStatus(
	val scanning: Boolean = false,
	val count: Long = 0L,
	val folderCount: Long = 0L,
)

@Serializable
data class MeloAgentSessionSummary(
	val id: String = "",
	val title: String = "",
	val status: String = "",
)

@Serializable
data class MeloAgentPlanStep(
	val title: String = "",
	val status: String = "",
)

@Serializable
data class MeloAgentPlan(
	val goal: String = "",
	val steps: List<MeloAgentPlanStep> = emptyList(),
)

@Serializable
data class MeloAgentMessage(
	val role: String = "",
	val content: String = "",
	@SerialName("has_image")
	val hasImage: Boolean = false,
	@SerialName("turn_id")
	val turnId: String = "",
	@SerialName("created_at")
	val createdAt: Long = 0L,
)

@Serializable
data class MeloAgentEvent(
	val id: String = "",
	@SerialName("turn_id")
	val turnId: String = "",
	@SerialName("created_at")
	val createdAt: Long = 0L,
	val tool: String = "",
	val operation: String = "",
	val status: String = "",
	val detail: String = "",
	val result: JsonObject? = null,
)

@Serializable
data class MeloAgentSession(
	val id: String = "",
	val title: String = "",
	val status: String = "",
	val detail: String = "",
	val partial: String = "",
	val decision: String = "",
	val plan: MeloAgentPlan? = null,
	val messages: List<MeloAgentMessage> = emptyList(),
	val events: List<MeloAgentEvent> = emptyList(),
)

@Serializable
data class MeloAgentTurnRequest(
	val message: String,
	val image: String? = null,
	@SerialName("allow_actions")
	val allowActions: Boolean? = null,
	@SerialName("track_ids")
	val trackIds: List<String>? = null,
	@SerialName("request_id")
	val requestId: String? = null,
)

@Serializable
data class MeloSourceCapability(
	val tier: String = "",
	val note: String = "",
	val search: Boolean = false,
	val playlist: Boolean = false,
	val partial: Boolean? = null,
	val lyrics: Boolean = false,
	val metadata: Boolean = false,
	val episodes: Boolean = false,
)

@Serializable
data class MeloAvailableSource(
	val id: String = "",
	val label: String = "",
	val category: String = "",
	@SerialName("engine_name")
	val engineName: String = "",
	@SerialName("proxy_recommended")
	val proxyRecommended: Boolean = false,
	@SerialName("init_error")
	val initError: String? = null,
	val capability: MeloSourceCapability? = null,
	val origin: String = "",
	@SerialName("plugin_id")
	val pluginId: String = "",
	val version: String = "",
	val description: String = "",
)

object MeloSourceLabelRegistry {
	private val labelsFlow = MutableStateFlow<Map<String, String>>(emptyMap())

	fun snapshot(): Map<String, String> = labelsFlow.value

	fun register(sources: List<MeloAvailableSource>) {
		val merged = labelsFlow.value.toMutableMap()
		for (src in sources) {
			val label = src.label.trim()
			if (label.isEmpty()) continue
			for (key in listOf(src.id, src.engineName, src.pluginId)) {
				val k = key.trim().lowercase()
				if (k.isNotEmpty()) merged[k] = label
			}
		}
		labelsFlow.value = merged.toMap()
	}

	fun resolve(source: String, overrides: Map<String, String> = emptyMap()): String? {
		val raw = source.trim()
		if (raw.isEmpty()) return null
		overrides[raw]?.takeIf { it.isNotBlank() }?.let { return it }
		for ((k, v) in overrides) {
			if (v.isNotBlank() && k.trim().equals(raw, ignoreCase = true)) return v
		}
		return labelsFlow.value[raw.lowercase()]
	}

	internal fun clearForTest() {
		labelsFlow.value = emptyMap()
	}
}

@Serializable
data class MeloSourceSetting(
	val cookie: String = "",
	val proxy: String = "",
)

@Serializable
data class MeloEngineConfig(
	@SerialName("available_sources")
	val availableSources: List<MeloAvailableSource> = emptyList(),
	val engine: String = "",
	val sources: List<String> = emptyList(),
	@SerialName("results_per_source")
	val resultsPerSource: Int = 20,
	@SerialName("search_timeout_seconds")
	val searchTimeoutSeconds: Int = 15,
	@SerialName("playback_timeout_seconds")
	val playbackTimeoutSeconds: Int = 15,
	@SerialName("download_concurrency")
	val downloadConcurrency: Int = 2,
	@SerialName("download_layout")
	val downloadLayout: String = "source",
	@SerialName("lyrics_mode")
	val lyricsMode: String = "both",
	@SerialName("embed_metadata")
	val embedMetadata: Boolean = true,
	@SerialName("source_settings")
	val sourceSettings: Map<String, MeloSourceSetting> = emptyMap(),
	@SerialName("proxy_url")
	val proxyUrl: String = "",
	@SerialName("proxy_scope")
	val proxyScope: String = "all",
	@SerialName("proxy_sources")
	val proxySources: List<String> = emptyList(),
)

@Serializable
data class MeloConfigUpdateRequest(
	val sources: List<String>? = null,
	@SerialName("results_per_source")
	val resultsPerSource: Int? = null,
	@SerialName("search_timeout_seconds")
	val searchTimeoutSeconds: Int? = null,
	@SerialName("playback_timeout_seconds")
	val playbackTimeoutSeconds: Int? = null,
	@SerialName("download_concurrency")
	val downloadConcurrency: Int? = null,
	@SerialName("download_layout")
	val downloadLayout: String? = null,
	@SerialName("lyrics_mode")
	val lyricsMode: String? = null,
	@SerialName("embed_metadata")
	val embedMetadata: Boolean? = null,
	@SerialName("source_settings")
	val sourceSettings: Map<String, MeloSourceSetting>? = null,
	@SerialName("proxy_url")
	val proxyUrl: String? = null,
	@SerialName("proxy_scope")
	val proxyScope: String? = null,
	@SerialName("proxy_sources")
	val proxySources: List<String>? = null,
)

@Serializable
data class MeloConfigSaveResponse(
	val saved: Boolean = false,
	@SerialName("restart_required")
	val restartRequired: Boolean = false,
)

@Serializable
data class MeloTrackGroup(
	val groupKey: String,
	val primary: MeloTrack,
	val alternatives: List<MeloTrack> = emptyList(),
)

@Serializable
data class MeloFolderMutationResult(
	val root: String = "",
	val folder: String = "",
)

@Serializable
data class MeloMoveResult(
	val moved: Int = 0,
	val failed: List<String> = emptyList(),
)

@Serializable
data class MeloBatchDeleteResult(
	@SerialName("track_ids")
	val trackIds: List<String> = emptyList(),
	val failed: List<String> = emptyList(),
)

@Serializable
data class MeloTagVersion(
	@SerialName("job_id")
	val jobId: String = "",
	val action: String = "",
	val status: String = "",
	@SerialName("created_at")
	val createdAt: JsonElement? = null,
	val before: Map<String, String> = emptyMap(),
	val after: Map<String, String> = emptyMap(),
	val restorable: Boolean = false,
)

@Serializable
data class MeloTagVersionsResponse(
	val versions: List<MeloTagVersion> = emptyList(),
)

@Serializable
data class MeloNavidromeCapabilities(
	@SerialName("delete_songs")
	val deleteSongs: Boolean = false,
)

@Serializable
data class MeloNavidromeDeletionPreview(
	val path: String = "",
	val revision: String = "",
)

@Serializable
data class MeloNavidromeDeleteResult(
	@SerialName("track_ids")
	val trackIds: List<String> = emptyList(),
	val warning: String? = null,
	val scan: MeloNavidromeScanStatus? = null,
)

@Serializable
internal data class MeloFolderMkdirRequest(
	val root: String = "",
	val parent: String = "",
	val name: String,
)

@Serializable
internal data class MeloFolderRenameRequest(
	val root: String = "",
	val folder: String,
	@SerialName("new_name")
	val newName: String,
)

@Serializable
internal data class MeloMoveEntriesRequest(
	val files: List<String> = emptyList(),
	val folders: List<MeloLibraryFolder> = emptyList(),
	@SerialName("target_root")
	val targetRoot: String,
	@SerialName("target_folder")
	val targetFolder: String,
)

@Serializable
internal data class MeloBatchDeleteRequest(
	val files: List<String> = emptyList(),
	val folders: List<MeloLibraryFolder> = emptyList(),
)

@Serializable
data class MeloDeleteLibraryJobsResult(
	val deleted: Int = 0,
	@SerialName("deleted_ids")
	val deletedIds: List<String> = emptyList(),
	@SerialName("skipped_running")
	val skippedRunning: List<String> = emptyList(),
)

@Serializable
data class MeloLocalPlaylistSummary(
	val revision: String = "",
	val owner: String = "",
	val id: String = "",
	val name: String = "",
	val source: String = "",
	@SerialName("source_url")
	val sourceUrl: String? = null,
	@SerialName("created_at")
	val createdAt: Long = 0L,
	val detail: String = "",
	@SerialName("task_id")
	val taskId: String = "",
	@SerialName("track_count")
	val trackCount: Int = 0,
	@SerialName("issue_count")
	val issueCount: Int = 0,
	val cover: String? = null,
)

@Serializable
data class MeloLocalPlaylistDetail(
	val id: String = "",
	val name: String = "",
	val source: String = "",
	@SerialName("source_url")
	val sourceUrl: String? = null,
	@SerialName("created_at")
	val createdAt: Long = 0L,
	val tracks: List<MeloTrack> = emptyList(),
	val detail: String = "",
	@SerialName("task_id")
	val taskId: String = "",
)

@Serializable
internal data class MeloLibraryRestoreRequest(
	val items: List<String> = emptyList(),
)

@Serializable
internal data class MeloBatchDeleteLibraryJobsRequest(
	val ids: List<String> = emptyList(),
	val mode: String? = null,
)

@Serializable
internal data class MeloLocalPlaylistCreateRequest(
	val name: String,
	@SerialName("track_ids")
	val trackIds: List<String> = emptyList(),
)

@Serializable
internal data class MeloLocalPlaylistTracksRequest(
	@SerialName("track_ids")
	val trackIds: List<String>,
)

@Serializable
internal data class MeloLocalPlaylistRenameRequest(
	val name: String,
)

@Serializable
data class MeloSystemUpdateSettings(
	@SerialName("github_token_configured")
	val githubTokenConfigured: Boolean = false,
	@SerialName("github_token_source")
	val githubTokenSource: String = "",
	@SerialName("github_token_saved")
	val githubTokenSaved: Boolean = false,
)

@Serializable
data class MeloAppUpdateResponse(
	val platform: String = "",
	val repo: String = "",
	val release: MeloGitHubRelease? = null,
	@SerialName("checked_at")
	val checkedAt: String = "",
	val cached: Boolean = false,
	val error: String = "",
	@SerialName("github_token_configured")
	val githubTokenConfigured: Boolean = false,
	@SerialName("github_token_source")
	val githubTokenSource: String = "",
	@SerialName("github_token_saved")
	val githubTokenSaved: Boolean = false,
) {
	fun toSettings(): MeloSystemUpdateSettings = MeloSystemUpdateSettings(
		githubTokenConfigured = githubTokenConfigured,
		githubTokenSource = githubTokenSource,
		githubTokenSaved = githubTokenSaved,
	)
}

data class MeloResolvedAppUpdate(
	val release: MeloGitHubRelease?,
	val viaServer: Boolean,
	val tokenSettings: MeloSystemUpdateSettings? = null,
)

@Serializable
internal data class MeloSystemUpdateSettingsRequest(
	@SerialName("github_token")
	val githubToken: String? = null,
	@SerialName("clear_github_token")
	val clearGitHubToken: Boolean = false,
)

@Serializable
data class MeloListeningHistoryRequest(
	@SerialName("track_id")
	val trackId: String = "",
	val title: String,
	val artist: String = "",
	val album: String = "",
	val genre: String = "",
	val source: String = "",
	@SerialName("duration_ms")
	val durationMs: Long = 0L,
	@SerialName("played_ms")
	val playedMs: Long = 0L,
)

@Serializable
data class MeloRecommendSeed(
	val title: String,
	val artist: String = "",
	val album: String = "",
	val genre: String = "",
	val weight: Double = 1.0,
)

@Serializable
data class MeloDailyRecommendStartRequest(
	val force: Boolean = false,
	val seeds: List<MeloRecommendSeed> = emptyList(),
)

@Serializable
data class MeloDailyRecommendItem(
	val track: MeloTrack,
	val reason: String = "",
	val category: String = "discover",
	@SerialName("in_library")
	val inLibrary: Boolean = false,
)

@Serializable
data class MeloDailyRecommendation(
	val date: String = "",
	@SerialName("refresh_seq")
	val refreshSeq: Int = 0,
	@SerialName("target_count")
	val targetCount: Int = 30,
	val status: String = "empty",
	@SerialName("job_id")
	val jobId: String = "",
	val mode: String = "",
	@SerialName("curator_note")
	val curatorNote: String = "",
	@SerialName("style_tags")
	val styleTags: List<String> = emptyList(),
	val items: List<MeloDailyRecommendItem> = emptyList(),
	@SerialName("generated_at")
	val generatedAt: Long = 0L,
	val error: String = "",
)

@Serializable
data class MeloDailyRecommendStatusPayload(
	val stage: String = "",
	val message: String = "",
	val matched: Int = 0,
	val target: Int = 30,
)

@Serializable
data class MeloDailyRecommendMetaPayload(
	val date: String = "",
	@SerialName("refresh_seq")
	val refreshSeq: Int = 0,
	val mode: String = "",
	@SerialName("curator_note")
	val curatorNote: String = "",
	@SerialName("style_tags")
	val styleTags: List<String> = emptyList(),
	@SerialName("target_count")
	val targetCount: Int = 30,
)

@Serializable
data class MeloDailyRecommendItemPayload(
	val index: Int = 0,
	val item: MeloDailyRecommendItem,
)

@Serializable
data class MeloDailyRecommendDonePayload(
	val daily: MeloDailyRecommendation = MeloDailyRecommendation(),
	val cancelled: Boolean = false,
)

sealed interface MeloDailyRecommendEvent {
	data class Status(val payload: MeloDailyRecommendStatusPayload) : MeloDailyRecommendEvent
	data class Meta(val payload: MeloDailyRecommendMetaPayload) : MeloDailyRecommendEvent
	data class Item(val payload: MeloDailyRecommendItemPayload) : MeloDailyRecommendEvent
	data class Done(val payload: MeloDailyRecommendDonePayload) : MeloDailyRecommendEvent
	data class Error(val detail: String) : MeloDailyRecommendEvent
}
