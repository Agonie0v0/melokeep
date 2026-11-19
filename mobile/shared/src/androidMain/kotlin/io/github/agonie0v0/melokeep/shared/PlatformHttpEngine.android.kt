package io.github.agonie0v0.melokeep.shared

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun createPlatformHttpClientEngine(): HttpClientEngine = OkHttp.create()
