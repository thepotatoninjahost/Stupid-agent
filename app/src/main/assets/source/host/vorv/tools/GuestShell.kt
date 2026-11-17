package host.vorv.tools

import host.vorv.linux.LinuxSystem

/**
 * The thing `shell` runs commands on.
 *
 * It exists so the tool can be tested without an Android device: a test hands
 * it a fake that answers whatever the test needs, and the production path
 * hands it [LinuxSystem] — Vorv's own Debian guest.
 */
interface GuestShell {

    /** False when this build shipped without the Linux payload. */
    fun isAvailable(): Boolean

    /**
     * Runs one command inside the guest.
     *
     * @param binds host folder → guest path, for folders the guest should see
     * @param workDir guest path the command starts in
     */
    suspend fun run(
        command: String,
        timeoutMs: Long = 180_000,
        binds: List<Pair<String, String>> = emptyList(),
        workDir: String = "/root",
    ): LinuxSystem.Shell
}
