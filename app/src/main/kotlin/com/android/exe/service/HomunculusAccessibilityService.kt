package com.android.exe.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * HomunculusAccessibilityService: System bridge for device context awareness.
 *
 * Responsibilities:
 * 1. Listen to accessibility events (screen content, notifications, app changes)
 * 2. Emit ScreenContext events to the soul system
 * 3. Enable Awareness oni to understand user context
 *
 * Permissions required:
 * - android.permission.BIND_ACCESSIBILITY_SERVICE
 * - Must be enabled manually in Settings > Accessibility
 */
class HomunculusAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "HomunculusAccessibilityService"
    }

    override fun onServiceConnected() {
        Log.i(TAG, "Accessibility service connected")
        configureServiceInfo()
    }

    private fun configureServiceInfo() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString() ?: "unknown"
                Log.d(TAG, "Window changed: $packageName")
                // Emit to soul.awareness
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                Log.d(TAG, "Notification: ${event.contentDescription}")
                // Emit to soul.awareness
            }
            else -> {
                Log.v(TAG, "Accessibility event type: ${event.eventType}")
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }
}
