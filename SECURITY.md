# Vorv Security Policy

This document is the honest threat model for Vorv v0.6.6, written to be read
by both maintainers and users. It replaces the stale security claims of the
v0.5.x README (audit finding C-6) and reflects the second audit's remediation
(report VRA-2026-1102: 27 findings, all fixed).

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
   AND every `shell` command require an Allow/Deny dialog that shows exactly
   what will happen (for shell: the command text). A denied confirmation is
   reported back to the model as a failure — the model cannot retry its way
   around the user. Both tools fail closed when no bridge is available.

4. **Egress control (network tools):** `web_fetch` resolves through an OkHttp
   `Dns` implementation that refuses loopback, link-local, site-local,
   unique-local, carrier-grade NAT (100.64.0.0/10), any-local and multicast
   addresses; `browser_automate` host-checks EVERY navigation (redirects and
   JS moves included) and proxies all GET traffic through the same guarded
   DNS at connect time. Localhost services, router admin panels and cloud
   metadata endpoints are unreachable on both paths.

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

Four layers, self-tested in CI (`app/src/main/python/test_vorv_sandbox.py`,
90+ assertions including direct-attack regression cases):

1. **CPython audit hook** (`sys.addaudithook`, cannot be removed by user code):
   blocks `open`, `os.system`, `os.exec`, `subprocess.Popen`, `socket.*`,
   `os.kill`, `ctypes.*`, `sys._getframe`, and more — raising `PermissionError`
   at the C level.
2. **AST-level attribute guard** (audit C-1 fix): user code is parsed and its
   AST REWRITTEN before compilation — every attribute read/write/delete and
   every `from X import y` routes through guarded helpers that block:
   - all dunder attribute names in any syntax position (the v0.6.5 guard only
     wrapped the `getattr()` builtin, so `x.__class__` bypassed it);
   - reflection routes that do attribute access inside C code, invisible to
     any Python-level guard (`str.format`/`str.format_map` — use f-strings);
   - any attribute whose value is a module outside the whitelist
     (`re.enum` → `enum.sys` chains are dead);
   - writes to and deletes from module objects (no state poisoning across
     runs of the same long-lived interpreter).
   The exposed `getattr`/`setattr`/`delattr` builtins ARE the guarded
   functions — shadowing them with each other gains an attacker nothing, and
   no unguarded attribute primitive exists in the namespace.
3. **Import whitelist**: only pre-imported pure-computation stdlib modules
   (math, json, re, datetime, collections, …). `uuid`, `random` and
   `statistics` are deliberately NOT whitelisted (they import os/sys
   transitively — the original C-1 leak); safe runner-implemented
   `uuid4()`, `randint()` and `random_float()` builtins are provided instead.
   `os`, `sys`, `io`, `time`, `socket`, `subprocess`, `threading`, `ctypes`,
   `pickle` raise `ImportError` — as does Chaquopy's `java` interop module.
4. **Trace watchdog**: `sys.settrace` raises `TimeoutError` past the deadline.

**Residual limits (honest):**

- An in-process sandbox remains defense-in-depth, not a formal security
  boundary: the AST guard closes every KNOWN escape route (dunder syntax,
  format-string reflection, module hopping, builtin shadowing, frame walking,
  bytecode synthesis, ctypes), but a novel CPython-level exploit could still
  exist. A process boundary would be required for a formal guarantee.
- C-extension compute (e.g. a catastrophic regex) cannot be interrupted by
  `settrace`; such calls hard-timeout at the Kotlin thread join and leak only
  a daemon thread.
- If an escape is found, the blast radius is the app process (same as
  js_exec). File/network/process escapes must then chain a second bug in
  Android itself to leave the app sandbox.

### browser_automate (WebView)

- URL scheme allowlist: http/https only.
- `allowFileAccess`, `allowContentAccess`, `allowFileAccessFromFileURLs`,
  `allowUniversalAccessFromFileURLs`: all **false**.
- Mixed content: `MIXED_CONTENT_NEVER_ALLOW`.
- **Every navigation is host-checked** (audit H-3): a GuardWebClient
  validates the host in `shouldOverrideUrlLoading` AND
  `doUpdateVisitedHistory` — redirects and JS `location` changes cannot walk
  around the guard; a blocked navigation stops the load and is reported
  honestly to the model.
- **DNS-rebinding (audit M-1):** all GET traffic is proxied through OkHttp
  with the guarded `PublicDns`, which re-resolves and validates every hop,
  redirects included, at connect time.
- JS built by Gson string interpolation (no injection via selectors/text).
- The WebView is headless and destroyed with the ViewModel.
- Residual (honest): non-GET requests cannot be proxied — Android does not
  expose request bodies to `shouldInterceptRequest` — so a theoretical
  rebinding window remains for POST/PUT subresources against a
  attacker-controlled DNS. Navigation-level host checks still apply to the
  page itself.

### shell (Termux)

- **Every command requires user consent** (audit H-2): the same Allow/Deny
  bridge as `app_intent`, showing the command text. Fail-closed when the
  bridge is unavailable; denials reported to the model as failures.
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
  (`allowBackup=false`; the backup rule sets are empty).
- The pre-0.6.0 plaintext copy (DataStore) is migrated once and erased.
- No secrets are committed to the repository. The previously committed debug
  keystore was removed; release signing uses GitHub Actions secrets only.

## Data

- Stored locally: prompts, task history, tool-call logs (30-day retention),
  taught memories (kept until you delete them), model files.
- **Nothing is backed up** (audit M-7): `allowBackup=false` with empty
  backup/data-extraction rule sets — prompts, tool inputs/outputs and
  memories do NOT participate in Android cloud backup or device transfer.
  Moving to a new phone means re-teaching; local-first means local.
- Leaving the device (only when you use the features): prompts + tool results
  to Z.ai when in cloud mode; search queries to Z.ai's Web Search API;
- fetched URLs contact their origin servers; WebView automation contacts
  loaded pages' origins.
- "Clear ALL data" (Setup tab) wipes every table, all scheduled reminders,
  tool-output files and crash diagnostics after a confirmation dialog. Your
  API key and settings are kept (the dialog says so).

## Reporting a vulnerability

Open a GitHub issue marked **[security]** or contact the maintainer directly.
Please include a PoC; for sandbox escapes, the sandbox self-test files
(`app/src/main/python/test_vorv_sandbox.py`, `JsExecToolTest.kt`) are the
place to add a regression case.
