/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.domain.model.settings

import paige.navic.R

enum class FontOption(val displayName: Int) {
	System(R.string.option_system_font),
	GoogleSans(R.string.option_google_sans_font),
	Custom(R.string.option_custom_font)
}
