#!/usr/bin/env python3
"""Rewrite one DT_NEEDED entry inside a dynamic binary.

Android's native library directory is where a binary's dependencies are looked
for, and Gradle only packages files ending in .so. When a library's real name
ends in something else (libtalloc.so.2), the only way to make it loadable is to
change the name the binary asks for.

The replacement must be no longer than the original: the string lives in the
dynamic string table, and every other indexed string keeps its offset only if
the entry's length is unchanged. Shorter names are padded with NULs.
"""
import sys


def main() -> int:
    if len(sys.argv) != 4:
        print(__doc__, file=sys.stderr)
        return 2
    path, old, new = sys.argv[1], sys.argv[2].encode(), sys.argv[3].encode()
    if len(new) > len(old):
        print(f"{new.decode()} is longer than {old.decode()}", file=sys.stderr)
        return 1

    data = bytearray(open(path, "rb").read())
    # The needle has to be NUL-terminated to be the entry itself and not part of
    # a longer name, and it must appear exactly once.
    needle = old + b"\x00"
    occurrences = data.count(needle)
    if occurrences != 1:
        print(f"expected one {old.decode()} entry in {path}, found {occurrences}", file=sys.stderr)
        return 1

    at = data.index(needle)
    data[at:at + len(old)] = new + b"\x00" * (len(old) - len(new))
    open(path, "wb").write(bytes(data))
    print(f"{path}: {old.decode()} -> {new.decode()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
