package com.projectexe.bridge

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.projectexe.BuildConfig
import com.projectexe.ai.arbitrator.ArbitratorResult
import com.projectexe.ai.soul.EmotionalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AnimationBridge(private val webView: WebView, private val scope: CoroutineScope) {
    companion object {
        private const val TAG = "EXE.Bridge"
        private const val JS_EXPR    = "window.EXERenderer.setExpression"
        private const val JS_ANIM    = "window.EXERenderer.triggerAnimation"
        private const val JS_TEXT    = "window.EXERenderer.displayText"
        private const val JS_LOADING = "window.EXERenderer.setLoading"
        private const val JS_VRM     = "window.EXERenderer.loadVRM"
    }

    var onUserInputReceived: ((String) -> Unit)? = null
    var onCloseRequested:    (() -> Unit)?       = null
    @Volatile private var ready = false

    fun dispatchResponse(r: ArbitratorResult) {
        if (r.isThinking) { dispatchThinking(); return }
        if (r.text.isEmpty()) return
        scope.launch(Dispatchers.Main) {
            if (!ready) delay(700)
            js("$JS_ANIM('${r.animationTrigger.esc()}')")
            js("$JS_EXPR('${r.expression.esc()}', 1.0)")
            js("$JS_LOADING(false)")
            val ms = (r.text.length / 14.0 * 1000).toLong().coerceIn(2500, 12000)
            js("$JS_TEXT('${r.text.esc()}', $ms)")
        }
    }

    fun dispatchThinking() = scope.launch(Dispatchers.Main) {
        js("$JS_ANIM('thinking')"); js("$JS_EXPR('thinking', 0.8)"); js("$JS_LOADING(true)")
    }

    fun loadVrmFile(file: String) = scope.launch(Dispatchers.Main) {
        ready = false; js("$JS_VRM('${file.esc()}')")
    }

    @JavascriptInterface fun onUserInputSubmitted(text: String) {
        if (text.isBlank()) return
        scope.launch(Dispatchers.Main) { onUserInputReceived?.invoke(text.trim().take(2000)) }
    }

    @JavascriptInterface fun onRendererReady() {
        ready = true; scope.launch(Dispatchers.Main) { js("$JS_ANIM('idle')") }
    }

    @JavascriptInterface fun onRendererError(code: Int, msg: String) {
        Log.e(TAG, "Renderer error $code: $msg"); ready = false
        if (code == 1001) scope.launch(Dispatchers.Main) { delay(1500); webView.reload() }
    }

    @JavascriptInterface fun requestClose() {
        scope.launch(Dispatchers.Main) { onCloseRequested?.invoke() }
    }

    @JavascriptInterface fun requestHaptic(ms: Int) {
        val d = ms.coerceIn(10, 200).toLong()
        scope.launch(Dispatchers.Main) {
            try {
                val v = webView.context.getSystemService(Vibrator::class.java) ?: return@launch
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    v.vibrate(VibrationEffect.createOneShot(d, VibrationEffect.DEFAULT_AMPLITUDE))
                else @Suppress("DEPRECATION") v.vibrate(d)
            } catch (_: Exception) {}
        }
    }

    private fun js(s: String) = webView.evaluateJavascript(s) { r ->
        if (BuildConfig.DEBUG && r != null && r != "null") Log.v(TAG, "JS: $r")
    }
    private fun String.esc() = replace("\\","\\\\").replace("'","\\'").replace("\n","\\n").replace("\r","").replace("\"","\\\"")
}
