package com.android.exe

import android.content.Context
import android.content.SharedPreferences

object PreferencesManager {
    
    private const val PREF_NAME = "android_exe_prefs"
    private const val KEY_AVATAR_URI = "default_avatar_uri"
    private const val KEY_AVATAR_NAME = "default_avatar_name"
    private const val KEY_MODEL_URI = "default_model_uri"
    private const val KEY_MODEL_NAME = "default_model_name"
    private const val KEY_PET_NAME = "pet_name"
    
    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
    
    fun saveDefaultAvatar(context: Context, uri: String, name: String) {
        getPreferences(context).edit().apply {
            putString(KEY_AVATAR_URI, uri)
            putString(KEY_AVATAR_NAME, name)
            apply()
        }
    }
    
    fun getDefaultAvatarUri(context: Context): String? {
        return getPreferences(context).getString(KEY_AVATAR_URI, null)
    }
    
    fun getDefaultAvatarName(context: Context): String {
        return getPreferences(context).getString(KEY_AVATAR_NAME, "Not set") ?: "Not set"
    }
    
    fun clearDefaultAvatar(context: Context) {
        getPreferences(context).edit().apply {
            remove(KEY_AVATAR_URI)
            remove(KEY_AVATAR_NAME)
            apply()
        }
    }
    
    // Model
    fun saveDefaultModel(context: Context, uri: String, name: String) {
        getPreferences(context).edit().apply {
            putString(KEY_MODEL_URI, uri)
            putString(KEY_MODEL_NAME, name)
            apply()
        }
    }
    
    fun getDefaultModelUri(context: Context): String? {
        return getPreferences(context).getString(KEY_MODEL_URI, null)
    }
    
    fun getDefaultModelName(context: Context): String {
        return getPreferences(context).getString(KEY_MODEL_NAME, "Not set") ?: "Not set"
    }
    
    fun clearDefaultModel(context: Context) {
        getPreferences(context).edit().apply {
            remove(KEY_MODEL_URI)
            remove(KEY_MODEL_NAME)
            apply()
        }
    }
    
    // Pet name
    fun savePetName(context: Context, name: String) {
        getPreferences(context).edit().putString(KEY_PET_NAME, name).apply()
    }
    
    fun getPetName(context: Context): String {
        return getPreferences(context).getString(KEY_PET_NAME, "Fluffy") ?: "Fluffy"
    }
}
