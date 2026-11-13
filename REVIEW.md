# Vorv (`thepotatoninjahost/Stupid-agent`) — independent code & capability review

Reviewed commit: `b625e54` (2026-11-13). Reviewer: Lemon Code (AI agent).
Scope: the whole repository, read in full for the Kotlin/Android app, build
config, CI, resources and Python sandbox. No code was changed by this review;
this file is the only addition.

---

## 1. What the repository actually is

An Android app (`host.vorv`, version 0.8.0, versionCode 37, minSdk 31,
targetSdk 35, compileSdk 37) that runs an on-device ReAct agent loop:

- **Kernel** — `kernel/CognitiveKernel.kt` (813 lines): 20-iteration
  perceive → pick tool → observe loop, per-tool timeouts and per-task rate
  limits, schema validation of model-produced arguments, history compaction
  to the engine's token budget, retry-loop backstop, citation gate.
- **Tools** — 19 dispatchable classes: shell (via Termux `RUN_COMMAND`),
  `python_exec` (Chaquopy + sandbox), `js_exec` (Rhino), web search/fetch,
  headless browser automation, file read/write/list over SAF, memory, schedule,
  notify, app intents, dialing, `self_fix`.
- **LLM** — `llm/`: local GenieX (Qualcomm Hexagon NPU, Qwen2.5-Coder 3B/7B)
  plus a cloud (Z.ai) fallback engine.
- **Self-healing** — `healer/` + `watchdog/`: LLM writes a complete
  replacement for one class, it is compiled **on the device** in Termux
  (`kotlinc` + `d8`/`dx`), verified to keep the full public API, signed with a
  device-local Ed25519/ECDSA key, gated by an explicit user consent dialog,
  loaded at next boot through `DexClassLoader`, and auto-rolled back if the
  app crashes in that class within 72 h.
- **Memory** — Room DB (`vorv.db`, schema v4 with hand-written, tested
  migrations), FTS4 mirror, a knowledge graph (nodes/edges/salience) and a
  single-operator profile.
- **Watchdog** — separate `:watchdog` process, binder-death + heartbeat + pid
  detection, diagnostics capture, relaunch with a 5-per-10-min circuit breaker.
- **Tests** — 43 unit-test files / ~6 200 lines (JUnit, Robolectric, Room
  migration test, MockWebServer) plus a Python sandbox self-test.

Rough size: ~41 000 lines. Quality of the code itself is high: comments
explain *why* (audit findings, field recordings), errors are surfaced rather
than swallowed, and failures fail closed (consent bridge missing → tool
refuses; signature missing → patch quarantined; unknown class → not patchable).

---

## 2. Verification performed in this environment

Environment built for this review: Temurin JDK 17, Android SDK platform
`android-37.0` + build-tools `37.0.0`, Gradle 9.8.2 from the pinned wrapper.

| Check | Result |
|---|---|
| `python3 app/src/main/python/test_vorv_sandbox.py` | **PASS** — `ALL SANDBOX TESTS PASSED` |
| Kotlin **main** sources compile (`:app:compileDebugKotlin`) | **PASS** — warnings only (unnecessary `!!`, deprecated Rhino/WebView setters) |
| `./gradlew tasks` (project configuration) | **FAILS** — see F-1 |
| `./gradlew testDebugUnitTest` | run attempted; Gradle process died during `:app:compileDebugUnitTestKotlin` (F-2) |
| On-device behaviour (NPU, Termux, SAF, hot-patch) | **not verifiable here** — no Android device, no Qualcomm NPU, no Termux |
| Release build / R8 / APK signing | not run (release keystore is CI-only) |

Everything below marked "verified here" was reproduced in this environment;
everything else is from reading the code.

---

## 3. Findings — build & repository

### F-1 (High for maintainability) Project configuration breaks `./gradlew tasks`
```
Execution failed for task ':tasks'.
> Could not create task ':app:generateReleaseComposePreviewRunfiles'.
  > Compose Preview failed for ':app' (variant 'release'): Unit tests are
    disabled for this variant (enableUnitTest = false in beforeVariants).
    Compose Preview requires unit tests to package Android XML resources ...
```
Verified here at the pinned AGP 9.5.1 / Compose 2026.09.00 stack. The project
enables Compose, and the release variant has no unit tests, so AGP's Compose
Preview task cannot be created. `test` and `assembleDebug` do not hit this
path, which is why CI is green — but any `gradlew tasks`, IDE sync or
`gradlew build`/`lint` variant that materializes all tasks fails.
Fix: disable preview runfiles for the release variant, or enable release unit
tests, e.g.

```kotlin
androidComponents {
    onVariants(selector().withBuildType("release")) { v ->
        v.composePreviewRunfilesEnabled = false   // or enable release unit tests
    }
}
```

### F-2 Test run could not be completed here
`testDebugUnitTest` reached `:app:compileDebugUnitTestKotlin` and the Gradle
process was killed (no test results produced). Not yet diagnosed; it is an
environment/OOM-shaped failure, not a reported test failure. Re-run on a
machine with more headroom (`org.gradle.jvmargs=-Xmx6g`) before treating the
suite as green.

### F-3 (High for the project) The documentation was gutted in the last five commits
`README.md`, `SECURITY.md`, `CONTRIBUTING.md`, `DEVELOPMENT.md` and
`NOTICES.md` are **1 byte each** (`263853d`, `7760748`, `734ba6e`, `2f065d7`,
`b625e54`). Consequences:

- The app's own UI still tells the user *"See SECURITY.md in the repository for
  the full threat model"* (`ui/screen/SettingsScreen.kt:559`) — a dead pointer.
- `AndroidManifest.xml:28`, `res/xml/backup_rules.xml` and
  `res/xml/data_extraction_rules.xml` all justify their configuration with
  "SECURITY.md promises…" / "README/SECURITY.md already claimed…". The
  promise no longer exists in the repo.
- No install/build/run instructions exist for a user or a new contributor.
- Removing `NOTICES.md` drops the third-party attribution for Apache-2.0
  (OkHttp, AndroidX), MIT (jsoup) and MPL-2.0 (Rhino) dependencies. Apache-2.0
  in particular requires the NOTICE to travel with distributions.
This is the single biggest gap for anyone else (or future you) picking the
project up.

### F-4 (Medium) The build writes into the source tree
`tasks.register<Sync>("syncSourceMirror")` copies `src/main/java` into
`src/main/assets/source` on every `preBuild`, i.e. **inside the tracked
source set**. Every build can dirty the working tree, invalidates Gradle's
up-to-date checks, and makes "what changed" depend on whether a build ran.
The mirror should be generated into `build/generated/...` and registered as a
generated asset directory (the same mechanism already used for
`healer/app_classes.jar` in this file).

### F-5 (Medium, supply chain) Mirrors pinned first
- `settings.gradle.kts` puts `maven-central.storage-download.googleapis.com`
  ahead of `google()`/`mavenCentral()` as a *temporary* workaround.
- `gradle/wrapper/gradle-wrapper.properties` downloads Gradle from
  `mirrors.cloud.tencent.com`.
The wrapper hash is pinned (`distributionSha256Sum`), which is right, and the
Maven mirror is Google-hosted. Still: a build that resolves all of Maven
Central through one intermediary is a single point of trust and of failure.
Put the mirror *after* `mavenCentral()` (or scope it), and drop it once the
CDN issue is over.

### F-6 (Low) `patchkeys/` is outside the backup exclusions
`backup_rules.xml` / `data_extraction_rules.xml` exclude `vorv.db`,
`vorv_secrets.xml` and the external crash/healer dirs — good. They do **not**
exclude `filesDir/patchkeys/`, which holds the software-fallback **private**
patch-signing key (`PatchKeys.trySoftwareKeys`), nor `filesDir/hotpatch/`.
Add `<exclude domain="file" path="patchkeys/" />` (and `hotpatch/`).

### F-7 (Low) Committed debug keystore — acceptable, documented
`app/vorv-debug.keystore` with password `android` is committed on purpose so
every build is signature-compatible with the sideloaded app. That reasoning is
sound for a debug key (it authenticates nothing). Make sure the *release*
keystore never follows it into the repo — it currently does not.

---

## 4. Findings — code

### C-1 (Low) Unescaped string interpolation into tool-result JSON
`kernel/CognitiveKernel.kt` builds tool messages as
`Llm.Message("tool", "{\"error\":\"$errMsg\"}", …)`. `errMsg` can contain
quotes, backslashes or newlines (a model name, a tool error). The next LLM
call then receives malformed JSON. Use `JsonObject` like the success path does.

### C-2 (Low) Off-by-one in persisted-output file names
`persistFullOutput(taskId, iterations, tc.name, result.output, toolCallCount + 1)`
is called *after* `toolCallCount++`, so the first call is index 2. Harmless
(no collisions) but the comment says the index is the call index.

### C-3 (Medium, design) Patch "verification" is API-shape only
`PatchVerifier` proves the patched class keeps every public constructor,
method and field and still implements its dispatch interface. It proves
nothing about behaviour — no test runs against the patched class. Combined
with the fact that a patch is arbitrary in-process code generated by a 3B/7B
model from untrusted text (crash traces, tool output), the only real gate is
the user reading a one-paragraph description and tapping APPROVE. The 72 h
crash-probation rollback is the right mitigation and partially covers it;
still, the consent dialog shows no diff and no source, so "approve" is a leap
of faith. Recommended: show the generated Kotlin (or a unified diff against
the shipped file) in the consent sheet, and keep the original file for diffing.

### C-4 (Medium, design) The signing key is not a trust boundary against the app itself
`PatchKeys` signs with a device-generated key whose private half lives in the
Android Keystore (or `filesDir` fallback). This correctly stops *other apps*
from dropping a dex. It cannot stop anything running in Vorv's own process
(the tool layer itself can sign). That is fine for the stated single-user
threat model — just do not describe it as "proof of origin" for the user's
benefit; the honest description is "refuses unsigned/unlisted dex files".

Also: if the Keystore backend ever becomes unavailable, `trySoftwareKeys`
generates a *new* keypair, and every previously approved patch then fails
verification and is silently quarantined. Worth a user-visible log line, or
keeping a key-generation id in the manifest.

### C-5 (Low) Watchdog relaunch path runs off the main thread
`onMainProcessDied` is invoked from a binder death recipient and calls
`startActivity(...)` after `Thread.sleep`. It works, but the sequencing is not
a documented Android guarantee; posting the relaunch to the service's main
handler is the safe form. The relaunch circuit breaker (5 per 10 min) is
correctly in place.

### C-6 (Low) `HotPatcher.registerApprovedPatch` trusts the `sha256` argument
It verifies the **signature** over the real bytes (the load-bearing gate), but
stores whatever `sha256` string the caller passed. If the two ever diverge,
the patch is quarantined at boot with a confusing "SHA-256 mismatch" — the
parameter should be computed locally from `dexBytes`.

### C-7 (Info) Room schema and migrations agree
`MIGRATION_1_2`, `2_3`, `3_4` were compared column-by-column, index-by-index
against the entity definitions: they match, and there is no
`fallbackToDestructiveMigration` anywhere. That is the right call for an app
whose whole value is its accumulated memory.

---

## 5. Security posture (summary)

Solid, and unusually well-reasoned for a hobby-scale agent app:

- **SSRF**: `NetworkGuard` blocks loopback/link-local/metadata hosts and
  suffixes, resolves through a validating `Dns`, checks every redirect hop.
- **Path handling**: one `SafPaths.rawWorkspacePath` implementation, no
  duplicate pre-buggy copies left; `ShellQuote.sh` used on every path that
  reaches a bash command (the H-1 injection fix is real and tested).
- **Command execution**: base64 transport into Termux `RUN_COMMAND`, output
  captured through files, stale-file sweep, per-tool timeouts, consent bridge.
- **JS/Python**: Rhino with restricted class access and a 5 s watchdog;
  Chaquopy with an audit hook, restricted builtins, import allow-list and a
  trace-based timeout. The Python self-test passes here.
- **Secrets**: API key in `EncryptedSharedPreferences` (Keystore master key),
  plaintext legacy copy migrated and erased; backup exclusions present.
- **Network egress**: no cleartext HTTP anywhere in the Kotlin sources; no
  remote fetch of code or patch manifests.
- **CI**: all third-party actions SHA-pinned, per-job `permissions`, fork PRs
  cannot write, `pull_request` (not `pull_request_target`).
- **Manifest**: only `MainActivity` exported; both services `exported=false`
  with honest `specialUse` subtypes.

Residual risks are the ones listed above (C-3, C-4, F-5, F-6) plus the
inherent one: an agent that can run arbitrary shell commands and patch its own
code is, by design, equivalent to a remote-code-execution interface into the
device. The consent gates and the watchdog/rollback machinery are the correct
control; they should never be relaxed for convenience.

---

## 6. Capability assessment against the four stated goals

Measured against what the code actually does today, not against the docs.

### "A human-like brain"
**Not implemented — this is an agent loop, not a mind.** What exists: a
20-step ReAct loop with a strong system prompt (12–13 numbered honesty rules),
tool schemas, citation tracking and a retry backstop. There is no planner, no
self-critique pass, no working memory beyond the conversation, no goal
decomposition across tasks, no reflection loop, no world model. The local
model is Qwen2.5-Coder 3B/7B — a *coding* model, chosen because code is what
the self-healer needs. Its 4096-token context is small enough that the kernel
has to compact history mid-task. Calling this "human-like" is the single
largest gap between the promise and the artifact.

### "Write any code I ask it to"
**Partially.** It can call `shell`, `python_exec`, `js_exec` and `file_write`
to produce code and run it, and the on-device coder model writes Kotlin for
the healer. But the model runs on a phone NPU with a few thousand tokens of
context; it will not produce a large, coherent, multi-file app in one pass,
and there is no compile-and-fix loop for *user* code (only for its own
patches, and only inside Termux).

### "Build any app I ask it to build"
**Not implemented.** There is no path in this repository that builds a
third-party app: no on-device Gradle/`aapt2`/`d8` packaging pipeline for user
projects, no project scaffolding, no signing of user APKs, no install step.
The only on-device compilation that exists is `kotlinc` + `d8` inside Termux,
for **one class of this app**. If "build any app" is a real requirement, it is
a new subsystem (project generator + build backend + signing + install via
`ACTION_VIEW`/SAF), not a tweak.

### "Fix and modify itself"
**Implemented, with real engineering behind it** — and this is the most
impressive part of the repo: LLM → on-device compile → API-surface verify →
sign → consent → hot-patch → probation rollback. Limits, all honest and
visible in the code: (a) only 19 `DISPATCH_BASES` classes can be patched;
(b) requires Termux + `kotlinc` + `d8` + a SAF workspace grant + a downloaded
coder model; (c) one class per attempt, 3 attempts, 15-minute budget;
(d) takes effect only after a restart; (e) a release build's R8 output is
kept unminified for these classes by `proguard-rules.pro`, which is correct.
It is a genuine self-modification path, not a slide deck.

### "Learn and evolve"
**Implemented as accumulating data, not as learning.** It stores episodes,
tool calls, knowledge entries (+FTS), a knowledge graph with salience decay
and an operator profile, and updates per-skill success/fail counters. That is
memory and statistics. There is no weight update, no fine-tuning, no policy
change from outcomes — "evolve" in the sense of the model getting better at
its job over time does **not** happen. The honest label is "persistent,
associative memory with usage tracking".

**Bottom line:** self-repair/modification is real and well-engineered; memory
is real; "human-like brain", "build any app" and "evolve" are aspirational and
would each be a substantial new subsystem.

---

## 7. What to do first (ordered)

1. **Restore the documentation** (F-3): README with build/install/run steps,
   SECURITY.md with the threat model the UI and XML files already reference,
   NOTICES.md with third-party licences. Nothing else in this list matters if
   the project cannot be understood or legally distributed.
2. **Fix the release-variant configuration failure** (F-1) so `gradlew tasks`,
   IDE sync and `gradlew build` work.
3. **Finish the test run on a machine with more memory** (F-2) and treat the
   suite as the regression gate; then wire `test` into the default CI path it
   already has.
4. **Move the generated source mirror out of `src/`** (F-4).
5. **Patch-key backup exclusion** (F-6) and the small code fixes (C-1, C-2,
   C-6).
6. **Show the generated code/diff in the patch consent dialog** (C-3) — the
   cheapest real improvement to the self-modification safety story.
7. Then, and only then, decide whether "build any app" and "learn/evolve" are
   new subsystems you want to specify.
