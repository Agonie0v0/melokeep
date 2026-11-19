package dev.zt64.subsonic.client.test

import dev.zt64.subsonic.client.SubsonicClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserTest {
    @Test
    fun testGetUser() = runTest {
        val user = testEndpoint(
            endpoint = "getUser",
            response = loadFixture("user/getUser")
        ) {
            getUser("123")
        }

        assertEquals("sindre", user.name)
        assertEquals("sindre@activeobjects.no", user.email)
    }

    @Test
    fun testDeleteUser() = runTest {
        testEndpoint("deleteUser") {
            deleteUser(username)
        }
    }

    @Test
    fun testUpdateUser() = runTest {
        testEndpoint("updateUser") {
            updateUser(username, password, "", false)
        }
    }

    @Test
    fun testGetUsers() = runTest {
        val users = testEndpoint(
            endpoint = "getUsers",
            response = loadFixture("user/getUsers")
        ) { getUsers() }

        assertEquals(1, users.size)
        assertEquals("sindre", users.first().name)
    }

    @Test
    fun testCreateUser() = runTest {
        testEndpoint("createUser") {
            createUser(username, password, "")
        }
    }

    @Test
    fun testChangePassword() = runTest {
        testEndpoint("changePassword") {
            changePassword(username, password)
        }
    }

    @Test
    fun testGetChatMessages() = runTest {
        val messages = testEndpoint(
            endpoint = "getChatMessages",
            response = loadFixture("user/getChatMessages")
        ) {
            getChatMessages()
        }

        assertEquals(1, messages.size)
        assertEquals("Hello World", messages.first().message)
    }

    @Test
    fun testAddChatMessage() = runTest {
        testEndpoint("addChatMessage") {
            addChatMessage("Test message")
        }
    }

    @Test
    fun testGetShares() = runTest {
        val shares = testEndpoint(
            endpoint = "getShares",
            response = loadFixture("user/getShares"),
            call = SubsonicClient::getShares
        )

        assertTrue(shares.isEmpty())
    }

    // @Test
    // fun testCreateShare() = runTest {
    //     testEndpoint("createShare") {
    //         createShare(listOf("Wa5fzmngg4VgscnxP1c05u"), "test")
    //     }
    // }

    // @Test
    // fun testUpdateShare() = runTest {
    //     val share = testEndpoint(
    //         endpoint = "createShare",
    //         response = ""
    //     ) {
    //         createShare(listOf("Wa5fzmngg4VgscnxP1c05u"), "test")
    //     }
    //
    //     testEndpoint("updateShare") {
    //         updateShare(share.id, expiresAt = Clock.System.now() + 5.days)
    //     }
    // }

    // @Test
    // fun testDeleteShare() = runTest {
    //     val share = testEndpoint("createShare") {
    //         createShare(listOf("Wa5fzmngg4VgscnxP1c05u"), "test")
    //     }
    //
    //     testEndpoint("deleteShare") {
    //         deleteShare(share.id)
    //     }
    // }

    @Test
    fun testStar() = runTest {
        testEndpoint("star") { star("abc") }
    }

    @Test
    fun testUnstar() = runTest {
        testEndpoint("unstar") { unstar("abc") }
    }

    // @Test
    // fun testGetBookmarks() = runTest {
    //     testEndpoint("getBookmarks", response = "") {
    //         getBookmarks()
    //     }
    // }

    @Test
    fun testBookmark() = runTest {
        testEndpoint("createBookmark") {
            createBookmark("abc", position = 0)
        }

        testEndpoint("deleteBookmark") {
            deleteBookmark("abc")
        }
    }
}