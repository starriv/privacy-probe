#!/usr/bin/env bash
# PrivacyProbe 真机验收：root / 安装 / 服务 / 探针 / 数据库 / 旧包授权残留。
set -uo pipefail

ADB=(adb)
[[ -n "${ANDROID_SERIAL:-}" ]] && ADB=(adb -s "$ANDROID_SERIAL")
PKG="dev.popsicle.privprobe"
OLD_PKG="dev.popsicle.privradar"
OK=0; FAIL=0; WARN=0

check() {
  local name="$1"; shift
  if "$@" >/tmp/verify.out 2>&1; then
    echo "  [OK] $name"; OK=$((OK + 1))
  else
    echo "  [FAIL] $name"; sed -n '1,8p' /tmp/verify.out | sed 's/^/        /'; FAIL=$((FAIL + 1))
  fi
}
warn() { echo "  [WARN] $1"; WARN=$((WARN + 1)); }

echo "== 设备 =="
"${ADB[@]}" devices

echo "== root（KernelSU）=="
if "${ADB[@]}" shell "su -c 'id'" 2>/dev/null | grep -q 'uid=0'; then
  echo "  [OK] su -c id → uid=0"; OK=$((OK + 1))
else
  echo "  [FAIL] 无 root"; FAIL=$((FAIL + 1))
fi

echo "== 应用 =="
check "已安装 $PKG" "${ADB[@]}" shell pm path "$PKG"
VERSION=$("${ADB[@]}" shell dumpsys package "$PKG" 2>/dev/null | grep -m1 versionName | tr -d '\r')
[[ -n "$VERSION" ]] && echo "  [OK] $VERSION" && OK=$((OK + 1)) || { echo "  [FAIL] 无法读取版本"; FAIL=$((FAIL + 1)); }
OLD_PRESENT=$("${ADB[@]}" shell pm list packages 2>/dev/null | grep -c "$OLD_PKG" || true)
[[ "${OLD_PRESENT:-0}" -eq 0 ]] && echo "  [OK] 旧包未安装" && OK=$((OK + 1)) || { echo "  [FAIL] 旧包仍安装"; FAIL=$((FAIL + 1)); }
if "${ADB[@]}" shell "su -c 'strings /data/adb/ksu/.allowlist 2>/dev/null'" 2>/dev/null | grep -q "$OLD_PKG"; then
  warn "KernelSU allowlist 文件仍含旧包字符串 ${OLD_PKG}（需确认旧条目 allow_su=0；若仍授权请在 KSU 管理器删除）"
else
  echo "  [OK] KernelSU allowlist 无旧包"; OK=$((OK + 1))
fi

echo "== 服务 =="
SERVICE=$("${ADB[@]}" shell dumpsys activity services "$PKG" 2>/dev/null | grep -c "CollectorService" || true)
if [[ "${SERVICE:-0}" -gt 0 ]]; then echo "  [OK] CollectorService 已注册"; OK=$((OK + 1)); else warn "CollectorService 未运行（采集可能未开启）"; fi

echo "== 进程 =="
PRIVD=$("${ADB[@]}" shell ps -A 2>/dev/null | grep -c "privd-" || true)
[[ "${PRIVD:-0}" -gt 0 ]] && echo "  [OK] privd 运行中 ($PRIVD)" && OK=$((OK + 1)) || warn "privd 未运行（native 探针可能关闭）"

echo "== 数据库 =="
DB_LS=$("${ADB[@]}" shell "su -c 'ls /data/data/$PKG/databases/ 2>/dev/null'" | tr -d '\r')
if echo "$DB_LS" | grep -q "privprobe.db"; then echo "  [OK] privprobe.db 存在"; OK=$((OK + 1)); else echo "  [FAIL] privprobe.db 不存在"; FAIL=$((FAIL + 1)); fi
DB_SIZE=$("${ADB[@]}" shell "su -c 'du -sk /data/data/$PKG/databases 2>/dev/null | cut -f1'" | tr -d '\r')
echo "  databases: ${DB_SIZE:-0} KB"

echo "== 日志摘要 =="
"${ADB[@]}" logcat -d 2>/dev/null | grep -E "CollectorController|ProbeManager|NativeStreamProbe|UidResolver|RootShell" | tail -15

echo
echo "结果：OK=$OK FAIL=$FAIL WARN=$WARN"
exit $((FAIL > 0 ? 1 : 0))
