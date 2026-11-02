package host.vorv.healer

/**
 * HealerApi — the SHARED DISPATCH INTERFACE for the self-healing agent
 * (v0.6.8 patch-dispatch fix).
 *
 * WHY AN INTERFACE: a patch class is loaded by a DIFFERENT classloader than
 * the original. Two classes with the same fully-qualified name under
 * different loaders are DIFFERENT runtime identities, so
 * `original.isAssignableFrom(patched)` is always false — the audited-era
 * code "loaded" patches that could never actually be used. Dispatching
 * through an interface that BOTH the original and the patch class implement
 * (and that lives in the parent classloader both can see) is the mechanism
 * that makes a patched class genuinely run at its construction sites.
 *
 * Construction sites must create the healer through
 * [host.vorv.watchdog.PatchRegistry.create] with this interface.
 */
interface HealerApi {

    /**
     * Crash-shaped entry point (back-compat wrapper over [modify]):
     * the crash dialog's "Attempt self-repair" lands here.
     */
    suspend fun heal(
        crash: CrashInfo,
        workspaceUri: String,
        onConsent: suspend (PatchProposal) -> Boolean,
    ): HealResult

    /**
     * General-scope entry point (v0.6.8): ANY problem, issue, error or
     * requested modification, described in free text. Same hardened
     * pipeline as [heal].
     */
    suspend fun modify(
        request: HealRequest,
        workspaceUri: String,
        onConsent: suspend (PatchProposal) -> Boolean,
    ): HealResult

    /**
     * Write a diagnostic bundle (crash log + boot trace + on-device LLM
     * analysis marked UNVERIFIED) for the user. Never compiles or applies
     * anything.
     */
    suspend fun produceDiagnosticBundle(
        stackTrace: String,
        bootTrace: String,
    ): DiagnosticBundle
}

/** A problem statement for the general-scope healer. */
data class HealRequest(
    val problem: String,
    val diagnostics: String = "",
    val targetClass: String? = null,
    val patchIdPrefix: String = "heal",
)

/** Proposal shown to the user for APPROVE/DENY before anything applies. */
data class PatchProposal(
    val patchId: String,
    val targetClass: String,
    val description: String,
    val sha256: String,
    val signature: String,
    val surfaceVerified: Boolean,
    val attempts: Int,
)

data class HealResult(
    val success: Boolean,
    val patchId: String? = null,
    val targetClass: String? = null,
    val error: String? = null,
    val attempts: Int = 0,
    val userApproved: Boolean = true,
)

data class DiagnosticBundle(
    val bundleDir: java.io.File,
    val analysisPath: java.io.File?,
)

/** Crash-shaped input kept as a plain data class both eras understand. */
data class CrashInfo(
    val stackTrace: String,
    val bootTrace: String,
    val crashedClass: String? = null,
)
