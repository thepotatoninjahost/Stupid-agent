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
        // Carrier-grade NAT 100.64.0.0/10 (audit L-4): first octet 100,
        // second octet 64..127 — the ISP-shared address space mobile
        // carriers use internally; same class of "not the public internet"
        // as RFC1918.
        val bytes = addr.address
        if (bytes.size == 4) {
            val o1 = bytes[0].toInt() and 0xFF
            val o2 = bytes[1].toInt() and 0xFF
            if (o1 == 100 && o2 in 64..127) return true
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
        checkLiteralHostAllowed(host)
        if (parseLiteral(host) != null) return
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

    /** Check canonical URL hosts without resolving names; PublicDns checks DNS results. */
    internal fun checkLiteralHostAllowed(host: String) {
        if (isBlockedHost(host)) {
            throw UnknownHostException("Vorv network guard: host '$host' is not allowed")
        }
        // IP literal?
        val literal = parseLiteral(host)
        if (literal != null) {
            if (isBlockedAddress(literal)) {
                throw UnknownHostException("Vorv network guard: address '$host' is private/loopback")
            }
        }
    }

    /**
     * Syntactic IP-literal detection (audit L-8): a hostname that IS an
     * IPv4/IPv6 literal is parsed without any DNS lookup — InetAddress
     * .getByName performs a lookup for non-literals despite the old
     * comment claiming otherwise.
     */
    private val ipv4Literal = Regex(
        "^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    private fun parseLiteral(host: String): InetAddress? {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        // IPv4 literal — validate octets syntactically, then build.
        ipv4Literal.find(h)?.let { m ->
            val octets = m.destructured.toList().map { it.toInt() }
            if (octets.all { it in 0..255 }) {
                return runCatching { InetAddress.getByAddress(
                    octets.map { it.toByte() }.toByteArray()) }.getOrNull()
            }
            return null
        }
        // IPv6 literal — contains a colon and only hex/colon/dot/percent chars.
        if (h.contains(':') &&
            h.all { it.isDigit() || it == ':' || it == '.' || it == '%' ||
                (it.lowercaseChar() in 'a'..'f') }
        ) {
            return runCatching { InetAddress.getByName(h) }.getOrNull() // literal: no DNS
        }
        return null // a real hostname — resolution path decides
    }

    /**
     * OkHttp Dns implementation: system resolution, then reject if ANY
     * returned address is private/loopback. Wiring this into an OkHttpClient
     * guards resolved hostnames; IP literals require a separate destination check.
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
