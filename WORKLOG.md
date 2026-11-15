## 0.8.6 — "Vorv crashed last time" can no longer appear without evidence

**Reported:** "It never fucking crashed." The dialog fired on a launch that had not crashed, and its own text said so: *no Java crash report was written*.

**1. The rule was wrong, not the wording.** Every boot wrote `BOOT_COMPLETE` when the UI was up; a boot without that line was *assumed* to be a crash. Closing the app, swiping it away, or Android reclaiming memory all produce a missing `BOOT_COMPLETE` — so the next launch was told it had crashed. New `host.vorv.healer.DeathEvidence` decides from evidence a session cannot fabricate: Android's own exit history names a crash reason (CRASH, CRASH_NATIVE, ANR, INITIALIZATION_FAILURE) for an exit at/after that boot's start, or a Java crash report was written during it, or the watchdog recorded a death detection during it. No evidence → no crash: breadcrumb `previous_exit_not_a_crash` with the reason ("Android's exit history says REASON_USER_REQUESTED — ended, not crashed" or "Android kept no exit record for it"), crash counter untouched, no safe mode, no patch rollback, no synthesized report.

**2. The dialog asks the verdict, not the trace.** `VorvApplication` decides once at boot (`lastExitCrashed`, `lastExitVerdict`); `MainActivity` shows the crash dialog only when that is true. A stale `crash_synthesized_*` report from an earlier false alarm is retired to `handled_…` when the evidence says otherwise, so it cannot resurface as a crash.

**3. Boot start is a fact now.** `BOOT_STARTED` carries `epoch=<millis>` and `BootTracer.previousBootStartEpoch`/`sectionStartEpoch` read it, so a crash record from an older session cannot be pinned on the previous one. Without a stamp it reports `null` instead of guessing.

**4. Reports say what they rest on.** The synthesized body now prints `What says this was a crash: <evidence>` instead of "consistent with a NATIVE crash … or the process being killed externally".
Tests: `DeathEvidenceTest` — unfinished boot with no record is not a crash; user-closed and low-memory are not crashes; a native crash after the boot start is; a crash record older than the session is not; a Java report or watchdog detection during the session is; missing epoch still trusts a current crash reason; `sectionStartEpoch` parses the stamp and returns null when absent.

**Version:** versionCode 43, versionName 0.8.6. 461 tests, 0 failures.

## 0.8.5 — the app stopped claiming it had no Linux system when the APK plainly has it

**Reported:** "Still have no linux." with the Setup card reading *NOT PACKAGED — this build has no Linux system* and *proot MISSING*, on 0.8.2 (39).

**1. Two different facts were being reported as one.** "Is the payload in the APK?" and "did the installer unpack it where Android can execute it?" are not the same question, and the card answered the first with the second's result. It told the owner the build had no payload while the APK contained `lib/arm64-v8a/libforge-proot.so` (verified in the 0.8.2 release asset by reading its central directory, and again in 0.8.5). New `host.vorv.linux.LinuxPayload` answers both: the APK entry for this ABI (`ZipFile` over `applicationInfo.sourceDir`), the path the runtime itself reports (`ClassLoader.findLibrary("forge-proot")`, by reflection because it is protected), what `nativeLibraryDir` actually holds, and — when nothing is executable — an honest unpack-and-try in app storage that records the exact failure instead of swallowing it.
`LinuxSystem.isAvailable()` and `run()` now use the runtime-resolved path rather than assuming `nativeLibraryDir/libforge-proot.so`, so a stale `nativeLibraryDir` no longer reads as "missing".

**2. The card says which fix is needed.** Payload in the APK but not unpacked now reads **PACKAGED, NOT UNPACKED — Android did not unpack this APK's binaries; reinstall it**, gate 1 shows ✓ with that explanation, and TEST reports the four facts it checked on this phone. That unpack is an install-time step: nothing in app code can perform it, and Android 10+ will not execute a binary from app storage, so a clean install is the real remedy — now stated instead of "no Linux system in this build".

**3. Diagnostics widened.** The Setup dump now prints version, ABIs, `nativeLibraryDir`, the APK entry, the runtime-resolved path, an explicit "the installer unpacked no libraries for this install", the `assets/forge/linux` listing and the rootfs state. 16 KB page alignment was checked on the payload (`p_align = 0x4000` on proot, its loader and libjvm), so alignment is ruled out as a cause.
Tests: `LinuxPayloadTest` — inspect and repair answer instead of throwing on a context with no payload; `nativeLibraryDir` is null-guarded (Robolectric has no native lib dir, and a crash there would also have read as "no Linux").

**Version:** versionCode 42, versionName 0.8.5. 450 tests, 0 failures.

## 0.8.4 — crash reports come from Android itself, and a paid model can no longer look like a broken key

**Reported:** "the last crash didn't say that on the crash log — I believe it said unknown because it didn't have the tools to trace native crashes", and "I don't think it ever saved any of the crash logs."
**Reported:** "I will never pay for an AI model."

**1. Real crash evidence, not inference.** The old reports were *synthesized* from breadcrumbs because nothing asked the OS. Added `host.vorv.healer.NativeCrashHistory` — `ActivityManager.getHistoricalProcessExitReasons()` (no root; only this app's own exits) with reason, description, process, timestamp and, for a debuggable build, the tombstone/ANR trace from `getTraceInputStream()`. `VorvApplication.synthesizeNativeCrashReportIfMissing()` now writes a `crash_native_<exitTimestamp>.txt` labelled **REAL — from Android's exit history** and returns; synthesis only runs when the OS genuinely has no record, and it says so. A missing trace is named as missing instead of guessed.
Note: the entry type is the top-level `android.app.ApplicationExitInfo` on current SDKs (constants live there, not on `ActivityManager`).
Tests: `NativeCrashHistoryTest` — native/ANR reason names, real report quotes the tombstone, missing trace is stated as missing, unknown reason keeps its number.

**2. Free tier is the only path.** A paid model with no credit answers 402/"insufficient balance"; the old client would have surfaced that as a key-ish failure after retrying. `LlmHttpException.notEntitled` now recognises it, the chain skips the model for 30 minutes without calling it an outage, and if every model tried was paid the message says the account has no credit and points at the free tier / on-device model. Free models are ordered ahead of paid ones everywhere a chain is built, and Setup marks every non-free model **PAID — needs credit** so it is never picked by surprise.
Tests: `CloudChainLlmTest` — 402 is skipped and the free model answers, only-paid-no-credit message names the free option and says no key is wrong, free model sorts first.

**Version:** versionCode 41, versionName 0.8.4.

## 0.8.3 — the "crashed last time" dialog was reading another build's file, and one busy model no longer ends a task

**Reported:** "just installed it, first time opening it and this popped up. It didn't crash." The dialog said *Vorv crashed last time* on a brand-new install.
**Reported:** "the key was not the problem with GLM, I have multiple keys — the problem was the inadequate servers."

**1. Stale boot trace from an earlier install was reported as a crash.**
Boot traces live in `getExternalFilesDir`, which survives uninstall/reinstall on Android. The trace the dialog quoted (`BOOT_STARTED`, `hotpatch_load_*`, `check_previous_boot`, nothing after) belonged to a build that was removed — the previous version that hung on its loading screen. `previousBootCompleted()` only asked "does the last section end with BOOT_COMPLETE?", so a foreign section read as a crash of the new app.
Fix: `BootTracer.installId()` (a UUID in `filesDir`), written into every `BOOT_STARTED` line; `sectionCompleted(section, id)` ignores sections that do not carry the current install's id. Fresh installs can no longer inherit another build's death. Breadcrumbs `synthesize_native_report_begin/end` were added around the crash-report synthesis so the next unexplained exit names its step.
Tests: `BootTracerInstallTest` — foreign section ignored, legacy section ignored, real death in this install still detected, completed boot clean.

**2. Cloud mode had one model and therefore one point of overload.**
`CloudLlm` retried the SAME model four times (~10 s) and then failed the task. When Z.ai is saturated that is the whole failure mode.
Fix: `CloudChainLlm` — the selected model first, then every other catalog model; a model that answers 429/5xx (or drops) is put on a 60 s cooldown and skipped for the next steps of the same task. A 401/403/400 is NOT retried elsewhere (the request or key is wrong and every model would say so). When all are busy the error says exactly that, names each model and its status, and points at the on-device model — it never says "something went wrong".
`LlmHttpException(code, body)` now carries the status so the chain can tell overload from a bad key, replacing the private `NonRetryableLlmException`. `CloudLlm` gained `modelName`, `maxAttempts`, `backoffMs` (test seams).
Wired into both engine factories: `LlmRouter.pick` and `ModelRepository.activeEngine`.
Tests: `CloudChainLlmTest` — falls over on 429, skips the cooling model next step, "all busy" message names models and says it is not the key, 401 is not retried on other models.

**Version:** versionCode 40, versionName 0.8.3. Full suite: 51 files, 441 tests, 0 failures.

# Worklog — every change, why, and what was actually verified

This file exists so the record of the work lives in the repository itself, not
in a chat window. Newest entries at the top. Written by Lemon Code (AI agent)
on behalf of the repository owner.

How to read it: **Done** is code that is in `main`. **Verified** is something
that was actually executed and its result. **Not verified** is something that
has never run — it is never described as working.

### One source of truth per question
Copies drift when two documents both try to answer the same question. Each of
these questions is answered in exactly one place:

| Question | File |
| --- | --- |
| What changed, when, and what was verified | **`WORKLOG.md`** (this file) |
| What is wrong with the code and why | `REVIEW.md` (findings F-*, C-*) |
| How the Linux system works and its limits | `docs/OWN-LINUX-SYSTEM.md` |
| How the app's own runtime replaced Termux | `docs/OWN-COMPUTER.md` |
| What the user-visible release notes say | the GitHub release `v0.8.0-linux-preview` |
| Version | `app/build.gradle.kts` — `versionName 0.8.0`, `versionCode 37` |
| Behaviour | the code and its tests, never a document |

`CHANGES.md` is history: entries describe the build they were written for.
Where it and this file seem to disagree about Termux, this file is current.

---

## 2026-11-15 · v0.8.2 — the brain actually learns (weights move, on the phone)

The owner's objection was correct: an engine that cannot learn is not worth
having. So the app now owns a real trainer.

`host.vorv.brain`:
- `Value` — a scalar with a gradient. Every operation records its parents and
  local derivatives; `backward()` walks the graph and accumulates dL/dv. This
  is backpropagation written out, not a wrapper around a library.
- `CharBrain` — a ~3 000 parameter model: 8-character context, embedding
  table, one tanh hidden layer of 24, softmax over the vocabulary. Trains by
  cross-entropy + SGD with per-weight gradient clipping (without clipping the
  first version diverged, 2.9 → 23.6; with it, 2.958 → 0.393 in 800 steps).
  Weights save to `files/brain/brain.txt` and reload.
- `BrainTool` (`brain`) — `train` (learns from any text passed to it, reports
  the loss before and after), `generate` (predicts with the current weights),
  `status`. Registered next to the other tools, so the agent can use it.

Proof on the JVM, in the test suite (`CharBrainTest`): loss falls 2.958 →
0.393; more than a third of weights move and none go NaN; save/load round-trips
to identical predictions; generation stays inside the vocabulary; unseen
characters map to padding instead of crashing.

Said plainly, in the tool description and here: this model learns the patterns
in the data it is given. It does not write new programs. The cloud model does
that.

Also in this build: `versionName 0.8.2`, `versionCode 39`.

Verified: 433 unit tests, 0 failures; `assembleDebug` succeeds;
`extractNativeLibs=true`; rootfs 9 113 600 B and proot 259 832 B in the APK;
apksigner verifies.

Not verified: nothing here ran on the S25 — again, the diagnostics line is the
instrument for that.


## 2026-11-15 · v0.8.1 — why the phone said "NOT PACKAGED"

**Reported from the phone:** Setup → Linux system showed
`NOT PACKAGED — this build has no Linux system` on an APK that does contain
the payload.

**Cause, found by inspection and now fixed:** the built APK had no
`android:extractNativeLibs` attribute, so Android mapped the libraries
straight out of the APK instead of unpacking them at install time. The app
looks for proot in `nativeLibraryDir` (the only place Android will execute a
binary from), that directory was empty, and the check correctly said the build
was not packaged. The `.so` files were in the APK the whole time; they were
never installed. This is also why the on-device NPU plugin could not find its
libraries.

- `AndroidManifest.xml`: `android:extractNativeLibs="true"` on `<application>`.
- `LinuxSystem.diagnose()`: the Setup card now prints version, ABIs,
  `nativeLibraryDir`, whether proot/loader are present, and whether
  `rootfs.tar` is in the assets — so a missing payload names itself instead of
  showing a bare verdict.
- `NativeLibExtractionTest`: fails if the attribute is ever removed.
- `versionCode 38`, `versionName 0.8.1`, so a build can be identified on the
  phone by the number in the diagnostics.

Verified: `assembleDebug` succeeds; `aapt2 dump xmltree` shows
`extractNativeLibs=true` in the built APK; `rootfs.tar` 9 113 600 B and
`libforge-proot.so` present in the zip; apksigner verifies (debug key);
427 + 1 unit tests, 0 failures.

Not verified: the install itself. Whether libs truly land in
`nativeLibraryDir` on the S25 can only be confirmed on the S25 — that is what
the new diagnostics line is for.


## 2026-11-13 → 2026-11-15 · one engagement, 14 commits

Starting point: `b625e54` (the repository as delivered). Everything below is
on `main`.

### What was asked
1. Review the repository (independent code and capability review).
2. The app must be a fully autonomous coding agent with its own computer, on
   the owner's phone, **without root and without Termux**.
3. The computer must be a real Linux system inside the app.
4. It must build an app itself, open it, exercise it, and only then hand it
   over.
5. Every piece of work committed and pushed immediately.

### What exists now

| Commit | What it did | Verified how |
| --- | --- | --- |
| `e13d848` | Release variant gets host unit tests, so `./gradlew tasks` / IDE sync / `gradlew build` configure. AGP 9 reaches unit tests through `hostTests`, not `enableUnitTest`. | `./gradlew tasks` BUILD SUCCESSFUL; `testReleaseUnitTest` in the task graph |
| `b859594` | **Termux removed.** `shell` runs through `GuestShell` → proot/Alpine; `TermuxProbe`, its four-gate checklist, the old Termux `ShellTool`, the healer's SAF dispatch transport, `com.termux.permission.RUN_COMMAND` and the Setup card are deleted. `self_fix` compiles with the APK's own toolchain. | 427 unit tests, 0 failures; `assembleDebug` succeeds |
| `657a45c` | Guest shell binds the user's workspace at `/workspace`; review addendum. | `LinuxSystemArgvTest`, `ShellToolTest` |
| `f3e721a` | The Linux system itself: Alpine 3.20 rootfs as `rootfs.tar`, proot 5.1.107 + loader, `linux_run` tool, `RootfsTar` extractor with an escape guard. | JVM extraction of the real Alpine payload: 518 entries, 334 symlinks, `apk` executable |
| `2113edb` | `build_app`: builds, signs and packages an APK on the phone (`PhoneBuilder`, `BuildAppTool`, template APK). | Desktop JVM run of the identical pipeline: base.apk → classes.dex → signed.apk, `apksigner verify` OK |
| `c0c1fa5` | Reverted the bundled Java runtime experiment — it was carried as a payload but the JVM process could not be started from app storage, so shipping it was a dead 30 MB. Kept the compiler jars instead. | reverted on purpose; recorded here so it is not re-attempted blind |
| `a755a56` | `native_test`: install the built APK, launch it, tap through it (`AppInstaller`, `UiAutomationService`). | APK parse/install-intent path checked; **never run on a phone** |
| `21c46b1` | `web_test`: start the built web app, open it, exercise it. | 7/7 live checks in the harness |
| `818cf9c` | `computer_run`: the app's own JS/Python runtime, no Termux, no root. | 17 tests |
| `dfa78c5` | Own-computer core; **F-8**: the healer classpath jar shipped 5 entries and no classes, so on-device patching could never compile. Now built from AGP 9's `built_in_kotlinc` output (549 entries). | jar entry count + a full kotlinc→d8 run on the desktop JVM |
| `4afa8f4`, `5a77622` | `REVIEW.md`: the independent review (findings F-1…F-9, C-1…C-7) and its addendum. | the review's own table of what was run |
| `46bab36` | Source mirror synced as part of `preBuild`. | build |

### Design decisions and the reason (so they are not re-litigated)
- **proot + Alpine, not chroot.** Android forbids chroot without root; proot
  is the unprivileged equivalent. The owner forbade rooting.
- **Binaries ship named `.so`** in `jniLibs` because Android only executes
  files under `nativeLibraryDir`.
- **`libtalloc.so.2` rewritten to `../lib/libtalloc.so`** inside proot's
  `DT_NEEDED` (NUL-padded) instead of renaming the file: Android refuses to
  load a dependency whose name is not `lib*.so`.
- **Rootfs as plain `rootfs.tar`** because AGP strips `.gz` from assets.
  `RootfsTar` sniffs gzip anyway.
- **`GuestShell` interface** so `ShellTool` and `LinuxRunTool` are testable on
  the JVM with a fake guest, with `LinuxSystem` as the only production
  implementation.
- **Consent unchanged** when swapping the backend: the full command text is
  approved, a session grant is audited, a hung command trips the breaker.
- **Compiler jars are sha256-pinned** in `tools/forge/runtime-manifest.json`
  and fetched by `tools/forge/fetch-maven.py`, which refuses a mismatch.

### Payload in the APK (all tracked, all pinned)
`assets/forge/linux/rootfs.tar` (9 113 600 B), `lib/arm64-v8a/libforge-proot.so`,
`libforge-proot-loader.so`, `libforge-talloc.so`, `libforge-java.so`,
`assets/forge/tools/`: `kotlin-compiler-embeddable-2.4.21.jar` (58 593 291 B),
`kotlin-stdlib-2.4.21.jar`, `kotlinx-coroutines-core-jvm-1.11.0.jar`,
`annotations-26.0.2.jar`, `d8.jar`, `android.jar`, `apksigner.jar`,
`assets/forge/template.apk`.

### Not verified — say it plainly
- **proot has never executed on a phone.** `apk --version` and
  `apk add gcc && gcc hello.c -o hello && ./hello` must be run on the S25.
  Until then the Linux system is a payload plus a proven extraction path.
- **`build_app`, `native_test`, `self_fix` compile** have never run on a
  phone either; their pipelines are proven on the desktop JVM only.
- **NPU path (GenieX)** cannot be exercised without the device.

### How to rebuild / re-verify here
```
export JAVA_HOME=<jdk17> ANDROID_HOME=<sdk with platform 37 + build-tools 37.0.0>
./gradlew :app:testDebugUnitTest     # 427 tests
./gradlew :app:assembleDebug         # APK
./gradlew tasks                      # configuration check (F-1)
python3 app/src/main/python/test_vorv_sandbox.py
```

### Where the installable build is
GitHub release `v0.8.0-linux-preview`, asset
`Vorv-0.8.0-termux-removed.apk` (347 331 661 B). It is a debug build signed
with the repo's committed debug key, so an older differently-signed Vorv must
be uninstalled first.
