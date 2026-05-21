package com.android.exe.rendering

import android.util.Log
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * Minimal single-file HTTP server.
 *
 * Starts on a random free port and serves exactly one file at GET /model
 * with the appropriate MIME type.  The WebView can then load it via
 *   http://127.0.0.1:<port>/model
 * which sidesteps the ~2 MB evaluateJavascript string limit that kills
 * base64 data-URI delivery for large VRM/GLB files.
 *
 * Usage:
 *   val server = LocalFileServer(file)
 *   server.start()
 *   val url = server.url          // "http://127.0.0.1:PORT/model"
 *   // ... tell WebView to load url ...
 *   server.stop()                 // call when overlay is detached
 */
class LocalFileServer(private val file: File) {

    companion object {
        private const val TAG = "LocalFileServer"
    }

    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null

    /** Available after [start] returns. */
    var port: Int = 0
        private set

    val url: String get() = "http://127.0.0.1:$port/model"

    fun start() {
        if (thread?.isAlive == true) return
        val ss = ServerSocket(0)   // OS picks a free port
        serverSocket = ss
        port = ss.localPort
        Log.i(TAG, "LocalFileServer listening on port $port for ${file.name}")

        thread = Thread {
            while (!ss.isClosed) {
                try {
                    val client = ss.accept()
                    Thread { handleClient(client) }.start()
                } catch (e: SocketException) {
                    // ss.close() was called — normal shutdown
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Accept error", e)
                }
            }
            Log.i(TAG, "LocalFileServer stopped")
        }.also {
            it.isDaemon = true
            it.name = "LocalFileServer"
            it.start()
        }
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        thread = null
        Log.i(TAG, "LocalFileServer stop() called")
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                val input  = s.getInputStream().bufferedReader()
                val output = s.getOutputStream()

                // Read the HTTP request line (we serve everything the same way)
                val requestLine = input.readLine() ?: return
                Log.d(TAG, "Request: $requestLine")

                // Drain remaining headers
                var line = input.readLine()
                while (!line.isNullOrEmpty()) { line = input.readLine() }

                if (!file.exists() || !file.canRead()) {
                    val body = "File not found".toByteArray()
                    output.write(
                        "HTTP/1.1 404 Not Found\r\nContent-Length: ${body.size}\r\n\r\n"
                            .toByteArray()
                    )
                    output.write(body)
                    return
                }

                val mime = when {
                    file.name.endsWith(".vrm",  ignoreCase = true) -> "model/gltf-binary"
                    file.name.endsWith(".glb",  ignoreCase = true) -> "model/gltf-binary"
                    file.name.endsWith(".gltf", ignoreCase = true) -> "model/gltf+json"
                    else -> "application/octet-stream"
                }

                val headers = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: $mime\r\n")
                    append("Content-Length: ${file.length()}\r\n")
                    append("Access-Control-Allow-Origin: *\r\n")
                    append("Cache-Control: no-cache\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }
                output.write(headers.toByteArray())

                file.inputStream().use { fis ->
                    val buf = ByteArray(65536)
                    var n = fis.read(buf)
                    while (n > 0) {
                        output.write(buf, 0, n)
                        n = fis.read(buf)
                    }
                }
                output.flush()
                Log.d(TAG, "Served ${file.length()} bytes of ${file.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleClient error", e)
        }
    }
}
