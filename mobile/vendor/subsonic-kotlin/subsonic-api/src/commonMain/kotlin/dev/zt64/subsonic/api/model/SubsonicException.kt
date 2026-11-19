package dev.zt64.subsonic.api.model

/**
 * Subsonic exception
 *
 * @property code The error code
 */
public class SubsonicException(
    message: String? = null,
    public val code: SubsonicErrorCode = SubsonicErrorCode.GENERIC
) : Exception("${code.name}: ${message ?: code.description}")