/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.domain.manager.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlin.math.abs

class BottomBarScrollManager(val thresholdPx: Float) {
	var isTriggered by mutableStateOf(false)
	private var accumulator = 0f
	private var lastToggleTimeMs = 0L

	private val hideThresholdPx: Float = thresholdPx.coerceAtLeast(16f)
	private val showThresholdPx: Float = (thresholdPx * 0.35f).coerceAtLeast(12f)

	fun reset() {
		isTriggered = false
		accumulator = 0f
		lastToggleTimeMs = 0L
	}

	private fun isWithinCooldown(cooldownMs: Long): Boolean {
		val last = lastToggleTimeMs
		if (last == 0L) return false
		return (SystemClock.elapsedRealtime() - last) < cooldownMs
	}

	val connection = object : NestedScrollConnection {
		override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
			if (!isTriggered) return Offset.Zero
			val deltaY = available.y
			// 过滤微小像素噪声与横向为主的滑动（避免横滑画廊/标签栏误触发）
			if (abs(deltaY) < 1f || abs(available.x) > abs(deltaY) * 1.15f) {
				return Offset.Zero
			}
			// 刚折叠后短时间内忽略惯性/回弹 SideEffect 反向抖动
			if (source == NestedScrollSource.SideEffect && isWithinCooldown(220L)) {
				return Offset.Zero
			}
			// 仍在下滑时重置正向累计，保证必须连续轻微上滑才恢复
			if (deltaY < 0f) {
				accumulator = 0f
				return Offset.Zero
			}

			accumulator += deltaY
			if (accumulator >= showThresholdPx) {
				isTriggered = false
				accumulator = 0f
				lastToggleTimeMs = SystemClock.elapsedRealtime()
			}
			return Offset.Zero
		}

		override fun onPostScroll(
			consumed: Offset,
			available: Offset,
			source: NestedScrollSource
		): Offset {
			if (isTriggered) return Offset.Zero
			val deltaY = consumed.y
			// 过滤微小抖动与横向列表滚动
			if (abs(deltaY) < 1f || abs(consumed.x) > abs(deltaY) * 1.15f) {
				return Offset.Zero
			}
			// 刚展开后短时间内忽略惯性/回弹 SideEffect 误折叠
			if (source == NestedScrollSource.SideEffect && isWithinCooldown(220L)) {
				return Offset.Zero
			}
			// 向上滚（手指下拉）则重置负向累计
			if (deltaY > 0f) {
				accumulator = 0f
				return Offset.Zero
			}

			accumulator += deltaY
			if (accumulator <= -hideThresholdPx) {
				isTriggered = true
				accumulator = 0f
				lastToggleTimeMs = SystemClock.elapsedRealtime()
			}
			return Offset.Zero
		}
	}
}
