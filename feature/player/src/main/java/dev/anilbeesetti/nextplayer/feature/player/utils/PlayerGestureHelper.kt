package dev.anilbeesetti.nextplayer.feature.player.utils

import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.anilbeesetti.nextplayer.core.common.Utils
import dev.anilbeesetti.nextplayer.core.common.extensions.dpToPx
import dev.anilbeesetti.nextplayer.core.model.DoubleTapGesture
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences
import dev.anilbeesetti.nextplayer.core.ui.R as coreUiR
import dev.anilbeesetti.nextplayer.feature.player.PlayerActivity
import dev.anilbeesetti.nextplayer.feature.player.PlayerViewModel
import dev.anilbeesetti.nextplayer.feature.player.R
import dev.anilbeesetti.nextplayer.feature.player.extensions.seekBack
import dev.anilbeesetti.nextplayer.feature.player.extensions.seekForward
import dev.anilbeesetti.nextplayer.feature.player.extensions.shouldFastSeek
import dev.anilbeesetti.nextplayer.feature.player.extensions.togglePlayPause
import kotlin.math.abs
import kotlin.math.roundToInt

@UnstableApi
@SuppressLint("ClickableViewAccessibility")
class PlayerGestureHelper(
    private val viewModel: PlayerViewModel,
    private val activity: PlayerActivity,
    private val volumeManager: VolumeManager,
    private val brightnessManager: BrightnessManager,
    private val onScaleChanged: (Float) -> Unit,
) {
    private val prefs: PlayerPreferences
        get() = viewModel.playerPrefs.value

    private val playerView: PlayerView
        get() = activity.binding.playerView

    private val shouldFastSeek: Boolean
        get() = playerView.player?.duration?.let { prefs.shouldFastSeek(it) } == true

    private var exoContentFrameLayout: AspectRatioFrameLayout = playerView.findViewById(R.id.exo_content_frame)

    private var currentGestureAction: GestureAction? = null
    private var seekStart = 0L
    private var position = 0L
    private var seekChange = 0L
    private var pointerCount = 1
    private var isPlayingOnSeekStart: Boolean = false
    private var currentPlaybackSpeed: Float? = null
    private var isLongPressActive: Boolean = false

    private val tapGestureDetector = GestureDetector(
        playerView.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                if (currentGestureAction == null) {
                    with(playerView) {
                        if (!isControllerFullyVisible) showController() else hideController()
                    }
                    return true
                }
                return false
            }

            override fun onLongPress(e: MotionEvent) {
                if (!prefs.useLongPressControls) return
                if (playerView.player?.isPlaying == false) return
                if (activity.isControlsLocked) return

                if (currentGestureAction == null) {
                    currentGestureAction = GestureAction.FAST_PLAYBACK
                    isLongPressActive = true
                    currentPlaybackSpeed = playerView.player?.playbackParameters?.speed
                    playerView.hideController()
                }
            }

            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (activity.isControlsLocked) return false
                if (currentGestureAction != null) return false

                playerView.player?.run {
                    when (prefs.doubleTapGesture) {
                        DoubleTapGesture.FAST_FORWARD_AND_REWIND -> {
                            val viewCenterX = playerView.measuredWidth / 2
                            if (event.x.toInt() < viewCenterX) {
                                val newPosition = currentPosition - prefs.seekIncrement.toMillis
                                seekBack(newPosition.coerceAtLeast(0), shouldFastSeek)
                            } else {
                                val newPosition = currentPosition + prefs.seekIncrement.toMillis
                                seekForward(newPosition.coerceAtMost(duration), shouldFastSeek)
                            }
                        }
                        DoubleTapGesture.BOTH -> {
                            val eventPositionX = event.x / playerView.measuredWidth
                            if (eventPositionX < 0.35) {
                                val newPosition = currentPosition - prefs.seekIncrement.toMillis
                                seekBack(newPosition.coerceAtLeast(0), shouldFastSeek)
                            } else if (eventPositionX > 0.65) {
                                val newPosition = currentPosition + prefs.seekIncrement.toMillis
                                seekForward(newPosition.coerceAtMost(duration), shouldFastSeek)
                            } else {
                                playerView.togglePlayPause()
                            }
                        }
                        DoubleTapGesture.PLAY_PAUSE -> playerView.togglePlayPause()
                        DoubleTapGesture.NONE -> return false
                    }
                } ?: return false
                return true
            }
        },
    )

    private val SEEK_GESTURE_THRESHOLD_PX = 95f

    private val seekGestureDetector = GestureDetector(
        playerView.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                firstEvent: MotionEvent?,
                currentEvent: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (firstEvent == null) return false
                if (inExclusionArea(firstEvent)) return false
                if (!prefs.useSeekControls) return false
                if (activity.isControlsLocked) return false
                if (!activity.isMediaItemReady) return false
                // This check is crucial: only handle horizontal scrolls.
                if (abs(distanceX) < abs(distanceY)) return false

                // If another gesture is active, don't interfere.
                if (currentGestureAction != null && currentGestureAction != GestureAction.SEEK) return false

                if (currentGestureAction == null) {
                    val horizontalScrollDistance = abs(currentEvent.x - firstEvent.x)
                    if (horizontalScrollDistance < SEEK_GESTURE_THRESHOLD_PX) {
                        return false
                    }
                    // This is the first valid seek event. Initialize the gesture.
                    currentGestureAction = GestureAction.SEEK
                    seekChange = 0L
                    seekStart = playerView.player?.currentPosition ?: 0L
                    playerView.controllerAutoShow = playerView.isControllerFullyVisible
                    if (playerView.player?.isPlaying == true) {
                        playerView.player?.pause()
                        isPlayingOnSeekStart = true
                    }
                }

                // Continue the seek gesture
                val distanceDiff = abs(Utils.pxToDp(distanceX) / 4).coerceIn(0.5f, 10f)
                val change = (distanceDiff * SEEK_STEP_MS).toLong()

                playerView.player?.run {
                    if (distanceX < 0L) {
                        seekChange = (seekChange + change)
                            .takeIf { it + seekStart < duration } ?: (duration - seekStart)
                        position = (seekStart + seekChange).coerceAtMost(duration)
                        seekForward(positionMs = position, shouldFastSeek = shouldFastSeek)
                    } else {
                        seekChange = (seekChange - change)
                            .takeIf { it + seekStart > 0 } ?: (0 - seekStart)
                        position = seekStart + seekChange
                        seekBack(positionMs = position, shouldFastSeek = shouldFastSeek)
                    }
                    activity.showPlayerInfo(
                        info = Utils.formatDurationMillis(this.currentPosition),
                        subInfo = "[${Utils.formatDurationMillisSign(seekChange)}]",
                    )
                    return true
                }
                return false
            }
        },
    )

    private val volumeAndBrightnessGestureDetector = GestureDetector(
        playerView.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                firstEvent: MotionEvent?,
                currentEvent: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (firstEvent == null) return false
                if (inExclusionArea(firstEvent)) return false
                if (!prefs.useSwipeControls) return false
                if (activity.isControlsLocked) return false
                if (abs(distanceY) < abs(distanceX)) return false

                // If another gesture is active, don't interfere.
                if (currentGestureAction != null && currentGestureAction != GestureAction.SWIPE) return false

                if (currentGestureAction == null) {
                    currentGestureAction = GestureAction.SWIPE
                }

                val viewCenterX = playerView.measuredWidth / 2
                val distanceFull = playerView.measuredHeight * FULL_SWIPE_RANGE_SCREEN_RATIO
                val ratioChange = distanceY / distanceFull

                if (firstEvent.x.toInt() > viewCenterX) {
                    val change = ratioChange * volumeManager.maxStreamVolume
                    volumeManager.setVolume(volumeManager.currentVolume + change, prefs.showSystemVolumePanel)
                    activity.showVolumeGestureLayout()
                } else {
                    val change = ratioChange * brightnessManager.maxBrightness
                    brightnessManager.setBrightness(brightnessManager.currentBrightness + change)
                    activity.showBrightnessGestureLayout()
                }
                return true
            }
        },
    )

    private val zoomGestureDetector = ScaleGestureDetector(
        playerView.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private val SCALE_RANGE = 0.25f..4.0f

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                if (!prefs.useZoomControls) return false
                if (activity.isControlsLocked) return false
                if (currentGestureAction != null && currentGestureAction != GestureAction.ZOOM) {
                    return false
                }
                currentGestureAction = GestureAction.ZOOM
                releaseGestures()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (currentGestureAction != GestureAction.ZOOM) return false

                playerView.player?.videoSize?.let { videoSize ->
                    val scaleFactor = (exoContentFrameLayout.scaleX * detector.scaleFactor)
                    val updatedVideoScale = (exoContentFrameLayout.width * scaleFactor) / videoSize.width.toFloat()
                    if (updatedVideoScale in SCALE_RANGE) {
                        exoContentFrameLayout.scaleX = scaleFactor
                        exoContentFrameLayout.scaleY = scaleFactor
                        onScaleChanged(scaleFactor)
                    }
                    val currentVideoScale = (exoContentFrameLayout.width * exoContentFrameLayout.scaleX) / videoSize.width.toFloat()
                    activity.showPlayerInfo("${(currentVideoScale * 100).roundToInt()}%")
                }
                return true
            }
        },
    )

    private fun releaseGestures() {
        activity.hideVolumeGestureLayout()
        activity.hideBrightnessGestureLayout()
        activity.hidePlayerInfo(0L)
        activity.hideTopInfo()

        if (currentGestureAction == GestureAction.FAST_PLAYBACK) {
            playerView.player?.setPlaybackSpeed(currentPlaybackSpeed ?: 1.0f)
        }
        currentPlaybackSpeed = null

        playerView.controllerAutoShow = true
        if (isPlayingOnSeekStart) playerView.player?.play()
        isPlayingOnSeekStart = false
        currentGestureAction = null
        isLongPressActive = false
    }
    
    private fun inExclusionArea(firstEvent: MotionEvent): Boolean {
        val gestureExclusionBorder = playerView.context.dpToPx(GESTURE_EXCLUSION_AREA)
        return firstEvent.y < gestureExclusionBorder || firstEvent.y > playerView.height - gestureExclusionBorder ||
                firstEvent.x < gestureExclusionBorder || firstEvent.x > playerView.width - gestureExclusionBorder
    }

    init {
        playerView.setOnTouchListener { _, motionEvent ->
            pointerCount = motionEvent.pointerCount

            // 1. Release all gestures when the final finger is lifted or the gesture is canceled.
            when (motionEvent.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    releaseGestures()
                    return@setOnTouchListener true
                }
            }

            // 2. Prioritize long press for fast playback. Once active, it controls everything.
            if (isLongPressActive && currentGestureAction == GestureAction.FAST_PLAYBACK) {
                // Allow multi-finger speed changes during an active long press
                val baseSpeed = currentPlaybackSpeed ?: 1.0f
                val targetSpeed = when (pointerCount) {
                    1 -> (baseSpeed + prefs.longPressControlsSpeed) / 2f
                    2 -> prefs.longPressControlsSpeed
                    3 -> prefs.longPressControlsSpeed + 0.2f
                    4 -> prefs.longPressControlsSpeed + 0.4f
                    else -> prefs.longPressControlsSpeed + 0.6f
                }
                activity.showTopInfo(activity.getString(coreUiR.string.fast_playback_speed, targetSpeed))
                playerView.player?.setPlaybackSpeed(targetSpeed)
                return@setOnTouchListener true // Consume event, no other gestures can run
            }

            // 3. Handle multi-finger zoom.
            if (pointerCount > 1) {
                zoomGestureDetector.onTouchEvent(motionEvent)
                return@setOnTouchListener true // Consume event, don't allow single-finger gestures
            }

            // 4. Handle all single-finger gestures. Let the detectors decide who should act.
            // First, let the tap detector check for single-tap, double-tap, or the start of a long-press.
            tapGestureDetector.onTouchEvent(motionEvent)

            // Then, let the scroll detectors check for swipe or seek.
            // Since their onScroll methods now correctly check for scroll direction,
            // only one of them will actually execute its logic.
            if (!volumeAndBrightnessGestureDetector.onTouchEvent(motionEvent)) {
                seekGestureDetector.onTouchEvent(motionEvent)
            }

            true
        }
    }

    companion object {
        const val FULL_SWIPE_RANGE_SCREEN_RATIO = 0.66f
        const val GESTURE_EXCLUSION_AREA = 20f
        const val SEEK_STEP_MS = 1000L
    }
}

inline val Int.toMillis get() = this * 1000

enum class GestureAction {
    SWIPE,
    SEEK,
    ZOOM,
    FAST_PLAYBACK,
}
