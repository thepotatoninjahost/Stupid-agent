
## 7. Status log

### 2026-11-18 — 0.9.21 / 0.9.22

* **Item 9 — done.** `docs/CLOSED-PR-DECISIONS.md` records, for each closed PR
  #15–#30, whether the concern is handled (and where), declined on purpose
  (and why), or still open (and what it needs).
* **Item 10 — done, except two GitHub-side flags.** The fine-grained token
  could not touch repository administration, and a token with `repo`+admin
  rights could. Now active: branch ruleset `main-protection` (blocks deletion
  and force-push of `main`; id 27556986) and CodeQL default setup. Still
  refused: `secret_scanning_non_provider_patterns` and
  `secret_scanning_validity_checks` — the API accepts the request and leaves
  them `disabled`; both are GitHub Advanced Security features.
* **Item 11 — done earlier.** Lint is a required job; the workflow has a
  concurrency group with `cancel-in-progress`.
* **Item 12 — done, in the cheap form.** `host.vorv.tools.Attempt` logs a
  survivable failure in one line naming what failed and why;
  `SilentFailureGuardTest` freezes the count of discarded `runCatching` sites
  at 153 and refuses a new one. A real detekt rule needs a rules jar in every
  build; this is the same guarantee for new code without that dependency.
* **Item 13 — done.** `tools/forge/verify-apk-payload.py` now also refuses an
  APK that (a) ships an `assets/source` mirror differing from the sources it
  was built from, or (b) is above 400 MB, and each APK is checked before
  upload. Verified locally: run against the released 0.9.21 APK with a
  newer working tree it refuses and names both drifting files. The manifest
  backup comment now matches what the rules actually do.
* **Item 14 — started.** `explainLlmFailure` (the sentences shown for 401,
  404, 402, 429, 5xx) moved out of the 1,980-line view model into
  `llm/LlmFailure.kt` with its first tests. The four-section split is still
  to do, one extraction per commit.
* **Item 15 — not possible as written.** The APK is arm64-only by necessity
  (proot, the guest JVM and the GenieX NPU libraries are arm64); GitHub's
  Linux runners are x86_64 and cannot run an arm64 image. A managed-device
  job would install an app with no native payload and prove nothing. What
  stands in its place: the payload gate on the real APK, the mirror gate, and
  the on-phone pass by the owner.
* **Item 6 — not done, and should not be done alone.** Moving debug builds to
  a `.ci` package makes the CI APK install *beside* the owner's app instead of
  over it — which is right — but the owner's update path today *is* the debug
  APK, so it also needs item 7. Item 7 (a release key) the owner has explicitly
  refused while debugging. The pair waits for that go-ahead.
* **Item 7 — declined by the owner for now.** Debug-phase instruction.
