package dev.zt64.subsonic.api.model

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

class SubsonicSerializationTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun testResponseSuccessList() {
        val payload = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "openSubsonic": true,
                "numbers": {
                  "number": [1, 2, 3]
                }
              }
            }
        """.trimIndent()

        val response = json.decodeFromString(
            SubsonicResponse.serializer(ListSerializer(Int.serializer())),
            payload
        )

        val success = assertIs<SubsonicResponse.Success<List<Int>>>(response)
        assertEquals(listOf(1, 2, 3), success.data)
    }

    @Test
    fun testResponseError() {
        val payload = """
            {
              "subsonic-response": {
                "status": "failed",
                "error": {
                  "code": "40",
                  "message": "Wrong username or password."
                }
              }
            }
        """.trimIndent()

        val response = json.decodeFromString(
            SubsonicResponse.serializer(String.serializer()),
            payload
        )

        val error = assertIs<SubsonicResponse.Error>(response)
        assertEquals(SubsonicErrorCode.WRONG_USERNAME_OR_PASSWORD, error.error.code)
    }

    @Test
    fun testBlankInstantInAlbumIsNull() {
        val payload = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "albumList": {
                  "album": [
                    {
                      "id": "1",
                      "created": "2026-01-25T17:24:40.000Z",
                      "played": "",
                      "starred": ""
                    },
                    {
                      "id": "2",
                      "created": "2026-01-25T17:24:40.000Z",
                      "played": "2026-08-01T17:35:28.000Z"
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val response = json.decodeFromString(
            SubsonicResponse.serializer(ListSerializer(Album.serializer())),
            payload
        )

        val success = assertIs<SubsonicResponse.Success<List<Album>>>(response)
        assertNull(success.data[0].lastPlayedAt)
        assertNull(success.data[0].starredAt)
        assertEquals(
            Instant.parse("2026-08-01T17:35:28.000Z"),
            success.data[1].lastPlayedAt
        )
    }

    @Test
    fun testOptionalFieldsMayBeOmitted() {
        val artist = json.decodeFromString(
            Artist.serializer(),
            """{"id":"1","name":"Childish Gambino"}"""
        )
        val song = json.decodeFromString(
            Song.serializer(),
            """{"id":"1","title":"Redbone","explicitStatus":"","groupings":[],"works":[],"movements":[]}"""
        )
        val playlist = json.decodeFromString(
            Playlist.serializer(),
            """
                {
                  "id": "1",
                  "name": "Favorites",
                  "created": "2026-01-25T17:24:40.000Z",
                  "changed": "2026-01-25T17:24:40.000Z",
                  "duration": 0
                }
            """.trimIndent()
        )

        assertNull(artist.starredAt)
        assertNull(song.starredAt)
        assertNull(playlist.validUntil)
    }
}