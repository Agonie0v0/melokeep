/*
 * Navic / MeloKeep, a Subsonic music streaming & studio app for iOS
 * Copyright (c) 2026 paige & MeloKeep Swarm
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.agonie0v0.melokeep.ios

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import io.github.agonie0v0.melokeep.shared.MeloKeepApiClient
import io.github.agonie0v0.melokeep.shared.MeloSettingsController
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import kotlinx.coroutines.launch

/**
 * 统一双模连接引导页（极简表单版）：支持独立或同时接入 MeloKeep 与 Navidrome。
 */
@Composable
fun IosOnboardingOverlay(
	settingsStore: MeloSettingsStore,
	settingsController: MeloSettingsController,
	apiClient: MeloKeepApiClient,
	subsonicManager: IosSubsonicManager,
	canNavigateBack: Boolean,
	onDismiss: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val focusManager = LocalFocusManager.current
	val scope = rememberCoroutineScope()

	val meloConfig by settingsStore.config.collectAsState()
	val settingsState by settingsController.state.collectAsState()
	val libState by subsonicManager.state.collectAsState()

	var meloUrl by rememberSaveable(meloConfig.baseUrl) { mutableStateOf(meloConfig.baseUrl) }
	var meloUser by rememberSaveable(meloConfig.username) { mutableStateOf(meloConfig.username) }
	var meloPass by rememberSaveable { mutableStateOf("") }
	var meloPassVisible by rememberSaveable { mutableStateOf(false) }

	var probingHealth by rememberSaveable { mutableStateOf(false) }
	var healthStatusMessage by rememberSaveable { mutableStateOf<String?>(null) }
	var healthStatusSuccess by rememberSaveable { mutableStateOf(false) }

	val activeServer = libState.activeServer
	var navidromeExpanded by rememberSaveable { mutableStateOf(activeServer != null) }
	var navidromeName by rememberSaveable(activeServer?.id) { mutableStateOf(activeServer?.name.orEmpty()) }
	var navidromeUrl by rememberSaveable(activeServer?.id) { mutableStateOf(activeServer?.url.orEmpty()) }
	var navidromeUser by rememberSaveable(activeServer?.id) { mutableStateOf(activeServer?.username.orEmpty()) }
	var navidromePass by rememberSaveable { mutableStateOf("") }
	var navidromePassVisible by rememberSaveable { mutableStateOf(false) }
	var navidromeConnecting by rememberSaveable { mutableStateOf(false) }
	var navidromeFeedback by rememberSaveable { mutableStateOf<String?>(null) }
	var navidromeFeedbackOk by rememberSaveable { mutableStateOf(false) }

	val finishOnboarding: () -> Unit = {
		settingsStore.setOnboardingDismissed(true)
		onDismiss()
	}

	Column(
		modifier = modifier
			.fillMaxSize()
			.background(MaterialTheme.colorScheme.background)
			.statusBarsPadding()
			.navigationBarsPadding()
			.imePadding(),
	) {
		IosCleanTopBar(
			title = "连接服务",
			onBack = if (canNavigateBack) onDismiss else null,
			trailing = {
				TextButton(
					onClick = finishOnboarding,
					contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
				) {
					Text(
						text = "稍后",
						style = MaterialTheme.typography.labelLarge,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			},
		)

		Column(
			modifier = Modifier
				.weight(1f)
				.verticalScroll(rememberScrollState())
				.padding(horizontal = 16.dp, vertical = 8.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			Column(
				modifier = Modifier
					.widthIn(max = 520.dp)
					.fillMaxWidth(),
				verticalArrangement = Arrangement.spacedBy(20.dp),
			) {
				// 1. MeloKeep 核心服务
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					IosSectionLabel(
						title = "MeloKeep",
						trailing = {
							IosOnboardingStatusLabel(
								connected = meloConfig.isAuthenticated,
								label = if (meloConfig.isAuthenticated) "已连接" else "未连接",
							)
						},
					)

					IosGroupedCard {
						Column(
							modifier = Modifier.padding(16.dp),
							verticalArrangement = Arrangement.spacedBy(10.dp),
						) {
							if (meloConfig.isAuthenticated) {
								Text(
									text = if (meloConfig.authEnabled && meloConfig.username.isNotBlank()) {
										"${meloConfig.baseUrl} · ${meloConfig.username}"
									} else {
										"${meloConfig.baseUrl} · 免密"
									},
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.meloPositive,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
								)
							}

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
									imeAction = ImeAction.Next,
								),
							)

							val quickEndpoints = remember {
								listOf(
									"http://127.0.0.1:8765" to "127.0.0.1",
									"http://localhost:8765" to "localhost",
									"http://192.168." to "192.168.*",
								)
							}
							Row(
								modifier = Modifier
									.fillMaxWidth()
									.horizontalScroll(rememberScrollState()),
								horizontalArrangement = Arrangement.spacedBy(6.dp),
								verticalAlignment = Alignment.CenterVertically,
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
											width = IosHairlineWidth,
											color = if (selected) {
												MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
											} else {
												MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
											},
										),
										modifier = Modifier
											.clip(CircleShape)
											.clickable {
												meloUrl = endpoint
												healthStatusMessage = null
											},
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
											modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
										)
									}
								}
							}

							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.spacedBy(10.dp),
								verticalAlignment = Alignment.Top,
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
										imeAction = ImeAction.Next,
									),
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
												imageVector = if (meloPassVisible) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
												contentDescription = if (meloPassVisible) "隐藏密码" else "显示密码",
												modifier = Modifier.size(18.dp),
											)
										}
									},
									keyboardOptions = KeyboardOptions(
										autoCorrectEnabled = false,
										keyboardType = KeyboardType.Password,
										imeAction = ImeAction.Done,
									),
									keyboardActions = KeyboardActions(
										onDone = {
											focusManager.clearFocus()
											if (meloUrl.isNotBlank()) {
												settingsController.testAndLogin(
													baseUrl = meloUrl,
													username = meloUser,
													password = meloPass,
													deviceName = "MeloKeep iOS",
												)
											}
										},
									),
								)
							}

							AnimatedVisibility(visible = !healthStatusMessage.isNullOrBlank()) {
								Text(
									text = healthStatusMessage.orEmpty(),
									style = MaterialTheme.typography.bodySmall,
									color = if (healthStatusSuccess) {
										MaterialTheme.colorScheme.meloPositive
									} else {
										MaterialTheme.colorScheme.error
									},
								)
							}

							settingsState.errorMessage?.let { err ->
								Text(
									text = err,
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.error,
								)
							}
							settingsState.connectionMessage?.let { msg ->
								Text(
									text = msg,
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.meloPositive,
								)
							}

							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.spacedBy(10.dp),
							) {
								val probeEnabled = !probingHealth && meloUrl.isNotBlank()
								OutlinedButton(
									modifier = Modifier.weight(0.42f),
									enabled = probeEnabled,
									shape = RoundedCornerShape(12.dp),
									contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
									border = BorderStroke(
										width = IosHairlineWidth,
										color = if (probeEnabled) {
											MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
										} else {
											MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
										},
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
									},
								) {
									if (probingHealth) {
										CircularProgressIndicator(
											modifier = Modifier.size(14.dp),
											strokeWidth = 2.dp,
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
										settingsController.testAndLogin(
											baseUrl = meloUrl,
											username = meloUser,
											password = meloPass,
											deviceName = "MeloKeep iOS",
										)
									},
								) {
									if (settingsState.testingConnection) {
										CircularProgressIndicator(
											modifier = Modifier.size(16.dp),
											strokeWidth = 2.dp,
											color = MaterialTheme.colorScheme.onPrimary,
										)
										Spacer(Modifier.width(8.dp))
										Text("连接中…")
									} else {
										Text(
											text = if (meloConfig.isAuthenticated) "重新认证" else "连接并登录",
											maxLines = 1,
										)
									}
								}
							}
						}
					}
				}

				// 2. Navidrome（可选）
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					IosSectionLabel(
						title = "Navidrome（可选）",
						trailing = {
							IosOnboardingStatusLabel(
								connected = activeServer != null,
								label = if (activeServer != null) "已连接" else "未关联",
							)
						},
					)

					IosGroupedCard {
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.clickable { navidromeExpanded = !navidromeExpanded }
								.padding(horizontal = 16.dp, vertical = 13.dp),
							horizontalArrangement = Arrangement.SpaceBetween,
							verticalAlignment = Alignment.CenterVertically,
						) {
							Text(
								text = if (activeServer != null) {
									"${activeServer.name} · ${activeServer.username}"
								} else {
									"关联 Subsonic / Navidrome 曲库"
								},
								style = MaterialTheme.typography.bodyMedium,
								color = if (activeServer != null) {
									MaterialTheme.colorScheme.onSurface
								} else {
									MaterialTheme.colorScheme.onSurfaceVariant
								},
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
								modifier = Modifier.weight(1f),
							)
							Spacer(Modifier.width(8.dp))
							Icon(
								imageVector = when {
									activeServer != null && !navidromeExpanded -> Icons.Rounded.Check
									navidromeExpanded -> Icons.Rounded.ExpandLess
									else -> Icons.Rounded.ExpandMore
								},
								contentDescription = null,
								tint = if (activeServer != null) {
									MaterialTheme.colorScheme.meloPositive
								} else {
									MaterialTheme.colorScheme.onSurfaceVariant
								},
								modifier = Modifier.size(18.dp),
							)
						}

						AnimatedVisibility(
							visible = navidromeExpanded,
							enter = fadeIn() + expandVertically(),
							exit = fadeOut() + shrinkVertically(),
						) {
							Column {
								IosHairlineDivider(startIndent = 16.dp)
								Column(
									modifier = Modifier.padding(16.dp),
									verticalArrangement = Arrangement.spacedBy(10.dp),
								) {
									val derivedSameHostUrl = remember(meloUrl, meloConfig.baseUrl) {
										deriveIosNavidromeUrl(meloUrl.ifBlank { meloConfig.baseUrl })
									}
									if (derivedSameHostUrl != null) {
										Row(
											modifier = Modifier.fillMaxWidth(),
											horizontalArrangement = Arrangement.End,
										) {
											Text(
												text = "同机填充 :4533",
												style = MaterialTheme.typography.labelMedium,
												color = MaterialTheme.colorScheme.primary,
												modifier = Modifier
													.clip(RoundedCornerShape(6.dp))
													.clickable {
														navidromeUrl = derivedSameHostUrl
														navidromeFeedback = null
													}
													.padding(horizontal = 6.dp, vertical = 2.dp),
											)
										}
									}

									OutlinedTextField(
										modifier = Modifier.fillMaxWidth(),
										value = navidromeName,
										onValueChange = { navidromeName = it },
										singleLine = true,
										enabled = !navidromeConnecting,
										shape = RoundedCornerShape(12.dp),
										label = { Text("别名（可选）", maxLines = 1, overflow = TextOverflow.Ellipsis) },
										placeholder = { Text("Navidrome", maxLines = 1, overflow = TextOverflow.Ellipsis) },
									)

									OutlinedTextField(
										modifier = Modifier.fillMaxWidth(),
										value = navidromeUrl,
										onValueChange = {
											navidromeUrl = it
											navidromeFeedback = null
										},
										singleLine = true,
										enabled = !navidromeConnecting,
										shape = RoundedCornerShape(12.dp),
										label = { Text("服务器地址", maxLines = 1, overflow = TextOverflow.Ellipsis) },
										placeholder = { Text("http://192.168.1.10:4533", maxLines = 1, overflow = TextOverflow.Ellipsis) },
										keyboardOptions = KeyboardOptions(
											autoCorrectEnabled = false,
											keyboardType = KeyboardType.Uri,
											imeAction = ImeAction.Next,
										),
									)

									Row(
										modifier = Modifier.fillMaxWidth(),
										horizontalArrangement = Arrangement.spacedBy(10.dp),
										verticalAlignment = Alignment.Top,
									) {
										OutlinedTextField(
											modifier = Modifier.weight(1f),
											value = navidromeUser,
											onValueChange = {
												navidromeUser = it
												navidromeFeedback = null
											},
											singleLine = true,
											enabled = !navidromeConnecting,
											shape = RoundedCornerShape(12.dp),
											label = { Text("账号", maxLines = 1, overflow = TextOverflow.Ellipsis) },
											keyboardOptions = KeyboardOptions(
												autoCorrectEnabled = false,
												imeAction = ImeAction.Next,
											),
										)
										OutlinedTextField(
											modifier = Modifier.weight(1f),
											value = navidromePass,
											onValueChange = {
												navidromePass = it
												navidromeFeedback = null
											},
											singleLine = true,
											enabled = !navidromeConnecting,
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
														imageVector = if (navidromePassVisible) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
														contentDescription = if (navidromePassVisible) "隐藏密码" else "显示密码",
														modifier = Modifier.size(18.dp),
													)
												}
											},
											keyboardOptions = KeyboardOptions(
												autoCorrectEnabled = false,
												keyboardType = KeyboardType.Password,
												imeAction = ImeAction.Done,
											),
										)
									}

									navidromeFeedback?.let { fb ->
										Text(
											text = fb,
											style = MaterialTheme.typography.bodySmall,
											color = if (navidromeFeedbackOk) {
												MaterialTheme.colorScheme.meloPositive
											} else {
												MaterialTheme.colorScheme.error
											},
										)
									}

									OutlinedButton(
										modifier = Modifier.fillMaxWidth(),
										shape = RoundedCornerShape(12.dp),
										border = BorderStroke(
											IosHairlineWidth,
											MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
										),
										enabled = !navidromeConnecting &&
											navidromeUrl.isNotBlank() &&
											navidromeUser.isNotBlank() &&
											navidromePass.isNotBlank(),
										onClick = {
											focusManager.clearFocus()
											navidromeConnecting = true
											navidromeFeedback = null
											scope.launch {
												val res = subsonicManager.testAndSaveServer(
													name = navidromeName,
													url = navidromeUrl,
													username = navidromeUser,
													password = navidromePass,
													editingServerId = activeServer?.id,
												)
												navidromeConnecting = false
												res.onSuccess { srv ->
													navidromeFeedbackOk = true
													navidromeFeedback = "已连接：${srv.name}"
												}.onFailure { err ->
													navidromeFeedbackOk = false
													navidromeFeedback = err.message ?: "连接 Navidrome 失败"
												}
											}
										},
									) {
										if (navidromeConnecting) {
											CircularProgressIndicator(
												modifier = Modifier.size(16.dp),
												strokeWidth = 2.dp,
											)
											Spacer(Modifier.width(8.dp))
											Text("验证中…")
										} else {
											Text(if (activeServer != null) "重新验证连接" else "连接 Navidrome")
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
		IosHairlineDivider()
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 12.dp),
			contentAlignment = Alignment.Center,
		) {
			Button(
				modifier = Modifier
					.widthIn(max = 520.dp)
					.fillMaxWidth()
					.height(48.dp),
				shape = RoundedCornerShape(12.dp),
				onClick = finishOnboarding,
			) {
				Text(
					text = "进入应用",
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.SemiBold,
				)
			}
		}
	}
}

@Composable
private fun IosOnboardingStatusLabel(
	connected: Boolean,
	label: String,
) {
	Row(
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(5.dp),
	) {
		Box(
			modifier = Modifier
				.size(6.dp)
				.clip(CircleShape)
				.background(
					if (connected) MaterialTheme.colorScheme.meloPositive
					else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
				),
		)
		Text(
			text = label,
			style = MaterialTheme.typography.labelSmall,
			color = if (connected) MaterialTheme.colorScheme.meloPositive else MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

/**
 * 纯 Kotlin（无 JVM 平台依赖）推导同机 Navidrome 默认 `:4533` 地址。
 */
private fun deriveIosNavidromeUrl(rawMeloUrl: String): String? {
	val trimmed = rawMeloUrl.trim()
	if (trimmed.isEmpty()) return null
	val normalized = if ("://" in trimmed) trimmed else "http://$trimmed"
	val schemeIdx = normalized.indexOf("://")
	if (schemeIdx < 0) return null
	val scheme = normalized.substring(0, schemeIdx).lowercase().takeIf { it == "http" || it == "https" } ?: return null
	val authorityAndRest = normalized.substring(schemeIdx + 3)
	val authority = authorityAndRest.substringBefore('/').substringBefore('?').substringBefore('#')
	if (authority.isBlank()) return null

	val host = if (authority.startsWith("[")) {
		val endBracket = authority.indexOf(']')
		if (endBracket <= 0) return null
		authority.substring(0, endBracket + 1)
	} else {
		authority.substringBefore(':')
	}
	if (host.isBlank()) return null
	return "$scheme://$host:4533"
}
