package dev.anilbeesetti.nextplayer

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri // Added import
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher // Added import
import androidx.activity.result.contract.ActivityResultContracts // Added import
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri // Added import
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import dagger.hilt.android.AndroidEntryPoint
import dev.anilbeesetti.nextplayer.core.common.storagePermission
import dev.anilbeesetti.nextplayer.core.media.services.MediaService
import dev.anilbeesetti.nextplayer.core.media.sync.MediaSynchronizer
import dev.anilbeesetti.nextplayer.core.model.ThemeConfig
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import dev.anilbeesetti.nextplayer.navigation.MEDIA_ROUTE
import dev.anilbeesetti.nextplayer.navigation.mediaNavGraph
import dev.anilbeesetti.nextplayer.navigation.settingsNavGraph
import javax.inject.Inject
import kotlinx.coroutines.flow.first // Added import for .first()
import kotlinx.coroutines.launch
import timber.log.Timber
import android.app.Activity // Added import for Activity.RESULT_OK


@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var preferencesRepository: PreferencesRepository // Inject the preferences repository

    @Inject
    lateinit var synchronizer: MediaSynchronizer

    @Inject
    lateinit var mediaService: MediaService

    private val viewModel: MainActivityViewModel by viewModels()

    // This is the new way to handle results from an activity (like the folder picker)
    private val requestFolderPermissionLauncher: ActivityResultLauncher<Intent> = registerForActivityResult( // Explicit type added
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleSelectedFolderUri(uri)
            }
        } else {
            Timber.d("User cancelled folder selection for playback sync.")
            // Optional: You could show a Toast message to the user that they need to select a folder
            // or that syncing won't happen without it.
        }
    }

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize mediaService early
        mediaService.initialize(this@MainActivity)

        // Start UI state collection
        var uiState: MainActivityUiState by mutableStateOf(MainActivityUiState.Loading)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    uiState = state
                }
            }
        }

        // Handle splash screen and edge-to-edge
        installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        // Set Compose content
        setContent {
            NextPlayerTheme(
                darkTheme = shouldUseDarkTheme(uiState = uiState),
                highContrastDarkTheme = shouldUseHighContrastDarkTheme(uiState = uiState),
                dynamicColor = shouldUseDynamicTheming(uiState = uiState),
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    val storagePermissionState = rememberPermissionState(permission = storagePermission)

                    LifecycleEventEffect(event = Lifecycle.Event.ON_START) {
                        storagePermissionState.launchPermissionRequest()
                    }

                    LaunchedEffect(key1 = storagePermissionState.status.isGranted) {
                        if (storagePermissionState.status.isGranted) {
                            synchronizer.startSync()
                        }
                    }

                    val mainNavController = rememberNavController()

                    NavHost(
                        navController = mainNavController,
                        startDestination = MEDIA_ROUTE,
                    ) {
                        mediaNavGraph(
                            context = this@MainActivity,
                            navController = mainNavController,
                        )
                        settingsNavGraph(navController = mainNavController)
                    }
                }
            }
        }

        // Call this function when the activity is created to check/request the sync folder
        // This should be called after `setContent` if it interacts with views, or ideally
        // within a LaunchedEffect in Compose, or after initial setup.
        // For simplicity and to match your original structure, keeping it here for now.
        checkAndRequestSyncFolder()
    }

    /**
     * Checks if a sync folder is already set and has persistent permissions.
     * If not, it launches the folder picker.
     */
    private fun checkAndRequestSyncFolder() {
        lifecycleScope.launch {
            // preferencesRepository.playerPreferences.first() requires kotlinx.coroutines.flow.first import
            val currentSyncFolderUri = preferencesRepository.playerPreferences.first().syncPlaybackPositionsFolderUri
            if (currentSyncFolderUri.isBlank()) {
                Timber.d("No sync folder URI found in preferences. Launching folder picker.")
                launchFolderPicker()
            } else {
                val uri = currentSyncFolderUri.toUri()
                // Check if the persisted URI permission is still valid
                if (!checkUriPermission(uri)) {
                    Timber.d("Persisted URI permission revoked for $currentSyncFolderUri. Launching folder picker again.")
                    launchFolderPicker() // Permissions lost, prompt again
                } else {
                    Timber.d("Sync folder already set and permissions confirmed: $currentSyncFolderUri")
                }
            }
        }
    }

    /**
     * Launches the Storage Access Framework (SAF) document tree picker.
     */
    private fun launchFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        // Optionally, you can add an initial URI to guide the user:
        // intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, someDefaultUri)
        requestFolderPermissionLauncher.launch(intent)
    }

    /**
     * Handles the URI received from the SAF folder picker.
     * Takes persistable URI permissions and saves the URI to preferences.
     */
    private fun handleSelectedFolderUri(uri: Uri) {
        try {
            // Take persistable URI permissions. This is crucial so your app retains access
            // to this folder across app restarts and device reboots.
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            // Save this URI string to your PreferencesRepository
            lifecycleScope.launch {
                preferencesRepository.setSyncPlaybackPositionsFolderUri(uri.toString())
                Timber.d("Successfully saved sync folder URI: $uri")
                // You might want to trigger an initial sync after the folder is set.
                // For example, mediaRepository.syncAllPlaybackPositionsFromFiles() if you implement that.
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Failed to persist URI permission for $uri. User might have denied permanent access.")
            // Inform the user that the folder couldn't be set for syncing due to permission issues.
        }
    }

    /**
     * Helper function to check if your app still has granted permissions for a given URI.
     */
    private fun checkUriPermission(uri: Uri): Boolean {
        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), flags) == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Timber.e(e, "Error checking URI permission.")
            false
        }
    }
}

/**
 * Returns `true` if dark theme should be used, as a function of the [uiState] and the
 * current system context.
 */
@Composable
private fun shouldUseDarkTheme(
    uiState: MainActivityUiState,
): Boolean = when (uiState) {
    MainActivityUiState.Loading -> isSystemInDarkTheme()
    is MainActivityUiState.Success -> when (uiState.preferences.themeConfig) {
        ThemeConfig.SYSTEM -> isSystemInDarkTheme()
        ThemeConfig.OFF -> false
        ThemeConfig.ON -> true
    }
}

@Composable
fun shouldUseHighContrastDarkTheme(
    uiState: MainActivityUiState,
): Boolean = when (uiState) {
    MainActivityUiState.Loading -> false
    is MainActivityUiState.Success -> uiState.preferences.useHighContrastDarkTheme
}

/**
 * Returns `true` if the dynamic color is disabled, as a function of the [uiState].
 */
@Composable
private fun shouldUseDynamicTheming(
    uiState: MainActivityUiState,
): Boolean = when (uiState) {
    MainActivityUiState.Loading -> false
    is MainActivityUiState.Success -> uiState.preferences.useDynamicColors
}
