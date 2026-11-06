#!/usr/bin/env bash
# 独立性检查：App 工程不得依赖外部仓库/父目录/旧 CVE 项目。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT"

FAIL=0
check() {
  local name="$1"; shift
  if "$@" >/tmp/standalone.out 2>&1; then
    echo "  [OK] $name"
  else
    echo "  [FAIL] $name"; sed -n '1,6p' /tmp/standalone.out | sed 's/^/        /'; FAIL=$((FAIL+1))
  fi
}
for f in gradlew settings.gradle.kts build.gradle.kts app/build.gradle.kts \
         native/privd.c app/src/main/assets/bin/arm64-v8a/privd \
         knowledge/report.json scripts/build-app.sh scripts/install.sh README.md; do
  check "存在 $f" test -f "$f"
done

echo "== 外部路径/仓库引用 =="
if grep -RIn --exclude-dir=.gradle-home --exclude-dir=.android-home --exclude-dir=build \
    --exclude-dir=.gradle --exclude-dir=.kotlin --exclude='*.png' --exclude='*.apk' --exclude='verify-standalone.sh' \
    -E "\.\./CVE-|/workspace/CVE-|CVE-2026-98260-Xiaomi-17-Pro-Max|privacy-radar/" "$ROOT" >/tmp/standalone-refs.out 2>&1; then
  echo "  [FAIL] 仍存在外部引用："; cat /tmp/standalone-refs.out | sed 's/^/        /'; FAIL=$((FAIL+1))
else
  echo "  [OK] 无外部仓库/父目录引用"
fi

echo "== 本地构建（assembleDebug）=="
if ./scripts/build-app.sh :app:assembleDebug >/tmp/standalone-build.out 2>&1; then
  echo "  [OK] 独立构建通过"
else
  echo "  [FAIL] 构建失败"; tail -20 /tmp/standalone-build.out | sed 's/^/        /'; FAIL=$((FAIL+1))
fi

echo
echo "结果：FAIL=$FAIL"
exit $((FAIL > 0 ? 1 : 0))
