package dev.zt64.subsonic.api.model

import kotlinx.serialization.Serializable

@Serializable
public enum class PlaybackState {
    STARTING,
    PLAYING,
    PAUSED,
    STOPPED
}