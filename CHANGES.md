# Changelog — v0.6.15 "Stop Guessing, Start Asking"

Third real-world test finding: asked to use Termux, Vorv answered "I
don't have access to Termux" — WITHOUT EVER CALLING the shell tool. The
tool was registered, reachable and one consent dialog away. But its
description opened with "Requires: Termux installed, RUN_COMMAND
permission granted, ..." — four prerequisites that a small local model
reads as a disclaimer of unavailability — and the system prompt carried
live status only for the workspace, never for Termux. The model guessed,
guessed "no", and the honest-error machinery never even fired. Two
fixes: tell the model the LIVE truth, and make repeated consent honest
instead of nagging.

## The model is told the truth, not a guess

- New SessionGrants singleton (process-level, same binary-compat pattern
  as PermissionBridge — zero signature changes, registered patches keep
  working) tracks two things a session can KNOW or CHOOSE:
  - VERIFIED: a real Termux round-trip completed — the Setup probe
    passed, or a shell command's exit-code file actually appeared in the
    shared workspace. Knowledge, not consent.
  - GRANTED: the user chose "Allow all this session" on a shell consent
    dialog. Consent, explicitly batched.
- The kernel's system prompt now ends with a live Termux status line
  (SessionGrants.statusLine()) in one of three wordings: verified +
  granted (run freely, everything is logged and revocable); verified
  (it works; per-command dialogs are normal, not a failure); not yet
  verified (do NOT claim you cannot access Termux — call the tool and
  report its honest error). The prompt already did this for the
  workspace; Termux was the missing row.
- The shell tool's description is rewritten to lead with what to DO:
  "ALWAYS call this tool whenever the user asks for anything involving
  Termux, the shell, or running commands... NEVER claim you cannot
  access Termux without calling this tool first." The prerequisite
  disclaimer moved to where it belongs — the honest errors that fire
  only when something is actually missing.

## Session grants — consent that doesn't train you to click blindly

- Once shell works, every command popped a full dialog; a ten-command
  task became ten dialogs, training the user to hit Allow without
  reading. The shell consent dialog now offers a third answer: "Allow
  all this session". It approves the current command AND records a
  session-scoped grant.
- While the grant holds: no more dialogs, but every command is audited
  — the COMPLETE command goes to the episode log (kind
  shell_session_auto) and a counter the user can see in Setup. The
  grant replaces the dialog, never the record.
- The grant is never the default button, never silent, never persisted
  — it dies with the process. Revoke anytime: Setup → Termux
  integration → REVOKE. Grant and revoke are themselves logged as
  episodes. Shell-only: python_exec, app_intent and every other
  consented tool are untouched.
- Setup's Termux card now shows both live rows: "Shell VERIFIED this
  session — the assistant is told the shell works" and the grant state
  with its run counter.

## Smoke tests — the wiring, not just the units

- New TermuxSmokeTest drives the REAL CognitiveKernel with a scripted
  LLM and pins the system prompt's Termux line in all three session
  states; pins the rewritten description (and that the "Requires:"
  disclaimer opener is gone); and pins the consent mechanics end-to-end:
  the grant skips dialogs but not the workspace/permission gates, the
  audit record carries the complete command, revoke restores dialogs,
  fail-closed survives, and python_exec is unaffected by a shell grant.

# Changelog — v0.6.14 "The Wall Gets a Door"

Second real-world test finding, from a field recording: after v0.6.13
made the model ACT, the very next shell call hit the RUN_COMMAND
permission wall — and the honest error, while true, could only recite a
five-step Android Settings walk. The user's standing rule: never just
show the wall — show the cheapest real way through it. It turns out
Android HAS one: Termux declares com.termux.permission.RUN_COMMAND with
protectionLevel="dangerous", which in Android terms means
RUNTIME-REQUESTABLE. One tap. It was never being offered.

## One-tap grant where the model hits the wall

- New PermissionBridge: when the shell tool (or self_fix) finds the
  permission missing, it now pops Android's OWN system dialog —
  "Allow Vorv to run commands in Termux environment?" — after the
  command consent, so the user always knows what the grant is FOR.
  Grant → the command proceeds. Deny/dismiss/timeout/no-UI → the honest
  manual fallback error, naming every remaining path (unchanged law:
  fail closed).
- Why a process-level bridge and not a ToolContext field: binary
  compatibility with registered patches. Changing ToolContext's or
  KernelApi.run's signature would crash every previously-approved patch
  with NoSuchMethodError. The bridge adds zero signatures; old patches
  keep working exactly as before.
- self_fix now checks Termux readiness BEFORE the expensive pipeline —
  previously a missing permission was discovered 10 minutes into a
  15-minute heal, after the model had already written the patch.
- The shell tool's kernel budget grows 125s → 300s: two serial dialogs
  (consent up to 60s + permission up to 60s) plus the command timeout
  must fit inside the wall clock.

## Setup → Termux integration

- New Setup card with the live state of every gate: Termux installed?
  permission granted? — plus GRANT (the one-tap dialog on demand),
  Android Settings (manual fallback), and RECHECK.
- TEST CONNECTION runs exactly `echo vorv-termux-ok` through the same
  base64-transport round-trip the shell tool uses, then reports the
  REAL result — including the two Termux-side gates Vorv cannot reach
  (allow-external-apps, termux-setup-storage) with the exact commands
  to type, and an honest statement of WHY Vorv can't automate them
  (they live inside Termux's private data directory, blocked from every
  other app by Android design).

## No raw JSON in the chat

- Field finding from the same recording: when a memory lookup returned
  {"ok":true,"output":"No memories matched..."}, the model pasted the
  raw JSON into its reply and the user had no idea what it meant. New
  HARD RULE 10 in the system prompt: tool results are for the model,
  not the user's screen — explain in plain English, and when a tool
  fails, say what failed and what the user can do.

## Version

- `versionCode` 22 → 23, `versionName` 0.6.13 → 0.6.14.

---

# Changelog — v0.6.13 "Act, Don't Advise"

Found in real-world testing: asked to "use termux to install all the
packages you need", the local model (Qwen2.5-Coder-7B) replied with a
bash code block of suggested commands — ADVICE — instead of calling the
shell tool. Nothing ran; the honesty system flagged the answer
"uncited — no tool evidence" (that part worked exactly as designed).
But the agent still wasn't ACTING. Two fixes:

## The nudge: one correction when the model advises instead of acting

- When the model's answer contains runnable commands (a ```bash or
  ```python block) but it made NO tool call and NOTHING has been
  executed yet, the kernel sends it ONE corrective message: if the user
  asked you to PERFORM these actions, call the matching tool now (shell
  / python_exec); if the user only wanted advice, restate the final
  answer without runnable code blocks. Then the loop continues.
- The nudge never executes anything itself. Every execution still goes
  through the consent dialog — unchanged.
- At most ONE nudge per task (no loops burning slow on-device inference),
  and never after a tool already ran (quoting commands in a final
  summary is legitimate). Pinned by six new CognitiveKernelTest cases.
- Each nudge is logged as a "nudge" episode in the task's verifiable
  record.

## The standing rule: act-don't-advise in the system prompt

- New HARD RULE 9 in the kernel's system prompt: when the user asks you
  to DO something on this device, you MUST call a tool to actually do
  it — writing commands in a code block is NOT doing it.

## Version

- `versionCode` 21 → 22, `versionName` 0.6.12 → 0.6.13.

---

# Changelog — v0.6.12 "Show Me Everything, Part 2"

One more truncation was hiding in a place the v0.6.11 sweep missed:
the crash-repair patch dialog. It is now gone. Every approval surface in
the app — all five — now shows the complete, exact content, and the rule
is pinned by tests.

## The crash-repair patch dialog now shows everything

- The "Apply self-repair patch?" dialog (shown when you tap Self-repair
  after a crash) used to cut the SHA-256 to a 16-character prefix and the
  patch description to 400 characters. It now shows the COMPLETE
  64-character SHA-256 and the COMPLETE description — no prefix, no cap,
  no ellipsis. (`MainActivity.kt`)
- The dialog body now SCROLLS, so any length is fully readable, and the
  text is SELECTABLE — you can long-press, copy the hash, and verify it
  yourself outside the app. Length is never a reason to cut anything.
- The exact consequences of APPROVE and DENY (apply at next start /
  nothing changes / 72h auto-rollback) are stated in the dialog itself.
- **Pinned by tests**: `ConsentPromptTest` now covers all FIVE approval
  surfaces — shell, python_exec, app_intent (share + view/dial), the
  in-chat self_fix patch consent, and the crash-repair patch dialog —
  each asserting the complete content appears and no ellipsis does.

## Version

- `versionCode` 20 → 21, `versionName` 0.6.11 → 0.6.12.

---

# Changelog — v0.6.11 "Show Me Everything"

Resolves the two judgment calls the VRA-2026-1103 audit left to the
maintainer (N-I1, N-I4) under one rule: nothing is ever hidden or cut off
from the user. Nothing is disabled; every change strengthens an existing
guarantee.

## Consent prompts now show everything (N-I4)

- **shell** — the consent dialog shows the COMPLETE command, every line.
  The old 8-line preview with "+N more lines" is gone. (`SystemTools.kt`)
- **python_exec** — the first-run consent shows the complete program; the
  old 6-line preview is gone. (`PythonExecTool.kt`)
- **app_intent (share)** — the full text is shown; the old 120-character
  cut is gone. (`SystemTools.kt`)
- **self_fix** — the full 64-character SHA-256 is shown; the old
  16-character prefix with an ellipsis is gone. (`SelfFixTool.kt`)
- **The consent dialog itself now scrolls** (`VorvApp.kt`), so even a very
  long command is fully displayable. Length is never a reason to
  truncate anything.
- **Pinned by tests**: `ConsentPromptTest` (all four prompts contain the
  complete content and no truncation notice) and a new
  `SelfFixToolTest` case (full SHA-256, no ellipsis).

## Reserved code documented (N-I1)

- **`HotPatcher.fetchManifest`** is now formally reserved in SECURITY.md:
  reachable from no code path, kept for the planned remote-patch feature,
  HTTPS allow-list and pinned DNS intact. Making it live remains a
  deliberate decision, never an accident.

## Version

- `versionCode` 19 → 20, `versionName` 0.6.10 → 0.6.11.

---

# Changelog — v0.6.10 "Audit Follow-ups"

Every finding from the VRA-2026-1103 full-project line-by-line audit (158
files, 24,063 lines) fixed. The audit re-verified all 27 findings from
VRA-2026-1102 as still remediated and found 16 new items (3 medium, 9 low,
4 informational) — all addressed below. Nothing is disabled; every fix
preserves behavior.

## Medium

- **N-M1 — healer classpath went stale after an app update.** The extracted
  `app_classes.jar` (filesDir) and the workspace-staged copy were reused
  without a version check, so the first self-repair after an update compiled
  against the PREVIOUS app's classes while PatchVerifier compared against the
  new original. Both copies are now stamped with `BuildConfig.VERSION_CODE`
  and re-extracted/re-staged whenever it changes. (`HealingAgent.kt`)
- **N-M2 — persisted tool outputs could overwrite each other.** Full outputs
  were named `<iteration>_<tool>.txt`; two calls to the same tool in one
  assistant turn (parallel tool calls) collided, and both DB rows pointed at
  a file holding only the second output. Filenames now carry the per-task
  call index. Pinned by a new CognitiveKernelTest case.
  (`CognitiveKernel.kt`)
- **N-M3 — tool inventory undercounted (15 vs 16).** `self_fix` was missing
  from the Tools screen entirely, and the Tools header, the Setup About text
  and the README diagram all said "15". The inventory now lists all 16 with
  an honest description of self_fix. (`ToolsScreen.kt`, `SettingsScreen.kt`,
  `README.md`)

## Low

- **N-L1** — `CloudLlm` no longer swallows `CancellationException`: a timed-out
  cloud task's worker stops retrying immediately instead of running up to 4
  background attempts. (`Llm.kt`)
- **N-L2** — `BootTracer.start()` writes are guarded: an I/O failure before
  the crash handler installs can no longer crash the app unlogged.
  (`BootTracer.kt`)
- **N-L3** — dead `patchConsent` field removed from `MainActivity`.
- **N-L4** — `BootTracer` writes are `@Synchronized`: GenieX callbacks write
  breadcrumbs from binder threads; lines can interleave in order but never
  corrupt. (`BootTracer.kt`)
- **N-L5** — watchdog relaunch circuit breaker: at most 5 relaunches per
  10 minutes. A persistent pre-safe-mode native crash can no longer spin
  crash→relaunch forever. (`WatchdogService.kt`)
- **N-L6** — a HotPatcher manifest entry without a `classes` list is
  quarantined like any other bad entry instead of NPE-ing and aborting the
  whole patch loop. (`HotPatcher.kt`)
- **N-L7** — `self_fix` budget aligned to the real 15-minute ceiling (was a
  documented 21 minutes that the task timeout and healer budget made
  unreachable). (`SelfFixTool.kt`)
- **N-L8** — the crash-dialog analysis and self-repair paths refuse to start
  while a chat task is running, so two native GenieX engines can never hold
  the NPU at once. (`MainActivity.kt`)
- **N-L9** — duplicated H-4 doc block removed from `app/build.gradle.kts`
  (introduced with the CI dependency fix).

## Informational

- **N-I2** — SECURITY.md consent section now names every gated tool
  (app_intent, shell, python_exec first-use, self_fix); the hashlib sandbox
  self-test asserts the real digest prefix instead of a vacuous empty string.
- **N-I3** — README status header no longer pins a stale version; the
  acceptance-test status line likewise.
- Also fixed in 2040a41 (same series): CI run #57's
  `mergeReleaseAssets` WorkValidationException — the healer asset producer is
  now a registered generated-asset directory with explicit consumer edges, so
  the build order is deterministic instead of a coin flip. Verified by
  run #58's green test job.

---

# Changelog — v0.6.9 "27/27 Remediated"

This release fixes every finding from the VRA-2026-1102 forensic audit
(27 findings: 2 critical, 5 high, 10 medium, 10 low) — in-place hardening
that PRESERVES every feature. Nothing is disabled: Python execution, JS
execution, DEX hot-patching, the watchdog, shell, and network tools all
keep working, now with real gates.

Severity legend: C = Critical, H = High, M = Medium, L = Low.

---

## Critical

### C-1 — python_exec sandbox was escapable (dunder guard only wrapped the getattr() builtin; whitelisted modules leaked os/sys)
**Was:** the guarded getattr/hasattr/setattr builtins never saw attribute SYNTAX — `uuid.uuid4.__globals__['os']` handed user code the live os module (uuid.py imports os/sys at module level). Verified by the audit's PoC.
**Now (vorv_runner.py, Layer 0):**
- namespace scrubbing of the entire whitelist closure: real os/sys references are replaced with inert proxies (only urandom/getpid and safe sys constants survive — C builtins with no reachable namespace);
- every callable the sandbox exposes (guarded getattr/setattr/hasattr, the safe `__import__`, the meta-path finder, the trace watchdog) is defined in a CLEAN namespace, so `fn.__globals__` yields nothing;
- a transitive verification walk (module dicts, function `__globals__`, class dicts, MROs) asserts no real os/sys is reachable from the whitelist; a module that cannot be scrubbed or whose canary self-test fails is dropped from the session whitelist with an honest ImportError;
- **the user-consent bridge gates the first python_exec call per task** (audit-recommended: a prompt-injected model can call it up to 30 times/task) — the in-process sandbox is documented as hardening, not a barrier (SECURITY.md updated);
- test_vorv_sandbox.py gains the direct-syntax attack battery: the exact audit PoC, attribute-syntax walks on random/json/statistics, inert-proxy refusal, clean-namespace probing of the exposed guard functions.
**Files:** `app/src/main/python/vorv_runner.py`, `app/src/main/python/test_vorv_sandbox.py`, `PythonExecTool.kt`

### C-2 — HotPatcher loaded unsigned .dex files and skipped hash verification when a manifest entry was missing
**Was:** a self-attested dex loading into the app process; a missing manifest entry meant NO verification at all; auto-generated patches never required user consent; and loaded patches never actually ran (see the dispatch fix below).
**Now:**
- every patch dex is **signed with the device patch keypair** (`PatchKeys`: Android-Keystore Ed25519 on API 33+, ECDSA P-256 fallback, software fallback for environments without a Keystore) and HotPatcher REFUSES (quarantines, never skips) any dex failing signature, hash, version-gate, consent-registry, or dispatch-interface checks;
- the manifest fetch is pinned to HTTPS allow-listed hosts and resolves through the NetworkGuard DNS;
- **every auto-generated patch goes through the user-consent bridge** (the same APPROVE/DENY dialog as app_intent and shell) before anything is registered;
- the v0.6.5 "load the class" mechanism could never dispatch (same-FQCN classes under different classloaders are different runtime identities) — patches now run through **interface-based dispatch**: shared interfaces (Tool / KernelApi / HealerApi) + `PatchRegistry` with an honest 18-class patchable surface, and the kernel/healer/tools are constructed through it;
- **probation + auto-rollback**: a patch followed by a crash in the class it overrides within 72h of first apply is rolled back at next boot (a fix that makes things worse undoes itself).
**Files:** `watchdog/PatchKeys.kt` (new), `watchdog/HotPatcher.kt`, `watchdog/PatchRegistry.kt` (new), `healer/HealerApi.kt` (new), `kernel/KernelApi.kt` (new)

## High

### H-1 — Watchdog crash detection was structurally dead
**Was:** the watchdog process ran the same `VorvApplication.onCreate` and wrote its own "alive" heartbeats (poisoning the stale-heartbeat check), MainActivity never called bindService (the documented binder-death signal did not exist), and the heartbeat pid was recorded but ignored.
**Now:** `VorvApplication` guards init by process name (main process = full init; `:watchdog` = none); heartbeats are written exclusively from the main process; **MainActivity binds to the watchdog and registers a death token** — `linkToDeath` fires immediately when the main process dies, with the pid-checked stale heartbeat as fallback.

### H-2 — shell executed arbitrary bash with no consent gate
**Now:** ShellTool routes through `ctx.confirm` with the first lines of the command in the dialog — the same bar as app_intent. Fail-closed without the bridge; a denial is reported honestly.

### H-3 — browser_automate validated only the initial URL (redirects/JS navigations bypassed the guard)
**Now:** a `GuardWebViewClient` runs `NetworkGuard.checkHostAllowed` on EVERY navigation (`shouldOverrideUrlLoading`) and EVERY sub-resource request (`shouldInterceptRequest` — iframes, fetches, XHRs). Blocked navigations fail the tool call with the reason.

### H-4 — the self-heal compile pipeline could not work on a stock device (Termux paths it cannot read)
**Now:** the compile round-trips through the SAF workspace exactly like ShellTool (script/source/jar/dex inside the granted folder, `/storage/emulated/0/...` paths), the app classpath jar is PACKAGED at build time (new `packageHealerClasspath` Gradle task → `assets/healer/app_classes.jar`) and staged into the workspace before each compile, and the heal runs in the MAIN process (see M-8). Tool-missing errors (kotlinc/d8) are honest and actionable.

### H-5 — the bundled source mirror (assets/source) was stale
**Now:** a `syncSourceMirror` Gradle task copies the live tree into `assets/source` on every build (wired into preBuild) — drift is structurally impossible, so patches are always generated against the code that matches the running binary.

## Medium

- **M-1 (DNS-rebinding TOCTOU):** WebView requests are proxied through an OkHttp client wired to `NetworkGuard.PublicDns` — one resolution, checked, and used for the actual transfer.
- **M-2 (unbounded local-mode context):** `LocalLlm.contextTokenBudget` states the honest 1800-token prompt budget; the kernel estimates size per iteration and compacts completed tool results into a digest (system + task + recent turns survive); GenieX context/token errors surface as "context window exhausted at iteration N".
- **M-3 (Rhino CVE-2025-66453):** bumped to `org.mozilla:rhino:1.7.15.1`; the JsExecTool attack suite pins the upgrade.
- **M-4 (clear-all-data left data behind):** clearAllData now cancels every registered reminder (WorkManager + registry), clears the registry preference, recursively deletes tool-output files, and purges crash/watchdog/healer diagnostics. The API key is kept (a setting, not agent data) and the dialog says so.
- **M-5 (file_read buffered whole file before the cap):** streams at most 200 KB then truncates to the 50 000-char cap.
- **M-6 (web_fetch decoded UTF-8 per 16 KB chunk):** decodes through an InputStreamReader — multi-byte characters split across chunk boundaries survive.
- **M-7 (vorv.db in cloud backup despite "stays local"):** the database, crash logs and healer diagnostics are excluded from cloud backup AND device transfer; SECURITY.md states exactly what participates.
- **M-8 (watchdog ran runBlocking LLM heals on the heartbeat thread):** the watchdog NEVER runs the LLM. Healing runs in the restarted MAIN process (crash dialog "Self-repair") on Dispatchers.IO under a hard 15-minute budget.
- **M-9 (schedule enqueue/registry not atomic):** register-first-then-enqueue with rollback on enqueue failure; expired entries are returned for cancellation too, so nothing lingers uncancellable.
- **M-10 (crash filenames had one-second resolution):** millisecond timestamp + pid in the filename.

## Low

- **L-1:** Gradle wrapper committed (gradlew, gradlew.bat, gradle-wrapper.jar, `distributionSha256Sum`); CI uses `./gradlew`.
- **L-2:** DEVELOPMENT.md describes the actual schema decision (exportSchema off; MigrationTest builds v1 by DDL).
- **L-3:** the release tag is derived from the real `versionName` in app/build.gradle.kts.
- **L-4:** carrier-grade NAT 100.64.0.0/10 blocked in NetworkGuard.
- **L-5:** JsExecTool creates a WatchdogFactory per run (no shared mutable deadline/aborted state).
- **L-6:** file_write colliding with a directory name is an honest error, not a provider-suffixed duplicate.
- **L-7:** BootTracer writes BOOT_STARTED before the hot-patch step (a hotpatch-phase crash is no longer misclassified as the previous boot's).
- **L-8:** IP literals are detected syntactically — no DNS lookup for non-literal hosts in parseLiteral.
- **L-9:** AgentService declared `specialUse` with the required property (the honest type for a compute workload).
- **L-10:** notification IDs come from an AtomicInteger seeded randomly — no truncated-timestamp collisions.

## Feature completion (user requirements)

- **Self-healing covers EVERY problem, not just crashes**: `HealingAgent.modify(HealRequest)` runs the same hardened pipeline from a free-text problem statement; the crash-dialog path is a thin wrapper over it.
- **`self_fix` agent tool (16th)**: the user can tell Vorv IN CHAT to fix or change its own code ("make notify use a different sound"); the agent drives generate → compile → verify → sign → user consent → apply-at-next-boot, fail-closed at every step.
- **Completeness gate (`PatchVerifier`)**: before a patch is signed or shown to the user, it is loaded in an isolated classloader and checked against the running class — every public constructor, method and field must survive, the dispatch interface must be implemented. Failures feed back into the regeneration loop. **No partial fixes, ever.**
- **Target-class inference** for problems that do not name a class: the model may only pick from the dispatchable surface; anything else is refused honestly.
- **Multi-class source files handled** (ShellTool lives in SystemTools.kt): the source lookup scans the package for the class declaration instead of assuming `<Class>.kt`.
- **CrashParser no longer swallows the method name** (`…NotifyTool.run` → `host.vorv.tools.NotifyTool`), which used to break every source lookup.
- **Repairs use the STRONGEST on-device model**: `ModelManager.bestRepairModel()` picks the 7B whenever it is downloaded (the 3B only if the 7B was never pulled), and `repairLabel()` names the model that wrote each patch — a 3B patch can never masquerade as a 7B one.

## Also fixed along the way

- CI workflow: the push trigger had a broken `branches:` list (a stray `[` made it `ain, …`), so pushes to main did not run CI deterministically.
- `CrashReportWorker` filenames include the pid (two crashes in the same millisecond from different processes cannot overwrite each other).

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
