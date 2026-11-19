package dev.zt64.subsonic.client.test

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class SearchTest {
    @Test
    fun testSearch() = runTest {
        val result = testEndpoint(
            endpoint = "search2",
            response = loadFixture("search/search2")
        ) {
            search("duvet")
        }

        assertTrue(result.songs.isNotEmpty())
        assertTrue(result.albums.isNotEmpty())
        assertTrue(result.artists.isNotEmpty())
    }
}