#!/usr/bin/env bash
# 跑全部模块的测试。只需 JDK 17，不需要 Maven/Gradle。
#
# 用法：
#   bash run-tests.sh             # 自动下载 JUnit（首次需要网络）
#   bash run-tests.sh --offline   # 使用各模块 lib/ 里已有的 jar

set -uo pipefail
cd "$(dirname "$0")"

MODULES=("rrule" "event-layout")
ARGS=("$@")
failed=()

for module in "${MODULES[@]}"; do
  echo
  echo "════════════════════════════════════════"
  echo "  $module"
  echo "════════════════════════════════════════"
  if ! bash "$module/run-tests.sh" "${ARGS[@]:-}"; then
    failed+=("$module")
  fi
done

echo
if [ ${#failed[@]} -eq 0 ]; then
  echo "✅ 全部模块测试通过"
else
  echo "❌ 失败的模块：${failed[*]}"
  exit 1
fi
