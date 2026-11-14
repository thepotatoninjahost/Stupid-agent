# Testing a native Android app on the phone

## The path that now exists

```
write the app  →  build an APK  →  native_test  →  the user gets a working app
                                     │
                                     ├── installs through PackageInstaller (no root)
                                     ├── opens it with its real launcher intent
                                     ├── waits for it to be on screen
                                     ├── taps, types and asserts each step
                                     └── FAILs if any step fails or the app vanishes
```

## Why each part is the way it is

**PackageInstaller, not `pm install`.**
A normal install session is the only way a non-rooted app can install an APK,
and Android still shows its own install consent. An app that installed packages
silently would be malware-shaped; that consent stays.

**A real launch, not a reflection call.**
`getLaunchIntentForPackage` + `startActivity` is what the user's tap does. If
the activity is missing, misconfigured or crashes on start, this fails here
instead of on the user's home screen.

**AccessibilityService to see and touch.**
Reading another app's screen and clicking its buttons without root is exactly
what an accessibility service is for; Android offers nothing else. It is off
until the user turns on "Vorv app testing" in Settings → Accessibility, and
`native_test` refuses to run (and says why) while it is off. It is used only
while an app built here is under test.

**The app vanishing is a failure.**
After every step the foreground package is re-checked. If it is no longer the
app under test, the app crashed or was thrown out — recorded as a failure, not
skipped.

**No steps means no pass.**
Same rule as `web_test`: a run that verified nothing is reported as
`FAIL — nothing was verified`.

## Verified here

- `:app:compileDebugKotlin` and `:app:assembleDebug` succeed; the produced
  `app-debug.apk` (host.vorv 0.8.0) was inspected with `aapt2 dump`: it really
  contains the accessibility service with
  `BIND_ACCESSIBILITY_SERVICE`, its intent filter and its config meta-data, and
  `REQUEST_INSTALL_PACKAGES`.
- The accessibility config XML is resource-linked into the APK (a malformed one
  fails the build).

## Not verified here, and why

This workspace has no phone and no emulator, so these can only be confirmed on
the S25: an actual install session completing, the launch intent resolving,
the accessibility tree being readable, and a tap landing. The code compiles
and the APK is correct; a device run is the last step and it happens on the
user's phone, not here.

## The remaining gap: building the APK on the phone

Building an APK needs a compiler (`kotlinc`/`javac`), a dexer (`d8`) and a
resource packager (`aapt2`). All of them are host programs; the phone has no
JVM they can run on. Two honest routes:

1. **Build off-device, test on-device (works today).** A GitHub Actions
   workflow builds the APK on a real runner; the app downloads the artifact to
   its own computer and then `native_test` installs, opens and taps through it.
   The phone still does the part the user cares about — using the app.
2. **Build on-device (large, needs the phone to verify).** Ship the toolchain
   as native libraries and run the JVM-based tools on ART, the way AIDE did
   (a Java compiler compiled to DEX, `d8` compiled to DEX, `aapt2` ported to
   arm64). It is possible without root and without Termux, but nobody has
   proven it on this device yet, so it is not promised here.
