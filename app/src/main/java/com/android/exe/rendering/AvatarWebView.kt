package com.android.exe.rendering

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.*
import com.android.exe.ai.PetEmotion
import java.io.File

@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class AvatarWebView(context: Context) : WebView(context) {

    companion object {
        private const val TAG = "AvatarWebView"
    }

    interface Listener {
        fun onRendererReady()
        fun onModelLoaded(name: String)
        fun onModelError(error: String)
        fun onDebugMessage(msg: String)  // NEW: for debug callbacks
    }

    var listener: Listener? = null
    private var pendingModelUri: String? = null
    private var rendererReady  = false

    // ─────────────────────────────────────────────────────────────────────────
    // Guard timer: if JS never calls onRendererReady() within 10s, unblock
    // ─────────────────────────────────────────────────────────────────────────
    private val mainHandler = Handler(Looper.getMainLooper())
    private val readyGuard  = Runnable {
        if (!rendererReady) {
            Log.w(TAG, "⚠ Ready guard FIRED — JS never called onRendererReady. Unblocking overlay. This suggests a module loading failure.")
            markReady()
        }
    }

    init {
        settings.apply {
            javaScriptEnabled                = true
            domStorageEnabled                = true
            allowFileAccess                  = true
            allowContentAccess               = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs      = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            mixedContentMode                 = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode                        = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
        }

        setBackgroundColor(0x00000000)
        background?.alpha = 0

        addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                val msg = "WebView resource error: ${error?.description} url=${request?.url}"
                Log.e(TAG, "❌ $msg")
                listener?.onDebugMessage("❌ Resource load failed: ${request?.url}")
            }
            
            override fun onPageFinished(view: WebView?, url: String?) {
                Log.d(TAG, "✓ Page finished: $url")
                listener?.onDebugMessage("✓ Page loaded: $url")
            }
            
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                Log.d(TAG, "→ Page starting: $url")
                listener?.onDebugMessage("→ Loading: $url")
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                val text = msg?.message() ?: return false
                val prefix = when (msg.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR   -> { Log.e(TAG, "JS ERROR: $text"); "❌ JS:" }
                    ConsoleMessage.MessageLevel.WARNING -> { Log.w(TAG, "JS WARN: $text"); "⚠ JS:" }
                    else                                -> { Log.d(TAG, "JS LOG: $text"); "ℹ JS:" }
                }
                listener?.onDebugMessage("$prefix $text")
                return true
            }
        }

        Log.i(TAG, "🚀 AvatarWebView initializing...")
        listener?.onDebugMessage("🚀 Initializing WebView")
        
        loadUrl("file:///android_asset/avatar_renderer.html")

        // 10-second guard timer
        mainHandler.postDelayed(readyGuard, 10_000L)
        Log.d(TAG, "Guard timer set: 10 seconds")
        listener?.onDebugMessage("⏱ Guard timer: 10s")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JS → Kotlin bridge (all methods receive debug tracking)
    // ─────────────────────────────────────────────────────────────────────────

    inner class AndroidBridge {
        @JavascriptInterface
        fun onRendererReady() {
            Log.i(TAG, "✅ Renderer ready (JS callback)")
            listener?.onDebugMessage("✅ Renderer READY from JS")
            post { markReady() }
        }

        @JavascriptInterface
        fun onModelLoaded(name: String) {
            Log.i(TAG, "✅ Model loaded: $name")
            listener?.onDebugMessage("✅ Model loaded: $name")
            post { listener?.onModelLoaded(name) }
        }

        @JavascriptInterface
        fun onModelError(error: String) {
            Log.e(TAG, "❌ Model error: $error")
            listener?.onDebugMessage("❌ Model ERROR: $error")
            post { listener?.onModelError(error) }
        }
        
        @JavascriptInterface
        fun onDebugMessage(msg: String) {
            Log.d(TAG, "🐛 Debug: $msg")
            post { listener?.onDebugMessage(msg) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Kotlin → JS calls (with detailed logging)
    // ─────────────────────────────────────────────────────────────────────────

    fun loadModelFromPath(path: String) {
        val file = File(path)
        if (!file.exists()) {
            Log.e(TAG, "❌ File not found: $path")
            listener?.onDebugMessage("❌ File not found: $path")
            listener?.onModelError("File not found: $path")
            return
        }

        val fileSize = file.length() / 1024  // KB
        Log.i(TAG, "📦 Loading model from path: $path ($fileSize KB)")
        listener?.onDebugMessage("📦 Loading: ${file.name} ($fileSize KB)")

        val mimeType = when {
            path.endsWith(".vrm",  ignoreCase = true) -> "model/gltf-binary"
            path.endsWith(".glb",  ignoreCase = true) -> "model/gltf-binary"
            path.endsWith(".gltf", ignoreCase = true) -> "model/gltf+json"
            else                                       -> "application/octet-stream"
        }

        Thread {
            try {
                Log.d(TAG, "→ Reading file: ${file.name}")
                listener?.onDebugMessage("→ Reading file...")
                
                val bytes = file.readBytes()
                Log.d(TAG, "✓ File read: ${bytes.size / 1024} KB")
                listener?.onDebugMessage("✓ Read: ${bytes.size / 1024} KB")
                
                Log.d(TAG, "→ Encoding to base64...")
                listener?.onDebugMessage("→ Encoding base64...")
                
                val b64   = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val uri   = "data:$mimeType;base64,$b64"
                
                Log.i(TAG, "✓ Encoded successfully. Dispatching to JS...")
                listener?.onDebugMessage("✓ Dispatch to JS")
                post { dispatchModelUri(uri) }
            } catch (e: Exception) {
                Log.e(TAG, "❌ File read failed: ${e.message}", e)
                listener?.onDebugMessage("❌ Read ERROR: ${e.message}")
                post { listener?.onModelError(e.message ?: "Read error") }
            }
        }.start()
    }

    fun loadModelFromUri(uri: Uri) {
        Log.i(TAG, "📦 Loading model from URI: $uri")
        listener?.onDebugMessage("📦 Loading from URI...")
        
        Thread {
            try {
                Log.d(TAG, "→ Opening content resolver...")
                val bytes = context.contentResolver.openInputStream(uri)
                    ?.readBytes()
                    ?: run {
                        Log.e(TAG, "❌ Cannot open URI: $uri")
                        listener?.onDebugMessage("❌ Cannot open URI")
                        post { listener?.onModelError("Cannot open Uri: $uri") }
                        return@Thread
                    }
                
                Log.d(TAG, "✓ Read ${bytes.size / 1024} KB from URI")
                listener?.onDebugMessage("✓ Read: ${bytes.size / 1024} KB")
                
                val mime = if (uri.path?.endsWith(".gltf", ignoreCase = true) == true)
                    "model/gltf+json" else "model/gltf-binary"
                    
                Log.d(TAG, "→ Encoding to base64...")
                val b64  = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val data = "data:$mime;base64,$b64"
                
                Log.i(TAG, "✓ Encoded URI data. Dispatching to JS...")
                listener?.onDebugMessage("✓ Dispatch URI to JS")
                post { dispatchModelUri(data) }
            } catch (e: Exception) {
                Log.e(TAG, "❌ loadModelFromUri failed: ${e.message}", e)
                listener?.onDebugMessage("❌ URI load ERROR: ${e.message}")
                post { listener?.onModelError(e.message ?: "Error") }
            }
        }.start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun markReady() {
        mainHandler.removeCallbacks(readyGuard)
        if (rendererReady) return
        rendererReady = true
        Log.i(TAG, "🎉 Marked as READY")
        listener?.onDebugMessage("🎉 WebView READY")
        listener?.onRendererReady()
        
        pendingModelUri?.let { uri ->
            pendingModelUri = null
            Log.d(TAG, "→ Delivering queued model to renderer")
            listener?.onDebugMessage("→ Loading queued model...")
            evaluateJavascript("AvatarAPI.loadModel('$uri');", null)
        }
    }

    private fun dispatchModelUri(dataUri: String) {
        if (rendererReady) {
            Log.d(TAG, "→ Dispatching model to renderer immediately")
            listener?.onDebugMessage("→ Dispatching model...")
            evaluateJavascript("AvatarAPI.loadModel('$dataUri');", null)
        } else {
            Log.d(TAG, "⏳ Renderer not ready — queuing model (waiting for JS boot)")
            listener?.onDebugMessage("⏳ Queuing model (waiting for JS)")
            pendingModelUri = dataUri
        }
    }

    fun playExpression(emotion: PetEmotion) {
        Log.d(TAG, "😊 Playing expression: ${emotion.vrmExpression} (${(emotion.weight*100).toInt()}%)")
        listener?.onDebugMessage("😊 ${emotion.vrmExpression}")
        evaluateJavascript(
            "AvatarAPI.playExpression('${emotion.vrmExpression}',${emotion.weight},${emotion.durationSec});",
            null
        )
    }

    fun resetExpression() {
        Log.d(TAG, "😐 Resetting expression")
        listener?.onDebugMessage("😐 Reset")
        evaluateJavascript("AvatarAPI.resetExpression();", null)
    }
    
    fun lookAt(x: Float, y: Float) {
        evaluateJavascript("AvatarAPI.lookAt($x,$y);", null)
    }
    
    fun setFraming(mode: String) {
        Log.d(TAG, "🎥 Setting framing: $mode")
        listener?.onDebugMessage("🎥 $mode")
        evaluateJavascript("AvatarAPI.setFraming('$mode');", null)
    }
}
