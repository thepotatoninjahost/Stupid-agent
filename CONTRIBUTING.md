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
5. **Secrets never get committed.** Keystores, API keys, tokens — no
   exceptions, no "it's just a debug key" force-adds.

## Workflow

- Branch from `main` using `feature/**` or `fix/**` — CI builds and tests
  every push to those branches, so you can verify green before merging.
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
