/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.domain.model.settings

import paige.navic.R

enum class AppIconVariant(
	val activityName: String,
	val designer: String,
	val displayName: Int
) {
	Default("MainActivityDefault", designer = "ssalggnikool", displayName = R.string.option_app_icon_default),
	Inverted("MainActivityInverted", designer = "ssalggnikool", displayName = R.string.option_app_icon_inverted)
}
