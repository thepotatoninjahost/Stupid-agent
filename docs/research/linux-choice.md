# Which Linux should Vorv's computer be?

Written 2026-11-16, in answer to the owner's question: *where is the research on the
best Linux for this phone?* Everything below was checked during the session named
above; anything estimated is labelled an estimate.

The job this computer exists for is narrow and decides everything: **build Android
APKs from source.** Not a desktop, not a general dev box.

## 1. The finding that rules out Alpine

Android's resource packer, `aapt2`, is a **native** program, not a Java one. Google's
SDK manifest (`https://dl.google.com/android/repository/repository2-3.xml`, read
2026-11-16) lists `build-tools` archives for `linux/x64`, `macosx/x64`, `windows/x64`.
The aarch64 entries in that manifest are only: `ndk`, `ndk-bundle`, `cmdline-tools`,
`cmake`, `emulator`, `skiaparser`, `extras`, and a preview build daemon
`build;lightbuild;0.0.30` (`lume-daemon-linux_aarch64`).

So an arm64 phone cannot obtain an official arm64 `aapt2`. And Alpine is **musl**, so
even a glibc arm64 binary would not run there. Alpine fails on two separate counts for
this job. It was chosen without asking the owner what the computer was for, and it was
the wrong choice for the job the owner actually has.

## 2. Debian arm64 ships the Android toolchain natively

Checked package by package on `packages.debian.org/bookworm/arm64` (2026-11-16):

| Package | Version | Arch | Role |
|---|---|---|---|
| `aapt` | 10.0.0+r36-10 | **arm64** | resource/APK packing, native |
| `zipalign` | 10.0.0+r36-1 | **arm64** | APK alignment, native |
| `android-sdk-build-tools` | 29.0.3+9 | **arm64** | the build-tools set |
| `apksigner` | 31.0.2-1 | all | signing (Java) |
| `openjdk-17-jdk-headless` | 17.0.21+9-1~deb12u1 | **arm64** | javac + java |

The JDK version is `17.0.21+9`, the same version already in the payload. Note the
distinction that matters: the Termux JDK currently shipped is **bionic**-linked because
it was built for Android; on Debian the glibc JDK from the distro is the correct one.

## 3. What is *not* available, and the consequence

- No arm64 `aapt2` from Google (see §1).
- Debian bookworm has no `aapt2` package (queried; not found).
- Gradle's Android plugin downloads `aapt2` per host platform from Google Maven, so a
  **Gradle/AGP build on this phone will fail at resource packing.**

Vorv's built-app path does not use Gradle: `javac` → `d8` → inject `classes.dex` into a
pre-built resource-free template APK → `apksigner`. That pipeline never invokes
`aapt2`. Gradle-based projects are therefore out of scope, and that limitation is a
fact of Google's packaging, not of Vorv's design.

## 4. The alternative considered and rejected

Running Google's x86-64 build-tools under emulation (box64, qemu-x86_64) inside the
guest. It can work, but it adds a second binary layer, loses most of the speed, and
buys nothing that Debian's native packages do not already provide.

Ubuntu 24.04 arm64 would work equally well in principle (same glibc family), but its
package list was not verified here, so Debian is chosen on evidence rather than
familiarity.

## 5. Prior art: full Android builds on a phone are real

**AndroidIDE** (`github.com/AndroidIDEOfficial/AndroidIDE`, 3,387 stars) was an
on-device IDE that built full Android applications on the phone, with an SDK manager
reachable from its terminal. It is **dead**: the GitHub repository is archived, its last
commit is 2024-10-18, and that commit is "docs: add deprecation message".

It is cited here only as evidence that the category runs on Android hardware — not as
anything to build on. Its death is itself an argument for depending only on maintained
components: Debian's own native `aapt`/`zipalign`/`apksigner`, plus Google's Java tools
(`d8.jar`, `android.jar`) which ship inside Vorv already.

## 6. Decision

**Debian 12 (bookworm) arm64, glibc, native aarch64 toolchain**, no desktop, no console
for the owner, 20 GB cap, every failure reported with a reason code. Toolchain:
`openjdk-17-jdk-headless`, `android-sdk-build-tools`, `aapt`, `zipalign`, `apksigner`;
plus Vorv's own `d8.jar`, `android.jar` and Kotlin compiler jars running on that JDK.

## 7. Unverified / estimates

- **Debian rootfs unpacked size**: the download servers returned no `content-length`
  when probed, so the earlier "~120 MB base, 4–6 GB with toolchain and Gradle caches"
  figure remains an **estimate** until measured inside the guest on the owner's phone.
- **First-run time**: Debian unpacks far slower than Alpine's 9 MB rootfs. No measured
  number yet.
- **`aapt` 10.0.0+r36 age**: it is an Android 10-era packer. Vorv's template-APK path
  does not depend on modern resource features, but this has not been exercised on
  device.
- Nothing in this document was executed on the owner's phone. The device remains the
  only proof that counts.
