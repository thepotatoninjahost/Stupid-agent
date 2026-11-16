## 0.9.0 — the build actually contains the computer, and proot can find its libraries

**Found by the full review (F-5, F-2, F-3, F-4).** 0.9.0 is the first build whose
APK is checked for the payload before it is allowed to exist.

**F-5 — the payload was never in the APK.** `jniLibs/` and `assets/forge/` are
git-ignored (200 MB of binaries; the repo must stay clonable), and CI never ran
`tools/forge/fetch-runtime.sh` or `fetch-linux.sh`. Every APK from 0.8.0 to 0.8.9
installed fine and answered "this build has no Linux system" — because it was
true. That message was correct and was repeatedly misread as an install problem.
* CI (test + build jobs) now provisions the payload from the sha256-pinned
  manifest before compiling.
* `checkPayload` (wired into `preBuild`) fails the build when the source tree has
  no payload, so a local build cannot produce a computer-less APK either.
* `tools/forge/verify-apk-payload.py` inspects the finished APK and the CI "Gate"
  step runs it before any artifact or release. A computer-less APK cannot be
  published now.

**proot's own libraries — the failure the loader fix could not reach.** The
binaries are Termux builds: `DT_RUNPATH` is `/data/data/com.termux/files/usr/lib`,
which does not exist in Vorv and cannot be created (another app's private data).
proot therefore died in the dynamic linker, before `main()`.
* `tools/forge/fix-proot-deps.py` (run by `fetch-linux.sh`) rewrites the runpath
  of `libforge-proot.so`, `libtalloc.so` and `libandroid-shmem.so` to `$ORIGIN`
  (the folder the binary is loaded from == `nativeLibraryDir`) and makes
  libtalloc's `SONAME` (`libtalloc.so.2`) match the name it ships under. Both
  edits are NUL-padded in place, so no offsets move, and the script fails the
  build if the result is not what was asked for.
* `guestEnv` also sets `LD_LIBRARY_PATH` to the app's native library directory,
  so resolution does not depend on `$ORIGIN` support alone.

**F-2 — the guest timeout could not fire.** `run()` read the process output on
its own thread, so a command that hung without printing blocked forever and
`waitFor(timeoutMs)` was never reached. The drain now runs on a dedicated thread.

**F-3 — foreground-service lifecycle.** `stop()` used `startService`, which
throws from the background — it runs in the `finally` of a finished task, i.e.
exactly when the app is backgrounded; the uncaught exception aborted cleanup.
It is now `stopService`, and `start()` is guarded so a denied FGS start cannot
take the task down.

**F-4 — one 404 ended the cloud chain.** `LlmHttpException.unknownModel` now
covers 404 and "model" 400s; the chain skips a retired id instead of rethrowing,
which is the mechanical cause of "none of the GLMs are ever available".

**Tests:** 474 unit tests, 0 failures (`an unknown model id falls over to the
next model` is the F-4 regression). The Linux stack itself is still not proven:
no host here can execute an aarch64 proot, so Setup → Linux System → TEST on the
phone is the only real check.

## 0.8.9 — Linux can actually start: the loader is no longer put in app storage

**Found by the full review (F-1, `CODE-REVIEW.md`).** `provision()` copied
`libforge-proot-loader.so` into `filesDir/linux/loader/loader` and `run()` set
`PROOT_LOADER` to that copy. Since Android 10 the kernel refuses to execute a
file an app wrote into its own data directory when `targetSdk >= 29` (Vorv is
35) — permissive mode bits are not enough, the denial is in SELinux. proot execs
its loader before the guest exists, so the guest shell never appeared no matter
how complete the payload was. That single line is why every earlier payload fix
ended in "still no Linux".

**Fixed:**
* The loader is never copied. `LinuxSystem.resolveLoader()` picks
  `nativeLibraryDir/libforge-proot-loader.so` — the copy the installer unpacked,
  the one place Android allows execution — and falls back to a legacy filesDir
  copy only if nothing native exists.
* `provision()` fails early, with a message naming the file and saying to
  reinstall the APK, when no executable loader is present. It no longer unpacks
  9 MB of rootfs to then fail at exec time.
* `run()` resolves the loader before building the command and returns the same
  honest message instead of an unexplained exit code.
* `diagnose()` (Setup → Linux → Test) now prints `proot loader: <path>` or
  `NOTHING EXECUTABLE`, so the next failure names itself.
* `guestEnv` takes the loader file directly, so the path cannot be re-derived
  wrongly anywhere.

**Tests (`LinuxSystemArgvTest`, 7):** the old test asserted
`.../files/linux/loader/loader` — it was certifying the bug (F-9). It now asserts
the exact native path is passed through, that `nativeLibraryDir` wins over an
existing `+x` copy in app storage, and that the app-storage copy is last resort
only.

**Not verified here:** proot actually starting on the S25. The loader path is
now the only permitted one; what remains is the device's own exec policy, which
is what `diagnose()` reports. Uninstall the previous Vorv, install this APK,
then Setup → Linux System → Test.

**Version:** versionCode 46, versionName 0.8.9.


## 0.8.8 — the boot trace can no longer grow into a startup kill

**From the recording (recorded 08:20 PST):** the dialog is the OLD wording ("Reason: the previous boot did not complete … consistent with a NATIVE crash"), so that phone was still on a pre-0.8.6 build — 0.8.6 was not published until 15:30 that day. The trace in it ends ~2 ms after `previous_boot_crashed`, i.e. the process died inside its own crash handling, at the first thing that reads the trace file.

**Found by reading the path it died in:** `boot_trace.txt` is appended on every launch, from 0.8.0 onward, and was never trimmed. It grows for the life of the install — hundreds of launches, plus a crash report that embeds the whole thing each time it is written, plus the dialog that renders it. Every one of those is a full `readText()` during startup on a phone. That is an out-of-memory kill in exactly the milliseconds where the crash check runs, and a process killed there leaves "the previous boot did not complete" behind — which is the loop the recording shows.

**Fixed:**
* `BootTracer.MAX_BYTES` (128 KB). `start()` calls `trimIfHuge()` first: if the file is over the cap it is rewritten with the newest half, cut at a clean section boundary. Older sessions are dropped; the current one is kept.
* `readTrace()`, `readPreviousTrace()` and `BootTracer.tailOf()` read only the LAST 128 KB via `RandomAccessFile` — the whole file is never loaded again.
* Crash reports embed at most 8 KB of the previous trace (`BootTracer.EMBED_BYTES`) and 8 KB of the watchdog report, with `…(older lines dropped)` when truncated. The dialog shows the same bounded text.

Tests (`BootTracerTrimTest`, 4): a trace over the cap is trimmed on the next boot and keeps the newest session; the previous section is still found in a huge file; `tailOf` returns exactly the bytes asked for; a small trace is untouched. `BootTracerInstallTest` wording corrected — "did not finish" is not "crashed"; that verdict belongs to `DeathEvidence` alone.

**Version:** versionCode 45, versionName 0.8.8. 471 tests, 0 failures.

