package dev.zt64.subsonic.api

import dev.zt64.subsonic.api.model.Album
import dev.zt64.subsonic.api.model.AlbumInfo
import dev.zt64.subsonic.api.model.AlbumListType
import dev.zt64.subsonic.api.model.Artist
import dev.zt64.subsonic.api.model.ArtistIndexes
import dev.zt64.subsonic.api.model.ArtistInfo
import dev.zt64.subsonic.api.model.Artists
import dev.zt64.subsonic.api.model.Bookmark
import dev.zt64.subsonic.api.model.ChatMessage
import dev.zt64.subsonic.api.model.ClientInfo
import dev.zt64.subsonic.api.model.Directory
import dev.zt64.subsonic.api.model.Genre
import dev.zt64.subsonic.api.model.InternetRadioStation
import dev.zt64.subsonic.api.model.JukeboxAction
import dev.zt64.subsonic.api.model.License
import dev.zt64.subsonic.api.model.Lyrics
import dev.zt64.subsonic.api.model.MediaType
import dev.zt64.subsonic.api.model.MusicFolder
import dev.zt64.subsonic.api.model.NowPlayingEntry
import dev.zt64.subsonic.api.model.PlayQueue
import dev.zt64.subsonic.api.model.PlaybackState
import dev.zt64.subsonic.api.model.Playlist
import dev.zt64.subsonic.api.model.PodcastChannel
import dev.zt64.subsonic.api.model.PodcastEpisode
import dev.zt64.subsonic.api.model.Role
import dev.zt64.subsonic.api.model.ScanStatus
import dev.zt64.subsonic.api.model.SearchResult
import dev.zt64.subsonic.api.model.Share
import dev.zt64.subsonic.api.model.Song
import dev.zt64.subsonic.api.model.SonicMatch
import dev.zt64.subsonic.api.model.Starred
import dev.zt64.subsonic.api.model.StructuredLyrics
import dev.zt64.subsonic.api.model.SubsonicExtension
import dev.zt64.subsonic.api.model.SubsonicResource
import dev.zt64.subsonic.api.model.SubsonicResponse
import dev.zt64.subsonic.api.model.TokenInfo
import dev.zt64.subsonic.api.model.TranscodeDecision
import dev.zt64.subsonic.api.model.User
import io.ktor.utils.io.ByteReadChannel
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Subsonic API
 */
public interface SubsonicApi {
    /**
     * Used to test connectivity with the server.
     */
    public suspend fun ping(): SubsonicResponse<Nothing>

    /**
     * Returns information about the API key.
     *
     * @return Token information
     */
    public suspend fun tokenInfo(): TokenInfo

    /**
     * Get details about the software license.
     *
     * @return License details
     */
    public suspend fun getLicense(): License

    /**
     * Get supported open subsonic extensions
     *
     * @return List of supported extensions
     */
    public suspend fun getOpenSubsonicExtensions(): List<SubsonicExtension>

    /**
     * Changes the password of an existing user on the server, using the following parameters.
     * You can only change your own password unless you have admin privileges.
     *
     * @param username The name of the user which should change its password.
     * @param password The new password to set
     */
    public suspend fun changePassword(username: String, password: String)

    /**
     * Create a new user on the server.
     *
     * @param username The name of the new user
     * @param password The password of the new user
     * @param email The email address of the new user
     * @param ldapAuthenticated Whether to use LDAP authentication
     * @param roles The roles to assign to the user
     * @param folders The music folders to grant access to
     */
    public suspend fun createUser(
        username: String,
        password: String,
        email: String,
        ldapAuthenticated: Boolean = false,
        roles: List<Role> = emptyList(),
        folders: List<String> = emptyList()
    )

    /**
     * Update an existing user on the server.
     *
     * @param username The name of the user to update
     * @param password The new password
     * @param email The new email address
     * @param ldapAuthenticated Whether to use LDAP authentication
     * @param roles The roles to assign to the user
     * @param maxBitRate The maximum bit rate for streaming
     * @param folders The music folders to grant access to
     */
    public suspend fun updateUser(
        username: String,
        password: String,
        email: String? = null,
        ldapAuthenticated: Boolean? = null,
        roles: List<Role>? = null,
        maxBitRate: Int? = null,
        folders: List<String> = emptyList()
    )

    /**
     * Delete a user from the server.
     *
     * @param username The name of the user to delete
     */
    public suspend fun deleteUser(username: String)

    /**
     * Get details about a specific user.
     *
     * @param username The username to look up
     * @return User information
     */
    public suspend fun getUser(username: String): User

    /**
     * Get a list of all users on the server.
     *
     * @return List of users
     */
    public suspend fun getUsers(): List<User>

    /**
     * Get all configured music folders.
     *
     * Use [getDirectory] to get a listing of the directory.
     *
     * @return List of music folders
     */
    public suspend fun getDirectories(): List<MusicFolder>

    /**
     * Get an indexed structure of all artists.
     *
     * @param musicFolderId Only return artists in the specified music folder
     * @param ifModifiedSince Only return artists (and indexes) modified since this instant
     * @return Indexed artist list
     */
    public suspend fun getIndexes(
        musicFolderId: String? = null,
        ifModifiedSince: Instant? = null
    ): ArtistIndexes

    /**
     * Get a listing of all files in a music directory. Not to be confused with [getDirectories]
     * which gets a list of all folders available on the server.
     *
     * @param id The directory ID
     *
     * @see getDirectories
     */
    public suspend fun getDirectory(id: String): Directory

    /**
     * Get all available genres in the music library.
     *
     * @return List of genres
     */
    public suspend fun getGenres(): List<Genre>

    /**
     * Get all artists but organizes music according to ID3 tags.
     *
     * @param folder The music folder ID to filter by
     * @return Artists grouped by index
     */
    public suspend fun getArtists(folder: String? = null): Artists

    /**
     * Get details about a specific artist.
     *
     * @param id The artist ID
     * @return Artist details
     */
    public suspend fun getArtist(id: String): Artist

    /**
     * Get details about a specific album.
     *
     * @param id The album ID
     * @return Album details
     */
    public suspend fun getAlbum(id: String): Album

    /**
     * Get details about a specific song.
     *
     * @param id The song ID
     * @return Song details
     */
    public suspend fun getSong(id: String): Song

    /**
     * Get artist information from Last.fm, including biography and similar artists.
     *
     * @param id The artist ID
     * @param maxSimilar Maximum number of similar artists to return
     * @param includeNotPresent Whether to include artists not present in the library
     * @return Artist information
     */
    public suspend fun getArtistInfo(
        id: String,
        maxSimilar: Int = 20,
        includeNotPresent: Boolean = false
    ): ArtistInfo

    /**
     * Get artist information from Last.fm, including biography and similar artists.
     *
     * @param artist The artist
     * @param maxSimilar Maximum number of similar artists to return
     * @param includeNotPresent Whether to include artists not present in the library
     * @return Artist information
     */
    public suspend fun getArtistInfo(
        artist: Artist,
        maxSimilar: Int = 20,
        includeNotPresent: Boolean = false
    ): ArtistInfo

    /**
     * Get artist information using ID3 tags, including biography and similar artists.
     *
     * @param id The artist ID
     * @param maxSimilar Maximum number of similar artists to return
     * @param includeNotPresent Whether to include artists not present in the library
     */
    public suspend fun getArtistInfoID3(
        id: String,
        maxSimilar: Int = 20,
        includeNotPresent: Boolean = false
    ): ArtistInfo

    /**
     * Get album information from Last.fm, including notes and links.
     *
     * @param id The album ID
     */
    public suspend fun getAlbumInfo(id: String): AlbumInfo

    /**
     * Get album information using ID3 tags, including notes and links.
     *
     * @param id The album ID
     */
    public suspend fun getAlbumInfoID3(id: String): AlbumInfo

    /**
     * Get songs similar to a given song.
     *
     * @param id The song ID
     * @param count Maximum number of similar songs to return
     * @return List of similar songs
     */
    public suspend fun getSimilarSongs(id: String, count: Int = 50): List<Song>

    /**
     * Get songs similar to a given song using ID3 tags.
     *
     * @param id The song ID
     * @param count Maximum number of similar songs to return
     * @return List of similar songs
     */
    public suspend fun getSimilarSongsID3(id: String, count: Int = 50): List<Song>

    /**
     * Get top songs for an artist.
     *
     * @param artist The artist name
     * @param count Maximum number of songs to return
     * @return A list of top songs, or empty if no songs found
     */
    public suspend fun getTopSongs(artist: String, count: Int = 50): List<Song>

    /**
     * Get top songs for an artist.
     *
     * @param artist The artist
     * @param count Maximum number of songs to return
     * @return A list of top songs, or empty if no songs found
     */
    public suspend fun getTopSongs(artist: Artist, count: Int = 50): List<Song>

    /**
     * Get a list of albums matching the specified criteria.
     *
     * @param type The type of album list to return
     * @param size Maximum number of albums to return
     * @param offset The offset for pagination
     * @return List of albums
     */
    public suspend fun getAlbums(type: AlbumListType, size: Int = 10, offset: Int = 0): List<Album>

    /**
     * Get a list of albums matching the specified criteria using ID3 tags.
     *
     * @param type The type of album list to return
     * @param size Maximum number of albums to return
     * @param offset The offset for pagination
     * @return List of albums
     */
    public suspend fun getAlbumsID3(
        type: AlbumListType,
        size: Int = 10,
        offset: Int = 0
    ): List<Album>

    /**
     * Get random songs matching the specified criteria.
     *
     * @param size Maximum number of songs to return
     * @param genre The genre to filter by
     * @param fromYear The minimum year to filter by
     * @param toYear The maximum year to filter by
     * @return List of random songs
     */
    public suspend fun getRandomSongs(
        size: Int = 10,
        genre: String? = null,
        fromYear: Int? = null,
        toYear: Int? = null
    ): List<Song>

    /**
     * Get songs by genre
     *
     * @param genre The genre to filter by
     * @param count The max number of songs to get (max 500)
     * @param offset The offset, used for pagination
     * @param musicFolderId The music folder ID to filter by
     * @return List of songs
     */
    public suspend fun getSongs(
        genre: String,
        count: Int = 10,
        offset: Int = 0,
        musicFolderId: String? = null
    ): List<Song>

    /**
     * Get information about what is currently being played by all users.
     *
     * @return List of now playing entries
     */
    public suspend fun getNowPlaying(): List<NowPlayingEntry>

    /**
     * Get starred songs, albums, and artists.
     *
     * @param musicFolder The music folder to filter by
     * @return Starred items
     */
    public suspend fun getStarred(musicFolder: Directory? = null): Starred

    /**
     * Get starred songs, albums, and artists using ID3 tags.
     *
     * @param musicFolder The music folder to filter by
     * @return Starred items
     */
    public suspend fun getStarredID3(musicFolder: Directory? = null): Starred

    /**
     * Search for artists, albums, and songs.
     *
     * @param query The search query
     * @param artistCount Maximum number of artists to return
     * @param artistOffset The offset for artist pagination
     * @param albumCount Maximum number of albums to return
     * @param albumOffset The offset for album pagination
     * @param songCount Maximum number of songs to return
     * @param songOffset The offset for song pagination
     * @param musicFolderId The music folder ID to filter by
     * @return Search results
     */
    public suspend fun search(
        query: String,
        artistCount: Int = 20,
        artistOffset: Int = 0,
        albumCount: Int = 20,
        albumOffset: Int = 0,
        songCount: Int = 20,
        songOffset: Int = 0,
        musicFolderId: Int? = null
    ): SearchResult

    /**
     * Search for artists, albums, and songs using ID3 tags.
     *
     * @param query The search query
     * @param artistCount Maximum number of artists to return
     * @param artistOffset The offset for artist pagination
     * @param albumCount Maximum number of albums to return
     * @param albumOffset The offset for album pagination
     * @param songCount Maximum number of songs to return
     * @param songOffset The offset for song pagination
     * @param musicFolderId The music folder ID to filter by
     * @return Search results
     */
    public suspend fun searchID3(
        query: String,
        artistCount: Int = 20,
        artistOffset: Int = 0,
        albumCount: Int = 20,
        albumOffset: Int = 0,
        songCount: Int = 20,
        songOffset: Int = 0,
        musicFolderId: Int? = null
    ): SearchResult

    /**
     * Get all playlists.
     *
     * @return List of playlists
     */
    public suspend fun getPlaylists(): List<Playlist>

    /**
     * Get details about a specific playlist.
     *
     * @param id The playlist ID
     * @return Playlist details
     */
    public suspend fun getPlaylist(id: String): Playlist

    /**
     * Create a new playlist.
     *
     * @param name The playlist name
     * @param songIds The list of song IDs to add
     * @return The created playlist
     */
    public suspend fun createPlaylist(name: String, songIds: List<String>): Playlist

    /**
     * Create a new playlist from a list of songs.
     *
     * @param name The playlist name
     * @param songs The list of songs to add
     * @return The created playlist
     */
    public suspend fun createPlaylistFromSongs(name: String, songs: List<Song>): Playlist

    /**
     * Update an existing playlist.
     *
     * @param id The playlist ID
     * @param name The new playlist name
     * @param comment The new playlist comment
     * @param public Whether the playlist should be public
     * @param songIdsToAdd List of song IDs to add to the playlist
     * @param songIndicesToRemove List of song indices to remove from the playlist
     */
    public suspend fun updatePlaylist(
        id: String,
        name: String? = null,
        comment: String? = null,
        public: Boolean? = null,
        songIdsToAdd: List<String> = emptyList(),
        songIndicesToRemove: List<Int> = emptyList()
    )

    /**
     * Delete a playlist.
     *
     * @param id The playlist ID
     */
    public suspend fun deletePlaylist(id: String)

    /**
     * Get the URL for streaming a media file.
     *
     * @param id The media file ID
     * @param maxBitRate The maximum bit rate for streaming
     * @param format The preferred audio format
     * @param timeOffset If specified, the offset to start streaming at (in seconds) into the media
     * @param estimateContentLength If set to `true`, the Content-Length HTTP header will be set to an estimated value for transcoded or downsampled media
     * @return The stream URL
     */
    public fun getStreamUrl(
        id: String,
        maxBitRate: Int = 0,
        format: String? = null,
        timeOffset: Int? = null,
        estimateContentLength: Boolean = false
    ): String

    /**
     * Get the URL for streaming a transcoded media file.
     *
     * @param id The media file ID
     * @param mediaType The media type
     * @param offset Audio offset
     * @param transcodeParams Transcoding parameters (returned by getTranscodeDecision)
     * @return The stream URL
     */
    public fun getTranscodeStreamUrl(
        id: String,
        mediaType: MediaType,
        offset: Int,
        transcodeParams: String
    ): String

    /**
     * Download a media file.
     *
     * @param id The media file ID
     * @return The file contents
     */
    public suspend fun download(id: String): ByteReadChannel

    /**
     * Get an HLS playlist for streaming video or audio.
     *
     * @param id The video ID
     * @param bitRate The video bit rate to use
     * @param audioTrack The audio track to use
     * @return The HLS playlist
     */
    public suspend fun hls(id: String, bitRate: Int? = null, audioTrack: String? = null): ByteArray

    /**
     * Get captions/subtitles for a video.
     *
     * @param videoId The video ID
     * @param format The caption format
     * @return List of caption lines
     */
    public suspend fun getCaptions(videoId: String, format: String): List<String>

    /**
     * Get cover art for an album or song.
     *
     * @param id The cover art ID
     * @param size The desired image size
     * @return The cover art image data
     */
    public suspend fun getCoverArt(id: String, size: String? = null): ByteArray

    /**
     * Get the URL for cover art.
     *
     * @param id The cover art ID
     * @param size The desired image size
     * @param auth Whether to include authentication in the URL
     * @return The cover art URL
     */
    public fun getCoverArtUrl(id: String, size: String? = null, auth: Boolean = true): String

    /**
     * Get lyrics for a song by artist and title.
     *
     * @param artist The artist name
     * @param title The song title
     * @return Lyrics information
     */
    public suspend fun getLyrics(artist: String, title: String): Lyrics

    /**
     * Get structured lyrics for a song by ID.
     *
     * @param id The song ID
     * @return Structured lyrics with timing information
     */
    public suspend fun getLyrics(id: String): List<StructuredLyrics>

    /**
     * Get structured lyrics for a song.
     *
     * @param song The song
     * @return Structured lyrics with timing information
     */
    public suspend fun getLyrics(song: Song): List<StructuredLyrics>

    /**
     * Get the avatar image for a user.
     *
     * @param username The username
     * @return The avatar image data
     */
    public suspend fun getAvatar(username: String): ByteArray

    /**
     * Get the URL for a user's avatar.
     *
     * @param username The username
     * @param auth Whether to include authentication in the URL
     * @return The avatar URL
     */
    public fun getAvatarUrl(username: String, auth: Boolean = true): String

    /**
     * Star items by ID.
     *
     * @param ids The IDs of items to star
     */
    public suspend fun star(vararg ids: String)

    /**
     * Star items.
     *
     * @param items The items to star
     */
    public suspend fun star(vararg items: SubsonicResource)

    /**
     * Unstar items by ID.
     *
     * @param ids The IDs of items to unstar
     */
    public suspend fun unstar(vararg ids: String)

    /**
     * Unstar items.
     *
     * @param items The items to unstar
     */
    public suspend fun unstar(vararg items: SubsonicResource)

    /**
     * Set the rating for a media file.
     *
     * @param id The media file ID
     * @param rating The rating (1-5, or 0 to remove rating)
     */
    public suspend fun setRating(id: String, rating: Int)

    /**
     * Register a song play with Last.fm or similar services.
     *
     * @param id The song ID
     * @param time The time the song was played
     * @param submission Whether this is a submission or a now-playing notification
     */
    public suspend fun scrobble(
        id: String,
        time: Instant = Clock.System.now(),
        submission: Boolean = true
    )

    /**
     * Get all shares active user is allowed to manage.
     *
     * @return List of shares
     */
    public suspend fun getShares(): List<Share>

    /**
     * Create a public share for media files.
     *
     * @param entries The IDs of media files to share
     * @param description A description of the share
     * @param expiresAt The expiration time for the share
     * @return The created share
     */
    public suspend fun createShare(
        entries: List<String>,
        description: String? = null,
        expiresAt: Instant? = null
    ): Share

    /**
     * Update an existing share.
     *
     * @param id The share ID
     * @param description The new description
     * @param expiresAt The new expiration time
     */
    public suspend fun updateShare(
        id: String,
        description: String? = null,
        expiresAt: Instant? = null
    )

    /**
     * Delete a share.
     *
     * @param id The share ID
     */
    public suspend fun deleteShare(id: String)

    /**
     * Get all podcast channels.
     *
     * @param includeEpisodes Whether to include episode details
     * @return List of podcast channels
     */
    public suspend fun getPodcasts(includeEpisodes: Boolean = true): List<PodcastChannel>

    /**
     * Get the most recently published podcast episodes
     *
     * @param count The maximum number of episodes to return
     * @return A list of episodes or empty if none found
     */
    public suspend fun getNewestPodcasts(count: Int = 20): List<PodcastEpisode>

    /**
     * Get details about a specific podcast episode.
     *
     * @param id The episode ID
     */
    public suspend fun getPodcastEpisode(id: String)

    /**
     * Refresh all podcast channels and check for new episodes.
     */
    public suspend fun refreshPodcasts()

    /**
     * Create a new podcast channel
     *
     * @param url The podcast URL
     */
    public suspend fun createPodcastChannel(url: String)

    /**
     * Delete a podcast channel.
     *
     * @param id The channel ID
     */
    public suspend fun deletePodcastChannel(id: String)

    /**
     * Delete a podcast episode.
     *
     * @param id The episode ID
     */
    public suspend fun deletePodcastEpisode(id: String)

    /**
     * Download a podcast episode.
     *
     * @param id The episode ID
     */
    public suspend fun downloadPodcastEpisode(id: String)

    /**
     * Control the jukebox playback.
     *
     * @param action The action to perform
     * @param gain The audio gain (0.0-1.0)
     */
    public suspend fun jukeboxControl(action: JukeboxAction, gain: Float)

    /**
     * Get all internet radio stations.
     *
     * @return List of internet radio stations
     */
    public suspend fun getInternetRadioStations(): List<InternetRadioStation>

    /**
     * Create a new internet radio station.
     *
     * @param streamUrl The stream URL
     * @param name The station name
     * @param homepageUrl The station homepage URL
     */
    public suspend fun createInternetRadioStation(
        streamUrl: String,
        name: String,
        homepageUrl: String? = null
    )

    /**
     * Update an existing internet radio station.
     *
     * @param id The station ID
     * @param streamUrl The stream URL
     * @param name The station name
     * @param homepageUrl The station homepage URL
     */
    public suspend fun updateInternetRadioStation(
        id: String,
        streamUrl: String,
        name: String,
        homepageUrl: String? = null
    )

    /**
     * Delete an internet radio station.
     *
     * @param id The station ID
     */
    public suspend fun deleteInternetRadioStation(id: String)

    /**
     * Get all chat messages.
     *
     * @return List of chat messages
     */
    public suspend fun getChatMessages(): List<ChatMessage>

    /**
     * Add a message to the chat log
     *
     * @param message The message content
     */
    public suspend fun addChatMessage(message: String)

    /**
     * Get all bookmarks for the current user.
     *
     * @return List of bookmarks
     */
    public suspend fun getBookmarks(): List<Bookmark<SubsonicResource>>

    /**
     * Create or update a bookmark.
     *
     * @param id The ID of the media file to bookmark
     * @param position The position within the media file in milliseconds
     * @param comment An additional comment
     */
    public suspend fun createBookmark(id: String, position: Long, comment: String? = null)

    /**
     * Delete a bookmark.
     *
     * @param id The bookmark ID
     */
    public suspend fun deleteBookmark(id: String)

    /**
     * Get the current play queue for the user.
     *
     * @return Play queue information
     */
    public suspend fun getPlayQueue(): PlayQueue

    /**
     * Get the play queue by index.
     */
    public suspend fun getPlayQueueByIndex()

    /**
     * Save the play queue state.
     *
     * @param id The play queue ID
     * @param currentId The ID of the currently playing song
     * @param position The playback position in the current song
     */
    public suspend fun savePlayQueue(id: Long? = null, currentId: Long, position: Int = 0)

    /**
     * Save the play queue state.
     *
     * @param id The play queue ID
     * @param currentIndex The index of the currently playing song
     * @param position The playback position in milliseconds
     */
    public suspend fun savePlayQueue(
        id: String? = null,
        currentIndex: Int? = null,
        position: Long? = null
    )

    /**
     * Check the current status of an active scan.
     *
     * @return The scan status
     */
    public suspend fun getScanStatus(): ScanStatus

    /**
     * Start a scan of the media library.
     *
     * @return The scan status
     */
    public suspend fun startScan(): ScanStatus

    /**
     * Get transcoding decision information for a media file.
     *
     * @param id The media file ID
     * @param mediaType The media type
     * @return Transcoding decision details
     */
    public suspend fun getTranscodeDecision(
        id: String,
        mediaType: MediaType,
        clientInfo: ClientInfo
    ): TranscodeDecision

    /**
     * Get a transcoded media stream with custom parameters.
     *
     * @param id The media file ID
     * @param mediaType The media type
     * @param offset The time offset in seconds
     * @param params Custom transcoding parameters
     * @return The transcoded stream data
     */
    public suspend fun getTranscodeStream(
        id: String,
        mediaType: MediaType,
        offset: Int = 0,
        params: String
    ): ByteArray

    /**
     * Get a transcoded media stream
     *
     * @param id The ID of the media to be transcoded
     * @param mediaType The media type
     * @param offset The time offset in seconds
     * @return The transcoded stream data
     */
    public suspend fun getTranscodeStream(
        id: String,
        mediaType: MediaType,
        offset: Int = 0
    ): ByteReadChannel

    /**
     * Finds a path of songs connecting a start song to an end song, navigating through audio similarity space.
     *
     * @param start The song to start at
     * @param stop The song to end at
     * @param count The maximum number of songs to return
     * @return The path of matched songs
     */
    public suspend fun findSonicPath(start: Song, stop: Song, count: Int = 25): List<SonicMatch>

    /**
     * Finds a path of songs connecting a start song to an end song, navigating through audio similarity space.
     *
     * @param startId The id of the song to start at
     * @param stopId The id of the song to end at
     * @param count The maximum number of songs to return
     * @return The path of matched songs
     */
    public suspend fun findSonicPath(
        startId: String,
        stopId: String,
        count: Int = 25
    ): List<SonicMatch>

    /**
     * Returns tracks that are sonically similar to a given track, based on audio analysis. \
     * Results are ordered from most similar to least similar. Each result includes a normalized \
     * similarity score to the query track. Higher values indicate greater similarity, with 1.0 \
     * meaning it is the exact same song and 0.0 meaning the most different.
     *
     * @param song The song to find similar tracks to
     * @param count The maximum number of songs to return
     * @return The matched similar tracks
     */
    public suspend fun getSonicSimilarTracks(song: Song, count: Int = 10): List<SonicMatch>

    /**
     * Returns tracks that are sonically similar to a given track, based on audio analysis. \
     * Results are ordered from most similar to least similar. Each result includes a normalized \
     * similarity score to the query track. Higher values indicate greater similarity, with 1.0 \
     * meaning it is the exact same song and 0.0 meaning the most different.
     *
     * @param songId The id of the song to find similar tracks to
     * @param count The maximum number of songs to return
     * @return The matched similar tracks
     */
    public suspend fun getSonicSimilarTracks(songId: String, count: Int = 10): List<SonicMatch>

    /**
     * Reports playback timeline state for a song.
     *
     * @param mediaId The ID of the media being reported
     * @param mediaType Either song or podcast so the server knows what the mediaId is referring to
     * @param positionMs The playback position in milliseconds
     * @param state Playback state: starting, playing, paused, or stopped
     * @param playbackRate Playback speed multiplier
     * @param ignoreScrobble If true, server should only update now-playing display/state and should not trigger scrobble/playcount side effects
     */
    public suspend fun reportPlayback(
        mediaId: String,
        mediaType: MediaType,
        positionMs: Long,
        state: PlaybackState,
        playbackRate: Float = 1f,
        ignoreScrobble: Boolean? = null
    )
}