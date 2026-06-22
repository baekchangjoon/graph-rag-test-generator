#!/usr/bin/env bash
# diary-overhead.sh — V3b cross-SUT 오버헤드 측정 (tainted-spring diary)
#
# petclinic(H2 in-memory) 대비 현실적 SUT(Postgres+Kafka)에서
# per-request pjacoco flush 오버헤드가 얼마나 낮아지는지 측정한다.
#
# 측정 모델: V3b production-model과 동일
#   - baseline: 60 POST /internal/diaries WITHOUT traceparent/flush
#   - measured: 60 POST /internal/diaries WITH traceparent + per-request flush
#     (load는 루프 밖 — off critical path, petclinic production-model과 동일)
#   - flush 왕복: 100회 별도 측정
#
# 사전 조건:
#   - Docker Desktop 실행 중
#   - pjacoco jacocoagent-parallel.jar 빌드 완료
#
# 실행:
#   bash e2e/poc-fanout/diary-overhead.sh
#
# 환경 변수:
#   TAINTED_PLATFORM — tainted-spring-platform 경로 (기본: ~/github_tainted-spring/tainted-spring-platform)
#   N_REQUESTS       — 측정 요청 수 (기본: 60)
#   N_WARMUP         — 워밍업 요청 수 (기본: 10)
#   N_FLUSH_PROBE    — flush 왕복 측정 횟수 (기본: 100)
#   SKIP_COMPOSE     — 1이면 compose 기동/종료 건너뜀 (기존 기동 중인 경우)

set -euo pipefail

TAINTED_PLATFORM="${TAINTED_PLATFORM:-$HOME/github_tainted-spring/tainted-spring-platform}"
N_REQUESTS="${N_REQUESTS:-60}"
N_WARMUP="${N_WARMUP:-10}"
N_FLUSH_PROBE="${N_FLUSH_PROBE:-100}"
SKIP_COMPOSE="${SKIP_COMPOSE:-0}"

DIARY_PORT=8082
DIARY_CTL_PORT=6310

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
pass() { echo -e "${GREEN}[PASS]${NC} $*"; }
fail() { echo -e "${RED}[FAIL]${NC} $*"; }
info() { echo -e "${YELLOW}[INFO]${NC} $*"; }

# ── teardown ─────────────────────────────────────────────────────────────────
teardown() {
    if [[ "$SKIP_COMPOSE" == "1" ]]; then
        info "SKIP_COMPOSE=1 — skipping teardown"
        return
    fi
    info "Stopping tainted-spring compose services..."
    cd "$TAINTED_PLATFORM"
    docker compose \
        -f docker-compose.yml \
        -f docker-compose.pjacoco-otel.yml \
        down --remove-orphans 2>&1 | tail -5 || true
    info "Teardown complete."
}
trap teardown EXIT

echo "=== V3b cross-SUT 오버헤드 측정 — tainted-spring diary ==="
echo "Platform:  $TAINTED_PLATFORM"
echo "Requests:  $N_REQUESTS baseline + $N_REQUESTS measured"
echo "Warmup:    $N_WARMUP throwaway"
echo "FlushProbe: $N_FLUSH_PROBE"
echo ""

# ── 1. pjacoco jar 확인 ───────────────────────────────────────────────────────
PJACOCO_JAR="$HOME/github_parallel-per-test-coverage/parallel-per-test-coverage/agent/build/libs/jacocoagent-parallel.jar"
if [[ ! -f "$PJACOCO_JAR" ]]; then
    fail "pjacoco jar not found at $PJACOCO_JAR"
    fail "Build: cd ~/github_parallel-per-test-coverage/parallel-per-test-coverage && JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home ./gradlew :agent:shadowJar"
    exit 1
fi
info "pjacoco jar: $PJACOCO_JAR"

# ── 2. coverage 디렉터리 준비 ─────────────────────────────────────────────────
mkdir -p "$TAINTED_PLATFORM/coverage/diary"

# ── 3. Docker Compose 기동 ────────────────────────────────────────────────────
if [[ "$SKIP_COMPOSE" != "1" ]]; then
    info "Starting tainted-spring services (diary only — mindgraph not needed)..."
    cd "$TAINTED_PLATFORM"
    docker compose \
        -f docker-compose.yml \
        -f docker-compose.pjacoco-otel.yml \
        up -d zookeeper kafka postgres redis auth-user diary 2>&1 | tail -20

    # wait for [pjacoco] agent installed
    info "Waiting for diary [pjacoco] agent installed..."
    DEADLINE=$(( $(date +%s) + 120 ))
    while true; do
        if docker compose -f docker-compose.yml -f docker-compose.pjacoco-otel.yml logs diary 2>&1 | grep -q "\[pjacoco\] agent installed"; then
            pass "diary: [pjacoco] agent installed"; break
        fi
        if [[ $(date +%s) -gt $DEADLINE ]]; then
            fail "diary did not log [pjacoco] agent installed within 120s"
            docker compose -f docker-compose.yml -f docker-compose.pjacoco-otel.yml logs diary 2>&1 | tail -30
            exit 1
        fi
        sleep 3
    done

    # wait for HTTP readiness
    info "Waiting for diary HTTP readiness on :$DIARY_PORT..."
    DEADLINE=$(( $(date +%s) + 120 ))
    while true; do
        if curl -fs "http://localhost:$DIARY_PORT/actuator/health" >/dev/null 2>&1; then
            pass "diary HTTP ready on :$DIARY_PORT"; break
        fi
        if [[ $(date +%s) -gt $DEADLINE ]]; then
            fail "diary HTTP did not become ready on :$DIARY_PORT within 120s"; exit 1
        fi
        sleep 3
    done

    # wait for pjacoco control (use /stop with a dummy testId — returns 200 when ready)
    info "Waiting for diary pjacoco control on :$DIARY_CTL_PORT..."
    DEADLINE=$(( $(date +%s) + 60 ))
    while true; do
        STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
            "http://localhost:$DIARY_CTL_PORT/__coverage__/test/stop?testId=readiness-probe&result=passed" 2>/dev/null || echo "000")
        if [[ "$STATUS" == "200" ]]; then
            pass "diary pjacoco control ready on :$DIARY_CTL_PORT"; break
        fi
        if [[ $(date +%s) -gt $DEADLINE ]]; then
            fail "diary pjacoco control did not become ready on :$DIARY_CTL_PORT (last status=$STATUS)"; exit 1
        fi
        sleep 2
    done
else
    info "SKIP_COMPOSE=1 — assuming diary is already running"
fi

# ── 4. 워밍업 (JIT/connection-setup 스큐 제거) ────────────────────────────────
info "Warmup: $N_WARMUP throwaway requests..."
for i in $(seq 1 "$N_WARMUP"); do
    curl -sf -o /dev/null -X POST "http://localhost:$DIARY_PORT/internal/diaries" \
        -H 'Content-Type: application/json' \
        -d "{\"userId\":\"warmup${i}\",\"title\":\"w\",\"content\":\"w\",\"primaryEmotion\":\"joy\",\"energyScore\":5}" \
        || true
done
info "Warmup done."
echo ""

# ── 5. Baseline 측정: 60 requests WITHOUT traceparent/flush ───────────────────
info "Measuring BASELINE: $N_REQUESTS POST /internal/diaries (no traceparent, no flush)..."
T_BASELINE_START=$(python3 -c "import time; print(int(time.time()*1000))")

for i in $(seq 1 "$N_REQUESTS"); do
    HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "http://localhost:$DIARY_PORT/internal/diaries" \
        -H 'Content-Type: application/json' \
        -d "{\"userId\":\"base${i}\",\"title\":\"t\",\"content\":\"c\",\"primaryEmotion\":\"joy\",\"energyScore\":5}")
    if [[ "$HTTP_STATUS" -lt 200 || "$HTTP_STATUS" -ge 300 ]]; then
        fail "Baseline request $i → HTTP $HTTP_STATUS"; exit 1
    fi
done

T_BASELINE_END=$(python3 -c "import time; print(int(time.time()*1000))")
BASELINE_TOTAL=$(( T_BASELINE_END - T_BASELINE_START ))
BASELINE_PER_REQ=$(python3 -c "print(round($BASELINE_TOTAL / $N_REQUESTS, 2))")

pass "Baseline: total=${BASELINE_TOTAL}ms, per-request=${BASELINE_PER_REQ}ms"
echo ""

# ── 6. Flush 왕복 측정: 100회 (별도, off critical path) ──────────────────────
info "Measuring FLUSH round-trip: $N_FLUSH_PROBE calls..."
FLUSH_SUM_MS=0
FLUSH_TIMES=()
FLUSH_PROBE_TRACE="diaryflushprobe000000000000000001"
for i in $(seq 1 "$N_FLUSH_PROBE"); do
    T_F_START=$(python3 -c "import time; print(int(time.time()*1000))")
    curl -sf -o /dev/null -X POST \
        "http://localhost:$DIARY_CTL_PORT/__coverage__/test/stop?testId=${FLUSH_PROBE_TRACE}&result=passed" \
        || true
    T_F_END=$(python3 -c "import time; print(int(time.time()*1000))")
    DT=$(( T_F_END - T_F_START ))
    FLUSH_TIMES+=("$DT")
    FLUSH_SUM_MS=$(( FLUSH_SUM_MS + DT ))
done

FLUSH_MEAN=$(python3 -c "print(round($FLUSH_SUM_MS / $N_FLUSH_PROBE, 3))")
# p95 — write times to temp file to avoid bash array expansion issues
printf '%s\n' "${FLUSH_TIMES[@]}" > /tmp/flush_times.txt
FLUSH_P95=$(python3 -c "
times = sorted(int(x) for x in open('/tmp/flush_times.txt').read().split())
idx = int(len(times) * 0.95)
print(round(times[idx], 1))
")
pass "Flush round-trip: mean=${FLUSH_MEAN}ms, p95=${FLUSH_P95}ms"
echo ""

# ── 7. Measured 측정: 60 requests WITH traceparent + per-request flush ────────
info "Measuring MEASURED (production model): $N_REQUESTS POST + traceparent + flush..."
info "(load is OFF critical path — flushed exec files accumulated, loaded after loop)"

T_MEASURED_START=$(python3 -c "import time; print(int(time.time()*1000))")

MEASURED_TRACE_IDS=()
for i in $(seq 1 "$N_REQUESTS"); do
    # 32-hex traceId unique per request (valid lowercase hex only)
    TRACE_ID=$(printf 'dba%029x' "$i")
    MEASURED_TRACE_IDS+=("$TRACE_ID")
    TRACEPARENT="00-${TRACE_ID}-0000000000000001-01"

    HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "http://localhost:$DIARY_PORT/internal/diaries" \
        -H 'Content-Type: application/json' \
        -H "traceparent: $TRACEPARENT" \
        -d "{\"userId\":\"meas${i}\",\"title\":\"t\",\"content\":\"c\",\"primaryEmotion\":\"joy\",\"energyScore\":5}")
    if [[ "$HTTP_STATUS" -lt 200 || "$HTTP_STATUS" -ge 300 ]]; then
        fail "Measured request $i → HTTP $HTTP_STATUS"; exit 1
    fi

    # per-request flush (critical path)
    curl -sf -o /dev/null -X POST \
        "http://localhost:$DIARY_CTL_PORT/__coverage__/test/stop?testId=${TRACE_ID}&result=passed" \
        || true
done

T_MEASURED_END=$(python3 -c "import time; print(int(time.time()*1000))")
MEASURED_TOTAL=$(( T_MEASURED_END - T_MEASURED_START ))
MEASURED_PER_REQ=$(python3 -c "print(round($MEASURED_TOTAL / $N_REQUESTS, 2))")

pass "Measured: total=${MEASURED_TOTAL}ms, per-request=${MEASURED_PER_REQ}ms"
echo ""

# ── 8. exec 파일 대기 + 일괄 load (off critical path) ────────────────────────
info "Waiting for .exec files to be written (off critical path)..."
T_LOAD_START=$(python3 -c "import time; print(int(time.time()*1000))")
EXEC_COUNT=0
EXEC_BYTES=0
for TRACE_ID in "${MEASURED_TRACE_IDS[@]}"; do
    EXEC_PATH="$TAINTED_PLATFORM/coverage/diary/${TRACE_ID}.exec"
    DEADLINE=$(( $(date +%s) + 10 ))
    while [[ ! -s "$EXEC_PATH" && $(date +%s) -le $DEADLINE ]]; do
        sleep 0.2
    done
    if [[ -s "$EXEC_PATH" ]]; then
        EXEC_COUNT=$(( EXEC_COUNT + 1 ))
        BYTES=$(wc -c < "$EXEC_PATH")
        EXEC_BYTES=$(( EXEC_BYTES + BYTES ))
    fi
done
T_LOAD_END=$(python3 -c "import time; print(int(time.time()*1000))")
POST_LOAD_MS=$(( T_LOAD_END - T_LOAD_START ))

info ".exec files: count=$EXEC_COUNT, total_bytes=$EXEC_BYTES, load_wait=${POST_LOAD_MS}ms"
echo ""

# ── 9. 결과 계산 ──────────────────────────────────────────────────────────────
OVERHEAD_PCT=$(python3 -c "print(round(($MEASURED_TOTAL - $BASELINE_TOTAL) / $BASELINE_TOTAL * 100, 2))")
OVERHEAD_ABS=$(( MEASURED_TOTAL - BASELINE_TOTAL ))

echo "=================================================================="
echo "=== V3b cross-SUT 오버헤드 측정 결과 (tainted-spring diary) ==="
echo "=================================================================="
echo ""
echo "  [① flush 왕복]"
echo "    mean: ${FLUSH_MEAN}ms  p95: ${FLUSH_P95}ms  (petclinic: 4.1ms)"
echo ""
echo "  [② 벽시계]"
echo "    baseline:        ${BASELINE_TOTAL}ms  (${BASELINE_PER_REQ}ms/req)"
echo "    measured:        ${MEASURED_TOTAL}ms  (${MEASURED_PER_REQ}ms/req)"
echo "    overhead (abs):  +${OVERHEAD_ABS}ms"
echo "    overhead (%):    +${OVERHEAD_PCT}%  (petclinic production-model: +15.80%)"
echo ""
echo "  [③ .exec 파일]"
echo "    count: $EXEC_COUNT / $N_REQUESTS"
echo "    total: ${EXEC_BYTES} bytes  (~$(python3 -c "print(round($EXEC_BYTES/1024,1))")KB)"
echo "    post-run load: ${POST_LOAD_MS}ms (off critical path)"
echo ""
echo "  [환경]"
echo "    diary: Postgres + Kafka (tainted-spring)"
echo "    pjacoco: traceKeyAutoCreate=true, loopback"
echo "=================================================================="
echo ""

# RESULT line for grep
echo "OVERHEAD_RESULT: baseline=${BASELINE_PER_REQ}ms/req, measured_total_overhead=+${OVERHEAD_PCT}%, flush_mean=${FLUSH_MEAN}ms, petclinic_comparison=+15.80%"
echo ""
echo "=== Teardown은 trap EXIT에서 자동 수행됩니다 ==="
