#!/usr/bin/env bash
# Puts Vorv's build toolchain where the Android build picks it up.
#
# Every download is checked against the sha256 in runtime-manifest.json before
# a single byte is unpacked, so a swapped or truncated file fails loudly here
# instead of on the phone at the user's expense. Nothing lands in git: the
# payload is ~200 MB and the repository must stay clonable.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
manifest="$here/runtime-manifest.json"
cache="${FORGE_CACHE:-/tmp/forge-runtime}"
jni="$root/app/src/main/jniLibs/arm64-v8a"
jvm="$root/app/src/main/assets/forge/jvm"
tools="$root/app/src/main/assets/forge/tools"

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$sdk" ] || { echo "set ANDROID_HOME to an Android SDK that has platforms/android-37 and build-tools/37.0.0" >&2; exit 1; }

mkdir -p "$cache" "$jni" "$jvm" "$tools"

python3 - "$manifest" <<'PY' > "$cache/plan.tsv"
import json, sys
m = json.load(open(sys.argv[1]))
for p in m["packages"]:
    print("\t".join([p["name"], p["url"], p["sha256"], p["prefix"], ",".join(p["take"])]))
PY

while IFS=$'\t' read -r name url sha prefix take; do
  deb="$cache/$name.deb"
  if [ ! -f "$deb" ] || [ "$(sha256sum "$deb" | cut -d' ' -f1)" != "$sha" ]; then
    echo "fetching $name"
    curl -fsSL --retry 3 -o "$deb.part" "$url"
    got="$(sha256sum "$deb.part" | cut -d' ' -f1)"
    if [ "$got" != "$sha" ]; then
      echo "REFUSED: $name has sha256 $got, manifest says $sha" >&2
      rm -f "$deb.part"; exit 1
    fi
    mv "$deb.part" "$deb"
  fi
  echo "$name verified"

  work="$cache/$name.d"
  rm -rf "$work"; mkdir -p "$work"
  dpkg-deb -x "$deb" "$work"

  IFS=',' read -ra files <<< "$take"
  for f in "${files[@]}"; do
    src="$work/$prefix/$f"
    [ -e "$src" ] || { echo "missing $name:$f" >&2; exit 1; }
    case "$f" in
      bin/*) cp "$src" "$jni/libforge-$(basename "$f").so"; chmod 755 "$jni/libforge-$(basename "$f").so" ;;
      lib/modules) cp "$src" "$jvm/modules" ;;
      lib/server/libjvm.so) cp "$src" "$jni/libjvm.so" ;;
      lib/*.so) cp "$src" "$jni/$(basename "$f")" ;;
      conf) cp -r "$src" "$jvm/conf" ;;
      *) cp -r "$src" "$jni/$(basename "$f")" ;;
    esac
  done
done < "$cache/plan.tsv"

python3 - "$manifest" "$sdk" "$tools" <<'PY'
import json, os, shutil, sys
m = json.load(open(sys.argv[1])); sdk, out = sys.argv[2], sys.argv[3]
for f in m["sdkFiles"]:
    src = os.path.join(sdk, f["from"])
    if not os.path.isfile(src):
        sys.exit("missing SDK file %s — install platforms/android-37 and build-tools/37.0.0" % f["from"])
    shutil.copyfile(src, os.path.join(out, f["as"]))
    print("sdk %s -> %s" % (f["from"], f["as"]))
PY

echo
du -sh "$jni" "$jvm" "$tools"
echo "payload ready"
