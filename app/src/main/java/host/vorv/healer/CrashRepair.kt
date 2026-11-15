package host.vorv.healer

import android.content.Context
import java.io.File

/**
 * What Vorv does about a crash it can prove — without waiting for a model.
 *
 * Reading a report is not a repair. When [DeathEvidence] confirms a real
 * crash, this pass takes the steps that do not need judgement: quarantine the
 * hot-patch that was followed by the crash (the probation gate), and run the
 * next boot without the subsystem that was starting when the process died —
 * so a native crash on launch stops repeating instead of looping.
 *
 * Anything that needs a code change is left to the agent: this writes one
 * record per crash (`crash_repairs/repair_<ts>.txt`) saying what the evidence
 * was, what was already done, and what is still unfixed. The dialog and the
 * boot diagnosis both read it, so nothing has to be re-derived.
 */
object CrashRepair {

    data class Plan(
        /** The last breadcrumb of the dying session — where it stopped. */
        val lastStep: String?,
        /** Run this boot without GenieX/native init (it was mid-load when death came). */
        val suppressNativeInit: Boolean,
        /** Sentences describing what was done, in the owner's language. */
        val actions: List<String>,
    )

    /**
     * Pure: what should be done about a proven crash, given the trail it left.
     * [prevTrace] is the dying boot's breadcrumbs; [rolledBackPatches] are the
     * patches the probation gate already quarantined this boot.
     */
    fun plan(
        prevTrace: String?,
        rolledBackPatches: List<String>,
    ): Plan {
        val lines = prevTrace.orEmpty().lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("=") }
        val last = lines.lastOrNull()
        val steps = lines.map { it.substringAfter(']').trim().substringBefore(" — ").trim() }

        val reach = { name: String -> steps.any { it.startsWith(name) } }

        val actions = mutableListOf<String>()

        if (rolledBackPatches.isNotEmpty()) {
            actions += "Quarantined ${rolledBackPatches.joinToString(", ")}: a patch was applied " +
                "and the crash came from the class it overrides. It is kept on disk, just not loaded."
        }

        // Death inside native init: it never reached its success line.
        val suppressNative = reach("geniex_init") && !reach("geniex_init_success")
        if (suppressNative) {
            actions += "GenieX native init was mid-load when the process died, so this boot " +
                "runs without it — one boot, then it is tried again."
        }

        // Death while applying a hot-patch.
        if (reach("hotpatch_load") && !reach("hotpatch_load_end")) {
            actions += "The process died while hot-patches were being applied, so the " +
                "pending patch load is suspect and is reported to the fixer."
        }

        if (actions.isEmpty()) {
            actions += if (last == null) {
                "Nothing was in progress when the last breadcrumb was written; nothing was disabled."
            } else {
                "The last thing recorded was \"$last\". Nothing needed disabling for this boot."
            }
        }

        return Plan(lastStep = last, suppressNativeInit = suppressNative, actions = actions)
    }

    /** Write the repair record and return the one-line summary for the UI. */
    fun record(ctx: Context, verdict: DeathEvidence.Verdict, plan: Plan): String {
        val sentence = plan.actions.joinToString(" ")
        runCatching {
            val dir = File(ctx.getExternalFilesDir(null) ?: return sentence, "crash_repairs")
            dir.mkdirs()
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", java.util.Locale.US)
                .format(java.util.Date())
            val f = File(dir, "repair_$stamp.txt")
            f.writeText(
                buildString {
                    appendLine("Vorv crash response — ${java.util.Date()}")
                    appendLine("Evidence: ${verdict.detail}")
                    appendLine("Where the dying session stopped: ${plan.lastStep ?: "(no breadcrumb)"}")
                    appendLine("Done automatically:")
                    plan.actions.forEach { appendLine("• $it") }
                    appendLine("Still open: a code fix, if the evidence supports one. Ask Vorv")
                    appendLine("to fix it — it reads this file and the crash reports itself.")
                },
            )
        }
        return sentence
    }
}
