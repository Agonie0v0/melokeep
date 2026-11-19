package dev.zt64.subsonic.api.model

import dev.zt64.subsonic.api.model.serializer.LenientInstantSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Podcast status
 */
@Serializable
public enum class PodcastStatus {
    @SerialName("new")
    NEW,

    @SerialName("downloading")
    DOWNLOADING,

    @SerialName("completed")
    COMPLETED,

    @SerialName("error")
    ERROR,

    @SerialName("skipped")
    SKIPPED
}

/**
 * Podcast channel information
 *
 * @property id Unique channel identifier
 * @property title Channel title
 * @property url Channel URL
 * @property description Channel description
 * @property coverArtId Cover art ID
 * @property originalImageUrl Original cover art URL
 * @property status Channel status
 * @property errorMessage Error message
 * @property episodes List of podcast episodes
 */
@Serializable
public data class PodcastChannel internal constructor(
    val id: String,
    val title: String?,
    val url: String,
    val description: String?,
    @SerialName("coverArt")
    val coverArtId: String?,
    val originalImageUrl: String?,
    val status: PodcastStatus,
    val errorMessage: String?,
    @SerialName("episode")
    val episodes: List<PodcastEpisode> = emptyList()
)

/**
 * Podcast episode information
 *
 * @property id Unique episode identifier
 * @property streamId Stream ID for playback
 * @property channelId Parent channel identifier
 * @property description Episode description or show notes
 * @property coverArtId Cover art ID
 * @property status Episode download status
 * @property publishDate Episode publication date
 * @property starredAt Timestamp when starred, or null if not starred
 */
@Serializable
public data class PodcastEpisode internal constructor(
    override val id: String,
    val streamId: String?,
    val channelId: String,
    val description: String?,
    @SerialName("coverArt")
    override val coverArtId: String?,
    val status: PodcastStatus,
    @Serializable(with = LenientInstantSerializer::class)
    val publishDate: Instant?,
    @Serializable(with = LenientInstantSerializer::class)
    @SerialName("starred")
    override val starredAt: Instant?,
    override val musicBrainzId: String?,
    override val isExternal: Boolean = false,
    override val sortName: String?
) : SubsonicResource