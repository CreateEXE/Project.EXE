package com.projectexe.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class UserPreferenceManager(context: Context) {
    companion object {
        private const val PREFS="exe_user_prefs"; private const val KEY_NAME="user_name"
        private const val KEY_FIRST="first_run_complete"; private const val KEY_LAST="last_session_ts"
        private const val KEY_VOICE="voice_input_enabled"; private const val KEY_LEN="response_length"
        const val LEN_CONCISE="concise"; const val LEN_BALANCED="balanced"; const val LEN_DETAILED="detailed"
    }
    private val p: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var userName: String get()=p.getString(KEY_NAME,"")?:""; set(v)=p.edit{putString(KEY_NAME,v.trim().take(32))}
    val promptAddressName get()=userName.ifEmpty{"there"}
    var isFirstRunComplete: Boolean get()=p.getBoolean(KEY_FIRST,false); set(v)=p.edit{putBoolean(KEY_FIRST,v)}
    var lastSessionTimestamp: Long   get()=p.getLong(KEY_LAST,0L);        set(v)=p.edit{putLong(KEY_LAST,v)}
    fun recordSessionStart() { lastSessionTimestamp=System.currentTimeMillis()/1000 }

    fun getSessionGap(): SessionGap {
        val l=lastSessionTimestamp; if(l==0L) return SessionGap.FIRST_TIME
        return when(System.currentTimeMillis()/1000-l) {
            in 0..7200     -> SessionGap.RECENT
            in 7201..86400 -> SessionGap.TODAY
            in 86401..604800 -> SessionGap.FEW_DAYS
            else           -> SessionGap.LONG_ABSENCE
        }
    }

    var responseLengthPref: String get()=p.getString(KEY_LEN,LEN_BALANCED)?:LEN_BALANCED
        set(v) { if(v in setOf(LEN_CONCISE,LEN_BALANCED,LEN_DETAILED)) p.edit{putString(KEY_LEN,v)} }

    fun responseLengthInstruction() = when(responseLengthPref) {
        LEN_CONCISE  -> "Keep responses to 1-2 sentences. Be brief and direct."
        LEN_DETAILED -> "Use up to 6 sentences when depth is warranted."
        else         -> "Keep responses to 2-4 sentences unless more depth is needed."
    }

    var voiceInputEnabled: Boolean get()=p.getBoolean(KEY_VOICE,false); set(v)=p.edit{putBoolean(KEY_VOICE,v)}
}

enum class SessionGap { FIRST_TIME, RECENT, TODAY, FEW_DAYS, LONG_ABSENCE }
