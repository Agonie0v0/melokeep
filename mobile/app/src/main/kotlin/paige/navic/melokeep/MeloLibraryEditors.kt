@file:OptIn(
	ExperimentalMaterial3Api::class,
	ExperimentalMaterial3ExpressiveApi::class,
	ExperimentalLayoutApi::class
)

package paige.navic.melokeep

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.agonie0v0.melokeep.shared.MeloLibraryFile
import io.github.agonie0v0.melokeep.shared.MeloLibraryFolder
import io.github.agonie0v0.melokeep.shared.MeloLocalPlaylistSummary
import io.github.agonie0v0.melokeep.shared.MeloNavidromeDeletionPreview
import paige.navic.database.entity.DownloadStatus
import paige.navic.domain.model.DomainSong
import paige.navic.ui.component.common.CoverArt
import paige.navic.ui.component.common.MarqueeText
import paige.navic.ui.component.common.SegmentedListItem
import paige.navic.ui.component.common.SegmentedListItemDefaults
import paige.navic.ui.component.sheet.ModalBottomSheet
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.filled.Play
import paige.navic.ui.icons.filled.Star
import paige.navic.ui.icons.outlined.Add
import paige.navic.ui.icons.outlined.Album
import paige.navic.ui.icons.outlined.Artist
import paige.navic.ui.icons.outlined.Check
import paige.navic.ui.icons.outlined.Checklist
import paige.navic.ui.icons.outlined.Close
import paige.navic.ui.icons.outlined.Delete
import paige.navic.ui.icons.outlined.Download
import paige.navic.ui.icons.outlined.Edit
import paige.navic.ui.icons.outlined.Info
import paige.navic.ui.icons.outlined.LibraryMusic
import paige.navic.ui.icons.outlined.Lock
import paige.navic.ui.icons.outlined.PlaylistAdd
import paige.navic.ui.icons.outlined.Queue
import paige.navic.ui.icons.outlined.QueuePlayNext
import paige.navic.ui.icons.outlined.Share
import paige.navic.ui.icons.outlined.Star
import paige.navic.ui.theme.defaultFont
import paige.navic.ui.theme.meloGlassSurface
import paige.navic.ui.theme.meloGoldText
import paige.navic.ui.theme.tabularNums
import paige.navic.ui.theme.warning
import paige.navic.util.toFileSize

private data class MeloTagFieldDef(
	val key: String,
	val label: String,
	val placeholder: String,
	val isCore: Boolean
)

/**
 * 与 Go 后端 `media.TagFields`（17 项标准字段）严格一致的标签定义。
 */
private val MELO_17_TAG_FIELDS = listOf(
	MeloTagFieldDef("title", "歌曲标题", "输入曲目标题", isCore = true),
	MeloTagFieldDef("artist", "歌手", "输入演唱者", isCore = true),
	MeloTagFieldDef("album", "专辑", "输入专辑名称", isCore = true),
	MeloTagFieldDef("album_artist", "专辑歌手", "输入专辑主艺人", isCore = true),
	MeloTagFieldDef("date", "发行日期", "例如 2024 或 2024-05-01", isCore = true),
	MeloTagFieldDef("track_number", "曲序", "例如 1 或 1/12", isCore = true),
	MeloTagFieldDef("disc_number", "碟号", "例如 1 或 1/2", isCore = true),
	MeloTagFieldDef("genre", "流派", "例如 Pop / Rock", isCore = false),
	MeloTagFieldDef("composer", "作曲", "输入作曲者", isCore = false),
	MeloTagFieldDef("lyricist", "作词", "输入作词者", isCore = false),
	MeloTagFieldDef("comment", "备注", "补充备注信息", isCore = false),
	MeloTagFieldDef("language", "语言", "例如 zho / eng / jpn", isCore = false),
	MeloTagFieldDef("copyright", "版权", "输入版权声明", isCore = false),
	MeloTagFieldDef("publisher", "发行商", "输入唱片公司或厂牌", isCore = false),
	MeloTagFieldDef("grouping", "分组", "输入曲风或专辑分组", isCore = false),
	MeloTagFieldDef("bpm", "BPM", "节拍速度，例如 120", isCore = false),
	MeloTagFieldDef("isrc", "ISRC", "国际标准录音代码", isCore = false)
)

private val MELO_TAG_LABEL_MAP = MELO_17_TAG_FIELDS.associate { it.key to it.label }

@Composable
internal fun LibraryNavidromeSongActionSheet(
	song: DomainSong,
	downloadStatus: DownloadStatus,
	canShare: Boolean,
	canDeletePhysicalFile: Boolean,
	hostNavBottom: Dp,
	onDismiss: () -> Unit,
	onPlayNow: () -> Unit,
	onPlayNext: () -> Unit,
	onAddToQueue: () -> Unit,
	onToggleStar: (Boolean) -> Unit,
	onAddToPlaylist: () -> Unit,
	onDownload: () -> Unit,
	onCancelDownload: () -> Unit,
	onDeleteDownload: () -> Unit,
	onViewAlbum: (() -> Unit)?,
	onViewArtist: (() -> Unit)?,
	onTrackInfo: () -> Unit,
	onShare: () -> Unit,
	onRequestPhysicalDelete: () -> Unit
) {
	val starred = song.starredAt != null
	val itemColors = ListItemDefaults.colors(
		containerColor = Color.Transparent,
		headlineColor = MaterialTheme.colorScheme.onSurface,
		supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
		leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
	)
	val contentPadding = PaddingValues(horizontal = 20.dp)

	ModalBottomSheet(
		onDismissRequest = onDismiss,
		sheetState = rememberBottomSheetState(
			initialValue = SheetValue.Hidden,
			enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
		),
		containerColor = MaterialTheme.colorScheme.surfaceContainerLow
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.verticalScroll(rememberScrollState())
				.padding(bottom = maxOf(hostNavBottom, 16.dp) + 16.dp)
		) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 20.dp, vertical = 10.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(12.dp)
			) {
				CoverArt(
					coverArtId = song.coverArtId,
					modifier = Modifier.size(52.dp),
					shape = RoundedCornerShape(10.dp)
				)
				Column(
					modifier = Modifier.weight(1f),
					verticalArrangement = Arrangement.spacedBy(2.dp)
				) {
					MarqueeText(
						text = song.title,
						style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
					)
					Text(
						text = listOfNotNull(
							song.artistName,
							song.albumTitle
						).joinToString(" · "),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis
					)
					val techSpec = listOfNotNull(
						song.fileExtension?.uppercase()?.takeIf { it.isNotBlank() },
						song.bitDepth?.takeIf { it > 0 }?.let { "${it}bit" },
						song.bitRate?.takeIf { it > 0 }?.let { "${it}kbps" },
						song.fileSize.takeIf { it > 0L }?.toFileSize()
					).joinToString(" · ")
					if (techSpec.isNotBlank()) {
						Text(
							text = techSpec,
							style = MaterialTheme.typography.labelSmall.tabularNums(),
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
					}
				}
			}

			MeloHairlineDivider(
				modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
			)

			ListItem(
				onClick = onPlayNow,
				colors = itemColors,
				contentPadding = contentPadding,
				leadingContent = { Icon(Icons.Filled.Play, null) },
				content = { Text("立即播放") }
			)
			ListItem(
				onClick = onPlayNext,
				colors = itemColors,
				contentPadding = contentPadding,
				leadingContent = { Icon(Icons.Outlined.QueuePlayNext, null) },
				content = { Text("下一首播放") }
			)
			ListItem(
				onClick = onAddToQueue,
				colors = itemColors,
				contentPadding = contentPadding,
				leadingContent = { Icon(Icons.Outlined.Queue, null) },
				content = { Text("加入待播队列") }
			)
			ListItem(
				onClick = { onToggleStar(!starred) },
				colors = itemColors,
				contentPadding = contentPadding,
				leadingContent = {
					Icon(
						imageVector = if (starred) Icons.Filled.Star else Icons.Outlined.Star,
						contentDescription = null,
						tint = if (starred) MaterialTheme.colorScheme.meloGoldText else MaterialTheme.colorScheme.onSurfaceVariant
					)
				},
				content = { Text(if (starred) "移出我喜欢" else "加入我喜欢") }
			)
			ListItem(
				onClick = onAddToPlaylist,
				colors = itemColors,
				contentPadding = contentPadding,
				leadingContent = { Icon(Icons.Outlined.PlaylistAdd, null) },
				content = { Text("加入歌单") }
			)

			when (downloadStatus) {
				DownloadStatus.DOWNLOADING -> ListItem(
					onClick = onCancelDownload,
					colors = itemColors,
					contentPadding = contentPadding,
					leadingContent = { Icon(Icons.Outlined.Close, null) },
					content = { Text("取消离线缓存") }
				)
				DownloadStatus.DOWNLOADED -> ListItem(
					onClick = onDeleteDownload,
					colors = itemColors,
					contentPadding = contentPadding,
					leadingContent = { Icon(Icons.Outlined.Delete, null) },
					content = { Text("移除本地离线缓存") }
				)
				else -> ListItem(
					onClick = onDownload,
					colors = itemColors,
					contentPadding = contentPadding,
					leadingContent = { Icon(Icons.Outlined.Download, null) },
					content = { Text("离线到手机") }
				)
			}

			if (onViewAlbum != null) {
				ListItem(
					onClick = onViewAlbum,
					colors = itemColors,
					contentPadding = contentPadding,
					leadingContent = { Icon(Icons.Outlined.Album, null) },
					content = { Text("查看所属专辑") }
				)
			}
			if (onViewArtist != null) {
				ListItem(
					onClick = onViewArtist,
					colors = itemColors,
					contentPadding = contentPadding,
					leadingContent = { Icon(Icons.Outlined.Artist, null) },
					content = { Text("查看歌手主页") }
				)
			}
			if (canShare) {
				ListItem(
					onClick = onShare,
					colors = itemColors,
					contentPadding = contentPadding,
					leadingContent = { Icon(Icons.Outlined.Share, null) },
					content = { Text("创建分享链接") }
				)
			}
			ListItem(
				onClick = onTrackInfo,
				colors = itemColors,
				contentPadding = contentPadding,
				leadingContent = { Icon(Icons.Outlined.Info, null) },
				content = { Text("技术参数") }
			)

			if (canDeletePhysicalFile) {
				MeloHairlineDivider(
					modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
				)
				Surface(
					onClick = onRequestPhysicalDelete,
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 16.dp),
					shape = RoundedCornerShape(14.dp),
					color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f),
					border = BorderStroke(MeloHairlineWidth, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
				) {
					Row(
						modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(12.dp)
					) {
						Icon(
							imageVector = Icons.Outlined.Delete,
							contentDescription = null,
							tint = MaterialTheme.colorScheme.error,
							modifier = Modifier.size(20.dp)
						)
						Text(
							text = "删除服务器原文件",
							style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
							color = MaterialTheme.colorScheme.error
						)
					}
				}
			}
		}
	}
}

@Composable
internal fun LibraryNavidromePhysicalDeleteSheet(
	song: DomainSong,
	preview: MeloNavidromeDeletionPreview?,
	loadingPreview: Boolean,
	deleting: Boolean,
	hostNavBottom: Dp,
	onRetryPreview: () -> Unit,
	onDismiss: () -> Unit,
	onConfirmDelete: (String) -> Unit
) {
	ModalBottomSheet(
		onDismissRequest = { if (!deleting) onDismiss() },
		sheetState = rememberBottomSheetState(
			initialValue = SheetValue.Hidden,
			enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
		),
		containerColor = MaterialTheme.colorScheme.surfaceContainerLow
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 20.dp)
				.padding(bottom = maxOf(hostNavBottom, 16.dp) + 16.dp),
			verticalArrangement = Arrangement.spacedBy(14.dp)
		) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(10.dp)
			) {
				Box(
					modifier = Modifier
						.size(40.dp)
						.background(MaterialTheme.colorScheme.errorContainer, CircleShape),
					contentAlignment = Alignment.Center
				) {
					Icon(
						imageVector = Icons.Outlined.Delete,
						contentDescription = null,
						tint = MaterialTheme.colorScheme.error,
						modifier = Modifier.size(20.dp)
					)
				}
				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = "删除服务器原文件",
						style = MaterialTheme.typography.titleMedium.copy(
							fontFamily = defaultFont(grade = 100),
							fontWeight = FontWeight.SemiBold
						)
					)
					Text(
						text = "${song.title} · ${song.artistName ?: "未知歌手"}",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis
					)
				}
			}

			when {
				loadingPreview -> {
					MeloGroupedCard {
						Row(
							modifier = Modifier.padding(16.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(12.dp)
						) {
							CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
							Text(
								text = "正在获取文件路径…",
								style = MaterialTheme.typography.bodyMedium,
								color = MaterialTheme.colorScheme.onSurfaceVariant
							)
						}
					}
				}

				preview != null -> {
					Surface(
						modifier = Modifier.fillMaxWidth(),
						shape = RoundedCornerShape(14.dp),
						color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.24f),
						border = BorderStroke(MeloHairlineWidth, MaterialTheme.colorScheme.error.copy(alpha = 0.40f))
					) {
						Column(
							modifier = Modifier.padding(14.dp),
							verticalArrangement = Arrangement.spacedBy(6.dp)
						) {
							Text(
								text = "文件路径",
								style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
								color = MaterialTheme.colorScheme.error
							)
							Text(
								text = preview.path.ifBlank { "未知路径" },
								style = MaterialTheme.typography.bodySmall.copy(
									fontFamily = FontFamily.Monospace,
									fontWeight = FontWeight.Medium
								),
								color = MaterialTheme.colorScheme.onSurface
							)
						}
					}

					Text(
						text = "仅删除音频文件，保留歌词封面并自动扫库，删除后不可恢复。",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)

					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.spacedBy(10.dp)
					) {
						OutlinedButton(
							onClick = onDismiss,
							enabled = !deleting,
							modifier = Modifier
								.weight(1f)
								.height(44.dp),
							shape = RoundedCornerShape(12.dp)
						) {
							Text("取消")
						}
						Button(
							onClick = { onConfirmDelete(preview.revision) },
							enabled = !deleting && preview.revision.isNotBlank(),
							modifier = Modifier
								.weight(1.3f)
								.height(44.dp),
							shape = RoundedCornerShape(12.dp),
							colors = ButtonDefaults.buttonColors(
								containerColor = MaterialTheme.colorScheme.error,
								contentColor = MaterialTheme.colorScheme.onError
							)
						) {
							if (deleting) {
								CircularProgressIndicator(
									modifier = Modifier.size(16.dp),
									strokeWidth = 2.dp,
									color = MaterialTheme.colorScheme.onError
								)
								Spacer(Modifier.width(8.dp))
								Text("删除中…")
							} else {
								Text("确认删除")
							}
						}
					}
				}

				else -> {
					MeloGroupedCard {
						Column(
							modifier = Modifier.padding(16.dp),
							verticalArrangement = Arrangement.spacedBy(10.dp)
						) {
							Text(
								text = "无法获取文件删除预览，请重试。",
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant
							)
							Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
								OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
									Text("关闭")
								}
								Button(onClick = onRetryPreview, modifier = Modifier.weight(1f)) {
									Text("重试")
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
internal fun LibraryBatchSelectionDock(
	selectedCount: Int,
	busy: Boolean,
	onClear: () -> Unit,
	onScrape: () -> Unit,
	onMove: () -> Unit,
	onDelete: () -> Unit,
	onOffline: () -> Unit = {},
	onAddToPlaylist: () -> Unit = {},
	onSelectAll: () -> Unit = {}
) {
	Surface(
		modifier = Modifier
			.widthIn(max = 560.dp)
			.fillMaxWidth(),
		shape = RoundedCornerShape(20.dp),
		color = MaterialTheme.colorScheme.meloGlassSurface,
		border = libraryHairlineBorder(),
		shadowElevation = 10.dp
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 12.dp, vertical = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp)
		) {
			Row(
				modifier = Modifier.weight(1f),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(4.dp)
			) {
				IconButton(
					onClick = onClear,
					modifier = Modifier.size(28.dp)
				) {
					Icon(
						imageVector = Icons.Outlined.Close,
						contentDescription = "退出多选",
						modifier = Modifier.size(16.dp)
					)
				}
				Text(
					text = "已选 $selectedCount",
					style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold).tabularNums(),
					maxLines = 1
				)
			}

			Row(
				modifier = Modifier
					.weight(1.75f)
					.horizontalScroll(rememberScrollState()),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp)
			) {
				LibraryDockPill(label = "全选", icon = Icons.Outlined.Checklist, onClick = onSelectAll)
				LibraryDockPill(label = "离线", icon = Icons.Outlined.Download, onClick = onOffline, emphasized = true)
				LibraryDockPill(label = "加歌单", icon = Icons.Outlined.PlaylistAdd, onClick = onAddToPlaylist)
				Surface(
					onClick = { if (!busy) onScrape() },
					shape = CircleShape,
					color = MaterialTheme.colorScheme.primary,
					contentColor = MaterialTheme.colorScheme.onPrimary
				) {
					Row(
						modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(4.dp)
					) {
						Icon(MeloIcons.Sparkles, null, modifier = Modifier.size(14.dp))
						Text(
							text = "刮削",
							style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
							maxLines = 1,
							softWrap = false
						)
					}
				}

				Surface(
					onClick = { if (!busy) onMove() },
					shape = CircleShape,
					color = MaterialTheme.colorScheme.surfaceContainerHigh,
					border = libraryHairlineBorder()
				) {
					Row(
						modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(4.dp)
					) {
						Icon(MeloIcons.Folder, null, modifier = Modifier.size(14.dp))
						Text(
							text = "移动",
							style = MaterialTheme.typography.labelMedium,
							maxLines = 1,
							softWrap = false
						)
					}
				}

				Surface(
					onClick = { if (!busy) onDelete() },
					shape = CircleShape,
					color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
					contentColor = MaterialTheme.colorScheme.onErrorContainer
				) {
					Row(
						modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(4.dp)
					) {
						Icon(Icons.Outlined.Delete, null, modifier = Modifier.size(14.dp))
						Text(
							text = "删除",
							style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
							maxLines = 1,
							softWrap = false
						)
					}
				}
			}
		}
	}
}

@Composable
internal fun LibraryFileTagEditorSheet(
	file: MeloLibraryFile,
	hostNavBottom: Dp,
	onDismiss: () -> Unit,
	onSave: (Map<String, String>) -> Unit,
	onUnlockAll: () -> Unit
) {
	val initialValues = remember(file.path, file.metadata) {
		MELO_17_TAG_FIELDS.associate { def ->
			def.key to file.metadata[def.key].orEmpty()
		}
	}
	val draft = remember(file.path) {
		mutableStateMapOf<String, String>().apply {
			putAll(initialValues)
		}
	}
	var showExtended by rememberSaveable(file.path) { mutableStateOf(true) }
	val isEditable = file.editable != false

	ModalBottomSheet(
		onDismissRequest = onDismiss,
		sheetState = rememberBottomSheetState(
			initialValue = SheetValue.Hidden,
			enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
		),
		containerColor = MaterialTheme.colorScheme.surfaceContainerLow
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.verticalScroll(rememberScrollState())
				.imePadding()
				.padding(horizontal = 20.dp)
				.padding(bottom = maxOf(hostNavBottom, 16.dp) + 16.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp)
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically
			) {
				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = "编辑音频标签",
						style = MaterialTheme.typography.titleMedium.copy(
							fontFamily = defaultFont(grade = 100),
							fontWeight = FontWeight.SemiBold
						)
					)
					Text(
						text = file.name,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis
					)
				}
				IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
					Icon(Icons.Outlined.Close, contentDescription = "关闭", modifier = Modifier.size(18.dp))
				}
			}

			if (!isEditable) {
				Surface(
					shape = RoundedCornerShape(12.dp),
					color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.40f)
				) {
					Text(
						text = "当前格式暂不支持回写内嵌标签。",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onErrorContainer,
						modifier = Modifier.padding(12.dp)
					)
				}
			}

			MeloGroupedCard {
				Column(
					modifier = Modifier.padding(12.dp),
					verticalArrangement = Arrangement.spacedBy(8.dp)
				) {
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.SpaceBetween,
						verticalAlignment = Alignment.CenterVertically
					) {
						Row(
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(6.dp)
						) {
							Icon(
								imageVector = Icons.Outlined.Lock,
								contentDescription = null,
								tint = if (file.lockedFields.isNotEmpty()) {
									MaterialTheme.colorScheme.warning
								} else {
									MaterialTheme.colorScheme.onSurfaceVariant
								},
								modifier = Modifier.size(15.dp)
							)
							Text(
								text = if (file.lockedFields.isNotEmpty()) {
									"已锁 ${file.lockedFields.size} 项字段"
								} else {
									"保存修改后将自动锁定对应字段"
								},
								style = MaterialTheme.typography.labelMedium,
								color = if (file.lockedFields.isNotEmpty()) {
									MaterialTheme.colorScheme.warning
								} else {
									MaterialTheme.colorScheme.onSurfaceVariant
								}
							)
						}
					}

					if (file.lockedFields.isNotEmpty()) {
						FlowRow(
							horizontalArrangement = Arrangement.spacedBy(6.dp),
							verticalArrangement = Arrangement.spacedBy(6.dp)
						) {
							file.lockedFields.forEach { fieldKey ->
								Surface(
									shape = CircleShape,
									color = MaterialTheme.colorScheme.warning.copy(alpha = 0.14f),
									border = BorderStroke(MeloHairlineWidth, MaterialTheme.colorScheme.warning.copy(alpha = 0.35f))
								) {
									Row(
										modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
										verticalAlignment = Alignment.CenterVertically,
										horizontalArrangement = Arrangement.spacedBy(3.dp)
									) {
										Icon(
											imageVector = Icons.Outlined.Lock,
											contentDescription = null,
											tint = MaterialTheme.colorScheme.warning,
											modifier = Modifier.size(10.dp)
										)
										Text(
											text = MELO_TAG_LABEL_MAP[fieldKey] ?: fieldKey,
											style = MaterialTheme.typography.labelSmall,
											color = MaterialTheme.colorScheme.warning
										)
									}
								}
							}
						}

						OutlinedButton(
							onClick = onUnlockAll,
							shape = RoundedCornerShape(12.dp),
							modifier = Modifier.fillMaxWidth()
						) {
							Text("解除全部锁定")
						}
					}
				}
			}

			val coreFields = remember { MELO_17_TAG_FIELDS.filter { it.isCore } }
			val extendedFields = remember { MELO_17_TAG_FIELDS.filterNot { it.isCore } }

			MeloSectionLabel(title = "核心字段 · ${coreFields.size}")
			coreFields.forEach { def ->
				val isLocked = def.key in file.lockedFields
				TagFieldInput(
					def = def,
					value = draft[def.key].orEmpty(),
					locked = isLocked,
					enabled = isEditable,
					onValueChange = { draft[def.key] = it }
				)
			}

			Row(
				modifier = Modifier
					.fillMaxWidth()
					.clip(RoundedCornerShape(10.dp))
					.clickable { showExtended = !showExtended }
					.padding(vertical = 4.dp),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically
			) {
				Text(
					text = "扩展字段 · ${extendedFields.size}",
					style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
				Text(
					text = if (showExtended) "收起" else "展开",
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.primary
				)
			}

			AnimatedVisibility(visible = showExtended) {
				Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
					extendedFields.forEach { def ->
						val isLocked = def.key in file.lockedFields
						TagFieldInput(
							def = def,
							value = draft[def.key].orEmpty(),
							locked = isLocked,
							enabled = isEditable,
							onValueChange = { draft[def.key] = it }
						)
					}
				}
			}

			Spacer(Modifier.height(4.dp))

			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(10.dp)
			) {
				OutlinedButton(
					onClick = onDismiss,
					modifier = Modifier
						.weight(1f)
						.height(44.dp),
					shape = RoundedCornerShape(12.dp)
				) {
					Text("取消")
				}
				Button(
					onClick = {
						val changed = buildMap {
							MELO_17_TAG_FIELDS.forEach { def ->
								val nextVal = draft[def.key].orEmpty()
								val prevVal = initialValues[def.key].orEmpty()
								if (nextVal != prevVal) {
									put(def.key, nextVal)
								}
							}
						}
						onSave(changed)
					},
					enabled = isEditable,
					modifier = Modifier
						.weight(1.4f)
						.height(44.dp),
					shape = RoundedCornerShape(12.dp)
				) {
					Icon(Icons.Outlined.Check, null, modifier = Modifier.size(16.dp))
					Spacer(Modifier.width(6.dp))
					Text("保存修改")
				}
			}
		}
	}
}

@Composable
private fun TagFieldInput(
	def: MeloTagFieldDef,
	value: String,
	locked: Boolean,
	enabled: Boolean,
	onValueChange: (String) -> Unit
) {
	OutlinedTextField(
		modifier = Modifier.fillMaxWidth(),
		value = value,
		onValueChange = onValueChange,
		enabled = enabled,
		singleLine = def.key != "comment",
		maxLines = if (def.key == "comment") 3 else 1,
		shape = RoundedCornerShape(12.dp),
		label = {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(4.dp)
			) {
				Text(def.label)
				if (locked) {
					Icon(
						imageVector = Icons.Outlined.Lock,
						contentDescription = "已锁定",
						tint = MaterialTheme.colorScheme.warning,
						modifier = Modifier.size(12.dp)
					)
				}
			}
		},
		placeholder = { Text(def.placeholder) }
	)
}

@Composable
internal fun LibraryCreateFolderDialog(
	parentFolder: String,
	onDismiss: () -> Unit,
	onConfirm: (String) -> Unit
) {
	var nameInput by rememberSaveable { mutableStateOf("") }
	AlertDialog(
		onDismissRequest = onDismiss,
		icon = { Icon(MeloIcons.Folder, null, tint = MaterialTheme.colorScheme.primary) },
		title = { Text("新建目录") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
				Text(
					text = if (parentFolder.isBlank()) "位置：根目录" else "位置：$parentFolder",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
				OutlinedTextField(
					value = nameInput,
					onValueChange = { nameInput = it },
					singleLine = true,
					shape = RoundedCornerShape(12.dp),
					label = { Text("文件夹名称") },
					placeholder = { Text("例如 经典华语 / Hi-Res") },
					modifier = Modifier.fillMaxWidth()
				)
			}
		},
		confirmButton = {
			Button(
				onClick = { onConfirm(nameInput) },
				enabled = nameInput.trim().isNotEmpty()
			) {
				Text("创建")
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) {
				Text("取消")
			}
		}
	)
}

@Composable
internal fun LibraryRenameFolderDialog(
	folder: LibraryFolderChildEntry,
	onDismiss: () -> Unit,
	onConfirm: (String) -> Unit
) {
	var nameInput by rememberSaveable(folder.fullPath) { mutableStateOf(folder.name) }
	AlertDialog(
		onDismissRequest = onDismiss,
		icon = { Icon(Icons.Outlined.Edit, null) },
		title = { Text("重命名目录") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
				Text(
					text = folder.fullPath,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
				OutlinedTextField(
					value = nameInput,
					onValueChange = { nameInput = it },
					singleLine = true,
					shape = RoundedCornerShape(12.dp),
					label = { Text("新目录名称") },
					modifier = Modifier.fillMaxWidth()
				)
			}
		},
		confirmButton = {
			Button(
				onClick = { onConfirm(nameInput) },
				enabled = nameInput.trim().isNotEmpty() && nameInput.trim() != folder.name
			) {
				Text("保存")
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) {
				Text("取消")
			}
		}
	)
}

@Composable
internal fun LibraryMoveTargetFolderSheet(
	folders: List<MeloLibraryFolder>,
	defaultRoot: String,
	currentFolder: String,
	selectedCount: Int,
	hostNavBottom: Dp,
	onDismiss: () -> Unit,
	onConfirmMove: (targetRoot: String, targetFolder: String) -> Unit
) {
	val distinctRoots = remember(folders, defaultRoot) {
		(folders.mapNotNull { it.root.takeIf(String::isNotBlank) } + listOfNotNull(defaultRoot.takeIf(String::isNotBlank)))
			.distinct()
	}
	val targetOptions = remember(folders, distinctRoots) {
		buildList {
			val rootChoices = distinctRoots.ifEmpty { listOf("") }
			rootChoices.forEach { r ->
				add(MeloLibraryFolder(root = r, folder = ""))
			}
			folders
				.filter { it.folder.isNotBlank() && it.folder != "." }
				.distinctBy { "${it.root}\u0000${libraryNormalizeFolder(it.folder)}" }
				.forEach { add(it.copy(folder = libraryNormalizeFolder(it.folder))) }
		}
	}

	ModalBottomSheet(
		onDismissRequest = onDismiss,
		sheetState = rememberBottomSheetState(
			initialValue = SheetValue.Hidden,
			enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
		),
		containerColor = MaterialTheme.colorScheme.surfaceContainerLow
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 20.dp)
				.padding(bottom = maxOf(hostNavBottom, 16.dp) + 16.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp)
		) {
			Text(
				text = "移动 $selectedCount 个文件到",
				style = MaterialTheme.typography.titleMedium.copy(
					fontFamily = defaultFont(grade = 100),
					fontWeight = FontWeight.SemiBold
				)
			)
			LazyColumn(
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(max = 360.dp),
				verticalArrangement = Arrangement.spacedBy(4.dp)
			) {
				itemsIndexed(
					items = targetOptions,
					key = { _, item -> "${item.root}\u0000${item.folder}" },
					contentType = { _, _ -> "folder-option" }
				) { idx, option ->
					val isCurrent = libraryNormalizeFolder(option.folder) == currentFolder
					val label = if (option.folder.isBlank()) {
						val rootName = option.root.substringAfterLast('/').substringAfterLast('\\')
						if (distinctRoots.size > 1 && rootName.isNotBlank()) "根目录 ($rootName)" else "根目录"
					} else {
						option.folder
					}
					SegmentedListItem(
						selected = isCurrent,
						onClick = {
							onConfirmMove(option.root.ifBlank { defaultRoot }, option.folder)
						},
						shapes = SegmentedListItemDefaults.segmentedShapes(
							index = idx,
							count = targetOptions.size
						),
						leadingContent = {
							Icon(
								imageVector = MeloIcons.Folder,
								contentDescription = null,
								tint = MaterialTheme.colorScheme.onSurfaceVariant,
								modifier = Modifier.size(20.dp)
							)
						},
						content = {
							Text(
								text = label,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis
							)
						},
						supportingContent = if (isCurrent) {
							{ Text("当前目录") }
						} else null
					)
				}
			}
			OutlinedButton(
				onClick = onDismiss,
				modifier = Modifier
					.fillMaxWidth()
					.height(42.dp),
				shape = RoundedCornerShape(12.dp)
			) {
				Text("取消")
			}
		}
	}
}

@Composable
internal fun LibraryLocalTrackActionSheet(
	item: LibraryLocalTrackItem,
	downloadStatus: DownloadStatus?,
	isFavorite: Boolean,
	hostNavBottom: Dp,
	onDismiss: () -> Unit,
	onPlayNow: () -> Unit,
	onPlayNext: () -> Unit,
	onAddToQueue: () -> Unit,
	onToggleFavorite: () -> Unit,
	onAddToPlaylist: () -> Unit,
	onDownload: () -> Unit,
	onCancelDownload: () -> Unit,
	onDeleteOffline: () -> Unit,
	onRemoveFromPlaylist: (() -> Unit)?
) {
	ModalBottomSheet(
		onDismissRequest = onDismiss,
		sheetState = rememberBottomSheetState(
			initialValue = SheetValue.Hidden,
			enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
		),
		containerColor = MaterialTheme.colorScheme.surfaceContainerLow
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.verticalScroll(rememberScrollState())
				.padding(bottom = maxOf(hostNavBottom, 16.dp) + 16.dp)
		) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 20.dp, vertical = 10.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(12.dp)
			) {
				CoverArt(
					coverArtId = item.song.coverArtId,
					modifier = Modifier.size(52.dp),
					shape = RoundedCornerShape(10.dp)
				)
				Column(
					modifier = Modifier.weight(1f),
					verticalArrangement = Arrangement.spacedBy(2.dp)
				) {
					Text(
						text = item.song.title,
						style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
						maxLines = 1,
						overflow = TextOverflow.Ellipsis
					)
					Text(
						text = listOfNotNull(
							item.track.artist.takeIf { it.isNotBlank() },
							item.track.album.takeIf { it.isNotBlank() },
							libraryFormatLocalQuietQuality(item.track)
						).joinToString(" · "),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis
					)
				}
			}
			MeloHairlineDivider(
				modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
			)
			LibrarySheetActionRow(Icons.Filled.Play, "立即播放", onPlayNow)
			LibrarySheetActionRow(Icons.Outlined.QueuePlayNext, "下一首播放", onPlayNext)
			LibrarySheetActionRow(Icons.Outlined.Queue, "加入待播队列", onAddToQueue)
			LibrarySheetActionRow(
				icon = if (isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
				label = if (isFavorite) "移出我喜欢" else "加入我喜欢",
				onClick = onToggleFavorite,
				tint = if (isFavorite) MaterialTheme.colorScheme.meloGoldText else MaterialTheme.colorScheme.onSurface
			)
			LibrarySheetActionRow(Icons.Outlined.PlaylistAdd, "加入歌单", onAddToPlaylist)
			when (downloadStatus) {
				DownloadStatus.DOWNLOADING -> LibrarySheetActionRow(Icons.Outlined.Close, "取消离线缓存", onCancelDownload)
				DownloadStatus.DOWNLOADED -> LibrarySheetActionRow(Icons.Outlined.Delete, "移除离线缓存", onDeleteOffline)
				else -> LibrarySheetActionRow(Icons.Outlined.Download, "离线到手机", onDownload)
			}
			if (onRemoveFromPlaylist != null) {
				LibrarySheetActionRow(
					icon = Icons.Outlined.Delete,
					label = "移出当前歌单",
					onClick = onRemoveFromPlaylist,
					tint = MaterialTheme.colorScheme.error
				)
			}
		}
	}
}

@Composable
internal fun LibraryLocalPlaylistNameDialog(
	title: String,
	confirmLabel: String,
	initial: String,
	onDismiss: () -> Unit,
	onConfirm: (String) -> Unit
) {
	var name by remember { mutableStateOf(initial) }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(title) },
		text = {
			OutlinedTextField(
				value = name,
				onValueChange = { name = it },
				singleLine = true,
				shape = RoundedCornerShape(12.dp),
				label = { Text("歌单名称") },
				modifier = Modifier.fillMaxWidth()
			)
		},
		confirmButton = {
			Button(
				onClick = { onConfirm(name.trim()) },
				enabled = name.isNotBlank()
			) {
				Text(confirmLabel)
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) {
				Text("取消")
			}
		}
	)
}

@Composable
internal fun LibraryLocalPlaylistPickerSheet(
	trackCount: Int,
	playlists: List<MeloLocalPlaylistSummary>,
	hostNavBottom: Dp,
	onDismiss: () -> Unit,
	onPick: (String) -> Unit,
	onCreate: (String) -> Unit
) {
	var creating by remember { mutableStateOf(false) }
	var newName by remember { mutableStateOf("") }

	ModalBottomSheet(
		onDismissRequest = onDismiss,
		sheetState = rememberBottomSheetState(
			initialValue = SheetValue.Hidden,
			enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
		),
		containerColor = MaterialTheme.colorScheme.surfaceContainerLow
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.verticalScroll(rememberScrollState())
				.padding(bottom = maxOf(hostNavBottom, 16.dp) + 16.dp)
		) {
			Text(
				text = "加入歌单 · $trackCount 首",
				style = MaterialTheme.typography.titleMedium.copy(
					fontFamily = defaultFont(grade = 100),
					fontWeight = FontWeight.SemiBold
				),
				modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
			)
			if (creating) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 20.dp, vertical = 6.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(8.dp)
				) {
					OutlinedTextField(
						value = newName,
						onValueChange = { newName = it },
						singleLine = true,
						shape = RoundedCornerShape(12.dp),
						label = { Text("新歌单名称") },
						modifier = Modifier.weight(1f)
					)
					Button(
						onClick = { onCreate(newName.trim()) },
						enabled = newName.isNotBlank()
					) {
						Text("创建")
					}
				}
			} else {
				LibrarySheetActionRow(Icons.Outlined.Add, "新建歌单并加入", onClick = { creating = true })
			}
			if (playlists.isEmpty() && !creating) {
				Text(
					text = "暂无歌单",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
				)
			}
			playlists.forEach { playlist ->
				LibrarySheetActionRow(
					icon = Icons.Outlined.LibraryMusic,
					label = "${playlist.name.ifBlank { "未命名歌单" }} · ${playlist.trackCount} 首",
					onClick = { onPick(playlist.id) }
				)
			}
		}
	}
}
