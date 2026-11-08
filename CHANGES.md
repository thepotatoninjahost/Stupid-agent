# Changelog — full code-review remediation (2026-11-08, on top of v0.7.5)

Every code defect from the line-by-line code review, fixed — no downgrades,
no removed guards. 2 High, 9 Medium, 6 Low, 3 Info, plus regression tests
for every security-relevant fix. (Detailed per-finding notes live in each
fix's code comments, tagged with the finding ID. The review's
test-infrastructure recommendations — AgentViewModel and
BrowserAutomateTool test suites, an androidTest module, and the
major-stack migration — remain open by design: they need a real build
environment and are tracked in DEVELOPMENT.md and the remediation report,
not silently dropped.)

## High

- **H-1 shell-quote injection**: HealingAgent and TermuxProbe interpolated
  the user-chosen workspace path into Termux dispatch wrappers inside
  hand-written quotes — a folder named `x'$(cmd)'` executed arbitrary text
  in Termux. New `ShellQuote` object is THE single escaper; all three call
  sites (ShellTool, healer, probe) route through it; the wrapper builders
  are pure functions pinned by `ShellQuoteTest` (bash word-splitting
  round-trip over the exact attack payloads), and a real-bash run confirms
  the pre-fix code fires the injection while the fixed code stays inert.
- **H-2 false security claims**: SECURITY.md said the committed debug
  keystore "was removed" while `app/vorv-debug.keystore` is committed
  (deliberately, since v0.7.0, for update-in-place CI builds). Docs now
  state the exception honestly with the rationale (removing it would break
  update-in-place — a downgrade); CONTRIBUTING.md rule 5 aligned.

## Medium

- **M-1/M-2 silent truncation**: file_read's 50 000-char output cap,
  web_fetch's 8 000, browser_automate's 6 000 and memory_recall's 200-char
  preview all cut text with no marker — the model believed it had seen the
  whole thing. New shared `capWithMarker()` announces every cap (Tool.kt);
  the healer's compiler-error feedback is marked too.
- **M-3 repair engines outside the ledger**: SelfFixTool/MainActivity built
  their repair `LocalLlm` directly, outside ModelRepository's H-4 mutex, and
  doubled NPU memory when the resident engine already was the 7B. New
  `acquireRepairEngine()` — under the mutex, reuses the resident engine
  when it IS the strongest repair model, caller-owned otherwise. All three
  repair sites route through it.
- **M-4 citation substring matching**: the inline-citation evidence check
  was a raw `contains(ref)` — "example.com" matched inside
  "notexample.community…". Now `evidenceContainsRef()`: length-gated (≥4),
  word-boundary-delimited, regex-metacharacter-safe; pinned by
  `EvidenceContainsRefTest`.
- **M-5 tool-count drift**: ToolsScreen listed 16 tools (crash_logs
  missing), SECURITY.md said 16/18, SettingsScreen said 16. Now 17 tools /
  19 dispatchable classes everywhere, matching PatchRegistry exactly.
- **M-6 watchdog dead on background boots**: `startService()` from a
  WorkManager-woken process throws on Android 8+; the old catch swallowed
  it silently. Fallback `startForegroundService()` + momentary quiet
  foreground promotion (manifest specialUse type added) — the watchdog is
  upgraded to survive the boots it used to miss, never removed.
- **M-7 swallowed cancellation**: HealingAgent.callLlm's runCatching ate
  CancellationException — a cancelled heal fought the retry loop. Now
  rethrown; only real failures are logged.
- **M-8 consent gate fail-open**: python_exec marked consent BEFORE the
  dialog; a thrown bridge left it granted so a plain retry ran code
  unconsented. Tri-state map (pending/granted/absent) — exceptions,
  denials and cancellations all clean up and re-ask; concurrent same-task
  calls fail closed honestly. Pinned by `PythonExecConsentTest`.
- **M-9 secrets in backups**: README claimed the encrypted API-key store
  was excluded from backups; the rules never excluded it. Both
  backup_rules.xml and data_extraction_rules.xml now exclude
  `shared_prefs/vorv_secrets.xml` (cloud backup + device transfer).
- **M-2-family compiler-error feedback**: the healer's regeneration prompt
  now says when compiler output was capped.

## Low

- **L-2 DialGuard `^166`**: refused every US number with area code 660–669
  in international form ("1-660-…" normalizes to "1660…"). Anchored to the
  real HK shape (exactly 8 digits); tests pin both sides.
- **L-3 shell straggler files**: a dispatch that outlived its poll wrote
  out/err/code files after the tool returned — nothing ever deleted them.
  Every shell call now sweeps `<16hex>.{out,err,code}` files older than
  10 min (age-gated far beyond the 120 s poll window).
- **L-4 PatchVerifier field types**: fields were compared by NAME only — a
  type-changed public field passed verification and broke every caller at
  runtime. Now name AND type, with the exact expected/actual in the
  regeneration feedback.
- **L-5 CloudLlm retried deterministic failures**: HTTP 4xx (bad key, bad
  request) burned 4 attempts / ~10 s for the same answer. Now a private
  `NonRetryableLlmException` fails fast; 429/5xx keep their backoff.
- **L-6 two decoders for one URI family**: SafPaths used Uri.decode while
  TermuxProbe used java.net.URLDecoder ('+' → space, throw-on-malformed).
  SafPaths is now pure string logic with ONE Uri.decode-semantics decoder;
  both consumers share `primaryStorageTreeDocId()`.
- **L-7 release shipped debug bytecode**: the healer's app_classes.jar was
  always built from `tmp/kotlin-classes/debug` and embedded in EVERY
  variant — release self-repair compiled against debug classes. Now a
  per-variant jar task (packageHealerClasspathDebug/Release) with
  variant-mapped consumer dependencies.

## Info

- **I-1 dead network surface**: HotPatcher.fetchManifest (zero callers,
  zero tests, fresh OkHttpClient per call) removed outright — smaller
  attack surface, same functionality; the class doc now states remote
  manifest fetching does not exist in the product.
- **I-2 CI blind spots**: no `pull_request` trigger (fork PRs: zero CI)
  and `dependabot/**` matched no push pattern (bump PRs: zero CI). Both
  added; PR runs are read-token-safe (no secrets, no artifact writes,
  publish steps stay main-gated).
- **I-3 dependency stack**: verified, envelope-safe upgrades applied
  (AGP 8.7.3, KSP 1.0.28, OkHttp 4.12.1 — the certificate-blocklist
  security backport, coroutines 1.10.2, gson 2.13.2, lifecycle 2.8.7,
  core-ktx 1.15.0, datastore 1.1.7, WorkManager 2.10.0, Compose BOM
  2025.04.01; every version existence-checked against Google Maven /
  Maven Central and inside the Gradle 8.9 / Kotlin 2.0.21 / compileSdk 35
  envelope). The major migration (AGP 9.5 + Gradle 9.6 + Kotlin 2.4 +
  OkHttp 5.5.1 + Compose 2026.09) is a documented 7-step roadmap in
  DEVELOPMENT.md — one step per PR, CI green between steps.

**New tests**: ShellQuoteTest (H-1), EvidenceContainsRefTest (M-4),
PythonExecConsentTest (M-8), DialGuard HK/US cases (L-2), SafPaths
decoder/agreement cases (L-6). Mirror tree `assets/source/` re-synced
byte-identical.

---

# Changelog — v0.7.5 "The Crash Vorv Can Finally See"

The 20:32 screen recording, root-caused and fixed: an agent that could not
remember the last turn, could not read its own crash reports, and told the
user to do the debugging. Four fixes, all traced to the recording.

## The evidence (screen recording 2026-11-07 20:32, 4.2 s)

The user scrolled the chat after two real crashes. Verbatim from the
frames: *"did you see the crash report?"* → *"I don't have any crash report
information in our current conversation. Could you share the crash report
details with me? You can paste the error text, stack trace…"* — tagged
`[uncited]`. Then a 5-minute task timeout, then *"try again"* → *"I don't
have context for what you'd like me to try again."* — also `[uncited]`.
The user's verdict: *"You're supposed to fix the problem. You are supposed
to be [the one who] sees the crash report, not me. I'm not supposed to see
the report until you're done fixing it."*

## Root cause 1 — the agent was amnesiac BY DESIGN (the big one)

`kernel.run` builds a fresh `[system, user(prompt)]` for every send. The
model never saw a single prior turn — not the timeout notice, not the crash
question before it. "I don't have context for what you'd like me to try
again" was a perfectly honest answer to the only message it was ever
given. Fix: `ConversationPrompt` (pure, unit-tested) prepends recent turns
inside the prompt string — the `KernelApi.run` signature stays frozen
(SessionGrants would crash approved patches otherwise). Budgets are honest
per engine: local (nCtx 4096, 2048 reserved for output) gets 4 turns ×
200 chars; cloud gets 10 × 400 with a 3000-char cap and an explicit
"older turns omitted" note. Long-term memory stays in `memory_recall`.

## Root cause 2 — no tool could reach the crash artifacts

CrashReportWorker writes Java reports to `getExternalFilesDir/crash_logs/`;
the watchdog writes detections to `watchdog/`. The file tools only see the
SAF workspace; shell sees Termux. The reports sat on disk, invisible to
every tool — "I don't have any crash report information" was the literal
truth of a blind agent. Fix: **`crash_logs` (tool #17)** — list/read over
both evidence dirs plus the current and previous boot traces; paths
confined by prefix allow-list AND canonical-path check; 50KB read cap;
joins the `file` citation family so a crash claim can be cited. On the
19-class patchable surface (PatchRegistry + SelfFixTool updated; the
surface test pins 19).

## Root cause 3 — crash handling was user-driven, inverted from the contract

On boot after a crash, MainActivity pushed the raw report at the USER and
waited for a tap (Analyze / Self-Repair). The user's contract is the
reverse. Fix: `AgentViewModel.selfDiagnoseOnBoot` — on boot after a crash,
the agent gets the job directly: read the crash evidence, state the root
cause as precisely as the evidence allows (native-crash reports say
plainly that no Java stack exists — never invent one), `self_fix` if a
code fix applies (user approval still gates the patch). The persisted
kickoff note says what Vorv is doing; the task prompt never masquerades
as a user message. The dialog stays as the fallback (safe mode / engine
not configured / busy). Native crashes now also get a **synthesized
report** (breadcrumbs + watchdog detection, labeled as synthesized —
"there is NO exception class, NO stack trace below"), and
`getLatestCrashLog` falls back to watchdog detections so a mid-session
native death is never invisible again. The probation gate additionally
falls back to `CrashParser.inferCrashedClassFromBootTrace` when a report
has no Java frame.

## Root cause 4 — the timeout notice carried nothing to retry

"Task timed out after 5 minutes and was stopped. Partial tool-call
history is preserved in the Tasks tab." — no task, no progress, and the
model cannot read the Tasks tab. Fix: the notice now embeds the original
task (200-char excerpt) and the last 5 tool outcomes (name + ok/failed),
and ends with "If asked to retry, rerun the original task." The
user-STOP message carries the original task too.

## HARD RULE 13

Rule 12 banned inventing facts; the recording showed the mirrored
failure — claiming ABSENCE without looking, then asking the user to paste
what a tool should provide. New rule in the kernel's system prompt:
before saying you don't have or can't find information, CHECK the tool
that could hold it (memory_recall / file_list / crash_logs); report what
you checked; if no tool can reach it, say exactly that; never ask the
user to paste something a tool should provide. Pinned by test
(`system prompt carries rule 13`).

## Tests

New: `ConversationPromptTest` (7 tests — window sizes per engine, budgets,
omission notes, flattening), `crash_logs evidence backs a file citation`,
`system prompt carries rule 13`. Updated: the dispatch-surface pin 18→19.
CI runs the full suite on push.

## Honest limits (kept visible on purpose)

- Boot self-diagnosis needs a working engine; with cloud 429ing and no
  local model, it falls back to the dialog and says so.
- A stale REAL crash report still suppresses synthesis (pre-existing
  dialog behavior, not made worse; documented in code).
- The watchdog itself can die (battery optimization) — its notification
  path is still file-only; that residual is now at least readable by the
  agent.

---

# Changelog — v0.7.4 "The Heartbeat Learns to Rest"

Post-sweep verification audit + the one gap it found, closed.

## The audit: every review finding re-checked against the code

After the v0.7.2 "Full Sweep" landed, every one of the 25 review findings
was re-verified against the actual code at HEAD — mechanism, honesty, and
the regression test that pins it — rather than trusting the sweep's own
commit message. Result: 24 of 25 verified FIXED with real implementations
and real tests (M1's full Room stack + migration, M4's byte-true fetch cap
with UTF-8 tail trimming, M9's SAF rename honesty, M14's charge-only-on-
dispatch, L1's tightened action heuristic, …). Hygiene: the production and
test trees contain zero TODO / FIXME / STOPSHIP / @Ignore / "not
implemented" hits; the source mirror is byte-identical to the live tree
(all 56 Kotlin files at audit time, `cmp`-verified; v0.7.4 adds
ForegroundTracker to both trees).

## M6 upgraded from "moved" to the demanded mechanism

The audit found exactly one substitution: the review demanded the 5 s
heartbeat BACK OFF when no Activity is resumed, and the sweep had instead
moved the file to internal storage (a real improvement — no external-flash
wear, crash detection survives unmounted external storage — but the writes
still fired every 5 s for the whole process lifetime). v0.7.4 implements
the mechanism as demanded, and keeps the storage fix:

- New ForegroundTracker: counts STARTED activities via
  Application.ActivityLifecycleCallbacks (main-thread single writer,
  volatile read). Foreground = at least one visible activity.
- The heartbeat writer picks its cadence from the tracker: 5 s while the
  UI is visible, 60 s while backgrounded — 12x fewer flash writes when
  the app simply sits in the background.
- The heartbeat file records the cadence it was written at (line 4,
  `cadence=...`), and the watchdog's stale rule is now adaptive
  (`heartbeatIsStale`: stale = older than 3 written cadences). A
  backgrounded-but-alive main process can therefore NEVER be declared
  dead because of the backoff; the isMainProcessRunning() process check
  stays the real discriminator, and binder death remains the primary,
  immediate signal.
- The sleep is sliced (1 s) so switching foreground/background changes
  the cadence within a second — without ever writing faster than the
  current cadence allows.
- The legacy external file is swept on EVERY heartbeat write — stateless
  and unconditional (delete() on a missing file is a no-op, and the
  backoff made writes 12x rarer in the background), so no write can ever
  leave a stale external file behind as a false crash signal. An earlier
  draft made this sweep one-shot per process; that carried process-level
  static state for a negligible saving and was dropped before merge
  (Robolectric shares statics across tests, and the state broke the
  pre-existing sweep test).

Tests: WatchdogHeartbeatPathTest grows 6 backoff tests (cadence line
recorded, cadence-by-state, stale rule at both cadences, legacy-file rule,
stateless sweep-on-every-write) and a new ForegroundTrackerTest pins the
counting rules PLUS the real wiring — a live activity start/stop cycle
driven through Robolectric's controller flips the tracker exactly as the
framework will on device. 11 new tests total.

## The stale v1.0 release is retired

The Releases page offered two artifacts: the rolling `ci-latest` build and
`v1.0-00a4ce2` — a 170 MB debug APK from 2026-10-31 that predates every
single fix (root causes R2 and R3 included) under a tag that says v1.0
while the app was at 0.7.0. The review's fix plan ordered it retired; it
is now deleted from the release surface (the git tag and its commit remain
in history). A phone user can no longer download a known-broken build.
The historical transition cost is unchanged: one uninstall if the
v1.0-era keystore is installed, never again after (committed debug
keystore since v0.7.0).

Version 0.7.4 (versionCode 33).

# Changelog — v0.7.3 "Never Guess"

One rule added to the HARD RULES the kernel sends with every task: NEVER
guess. The codebase had already banned the two worst forms of it — rule 1
(never invent what a tool returned) and the citation machinery (rule 4,
`[uncited]`) — and v0.6.15 fixed the model guessing "no Termux" from a
tool description instead of calling it. What was still legal for the model:

- guessing a file's CONTENTS instead of reading it,
- inventing command FLAGS instead of running `--help` or trying,
- asserting "it should work" instead of testing that it does,
- answering from plausibility when one tool call would have produced the fact.

Rule 12 closes all four: if you have not read it, run it, or checked it,
you don't know it. `file_list` before `file_read`; run the command to see
what it actually does; `web_search`/`web_fetch` before stating a fact.
Invented paths, flags, contents, and "should work" claims are failures even
when they sound confident — and what cannot be verified is either admitted
("I don't know") or marked `[uncited]`, exactly as rule 4 already requires.

The rule is pinned by prompt assertions in `CognitiveKernelTest` (the same
pattern every prior rule uses), and the bundled source mirror carries it
too — `syncSourceMirror` keeps the healer reading the code that matches
this binary.

# Changelog — v0.7.2 "The Full Sweep"

Phase 1 of the code-review fix plan: every remaining finding from the
25-finding review, fixed and regression-tested. Nothing removed, nothing
watered down — 20 fixes, 40+ new tests, one Room migration (v2 → v3).

## The conversation survives (M1)

Chat history is now persisted to Room (`chat_messages`, schema v3) and
reloaded on startup. Restarting the app used to lose every message while
the tool-call logs survived — it read as "the app forgot what we just
said." Every message is written as it is appended (user echo, answers,
busy hints, stop notices, errors — all of it), reloaded in order (rowid
tiebreak for same-millisecond writes), bounded at the 500 most recent,
and wiped by the existing clear-all-data flow.

Post-release correction (CI rounds 4–5, 2026-11-07): the M1 window query
shipped upside down — `ORDER BY ts ASC … LIMIT 500` keeps the OLDEST
500, so a restart past 500 messages restored the beginning of the
conversation and silently dropped the recent tail. The new regression
tests caught it on their first actual run (rounds 1–3 never got that
far — compile errors). Fixed in two steps: the window now selects the
newest messages (inner query `ts DESC, rowId DESC`) and presents them
chronologically; and the insertion-order tiebreak became an explicit
autoincrement `rowId` PRIMARY KEY, because Room's query verifier cannot
see SQLite's implicit `rowid` at all. The message UUID carries a UNIQUE
index so a re-inserted logical message still replaces its row
(REPLACE strategy), and the v2 → v3 migration DDL was rewritten to the
exact Room-expected schema.

## Local-mode tasks stop dying mid-run (R3)

The kernel's own compaction window could open directly on a tool result
whose calling assistant message had just been folded into the digest —
chat templates reject orphaned tool messages, so longer local tasks died
mid-run (root cause RC-3). The window boundary now advances past leading
tool results, the same rule FallbackLlm already applied to the secondary
engine. Regression tests tune the boundary to land exactly on the bug.

## The parser stops throwing (H1) and stops hijacking (H1b)

- `<tool>` calls with array/number/null `name` or `arguments` used to
  throw UnsupportedOperationException straight out of parse() and abort
  the task. Non-string names and non-object/non-string arguments are now
  shape-rejected → treated as an answer, never a crash.
- Bare/prose JSON carrying a "name" key (package.json, API payloads) was
  hijacked into phantom tool calls. The bare path now requires a KNOWN
  tool name; fenced blocks keep the name-key gate (a fence is strong
  intent — the kernel reports unknown tools honestly there).

## Shell output cannot OOM the app (H2)

The shell tool's file reader slurped the WHOLE output file before
capping — a runaway command writing hundreds of MB killed the process
before the cap applied. The reader now stops AT the cap (streaming) and
appends an honest truncation marker when more content existed.

## self_fix gets a realistic budget (H3a/H3b) and stops leaking (M8)

- Termux compile budget: 150 s → 600 s (real kotlinc round-trips exceed
  150 s on-device; the old budget killed healthy compiles mid-flight).
  Every attempt now records its REAL duration (log + lastCompileDurationMs).
- Source cap: 8 000 → 131 072 chars — self_fix must re-emit files whole,
  and the largest bundled source is ~47 K chars. A hypothetical larger
  file is refused honestly instead of silently truncated into a
  guaranteed-broken patch.
- Verifier candidate dex files are swept on every write and deleted after
  verification — they used to accumulate in cacheDir forever.

## Honesty fixes

- **M2**: finish_reason=length answers are delivered with an explicit
  "[answer truncated by the model's token limit…]" marker and a
  `truncated:true` episode — a cut-off answer is never presented as
  complete.
- **M9**: file_write reports the file's REAL name (SAF providers may
  rename on create) plus an explicit rename note — a reported-but-
  nonexistent path is a lie the model trips over on the next read.
- **M13**: when Android blocks the Termux launch because Vorv is
  backgrounded, the error names the cause and the exact remedy instead
  of burying it in a generic dispatch-failure message.
- **M14**: the per-tool rate limit is charged only when a call actually
  dispatches — malformed attempts no longer burn the budget and lock the
  model out of the tool.
- **M10**: file_list is bounded (12 levels / 2000 entries) with an honest
  truncation note.
- **L1**: the action-request nudge only fires for imperative-position
  verbs or non-subject statements — "the app runs slowly" no longer gets
  a "call the tool NOW" correction.

## Reliability fixes

- **M4**: web_fetch caps actual BYTES, not decoded chars — multi-byte
  content used to pull up to 4x the nominal cap through; split UTF-8
  tails are trimmed cleanly.
- **M5**: the Keystore-backed SecretsStore is built lazily and seeds its
  state on Dispatchers.IO — app start no longer pays the Keystore
  handshake on the main thread.
- **M6**: the 5-second heartbeat moved to internal storage — no more
  external-flash wear, and crash detection no longer breaks when
  external storage is unmounted.
- **M7**: model-download progress never emits a bogus 0% — unknown and
  zero-total pull events are skipped instead of flickering the bar back.
- **M3**: the PermissionBridge/SessionGrants singletons release the last
  dead ViewModel in onCleared (guarded so a newer ViewModel's handlers
  are never cleared in a recreation race).
- **M12**: Japanese mobile numbers (090/080/070, +81) are no longer
  refused as premium-rate; German 0900 and non-JP-shaped UK 09 numbers
  stay blocked.
- **L6**: the crash dialog's self-repair no longer passes a fake empty
  boot trace; **L5** fixed a comment that had drifted from the code;
  **L8** the README headline no longer says GLM-4.6 (default is the free
  GLM-4.7-Flash tier).

# Changelog — v0.7.0 "The Free Brain"

The founding complaint, verbatim from the field: "It lies constantly. Literally
cannot function at all. I expected that Vorv would be able to understand
things like you do." Root cause: cloud mode was hardwired to a single paid
model (GLM-4.6) with no key ever entered, so every real task ran on the 7B
local model crushed into ~1,800 tokens of working memory — a brain that
forgets its own last step. The fix is not a bigger local model; it is making
the smart brain free and the switch automatic.

## Free by default — GLM-4.7-Flash, $0/token

- Cloud model becomes a choice in Setup, and the DEFAULT is Z.ai's free tier
  (GLM-4.7-Flash): 128K context, native function calling, no credit card, no
  subscription. The paid models (GLM-5.3-Flash, GLM-5.3 flagship, GLM-4.6)
  are listed with their prices and are strictly opt-in.
- Saving an API key now runs a REAL probe — one actual round-trip against the
  selected model (~8 tokens, $0 on the free tier). Setup shows the model's
  literal reply or Z.ai's literal error, never a guess, and on success
  switches the engine to cloud automatically: pasting a key is an
  unambiguous instruction to use cloud.
- Picking a different model with a key saved re-probes immediately — a
  typo'd or retired model id is caught at selection time in Setup, not
  mid-task.

## No task dies when the signal does — automatic offline fallback

- New FallbackLlm engine wrapper: cloud first, offline NPU brain as backup,
  per kernel step. Signal drop, 5xx, or exhausted retries mid-task → the
  same step reruns on the local engine with the history compacted to fit
  its small context. The task degrades; it does not die.
- Three honesty rules, unit-tested (FallbackLlmTest):
  1. Every takeover is logged in the episode log with its REAL reason —
     a fallback is visible, never a silent engine swap.
  2. AUTH failures (401/403/invalid key) NEVER fall back — a broken key
     must stay loud until fixed in Setup, not be papered over by offline
     answers.
  3. Cancellation (STOP button / timeout) never falls back.
- Compaction is pairing-safe: the system prompt always survives, the newest
  turns win, and a tool result is never orphaned from its tool call.
- Fallback is a Setup switch, armed only when a local model is on disk;
  the header label states when it is armed ("· offline fallback armed").

## Field note: mid-session sandbox rollback

During the build of this version the dev sandbox was silently recycled to a
v0.6.16 snapshot while v0.6.17–19 sat safe on GitHub. Detection (versionCode
disagreement in build.gradle.kts), recovery (rebase of the v0.7.0 work onto
origin/main, verified: lecture-breaker rule 11, one-line setup instructions
and Termux probe gates all still present). Recorded here because "the tree
moved under me" is exactly the kind of thing this project writes down.

## Field finding #9 — the dying build machine takes the app's identity with it

The recycle also destroyed ~/.android/debug.keystore — the key that signed
every APK on the phone. Android refuses to update an app whose signature
changed, so ANY next build would force a full uninstall (wiping every memory,
task log and setting) before it could install. Root-cause fix: a debug
keystore is now COMMITTED at app/vorv-debug.keystore and pinned for the debug
build type, so every future build — CI, sandbox, fork — signs identically and
installs over the installed base. This deliberately overturns the debug-key
half of audit H-7 (that finding was right about RELEASE keys; a debug key has
no security value to protect, and the field just proved the alternative costs
a phone-only user their data). One-time cost: the transition TO this keystore
requires one final uninstall. Never again after.

---

# Changelog — v0.6.19 "The Lecture Breaker"

Eighth real-world finding, from the field recording: the user typed
"termux works install the tools you need" and the model answered with a
multi-screen essay about "learning language" — syntax, grammar, tenses,
SWOT analysis, the Six Thinking Hats — ending "Let me know if you have
any specific questions!" No tool call. No code block. The honesty pill
flagged it "uncited — no tool evidence" and delivered it anyway. It also
opened with a factual hallucination: "Termux … doesn't have a direct
way to install tools" (it has pkg). The user asked an agent to act; the
agent delivered a curriculum.

## Why the guardrails missed it

The v0.6.13 nudge (act, don't advise) fires when the answer contains a
bash or python code block with no tool call. A prose lecture has no code
block — the trigger never fired. And nothing in the prompt knew Termux
has a package manager, so the model's "can't install" claim went
unchallenged.

## The lecture breaker — prose non-answers get the same one correction

- New kernel heuristic `looksLikeActionRequest`: the user's message
  reads as an instruction to DO something (imperative verbs — install,
  run, create, fix, set up… — on word boundaries; a trailing "?"
  exempts questions). When such a request is answered with ZERO tool
  calls, the kernel sends the same single nudge, under a new episode
  name `action-request-without-tool-call`, with a correction that names
  the non-answer: "A lecture, a curriculum, or an essay about how you
  would learn is NOT an answer to an action request."
- The correction carries the fact the model hallucinated around:
  Termux CAN install software — `pkg install -y git`.
- Same bounds as v0.6.13: at most ONE nudge per task, and the nudge
  never executes anything itself — the consent gate still bounds every
  execution.

## The model now knows Termux has a package manager

- The shell tool's description (what the model actually reads) now
  states Termux is a full Linux environment with pkg, gives the exact
  install form (`pkg install -y <name>`; git, python, nodejs, ripgrep
  as examples), and forbids the "Termux cannot install tools" claim
  from the recording.
- System prompt rule 11: never answer an action request with a lecture,
  a curriculum, a plan, or an essay; call the tool or ask ONE short
  clarifying question.

## Why this can only mitigate, not cure

The brain is Qwen2.5-Coder-7B on-device. A 7B model will still
sometimes choose words poorly; these changes make the WRONG choice
correctable within one turn instead of final, and make the right
choice (call the shell, use pkg) the explicitly taught one. If the
lecture behavior persists in the field, the honest next step is a
bigger model on stronger hardware — no prompt can fully prompt a brain
past its size.

# Changelog — v0.6.18 "One Line at a Time, Copyable at Last"

Sixth and seventh real-world findings, live from the device this time. The
user cleared the remaining Termux walls BY HAND — and hit two new walls
that Vorv itself created:

- Finding #6: every fix instruction arrived as a `mkdir -p ~/.termux &&
  echo 'allow-external-apps=true' >> …` ONE-LINER. Typed by hand on a
  phone keyboard it became `mkdir-p~/.termux` — one word to bash, instant
  failure, user confused through no fault of their own.
- Finding #7: Vorv's chat text was not selectable. The user could not
  copy the commands OUT of Vorv into Termux — retyping was the only way,
  which is exactly how finding #6 happened.

## One line at a time — every command a human must type is short

- Every fix instruction in the app — the Setup checklist's gate 4 (both
  the untested and the blocked variant), the probe timeout message, the
  shell dispatch-timeout error, the blocked-shell error — now shows
  SINGLE-STEP commands, numbered, one per line: `mkdir -p ~/.termux`,
  then `echo 'allow-external-apps=true' >> ~/.termux/termux.properties`,
  then restart Termux, then `termux-setup-storage`. No `&&` chains
  anywhere a human is asked to type — pinned by tests
  (`no && one-liner for a phone keyboard`).

## Copyable at last — chat, consents and full outputs are selectable

- Chat message bubbles are wrapped in SelectionContainer: long-press →
  select → copy works. The user can now copy Vorv's commands straight
  into Termux instead of retyping them (finding #7).
- The consent dialog and the full-output viewer are selectable too —
  they carry exact commands and long tool results.

## Bonus: root-of-storage workspaces were wrongly rejected (since v0.6.14)

- New SAF-parsing tests exposed a latent bug in BOTH rawWorkspacePath
  copies (ShellTool and TermuxProbe): `docId.substringBefore(":", "")`
  returns "" (the missing-delimiter value) when a docId has no colon —
  so granting the ROOT of primary storage ("tree/primary", no colon)
  produced volume "" and dispatch rejected the workspace as
  "not primary storage"… while workspaceOnPrimaryStorage (correct)
  showed gate 1 PASS. The checklist said green, the shell said no —
  exactly the contradiction that burns a field user. Fixed in both
  copies, in lockstep, with the asymmetry documented.

Tests: TermuxSetupTest 21 (checklist states, next-step ordering, SAF
tree-URI parsing including the storage root, single-step commands,
blocked banner), TermuxSmokeTest 17 with single-step assertions on both
shell error messages.

---

# Changelog — v0.6.17 "The Whole Wall at Once"

The aftermath of findings #4/#5: "keeps coming up with new bullshit." The
user granted every permission both apps offered — and still hit fresh
walls, one per error, minutes apart. Not one wall was fake: the workspace
grant, Termux installed, RUN_COMMAND, allow-external-apps,
termux-setup-storage. The UX sin was the REVEAL ORDER: each gate surfaced
only when the previous one opened, mid-task, exactly when the user thought
they were done. v0.6.16 stopped the bleeding (fail fast, no retry loop, a
STOP button); this version removes the surprise itself.

## The consolidated checklist — every gate, live, all at once

- Setup → Termux integration is now a four-gate checklist, each gate with
  a live status and the exact price of opening it, in the order they must
  open: 1) workspace folder (Files tab, primary local storage), 2) Termux
  installed, 3) 'Run commands in Termux' permission, 4) the two one-time
  Termux-side steps. Nothing is held back for a mid-task surprise.
- ONE next step is highlighted — the first gate still not green — instead
  of a flood of warnings. When everything opens: "ALL 4 GATES OPEN."
- Gate 1's fix lives in the Files tab, so the card carries an "Open the
  Files tab" button when the workspace is missing or off primary storage.
- The dead-shell circuit breaker is now VISIBLE in Setup: a red banner
  while active (the model's status line already points the user here —
  this is what they find when they arrive), and gate 4 shows FAIL with
  the exact two commands plus the lift path while it holds.

## The probe and the breaker share one truth

- TermuxProbe.runProbe now returns a TYPED result (READY / TIMED_OUT /
  OTHER); the ViewModel acts on meaning, never on string prefixes.
- A probe timeout trips the SAME breaker the shell tool trips — the Setup
  test and real shell calls can never disagree about the wall's state.
  Every shell call after a timed-out test fails instantly with the manual
  fix, instead of burning its own 30-second timeout first.
- A passing probe still marks the chain verified (which lifts the block);
  that invariant moved into the probe itself, next to the dispatch it
  proves, instead of living in the ViewModel's string check.
- Session state (verified / blocked / grant / counter) refreshes the
  moment a task ends — the checklist can no longer show a stale breaker
  or a stale "not verified" after the task that changed them.

## Tests

- TermuxSetupTest grows to 19: every checklist state pinned (all-green,
  each single-gate failure, off-primary workspace, UNKNOWN vs FAIL for
  the Termux-side steps, blocked-outranks-verified fail-closed order),
  the next-step ordering (first closed gate first), the SAF tree-URI
  parsing (primary / SD volume / other providers / null), and the
  blocked banner's instant-fail + lift-path wording. 199 tests total.

# Changelog — v0.6.16 "The Exit and the Circuit Breaker"

Fourth and fifth real-world findings, from the field recording: the user
granted every permission both apps offered, and shell STILL failed — while
Termux's own notifications told the real story ("RunCommandService requires
allow-external-apps property to be set to true in ~/.termux/
termux.properties") four times in three minutes. Meanwhile Vorv re-called
the dead shell every 30 seconds at iteration 5, 6, 7... and the user
discovered there was NO WAY TO STOP IT: the send button is disabled while
busy, and typed "stop" was silently dropped. Three laws were broken at
once: the wall's price wasn't named exactly, the loop had no backstop, and
the user had no exit. All three get fixed.

## The STOP button — no task is ever uncancellable again

- The header's "Live" pill becomes a red ■ STOP button while any task
  runs. One tap cancels the kernel loop at its next suspension point —
  mid-generation, mid-tool, mid-consent.
- Cancellation now PROPAGATES: the kernel rethrows CancellationException
  (previously its catch-all would have swallowed it into a fake
  TaskResult), and the ViewModel answers honestly: "Stopped — you
  cancelled this task," with partial work preserved in the Tasks tab
  (DB writes wrapped in NonCancellable so the log lands).
- The chat input is usable while busy again: "stop"/"cancel"/"abort"
  cancels; any other message gets an honest "I'm still working — tap
  STOP or send stop" instead of the old silent drop that locked the
  user out.

## The dead-shell circuit breaker — one timeout, then instant answers

- When a dispatched command never comes back (the one-time Termux setup
  wall), ShellTool records the wall in SessionGrants. Every subsequent
  shell call fails FAST — no consent dialog, no dispatch, no 30-second
  wait — with the exact three manual steps and the re-enable path.
- The block lifts only on a VERIFIED round-trip: the Setup probe passing
  or a real command completing. A session grant does NOT lift it —
  consent is not proof the wall is gone.
- The dispatch-timeout error is rebuilt: the exact commands to type in
  Termux, the full-restart step, the honest "I cannot do this for you"
  reason (the files live inside Termux's private storage), the Setup →
  Test connection verification path, and an explicit do-not-retry order.
  The old text named three causes but not one exact command.

## The retry-loop backstop — the kernel ends what the model won't

- Three consecutive failures of the same tool inject ONE corrective
  order: stop calling it, explain the wall to the user in plain English.
- Three more consecutive failures after the corrective → the kernel
  force-finalizes with the honest last error (error=retry_loop, logged
  as an episode). The recording's endless loop is now impossible: with
  the circuit breaker, retries fail instantly, so the whole backstop
  resolves in seconds rather than minutes.
- A single success resets the streak counter — legitimate retry-then-
  succeed flows are untouched.

## Tests

- TermuxSmokeTest grows to 17: the blocked shell fails fast with the
  manual fix and no dialog, blocked outranks verified in the prompt
  line, only a verified round-trip lifts the block, and the timeout
  message carries the exact commands.
- CognitiveKernelTest grows to 23: the 3-strikes corrective, the
  3-more force-final, the success-reset, and user-stop cancellation
  propagating out of the kernel un-swallowed (the v0.6.15 bug, pinned).
- 189 tests total, all green.

# Changelog — v0.6.15 "Stop Guessing, Start Asking"

Third real-world test finding: asked to use Termux, Vorv answered "I
don't have access to Termux" — WITHOUT EVER CALLING the shell tool. The
tool was registered, reachable and one consent dialog away. But its
description opened with "Requires: Termux installed, RUN_COMMAND
permission granted, ..." — four prerequisites that a small local model
reads as a disclaimer of unavailability — and the system prompt carried
live status only for the workspace, never for Termux. The model guessed,
guessed "no", and the honest-error machinery never even fired. Two
fixes: tell the model the LIVE truth, and make repeated consent honest
instead of nagging.

## The model is told the truth, not a guess

- New SessionGrants singleton (process-level, same binary-compat pattern
  as PermissionBridge — zero signature changes, registered patches keep
  working) tracks two things a session can KNOW or CHOOSE:
  - VERIFIED: a real Termux round-trip completed — the Setup probe
    passed, or a shell command's exit-code file actually appeared in the
    shared workspace. Knowledge, not consent.
  - GRANTED: the user chose "Allow all this session" on a shell consent
    dialog. Consent, explicitly batched.
- The kernel's system prompt now ends with a live Termux status line
  (SessionGrants.statusLine()) in one of three wordings: verified +
  granted (run freely, everything is logged and revocable); verified
  (it works; per-command dialogs are normal, not a failure); not yet
  verified (do NOT claim you cannot access Termux — call the tool and
  report its honest error). The prompt already did this for the
  workspace; Termux was the missing row.
- The shell tool's description is rewritten to lead with what to DO:
  "ALWAYS call this tool whenever the user asks for anything involving
  Termux, the shell, or running commands... NEVER claim you cannot
  access Termux without calling this tool first." The prerequisite
  disclaimer moved to where it belongs — the honest errors that fire
  only when something is actually missing.

## Session grants — consent that doesn't train you to click blindly

- Once shell works, every command popped a full dialog; a ten-command
  task became ten dialogs, training the user to hit Allow without
  reading. The shell consent dialog now offers a third answer: "Allow
  all this session". It approves the current command AND records a
  session-scoped grant.
- While the grant holds: no more dialogs, but every command is audited
  — the COMPLETE command goes to the episode log (kind
  shell_session_auto) and a counter the user can see in Setup. The
  grant replaces the dialog, never the record.
- The grant is never the default button, never silent, never persisted
  — it dies with the process. Revoke anytime: Setup → Termux
  integration → REVOKE. Grant and revoke are themselves logged as
  episodes. Shell-only: python_exec, app_intent and every other
  consented tool are untouched.
- Setup's Termux card now shows both live rows: "Shell VERIFIED this
  session — the assistant is told the shell works" and the grant state
  with its run counter.

## Smoke tests — the wiring, not just the units

- New TermuxSmokeTest drives the REAL CognitiveKernel with a scripted
  LLM and pins the system prompt's Termux line in all three session
  states; pins the rewritten description (and that the "Requires:"
  disclaimer opener is gone); and pins the consent mechanics end-to-end:
  the grant skips dialogs but not the workspace/permission gates, the
  audit record carries the complete command, revoke restores dialogs,
  fail-closed survives, and python_exec is unaffected by a shell grant.

# Changelog — v0.6.14 "The Wall Gets a Door"

Second real-world test finding, from a field recording: after v0.6.13
made the model ACT, the very next shell call hit the RUN_COMMAND
permission wall — and the honest error, while true, could only recite a
five-step Android Settings walk. The user's standing rule: never just
show the wall — show the cheapest real way through it. It turns out
Android HAS one: Termux declares com.termux.permission.RUN_COMMAND with
protectionLevel="dangerous", which in Android terms means
RUNTIME-REQUESTABLE. One tap. It was never being offered.

## One-tap grant where the model hits the wall

- New PermissionBridge: when the shell tool (or self_fix) finds the
  permission missing, it now pops Android's OWN system dialog —
  "Allow Vorv to run commands in Termux environment?" — after the
  command consent, so the user always knows what the grant is FOR.
  Grant → the command proceeds. Deny/dismiss/timeout/no-UI → the honest
  manual fallback error, naming every remaining path (unchanged law:
  fail closed).
- Why a process-level bridge and not a ToolContext field: binary
  compatibility with registered patches. Changing ToolContext's or
  KernelApi.run's signature would crash every previously-approved patch
  with NoSuchMethodError. The bridge adds zero signatures; old patches
  keep working exactly as before.
- self_fix now checks Termux readiness BEFORE the expensive pipeline —
  previously a missing permission was discovered 10 minutes into a
  15-minute heal, after the model had already written the patch.
- The shell tool's kernel budget grows 125s → 300s: two serial dialogs
  (consent up to 60s + permission up to 60s) plus the command timeout
  must fit inside the wall clock.

## Setup → Termux integration

- New Setup card with the live state of every gate: Termux installed?
  permission granted? — plus GRANT (the one-tap dialog on demand),
  Android Settings (manual fallback), and RECHECK.
- TEST CONNECTION runs exactly `echo vorv-termux-ok` through the same
  base64-transport round-trip the shell tool uses, then reports the
  REAL result — including the two Termux-side gates Vorv cannot reach
  (allow-external-apps, termux-setup-storage) with the exact commands
  to type, and an honest statement of WHY Vorv can't automate them
  (they live inside Termux's private data directory, blocked from every
  other app by Android design).

## No raw JSON in the chat

- Field finding from the same recording: when a memory lookup returned
  {"ok":true,"output":"No memories matched..."}, the model pasted the
  raw JSON into its reply and the user had no idea what it meant. New
  HARD RULE 10 in the system prompt: tool results are for the model,
  not the user's screen — explain in plain English, and when a tool
  fails, say what failed and what the user can do.

## Version

- `versionCode` 22 → 23, `versionName` 0.6.13 → 0.6.14.

---

# Changelog — v0.6.13 "Act, Don't Advise"

Found in real-world testing: asked to "use termux to install all the
packages you need", the local model (Qwen2.5-Coder-7B) replied with a
bash code block of suggested commands — ADVICE — instead of calling the
shell tool. Nothing ran; the honesty system flagged the answer
"uncited — no tool evidence" (that part worked exactly as designed).
But the agent still wasn't ACTING. Two fixes:

## The nudge: one correction when the model advises instead of acting

- When the model's answer contains runnable commands (a ```bash or
  ```python block) but it made NO tool call and NOTHING has been
  executed yet, the kernel sends it ONE corrective message: if the user
  asked you to PERFORM these actions, call the matching tool now (shell
  / python_exec); if the user only wanted advice, restate the final
  answer without runnable code blocks. Then the loop continues.
- The nudge never executes anything itself. Every execution still goes
  through the consent dialog — unchanged.
- At most ONE nudge per task (no loops burning slow on-device inference),
  and never after a tool already ran (quoting commands in a final
  summary is legitimate). Pinned by six new CognitiveKernelTest cases.
- Each nudge is logged as a "nudge" episode in the task's verifiable
  record.

## The standing rule: act-don't-advise in the system prompt

- New HARD RULE 9 in the kernel's system prompt: when the user asks you
  to DO something on this device, you MUST call a tool to actually do
  it — writing commands in a code block is NOT doing it.

## Version

- `versionCode` 21 → 22, `versionName` 0.6.12 → 0.6.13.

---

# Changelog — v0.6.12 "Show Me Everything, Part 2"

One more truncation was hiding in a place the v0.6.11 sweep missed:
the crash-repair patch dialog. It is now gone. Every approval surface in
the app — all five — now shows the complete, exact content, and the rule
is pinned by tests.

## The crash-repair patch dialog now shows everything

- The "Apply self-repair patch?" dialog (shown when you tap Self-repair
  after a crash) used to cut the SHA-256 to a 16-character prefix and the
  patch description to 400 characters. It now shows the COMPLETE
  64-character SHA-256 and the COMPLETE description — no prefix, no cap,
  no ellipsis. (`MainActivity.kt`)
- The dialog body now SCROLLS, so any length is fully readable, and the
  text is SELECTABLE — you can long-press, copy the hash, and verify it
  yourself outside the app. Length is never a reason to cut anything.
- The exact consequences of APPROVE and DENY (apply at next start /
  nothing changes / 72h auto-rollback) are stated in the dialog itself.
- **Pinned by tests**: `ConsentPromptTest` now covers all FIVE approval
  surfaces — shell, python_exec, app_intent (share + view/dial), the
  in-chat self_fix patch consent, and the crash-repair patch dialog —
  each asserting the complete content appears and no ellipsis does.

## Version

- `versionCode` 20 → 21, `versionName` 0.6.11 → 0.6.12.

---

# Changelog — v0.6.11 "Show Me Everything"

Resolves the two judgment calls the VRA-2026-1103 audit left to the
maintainer (N-I1, N-I4) under one rule: nothing is ever hidden or cut off
from the user. Nothing is disabled; every change strengthens an existing
guarantee.

## Consent prompts now show everything (N-I4)

- **shell** — the consent dialog shows the COMPLETE command, every line.
  The old 8-line preview with "+N more lines" is gone. (`SystemTools.kt`)
- **python_exec** — the first-run consent shows the complete program; the
  old 6-line preview is gone. (`PythonExecTool.kt`)
- **app_intent (share)** — the full text is shown; the old 120-character
  cut is gone. (`SystemTools.kt`)
- **self_fix** — the full 64-character SHA-256 is shown; the old
  16-character prefix with an ellipsis is gone. (`SelfFixTool.kt`)
- **The consent dialog itself now scrolls** (`VorvApp.kt`), so even a very
  long command is fully displayable. Length is never a reason to
  truncate anything.
- **Pinned by tests**: `ConsentPromptTest` (all four prompts contain the
  complete content and no truncation notice) and a new
  `SelfFixToolTest` case (full SHA-256, no ellipsis).

## Reserved code documented (N-I1)

- **`HotPatcher.fetchManifest`** is now formally reserved in SECURITY.md:
  reachable from no code path, kept for the planned remote-patch feature,
  HTTPS allow-list and pinned DNS intact. Making it live remains a
  deliberate decision, never an accident.

## Version

- `versionCode` 19 → 20, `versionName` 0.6.10 → 0.6.11.

---

# Changelog — v0.6.10 "Audit Follow-ups"

Every finding from the VRA-2026-1103 full-project line-by-line audit (158
files, 24,063 lines) fixed. The audit re-verified all 27 findings from
VRA-2026-1102 as still remediated and found 16 new items (3 medium, 9 low,
4 informational) — all addressed below. Nothing is disabled; every fix
preserves behavior.

## Medium

- **N-M1 — healer classpath went stale after an app update.** The extracted
  `app_classes.jar` (filesDir) and the workspace-staged copy were reused
  without a version check, so the first self-repair after an update compiled
  against the PREVIOUS app's classes while PatchVerifier compared against the
  new original. Both copies are now stamped with `BuildConfig.VERSION_CODE`
  and re-extracted/re-staged whenever it changes. (`HealingAgent.kt`)
- **N-M2 — persisted tool outputs could overwrite each other.** Full outputs
  were named `<iteration>_<tool>.txt`; two calls to the same tool in one
  assistant turn (parallel tool calls) collided, and both DB rows pointed at
  a file holding only the second output. Filenames now carry the per-task
  call index. Pinned by a new CognitiveKernelTest case.
  (`CognitiveKernel.kt`)
- **N-M3 — tool inventory undercounted (15 vs 16).** `self_fix` was missing
  from the Tools screen entirely, and the Tools header, the Setup About text
  and the README diagram all said "15". The inventory now lists all 16 with
  an honest description of self_fix. (`ToolsScreen.kt`, `SettingsScreen.kt`,
  `README.md`)

## Low

- **N-L1** — `CloudLlm` no longer swallows `CancellationException`: a timed-out
  cloud task's worker stops retrying immediately instead of running up to 4
  background attempts. (`Llm.kt`)
- **N-L2** — `BootTracer.start()` writes are guarded: an I/O failure before
  the crash handler installs can no longer crash the app unlogged.
  (`BootTracer.kt`)
- **N-L3** — dead `patchConsent` field removed from `MainActivity`.
- **N-L4** — `BootTracer` writes are `@Synchronized`: GenieX callbacks write
  breadcrumbs from binder threads; lines can interleave in order but never
  corrupt. (`BootTracer.kt`)
- **N-L5** — watchdog relaunch circuit breaker: at most 5 relaunches per
  10 minutes. A persistent pre-safe-mode native crash can no longer spin
  crash→relaunch forever. (`WatchdogService.kt`)
- **N-L6** — a HotPatcher manifest entry without a `classes` list is
  quarantined like any other bad entry instead of NPE-ing and aborting the
  whole patch loop. (`HotPatcher.kt`)
- **N-L7** — `self_fix` budget aligned to the real 15-minute ceiling (was a
  documented 21 minutes that the task timeout and healer budget made
  unreachable). (`SelfFixTool.kt`)
- **N-L8** — the crash-dialog analysis and self-repair paths refuse to start
  while a chat task is running, so two native GenieX engines can never hold
  the NPU at once. (`MainActivity.kt`)
- **N-L9** — duplicated H-4 doc block removed from `app/build.gradle.kts`
  (introduced with the CI dependency fix).

## Informational

- **N-I2** — SECURITY.md consent section now names every gated tool
  (app_intent, shell, python_exec first-use, self_fix); the hashlib sandbox
  self-test asserts the real digest prefix instead of a vacuous empty string.
- **N-I3** — README status header no longer pins a stale version; the
  acceptance-test status line likewise.
- Also fixed in 2040a41 (same series): CI run #57's
  `mergeReleaseAssets` WorkValidationException — the healer asset producer is
  now a registered generated-asset directory with explicit consumer edges, so
  the build order is deterministic instead of a coin flip. Verified by
  run #58's green test job.

---

# Changelog — v0.6.9 "27/27 Remediated"

This release fixes every finding from the VRA-2026-1102 forensic audit
(27 findings: 2 critical, 5 high, 10 medium, 10 low) — in-place hardening
that PRESERVES every feature. Nothing is disabled: Python execution, JS
execution, DEX hot-patching, the watchdog, shell, and network tools all
keep working, now with real gates.

Severity legend: C = Critical, H = High, M = Medium, L = Low.

---

## Critical

### C-1 — python_exec sandbox was escapable (dunder guard only wrapped the getattr() builtin; whitelisted modules leaked os/sys)
**Was:** the guarded getattr/hasattr/setattr builtins never saw attribute SYNTAX — `uuid.uuid4.__globals__['os']` handed user code the live os module (uuid.py imports os/sys at module level). Verified by the audit's PoC.
**Now (vorv_runner.py, Layer 0):**
- namespace scrubbing of the entire whitelist closure: real os/sys references are replaced with inert proxies (only urandom/getpid and safe sys constants survive — C builtins with no reachable namespace);
- every callable the sandbox exposes (guarded getattr/setattr/hasattr, the safe `__import__`, the meta-path finder, the trace watchdog) is defined in a CLEAN namespace, so `fn.__globals__` yields nothing;
- a transitive verification walk (module dicts, function `__globals__`, class dicts, MROs) asserts no real os/sys is reachable from the whitelist; a module that cannot be scrubbed or whose canary self-test fails is dropped from the session whitelist with an honest ImportError;
- **the user-consent bridge gates the first python_exec call per task** (audit-recommended: a prompt-injected model can call it up to 30 times/task) — the in-process sandbox is documented as hardening, not a barrier (SECURITY.md updated);
- test_vorv_sandbox.py gains the direct-syntax attack battery: the exact audit PoC, attribute-syntax walks on random/json/statistics, inert-proxy refusal, clean-namespace probing of the exposed guard functions.
**Files:** `app/src/main/python/vorv_runner.py`, `app/src/main/python/test_vorv_sandbox.py`, `PythonExecTool.kt`

### C-2 — HotPatcher loaded unsigned .dex files and skipped hash verification when a manifest entry was missing
**Was:** a self-attested dex loading into the app process; a missing manifest entry meant NO verification at all; auto-generated patches never required user consent; and loaded patches never actually ran (see the dispatch fix below).
**Now:**
- every patch dex is **signed with the device patch keypair** (`PatchKeys`: Android-Keystore Ed25519 on API 33+, ECDSA P-256 fallback, software fallback for environments without a Keystore) and HotPatcher REFUSES (quarantines, never skips) any dex failing signature, hash, version-gate, consent-registry, or dispatch-interface checks;
- the manifest fetch is pinned to HTTPS allow-listed hosts and resolves through the NetworkGuard DNS;
- **every auto-generated patch goes through the user-consent bridge** (the same APPROVE/DENY dialog as app_intent and shell) before anything is registered;
- the v0.6.5 "load the class" mechanism could never dispatch (same-FQCN classes under different classloaders are different runtime identities) — patches now run through **interface-based dispatch**: shared interfaces (Tool / KernelApi / HealerApi) + `PatchRegistry` with an honest 18-class patchable surface, and the kernel/healer/tools are constructed through it;
- **probation + auto-rollback**: a patch followed by a crash in the class it overrides within 72h of first apply is rolled back at next boot (a fix that makes things worse undoes itself).
**Files:** `watchdog/PatchKeys.kt` (new), `watchdog/HotPatcher.kt`, `watchdog/PatchRegistry.kt` (new), `healer/HealerApi.kt` (new), `kernel/KernelApi.kt` (new)

## High

### H-1 — Watchdog crash detection was structurally dead
**Was:** the watchdog process ran the same `VorvApplication.onCreate` and wrote its own "alive" heartbeats (poisoning the stale-heartbeat check), MainActivity never called bindService (the documented binder-death signal did not exist), and the heartbeat pid was recorded but ignored.
**Now:** `VorvApplication` guards init by process name (main process = full init; `:watchdog` = none); heartbeats are written exclusively from the main process; **MainActivity binds to the watchdog and registers a death token** — `linkToDeath` fires immediately when the main process dies, with the pid-checked stale heartbeat as fallback.

### H-2 — shell executed arbitrary bash with no consent gate
**Now:** ShellTool routes through `ctx.confirm` with the first lines of the command in the dialog — the same bar as app_intent. Fail-closed without the bridge; a denial is reported honestly.

### H-3 — browser_automate validated only the initial URL (redirects/JS navigations bypassed the guard)
**Now:** a `GuardWebViewClient` runs `NetworkGuard.checkHostAllowed` on EVERY navigation (`shouldOverrideUrlLoading`) and EVERY sub-resource request (`shouldInterceptRequest` — iframes, fetches, XHRs). Blocked navigations fail the tool call with the reason.

### H-4 — the self-heal compile pipeline could not work on a stock device (Termux paths it cannot read)
**Now:** the compile round-trips through the SAF workspace exactly like ShellTool (script/source/jar/dex inside the granted folder, `/storage/emulated/0/...` paths), the app classpath jar is PACKAGED at build time (new `packageHealerClasspath` Gradle task → `assets/healer/app_classes.jar`) and staged into the workspace before each compile, and the heal runs in the MAIN process (see M-8). Tool-missing errors (kotlinc/d8) are honest and actionable.

### H-5 — the bundled source mirror (assets/source) was stale
**Now:** a `syncSourceMirror` Gradle task copies the live tree into `assets/source` on every build (wired into preBuild) — drift is structurally impossible, so patches are always generated against the code that matches the running binary.

## Medium

- **M-1 (DNS-rebinding TOCTOU):** WebView requests are proxied through an OkHttp client wired to `NetworkGuard.PublicDns` — one resolution, checked, and used for the actual transfer.
- **M-2 (unbounded local-mode context):** `LocalLlm.contextTokenBudget` states the honest 1800-token prompt budget; the kernel estimates size per iteration and compacts completed tool results into a digest (system + task + recent turns survive); GenieX context/token errors surface as "context window exhausted at iteration N".
- **M-3 (Rhino CVE-2025-66453):** bumped to `org.mozilla:rhino:1.7.15.1`; the JsExecTool attack suite pins the upgrade.
- **M-4 (clear-all-data left data behind):** clearAllData now cancels every registered reminder (WorkManager + registry), clears the registry preference, recursively deletes tool-output files, and purges crash/watchdog/healer diagnostics. The API key is kept (a setting, not agent data) and the dialog says so.
- **M-5 (file_read buffered whole file before the cap):** streams at most 200 KB then truncates to the 50 000-char cap.
- **M-6 (web_fetch decoded UTF-8 per 16 KB chunk):** decodes through an InputStreamReader — multi-byte characters split across chunk boundaries survive.
- **M-7 (vorv.db in cloud backup despite "stays local"):** the database, crash logs and healer diagnostics are excluded from cloud backup AND device transfer; SECURITY.md states exactly what participates.
- **M-8 (watchdog ran runBlocking LLM heals on the heartbeat thread):** the watchdog NEVER runs the LLM. Healing runs in the restarted MAIN process (crash dialog "Self-repair") on Dispatchers.IO under a hard 15-minute budget.
- **M-9 (schedule enqueue/registry not atomic):** register-first-then-enqueue with rollback on enqueue failure; expired entries are returned for cancellation too, so nothing lingers uncancellable.
- **M-10 (crash filenames had one-second resolution):** millisecond timestamp + pid in the filename.

## Low

- **L-1:** Gradle wrapper committed (gradlew, gradlew.bat, gradle-wrapper.jar, `distributionSha256Sum`); CI uses `./gradlew`.
- **L-2:** DEVELOPMENT.md describes the actual schema decision (exportSchema off; MigrationTest builds v1 by DDL).
- **L-3:** the release tag is derived from the real `versionName` in app/build.gradle.kts.
- **L-4:** carrier-grade NAT 100.64.0.0/10 blocked in NetworkGuard.
- **L-5:** JsExecTool creates a WatchdogFactory per run (no shared mutable deadline/aborted state).
- **L-6:** file_write colliding with a directory name is an honest error, not a provider-suffixed duplicate.
- **L-7:** BootTracer writes BOOT_STARTED before the hot-patch step (a hotpatch-phase crash is no longer misclassified as the previous boot's).
- **L-8:** IP literals are detected syntactically — no DNS lookup for non-literal hosts in parseLiteral.
- **L-9:** AgentService declared `specialUse` with the required property (the honest type for a compute workload).
- **L-10:** notification IDs come from an AtomicInteger seeded randomly — no truncated-timestamp collisions.

## Feature completion (user requirements)

- **Self-healing covers EVERY problem, not just crashes**: `HealingAgent.modify(HealRequest)` runs the same hardened pipeline from a free-text problem statement; the crash-dialog path is a thin wrapper over it.
- **`self_fix` agent tool (16th)**: the user can tell Vorv IN CHAT to fix or change its own code ("make notify use a different sound"); the agent drives generate → compile → verify → sign → user consent → apply-at-next-boot, fail-closed at every step.
- **Completeness gate (`PatchVerifier`)**: before a patch is signed or shown to the user, it is loaded in an isolated classloader and checked against the running class — every public constructor, method and field must survive, the dispatch interface must be implemented. Failures feed back into the regeneration loop. **No partial fixes, ever.**
- **Target-class inference** for problems that do not name a class: the model may only pick from the dispatchable surface; anything else is refused honestly.
- **Multi-class source files handled** (ShellTool lives in SystemTools.kt): the source lookup scans the package for the class declaration instead of assuming `<Class>.kt`.
- **CrashParser no longer swallows the method name** (`…NotifyTool.run` → `host.vorv.tools.NotifyTool`), which used to break every source lookup.
- **Repairs use the STRONGEST on-device model**: `ModelManager.bestRepairModel()` picks the 7B whenever it is downloaded (the 3B only if the 7B was never pulled), and `repairLabel()` names the model that wrote each patch — a 3B patch can never masquerade as a 7B one.

## Also fixed along the way

- CI workflow: the push trigger had a broken `branches:` list (a stray `[` made it `ain, …`), so pushes to main did not run CI deterministically.
- `CrashReportWorker` filenames include the pid (two crashes in the same millisecond from different processes cannot overwrite each other).

---

# Changelog — v0.6.0 "Full Remediation"

This release implements every finding from the forensic audit (44 findings:
6 critical, 10 high, 12 medium, 10 low, 6 informational). No triage, no
deferrals — everything below is fixed in code, and the fixes are enforced by
CI (unit tests + the Python sandbox self-test run on every push).

Severity legend: C = Critical, H = High, M = Medium, L = Low, I = Informational.
("S-xx" = architectural hardening from audit §10.2/§11.3 not in the numbered list.)

---

## Critical

### C-1 — js_exec was not a sandbox
**Was:** `Context.initStandardObjects()` with no `ClassShutter` — LLM JavaScript could call `Packages.java.lang.Runtime.getRuntime().exec(...)`, read the app database, exfiltrate the API key, or `System.exit(0)`.
**Now:** `JsExecTool` runs on a custom `ContextFactory` with:
- `ClassShutter` that allows only Rhino internals + immutable `java.lang` types (String/Boolean/Number/Math/…);
- all LiveConnect globals (`Packages`, `java`, `javax`, `org`, `com`, `net`, `io`, `android`, `androidx`, `host`, `jdk`, `sun`, …) and `importClass`/`importPackage`/`JavaAdapter`/`JavaImporter` deleted from the scope;
- the scope **sealed** (`ScriptableObject.sealObject()`).
**Tests:** `JsExecToolTest` — 8 attack cases (Runtime, Packages, File read, System.exit, importClass/Package, JavaAdapter, reflection) all blocked; legit ES6 + console + timeout verified.
**File:** `app/src/main/java/host/vorv/tools/JsExecTool.kt`

### C-2 — python_exec was not a sandbox, and its description lied
**Was:** `exec(code, {"__builtins__": __builtins__})` — full builtins, full filesystem, raw sockets. The description claimed "cannot access files outside the workspace" and "requests available" — both false.
**Now:** `vorv_runner.py` rebuilt as a four-layer sandbox:
1. `sys.addaudithook` blocking file/process/socket/ctypes/frame events (C-level, unremovable);
2. restricted `__builtins__` (no `open`/`eval`/`exec`/`compile`/`__import__`; dunder-guarded `getattr`/`setattr`/`hasattr`);
3. import whitelist of pre-imported pure-computation stdlib modules (`os`/`sys`/`socket`/`subprocess`/`ctypes`/`pickle`/`time`/`threading` → `ImportError`);
4. `sys.settrace` time-budget watchdog.
Kotlin side serializes runs (stdout is process-global) and enforces a hard join timeout for C-level stalls. The tool description is now honest about capabilities and limits.
**Tests:** `test_vorv_sandbox.py` — 55 assertions (file I/O, network, process, escapes, whitelist, timeouts, honest errors), runs in CI with plain python3.
**Files:** `app/src/main/python/vorv_runner.py`, `app/src/main/python/test_vorv_sandbox.py`, `app/src/main/java/host/vorv/tools/PythonExecTool.kt`

### C-3 — shell tool fabricated its output and lied to the LLM
**Was:** Termux RUN_COMMAND is fire-and-forget; the tool returned a placeholder string with `ok=true`, which the kernel logged and fed to the LLM as the command's real result.
**Now:** `ShellTool` does a real workspace round-trip:
- maps the SAF workspace tree URI to its `/storage/emulated/0/...` path (primary storage only, else honest error);
- creates `.vorv-shell/` in the workspace;
- dispatches `bash -c "printf '%s' <b64> | base64 -d | bash > out 2> err; echo $? > code"` — the command travels base64-encoded (no quoting bugs), outputs land where both Termux and Vorv can reach them;
- polls for the exit-code file via SAF (250 ms interval, 1–120 s budget);
- returns the REAL stdout (100 KB cap), stderr (20 KB cap) and exit code; cleans up.
- If capture is impossible, returns `ok=false` explicitly telling the model the result is UNKNOWN and listing the exact setup steps (`termux-setup-storage`, `allow-external-apps=true`, workspace on primary storage).
**File:** `app/src/main/java/host/vorv/tools/SystemTools.kt`

### C-4 — browser_automate WebView allowed file:// reads and arbitrary JS
**Was:** `javaScriptEnabled` + default `allowFileAccess`/`allowContentAccess`, no scheme filter, hand-rolled JS string escaping.
**Now:** `allowFileAccess`, `allowContentAccess`, `allowFileAccessFromFileURLs`, `allowUniversalAccessFromFileURLs` all **false**; `MIXED_CONTENT_NEVER_ALLOW`; http/https scheme allowlist; `NetworkGuard.checkHostAllowed()` host pre-check (no localhost/LAN/metadata); JS built with Gson `toJson` interpolation; `destroy()` releases the WebView from `ViewModel.onCleared`.
**File:** `app/src/main/java/host/vorv/tools/BrowserAutomateTool.kt`

### C-5 — API key stored unencrypted in DataStore
**Was:** plaintext protobuf at `files/datastore/vorv_prefs.preferences_pb`, readable by the unsandboxed exec tools (C-1/C-2) or `adb`.
**Now:** `SecretsStore` (EncryptedSharedPreferences, AES-256-GCM, Android-Keystore master key — hardware-backed on the S25). The old plaintext DataStore key is migrated once on first launch and erased. Non-secret settings stay in the renamed `VorvPrefs`.
**Files:** `app/src/main/java/host/vorv/data/SecretsStore.kt`, `VorvPrefs.kt`, `VorvApplication.kt`

### C-6 — README materially misrepresented the app
**Was:** claimed local LLM was "a stub", and shell/python/web/browser/notify/schedule were "pending" — all were shipped and wired.
**Now:** README fully rewritten with an honest status block, accurate architecture diagram, a per-tool safety table, and links to the new `SECURITY.md` (full threat model with residual limits), `DEVELOPMENT.md` (env setup, release-signing secrets, adding-a-tool guide), `CONTRIBUTING.md` and `LICENSE` (MIT, previously missing). In-app copy (`ToolsScreen`, `SettingsScreen`) updated to match reality.
**Files:** `README.md`, `SECURITY.md`, `DEVELOPMENT.md`, `CONTRIBUTING.md`, `LICENSE`

---

## High

### H-1 — dial premium numbers / no confirmation for intents
`AppIntentTool`: every action (view/share/dial/open_app) now goes through the user-consent bridge (`ToolContext.confirm`) — an Allow/Deny dialog showing exactly what will happen; when the bridge is absent the tool **fails closed**. `DialGuard` refuses premium-rate patterns (US 1-900/1-976, UK 09xx/118, IT 89x/090x, AU 190x, HK 166x) and malformed numbers (length 7–15 digits, charset check). `view` restricted to http/https. Denials are reported to the model as failures.
**Files:** `tools/SystemTools.kt`, `tools/Tool.kt`, `AgentViewModel.kt`, `ui/VorvApp.kt` — tests: `DialGuardTest`, `CognitiveKernelTest`

### H-2 — POST_NOTIFICATIONS never requested
`MainActivity` requests it once per install via the registered permission launcher; `NotifyTool` checks it before posting and returns an honest error (with the exact Settings path) when missing.
**Files:** `MainActivity.kt`, `tools/SystemTools.kt`

### H-3 — cross-thread Rhino watchdog (undefined behavior)
Replaced with Rhino's documented interruption mechanism: a `ContextFactory` subclass overriding `observeInstructionCount` checks a wall-clock deadline every 10 000 interpreted instructions **on the evaluating thread**; `cx.instructionObserverThreshold = 10_000` enables it.
**Files:** `tools/JsExecTool.kt` — test: `JsExecToolTest` (infinite loop stopped)

### H-4 — localLlm released out from under in-flight inference
New `ModelRepository` owns the engine lifecycle: one `Mutex` serializes every transition (`activeEngine`/`swapModel`/`deleteModel`/`shutdown`); swaps are refused while a task is running (`busy`); the engine is (re)built lazily under the lock; `onTrimMemory` releases it when idle; `ViewModel.onCleared` shuts it down. The Settings UI disables model controls while busy (UI mirrors the rule, repository enforces it).
**File:** `llm/ModelRepository.kt`, `AgentViewModel.kt`, `ui/screen/SettingsScreen.kt`

### H-5 — fallbackToDestructiveMigration would wipe user data
Removed everywhere. Room bumped to **version 2** with a real `Migration(1,2)` (`ALTER TABLE tool_calls ADD COLUMN outputFilePath TEXT`). `MigrationTest` builds a v1 database by DDL, runs the real migration object and verifies the column exists and data survives. (Schema JSON export stays off: it is tooling sugar, and the KSP arg-based export path hit a Room 2.6.1 "Empty schema file" failure on the release variant in clean CI — the migration does not depend on it.)
**Files:** `data/VorvDatabase.kt`, `VorvApplication.kt`, `app/build.gradle.kts` — test: `MigrationTest`

### H-6 — zero automated tests
10 test files added (~120 assertions) + CI `gradle test` job. To make the kernel testable, persistence moved behind `KernelEventSink` (production: `RoomKernelEventSink`; tests: fakes) and the LLM-output parser, citation stripper, dial guard, schedule validator, network guard and tool-argument validator were extracted into pure objects. Covered: parser (incl. truncated-JSON repair), kernel loop (validation, rate limits, timeouts, citations, consent pass-through, stuck-at-cap, full-output persistence), Rhino attacks, WebFetch (MockWebServer), network guard, migration.
**Files:** `app/src/test/...` (10 files)

### H-7 — CI published a debuggable debug APK as the Release
Release build now `isMinifyEnabled = true` + `isShrinkResources = true` with complete ProGuard rules (Rhino, GenieX, Chaquopy, OkHttp, Tink, Room, our classes). Signing is env-var-driven (`RELEASE_KEYSTORE_*`, decoded from GitHub secrets) — no key material in the repo. The **committed vorv-debug.keystore is deleted** (`.gitignore` already covered it). The publish job runs only on main **and** only when the keystore secret exists, and ships the signed release APK; otherwise it warns visibly and ships nothing installable.
**Files:** `app/build.gradle.kts`, `proguard-rules.pro`, `.github/workflows/build.yml`, deleted `vorv-debug.keystore`

### H-8 — web_fetch had no response size cap
Streams at most 2 MB (16 KB chunks), refuses when `Content-Length` exceeds the cap, and the extracted text is capped at 8000 chars for the prompt.
**File:** `tools/WebTools.kt` — tests: `WebFetchToolTest`

### H-9 — memory_write LIKE-race could update the wrong row
The UUID is generated up-front and `citationRef` is set before insert — the entire searchLike-and-update dance is gone. No lookup, no race.
**File:** `tools/MemoryTools.kt`

### H-10 — citation enforcement only checked iteration 1
Tool-captured and inline citations are now tracked in separate lists; the answer is flagged `[uncited]` when **both** are empty, at any iteration. Extracted into `CitationStripper` (tested).
**Files:** `kernel/CitationStripper.kt`, `kernel/CognitiveKernel.kt`

---

## Medium

| ID | Fix |
|---|---|
| M-1 | `LocalLlm maxTokens` 768 → 2048; `ToolCallParser.repairJson` balances braces/strings so truncated tool calls recover instead of degrading to raw text (tested) |
| M-2 | `CloudLlm` backoff uses `delay()`, never `Thread.sleep`, inside `withContext(IO)` |
| M-3 | Mode-aware task timeout: 5 min cloud / 15 min local, wrapped around `kernel.run`; timeout fails the task honestly and preserves the partial log |
| M-4 | `CloudLlm` guards empty `choices[]` with an actionable error; null-safe `finish_reason`/`id`/`arguments` |
| M-5 | `ScheduleTool` enqueues **unique** work names (`vorv_reminder_<task>_<iter>`, REPLACE policy), registry capped at 20 pending; new `schedule_cancel` tool cancels by title or all |
| M-6 | Minutes validated by `ScheduleValidator`: 1–10 080 (7 days), finite (tested) |
| M-7 | Outputs > 2000 chars are persisted to `cacheDir/tool_outputs/...` and the `tool_calls` row (new `outputFilePath` column, DB v2) records the path; Tasks screen expands to the verifiable log with a "View full output" dialog |
| M-8 | `bootstrapSkills` checks **each** tool individually, so upgrades register new tools |
| M-9 | `SkillDao.ensureExists` (INSERT OR IGNORE) runs before every skill stat update — no more silent no-ops |
| M-10 | `teach()` now inserts a real `ToolCall` row (+ episode), keeping the verifiable log complete |
| M-11 | `LocalLlm.release()` logs failures with stack trace instead of swallowing |
| M-12 | `AgentService` + `ReminderWorker` notifications have `setContentIntent` (tap opens the app), IMMUTABLE PendingIntents |

---

## Low & Informational

| ID | Fix |
|---|---|
| L-1 | `KeyStore` split into `SecretsStore` (encrypted secrets) + `VorvPrefs` (non-secret settings) |
| L-2, L-3 | open_app/share require user confirmation; share text is previewed in the dialog |
| L-4 | HTML→text via jsoup with block-marker preservation (regex parser deleted) |
| L-5 | Retrofit + converter removed from catalog and build (never used) |
| L-6 | okhttp-logging removed (was declared, never wired — would have risked header logging) |
| L-7 | `ViewModel.onCleared` releases the GenieX engine + destroys the headless WebView; `VorvApplication.onTrimMemory` releases the engine when idle |
| L-8 | Full ProGuard keep rules for GenieX, Chaquopy, Tink, Rhino, Room, tools/kernel/llm (now that R8 is on) + line-number attributes |
| L-9 | Release signing config env-driven; wired into CI with secrets |
| L-10 | `stopForeground(Service.STOP_FOREGROUND_REMOVE)` qualified |
| I-1 | All CI actions pinned to full commit SHAs (checkout v4.4.0, setup-java v4.9.1, setup-python v5.6.0, setup-gradle v3.5.0, upload-artifact v4.6.2, download-artifact v4.3.0, gh-release v2.6.2); job-level minimal permissions; concurrency cancellation |
| I-2 | `parallel=false` kept (upstream Chaquopy+KSP lock) — documented in DEVELOPMENT.md as a known quirk with a tracking note |
| I-3 | `WebSearchTool` rewritten against the **documented** Z.ai Web Search API (`POST /api/paas/v4/web_search`, `search_query`/`count`, `search_result[]`) — the old `/functions` endpoint does not exist in the public API |
| I-4 | User-Agent is versioned from `BuildConfig.VERSION_NAME` |
| I-5 | Legacy store names kept deliberately (renaming breaks existing installs); convention documented |
| I-6 | ToolsScreen copy is honest (sandbox mechanisms, shell requirements, confirmation gates) |

---

## Architectural hardening (audit §10.2 / §11.3)

- **`ToolCallValidator`** — LLM arguments validated against each tool's schema before dispatch (required fields, string/integer/number/boolean types). Tested.
- **Per-tool wall-clock timeouts** — `Tool.timeoutMs` enforced by the kernel via `withTimeoutOrNull`; honest timeout errors are logged and fed back.
- **Per-task rate limits** — `Tool.maxCallsPerTask` enforced by the kernel (e.g. web_fetch 8/task, shell 10/task); the model gets an explicit "stop repeating this tool" message.
- **`NetworkGuard`** — OkHttp `Dns` (`PublicDns`) + host/address predicates blocking loopback, link-local, site-local, unique-local, any-local, multicast, `*.local`, `*.internal`, metadata endpoints. Applied to web_fetch, web_search, CloudLlm, and pre-checked in browser_automate. Tested.
- **Consent bridge** — `ToolContext.confirm` + `PendingConfirmation` state + AlertDialog in `VorvApp`; serialized (one dialog at a time), 60 s auto-deny, denial reported to the model as failure.
- **Prompt-injection note** — system prompt marks tool outputs/file contents/web pages as untrusted data, never instructions (rule 7), and documents the confirmation behavior (rule 8).
- **Data retention** — episodes/completed tasks/tool calls pruned after 30 days at boot; "Clear ALL data" button (confirmation-gated) wipes every table.
- **file_write cap** — 1 MB per call, honest error beyond it.
- **Manifest hygiene** — removed unused `RECEIVE_BOOT_COMPLETED` and `WAKE_LOCK` declarations; kept `com.termux.permission.RUN_COMMAND` (required to be grantable for the shell tool).

---

## Not fixed (with reasons)

- **Rhino upgrade** (1.7.15 → 1.9.x) — deliberately deferred: the sandbox fix does not depend on it, and an engine swap mid-remediation would invalidate the attack-test baseline. Dependabot will propose the bump; run `JsExecToolTest` against it.
- **Subprocess isolation for python_exec** — the genuinely complete fix for the C-extension-stall residual limit. Android offers no practical unprivileged process sandbox with IPC this cheap; the layered sandbox + honest documentation is the current right answer. Tracked in SECURITY.md as a known residual limit.
