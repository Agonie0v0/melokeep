/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.screen.nowPlaying.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import paige.navic.domain.model.DomainSong
import paige.navic.ui.component.common.CoverArt
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.filled.Note

@Composable
fun NowPlayingArtwork(
	modifier: Modifier = Modifier,
	onClick: (() -> Unit)?,
	isLandscape: Boolean,
	song: DomainSong
) {
	// 统一温和 12.dp 边距，暂停呼吸缩放完全交由外层 NowPlayingCoverStage 单层弹簧动画承担
	Box(
		modifier = modifier,
		contentAlignment = Alignment.Center
	) {
		CoverArt(
			coverArtId = song.coverArtId,
			modifier = Modifier
				.aspectRatio(1f)
				.then(if (isLandscape) Modifier.fillMaxHeight() else Modifier.fillMaxSize())
				.padding(12.dp),
			shadowElevation = 8.dp,
			onClick = onClick
		)
		if (song.coverArtId == null) {
			Icon(
				imageVector = Icons.Filled.Note,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
				modifier = Modifier.size(96.dp)
			)
		}
	}
}
