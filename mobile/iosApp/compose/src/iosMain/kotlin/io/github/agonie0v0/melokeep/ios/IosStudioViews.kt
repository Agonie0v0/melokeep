@file:OptIn(
	ExperimentalMaterial3Api::class,
	ExperimentalFoundationApi::class,
	ExperimentalLayoutApi::class,
)

package io.github.agonie0v0.melokeep.ios

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.agonie0v0.melokeep.shared.MeloDownloadJob
import io.github.agonie0v0.melokeep.shared.MeloLibraryFile
import io.github.agonie0v0.melokeep.shared.MeloLibraryJob
import io.github.agonie0v0.melokeep.shared.MeloLibraryJobItem
import io.github.agonie0v0.melokeep.shared.MeloMetadataCandidate
import io.github.agonie0v0.melokeep.shared.MeloServerConfig
import io.github.agonie0v0.melokeep.shared.MeloStudioUiState
import io.github.agonie0v0.melokeep.shared.MeloTagVersion
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

// ==========================================
// 工坊极简顶栏 + 全宽等宽三段胶囊
// ==========================================

@Composable
internal fun IosStudioTopHeader(
	workspaceTab: IosStudioWorkspaceTab,
	activeDownloadCount: Int,
	phoneDownloadingCount: Int,
	phoneDownloadedCount: Int,
	activeScrapeCount: Int,
	scanningNavidrome: Boolean,
	scanCount: Long?,
	onSelectTab: (IosStudioWorkspaceTab) -> Unit,
	onTriggerScan: () -> Unit,
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(bottom = 8.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		IosCleanTopBar(
			title = "工坊",
			trailing = {
				Surface(
					modifier = Modifier
						.clip(CircleShape)
						.clickable(enabled = !scanningNavidrome, onClick = onTriggerScan),
					shape = CircleShape,
					color = MaterialTheme.colorScheme.surfaceContainer,
					border = BorderStroke(
						width = IosHairlineWidth,
						color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
					),
				) {
					Row(
						modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(5.dp),
					) {
						if (scanningNavidrome) {
							CircularProgressIndicator(
								modifier = Modifier.size(13.dp),
								strokeWidth = 1.8.dp,
							)
							Text(
								text = "扫库中",
								style = MaterialTheme.typography.labelSmall,
								color = MaterialTheme.colorScheme.primary,
								maxLines = 1,
								softWrap = false,
							)
						} else {
							Icon(
								imageVector = Icons.Outlined.Refresh,
								contentDescription = "扫库",
								tint = MaterialTheme.colorScheme.primary,
								modifier = Modifier.size(14.dp),
							)
							val scanLabel = if (scanCount != null && scanCount > 0L) {
								"扫库 · $scanCount"
							} else {
								"扫库"
							}
							Text(
								text = scanLabel,
								style = MaterialTheme.typography.labelSmall.tabularNums(),
								color = MaterialTheme.colorScheme.onSurface,
								maxLines = 1,
								softWrap = false,
							)
						}
					}
				}
			},
		)

		// 第二行：全宽严格等宽 3 段胶囊（下载到库 / 下载到手机 / 刮削整理）
		Surface(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp),
			shape = CircleShape,
			color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
			border = BorderStroke(
				width = IosHairlineWidth,
				color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
			),
		) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(3.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(2.dp),
			) {
				IosStudioWorkspaceTab.entries.forEach { tab ->
					val selected = workspaceTab == tab
					val badgeText: String?
					val isHighlightBadge: Boolean
					when (tab) {
						IosStudioWorkspaceTab.SERVER_DOWNLOADS -> {
							badgeText = activeDownloadCount
								.takeIf { it > 0 }
								?.let { if (it > 99) "99+" else "$it" }
							isHighlightBadge = true
						}
						IosStudioWorkspaceTab.PHONE_OFFLINE -> {
							when {
								phoneDownloadingCount > 0 -> {
									badgeText = if (phoneDownloadingCount > 99) "99+" else "$phoneDownloadingCount"
									isHighlightBadge = true
								}
								phoneDownloadedCount > 0 -> {
									badgeText = if (phoneDownloadedCount > 999) "999+" else "$phoneDownloadedCount"
									isHighlightBadge = false
								}
								else -> {
									badgeText = null
									isHighlightBadge = false
								}
							}
						}
						IosStudioWorkspaceTab.SCRAPE_JOBS -> {
							badgeText = activeScrapeCount
								.takeIf { it > 0 }
								?.let { if (it > 99) "99+" else "$it" }
							isHighlightBadge = true
						}
					}

					Surface(
						modifier = Modifier
							.weight(1f)
							.clip(CircleShape)
							.clickable { onSelectTab(tab) },
						shape = CircleShape,
						color = if (selected) {
							MaterialTheme.colorScheme.primary
						} else {
							Color.Transparent
						},
					) {
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.padding(horizontal = 4.dp, vertical = 7.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.Center,
						) {
							Text(
								text = tab.label,
								style = MaterialTheme.typography.labelMedium.copy(
									fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
								),
								color = if (selected) {
									MaterialTheme.colorScheme.onPrimary
								} else {
									MaterialTheme.colorScheme.onSurfaceVariant
								},
								maxLines = 1,
								softWrap = false,
								overflow = TextOverflow.Ellipsis,
								modifier = Modifier.weight(1f, fill = false),
							)
							if (badgeText != null) {
								Spacer(Modifier.width(3.dp))
								val badgeBg = when {
									selected && isHighlightBadge -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f)
									selected -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f)
									isHighlightBadge -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
									else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
								}
								val badgeFg = when {
									selected && isHighlightBadge -> MaterialTheme.colorScheme.onPrimary
									selected -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.92f)
									isHighlightBadge -> MaterialTheme.colorScheme.primary
									else -> MaterialTheme.colorScheme.onSurfaceVariant
								}
								Box(
									modifier = Modifier
										.clip(CircleShape)
										.background(badgeBg)
										.padding(horizontal = 5.dp, vertical = 1.dp),
									contentAlignment = Alignment.Center,
								) {
									Text(
										text = badgeText,
										style = MaterialTheme.typography.labelSmall.copy(
											fontSize = 10.sp,
											lineHeight = 12.sp,
											fontWeight = if (isHighlightBadge) FontWeight.Bold else FontWeight.SemiBold,
										).tabularNums(),
										color = badgeFg,
										maxLines = 1,
										softWrap = false,
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

// ==========================================
// 栏位 1：下载到库（服务端队列批控 + 刮削去重联动）
// ==========================================

@Composable
internal fun IosStudioDownloadsContent(
	downloads: List<MeloDownloadJob>,
	libraryJobs: List<MeloLibraryJob>,
	ignoredLibraryJobIds: Set<String>,
	loading: Boolean,
	serverConfig: MeloServerConfig,
	safeBottomDp: Dp,
	currentTrackId: String?,
	isPlayerPlaying: Boolean,
	canPreviewPlay: Boolean,
	onRefresh: () -> Unit,
	onPauseAll: () -> Unit,
	onResumeAll: () -> Unit,
	onRetryFailed: () -> Unit,
	onClearCompleted: () -> Unit,
	onControl: (id: String, action: String) -> Unit,
	onDelete: (id: String) -> Unit,
	onPreviewPlay: (MeloDownloadJob) -> Unit,
	onGoScrape: (filePath: String, downloadId: String) -> Unit,
	onRescrape: (filePath: String, downloadId: String) -> Unit,
) {
	if (loading && downloads.isEmpty()) {
		Box(
			modifier = Modifier
				.fillMaxSize()
				.padding(bottom = safeBottomDp),
			contentAlignment = Alignment.Center,
		) {
			CircularProgressIndicator()
		}
		return
	}

	if (downloads.isEmpty()) {
		Box(
			modifier = Modifier
				.fillMaxSize()
				.padding(bottom = safeBottomDp),
			contentAlignment = Alignment.Center,
		) {
			IosQuietEmptyState(
				message = "暂无服务端下载任务",
				icon = Icons.Outlined.Cloud,
				actionLabel = "刷新队列",
				onAction = onRefresh,
			)
		}
		return
	}

	val statusCounts = remember(downloads) {
		var active = 0
		var paused = 0
		var failed = 0
		var done = 0
		downloads.forEach { job ->
			when (job.status.trim().lowercase()) {
				in IosStudioDownloadActiveStates -> active++
				in IosStudioDownloadPausedStates -> paused++
				in IosStudioDownloadFailedStates -> failed++
				in IosStudioDownloadDoneStates -> done++
			}
		}
		intArrayOf(active, paused, failed, done)
	}
	val activeCount = statusCounts[0]
	val pausedCount = statusCounts[1]
	val failedCount = statusCounts[2]
	val doneCount = statusCounts[3]

	val associatedJobsByDownloadId = remember(downloads, libraryJobs, ignoredLibraryJobIds) {
		if (libraryJobs.isEmpty()) {
			emptyMap()
		} else {
			val validJobs = libraryJobs.filter { it.id !in ignoredLibraryJobIds }
			val jobsById = validJobs.associateBy { it.id }
			val latestJobByPath = HashMap<String, MeloLibraryJob>(validJobs.size * 2)
			validJobs.forEach { libJob ->
				libJob.items.forEach { item ->
					val origPath = item.originalPath.trim()
					if (origPath.isNotBlank()) {
						val prev = latestJobByPath[origPath]
						if (prev == null || libJob.updatedAt > prev.updatedAt) {
							latestJobByPath[origPath] = libJob
						}
					}
					val curPath = item.path.trim()
					if (curPath.isNotBlank() && curPath != origPath) {
						val prev = latestJobByPath[curPath]
						if (prev == null || libJob.updatedAt > prev.updatedAt) {
							latestJobByPath[curPath] = libJob
						}
					}
				}
			}
			buildMap(downloads.size) {
				downloads.forEach { dlJob ->
					val byProc = dlJob.processing?.id
						?.takeIf { it.isNotBlank() && it !in ignoredLibraryJobIds }
						?.let { jobsById[it] }
					val matched = byProc ?: dlJob.file?.trim()
						?.takeIf { it.isNotBlank() }
						?.let { latestJobByPath[it] }
					if (matched != null) {
						put(dlJob.id, matched)
					}
				}
			}
		}
	}

	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = 4.dp,
			bottom = safeBottomDp + 16.dp,
		),
	) {
		item(key = "downloads-command-summary", contentType = "downloads-summary") {
			IosStudioDownloadsCommandCard(
				totalCount = downloads.size,
				activeCount = activeCount,
				pausedCount = pausedCount,
				failedCount = failedCount,
				doneCount = doneCount,
				onRefresh = onRefresh,
				onPauseAll = onPauseAll,
				onResumeAll = onResumeAll,
				onRetryFailed = onRetryFailed,
				onClearCompleted = onClearCompleted,
				modifier = Modifier.padding(bottom = 14.dp),
			)
		}

		item(key = "downloads-section-header", contentType = "section-header") {
			IosSectionLabel(
				title = "服务端队列 · ${downloads.size}",
				modifier = Modifier.padding(bottom = 8.dp),
			)
		}

		itemsIndexed(
			items = downloads,
			key = { _, job -> job.id },
			contentType = { _, _ -> "download-job" },
		) { index, job ->
			val isCurrent = job.track.id.isNotBlank() && currentTrackId == "mk:${job.track.id}"
			val associatedJob = associatedJobsByDownloadId[job.id]
			val associatedStatus = (associatedJob?.status
				?: job.processing?.takeIf { it.id !in ignoredLibraryJobIds }?.status)
				.orEmpty()
				.trim()
				.lowercase()
			val associatedDetail = (associatedJob?.detail
				?: job.processing?.takeIf { it.id !in ignoredLibraryJobIds }?.detail)
				.orEmpty()
				.trim()

			IosStudioDownloadJobRow(
				job = job,
				serverConfig = serverConfig,
				associatedScrapeStatus = associatedStatus,
				associatedScrapeDetail = associatedDetail,
				itemShape = iosStudioGroupedItemShape(index, downloads.size),
				showBottomDivider = index < downloads.lastIndex,
				isCurrent = isCurrent,
				isPlaying = isCurrent && isPlayerPlaying,
				canPreviewPlay = canPreviewPlay,
				onPause = { onControl(job.id, "pause") },
				onResume = { onControl(job.id, "resume") },
				onRetry = { onControl(job.id, "retry") },
				onDelete = { onDelete(job.id) },
				onPreviewPlay = { onPreviewPlay(job) },
				onGoScrape = {
					job.file?.takeIf { it.isNotBlank() }?.let { filePath ->
						onGoScrape(filePath, job.id)
					}
				},
				onRescrape = {
					job.file?.takeIf { it.isNotBlank() }?.let { filePath ->
						onRescrape(filePath, job.id)
					}
				},
			)
		}
	}
}

@Composable
private fun IosStudioDownloadsCommandCard(
	totalCount: Int,
	activeCount: Int,
	pausedCount: Int,
	failedCount: Int,
	doneCount: Int,
	onRefresh: () -> Unit,
	onPauseAll: () -> Unit,
	onResumeAll: () -> Unit,
	onRetryFailed: () -> Unit,
	onClearCompleted: () -> Unit,
	modifier: Modifier = Modifier,
) {
	IosGroupedCard(modifier = modifier) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(start = 14.dp, end = 8.dp, top = 11.dp, bottom = 10.dp),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			Column(
				modifier = Modifier.weight(1f),
				verticalArrangement = Arrangement.spacedBy(2.dp),
			) {
				Text(
					text = if (activeCount > 0) {
						"正在入库 $activeCount 首"
					} else {
						"入库队列 · $totalCount 项"
					},
					style = MaterialTheme.typography.titleSmall.tabularNums(),
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.onSurface,
				)
				Text(
					text = "进行中 $activeCount · 暂停 $pausedCount · 完成 $doneCount · 失败 $failedCount",
					style = MaterialTheme.typography.labelSmall.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
			IconButton(
				onClick = onRefresh,
				modifier = Modifier.size(32.dp),
			) {
				Icon(
					imageVector = Icons.Outlined.Refresh,
					contentDescription = "刷新下载队列",
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.size(17.dp),
				)
			}
		}

		IosHairlineDivider()

		Row(
			modifier = Modifier
				.fillMaxWidth()
				.horizontalScroll(rememberScrollState())
				.padding(horizontal = 12.dp, vertical = 9.dp),
			horizontalArrangement = Arrangement.spacedBy(6.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			IosStudioBatchControlPill(
				icon = Icons.Outlined.Pause,
				label = "全部暂停",
				enabled = activeCount > 0,
				onClick = onPauseAll,
			)
			IosStudioBatchControlPill(
				icon = Icons.Outlined.PlayArrow,
				label = "全部继续",
				enabled = pausedCount > 0,
				onClick = onResumeAll,
			)
			IosStudioBatchControlPill(
				icon = Icons.Outlined.Refresh,
				label = "重试失败",
				enabled = failedCount > 0,
				tint = if (failedCount > 0) MaterialTheme.colorScheme.error else null,
				onClick = onRetryFailed,
			)
			IosStudioBatchControlPill(
				icon = Icons.Outlined.DeleteSweep,
				label = "清理完成",
				enabled = doneCount > 0,
				onClick = onClearCompleted,
			)
		}
	}
}

@Composable
private fun IosStudioDownloadJobRow(
	job: MeloDownloadJob,
	serverConfig: MeloServerConfig,
	associatedScrapeStatus: String,
	associatedScrapeDetail: String,
	itemShape: Shape,
	showBottomDivider: Boolean,
	isCurrent: Boolean,
	isPlaying: Boolean,
	canPreviewPlay: Boolean,
	onPause: () -> Unit,
	onResume: () -> Unit,
	onRetry: () -> Unit,
	onDelete: () -> Unit,
	onPreviewPlay: () -> Unit,
	onGoScrape: () -> Unit,
	onRescrape: () -> Unit,
) {
	val statusLower = job.status.trim().lowercase()
	val canPause = statusLower in IosStudioDownloadActiveStates
	val canResume = statusLower in IosStudioDownloadPausedStates
	val canRetry = statusLower in IosStudioDownloadFailedStates
	val isDone = statusLower in IosStudioDownloadDoneStates

	val statusColor = when {
		isDone -> MaterialTheme.colorScheme.meloPositive
		canRetry -> MaterialTheme.colorScheme.error
		canResume -> MaterialTheme.colorScheme.meloGoldText
		else -> MaterialTheme.colorScheme.primary
	}

	val scrapeDotSpec = when {
		associatedScrapeStatus in IosStudioScrapeReviewStates ->
			"待审核" to MaterialTheme.colorScheme.meloGoldText
		associatedScrapeStatus in IosStudioScrapeRunningStates ->
			"整理中" to MaterialTheme.colorScheme.primary
		associatedScrapeStatus == "restored" ->
			"已回滚" to MaterialTheme.colorScheme.onSurfaceVariant
		associatedScrapeStatus in IosStudioScrapeDoneStates ->
			"已整理" to MaterialTheme.colorScheme.meloPositive
		associatedScrapeStatus in IosStudioScrapeErrorStates ->
			"整理异常" to MaterialTheme.colorScheme.error
		else -> null
	}

	val coverUrl = remember(job.track.cover, serverConfig.baseUrl, serverConfig.mediaToken) {
		iosStudioResolveMediaUrl(job.track.cover, serverConfig)
	}
	val qualityLabel = remember(job.qualityLabel, job.qualityId, job.track) {
		extractIosTrackQualityBadge(job.track)?.first
			?: job.qualityLabel.ifBlank { job.qualityId }.takeIf { it.isNotBlank() }
	}
	val sourceText = remember(job.downloadSource, job.track.source) {
		job.downloadSource.ifBlank { job.track.source }
			.takeIf { it.isNotBlank() }
			?.let(::translateIosSourceName)
	}
	val doneSizeText = remember(isDone, job.totalBytes, job.bytes) {
		if (!isDone) {
			null
		} else {
			(job.totalBytes?.takeIf { it > 0L } ?: job.bytes.takeIf { it > 0L })?.let(::formatBytesHuman)
		}
	}

	Surface(
		modifier = Modifier.fillMaxWidth(),
		shape = itemShape,
		color = if (isCurrent) {
			MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
		} else {
			MaterialTheme.colorScheme.surfaceContainer
		},
	) {
		Column(modifier = Modifier.fillMaxWidth()) {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 14.dp, vertical = 12.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(12.dp),
				) {
					CoverArtBox(
						coverUrl = coverUrl,
						size = 44.dp,
						cornerRadius = 10.dp,
					)
					Column(
						modifier = Modifier.weight(1f),
						verticalArrangement = Arrangement.spacedBy(2.dp),
					) {
						Text(
							text = job.track.title.ifBlank { "未知曲目" },
							style = MaterialTheme.typography.bodyMedium,
							fontWeight = FontWeight.SemiBold,
							color = if (isCurrent) {
								MaterialTheme.colorScheme.primary
							} else {
								MaterialTheme.colorScheme.onSurface
							},
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
						val subtitle = listOfNotNull(
							job.track.artist.takeIf { it.isNotBlank() },
							sourceText,
							qualityLabel,
							doneSizeText,
						).joinToString(" · ")
						if (subtitle.isNotEmpty()) {
							Text(
								text = subtitle,
								style = MaterialTheme.typography.bodySmall.tabularNums(),
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
							)
						}
					}
					IosStudioStatusDot(
						label = formatIosStatusLabel(job.status),
						color = statusColor,
					)
				}

				if (!isDone) {
					val progressFraction = job.progress.coerceIn(0f, 1f)
					val progressPct = (progressFraction * 100f).roundToInt().coerceIn(0, 100)
					if (statusLower in setOf("queued", "resolving") && (job.totalBytes ?: 0L) <= 0L) {
						LinearProgressIndicator(
							modifier = Modifier
								.fillMaxWidth()
								.height(3.dp)
								.clip(CircleShape),
							color = statusColor,
							trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
						)
					} else {
						Box(
							modifier = Modifier
								.fillMaxWidth()
								.height(3.dp)
								.clip(CircleShape)
								.background(MaterialTheme.colorScheme.surfaceContainerHighest),
						) {
							if (progressFraction > 0f) {
								Box(
									modifier = Modifier
										.fillMaxWidth(progressFraction)
										.fillMaxHeight()
										.clip(CircleShape)
										.background(statusColor),
								)
							}
						}
					}

					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.SpaceBetween,
						verticalAlignment = Alignment.CenterVertically,
					) {
						val downloadedStr = formatBytesHuman(job.bytes)
						val totalStr = job.totalBytes?.takeIf { it > 0L }?.let(::formatBytesHuman) ?: "--"
						Text(
							text = "$downloadedStr / $totalStr",
							style = MaterialTheme.typography.labelSmall.tabularNums(),
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
						Text(
							text = "$progressPct%",
							style = MaterialTheme.typography.labelSmall.tabularNums(),
							color = statusColor,
							fontWeight = FontWeight.Medium,
						)
					}
				}

				val detailLine = associatedScrapeDetail.takeIf { it.isNotBlank() }
					?: job.detail.takeIf { it.isNotBlank() }
					?: if (!isDone) job.file?.takeIf { it.isNotBlank() } else null
				if (scrapeDotSpec != null || detailLine != null) {
					Row(
						modifier = Modifier.fillMaxWidth(),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(8.dp),
					) {
						if (scrapeDotSpec != null) {
							IosStudioStatusDot(
								label = scrapeDotSpec.first,
								color = scrapeDotSpec.second,
							)
						}
						if (detailLine != null) {
							Text(
								text = detailLine,
								style = MaterialTheme.typography.bodySmall,
								color = if (canRetry) {
									MaterialTheme.colorScheme.error
								} else {
									MaterialTheme.colorScheme.onSurfaceVariant
								},
								maxLines = 2,
								overflow = TextOverflow.Ellipsis,
								modifier = Modifier.weight(1f),
							)
						}
					}
				}

				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically,
				) {
					Row(
						modifier = Modifier
							.weight(1f)
							.horizontalScroll(rememberScrollState()),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(6.dp),
					) {
						if (isDone && canPreviewPlay && job.track.id.isNotBlank()) {
							Surface(
								modifier = Modifier
									.clip(CircleShape)
									.clickable(onClick = onPreviewPlay),
								shape = CircleShape,
								color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
							) {
								Row(
									modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.spacedBy(4.dp),
								) {
									Icon(
										imageVector = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
										contentDescription = null,
										tint = MaterialTheme.colorScheme.primary,
										modifier = Modifier.size(13.dp),
									)
									Text(
										text = if (isPlaying) "播放中" else "试听",
										style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
										color = MaterialTheme.colorScheme.primary,
										maxLines = 1,
									)
								}
							}
						}

						if (isDone && !job.file.isNullOrBlank()) {
							val primaryScrapeLabel = when {
								associatedScrapeStatus in IosStudioScrapeReviewStates -> "审核整理"
								associatedScrapeStatus in IosStudioScrapeRunningStates -> "查看进度"
								associatedScrapeStatus in setOf("complete", "completed") -> "查看整理"
								associatedScrapeStatus == "restored" ||
									associatedScrapeStatus in IosStudioScrapeErrorStates -> "重新刮削"
								else -> "刮削整理"
							}
							Surface(
								modifier = Modifier
									.clip(CircleShape)
									.clickable(onClick = onGoScrape),
								shape = CircleShape,
								color = MaterialTheme.colorScheme.surfaceContainerHigh,
								border = BorderStroke(
									width = IosHairlineWidth,
									color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
								),
							) {
								Row(
									modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.spacedBy(4.dp),
								) {
									Icon(
										imageVector = Icons.Outlined.AutoAwesome,
										contentDescription = null,
										tint = MaterialTheme.colorScheme.primary,
										modifier = Modifier.size(12.dp),
									)
									Text(
										text = primaryScrapeLabel,
										style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
										color = MaterialTheme.colorScheme.onSurface,
										maxLines = 1,
									)
								}
							}

							if (associatedScrapeStatus in IosStudioScrapeReviewStates) {
								Surface(
									modifier = Modifier
										.clip(CircleShape)
										.clickable(onClick = onRescrape),
									shape = CircleShape,
									color = MaterialTheme.colorScheme.surfaceContainerLow,
									border = BorderStroke(
										width = IosHairlineWidth,
										color = MaterialTheme.colorScheme.outline.copy(alpha = 0.30f),
									),
								) {
									Row(
										modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
										verticalAlignment = Alignment.CenterVertically,
										horizontalArrangement = Arrangement.spacedBy(4.dp),
									) {
										Icon(
											imageVector = Icons.Outlined.Refresh,
											contentDescription = null,
											tint = MaterialTheme.colorScheme.onSurfaceVariant,
											modifier = Modifier.size(12.dp),
										)
										Text(
											text = "重新刮削",
											style = MaterialTheme.typography.labelSmall,
											color = MaterialTheme.colorScheme.onSurfaceVariant,
											maxLines = 1,
										)
									}
								}
							}
						}
					}

					Row(verticalAlignment = Alignment.CenterVertically) {
						if (canPause) {
							TextButton(
								onClick = onPause,
								contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
							) {
								Icon(Icons.Outlined.Pause, contentDescription = null, modifier = Modifier.size(14.dp))
								Spacer(Modifier.width(3.dp))
								Text("暂停", style = MaterialTheme.typography.labelMedium)
							}
						}
						if (canResume) {
							TextButton(
								onClick = onResume,
								contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
							) {
								Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
								Spacer(Modifier.width(3.dp))
								Text("继续", style = MaterialTheme.typography.labelMedium)
							}
						}
						if (canRetry) {
							TextButton(
								onClick = onRetry,
								contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
							) {
								Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
								Spacer(Modifier.width(3.dp))
								Text("重试", style = MaterialTheme.typography.labelMedium)
							}
						}
						IconButton(
							onClick = onDelete,
							modifier = Modifier.size(32.dp),
						) {
							Icon(
								imageVector = Icons.Outlined.Delete,
								contentDescription = "取消或删除",
								modifier = Modifier.size(16.dp),
								tint = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					}
				}
			}

			if (showBottomDivider) {
				IosHairlineDivider(startIndent = 70.dp)
			}
		}
	}
}

// ==========================================
// 栏位 3：刮削整理（任务看板 + 单条/批删 + 文件选择 + 候选对比回滚）
// ==========================================

@Composable
internal fun IosStudioScrapeContent(
	state: MeloStudioUiState,
	jobFilter: IosStudioScrapeFilter,
	multiSelectMode: Boolean,
	runningDeleteHint: String?,
	safeBottomDp: Dp,
	onDismissRunningHint: () -> Unit,
	onFilterChange: (IosStudioScrapeFilter) -> Unit,
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
	onShowRunningDeleteHint: () -> Unit,
) {
	val filteredJobs = remember(state.libraryJobs, jobFilter) {
		when (jobFilter) {
			IosStudioScrapeFilter.ALL -> state.libraryJobs
			IosStudioScrapeFilter.REVIEW -> state.libraryJobs.filter {
				it.status.trim().lowercase() in IosStudioScrapeReviewStates
			}
			IosStudioScrapeFilter.RUNNING -> state.libraryJobs.filter {
				it.status.trim().lowercase() in IosStudioScrapeRunningStates
			}
			IosStudioScrapeFilter.DONE -> state.libraryJobs.filter {
				it.status.trim().lowercase() in IosStudioScrapeDoneStates
			}
			IosStudioScrapeFilter.ERROR -> state.libraryJobs.filter {
				it.status.trim().lowercase() in IosStudioScrapeErrorStates
			}
		}
	}
	val finishedCount = remember(state.libraryJobs) {
		state.libraryJobs.count { iosStudioIsScrapeFinished(it.status) }
	}
	val deletableFilteredIds = remember(filteredJobs) {
		filteredJobs
			.filterNot { iosStudioIsScrapeRunning(it.status) }
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
				top = 4.dp,
				bottom = safeBottomDp + if (multiSelectMode) 78.dp else 16.dp,
			),
		) {
			item(key = "scrape-launcher-card", contentType = "scrape-launcher") {
				IosGroupedCard(modifier = Modifier.padding(bottom = 12.dp)) {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(start = 14.dp, end = 12.dp, top = 11.dp, bottom = 10.dp),
						horizontalArrangement = Arrangement.SpaceBetween,
						verticalAlignment = Alignment.CenterVertically,
					) {
						Text(
							text = "刮削整理 · ${state.libraryJobs.size}",
							style = MaterialTheme.typography.titleSmall.tabularNums(),
							fontWeight = FontWeight.SemiBold,
							color = MaterialTheme.colorScheme.onSurface,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
							modifier = Modifier.weight(1f),
						)
						Row(
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(4.dp),
						) {
							IconButton(
								onClick = onRefreshJobs,
								modifier = Modifier.size(32.dp),
							) {
								Icon(
									imageVector = Icons.Outlined.Refresh,
									contentDescription = "刷新任务",
									tint = MaterialTheme.colorScheme.onSurfaceVariant,
									modifier = Modifier.size(17.dp),
								)
							}
							FilledTonalButton(
								onClick = onOpenFilePicker,
								contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
								modifier = Modifier.height(32.dp),
							) {
								Icon(
									imageVector = Icons.Outlined.Add,
									contentDescription = null,
									modifier = Modifier.size(15.dp),
								)
								Spacer(Modifier.width(4.dp))
								Text(
									text = "选文件刮削",
									style = MaterialTheme.typography.labelMedium,
									maxLines = 1,
									softWrap = false,
								)
							}
						}
					}

					IosHairlineDivider()

					Row(
						modifier = Modifier
							.fillMaxWidth()
							.horizontalScroll(rememberScrollState())
							.padding(horizontal = 12.dp, vertical = 9.dp),
						horizontalArrangement = Arrangement.spacedBy(6.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						IosStudioBatchControlPill(
							icon = Icons.Outlined.DeleteSweep,
							label = if (finishedCount > 0) "清理已完成 ($finishedCount)" else "清理已完成",
							enabled = finishedCount > 0 && !state.jobsLoading,
							onClick = { pendingClearFinishedConfirm = true },
						)
						IosStudioBatchControlPill(
							icon = if (multiSelectMode) Icons.Outlined.Close else Icons.Outlined.DoneAll,
							label = if (multiSelectMode) "退出多选" else "多选管理",
							enabled = multiSelectMode || (deletableFilteredIds.isNotEmpty() && !state.jobsLoading),
							tint = if (multiSelectMode) MaterialTheme.colorScheme.primary else null,
							onClick = onToggleMultiSelect,
						)
						if (multiSelectMode) {
							IosStudioBatchControlPill(
								icon = Icons.Outlined.SelectAll,
								label = if (allDeletableSelected) "取消全选" else "全选当前 (${deletableFilteredIds.size})",
								enabled = deletableFilteredIds.isNotEmpty() && !state.jobsLoading,
								onClick = {
									if (allDeletableSelected) {
										onClearJobSelection()
									} else {
										onSelectAllJobs(deletableFilteredIds)
									}
								},
							)
						}
					}
				}
			}

			if (!runningDeleteHint.isNullOrBlank()) {
				item(key = "scrape-running-hint", contentType = "hint-banner") {
					Surface(
						modifier = Modifier
							.fillMaxWidth()
							.padding(bottom = 10.dp),
						shape = RoundedCornerShape(12.dp),
						color = MaterialTheme.colorScheme.surfaceContainerHigh,
						border = BorderStroke(
							width = IosHairlineWidth,
							color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
						),
					) {
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.padding(start = 12.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.SpaceBetween,
						) {
							Text(
								text = runningDeleteHint,
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								modifier = Modifier.weight(1f),
							)
							IconButton(
								onClick = onDismissRunningHint,
								modifier = Modifier.size(26.dp),
							) {
								Icon(
									imageVector = Icons.Outlined.Close,
									contentDescription = "关闭提示",
									tint = MaterialTheme.colorScheme.onSurfaceVariant,
									modifier = Modifier.size(14.dp),
								)
							}
						}
					}
				}
			}

			item(key = "scrape-status-filters", contentType = "scrape-filters") {
				LazyRow(
					modifier = Modifier.padding(bottom = 12.dp),
					horizontalArrangement = Arrangement.spacedBy(6.dp),
				) {
					items(
						items = IosStudioScrapeFilter.entries,
						key = { it.name },
						contentType = { "filter-chip" },
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
								width = IosHairlineWidth,
								color = if (selected) {
									MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
								} else {
									MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
								},
							),
						) {
							Text(
								text = filter.label,
								style = MaterialTheme.typography.labelMedium.copy(
									fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
								),
								color = if (selected) {
									MaterialTheme.colorScheme.primary
								} else {
									MaterialTheme.colorScheme.onSurfaceVariant
								},
								maxLines = 1,
								softWrap = false,
								modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
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
						contentAlignment = Alignment.Center,
					) {
						CircularProgressIndicator()
					}
				}
			} else if (filteredJobs.isEmpty()) {
				item(key = "jobs-empty", contentType = "empty") {
					IosQuietEmptyState(
						message = if (state.libraryJobs.isEmpty()) {
							"暂无刮削整理任务"
						} else {
							"「${jobFilter.label}」下暂无任务"
						},
						icon = Icons.Outlined.AutoAwesome,
						actionLabel = if (state.libraryJobs.isEmpty()) "选择文件刮削" else null,
						onAction = if (state.libraryJobs.isEmpty()) onOpenFilePicker else null,
					)
				}
			} else {
				item(key = "scrape-jobs-section-header", contentType = "section-header") {
					IosSectionLabel(
						title = "任务记录 · ${filteredJobs.size}",
						modifier = Modifier.padding(bottom = 8.dp),
					)
				}
				itemsIndexed(
					items = filteredJobs,
					key = { _, it -> "job-${it.id}" },
					contentType = { _, _ -> "library-job" },
				) { index, job ->
					IosStudioScrapeJobRow(
						job = job,
						itemShape = iosStudioGroupedItemShape(index, filteredJobs.size),
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
						onShowRunningHint = onShowRunningDeleteHint,
					)
				}
			}
		}

		androidx.compose.animation.AnimatedVisibility(
			visible = multiSelectMode,
			enter = slideInVertically { it } + fadeIn(),
			exit = slideOutVertically { it } + fadeOut(),
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.padding(horizontal = 16.dp)
				.padding(bottom = safeBottomDp + 8.dp),
		) {
			IosStudioScrapeBatchDock(
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
				onBatchDelete = { pendingBatchDeleteConfirm = true },
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
					tint = MaterialTheme.colorScheme.error,
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
						contentColor = MaterialTheme.colorScheme.onError,
					),
				) {
					Text("确认删除")
				}
			},
			dismissButton = {
				TextButton(onClick = { pendingSingleDeleteJob = null }) {
					Text("取消")
				}
			},
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
					tint = MaterialTheme.colorScheme.error,
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
						onBatchDeleteSelected(selectedDeletableIds)
					},
					colors = ButtonDefaults.buttonColors(
						containerColor = MaterialTheme.colorScheme.error,
						contentColor = MaterialTheme.colorScheme.onError,
					),
				) {
					Text("批量删除 ($count)")
				}
			},
			dismissButton = {
				TextButton(onClick = { pendingBatchDeleteConfirm = false }) {
					Text("取消")
				}
			},
		)
	}

	if (pendingClearFinishedConfirm) {
		AlertDialog(
			onDismissRequest = { pendingClearFinishedConfirm = false },
			icon = {
				Icon(
					imageVector = Icons.Outlined.DeleteSweep,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary,
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
					},
				) {
					Text("确认清理")
				}
			},
			dismissButton = {
				TextButton(onClick = { pendingClearFinishedConfirm = false }) {
					Text("取消")
				}
			},
		)
	}
}

@Composable
private fun IosStudioScrapeBatchDock(
	selectedCount: Int,
	allSelected: Boolean,
	hasSelectable: Boolean,
	busy: Boolean,
	onExit: () -> Unit,
	onToggleSelectAll: () -> Unit,
	onBatchDelete: () -> Unit,
) {
	Surface(
		modifier = Modifier
			.widthIn(max = 560.dp)
			.fillMaxWidth(),
		shape = RoundedCornerShape(18.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		border = BorderStroke(
			width = IosHairlineWidth,
			color = MaterialTheme.colorScheme.outline.copy(alpha = 0.40f),
		),
		shadowElevation = 6.dp,
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 12.dp, vertical = 9.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Row(
				modifier = Modifier.weight(1f),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(4.dp),
			) {
				IconButton(
					onClick = onExit,
					modifier = Modifier.size(28.dp),
				) {
					Icon(
						imageVector = Icons.Outlined.Close,
						contentDescription = "退出多选",
						modifier = Modifier.size(16.dp),
					)
				}
				Text(
					text = "已选 $selectedCount 项",
					style = MaterialTheme.typography.labelLarge.tabularNums(),
					fontWeight = FontWeight.SemiBold,
					maxLines = 1,
				)
			}

			TextButton(
				onClick = onToggleSelectAll,
				enabled = hasSelectable && !busy,
				contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
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
					contentColor = MaterialTheme.colorScheme.onError,
				),
			) {
				Icon(
					imageVector = Icons.Outlined.Delete,
					contentDescription = null,
					modifier = Modifier.size(15.dp),
				)
				Spacer(Modifier.width(4.dp))
				Text(
					text = "删除 ($selectedCount)",
					style = MaterialTheme.typography.labelMedium.tabularNums(),
					fontWeight = FontWeight.SemiBold,
					maxLines = 1,
				)
			}
		}
	}
}

@Composable
private fun IosStudioScrapeJobRow(
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
	onShowRunningHint: () -> Unit,
) {
	val statusLower = job.status.trim().lowercase()
	val isRunning = statusLower in IosStudioScrapeRunningStates
	val isNeedsReview = statusLower == "needs_review"
	val canApply = statusLower in setOf("preview_ready", "ready")
	val canCancel = isRunning && statusLower != "restoring"
	val canRetry = statusLower in setOf("failed", "partial", "needs_attention", "cancelled")

	val statusColor = when {
		statusLower in IosStudioScrapeDoneStates -> MaterialTheme.colorScheme.meloPositive
		statusLower in IosStudioScrapeErrorStates -> MaterialTheme.colorScheme.error
		statusLower in IosStudioScrapeReviewStates -> MaterialTheme.colorScheme.meloGoldText
		else -> MaterialTheme.colorScheme.primary
	}

	val createdAtText = remember(job.createdAt) {
		if (job.createdAt > 0L) formatIosJsonTimestamp(JsonPrimitive(job.createdAt)) else ""
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
				},
			),
		shape = itemShape,
		color = when {
			multiSelectMode && isChecked -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
			isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
			else -> MaterialTheme.colorScheme.surfaceContainer
		},
	) {
		Column(modifier = Modifier.fillMaxWidth()) {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 14.dp, vertical = 12.dp),
				verticalArrangement = Arrangement.spacedBy(6.dp),
			) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically,
				) {
					Row(
						modifier = Modifier.weight(1f),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(6.dp),
					) {
						if (multiSelectMode) {
							Checkbox(
								checked = isChecked,
								enabled = !isRunning,
								onCheckedChange = null,
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
							overflow = TextOverflow.Ellipsis,
						)
					}
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(6.dp),
					) {
						IosStudioStatusDot(
							label = formatIosStatusLabel(job.status),
							color = statusColor,
						)
						if (!multiSelectMode) {
							IconButton(
								onClick = onDelete,
								enabled = !isRunning && !jobsLoading,
								modifier = Modifier.size(28.dp),
							) {
								Icon(
									imageVector = Icons.Outlined.Delete,
									contentDescription = if (isRunning) "运行中不可删除" else "删除记录",
									tint = if (isRunning) {
										MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
									} else {
										MaterialTheme.colorScheme.onSurfaceVariant
									},
									modifier = Modifier.size(15.dp),
								)
							}
						}
					}
				}

				val metaLine = listOfNotNull(
					createdAtText.takeIf { it.isNotBlank() },
					job.detail.takeIf { it.isNotBlank() },
				).joinToString(" · ")
				if (metaLine.isNotEmpty()) {
					Text(
						text = metaLine,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
					)
				}

				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically,
				) {
					if (isRunning) {
						Text(
							text = "运行中请先取消",
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					} else {
						Spacer(Modifier.width(1.dp))
					}
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(4.dp),
					) {
						if (isNeedsReview) {
							FilledTonalButton(
								onClick = onClick,
								contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
								modifier = Modifier.height(30.dp),
							) {
								Text("去确认候选", style = MaterialTheme.typography.labelMedium)
							}
						} else {
							TextButton(
								onClick = onClick,
								contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
							) {
								Text("对比 / 候选", style = MaterialTheme.typography.labelMedium)
							}
						}
						if (canApply) {
							FilledTonalButton(
								onClick = onApply,
								contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
								modifier = Modifier.height(30.dp),
							) {
								Text("确认归档", style = MaterialTheme.typography.labelMedium)
							}
						} else if (canCancel) {
							TextButton(
								onClick = onCancel,
								contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
							) {
								Text(
									text = "取消",
									style = MaterialTheme.typography.labelMedium,
									color = MaterialTheme.colorScheme.error,
								)
							}
						} else if (canRetry) {
							TextButton(
								onClick = onRetry,
								contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
							) {
								Text("重试", style = MaterialTheme.typography.labelMedium)
							}
						}
					}
				}
			}

			if (showBottomDivider) {
				IosHairlineDivider(startIndent = 14.dp)
			}
		}
	}
}

// ==========================================
// 服务端曲库文件选择抽屉（发起刮削预览 / 直接整理归档）
// ==========================================

@Composable
internal fun IosStudioLibraryFilePickerSheet(
	state: MeloStudioUiState,
	normalizedFolders: List<String>,
	onDismiss: () -> Unit,
	onQuerySubmit: (String) -> Unit,
	onFolderSelect: (String) -> Unit,
	onLoadMore: () -> Unit,
	onToggleFile: (String) -> Unit,
	onSelectAllFiles: () -> Unit,
	onClearSelection: () -> Unit,
	onPreviewScrape: () -> Unit,
	onDirectOrganize: () -> Unit,
	onScopeScrape: () -> Unit,
) {
	var queryInput by remember(state.fileQuery) { mutableStateOf(state.fileQuery) }
	val focusManager = LocalFocusManager.current
	val normalizedCurrentFolder = remember(state.currentFolder) {
		iosStudioNormalizeFolder(state.currentFolder)
	}
	val allSelected = state.libraryFiles.isNotEmpty() &&
		state.selectedFilePaths.size == state.libraryFiles.size
	val hasNextPage = !state.nextCursor.isNullOrBlank()

	ModalBottomSheet(
		onDismissRequest = onDismiss,
		containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp)
				.padding(bottom = 24.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = "选择曲库文件",
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.Bold,
					)
					Text(
						text = "已加载 ${state.libraryFiles.size} 首${if (hasNextPage) "（仅当前已加载页）" else ""} · 已选 ${state.selectedFilePaths.size}",
						style = MaterialTheme.typography.labelSmall.tabularNums(),
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				TextButton(
					onClick = if (allSelected) onClearSelection else onSelectAllFiles,
					enabled = state.libraryFiles.isNotEmpty(),
					contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
				) {
					Text(if (allSelected) "取消全选" else "全选当前 (${state.libraryFiles.size})")
				}
			}

			// 极简发丝搜索栏
			Surface(
				shape = CircleShape,
				color = MaterialTheme.colorScheme.surfaceContainer,
				border = BorderStroke(
					width = IosHairlineWidth,
					color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
				),
			) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.height(42.dp)
						.padding(start = 14.dp, end = 6.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Icon(
						imageVector = Icons.Outlined.Search,
						contentDescription = null,
						tint = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.size(17.dp),
					)
					Spacer(Modifier.width(8.dp))
					Box(
						modifier = Modifier.weight(1f),
						contentAlignment = Alignment.CenterStart,
					) {
						if (queryInput.isEmpty()) {
							Text(
								text = "按文件名或路径筛选",
								style = MaterialTheme.typography.bodyMedium,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
							)
						}
						BasicTextField(
							value = queryInput,
							onValueChange = { queryInput = it },
							singleLine = true,
							textStyle = MaterialTheme.typography.bodyMedium.copy(
								color = MaterialTheme.colorScheme.onSurface,
							),
							cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
							keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
							keyboardActions = KeyboardActions(
								onSearch = {
									focusManager.clearFocus()
									onQuerySubmit(queryInput)
								},
							),
							modifier = Modifier.fillMaxWidth(),
						)
					}
					if (queryInput.isNotEmpty()) {
						IconButton(
							onClick = {
								queryInput = ""
								onQuerySubmit("")
							},
							modifier = Modifier.size(30.dp),
						) {
							Icon(
								imageVector = Icons.Outlined.Close,
								contentDescription = "清除",
								modifier = Modifier.size(15.dp),
							)
						}
					}
					FilledTonalButton(
						onClick = {
							focusManager.clearFocus()
							onQuerySubmit(queryInput)
						},
						contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
						modifier = Modifier.height(28.dp),
					) {
						Text("筛选", style = MaterialTheme.typography.labelMedium)
					}
				}
			}

			if (normalizedFolders.isNotEmpty() || normalizedCurrentFolder.isNotEmpty()) {
				LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					item(key = "all-folders", contentType = "folder-chip") {
						val selected = normalizedCurrentFolder.isEmpty()
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
								width = IosHairlineWidth,
								color = if (selected) {
									MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
								} else {
									MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
								},
							),
						) {
							Row(
								modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(4.dp),
							) {
								Icon(
									imageVector = Icons.Outlined.Folder,
									contentDescription = null,
									tint = if (selected) {
										MaterialTheme.colorScheme.primary
									} else {
										MaterialTheme.colorScheme.onSurfaceVariant
									},
									modifier = Modifier.size(14.dp),
								)
								Text(
									text = "全部目录",
									style = MaterialTheme.typography.labelMedium,
									color = if (selected) {
										MaterialTheme.colorScheme.primary
									} else {
										MaterialTheme.colorScheme.onSurfaceVariant
									},
								)
							}
						}
					}
					items(
						items = normalizedFolders,
						key = { "folder-$it" },
						contentType = { "folder-chip" },
					) { folderPath ->
						val selected = normalizedCurrentFolder == folderPath
						Surface(
							modifier = Modifier
								.clip(CircleShape)
								.clickable { onFolderSelect(folderPath) },
							shape = CircleShape,
							color = if (selected) {
								MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
							} else {
								MaterialTheme.colorScheme.surfaceContainer
							},
							border = BorderStroke(
								width = IosHairlineWidth,
								color = if (selected) {
									MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
								} else {
									MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
								},
							),
						) {
							Text(
								text = folderPath,
								style = MaterialTheme.typography.labelMedium,
								color = if (selected) {
									MaterialTheme.colorScheme.primary
								} else {
									MaterialTheme.colorScheme.onSurfaceVariant
								},
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
								modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
							)
						}
					}
				}
			}

			LazyColumn(
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(min = 150.dp, max = 270.dp),
			) {
				if (state.filesLoading && state.libraryFiles.isEmpty()) {
					item(key = "picker-loading", contentType = "loading") {
						Box(
							modifier = Modifier
								.fillMaxWidth()
								.padding(vertical = 32.dp),
							contentAlignment = Alignment.Center,
						) {
							CircularProgressIndicator()
						}
					}
				} else if (state.libraryFiles.isEmpty()) {
					item(key = "picker-empty", contentType = "empty") {
						IosQuietEmptyState(
							message = "当前目录下暂无音频文件",
							icon = Icons.Outlined.Folder,
						)
					}
				} else {
					itemsIndexed(
						items = state.libraryFiles,
						key = { _, f -> f.path },
						contentType = { _, _ -> "library-file" },
					) { index, file ->
						IosStudioLibraryFileRow(
							file = file,
							checked = file.path in state.selectedFilePaths,
							itemShape = iosStudioGroupedItemShape(index, state.libraryFiles.size),
							showBottomDivider = index < state.libraryFiles.lastIndex,
							onToggle = { onToggleFile(file.path) },
						)
					}

					if (!state.nextCursor.isNullOrBlank()) {
						item(key = "picker-load-more", contentType = "load-more") {
							Box(
								modifier = Modifier
									.fillMaxWidth()
									.padding(top = 8.dp),
								contentAlignment = Alignment.Center,
							) {
								OutlinedButton(
									onClick = onLoadMore,
									enabled = !state.filesLoading,
								) {
									Text(if (state.filesLoading) "加载中…" else "加载更多文件")
								}
							}
						}
					}
				}
			}

			if (hasNextPage) {
				Surface(
					shape = RoundedCornerShape(12.dp),
					color = MaterialTheme.colorScheme.surfaceContainer,
					border = BorderStroke(
						width = IosHairlineWidth,
						color = MaterialTheme.colorScheme.outline.copy(alpha = 0.30f),
					),
				) {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(horizontal = 12.dp, vertical = 8.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(8.dp),
					) {
						Text(
							text = "还有更多文件未加载，上方“全选当前”仅选中已加载的 ${state.libraryFiles.size} 首；可继续加载更多，或直接整目录 / 全库刮削。",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.weight(1f),
						)
						TextButton(
							onClick = onLoadMore,
							enabled = !state.filesLoading,
							contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
						) {
							Text(if (state.filesLoading) "加载中…" else "加载更多")
						}
					}
				}
			}

			FilledTonalButton(
				onClick = onScopeScrape,
				enabled = !state.jobsLoading,
				modifier = Modifier.fillMaxWidth(),
			) {
				Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
				Spacer(Modifier.width(6.dp))
				Text(
					text = if (normalizedCurrentFolder.isNotEmpty()) {
						"整目录刮削 (${normalizedCurrentFolder})"
					} else {
						"全库刮削（当前目录全部曲目）"
					},
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}

			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				OutlinedButton(
					modifier = Modifier.weight(1f),
					enabled = state.selectedFilePaths.isNotEmpty() && !state.jobsLoading,
					onClick = onPreviewScrape,
				) {
					Text("生成刮削预览", maxLines = 1, softWrap = false)
				}
				Button(
					modifier = Modifier.weight(1f),
					enabled = state.selectedFilePaths.isNotEmpty() && !state.jobsLoading,
					onClick = onDirectOrganize,
				) {
					Text("直接整理归档", maxLines = 1, softWrap = false)
				}
			}
		}
	}
}

@Composable
private fun IosStudioLibraryFileRow(
	file: MeloLibraryFile,
	checked: Boolean,
	itemShape: Shape,
	showBottomDivider: Boolean,
	onToggle: () -> Unit,
) {
	val metaTitle = file.metadata["title"]?.takeIf { it.isNotBlank() }
		?: file.name.ifBlank { file.path.substringAfterLast('/') }
	val subParts = listOfNotNull(
		file.metadata["artist"]?.takeIf { it.isNotBlank() },
		file.metadata["album"]?.takeIf { it.isNotBlank() },
		file.folder.ifBlank { file.root }.takeIf { it.isNotBlank() },
		file.size.takeIf { it > 0L }?.let(::formatBytesHuman),
	)

	Surface(
		modifier = Modifier
			.fillMaxWidth()
			.clip(itemShape)
			.clickable(onClick = onToggle),
		shape = itemShape,
		color = if (checked) {
			MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
		} else {
			MaterialTheme.colorScheme.surfaceContainer
		},
	) {
		Column(modifier = Modifier.fillMaxWidth()) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 12.dp, vertical = 10.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(10.dp),
			) {
				Checkbox(checked = checked, onCheckedChange = null)
				Column(
					modifier = Modifier.weight(1f),
					verticalArrangement = Arrangement.spacedBy(2.dp),
				) {
					Text(
						text = metaTitle,
						style = MaterialTheme.typography.bodyMedium,
						fontWeight = FontWeight.Medium,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
					Text(
						text = subParts.joinToString(" · ").ifEmpty { file.path },
						style = MaterialTheme.typography.bodySmall.tabularNums(),
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
				if (file.lockedFields.isNotEmpty()) {
					Icon(
						imageVector = Icons.Outlined.Lock,
						contentDescription = "已锁定字段",
						tint = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.size(15.dp),
					)
				}
			}
			if (showBottomDivider) {
				IosHairlineDivider(startIndent = 52.dp)
			}
		}
	}
}

// ==========================================
// 刮削详情对比、多源候选切换与历史版本回滚抽屉
// ==========================================

@Composable
internal fun IosStudioScrapeDiffSheet(
	job: MeloLibraryJob?,
	jobsLoading: Boolean,
	fileTagVersions: Map<String, List<MeloTagVersion>>,
	errorMessage: String? = null,
	noticeMessage: String? = null,
	onDismissError: () -> Unit = {},
	onDismissNotice: () -> Unit = {},
	onDismiss: () -> Unit,
	onRefreshJob: (String) -> Unit,
	onPreviewCandidate: (jobId: String, itemKey: String, candidateId: String) -> Unit,
	onKeepExistingForPending: (jobId: String) -> Unit = {},
	onSearchItemCandidates: (jobId: String, itemId: String, query: String) -> Unit = { _, _, _ -> },
	onLoadFileVersions: (path: String) -> Unit,
	onRestoreFileVersion: (versionJobId: String, itemId: String) -> Unit,
	onApplyJob: (jobId: String, choices: Map<String, String>, autoTriggerScan: Boolean) -> Unit,
	onCancelJob: (String) -> Unit,
	onRetryJob: (String) -> Unit,
) {
	val candidateChoices = remember(job?.id) { mutableStateMapOf<String, String>() }
	val lastServerChosen = remember(job?.id) { mutableStateMapOf<String, String?>() }
	LaunchedEffect(job?.id, job?.items) {
		job?.items?.forEach { item ->
			val key = item.id.ifBlank { item.originalPath.ifBlank { item.path } }
			if (key.isBlank()) return@forEach
			val serverChosen = item.chosen?.takeIf { it.isNotBlank() }
			if (key !in lastServerChosen || lastServerChosen[key] != serverChosen) {
				lastServerChosen[key] = serverChosen
				if (serverChosen != null) {
					candidateChoices[key] = serverChosen
				} else {
					candidateChoices.remove(key)
				}
			}
		}
	}

	var autoScanAfterApply by rememberSaveable(job?.id) { mutableStateOf(true) }
	val expandedVersionPaths = remember(job?.id) { mutableStateMapOf<String, Boolean>() }

	ModalBottomSheet(
		onDismissRequest = onDismiss,
		containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
	) {
		if (job == null) {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp, vertical = 24.dp),
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.spacedBy(12.dp),
			) {
				if (!errorMessage.isNullOrBlank()) {
					Surface(
						shape = RoundedCornerShape(12.dp),
						color = MaterialTheme.colorScheme.errorContainer,
					) {
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.padding(horizontal = 12.dp, vertical = 10.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(8.dp),
						) {
							Text(
								text = errorMessage,
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onErrorContainer,
								modifier = Modifier.weight(1f),
							)
							IconButton(onClick = onDismissError, modifier = Modifier.size(24.dp)) {
								Icon(Icons.Outlined.Close, contentDescription = "关闭错误", modifier = Modifier.size(16.dp))
							}
						}
					}
				}
				CircularProgressIndicator()
			}
			return@ModalBottomSheet
		}

		val statusLower = job.status.trim().lowercase()
		val isRunning = statusLower in MeloStudioUiState.RUNNING_LIBRARY_JOB_STATUSES
		val canApply = statusLower in (IosStudioScrapeReviewStates + "partial") ||
			job.items.any { it.status.trim().lowercase() in IosStudioScrapeReviewStates }
		val canCancel = statusLower in IosStudioScrapeRunningStates && statusLower != "restoring"
		val canRetry = statusLower in setOf("failed", "partial", "needs_attention", "cancelled")
		val pendingReviewCount = job.items.count {
			it.status.trim().lowercase() == "needs_review" && it.id.isNotBlank()
		}
		val readyCount = job.items.count { item ->
			val itemStatus = item.status.trim().lowercase()
			val key = item.id.ifBlank { item.originalPath.ifBlank { item.path } }
			val choice = candidateChoices[key] ?: item.chosen
			itemStatus == "preview_ready" ||
				itemStatus == "ready" ||
				(itemStatus == "needs_review" && !choice.isNullOrBlank())
		}

		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp)
				.padding(bottom = 24.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = "刮削详情与归档 (${job.items.size} 项)",
						style = MaterialTheme.typography.titleMedium.tabularNums(),
						fontWeight = FontWeight.Bold,
					)
					Text(
						text = "${formatIosStatusLabel(job.status)} · ${job.processed}/${job.total}",
						style = MaterialTheme.typography.bodySmall.tabularNums(),
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				if (jobsLoading) {
					CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
				} else {
					IconButton(
						onClick = { onRefreshJob(job.id) },
						modifier = Modifier.size(32.dp),
					) {
						Icon(
							imageVector = Icons.Outlined.Refresh,
							contentDescription = "刷新任务",
							modifier = Modifier.size(18.dp),
						)
					}
				}
			}

			if (isRunning) {
				Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
					if (job.total > 0) {
						LinearProgressIndicator(
							progress = { (job.processed.toFloat() / job.total.toFloat()).coerceIn(0f, 1f) },
							modifier = Modifier.fillMaxWidth(),
						)
					} else {
						LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
					}
					if (job.detail.isNotBlank()) {
						Text(
							text = job.detail,
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							maxLines = 2,
							overflow = TextOverflow.Ellipsis,
						)
					}
				}
			}

			if (!errorMessage.isNullOrBlank()) {
				Surface(
					shape = RoundedCornerShape(12.dp),
					color = MaterialTheme.colorScheme.errorContainer,
				) {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(horizontal = 12.dp, vertical = 8.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(8.dp),
					) {
						Text(
							text = errorMessage,
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onErrorContainer,
							modifier = Modifier.weight(1f),
						)
						IconButton(onClick = onDismissError, modifier = Modifier.size(24.dp)) {
							Icon(
								imageVector = Icons.Outlined.Close,
								contentDescription = "关闭错误",
								tint = MaterialTheme.colorScheme.onErrorContainer,
								modifier = Modifier.size(15.dp),
							)
						}
					}
				}
			}

			if (!noticeMessage.isNullOrBlank()) {
				Surface(
					shape = RoundedCornerShape(12.dp),
					color = MaterialTheme.colorScheme.surfaceContainer,
					border = BorderStroke(
						width = IosHairlineWidth,
						color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
					),
				) {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(horizontal = 12.dp, vertical = 8.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(8.dp),
					) {
						Text(
							text = noticeMessage,
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurface,
							modifier = Modifier.weight(1f),
						)
						IconButton(onClick = onDismissNotice, modifier = Modifier.size(24.dp)) {
							Icon(
								imageVector = Icons.Outlined.Close,
								contentDescription = "关闭提示",
								tint = MaterialTheme.colorScheme.onSurfaceVariant,
								modifier = Modifier.size(15.dp),
							)
						}
					}
				}
			}

			if (pendingReviewCount > 0) {
				OutlinedButton(
					onClick = { onKeepExistingForPending(job.id) },
					enabled = !jobsLoading,
					modifier = Modifier.fillMaxWidth(),
				) {
					Text("全部待确认项保留现有标签 ($pendingReviewCount)")
				}
			}

			if (job.warnings.isNotEmpty()) {
				Surface(
					shape = RoundedCornerShape(12.dp),
					color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
				) {
					Column(
						modifier = Modifier
							.fillMaxWidth()
							.padding(10.dp),
						verticalArrangement = Arrangement.spacedBy(3.dp),
					) {
						job.warnings.forEach { warn ->
							Text(
								text = "• $warn",
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onErrorContainer,
							)
						}
					}
				}
			}

			LazyColumn(
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(min = 120.dp, max = 330.dp),
				verticalArrangement = Arrangement.spacedBy(10.dp),
			) {
				if (job.items.isEmpty()) {
					item(key = "job-items-pending", contentType = "pending") {
						Text(
							text = job.detail.ifBlank { "正在扫描分析…" },
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.padding(vertical = 12.dp),
						)
					}
				} else {
					itemsIndexed(
						items = job.items,
						key = { idx, item -> item.id.ifBlank { item.path.ifBlank { "item-$idx" } } },
						contentType = { _, _ -> "scrape-diff-item" },
					) { _, item ->
						val itemKey = item.id.ifBlank { item.originalPath.ifBlank { item.path } }
						val versionTargetPath = item.path.ifBlank { item.originalPath }
						val versionsForFile = fileTagVersions[versionTargetPath]
						val versionsExpanded = expandedVersionPaths[versionTargetPath] == true

						IosStudioScrapeDiffItemCard(
							item = item,
							selectedCandidateId = candidateChoices[itemKey] ?: item.chosen,
							jobsLoading = jobsLoading,
							versionsExpanded = versionsExpanded,
							versions = versionsForFile,
							onSelectCandidate = { candId ->
								candidateChoices[itemKey] = candId
								onPreviewCandidate(job.id, itemKey, candId)
							},
							onSearchCandidates = { query ->
								if (item.id.isNotBlank()) {
									onSearchItemCandidates(job.id, item.id, query)
								}
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
							},
						)
					}
				}
			}

			if (canApply || canCancel || canRetry) {
				IosHairlineDivider()
			}

			if (canApply) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.clip(RoundedCornerShape(10.dp))
						.clickable { autoScanAfterApply = !autoScanAfterApply }
						.padding(horizontal = 4.dp, vertical = 2.dp),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						text = "归档后自动扫库",
						style = MaterialTheme.typography.bodyMedium,
						fontWeight = FontWeight.Medium,
						modifier = Modifier.weight(1f),
					)
					Switch(
						checked = autoScanAfterApply,
						onCheckedChange = { autoScanAfterApply = it },
					)
				}
				if (readyCount == 0) {
					Text(
						text = "当前暂无可归档曲目（0 项）：请先为待确认曲目选择候选或“保留现有标签”",
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.error,
					)
				}
			}

			if (canApply || canCancel || canRetry) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.spacedBy(8.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					if (canCancel) {
						OutlinedButton(
							onClick = { onCancelJob(job.id) },
							modifier = Modifier.weight(1f),
						) {
							Text("取消任务", maxLines = 1, softWrap = false)
						}
					}
					if (canRetry) {
						OutlinedButton(
							onClick = { onRetryJob(job.id) },
							modifier = Modifier.weight(1f),
						) {
							Text("重试失败项", maxLines = 1, softWrap = false)
						}
					}
					if (canApply) {
						Button(
							onClick = {
								onApplyJob(
									job.id,
									candidateChoices.filterValues { it.isNotBlank() },
									autoScanAfterApply,
								)
							},
							enabled = !jobsLoading && readyCount > 0,
							modifier = Modifier.weight(1.6f),
						) {
							Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp))
							Spacer(Modifier.width(6.dp))
							Text(
								text = when {
									readyCount == 0 -> "暂无可归档 (0)"
									autoScanAfterApply -> "确认归档并扫库 ($readyCount)"
									else -> "确认归档 ($readyCount)"
								},
								maxLines = 1,
								softWrap = false,
							)
						}
					}
				}
			}
		}
	}
}

@Composable
private fun IosStudioScrapeDiffItemCard(
	item: MeloLibraryJobItem,
	selectedCandidateId: String?,
	jobsLoading: Boolean,
	versionsExpanded: Boolean,
	versions: List<MeloTagVersion>?,
	onSelectCandidate: (String) -> Unit,
	onSearchCandidates: (query: String) -> Unit,
	onToggleVersions: () -> Unit,
	onRestoreVersion: (versionJobId: String) -> Unit,
) {
	val focusManager = LocalFocusManager.current
	val itemStatusLower = item.status.trim().lowercase()
	val isNeedsReview = itemStatusLower == "needs_review"
	val canReviewTools = isNeedsReview ||
		itemStatusLower in IosStudioScrapeReviewStates ||
		selectedCandidateId == "existing"
	val defaultSearchQuery = remember(item.id) {
		val artist = item.before["artist"].orEmpty().trim()
		val title = item.before["title"].orEmpty().trim()
		listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
			.ifBlank { item.name.substringBeforeLast('.').ifBlank { item.path.substringAfterLast('/').substringBeforeLast('.') } }
	}
	var reSearchExpanded by remember(item.id) { mutableStateOf(false) }
	var reSearchQuery by remember(item.id) { mutableStateOf(defaultSearchQuery) }

	IosGroupedCard(modifier = Modifier.animateContentSize()) {
		Column(
			modifier = Modifier.padding(12.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = item.name.ifBlank { item.path.substringAfterLast('/') },
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = FontWeight.SemiBold,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f),
				)
				Spacer(Modifier.width(8.dp))
				IosStudioStatusDot(
					label = formatIosStatusLabel(item.status),
					color = MaterialTheme.colorScheme.primary,
				)
			}

			val orig = item.originalPath.ifBlank { item.path }
			if (item.target.isNotBlank() && item.target != orig) {
				Surface(
					shape = RoundedCornerShape(10.dp),
					color = MaterialTheme.colorScheme.surfaceContainerLow,
				) {
					Column(
						modifier = Modifier
							.fillMaxWidth()
							.padding(8.dp),
						verticalArrangement = Arrangement.spacedBy(2.dp),
					) {
						Text(
							text = orig,
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							textDecoration = TextDecoration.LineThrough,
						)
						Text(
							text = item.target,
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.primary,
							fontWeight = FontWeight.Medium,
						)
					}
				}
			}

			if (item.locks.isNotEmpty()) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(4.dp),
				) {
					Icon(
						imageVector = Icons.Outlined.Lock,
						contentDescription = null,
						tint = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.size(13.dp),
					)
					Text(
						text = "已锁定：${item.locks.joinToString("、", transform = ::translateIosTagKey)}",
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
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
							verticalAlignment = Alignment.CenterVertically,
						) {
							Text(
								text = "${translateIosTagKey(key)}:",
								style = MaterialTheme.typography.labelSmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
							Text(
								text = oldVal,
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								textDecoration = TextDecoration.LineThrough,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
							)
							Text(
								text = "→ $newVal",
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.primary,
								fontWeight = FontWeight.Medium,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
								modifier = Modifier.weight(1f),
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
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						itemsIndexed(
							items = item.candidates,
							key = { idx, c -> c.id.ifBlank { "cand-$idx" } },
							contentType = { _, _ -> "candidate-pill" },
						) { _, cand ->
							IosStudioCandidateChip(
								candidate = cand,
								selected = selectedCandidateId == cand.id,
								enabled = !jobsLoading,
								onClick = { onSelectCandidate(cand.id) },
							)
						}
					}
				}
			}

			if (canReviewTools) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.spacedBy(8.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					val keepExistingSelected = selectedCandidateId == "existing"
					Surface(
						onClick = { if (!jobsLoading) onSelectCandidate("existing") },
						shape = CircleShape,
						color = if (keepExistingSelected) {
							MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
						} else {
							MaterialTheme.colorScheme.surfaceContainerLow
						},
						border = BorderStroke(
							width = if (keepExistingSelected) 1.dp else IosHairlineWidth,
							color = if (keepExistingSelected) {
								MaterialTheme.colorScheme.primary.copy(alpha = 0.70f)
							} else {
								MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
							},
						),
					) {
						Row(
							modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(4.dp),
						) {
							if (keepExistingSelected) {
								Icon(
									imageVector = Icons.Outlined.Check,
									contentDescription = null,
									tint = MaterialTheme.colorScheme.primary,
									modifier = Modifier.size(13.dp),
								)
							}
							Text(
								text = "保留现有标签",
								style = MaterialTheme.typography.labelMedium,
								color = if (keepExistingSelected) {
									MaterialTheme.colorScheme.primary
								} else {
									MaterialTheme.colorScheme.onSurface
								},
							)
						}
					}
					if (item.id.isNotBlank()) {
						TextButton(
							onClick = { reSearchExpanded = !reSearchExpanded },
							enabled = !jobsLoading,
							contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
							modifier = Modifier.height(30.dp),
						) {
							Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(14.dp))
							Spacer(Modifier.width(4.dp))
							Text(
								text = if (reSearchExpanded) "收起搜索" else "重新搜索候选",
								style = MaterialTheme.typography.labelMedium,
							)
						}
					}
				}
				androidx.compose.animation.AnimatedVisibility(visible = reSearchExpanded && item.id.isNotBlank()) {
					Row(
						modifier = Modifier.fillMaxWidth(),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(8.dp),
					) {
						OutlinedTextField(
							value = reSearchQuery,
							onValueChange = { reSearchQuery = it },
							placeholder = { Text("输入歌名 / 歌手重新匹配") },
							singleLine = true,
							keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
							keyboardActions = KeyboardActions(
								onSearch = {
									val clean = reSearchQuery.trim()
									if (clean.isNotEmpty() && !jobsLoading) {
										focusManager.clearFocus()
										onSearchCandidates(clean)
									}
								},
							),
							modifier = Modifier.weight(1f),
						)
						FilledTonalButton(
							onClick = {
								val clean = reSearchQuery.trim()
								if (clean.isNotEmpty()) {
									focusManager.clearFocus()
									onSearchCandidates(clean)
								}
							},
							enabled = !jobsLoading && reSearchQuery.trim().isNotEmpty(),
							contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
						) {
							Text("搜索", maxLines = 1, softWrap = false)
						}
					}
				}
			}

			if (item.error.isNotBlank()) {
				Text(
					text = "错误：${item.error}",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.error,
				)
			}

			val versionPath = item.path.ifBlank { item.originalPath }
			if (versionPath.isNotBlank()) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.End,
				) {
					TextButton(
						onClick = onToggleVersions,
						contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
						modifier = Modifier.height(28.dp),
					) {
						Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(14.dp))
						Spacer(Modifier.width(4.dp))
						Text(
							text = if (versionsExpanded) "收起历史版本" else "历史版本 / 回滚",
							style = MaterialTheme.typography.labelMedium,
						)
					}
				}

				androidx.compose.animation.AnimatedVisibility(visible = versionsExpanded) {
					Column(
						modifier = Modifier.fillMaxWidth(),
						verticalArrangement = Arrangement.spacedBy(6.dp),
					) {
						IosHairlineDivider()
						when {
							versions == null -> {
								Row(
									modifier = Modifier
										.fillMaxWidth()
										.padding(vertical = 8.dp),
									horizontalArrangement = Arrangement.Center,
									verticalAlignment = Alignment.CenterVertically,
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
									modifier = Modifier.padding(vertical = 4.dp),
								)
							}
							else -> {
								versions.forEachIndexed { idx, ver ->
									IosStudioTagVersionRow(
										version = ver,
										enabled = !jobsLoading,
										onRestore = { onRestoreVersion(ver.jobId) },
									)
									if (idx < versions.lastIndex) {
										IosHairlineDivider()
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
private fun IosStudioCandidateChip(
	candidate: MeloMetadataCandidate,
	selected: Boolean,
	enabled: Boolean,
	onClick: () -> Unit,
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
			width = IosHairlineWidth,
			color = if (selected) {
				MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
			} else {
				MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
			},
		),
	) {
		Column(
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
			verticalArrangement = Arrangement.spacedBy(2.dp),
		) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(6.dp),
			) {
				Text(
					text = translateIosSourceName(candidate.source),
					style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
					color = MaterialTheme.colorScheme.primary,
				)
				if (displayScore > 0) {
					Text(
						text = "${displayScore}分",
						style = MaterialTheme.typography.labelSmall.tabularNums(),
						color = if (selected) {
							MaterialTheme.colorScheme.primary
						} else {
							MaterialTheme.colorScheme.onSurfaceVariant
						},
					)
				}
				if (selected) {
					Icon(
						imageVector = Icons.Outlined.Check,
						contentDescription = "已选中",
						tint = MaterialTheme.colorScheme.primary,
						modifier = Modifier.size(13.dp),
					)
				}
			}
			Text(
				text = "${candidate.title.ifBlank { "未知" }} · ${candidate.artist.ifBlank { "-" }}",
				style = MaterialTheme.typography.bodySmall,
				fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.widthIn(max = 176.dp),
			)
			if (candidate.album.isNotBlank()) {
				Text(
					text = candidate.album,
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.widthIn(max = 176.dp),
				)
			}
		}
	}
}

@Composable
private fun IosStudioTagVersionRow(
	version: MeloTagVersion,
	enabled: Boolean,
	onRestore: () -> Unit,
) {
	val timeText = remember(version.createdAt) { formatIosJsonTimestamp(version.createdAt) }
	val changedSummary = remember(version.before, version.after) {
		(version.before.keys + version.after.keys)
			.distinct()
			.filter { version.after[it] != version.before[it] && !version.after[it].isNullOrBlank() }
			.take(3)
			.joinToString(" · ") { k -> "${translateIosTagKey(k)}→${version.after[k].orEmpty()}" }
	}
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Column(
			modifier = Modifier.weight(1f),
			verticalArrangement = Arrangement.spacedBy(2.dp),
		) {
			Text(
				text = listOfNotNull(
					timeText.takeIf { it.isNotBlank() } ?: "历史快照",
					when (version.action.lowercase()) {
						"scrape" -> "刮削"
						"organize" -> "整理"
						"both" -> "刮削并整理"
						"edit" -> "编辑标签"
						else -> version.action.takeIf { it.isNotBlank() }
					},
					formatIosStatusLabel(version.status),
				).joinToString(" · "),
				style = MaterialTheme.typography.labelMedium.tabularNums(),
				fontWeight = FontWeight.Medium,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			if (changedSummary.isNotBlank()) {
				Text(
					text = changedSummary,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
		if (version.restorable) {
			FilledTonalButton(
				onClick = onRestore,
				enabled = enabled,
				shape = CircleShape,
				contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
				modifier = Modifier.height(28.dp),
			) {
				Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(13.dp))
				Spacer(Modifier.width(4.dp))
				Text("回滚", style = MaterialTheme.typography.labelSmall)
			}
		}
	}
}
