package me.jaival.telewalls.core.ai
import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class GeminiApiKeyStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context, "gemini_ai_secure", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    fun getApiKey(): String? = prefs.getString(API_KEY, null)?.takeIf { it.isNotBlank() }
    fun setApiKey(value: String) { prefs.edit().putString(API_KEY, value.trim()).apply() }
    fun clearApiKey() { prefs.edit().remove(API_KEY).apply() }
    companion object { private const val API_KEY = "gemini_api_key" }
}