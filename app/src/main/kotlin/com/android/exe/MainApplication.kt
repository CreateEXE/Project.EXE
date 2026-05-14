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

        // Try to load native library, but don't crash if it fails
        try {
            initializeLlamaRuntime()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing llama runtime (continuing)", e)
        }
    }

    private fun initializeLlamaRuntime() {
        try {
            System.loadLibrary("llama")
            Log.d(TAG, "Llama native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Llama native library not available - app will continue")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error loading llama", e)
        }
    }

    override fun onTerminate() {
        Log.d(TAG, "MainApplication terminating")
        super.onTerminate()
    }
}
