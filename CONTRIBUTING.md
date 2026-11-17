# Contributing to Vorv

Vorv is maintained by one owner and reviewed in public. Small, evidenced
changes land; large, unexplained ones do not. `AGENTS.md` is the longer version
of this file — read it before your first change.

## The four rules

1. **Read the code path before theorising.** When something is broken, read the
   path top to bottom, in order. Most bugs found here (a stamp checked before it
   was written, a payload folder excluded by `.gitignore`) were obvious to one
   ordered read and invisible to every test.
2. **State the claim your evidence covers.** A unit test proves the unit. It
   does not prove the phone. Say which claim a check exercises, and run the
   check after your last edit, not before.
3. **Prove a new test fails first.** For a bug fix, add the test, watch it fail
   on the old code, then fix. A test that never failed has not been shown to
   test anything.
4. **Report what came back, failures included.** "Fixed" without a check run
   after the last change is not a report. If CI or a device is the only place a
   claim can be checked, say so.

## What a change should look like

- One concern per commit. The subject line says what changed, the body says why
  and what was run.
- Comments explain the non-obvious: an invariant, a workaround, a trade-off that
  was measured. They do not narrate the code.
- No new dependency without a reason in the commit body. Pins live in
  `gradle/libs.versions.toml`, never inline in a build file.
- Anything the app reads at run time to *decide* what to download is committed.
  Only payload binaries are git-ignored (`tools/forge/runtime-manifest.json`
  stays in git; `assets/forge/` and `jniLibs/arm64-v8a/` do not).
- Security-relevant code stays boring: `NetworkGuard` before every outbound
  request, `ShellQuote` before every guest command, `SafPaths` before every
  file write, and a consent gate before anything that changes the user's device.

## Checks before you ask for a review

```bash
./gradlew :app:testDebugUnitTest
python3 app/src/main/python/test_vorv_sandbox.py
./gradlew :app:assembleRelease      # R8 finds classes debug builds never touch
python3 tools/forge/verify-apk-payload.py app/build/outputs/apk/debug/app-debug.apk
```

If any of these cannot run in your environment, say which and why in the pull
request. That sentence is more useful than a green checkmark that skipped it.

## Pull requests

- Branch from `main`; keep the branch short-lived.
- Say what the change does, how it was checked, and what was not checked.
- Do not reformat files you did not otherwise touch. Review diffs here are read
  line by line, and unrelated churn hides the real change.
- Dependency bumps go through Dependabot. A bump that breaks the suite gets an
  `ignore` entry with the failure quoted — Robolectric 4.18 is the current
  example — not a silent revert.

## Licensing

The application source is MIT (`LICENSE`). Bundled components and their licences
are listed in `NOTICES.md`; a new bundled binary or library needs an entry there
in the same commit.
