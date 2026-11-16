#!/usr/bin/env bash
# Puts the Linux system into the app: proot, its loader, libtalloc and the
# Alpine root filesystem.
#
# Same rule as fetch-runtime.sh — every download is checked against the
# sha256 in runtime-manifest.json before it is unpacked, so a swapped or
# truncated file fails here instead of on the phone. The rootfs (~4 MB) and
# the binaries are ignored by git; this script is how they come back.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
manifest="$here/runtime-manifest.json"
cache="${FORGE_CACHE:-/tmp/forge-runtime}"
jni="$root/app/src/main/jniLibs/arm64-v8a"
assetdir="$root/app/src/main/assets/forge/linux"

mkdir -p "$cache" "$jni" "$assetdir"

verify() { # file expected
  local got
  got="$(sha256sum "$1" | cut -d' ' -f1)"
  if [ "$got" != "$2" ]; then
    echo "REFUSED: $(basename "$1") has sha256 $got, manifest says $2" >&2
    exit 1
  fi
}

fetch() { # url dest sha
  local url="$1" dest="$2" sha="$3"
  if [ ! -f "$dest" ] || [ "$(sha256sum "$dest" | cut -d' ' -f1)" != "$sha" ]; then
    curl -fsSL --retry 3 -o "$dest.part" "$url"
    verify "$dest.part" "$sha"
    mv "$dest.part" "$dest"
  fi
  echo "$(basename "$dest") verified"
}

python3 - "$manifest" <<'PY' > "$cache/linux-plan.tsv"
import json, sys
lx = json.load(open(sys.argv[1]))["linuxSystem"]
for p in lx["packages"]:
    print("pkg\t%s\t%s\t%s\t%s" % (p["name"], p["url"], p["sha256"], p["prefix"]))
    for src, dest in p["take"].items():
        print("map\t%s\t%s\t%s" % (p["name"], p["prefix"] + "/" + src, dest))
r = lx["rootfs"]
print("rootfs\t%s\t%s\t%s\t%s" % (r["name"], r["url"], r["sha256"], r["as"]))
PY

while IFS=$'\t' read -r kind a b c d; do
  case "$kind" in
    pkg)
      deb="$cache/$a.deb"
      fetch "$b" "$deb" "$c"
      rm -rf "$cache/$a.d"; mkdir -p "$cache/$a.d"
      dpkg-deb -x "$deb" "$cache/$a.d"
      ;;
    map)
      src="$cache/$a.d/$b"
      [ -e "$src" ] || { echo "missing $a:$b" >&2; exit 1; }
      cp "$src" "$jni/$c"
      chmod 755 "$jni/$c"
      ;;
    rootfs)
      tgz="$cache/rootfs.tar.gz"
      fetch "$b" "$tgz" "$c"
      # Unpack the gzip layer here: AGP strips a .gz suffix from asset names,
      # which would leave a file called rootfs.tar holding gzipped bytes.
      gunzip -c "$tgz" > "$root/$d"
      ;;
  esac
done < "$cache/linux-plan.tsv"

# proot is linked against libtalloc.so.2. Gradle only packages files matching
# *.so, and Android loads a binary's dependencies from its own native library
# directory, which an app cannot write to, so our copy ships as libtalloc.so.
# Renaming the file is not enough on its own: point proot's NEEDED entry at that
# name too. The new string is shorter than the old one, so it is padded with
# NULs and every other offset in the dynamic string table stays valid.
python3 "$here/rename-needed.py" "$jni/libforge-proot.so" libtalloc.so.2 libtalloc.so

readelf -d "$jni/libforge-proot.so" | grep -q 'libtalloc\.so\]' \
  || { echo "proot does not reference libtalloc.so" >&2; exit 1; }

# Termux's runpath does not exist in this app, so the linker cannot find
# libtalloc.so / libandroid-shmem.so and proot dies before main(). Rewrite the
# runpaths to $ORIGIN (the folder the binary itself lives in == nativeLibraryDir)
# and make libtalloc's SONAME match the name it ships under. The script fails
# loudly if either edit did not take.
python3 "$here/fix-proot-deps.py" "$jni"

# proot resolves its loader from a compiled-in Termux path; PROOT_LOADER
# overrides it. Check that the override is actually honoured by the binary.
grep -aq 'PROOT_LOADER' "$jni/libforge-proot.so" \
  || { echo "this proot does not honour PROOT_LOADER" >&2; exit 1; }

echo
ls -l "$jni"/libforge-proot*.so "$jni/libtalloc.so" "$root/app/src/main/assets/forge/linux/rootfs.tar"
echo "linux system payload ready"
