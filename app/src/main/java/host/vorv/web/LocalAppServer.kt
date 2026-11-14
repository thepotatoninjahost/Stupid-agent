package host.vorv.web

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Serves one of the agent's projects over loopback so it can be *opened like a
 * real app* instead of being pointed at with `file://`.
 *
 * Why a server rather than a file URL:
 *  - `file://` breaks `fetch`, XHR, ES modules, service workers and absolute
 *    paths — a page that works when served would fail there, so "tested" would
 *    be a lie.
 *  - It binds to 127.0.0.1 only. Nothing on the network, or on the phone, can
 *    reach it; the port is picked by the OS and lives as long as the test.
 *
 * No Android types, so the whole thing is exercised by ordinary JVM tests.
 */
class LocalAppServer(
    private val root: File,
    requestedPort: Int = 0,
) : Closeable {

    private val socket = ServerSocket(requestedPort, 8, InetAddress.getLoopbackAddress())
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "vorv-app-server").apply { isDaemon = true } }
    @Volatile private var running = true

    val port: Int get() = socket.localPort
    /** Trailing slash included, so callers can concatenate a relative path. */
    val baseUrl: String get() = "http://127.0.0.1:$port/"

    fun start(): LocalAppServer {
        require(root.isDirectory) { "project directory does not exist: $root" }
        Thread({
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break // closed
                }
                pool.execute { serve(client) }
            }
        }, "vorv-app-accept").apply { isDaemon = true }.start()
        return this
    }

    private fun serve(client: Socket) {
        client.use { c ->
            c.soTimeout = SOCKET_TIMEOUT_MS
            val input = c.getInputStream()
            val output = c.getOutputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2 || parts[0] != "GET" && parts[0] != "HEAD") {
                respond(output, 405, "text/plain", "method not allowed".toByteArray())
                return
            }
            drainHeaders(input)
            val target = resolve(parts[1])
            when {
                target == null -> respond(output, 403, "text/plain", "forbidden".toByteArray())
                target.isDirectory -> {
                    val index = File(target, "index.html")
                    if (index.isFile) sendFile(output, index, head = parts[0] == "HEAD")
                    else respond(output, 404, "text/plain", "not found".toByteArray())
                }
                target.isFile -> sendFile(output, target, head = parts[0] == "HEAD")
                else -> respond(output, 404, "text/plain", "not found: ${parts[1]}".toByteArray())
            }
        }
    }

    /** Null when the path escapes [root] — traversal is refused, never clamped. */
    private fun resolve(rawTarget: String): File? {
        val path = URLDecoder.decode(rawTarget.substringBefore('?').substringBefore('#'), "UTF-8")
        if (path.contains('\u0000')) return null
        val relative = path.trimStart('/')
        val candidate = File(root, relative).canonicalFile
        val base = root.canonicalFile
        val allowed = candidate == base || candidate.path.startsWith(base.path + File.separator)
        return if (allowed) candidate else null
    }

    private fun sendFile(output: OutputStream, file: File, head: Boolean) {
        val body = file.readBytes()
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: ${contentType(file.name)}\r\n")
            append("Content-Length: ${body.size}\r\n")
            // A test must see the file it just wrote, not a cached older one.
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray()
        output.write(header)
        if (!head) output.write(body)
        output.flush()
    }

    private fun respond(output: OutputStream, code: Int, type: String, body: ByteArray) {
        val header = ("HTTP/1.1 $code ${reason(code)}\r\n" +
            "Content-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()
        output.write(header)
        output.write(body)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > MAX_LINE) return null
        }
    }

    private fun drainHeaders(input: InputStream) {
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) return
        }
    }

    override fun close() {
        running = false
        runCatching { socket.close() }
        pool.shutdownNow()
        pool.awaitTermination(1, TimeUnit.SECONDS)
    }

    companion object {
        private const val SOCKET_TIMEOUT_MS = 5_000
        private const val MAX_LINE = 8_192

        private fun reason(code: Int) = when (code) {
            200 -> "OK"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Error"
        }

        fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "woff2" -> "font/woff2"
            "wasm" -> "application/wasm"
            "txt", "md" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }
    }
}
