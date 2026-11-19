package dev.zt64.subsonic.api.model

import kotlinx.serialization.Serializable

/**
 * A date for a media item that may be just a year, or year-month, or full date.
 *
 * @property year The year
 * @property month The month (1-12)
 * @property day The day (1-31)
 */
@Serializable
public data class ItemDate internal constructor(
    public val year: Int?,
    public val month: Int?,
    public val day: Int?
)