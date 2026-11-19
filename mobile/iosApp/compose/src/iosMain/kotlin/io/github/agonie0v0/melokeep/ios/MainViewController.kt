package io.github.agonie0v0.melokeep.ios

import androidx.compose.ui.window.ComposeUIViewController
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import com.russhwolf.settings.Settings
import io.github.agonie0v0.melokeep.shared.MeloAiController
import io.github.agonie0v0.melokeep.shared.MeloKeepApiClient
import io.github.agonie0v0.melokeep.shared.MeloSearchController
import io.github.agonie0v0.melokeep.shared.MeloSettingsController
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import io.github.agonie0v0.melokeep.shared.MeloStudioController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import platform.UIKit.UIViewController

private object IosAppContainer {
	private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	private val settings = Settings()
	val meloSettingsStore = MeloSettingsStore(settings)
	val meloApiClient = MeloKeepApiClient(meloSettingsStore)
	val subsonicManager = IosSubsonicManager(settings, meloApiClient)
	val audioPlayer = IosAudioPlayer(subsonicManager, settings)
	val searchController = MeloSearchController(meloApiClient, appScope)
	val studioController = MeloStudioController(meloApiClient, appScope)
	val aiController = MeloAiController(meloApiClient, appScope)
	val settingsController = MeloSettingsController(meloApiClient, appScope)
}

@OptIn(ExperimentalCoilApi::class)
fun MainViewController(): UIViewController {
	val vc = ComposeUIViewController {
		setSingletonImageLoaderFactory { context ->
			ImageLoader.Builder(context)
				.components {
					add(KtorNetworkFetcherFactory(IosAppContainer.subsonicManager.rawHttpClient))
				}
				.crossfade(true)
				.build()
		}

		MeloKeepIosApp(
			subsonicManager = IosAppContainer.subsonicManager,
			audioPlayer = IosAppContainer.audioPlayer,
			searchController = IosAppContainer.searchController,
			studioController = IosAppContainer.studioController,
			aiController = IosAppContainer.aiController,
			settingsController = IosAppContainer.settingsController,
			settingsStore = IosAppContainer.meloSettingsStore,
			apiClient = IosAppContainer.meloApiClient,
		)
	}
	IosImagePicker.rootHostViewController = vc
	return vc
}
