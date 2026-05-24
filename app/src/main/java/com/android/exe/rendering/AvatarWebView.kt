package com.android.exe.rendering

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.*
import com.android.exe.ai.PetEmotion
import java.io.File

/**
 * Hosts the Three.js / three-vrm avatar renderer in a WebView.
 *
 * Key design:
 *  - LocalFileServer starts immediately in init() and serves the HTML at /
 *    and the model at /model from http://127.0.0.1.
 *  - WebView loads http://127.0.0.1:PORT/ — NOT file:///android_asset/.
 *    This lets ES-module CDN imports work (file:// blocks cross-origin module fetches).
 *  - When a model is set, we tell the server about the file and call
 *    AvatarAPI.loadModel('/model') in JS. No base64 encoding, no data URIs.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class AvatarWebView(context: android.content.Context) : WebView(context) {

    companion object {
        private const val TAG = "AvatarWebView"
    }

    interface Listener {
        fun onRendererReady()
        fun onModelLoaded(name: String)
        fun onModelError(error: String)
        fun onDebugMessage(msg: String)
    }

    var listener: Listener? = null
    private var pendingModelPath: String? = null
    private var rendererReady = false

    private val fileServer = LocalFileServer(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Fallback: if JS never signals ready after 12 s, unblock anyway
    private val readyGuard = Runnable {
        if (!rendererReady) {
            Log.w(TAG, "Ready guard fired — forcing ready")
            markReady()
        }
    }

    init {
        settings.apply {
            javaScriptEnabled                = true
            domStorageEnabled                = true
            allowFileAccess                  = true
            allowContentAccess               = true
            @Suppress("DEPRECATION") allowFileAccessFromFileURLs      = true
            @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = true
            mixedContentMode                 = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode                        = WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = false
        }

        setBackgroundColor(0x00000000)
        background?.alpha = 0

        addJavascriptInterface(Bridge(), "AndroidBridge")

        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                Log.d(TAG, "Page finished: $url")
                listener?.onDebugMessage("✓ Page loaded")
            }
            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                val msg = "WebView error: ${error?.description} ${request?.url}"
                Log.e(TAG, msg)
                listener?.onDebugMessage("❌ $msg")
            }
            override fun onReceivedHttpError(
                view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?
            ) {
                Log.w(TAG, "HTTP ${response?.statusCode} for ${request?.url}")
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                val text  = msg?.message() ?: return false
                val level = msg.messageLevel()
                when (level) {
                    ConsoleMessage.MessageLevel.ERROR   -> Log.e(TAG, "JS: $text")
                    ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, "JS: $text")
                    else                               -> Log.v(TAG, "JS: $text")
                }
                if (level == ConsoleMessage.MessageLevel.ERROR)
                    listener?.onDebugMessage("❌ ${text.take(80)}")
                return true
            }
        }

        // Start server FIRST, then load from http://
        fileServer.start()
        Log.i(TAG, "Server started on port ${fileServer.port}")
        loadUrl(fileServer.rootUrl)
        mainHandler.postDelayed(readyGuard, 12_000L)
    }

    // ── JS → Kotlin bridge ────────────────────────────────────────────────────

    inner class Bridge {
        @JavascriptInterface fun onRendererReady()           { Log.i(TAG, "onRendererReady"); post { markReady() } }
        @JavascriptInterface fun onModelLoaded(name: String) { Log.i(TAG, "onModelLoaded: $name"); post { listener?.onModelLoaded(name) } }
        @JavascriptInterface fun onModelError(error: String) { Log.e(TAG, "onModelError: $error"); listener?.onDebugMessage("❌ $error"); post { listener?.onModelError(error) } }
        @JavascriptInterface fun onDebugMessage(msg: String) { Log.d(TAG, "JS: $msg"); post { listener?.onDebugMessage(msg) } }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun loadModelFromPath(path: String) {
        val file = File(path)
        if (!file.exists() || !file.canRead()) {
            val err = "File not found: $path"
            Log.e(TAG, err)
            listener?.onModelError(err)
            return
        }
        Log.i(TAG, "loadModelFromPath: ${file.name} (${file.length() / 1024} KB)")
        fileServer.setModelFile(file)

        if (rendererReady) {
            Log.d(TAG, "Renderer ready — loading now")
            js("AvatarAPI.loadModel('/model');")
        } else {
            Log.d(TAG, "Renderer not ready — queuing")
            pendingModelPath = path
        }
    }

    fun loadModelFromUri(uri: Uri) {
        Log.i(TAG, "loadModelFromUri: $uri")
        Thread {
            try {
                val ext  = if (uri.path?.endsWith(".gltf", true) == true) ".gltf" else ".glb"
                val dest = File(context.cacheDir, "avatar_tmp$ext")
                context.contentResolver.openInputStream(uri)?.use { it.copyTo(dest.outputStream()) }
                    ?: run { post { listener?.onModelError("Cannot open URI") }; return@Thread }
                post { loadModelFromPath(dest.absolutePath) }
            } catch (e: Exception) {
                Log.e(TAG, "loadModelFromUri failed", e)
                post { listener?.onModelError(e.message ?: "URI error") }
            }
        }.also { it.isDaemon = true }.start()
    }

    fun playExpression(emotion: PetEmotion) =
        js("AvatarAPI.playExpression('${emotion.vrmExpression}',${emotion.weight},${emotion.durationSec});")

    fun resetExpression() = js("AvatarAPI.resetExpression();")
    fun setThinking()     = js("AvatarAPI.setThinking();")
    fun setSpeaking()     = js("AvatarAPI.setSpeaking();")
    fun setIdle()         = js("AvatarAPI.setIdle();")
    fun lookAt(x: Float, y: Float) = js("AvatarAPI.lookAt($x,$y);")
    fun setFraming(mode: String)   = js("AvatarAPI.setFraming('$mode');")

    override fun destroy() {
        mainHandler.removeCallbacks(readyGuard)
        fileServer.stop()
        super.destroy()
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun js(script: String) = evaluateJavascript(script, null)

    private fun markReady() {
        mainHandler.removeCallbacks(readyGuard)
        if (rendererReady) return
        rendererReady = true
        Log.i(TAG, "Renderer marked READY")
        listener?.onRendererReady()
        pendingModelPath?.let { path ->
            pendingModelPath = null
            Log.d(TAG, "Delivering queued model")
            fileServer.setModelFile(File(path))
            js("AvatarAPI.loadModel('/model');")
        }
    }
}
