# Status of this review (updated 0.9.1, commit b068f70)

| Finding | State |
|---|---|
| F-1 loader in app storage | fixed 0.8.9 (fb1617f) |
| F-2 guest timeout could not fire | fixed 0.9.0 |
| F-3 foreground-service lifecycle | fixed 0.9.0 |
| F-4 a 404 ended the cloud chain | fixed 0.9.0, test added |
| F-5 payload absent from every APK | fixed 0.9.0 — build gate + APK gate. The CI provisioning *step* is in `tools/ci/workflow-payload-step.patch`: this account's token has no `workflow` scope, so GitHub refuses the push. |
| F-6 Termux in release notes | replacement text is in the same patch — same block |
| F-7 unused PythonEngine | fixed 0.9.1 (class removed, delegation documented) |
| F-8 repair() cannot work | fixed 0.9.1 (`payloadReport()`, inventory only) |
| F-9 test enshrined F-1 | fixed 0.8.9 |
| F-10/F-14 outdated Setup text | fixed 0.9.1 |
| F-11 failing write during error handling | fixed 0.9.1 |
| F-12 unbounded crash-log read | fixed 0.9.1 |
| F-13 Rhino cannot be interrupted | documented in place; not fixed by design |

Everything claimed fixed is verified by build, unit tests or an APK inspection.
Nothing here has been verified on the phone: Setup → Linux System → TEST is the
check that matters for the guest, and it now names the stage that fails.

# Vorv — full project code review (2026-11-13)

Scope: every tracked file in `thepotatoninjahost/Stupid-agent` at `944381c`.
Method: read the file, trace the input to the effect, and where the defect can be
reproduced without a phone, prove it with a test that fails. Where a claim needs
hardware (Android install, proot on a Samsung S25, GenieX), it is marked
**not verified — needs the phone** rather than assumed either way.

This is the *correctness* review the owner asked for ("review every line in every
file … everything and anything that is not correct"). Security findings live in
`SECURITY.md` / prior audits; nothing here duplicates a closed item.

---

## 1. What to fix first

Ordered by how much damage the defect does to the owner's actual goal — a phone
that runs its own code, with no cloud account, and does not crash.

| # | Defect | Why it matters right now |
|---|--------|--------------------------|
| **1** | **Linux cannot start: `PROOT_LOADER` points at a copy in app storage** (F-1) | This is the reason every build since 0.8.0 says "Linux system not ready". Android 10+ (Vorv targets 35) refuses to execute a file the app itself wrote into `/data/data/...`. The loader must stay where the installer put it. One-line-class fix, testable on the phone in 30 seconds. |
| **2** | **A hung Linux command hangs Vorv forever** (F-2) | The timeout is checked *after* the output is fully read, so a command that never exits never times out. Any stuck `shell` call freezes the task, the notification and the app until Android kills it. |
| **3** | **Foreground-service start/stop is unguarded** (F-3) | `stop()` uses `startService` (background-launch crash) and `start()` throws if the app is backgrounded at that instant. Backgrounding the app mid-task — the normal way to use it — can crash the process. No `try` anywhere. |
| **4** | **One unknown model id kills the whole cloud chain** (F-4) | On HTTP 404 the chain gives up instead of trying the next model, so a single retired/renamed model id makes *every* GLM look "unavailable". Proven with a test in this pass. |
| **5** | **The computer is not in the repository, and CI publishes a build without it** (F-5) | `jniLibs/`, `assets/forge/jvm`, `assets/forge/tools`, `assets/forge/linux` are git-ignored and CI never provisions them, so the APK built by CI (and the `ci-latest` release) has no JRE, no rootfs, no proot. It installs, chats, and can never run a program — while looking like an official download. |

F-1 and F-2 are the same subsystem and should be fixed and shipped together.

---

## 2. Coverage

Repository files inventoried with `git ls-files` (297 tracked files at
`944381c`; the 370 MB payload is not among them — see F-5).
`app/src/main/java` is 98 Kotlin files / 18,616 lines, mirrored at build time into
`app/src/main/assets/source` (verified byte-identical with `diff -rq`, so the
in-app self-repair copy cannot drift silently).

Read in full (line by line, with the non-obvious invariants checked against the
call sites): every file in `host/vorv`, `host/vorv/kernel`, `host/vorv/llm`,
`host/vorv/tools`, `host/vorv/healer`, `host/vorv/watchdog`, `host/vorv/data`,
`host/vorv/knowledge`, `host/vorv/computer`, `host/vorv/forge`,
`host/vorv/linux`, `host/vorv/nativeapp`, `host/vorv/web`, `host/vorv/brain`,
`host/vorv/service`, `host/vorv/ui` (incl. all five screens), plus
`AndroidManifest.xml`, `app/build.gradle.kts`, `build.gradle.kts`,
`settings.gradle.kts`, `gradle/libs.versions.toml`, `proguard-rules.pro`,
`.github/workflows/build.yml`, `tools/forge/*`, `app/src/main/python/*`,
`app/src/main/assets/forge/{linux,README,licenses}` and the 60 test files.

Skipped, with the reason stated:
* `app/src/main/assets/forge/{jvm,tools}/**` — third-party binaries (JRE modules,
  d8, apksigner, kotlin-compiler). Inspected by size/entry list only; not source.
* `app/src/main/assets/forge/linux/rootfs.tar` — 518-entry Alpine rootfs; the
  extractor was reviewed and driven by `RootfsTarTest`, the payload itself is
  upstream.
* `app/src/main/jniLibs/arm64-v8a/*.so` — Termux/openjdk/proot prebuilts.
* `res/drawable*`, app icons, launcher XML — no logic.

---

## 3. Findings

Severity is about consequence for this app, not abstract purity.
`conf` = confidence in the finding itself.

| id | title | sev | conf | location |
|----|-------|-----|------|----------|
| F-1 | proot loader is copied to a directory Android will not execute | critical | high | `linux/LinuxSystem.kt:97-108`, `:196-206` |
| F-2 | Linux command timeout is applied only after a blocking full read | high | confirmed by code shape | `linux/LinuxSystem.kt:150-160` |
| F-3 | Foreground service start/stop unguarded; `stop()` uses `startService` | high | high | `service/AgentService.kt:47-51`, `AgentViewModel.kt:929,1046` |
| F-4 | HTTP 404 on one model aborts the whole cloud chain | high | **confirmed (test run)** | `llm/CloudChainLlm.kt` |
| F-5 | Payload absent from repo; CI publishes an APK with no computer | high | confirmed | `.gitignore:25-34`, `.github/workflows/build.yml` |
| F-6 | Release notes still tell the owner to install Termux | high (requirement) | confirmed | `.github/workflows/build.yml:330-333` |
| F-7 | `python_exec` engine is dead code — the computer runs JS only | medium | confirmed | `computer/PythonEngine.kt` (no callers) |
| F-8 | `repair()` can never succeed and never says why honestly | medium | high | `linux/LinuxPayload.kt:82-108` |
| F-9 | A test locks the wrong loader path in place | medium | confirmed | `test/.../LinuxSystemArgvTest.kt:74-75` |
| F-10 | Settings "About" describes an engine the app no longer has | medium | confirmed | `ui/screen/SettingsScreen.kt:568-583` |
| F-11 | `db.taskDao().fail()` inside `catch` is unguarded — can re-crash | medium | high | `AgentViewModel.kt:1036-1040` |
| F-12 | Crash-log read is unbounded before truncation | low | high | `tools/CrashLogsTool.kt:178` |
| F-13 | `js_exec` timeout leaves the runaway thread spinning | low | high | `computer/JsEngine.kt` |
| F-14 | Tool count in the About text (17) vs registry (24) | low | confirmed | `SettingsScreen.kt:571`, `AgentViewModel.kt:185-209` |

Requirement gaps, not bugs — recorded because they are what the owner actually
asked for and they are still open:

* **R-1** There is no reasoning engine that works with no key and no 1.8–4.4 GB
  download. `brain/CharBrain.kt` is a 6k-parameter character model; it cannot
  write or reason, and its own KDoc says so. With `llmMode=cloud` and no key the
  app politely refuses to work (`AgentViewModel.kt:886-907`). This is the
  "app that writes code by itself" requirement, still unmet.
* **R-2** The GenieX/Qwen and Z.ai/GLM model lists are still the app's brain and
  still shown in Setup; they were to be replaced by the custom brain.
* **R-3** Nothing built here has ever been run on the phone: proot+Alpine, the
  in-app `javac`→`d8`→`apksigner` build, installed-app launch and the crash
  repair path are all unproven on hardware. F-1 is the most likely reason the
  Linux half has never once worked for the owner.

---

## 4. Details, evidence, fix

### F-1 — proot cannot start: the loader is put where Android forbids execution  (critical)

`LinuxSystem.provision()` copies `libforge-proot-loader*.so` out of
`nativeLibraryDir` into `filesDir/linux/loader`, marks it executable, and
`guestEnv()` then hands proot:

```
"PROOT_LOADER" to File(loaderDir, "loader").absolutePath   // /data/user/0/host.vorv/files/linux/loader/loader
```

Evidence — `app/src/main/java/host/vorv/linux/LinuxSystem.kt:97-108`:

```kotlin
val candidates = listOf(
    File(nativeDir, "libforge-proot-$name.so"),        // the only executable place
    File(File(context.filesDir, "native"), name),
)
candidates.firstOrNull { it.isFile }?.copyTo(File(loaderDir, name), overwrite = true)
File(loaderDir, "loader").setExecutable(true)
```

Why it fails: since Android 10, an app with `targetSdk >= 29` (Vorv is 35) may
not `execve` a file in its own writable data directory — this is the same
restriction that forces Termux to stay on `targetSdk 28`. `setExecutable(true)`
sets a bit the kernel will ignore; proot execs its loader, gets `EACCES`/
`ENOEXEC`, and dies before the guest shell exists. `LinuxPayload.repair()`
demonstrates the app already knows the outcome — it writes the verdict
"app storage refuses to execute it" — yet the same broken location is what the
real run path uses.

Impact: the owner's number-one request ("the app must have its own Linux
computer") cannot work on any build, on any modern phone, regardless of payload.
Their report — "Still have no linux", Setup stuck at "not ready", a Termux tab
that spins forever — is exactly the symptom of proot dying at startup.

Fix: leave the loader where the installer put it (`nativeLibraryDir`) and use it
from there; keep the copy only as a last-resort diagnostic, never as the path
handed to proot.

```kotlin
// LinuxSystem.guestEnv(loaderDir, tmp) → guestEnv(loader: File, tmp: File)
internal fun guestEnv(loader: File, tmp: File): Map<String, String> = mapOf(
    "PROOT_LOADER" to loader.absolutePath,
    ...
)

// resolve it like proot itself, not like a data file:
val loader = sequenceOf(
    File(nativeDir, "libforge-proot-loader.so"),
    File(loaderDir, "loader"),           // only if a future Android allows it
).firstOrNull { it.isFile && it.canExecute() }
    ?: return@withContext Shell(-1, "", "this install has no executable proot loader " +
        "(Android did not unpack libforge-proot-loader.so) — reinstall the APK", true)
```

Also add to `diagnose()`: print `loader.canExecute()` and the first line of
proot's stderr, so the next failure names itself instead of showing "loading".

Verify on the phone: Setup → Linux → *Test system* should print
`alpine-release` and `uname -m`; if it still fails, the printed proot stderr is
the next evidence (it distinguishes a missing loader from a seccomp denial).

### F-2 — a hung command never times out  (high)

`linux/LinuxSystem.kt:150-160`:

```kotlin
val process = builder.redirectErrorStream(true).start()
val out = StringBuilder()
process.inputStream.bufferedReader().forEachLine { ... }   // blocks until the process closes stdout
if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {  // ← timeout evaluated here
```

The read loop runs on the calling thread and returns only at EOF, so an
interactive or stuck command (`sh` waiting on stdin, a runaway compiler, `sleep
inf`) blocks forever: the timeout line is never reached, the coroutine is not
cancellable (blocking IO has no cancellation point), and `ShellTool`'s own
`withTimeout` cannot interrupt it either. The task, the foreground notification
and the UI stay "working" until Android kills the app; the tool never returns an
error the model can react to.

The rest of the codebase already does this correctly, which is how the defect
was spotted — `forge/ForgeCompiler.kt:139`:

```kotlin
val t1 = Thread { drain(process.inputStream.bufferedReader(), out) }.apply { isDaemon = true; start() }
val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
```

Fix: drain on a daemon thread (with a bounded buffer), `waitFor(timeoutMs)` on
the calling thread, `destroyForcibly()` on expiry, then join the drainer with a
short grace period. Additionally close `process.outputStream` immediately so a
guest that reads stdin gets EOF instead of waiting.

A regression test is possible without Android by extracting the runner into a
pure function over `ProcessBuilder` — `ForgeCompiler`'s pattern is the model.

### F-3 — foreground service lifecycle is unprotected  (high)

`service/AgentService.kt:47-51`:

```kotlin
fun stop(ctx: Context) {
    ctx.startService(Intent(ctx, AgentService::class.java).apply { action = ACTION_STOP })
}
```

Two separate defects:

1. `stop` uses `startService`. From Android 8, starting a background service
   throws `IllegalStateException: Not allowed to start service Intent … app is in
   background`. The call site is the `finally` of the task coroutine
   (`AgentViewModel.kt:1046`), which runs after a long task — by then the owner
   has almost certainly switched away from Vorv. The exception is thrown inside
   `finally`, inside `viewModelScope.launch`: it aborts the rest of the cleanup
   (`refreshSessionGrants()` never runs) and, being uncaught, is handed to the
   default handler — a process crash at the worst possible moment (task just
   finished, results not yet fully settled). `ctx.stopService(...)` is the
   correct, always-allowed API here.
2. `AgentService.start(...)` (`:33-43`, called at `AgentViewModel.kt:929`)
   throws `ForegroundServiceStartNotAllowedException` on Android 12+ if the app
   is not in the foreground at that instant. It is called from a coroutine that
   begins executing slightly after the tap (DB insert first), so a fast app-switch
   races it.

Fix: `stop()` → `runCatching { ctx.stopService(intent) }`; `start()` → wrap in
`runCatching` and, on failure, continue the task without the notification (the
kernel does not need the service to work). Log the reason into the live trace
once, so "no notification" is never a mystery.

### F-4 — one 404 ends the cloud chain  (high, proven)

`CloudChainLlm` skips a model only when `LlmHttpException.overloaded` (429 / 5xx)
or `notEntitled` (402 / quota wording). Any other status is rethrown. Since model
ids are hardcoded (`"glm-4.7-flash"`, `"glm-5.3-flash"`, `"glm-5.3"`,
`"glm-4.6"`) and providers do rename and retire them, a 404 for the first model
makes the whole chain fail with "HTTP 404" instead of trying the free model that
is alive.

Proof, run in this pass (throwaway test, since deleted):

```
PROBE_RESULT=THREW: LlmHttpException: LLM HTTP 404: {"error":{"message":"model not found"}} requests=1
BUILD SUCCESSFUL in 29s
```

One request, no fallback attempt. This is the most plausible mechanical cause of
the owner's "none of the GLMs are ever available": a single stale id hides every
working one.

Fix: add a third skip class — `unknownModel = code == 404 || (code == 400 && body
contains "model")` — and record it in `lastOverloadReason` as
"model id not recognised by the provider". Keep 401/403/400(non-model) fatal, as
the existing tests require. Add the regression test next to
`CloudChainLlmTest.a rejected key is not retried on another model`:

```kotlin
@Test fun `an unknown model id falls over to the next model`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(404)
        .setBody("""{"error":{"message":"model not found"}}"""))
    server.enqueue(reply("free model answered"))
    val chain = chain(CloudModel.GLM_5_3, CloudModel.GLM_4_7_FLASH)
    val r = chain.step(messages, emptyList())
    assertEquals("free model answered", (r as Llm.LlmResponse.Text).content)
    assertEquals("GLM-4.7-Flash", chain.lastModelUsed)
}
```

### F-5 — the computer is not in the repo, and CI ships a build without it  (high)

`.gitignore` lines 25-34 exclude `/app/src/main/jniLibs/`,
`/app/src/main/assets/forge/jvm/`, `/app/src/main/assets/forge/tools/` and
`/app/src/main/assets/forge/linux/` — that is proot, its loader, the JRE
modules, d8/apksigner/kotlinc and the Alpine rootfs, i.e. the whole "own
computer". `git ls-files app/src/main/jniLibs` → 0.

`.github/workflows/build.yml` never runs `tools/forge/fetch-*.sh`, so:
* CI's APK is a valid app with **no JRE, no rootfs, no proot**;
* the rolling `ci-latest` release publishes that APK under names a user will
  reasonably download (`app-debug.apk`, `app-release-unsigned.apk`);
* the `publish` job, when `RELEASE_KEYSTORE_*` is set, would attach a payload-less
  build to the release tag it derives from `versionName` — competing with the
  full APK the owner actually installs.

Impact: two AIs and one person can each download a different "Vorv 0.8.x" that
behave completely differently, which is precisely the inconsistency the owner
complained about.

Fix (pick one, and make CI fail if the payload is missing):
1. **Preferred**: add a `Provision toolchain` step to the workflow that runs
   `tools/forge/fetch-runtime.sh && tools/forge/fetch-linux.sh &&
   tools/forge/fetch-maven.py` (all pinned by `runtime-manifest.json`) before
   `assemble`, and a `preBuild` assertion that the four payload roots exist for
   release variants — the build fails loudly instead of shipping a hollow APK.
2. Label the CI artifacts unambiguously, e.g. `vorv-ci-thin-<sha>-NO-LINUX.apk`,
   and state in the notes that it has no computer.

Either way, `ci-latest` must not look like a distributable Vorv.

### F-6 — release notes still instruct the owner to install Termux  (high, requirement)

`.github/workflows/build.yml:330-333`:

```
**First run:** Open Vorv → Files tab → grant a workspace folder → Setup tab → set LLM mode …
**Shell tool setup (optional):** install Termux (F-Droid/GitHub), run `termux-setup-storage` once, and grant Vorv the "Run commands in Termux" permission.
```

Termux was removed from the app in 0.8.x (commits `f3e721a`, `657a45c`). These
notes are generated for every release and directly contradict the owner's
standing instruction. Fix: delete the second line; replace it with "Setup → Linux
System → Test" so first-run guidance matches the app.

### F-7 — `python_exec` has an engine that is never constructed  (medium)

`computer/PythonEngine.kt` is a clean adapter, but
`grep -rn "PythonEngine(" app/src` returns only its own declaration.
`ComputerTool` (`:73`) builds `Computer(home, listOf(JsEngine()), …)`. So the
"computer" executes JavaScript only; the Python half of the promise is inert
code, and `Computer`'s capability list in the tool schema should say so until it
is wired.

Fix: inject the Chaquopy-backed engine at the call site (Chaquopy is already a
dependency and initialised by the Application), or remove the class and stop
advertising Python in the tool description.

### F-8 — `repair()` cannot work and its wording hides that  (medium)

`LinuxPayload.repair()` copies proot to `filesDir/native`, sets the exec bit and
runs it. On `targetSdk >= 29` that exec is denied, always — the method's own
comment says as much. The user-visible effect is a "Repair" button that can only
ever produce "app storage refuses to execute it". Making the failure honest is
good; offering it as a repair is not.

Fix: drop the exec probe from `repair()` and make it a pure *inventory check*
(is the binary in the APK? did the installer unpack it?), with the remedy text
keyed to the answer ("reinstall the APK" vs "unpack failed for ABI x"). Once F-1
lands, the loader lives in `nativeLibraryDir` and this class stops being on the
critical path.

### F-9 — a test enshrines the F-1 bug  (medium)

`app/src/test/java/host/vorv/linux/LinuxSystemArgvTest.kt:74-75`:

```kotlin
val env = LinuxSystem.guestEnv(File("/data/user/0/host.vorv/files/linux/loader"), tmp)
assertEquals("/data/user/0/host.vorv/files/linux/loader/loader", env["PROOT_LOADER"])
```

The test asserts the loader is read from app data — the exact thing Android
forbids. It passes, so the suite actively certifies the broken behaviour. Fix it
with F-1 (assert the path handed in is returned verbatim, and add a case pinning
`nativeLibraryDir` as the production source).

### F-10 / F-14 — the app's own description is out of date  (medium / low)

`SettingsScreen.kt:568-583` tells the owner:

* "17 real tools" — the registry (`AgentViewModel.kt:185-209`) has **24**;
* "Local engine: Qwen2.5-Coder via Qualcomm GenieX on the Hexagon NPU" and
  "Cloud engine: Z.ai GLM" — the components the owner asked to have replaced by
  the custom brain (R-2), still presented as the product;
* "shell runs inside Vorv's own Alpine Linux system" — untrue until F-1 is fixed.

This is the paragraph the owner reads to judge the app, and it is the one place
where every open gap is stated as if it were done.

Fix: generate the tool count from `tools.size`, and make the engine paragraph
describe what is actually selectable today, with one explicit line for what is
not yet available. Do not claim the Linux system works until it has been seen
working on the phone.

### F-11 — a failing insert during error handling can crash  (medium)

`AgentViewModel.kt:1036-1040`:

```kotlin
} catch (e: Exception) {
    ...
    db.taskDao().fail(task.id, e.message ?: "unknown")   // can itself throw
} finally {
```

`fail()` runs inside the catch of a coroutine with no outer guard. If Room is
unavailable (rare, but exactly the kind of thing that accompanies a crash) the
original error is replaced by a second, unexplained one and the task row is left
`in_progress` forever. The cancellation branch three lines above already does
this correctly with `runCatching { … }` inside `NonCancellable`.

Fix: `runCatching { db.taskDao().fail(task.id, e.message ?: "unknown") }`.

### F-12 — crash-log read is unbounded  (low)

`tools/CrashLogsTool.kt:178` does `target.readText()` and only then truncates to
50 000 chars. A native tombstone or a runaway `boot_trace` is read whole into
memory. The cap should be applied while reading (`bufferedReader().use { it.read(
MAX_READ_CHARS) }`). Path traversal is correctly guarded here — this is only about
size.

### F-13 — `js_exec` timeout leaks a spinning thread  (low)

`JsEngine` runs the script on a worker and `join(timeoutMs + 500)`; on timeout the
worker is abandoned. Rhino has no interrupt, so `while (true) { }` keeps a core
busy (and the battery draining) until the deadline observer happens to trip. The
unit test only asserts the *caller* returns. Acceptable as-is for short scripts,
worth a comment and a documented limit (the deadline factory does bound the loop
if it yields, but a tight loop need not).

---

## 5. What is correct, and verified so I do not "fix" it later

These were checked because they look like bugs and are not:

* **Crash honesty.** `DeathEvidence.decide` requires real evidence (OS exit
  reason naming CRASH/ANR at/after the previous boot, a Java crash file written
  during that boot, or a watchdog death). A boot that merely lacks
  `BOOT_COMPLETED` is *not* treated as a crash — which is why the app must not
  and does not claim one. The owner's "it never crashed" report is consistent
  with this code; the dialog wording is what needs care, not the logic.
* **Shell quoting.** `ShellQuote` + argv-based `ProcessBuilder` everywhere; no
  string-built command lines in the Linux path.
* **Path containment.** `SafPaths`, `ComputerHome`, `CrashLogsTool` and
  `RootfsTar` all canonicalise and reject escapes, including symlink targets.
* **Patch signing.** `HotPatcher` refuses unsigned dex, verifies Ed25519/ECDSA
  over the exact bytes, and requires an explicit consent entry. `PatchVerifier`
  loads candidates in an isolated loader and requires the public constructors to
  exist before use. This is the one part of self-modification that is genuinely
  safe.
* **Secrets.** `EncryptedSharedPreferences` with a `MasterKey`, no secret ever
  logged; `Llm.kt` redacts the Authorization header on failure.
* **Budgeting.** Kernel `MAX_ITERATIONS = 20`, per-tool per-task rate limits
  charged only after a successful parse, consecutive-failure breaker, and
  `HealingAgent`'s compile/repair budgets are all enforced where they are
  claimed.
* **Persistence.** Room v4 with explicit migrations (`MIGRATION_2_3`,
  `MIGRATION_3_4`) and retention pruning that never touches taught memories.
* **JS sandbox.** Rhino `ClassShutter` refuses every class, `Packages` is
  removed, output is line- and byte-capped.

---

## 6. Not verified (needs the phone)

1. F-1's fix: whether `PROOT_LOADER` in `nativeLibraryDir` is enough for this
   Termux proot build on `targetSdk 35`, or whether the guest binaries in
   `filesDir/linux/rootfs` also need the loader trick (`PROOT_NO_SECCOMP` is
   already set). The diagnostic in `diagnose()` will name whichever restriction
   remains.
2. Everything GenieX/NPU: the model list shows GenieX models but no build here
   has ever produced a token from them.
3. The in-app APK build (`javac` → `d8` → `apksigner` on 121 MB of toolchain
   jars) and the AccessibilityService tap-through of a freshly installed app.
4. Android 12+ foreground-service behaviour on a Samsung S25 (F-3): whether the
   exception is reachable depends on the exact background timing.
5. The 471-test suite was green at `944381c` (last run, `0.8.8`) and the one test
   added in this review (F-4's probe) ran green-then-deleted; **no test was
   changed in this pass** — this document is the only file added.
