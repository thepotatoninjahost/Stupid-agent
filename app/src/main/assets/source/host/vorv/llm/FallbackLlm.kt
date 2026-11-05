package host.vorv.llm

/**
 * v0.7.0 — the hybrid engine wrapper: cloud first, offline brain as backup.
 *
 * Why this exists: before v0.7.0 the mode switch was a manual, global choice
 * and a cloud task that lost signal mid-run simply died. FallbackLlm makes
 * the resilience per-step: every kernel iteration goes to the cloud engine
 * first; if the cloud call fails with anything OTHER than an authorization
 * problem (bad key, revoked key — those must surface loudly in Setup, not be
 * papered over by a silent engine switch), the SAME step is retried once on
 * the local engine with the history compacted to fit its small context.
 *
 * Honesty rules baked in:
 *  - [onEngineSwitch] fires on every takeover so the switch and its REAL
 *    reason land in the episode log — a fallback is visible, never silent.
 *  - Auth errors are rethrown, never fallen back: a broken key must be
 *    fixed by the user in Setup, not masked by the offline engine.
 *  - [lastEngineUsed] reports which engine actually produced each step.
 *
 * Context budget: the wrapper advertises the PRIMARY engine's budget so the
 * kernel compacts for cloud (100K) and cloud conversations get the full
 * window. When the secondary takes over, [compactFor] re-compacts the
 * history to the secondary's budget on the fly — preserving the system
 * message and never orphaning a tool result from its tool call.
 */
class FallbackLlm(
    private val primary: Llm,
    private val secondary: Llm,
) : Llm {

    override val label: String = "${primary.label} → ${secondary.label}"

    /** The kernel compacts for the primary engine; the secondary self-compacts in [step]. */
    override val contextTokenBudget: Int get() = primary.contextTokenBudget

    /** Invoked on every primary→secondary takeover (ViewModel wires it to the episode log). */
    @Volatile
    var onEngineSwitch: ((primaryLabel: String, secondaryLabel: String, reason: String) -> Unit)? = null

    /** Which engine produced the most recent step — for honest trace labels. */
    @Volatile
    var lastEngineUsed: String = primary.label
        private set

    override suspend fun step(messages: List<Llm.Message>, tools: List<Llm.ToolSpec>): Llm.LlmResponse {
        return try {
            primary.step(messages, tools).also { lastEngineUsed = primary.label }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // STOP button / timeout: cancellation is never a fallback trigger.
            throw e
        } catch (e: Exception) {
            if (isAuthError(e)) throw e
            val reason = e.message ?: e.javaClass.simpleName
            val compacted = compactFor(messages, secondary.contextTokenBudget)
            runCatching { onEngineSwitch?.invoke(primary.label, secondary.label, reason) }
            secondary.step(compacted, tools).also { lastEngineUsed = secondary.label }
        }
    }

    companion object {
        /**
         * Auth failures must NOT trigger a fallback — swapping engines would
         * hide a broken key behind working offline answers and the user would
         * never learn the cloud side is dead. CloudLlm reports non-retryable
         * HTTP errors as "LLM HTTP <code>: <body>"; these signatures cover
         * Z.ai's 401/403 and its "invalid api key" body strings.
         */
        internal fun isAuthError(e: Throwable): Boolean {
            val m = e.message?.lowercase() ?: return false
            return m.contains("http 401") || m.contains("http 403") ||
                m.contains("unauthorized") || m.contains("invalid api key") ||
                m.contains("invalid_api_key") || m.contains("authorization token")
        }

        /**
         * Pairing-safe history compaction for the small-context secondary.
         *
         * Rules:
         *  1. The system message (Vorv's identity + hard rules) is always kept.
         *  2. The most RECENT messages are kept; the oldest are dropped.
         *  3. A window never STARTS with a tool result whose tool call was
         *     dropped — OpenAI-style protocols (and GenieX's chat template)
         *     reject orphaned tool messages, so leading role="tool" entries
         *     are trimmed until the window starts at a user or assistant
         *     message.
         *  4. If even the newest message alone busts the budget, it is still
         *     sent — the kernel already truncates oversized single payloads,
         *     and dropping the newest message would answer a different
         *     question than the one being asked.
         *
         * Budget arithmetic is deliberately conservative (3 chars ≈ 1 token
         * vs. the usual ~4) so a "fits" verdict errs toward extra trimming.
         */
        internal fun compactFor(messages: List<Llm.Message>, tokenBudget: Int): List<Llm.Message> {
            val budgetChars = tokenBudget * 3
            fun size(m: Llm.Message): Int =
                m.content.length + m.toolCalls.sumOf { it.arguments.length + it.name.length }

            if (messages.sumOf(::size) <= budgetChars) return messages

            val system = messages.firstOrNull { it.role == "system" }
            val rest = if (system != null && messages.first().role == "system") {
                messages.drop(1)
            } else messages

            val window = ArrayDeque<Llm.Message>()
            var used = system?.let(::size) ?: 0
            for (m in rest.asReversed()) {
                val s = size(m)
                if (window.isNotEmpty() && used + s > budgetChars) break
                window.addFirst(m)
                used += s
            }
            // Rule 3: no orphaned tool results at the window's head.
            while (window.isNotEmpty() && window.first().role == "tool") {
                val dropped = window.removeFirst()
                used -= size(dropped)
            }
            if (window.isEmpty()) {
                // Degenerate budget: keep only the newest message so the
                // secondary still answers the ACTUAL current question.
                val newest = rest.lastOrNull() ?: return messages
                window.add(newest)
            }
            return if (system != null) listOf(system) + window.toList() else window.toList()
        }
    }
}
