/*
 * Navic / MeloKeep, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.component.sheet

import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.R
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.model.settings.UpdateCheckFrequency
import paige.navic.ui.component.common.Markdown
import paige.navic.ui.component.dialog.LinkConfirmationDialog
import paige.navic.ui.theme.defaultFont
import paige.navic.util.Logger

private const val MELOKEEP_LATEST_RELEASE_URL =
	"https://api.github.com/repos/Agonie0v0/melokeep/releases/latest"
private const val ONE_DAY_MS = 24L * 60L * 60L * 1000L
private const val ONE_WEEK_MS = 7L * ONE_DAY_MS

@Serializable
data class GitHubRelease(
	@SerialName("tag_name") val tag: String,
	@SerialName("html_url") val url: String,
	@SerialName("body") val body: String
)

sealed interface UpdateCheckFeedback {
	data object Idle : UpdateCheckFeedback
	data object Checking : UpdateCheckFeedback
	data object UpToDate : UpdateCheckFeedback
	data class UpdateAvailable(val tag: String) : UpdateCheckFeedback
	data class Error(val message: String) : UpdateCheckFeedback
}

private data class ParsedSemVer(
	val core: List<Int>,
	val preRelease: List<String>
)

private fun parseSemVer(raw: String): ParsedSemVer? {
	val trimmed = raw.trim().removePrefix("v").removePrefix("V").substringBefore('+')
	if (trimmed.isEmpty()) return null
	val dashIdx = trimmed.indexOf('-')
	val coreStr = if (dashIdx >= 0) trimmed.substring(0, dashIdx) else trimmed
	val preStr = if (dashIdx >= 0) trimmed.substring(dashIdx + 1) else ""
	val coreSegments = coreStr.split('.').map { seg ->
		seg.toIntOrNull() ?: seg.takeWhile { it.isDigit() }.toIntOrNull() ?: return null
	}
	if (coreSegments.isEmpty()) return null
	val preSegments = if (preStr.isBlank()) {
		emptyList()
	} else {
		preStr.split('.').filter { it.isNotBlank() }
	}
	return ParsedSemVer(core = coreSegments, preRelease = preSegments)
}

private fun tokenizeAlphaNum(value: String): List<String> {
	val result = mutableListOf<String>()
	var idx = 0
	while (idx < value.length) {
		val isDigit = value[idx].isDigit()
		val start = idx
		while (idx < value.length && value[idx].isDigit() == isDigit) {
			idx++
		}
		result.add(value.substring(start, idx))
	}
	return result
}

private fun comparePreReleaseIdentifiers(a: String, b: String): Int {
	val aInt = a.toIntOrNull()
	val bInt = b.toIntOrNull()
	if (aInt != null && bInt != null) return aInt.compareTo(bInt)
	if (aInt != null) return -1 // numeric < alphanumeric per SemVer 2.0.0
	if (bInt != null) return 1

	val tokensA = tokenizeAlphaNum(a)
	val tokensB = tokenizeAlphaNum(b)
	val count = maxOf(tokensA.size, tokensB.size)
	for (i in 0 until count) {
		val tA = tokensA.getOrNull(i) ?: return -1
		val tB = tokensB.getOrNull(i) ?: return 1
		val nA = tA.toIntOrNull()
		val nB = tB.toIntOrNull()
		val cmp = if (nA != null && nB != null) {
			nA.compareTo(nB)
		} else {
			tA.compareTo(tB, ignoreCase = true)
		}
		if (cmp != 0) return cmp
	}
	return 0
}

internal fun compareSemVer(remoteTag: String, currentVersion: String): Int {
	val remote = parseSemVer(remoteTag) ?: return 0
	val local = parseSemVer(currentVersion) ?: return 0
	val maxLen = maxOf(remote.core.size, local.core.size)
	for (i in 0 until maxLen) {
		val r = remote.core.getOrElse(i) { 0 }
		val l = local.core.getOrElse(i) { 0 }
		if (r != l) return r.compareTo(l)
	}
	// 相同主次修订号时，正式版 > 预发布版
	if (remote.preRelease.isEmpty() && local.preRelease.isEmpty()) return 0
	if (remote.preRelease.isEmpty()) return 1
	if (local.preRelease.isEmpty()) return -1

	val maxPre = maxOf(remote.preRelease.size, local.preRelease.size)
	for (i in 0 until maxPre) {
		val rPre = remote.preRelease.getOrNull(i) ?: return -1
		val lPre = local.preRelease.getOrNull(i) ?: return 1
		val cmp = comparePreReleaseIdentifiers(rPre, lPre)
		if (cmp != 0) return cmp
	}
	return 0
}

class ChangelogViewModel(
	context: Context,
	private val preferenceManager: PreferenceManager
) : ViewModel() {
	private val appVersionName: String = runCatching {
		context.packageManager
			.getPackageInfo(context.packageName, 0)
			.versionName
			?.takeIf { it.isNotBlank() }
	}.getOrNull() ?: "0.0.0"

	val release: StateFlow<GitHubRelease?>
		field = MutableStateFlow(null)

	val checkFeedback: StateFlow<UpdateCheckFeedback>
		field = MutableStateFlow<UpdateCheckFeedback>(UpdateCheckFeedback.Idle)

	private val updateClient = HttpClient {
		install(ContentNegotiation) {
			json(Json { ignoreUnknownKeys = true })
		}
	}

	init {
		if (shouldAutoCheck(System.currentTimeMillis())) {
			checkForUpdates(currentVersion = appVersionName, manual = false)
		}
	}

	private fun shouldAutoCheck(nowMs: Long): Boolean {
		if (!preferenceManager.checkForUpdates) return false
		val elapsed = nowMs - preferenceManager.lastUpdateCheckTimeMs
		return when (preferenceManager.updateCheckFrequency) {
			UpdateCheckFrequency.OnEveryLaunch -> true
			UpdateCheckFrequency.Daily -> preferenceManager.lastUpdateCheckTimeMs <= 0L || elapsed >= ONE_DAY_MS
			UpdateCheckFrequency.Weekly -> preferenceManager.lastUpdateCheckTimeMs <= 0L || elapsed >= ONE_WEEK_MS
			UpdateCheckFrequency.ManualOnly -> false
		}
	}

	fun checkForUpdates(
		currentVersion: String = appVersionName,
		manual: Boolean = false
	) {
		viewModelScope.launch {
			if (manual) {
				checkFeedback.value = UpdateCheckFeedback.Checking
			}
			try {
				val fetched: GitHubRelease = updateClient
					.get(MELOKEEP_LATEST_RELEASE_URL) {
						header("Accept", "application/vnd.github+json")
					}
					.body()
				preferenceManager.lastUpdateCheckTimeMs = System.currentTimeMillis()
				val isNewer = compareSemVer(fetched.tag, currentVersion) > 0
				if (isNewer) {
					if (!manual && fetched.tag == preferenceManager.ignoredUpdateTag) {
						release.value = null
						return@launch
					}
					release.value = fetched
					checkFeedback.value = UpdateCheckFeedback.UpdateAvailable(fetched.tag)
				} else {
					release.value = null
					if (manual) {
						checkFeedback.value = UpdateCheckFeedback.UpToDate
					}
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Logger.e("ChangelogViewModel", "couldn't check for updates", e)
				if (manual) {
					checkFeedback.value = UpdateCheckFeedback.Error(
						e.message?.takeIf { it.isNotBlank() } ?: "检查更新失败，请检查网络连接"
					)
				}
			}
		}
	}

	fun skipVersion(tag: String) {
		preferenceManager.ignoredUpdateTag = tag
		release.value = null
	}

	fun clearRelease() {
		release.value = null
	}

	override fun onCleared() {
		updateClient.close()
		super.onCleared()
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogSheet() {
	val activityOwner = LocalActivity.current as? ViewModelStoreOwner
	val viewModel = if (activityOwner != null) {
		koinViewModel<ChangelogViewModel>(viewModelStoreOwner = activityOwner)
	} else {
		koinViewModel<ChangelogViewModel>()
	}
	val release by viewModel.release.collectAsStateWithLifecycle()
	var linkToOpen by rememberSaveable { mutableStateOf<String?>(null) }

	release?.let { activeRelease ->
		ModalBottomSheet(
			onDismissRequest = { viewModel.clearRelease() },
			sheetState = rememberBottomSheetState(
				initialValue = SheetValue.Hidden,
				enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
			)
		) {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.verticalScroll(rememberScrollState())
					.navigationBarsPadding()
					.padding(horizontal = 16.dp, vertical = 8.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp)
			) {
				Column(
					modifier = Modifier.fillMaxWidth(),
					horizontalAlignment = Alignment.CenterHorizontally
				) {
					Text(
						text = stringResource(R.string.title_update),
						style = MaterialTheme.typography.titleLarge,
						fontFamily = defaultFont(round = 100f)
					)
					Text(
						stringResource(R.string.info_update, activeRelease.tag),
						style = MaterialTheme.typography.bodyMedium
					)
				}

				Markdown(
					text = activeRelease.body,
					modifier = Modifier
						.heightIn(max = 400.dp)
						.fillMaxWidth()
						.clip(MaterialTheme.shapes.large)
						.background(MaterialTheme.colorScheme.surfaceContainerHigh)
						.verticalScroll(rememberScrollState())
						.padding(10.dp)
				)

				Spacer(Modifier.height(4.dp))

				Button(
					onClick = { linkToOpen = activeRelease.url },
					modifier = Modifier.fillMaxWidth(),
					shape = CircleShape
				) {
					Text(
						text = "前往更新",
						fontFamily = defaultFont(100)
					)
				}

				OutlinedButton(
					onClick = { viewModel.skipVersion(activeRelease.tag) },
					modifier = Modifier.fillMaxWidth(),
					shape = CircleShape
				) {
					Text(
						text = "跳过此版本",
						fontFamily = defaultFont(100)
					)
				}

				TextButton(
					onClick = { viewModel.clearRelease() },
					modifier = Modifier.fillMaxWidth(),
					shape = CircleShape
				) {
					Text(
						text = "稍后提醒",
						fontFamily = defaultFont(100)
					)
				}
			}
		}
	}

	LinkConfirmationDialog(
		linkToOpen = linkToOpen,
		onDismissRequest = { linkToOpen = null }
	)
}
