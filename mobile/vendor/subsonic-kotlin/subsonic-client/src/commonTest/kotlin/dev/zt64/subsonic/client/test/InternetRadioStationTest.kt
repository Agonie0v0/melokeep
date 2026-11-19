package dev.zt64.subsonic.client.test

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class InternetRadioStationTest {
    @Test
    fun testCreateInternetRadioStation() = runTest {
        testEndpoint("createInternetRadioStation") {
            createInternetRadioStation(
                streamUrl = "",
                name = "",
                homepageUrl = ""
            )
        }
    }

    @Test
    fun testDeleteInternetRadioStation() = runTest {
        testEndpoint("deleteInternetRadioStation") {
            deleteInternetRadioStation("abc")
        }
    }

    @Test
    fun testGetInternetRadioStations() = runTest {
        val stations = testEndpoint(
            endpoint = "getInternetRadioStations",
            response = loadFixture("radio/getInternetRadioStations")
        ) {
            getInternetRadioStations()
        }

        assertEquals(1, stations.size)
        assertEquals("HBR1.com - Dream Factory", stations.first().name)
    }
}