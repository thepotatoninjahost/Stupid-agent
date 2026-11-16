# 1. OBJECTIVE

Recover the Vorv autonomous coding-agent Android app to a state that is proven
working by the build, the test suite, and the phone — not by the repository's own
documentation — and repair whatever was actually lost during the last week of
changes. Restate the goal operationally: produce a debug APK from the current
source, prove its payload and version, install it, and show the agent completing a
real coding task; then fix or revert the specific regression found.

# 2. CONTEXT SUMMARY

- Project: `host.vorv` ("Vorv"), an on-device ReAct coding agent. Android app,
  minSdk 31, targetSdk 35, compileSdk 37; source declares versionName `0.9.2`,
  versionCode `49`. Core subsystems: `kernel/CognitiveKernel`, `tools/*`,
  `llm/*` (local GenieX + cloud fallback), `healer/*` + `watchdog/*`
  (self-repair), `forge/*` (in-app build toolchain), `linux/*` (proot + Alpine).
- Last week's commits (from `.git/logs/HEAD`): Termux removal, in-app
  Linux/proot, in-app APK building, CharBrain, crash evidence, and 0.9.0 →
  0.9.2 (embed the payload; make the build provision its own payload).
- Verified intact right now: 97 Kotlin files under `app/src/main/java`, a
  97-file mirror under `app/src/main/assets/source` (same names), a valid
  `AndroidManifest.xml`, 56 unit-test files, the Python sandbox
  (`vorv_runner.py` + `test_vorv_sandbox.py`), and no dangling `Termux`
  references in Kotlin or the manifest.
- Verified damage / discrepancy: `README.md`, `DEVELOPMENT.md`,
  `CONTRIBUTING.md`, `SECURITY.md`, and `NOTICES.md` are empty; the only built
  APK (`app/build/outputs/apk/debug/app-debug.apk`) is metadata **0.9.1 /
  versionCode 48**, so it does not correspond to the current 0.9.2 source; the
  payload trees (`jniLibs/`, `assets/forge/{jvm,tools,linux}`) are git-ignored
  and may be absent locally.
- Constraint: the owner has explicitly stated the documentation cannot be
  trusted. Every claim below must be re-proven by a command or a device
  observation.
- Environment: no Android device or Qualcomm NPU is available to this planning
  step; on-device validation must be performed by the owner or the code agent
  with a connected phone.

# 3. APPROACH OVERVIEW

Evidence-first recovery with one explicit decision point.

1. Freeze the current state and record ground truth (git status, diff, HEAD,
   build/APK versions) so nothing is lost by further edits.
2. Establish a clean baseline: build from the current source with payload
   provisioning, run the full unit suite and the Python sandbox, and inspect the
   produced APK. This converts "the docs say it works" into real pass/fail.
3. Reproduce the failure on the phone with `adb logcat` and the README
   acceptance prompts, so the actual defect is named rather than guessed.
4. Locate the regression by comparing against the last commit that builds and
   boots (bisect 0.9.2 → 0.9.1 → 0.9.0 → 0.8.x).
5. Repair forward with a regression test, or revert to the last proven-good
   commit if the fix is not contained. The owner chooses at this decision point.
6. Restore the emptied root docs from git (if their emptiness was unintentional)
   and correct any documentation that contradicts observed behavior.

Rationale for diagnosis-first: the source tree is intact and cannot be judged
broken or healthy without a build and a device. Fixing before reproducing would
repeat the mistake of trusting documentation.

# 4. IMPLEMENTATION STEPS

### Step 1 — Snapshot and record ground truth
Goal: capture the exact starting state so no evidence is lost.
Method: run `git status --short`, `git diff --stat`, `git rev-parse HEAD`,
`git log --oneline -15`, and record the values in `WORKLOG.md` (appended, dated).
Reference: repository root, `.git/`.

### Step 2 — Restore emptied documentation (only if unintentional)
Goal: recover `README.md`, `DEVELOPMENT.md`, `CONTRIBUTING.md`, `SECURITY.md`,
`NOTICES.md` if they were emptied in the working tree rather than committed
empty.
Method: `git show HEAD:<file>` and `git log --oneline -- <file>` to see whether
the content exists in history; if it does, restore it. If the files were
committed empty, leave them and note it, because inventing content would repeat
the "untrustworthy docs" problem.
Reference: the five root `.md` files.

### Step 3 — Clean baseline build (payload included)
Goal: prove the current source compiles and packages its computer.
Method: `./gradlew clean`, then `./gradlew :app:assembleDebug --stacktrace`.
The build's `checkPayload` → `provisionPayload` path should fetch the payload when
missing; if it fails, capture the exact missing file or script error from
`tools/forge/fetch-runtime.sh` / `fetch-linux.sh`.
Reference: `app/build.gradle.kts` (`requiredPayload`, `provisionPayload`,
`checkPayload`).

### Step 4 — Run the real test suite
Goal: replace the claimed "475 tests, 0 failures" with an observed number.
Method: run `./gradlew :app:testDebugUnitTest` and
`python3 app/src/main/python/test_vorv_sandbox.py`; record exact counts and every
failing test name.
Reference: `app/src/test/**` (56 files), `app/src/main/python/`.

### Step 5 — Inspect the produced APK
Goal: confirm the APK actually contains the computer and the right version.
Method: read `app/build/outputs/apk/debug/output-metadata.json` (expect 0.9.2 /
49), run `python3 tools/forge/verify-apk-payload.py`, and confirm the required
entries listed in `requiredPayload` are present in the APK.
Reference: `app/build/outputs/apk/debug/`, `tools/forge/verify-apk-payload.py`.

### Step 6 — Reproduce on the phone
Goal: name the actual defect instead of guessing.
Method: `adb install -r app/build/outputs/apk/debug/app-debug.apk`, start
`adb logcat -c` then `adb logcat`, launch the app, and run the minimal acceptance
prompts from `ACCEPTANCE_LOG.md` (offline boot, `uname -a`, a multi-step tool
loop). Capture crash stacks and the Linux "TEST" stage result.
Reference: `ACCEPTANCE_LOG.md`, `docs/OWN-LINUX-SYSTEM.md`.

### Step 7 — Identify the regression
Goal: pinpoint which commit introduced the failure.
Method: using the Step 3–6 results, `git bisect` between the current HEAD and the
last commit known to build and boot (candidate: the v0.8.0 / pre-Termux-removal
range in `.git/logs/HEAD`). At each step check "compiles + APK payload + boots".
Reference: `.git/logs/HEAD`.

### Step 8 — Repair forward or revert (owner decision)
Goal: make the app work with the smallest safe change.
Method: if the defect is contained, fix it in the current tree and add a failing
test that pins it (test-first). If it is deep in the in-app Linux/self-heal
subsystem, offer reverting to the last proven-good commit and re-landing the
removed features one at a time. Do not silently drop guards or downgrade
dependencies.
Reference: whichever files the bisect implicates.

### Step 9 — Re-verify and correct the docs
Goal: ensure the fix is real and the record is honest.
Method: rerun Steps 3–6 on the repaired tree; then update `WORKLOG.md` /
`CHANGES.md` with observed counts and any remaining limitation. Remove or correct
claims that were not reproduced.
Reference: `WORKLOG.md`, `CHANGES.md`.

# 5. TESTING AND VALIDATION

Success means all of the following are observed, not asserted:

- `./gradlew :app:assembleDebug` completes; no `checkPayload` or configuration
  failure.
- `:app:testDebugUnitTest` reports the actual test count with 0 failures (or the
  exact failing tests are listed and then fixed).
- `python3 app/src/main/python/test_vorv_sandbox.py` prints
  `ALL SANDBOX TESTS PASSED`.
- `output-metadata.json` shows versionName `0.9.2` / versionCode `49`, and
  `verify-apk-payload.py` reports the payload complete.
- On the phone: the app launches with no crash in `adb logcat`; the Linux
  "TEST" reaches a running shell; and one agent prompt completes with a real
  tool trace (not just chat text).
- Any regression test added for the defect fails before the fix and passes
  after.
- `git status` shows only intended changes; the five root docs are either
  restored to their historical content or explicitly recorded as lost.

