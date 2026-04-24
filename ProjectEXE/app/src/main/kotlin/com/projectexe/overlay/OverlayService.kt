package com.projectexe.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.projectexe.MainActivity
import com.projectexe.ProjectEXEApplication
import com.projectexe.R
import com.projectexe.ai.arbitrator.Arbitrator
import com.projectexe.bridge.AnimationBridge
import com.projectexe.memory.MemoryMaintenanceWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class OverlayService : LifecycleService() {
    companion object {
        private const val TAG      = "EXE.Overlay"
        private const val NOTIF_ID = 1001
        const val ACTION_START     = "com.projectexe.action.START_OVERLAY"
        const val ACTION_STOP      = "com.projectexe.action.STOP_OVERLAY"
        @Volatile var isRunning    = false; private set
    }

    private lateinit var wm: WindowManager
    private lateinit var pm: PowerManager
    private lateinit var arb: Arbitrator
    private lateinit var bridge: AnimationBridge
    private lateinit var maint: MemoryMaintenanceWorker

    private var overlayView: View? = null
    private var wv: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var dInitX=0; private var dInitY=0
    private var dTouchX=0f; private var dTouchY=0f

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        pm = getSystemService(PowerManager::class.java)
        val app = application as ProjectEXEApplication
        arb   = Arbitrator(
            soul   = app.soulHemisphere,
            client = app.openRouterClient,
            scope  = lifecycleScope,
            router = app.engineRouter,
            tools  = app.toolRegistry
        )
        maint = MemoryMaintenanceWorker(app.memoryDatabase.memoryDao(), lifecycleScope)
        arb.loadCharacter(this)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ProjectEXE::Lock")
            .also { it.acquire(3600_000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            tearDown(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotif())
        if (overlayView == null) {
            buildOverlay()
            arb.responseFlow.onEach { r ->
                bridge.dispatchResponse(r)
                if (!r.isThinking) maint.onMemoryInserted()
            }.launchIn(lifecycleScope)
            (application as ProjectEXEApplication).userPrefs.recordSessionStart()
        }
        return START_STICKY
    }

    override fun onBind(i: Intent): IBinder? { super.onBind(i); return null }
    override fun onDestroy() { super.onDestroy(); tearDown(); wakeLock?.let { if(it.isHeld) it.release() }; isRunning=false }

    @SuppressLint("SetJavaScriptEnabled","ClickableViewAccessibility")
    private fun buildOverlay() {
        val m = metrics(); val sz = (m.widthPixels*0.35f).toInt().coerceIn(280,520)
        val sp = loadPos()
        params = WindowManager.LayoutParams(sz, sz,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.START
            x=sp.first.coerceIn(0,m.widthPixels-sz); y=sp.second.coerceIn(0,m.heightPixels-sz)
        }
        wv = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT); isClickable=true; isFocusable=false
            settings.apply { javaScriptEnabled=true; domStorageEnabled=true; allowFileAccess=true
                allowFileAccessFromFileURLs=true; allowUniversalAccessFromFileURLs=true
                mediaPlaybackRequiresUserGesture=false; setLayerType(View.LAYER_TYPE_HARDWARE,null) }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, url: String?) {
                    isRunning=true
                    val vf = arb.activeVrmFile()
                    if (vf.isNotEmpty()) lifecycleScope.launch { delay(300); bridge.loadVrmFile(vf) }
                    lifecycleScope.launch {
                        delay(600)
                        val app = application as ProjectEXEApplication
                        arb.submitPrompt(if(app.memoryDatabase.memoryDao().getMemoryCount()==0L) "__SYSTEM_INIT__" else "__SYSTEM_WAKE__", true)
                    }
                }
            }
        }
        bridge = AnimationBridge(wv!!, lifecycleScope)
        bridge.onUserInputReceived = { arb.submitPrompt(it, false) }
        bridge.onCloseRequested = {
            try { startService(Intent(this, OverlayService::class.java).setAction(ACTION_STOP)) }
            catch (_: Exception) { stopSelf() }
        }
        wv!!.addJavascriptInterface(bridge, "EXEBridge")
        wv!!.loadUrl("file:///android_asset/web/vrm_renderer.html")
        wv!!.setOnTouchListener { v,e -> handleTouch(v,e) }
        overlayView = wv
        try { wm.addView(overlayView, params); Log.i(TAG,"Overlay attached") }
        catch (e: Exception) { Log.e(TAG,"addView failed",e); stopSelf() }
    }

    private fun tearDown() {
        try { overlayView?.let { wm.removeView(it) } } catch(_:Exception){}
        wv?.destroy(); wv=null; overlayView=null; isRunning=false
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun handleTouch(v: View, e: MotionEvent): Boolean {
        val p = params ?: return false
        return when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dInitX=p.x; dInitY=p.y; dTouchX=e.rawX; dTouchY=e.rawY; false }
            MotionEvent.ACTION_MOVE -> {
                val dx=(e.rawX-dTouchX).toInt(); val dy=(e.rawY-dTouchY).toInt()
                if(dx*dx+dy*dy>144) { val m=metrics()
                    p.x=(dInitX+dx).coerceIn(0,m.widthPixels-v.width)
                    p.y=(dInitY+dy).coerceIn(0,m.heightPixels-v.height)
                    try { wm.updateViewLayout(overlayView,p) } catch(_:Exception){} ; true
                } else false
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> { savePos(p.x,p.y); false }
            else -> false
        }
    }

    private fun buildNotif(): Notification {
        val stop = PendingIntent.getService(this,0,Intent(this,OverlayService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this,1,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, ProjectEXEApplication.CHANNEL_OVERLAY)
            .setContentTitle("${arb.activeCharacterName()} is active")
            .setContentText("EXE AI companion running")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,"Dismiss",stop)
            .setOngoing(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_SERVICE).build()
    }

    private fun metrics(): DisplayMetrics =
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b=getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            DisplayMetrics().also{it.widthPixels=b.width();it.heightPixels=b.height();it.density=resources.displayMetrics.density}
        } else @Suppress("DEPRECATION") resources.displayMetrics

    private fun loadPos(): Pair<Int,Int> { val m=metrics(); val p=getSharedPreferences("exe_overlay_prefs",Context.MODE_PRIVATE)
        return Pair(p.getInt("x",(m.widthPixels*0.62f).toInt()),p.getInt("y",(m.heightPixels*0.55f).toInt())) }
    private fun savePos(x:Int,y:Int) = getSharedPreferences("exe_overlay_prefs",Context.MODE_PRIVATE).edit().putInt("x",x).putInt("y",y).apply()
}
