# Developing Vorv

## Environment requirements

| Tool | Version | Why |
|---|---|---|
| JDK | 17 | AGP 8.7 requires it |
| Android SDK | platform 35, build-tools 34+ | compileSdk 35 |
| Python | 3.11 | Chaquopy embeds 3.11; CI pins the same |
| Gradle | 8.9 | Via wrapper or `gradle/actions/setup-gradle` |
| Device | Galaxy S25 (or arm64 device with Hexagon NPU) | The GenieX AAR ships only `arm64-v8a` Hexagon libs; x86_64 emulators cannot run the local engine |

No Android Studio project files are committed. Open the repo root in Android
Studio and let it import the Gradle project.

## Building

```bash
cd Stupid-agent
./gradlew assembleDebug          # debug APK → app/build/outputs/apk/debug/
./gradlew assembleRelease        # minified release; signed only when env vars set
```

## Testing

```bash
./gradlew test                                       # JUnit + Robolectric unit tests
python3 app/src/main/python/test_vorv_sandbox.py     # Python sandbox self-test (fast, no deps)
```

CI runs both on every push to `main`, `feature/**`, `fix/**`. The Python
self-test is the fastest feedback loop for sandbox changes — run it before
committing anything that touches `vorv_runner.py`.

### What the suites cover

- `ToolCallParserTest` — local-LLM output parsing, including truncated-JSON repair.
- `CognitiveKernelTest` — the full ReAct loop with a scripted fake LLM:
  validation, rate limits, timeouts, citation enforcement, full-output
  persistence, the consent bridge.
- `JsExecToolTest` — Rhino sandbox attack tests (`Packages`, `Runtime`,
  `importClass`, …) plus legit-code and timeout cases.
- `test_vorv_sandbox.py` — Python sandbox attack tests (file/network/process/
  escapes/whitelist/timeouts).
- `WebFetchToolTest` — MockWebServer: HTML stripping, size caps, content-type
  refusal, scheme refusal.
- `NetworkGuardTest` — private/loopback/LAN/metadata address blocking.
- `DialGuardTest`, `ScheduleValidatorTest`, `ToolCallValidatorTest`,
  `CitationStripperTest`, `MigrationTest` — the extracted pure components.

## Release signing (CI)

The release build is signed only when these environment variables exist:

```
RELEASE_KEYSTORE_PATH      # path to the decoded keystore file
RELEASE_KEYSTORE_PASSWORD
RELEASE_KEY_ALIAS
RELEASE_KEY_PASSWORD
```

In GitHub: Settings → Secrets and variables → Actions → add

```
RELEASE_KEYSTORE_BASE64    # base64 of your release .keystore (never commit it!)
RELEASE_KEYSTORE_PASSWORD
RELEASE_KEY_ALIAS
RELEASE_KEY_PASSWORD
```

Generate a keystore locally:

```bash
keytool -genkeypair -v -keystore vorv-release.keystore -alias vorvrelease \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 vorv-release.keystore   # paste into RELEASE_KEYSTORE_BASE64
```

Without the secrets, CI still builds and tests everything; it just skips the
GitHub Release (with a visible warning) instead of publishing an unsigned or
debuggable build.

## Adding a tool

1. Create `app/src/main/java/host/vorv/tools/MyTool.kt` implementing the
   `Tool` interface:
   - `name`, `description` (honest — the LLM reads this),
   - `schema()` (JSON schema; the kernel validates arguments against it),
   - `run(input, ctx)` — return REAL output or an honest error,
   - override `timeoutMs` and `maxCallsPerTask` to sensible budgets.
2. Register it in `AgentViewModel.tools`.
3. Add the row to `ToolsScreen`'s list (keep the copy honest).
4. Write tests: happy path, missing-arg path, and at least one hostile-input
   path. Sandbox-capable tools: add attack cases to the relevant suite.
5. Update `NOTICES.md` if you added a dependency, and `SECURITY.md` if you
   changed the security posture.

## Known build quirks

- `org.gradle.parallel=false` is deliberate: Chaquopy + KSP have a state-lock
  conflict under parallel execution. Track the upstream Chaquopy issue and
  re-enable when fixed.
- First build downloads the 89 MB GenieX AAR and the Chaquopy Python stdlib;
  subsequent builds are cached.
- Room has `exportSchema = false` BY DECISION: the schema is pinned by
  `MigrationTest`, which builds the v1 database from the actual DDL and
  asserts every migration lands exactly on it. No `app/schemas/` directory
  exists and none is committed — if you flip exportSchema on, decide where
  the files live BEFORE committing them (audit L-2: the old text instructed
  committing schemas that the build never produced).

## CI pipeline

`.github/workflows/build.yml`:

1. **test** — `./gradlew test` + Python sandbox self-test. SHA-pinned actions.
2. **build** — assembleDebug + assembleRelease (signed when secrets exist).
3. **publish** (main only, keystore required) — GitHub Release with the signed
   APK. Feature branches build and test only, so you can verify CI is green
   before merging — same flow the project has always used.

Dependabot watches Gradle deps and GitHub Actions weekly
(`.github/dependabot.yml`).
