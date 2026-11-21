# The Linux system inside Vorv

Vorv carries a complete Linux userland. It is not a simulator and it is not a
list of pre-approved tools: it is Alpine Linux, running on the phone, with its
own root filesystem, its own shell and its own package manager (`apk`). The
agent reaches it through one tool, `linux_run`, which executes a shell command
inside that system and returns what it printed.

## How it runs without root

Android will not let an unprivileged app call `chroot`. `proot` solves this by
intercepting syscalls with `ptrace`: to every program inside, the guest tree
*is* `/`, while on disk it is a folder in Vorv's private storage. No root, no
unlocked bootloader, no Termux.

Two things have to be true for Android to allow this at all:

1. **The binaries must be executable.** Android only permits executing files the
   installer unpacked into `nativeLibraryDir` (`extractNativeLibs=true`). So
   `proot`, its loader and its helper libraries ship as `libforge-proot.so`,
   `libforge-proot-loader.so` and `libtalloc.so.2` — real names in the APK,
   renamed only so the platform treats them as native libraries.
2. **`proot` must find its loader.** The Termux build of `proot` has a
   compiled-in loader path of `/data/data/com.termux/...`. `PROOT_LOADER` points
   it at `nativeLibraryDir/libforge-proot-loader.so` — the copy the installer
   unpacked. It must **not** be a copy inside app storage: since Android 10 the
   kernel refuses to execute a file the app itself wrote there, for any
   `targetSdk >= 29`, which is exactly how a working payload produced "no
   Linux". `fetch-linux.sh` checks that the binary honours `PROOT_LOADER`
   before anything is packaged.

The root filesystem itself is an asset (`assets/forge/linux/rootfs.tar`, 9 MB,
518 entries) unpacked once into `filesDir/linux/rootfs` on first use. Every entry is
checked to resolve inside that folder, symlinks are recreated as symlinks, and
executable bits are preserved — a rootfs that loses those looks present and
does nothing.

## What the agent gets

`linux_run(command)` — a POSIX shell in the guest, with:

* `/dev`, `/proc`, `/sys` bound from the phone (read-only use),
* `~` bound to `filesDir/linux/home`, so work survives between calls,
* `/tmp` bound to `filesDir/linux/tmp`,
* an exit code, the output, and a timeout (default 180 s, hard cap 600 s).

Whatever the distribution can install, the agent can install: `apk add gcc
musl-dev`, `apk add python3 py3-pip`, `apk add openjdk17`, `apk add git`. That
is the difference between "the app has some tools" and "the app has a computer".

### Network: one guarded way out

The guest's network goes through an egress proxy that Vorv starts on loopback and
hands to every guest command (`http_proxy`, `https_proxy`, `HTTP_PROXY`,
`HTTPS_PROXY`, `ALL_PROXY`). The proxy resolves a host once through
`NetworkGuard`, refuses private, loopback, link-local and cloud-metadata
addresses with a 403 that names the reason, and connects to exactly the address
it vetted (so a second DNS answer cannot redirect the connection). Its
`/etc/hosts` maps `metadata.google.internal`, `metadata.goog` and `instance-data`
to `0.0.0.0`, because `proot` shares the phone's network stack and loopback
could otherwise reach phone services.

Covered: proxy-aware tools — `apt`, `git`, `pip`, `curl`, `wget`. Not covered:
raw sockets, UDP, and anything that ignores proxy variables. This is an HTTP
proxy, not a kernel firewall; state that limit when you report what the guest
can reach.

## What was verified, and how

* The Alpine 3.20.0 aarch64 root filesystem was downloaded from
  `dl-cdn.alpinelinux.org` and pinned by sha256 in
  `tools/forge/runtime-manifest.json`; the same checksum is enforced by
  `tools/forge/fetch-linux.sh`, which refuses a payload that does not match.
* The rootfs is a working Alpine tree: its musl loader runs its busybox on a
  Linux kernel outside the app (checked on this machine).
* Unpacking is covered by `RootfsTarTest` (3 tests): files and exec bits
  survive, symlinks are recreated, and a tar entry that would escape the
  destination throws instead of writing.
* `:app:compileDebugKotlin` and `:app:testDebugUnitTest` pass with the system
  wired in.

## What is not verified

`proot` itself was **not** executed on the phone here, and it could not be
executed on this build machine either: this sandbox's seccomp filter kills
`proot` with `SIGSYS` before it can interpose. So the two things that must be
proven on the S25 are:

1. `proot` starts and prints a version (it needs `ptrace`, which Android allows
   for apps — this is how Termux's proot works — but "should" is not "does").
2. `apk add gcc && gcc hello.c -o hello && ./hello` runs inside the guest.

Until those two are seen on the device, this is a complete, tested
implementation of the design, not a proven working computer.

## Rebuilding the payload

The payload is ~4 MB of rootfs and ~330 KB of binaries, plus (separately, for
`build_app`) the Java toolchain. None of it is committed:

```bash
export ANDROID_HOME=/path/to/android-sdk   # needs platforms/android-37
./tools/forge/fetch-linux.sh               # proot + loader + libtalloc + rootfs
./tools/forge/fetch-runtime.sh             # OpenJDK + android.jar + d8 + apksigner
./gradlew :app:assembleDebug
```

## Status: Termux is gone (2026-11-13)

Nothing in the app calls Termux any more. `shell` runs in this Linux system,
`self_fix` compiles with the toolchain in the APK, `TermuxProbe` and the
`com.termux.permission.RUN_COMMAND` permission are deleted, and the Setup
card asks `LinuxSystem.isProvisioned()` instead of asking the user to install
another app. See REVIEW.md section 10 for the full change table.

What is still unproven is proot itself on a phone: argv and contract are
pinned by `LinuxSystemArgvTest` (5) and `ShellToolTest` (12), but the guest
must be started on the S25 before this page can claim a working OS.
