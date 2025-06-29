package dev.anilbeesetti.nextplayer.core.playback

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.anilbeesetti.nextplayer.core.common.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import javax.inject.Inject
import javax.inject.Singleton

private const val JSON_FILE_NAME = "playback_positions.json"

@Singleton
class JsonPlaybackSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val applicationScope: CoroutineScope,
    private val json: Json,
) {

    suspend fun readAllPlaybackPositions(syncDirectoryUri: String): List<PlaybackPosition> = withContext(Dispatchers.IO) {
        if (syncDirectoryUri.isBlank()) return@withContext emptyList()
        try {
            val dir = DocumentFile.fromTreeUri(context, syncDirectoryUri.toUri())
            val playbackDir = dir?.findFile("playback_positions")
            if (playbackDir == null || !playbackDir.isDirectory) return@withContext emptyList()
            
            playbackDir.listFiles().mapNotNull { file ->
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
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    suspend fun writePlaybackPosition(
        syncDirectoryUri: String,
        playbackPosition: PlaybackPosition
    ) = withContext(Dispatchers.IO) {
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
            val safeFilename = playbackPosition.filename.replace(Regex("""[^a-zA-Z0-9._-]"""), "_")
            val fileName = "$safeFilename.json"
            
            // Remove old file if exists
            playbackDir.findFile(fileName)?.delete()
            
            // Write new file
            val file = playbackDir.createFile("application/json", fileName)
            if (file != null) {
                context.contentResolver.openOutputStream(file.uri, "w")?.use { outputStream ->
                    OutputStreamWriter(outputStream).use { writer ->
                        writer.write(json.encodeToString(playbackPosition))
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error writing playback position for ${playbackPosition.filename}")
        }
    }
}
