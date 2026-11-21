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
        runCatching { server?.close() }
        server = null
        boundPort = 0
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: return
            Thread({ handle(client) }, "vorv-guest-egress-conn").apply { isDaemon = true }.start()
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            val input = BufferedInputStream(socket.getInputStream())
            val head = readHead(input)
            if (head == null) {
                respond(socket.getOutputStream(), 400, "Bad Request", "missing or oversized request headers")
                return
            }
            val requestLine = head.substringBefore("\r\n")
            val parts = requestLine.split(' ')
            if (parts.size != 3) {
                respond(socket.getOutputStream(), 400, "Bad Request", "malformed request line")
                return
            }
            val (method, target, _) = parts
            when {
                method.equals("CONNECT", ignoreCase = true) -> tunnel(socket, input, target)
                target.startsWith("http://", ignoreCase = true) -> relay(socket, input, head, method, target)
                else -> respond(
                    socket.getOutputStream(), 400, "Bad Request",
                    "the guest egress guard only handles HTTP proxy requests (CONNECT and absolute-form http://)",
                )
            }
        }
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
        val uri = runCatching { URI(target) }.getOrNull()
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
        runCatching {
            val out = upstream.getOutputStream()
            out.write("$method $path HTTP/1.1\r\n".toByteArray())
            out.write(headers.toByteArray(Charsets.ISO_8859_1))
            out.flush()
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
        val address = runCatching { resolve(host).first() }.getOrElse { error ->
            respond(
                client.getOutputStream(), 403, "Forbidden",
                error.message ?: "Vorv network guard: '$host' could not be resolved",
            )
            return null
        }
        val upstream = runCatching {
            Socket().apply {
                connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
                soTimeout = 0
            }
        }.getOrElse { error ->
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
            runCatching { clientIn.copyTo(upstream.getOutputStream()) }
            runCatching { upstream.shutdownOutput() }
        }, "vorv-guest-egress-up").apply { isDaemon = true }
        up.start()
        runCatching { upstream.getInputStream().copyTo(client.getOutputStream()) }
        // Close the upstream first: the client half of the pump is blocked in a
        // read that only this close can end, and joining on it would stall the
        // connection thread until the timeout.
        runCatching { upstream.close() }
        runCatching { client.shutdownOutput() }
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
        runCatching {
            out.write(
                ("HTTP/1.1 $code $reason\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(),
            )
            out.write(bytes)
            out.flush()
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

    private companion object {
        const val MAX_HEAD = 8 * 1024
        const val CONNECT_TIMEOUT_MS = 10_000
        const val CR = '\r'.code
        const val LF = '\n'.code
    }
}
