package dev.zt64.subsonic.api.model

import kotlinx.serialization.Serializable

@Serializable
public data class SubsonicExtension internal constructor(val name: String, val versions: IntArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as SubsonicExtension

        if (name != other.name) return false
        if (!versions.contentEquals(other.versions)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + versions.contentHashCode()
        return result
    }
}