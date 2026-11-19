package dev.zt64.subsonic.client.test

import dev.zt64.subsonic.client.SubsonicAuth
import dev.zt64.subsonic.client.SubsonicClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val apiUrl = env("SUBSONIC_API_URL")
val username = env("SUBSONIC_USERNAME") ?: "abc"
val password = env("SUBSONIC_PASSWORD") ?: "xyz"

expect fun env(name: String): String?

/**
 * Builds a client for a single [testEndpoint] call.
 *
 * When `SUBSONIC_API_URL` is set, requests are sent to a real server. Otherwise, a fresh [MockEngine]
 * is created per call
 */
@OptIn(ExperimentalUuidApi::class)
private fun testClient(
    endpoint: String,
    response: String?,
    expectedParams: Map<String, String?>
): SubsonicClient {
    if (apiUrl != null) {
        return SubsonicClient(baseUrl = apiUrl, auth = SubsonicAuth.Token(username, password))
    }

    val apiKey = Uuid.generateV4().toHexString()

    return SubsonicClient(
        engine = MockEngine { req ->
            val params = req.url.parameters

            if ("apiKey" in params) {
                assertEquals(apiKey, params["apiKey"])
            } else {
                assertEquals(username, params["u"])
            }

            assertEquals("1.16.1", params["v"])
            assertEquals("subsonic-kotlin", params["c"])
            assertEquals("json", params["f"])

            val requestedEndpoint = req.url.segments.last()
            assertEquals("$endpoint.view", requestedEndpoint, "Unexpected endpoint requested")

            expectedParams.forEach { (key, value) ->
                assertEquals(value, params[key], "Unexpected value for query param '$key'")
            }

            val body = if (response == null) {
                """
                {
                    "subsonic-response": {
                        "status": "ok",
                        "version": "1.16.1",
                        "type": "AwesomeServerName",
                        "serverVersion": "0.1.3 (tag)",
                        "openSubsonic": true
                    }
                }
                """.trimIndent()
            } else {
                """
                {
                    "subsonic-response": {
                        "status": "ok",
                        "version": "1.16.1",
                        "type": "AwesomeServerName",
                        "serverVersion": "0.1.3 (tag)",
                        "openSubsonic": true,
                        $response
                    }
                }
                """.trimIndent()
            }

            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString()
                )
            )
        },
        baseUrl = "subsonic.somewhere.xyz",
        auth = SubsonicAuth.Token(username, password)
    )
}

/**
 * Stubs [endpoint] to return [response] and invokes [call] on a client. Fails if
 * endpoint, or if any [expectedParams] don't match the outgoing request's query parameters.
 */
suspend fun <T> testEndpoint(
    endpoint: String,
    response: String? = null,
    expectedParams: Map<String, String?> = emptyMap(),
    call: suspend SubsonicClient.() -> T
): T {
    val client = testClient(endpoint, response, expectedParams)
    return client.call()
}