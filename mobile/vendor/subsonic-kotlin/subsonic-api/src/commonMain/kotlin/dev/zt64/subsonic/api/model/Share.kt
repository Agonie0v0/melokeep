package dev.zt64.subsonic.api.model

import dev.zt64.subsonic.api.model.serializer.LenientInstantSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Share information
 *
 * @property id The share ID
 * @property url The share URL
 * @property description A description
 * @property username User who created the share
 * @property createdAt Date share was created
 * @property expiresAt Date of expiration
 * @property lastVisited Date of last visit
 * @property visitCount Number of visits
 * @property items List of items in this share
 */
@Serializable
public data class Share internal constructor(
    val id: String,
    val url: String,
    val description: String? = null,
    val username: String,
    @SerialName("created")
    val createdAt: Instant,
    @Serializable(with = LenientInstantSerializer::class)
    @SerialName("expires")
    val expiresAt: Instant? = null,
    @Serializable(with = LenientInstantSerializer::class)
    val lastVisited: Instant? = null,
    val visitCount: Int = 0,
    @SerialName("entry")
    val items: List<SubsonicResource> = emptyList()
)