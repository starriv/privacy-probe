#!/usr/bin/env bash
# 构建 privd（aarch64，仅依赖 bionic libc），产物直接落到 App assets：
#   app/src/main/assets/bin/arm64-v8a/privd
#
# 依赖：Android NDK 28（$ANDROID_HOME/ndk/28.0.12674087）。
# 该二进制随仓库交付（预编译），此脚本用于可复现构建：
#   ./scripts/build-native.sh
# 可选环境变量：
#   ANDROID_NDK_HOME  覆盖 NDK 路径
#   PRIVD_API         Android API level（默认 31）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
NATIVE_DIR="$APP_DIR/native"

API="${PRIVD_API:-31}"
OUT_DIR="$APP_DIR/app/src/main/assets/bin/arm64-v8a"
OUT="$OUT_DIR/privd"

ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/SDK}"
NDK="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.0.12674087}"
if [[ ! -d "$NDK" ]]; then
  echo "ERROR: NDK not found: $NDK" >&2
  exit 1
fi

# 主机 tag（darwin-x86_64 / linux-x86_64）
HOST_TAG="$(ls "$NDK/toolchains/llvm/prebuilt" | head -1)"
BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
CLANG="$BIN/aarch64-linux-android${API}-clang"
STRIP="$BIN/llvm-strip"
if [[ ! -x "$CLANG" ]]; then
  echo "ERROR: clang not found: $CLANG" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
echo "==> building privd (aarch64, API $API)"
"$CLANG" \
  -O2 -g0 -Wall -Wextra -Wno-unused-parameter \
  -fPIE -pie -fno-omit-frame-pointer \
  -D_GNU_SOURCE \
  --target=aarch64-linux-android${API} \
  -o "$OUT" \
  "$NATIVE_DIR/privd.c" "$NATIVE_DIR/netcap.c" "$NATIVE_DIR/tracefs.c" "$NATIVE_DIR/util.c" \
  -pthread

if [[ -x "$STRIP" ]]; then
  "$STRIP" --strip-unneeded "$OUT" || true
fi
chmod 755 "$OUT"

echo "==> ok: $OUT"
file "$OUT" 2>/dev/null || true
ls -l "$OUT"
