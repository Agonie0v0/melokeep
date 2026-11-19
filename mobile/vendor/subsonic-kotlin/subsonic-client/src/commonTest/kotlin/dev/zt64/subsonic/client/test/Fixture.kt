package dev.zt64.subsonic.client.test

import com.goncalossilva.resources.Resource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private val json = Json { ignoreUnknownKeys = true }

/**
 * Loads `src/commonTest/resources/fixtures/<path>.json` and returns its top-level fields
 *
 * [replacements] are applied to the raw text before parsing, substituting any `{{key}}`
 * placeholder with its value (useful for fixtures that depend on runtime values, like a username).
 */
fun loadFixture(path: String, vararg replacements: Pair<String, String>): String {
    var text = Resource("fixtures/$path.json").readText()

    replacements.forEach { (key, value) ->
        text = text.replace("{{$key}}", value)
    }

    val fields = json.parseToJsonElement(text).jsonObject

    return fields.entries.joinToString(",\n") { (key, value) -> "\"$key\": $value" }
}