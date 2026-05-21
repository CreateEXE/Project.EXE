package com.android.exe.rendering

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.*
import com.android.exe.ai.PetEmotion
import java.io.File

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
    private var pendingModelUrl: String? = null
    private var rendererReady   = false

    // One server per WebView lifetime — stopped in destroy()
    private var fileServer: LocalFileServer? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val readyGuard  = Runnable {
        if (!rendererReady) {
            Log.w(TAG, "Ready guard fired — JS never called onRendererReady. Unblocking.")
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
                val msg = "Resource error: ${error?.description} url=${request?.url}"
                Log.e(TAG, msg)
                listener?.onDebugMessage("❌ $msg")
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                Log.d(TAG, "Page finished: $url")
                listener?.onDebugMessage("✓ Page loaded")
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                val text = msg?.message() ?: return false
                val level = msg.messageLevel()
                when (level) {
                    ConsoleMessage.MessageLevel.ERROR   -> Log.e(TAG, "JS: $text")
                    ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, "JS: $text")
                    else                                -> Log.d(TAG, "JS: $text")
                }
                listener?.onDebugMessage(text.take(60))
                return true
            }
        }

        loadUrl("file:///android_asset/avatar_renderer.html")
        mainHandler.postDelayed(readyGuard, 10_000L)
    }

    // ── JS → Kotlin bridge ────────────────────────────────────────────────────

    inner class AndroidBridge {
        @JavascriptInterface
        fun onRendererReady() {
            Log.i(TAG, "onRendererReady from JS")
            listener?.onDebugMessage("✅ Renderer READY")
            post { markReady() }
        }

        @JavascriptInterface
        fun onModelLoaded(name: String) {
            Log.i(TAG, "onModelLoaded: $name")
            listener?.onDebugMessage("✅ Model: $name")
            post { listener?.onModelLoaded(name) }
        }

        @JavascriptInterface
        fun onModelError(error: String) {
            Log.e(TAG, "onModelError: $error")
            listener?.onDebugMessage("❌ $error")
            post { listener?.onModelError(error) }
        }

        @JavascriptInterface
        fun onDebugMessage(msg: String) {
            Log.d(TAG, "JS debug: $msg")
            post { listener?.onDebugMessage(msg) }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Load a model from a file path.
     * Starts a local HTTP server so WebView fetches it over http://127.0.0.1
     * instead of encoding it as a potentially-huge base64 data URI.
     */
    fun loadModelFromPath(path: String) {
        val file = File(path)
        if (!file.exists() || !file.canRead()) {
            val err = "File not found or unreadable: $path"
            Log.e(TAG, err)
            listener?.onDebugMessage("❌ $err")
            listener?.onModelError(err)
            return
        }

        Log.i(TAG, "loadModelFromPath: ${file.name} (${file.length() / 1024} KB)")
        listener?.onDebugMessage("📦 ${file.name} (${file.length() / 1024} KB)")

        // Stop any previous server
        fileServer?.stop()

        val server = LocalFileServer(file)
        server.start()
        fileServer = server

        val url = server.url
        Log.i(TAG, "Serving model at $url")
        listener?.onDebugMessage("🌐 Serving on port ${server.port}")

        dispatchModelUrl(url)
    }

    /**
     * Load a model from a content:// URI.
     * Copies to cache first, then hands off to [loadModelFromPath].
     */
    fun loadModelFromUri(uri: Uri) {
        Log.i(TAG, "loadModelFromUri: $uri")
        listener?.onDebugMessage("📦 Copying from URI…")
        Thread {
            try {
                val ext = when {
                    uri.path?.endsWith(".gltf", true) == true -> ".gltf"
                    else -> ".glb"
                }
                val dest = File(context.cacheDir, "avatar_tmp$ext")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                } ?: run {
                    post {
                        listener?.onModelError("Cannot open URI: $uri")
                        listener?.onDebugMessage("❌ Cannot open URI")
                    }
                    return@Thread
                }
                Log.d(TAG, "Copied URI to ${dest.absolutePath} (${dest.length()} bytes)")
                post { loadModelFromPath(dest.absolutePath) }
            } catch (e: Exception) {
                Log.e(TAG, "loadModelFromUri failed", e)
                post {
                    listener?.onModelError(e.message ?: "URI copy error")
                    listener?.onDebugMessage("❌ URI error: ${e.message}")
                }
            }
        }.start()
    }

    fun playExpression(emotion: PetEmotion) {
        evaluateJavascript(
            "AvatarAPI.playExpression('${emotion.vrmExpression}',${emotion.weight},${emotion.durationSec});",
            null
        )
    }

    fun resetExpression() {
        evaluateJavascript("AvatarAPI.resetExpression();", null)
    }

    fun lookAt(x: Float, y: Float) {
        evaluateJavascript("AvatarAPI.lookAt($x,$y);", null)
    }

    fun setFraming(mode: String) {
        evaluateJavascript("AvatarAPI.setFraming('$mode');", null)
    }

    override fun destroy() {
        mainHandler.removeCallbacks(readyGuard)
        fileServer?.stop()
        fileServer = null
        super.destroy()
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun markReady() {
        mainHandler.removeCallbacks(readyGuard)
        if (rendererReady) return
        rendererReady = true
        Log.i(TAG, "Marked READY")
        listener?.onRendererReady()
        pendingModelUrl?.let { url ->
            pendingModelUrl = null
            Log.d(TAG, "Delivering queued model URL")
            evaluateJavascript("AvatarAPI.loadModel('$url');", null)
        }
    }

    private fun dispatchModelUrl(url: String) {
        if (rendererReady) {
            Log.d(TAG, "Dispatching model URL immediately: $url")
            evaluateJavascript("AvatarAPI.loadModel('$url');", null)
        } else {
            Log.d(TAG, "Renderer not ready — queuing model URL")
            listener?.onDebugMessage("⏳ Queued (waiting for JS)")
            pendingModelUrl = url
        }
    }
}
