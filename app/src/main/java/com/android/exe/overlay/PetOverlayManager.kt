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
        private const val TAG               = "PetOverlay"
        private const val OVERLAY_WIDTH_DP  = 160
        private const val OVERLAY_HEIGHT_DP = 240
        private const val BUBBLE_MAX_DP     = 220
    }

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density    = context.resources.displayMetrics.density
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayRoot:  FrameLayout?  = null
    var avatarView: AvatarWebView? = null; private set
    private var speechBubble: TextView?     = null
    private var debugPanel:   DebugPanelView? = null

    private var params: WindowManager.LayoutParams? = null
    private var motionController: PetMotionController? = null

    private var bubbleJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Drag state ─────────────────────────────────────────────────────────────
    private var dragging     = false
    private var touchX0      = 0f;  private var touchY0 = 0f
    private var paramX0      = 0;   private var paramY0 = 0

    // ── Debug panel ────────────────────────────────────────────────────────────
    private inner class DebugPanelView(ctx: Context) : android.view.View(ctx) {
        private val lines = mutableListOf<String>()
        private val paint = android.graphics.Paint().apply {
            color = Color.GREEN; textSize = 28f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        private val bg = android.graphics.Paint().apply { color = Color.argb(200, 0, 0, 0) }
        private val border = android.graphics.Paint().apply {
            color = Color.GREEN; strokeWidth = 2f
            style = android.graphics.Paint.Style.STROKE
        }
        fun add(msg: String) { lines.add(msg); if (lines.size > 8) lines.removeAt(0); invalidate() }
        fun clear() { lines.clear(); invalidate() }
        override fun onDraw(c: android.graphics.Canvas) {
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), border)
            lines.forEachIndexed { i, s -> c.drawText(s, 8f, 20f + i * 20f, paint) }
        }
    }

    fun addDebugLine(msg: String) { debugPanel?.add(msg) }
    fun clearDebugPanel()         { debugPanel?.clear() }

    // ── attach ─────────────────────────────────────────────────────────────────
    fun attach(avatarPath: String?) {
        if (Looper.myLooper() != Looper.getMainLooper()) { mainHandler.post { attach(avatarPath) }; return }
        if (overlayRoot != null) return

        val w = (OVERLAY_WIDTH_DP  * density).toInt()
        val h = (OVERLAY_HEIGHT_DP * density).toInt()

        val lp = WindowManager.LayoutParams(
            w, h,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.END; x = 16; y = 120 }
        params = lp

        val root = object : FrameLayout(context) {
            override fun onInterceptTouchEvent(ev: MotionEvent) = true
        }.also { overlayRoot = it }
        root.setBackgroundColor(Color.TRANSPARENT)

        // Avatar WebView
        val avw = AvatarWebView(context).also { avatarView = it }
        avw.setBackgroundColor(Color.TRANSPARENT)
        avw.listener = object : AvatarWebView.Listener {
            override fun onRendererReady()        { addDebugLine("✅ READY") }
            override fun onModelLoaded(name: String) { addDebugLine("✅ Model OK") }
            override fun onModelError(error: String) { addDebugLine("❌ ${error.take(15)}") }
            override fun onDebugMessage(msg: String) { addDebugLine(msg.take(20)) }
        }
        root.addView(avw, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Debug panel
        val dbg = DebugPanelView(context).also { debugPanel = it }
        root.addView(dbg, FrameLayout.LayoutParams(250, 180,
            Gravity.TOP or Gravity.START).also { it.leftMargin = 4; it.topMargin = 4 })

        // Speech bubble
        val bubble = TextView(context).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            setBackgroundResource(android.R.drawable.toast_frame)
            textSize = 12f; setPadding(16, 8, 16, 8)
            maxWidth = (BUBBLE_MAX_DP * density).toInt()
        }
        speechBubble = bubble
        root.addView(bubble, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).also { it.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL })

        // Touch / drag
        val gd = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean { cycleSizeMode(); return true }
        })
        root.setOnTouchListener { v, ev ->
            gd.onTouchEvent(ev)
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    touchX0 = ev.rawX; touchY0 = ev.rawY
                    paramX0 = lp.x;    paramY0 = lp.y
                    motionController?.onDragStart()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - touchX0).toInt()
                    val dy = (ev.rawY - touchY0).toInt()
                    if (!dragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) dragging = true
                    if (dragging) {
                        lp.x = paramX0 - dx; lp.y = paramY0 - dy
                        try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) motionController?.onDragEnd()
                    else v.performClick()
                    dragging = false
                }
            }
            true
        }

        wm.addView(root, lp)
        addDebugLine("✅ OVERLAY UP")

        // Start motion after view is added
        motionController = PetMotionController(wm, lp, root, w, h).also { it.start() }
        addDebugLine("🚶 MOTION ON")

        if (!avatarPath.isNullOrBlank()) avw.loadModelFromPath(avatarPath)
        else addDebugLine("⚠️  No path")
    }

    // ── detach ─────────────────────────────────────────────────────────────────
    fun detach() {
        if (Looper.myLooper() != Looper.getMainLooper()) { mainHandler.post { detach() }; return }
        motionController?.stop(); motionController = null
        overlayRoot?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        avatarView?.destroy()
        avatarView = null; overlayRoot = null; speechBubble = null; debugPanel = null
        scope.cancel()
    }

    fun isAttached() = overlayRoot != null

    // ── Motion forwarding ──────────────────────────────────────────────────────
    fun onMoodChanged(valence: Float, arousal: Float) = motionController?.onMoodChanged(valence, arousal)
    fun onAppChanged(pkg: String)                     = motionController?.onAppChanged(pkg)
    fun onKeyboardVisible(keyboardH: Int)             = motionController?.onKeyboardVisible(keyboardH)
    fun onKeyboardHidden()                            = motionController?.onKeyboardHidden()
    fun triggerPeek()                                 = motionController?.peek()

    // ── Speech bubble ──────────────────────────────────────────────────────────
    fun showSpeechBubble(text: String, durationMs: Long = 5000L) {
        bubbleJob?.cancel()
        speechBubble?.let { b ->
            b.text = text; b.visibility = View.VISIBLE
            bubbleJob = scope.launch { delay(durationMs); b.visibility = View.GONE }
        }
    }
    fun hideSpeechBubble() { bubbleJob?.cancel(); speechBubble?.visibility = View.GONE }

    // ── Size cycling ───────────────────────────────────────────────────────────
    private var sizeMode = 0
    private fun cycleSizeMode() {
        sizeMode = (sizeMode + 1) % 3
        val (w, h, framing) = when (sizeMode) {
            1    -> Triple(240, 360, "full")
            2    -> Triple(100, 150, "face")
            else -> Triple(OVERLAY_WIDTH_DP, OVERLAY_HEIGHT_DP, "bust")
        }
        params?.let { p ->
            p.width  = (w * density).toInt()
            p.height = (h * density).toInt()
            overlayRoot?.let { try { wm.updateViewLayout(it, p) } catch (_: Exception) {} }
        }
        avatarView?.setFraming(framing)
        addDebugLine(framing.uppercase())
    }

    // ── Avatar controls ────────────────────────────────────────────────────────
    fun playExpression(emotion: PetEmotion) { addDebugLine("EXPR: ${emotion.vrmExpression}"); avatarView?.playExpression(emotion) }
    fun resetExpression()                   { avatarView?.resetExpression() }
    fun loadAvatar(path: String)            { avatarView?.loadModelFromPath(path) }
    fun sayLlmThinking()                    { addDebugLine("🤔 LLM...") }
    fun sayLlmDone()                        { addDebugLine("✅ LLM OK") }
    fun sayLlmError(error: String)          { addDebugLine("❌ LLM ERR") }
}
