# Security policy

Vorv runs an autonomous agent with a shell, a package manager and the ability to
install apps. That is the product, not a bug: an install is only as safe as the
instructions it is given. The reports that matter here are the ones where
something happens **without** the user's instruction, or where an instruction
reaches further than the user can see.

## Reporting a vulnerability

Use GitHub's private reporting on this repository: **Security → Advisories →
Report a vulnerability**. That opens a draft advisory visible only to the owner.

Do not open a public issue for a vulnerability, and do not include a working
exploit in a pull request. If GitHub's advisory form is unavailable to you, open
an issue that says only "I have a security report, please open a private channel"
and nothing else.

Useful in a report: the app version (`Settings → About`), the Android version and
device, what you did, what happened, and whether it needs a hostile app, a
hostile web page, a hostile model response or only a local file to trigger.

## What is in scope

| Area | Examples of what counts |
|------|-------------------------|
| Guest isolation | escaping the proot root, writing outside the app's private storage, reaching the host filesystem or another app's data |
| Payload integrity | getting the app to unpack or execute a file that does not match `tools/forge/runtime-manifest.json`, or making the fetch follow a redirect to an attacker's host or a `file://` URL |
| Command construction | a guest command where an argument can break out of its quoting (`ShellQuote`), or a shell string built from model output that was never quoted |
| Network policy | an outbound request that bypasses `NetworkGuard` (blocked hosts and suffixes, DNS resolution checks), or SSRF through a tool |
| Model output | a tool call that executes without validation (`ToolCallValidator`), or a consent gate that can be skipped by prompt injection |
| Hot patching | a dex patch that is applied without a valid signature from the app's own key, or a patch that survives a rejected consent |
| Device control | accessibility or UI-automation actions taken without the user's consent, or installs that do not report a verdict back |
| Exported components | an `Activity`, `Service`, `Receiver` or provider reachable by another app that should not be |
| Secrets | an API key or keystore password that reaches logs, a crash report, a backup or the model provider |

## Known and accepted, not vulnerabilities

These are deliberate and are documented so reports can skip them:

- **The app is a remote execution surface by design.** If you ask it to run
  something, it runs it. A model that can be persuaded to run a harmful command
  is a model-quality problem unless a guard (consent, validation, quoting) fails.
- **The debug keystore is public.** `app/vorv-debug.keystore` is committed on
  purpose so sideloaded builds update over each other. It follows that any APK
  signed with it installs over a build signed with it. Install APKs only from
  this repository's releases, and prefer a release built with a private key.
- **The guest has no root and no sandbox of its own.** proot is a userspace
  interposer: it gives a root-looking filesystem, not kernel isolation. Code
  running in the guest can do anything the app's own process can, which is why
  the app's process is the measurement point.
- **`ci-latest` builds are debug builds.** They are for testing the agent, not
  for trust: see `DEVELOPMENT.md` on signing.

## What happens after a report

You get an acknowledgement, a judgement on severity and reach, and a fix or a
reasoned refusal. Anything that lands gets a test that failed before the fix and
a line in `WORKLOG.md`. This is a single-maintainer project with no bounty
programme, so there is no payment to promise — only credit in the advisory if
you want it.

## Supported versions

The newest release, and `ci-latest` from `main`. Older APKs are not patched; the
fix for a finding is always in the next build.
