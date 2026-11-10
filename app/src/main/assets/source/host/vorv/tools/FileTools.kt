package host.vorv.tools

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * file_list — recursive list of files in the SAF-granted workspace.
 */
class FileListTool(private val appContext: Context) : Tool {
    override val name = "file_list"
    override val description = "List files in the user's granted workspace folder. Returns relative paths + sizes. Use before reading or writing files."

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("path", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Subdirectory to list, or empty for root. e.g. '' or 'src/'")
            })
        })
        add("required", com.google.gson.JsonArray().apply { add("path") })
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        if (ctx.workspaceUri.isNullOrBlank()) {
            return@withContext ToolResult(ok = false, output = "", error = "No workspace granted. Ask user to open Files tab and pick a folder.", durationMs = System.currentTimeMillis() - t0)
        }
        val sub = input.get("path")?.asString ?: ""
        val root = DocumentFile.fromTreeUri(appContext, Uri.parse(ctx.workspaceUri))
            ?: return@withContext ToolResult(ok = false, output = "", error = "Invalid workspace URI", durationMs = System.currentTimeMillis() - t0)

        val target = if (sub.isBlank()) root else navigate(root, sub)
            ?: return@withContext ToolResult(ok = false, output = "", error = "Path not found: $sub", durationMs = System.currentTimeMillis() - t0)

        val listing = StringBuilder()
        val budget = ListBudget()
        listBounded(target, "", listing, budget)
        // M10: an honest truncation note — the model must KNOW the listing
        // is partial and can ask for subfolders individually.
        when {
            budget.truncatedByEntries -> listing.appendLine(
                "…(listing truncated: $MAX_LIST_ENTRIES-entry cap reached — list subfolders individually for more)")
            budget.truncatedByDepth -> listing.appendLine(
                "…(listing truncated: $MAX_LIST_DEPTH-level depth cap reached — list deeper subfolders individually)")
        }

        ToolResult(
            ok = true,
            output = if (listing.isEmpty()) "(empty folder)" else listing.toString().trim(),
            durationMs = System.currentTimeMillis() - t0,
            citation = Citation("file", ctx.workspaceUri, "workspace"),
        )
    }

    private fun navigate(root: DocumentFile, path: String): DocumentFile? {
        var current = root
        for (segment in path.trim('/').split('/').filter { it.isNotBlank() }) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }
}

/**
 * file_read — read a file's content from the workspace.
 */
class FileReadTool(private val appContext: Context) : Tool {
    override val name = "file_read"
    override val description = "Read text content of a file in the workspace. Returns the content. Fails if path doesn't exist (do NOT guess — try, then adapt)."

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("path", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Relative path inside workspace, e.g. 'src/index.ts'")
            })
        })
        add("required", com.google.gson.JsonArray().apply { add("path") })
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        if (ctx.workspaceUri.isNullOrBlank()) {
            return@withContext ToolResult(ok = false, output = "", error = "No workspace granted.", durationMs = System.currentTimeMillis() - t0)
        }
        val path = input.get("path")?.asString ?: return@withContext ToolResult(ok = false, output = "", error = "missing path", durationMs = System.currentTimeMillis() - t0)
        val root = DocumentFile.fromTreeUri(appContext, Uri.parse(ctx.workspaceUri))
            ?: return@withContext ToolResult(ok = false, output = "", error = "Invalid workspace URI", durationMs = System.currentTimeMillis() - t0)

        val file = navigate(root, path)
        if (file == null || !file.isFile) {
            return@withContext ToolResult(ok = false, output = "", error = "File not found: $path", durationMs = System.currentTimeMillis() - t0)
        }

        try {
            appContext.contentResolver.openInputStream(file.uri)?.use { stream ->
                // Audit M-5: stream-read at most MAX_READ_BYTES (cap × 4 for
                // UTF-8 safety) instead of buffering the whole file, then
                // truncate to the character cap. A 500 MB log can no longer
                // OOM the process before the cap applies.
                val sb = StringBuilder()
                val reader = stream.bufferedReader(Charsets.UTF_8)
                var total = 0L
                while (total < MAX_READ_BYTES) {
                    val cbuf = CharArray(8192)
                    val n = reader.read(cbuf)
                    if (n <= 0) break
                    total += n
                    sb.append(cbuf, 0, n)
                }
                if (total >= MAX_READ_BYTES) sb.append("\n…(truncated at $MAX_READ_BYTES chars)")
                val content = sb.toString()
                // M-1 (code review, 2026-11): the output cap must announce
                // itself — the old silent take() handed the model 50 000
                // chars of a longer file with no sign anything was missing,
                // so "read the whole file" beliefs were silently wrong.
                val output = if (content.length > MAX_OUTPUT_CHARS) {
                    content.take(MAX_OUTPUT_CHARS) +
                        "\n…(truncated at $MAX_OUTPUT_CHARS chars — file_read caps single reads; use shell/python to page through the rest, or ask for specific sections)"
                } else content
                ToolResult(
                    ok = true,
                    output = output,  // hard cap to keep prompt small, now WITH an honest marker
                    durationMs = System.currentTimeMillis() - t0,
                    citation = Citation("file", file.uri.toString(), "file: $path"),
                )
            } ?: ToolResult(ok = false, output = "", error = "Could not open stream", durationMs = System.currentTimeMillis() - t0)
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = "Read failed: ${e.message}", durationMs = System.currentTimeMillis() - t0)
        }
    }

    private companion object {
        const val MAX_OUTPUT_CHARS = 50_000
        const val MAX_READ_BYTES = 200_000L
    }

    private fun navigate(root: DocumentFile, path: String): DocumentFile? {
        var current = root
        for (segment in path.trim('/').split('/').filter { it.isNotBlank() }) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }
}

/**
 * file_write — write/create/overwrite a file in the workspace.
 */
class FileWriteTool(private val appContext: Context) : Tool {
    override val name = "file_write"
    override val description = "Write text content to a file in the workspace. Overwrites if exists, creates if not. Use for saving artifacts, reports, generated code. Requires user confirmation for each write."

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("path", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Relative path inside workspace, e.g. 'report.csv'")
            })
            add("content", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Text content to write.")
            })
        })
        add("required", com.google.gson.JsonArray().apply { add("path"); add("content") })
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        if (ctx.workspaceUri.isNullOrBlank()) {
            return@withContext ToolResult(ok = false, output = "", error = "No workspace granted.", durationMs = System.currentTimeMillis() - t0)
        }
        val path = input.get("path")?.asString ?: return@withContext ToolResult(ok = false, output = "", error = "missing path", durationMs = System.currentTimeMillis() - t0)
        val content = input.get("content")?.asString ?: return@withContext ToolResult(ok = false, output = "", error = "missing content", durationMs = System.currentTimeMillis() - t0)
        if (content.length > MAX_WRITE_CHARS) {
            return@withContext ToolResult(ok = false, output = "",
                error = "Content too large (${content.length} chars, cap $MAX_WRITE_CHARS). " +
                    "Write in smaller chunks or use shell/python with the workspace.",
                durationMs = System.currentTimeMillis() - t0)
        }
        // A workspace grant scopes storage access; it does not authorize a
        // model-selected modification. Ask before even creating parent folders.
        // Keep the approved values in local strings, independent of mutable input.
        val confirm = ctx.confirm
            ?: return@withContext ToolResult(ok = false, output = "",
                error = "User confirmation bridge unavailable — refusing to write (fail closed).",
                durationMs = System.currentTimeMillis() - t0)
        if (!confirm("Vorv wants to create or overwrite a workspace file. " +
                "Existing content will be replaced and missing parent folders created.\n\n" +
                "Workspace: ${ctx.workspaceUri}\nPath: $path\n" +
                "Replacement content (${content.length} chars):\n$content")) {
            return@withContext ToolResult(ok = false, output = "",
                error = "User DENIED the file write. No files were changed.",
                durationMs = System.currentTimeMillis() - t0)
        }
        val root = DocumentFile.fromTreeUri(appContext, Uri.parse(ctx.workspaceUri))
            ?: return@withContext ToolResult(ok = false, output = "", error = "Invalid workspace URI", durationMs = System.currentTimeMillis() - t0)

        // Walk path, creating directories as needed
        val segments = path.trim('/').split('/').filter { it.isNotBlank() }
        var current = root
        for ((i, segment) in segments.withIndex()) {
            if (i == segments.lastIndex) {
                // File
                val existing = current.findFile(segment)
                if (existing != null && existing.isDirectory) {
                    // Audit L-6: a name collision with a DIRECTORY is an honest
                    // error, not a provider-suffixed duplicate file.
                    return@withContext ToolResult(ok = false, output = "",
                        error = "'$path' exists as a DIRECTORY — pick a different file name.",
                        durationMs = System.currentTimeMillis() - t0)
                }
                val target = existing?.takeIf { it.isFile }
                    ?: current.createFile("text/plain", segment)
                    ?: return@withContext ToolResult(ok = false, output = "", error = "Could not create file: $segment", durationMs = System.currentTimeMillis() - t0)
                try {
                    appContext.contentResolver.openOutputStream(target.uri, "wt")?.use { os ->
                        os.write(content.toByteArray())
                    } ?: return@withContext ToolResult(ok = false, output = "", error = "Could not open output stream", durationMs = System.currentTimeMillis() - t0)
                } catch (e: Exception) {
                    return@withContext ToolResult(ok = false, output = "", error = "Write failed: ${e.message}", durationMs = System.currentTimeMillis() - t0)
                }
                // M9 (code review, 2026-11): SAF providers may rename a file
                // on create (adding an extension, uniquifying a collision).
                // The tool reports the file's REAL name — a reported path
                // that does not exist is a lie the model trips over on the
                // next file_read.
                val (actualPath, renameNote) = resolveWrittenPath(path, target.name ?: segment)
                return@withContext ToolResult(
                    ok = true,
                    output = "Wrote ${content.length} chars to $actualPath$renameNote",
                    durationMs = System.currentTimeMillis() - t0,
                    citation = Citation("file", target.uri.toString(), "file: $actualPath"),
                )
            } else {
                // Directory
                current = current.findFile(segment)?.takeIf { it.isDirectory }
                    ?: current.createDirectory(segment)
                    ?: return@withContext ToolResult(ok = false, output = "", error = "Could not create directory: $segment", durationMs = System.currentTimeMillis() - t0)
            }
        }
        ToolResult(ok = false, output = "", error = "Empty path", durationMs = System.currentTimeMillis() - t0)
    }

    private companion object {
        /** 1 MB cap on a single file_write payload. */
        const val MAX_WRITE_CHARS = 1_000_000
    }
}

/**
 * M10 (code review, 2026-11): file_list bounds. The old walker recursed
 * without limit in depth or entries — a deep or huge tree produced
 * megabytes of listing. Bounded, with an honest truncation note appended
 * by the caller.
 */
internal const val MAX_LIST_DEPTH = 12
internal const val MAX_LIST_ENTRIES = 2000

/** M10: walker state — depth, entry count, and which cap fired. */
internal class ListBudget {
    var depth = 0
    var entries = 0
    var truncatedByEntries = false
    var truncatedByDepth = false
}

/** M10: bounded recursive listing. Pure over DocumentFile, unit-testable. */
internal fun listBounded(df: DocumentFile, prefix: String, out: StringBuilder, budget: ListBudget) {
    if (budget.depth >= MAX_LIST_DEPTH) {
        budget.truncatedByDepth = true
        return
    }
    val children = df.listFiles()
    for (child in children) {
        if (budget.entries >= MAX_LIST_ENTRIES) {
            budget.truncatedByEntries = true
            return
        }
        val rel = if (prefix.isBlank()) child.name ?: "?" else "$prefix/${child.name}"
        budget.entries++
        if (child.isDirectory) {
            out.appendLine("[dir] $rel/")
            budget.depth++
            listBounded(child, rel, out, budget)
            budget.depth--
            if (budget.entries >= MAX_LIST_ENTRIES) {
                budget.truncatedByEntries = true
                return
            }
        } else {
            val kb = child.length() / 1024
            out.appendLine("      $rel (${kb}KB)")
        }
    }
}

/**
 * M9 (code review, 2026-11): the path file_write REPORTS. SAF providers
 * may rename a created file (extension added, collision uniquified), so
 * the reported path must be built from the file's REAL name — plus an
 * explicit note when it differs from what was requested. Returns
 * (actualPath, note); the note is "" when nothing was renamed.
 */
internal fun resolveWrittenPath(requested: String, actualName: String): Pair<String, String> {
    val req = requested.trim('/')
    val requestedName = req.substringAfterLast('/')
    val dirPart = if (req.contains('/')) req.substringBeforeLast('/') else ""
    val actualPath = if (dirPart.isBlank()) actualName else "$dirPart/$actualName"
    val note = if (actualName != requestedName) {
        " — NOTE: the storage provider renamed '$requestedName' to '$actualName'"
    } else ""
    return actualPath to note
}
