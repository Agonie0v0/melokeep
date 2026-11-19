package dev.zt64.subsonic.api.model.serializer

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Instant

/**
 * Lenient instant serializer
 *
 * Some servers (e.g. LMS) send an empty string instead of omitting a field (in this case, an instant field),
 * which unfortunately is allowed by the OpenSubsonic spec. Blank or malformed values are decoded as null.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object LenientInstantSerializer : KSerializer<Instant?> {
    override val descriptor = PrimitiveSerialDescriptor(
        "LenientInstant",
        PrimitiveKind.STRING
    ).nullable

    override fun deserialize(decoder: Decoder): Instant? {
        return try {
            Instant.parse(decoder.decodeString())
        } catch (_: Exception) {
            null
        }
    }

    override fun serialize(encoder: Encoder, value: Instant?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeString(value.toString())
        }
    }
}