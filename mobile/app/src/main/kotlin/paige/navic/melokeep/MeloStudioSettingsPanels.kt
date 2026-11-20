/*
 * Navic / MeloKeep, a Subsonic music streaming & studio app for Android
 * Copyright (c) 2026 paige & MeloKeep Swarm
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.melokeep

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.generated.BuildInfo
import paige.navic.domain.manager.playback.AudioGainManager
import paige.navic.domain.manager.playback.EqualizerManager
import paige.navic.domain.manager.playback.StreamCacheManager
import paige.navic.domain.manager.runtime.PermissionManager
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.manager.runtime.StorageManager
import paige.navic.domain.manager.session.LoginManager
import paige.navic.domain.manager.session.SessionManager
import paige.navic.domain.manager.session.SessionState
import paige.navic.domain.manager.session.SyncManager
import paige.navic.domain.model.settings.EqualizerMode
import paige.navic.domain.model.settings.NowPlayingBackgroundStyle
import paige.navic.domain.model.settings.NowPlayingSliderStyle
import paige.navic.domain.model.settings.OfflineMode
import paige.navic.domain.model.settings.ReplayGainMode
import paige.navic.domain.model.settings.StreamingQuality
import paige.navic.domain.model.settings.UpdateCheckFrequency
import paige.navic.playback.exoplayer.ExoStateHolder
import paige.navic.ui.component.sheet.ChangelogViewModel
import paige.navic.ui.component.sheet.UpdateCheckFeedback
import paige.navic.ui.component.common.VerticalSlider
import paige.navic.ui.core.LoginUiState
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.Add
import paige.navic.ui.icons.outlined.ChevronForward
import paige.navic.ui.icons.outlined.Close
import paige.navic.ui.icons.outlined.Refresh
import paige.navic.ui.screen.lyrics.component.LyricsStyleCustomizationControls
import paige.navic.ui.theme.positive
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
				?.takeIf { it.isNotBlank() }
		}.getOrNull() ?: "dev"
	}
}

// =============================================================================
// 1. Navidrome 连接面板
// =============================================================================

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

	val sessionState by sessionManager.sessionState.collectAsStateWithLifecycle()
	val loginState by loginManager.loginState.collectAsStateWithLifecycle()
	val isConnected = sessionState !is SessionState.Unauthenticated
	val isBusy = loginState is LoginUiState.Loading || loginState is LoginUiState.Syncing

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
			bottom = if (nestedInSheet) 32.dp else 28.dp
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
									if (isConnected) MaterialTheme.colorScheme.positive
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
// 2. 歌词偏移与播放舞台面板
// =============================================================================

@Composable
internal fun SettingsLyricsAndStagePanel(
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
			bottom = if (nestedInSheet) 32.dp else 28.dp
		),
		verticalArrangement = Arrangement.spacedBy(18.dp)
	) {
		// 1. 歌词偏移（播放页 & 状态栏双轴独立调节）
		item(key = "lyrics-offsets") {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "歌词偏移")
				MeloGroupedCard {
					SettingsLyricsOffsetControl(
						title = "播放页歌词",
						offsetMs = preferenceManager.playerLyricsOffsetMs,
						onOffsetChange = { preferenceManager.playerLyricsOffsetMs = it },
						modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
					)

					MeloHairlineDivider(startIndent = 16.dp)

					SettingsToggleRow(
						title = "状态栏歌词",
						checked = preferenceManager.statusBarLyricsEnabled,
						onCheckedChange = { preferenceManager.statusBarLyricsEnabled = it }
					)

					SettingsLyricsOffsetControl(
						title = "状态栏偏移",
						offsetMs = preferenceManager.statusBarLyricsOffsetMs,
						onOffsetChange = { preferenceManager.statusBarLyricsOffsetMs = it },
						showSteppers = true,
						modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
					)
				}
			}
		}

		// 2. 歌词外观样式
		item(key = "lyrics-style") {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "歌词外观样式")
				MeloGroupedCard {
					LyricsStyleCustomizationControls(
						preferenceManager = preferenceManager,
						showResetButton = true,
						modifier = Modifier.padding(16.dp)
					)
				}
			}
		}

		// 3. 歌词显示
		item(key = "lyrics-display") {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "歌词显示")
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

		// 3. 播放页交互与样式
		item(key = "lyrics-stage") {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "播放页")
				MeloGroupedCard {
					SettingsToggleRow(
						title = "左右滑动切歌",
						checked = preferenceManager.swipeToSkip,
						onCheckedChange = { preferenceManager.swipeToSkip = it }
					)
					MeloHairlineDivider(startIndent = 16.dp)
					SettingsToggleRow(
						title = "显示音频参数",
						checked = preferenceManager.nowPlayingSongInfo,
						onCheckedChange = { preferenceManager.nowPlayingSongInfo = it }
					)
					MeloHairlineDivider(startIndent = 16.dp)
					Column(
						modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
						verticalArrangement = Arrangement.spacedBy(8.dp)
					) {
						Text(
							text = "背景风格",
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
										NowPlayingBackgroundStyle.Dynamic -> "动态"
										NowPlayingBackgroundStyle.Static -> "静态"
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
						modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
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
// 3. 音频解码与音质面板
// =============================================================================

@Composable
internal fun SettingsAudioEnginePanel(
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
			bottom = if (nestedInSheet) 32.dp else 28.dp
		),
		verticalArrangement = Arrangement.spacedBy(18.dp)
	) {
		// 1. 音质选择
		item(key = "audio-quality") {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "音质")
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
							title = "离线缓存",
							current = preferenceManager.downloadQuality,
							onSelect = { preferenceManager.downloadQuality = it }
						)
					}
				}
			}
		}

		// 2. 解码与播放
		item(key = "audio-decoder") {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "播放与输出")
				MeloGroupedCard {
					SettingsToggleRow(
						title = "无缝播放",
						checked = preferenceManager.gaplessPlayback,
						onCheckedChange = { preferenceManager.gaplessPlayback = it }
					)
					MeloHairlineDivider(startIndent = 16.dp)
					SettingsToggleRow(
						title = "32-bit 浮点输出",
						checked = preferenceManager.floatOutputEnabled,
						onCheckedChange = { preferenceManager.floatOutputEnabled = it }
					)
					MeloHairlineDivider(startIndent = 16.dp)
					SettingsToggleRow(
						title = "硬件直通 (Audio Offload)",
						checked = preferenceManager.audioOffload,
						onCheckedChange = { preferenceManager.audioOffload = it }
					)
					MeloHairlineDivider(startIndent = 16.dp)
					SettingsToggleRow(
						title = "队列播完自动续播",
						checked = preferenceManager.autoFillQueue,
						onCheckedChange = { preferenceManager.autoFillQueue = it }
					)
				}
			}
		}

		// 3. ReplayGain 音量平衡
		item(key = "audio-replaygain") {
			val rgAmpGain = preferenceManager.rgAmpGain
			val ampGain = preferenceManager.ampGain
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "ReplayGain")
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
	}
}

// =============================================================================
// 4. 存储、同步与关于面板
// =============================================================================

@Composable
internal fun SettingsStorageAndAboutPanel(
	modifier: Modifier = Modifier,
	nestedInSheet: Boolean = false
) {
	val storageManager = koinInject<StorageManager>()
	val streamCacheManager = koinInject<StreamCacheManager>()
	val syncManager = koinInject<SyncManager>()
	val imageLoader = koinInject<ImageLoader>()
	val preferenceManager = koinInject<PreferenceManager>()
	val activityOwner = LocalActivity.current as? ViewModelStoreOwner
	val changelogViewModel = if (activityOwner != null) {
		koinViewModel<ChangelogViewModel>(viewModelStoreOwner = activityOwner)
	} else {
		koinViewModel<ChangelogViewModel>()
	}
	val updateFeedback by changelogViewModel.checkFeedback.collectAsStateWithLifecycle()
	val appVersionName = rememberMeloVersionName()
	val scope = rememberCoroutineScope()

	val syncState by syncManager.syncState.collectAsStateWithLifecycle()

	var streamCacheSizeStr by remember { mutableStateOf("计算中…") }
	var imageCacheSizeStr by remember { mutableStateOf("计算中…") }
	var freeSpaceFormatted by remember { mutableStateOf("计算中…") }

	LaunchedEffect(Unit) {
		val (imgBytes, streamBytes, freeBytes) = withContext(Dispatchers.IO) {
			Triple(
				imageLoader.diskCache?.size ?: 0L,
				streamCacheManager.size,
				storageManager.getCurrentFreeSpace()
			)
		}
		imageCacheSizeStr = imgBytes.toFileSize()
		streamCacheSizeStr = streamBytes.toFileSize()
		freeSpaceFormatted = freeBytes.toFileSize()
	}

	val animatedSyncProgress by animateFloatAsState(
		targetValue = if (syncState.isSyncing) syncState.progress.coerceIn(0f, 1f) else 0f,
		animationSpec = tween(durationMillis = 300, easing = EaseOut)
	)

	LazyColumn(
		modifier = modifier.background(MaterialTheme.colorScheme.background),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = 8.dp,
			bottom = if (nestedInSheet) 32.dp else 28.dp
		),
		verticalArrangement = Arrangement.spacedBy(18.dp)
	) {
		// 1. 缓存与空间
		item(key = "storage-cache") {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "缓存")
				MeloGroupedCard {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(horizontal = 16.dp, vertical = 12.dp),
						horizontalArrangement = Arrangement.SpaceBetween,
						verticalAlignment = Alignment.CenterVertically
					) {
						Column(modifier = Modifier.weight(1f)) {
							Text("音频缓存", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
							Text(
								text = streamCacheSizeStr,
								style = MaterialTheme.typography.bodySmall.tabularNums(),
								color = MaterialTheme.colorScheme.onSurfaceVariant
							)
						}
						Text(
							text = "清理",
							style = MaterialTheme.typography.labelMedium,
							color = MaterialTheme.colorScheme.primary,
							modifier = Modifier
								.clip(RoundedCornerShape(8.dp))
								.clickable {
									scope.launch {
										withContext(Dispatchers.IO) {
											streamCacheManager.clear()
										}
										streamCacheSizeStr = "0 B"
									}
								}
								.padding(horizontal = 10.dp, vertical = 6.dp)
						)
					}

					MeloHairlineDivider(startIndent = 16.dp)

					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(horizontal = 16.dp, vertical = 12.dp),
						horizontalArrangement = Arrangement.SpaceBetween,
						verticalAlignment = Alignment.CenterVertically
					) {
						Column(modifier = Modifier.weight(1f)) {
							Text("封面缓存", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
							Text(
								text = imageCacheSizeStr,
								style = MaterialTheme.typography.bodySmall.tabularNums(),
								color = MaterialTheme.colorScheme.onSurfaceVariant
							)
						}
						Text(
							text = "清理",
							style = MaterialTheme.typography.labelMedium,
							color = MaterialTheme.colorScheme.primary,
							modifier = Modifier
								.clip(RoundedCornerShape(8.dp))
								.clickable {
									scope.launch {
										withContext(Dispatchers.IO) {
											imageLoader.memoryCache?.clear()
											imageLoader.diskCache?.clear()
											storageManager.clearCoverArts()
										}
										imageCacheSizeStr = "0 B"
									}
								}
								.padding(horizontal = 10.dp, vertical = 6.dp)
						)
					}

					MeloHairlineDivider(startIndent = 16.dp)

					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(horizontal = 16.dp, vertical = 13.dp),
						horizontalArrangement = Arrangement.SpaceBetween,
						verticalAlignment = Alignment.CenterVertically
					) {
						Text("设备剩余空间", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
						Text(freeSpaceFormatted, style = MaterialTheme.typography.bodyMedium.tabularNums(), fontWeight = FontWeight.Medium)
					}
				}
			}
		}

		// 2. 曲库同步
		item(key = "storage-sync") {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "曲库索引")
				MeloGroupedCard {
					Column(
						modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
						verticalArrangement = Arrangement.spacedBy(10.dp)
					) {
						Row(
							modifier = Modifier.fillMaxWidth(),
							horizontalArrangement = Arrangement.SpaceBetween,
							verticalAlignment = Alignment.CenterVertically
						) {
							Text(
								text = if (syncState.isSyncing) "正在同步元数据…" else "元数据索引已就绪",
								style = MaterialTheme.typography.bodyMedium,
								fontWeight = FontWeight.Medium,
								modifier = Modifier.weight(1f)
							)
							Surface(
								onClick = { syncManager.triggerManualSync() },
								enabled = !syncState.isSyncing,
								shape = RoundedCornerShape(10.dp),
								color = MaterialTheme.colorScheme.primary
							) {
								Text(
									text = if (syncState.isSyncing) "同步中…" else "全量同步",
									style = MaterialTheme.typography.labelMedium,
									color = MaterialTheme.colorScheme.onPrimary,
									modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
								)
							}
						}

						if (syncState.isSyncing) {
							LinearProgressIndicator(
								progress = { animatedSyncProgress },
								modifier = Modifier
									.fillMaxWidth()
									.height(3.dp)
									.clip(CircleShape),
								color = MaterialTheme.colorScheme.primary,
								trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
							)
						}
					}
				}
			}
		}

		// 3. 离线策略与更新
		item(key = "storage-offline") {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				MeloSectionLabel(title = "网络")
				MeloGroupedCard {
					Column(
						modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
						verticalArrangement = Arrangement.spacedBy(8.dp)
					) {
						Text(
							text = "离线模式",
							style = MaterialTheme.typography.bodyMedium,
							fontWeight = FontWeight.Medium
						)
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
					}

					if (!BuildInfo.FDROID) {
						MeloHairlineDivider(startIndent = 16.dp)

						SettingsToggleRow(
							title = "自动检查更新",
							checked = preferenceManager.checkForUpdates,
							onCheckedChange = { preferenceManager.checkForUpdates = it }
						)

						if (preferenceManager.checkForUpdates) {
							MeloHairlineDivider(startIndent = 16.dp)

							Column(
								modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
								verticalArrangement = Arrangement.spacedBy(8.dp)
							) {
								Text(
									text = "检查更新频率",
									style = MaterialTheme.typography.bodyMedium,
									fontWeight = FontWeight.Medium
								)
								Row(
									modifier = Modifier.fillMaxWidth(),
									horizontalArrangement = Arrangement.spacedBy(6.dp)
								) {
									UpdateCheckFrequency.entries.forEach { freq ->
										SettingsChoicePill(
											label = when (freq) {
												UpdateCheckFrequency.OnEveryLaunch -> "每次启动"
												UpdateCheckFrequency.Daily -> "每天"
												UpdateCheckFrequency.Weekly -> "每周"
												UpdateCheckFrequency.ManualOnly -> "仅手动"
											},
											selected = preferenceManager.updateCheckFrequency == freq,
											onClick = { preferenceManager.updateCheckFrequency = freq },
											modifier = Modifier.weight(1f)
										)
									}
								}
							}
						}

						MeloHairlineDivider(startIndent = 16.dp)

						Row(
							modifier = Modifier
								.fillMaxWidth()
								.padding(horizontal = 16.dp, vertical = 13.dp),
							horizontalArrangement = Arrangement.SpaceBetween,
							verticalAlignment = Alignment.CenterVertically
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
								val statusText = when (val fb = updateFeedback) {
									UpdateCheckFeedback.Checking -> "正在连接 GitHub Releases…"
									UpdateCheckFeedback.UpToDate -> "当前已是最新版本 v$appVersionName"
									is UpdateCheckFeedback.UpdateAvailable -> "发现新版本 ${fb.tag}"
									is UpdateCheckFeedback.Error -> fb.message
									UpdateCheckFeedback.Idle -> "当前版本 v$appVersionName"
								}
								Text(
									text = statusText,
									style = MaterialTheme.typography.bodySmall,
									color = when (updateFeedback) {
										is UpdateCheckFeedback.Error -> MaterialTheme.colorScheme.error
										is UpdateCheckFeedback.UpdateAvailable -> MaterialTheme.colorScheme.primary
										else -> MaterialTheme.colorScheme.onSurfaceVariant
									}
								)
							}
							Surface(
								onClick = {
									changelogViewModel.checkForUpdates(
										currentVersion = appVersionName,
										manual = true
									)
								},
								enabled = updateFeedback !is UpdateCheckFeedback.Checking,
								shape = RoundedCornerShape(10.dp),
								color = MaterialTheme.colorScheme.primaryContainer
							) {
								Text(
									text = if (updateFeedback is UpdateCheckFeedback.Checking) "检查中…" else "立即检查更新",
									style = MaterialTheme.typography.labelMedium,
									color = MaterialTheme.colorScheme.onPrimaryContainer,
									modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
								)
							}
						}
					}
				}
			}
		}

		// 4. 极简版本尾注
		item(key = "storage-version") {
			Text(
				text = "MeloKeep v$appVersionName",
				style = MaterialTheme.typography.labelSmall.tabularNums(),
				color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
				textAlign = TextAlign.Center,
				modifier = Modifier
					.fillMaxWidth()
					.padding(top = 4.dp)
			)
		}
	}
}

// =============================================================================
// 5. 硬件均衡器面板
// =============================================================================

@Composable
internal fun SettingsEqualizerPanel(
	modifier: Modifier = Modifier,
	nestedInSheet: Boolean = false
) {
	val equalizerManager = koinInject<EqualizerManager>()
	val config by equalizerManager.config.collectAsStateWithLifecycle()
	val scope = rememberCoroutineScope()

	LazyColumn(
		modifier = modifier.background(MaterialTheme.colorScheme.background),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = 8.dp,
			bottom = if (nestedInSheet) 32.dp else 28.dp
		),
		verticalArrangement = Arrangement.spacedBy(18.dp)
	) {
		item(key = "eq-mode") {
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
		}

		if (config.mode == EqualizerMode.BuiltIn) {
			if (config.bandCount == 0) {
				item(key = "eq-empty") {
					MeloGroupedCard {
						MeloQuietEmptyState(
							message = "当前设备无可调频段，请在播放时重试"
						)
					}
				}
			} else {
				item(key = "eq-bands") {
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
