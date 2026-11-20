@file:OptIn(ExperimentalMaterial3Api::class)

package paige.navic.melokeep

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.agonie0v0.melokeep.shared.MeloKeepApiClient
import io.github.agonie0v0.melokeep.shared.MeloSettingsController
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalSnackBarState
import paige.navic.domain.manager.runtime.PermissionManager
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.manager.session.LoginManager
import paige.navic.domain.manager.session.SessionManager
import paige.navic.domain.manager.session.SessionState
import paige.navic.domain.manager.session.SyncManager
import paige.navic.ui.core.LoginEvent
import paige.navic.ui.core.LoginUiState
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.Check
import paige.navic.ui.icons.outlined.KeyboardArrowDown
import paige.navic.ui.theme.positive
import paige.navic.ui.theme.tabularNums

/**
 * 统一双模连接引导页：支持单独或同时接入 MeloKeep 与 Navidrome。
 */
@Composable
fun MeloOnboardingScreen(
	onFinished: () -> Unit = {}
) {
	val backStack = LocalNavStack.current
	val snackBarState = LocalSnackBarState.current
	val focusManager = LocalFocusManager.current
	val scope = rememberCoroutineScope()

	val meloSettingsStore = koinInject<MeloSettingsStore>()
	val meloSettingsController = koinInject<MeloSettingsController>()
	val apiClient = koinInject<MeloKeepApiClient>()
	val sessionManager = koinInject<SessionManager>()
	val loginManager = koinInject<LoginManager>()
	val syncManager = koinInject<SyncManager>()
	val permissionManager = koinInject<PermissionManager>()
	val preferenceManager = koinInject<PreferenceManager>()

	val meloConfig by meloSettingsStore.config.collectAsStateWithLifecycle()
	val settingsState by meloSettingsController.state.collectAsStateWithLifecycle()
	val sessionState by sessionManager.sessionState.collectAsStateWithLifecycle()
	val navidromeLoginState by loginManager.loginState.collectAsStateWithLifecycle()

	val canNavigateBack = backStack.size > 1

	var meloDisplayName by rememberSaveable { mutableStateOf(meloConfig.displayName) }
	var meloUrl by rememberSaveable { mutableStateOf(meloConfig.baseUrl) }
	var meloUser by rememberSaveable { mutableStateOf(meloConfig.username) }
	var meloPass by rememberSaveable { mutableStateOf("") }
	var meloPassVisible by rememberSaveable { mutableStateOf(false) }

	var probingHealth by rememberSaveable { mutableStateOf(false) }
	var healthStatusMessage by rememberSaveable { mutableStateOf<String?>(null) }
	var healthStatusSuccess by rememberSaveable { mutableStateOf(false) }
	var enteringApp by rememberSaveable { mutableStateOf(false) }
	var enterErrorMessage by rememberSaveable { mutableStateOf<String?>(null) }
	var showSkipAnyway by rememberSaveable { mutableStateOf(false) }

	var navidromeExpanded by rememberSaveable {
		mutableStateOf(sessionState !is SessionState.Unauthenticated)
	}
	var navidromeServerName by rememberSaveable { mutableStateOf(preferenceManager.navidromeServerName) }
	var navidromeUrl by rememberSaveable { mutableStateOf(sessionManager.instanceUrl) }
	var navidromeUser by rememberSaveable { mutableStateOf(sessionManager.username) }
	var navidromePass by rememberSaveable { mutableStateOf("") }
	var navidromePassVisible by rememberSaveable { mutableStateOf(false) }
	var navidromeLocalError by rememberSaveable { mutableStateOf<String?>(null) }

	val deviceName = remember {
		listOf(Build.MANUFACTURER, Build.MODEL)
			.map { it.trim() }
			.filter { it.isNotEmpty() }
			.distinct()
			.joinToString(" ")
			.ifBlank { "MeloKeep Android" }
	}

	MeloFeedbackObserver(
		errorMessage = settingsState.errorMessage,
		noticeMessage = settingsState.noticeMessage,
		onClearError = meloSettingsController::clearError,
		onClearNotice = meloSettingsController::clearNotice
	)

	LaunchedEffect(Unit) {
		loginManager.events.collect { event ->
			when (event) {
				LoginEvent.InvalidCredentials -> {
					navidromeLocalError = "Navidrome 用户名或密码不正确"
				}
				is LoginEvent.ValidationFailed -> {
					navidromeLocalError = "请完整填写 Navidrome 服务器地址、用户名和密码"
				}
				LoginEvent.Success -> {
					navidromeLocalError = null
				}
			}
		}
	}

	val isNavidromeConnected = sessionState !is SessionState.Unauthenticated
	val isNavidromeBusy = navidromeLoginState is LoginUiState.Loading ||
		navidromeLoginState is LoginUiState.Syncing

	val finishOnboarding: () -> Unit = {
		meloSettingsController.updateDisplayName(meloDisplayName.trim())
		preferenceManager.navidromeServerName = navidromeServerName.trim()
		meloSettingsStore.setOnboardingDismissed(true)
		onFinished()
	}

	val verifyAndFinishOnboarding: () -> Unit = {
		focusManager.clearFocus()
		enterErrorMessage = null
		meloSettingsController.updateDisplayName(meloDisplayName.trim())
		preferenceManager.navidromeServerName = navidromeServerName.trim()

		val trimmedMeloUrl = meloUrl.trim()
		val normalizedInputMelo = MeloSettingsStore.normalizeBaseUrl(trimmedMeloUrl)
		val meloAlreadyMatched = meloConfig.isAuthenticated &&
			MeloSettingsStore.normalizeBaseUrl(meloConfig.baseUrl) == normalizedInputMelo &&
			meloPass.isBlank() &&
			(meloUser.trim() == meloConfig.username || !meloConfig.authEnabled)
		val shouldConnectMelo = trimmedMeloUrl.isNotEmpty() && !meloAlreadyMatched

		val trimmedNavUrl = navidromeUrl.trim().removeSuffix("/")
		val normalizedNavUrl = when {
			trimmedNavUrl.startsWith("https://") || trimmedNavUrl.startsWith("http://") -> trimmedNavUrl
			trimmedNavUrl.isNotEmpty() -> "https://$trimmedNavUrl"
			else -> ""
		}
		val hasNavCreds = navidromeUrl.isNotBlank() && navidromeUser.isNotBlank() && navidromePass.isNotBlank()
		val partialNavCreds = (navidromeUser.isNotBlank() || navidromePass.isNotBlank()) && !hasNavCreds
		val navAlreadyMatched = isNavidromeConnected &&
			sessionManager.instanceUrl.trim().removeSuffix("/") == normalizedNavUrl &&
			sessionManager.username == navidromeUser.trim()
		val shouldConnectNav = hasNavCreds && !navAlreadyMatched

		when {
			partialNavCreds -> {
				val err = "请完整填写 Navidrome 服务器地址、账号和密码"
				navidromeLocalError = err
				enterErrorMessage = err
				showSkipAnyway = true
			}
			!shouldConnectMelo && !shouldConnectNav -> finishOnboarding()
			else -> {
				enteringApp = true
				showSkipAnyway = false
				scope.launch {
					var meloConnectedOk = !shouldConnectMelo
					try {
						if (shouldConnectMelo) {
							val health = apiClient.health(trimmedMeloUrl)
							if (!health.ok) {
								throw IllegalStateException("MeloKeep 服务状态异常")
							}
							apiClient.login(
								baseUrl = trimmedMeloUrl,
								username = meloUser.trim(),
								password = meloPass,
								deviceName = deviceName,
								displayName = meloDisplayName.trim()
							)
							meloSettingsController.loadConfig()
							meloConnectedOk = true
						}
						if (shouldConnectNav) {
							loginManager.instanceState.edit { replace(0, length, trimmedNavUrl) }
							loginManager.usernameState.edit { replace(0, length, navidromeUser.trim()) }
							loginManager.passwordState.edit { replace(0, length, navidromePass) }
							if (loginManager.isLocalNetworkInstance() && !permissionManager.requestLocalNetworkPermission()) {
								throw IllegalStateException("请授予局域网访问权限后再连接 Navidrome")
							}
							permissionManager.requestNotificationsPermission()
							sessionManager.login(normalizedNavUrl, navidromeUser.trim(), navidromePass)
							syncManager.startPeriodicSync(syncImmediately = true)
						}
						enteringApp = false
						finishOnboarding()
					} catch (e: CancellationException) {
						throw e
					} catch (e: Exception) {
						enteringApp = false
						val msg = e.message?.takeIf { it.isNotBlank() } ?: "连接校验失败，请检查服务器地址、账号密码或网络"
						enterErrorMessage = msg
						if (!meloConnectedOk) {
							healthStatusSuccess = false
							healthStatusMessage = msg
						} else if (shouldConnectNav) {
							navidromeLocalError = msg
						}
						showSkipAnyway = true
					}
				}
			}
		}
	}

	Column(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colorScheme.background)
			.windowInsetsPadding(WindowInsets.navigationBars)
			.imePadding()
	) {
		MeloCleanTopBar(
			title = "连接服务",
			onBack = if (canNavigateBack) {
				{ backStack.removeLastOrNull() }
			} else {
				null
			},
			applyStatusBarPadding = true,
			trailing = {
				TextButton(
					onClick = finishOnboarding,
					contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
				) {
					Text(
						text = "稍后",
						style = MaterialTheme.typography.labelLarge,
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
				}
			}
		)

		Column(
			modifier = Modifier
				.weight(1f)
				.verticalScroll(rememberScrollState())
				.padding(horizontal = 16.dp, vertical = 8.dp),
			horizontalAlignment = Alignment.CenterHorizontally
		) {
			Column(
				modifier = Modifier
					.widthIn(max = 520.dp)
					.fillMaxWidth(),
				verticalArrangement = Arrangement.spacedBy(20.dp)
			) {
				// 1. MeloKeep 核心服务
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					MeloSectionLabel(
						title = "MeloKeep",
						trailing = {
							OnboardingStatusLabel(
								connected = meloConfig.isAuthenticated,
								label = if (meloConfig.isAuthenticated) "已连接" else "未连接"
							)
						}
					)

					MeloGroupedCard {
						Column(
							modifier = Modifier.padding(16.dp),
							verticalArrangement = Arrangement.spacedBy(10.dp)
						) {
							if (meloConfig.isAuthenticated) {
								Text(
									text = if (meloConfig.authEnabled && meloConfig.username.isNotBlank()) {
										"${meloConfig.baseUrl} · ${meloConfig.username}"
									} else {
										"${meloConfig.baseUrl} · 免密"
									},
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.positive,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis
								)
							}

							OutlinedTextField(
								modifier = Modifier.fillMaxWidth(),
								value = meloDisplayName,
								onValueChange = {
									meloDisplayName = it
									meloSettingsController.updateDisplayName(it.trim())
								},
								singleLine = true,
								shape = RoundedCornerShape(12.dp),
								label = { Text("服务名称（可选，如 家里 NAS）", maxLines = 1, overflow = TextOverflow.Ellipsis) },
								placeholder = { Text("默认显示 MeloKeep", maxLines = 1, overflow = TextOverflow.Ellipsis) },
								keyboardOptions = KeyboardOptions(
									autoCorrectEnabled = false,
									imeAction = ImeAction.Next
								)
							)

							OutlinedTextField(
								modifier = Modifier.fillMaxWidth(),
								value = meloUrl,
								onValueChange = {
									meloUrl = it
									healthStatusMessage = null
								},
								singleLine = true,
								shape = RoundedCornerShape(12.dp),
								label = { Text("服务器地址", maxLines = 1, overflow = TextOverflow.Ellipsis) },
								placeholder = { Text("http://192.168.1.10:8765", maxLines = 1, overflow = TextOverflow.Ellipsis) },
								keyboardOptions = KeyboardOptions(
									autoCorrectEnabled = false,
									keyboardType = KeyboardType.Uri,
									imeAction = ImeAction.Next
								)
							)

							val quickEndpoints = remember {
								listOf(
									"http://10.0.2.2:8765" to "10.0.2.2",
									"http://127.0.0.1:8765" to "127.0.0.1",
									"http://192.168." to "192.168.*"
								)
							}
							Row(
								modifier = Modifier
									.fillMaxWidth()
									.horizontalScroll(rememberScrollState()),
								horizontalArrangement = Arrangement.spacedBy(6.dp),
								verticalAlignment = Alignment.CenterVertically
							) {
								quickEndpoints.forEach { (endpoint, label) ->
									val selected = if (endpoint == "http://192.168.") {
										meloUrl.trim().startsWith(endpoint)
									} else {
										meloUrl.trim() == endpoint
									}
									Surface(
										shape = CircleShape,
										color = if (selected) {
											MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
										} else {
											MaterialTheme.colorScheme.surfaceContainerHigh
										},
										border = BorderStroke(
											width = MeloHairlineWidth,
											color = if (selected) {
												MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
											} else {
												MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
											}
										),
										modifier = Modifier
											.clip(CircleShape)
											.clickable {
												meloUrl = endpoint
												healthStatusMessage = null
											}
									) {
										Text(
											text = label,
											style = MaterialTheme.typography.labelSmall.tabularNums(),
											fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
											color = if (selected) {
												MaterialTheme.colorScheme.primary
											} else {
												MaterialTheme.colorScheme.onSurfaceVariant
											},
											maxLines = 1,
											modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
										)
									}
								}
							}

							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.spacedBy(10.dp),
								verticalAlignment = Alignment.Top
							) {
								OutlinedTextField(
									modifier = Modifier.weight(1f),
									value = meloUser,
									onValueChange = { meloUser = it },
									singleLine = true,
									shape = RoundedCornerShape(12.dp),
									label = { Text("用户名", maxLines = 1, overflow = TextOverflow.Ellipsis) },
									placeholder = { Text("可选", maxLines = 1, overflow = TextOverflow.Ellipsis) },
									keyboardOptions = KeyboardOptions(
										autoCorrectEnabled = false,
										imeAction = ImeAction.Next
									)
								)
								OutlinedTextField(
									modifier = Modifier.weight(1f),
									value = meloPass,
									onValueChange = { meloPass = it },
									singleLine = true,
									shape = RoundedCornerShape(12.dp),
									label = { Text("密码", maxLines = 1, overflow = TextOverflow.Ellipsis) },
									placeholder = { Text("可选", maxLines = 1, overflow = TextOverflow.Ellipsis) },
									visualTransformation = if (meloPassVisible) {
										VisualTransformation.None
									} else {
										PasswordVisualTransformation()
									},
									trailingIcon = {
										IconButton(onClick = { meloPassVisible = !meloPassVisible }) {
											Icon(
												imageVector = if (meloPassVisible) MeloIcons.VisibilityOff else MeloIcons.Visibility,
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
											if (meloUrl.isNotBlank()) {
												meloSettingsController.testAndLogin(
													baseUrl = meloUrl,
													username = meloUser,
													password = meloPass,
													deviceName = deviceName,
													displayName = meloDisplayName.trim()
												)
											}
										}
									)
								)
							}

							AnimatedVisibility(visible = !healthStatusMessage.isNullOrBlank()) {
								Text(
									text = healthStatusMessage.orEmpty(),
									style = MaterialTheme.typography.bodySmall,
									color = if (healthStatusSuccess) {
										MaterialTheme.colorScheme.positive
									} else {
										MaterialTheme.colorScheme.error
									}
								)
							}

							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.spacedBy(10.dp)
							) {
								val probeEnabled = !probingHealth && meloUrl.isNotBlank()
								OutlinedButton(
									modifier = Modifier.weight(0.42f),
									enabled = probeEnabled,
									shape = RoundedCornerShape(12.dp),
									contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
									border = BorderStroke(
										width = MeloHairlineWidth,
										color = if (probeEnabled) {
											MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
										} else {
											MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
										}
									),
									onClick = {
										focusManager.clearFocus()
										probingHealth = true
										healthStatusMessage = null
										scope.launch {
											runCatching { apiClient.health(meloUrl) }
												.onSuccess { info ->
													probingHealth = false
													healthStatusSuccess = info.ok
													healthStatusMessage = if (info.ok) {
														"在线 · ${info.version.ifBlank { info.backend }} · ${info.sources} 音源"
													} else {
														"服务返回异常状态"
													}
												}
												.onFailure { err ->
													probingHealth = false
													healthStatusSuccess = false
													healthStatusMessage = "探测失败：${err.message ?: "无法访问"}"
												}
										}
									}
								) {
									if (probingHealth) {
										CircularProgressIndicator(
											modifier = Modifier.size(14.dp),
											strokeWidth = 2.dp
										)
										Spacer(Modifier.width(6.dp))
									}
									Text("探测", maxLines = 1, overflow = TextOverflow.Ellipsis)
								}

								Button(
									modifier = Modifier.weight(0.58f),
									shape = RoundedCornerShape(12.dp),
									enabled = !settingsState.testingConnection && meloUrl.isNotBlank(),
									onClick = {
										focusManager.clearFocus()
										meloSettingsController.testAndLogin(
											baseUrl = meloUrl,
											username = meloUser,
											password = meloPass,
											deviceName = deviceName,
											displayName = meloDisplayName.trim()
										)
									}
								) {
									if (settingsState.testingConnection) {
										CircularProgressIndicator(
											modifier = Modifier.size(16.dp),
											strokeWidth = 2.dp,
											color = MaterialTheme.colorScheme.onPrimary
										)
										Spacer(Modifier.width(8.dp))
										Text("连接中…")
									} else {
										Text(if (meloConfig.isAuthenticated) "重新认证" else "连接并登录")
									}
								}
							}
						}
					}
				}

				// 2. Navidrome（可选）
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					MeloSectionLabel(
						title = "Navidrome（可选）",
						trailing = {
							OnboardingStatusLabel(
								connected = isNavidromeConnected,
								label = if (isNavidromeConnected) "已连接" else "未关联"
							)
						}
					)

					MeloGroupedCard {
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.clickable { navidromeExpanded = !navidromeExpanded }
								.padding(horizontal = 16.dp, vertical = 13.dp),
							horizontalArrangement = Arrangement.SpaceBetween,
							verticalAlignment = Alignment.CenterVertically
						) {
							Text(
								text = when (sessionState) {
									is SessionState.Authenticated ->
										"${sessionManager.username} · ${sessionManager.instanceUrl}"
									is SessionState.Restoring -> "正在恢复连接…"
									SessionState.Unauthenticated -> "关联 Subsonic / Navidrome 曲库"
								},
								style = MaterialTheme.typography.bodyMedium,
								color = if (isNavidromeConnected) {
									MaterialTheme.colorScheme.onSurface
								} else {
									MaterialTheme.colorScheme.onSurfaceVariant
								},
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
								modifier = Modifier.weight(1f)
							)
							Spacer(Modifier.width(8.dp))
							Icon(
								imageVector = if (isNavidromeConnected && !navidromeExpanded) {
									Icons.Outlined.Check
								} else {
									Icons.Outlined.KeyboardArrowDown
								},
								contentDescription = null,
								tint = if (isNavidromeConnected) {
									MaterialTheme.colorScheme.positive
								} else {
									MaterialTheme.colorScheme.onSurfaceVariant
								},
								modifier = Modifier.size(18.dp)
							)
						}

						AnimatedVisibility(
							visible = navidromeExpanded,
							enter = fadeIn() + expandVertically(),
							exit = fadeOut() + shrinkVertically()
						) {
							Column {
								MeloHairlineDivider(startIndent = 16.dp)
								Column(
									modifier = Modifier.padding(16.dp),
									verticalArrangement = Arrangement.spacedBy(10.dp)
								) {
									Row(
										modifier = Modifier.fillMaxWidth(),
										horizontalArrangement = Arrangement.End
									) {
										Text(
											text = "同机填充 :4533",
											style = MaterialTheme.typography.labelMedium,
											color = MaterialTheme.colorScheme.primary,
											modifier = Modifier
												.clip(RoundedCornerShape(6.dp))
												.clickable {
													val sourceUrl = meloUrl.ifBlank { meloConfig.baseUrl }
													val derived = deriveSameHostPort4533(sourceUrl)
													if (derived != null) {
														navidromeUrl = derived
														navidromeLocalError = null
													} else {
														scope.launch {
															snackBarState.currentSnackbarData?.dismiss()
															snackBarState.showSnackbar("请先填写有效的 MeloKeep 地址")
														}
													}
												}
												.padding(horizontal = 6.dp, vertical = 2.dp)
										)
									}

									OutlinedTextField(
										modifier = Modifier.fillMaxWidth(),
										value = navidromeServerName,
										onValueChange = {
											navidromeServerName = it
											preferenceManager.navidromeServerName = it.trim()
										},
										singleLine = true,
										enabled = !isNavidromeBusy,
										shape = RoundedCornerShape(12.dp),
										label = { Text("服务名称（可选）", maxLines = 1, overflow = TextOverflow.Ellipsis) },
										placeholder = { Text("默认显示 Navidrome", maxLines = 1, overflow = TextOverflow.Ellipsis) },
										keyboardOptions = KeyboardOptions(
											autoCorrectEnabled = false,
											imeAction = ImeAction.Next
										)
									)

									OutlinedTextField(
										modifier = Modifier.fillMaxWidth(),
										value = navidromeUrl,
										onValueChange = {
											navidromeUrl = it
											navidromeLocalError = null
										},
										singleLine = true,
										enabled = !isNavidromeBusy,
										shape = RoundedCornerShape(12.dp),
										label = { Text("服务器地址", maxLines = 1, overflow = TextOverflow.Ellipsis) },
										placeholder = { Text("http://192.168.1.10:4533", maxLines = 1, overflow = TextOverflow.Ellipsis) },
										keyboardOptions = KeyboardOptions(
											autoCorrectEnabled = false,
											keyboardType = KeyboardType.Uri,
											imeAction = ImeAction.Next
										)
									)

									Row(
										modifier = Modifier.fillMaxWidth(),
										horizontalArrangement = Arrangement.spacedBy(10.dp),
										verticalAlignment = Alignment.Top
									) {
										OutlinedTextField(
											modifier = Modifier.weight(1f),
											value = navidromeUser,
											onValueChange = {
												navidromeUser = it
												navidromeLocalError = null
											},
											singleLine = true,
											enabled = !isNavidromeBusy,
											shape = RoundedCornerShape(12.dp),
											label = { Text("账号", maxLines = 1, overflow = TextOverflow.Ellipsis) },
											keyboardOptions = KeyboardOptions(
												autoCorrectEnabled = false,
												imeAction = ImeAction.Next
											)
										)
										OutlinedTextField(
											modifier = Modifier.weight(1f),
											value = navidromePass,
											onValueChange = {
												navidromePass = it
												navidromeLocalError = null
											},
											singleLine = true,
											enabled = !isNavidromeBusy,
											shape = RoundedCornerShape(12.dp),
											label = { Text("密码", maxLines = 1, overflow = TextOverflow.Ellipsis) },
											visualTransformation = if (navidromePassVisible) {
												VisualTransformation.None
											} else {
												PasswordVisualTransformation()
											},
											trailingIcon = {
												IconButton(onClick = { navidromePassVisible = !navidromePassVisible }) {
													Icon(
														imageVector = if (navidromePassVisible) MeloIcons.VisibilityOff else MeloIcons.Visibility,
														contentDescription = "切换密码可见性"
													)
												}
											},
											keyboardOptions = KeyboardOptions(
												autoCorrectEnabled = false,
												keyboardType = KeyboardType.Password,
												imeAction = ImeAction.Done
											)
										)
									}

									val errMsg = navidromeLocalError ?: (navidromeLoginState as? LoginUiState.Error)
										?.error?.localizedMessage
									if (!errMsg.isNullOrBlank()) {
										Text(
											text = errMsg,
											style = MaterialTheme.typography.bodySmall,
											color = MaterialTheme.colorScheme.error
										)
									}

									if (navidromeLoginState is LoginUiState.Syncing) {
										val syncing = navidromeLoginState as LoginUiState.Syncing
										Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
											LinearProgressIndicator(
												progress = { syncing.progress.coerceIn(0f, 1f) },
												modifier = Modifier
													.fillMaxWidth()
													.height(3.dp)
													.clip(CircleShape)
											)
											Text(
												text = "同步中 ${(syncing.progress * 100).toInt()}%",
												style = MaterialTheme.typography.labelSmall.tabularNums(),
												color = MaterialTheme.colorScheme.onSurfaceVariant
											)
										}
									}

									OutlinedButton(
										modifier = Modifier.fillMaxWidth(),
										shape = RoundedCornerShape(12.dp),
										border = BorderStroke(
											MeloHairlineWidth,
											MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
										),
										enabled = !isNavidromeBusy &&
											navidromeUrl.isNotBlank() &&
											navidromeUser.isNotBlank() &&
											navidromePass.isNotBlank(),
										onClick = {
											focusManager.clearFocus()
											navidromeLocalError = null
											preferenceManager.navidromeServerName = navidromeServerName.trim()
											scope.launch {
												loginManager.instanceState.edit {
													replace(0, length, navidromeUrl.trim())
												}
												loginManager.usernameState.edit {
													replace(0, length, navidromeUser.trim())
												}
												loginManager.passwordState.edit {
													replace(0, length, navidromePass)
												}
												if (loginManager.isLocalNetworkInstance()) {
													if (!permissionManager.requestLocalNetworkPermission()) {
														navidromeLocalError = "请授予局域网访问权限"
														return@launch
													}
												}
												permissionManager.requestNotificationsPermission()
												loginManager.login()
											}
										}
									) {
										if (isNavidromeBusy) {
											CircularProgressIndicator(
												modifier = Modifier.size(16.dp),
												strokeWidth = 2.dp
											)
											Spacer(Modifier.width(8.dp))
											Text("验证并同步…")
										} else {
											Text(if (isNavidromeConnected) "重新同步" else "连接 Navidrome")
										}
									}
								}
							}
						}
					}
				}

				Spacer(Modifier.height(12.dp))
			}
		}

		// 底部完成按钮
		MeloHairlineDivider()
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 12.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(8.dp)
		) {
			AnimatedVisibility(visible = !enterErrorMessage.isNullOrBlank()) {
				Surface(
					modifier = Modifier
						.widthIn(max = 520.dp)
						.fillMaxWidth(),
					shape = RoundedCornerShape(10.dp),
					color = MaterialTheme.colorScheme.errorContainer,
					border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.55f))
				) {
					Text(
						text = enterErrorMessage.orEmpty(),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onErrorContainer,
						modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
					)
				}
			}

			Row(
				modifier = Modifier
					.widthIn(max = 520.dp)
					.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(10.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				if (showSkipAnyway) {
					OutlinedButton(
						onClick = finishOnboarding,
						enabled = !enteringApp,
						shape = RoundedCornerShape(12.dp),
						modifier = Modifier
							.weight(0.36f)
							.height(48.dp)
					) {
						Text(
							text = "仍要进入",
							style = MaterialTheme.typography.titleSmall,
							fontWeight = FontWeight.Medium
						)
					}
				}

				Button(
					onClick = verifyAndFinishOnboarding,
					enabled = !enteringApp,
					shape = RoundedCornerShape(12.dp),
					modifier = Modifier
						.weight(if (showSkipAnyway) 0.64f else 1f)
						.height(48.dp)
				) {
					if (enteringApp) {
						CircularProgressIndicator(
							modifier = Modifier.size(16.dp),
							strokeWidth = 2.dp,
							color = MaterialTheme.colorScheme.onPrimary
						)
						Spacer(Modifier.width(8.dp))
						Text(
							text = "正在保存并校验…",
							style = MaterialTheme.typography.titleSmall,
							fontWeight = FontWeight.SemiBold
						)
					} else {
						Text(
							text = "进入应用",
							style = MaterialTheme.typography.titleSmall,
							fontWeight = FontWeight.SemiBold
						)
					}
				}
			}
		}
	}
}

@Composable
private fun OnboardingStatusLabel(
	connected: Boolean,
	label: String
) {
	Row(
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(5.dp)
	) {
		Box(
			modifier = Modifier
				.size(6.dp)
				.clip(CircleShape)
				.background(
					if (connected) MaterialTheme.colorScheme.positive
					else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
				)
		)
		Text(
			text = label,
			style = MaterialTheme.typography.labelSmall,
			color = if (connected) MaterialTheme.colorScheme.positive else MaterialTheme.colorScheme.onSurfaceVariant
		)
	}
}

/**
 * 从 MeloKeep 服务地址派生同机 `:4533` 的 Navidrome 地址。
 */
internal fun deriveSameHostPort4533(rawUrl: String): String? {
	val trimmed = rawUrl.trim()
	if (trimmed.isEmpty()) return null
	val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
	val uri = runCatching { withScheme.toUri() }.getOrNull() ?: return null
	val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
	val scheme = uri.scheme?.takeIf { it.isNotBlank() } ?: "http"
	val formattedHost = if (":" in host && !host.startsWith("[")) "[$host]" else host
	return "$scheme://$formattedHost:4533"
}
