#!/usr/bin/env python3
"""Make proot's own shared-library dependencies resolvable inside Vorv.

proot and its libraries were built for Termux, so their DT_RUNPATH says
/data/data/com.termux/files/usr/lib. That directory does not exist in Vorv, the
app cannot create it (it is another app's private data), and Android's dynamic
linker therefore cannot find libtalloc.so or libandroid-shmem.so. proot then
dies in the linker, before main(), which looks exactly like "proot does not
start" with no useful message.

Two in-place edits fix it without touching link order or offsets:

  DT_RUNPATH  -> $ORIGIN   the loader searches the directory the binary itself
                           was loaded from, which is nativeLibraryDir, where
                           the rest of the payload sits. Install-independent:
                           no /data/app path has to be known at build time.
  DT_SONAME   -> libtalloc.so
                           the file ships as libtalloc.so (Gradle only packages
                           *.so), while its SONAME is libtalloc.so.2. A loader
                           that matches the requested name against the SONAME
                           would refuse it; making the two identical removes
                           that question entirely.

Every replacement is same-length-or-shorter and NUL padded, so the dynamic
string table keeps its offsets. If a string is not there, or the result is not
what was asked for, this exits non-zero — the build must not silently ship a
payload that cannot load.
"""
import sys

TERMUX_LIB = b"/data/data/com.termux/files/usr/lib"
ORIGIN = b"$ORIGIN"


def patch(path: str, old: bytes, new: bytes, expect_at_least: int = 1) -> int:
    if len(new) > len(old):
        sys.exit(f"REFUSED: replacement for {old!r} is longer than the original")
    data = bytearray(open(path, "rb").read())
    needle = old + b"\0"
    filler = new + b"\0" * (len(old) - len(new)) + b"\0"
    count = data.count(needle)
    if count < expect_at_least:
        sys.exit(f"REFUSED: {path} contains no {old.decode()!r} (found {count})")
    data = data.replace(needle, filler)
    open(path, "wb").write(data)
    return count


def dt_strings(path: str) -> dict:
    """Read DT_RUNPATH / DT_SONAME out of the dynamic section (ELF64 LE)."""
    import struct
    d = open(path, "rb").read()
    if d[:4] != b"\x7fELF" or d[4] != 2:
        return {}
    e_phoff, = struct.unpack_from("<Q", d, 0x20)
    e_phentsize, e_phnum = struct.unpack_from("<HH", d, 0x36)
    loads, dyn = [], None
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type, = struct.unpack_from("<I", d, off)
        p_offset, p_vaddr = struct.unpack_from("<QQ", d, off + 0x08)
        p_filesz, = struct.unpack_from("<Q", d, off + 0x20)
        if p_type == 1:
            loads.append((p_vaddr, p_offset, p_filesz))
        elif p_type == 2:
            dyn = (p_offset, p_filesz)

    def to_off(vaddr: int) -> int:
        for v, o, size in loads:
            if v <= vaddr < v + size:
                return o + (vaddr - v)
        raise ValueError(f"{path}: vaddr {vaddr:#x} is in no PT_LOAD segment")

    if dyn is None:
        return {}
    strtab = None
    entries = {}
    for i in range(dyn[1] // 16):
        tag, val = struct.unpack_from("<qQ", d, dyn[0] + i * 16)
        if tag == 5:
            strtab = to_off(val)
        elif tag in (14, 29):
            entries[tag] = val
    out = {}
    for tag, name in ((14, "soname"), (29, "runpath")):
        if tag in entries:
            start = strtab + entries[tag]
            end = d.index(b"\0", start)
            out[name] = d[start:end].decode("utf-8", "replace")
    return out


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        return 2
    jni = argv[1]
    proot = f"{jni}/libforge-proot.so"
    talloc = f"{jni}/libtalloc.so"
    shmem = f"{jni}/libandroid-shmem.so"

    for lib, minimum in ((proot, 1), (talloc, 1), (shmem, 1)):
        n = patch(lib, TERMUX_LIB, ORIGIN, minimum)
        print(f"runpath  {lib}: {n} Termux path(s) -> $ORIGIN")
    patch(talloc, b"libtalloc.so.2", b"libtalloc.so")
    print(f"soname   {talloc}: libtalloc.so.2 -> libtalloc.so")

    # The post-condition is the whole point: a build that ships the Termux path
    # as its runpath again must fail here, not on the owner's phone. proot also
    # carries the string as its compiled-in libexec/ default loader path, which
    # PROOT_LOADER overrides — so the check reads the dynamic entry itself.
    for lib in (proot, talloc, shmem):
        dyn = dt_strings(lib)
        if dyn.get("runpath") != "$ORIGIN":
            sys.exit(f"FAILED: {lib} runpath is {dyn.get('runpath')!r}, expected '$ORIGIN'")
    if dt_strings(talloc).get("soname") != "libtalloc.so":
        sys.exit(f"FAILED: libtalloc.so soname is {dt_strings(talloc).get('soname')!r}")
    for lib in (proot, talloc, shmem):
        print(f"checked  {lib}: runpath=$ORIGIN")
    print("proot dependency resolution: Termux runpaths gone, $ORIGIN present")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
