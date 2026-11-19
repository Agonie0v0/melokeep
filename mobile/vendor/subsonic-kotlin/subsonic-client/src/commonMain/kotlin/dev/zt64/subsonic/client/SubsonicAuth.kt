package dev.zt64.subsonic.client

import org.kotlincrypto.hash.md.MD5

/**
 * Subsonic authentication method
 */
public sealed interface SubsonicAuth {
    /**
     * Do not send any authentication parameters.
     *
     * Intended for endpoints that must be accessible without authentication, such as
     * [getOpenSubsonicExtensions][dev.zt64.subsonic.api.SubsonicApi.getOpenSubsonicExtensions].
     */
    public data object None : SubsonicAuth

    /**
     * Authenticate using an API key
     *
     * @property apiKey The API key to authenticate with
     */
    public data class Key(public val apiKey: String) : SubsonicAuth {
        override fun toString(): String {
            return "Key(apiKey='*****')"
        }
    }

    /**
     * Authenticate using a secure token
     *
     * @property username The username to authenticate with
     * @property salt The randomly generated salt
     * @property token The MD5 hash of password + salt
     */
    public data class Token(
        public val username: String,
        public val salt: String,
        public val token: String
    ) : SubsonicAuth {
        public companion object {
            private const val SALT_LENGTH = 6
            private val CHARS = ('a'..'z') + ('0'..'9')

            /**
             * Create a token from username and password
             *
             * Token is computed as the MD5 of a randomly generated salt appended to the plain text password
             *
             * @param username The username to authenticate with
             * @param password The password in plain text (not stored)
             */
            public operator fun invoke(username: String, password: String): Token {
                val salt = CharArray(SALT_LENGTH) { CHARS.random() }.concatToString()
                val token = MD5().digest((password + salt).encodeToByteArray()).toHexString()
                return Token(username, salt, token)
            }
        }

        override fun toString(): String {
            return "Token(username='$username', salt='$salt', token='**********')"
        }
    }
}