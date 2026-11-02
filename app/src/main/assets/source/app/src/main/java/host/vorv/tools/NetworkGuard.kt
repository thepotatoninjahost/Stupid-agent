package host.vorv.tools

import okhttp3.Dns
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Egress guard for every network-capable tool (audit: SSRF/local-network
 * hardening). web_fetch resolves through [PublicDns] — an OkHttp Dns that
 * refuses loopback, link-local, site-local, unique-local and any-local
 * addresses — and browser_automate pre-checks the host with [checkHostAllowed]
 * before any WebView load. Together this stops the LLM from aiming the agent
 * at localhost services, router admin panels, or cloud metadata endpoints.
 */
object NetworkGuard {

    /** Hostnames never allowed, regardless of what they resolve to. */
    private val BLOCKED_HOSTS = setOf(
        "localhost",
        "ip6-localhost",
        "ip6-loopback",
        "metadata.google.internal",
        "metadata.goog",
    )

    /** Hostname suffixes never allowed. */
    private val BLOCKED_SUFFIXES = listOf(".localhost", ".local", ".internal")

    fun isBlockedHost(host: String): Boolean {
        val h = host.trim().lowercase().trimEnd('.')
        if (h.isEmpty()) return true
        if (h in BLOCKED_HOSTS) return true
        if (BLOCKED_SUFFIXES.any { h.endsWith(it) }) return true
        // Bare IP literal — let isBlockedAddress judge it.
        return false
    }

    fun isBlockedAddress(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress) return true
        if (addr.isLoopbackAddress) return true
        if (addr.isLinkLocalAddress) return true
        if (addr.isSiteLocalAddress) return true
        if (addr.isMulticastAddress) return true
        if (addr is Inet6Address) {
            // IPv6 unique-local fc00::/7 (siteLocal only covers deprecated fec0::/10).
            val first = addr.getAddress()[0].toInt() and 0xFF
            if (first and 0xFE == 0xFC) return true
        }
        return false
    }

    /**
     * Resolves [host] and throws [UnknownHostException] when the host or ANY
     * resolved address is private/loopback. Used by browser_automate before
     * loadUrl (WebView has no DNS hook, so this is the check point).
     */
    @Throws(UnknownHostException::class)
    fun checkHostAllowed(host: String) {
        if (isBlockedHost(host)) {
            throw UnknownHostException("Vorv network guard: host '$host' is not allowed")
        }
        // IP literal?
        val literal = parseLiteral(host)
        if (literal != null) {
            if (isBlockedAddress(literal)) {
                throw UnknownHostException("Vorv network guard: address '$host' is private/loopback")
            }
            return
        }
        val addrs = try {
            InetAddress.getAllByName(host)
        } catch (e: UnknownHostException) {
            throw e
        }
        val bad = addrs.firstOrNull { isBlockedAddress(it) }
        if (bad != null) {
            throw UnknownHostException(
                "Vorv network guard: '$host' resolves to a private/loopback address (${bad.hostAddress})"
            )
        }
    }

    private fun parseLiteral(host: String): InetAddress? = try {
        InetAddress.getByName(host) // for literals, no DNS lookup happens
    } catch (e: Exception) {
        null
    }

    /**
     * OkHttp Dns implementation: system resolution, then reject if ANY
     * returned address is private/loopback. Wiring this into an OkHttpClient
     * makes SSRF-to-LAN impossible for every request through that client.
     */
    object PublicDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (isBlockedHost(hostname)) {
                throw UnknownHostException("Vorv network guard: host '$hostname' is not allowed")
            }
            val addrs = Dns.SYSTEM.lookup(hostname)
            val bad = addrs.firstOrNull { isBlockedAddress(it) }
            if (bad != null) {
                throw UnknownHostException(
                    "Vorv network guard: '$hostname' resolves to a private/loopback address (${bad.hostAddress})"
                )
            }
            return addrs
        }
    }
}
