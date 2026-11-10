# Vorv Security Policy

This document is the honest threat model for Vorv v0.6.0, written to be read
by both maintainers and users. It replaces the stale security claims of the
v0.5.x README (audit finding C-6).

## Threat model

Vorv executes output from an LLM. The LLM is **untrusted**:

- even an aligned model can be confused or jailbroken by adversarial content
  pasted into a prompt, fetched from a web page, or read from a file;
- the local 7B model in particular has limited instruction-following ability
  and can emit malformed or hostile tool arguments with no adversarial input
  at all.

Therefore every tool treats its input as hostile data. The defense is layered:

1. **Kernel-level (applies to every tool call):**
   - Arguments are validated against each tool's JSON schema before dispatch
     (`ToolCallValidator`).
   - Each tool call has a wall-clock timeout (`Tool.timeoutMs`).
   - Each tool has a per-task invocation cap (`Tool.maxCallsPerTask`).
   - Tool outputs fed back to the LLM are capped at 2000 chars.
   - The system prompt marks tool outputs, file contents and web pages as
     untrusted data, never as instructions.

2. **Sandboxing (code-execution tools):** see below.

<!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:887f0fc41e42368c6ab8ac0c91de3312c9e1d3826872a0c5864a94b91def81fe -->

3. **User consent:** intents, notifications, reminders, workspace writes, model-proposed memory writes, web requests, every browser action, shell commands and self-repair patches require the consent bridge. Python asks once per task. The full action payload is disclosed; missing or denied consent fails closed. A folder grant scopes access but does not approve each model-proposed write. Direct teaching uses the same confirmation path.

4. **Network destinations:** numeric IP destinations and DNS results are checked. Web fetch follows redirects only after separate approval of each destination. Browser requests are proxied through the guarded client; failures return a blocked response, never an unguarded WebView retry. Cross-origin proxy redirects are refused because their response would otherwise inherit the original WebView origin. Same-origin browser redirects remain supported.

5. **Resource budgets:** JavaScript console capture and returned values are bounded; Python stdout/stderr are capped before buffering. A Python worker that survives its deadline blocks subsequent executions until it stops or the app restarts. Native code can still allocate memory before these output limits apply; in-process execution is not a process isolation boundary.

6. **Shell supervision:** Termux requires Python (`pkg install python`) for deadline, output, cancellation and descendant-process supervision. Captured stdout is capped at 400000 bytes and stderr at 80000 bytes. Shell remains explicitly authorized native execution with Termux's permissions, not a sandbox against malicious approved code.

7. **Private backup data:** reminders, patch keys, patch approvals and DEX files are excluded from backup/transfer, along with the database, API key and crash diagnostics. Patches must retain their local device identity.



<!-- /coderabbit-ai-generated:v1 -->## Per-tool sandbox guarantees

### js_exec (Rhino)

- `ClassShutter` denies every Java class except Rhino internals and a small
  allowlist of immutable `java.lang` types (`String`, `Boolean`, `Number`,
  `Math`, …). `Runtime`, `ProcessBuilder`, `System`, `Class`, `java.io.*`,
  `android.*`, `host.vorv.*`, `com.chaquo.*` and `com.geniex.*` are all denied.
- LiveConnect globals (`Packages`, `java`, `javax`, `org`, `com`, `net`, …)
  and import helpers (`importClass`, `importPackage`, `JavaAdapter`,
  `JavaImporter`) are deleted from the scope, which is then **sealed**.
- Timeout via Rhino's instruction-count observer on the evaluating thread
  (no cross-thread context access).
- Residual risk: none known for the interpreted engine with LiveConnect
  removed. Rhino's historical sandbox CVEs target LiveConnect, which is
  deleted here.

### python_exec (Chaquopy, CPython 3.11)

Five layers, self-tested in CI (`app/src/main/python/test_vorv_sandbox.py`):

0. **Namespace scrubbing + verification (v0.6.9, audit C-1):** the whitelist
   closure is scrubbed of the real `os`/`sys` module objects (inert proxies
   with only `urandom`/`getpid` survive), every callable the sandbox exposes
   is defined in a clean namespace (`fn.__globals__` yields nothing), a
   transitive walk verifies no real `os`/`sys` is reachable, and per-module
   canaries drop any module that cannot be offered safely. The first
   execution per task additionally requires USER CONSENT — an in-process
   sandbox is hardening, not a barrier (see residual limits below).

1. **CPython audit hook** (`sys.addaudithook`, cannot be removed by user code):
   blocks `open`, `os.system`, `os.exec`, `subprocess.Popen`, `socket.*`,
   `os.kill`, `ctypes.*`, `sys._getframe`, and more — raising `PermissionError`
   at the C level.
2. **Restricted `__builtins__`**: `open`, `eval`, `exec`, `compile`,
   `__import__`, `input`, `globals`, `vars`, `delattr`, `super` are absent;
   `getattr`/`setattr`/`hasattr` block dunder names (closes the
   `__class__.__subclasses__()` escape chain).
3. **Import whitelist**: only pre-imported pure-computation stdlib modules
   (math, json, re, datetime, statistics, collections, …). `os`, `sys`, `io`,
   `time`, `socket`, `subprocess`, `threading`, `ctypes`, `pickle` raise
   `ImportError`.
4. **Trace watchdog**: `sys.settrace` raises `TimeoutError` past the deadline.

**Residual limits (honest):**

- In-process sandboxes are defense-in-depth, not a formal security boundary.
  CPython audit hooks are bypassable in principle by novel CPython-level
  exploits; the known routes (ctypes, bytecode injection via `compile`/`eval`/
  `marshal`, frame walking) are individually blocked here.
- C-extension compute (e.g. a catastrophic regex) cannot be interrupted by
  `settrace`; such calls hard-timeout at the Kotlin thread join and leak only
  a daemon thread. A subprocess boundary would be needed to do better.
- If an escape is found, the blast radius is the app process (same as
  js_exec). File/network/process escapes must then chain a second bug in
  Android itself to leave the app sandbox.

### browser_automate (WebView)

- URL scheme allowlist: http/https only.
- `allowFileAccess`, `allowContentAccess`, `allowFileAccessFromFileURLs`,
  `allowUniversalAccessFromFileURLs`: all **false**.
- Mixed content: `MIXED_CONTENT_NEVER_ALLOW`.
- Private/loopback hosts blocked before load.
- JS built by Gson string interpolation (no injection via selectors/text).
- The WebView is headless and destroyed with the ViewModel.

### shell (Termux)

- Dispatched via Termux `RUN_COMMAND`; **output is captured for real** through
  a `.vorv-shell/` round-trip in the user-granted workspace and returned with
  the true exit code. If capture is impossible, the tool returns an honest
  error and the model is explicitly told the result is UNKNOWN.
- Prerequisites (Termux, RUN_COMMAND permission, `termux-setup-storage`,
  workspace on primary storage) are checked individually with actionable
  error messages.

## Secrets

- The Z.ai API key is stored in `EncryptedSharedPreferences`
  (AES-256-GCM values, AES-256-SIV keys, master key in the Android Keystore —
  hardware-backed on the S25). It is never logged, never sent anywhere except
  `api.z.ai` in an `Authorization` header, and excluded from backups
  (`data_extraction_rules.xml` / `backup_rules.xml` EXCLUDE `vorv.db`, crash
logs and healer diagnostics entirely — conversation and tool-call data never
participate in cloud backup or device transfer; only non-secret settings do).
- The pre-0.6.0 plaintext copy (DataStore) is migrated once and erased.
- No secrets are committed to the repository. Release key material is
  never committed; release signing uses GitHub Actions secrets only.
<!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:3e13b15dea5eedb9b3f1de074a4958e95fa43e10f03a5b69e671c9b8d566b382 -->

- The legacy debug signing key is publicly committed to preserve updates of existing test installations. This is an exposed update identity: anyone with that key can sign an APK Android accepts as an update to an installation using it. A debug signature is not a production trust boundary. Install only builds obtained through the repository's verified build/release path. Migrating existing installations to a private key requires an explicit data-preserving migration decision; changing a signing key cannot transparently update the old app.


<!-- /coderabbit-ai-generated:v1 -->
## Data

**Backup posture (v0.6.9, audit M-7):** `vorv.db` — prompts, tool inputs and
outputs, memory — is EXCLUDED from Android cloud backup AND device transfer.
The same applies to crash logs and healer diagnostics. Only non-secret
settings (DataStore) participate. Data that stays local means it stays
local.


- Stored locally: prompts, task history, tool-call logs (30-day retention),
  taught memories (kept until you delete them), model files.
- Leaving the device (only when you use the features): prompts + tool results
  to Z.ai when in cloud mode; search queries to Z.ai's Web Search API;
- fetched URLs contact their origin servers; WebView automation contacts
  loaded pages' origins.
- "Clear ALL data" (Setup tab) wipes every table after a confirmation dialog.

## Reporting a vulnerability

Open a GitHub issue marked **[security]** or contact the maintainer directly.
## Self-healing: what is honestly patchable

The on-device self-repair system (crash dialog "Self-repair", the `self_fix`
tool) can patch exactly the classes with a `PatchRegistry` dispatch point:
the 17 agent tools, `CognitiveKernel` (via `KernelApi`) and `HealingAgent`
(via `HealerApi`) — 19 classes, matching `PatchRegistry.DISPATCH_BASES`
exactly. A problem outside that surface (Activity,
Application, Service, helpers) is refused with an honest explanation, because
a patch for such a class would compile but could never actually run.

Every patch passes ALL gates before it takes effect: on-device compile
(Termux round-trip through the user-granted workspace), the PatchVerifier
completeness check (the full public API of the original class must survive),
an Ed25519/ECDSA signature by the device patch key, explicit user APPROVAL,
and version gates. After applying, a 72-hour probation window auto-rolls-back
any patch followed by a crash in the class it overrides.

Please include a PoC; for sandbox escapes, the sandbox self-test files
(`app/src/main/python/test_vorv_sandbox.py`, `JsExecToolTest.kt`) are the
place to add a regression case.

## Reserved code (explicitly dormant)

Nothing in this codebase is silently dead. `HotPatcher.fetchManifest`
(HTTPS-only URL, host allow-list, DNS pinned through `NetworkGuard`)
currently has **no caller**: it exists for the planned remote-patch
feature and is reachable from no code path, so it can never fire on its
own. Making it live is a deliberate maintainer decision that will be
recorded here — never an accident. Until then it is inert code kept
honest on purpose, not a hidden feature.
