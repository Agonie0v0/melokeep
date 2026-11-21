/*
 * Navic / MeloKeep, a Subsonic music streaming & studio app for Android
 * Copyright (c) 2026 paige & MeloKeep Swarm
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.melokeep

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import io.github.agonie0v0.melokeep.shared.MeloSettingsController
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import paige.navic.di.LocalSnackBarState
import paige.navic.domain.manager.playback.AudioGainManager
import paige.navic.domain.manager.playback.EqualizerManager
import paige.navic.domain.manager.playback.StreamCacheManager
import paige.navic.domain.manager.runtime.PermissionManager
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.manager.runtime.StorageManager
import paige.navic.domain.manager.session.DownloadManager
import paige.navic.domain.manager.session.LoginManager
import paige.navic.domain.manager.session.SessionManager
import paige.navic.domain.manager.session.SessionState
import paige.navic.domain.manager.session.SyncManager
import paige.navic.domain.model.settings.BottomBarCollapseMode
import paige.navic.domain.model.settings.BottomBarVisibilityMode
import paige.navic.domain.model.settings.CoverArtShape
import paige.navic.domain.model.settings.DownloadStorage
import paige.navic.domain.model.settings.EqualizerMode
import paige.navic.domain.model.settings.NowPlayingBackgroundStyle
import paige.navic.domain.model.settings.NowPlayingSliderStyle
import paige.navic.domain.model.settings.OfflineMode
import paige.navic.domain.model.settings.ReplayGainMode
import paige.navic.domain.model.settings.StreamingQuality
import paige.navic.domain.model.settings.ThemeMode
import paige.navic.domain.model.settings.UpdateCheckFrequency
import paige.navic.domain.sync.SyncCoordinator
import paige.navic.generated.BuildInfo
import paige.navic.playback.exoplayer.ExoStateHolder
import paige.navic.ui.component.common.VerticalSlider
import paige.navic.ui.component.sheet.ChangelogViewModel
import paige.navic.ui.component.sheet.UpdateCheckFeedback
import paige.navic.ui.core.LoginUiState
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.Add
import paige.navic.ui.icons.outlined.Check
import paige.navic.ui.icons.outlined.ChevronForward
import paige.navic.ui.icons.outlined.Close
import paige.navic.ui.icons.outlined.Delete
import paige.navic.ui.icons.outlined.Logout
import paige.navic.ui.icons.outlined.Refresh
import paige.navic.ui.screen.lyrics.component.LyricsStyleCustomizationControls
import paige.navic.ui.theme.MeloStudioPresets
import paige.navic.ui.theme.tabularNums
import paige.navic.util.network.PROXY_URL_REGEX
import paige.navic.util.toFileSize
import kotlin.math.roundToInt

internal const val SettingsLyricsOffsetMinMs = -5000
internal const val SettingsLyricsOffsetMaxMs = 5000
private const val SettingsLyricsOffsetStepMs = 100
private const val SettingsLyricsOffsetSliderSteps =
	(SettingsLyricsOffsetMaxMs - SettingsLyricsOffsetMinMs) / SettingsLyricsOffsetStepMs - 1

@Composable
internal fun rememberMeloVersionName(): String {
	val context = LocalContext.current
	return remember(context) {
		runCatching {
			context.packageManager
				.getPackageInfo(context.packageName, 0)
				.versionName
				?.trim()
				?.removePrefix("v")
				?.removePrefix("V")
				?.takeIf { it.isNotEmpty() }
		}.getOrNull() ?: "dev"
	}
}

@Composable
internal fun rememberThemePositiveColor(): Color {
	val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
	return if (isDark) Color(0xFF50C660) else Color(0xFF238636)
}

internal fun formatRelativeSyncTime(epochMs: Long): String {
	if (epochMs <= 0L) return "从未同步"
	val elapsedMs = (System.currentTimeMillis() - epochMs).coerceAtLeast(0L)
	val minutes = elapsedMs / 60_000L
	val hours = elapsedMs / 3_600_000L
	val days = elapsedMs / 86_400_000L
	return when {
		minutes < 1L -> "刚刚"
		hours < 1L -> "${minutes} 分钟前"
		days < 1L -> "${hours} 小时前"
		else -> "${days} 天前"
	}
}

private fun panelBottomPadding(nestedInSheet: Boolean) = if (nestedInSheet) 32.dp else 28.dp

// =============================================================================
// 1. 服务连接面板（MeloKeep / Navidrome）
// =============================================================================

@Composable
internal fun SettingsMeloKeepConnectionPanel(
	modifier: Modifier = Modifier,
	nestedInSheet: Boolean = false
) {
	val controller = koinInject<MeloSettingsController>()
	val focusManager = LocalFocusManager.current
	val state by controller.state.collectAsStateWithLifecycle()
	val serverConfig = state.serverConfig
	val positiveColor = rememberThemePositiveColor()

	var displayNameInput by rememberSaveable(serverConfig.displayName) {
		mutableStateOf(serverConfig.displayName)
	}
	var baseUrlInput by rememberSaveable(serverConfig.baseUrl) {
		mutableStateOf(serverConfig.baseUrl)
	}
	var usernameInput by rememberSaveable(serverConfig.username) {
		mutableStateOf(serverConfig.username)
	}
	var passwordInput by rememberSaveable { mutableStateOf("") }
	var passwordVisible by rememberSaveable { mutableStateOf(false) }

	val deviceName = remember {
		listOf(Build.MANUFACTURER, Build.MODEL)
			.map { it.trim() }
			.filter { it.isNotEmpty() }
			.distinct()
			.joinToString(" ")
			.ifBlank { "MeloKeep Android" }
	}

	LaunchedEffect(serverConfig.isAuthenticated) {
		if (serverConfig.isAuthenticated) {
			controller.loadConfig()
		}
	}

	MeloFeedbackObserver(
		errorMessage = state.errorMessage,
		noticeMessage = state.noticeMessage,
		onClearError = controller::clearError,
		onClearNotice = controller::clearNotice
	)

	LazyColumn(
		modifier = modifier.background(MaterialTheme.colorScheme.background),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = 8.dp,
			bottom = panelBottomPadding(nestedInSheet)
		),
		verticalArrangement = Arrangement.spacedBy(18.dp)
	) {
		item(key = "melokeep-connection") {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(
					title = "服务与凭据",
					trailing = {
						Text(
							text = if (serverConfig.isAuthenticated) "已连接" else "未连接",
							style = MaterialTheme.typography.labelSmall,
							color = if (serverConfig.isAuthenticated) {
								positiveColor
							} else {
								MaterialTheme.colorScheme.onSurfaceVariant
							}
						)
					}
				)
				MeloGroupedCard {
					Column(
						modifier = Modifier.padding(16.dp),
						verticalArrangement = Arrangement.spacedBy(10.dp)
					) {
						OutlinedTextField(
							value = displayNameInput,
							onValueChange = {
								displayNameInput = it
								controller.updateDisplayName(it.trim())
							},
							singleLine = true,
							modifier = Modifier.fillMaxWidth(),
							label = { Text("服务名称（可选，如 家里 NAS）") },
							placeholder = { Text("默认显示 MeloKeep") },
							keyboardOptions = KeyboardOptions(
								autoCorrectEnabled = false,
								imeAction = ImeAction.Next
							),
							shape = RoundedCornerShape(12.dp)
						)

						OutlinedTextField(
							value = baseUrlInput,
							onValueChange = { baseUrlInput = it },
							singleLine = true,
							modifier = Modifier.fillMaxWidth(),
							label = { Text("服务器地址") },
							placeholder = { Text("http://192.168.1.10:8765") },
							keyboardOptions = KeyboardOptions(
								autoCorrectEnabled = false,
								keyboardType = KeyboardType.Uri,
								imeAction = ImeAction.Next
							),
							shape = RoundedCornerShape(12.dp)
						)

						Row(
							modifier = Modifier.fillMaxWidth(),
							horizontalArrangement = Arrangement.spacedBy(10.dp)
						) {
							OutlinedTextField(
								value = usernameInput,
								onValueChange = { usernameInput = it },
								singleLine = true,
								modifier = Modifier.weight(1f),
								label = { Text("用户名（可选）") },
								keyboardOptions = KeyboardOptions(
									autoCorrectEnabled = false,
									imeAction = ImeAction.Next
								),
								shape = RoundedCornerShape(12.dp)
							)
							OutlinedTextField(
								value = passwordInput,
								onValueChange = { passwordInput = it },
								singleLine = true,
								modifier = Modifier.weight(1f),
								label = { Text("密码") },
								visualTransformation = if (passwordVisible) {
									VisualTransformation.None
								} else {
									PasswordVisualTransformation()
								},
								trailingIcon = {
									IconButton(onClick = { passwordVisible = !passwordVisible }) {
										Icon(
											imageVector = if (passwordVisible) MeloIcons.VisibilityOff else MeloIcons.Visibility,
											contentDescription = "切换密码可见性"
										)
									}
								},
								keyboardOptions = KeyboardOptions(
									autoCorrectEnabled = false,
									keyboardType = KeyboardType.Password,
									imeAction = ImeAction.Done
								),
								keyboardActions = KeyboardActions(
									onDone = {
										focusManager.clearFocus()
										if (baseUrlInput.isNotBlank()) {
											controller.testAndLogin(
												baseUrl = baseUrlInput,
												username = usernameInput,
												password = passwordInput,
												deviceName = deviceName,
												displayName = displayNameInput.trim()
											)
										}
									}
								),
								shape = RoundedCornerShape(12.dp)
							)
						}

						if (!state.errorMessage.isNullOrBlank()) {
							Text(
								text = state.errorMessage.orEmpty(),
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.error
							)
						} else if (!state.noticeMessage.isNullOrBlank()) {
							Text(
								text = state.noticeMessage.orEmpty(),
								style = MaterialTheme.typography.bodySmall,
								color = positiveColor
							)
						}

						Row(
							modifier = Modifier
								.fillMaxWidth()
								.padding(top = 2.dp),
							horizontalArrangement = Arrangement.spacedBy(10.dp),
							verticalAlignment = Alignment.CenterVertically
						) {
							Button(
								modifier = Modifier
									.weight(1f)
									.height(44.dp),
								shape = RoundedCornerShape(12.dp),
								enabled = !state.testingConnection && baseUrlInput.isNotBlank(),
								onClick = {
									focusManager.clearFocus()
									controller.testAndLogin(
										baseUrl = baseUrlInput,
										username = usernameInput,
										password = passwordInput,
										deviceName = deviceName,
										displayName = displayNameInput.trim()
									)
								}
							) {
								if (state.testingConnection) {
									CircularProgressIndicator(
										modifier = Modifier.size(16.dp),
										strokeWidth = 2.dp,
										color = MaterialTheme.colorScheme.onPrimary
									)
									Spacer(Modifier.width(8.dp))
									Text("连接中…")
								} else {
									Text(if (serverConfig.isAuthenticated) "保存并重新认证" else "连接并登录")
								}
							}

							if (serverConfig.isAuthenticated) {
								OutlinedButton(
									onClick = {
										passwordInput = ""
										controller.logout()
									},
									modifier = Modifier.height(44.dp),
									shape = RoundedCornerShape(12.dp),
									border = BorderStroke(
										MeloHairlineWidth,
										MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
									),
									colors = ButtonDefaults.outlinedButtonColors(
										contentColor = MaterialTheme.colorScheme.error
									)
								) {
									Icon(Icons.Outlined.Logout, null, modifier = Modifier.size(16.dp))
									Spacer(Modifier.width(6.dp))
									Text("退出")
								}
							}
						}
					}
				}
			}
		}

		if (serverConfig.isAuthenticated && state.devices.isNotEmpty()) {
			item(key = "melokeep-devices") {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					MeloSectionLabel(
						title = "已授权设备",
						trailing = {
							Text(
								text = "${state.devices.size} 台",
								style = MaterialTheme.typography.labelSmall.tabularNums(),
								color = MaterialTheme.colorScheme.onSurfaceVariant
							)
						}
					)
					MeloGroupedCard {
						state.devices.forEachIndexed { idx, device ->
							Row(
								modifier = Modifier
									.fillMaxWidth()
									.padding(horizontal = 16.dp, vertical = 12.dp),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(12.dp)
							) {
								Column(
									modifier = Modifier.weight(1f),
									verticalArrangement = Arrangement.spacedBy(2.dp)
								) {
									Text(
										text = if (device.current) {
											"${device.deviceName.ifBlank { "未知设备" }} · 当前"
										} else {
											device.deviceName.ifBlank { "未知设备" }
										},
										style = MaterialTheme.typography.bodyMedium,
										fontWeight = FontWeight.Medium,
										maxLines = 1,
										overflow = TextOverflow.Ellipsis
									)
									val created = formatEpochTimestamp(device.createdAt)
									Text(
										text = listOfNotNull(
											"ID ${device.id}",
											created.takeIf { it.isNotBlank() }
										).joinToString(" · "),
										style = MaterialTheme.typography.bodySmall.tabularNums(),
										color = MaterialTheme.colorScheme.onSurfaceVariant,
										maxLines = 1,
										overflow = TextOverflow.Ellipsis
									)
								}
								if (!device.current) {
									FilledTonalButton(
										onClick = { controller.revokeDevice(device.id) },
										shape = CircleShape,
										contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
										modifier = Modifier.height(30.dp),
										colors = ButtonDefaults.filledTonalButtonColors(
											containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
											contentColor = MaterialTheme.colorScheme.onErrorContainer
										)
									) {
										Icon(
											imageVector = Icons.Outlined.Delete,
											contentDescription = null,
											modifier = Modifier.size(14.dp)
										)
										Spacer(Modifier.width(4.dp))
										Text("吊销", style = MaterialTheme.typography.labelSmall)
									}
								}
							}
							if (idx < state.devices.lastIndex) {
								MeloHairlineDivider(startIndent = 16.dp)
							}
						}
					}
				}
			}
		}
	}
}

@Composable
internal fun SettingsNavidromeConnectionPanel(
	modifier: Modifier = Modifier,
	nestedInSheet: Boolean = false
) {
	val sessionManager = koinInject<SessionManager>()
	val loginManager = koinInject<LoginManager>()
	val permissionManager = koinInject<PermissionManager>()
	val preferenceManager = koinInject<PreferenceManager>()
	val exoStateHolder = koinInject<ExoStateHolder>()
	val focusManager = LocalFocusManager.current
	val scope = rememberCoroutineScope()
	val positiveColor = rememberThemePositiveColor()

	val sessionState by sessionManager.sessionState.collectAsStateWithLifecycle()
	val loginState by loginManager.loginState.collectAsStateWithLifecycle()
	val isConnected = sessionState !is SessionState.Unauthenticated
	val isBusy = loginState is LoginUiState.Loading || loginState is LoginUiState.Syncing

	var serverNameInput by rememberSaveable(preferenceManager.navidromeServerName) {
		mutableStateOf(preferenceManager.navidromeServerName)
	}
	var serverUrlInput by rememberSaveable(sessionManager.instanceUrl) {
		mutableStateOf(sessionManager.instanceUrl)
	}
	var usernameInput by rememberSaveable(sessionManager.username) {
		mutableStateOf(sessionManager.username)
	}
	var passwordInput by rememberSaveable { mutableStateOf("") }
	var passwordVisible by rememberSaveable { mutableStateOf(false) }
	var credentialsExpanded by rememberSaveable(isConnected) { mutableStateOf(!isConnected) }
	var localLoginError by remember { mutableStateOf<String?>(null) }

	var customHeadersInput by remember(preferenceManager.customHeaders) {
		mutableStateOf(preferenceManager.customHeaders)
	}
	var proxyUrlInput by remember(preferenceManager.proxyUrl) {
		mutableStateOf(preferenceManager.proxyUrl)
	}
	var showAddAltDialog by remember { mutableStateOf(false) }
	var showLogoutDialog by remember { mutableStateOf(false) }

	val proxyHasError = proxyUrlInput.isNotBlank() && !PROXY_URL_REGEX.matches(proxyUrlInput)

	LazyColumn(
		modifier = modifier.background(MaterialTheme.colorScheme.background),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = 8.dp,
			bottom = panelBottomPadding(nestedInSheet)
		),
		verticalArrangement = Arrangement.spacedBy(18.dp)
	) {
		// 1. 账号状态与凭据
		item(key = "navidrome-account") {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "账号")
				MeloGroupedCard {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(horizontal = 16.dp, vertical = 13.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(12.dp)
					) {
						Box(
							modifier = Modifier
								.size(8.dp)
								.clip(CircleShape)
								.background(
									if (isConnected) positiveColor
									else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
								)
						)
						Column(
							modifier = Modifier.weight(1f),
							verticalArrangement = Arrangement.spacedBy(2.dp)
						) {
							Text(
								text = if (isConnected) sessionManager.username.ifBlank { "Navidrome" } else "未连接",
								style = MaterialTheme.typography.bodyMedium,
								fontWeight = FontWeight.Medium
							)
							Text(
								text = if (isConnected) sessionManager.instanceUrl else "填写地址与账号登录",
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis
							)
						}
						if (isConnected) {
							Text(
								text = "退出",
								style = MaterialTheme.typography.labelMedium,
								color = MaterialTheme.colorScheme.error,
								modifier = Modifier
									.clip(RoundedCornerShape(8.dp))
									.clickable { showLogoutDialog = true }
									.padding(horizontal = 8.dp, vertical = 4.dp)
							)
						}
					}

					Column(
						modifier = Modifier
							.fillMaxWidth()
							.padding(horizontal = 16.dp)
							.padding(bottom = 12.dp)
					) {
						OutlinedTextField(
							value = serverNameInput,
							onValueChange = {
								serverNameInput = it
								preferenceManager.navidromeServerName = it.trim()
							},
							enabled = !isBusy,
							label = { Text("服务名称（可选）") },
							placeholder = { Text("默认显示 Navidrome") },
							singleLine = true,
							modifier = Modifier.fillMaxWidth(),
							keyboardOptions = KeyboardOptions(
								autoCorrectEnabled = false,
								imeAction = ImeAction.Next
							),
							shape = RoundedCornerShape(12.dp)
						)
					}

					if (isConnected) {
						MeloHairlineDivider(startIndent = 16.dp)
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.clickable { credentialsExpanded = !credentialsExpanded }
								.padding(horizontal = 16.dp, vertical = 12.dp),
							horizontalArrangement = Arrangement.SpaceBetween,
							verticalAlignment = Alignment.CenterVertically
						) {
							Text(
								text = if (credentialsExpanded) "收起凭据表单" else "切换账号或修改密码",
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.primary
							)
							Icon(
								imageVector = if (credentialsExpanded) Icons.Outlined.Close else Icons.Outlined.ChevronForward,
								contentDescription = null,
								tint = MaterialTheme.colorScheme.primary,
								modifier = Modifier.size(16.dp)
							)
						}
					}

					AnimatedVisibility(
						visible = !isConnected || credentialsExpanded,
						enter = fadeIn(),
						exit = fadeOut()
					) {
						Column(
							modifier = Modifier
								.fillMaxWidth()
								.padding(horizontal = 16.dp, vertical = 14.dp),
							verticalArrangement = Arrangement.spacedBy(10.dp)
						) {
							OutlinedTextField(
								value = serverUrlInput,
								onValueChange = {
									serverUrlInput = it
									localLoginError = null
								},
								enabled = !isBusy,
								label = { Text("服务器地址") },
								placeholder = { Text("http://192.168.1.10:4533") },
								singleLine = true,
								modifier = Modifier.fillMaxWidth(),
								keyboardOptions = KeyboardOptions(
									keyboardType = KeyboardType.Uri,
									imeAction = ImeAction.Next
								),
								shape = RoundedCornerShape(12.dp)
							)

							OutlinedTextField(
								value = usernameInput,
								onValueChange = {
									usernameInput = it
									localLoginError = null
								},
								enabled = !isBusy,
								label = { Text("用户名") },
								singleLine = true,
								modifier = Modifier.fillMaxWidth(),
								keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
								shape = RoundedCornerShape(12.dp)
							)

							OutlinedTextField(
								value = passwordInput,
								onValueChange = {
									passwordInput = it
									localLoginError = null
								},
								enabled = !isBusy,
								label = { Text("密码") },
								singleLine = true,
								modifier = Modifier.fillMaxWidth(),
								visualTransformation = if (passwordVisible) {
									VisualTransformation.None
								} else {
									PasswordVisualTransformation()
								},
								trailingIcon = {
									IconButton(onClick = { passwordVisible = !passwordVisible }) {
										Icon(
											imageVector = if (passwordVisible) MeloIcons.VisibilityOff else MeloIcons.Visibility,
											contentDescription = "切换密码可见性"
										)
									}
								},
								keyboardOptions = KeyboardOptions(
									keyboardType = KeyboardType.Password,
									imeAction = ImeAction.Done
								),
								keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
								shape = RoundedCornerShape(12.dp)
							)

							val activeError = localLoginError ?: (loginState as? LoginUiState.Error)?.error?.localizedMessage
							if (!activeError.isNullOrBlank()) {
								Text(
									text = activeError,
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.error
								)
							}

							if (loginState is LoginUiState.Syncing) {
								val syncing = loginState as LoginUiState.Syncing
								Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
									LinearProgressIndicator(
										progress = { syncing.progress.coerceIn(0f, 1f) },
										modifier = Modifier
											.fillMaxWidth()
											.height(3.dp)
											.clip(CircleShape),
										color = MaterialTheme.colorScheme.primary,
										trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
									)
									Text(
										text = "同步中 ${(syncing.progress * 100).toInt()}%",
										style = MaterialTheme.typography.labelSmall.tabularNums(),
										color = MaterialTheme.colorScheme.onSurfaceVariant
									)
								}
							}

							val canSubmit = !isBusy &&
								serverUrlInput.isNotBlank() &&
								usernameInput.isNotBlank() &&
								passwordInput.isNotBlank()
							Surface(
								onClick = {
									val url = serverUrlInput.trim()
									val user = usernameInput.trim()
									val pass = passwordInput
									if (url.isBlank() || user.isBlank() || pass.isBlank()) {
										localLoginError = "请完整填写地址、用户名与密码"
										return@Surface
									}
									focusManager.clearFocus()
									localLoginError = null
									preferenceManager.navidromeServerName = serverNameInput.trim()
									scope.launch {
										val normalizedUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
											url
										} else {
											"https://$url"
										}
										loginManager.instanceState.edit {
											replace(0, length, normalizedUrl)
										}
										loginManager.usernameState.edit {
											replace(0, length, user)
										}
										loginManager.passwordState.edit {
											replace(0, length, pass)
										}
										if (loginManager.isLocalNetworkInstance()) {
											if (!permissionManager.requestLocalNetworkPermission()) {
												localLoginError = "请授予局域网访问权限"
												return@launch
											}
										}
										permissionManager.requestNotificationsPermission()
										loginManager.login()
									}
								},
								enabled = canSubmit,
								shape = RoundedCornerShape(12.dp),
								color = if (canSubmit) {
									MaterialTheme.colorScheme.primary
								} else {
									MaterialTheme.colorScheme.surfaceContainerHigh
								},
								modifier = Modifier.fillMaxWidth()
							) {
								Box(
									modifier = Modifier.padding(vertical = 11.dp),
									contentAlignment = Alignment.Center
								) {
									Text(
										text = when {
											loginState is LoginUiState.Loading -> "验证中…"
											loginState is LoginUiState.Syncing -> "同步中…"
											isConnected -> "重新连接"
											else -> "连接并登录"
										},
										style = MaterialTheme.typography.labelLarge,
										fontWeight = FontWeight.SemiBold,
										color = if (canSubmit) {
											MaterialTheme.colorScheme.onPrimary
										} else {
											MaterialTheme.colorScheme.onSurfaceVariant
										}
									)
								}
							}
						}
					}
				}
			}
		}

		// 2. 自定义 Headers 与代理
		item(key = "navidrome-network") {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "网络与请求头")
				MeloGroupedCard {
					Column(
						modifier = Modifier.padding(16.dp),
						verticalArrangement = Arrangement.spacedBy(10.dp)
					) {
						OutlinedTextField(
							value = customHeadersInput,
							onValueChange = {
								customHeadersInput = it
								preferenceManager.customHeaders = it
								sessionManager.refreshClient()
								exoStateHolder.reloadNetworkDataSource()
							},
							label = { Text("自定义 Headers") },
							placeholder = { Text("Header-Name: Value") },
							minLines = 2,
							maxLines = 4,
							modifier = Modifier.fillMaxWidth(),
							shape = RoundedCornerShape(12.dp)
						)

						OutlinedTextField(
							value = proxyUrlInput,
							onValueChange = {
								proxyUrlInput = it
								if (it.isBlank() || PROXY_URL_REGEX.matches(it)) {
									preferenceManager.proxyUrl = it
									sessionManager.refreshClient()
									exoStateHolder.reloadNetworkDataSource()
								}
							},
							label = { Text("HTTP / SOCKS5 代理") },
							placeholder = { Text("socks5://127.0.0.1:1080") },
							singleLine = true,
							isError = proxyHasError,
							supportingText = if (proxyHasError) {
								{ Text("格式无效，示例：http://127.0.0.1:7890", color = MaterialTheme.colorScheme.error) }
							} else {
								null
							},
							modifier = Modifier.fillMaxWidth(),
							shape = RoundedCornerShape(12.dp)
						)
					}

					MeloHairlineDivider(startIndent = 16.dp)

					SettingsToggleRow(
						title = "忽略自签名 SSL 证书",
						checked = preferenceManager.dangerousSslNoopEnabled,
						onCheckedChange = { checked ->
							preferenceManager.dangerousSslNoopEnabled = checked
							sessionManager.refreshClient()
							exoStateHolder.reloadNetworkDataSource()
						}
					)
				}
			}
		}

		// 3. 备用服务器地址
		item(key = "navidrome-alt-urls") {
			val altAddresses = preferenceManager.alternateServerAddresses
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(
					title = "备用地址 (${altAddresses.size})",
					trailing = {
						Row(
							modifier = Modifier
								.clip(RoundedCornerShape(6.dp))
								.clickable { showAddAltDialog = true }
								.padding(horizontal = 4.dp, vertical = 2.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(2.dp)
						) {
							Icon(
								imageVector = Icons.Outlined.Add,
								contentDescription = null,
								tint = MaterialTheme.colorScheme.primary,
								modifier = Modifier.size(14.dp)
							)
							Text(
								text = "添加",
								style = MaterialTheme.typography.labelMedium,
								color = MaterialTheme.colorScheme.primary
							)
						}
					}
				)
				MeloGroupedCard {
					if (altAddresses.isEmpty()) {
						Text(
							text = "暂无备用地址",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
						)
					} else {
						altAddresses.forEachIndexed { index, altUrl ->
							Row(
								modifier = Modifier
									.fillMaxWidth()
									.padding(horizontal = 16.dp, vertical = 10.dp),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(8.dp)
							) {
								Text(
									text = altUrl,
									style = MaterialTheme.typography.bodySmall,
									modifier = Modifier.weight(1f),
									maxLines = 1,
									overflow = TextOverflow.Ellipsis
								)
								Text(
									text = "设为主地址",
									style = MaterialTheme.typography.labelSmall,
									color = MaterialTheme.colorScheme.primary,
									modifier = Modifier
										.clip(RoundedCornerShape(6.dp))
										.clickable {
											sessionManager.updateInstanceUrl(altUrl)
											serverUrlInput = altUrl
										}
										.padding(horizontal = 6.dp, vertical = 4.dp)
								)
								IconButton(
									onClick = {
										preferenceManager.alternateServerAddresses = altAddresses - altUrl
									},
									modifier = Modifier.size(28.dp)
								) {
									Icon(
										imageVector = Icons.Outlined.Close,
										contentDescription = "删除",
										tint = MaterialTheme.colorScheme.onSurfaceVariant,
										modifier = Modifier.size(15.dp)
									)
								}
							}
							if (index < altAddresses.lastIndex) {
								MeloHairlineDivider(startIndent = 16.dp)
							}
						}
					}
				}
			}
		}
	}

	if (showAddAltDialog) {
		var newUrl by remember { mutableStateOf("") }
		AlertDialog(
			onDismissRequest = { showAddAltDialog = false },
			title = { Text("添加备用地址") },
			text = {
				OutlinedTextField(
					value = newUrl,
					onValueChange = { newUrl = it },
					label = { Text("服务器 URL") },
					placeholder = { Text("http://192.168.1.100:4533") },
					singleLine = true,
					modifier = Modifier.fillMaxWidth()
				)
			},
			confirmButton = {
				TextButton(
					onClick = {
						val trimmed = newUrl.trim()
						if (trimmed.isNotBlank()) {
							preferenceManager.alternateServerAddresses =
								(preferenceManager.alternateServerAddresses + trimmed).distinct()
						}
						showAddAltDialog = false
					}
				) {
					Text("添加")
				}
			},
			dismissButton = {
				TextButton(onClick = { showAddAltDialog = false }) {
					Text("取消")
				}
			}
		)
	}

	if (showLogoutDialog) {
		AlertDialog(
			onDismissRequest = { showLogoutDialog = false },
			title = { Text("退出登录") },
			text = { Text("断开当前 Navidrome 连接并清理本地索引？") },
			confirmButton = {
				TextButton(
					onClick = {
						loginManager.logout()
						showLogoutDialog = false
					}
				) {
					Text("退出", color = MaterialTheme.colorScheme.error)
				}
			},
			dismissButton = {
				TextButton(onClick = { showLogoutDialog = false }) {
					Text("取消")
				}
			}
		)
	}
}

// =============================================================================
// 2. 曲库与同步面板（MeloLibrarySyncSheet 内容）
// =============================================================================

@Composable
internal fun SettingsLibrarySyncPanel(
	modifier: Modifier = Modifier,
	nestedInSheet: Boolean = false
) {
	val syncManager = koinInject<SyncManager>()
	val syncCoordinator = koinInject<SyncCoordinator>()
	val sessionManager = koinInject<SessionManager>()
	val preferenceManager = koinInject<PreferenceManager>()
	val scope = rememberCoroutineScope()
	val positiveColor = rememberThemePositiveColor()

	val syncState by syncManager.syncState.collectAsStateWithLifecycle()
	val sessionState by sessionManager.sessionState.collectAsStateWithLifecycle()
	val isNavidromeConnected = sessionState is SessionState.Authenticated

	var incrementalSyncing by remember { mutableStateOf(false) }
	var syncFeedback by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

	val animatedSyncProgress by animateFloatAsState(
		targetValue = if (syncState.isSyncing) syncState.progress.coerceIn(0f, 1f) else 0f,
		animationSpec = tween(durationMillis = 300, easing = EaseOut),
		label = "LibrarySyncProgress"
	)

	LazyColumn(
		modifier = modifier.background(MaterialTheme.colorScheme.background),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = 8.dp,
			bottom = panelBottomPadding(nestedInSheet)
		),
		verticalArrangement = Arrangement.spacedBy(18.dp)
	) {
		item(key = "sync-offline-mode") {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "网络与离线模式")
				MeloGroupedCard {
					Column(
						modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
						verticalArrangement = Arrangement.spacedBy(10.dp)
					) {
						Row(
							modifier = Modifier.fillMaxWidth(),
							horizontalArrangement = Arrangement.spacedBy(6.dp)
						) {
							OfflineMode.entries.forEach { mode ->
								SettingsChoicePill(
									label = when (mode) {
										OfflineMode.Auto -> "自动"
										OfflineMode.Forced -> "离线"
										OfflineMode.NoWiFi -> "仅 Wi-Fi"
									},
									selected = preferenceManager.offlineMode == mode,
									onClick = { preferenceManager.offlineMode = mode },
									modifier = Modifier.weight(1f)
								)
							}
						}
						Text(
							text = when (preferenceManager.offlineMode) {
								OfflineMode.Auto -> "自动模式：有网时正常流媒体播放，无网时回退至本地已缓存歌曲。"
								OfflineMode.Forced -> "离线模式：仅访问与播放已下载至本机的曲库资源。"
								OfflineMode.NoWiFi -> "仅 Wi-Fi：在移动网络下自动禁用在线流媒体与索引同步。"
							},
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
					}
				}
			}
		}

		item(key = "sync-index-control") {
			val lastSyncText = formatRelativeSyncTime(preferenceManager.lastFullSyncTime)
			val isBusy = syncState.isSyncing || incrementalSyncing

			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(
					title = "Navidrome 曲库索引",
					trailing = {
						Text(
							text = "上次同步：$lastSyncText",
							style = MaterialTheme.typography.labelSmall.tabularNums(),
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
					}
				)
				MeloGroupedCard {
					Column(
						modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
						verticalArrangement = Arrangement.spacedBy(12.dp)
					) {
						Row(
							modifier = Modifier.fillMaxWidth(),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(10.dp)
						) {
							Box(
								modifier = Modifier
									.size(8.dp)
									.clip(CircleShape)
									.background(
										when {
											isBusy -> MaterialTheme.colorScheme.primary
											isNavidromeConnected -> positiveColor
											else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
										}
									)
							)
							Column(
								modifier = Modifier.weight(1f),
								verticalArrangement = Arrangement.spacedBy(2.dp)
							) {
								Text(
									text = when {
										syncState.isSyncing -> "正在同步元数据索引…"
										incrementalSyncing -> "正在执行增量同步…"
										!isNavidromeConnected -> "尚未连接 Navidrome"
										else -> "元数据索引已就绪"
									},
									style = MaterialTheme.typography.bodyMedium,
									fontWeight = FontWeight.Medium
								)
								Text(
									text = if (isNavidromeConnected) {
										"支持轻量增量同步变更记录或重新拉取全量曲库索引"
									} else {
										"请先在「服务器连接」中登录 Navidrome 服务"
									},
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant
								)
							}
						}

						if (syncState.isSyncing) {
							LinearProgressIndicator(
								progress = { animatedSyncProgress },
								modifier = Modifier
									.fillMaxWidth()
									.height(4.dp)
									.clip(CircleShape),
								color = MaterialTheme.colorScheme.primary,
								trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
							)
						}

						syncFeedback?.let { (message, isError) ->
							Text(
								text = message,
								style = MaterialTheme.typography.bodySmall,
								color = if (isError) MaterialTheme.colorScheme.error else positiveColor
							)
						}

						Row(
							modifier = Modifier.fillMaxWidth(),
							horizontalArrangement = Arrangement.spacedBy(10.dp)
						) {
							FilledTonalButton(
								onClick = {
									if (!isNavidromeConnected || isBusy) return@FilledTonalButton
									incrementalSyncing = true
									syncFeedback = null
									scope.launch {
										val result = withContext(Dispatchers.IO) {
											syncCoordinator.run(force = false)
										}
										incrementalSyncing = false
										syncFeedback = result.fold(
											onSuccess = { "增量同步已完成" to false },
											onFailure = { err ->
												(err.message?.takeIf { it.isNotBlank() } ?: "增量同步失败") to true
											}
										)
									}
								},
								enabled = isNavidromeConnected && !isBusy,
								shape = RoundedCornerShape(12.dp),
								modifier = Modifier.weight(1f)
							) {
								if (incrementalSyncing) {
									CircularProgressIndicator(
										modifier = Modifier.size(14.dp),
										strokeWidth = 2.dp
									)
									Spacer(Modifier.width(6.dp))
								}
								Text("增量同步")
							}

							Button(
								onClick = {
									if (!isNavidromeConnected || isBusy) return@Button
									syncFeedback = null
									syncManager.triggerManualSync()
									syncFeedback = "已触发全量索引重建" to false
								},
								enabled = isNavidromeConnected && !isBusy,
								shape = RoundedCornerShape(12.dp),
								modifier = Modifier.weight(1f)
							) {
								Text("强制全量同步")
							}
						}
					}
				}
			}
		}
	}
}

// =============================================================================
// 3. 音质与播放面板（MeloPlaybackAndQualitySheet 内容）
// =============================================================================

@Composable
internal fun SettingsPlaybackAndQualityPanel(
	modifier: Modifier = Modifier,
	nestedInSheet: Boolean = false
) {
	val preferenceManager = koinInject<PreferenceManager>()

	LazyColumn(
		modifier = modifier.background(MaterialTheme.colorScheme.background),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = 8.dp,
			bottom = panelBottomPadding(nestedInSheet)
		),
		verticalArrangement = Arrangement.spacedBy(18.dp)
	) {
		item(key = "playback-quality") {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "网络与离线音质")
				MeloGroupedCard {
					Column(
						modifier = Modifier.padding(16.dp),
						verticalArrangement = Arrangement.spacedBy(14.dp)
					) {
						SettingsQualitySelector(
							title = "Wi-Fi 流媒体",
							current = preferenceManager.streamingQualityWifi,
							onSelect = { preferenceManager.streamingQualityWifi = it }
						)
						SettingsQualitySelector(
							title = "移动网络",
							current = preferenceManager.streamingQualityCellular,
							onSelect = { preferenceManager.streamingQualityCellular = it }
						)
						SettingsQualitySelector(
							title = "离线下载",
							current = preferenceManager.downloadQuality,
							onSelect = { preferenceManager.downloadQuality = it }
						)
					}
				}
			}
		}

		item(key = "playback-behavior") {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "播放与切歌交互")
				MeloGroupedCard {
					SettingsToggleRow(
						title = "无缝播放",
						subtitle = "曲目衔接零间隙过渡",
						checked = preferenceManager.gaplessPlayback,
						onCheckedChange = { preferenceManager.gaplessPlayback = it }
					)
					MeloHairlineDivider(startIndent = 16.dp)
					SettingsToggleRow(
						title = "队列播完自动续播",
						subtitle = "播完当前列表后自动补充相似曲目",
						checked = preferenceManager.autoFillQueue,
						onCheckedChange = { preferenceManager.autoFillQueue = it }
					)
					MeloHairlineDivider(startIndent = 16.dp)
					SettingsToggleRow(
						title = "左右滑动切歌",
						subtitle = "在播放页封面左右滑动切换上一首 / 下一首",
						checked = preferenceManager.swipeToSkip,
						onCheckedChange = { preferenceManager.swipeToSkip = it }
					)
					MeloHairlineDivider(startIndent = 16.dp)
					SettingsToggleRow(
						title = "显示音频参数",
						subtitle = "在播放页展示编码格式、采样率与码率",
						checked = preferenceManager.nowPlayingSongInfo,
						onCheckedChange = { preferenceManager.nowPlayingSongInfo = it }
					)
				}
			}
		}

		item(key = "playback-decoder") {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "解码与硬件输出")
				MeloGroupedCard {
					SettingsToggleRow(
						title = "32-bit 浮点输出",
						subtitle = "保留更高动态余量（部分外置 DAC 设备适用）",
						checked = preferenceManager.floatOutputEnabled,
						onCheckedChange = { preferenceManager.floatOutputEnabled = it }
					)
					MeloHairlineDivider(startIndent = 16.dp)
					SettingsToggleRow(
						title = "硬件直通 (Audio Offload)",
						subtitle = "交由系统 DSP 硬件解码以降低功耗",
						checked = preferenceManager.audioOffload,
						onCheckedChange = { preferenceManager.audioOffload = it }
					)
				}
			}
		}
	}
}

// =============================================================================
// 4. 音效与增益面板（ReplayGain + 均衡器合一，消除二级跳三级套娃）
// =============================================================================

@Composable
internal fun SettingsSoundAndGainPanel(
	modifier: Modifier = Modifier,
	nestedInSheet: Boolean = false
) {
	val preferenceManager = koinInject<PreferenceManager>()
	val audioGainManager = koinInject<AudioGainManager>()

	LazyColumn(
		modifier = modifier.background(MaterialTheme.colorScheme.background),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = 8.dp,
			bottom = panelBottomPadding(nestedInSheet)
		),
		verticalArrangement = Arrangement.spacedBy(18.dp)
	) {
		item(key = "sound-replaygain") {
			val rgAmpGain = preferenceManager.rgAmpGain
			val ampGain = preferenceManager.ampGain
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "ReplayGain 音量平衡")
				MeloGroupedCard {
					Column(
						modifier = Modifier.padding(16.dp),
						verticalArrangement = Arrangement.spacedBy(12.dp)
					) {
						Row(
							modifier = Modifier.fillMaxWidth(),
							horizontalArrangement = Arrangement.spacedBy(6.dp)
						) {
							ReplayGainMode.entries.forEach { mode ->
								SettingsChoicePill(
									label = when (mode) {
										ReplayGainMode.Off -> "关闭"
										ReplayGainMode.Track -> "单曲"
										ReplayGainMode.Album -> "专辑"
										ReplayGainMode.Dynamic -> "动态"
									},
									selected = preferenceManager.replayGainMode == mode,
									onClick = {
										preferenceManager.replayGainMode = mode
										audioGainManager.applyGainMode(mode)
									},
									modifier = Modifier.weight(1f)
								)
							}
						}

						Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.SpaceBetween
							) {
								Text(
									text = "有标签前级增益",
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant
								)
								Text(
									text = SettingsFormatGain(rgAmpGain),
									style = MaterialTheme.typography.labelSmall.tabularNums(),
									fontWeight = FontWeight.SemiBold
								)
							}
							Slider(
								value = rgAmpGain,
								onValueChange = {
									val rounded = (it * 10f).roundToInt() / 10f
									preferenceManager.rgAmpGain = rounded
									audioGainManager.setAmplifierValues(rounded, ampGain)
								},
								valueRange = -12f..12f,
								colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary)
							)
						}

						Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.SpaceBetween
							) {
								Text(
									text = "无标签补偿增益",
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant
								)
								Text(
									text = SettingsFormatGain(ampGain),
									style = MaterialTheme.typography.labelSmall.tabularNums(),
									fontWeight = FontWeight.SemiBold
								)
							}
							Slider(
								value = ampGain,
								onValueChange = {
									val rounded = (it * 10f).roundToInt() / 10f
									preferenceManager.ampGain = rounded
									audioGainManager.setAmplifierValues(rgAmpGain, rounded)
								},
								valueRange = -12f..12f,
								colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary)
							)
						}
					}
				}
			}
		}

		item(key = "sound-equalizer") {
			SettingsEqualizerContent()
		}
	}
}

// =============================================================================
// 5. 歌词与桌面歌词面板（桌面悬浮歌词置顶 → 状态栏歌词 → 播放页样式 → 时间轴偏移 → 显示开关）
// =============================================================================

@Composable
internal fun SettingsLyricsSettingsPanel(
	modifier: Modifier = Modifier,
	nestedInSheet: Boolean = false
) {
	val preferenceManager = koinInject<PreferenceManager>()
	val context = LocalContext.current
	val lifecycleOwner = LocalLifecycleOwner.current

	var hasOverlayPermission by remember {
		mutableStateOf(Settings.canDrawOverlays(context))
	}
	var pendingEnableDesktopLyrics by remember { mutableStateOf(false) }

	DisposableEffect(lifecycleOwner) {
		val observer = LifecycleEventObserver { _, event ->
			if (event == Lifecycle.Event.ON_RESUME) {
				val granted = Settings.canDrawOverlays(context)
				hasOverlayPermission = granted
				if (granted && pendingEnableDesktopLyrics) {
					pendingEnableDesktopLyrics = false
					preferenceManager.desktopLyricsEnabled = true
				}
			}
		}
		lifecycleOwner.lifecycle.addObserver(observer)
		onDispose {
			lifecycleOwner.lifecycle.removeObserver(observer)
		}
	}

	val openOverlayPermissionSettings = {
		val intent = Intent(
			Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
			Uri.parse("package:${context.packageName}")
		)
		runCatching { context.startActivity(intent) }
	}

	LazyColumn(
		modifier = modifier.background(MaterialTheme.colorScheme.background),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = 8.dp,
			bottom = panelBottomPadding(nestedInSheet)
		),
		verticalArrangement = Arrangement.spacedBy(18.dp)
	) {
		// 1. 顶部置顶：桌面悬浮歌词
		item(key = "lyrics-desktop") {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "桌面悬浮歌词")
				MeloGroupedCard {
					SettingsToggleRow(
						title = if (hasOverlayPermission) {
							"开启桌面悬浮歌词"
						} else {
							"开启桌面悬浮歌词（需授权悬浮窗）"
						},
						checked = preferenceManager.desktopLyricsEnabled && hasOverlayPermission,
						onCheckedChange = { enabled ->
							if (!enabled) {
								pendingEnableDesktopLyrics = false
								preferenceManager.desktopLyricsEnabled = false
							} else if (Settings.canDrawOverlays(context)) {
								hasOverlayPermission = true
								preferenceManager.desktopLyricsEnabled = true
							} else {
								pendingEnableDesktopLyrics = true
								openOverlayPermissionSettings()
							}
						}
					)
					MeloHairlineDivider(startIndent = 16.dp)
					SettingsToggleRow(
						title = "锁定悬浮窗（开启后触摸穿透）",
						checked = preferenceManager.desktopLyricsLocked,
						onCheckedChange = { preferenceManager.desktopLyricsLocked = it }
					)
					MeloHairlineDivider(startIndent = 16.dp)
					Column(
						modifier = Modifier
							.fillMaxWidth()
							.padding(horizontal = 16.dp, vertical = 14.dp),
						verticalArrangement = Arrangement.spacedBy(12.dp)
					) {
						val fontSizeSp = preferenceManager.desktopLyricsFontSizeSp.coerceIn(12f, 32f)
						Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.SpaceBetween,
								verticalAlignment = Alignment.CenterVertically
							) {
								Text(
									text = "悬浮歌词字号",
									style = MaterialTheme.typography.bodyMedium,
									fontWeight = FontWeight.Medium
								)
								Text(
									text = "${fontSizeSp.toInt()} sp",
									style = MaterialTheme.typography.labelMedium.tabularNums(),
									color = MaterialTheme.colorScheme.primary
								)
							}
							Slider(
								value = fontSizeSp,
								onValueChange = {
									preferenceManager.desktopLyricsFontSizeSp = it.coerceIn(12f, 32f)
								},
								valueRange = 12f..32f
							)
						}

						val bgAlpha = preferenceManager.desktopLyricsBgAlpha.coerceIn(0f, 0.9f)
						Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.SpaceBetween,
								verticalAlignment = Alignment.CenterVertically
							) {
								Text(
									text = "悬浮窗背景不透明度",
									style = MaterialTheme.typography.bodyMedium,
									fontWeight = FontWeight.Medium
								)
								Text(
									text = "${(bgAlpha * 100f).toInt()}%",
									style = MaterialTheme.typography.labelMedium.tabularNums(),
									color = MaterialTheme.colorScheme.primary
								)
							}
							Slider(
								value = bgAlpha,
								onValueChange = {
									preferenceManager.desktopLyricsBgAlpha = it.coerceIn(0f, 0.9f)
								},
								valueRange = 0f..0.9f
							)
						}

						val colorPresets = listOf(
							"#FFFFFF" to Color(0xFFFFFFFF),
							"#FFE082" to Color(0xFFFFE082),
							"#80DEEA" to Color(0xFF80DEEA),
							"#F48FB1" to Color(0xFFF48FB1),
							"#A5D6A7" to Color(0xFFA5D6A7),
							"#CE93D8" to Color(0xFFCE93D8)
						)
						val currentColorHex = preferenceManager.desktopLyricsColorHex
							.trim()
							.uppercase()
							.ifBlank { "#FFFFFF" }
						Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
							Text(
								text = "悬浮歌词颜色",
								style = MaterialTheme.typography.bodyMedium,
								fontWeight = FontWeight.Medium
							)
							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.spacedBy(10.dp),
								verticalAlignment = Alignment.CenterVertically
							) {
								colorPresets.forEach { (hex, swatchColor) ->
									val selected = currentColorHex == hex
									Surface(
										onClick = { preferenceManager.desktopLyricsColorHex = hex },
										shape = CircleShape,
										color = swatchColor,
										border = BorderStroke(
											width = if (selected) 2.5.dp else 1.dp,
											color = if (selected) {
												MaterialTheme.colorScheme.primary
											} else {
												MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
											}
										),
										modifier = Modifier.size(28.dp)
									) {}
								}
							}
						}

						Row(
							modifier = Modifier.fillMaxWidth(),
							horizontalArrangement = Arrangement.SpaceBetween,
							verticalAlignment = Alignment.CenterVertically
						) {
							OutlinedButton(
								onClick = { preferenceManager.desktopLyricsYFraction = 0.15f }
							) {
								Text("重置悬浮窗位置")
							}
							if (!hasOverlayPermission) {
								FilledTonalButton(onClick = { openOverlayPermissionSettings() }) {
									Text("去授权悬浮窗")
								}
							}
						}
					}
				}
			}
		}

		// 2. 状态栏歌词
		item(key = "lyrics-statusbar") {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "状态栏歌词")
				MeloGroupedCard {
					SettingsToggleRow(
						title = "开启状态栏歌词",
						checked = preferenceManager.statusBarLyricsEnabled,
						onCheckedChange = { preferenceManager.statusBarLyricsEnabled = it }
					)
					MeloHairlineDivider(startIndent = 16.dp)
					SettingsLyricsOffsetControl(
						title = "状态栏时间偏移",
						offsetMs = preferenceManager.statusBarLyricsOffsetMs,
						onOffsetChange = { preferenceManager.statusBarLyricsOffsetMs = it },
						showSteppers = true,
						modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
					)
				}
			}
		}

		// 3. 播放页歌词样式（含行间距）
		item(key = "lyrics-player-style") {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "播放页歌词样式")
				MeloGroupedCard {
					LyricsStyleCustomizationControls(
						preferenceManager = preferenceManager,
						showResetButton = true,
						modifier = Modifier.padding(16.dp)
					)
				}
			}
		}

		// 4. 时间轴偏移
		item(key = "lyrics-timeline-offset") {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "时间轴偏移")
				MeloGroupedCard {
					SettingsLyricsOffsetControl(
						title = "播放页歌词偏移",
						offsetMs = preferenceManager.playerLyricsOffsetMs,
						onOffsetChange = { preferenceManager.playerLyricsOffsetMs = it },
						showSteppers = true,
						modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
					)
				}
			}
		}

		// 5. 其它歌词开关
		item(key = "lyrics-display-toggles") {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "歌词显示与行为")
				MeloGroupedCard {
					SettingsToggleRow(
						title = "歌词自动滚动",
						checked = preferenceManager.lyricsAutoscroll,
						onCheckedChange = { preferenceManager.lyricsAutoscroll = it }
					)
					MeloHairlineDivider(startIndent = 16.dp)
					SettingsToggleRow(
						title = "逐字高亮",
						checked = preferenceManager.lyricsBeatByBeat,
						onCheckedChange = { preferenceManager.lyricsBeatByBeat = it }
					)
					MeloHairlineDivider(startIndent = 16.dp)
					SettingsToggleRow(
						title = "模拟逐字节奏",
						checked = preferenceManager.lyricsFakeBeatByBeat,
						onCheckedChange = { preferenceManager.lyricsFakeBeatByBeat = it }
					)
					MeloHairlineDivider(startIndent = 16.dp)
					SettingsToggleRow(
						title = "屏幕常亮",
						checked = preferenceManager.lyricsKeepAlive,
						onCheckedChange = { preferenceManager.lyricsKeepAlive = it }
					)
					MeloHairlineDivider(startIndent = 16.dp)
					SettingsToggleRow(
						title = "背景柔焦",
						checked = preferenceManager.lyricsBlur,
						onCheckedChange = { preferenceManager.lyricsBlur = it }
					)
					MeloHairlineDivider(startIndent = 16.dp)
					SettingsToggleRow(
						title = "未激活行增亮",
						checked = preferenceManager.lyricsBrightInactive,
						onCheckedChange = { preferenceManager.lyricsBrightInactive = it }
					)
				}
			}
		}
	}
}

@Composable
internal fun SettingsLyricsOffsetControl(
	title: String,
	offsetMs: Int,
	onOffsetChange: (Int) -> Unit,
	modifier: Modifier = Modifier,
	showSteppers: Boolean = true
) {
	val clampedOffset = offsetMs.coerceIn(SettingsLyricsOffsetMinMs, SettingsLyricsOffsetMaxMs)

	Column(
		modifier = modifier.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(6.dp)
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically
		) {
			Text(
				text = title,
				style = MaterialTheme.typography.bodyMedium,
				fontWeight = FontWeight.Medium,
				color = MaterialTheme.colorScheme.onSurface
			)
			Text(
				text = SettingsFormatOffsetLabel(offsetMs),
				style = MaterialTheme.typography.labelMedium.tabularNums(),
				fontWeight = FontWeight.SemiBold,
				color = if (offsetMs != 0) {
					MaterialTheme.colorScheme.primary
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				}
			)
		}

		Slider(
			value = clampedOffset.toFloat(),
			onValueChange = { raw ->
				val stepped = ((raw / SettingsLyricsOffsetStepMs.toFloat()).roundToInt() * SettingsLyricsOffsetStepMs)
					.coerceIn(SettingsLyricsOffsetMinMs, SettingsLyricsOffsetMaxMs)
				if (stepped != offsetMs) {
					onOffsetChange(stepped)
				}
			},
			valueRange = SettingsLyricsOffsetMinMs.toFloat()..SettingsLyricsOffsetMaxMs.toFloat(),
			steps = SettingsLyricsOffsetSliderSteps,
			modifier = Modifier.fillMaxWidth(),
			colors = SliderDefaults.colors(
				thumbColor = MaterialTheme.colorScheme.primary,
				activeTrackColor = MaterialTheme.colorScheme.primary,
				activeTickColor = Color.Transparent,
				inactiveTickColor = Color.Transparent
			)
		)

		if (showSteppers) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(6.dp)
			) {
				SettingsStepPill(
					label = "-500",
					onClick = {
						onOffsetChange((offsetMs - 500).coerceIn(SettingsLyricsOffsetMinMs, SettingsLyricsOffsetMaxMs))
					},
					modifier = Modifier.weight(1f)
				)
				SettingsStepPill(
					label = "-100",
					onClick = {
						onOffsetChange((offsetMs - 100).coerceIn(SettingsLyricsOffsetMinMs, SettingsLyricsOffsetMaxMs))
					},
					modifier = Modifier.weight(1f)
				)
				SettingsStepPill(
					label = "重置",
					highlighted = offsetMs != 0,
					onClick = { onOffsetChange(0) },
					modifier = Modifier.weight(0.95f)
				)
				SettingsStepPill(
					label = "+100",
					onClick = {
						onOffsetChange((offsetMs + 100).coerceIn(SettingsLyricsOffsetMinMs, SettingsLyricsOffsetMaxMs))
					},
					modifier = Modifier.weight(1f)
				)
				SettingsStepPill(
					label = "+500",
					onClick = {
						onOffsetChange((offsetMs + 500).coerceIn(SettingsLyricsOffsetMinMs, SettingsLyricsOffsetMaxMs))
					},
					modifier = Modifier.weight(1f)
				)
			}
		}
	}
}

// =============================================================================
// 6. 主题与外观面板（MeloThemeAppearanceSheet 内容）
// =============================================================================

@Composable
internal fun SettingsThemeAppearancePanel(
	modifier: Modifier = Modifier,
	nestedInSheet: Boolean = false
) {
	val meloSettingsStore = koinInject<MeloSettingsStore>()
	val preferenceManager = koinInject<PreferenceManager>()
	val studioPreset by meloSettingsStore.studioThemePreset.collectAsStateWithLifecycle()

	LazyColumn(
		modifier = modifier.background(MaterialTheme.colorScheme.background),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = 8.dp,
			bottom = panelBottomPadding(nestedInSheet)
		),
		verticalArrangement = Arrangement.spacedBy(18.dp)
	) {
		item(key = "theme-presets") {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "Studio 主题预设")
				Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
					MeloStudioPresets.all.chunked(2).forEach { rowPresets ->
						Row(
							modifier = Modifier.fillMaxWidth(),
							horizontalArrangement = Arrangement.spacedBy(10.dp)
						) {
							rowPresets.forEach { descriptor ->
								val selected = studioPreset == descriptor.id
								ThemePresetSwatchCard(
									descriptor = descriptor,
									selected = selected,
									onClick = { meloSettingsStore.setStudioThemePreset(descriptor.id) },
									modifier = Modifier.weight(1f)
								)
							}
							if (rowPresets.size == 1) {
								Spacer(modifier = Modifier.weight(1f))
							}
						}
					}
				}
			}
		}

		item(key = "theme-mode-amoled") {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "深浅色与纯黑")
				MeloGroupedCard {
					Column(
						modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
						verticalArrangement = Arrangement.spacedBy(10.dp)
					) {
						Text(
							text = "色彩极性",
							style = MaterialTheme.typography.bodyMedium,
							fontWeight = FontWeight.Medium
						)
						Row(
							modifier = Modifier.fillMaxWidth(),
							horizontalArrangement = Arrangement.spacedBy(6.dp)
						) {
							listOf(
								ThemeMode.System to "跟随系统",
								ThemeMode.Dark to "深色",
								ThemeMode.Light to "浅色"
							).forEach { (mode, label) ->
								SettingsChoicePill(
									label = label,
									selected = preferenceManager.themeMode == mode,
									onClick = { preferenceManager.themeMode = mode },
									modifier = Modifier.weight(1f)
								)
							}
						}
						if (studioPreset != MeloStudioPresets.DYNAMIC) {
							Text(
								text = "当前使用固定调色预设；切换为「动态壁纸色」时将自动遵循以上深浅色与 AMOLED 纯黑设置。",
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant
							)
						}
					}

					MeloHairlineDivider(startIndent = 16.dp)

					SettingsToggleRow(
						title = "AMOLED 纯黑背景",
						subtitle = "深色模式下使用纯粹 #000000 底色以节省屏幕功耗",
						checked = preferenceManager.amoled,
						onCheckedChange = { preferenceManager.amoled = it }
					)
				}
			}
		}

		item(key = "theme-cover-shape") {
			val currentShape = preferenceManager.coverArtShape
			val previewShape = when (currentShape) {
				CoverArtShape.Square -> RoundedCornerShape(0.dp)
				CoverArtShape.Soft -> RoundedCornerShape(8.dp)
				CoverArtShape.Curved -> RoundedCornerShape(14.dp)
				CoverArtShape.Circle -> CircleShape
			}

			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "封面圆角形状")
				MeloGroupedCard {
					Column(
						modifier = Modifier.padding(16.dp),
						verticalArrangement = Arrangement.spacedBy(12.dp)
					) {
						Row(
							modifier = Modifier.fillMaxWidth(),
							horizontalArrangement = Arrangement.SpaceBetween,
							verticalAlignment = Alignment.CenterVertically
						) {
							Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
								Text(
									text = "专辑与歌单封面圆角",
									style = MaterialTheme.typography.bodyMedium,
									fontWeight = FontWeight.Medium
								)
								Text(
									text = "当前：${SettingsCoverShapeLabel(currentShape)}",
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant
								)
							}
							Box(
								modifier = Modifier
									.size(36.dp)
									.clip(previewShape)
									.background(MaterialTheme.colorScheme.primaryContainer)
									.border(
										width = MeloHairlineWidth,
										color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
										shape = previewShape
									),
								contentAlignment = Alignment.Center
							) {
								Box(
									modifier = Modifier
										.size(12.dp)
										.clip(CircleShape)
										.background(MaterialTheme.colorScheme.primary)
								)
							}
						}

						Row(
							modifier = Modifier.fillMaxWidth(),
							horizontalArrangement = Arrangement.spacedBy(6.dp)
						) {
							CoverArtShape.entries.forEach { shape ->
								SettingsChoicePill(
									label = SettingsCoverShapeLabel(shape),
									selected = currentShape == shape,
									onClick = { preferenceManager.coverArtShape = shape },
									modifier = Modifier.weight(1f)
								)
							}
						}
					}
				}
			}
		}
	}
}

@Composable
private fun ThemePresetSwatchCard(
	descriptor: MeloStudioPresets.Descriptor,
	selected: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier
) {
	Surface(
		onClick = onClick,
		shape = RoundedCornerShape(14.dp),
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = BorderStroke(
			width = if (selected) 1.5.dp else MeloHairlineWidth,
			color = if (selected) {
				MaterialTheme.colorScheme.primary
			} else {
				MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
			}
		),
		modifier = modifier
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(12.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp)
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically
			) {
				// 双层画布 + 强调色预览条
				Row(
					modifier = Modifier
						.clip(RoundedCornerShape(8.dp))
						.background(descriptor.previewBackground)
						.border(
							width = MeloHairlineWidth,
							color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
							shape = RoundedCornerShape(8.dp)
						)
						.padding(horizontal = 6.dp, vertical = 5.dp),
					horizontalArrangement = Arrangement.spacedBy(5.dp),
					verticalAlignment = Alignment.CenterVertically
				) {
					Box(
						modifier = Modifier
							.size(width = 16.dp, height = 10.dp)
							.clip(RoundedCornerShape(3.dp))
							.background(descriptor.previewContainer)
					)
					Box(
						modifier = Modifier
							.size(10.dp)
							.clip(CircleShape)
							.background(descriptor.previewAccent)
					)
				}

				if (selected) {
					Box(
						modifier = Modifier
							.size(20.dp)
							.clip(CircleShape)
							.background(MaterialTheme.colorScheme.primary),
						contentAlignment = Alignment.Center
					) {
						Icon(
							imageVector = Icons.Outlined.Check,
							contentDescription = "已选中",
							tint = MaterialTheme.colorScheme.onPrimary,
							modifier = Modifier.size(13.dp)
						)
					}
				}
			}

			Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Text(
					text = descriptor.title,
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
					color = MaterialTheme.colorScheme.onSurface,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
				Text(
					text = descriptor.subtitle,
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
			}
		}
	}
}

// =============================================================================
// 7. 界面与播放页面板（MeloInterfaceGesturesSheet 内容）
// =============================================================================

@Composable
internal fun SettingsInterfaceGesturesPanel(
	modifier: Modifier = Modifier,
	nestedInSheet: Boolean = false
) {
	val preferenceManager = koinInject<PreferenceManager>()

	LazyColumn(
		modifier = modifier.background(MaterialTheme.colorScheme.background),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = 8.dp,
			bottom = panelBottomPadding(nestedInSheet)
		),
		verticalArrangement = Arrangement.spacedBy(18.dp)
	) {
		item(key = "interface-bottom-bar") {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "底部导航与悬浮播放舱")
				MeloGroupedCard {
					SettingsToggleRow(
						title = "下滑隐藏底栏",
						subtitle = "浏览列表向下滚动时自动收起底部导航坞",
						checked = preferenceManager.bottomBarCollapseMode == BottomBarCollapseMode.OnScroll,
						onCheckedChange = { enabled ->
							preferenceManager.bottomBarCollapseMode = if (enabled) {
								BottomBarCollapseMode.OnScroll
							} else {
								BottomBarCollapseMode.Never
							}
						}
					)
					MeloHairlineDivider(startIndent = 16.dp)
					SettingsToggleRow(
						title = "二级页显示播放栏",
						subtitle = "在专辑、歌手与搜索详情等子页面保留底部播放栏",
						checked = preferenceManager.bottomBarVisibilityMode == BottomBarVisibilityMode.AllScreens,
						onCheckedChange = { showAll ->
							preferenceManager.bottomBarVisibilityMode = if (showAll) {
								BottomBarVisibilityMode.AllScreens
							} else {
								BottomBarVisibilityMode.Default
							}
						}
					)
				}
			}
		}

		item(key = "interface-now-playing") {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "播放页舞台样式")
				MeloGroupedCard {
					Column(
						modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
						verticalArrangement = Arrangement.spacedBy(8.dp)
					) {
						Text(
							text = "播放页背景样式",
							style = MaterialTheme.typography.bodyMedium,
							fontWeight = FontWeight.Medium
						)
						Row(
							modifier = Modifier.fillMaxWidth(),
							horizontalArrangement = Arrangement.spacedBy(8.dp)
						) {
							NowPlayingBackgroundStyle.entries.forEach { style ->
								SettingsChoicePill(
									label = when (style) {
										NowPlayingBackgroundStyle.Dynamic -> "动态流光"
										NowPlayingBackgroundStyle.Static -> "静态极简"
									},
									selected = preferenceManager.nowPlayingBackgroundStyle == style,
									onClick = { preferenceManager.nowPlayingBackgroundStyle = style },
									modifier = Modifier.weight(1f)
								)
							}
						}
					}

					MeloHairlineDivider(startIndent = 16.dp)

					Column(
						modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
						verticalArrangement = Arrangement.spacedBy(8.dp)
					) {
						Text(
							text = "进度条样式",
							style = MaterialTheme.typography.bodyMedium,
							fontWeight = FontWeight.Medium
						)
						Row(
							modifier = Modifier.fillMaxWidth(),
							horizontalArrangement = Arrangement.spacedBy(6.dp)
						) {
							NowPlayingSliderStyle.entries.forEach { style ->
								SettingsChoicePill(
									label = when (style) {
										NowPlayingSliderStyle.Flat -> "直线"
										NowPlayingSliderStyle.Squiggly -> "波浪"
										NowPlayingSliderStyle.Slim -> "细线"
										NowPlayingSliderStyle.Yoyo -> "跳动"
									},
									selected = preferenceManager.nowPlayingSliderStyle == style,
									onClick = { preferenceManager.nowPlayingSliderStyle = style },
									modifier = Modifier.weight(1f)
								)
							}
						}
					}
				}
			}
		}
	}
}

// =============================================================================
// 8. 存储与缓存面板（MeloStorageCacheSheet 内容）
// =============================================================================

@Composable
internal fun SettingsStorageCachePanel(
	modifier: Modifier = Modifier,
	nestedInSheet: Boolean = false,
	onOpenPhoneDownloads: (() -> Unit)? = null,
	onCacheCleared: () -> Unit = {}
) {
	val streamCacheManager = koinInject<StreamCacheManager>()
	val storageManager = koinInject<StorageManager>()
	val downloadManager = koinInject<DownloadManager>()
	val preferenceManager = koinInject<PreferenceManager>()
	val imageLoader = koinInject<ImageLoader>()
	val snackBarState = LocalSnackBarState.current
	val scope = rememberCoroutineScope()
	val positiveColor = rememberThemePositiveColor()

	val downloadCount by downloadManager.downloadCount.collectAsStateWithLifecycle(0)
	val downloadSize by downloadManager.downloadSize.collectAsStateWithLifecycle(0L)
	val hasExternalStorage = remember { storageManager.isExternalStorageAvailable() }

	var streamCacheBytes by remember { mutableLongStateOf(0L) }
	var imageCacheBytes by remember { mutableLongStateOf(0L) }
	var freeSpaceBytes by remember { mutableLongStateOf(0L) }
	var loaded by remember { mutableStateOf(false) }
	var clearingCache by remember { mutableStateOf(false) }
	var cacheClearMessage by remember { mutableStateOf<String?>(null) }
	var pendingDownloadStorage by remember { mutableStateOf<DownloadStorage?>(null) }

	LaunchedEffect(Unit) {
		val (stream, image, free) = withContext(Dispatchers.IO) {
			Triple(
				streamCacheManager.size,
				imageLoader.diskCache?.size ?: 0L,
				storageManager.getCurrentFreeSpace()
			)
		}
		streamCacheBytes = stream
		imageCacheBytes = image
		freeSpaceBytes = free
		loaded = true
	}

	val tempCacheBytes = streamCacheBytes + imageCacheBytes

	LazyColumn(
		modifier = modifier.background(MaterialTheme.colorScheme.background),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = 8.dp,
			bottom = panelBottomPadding(nestedInSheet)
		),
		verticalArrangement = Arrangement.spacedBy(18.dp)
	) {
		// 1. 临时缓存清理
		item(key = "storage-temp-cache") {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "临时缓存")
				MeloGroupedCard {
					Column(
						modifier = Modifier.padding(16.dp),
						verticalArrangement = Arrangement.spacedBy(12.dp)
					) {
						Row(
							modifier = Modifier.fillMaxWidth(),
							horizontalArrangement = Arrangement.SpaceBetween,
							verticalAlignment = Alignment.CenterVertically
						) {
							Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
								Text(
									text = "可清理临时文件",
									style = MaterialTheme.typography.bodyMedium,
									fontWeight = FontWeight.Medium
								)
								Text(
									text = "含网络试听流缓存 (${if (loaded) streamCacheBytes.toFileSize() else "…"}) 与封面图缓存 (${if (loaded) imageCacheBytes.toFileSize() else "…"})",
									style = MaterialTheme.typography.bodySmall.tabularNums(),
									color = MaterialTheme.colorScheme.onSurfaceVariant
								)
							}
						}

						cacheClearMessage?.let { msg ->
							Text(
								text = msg,
								style = MaterialTheme.typography.bodySmall,
								color = positiveColor
							)
						}

						Row(
							modifier = Modifier.fillMaxWidth(),
							horizontalArrangement = Arrangement.SpaceBetween,
							verticalAlignment = Alignment.CenterVertically
						) {
							Text(
								text = if (loaded) tempCacheBytes.toFileSize() else "计算中…",
								style = MaterialTheme.typography.titleMedium.tabularNums(),
								fontWeight = FontWeight.SemiBold,
								color = MaterialTheme.colorScheme.onSurface
							)
							Button(
								onClick = {
									clearingCache = true
									cacheClearMessage = null
									scope.launch {
										withContext(Dispatchers.IO) {
											streamCacheManager.clear()
											imageLoader.memoryCache?.clear()
											imageLoader.diskCache?.clear()
											storageManager.clearCoverArts()
										}
										streamCacheBytes = 0L
										imageCacheBytes = 0L
										clearingCache = false
										cacheClearMessage = "已清理临时缓存，已下载离线歌曲不受影响"
										onCacheCleared()
										snackBarState.currentSnackbarData?.dismiss()
										snackBarState.showSnackbar("已清理临时缓存")
									}
								},
								enabled = loaded && tempCacheBytes > 0L && !clearingCache,
								shape = RoundedCornerShape(10.dp),
								contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
							) {
								if (clearingCache) {
									CircularProgressIndicator(
										modifier = Modifier.size(14.dp),
										strokeWidth = 2.dp,
										color = MaterialTheme.colorScheme.onPrimary
									)
									Spacer(Modifier.width(6.dp))
									Text("清理中…")
								} else {
									Text("一键清理")
								}
							}
						}
					}
				}
			}
		}

		// 2. 下载存储位置
		item(key = "storage-download-location") {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "下载存储位置")
				MeloGroupedCard {
					Column(
						modifier = Modifier.padding(16.dp),
						verticalArrangement = Arrangement.spacedBy(10.dp)
					) {
						Row(
							modifier = Modifier.fillMaxWidth(),
							horizontalArrangement = Arrangement.spacedBy(8.dp)
						) {
							DownloadStorage.entries.forEach { storage ->
								val enabled = storage != DownloadStorage.External || hasExternalStorage
								val selected = preferenceManager.downloadStorage == storage
								val label = when (storage) {
									DownloadStorage.Internal -> "手机内部存储"
									DownloadStorage.External -> if (hasExternalStorage) "外置 SD 卡" else "SD 卡（未挂载）"
								}
								Surface(
									onClick = {
										if (enabled && storage != preferenceManager.downloadStorage) {
											if (downloadCount > 0) {
												pendingDownloadStorage = storage
											} else {
												preferenceManager.downloadStorage = storage
											}
										}
									},
									enabled = enabled,
									shape = RoundedCornerShape(10.dp),
									color = when {
										selected -> MaterialTheme.colorScheme.primary
										enabled -> MaterialTheme.colorScheme.surfaceContainerHigh
										else -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f)
									},
									modifier = Modifier.weight(1f)
								) {
									Box(
										modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
										contentAlignment = Alignment.Center
									) {
										Text(
											text = label,
											style = MaterialTheme.typography.labelMedium,
											fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
											color = when {
												selected -> MaterialTheme.colorScheme.onPrimary
												enabled -> MaterialTheme.colorScheme.onSurface
												else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
											},
											maxLines = 1,
											overflow = TextOverflow.Ellipsis
										)
									}
								}
							}
						}

						Text(
							text = "已离线下载 $downloadCount 首 (${downloadSize.toFileSize()}) · 当前存储可用 ${if (loaded) freeSpaceBytes.toFileSize() else "…"}",
							style = MaterialTheme.typography.bodySmall.tabularNums(),
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
					}

					if (onOpenPhoneDownloads != null) {
						MeloHairlineDivider(startIndent = 16.dp)
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.clickable(onClick = onOpenPhoneDownloads)
								.padding(horizontal = 16.dp, vertical = 13.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(10.dp)
						) {
							Icon(
								imageVector = MeloIcons.PhoneAndroid,
								contentDescription = null,
								tint = MaterialTheme.colorScheme.primary,
								modifier = Modifier.size(18.dp)
							)
							Column(
								modifier = Modifier.weight(1f),
								verticalArrangement = Arrangement.spacedBy(1.dp)
							) {
								Text(
									text = "管理手机离线曲库",
									style = MaterialTheme.typography.bodyMedium,
									fontWeight = FontWeight.Medium
								)
								Text(
									text = "查看已下载歌曲、管理下载任务与本机音频",
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis
								)
							}
							Icon(
								imageVector = Icons.Outlined.ChevronForward,
								contentDescription = null,
								tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
								modifier = Modifier.size(17.dp)
							)
						}
					}
				}
			}
		}
	}

	pendingDownloadStorage?.let { target ->
		AlertDialog(
			onDismissRequest = { pendingDownloadStorage = null },
			title = { Text("更改下载存储位置") },
			text = { Text("当前已有 $downloadCount 首已下载歌曲，是否将现有离线文件一并迁移到新存储位置？") },
			confirmButton = {
				TextButton(
					onClick = {
						preferenceManager.downloadStorage = target
						downloadManager.migrateDownloads(target)
						pendingDownloadStorage = null
					}
				) {
					Text("迁移并切换")
				}
			},
			dismissButton = {
				Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
					TextButton(onClick = { pendingDownloadStorage = null }) {
						Text("取消")
					}
					TextButton(
						onClick = {
							preferenceManager.downloadStorage = target
							pendingDownloadStorage = null
						}
					) {
						Text("仅切换")
					}
				}
			}
		)
	}
}

// =============================================================================
// 9. 版本与更新面板（MeloVersionUpdateSheet 内容）
// =============================================================================

@Composable
internal fun SettingsVersionUpdatePanel(
	changelogViewModel: ChangelogViewModel,
	appVersionName: String,
	modifier: Modifier = Modifier,
	nestedInSheet: Boolean = false
) {
	val preferenceManager = koinInject<PreferenceManager>()
	val checkFeedback by changelogViewModel.checkFeedback.collectAsStateWithLifecycle()
	val availableRelease by changelogViewModel.release.collectAsStateWithLifecycle()
	val positiveColor = rememberThemePositiveColor()

	val isChecking = checkFeedback is UpdateCheckFeedback.Checking
	val updateTag = (checkFeedback as? UpdateCheckFeedback.UpdateAvailable)?.tag
		?: availableRelease?.tagName?.takeIf { it.isNotBlank() }
	val normalizedNewTag = updateTag?.let { "v" + it.trim().removePrefix("v").removePrefix("V") }

	LazyColumn(
		modifier = modifier.background(MaterialTheme.colorScheme.background),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = 8.dp,
			bottom = panelBottomPadding(nestedInSheet)
		),
		verticalArrangement = Arrangement.spacedBy(18.dp)
	) {
		item(key = "update-manual-check") {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "应用更新")
				MeloGroupedCard {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.clickable(enabled = !isChecking) {
								changelogViewModel.checkForUpdates(
									currentVersion = appVersionName,
									manual = true
								)
							}
							.padding(horizontal = 16.dp, vertical = 14.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(10.dp)
					) {
						Column(
							modifier = Modifier.weight(1f),
							verticalArrangement = Arrangement.spacedBy(2.dp)
						) {
							Text(
								text = "检查更新",
								style = MaterialTheme.typography.bodyMedium,
								fontWeight = FontWeight.Medium
							)
							Text(
								text = when (val fb = checkFeedback) {
									is UpdateCheckFeedback.Checking -> "正在向 GitHub Releases 查询最新版本…"
									is UpdateCheckFeedback.UpToDate -> "当前已是最新版本 (v$appVersionName)"
									is UpdateCheckFeedback.Error -> fb.message
									is UpdateCheckFeedback.UpdateAvailable -> "发现新版本 $normalizedNewTag，点击查看详情并安装"
									UpdateCheckFeedback.Idle -> if (normalizedNewTag != null) {
										"发现新版本 $normalizedNewTag，点击查看详情并安装"
									} else {
										"点击手动检测是否有新版本发布"
									}
								},
								style = MaterialTheme.typography.bodySmall,
								color = when (checkFeedback) {
									is UpdateCheckFeedback.Error -> MaterialTheme.colorScheme.error
									is UpdateCheckFeedback.UpdateAvailable -> MaterialTheme.colorScheme.primary
									is UpdateCheckFeedback.UpToDate -> positiveColor
									else -> if (normalizedNewTag != null) {
										MaterialTheme.colorScheme.primary
									} else {
										MaterialTheme.colorScheme.onSurfaceVariant
									}
								}
							)
						}

						when {
							isChecking -> CircularProgressIndicator(
								modifier = Modifier.size(16.dp),
								strokeWidth = 2.dp
							)
							normalizedNewTag != null -> {
								Box(
									modifier = Modifier
										.size(7.dp)
										.clip(CircleShape)
										.background(MaterialTheme.colorScheme.error)
								)
								Text(
									text = normalizedNewTag,
									style = MaterialTheme.typography.labelMedium.tabularNums(),
									fontWeight = FontWeight.SemiBold,
									color = MaterialTheme.colorScheme.primary
								)
							}
							else -> Icon(
								imageVector = Icons.Outlined.Refresh,
								contentDescription = null,
								tint = MaterialTheme.colorScheme.primary,
								modifier = Modifier.size(18.dp)
							)
						}
					}

					MeloHairlineDivider(startIndent = 16.dp)

					SettingsToggleRow(
						title = "自动检查更新",
						subtitle = "定期在后台静默检查新版本发布",
						checked = preferenceManager.checkForUpdates,
						onCheckedChange = { enabled ->
							preferenceManager.checkForUpdates = enabled
							if (enabled && preferenceManager.updateCheckFrequency == UpdateCheckFrequency.ManualOnly) {
								preferenceManager.updateCheckFrequency = UpdateCheckFrequency.Daily
							}
						}
					)

					AnimatedVisibility(visible = preferenceManager.checkForUpdates) {
						Column(
							modifier = Modifier
								.fillMaxWidth()
								.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
							verticalArrangement = Arrangement.spacedBy(8.dp)
						) {
							Text(
								text = "检查频率",
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant
							)
							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.spacedBy(6.dp)
							) {
								listOf(
									UpdateCheckFrequency.OnEveryLaunch to "每次启动",
									UpdateCheckFrequency.Daily to "每天",
									UpdateCheckFrequency.Weekly to "每周"
								).forEach { (freq, label) ->
									SettingsChoicePill(
										label = label,
										selected = preferenceManager.updateCheckFrequency == freq,
										onClick = { preferenceManager.updateCheckFrequency = freq },
										modifier = Modifier.weight(1f)
									)
								}
							}
						}
					}
				}
			}
		}

		item(key = "update-about-info") {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "版本信息")
				MeloGroupedCard {
					VersionInfoRow(label = "应用版本", value = "MeloKeep v$appVersionName")
					MeloHairlineDivider(startIndent = 16.dp)
					VersionInfoRow(
						label = "系统环境",
						value = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
					)
					BuildInfo.GIT_COMMIT_HASH?.takeIf { it.isNotBlank() }?.let { hash ->
						MeloHairlineDivider(startIndent = 16.dp)
						VersionInfoRow(label = "构建提交", value = hash.take(7))
					}
				}
			}
		}
	}
}

@Composable
private fun VersionInfoRow(
	label: String,
	value: String
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 13.dp),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.CenterVertically
	) {
		Text(
			text = label,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant
		)
		Text(
			text = value,
			style = MaterialTheme.typography.bodyMedium.tabularNums(),
			fontWeight = FontWeight.Medium,
			color = MaterialTheme.colorScheme.onSurface
		)
	}
}

// =============================================================================
// 10. 向后兼容旧面板导出（供外部 legacy Screen/Sheet 调用）
// =============================================================================

@Composable
internal fun SettingsLyricsAndStagePanel(
	modifier: Modifier = Modifier,
	nestedInSheet: Boolean = false
) {
	SettingsLyricsSettingsPanel(
		modifier = modifier,
		nestedInSheet = nestedInSheet
	)
}

@Composable
internal fun SettingsAudioEnginePanel(
	modifier: Modifier = Modifier,
	nestedInSheet: Boolean = false
) {
	SettingsPlaybackAndQualityPanel(
		modifier = modifier,
		nestedInSheet = nestedInSheet
	)
}

@Composable
internal fun SettingsStorageAndAboutPanel(
	modifier: Modifier = Modifier,
	nestedInSheet: Boolean = false
) {
	SettingsLibrarySyncPanel(
		modifier = modifier,
		nestedInSheet = nestedInSheet
	)
}

// =============================================================================
// 11. 硬件均衡器面板（无嵌套滚动的共享内容）
// =============================================================================

@Composable
internal fun SettingsEqualizerPanel(
	modifier: Modifier = Modifier,
	nestedInSheet: Boolean = false
) {
	LazyColumn(
		modifier = modifier.background(MaterialTheme.colorScheme.background),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = 8.dp,
			bottom = panelBottomPadding(nestedInSheet)
		),
		verticalArrangement = Arrangement.spacedBy(18.dp)
	) {
		item(key = "eq-content") {
			SettingsEqualizerContent()
		}
	}
}

@Composable
private fun SettingsEqualizerContent() {
	val equalizerManager = koinInject<EqualizerManager>()
	val config by equalizerManager.config.collectAsStateWithLifecycle()
	val scope = rememberCoroutineScope()

	Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
		Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
			MeloSectionLabel(
				title = "均衡器模式",
				trailing = if (config.mode == EqualizerMode.BuiltIn && config.bandLevels.isNotEmpty()) {
					{
						Row(
							modifier = Modifier
								.clip(RoundedCornerShape(6.dp))
								.clickable {
									scope.launch {
										equalizerManager.setConfig(config.copy(bandLevels = emptyMap()))
									}
								}
								.padding(horizontal = 4.dp, vertical = 2.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(4.dp)
						) {
							Icon(
								imageVector = Icons.Outlined.Refresh,
								contentDescription = null,
								tint = MaterialTheme.colorScheme.primary,
								modifier = Modifier.size(14.dp)
							)
							Text(
								text = "重置",
								style = MaterialTheme.typography.labelMedium,
								color = MaterialTheme.colorScheme.primary
							)
						}
					}
				} else {
					null
				}
			)

			MeloGroupedCard {
				Column(
					modifier = Modifier.padding(12.dp),
					verticalArrangement = Arrangement.spacedBy(8.dp)
				) {
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.spacedBy(6.dp)
					) {
						EqualizerMode.entries.forEach { mode ->
							SettingsChoicePill(
								label = when (mode) {
									EqualizerMode.BuiltIn -> "内置 EQ"
									EqualizerMode.External -> "系统音效"
									EqualizerMode.Disabled -> "关闭"
								},
								selected = config.mode == mode,
								onClick = {
									scope.launch {
										equalizerManager.setConfig(config.copy(mode = mode))
									}
								},
								modifier = Modifier.weight(1f)
							)
						}
					}
					Text(
						text = "「系统音效」会将音频会话接入手机系统声效（如杜比全景声/厂商外放增强），推荐默认开启；「内置 EQ」可在此微调频段；「关闭」则旁路直出。",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
				}
			}
		}

		if (config.mode == EqualizerMode.BuiltIn) {
			if (config.bandCount == 0) {
				MeloGroupedCard {
					MeloQuietEmptyState(
						message = "当前设备无可调频段，请在播放时重试"
					)
				}
			} else {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					MeloSectionLabel(title = "频段推子")
					MeloGroupedCard {
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.padding(16.dp),
							horizontalArrangement = Arrangement.SpaceBetween
						) {
							repeat(config.bandCount) { band ->
								val level = config.bandLevels[band] ?: 0f
								val range = config.bandLowerRange..config.bandUpperRange
								Column(
									modifier = Modifier
										.height(260.dp)
										.weight(1f),
									horizontalAlignment = Alignment.CenterHorizontally,
									verticalArrangement = Arrangement.spacedBy(6.dp)
								) {
									Text(
										text = "${(range.endInclusive / 100).toInt()}dB",
										style = MaterialTheme.typography.labelSmall.tabularNums(),
										color = MaterialTheme.colorScheme.onSurfaceVariant
									)
									VerticalSlider(
										modifier = Modifier.weight(1f),
										value = level,
										onValueChange = { newLevel ->
											val newLevels = config.bandLevels.toMutableMap().apply {
												set(band, newLevel)
											}
											scope.launch {
												equalizerManager.setConfig(config.copy(bandLevels = newLevels))
											}
										},
										valueRange = range
									)
									Text(
										text = "${(range.start / 100).toInt()}dB",
										style = MaterialTheme.typography.labelSmall.tabularNums(),
										color = MaterialTheme.colorScheme.onSurfaceVariant
									)
									Text(
										text = "B${band + 1}",
										style = MaterialTheme.typography.labelSmall,
										fontWeight = FontWeight.SemiBold,
										color = MaterialTheme.colorScheme.primary
									)
								}
							}
						}
					}
				}
			}
		}
	}
}

@Composable
private fun SettingsQualitySelector(
	title: String,
	current: StreamingQuality,
	onSelect: (StreamingQuality) -> Unit
) {
	Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		Text(
			text = title,
			style = MaterialTheme.typography.bodyMedium,
			fontWeight = FontWeight.Medium
		)
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(6.dp)
		) {
			StreamingQuality.entries.forEach { quality ->
				SettingsChoicePill(
					label = when (quality) {
						StreamingQuality.Low -> "80k"
						StreamingQuality.Medium -> "128k"
						StreamingQuality.High -> "192k"
						StreamingQuality.Lossless -> "无损"
					},
					selected = current == quality,
					onClick = { onSelect(quality) },
					modifier = Modifier.weight(1f)
				)
			}
		}
	}
}
