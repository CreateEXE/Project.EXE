package com.android.exe.rendering

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.*
import com.android.exe.ai.PetEmotion
import java.io.File

/**
 * A WebView configured to render a VRM or GLB avatar via Three.js + @pixiv/three-vrm.
 * The HTML page is bundled in assets/avatar_renderer.html.
 *
 * Transparent background is achieved via:
 *   webView.setBackgroundColor(Color.TRANSPARENT)
 *   webView.background.alpha = 0
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class AvatarWebView(context: Context) : WebView(context) {

    companion object {
        private const val TAG = "AvatarWebView"
    }

    interface Listener {
        fun onModelLoaded(name: String)
        fun onModelError(error: String)
    }

    var listener: Listener? = null

    init {
        settings.apply {
            javaScriptEnabled        = true
            domStorageEnabled        = true
            allowFileAccess          = true
            allowContentAccess       = true
            mixedContentMode         = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode                = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            // Needed for importmap + ES modules
            javaScriptCanOpenWindowsAutomatically = false
        }

        // Transparent WebView background
        setBackgroundColor(0x00000000)
        background?.alpha = 0

        // Inject the Kotlin ↔ JS bridge
        addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                Log.e(TAG, "WebView error: ${error?.description} for ${request?.url}")
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean = false  // allow all navigation within the asset

            override fun onReceivedHttpAuthRequest(
                view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?
            ) { handler?.cancel() }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                val level = msg?.messageLevel()
                val text  = msg?.message() ?: return false
                when (level) {
                    ConsoleMessage.MessageLevel.ERROR -> Log.e(TAG, "JS: $text")
                    ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, "JS: $text")
                    else -> Log.d(TAG, "JS: $text")
                }
                return true
            }
        }

        loadUrl("file:///android_asset/avatar_renderer.html")
    }

    // ─── JS bridge (Kotlin called from JS) ────────────────────────────────────

    inner class AndroidBridge {
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

    // ─── Public API (Kotlin → JS) ─────────────────────────────────────────────

    /**
     * Load a VRM or GLB file from the given absolute path on device storage.
     * The file bytes are base64-encoded and sent as a data: URI to avoid
     * cross-origin file:// restrictions in the WebView.
     */
    fun loadModelFromPath(path: String) {
        val file = File(path)
        if (!file.exists()) {
            Log.e(TAG, "Model file not found: $path")
            listener?.onModelError("File not found: $path")
            return
        }

        // Determine mime type
        val mimeType = when {
            path.endsWith(".vrm", ignoreCase = true) -> "model/gltf-binary"
            path.endsWith(".glb", ignoreCase = true) -> "model/gltf-binary"
            path.endsWith(".gltf", ignoreCase = true) -> "model/gltf+json"
            else -> "application/octet-stream"
        }

        // Encode to base64 data URI (works for models up to ~50MB on modern Android)
        val bytes   = file.readBytes()
        val b64     = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val dataUri = "data:$mimeType;base64,$b64"

        evaluateJavascript("AvatarAPI.loadModel('$dataUri');", null)
        Log.i(TAG, "Sent model to JS (${bytes.size / 1024} KB)")
    }

    /** Load from a content:// Uri (e.g. from a file picker) */
    fun loadModelFromUri(uri: Uri) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.readBytes()
                ?: run { listener?.onModelError("Cannot open Uri: $uri"); return }
            val ext = when {
                uri.path?.endsWith(".vrm", ignoreCase = true) == true -> "model/gltf-binary"
                uri.path?.endsWith(".gltf", ignoreCase = true) == true -> "model/gltf+json"
                else -> "model/gltf-binary"
            }
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            evaluateJavascript("AvatarAPI.loadModel('data:$ext;base64,$b64');", null)
        } catch (e: Exception) {
            Log.e(TAG, "loadModelFromUri failed", e)
            listener?.onModelError(e.message ?: "Unknown error")
        }
    }

    /** Play a VRM expression by name. */
    fun playExpression(emotion: PetEmotion) {
        val js = "AvatarAPI.playExpression('${emotion.vrmExpression}', " +
                 "${emotion.weight}, ${emotion.durationSec});"
        evaluateJavascript(js, null)
    }

    /** Reset all expressions to neutral. */
    fun resetExpression() {
        evaluateJavascript("AvatarAPI.resetExpression();", null)
    }

    /** Point the avatar's gaze toward (x, y) in normalised coords (-1..1). */
    fun lookAt(x: Float, y: Float) {
        evaluateJavascript("AvatarAPI.lookAt($x, $y);", null)
    }

    /** Switch camera framing: "bust" | "face" | "full" */
    fun setFraming(mode: String) {
        evaluateJavascript("AvatarAPI.setFraming('$mode');", null)
    }
}
