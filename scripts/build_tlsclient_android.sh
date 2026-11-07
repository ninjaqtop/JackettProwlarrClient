#!/usr/bin/env bash
# Builds the Go TLS library (bogdanfinn/tls-client) from source as an Android
# c-shared library, one ABI at a time, with the NDK's clang.
#
#   scripts/build_tlsclient_android.sh [abi ...]      (default: arm64-v8a)
#
# Outputs per ABI:
#   app/src/main/jniLibs/<abi>/libtlsclient.so       (DT_SONAME libtlsclient.so, 16 KB pages)
#   app/src/main/cpp/generated/<abi>/libtlsclient.h  (cgo-generated header the JNI bridge includes)
#
# The JNI bridge (native-lib.cpp -> libtlsclientbridge.so) is then compiled by
# Gradle's externalNativeBuild (CMake) against these outputs.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GO_DIR="$ROOT_DIR/app/src/main/go/tlsclient"
JNI_ROOT="$ROOT_DIR/app/src/main/jniLibs"
GEN_ROOT="$ROOT_DIR/app/src/main/cpp/generated"

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/usr/local/lib/android/sdk}}"
NDK_VERSION="${NDK_VERSION:-27.0.12077973}"
NDK_HOME="${NDK_HOME:-$ANDROID_SDK_ROOT/ndk/$NDK_VERSION}"
API_LEVEL="${ANDROID_API_LEVEL:-26}"
TOOLCHAIN="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"

ABIS=("$@")
[[ ${#ABIS[@]} -eq 0 ]] && ABIS=(arm64-v8a)

PAGE_FLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"

cd "$GO_DIR"
go version
go mod download

for ABI in "${ABIS[@]}"; do
  unset GOARM
  case "$ABI" in
    arm64-v8a)   GOARCH=arm64; TRIPLE=aarch64-linux-android ;;
    armeabi-v7a) GOARCH=arm;   TRIPLE=armv7a-linux-androideabi; export GOARM=7 ;;
    x86_64)      GOARCH=amd64; TRIPLE=x86_64-linux-android ;;
    x86)         GOARCH=386;   TRIPLE=i686-linux-android ;;
    *) echo "unsupported ABI: $ABI" >&2; exit 1 ;;
  esac
  CC="$TOOLCHAIN/bin/${TRIPLE}${API_LEVEL}-clang"
  CXX="$TOOLCHAIN/bin/${TRIPLE}${API_LEVEL}-clang++"
  if [[ ! -x "$CC" ]]; then
    echo "Android NDK compiler not found: $CC (set NDK_HOME / NDK_VERSION)" >&2
    exit 1
  fi
  OUT_DIR="$JNI_ROOT/$ABI"
  HDR_DIR="$GEN_ROOT/$ABI"
  mkdir -p "$OUT_DIR" "$HDR_DIR"
  echo "== building libtlsclient.so for $ABI (GOARCH=$GOARCH, CC=$(basename "$CC"))"
  env CGO_ENABLED=1 GOOS=android GOARCH="$GOARCH" CC="$CC" CXX="$CXX" \
      CGO_LDFLAGS="$PAGE_FLAGS" \
      go build -buildmode=c-shared -trimpath -buildvcs=false \
        -ldflags="-s -w -extldflags '$PAGE_FLAGS -Wl,-soname,libtlsclient.so'" \
        -o "$OUT_DIR/libtlsclient.so" .
  mv "$OUT_DIR/libtlsclient.h" "$HDR_DIR/libtlsclient.h"
  ls -l "$OUT_DIR/libtlsclient.so" "$HDR_DIR/libtlsclient.h"
done
