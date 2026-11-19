package dev.zt64.subsonic.api.model

import dev.zt64.subsonic.api.model.serializer.LenientInstantSerializer
import dev.zt64.subsonic.api.model.serializer.SubsonicDurationSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Collection of audio tracks
 *
 * @property id Unique identifier
 * @property name Collection name
 * @property coverArtId Cover art ID
 * @property duration Total duration
 * @property songCount Number of songs
 * @property songs List of songs
 */
@Serializable
public sealed interface SongCollection {
    public val id: String
    public val name: String?

    @SerialName("coverArt")
    public val coverArtId: String?

    @Serializable(SubsonicDurationSerializer::class)
    public val duration: Duration?
    public val songCount: Int
    public val songs: List<Song>
}

/**
 * Playlist information
 *
 * @property id Unique playlist identifier
 * @property name Playlist name
 * @property owner Username of the playlist owner
 * @property comment Playlist description or comment
 * @property coverArtId Cover art ID
 * @property songCount Number of songs in the playlist
 * @property duration Total playlist duration
 * @property createdAt Timestamp when playlist was created
 * @property modifiedAt Timestamp when playlist was last modified
 * @property public Whether the playlist is public
 * @property readOnly Whether the playlist is read-only
 * @property allowedUsers List of usernames with access to the playlist
 * @property validUntil Expiration timestamp for temporary playlists
 * @property songs List of songs in the playlist
 */
@Serializable
public data class Playlist internal constructor(
    override val id: String,
    override val name: String,
    val owner: String? = null,
    val comment: String? = null,
    @SerialName("coverArt")
    override val coverArtId: String? = null,
    override val songCount: Int = 0,
    @Serializable(SubsonicDurationSerializer::class)
    override val duration: Duration,
    @SerialName("created")
    val createdAt: Instant,
    @SerialName("changed")
    val modifiedAt: Instant,
    val public: Boolean? = null,
    @SerialName("readonly")
    val readOnly: Boolean? = null,
    val allowedUsers: List<String> = emptyList(),
    @Serializable(with = LenientInstantSerializer::class)
    val validUntil: Instant? = null,
    @SerialName("entry")
    override val songs: List<Song> = emptyList()
) : SongCollection