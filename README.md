# Vorv

**Local-first autonomous agent for Samsung Galaxy S25 (Android 15+).**

Vorv is an Android app that does real multi-step work — it plans, picks a tool, executes, observes the actual result, picks the next tool, repeats. Not a chatbot. Not a wrapper around a single LLM call.

---

## Status — honest, as always

✅ **Local LLM**: Qwen2.5-Coder-3B/7B-Instruct (Q4_0 GGUF) on the Hexagon NPU via Qualcomm **GenieX** — fully offline after a one-time 1.8–4.4 GB download.
✅ **Cloud LLM**: GLM-4.7-Flash via Z.ai — free tier, $0/token, the DEFAULT (optional paid models: GLM-5.3-Flash, GLM-5.3, GLM-4.6; needs your API key, stored encrypted at rest; automatic offline fallback to the local model when the signal drops).
✅ **16 tools, all wired** (list below). The 16th, `self_fix`, lets the user tell Vorv in chat to fix or change its own code — every patch is compiled on device, verified complete, signed, user-approved, and rolled back automatically if it causes a crash. js_exec and python_exec run in hardened in-process sandboxes; sensitive actions require explicit user confirmation; outputs are real or the tool reports an honest error.
✅ **Verifiable logging**: every tool call persists real input/output/duration/exit-code; long outputs are stored in full and viewable from the Tasks screen.
⚠️ **Acceptance tests #1–#8 below are still developer-unverified on a real S25.** The 0.7.x series fixes all 25 findings of the 2026-11 full code review — including the three root causes of "never usable" (no installable build ever published, the stale key gate, local-mode mid-task deaths) — see [CHANGES.md](CHANGES.md).

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      Vorv App (Kotlin)                        │
├─────────────────────────────────────────────────────────────┤
│  UI (Jetpack Compose, 6 screens, dark theme)                 │
│   Chat · Tasks · Memory · Tools · Files · Setup               │
├─────────────────────────────────────────────────────────────┤
│  AgentViewModel (StateFlow, single source of truth)           │
│   + ModelRepository (mutex-serialized engine lifecycle)       │
│   + user-consent bridge for sensitive tools                   │
├─────────────────────────────────────────────────────────────┤
│  CognitiveKernel — ReAct loop                                 │
│   perceive → plan → tool → observe → learn                    │
│   • tool args validated against schemas before dispatch       │
│   • per-tool wall-clock timeouts + per-task rate limits       │
│   • max 20 iterations; every iteration logged                 │
├─────────────────────────────────────────────────────────────┤
│  LLM (kernel picks)              │  Tool Registry (16)        │
│   ├─ Local: GenieX → Hexagon NPU  │  memory_write/recall      │
│   └─ Cloud: GLM-4.7-Flash (free)  │  js_exec (Rhino sandbox)  │
│                                   │  python_exec (sandboxed)  │
│                                   │  file_read/write/list     │
│                                   │  web_search / web_fetch   │
│                                   │  app_intent (confirmed)   │
│                                   │  shell (real output)      │
│                                   │  notify / schedule(+cancel)│
│                                   │  browser_automate         │
├─────────────────────────────────────────────────────────────┤
│  Persistence                                                  │
│   Room v3 (episodes, knowledge+FTS, skills, tasks,            │
│     chat_messages, tool_calls, artifacts)                     │
│   EncryptedSharedPreferences (API key, Keystore-backed)       │
│   DataStore (workspace URI, modes, reminder registry)         │
│   SAF DocumentFile (user-granted workspace)                   │
├─────────────────────────────────────────────────────────────┤
│  Foreground service (AgentService) + WorkManager reminders    │
└─────────────────────────────────────────────────────────────┘
```

---

## Setup

### Install

1. From the [Releases page](https://github.com/thepotatoninjahost/Stupid-agent/releases), download **`app-debug.apk`** from the latest **`ci-latest` pre-release** — CI deletes and rebuilds it on every push to `main`, and the debug keystore is committed since v0.7.0, so it installs and updates in place. The `app-release-unsigned.apk` asset is for developers only. A signed release build is **not** published yet (it requires the `RELEASE_KEYSTORE_*` secrets — see DEVELOPMENT.md).
2. On your S25: tap the APK → Settings → allow install from Chrome.
3. Open **Vorv**.
4. Grant the notification permission when prompted (needed by `notify` and `schedule`).

### Grant a workspace (optional, needed for file tools and shell output capture)

1. Open the **Files** tab.
2. Tap **Grant workspace access**.
3. Pick a folder on **internal shared storage**. Vorv can read/write/list files in this folder — nowhere else.
4. Permission persists across reboots.

### Enable cloud LLM (optional)

Default mode is **cloud with no key** (idle until you add one). Saving a key activates cloud mode on the **default free model, GLM-4.7-Flash**; the paid models (GLM-5.3-Flash, GLM-5.3, GLM-4.6) are a picker choice in Setup:

1. Get a free API key at `z.ai/manage-apikey`.
2. Open the **Setup** tab → paste your key → **Save Key**.

The key is stored with AndroidX Security (EncryptedSharedPreferences, AES-256-GCM, master key in the Android Keystore — hardware-backed on the S25). It is never uploaded, never logged, and excluded from cloud backups.

### Enable shell access (optional, used by the `shell` tool)

1. Install [Termux](https://github.com/termux/termux-app/releases) from F-Droid or GitHub releases (Play Store version is deprecated).
2. Open Termux once and run `termux-setup-storage` (allows Termux to write to shared storage — required for output capture).
3. Android Settings → Apps → Vorv → Permissions → Additional permissions → **Run commands in Termux environment**.
4. Ensure `~/.termux/termux.properties` contains `allow-external-apps=true`.
5. Grant a Vorv workspace folder (see above) on internal shared storage — shell output round-trips through `.vorv-shell/` inside it.

Without any of these, `shell` returns an honest error explaining exactly what's missing — it never fakes output.

---

## Tools and their safety properties

| Tool | What it does | Safety posture |
|---|---|---|
| `memory_write` / `memory_recall` | Room + FTS4 long-term memory | DB only |
| `self_fix` | Fix/modify Vorv's own code | Full hardened pipeline: strongest on-device model generates the complete fixed class → compiled in Termux via the workspace → PatchVerifier completeness gate → device-key signature → user APPROVE/DENY → applies at next boot → 72h probation auto-rollback. Honest refusal for classes outside the 18-class patchable surface |
| `js_exec` | JavaScript, 5s budget | **Rhino sandbox**: ClassShutter denies all Java except immutable `java.lang` types; LiveConnect globals (`Packages`, `java`, …) deleted; scope sealed; instruction-count watchdog |
| `python_exec` | Python 3.11, 30s budget | **Layered sandbox**: namespace scrubbing + transitive verification of the whitelist closure (no reachable os/sys; the audit-PoC chains are closed and self-tested); CPython audit hook blocks file/process/socket ops; restricted builtins; import whitelist; trace watchdog; first execution per task requires user consent |
| `file_read` / `file_write` / `file_list` | SAF workspace I/O | Scoped to the granted folder; 50KB read / 1MB write caps |
| `web_search` | Z.ai Web Search API (documented `/web_search` endpoint) | Key-gated |
| `web_fetch` | HTTP fetch → text | 2MB cap; content-type allowlist; **private/loopback/LAN/metadata egress blocked**; jsoup extraction |
| `app_intent` | view URL / share / dial / open app | **Every action needs a confirmation dialog**; http/https URLs only; premium-rate numbers refused outright |
| `shell` | Bash via Termux | **Real stdout/stderr/exit-code** captured via workspace round-trip; honest errors when setup is incomplete |
| `notify` | System notification | Checks POST_NOTIFICATIONS; honest error when missing |
| `schedule` / `schedule_cancel` | WorkManager reminders | Unique work names; 7-day cap; 20-pending cap; cancellable |
| `browser_automate` | Headless WebView automation | http/https only; file/content access disabled; mixed content blocked; private hosts blocked; Gson-quoted JS interpolation |

See [SECURITY.md](SECURITY.md) for the full threat model, including the honest residual limits of the in-process sandboxes.

---

## Acceptance tests

Before declaring "done", all 8 must pass on a real S25:

1. **Offline boot.** Airplane mode on → open Vorv (local model downloaded) → boots, loads memory, Chat accepts input, returns response. No network call.
2. **Multi-step tool loop.** Ask: "Read every file in my workspace, count lines per language, save report.csv, notify me." Agent: lists files → reads each → runs js_exec counter → writes CSV → posts notification. Each step visible in trace.
3. **Persistent memory.** Teach Vorv a fact in Memory tab. Kill app. Reopen. Ask "What was I taught?" — answer cites the stored memory ID.
4. **Cite-or-refuse.** Ask "What's the latest version of React?" Online: web_search and cite URL. Offline: `[uncited]` prefix on any guess. No uncited factual statements.
5. **Background task.** Start a 60-second task. Background the app. Wait 70 seconds. Notification fires. Reopen — task marked completed, log intact.
6. **Failure recovery.** Ask the agent to read a file that doesn't exist. It sees the error, tries a different path or asks for clarification. Does NOT claim success.
7. **Scheduled task.** "Remind me in 5 minutes to check the build." WorkManager job enqueued. 5 min later, notification fires (even if app killed).
8. **Verifiable log.** Open any past task in Tasks screen and expand it. Every tool call shows real input, real output, real duration — with "View full output" for long results. Nothing paraphrased or invented.

**Status:** 2 of 8 passed on-device (2026-11-07/08 night session): #1 offline boot, #2 multi-step loop on the cloud path — the local-fallback run completed the loop but not the task (defects + receipts in [ACCEPTANCE_LOG.md](ACCEPTANCE_LOG.md)). #3–#8 pending; the exact prompts for the next session are in the log too.

---

## Anti-features (intentionally not present)

- No telemetry, analytics, or tracking of any kind.
- No internet requirement for local mode (after the one-time model download).
- No bundling of any model over 1GB without disclosing.
- No proprietary dependencies. See [NOTICES.md](NOTICES.md) for the full list.
- No reusing any existing agent framework's name or branding.

---

## Build

GitHub Actions on every push to `main`, `feature/**`, `fix/**`:
1. **test** — unit tests (JUnit + Robolectric) + the Python sandbox self-test.
2. **build** — debug APK + R8-minified unsigned release APK (7-day artifacts).
3. **publish** (main only) — the rolling **`ci-latest` pre-release** is deleted and rebuilt with `app-debug.apk` (debug-signed — the honest installable build) and `app-release-unsigned.apk`, and the step hard-fails if either asset is missing. A separately **signed** release is published only when the `RELEASE_KEYSTORE_*` secrets are configured (see DEVELOPMENT.md).

Local build (JDK 17, Android SDK 35, Python 3.11 for Chaquopy):
```
cd Stupid-agent
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk

./gradlew test          # unit tests
python3 app/src/main/python/test_vorv_sandbox.py   # sandbox self-test
```

See [DEVELOPMENT.md](DEVELOPMENT.md) for the full environment setup, release-signing secret configuration, and how to add a tool.

---

## Security

See [SECURITY.md](SECURITY.md) — threat model, per-tool sandbox guarantees and residual limits, and how to report a vulnerability.

---

## License

Source code: MIT (see [LICENSE](LICENSE)).
Bundled dependencies: see [NOTICES.md](NOTICES.md) for their respective licenses.
