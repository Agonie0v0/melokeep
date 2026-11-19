package io.github.agonie0v0.melokeep.shared

import io.ktor.client.engine.HttpClientEngine

internal expect fun createPlatformHttpClientEngine(): HttpClientEngine
