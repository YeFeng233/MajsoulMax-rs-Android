#!/usr/bin/env bash
#
# Downloads prebuilt Meta (mihomo) kernels and installs them as
# app/src/main/jniLibs/<abi>/libmihomo.so.
#
# Shipping the kernel inside jniLibs is what makes it executable at runtime:
# that directory is the only place an app may exec from on modern Android, and
# the lib*.so name is required for the packager to extract it.
#
# Usage: ./scripts/fetch-mihomo.sh [version]     (default: latest release)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI_LIBS_DIR="${JNI_LIBS_DIR:-$ROOT/app/src/main/jniLibs}"
TARGET_ABIS="${TARGET_ABIS:-arm64-v8a armeabi-v7a}"
REPO="MetaCubeX/mihomo"
VERSION="${1:-${MIHOMO_VERSION:-latest}}"

die() { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }
info() { printf '\033[36m==>\033[0m %s\n' "$*"; }

command -v curl >/dev/null || die "curl is required"
command -v jq >/dev/null || die "jq is required"
command -v gzip >/dev/null || die "gzip is required"

# Android ABI -> substring of the release asset name.
declare -A ASSET_ARCH=(
  [arm64-v8a]=android-arm64
  [armeabi-v7a]=android-armv7
  [x86_64]=android-amd64
  [x86]=android-386
)

AUTH=()
[[ -n "${GITHUB_TOKEN:-}" ]] && AUTH=(-H "Authorization: Bearer $GITHUB_TOKEN")

if [[ "$VERSION" == "latest" ]]; then
  API="https://api.github.com/repos/$REPO/releases/latest"
else
  API="https://api.github.com/repos/$REPO/releases/tags/$VERSION"
fi

info "querying $API"
RELEASE_JSON="$(curl -fsSL "${AUTH[@]}" -H 'Accept: application/vnd.github+json' "$API")" \
  || die "cannot reach the GitHub releases API"

TAG="$(jq -r '.tag_name' <<<"$RELEASE_JSON")"
[[ -n "$TAG" && "$TAG" != "null" ]] || die "no release found for '$VERSION'"
info "using mihomo $TAG"

mkdir -p "$JNI_LIBS_DIR"

for abi in $TARGET_ABIS; do
  arch="${ASSET_ARCH[$abi]:-}"
  [[ -n "$arch" ]] || die "unknown ABI '$abi'"

  # Prefer the plain build over "-compatible"/"-go1xx" variants.
  url="$(jq -r --arg a "$arch" '
    [ .assets[]
      | select(.name | test("^mihomo-" + $a + "-v[0-9].*\\.gz$"))
      | select(.name | test("compatible|go1") | not)
    ] | first | .browser_download_url // empty' <<<"$RELEASE_JSON")"

  if [[ -z "$url" ]]; then
    # Fall back to any asset for this architecture.
    url="$(jq -r --arg a "$arch" '
      [ .assets[] | select(.name | test("^mihomo-" + $a + ".*\\.gz$")) ]
      | first | .browser_download_url // empty' <<<"$RELEASE_JSON")"
  fi
  if [[ -z "$url" ]]; then
    printf 'assets published in %s:\n' "$TAG" >&2
    jq -r '.assets[].name' <<<"$RELEASE_JSON" | sed 's/^/  /' >&2
    die "no release asset matching '$arch' in $TAG"
  fi

  dest_dir="$JNI_LIBS_DIR/$abi"
  mkdir -p "$dest_dir"
  info "$(printf '%-12s' "$abi") $(basename "$url")"

  tmp="$(mktemp)"
  curl -fsSL "$url" -o "$tmp" || die "download failed: $url"
  gzip -dc "$tmp" > "$dest_dir/libmihomo.so" || die "cannot decompress $url"
  rm -f "$tmp"
  chmod 0755 "$dest_dir/libmihomo.so"
done

printf '%s\n' "$TAG" > "$JNI_LIBS_DIR/.mihomo-version"
info "installed mihomo $TAG for: $TARGET_ABIS"
