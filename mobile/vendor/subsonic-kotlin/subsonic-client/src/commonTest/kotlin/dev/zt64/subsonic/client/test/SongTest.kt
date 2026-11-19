package dev.zt64.subsonic.client.test

import dev.zt64.subsonic.client.SubsonicClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SongTest {
    @Test
    fun testGetSong() = runTest {
        val song = testEndpoint(
            endpoint = "getSong",
            response = loadFixture("song/getSong")
        ) { getSong("082f435a363c32c57d5edb6a678a28d4") }

        assertEquals("082f435a363c32c57d5edb6a678a28d4", song.id)
        assertEquals("\"polar expedition\"", song.title)
        assertEquals(178, song.duration?.inWholeSeconds?.toInt())
    }

    @Test
    fun testGetGenres() = runTest {
        val genres = testEndpoint(
            endpoint = "getGenres",
            response = loadFixture("song/getGenres"),
            call = SubsonicClient::getGenres
        )

        assertEquals(7, genres.size)
        assertEquals("Punk", genres.first().name)
    }

    @Test
    fun testGetTopSongs() = runTest {
        val songs = testEndpoint(
            endpoint = "getTopSongs",
            response = loadFixture("song/getTopSongs")
        ) {
            getTopSongs("abriction")
        }

        assertEquals(2, songs.size)
        assertEquals("BrownSmoke", songs.first().title)
    }

    @Test
    fun testGetRandomSongs() = runTest {
        val songs = testEndpoint(
            endpoint = "getRandomSongs",
            response = loadFixture("song/getRandomSongs"),
            call = SubsonicClient::getRandomSongs
        )

        assertEquals(2, songs.size)
    }

    @Test
    fun testGetLyrics() = runTest {
        val lyrics = testEndpoint(
            endpoint = "getLyricsBySongId",
            response = loadFixture("song/getLyricsBySongId")
        ) {
            getLyrics("YN6pPLdIqkkPBan84Wgb3Y")
        }

        assertEquals(1, lyrics.size)
        assertEquals("Metallica", lyrics.first().displayArtist)
        assertEquals("Blitzkrieg", lyrics.first().displayTitle)
        assertEquals(2, lyrics.first().lines.size)
    }

    // @Test
    // fun testDownloadSong() = runTest {
    //     testEndpoint("download") {
    //         download("")
    //     }
    // }

    // @Test
    // fun testGetCoverArt() = runTest {
    //     client.getCoverArt("")
    // }

    // @Test
    // fun testHls() = runTest {
    //     println(client.hls("abc"))
    // }

    // @Test
    // fun testTranscode() = runTest {
    //     testEndpoint(
    //         endpoint = "getTranscodeDecision",
    //         response = fixture("song/getTranscodeDecision")
    //     ) {
    //         getTranscodeDecision("abc", MediaType.SONG)
    //     }
    //
    //     testEndpoint(
    //         endpoint = "getTranscodeStream",
    //         response = "todo"
    //     ) { getTranscodeStream("abc", MediaType.SONG, 0, "abc") }
    // }
}