package dev.zt64.subsonic.api.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class SubsonicResponseSerializer<T : Any>(
    private val tSerializer: KSerializer<T>
) : KSerializer<SubsonicResponse<T>> {
    override val descriptor = buildClassSerialDescriptor(
        "SubsonicResponse",
        tSerializer.descriptor
    )

    override fun deserialize(decoder: Decoder): SubsonicResponse<T> {
        require(decoder is JsonDecoder)

        val parent = decoder.decodeJsonElement().jsonObject
        val element = parent["subsonic-response"]!!.jsonObject
        val status = element.jsonObject["status"]!!.jsonPrimitive.content

        if (status != "ok") {
            return decoder.json.decodeFromJsonElement<SubsonicResponse.Error>(element)
        }

        if (tSerializer.descriptor.serialName == "kotlin.Nothing") {
            return decoder.json.decodeFromJsonElement<SubsonicResponse.Empty>(element)
        }

        // Presence of data can be inferred by T not being Nothing
        val dataEntry = element.entries.last()

        // List responses are either a bare array e.g. [...] like "getOpenSubsonicExtensions"
        // or wrapped in a single-key object e.g. {"song": [...]};
        // unwrap to a plain array so the element serializer receives what it expects
        val dataValue = if (tSerializer.descriptor.kind == StructureKind.LIST) {
            when (val v = dataEntry.value) {
                is JsonArray -> v
                is JsonObject -> v.entries.singleOrNull()?.value ?: JsonArray(emptyList())
                else -> JsonArray(emptyList())
            }
        } else {
            // This normalizes fields that are blank/empty by making them null
            dataEntry.value.takeIf { v ->
                val content = v.takeIf { it is JsonPrimitive }?.jsonPrimitive?.contentOrNull
                content == null || (content.isNotBlank() && content.isNotEmpty())
            } ?: JsonNull
        }

        val obj = element.jsonObject.toMutableMap().apply {
            remove(dataEntry.key)
            this["data"] = dataValue
        }

        return decoder.json.decodeFromJsonElement(
            SubsonicResponse.Success.serializer(tSerializer),
            JsonObject(obj)
        )
    }

    override fun serialize(encoder: Encoder, value: SubsonicResponse<T>) {
        throw SerializationException("Serialization is not supported")
    }
}

/**
 * Status of Subsonic API response
 */
@Serializable
public enum class SubsonicStatus {
    @SerialName("ok")
    OK,

    @SerialName("failed")
    FAILED
}

/**
 * Generic Subsonic response
 *
 * @param T Type of data
 * @property status Status of the response
 * @property version API version
 * @property type Server name
 * @property serverVersion Server version
 * @property openSubsonic Whether the server is OpenSubsonic
 */
@Serializable(SubsonicResponseSerializer::class)
public sealed interface SubsonicResponse<out T : Any> {
    public val status: SubsonicStatus
    public val version: String?
    public val type: String?
    public val serverVersion: String?
    public val openSubsonic: Boolean

    /**
     * Success
     *
     * @param T Type of data
     * @property data The data deserialized as [T]
     */
    @Serializable
    public data class Success<out T : Any> internal constructor(
        override val status: SubsonicStatus,
        override val version: String? = null,
        override val type: String? = null,
        override val serverVersion: String? = null,
        override val openSubsonic: Boolean = false,
        val data: T
    ) : SubsonicResponse<T>

    /**
     * An empty response with no data, used for endpoints that return no data.
     */
    @Serializable
    public data class Empty internal constructor(
        override val status: SubsonicStatus,
        override val version: String? = null,
        override val type: String? = null,
        override val serverVersion: String? = null,
        override val openSubsonic: Boolean = false
    ) : SubsonicResponse<Nothing>

    /**
     * Subsonic error
     *
     * @property error Error information
     */
    @Serializable
    public data class Error internal constructor(
        override val status: SubsonicStatus,
        override val version: String? = null,
        override val type: String? = null,
        override val serverVersion: String? = null,
        override val openSubsonic: Boolean = false,
        public val error: ErrorDetail
    ) : SubsonicResponse<Nothing> {
        @Serializable
        public data class ErrorDetail internal constructor(
            val code: SubsonicErrorCode,
            val helpUrl: String? = null,
            val message: String
        )
    }
}

@Serializable
public enum class SubsonicErrorCode(public val description: String) {
    @SerialName("0")
    GENERIC("A generic error."),

    @SerialName("10")
    REQUIRED_PARAMETER_MISSING("Required parameter is missing."),

    @SerialName("20")
    INCOMPATIBLE_CLIENT_VERSION(
        "Incompatible Subsonic REST protocol version. Client must upgrade."
    ),

    @SerialName("30")
    INCOMPATIBLE_SERVER_VERSION(
        "Incompatible Subsonic REST protocol version. Server must upgrade."
    ),

    @SerialName("40")
    WRONG_USERNAME_OR_PASSWORD("Wrong username or password."),

    @SerialName("41")
    TOKEN_AUTH_NOT_SUPPORTED_LDAP("Token authentication not supported for LDAP users."),

    @SerialName("42")
    AUTH_METHOD_NOT_SUPPORTED("Provided authentication mechanism not supported."),

    @SerialName("43")
    MULTIPLE_CONFLICTING_AUTH("Multiple conflicting authentication mechanisms provided."),

    @SerialName("44")
    INVALID_API_KEY("Invalid API key."),

    @SerialName("50")
    UNAUTHORIZED("User is not authorized for the given operation."),

    @SerialName("60")
    TRIAL_EXPIRED(
        "The trial period for the Subsonic server is over. Please upgrade to Subsonic Premium. " +
            "Visit subsonic.org for details."
    ),

    @SerialName("70")
    DATA_NOT_FOUND("The requested data was not found.")
}