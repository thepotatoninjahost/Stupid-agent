package host.vorv.tools

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import host.vorv.VorvApplication
import host.vorv.data.VorvPrefs
import host.vorv.healer.FixPlan
import host.vorv.healer.FixStep
import host.vorv.healer.HealRequest
import host.vorv.healer.HealerApi
import host.vorv.healer.HealingAgent
import host.vorv.healer.PlanStore
import host.vorv.healer.consentText
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
 *     → compiled by Vorv's own compiler inside the app (H-4, replaced)
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
 * MODEL: patch generation uses the on-device coder model (OmniCoder-9B)
 * when it is downloaded. There is no smaller fallback — a missing model is
 * reported honestly and nothing is generated.
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
            "loses entries'). Name the steps: which classes change and what changes in " +
            "each. The user approves the PLAN once (one dialog listing every step), then " +
            "each step is written by the on-device coder model, compiled on device, " +
            "verified to keep the complete public API of the class, and signed. Nothing " +
            "outside the plan is touched — a repair for another class is refused, not " +
            "added. Approved patches become active at the next app start and are rolled " +
            "back automatically if the app crashes in the patched class. Requires: a coder " +
            "model downloaded (OmniCoder-9B writes the repairs) and the app's own " +
            "compiler (tools/forge/fetch-runtime.sh)."

    /** Model load + 3 attempts (LLM + on-device compile) + verification — 15 min,
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
            add("steps", JsonObject().apply {
                addProperty("type", "array")
                addProperty("description", "The plan, one entry per class that will change " +
                    "(at most 3). Each step runs exactly as written; the user approves this " +
                    "list once and nothing outside it is touched.")
                add("items", JsonObject().apply {
                    addProperty("type", "object")
                    add("properties", JsonObject().apply {
                        add("target_class", JsonObject().apply {
                            addProperty("type", "string")
                            addProperty("description", "Fully-qualified class, e.g. " +
                                "host.vorv.tools.NotifyTool. Only the dispatchable classes can " +
                                "be patched (the agent tools, host.vorv.kernel.CognitiveKernel, " +
                                "host.vorv.healer.HealingAgent).")
                        })
                        add("change", JsonObject().apply {
                            addProperty("type", "string")
                            addProperty("description", "What changes in that class, in one " +
                                "precise sentence. This is what the user approves.")
                        })
                    })
                    add("required", JsonArray().apply { add("target_class"); add("change") })
                })
            })
            add("target_class", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Shortcut for a one-step plan: the single class to " +
                    "patch. Ignored when 'steps' is given.")
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
        // The plan: the classes that will change and what changes in each.
        // The owner approves this list ONCE; the run below executes it and
        // refuses any repair outside it.
        val parsed = parseSteps(input, problem)
        val planError = parsed.second
        if (planError != null) {
            return ToolResult(ok = false, output = "", durationMs = elapsed(t0), error = planError)
        }
        val steps = parsed.first.orEmpty()
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
        // Patches are written by the on-device model (OmniCoder-9B).
        val repairModel = ModelManager.bestRepairModel()
        if (repairModel == null) {
            return ToolResult(
                ok = false, output = "", durationMs = elapsed(t0),
                error = "No coder model is downloaded — self-fix generates patches with " +
                    "the on-device coder model (OmniCoder-9B; Setup tab). " +
                    "Nothing was changed.",
            )
        }

        // Compiler readiness BEFORE the expensive pipeline: discovering a
        // missing toolchain ten minutes into a fifteen-minute heal (after the
        // model already wrote the patch) wasted the whole budget. The check is
        // file-only — the compiler travels inside the APK.
        val readiness = host.vorv.forge.ForgeProbe.readinessError(appContext)
        if (readiness != null) {
            return ToolResult(
                ok = false, output = "", durationMs = elapsed(t0),
                error = "$readiness\n\nPackage the toolchain with " +
                    "tools/forge/fetch-runtime.sh, then rebuild Vorv. Nothing was changed.",
            )
        }

        // M-3 (code review, 2026-11): the repair engine now comes from
        // ModelRepository (single owner of the native engine lifecycle,
        // audit H-4) — acquired under its mutex, REUSED when the resident
        // engine already IS the strongest repair model (no second native
        // instance on the NPU), and time-bounded-owned otherwise. The old
        // direct `LocalLlm(...)` construction was a hole in the H-4 ledger
        // and doubled NPU memory when the model was already resident.
        val repository = (appContext as? VorvApplication)?.modelRepository
        val handle = repository?.acquireRepairEngine()
            ?: run {
                // Fallback for contexts without a VorvApplication (tests):
                // the old direct construction, caller-owned. repairModel is
                // non-null — checked before the toolchain gate above.
                host.vorv.llm.ModelRepository.RepairEngineHandle(
                    LocalLlm(
                        appContext,
                        repairModel,
                        ModelManager.repairLabel(repairModel, "self-fix"),
                    ),
                    owned = true,
                )
            }
        val llm = handle.llm
            ?: return ToolResult(
                ok = false, output = "", durationMs = elapsed(t0),
                error = "No coder model is downloaded — self-fix generates patches with " +
                    "the on-device coder model (OmniCoder-9B; Setup tab). " +
                    "Nothing was changed.",
            )
        return try {
            // Patch-aware construction: a verified, approved patch may
            // override the healer itself (the heal-the-healer case).
            val healer: HealerApi = PatchRegistry.create(
                HealerApi::class.java,
                HealingAgent::class.java.name,
                appContext, llm,
            ) ?: HealingAgent(appContext, llm)

            val plan = FixPlan(
                id = PlanStore.newId(),
                title = input.get("title")?.takeIf { !it.isJsonNull }?.asString?.trim()
                    ?.takeIf { it.isNotBlank() } ?: problem.lineSequence().first().take(90),
                problem = problem,
                steps = steps,
            )
            // Fail closed: a plan that cannot be written down cannot be
            // approved or audited, so nothing runs.
            if (PlanStore.save(appContext, plan) == null) {
                return ToolResult(
                    ok = false, output = "", durationMs = elapsed(t0),
                    error = "could not write the plan to storage — nothing was run " +
                        "(no approval was recorded, so no patch may exist).",
                )
            }

            // ONE question, about the whole plan.
            if (!confirm(planMessage(plan))) {
                PlanStore.decide(appContext, plan.id, approved = false, note = "owner denied the plan")
                return ToolResult(
                    ok = false, output = "", durationMs = elapsed(t0),
                    error = "The user DENIED the plan ${plan.id} — nothing was generated, " +
                        "nothing was changed. Do not retry these same steps: either drop the " +
                        "idea or propose a different plan.",
                )
            }
            PlanStore.decide(appContext, plan.id, approved = true)

            val lines = StringBuilder()
            lines.appendLine("Plan ${plan.id} APPROVED by the user — executing its ${plan.steps.size} step(s).")
            var ran = 0
            var stopped = false
            for (step in plan.steps) {
                val result = healer.modify(
                    HealRequest(
                        problem = problem + "\n\nApproved plan step ${step.index} of " +
                            "${plan.steps.size}: ${step.change}",
                        diagnostics = diagnostics,
                        targetClass = step.targetClass,
                        patchIdPrefix = "plan",
                        allowedTargets = plan.targetClasses,
                    ),
                    workspaceUri,
                ) { true }
                ran++
                if (result.success) {
                    PlanStore.recordStep(appContext, plan.id, step.index, "patched", result.patchId, null)
                    val how = if (result.surfaceVerified) {
                        "public API kept, signed"
                    } else {
                        "signed; the public-API check did not run"
                    }
                    lines.appendLine("• step ${step.index} ${step.targetClass} — patched as ${result.patchId} ($how)")
                } else {
                    PlanStore.recordStep(appContext, plan.id, step.index, "failed", null, result.error)
                    lines.appendLine(
                        "• step ${step.index} ${step.targetClass} — FAILED: ${result.error ?: "unknown"}",
                    )
                    stopped = true
                    break
                }
            }
            val notRun = plan.steps.drop(ran)
            if (notRun.isNotEmpty()) {
                lines.appendLine("Not run: " + notRun.joinToString { "step ${it.index} ${it.targetClass}" } +
                    " — the plan stops at its first failure (nothing is improvised).")
            }
            val ok = !stopped
            ToolResult(
                ok = ok, output = lines.toString().trimEnd(), durationMs = elapsed(t0),
                error = if (stopped) {
                    "step ${plan.steps[ran - 1].index} of plan ${plan.id} failed — later steps did " +
                        "not run. Report exactly what the plan lines say; do not claim the rest."
                } else null,
            )
        } finally {
            // Owned engines are released; a REUSED resident engine belongs
            // to ModelRepository and must survive this call.
            if (handle.owned) llm.release()
        }
    }

    // ---------------- internals ----------------

    private fun elapsed(t0: Long): Long = System.currentTimeMillis() - t0

    /** The plan, as the owner sees it in the ONE consent dialog. */
    internal fun planMessage(plan: FixPlan): String = plan.consentText()

    /**
     * Turns the tool input into the plan's steps, or into the sentence that
     * says why it is not a usable plan. A plan with no class is refused —
     * "fix it" is not a plan the owner can approve.
     */
    internal fun parseSteps(input: JsonObject, problem: String): Pair<List<FixStep>?, String?> {
        val classes = mutableListOf<FixStep>()
        val steps = input.get("steps")?.takeIf { it.isJsonArray }?.asJsonArray
        if (steps != null && steps.size() > 0) {
            for ((i, el) in steps.withIndex()) {
                if (!el.isJsonObject) {
                    return null to "step ${i + 1} is not an object with target_class and change"
                }
                val o = el.asJsonObject
                val cls = o.get("target_class")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                val change = o.get("change")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                if (cls.isBlank()) return null to "step ${i + 1} names no target_class"
                if (change.length < 8) {
                    return null to "step ${i + 1} does not say what changes — the owner " +
                        "approves the change, so it has to be written down"
                }
                classes += FixStep(index = classes.size + 1, targetClass = cls, change = change)
            }
        } else {
            val single = input.get("target_class")?.takeIf { !it.isJsonNull }?.asString?.trim()
                .orEmpty()
            if (single.isBlank()) {
                return null to "No plan: name the class(es) that change in 'steps' " +
                    "(target_class + change per step). A fix with no named class cannot be " +
                    "approved, and the healer is not allowed to choose one for you."
            }
            classes += FixStep(index = 1, targetClass = single, change = problem.take(400))
        }
        if (classes.size > MAX_PLAN_STEPS) {
            return null to "A plan may have at most $MAX_PLAN_STEPS steps (each one can take " +
                "minutes on the phone). Split the work into separate plans."
        }
        val unknown = classes.map { it.targetClass }.filterNot { PatchRegistry.isPatchableClass(it) }
        if (unknown.isNotEmpty()) {
            return null to "${unknown.joinToString()} cannot be patched: only the " +
                "${PatchRegistry.DISPATCH_BASES.size} dispatchable classes can (the agent " +
                "tools, host.vorv.kernel.CognitiveKernel, host.vorv.healer.HealingAgent)."
        }
        val duplicated = classes.groupBy { it.targetClass }.filterValues { it.size > 1 }.keys
        if (duplicated.isNotEmpty()) {
            return null to "${duplicated.joinToString()} appears in more than one step — " +
                "one step per class, with all the changes for that class in it."
        }
        return classes to null
    }

    private companion object {
        /** Each step is a full model+compile+verify cycle; the phone is the cost. */
        const val MAX_PLAN_STEPS = 3
    }
}
