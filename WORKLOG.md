## 2026-11-27 (later) — the crash that left no stack, and the watchdog overwriting it

**What was wrong.** The owner's S25 crashed while a Linux command was being run.
Android's exit history said `CRASH (uncaught Java exception)` at 11:58:55
(pid 2733), and there was NO Java report in `crash_logs/` — no class, no
message, no line. Two separate defects made that happen:

1. `VorvApplication`'s UncaughtExceptionHandler only *enqueued* a WorkManager
   job. Enqueue queues work to run later; the handler returns and the process
   is killed immediately, so a worker that never runs writes nothing. The
   handler now writes the report itself, synchronously, through the dying
   process (`crash_java_<ts>_pid<pid>.txt`), and the worker is kept only as a
   fallback that skips itself when that file already exists.
2. `BootTracer` kept ONE trace file for every process. `:watchdog` writes its
   own BOOT_STARTED seconds after the main process and never writes
   BOOT_COMPLETE, so the "previous boot trace" a crash report read was the
   watchdog's two-line section (pid 2768) while the process that died was
   pid 2733. The watchdog now writes `boot_trace_watchdog.txt`; the main
   process keeps `boot_trace.txt`, which is what crash reports read.

Also closed a crash path found while reading the Linux command route:
`GuestEgressProxy` runs one bare thread per guest connection with no caller.
An IOException mid-request (a guest that opens and drops a connection —
normal traffic) reached the app's default handler and killed the whole
process. It is now caught, logged and closed. `NativeCrashHistory` also no
longer claims "only debuggable builds keep tombstones": Android supplies
traces for ANRs and native tombstones from API 31, never for a Java uncaught
exception, so the Java stack must come from this app's own handler.

**Checked.** `:app:testDebugUnitTest` BUILD SUCCESSFUL — 120 result files, 934
tests, 0 failures, 0 errors, 2 skipped. Three new tests: the report names the
thread/exception/message/frame, the watchdog gets `boot_trace_watchdog.txt`,
and a dropped guest connection never reaches the crash handler. The last one
was run against the unguarded code first — `8 tests completed, 1 failed` — so
it genuinely pins that defect rather than describing it. `:app:assembleDebug`
BUILD SUCCESSFUL (374,352,768-byte APK, 20s); source mirror re-synced and
`diff -rq` identical. `SilentFailureGuardTest` kept at its 153 baseline: the
new fallible calls go through `Attempt.of`/`orDefault`, so no failure is
swallowed.

**Not checked.** The crash that started this is still unexplained: no trace
existed at the time, so the next occurrence is the one that can name the line.
Nothing here was run on the owner's S25; release/R8 is left to CI.

## 2026-11-27 — 24/24 tools, package visibility, Debian docs, debug + release + 930 tests

**What was wrong.** The Tools screen still listed 18 of the 24 tools
`AgentViewModel` registers — `computer_run`, `web_test`, `build_app`,
`linux_run`, `brain` and `native_test` were invisible to the owner. Android 11+
does not expose apps installed by Vorv to Vorv, so `native_test`'s
`getLaunchIntentForPackage()` on a freshly built APK returned null and
`app_intent(open_app)` called a real app "not installed". The guest docs and
the `provisionPayload` description still described Alpine and a 9 MB rootfs,
while the shipped payload is Debian 12.15 (28 MB, 4,217 entries, `apt`/`dpkg`).
README linked three deleted review documents, and two build/doc messages cited
the deleted `REVIEW.md`.

**What changed.** `ToolsScreen` now lists all 24 tools with the registry's real
descriptions; the manifest declares a narrow MAIN/LAUNCHER `<queries>` block
(not `QUERY_ALL_PACKAGES`) so apps Vorv installs are visible; `app_intent`'s
error says "no launchable activity" instead of claiming "not installed". The
guest docs, research note, `fetch-linux.sh` comment and Gradle task description
now say Debian 12.15 with the tar's real size and entry count. README's docs
table points at `AGENTS.md`. The source mirror matches `app/src/main/java`
byte for byte.

**Checked.** `:app:assembleDebug` BUILD SUCCESSFUL (3m 28s, 371,936,570-byte
APK); `verify-apk-payload.py` reports 10 required entries (>= 223 MB unpacked);
`:app:testDebugUnitTest` BUILD SUCCESSFUL — 119 result files, 930 tests, 0
failures, 0 errors, 2 skipped; `:app:assembleRelease` BUILD SUCCESSFUL
(4m 11s, 286,631,470-byte unsigned APK, R8); `:app:syncSourceMirror` +
`diff -rq` identical. Not checked: on-device proot, accessibility,
GenieX/NPU, and anything needing the owner's S25. Built with JDK 17.0.21.1 and
SDK platform 37.0 / build-tools 37.0.0.

## Docs and CI — four empty files, the APK gate, and the scope that blocked it

**What was wrong.** `DEVELOPMENT.md`, `CONTRIBUTING.md`, `SECURITY.md` and
`NOTICES.md` were each one byte: the README's documentation table linked to four
empty files. `README.md` also said the source "carries no separate licence file"
while `LICENSE` (MIT) sat in the root. And the APK payload gate — the check that
stops a computer-less APK leaving the builder — existed only as an unapplied
patch, because this account's token had no `workflow` scope and GitHub refused
any push touching `.github/workflows/`.

**What changed.** The four files now say what their titles promise, and the two
licence statements agree. `NOTICES.md` lists the payload components with the
licences read from `tools/forge/runtime-manifest.json` and from each Maven
artifact's published POM, including the GPL-2.0 obligation that comes with
shipping proot. The APK gate is a step in `build.yml` (it inspects the finished
APK with `tools/forge/verify-apk-payload.py` before any upload), the release
notes no longer tell the owner to install Termux, and the patch and README that
described the blocked state are deleted.

**Checked.** `.github/workflows/build.yml` parses (13 steps in `build`); the
documented commands and paths exist in the tree (`assembleDebug`,
`testDebugUnitTest`, `test_vorv_sandbox.py`, `verify-apk-payload.py`, the four
`RELEASE_KEYSTORE_*` names, `chaquopy { version = "3.11" }`); the licences that
were not already in the manifest were read from the POMs of Robolectric 4.17,
GenieX 0.10.0, jsoup 1.24.1, Rhino 1.9.2, XZ 1.10 and JUnit 4.13.2. Not
checked: anything that needs a JDK or an Android SDK — this environment has
neither, so the build itself was verified by CI, not here.

## 0.9.3 — the Linux system actually boots: provisioning was impossible

**The bug, exactly.** `LinuxSystem.provision()` unpacked the root filesystem and
then asked `isProvisioned()`, which required the stamp file `filesDir/linux/provisioned`
to exist. The stamp was written eight lines *below* that check. On a fresh install
the stamp cannot exist yet, so the check could never pass and the stamp was never
written — on any phone, on every run. The app therefore reported "the Linux root
filesystem is incomplete" over a tree it had just unpacked correctly, and
`apk --version` never answered. That is the owner's screenshot, line for line.

**The fix.** The decision now lives in `RootfsProvisioner` (no Android types):
extract → confirm the tree (loader present, `etc/alpine-release` present) → then
write the stamp. Failure deletes the half-built tree and writes no stamp.
`isProvisioned()` also now *reads* the stamp instead of only testing that it
exists, so bumping `STAMP` after a payload change really does force a re-extract,
which is what its comment always claimed.

**Why 475 green tests coexisted with this.** The suite only covered pure helpers
(`prootArgv`, `loaderCandidates`, `guestEnv`, `RootfsTar.extract`). Nothing ran
the ordering. `ProvisioningTest` (7 tests) now does, and was checked both ways:
with the old ordering spliced back in, three of its tests fail; with the fix,
all pass. Full suite: 482 tests, 0 failures.

**Checked before blaming the payload.** The shipped `rootfs.tar` (518 entries)
has `/bin/sh` → `/bin/busybox`, `/sbin/apk`, `/etc/alpine-release`, 334 symlinks,
and zero tar hard links; no entry is nested under a symlinked directory. So the
extractor's lack of hard-link handling (now documented in place) cannot affect
this payload, and no extraction change was made on spec.

**Build.** `:app:assembleDebug` → versionName 0.9.3 / versionCode 50, APK
346,004,997 bytes, sha256 `0615f4c8…`, `verify-apk-payload.py` passes with 10
required entries.

**Shipped.** Commit `7993027`, release `v0.9.3` with `Vorv-0.9.3.apk` (HTTP 200).
GitHub Actions run 43806325568 on the same commit: unit tests + sandbox
self-test, build, and publish all succeeded. The APK that run produced was
downloaded and checked: payload present (10 entries) and its bundled source
mirror contains `RootfsProvisioner`, so CI builds the fixed code.

**Not verified here:** the guest actually answering on the S25, and `uname -a`
reporting aarch64. That needs the phone: uninstall Vorv, install 0.9.3, open
Setup, tap "Test the Linux system". Expect `ALL GOOD` plus a real `apk --version`.

## 0.9.2 — CI goes green: the build fetches its own payload

**What was red and why.** Every GitHub build from 0.9.0 onward failed at
`:app:checkPayload` with "The Linux payload is not in this tree". The gate was
right and the tree was empty: the payload is git-ignored on purpose (~250 MB),
so a fresh clone — which is exactly what CI makes — has none of it. The step
that was supposed to provision it lived in `.github/workflows/build.yml`, which
this account's token cannot push (no `workflow` scope). So the fix could not be
the workflow, and the build had to stop depending on it.

**The fix.** `app/build.gradle.kts` now registers `provisionPayload`: when any
required payload file (or the Kotlin compiler jar) is missing, it runs the same
sha256-pinned scripts a developer runs — `tools/forge/fetch-runtime.sh` then
`tools/forge/fetch-linux.sh` — and `checkPayload` depends on it. Present payload:
the task is SKIPPED, so local builds are unchanged. `-PskipPayloadFetch` keeps
the old loud failure for an offline build.

Verified here from a genuinely empty tree: the payload directories were moved
away, `:app:checkPayload` rebuilt proot, its loader, libtalloc,
libandroid-shmem, libjvm, the JVM modules, the SDK jars, the Maven jars and the
rootfs (sha256 3bc5745…, identical to the previous build), rewrote proot's
runpaths to `$ORIGIN`, and succeeded. With `-PskipPayloadFetch` and the rootfs
removed, the build still fails and names the missing file.

**Also in this release.** The workflow patch is regenerated to contain only what
Gradle cannot do — the finished-APK gate and the release-notes text that still
mentioned Termux — and `tools/ci/README.md` says so. `git apply --check` passes.

**Tests:** 475 unit tests, 0 failures. APK verified: 10 required entries.

**0.9.2 follow-up, same release.** The first CI run after the fix showed the
next real problem: `fetch-runtime.sh` demanded the literal path
`platforms/android-37/android.jar`, but API 37 is published as `37.0`, so the
directory is `android-37` on one SDK and `android-37.0` on another — and the
CI runner had neither spelled as the script expected. Three changes:

- `tools/forge/ensure-sdk.sh` (new): finds the SDK (`ANDROID_HOME`,
  `ANDROID_SDK_ROOT`, `local.properties`, usual install paths), checks for an
  `android-37*` platform and `build-tools/37*`, and installs them with
  `sdkmanager` only when absent, trying `platforms;android-37.0` then
  `platforms;android-37`. It re-checks the files afterwards, because sdkmanager
  exits 0 even when it installed nothing.
- `runtime-manifest.json`: the SDK entries are now globs (`android-37*`,
  `build-tools/37*`) and `fetch-runtime.sh` expands them, so either layout works.
- `provisionPayload` also pins `ANDROID_HOME`/`ANDROID_SDK_ROOT` for the
  scripts from the same resolution, so the build does not depend on how the
  shell was configured.

Verified again from an empty tree: `:app:checkPayload` provisioned the SDK
files, the JVM, the toolchain and the rootfs and succeeded in 23 s; the rebuilt
APK is 345,994,945 bytes and passes `verify-apk-payload.py` (10 entries).

**CI is green.** Run 43795325423 (commit 5d4e3e5, 12m52s): "Unit tests +
sandbox self-test" success, "Build debug + release APKs" success, "Publish
signed release" success — the first green GitHub build since 0.8.9. The APK that
build produced was downloaded from the rolling `ci-latest` release and checked
here: `payload present … 10 required entries`, so this is the first
CI-produced APK that carries the in-app computer.

Release v0.9.2 carries the freshly rebuilt debug APK (345,994,945 bytes, sha256
9ba4b4c5…), asset HTTP 200.

