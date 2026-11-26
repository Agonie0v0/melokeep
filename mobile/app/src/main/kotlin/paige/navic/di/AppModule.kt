/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.di

import com.russhwolf.settings.Settings
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.module
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.ui.navigation.PersistentViewModelStoreOwner
import paige.navic.util.network.buildKtorEngine
import paige.navic.util.network.createOkHttpClient
import paige.navic.util.network.getProxyUrlOrNull

import io.github.agonie0v0.melokeep.shared.MeloAiController
import io.github.agonie0v0.melokeep.shared.MeloDailyRecommendController
import io.github.agonie0v0.melokeep.shared.MeloKeepApiClient
import io.github.agonie0v0.melokeep.shared.MeloLocalLibraryController
import io.github.agonie0v0.melokeep.shared.MeloSearchController
import io.github.agonie0v0.melokeep.shared.MeloSettingsController
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import io.github.agonie0v0.melokeep.shared.MeloStudioController

val appModule = module {
	single { Settings() }
	single { MeloSettingsStore(get()) }
	single {
		val preferenceManager: PreferenceManager = get()
		MeloKeepApiClient(
			settingsStore = get(),
			httpClientEngineFactory = {
				buildKtorEngine(
					createOkHttpClient(
						proxyUrl = preferenceManager.proxyUrl.getProxyUrlOrNull(),
						trustAllCerts = preferenceManager.dangerousSslNoopEnabled,
						dnsResolver = null,
						customHeaders = preferenceManager.customHeadersMap(),
						connectTimeoutSeconds = 15L,
						readTimeoutSeconds = 0L,
					)
				)
			}
		)
	}
	single(named("meloScope")) {
		kotlinx.coroutines.CoroutineScope(
			kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
		)
	}
	single { MeloSearchController(get(), get(named("meloScope"))) }
	single { MeloStudioController(get(), get(named("meloScope"))) }
	single { MeloAiController(get(), get(named("meloScope"))) }
	single { MeloSettingsController(get(), get(named("meloScope"))) }
	single { MeloLocalLibraryController(get(), get(named("meloScope"))) }
	single { MeloDailyRecommendController(get(), get(named("meloScope"))) }
	singleOf(::CoilSingleton)
	single { get<CoilSingleton>().coilImageLoader }
	single(named("static")) { get<CoilSingleton>().staticCoilImageLoader }
	singleOf(::PersistentViewModelStoreOwner)
}
