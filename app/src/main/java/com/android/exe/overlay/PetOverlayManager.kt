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
        private const val OVERLAY_WIDTH_DP   = 160
        private const val OVERLAY_HEIGHT_DP  = 240
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
    private var debugPanel: DebugPanelView? = null      // NEW: debug display

    // ── Async jobs ─────────────────────────────────────────────────────────────
    private var bubbleJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Drag state ─────────────────────────────────────────────────────────────
    private var params: WindowManager.LayoutParams? = null
    private var dragging = false
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialX = 0
    private var initialY = 0

    // ─────────────────────────────────────────────────────────────────────────
    // Custom debug panel view
    // ─────────────────────────────────────────────────────────────────────────
    private inner class DebugPanelView(context: Context) : View(context) {
        private val debugLines = mutableListOf<String>()
        private val maxLines = 8
        private val paint = android.graphics.Paint().apply {
            color = Color.GREEN
            textSize = 28f  // 10sp in pixels
            typeface = android.graphics.Typeface.MONOSPACE
        }
        private val bgPaint = android.graphics.Paint().apply {
            color = Color.argb(200, 0, 0, 0)  // semi-transparent black
        }
        private val borderPaint = android.graphics.Paint().apply {
            color = Color.GREEN
            strokeWidth = 2f
            style = android.graphics.Paint.Style.STROKE
        }

        fun addLine(msg: String) {
            debugLines.add(msg)
            if (debugLines.size > maxLines) debugLines.removeAt(0)
            invalidate()
        }

        fun clear() {
            debugLines.clear()
            invalidate()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            
            val w = width.toFloat()
            val h = height.toFloat()
            
            // Background
            canvas.drawRect(0f, 0f, w, h, bgPaint)
            // Border
            canvas.drawRect(0f, 0f, w, h, borderPaint)
            
            // Text
            var y = 20f
            for (line in debugLines) {
                canvas.drawText(line, 8f, y, paint)
                y += 20f
            }
        }
    }

    fun addDebugLine(msg: String) {
        debugPanel?.addLine(msg)
    }

    fun clearDebugPanel() {
        debugPanel?.clear()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // attach() - Creates overlay with avatar view and debug panel
    // ─────────────────────────────────────────────────────────────────────────
    fun attach(avatarPath: String?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { attach(avatarPath) }
            return
        }
        if (overlayRoot != null) return

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
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 16
            y = 120
        }

        // ── Root container ──
        val root = object : FrameLayout(context) {
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true
        }.also { overlayRoot = it }
        root.setBackgroundColor(Color.TRANSPARENT)

        // ── Avatar WebView ──
        val avw = AvatarWebView(context).also { avatarView = it }
        avw.setBackgroundColor(Color.TRANSPARENT)
        avw.background?.alpha = 0
        
        // Listen for debug messages
        avw.listener = object : AvatarWebView.Listener {
            override fun onRendererReady() {
                addDebugLine("✅ READY")
            }
            override fun onModelLoaded(name: String) {
                addDebugLine("✅ Model OK")
            }
            override fun onModelError(error: String) {
                addDebugLine("❌ ${error.take(15)}")
            }
            override fun onDebugMessage(msg: String) {
                // Truncate long messages
                val short = msg.take(20)
                addDebugLine(short)
            }
        }
        
        root.addView(avw, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // ── Debug Panel (top-left corner, semi-transparent) ──
        val debugView = DebugPanelView(context).also { debugPanel = it }
        root.addView(debugView, FrameLayout.LayoutParams(
            250,  // width
            180,  // height
            Gravity.TOP or Gravity.START
        ).also { 
            it.leftMargin = 4
            it.topMargin = 4
        })
        
        addDebugLine("🎬 INIT")

        // ── Speech bubble ────
        val bubble = TextView(context).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            setBackgroundResource(android.R.drawable.toast_frame)
            textSize = 12f
            setPadding(16, 8, 16, 8)
            maxWidth = (BUBBLE_MAX_WIDTH_DP * density).toInt()
        }
        speechBubble = bubble
        root.addView(bubble, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).also { it.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL })

        // ── Gestures ─────────
        val gestureDetector = GestureDetector(context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    cycleSizeMode(); return true
                }
            })

        // ── Touch listener ────
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
        Log.i(TAG, "✅ Overlay attached (${w}×${h}px) with debug panel")
        addDebugLine("✅ OVERLAY UP")

        if (!avatarPath.isNullOrBlank()) {
            Log.d(TAG, "Loading avatar: $avatarPath")
            addDebugLine("→ LOAD")
            avw.loadModelFromPath(avatarPath)
        } else {
            Log.w(TAG, "No avatar path provided")
            addDebugLine("⚠️  No path")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // detach
    // ─────────────────────────────────────────────────────────────────────────
    fun detach() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { detach() }
            return
        }
        overlayRoot?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        avatarView?.destroy()
        avatarView   = null
        overlayRoot  = null
        speechBubble = null
        debugPanel   = null
        scope.cancel()
        Log.i(TAG, "Overlay detached")
    }

    fun isAttached() = overlayRoot != null

    // ─────────────────────────────────────────────────────────────────────────
    // Speech bubble
    // ─────────────────────────────────────────────────────────────────────────
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

    // ─────────────────────────────────────────────────────────────────────────
    // Size cycling (double-tap)
    // ─────────────────────────────────────────────────────────────────────────
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
            1    -> {
                avatarView?.setFraming("full")
                addDebugLine("FULL")
            }
            2    -> {
                avatarView?.setFraming("face")
                addDebugLine("FACE")
            }
            else -> {
                avatarView?.setFraming("bust")
                addDebugLine("BUST")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Delegated avatar controls
    // ─────────────────────────────────────────────────────────────────────────
    fun playExpression(emotion: PetEmotion) {
        addDebugLine("EXPR: ${emotion.vrmExpression}")
        avatarView?.playExpression(emotion)
    }
    
    fun resetExpression() {
        addDebugLine("RESET")
        avatarView?.resetExpression()
    }
    
    fun loadAvatar(path: String) {
        addDebugLine("LOAD: ${path.substringAfterLast("/").take(12)}")
        avatarView?.loadModelFromPath(path)
    }

    fun sayLlmThinking() {
        addDebugLine("🤔 LLM...")
    }

    fun sayLlmDone() {
        addDebugLine("✅ LLM OK")
    }

    fun sayLlmError(error: String) {
        addDebugLine("❌ LLM ERR")
    }
}
