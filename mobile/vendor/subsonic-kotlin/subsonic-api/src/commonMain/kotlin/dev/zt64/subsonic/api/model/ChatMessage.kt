package dev.zt64.subsonic.api.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Instant

internal object EpochSerializer : KSerializer<Instant> {
    override val descriptor =
        PrimitiveSerialDescriptor("kotlinx.datetime.Instant", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Instant {
        return Instant.fromEpochMilliseconds(decoder.decodeLong())
    }

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeLong(value.toEpochMilliseconds())
    }
}

/**
 * A chat message
 *
 * @property username The user who sent the message
 * @property time Time message was sent
 * @property message The message content
 */
@Serializable
public data class ChatMessage internal constructor(
    val username: String,
    @Serializable(EpochSerializer::class)
    val time: Instant,
    val message: String
)