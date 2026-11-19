package dev.zt64.subsonic.api.model

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

/**
 * Base interface for media resources
 *
 * @property id Unique identifier
 * @property coverArtId ID of the cover art image
 * @property starredAt Timestamp when the resource was starred, if applicable
 * @property musicBrainzId MusicBrainz identifier
 * @property isExternal True if the resource is external e.g. OctoFiesta
 * @property sortName Name to be used for sorting
 */
@Serializable(ResourceSerializer::class)
public sealed interface SubsonicResource {
    public val id: String
    public val coverArtId: String?

    public val starredAt: Instant?
    public val musicBrainzId: String?
    public val isExternal: Boolean
    public val sortName: String?

    /** Whether this resource has been starred */
    public val isStarred: Boolean
        get() = starredAt != null
}

internal object ResourceSerializer : JsonContentPolymorphicSerializer<SubsonicResource>(
    baseClass = SubsonicResource::class
) {
    override fun selectDeserializer(
        element: JsonElement
    ): DeserializationStrategy<SubsonicResource> {
        val mediaType = element.jsonObject["mediaType"]?.jsonPrimitive?.content
            ?: throw SerializationException("Missing 'mediaType' field in json")

        return when (mediaType) {
            "song" -> Song.serializer()
            "artist" -> Artist.serializer()
            "album" -> Album.serializer()
            else -> throw SerializationException("Unknown media type: $mediaType")
        }
    }
}