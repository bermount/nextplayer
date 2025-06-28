package dev.anilbeesetti.nextplayer.core.data.repository

import android.net.Uri
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
            val duration = mediumDao.get(uri)?.duration ?: position.plus(1)
            val finalPosition = position.takeIf { it < duration } ?: Long.MIN_VALUE.plus(1)
            val timestamp = System.currentTimeMillis()
            
            // Update local database
            mediumDao.updatePositionAndTimestamp(uri, finalPosition, timestamp)
            mediumDao.updateMediumLastPlayedTime(uri, timestamp)
            
            // Update external JSON
            val syncFolder = preferencesRepository.playerPreferences.first().syncPlaybackPositionsFolderUri
            if (syncFolder.isNotBlank()) {
                val allPositions = jsonPlaybackSyncManager.readPlaybackPositions(syncFolder).toMutableList()
                val existingIndex = allPositions.indexOfFirst { it.filename == filename }
                val newPositionEntry = PlaybackPosition(filename, finalPosition, timestamp)
                
                if (existingIndex != -1) {
                    allPositions[existingIndex] = newPositionEntry
                } else {
                    allPositions.add(newPositionEntry)
                }
                jsonPlaybackSyncManager.writePlaybackPositions(syncFolder, allPositions)
            }
        }
    }

 
    override suspend fun syncAndGetPlaybackPosition(uri: String, filename: String): Long? {
        val syncFolder = preferencesRepository.playerPreferences.first().syncPlaybackPositionsFolderUri
        if (syncFolder.isBlank()) {
            return mediumDao.get(uri)?.playbackPosition
        }
        
        val dbEntity = mediumDao.get(uri)
        val dbPosition = dbEntity?.playbackPosition
        val dbTimestamp = dbEntity?.positionLastUpdated ?: 0L
        
        val jsonPositions = jsonPlaybackSyncManager.readPlaybackPositions(syncFolder)
        val jsonEntry = jsonPositions.find { it.filename == filename }
        val jsonPosition = jsonEntry?.position
        val jsonTimestamp = jsonEntry?.lastUpdated ?: 0L
        
        // Compare and sync
        when {
            // JSON is newer, update DB
            jsonTimestamp > dbTimestamp && jsonPosition != null -> {
                mediumDao.updatePositionAndTimestamp(uri, jsonPosition, jsonTimestamp)
                return jsonPosition
            }
            // DB is newer, update JSON
            dbTimestamp > jsonTimestamp && dbPosition != null -> {
                val newPositions = jsonPositions.filterNot { it.filename == filename }.toMutableList()
                newPositions.add(PlaybackPosition(filename, dbPosition, dbTimestamp))
                jsonPlaybackSyncManager.writePlaybackPositions(syncFolder, newPositions)
                return dbPosition
            }
            // No conflict or no data, return DB value
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
