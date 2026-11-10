#!/usr/bin/env bash
# Builds cloudflared from source for Android and drops it into the app as a "native library",
# so it ends up in the APK and can be executed from the app's nativeLibraryDir.
#
# Needs: git, Go (any recent version; the exact toolchain in cloudflared's go.mod is downloaded),
# and the Android NDK (ANDROID_NDK_HOME, or ANDROID_NDK_LATEST_HOME on GitHub runners).
# cgo with the NDK is required: without it, Go can't resolve hostnames on Android.
#
# Usage: scripts/build-cloudflared.sh [abi ...]   (default: arm64-v8a armeabi-v7a)
set -euo pipefail

CLOUDFLARED_VERSION="${CLOUDFLARED_VERSION:-$(cat "$(dirname "$0")/cloudflared-version.txt")}"
API_LEVEL=28 # matches minSdk
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/jniLibs"
WORK="${CLOUDFLARED_WORK_DIR:-$ROOT/build/cloudflared-src}"

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  echo "error: set ANDROID_NDK_HOME to an Android NDK install" >&2
  exit 1
fi
TOOLCHAIN="$(echo "$NDK"/toolchains/llvm/prebuilt/*/bin)"

if [ ! -d "$WORK/.git" ] || [ "$(git -C "$WORK" describe --tags 2>/dev/null)" != "$CLOUDFLARED_VERSION" ]; then
  rm -rf "$WORK"
  git clone --quiet --depth 1 --branch "$CLOUDFLARED_VERSION" https://github.com/cloudflare/cloudflared "$WORK"
fi

abis=("$@")
[ ${#abis[@]} -eq 0 ] && abis=(arm64-v8a armeabi-v7a)

cd "$WORK"
GO_MOD_VERSION="$(sed -n 's/^go \([0-9.]*\)$/\1/p' go.mod)"
export GOTOOLCHAIN="go$GO_MOD_VERSION" GOFIPS140=off CGO_ENABLED=1 GOOS=android

for abi in "${abis[@]}"; do
  case "$abi" in
    arm64-v8a)   arch=arm64; cc="aarch64-linux-android$API_LEVEL-clang"; unset GOARM ;;
    armeabi-v7a) arch=arm;   cc="armv7a-linux-androideabi$API_LEVEL-clang"; export GOARM=7 ;;
    x86_64)      arch=amd64; cc="x86_64-linux-android$API_LEVEL-clang"; unset GOARM ;;
    *) echo "error: unsupported ABI $abi" >&2; exit 1 ;;
  esac
  echo "Building cloudflared $CLOUDFLARED_VERSION for $abi"
  mkdir -p "$OUT/$abi"
  GOARCH="$arch" CC="$TOOLCHAIN/$cc" go build -mod=readonly -trimpath \
    -ldflags="-s -w -X main.Version=$CLOUDFLARED_VERSION -X main.BuildTime=android" \
    -o "$OUT/$abi/libcloudflared.so" ./cmd/cloudflared
  ls -l "$OUT/$abi/libcloudflared.so"
done
