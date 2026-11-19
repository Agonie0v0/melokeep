/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.domain.model.settings

import kotlinx.serialization.Serializable
import paige.navic.R

@Serializable
data class NavbarTab(
	val id: Id,
	val visible: Boolean
) {
	@Serializable
	enum class Id(val displayName: Int) {
		LIBRARY(R.string.title_library),
		ALBUMS(R.string.title_albums),
		MELO_DISCOVER(R.string.title_melo_discover),
		MELO_STUDIO(R.string.title_melo_studio),
		MELO_AI(R.string.title_melo_ai),
		PLAYLISTS(R.string.title_playlists),
		ARTISTS(R.string.title_artists),
		SEARCH(R.string.title_search),
		GENRES(R.string.title_genres),
		SONGS(R.string.title_songs),
		RADIOS(R.string.title_radios),
		STATISTICS(R.string.title_statistics)
	}
}
