package dev.zt64.subsonic.client.test

import dev.zt64.subsonic.client.SubsonicClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SystemTest {
    @Test
    fun testGetOpenSubsonicExtensions() = runTest {
        val extensions = testEndpoint(
            endpoint = "getOpenSubsonicExtensions",
            response = loadFixture("system/getOpenSubsonicExtensions"),
            call = SubsonicClient::getOpenSubsonicExtensions
        )

        assertEquals(2, extensions.size)
    }

    @Test
    fun testTokenInfo() = runTest {
        val info = testEndpoint(
            endpoint = "tokenInfo",
            response = loadFixture("system/tokenInfo", "username" to username),
            call = SubsonicClient::tokenInfo
        )

        assertEquals(username, info.username)
    }

    @Test
    fun testGetLicense() = runTest {
        val license = testEndpoint(
            endpoint = "getLicense",
            response = loadFixture("system/getLicense"),
            call = SubsonicClient::getLicense
        )

        assertTrue(license.valid)
    }
}