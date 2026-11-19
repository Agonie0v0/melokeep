/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.screen.nowPlaying.component.controls

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.model.settings.NowPlayingSliderStyle
import paige.navic.playback.MediaPlayer
import paige.navic.ui.component.common.SlimSlider
import paige.navic.ui.component.common.SpermSlider
import paige.navic.ui.component.common.SpermSliderDefaults

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingProgressBar() {
	val preferenceManager = koinInject<PreferenceManager>()
	val player = koinInject<MediaPlayer>()
	val enabled by remember(player) {
		player.uiState
			.map { it.currentSong != null }
			.distinctUntilChanged()
	}.collectAsStateWithLifecycle(initialValue = player.uiState.value.currentSong != null)
	val isPaused by remember(player) {
		player.uiState
			.map { it.isPaused }
			.distinctUntilChanged()
	}.collectAsStateWithLifecycle(initialValue = player.uiState.value.isPaused)
	val progress by player.progressFlow.collectAsStateWithLifecycle()
	val sliderState = rememberSliderState(value = progress)
	val onValueChange: (Float) -> Unit = remember(player, sliderState) {
		{ value ->
			sliderState.value = value
			player.seek(value)
		}
	}

	LaunchedEffect(progress, isPaused) {
		sliderState.value = progress
	}

	when (preferenceManager.nowPlayingSliderStyle) {
		NowPlayingSliderStyle.Flat -> {
			Slider(
				state = sliderState,
				onValueChange = onValueChange,
				modifier = Modifier.padding(horizontal = 16.dp),
				enabled = enabled
			)
		}

		NowPlayingSliderStyle.Squiggly, NowPlayingSliderStyle.Yoyo -> {
			val isYoyo = preferenceManager.nowPlayingSliderStyle == NowPlayingSliderStyle.Yoyo
			SpermSlider(
				state = sliderState,
				onValueChange = onValueChange,
				modifier = Modifier.padding(horizontal = if (isYoyo) 7.dp else 14.dp),
				thumb = {
					SliderDefaults.Thumb(
						enabled = enabled,
						thumbSize = if (isYoyo) DpSize(20.dp, 20.dp) else DpSize(4.dp, 32.dp),
						interactionSource = remember { MutableInteractionSource() }
					)
				},
				track = { sliderState ->
					SpermSliderDefaults.Track(
						sliderState = sliderState,
						gapSize = if (isYoyo) 0.dp else 6.dp,
						wavelength = if (isYoyo) 32.dp else 26.dp,
						amplitude = { if (!isPaused) 1f else 0f }
					)
				},
				enabled = enabled
			)
		}

		NowPlayingSliderStyle.Slim -> {
			SlimSlider(
				state = sliderState,
				onValueChange = onValueChange,
				modifier = Modifier.padding(horizontal = 16.dp),
				enabled = enabled
			)
		}
	}
}
