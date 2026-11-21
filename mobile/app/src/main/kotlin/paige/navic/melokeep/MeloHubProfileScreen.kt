/*
 * Navic / MeloKeep, a Subsonic music streaming & studio app for Android
 * Copyright (c) 2026 paige & MeloKeep Swarm
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.melokeep

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import paige.navic.ui.theme.MeloBottomChromeClearance

/**
 * 第四 Tab「我的」：顶部双服务状态总览卡 + 4 大功能域分组极简工作台。
 */
@Composable
fun MeloHubProfileScreen(
	onOpenOnboarding: () -> Unit = {},
	onOpenPhoneDownloads: (() -> Unit)? = null
) {
	Column(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colorScheme.background)
	) {
		MeloCleanTopBar(
			title = "我的",
			applyStatusBarPadding = true
		)

		MeloProfileHubBody(
			modifier = Modifier
				.weight(1f)
				.fillMaxWidth(),
			bottomContentPadding = MeloBottomChromeClearance + 16.dp,
			onOpenOnboarding = onOpenOnboarding,
			onOpenPhoneDownloads = onOpenPhoneDownloads
		)
	}
}
