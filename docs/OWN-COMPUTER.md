# Vorv's own computer — removing Termux

Owner constraints that drive every decision here:

- The only device is a **Samsung Galaxy S25** (Android, non-rooted). No PC.
- **Termux is removed.** The app must not require, probe or instruct the user
  to install it, and no path may depend on `bash`, a shell `PATH`, or SAF
  folder names being safe to interpolate into a command line.
- The app gets **its own computer**: its own toolchain, its own file tree, its
  own process boundaries — everything an agent needs to write, build and run
  code happens inside Vorv.
- **The phone is never rooted.** No `su`, no system-partition writes, no
  exploit, no "just adb root once". Everything here works with the permissions
  a normal sideloaded app has.
- Self-modification and code-building must keep working, on the phone, with no
  external package manager.

## 1. What the "computer" is

`Forge` is a private compute root under the app's own data directory:

```
filesDir/forge/
  toolchain/            extracted, sha256-verified compiler payload
    jre/                (mode A only) private aarch64 runtime
    lib/kotlin-compiler-embeddable-2.4.21.jar
    lib/d8.jar
    lib/kotlin-stdlib-2.4.21.jar
    lib/kotlinx-coroutines-core-jvm-1.11.0.jar
    lib/annotations-26.0.2.jar
  work/<runId>/         per-build working dir (source, classes, dex)
  out/                  verified .dex artifacts
  log/                  compiler stdout/stderr, capped
```

It is *not* SAF. Nothing under `forge/` is user-visible, shareable or
writable by another app, so the whole class of bugs that came from
interpolating a user-chosen folder name into a shell command disappears by
construction. `ShellQuote` stays, but nothing new may go through a shell.

## 2. What a non-rooted phone can actually run (corrected)

An earlier revision of this document claimed the app could bundle a desktop
aarch64 JRE and execute it. **That is wrong, and it is corrected here.** A
non-rooted Android app cannot:

- `execve` a binary it downloaded into its own data directory
  (`targetSdk >= 29` blocks exec from writable app storage), and
- run an ordinary Linux aarch64 binary at all — Android uses bionic, not
  glibc, so a JRE built for a desktop distro has no loader to start it.

Termux works because Termux is *its own app* with its own `libexec`, its own
bionic-linked packages and its own `RUN_COMMAND` bridge. There is no way to
borrow that from inside another app without the second app, and there is no
way to get it by rooting — the phone is never rooted.

So the app's computer is not a Linux box. It is what an unrooted app can
genuinely own, and it is enough to write and run real programs:

| Engine | Runs | Status |
|---|---|---|
| JS (`JsEngine`) | Rhino interpreter compiled into the APK | **implemented, tested** |
| Python (`PythonEngine`) | CPython 3.11 via Chaquopy, compiled into the APK (bionic `.so`, no Termux) | implemented, wired to `python_exec` |
| Kotlin patch (`ForgeCompiler`) | needs a JVM; only possible with a bionic-linked runtime payload, which does not exist yet | unproven — do not advertise |

`host.vorv.computer` is the single place that decides what can run: engines
that are not present are reported as refused with a reason, never silently
attempted.

### What the JS engine can do

Real programs: computation, string/JSON handling, algorithms, scripted
transformations. It cannot touch the filesystem, the network or Java classes —
that is the point. Its purpose is *writing and running code*, on the device,
with no Termux, which the tests below demonstrate with an actual program.

### The Kotlin-compile question, stated honestly

Self-repair that recompiles Kotlin on the phone needs a JVM that Android can
execute. `ForgeCompiler` is written and proven to work *wherever a JVM
exists* (verified on a desktop JVM against the app's own 531 classes). On the
phone it will refuse with "toolchain incomplete" until a bionic-linked JVM
payload is built and verified. Making the model write Kotlin it cannot compile
would be the same lie in a new costume.

## 3. What was proven here (2026-11-13)

With `kotlin-compiler-embeddable 2.4.21` and `d8.jar` from build-tools
37.0.0, on a plain JVM and with no shell of any kind:

```
kotlinc-embeddable -classpath app_classes.jar:android.jar -d patchout Probe.kt
  -> patchout/host/vorv/tools/PatchProbe.class
d8 --release --min-api 31 --lib android.jar --output dexout Probe.class
  -> dexout/classes.dex (1280 bytes)
```

Two consequences:

1. `kotlinc` + `d8` are ordinary JVM libraries. Termux was only ever a host.
2. The compiler needs `kotlin-stdlib`, `kotlinx-coroutines-core-jvm` and
   `org.jetbrains.annotations` on **its own** classpath in addition to the
   target classpath. Missing any of them fails at codegen, not at startup —
   the payload manifest below pins all four so this cannot regress silently.

## 4. What the app must stop doing

| Today | After |
|---|---|
| `TermuxProbe.termuxInstalled` gate on `self_fix` | `ForgeProbe.readiness` (toolchain present + verified) |
| `RUN_COMMAND` permission request | gone |
| `bash` script building `kotlinc …` with SAF paths | `ForgeCompiler` argv array, private dirs |
| Compile output read back through SAF polling files | `Process` streams, read directly |
| `d8`/`dx` lookup in Termux PATH | pinned `d8.jar`, executed by the private runtime |
| 600 s budget for cold Termux start | per-phase budgets, measured, no cold start |
| Shell tool requires Termux | runs inside Forge (same argv discipline) |

`TermuxProbe` is deleted, not deprecated. Keeping it as a fallback keeps the
permission, the second app and the injection surface alive.

## 5. Packaging and verification rules

- Every payload file is listed in `assets/forge/toolchain.manifest` with
  `sha256` and size. `Toolchain.verify` refuses to use the toolchain if any
  file is missing or does not match; nothing is ever executed before it
  verifies.
- The payload is extracted once per version (stamp file), never re-downloaded
  at runtime, never fetched over the network at run time.
- The compiler runs with `-no-stdlib -no-reflect -no-daemon` and a per-run
  working directory it owns; it is killed by the existing watchdog on timeout.
- Builds run in `:forge`, a separate process, so a compiler OOM kills the
  build, not the agent's memory.

## 6. Order of work

1. `Forge` core: paths, manifest verification, argv builders, exec wrapper,
   readiness probe, unit tests. **(this change)**
2. Build wiring: fetch the pinned payload with sha256 into `assets/forge/`,
   assert the manifest at build time, fail the build if it drifts.
3. `HealingAgent`: replace the bash/Termux compile step with
   `ForgeCompiler`, keep the 72 h probation and the signature gate unchanged.
4. `ShellTool`: execute through Forge instead of Termux (same argv rules).
5. Delete `TermuxProbe`, the `RUN_COMMAND` permission, the Termux onboarding
   copy in Settings, and every "install Termux" string in the UI.
6. On-device check on the S25: compile a trivial patch, load it, roll it back.
