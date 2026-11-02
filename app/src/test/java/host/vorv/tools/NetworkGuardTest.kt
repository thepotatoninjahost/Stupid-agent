package host.vorv.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class NetworkGuardTest {

    // ---------------------------------------------------- hostnames

    @Test fun `localhost blocked`() {
        assertTrue(NetworkGuard.isBlockedHost("localhost"))
        assertTrue(NetworkGuard.isBlockedHost("LOCALHOST"))
        assertTrue(NetworkGuard.isBlockedHost("localhost."))
    }

    @Test fun `local suffixes blocked`() {
        assertTrue(NetworkGuard.isBlockedHost("printer.local"))
        assertTrue(NetworkGuard.isBlockedHost("api.internal"))
        assertTrue(NetworkGuard.isBlockedHost("x.localhost"))
    }

    @Test fun `metadata endpoints blocked`() {
        assertTrue(NetworkGuard.isBlockedHost("metadata.google.internal"))
        assertTrue(NetworkGuard.isBlockedHost("metadata.goog"))
    }

    @Test fun `public hosts allowed`() {
        assertFalse(NetworkGuard.isBlockedHost("example.com"))
        assertFalse(NetworkGuard.isBlockedHost("api.z.ai"))
        assertFalse(NetworkGuard.isBlockedHost("github.com"))
    }

    // ---------------------------------------------------- addresses (IP literals — no DNS)

    @Test fun `loopback blocked`() {
        assertTrue(NetworkGuard.isBlockedAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(NetworkGuard.isBlockedAddress(InetAddress.getByName("::1")))
    }

    @Test fun `private v4 ranges blocked`() {
        assertTrue(NetworkGuard.isBlockedAddress(InetAddress.getByName("10.0.0.1")))
        assertTrue(NetworkGuard.isBlockedAddress(InetAddress.getByName("192.168.1.1")))
        assertTrue(NetworkGuard.isBlockedAddress(InetAddress.getByName("172.16.0.1")))
        assertTrue(NetworkGuard.isBlockedAddress(InetAddress.getByName("172.31.255.255")))
    }

    @Test fun `link local blocked`() {
        assertTrue(NetworkGuard.isBlockedAddress(InetAddress.getByName("169.254.169.254")))
        assertTrue(NetworkGuard.isBlockedAddress(InetAddress.getByName("fe80::1")))
    }

    @Test fun `carrier grade NAT blocked (audit L-4)`() {
        assertTrue(NetworkGuard.isBlockedAddress(InetAddress.getByName("100.64.0.1")))
        assertTrue(NetworkGuard.isBlockedAddress(InetAddress.getByName("100.127.255.255")))
        assertFalse("100.63.x.x is public", NetworkGuard.isBlockedAddress(InetAddress.getByName("100.63.0.1")))
        assertFalse("100.128.x.x is public", NetworkGuard.isBlockedAddress(InetAddress.getByName("100.128.0.1")))
    }

    @Test fun `ipv6 unique local blocked`() {
        assertTrue(NetworkGuard.isBlockedAddress(InetAddress.getByName("fd00::1")))
        assertTrue(NetworkGuard.isBlockedAddress(InetAddress.getByName("fc00::1")))
    }

    @Test fun `public addresses allowed`() {
        assertFalse(NetworkGuard.isBlockedAddress(InetAddress.getByName("8.8.8.8")))
        assertFalse(NetworkGuard.isBlockedAddress(InetAddress.getByName("1.1.1.1")))
        assertFalse(NetworkGuard.isBlockedAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }

    // ---------------------------------------------------- checkHostAllowed

    @Test fun `checkHostAllowed throws for localhost`() {
        val ex = runCatching { NetworkGuard.checkHostAllowed("localhost") }.exceptionOrNull()
        assertTrue(ex is java.net.UnknownHostException)
    }

    @Test fun `checkHostAllowed throws for private ip literal`() {
        val ex = runCatching { NetworkGuard.checkHostAllowed("192.168.1.1") }.exceptionOrNull()
        assertTrue(ex is java.net.UnknownHostException)
    }

    @Test fun `checkHostAllowed throws for any local`() {
        val ex = runCatching { NetworkGuard.checkHostAllowed("0.0.0.0") }.exceptionOrNull()
        assertTrue(ex is java.net.UnknownHostException)
    }
}
