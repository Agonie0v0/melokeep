package dev.zt64.subsonic.api.model

import dev.zt64.subsonic.api.model.serializer.LenientInstantSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * A configured music folder (top-level root folder).
 *
 * @property id Unique music folder ID
 * @property name Name of the music folder
 */
@Serializable
public data class MusicFolder internal constructor(
    val id: Int,
    val name: String
)

/**
 * A browsable directory within a music library.
 *
 * @property id Unique directory ID
 * @property name Name of the directory
 * @property parent ID of the parent directory, or null if this is a root directory
 * @property starredAt Time at which this directory was starred, or null if not starred
 * @property userRating User rating (1-5), or null if not rated
 * @property averageRating Average community rating (1.0-5.0), or null if unrated
 * @property playCount Number of times this directory has been played, or null if unknown
 * @property items Child entries contained in this directory
 */
@Serializable
public data class Directory internal constructor(
    val id: String,
    val name: String,
    val parent: String?,
    @Serializable(with = LenientInstantSerializer::class)
    @SerialName("starred")
    val starredAt: Instant?,
    val userRating: Int?,
    val averageRating: Float?,
    val playCount: Int = 0,
    @SerialName("entry")
    val items: List<SubsonicResource> = emptyList()
) {
    public val isStarred: Boolean
        get() = starredAt != null
}