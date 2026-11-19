package dev.zt64.subsonic.client.test

import dev.zt64.subsonic.api.model.SubsonicException
import dev.zt64.subsonic.client.SubsonicAuth
import dev.zt64.subsonic.client.SubsonicClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClientTest {
    private val successResponse = """
        {
          "subsonic-response": {
            "status": "ok",
            "version": "1.16.1",
            "openSubsonic": true
          }
        }
    """.trimIndent()

    @Test
    fun testUrlSubpath() = runTest {
        var requestedPath: String? = null

        val client = SubsonicClient(
            baseUrl = "https://example.com/navidrome",
            auth = SubsonicAuth.Key("secret"),
            engine = MockEngine { req ->
                requestedPath = req.url.encodedPath
                respond(
                    content = successResponse,
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        name = HttpHeaders.ContentType,
                        value = ContentType.Application.Json.toString()
                    )
                )
            }
        )

        client.use {
            it.ping()
        }

        assertEquals("/navidrome/rest/ping.view", requestedPath)
    }

    @Test
    fun testUrlSubpathTrailingSlash() = runTest {
        var requestedPath: String? = null

        val client = SubsonicClient(
            baseUrl = "https://example.com/navidrome/",
            auth = SubsonicAuth.Key("secret"),
            engine = MockEngine { req ->
                requestedPath = req.url.encodedPath
                respond(
                    content = successResponse,
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        name = HttpHeaders.ContentType,
                        value = ContentType.Application.Json.toString()
                    )
                )
            }
        )

        client.use {
            it.ping()
        }

        assertEquals("/navidrome/rest/ping.view", requestedPath)
    }

    @Test
    fun testNoneAuthSendsNoAuthParameters() = runTest {
        var requestedUrl: Url? = null

        val client = SubsonicClient(
            baseUrl = "https://example.com",
            auth = SubsonicAuth.None,
            engine = MockEngine { req ->
                requestedUrl = req.url
                respond(
                    content = successResponse,
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        name = HttpHeaders.ContentType,
                        value = ContentType.Application.Json.toString()
                    )
                )
            }
        )

        client.use {
            it.ping()
        }

        val parameters = requestedUrl!!.parameters
        assertEquals(null, parameters["u"])
        assertEquals(null, parameters["t"])
        assertEquals(null, parameters["s"])
        assertEquals(null, parameters["apiKey"])
    }

    @Test
    fun testNonJsonResponseThrowsSubsonicException() = runTest {
        val client = SubsonicClient(
            baseUrl = "https://example.com",
            auth = SubsonicAuth.Key("secret"),
            engine = MockEngine {
                respond(
                    content = "<html><body>Unauthorized</body></html>",
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(
                        name = HttpHeaders.ContentType,
                        value = ContentType.Text.Html.toString()
                    )
                )
            }
        )

        client.use {
            assertFailsWith<SubsonicException> { it.ping() }
        }
    }
}