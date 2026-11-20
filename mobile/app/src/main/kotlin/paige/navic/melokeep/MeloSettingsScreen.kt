@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package paige.navic.melokeep

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
import io.github.agonie0v0.melokeep.shared.MeloSettingsController
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalSizeClass
import paige.navic.di.LocalSnackBarState
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.ChevronForward
import paige.navic.ui.icons.outlined.Close
import paige.navic.ui.icons.outlined.Delete
import paige.navic.ui.icons.outlined.Logout
import paige.navic.ui.icons.outlined.Refresh
import paige.navic.ui.icons.outlined.Search
import paige.navic.ui.theme.positive
import paige.navic.ui.theme.tabularNums
import paige.navic.ui.theme.warning

/**
 * MeloKeep 服务连接、代理配置、音源开关及浏览器 Web 后台入口。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MeloSettingsScreen() {
	val controller = koinInject<MeloSettingsController>()
	val sizeClass = LocalSizeClass.current
	val hideBack = sizeClass.widthSizeClass >= WindowWidthSizeClass.Medium
	val backStack = LocalNavStack.current
	val context = LocalContext.current
	val focusManager = LocalFocusManager.current
	val snackBarState = LocalSnackBarState.current
	val scope = rememberCoroutineScope()

	val state by controller.state.collectAsStateWithLifecycle()
	val serverConfig = state.serverConfig
	val engineConfig = state.engineConfig

	var displayNameInput by rememberSaveable { mutableStateOf(serverConfig.displayName) }
	var baseUrlInput by rememberSaveable { mutableStateOf(serverConfig.baseUrl) }
	var usernameInput by rememberSaveable { mutableStateOf(serverConfig.username) }
	var passwordInput by rememberSaveable { mutableStateOf("") }
	var passwordVisible by rememberSaveable { mutableStateOf(false) }

	var proxyUrlInput by rememberSaveable { mutableStateOf("") }
	var proxyScopeInput by rememberSaveable { mutableStateOf("all") }

	var sourceSearchQuery by rememberSaveable { mutableStateOf("") }
	var sourceCategoryFilter by rememberSaveable { mutableStateOf("ALL") }

	val deviceName = remember {
		listOf(Build.MANUFACTURER, Build.MODEL)
			.map { it.trim() }
			.filter { it.isNotEmpty() }
			.distinct()
			.joinToString(" ")
			.ifBlank { "MeloKeep Android" }
	}

	MeloFeedbackObserver(
		errorMessage = state.errorMessage,
		noticeMessage = state.noticeMessage,
		onClearError = controller::clearError,
		onClearNotice = controller::clearNotice
	)

	LaunchedEffect(serverConfig.displayName, serverConfig.baseUrl, serverConfig.username) {
		if (displayNameInput.isBlank() && serverConfig.displayName.isNotBlank()) {
			displayNameInput = serverConfig.displayName
		}
		if (baseUrlInput.isBlank() && serverConfig.baseUrl.isNotBlank()) {
			baseUrlInput = serverConfig.baseUrl
		}
		if (usernameInput.isBlank() && serverConfig.username.isNotBlank()) {
			usernameInput = serverConfig.username
		}
	}

	LaunchedEffect(serverConfig.isAuthenticated) {
		if (serverConfig.isAuthenticated) {
			controller.loadConfig()
		}
	}

	LaunchedEffect(engineConfig?.proxyUrl, engineConfig?.proxyScope) {
		if (engineConfig != null) {
			proxyUrlInput = engineConfig.proxyUrl
			proxyScopeInput = engineConfig.proxyScope.ifBlank { "all" }
		}
	}

	Scaffold(
		containerColor = MaterialTheme.colorScheme.background,
		topBar = {
			MeloCleanTopBar(
				title = "音源与代理",
				onBack = if (!hideBack && backStack.size > 1) {
					{ backStack.removeLastOrNull() }
				} else {
					null
				},
				applyStatusBarPadding = true,
				trailing = {
					if (serverConfig.isAuthenticated) {
						IconButton(
							onClick = controller::loadConfig,
							enabled = !state.loadingConfig
						) {
							if (state.loadingConfig) {
								CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
							} else {
								Icon(
									imageVector = Icons.Outlined.Refresh,
									contentDescription = "刷新配置",
									modifier = Modifier.size(20.dp)
								)
							}
						}
					}
				}
			)
		}
	) { innerPadding ->
		CompositionLocalProvider(
			LocalMinimumInteractiveComponentSize provides 0.dp
		) {
			Column(
				modifier = Modifier
					.fillMaxSize()
					.padding(top = innerPadding.calculateTopPadding())
					.verticalScroll(rememberScrollState())
					.navigationBarsPadding()
					.padding(horizontal = 16.dp, vertical = 8.dp)
					.padding(bottom = 36.dp),
				verticalArrangement = Arrangement.spacedBy(20.dp)
			) {
				// 1. 服务器连接
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					MeloSectionLabel(
						title = "MeloKeep 服务",
						trailing = {
							Text(
								text = if (serverConfig.isAuthenticated) "在线" else "未连接",
								style = MaterialTheme.typography.labelSmall,
								color = if (serverConfig.isAuthenticated) {
									MaterialTheme.colorScheme.positive
								} else {
									MaterialTheme.colorScheme.onSurfaceVariant
								}
							)
						}
					)

					MeloGroupedCard {
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.clickable(enabled = serverConfig.isAuthenticated) {
									controller.loadConfig()
								}
								.padding(horizontal = 16.dp, vertical = 13.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(10.dp)
						) {
							Box(
								modifier = Modifier
									.size(8.dp)
									.clip(CircleShape)
									.background(
										if (serverConfig.isAuthenticated) {
											MaterialTheme.colorScheme.positive
										} else {
											MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
										}
									)
							)
							val desc = when {
								!state.connectionMessage.isNullOrBlank() -> state.connectionMessage.orEmpty()
								serverConfig.isAuthenticated && serverConfig.authEnabled ->
									"${serverConfig.baseUrl} · ${serverConfig.username}"
								serverConfig.isAuthenticated ->
									"${serverConfig.baseUrl} · 免密"
								else -> "未配置服务器地址"
							}
							Text(
								text = desc,
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
								modifier = Modifier.weight(1f)
							)
						}

						MeloHairlineDivider(startIndent = 16.dp)

						Column(
							modifier = Modifier.padding(16.dp),
							verticalArrangement = Arrangement.spacedBy(10.dp)
						) {
							OutlinedTextField(
								modifier = Modifier.fillMaxWidth(),
								value = displayNameInput,
								onValueChange = {
									displayNameInput = it
									controller.updateDisplayName(it.trim())
								},
								singleLine = true,
								shape = RoundedCornerShape(12.dp),
								label = { Text("服务名称（可选，如 家里 NAS）") },
								placeholder = { Text("默认显示 MeloKeep") },
								keyboardOptions = KeyboardOptions(
									autoCorrectEnabled = false,
									imeAction = ImeAction.Next
								)
							)

							OutlinedTextField(
								modifier = Modifier.fillMaxWidth(),
								value = baseUrlInput,
								onValueChange = { baseUrlInput = it },
								singleLine = true,
								shape = RoundedCornerShape(12.dp),
								label = { Text("服务器地址") },
								placeholder = { Text("http://192.168.1.10:8765") },
								keyboardOptions = KeyboardOptions(
									autoCorrectEnabled = false,
									keyboardType = KeyboardType.Uri,
									imeAction = ImeAction.Next
								)
							)

							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.spacedBy(10.dp)
							) {
								OutlinedTextField(
									modifier = Modifier.weight(1f),
									value = usernameInput,
									onValueChange = { usernameInput = it },
									singleLine = true,
									shape = RoundedCornerShape(12.dp),
									label = { Text("用户名（可选）") },
									keyboardOptions = KeyboardOptions(
										autoCorrectEnabled = false,
										imeAction = ImeAction.Next
									)
								)
								OutlinedTextField(
									modifier = Modifier.weight(1f),
									value = passwordInput,
									onValueChange = { passwordInput = it },
									singleLine = true,
									shape = RoundedCornerShape(12.dp),
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
									)
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
										Text("测试并登录")
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

				// 2. 代理与引擎
				if (serverConfig.isAuthenticated) {
					Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
						MeloSectionLabel(title = "代理与引擎")
						MeloGroupedCard {
							Column(
								modifier = Modifier.padding(16.dp),
								verticalArrangement = Arrangement.spacedBy(10.dp)
							) {
								OutlinedTextField(
									modifier = Modifier.fillMaxWidth(),
									value = proxyUrlInput,
									onValueChange = { proxyUrlInput = it },
									singleLine = true,
									shape = RoundedCornerShape(12.dp),
									label = { Text("全局代理 URL") },
									placeholder = { Text("http://127.0.0.1:7890") },
									keyboardOptions = KeyboardOptions(
										autoCorrectEnabled = false,
										keyboardType = KeyboardType.Uri,
										imeAction = ImeAction.Done
									)
								)

								Row(
									modifier = Modifier.fillMaxWidth(),
									horizontalArrangement = Arrangement.spacedBy(8.dp),
									verticalAlignment = Alignment.CenterVertically
								) {
									FlowRow(
										modifier = Modifier.weight(1f),
										horizontalArrangement = Arrangement.spacedBy(8.dp),
										verticalArrangement = Arrangement.spacedBy(6.dp)
									) {
										listOf(
											"all" to "全部音源",
											"recommended" to "仅需代理"
										).forEach { (scopeKey, label) ->
											FilterChip(
												selected = proxyScopeInput == scopeKey,
												onClick = { proxyScopeInput = scopeKey },
												shape = CircleShape,
												label = { Text(label, maxLines = 1) }
											)
										}
									}

									FilledTonalButton(
										onClick = {
											focusManager.clearFocus()
											controller.saveEngineSettings(
												proxyUrl = proxyUrlInput.trim(),
												proxyScope = proxyScopeInput
											)
										},
										enabled = !state.savingConfig,
										shape = CircleShape,
										contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
										modifier = Modifier.height(34.dp)
									) {
										Text("保存")
									}
								}
							}

							engineConfig?.let { cfg ->
								MeloHairlineDivider(startIndent = 16.dp)
								Row(
									modifier = Modifier
										.fillMaxWidth()
										.clickable(enabled = !state.savingConfig) {
											controller.saveEngineSettings(embedMetadata = !cfg.embedMetadata)
										}
										.padding(horizontal = 16.dp, vertical = 12.dp),
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.spacedBy(12.dp)
								) {
									Column(
										modifier = Modifier.weight(1f),
										verticalArrangement = Arrangement.spacedBy(2.dp)
									) {
										Text(
											text = "自动内嵌封面与标签",
											style = MaterialTheme.typography.bodyMedium,
											fontWeight = FontWeight.Medium
										)
										Text(
											text = "并发 ${cfg.downloadConcurrency} · 歌词 ${cfg.lyricsMode}",
											style = MaterialTheme.typography.bodySmall.tabularNums(),
											color = MaterialTheme.colorScheme.onSurfaceVariant
										)
									}
									Switch(
										checked = cfg.embedMetadata,
										enabled = !state.savingConfig,
										onCheckedChange = { enabled ->
											controller.saveEngineSettings(embedMetadata = enabled)
										}
									)
								}
							}
						}
					}

					// 3. 音源开关
					val allSources = engineConfig?.availableSources.orEmpty()
					val enabledSet = engineConfig?.sources.orEmpty().toSet()
					val categories = remember(allSources) {
						allSources.mapNotNull { it.category.trim().takeIf { c -> c.isNotEmpty() } }.distinct()
					}
					val filteredSources = remember(allSources, enabledSet, sourceSearchQuery, sourceCategoryFilter) {
						val q = sourceSearchQuery.trim().lowercase()
						allSources.filter { src ->
							val matchesCategory = when (sourceCategoryFilter) {
								"ALL" -> true
								"ENABLED" -> src.id in enabledSet
								else -> src.category.equals(sourceCategoryFilter, ignoreCase = true)
							}
							val matchesQuery = q.isEmpty() ||
								src.id.lowercase().contains(q) ||
								src.label.lowercase().contains(q) ||
								src.category.lowercase().contains(q) ||
								src.pluginId.lowercase().contains(q) ||
								src.description.lowercase().contains(q)
							matchesCategory && matchesQuery
						}
					}

					Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
						MeloSectionLabel(
							title = "音源",
							trailing = {
								Text(
									text = "${enabledSet.size}/${allSources.size}",
									style = MaterialTheme.typography.labelSmall.tabularNums(),
									color = MaterialTheme.colorScheme.onSurfaceVariant
								)
							}
						)

						if (state.loadingConfig && allSources.isEmpty()) {
							MeloGroupedCard {
								Box(
									modifier = Modifier
										.fillMaxWidth()
										.padding(vertical = 24.dp),
									contentAlignment = Alignment.Center
								) {
									CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
								}
							}
						} else if (allSources.isEmpty()) {
							MeloGroupedCard {
								MeloQuietEmptyState(
									message = "暂未获取到音源列表",
									actionLabel = "重新加载",
									onAction = controller::loadConfig
								)
							}
						} else {
							OutlinedTextField(
								modifier = Modifier.fillMaxWidth(),
								value = sourceSearchQuery,
								onValueChange = { sourceSearchQuery = it },
								singleLine = true,
								shape = RoundedCornerShape(12.dp),
								placeholder = { Text("搜索音源 (${allSources.size})") },
								leadingIcon = { Icon(Icons.Outlined.Search, null, modifier = Modifier.size(18.dp)) },
								trailingIcon = {
									if (sourceSearchQuery.isNotEmpty()) {
										IconButton(onClick = { sourceSearchQuery = "" }) {
											Icon(Icons.Outlined.Close, contentDescription = "清除", modifier = Modifier.size(18.dp))
										}
									}
								}
							)

							LazyRow(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.spacedBy(6.dp)
							) {
								item(key = "cat-all") {
									FilterChip(
										selected = sourceCategoryFilter == "ALL",
										onClick = { sourceCategoryFilter = "ALL" },
										shape = CircleShape,
										label = { Text("全部 ${allSources.size}", style = MaterialTheme.typography.labelSmall.tabularNums()) }
									)
								}
								item(key = "cat-enabled") {
									FilterChip(
										selected = sourceCategoryFilter == "ENABLED",
										onClick = { sourceCategoryFilter = "ENABLED" },
										shape = CircleShape,
										label = { Text("已启用 ${enabledSet.size}", style = MaterialTheme.typography.labelSmall.tabularNums()) }
									)
								}
								items(categories, key = { "cat-$it" }) { cat ->
									FilterChip(
										selected = sourceCategoryFilter == cat,
										onClick = { sourceCategoryFilter = cat },
										shape = CircleShape,
										label = { Text(cat, style = MaterialTheme.typography.labelSmall) }
									)
								}
							}

							if (filteredSources.isEmpty()) {
								MeloGroupedCard {
									MeloQuietEmptyState(
										message = "无匹配音源",
										actionLabel = "重置筛选",
										onAction = {
											sourceSearchQuery = ""
											sourceCategoryFilter = "ALL"
										}
									)
								}
							} else {
								MeloGroupedCard {
									filteredSources.forEachIndexed { idx, src ->
										key(src.id) {
											val isEnabled = src.id in enabledSet
											val meta = buildList {
												add(src.id)
												if (src.origin.equals("plugin", ignoreCase = true)) add("插件")
												src.category.takeIf { it.isNotBlank() }?.let(::add)
												src.version.takeIf { it.isNotBlank() }?.let { add("v${it.removePrefix("v")}") }
												src.capability?.tier?.takeIf { it.isNotBlank() }?.let(::add)
												if (src.proxyRecommended) add("建议代理")
												src.initError?.takeIf { it.isNotBlank() }?.let { add("异常: $it") }
											}.joinToString(" · ")

											Row(
												modifier = Modifier
													.fillMaxWidth()
													.clickable(enabled = !state.savingConfig) {
														controller.toggleSourceEnabled(src.id)
													}
													.padding(horizontal = 16.dp, vertical = 11.dp),
												verticalAlignment = Alignment.CenterVertically,
												horizontalArrangement = Arrangement.spacedBy(12.dp)
											) {
												Column(
													modifier = Modifier.weight(1f),
													verticalArrangement = Arrangement.spacedBy(2.dp)
												) {
													Text(
														text = src.label.ifBlank { src.id },
														style = MaterialTheme.typography.bodyMedium,
														fontWeight = FontWeight.Medium,
														maxLines = 1,
														overflow = TextOverflow.Ellipsis
													)
													Text(
														text = meta,
														style = MaterialTheme.typography.bodySmall,
														color = if (!src.initError.isNullOrBlank()) {
															MaterialTheme.colorScheme.warning
														} else {
															MaterialTheme.colorScheme.onSurfaceVariant
														},
														maxLines = 1,
														overflow = TextOverflow.Ellipsis
													)
												}

												Switch(
													checked = isEnabled,
													enabled = !state.savingConfig,
													onCheckedChange = {
														controller.toggleSourceEnabled(src.id)
													}
												)
											}

											if (idx < filteredSources.lastIndex) {
												MeloHairlineDivider(startIndent = 16.dp)
											}
										}
									}
								}
							}
						}
					}

					// 4. 已授权设备
					AnimatedVisibility(visible = state.devices.isNotEmpty()) {
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

				// 5. Web 后台入口
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					MeloSectionLabel(title = "更多")
					MeloGroupedCard {
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.clickable {
									val rawUrl = serverConfig.baseUrl.ifBlank { baseUrlInput }
									val targetUrl = MeloSettingsStore.normalizeBaseUrl(rawUrl)
									if (targetUrl.isEmpty()) {
										scope.launch {
											snackBarState.currentSnackbarData?.dismiss()
											snackBarState.showSnackbar("请先填写 MeloKeep 服务器地址")
										}
									} else {
										openMeloWebDashboard(context, targetUrl) { errMsg ->
											scope.launch {
												snackBarState.currentSnackbarData?.dismiss()
												snackBarState.showSnackbar(errMsg)
											}
										}
									}
								}
								.padding(horizontal = 16.dp, vertical = 13.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(12.dp)
						) {
							Icon(
								imageVector = MeloIcons.OpenInNew,
								contentDescription = null,
								tint = MaterialTheme.colorScheme.onSurfaceVariant,
								modifier = Modifier.size(19.dp)
							)
							Text(
								text = "在浏览器打开 Web 后台",
								style = MaterialTheme.typography.bodyMedium,
								fontWeight = FontWeight.Medium,
								modifier = Modifier.weight(1f)
							)
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
}

private fun openMeloWebDashboard(
	context: Context,
	url: String,
	onError: (String) -> Unit
) {
	val uri = url.toUri()
	try {
		val customTabsIntent = CustomTabsIntent.Builder()
			.setShowTitle(true)
			.build()
		customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		customTabsIntent.launchUrl(context, uri)
	} catch (_: ActivityNotFoundException) {
		try {
			val fallback = Intent(Intent.ACTION_VIEW, uri).apply {
				addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			}
			context.startActivity(fallback)
		} catch (_: Exception) {
			onError("未找到可用的浏览器打开后台地址")
		}
	} catch (ex: Exception) {
		onError("无法打开浏览器：${ex.localizedMessage ?: "未知错误"}")
	}
}
