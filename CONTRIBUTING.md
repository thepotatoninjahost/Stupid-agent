# Contributing to Vorv

## Requirements

JDK 17, Android SDK 35, Python 3.11, and ideally a Galaxy S25 (the local
engine needs the Hexagon NPU). See [DEVELOPMENT.md](DEVELOPMENT.md).

## Ground rules

1. **Honesty over marketing.** Commit messages, tool descriptions, README and
   in-app copy must describe what the code actually does. The v0.5.x README
   claimed shipped tools were "pending" — that class of lie is how dangerous
   software ships. Don't reintroduce it.
2. **Tools never fake output.** If a tool cannot produce a real result, it
   returns `ok = false` with an actionable error. The system prompt's rule #1
   applies to us too.
3. **Tests for new logic.** Pure logic goes in testable objects (see
   `ToolCallParser`, `DialGuard`, `ScheduleValidator`, `NetworkGuard` for the
   pattern). Sandbox-touching changes must extend the sandbox self-tests.
4. **Sensitive actions need consent.** Anything that leaves the app, dials,
   shares, launches, or spends the user's money goes through the confirmation
   bridge (`ctx.confirm`) — fail closed when it's absent.
<!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:e0ba67db97a864fc6281851ac12d6eb41194de2793c2db67367038c46081b604 -->

5. **Secrets and signing private keys never get committed.** This includes debug keys. Development builds use a machine-local debug key and a separate application ID. Release signing keys must be private, stored outside the repository and supplied through the documented environment variables or GitHub Actions secrets.


<!-- /coderabbit-ai-generated:v1 -->
## Workflow

- Branch from `main` using `feature/**` or `fix/**` — CI builds and tests
  every push to those branches, so you can verify green before merging.
  PRs targeting `main` get the same test + compile gate (I-2, code review
  2026-11) — including PRs from forks, which previously got no CI at all.
- Commit style: imperative subject line, honest body. Look at `git log` for
  the house voice ("Fix three real bugs caught by the user in v0.5.0").
- PRs: describe what changed and why; link the audit finding ID
  (`C-1`, `H-4`, …) when you're fixing one.
- Run before pushing:
  ```bash
  ./gradlew test
  python3 app/src/main/python/test_vorv_sandbox.py
  ```

## Reporting security issues

See [SECURITY.md](SECURITY.md).
