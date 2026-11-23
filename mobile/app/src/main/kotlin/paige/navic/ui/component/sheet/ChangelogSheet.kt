/*
 * Navic / MeloKeep, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige & MeloKeep Swarm
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.component.sheet

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.agonie0v0.melokeep.shared.MeloAppUpdateChecker
import io.github.agonie0v0.melokeep.shared.MeloGitHubAsset
import io.github.agonie0v0.melokeep.shared.MeloGitHubRelease
import io.github.agonie0v0.melokeep.shared.MeloSemVer
import io.github.agonie0v0.melokeep.shared.findAndroidApkAsset
import io.github.agonie0v0.melokeep.shared.hasAndroidUpdateFor
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.R
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.model.settings.UpdateCheckFrequency
import paige.navic.generated.BuildInfo
import paige.navic.ui.component.common.Markdown
import paige.navic.ui.component.dialog.LinkConfirmationDialog
import paige.navic.ui.theme.defaultFont
import paige.navic.ui.theme.tabularNums
import paige.navic.util.Logger
import paige.navic.util.toFileSize

private const val ONE_DAY_MS = 24L * 60L * 60L * 1000L
private const val ONE_WEEK_MS = 7L * ONE_DAY_MS
private const val FALLBACK_RELEASES_HTML_URL = "https://github.com/Agonie0v0/melokeep/releases"

sealed interface UpdateCheckFeedback {
	data object Idle : UpdateCheckFeedback
	data object Checking : UpdateCheckFeedback
	data object UpToDate : UpdateCheckFeedback
	data class UpdateAvailable(val tag: String) : UpdateCheckFeedback
	data class Error(val message: String) : UpdateCheckFeedback
}

sealed interface ApkUpdateInstallState {
	data object Idle : ApkUpdateInstallState
	data class Downloading(
		val downloadedBytes: Long,
		val totalBytes: Long,
		val progress: Float
	) : ApkUpdateInstallState
	data class ReadyToInstall(
		val apkFile: File,
		val tag: String
	) : ApkUpdateInstallState
	data class NeedInstallPermission(
		val apkFile: File,
		val tag: String
	) : ApkUpdateInstallState
	data class Error(val message: String) : ApkUpdateInstallState
}

internal fun compareSemVer(remoteTag: String, currentVersion: String): Int =
	MeloSemVer.compare(remoteTag, currentVersion)

internal fun formatReleaseTag(tag: String): String? {
	val cleaned = tag.trim().removePrefix("v").removePrefix("V")
	return if (cleaned.isNotEmpty()) "v$cleaned" else null
}

class ChangelogViewModel(
	private val appContext: Context,
	private val preferenceManager: PreferenceManager
) : ViewModel() {
	private val appVersionName: String = runCatching {
		appContext.packageManager
			.getPackageInfo(appContext.packageName, 0)
			.versionName
			?.trim()
			?.removePrefix("v")
			?.removePrefix("V")
			?.takeIf { it.isNotEmpty() }
	}.getOrNull() ?: "0.0.0"

	internal val currentVersionName: String
		get() = appVersionName

	val release: StateFlow<MeloGitHubRelease?>
		field = MutableStateFlow<MeloGitHubRelease?>(null)

	val latestRelease: StateFlow<MeloGitHubRelease?>
		field = MutableStateFlow<MeloGitHubRelease?>(null)

	val checkFeedback: StateFlow<UpdateCheckFeedback>
		field = MutableStateFlow<UpdateCheckFeedback>(UpdateCheckFeedback.Idle)

	val installState: StateFlow<ApkUpdateInstallState>
		field = MutableStateFlow<ApkUpdateInstallState>(ApkUpdateInstallState.Idle)

	private var checkJob: Job? = null
	private var downloadJob: Job? = null

	private val updateClient = HttpClient {
		install(ContentNegotiation) {
			json(Json { ignoreUnknownKeys = true })
		}
	}

	private val apkHttpClient = OkHttpClient.Builder()
		.followRedirects(true)
		.followSslRedirects(true)
		.build()

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
		manual: Boolean = false,
		showSheet: Boolean = true
	) {
		if (!manual && checkFeedback.value is UpdateCheckFeedback.Checking) {
			return
		}
		checkJob?.cancel()
		checkJob = viewModelScope.launch {
			if (manual) {
				checkFeedback.value = UpdateCheckFeedback.Checking
			}
			try {
				val fetched = MeloAppUpdateChecker.fetchLatestAndroidReleaseOrNull(updateClient)
				preferenceManager.lastUpdateCheckTimeMs = System.currentTimeMillis()
				latestRelease.value = fetched

				val isNewer = fetched != null && fetched.hasAndroidUpdateFor(currentVersion)
				if (isNewer) {
					updateInstallStateForRelease(fetched)
					checkFeedback.value = UpdateCheckFeedback.UpdateAvailable(fetched.tagName)
					if (showSheet && (manual || fetched.tagName != preferenceManager.ignoredUpdateTag)) {
						release.value = fetched
					}
				} else {
					cleanupInstalledOrOlderCachedApks(currentVersion)
					if (installState.value !is ApkUpdateInstallState.Downloading) {
						installState.value = ApkUpdateInstallState.Idle
					}
					release.value = null
					if (manual) {
						checkFeedback.value = UpdateCheckFeedback.UpToDate
					} else if (
						checkFeedback.value is UpdateCheckFeedback.Checking ||
						checkFeedback.value is UpdateCheckFeedback.UpdateAvailable ||
						checkFeedback.value is UpdateCheckFeedback.Error
					) {
						checkFeedback.value = UpdateCheckFeedback.Idle
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

	private fun updateInstallStateForRelease(fetched: MeloGitHubRelease) {
		val currentInstall = installState.value
		if (currentInstall is ApkUpdateInstallState.Downloading) {
			return
		}
		val cachedFile = resolveCachedApkFile(fetched)
		installState.value = if (cachedFile != null) {
			if (
				currentInstall is ApkUpdateInstallState.NeedInstallPermission &&
				currentInstall.tag == fetched.tagName &&
				currentInstall.apkFile.absolutePath == cachedFile.absolutePath &&
				Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
				!appContext.packageManager.canRequestPackageInstalls()
			) {
				currentInstall
			} else {
				ApkUpdateInstallState.ReadyToInstall(
					apkFile = cachedFile,
					tag = fetched.tagName
				)
			}
		} else {
			ApkUpdateInstallState.Idle
		}
	}

	private suspend fun cleanupInstalledOrOlderCachedApks(currentVersion: String) {
		withContext(Dispatchers.IO) {
			runCatching {
				val updatesDir = File(appContext.cacheDir, "apk_updates")
				if (!updatesDir.isDirectory) return@withContext
				updatesDir.listFiles()?.forEach { file ->
					if (!file.isFile) return@forEach
					val name = file.name
					val tag = when {
						name.startsWith("MeloKeep-") && name.endsWith(".apk.part") ->
							name.removePrefix("MeloKeep-").removeSuffix(".apk.part")
						name.startsWith("MeloKeep-") && name.endsWith(".apk") ->
							name.removePrefix("MeloKeep-").removeSuffix(".apk")
						else -> null
					} ?: return@forEach
					if (tag.isNotBlank() && MeloSemVer.compare(tag, currentVersion) <= 0) {
						file.delete()
					}
				}
			}
		}
	}

	private fun sanitizeTag(tag: String): String =
		tag.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "latest" }

	private fun resolveCachedApkFile(targetRelease: MeloGitHubRelease): File? {
		val asset = targetRelease.findAndroidApkAsset() ?: return null
		val dir = File(appContext.cacheDir, "apk_updates")
		val file = File(dir, "MeloKeep-${sanitizeTag(targetRelease.tagName)}.apk")
		if (!file.exists() || file.length() <= 0L) return null
		if (asset.size > 0L && file.length() != asset.size) return null
		return file
	}

	fun startDownloadAndInstall(targetRelease: MeloGitHubRelease) {
		if (!targetRelease.hasAndroidUpdateFor(appVersionName)) {
			installState.value = ApkUpdateInstallState.Idle
			return
		}
		if (downloadJob?.isActive == true) {
			return
		}
		val asset = targetRelease.findAndroidApkAsset()
		if (asset == null || asset.downloadUrl.isBlank()) {
			installState.value = ApkUpdateInstallState.Error("该版本暂无 APK 安装包，请前往 GitHub 页面下载")
			return
		}
		val cached = resolveCachedApkFile(targetRelease)
		if (cached != null) {
			proceedWithInstall(cached, targetRelease.tagName)
			return
		}

		downloadJob = viewModelScope.launch {
			val safeTag = sanitizeTag(targetRelease.tagName)
			val updatesDir = File(appContext.cacheDir, "apk_updates")
			val partFile = File(updatesDir, "MeloKeep-$safeTag.apk.part")
			val finalFile = File(updatesDir, "MeloKeep-$safeTag.apk")

			installState.value = ApkUpdateInstallState.Downloading(
				downloadedBytes = 0L,
				totalBytes = asset.size,
				progress = 0f
			)

			try {
				withContext(Dispatchers.IO) {
					if (!updatesDir.exists()) {
						updatesDir.mkdirs()
					}
					if (partFile.exists()) {
						partFile.delete()
					}
					val request = Request.Builder()
						.url(asset.downloadUrl)
						.header("Accept", "application/octet-stream")
						.header("User-Agent", "MeloKeep-Android")
						.build()

					apkHttpClient.newCall(request).execute().use { response ->
						if (!response.isSuccessful) {
							throw IllegalStateException("下载失败 (HTTP ${response.code})")
						}
						val body = response.body
						val contentLength = body.contentLength().takeIf { it > 0L } ?: asset.size
						var downloaded = 0L
						val buffer = ByteArray(32 * 1024)

						body.byteStream().use { input ->
							FileOutputStream(partFile).use { output ->
								while (true) {
									ensureActive()
									val read = input.read(buffer)
									if (read == -1) break
									output.write(buffer, 0, read)
									downloaded += read
									val ratio = if (contentLength > 0L) {
										(downloaded.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f)
									} else {
										0f
									}
									installState.value = ApkUpdateInstallState.Downloading(
										downloadedBytes = downloaded,
										totalBytes = contentLength,
										progress = ratio
									)
								}
								output.flush()
							}
						}
					}

					if (finalFile.exists()) {
						finalFile.delete()
					}
					if (!partFile.renameTo(finalFile)) {
						partFile.copyTo(finalFile, overwrite = true)
						partFile.delete()
					}
				}
				proceedWithInstall(finalFile, targetRelease.tagName)
			} catch (e: CancellationException) {
				withContext(NonCancellable + Dispatchers.IO) {
					runCatching { if (partFile.exists()) partFile.delete() }
				}
				if (installState.value is ApkUpdateInstallState.Downloading) {
					installState.value = ApkUpdateInstallState.Idle
				}
				throw e
			} catch (e: Exception) {
				Logger.e("ChangelogViewModel", "Failed to download APK update", e)
				withContext(NonCancellable + Dispatchers.IO) {
					runCatching { if (partFile.exists()) partFile.delete() }
				}
				installState.value = ApkUpdateInstallState.Error(
					e.message?.takeIf { it.isNotBlank() } ?: "下载更新包失败，请重试或前往 GitHub 页面下载"
				)
			}
		}
	}

	fun cancelDownload() {
		val jobToCancel = downloadJob
		downloadJob = null
		jobToCancel?.cancel()
		if (installState.value is ApkUpdateInstallState.Downloading) {
			installState.value = ApkUpdateInstallState.Idle
		}
	}

	fun openInstallPermissionSettings() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			runCatching {
				val intent = Intent(
					Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
					Uri.parse("package:${appContext.packageName}")
				).apply {
					addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				}
				appContext.startActivity(intent)
			}.onFailure { e ->
				Logger.e("ChangelogViewModel", "Unable to open unknown app sources settings", e)
			}
		}
	}

	fun proceedWithInstall(apkFile: File, tag: String) {
		if (!apkFile.exists() || apkFile.length() <= 0L) {
			installState.value = ApkUpdateInstallState.Error("安装包文件不存在或已损坏，请重新下载")
			return
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
			!appContext.packageManager.canRequestPackageInstalls()
		) {
			installState.value = ApkUpdateInstallState.NeedInstallPermission(apkFile = apkFile, tag = tag)
			openInstallPermissionSettings()
			return
		}

		installState.value = ApkUpdateInstallState.ReadyToInstall(apkFile = apkFile, tag = tag)
		try {
			val apkUri = FileProvider.getUriForFile(
				appContext,
				"${appContext.packageName}.fileprovider",
				apkFile
			)
			val installIntent = Intent(Intent.ACTION_VIEW).apply {
				setDataAndType(apkUri, "application/vnd.android.package-archive")
				addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
			}
			appContext.startActivity(installIntent)
		} catch (e: Exception) {
			Logger.e("ChangelogViewModel", "Failed to launch system package installer", e)
			installState.value = ApkUpdateInstallState.Error(
				"无法拉起系统安装器：${e.message ?: "未知错误"}"
			)
		}
	}

	fun skipVersion(tag: String) {
		cancelDownload()
		preferenceManager.ignoredUpdateTag = tag
		release.value = null
	}

	fun clearRelease() {
		release.value = null
	}

	override fun onCleared() {
		checkJob?.cancel()
		downloadJob?.cancel()
		updateClient.close()
		super.onCleared()
	}
}

@Composable
internal fun MeloReleaseNotesContent(
	body: String,
	modifier: Modifier = Modifier,
	maxHeight: Dp = 240.dp
) {
	Markdown(
		text = body.ifBlank { "包含体验优化与问题修复。" },
		modifier = modifier
			.heightIn(max = maxHeight)
			.fillMaxWidth()
			.clip(RoundedCornerShape(12.dp))
			.background(MaterialTheme.colorScheme.surfaceContainerHigh)
			.verticalScroll(rememberScrollState())
			.padding(12.dp)
	)
}

@Composable
internal fun MeloApkUpdateActionSection(
	release: MeloGitHubRelease,
	apkAsset: MeloGitHubAsset?,
	installState: ApkUpdateInstallState,
	isNewerVersion: Boolean,
	allowDirectInstall: Boolean = !BuildInfo.FDROID,
	buttonShape: Shape = CircleShape,
	primaryDownloadLabel: String = "下载并安装",
	onStartDownload: () -> Unit,
	onCancelDownload: () -> Unit,
	onProceedInstall: (File, String) -> Unit,
	onOpenPermissionSettings: () -> Unit,
	onOpenGitHubUrl: (String) -> Unit,
	modifier: Modifier = Modifier
) {
	val releaseHtmlUrl = release.htmlUrl.ifBlank { FALLBACK_RELEASES_HTML_URL }

	Column(
		modifier = modifier.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(8.dp)
	) {
		if (!isNewerVersion) {
			Text(
				text = "当前已是最新 Android 客户端版本，无需更新",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(horizontal = 4.dp)
			)
			TextButton(
				onClick = { onOpenGitHubUrl(releaseHtmlUrl) },
				modifier = Modifier.fillMaxWidth(),
				shape = buttonShape
			) {
				Text(
					text = "前往 GitHub 页面",
					fontFamily = defaultFont(100)
				)
			}
			return@Column
		}

		if (!allowDirectInstall) {
			Button(
				onClick = { onOpenGitHubUrl(releaseHtmlUrl) },
				modifier = Modifier.fillMaxWidth(),
				shape = buttonShape
			) {
				Text(
					text = "前往 GitHub 页面",
					fontFamily = defaultFont(100)
				)
			}
			return@Column
		}

		when (val state = installState) {
			is ApkUpdateInstallState.Downloading -> {
				Surface(
					shape = RoundedCornerShape(14.dp),
					color = MaterialTheme.colorScheme.surfaceContainerHigh,
					modifier = Modifier.fillMaxWidth()
				) {
					Column(
						modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
						verticalArrangement = Arrangement.spacedBy(8.dp)
					) {
						Row(
							modifier = Modifier.fillMaxWidth(),
							horizontalArrangement = Arrangement.SpaceBetween,
							verticalAlignment = Alignment.CenterVertically
						) {
							Text(
								text = "正在下载安装包…",
								style = MaterialTheme.typography.bodyMedium,
								fontWeight = FontWeight.Medium
							)
							Text(
								text = "${(state.progress * 100).toInt()}%",
								style = MaterialTheme.typography.labelMedium.tabularNums(),
								color = MaterialTheme.colorScheme.primary
							)
						}
						if (state.totalBytes > 0L) {
							LinearProgressIndicator(
								progress = { state.progress.coerceIn(0f, 1f) },
								modifier = Modifier
									.fillMaxWidth()
									.height(5.dp)
									.clip(CircleShape)
							)
						} else {
							LinearProgressIndicator(
								modifier = Modifier
									.fillMaxWidth()
									.height(5.dp)
									.clip(CircleShape)
							)
						}
						Text(
							text = if (state.totalBytes > 0L) {
								"${state.downloadedBytes.toFileSize()} / ${state.totalBytes.toFileSize()}"
							} else {
								"已下载 ${state.downloadedBytes.toFileSize()}"
							},
							style = MaterialTheme.typography.labelSmall.tabularNums(),
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
					}
				}

				OutlinedButton(
					onClick = onCancelDownload,
					modifier = Modifier.fillMaxWidth(),
					shape = buttonShape
				) {
					Text(
						text = "取消下载",
						fontFamily = defaultFont(100)
					)
				}
			}

			is ApkUpdateInstallState.NeedInstallPermission -> {
				Surface(
					shape = RoundedCornerShape(14.dp),
					color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
					modifier = Modifier.fillMaxWidth()
				) {
					Text(
						text = "更新包已下载完成！首次应用内更新请先开启「允许安装未知应用」权限，授权后返回点击下方按钮即可直接覆盖安装。",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSecondaryContainer,
						modifier = Modifier.padding(12.dp)
					)
				}

				Button(
					onClick = { onProceedInstall(state.apkFile, state.tag) },
					modifier = Modifier.fillMaxWidth(),
					shape = buttonShape
				) {
					Text(
						text = "已完成授权，立即安装",
						fontFamily = defaultFont(100)
					)
				}

				OutlinedButton(
					onClick = onOpenPermissionSettings,
					modifier = Modifier.fillMaxWidth(),
					shape = buttonShape
				) {
					Text(
						text = "前往系统设置授权",
						fontFamily = defaultFont(100)
					)
				}
			}

			is ApkUpdateInstallState.ReadyToInstall -> {
				Button(
					onClick = { onProceedInstall(state.apkFile, state.tag) },
					modifier = Modifier.fillMaxWidth(),
					shape = buttonShape
				) {
					Text(
						text = "立即安装 (${state.apkFile.length().toFileSize()})",
						fontFamily = defaultFont(100)
					)
				}

				TextButton(
					onClick = { onOpenGitHubUrl(releaseHtmlUrl) },
					modifier = Modifier.fillMaxWidth(),
					shape = buttonShape
				) {
					Text(
						text = "前往 GitHub 页面",
						fontFamily = defaultFont(100)
					)
				}
			}

			is ApkUpdateInstallState.Idle,
			is ApkUpdateInstallState.Error -> {
				if (state is ApkUpdateInstallState.Error) {
					Text(
						text = state.message,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.error,
						modifier = Modifier.padding(horizontal = 4.dp)
					)
				}

				if (apkAsset != null) {
					val sizeLabel = apkAsset.size.takeIf { it > 0L }?.let { " (${it.toFileSize()})" }.orEmpty()
					Button(
						onClick = onStartDownload,
						modifier = Modifier.fillMaxWidth(),
						shape = buttonShape
					) {
						Text(
							text = if (state is ApkUpdateInstallState.Error) {
								"重试下载并更新$sizeLabel"
							} else {
								"$primaryDownloadLabel$sizeLabel"
							},
							fontFamily = defaultFont(100)
						)
					}

					TextButton(
						onClick = { onOpenGitHubUrl(releaseHtmlUrl) },
						modifier = Modifier.fillMaxWidth(),
						shape = buttonShape
					) {
						Text(
							text = "前往 GitHub 页面",
							fontFamily = defaultFont(100)
						)
					}
				} else {
					Text(
						text = "该版本暂未附带 APK 安装包，请前往 GitHub 页面查看或手动下载。",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.padding(horizontal = 4.dp)
					)
					Button(
						onClick = { onOpenGitHubUrl(releaseHtmlUrl) },
						modifier = Modifier.fillMaxWidth(),
						shape = buttonShape
					) {
						Text(
							text = "前往 GitHub 页面",
							fontFamily = defaultFont(100)
						)
					}
				}
			}
		}
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
	val installState by viewModel.installState.collectAsStateWithLifecycle()
	var linkToOpen by rememberSaveable { mutableStateOf<String?>(null) }

	release?.takeIf { it.hasAndroidUpdateFor(viewModel.currentVersionName) }?.let { activeRelease ->
		val apkAsset = remember(activeRelease) { activeRelease.findAndroidApkAsset() }
		val isDownloading = installState is ApkUpdateInstallState.Downloading

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
					val displayTag = formatReleaseTag(activeRelease.tagName) ?: activeRelease.tagName
					Text(
						text = stringResource(R.string.info_update, displayTag),
						style = MaterialTheme.typography.bodyMedium
					)
				}

				MeloReleaseNotesContent(
					body = activeRelease.body,
					maxHeight = 360.dp
				)

				Spacer(Modifier.height(4.dp))

				MeloApkUpdateActionSection(
					release = activeRelease,
					apkAsset = apkAsset,
					installState = installState,
					isNewerVersion = true,
					allowDirectInstall = !BuildInfo.FDROID,
					buttonShape = CircleShape,
					primaryDownloadLabel = "立即更新",
					onStartDownload = { viewModel.startDownloadAndInstall(activeRelease) },
					onCancelDownload = { viewModel.cancelDownload() },
					onProceedInstall = { file, tag -> viewModel.proceedWithInstall(file, tag) },
					onOpenPermissionSettings = { viewModel.openInstallPermissionSettings() },
					onOpenGitHubUrl = { url -> linkToOpen = url }
				)

				if (!isDownloading) {
					OutlinedButton(
						onClick = { viewModel.skipVersion(activeRelease.tagName) },
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
				} else {
					TextButton(
						onClick = { viewModel.clearRelease() },
						modifier = Modifier.fillMaxWidth(),
						shape = CircleShape
					) {
						Text(
							text = "后台继续下载",
							fontFamily = defaultFont(100)
						)
					}
				}
			}
		}
	}

	LinkConfirmationDialog(
		linkToOpen = linkToOpen,
		onDismissRequest = { linkToOpen = null }
	)
}
