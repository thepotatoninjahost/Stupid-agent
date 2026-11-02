# Changelog — v0.6.6 "Audit Remediation Round 2"

This release fixes ALL 27 findings from the second comprehensive security &
code-quality audit (report VRA-2026-1102: 2 critical, 5 high, 10 medium,
10 low). Every fix below is in code; the Python sandbox self-test grew
direct-attack regression cases for C-1 and runs in CI as before.

Severity legend: C = Critical, H = High, M = Medium, L = Low.

---

## Critical

### C-1 — python_exec sandbox was escapable (direct attribute syntax + module leaks)
**Was:** the dunder guard only wrapped the `getattr()` BUILTIN; `x.__class__` / `f.__globals__` attribute syntax never routed through it. `uuid` in the import whitelist leaked `os`/`sys` via `uuid.uuid4.__globals__`; `sys.modules` was readable, the whitelist self-modifiable, threads spawnable untraced, the meta_path finder removable. All six PoC escapes ran with `ok=True`.
**Now:** `vorv_runner.py` rebuilt around an AST-level attribute guard:
- user code is PARSED AND REWRITTEN — every attribute read/write/delete routes through guarded helpers; every `from X import y` too (star and relative imports refused);
- ALL dunder attribute names are blocked statically in ANY syntax position, plus reflection routes invisible to Python-level guards (`str.format`/`format_map`, frame/coroutine internals);
- any attribute whose VALUE is a module outside the whitelist is refused (kills `re.enum` → `enum.sys` chains);
- module objects are read-only (no poisoning module state across runs);
- `uuid`, `random`, `statistics` removed from the whitelist (they import os/sys transitively); safe runner-implemented `uuid4()`, `randint()`, `random_float()` builtins provided instead;
- the exposed `getattr`/`setattr`/`delattr` ARE the guarded functions — shadowing them with each other gains nothing.
**Verified:** the original 6-step PoC now refuses every step (`ok=False`); 90+ self-test assertions pass including new direct-syntax, format-string, module-hop, shadowing and poisoning regression cases.
**Files:** `app/src/main/python/vorv_runner.py`, `app/src/main/python/test_vorv_sandbox.py`, `app/src/main/java/host/vorv/tools/PythonExecTool.kt`

### C-2 — HotPatcher loaded unsigned .dex (and none at all when the manifest entry was missing)
**Was:** every boot, `loadPendingPatches()` loaded each .dex in `filesDir/hotpatch/` listed in `applied.json`, verifying only a SHA-256 from a manifest in the SAME directory — and when the manifest had NO entry for a patch, it loaded it UNVERIFIED. `fetchManifest` accepted any URL. HealingAgent compiled untrusted LLM output into .dex and registered it into this path.
**Now:** fail-closed by design:
- NO loading at boot at all — the `loadPendingPatches` call is removed from `VorvApplication.onCreate`;
- the hardened loader (kept for the day signed patching is enabled) refuses AND DELETES any dex lacking a manifest entry, sha256, or a valid **Ed25519 signature over the dex bytes verified against a public key compiled into the app** (API 33+); with no key pinned it refuses everything;
- `fetchManifest` accepts only HTTPS on an allow-listed host (empty allow-list by default);
- `markPendingApply` is gone — nothing feeds the pipeline;
- the false "patches are signed (SHA-256 verified)" doc claim is corrected (a hash is not a signature), and the never-implemented class-override claim/pendingLoaders list is removed.
**Files:** `watchdog/HotPatcher.kt`, `VorvApplication.kt`, `healer/HealingAgent.kt`

---

## High

### H-1 — watchdog crash detection was structurally dead
**Was:** `VorvApplication.onCreate` ran in BOTH processes; the watchdog process wrote its own "alive" heartbeats (so the heartbeat never went stale), opened the DB, built a ModelRepository, and even `startService`d the watchdog from inside the watchdog. The documented bindService-based binder-death signal did not exist anywhere.
**Now:** a process guard in `VorvApplication` (API-28+ `getProcessName`) gives the `:watchdog` process a minimal init (service only); heartbeats are written exclusively by the main process; **MainActivity now binds to WatchdogService** so binder death — verified against `isMainProcessRunning()` — is the primary crash signal, with the (now meaningful) stale heartbeat as fallback; duplicate crash handling is guarded.
**Files:** `VorvApplication.kt`, `watchdog/WatchdogService.kt`, `MainActivity.kt`

### H-2 — shell executed arbitrary bash with no user consent
**Was:** `ShellTool` dispatched any command to Termux after setup checks only — a weaker bar than `app_intent`'s confirmation for merely opening a URL.
**Now:** every shell invocation goes through the same consent bridge (`ctx.confirm`) with the command text (first 8 lines + overflow note) in the dialog; fail-closed when the bridge is absent; denials reported to the model as honest failures. Timeout raised to cover dialog + execution.
**File:** `tools/SystemTools.kt`

### H-3 — browser_automate validated only the initial URL
**Was:** redirects and JS navigation bypassed the private-network guard entirely — the WebView followed a public URL's 302 to `192.168.1.1` and the agent could read the page back.
**Now:** a `GuardWebClient` installed for the WebView's LIFETIME (the old `loadPage` used to replace the client on every load, which would have thrown the guard away) host-checks EVERY navigation in `shouldOverrideUrlLoading` AND `doUpdateVisitedHistory`; blocked navigations stop the load and are reported honestly in the tool output.
**File:** `tools/BrowserAutomateTool.kt`

### H-4 — the self-heal compile pipeline could never work on a stock device
**Was:** compileToDex passed `/data/data/host.vorv/...` paths to Termux (different UID — can't read script/source/classpath, can't write outputs); the `APP_CLASSES_JAR` classpath entry never existed; every compile timed out after 90s × 3 retries; the output round-trip path was unreachable under scoped storage.
**Now:** the on-device compile step is GONE. `HealingAgent` is a diagnostics-only bundle generator: crash identification, bundled-source lookup, an on-device LLM analysis (unverified suggestion, clearly labeled), all written to `healer/diag_<ts>/`. It runs in the restarted MAIN process on explicit user request ("Analyze crash" in the crash dialog), never compiles, never patches. Dead references deleted.
**Files:** `healer/HealingAgent.kt`, `watchdog/WatchdogService.kt`, `MainActivity.kt`

### H-5 — the bundled source tree (assets/source) was stale
**Was:** two files had drifted from the compiled tree — the healer would prompt against source that no longer matched the running binary.
**Now:** a Gradle `Sync` task (`syncSourceMirror`) mirrors `src/main/java` into `src/main/assets/source` before every build, making drift structurally impossible; the mirror is regenerated in this release.
**Files:** `app/build.gradle.kts`, `app/src/main/assets/source/**`

---

## Medium

### M-1 — DNS-rebinding TOCTOU in browser_automate
All GET traffic (top frame + subresources) is now proxied through OkHttp wired to `NetworkGuard.PublicDns`, which re-resolves and validates every hop INCLUDING redirects at connect time. Non-GET requests cannot be proxied (Android does not expose request bodies to `shouldInterceptRequest`) and are covered by the navigation-level checks; the residual is documented in SECURITY.md. (`tools/BrowserAutomateTool.kt`)

### M-2 — local-mode conversations grew without bound against a 4096-token context
The kernel now estimates the prompt per iteration against `Llm.contextTokenBudget` (local: 1800 tokens — honest arithmetic for nCtx 4096 minus the 2048-token generation reserve; cloud: 100k) and compacts older tool results into short digests (full outputs remain in the Tasks tab) in protocol-safe units. When compaction can't free enough space it stops with an honest "context window exhausted at iteration N" instead of silent truncation; engine errors on over-budget prompts are reported as exhaustion. (`kernel/CognitiveKernel.kt`, `llm/Llm.kt`, `llm/LocalLlm.kt`)

### M-3 — Rhino pinned to the CVE-2025-66453 version
Bumped `org.mozilla:rhino` 1.7.15 → **1.7.15.1**. (`gradle/libs.versions.toml`)

### M-4 — "Clear ALL data" left things behind
It now also cancels every scheduled reminder (WorkManager unique work + registry), deletes `cacheDir/tool_outputs`, purges `crash_logs/`, `watchdog/`, `healer/` diagnostics and leftover `hotpatch/` files. The dialog says the API key and settings are kept. (`AgentViewModel.kt`, `tools/ScheduleTool.kt`)

### M-5 — file_read buffered the entire file before its 50 KB cap
Now stream-reads at most 200 KB of bytes (cap × 4 UTF-8 safety) before decoding and truncating to 50 000 chars. (`tools/FileTools.kt`)

### M-6 — web_fetch decoded UTF-8 per 16 KB chunk (mojibake at boundaries)
Bytes are now accumulated under the cap and decoded ONCE. (`tools/WebTools.kt`)

### M-7 — vorv.db rode Android cloud backup while SECURITY.md said "local"
`allowBackup=false` + empty backup/data-extraction rules: NO app data (prompts, tool logs, memories) leaves the device via Auto Backup or device transfer. SECURITY.md's data section now states this explicitly. (`AndroidManifest.xml`, `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`, `SECURITY.md`)

### M-8 — self-heal ran runBlocking on the watchdog's heartbeat thread
The watchdog never loads the LLM anymore — crash analysis moved to the restarted main process behind an explicit user action (see H-4). (`watchdog/WatchdogService.kt`)

### M-9 — schedule: enqueue and registry were not atomic
Registration now happens FIRST (where the 20-entry cap throws), the WorkManager enqueue follows, and a failed enqueue rolls the registry entry back. `schedule_cancel` also cancels the work of EXPIRED entries, which used to be silently dropped from the registry while their (doze-delayed) reminder could still fire. (`tools/ScheduleTool.kt`)

### M-10 — crash-report filenames collided at one-second resolution
Filenames now carry milliseconds + pid (`crash_<ts>_pid<N>.txt`). (`CrashReportWorker.kt`)

---

## Low

### L-1 — Gradle wrapper scripts and jar not committed
`gradlew`, `gradlew.bat` and `gradle-wrapper.jar` are committed (official Gradle 8.9 artifacts), and `distributionSha256Sum` is pinned in `gradle-wrapper.properties` so a tampered distribution is refused. Fresh clones build per README.

### L-2 — DEVELOPMENT.md instructed committing Room schemas while exportSchema = false
Docs now describe the actual decision: exportSchema deliberately off (flaky KSP export path in clean CI), `MigrationTest` builds v1 by DDL and runs `MIGRATION_1_2` directly.

### L-3 — CI published releases tagged v0.6.0-<sha> while the app was 0.6.5
The publish job now derives the tag from `versionName` in `app/build.gradle.kts` (with a hard error if unreadable). (`.github/workflows/build.yml`)

### L-4 — NetworkGuard missed carrier-grade NAT space
`100.64.0.0/10` is blocked (with tests for both the blocked span and the public 100.63/100.128 edges). (`tools/NetworkGuard.kt`)

### L-5 — JsExecTool shared one mutable WatchdogFactory across evaluations
A fresh factory per evaluation; no cross-run deadline/abort interference. (`tools/JsExecTool.kt`)

### L-6 — file_write colliding with a directory created "(1)" duplicates
Colliding with an existing DIRECTORY is now an explicit error telling the model to pick another name. (`tools/FileTools.kt`)

### L-7 — BootTracer misclassified crashes that happened before start()
`BootTracer.start()` (separator + BOOT_STARTED) now runs FIRST in `VorvApplication.onCreate`, so no breadcrumb can ever land in the previous boot's section. (`VorvApplication.kt`)

### L-8 — parseLiteral did a DNS lookup for non-literal hosts
Literal detection is now syntactic (strict dotted-quad / hex-colon shape); `InetAddress.getByName` is only called for genuine literals. Comment corrected. (`tools/NetworkGuard.kt`)

### L-9 — AgentService declared foregroundServiceType=dataSync for a compute workload
Changed to `specialUse` with the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property documenting the LLM-inference purpose; `startForeground` passes the explicit type on API 34+. (`AndroidManifest.xml`, `service/AgentService.kt`)

### L-10 — notification IDs derived from a truncated timestamp could collide
A shared `NotificationIds` counter (AtomicInteger, random seed, monotonic) is used by `notify` and reminder notifications. (`tools/SystemTools.kt`, `tools/ReminderWorker.kt`)

---

## Also in this release

- Version bumped to **0.6.6 (versionCode 15)**.
- SECURITY.md rewritten for the new sandbox architecture, the shell consent gate, the browser guard (incl. the residual non-GET rebinding window), the backup posture (nothing leaves the device), and the de-scoped hot-patch feature.
- README safety table and setup copy updated to match the new behavior.

---

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
Removed everywhere. Room bumped to **version 2** with a real `Migration(1,2)` (`ALTER TABLE tool_calls ADD COLUMN outputFilePath TEXT`). `MigrationTest` builds a v1 database by DDL, runs the real migration object and verifies the column exists and data survives. (Schema JSON export stays off: it is tooling sugar, and the KSP arg-based export path hit a Room 2.6.1 "Empty schema file" failure on the release variant in clean CI — the migration does not depend on it.)
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
