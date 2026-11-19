/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.screen.song

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import paige.navic.R
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalSnackBarState
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.model.DomainSong
import paige.navic.domain.model.settings.BottomBarVisibilityMode
import paige.navic.melokeep.MeloCleanTopBar
import paige.navic.melokeep.MeloGroupedCard
import paige.navic.melokeep.MeloHairlineDivider
import paige.navic.melokeep.MeloQuietEmptyState
import paige.navic.melokeep.MeloSectionLabel
import paige.navic.melokeep.MeloTrackMapper
import paige.navic.ui.component.common.CoverArt
import paige.navic.ui.core.UiState
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.filled.Note
import paige.navic.ui.icons.outlined.Error
import paige.navic.ui.screen.song.viewmodel.SongDetailViewModel
import paige.navic.ui.theme.MeloStudioPresets
import paige.navic.ui.theme.NavicTheme
import paige.navic.ui.theme.positive
import paige.navic.ui.theme.tabularNums
import paige.navic.ui.util.rememberColorSchemeFromCoverArt

@Immutable
private data class SongInfoEntry(
	val id: Int,
	val label: String,
	val value: String?,
	val isMultiline: Boolean = false
)

@Composable
fun SongDetailScreen(
	songId: String,
	initialCoverArtId: String? = null
) {
	val viewModel = koinViewModel<SongDetailViewModel>(
		key = songId,
		parameters = { parametersOf(songId) }
	)
	val preferenceManager = koinInject<PreferenceManager>()
	val meloSettingsStore = koinInject<MeloSettingsStore>()
	val studioPreset by meloSettingsStore.studioThemePreset.collectAsStateWithLifecycle()
	val backStack = LocalNavStack.current

	val resolved = rememberResolvedSongDetailState(
		songId = songId,
		initialCoverArtId = initialCoverArtId,
		viewModel = viewModel
	)

	val colorScheme = if (studioPreset == MeloStudioPresets.DYNAMIC) {
		rememberColorSchemeFromCoverArt(resolved.coverArtId)
	} else {
		null
	}

	val bottomInset = WindowInsets.systemBars
		.only(WindowInsetsSides.Bottom)
		.asPaddingValues()
		.calculateBottomPadding()
	val deckClearance = if (preferenceManager.bottomBarVisibilityMode == BottomBarVisibilityMode.AllScreens) {
		96.dp
	} else {
		24.dp
	}

	NavicTheme(colorScheme) {
		Surface(
			modifier = Modifier.fillMaxSize(),
			color = MaterialTheme.colorScheme.background
		) {
			Column(modifier = Modifier.fillMaxSize()) {
				MeloCleanTopBar(
					title = "歌曲信息",
					onBack = dropUnlessResumed {
						if (backStack.size > 1) {
							backStack.removeLastOrNull()
						}
					},
					applyStatusBarPadding = true
				)

				SongDetailQuietBody(
					songState = resolved.songState,
					info = resolved.info,
					activeCoverArtId = resolved.coverArtId,
					bottomPadding = bottomInset + deckClearance,
					modifier = Modifier.weight(1f)
				)
			}
		}
	}
}

@Composable
private fun SongDetailQuietBody(
	songState: UiState<DomainSong>,
	info: List<Pair<Int, String?>>,
	activeCoverArtId: String?,
	bottomPadding: Dp,
	modifier: Modifier = Modifier
) {
	val song = songState.data
	@Suppress("DEPRECATION")
	val clipboard = LocalClipboardManager.current
	val haptics = LocalHapticFeedback.current
	val snackBarState = LocalSnackBarState.current
	val scope = rememberCoroutineScope()

	var copiedKey by remember { mutableStateOf<Int?>(null) }

	LaunchedEffect(copiedKey) {
		if (copiedKey != null) {
			delay(1400)
			copiedKey = null
		}
	}

	val audioKeys = remember {
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
	val fileKeys = remember {
		setOf(
			R.string.info_track_file_size,
			R.string.info_track_path
		)
	}

	val coreEntries = remember(info) {
		info.filter { it.first !in audioKeys && it.first !in fileKeys }
			.map { (keyRes, raw) ->
				SongInfoEntry(
					id = keyRes,
					label = songInfoLabel(keyRes),
					value = normalizeSongInfoValue(keyRes, raw)
				)
			}
	}
	val audioEntries = remember(info) {
		info.filter { it.first in audioKeys }
			.map { (keyRes, raw) ->
				SongInfoEntry(
					id = keyRes,
					label = songInfoLabel(keyRes),
					value = normalizeSongInfoValue(keyRes, raw)
				)
			}
	}
	val fileEntries = remember(info, song) {
		buildList {
			if (song != null) {
				val isMeloLocal = song.isExternal || MeloTrackMapper.isMeloSongId(song.id)
				add(
					SongInfoEntry(
						id = -1001,
						label = "来源",
						value = if (isMeloLocal) "本地库" else "Navidrome"
					)
				)
			}
			info.filter { it.first in fileKeys }.forEach { (keyRes, raw) ->
				add(
					SongInfoEntry(
						id = keyRes,
						label = songInfoLabel(keyRes),
						value = normalizeSongInfoValue(keyRes, raw),
						isMultiline = keyRes == R.string.info_track_path
					)
				)
			}
		}
	}

	val onCopy: (Int, String, String) -> Unit = { id, label, value ->
		haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
		clipboard.setText(AnnotatedString(value))
		copiedKey = id
		scope.launch {
			snackBarState.currentSnackbarData?.dismiss()
			snackBarState.showSnackbar("已复制 $label")
		}
	}

	Column(
		modifier = modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(horizontal = 16.dp, vertical = 8.dp),
		verticalArrangement = Arrangement.spacedBy(18.dp)
	) {
		// 曲目概览头卡
		MeloGroupedCard {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(14.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(14.dp)
			) {
				Box(
					modifier = Modifier.size(62.dp),
					contentAlignment = Alignment.Center
				) {
					CoverArt(
						coverArtId = activeCoverArtId,
						modifier = Modifier.size(62.dp),
						shape = RoundedCornerShape(10.dp)
					)
					if (activeCoverArtId == null) {
						Icon(
							imageVector = Icons.Filled.Note,
							contentDescription = null,
							tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
							modifier = Modifier.size(24.dp)
						)
					}
				}

				Column(
					modifier = Modifier.weight(1f),
					verticalArrangement = Arrangement.spacedBy(3.dp)
				) {
					Text(
						text = song?.title?.takeIf { it.isNotBlank() } ?: "未知曲目",
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.SemiBold,
						color = MaterialTheme.colorScheme.onSurface,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis
					)
					val subtitle = listOfNotNull(
						song?.artistName?.takeIf { it.isNotBlank() }
							?: song?.artists?.joinToString(", ") { it.name }?.takeIf { it.isNotBlank() },
						song?.albumTitle?.takeIf { it.isNotBlank() },
						song?.year?.takeIf { it > 0 }?.toString()
					).joinToString(" · ")
					if (subtitle.isNotEmpty()) {
						Text(
							text = subtitle,
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							maxLines = 2,
							overflow = TextOverflow.Ellipsis
						)
					}
				}
			}
		}

		when {
			songState is UiState.Loading && song == null -> {
				MeloGroupedCard {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(20.dp),
						horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
						verticalAlignment = Alignment.CenterVertically
					) {
						CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
						Text(
							text = "加载中…",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
					}
				}
			}

			songState is UiState.Error && song == null -> {
				MeloGroupedCard {
					MeloQuietEmptyState(
						message = "未找到歌曲信息",
						icon = Icons.Outlined.Error
					)
				}
			}

			else -> {
				if (coreEntries.isNotEmpty()) {
					SongInfoKeyValueGroup(
						title = "基本信息",
						entries = coreEntries,
						copiedKey = copiedKey,
						onCopy = onCopy
					)
				}
				if (audioEntries.isNotEmpty()) {
					SongInfoKeyValueGroup(
						title = "音频规格",
						entries = audioEntries,
						copiedKey = copiedKey,
						onCopy = onCopy
					)
				}
				if (fileEntries.isNotEmpty()) {
					SongInfoKeyValueGroup(
						title = "文件与来源",
						entries = fileEntries,
						copiedKey = copiedKey,
						onCopy = onCopy
					)
				}
			}
		}

		Spacer(Modifier.height(bottomPadding))
	}
}

@Composable
private fun SongInfoKeyValueGroup(
	title: String,
	entries: List<SongInfoEntry>,
	copiedKey: Int?,
	onCopy: (Int, String, String) -> Unit
) {
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		MeloSectionLabel(title = title)
		MeloGroupedCard {
			entries.forEachIndexed { index, entry ->
				val isCopied = copiedKey == entry.id
				val cleanValue = entry.value

				if (entry.isMultiline) {
					Column(
						modifier = Modifier
							.fillMaxWidth()
							.clickable(enabled = cleanValue != null) {
								cleanValue?.let { onCopy(entry.id, entry.label, it) }
							}
							.padding(horizontal = 16.dp, vertical = 12.dp),
						verticalArrangement = Arrangement.spacedBy(4.dp)
					) {
						Row(
							modifier = Modifier.fillMaxWidth(),
							horizontalArrangement = Arrangement.SpaceBetween,
							verticalAlignment = Alignment.CenterVertically
						) {
							Text(
								text = entry.label,
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant
							)
							if (isCopied) {
								Text(
									text = "已复制",
									style = MaterialTheme.typography.labelSmall,
									color = MaterialTheme.colorScheme.positive
								)
							}
						}
						Text(
							text = cleanValue ?: "—",
							style = MaterialTheme.typography.bodySmall.copy(
								fontFamily = FontFamily.Monospace
							),
							color = if (cleanValue != null) {
								MaterialTheme.colorScheme.onSurface
							} else {
								MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
							},
							maxLines = 4,
							overflow = TextOverflow.Ellipsis
						)
					}
				} else {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.clickable(enabled = cleanValue != null) {
								cleanValue?.let { onCopy(entry.id, entry.label, it) }
							}
							.padding(horizontal = 16.dp, vertical = 12.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(16.dp)
					) {
						Text(
							text = entry.label,
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
						Text(
							text = if (isCopied) "已复制" else (cleanValue ?: "—"),
							style = MaterialTheme.typography.bodyMedium.tabularNums(),
							color = when {
								isCopied -> MaterialTheme.colorScheme.positive
								cleanValue != null -> MaterialTheme.colorScheme.onSurface
								else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
							},
							textAlign = TextAlign.End,
							maxLines = 2,
							overflow = TextOverflow.Ellipsis,
							modifier = Modifier.weight(1f)
						)
					}
				}

				if (index < entries.lastIndex) {
					MeloHairlineDivider(startIndent = 16.dp)
				}
			}
		}
	}
}

private fun normalizeSongInfoValue(keyRes: Int, rawValue: String?): String? {
	val clean = rawValue?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
		?: return null
	if (clean.equals("External", ignoreCase = true)) {
		return "本地库"
	}
	return when {
		keyRes == R.string.info_track_bit_depth && !clean.contains("bit", ignoreCase = true) ->
			"$clean bit"
		keyRes == R.string.info_track_channel_count && clean.all(Char::isDigit) ->
			"$clean 声道"
		else -> clean
	}
}

private fun songInfoLabel(keyRes: Int): String = when (keyRes) {
	R.string.info_track_name -> "名称"
	R.string.info_track_artist -> "歌手"
	R.string.info_track_album -> "专辑"
	R.string.info_track_number -> "音轨"
	R.string.info_track_disc_number -> "碟号"
	R.string.info_track_year -> "年份"
	R.string.info_track_genre -> "流派"
	R.string.info_track_duration -> "时长"
	R.string.info_track_format -> "格式"
	R.string.info_track_bitrate -> "比特率"
	R.string.info_track_bit_depth -> "位深"
	R.string.info_track_sampling_rate -> "采样率"
	R.string.info_track_channel_count -> "声道"
	R.string.info_track_file_size -> "体积"
	R.string.info_track_path -> "路径"
	R.string.info_track_replay_gain -> "音轨增益"
	R.string.info_album_replay_gain -> "专辑增益"
	R.string.title_statistics -> "播放次数"
	else -> "属性"
}
