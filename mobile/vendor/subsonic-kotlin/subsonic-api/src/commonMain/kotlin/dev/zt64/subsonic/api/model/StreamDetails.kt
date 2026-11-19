package dev.zt64.subsonic.api.model

import kotlinx.serialization.Serializable

/**
 * Provides details about a media stream.
 *
 * @param protocol The streaming protocol. Can be http or hls.
 * @param container The container format.
 * @param codec The audio codec.
 * @param audioChannels The number of audio channels.
 * @param audioBitrate The audio bitrate.
 * @param audioProfile The audio profile.
 * @param audioSamplerate The audio sample rate.
 * @param audioBitdepth The audio bit depth.
 */
@Serializable
public data class StreamDetails internal constructor(
    val audioBitdepth: Int?,
    val audioBitrate: Int?,
    val audioChannels: Int?,
    val audioProfile: String = "",
    val audioSamplerate: Int?,
    val codec: String,
    val container: String,
    val protocol: String
)