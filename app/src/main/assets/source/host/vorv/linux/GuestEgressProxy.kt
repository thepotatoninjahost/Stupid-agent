package host.vorv.linux

import host.vorv.tools.NetworkGuard
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI

/**
 * H-2 (code review, 2026-11): the guest used to reach the network directly —
 * proot shares the phone's network stack, so `curl http://169.254.169.254/`
 * inside the "sandbox" read cloud metadata, and `curl http://127.0.0.1:8080/`
 * reached services the phone itself runs. [NetworkGuard] only ever covered the
 * app's own HTTP clients.
 *
 * This is a loopback HTTP proxy the guest is pointed at with `http_proxy` and
 * friends. Every host is vetted through the SAME policy ([NetworkGuard.resolveChecked]
 * returns the addresses it vetted, so the connection cannot be rebound to a
 * different one between check and connect), and the guest gets a 403 carrying
 * the refusal reason instead of a connection.
 *
 * The socket binds to 127.0.0.1, so only processes in the same network
 * namespace (the guest) can use it. It handles HTTP: absolute-form requests and
 * `CONNECT` tunnels; raw sockets, UDP and tools that ignore proxy variables are
 * not covered — that is stated in the tool descriptions and DEVELOPMENT.md.
 *
 * The policy is injected so tests can point an "allowed" host at a loopback
 * upstream without weakening the real guard.
 */
internal class GuestEgressProxy(
    private val resolve: (String) -> List<InetAddress> = NetworkGuard::resolveChecked,
) {

    private var server: ServerSocket? = null
    private var boundPort = 0

    /** Starts the proxy once and returns the loopback port; later calls reuse it. */
    @Synchronized
    fun ensureStarted(): Int {
        server?.let { return boundPort }
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 50)
        server = socket
        boundPort = socket.localPort
        Thread({ acceptLoop(socket) }, "vorv-guest-egress").apply { isDaemon = true }.start()
        return boundPort
    }

    /** Closes the listener. In-flight connections finish on their own threads. */
    @Synchronized
    fun stop() {
        closeQuietly(server)
        server = null
        boundPort = 0
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: java.io.IOException) {
                return // the listener was closed under us: nothing left to serve
            } catch (e: RuntimeException) {
                note("accept failed (${e.javaClass.simpleName}: ${e.message}); the guard stops here")
                return
            }
            Thread({ handle(client) }, "vorv-guest-egress-conn").apply { isDaemon = true }.start()
        }
    }

    /**
     * One guest connection.
     *
     * Nothing may escape this function. It runs on a bare thread with no
     * caller, so an uncaught exception reaches the app's default handler and
     * KILLS THE WHOLE APP. A guest that opens a connection and drops it is
     * normal traffic (curl probes the proxy exactly this way), not a crash.
     */
    private fun handle(client: Socket) {
        try {
            serve(client)
        } catch (e: Exception) {
            note("connection ended early (${e.javaClass.simpleName}: ${e.message})")
        } finally {
            closeQuietly(client)
        }
    }

    private fun serve(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val head = readHead(input)
        if (head == null) {
            respond(client.getOutputStream(), 400, "Bad Request", "missing or oversized request headers")
            return
        }
        val requestLine = head.substringBefore("\r\n")
        val parts = requestLine.split(' ')
        if (parts.size != 3) {
            respond(client.getOutputStream(), 400, "Bad Request", "malformed request line")
            return
        }
        val (method, target, _) = parts
        when {
            method.equals("CONNECT", ignoreCase = true) -> tunnel(client, input, target)
            target.startsWith("http://", ignoreCase = true) -> relay(client, input, head, method, target)
            else -> respond(
                client.getOutputStream(), 400, "Bad Request",
                "the guest egress guard only handles HTTP proxy requests (CONNECT and absolute-form http://)",
            )
        }
    }

    /** Log line for failures this thread has no caller to report to. */
    private fun note(message: String) {
        System.err.println("Vorv guest egress guard: $message")
    }

    // ------------------------------------------------------------------ CONNECT

    private fun tunnel(client: Socket, input: InputStream, target: String) {
        val hostPort = splitHostPort(target)
        if (hostPort == null) {
            respond(client.getOutputStream(), 400, "Bad Request", "CONNECT target must be host:port")
            return
        }
        val (host, port) = hostPort
        val upstream = connectChecked(host, port, client) ?: return
        client.getOutputStream().apply {
            write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            flush()
        }
        pump(client, input, upstream)
    }

    // ------------------------------------------------------------ absolute-form

    private fun relay(client: Socket, input: InputStream, head: String, method: String, target: String) {
        val uri = try {
            URI(target)
        } catch (e: java.net.URISyntaxException) {
            null
        }
        val host = uri?.host
        if (uri == null || host.isNullOrBlank()) {
            respond(client.getOutputStream(), 400, "Bad Request", "absolute-form request has no host")
            return
        }
        val port = if (uri.port == -1) 80 else uri.port
        val upstream = connectChecked(host, port, client) ?: return
        val path = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") +
            (uri.rawQuery?.let { "?$it" } ?: "")
        val headers = head.substringAfter("\r\n")
        try {
            val out = upstream.getOutputStream()
            out.write("$method $path HTTP/1.1\r\n".toByteArray())
            out.write(headers.toByteArray(Charsets.ISO_8859_1))
            out.flush()
        } catch (e: java.io.IOException) {
            // The upstream hung up before the request was written; the client
            // sees the closed connection instead of a hang.
            closeQuietly(upstream)
            return
        }
        pump(client, input, upstream)
    }

    /**
     * Applies the policy and opens the connection. Returns null when the request
     * was answered with 403/502 and must not continue.
     */
    private fun connectChecked(host: String, port: Int, client: Socket): Socket? {
        // ONE resolution, and the connection uses the address that was vetted:
        // resolving again would reopen the DNS-rebinding window.
        val address = try {
            resolve(host).first()
        } catch (error: Exception) {
            respond(
                client.getOutputStream(), 403, "Forbidden",
                error.message ?: "Vorv network guard: '$host' could not be resolved",
            )
            return null
        }
        val upstream = try {
            Socket().apply {
                connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
                soTimeout = 0
            }
        } catch (error: java.io.IOException) {
            respond(
                client.getOutputStream(), 502, "Bad Gateway",
                "the guest egress guard could not reach $host:$port (${error.message ?: "connection failed"})",
            )
            return null
        }
        return upstream
    }

    // ------------------------------------------------------------------ plumbing

    private fun pump(client: Socket, clientIn: InputStream, upstream: Socket) {
        val up = Thread({
            copyQuietly(clientIn, upstream)
            halfCloseQuietly(upstream)
        }, "vorv-guest-egress-up").apply { isDaemon = true }
        up.start()
        copyQuietly(upstream.getInputStream(), client)
        // Close the upstream first: the client half of the pump is blocked in a
        // read that only this close can end, and joining on it would stall the
        // connection thread until the timeout.
        closeQuietly(upstream)
        halfCloseQuietly(client)
        up.join(2_000)
    }

    /** Reads request line + headers, capped at [MAX_HEAD]; null when missing/oversized. */
    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var matched = 0
        while (buf.size() < MAX_HEAD) {
            val b = input.read()
            if (b == -1) return null
            buf.write(b)
            matched = when {
                matched == 0 && b == CR -> 1
                matched == 1 && b == LF -> 2
                matched == 2 && b == CR -> 3
                matched == 3 && b == LF -> 4
                b == CR -> 1
                else -> 0
            }
            if (matched == 4) return buf.toString("ISO-8859-1")
        }
        return null
    }

    private fun respond(out: OutputStream, code: Int, reason: String, body: String) {
        val bytes = body.toByteArray()
        try {
            out.write(
                ("HTTP/1.1 $code $reason\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(),
            )
            out.write(bytes)
            out.flush()
        } catch (e: java.io.IOException) {
            // The guest already hung up; there is nobody left to tell.
        }
    }

    private fun splitHostPort(target: String): Pair<String, Int>? {
        val host: String
        val portText: String
        if (target.startsWith("[")) {
            val end = target.indexOf(']')
            if (end < 0) return null
            host = target.substring(1, end)
            portText = target.substring(end + 1).removePrefix(":")
        } else {
            val colon = target.lastIndexOf(':')
            if (colon <= 0) return null
            host = target.substring(0, colon)
            portText = target.substring(colon + 1)
        }
        val port = portText.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return host to port
    }

    // ---- best-effort plumbing: the other side is going away regardless ----

    /** Ends the transfer; a socket that is already closed throws here and nowhere else. */
    private fun copyQuietly(from: InputStream, to: Socket) {
        try {
            from.copyTo(to.getOutputStream())
        } catch (e: java.io.IOException) {
            // One side closed mid-transfer: that ends the connection, by design.
        }
    }

    private fun halfCloseQuietly(socket: Socket) {
        try {
            socket.shutdownOutput()
        } catch (e: java.io.IOException) {
            // Already closed.
        }
    }

    private fun closeQuietly(closeable: java.io.Closeable?) {
        try {
            closeable?.close()
        } catch (e: java.io.IOException) {
            // Already closed.
        }
    }

    private companion object {
        const val MAX_HEAD = 8 * 1024
        const val CONNECT_TIMEOUT_MS = 10_000
        const val CR = '\r'.code
        const val LF = '\n'.code
    }
}
