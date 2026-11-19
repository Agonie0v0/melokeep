package dev.zt64.subsonic.api.model.serializer

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Subsonic duration serializer
 *
 * The Subsonic API represents durations using seconds. This serializer will serialize a duration to
 * whole seconds. And will deserialize integer seconds to a [Duration]
 */
internal class SubsonicDurationSerializer : KSerializer<Duration> {
    override val descriptor = PrimitiveSerialDescriptor(
        "kotlin.time.DurationSeconds",
        PrimitiveKind.INT
    )

    override fun serialize(encoder: Encoder, value: Duration) {
        encoder.encodeInt(value.inWholeSeconds.toInt())
    }

    override fun deserialize(decoder: Decoder): Duration {
        return decoder.decodeInt().seconds
    }
}