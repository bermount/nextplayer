package dev.anilbeesetti.nextplayer.core.data.repository

import android.net.Uri
import androidx.media3.common.C
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
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

class LocalMediaRepository @Inject constructor(
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

    override fun updateMediumPosition(uri: String, filename: String, position: Long) {
        applicationScope.launch {
            // Ensure position is not greater than duration to avoid issues
            val duration = mediumDao.get(uri)?.duration ?: position.plus(1)
            val finalPosition = position.takeIf { it < duration } ?: C.TIME_UNSET // Use C.TIME_UNSET for "not set"
            val timestamp = System.currentTimeMillis()
            
            // 1. Update local database
            mediumDao.updatePositionAndTimestamp(uri, finalPosition, timestamp)
            mediumDao.updateMediumLastPlayedTime(uri, timestamp) // Keep last played time updated
            
            // 2. Update external JSON if sync folder is set
            val syncFolder = preferencesRepository.playerPreferences.first().syncPlaybackPositionsFolderUri
            if (syncFolder.isNotBlank()) {
                val newPositionEntry = PlaybackPosition(filename, finalPosition, timestamp)
                // Pass a list containing only the updated entry to the sync manager
                // The sync manager will handle merging this with the existing JSON content.
                jsonPlaybackSyncManager.writePlaybackPositions(syncFolder, newPositionEntry)
            }
        }
    }

    override suspend fun syncAndGetPlaybackPosition(uri: String, filename: String): Long? {
        val syncFolder = preferencesRepository.playerPreferences.first().syncPlaybackPositionsFolderUri
        
        // If no sync folder is set, just return the position from the local database
        if (syncFolder.isBlank()) {
            return mediumDao.get(uri)?.playbackPosition
        }
        
        val dbEntity = mediumDao.get(uri)
        val dbPosition = dbEntity?.playbackPosition
        val dbTimestamp = dbEntity?.positionLastUpdated ?: 0L // Default to 0 if null
        
        val jsonPositions = jsonPlaybackSyncManager.readPlaybackPositions(syncFolder)
        val jsonEntry = jsonPositions.find { it.filename == filename }
        val jsonPosition = jsonEntry?.position
        val jsonTimestamp = jsonEntry?.lastUpdated ?: 0L // Default to 0 if null
        
        // Compare timestamps to decide which source is more recent
        when {
            // Case 1: JSON data is newer than DB data (or DB has no timestamp for this item)
            jsonTimestamp > dbTimestamp && jsonPosition != null -> {
                Timber.d("JSON is newer for $filename. Syncing JSON to DB. Pos: $jsonPosition")
                mediumDao.updatePositionAndTimestamp(uri, jsonPosition, jsonTimestamp)
                return jsonPosition
            }
            // Case 2: DB data is newer than JSON data
            dbTimestamp > jsonTimestamp && dbPosition != null -> {
                Timber.d("DB is newer for $filename. Syncing DB to JSON. Pos: $dbPosition")
                // Create a list with just this updated entry
                val updatedJsonEntry = PlaybackPosition(filename, dbPosition, dbTimestamp)
                jsonPlaybackSyncManager.writePlaybackPositions(syncFolder, updatedJsonEntry)
                return dbPosition
            }
            // Case 3: Timestamps are equal or no data in either, or data matches.
            // In this case, prefer the DB value as the primary source.
            else -> {
                Timber.d("Timestamps are equal or no new data for $filename. Using DB position: $dbPosition")
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
