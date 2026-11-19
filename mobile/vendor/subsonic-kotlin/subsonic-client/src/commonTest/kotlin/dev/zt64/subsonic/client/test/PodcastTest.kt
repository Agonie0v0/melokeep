package dev.zt64.subsonic.client.test

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class PodcastTest {
    // @Test
    // fun testGetPodcasts() = runTest {
    //     testEndpoint(
    //         endpoint = "getPodcasts",
    //         response = fixture("podcast/getPodcasts")
    //     ) {
    //         getPodcasts()
    //     }
    // }

    @Test
    fun getNewestPodcasts() = runTest {
        testEndpoint(
            endpoint = "getNewestPodcasts",
            response = loadFixture("podcast/getNewestPodcasts")
        ) { getNewestPodcasts() }
    }

    @Test
    fun testRefreshPodcasts() = runTest {
        testEndpoint("refreshPodcasts") {
            refreshPodcasts()
        }
    }

    @Test
    fun testDeletePodcast() = runTest {
        testEndpoint("deletePodcastChannel") {
            deletePodcastChannel("abc")
        }

        testEndpoint("deletePodcastEpisode") {
            deletePodcastEpisode("abc")
        }
    }

    // @Test
    // fun testDownloadPodcastEpisode() = runTest {
    //     testEndpoint("downloadPodcastEpisode") {
    //         downloadPodcastEpisode("123")
    //     }
    // }
}