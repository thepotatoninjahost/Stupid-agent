# Developing Vorv

<!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:95dc55b75407e36ad00425c11061fd87f5cc94edb0e77db3bfd84d92940ca677 -->

## Environment requirements

| Tool | Version |
|---|---|
| JDK | 17 |
| Android platform | 37.0 (compile SDK 37; target SDK remains 35) |
| Android build-tools | 37.0.0 |
| Python | 3.11 |
| Gradle | 9.8.2, supplied by the checksum-pinned wrapper |
| Build plugins | AGP 9.5.1, Kotlin 2.4.21, KSP 2.3.13, Chaquopy 17.2.0 |
| Device | ARM64 Android; Galaxy S25 required for Hexagon acceptance |

Open the repository root in Android Studio. Set `JAVA_HOME` and `ANDROID_HOME`, and put Python 3.11 on PATH. The dependency catalog is the authoritative version list. x86_64 emulators cannot run the bundled ARM64 native engine.



<!-- /coderabbit-ai-generated:v1 -->## Building

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

<!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:391f7dea6cf49455a67ca1e7d9f66a9cf149ecaa69ebdcbba3ecdd4c08678f49 -->

Without the secrets, CI builds and tests the app and publishes testing APKs. Main runs also publish the rolling `ci-latest` prerelease and a permanent commit-specific prerelease. These debug APKs are debuggable test builds, not private-key-signed production releases. PRs and branch runs provide APK downloads through their workflow Artifacts section. See README.md for installation instructions and SECURITY.md for the legacy debug-key caveat.



<!-- /coderabbit-ai-generated:v1 -->## Adding a tool

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

<!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:603be46fb352c4dde9ba22a6c84f9b5ed2f7ba592030e99b5d7dfaebcbcba68f -->

## Dependency upgrades

The coordinated Gradle/AGP/Kotlin/AndroidX migration is already present in the catalog. Do not merge revert PRs #32 or #33 as a build workaround: they downgrade jsoup and Rhino. Diagnose failing checks while retaining current versions.

After a dependency upgrade, run `./gradlew test assembleDebug assembleRelease lint` and the Python sandbox self-test. Test both build variants and inspect the healer classpath asset. S25 offline inference, Termux dispatch, DEX loading, background work, and install/update behavior require device validation.


<!-- /coderabbit-ai-generated:v1 -->