#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GO_DIR="$ROOT_DIR/app/src/main/go/tlsclient"
JNI_LIB_DIR="$ROOT_DIR/app/src/main/jniLibs/arm64-v8a"
CPP_DIR="$ROOT_DIR/app/src/main/cpp"

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/workspaces/.android-sdk}"
NDK_HOME="${NDK_HOME:-$ANDROID_SDK_ROOT/ndk/26.3.11579264}"
API_LEVEL="${ANDROID_API_LEVEL:-26}"
TOOLCHAIN="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"

export CGO_ENABLED=1
export GOOS=android
export GOARCH=arm64
export CC="$TOOLCHAIN/bin/aarch64-linux-android${API_LEVEL}-clang"
export CXX="$TOOLCHAIN/bin/aarch64-linux-android${API_LEVEL}-clang++"

if [[ ! -x "$CC" ]]; then
  echo "Android NDK compiler not found: $CC" >&2
  echo "Install NDK 26.3.11579264 or set NDK_HOME to your Android NDK path." >&2
  exit 1
fi

mkdir -p "$JNI_LIB_DIR" "$CPP_DIR"

cd "$GO_DIR"
go mod tidy
go build -buildmode=c-shared -trimpath -ldflags="-s -w" -o "$JNI_LIB_DIR/libtlsclient.so" .
cp "$JNI_LIB_DIR/libtlsclient.h" "$CPP_DIR/libtlsclient.h"
rm -f "$JNI_LIB_DIR/libtlsclient.h"

echo "Built $JNI_LIB_DIR/libtlsclient.so"
echo "Copied generated header to $CPP_DIR/libtlsclient.h"
