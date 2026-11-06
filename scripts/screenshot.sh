#!/usr/bin/env bash
# 截取 PrivacyProbe 关键页面到 privacy-probe/docs/screenshots/
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT="$APP_DIR/docs/screenshots"
mkdir -p "$OUT"

ADB=(adb)
[[ -n "${ANDROID_SERIAL:-}" ]] && ADB=(adb -s "$ANDROID_SERIAL")

wake() {
  "${ADB[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  "${ADB[@]}" shell wm dismiss-keyguard >/dev/null 2>&1 || true
}

shot() {
  local name="$1"
  "${ADB[@]}" shell screencap -p "/sdcard/$name.png"
  "${ADB[@]}" pull "/sdcard/$name.png" "$OUT/$name.png" >/dev/null
  echo "saved $OUT/$name.png"
}

wake
"${ADB[@]}" shell am start -n dev.popsicle.privprobe/.MainActivity >/dev/null
sleep 2
shot "ui-dashboard"
echo "提示：用 UI 进入大类详情/配置页后再执行 shot <name>（或手动截图）"
