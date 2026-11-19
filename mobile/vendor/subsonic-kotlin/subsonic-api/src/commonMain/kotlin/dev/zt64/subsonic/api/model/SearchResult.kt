package dev.zt64.subsonic.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Search results
 *
 * @property artists List of artists or empty if none found
 * @property albums List of albums or empty if none found
 * @property songs List of songs or empty if none found
 */
@Serializable
public data class SearchResult internal constructor(
    @SerialName("artist")
    val artists: List<Artist> = emptyList(),
    @SerialName("album")
    val albums: List<Album> = emptyList(),
    @SerialName("song")
    val songs: List<Song> = emptyList()
)