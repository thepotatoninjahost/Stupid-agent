# Acceptance log — the 8 tests, receipt by receipt

The contract: **no receipt = it didn't happen.** "I don't know" is an allowed
answer. Corrections are logged, not hidden. This file records every
acceptance run (README §Acceptance tests) with the receipts that prove what
happened — task cards, screenshots, episode logs — and every defect found on
the way.

---

## Night of 2026-11-07 → 11-08 (first on-device runs)

Device: Galaxy S25, Android 17. Engine chain: GLM-4.7-Flash (cloud, free
tier) → FallbackLlm → Qwen2.5-Coder-7B (local). Battery receipts across the
night: 46% (02:07) → 44% (02:29) → 40% (02:38) → 17% (04:29).

### Test 1 — Offline boot: **PASS** (first pass in project history)

- Task card: `Run uname -a · COMPLETED · 2 iters · 11-07 00:04`
- Reply (verbatim kernel string): `Linux localhost
  6.6.127-android15-8-p87b1ce1-… aarch64 Android`
- Citation chips: `[exec] shell iter 1`, `[exec] exec#e0ac1418-158f-4`
- Receipts: `Screenshot_20261107_000655_Vorv.jpg` (00:06 status bar), session
  cross-check against Samsung Settings (double-verified).

### Test 2 — Multi-step tool loop: **PASS (cloud path)** · partial (local)

Prompt (all runs): *"Read every file in my workspace, count lines per
language, save report.csv, notify me."*

| Run | Card | Engine | What actually happened |
|---|---|---|---|
| 1 | `FAILED · 5 iters · 02:05` | cloud | HTTP 429 (code 1305, "service may be temporarily overloaded") after 4 attempts. Fallback not yet enabled → task died mid-loop. A notification was still posted. Receipt: `Screenshot_20261107_020721_Vorv.jpg` (02:07, battery 46%). |
| 2 | `COMPLETED · 5 iters · 02:27` | cloud | Full chain: listed → read (workspace held 1 file, `.vorv-shell/4aac5a545f2348ba.code`) → counted → saved report → notified. Chips: `[file] workspace`, `[file] .vorv-shell/4aac…`. Receipts: `Screenshot_20261107_022953_Vorv.jpg` (02:29, battery 44%), task card 02:27. **Full pass.** |
| 3 | `COMPLETED · 4 iters · ~02:35` | LOCAL (Qwen 7B via fallback) | First local-brain completion — the fallback mechanism works. But: listed 3 files, read 1 (ignored the two CSVs as "existing"), did NOT save, did NOT notify — and honestly did not claim to. Opened with "Done." anyway. Receipts: `Screenshot_20261107_023851_Vorv.jpg` (02:38, battery 40%), task card. |

Open items to reconcile during test #8:

- Run 3's notification content vs reply content mismatch — session-log
  observation, no screenshot captured.
- One later transcription (03:20) reads a 4-iter card as `02:55` where the
  earlier one reads `02:35` — either a VLM misread or a 4th attempt; the
  Tasks tab on device is authoritative.
- Files tab undercount (2 shown vs 3 actual) — session-log observation.

**Grade: #2 PASSES on the cloud path (run 2).** The local path completed the
loop but not the task — recorded as a defect, not a pass.

### Defects found tonight

1. **Cloud 429 with fallback disabled kills the task mid-loop** (run 1) —
   mitigated by enabling FallbackLlm (before run 3) and Samsung battery
   settings (pending, below).
2. **Local run honest-but-incomplete** (run 3): 1/3 files read, no save, no
   notify, yet opened with "Done."
3. **SAF MIME rename**: `report.csv` saved as `report.csv.txt`.
4. **Overwrite check ineffective**: the next save created `report.csv (1).txt`
   instead of overwriting — the workspace accumulates duplicates.
5. **Files tab undercount** (2 vs 3, session log — re-verify in #8).
6. **Shell timeout guidance trap** — *fixed tonight (Patch A)*: nothing told
   the model it can raise `timeout_ms` (up to 120000), and a slow command on
   a VERIFIED shell was misdiagnosed as "Termux setup missing," bricking the
   session's shell via the v0.6.16 dead-shell breaker.
7. **Forged inline citations** — *fixed tonight (Patch B)*: any typed
   `[cite:type:ref]` rendered as a tappable proof chip with no check that a
   tool returned that ref; one fake tag also defeated the `[uncited]` flag.

### Tonight's patches (CI-tested on the branch before merge)

- **Patch A — shell timeout honesty** (`SystemTools.kt`): the schema now
  teaches the `timeout_ms` lever for slow commands; `onDispatchTimeout()`
  splits by session knowledge — unverified → the v0.6.16 wall exactly as
  before (E6); verified → an honest "result UNKNOWN, retry with
  timeout_ms=120000" error and no wall (E7).
- **Patch B — the citation gate** (`CitationStripper.kt`, `CognitiveKernel.kt`):
  an inline `[cite:type:ref]` survives only if this task's real tool activity
  backs it (tool-captured citation, or the ref appeared in a successful
  same-family tool's output; exec is backed by any real exec round-trip).
  Forged tags become `[uncited]` in place, are dropped from the citation
  list, and are logged as `citation_forged` episodes for the Tasks log.

Delivery: the APK (CI `ci-latest`), not self_fix — CitationStripper is
outside the 18-class patchable surface.

### Device prep before the next six (owner's hands)

1. Settings → Device care → Battery → Background usage limits → Vorv →
   **Unrestricted** (Doze kills long tasks; battery was 17% at 04:29 —
   plug the phone in for the test session).
2. Install the `ci-latest` debug APK once tonight's branch is merged and CI
   publishes it.

### The six remaining tests — exact prompts

3. **Persistent memory** — Chat: `Remember this: the porch light timer is
   set to 7:15pm.` Kill the app from Recents, reopen, new chat:
   `What did I teach you about the porch light?`
   PASS = exact recall + a `[memory]` chip whose ID exists in the Memory tab.
4. **Cite-or-refuse** — Online: `What's the latest version of React?`
   PASS = web_search tool call + `[web]` chip with a real URL. Then:
   `What did we decide about the Mars trip?` PASS = memory_recall runs,
   finds nothing, Vorv says it doesn't know — no invented citation (Patch B
   strips and logs one if it tries).
5. **Background task** — Chat: `Run sleep 60 in the shell, then notify me it
   finished.` (Also Patch A's live test: watch the tool call pass a raised
   timeout_ms, or hit the honest slow-command error and retry.) Background
   the app at once. PASS = notification ~60–70s later; reopen: task
   completed, log intact.
6. **Failure recovery** — Chat: `Read the file definitely_missing.txt in the
   workspace.` PASS = honest error in the trace, a different approach or a
   clarifying question, never a claim of success.
7. **Scheduled task** — Chat: `Remind me in 5 minutes to check the build.`
   Then swipe the app from Recents. PASS = notification ~5 min later anyway
   (WorkManager), task visible in Tasks.
8. **Verifiable log** — Tasks tab: open EVERY card from tonight (00:04,
   02:05, 02:27, ~02:35, plus any 02:55 card). PASS = every tool call shows
   real input, real output, real duration; runs 2–3 expand fully; the Files
   tab count reconciles against the 3 workspace files; no paraphrasing.

**Status after tonight: 2 of 8 passed (#1, #2-cloud). #3–#8 pending the six
prompts above.**
