package dev.anilbeesetti.nextplayer.feature.player

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Rational
import android.util.TypedValue
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.view.WindowManager
import android.view.accessibility.CaptioningManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.viewModels
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.TimeBar
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import dev.anilbeesetti.nextplayer.core.common.Utils
import dev.anilbeesetti.nextplayer.core.common.extensions.getMediaContentUri
import dev.anilbeesetti.nextplayer.core.common.extensions.isDeviceTvBox
import dev.anilbeesetti.nextplayer.core.model.ControlButtonsPosition
import dev.anilbeesetti.nextplayer.core.model.LoopMode
import dev.anilbeesetti.nextplayer.core.model.ThemeConfig
import dev.anilbeesetti.nextplayer.core.model.VideoZoom
import dev.anilbeesetti.nextplayer.core.ui.R as coreUiR
import dev.anilbeesetti.nextplayer.feature.player.databinding.ActivityPlayerBinding
import dev.anilbeesetti.nextplayer.feature.player.dialogs.PlaybackSpeedControlsDialogFragment
import dev.anilbeesetti.nextplayer.feature.player.dialogs.TrackSelectionDialogFragment
import dev.anilbeesetti.nextplayer.feature.player.dialogs.VideoZoomOptionsDialogFragment
import dev.anilbeesetti.nextplayer.feature.player.dialogs.nameRes
import dev.anilbeesetti.nextplayer.feature.player.extensions.isPortrait
import dev.anilbeesetti.nextplayer.feature.player.extensions.next
import dev.anilbeesetti.nextplayer.feature.player.extensions.seekBack
import dev.anilbeesetti.nextplayer.feature.player.extensions.seekForward
import dev.anilbeesetti.nextplayer.feature.player.extensions.setImageDrawable
import dev.anilbeesetti.nextplayer.feature.player.extensions.shouldFastSeek
import dev.anilbeesetti.nextplayer.feature.player.extensions.toActivityOrientation
import dev.anilbeesetti.nextplayer.feature.player.extensions.toTypeface
import dev.anilbeesetti.nextplayer.feature.player.extensions.togglePlayPause
import dev.anilbeesetti.nextplayer.feature.player.extensions.toggleSystemBars
import dev.anilbeesetti.nextplayer.feature.player.extensions.uriToSubtitleConfiguration
import dev.anilbeesetti.nextplayer.feature.player.service.PlayerService
import dev.anilbeesetti.nextplayer.feature.player.service.addSubtitleTrack
import dev.anilbeesetti.nextplayer.feature.player.service.getAudioSessionId
import dev.anilbeesetti.nextplayer.feature.player.service.getSkipSilenceEnabled
import dev.anilbeesetti.nextplayer.feature.player.service.stopPlayerSession
import dev.anilbeesetti.nextplayer.feature.player.service.switchAudioTrack
import dev.anilbeesetti.nextplayer.feature.player.service.switchSubtitleTrack
import dev.anilbeesetti.nextplayer.feature.player.utils.BrightnessManager
import dev.anilbeesetti.nextplayer.feature.player.utils.PlayerApi
import dev.anilbeesetti.nextplayer.feature.player.utils.PlayerGestureHelper
import dev.anilbeesetti.nextplayer.feature.player.utils.VolumeManager
import dev.anilbeesetti.nextplayer.feature.player.utils.toMillis
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@SuppressLint("UnsafeOptInUsageError")
@AndroidEntryPoint
class PlayerActivity : AppCompatActivity() {

    lateinit var binding: ActivityPlayerBinding

    private val viewModel: PlayerViewModel by viewModels()
    private val applicationPreferences get() = viewModel.appPrefs.value
    private val playerPreferences get() = viewModel.playerPrefs.value

    private var isPlaybackFinished = false

    var isMediaItemReady = false
    var isControlsLocked = false
    private var isFrameRendered = false
    private var isPlayingOnScrubStart: Boolean = false
    private var previousScrubPosition = 0L
    private var scrubStartPosition: Long = -1L
    private var currentOrientation: Int? = null
    private var hideVolumeIndicatorJob: Job? = null
    private var hideBrightnessIndicatorJob: Job? = null
    private var hideInfoLayoutJob: Job? = null
    
    private lateinit var thinProgress: View
    private lateinit var finishTimeText: TextView
    private var finishTimeMillis: Long? = null
    private var progressUpdateJob: Job? = null

    private var originalPlaybackSpeed: Float = 1.0f
    private var isFastPlaybackFromKeyboardActive: Boolean = false

    private var playInBackground: Boolean = false
    private var isIntentNew: Boolean = true

    private var isPipActive: Boolean = false

    private val shouldFastSeek: Boolean
        get() = playerPreferences.shouldFastSeek(mediaController?.duration ?: C.TIME_UNSET)

    private lateinit var remainingTimeText: TextView

    private var lastPressedNumber: Int = 0
    private val numpadKeyHistory = mutableListOf<Int>()
    private var targetSpeed: Float = 1.0f
    private var fastPlaybackLockActive: Boolean = false
    private var fastPlaybackLockedSpeed: Float = 1.0f
    private var fastPlaybackLockedKey: Int? = null

    private var hideTopInfoJob: Job? = null

    /**
     * Player
     */
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private lateinit var playerGestureHelper: PlayerGestureHelper
    private lateinit var playerApi: PlayerApi
    private lateinit var volumeManager: VolumeManager
    private lateinit var brightnessManager: BrightnessManager
    private var pipBroadcastReceiver: BroadcastReceiver? = null

    /**
     * Listeners
     */
    private val playbackStateListener: Player.Listener = playbackStateListener()
    private var subtitleFileLauncherLaunchedForMediaItem: MediaItem? = null

    private val subtitleFileLauncher = registerForActivityResult(OpenDocument()) { uri ->
        if (uri != null && subtitleFileLauncherLaunchedForMediaItem != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            lifecycleScope.launch {
                maybeInitControllerFuture()
                controllerFuture?.await()?.addSubtitleTrack(uri)
            }
        }
    }

    /**
     * Player controller views
     */
    private lateinit var audioTrackButton: ImageButton
    private lateinit var backButton: ImageButton
    private lateinit var exoContentFrameLayout: AspectRatioFrameLayout
    private lateinit var lockControlsButton: ImageButton
    private lateinit var playbackSpeedButton: ImageButton
    private lateinit var playerLockControls: FrameLayout
    private lateinit var playerUnlockControls: FrameLayout
    private lateinit var playerCenterControls: LinearLayout
    private lateinit var screenRotateButton: ImageButton
    private lateinit var pipButton: ImageButton
    private lateinit var seekBar: TimeBar
    private lateinit var subtitleTrackButton: ImageButton
    private lateinit var unlockControlsButton: ImageButton
    private lateinit var videoTitleTextView: TextView
    private lateinit var videoZoomButton: ImageButton
    private lateinit var playInBackgroundButton: ImageButton
    private lateinit var loopModeButton: ImageButton
    private lateinit var extraControls: LinearLayout

    private val isPipSupported: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private val isPipEnabled: Boolean
        get() {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager?
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps?.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
                } else {
                    @Suppress("DEPRECATION")
                    appOps?.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
                }
            } else {
                false
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppCompatDelegate.setDefaultNightMode(
            when (applicationPreferences.themeConfig) {
                ThemeConfig.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                ThemeConfig.OFF -> AppCompatDelegate.MODE_NIGHT_NO
                ThemeConfig.ON -> AppCompatDelegate.MODE_NIGHT_YES
            },
        )

        if (applicationPreferences.useDynamicColors) {
            DynamicColors.applyToActivityIfAvailable(this)
        }

        // The window is always allowed to extend into the DisplayCutout areas on the short edges of the screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initializing views
        audioTrackButton = binding.playerView.findViewById(R.id.btn_audio_track)
        backButton = binding.playerView.findViewById(R.id.back_button)
        exoContentFrameLayout = binding.playerView.findViewById(R.id.exo_content_frame)
        lockControlsButton = binding.playerView.findViewById(R.id.btn_lock_controls)
        playbackSpeedButton = binding.playerView.findViewById(R.id.btn_playback_speed)
        playerLockControls = binding.playerView.findViewById(R.id.player_lock_controls)
        playerUnlockControls = binding.playerView.findViewById(R.id.player_unlock_controls)
        playerCenterControls = binding.playerView.findViewById(R.id.player_center_controls)
        screenRotateButton = binding.playerView.findViewById(R.id.screen_rotate)
        pipButton = binding.playerView.findViewById(R.id.btn_pip)
        seekBar = binding.playerView.findViewById(R.id.exo_progress)
        subtitleTrackButton = binding.playerView.findViewById(R.id.btn_subtitle_track)
        unlockControlsButton = binding.playerView.findViewById(R.id.btn_unlock_controls)
        videoTitleTextView = binding.playerView.findViewById(R.id.video_name)
        videoZoomButton = binding.playerView.findViewById(R.id.btn_video_zoom)
        playInBackgroundButton = binding.playerView.findViewById(R.id.btn_background)
        loopModeButton = binding.playerView.findViewById(R.id.btn_loop_mode)
        extraControls = binding.playerView.findViewById(R.id.extra_controls)

        thinProgress = binding.thinProgress
        
        // Adjust progress bar thickness based on screen density
        val density = resources.displayMetrics.density
        val heightInDp = when {
            density <= 2.0f -> 2f
//            density <= 1.5f -> 2f // For medium-resolution screens (hdpi)
            else -> 1.2f
        }
        thinProgress.layoutParams.height = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            heightInDp,
            resources.displayMetrics
        ).toInt()
        
        if (playerPreferences.controlButtonsPosition == ControlButtonsPosition.RIGHT) {
            extraControls.gravity = Gravity.END
        }

        if (!isPipSupported) {
            pipButton.visibility = View.GONE
        }

        seekBar.addListener(
            object : TimeBar.OnScrubListener {
                override fun onScrubStart(timeBar: TimeBar, position: Long) {
                    mediaController?.run {
                        if (isPlaying) {
                            isPlayingOnScrubStart = true
                            pause()
                        }
                        isFrameRendered = true
                        scrubStartPosition = currentPosition
                        previousScrubPosition = currentPosition
                        scrub(position)
                        showPlayerInfo(
                            info = Utils.formatDurationMillis(position),
                            subInfo = "[${Utils.formatDurationMillisSign(position - scrubStartPosition)}]",
                        )
                    }
                    // Add this line
                    mediaController?.duration?.let { updateThinProgressBar(position, it) }
                }

                override fun onScrubMove(timeBar: TimeBar, position: Long) {
                    scrub(position)
                    showPlayerInfo(
                        info = Utils.formatDurationMillis(position),
                        subInfo = "[${Utils.formatDurationMillisSign(position - scrubStartPosition)}]",
                    )
                    // Add this line
                    mediaController?.duration?.let { updateThinProgressBar(position, it) }
                }

                override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                    hidePlayerInfo(0L)
                    scrubStartPosition = -1L
                    if (isPlayingOnScrubStart) {
                        mediaController?.play()
                    } else {
                        // Manually update one last time if player is paused
                        mediaController?.duration?.let { updateThinProgressBar(position, it) }
                    }
                }
            },
        )

        volumeManager = VolumeManager(audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        brightnessManager = BrightnessManager(activity = this)
        playerGestureHelper = PlayerGestureHelper(
            viewModel = viewModel,
            activity = this,
            volumeManager = volumeManager,
            brightnessManager = brightnessManager,
            onScaleChanged = { scale ->
                mediaController?.currentMediaItem?.mediaId?.let {
                    viewModel.updateMediumZoom(uri = it, zoom = scale)
                }
            },
        )

        playerApi = PlayerApi(this)

        onBackPressedDispatcher.addCallback {
            mediaController?.run {
                clearMediaItems()
                stop()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (playerPreferences.rememberPlayerBrightness) {
            brightnessManager.setBrightness(playerPreferences.playerBrightness)
        }
        lifecycleScope.launch {
            maybeInitControllerFuture()
            mediaController = controllerFuture?.await()

            setOrientation()
            applyVideoZoom(videoZoom = playerPreferences.playerVideoZoom)
            mediaController?.currentMediaItem?.mediaId?.let {
                applyVideoScale(videoScale = viewModel.getVideoState(it)?.videoScale ?: 1f)
            }

            mediaController?.run {
                binding.playerView.player = this
                isMediaItemReady = currentMediaItem != null
                toggleSystemBars(showBars = binding.playerView.isControllerFullyVisible)
                videoTitleTextView.text = currentMediaItem?.mediaMetadata?.title
                applyLoopMode(playerPreferences.loopMode)
                if (playerPreferences.shouldUseVolumeBoost) {
                    try {
                        volumeManager.loudnessEnhancer = LoudnessEnhancer(getAudioSessionId())
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                updateKeepScreenOnFlag()
                addListener(playbackStateListener)
                startPlayback()
            }
            subtitleFileLauncherLaunchedForMediaItem = null
        }
        initializePlayerView()
        
        //Remaining Time Text
        finishTimeText = findViewById(R.id.finish_time_text)
        remainingTimeText = findViewById(R.id.remaining_time_text)

        val displayMetrics = DisplayMetrics()
        (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.getMetrics(displayMetrics)

        //Convert screen width to centimeters
        val screenWidthInches = displayMetrics.widthPixels / displayMetrics.xdpi
        val screenWidthCm = screenWidthInches * 2.54f
                
        // Convert the target size from centimeters to millimeters for TypedValue
        val scaledTextSizeMm = screenWidthCm * 10f
        val scaledTextSizePx = if (scaledTextSizeMm < 155f) {
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, scaledTextSizeMm, displayMetrics) * 0.025f
        } else if (scaledTextSizeMm < 200f) {
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, scaledTextSizeMm, displayMetrics) * 0.023f
        } else if (scaledTextSizeMm < 300f) {
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, scaledTextSizeMm, displayMetrics) * 0.009f
        } else if (scaledTextSizeMm < 450f) {
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, scaledTextSizeMm, displayMetrics) * 0.008f
        } else {
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, scaledTextSizeMm, displayMetrics) * 0.006f
        }
        val scaledTextSizeSp = scaledTextSizePx / resources.displayMetrics.scaledDensity
        
        finishTimeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledTextSizeSp)
        remainingTimeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledTextSizeSp)
    }

    override fun onStop() {
        binding.playerView.player = null
        binding.volumeGestureLayout.visibility = View.GONE
        binding.brightnessGestureLayout.visibility = View.GONE
        currentOrientation = requestedOrientation
        mediaController?.run {
            viewModel.playWhenReady = playWhenReady
            lifecycleScope.launch {
                viewModel.skipSilenceEnabled = getSkipSilenceEnabled()
            }
            removeListener(playbackStateListener)
            stopProgressUpdater()
        }
        if (subtitleFileLauncherLaunchedForMediaItem != null) {
            mediaController?.pause()
        } else if (!playerPreferences.autoBackgroundPlay && !playInBackground) {
            mediaController?.stopPlayerSession()
        }
        controllerFuture?.run {
            MediaController.releaseFuture(this)
            controllerFuture = null
        }

        if (isPipActive) {
            finishAndRemoveTask()
        }
        super.onStop()
    }

    private fun maybeInitControllerFuture() {
        if (controllerFuture == null) {
            val sessionToken = SessionToken(applicationContext, ComponentName(applicationContext, PlayerService::class.java))
            controllerFuture = MediaController.Builder(applicationContext, sessionToken).buildAsync()
        }
    }

    @SuppressLint("NewApi", "MissingSuperCall")
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O..<Build.VERSION_CODES.S &&
            isPipSupported &&
            playerPreferences.autoPip &&
            mediaController?.isPlaying == true &&
            !isControlsLocked
        ) {
            try {
                this.enterPictureInPictureMode(updatePictureInPictureParams())
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isPipActive = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            
            // Hide finish time and remaining time in PiP mode
            finishTimeText.visibility = View.GONE
            remainingTimeText.visibility = View.GONE
            
            binding.playerView.subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION)
            playerUnlockControls.visibility = View.INVISIBLE
            pipBroadcastReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent == null || intent.action != PIP_INTENT_ACTION) return
                    when (intent.getIntExtra(PIP_INTENT_ACTION_CODE, 0)) {
                        PIP_ACTION_PLAY -> mediaController?.play()
                        PIP_ACTION_PAUSE -> mediaController?.pause()
                        PIP_ACTION_NEXT -> mediaController?.seekToNext()
                        PIP_ACTION_PREVIOUS -> mediaController?.seekToPrevious()
                    }
                    updatePictureInPictureParams()
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(pipBroadcastReceiver, IntentFilter(PIP_INTENT_ACTION), RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(pipBroadcastReceiver, IntentFilter(PIP_INTENT_ACTION))
            }
        } else {
            
            // Restore finish/remaining time based on current logic
            if (playerPreferences.showFinishTime) {
                updateFinishTimeText()
            }
            if (playerPreferences.showRemainingTime) {
                mediaController?.let {
                    updateRemainingTimeText(it.currentPosition, it.duration)
                }
            }
            
            binding.playerView.subtitleView?.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, playerPreferences.subtitleTextSize.toFloat())
            if (!isControlsLocked) {
                playerUnlockControls.visibility = View.VISIBLE
            }
            pipBroadcastReceiver?.let {
                unregisterReceiver(it)
                pipBroadcastReceiver = null
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun updatePictureInPictureParams(enableAutoEnter: Boolean = mediaController?.isPlaying == true): PictureInPictureParams {
        val displayAspectRatio = Rational(binding.playerView.width, binding.playerView.height)

        return PictureInPictureParams.Builder().apply {
            val aspectRatio = calculateVideoAspectRatio()
            if (aspectRatio != null) {
                val sourceRectHint = calculateSourceRectHint(displayAspectRatio, aspectRatio)
                setAspectRatio(aspectRatio)
                setSourceRectHint(sourceRectHint)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setSeamlessResizeEnabled(playerPreferences.autoPip && enableAutoEnter)
                setAutoEnterEnabled(playerPreferences.autoPip && enableAutoEnter)
            }

            setActions(
                listOf(
                    createPipAction(
                        context = this@PlayerActivity,
                        title = "skip to previous",
                        icon = coreUiR.drawable.ic_skip_prev,
                        actionCode = PIP_ACTION_PREVIOUS,
                    ),
                    if (mediaController?.isPlaying == true) {
                        createPipAction(
                            context = this@PlayerActivity,
                            title = "pause",
                            icon = coreUiR.drawable.ic_pause,
                            actionCode = PIP_ACTION_PAUSE,
                        )
                    } else {
                        createPipAction(
                            context = this@PlayerActivity,
                            title = "play",
                            icon = coreUiR.drawable.ic_play,
                            actionCode = PIP_ACTION_PLAY,
                        )
                    },
                    createPipAction(
                        context = this@PlayerActivity,
                        title = "skip to next",
                        icon = coreUiR.drawable.ic_skip_next,
                        actionCode = PIP_ACTION_NEXT,
                    ),
                ),
            )
        }.build().also { setPictureInPictureParams(it) }
    }

    private fun calculateVideoAspectRatio(): Rational? {
        return binding.playerView.player?.videoSize?.let { videoSize ->
            if (videoSize.width == 0 || videoSize.height == 0) return@let null

            Rational(
                videoSize.width,
                videoSize.height,
            ).takeIf { it.toFloat() in 0.5f..2.39f }
        }
    }

    private fun calculateSourceRectHint(displayAspectRatio: Rational, aspectRatio: Rational): Rect {
        val playerWidth = binding.playerView.width.toFloat()
        val playerHeight = binding.playerView.height.toFloat()

        return if (displayAspectRatio < aspectRatio) {
            val space = ((playerHeight - (playerWidth / aspectRatio.toFloat())) / 2).toInt()
            Rect(0, space, playerWidth.toInt(), (playerWidth / aspectRatio.toFloat()).toInt() + space)
        } else {
            val space = ((playerWidth - (playerHeight * aspectRatio.toFloat())) / 2).toInt()
            Rect(space, 0, (playerHeight * aspectRatio.toFloat()).toInt() + space, playerHeight.toInt())
        }
    }

    private fun setOrientation() {
        requestedOrientation = currentOrientation ?: playerPreferences.playerScreenOrientation.toActivityOrientation(
            videoOrientation = mediaController?.videoSize?.let { videoSize ->
                when {
                    videoSize.width == 0 || videoSize.height == 0 -> null
                    videoSize.isPortrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            },
        )
    }

    private fun initializePlayerView() {
        binding.playerView.apply {
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            controllerShowTimeoutMs = playerPreferences.controllerAutoHideTimeout.toMillis
            setControllerVisibilityListener(
                PlayerView.ControllerVisibilityListener { visibility ->
                    toggleSystemBars(showBars = visibility == View.VISIBLE && !isControlsLocked)
                },
            )

            subtitleView?.apply {
                val captioningManager = getSystemService(Context.CAPTIONING_SERVICE) as CaptioningManager
                if (playerPreferences.useSystemCaptionStyle) {
                    val systemCaptionStyle = CaptionStyleCompat.createFromCaptionStyle(captioningManager.userStyle)
                    setStyle(systemCaptionStyle)
                } else {
                    val userStyle = CaptionStyleCompat(
                        Color.WHITE,
                        Color.BLACK.takeIf { playerPreferences.subtitleBackground } ?: Color.TRANSPARENT,
                        Color.TRANSPARENT,
                        CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                        Color.BLACK,
                        Typeface.create(
                            playerPreferences.subtitleFont.toTypeface(),
                            Typeface.BOLD.takeIf { playerPreferences.subtitleTextBold } ?: Typeface.NORMAL,
                        ),
                    )
                    setStyle(userStyle)
                    setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, playerPreferences.subtitleTextSize.toFloat())
                }
                setApplyEmbeddedStyles(playerPreferences.applyEmbeddedStyles)
            }
        }

        audioTrackButton.setOnClickListener {
            TrackSelectionDialogFragment(
                type = C.TRACK_TYPE_AUDIO,
                tracks = mediaController?.currentTracks ?: return@setOnClickListener,
                onTrackSelected = { mediaController?.switchAudioTrack(it) },
            ).show(supportFragmentManager, "TrackSelectionDialog")
        }

        subtitleTrackButton.setOnClickListener {
            TrackSelectionDialogFragment(
                type = C.TRACK_TYPE_TEXT,
                tracks = mediaController?.currentTracks ?: return@setOnClickListener,
                onTrackSelected = { mediaController?.switchSubtitleTrack(it) },
                onOpenLocalTrackClicked = {
                    subtitleFileLauncherLaunchedForMediaItem = mediaController?.currentMediaItem
                    subtitleFileLauncher.launch(
                        arrayOf(
                            MimeTypes.APPLICATION_SUBRIP,
                            MimeTypes.APPLICATION_TTML,
                            MimeTypes.TEXT_VTT,
                            MimeTypes.TEXT_SSA,
                            MimeTypes.BASE_TYPE_APPLICATION + "/octet-stream",
                            MimeTypes.BASE_TYPE_TEXT + "/*",
                        ),
                    )
                },
            ).show(supportFragmentManager, "TrackSelectionDialog")
        }

        playbackSpeedButton.setOnClickListener {
            PlaybackSpeedControlsDialogFragment(
                mediaController = mediaController ?: return@setOnClickListener,
            ).show(supportFragmentManager, "PlaybackSpeedSelectionDialog")
        }

        lockControlsButton.setOnClickListener {
            playerUnlockControls.visibility = View.INVISIBLE
            playerLockControls.visibility = View.VISIBLE
            isControlsLocked = true
            toggleSystemBars(showBars = false)
        }
        unlockControlsButton.setOnClickListener {
            playerLockControls.visibility = View.INVISIBLE
            playerUnlockControls.visibility = View.VISIBLE
            isControlsLocked = false
            binding.playerView.showController()
            toggleSystemBars(showBars = true)
        }
        videoZoomButton.setOnClickListener {
            val videoZoom = playerPreferences.playerVideoZoom.next()
            changeAndSaveVideoZoom(videoZoom = videoZoom)
        }

        videoZoomButton.setOnLongClickListener {
            VideoZoomOptionsDialogFragment(
                currentVideoZoom = playerPreferences.playerVideoZoom,
                onVideoZoomOptionSelected = { changeAndSaveVideoZoom(videoZoom = it) },
            ).show(supportFragmentManager, "VideoZoomOptionsDialog")
            true
        }
        screenRotateButton.setOnClickListener {
            requestedOrientation = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
        pipButton.setOnClickListener {
            if (isPipSupported && !isPipEnabled) {
                Toast.makeText(this, coreUiR.string.enable_pip_from_settings, Toast.LENGTH_SHORT).show()
                try {
                    Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS").apply {
                        data = "package:$packageName".toUri()
                        startActivity(this@apply)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isPipSupported) {
                this.enterPictureInPictureMode(updatePictureInPictureParams())
            }
        }
        playInBackgroundButton.setOnClickListener {
            playInBackground = true
            finish()
        }
        backButton.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        updateLoopModeIcon(playerPreferences.loopMode)
        loopModeButton.setOnClickListener {
            val currentLoopMode = playerPreferences.loopMode
            val nextLoopMode = when (currentLoopMode) {
                LoopMode.OFF -> LoopMode.ONE
                LoopMode.ONE -> LoopMode.ALL
                LoopMode.ALL -> LoopMode.OFF
            }

            viewModel.setLoopMode(nextLoopMode)
            updateLoopModeIcon(nextLoopMode)
            applyLoopMode(nextLoopMode)
            showPlayerInfo(
                info = when (nextLoopMode) {
                    LoopMode.OFF -> getString(coreUiR.string.loop_mode_off)
                    LoopMode.ONE -> getString(coreUiR.string.loop_mode_one)
                    LoopMode.ALL -> getString(coreUiR.string.loop_mode_all)
                },
            )
        }
    }

    private fun updateLoopModeIcon(loopMode: LoopMode) {
        val iconResId = when (loopMode) {
            LoopMode.OFF -> coreUiR.drawable.ic_loop_off
            LoopMode.ONE -> coreUiR.drawable.ic_loop_one
            LoopMode.ALL -> coreUiR.drawable.ic_loop_all
        }
        loopModeButton.setImageResource(iconResId)
    }

    private fun applyLoopMode(loopMode: LoopMode) {
        mediaController?.repeatMode = when (loopMode) {
            LoopMode.OFF -> Player.REPEAT_MODE_OFF
            LoopMode.ONE -> Player.REPEAT_MODE_ONE
            LoopMode.ALL -> Player.REPEAT_MODE_ALL
        }
    }

    private fun startPlayback() {
        val uri = intent.data ?: return

        // If the intent is not new and the current media item is not null, return
        if (!isIntentNew && mediaController?.currentMediaItem != null) {
            mediaController?.prepare()
            return
        }

        // If the current media item is not null and the current media item's uri is the same as the intent's data, return
        if (mediaController?.currentMediaItem?.localConfiguration?.uri.toString() == uri.toString()) {
            mediaController?.prepare()
            return
        }

        isIntentNew = false

        lifecycleScope.launch {
            playVideo(uri)
        }
    }

    private suspend fun playVideo(uri: Uri) = withContext(Dispatchers.Default) {
        val mediaContentUri = getMediaContentUri(uri)
        val playlist = mediaContentUri?.let { mediaUri ->
            viewModel.getPlaylistFromUri(mediaUri)
                .map { it.uriString }
                .toMutableList()
                .apply {
                    if (!contains(mediaUri.toString())) {
                        add(index = 0, element = mediaUri.toString())
                    }
                }
        } ?: listOf(uri.toString())

        val mediaItemIndexToPlay = playlist.indexOfFirst {
            it == (mediaContentUri ?: uri).toString()
        }.takeIf { it >= 0 } ?: 0

        val mediaItems = playlist.mapIndexed { index, uri ->
            MediaItem.Builder().apply {
                setUri(uri)
                setMediaId(uri)
                if (index == mediaItemIndexToPlay) {
                    setMediaMetadata(MediaMetadata.Builder().setTitle(playerApi.title).build())
                    val apiSubs = playerApi.getSubs().map { subtitle ->
                        uriToSubtitleConfiguration(
                            uri = subtitle.uri,
                            subtitleEncoding = playerPreferences.subtitleTextEncoding,
                            isSelected = subtitle.isSelected,
                        )
                    }
                    setSubtitleConfigurations(apiSubs)
                }
            }.build()
        }

        withContext(Dispatchers.Main) {
            mediaController?.run {
                setMediaItems(mediaItems, mediaItemIndexToPlay, playerApi.position?.toLong() ?: C.TIME_UNSET)
                playWhenReady = viewModel.playWhenReady
                prepare()
            }
        }
    }

    private fun playbackStateListener() = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            super.onMediaItemTransition(mediaItem, reason)
            intent.data = mediaItem?.localConfiguration?.uri
            isMediaItemReady = false
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            super.onMediaMetadataChanged(mediaMetadata)
            videoTitleTextView.text = mediaMetadata.title
        }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
            super.onIsPlayingChanged(isPlaying)
            updateKeepScreenOnFlag()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isPipSupported) {
                updatePictureInPictureParams()
            }
            // Add this if/else block
            if (isPlaying) {
                startProgressUpdater()
            } else {
                stopProgressUpdater()
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            super.onAudioSessionIdChanged(audioSessionId)
            volumeManager.loudnessEnhancer?.release()

            if (playerPreferences.shouldUseVolumeBoost) {
                try {
                    volumeManager.loudnessEnhancer = LoudnessEnhancer(audioSessionId)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            super.onVideoSizeChanged(videoSize)
            if (videoSize.width != 0 && videoSize.height != 0) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isPipSupported) {
                    updatePictureInPictureParams()
                }
                setOrientation()
            }
            lifecycleScope.launch {
                val videoScale = mediaController?.currentMediaItem?.mediaId?.let { viewModel.getVideoState(it)?.videoScale } ?: 1f
                applyVideoZoom(videoZoom = playerPreferences.playerVideoZoom)
                applyVideoScale(videoScale = videoScale)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            super.onPlayerError(error)
            Timber.e(error)
            val alertDialog = MaterialAlertDialogBuilder(this@PlayerActivity).apply {
                setTitle(getString(coreUiR.string.error_playing_video))
                setMessage(error.message ?: getString(coreUiR.string.unknown_error))
                setNegativeButton(getString(coreUiR.string.exit)) { _, _ ->
                    finish()
                }
                if (mediaController?.hasNextMediaItem() == true) {
                    setPositiveButton(getString(coreUiR.string.play_next_video)) { dialog, _ ->
                        dialog.dismiss()
                        mediaController?.seekToNext()
                    }
                }
            }.create()

            alertDialog.show()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            super.onPlaybackStateChanged(playbackState)
            when (playbackState) {
                Player.STATE_ENDED -> {
                    isPlaybackFinished = true
                    stopProgressUpdater()
                    // Set progress to full width on completion
                    if (playerPreferences.showThinProgressBar) {
                        thinProgress.layoutParams.width = resources.displayMetrics.widthPixels
                        thinProgress.requestLayout()
                    }
                    finishTimeMillis = null
                    finish()
                }
                Player.STATE_IDLE -> {
                    isPlaybackFinished = mediaController?.playbackState == Player.STATE_ENDED
                    // Add these lines to stop and reset the progress bar
                    stopProgressUpdater()
                    thinProgress.visibility = View.GONE
                    thinProgress.layoutParams.width = 0
                    thinProgress.requestLayout()
                    finish()
                }

                Player.STATE_READY -> {
                    binding.playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    mediaController?.let {
                        //setFinishTimeOnce(it.currentPosition, it.duration)
                        lifecycleScope.launch {
                            delay(1000)
                            setFinishTimeOnce(it.currentPosition, it.duration)
                        }
                    }
                    isMediaItemReady = true
                    isFrameRendered = true
                }

                else -> {}
            }
        }
    }

    override fun finish() {
        if (playerApi.shouldReturnResult) {
            val result = playerApi.getResult(
                isPlaybackFinished = isPlaybackFinished,
                duration = mediaController?.duration ?: C.TIME_UNSET,
                position = mediaController?.currentPosition ?: C.TIME_UNSET,
            )
            setResult(Activity.RESULT_OK, result)
        }
        finishTimeMillis = null
        stopProgressUpdater()
        super.finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.data != null) {
            currentOrientation = null
            setIntent(intent)
            isIntentNew = true
            if (mediaController != null) {
                startPlayback()
            }
        }
    }

    private fun getFastPlaybackKeyNumber(keyCode: Int): Int =
    when (keyCode) {
        KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> 1
        KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> 2
        KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> 3
        KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> 4
        KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> 5
        KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> 6
        else -> 0
    }

    private fun lockFastPlayback() {
        if (mediaController != null) {
            fastPlaybackLockActive = true
            fastPlaybackLockedSpeed = mediaController!!.playbackParameters.speed
            showTopInfo("Speed locked: %.1fx".format(Locale.US, fastPlaybackLockedSpeed))
        }
    }
    
    private fun unlockFastPlayback() {
        if (mediaController != null) {
            fastPlaybackLockActive = false
            fastPlaybackLockedKey = null
            stopFastPlayback()
            showTopInfo("Speed unlocked")
            hideTopInfo(HIDE_DELAY_MILLIS)
        }
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isControlsLocked) return super.onKeyDown(keyCode, event)
        val isNumPadKey = keyCode in listOf(
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1,
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2,
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3,
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4,
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5,
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6
        )
        if (isNumPadKey) {
            if (event?.repeatCount == 0) {
                val lastPressedNumber = getFastPlaybackKeyNumber(keyCode)
                numpadKeyHistory.add(0,lastPressedNumber)
                unlockFastPlayback()
                startFastPlayback(lastPressedNumber)
                fastPlaybackLockedKey = keyCode
                return true
            }
        }
        
        if (keyCode == KeyEvent.KEYCODE_NUMPAD_DOT && event?.repeatCount == 0) {
            if (!fastPlaybackLockActive && fastPlaybackLockedKey != null) {
                lockFastPlayback()
            } else if (fastPlaybackLockActive) {
                unlockFastPlayback()
            }
            return true
        }
        
        when (keyCode) {
            // Volume Controls (Existing)
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_DPAD_UP,
            -> {
                if (!binding.playerView.isControllerFullyVisible || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    volumeManager.increaseVolume(playerPreferences.showSystemVolumePanel)
                    showVolumeGestureLayout()
                    return true
                }
            }
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_DPAD_DOWN,
            -> {
                if (!binding.playerView.isControllerFullyVisible || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    volumeManager.decreaseVolume(playerPreferences.showSystemVolumePanel)
                    showVolumeGestureLayout()
                    return true
                }
            }

            // Play/Pause Controls
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_8,
            KeyEvent.KEYCODE_NUMPAD_8,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            -> {
                if (event?.repeatCount == 0) {
                    binding.playerView.togglePlayPause()
                    return true
                }
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                mediaController?.play()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                mediaController?.pause()
                return true
            }

            // Seek Controls
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_7,
            KeyEvent.KEYCODE_NUMPAD_7,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            -> {
                mediaController?.run {
                    val newPosition = currentPosition - playerPreferences.seekIncrement.toMillis
                    seekBack(newPosition.coerceAtLeast(0), shouldFastSeek)
                    showPlayerInfo(
                        info = Utils.formatDurationMillis(newPosition),
                        subInfo = "-${playerPreferences.seekIncrement}s"
                    )
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_9,
            KeyEvent.KEYCODE_NUMPAD_9,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            -> {
                mediaController?.run {
                    val newPosition = currentPosition + playerPreferences.seekIncrement.toMillis
                    seekForward(newPosition.coerceAtMost(duration), shouldFastSeek)
                    showPlayerInfo(
                        info = Utils.formatDurationMillis(newPosition),
                        subInfo = "+${playerPreferences.seekIncrement}s"
                    )
                }
                return true
            }

            // Playback Speed Controls
//            KeyEvent.KEYCODE_Z -> {
//                changePlaybackSpeed(increase = false)
//                return true
//            }
//            KeyEvent.KEYCODE_X -> {
//                resetPlaybackSpeed()
//                return true
//            }
//            KeyEvent.KEYCODE_C -> {
//                changePlaybackSpeed(increase = true)
//                return true
//            }

            // Fast Playback (Hold-to-Seek)
//            KeyEvent.KEYCODE_1,
//            KeyEvent.KEYCODE_NUMPAD_1,
//            -> {
//                startFastPlayback(1)
//                return true
//            }
//            KeyEvent.KEYCODE_2,
//            KeyEvent.KEYCODE_NUMPAD_2,
//            -> {
//                startFastPlayback(2)
//                return true
//            }
//            KeyEvent.KEYCODE_3,
//            KeyEvent.KEYCODE_NUMPAD_3,
//            -> {
//                startFastPlayback(3)
//                return true
//            }
//            KeyEvent.KEYCODE_4,
//            KeyEvent.KEYCODE_NUMPAD_4,
//            -> {
//                startFastPlayback(4)
//                return true
//            }
//            KeyEvent.KEYCODE_5,
//            KeyEvent.KEYCODE_NUMPAD_5,
//            -> {
//                startFastPlayback(5)
//                return true
//            }
//            KeyEvent.KEYCODE_6,
//            KeyEvent.KEYCODE_NUMPAD_6,
//            -> {
//                startFastPlayback(6)
//                return true
//            }

            // Show/Hide Controller
//            KeyEvent.KEYCODE_DPAD_CENTER -> {
//                if (!binding.playerView.isControllerFullyVisible) {
//                    binding.playerView.showController()
//                }
                // Let the system handle it to press buttons on the controller
//                return super.onKeyDown(keyCode, event)
//            }

            // Back button behavior (Existing)
            KeyEvent.KEYCODE_BACK -> {
                if (binding.playerView.isControllerFullyVisible && mediaController?.isPlaying == true && isDeviceTvBox()) {
                    binding.playerView.hideController()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
    if (isControlsLocked) return super.onKeyUp(keyCode, event)

    val isNumPadKey = keyCode in listOf(
        KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1,
        KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2,
        KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3,
        KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4,
        KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5,
        KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6
    )
    if (isNumPadKey) {
        numpadKeyHistory.removeAll(listOf(getFastPlaybackKeyNumber(keyCode)))
        if (!fastPlaybackLockActive) {
            val latestPressedKey: Int? = numpadKeyHistory.firstOrNull()
            if (latestPressedKey != null) {
                unlockFastPlayback()
                startFastPlayback(latestPressedKey)
                fastPlaybackLockedKey = keyCode
            } else {
                stopFastPlayback()
                fastPlaybackLockedKey = null
            }
        return true
        }
    }
        
    when (keyCode) {
            // Hide volume indicator (Existing)
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            -> {
                hideVolumeGestureLayout()
                return true
            }

            // Hide seek indicator (Existing)
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_7,
            KeyEvent.KEYCODE_NUMPAD_7,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_9,
            KeyEvent.KEYCODE_NUMPAD_9,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            -> {
                hidePlayerInfo()
                return true
            }

            // Hide speed indicator after a delay
//            KeyEvent.KEYCODE_Z,
//            KeyEvent.KEYCODE_X,
//            KeyEvent.KEYCODE_C,
//            -> {
//                hideTopInfo(HIDE_DELAY_MILLIS)
//                return true
//            }

            // Stop Fast Playback on key release
//            KeyEvent.KEYCODE_1,
//            KeyEvent.KEYCODE_NUMPAD_1,
//            KeyEvent.KEYCODE_2,
//            KeyEvent.KEYCODE_NUMPAD_2,
//            KeyEvent.KEYCODE_3,
//            KeyEvent.KEYCODE_NUMPAD_3,
//            KeyEvent.KEYCODE_4,
//            KeyEvent.KEYCODE_NUMPAD_4,
//            KeyEvent.KEYCODE_5,
//            KeyEvent.KEYCODE_NUMPAD_5,
//            KeyEvent.KEYCODE_6,
//            KeyEvent.KEYCODE_NUMPAD_6,
//            -> {
//                stopFastPlayback()
//                return true
//            }
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun scrub(position: Long) {
        if (isFrameRendered) {
            isFrameRendered = false
            if (position > previousScrubPosition) {
                mediaController?.seekForward(position, shouldFastSeek)
            } else {
                mediaController?.seekBack(position, shouldFastSeek)
            }
            previousScrubPosition = position
        }
    }

    fun showVolumeGestureLayout() {
        hideVolumeIndicatorJob?.cancel()
        with(binding) {
            volumeGestureLayout.visibility = View.VISIBLE
            volumeProgressBar.max = volumeManager.maxVolume.times(100)
            volumeProgressBar.progress = volumeManager.currentVolume.times(100).toInt()
            volumeProgressText.text = volumeManager.volumePercentage.toString()
        }
    }

    fun showBrightnessGestureLayout() {
        hideBrightnessIndicatorJob?.cancel()
        with(binding) {
            brightnessGestureLayout.visibility = View.VISIBLE
            brightnessProgressBar.max = brightnessManager.maxBrightness.times(100).toInt()
            brightnessProgressBar.progress = brightnessManager.currentBrightness.times(100).toInt()
            brightnessProgressText.text = brightnessManager.brightnessPercentage.toString()
        }
    }

    fun showPlayerInfo(info: String, subInfo: String? = null) {
        hideInfoLayoutJob?.cancel()
        with(binding) {
            infoLayout.visibility = View.VISIBLE
            infoText.text = info
            infoSubtext.visibility = View.GONE.takeIf { subInfo == null } ?: View.VISIBLE
            infoSubtext.text = subInfo
        }
    }

    fun showTopInfo(info: String) {
        hideTopInfoJob?.cancel()
        with(binding) {
            topInfoLayout.visibility = View.VISIBLE
            topInfoText.text = info
        }
    }

    fun hideVolumeGestureLayout(delayTimeMillis: Long = HIDE_DELAY_MILLIS) {
        if (binding.volumeGestureLayout.visibility != View.VISIBLE) return
        hideVolumeIndicatorJob = lifecycleScope.launch {
            delay(delayTimeMillis)
            binding.volumeGestureLayout.visibility = View.GONE
        }
    }

    fun hideBrightnessGestureLayout(delayTimeMillis: Long = HIDE_DELAY_MILLIS) {
        if (binding.brightnessGestureLayout.visibility != View.VISIBLE) return
        hideBrightnessIndicatorJob = lifecycleScope.launch {
            delay(delayTimeMillis)
            binding.brightnessGestureLayout.visibility = View.GONE
        }
        if (playerPreferences.rememberPlayerBrightness) {
            viewModel.setPlayerBrightness(window.attributes.screenBrightness)
        }
    }

    fun hidePlayerInfo(delayTimeMillis: Long = HIDE_DELAY_MILLIS) {
        if (binding.infoLayout.visibility != View.VISIBLE) return
        hideInfoLayoutJob = lifecycleScope.launch {
            delay(delayTimeMillis)
            binding.infoLayout.visibility = View.GONE
        }
    }

    fun hideTopInfo() {
        if (!isFastPlaybackFromKeyboardActive) {
            binding.topInfoLayout.visibility = View.GONE
            hideTopInfoJob = null
        }
    }

    private fun updateKeepScreenOnFlag() {
        if (mediaController?.isPlaying == true) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun applyVideoScale(videoScale: Float) {
        exoContentFrameLayout.scaleX = videoScale
        exoContentFrameLayout.scaleY = videoScale
        exoContentFrameLayout.requestLayout()
    }

    private fun resetExoContentFrameWidthAndHeight() {
        exoContentFrameLayout.layoutParams.width = LayoutParams.MATCH_PARENT
        exoContentFrameLayout.layoutParams.height = LayoutParams.MATCH_PARENT
        exoContentFrameLayout.scaleX = 1.0f
        exoContentFrameLayout.scaleY = 1.0f
        exoContentFrameLayout.requestLayout()
    }

    private fun applyVideoZoom(videoZoom: VideoZoom) {
        resetExoContentFrameWidthAndHeight()
        when (videoZoom) {
            VideoZoom.BEST_FIT -> {
                binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                videoZoomButton.setImageDrawable(this, coreUiR.drawable.ic_fit_screen)
            }

            VideoZoom.STRETCH -> {
                binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                videoZoomButton.setImageDrawable(this, coreUiR.drawable.ic_aspect_ratio)
            }

            VideoZoom.CROP -> {
                binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                videoZoomButton.setImageDrawable(this, coreUiR.drawable.ic_crop_landscape)
            }

            VideoZoom.HUNDRED_PERCENT -> {
                mediaController?.videoSize?.let {
                    exoContentFrameLayout.layoutParams.width = it.width
                    exoContentFrameLayout.layoutParams.height = it.height
                    exoContentFrameLayout.requestLayout()
                }
                binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                videoZoomButton.setImageDrawable(this, coreUiR.drawable.ic_width_wide)
            }
        }
    }

    private fun changeAndSaveVideoZoom(videoZoom: VideoZoom) {
        applyVideoZoom(videoZoom)
        viewModel.setVideoZoom(videoZoom)

        mediaController?.currentMediaItem?.mediaId?.let {
            viewModel.updateMediumZoom(uri = it, zoom = 1f)
        }

        lifecycleScope.launch {
            binding.infoLayout.visibility = View.VISIBLE
            binding.infoText.text = getString(videoZoom.nameRes())
            delay(HIDE_DELAY_MILLIS)
            binding.infoLayout.visibility = View.GONE
        }
    }

    private fun startProgressUpdater() {
        // Cancel any existing job to avoid multiple updaters running
        stopProgressUpdater()
        progressUpdateJob = lifecycleScope.launch {
            while (true) {
                mediaController?.let { controller ->
                    // Ensure duration is positive to avoid division by zero
                    if (controller.duration > 0) {
                        val currentPosition = controller.currentPosition
                        val duration = controller.duration
                        val screenWidth = resources.displayMetrics.widthPixels

                        // Calculate the width of the progress bar
                        val progressWidth = (currentPosition.toFloat() / duration * screenWidth).toInt()

                        // Update Finish Time Text
                        updateFinishTimeText()

                        // Update the UI on the main thread
                        withContext(Dispatchers.Main) {
                            if (playerPreferences.showThinProgressBar) {
                                thinProgress.visibility = View.VISIBLE
                                val params = thinProgress.layoutParams
                                params.width = progressWidth
                                thinProgress.layoutParams = params
                            } else {
                                thinProgress.visibility = View.GONE
                            }
                            updateRemainingTimeText(currentPosition, duration)
                        }
                    }
                }
                delay(250) // Update interval
            }
        }
    }

    private fun stopProgressUpdater() {
        progressUpdateJob?.cancel()
        progressUpdateJob = null
    }

    /**
     * Updates the thin progress bar manually, useful for immediate feedback like during seeking.
     */
    private fun updateThinProgressBar(position: Long, duration: Long) {
        if (duration > 0) {
            val screenWidth = resources.displayMetrics.widthPixels
            val progressWidth = (position.toFloat() / duration * screenWidth).toInt()
            val params = thinProgress.layoutParams
            params.width = progressWidth
            thinProgress.layoutParams = params
            updateRemainingTimeText(position, duration)
            if (playerPreferences.showThinProgressBar) {
                thinProgress.visibility = View.VISIBLE
            }
        } else {
            thinProgress.visibility = View.GONE
        }
    }

    //Set Video Finish Time Text
    private fun setFinishTimeOnce(currentPos: Long, duration: Long) {
        if (finishTimeMillis != null) return  // Already set
        if (duration > 0 && currentPos <= duration) {
            val remainingMs = duration - currentPos
            val remainingMin = ((remainingMs + 59_999) / 60_000).toInt()
            val now = System.currentTimeMillis()
            finishTimeMillis = now + remainingMin * 60_000L
            if (playerPreferences.showFinishTime) {
                finishTimeText.visibility = View.VISIBLE
            }
            // Immediately update the text at start
            updateFinishTimeText()
        } else {
            finishTimeText.visibility = View.GONE
        }
    }

    //Update Video Finish Time Text
    private fun updateFinishTimeText() {
        if (isPipActive || !playerPreferences.showFinishTime) {
            finishTimeText.visibility = View.GONE
            return
        }
        val finishMillis = finishTimeMillis ?: return
        
        // Format finish time as HH:mm
        val finishTimeStr = SimpleDateFormat("HH:mm", Locale.getDefault())
            .format(Date(finishMillis))
            
        // Time difference from finish time (round up to the next minute)
        val msDiff = System.currentTimeMillis() - finishMillis
        val minutes = (msDiff + if (msDiff >= 0) 30_000 else -30_000) / 60_000

        val showText = "$finishTimeStr (${if (minutes >= 0) "+" else ""}${minutes}m)"
        finishTimeText.text = showText
        finishTimeText.visibility = View.VISIBLE
    }
    
    // Update Remaining Time Text
    private fun updateRemainingTimeText(position: Long, duration: Long) {
        if (isPipActive || !playerPreferences.showRemainingTime) {
            remainingTimeText.visibility = View.GONE
            return
        }
        if (duration > 0 && position <= duration) {
            val remainingMs = duration - position
            val remainingMin = ((remainingMs + 59_999) / 60_000).toInt() // round up to minutes
            val text = if (remainingMin > 1) "${remainingMin}m" else "<1m"
            remainingTimeText.text = text
            remainingTimeText.visibility = View.VISIBLE
        } else {
            remainingTimeText.visibility = View.GONE
        }
    }
    
    private fun changePlaybackSpeed(increase: Boolean) {
        mediaController?.let { controller ->
            val currentSpeed = controller.playbackParameters.speed
            // Increase or decrease speed by 0.10f, ensuring it doesn't go below 0.10
            val newSpeed = if (increase) {
                currentSpeed + 0.10f
            } else {
                (currentSpeed - 0.10f).coerceAtLeast(0.10f)
            }
            controller.setPlaybackSpeed(newSpeed)
            showTopInfo(getString(coreUiR.string.fast_playback_speed, "%.2f".format(newSpeed)))
        }
    }

    private fun resetPlaybackSpeed() {
        mediaController?.setPlaybackSpeed(1.0f)
        showTopInfo(getString(coreUiR.string.fast_playback_speed, "1.00"))
    }

    private fun startFastPlayback(keyNumber: Int) {
        if (isFastPlaybackFromKeyboardActive) return
        mediaController?.let { controller ->
            isFastPlaybackFromKeyboardActive = true
            originalPlaybackSpeed = controller.playbackParameters.speed
            val targetSpeed = when (keyNumber) {
                1 -> (2 * originalPlaybackSpeed + 1 * playerPreferences.longPressControlsSpeed) / 3f
                2 -> (1 * originalPlaybackSpeed + 2 * playerPreferences.longPressControlsSpeed) / 3f
                3 -> playerPreferences.longPressControlsSpeed
                4 -> playerPreferences.longPressControlsSpeed + 0.25f
                5 -> playerPreferences.longPressControlsSpeed + 0.5f
                else -> playerPreferences.longPressControlsSpeed + 0.75f
            }
            showTopInfo(getString(coreUiR.string.fast_playback_speed, targetSpeed))
            controller.setPlaybackSpeed(targetSpeed)
        }
    }
    
    private fun stopFastPlayback(force: Boolean = false) {
        if (!isFastPlaybackFromKeyboardActive) return
        if (fastPlaybackLockActive && !force) return // Don't stop if locked, unless forced
        mediaController?.setPlaybackSpeed(originalPlaybackSpeed)
        isFastPlaybackFromKeyboardActive = false
        hideTopInfo()
    }
    
    // Overload hideTopInfo to allow for a delay
    fun hideTopInfo(delayTimeMillis: Long) {
        hideTopInfoJob?.cancel()
        hideTopInfoJob = lifecycleScope.launch {
            delay(delayTimeMillis)
            hideTopInfo()
        }
    }

    companion object {
        const val HIDE_DELAY_MILLIS = 1000L
        const val PIP_INTENT_ACTION = "pip_action"
        const val PIP_INTENT_ACTION_CODE = "pip_action_code"
        const val PIP_ACTION_PLAY = 1
        const val PIP_ACTION_PAUSE = 2
        const val PIP_ACTION_NEXT = 3
        const val PIP_ACTION_PREVIOUS = 4
    }
}

@RequiresApi(Build.VERSION_CODES.O)
private fun createPipAction(
    context: Context,
    title: String,
    @DrawableRes icon: Int,
    actionCode: Int,
): RemoteAction {
    return RemoteAction(
        Icon.createWithResource(context, icon),
        title,
        title,
        PendingIntent.getBroadcast(
            context,
            actionCode,
            Intent(PlayerActivity.PIP_INTENT_ACTION).apply {
                putExtra(PlayerActivity.PIP_INTENT_ACTION_CODE, actionCode)
                setPackage(context.packageName)
            },
            PendingIntent.FLAG_IMMUTABLE,
        ),
    )
}
