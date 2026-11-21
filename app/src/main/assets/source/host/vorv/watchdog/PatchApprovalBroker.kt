package host.vorv.watchdog

/**
 * PatchApprovalBroker — the one place that can ask the screen to run the
 * system's own user-authentication prompt for a patch approval.
 *
 * WHY A BROKER: the polite dialog can be shown from anywhere (healer, crash
 * dialog, the self_fix tool), but the signature that counts has to come from
 * the Activity, because the system's biometric/PIN prompt is attached to it.
 * Tools and the healer therefore ask here, and the Activity answers while it
 * exists.
 *
 * This is not a trust boundary and does not pretend to be one: anything in
 * the process could replace [host]. What keeps that honest is that a replaced
 * host can only hand back a string, and [HotPatcher.registerApprovedPatch]
 * accepts one only if the device's Keystore certificate verifies it for that
 * exact patch. No host, no approval — it fails closed, it does not fall back.
 */
object PatchApprovalBroker {

    /** Set by the Activity; null when no screen can ask the user. */
    @Volatile
    private var host: (suspend (statement: String) -> String?)? = null

    fun attach(requester: suspend (statement: String) -> String?) {
        host = requester
    }

    fun detach(requester: Any? = null) {
        host = null
    }

    /**
     * The device-signed approval for [statement], or null when the user
     * declined, the prompt failed, or no screen is available.
     */
    suspend fun request(statement: String): String? = host?.invoke(statement)
}
