# Worklog — every change, why, and what was actually verified

This file exists so the record of the work lives in the repository itself, not
in a chat window. Newest entries at the top. Written by Lemon Code (AI agent)
on behalf of the repository owner.

How to read it: **Done** is code that is in `main`. **Verified** is something
that was actually executed and its result. **Not verified** is something that
has never run — it is never described as working.

---

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
