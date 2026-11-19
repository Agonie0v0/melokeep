package io.github.agonie0v0.melokeep.shared

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

internal actual fun createPlatformHttpClientEngine(): HttpClientEngine = Darwin.create()
