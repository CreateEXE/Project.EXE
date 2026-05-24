package com.android.exe.rendering

import android.content.Context
import android.util.Log
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * Minimal HTTP server that serves two routes:
 *   GET /        → avatar_renderer.html (read from Android assets)
 *   GET /model   → the VRM/GLB model file (swappable at runtime)
 *
 * Serving from http://127.0.0.1 instead of file:// means ES-module CDN
 * imports work correctly in the WebView (file:// blocks cross-origin fetches
 * in module context even with MIXED_CONTENT_ALWAYS_ALLOW).
 */
class LocalFileServer(private val context: Context) {

    companion object {
        private const val TAG = "LocalFileServer"
    }

    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null

    @Volatile private var modelFile: File? = null

    var port: Int = 0
        private set

    val rootUrl:  String get() = "http://127.0.0.1:$port/"
    val modelUrl: String get() = "http://127.0.0.1:$port/model"

    fun start() {
        if (thread?.isAlive == true) return
        val ss = ServerSocket(0)
        serverSocket = ss
        port = ss.localPort
        Log.i(TAG, "Listening on port $port")

        thread = Thread {
            while (!ss.isClosed) {
                try {
                    val client = ss.accept()
                    Thread { handleClient(client) }.also { it.isDaemon = true }.start()
                } catch (_: SocketException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Accept error", e)
                }
            }
            Log.i(TAG, "Server stopped")
        }.also { it.isDaemon = true; it.name = "LocalFileServer"; it.start() }
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        thread = null
    }

    /** Swap the model file served at /model. Thread-safe. */
    fun setModelFile(file: File) {
        modelFile = file
        Log.i(TAG, "Model set: ${file.name} (${file.length() / 1024} KB)")
    }

    // ── Request handler ───────────────────────────────────────────────────────

    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                val input  = s.getInputStream().bufferedReader()
                val output = s.getOutputStream()

                val requestLine = input.readLine() ?: return
                // Drain headers
                var line = input.readLine()
                while (!line.isNullOrEmpty()) { line = input.readLine() }

                val path = requestLine.split(" ").getOrNull(1) ?: "/"
                Log.d(TAG, "GET $path")

                when {
                    path == "/" || path.startsWith("/?") -> serveHtml(output)
                    path == "/model"                     -> serveModel(output)
                    else -> {
                        val body = "Not found".toByteArray()
                        output.write("HTTP/1.1 404 Not Found\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
                        output.write(body)
                    }
                }
                output.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleClient error", e)
        }
    }

    private fun serveHtml(output: java.io.OutputStream) {
        try {
            val bytes = context.assets.open("avatar_renderer.html").readBytes()
            output.write(buildHeaders("text/html; charset=utf-8", bytes.size.toLong()).toByteArray())
            output.write(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "serveHtml error", e)
            val body = "asset error: ${e.message}".toByteArray()
            output.write("HTTP/1.1 500 Error\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
            output.write(body)
        }
    }

    private fun serveModel(output: java.io.OutputStream) {
        val file = modelFile
        if (file == null || !file.exists() || !file.canRead()) {
            val body = "model not set".toByteArray()
            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
            output.write(body)
            return
        }
        val mime = when {
            file.name.endsWith(".vrm",  ignoreCase = true) -> "model/gltf-binary"
            file.name.endsWith(".glb",  ignoreCase = true) -> "model/gltf-binary"
            file.name.endsWith(".gltf", ignoreCase = true) -> "model/gltf+json"
            else -> "application/octet-stream"
        }
        output.write(buildHeaders(mime, file.length()).toByteArray())
        file.inputStream().use { fis ->
            val buf = ByteArray(65536)
            var n = fis.read(buf)
            while (n > 0) { output.write(buf, 0, n); n = fis.read(buf) }
        }
        Log.d(TAG, "Served ${file.length()} bytes of ${file.name}")
    }

    private fun buildHeaders(mime: String, length: Long) = buildString {
        append("HTTP/1.1 200 OK\r\n")
        append("Content-Type: $mime\r\n")
        append("Content-Length: $length\r\n")
        append("Access-Control-Allow-Origin: *\r\n")
        append("Cache-Control: no-cache\r\n")
        append("Connection: close\r\n")
        append("\r\n")
    }
}
