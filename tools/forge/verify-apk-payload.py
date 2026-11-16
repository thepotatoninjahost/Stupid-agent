#!/usr/bin/env python3
"""Refuse to ship an APK that has no computer inside it.

Nine builds went to the phone without the payload, and the app could only say
"this build has no Linux system" after the owner had installed it. This runs on
the APK itself — the thing that gets downloaded — and lists every file the
in-app computer needs. Missing any of them is a build failure here, before a
release exists, instead of a discovery on the phone.

Usage: verify-apk-payload.py app/build/outputs/apk/debug/app-debug.apk
"""
import sys
import zipfile

# (entry, why) — every one of these is executed or read at run time.
REQUIRED = [
    ("lib/arm64-v8a/libforge-proot.so", "proot itself"),
    ("lib/arm64-v8a/libforge-proot-loader.so", "proot's loader (PROOT_LOADER)"),
    ("lib/arm64-v8a/libtalloc.so", "proot's libtalloc dependency"),
    ("lib/arm64-v8a/libandroid-shmem.so", "proot's shared-memory dependency"),
    ("lib/arm64-v8a/libjvm.so", "the guest JVM"),
    ("assets/forge/linux/rootfs.tar", "the Alpine root filesystem"),
    ("assets/forge/jvm/modules", "the guest Java runtime image"),
    ("assets/forge/tools/d8.jar", "on-phone dexer"),
    ("assets/forge/tools/android.jar", "android.jar for on-phone compiles"),
    ("assets/forge/tools/apksigner.jar", "on-phone signing"),
]


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: verify-apk-payload.py <apk>", file=sys.stderr)
        return 2
    apk = argv[1]
    with zipfile.ZipFile(apk) as z:
        names = set(z.namelist())
        missing = [(n, why) for n, why in REQUIRED if n not in names]
        # The bundled Kotlin compiler is versioned by its jar name, so match by
        # prefix rather than pinning a version in two places.
        kotlin = [n for n in names if n.startswith("assets/forge/tools/kotlin-compiler-embeddable-")]
        if not kotlin:
            missing.append(("assets/forge/tools/kotlin-compiler-embeddable-*.jar",
                            "the on-phone Kotlin compiler"))
        if missing:
            print(f"REFUSED: {apk} cannot run a program — it is missing:", file=sys.stderr)
            for n, why in missing:
                print(f"  {n}  ({why})", file=sys.stderr)
            print("Provision the payload (tools/forge/fetch-runtime.sh, "
                  "tools/forge/fetch-linux.sh) before building.", file=sys.stderr)
            return 1
        total = sum(z.getinfo(n).file_size for n, _ in REQUIRED)
        print(f"payload present in {apk}: {len(REQUIRED)} required entries "
              f"(>= {total // (1024 * 1024)} MB unpacked), kotlin compiler: "
              f"{kotlin[0].rsplit('/', 1)[-1]}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
