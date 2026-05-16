package com.android.exe.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.*
import com.android.exe.ai.PetEmotion
import com.android.exe.rendering.AvatarWebView
import kotlinx.coroutines.*

class PetOverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "PetOverlay"
        private const val OVERLAY_WIDTH_DP  = 160
        private const val OVERLAY_HEIGHT_DP = 240
        private const val BUBBLE_MAX_WIDTH_DP = 220
    }

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val density = context.resources.displayMetrics.density
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Views ──────────────────────────────────────────────────────────────────
    private var overlayRoot: FrameLayout? = null
    var avatarView: AvatarWebView? = null
        private set

    private var speechBubble: TextView? = null
    private var bubbleJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Drag state ─────────────────────────────────────────────────────────────
    private var params: WindowManager.LayoutParams? = null
    private var dragging = false
    private var initialTouchX = 0f; private var initialTouchY = 0f
    private var initialX = 0;       private var initialY = 0

    // ── Attach ─────────────────────────────────────────────────────────────────

    /**
     * Creates the overlay window and the AvatarWebView inside it.
     *
     * MUST run on the main thread — WebView's constructor calls
     * new Handler(Looper.myLooper()) internally. If the calling thread has no
     * Looper (e.g. a Service worker thread or a coroutine dispatcher),
     * myLooper() returns null and Android throws:
     *   "Attempt to read from field 'MessageQueue Looper.mQueue' on a null object"
     *
     * This method enforces the main-thread requirement by re-posting to
     * mainHandler when called from any other thread.
     */
    fun attach(avatarPath: String?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // Called from a background thread / coroutine — re-dispatch to main.
            mainHandler.post { attach(avatarPath) }
            return
        }

        if (overlayRoot != null) return  // already attached

        val w = (OVERLAY_WIDTH_DP  * density).toInt()
        val h = (OVERLAY_HEIGHT_DP * density).toInt()

        params = WindowManager.LayoutParams(
            w, h,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 16
            y = 120
        }

        // Root container
        val root = FrameLayout(context).also { overlayRoot = it }
        root.setBackgroundColor(Color.TRANSPARENT)

        // Avatar WebView — safe: we are guaranteed on main thread here
        val avw = AvatarWebView(context).also { avatarView = it }
        avw.setBackgroundColor(Color.TRANSPARENT)
        avw.background?.alpha = 0

        root.addView(
            avw,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Speech bubble (hidden by default)
        val bubble = TextView(context).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            setBackgroundResource(android.R.drawable.toast_frame)
            textSize = 12f
            setPadding(16, 8, 16, 8)
            maxWidth = (BUBBLE_MAX_WIDTH_DP * density).toInt()
        }
        speechBubble = bubble
        root.addView(
            bubble,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
        )

        // Drag gesture detector
        val gestureDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    cycleSizeMode()
                    return true
                }
            }
        )

        root.setOnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    initialX = params!!.x;      initialY = params!!.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) dragging = true
                    if (dragging) {
                        params!!.x = initialX - dx
                        params!!.y = initialY - dy
                        try { wm.updateViewLayout(root, params) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) v.performClick()
                }
            }
            true
        }

        wm.addView(root, params)
        Log.i(TAG, "Overlay attached")

        if (!avatarPath.isNullOrBlank()) {
            avw.loadModelFromPath(avatarPath)
        }
    }

    // ── Detach ─────────────────────────────────────────────────────────────────

    fun detach() {
        // WindowManager operations must also be on main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { detach() }
            return
        }
        overlayRoot?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        avatarView?.destroy()
        avatarView  = null
        overlayRoot = null
        speechBubble = null
        scope.cancel()
        Log.i(TAG, "Overlay detached")
    }

    fun isAttached() = overlayRoot != null

    // ── Speech bubble ──────────────────────────────────────────────────────────

    fun showSpeechBubble(text: String, durationMs: Long = 5000L) {
        bubbleJob?.cancel()
        speechBubble?.let { b ->
            b.text = text
            b.visibility = View.VISIBLE
            bubbleJob = scope.launch {
                delay(durationMs)
                b.visibility = View.GONE
            }
        }
    }

    fun hideSpeechBubble() {
        bubbleJob?.cancel()
        speechBubble?.visibility = View.GONE
    }

    // ── Size cycling ───────────────────────────────────────────────────────────

    private var sizeMode = 0

    private fun cycleSizeMode() {
        sizeMode = (sizeMode + 1) % 3
        val (w, h) = when (sizeMode) {
            1    -> Pair(240, 360)
            2    -> Pair(100, 150)
            else -> Pair(OVERLAY_WIDTH_DP, OVERLAY_HEIGHT_DP)
        }
        params?.let { p ->
            p.width  = (w * density).toInt()
            p.height = (h * density).toInt()
            overlayRoot?.let { root ->
                try { wm.updateViewLayout(root, p) } catch (_: Exception) {}
            }
        }
        when (sizeMode) {
            1 -> avatarView?.setFraming("full")
            2 -> avatarView?.setFraming("face")
            else -> avatarView?.setFraming("bust")
        }
    }

    // ── Avatar controls ────────────────────────────────────────────────────────

    fun playExpression(emotion: PetEmotion) = avatarView?.playExpression(emotion)
    fun resetExpression()                   = avatarView?.resetExpression()
    fun loadAvatar(path: String)            = avatarView?.loadModelFromPath(path)
}
