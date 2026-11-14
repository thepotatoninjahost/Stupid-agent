# Vorv building an app on your phone

Two different jobs. Worth keeping straight, because they get mixed up:

- **Building Vorv itself** — turning Vorv's own source into the APK on your
  phone. You do this with a normal Android build, or Vorv's own updater. Not
  what this page is about.
- **Vorv building an app for you** — you ask for an app, Vorv writes it, and
  then turns that code into something you can install and open. *This* page.

## What has to be inside Vorv for the second job

Three tools: a Java compiler (turns code into Java bytecode), a dexer (turns
that into Android's format), and a resource packager (turns your icons,
layouts and manifest into the binary parts of an APK). All three are ordinary
Java programs. Your phone has no Java, so one has to travel inside Vorv.

## What is already true

- A Java runtime built for Android on ARM64 exists, and Vorv can carry it.
  It is pinned by sha256 in `tools/forge/runtime-manifest.json`;
  `tools/forge/fetch-runtime.sh` fetches and verifies it. Nothing is committed
  to git — the repo stays small and clonable.
- That runtime is **in the APK** as of this commit: I built
  `app-debug.apk` (227 MB) and inspected its contents. The launchers
  (`java`, `javac`, `jar`, `jarsigner`) are packaged as native libraries, the
  support files are in assets, and `extractNativeLibs=true` means Android
  unpacks the launchers somewhere they are allowed to run — which is the only
  non-root way to start a binary the app carries.
- `ForgeRuntime` unpacks the support files into Vorv's own storage on first
  use and can hand out a ready-made command line.

## What is not true yet, and will not be claimed until it is

- Nobody has started that runtime on your S25. The packaging is right; the
  first launch is the proof. `ForgeRuntime.probe()` exists exactly to report
  the JVM's version line from the phone — if that never runs, everything else
  is theory.
- The dexer (`d8`) and resource packager (`aapt2`) are not carried yet.
- So: Vorv cannot yet turn "an app" into an installable APK on the phone.

## The order I'm doing this in

1. Prove the runtime starts on your phone, and say the version out loud.
2. Add `d8` and `aapt2` to the payload and prove each runs.
3. Assemble a real APK on the phone from a tiny test app.
4. Hand that APK to `native_test`, which installs it, opens it and taps through
   it — that part is already built.

Until step 3 works, Vorv says "I wrote this, but I could not build it here" —
it does not hand you a broken app and call it finished.
