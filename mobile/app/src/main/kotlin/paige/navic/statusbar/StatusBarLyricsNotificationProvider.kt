package paige.navic.statusbar

import android.app.Notification
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList

/**
 * Adds the Meizu/Flyme status-bar lyric markers to Navic's normal media notification.
 *
 * The notification remains owned by Media3. This wrapper only changes a cloned notification so
 * artwork, actions, foreground-service behaviour and Android Auto keep using the upstream path.
 */
@UnstableApi
class StatusBarLyricsNotificationProvider(
	private val delegate: DefaultMediaNotificationProvider
) : MediaNotification.Provider {
	private companion object {
		const val FLAG_ALWAYS_SHOW_TICKER = 0x01000000
		const val FLAG_ONLY_UPDATE_TICKER = 0x02000000
	}

	private var baseNotification: MediaNotification? = null
	private var callback: MediaNotification.Provider.Callback? = null
	private var lyric: String? = null

	fun updateLyric(text: String?, onlyUpdateTicker: Boolean = true) {
		val normalized = text?.trim()?.takeIf { it.isNotEmpty() }
		if (normalized == lyric) return

		lyric = normalized
		val base = baseNotification ?: return
		callback?.onNotificationChanged(decorate(base, onlyUpdateTicker))
	}

	fun release() {
		baseNotification = null
		callback = null
		lyric = null
	}

	override fun createNotification(
		mediaSession: MediaSession,
		mediaButtonPreferences: ImmutableList<CommandButton>,
		actionFactory: MediaNotification.ActionFactory,
		onNotificationChangedCallback: MediaNotification.Provider.Callback
	): MediaNotification {
		callback = onNotificationChangedCallback

		val notification = delegate.createNotification(
			mediaSession,
			mediaButtonPreferences,
			actionFactory,
			object : MediaNotification.Provider.Callback {
				override fun onNotificationChanged(notification: MediaNotification) {
					baseNotification = notification
					onNotificationChangedCallback.onNotificationChanged(
						decorate(notification, onlyUpdateTicker = false)
					)
				}
			}
		)

		baseNotification = notification
		return decorate(notification, onlyUpdateTicker = false)
	}

	override fun handleCustomCommand(
		session: MediaSession,
		action: String,
		extras: android.os.Bundle
	): Boolean = delegate.handleCustomCommand(session, action, extras)

	private fun decorate(
		source: MediaNotification,
		onlyUpdateTicker: Boolean
	): MediaNotification {
		val notification = source.notification.clone()
		val baseFlags = notification.flags and
			FLAG_ALWAYS_SHOW_TICKER.inv() and FLAG_ONLY_UPDATE_TICKER.inv()

		if (lyric != null) {
			notification.tickerText = lyric
			notification.flags = baseFlags or FLAG_ALWAYS_SHOW_TICKER or
				if (onlyUpdateTicker) FLAG_ONLY_UPDATE_TICKER else 0
		} else {
			notification.tickerText = null
			notification.flags = baseFlags
		}

		return MediaNotification(source.notificationId, notification)
	}
}
