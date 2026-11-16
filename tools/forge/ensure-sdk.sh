#!/usr/bin/env bash
# Makes sure the Android SDK has the two pieces the payload copies out of it:
# platforms/android-37/android.jar (for d8 to compile against on the phone) and
# build-tools/37.0.0/{d8,apksigner}.jar.
#
# Needed because the CI runner image ships an SDK without android-37; AGP would
# download it lazily, but provisionPayload runs before that and must not depend
# on the order. Installing the same two packages here is what makes a fresh
# clone and a fresh runner behave the same way.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
platform="platforms;android-37"
buildtools="build-tools;37.0.0"

resolve_sdk() {
  local c
  for c in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    [ -n "$c" ] && [ -d "$c" ] && { printf '%s\n' "$c"; return 0; }
  done
  if [ -f "$root/local.properties" ]; then
    c="$(sed -n 's/^sdk\.dir=//p' "$root/local.properties" | tail -1)"
    [ -n "$c" ] && [ -d "$c" ] && { printf '%s\n' "$c"; return 0; }
  fi
  for c in /usr/local/lib/android/sdk "$HOME/Android/Sdk" /opt/android-sdk; do
    [ -d "$c" ] && { printf '%s\n' "$c"; return 0; }
  done
  return 1
}

# Android 17 is API "37.0" and its platform directory is spelled either
# "android-37" or "android-37.0" depending on the SDK; both are fine.
complete() { # sdk
  compgen -G "$1/platforms/android-37*/android.jar" >/dev/null || return 1
  compgen -G "$1/build-tools/37*/lib/d8.jar" >/dev/null || return 1
  compgen -G "$1/build-tools/37*/lib/apksigner.jar" >/dev/null || return 1
  return 0
}

sdk="$(resolve_sdk || true)"
if [ -z "$sdk" ]; then
  echo "REFUSED: no Android SDK found. Point ANDROID_HOME at one that has $platform and $buildtools." >&2
  exit 1
fi

if complete "$sdk"; then
  echo "sdk $sdk already has android-37 and build-tools 37.0.0"
  exit 0
fi

sm=""
for c in "$sdk"/cmdline-tools/*/bin/sdkmanager "$sdk"/tools/bin/sdkmanager; do
  [ -x "$c" ] && { sm="$c"; break; }
done
if [ -z "$sm" ]; then
  sm="$(command -v sdkmanager || true)"
fi
if [ -z "$sm" ]; then
  echo "REFUSED: $sdk lacks android-37 / build-tools 37.0.0 and has no sdkmanager." >&2
  echo "Install them with: sdkmanager '$platform' '$buildtools'" >&2
  exit 1
fi

echo "installing android-37 and build-tools 37.0.0 into $sdk (one-time, ~150 MB)"
yes | "$sm" --sdk_root="$sdk" --licenses >/dev/null 2>&1 || true
# sdkmanager exits 0 even when a package is already present; only the files it
# was asked for are the proof, so they are re-checked below. The platform may
# be published as "platforms;android-37.0" (the API 37.0 name) or as
# "platforms;android-37"; try both instead of guessing which SDK repo is live.
if ! complete "$sdk"; then
  yes | "$sm" --sdk_root="$sdk" "platforms;android-37.0" "$buildtools" >/dev/null 2>&1 || true
fi
if ! complete "$sdk"; then
  yes | "$sm" --sdk_root="$sdk" "$platform" "$buildtools" >/dev/null 2>&1 || true
fi

if ! complete "$sdk"; then
  echo "REFUSED: after installing, $sdk still lacks android-37 or build-tools 37.0.0." >&2
  exit 1
fi
echo "sdk ready: $sdk"
