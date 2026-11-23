/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package paige.navic.ui.screen.playlist.dialog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import paige.navic.di.LocalNavStack
import paige.navic.melokeep.MeloGroupedCard
import paige.navic.melokeep.MeloHairlineDivider
import paige.navic.melokeep.MeloHairlineWidth
import paige.navic.melokeep.MeloQuietEmptyState
import paige.navic.ui.component.common.CoverArt
import paige.navic.ui.component.sheet.ModalBottomSheet
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.Add
import paige.navic.ui.icons.outlined.Check
import paige.navic.ui.icons.outlined.Copy
import paige.navic.ui.icons.outlined.Error
import paige.navic.ui.icons.outlined.KeyboardArrowDown
import paige.navic.ui.icons.outlined.LibraryMusic
import paige.navic.ui.icons.outlined.Refresh
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screen.playlist.viewmodel.PlaylistOperationError
import paige.navic.ui.screen.playlist.viewmodel.PlaylistPickerItem
import paige.navic.ui.screen.playlist.viewmodel.PlaylistTargetBackend
import paige.navic.ui.screen.playlist.viewmodel.PlaylistUpdateDialogViewModel
import paige.navic.ui.theme.tabularNums

@Composable
fun PlaylistUpdateDialog(
	songIds: ImmutableList<String>,
	playlistToExclude: String? = null,
	onSuccess: () -> Unit = {},
	onDismissRequest: () -> Unit
) {
	val viewModel = koinViewModel<PlaylistUpdateDialogViewModel>(
		key = "${songIds.joinToString()}|${playlistToExclude.orEmpty()}",
		parameters = { parametersOf(songIds, playlistToExclude) }
	)
	val state by viewModel.uiState.collectAsStateWithLifecycle()
	val backStack = LocalNavStack.current
	val focusManager = LocalFocusManager.current

	var inlineCreateExpanded by rememberSaveable { mutableStateOf(false) }
	var newPlaylistName by rememberSaveable { mutableStateOf("") }

	LaunchedEffect(viewModel) {
		viewModel.refreshIfIdle()
	}

	LaunchedEffect(viewModel) {
		viewModel.events.collect { event ->
			when (event) {
				PlaylistUpdateDialogViewModel.Event.Dismiss -> {
					onSuccess()
					onDismissRequest()
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
		sheetTitle = "添加到歌单"
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.imePadding()
				.padding(horizontal = 16.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp)
		) {
			// 顶部标题栏
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
							text = "添加到歌单",
							style = MaterialTheme.typography.titleMedium,
							fontWeight = FontWeight.SemiBold,
							color = MaterialTheme.colorScheme.onSurface
						)
						PlaylistBackendBadge(backend = state.backend)
					}
					Text(
						text = if (state.unsupportedOnly) {
							"仅包含本机文件或电台流"
						} else {
							"已选 ${state.supportedSongCount} 首曲目"
						},
						style = MaterialTheme.typography.bodySmall.tabularNums(),
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
				}

				Surface(
					onClick = { viewModel.refreshResults() },
					enabled = !state.loading && !state.submitting && !state.unsupportedOnly,
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
						if (state.loading && state.playlists.isNotEmpty()) {
							CircularProgressIndicator(
								modifier = Modifier.size(15.dp),
								strokeWidth = 2.dp
							)
						} else {
							Icon(
								imageVector = Icons.Outlined.Refresh,
								contentDescription = "刷新歌单",
								tint = if (!state.unsupportedOnly) {
									MaterialTheme.colorScheme.onSurface
								} else {
									MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
								},
								modifier = Modifier.size(18.dp)
							)
						}
					}
				}
			}

			// 内联「新建歌单并加入」卡片
			if (!state.unsupportedOnly) {
				MeloGroupedCard {
					if (!inlineCreateExpanded) {
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.clickable(enabled = !state.submitting) {
									inlineCreateExpanded = true
								}
								.padding(horizontal = 14.dp, vertical = 13.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(10.dp)
						) {
							Box(
								modifier = Modifier
									.size(28.dp)
									.clip(CircleShape)
									.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
								contentAlignment = Alignment.Center
							) {
								Icon(
									imageVector = Icons.Outlined.Add,
									contentDescription = null,
									tint = MaterialTheme.colorScheme.primary,
									modifier = Modifier.size(17.dp)
								)
							}
							Text(
								text = "新建${state.backend.displayName}并加入",
								style = MaterialTheme.typography.bodyMedium,
								fontWeight = FontWeight.Medium,
								color = MaterialTheme.colorScheme.primary,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis
							)
						}
					} else {
						Column(
							modifier = Modifier
								.fillMaxWidth()
								.padding(14.dp),
							verticalArrangement = Arrangement.spacedBy(10.dp)
						) {
							OutlinedTextField(
								value = newPlaylistName,
								onValueChange = { newPlaylistName = it },
								singleLine = true,
								enabled = !state.submitting,
								shape = RoundedCornerShape(12.dp),
								label = { Text("新歌单名称") },
								placeholder = { Text("输入${state.backend.displayName}名称") },
								keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
								keyboardActions = KeyboardActions(
									onDone = {
										focusManager.clearFocus()
										if (newPlaylistName.isNotBlank() && !state.submitting) {
											viewModel.createAndAdd(newPlaylistName)
										}
									}
								),
								modifier = Modifier.fillMaxWidth()
							)
							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.End,
								verticalAlignment = Alignment.CenterVertically
							) {
								TextButton(
									onClick = {
										focusManager.clearFocus()
										inlineCreateExpanded = false
										newPlaylistName = ""
									},
									enabled = !state.submitting
								) {
									Text("取消")
								}
								Spacer(Modifier.width(6.dp))
								Button(
									onClick = {
										focusManager.clearFocus()
										viewModel.createAndAdd(newPlaylistName)
									},
									enabled = !state.submitting && newPlaylistName.isNotBlank(),
									shape = RoundedCornerShape(10.dp)
								) {
									if (state.creatingPlaylist) {
										CircularProgressIndicator(
											modifier = Modifier.size(15.dp),
											strokeWidth = 2.dp,
											color = MaterialTheme.colorScheme.onPrimary
										)
										Spacer(Modifier.width(6.dp))
										Text("创建中…")
									} else {
										Text("创建并加入")
									}
								}
							}
						}
					}
				}
			}

			// 结构化错误诊断卡片
			state.error?.let { error ->
				PlaylistOperationErrorCard(
					error = error,
					onRetry = if (!state.unsupportedOnly && !state.submitting) {
						{ viewModel.retryLastAction() }
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

			// 歌单列表区
			if (!state.unsupportedOnly) {
				when {
					state.loading && state.playlists.isEmpty() -> {
						MeloGroupedCard {
							Row(
								modifier = Modifier
									.fillMaxWidth()
									.padding(vertical = 28.dp, horizontal = 16.dp),
								horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
								verticalAlignment = Alignment.CenterVertically
							) {
								CircularProgressIndicator(
									modifier = Modifier.size(20.dp),
									strokeWidth = 2.dp
								)
								Text(
									text = "正在获取歌单…",
									style = MaterialTheme.typography.bodyMedium,
									color = MaterialTheme.colorScheme.onSurfaceVariant
								)
							}
						}
					}

					state.playlists.isEmpty() && state.error == null -> {
						MeloGroupedCard {
							MeloQuietEmptyState(
								message = "暂无歌单，可直接上方新建",
								icon = Icons.Outlined.LibraryMusic
							)
						}
					}

					state.playlists.isNotEmpty() -> {
						MeloGroupedCard {
							LazyColumn(
								modifier = Modifier
									.fillMaxWidth()
									.heightIn(max = 320.dp)
							) {
								itemsIndexed(
									items = state.playlists,
									key = { _, item -> item.id },
									contentType = { _, _ -> "playlist-picker-row" }
								) { index, item ->
									PlaylistPickerRow(
										item = item,
										selected = item.id in state.selectedPlaylistIds,
										enabled = !state.submitting,
										onClick = { viewModel.togglePlaylistSelection(item.id) }
									)
									if (index < state.playlists.lastIndex) {
										MeloHairlineDivider(startIndent = 64.dp)
									}
								}
							}
						}
					}
				}
			}

			// 底部固定操作栏
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
				val selectedCount = state.selectedPlaylistIds.size
				val isAdding = state.submitting && !state.creatingPlaylist
				Button(
					onClick = viewModel::confirm,
					enabled = !state.submitting && selectedCount > 0,
					modifier = Modifier
						.fillMaxWidth()
						.height(46.dp),
					shape = RoundedCornerShape(12.dp)
				) {
					if (isAdding) {
						CircularProgressIndicator(
							modifier = Modifier.size(16.dp),
							strokeWidth = 2.dp,
							color = MaterialTheme.colorScheme.onPrimary
						)
						Spacer(Modifier.width(8.dp))
						Text("正在加入…")
					} else if (selectedCount > 0) {
						Text("加入所选歌单 ($selectedCount)")
					} else {
						Text("请选择歌单")
					}
				}
			}

			Spacer(Modifier.height(12.dp))
		}
	}
}

@Composable
private fun PlaylistPickerRow(
	item: PlaylistPickerItem,
	selected: Boolean,
	enabled: Boolean,
	onClick: () -> Unit
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(enabled = enabled, onClick = onClick)
			.padding(horizontal = 14.dp, vertical = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp)
	) {
		val hasCover = !item.coverArtId.isNullOrBlank()
		if (hasCover) {
			CoverArt(
				coverArtId = item.coverArtId,
				contentScale = ContentScale.Crop,
				modifier = Modifier.size(40.dp),
				shape = RoundedCornerShape(9.dp)
			)
		} else {
			Surface(
				modifier = Modifier.size(40.dp),
				shape = RoundedCornerShape(9.dp),
				color = MaterialTheme.colorScheme.surfaceContainerHigh,
				border = BorderStroke(
					width = MeloHairlineWidth,
					color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
				)
			) {
				Box(contentAlignment = Alignment.Center) {
					Icon(
						imageVector = Icons.Outlined.LibraryMusic,
						contentDescription = null,
						tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
						modifier = Modifier.size(18.dp)
					)
				}
			}
		}

		Column(
			modifier = Modifier.weight(1f),
			verticalArrangement = Arrangement.spacedBy(2.dp)
		) {
			Text(
				text = item.name,
				style = MaterialTheme.typography.bodyMedium,
				fontWeight = FontWeight.Medium,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
			Text(
				text = "${item.trackCount} 首",
				style = MaterialTheme.typography.bodySmall.tabularNums(),
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1
			)
		}

		Box(
			modifier = Modifier
				.size(22.dp)
				.clip(CircleShape)
				.then(
					if (selected) {
						Modifier.background(MaterialTheme.colorScheme.primary)
					} else {
						Modifier.border(
							width = 1.5.dp,
							color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
							shape = CircleShape
						)
					}
				),
			contentAlignment = Alignment.Center
		) {
			if (selected) {
				Icon(
					imageVector = Icons.Outlined.Check,
					contentDescription = "已选择",
					tint = MaterialTheme.colorScheme.onPrimary,
					modifier = Modifier.size(14.dp)
				)
			}
		}
	}
}

@Composable
internal fun PlaylistSheetDragHandle() {
	Box(
		modifier = Modifier
			.padding(top = 10.dp, bottom = 6.dp)
			.size(width = 36.dp, height = 4.dp)
			.clip(CircleShape)
			.background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f))
	)
}

@Composable
internal fun PlaylistBackendBadge(
	backend: PlaylistTargetBackend,
	modifier: Modifier = Modifier
) {
	Surface(
		modifier = modifier,
		shape = CircleShape,
		color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
		border = BorderStroke(
			width = MeloHairlineWidth,
			color = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
		)
	) {
		Text(
			text = backend.displayName,
			style = MaterialTheme.typography.labelSmall,
			fontWeight = FontWeight.Medium,
			color = MaterialTheme.colorScheme.primary,
			maxLines = 1,
			modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
		)
	}
}

@Composable
internal fun PlaylistOperationErrorCard(
	error: PlaylistOperationError,
	onRetry: (() -> Unit)? = null,
	onConnectService: (() -> Unit)? = null,
	modifier: Modifier = Modifier
) {
	@Suppress("DEPRECATION")
	val clipboard = LocalClipboardManager.current
	val haptics = LocalHapticFeedback.current
	var detailsExpanded by rememberSaveable(error.technicalDetail) { mutableStateOf(false) }
	var copied by remember(error.technicalDetail) { mutableStateOf(false) }
	val chevronRotation by animateFloatAsState(
		targetValue = if (detailsExpanded) 180f else 0f
	)

	LaunchedEffect(copied) {
		if (copied) {
			delay(1500)
			copied = false
		}
	}

	Surface(
		modifier = modifier.fillMaxWidth(),
		shape = RoundedCornerShape(14.dp),
		color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.32f),
		border = BorderStroke(
			width = MeloHairlineWidth,
			color = MaterialTheme.colorScheme.error.copy(alpha = 0.42f)
		)
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(14.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp)
		) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp)
			) {
				Icon(
					imageVector = Icons.Outlined.Error,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.error,
					modifier = Modifier.size(17.dp)
				)
				Surface(
					shape = RoundedCornerShape(6.dp),
					color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f)
				) {
					Text(
						text = "${error.sourceLabel} · ${error.actionLabel}",
						style = MaterialTheme.typography.labelSmall,
						fontWeight = FontWeight.Medium,
						color = MaterialTheme.colorScheme.onErrorContainer,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
					)
				}
			}

			Text(
				text = error.summary,
				style = MaterialTheme.typography.bodyMedium,
				fontWeight = FontWeight.Medium,
				color = MaterialTheme.colorScheme.onSurface
			)

			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically
			) {
				Row(
					modifier = Modifier
						.clip(RoundedCornerShape(6.dp))
						.clickable { detailsExpanded = !detailsExpanded }
						.padding(vertical = 3.dp, horizontal = 2.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(4.dp)
				) {
					Text(
						text = if (detailsExpanded) "收起技术详情" else "查看技术详情",
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
					Icon(
						imageVector = Icons.Outlined.KeyboardArrowDown,
						contentDescription = null,
						tint = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier
							.size(16.dp)
							.rotate(chevronRotation)
					)
				}

				Surface(
					onClick = {
						haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
						clipboard.setText(AnnotatedString(error.technicalDetail))
						copied = true
					},
					shape = CircleShape,
					color = MaterialTheme.colorScheme.surfaceContainer,
					border = BorderStroke(
						width = MeloHairlineWidth,
						color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
					)
				) {
					Row(
						modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(4.dp)
					) {
						Icon(
							imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.Copy,
							contentDescription = null,
							tint = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.size(13.dp)
						)
						Text(
							text = if (copied) "已复制" else "复制诊断",
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
					}
				}
			}

			AnimatedVisibility(visible = detailsExpanded) {
				Surface(
					modifier = Modifier.fillMaxWidth(),
					shape = RoundedCornerShape(10.dp),
					color = MaterialTheme.colorScheme.surfaceContainerLowest,
					border = BorderStroke(
						width = MeloHairlineWidth,
						color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
					)
				) {
					SelectionContainer {
						Text(
							text = error.technicalDetail,
							style = MaterialTheme.typography.labelSmall.copy(
								fontFamily = FontFamily.Monospace
							),
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier
								.fillMaxWidth()
								.padding(10.dp)
						)
					}
				}
			}

			if (onRetry != null || (error.isConnectionMissing && onConnectService != null)) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.spacedBy(8.dp)
				) {
					if (onRetry != null) {
						OutlinedButton(
							onClick = onRetry,
							modifier = Modifier
								.weight(1f)
								.height(38.dp),
							shape = RoundedCornerShape(10.dp)
						) {
							Text("重试", style = MaterialTheme.typography.labelLarge)
						}
					}
					if (error.isConnectionMissing && onConnectService != null) {
						Button(
							onClick = onConnectService,
							modifier = Modifier
								.weight(1.2f)
								.height(38.dp),
							shape = RoundedCornerShape(10.dp)
						) {
							Text("去连接服务", style = MaterialTheme.typography.labelLarge)
						}
					}
				}
			}
		}
	}
}
