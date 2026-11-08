package host.vorv.tools

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import host.vorv.VorvApplication
import host.vorv.data.VorvPrefs
import host.vorv.healer.HealRequest
import host.vorv.healer.HealerApi
import host.vorv.healer.HealResult
import host.vorv.healer.HealingAgent
import host.vorv.healer.PatchProposal
import host.vorv.llm.LocalLlm
import host.vorv.llm.ModelManager
import host.vorv.watchdog.PatchRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first

/**
 * SelfFixTool — the agent-facing surface of the general-purpose self-healing
 * system (user requirement: "self-healing is for every problem, every issue,
 * every error and everything I feel like having changed").
 *
 * WHAT IT DOES: the model (or the user, through the chat) describes ANY
 * problem — a crash, an error, a misbehavior, or a desired modification of
 * Vorv's own behavior — and this tool runs the full hardened pipeline
 * against the class that must change:
 *
 *   problem (+ optional target class & diagnostics)
 *     → on-device coder model (STRONGEST downloaded — v0.6.9) generates the
 *       COMPLETE fixed class
 *     → compiled in Termux through the workspace round-trip (H-4)
 *     → PatchVerifier: every public constructor/method kept, dispatch
 *       interface intact — partial fixes are REJECTED and regenerated
 *     → signed with the device patch key (C-2)
 *     → USER APPROVES in the consent dialog (C-2 — same bridge as shell
 *       and app_intent; deny rolls everything back)
 *     → applies at next boot; a crash in the patched class during
 *       probation rolls the patch back automatically (HotPatcher).
 *
 * NOTHING IS DISABLED: the tool is the same pipeline the crash dialog's
 * "Attempt self-repair" uses, opened up to every problem instead of only
 * crashes. The consent gate stays: a patch changes what the app IS, and
 * the user said "any modification I deem fit" — the dialog is where they
 * deem it fit.
 *
 * Runs in the MAIN process (audit M-8), on its own timeout budget (the
 * kernel-enforced budget is 15 minutes: model load + up to 3 compile
 * attempts + verification — aligned with the healer's own 15-minute total
 * budget and the 15-minute local-mode task timeout, audit N-L7,
 * VRA-2026-1103: the previous 21-minute figure was unreachable).
 *
 * MODEL (v0.6.9): patch generation uses the STRONGEST coder model on the
 * device — the 7B whenever it is downloaded. The 3B is only a fallback
 * for devices that never pulled the 7B, and the tool says so honestly.
 * A weak model never writes repairs while a stronger one sits on disk.
 */
class SelfFixTool(
    private val appContext: Context,
    private val prefs: VorvPrefs? = null,
) : Tool {

    override val name = "self_fix"
    override val description =
        "Fix or modify Vorv's OWN code. Use it for ANY problem with Vorv itself: a crash, " +
            "an error, a tool misbehaving, or any change the user asks for (" +
            "e.g. 'make notify use a different sound', 'fix the bug where schedule " +
            "loses entries'). The fix is generated with the on-device coder model, " +
            "compiled on device, verified to keep the complete public API of the class, " +
            "cryptographically signed, and shown to the user for approval BEFORE it is " +
            "applied; it becomes active at the next app start, and is rolled back " +
            "automatically if the app crashes in the patched class. Requires: a coder " +
            "model downloaded (the 7B is used when present — strongest model writes " +
            "the repairs; the 3B only if the 7B was never pulled), a granted workspace " +
            "folder, and Termux with kotlinc+d8 for the on-device compile."

    /** Model load + 3 attempts (LLM + Termux compile) + verification — 15 min,
     * matching the healer's TOTAL_HEAL_BUDGET_MS and the local task timeout. */
    override val timeoutMs: Long get() = 15 * 60_000L

    /** A confused model must not spam heal attempts. */
    override val maxCallsPerTask: Int get() = 2

    override fun schema(): JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("problem", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "What is wrong or what to change — any bug, " +
                    "error, crash, misbehavior, or desired modification, described " +
                    "precisely (what happens, what should happen instead).")
            })
            add("target_class", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Optional fully-qualified class to patch, e.g. " +
                    "host.vorv.tools.ShellTool. Only the 19 dispatchable classes can be " +
                    "patched (the agent tools, host.vorv.kernel.CognitiveKernel, " +
                    "host.vorv.healer.HealingAgent). If omitted, the healer infers the " +
                    "target from the problem.")
            })
            add("diagnostics", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Optional supporting evidence: exact error " +
                    "text, stack trace, tool output, or the behavior observed.")
            })
        })
        add("required", JsonArray().apply { add("problem") })
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult {
        val t0 = System.currentTimeMillis()
        val confirm = ctx.confirm
            ?: return ToolResult(
                ok = false, output = "", durationMs = elapsed(t0),
                error = "user-consent bridge unavailable — self_fix refuses to run " +
                    "without it (fail closed)",
            )

        val problem = input.get("problem")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        if (problem.length < 10) {
            return ToolResult(
                ok = false, output = "", durationMs = elapsed(t0),
                error = "'problem' is too vague — describe what is wrong or what to " +
                    "change (at least a full sentence).",
            )
        }
        val targetClass = input.get("target_class")?.takeIf { !it.isJsonNull }?.asString?.trim()
            ?.takeIf { it.isNotBlank() }
        val diagnostics = input.get("diagnostics")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            .take(4000)

        // Workspace first (cheapest check): the compile round-trips through it.
        val workspaceUri = ctx.workspaceUri?.takeIf { it.isNotBlank() }
            ?: prefs?.workspaceUri?.first().orEmpty().takeIf { it.isNotBlank() }
            ?: return ToolResult(
                ok = false, output = "", durationMs = elapsed(t0),
                error = "No workspace granted. The on-device compile round-trips through " +
                    "the workspace folder — open the Files tab and grant one on primary " +
                    "local storage, then retry.",
            )

        // Same honest prerequisites as the crash-dialog flow.
        val app = appContext as? VorvApplication
        if (app?.safeMode == true) {
            return ToolResult(
                ok = false, output = "", durationMs = elapsed(t0),
                error = "Safe mode is active — the AI engine is not initialized. " +
                    "Use 'Retry normal boot' from the safe-mode screen first.",
            )
        }
        // Strongest-first repair model (v0.6.9): the 7B writes the patches
        // whenever it is on the device; the 3B is a last-resort fallback,
        // never the first choice for code repair.
        val repairModel = ModelManager.bestRepairModel()
        if (repairModel == null) {
            return ToolResult(
                ok = false, output = "", durationMs = elapsed(t0),
                error = "No coder model is downloaded — self-fix generates patches with " +
                    "the strongest on-device coder model (the 7B; Setup tab). " +
                    "Nothing was changed.",
            )
        }

        // v0.6.14: Termux readiness BEFORE the expensive pipeline. The
        // compile round-trips through Termux; discovering a missing
        // permission 10 minutes into a 15-minute heal (after the model
        // already wrote the patch) wasted the whole budget. Check now,
        // offer the one-tap grant (Termux declares RUN_COMMAND
        // protectionLevel="dangerous" — runtime-requestable), and fail
        // fast and honestly when it still isn't there.
        val readiness = TermuxProbe.readinessError(
            termuxInstalled = TermuxProbe.termuxInstalled(appContext),
            permissionGranted = TermuxProbe.runCommandGranted(appContext),
        )
        if (readiness != null) {
            // The permission is the only wall with a one-tap door — try it.
            if (!TermuxProbe.runCommandGranted(appContext) &&
                TermuxProbe.termuxInstalled(appContext)
            ) {
                PermissionBridge.request(TermuxProbe.RUN_COMMAND_PERMISSION)
                val retry = TermuxProbe.readinessError(
                    termuxInstalled = true,
                    permissionGranted = TermuxProbe.runCommandGranted(appContext),
                )
                if (retry == null) {
                    // Granted — fall through to the pipeline.
                } else {
                    return ToolResult(ok = false, output = "", durationMs = elapsed(t0), error = retry)
                }
            } else {
                return ToolResult(ok = false, output = "", durationMs = elapsed(t0), error = readiness)
            }
        }

        val llm = LocalLlm(
            appContext,
            repairModel,
            ModelManager.repairLabel(repairModel, "self-fix"),
        )
        return try {
            // Patch-aware construction: a verified, approved patch may
            // override the healer itself (the heal-the-healer case).
            val healer: HealerApi = PatchRegistry.create(
                HealerApi::class.java,
                HealingAgent::class.java.name,
                appContext, llm,
            ) ?: HealingAgent(appContext, llm)

            val result = healer.modify(
                HealRequest(
                    problem = problem,
                    diagnostics = diagnostics,
                    targetClass = targetClass,
                    patchIdPrefix = "fix",
                ),
                workspaceUri,
            ) { proposal -> confirm(approvalMessage(proposal)) }

            formatResult(result, t0, llm.label)
        } finally {
            llm.release()
        }
    }

    // ---------------- internals ----------------

    private fun elapsed(t0: Long): Long = System.currentTimeMillis() - t0

    /** The consent dialog text for a patch proposal (audit C-2; N-I4: the
     *  full 64-char SHA-256 is shown, never a truncated prefix). */
    internal fun approvalMessage(p: PatchProposal): String = buildString {
        appendLine("Vorv wants to apply a SELF-FIX patch:")
        appendLine("• Class: ${p.targetClass}")
        appendLine("• Patch: ${p.patchId}")
        appendLine("• SHA-256: ${p.sha256}")
        appendLine("• Verified: ${if (p.surfaceVerified) "full public API kept" else "NOT verified"}")
        appendLine("• Attempts: ${p.attempts}")
        appendLine()
        appendLine(p.description)
        appendLine()
        appendLine("APPROVE = the patched class replaces the original at the next app " +
            "start. DENY = everything is rolled back now. If the app crashes in this " +
            "class after the patch, it is rolled back automatically.")
    }

    private fun formatResult(
        result: HealResult,
        t0: Long,
        modelLabel: String,
    ): ToolResult {
        return if (result.success) {
            ToolResult(
                ok = true,
                output = "Self-fix SUCCEEDED. Patch ${result.patchId} for " +
                    "${result.targetClass} was generated with $modelLabel, " +
                    "compiled on device, VERIFIED (complete public API kept), signed, " +
                    "and APPROVED by the user. It becomes active at the NEXT APP " +
                    "START — tell the user to restart Vorv to apply it.",
                durationMs = elapsed(t0),
            )
        } else {
            val denied = result.error == "user denied the patch"
            ToolResult(
                ok = false, output = "", durationMs = elapsed(t0),
                error = if (denied) {
                    "The user DENIED the patch — it was rolled back completely. " +
                        "Nothing was changed. Do not claim the fix was applied."
                } else {
                    "Self-fix did not produce an applicable patch " +
                        "(attempts: ${result.attempts}). ${result.error ?: ""} " +
                        "Report this honestly to the user."
                },
            )
        }
    }
}
