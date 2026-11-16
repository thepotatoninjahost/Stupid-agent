## 0.9.1 — every remaining review finding, closed

**F-6 (blocked, not forgotten):** the release notes still contained the Termux
instruction. The replacement text and the CI payload step are in
`tools/ci/workflow-payload-step.patch`; GitHub refuses any workflow edit from
this account's token (no `workflow` scope), so that one hunk cannot be pushed
until the scope is granted. `tools/ci/README.md` says exactly how to apply it.

**F-7 — a Python engine nothing ever built.** `computer/PythonEngine.kt` was
imported by `ComputerTool` and never constructed, so the "computer" ran JS only.
Python does work, but through `python_exec` — one interpreter, one sandbox, one
consent gate. The dead class and its import are gone, and `ComputerTool`'s
documentation now says which path Python takes.

**F-8 — a Repair button that could not repair.** `LinuxPayload.repair()` copied
proot into app storage and exec'd it; since Android 10 that exec is always denied,
so the only possible answer was "app storage refuses to execute it". It is now
`payloadReport()`: an inventory that names the real cause — no payload in the
APK, the installer never unpacked it (uninstall and reinstall), or the runtime
cannot resolve it. No copy, no exec, no false hope.

**F-10 / F-14 — the Setup text.** It said "17 real tools" (the registry has 24),
advertised the GenieX/Qwen and Z.ai GLM engines as the product, and claimed the
shell runs in Vorv's Alpine system as though that were proven. The count now
comes from `tools.size` (`AgentState.toolCount`), the engine paragraph describes
what is actually selectable, and the Linux line says plainly that it has not yet
been seen starting on a phone and that TEST is what proves it.

**F-11 — a failing write during error handling.** `db.taskDao().fail(...)` in the
exception path could itself throw, replacing the real error and leaving the task
row `in_progress` forever. Wrapped in `runCatching`, like the cancellation path
already was.

**F-12 — unbounded crash-log read.** `readText()` loaded a whole tombstone before
truncating. The cap is now applied while reading.

**F-13 — documented, not fixed.** Rhino cannot be interrupted, so a
`while(true){}` keeps a core after the caller returns. Recorded at the site with
its cost and the alternative (out-of-process JS, ~1 s per call).

**Tests:** 475 unit tests, 0 failures, including the new F-8 test that a
payload-less install is *reported*, not "repaired".

