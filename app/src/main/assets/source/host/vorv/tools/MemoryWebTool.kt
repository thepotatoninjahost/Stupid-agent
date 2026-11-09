package host.vorv.tools

import com.google.gson.JsonObject
import host.vorv.data.dao.KnowledgeGraphDao

/**
 * v0.8.0 — memory_web: the agent reading its own non-linear brain
 * (owner spec, 2026-11-09: an agent that can inspect and extend its own
 * memory web).
 *
 * Actions:
 *  - stats  — node/edge counts, kind breakdown, top-salience nodes;
 *  - query  — keyword search over the web, each hit with its edges;
 *  - node   — one full node by (short) id, with all edges touching it.
 *
 * Fail-closed honest errors, real output only, citation-backed per node
 * (type "memory", ref = node id) so a statement grounded in the web can
 * carry a tappable proof chip like every other evidence family.
 */
class MemoryWebTool(private val dao: KnowledgeGraphDao) : Tool {
    override val name = "memory_web"
    override val description =
        "Inspect your associative knowledge web (the graph memory that grows from every chat and task). " +
            "action=stats gives counts and top nodes; action=query searches by keyword and shows each hit's associations; " +
            "action=node returns one full node by its 8-char id. Use it to ground statements about what you know."

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("action", JsonObject().apply {
                addProperty("type", "string")
                add("enum", com.google.gson.JsonArray().apply { add("stats"); add("query"); add("node") })
                addProperty("description", "What to do: 'stats', 'query', or 'node'.")
            })
            add("q", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "For action=query: the keyword(s).")
            })
            add("id", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "For action=node: the 8-char node id shown in prompts and query output.")
            })
        })
        add("required", com.google.gson.JsonArray().apply { add("action") })
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult {
        val t0 = System.currentTimeMillis()
        val action = input.get("action")?.takeIf { !it.isJsonNull }?.asString
            ?: return fail("missing action — use 'stats', 'query', or 'node'", t0)
        return when (action) {
            "stats" -> stats(t0)
            "query" -> {
                val q = input.get("q")?.takeIf { !it.isJsonNull }?.asString
                    ?: return fail("action=query needs a 'q' keyword", t0)
                query(q, t0)
            }
            "node" -> {
                val id = input.get("id")?.takeIf { !it.isJsonNull }?.asString
                    ?: return fail("action=node needs an 'id'", t0)
                node(id, t0)
            }
            else -> fail("unknown action '$action' — use 'stats', 'query', or 'node'", t0)
        }
    }

    private fun fail(msg: String, t0: Long) =
        ToolResult(ok = false, output = "", error = msg, durationMs = System.currentTimeMillis() - t0)

    private suspend fun stats(t0: Long): ToolResult {
        val nodes = dao.nodeCount()
        val edges = dao.edgeCount()
        if (nodes == 0) {
            return ToolResult(
                ok = true,
                output = "Knowledge web is empty — 0 nodes, 0 edges. Nothing has been learned yet; " +
                    "it grows automatically from every chat message and task outcome.",
                durationMs = System.currentTimeMillis() - t0,
            )
        }
        val sb = StringBuilder()
        sb.appendLine("Knowledge web: $nodes node(s), $edges edge(s).")
        dao.countsByKind().forEach { sb.appendLine("  ${it.kind}: ${it.c}") }
        val operator = dao.operator()
        sb.appendLine(
            if (operator != null) "Operator: ${operator.name} (learned ${operator.learnedFrom})."
            else "Operator: not yet identified."
        )
        sb.appendLine("Top salience:")
        dao.recentNodes(500)
            .sortedByDescending { it.salience }
            .take(5)
            .forEach { sb.appendLine("  ${it.id.take(8)} | ${it.kind} | ${it.topic} (salience ${"%.2f".format(it.salience)})") }
        return ToolResult(
            ok = true,
            output = sb.toString().trim(),
            durationMs = System.currentTimeMillis() - t0,
            citation = Citation("memory", "web", "knowledge web"),
        )
    }

    private suspend fun query(q: String, t0: Long): ToolResult {
        val hits = dao.search(q, limit = 10)
        if (hits.isEmpty()) {
            return ToolResult(
                ok = true,
                output = "No knowledge-web nodes matched '$q'. The web only holds what chats and tasks actually taught.",
                durationMs = System.currentTimeMillis() - t0,
            )
        }
        val touching = dao.edgesTouching(hits.map { it.id })
        val sb = StringBuilder("Knowledge-web matches for '$q':")
        for (h in hits) {
            sb.appendLine()
            sb.appendLine("[${h.id.take(8)}] ${h.kind} | ${h.topic} (salience ${"%.2f".format(h.salience)}, used ${h.accessCount}x)")
            sb.appendLine("  ${capWithMarker(h.content, 400)}")
            val edges = touching.filter { it.fromId == h.id || it.toId == h.id }
            if (edges.isNotEmpty()) {
                sb.appendLine("  associations:")
                edges.take(5).forEach {
                    val other = if (it.fromId == h.id) it.toId else it.fromId
                    sb.appendLine("    --${it.relation}(${"%.2f".format(it.weight)})--> [${other.take(8)}]")
                }
            }
        }
        return ToolResult(
            ok = true,
            output = sb.toString().trim(),
            durationMs = System.currentTimeMillis() - t0,
            citation = Citation("memory", hits.first().id, "memory web #${hits.first().id.take(8)}"),
        )
    }

    private suspend fun node(id: String, t0: Long): ToolResult {
        val n = dao.nodeByShortId(id.trim())
            ?: return fail(
                "no knowledge-web node with id '$id' — list ids via action=query or action=stats",
                t0,
            )
        val touching = dao.edgesTouching(listOf(n.id))
        val sb = StringBuilder()
        sb.appendLine("[${n.id.take(8)}] ${n.kind} | ${n.topic}")
        sb.appendLine(n.content)
        sb.appendLine("source=${n.source} salience=${"%.2f".format(n.salience)} used=${n.accessCount}x created=${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(n.ts))}")
        if (touching.isNotEmpty()) {
            sb.appendLine("associations (${touching.size}):")
            touching.take(10).forEach {
                val other = if (it.fromId == n.id) it.toId else it.fromId
                sb.appendLine("  --${it.relation}(${"%.2f".format(it.weight)})--> [${other.take(8)}]")
            }
        }
        return ToolResult(
            ok = true,
            output = sb.toString().trim(),
            durationMs = System.currentTimeMillis() - t0,
            citation = Citation("memory", n.id, "memory web #${n.id.take(8)}"),
        )
    }
}
