package dev.zt64.subsonic.api.model

import kotlinx.serialization.Serializable

/**
 * Scan status
 *
 * @property scanning Whether a scan is active
 * @property count Number of items scanned
 */
@Serializable
public data class ScanStatus internal constructor(
    val scanning: Boolean,
    val count: Int = 0
)