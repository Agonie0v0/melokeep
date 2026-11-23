/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package paige.navic.ui.screen.playlist.dialog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import paige.navic.di.LocalNavStack
import paige.navic.melokeep.MeloGroupedCard
import paige.navic.melokeep.MeloHairlineWidth
import paige.navic.ui.component.sheet.ModalBottomSheet
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.Close
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screen.playlist.viewmodel.PlaylistCreateDialogViewModel
import paige.navic.ui.screen.playlist.viewmodel.PlaylistTargetBackend
import paige.navic.ui.theme.tabularNums

@Composable
fun PlaylistCreateDialog(
	onDismissRequest: () -> Unit,
	onRefresh: () -> Unit,
	songIds: ImmutableList<String> = persistentListOf(),
	navigateAfterwards: Boolean = true,
	targetBackend: PlaylistTargetBackend? = null
) {
	val viewModel = koinViewModel<PlaylistCreateDialogViewModel>(
		key = "${songIds.joinToString()}|${targetBackend?.name.orEmpty()}",
		parameters = { parametersOf(songIds, targetBackend) }
	)
	val backStack = LocalNavStack.current
	val focusManager = LocalFocusManager.current
	val state by viewModel.uiState.collectAsStateWithLifecycle()

	LaunchedEffect(viewModel) {
		viewModel.resetForOpen()
	}

	LaunchedEffect(viewModel) {
		viewModel.events.collect { event ->
			when (event) {
				is PlaylistCreateDialogViewModel.Event.Dismiss -> {
					onDismissRequest()
					onRefresh()
					if (
						navigateAfterwards &&
						event.backend == PlaylistTargetBackend.NAVIDROME &&
						event.playlist != null
					) {
						if (backStack.contains(Screen.NowPlaying)) {
							backStack.remove(Screen.NowPlaying)
						}
						backStack.add(Screen.CollectionDetail(event.playlist.id, "playlists"))
					}
				}
			}
		}
	}

	ModalBottomSheet(
		onDismissRequest = { if (!state.submitting) onDismissRequest() },
		dragHandle = { PlaylistSheetDragHandle() },
		containerColor = MaterialTheme.colorScheme.background,
		sheetState = rememberBottomSheetState(
			initialValue = SheetValue.Hidden,
			enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
		),
		contentWindowInsets = {
			BottomSheetDefaults.modalWindowInsets.add(
				WindowInsets(left = 4.dp, right = 4.dp)
			)
		},
		sheetTitle = "新建歌单"
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.imePadding()
				.padding(horizontal = 16.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp)
		) {
			// 顶部标题行
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(12.dp)
			) {
				Column(
					modifier = Modifier.weight(1f),
					verticalArrangement = Arrangement.spacedBy(4.dp)
				) {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(8.dp)
					) {
						Text(
							text = "新建歌单",
							style = MaterialTheme.typography.titleMedium,
							fontWeight = FontWeight.SemiBold,
							color = MaterialTheme.colorScheme.onSurface
						)
						PlaylistBackendBadge(backend = state.backend)
					}
					Text(
						text = when {
							state.unsupportedOnly -> "仅包含本机文件或电台流"
							state.supportedSongCount > 0 -> "创建并加入已选 ${state.supportedSongCount} 首曲目"
							else -> "创建一个新歌单，稍后可随时添加曲目"
						},
						style = MaterialTheme.typography.bodySmall.tabularNums(),
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
				}

				Surface(
					onClick = onDismissRequest,
					enabled = !state.submitting,
					shape = CircleShape,
					color = MaterialTheme.colorScheme.surfaceContainer,
					border = BorderStroke(
						width = MeloHairlineWidth,
						color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
					),
					modifier = Modifier.size(36.dp)
				) {
					Box(
						modifier = Modifier.fillMaxSize(),
						contentAlignment = Alignment.Center
					) {
						Icon(
							imageVector = Icons.Outlined.Close,
							contentDescription = "关闭",
							tint = MaterialTheme.colorScheme.onSurface,
							modifier = Modifier.size(18.dp)
						)
					}
				}
			}

			// 双服务同时在线且无特定曲目/固定目标时展示目标服务分段胶囊
			if (state.allowBackendSwitch) {
				Surface(
					shape = CircleShape,
					color = MaterialTheme.colorScheme.surfaceContainerHigh,
					border = BorderStroke(
						width = MeloHairlineWidth,
						color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
					),
					modifier = Modifier.fillMaxWidth()
				) {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(3.dp),
						horizontalArrangement = Arrangement.spacedBy(4.dp),
						verticalAlignment = Alignment.CenterVertically
					) {
						listOf(
							PlaylistTargetBackend.NAVIDROME,
							PlaylistTargetBackend.MELOKEEP
						).forEach { option ->
							val selected = state.backend == option
							Box(
								modifier = Modifier
									.weight(1f)
									.clip(CircleShape)
									.background(
										if (selected) {
											MaterialTheme.colorScheme.primary
										} else {
											Color.Transparent
										}
									)
									.clickable(enabled = !state.submitting) {
										viewModel.selectBackend(option)
									}
									.padding(horizontal = 10.dp, vertical = 8.dp),
								contentAlignment = Alignment.Center
							) {
								Text(
									text = option.displayName,
									style = MaterialTheme.typography.labelMedium,
									fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
									color = if (selected) {
										MaterialTheme.colorScheme.onPrimary
									} else {
										MaterialTheme.colorScheme.onSurfaceVariant
									},
									textAlign = TextAlign.Center,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis
								)
							}
						}
					}
				}
			}

			// 错误提示卡
			state.error?.let { error ->
				PlaylistOperationErrorCard(
					error = error,
					onRetry = if (!state.unsupportedOnly && !error.isConnectionMissing && !state.submitting) {
						{
							focusManager.clearFocus()
							viewModel.create()
						}
					} else null,
					onConnectService = if (error.isConnectionMissing && !state.submitting) {
						{
							onDismissRequest()
							if (backStack.lastOrNull() != Screen.MeloOnboarding) {
								backStack.add(Screen.MeloOnboarding)
							}
						}
					} else null
				)
			}

			// 歌单名称输入卡片
			if (!state.unsupportedOnly) {
				MeloGroupedCard {
					Column(
						modifier = Modifier
							.fillMaxWidth()
							.padding(14.dp),
						verticalArrangement = Arrangement.spacedBy(8.dp)
					) {
						OutlinedTextField(
							value = state.name,
							onValueChange = viewModel::updateName,
							singleLine = true,
							enabled = !state.submitting,
							shape = RoundedCornerShape(12.dp),
							label = { Text("歌单名称") },
							placeholder = { Text("例如 深夜循环 / 精选无损") },
							keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
							keyboardActions = KeyboardActions(
								onDone = {
									focusManager.clearFocus()
									if (state.name.isNotBlank() && !state.submitting) {
										viewModel.create()
									}
								}
							),
							modifier = Modifier.fillMaxWidth()
						)
					}
				}
			}

			// 底部操作按钮
			if (state.unsupportedOnly) {
				OutlinedButton(
					onClick = onDismissRequest,
					modifier = Modifier
						.fillMaxWidth()
						.height(46.dp),
					shape = RoundedCornerShape(12.dp)
				) {
					Text("关闭")
				}
			} else {
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.spacedBy(10.dp)
				) {
					OutlinedButton(
						onClick = {
							focusManager.clearFocus()
							onDismissRequest()
						},
						enabled = !state.submitting,
						modifier = Modifier
							.weight(1f)
							.height(46.dp),
						shape = RoundedCornerShape(12.dp)
					) {
						Text("取消")
					}
					Button(
						onClick = {
							focusManager.clearFocus()
							viewModel.create()
						},
						enabled = !state.submitting && state.name.isNotBlank(),
						modifier = Modifier
							.weight(1.4f)
							.height(46.dp),
						shape = RoundedCornerShape(12.dp)
					) {
						if (state.submitting) {
							CircularProgressIndicator(
								modifier = Modifier.size(16.dp),
								strokeWidth = 2.dp,
								color = MaterialTheme.colorScheme.onPrimary
							)
							Spacer(Modifier.width(8.dp))
							Text("创建中…")
						} else if (state.supportedSongCount > 0) {
							Text("创建并加入")
						} else {
							Text("创建歌单")
						}
					}
				}
			}

			Spacer(Modifier.height(12.dp))
		}
	}
}
