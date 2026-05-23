package com.android.exe.overlay

import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.*
import kotlin.math.*
import kotlin.random.Random

/**
 * Drives purposeful, physics-based movement of the pet overlay.
 *
 * Logical coords: (0,0) = top-left, x+ right, y+ down.
 * Converts to WindowManager GRAVITY_BOTTOM|END params internally.
 */
class PetMotionController(
    private val wm: WindowManager,
    private val params: WindowManager.LayoutParams,
    private val overlayRoot: View,
    private val overlayW: Int,
    private val overlayH: Int
) {
    companion object {
        private const val TAG        = "PetMotion"
        private const val TICK_MS    = 16L
        private const val SPRING_K   = 6f
        private const val DAMPING    = 0.80f
        private const val MAX_SPEED  = 1200f   // px/s
        private const val MARGIN     = 32f
    }

    enum class State { ANCHORED, WANDERING, REACTING, EXCITED, SULKING, PEEKING }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val screenW: Int
    private val screenH: Int

    init {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels
    }

    // Physics state in logical coords
    private var px = 0f
    private var py = 0f
    private var vx = 0f
    private var vy = 0f
    private var tx = 0f
    private var ty = 0f

    var state = State.ANCHORED; private set

    private var dragging  = false
    private var anchorIdx = 0

    // Normalized (0–1) anchor zones: (nx, ny, label)
    private data class Zone(val nx: Float, val ny: Float, val label: String)

    private val anchors = listOf(
        Zone(1.00f, 1.00f, "bottom-right"),
        Zone(0.00f, 1.00f, "bottom-left"),
        Zone(1.00f, 0.50f, "right-mid"),
        Zone(0.00f, 0.50f, "left-mid"),
        Zone(1.00f, 0.15f, "top-right"),
        Zone(0.00f, 0.15f, "top-left"),
    )

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    fun start() {
        // Sync starting position from current params
        px = (screenW - overlayW - params.x).toFloat()
        py = (screenH - overlayH - params.y).toFloat()
        tx = px; ty = py
        startPhysics()
        startWanderScheduler()
        Log.i(TAG, "Started. screen=${screenW}x${screenH} overlay=${overlayW}x${overlayH}")
    }

    fun stop() {
        scope.cancel()
    }

    // ── Public triggers ────────────────────────────────────────────────────────

    /** Call when the user starts dragging the overlay. */
    fun onDragStart() {
        dragging = true
    }

    /** Call after drag ends — syncs physics to wherever user dropped it. */
    fun onDragEnd() {
        px = (screenW - overlayW - params.x).toFloat()
        py = (screenH - overlayH - params.y).toFloat()
        tx = px; ty = py
        vx = 0f; vy = 0f
        dragging = false
        state = State.ANCHORED
    }

    /**
     * React to mood. valence 0=sad→1=happy, arousal 0=calm→1=excited.
     */
    fun onMoodChanged(valence: Float, arousal: Float) {
        state = when {
            arousal > 0.75f && valence > 0.6f -> State.EXCITED
            valence < 0.3f                     -> State.SULKING
            arousal < 0.25f                    -> State.ANCHORED
            else                               -> State.WANDERING
        }
        pickTarget()
        Log.d(TAG, "Mood → $state (v=$valence a=$arousal)")
    }

    /** React to app switch — move to a sensible screen zone for that app. */
    fun onAppChanged(pkg: String) {
        val zone = zoneForApp(pkg)
        setTargetZone(zone)
        state = State.REACTING
        Log.d(TAG, "App $pkg → ${zone.label}")
    }

    /** Brief curiosity drift toward center then retreat. */
    fun peek() {
        if (state == State.PEEKING) return
        val prev = state
        state = State.PEEKING
        setTargetZone(Zone(0.5f, 0.4f, "center"))
        scope.launch {
            delay(2800L)
            if (state == State.PEEKING) {
                state = prev
                pickTarget()
            }
        }
    }

    /** Push avatar above keyboard. */
    fun onKeyboardVisible(keyboardH: Int) {
        val safeY = (screenH - keyboardH - overlayH - MARGIN).coerceAtLeast(MARGIN)
        setTarget(px, safeY)
    }

    fun onKeyboardHidden() = pickTarget()

    // ── Internal ───────────────────────────────────────────────────────────────

    private fun pickTarget() {
        when (state) {
            State.ANCHORED  -> setTargetZone(anchors[anchorIdx])
            State.WANDERING -> { anchorIdx = (anchorIdx + 1) % anchors.size; setTargetZone(anchors[anchorIdx]) }
            State.EXCITED   -> setTargetZone(Zone(0.2f + Random.nextFloat() * 0.6f, 0.2f + Random.nextFloat() * 0.6f, "excited"))
            State.SULKING   -> setTargetZone(anchors.filter { "bottom" in it.label }.random())
            State.REACTING  -> { /* set externally by onAppChanged */ }
            State.PEEKING   -> { /* set externally by peek() */ }
        }
    }

    private fun zoneForApp(pkg: String) = when {
        pkg.containsAny("message","whatsapp","telegram","discord","instagram","twitter","reddit","snapchat")
                         -> Zone(0.85f, 0.92f, "social-bottom-right")
        pkg.containsAny("youtube","netflix","twitch","tiktok","prime","hulu")
                         -> Zone(0.95f, 0.04f, "media-top-right")
        pkg.containsAny("game","clash","pokemon","minecraft","roblox","pubg","fortnite")
                         -> Zone(0.98f, 0.04f, "game-corner")
        pkg.containsAny("chrome","firefox","browser","duck")
                         -> Zone(1.00f, 0.38f, "browser-right-edge")
        pkg.containsAny("maps","waze","navigation","uber","lyft")
                         -> Zone(0.50f, 0.04f, "maps-top")
        pkg.containsAny("music","spotify","tidal","soundcloud")
                         -> Zone(0.00f, 0.88f, "music-bottom-left")
        pkg.containsAny("camera","photo","gallery","snapseed")
                         -> Zone(0.95f, 0.92f, "photo-corner")
        else             -> anchors[anchorIdx]
    }

    private fun String.containsAny(vararg tokens: String) =
        tokens.any { this.contains(it, ignoreCase = true) }

    private fun setTargetZone(z: Zone) {
        val maxX = screenW - overlayW - MARGIN
        val maxY = screenH - overlayH - MARGIN
        setTarget(
            (z.nx * maxX).coerceIn(MARGIN, maxX),
            (z.ny * maxY).coerceIn(MARGIN, maxY)
        )
    }

    private fun setTarget(x: Float, y: Float) { tx = x; ty = y }

    // ── Physics loop ───────────────────────────────────────────────────────────

    private fun startPhysics() {
        scope.launch {
            while (isActive) {
                if (!dragging) {
                    val dt = TICK_MS / 1000f

                    vx = ((vx + (tx - px) * SPRING_K * dt) * DAMPING)
                        .coerceIn(-MAX_SPEED * dt, MAX_SPEED * dt)
                    vy = ((vy + (ty - py) * SPRING_K * dt) * DAMPING)
                        .coerceIn(-MAX_SPEED * dt, MAX_SPEED * dt)

                    px += vx
                    py += vy

                    val maxX = (screenW - overlayW - MARGIN)
                    val maxY = (screenH - overlayH - MARGIN)
                    if (px < MARGIN)  { px = MARGIN;  vx = abs(vx) * 0.3f }
                    if (px > maxX) { px = maxX; vx = -abs(vx) * 0.3f }
                    if (py < MARGIN)  { py = MARGIN;  vy = abs(vy) * 0.3f }
                    if (py > maxY) { py = maxY; vy = -abs(vy) * 0.3f }

                    val speed = sqrt(vx * vx + vy * vy)
                    if (speed > 0.3f) {
                        params.x = (screenW - overlayW - px).toInt()
                        params.y = (screenH - overlayH - py).toInt()
                        try { wm.updateViewLayout(overlayRoot, params) } catch (_: Exception) {}
                    }
                }
                delay(TICK_MS)
            }
        }
    }

    // ── Wander scheduler ───────────────────────────────────────────────────────

    private fun startWanderScheduler() {
        scope.launch {
            while (isActive) {
                delay(25_000L + Random.nextLong(50_000L))   // every 25–75 s
                if (!dragging && state in listOf(State.ANCHORED, State.WANDERING)) {
                    if (Random.nextFloat() < 0.25f) {
                        peek()
                    } else {
                        state = State.WANDERING
                        pickTarget()
                        Log.d(TAG, "Autonomous wander → ${anchors[anchorIdx].label}")
                    }
                }
            }
        }
    }
}
