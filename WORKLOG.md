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

