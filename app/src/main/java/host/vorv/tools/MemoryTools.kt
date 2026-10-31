package host.vorv.tools

import com.google.gson.JsonObject
import host.vorv.data.VorvDatabase
import host.vorv.data.entity.KnowledgeEntry
import host.vorv.llm.EmbeddingCache

/**
 * memory_write — persist a fact for future sessions.
 */
class MemoryWriteTool(private val db: VorvDatabase) : Tool {
    override val name = "memory_write"
    override val description = "Persist a fact or lesson to long-term memory. Use when you learn something the user said, or you discovered something worth keeping."

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("topic", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Short topic label, e.g. 'user-preferences-testing'")
            })
            add("content", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "The fact to remember, as a full sentence.")
            })
        })
        add("required", com.google.gson.JsonArray().apply { add("topic"); add("content") })
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult {
        val t0 = System.currentTimeMillis()
        val topic = input.get("topic")?.asString ?: return ToolResult(ok = false, output = "", error = "missing topic", durationMs = System.currentTimeMillis() - t0)
        val content = input.get("content")?.asString ?: return ToolResult(ok = false, output = "", error = "missing content", durationMs = System.currentTimeMillis() - t0)
        val entry = KnowledgeEntry(
            topic = topic,
            content = content,
            source = "memory_write",
            citationType = "memory",
            citationRef = "",  // filled below
            confidence = 0.9f,
        )
        db.knowledgeDao().upsert(entry)
        // Re-fetch to get the ID, then update citationRef to point at itself
        val saved = db.knowledgeDao().searchLike(topic, 1).firstOrNull()
            ?: return ToolResult(true, "Stored: '$topic'", durationMs = System.currentTimeMillis() - t0,
                citation = Citation("memory", "", "memory"))
        db.knowledgeDao().update(saved.copy(citationRef = saved.id))
        // Vector-embed at write time so memory_recall can cosine-rank (local model only).
        EmbeddingCache.embed("$topic. $content", saved.id)
        return ToolResult(
            ok = true,
            output = "Stored memory: '$topic' (id=${saved.id})",
            durationMs = System.currentTimeMillis() - t0,
            citation = Citation("memory", saved.id, "memory #${saved.id.take(8)}"),
        )
    }
}

/**
 * memory_recall — search long-term memory by keyword (FTS4).
 */
class MemoryRecallTool(private val db: VorvDatabase) : Tool {
    override val name = "memory_recall"
    override val description = "Search long-term memory by keyword. Returns top matches with their memory IDs. Use when the user asks about something you may have learned before."

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("query", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Search keywords.")
            })
            add("limit", JsonObject().apply {
                addProperty("type", "integer")
                addProperty("description", "Max results. Default 5.")
            })
        })
        add("required", com.google.gson.JsonArray().apply { add("query") })
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult {
        val t0 = System.currentTimeMillis()
        val query = input.get("query")?.asString ?: return ToolResult(ok = false, output = "", error = "missing query", durationMs = System.currentTimeMillis() - t0)
        val limit = input.get("limit")?.takeIf { !it.isJsonNull }?.asInt ?: 5

        // 1) Vector search first: embed the query, cosine-rank cached entry vectors (local model only).
        val vectorHits: List<KnowledgeEntry> = if (EmbeddingCache.active) {
            EmbeddingCache.embed(query, "__query__")?.let { qv ->
                EmbeddingCache.rank(qv, limit * 2)
                    .mapNotNull { (id, score) -> if (score < 0.35) null else db.knowledgeDao().get(id) }
            }.orEmpty()
        } else emptyList()

        // 2) Keyword FTS4 search — always available, even without the local model.
        val keywordHits = try {
            db.knowledgeDao().searchFts("$query*", limit).ifEmpty {
                db.knowledgeDao().searchLike(query, limit)
            }
        } catch (e: Exception) {
            db.knowledgeDao().searchLike(query, limit)
        }

        // Merge, dedupe by id, vector hits ranked first.
        val results = (vectorHits + keywordHits).distinctBy { it.id }.take(limit)

        results.forEach { db.knowledgeDao().markRecalled(it.id) }

        val output = if (results.isEmpty()) {
            "No memories matched '$query'."
        } else {
            results.joinToString("\n") { m ->
                "• [id=${m.id.take(8)}] ${m.topic}: ${m.content.take(200)}"
            }
        }
        return ToolResult(
            ok = true,
            output = output,
            durationMs = System.currentTimeMillis() - t0,
            citation = if (results.isNotEmpty()) Citation("memory", results.first().id, "memory #${results.first().id.take(8)}") else null,
        )
    }
}
