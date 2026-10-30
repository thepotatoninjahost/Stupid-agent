# Vorv

**Local-first autonomous agent for Samsung Galaxy S25 (Android 15+).**

Vorv is an Android app that does real multi-step work — it plans, picks a tool, executes, observes the actual result, picks the next tool, repeats. Not a chatbot. Not a wrapper around a single LLM call.

---

## Name

**Vorv** — coined word, no existing meaning in English or any other language searched. Searched Google Play, GitHub, and Google web search — zero agent/AI/android products use this name. applicationId `host.vorv`.

If you find another product using "Vorv", file an issue. The name was chosen to be unique.

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                     Vorv App (Kotlin)                    │
├─────────────────────────────────────────────────────────┤
│  UI (Jetpack Compose, 6 screens, dark theme)            │
│   Chat · Tasks · Memory · Tools · Files · Setup          │
├─────────────────────────────────────────────────────────┤
│  AgentViewModel (StateFlow, single source of truth)     │
├─────────────────────────────────────────────────────────┤
│  CognitiveKernel — ReAct loop                            │
│   perceive → plan → tool → observe → learn               │
│   Max 20 iterations per task. Every iteration logged.   │
├─────────────────────────────────────────────────────────┤
│  LLM (LlmRouter picks)        │   Tool Registry         │
│   ├─ Local: MLC LLM (pending) │   ├─ memory_write       │
│   └─ Cloud: GLM-4.6 (optional)│   ├─ memory_recall (FTS)│
│                                │   ├─ js_exec (Rhino)    │
│                                │   ├─ file_read (SAF)   │
│                                │   ├─ file_write (SAF)  │
│                                │   ├─ file_list (SAF)   │
│                                │   └─ [pending: python,  │
│                                │       shell, web, etc] │
├─────────────────────────────────────────────────────────┤
│  Persistence                                              │
│   Room (episodes, knowledge, skills, tasks, tool_calls, │
│         artifacts)                                        │
│   DataStore (API key, workspace URI, LLM mode)           │
│   SAF DocumentFile (user-granted workspace)              │
├─────────────────────────────────────────────────────────┤
│  Foreground service (AgentService)                       │
│   Keeps long tasks alive when app backgrounded           │
└─────────────────────────────────────────────────────────┘
```

---

## Setup

### Install

1. Download the latest APK from the [Releases page](https://github.com/thepotatoninjahost/Stupid-agent/releases).
2. On your S25: tap the APK → Settings → allow install from Chrome.
3. Open **Vorv**.

### Grant a workspace (optional, needed for file tools)

1. Open the **Files** tab.
2. Tap **Grant workspace access**.
3. Pick a folder. Vorv can read/write/list files in this folder — nowhere else.
4. Permission persists across reboots.

### Enable cloud LLM (optional)

Default mode is **local** (offline). Local LLM is currently a stub (MLC integration pending).

To use cloud mode with GLM-4.6:
1. Get a free API key at `z.ai/manage-apikey`.
2. Open the **Setup** tab.
3. Tap the **Cloud (GLM-4.6)** chip.
4. Paste your key → **Save Key**.

Key is stored locally with Android DataStore. Never uploaded, never committed to git, never logged.

### Install Termux for shell access (optional, used by `shell` tool when implemented)

1. Install [Termux](https://github.com/termux/termux-app/releases) from F-Droid or GitHub releases (Play Store version is deprecated).
2. Open Termux once to initialize.
3. Vorv's `shell` tool (pending) will detect it and use `RUN_COMMAND` intent.

---

## Acceptance tests

Before declaring "done", all 8 must pass on a real S25:

1. **Offline boot.** Airplane mode on → open Vorv → boots, loads memory, Chat accepts input, returns response. No network call.
2. **Multi-step tool loop.** Ask: "Read every file in my workspace, count lines per language, save report.csv, notify me." Agent: lists files → reads each → runs js_exec counter → writes CSV → posts notification. Each step visible in trace.
3. **Persistent memory.** Teach Vorv a fact in Memory tab. Kill app. Reopen. Ask "What was I taught?" — answer cites the stored memory ID.
4. **Cite-or-refuse.** Ask "What's the latest version of React?" Online: web_search and cite URL. Offline: `[uncited]` prefix on any guess. No uncited factual statements.
5. **Background task.** Start a 60-second task. Background the app. Wait 70 seconds. Notification fires. Reopen — task marked completed, log intact.
6. **Failure recovery.** Ask the agent to read a file that doesn't exist. It sees the error, tries a different path or asks for clarification. Does NOT claim success.
7. **Scheduled task.** "Remind me in 5 minutes to check the build." WorkManager job enqueued. 5 min later, notification fires (even if app killed).
8. **Verifiable log.** Open any past task in Tasks screen. Every tool call shows real input, real output, real duration. Nothing paraphrased or invented.

**Status as of latest push:**
- ✅ Skeleton complete — app boots, navigates, builds.
- ⏳ Real cognitive loop wired — but tools beyond memory/js/file are still pending.
- ⏳ Local LLM is a stub — cloud mode (GLM-4.6) works if you add a key.
- ❌ Acceptance tests #1-#8 NOT yet verified — user must run on real device.

---

## What's NOT done yet (honest status)

- Local LLM (MLC LLM Android integration) — currently returns a stub message. Cloud mode works.
- `python_exec` — Chaquopy integration pending.
- `shell` — Termux intent pending.
- `web_search` / `web_fetch` — pending (Z.ai has the function, not wired up yet).
- `browser_automate` — pending.
- `notify` / `schedule` — pending (foreground service exists, but tools that fire notifications not yet wired).
- Citation enforcement in UI is a chip rendering; doesn't yet block uncited facts.
- WorkManager — declared as dependency but no scheduled task job yet.

These come in subsequent pushes. Each push triggers a fresh APK build via GitHub Actions.

---

## Anti-features (intentionally not present)

- No telemetry, analytics, or tracking of any kind.
- No internet requirement for default local mode.
- No bundling of any model over 1GB without disclosing.
- No proprietary dependencies. See `NOTICES.md` for the full list.
- No reusing any existing agent framework's name or branding.

---

## Build

GitHub Actions workflow on every push to `main`:
- ubuntu-latest, JDK 17, Gradle 8.9
- Preinstalled Android SDK (compileSdk 35, minSdk 31)
- Produces debug APK
- Publishes as a GitHub Release with tag `v<version>-<short_sha>`

Local build (if you have Android Studio or cmdline-tools):
```
cd stupid-agent
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

---

## License

Source code: MIT (see `LICENSE`).
Bundled dependencies: see `NOTICES.md` for their respective licenses.
