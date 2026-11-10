package host.vorv.tools

import com.google.gson.JsonObject
import host.vorv.data.VorvDatabase
import host.vorv.data.entity.KnowledgeEntry

/**
 * memory_write - persist a fact for future sessions.
 *
 * v0.5.0: removed the EmbeddingCache path. GenieX's LlmWrapper API does not
 * expose an embed() call (unlike the llama-android library we used in
 * v0.2.0-v0.4.0). memory_recall falls back to Room FTS4 + LIKE keyword
 * search, which is what we actually use today anyway — the vector path
 * was an optimization that never shipped end-to-end.
 */
class MemoryWriteTool(private val db: VorvDatabase) : Tool {
    override val name = "memory_write"
    override val description = "Persist a fact or lesson to long-term memory. Requires user approval of the exact topic and content before storing a model-proposed memory."

    override val timeoutMs: Long get() = 70_000L

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

        // Model arguments may originate in untrusted tool observations. Only the
        // user consent bridge can authorize persistence; model-supplied approval
        // or provenance fields are never accepted as authorization.
        val confirm = ctx.confirm ?: return ToolResult(
            ok = false, output = "", error = "User confirmation unavailable — memory was not stored.",
            durationMs = System.currentTimeMillis() - t0,
        )
        if (!confirm(
                (if (ctx.userAuthoredMemory) "Store this user-taught memory for future sessions?\n\n"
                 else "Store this model-proposed memory for future sessions? " +
                    "Its origin and accuracy have not been independently verified.\n\n") +
                    "Topic:\n$topic\n\nContent:\n$content"
            )) {
            return ToolResult(
                ok = false, output = "", error = "User did not approve — memory was not stored.",
                durationMs = System.currentTimeMillis() - t0,
            )
        }

        // Generate the ID up-front and set citationRef before insert (audit H-9).
        // The v0.5.x code inserted, then tried to FIND the row again via
        // searchLike(topic, 1) — a substring query that could match a DIFFERENT
        // row (e.g. topic "cat" matching "category") and update the wrong
        // citationRef. The row's own id is known before insert; no lookup needed.
        val id = java.util.UUID.randomUUID().toString()
        val entry = KnowledgeEntry(
            id = id,
            topic = topic,
            content = content,
            source = if (ctx.userAuthoredMemory) "user-taught" else "memory_write:user-approved-model-proposal",
            citationType = "memory",
            citationRef = id,
            confidence = if (ctx.userAuthoredMemory) 1.0f else 0.5f,
        )
        db.knowledgeDao().upsert(entry)
        return ToolResult(
            ok = true,
            output = "Stored memory: '$topic' (id=$id)",
            durationMs = System.currentTimeMillis() - t0,
            citation = Citation("memory", id, "memory #${id.take(8)}"),
        )
    }
}

/**
 * memory_recall - search long-term memory by keyword (FTS4 + LIKE fallback).
 *
 * v0.5.0: vector path removed (GenieX doesn't expose embed() through
 * LlmWrapper). Keyword search is what's used — and it's plenty good for
 * a coding agent's small knowledge base.
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

        val results = try {
            db.knowledgeDao().searchFts("$query*", limit).ifEmpty {
                db.knowledgeDao().searchLike(query, limit)
            }
        } catch (e: Exception) {
            db.knowledgeDao().searchLike(query, limit)
        }

        results.forEach { db.knowledgeDao().markRecalled(it.id) }

        val output = if (results.isEmpty()) {
            "No memories matched '$query'."
        } else {
            results.joinToString("\n") { m ->
                // M-2 family (code review, 2026-11): the 200-char preview
                // announces itself — the model knows the full memory text
                // is longer and can ask for it by id.
                val preview = if (m.content.length > 200) {
                    m.content.take(200) + "…(memory preview truncated at 200 chars — full text retrievable by id)"
                } else m.content
                "[id=${m.id.take(8)}] ${m.topic}: $preview"
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
