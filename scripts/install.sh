#!/usr/bin/env bash
# 构建 + 以 root 安装 + 启动 PrivacyProbe（MIUI 不允许 adb install）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

TASK="${1:-assembleRelease}"
"$SCRIPT_DIR/build-app.sh" "$TASK" "${@:2}"

APK="$APP_DIR/app/build/outputs/apk/release/app-release.apk"
# 允许显式传 debug
if [[ "$TASK" == *"Debug"* ]]; then APK="$APP_DIR/app/build/outputs/apk/debug/app-debug.apk"; fi
SERIAL="${ANDROID_SERIAL:-}"
ADB=(adb)
[[ -n "$SERIAL" ]] && ADB=(adb -s "$SERIAL")

OLD_PKG="dev.popsicle.privradar"
if "${ADB[@]}" shell pm list packages 2>/dev/null | grep -q "$OLD_PKG"; then
  echo "==> 清理旧包 $OLD_PKG"
  "${ADB[@]}" shell "su -c 'pm uninstall $OLD_PKG'" >/dev/null 2>&1 || true
fi
if "${ADB[@]}" shell "su -c 'strings /data/adb/ksu/.allowlist 2>/dev/null'" 2>/dev/null | grep -q "$OLD_PKG"; then
  echo "WARNING: KernelSU allowlist 仍含旧包 $OLD_PKG（旧 uid 可能被复用后继承 root）。"
  echo "         请打开 KernelSU 管理器 → 超级用户 → 删除/关闭旧「隐私雷达」条目。"
fi

"${ADB[@]}" push "$APK" /data/local/tmp/privprobe.apk >/dev/null
"${ADB[@]}" shell "su -c 'pm install -r /data/local/tmp/privprobe.apk'"
"${ADB[@]}" shell am start -n dev.popsicle.privprobe/.MainActivity >/dev/null
echo "installed & launched"
