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
    private var pointerCount = 1 // Keep this to track current active fingers
    private var isPlayingOnSeekStart: Boolean = false
    private var currentPlaybackSpeed: Float? = null // Stored initial speed for fast playback reset

    // NEW: Flag to track if a long press has *begun*
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

                // Only start long press fast playback if no other continuous gesture is active
                if (currentGestureAction == null) {
                    currentGestureAction = GestureAction.FAST_PLAYBACK
                    isLongPressActive = true // Mark long press as active
                    currentPlaybackSpeed = playerView.player?.playbackParameters?.speed // Capture original speed
                    playerView.hideController() // Hide controls immediately on long press start
                }
                // If another gesture type is currently active, don't interfere with the long press.
                if (currentGestureAction != GestureAction.FAST_PLAYBACK) return

                // No speed application here in onLongPress.
                // Speed will be applied in ACTION_MOVE within the onTouchListener.
                // This `onLongPress` primarily signals the *start* of the fast playback gesture.
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
                if (abs(distanceX / distanceY) < 2) return false

                if (currentGestureAction != null && currentGestureAction != GestureAction.SEEK) return false
                if (pointerCount != 1) return false

                if (currentGestureAction == null) {
                    val horizontalScrollDistance = abs(currentEvent.x - firstEvent.x)
                    if (horizontalScrollDistance < SEEK_GESTURE_THRESHOLD_PX) {
                        return false
                    }
                    seekChange = 0L
                    seekStart = playerView.player?.currentPosition ?: 0L
                    playerView.controllerAutoShow = playerView.isControllerFullyVisible
                    if (playerView.player?.isPlaying == true) {
                        playerView.player?.pause()
                        isPlayingOnSeekStart = true
                    }
                    currentGestureAction = GestureAction.SEEK
                }

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
                if (abs(distanceY / distanceX) < 2) return false

                if (currentGestureAction != null && currentGestureAction != GestureAction.SWIPE) return false
                if (pointerCount != 1) return false

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
                releaseGestures() // Release any other UI
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

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                // Handled by ACTION_UP/POINTER_UP
            }
        },
    )

    private fun releaseGestures() {
        // hide all gesture-related UI
        activity.hideVolumeGestureLayout()
        activity.hideBrightnessGestureLayout()
        activity.hidePlayerInfo(0L)
        activity.hideTopInfo()

        // Only revert playback speed if FAST_PLAYBACK was active
        if (currentGestureAction == GestureAction.FAST_PLAYBACK) {
            playerView.player?.setPlaybackSpeed(currentPlaybackSpeed ?: 1.0f) // Revert to original or 1.0f
        }
        currentPlaybackSpeed = null // Clear stored speed

        playerView.controllerAutoShow = true
        if (isPlayingOnSeekStart) playerView.player?.play()
        isPlayingOnSeekStart = false
        currentGestureAction = null // KEY: Reset the active gesture
        isLongPressActive = false // NEW: Reset long press flag
    }

    /**
     * Check if [firstEvent] is in the gesture exclusion area
     */
    private fun inExclusionArea(firstEvent: MotionEvent): Boolean {
        val gestureExclusionBorder = playerView.context.dpToPx(GESTURE_EXCLUSION_AREA)
        return firstEvent.y < gestureExclusionBorder || firstEvent.y > playerView.height - gestureExclusionBorder ||
            firstEvent.x < gestureExclusionBorder || firstEvent.x > playerView.width - gestureExclusionBorder
    }

    init {
        playerView.setOnTouchListener { _, motionEvent ->
            pointerCount = motionEvent.pointerCount // Always update pointerCount first

            // 1. Process ACTION_DOWN for initial state reset
            if (motionEvent.actionMasked == MotionEvent.ACTION_DOWN) {
                releaseGestures() // Start a new touch sequence with a clean slate
            }

            // 2. Pass event to tapGestureDetector (for onLongPress, onDoubleTap, onSingleTapConfirmed)
            // It will set `isLongPressActive` and `currentGestureAction` to FAST_PLAYBACK if long press starts.
            tapGestureDetector.onTouchEvent(motionEvent)

            // **FIXED**: This block now only runs on ACTION_MOVE, preventing it from hijacking ACTION_UP.
            if (isLongPressActive && currentGestureAction == GestureAction.FAST_PLAYBACK && motionEvent.actionMasked == MotionEvent.ACTION_MOVE) {
                // Ensure pointerCount is valid for fast playback (1 or 2)
                if (pointerCount < 1) {
                    releaseGestures() // Cancel fast playback if no fingers
                    return@setOnTouchListener true
                }

                // Dynamically update playback speed based on current pointerCount
                val baseSpeed = currentPlaybackSpeed ?: playerView.player?.playbackParameters?.speed ?: 1.0f
                val targetSpeed = when (pointerCount) {
                    1 -> (baseSpeed + prefs.longPressControlsSpeed) / 2f
                    2 -> prefs.longPressControlsSpeed
                    3 -> prefs.longPressControlsSpeed + 0.25f
                    4 -> prefs.longPressControlsSpeed + 0.5f
                    else -> prefs.longPressControlsSpeed + 0.7f
                }

                activity.showTopInfo(activity.getString(coreUiR.string.fast_playback_speed, targetSpeed))
                playerView.player?.setPlaybackSpeed(targetSpeed)

                // Consume the event. No other detectors should run while long press fast playback is active.
                return@setOnTouchListener true
            }


            // 3. Process Zoom if no other gesture is active
            // This will claim currentGestureAction if zoom starts.
            val didZoomGestureConsume = zoomGestureDetector.onTouchEvent(motionEvent)
            if (didZoomGestureConsume && currentGestureAction == GestureAction.ZOOM) {
                return@setOnTouchListener true
            }


            // 4. Handle other continuous gestures (seek, volume/brightness)
            // only if no other gesture is active and it's a 1-finger gesture.
            if (currentGestureAction == null && pointerCount == 1) {
                val didSwipeGestureConsume = volumeAndBrightnessGestureDetector.onTouchEvent(motionEvent)
                if (!didSwipeGestureConsume) {
                    seekGestureDetector.onTouchEvent(motionEvent)
                }
            }


            // 5. Handle pointer up/cancel events to release gestures
            when (motionEvent.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    releaseGestures() // Last finger lifted or gesture canceled.
                    return@setOnTouchListener true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // A finger was lifted, but others remain.
                    // If zoom was active and now only 1 finger remains, release zoom.
                    if (currentGestureAction == GestureAction.ZOOM && pointerCount == 1) {
                        releaseGestures()
                        return@setOnTouchListener true // Consume, as zoom gesture is effectively over
                    }
                    // For other cases, let the ACTION_MOVE continue to determine if a new 1-finger gesture should start.
                }
            }

            true // Always return true to indicate the event was handled.
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
