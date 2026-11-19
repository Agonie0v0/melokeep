package dev.zt64.subsonic.api.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

internal object UserSerializer : JsonTransformingSerializer<User>(User.generatedSerializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val user = element.jsonObject

        val roles = listOf(
            "admin" to "adminRole",
            "guest" to "guestRole",
            "settings" to "settingsRole",
            "share" to "shareRole",
            "upload" to "uploadRole",
            "cover_art" to "coverArtRole",
            "stream" to "streamRole",
            "podcast" to "podcastRole",
            "jukebox" to "jukeboxRole",
            "playlist" to "playlistRole",
            "download" to "downloadRole",
            "videoconversion" to "videoConversionRole",
            "upload" to "uploadRole"
        ).mapNotNull { (roleName, key) ->
            user[key]?.jsonPrimitive?.booleanOrNull
                ?.takeIf { it }
                ?.let { JsonPrimitive(roleName) }
        }

        return buildJsonObject {
            put("folder", user["folder"]!!)
            put("username", user["username"]!!)
            user["email"]?.let { put("email", it) }
            put("scrobblingEnabled", user["scrobblingEnabled"]!!)
            put("roles", JsonArray(roles))
        }
    }
}

/**
 * User information
 *
 * @property name The username
 * @property email The email
 * @property scrobblingEnabled Whether this user can scrobble
 * @property folder The folders this user has access to
 * @property roles The roles this user has
 */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(UserSerializer::class)
public data class User internal constructor(
    @SerialName("username")
    val name: String,
    val email: String?,
    val scrobblingEnabled: Boolean,
    val folder: List<Int> = emptyList(),
    val roles: List<Role> = emptyList()
)

@Serializable
public enum class Role {
    @SerialName("guest")
    GUEST,

    @SerialName("admin")
    ADMIN,

    @SerialName("settings")
    SETTINGS,

    @SerialName("download")
    DOWNLOAD,

    @SerialName("upload")
    UPLOAD,

    @SerialName("playlist")
    PLAYLIST,

    @SerialName("cover_art")
    COVER_ART,

    @SerialName("comment")
    COMMENT,

    @SerialName("podcast")
    PODCAST,

    @SerialName("stream")
    STREAM,

    @SerialName("jukebox")
    JUKEBOX,

    @SerialName("share")
    SHARE,

    @SerialName("videoconversion")
    VIDEO_CONVERSION
}