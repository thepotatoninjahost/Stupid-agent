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
            return@withContext ToolResult(false, "", "No workspace granted. Ask user to open Files tab and pick a folder.", System.currentTimeMillis() - t0)
        }
        val sub = input.get("path")?.asString ?: ""
        val root = DocumentFile.fromTreeUri(appContext, Uri.parse(ctx.workspaceUri))
            ?: return@withContext ToolResult(false, "", "Invalid workspace URI", System.currentTimeMillis() - t0)

        val target = if (sub.isBlank()) root else navigate(root, sub)
            ?: return@withContext ToolResult(false, "", "Path not found: $sub", System.currentTimeMillis() - t0)

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
            return@withContext ToolResult(false, "", "No workspace granted.", System.currentTimeMillis() - t0)
        }
        val path = input.get("path")?.asString ?: return@withContext ToolResult(false, "", "missing path", System.currentTimeMillis() - t0)
        val root = DocumentFile.fromTreeUri(appContext, Uri.parse(ctx.workspaceUri))
            ?: return@withContext ToolResult(false, "", "Invalid workspace URI", System.currentTimeMillis() - t0)

        val file = navigate(root, path)
        if (file == null || !file.isFile) {
            return@withContext ToolResult(false, "", "File not found: $path", System.currentTimeMillis() - t0)
        }

        try {
            appContext.contentResolver.openInputStream(file.uri)?.use { stream ->
                val content = stream.bufferedReader().readText()
                ToolResult(
                    ok = true,
                    output = content.take(50000),  // hard cap to keep prompt small
                    durationMs = System.currentTimeMillis() - t0,
                    citation = Citation("file", file.uri.toString(), "file: $path"),
                )
            } ?: ToolResult(false, "", "Could not open stream", System.currentTimeMillis() - t0)
        } catch (e: Exception) {
            ToolResult(false, "", "Read failed: ${e.message}", System.currentTimeMillis() - t0)
        }
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
            return@withContext ToolResult(false, "", "No workspace granted.", System.currentTimeMillis() - t0)
        }
        val path = input.get("path")?.asString ?: return@withContext ToolResult(false, "", "missing path", System.currentTimeMillis() - t0)
        val content = input.get("content")?.asString ?: return@withContext ToolResult(false, "", "missing content", System.currentTimeMillis() - t0)
        val root = DocumentFile.fromTreeUri(appContext, Uri.parse(ctx.workspaceUri))
            ?: return@withContext ToolResult(false, "", "Invalid workspace URI", System.currentTimeMillis() - t0)

        // Walk path, creating directories as needed
        val segments = path.trim('/').split('/').filter { it.isNotBlank() }
        var current = root
        for ((i, segment) in segments.withIndex()) {
            if (i == segments.lastIndex) {
                // File
                val existing = current.findFile(segment)
                val target = existing?.takeIf { it.isFile }
                    ?: current.createFile("text/plain", segment)
                    ?: return@withContext ToolResult(false, "", "Could not create file: $segment", System.currentTimeMillis() - t0)
                try {
                    appContext.contentResolver.openOutputStream(target.uri, "wt")?.use { os ->
                        os.write(content.toByteArray())
                    } ?: return@withContext ToolResult(false, "", "Could not open output stream", System.currentTimeMillis() - t0)
                } catch (e: Exception) {
                    return@withContext ToolResult(false, "", "Write failed: ${e.message}", System.currentTimeMillis() - t0)
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
                    ?: return@withContext ToolResult(false, "", "Could not create directory: $segment", System.currentTimeMillis() - t0)
            }
        }
        ToolResult(false, "", "Empty path", System.currentTimeMillis() - t0)
    }
}
