#!/usr/bin/env bash
#
# Cross-compiles the Rust MITM core into app/src/main/jniLibs/<abi>/libmajsoulmax.so.
#
# Requirements: rustup, cargo-ndk, protoc and an Android NDK (ANDROID_NDK_HOME).
# Invoked automatically by Gradle's preBuild; safe to run by hand.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CRATE_DIR="$ROOT/rust/majsoul-jni"
UPSTREAM_DIR="$ROOT/external/MajsoulMax-rs"
# Gradle owns this directory and registers it as a jniLibs source dir; the other
# two payload scripts write to app/src/main/jniLibs instead, which Gradle also
# reads but does not manage.
JNI_LIBS_DIR="${JNI_LIBS_DIR:-$ROOT/app/build/generated/jniLibs}"
TARGET_ABIS="${TARGET_ABIS:-arm64-v8a armeabi-v7a}"
PROFILE="${RUST_PROFILE:-release}"

die() { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }
info() { printf '\033[36m==>\033[0m %s\n' "$*"; }

[[ -f "$UPSTREAM_DIR/Cargo.toml" ]] || die \
  "MajsoulMax-rs submodule is missing. Run: git submodule update --init --recursive"

command -v cargo >/dev/null || die "cargo not found — install Rust 1.85 or newer"
command -v protoc >/dev/null || die "protoc not found — upstream's build.rs needs it"

if ! cargo ndk --version >/dev/null 2>&1; then
  die "cargo-ndk not found — install it with: cargo install cargo-ndk"
fi

# cargo-ndk finds the NDK through these, in order.
if [[ -z "${ANDROID_NDK_HOME:-}${ANDROID_NDK_ROOT:-}${NDK_HOME:-}" ]]; then
  die "set ANDROID_NDK_HOME (or ANDROID_NDK_ROOT) to your NDK path"
fi
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-$NDK_HOME}}"

export ANDROID_NDK_HOME="$NDK"
export ANDROID_NDK_ROOT="$NDK"

# Map Android ABI -> Rust target triple.
declare -A RUST_TARGET=(
  [arm64-v8a]=aarch64-linux-android
  [armeabi-v7a]=armv7-linux-androideabi
  [x86_64]=x86_64-linux-android
  [x86]=i686-linux-android
)

mkdir -p "$JNI_LIBS_DIR"

# One cargo invocation per ABI, rather than one invocation with several -t flags.
# aws-lc-sys (via hudsucker's rustls stack) compiles C through the `cmake` crate,
# and the NDK toolchain file reads ANDROID_ABI from the environment - a single
# invocation would build every target against whichever ABI happened to be set.
for abi in $TARGET_ABIS; do
  triple="${RUST_TARGET[$abi]:-}"
  [[ -n "$triple" ]] || die "unknown ABI '$abi'"

  info "ensuring rust target $triple"
  rustup target add "$triple" >/dev/null 2>&1 || true

  info "building libmajsoulmax.so for $abi ($triple)"
  (
    cd "$CRATE_DIR"
    export ANDROID_ABI="$abi"
    export ANDROID_PLATFORM="android-26"
    export CMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
    cargo ndk -t "$abi" --platform 26 -o "$JNI_LIBS_DIR" build --profile "$PROFILE"
  )

  out="$JNI_LIBS_DIR/$abi/libmajsoulmax.so"
  [[ -f "$out" ]] || die "expected $out to exist after the build"
  info "$(printf '%-12s' "$abi") $(du -h "$out" | cut -f1)"
done

info "done"
