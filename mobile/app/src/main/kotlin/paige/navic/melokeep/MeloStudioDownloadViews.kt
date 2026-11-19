package paige.navic.melokeep

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.agonie0v0.melokeep.shared.MeloDownloadJob
import io.github.agonie0v0.melokeep.shared.MeloLibraryJob
import paige.navic.ui.component.common.CoverArt
import paige.navic.ui.component.common.Waveform
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.Delete
import paige.navic.ui.icons.outlined.Refresh
import paige.navic.ui.theme.defaultFont
import paige.navic.ui.theme.positive
import paige.navic.ui.theme.tabularNums
import paige.navic.ui.theme.warning
import paige.navic.util.toFileSize
import kotlin.math.roundToInt

/**
 * 工坊「下载到库（服务端）」任务队列视图：紧凑总览控制卡 + 极简分组下载列表。
 */
@Composable
internal fun StudioDownloadsTabContent(
	downloads: List<MeloDownloadJob>,
	libraryJobs: List<MeloLibraryJob>,
	ignoredLibraryJobIds: Set<String>,
	loading: Boolean,
	bottomClearance: Dp,
	coverShape: Shape,
	currentSongId: String?,
	isPlayerPaused: Boolean,
	onRefresh: () -> Unit,
	onPauseAll: () -> Unit,
	onResumeAll: () -> Unit,
	onRetryFailed: () -> Unit,
	onClearCompleted: () -> Unit,
	onControl: (id: String, action: String) -> Unit,
	onDelete: (id: String) -> Unit,
	onPreviewPlay: (MeloDownloadJob) -> Unit,
	onGoScrape: (filePath: String, downloadId: String) -> Unit,
	onRescrape: (filePath: String, downloadId: String) -> Unit
) {
	if (loading && downloads.isEmpty()) {
		Box(
			modifier = Modifier
				.fillMaxSize()
				.padding(bottom = bottomClearance),
			contentAlignment = Alignment.Center
		) {
			CircularProgressIndicator()
		}
		return
	}

	if (downloads.isEmpty()) {
		Box(
			modifier = Modifier
				.fillMaxSize()
				.padding(bottom = bottomClearance),
			contentAlignment = Alignment.Center
		) {
			MeloQuietEmptyState(
				message = "暂无服务端下载任务",
				icon = MeloIcons.Cloud,
				actionLabel = "刷新队列",
				onAction = onRefresh
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
				in STUDIO_DOWNLOAD_ACTIVE_STATES -> active++
				in STUDIO_DOWNLOAD_PAUSED_STATES -> paused++
				in STUDIO_DOWNLOAD_FAILED_STATES -> failed++
				in STUDIO_DOWNLOAD_DONE_STATES -> done++
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
			top = 6.dp,
			bottom = bottomClearance
		)
	) {
		item(key = "downloads-command-summary", contentType = "downloads-summary") {
			DownloadsCommandSummaryCard(
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
				modifier = Modifier.padding(bottom = 14.dp)
			)
		}

		item(key = "downloads-section-header", contentType = "section-header") {
			MeloSectionLabel(
				title = "服务端队列 · ${downloads.size}",
				modifier = Modifier.padding(bottom = 8.dp)
			)
		}

		itemsIndexed(
			items = downloads,
			key = { _, job -> job.id },
			contentType = { _, _ -> "download-job" }
		) { index, job ->
			val songId = if (job.track.id.isNotBlank()) {
				"${MeloTrackMapper.ID_PREFIX}${job.track.id}"
			} else {
				""
			}
			val isCurrent = songId.isNotEmpty() && currentSongId == songId
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

			DownloadJobRow(
				job = job,
				associatedScrapeStatus = associatedStatus,
				associatedScrapeDetail = associatedDetail,
				coverShape = coverShape,
				itemShape = studioGroupedItemShape(index, downloads.size),
				showBottomDivider = index < downloads.lastIndex,
				isCurrent = isCurrent,
				isPlaying = isCurrent && !isPlayerPaused,
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
				}
			)
		}
	}
}

@Composable
private fun DownloadsCommandSummaryCard(
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
	modifier: Modifier = Modifier
) {
	MeloGroupedCard(modifier = modifier) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 10.dp),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically
		) {
			Column(
				modifier = Modifier.weight(1f),
				verticalArrangement = Arrangement.spacedBy(2.dp)
			) {
				Text(
					text = if (activeCount > 0) {
						"正在入库 $activeCount 首"
					} else {
						"入库队列 · $totalCount 项"
					},
					style = MaterialTheme.typography.titleSmall.tabularNums().copy(
						fontFamily = defaultFont(grade = 100),
						fontWeight = FontWeight.SemiBold
					),
					color = MaterialTheme.colorScheme.onSurface
				)
				Text(
					text = "进行中 $activeCount · 暂停 $pausedCount · 完成 $doneCount · 失败 $failedCount",
					style = MaterialTheme.typography.labelSmall.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
			}
			IconButton(
				onClick = onRefresh,
				modifier = Modifier.size(32.dp)
			) {
				Icon(
					imageVector = Icons.Outlined.Refresh,
					contentDescription = "刷新下载队列",
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.size(17.dp)
				)
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
				icon = MeloIcons.Pause,
				label = "全部暂停",
				enabled = activeCount > 0,
				onClick = onPauseAll
			)
			StudioBatchControlPill(
				icon = MeloIcons.PlayArrow,
				label = "全部继续",
				enabled = pausedCount > 0,
				onClick = onResumeAll
			)
			StudioBatchControlPill(
				icon = Icons.Outlined.Refresh,
				label = "重试失败",
				enabled = failedCount > 0,
				tint = if (failedCount > 0) MaterialTheme.colorScheme.error else null,
				onClick = onRetryFailed
			)
			StudioBatchControlPill(
				icon = MeloIcons.CleaningServices,
				label = "清理完成",
				enabled = doneCount > 0,
				onClick = onClearCompleted
			)
		}
	}
}

@Composable
private fun DownloadJobRow(
	job: MeloDownloadJob,
	associatedScrapeStatus: String,
	associatedScrapeDetail: String,
	coverShape: Shape,
	itemShape: Shape,
	showBottomDivider: Boolean,
	isCurrent: Boolean,
	isPlaying: Boolean,
	onPause: () -> Unit,
	onResume: () -> Unit,
	onRetry: () -> Unit,
	onDelete: () -> Unit,
	onPreviewPlay: () -> Unit,
	onGoScrape: () -> Unit,
	onRescrape: () -> Unit
) {
	val statusLower = job.status.trim().lowercase()
	val canPause = statusLower in STUDIO_DOWNLOAD_ACTIVE_STATES
	val canResume = statusLower in STUDIO_DOWNLOAD_PAUSED_STATES
	val canRetry = statusLower in STUDIO_DOWNLOAD_FAILED_STATES
	val isDone = statusLower in STUDIO_DOWNLOAD_DONE_STATES

	val statusColor = when {
		isDone -> MaterialTheme.colorScheme.positive
		canRetry -> MaterialTheme.colorScheme.error
		canResume -> MaterialTheme.colorScheme.warning
		else -> MaterialTheme.colorScheme.primary
	}

	val scrapeDotSpec = when {
		associatedScrapeStatus in STUDIO_SCRAPE_REVIEW_STATES ->
			"待审核" to MaterialTheme.colorScheme.warning
		associatedScrapeStatus in STUDIO_SCRAPE_RUNNING_STATES ->
			"整理中" to MaterialTheme.colorScheme.primary
		associatedScrapeStatus == "restored" ->
			"已回滚" to MaterialTheme.colorScheme.onSurfaceVariant
		associatedScrapeStatus in STUDIO_SCRAPE_DONE_STATES ->
			"已整理" to MaterialTheme.colorScheme.positive
		associatedScrapeStatus in STUDIO_SCRAPE_ERROR_STATES ->
			"整理异常" to MaterialTheme.colorScheme.error
		else -> null
	}

	val qualityLabel = remember(job.qualityLabel, job.qualityId, job.track.id) {
		extractQualityBadgeFromLabel(
			qualityLabel = job.qualityLabel.ifBlank { job.qualityId },
			track = job.track
		)?.first
	}

	val sourceText = remember(job.downloadSource, job.track.source) {
		job.downloadSource.ifBlank { job.track.source }
			.takeIf { it.isNotBlank() }
			?.let(::translateMeloSourceName)
	}

	val doneSizeText = remember(isDone, job.totalBytes, job.bytes) {
		if (!isDone) {
			null
		} else {
			(job.totalBytes?.takeIf { it > 0L } ?: job.bytes.takeIf { it > 0L })?.toFileSize()
		}
	}

	Surface(
		modifier = Modifier.fillMaxWidth(),
		shape = itemShape,
		color = if (isCurrent) {
			MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
		} else {
			MaterialTheme.colorScheme.surfaceContainer
		}
	) {
		Column(modifier = Modifier.fillMaxWidth()) {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 14.dp, vertical = 12.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp)
			) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(12.dp)
				) {
					CoverArt(
						coverArtId = job.track.cover,
						modifier = Modifier.size(44.dp),
						shape = coverShape
					)
					Column(
						modifier = Modifier.weight(1f),
						verticalArrangement = Arrangement.spacedBy(2.dp)
					) {
						Text(
							text = job.track.title.ifBlank { "未知曲目" },
							style = MaterialTheme.typography.bodyMedium.copy(
								fontWeight = FontWeight.SemiBold
							),
							color = if (isCurrent) {
								MaterialTheme.colorScheme.primary
							} else {
								MaterialTheme.colorScheme.onSurface
							},
							maxLines = 1,
							overflow = TextOverflow.Ellipsis
						)
						val subtitle = listOfNotNull(
							job.track.artist.takeIf { it.isNotBlank() },
							sourceText,
							qualityLabel,
							doneSizeText
						).joinToString(" · ")
						if (subtitle.isNotEmpty()) {
							Text(
								text = subtitle,
								style = MaterialTheme.typography.bodySmall.tabularNums(),
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis
							)
						}
					}
					StudioStatusDot(
						label = formatMeloStatusLabel(job.status),
						color = statusColor
					)
				}

				if (!isDone) {
					val progressPct = (job.progress * 100f).roundToInt().coerceIn(0, 100)
					if (statusLower in setOf("queued", "resolving") && (job.totalBytes ?: 0L) <= 0L) {
						LinearProgressIndicator(
							modifier = Modifier
								.fillMaxWidth()
								.height(3.dp)
								.clip(CircleShape),
							color = statusColor,
							trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
						)
					} else {
						Box(
							modifier = Modifier
								.fillMaxWidth()
								.height(3.dp)
								.clip(CircleShape)
								.background(MaterialTheme.colorScheme.surfaceContainerHighest)
						) {
							val fraction = job.progress.coerceIn(0f, 1f)
							if (fraction > 0f) {
								Box(
									modifier = Modifier
										.fillMaxWidth(fraction)
										.fillMaxHeight()
										.clip(CircleShape)
										.background(statusColor)
								)
							}
						}
					}

					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.SpaceBetween,
						verticalAlignment = Alignment.CenterVertically
					) {
						val downloadedStr = job.bytes.toFileSize()
						val totalStr = job.totalBytes?.takeIf { it > 0L }?.toFileSize() ?: "--"
						Text(
							text = "$downloadedStr / $totalStr",
							style = MaterialTheme.typography.labelSmall.tabularNums(),
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
						Text(
							text = "$progressPct%",
							style = MaterialTheme.typography.labelSmall.tabularNums(),
							color = statusColor,
							fontWeight = FontWeight.Medium
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
						horizontalArrangement = Arrangement.spacedBy(8.dp)
					) {
						if (scrapeDotSpec != null) {
							StudioStatusDot(
								label = scrapeDotSpec.first,
								color = scrapeDotSpec.second
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
								modifier = Modifier.weight(1f)
							)
						}
					}
				}

				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically
				) {
					Row(
						modifier = Modifier
							.weight(1f)
							.horizontalScroll(rememberScrollState()),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(6.dp)
					) {
						if (isDone && job.track.id.isNotBlank()) {
							Surface(
								modifier = Modifier
									.clip(CircleShape)
									.clickable(onClick = onPreviewPlay),
								shape = CircleShape,
								color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
							) {
								Row(
									modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.spacedBy(4.dp)
								) {
									if (isCurrent) {
										Waveform(isPlaying = isPlaying)
									} else {
										Icon(
											imageVector = MeloIcons.PlayArrow,
											contentDescription = null,
											tint = MaterialTheme.colorScheme.primary,
											modifier = Modifier.size(13.dp)
										)
									}
									Text(
										text = if (isPlaying) "播放中" else "试听",
										style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
										color = MaterialTheme.colorScheme.primary,
										maxLines = 1
									)
								}
							}
						}

						if (isDone && !job.file.isNullOrBlank()) {
							val primaryScrapeLabel = when {
								associatedScrapeStatus in STUDIO_SCRAPE_REVIEW_STATES -> "审核整理"
								associatedScrapeStatus in STUDIO_SCRAPE_RUNNING_STATES -> "查看进度"
								associatedScrapeStatus in setOf("complete", "completed") -> "查看整理"
								associatedScrapeStatus == "restored" ||
									associatedScrapeStatus in STUDIO_SCRAPE_ERROR_STATES -> "重新刮削"
								else -> "刮削整理"
							}
							Surface(
								modifier = Modifier
									.clip(CircleShape)
									.clickable(onClick = onGoScrape),
								shape = CircleShape,
								color = MaterialTheme.colorScheme.surfaceContainerHigh,
								border = BorderStroke(
									width = MeloHairlineWidth,
									color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
								)
							) {
								Row(
									modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.spacedBy(4.dp)
								) {
									Icon(
										imageVector = MeloIcons.Sparkles,
										contentDescription = null,
										tint = MaterialTheme.colorScheme.primary,
										modifier = Modifier.size(12.dp)
									)
									Text(
										text = primaryScrapeLabel,
										style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
										color = MaterialTheme.colorScheme.onSurface,
										maxLines = 1
									)
								}
							}

							if (associatedScrapeStatus in STUDIO_SCRAPE_REVIEW_STATES) {
								Surface(
									modifier = Modifier
										.clip(CircleShape)
										.clickable(onClick = onRescrape),
									shape = CircleShape,
									color = MaterialTheme.colorScheme.surfaceContainerLow,
									border = BorderStroke(
										width = MeloHairlineWidth,
										color = MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
									)
								) {
									Row(
										modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
										verticalAlignment = Alignment.CenterVertically,
										horizontalArrangement = Arrangement.spacedBy(4.dp)
									) {
										Icon(
											imageVector = Icons.Outlined.Refresh,
											contentDescription = null,
											tint = MaterialTheme.colorScheme.onSurfaceVariant,
											modifier = Modifier.size(12.dp)
										)
										Text(
											text = "重新刮削",
											style = MaterialTheme.typography.labelSmall,
											color = MaterialTheme.colorScheme.onSurfaceVariant,
											maxLines = 1
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
								contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
							) {
								Icon(MeloIcons.Pause, null, modifier = Modifier.size(14.dp))
								Spacer(Modifier.width(3.dp))
								Text("暂停", style = MaterialTheme.typography.labelMedium)
							}
						}
						if (canResume) {
							TextButton(
								onClick = onResume,
								contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
							) {
								Icon(MeloIcons.PlayArrow, null, modifier = Modifier.size(14.dp))
								Spacer(Modifier.width(3.dp))
								Text("继续", style = MaterialTheme.typography.labelMedium)
							}
						}
						if (canRetry) {
							TextButton(
								onClick = onRetry,
								contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
							) {
								Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(14.dp))
								Spacer(Modifier.width(3.dp))
								Text("重试", style = MaterialTheme.typography.labelMedium)
							}
						}
						IconButton(
							onClick = onDelete,
							modifier = Modifier.size(32.dp)
						) {
							Icon(
								imageVector = Icons.Outlined.Delete,
								contentDescription = "取消或删除",
								modifier = Modifier.size(16.dp),
								tint = MaterialTheme.colorScheme.onSurfaceVariant
							)
						}
					}
				}
			}

			if (showBottomDivider) {
				MeloHairlineDivider(startIndent = 70.dp)
			}
		}
	}
}
