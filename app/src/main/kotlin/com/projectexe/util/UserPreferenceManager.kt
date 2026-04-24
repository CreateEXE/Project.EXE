package com.projectexe.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * All persisted user/runtime configuration. Two LLM "roles" are supported:
 *   ROLE_PERSONA  - the in-character creative voice (smaller / faster).
 *   ROLE_FACTUAL  - the analytical fact-check / augmentation voice (larger / accurate).
 *
 * Each role independently picks online (OpenRouter) or offline (local GGUF).
 */
class UserPreferenceManager(context: Context) {

    enum class Role(val key: String, val slot: Int) {
        PERSONA("persona", 0),
        FACTUAL("factual", 1)
    }

    companion object {
        private const val PREFS = "exe_user_prefs"
        // user
        private const val KEY_NAME    = "user_name"
        private const val KEY_FIRST   = "first_run_complete"
        private const val KEY_LAST    = "last_session_ts"
        private const val KEY_VOICE   = "voice_input_enabled"
        private const val KEY_LEN     = "response_length"
        // pipeline
        private const val KEY_USE_PIPELINE = "use_dual_pipeline"
        private const val KEY_TOOLS_ON     = "tools_enabled"
        // weather
        private const val KEY_WX_LAT       = "weather_lat"
        private const val KEY_WX_LON       = "weather_lon"
        // character card
        private const val KEY_CARD_URI     = "character_card_uri"
        private const val KEY_CARD_NAME    = "character_card_name"
        // shared online key (one OpenRouter account, two model ids)
        private const val KEY_API_KEY      = "openrouter_api_key"

        const val LEN_CONCISE  = "concise"
        const val LEN_BALANCED = "balanced"
        const val LEN_DETAILED = "detailed"

        const val MODE_AUTO    = "auto"
        const val MODE_ONLINE  = "online"
        const val MODE_OFFLINE = "offline"
    }

    private val p: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- user identity ----------
    var userName: String
        get() = p.getString(KEY_NAME, "") ?: ""
        set(v) = p.edit { putString(KEY_NAME, v.trim().take(32)) }
    val promptAddressName get() = userName.ifEmpty { "there" }

    var isFirstRunComplete: Boolean
        get() = p.getBoolean(KEY_FIRST, false); set(v) = p.edit { putBoolean(KEY_FIRST, v) }
    var lastSessionTimestamp: Long
        get() = p.getLong(KEY_LAST, 0L);        set(v) = p.edit { putLong(KEY_LAST, v) }
    fun recordSessionStart() { lastSessionTimestamp = System.currentTimeMillis() / 1000 }

    fun getSessionGap(): SessionGap {
        val l = lastSessionTimestamp; if (l == 0L) return SessionGap.FIRST_TIME
        return when (System.currentTimeMillis() / 1000 - l) {
            in 0..7200       -> SessionGap.RECENT
            in 7201..86400   -> SessionGap.TODAY
            in 86401..604800 -> SessionGap.FEW_DAYS
            else             -> SessionGap.LONG_ABSENCE
        }
    }

    var responseLengthPref: String
        get() = p.getString(KEY_LEN, LEN_BALANCED) ?: LEN_BALANCED
        set(v) { if (v in setOf(LEN_CONCISE, LEN_BALANCED, LEN_DETAILED)) p.edit { putString(KEY_LEN, v) } }

    fun responseLengthInstruction() = when (responseLengthPref) {
        LEN_CONCISE  -> "Keep responses to 1-2 sentences. Be brief and direct."
        LEN_DETAILED -> "Use up to 6 sentences when depth is warranted."
        else         -> "Keep responses to 2-4 sentences unless more depth is needed."
    }

    var voiceInputEnabled: Boolean
        get() = p.getBoolean(KEY_VOICE, false); set(v) = p.edit { putBoolean(KEY_VOICE, v) }

    // ---------- pipeline / tools ----------
    var useDualPipeline: Boolean
        get() = p.getBoolean(KEY_USE_PIPELINE, true);  set(v) = p.edit { putBoolean(KEY_USE_PIPELINE, v) }
    var toolsEnabled: Boolean
        get() = p.getBoolean(KEY_TOOLS_ON, true);      set(v) = p.edit { putBoolean(KEY_TOOLS_ON, v) }

    // ---------- shared OpenRouter API key ----------
    var apiKeyOverride: String
        get() = p.getString(KEY_API_KEY, "") ?: ""
        set(v) = p.edit { putString(KEY_API_KEY, v.trim()) }

    // ---------- per-role config ----------
    fun engineMode(r: Role): String =
        p.getString("${r.key}_engine_mode", MODE_AUTO) ?: MODE_AUTO
    fun setEngineMode(r: Role, v: String) {
        if (v in setOf(MODE_AUTO, MODE_ONLINE, MODE_OFFLINE))
            p.edit { putString("${r.key}_engine_mode", v) }
    }

    fun modelOverride(r: Role): String = p.getString("${r.key}_model", "") ?: ""
    fun setModelOverride(r: Role, v: String) = p.edit { putString("${r.key}_model", v.trim()) }

    fun ggufUri(r: Role): String  = p.getString("${r.key}_gguf_uri", "") ?: ""
    fun ggufName(r: Role): String = p.getString("${r.key}_gguf_name", "") ?: ""
    fun setGguf(r: Role, uri: String, name: String) =
        p.edit { putString("${r.key}_gguf_uri", uri); putString("${r.key}_gguf_name", name) }
    fun clearGguf(r: Role) =
        p.edit { remove("${r.key}_gguf_uri"); remove("${r.key}_gguf_name") }

    fun ggufContextSize(r: Role): Int = p.getInt("${r.key}_gguf_ctx", 2048)
    fun setGgufContextSize(r: Role, v: Int) =
        p.edit { putInt("${r.key}_gguf_ctx", v.coerceIn(512, 8192)) }

    // ---------- character card ----------
    var characterCardUri: String
        get() = p.getString(KEY_CARD_URI, "") ?: ""
        set(v) = p.edit { putString(KEY_CARD_URI, v) }
    var characterCardName: String
        get() = p.getString(KEY_CARD_NAME, "") ?: ""
        set(v) = p.edit { putString(KEY_CARD_NAME, v) }

    // ---------- weather ----------
    var weatherLat: Float
        get() = p.getFloat(KEY_WX_LAT, Float.NaN); set(v) = p.edit { putFloat(KEY_WX_LAT, v) }
    var weatherLon: Float
        get() = p.getFloat(KEY_WX_LON, Float.NaN); set(v) = p.edit { putFloat(KEY_WX_LON, v) }
    fun hasWeatherCoords() = !weatherLat.isNaN() && !weatherLon.isNaN()
}

enum class SessionGap { FIRST_TIME, RECENT, TODAY, FEW_DAYS, LONG_ABSENCE }
