package dev.zt64.subsonic.client.test

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ArtistTest {
    @Test
    fun testGetArtists() = runTest {
        val index = testEndpoint(
            endpoint = "getArtists",
            response = loadFixture("artist/getArtists")
        ) {
            getArtists()
        }

        assertEquals(2, index.size)
    }

    @Test
    fun testGetArtist() = runTest {
        val artist = testEndpoint(
            endpoint = "getArtist",
            response = loadFixture("artist/getArtist")
        ) {
            getArtist("37ec820ca7193e17040c98f7da7c4b51")
        }

        assertEquals("2 Mello", artist.name)
        assertEquals(1, artist.albumCount)
    }

    @Test
    fun testGetArtistInfo() = runTest {
        val info = testEndpoint(
            endpoint = "getArtistInfo",
            response = loadFixture("artist/getArtistInfo")
        ) {
            getArtistInfo("1")
        }

        assertEquals("Empty biography", info.biography)

        val info2 = testEndpoint(
            endpoint = "getArtistInfo2",
            response = loadFixture("artist/getArtistInfo2")
        ) {
            getArtistInfoID3("1")
        }

        assertEquals("Empty biography", info2.biography)
    }
}