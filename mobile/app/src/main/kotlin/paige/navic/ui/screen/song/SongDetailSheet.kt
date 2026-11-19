/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.screen.song

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import paige.navic.R
import paige.navic.di.LocalSnackBarState
import paige.navic.domain.model.DomainSong
import paige.navic.melokeep.MeloTrackMapper
import paige.navic.playback.MediaPlayer
import paige.navic.ui.component.common.CoverArt
import paige.navic.ui.core.UiState
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.filled.Note
import paige.navic.ui.icons.outlined.Album
import paige.navic.ui.icons.outlined.Artist
import paige.navic.ui.icons.outlined.Check
import paige.navic.ui.icons.outlined.Code
import paige.navic.ui.icons.outlined.Copy
import paige.navic.ui.icons.outlined.DataTable
import paige.navic.ui.icons.outlined.Download
import paige.navic.ui.icons.outlined.Error
import paige.navic.ui.icons.outlined.Genre
import paige.navic.ui.icons.outlined.History
import paige.navic.ui.icons.outlined.List
import paige.navic.ui.icons.outlined.Lyrics
import paige.navic.ui.icons.outlined.Note
import paige.navic.ui.icons.outlined.Speed
import paige.navic.ui.icons.outlined.Statistics
import paige.navic.ui.screen.song.viewmodel.SongDetailViewModel
import paige.navic.ui.theme.MeloStudioPresets
import paige.navic.ui.theme.NavicTheme
import paige.navic.ui.theme.meloGoldBorder
import paige.navic.ui.theme.meloGoldContainer
import paige.navic.ui.theme.meloGoldText
import paige.navic.ui.theme.meloSubtleBorder
import paige.navic.ui.theme.positive
import paige.navic.ui.theme.tabularNums
import paige.navic.ui.util.rememberColorSchemeFromCoverArt
import paige.navic.util.toFileSize
import java.util.Locale

@Immutable
internal data class ResolvedSongDetailState(
	val songState: UiState<DomainSong>,
	val info: List<Pair<Int, String?>>,
	val coverArtId: String?
)

@Immutable
private data class DetailRowItem(
	val id: Int,
	val label: String,
	val rawValue: String?,
	val icon: ImageVector,
	val isMonospace: Boolean = false
)

@Composable
internal fun rememberResolvedSongDetailState(
	songId: String,
	initialCoverArtId: String?,
	viewModel: SongDetailViewModel
): ResolvedSongDetailState {
	val player = koinInject<MediaPlayer>()
	val songState by viewModel.songState.collectAsStateWithLifecycle()
	val info by viewModel.info.collectAsStateWithLifecycle()
	val playerSnapshot by remember(player) {
		player.uiState
			.map { it.currentSong to it.queue }
			.distinctUntilChanged()
	}.collectAsStateWithLifecycle(
		initialValue = player.uiState.value.let { it.currentSong to it.queue }
	)

	val fallbackSong = remember(songId, playerSnapshot) {
		val (current, queue) = playerSnapshot
		current?.takeIf { it.id == songId } ?: queue.firstOrNull { it.id == songId }
	}
	val effectiveSong = songState.data ?: fallbackSong
	val effectiveSongState = remember(songState, effectiveSong) {
		if (effectiveSong != null) UiState.Success(effectiveSong) else songState
	}
	val effectiveInfo = remember(info, effectiveSong, songState.data) {
		if (songState.data != null) info
		else effectiveSong?.let { viewModel.getInfo(it) } ?: info
	}
	val activeCoverArtId = if (initialCoverArtId.isNullOrEmpty()) {
		effectiveSong?.coverArtId
	} else {
		initialCoverArtId
	}
	return ResolvedSongDetailState(
		songState = effectiveSongState,
		info = effectiveInfo,
		coverArtId = activeCoverArtId
	)
}

@Composable
fun SongDetailSheet(
	songId: String,
	initialCoverArtId: String? = null,
	useSongTheme: Boolean = true
) {
	val viewModel = koinViewModel<SongDetailViewModel>(
		key = songId,
		parameters = { parametersOf(songId) }
	)
	val meloSettingsStore = koinInject<MeloSettingsStore>()
	val studioPreset by meloSettingsStore.studioThemePreset.collectAsStateWithLifecycle()
	val resolved = rememberResolvedSongDetailState(
		songId = songId,
		initialCoverArtId = initialCoverArtId,
		viewModel = viewModel
	)

	val colorScheme = if (useSongTheme && studioPreset == MeloStudioPresets.DYNAMIC) {
		rememberColorSchemeFromCoverArt(resolved.coverArtId)
	} else {
		null
	}

	val bottomInsets = WindowInsets.systemBars
		.only(WindowInsetsSides.Bottom)
		.asPaddingValues()
		.calculateBottomPadding()

	NavicTheme(colorScheme) {
		Surface(
			modifier = Modifier.fillMaxSize(),
			color = MaterialTheme.colorScheme.background
		) {
			Column(modifier = Modifier.fillMaxSize()) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 20.dp, vertical = 14.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.SpaceBetween
				) {
					Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
						Text(
							text = "曲目声学档案",
							style = MaterialTheme.typography.titleLarge,
							fontWeight = FontWeight.Bold,
							color = MaterialTheme.colorScheme.onSurface
						)
						Text(
							text = "Studio 音轨详情 · 点击任意条目即可复制",
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
					}
				}

				SongDetailStudioContent(
					songState = resolved.songState,
					info = resolved.info,
					activeCoverArtId = resolved.coverArtId,
					bottomPadding = bottomInsets + 24.dp,
					showSnackbarOnCopy = false,
					modifier = Modifier.weight(1f)
				)
			}
		}
	}
}

@Composable
internal fun SongDetailStudioContent(
	songState: UiState<DomainSong>,
	info: List<Pair<Int, String?>>,
	activeCoverArtId: String?,
	bottomPadding: Dp,
	showSnackbarOnCopy: Boolean,
	modifier: Modifier = Modifier
) {
	val song = songState.data
	@Suppress("DEPRECATION")
	val clipboard = LocalClipboardManager.current
	val haptics = LocalHapticFeedback.current
	val snackBarState = LocalSnackBarState.current
	val scope = rememberCoroutineScope()

	var copiedKey by remember { mutableStateOf<Int?>(null) }
	var copiedLabel by remember { mutableStateOf<String?>(null) }

	LaunchedEffect(copiedKey) {
		if (copiedKey != null) {
			delay(1600)
			copiedKey = null
			copiedLabel = null
		}
	}

	val acousticKeys = remember {
		setOf(
			R.string.info_track_duration,
			R.string.info_track_format,
			R.string.info_track_bitrate,
			R.string.info_track_bit_depth,
			R.string.info_track_sampling_rate,
			R.string.info_track_channel_count,
			R.string.info_track_replay_gain,
			R.string.info_album_replay_gain
		)
	}
	val sourceKeys = remember {
		setOf(
			R.string.info_track_file_size,
			R.string.info_track_path
		)
	}

	val coreRows = remember(info) {
		info.filter { it.first !in acousticKeys && it.first !in sourceKeys }
			.map { (keyRes, value) ->
				DetailRowItem(
					id = keyRes,
					label = labelForInfoKey(keyRes),
					rawValue = normalizeFieldValue(keyRes, value),
					icon = iconForInfoKey(keyRes)
				)
			}
	}
	val acousticRows = remember(info) {
		info.filter { it.first in acousticKeys }
			.map { (keyRes, value) ->
				DetailRowItem(
					id = keyRes,
					label = labelForInfoKey(keyRes),
					rawValue = normalizeFieldValue(keyRes, value),
					icon = iconForInfoKey(keyRes)
				)
			}
	}
	val sourceRows = remember(info, song) {
		buildList {
			if (song != null) {
				val isMeloLocal = song.isExternal || MeloTrackMapper.isMeloSongId(song.id)
				add(
					DetailRowItem(
						id = -1001,
						label = "曲目来源",
						rawValue = if (isMeloLocal) "MeloKeep 本地库" else "Navidrome 曲库",
						icon = Icons.Outlined.History
					)
				)
			}
			info.filter { it.first in sourceKeys }.forEach { (keyRes, value) ->
				add(
					DetailRowItem(
						id = keyRes,
						label = labelForInfoKey(keyRes),
						rawValue = normalizeFieldValue(keyRes, value),
						icon = iconForInfoKey(keyRes),
						isMonospace = keyRes == R.string.info_track_path
					)
				)
			}
		}
	}

	val onCopyField: (Int, String, String) -> Unit = { keyId, label, value ->
		haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
		clipboard.setText(AnnotatedString(value))
		copiedKey = keyId
		copiedLabel = label
		if (showSnackbarOnCopy) {
			scope.launch {
				snackBarState.currentSnackbarData?.dismiss()
				snackBarState.showSnackbar("已复制 $label")
			}
		}
	}

	Box(modifier = modifier.fillMaxSize()) {
		Column(
			modifier = Modifier
				.fillMaxSize()
				.verticalScroll(rememberScrollState())
				.padding(horizontal = 16.dp),
			verticalArrangement = Arrangement.spacedBy(16.dp)
		) {
			// 1. 顶部曲目概览头卡 + 声学规格微仪表带
			SongOverviewStudioCard(
				song = song,
				activeCoverArtId = activeCoverArtId
			)

			when {
				songState is UiState.Loading && song == null -> {
					Surface(
						modifier = Modifier.fillMaxWidth(),
						shape = RoundedCornerShape(18.dp),
						color = MaterialTheme.colorScheme.surfaceContainer,
						border = BorderStroke(1.dp, MaterialTheme.colorScheme.meloSubtleBorder)
					) {
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.padding(20.dp),
							horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
							verticalAlignment = Alignment.CenterVertically
						) {
							CircularProgressIndicator(
								modifier = Modifier.size(18.dp),
								strokeWidth = 2.dp
							)
							Text(
								text = "正在读取音轨档案...",
								style = MaterialTheme.typography.bodyMedium,
								color = MaterialTheme.colorScheme.onSurfaceVariant
							)
						}
					}
				}

				songState is UiState.Error && song == null -> {
					Surface(
						modifier = Modifier.fillMaxWidth(),
						shape = RoundedCornerShape(18.dp),
						color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
						border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
					) {
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.padding(16.dp),
							horizontalArrangement = Arrangement.spacedBy(12.dp),
							verticalAlignment = Alignment.CenterVertically
						) {
							Icon(
								imageVector = Icons.Outlined.Error,
								contentDescription = null,
								tint = MaterialTheme.colorScheme.error
							)
							Text(
								text = "未找到该曲目的声学档案",
								style = MaterialTheme.typography.bodyMedium,
								color = MaterialTheme.colorScheme.onSurface
							)
						}
					}
				}

				else -> {
					if (coreRows.isNotEmpty()) {
						SongDetailGroupSection(
							title = "核心元数据",
							sectionIcon = Icons.Outlined.Note,
							rows = coreRows,
							copiedKey = copiedKey,
							onCopyField = onCopyField
						)
					}

					if (acousticRows.isNotEmpty()) {
						SongDetailGroupSection(
							title = "音频声学",
							sectionIcon = Icons.Outlined.DataTable,
							rows = acousticRows,
							copiedKey = copiedKey,
							onCopyField = onCopyField
						)
					}

					if (sourceRows.isNotEmpty()) {
						SongDetailGroupSection(
							title = "来源与路径",
							sectionIcon = Icons.Outlined.Code,
							rows = sourceRows,
							copiedKey = copiedKey,
							onCopyField = onCopyField
						)
					}
				}
			}

			Spacer(Modifier.height(bottomPadding))
		}

		// 模态 Sheet 内也可感知的复制成功提示胶囊
		AnimatedVisibility(
			visible = copiedLabel != null,
			enter = fadeIn() + slideInVertically { -it },
			exit = fadeOut() + slideOutVertically { -it },
			modifier = Modifier
				.align(Alignment.TopCenter)
				.padding(top = 6.dp)
		) {
			Surface(
				shape = CircleShape,
				color = MaterialTheme.colorScheme.surfaceContainerHighest,
				border = BorderStroke(1.dp, MaterialTheme.colorScheme.positive.copy(alpha = 0.45f)),
				shadowElevation = 6.dp
			) {
				Row(
					modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(6.dp)
				) {
					Icon(
						imageVector = Icons.Outlined.Check,
						contentDescription = null,
						tint = MaterialTheme.colorScheme.positive,
						modifier = Modifier.size(15.dp)
					)
					Text(
						text = "已复制 ${copiedLabel.orEmpty()}",
						style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
						color = MaterialTheme.colorScheme.onSurface
					)
				}
			}
		}
	}
}

@Composable
private fun SongOverviewStudioCard(
	song: DomainSong?,
	activeCoverArtId: String?
) {
	val isMeloLocal = song != null && (song.isExternal || MeloTrackMapper.isMeloSongId(song.id))
	val formatValue = remember(song?.fileExtension, song?.mimeType) {
		song?.fileExtension?.takeIf { it.isNotBlank() }?.uppercase()
			?: song?.mimeType?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.uppercase()
			?: "--"
	}
	val isLossless = remember(song?.bitDepth, song?.fileExtension, song?.bitRate) {
		(song?.bitDepth ?: 0) >= 24 ||
			song?.fileExtension?.lowercase() in setOf("flac", "wav", "alac", "ape", "dsf", "aiff") ||
			(song?.bitRate ?: 0) >= 700
	}
	val resolutionValue = remember(song?.bitDepth, song?.sampleRate) {
		val depth = song?.bitDepth?.takeIf { it > 0 }?.let { "${it}bit" }
		val rate = song?.sampleRate?.takeIf { it > 0 }?.let(::formatAcousticRate)
		listOfNotNull(depth, rate).joinToString("/").ifBlank { "--" }
	}
	val bitrateValue = remember(song?.bitRate) {
		song?.bitRate?.takeIf { it > 0 }?.let { "${it} kbps" } ?: "--"
	}
	val sizeValue = remember(song?.fileSize) {
		song?.fileSize?.takeIf { it > 0L }?.toFileSize() ?: "--"
	}

	Surface(
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(22.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		border = BorderStroke(1.dp, MaterialTheme.colorScheme.meloSubtleBorder)
	) {
		Column(
			modifier = Modifier.padding(14.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp)
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(14.dp)
			) {
				Box(
					modifier = Modifier.size(74.dp),
					contentAlignment = Alignment.Center
				) {
					CoverArt(
						coverArtId = activeCoverArtId,
						modifier = Modifier.size(74.dp),
						shape = RoundedCornerShape(16.dp)
					)
					if (activeCoverArtId == null) {
						Icon(
							imageVector = Icons.Filled.Note,
							contentDescription = null,
							tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
							modifier = Modifier.size(28.dp)
						)
					}
				}

				Column(
					modifier = Modifier.weight(1f),
					verticalArrangement = Arrangement.spacedBy(4.dp)
				) {
					Text(
						text = song?.title?.takeIf { it.isNotBlank() } ?: "未知曲目",
						style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
						color = MaterialTheme.colorScheme.onSurface,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis
					)

					val artistDisplay = song?.artistName?.takeIf { it.isNotBlank() }
						?: song?.artists?.joinToString(", ") { it.name }?.takeIf { it.isNotBlank() }
					if (artistDisplay != null) {
						Text(
							text = artistDisplay,
							style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
							color = MaterialTheme.colorScheme.primary,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis
						)
					}

					val albumLine = listOfNotNull(
						song?.albumTitle?.takeIf { it.isNotBlank() },
						song?.year?.takeIf { it > 0 }?.toString()
					).joinToString(" · ")
					if (albumLine.isNotEmpty()) {
						Text(
							text = albumLine,
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis
						)
					}

					if (isMeloLocal) {
						Surface(
							shape = RoundedCornerShape(6.dp),
							color = MaterialTheme.colorScheme.meloGoldContainer,
							border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.meloGoldBorder)
						) {
							Text(
								text = "MeloKeep 本地库",
								style = MaterialTheme.typography.labelSmall.copy(
									fontSize = 10.sp,
									fontWeight = FontWeight.SemiBold
								),
								color = MaterialTheme.colorScheme.meloGoldText,
								modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
							)
						}
					}
				}
			}

			// 声学规格微仪表带
			Surface(
				modifier = Modifier.fillMaxWidth(),
				shape = RoundedCornerShape(14.dp),
				color = MaterialTheme.colorScheme.surfaceContainer,
				border = BorderStroke(1.dp, MaterialTheme.colorScheme.meloSubtleBorder)
			) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(vertical = 10.dp, horizontal = 6.dp),
					verticalAlignment = Alignment.CenterVertically
				) {
					AcousticMeterCell(
						label = "格式",
						value = formatValue,
						highlightGold = isLossless,
						modifier = Modifier.weight(1f)
					)
					MeterDivider()
					AcousticMeterCell(
						label = "位深/采样",
						value = resolutionValue,
						highlightGold = false,
						modifier = Modifier.weight(1.25f)
					)
					MeterDivider()
					AcousticMeterCell(
						label = "码率",
						value = bitrateValue,
						highlightGold = false,
						modifier = Modifier.weight(1f)
					)
					MeterDivider()
					AcousticMeterCell(
						label = "体积",
						value = sizeValue,
						highlightGold = false,
						modifier = Modifier.weight(1f)
					)
				}
			}
		}
	}
}

@Composable
private fun AcousticMeterCell(
	label: String,
	value: String,
	highlightGold: Boolean,
	modifier: Modifier = Modifier
) {
	Column(
		modifier = modifier.padding(horizontal = 4.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(2.dp)
	) {
		Text(
			text = label,
			style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis
		)
		Text(
			text = value,
			style = MaterialTheme.typography.labelMedium.copy(
				fontWeight = FontWeight.Bold
			).tabularNums(),
			color = if (highlightGold) {
				MaterialTheme.colorScheme.meloGoldText
			} else {
				MaterialTheme.colorScheme.onSurface
			},
			maxLines = 1,
			overflow = TextOverflow.Ellipsis
		)
	}
}

@Composable
private fun MeterDivider() {
	Box(
		modifier = Modifier
			.width(1.dp)
			.height(24.dp)
			.background(MaterialTheme.colorScheme.meloSubtleBorder)
	)
}

@Composable
private fun SongDetailGroupSection(
	title: String,
	sectionIcon: ImageVector,
	rows: List<DetailRowItem>,
	copiedKey: Int?,
	onCopyField: (Int, String, String) -> Unit
) {
	Column(
		modifier = Modifier.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(8.dp)
	) {
		Row(
			modifier = Modifier.padding(horizontal = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp)
		) {
			Icon(
				imageVector = sectionIcon,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.primary,
				modifier = Modifier.size(15.dp)
			)
			Text(
				text = title,
				style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
		}

		Surface(
			modifier = Modifier.fillMaxWidth(),
			shape = RoundedCornerShape(18.dp),
			color = MaterialTheme.colorScheme.surfaceContainer,
			border = BorderStroke(1.dp, MaterialTheme.colorScheme.meloSubtleBorder)
		) {
			Column(modifier = Modifier.fillMaxWidth()) {
				rows.forEachIndexed { index, row ->
					val cleanValue = row.rawValue
					val displayValue = cleanValue ?: "暂无"
					val isCopied = copiedKey == row.id

					Row(
						modifier = Modifier
							.fillMaxWidth()
							.clickable(enabled = cleanValue != null) {
								cleanValue?.let { onCopyField(row.id, row.label, it) }
							}
							.padding(horizontal = 14.dp, vertical = 12.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(12.dp)
					) {
						Box(
							modifier = Modifier
								.size(32.dp)
								.clip(RoundedCornerShape(9.dp))
								.background(MaterialTheme.colorScheme.surfaceContainerHigh),
							contentAlignment = Alignment.Center
						) {
							Icon(
								imageVector = row.icon,
								contentDescription = null,
								tint = if (isCopied) {
									MaterialTheme.colorScheme.positive
								} else {
									MaterialTheme.colorScheme.onSurfaceVariant
								},
								modifier = Modifier.size(17.dp)
							)
						}

						Column(
							modifier = Modifier.weight(1f),
							verticalArrangement = Arrangement.spacedBy(2.dp)
						) {
							Text(
								text = row.label,
								style = MaterialTheme.typography.labelSmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant
							)
							Text(
								text = displayValue,
								style = MaterialTheme.typography.bodyMedium.copy(
									fontFamily = if (row.isMonospace) {
										FontFamily.Monospace
									} else {
										MaterialTheme.typography.bodyMedium.fontFamily
									},
									fontWeight = if (cleanValue != null) FontWeight.Medium else FontWeight.Normal
								).tabularNums(),
								color = if (cleanValue != null) {
									MaterialTheme.colorScheme.onSurface
								} else {
									MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
								},
								maxLines = if (row.isMonospace) 4 else 2,
								overflow = TextOverflow.Ellipsis
							)
						}

						if (cleanValue != null) {
							if (isCopied) {
								Surface(
									shape = CircleShape,
									color = MaterialTheme.colorScheme.positive.copy(alpha = 0.14f),
									border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.positive.copy(alpha = 0.35f))
								) {
									Row(
										modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
										verticalAlignment = Alignment.CenterVertically,
										horizontalArrangement = Arrangement.spacedBy(4.dp)
									) {
										Icon(
											imageVector = Icons.Outlined.Check,
											contentDescription = "已复制",
											tint = MaterialTheme.colorScheme.positive,
											modifier = Modifier.size(13.dp)
										)
										Text(
											text = "已复制",
											style = MaterialTheme.typography.labelSmall,
											color = MaterialTheme.colorScheme.positive
										)
									}
								}
							} else {
								Icon(
									imageVector = Icons.Outlined.Copy,
									contentDescription = "复制",
									tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
									modifier = Modifier.size(16.dp)
								)
							}
						}
					}

					if (index < rows.lastIndex) {
						HorizontalDivider(
							modifier = Modifier.padding(start = 58.dp, end = 14.dp),
							thickness = 0.5.dp,
							color = MaterialTheme.colorScheme.meloSubtleBorder
						)
					}
				}
			}
		}
	}
}

private fun normalizeFieldValue(keyRes: Int, rawValue: String?): String? {
	val clean = rawValue?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
		?: return null
	if (clean.equals("External", ignoreCase = true)) {
		return "MeloKeep 本地库"
	}
	return when {
		keyRes == R.string.info_track_bit_depth && !clean.contains("bit", ignoreCase = true) ->
			"$clean bit"
		keyRes == R.string.info_track_channel_count && clean.all(Char::isDigit) ->
			"${clean} 声道"
		else -> clean
	}
}

private fun labelForInfoKey(keyRes: Int): String = when (keyRes) {
	R.string.info_track_name -> "曲目名称"
	R.string.info_track_artist -> "音乐人"
	R.string.info_track_album -> "所属专辑"
	R.string.info_track_number -> "音轨序号"
	R.string.info_track_disc_number -> "碟片序号"
	R.string.info_track_year -> "发行年份"
	R.string.info_track_genre -> "流派风格"
	R.string.info_track_duration -> "曲目时长"
	R.string.info_track_format -> "音频格式"
	R.string.info_track_bitrate -> "比特率"
	R.string.info_track_bit_depth -> "位深度"
	R.string.info_track_sampling_rate -> "采样率"
	R.string.info_track_channel_count -> "声道数"
	R.string.info_track_file_size -> "文件体积"
	R.string.info_track_path -> "媒体路径"
	R.string.info_track_replay_gain -> "音轨回放增益"
	R.string.info_album_replay_gain -> "专辑回放增益"
	R.string.title_statistics -> "播放次数"
	else -> "属性"
}

private fun iconForInfoKey(keyRes: Int): ImageVector = when (keyRes) {
	R.string.info_track_name -> Icons.Outlined.Note
	R.string.info_track_artist -> Icons.Outlined.Artist
	R.string.info_track_album,
	R.string.info_track_disc_number -> Icons.Outlined.Album
	R.string.info_track_number -> Icons.Outlined.List
	R.string.info_track_year -> Icons.Outlined.History
	R.string.info_track_genre -> Icons.Outlined.Genre
	R.string.info_track_duration,
	R.string.info_track_bitrate -> Icons.Outlined.Speed
	R.string.info_track_sampling_rate -> Icons.Outlined.Lyrics
	R.string.info_track_format,
	R.string.info_track_bit_depth,
	R.string.info_track_channel_count -> Icons.Outlined.DataTable
	R.string.info_track_file_size -> Icons.Outlined.Download
	R.string.info_track_path -> Icons.Outlined.Code
	R.string.info_track_replay_gain,
	R.string.info_album_replay_gain,
	R.string.title_statistics -> Icons.Outlined.Statistics
	else -> Icons.Outlined.Note
}

private fun formatAcousticRate(sampleRate: Int): String {
	return if (sampleRate >= 1000) {
		if (sampleRate % 1000 == 0) {
			"${sampleRate / 1000}k"
		} else {
			String.format(Locale.US, "%.1fk", sampleRate / 1000.0)
		}
	} else {
		"${sampleRate}Hz"
	}
}
