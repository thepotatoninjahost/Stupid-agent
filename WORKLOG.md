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

