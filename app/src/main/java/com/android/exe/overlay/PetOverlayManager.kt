package com.android.exe.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
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
        private const val OVERLAY_HEIGHT_DP = 260
        private const val BUBBLE_WIDTH_DP   = 240
    }

    private val wm          = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density     = context.resources.displayMetrics.density
    private val mainHandler = Handler(Looper.getMainLooper())

    // Avatar overlay
    private var overlayRoot: FrameLayout?  = null
    var avatarView: AvatarWebView? = null; private set
    private var avatarParams: WindowManager.LayoutParams? = null

    // Speech bubble — separate floating window so it's never clipped
    private var bubbleRoot:   FrameLayout?  = null
    private var bubbleText:   TextView?     = null
    private var thinkDots:    TextView?     = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleAttached = false

    // Motion
    private var motionController: PetMotionController? = null

    // Coroutines
    private var bubbleJob:  Job? = null
    private var dotsJob:    Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Drag
    private var dragging = false
    private var touchX0 = 0f; private var touchY0 = 0f
    private var paramX0 = 0;  private var paramY0 = 0

    // ── Attach ────────────────────────────────────────────────────────────────
    fun attach(avatarPath: String?) {
        if (Looper.myLooper() != Looper.getMainLooper()) { mainHandler.post { attach(avatarPath) }; return }
        if (overlayRoot != null) return

        val w = dp(OVERLAY_WIDTH_DP)
        val h = dp(OVERLAY_HEIGHT_DP)

        val lp = overlayLayoutParams(w, h).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 16; y = 80
        }
        avatarParams = lp

        // Root intercepts all touch for drag
        val root = object : FrameLayout(context) {
            override fun onInterceptTouchEvent(ev: MotionEvent) = true
        }.also { overlayRoot = it }
        root.setBackgroundColor(Color.TRANSPARENT)

        // Avatar WebView — full size
        val avw = AvatarWebView(context).also { avatarView = it }
        avw.setBackgroundColor(Color.TRANSPARENT)
        avw.listener = object : AvatarWebView.Listener {
            override fun onRendererReady()           { Log.i(TAG, "Renderer ready") }
            override fun onModelLoaded(name: String) { Log.i(TAG, "Model loaded: $name") }
            override fun onModelError(error: String) { Log.e(TAG, "Model error: $error") }
            override fun onDebugMessage(msg: String) { Log.d(TAG, "JS: $msg") }
        }
        root.addView(avw, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT))

        // Drag / double-tap
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
                        repositionBubble()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) { motionController?.onDragEnd(); repositionBubble() }
                    else v.performClick()
                    dragging = false
                }
            }
            true
        }

        wm.addView(root, lp)

        // Build speech bubble window
        attachBubble()

        // Start motion
        motionController = PetMotionController(wm, lp, root, w, h).also { it.start() }

        if (!avatarPath.isNullOrBlank()) avw.loadModelFromPath(avatarPath)
        Log.i(TAG, "Overlay attached")
    }

    // ── Speech bubble window ──────────────────────────────────────────────────
    private fun attachBubble() {
        if (bubbleAttached) return
        val bw = dp(BUBBLE_WIDTH_DP)
        val bh = ViewGroup.LayoutParams.WRAP_CONTENT

        val lp = overlayLayoutParams(bw, bh).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        bubbleParams = lp

        val root = FrameLayout(context).also { bubbleRoot = it }
        root.setBackgroundColor(Color.TRANSPARENT)
        root.visibility = View.GONE

        // Card-style background
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        // Thinking dots
        val dots = TextView(context).apply {
            text = "● ● ●"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#BBDDFF"))
            visibility = View.GONE
        }.also { thinkDots = it }

        // Speech text
        val tv = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setLineSpacing(2f, 1f)
            maxWidth = dp(BUBBLE_WIDTH_DP - 24)
            background = buildBubbleBackground()
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
        }.also { bubbleText = it }

        card.addView(dots)
        card.addView(tv)
        root.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        repositionBubble()
        wm.addView(root, lp)
        bubbleAttached = true
    }

    private fun buildBubbleBackground(): android.graphics.drawable.Drawable {
        val gd = android.graphics.drawable.GradientDrawable()
        gd.setColor(Color.argb(220, 20, 20, 40))
        gd.cornerRadius = dp(12).toFloat()
        gd.setStroke(dp(1), Color.argb(180, 100, 140, 255))
        return gd
    }

    private fun repositionBubble() {
        val lp  = bubbleParams ?: return
        val alp = avatarParams ?: return
        val bw  = dp(BUBBLE_WIDTH_DP)
        // Place bubble above avatar, aligned right
        lp.x = alp.x
        lp.y = alp.y + dp(OVERLAY_HEIGHT_DP) + dp(8)
        if (bubbleAttached) {
            try { wm.updateViewLayout(bubbleRoot ?: return, lp) } catch (_: Exception) {}
        }
    }

    // ── Detach ────────────────────────────────────────────────────────────────
    fun detach() {
        if (Looper.myLooper() != Looper.getMainLooper()) { mainHandler.post { detach() }; return }
        motionController?.stop(); motionController = null
        overlayRoot?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        if (bubbleAttached) {
            bubbleRoot?.let { try { wm.removeView(it) } catch (_: Exception) {} }
            bubbleAttached = false
        }
        avatarView?.destroy()
        avatarView = null; overlayRoot = null
        bubbleRoot = null; bubbleText = null; thinkDots = null
        scope.cancel()
    }

    fun isAttached() = overlayRoot != null

    // ── Motion forwarding ─────────────────────────────────────────────────────
    fun onMoodChanged(valence: Float, arousal: Float) {
        motionController?.onMoodChanged(valence, arousal)
    }
    fun onAppChanged(pkg: String) {
        motionController?.onAppChanged(pkg)
    }
    fun onKeyboardVisible(keyboardH: Int) = motionController?.onKeyboardVisible(keyboardH)
    fun onKeyboardHidden()                = motionController?.onKeyboardHidden()
    fun triggerPeek()                     = motionController?.peek()

    // ── AI state → avatar animation ───────────────────────────────────────────

    /** Call when LLM starts generating. Shows animated thinking dots. */
    fun onLlmThinking() {
        avatarView?.evaluateJavascript("AvatarAPI.setThinking();", null)
        bubbleJob?.cancel()
        bubbleRoot?.visibility = View.VISIBLE
        bubbleText?.visibility = View.GONE
        thinkDots?.visibility  = View.VISIBLE
        // Animate the dots
        dotsJob?.cancel()
        dotsJob = scope.launch {
            val frames = listOf("●  ○  ○", "○  ●  ○", "○  ○  ●", "○  ●  ○")
            var i = 0
            while (isActive) {
                thinkDots?.text = frames[i % frames.size]
                i++
                delay(350)
            }
        }
    }

    /** Call with each token as it streams in. Shows text building up live. */
    fun onLlmToken(accumulated: String) {
        // Switch from dots to text on first token
        if (thinkDots?.visibility == View.VISIBLE) {
            dotsJob?.cancel()
            thinkDots?.visibility = View.GONE
            bubbleText?.visibility = View.VISIBLE
            avatarView?.evaluateJavascript("AvatarAPI.setSpeaking();", null)
        }
        bubbleRoot?.visibility = View.VISIBLE
        bubbleText?.text       = accumulated
    }

    /** Call when LLM finishes. Keeps bubble up then fades. */
    fun onLlmDone(finalText: String, durationMs: Long = 7000L) {
        dotsJob?.cancel()
        thinkDots?.visibility  = View.GONE
        bubbleText?.visibility = View.VISIBLE
        bubbleText?.text       = finalText
        bubbleRoot?.visibility = View.VISIBLE
        bubbleJob?.cancel()
        bubbleJob = scope.launch {
            delay(durationMs)
            bubbleRoot?.visibility = View.GONE
            avatarView?.evaluateJavascript("AvatarAPI.setIdle();", null)
        }
    }

    /** Call on error. */
    fun onLlmError(error: String) {
        dotsJob?.cancel()
        thinkDots?.visibility  = View.GONE
        bubbleText?.text       = "⚠ $error"
        bubbleText?.visibility = View.VISIBLE
        bubbleRoot?.visibility = View.VISIBLE
        avatarView?.evaluateJavascript("AvatarAPI.setIdle();", null)
        bubbleJob?.cancel()
        bubbleJob = scope.launch {
            delay(4000)
            bubbleRoot?.visibility = View.GONE
        }
    }

    // ── Expressions ───────────────────────────────────────────────────────────
    fun playExpression(emotion: PetEmotion) {
        avatarView?.playExpression(emotion)
    }
    fun resetExpression() { avatarView?.resetExpression() }
    fun loadAvatar(path: String) { avatarView?.loadModelFromPath(path) }

    // ── Size cycling (double-tap) ─────────────────────────────────────────────
    private var sizeMode = 0
    private fun cycleSizeMode() {
        sizeMode = (sizeMode + 1) % 3
        val (w, h, framing) = when (sizeMode) {
            1    -> Triple(240, 380, "full")
            2    -> Triple(100, 150, "face")
            else -> Triple(OVERLAY_WIDTH_DP, OVERLAY_HEIGHT_DP, "bust")
        }
        avatarParams?.let { p ->
            p.width  = dp(w)
            p.height = dp(h)
            overlayRoot?.let { try { wm.updateViewLayout(it, p) } catch (_: Exception) {} }
        }
        avatarView?.setFraming(framing)
        repositionBubble()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun dp(v: Int) = (v * density).toInt()

    private fun overlayLayoutParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    )
}
