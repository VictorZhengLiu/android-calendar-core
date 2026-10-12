#!/usr/bin/env bash
# 不依赖 Maven/Gradle 的测试脚本：只要有 JDK 就能编译并运行全部单元测试。
#
# 用法：
#   bash run-tests.sh            # 自动下载 JUnit（首次需要网络）
#   bash run-tests.sh --offline  # 使用 lib/ 里已有的 jar

set -euo pipefail
cd "$(dirname "$0")"

LIB=lib
JUNIT="$LIB/junit-4.13.2.jar"
HAMCREST="$LIB/hamcrest-core-1.3.jar"
OUT=build/classes
TEST_OUT=build/test-classes

mkdir -p "$LIB"

if [ ! -f "$JUNIT" ] || [ ! -f "$HAMCREST" ]; then
  if [ "${1:-}" = "--offline" ]; then
    echo "缺少 $LIB 下的 JUnit jar，且指定了 --offline" >&2
    exit 1
  fi
  echo "▶ 下载 JUnit …"
  curl -sSL -o "$JUNIT" https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar
  curl -sSL -o "$HAMCREST" https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar
fi

CP="$JUNIT:$HAMCREST"

rm -rf "$OUT" "$TEST_OUT"
mkdir -p "$OUT" "$TEST_OUT"

echo "▶ 编译主代码 …"
javac -d "$OUT" -encoding UTF-8 src/main/java/com/liuzheng/rrule/*.java

echo "▶ 编译测试 …"
javac -cp "$OUT:$CP" -d "$TEST_OUT" -encoding UTF-8 src/test/java/com/liuzheng/rrule/*.java

echo "▶ 运行测试 …"
java -cp "$OUT:$TEST_OUT:$CP" org.junit.runner.JUnitCore com.liuzheng.rrule.RRuleTest
