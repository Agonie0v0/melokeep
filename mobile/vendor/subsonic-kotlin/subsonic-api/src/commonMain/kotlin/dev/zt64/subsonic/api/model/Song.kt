package dev.zt64.subsonic.api.model

import dev.zt64.subsonic.api.model.serializer.GenresSerializer
import dev.zt64.subsonic.api.model.serializer.LenientInstantSerializer
import dev.zt64.subsonic.api.model.serializer.SubsonicDurationSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Represents a music track or video in the media library
 *
 * @property id Unique identifier for the song
 * @property title Name of the song
 * @property artistName Name of the artist
 * @property artistId Unique identifier for the artist
 * @property albumTitle Name of the album
 * @property albumId Unique identifier for the album
 * @property parentId ID of the parent directory
 * @property comment The comment tag of the song
 * @property trackNumber Track number on the album
 * @property discNumber Disc number for multi-disc albums
 * @property isrc List of ISRC(s)
 * @property year Release year
 * @property genre Music genre
 * @property genres List of all genres of the song
 * @property moods List of moods, or empty if none
 * @property duration Length of the song
 * @property bpm Beats per minute of the song
 * @property contributors List of people who contributed to the song (e.g., composer, performer)
 * @property displayComposer Single composer display string
 * @property playCount Number of times the song has been played
 * @property userRating User's rating (1-5)
 * @property averageRating Average rating across all users
 * @property bitRate Audio bit rate in kilobits per second
 * @property bitDepth Audio bit depth
 * @property sampleRate Audio sample rate in hertz
 * @property audioChannelCount Number of audio channels
 * @property replayGain Replay gain info
 * @property explicitStatus Explicit content status of the song
 * @property bookmarkPosition The bookmark position in seconds
 * @property fileSize Size of the file in bytes
 * @property fileExtension File extension (e.g., mp3, flac)
 * @property mimeType MIME type of the file
 * @property filePath File system path
 * @property works List of works, typically associated with classical songs
 * @property movements List of movements, typically associated with classical songs
 * @property transcodedContentType Transcoded media type if transcoding should be used
 * @property transcodedSuffix Suffix of the transcoded media
 * @property starredAt Timestamp when the song was starred, if applicable
 * @property coverArtId ID of the cover art image
 */
@Serializable
public data class Song internal constructor(
    override val id: String,
    val title: String,
    @SerialName("artist")
    val artistName: String? = null,
    val artistId: String? = null,
    val artists: List<SongArtist> = emptyList(),
    val albumArtists: List<SongArtist> = emptyList(),
    val displayArtist: String? = null,
    val displayAlbumArtist: String? = null,
    @SerialName("album")
    val albumTitle: String? = null,
    val albumId: String? = null,
    @SerialName("parent")
    val parentId: String? = null,
    val comment: String? = null,
    @SerialName("track")
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val isrc: List<String> = emptyList(),
    val year: Int? = null,
    val genre: String? = null,
    @Serializable(GenresSerializer::class)
    val genres: List<String> = emptyList(),
    val moods: List<String> = emptyList(),
    @Serializable(SubsonicDurationSerializer::class)
    val duration: Duration? = null,
    val bpm: Int? = null,
    val contributors: List<Contributor> = emptyList(),
    val displayComposer: String? = null,
    val playCount: Int = 0,
    val userRating: Int? = null,
    val averageRating: Float? = null,
    @SerialName("bitRate")
    val bitRate: Int? = null,
    val bitDepth: Int? = null,
    @SerialName("samplingRate")
    val sampleRate: Int? = null,
    @SerialName("channelCount")
    val audioChannelCount: Int? = null,
    val replayGain: ReplayGain? = null,
    val explicitStatus: ExplicitStatus? = null,
    @Serializable(SubsonicDurationSerializer::class)
    val bookmarkPosition: Duration? = null,
    @SerialName("size")
    val fileSize: Long? = null,
    @SerialName("suffix")
    val fileExtension: String? = null,
    @SerialName("contentType")
    val mimeType: String? = null,
    @SerialName("path")
    val filePath: String? = null,
    val works: List<Work> = emptyList(),
    val movements: List<Movement> = emptyList(),
    val transcodedContentType: String? = null,
    val transcodedSuffix: String? = null,
    override val sortName: String? = null,
    @Serializable(with = LenientInstantSerializer::class)
    @SerialName("starred")
    override val starredAt: Instant? = null,
    @SerialName("coverArt")
    override val coverArtId: String? = null,
    override val musicBrainzId: String? = null,
    override val isExternal: Boolean = false
) : SubsonicResource {
    /**
     * Type of media content
     */
    @Serializable
    public enum class Type {
        /** Audio file */
        MUSIC,

        /** Video file */
        VIDEO
    }

    /**
     * Replay gain data of a song
     *
     * @property albumGain Album gain value in dB
     * @property albumPeak Album peak value
     * @property trackGain Track gain value in dB
     * @property trackPeak Track peak value
     * @property baseGain Base gain value in dB
     * @property fallbackGain Optional fallback gain in dB clients should apply when the corresponding
     * gain value is missing.
     */
    @Serializable
    public data class ReplayGain internal constructor(
        val albumGain: Float?,
        val albumPeak: Float?,
        val trackGain: Float?,
        val trackPeak: Float?,
        val baseGain: Float?,
        val fallbackGain: Float?
    )

    /**
     * Explicit status
     */
    @Serializable
    public enum class ExplicitStatus {
        @SerialName("explicit")
        EXPLICIT,

        @SerialName("clean")
        CLEAN,

        @SerialName("")
        UNKNOWN
    }

    @Serializable
    public data class SongArtist(val id: String, val name: String)

    @Serializable
    public data class Work(val name: String, val musicBrainzId: String? = null)

    /**
     * @property name Name of the movement
     * @property number Number of the movement
     * @property count How many times the movement occurs
     */
    @Serializable
    public data class Movement internal constructor(
        val name: String,
        val number: Int?,
        val count: Int?
    )
}

/**
 * Represents a person who contributed to a song (e.g., composer, performer, engineer)
 *
 * @property role Primary role of the contributor
 * @property subRole Specific sub-role or additional details
 */
@Serializable
public data class Contributor internal constructor(
    val role: String,
    val subRole: String?,
    val artist: Artist
)

/**
 * Plain text lyrics for a song
 *
 * @property artistName Name of the artist
 * @property title Title of the song
 * @property value Lyrics text content
 */
@Serializable
public data class Lyrics internal constructor(
    @SerialName("artist")
    val artistName: String,
    val title: String,
    val value: String
)

/**
 * Structured lyrics with timing information for synchronized playback
 *
 * @property lang Language code of the lyrics (e.g., "en", "ja")
 * @property synced Whether the lyrics are synchronized with timestamps
 * @property displayArtist Artist name to display
 * @property displayTitle Song title to display
 * @property offset Global time offset in milliseconds
 * @property lines Individual lyric lines with timing information
 */
@Serializable
public data class StructuredLyrics internal constructor(
    val lang: String,
    val synced: Boolean,
    val displayArtist: String,
    val displayTitle: String,
    val offset: Int = 0,
    @SerialName("line")
    val lines: List<Line> = emptyList()
) {
    /**
     * A single line of lyrics with timing information
     *
     * @property start Start time in milliseconds
     * @property value Lyric text for this line
     */
    @Serializable
    public data class Line internal constructor(
        val start: Int?,
        val value: String
    )
}