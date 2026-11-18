# Review and plan

The reviewer's plan for this repository, and where each item stands. Every
item comes from reading the code and the CI history: nothing here is guessed.

Owner's two standing rules, which most of Phase 1 exists to serve:

* nothing fails silently — a failure must say what failed and why;
* nothing succeeds silently — a green light must be backed by a check that
  actually ran.

## 1. Phase 1 — the foundation has to be trustworthy

| # | Item | Status |
|---|------|--------|
| 1 | Rootfs download: retries, redirects, ranged/resumable download | done (`71a3111`) |
| 2 | Payload cache so a rebuild does not refetch 200 MB | done (`7a77262`) |
| 3 | Green CI at `7a77262`, stale PR #35 closed | done (run 44015457552) |
| 4 | Robolectric pinned at 4.17; Dependabot ignores 4.18 | done |
| 5 | Lint and a concurrency group with `cancel-in-progress` | done |
| 6 | Debug build moved to a `.ci` application id so CI installs beside the owner's app | waiting — belongs with item 7 |
| 7 | Release signing key, so an APK can be updated in place | declined by the owner for the debug phase |
| 8 | Backup rules exclude the database, secrets, crash logs | done |
| 9 | A decision recorded for every closed security PR #15–#30 | done — `docs/CLOSED-PR-DECISIONS.md` |
| 10 | Branch ruleset, CodeQL, non-provider secret patterns | done except two GHAS-only flags — see below |
| 11 | JS sandbox output bound (PR #26) | done — 64 KB per stream, visible truncation |
| 12 | No new silently swallowed failures | done — `Attempt` + guard test at 153 sites |
| 13 | APK gates: stale source mirror, size, manifest comment | done — `verify-apk-payload.py` |
| 14 | Split the 1,980-line view model, one extraction per commit | in progress — 2 of 4 |
| 15 | A CI job that installs the APK on an Android and taps it | not possible with an arm64-only payload |

## 2. What each open item needs

* **Item 6 + 7.** They are one decision, not two. Moving debug builds to
  `host.vorv.ci` stops CI APKs from landing on top of the owner's app, but it
  also means the owner's own updates need a release build that installs over
  the existing one — which needs the release key he has refused while
  debugging. Ask once, then do both together.
* **Item 10, remainder.** `secret_scanning_non_provider_patterns` and
  `secret_scanning_validity_checks` accept the request and stay `disabled`:
  both are GitHub Advanced Security features. Nothing to fix in the repo.
* **Item 14.** Two extractions done (`llm/LlmFailure.kt`,
  `agent/ConsentGate.kt`), both now tested. Left: persistence, and the send
  path. Each is one commit, behaviour unchanged, tests first.
* **Item 15.** The app ships arm64 `proot`, an arm64 guest JVM and arm64 NPU
  libraries; GitHub's Linux runners are x86_64 and cannot run an arm64 image.
  An emulator job would install an app with no native payload and prove
  nothing. In its place: the payload gate and the mirror gate on every real
  APK, and the owner's pass on the phone.

## 3. Phase 2 — the coder itself (not started)

These are the items that decide whether the app can build a large, custom
Android app, which is the whole point of owning it:

1. **Learning ledger.** Every task stores what was tried, what failed, and
   what fixed it; the next prompt is given the closest past cases. Without
   this the agent cannot get better at the owner's projects.
2. **Code search index.** A symbol/file index of the workspace so a task can
   find the right file instead of reading everything.
3. **On-phone trainer.** Fine-tune or adapt on the interactions the owner
   approves, with an evaluation set that must improve before a new adapter is
   kept.
4. **A scoring test.** A fixed set of small Android tasks run against the
   coder; a change that lowers the score is refused. This is what turns "it
   feels better" into a number.

## 4. Status log

### 2026-11-18 — 0.9.21 / 0.9.22 / 0.9.23

* **Item 9 — done.** `docs/CLOSED-PR-DECISIONS.md` records, for each closed PR
  #15–#30, whether the concern is handled (and where), declined on purpose
  (and why), or still open (and what it needs).
* **Item 10 — done, except the two GHAS flags.** Now active: branch ruleset
  `main-protection` (blocks deletion and force-push of `main`; id 27556986)
  and CodeQL default setup (first run succeeded).
* **Item 11 — done.** 64 KB per sandbox stream with a visible truncation line
  (`JsExecTool.kt`), pinned by `JsSandboxOutputCapTest`.
* **Item 12 — done, in the cheap form.** `host.vorv.tools.Attempt` logs a
  survivable failure in one line naming what failed and why;
  `SilentFailureGuardTest` freezes the count of discarded `runCatching` sites
  at 153 and refuses a new one. A detekt rule needs a rules jar in every
  build; this gives the same guarantee for new code without that dependency.
* **Item 13 — done.** `tools/forge/verify-apk-payload.py` refuses an APK that
  (a) ships an `assets/source` mirror differing from the sources it was built
  from, or (b) is above 400 MB. Each APK is checked before upload. Verified
  locally: run against the released 0.9.21 APK with a newer working tree it
  refuses and names both drifting files; in CI on a matching tree it passes.
  The manifest backup comment now matches what the rules actually do.
* **Item 14 — two of four.** `explainLlmFailure` (401/404/402/429/5xx
  sentences) moved to `llm/LlmFailure.kt`; the consent dialog's ask/answer/
  timeout moved to `agent/ConsentGate.kt`. Six and five tests respectively.
* **Item 6/7 — waiting on the owner**, as described in section 2.
* **Item 15 — not possible**, as described in section 2.
