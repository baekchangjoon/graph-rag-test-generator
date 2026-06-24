#!/usr/bin/env bash
# graph-diff.sh — graph.json set-동등 비교 (REQ-P003)
#
# Usage:
#   ./graph-diff.sh <graph-a.json> <graph-b.json>
#
# Exit code:
#   0 = EQUIVALENT (set-동등)
#   1 = NON-EQUIVALENT (집합 차이 있음)
#   2 = 사용법 오류
#
# 전제: graph-rag-builder 모듈이 빌드된 상태여야 한다.
#   ./gradlew :graph-rag-builder:testClasses

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <graph-a.json> <graph-b.json>" >&2
  exit 2
fi

GRAPH_A="$1"
GRAPH_B="$2"

if [[ ! -f "$GRAPH_A" ]]; then
  echo "File not found: $GRAPH_A" >&2
  exit 2
fi
if [[ ! -f "$GRAPH_B" ]]; then
  echo "File not found: $GRAPH_B" >&2
  exit 2
fi

# ─── Classpath 조립 ──────────────────────────────────────────────────────
# builder testClasses + 의존성 (Jackson 포함)
BUILDER_CLASSES="$REPO_ROOT/graph-rag-builder/build/classes/java/test"
MAIN_CLASSES="$REPO_ROOT/graph-rag-builder/build/classes/java/main"
SHARED_CLASSES="$REPO_ROOT/shared-model/build/classes/java/main"

# 의존성 jar: gradlew printRuntimeClasspath 로 뽑거나 build/libs 를 사용
# 간단하게 gradle test classpath 캐시를 활용한다
DEPS_CLASSPATH="$(
  "$REPO_ROOT/gradlew" -q -p "$REPO_ROOT" \
    :graph-rag-builder:printTestRuntimeClasspath 2>/dev/null \
    || echo ""
)"

if [[ -z "$DEPS_CLASSPATH" ]]; then
  # fallback: lib 디렉터리에서 Jackson 찾기
  DEPS_CLASSPATH="$(find "$HOME/.gradle/caches" -name "jackson-databind-*.jar" 2>/dev/null | head -1)"
  DEPS_CLASSPATH="$DEPS_CLASSPATH:$(find "$HOME/.gradle/caches" -name "jackson-core-*.jar" 2>/dev/null | head -1)"
  DEPS_CLASSPATH="$DEPS_CLASSPATH:$(find "$HOME/.gradle/caches" -name "jackson-datatype-jsr310-*.jar" 2>/dev/null | head -1)"
fi

CP="$BUILDER_CLASSES:$MAIN_CLASSES:$SHARED_CLASSES:$DEPS_CLASSPATH"

# ─── Java 17+ 선택 (클래스 파일 버전 61.0 = Java 17 요구) ────────────────
# JAVA_HOME이 Java 17+이면 그대로, 아니면 알려진 위치에서 찾는다.
JAVA_BIN="java"
_cur_ver="$(java -version 2>&1 | awk -F'"' '/version/{print $2}' | cut -d. -f1)"
if [[ "${_cur_ver:-0}" -lt 17 ]]; then
  # macOS: JavaVirtualMachines에서 17+ JDK 탐색
  for _jvm in \
      "$HOME/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home/bin/java" \
      "$HOME/Library/Java/JavaVirtualMachines/corretto-17.0.12/Contents/Home/bin/java" \
      "/usr/local/opt/openjdk@17/bin/java" \
      "/usr/lib/jvm/java-17-openjdk-amd64/bin/java"; do
    if [[ -x "$_jvm" ]]; then
      JAVA_BIN="$_jvm"
      break
    fi
  done
fi

# ─── 실행 ────────────────────────────────────────────────────────────────
exec "$JAVA_BIN" -cp "$CP" \
  io.graphrag.builder.parallel.GraphSetEquivDiffTool \
  "$GRAPH_A" "$GRAPH_B"
