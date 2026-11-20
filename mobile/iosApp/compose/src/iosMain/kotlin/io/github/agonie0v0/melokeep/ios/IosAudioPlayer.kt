@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.github.agonie0v0.melokeep.ios

import com.russhwolf.settings.Settings
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.AVFAudio.setActive
import platform.AVFoundation.AVPlayerActionAtItemEndNone
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.AVQueuePlayer
import platform.AVFoundation.actionAtItemEnd
import platform.AVFoundation.currentItem
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.playImmediatelyAtRate
import platform.AVFoundation.seekToTime
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.volume
import platform.AVFoundation.addPeriodicTimeObserverForInterval
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.closeFile
import platform.Foundation.dataWithBytes
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeData
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPMediaItemArtwork
import platform.MediaPlayer.MPMediaItemPropertyAlbumTitle
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyArtwork
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusCommandFailed
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid
import platform.UIKit.UIImage
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

enum class IosRepeatMode {
	OFF,
	ALL,
	ONE,
}

@Serializable
data class IosOfflineTrackRecord(
	val track: IosTrackItem,
	val fileName: String,
	val sizeBytes: Long,
	val savedAtEpochSec: Long = 0L,
)

data class IosDownloadTask(
	val track: IosTrackItem,
	val progress: Float = 0f,
	val status: String = "downloading", // "downloading" | "completed" | "failed" | "cancelled"
	val error: String? = null,
	val downloadedBytes: Long = 0L,
	val totalBytes: Long = 0L,
)

data class IosPhoneOfflineState(
	val offlineTracks: List<IosTrackItem> = emptyList(),
	val activeDownloads: List<IosDownloadTask> = emptyList(),
	val completedDownloads: List<IosDownloadTask> = emptyList(),
	val failedDownloads: List<IosDownloadTask> = emptyList(),
	val totalOfflineBytes: Long = 0L,
	val freeSpaceBytes: Long = 0L,
)

data class IosSleepTimerState(
	val active: Boolean = false,
	val remainingSeconds: Long = 0L,
	val stopAfterCurrent: Boolean = false,
)

data class IosPlaybackProgress(
	val positionMs: Long = 0L,
	val durationMs: Long = 0L,
) {
	val fraction: Float
		get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
}

data class IosPlayerUiState(
	val queue: List<IosTrackItem> = emptyList(),
	val currentIndex: Int = -1,
	val isPlaying: Boolean = false,
	val isLoading: Boolean = false,
	val positionMs: Long = 0L,
	val durationMs: Long = 0L,
	val repeatMode: IosRepeatMode = IosRepeatMode.OFF,
	val shuffleEnabled: Boolean = false,
	val playbackSpeed: Float = 1.0f,
	val volume: Float = 1.0f,
	val lyrics: IosLyricsResult = IosLyricsResult(emptyList(), false),
	val lyricsLoading: Boolean = false,
	val lyricsOffsetMs: Int = 0,
	val sleepTimer: IosSleepTimerState = IosSleepTimerState(),
	val offlineTracks: Map<String, IosOfflineTrackRecord> = emptyMap(),
	val downloadingProgress: Map<String, Float> = emptyMap(),
	val errorMessage: String? = null,
	val noticeMessage: String? = null,
) {
	val currentTrack: IosTrackItem?
		get() = queue.getOrNull(currentIndex)
}

class IosAudioPlayer(
	private val subsonicManager: IosSubsonicManager,
	private val settings: Settings,
	private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
	private val json = Json {
		ignoreUnknownKeys = true
		encodeDefaults = true
		coerceInputValues = true
	}

	private val queuePlayer = AVQueuePlayer().apply {
		actionAtItemEnd = AVPlayerActionAtItemEndNone
	}

	private val _state = MutableStateFlow(
		IosPlayerUiState(
			lyricsOffsetMs = subsonicManager.prefs.value.lyricsOffsetMs,
			offlineTracks = loadOfflineRecords(),
		)
	)
	val state: StateFlow<IosPlayerUiState> = _state.asStateFlow()

	private val _progressFlow = MutableStateFlow(IosPlaybackProgress())
	val progressFlow: StateFlow<IosPlaybackProgress> = _progressFlow.asStateFlow()

	private val _offlineState = MutableStateFlow(IosPhoneOfflineState())
	val offlineState: StateFlow<IosPhoneOfflineState> = _offlineState.asStateFlow()

	private val activeDownloadJobs = mutableMapOf<String, Job>()
	private val downloadTasks = mutableMapOf<String, IosDownloadTask>()

	private var unshuffledQueue: List<IosTrackItem> = emptyList()
	private var pausedByInterruption = false
	private var loadJob: Job? = null
	private var lyricsJob: Job? = null
	private var sleepTimerJob: Job? = null
	private var progressObserverToken: Any? = null
	private val notificationObservers = mutableListOf<Any>()

	private var activeScrobbleTrackId: String? = null
	private var activeScrobbleReported = false
	private var accumulatedPlayedMs = 0L
	private var lastObservedPositionMs = 0L
	private var lastNowPlayingSyncMs = 0L
	private var currentArtworkUrl: String? = null
	private var currentArtworkImage: UIImage? = null
	private val artworkCache = mutableMapOf<String, UIImage>()

	private val offlineDirPath: String by lazy {
		val docs = NSSearchPathForDirectoriesInDomains(
			NSDocumentDirectory,
			NSUserDomainMask,
			true,
		).firstOrNull() as? String ?: ""
		val dir = "$docs/offline_tracks"
		NSFileManager.defaultManager.createDirectoryAtPath(
			path = dir,
			withIntermediateDirectories = true,
			attributes = null,
			error = null,
		)
		dir
	}

	init {
		configureAudioSession()
		registerNotificationObservers()
		registerRemoteCommands()
		registerPeriodicTimeObserver()
		refreshOfflineState()
	}

	private fun configureAudioSession() {
		runOnMain { configureAudioSessionOnMain() }
	}

	private fun configureAudioSessionOnMain() {
		val session = AVAudioSession.sharedInstance()
		session.setCategory(
			AVAudioSessionCategoryPlayback,
			mode = AVAudioSessionModeDefault,
			options = 0uL,
			error = null,
		)
		session.setActive(true, error = null)
	}

	private fun registerNotificationObservers() {
		val center = NSNotificationCenter.defaultCenter
		notificationObservers += center.addObserverForName(
			name = AVAudioSessionInterruptionNotification,
			`object` = null,
			queue = NSOperationQueue.mainQueue,
		) { note ->
			val info = note?.userInfo ?: return@addObserverForName
			val type = (info[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.longValue
			when (type) {
				AVAudioSessionInterruptionTypeBegan.toLong() -> {
					if (_state.value.isPlaying) {
						pausedByInterruption = true
						pauseOnMain()
					}
				}
				AVAudioSessionInterruptionTypeEnded.toLong() -> {
					val options = (info[AVAudioSessionInterruptionOptionKey] as? NSNumber)?.longValue ?: 0L
					val shouldResume = (options and AVAudioSessionInterruptionOptionShouldResume.toLong()) != 0L
					if (pausedByInterruption && shouldResume) {
						pausedByInterruption = false
						resumeOnMain()
					} else {
						pausedByInterruption = false
					}
				}
			}
		}

		notificationObservers += center.addObserverForName(
			name = AVAudioSessionRouteChangeNotification,
			`object` = null,
			queue = NSOperationQueue.mainQueue,
		) { note ->
			val info = note?.userInfo ?: return@addObserverForName
			val reason = (info[AVAudioSessionRouteChangeReasonKey] as? NSNumber)?.longValue
			if (reason == AVAudioSessionRouteChangeReasonOldDeviceUnavailable.toLong()) {
				pauseOnMain()
			}
		}

		notificationObservers += center.addObserverForName(
			name = AVPlayerItemDidPlayToEndTimeNotification,
			`object` = null,
			queue = NSOperationQueue.mainQueue,
		) { note ->
			val endedItem = note?.`object` as? AVPlayerItem ?: return@addObserverForName
			if (endedItem === queuePlayer.currentItem) {
				handleItemDidPlayToEnd()
			}
		}
	}

	private fun registerRemoteCommands() {
		val commandCenter = MPRemoteCommandCenter.sharedCommandCenter()

		commandCenter.playCommand.enabled = true
		commandCenter.playCommand.addTargetWithHandler { _ ->
			runOnMain { resumeOnMain() }
			MPRemoteCommandHandlerStatusSuccess.toLong()
		}

		commandCenter.pauseCommand.enabled = true
		commandCenter.pauseCommand.addTargetWithHandler { _ ->
			runOnMain { pauseOnMain() }
			MPRemoteCommandHandlerStatusSuccess.toLong()
		}

		commandCenter.togglePlayPauseCommand.enabled = true
		commandCenter.togglePlayPauseCommand.addTargetWithHandler { _ ->
			runOnMain { togglePlayPauseOnMain() }
			MPRemoteCommandHandlerStatusSuccess.toLong()
		}

		commandCenter.nextTrackCommand.enabled = true
		commandCenter.nextTrackCommand.addTargetWithHandler { _ ->
			next()
			MPRemoteCommandHandlerStatusSuccess.toLong()
		}

		commandCenter.previousTrackCommand.enabled = true
		commandCenter.previousTrackCommand.addTargetWithHandler { _ ->
			previous()
			MPRemoteCommandHandlerStatusSuccess.toLong()
		}

		commandCenter.changePlaybackPositionCommand.enabled = true
		commandCenter.changePlaybackPositionCommand.addTargetWithHandler { event ->
			val posEvent = event as? MPChangePlaybackPositionCommandEvent
			if (posEvent != null) {
				seekTo((posEvent.positionTime * 1000.0).roundToLong())
				MPRemoteCommandHandlerStatusSuccess.toLong()
			} else {
				MPRemoteCommandHandlerStatusCommandFailed.toLong()
			}
		}
	}

	private fun registerPeriodicTimeObserver() {
		progressObserverToken = queuePlayer.addPeriodicTimeObserverForInterval(
			interval = CMTimeMakeWithSeconds(0.2, 600),
			queue = dispatch_get_main_queue(),
		) { time ->
			val rawSec = CMTimeGetSeconds(time)
			val posMs = if (!rawSec.isNaN() && !rawSec.isInfinite() && rawSec >= 0.0) {
				(rawSec * 1000.0).roundToLong()
			} else {
				_progressFlow.value.positionMs
			}
			val currentTrack = _state.value.currentTrack
			if (currentTrack == null) return@addPeriodicTimeObserverForInterval

			val itemDurSec = queuePlayer.currentItem?.duration?.let { CMTimeGetSeconds(it) } ?: Double.NaN
			val durMs = if (!itemDurSec.isNaN() && !itemDurSec.isInfinite() && itemDurSec > 0.0) {
				(itemDurSec * 1000.0).roundToLong()
			} else {
				currentTrack.durationMs.takeIf { it > 0L } ?: _progressFlow.value.durationMs
			}
			val resolvedDurMs = durMs.coerceAtLeast(currentTrack.durationMs)
			val status = queuePlayer.timeControlStatus
			val isPlaying = status == AVPlayerTimeControlStatusPlaying
			val isBuffering = status == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
			val nextLoading = isBuffering && !isPlaying

			if (isPlaying && posMs > lastObservedPositionMs) {
				val delta = posMs - lastObservedPositionMs
				if (delta in 1L..2500L) {
					accumulatedPlayedMs += delta
				}
			}
			lastObservedPositionMs = posMs

			// 高频进度只推送至独立 progressFlow，严禁每帧污染主 state 引发根壳层和曲库重组
			val prevProgress = _progressFlow.value
			if (prevProgress.positionMs != posMs || prevProgress.durationMs != resolvedDurMs) {
				_progressFlow.value = IosPlaybackProgress(
					positionMs = posMs,
					durationMs = resolvedDurMs,
				)
			}

			val prevState = _state.value
			val stateChanged = prevState.isPlaying != isPlaying ||
				prevState.isLoading != nextLoading ||
				prevState.durationMs != resolvedDurMs
			if (stateChanged) {
				_state.update { current ->
					if (current.currentTrack == null) return@update current
					current.copy(
						isPlaying = isPlaying,
						isLoading = nextLoading,
						durationMs = resolvedDurMs,
					)
				}
			}

			checkAndSubmitScrobble(resolvedDurMs)
			if (stateChanged || kotlin.math.abs(posMs - lastNowPlayingSyncMs) >= 1_000L) {
				lastNowPlayingSyncMs = posMs
				updateNowPlayingMetadata()
			}
		}
	}

	fun playQueue(tracks: List<IosTrackItem>, startIndex: Int = 0) {
		if (tracks.isEmpty()) return
		val clampedIndex = startIndex.coerceIn(0, tracks.lastIndex)
		val pickedTrack = tracks[clampedIndex]
		unshuffledQueue = tracks

		val activeQueue = if (_state.value.shuffleEnabled && tracks.size > 1) {
			val others = tracks.filterIndexed { idx, _ -> idx != clampedIndex }.shuffled()
			listOf(pickedTrack) + others
		} else {
			tracks
		}
		val activeIndex = if (_state.value.shuffleEnabled) 0 else clampedIndex

		_state.update {
			it.copy(
				queue = activeQueue,
				currentIndex = activeIndex,
				errorMessage = null,
			)
		}
		loadAndPlayTrackAt(activeIndex)
	}

	fun playSingle(track: IosTrackItem) {
		playQueue(listOf(track), 0)
	}

	fun playNext(track: IosTrackItem) {
		val current = _state.value
		if (current.queue.isEmpty() || current.currentIndex < 0) {
			playSingle(track)
			return
		}
		unshuffledQueue = unshuffledQueue.toMutableList().apply {
			val anchor = current.currentTrack
			val anchorIdx = if (anchor != null) indexOfFirst { it.id == anchor.id } else -1
			add(if (anchorIdx >= 0) anchorIdx + 1 else size, track)
		}
		val updated = current.queue.toMutableList()
		val insertAt = (current.currentIndex + 1).coerceAtMost(updated.size)
		updated.add(insertAt, track)
		_state.update {
			it.copy(
				queue = updated,
				noticeMessage = "将在下一首播放：${track.title}",
			)
		}
	}

	fun appendToQueue(tracks: List<IosTrackItem>) {
		if (tracks.isEmpty()) return
		val current = _state.value
		if (current.queue.isEmpty()) {
			playQueue(tracks, 0)
			return
		}
		unshuffledQueue = unshuffledQueue + tracks
		_state.update {
			it.copy(
				queue = it.queue + tracks,
				noticeMessage = "已追加 ${tracks.size} 首歌曲到队列",
			)
		}
	}

	fun skipToQueueIndex(index: Int) {
		val queue = _state.value.queue
		if (index !in queue.indices) return
		_state.update { it.copy(currentIndex = index) }
		loadAndPlayTrackAt(index)
	}

	fun removeQueueItem(index: Int) {
		val current = _state.value
		if (index !in current.queue.indices) return
		val removed = current.queue[index]
		val updated = current.queue.toMutableList().apply { removeAt(index) }
		unshuffledQueue = unshuffledQueue.filterNot { it.id == removed.id }

		if (updated.isEmpty()) {
			clearQueue()
			return
		}
		val newIndex = when {
			index < current.currentIndex -> current.currentIndex - 1
			index == current.currentIndex -> current.currentIndex.coerceAtMost(updated.lastIndex)
			else -> current.currentIndex
		}
		_state.update { it.copy(queue = updated, currentIndex = newIndex) }
		if (index == current.currentIndex) {
			loadAndPlayTrackAt(newIndex)
		}
	}

	fun moveQueueItem(fromIndex: Int, toIndex: Int) {
		val current = _state.value
		if (fromIndex !in current.queue.indices || toIndex !in current.queue.indices || fromIndex == toIndex) {
			return
		}
		val activeTrack = current.currentTrack
		val mutable = current.queue.toMutableList()
		val moved = mutable.removeAt(fromIndex)
		mutable.add(toIndex, moved)
		val nextIndex = activeTrack?.let { t -> mutable.indexOfFirst { it.id == t.id } } ?: current.currentIndex
		if (!current.shuffleEnabled) {
			unshuffledQueue = mutable
		}
		_state.update { it.copy(queue = mutable, currentIndex = nextIndex) }
	}

	fun clearQueue() {
		loadJob?.cancel()
		lyricsJob?.cancel()
		runOnMain {
			queuePlayer.pause()
			queuePlayer.removeAllItems()
			MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
		}
		lastObservedPositionMs = 0L
		lastNowPlayingSyncMs = 0L
		_progressFlow.value = IosPlaybackProgress()
		_state.update {
			it.copy(
				queue = emptyList(),
				currentIndex = -1,
				isPlaying = false,
				isLoading = false,
				positionMs = 0L,
				durationMs = 0L,
				lyrics = IosLyricsResult(emptyList(), false),
			)
		}
	}

	fun togglePlayPause() {
		runOnMain { togglePlayPauseOnMain() }
	}

	fun pause() {
		runOnMain { pauseOnMain() }
	}

	fun resume() {
		runOnMain { resumeOnMain() }
	}

	fun next() {
		val current = _state.value
		if (current.queue.isEmpty()) return
		flushScrobbleOnTrackChange()
		val nextIndex = when {
			current.currentIndex + 1 < current.queue.size -> current.currentIndex + 1
			current.repeatMode == IosRepeatMode.ALL -> 0
			else -> return
		}
		_state.update { it.copy(currentIndex = nextIndex) }
		loadAndPlayTrackAt(nextIndex)
	}

	fun previous() {
		val current = _state.value
		if (current.queue.isEmpty()) return
		if (_progressFlow.value.positionMs > 4_000L) {
			seekTo(0L)
			return
		}
		flushScrobbleOnTrackChange()
		val prevIndex = when {
			current.currentIndex - 1 >= 0 -> current.currentIndex - 1
			current.repeatMode == IosRepeatMode.ALL -> current.queue.lastIndex
			else -> 0
		}
		_state.update { it.copy(currentIndex = prevIndex) }
		loadAndPlayTrackAt(prevIndex)
	}

	fun seekTo(positionMs: Long) {
		val clamped = positionMs.coerceAtLeast(0L)
		lastObservedPositionMs = clamped
		lastNowPlayingSyncMs = clamped
		_progressFlow.update { current ->
			val dur = current.durationMs.coerceAtLeast(_state.value.durationMs)
			current.copy(positionMs = clamped, durationMs = dur)
		}
		runOnMain {
			queuePlayer.seekToTime(
				time = CMTimeMakeWithSeconds(clamped / 1000.0, 600),
				toleranceBefore = CMTimeMakeWithSeconds(0.05, 600),
				toleranceAfter = CMTimeMakeWithSeconds(0.05, 600),
			) { _ ->
				updateNowPlayingMetadata()
			}
		}
	}

	fun cycleRepeatMode() {
		_state.update { current ->
			val next = when (current.repeatMode) {
				IosRepeatMode.OFF -> IosRepeatMode.ALL
				IosRepeatMode.ALL -> IosRepeatMode.ONE
				IosRepeatMode.ONE -> IosRepeatMode.OFF
			}
			current.copy(repeatMode = next)
		}
	}

	fun toggleShuffle() {
		val current = _state.value
		val nextShuffle = !current.shuffleEnabled
		val activeTrack = current.currentTrack
		if (current.queue.isEmpty()) {
			_state.update { it.copy(shuffleEnabled = nextShuffle) }
			return
		}
		if (nextShuffle) {
			if (unshuffledQueue.isEmpty()) unshuffledQueue = current.queue
			val rest = if (activeTrack != null) {
				current.queue.filter { it.id != activeTrack.id }.shuffled()
			} else {
				current.queue.shuffled()
			}
			val shuffled = if (activeTrack != null) listOf(activeTrack) + rest else rest
			_state.update {
				it.copy(
					shuffleEnabled = true,
					queue = shuffled,
					currentIndex = if (activeTrack != null) 0 else 0,
				)
			}
		} else {
			val base = unshuffledQueue.ifEmpty { current.queue }
			val idx = activeTrack?.let { t -> base.indexOfFirst { it.id == t.id } }?.takeIf { it >= 0 } ?: 0
			_state.update {
				it.copy(
					shuffleEnabled = false,
					queue = base,
					currentIndex = idx,
				)
			}
		}
	}

	fun setPlaybackSpeed(speed: Float) {
		val clamped = speed.coerceIn(0.5f, 2.0f)
		_state.update { it.copy(playbackSpeed = clamped) }
		runOnMain {
			if (queuePlayer.timeControlStatus == AVPlayerTimeControlStatusPlaying) {
				queuePlayer.playImmediatelyAtRate(clamped)
			}
			updateNowPlayingMetadata()
		}
	}

	fun setVolume(volume: Float) {
		val clamped = volume.coerceIn(0f, 1f)
		_state.update { it.copy(volume = clamped) }
		applyReplayGainAndVolume()
	}

	fun refreshReplayGain() {
		applyReplayGainAndVolume()
	}

	fun adjustLyricsOffset(deltaMs: Int) {
		val next = (_state.value.lyricsOffsetMs + deltaMs).coerceIn(-5000, 5000)
		setLyricsOffset(next)
	}

	fun resetLyricsOffset() {
		setLyricsOffset(0)
	}

	fun setLyricsOffset(offsetMs: Int) {
		val clamped = offsetMs.coerceIn(-5000, 5000)
		_state.update { it.copy(lyricsOffsetMs = clamped) }
		subsonicManager.updatePrefs { it.copy(lyricsOffsetMs = clamped) }
	}

	fun setSleepTimer(minutes: Int, stopAfterCurrent: Boolean = false) {
		sleepTimerJob?.cancel()
		if (minutes <= 0 && !stopAfterCurrent) {
			_state.update { it.copy(sleepTimer = IosSleepTimerState()) }
			return
		}
		if (stopAfterCurrent) {
			_state.update {
				it.copy(
					sleepTimer = IosSleepTimerState(
						active = true,
						remainingSeconds = 0L,
						stopAfterCurrent = true,
					),
					noticeMessage = "已设置：播完本首后停止播放",
				)
			}
			return
		}
		val totalSeconds = minutes * 60L
		_state.update {
			it.copy(
				sleepTimer = IosSleepTimerState(
					active = true,
					remainingSeconds = totalSeconds,
					stopAfterCurrent = false,
				),
				noticeMessage = "睡眠定时已开启：${minutes} 分钟后暂停",
			)
		}
		sleepTimerJob = scope.launch {
			var remaining = totalSeconds
			while (isActive && remaining > 0L) {
				delay(1000L)
				remaining -= 1L
				_state.update { current ->
					if (!current.sleepTimer.active || current.sleepTimer.stopAfterCurrent) return@update current
					current.copy(sleepTimer = current.sleepTimer.copy(remainingSeconds = remaining))
				}
			}
			if (isActive) {
				runOnMain { pauseOnMain() }
				_state.update {
					it.copy(
						sleepTimer = IosSleepTimerState(),
						noticeMessage = "睡眠定时器已到达，已暂停播放",
					)
				}
			}
		}
	}

	private fun refreshOfflineState() {
		val records = _state.value.offlineTracks.values.toList()
		val offlineTracks = records.map { it.track }
		val totalBytes = records.sumOf { it.sizeBytes }
		val freeBytes = queryFreeSpaceBytes()

		val activeList = downloadTasks.values.filter { it.status == "downloading" }
		val completedList = downloadTasks.values.filter { it.status == "completed" }
		val failedList = downloadTasks.values.filter { it.status == "failed" }

		_offlineState.update {
			it.copy(
				offlineTracks = offlineTracks,
				activeDownloads = activeList,
				completedDownloads = completedList,
				failedDownloads = failedList,
				totalOfflineBytes = totalBytes,
				freeSpaceBytes = freeBytes,
			)
		}
	}

	private fun queryFreeSpaceBytes(): Long {
		return runCatching {
			val attrs = NSFileManager.defaultManager.attributesOfFileSystemForPath(offlineDirPath, error = null)
			(attrs?.get(NSFileSystemFreeSize) as? NSNumber)?.longValue ?: 0L
		}.getOrDefault(0L)
	}

	fun isTrackOffline(trackId: String): Boolean {
		val rec = _state.value.offlineTracks[trackId] ?: return false
		return NSFileManager.defaultManager.fileExistsAtPath("$offlineDirPath/${rec.fileName}")
	}

	fun getOfflineProgress(trackId: String): Float? {
		val task = downloadTasks[trackId]
		if (task != null && task.status == "downloading") return task.progress
		if (_state.value.downloadingProgress.containsKey(trackId)) return _state.value.downloadingProgress[trackId]
		if (isTrackOffline(trackId)) return 1.0f
		return null
	}

	fun downloadTrackOffline(track: IosTrackItem) {
		if (isTrackOffline(track.id)) {
			return
		}
		if (activeDownloadJobs[track.id]?.isActive == true) {
			return
		}
		val task = IosDownloadTask(
			track = track,
			progress = 0.01f,
			status = "downloading",
		)
		downloadTasks[track.id] = task
		_state.update { it.copy(downloadingProgress = it.downloadingProgress + (track.id to 0.01f)) }
		refreshOfflineState()

		val job = scope.launch {
			try {
				val url = subsonicManager.resolveStreamUrl(track)
					?: throw IllegalStateException("无法解析音频下载地址")
				val ext = track.fileExtension?.lowercase()?.takeIf { it.isNotBlank() } ?: "mp3"
				val safeName = "${track.id.replace(Regex("[^a-zA-Z0-9._-]"), "_")}.$ext"
				val finalPath = "$offlineDirPath/$safeName"
				val tmpPath = "$finalPath.part"

				val fm = NSFileManager.defaultManager
				if (fm.fileExistsAtPath(tmpPath)) {
					fm.removeItemAtPath(tmpPath, error = null)
				}
				fm.createFileAtPath(tmpPath, contents = null, attributes = null)
				val handle = NSFileHandle.fileHandleForWritingAtPath(tmpPath)
					?: throw IllegalStateException("无法创建本地缓存文件")

				var totalWritten = 0L
				try {
					subsonicManager.rawHttpClient.prepareGet(url).execute { response ->
						if (!response.status.isSuccess()) {
							throw IllegalStateException("下载失败 (HTTP ${response.status.value})")
						}
						val totalLen = response.contentLength() ?: 0L
						val channel = response.bodyAsChannel()
						val buffer = ByteArray(64 * 1024)
						var lastReportedProgress = 0f
						var lastReportedBytes = 0L
						while (!channel.isClosedForRead && isActive) {
							val read = channel.readAvailable(buffer, 0, buffer.size)
							if (read <= 0) break
							val slice = if (read == buffer.size) buffer else buffer.copyOf(read)
							handle.writeData(slice.toNSData())
							totalWritten += read
							val progress = if (totalLen > 0L) {
								(totalWritten.toFloat() / totalLen.toFloat()).coerceIn(0.02f, 0.99f)
							} else {
								0.5f
							}
							val shouldEmit = lastReportedBytes == 0L ||
								(progress - lastReportedProgress) >= 0.02f ||
								(totalWritten - lastReportedBytes) >= 256L * 1024L
							if (shouldEmit) {
								lastReportedProgress = progress
								lastReportedBytes = totalWritten
								downloadTasks[track.id] = IosDownloadTask(
									track = track,
									progress = progress,
									status = "downloading",
									downloadedBytes = totalWritten,
									totalBytes = totalLen,
								)
								_state.update { cur ->
									cur.copy(downloadingProgress = cur.downloadingProgress + (track.id to progress))
								}
								refreshOfflineState()
							}
						}
					}
				} finally {
					handle.closeFile()
				}

				if (!isActive) {
					if (fm.fileExistsAtPath(tmpPath)) {
						fm.removeItemAtPath(tmpPath, error = null)
					}
					downloadTasks[track.id] = IosDownloadTask(track = track, status = "cancelled")
					_state.update { it.copy(downloadingProgress = it.downloadingProgress - track.id) }
					refreshOfflineState()
					return@launch
				}

				if (fm.fileExistsAtPath(finalPath)) {
					fm.removeItemAtPath(finalPath, error = null)
				}
				fm.moveItemAtPath(tmpPath, toPath = finalPath, error = null)

				val record = IosOfflineTrackRecord(
					track = track,
					fileName = safeName,
					sizeBytes = totalWritten,
					savedAtEpochSec = NSDate().timeIntervalSince1970.roundToLong(),
				)
				val nextMap = _state.value.offlineTracks + (track.id to record)
				persistOfflineRecords(nextMap)
				downloadTasks[track.id] = IosDownloadTask(
					track = track,
					progress = 1.0f,
					status = "completed",
					downloadedBytes = totalWritten,
					totalBytes = totalWritten,
				)
				_state.update {
					it.copy(
						offlineTracks = nextMap,
						downloadingProgress = it.downloadingProgress - track.id,
						noticeMessage = "已离线缓存：${track.title}",
					)
				}
				refreshOfflineState()
			} catch (e: CancellationException) {
				val fm = NSFileManager.defaultManager
				val ext = track.fileExtension?.lowercase()?.takeIf { it.isNotBlank() } ?: "mp3"
				val safeName = "${track.id.replace(Regex("[^a-zA-Z0-9._-]"), "_")}.$ext"
				val tmpPath = "$offlineDirPath/$safeName.part"
				if (fm.fileExistsAtPath(tmpPath)) {
					fm.removeItemAtPath(tmpPath, error = null)
				}
				downloadTasks[track.id] = IosDownloadTask(track = track, status = "cancelled")
				_state.update { it.copy(downloadingProgress = it.downloadingProgress - track.id) }
				refreshOfflineState()
				throw e
			} catch (e: Throwable) {
				downloadTasks[track.id] = IosDownloadTask(
					track = track,
					status = "failed",
					error = e.message ?: "下载异常",
				)
				_state.update {
					it.copy(
						downloadingProgress = it.downloadingProgress - track.id,
						errorMessage = "离线下载失败：${e.message ?: "网络异常"}",
					)
				}
				refreshOfflineState()
			} finally {
				activeDownloadJobs.remove(track.id)
			}
		}
		activeDownloadJobs[track.id] = job
	}

	fun downloadTracksOffline(tracks: List<IosTrackItem>) {
		tracks.forEach { downloadTrackOffline(it) }
	}

	fun cancelDownload(trackId: String) {
		activeDownloadJobs[trackId]?.cancel()
		activeDownloadJobs.remove(trackId)
		val task = downloadTasks[trackId]
		if (task != null) {
			if (task.status == "downloading") {
				downloadTasks[trackId] = task.copy(status = "cancelled")
			} else if (task.status == "failed") {
				downloadTasks.remove(trackId)
			}
		}
		_state.update { it.copy(downloadingProgress = it.downloadingProgress - trackId) }
		refreshOfflineState()
	}

	fun cancelDownloads(trackIds: Collection<String>) {
		if (trackIds.isEmpty()) return
		trackIds.forEach { trackId ->
			activeDownloadJobs[trackId]?.cancel()
			activeDownloadJobs.remove(trackId)
			val task = downloadTasks[trackId]
			if (task != null) {
				if (task.status == "downloading") {
					downloadTasks[trackId] = task.copy(status = "cancelled")
				} else if (task.status == "failed") {
					downloadTasks.remove(trackId)
				}
			}
		}
		_state.update { it.copy(downloadingProgress = it.downloadingProgress - trackIds.toSet()) }
		refreshOfflineState()
	}

	fun cancelAllDownloads() {
		activeDownloadJobs.values.forEach { it.cancel() }
		activeDownloadJobs.clear()
		downloadTasks.values.filter { it.status == "downloading" }.forEach {
			downloadTasks[it.track.id] = it.copy(status = "cancelled")
		}
		_state.update { it.copy(downloadingProgress = emptyMap()) }
		refreshOfflineState()
	}

	fun retryFailedDownload(trackId: String) {
		val task = downloadTasks[trackId] ?: return
		downloadTrackOffline(task.track)
	}

	fun retryFailedDownloads(trackIds: Collection<String>) {
		val targets = trackIds.mapNotNull { id -> downloadTasks[id]?.takeIf { it.status == "failed" }?.track }
		targets.forEach { downloadTrackOffline(it) }
	}

	fun retryAllFailedDownloads() {
		val failed = downloadTasks.values.filter { it.status == "failed" }.map { it.track }
		failed.forEach { downloadTrackOffline(it) }
	}

	fun deleteOfflineTrack(trackId: String) {
		val existing = _state.value.offlineTracks[trackId] ?: return
		val fullPath = "$offlineDirPath/${existing.fileName}"
		if (NSFileManager.defaultManager.fileExistsAtPath(fullPath)) {
			NSFileManager.defaultManager.removeItemAtPath(fullPath, error = null)
		}
		val nextMap = _state.value.offlineTracks - trackId
		persistOfflineRecords(nextMap)
		downloadTasks.remove(trackId)
		_state.update {
			it.copy(
				offlineTracks = nextMap,
				noticeMessage = "已删除离线文件：${existing.track.title}",
			)
		}
		refreshOfflineState()
	}

	fun deleteOfflineTracks(trackIds: Collection<String>) {
		if (trackIds.isEmpty()) return
		val fm = NSFileManager.defaultManager
		var removedCount = 0
		val currentMap = _state.value.offlineTracks
		val idSet = trackIds.toSet()
		idSet.forEach { trackId ->
			val existing = currentMap[trackId] ?: return@forEach
			val fullPath = "$offlineDirPath/${existing.fileName}"
			if (fm.fileExistsAtPath(fullPath)) {
				fm.removeItemAtPath(fullPath, error = null)
			}
			downloadTasks.remove(trackId)
			removedCount++
		}
		if (removedCount == 0) return
		val nextMap = currentMap - idSet
		persistOfflineRecords(nextMap)
		_state.update {
			it.copy(
				offlineTracks = nextMap,
				noticeMessage = "已删除 $removedCount 首离线缓存",
			)
		}
		refreshOfflineState()
	}

	fun clearAllOfflineTracks() {
		val records = _state.value.offlineTracks.values
		val fm = NSFileManager.defaultManager
		records.forEach { rec ->
			val fullPath = "$offlineDirPath/${rec.fileName}"
			if (fm.fileExistsAtPath(fullPath)) {
				fm.removeItemAtPath(fullPath, error = null)
			}
		}
		persistOfflineRecords(emptyMap())
		downloadTasks.clear()
		_state.update {
			it.copy(
				offlineTracks = emptyMap(),
				noticeMessage = "已清空全部离线缓存",
			)
		}
		refreshOfflineState()
	}

	fun startFullLibraryOfflineDownload(tracksProvider: suspend () -> List<IosTrackItem>) {
		scope.launch {
			try {
				val tracks = tracksProvider()
				val unOffline = tracks.filter { !isTrackOffline(it.id) }
				downloadTracksOffline(unOffline)
			} catch (e: Throwable) {
				_state.update { it.copy(errorMessage = "获取全库曲目失败：${e.message}") }
			}
		}
	}

	fun totalOfflineBytes(): Long = _state.value.offlineTracks.values.sumOf { it.sizeBytes }

	fun clearMessages() {
		_state.update { it.copy(errorMessage = null, noticeMessage = null) }
	}

	private fun loadAndPlayTrackAt(index: Int) {
		val track = _state.value.queue.getOrNull(index) ?: return
		loadJob?.cancel()
		lyricsJob?.cancel()

		activeScrobbleTrackId = track.id
		activeScrobbleReported = false
		accumulatedPlayedMs = 0L
		lastObservedPositionMs = 0L
		lastNowPlayingSyncMs = 0L

		_progressFlow.value = IosPlaybackProgress(
			positionMs = 0L,
			durationMs = track.durationMs,
		)
		_state.update {
			it.copy(
				isLoading = true,
				positionMs = 0L,
				durationMs = track.durationMs,
				lyrics = IosLyricsResult(emptyList(), false),
				lyricsLoading = true,
				errorMessage = null,
			)
		}

		// Load lyrics in parallel
		lyricsJob = scope.launch {
			val fetched = subsonicManager.fetchLyrics(track)
			if (_state.value.currentTrack?.id == track.id) {
				_state.update { it.copy(lyrics = fetched, lyricsLoading = false) }
			}
		}

		// Fetch artwork for lock screen
		val coverUrl = track.coverUrl ?: subsonicManager.buildCoverUrl(track.coverArtId, size = 512)
		fetchArtworkForNowPlaying(coverUrl)

		loadJob = scope.launch {
			var bgTaskId: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid
			runOnMain {
				bgTaskId = UIApplication.sharedApplication.beginBackgroundTaskWithExpirationHandler {
					if (bgTaskId != UIBackgroundTaskInvalid) {
						UIApplication.sharedApplication.endBackgroundTask(bgTaskId)
						bgTaskId = UIBackgroundTaskInvalid
					}
				}
			}
			try {
				val nsUrl = resolvePlayableNsUrl(track)
					?: throw IllegalStateException("无法获取该曲目的播放地址")
				runOnMain {
					configureAudioSessionOnMain()
					val item = AVPlayerItem(uRL = nsUrl)
					queuePlayer.removeAllItems()
					queuePlayer.insertItem(item, afterItem = null)
					applyReplayGainAndVolumeOnMain(track)
					queuePlayer.playImmediatelyAtRate(_state.value.playbackSpeed)
					_state.update { current ->
						if (current.currentTrack?.id == track.id) {
							current.copy(isPlaying = true, isLoading = false)
						} else {
							current
						}
					}
					updateNowPlayingMetadata()
				}
				// Report now-playing to Navidrome
				subsonicManager.scrobble(track, submission = false)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				_state.update {
					it.copy(
						isLoading = false,
						isPlaying = false,
						errorMessage = "播放失败：${e.message ?: "请检查网络或服务器"}",
					)
				}
			} finally {
				if (bgTaskId != UIBackgroundTaskInvalid) {
					val idToEnd = bgTaskId
					runOnMain { UIApplication.sharedApplication.endBackgroundTask(idToEnd) }
				}
			}
		}
	}

	private suspend fun resolvePlayableNsUrl(track: IosTrackItem): NSURL? {
		val offlineRec = _state.value.offlineTracks[track.id]
		if (offlineRec != null) {
			val path = "$offlineDirPath/${offlineRec.fileName}"
			if (NSFileManager.defaultManager.fileExistsAtPath(path)) {
				return NSURL.fileURLWithPath(path)
			}
		}
		val remoteUrl = subsonicManager.resolveStreamUrl(track) ?: return null
		return NSURL.URLWithString(remoteUrl)
	}

	private fun handleItemDidPlayToEnd() {
		val current = _state.value
		val track = current.currentTrack ?: return
		if (!activeScrobbleReported && activeScrobbleTrackId == track.id) {
			activeScrobbleReported = true
			scope.launch { subsonicManager.scrobble(track, submission = true) }
		}

		if (current.sleepTimer.active && current.sleepTimer.stopAfterCurrent) {
			sleepTimerJob?.cancel()
			_state.update {
				it.copy(
					isPlaying = false,
					sleepTimer = IosSleepTimerState(),
					noticeMessage = "本首播放完毕，已停止",
				)
			}
			pauseOnMain()
			return
		}

		if (current.repeatMode == IosRepeatMode.ONE) {
			activeScrobbleReported = false
			accumulatedPlayedMs = 0L
			lastObservedPositionMs = 0L
			seekTo(0L)
			resumeOnMain()
			return
		}

		val nextIndex = when {
			current.currentIndex + 1 < current.queue.size -> current.currentIndex + 1
			current.repeatMode == IosRepeatMode.ALL && current.queue.isNotEmpty() -> 0
			else -> -1
		}
		if (nextIndex >= 0) {
			_state.update { it.copy(currentIndex = nextIndex) }
			loadAndPlayTrackAt(nextIndex)
		} else {
			val finalDur = _progressFlow.value.durationMs.coerceAtLeast(current.durationMs)
			_progressFlow.value = IosPlaybackProgress(positionMs = finalDur, durationMs = finalDur)
			_state.update { it.copy(isPlaying = false, positionMs = finalDur) }
			updateNowPlayingMetadata()
		}
	}

	private fun checkAndSubmitScrobble(durationMs: Long) {
		val track = _state.value.currentTrack ?: return
		if (activeScrobbleReported || activeScrobbleTrackId != track.id) return
		if (track.sourceType != IosTrackSource.NAVIDROME) return
		val thresholdMs = if (durationMs > 10_000L) {
			min(durationMs / 2L, 240_000L)
		} else {
			5_000L
		}
		if (accumulatedPlayedMs >= thresholdMs) {
			activeScrobbleReported = true
			scope.launch { subsonicManager.scrobble(track, submission = true) }
		}
	}

	private fun flushScrobbleOnTrackChange() {
		val track = _state.value.currentTrack ?: return
		if (!activeScrobbleReported && activeScrobbleTrackId == track.id && accumulatedPlayedMs >= 30_000L) {
			activeScrobbleReported = true
			scope.launch { subsonicManager.scrobble(track, submission = true) }
		}
	}

	private fun togglePlayPauseOnMain() {
		if (_state.value.isPlaying) {
			pauseOnMain()
		} else {
			resumeOnMain()
		}
	}

	private fun pauseOnMain() {
		queuePlayer.pause()
		_state.update { it.copy(isPlaying = false) }
		updateNowPlayingMetadata()
	}

	private fun resumeOnMain() {
		if (queuePlayer.currentItem == null && _state.value.currentTrack != null) {
			loadAndPlayTrackAt(_state.value.currentIndex)
			return
		}
		configureAudioSessionOnMain()
		queuePlayer.playImmediatelyAtRate(_state.value.playbackSpeed)
		_state.update { it.copy(isPlaying = true) }
		updateNowPlayingMetadata()
	}

	private fun applyReplayGainAndVolume() {
		val track = _state.value.currentTrack
		runOnMain { applyReplayGainAndVolumeOnMain(track) }
	}

	private fun applyReplayGainAndVolumeOnMain(track: IosTrackItem?) {
		val userVolume = _state.value.volume
		val prefs = subsonicManager.prefs.value
		val gainDb = when (prefs.replayGainMode) {
			ReplayGainMode.OFF -> null
			ReplayGainMode.ALBUM -> track?.albumGainDb ?: track?.trackGainDb ?: track?.fallbackGainDb
			ReplayGainMode.TRACK -> track?.trackGainDb ?: track?.albumGainDb ?: track?.fallbackGainDb
		}
		val linear = if (prefs.replayGainMode != ReplayGainMode.OFF && gainDb != null) {
			val effectiveDb = gainDb + prefs.replayGainPreampDb.coerceIn(-6f, 12f)
			10.0f.pow(effectiveDb / 20.0f)
		} else {
			1.0f
		}
		queuePlayer.volume = (userVolume * linear).coerceIn(0f, 1f)
	}

	private fun fetchArtworkForNowPlaying(coverUrl: String?) {
		if (coverUrl.isNullOrBlank()) {
			currentArtworkUrl = null
			currentArtworkImage = null
			runOnMain { updateNowPlayingMetadata() }
			return
		}
		currentArtworkUrl = coverUrl
		artworkCache[coverUrl]?.let { cached ->
			currentArtworkImage = cached
			runOnMain { updateNowPlayingMetadata() }
			return
		}
		scope.launch {
			val bytes = runCatching {
				subsonicManager.rawHttpClient.prepareGet(coverUrl).execute { resp ->
					if (!resp.status.isSuccess()) return@execute null
					val channel = resp.bodyAsChannel()
					val chunks = mutableListOf<ByteArray>()
					val buf = ByteArray(16 * 1024)
					var total = 0
					while (!channel.isClosedForRead && total < 4 * 1024 * 1024) {
						val read = channel.readAvailable(buf, 0, buf.size)
						if (read <= 0) break
						chunks.add(buf.copyOf(read))
						total += read
					}
					val merged = ByteArray(total)
					var offset = 0
					chunks.forEach { chunk ->
						chunk.copyInto(merged, offset)
						offset += chunk.size
					}
					merged
				}
			}.getOrNull() ?: return@launch

			val uiImage = UIImage.imageWithData(bytes.toNSData()) ?: return@launch
			if (artworkCache.size >= 32) {
				artworkCache.clear()
			}
			artworkCache[coverUrl] = uiImage
			if (currentArtworkUrl == coverUrl) {
				currentArtworkImage = uiImage
				runOnMain { updateNowPlayingMetadata() }
			}
		}
	}

	private fun updateNowPlayingMetadata() {
		val current = _state.value
		val track = current.currentTrack ?: return
		val progress = _progressFlow.value
		val resolvedDurationMs = progress.durationMs.coerceAtLeast(current.durationMs)
		val info = mutableMapOf<Any?, Any?>()
		info[MPMediaItemPropertyTitle] = track.title
		info[MPMediaItemPropertyArtist] = track.artist
		info[MPMediaItemPropertyAlbumTitle] = track.album
		info[MPMediaItemPropertyPlaybackDuration] = (resolvedDurationMs / 1000.0).coerceAtLeast(0.0)
		info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = (progress.positionMs / 1000.0).coerceAtLeast(0.0)
		info[MPNowPlayingInfoPropertyPlaybackRate] = if (current.isPlaying) current.playbackSpeed.toDouble() else 0.0
		currentArtworkImage?.let { img ->
			info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize = img.size) { _ -> img }
		}
		MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = info
	}

	private fun loadOfflineRecords(): Map<String, IosOfflineTrackRecord> {
		val raw = settings.getString(KEY_OFFLINE_RECORDS, "")
		if (raw.isBlank()) return emptyMap()
		return runCatching {
			json.decodeFromString<List<IosOfflineTrackRecord>>(raw).associateBy { it.track.id }
		}.getOrDefault(emptyMap())
	}

	private fun persistOfflineRecords(map: Map<String, IosOfflineTrackRecord>) {
		runCatching {
			settings.putString(KEY_OFFLINE_RECORDS, json.encodeToString(map.values.toList()))
		}
	}

	private fun runOnMain(block: () -> Unit) {
		dispatch_async(dispatch_get_main_queue(), block)
	}

	private fun ByteArray.toNSData(): NSData {
		if (isEmpty()) return NSData()
		return usePinned { pinned ->
			NSData.dataWithBytes(pinned.addressOf(0), size.toULong())
		}
	}

	companion object {
		private const val KEY_OFFLINE_RECORDS = "ios.offline_tracks_manifest_v1"
	}
}
