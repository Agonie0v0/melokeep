/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.domain.model.settings

import paige.navic.R

enum class MarqueeSpeed(val value: Int, val displayName: Int) {
	Disabled(0, R.string.option_marquee_disabled),
	Slow(6000, R.string.option_marquee_slow),
	Medium(4000, R.string.option_marquee_medium),
	Fast(1000, R.string.option_marquee_fast)
}
