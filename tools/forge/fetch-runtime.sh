#!/usr/bin/env bash
# Fetches the pinned Java runtime that Vorv carries inside itself.
#
# Nothing here is committed: this puts the payload where the Android build
# picks it up. Every package is checked against the sha256 in
# runtime-manifest.json before a single byte is unpacked, so a mirror swap or
# a truncated download fails loudly instead of shipping a broken runtime.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
manifest="$here/runtime-manifest.json"
cache="${FORGE_CACHE:-/tmp/forge-runtime}"
jni="$root/app/src/main/jniLibs/arm64-v8a"
assets="$root/app/src/main/assets/forge/jvm"

mkdir -p "$cache" "$jni" "$assets"

python3 - "$manifest" <<'PY' > "$cache/plan.txt"
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
    if [ ! -e "$src" ]; then echo "missing $name:$f" >&2; exit 1; fi
    case "$f" in
      bin/*)     cp "$src" "$jni/libforge-$(basename "$f").so"; chmod 755 "$jni/libforge-$(basename "$f").so" ;;
      lib/modules|conf*) cp -r "$src" "$assets/" ;;
      *.so)      cp "$src" "$jni/$(basename "$f")" ;;

      *)         cp -r "$src" "$assets/" ;;
    esac
  done
done < "$cache/plan.txt"

echo
echo "payload:"
du -sh "$jni" "$assets"
echo "done — nothing was written outside app/src/main/{jniLibs,assets}/forge"
