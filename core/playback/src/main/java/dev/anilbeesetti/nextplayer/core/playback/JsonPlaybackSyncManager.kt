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

    suspend fun readPlaybackPositions(syncDirectoryUri: String): List<PlaybackPosition> = withContext(Dispatchers.IO) {
        if (syncDirectoryUri.isBlank()) return@withContext emptyList()
        try {
            val dir = DocumentFile.fromTreeUri(context, syncDirectoryUri.toUri())
            val file = dir?.findFile(JSON_FILE_NAME)
            if (file == null || !file.exists()) {
                return@withContext emptyList()
            }

            context.contentResolver.openInputStream(file.uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    val content = reader.readText()
                    if (content.isBlank()) emptyList() else json.decodeFromString(content)
                }
            } ?: emptyList()
        } catch (e: Exception) {
            Timber.e(e, "Error reading playback positions JSON")
            emptyList()
        }
    }

    suspend fun writePlaybackPositions(syncDirectoryUri: String, newPositions: List<PlaybackPosition>) = withContext(Dispatchers.IO) {
        if (syncDirectoryUri.isBlank()) return@withContext
        try {
            val dir = DocumentFile.fromTreeUri(context, syncDirectoryUri.toUri())
            var file = dir?.findFile(JSON_FILE_NAME)
            if (file == null) {
                file = dir?.createFile("application/json", JSON_FILE_NAME)
            }
            if (file == null) {
                Timber.e("Failed to create playback positions JSON file.")
                return@withContext
            }
            
            val existingPositions = readPlaybackPositions(syncDirectoryUri).associateBy { it.filename }.toMutableMap()
            newPositions.forEach { newEntry ->
                existingPositions[newEntry.filename] = newEntry
            }
            
            val mergedList = existingPositions.values.toList()
            
            context.contentResolver.openOutputStream(file.uri, "w")?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    val jsonString = json.encodeToString(mergedList)
                    writer.write(jsonString)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error writing playback positions JSON")
        }
    }
    
}
