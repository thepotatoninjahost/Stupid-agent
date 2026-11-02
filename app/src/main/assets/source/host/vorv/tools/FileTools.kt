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
        listRecursive(target, "", listing)

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

    private fun listRecursive(df: DocumentFile, prefix: String, out: StringBuilder) {
        df.listFiles().forEach { child ->
            val rel = if (prefix.isBlank()) child.name ?: "?" else "$prefix/${child.name}"
            if (child.isDirectory) {
                out.appendLine("[dir] $rel/")
                listRecursive(child, rel, out)
            } else {
                val kb = child.length() / 1024
                out.appendLine("      $rel (${kb}KB)")
            }
        }
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
                ToolResult(
                    ok = true,
                    output = content.take(MAX_OUTPUT_CHARS),  // hard cap to keep prompt small
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
    override val description = "Write text content to a file in the workspace. Overwrites if exists, creates if not. Use for saving artifacts, reports, generated code."

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
                return@withContext ToolResult(
                    ok = true,
                    output = "Wrote ${content.length} chars to $path",
                    durationMs = System.currentTimeMillis() - t0,
                    citation = Citation("file", target.uri.toString(), "file: $path"),
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
