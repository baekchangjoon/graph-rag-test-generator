#!/usr/bin/env bash
# v3-overhead.sh — V3(b) per-request 오버헤드 측정 (REQ-005)
#
# OTel-scope/traceId 경로(PjacocoOtelScopeClient)를 사용해:
#   ① 제어 엔드포인트 flush 왕복 지연 100회 측정 → 평균 ms (임계: < 5ms)
#   ② 60-요청 1엔드포인트 벽시계: per-request 격리(traceparent+flush+load) vs baseline(단순 요청)
#      → 증가율 % (임계: < 10%)
#   ③ .exec 파일 개수 · 총 바이트 보고 (경고 기준: 없음 — 수치만 보고)
#
# JUnit 하니스(V3OverheadPoc.java)가 실제 측정·판정을 수행한다.
# 이 스크립트는 thin launcher다.
#
# 직접 실행:
#   bash e2e/poc-fanout/v3-overhead.sh
#
# 또는 JUnit만:
#   POC_FANOUT_E2E=1 ./gradlew :graph-rag-builder:test --tests '*V3OverheadPoc*' \
#     -Dpjacoco.agent.jar=$(e2e/poc-fanout/install-pjacoco.sh | tail -1)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT_DIR="$REPO_ROOT/e2e/poc-fanout"

echo "=== V3(b) per-request 오버헤드 측정 (REQ-005) ==="
echo "Repo root: $REPO_ROOT"

# pjacoco agent jar 확인/빌드
echo "[v3-overhead] Installing/finding pjacoco agent..."
PJACOCO_JAR="$("$SCRIPT_DIR/install-pjacoco.sh" | tail -1)"
echo "[v3-overhead] pjacoco agent: $PJACOCO_JAR"

# OTel jar 확인
OTEL_JAR="${OTEL_JAR:-$HOME/github_tainted-spring/tainted-spring-platform/jacoco/opentelemetry-javaagent.jar}"
if [[ ! -f "$OTEL_JAR" ]]; then
    echo "[v3-overhead] ERROR: OTel javaagent not found at $OTEL_JAR" >&2
    echo "[v3-overhead] Set OTEL_JAR env var to the OTel javaagent path" >&2
    exit 1
fi
echo "[v3-overhead] OTel agent: $OTEL_JAR"

echo ""
echo "[v3-overhead] Running V3OverheadPoc JUnit test..."
echo "[v3-overhead] Measurements:"
echo "  ① flush 왕복 지연 100회 평균 ms (임계: < 5ms)"
echo "  ② 60-요청 벽시계 증가율 % vs baseline (임계: < 10%)"
echo "  ③ .exec 개수 · 총 바이트"
echo ""

cd "$REPO_ROOT"
POC_FANOUT_E2E=1 \
OTEL_JAR="$OTEL_JAR" \
./gradlew :graph-rag-builder:test \
    --tests '*V3OverheadPoc*' \
    -Dpjacoco.agent.jar="$PJACOCO_JAR" \
    --info \
    2>&1 | tee /tmp/v3-overhead.log

EXIT_CODE=${PIPESTATUS[0]}

echo ""
echo "=== V3(b) 측정 완료 (gradle exit=$EXIT_CODE) ==="
echo "Full log: /tmp/v3-overhead.log"
echo ""
echo "=== 결과 요약 ==="
grep -E "OVERHEAD|round-trip|wall-clock|exec count|PASS|FAIL|REQ-005" /tmp/v3-overhead.log || true
exit $EXIT_CODE
