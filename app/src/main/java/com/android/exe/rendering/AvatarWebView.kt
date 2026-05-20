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
    }

    var listener: Listener? = null
    private var pendingModelUri: String? = null   // stores a data-URI if renderer not ready yet
    private var rendererReady  = false

    // Guard timer: if JS never calls onRendererReady() within 8 s (e.g. because
    // an ES module failed to parse or a file is missing from assets), we fire it
    // ourselves so the overlay stops blocking that screen corner.
    private val mainHandler = Handler(Looper.getMainLooper())
    private val readyGuard  = Runnable {
        if (!rendererReady) {
            Log.w(TAG, "Ready guard fired — JS never called onRendererReady. Unblocking overlay.")
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
                Log.e(TAG, "WebView load error: ${error?.description} url=${request?.url}")
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                Log.d(TAG, "Page finished: $url")
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                val text = msg?.message() ?: return false
                when (msg.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR   -> Log.e(TAG, "JS: $text")
                    ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, "JS: $text")
                    else                                -> Log.d(TAG, "JS: $text")
                }
                return true
            }
        }

        loadUrl("file:///android_asset/avatar_renderer.html")

        // Start the 8-second guard. Canceled in markReady() if JS responds in time.
        mainHandler.postDelayed(readyGuard, 8_000L)
    }

    // ── JS → Kotlin bridge ────────────────────────────────────────────────────

    inner class AndroidBridge {
        @JavascriptInterface
        fun onRendererReady() {
            Log.i(TAG, "Renderer ready (JS callback)")
            post { markReady() }
        }

        @JavascriptInterface
        fun onModelLoaded(name: String) {
            Log.i(TAG, "Model loaded: $name")
            post { listener?.onModelLoaded(name) }
        }

        @JavascriptInterface
        fun onModelError(error: String) {
            Log.e(TAG, "Model error: $error")
            post { listener?.onModelError(error) }
        }
    }

    // ── Kotlin → JS ───────────────────────────────────────────────────────────

    fun loadModelFromPath(path: String) {
        val file = File(path)
        if (!file.exists()) {
            Log.e(TAG, "File not found: $path")
            listener?.onModelError("File not found: $path")
            return
        }

        val mimeType = when {
            path.endsWith(".vrm",  ignoreCase = true) -> "model/gltf-binary"
            path.endsWith(".glb",  ignoreCase = true) -> "model/gltf-binary"
            path.endsWith(".gltf", ignoreCase = true) -> "model/gltf+json"
            else                                       -> "application/octet-stream"
        }

        Thread {
            try {
                val bytes = file.readBytes()
                val b64   = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val uri   = "data:$mimeType;base64,$b64"
                Log.i(TAG, "Encoded model: ${bytes.size / 1024} KB")
                post { dispatchModelUri(uri) }
            } catch (e: Exception) {
                Log.e(TAG, "File read failed: ${e.message}", e)
                post { listener?.onModelError(e.message ?: "Read error") }
            }
        }.start()
    }

    fun loadModelFromUri(uri: Uri) {
        Thread {
            try {
                val bytes = context.contentResolver.openInputStream(uri)
                    ?.readBytes()
                    ?: run {
                        post { listener?.onModelError("Cannot open Uri: $uri") }
                        return@Thread
                    }
                val mime = if (uri.path?.endsWith(".gltf", ignoreCase = true) == true)
                    "model/gltf+json" else "model/gltf-binary"
                val b64  = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val data = "data:$mime;base64,$b64"
                Log.i(TAG, "loadModelFromUri encoded: ${bytes.size / 1024} KB")
                post { dispatchModelUri(data) }
            } catch (e: Exception) {
                Log.e(TAG, "loadModelFromUri failed: ${e.message}", e)
                post { listener?.onModelError(e.message ?: "Error") }
            }
        }.start()
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Single place that transitions rendererReady → true.
     * Called either by the JS bridge (happy path) or by readyGuard (timeout).
     * Must always run on the main thread.
     */
    private fun markReady() {
        mainHandler.removeCallbacks(readyGuard)   // cancel guard if JS fired it
        if (rendererReady) return                 // idempotent
        rendererReady = true
        listener?.onRendererReady()
        pendingModelUri?.let { uri ->
            pendingModelUri = null
            Log.d(TAG, "Delivering queued model to renderer")
            evaluateJavascript("AvatarAPI.loadModel('$uri');", null)
        }
    }

    private fun dispatchModelUri(dataUri: String) {
        if (rendererReady) {
            evaluateJavascript("AvatarAPI.loadModel('$dataUri');", null)
        } else {
            Log.d(TAG, "Renderer not ready — queuing model")
            pendingModelUri = dataUri
        }
    }

    fun playExpression(emotion: PetEmotion) {
        evaluateJavascript(
            "AvatarAPI.playExpression('${emotion.vrmExpression}',${emotion.weight},${emotion.durationSec});",
            null
        )
    }

    fun resetExpression()          = evaluateJavascript("AvatarAPI.resetExpression();", null)
    fun lookAt(x: Float, y: Float) = evaluateJavascript("AvatarAPI.lookAt($x,$y);", null)
    fun setFraming(mode: String)   = evaluateJavascript("AvatarAPI.setFraming('$mode');", null)
}
