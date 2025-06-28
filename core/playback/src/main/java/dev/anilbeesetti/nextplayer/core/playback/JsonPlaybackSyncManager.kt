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

    /**
     * Reads all playback positions from the JSON file in the specified directory.
     * Returns an empty list if the file doesn't exist, is empty, or if an error occurs.
     */
    suspend fun readPlaybackPositions(syncDirectoryUri: String): List<PlaybackPosition> = withContext(Dispatchers.IO) {
        if (syncDirectoryUri.isBlank()) {
            Timber.d("Sync directory URI is blank, cannot read JSON.")
            return@withContext emptyList()
        }
        try {
            val dir = DocumentFile.fromTreeUri(context, syncDirectoryUri.toUri())
            val file = dir?.findFile(JSON_FILE_NAME)
            if (file == null || !file.exists()) {
                Timber.d("JSON file '$JSON_FILE_NAME' not found or does not exist in $syncDirectoryUri.")
                return@withContext emptyList()
            }

            context.contentResolver.openInputStream(file.uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    val content = reader.readText()
                    if (content.isBlank()) {
                        Timber.d("JSON file is empty.")
                        emptyList()
                    } else {
                        json.decodeFromString(content)
                    }
                }
            } ?: emptyList()
        } catch (e: Exception) {
            Timber.e(e, "Error reading playback positions JSON from $syncDirectoryUri")
            emptyList()
        }
    }

    /**
     * Writes a list of playback positions to the JSON file, merging them with existing data.
     * If a position for a filename already exists, it will be updated. New positions will be added.
     */
    suspend fun writePlaybackPositions(syncDirectoryUri: String, positionsToUpdate: List<PlaybackPosition>) = withContext(Dispatchers.IO) {
        if (syncDirectoryUri.isBlank()) {
            Timber.d("Sync directory URI is blank, cannot write JSON.")
            return@withContext
        }
        try {
            val dirUri = syncDirectoryUri.toUri()
            val dir = DocumentFile.fromTreeUri(context, dirUri)
            if (dir == null || !dir.exists() || !dir.isDirectory) {
                Timber.e("Selected sync directory does not exist or is not a directory: $syncDirectoryUri")
                return@withContext
            }

            var file = dir.findFile(JSON_FILE_NAME)
            if (file == null) {
                Timber.d("Creating new JSON file '$JSON_FILE_NAME' in $syncDirectoryUri.")
                file = dir.createFile("application/json", JSON_FILE_NAME)
            }
            if (file == null) {
                Timber.e("Failed to create or find playback positions JSON file.")
                return@withContext
            }
            if (!file.canWrite()) {
                Timber.e("Cannot write to JSON file. Check permissions for $syncDirectoryUri.")
                return@withContext
            }

            // Read existing positions, merge, and then write back
            val existingPositions = readPlaybackPositions(syncDirectoryUri).associateBy { it.filename }.toMutableMap()
            positionsToUpdate.forEach { newEntry ->
                existingPositions[newEntry.filename] = newEntry // Overwrite if exists, add if new
            }

            val mergedList = existingPositions.values.toList()

            context.contentResolver.openOutputStream(file.uri, "w")?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    val jsonString = json.encodeToString(mergedList)
                    writer.write(jsonString)
                    Timber.d("Successfully wrote merged playback positions to JSON.")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error writing playback positions JSON to $syncDirectoryUri")
        }
    }
}
