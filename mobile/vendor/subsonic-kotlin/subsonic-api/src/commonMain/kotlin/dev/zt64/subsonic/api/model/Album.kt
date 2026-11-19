package dev.zt64.subsonic.api.model

import dev.zt64.subsonic.api.model.serializer.GenresSerializer
import dev.zt64.subsonic.api.model.serializer.LenientInstantSerializer
import dev.zt64.subsonic.api.model.serializer.SubsonicDurationSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Album information
 *
 * @property id Unique album identifier
 * @property name Album name
 * @property artistName Artist name
 * @property artistId Artist identifier
 * @property artists List of album artists
 * @property displayArtist Single artist display string
 * @property year Release year
 * @property coverArtId Cover art ID
 * @property genre Album genre
 * @property genres List of genres
 * @property moods List of moods
 * @property duration Total album duration
 * @property createdAt Timestamp when added to library
 * @property starredAt Timestamp when starred, or null if not starred
 * @property lastPlayedAt Timestamp when last played
 * @property playCount Number of times played
 * @property userRating User rating (1-5)
 * @property version Album version or edition
 * @property originalReleaseDate Date the album was originally released
 * @property releaseDate Date the specific edition of the album was released
 * @property recordLabels List of record labels of the album
 * @property releaseTypes List of release types e.g. "Album", "Remixes"
 * @property sortName Name to be used for sorting
 * @property musicBrainzId MusicBrainz identifier
 * @property songs List of songs in the album
 * @property songCount Number of songs in the album
 * @property isCompilation True if the album is a compilation
 * @property discs List of discs in the album
 */
@Serializable
public data class Album internal constructor(
    override val id: String,
    override val name: String? = null,
    @SerialName("artist")
    val artistName: String? = null,
    val artistId: String? = null,
    val artists: List<Artist> = emptyList(),
    val displayArtist: String? = null,
    val year: Int? = null,
    @SerialName("coverArt")
    override val coverArtId: String? = null,
    val genre: String? = null,
    @Serializable(GenresSerializer::class)
    val genres: List<String> = emptyList(),
    val moods: List<String> = emptyList(),
    @Serializable(SubsonicDurationSerializer::class)
    override val duration: Duration? = null,
    @SerialName("created")
    val createdAt: Instant,
    @Serializable(with = LenientInstantSerializer::class)
    @SerialName("starred")
    override val starredAt: Instant? = null,
    @Serializable(with = LenientInstantSerializer::class)
    @SerialName("played")
    val lastPlayedAt: Instant? = null,
    val playCount: Int = 0,
    val userRating: Int? = null,
    val version: String? = null,
    val originalReleaseDate: ItemDate? = null,
    val releaseDate: ItemDate? = null,
    val recordLabels: List<RecordLabel> = emptyList(),
    val releaseTypes: List<String> = emptyList(),
    override val sortName: String? = null,
    override val musicBrainzId: String? = null,
    override val songCount: Int = 0,
    @SerialName("song")
    override val songs: List<Song> = emptyList(),
    val isCompilation: Boolean = false,
    @SerialName("discTitles")
    val discs: List<Disc> = emptyList(),
    override val isExternal: Boolean = false
) : SubsonicResource, SongCollection {
    /**
     * @property disc Number of the disc
     * @property title Title of the disc
     * @property coverArtId ID of the disc cover art
     */
    @Serializable
    public data class Disc(
        val disc: Int,
        val title: String,
        @SerialName("coverArt")
        val coverArtId: String? = null
    )

    /**
     * @property name Name of the record label
     */
    @Serializable
    public data class RecordLabel(val name: String)
}

/**
 * Detailed album information from Last.fm
 *
 * @property musicBrainzId MusicBrainz identifier
 * @property largeImageUrl Large album image URL
 * @property mediumImageUrl Medium album image URL
 * @property smallImageUrl Small album image URL
 * @property lastFmUrl Last.fm album page URL
 * @property notes Album notes or description
 */
@Serializable
public data class AlbumInfo internal constructor(
    val musicBrainzId: String?,
    val largeImageUrl: String?,
    val mediumImageUrl: String?,
    val smallImageUrl: String?,
    val lastFmUrl: String?,
    val notes: String?
)

/**
 * Album list filtering type
 *
 * @property value The API parameter value
 */
@Serializable
public sealed class AlbumListType(public val value: String) {
    /** Random albums */
    @Serializable
    public data object Random : AlbumListType("random")

    /** Newest albums */
    @Serializable
    public data object Newest : AlbumListType("newest")

    /** Highest rated albums */
    @Serializable
    public data object Highest : AlbumListType("highest")

    /** Most frequently played albums */
    @Serializable
    public data object Frequent : AlbumListType("frequent")

    /** Recently played albums */
    @Serializable
    public data object Recent : AlbumListType("recent")

    /** Starred albums */
    @Serializable
    public data object Starred : AlbumListType("starred")

    /** Alphabetically sorted by album name */
    @Serializable
    public data object AlphabeticalByName : AlbumListType("alphabeticalByName")

    /** Alphabetically sorted by artist name */
    @Serializable
    public data object AlphabeticalByArtist : AlbumListType("alphabeticalByArtist")

    /**
     * Albums released within a year range
     *
     * @property fromYear Start year (inclusive)
     * @property toYear End year (inclusive)
     */
    @Serializable
    public data class ByYear(val fromYear: Int, val toYear: Int) : AlbumListType("byYear")

    /**
     * Albums by genre
     *
     * @property genre Genre name
     */
    @Serializable
    public data class ByGenre(val genre: String) : AlbumListType("byGenre")
}