package dev.zt64.subsonic.client.test

import dev.zt64.subsonic.client.SubsonicClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaylistTest {
    @Test
    fun testGetPlaylists() = runTest {
        val playlists = testEndpoint(
            endpoint = "getPlaylists",
            response = loadFixture("playlist/getPlaylists"),
            call = SubsonicClient::getPlaylists
        )

        assertEquals(2, playlists.size)
    }

    @Test
    fun testGetPlaylist() = runTest {
        val playlist = testEndpoint(
            endpoint = "getPlaylist",
            response = loadFixture("playlist/getPlaylist")
        ) {
            getPlaylist("300000060")
        }

        assertEquals("800000075", playlist.id)
        assertEquals(1, playlist.songCount)
    }

    @Test
    fun testCreatePlaylist() = runTest {
        val playlist = testEndpoint(
            endpoint = "createPlaylist",
            response = loadFixture("playlist/createPlaylist")
        ) {
            createPlaylist("Test Playlist", emptyList())
        }

        assertEquals("testcreate", playlist.name)
    }

    @Test
    fun testDeletePlaylist() = runTest {
        testEndpoint("deletePlaylist") {
            deletePlaylist("CZDXgohio9aEBl6RwmT6g8")
        }
    }
}