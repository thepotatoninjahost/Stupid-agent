# 1. OBJECTIVE

Fix the confirmed provisioning bug so Vorv's in-app Linux actually boots: after
unpacking the root filesystem, `provision()` must mark it provisioned instead of
failing because the mark it is checking for does not exist yet. Prove it on the
owner's Galaxy S25 by getting the Settings test to print `ALL GOOD` and a real
`apk --version` result, and add a regression test so this cannot silently return.

# 2. CONTEXT SUMMARY

Confirmed by the owner's device (screenshots, version 0.9.2 / vc49, arm64-v8a):

- Gate 1 (payload) is ✓: `proot in APK: lib/arm64-v8a/libforge-proot.so`,
  `runtime resolved`, and `proot loader used: .../lib/arm64/libforge-proot-loader.so`
  — both are in `nativeLibraryDir`.
- `payload rootfs.tar present`, but `rootfs not unpacked yet`.
- Pressing "Test the Linux system — runs `apk --version`" yields
  `FAILED — the guest did not answer within 180s, the Linux root filesystem is incomplete`.

Root cause (read in `app/src/main/java/host/vorv/linux/LinuxSystem.kt`):

- `isProvisioned()` (line 65) = `stamp.isFile && File(rootfs,"etc/alpine-release").isFile`.
- `provision()` (lines 101-135) extracts the tree at line 117, then at line 126:
  `if (!isProvisioned()) return failure("the Linux root filesystem is incomplete")`.
- `stamp.writeText(STAMP)` is line 129 — **after** the check.
  On a fresh install the stamp cannot exist, so the check always fails and the
  stamp is never written. Provisioning can never succeed, on any run. This is the
  reported failure exactly.
- Secondary defect: the `STAMP` constant is documented as "bumped when the
  payload layout changes, so provision() re-extracts" (line 260-261), but
  `isProvisioned()` only tests `isFile` and never compares the value — so a
  payload upgrade would silently reuse a stale rootfs.
- Verification gap: `app/src/test/**` only tests pure helpers
  (`prootArgv`, `loaderCandidates`, `guestEnv`, `RootfsTar.extract`). Nothing
  exercises `provision()`'s ordering, which is why 475 green tests coexisted with
  "Linux always fails". There is no `androidTest/`.
- The only built APK on disk is stale 0.9.1/vc48; a rebuild is required so the
  device runs the current source.

# 3. APPROACH OVERVIEW

Minimal fix-forward. The failure is a specific logic-inversion in `provision()`,
not a missing payload, so no rollback is warranted (the owner's one-time
downgrade exception can stay unused).

1. Correct the order: verify the extracted tree, then write the stamp.
2. Make the stamp authoritative (compare its content to `STAMP`) so the documented
   re-extract-on-payload-change behavior is real.
3. Extract the ordering into a testable unit and add a JVM test that fails on the
   current code and passes after the fix.
4. Rebuild the current source, install fresh, and prove it on the phone.

# 4. IMPLEMENTATION STEPS

### Step 1 — Fix the provision ordering
Goal: let a first, successful extraction actually mark the system ready.
Method: in `provision()`, after `extractRootfs()` and the loader check, replace
`if (!isProvisioned())` with a check that only the extracted tree is complete
(`File(rootfs, "etc/alpine-release").isFile`); on success write
`stamp.writeText(STAMP)` and return success. Keep the existing failure text and
the `catch` cleanup so a partial tree is still deleted.
Reference: `linux/LinuxSystem.kt` lines 101-135.

### Step 2 — Make the stamp authoritative
Goal: honor the documented "bump STAMP to re-extract" contract and avoid stale
rootfs after a payload change.
Method: change `isProvisioned()` to require `stamp.isFile` **and**
`stamp.readText().trim() == STAMP` (wrapped in `runCatching` so a corrupt stamp
counts as not provisioned) **and** the `etc/alpine-release` marker.
Reference: `linux/LinuxSystem.kt` line 65, `STAMP` at line 261.

### Step 3 — Add a regression test for the ordering
Goal: catch a check-before-write regression without a device.
Method: extract the decision into an internal, Context-free helper — e.g.
`internal fun provisionRootfs(rootfs: File, stamp: File, extract: () -> Unit,
loaderOk: Boolean): Result<Unit>` — that performs extract → completeness check →
write stamp. Call it from `provision()`. Add a JVM test with a temp dir where
`extract` writes `etc/alpine-release`: assert a fresh run succeeds and creates
the stamp with `STAMP`, a second run is a no-op, and a changed stamp value forces
re-extraction. This test must fail against the current ordering and pass after
Step 1.
Reference: new `app/src/test/java/host/vorv/linux/ProvisioningTest.kt`.

### Step 4 — Confirm the extracted tree can actually run
Goal: ensure the fix reveals a working guest, not a second latent failure.
Method: after Step 1, if the device still fails, capture the new
`linuxTestResult`/`linuxDiagnostics`. In particular, `RootfsTar` handles
directories, symlinks and files but **not tar hard links (`entry.isLink`)**; if
`/bin/sh` or busybox applets are missing, add hard-link handling (copy or link the
target). Do not change extraction speculatively — only per the observed error.
Reference: `linux/LinuxSystem.kt` (`RootfsTar.extract`, lines 292-327).

### Step 5 — Rebuild, install fresh, and verify on the phone
Goal: prove it on the real device with the current source.
Method: `./gradlew clean :app:assembleDebug`; confirm `output-metadata.json` is
0.9.2 / vc49 and run `python3 tools/forge/verify-apk-payload.py`; record the APK
sha256. `adb uninstall host.vorv` then `adb install app-debug.apk` (fresh install
so native libraries are unpacked). Open Setup → "Test the Linux system"; then run
one `shell`/`linux_run` command (e.g. `uname -a`).
Reference: `app/build.gradle.kts`, `tools/forge/verify-apk-payload.py`.

# 5. TESTING AND VALIDATION

Success is defined by the phone and the build, not by status text:

- `output-metadata.json` shows versionName `0.9.2` / versionCode `49`; the payload
  verifier passes; APK sha256 recorded.
- After a fresh install, the Settings Linux card shows gate 2 ✓ (filesystem
  unpacked) and gate 3 ✓ (a real command answered).
- Tapping "Test the Linux system" prints **`ALL GOOD`** with the real
  `apk --version` output; `uname -a` reports Linux `aarch64`.
- The second run skips re-extraction (stamp present and equal to `STAMP`), and
  changing `STAMP` forces a re-extract — both covered by the new JVM test.
- `ProvisioningTest` fails on the pre-fix ordering and passes after the change;
  the full unit suite stays green.
- `adb logcat` shows no `the Linux root filesystem is incomplete`,
  `Permission denied`, `CANNOT LINK EXECUTABLE`, or `Exec format error`.

