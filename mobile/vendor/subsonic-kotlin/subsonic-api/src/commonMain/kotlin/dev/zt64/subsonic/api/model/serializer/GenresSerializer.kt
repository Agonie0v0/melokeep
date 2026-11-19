package dev.zt64.subsonic.api.model.serializer

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*

// Serializer for genres field
// Transforms [{ name: "Hip-Hip" }] to ["Hip-Hop]
// Future Subsonic API might add more fields to genre, but this will do for now
internal class GenresSerializer : JsonTransformingSerializer<List<String>>(
    tSerializer = ListSerializer(String.serializer())
) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        return JsonArray(element.jsonArray.map { it.jsonObject["name"]!! })
    }
}