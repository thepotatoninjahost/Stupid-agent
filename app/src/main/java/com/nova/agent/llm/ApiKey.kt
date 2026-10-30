package com.nova.agent.llm

/**
 * API key for the Z.ai GLM-4.6 endpoint.
 *
 * REPLACE THIS with your own API key from https://z.ai/
 * Get one at https://z.ai/manage-apikey
 *
 * SECURITY NOTE: For production, store this in local.properties or
 * a secrets file and inject via BuildConfig. For development, putting it
 * here is fine — the app only ships as an APK on your own device.
 */
object ApiKey {
    // ⚠️ REPLACE WITH YOUR OWN KEY
    const val ZAI_API_KEY: String = "REPLACE_WITH_YOUR_ZAI_API_KEY"
}
