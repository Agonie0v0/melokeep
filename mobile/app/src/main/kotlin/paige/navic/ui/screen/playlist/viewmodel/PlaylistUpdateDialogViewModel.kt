/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.screen.playlist.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zt64.subsonic.api.model.SubsonicErrorCode
import dev.zt64.subsonic.api.model.SubsonicException
import io.github.agonie0v0.melokeep.shared.MeloApiException
import io.github.agonie0v0.melokeep.shared.MeloKeepApiClient
import io.github.agonie0v0.melokeep.shared.MeloLocalLibraryController
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import paige.navic.R
import paige.navic.database.dao.PlaylistDao
import paige.navic.database.mapper.toEntity
import paige.navic.domain.manager.session.SessionManager
import paige.navic.domain.manager.session.SessionState
import paige.navic.domain.manager.ui.SnackBarManager
import paige.navic.melokeep.MeloTrackMapper
import paige.navic.util.Logger
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

enum class PlaylistTargetBackend(
	val displayName: String,
	val serviceLabel: String
) {
	MELOKEEP("MeloKeep 歌单", "MeloKeep 服务"),
	NAVIDROME("Navidrome 歌单", "Navidrome 服务")
}

data class PlaylistPickerItem(
	val id: String,
	val name: String,
	val trackCount: Int,
	val coverArtId: String? = null
)

data class PlaylistOperationError(
	val sourceLabel: String,
	val actionLabel: String,
	val summary: String,
	val technicalDetail: String,
	val isConnectionMissing: Boolean = false,
)

internal fun isUnsupportedPlaylistSongId(id: String): Boolean {
	val trimmed = id.trim()
	return trimmed.isEmpty() ||
		trimmed.startsWith("device:") ||
		trimmed.startsWith("radio_")
}

internal fun filterSupportedPlaylistSongIds(songIds: List<String>): List<String> =
	songIds.map { it.trim() }
		.filterNot(::isUnsupportedPlaylistSongId)
		.distinct()

internal fun resolvePlaylistTargetBackend(songIds: List<String>): PlaylistTargetBackend {
	val supported = filterSupportedPlaylistSongIds(songIds)
	return if (supported.any { MeloTrackMapper.isMeloSongId(it) }) {
		PlaylistTargetBackend.MELOKEEP
	} else {
		PlaylistTargetBackend.NAVIDROME
	}
}

internal fun mapSongIdsForBackend(
	songIds: List<String>,
	backend: PlaylistTargetBackend
): List<String> {
	val supported = filterSupportedPlaylistSongIds(songIds)
	return when (backend) {
		PlaylistTargetBackend.MELOKEEP -> supported
			.filter { MeloTrackMapper.isMeloSongId(it) }
			.map { MeloTrackMapper.toRawTrackId(it).trim() }
			.filter { it.isNotEmpty() }
			.distinct()

		PlaylistTargetBackend.NAVIDROME -> supported
			.filterNot { MeloTrackMapper.isMeloSongId(it) }
			.distinct()
	}
}

internal fun buildUnsupportedSongsError(
	actionLabel: String,
	rawSongIds: List<String>
): PlaylistOperationError {
	val idsSummary = rawSongIds.joinToString(", ").ifBlank { "(空)" }
	return PlaylistOperationError(
		sourceLabel = "曲目来源检查",
		actionLabel = actionLabel,
		summary = "当前曲目为本机文件或电台，暂不支持加入服务端歌单",
		technicalDetail = buildString {
			appendLine("来源: 曲目来源检查")
			appendLine("操作: $actionLabel")
			appendLine("原始曲目 ID: $idsSummary")
			append("说明: device: 手机本地文件与 radio_ 电台流不属于服务端歌单曲目。")
		},
		isConnectionMissing = false
	)
}

internal fun buildConnectionMissingError(
	backend: PlaylistTargetBackend,
	actionLabel: String,
	diagnosticContext: String? = null
): PlaylistOperationError {
	val summary = when (backend) {
		PlaylistTargetBackend.MELOKEEP -> "尚未连接或登录 MeloKeep 服务器"
		PlaylistTargetBackend.NAVIDROME -> "尚未连接或登录 Navidrome 服务器"
	}
	val detail = buildString {
		appendLine("来源: ${backend.serviceLabel}")
		appendLine("操作: $actionLabel")
		appendLine("原因: 当前未配置有效登录会话，无法读取或写入${backend.displayName}。")
		if (!diagnosticContext.isNullOrBlank()) {
			append(diagnosticContext)
		}
	}.trim()
	return PlaylistOperationError(
		sourceLabel = backend.serviceLabel,
		actionLabel = actionLabel,
		summary = summary,
		technicalDetail = detail,
		isConnectionMissing = true
	)
}

internal fun toPlaylistOperationError(
	backend: PlaylistTargetBackend,
	actionLabel: String,
	throwable: Throwable,
	diagnosticContext: String? = null
): PlaylistOperationError {
	Logger.e(
		"PlaylistOperation",
		"[${backend.serviceLabel} · $actionLabel] ${throwable::class.java.simpleName}: ${throwable.message}",
		throwable
	)

	val chain = generateSequence(throwable) { it.cause }.toList()
	val meloEx = chain.filterIsInstance<MeloApiException>().firstOrNull()
	val subsonicEx = chain.filterIsInstance<SubsonicException>().firstOrNull()
	val responseEx = chain.filterIsInstance<ResponseException>().firstOrNull()

	var isConnectionMissing = false
	val summary = when {
		meloEx != null -> {
			when (meloEx.statusCode) {
				0 -> {
					val msg = meloEx.message.trim()
					if (msg.contains("配置") || msg.contains("地址") || msg.contains("登录") || msg.contains("连接")) {
						isConnectionMissing = true
					}
					msg.ifBlank { "尚未连接或配置 MeloKeep 服务器" }
				}

				401 -> {
					isConnectionMissing = true
					"MeloKeep 登录凭证已失效或未认证（HTTP 401），请重新连接服务"
				}

				403 -> "MeloKeep 拒绝访问（HTTP 403）：${meloEx.message.ifBlank { "没有权限执行此操作" }}"

				404 -> meloEx.message.takeIf { it.isNotBlank() && !it.startsWith("请求失败") }
					?: "未找到目标歌单或曲目（HTTP 404），请刷新后重试"

				400, 422 -> meloEx.message.takeIf { it.isNotBlank() }
					?: "请求参数无效（HTTP ${meloEx.statusCode}）"

				in 500..599 -> "MeloKeep 服务端处理异常（HTTP ${meloEx.statusCode}）：${meloEx.message.ifBlank { "请稍后重试" }}"

				else -> meloEx.message.takeIf { it.isNotBlank() }
					?: "MeloKeep 请求失败（HTTP ${meloEx.statusCode}）"
			}
		}

		subsonicEx != null -> {
			val codeStr = subsonicEx.code.toString()
			val isAuth = subsonicEx.code == SubsonicErrorCode.WRONG_USERNAME_OR_PASSWORD ||
				codeStr.contains("AUTH", ignoreCase = true) ||
				codeStr.contains("PASSWORD", ignoreCase = true) ||
				codeStr.contains("TOKEN", ignoreCase = true)
			if (isAuth) {
				isConnectionMissing = true
				"Navidrome 登录凭证无效或已过期（$codeStr），请重新连接服务"
			} else {
				val rawMsg = subsonicEx.message?.trim().orEmpty()
				if (rawMsg.isNotBlank()) {
					"Navidrome 返回错误（$codeStr）：$rawMsg"
				} else {
					"Navidrome 返回错误（$codeStr）"
				}
			}
		}

		responseEx != null -> {
			val status = responseEx.response.status.value
			when (status) {
				401 -> {
					isConnectionMissing = true
					"${backend.serviceLabel}认证已失效（HTTP 401），请重新连接服务"
				}

				403 -> "${backend.serviceLabel}拒绝访问（HTTP 403）"
				404 -> "${backend.serviceLabel}目标接口或资源不存在（HTTP 404）"
				in 500..599 -> "${backend.serviceLabel}服务端异常（HTTP $status），请稍后重试"
				else -> "${backend.serviceLabel}请求失败（HTTP $status）"
			}
		}

		chain.any {
			it is HttpRequestTimeoutException ||
				it is SocketTimeoutException ||
				it is TimeoutException
		} -> "连接${backend.serviceLabel}超时，请检查网络或服务器地址"

		chain.any { it is UnknownHostException } ->
			"无法解析${backend.serviceLabel}域名，请检查服务器地址或 DNS 设置"

		chain.any { it is ConnectException } ->
			"无法连接到${backend.serviceLabel}，请确认服务已启动且端口可达"

		chain.any { it is SSLException } ->
			"与${backend.serviceLabel}建立 HTTPS 安全连接失败，请检查证书或协议配置"

		else -> {
			val msg = throwable.message?.trim().orEmpty()
			when {
				msg.contains("未登录") || msg.contains("unauthenticated", ignoreCase = true) -> {
					isConnectionMissing = true
					msg
				}

				msg.isNotBlank() -> "${actionLabel}失败：$msg"
				else -> "${actionLabel}失败，请展开技术详情排查"
			}
		}
	}

	val technicalDetail = buildString {
		appendLine("来源: ${backend.serviceLabel}")
		appendLine("操作: $actionLabel")
		if (!diagnosticContext.isNullOrBlank()) {
			appendLine("上下文: $diagnosticContext")
		}
		if (meloEx != null) {
			appendLine("MeloApiException: statusCode=${meloEx.statusCode}")
			if (meloEx.detail.isNotBlank() && meloEx.detail != meloEx.message) {
				appendLine("detail: ${meloEx.detail}")
			}
		}
		if (subsonicEx != null) {
			appendLine("SubsonicException: code=${subsonicEx.code}")
		}
		if (responseEx != null) {
			appendLine("HTTP 状态: ${responseEx.response.status.value} ${responseEx.response.status.description}")
		}
		appendLine("异常链:")
		chain.forEachIndexed { idx, ex ->
			appendLine("  [$idx] ${ex::class.java.name}: ${ex.message ?: "(无消息)"}")
		}
		appendLine("堆栈摘要:")
		append(throwable.stackTraceToString().lineSequence().take(12).joinToString("\n"))
	}.trim()

	return PlaylistOperationError(
		sourceLabel = backend.serviceLabel,
		actionLabel = actionLabel,
		summary = summary,
		technicalDetail = technicalDetail,
		isConnectionMissing = isConnectionMissing
	)
}

data class PlaylistUpdateUiState(
	val backend: PlaylistTargetBackend,
	val supportedSongCount: Int,
	val unsupportedOnly: Boolean,
	val playlists: List<PlaylistPickerItem> = emptyList(),
	val selectedPlaylistIds: Set<String> = emptySet(),
	val loading: Boolean = false,
	val submitting: Boolean = false,
	val creatingPlaylist: Boolean = false,
	val error: PlaylistOperationError? = null
)

class PlaylistUpdateDialogViewModel(
	private val songIds: List<String>,
	private val playlistToExclude: String?,
	private val sessionManager: SessionManager,
	private val snackBarManager: SnackBarManager,
	private val meloApiClient: MeloKeepApiClient,
	private val meloSettingsStore: MeloSettingsStore,
	private val meloLocalController: MeloLocalLibraryController,
	private val playlistDao: PlaylistDao
) : ViewModel() {

	private val resolvedBackend = resolvePlaylistTargetBackend(songIds)
	private val targetSongIds = mapSongIdsForBackend(songIds, resolvedBackend)
	private val unsupportedOnly = targetSongIds.isEmpty()

	private val _uiState = MutableStateFlow(
		PlaylistUpdateUiState(
			backend = resolvedBackend,
			supportedSongCount = targetSongIds.size,
			unsupportedOnly = unsupportedOnly
		)
	)
	val uiState: StateFlow<PlaylistUpdateUiState> = _uiState.asStateFlow()

	private val _events = Channel<Event>(Channel.BUFFERED)
	val events = _events.receiveAsFlow()

	private var refreshJob: Job? = null
	private var lastRetryAction: RetryAction = RetryAction.Refresh

	init {
		refreshResults()
	}

	fun refreshIfIdle() {
		if (refreshJob?.isActive == true || _uiState.value.submitting) return
		_uiState.update { it.copy(selectedPlaylistIds = emptySet(), error = null) }
		refreshResults()
	}

	fun refreshResults() {
		if (_uiState.value.submitting) return
		lastRetryAction = RetryAction.Refresh
		if (unsupportedOnly) {
			refreshJob?.cancel()
			_uiState.update {
				it.copy(
					loading = false,
					playlists = emptyList(),
					selectedPlaylistIds = emptySet(),
					error = buildUnsupportedSongsError("添加到歌单", songIds)
				)
			}
			return
		}

		refreshJob?.cancel()
		refreshJob = viewModelScope.launch {
			when (resolvedBackend) {
				PlaylistTargetBackend.MELOKEEP -> loadMeloKeepPlaylists()
				PlaylistTargetBackend.NAVIDROME -> loadNavidromePlaylists()
			}
		}
	}

	private suspend fun loadMeloKeepPlaylists() {
		val cfg = meloSettingsStore.currentConfig()
		if (!cfg.isAuthenticated) {
			_uiState.update {
				it.copy(
					loading = false,
					playlists = emptyList(),
					selectedPlaylistIds = emptySet(),
					error = buildConnectionMissingError(
						backend = PlaylistTargetBackend.MELOKEEP,
						actionLabel = "加载歌单列表",
						diagnosticContext = "enabled=${cfg.enabled}, baseUrl=${cfg.baseUrl.ifBlank { "(空)" }}, authEnabled=${cfg.authEnabled}"
					)
				)
			}
			return
		}

		_uiState.update { it.copy(loading = true, error = null) }
		try {
			val playlists = meloApiClient.getLocalPlaylists()
				.filter { it.id != playlistToExclude }
				.map { summary ->
					PlaylistPickerItem(
						id = summary.id,
						name = summary.name.ifBlank { "未命名歌单" },
						trackCount = summary.trackCount,
						coverArtId = summary.cover?.takeIf { c -> c.isNotBlank() }
					)
				}
			val validIds = playlists.mapTo(HashSet()) { it.id }
			_uiState.update { current ->
				current.copy(
					playlists = playlists,
					selectedPlaylistIds = current.selectedPlaylistIds.intersect(validIds),
					loading = false,
					error = null
				)
			}
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			_uiState.update {
				it.copy(
					loading = false,
					error = toPlaylistOperationError(
						backend = PlaylistTargetBackend.MELOKEEP,
						actionLabel = "加载歌单列表",
						throwable = e,
						diagnosticContext = "baseUrl=${cfg.baseUrl}, trackCount=${targetSongIds.size}"
					)
				)
			}
		}
	}

	private suspend fun loadNavidromePlaylists() {
		if (sessionManager.sessionState.value is SessionState.Unauthenticated) {
			_uiState.update {
				it.copy(
					loading = false,
					playlists = emptyList(),
					selectedPlaylistIds = emptySet(),
					error = buildConnectionMissingError(
						backend = PlaylistTargetBackend.NAVIDROME,
						actionLabel = "加载歌单列表",
						diagnosticContext = "instanceUrl=${sessionManager.instanceUrl.ifBlank { "(空)" }}"
					)
				)
			}
			return
		}

		_uiState.update { it.copy(loading = true, error = null) }
		try {
			val playlists = sessionManager.api.getPlaylists()
				.filter { it.id != playlistToExclude }
				.map { playlist ->
					PlaylistPickerItem(
						id = playlist.id,
						name = playlist.name.ifBlank { "未命名歌单" },
						trackCount = playlist.songCount,
						coverArtId = playlist.coverArtId?.takeIf { c -> c.isNotBlank() }
					)
				}
			val validIds = playlists.mapTo(HashSet()) { it.id }
			_uiState.update { current ->
				current.copy(
					playlists = playlists,
					selectedPlaylistIds = current.selectedPlaylistIds.intersect(validIds),
					loading = false,
					error = null
				)
			}
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			_uiState.update {
				it.copy(
					loading = false,
					error = toPlaylistOperationError(
						backend = PlaylistTargetBackend.NAVIDROME,
						actionLabel = "加载歌单列表",
						throwable = e,
						diagnosticContext = "instanceUrl=${sessionManager.instanceUrl}, trackCount=${targetSongIds.size}"
					)
				)
			}
		}
	}

	fun togglePlaylistSelection(playlistId: String) {
		if (_uiState.value.submitting) return
		_uiState.update { current ->
			val next = if (playlistId in current.selectedPlaylistIds) {
				current.selectedPlaylistIds - playlistId
			} else {
				current.selectedPlaylistIds + playlistId
			}
			current.copy(selectedPlaylistIds = next)
		}
	}

	fun confirm() {
		val snapshot = _uiState.value
		if (snapshot.submitting || snapshot.unsupportedOnly) return
		val selectedIds = snapshot.selectedPlaylistIds
		if (selectedIds.isEmpty()) return

		lastRetryAction = RetryAction.Confirm
		val selectedItems = snapshot.playlists.filter { it.id in selectedIds }

		if (!ensureBackendConnected("加入歌单")) return

		viewModelScope.launch {
			_uiState.update { it.copy(submitting = true, creatingPlaylist = false, error = null) }
			val succeededIds = mutableSetOf<String>()
			try {
				when (resolvedBackend) {
					PlaylistTargetBackend.MELOKEEP -> {
						selectedIds.forEach { playlistId ->
							meloApiClient.addTracksToLocalPlaylist(playlistId, targetSongIds)
							succeededIds += playlistId
						}
						meloLocalController.refreshPlaylists()
					}

					PlaylistTargetBackend.NAVIDROME -> {
						selectedIds.forEach { playlistId ->
							sessionManager.api.updatePlaylist(
								playlistId,
								songIdsToAdd = targetSongIds
							)
							succeededIds += playlistId
						}
					}
				}

				if (selectedItems.size == 1) {
					snackBarManager.notify(
						R.string.notice_added_to_playlist,
						selectedItems.first().name
					)
				} else {
					snackBarManager.notify(R.string.notice_added_to_multiple_playlists)
				}
				_uiState.update { it.copy(submitting = false, creatingPlaylist = false) }
				_events.send(Event.Dismiss)
			} catch (e: CancellationException) {
				_uiState.update { it.copy(submitting = false, creatingPlaylist = false) }
				throw e
			} catch (e: Throwable) {
				_uiState.update { current ->
					current.copy(
						selectedPlaylistIds = current.selectedPlaylistIds - succeededIds,
						submitting = false,
						creatingPlaylist = false,
						error = toPlaylistOperationError(
							backend = resolvedBackend,
							actionLabel = "加入歌单",
							throwable = e,
							diagnosticContext = "playlistIds=${(selectedIds - succeededIds).joinToString()}, trackIds=${targetSongIds.joinToString()}"
						)
					)
				}
			}
		}
	}

	fun createAndAdd(name: String) {
		val trimmedName = name.trim()
		val snapshot = _uiState.value
		if (trimmedName.isEmpty() || snapshot.submitting || snapshot.unsupportedOnly) return

		lastRetryAction = RetryAction.Create(trimmedName)
		if (!ensureBackendConnected("创建歌单并加入")) return

		viewModelScope.launch {
			_uiState.update { it.copy(submitting = true, creatingPlaylist = true, error = null) }
			try {
				val createdName = when (resolvedBackend) {
					PlaylistTargetBackend.MELOKEEP -> {
						val created = meloApiClient.createLocalPlaylist(trimmedName, targetSongIds)
						meloLocalController.refreshPlaylists()
						created.name.ifBlank { trimmedName }
					}

					PlaylistTargetBackend.NAVIDROME -> {
						val created = sessionManager.api.createPlaylist(
							name = trimmedName,
							songIds = targetSongIds
						)
						playlistDao.insertPlaylist(created.toEntity())
						created.name.ifBlank { trimmedName }
					}
				}

				snackBarManager.notify(R.string.notice_created_playlist, createdName)
				_uiState.update { it.copy(submitting = false, creatingPlaylist = false) }
				_events.send(Event.Dismiss)
			} catch (e: CancellationException) {
				_uiState.update { it.copy(submitting = false, creatingPlaylist = false) }
				throw e
			} catch (e: Throwable) {
				_uiState.update {
					it.copy(
						submitting = false,
						creatingPlaylist = false,
						error = toPlaylistOperationError(
							backend = resolvedBackend,
							actionLabel = "创建歌单并加入",
							throwable = e,
							diagnosticContext = "name=$trimmedName, trackIds=${targetSongIds.joinToString()}"
						)
					)
				}
			}
		}
	}

	fun retryLastAction() {
		when (val action = lastRetryAction) {
			RetryAction.Refresh -> refreshResults()
			RetryAction.Confirm -> confirm()
			is RetryAction.Create -> createAndAdd(action.name)
		}
	}

	private fun ensureBackendConnected(actionLabel: String): Boolean {
		return when (resolvedBackend) {
			PlaylistTargetBackend.MELOKEEP -> {
				val cfg = meloSettingsStore.currentConfig()
				if (!cfg.isAuthenticated) {
					_uiState.update {
						it.copy(
							error = buildConnectionMissingError(
								backend = PlaylistTargetBackend.MELOKEEP,
								actionLabel = actionLabel,
								diagnosticContext = "enabled=${cfg.enabled}, baseUrl=${cfg.baseUrl.ifBlank { "(空)" }}"
							)
						)
					}
					false
				} else {
					true
				}
			}

			PlaylistTargetBackend.NAVIDROME -> {
				if (sessionManager.sessionState.value is SessionState.Unauthenticated) {
					_uiState.update {
						it.copy(
							error = buildConnectionMissingError(
								backend = PlaylistTargetBackend.NAVIDROME,
								actionLabel = actionLabel,
								diagnosticContext = "instanceUrl=${sessionManager.instanceUrl.ifBlank { "(空)" }}"
							)
						)
					}
					false
				} else {
					true
				}
			}
		}
	}

	private sealed interface RetryAction {
		data object Refresh : RetryAction
		data object Confirm : RetryAction
		data class Create(val name: String) : RetryAction
	}

	enum class Event {
		Dismiss
	}
}
