@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package paige.navic.melokeep

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.agonie0v0.melokeep.shared.MeloLibraryFile
import io.github.agonie0v0.melokeep.shared.MeloLibraryJob
import io.github.agonie0v0.melokeep.shared.MeloLibraryJobItem
import io.github.agonie0v0.melokeep.shared.MeloMetadataCandidate
import io.github.agonie0v0.melokeep.shared.MeloStudioUiState
import io.github.agonie0v0.melokeep.shared.MeloTagVersion
import paige.navic.ui.component.common.SegmentedListItem
import paige.navic.ui.component.common.SegmentedListItemDefaults
import paige.navic.ui.component.sheet.ModalBottomSheet
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.Add
import paige.navic.ui.icons.outlined.Check
import paige.navic.ui.icons.outlined.Checklist
import paige.navic.ui.icons.outlined.Close
import paige.navic.ui.icons.outlined.Delete
import paige.navic.ui.icons.outlined.Lock
import paige.navic.ui.icons.outlined.Refresh
import paige.navic.ui.icons.outlined.Search
import paige.navic.ui.theme.MeloBottomChromeClearance
import paige.navic.ui.theme.defaultFont
import paige.navic.ui.theme.positive
import paige.navic.ui.theme.tabularNums
import paige.navic.ui.theme.warning
import paige.navic.util.toFileSize
import kotlin.math.roundToInt

/**
 * 工坊「刮削整理」任务看板、多选清理、文件挑选与差异对比归档抽屉。
 */
@Composable
internal fun StudioLibraryScrapeTabContent(
	state: MeloStudioUiState,
	jobFilter: StudioScrapeJobFilter,
	multiSelectMode: Boolean,
	nested: Boolean,
	hasPlayingTrack: Boolean,
	bottomClearance: Dp,
	onFilterChange: (StudioScrapeJobFilter) -> Unit,
	onOpenFilePicker: () -> Unit,
	onRefreshJobs: () -> Unit,
	onToggleMultiSelect: () -> Unit,
	onEnterMultiSelectWithJob: (String) -> Unit,
	onToggleJobSelection: (String) -> Unit,
	onSelectAllJobs: (Collection<String>) -> Unit,
	onClearJobSelection: () -> Unit,
	onClearFinishedJobs: () -> Unit,
	onDeleteSingleJob: (String) -> Unit,
	onBatchDeleteSelected: (List<String>) -> Unit,
	onOpenJobDetail: (String) -> Unit,
	onApplyJob: (String) -> Unit,
	onCancelJob: (String) -> Unit,
	onRetryJob: (String) -> Unit,
	onShowRunningDeleteHint: () -> Unit
) {
	val filteredJobs = remember(state.libraryJobs, jobFilter) {
		when (jobFilter) {
			StudioScrapeJobFilter.ALL -> state.libraryJobs
			StudioScrapeJobFilter.REVIEW -> state.libraryJobs.filter {
				it.status.trim().lowercase() in STUDIO_SCRAPE_REVIEW_STATES
			}
			StudioScrapeJobFilter.RUNNING -> state.libraryJobs.filter {
				it.status.trim().lowercase() in STUDIO_SCRAPE_RUNNING_STATES
			}
			StudioScrapeJobFilter.DONE -> state.libraryJobs.filter {
				it.status.trim().lowercase() in STUDIO_SCRAPE_DONE_STATES
			}
			StudioScrapeJobFilter.ERROR -> state.libraryJobs.filter {
				it.status.trim().lowercase() in STUDIO_SCRAPE_ERROR_STATES
			}
		}
	}
	val finishedCount = remember(state.libraryJobs) {
		state.libraryJobs.count { studioIsScrapeJobFinished(it.status) }
	}
	val deletableFilteredIds = remember(filteredJobs) {
		filteredJobs
			.filterNot { studioIsScrapeJobRunning(it.status) }
			.map { it.id }
	}
	val selectedDeletableIds = remember(state.selectedJobIds, deletableFilteredIds) {
		val allowed = deletableFilteredIds.toSet()
		state.selectedJobIds.filter { it in allowed }
	}
	val allDeletableSelected = deletableFilteredIds.isNotEmpty() &&
		selectedDeletableIds.size == deletableFilteredIds.size

	var pendingSingleDeleteJob by remember { mutableStateOf<MeloLibraryJob?>(null) }
	var pendingBatchDeleteConfirm by remember { mutableStateOf(false) }
	var pendingClearFinishedConfirm by remember { mutableStateOf(false) }

	Box(modifier = Modifier.fillMaxSize()) {
		LazyColumn(
			modifier = Modifier.fillMaxSize(),
			contentPadding = PaddingValues(
				start = 16.dp,
				end = 16.dp,
				top = 6.dp,
				bottom = bottomClearance + if (multiSelectMode) 78.dp else 0.dp
			)
		) {
			item(key = "scrape-launcher-card", contentType = "scrape-launcher") {
				MeloGroupedCard(modifier = Modifier.padding(bottom = 12.dp)) {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(start = 14.dp, end = 12.dp, top = 11.dp, bottom = 10.dp),
						horizontalArrangement = Arrangement.SpaceBetween,
						verticalAlignment = Alignment.CenterVertically
					) {
						Text(
							text = "刮削整理 · ${state.libraryJobs.size}",
							style = MaterialTheme.typography.titleSmall.tabularNums().copy(
								fontFamily = defaultFont(grade = 100),
								fontWeight = FontWeight.SemiBold
							),
							color = MaterialTheme.colorScheme.onSurface,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
							modifier = Modifier.weight(1f)
						)
						Row(
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(4.dp)
						) {
							IconButton(
								onClick = onRefreshJobs,
								modifier = Modifier.size(32.dp)
							) {
								Icon(
									imageVector = Icons.Outlined.Refresh,
									contentDescription = "刷新任务",
									tint = MaterialTheme.colorScheme.onSurfaceVariant,
									modifier = Modifier.size(17.dp)
								)
							}
							FilledTonalButton(
								onClick = onOpenFilePicker,
								contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
								modifier = Modifier.height(32.dp)
							) {
								Icon(
									imageVector = Icons.Outlined.Add,
									contentDescription = null,
									modifier = Modifier.size(15.dp)
								)
								Spacer(Modifier.width(4.dp))
								Text(
									text = "选文件刮削",
									style = MaterialTheme.typography.labelMedium,
									maxLines = 1,
									softWrap = false
								)
							}
						}
					}

					MeloHairlineDivider()

					Row(
						modifier = Modifier
							.fillMaxWidth()
							.horizontalScroll(rememberScrollState())
							.padding(horizontal = 12.dp, vertical = 9.dp),
						horizontalArrangement = Arrangement.spacedBy(6.dp),
						verticalAlignment = Alignment.CenterVertically
					) {
						StudioBatchControlPill(
							icon = MeloIcons.CleaningServices,
							label = if (finishedCount > 0) "清理已完成 ($finishedCount)" else "清理已完成",
							enabled = finishedCount > 0 && !state.jobsLoading,
							onClick = { pendingClearFinishedConfirm = true }
						)
						StudioBatchControlPill(
							icon = if (multiSelectMode) Icons.Outlined.Close else Icons.Outlined.Checklist,
							label = if (multiSelectMode) "退出多选" else "多选管理",
							enabled = multiSelectMode || (deletableFilteredIds.isNotEmpty() && !state.jobsLoading),
							tint = if (multiSelectMode) MaterialTheme.colorScheme.primary else null,
							onClick = onToggleMultiSelect
						)
						if (multiSelectMode) {
							StudioBatchControlPill(
								icon = MeloIcons.SelectAll,
								label = if (allDeletableSelected) "取消全选" else "全选当前 (${deletableFilteredIds.size})",
								enabled = deletableFilteredIds.isNotEmpty() && !state.jobsLoading,
								onClick = {
									if (allDeletableSelected) {
										onClearJobSelection()
									} else {
										onSelectAllJobs(deletableFilteredIds)
									}
								}
							)
						}
					}
				}
			}

			item(key = "scrape-status-filters", contentType = "scrape-filters") {
				LazyRow(
					modifier = Modifier.padding(bottom = 12.dp),
					horizontalArrangement = Arrangement.spacedBy(6.dp)
				) {
					items(
						items = StudioScrapeJobFilter.entries,
						key = { it.name },
						contentType = { "filter-chip" }
					) { filter ->
						val selected = jobFilter == filter
						Surface(
							modifier = Modifier
								.clip(CircleShape)
								.clickable { onFilterChange(filter) },
							shape = CircleShape,
							color = if (selected) {
								MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
							} else {
								MaterialTheme.colorScheme.surfaceContainer
							},
							border = BorderStroke(
								width = MeloHairlineWidth,
								color = if (selected) {
									MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
								} else {
									MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
								}
							)
						) {
							Text(
								text = filter.label,
								style = MaterialTheme.typography.labelMedium.copy(
									fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
								),
								color = if (selected) {
									MaterialTheme.colorScheme.primary
								} else {
									MaterialTheme.colorScheme.onSurfaceVariant
								},
								maxLines = 1,
								softWrap = false,
								modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
							)
						}
					}
				}
			}

			if (state.jobsLoading && state.libraryJobs.isEmpty()) {
				item(key = "jobs-loading", contentType = "loading") {
					Box(
						modifier = Modifier
							.fillMaxWidth()
							.padding(vertical = 32.dp),
						contentAlignment = Alignment.Center
					) {
						CircularProgressIndicator()
					}
				}
			} else if (filteredJobs.isEmpty()) {
				item(key = "jobs-empty", contentType = "empty") {
					MeloQuietEmptyState(
						message = if (state.libraryJobs.isEmpty()) {
							"暂无刮削整理任务"
						} else {
							"「${jobFilter.label}」下暂无任务"
						},
						icon = MeloIcons.Sparkles,
						actionLabel = if (state.libraryJobs.isEmpty()) "选择文件刮削" else null,
						onAction = if (state.libraryJobs.isEmpty()) onOpenFilePicker else null
					)
				}
			} else {
				item(key = "scrape-jobs-section-header", contentType = "section-header") {
					MeloSectionLabel(
						title = "任务记录 · ${filteredJobs.size}",
						modifier = Modifier.padding(bottom = 8.dp)
					)
				}
				itemsIndexed(
					items = filteredJobs,
					key = { _, it -> "job-${it.id}" },
					contentType = { _, _ -> "library-job" }
				) { index, job ->
					LibraryJobSummaryRow(
						job = job,
						itemShape = studioGroupedItemShape(index, filteredJobs.size),
						showBottomDivider = index < filteredJobs.lastIndex,
						isSelected = state.activeJobDetail?.id == job.id,
						multiSelectMode = multiSelectMode,
						isChecked = job.id in state.selectedJobIds,
						jobsLoading = state.jobsLoading,
						onClick = { onOpenJobDetail(job.id) },
						onLongClick = { onEnterMultiSelectWithJob(job.id) },
						onToggleCheck = { onToggleJobSelection(job.id) },
						onApply = { onApplyJob(job.id) },
						onCancel = { onCancelJob(job.id) },
						onRetry = { onRetryJob(job.id) },
						onDelete = { pendingSingleDeleteJob = job },
						onShowRunningHint = onShowRunningDeleteHint
					)
				}
			}
		}

		val batchDockBottomPadding = when {
			nested -> 16.dp
			hasPlayingTrack -> MeloBottomChromeClearance
			else -> 86.dp
		}
		AnimatedVisibility(
			visible = multiSelectMode,
			enter = slideInVertically { it } + fadeIn(),
			exit = slideOutVertically { it } + fadeOut(),
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.navigationBarsPadding()
				.padding(horizontal = 16.dp)
				.padding(bottom = batchDockBottomPadding)
		) {
			ScrapeMultiSelectBatchDock(
				selectedCount = selectedDeletableIds.size,
				allSelected = allDeletableSelected,
				hasSelectable = deletableFilteredIds.isNotEmpty(),
				busy = state.jobsLoading,
				onExit = onToggleMultiSelect,
				onToggleSelectAll = {
					if (allDeletableSelected) {
						onClearJobSelection()
					} else {
						onSelectAllJobs(deletableFilteredIds)
					}
				},
				onBatchDelete = { pendingBatchDeleteConfirm = true }
			)
		}
	}

	pendingSingleDeleteJob?.let { target ->
		AlertDialog(
			onDismissRequest = { pendingSingleDeleteJob = null },
			icon = {
				Icon(
					imageVector = Icons.Outlined.Delete,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.error
				)
			},
			title = { Text("删除刮削记录") },
			text = {
				Text("仅删除整理记录与预览历史，不会删除音乐文件。")
			},
			confirmButton = {
				Button(
					onClick = {
						val targetId = target.id
						pendingSingleDeleteJob = null
						onDeleteSingleJob(targetId)
					},
					colors = ButtonDefaults.buttonColors(
						containerColor = MaterialTheme.colorScheme.error,
						contentColor = MaterialTheme.colorScheme.onError
					)
				) {
					Text("确认删除")
				}
			},
			dismissButton = {
				TextButton(onClick = { pendingSingleDeleteJob = null }) {
					Text("取消")
				}
			}
		)
	}

	if (pendingBatchDeleteConfirm) {
		val count = selectedDeletableIds.size
		AlertDialog(
			onDismissRequest = { pendingBatchDeleteConfirm = false },
			icon = {
				Icon(
					imageVector = Icons.Outlined.Delete,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.error
				)
			},
			title = { Text("批量删除记录") },
			text = {
				Text("将删除选中的 $count 条整理记录，不会删除音乐文件。")
			},
			confirmButton = {
				Button(
					onClick = {
						pendingBatchDeleteConfirm = false
						onBatchDeleteSelected(state.selectedJobIds.toList())
					},
					colors = ButtonDefaults.buttonColors(
						containerColor = MaterialTheme.colorScheme.error,
						contentColor = MaterialTheme.colorScheme.onError
					)
				) {
					Text("批量删除 ($count)")
				}
			},
			dismissButton = {
				TextButton(onClick = { pendingBatchDeleteConfirm = false }) {
					Text("取消")
				}
			}
		)
	}

	if (pendingClearFinishedConfirm) {
		AlertDialog(
			onDismissRequest = { pendingClearFinishedConfirm = false },
			icon = {
				Icon(
					imageVector = MeloIcons.CleaningServices,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary
				)
			},
			title = { Text("清理已完成记录") },
			text = {
				Text("清理所有已结束的整理记录，不会删除音乐文件。")
			},
			confirmButton = {
				Button(
					onClick = {
						pendingClearFinishedConfirm = false
						onClearFinishedJobs()
					}
				) {
					Text("确认清理")
				}
			},
			dismissButton = {
				TextButton(onClick = { pendingClearFinishedConfirm = false }) {
					Text("取消")
				}
			}
		)
	}
}

@Composable
private fun ScrapeMultiSelectBatchDock(
	selectedCount: Int,
	allSelected: Boolean,
	hasSelectable: Boolean,
	busy: Boolean,
	onExit: () -> Unit,
	onToggleSelectAll: () -> Unit,
	onBatchDelete: () -> Unit
) {
	Surface(
		modifier = Modifier
			.widthIn(max = 560.dp)
			.fillMaxWidth(),
		shape = RoundedCornerShape(18.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		border = BorderStroke(
			width = MeloHairlineWidth,
			color = MaterialTheme.colorScheme.outline.copy(alpha = 0.40f)
		),
		shadowElevation = 6.dp
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 12.dp, vertical = 9.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp)
		) {
			Row(
				modifier = Modifier.weight(1f),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(4.dp)
			) {
				IconButton(
					onClick = onExit,
					modifier = Modifier.size(28.dp)
				) {
					Icon(
						imageVector = Icons.Outlined.Close,
						contentDescription = "退出多选",
						modifier = Modifier.size(16.dp)
					)
				}
				Text(
					text = "已选 $selectedCount 项",
					style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold).tabularNums(),
					maxLines = 1
				)
			}

			TextButton(
				onClick = onToggleSelectAll,
				enabled = hasSelectable && !busy,
				contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
			) {
				Text(if (allSelected) "取消全选" else "全选当前", maxLines = 1)
			}

			Button(
				onClick = onBatchDelete,
				enabled = selectedCount > 0 && !busy,
				shape = CircleShape,
				contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
				colors = ButtonDefaults.buttonColors(
					containerColor = MaterialTheme.colorScheme.error,
					contentColor = MaterialTheme.colorScheme.onError
				)
			) {
				Icon(
					imageVector = Icons.Outlined.Delete,
					contentDescription = null,
					modifier = Modifier.size(15.dp)
				)
				Spacer(Modifier.width(4.dp))
				Text(
					text = "删除 ($selectedCount)",
					style = MaterialTheme.typography.labelMedium.tabularNums().copy(fontWeight = FontWeight.SemiBold),
					maxLines = 1
				)
			}
		}
	}
}

@Composable
private fun LibraryJobSummaryRow(
	job: MeloLibraryJob,
	itemShape: Shape,
	showBottomDivider: Boolean,
	isSelected: Boolean,
	multiSelectMode: Boolean,
	isChecked: Boolean,
	jobsLoading: Boolean,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	onToggleCheck: () -> Unit,
	onApply: () -> Unit,
	onCancel: () -> Unit,
	onRetry: () -> Unit,
	onDelete: () -> Unit,
	onShowRunningHint: () -> Unit
) {
	val statusLower = job.status.trim().lowercase()
	val isRunning = statusLower in STUDIO_SCRAPE_RUNNING_STATES
	val canApply = statusLower in STUDIO_SCRAPE_REVIEW_STATES
	val canCancel = isRunning && statusLower != "restoring"
	val canRetry = statusLower in setOf("failed", "partial", "needs_attention", "cancelled")

	val statusColor = when {
		statusLower in STUDIO_SCRAPE_DONE_STATES -> MaterialTheme.colorScheme.positive
		statusLower in STUDIO_SCRAPE_ERROR_STATES -> MaterialTheme.colorScheme.error
		statusLower in STUDIO_SCRAPE_REVIEW_STATES -> MaterialTheme.colorScheme.warning
		else -> MaterialTheme.colorScheme.primary
	}

	Surface(
		modifier = Modifier
			.fillMaxWidth()
			.clip(itemShape)
			.combinedClickable(
				onClick = {
					if (multiSelectMode) {
						if (isRunning) onShowRunningHint() else onToggleCheck()
					} else {
						onClick()
					}
				},
				onLongClick = {
					if (isRunning) onShowRunningHint() else onLongClick()
				}
			),
		shape = itemShape,
		color = when {
			multiSelectMode && isChecked -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
			isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
			else -> MaterialTheme.colorScheme.surfaceContainer
		}
	) {
		Column(modifier = Modifier.fillMaxWidth()) {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 14.dp, vertical = 12.dp),
				verticalArrangement = Arrangement.spacedBy(6.dp)
			) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically
				) {
					Row(
						modifier = Modifier.weight(1f),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(6.dp)
					) {
						if (multiSelectMode) {
							Checkbox(
								checked = isChecked,
								enabled = !isRunning,
								onCheckedChange = null
							)
						}
						val actionLabel = when (job.action.lowercase()) {
							"scrape" -> "元数据刮削"
							"organize" -> "目录整理"
							"both" -> "刮削与整理"
							"edit" -> "编辑标签"
							else -> job.action.ifBlank { "刮削与整理" }
						}
						Text(
							text = "$actionLabel · ${job.processed}/${job.total}",
							style = MaterialTheme.typography.bodyMedium.tabularNums(),
							fontWeight = FontWeight.SemiBold,
							color = MaterialTheme.colorScheme.onSurface,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis
						)
					}
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(6.dp)
					) {
						StudioStatusDot(
							label = formatMeloStatusLabel(job.status),
							color = statusColor
						)
						if (!multiSelectMode) {
							IconButton(
								onClick = onDelete,
								enabled = !isRunning && !jobsLoading,
								modifier = Modifier.size(28.dp)
							) {
								Icon(
									imageVector = Icons.Outlined.Delete,
									contentDescription = if (isRunning) "运行中不可删除" else "删除记录",
									tint = if (isRunning) {
										MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
									} else {
										MaterialTheme.colorScheme.onSurfaceVariant
									},
									modifier = Modifier.size(15.dp)
								)
							}
						}
					}
				}

				val metaLine = listOfNotNull(
					formatEpochTimestamp(job.createdAt).takeIf { it.isNotBlank() },
					job.detail.takeIf { it.isNotBlank() }
				).joinToString(" · ")
				if (metaLine.isNotEmpty()) {
					Text(
						text = metaLine,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis
					)
				}

				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically
				) {
					if (isRunning) {
						Text(
							text = "运行中请先取消",
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
					} else {
						Spacer(Modifier.width(1.dp))
					}
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(4.dp)
					) {
						TextButton(
							onClick = onClick,
							contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
						) {
							Text("对比 / 候选", style = MaterialTheme.typography.labelMedium)
						}
						if (canApply) {
							FilledTonalButton(
								onClick = onApply,
								contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
								modifier = Modifier.height(30.dp)
							) {
								Text("确认归档", style = MaterialTheme.typography.labelMedium)
							}
						} else if (canCancel) {
							TextButton(
								onClick = onCancel,
								contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
							) {
								Text(
									text = "取消",
									style = MaterialTheme.typography.labelMedium,
									color = MaterialTheme.colorScheme.error
								)
							}
						} else if (canRetry) {
							TextButton(
								onClick = onRetry,
								contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
							) {
								Text("重试", style = MaterialTheme.typography.labelMedium)
							}
						}
					}
				}
			}

			if (showBottomDivider) {
				MeloHairlineDivider(startIndent = 14.dp)
			}
		}
	}
}

@Composable
internal fun StudioLibraryFilePickerSheet(
	state: MeloStudioUiState,
	hostNavBottom: Dp,
	onDismissRequest: () -> Unit,
	onQuerySubmit: (String) -> Unit,
	onFolderSelect: (String) -> Unit,
	onLoadMore: () -> Unit,
	onToggleFile: (String) -> Unit,
	onSelectAllFiles: () -> Unit,
	onClearSelection: () -> Unit,
	onPreviewScrape: () -> Unit,
	onDirectOrganize: () -> Unit
) {
	var queryInput by remember(state.fileQuery) { mutableStateOf(state.fileQuery) }
	val focusManager = LocalFocusManager.current

	ModalBottomSheet(
		onDismissRequest = onDismissRequest,
		sheetState = rememberBottomSheetState(
			initialValue = SheetValue.Hidden,
			enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
		),
		sheetTitle = "选择曲库文件",
		containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp)
				.padding(bottom = maxOf(hostNavBottom, 16.dp) + 16.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp)
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically
			) {
				Text(
					text = "曲库文件 (${state.libraryFiles.size}) · 已选 ${state.selectedFilePaths.size}",
					style = MaterialTheme.typography.titleSmall.tabularNums(),
					fontWeight = FontWeight.SemiBold
				)
				TextButton(
					onClick = if (state.selectedFilePaths.size == state.libraryFiles.size && state.libraryFiles.isNotEmpty()) {
						onClearSelection
					} else {
						onSelectAllFiles
					},
					contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
				) {
					Text(
						if (state.selectedFilePaths.size == state.libraryFiles.size && state.libraryFiles.isNotEmpty()) {
							"取消全选"
						} else {
							"全选当前"
						}
					)
				}
			}

			Surface(
				shape = CircleShape,
				color = MaterialTheme.colorScheme.surfaceContainer,
				border = BorderStroke(
					width = MeloHairlineWidth,
					color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
				)
			) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.height(42.dp)
						.padding(start = 14.dp, end = 6.dp),
					verticalAlignment = Alignment.CenterVertically
				) {
					Icon(
						imageVector = Icons.Outlined.Search,
						contentDescription = null,
						tint = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.size(17.dp)
					)
					Spacer(Modifier.width(8.dp))
					Box(
						modifier = Modifier.weight(1f),
						contentAlignment = Alignment.CenterStart
					) {
						if (queryInput.isEmpty()) {
							Text(
								text = "按文件名或路径筛选",
								style = MaterialTheme.typography.bodyMedium,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis
							)
						}
						BasicTextField(
							value = queryInput,
							onValueChange = { queryInput = it },
							singleLine = true,
							textStyle = TextStyle(
								color = MaterialTheme.colorScheme.onSurface,
								fontFamily = defaultFont()
							),
							cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
							keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
							keyboardActions = KeyboardActions(onSearch = {
								focusManager.clearFocus()
								onQuerySubmit(queryInput)
							}),
							modifier = Modifier.fillMaxWidth()
						)
					}
					if (queryInput.isNotEmpty()) {
						IconButton(
							onClick = {
								queryInput = ""
								onQuerySubmit("")
							},
							modifier = Modifier.size(30.dp)
						) {
							Icon(Icons.Outlined.Close, contentDescription = "清除", modifier = Modifier.size(15.dp))
						}
					}
					FilledTonalButton(
						onClick = {
							focusManager.clearFocus()
							onQuerySubmit(queryInput)
						},
						contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
						modifier = Modifier.height(28.dp)
					) {
						Text("筛选", style = MaterialTheme.typography.labelMedium)
					}
				}
			}

			if (state.libraryFolders.isNotEmpty()) {
				LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					item(key = "all-folders", contentType = "folder-chip") {
						val selected = state.currentFolder.isNullOrBlank()
						Surface(
							modifier = Modifier
								.clip(CircleShape)
								.clickable { onFolderSelect("") },
							shape = CircleShape,
							color = if (selected) {
								MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
							} else {
								MaterialTheme.colorScheme.surfaceContainer
							},
							border = BorderStroke(
								width = MeloHairlineWidth,
								color = if (selected) {
									MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
								} else {
									MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
								}
							)
						) {
							Row(
								modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(4.dp)
							) {
								Icon(
									imageVector = MeloIcons.Folder,
									contentDescription = null,
									tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
									modifier = Modifier.size(14.dp)
								)
								Text(
									text = "全部目录",
									style = MaterialTheme.typography.labelMedium,
									color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
								)
							}
						}
					}
					items(
						items = state.libraryFolders,
						key = { "${it.root}:${it.folder}" },
						contentType = { "folder-chip" }
					) { folderObj ->
						val selected = state.currentFolder == folderObj.folder
						Surface(
							modifier = Modifier
								.clip(CircleShape)
								.clickable { onFolderSelect(folderObj.folder) },
							shape = CircleShape,
							color = if (selected) {
								MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
							} else {
								MaterialTheme.colorScheme.surfaceContainer
							},
							border = BorderStroke(
								width = MeloHairlineWidth,
								color = if (selected) {
									MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
								} else {
									MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
								}
							)
						) {
							Text(
								text = folderObj.folder.ifBlank { folderObj.root },
								style = MaterialTheme.typography.labelMedium,
								color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
								modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
							)
						}
					}
				}
			}

			LazyColumn(
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(min = 160.dp, max = 300.dp),
				verticalArrangement = Arrangement.spacedBy(SegmentedListItemDefaults.SegmentedGap)
			) {
				if (state.filesLoading && state.libraryFiles.isEmpty()) {
					item(key = "picker-loading", contentType = "loading") {
						Box(
							modifier = Modifier
								.fillMaxWidth()
								.padding(vertical = 32.dp),
							contentAlignment = Alignment.Center
						) {
							CircularProgressIndicator()
						}
					}
				} else if (state.libraryFiles.isEmpty()) {
					item(key = "picker-empty", contentType = "empty") {
						MeloQuietEmptyState(
							message = "当前目录下暂无音频文件",
							icon = MeloIcons.Folder
						)
					}
				} else {
					itemsIndexed(
						items = state.libraryFiles,
						key = { _, f -> f.path },
						contentType = { _, _ -> "library-file" }
					) { index, file ->
						LibraryFileRow(
							file = file,
							checked = file.path in state.selectedFilePaths,
							shapes = SegmentedListItemDefaults.segmentedShapes(
								index = index,
								count = state.libraryFiles.size
							),
							onToggle = { onToggleFile(file.path) }
						)
					}

					if (!state.nextCursor.isNullOrBlank()) {
						item(key = "picker-load-more", contentType = "load-more") {
							Box(
								modifier = Modifier
									.fillMaxWidth()
									.padding(top = 6.dp),
								contentAlignment = Alignment.Center
							) {
								OutlinedButton(
									onClick = onLoadMore,
									enabled = !state.filesLoading
								) {
									Text(if (state.filesLoading) "加载中…" else "加载更多文件")
								}
							}
						}
					}
				}
			}

			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(8.dp)
			) {
				OutlinedButton(
					modifier = Modifier.weight(1f),
					enabled = state.selectedFilePaths.isNotEmpty() && !state.jobsLoading,
					onClick = onPreviewScrape
				) {
					Text("生成刮削预览", maxLines = 1)
				}
				Button(
					modifier = Modifier.weight(1f),
					enabled = state.selectedFilePaths.isNotEmpty() && !state.jobsLoading,
					onClick = onDirectOrganize
				) {
					Text("直接整理归档", maxLines = 1)
				}
			}
		}
	}
}

@Composable
private fun LibraryFileRow(
	file: MeloLibraryFile,
	checked: Boolean,
	shapes: ListItemShapes,
	onToggle: () -> Unit
) {
	val metaTitle = file.metadata["title"]?.takeIf { it.isNotBlank() } ?: file.name
	val metaArtist = file.metadata["artist"]?.takeIf { it.isNotBlank() }
	val metaAlbum = file.metadata["album"]?.takeIf { it.isNotBlank() }
	val subParts = listOfNotNull(
		metaArtist,
		metaAlbum,
		file.folder.takeIf { it.isNotBlank() },
		file.size.takeIf { it > 0L }?.toFileSize()
	)
	SegmentedListItem(
		selected = checked,
		onClick = onToggle,
		shapes = shapes,
		leadingContent = {
			Checkbox(checked = checked, onCheckedChange = null)
		},
		content = {
			Text(
				text = metaTitle,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				fontWeight = FontWeight.Medium
			)
		},
		supportingContent = {
			Text(
				text = subParts.joinToString(" · ").ifEmpty { file.path },
				maxLines = 2,
				overflow = TextOverflow.Ellipsis
			)
		}
	)
}

/**
 * 刮削前后路径及标签对比抽屉：锁定字段保留标记、多资料源候选切换实时预览、
 * 历史版本回滚与自动联动 Navidrome 扫库开关。
 */
@Composable
internal fun StudioScrapeDiffSheet(
	job: MeloLibraryJob?,
	jobsLoading: Boolean,
	fileTagVersions: Map<String, List<MeloTagVersion>>,
	hostNavBottom: Dp,
	onDismissRequest: () -> Unit,
	onPreviewCandidate: (jobId: String, itemKey: String, candidateId: String) -> Unit,
	onLoadFileVersions: (path: String) -> Unit,
	onRestoreFileVersion: (jobId: String, itemId: String) -> Unit,
	onApplyJob: (jobId: String, choices: Map<String, String>, autoTriggerScan: Boolean) -> Unit,
	onCancelJob: (String) -> Unit,
	onRetryJob: (String) -> Unit
) {
	val candidateChoices = remember(job?.id) {
		mutableStateMapOf<String, String>()
	}
	LaunchedEffect(job?.id, job?.items) {
		job?.items?.forEach { item ->
			val key = item.id.ifBlank { item.originalPath.ifBlank { item.path } }
			val chosenId = item.chosen ?: item.candidates.firstOrNull()?.id
			if (key.isNotBlank() && !chosenId.isNullOrBlank() && key !in candidateChoices) {
				candidateChoices[key] = chosenId
			}
		}
	}

	var autoScanAfterApply by rememberSaveable(job?.id) { mutableStateOf(true) }
	val expandedVersionPaths = remember(job?.id) { mutableStateMapOf<String, Boolean>() }

	ModalBottomSheet(
		onDismissRequest = onDismissRequest,
		sheetState = rememberBottomSheetState(
			initialValue = SheetValue.Hidden,
			enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
		),
		sheetTitle = "刮削详情与归档",
		containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
	) {
		if (job == null) {
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.padding(top = 40.dp, bottom = maxOf(hostNavBottom, 16.dp) + 32.dp),
				contentAlignment = Alignment.Center
			) {
				CircularProgressIndicator()
			}
			return@ModalBottomSheet
		}

		val statusLower = job.status.trim().lowercase()
		val canApply = statusLower in (STUDIO_SCRAPE_REVIEW_STATES + "partial") ||
			job.items.any { it.status.trim().lowercase() in STUDIO_SCRAPE_REVIEW_STATES }
		val canCancel = statusLower in STUDIO_SCRAPE_RUNNING_STATES && statusLower != "restoring"
		val canRetry = statusLower in setOf("failed", "partial", "needs_attention")

		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp)
				.padding(bottom = maxOf(hostNavBottom, 16.dp) + 16.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp)
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically
			) {
				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = "对比明细 (${job.items.size} 项)",
						style = MaterialTheme.typography.titleSmall.tabularNums(),
						fontWeight = FontWeight.SemiBold
					)
					Text(
						text = "${formatMeloStatusLabel(job.status)} · ${job.processed}/${job.total}",
						style = MaterialTheme.typography.bodySmall.tabularNums(),
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
				}
				if (jobsLoading) {
					CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
				}
			}

			if (job.warnings.isNotEmpty()) {
				Surface(
					shape = RoundedCornerShape(12.dp),
					color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
				) {
					Column(
						modifier = Modifier
							.fillMaxWidth()
							.padding(10.dp),
						verticalArrangement = Arrangement.spacedBy(3.dp)
					) {
						job.warnings.forEach { warn ->
							Text(
								text = "• $warn",
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onErrorContainer
							)
						}
					}
				}
			}

			LazyColumn(
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(min = 120.dp, max = 320.dp),
				verticalArrangement = Arrangement.spacedBy(10.dp)
			) {
				if (job.items.isEmpty()) {
					item(key = "job-items-pending", contentType = "pending") {
						Text(
							text = job.detail.ifBlank { "正在扫描分析…" },
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.padding(vertical = 12.dp)
						)
					}
				} else {
					itemsIndexed(
						items = job.items,
						key = { idx, item -> item.id.ifBlank { item.path.ifBlank { "item-$idx" } } },
						contentType = { _, _ -> "scrape-diff-item" }
					) { _, item ->
						val itemKey = item.id.ifBlank { item.originalPath.ifBlank { item.path } }
						val versionTargetPath = item.path.ifBlank { item.originalPath }
						val versionsForFile = fileTagVersions[versionTargetPath]
						val versionsExpanded = expandedVersionPaths[versionTargetPath] == true

						ScrapeDiffItemCard(
							item = item,
							selectedCandidateId = candidateChoices[itemKey] ?: item.chosen,
							jobsLoading = jobsLoading,
							versionsExpanded = versionsExpanded,
							versions = versionsForFile,
							onSelectCandidate = { candId ->
								candidateChoices[itemKey] = candId
								onPreviewCandidate(job.id, itemKey, candId)
							},
							onToggleVersions = {
								val next = !versionsExpanded
								expandedVersionPaths[versionTargetPath] = next
								if (next && versionTargetPath.isNotBlank()) {
									onLoadFileVersions(versionTargetPath)
								}
							},
							onRestoreVersion = { versionJobId ->
								expandedVersionPaths[versionTargetPath] = false
								onRestoreFileVersion(versionJobId, item.id)
							}
						)
					}
				}
			}

			MeloHairlineDivider()

			if (canApply) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.clip(RoundedCornerShape(10.dp))
						.clickable { autoScanAfterApply = !autoScanAfterApply }
						.padding(horizontal = 4.dp, vertical = 2.dp),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically
				) {
					Text(
						text = "归档后自动扫库",
						style = MaterialTheme.typography.bodyMedium,
						fontWeight = FontWeight.Medium,
						modifier = Modifier.weight(1f)
					)
					Switch(
						checked = autoScanAfterApply,
						onCheckedChange = { autoScanAfterApply = it }
					)
				}
			}

			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				if (canCancel) {
					OutlinedButton(
						onClick = { onCancelJob(job.id) },
						modifier = Modifier.weight(1f)
					) {
						Text("取消任务")
					}
				}
				if (canRetry) {
					OutlinedButton(
						onClick = { onRetryJob(job.id) },
						modifier = Modifier.weight(1f)
					) {
						Text("重试失败项")
					}
				}
				if (canApply) {
					Button(
						onClick = {
							onApplyJob(job.id, candidateChoices.toMap(), autoScanAfterApply)
						},
						enabled = !jobsLoading,
						modifier = Modifier.weight(1.6f)
					) {
						Icon(Icons.Outlined.Check, null, modifier = Modifier.size(16.dp))
						Spacer(Modifier.width(6.dp))
						Text(
							text = if (autoScanAfterApply) "确认归档并扫库" else "确认归档",
							maxLines = 1
						)
					}
				}
			}
		}
	}
}

@Composable
private fun ScrapeDiffItemCard(
	item: MeloLibraryJobItem,
	selectedCandidateId: String?,
	jobsLoading: Boolean,
	versionsExpanded: Boolean,
	versions: List<MeloTagVersion>?,
	onSelectCandidate: (String) -> Unit,
	onToggleVersions: () -> Unit,
	onRestoreVersion: (versionJobId: String) -> Unit
) {
	MeloGroupedCard(modifier = Modifier.animateContentSize()) {
		Column(
			modifier = Modifier.padding(12.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp)
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically
			) {
				Text(
					text = item.name.ifBlank { item.path.substringAfterLast('/') },
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = FontWeight.SemiBold,
					modifier = Modifier.weight(1f),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
				Spacer(Modifier.width(8.dp))
				StudioStatusDot(
					label = formatMeloStatusLabel(item.status),
					color = MaterialTheme.colorScheme.primary
				)
			}

			val orig = item.originalPath.ifBlank { item.path }
			if (item.target.isNotBlank() && item.target != orig) {
				Surface(
					shape = RoundedCornerShape(10.dp),
					color = MaterialTheme.colorScheme.surfaceContainerLow
				) {
					Column(
						modifier = Modifier
							.fillMaxWidth()
							.padding(8.dp),
						verticalArrangement = Arrangement.spacedBy(2.dp)
					) {
						Text(
							text = orig,
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							textDecoration = TextDecoration.LineThrough
						)
						Text(
							text = item.target,
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.primary,
							fontWeight = FontWeight.Medium
						)
					}
				}
			}

			if (item.locks.isNotEmpty()) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(4.dp)
				) {
					Icon(
						imageVector = Icons.Outlined.Lock,
						contentDescription = null,
						tint = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.size(13.dp)
					)
					Text(
						text = "已锁定：${item.locks.joinToString("、", transform = ::translateMeloTagKey)}",
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis
					)
				}
			}

			val diffKeys = remember(item.before, item.after) {
				(item.before.keys + item.after.keys)
					.distinct()
					.filter { key ->
						val b = item.before[key].orEmpty()
						val a = item.after[key].orEmpty()
						a.isNotBlank() && a != b
					}
			}
			if (diffKeys.isNotEmpty()) {
				Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
					diffKeys.forEach { key ->
						val oldVal = item.before[key].orEmpty().ifBlank { "空" }
						val newVal = item.after[key].orEmpty()
						Row(
							modifier = Modifier.fillMaxWidth(),
							horizontalArrangement = Arrangement.spacedBy(6.dp),
							verticalAlignment = Alignment.CenterVertically
						) {
							Text(
								text = "${translateMeloTagKey(key)}:",
								style = MaterialTheme.typography.labelSmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant
							)
							Text(
								text = oldVal,
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								textDecoration = TextDecoration.LineThrough,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis
							)
							Text(
								text = "→ $newVal",
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.primary,
								fontWeight = FontWeight.Medium,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
								modifier = Modifier.weight(1f)
							)
						}
					}
				}
			}

			if (item.candidates.isNotEmpty()) {
				Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
					Text(
						text = "候选 (${item.candidates.size})",
						style = MaterialTheme.typography.labelMedium.tabularNums(),
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
					LazyRow(
						horizontalArrangement = Arrangement.spacedBy(8.dp)
					) {
						itemsIndexed(
							items = item.candidates,
							key = { idx, c -> c.id.ifBlank { "cand-$idx" } },
							contentType = { _, _ -> "candidate-pill" }
						) { _, cand ->
							CandidateSwitcherPill(
								candidate = cand,
								selected = selectedCandidateId == cand.id,
								enabled = !jobsLoading,
								onClick = { onSelectCandidate(cand.id) }
							)
						}
					}
				}
			}

			if (item.error.isNotBlank()) {
				Text(
					text = "错误：${item.error}",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.error
				)
			}

			val versionPath = item.path.ifBlank { item.originalPath }
			if (versionPath.isNotBlank()) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.End
				) {
					TextButton(
						onClick = onToggleVersions,
						contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
						modifier = Modifier.height(28.dp)
					) {
						Icon(
							imageVector = MeloIcons.Restore,
							contentDescription = null,
							modifier = Modifier.size(14.dp)
						)
						Spacer(Modifier.width(4.dp))
						Text(
							text = if (versionsExpanded) "收起历史版本" else "历史版本 / 回滚",
							style = MaterialTheme.typography.labelMedium
						)
					}
				}

				AnimatedVisibility(visible = versionsExpanded) {
					Column(
						modifier = Modifier.fillMaxWidth(),
						verticalArrangement = Arrangement.spacedBy(6.dp)
					) {
						MeloHairlineDivider()
						when {
							versions == null -> {
								Row(
									modifier = Modifier
										.fillMaxWidth()
										.padding(vertical = 8.dp),
									horizontalArrangement = Arrangement.Center,
									verticalAlignment = Alignment.CenterVertically
								) {
									CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 1.8.dp)
									Spacer(Modifier.width(8.dp))
									Text("加载历史版本…", style = MaterialTheme.typography.bodySmall)
								}
							}
							versions.isEmpty() -> {
								Text(
									text = "暂无历史版本记录",
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									modifier = Modifier.padding(vertical = 4.dp)
								)
							}
							else -> {
								versions.forEachIndexed { idx, ver ->
									VersionHistoryRow(
										version = ver,
										enabled = !jobsLoading,
										onRestore = { onRestoreVersion(ver.jobId) }
									)
									if (idx < versions.lastIndex) {
										MeloHairlineDivider()
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
private fun CandidateSwitcherPill(
	candidate: MeloMetadataCandidate,
	selected: Boolean,
	enabled: Boolean,
	onClick: () -> Unit
) {
	val displayScore = remember(candidate.score) {
		if (candidate.score <= 1.0 && candidate.score > 0.0) {
			(candidate.score * 100.0).roundToInt()
		} else {
			candidate.score.roundToInt()
		}
	}
	Surface(
		modifier = Modifier
			.clip(RoundedCornerShape(12.dp))
			.clickable(enabled = enabled, onClick = onClick),
		shape = RoundedCornerShape(12.dp),
		color = if (selected) {
			MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
		} else {
			MaterialTheme.colorScheme.surfaceContainerLow
		},
		border = BorderStroke(
			width = MeloHairlineWidth,
			color = if (selected) {
				MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
			} else {
				MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
			}
		)
	) {
		Column(
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
			verticalArrangement = Arrangement.spacedBy(2.dp)
		) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(6.dp)
			) {
				Text(
					text = translateMeloSourceName(candidate.source),
					style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
					color = MaterialTheme.colorScheme.primary
				)
				Text(
					text = "${displayScore}分",
					style = MaterialTheme.typography.labelSmall.tabularNums(),
					color = if (selected) {
						MaterialTheme.colorScheme.primary
					} else {
						MaterialTheme.colorScheme.onSurfaceVariant
					}
				)
				if (selected) {
					Icon(
						imageVector = Icons.Outlined.Check,
						contentDescription = "已选中",
						tint = MaterialTheme.colorScheme.primary,
						modifier = Modifier.size(13.dp)
					)
				}
			}
			Text(
				text = "${candidate.title} - ${candidate.artist}",
				style = MaterialTheme.typography.bodySmall,
				fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
			if (candidate.album.isNotBlank()) {
				Text(
					text = candidate.album,
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
			}
		}
	}
}

@Composable
private fun VersionHistoryRow(
	version: MeloTagVersion,
	enabled: Boolean,
	onRestore: () -> Unit
) {
	val timeStr = remember(version.createdAt) {
		formatMeloJsonTimestamp(version.createdAt)
	}
	val changedTags = remember(version.before, version.after) {
		version.after.entries
			.filter { (k, v) -> version.before[k].orEmpty() != v }
			.take(3)
	}
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = 4.dp),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.CenterVertically
	) {
		Column(
			modifier = Modifier.weight(1f),
			verticalArrangement = Arrangement.spacedBy(2.dp)
		) {
			Text(
				text = listOfNotNull(
					timeStr.takeIf { it.isNotBlank() },
					when (version.action.lowercase()) {
						"scrape" -> "刮削"
						"organize" -> "整理"
						"both" -> "刮削并整理"
						"edit" -> "编辑标签"
						else -> version.action.takeIf { it.isNotBlank() }
					},
					formatMeloStatusLabel(version.status)
				).joinToString(" · "),
				style = MaterialTheme.typography.labelMedium.tabularNums(),
				fontWeight = FontWeight.Medium
			)
			if (changedTags.isNotEmpty()) {
				val diffSummary = changedTags.joinToString("；") { (k, newVal) ->
					"${translateMeloTagKey(k)}: ${version.before[k].orEmpty().ifBlank { "空" }} → $newVal"
				}
				Text(
					text = diffSummary,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis
				)
			}
		}

		if (version.restorable) {
			FilledTonalButton(
				onClick = onRestore,
				enabled = enabled,
				contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
				modifier = Modifier.height(28.dp)
			) {
				Icon(
					imageVector = MeloIcons.Restore,
					contentDescription = null,
					modifier = Modifier.size(13.dp)
				)
				Spacer(Modifier.width(4.dp))
				Text("回滚", style = MaterialTheme.typography.labelSmall)
			}
		}
	}
}
