#!/usr/bin/env bash
# v3-otel-scope-equivalence.sh — V3(b) 조사: OTel-scope traceId 경로에서 JwtAuthenticationFilter probe 캡처 가설 검증
#
# 배경 (Task 4 결과):
#   - pjacoco baggage 경로는 pre-servlet JwtAuthenticationFilter probes(4개)를 drop → REQ-004 FAIL
#   - 가설: OTel servlet 계측은 filter chain 전체를 감싸므로, traceId scope가 JwtFilter probe를 포함할 수 있다
#
# 실행 방법:
#   bash e2e/poc-fanout/v3-otel-scope-equivalence.sh
#   (혹은 JUnit 직접 실행)
#   POC_FANOUT_E2E=1 ./gradlew :graph-rag-builder:test --tests '*V3OtelScopeEquivalenceProbe*' \
#     -Dpjacoco.agent.jar=$(e2e/poc-fanout/install-pjacoco.sh | tail -1)
#
# 환경 변수:
#   PETCLINIC_DIR  — petclinic repo 경로 (기본: ~/github_spring-petclinic/spring-petclinic)
#   PETCLINIC_JAVA — JDK 17 홈 (기본: /usr/libexec/java_home -v 17)
#   OTEL_JAR       — OTel javaagent jar 경로 (기본: ~/github_tainted-spring/.../opentelemetry-javaagent.jar)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT_DIR="$REPO_ROOT/e2e/poc-fanout"

echo "=== V3(b) OTel-scope traceId probe investigation ==="
echo "Repo root: $REPO_ROOT"

# pjacoco agent jar 확인/빌드
echo "[v3-otel] Installing/finding pjacoco agent..."
PJACOCO_JAR="$("$SCRIPT_DIR/install-pjacoco.sh" | tail -1)"
echo "[v3-otel] pjacoco agent: $PJACOCO_JAR"

# OTel jar 확인
OTEL_JAR="${OTEL_JAR:-$HOME/github_tainted-spring/tainted-spring-platform/jacoco/opentelemetry-javaagent.jar}"
if [[ ! -f "$OTEL_JAR" ]]; then
    echo "[v3-otel] ERROR: OTel javaagent not found at $OTEL_JAR" >&2
    echo "[v3-otel] Set OTEL_JAR env var to the OTel javaagent path" >&2
    exit 1
fi
echo "[v3-otel] OTel agent: $OTEL_JAR"

echo ""
echo "[v3-otel] Running V3OtelScopeEquivalenceProbe JUnit test..."
echo "[v3-otel] Expected: prints VERDICT line with CONFIRMED or REFUTED"
echo ""

cd "$REPO_ROOT"
POC_FANOUT_E2E=1 \
OTEL_JAR="$OTEL_JAR" \
./gradlew :graph-rag-builder:test \
    --tests '*V3OtelScopeEquivalenceProbe*' \
    -Dpjacoco.agent.jar="$PJACOCO_JAR" \
    --info \
    2>&1 | tee /tmp/v3-otel-scope-probe.log

EXIT_CODE=${PIPESTATUS[0]}

echo ""
echo "=== V3(b) probe complete (gradle exit=$EXIT_CODE) ==="
echo "Full log: /tmp/v3-otel-scope-probe.log"
grep -E "VERDICT|HYPOTHESIS|vanilla set|otelScope set|JwtAuth|intersection" /tmp/v3-otel-scope-probe.log || true
exit $EXIT_CODE
