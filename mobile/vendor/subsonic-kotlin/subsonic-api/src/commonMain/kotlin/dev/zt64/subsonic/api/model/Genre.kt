package dev.zt64.subsonic.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Genre
 *
 * @property name Name of the genre
 * @property albumCount Number of albums associated with this genre
 * @property songCount Number of songs associated with this genre
 */
@Serializable
public data class Genre internal constructor(
    @SerialName("value")
    val name: String,
    val albumCount: Int = 0,
    val songCount: Int = 0
)