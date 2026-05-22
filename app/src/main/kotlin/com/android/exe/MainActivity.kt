// File: app/src/main/kotlin/com/android/exe/MainApplication.kt
package com.android.exe

import android.app.Application
import android.util.Log

class MainApplication : Application() {

    companion object {
        private const val TAG = "MainApplication"
        private lateinit var instance: MainApplication

        fun getInstance(): MainApplication = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "MainApplication initialized")

        // LlamaBridge's companion object handles loading "llama-android"
        // lazily on first use. We just warm it up here so any link error
        // surfaces at startup rather than mid-session.
        try {
            LlamaBridge.ensureLibrary()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing llama runtime (continuing)", e)
        }
    }

    override fun onTerminate() {
        Log.d(TAG, "MainApplication terminating")
        super.onTerminate()
    }
}
