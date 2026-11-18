package com.minids.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.minids.util.KeyCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.store by preferencesDataStore("settings")

data class AppSettings(
    val apiKey: String = "",
    val contextTokens: Int = 131072,
    val thinking: Boolean = false,
    val search: Boolean = false,
    val dark: Boolean = false,
    val modelName: String? = null
)

class PrefsStore(private val ctx: Context) {

    private val apiKeyEnc = stringPreferencesKey("api_key_enc")
    private val apiKeyLegacy = stringPreferencesKey("api_key")
    private val ctxTokens = intPreferencesKey("context_tokens")
    private val thinking = booleanPreferencesKey("thinking")
    private val search = booleanPreferencesKey("search")
    private val dark = booleanPreferencesKey("dark")
    private val modelName = stringPreferencesKey("model_name")

    val flow: Flow<AppSettings> = ctx.store.data.map {
        val enc = it[apiKeyEnc].orEmpty()
        val plain = it[apiKeyLegacy].orEmpty()
        AppSettings(
            apiKey = if (enc.isNotBlank()) KeyCrypto.decrypt(enc) else plain,
            contextTokens = it[ctxTokens] ?: 131072,
            thinking = it[thinking] ?: false,
            search = it[search] ?: false,
            dark = it[dark] ?: false,
            modelName = it[modelName]?.takeIf { n -> n.isNotBlank() }
        )
    }.flowOn(Dispatchers.IO)

    /** 老版本明文 Key 迁移到加密存储。 */
    suspend fun migrateLegacy() {
        ctx.store.edit { p ->
            val legacy = p[apiKeyLegacy]
            if (!legacy.isNullOrBlank()) {
                p[apiKeyEnc] = KeyCrypto.encrypt(legacy)
                p.remove(apiKeyLegacy)
            }
        }
    }

    suspend fun setApiKey(v: String) {
        val enc = withContext(Dispatchers.IO) { KeyCrypto.encrypt(v.trim()) }
        ctx.store.edit {
            it[apiKeyEnc] = enc
            it.remove(apiKeyLegacy)
        }
    }

    suspend fun setContextTokens(v: Int) {
        ctx.store.edit { it[ctxTokens] = v }
    }

    suspend fun setThinking(v: Boolean) {
        ctx.store.edit { it[thinking] = v }
    }

    suspend fun setSearch(v: Boolean) {
        ctx.store.edit { it[search] = v }
    }

    suspend fun setDark(v: Boolean) {
        ctx.store.edit { it[dark] = v }
    }

    suspend fun setModelName(v: String) {
        ctx.store.edit { it[modelName] = v }
    }
}
