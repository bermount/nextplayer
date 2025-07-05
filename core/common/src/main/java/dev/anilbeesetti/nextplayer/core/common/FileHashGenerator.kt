package dev.anilbeesetti.nextplayer.core.common

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

object FileHashGenerator {
    private const val HASH_BUFFER_SIZE = 4096 // 4 KB

    suspend fun generateFileIdentifier(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val fileSize = pfd.statSize
                if (fileSize <= 0) return@withContext null

                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    
                    // 1. Use file size
                    digest.update(fileSize.toString().toByteArray())

                    val buffer = ByteArray(HASH_BUFFER_SIZE)

                    // 2. Use first 4KB
                    val firstBytesRead = inputStream.read(buffer)
                    if (firstBytesRead > 0) {
                        digest.update(buffer, 0, firstBytesRead)
                    }

                    // 3. Use last 4KB (if file is large enough)
                    if (fileSize > HASH_BUFFER_SIZE) {
                        val skipAmount = fileSize - HASH_BUFFER_SIZE
                        inputStream.skip(skipAmount - firstBytesRead) // Account for bytes already read
                        val lastBytesRead = inputStream.read(buffer)
                        if (lastBytesRead > 0) {
                            digest.update(buffer, 0, lastBytesRead)
                        }
                    }

                    return@withContext digest.digest().joinToString("") { "%02x".format(it) }
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
