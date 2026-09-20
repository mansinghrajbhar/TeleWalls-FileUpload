package me.jaival.telewalls.core.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

object ImageUtils {
    fun resolveImageModel(primaryPath: String?, secondaryPath: String? = null): Any? {
        fun toModel(path: String?): Any? {
            if (path.isNullOrBlank()) return null
            return when {
                path.startsWith("/") -> {
                    val file = File(path)
                    if (file.exists() && file.length() > 0) file else null
                }
                path.startsWith("http://") || path.startsWith("https://") ||
                path.startsWith("content://") || path.startsWith("file://") -> path
                else -> null
            }
        }
        return toModel(primaryPath) ?: toModel(secondaryPath)
    }

    /**
     * Saves an image into Pictures/TeleWalls.
     */
    suspend fun saveImageToGallery(
        context: Context,
        imagePath: String,
        title: String,
        mimeType: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val bytes = readPathBytes(imagePath)
            val cleanTitle = sanitizeFileName(title).ifBlank { "wallpaper" }
            val extension = when {
                mimeType.contains("png", ignoreCase = true) -> "png"
                mimeType.contains("webp", ignoreCase = true) -> "webp"
                mimeType.contains("gif", ignoreCase = true) -> "gif"
                else -> "jpg"
            }
            val fileName = "TeleWalls_" + cleanTitle + "_" + System.currentTimeMillis() + "." + extension

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType.ifBlank { "image/jpeg" })
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TeleWalls")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext Result.failure(Exception("Failed to create Gallery entry"))

            try {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(bytes)
                    outputStream.flush()
                } ?: throw Exception("Failed to write image data")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val completed = ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }
                    resolver.update(uri, completed, null, null)
                }
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Saves any non-image file (PDF, ZIP, APK, MP3, MP4, DOCX, etc.)
     * into the device Downloads/TeleWalls folder.
     */
    suspend fun saveFileToDownloads(
        context: Context,
        filePath: String,
        fileName: String,
        mimeType: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val bytes = readPathBytes(filePath)
            val cleanName = sanitizeFileName(fileName).ifBlank { "TeleWalls_file" }
            val safeMime = mimeType.ifBlank { "application/octet-stream" }
            val resolver = context.contentResolver

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, cleanName)
                    put(MediaStore.Downloads.MIME_TYPE, safeMime)
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/TeleWalls")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }

                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext Result.failure(Exception("Failed to create Downloads entry"))

                try {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(bytes)
                        outputStream.flush()
                    } ?: throw Exception("Failed to write file data")

                    val completed = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    resolver.update(uri, completed, null, null)
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
            } else {
                val publicDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "TeleWalls"
                )
                val targetDir = if (publicDir.exists() || publicDir.mkdirs()) {
                    publicDir
                } else {
                    File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "TeleWalls")
                        .also { it.mkdirs() }
                }

                File(targetDir, cleanName).outputStream().use { outputStream ->
                    outputStream.write(bytes)
                    outputStream.flush()
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun readPathBytes(path: String): ByteArray {
        return when {
            path.startsWith("http://") || path.startsWith("https://") -> {
                URL(path).openStream().use { it.readBytes() }
            }
            path.startsWith("/") && File(path).exists() -> File(path).readBytes()
            else -> throw Exception("File is not available to save")
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name
            .replace(Regex("[\\/:*?\"<>|]"), "_")
            .replace(Regex("\s+"), " ")
            .trim()
    }
}
