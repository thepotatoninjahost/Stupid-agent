package host.vorv

import host.vorv.data.entity.AgentTask
import host.vorv.data.entity.Episode
import host.vorv.data.entity.Skill
import host.vorv.data.entity.ToolCall
import host.vorv.kernel.TraceStep
import host.vorv.llm.BrainDoctor
import kotlinx.coroutines.CompletableDeferred

/**
 * One moment of Vorv, as the screens read it.
 *
 * ONE JOB: describe the app in immutable data — the chat, the tool calls, the
 * Setup facts, the two dialogs waiting for an answer. Nothing here opens a
 * database, calls a model or runs a tool; [AgentViewModel] fills these in.
 *
 * It lives in its own file (0.9.37) because these were 147 lines of a 2,417-line
 * ViewModel and not one of them needed the ViewModel to be understood.
 */
data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String,                // "user" | "assistant"
    val content: String,
    val citations: List<host.vorv.tools.Citation> = emptyList(),
    val ts: Long = System.currentTimeMillis(),
)

/** A pending user-consent dialog raised by a tool (audit H-1/L-2/L-3). */
data class PendingConfirmation(
    val id: String,
    val message: String,
    val response: CompletableDeferred<Boolean>,
    /**
     * v0.6.15: true only for SHELL consents — the dialog then offers the
     * third button "Allow all this session" (SessionGrants). Set by
     * [AgentViewModel.requestConfirmation] from ShellTool.CONSENT_PREFIX,
     * the single source of truth — no other tool's dialog ever gets it.
     */
    val offerSessionGrant: Boolean = false,
)

/**
 * A pending ANDROID runtime-permission dialog (v0.6.14). The UI observes
 * this, calls Activity.requestPermissions(), and reports back through
 * [AgentViewModel.respondPermissionRequest]. For any runtime grant a tool
 * needs — the standard one-tap dialog, never a Settings walk.
 */
data class PendingPermissionRequest(
    val id: String,
    val permission: String,
    val response: CompletableDeferred<Boolean>,
)

/** Full-output viewer state (audit M-7). */
data class FullOutputView(
    val title: String,
    val content: String,
)

data class AgentState(
    val screen: String = "chat",     // "chat" | "tasks" | "memory" | "tools" | "files" | "settings"
    val busy: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val liveTrace: List<TraceStep> = emptyList(),
    val memoryCount: Int = 0,
    /** Step 9 — what the Brain screen shows: champion, generations, runs. */
    val brain: host.vorv.learn.BrainReport.View? = null,
    /** A training run is going right now (read back from the run log). */
    val brainBusy: Boolean = false,
    /** The last thing the Brain screen did, in the owner's words. */
    val brainNote: String = "",
    /** Step 7 — what the learning ledger actually holds, read from the table. */
    val ledgerRows: Int = 0,
    val ledgerExamples: Int = 0,
    val ledgerValRows: Int = 0,
    /** What the last export did, in the owner's words. Never a silent success. */
    val exportNote: String = "",
    val skillCount: Int = 0,
    /** Number of tools actually registered — the Setup text quotes this, not a
     *  remembered number (F-10: it said 17 while the registry held 24). */
    val toolCount: Int = 0,
    val recentTasks: List<AgentTask> = emptyList(),
    val skills: List<Skill> = emptyList(),
    val episodes: List<Episode> = emptyList(),
    val llmMode: String = "cloud",       // "local" | "cloud"
    val hasApiKey: Boolean = false,
    /** 0.9.37 — a GitHub key is saved, so Vorv can push a change to its own code. */
    val hasGitHubToken: Boolean = false,
    /** 0.9.13 — a different provider's address; blank means the owner's default. */
    val cloudBaseUrl: String = "",
    /** 0.9.13 — that provider's model name; required when an address is set. */
    val cloudCustomModel: String = "",
    /** v0.7.0 — cloud→offline fallback armed (needs a downloaded local model). */
    val autoFallback: Boolean = true,
    /** v0.7.0 — key+model probe in flight (Setup screen). */
    val keyTesting: Boolean = false,
    /** v0.7.0 — verbatim result of the last key/model probe (Setup screen). */
    val keyTestResult: String? = null,
    val localModelReady: Boolean = false,
    val downloadingModel: Boolean = false,
    val downloadProgress: Int = 0,
    val selectedModelId: String = "omnicoder9b",
    val downloadedModelIds: Set<String> = emptySet(),
    val workspaceUri: String = "",
    /** Which provider the cloud brain uses ("" = [ProviderRegistry.DEFAULT_ID]). */
    val providerId: String = host.vorv.llm.provider.ProviderRegistry.DEFAULT_ID,
    /** 0.9.18 — model ids the provider itself listed; empty until refreshed. */
    val catalogIds: List<String> = emptyList(),
    /** What the refresh left out and why (e.g. GLM ids the owner does not use). */
    val catalogNote: String? = null,
    val catalogLoading: Boolean = false,
    /** 0.9.18 — why the model list could not be read. Shown, never swallowed. */
    val catalogError: String? = null,
    /** 0.9.18 — the encrypted key store itself failed; distinct from "no key". */
    val keyReadError: String? = null,
    /** 0.9.18 — the same facts the send gate uses, shown in Setup → Brain health. */
    val brainChecks: List<BrainDoctor.Check> = emptyList(),
    /** 0.9.18 — the Copy-button text for Brain health. */
    val brainReport: String = "",
    val currentLlmLabel: String = host.vorv.llm.provider.BrainTarget.DEFAULT_MODEL + " (cloud)",
    /**
     * Setup-screen error message — when a download or model operation fails,
     * the error is surfaced here so the SettingsScreen can display it inline
     * below the affected model card. NEVER added to the chat message list.
     * Cleared on next successful operation or explicit user dismissal.
     */
    val setupError: String? = null,
    /** Active consent dialog (one at a time; concurrent requests queue via the mutex). */
    val pendingConfirmation: PendingConfirmation? = null,
    /** Active Android runtime-permission request (v0.6.14; one at a time). */
    val pendingPermissionRequest: PendingPermissionRequest? = null,
    /** The app carries the Linux payload (proot + Debian rootfs). */
    val linuxAvailable: Boolean = false,
    /** The guest filesystem has been unpacked and stamped. */
    val linuxProvisioned: Boolean = false,
    /** A Linux-system test is running (Setup tab). */
    val linuxTesting: Boolean = false,
    /** Verbatim result of the last Linux-system test (Setup tab). */
    val linuxTestResult: String? = null,
    /** What the payload check actually looked for, worded for the Setup card. */
    val linuxDiagnostics: String? = null,
    /** The Debian build tools are downloading/unpacking into the guest. */
    val toolchainInstalling: Boolean = false,
    /** One live line: "12/169 packages — 8.4 MB of 203.0 MB — fetching libc6". */
    val toolchainProgress: String? = null,
    /** javac and aapt really exist in the guest right now. */
    val toolchainInstalled: Boolean = false,
    /** Verbatim outcome of the last install attempt, shown as-is. */
    val toolchainResult: String? = null,
    /** The APK carries proot even if Android has not unpacked it (two different facts). */
    val linuxPayloadInApk: Boolean = false,
    /** A real command completed inside the guest this session (knowledge). */
    val shellVerifiedThisSession: Boolean = false,
    /** v0.6.17: the dead-shell circuit breaker is active (live in Setup). */
    val shellBlockedThisSession: Boolean = false,
    /** v0.6.15: the user granted all shell commands for this session. */
    val shellSessionGrant: Boolean = false,
    /** v0.6.15: commands run under the session grant (audit counter). */
    val shellAutoRunCount: Int = 0,
    /** Full-output dialog (audit M-7). */
    val fullOutputView: FullOutputView? = null,
    /** Tasks screen: which task's tool calls are expanded. */
    val expandedTaskId: String? = null,
    /** Tool calls for the expanded task. */
    val toolCallsForTask: List<ToolCall> = emptyList(),
)
