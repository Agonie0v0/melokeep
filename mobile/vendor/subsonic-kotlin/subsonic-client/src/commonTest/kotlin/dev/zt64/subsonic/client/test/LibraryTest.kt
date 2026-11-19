package dev.zt64.subsonic.client.test

import dev.zt64.subsonic.client.SubsonicClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class LibraryTest {
    @Test
    fun testGetMusicFolders() = runTest {
        val folders = testEndpoint(
            endpoint = "getMusicFolders",
            response = loadFixture("library/getMusicFolders")
        ) {
            getDirectories()
        }

        assertEquals(2, folders.size)
    }

    @Test
    fun testGetIndexes() = runTest {
        val indexes = testEndpoint(
            endpoint = "getIndexes",
            response = loadFixture("library/getIndexes")
        ) {
            getIndexes()
        }

        assertEquals(1, indexes.index.size)
    }

    @Test
    fun testGetIndexesIfModifiedSince() = runTest {
        val since = Instant.fromEpochMilliseconds(1678943707000)

        testEndpoint(
            endpoint = "getIndexes",
            response = loadFixture("library/getIndexes"),
            expectedParams = mapOf(
                "musicFolderId" to "1",
                "ifModifiedSince" to since.toEpochMilliseconds().toString()
            )
        ) {
            getIndexes(musicFolderId = "1", ifModifiedSince = since)
        }
    }

    @Test
    fun testStartScan() = runTest {
        val status = testEndpoint(
            endpoint = "startScan",
            response = loadFixture("library/scanStatus"),
            call = SubsonicClient::startScan
        )

        assertEquals(true, status.scanning)
    }

    @Test
    fun testGetScanStatus() = runTest {
        val status = testEndpoint(
            endpoint = "getScanStatus",
            response = loadFixture("library/scanStatus"),
            call = SubsonicClient::getScanStatus
        )

        assertEquals(1, status.count)
    }
}