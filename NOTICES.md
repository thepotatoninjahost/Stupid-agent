# Third-party components and licences

Vorv's own source is MIT (`LICENSE`). The APK also carries a Linux system and its
tools; those keep their own licences, listed here. Licences below were read from
each component's own metadata — `tools/forge/runtime-manifest.json` for the
payload, the published POM for the Maven artifacts — not assumed from memory.

## Payload: what travels inside the APK

| Component | Where it comes from | Licence |
|-----------|--------------------|---------|
| proot | Termux `proot` package | GPL-2.0 |
| libtalloc | Termux `libtalloc` package | LGPL-3.0-or-later |
| libandroid-shmem | Termux package | Apache-2.0 |
| libandroid-spawn | Termux package | Apache-2.0 |
| OpenJDK 17 (`openjdk-17-jdk-headless`) modules | Termux package | GPL-2.0 WITH Classpath-exception-2.0 |
| d8, apksigner, `android.jar` | Android SDK build-tools 37.0.0 and platform 37 | Apache-2.0 (Android SDK licence applies to the SDK itself) |
| Debian 12 (bookworm) arm64 rootfs | `docker://library/debian:bookworm-slim` | Debian Social Contract / DFSG; each guest package carries its own licence in `/usr/share/doc/<pkg>/copyright` |

The manifest pins every one of these by sha256; the versions in it are the ones
this file describes.

### GPL and LGPL obligations

proot is GPL-2.0 and libtalloc is LGPL-3.0-or-later, and both are redistributed
inside the APK as unmodified binaries taken from Termux packages. Distributing
them means the corresponding source must be available to whoever receives the
APK. It is, and it stays that way as long as:

- the manifest records the exact package URL and sha256 each binary came from
  (it does), and
- those URLs stay resolvable, or a copy of the source is kept alongside the
  release.

Do not patch proot or libtalloc inside this repository without also publishing
the patch: a modified GPL binary shipped to users needs its modified source, and
a modified LGPL library needs both its source and a way to relink. Nothing in
`app/src/main` modifies them today; they are copied byte for byte.

OpenJDK is GPL-2.0 with the Classpath exception, which is what makes it usable
from an app at all: code that merely links against it is not covered.

## Maven libraries in the app

| Licence | Components |
|---------|------------|
| Apache-2.0 | AndroidX (core, activity, lifecycle, navigation, Room, WorkManager, DataStore, security-crypto, documentfile), Jetpack Compose (BOM, UI, Material3, icons), Kotlin stdlib and coroutines, OkHttp and MockWebServer, Gson, Apache Commons Compress |
| MIT | jsoup, Robolectric (test only) |
| MPL-2.0 | Mozilla Rhino |
| 0BSD | XZ for Java (`org.tukaani:xz`) |
| EPL-1.0 | JUnit 4 (test only) |
| BSD-3-Clause | GenieX Android (`com.qualcomm.qti:geniex-android`) |
| MIT | Chaquopy Gradle plugin and runtime (Python sandbox) |

GenieX is published under BSD-3-Clause **and** Qualcomm's Terms of Use; the
terms are worth reading before redistributing a build that enables the on-device
model path, because they are not an open-source licence in the usual sense.

## Python

The Chaquopy sandbox ships CPython 3.11 (PSF-2.0). No third-party Python
packages are installed — `chaquopy { defaultConfig { version = "3.11" } }` in
`app/build.gradle.kts` installs nothing beyond the standard library, and the
runtime glue (`app/src/main/python/`) uses stdlib only. Adding a `pip` entry
there adds its licence to this file in the same commit.

## Reporting a licence problem

Open an issue naming the component and the licence you believe applies. A
component whose licence is incompatible with redistribution gets removed or
replaced, not quietly kept.
