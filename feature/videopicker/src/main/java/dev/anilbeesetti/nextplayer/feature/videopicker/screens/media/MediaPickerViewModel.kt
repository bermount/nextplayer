package dev.anilbeesetti.nextplayer.feature.videopicker.screens.media

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.data.repository.MediaRepository
import dev.anilbeesetti.nextplayer.core.domain.GetSortedMediaUseCase
import dev.anilbeesetti.nextplayer.core.media.services.MediaService
import dev.anilbeesetti.nextplayer.core.media.sync.MediaInfoSynchronizer
import dev.anilbeesetti.nextplayer.core.media.sync.MediaSynchronizer
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.Folder
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.MediaState
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first // <-- Add this import

@HiltViewModel
class MediaPickerViewModel @Inject constructor(
    getSortedMediaUseCase: GetSortedMediaUseCase,
    private val mediaService: MediaService,
    private val preferencesRepository: PreferencesRepository,
    private val mediaInfoSynchronizer: MediaInfoSynchronizer,
    private val mediaSynchronizer: MediaSynchronizer,
    private val mediaRepository: MediaRepository
) : ViewModel() {

    private val uiStateInternal = MutableStateFlow(MediaPickerUiState())
    val uiState = uiStateInternal.asStateFlow()

    val mediaState = getSortedMediaUseCase.invoke()
        .map { MediaState.Success(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = MediaState.Loading,
        )

    val preferences = preferencesRepository.applicationPreferences
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ApplicationPreferences(),
        )

    init {
        viewModelScope.launch {
            val syncDirUri = preferencesRepository.playerPreferences.first().syncPlaybackPositionsFolderUri
            if (!syncDirUri.isNullOrBlank()) {
                mediaRepository.syncAllJsonPlaybackPositions(syncDirUri)
            }
        }
    }

    fun updateMenu(applicationPreferences: ApplicationPreferences) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences { applicationPreferences }
        }
    }

    fun deleteVideos(uris: List<String>) {
        viewModelScope.launch {
            mediaService.deleteMedia(uris.map { Uri.parse(it) })
        }
    }

    fun deleteFolders(folders: List<Folder>) {
        viewModelScope.launch {
            val uris = folders.flatMap { folder ->
                folder.allMediaList.mapNotNull { video ->
                    Uri.parse(video.uriString)
                }
            }
            mediaService.deleteMedia(uris)
        }
    }

    fun addToMediaInfoSynchronizer(uri: Uri) {
        viewModelScope.launch {
            mediaInfoSynchronizer.addMedia(uri)
        }
    }

    fun renameVideo(uri: Uri, to: String) {
        viewModelScope.launch {
            mediaService.renameMedia(uri, to)
        }
    }
    
    fun onRefreshClicked() {
        viewModelScope.launch {
            uiStateInternal.update { it.copy(refreshing = true) }
            val syncDirUri = preferencesRepository.playerPreferences.first().syncPlaybackPositionsFolderUri
            if (!syncDirUri.isNullOrBlank()) {
                mediaRepository.syncAllJsonPlaybackPositions(syncDirUri)
            }
            mediaSynchronizer.refresh()
            uiStateInternal.update { it.copy(refreshing = false) }
        }
    }
}

data class MediaPickerUiState(
    val refreshing: Boolean = false,
)
