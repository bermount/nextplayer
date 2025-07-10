package dev.anilbeesetti.nextplayer.core.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.C
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.anilbeesetti.nextplayer.core.common.FileHashGenerator
import dev.anilbeesetti.nextplayer.core.common.di.ApplicationScope
import dev.anilbeesetti.nextplayer.core.data.mappers.toFolder
import dev.anilbeesetti.nextplayer.core.data.mappers.toVideo
import dev.anilbeesetti.nextplayer.core.data.mappers.toVideoState
import dev.anilbeesetti.nextplayer.core.data.models.VideoState
import dev.anilbeesetti.nextplayer.core.database.converter.UriListConverter
import dev.anilbeesetti.nextplayer.core.database.dao.DirectoryDao
import dev.anilbeesetti.nextplayer.core.database.dao.MediumDao
import dev.anilbeesetti.nextplayer.core.database.relations.DirectoryWithMedia
import dev.anilbeesetti.nextplayer.core.database.relations.MediumWithInfo
import dev.anilbeesetti.nextplayer.core.model.Folder
import dev.anilbeesetti.nextplayer.core.model.Video
import dev.anilbeesetti.nextplayer.core.playback.JsonPlaybackSyncManager
import dev.anilbeesetti.nextplayer.core.playback.PlaybackPosition
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class LocalMediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediumDao: MediumDao,
    private val directoryDao: DirectoryDao,
    private val jsonPlaybackSyncManager: JsonPlaybackSyncManager,
    private val preferencesRepository: PreferencesRepository,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : MediaRepository {

    override fun getVideosFlow(): Flow<List<Video>> {
        return mediumDao.getAllWithInfo().map { it.map(MediumWithInfo::toVideo) }
    }

    override fun getVideosFlowFromFolderPath(folderPath: String): Flow<List<Video>> {
        return mediumDao.getAllWithInfoFromDirectory(folderPath).map { it.map(MediumWithInfo::toVideo) }
    }

    override fun getFoldersFlow(): Flow<List<Folder>> {
        return directoryDao.getAllWithMedia().map { it.map(DirectoryWithMedia::toFolder) }
    }

    override suspend fun getVideoState(uri: String): VideoState? {
        return mediumDao.get(uri)?.toVideoState()
    }

    override fun updateMediumLastPlayedTime(uri: String, lastPlayedTime: Long) {
        applicationScope.launch {
            mediumDao.updateMediumLastPlayedTime(uri, lastPlayedTime)
        }
    }

    override fun updateMediumPosition(uri: String, position: Long) {
        applicationScope.launch {
            val duration = mediumDao.get(uri)?.duration ?: position.plus(1)
            val finalPosition = position.takeIf { it < duration } ?: C.TIME_UNSET
            val timestamp = System.currentTimeMillis()

            mediumDao.updatePositionAndTimestamp(uri, finalPosition, timestamp)
            mediumDao.updateMediumLastPlayedTime(uri, timestamp)

            val syncFolder = preferencesRepository.playerPreferences.first().syncPlaybackPositionsFolderUri
            if (syncFolder.isNotBlank()) {
                val fileIdentifier = FileHashGenerator.generateFileIdentifier(context, uri.toUri())
                if (fileIdentifier != null) {
                    val newPositionEntry = PlaybackPosition(fileIdentifier, finalPosition, timestamp)
                    jsonPlaybackSyncManager.writePlaybackPositions(syncFolder, newPositionEntry)
                }
            }
        }
    }

    override suspend fun syncAllJsonPlaybackPositions(syncDirectoryUri: String) {
        val allPositions = jsonPlaybackSyncManager.readPlaybackPositions(syncDirectoryUri)
        allPositions.forEach { position ->
            mediumDao.updatePositionAndTimestampByHash(
                hash = position.identifier,
                position = position.position,
                timestamp = position.lastUpdated
            )
        }
    }

    override suspend fun syncAndGetPlaybackPosition(uri: String): Long? {
        val syncFolder = preferencesRepository.playerPreferences.first().syncPlaybackPositionsFolderUri
        
        if (syncFolder.isBlank()) {
            return mediumDao.get(uri)?.playbackPosition
        }

        val fileIdentifier = FileHashGenerator.generateFileIdentifier(context, Uri.parse(uri))
        val dbEntity = mediumDao.get(uri)
        val dbPosition = dbEntity?.playbackPosition
        val dbTimestamp = dbEntity?.positionLastUpdated ?: 0L

        if (fileIdentifier == null) {
            return dbPosition
        }

        val jsonPositions = jsonPlaybackSyncManager.readPlaybackPositions(syncFolder)
        val jsonEntry = jsonPositions.find { it.identifier == fileIdentifier }
        val jsonPosition = jsonEntry?.position
        val jsonTimestamp = jsonEntry?.lastUpdated ?: 0L
        
        when {
            jsonTimestamp > dbTimestamp && jsonPosition != null -> {
                // Return the position immediately
                return jsonPosition
                // Then, launch the synchronization work in a background coroutine using applicationScope
                applicationScope.launch {
                    // Ensure I/O operations are run on the Dispatchers.IO thread
                    withContext(Dispatchers.IO) {
                        Timber.d("JSON is newer for $fileIdentifier. Syncing JSON to DB in background.")
                        mediumDao.updatePositionAndTimestamp(uri, jsonPosition, jsonTimestamp)
                    }
                }
            }
            dbTimestamp > jsonTimestamp && dbPosition != null -> {
                // Return the position immediately
                return dbPosition
                // Then, launch the synchronization work in a background coroutine using applicationScope
                applicationScope.launch {
                    // Ensure I/O operations are run on the Dispatchers.IO thread
                    withContext(Dispatchers.IO) {
                        Timber.d("DB is newer for $fileIdentifier. Syncing DB to JSON in background.")
                        val updatedJsonEntry = PlaybackPosition(fileIdentifier, dbPosition, dbTimestamp)
                        jsonPlaybackSyncManager.writePlaybackPositions(syncFolder, updatedJsonEntry)
                    }
                }
            }
            else -> {
                return dbPosition
            }
        }
    }
    
    override fun updateMediumPlaybackSpeed(uri: String, playbackSpeed: Float) {
        applicationScope.launch {
            mediumDao.updateMediumPlaybackSpeed(uri, playbackSpeed)
            mediumDao.updateMediumLastPlayedTime(uri, System.currentTimeMillis())
        }
    }

    override fun updateMediumAudioTrack(uri: String, audioTrackIndex: Int) {
        applicationScope.launch {
            mediumDao.updateMediumAudioTrack(uri, audioTrackIndex)
            mediumDao.updateMediumLastPlayedTime(uri, System.currentTimeMillis())
        }
    }

    override fun updateMediumSubtitleTrack(uri: String, subtitleTrackIndex: Int) {
        applicationScope.launch {
            mediumDao.updateMediumSubtitleTrack(uri, subtitleTrackIndex)
            mediumDao.updateMediumLastPlayedTime(uri, System.currentTimeMillis())
        }
    }

    override fun updateMediumZoom(uri: String, zoom: Float) {
        applicationScope.launch {
            mediumDao.updateMediumZoom(uri, zoom)
            mediumDao.updateMediumLastPlayedTime(uri, System.currentTimeMillis())
        }
    }

    override fun addExternalSubtitleToMedium(uri: String, subtitleUri: Uri) {
        applicationScope.launch {
            val currentExternalSubs = getVideoState(uri)?.externalSubs ?: emptyList()
            if (currentExternalSubs.contains(subtitleUri)) return@launch
            mediumDao.addExternalSubtitle(
                mediumUri = uri,
                externalSubs = UriListConverter.fromListToString(urlList = currentExternalSubs + subtitleUri),
            )
        }
    }
}
