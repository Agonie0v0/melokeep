package dev.zt64.subsonic.client.test

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
actual fun env(name: String): String? {
    return getenv(name)?.toKString()
}