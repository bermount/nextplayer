package dev.anilbeesetti.nextplayer.core.playback

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.anilbeesetti.nextplayer.core.common.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import javax.inject.Inject
import javax.inject.Singleton
import java.security.MessageDigest

private const val JSON_FILE_NAME = "playback_positions.json"

fun hashString(input: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}

@Singleton
class JsonPlaybackSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val applicationScope: CoroutineScope,
    private val json: Json,
) {

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        private const val PREFS_NAME = "playback_sync_prefs"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
    }

    private val mutex = Mutex()
    
    suspend fun readPlaybackPositions(syncDirectoryUri: String): List<PlaybackPosition> = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (syncDirectoryUri.isBlank()) return@withContext emptyList()
            try {
                val syncStartTime = System.currentTimeMillis()
                val lastSyncTime = prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
                 
                val dir = DocumentFile.fromTreeUri(context, syncDirectoryUri.toUri())
                val playbackDir = dir?.findFile("playback_positions")
                if (playbackDir == null || !playbackDir.isDirectory) return@withContext emptyList()

                val positions = playbackDir.listFiles().mapNotNull { file ->
                    if (file.lastModified() > lastSyncTime) {
                        try {
                            context.contentResolver.openInputStream(file.uri)?.use { inputStream ->
                                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                                    val content = reader.readText()
                                    if (content.isNotBlank()) json.decodeFromString<PlaybackPosition>(content) else null
                                }
                            }
                        } catch (e: Exception) {
                        null
                        }
                    } else {
                        null
                    }
                }
                prefs.edit().putLong(KEY_LAST_SYNC_TIME, syncStartTime).apply()
                positions
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
    
    suspend fun writePlaybackPositions(
        syncDirectoryUri: String,
        playbackPosition: PlaybackPosition
    ) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (syncDirectoryUri.isBlank()) return@withContext
            try {
                val dir = DocumentFile.fromTreeUri(context, syncDirectoryUri.toUri())
                if (dir == null || !dir.exists() || !dir.isDirectory) return@withContext
                
                // Create a subdir for playback positions
                var playbackDir = dir.findFile("playback_positions")
                if (playbackDir == null || !playbackDir.isDirectory) {
                    playbackDir = dir.createDirectory("playback_positions")
                }
                if (playbackDir == null) return@withContext
                
                // Use filename as the file name
                // Use a SHA-256 hash of the filename as the file name
                val safeFilename = hashString(playbackPosition.filename)
                val fileName = "$safeFilename.json"
                
                // Remove old file if exists
                playbackDir.findFile(fileName)?.delete()
                val duplicatePattern = Regex("""^${Regex.escape(safeFilename)} \(\d+\)\.json$""")
                playbackDir.listFiles().forEach { file ->
                    if (duplicatePattern.matches(file.name ?: "")) {
                        file.delete()
                    }
                }
                
                val existingFile = playbackDir.findFile(fileName)
                if (existingFile != null) {
                    // Overwrite the file by opening its output stream
                    context.contentResolver.openOutputStream(existingFile.uri, "w")?.use { outputStream ->
                        OutputStreamWriter(outputStream).use { writer ->
                            writer.write(json.encodeToString(playbackPosition))
                        }
                    }
                } else {
                    // No file exists, safe to create
                    val file = playbackDir.createFile("application/json", fileName)
                    if (file != null) {
                        context.contentResolver.openOutputStream(file.uri, "w")?.use { outputStream ->
                            OutputStreamWriter(outputStream).use { writer ->
                                writer.write(json.encodeToString(playbackPosition))
                            }
                        }
                    }
                }
                
            } catch (e: Exception) {
                Timber.e(e, "Error writing playback position for ${playbackPosition.filename}")
            }
        }
    }
}
