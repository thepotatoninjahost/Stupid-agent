# Developing Vorv

Vorv is an Android app (`host.vorv`) with a Debian 12 userland and an on-phone
JDK/Android toolchain carried inside the APK. This file says how to build it,
what the build downloads, and where the pieces live. `AGENTS.md` holds the
working rules learned from previous builds; read it before changing behaviour.

## What you need

| Tool | Version | Why |
|------|---------|-----|
| JDK | 17 | `compileOptions` targets Java 17; CI uses Temurin 17 |
| Android SDK | `platforms;android-37`, `build-tools;37.0.0` | `compileSdk = 37`; the payload copies `android.jar`, `d8.jar` and `apksigner.jar` out of it |
| Python | 3.11 or newer, as `python3` | the `tools/forge` scripts and the Chaquopy sandbox self-test |
| Gradle | 9.8.2 via `./gradlew` | pinned in `gradle/wrapper/gradle-wrapper.properties` together with its SHA-256 |

The wrapper currently points at a mirror (`mirrors.cloud.tencent.com`) because
the canonical `services.gradle.org` URL redirected to a CDN that was returning
504 for Gradle assets on 2026-11-08. The mirror serves a byte-identical archive
and the wrapper enforces `distributionSha256Sum`, so a tampered mirror cannot
build. Move the URL back when the official CDN is healthy; keep the hash pin.

`ANDROID_HOME` (or `ANDROID_SDK_ROOT`, or `sdk.dir` in `local.properties`) must
point at that SDK. `tools/forge/ensure-sdk.sh` installs the two packages above
with `sdkmanager` if the SDK is missing them, so a fresh runner behaves the same
as a fresh clone.

## First build

```bash
./gradlew :app:assembleDebug
```

On a fresh clone `preBuild` depends on `checkPayload`, which depends on
`provisionPayload`. That task runs, in order:

1. `tools/forge/ensure-sdk.sh` — the two SDK packages above.
2. `tools/forge/fetch-runtime.sh` — OpenJDK 17 modules and the Termux d8 /
   apksigner / android.jar bundle (~246 MB) into `app/src/main/assets/forge/`.
3. `tools/forge/fetch-linux.sh` — proot, its loader, libtalloc and the Debian 12
   rootfs into `app/src/main/jniLibs/arm64-v8a/` and
   `app/src/main/assets/forge/linux/`.

Downloads land in `FORGE_CACHE` (default `/tmp/forge-runtime`) and every file is
checked against the sha256 in `tools/forge/runtime-manifest.json` before it is
unpacked. A mismatch fails the build; nothing is unpacked on trust.

Both payload directories are git-ignored — the payload is ~250 MB and the
repository must stay clonable. `tools/forge/runtime-manifest.json` **is**
committed: the app reads it to decide what to download.

Options:

- `-PskipPayloadFetch` makes a missing payload a hard failure instead of a
  download. Use it when the network must not be touched.
- `./gradlew :app:checkPayload` runs the completeness check alone.
- `python3 tools/forge/verify-apk-payload.py <apk>` inspects the finished APK and
  lists every payload file the in-app computer needs. CI runs it before anything
  is uploaded, because a payload-free APK fails only on the phone.

`FORGE_CACHE` survives between builds; keep it, and repeat builds do not
re-download. CI caches the two payload directories keyed on the manifest.

## Build variants and signing

- **debug** is signed with `app/vorv-debug.keystore`, a committed debug key. The
  reason is in `app/build.gradle.kts`: a sideload-only app must install over the
  previous build, and a machine-local debug key silently breaks that when the
  build machine changes. The consequence worth knowing: that key is public, so
  any build signed with it carries the same signature as yours.
- **release** is minified (R8) and only signed when `RELEASE_KEYSTORE_PATH`,
  `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD`
  are set. Without them `assembleRelease` still runs and leaves
  `app-release-unsigned.apk`.

R8 is where release-only failures live: run `minifyReleaseWithR8` (or
`assembleRelease`) before shipping, because a debug build cannot catch a missing
class that only shrinking reaches.

## Tests

```bash
./gradlew :app:testDebugUnitTest                  # JUnit + Robolectric suites
python3 app/src/main/python/test_vorv_sandbox.py  # the Chaquopy sandbox
```

Robolectric is pinned to 4.17 in `gradle/libs.versions.toml`, and 4.18 is
explicitly ignored in `.github/dependabot.yml`: 4.18 rewrites sandbox class
loading, and the bump run died with 44 of its tests failing on
`ClassNotFoundException` in `SandboxClassLoader` before reaching an assertion.
Unpin only when the suite is clean on the newer version.

A green unit suite is not evidence that the app works on a phone. The payload
paths, proot, the guest JDK and the accessibility tooling are exercised only on
a device; `WORKLOG.md` records what was checked where.

## Where the code lives

| Path | What is in it |
|------|----------------|
| `kernel/` | the cognitive loop: planning, tool calls, result checks |
| `tools/` | shell, files, web, native test, `NetworkGuard`, `ShellQuote` |
| `linux/` | provisioning and running the guest system under proot |
| `nativeapp/`, `web/` | building, installing and driving the apps Vorv writes |
| `healer/`, `watchdog/` | crash capture, patch signing, hot patching |
| `llm/`, `data/` | model providers, model manager, stores |
| `ui/` | Compose screens |
| `app/src/main/assets/source/` | a mirror of `app/src/main/java`, kept in step by the `syncSourceMirror` task |
| `tools/forge/` | payload fetch, verification and packaging scripts |

`AgentViewModel` is the largest file and holds several concerns (chat, consent,
task control, persistence). Extract along those seams when you touch it.

## CI

`.github/workflows/build.yml` runs on every push to `main`, `feature/**`,
`fix/**` and `dependabot/**`, and on pull requests to `main`:

1. **test** — the Python sandbox self-test, then `./gradlew test`.
2. **build** — `assembleDebug assembleRelease`, the APK payload gate, artifact
   upload (push only), and the rolling `ci-latest` prerelease (main only).
3. **publish** — a signed release, only when the `RELEASE_KEYSTORE_*` secrets
   exist.

There is deliberately no `gradle/actions/setup-gradle`: this repository's
Actions cache service returned errors for weeks, and from gradle/actions v6 the
caching component is proprietary under Gradle's Terms of Use. The forge payload
is cached with `actions/cache` instead, keyed on the manifest. The reasoning is
in a comment in the workflow itself.

A workflow file needs a `workflow`-scoped credential to push. Without that scope
GitHub rejects the push for `.github/workflows/build.yml` only, after accepting
every other file in the same push.
