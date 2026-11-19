package dev.zt64.subsonic.api.model

import kotlinx.serialization.Serializable

/**
 * Token information
 *
 * @property username The username associated with the token
 */
@Serializable
public data class TokenInfo internal constructor(val username: String)