# Independent apps: per-app package names and labels

**Status: plan. None of this is implemented.** Written 2026-11-28, before any
code, because installing app #2 currently replaces app #1 and that has to stop
in a way that does not break the working build.

## 1. What a build produces today (measured, not assumed)

- `app/src/main/assets/forge/template.apk` is 919 bytes and holds exactly two
  entries: `AndroidManifest.xml` (1,740 bytes) and `resources.arsc` (40 bytes,
  an empty table). There is no `res/` and no icon —
  `aapt2 dump badging` reports `icon=''`.
- Its manifest, dumped with build-tools 37.0.0: `package="host.vorv.app"`,
  `<application android:label="Vorv app">`, one activity
  `android:name="host.vorv.app.Main"`, `uses-sdk` 24/34, no permissions.
- The string pool is **UTF-16**, 26 strings, 0 styles, no `SORTED` flag. The
  three entries that matter: index 9 = `Vorv app`, index 17 = `host.vorv.app`,
  index 18 = `host.vorv.app.Main`. Index 18 names the class d8 compiled from
  `Main.java`, so it must not change when the package does.
- `PhoneBuilder` replaces only `classes.dex`. Every app therefore has the same
  application id, and Android identifies an app by that id — "Every Android app
  has a unique application ID … This ID uniquely identifies your app on the
  device" — so an install of app #2 replaces app #1 and its data.

## 2. What must be true when this is done

1. `build_app` derives a package name and a label from the project.
2. The launcher still starts `host.vorv.app.Main`, the class that was compiled;
   only the app's identity changes.
3. Deterministic: the same project always gets the same id, so rebuilding it is
   an update of that app; two different projects get different ids, so both
   stay installed side by side.
4. Nothing about Vorv's own manifest, build, SDK or dependencies changes. No new
   binary, no new dependency, no version bump without the owner's word.

## 3. Why the manifest is edited rather than relinked with aapt2

- aapt2 is a **host** program. Google publishes `linux-x86_64`, `osx` and
  `windows` classifiers; AGP 9.5 added a `linux_arm64` build, which is aarch64
  **glibc**. Android has no glibc, so a glibc aarch64 binary cannot run as an
  app payload, whatever it is named.
- `aapt2 link --rename-manifest-package` works only in the link phase, on a
  text manifest plus compiled resources. We have a finished APK and no aapt2.
- Termux ships an arm64 aapt2, but it is an old aapt-2.19-era build and fails to
  read the resource table of an android-35/36 `android.jar`; carrying a second
  native toolchain is a larger change than the problem.
- The platform's own renamer is the model to copy: aapt2's
  `RenameManifestPackage` sets the package attribute and first fully qualifies
  class names **with the old package**, "so that the code does not need to
  change". Our manifest already stores the activity name fully qualified, which
  is even simpler.

## 4. Design: rewrite the compiled manifest's string pool

A compiled Android manifest (AXML) is an outer `RES_XML_TYPE` chunk, a string
pool, an optional resource map, then node chunks; **attributes refer to strings
by index**, never by offset. That gives a safe edit:

- Replace the *values* of two existing pool entries — the one that is exactly
  `host.vorv.app` (index 17) and the one that is exactly `Vorv app` (index 9) —
  keeping the indices and their order. No node byte moves; the only other
  changes are the two chunk sizes.
- The resource map is positional: entry *i* is the resource id of string *i*. It
  stays valid precisely because indices do not change. (aapt2 sorts the pool so
  attribute names come first for exactly this reason.)
- Matching is by exact value. `host.vorv.app.Main` is a separate entry and is
  left alone, so the launcher still finds the compiled class.
- Writer rules, from aapt/aapt2: a UTF-16 entry is a `uint16` length (high bit
  set plus a second word above 32,767 chars), the UTF-16LE characters, then a
  `uint16` 0; `stringsStart` = header + offset array; the pool is padded up to a
  4-byte boundary ("Pad ending string position up to a uint32_t boundary");
  `SORTED_FLAG` must be clear or re-established if a replacement reorders values
  (the template has none, so this is a guard, not a step); the outer chunk's
  size is fixed up last.
- The failure mode of a wrong pool is a parse error at install or launch — loud,
  not silent. Gates: re-parse with our own reader in a unit test, then
  `aapt2 dump xmltree`/`badging` on the produced APK here, then install on the
  phone.

## 5. Identity rules

**Package name — default `host.vorv.app.<slug>`**

- `slug` = project folder name, lowercased; every character outside `[a-z0-9_]`
  becomes `_`; runs of `_` collapse; leading/trailing `_` trimmed; prefixed with
  `a` if it does not start with a letter; `app` if empty; cut to 24 characters.
- Android's documented rule for an application id: at least two segments, each
  starting with a letter, characters only `[a-zA-Z0-9_]`. The fixed prefix
  satisfies the segments; only the slug needs care, and the validator enforces
  the whole id anyway.

**Label**

- Folder name with `_`/`-` as spaces, each word capitalised: `todo` → "Todo",
  `unit-converter` → "Unit Converter"; 40 characters maximum; "Vorv app" only if
  the name yields nothing. The label is a plain string in the manifest — no
  resources involved.

**Collision is refused, never silently absorbed**

- A build writes `files/forge/apps.json`, mapping package → project folder.
- If a *different* existing project already owns the derived package, the build
  fails with "app id `…` is already used by project `…`; rename this folder" —
  the alternative is the exact bug this plan exists to fix, app #2 quietly
  taking over app #1's data.

**Existing installs**

- The already-installed `host.vorv.app` keeps its own id. The first rebuild of an
  old project installs a *new* app; the old one is left installed, untouched,
  for the owner to remove. Nothing is migrated or deleted automatically.

## 6. Stages, each with its own proof

**Stage 1 — identity only (the whole fix for "independent apps")**

1. `Axml.kt` (~250 lines): read the manifest into header + pool + rest;
   `strings()` for tests; `rewrite(Map<String,String>)`: reject a value that is
   missing or appears twice, rebuild the pool, fix sizes.
2. `AppIdentity.kt` (~70): `Identity(packageName, label)` and
   `derive(projectName)` with the rules above.
3. `PhoneBuilder`: derive the identity, rewrite the template's manifest bytes,
   write that entry into the APK in place of the template's, add `packageName`
   and `label` to `Build` and to the step log; `BuildAppTool` prints both so the
   agent and the ledger know what was installed.
4. `apps.json` registry + refusal.

Proofs:

- Identity tests: dots, dashes, spaces, digits, uppercase, non-ASCII, empty;
  every generated id passes the documented rule; two names differ; one name
  twice is identical.
- AXML tests on the **real** template: still 26 strings; indices 17 and 9
  changed; 18 unchanged; every other value equal; offsets monotonic; chunk
  sizes consistent; our reader re-parses what we wrote; rewriting twice with the
  same values gives identical bytes.
- A JVM test can rebuild a real APK from the template (manifest swap + junk
  dex); `aapt2 dump badging` on that file here is the external check, since
  aapt2 is not on the phone. Record the output in this file when it is run.
- On the phone: build two projects, see two entries with different names under
  Settings → Apps, open both, then `native_test` both. Owner-only.

**Stage 2 — richer identity, only if wanted**: optional `vorv-app.json` per
project (`name`, `package`, `version_name`), `versionCode` written and bumped so
a reinstall is never `INSTALL_FAILED_VERSION_DOWNGRADE`, and permissions from a
list. Needs a writer that can add strings and attributes, not just replace.

**Stage 3 — icon**: needs a resource table, i.e. aapt2 (in the guest or a
native build) or our own `resources.arsc` writer. Out of scope now.

**Stage 4 — Kotlin**: separate plan; the builder still filters `.java` and
requires `Main.java`.

## 7. What this deliberately does not do

- Adds no permission, activity, service or resource; no icon; `minSdk`/`targetSdk`
  stay at the template's 24/34. Changing any of those is a manifest
  configuration change and needs the owner's explicit permission.
- Does not touch Vorv's own package, manifest, SDK levels or dependencies.
- Does not rename or migrate apps already installed.

## 8. Decisions to confirm before code

1. Base id `host.vorv.app.<slug>` (recommended). Alternatives: `<slug>.vorv.app`,
   `host.vorv.<slug>`.
2. Label derived from the folder name (recommended); a `vorv-app.json` that
   overrides name and id can come in stage 2.

## Sources

- Application id rules and uniqueness: <https://developer.android.com/build/configure-app-module>
  — "Every Android app has a unique application ID … uniquely identifies your app
  on the device"; "It must have at least two segments (one or more dots). Each
  segment must start with a letter. All characters must be alphanumeric or an
  underscore."
- aapt2 link and `--rename-manifest-package`: <https://developer.android.com/tools/aapt2>
  — the flag is listed under **link**; "the generated APK does not contain DEX
  bytecode and is unsigned".
- Rename semantics: <https://android.googlesource.com/platform/frameworks/base/+/master/tools/aapt2/link/ManifestFixer.cpp>
  — `RenameManifestPackage` "FullyQualif[ies] all class names" with the original
  package before assigning the new one.
- No Android or glibc-free aapt2: <https://github.com/dotnet/android/issues/11184>
  — published prebuilts are "only windows-x86_64, darwin-x86_64, darwin-arm64 and
  linux-x86_64"; <https://issuetracker.google.com/issues/227219818> — linux_arm64
  added in 2026, still a Linux host build.
- Termux aapt2 too old for current resource tables:
  <https://github.com/voidnullvalue/Icsee-android/blob/main/BUILDING_IN_PROOT.md>
  — "Termux's own native aarch64 aapt2 … is too old to parse the resource table
  format used by android-35/android-36's android.jar".
- AXML layout: <https://github.com/facebook/buck/blob/main/src/com/facebook/buck/android/resources/ResourcesXml.java>
  — header, string pool, optional RefMap, node chunks; attributes reference
  strings by index. Chunk constants:
  <https://github.com/facebook/redex/blob/main/libresource/androidfw/ResourceTypes.h>.
- Resource map is positional: <https://android.googlesource.com/platform/frameworks/base/+/master/tools/aapt2/format/binary/XmlFlattener.cpp>
  — sorts the pool "so that attribute resource IDs show up first", then writes
  "the array of resource IDs, indexed by StringPool order".
- String pool encoding and padding: <https://github.com/aospapp/aosp/blob/main/frameworks/base/tools/aapt/StringPool.cpp>
  — "Pad ending string position up to a uint32_t boundary";
  <https://github.com/MIPS/frameworks-base/blob/master/tools/aapt2/StringPool.cpp>
  — `EncodeLength`/`EncodedLengthUnits` for the UTF-8 form;
  <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/androidfw/include/androidfw/ResourceTypes.h>
  — UTF-16 entries are a `uint16` length, characters, terminator; `UTF8_FLAG`
  and `SORTED_FLAG` definitions.

Sources not read: the current `tools/aapt2/StringPool.cpp` page returns 404 on
android.googlesource (the mirror linked above is an older revision), so the
per-entry 4-byte alignment of the modern writer is **not** confirmed from
current source — only aapt's pool padding and an independent Kotlin port's
`align4()` after the string block. Stage 1 must therefore validate its output
with `aapt2 dump` and an install rather than trusting the layout argument alone.
