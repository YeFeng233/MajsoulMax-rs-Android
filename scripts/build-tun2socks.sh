#!/usr/bin/env bash
#
# Builds hev-socks5-tunnel for every shipped ABI and installs the result as
# app/src/main/jniLibs/<abi>/libhev-socks5-tunnel.so, where our CMake shim
# (app/src/main/cpp/CMakeLists.txt) links against it.
#
# The upstream project ships its own ndk-build makefiles, so we drive those
# rather than re-describing its sources in CMake — a kernel of build logic we
# would otherwise have to keep in sync by hand.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/external/hev-socks5-tunnel"
JNI_LIBS_DIR="${JNI_LIBS_DIR:-$ROOT/app/src/main/jniLibs}"
TARGET_ABIS="${TARGET_ABIS:-arm64-v8a armeabi-v7a x86_64}"

die() { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }
info() { printf '\033[36m==>\033[0m %s\n' "$*"; }

[[ -f "$SRC/Makefile" ]] || die \
  "hev-socks5-tunnel submodule is missing. Run: git submodule update --init --recursive"

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${NDK_HOME:-}}}"
[[ -n "$NDK" ]] || die "set ANDROID_NDK_HOME to your NDK path"
NDK_BUILD="$NDK/ndk-build"
[[ -x "$NDK_BUILD" ]] || die "ndk-build not found at $NDK_BUILD"

# Upstream ships its own ndk-build makefiles; locate them rather than assuming one
# path, and fail with the candidates listed so a layout change is self-diagnosing.
BUILD_SCRIPT=""
for candidate in "$SRC/jni/Android.mk" "$SRC/Android.mk" "$SRC/android/jni/Android.mk"; do
  if [[ -f "$candidate" ]]; then BUILD_SCRIPT="$candidate"; break; fi
done
if [[ -z "$BUILD_SCRIPT" ]]; then
  printf 'looked for Android.mk in:\n' >&2
  printf '  %s\n' "$SRC/jni/Android.mk" "$SRC/Android.mk" "$SRC/android/jni/Android.mk" >&2
  printf 'found instead:\n' >&2
  find "$SRC" -maxdepth 3 -name 'Android.mk' -printf '  %p\n' 2>/dev/null >&2 || true
  die "no ndk-build script found in the hev-socks5-tunnel submodule"
fi

info "building hev-socks5-tunnel for: $TARGET_ABIS (via $BUILD_SCRIPT)"

"$NDK_BUILD" -C "$SRC" \
  NDK_PROJECT_PATH="$SRC" \
  APP_BUILD_SCRIPT="$BUILD_SCRIPT" \
  APP_PLATFORM=android-26 \
  APP_ABI="$TARGET_ABIS" \
  NDK_LIBS_OUT="$SRC/libs" \
  NDK_OUT="$SRC/obj" \
  -j"$(nproc 2>/dev/null || echo 4)"

for abi in $TARGET_ABIS; do
  built="$SRC/libs/$abi/libhev-socks5-tunnel.so"
  if [[ ! -f "$built" ]]; then
    printf 'built artefacts for %s:\n' "$abi" >&2
    ls -1 "$SRC/libs/$abi" 2>/dev/null | sed 's/^/  /' >&2 || true
    die "expected $built after the build (module name changed upstream?)"
  fi
  mkdir -p "$JNI_LIBS_DIR/$abi"
  install -m 0644 "$built" "$JNI_LIBS_DIR/$abi/libhev-socks5-tunnel.so"
  info "$(printf '%-12s' "$abi") $(du -h "$JNI_LIBS_DIR/$abi/libhev-socks5-tunnel.so" | cut -f1)"
done

info "done"
