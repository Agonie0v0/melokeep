package dev.zt64.subsonic.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Internet radio station
 *
 * @property id
 * @property name
 * @property streamUrl
 * @property homepageUrl
 */
@Serializable
public data class InternetRadioStation internal constructor(
    val id: String,
    val name: String,
    val streamUrl: String,
    val homepageUrl: String?,
    @SerialName("coverArt")
    val coverArtId: String?
)