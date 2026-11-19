package dev.zt64.subsonic.client.test

import dev.zt64.subsonic.api.model.AlbumListType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AlbumTest {
    @Test
    fun testGetAlbum() = runTest {
        val album = testEndpoint(
            endpoint = "getAlbum",
            response = loadFixture("album/getAlbum")
        ) {
            getAlbum("200000021")
        }

        assertEquals("200000021", album.id)
        assertEquals("Forget and Remember", album.name)
        assertEquals(20, album.songCount)
        assertEquals(2, album.songs.size)
    }

    @Test
    fun testGetAlbumInfo() = runTest {
        val info = testEndpoint(
            endpoint = "getAlbumInfo",
            response = loadFixture("album/getAlbumInfo")
        ) {
            getAlbumInfo("4vXlEeZr862A0WmK8wJXhc")
        }

        assertEquals("6e1d48f7-717c-416e-af35-5d2454a13af2", info.musicBrainzId)
    }

    @Test
    fun testGetAlbums() = runTest {
        val albums = testEndpoint(
            endpoint = "getAlbumList",
            response = loadFixture("album/getAlbumList")
        ) {
            getAlbums(AlbumListType.Random)
        }

        assertEquals(2, albums.size)
        assertEquals("Forget and Remember", albums.first().name)
    }
}