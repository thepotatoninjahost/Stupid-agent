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

**Not verified here:** the guest actually answering on the S25, and `uname -a`
reporting aarch64. That needs the phone.

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

