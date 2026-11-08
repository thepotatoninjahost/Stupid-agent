package host.vorv.kernel

/**
 * Night session 2026-11-08 — the amnesia fix, extracted as a pure object so
 * it is testable without Android (the VM itself needs Context/Room).
 *
 * Every send() used to hand kernel.run ONLY the new message (the kernel
 * builds a fresh [system, user(prompt)] per task), so the model never saw a
 * single prior turn. Consequences, from the 20:32 screen recording:
 *   - "try again" (after a 5-minute timeout) → "I don't have context for
 *     what you'd like me to try again" — a perfectly honest answer to the
 *     only message it was ever given;
 *   - "did you see the crash report?" → answered about "our current
 *     conversation" it could not even see.
 *
 * Constraints: KernelApi.run's signature is FROZEN (SessionGrants — a
 * signature change would crash every previously-approved patch), so history
 * rides INSIDE the prompt string. Budgets are honest per engine: the local
 * engine's nCtx is 4096 with 2048 output reserved — a fat history would
 * blow the input budget, so local gets a small window and heavy per-message
 * truncation; cloud gets a wider one. Long-term memory stays in
 * memory_recall — this is short-term continuity only.
 */
object ConversationPrompt {

    /** One prior turn, role "user" | "assistant". */
    data class Turn(val role: String, val content: String)

    /**
     * Build the task prompt: recent turns (oldest → newest) + the new
     * message. Returns [newMessage] unchanged when there is no history.
     * Per-turn truncation and the total budget keep the prompt inside the
     * engine's honest input window (see class doc).
     *
     * Budget walk is NEWEST → OLDEST: the newest turns carry the immediate
     * context, so when the budget runs out it is the OLDEST turns that are
     * dropped (with an explicit omission note). Walking oldest→newest — the
     * obvious loop — would silently discard the newest turns and re-create
     * the very amnesia this class exists to fix (caught by the budget test
     * on CI, run 42469102622).
     */
    fun build(
        newMessage: String,
        history: List<Turn>,
        localEngine: Boolean,
    ): String {
        val maxTurns = if (localEngine) 4 else 10
        val perMsgChars = if (localEngine) 200 else 400
        val totalBudget = if (localEngine) 900 else 3000

        val window = history
            .takeLast(maxTurns)
            .filter { it.content.isNotBlank() }

        if (window.isEmpty()) return newMessage

        // Newest first while the budget allows; at least the newest turn is
        // always kept (truncated to its per-message cap), even if oversized.
        val keptNewestFirst = mutableListOf<Pair<String, String>>() // role -> text
        var used = 0
        for (turn in window.asReversed()) {
            val text = turn.content.replace("\n", " ").take(perMsgChars)
            if (keptNewestFirst.isNotEmpty() && used + text.length > totalBudget) break
            used += text.length
            keptNewestFirst.add(turn.role to text)
        }
        val omittedOldest = window.size - keptNewestFirst.size

        val sb = StringBuilder()
        sb.appendLine("[Recent conversation, for continuity — older turns omitted; long-term facts live in memory_recall]")
        if (omittedOldest > 0) {
            sb.appendLine("…($omittedOldest older turn(s) omitted)")
        }
        for ((role, text) in keptNewestFirst.asReversed()) {
            sb.appendLine("${if (role == "user") "User" else "Vorv"}: $text")
        }
        sb.appendLine()
        sb.appendLine("[New message]")
        sb.append(newMessage)
        return sb.toString()
    }
}
