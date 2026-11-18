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
    ("assets/forge/linux/rootfs.tar", "the Debian root filesystem"),
    ("assets/forge/jvm/modules", "the guest Java runtime image"),
    ("assets/forge/tools/d8.jar", "on-phone dexer"),
    ("assets/forge/tools/android.jar", "android.jar for on-phone compiles"),
    ("assets/forge/tools/apksigner.jar", "on-phone signing"),
]


# A build that silently doubles in size is a build that quietly stopped being
# shippable over a phone connection. 0.9.20 is 353 MiB; this leaves headroom
# for a compiler or runtime bump and still refuses an accidental payload
# duplication (the pre-0.8.10 APK carried the rootfs twice).
MAX_APK_BYTES = 400 * 1024 * 1024

SOURCE_ROOT = "app/src/main/java"
MIRROR_ROOT = "assets/source"


def check_source_mirror(z: zipfile.ZipFile) -> list[str]:
    """The healer reads assets/source to patch itself. If that copy differs
    from the code in the APK, generated patches target code that is not there.
    The Gradle build syncs the mirror before packaging; this proves the sync
    reached the finished artifact."""
    import os

    if not os.path.isdir(SOURCE_ROOT):
        return []  # run outside the repo: nothing to compare against
    problems: list[str] = []
    on_disk = {
        os.path.relpath(os.path.join(dp, f), SOURCE_ROOT).replace(os.sep, "/")
        for dp, _, files in os.walk(SOURCE_ROOT) for f in files
    }
    mirrored = {
        n[len(MIRROR_ROOT) + 1:] for n in z.namelist()
        if n.startswith(MIRROR_ROOT + "/") and n.endswith(".kt")
    }
    for rel in sorted(on_disk - mirrored):
        if rel.endswith(".kt"):
            problems.append(f"{rel}: in the code but not in the shipped mirror")
    for rel in sorted(mirrored - on_disk):
        problems.append(f"{rel}: in the shipped mirror but not in the code")
    for rel in sorted(on_disk & mirrored):
        with open(os.path.join(SOURCE_ROOT, rel), "rb") as fh:
            if fh.read() != z.read(f"{MIRROR_ROOT}/{rel}"):
                problems.append(f"{rel}: shipped mirror is not the source that was built")
    return problems


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
        problems = check_source_mirror(z)
        if problems:
            print(f"REFUSED: {apk} ships a stale source mirror:", file=sys.stderr)
            for p in problems:
                print(f"  {p}", file=sys.stderr)
            print("The build's syncSourceMirror step did not reach the APK. "
                  "Check that preBuild, not just build, depends on it.", file=sys.stderr)
            return 1
        import os
        too_big = os.path.getsize(apk) > MAX_APK_BYTES
        if too_big:
            print(f"REFUSED: {apk} is {os.path.getsize(apk) // (1024 * 1024)} MB, "
                  f"over the {MAX_APK_BYTES // (1024 * 1024)} MB budget. Something is "
                  f"packed twice; check the payload before shipping.", file=sys.stderr)
            return 1
        total = sum(z.getinfo(n).file_size for n, _ in REQUIRED)
        print(f"payload present in {apk}: {len(REQUIRED)} required entries "
              f"(>= {total // (1024 * 1024)} MB unpacked), kotlin compiler: "
              f"{kotlin[0].rsplit('/', 1)[-1]}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
