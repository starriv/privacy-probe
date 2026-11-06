#!/usr/bin/env bash
# 构建 PrivacyProbe APK。
#
# 独立项目：Gradle home / Android user home 全部落在项目目录内。
# 依赖缓存首次会从网络拉取（仓库已带一份 9.4.1 wrapper dist）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$APP_DIR"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/SDK}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# 1) JDK：优先仓库内/本机已有的 JDK 17/21/22，最后退回 Android Studio JBR。
find_java_home() {
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then echo "$JAVA_HOME"; return; fi
  local candidates=(
    "$HOME/.gradle/jdks/eclipse_adoptium-17-aarch64-os_x.2/jdk-17.0.19+10/Contents/Home"
    "$HOME/.gradle/jdks/jetbrains_s_r_o_-21-aarch64-os_x.2/jbrsdk_jcef-21.0.11-osx-aarch64-b1163.116/Contents/Home"
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
  )
  local c
  for c in "${candidates[@]}"; do
    if [[ -x "$c/bin/java" ]]; then echo "$c"; return; fi
  done
  echo ""
}
JAVA_HOME="$(find_java_home)"
if [[ -z "$JAVA_HOME" ]]; then
  echo "ERROR: no JDK 17+ found; set JAVA_HOME" >&2
  exit 1
fi
export JAVA_HOME

# Gradle / Android 缓存全部放在独立项目内部，便于整体拆分。
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT_DIR/.gradle-home}"
# 注意：只能设置 ANDROID_USER_HOME；AGP 9 会拒绝两个目录环境变量同时存在。
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$ROOT_DIR/.android-home}"
unset ANDROID_PREFS_ROOT
mkdir -p "$GRADLE_USER_HOME" "$ANDROID_USER_HOME"

TASK="${1:-assembleDebug}"
cd "$APP_DIR"
exec ./gradlew --no-daemon "$TASK" "${@:2}"
