package host.vorv.tools

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** Execute the browser's GET, checking every redirect before any connection. */
internal fun executeBrowserRequest(client: OkHttpClient, initial: Request): Response {
    require(!client.followRedirects && !client.followSslRedirects)
    var request = initial
    var redirects = 0
    while (true) {
        // Numeric IP routes bypass OkHttp's DNS hook. Hostname connections
        // still use PublicDns to check the addresses actually used by OkHttp.
        NetworkGuard.checkHostAllowed(request.url.host)
        val response = client.newCall(request).execute()
        if (response.code !in listOf(300, 301, 302, 303, 307, 308)) return response
        request = response.use {
            if (++redirects > 20) throw IOException("Too many browser redirects")
            val target = response.header("Location")?.let { request.url.resolve(it) }
                ?: throw IOException("Invalid browser redirect destination")
            // Only GETs are sent by the browser proxy; carry no headers or
            // credentials from the previous origin to the redirect target.
            Request.Builder().url(target).build()
        }
    }
}
