/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.component.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.kmpalette.rememberDominantColorState
import org.koin.compose.koinInject
import org.koin.core.qualifier.named
import paige.navic.domain.manager.session.SessionManager
import paige.navic.ui.util.toImageBitmap
import paige.navic.util.disableHardwareIfCucked
import coil3.compose.LocalPlatformContext as LocalCoilPlatformContext

private const val ART_SAMPLE_PX = 96

/**
 * 轻量级双色径向渐变声场背景：基于封面主色与主题色生成柔和光晕，
 * 移除逐帧旋转与大半径模糊循环，保障高帧率丝滑体验。
 */
@Composable
fun BlendBackground(
	coverArtId: String?,
	modifier: Modifier = Modifier,
	isPaused: Boolean = false
) {
	val coilPlatformContext = LocalCoilPlatformContext.current
	val staticImageLoader = koinInject<ImageLoader>(named("static"))
	val sessionManager = koinInject<SessionManager>()
	val dominantColorState = rememberDominantColorState(cacheSize = 6)

	var hasExtracted by remember(coverArtId) { mutableStateOf(false) }

	val model = remember(coverArtId, coilPlatformContext) {
		coverArtId?.let { id ->
			ImageRequest.Builder(coilPlatformContext)
				.disableHardwareIfCucked()
				.data(sessionManager.getCoverArtUrl(id))
				.size(ART_SAMPLE_PX, ART_SAMPLE_PX)
				.memoryCacheKey("${id}_ambient_$ART_SAMPLE_PX")
				.diskCacheKey(id)
				.diskCachePolicy(CachePolicy.ENABLED)
				.memoryCachePolicy(CachePolicy.ENABLED)
				.build()
		}
	}

	LaunchedEffect(model) {
		if (model == null) {
			hasExtracted = false
			return@LaunchedEffect
		}
		val result = staticImageLoader.execute(model)
		val bitmap = result.image?.toImageBitmap()
		if (bitmap != null) {
			dominantColorState.updateFrom(bitmap)
			hasExtracted = true
		} else {
			hasExtracted = false
		}
	}

	val fallbackPrimary = MaterialTheme.colorScheme.primary
	val fallbackSecondary = MaterialTheme.colorScheme.tertiary
	val baseBackground = MaterialTheme.colorScheme.background
	val isLight = baseBackground.luminance() >= 0.5f

	val targetPrimary = if (hasExtracted) dominantColorState.color else fallbackPrimary
	val targetSecondary = if (hasExtracted) {
		lerp(dominantColorState.color, fallbackSecondary, 0.42f)
	} else {
		fallbackSecondary
	}

	val animatedPrimary by animateColorAsState(
		targetValue = targetPrimary,
		animationSpec = tween(durationMillis = 650),
		label = "AmbientPrimaryGlow"
	)
	val animatedSecondary by animateColorAsState(
		targetValue = targetSecondary,
		animationSpec = tween(durationMillis = 650),
		label = "AmbientSecondaryGlow"
	)
	val glowAlpha by animateFloatAsState(
		targetValue = if (isLight) {
			if (isPaused) 0.12f else 0.20f
		} else {
			if (isPaused) 0.24f else 0.38f
		},
		animationSpec = tween(durationMillis = 450),
		label = "AmbientGlowAlpha"
	)

	Box(
		modifier = modifier
			.fillMaxSize()
			.background(baseBackground)
	) {
		Canvas(modifier = Modifier.fillMaxSize()) {
			val w = size.width
			val h = size.height
			if (w <= 0f || h <= 0f) return@Canvas

			val maxDim = maxOf(w, h)
			// 左上主声场光晕
			drawRect(
				brush = Brush.radialGradient(
					colors = listOf(
						animatedPrimary.copy(alpha = glowAlpha),
						animatedPrimary.copy(alpha = glowAlpha * 0.35f),
						Color.Transparent
					),
					center = Offset(w * 0.24f, h * 0.22f),
					radius = maxDim * 0.72f
				)
			)

			// 右下辅声场光晕
			drawRect(
				brush = Brush.radialGradient(
					colors = listOf(
						animatedSecondary.copy(alpha = glowAlpha * 0.85f),
						animatedSecondary.copy(alpha = glowAlpha * 0.26f),
						Color.Transparent
					),
					center = Offset(w * 0.80f, h * 0.76f),
					radius = maxDim * 0.68f
				)
			)

			// 柔和暗角/浅雾渐变保障前景控键与歌词可读性
			val maskColors = if (isLight) {
				listOf(
					baseBackground.copy(alpha = 0.08f),
					baseBackground.copy(alpha = 0.18f),
					baseBackground.copy(alpha = 0.30f)
				)
			} else {
				listOf(
					baseBackground.copy(alpha = 0.12f),
					baseBackground.copy(alpha = 0.25f),
					baseBackground.copy(alpha = 0.42f)
				)
			}
			drawRect(
				brush = Brush.verticalGradient(colors = maskColors)
			)
		}
	}
}
