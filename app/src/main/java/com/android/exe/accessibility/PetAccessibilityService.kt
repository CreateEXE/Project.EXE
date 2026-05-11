package com.android.exe.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// ─── Screen context data ──────────────────────────────────────────────────────

data class ScreenContext(
    val activePackage: String,
    val summary: String,
    val triggerType: String,
    val timestampMs: Long = System.currentTimeMillis()
)

// ─── Accessibility service ────────────────────────────────────────────────────

class PetAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PetA11y"
        private val _screenFlow = MutableSharedFlow<ScreenContext>(
            extraBufferCapacity = 8,
            replay = 0
        )
        val screenFlow: SharedFlow<ScreenContext> = _screenFlow.asSharedFlow()

        // Singleton reference so PetForegroundService can check if it's bound
        @Volatile var instance: PetAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() { /* required */ }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val pkg = event.packageName?.toString() ?: return
        // Ignore our own events
        if (pkg == "com.android.exe" || pkg == "com.android.exe.debug") return

        val triggerType = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "app_switch"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED    -> "text_input"
            AccessibilityEvent.TYPE_ANNOUNCEMENT         -> "announcement"
            else -> return
        }

        val summary = buildSummary(event)
        val ctx = ScreenContext(
            activePackage = pkg,
            summary       = summary,
            triggerType   = triggerType
        )

        _screenFlow.tryEmit(ctx)
    }

    // ─── Extract readable text from the accessibility tree ────────────────────

    private fun buildSummary(event: AccessibilityEvent): String {
        val sb = StringBuilder()

        // Window title
        val title = event.text.joinToString(" ").trim()
        if (title.isNotBlank()) sb.append("Title: $title. ")

        // Walk the root node for text content (max 400 chars)
        val root = rootInActiveWindow
        if (root != null) {
            extractText(root, sb, maxChars = 400)
            root.recycle()
        }

        return sb.toString().take(500)
    }

    private fun extractText(node: AccessibilityNodeInfo, sb: StringBuilder, maxChars: Int) {
        if (sb.length >= maxChars) return
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank() && text.length > 2) {
            sb.append(text).append(' ')
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            extractText(child, sb, maxChars)
            child.recycle()
        }
    }
}
