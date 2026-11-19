package dev.zt64.subsonic.api.model

import kotlinx.serialization.Serializable

@Serializable
public data class TranscodeDecision internal constructor(
    val canDirectPlay: Boolean,
    val canTranscode: Boolean,
    val errorReason: String?,
    val transcodeParams: String?,
    val sourceStream: StreamDetails?,
    val transcodeStream: StreamDetails?
)

@Serializable
public enum class MediaType {
    SONG,
    PODCAST
}