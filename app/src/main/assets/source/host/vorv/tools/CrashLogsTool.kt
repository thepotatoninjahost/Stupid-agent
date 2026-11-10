package host.vorv.tools

import android.content.Context
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * crash_logs — Vorv reading its OWN crash evidence.
 *
 * Field finding (night session 2026-11-08, screen recording 20:32): after a
 * real crash the user asked "did you see the crash report?" and Vorv answered
 * "I don't have any crash report information" — tagged [uncited], because
 * not a single tool could reach the app's own crash artifacts:
 *
 *   - CrashReportWorker writes Java crash reports to
 *     getExternalFilesDir/crash_logs/ — file_* tools only see the SAF
 *     workspace, never the app's private dirs.
 *   - WatchdogService writes crash detections to getExternalFilesDir/watchdog/
 *     — same blindness.
 *   - BootTracer holds the boot breadcrumbs — internal only.
 *
 * So the model's "I don't have it" was the literal truth of a blind agent.
 * The honest fix is not a better prompt — it is EYES. This tool lists and
 * reads exactly those artifacts, so "did you see the crash report?" becomes
 * a tool call instead of a shrug.
 *
 * Paths are relative to getExternalFilesDir(null):
 *   crash_logs/<file>.txt   — Java crash reports (CrashReportWorker), incl.
 *                             synthesized native-crash reports
 *   watchdog/<file>.txt     — watchdog crash detections (heartbeat + traces)
 *   boot_trace_current      — the CURRENT boot's breadcrumb trace
 *   boot_trace_previous     — the PREVIOUS boot's trace (virtual: the
 *                             section before the latest BOOT_STARTED marker)
 */
class CrashLogsTool(private val appContext: Context) : Tool {

    override val name = "crash_logs"
    override val description =
        "List or read Vorv's OWN crash and boot evidence: Java crash reports (crash_logs/), " +
            "watchdog crash detections (watchdog/), and boot traces (boot_trace_current, " +
            "boot_trace_previous), and Android process_exit_history. For ANY question about Vorv crashing, restarting, or " +
            "misbehaving — check this BEFORE saying you don't have the information. " +
            "action=list returns paths; action=read returns a file's content."

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("action", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "'list' (see what exists) or 'read' (open one path)")
            })
            add("path", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "A path from action=list, e.g. 'crash_logs/crash_2026-11-08_01-23-45-123pid1234.txt' or 'boot_trace_previous'")
            })
        })
        add("required", com.google.gson.JsonArray().apply { add("action") })
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val action = input.get("action")?.asString?.trim()?.lowercase()
            ?: return@withContext ToolResult(ok = false, output = "", error = "missing action ('list' or 'read')", durationMs = System.currentTimeMillis() - t0)

        when (action) {
            "list" -> list(t0)
            "read" -> {
                val path = input.get("path")?.asString?.trim()
                if (path.isNullOrBlank()) {
                    ToolResult(ok = false, output = "", error = "read needs a path from action=list", durationMs = System.currentTimeMillis() - t0)
                } else {
                    read(path, t0)
                }
            }
            else -> ToolResult(ok = false, output = "", error = "unknown action '$action' — use 'list' or 'read'", durationMs = System.currentTimeMillis() - t0)
        }
    }

    private fun list(t0: Long): ToolResult {
        val extDir = appContext.getExternalFilesDir(null)
            ?: return ToolResult(ok = false, output = "", error = "external files dir unavailable", durationMs = System.currentTimeMillis() - t0)

        val sb = StringBuilder()
        sb.appendLine("Vorv crash & boot evidence (paths for action=read):")

        val crashDir = File(extDir, "crash_logs")
        val reports = crashDir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        if (reports.isEmpty()) {
            sb.appendLine("  crash_logs/ — (no Java crash reports)")
        } else {
            sb.appendLine("  crash_logs/ — ${reports.size} report(s), newest first:")
            reports.take(MAX_LIST).forEach {
                sb.appendLine("    crash_logs/${it.name}  (${it.length()} bytes, ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(it.lastModified()))})")
            }
            if (reports.size > MAX_LIST) sb.appendLine("    …(${reports.size - MAX_LIST} older omitted)")
        }

        val wdDir = File(extDir, "watchdog")
        val wd = wdDir.listFiles()
            ?.filter { it.isFile && (it.name.startsWith("crash_detected") || it.name.startsWith("handled_crash_detected")) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        if (wd.isEmpty()) {
            sb.appendLine("  watchdog/ — (no watchdog crash detections)")
        } else {
            sb.appendLine("  watchdog/ — ${wd.size} detection(s), newest first:")
            wd.take(MAX_LIST).forEach {
                sb.appendLine("    watchdog/${it.name}  (${it.length()} bytes, ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(it.lastModified()))})")
            }
            if (wd.size > MAX_LIST) sb.appendLine("    …(${wd.size - MAX_LIST} older omitted)")
        }

        sb.appendLine("  boot_trace_previous  — previous main-process boot's breadcrumbs")
        sb.appendLine("  boot_trace_current   — complete stored boot trace")
        sb.appendLine("  process_exit_history — Android's recent main-process exit records")

        val newest = reports.firstOrNull()?.let { "  Newest crash: ${it.name}" }
            ?: wd.firstOrNull()?.let { "  Newest detection: ${it.name}" }
            ?: "  No crash artifacts on disk. Absence of a report does not identify the cause."
        sb.appendLine(newest)

        return ToolResult(
            ok = true,
            output = sb.toString().trim(),
            durationMs = System.currentTimeMillis() - t0,
            citation = Citation("file", "crash_logs", "crash evidence"),
        )
    }

    private fun read(path: String, t0: Long): ToolResult {
        val extDir = appContext.getExternalFilesDir(null)
            ?: return ToolResult(ok = false, output = "", error = "external files dir unavailable", durationMs = System.currentTimeMillis() - t0)

        // Virtual entries first — they are not files on disk in their own right.
        when (path.trim('/')) {
            "boot_trace_current" -> {
                val trace = host.vorv.BootTracer.readTrace(appContext)
                return ToolResult(
                    ok = true,
                    output = trace.ifBlank { "(current boot trace is empty)" },
                    durationMs = System.currentTimeMillis() - t0,
                    citation = Citation("file", "boot_trace_current", "boot trace"),
                )
            }
            "boot_trace_previous" -> {
                val trace = host.vorv.BootTracer.readPreviousTrace(appContext)
                return if (trace.isNullOrBlank()) {
                    ToolResult(ok = false, output = "", error = "No previous main-process boot trace available", durationMs = System.currentTimeMillis() - t0)
                } else {
                    ToolResult(
                        ok = true,
                        output = trace,
                        durationMs = System.currentTimeMillis() - t0,
                        citation = Citation("file", "boot_trace_previous", "previous boot trace"),
                    )
                }
            }
            "process_exit_history" -> {
                return host.vorv.ProcessExitHistory.read(appContext).fold(
                    onSuccess = { history -> ToolResult(
                        ok = true, output = history, durationMs = System.currentTimeMillis() - t0,
                        citation = Citation("file", "process_exit_history", "Android process exit history"),
                    ) },
                    onFailure = { error -> ToolResult(
                        ok = false, output = "", error = "Android process exit history unavailable: ${error.message}",
                        durationMs = System.currentTimeMillis() - t0,
                    ) },
                )
            }
        }

        // Real files: ONLY inside crash_logs/ or watchdog/ — never arbitrary
        // paths. Reject traversal explicitly; File canonical-path check is
        // the belt, the prefix allow-list is the suspenders.
        val rel = path.trim('/')
        val allowed = rel.startsWith("crash_logs/") || rel.startsWith("watchdog/")
        val target = File(extDir, rel)
        val canonicalInside = runCatching {
            target.canonicalFile.startsWith(extDir.canonicalFile)
        }.getOrDefault(false)
        if (!allowed || !canonicalInside || !target.isFile) {
            return ToolResult(ok = false, output = "", error = "Not a readable crash path: $path (use action=list to see valid paths)", durationMs = System.currentTimeMillis() - t0)
        }

        return try {
            val content = target.readText()
            ToolResult(
                ok = true,
                output = if (content.length > MAX_READ_CHARS) {
                    content.take(MAX_READ_CHARS) + "\n…(truncated at $MAX_READ_CHARS chars — read the file in chunks via shell/python if you need more)"
                } else content,
                durationMs = System.currentTimeMillis() - t0,
                citation = Citation("file", rel, "crash report"),
            )
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = "Read failed: ${e.message}", durationMs = System.currentTimeMillis() - t0)
        }
    }

    private companion object {
        const val MAX_LIST = 20
        const val MAX_READ_CHARS = 50_000
    }
}
