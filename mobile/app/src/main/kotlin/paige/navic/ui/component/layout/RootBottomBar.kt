/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.component.layout

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import paige.navic.di.LocalSizeClass

/**
 * 轻量空安全占位，避免子页面重复渲染 MiniPlayer 与 BottomBar；
 * 仅在紧凑屏为外层常驻悬浮播放舱预留底部滚动安全区。
 */
@Composable
fun RootBottomBar(
	scrolled: Boolean,
	modifier: Modifier = Modifier,
	shadows: Boolean = true,
	hideMiniPlayer: Boolean = false,
	windowInsets: WindowInsets = WindowInsets.systemBars
) {
	val sizeClass = LocalSizeClass.current
	if (sizeClass.widthSizeClass >= WindowWidthSizeClass.Medium) return

	val clearanceHeight = if (hideMiniPlayer) 0.dp else 88.dp
	Spacer(
		modifier = modifier
			.fillMaxWidth()
			.windowInsetsPadding(windowInsets.only(WindowInsetsSides.Bottom))
			.height(clearanceHeight)
	)
}
