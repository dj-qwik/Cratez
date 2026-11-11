package com.cratez.app.data

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom

private val Context.store by preferencesDataStore("cratez_settings")

data class AppSettings(
    val myCode: String = "",
    val myName: String = "",
    val writeTags: Boolean = true,
    val camelotKeys: Boolean = true,
    val onlineLookup: Boolean = true,
    val replaceLowResArt: Boolean = true,
    val beamVisible: Boolean = true,
    val secret: String = "",
    val relayUrl: String = Settings.DEFAULT_RELAY,
    val onlineSharing: Boolean = true,
)

class Settings(private val ctx: Context) {
    private object K {
        val code = stringPreferencesKey("my_code")
        val name = stringPreferencesKey("my_name")
        val writeTags = booleanPreferencesKey("write_tags")
        val camelot = booleanPreferencesKey("camelot")
        val online = booleanPreferencesKey("online")
        val art = booleanPreferencesKey("replace_art")
        val visible = booleanPreferencesKey("beam_visible")
        val secret = stringPreferencesKey("secret")
        val relay = stringPreferencesKey("relay_url")
        val onlineShare = booleanPreferencesKey("online_sharing")
    }

    val flow: Flow<AppSettings> = ctx.store.data.map { p ->
        AppSettings(
            myCode = p[K.code] ?: "",
            myName = p[K.name] ?: "",
            writeTags = p[K.writeTags] ?: true,
            camelotKeys = p[K.camelot] ?: true,
            onlineLookup = p[K.online] ?: true,
            replaceLowResArt = p[K.art] ?: true,
            beamVisible = p[K.visible] ?: true,
            secret = p[K.secret] ?: "",
            relayUrl = (p[K.relay] ?: "").ifBlank { DEFAULT_RELAY },
            onlineSharing = p[K.onlineShare] ?: true,
        )
    }

    suspend fun current() = flow.first()

    /** Creates this phone's friend code and default name the first time. */
    suspend fun ensureIdentity(): AppSettings {
        val s = current()
        if (s.myCode.isNotBlank() && s.secret.isNotBlank()) return s
        val name = (Build.MODEL ?: "DJ").let { "DJ ${it.takeLast(4)}" }
        val r = SecureRandom()
        val secret = (1..32).map { "0123456789abcdef"[r.nextInt(16)] }.joinToString("")
        ctx.store.edit {
            if ((it[K.code] ?: "").isBlank()) it[K.code] = newFriendCode()
            if ((it[K.name] ?: "").isBlank()) it[K.name] = name
            if ((it[K.secret] ?: "").isBlank()) it[K.secret] = secret
        }
        return current()
    }

    suspend fun setName(v: String) = ctx.store.edit { it[K.name] = v.trim().take(24) }
    suspend fun setWriteTags(v: Boolean) = ctx.store.edit { it[K.writeTags] = v }
    suspend fun setCamelot(v: Boolean) = ctx.store.edit { it[K.camelot] = v }
    suspend fun setOnline(v: Boolean) = ctx.store.edit { it[K.online] = v }
    suspend fun setReplaceArt(v: Boolean) = ctx.store.edit { it[K.art] = v }
    suspend fun setBeamVisible(v: Boolean) = ctx.store.edit { it[K.visible] = v }
    suspend fun setRelayUrl(v: String) = ctx.store.edit { it[K.relay] = v.trim().trimEnd('/') }
    suspend fun setOnlineSharing(v: Boolean) = ctx.store.edit { it[K.onlineShare] = v }

    companion object {
        /** Cloudflare Worker relay for sending tracks to friend codes over the internet. */
        const val DEFAULT_RELAY = "https://cratez-relay.djqwik.workers.dev"
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        fun newFriendCode(): String {
            val r = SecureRandom()
            fun part() = (1..4).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("")
            return "CRZ-${part()}-${part()}"
        }

        /** Accepts "crz7kq2x9ab", "CRZ-7KQ2-X9AB" or a cratez://friend QR link. */
        fun parseFriendCode(input: String): Pair<String, String?>? {
            val t = input.trim()
            var name: String? = null
            var raw = t
            if (t.startsWith("cratez://", true)) {
                val u = android.net.Uri.parse(t)
                raw = u.lastPathSegment ?: return null
                name = u.getQueryParameter("name")
            }
            val clean = raw.uppercase().replace(Regex("[^A-Z0-9]"), "").removePrefix("CRZ")
            if (clean.length != 8 || clean.any { it !in ALPHABET }) return null
            return "CRZ-${clean.take(4)}-${clean.drop(4)}" to name
        }
    }
}
