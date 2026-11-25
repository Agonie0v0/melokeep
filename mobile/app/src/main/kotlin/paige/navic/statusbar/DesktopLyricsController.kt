package paige.navic.statusbar

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import paige.navic.R
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.model.lyrics.LyricsLine
import paige.navic.domain.model.lyrics.LyricsResult
import paige.navic.domain.repository.LyricsRepository
import kotlin.math.abs

/**
 * Renders an interactive floating desktop lyrics overlay window driven by [Player] playback
 * and persisted style/position settings in [PreferenceManager].
 */
class DesktopLyricsController(
	private val context: Context,
	private val scope: CoroutineScope,
	private val lyricsRepository: LyricsRepository,
	private val preferenceManager: PreferenceManager
) : Player.Listener {

	private data class OverlayPrefsSnapshot(
		val enabled: Boolean,
		val locked: Boolean,
		val fontSizeSp: Float,
		val colorHex: String,
		val bgAlpha: Float,
		val yFraction: Float,
		val offsetMs: Int
	)

	private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

	private var player: Player? = null
	private var lyricsJob: Job? = null
	private var tickerJob: Job? = null
	private var prefsJob: Job? = null
	private var hideControlsJob: Job? = null

	private var songId: String? = null
	private var lines: List<LyricsLine> = emptyList()

	private var overlayView: LinearLayout? = null
	private var cardBackground: GradientDrawable? = null
	private var lyricTextView: TextView? = null
	private var subTextView: TextView? = null
	private var controlsBar: LinearLayout? = null
	private var playPauseButton: ImageView? = null
	private var layoutParams: WindowManager.LayoutParams? = null
	private var controlsVisible = false

	fun attach(player: Player) {
		this.player = player
		player.addListener(this)
		prefsJob = scope.launch {
			snapshotFlow {
				OverlayPrefsSnapshot(
					enabled = preferenceManager.desktopLyricsEnabled,
					locked = preferenceManager.desktopLyricsLocked,
					fontSizeSp = preferenceManager.desktopLyricsFontSizeSp,
					colorHex = preferenceManager.desktopLyricsColorHex,
					bgAlpha = preferenceManager.desktopLyricsBgAlpha,
					yFraction = preferenceManager.desktopLyricsYFraction,
					offsetMs = preferenceManager.playerLyricsOffsetMs
				)
			}
				.distinctUntilChanged()
				.collect {
					showOrUpdateOverlay()
					syncTicker()
				}
		}
		loadLyrics(player.currentMediaItem)
	}

	fun release() {
		player?.removeListener(this)
		lyricsJob?.cancel()
		tickerJob?.cancel()
		prefsJob?.cancel()
		hideControlsJob?.cancel()
		player = null
		songId = null
		lines = emptyList()
		removeOverlay()
	}

	override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
		loadLyrics(mediaItem)
	}

	override fun onIsPlayingChanged(isPlaying: Boolean) {
		updatePlayPauseLabel()
		if (isPlaying) {
			syncTicker()
		} else {
			tickerJob?.cancel()
			updateLyricDisplay()
		}
	}

	override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
		syncTicker()
	}

	override fun onPositionDiscontinuity(
		oldPosition: Player.PositionInfo,
		newPosition: Player.PositionInfo,
		reason: Int
	) {
		syncTicker()
	}

	override fun onPlaybackStateChanged(playbackState: Int) {
		if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
			tickerJob?.cancel()
		}
		updateLyricDisplay()
		updatePlayPauseLabel()
	}

	override fun onPlayerError(error: PlaybackException) {
		tickerJob?.cancel()
		updateLyricDisplay()
	}

	private fun loadLyrics(mediaItem: MediaItem?) {
		lyricsJob?.cancel()
		tickerJob?.cancel()
		lines = emptyList()

		val id = mediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: run {
			songId = null
			updateLyricDisplay()
			return
		}

		songId = id
		updateLyricDisplay()
		lyricsJob = scope.launch {
			val result: LyricsResult? = runCatching {
				lyricsRepository.fetchLyrics(id)
			}.getOrNull()

			coroutineContext.ensureActive()
			if (songId != id) return@launch
			lines = result?.lines
				.orEmpty()
				.filter { it.time != null && it.text.isNotBlank() }
				.sortedBy { it.time }
			syncTicker()
		}
	}

	private fun syncTicker() {
		tickerJob?.cancel()
		updateLyricDisplay()

		if (!preferenceManager.desktopLyricsEnabled || !Settings.canDrawOverlays(context)) {
			return
		}
		val currentPlayer = player ?: return
		if (!currentPlayer.isPlaying || lines.isEmpty()) return

		val position = (currentPlayer.currentPosition + preferenceManager.playerLyricsOffsetMs)
			.coerceAtLeast(0L)
		val activeIndex = lines.indexOfLast {
			it.time!!.inWholeMilliseconds <= position
		}
		val nextTime = lines.getOrNull(activeIndex + 1)?.time?.inWholeMilliseconds ?: return
		val speed = currentPlayer.playbackParameters.speed.coerceAtLeast(0.01f)
		val waitMs = ((nextTime - position).coerceAtLeast(1L) / speed)
		tickerJob = scope.launch {
			delay(waitMs.toLong().coerceAtLeast(1L))
			syncTicker()
		}
	}

	private fun showOrUpdateOverlay() {
		if (!preferenceManager.desktopLyricsEnabled) {
			removeOverlay()
			return
		}
		if (!Settings.canDrawOverlays(context)) {
			preferenceManager.desktopLyricsEnabled = false
			removeOverlay()
			return
		}

		val metrics = context.resources.displayMetrics
		val screenWidth = metrics.widthPixels.coerceAtLeast(dpToPx(240))
		val screenHeight = metrics.heightPixels.coerceAtLeast(dpToPx(320))
		val overlayWidth = (screenWidth * 0.92f).toInt().coerceAtMost(dpToPx(460))
		val maxY = (screenHeight - dpToPx(120)).coerceAtLeast(0)
		val yFraction = preferenceManager.desktopLyricsYFraction.coerceIn(0f, 0.85f)
		val targetY = (maxY * yFraction).toInt().coerceIn(0, maxY)
		val locked = preferenceManager.desktopLyricsLocked

		val flags = if (locked) {
			WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
				WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
				WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
		} else {
			WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
				WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
		}

		if (overlayView == null) {
			createOverlayView()
			val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
			} else {
				@Suppress("DEPRECATION")
				WindowManager.LayoutParams.TYPE_PHONE
			}
			val params = WindowManager.LayoutParams(
				overlayWidth,
				WindowManager.LayoutParams.WRAP_CONTENT,
				windowType,
				flags,
				PixelFormat.TRANSLUCENT
			).apply {
				gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
				y = targetY
			}
			layoutParams = params
			applyOverlayAppearance()
			runCatching {
				windowManager.addView(overlayView, params)
			}.onFailure {
				removeOverlay()
			}
		} else {
			val params = layoutParams ?: return
			val needsLayoutUpdate = params.flags != flags || params.width != overlayWidth ||
				abs(params.y - targetY) > dpToPx(2)
			params.flags = flags
			params.width = overlayWidth
			params.y = targetY
			if (locked && controlsVisible) {
				setControlsVisible(false)
			}
			applyOverlayAppearance()
			if (needsLayoutUpdate) {
				runCatching {
					windowManager.updateViewLayout(overlayView, params)
				}
			}
		}
	}

	private fun removeOverlay() {
		hideControlsJob?.cancel()
		controlsVisible = false
		val view = overlayView ?: return
		runCatching {
			windowManager.removeViewImmediate(view)
		}
		overlayView = null
		cardBackground = null
		lyricTextView = null
		subTextView = null
		controlsBar = null
		playPauseButton = null
		layoutParams = null
	}

	private fun createOverlayView() {
		val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
		val bg = GradientDrawable().apply {
			cornerRadius = dpToPx(16).toFloat()
			setColor(Color.argb(115, 16, 18, 24))
		}
		cardBackground = bg

		val mainText = TextView(context).apply {
			gravity = Gravity.CENTER
			maxLines = 2
			ellipsize = TextUtils.TruncateAt.END
			typeface = Typeface.DEFAULT_BOLD
			setShadowLayer(4f, 0f, 1.5f, 0xCC000000.toInt())
			setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(2))
		}
		lyricTextView = mainText

		val subText = TextView(context).apply {
			gravity = Gravity.CENTER
			maxLines = 1
			ellipsize = TextUtils.TruncateAt.END
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
			setTextColor(0xB8FFFFFF.toInt())
			setShadowLayer(3f, 0f, 1f, 0xAA000000.toInt())
			visibility = View.GONE
			setPadding(dpToPx(6), 0, dpToPx(6), dpToPx(2))
		}
		subTextView = subText

		val controls = createControlsBar()
		controlsBar = controls

		val root = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			gravity = Gravity.CENTER_HORIZONTAL
			background = bg
			setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
			addView(
				mainText,
				LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT,
					LinearLayout.LayoutParams.WRAP_CONTENT
				)
			)
			addView(
				subText,
				LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT,
					LinearLayout.LayoutParams.WRAP_CONTENT
				)
			)
			addView(
				controls,
				LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.WRAP_CONTENT,
					LinearLayout.LayoutParams.WRAP_CONTENT
				).apply {
					topMargin = dpToPx(6)
				}
			)
		}

		var downRawY = 0f
		var downParamY = 0
		var dragging = false
		root.setOnTouchListener { _, event ->
			if (preferenceManager.desktopLyricsLocked) return@setOnTouchListener false
			val params = layoutParams ?: return@setOnTouchListener false
			when (event.actionMasked) {
				MotionEvent.ACTION_DOWN -> {
					downRawY = event.rawY
					downParamY = params.y
					dragging = false
					hideControlsJob?.cancel()
					true
				}

				MotionEvent.ACTION_MOVE -> {
					val deltaY = event.rawY - downRawY
					if (abs(deltaY) > touchSlop) {
						dragging = true
					}
					if (dragging) {
						val screenHeight = context.resources.displayMetrics.heightPixels.coerceAtLeast(dpToPx(320))
						val maxY = (screenHeight - dpToPx(120)).coerceAtLeast(1)
						params.y = (downParamY + deltaY).toInt().coerceIn(0, maxY)
						runCatching {
							windowManager.updateViewLayout(root, params)
						}
					}
					true
				}

				MotionEvent.ACTION_UP -> {
					if (dragging) {
						val screenHeight = context.resources.displayMetrics.heightPixels.coerceAtLeast(dpToPx(320))
						val maxY = (screenHeight - dpToPx(120)).coerceAtLeast(1)
						val newFraction = (params.y.toFloat() / maxY.toFloat()).coerceIn(0f, 0.85f)
						preferenceManager.desktopLyricsYFraction = newFraction
					} else {
						setControlsVisible(!controlsVisible)
					}
					if (controlsVisible) {
						scheduleHideControls()
					}
					true
				}

				MotionEvent.ACTION_CANCEL -> {
					if (controlsVisible) {
						scheduleHideControls()
					}
					true
				}

				else -> false
			}
		}

		overlayView = root
		updatePlayPauseLabel()
	}

	private fun createActionButtonBackground(isPrimary: Boolean = false): StateListDrawable {
		val corner = dpToPx(16).toFloat()
		val strokeWidth = dpToPx(1).coerceAtLeast(1)
		val normalDrawable = GradientDrawable().apply {
			cornerRadius = corner
			setColor(if (isPrimary) 0xEB2A3140.toInt() else 0xCC1E222B.toInt())
			setStroke(strokeWidth, if (isPrimary) 0x66FFFFFF else 0x42FFFFFF)
		}
		val pressedDrawable = GradientDrawable().apply {
			cornerRadius = corner
			setColor(0xE63B4354.toInt())
			setStroke(strokeWidth, 0x7AFFFFFF)
		}
		return StateListDrawable().apply {
			addState(intArrayOf(android.R.attr.state_pressed), pressedDrawable)
			addState(intArrayOf(), normalDrawable)
		}
	}

	private fun createControlsBar(): LinearLayout {
		val bar = LinearLayout(context).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			visibility = View.GONE
		}

		fun buttonLayoutParams(): LinearLayout.LayoutParams {
			return LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT,
				dpToPx(32)
			).apply {
				marginStart = dpToPx(2)
				marginEnd = dpToPx(2)
			}
		}

		fun addTextButton(label: String, description: String, onClick: () -> Unit): TextView {
			val btn = TextView(context).apply {
				text = label
				contentDescription = description
				setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
				setTextColor(Color.WHITE)
				typeface = Typeface.DEFAULT_BOLD
				includeFontPadding = false
				gravity = Gravity.CENTER
				background = createActionButtonBackground()
				minWidth = dpToPx(34)
				minimumWidth = dpToPx(34)
				setPadding(dpToPx(8), 0, dpToPx(8), 0)
				isClickable = true
				isFocusable = true
				setOnClickListener {
					onClick()
					if (controlsVisible) scheduleHideControls()
				}
			}
			bar.addView(btn, buttonLayoutParams())
			return btn
		}

		fun addIconButton(
			@DrawableRes iconRes: Int,
			description: String,
			isPrimary: Boolean = false,
			onClick: () -> Unit
		): ImageView {
			val widthPx = if (isPrimary) dpToPx(36) else dpToPx(32)
			val heightPx = dpToPx(32)
			val btn = ImageView(context).apply {
				setImageResource(iconRes)
				contentDescription = description
				scaleType = ImageView.ScaleType.CENTER_INSIDE
				imageTintList = ColorStateList.valueOf(Color.WHITE)
				background = createActionButtonBackground(isPrimary = isPrimary)
				setPadding(dpToPx(7), dpToPx(7), dpToPx(7), dpToPx(7))
				isClickable = true
				isFocusable = true
				setOnClickListener {
					onClick()
					if (controlsVisible) scheduleHideControls()
				}
			}
			bar.addView(
				btn,
				LinearLayout.LayoutParams(widthPx, heightPx).apply {
					marginStart = dpToPx(2)
					marginEnd = dpToPx(2)
				}
			)
			return btn
		}

		addTextButton("A-", "减小桌面歌词字号") {
			preferenceManager.desktopLyricsFontSizeSp =
				(preferenceManager.desktopLyricsFontSizeSp - 2f).coerceIn(12f, 32f)
		}
		addTextButton("A+", "增大桌面歌词字号") {
			preferenceManager.desktopLyricsFontSizeSp =
				(preferenceManager.desktopLyricsFontSizeSp + 2f).coerceIn(12f, 32f)
		}
		addIconButton(R.drawable.ic_desktop_lyrics_prev, "上一首") {
			player?.let { p ->
				if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem()
			}
		}
		playPauseButton = addIconButton(
			iconRes = R.drawable.ic_desktop_lyrics_pause,
			description = "播放或暂停",
			isPrimary = true
		) {
			player?.let { p ->
				if (p.isPlaying) p.pause() else p.play()
			}
		}
		addIconButton(R.drawable.ic_desktop_lyrics_next, "下一首") {
			player?.let { p ->
				if (p.hasNextMediaItem()) p.seekToNextMediaItem()
			}
		}
		addIconButton(R.drawable.ic_desktop_lyrics_lock, "锁定桌面歌词") {
			preferenceManager.desktopLyricsLocked = true
			setControlsVisible(false)
			Toast.makeText(context, "桌面歌词已锁定（触摸穿透），可在设置中解锁", Toast.LENGTH_SHORT).show()
		}
		addIconButton(R.drawable.ic_desktop_lyrics_home, "返回应用") {
			openApp()
		}
		addIconButton(R.drawable.ic_desktop_lyrics_close, "关闭桌面歌词") {
			preferenceManager.desktopLyricsEnabled = false
		}

		return bar
	}

	private fun openApp() {
		val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
		launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
		runCatching { context.startActivity(launchIntent) }
	}

	private fun setControlsVisible(visible: Boolean) {
		controlsVisible = visible
		controlsBar?.visibility = if (visible) View.VISIBLE else View.GONE
		updateCardBackground()
		if (visible) {
			scheduleHideControls()
		} else {
			hideControlsJob?.cancel()
		}
	}

	private fun scheduleHideControls() {
		hideControlsJob?.cancel()
		hideControlsJob = scope.launch {
			delay(4000)
			setControlsVisible(false)
		}
	}

	private fun updateCardBackground() {
		val userBgAlpha = preferenceManager.desktopLyricsBgAlpha.coerceIn(0f, 0.9f)
		val effectiveAlpha = if (controlsVisible) maxOf(userBgAlpha, 0.78f) else userBgAlpha
		val alphaInt = (effectiveAlpha * 255f).toInt().coerceIn(0, 230)
		cardBackground?.apply {
			setColor(Color.argb(alphaInt, 18, 20, 26))
			if (controlsVisible) {
				setStroke(dpToPx(1).coerceAtLeast(1), 0x33FFFFFF)
			} else {
				setStroke(0, Color.TRANSPARENT)
			}
		}
	}

	private fun applyOverlayAppearance() {
		val fontSizeSp = preferenceManager.desktopLyricsFontSizeSp.coerceIn(12f, 32f)
		val color = parseColorOrDefault(preferenceManager.desktopLyricsColorHex, Color.WHITE)

		lyricTextView?.apply {
			setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
			setTextColor(color)
		}
		updateCardBackground()
	}

	private fun updateLyricDisplay() {
		val mainView = lyricTextView ?: return
		val subView = subTextView ?: return
		val currentPlayer = player
		val metadata = currentPlayer?.currentMediaItem?.mediaMetadata
		val title = metadata?.title?.toString().orEmpty()
		val artist = metadata?.artist?.toString().orEmpty()

		if (lines.isEmpty() || currentPlayer == null) {
			mainView.text = title.ifBlank { "MeloKeep 桌面歌词" }
			if (artist.isNotBlank()) {
				subView.text = artist
				subView.visibility = View.VISIBLE
			} else {
				subView.visibility = View.GONE
			}
			return
		}

		val position = (currentPlayer.currentPosition + preferenceManager.playerLyricsOffsetMs)
			.coerceAtLeast(0L)
		val activeIndex = lines.indexOfLast {
			it.time!!.inWholeMilliseconds <= position
		}
		val currentText = lines.getOrNull(activeIndex)?.text.orEmpty()
		if (currentText.isNotBlank()) {
			mainView.text = currentText
			subView.visibility = View.GONE
		} else {
			mainView.text = title.ifBlank { "♪ ♪ ♪" }
			if (artist.isNotBlank()) {
				subView.text = artist
				subView.visibility = View.VISIBLE
			} else {
				subView.visibility = View.GONE
			}
		}
	}

	private fun updatePlayPauseLabel() {
		val isPlaying = player?.isPlaying == true
		playPauseButton?.apply {
			setImageResource(
				if (isPlaying) R.drawable.ic_desktop_lyrics_pause else R.drawable.ic_desktop_lyrics_play
			)
			contentDescription = if (isPlaying) "暂停" else "播放"
		}
	}

	private fun parseColorOrDefault(rawHex: String, fallback: Int): Int {
		val clean = rawHex.trim().removePrefix("#")
		if (clean.length != 6 && clean.length != 8) return fallback
		return runCatching {
			Color.parseColor("#$clean")
		}.getOrDefault(fallback)
	}

	private fun dpToPx(dp: Int): Int {
		return TypedValue.applyDimension(
			TypedValue.COMPLEX_UNIT_DIP,
			dp.toFloat(),
			context.resources.displayMetrics
		).toInt()
	}
}
