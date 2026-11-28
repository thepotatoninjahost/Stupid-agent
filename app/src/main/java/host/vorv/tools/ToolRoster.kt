package host.vorv.tools

import host.vorv.AgentViewModel
import host.vorv.watchdog.PatchRegistry

/**
 * Which tools Vorv has.
 *
 * ONE JOB: decide the roster. Each tool does its own job; this file only builds
 * them, in the order the model is offered them.
 *
 * It lives outside `AgentViewModel` (0.9.37) so that adding or removing a tool
 * is a change to a file about tools, not to a 2,000-line file about chat,
 * settings and tasks. The ViewModel still owns the things a tool needs (the
 * database, the key store, the Linux system) and hands them over through the
 * extension receiver.
 *
 * Every patchable tool is built through [AgentViewModel.patched], i.e. through
 * [PatchRegistry], so an approved patch of that class can actually run.
 * Constructing one directly makes "patch applied" true on disk and false at
 * runtime. Non-patchable tools are constructed directly on purpose — the healer
 * refuses to generate a patch for a class with no dispatch point.
 */
internal fun AgentViewModel.buildToolRoster(): List<Tool> = listOf(
    patched("host.vorv.tools.MemoryWriteTool", db) { MemoryWriteTool(db) },
    patched("host.vorv.tools.MemoryRecallTool", db) { MemoryRecallTool(db) },
    MemoryWebTool(db.knowledgeGraphDao()),
    patched("host.vorv.tools.JsExecTool") { JsExecTool() },
    ComputerTool(appContext),
    WebTestTool(appContext),
    BuildAppTool(appContext),
    LinuxRunTool(linuxSystem),
    BrainTool(appContext),
    NativeTestTool(appContext),
    patched("host.vorv.tools.FileReadTool", appContext) { FileReadTool(appContext) },
    patched("host.vorv.tools.FileWriteTool", appContext) { FileWriteTool(appContext) },
    patched("host.vorv.tools.FileListTool", appContext) { FileListTool(appContext) },
    patched("host.vorv.tools.CrashLogsTool", appContext) { CrashLogsTool(appContext) },
    patched("host.vorv.tools.PythonExecTool", appContext) { PythonExecTool(appContext) },
    patched("host.vorv.tools.WebSearchTool", secrets) { WebSearchTool(secrets) },
    patched("host.vorv.tools.WebFetchTool") { WebFetchTool() },
    patched("host.vorv.tools.AppIntentTool", appContext) { AppIntentTool(appContext) },
    patched("host.vorv.tools.ShellTool", linuxSystem, appContext) { ShellTool(linuxSystem, appContext) },
    patched("host.vorv.tools.NotifyTool", appContext) { NotifyTool(appContext) },
    patched("host.vorv.tools.ScheduleTool", appContext, reminderRegistry) {
        ScheduleTool(appContext, reminderRegistry)
    },
    patched("host.vorv.tools.ScheduleCancelTool", appContext, reminderRegistry) {
        ScheduleCancelTool(appContext, reminderRegistry)
    },
    patched("host.vorv.tools.BrowserAutomateTool", appContext) { BrowserAutomateTool(appContext) },
    patched("host.vorv.tools.SelfFixTool", appContext, prefs) { SelfFixTool(appContext, prefs) },
    patched("host.vorv.tools.SelfUpdateTool", appContext, secrets) { SelfUpdateTool(appContext, secrets) },
)
