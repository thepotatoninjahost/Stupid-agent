## 0.8.7 — a crash is something Vorv acts on, not something it describes

**Asked:** "if it did crash, then the app should want to fucking fix the problem."

**What was true before:** on a proven crash the app wrote a report and offered buttons. The only automatic action was the hot-patch probation quarantine. Nothing else changed, so a boot that died in the same place died there on every launch.

**New `host.vorv.healer.CrashRepair`** — runs at boot, immediately after `DeathEvidence` confirms a crash, and needs no model (so it works with no key, no download, no network):

* **Died inside GenieX/native init** (`geniex_init_begin` present, `geniex_init_success` absent) → `suppressNativeThisBoot = true`: that boot runs without native init once. A native crash on launch stops looping instead of repeating forever. One boot only; it is tried again next launch.
* **Died while applying hot-patches** (`hotpatch_load_begin` without `hotpatch_load_end`) → recorded as suspect and handed to the fixer.
* **A quarantined patch** from the probation gate → reported as already handled.
* **Nothing to disable** → says exactly that, with the last breadcrumb, instead of inventing a story.
* Every crash writes `crash_repairs/repair_<timestamp>.txt`: the evidence line, where the dying session stopped, what was done automatically, and that a code fix is still open.

**It is no longer silent.** The crash dialog gains `Done automatically: …`; the boot diagnosis task now receives `Already done automatically this boot (do not repeat it): …` plus the evidence line, and is told to read `crash_repairs/` before proposing anything. The old prompt's "consistent with a NATIVE crash … or an external process kill" was replaced with the actual evidence.

Tests: `CrashRepairTest` — death mid native init suppresses it once; a successful native init never does; quarantined patches are reported; death during hot-patch load is called out; nothing-to-disable says so; a null trace is answered, not crashed on.

**Version:** versionCode 44, versionName 0.8.7. 467 tests, 0 failures.

