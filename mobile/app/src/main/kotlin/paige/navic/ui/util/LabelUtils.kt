/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import paige.navic.R
import paige.navic.domain.model.DomainAlbumListType
import paige.navic.domain.model.DomainSongListType
import paige.navic.domain.model.lyrics.LyricsProvider
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

@Composable
fun Duration.label(): String {
	val hours = inWholeHours.toInt()
	val minutes = (this - hours.hours).inWholeMinutes.toInt()

	return when {
		hours > 0 && minutes > 0 ->
			"${pluralStringResource(R.plurals.count_hours, hours, hours)} ${
				pluralStringResource(
					R.plurals.count_minutes,
					minutes,
					minutes
				)
			}"

		hours > 0 ->
			pluralStringResource(R.plurals.count_hours, hours, hours)

		else ->
			pluralStringResource(R.plurals.count_minutes, max(1, minutes), max(1, minutes))
	}
}

@Composable
fun DomainAlbumListType.label(): String = when (this) {
	DomainAlbumListType.Random -> "随机"
	DomainAlbumListType.Newest -> "最新"
	DomainAlbumListType.Frequent -> "常听"
	DomainAlbumListType.Recent -> "最近"
	DomainAlbumListType.AlphabeticalByName -> "名称"
	DomainAlbumListType.AlphabeticalByArtist -> "歌手"
	DomainAlbumListType.Highest -> "评分"
	DomainAlbumListType.Year -> "年份"
	is DomainAlbumListType.ByGenre -> "流派"
	is DomainAlbumListType.ByYear -> "年份"
}

@Composable
fun DomainSongListType.label(): String = when (this) {
	is DomainSongListType.ByArtist -> "歌手"
	is DomainSongListType.ByGenre -> "流派"
	DomainSongListType.FrequentlyPlayed -> "常听"
	DomainSongListType.Newest -> "最新"
	DomainSongListType.Random -> "随机"
	DomainSongListType.Rating -> "评分"
	DomainSongListType.Year -> "年份"
}

fun PaletteStyle.label(): String = when (this) {
	PaletteStyle.TonalSpot -> "Tonal Spot"
	PaletteStyle.Neutral -> "Neutral"
	PaletteStyle.Vibrant -> "Vibrant"
	PaletteStyle.Expressive -> "Expressive"
	PaletteStyle.Rainbow -> "Rainbow"
	PaletteStyle.FruitSalad -> "Fruit Salad"
	PaletteStyle.Monochrome -> "Monochrome"
	PaletteStyle.Fidelity -> "Fidelity"
	PaletteStyle.Content -> "Content"
}

fun ColorSpec.SpecVersion.label() = when (this) {
	ColorSpec.SpecVersion.SPEC_2021 -> "Material 3 (2021)"
	ColorSpec.SpecVersion.SPEC_2025 -> "Expressive (2025)"
}

fun LyricsProvider.Id.label() = when (this) {
	LyricsProvider.Id.SUBSONIC -> "Subsonic"
	LyricsProvider.Id.LYRICS_PLUS -> "LyricsPlus"
	LyricsProvider.Id.LRCLIB -> "LRCLIB"
}
