package com.projectexe.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class UserPreferenceManager(context: Context) {
    companion object {
        private const val PREFS = "exe_user_prefs"
        private const val KEY_NAME    = "user_name"
        private const val KEY_FIRST   = "first_run_complete"
        private const val KEY_LAST    = "last_session_ts"
        private const val KEY_VOICE   = "voice_input_enabled"
        private const val KEY_LEN     = "response_length"

        // Engine / dual-LLM
        private const val KEY_ENGINE_MODE  = "engine_mode"          // auto | online | offline
        private const val KEY_API_KEY      = "openrouter_api_key"
        private const val KEY_MODEL        = "openrouter_model"
        private const val KEY_TOOLS_ON     = "tools_enabled"
        private const val KEY_GGUF_URI     = "gguf_uri"
        private const val KEY_GGUF_NAME    = "gguf_name"
        private const val KEY_GGUF_CTX     = "gguf_ctx"
        // Tool config
        private const val KEY_WX_LAT       = "weather_lat"
        private const val KEY_WX_LON       = "weather_lon"

        const val LEN_CONCISE  = "concise"
        const val LEN_BALANCED = "balanced"
        const val LEN_DETAILED = "detailed"

        const val MODE_AUTO    = "auto"
        const val MODE_ONLINE  = "online"
        const val MODE_OFFLINE = "offline"
    }

    private val p: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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

    // ---- Dual-LLM / engine ----
    var engineMode: String
        get() = p.getString(KEY_ENGINE_MODE, MODE_AUTO) ?: MODE_AUTO
        set(v) { if (v in setOf(MODE_AUTO, MODE_ONLINE, MODE_OFFLINE)) p.edit { putString(KEY_ENGINE_MODE, v) } }

    var apiKeyOverride: String
        get() = p.getString(KEY_API_KEY, "") ?: ""
        set(v) = p.edit { putString(KEY_API_KEY, v.trim()) }

    var modelOverride: String
        get() = p.getString(KEY_MODEL, "") ?: ""
        set(v) = p.edit { putString(KEY_MODEL, v.trim()) }

    var toolsEnabled: Boolean
        get() = p.getBoolean(KEY_TOOLS_ON, true); set(v) = p.edit { putBoolean(KEY_TOOLS_ON, v) }

    var ggufUri: String
        get() = p.getString(KEY_GGUF_URI, "") ?: ""
        set(v) = p.edit { putString(KEY_GGUF_URI, v) }
    var ggufName: String
        get() = p.getString(KEY_GGUF_NAME, "") ?: ""
        set(v) = p.edit { putString(KEY_GGUF_NAME, v) }
    var ggufContextSize: Int
        get() = p.getInt(KEY_GGUF_CTX, 2048)
        set(v) = p.edit { putInt(KEY_GGUF_CTX, v.coerceIn(512, 8192)) }

    // ---- Weather ----
    var weatherLat: Float
        get() = p.getFloat(KEY_WX_LAT, Float.NaN); set(v) = p.edit { putFloat(KEY_WX_LAT, v) }
    var weatherLon: Float
        get() = p.getFloat(KEY_WX_LON, Float.NaN); set(v) = p.edit { putFloat(KEY_WX_LON, v) }
    fun hasWeatherCoords() = !weatherLat.isNaN() && !weatherLon.isNaN()
}

enum class SessionGap { FIRST_TIME, RECENT, TODAY, FEW_DAYS, LONG_ABSENCE }
