# Changelog — v0.6.0 "Full Remediation"

This release implements every finding from the forensic audit (44 findings:
6 critical, 10 high, 12 medium, 10 low, 6 informational). No triage, no
deferrals — everything below is fixed in code, and the fixes are enforced by
CI (unit tests + the Python sandbox self-test run on every push).

Severity legend: C = Critical, H = High, M = Medium, L = Low, I = Informational.
("S-xx" = architectural hardening from audit §10.2/§11.3 not in the numbered list.)

---

## Critical

### C-1 — js_exec was not a sandbox
**Was:** `Context.initStandardObjects()` with no `ClassShutter` — LLM JavaScript could call `Packages.java.lang.Runtime.getRuntime().exec(...)`, read the app database, exfiltrate the API key, or `System.exit(0)`.
**Now:** `JsExecTool` runs on a custom `ContextFactory` with:
- `ClassShutter` that allows only Rhino internals + immutable `java.lang` types (String/Boolean/Number/Math/…);
- all LiveConnect globals (`Packages`, `java`, `javax`, `org`, `com`, `net`, `io`, `android`, `androidx`, `host`, `jdk`, `sun`, …) and `importClass`/`importPackage`/`JavaAdapter`/`JavaImporter` deleted from the scope;
- the scope **sealed** (`ScriptableObject.sealObject()`).
**Tests:** `JsExecToolTest` — 8 attack cases (Runtime, Packages, File read, System.exit, importClass/Package, JavaAdapter, reflection) all blocked; legit ES6 + console + timeout verified.
**File:** `app/src/main/java/host/vorv/tools/JsExecTool.kt`

### C-2 — python_exec was not a sandbox, and its description lied
**Was:** `exec(code, {"__builtins__": __builtins__})` — full builtins, full filesystem, raw sockets. The description claimed "cannot access files outside the workspace" and "requests available" — both false.
**Now:** `vorv_runner.py` rebuilt as a four-layer sandbox:
1. `sys.addaudithook` blocking file/process/socket/ctypes/frame events (C-level, unremovable);
2. restricted `__builtins__` (no `open`/`eval`/`exec`/`compile`/`__import__`; dunder-guarded `getattr`/`setattr`/`hasattr`);
3. import whitelist of pre-imported pure-computation stdlib modules (`os`/`sys`/`socket`/`subprocess`/`ctypes`/`pickle`/`time`/`threading` → `ImportError`);
4. `sys.settrace` time-budget watchdog.
Kotlin side serializes runs (stdout is process-global) and enforces a hard join timeout for C-level stalls. The tool description is now honest about capabilities and limits.
**Tests:** `test_vorv_sandbox.py` — 55 assertions (file I/O, network, process, escapes, whitelist, timeouts, honest errors), runs in CI with plain python3.
**Files:** `app/src/main/python/vorv_runner.py`, `app/src/main/python/test_vorv_sandbox.py`, `app/src/main/java/host/vorv/tools/PythonExecTool.kt`

### C-3 — shell tool fabricated its output and lied to the LLM
**Was:** Termux RUN_COMMAND is fire-and-forget; the tool returned a placeholder string with `ok=true`, which the kernel logged and fed to the LLM as the command's real result.
**Now:** `ShellTool` does a real workspace round-trip:
- maps the SAF workspace tree URI to its `/storage/emulated/0/...` path (primary storage only, else honest error);
- creates `.vorv-shell/` in the workspace;
- dispatches `bash -c "printf '%s' <b64> | base64 -d | bash > out 2> err; echo $? > code"` — the command travels base64-encoded (no quoting bugs), outputs land where both Termux and Vorv can reach them;
- polls for the exit-code file via SAF (250 ms interval, 1–120 s budget);
- returns the REAL stdout (100 KB cap), stderr (20 KB cap) and exit code; cleans up.
- If capture is impossible, returns `ok=false` explicitly telling the model the result is UNKNOWN and listing the exact setup steps (`termux-setup-storage`, `allow-external-apps=true`, workspace on primary storage).
**File:** `app/src/main/java/host/vorv/tools/SystemTools.kt`

### C-4 — browser_automate WebView allowed file:// reads and arbitrary JS
**Was:** `javaScriptEnabled` + default `allowFileAccess`/`allowContentAccess`, no scheme filter, hand-rolled JS string escaping.
**Now:** `allowFileAccess`, `allowContentAccess`, `allowFileAccessFromFileURLs`, `allowUniversalAccessFromFileURLs` all **false**; `MIXED_CONTENT_NEVER_ALLOW`; http/https scheme allowlist; `NetworkGuard.checkHostAllowed()` host pre-check (no localhost/LAN/metadata); JS built with Gson `toJson` interpolation; `destroy()` releases the WebView from `ViewModel.onCleared`.
**File:** `app/src/main/java/host/vorv/tools/BrowserAutomateTool.kt`

### C-5 — API key stored unencrypted in DataStore
**Was:** plaintext protobuf at `files/datastore/vorv_prefs.preferences_pb`, readable by the unsandboxed exec tools (C-1/C-2) or `adb`.
**Now:** `SecretsStore` (EncryptedSharedPreferences, AES-256-GCM, Android-Keystore master key — hardware-backed on the S25). The old plaintext DataStore key is migrated once on first launch and erased. Non-secret settings stay in the renamed `VorvPrefs`.
**Files:** `app/src/main/java/host/vorv/data/SecretsStore.kt`, `VorvPrefs.kt`, `VorvApplication.kt`

### C-6 — README materially misrepresented the app
**Was:** claimed local LLM was "a stub", and shell/python/web/browser/notify/schedule were "pending" — all were shipped and wired.
**Now:** README fully rewritten with an honest status block, accurate architecture diagram, a per-tool safety table, and links to the new `SECURITY.md` (full threat model with residual limits), `DEVELOPMENT.md` (env setup, release-signing secrets, adding-a-tool guide), `CONTRIBUTING.md` and `LICENSE` (MIT, previously missing). In-app copy (`ToolsScreen`, `SettingsScreen`) updated to match reality.
**Files:** `README.md`, `SECURITY.md`, `DEVELOPMENT.md`, `CONTRIBUTING.md`, `LICENSE`

---

## High

### H-1 — dial premium numbers / no confirmation for intents
`AppIntentTool`: every action (view/share/dial/open_app) now goes through the user-consent bridge (`ToolContext.confirm`) — an Allow/Deny dialog showing exactly what will happen; when the bridge is absent the tool **fails closed**. `DialGuard` refuses premium-rate patterns (US 1-900/1-976, UK 09xx/118, IT 89x/090x, AU 190x, HK 166x) and malformed numbers (length 7–15 digits, charset check). `view` restricted to http/https. Denials are reported to the model as failures.
**Files:** `tools/SystemTools.kt`, `tools/Tool.kt`, `AgentViewModel.kt`, `ui/VorvApp.kt` — tests: `DialGuardTest`, `CognitiveKernelTest`

### H-2 — POST_NOTIFICATIONS never requested
`MainActivity` requests it once per install via the registered permission launcher; `NotifyTool` checks it before posting and returns an honest error (with the exact Settings path) when missing.
**Files:** `MainActivity.kt`, `tools/SystemTools.kt`

### H-3 — cross-thread Rhino watchdog (undefined behavior)
Replaced with Rhino's documented interruption mechanism: a `ContextFactory` subclass overriding `observeInstructionCount` checks a wall-clock deadline every 10 000 interpreted instructions **on the evaluating thread**; `cx.instructionObserverThreshold = 10_000` enables it.
**Files:** `tools/JsExecTool.kt` — test: `JsExecToolTest` (infinite loop stopped)

### H-4 — localLlm released out from under in-flight inference
New `ModelRepository` owns the engine lifecycle: one `Mutex` serializes every transition (`activeEngine`/`swapModel`/`deleteModel`/`shutdown`); swaps are refused while a task is running (`busy`); the engine is (re)built lazily under the lock; `onTrimMemory` releases it when idle; `ViewModel.onCleared` shuts it down. The Settings UI disables model controls while busy (UI mirrors the rule, repository enforces it).
**File:** `llm/ModelRepository.kt`, `AgentViewModel.kt`, `ui/screen/SettingsScreen.kt`

### H-5 — fallbackToDestructiveMigration would wipe user data
Removed everywhere. Room bumped to **version 2** with a real `Migration(1,2)` (`ALTER TABLE tool_calls ADD COLUMN outputFilePath TEXT`), `exportSchema = true` + KSP `room.schemaLocation`. `MigrationTest` builds a v1 database and verifies the migration adds the column and preserves data.
**Files:** `data/VorvDatabase.kt`, `VorvApplication.kt`, `app/build.gradle.kts` — test: `MigrationTest`

### H-6 — zero automated tests
10 test files added (~120 assertions) + CI `gradle test` job. To make the kernel testable, persistence moved behind `KernelEventSink` (production: `RoomKernelEventSink`; tests: fakes) and the LLM-output parser, citation stripper, dial guard, schedule validator, network guard and tool-argument validator were extracted into pure objects. Covered: parser (incl. truncated-JSON repair), kernel loop (validation, rate limits, timeouts, citations, consent pass-through, stuck-at-cap, full-output persistence), Rhino attacks, WebFetch (MockWebServer), network guard, migration.
**Files:** `app/src/test/...` (10 files)

### H-7 — CI published a debuggable debug APK as the Release
Release build now `isMinifyEnabled = true` + `isShrinkResources = true` with complete ProGuard rules (Rhino, GenieX, Chaquopy, OkHttp, Tink, Room, our classes). Signing is env-var-driven (`RELEASE_KEYSTORE_*`, decoded from GitHub secrets) — no key material in the repo. The **committed vorv-debug.keystore is deleted** (`.gitignore` already covered it). The publish job runs only on main **and** only when the keystore secret exists, and ships the signed release APK; otherwise it warns visibly and ships nothing installable.
**Files:** `app/build.gradle.kts`, `proguard-rules.pro`, `.github/workflows/build.yml`, deleted `vorv-debug.keystore`

### H-8 — web_fetch had no response size cap
Streams at most 2 MB (16 KB chunks), refuses when `Content-Length` exceeds the cap, and the extracted text is capped at 8000 chars for the prompt.
**File:** `tools/WebTools.kt` — tests: `WebFetchToolTest`

### H-9 — memory_write LIKE-race could update the wrong row
The UUID is generated up-front and `citationRef` is set before insert — the entire searchLike-and-update dance is gone. No lookup, no race.
**File:** `tools/MemoryTools.kt`

### H-10 — citation enforcement only checked iteration 1
Tool-captured and inline citations are now tracked in separate lists; the answer is flagged `[uncited]` when **both** are empty, at any iteration. Extracted into `CitationStripper` (tested).
**Files:** `kernel/CitationStripper.kt`, `kernel/CognitiveKernel.kt`

---

## Medium

| ID | Fix |
|---|---|
| M-1 | `LocalLlm maxTokens` 768 → 2048; `ToolCallParser.repairJson` balances braces/strings so truncated tool calls recover instead of degrading to raw text (tested) |
| M-2 | `CloudLlm` backoff uses `delay()`, never `Thread.sleep`, inside `withContext(IO)` |
| M-3 | Mode-aware task timeout: 5 min cloud / 15 min local, wrapped around `kernel.run`; timeout fails the task honestly and preserves the partial log |
| M-4 | `CloudLlm` guards empty `choices[]` with an actionable error; null-safe `finish_reason`/`id`/`arguments` |
| M-5 | `ScheduleTool` enqueues **unique** work names (`vorv_reminder_<task>_<iter>`, REPLACE policy), registry capped at 20 pending; new `schedule_cancel` tool cancels by title or all |
| M-6 | Minutes validated by `ScheduleValidator`: 1–10 080 (7 days), finite (tested) |
| M-7 | Outputs > 2000 chars are persisted to `cacheDir/tool_outputs/...` and the `tool_calls` row (new `outputFilePath` column, DB v2) records the path; Tasks screen expands to the verifiable log with a "View full output" dialog |
| M-8 | `bootstrapSkills` checks **each** tool individually, so upgrades register new tools |
| M-9 | `SkillDao.ensureExists` (INSERT OR IGNORE) runs before every skill stat update — no more silent no-ops |
| M-10 | `teach()` now inserts a real `ToolCall` row (+ episode), keeping the verifiable log complete |
| M-11 | `LocalLlm.release()` logs failures with stack trace instead of swallowing |
| M-12 | `AgentService` + `ReminderWorker` notifications have `setContentIntent` (tap opens the app), IMMUTABLE PendingIntents |

---

## Low & Informational

| ID | Fix |
|---|---|
| L-1 | `KeyStore` split into `SecretsStore` (encrypted secrets) + `VorvPrefs` (non-secret settings) |
| L-2, L-3 | open_app/share require user confirmation; share text is previewed in the dialog |
| L-4 | HTML→text via jsoup with block-marker preservation (regex parser deleted) |
| L-5 | Retrofit + converter removed from catalog and build (never used) |
| L-6 | okhttp-logging removed (was declared, never wired — would have risked header logging) |
| L-7 | `ViewModel.onCleared` releases the GenieX engine + destroys the headless WebView; `VorvApplication.onTrimMemory` releases the engine when idle |
| L-8 | Full ProGuard keep rules for GenieX, Chaquopy, Tink, Rhino, Room, tools/kernel/llm (now that R8 is on) + line-number attributes |
| L-9 | Release signing config env-driven; wired into CI with secrets |
| L-10 | `stopForeground(Service.STOP_FOREGROUND_REMOVE)` qualified |
| I-1 | All CI actions pinned to full commit SHAs (checkout v4.4.0, setup-java v4.9.1, setup-python v5.6.0, setup-gradle v3.5.0, upload-artifact v4.6.2, download-artifact v4.3.0, gh-release v2.6.2); job-level minimal permissions; concurrency cancellation |
| I-2 | `parallel=false` kept (upstream Chaquopy+KSP lock) — documented in DEVELOPMENT.md as a known quirk with a tracking note |
| I-3 | `WebSearchTool` rewritten against the **documented** Z.ai Web Search API (`POST /api/paas/v4/web_search`, `search_query`/`count`, `search_result[]`) — the old `/functions` endpoint does not exist in the public API |
| I-4 | User-Agent is versioned from `BuildConfig.VERSION_NAME` |
| I-5 | Legacy store names kept deliberately (renaming breaks existing installs); convention documented |
| I-6 | ToolsScreen copy is honest (sandbox mechanisms, shell requirements, confirmation gates) |

---

## Architectural hardening (audit §10.2 / §11.3)

- **`ToolCallValidator`** — LLM arguments validated against each tool's schema before dispatch (required fields, string/integer/number/boolean types). Tested.
- **Per-tool wall-clock timeouts** — `Tool.timeoutMs` enforced by the kernel via `withTimeoutOrNull`; honest timeout errors are logged and fed back.
- **Per-task rate limits** — `Tool.maxCallsPerTask` enforced by the kernel (e.g. web_fetch 8/task, shell 10/task); the model gets an explicit "stop repeating this tool" message.
- **`NetworkGuard`** — OkHttp `Dns` (`PublicDns`) + host/address predicates blocking loopback, link-local, site-local, unique-local, any-local, multicast, `*.local`, `*.internal`, metadata endpoints. Applied to web_fetch, web_search, CloudLlm, and pre-checked in browser_automate. Tested.
- **Consent bridge** — `ToolContext.confirm` + `PendingConfirmation` state + AlertDialog in `VorvApp`; serialized (one dialog at a time), 60 s auto-deny, denial reported to the model as failure.
- **Prompt-injection note** — system prompt marks tool outputs/file contents/web pages as untrusted data, never instructions (rule 7), and documents the confirmation behavior (rule 8).
- **Data retention** — episodes/completed tasks/tool calls pruned after 30 days at boot; "Clear ALL data" button (confirmation-gated) wipes every table.
- **file_write cap** — 1 MB per call, honest error beyond it.
- **Manifest hygiene** — removed unused `RECEIVE_BOOT_COMPLETED` and `WAKE_LOCK` declarations; kept `com.termux.permission.RUN_COMMAND` (required to be grantable for the shell tool).

---

## Not fixed (with reasons)

- **Rhino upgrade** (1.7.15 → 1.9.x) — deliberately deferred: the sandbox fix does not depend on it, and an engine swap mid-remediation would invalidate the attack-test baseline. Dependabot will propose the bump; run `JsExecToolTest` against it.
- **Subprocess isolation for python_exec** — the genuinely complete fix for the C-extension-stall residual limit. Android offers no practical unprivileged process sandbox with IPC this cheap; the layered sandbox + honest documentation is the current right answer. Tracked in SECURITY.md as a known residual limit.
