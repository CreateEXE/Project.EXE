package com.android.exe

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.util.Log

class AccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AccessibilityService"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) {
            Log.d(TAG, "Accessibility event: ${event.eventType}")
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }
}
