# Vorv

An autonomous coding agent that runs **on the phone**, in a Linux system of its own.

Vorv is a native Android app (Kotlin, Jetpack Compose). Inside it lives a real Debian 12
userland — its own filesystem, shell and package manager — executed under
[proot](https://proot-me.github.io/) with no root and no Termux, no second app and no
permission to grant. That system is where code is compiled, where apps are built, and where
the agent runs the tests it reports back to you. The payload (proot, its loader, the rootfs
and the on-phone JDK/Android toolchain) travels inside the APK, so a fresh install has a
working computer before the first prompt.

* **No root.** proot interposes on syscalls, so the guest sees a root filesystem that is
  really a folder in the app's private storage.
* **No Termux.** Nothing is delegated to another app.
* **No desktop required.** Everything — building, running, testing — happens on the device.

## What it does

* A cognitive loop that plans, calls tools and checks its own results (`kernel/`).
* Tools for the shell, files, the web, building Android apps, and driving the UI of the
  apps it builds (`tools/`).
* Builds APKs on the phone with the bundled `d8`, `apksigner` and Kotlin compiler, installs
  them through `PackageInstaller`, and opens them to verify they work.
* Self-repair: a crash is captured, diagnosed, patched as a signed dex, and hot-applied
  behind a consent gate (`healer/`, `watchdog/HotPatcher.kt`).

## Build

Requires JDK 17 and the Android SDK (compileSdk 37).

```bash
./gradlew :app:assembleDebug
```

The payload is downloaded on first build if it is missing (`provisionPayload`), so a fresh
clone builds a complete APK. Use `-PskipPayloadFetch` to make a missing payload fail loudly
instead. A finished APK can be checked with:

```bash
python3 tools/forge/verify-apk-payload.py app/build/outputs/apk/debug/app-debug.apk
```

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

## Documentation

| File | What is in it |
|------|----------------|
| `DEVELOPMENT.md` | How to build, run and test the project |
| `CONTRIBUTING.md` | Conventions for changes |
| `WORKLOG.md` | What each build changed, with evidence |
| `REVIEW.md`, `CODE-REVIEW.md`, `ACCEPTANCE_LOG.md` | Review findings and how each was verified |
| `SECURITY.md` | How to report a vulnerability |
| `NOTICES.md` | Third-party components and licences |

## License

The application source is MIT — see `LICENSE`. Bundled components keep their own
licences: `NOTICES.md` lists them, including the GPL-2.0 obligations that come
with shipping proot.
