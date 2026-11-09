package host.vorv.kernel

import host.vorv.knowledge.KnowledgeGraph

/**
 * v0.8.0 — weaves the activated knowledge-web subgraph into the task
 * prompt (owner spec, 2026-11-09: "memory and knowledge web type brain and
 * thought process").
 *
 * Pure, unit-tested, and HONEST about what it is: the section is labeled
 * associative memory, every node carries its short id so the model can
 * cite it or pull the full text via memory_recall / memory_web, and the
 * header states the operative rules — single operator, cite-or-refuse.
 *
 * Budgets follow the engine split used by ConversationPrompt: the local
 * engine's 4096-token window gets a small web, cloud gets a wider one.
 */
object KnowledgePrompt {

    /**
     * @param operatorName null when no operator identity has been learned.
     * @return "" when nothing is active — no section, zero wasted tokens.
     */
    fun section(
        activation: KnowledgeGraph.Activation,
        operatorName: String?,
        localEngine: Boolean,
    ): String {
        if (activation.nodes.isEmpty()) return ""

        val nodeBudget = if (localEngine) 6 else 12
        val charBudget = if (localEngine) 550 else 1600
        val maxNodeChars = if (localEngine) 90 else 160

        val keptLines = mutableListOf<String>()
        var used = 0
        for (n in activation.nodes) {
            if (keptLines.size >= nodeBudget) break
            val content = n.content.replace("\n", " ")
            val body =
                if (content.length > maxNodeChars) content.take(maxNodeChars) + "…(truncated)"
                else content
            val line = "- ${n.id.take(8)} | ${n.kind} | ${n.topic}: $body"
            if (keptLines.isNotEmpty() && used + line.length > charBudget) break
            used += line.length
            keptLines.add(line)
        }
        val omitted = activation.nodes.size - keptLines.size

        val sb = StringBuilder()
        sb.appendLine("[Knowledge web — associative memory, auto-learned; NOT a linear history]")
        sb.appendLine(
            if (operatorName != null) {
                "Single operator: $operatorName. You serve this operator only. " +
                    "Honesty rules are absolute: cite evidence or say you don't know."
            } else {
                "You have exactly one operator (this device's owner); their name is not yet learned. " +
                    "Honesty rules are absolute: cite evidence or say you don't know."
            }
        )
        sb.appendLine("Nodes (id | kind | topic: content; cite by id, verify with memory_recall/memory_web):")
        keptLines.forEach { sb.appendLine(it) }
        if (omitted > 0) sb.appendLine("…($omitted node(s) omitted for budget)")

        if (activation.edges.isNotEmpty()) {
            sb.appendLine("Associations:")
            for (e in activation.edges.take(6)) {
                sb.appendLine("  ${e.from.take(8)} --${e.relation}(${"%.2f".format(e.weight)})--> ${e.to.take(8)}")
            }
        }
        return sb.toString().trim()
    }
}
