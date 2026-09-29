package me.jaival.telewalls.core.upload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.first as firstFlowEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.jaival.telewalls.MainActivity
import me.jaival.telewalls.core.palette.PaletteExtractor
import me.jaival.telewalls.core.telegram.TelegramUploadEvent
import me.jaival.telewalls.core.telegram.WallpaperMetadata
import me.jaival.telewalls.core.util.CharacterAuthorUtils
import me.jaival.telewalls.data.repository.AuthRepository
import me.jaival.telewalls.data.repository.WallpaperRepository
import java.io.File
import java.io.FileOutputStream
import java.io.BufferedInputStream
import java.security.MessageDigest
import javax.inject.Inject

@AndroidEntryPoint
class MassUploadService : Service() {

    @Inject
    lateinit var wallpaperRepository: WallpaperRepository

    @Inject
    lateinit var authRepository: AuthRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var isPaused = false

    @Volatile
    private var isStopped = false

    @Volatile
    private var currentProgressIndex = 0

    @Volatile
    private var totalProgressCount = 0

    @Volatile
    private var currentPhotoTitle = ""

    companion object {
        private const val TAG = "MassUploadService"
        const val EXTRA_IMAGE_URIS = "extra_image_uris"
        const val EXTRA_AUTHOR = "extra_author"
        const val EXTRA_WALLPAPER_TYPE = "extra_wallpaper_type"
        const val EXTRA_CATEGORY = "extra_category"
        const val EXTRA_TAGS = "extra_tags"

        const val ACTION_START = "me.jaival.telewalls.ACTION_START"
        const val ACTION_PAUSE = "me.jaival.telewalls.ACTION_PAUSE"
        const val ACTION_RESUME = "me.jaival.telewalls.ACTION_RESUME"
        const val ACTION_STOP = "me.jaival.telewalls.ACTION_STOP"

        private const val PROGRESS_CHANNEL_ID = "mass_upload_progress_channel"
        private const val RESULT_CHANNEL_ID = "mass_upload_result_channel"
        private const val PROGRESS_NOTIFICATION_ID = 2001
        private const val RESULT_NOTIFICATION_ID = 2002
        private const val PER_FILE_UPLOAD_TIMEOUT_MS = 3 * 60 * 1000L
        private const val DUPLICATE_CHECK_TIMEOUT_MS = 45 * 1000L

        fun startUpload(
            context: Context,
            uris: List<Uri>,
            author: String = "",
            wallpaperType: String = "",
            category: String = "",
            tags: String = ""
        ) {
            if (uris.isEmpty()) return
            val intent = Intent(context, MassUploadService::class.java).apply {
                action = ACTION_START
                putStringArrayListExtra(EXTRA_IMAGE_URIS, ArrayList(uris.map { it.toString() }))
                putExtra(EXTRA_AUTHOR, author)
                putExtra(EXTRA_WALLPAPER_TYPE, wallpaperType)
                putExtra(EXTRA_CATEGORY, category)
                putExtra(EXTRA_TAGS, tags)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (uris.isNotEmpty()) {
                    val clipData = android.content.ClipData.newRawUri("images", uris.first())
                    for (i in 1 until uris.size) {
                        clipData.addItem(android.content.ClipData.Item(uris[i]))
                    }
                    this.clipData = clipData
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        when (action) {
            ACTION_PAUSE -> {
                isPaused = true
                updateProgressNotification(currentProgressIndex, totalProgressCount, currentPhotoTitle)
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                isPaused = false
                updateProgressNotification(currentProgressIndex, totalProgressCount, currentPhotoTitle)
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                isStopped = true
                isPaused = false
                stopForegroundService()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val uriStrings = intent?.getStringArrayListExtra(EXTRA_IMAGE_URIS) ?: emptyList()
        val author = intent?.getStringExtra(EXTRA_AUTHOR) ?: ""
        val wallpaperType = intent?.getStringExtra(EXTRA_WALLPAPER_TYPE) ?: ""
        val category = intent?.getStringExtra(EXTRA_CATEGORY) ?: ""
        val tags = intent?.getStringExtra(EXTRA_TAGS) ?: ""

        if (uriStrings.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        isPaused = false
        isStopped = false
        totalProgressCount = uriStrings.size
        currentProgressIndex = 0
        currentPhotoTitle = "Preparing batch upload..."

        val initialNotification = buildProgressNotification(
            current = 0,
            total = uriStrings.size,
            currentFileName = currentPhotoTitle
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(
                        PROGRESS_NOTIFICATION_ID,
                        initialNotification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } catch (e: Exception) {
                    startForeground(PROGRESS_NOTIFICATION_ID, initialNotification)
                }
            } else {
                startForeground(PROGRESS_NOTIFICATION_ID, initialNotification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service notification", e)
        }

        serviceScope.launch {
            processMassUpload(
                uris = uriStrings.map { Uri.parse(it) },
                batchAuthor = author,
                batchWallpaperType = wallpaperType,
                batchCategory = category,
                batchTags = tags
            )
        }

        return START_NOT_STICKY
    }

    private suspend fun processMassUpload(
        uris: List<Uri>,
        batchAuthor: String = "",
        batchWallpaperType: String = "",
        batchCategory: String = "",
        batchTags: String = ""
    ) {
        val total = uris.size
        var successCount = 0
        var failureCount = 0
        val errors = mutableListOf<String>()
        val parsedTags = batchTags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val chatId = authRepository.activeChannelIdFlow.first() ?: 99999L

        for ((index, uri) in uris.withIndex()) {
            if (isStopped) break
            val n = index + 1
            try {
                val result = withTimeout(PER_FILE_UPLOAD_TIMEOUT_MS) {
                    uploadOneBatchFile(uri, index, n, total, chatId, batchAuthor, batchWallpaperType, batchCategory, parsedTags)
                }
                if (result.success) successCount++
                else if (!result.skipped) failureCount++
                result.message?.let { errors.add("File #$n: $it") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failureCount++
                errors.add("File #$n: " + (e.message ?: "Processing failed; skipped"))
                Log.e(TAG, "Batch file #$n failed; continuing", e)
            }
            if (!isStopped && n < total) delay(1500L)
        }

        stopForegroundService()
        if (!isStopped) showFinalResultNotification(total, successCount, failureCount, errors)
        stopSelf()
    }

    private data class BatchFileResult(
        val success: Boolean,
        val skipped: Boolean,
        val message: String?
    )

    private suspend fun uploadOneBatchFile(
        uri: Uri,
        index: Int,
        n: Int,
        total: Int,
        chatId: Long,
        batchAuthor: String,
        batchWallpaperType: String,
        batchCategory: String,
        parsedTags: List<String>
    ): BatchFileResult {
        var tempFile: File? = null
        try {
            val raw = getFileNameFromUri(uri)
            val title = cleanFileNameForTitle(raw ?: "photo_$n")
            currentProgressIndex = n
            totalProgressCount = total
            currentPhotoTitle = title

            while (isPaused && !isStopped) delay(500L)
            if (isStopped) return BatchFileResult(false, true, "Batch stopped by user")
            updateProgressNotification(n, total, title)

            tempFile = copyUriToTempFile(uri, raw, index)
            if (tempFile == null || !tempFile!!.exists()) {
                return BatchFileResult(false, false, "Failed to access selected file")
            }

            val mime = getMimeTypeFromUri(uri) ?: "application/octet-stream"
            val hash = calculateSha256(tempFile!!)
            val image = mime.lowercase().startsWith("image/")
            val wh = if (image) detectResolution(uri) else Pair(0, 0)
            val colors = if (image) {
                try {
                    PaletteExtractor.extractColorsFromUri(this, uri).hexList
                } catch (e: Exception) {
                    Log.w(TAG, "Palette extraction failed for $title", e)
                    emptyList()
                }
            } else emptyList()

            val name = raw ?: tempFile!!.name
            val type = if (image) {
                when {
                    batchWallpaperType.equals("Phone", true) -> "Phone"
                    batchWallpaperType.equals("Desktop/Tablet", true) || batchWallpaperType.equals("Desktop", true) -> "Desktop/Tablet"
                    wh.first >= wh.second -> "Desktop/Tablet"
                    else -> "Phone"
                }
            } else "File"

            val meta = WallpaperMetadata(
                title = title,
                category = batchCategory.ifBlank { "Uncategorized" },
                tags = parsedTags,
                resolution = if (image) "${wh.first}x${wh.second}" else "",
                aspectRatio = if (image) computeAspectRatioString(wh.first, wh.second) else "",
                sizeBytes = tempFile!!.length(),
                colors = colors,
                description = "",
                author = batchAuthor.ifBlank { CharacterAuthorUtils.getRandomCharacterName() },
                timestamp = System.currentTimeMillis(),
                wallpaperType = type,
                sha256 = hash
            )

            val duplicate = withTimeout(DUPLICATE_CHECK_TIMEOUT_MS) {
                wallpaperRepository.findPossibleDuplicate(chatId, name, tempFile!!.length(), mime, hash)
            }
            if (duplicate != null) {
                return BatchFileResult(false, true, "Duplicate skipped; already exists in Telegram")
            }

            // IMPORTANT: first terminal event wins. We do not wait for the callbackFlow
            // itself to close after Telegram reports a failure.
            val terminal = withTimeout(PER_FILE_UPLOAD_TIMEOUT_MS) {
                wallpaperRepository.uploadWallpaper(chatId, tempFile!!.absolutePath, name, mime, meta)
                    .first { event ->
                        event is TelegramUploadEvent.Succeeded || event is TelegramUploadEvent.Failed
                    }
            }

            return when (terminal) {
                is TelegramUploadEvent.Succeeded -> {
                    wallpaperRepository.saveUploadedWallpaperToDb(terminal.document)
                    BatchFileResult(true, false, null)
                }
                is TelegramUploadEvent.Failed -> {
                    BatchFileResult(false, false, terminal.message ?: "Upload failed; skipped")
                }
                is TelegramUploadEvent.Progress -> BatchFileResult(false, false, "Upload ended without a terminal result")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.e(TAG, "File #$n timed out; continuing", e)
            return BatchFileResult(false, false, "Timed out; skipped and continued to the next file")
        } catch (e: Exception) {
            Log.e(TAG, "File #$n failed; continuing", e)
            return BatchFileResult(false, false, e.message ?: "Upload failed; skipped")
        } finally {
            tempFile?.delete()
        }
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(file.inputStream()).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun stopForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun updateProgressNotification(current: Int, total: Int, currentFileName: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = buildProgressNotification(current, total, currentFileName)
        notificationManager.notify(PROGRESS_NOTIFICATION_ID, notification)
    }

    private fun buildProgressNotification(current: Int, total: Int, currentFileName: String): android.app.Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            100,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseOrResumeAction = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        val pauseOrResumeTitle = if (isPaused) "Resume" else "Pause"
        val pauseOrResumeIcon = if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause

        val pauseOrResumeIntent = Intent(this, MassUploadService::class.java).apply {
            action = pauseOrResumeAction
        }
        val pauseOrResumePendingIntent = PendingIntent.getService(
            this,
            101,
            pauseOrResumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MassUploadService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            102,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val titleText = if (isPaused) {
            "Uploading images... (Paused - $current/$total)"
        } else {
            "Uploading images... ($current/$total)"
        }

        val contentText = if (isPaused) {
            "Paused at $currentFileName"
        } else {
            "Uploading $currentFileName"
        }

        return NotificationCompat.Builder(this, PROGRESS_CHANNEL_ID)
            .setContentTitle(titleText)
            .setContentText(contentText)
            .setSmallIcon(if (isPaused) android.R.drawable.ic_media_pause else android.R.drawable.stat_sys_upload)
            .setContentIntent(contentPendingIntent)
            .setOngoing(!isPaused)
            .setOnlyAlertOnce(true)
            .setProgress(total, current, current == 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(pauseOrResumeIcon, pauseOrResumeTitle, pauseOrResumePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    private fun showFinalResultNotification(
        total: Int,
        successCount: Int,
        failureCount: Int,
        errorDetails: List<String>
    ) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            103,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setSmallIcon(if (failureCount == 0) android.R.drawable.stat_sys_upload_done else android.R.drawable.stat_notify_error)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (failureCount == 0) {
            builder.setContentTitle("Batch Upload Complete!")
                .setContentText("Successfully uploaded all $successCount wallpapers to Telegram channel!")
        } else {
            builder.setContentTitle("Batch Upload Finished with Errors")
                .setContentText("$successCount uploaded successfully, $failureCount failed.")

            val bigText = StringBuilder()
                .append("Upload summary:\n")
                .append("• Successful: $successCount / $total\n")
                .append("• Failed: $failureCount / $total\n\n")
                .append("Error details:\n")
                .append(errorDetails.joinToString("\n"))
                .toString()

            builder.setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
        }

        notificationManager.notify(RESULT_NOTIFICATION_ID, builder.build())
        Log.i(TAG, "BATCH_UPLOAD_RESULT: " + errorDetails.joinToString(" | "))
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val progressChannel = NotificationChannel(
                PROGRESS_CHANNEL_ID,
                "Batch Upload Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time progress of batch wallpaper uploads with controls"
            }

            val resultChannel = NotificationChannel(
                RESULT_CHANNEL_ID,
                "Batch Upload Results",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when batch wallpaper upload completes or encounters errors"
            }

            notificationManager.createNotificationChannel(progressChannel)
            notificationManager.createNotificationChannel(resultChannel)
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) {
                            name = cursor.getString(index)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying file name from URI", e)
            }
        }
        if (name.isNullOrBlank()) {
            name = uri.path?.let { File(it).name }
        }
        return name?.takeIf { it.isNotBlank() }
    }

    private fun cleanFileNameForTitle(fileName: String): String {
        val dotIndex = fileName.lastIndexOf('.')
        return if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
    }

    private fun getMimeTypeFromUri(uri: Uri): String? {
        return if (uri.scheme == "content") {
            contentResolver.getType(uri)
        } else {
            val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
            if (extension != null) {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
            } else null
        }
    }

    private fun detectResolution(uri: Uri): Pair<Int, Int> {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, options)
                val w = if (options.outWidth > 0) options.outWidth else 1080
                val h = if (options.outHeight > 0) options.outHeight else 1920
                Pair(w, h)
            } ?: Pair(1080, 1920)
        } catch (e: Exception) {
            Pair(1080, 1920)
        }
    }

    private fun computeAspectRatioString(width: Int, height: Int): String {
        return try {
            if (width > 0 && height > 0) {
                val g = gcd(width, height)
                "${width / g}:${height / g}"
            } else "9:16"
        } catch (e: Exception) {
            "9:16"
        }
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val t = y
            y = x % y
            x = t
        }
        return x
    }

    private fun copyUriToTempFile(uri: Uri, customFileName: String?, index: Int): File? {
        return try {
            val safeName = customFileName?.takeIf { it.isNotBlank() } ?: "mass_${System.currentTimeMillis()}_$index.jpg"
            val tempFile = File(cacheDir, "mass_${index}_$safeName")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (tempFile.exists() && tempFile.length() > 0) tempFile else null
        } catch (e: Exception) {
            Log.e(TAG, "Error copying URI to temp file", e)
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
