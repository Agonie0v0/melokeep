package dev.zt64.subsonic.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Bookmark information for resuming playback
 *
 * @param T The type of resource being bookmarked (song, video, etc.)
 * @property position Playback position in milliseconds
 * @property username User who created the bookmark
 * @property comment User comment or note
 * @property createdAt Timestamp when bookmark was created
 * @property modifiedAt Timestamp when bookmark was last modified
 * @property entry The bookmarked resource
 */
@Serializable
public data class Bookmark<T : SubsonicResource> internal constructor(
    val position: Int,
    val username: String?,
    val comment: String,
    @SerialName("created")
    val createdAt: Instant,
    @SerialName("changed")
    val modifiedAt: Instant,
    val entry: T
)