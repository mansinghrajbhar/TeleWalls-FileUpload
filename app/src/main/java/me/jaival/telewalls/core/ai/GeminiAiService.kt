package me.jaival.telewalls.core.ai
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
data class GeminiFileAnalysis(val category: String, val description: String, val tags: List<String>, val imageLabels: List<String> = emptyList())
@Singleton
class GeminiAiService @Inject constructor(private val apiKeyStore: GeminiApiKeyStore, private val gson: Gson) {
    fun hasApiKey(): Boolean = apiKeyStore.getApiKey() != null

    suspend fun analyzeFile(context: Context, uri: Uri, fileName: String, mimeType: String): Result<GeminiFileAnalysis> = withContext(Dispatchers.IO) {
        val key = apiKeyStore.getApiKey() ?: return@withContext Result.failure(IllegalStateException("Gemini API key is not configured"))
        try {
            val prompt = "Analyze this file for a personal file/wallpaper library. Return ONLY valid JSON with exactly these fields: category, description, tags (3-8 lowercase tags), imageLabels (3-10 lowercase visual labels, [] for non-images). Category should be useful, such as Documents, Photos, Videos, Audio, Apps, Archives, Wallpapers, Work, Personal or Other. File name: $fileName. MIME type: $mimeType."
            val input = com.google.gson.JsonArray()
            input.add(gson.fromJson("""{"type":"text","text":${gson.toJson(prompt)}}""", JsonObject::class.java))
            if (mimeType.startsWith("image/")) {
                val image = readImageAsJpegBase64(context, uri)
                    ?: return@withContext Result.failure(IllegalArgumentException("Could not read image for AI analysis"))
                input.add(gson.fromJson(
                    """{"type":"image","data":${gson.toJson(image)},"mime_type":"image/jpeg"}""",
                    JsonObject::class.java
                ))
            }
            val body = JsonObject().apply {
                addProperty("model", "gemini-3.8-flash")
                add("input", input)
                addProperty("store", false)
            }
            val connection = (URL("https://generativelanguage.googleapis.com/v1beta/interactions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 20000; readTimeout = 60000
                setRequestProperty("Content-Type", "application/json"); setRequestProperty("x-goog-api-key", key)
            }
            connection.outputStream.use { it.write(gson.toJson(body).toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) return@withContext Result.failure(IllegalStateException("Gemini API error $code"))
            val root = gson.fromJson(response, JsonObject::class.java)
            val output = root.getAsJsonArray("steps")?.asSequence()?.map { it.asJsonObject }?.filter { it.get("type")?.asString == "model_output" }?.flatMap { it.getAsJsonArray("content").asSequence() }?.mapNotNull { it.asJsonObject.get("text")?.asString }?.lastOrNull()
                ?: root.get("output_text")?.asString ?: return@withContext Result.failure(IllegalStateException("Gemini returned no analysis"))
            val cleaned = output.trim().replace(String.fromCharCode(96).toString(), "").removePrefix("json").trim()
            val json = gson.fromJson(cleaned, JsonObject::class.java)
            Result.success(GeminiFileAnalysis(
                category = json.get("category")?.asString?.trim().orEmpty().ifBlank { "Other" },
                description = json.get("description")?.asString?.trim().orEmpty(),
                tags = json.getAsJsonArray("tags")?.map { it.asString.trim().lowercase() } ?: emptyList(),
                imageLabels = json.getAsJsonArray("imageLabels")?.map { it.asString.trim().lowercase() } ?: emptyList()
            ))
        } catch (e: Exception) { Result.failure(e) }
    }
    private fun readImageAsJpegBase64(context: Context, uri: Uri): String? {
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
        return try {
            val scaled = if (bitmap.width > 1600 || bitmap.height > 1600) {
                val scale = minOf(1600f / bitmap.width, 1600f / bitmap.height)
                android.graphics.Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else bitmap
            ByteArrayOutputStream().use { out ->
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }.also { if (scaled !== bitmap) scaled.recycle() }
        } finally { bitmap.recycle() }
    }
}