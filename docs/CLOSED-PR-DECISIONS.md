# Closed, unmerged pull requests: what happened to each one

Sixteen security PRs (#15–#30) were opened against this repository, reviewed,
then closed without merging. Each line below says whether the concern is
handled in `main` today, handled differently, or still open. "Verified by
reading" means the code was read on 2026-11-17 and the mitigation is present;
none of these paths was exercised on a device.

| PR | Title | Decision |
|----|-------|----------|
| #15 | Remove exposed debug signing key and isolate development builds | **Declined as written.** The debug key in the repo is deliberate and documented in `app/build.gradle.kts`: sideload-only distribution needs one stable signature, or every build-machine move forces the owner to uninstall and lose all data. The real fix is a separate release key (needs the owner's go-ahead) and a `.ci` application id for test builds. |
| #16 | Prevent workspace script injection in healer compilation | **Landed differently.** Nothing in `healer/` builds a shell command line; compilation goes through the in-process toolchain. `tools/ShellQuote.kt` exists for the places that do pass text to a shell. |
| #17 | Block cross-origin browser proxy responses and fail closed | **Obsolete.** The local browser proxy this PR patched no longer exists in `main`; the browser path is `BrowserAutomateTool` + `NetworkGuard.PublicDns`. |
| #18 | Require user confirmation before each workspace file write | **Open by choice.** A confirmation per write would make every real task unusable for a single-owner agent. File writes are confined to the SAF workspace root, and every call is visible in the trace. Revisit if this app ever serves more than its owner. |
| #19 | Require explicit consent for web searches and fetches | **Open by choice, same reason as #18.** Egress is limited by `NetworkGuard` (private/loopback addresses refused), not by a prompt per request. |
| #20 | SSRF checks for IP literals and redirect destinations | **Landed.** `NetworkGuard` parses bare IP literals and checks every address a host resolves to; `BrowserAutomateTool` re-checks redirect destinations with `PublicDns`. Verified by reading. |
| #21 | Sensitive data retention after clearing agent data | **Landed, wider.** `clearAllData()` now cancels reminders, deletes tool output, crash logs, watchdog and healer directories and wipes every table — and says in the confirmation which things are deliberately kept (key and settings). |
| #22 | Browser proxy SSRF through redirects and WebView fallback | **Obsolete** for the proxy part (no proxy); the redirect part is covered by the `GuardWebViewClient` in `BrowserAutomateTool`. |
| #23 | Do not fall back to WebView when resource proxying fails | **Obsolete.** There is no resource proxy to fall back from. |
| #24 | Require confirmation for every browser automation action | **Open by choice, same reason as #18.** |
| #25 | Path traversal in crash log reads | **Landed.** `CrashLogsTool.read` rejects traversal and requires the canonical path to stay inside the external files directory. Verified by reading. |
| #26 | Bound JavaScript sandbox output | **Landed in 0.9.21.** Each sandbox stream is capped at 64 KB; past that the handler appends a truncation note instead of growing without limit. |
| #27 | Require approval before persisting model-proposed memories | **Open by choice.** Memories are the product; a prompt per memory defeats it. `clearAllData()` can remove them in one action. |
| #28 | Keep reminder content out of backups | **Not landed.** Reminder text lives in the same preferences file as ordinary settings, so `backup_rules.xml` cannot exclude it without excluding settings too. Needs its own preferences file before it can be excluded — tracked, not fixed. |
| #29 | Consent before scheduling reminders | **Open by choice, same reason as #18.** |
| #30 | Bound Termux shell time and output, clean up descendants | **Partly landed.** Every shell call carries `timeout_ms` (1000–120000, default 60000) and a task-level timeout; the guest process tree is killed on timeout. An explicit output cap is not in `main` — tracked, not fixed. |

## Why some of these stay open

This app has exactly one user, who owns the device, the workspace and the key,
and who has asked repeatedly for an agent that acts rather than asks. A consent
prompt on every file write, fetch, memory and reminder is not a security
control here — it is the failure mode the owner has spent four years fighting.
The controls that do stay are the ones that protect against a *mistaken or
malicious model*, not against the owner: workspace confinement, egress
blocking, traversal rejection, output bounds, and a trace of every action.

If this repository is ever used by anyone else, #18, #19, #24, #27 and #29 must
be re-opened before that happens.
