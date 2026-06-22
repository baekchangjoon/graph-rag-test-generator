#!/usr/bin/env bash
# v4-distributed-attribution.sh — V4 분산 트레이스 귀속 (REQ-006 단일 JVM + REQ-007 멀티 JVM)
#
# 재현 환경: tainted-spring diary(OTel+pjacoco:6310) → Kafka diary.created → mindgraph(OTel+pjacoco:6311)
# 동일 traceId가 diary in-process coverage(REQ-006)와 mindgraph consumer coverage(REQ-007) 양쪽에 귀속됨을 증명.
#
# 사전 조건:
#   - pjacoco jacocoagent-parallel.jar가 ~/github_parallel-per-test-coverage/.../agent/build/libs/ 에 존재
#   - Docker Desktop 실행 중
#   - tainted-spring 이미지가 빌드되어 있을 것 (docker-compose build)
#
# 실행:
#   cd ~/github_tainted-spring/tainted-spring-platform
#   bash <path>/v4-distributed-attribution.sh
#
# 환경 변수:
#   TAINTED_PLATFORM  — tainted-spring-platform 경로 (기본: ~/github_tainted-spring/tainted-spring-platform)
#   TRACE_ID          — 32-hex traceId (기본: v4poc0000000000000000000000000001)
#   KAFKA_WAIT_SEC    — mindgraph Kafka 소비 대기 시간 초 (기본: 8)

set -euo pipefail

TAINTED_PLATFORM="${TAINTED_PLATFORM:-$HOME/github_tainted-spring/tainted-spring-platform}"
TRACE_ID="${TRACE_ID:-76340000000000000000000000000001}"
KAFKA_WAIT_SEC="${KAFKA_WAIT_SEC:-8}"

DIARY_PORT=8082
DIARY_CTL_PORT=6310
MINDGRAPH_CTL_PORT=6311

DIARY_EXEC_HOST="$TAINTED_PLATFORM/coverage/diary/$TRACE_ID.exec"
MINDGRAPH_EXEC_HOST="$TAINTED_PLATFORM/coverage/mindgraph/$TRACE_ID.exec"

# ── 색상 출력 ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

pass() { echo -e "${GREEN}[PASS]${NC} $*"; }
fail() { echo -e "${RED}[FAIL]${NC} $*"; }
info() { echo -e "${YELLOW}[INFO]${NC} $*"; }

# ── 정리 함수 ──────────────────────────────────────────────────────────────────
teardown() {
    info "Stopping tainted-spring compose services..."
    cd "$TAINTED_PLATFORM"
    docker compose \
        -f docker-compose.yml \
        -f docker-compose.pjacoco-otel.yml \
        down --remove-orphans 2>&1 | tail -5 || true
    info "Teardown complete."
}
trap teardown EXIT

echo "=== V4 분산 트레이스 귀속 PoC (REQ-006 + REQ-007) ==="
echo "Platform: $TAINTED_PLATFORM"
echo "traceId:  $TRACE_ID"

# ── Step 1: pjacoco jar 확인 ───────────────────────────────────────────────────
PJACOCO_JAR="$HOME/github_parallel-per-test-coverage/parallel-per-test-coverage/agent/build/libs/jacocoagent-parallel.jar"
if [[ ! -f "$PJACOCO_JAR" ]]; then
    fail "pjacoco jar not found at $PJACOCO_JAR"
    fail "Build it: cd ~/github_parallel-per-test-coverage/parallel-per-test-coverage && JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home ./gradlew :agent:shadowJar"
    exit 1
fi
info "pjacoco jar: $PJACOCO_JAR ($(du -sh "$PJACOCO_JAR" | cut -f1))"

# ── Step 2: coverage 디렉터리 준비 ────────────────────────────────────────────
mkdir -p "$TAINTED_PLATFORM/coverage/diary"
mkdir -p "$TAINTED_PLATFORM/coverage/mindgraph"

# 이전 실행 stale exec 제거
rm -f "$DIARY_EXEC_HOST" "$TAINTED_PLATFORM/coverage/diary/$TRACE_ID.json"
rm -f "$MINDGRAPH_EXEC_HOST" "$TAINTED_PLATFORM/coverage/mindgraph/$TRACE_ID.json"
info "Cleaned stale exec files for traceId=$TRACE_ID"

# ── Step 3: Docker Compose 기동 ────────────────────────────────────────────────
info "Starting tainted-spring services (zookeeper kafka postgres redis auth-user diary mindgraph)..."
cd "$TAINTED_PLATFORM"
docker compose \
    -f docker-compose.yml \
    -f docker-compose.pjacoco-otel.yml \
    up -d zookeeper kafka postgres redis auth-user diary mindgraph 2>&1 | tail -20

# ── Step 4: diary [pjacoco] agent installed 확인 ──────────────────────────────
info "Waiting for diary [pjacoco] agent installed..."
DEADLINE=$(( $(date +%s) + 120 ))
while true; do
    if docker compose -f docker-compose.yml -f docker-compose.pjacoco-otel.yml logs diary 2>&1 | grep -q "\[pjacoco\] agent installed"; then
        pass "diary: [pjacoco] agent installed"
        break
    fi
    if [[ $(date +%s) -gt $DEADLINE ]]; then
        fail "diary did not log [pjacoco] agent installed within 120s"
        docker compose -f docker-compose.yml -f docker-compose.pjacoco-otel.yml logs diary 2>&1 | tail -30
        exit 1
    fi
    sleep 3
done

# ── Step 5: mindgraph [pjacoco] agent installed 확인 ──────────────────────────
info "Waiting for mindgraph [pjacoco] agent installed..."
DEADLINE=$(( $(date +%s) + 120 ))
while true; do
    if docker compose -f docker-compose.yml -f docker-compose.pjacoco-otel.yml logs mindgraph 2>&1 | grep -q "\[pjacoco\] agent installed"; then
        pass "mindgraph: [pjacoco] agent installed"
        break
    fi
    if [[ $(date +%s) -gt $DEADLINE ]]; then
        fail "mindgraph did not log [pjacoco] agent installed within 120s"
        docker compose -f docker-compose.yml -f docker-compose.pjacoco-otel.yml logs mindgraph 2>&1 | tail -30
        exit 1
    fi
    sleep 3
done

# ── Step 6: diary HTTP readiness 확인 ─────────────────────────────────────────
info "Waiting for diary HTTP readiness on port $DIARY_PORT..."
DEADLINE=$(( $(date +%s) + 120 ))
while true; do
    if curl -fs "http://localhost:$DIARY_PORT/actuator/health" >/dev/null 2>&1; then
        pass "diary HTTP ready on :$DIARY_PORT"
        break
    fi
    if [[ $(date +%s) -gt $DEADLINE ]]; then
        fail "diary HTTP did not become ready on :$DIARY_PORT within 120s"
        exit 1
    fi
    sleep 3
done

# ── Step 7: diary pjacoco 제어 엔드포인트 readiness 확인 ──────────────────────
info "Waiting for diary pjacoco control on :$DIARY_CTL_PORT..."
DEADLINE=$(( $(date +%s) + 60 ))
while true; do
    if curl -fs "http://localhost:$DIARY_CTL_PORT/__coverage__/test/list" >/dev/null 2>&1; then
        pass "diary pjacoco control ready on :$DIARY_CTL_PORT"
        break
    fi
    if [[ $(date +%s) -gt $DEADLINE ]]; then
        fail "diary pjacoco control did not become ready on :$DIARY_CTL_PORT"
        exit 1
    fi
    sleep 2
done

# ── Step 8: POST /internal/diaries with known traceId ─────────────────────────
TRACEPARENT="00-${TRACE_ID}-$(printf '%016x' 1)-01"
info "Sending POST /internal/diaries with traceparent=$TRACEPARENT"

HTTP_STATUS=$(curl -s -o /tmp/v4-diary-response.json -w "%{http_code}" \
    -X POST "http://localhost:$DIARY_PORT/internal/diaries" \
    -H 'Content-Type: application/json' \
    -H "traceparent: $TRACEPARENT" \
    -d '{"userId":"u1","title":"v4poc-hello","content":"v4poc-content","primaryEmotion":"joy","energyScore":5}')

if [[ "$HTTP_STATUS" -ge 200 && "$HTTP_STATUS" -lt 300 ]]; then
    pass "POST /internal/diaries → HTTP $HTTP_STATUS"
    cat /tmp/v4-diary-response.json 2>/dev/null || true
else
    fail "POST /internal/diaries → HTTP $HTTP_STATUS (expected 2xx)"
    cat /tmp/v4-diary-response.json 2>/dev/null || true
    exit 1
fi

# ── Step 9: mindgraph Kafka consumer 소비 대기 ────────────────────────────────
info "Waiting ${KAFKA_WAIT_SEC}s for mindgraph to consume diary.created Kafka event..."
sleep "$KAFKA_WAIT_SEC"

# mindgraph 로그에서 consumer 처리 확인 (선택적)
if docker compose -f docker-compose.yml -f docker-compose.pjacoco-otel.yml logs mindgraph 2>&1 | grep -qE "DiaryCreated|diary.*created|graphService|GraphService"; then
    pass "mindgraph Kafka consumer activity detected in logs"
else
    info "No explicit DiaryCreated log found in mindgraph — continuing (consumer may log differently)"
fi

# ── Step 10: diary coverage flush ─────────────────────────────────────────────
info "Flushing diary coverage store for traceId=$TRACE_ID..."
DIARY_FLUSH=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "http://localhost:$DIARY_CTL_PORT/__coverage__/test/stop?testId=$TRACE_ID&result=passed")
if [[ "$DIARY_FLUSH" -ge 200 && "$DIARY_FLUSH" -lt 300 ]]; then
    pass "diary flush → HTTP $DIARY_FLUSH"
else
    fail "diary flush → HTTP $DIARY_FLUSH"
    exit 1
fi

# ── Step 11: mindgraph coverage flush ─────────────────────────────────────────
info "Flushing mindgraph coverage store for traceId=$TRACE_ID..."
MG_FLUSH=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "http://localhost:$MINDGRAPH_CTL_PORT/__coverage__/test/stop?testId=$TRACE_ID&result=passed")
if [[ "$MG_FLUSH" -ge 200 && "$MG_FLUSH" -lt 300 ]]; then
    pass "mindgraph flush → HTTP $MG_FLUSH"
else
    fail "mindgraph flush → HTTP $MG_FLUSH"
    exit 1
fi

# ── Step 12: .exec 파일 생성 대기 ─────────────────────────────────────────────
info "Waiting for diary exec file at $DIARY_EXEC_HOST..."
DEADLINE=$(( $(date +%s) + 15 ))
while true; do
    if [[ -s "$DIARY_EXEC_HOST" ]]; then
        pass "diary exec created: $(du -sh "$DIARY_EXEC_HOST" | cut -f1)"
        break
    fi
    if [[ $(date +%s) -gt $DEADLINE ]]; then
        fail "diary exec not produced within 15s at $DIARY_EXEC_HOST"
        ls -la "$TAINTED_PLATFORM/coverage/diary/" || true
        exit 1
    fi
    sleep 1
done

info "Waiting for mindgraph exec file at $MINDGRAPH_EXEC_HOST..."
DEADLINE=$(( $(date +%s) + 15 ))
while true; do
    if [[ -s "$MINDGRAPH_EXEC_HOST" ]]; then
        pass "mindgraph exec created: $(du -sh "$MINDGRAPH_EXEC_HOST" | cut -f1)"
        break
    fi
    if [[ $(date +%s) -gt $DEADLINE ]]; then
        fail "mindgraph exec not produced within 15s at $MINDGRAPH_EXEC_HOST"
        ls -la "$TAINTED_PLATFORM/coverage/mindgraph/" || true
        exit 1
    fi
    sleep 1
done

# ── Step 13: JSON 메타 확인 ────────────────────────────────────────────────────
info "Checking sidecar JSON metadata..."
DIARY_JSON="$TAINTED_PLATFORM/coverage/diary/$TRACE_ID.json"
MINDGRAPH_JSON="$TAINTED_PLATFORM/coverage/mindgraph/$TRACE_ID.json"

if [[ -f "$DIARY_JSON" ]]; then
    DIARY_CLASS_COUNT=$(python3 -c "import json,sys; d=json.load(open('$DIARY_JSON')); print(d.get('classCount','?'))" 2>/dev/null || echo "?")
    info "diary.json: classCount=$DIARY_CLASS_COUNT"
else
    info "diary.json not yet available (exec may suffice)"
fi

if [[ -f "$MINDGRAPH_JSON" ]]; then
    MG_CLASS_COUNT=$(python3 -c "import json,sys; d=json.load(open('$MINDGRAPH_JSON')); print(d.get('classCount','?'))" 2>/dev/null || echo "?")
    info "mindgraph.json: classCount=$MG_CLASS_COUNT"
else
    info "mindgraph.json not yet available"
fi

# ── Step 14: ExecFileLoader로 probe 수 측정 ────────────────────────────────────
# JUnit 게이트(V4DistributedAttributionPoc)가 정식 측정을 수행.
# 여기서는 jacoco cli로 간단 확인.
JACOCOCLI=""
for candidate in \
    "$HOME/.m2/repository/org/jacoco/org.jacoco.cli/0.8.11/org.jacoco.cli-0.8.11-nodeps.jar" \
    "$HOME/.m2/repository/org/jacoco/org.jacoco.cli/0.8.12/org.jacoco.cli-0.8.12-nodeps.jar" \
    "$TAINTED_PLATFORM/jacoco/jacococli.jar"; do
    if [[ -f "$candidate" ]]; then
        JACOCOCLI="$candidate"
        break
    fi
done

if [[ -n "$JACOCOCLI" ]]; then
    info "Probing exec files with jacococli ($JACOCOCLI)..."
    echo "[diary exec probe count]"
    java -jar "$JACOCOCLI" execinfo "$DIARY_EXEC_HOST" 2>&1 | grep -E "Class:|Total" | head -10 || true
    echo "[mindgraph exec probe count]"
    java -jar "$JACOCOCLI" execinfo "$MINDGRAPH_EXEC_HOST" 2>&1 | grep -E "Class:|Total" | head -10 || true
fi

# ── 결과 요약 ──────────────────────────────────────────────────────────────────
echo ""
echo "=== V4 분산 귀속 실행 완료 ==="
echo "traceId:        $TRACE_ID"
echo "diary exec:     $DIARY_EXEC_HOST ($(du -sh "$DIARY_EXEC_HOST" | cut -f1))"
echo "mindgraph exec: $MINDGRAPH_EXEC_HOST ($(du -sh "$MINDGRAPH_EXEC_HOST" | cut -f1))"
echo ""
echo "→ JUnit 게이트(V4DistributedAttributionPoc)에서 probe 수 검증."
echo "  POC_FANOUT_E2E=1 ./gradlew :graph-rag-builder:test --tests '*V4DistributedAttributionPoc*'"
echo ""
echo "=== Teardown은 trap EXIT에서 자동 수행됩니다 ==="
