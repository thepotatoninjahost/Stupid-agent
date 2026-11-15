## 0.8.8 — the boot trace can no longer grow into a startup kill

**From the recording (recorded 08:20 PST):** the dialog is the OLD wording ("Reason: the previous boot did not complete … consistent with a NATIVE crash"), so that phone was still on a pre-0.8.6 build — 0.8.6 was not published until 15:30 that day. The trace in it ends ~2 ms after `previous_boot_crashed`, i.e. the process died inside its own crash handling, at the first thing that reads the trace file.

**Found by reading the path it died in:** `boot_trace.txt` is appended on every launch, from 0.8.0 onward, and was never trimmed. It grows for the life of the install — hundreds of launches, plus a crash report that embeds the whole thing each time it is written, plus the dialog that renders it. Every one of those is a full `readText()` during startup on a phone. That is an out-of-memory kill in exactly the milliseconds where the crash check runs, and a process killed there leaves "the previous boot did not complete" behind — which is the loop the recording shows.

**Fixed:**
* `BootTracer.MAX_BYTES` (128 KB). `start()` calls `trimIfHuge()` first: if the file is over the cap it is rewritten with the newest half, cut at a clean section boundary. Older sessions are dropped; the current one is kept.
* `readTrace()`, `readPreviousTrace()` and `BootTracer.tailOf()` read only the LAST 128 KB via `RandomAccessFile` — the whole file is never loaded again.
* Crash reports embed at most 8 KB of the previous trace (`BootTracer.EMBED_BYTES`) and 8 KB of the watchdog report, with `…(older lines dropped)` when truncated. The dialog shows the same bounded text.

Tests (`BootTracerTrimTest`, 4): a trace over the cap is trimmed on the next boot and keeps the newest session; the previous section is still found in a huge file; `tailOf` returns exactly the bytes asked for; a small trace is untouched. `BootTracerInstallTest` wording corrected — "did not finish" is not "crashed"; that verdict belongs to `DeathEvidence` alone.

**Version:** versionCode 45, versionName 0.8.8. 471 tests, 0 failures.

