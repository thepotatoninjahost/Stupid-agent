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

3. **User consent (side-effecting tools):** `app_intent` (all four actions)
   requires an Allow/Deny dialog that shows exactly what will happen. A denied
   confirmation is reported back to the model as a failure — the model cannot
   retry its way around the user.

4. **Egress control (network tools):** `web_fetch` resolves through an OkHttp
   `Dns` implementation that refuses loopback, link-local, site-local,
   unique-local, any-local and multicast addresses; `browser_automate`
   pre-checks hosts the same way. Localhost services, router admin panels and
   cloud metadata endpoints are unreachable.

## Per-tool sandbox guarantees

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

Four layers, self-tested in CI (`app/src/main/python/test_vorv_sandbox.py`):

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
  (`data_extraction_rules.xml` / `backup_rules.xml` include only `vorv.db`).
- The pre-0.6.0 plaintext copy (DataStore) is migrated once and erased.
- No secrets are committed to the repository. The previously committed debug
  keystore was removed; release signing uses GitHub Actions secrets only.

## Data

- Stored locally: prompts, task history, tool-call logs (30-day retention),
  taught memories (kept until you delete them), model files.
- Leaving the device (only when you use the features): prompts + tool results
  to Z.ai when in cloud mode; search queries to Z.ai's Web Search API;
- fetched URLs contact their origin servers; WebView automation contacts
  loaded pages' origins.
- "Clear ALL data" (Setup tab) wipes every table after a confirmation dialog.

## Reporting a vulnerability

Open a GitHub issue marked **[security]** or contact the maintainer directly.
Please include a PoC; for sandbox escapes, the sandbox self-test files
(`app/src/main/python/test_vorv_sandbox.py`, `JsExecToolTest.kt`) are the
place to add a regression case.
