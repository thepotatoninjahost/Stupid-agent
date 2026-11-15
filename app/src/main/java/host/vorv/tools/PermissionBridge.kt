package host.vorv.tools

/**
 * PermissionBridge — the tool → UI channel for RUNTIME permission requests
 * (v0.6.14).
 *
 * WHY THIS EXISTS. Some grants cannot be routed through a tool result: the
 * consent is a system dialog, and its answer arrives later, on the UI
 * thread. This bridge is the channel — a tool registers interest, the UI
 * shows the one-tap dialog, and the result comes back to the suspended
 * tool. Before v0.6.14 the shell tool hit such a wall and could only tell
 * the user to walk a five-step Settings path by hand — an honest error, but
 * the cheapest possible fix (one tap) was never offered.
 *
 * WHY A PROCESS-LEVEL SINGLETON instead of a ToolContext field: binary
 * compatibility with REGISTERED PATCHES. A patch compiled by the healer
 * against an earlier Vorv embeds direct constructor/field references
 * (e.g. `ToolContext.<init>(String, Int, String, Function)`); changing
 * ToolContext's or KernelApi.run's signature would make every
 * previously-approved patch crash with NoSuchMethodError /
 * AbstractMethodError the moment it runs. A process-level volatile
 * reference adds zero signatures, so old patches keep working exactly as
 * they did (they simply never request permissions — they still fail
 * honestly at the permission wall, which is the pre-v0.6.14 behavior they
 * were built for).
 *
 * AgentViewModel installs the handler at startup; tools call [request].
 * When no handler is installed (unit tests, background workers before the
 * UI exists), request() returns false — fail closed, and callers keep
 * their honest error paths.
 */
object PermissionBridge {

    /** Installed by AgentViewModel; null = no UI to ask on. */
    @Volatile
    var handler: (suspend (permission: String) -> Boolean)? = null

    /**
     * Ask the user to grant [permission] via the system runtime dialog.
     * Returns true only if the grant actually happened (the handler
     * re-checks after the dialog). A null handler, a denial, a dismissal
     * or a timeout all return false — the caller then reports the wall
     * honestly with the manual fallback path.
     */
    suspend fun request(permission: String): Boolean =
        handler?.invoke(permission) ?: false
}
