package host.vorv.healer

import android.content.Context
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A fix plan: what Vorv intends to change, written down before any code is
 * generated, so the owner approves the PLAN once instead of approving each
 * step (owner's rule, 2026-11: "I sign off on the plan, and then it does what
 * the plan says"). Execution is confined to [steps] — a repair for a class the
 * plan does not name is refused, never silently widened.
 */
data class FixStep(
    val index: Int,
    val targetClass: String,
    /** What changes in that class, in the owner's terms. */
    val change: String,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("index", index)
        addProperty("target_class", targetClass)
        addProperty("change", change)
        addProperty("status", "pending")
    }
}

data class FixPlan(
    val id: String,
    val title: String,
    val problem: String,
    val steps: List<FixStep>,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val targetClasses: Set<String> get() = steps.map { it.targetClass }.toSet()
}

/**
 * The plan, as the owner sees it in the ONE consent dialog.
 *
 * The owner's rule (2026-11): approve the plan once, then it runs — so this
 * text has to carry everything a person needs to say yes or no: every class
 * that will change, what changes in it, and what happens afterwards.
 */
fun FixPlan.consentText(): String = buildString {
    appendLine("Vorv's plan: $title")
    appendLine("Plan id: $id")
    appendLine()
    appendLine("Why: ${problem.take(600)}")
    appendLine()
    appendLine("What will change (${steps.size} step(s)):")
    steps.forEach { step ->
        appendLine("  ${step.index}. ${step.targetClass}")
        appendLine("     ${step.change}")
    }
    appendLine()
    appendLine("If you APPROVE, Vorv executes exactly these steps and nothing else:")
    appendLine("• the on-device coder model writes each class in full,")
    appendLine("• Vorv's own compiler builds it on this phone,")
    appendLine("• the result is checked to keep the complete public API and signed,")
    appendLine("• the patches load at the next app start, and a crash in a patched class")
    appendLine("  is rolled back automatically.")
    appendLine("A repair for any class NOT listed above is refused. A step that fails")
    appendLine("stops the plan; later steps do not run and nothing is improvised.")
    appendLine("Each step can take several minutes.")
    appendLine()
    appendLine("If you DENY, nothing is generated and the plan is kept in the bin.")
}

/**
 * Where plans live and how their fate is recorded.
 *
 * `files/healer/plans/<planId>.json` — one file per plan:
 * `id`, `title`, `problem`, `createdAt`, `status` (`proposed` / `approved` /
 * `denied`), `steps[]` (each with `status` and the `patch_id` it produced),
 * and `note`. A denied plan is KEPT, not deleted: the owner's rule is that a
 * rejected plan goes in the bin or is rewritten, and the agent must be able to
 * see which of the two happened instead of re-proposing the same steps.
 */
object PlanStore {

    private const val TAG = "PlanStore"
    private const val DIR = "healer/plans"
    private const val MAX_PLANS = 40

    fun dir(context: Context): File = File(context.filesDir, DIR)

    fun newId(now: Long = System.currentTimeMillis()): String = "plan-$now"

    /** Writes (or overwrites) the plan as `proposed`, with every step pending. */
    fun save(context: Context, plan: FixPlan): File? {
        val root = dir(context)
        return try {
            if (!root.isDirectory && !root.mkdirs()) {
                Log.e(TAG, "could not create ${root.absolutePath}")
                return null
            }
            val steps = JsonArray()
            plan.steps.forEach { steps.add(it.toJson()) }
            val doc = JsonObject().apply {
                addProperty("id", plan.id)
                addProperty("title", plan.title)
                addProperty("problem", plan.problem)
                addProperty("created_at", plan.createdAt)
                addProperty("status", "proposed")
                add("steps", steps)
            }
            val file = File(root, "${plan.id}.json")
            file.writeText(doc.toString())
            prune(root)
            file
        } catch (e: Exception) {
            Log.e(TAG, "could not save plan ${plan.id}: ${e.message}", e)
            null
        }
    }

    /** Records the owner's answer. Denied plans stay on disk, with the reason. */
    fun decide(context: Context, planId: String, approved: Boolean, note: String = ""): Boolean {
        val doc = read(context, planId)
        if (doc == null) {
            Log.e(TAG, "cannot record a decision for unknown plan $planId")
            return false
        }
        return try {
            doc.addProperty("status", if (approved) "approved" else "denied")
            doc.addProperty("decided_at", System.currentTimeMillis())
            doc.addProperty("note", note)
            File(dir(context), "$planId.json").writeText(doc.toString())
            true
        } catch (e: Exception) {
            Log.e(TAG, "could not record the decision for $planId: ${e.message}", e)
            false
        }
    }

    /** Per-step outcome, so the plan file says what actually ran. */
    fun recordStep(
        context: Context,
        planId: String,
        stepIndex: Int,
        status: String,
        patchId: String? = null,
        detail: String? = null,
    ): Boolean {
        val doc = read(context, planId) ?: return false
        return try {
            val steps = doc.getAsJsonArray("steps") ?: return false
            for (i in 0 until steps.size()) {
                val step = steps.get(i).asJsonObject
                if (step.get("index")?.asInt != stepIndex) continue
                step.addProperty("status", status)
                if (patchId != null) step.addProperty("patch_id", patchId)
                if (detail != null) step.addProperty("detail", detail)
            }
            File(dir(context), "$planId.json").writeText(doc.toString())
            true
        } catch (e: Exception) {
            Log.e(TAG, "could not record step $stepIndex of $planId: ${e.message}", e)
            false
        }
    }

    fun read(context: Context, planId: String): JsonObject? = try {
        val f = File(dir(context), "$planId.json")
        if (!f.isFile) null else JsonParser.parseString(f.readText()).asJsonObject
    } catch (e: Exception) {
        Log.e(TAG, "could not read plan $planId: ${e.message}", e)
        null
    }

    fun status(context: Context, planId: String): String? =
        read(context, planId)?.get("status")?.takeIf { !it.isJsonNull }?.asString

    /** Newest first, one line each — what the owner and the agent can read back. */
    fun list(context: Context): List<String> {
        val root = dir(context)
        val files = try {
            root.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedByDescending { it.lastModified() }
        } catch (e: Exception) {
            Log.e(TAG, "could not list plans: ${e.message}", e)
            null
        } ?: return emptyList()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return files.mapNotNull { f ->
            val doc = try { JsonParser.parseString(f.readText()).asJsonObject } catch (e: Exception) {
                Log.e(TAG, "unreadable plan ${f.name}: ${e.message}", e)
                null
            } ?: return@mapNotNull null
            val steps = doc.getAsJsonArray("steps")
            val done = steps?.count { it.asJsonObject.get("status")?.asString == "applied" } ?: 0
            val total = steps?.size() ?: 0
            val at = doc.get("created_at")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            "${fmt.format(Date(at))}  ${doc.get("id")?.asString}  ${doc.get("status")?.asString}  " +
                "($done/$total step(s) applied)  ${doc.get("title")?.asString.orEmpty()}"
        }
    }

    /** Keeps the folder bounded; the oldest plans go first. */
    private fun prune(root: File) {
        val files = root.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        if (files.size <= MAX_PLANS) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_PLANS)
            .forEach { f -> if (!f.delete()) Log.e(TAG, "could not prune ${f.name}") }
    }
}
