package dev.zt64.subsonic.client

import dev.zt64.subsonic.api.SubsonicApi
import dev.zt64.subsonic.api.SubsonicApiImpl
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

private const val USER_AGENT = "subsonic-kotlin (github.com/zt64/subsonic-kotlin)"
private const val API_VERSION = "1.16.1"
private const val CLIENT_NAME = "subsonic-kotlin"

/**
 * Subsonic API client
 *
 * Based off Open Subsonic API
 */
public class SubsonicClient(
    private val httpClient: HttpClient,
    private val json: Json,
    private val baseUrl: String,
    private val params: Map<String, String>
) : SubsonicApi by SubsonicApiImpl(httpClient, json, baseUrl, params), AutoCloseable {
    public companion object {
        /**
         * Create a SubsonicClient
         */
        public operator fun invoke(
            baseUrl: String,
            auth: SubsonicAuth,
            client: String = CLIENT_NAME,
            userAgent: String = USER_AGENT,
            engine: HttpClientEngine? = null,
            clientConfig: HttpClientConfig<*>.() -> Unit = {}
        ): SubsonicClient {
            val normalizedBaseUrl = baseUrl.trimEnd('/')

            val params = buildMap {
                put("f", "json")
                put("v", API_VERSION)
                put("c", client)

                when (auth) {
                    SubsonicAuth.None -> Unit

                    is SubsonicAuth.Key -> {
                        put("apiKey", auth.apiKey)
                    }

                    is SubsonicAuth.Token -> {
                        put("u", auth.username)
                        put("t", auth.token)
                        put("s", auth.salt)
                    }
                }
            }

            val json = Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                encodeDefaults = true
            }

            val httpClientConfig: HttpClientConfig<*>.() -> Unit = {
                install(ContentNegotiation) { json(json) }
                install(UserAgent) { agent = userAgent }
                install(HttpTimeout)

                defaultRequest {
                    url {
                        takeFrom("$normalizedBaseUrl/rest/")
                        params.forEach { (k, v) -> parameters[k] = v }
                    }
                }

                clientConfig()
            }

            val httpClient = if (engine != null) {
                HttpClient(engine, httpClientConfig)
            } else {
                HttpClient(httpClientConfig)
            }

            return SubsonicClient(httpClient, json, normalizedBaseUrl, params)
        }
    }

    override fun close() {
        httpClient.close()
    }
}