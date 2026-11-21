package host.vorv.linux

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * H-2 (code review, 2026-11): the guest shares the phone's network stack, so
 * without this proxy `curl http://169.254.169.254/` inside the "sandbox" read
 * cloud metadata. These tests drive real sockets through the guard: the
 * refusal comes from the real [host.vorv.tools.NetworkGuard] policy, and the
 * allowed path is pointed at a loopback upstream so the test never leaves the
 * machine (the real policy would, correctly, refuse that loopback address).
 */
class GuestEgressProxyTest {

    private var proxy: GuestEgressProxy? = null
    private var upstream: ServerSocket? = null

    @After
    fun tearDown() {
        proxy?.stop()
        upstream?.let { runCatching { it.close() } }
    }

    // The injected resolver is what makes the loopback upstream legal: the
    // guard connects to the address the policy vetted, never a second lookup.
    private fun allowAll(): GuestEgressProxy =
        GuestEgressProxy(resolve = { listOf(InetAddress.getByName("127.0.0.1")) })

    private fun listener(): ServerSocket =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).also { upstream = it }

    // ------------------------------------------------------------------- allow

    @Test
    fun `CONNECT to an allowed host tunnels bytes to the vetted address`() {
        val server = listener()
        val sawRequest = AtomicReference<String?>(null)
        val handler = Thread {
            runCatching {
                server.accept().use { s ->
                    sawRequest.set(BufferedReader(InputStreamReader(s.getInputStream())).readLine())
                    s.getOutputStream().apply {
                        write("pong\n".toByteArray())
                        flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val guard = allowAll().also { proxy = it }
        val port = guard.ensureStarted()
        assertEquals("the second start reuses the running proxy", port, guard.ensureStarted())

        Socket("127.0.0.1", port).use { client ->
            client.soTimeout = 5_000
            val out = client.getOutputStream()
            out.write(
                "CONNECT allowed.test:${server.localPort} HTTP/1.1\r\nHost: allowed.test\r\n\r\n"
                    .toByteArray(),
            )
            out.flush()
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            assertEquals("HTTP/1.1 200 Connection Established", reader.readLine())
            assertEquals("", reader.readLine())
            out.write("ping\n".toByteArray())
            out.flush()
            assertEquals("pong", reader.readLine())
        }
        handler.join(5_000)
        // The tunnel really reached the upstream, and the head was consumed by
        // the proxy (the upstream only sees what the client sent after it).
        assertEquals("ping", sawRequest.get())
    }

    @Test
    fun `an absolute-form request is relayed in origin form to the vetted address`() {
        val server = listener()
        val requestLine = AtomicReference<String?>(null)
        val handler = Thread {
            runCatching {
                server.accept().use { s ->
                    requestLine.set(BufferedReader(InputStreamReader(s.getInputStream())).readLine())
                    s.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray())
                        flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val port = allowAll().also { proxy = it }.ensureStarted()

        val response = request(
            port,
            "GET http://allowed.test:${server.localPort}/a?b=1 HTTP/1.1\r\n" +
                "Host: allowed.test\r\n\r\n",
        )
        handler.join(5_000)
        assertEquals("GET /a?b=1 HTTP/1.1", requestLine.get())
        assertTrue(response, response.startsWith("HTTP/1.1 200 OK"))
        assertTrue(response, response.endsWith("ok"))
    }

    // ------------------------------------------------------------------- deny

    @Test
    fun `the real policy refuses loopback, metadata and localhost for CONNECT`() {
        val port = GuestEgressProxy().also { proxy = it }.ensureStarted()
        for (host in listOf("127.0.0.1", "169.254.169.254", "localhost", "ip6-localhost")) {
            val response = request(port, "CONNECT $host:80 HTTP/1.1\r\nHost: $host\r\n\r\n")
            assertTrue("$host must be refused: $response", response.startsWith("HTTP/1.1 403"))
            // The reason is in the body so the guest (and the person reading a
            // build log) knows WHY, instead of a silent hang.
            assertTrue(
                "$host: the refusal must name the policy: $response",
                response.contains("Vorv network guard"),
            )
        }
    }

    @Test
    fun `metadata is refused for absolute-form requests as well`() {
        val port = GuestEgressProxy().also { proxy = it }.ensureStarted()
        val response = request(port, "GET http://169.254.169.254/latest/meta-data/ HTTP/1.1\r\nHost: 169.254.169.254\r\n\r\n")
        assertTrue(response, response.startsWith("HTTP/1.1 403"))
        assertTrue(response, response.contains("private/loopback"))
        assertFalse("no credentials may leak", response.contains("iam/security-credentials"))
    }

    @Test
    fun `a host refused by the injected policy never reaches the network`() {
        val server = listener()
        val accepted = AtomicReference(false)
        Thread {
            runCatching {
                server.accept().use { accepted.set(true) }
            }
        }.apply { isDaemon = true; start() }

        val guard = GuestEgressProxy(
            resolve = { host -> throw java.net.UnknownHostException("test policy says no to $host") },
        ).also { proxy = it }
        val port = guard.ensureStarted()

        val response = request(
            port,
            "CONNECT allowed.test:${server.localPort} HTTP/1.1\r\nHost: allowed.test\r\n\r\n",
        )
        assertTrue(response, response.startsWith("HTTP/1.1 403"))
        assertTrue(response, response.contains("test policy says no to allowed.test"))
        assertFalse("the upstream must never be contacted", accepted.get())
    }

    // ------------------------------------------------------------------ frames

    @Test
    fun `a request that is not proxy-shaped is refused with a reason`() {
        val port = GuestEgressProxy().also { proxy = it }.ensureStarted()
        val response = request(port, "GET /relative HTTP/1.1\r\nHost: example.com\r\n\r\n")
        assertTrue(response, response.startsWith("HTTP/1.1 400"))
        assertTrue(response, response.contains("absolute-form"))
    }

    @Test
    fun `oversized headers are refused instead of buffered without limit`() {
        val port = GuestEgressProxy().also { proxy = it }.ensureStarted()
        val junk = "GET http://example.com/ HTTP/1.1\r\nX-Pad: " + "a".repeat(9 * 1024) + "\r\n\r\n"
        val response = request(port, junk)
        assertTrue(response, response.startsWith("HTTP/1.1 400"))
        assertTrue(response, response.contains("oversized"))
    }

    // ---------------------------------------------------------------- plumbing

    private fun request(port: Int, text: String): String =
        Socket("127.0.0.1", port).use { client ->
            client.soTimeout = 5_000
            client.getOutputStream().apply {
                write(text.toByteArray())
                flush()
            }
            readAll(client.getInputStream())
        }

    private fun readAll(input: InputStream): String {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(1024)
        while (true) {
            val n = runCatching { input.read(chunk) }.getOrElse { -1 }
            if (n < 0) break
            buf.write(chunk, 0, n)
        }
        return buf.toString("ISO-8859-1")
    }
}
