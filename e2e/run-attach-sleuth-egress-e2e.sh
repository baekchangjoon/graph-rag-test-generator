#!/usr/bin/env bash
# attach 모드 + sleuth egress 발견 e2e (otel attach egress와 동치 동작 검증).
#
# legacy-tram(order-web: Boot2.7/Sleuth/Brave)을 사용자 compose + 생성 override로 attach하고,
# 빌더가 호스트에 0.0.0.0-bind ZipkinSpanReceiver를 띄운다. 컨테이너 order-web의 Brave가
# host.docker.internal:<port>/api/v2/spans 로 CLIENT span을 보고 → EgressCollector.forMode(env)가
# 이를 집어(otlpReceiver와 동일 경로) order-web→reservation egress(POST /reservations)를 발견,
# graph.json httpCalls에 기록되는지 검증한다.
#
# 검증 단계(각 단계는 타임스탬프와 함께 PASS/FAIL/SKIP 판정을 출력한다):
#   PRECHECK : docker 가용성.
#   BUILD-JAR: gradle:7.6-jdk8로 order-web.jar 빌드 + app 이미지 빌드.
#   ATTACH   : 빌더 attach 실행(--trace-mode sleuth) 성공.
#   WIRING   : 빌더 로그에 "sleuth SQL+egress capture (attach): zipkin receiver" (otel "otlp receiver"와 대칭).
#   EGRESS   : graph.json httpCalls에 reservation egress(POST /reservations) 존재.
#   CLEAN    : teardown 후 잔여 컨테이너 0.
#
# 선행 단계가 실패/스킵이면 하위 단계는 SKIP으로 보고한다. Docker 미가용 시 전체 SKIP.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STACK="$ROOT/samples/legacy-tram"
OUT="$ROOT/e2e/.attach-sleuth-egress-out"
LOG="$OUT/builder.log"
APP_PORT=58080
COVERAGE_PORT=56300
DB_PORT=53306
KAFKA_PORT=59092
PROJECT="grb-attach-legacy-tram-sleuth-egress"   # = "grb-attach-" + sutId

DC_E2E="-f $STACK/docker-compose.yml -f $STACK/docker-compose.e2e.yml"

# ── 타임스탬프 + PASS/FAIL/SKIP 판정 로깅 ─────────────────────────────────────
PASS_N=0; FAIL_N=0; SKIP_N=0
declare -a RESULTS
ts()   { date '+%Y-%m-%dT%H:%M:%S%z'; }
log()  { echo "[$(ts)] $*"; }
verdict() {   # verdict <PASS|FAIL|SKIP> <step> <message>
    local v="$1" step="$2"; shift 2; local msg="$*"
    echo "[$(ts)] [$v] $step — $msg"
    RESULTS+=("$v  $step — $msg")
    case "$v" in
        PASS) PASS_N=$((PASS_N+1));;
        FAIL) FAIL_N=$((FAIL_N+1));;
        SKIP) SKIP_N=$((SKIP_N+1));;
    esac
}

cleanup() {
    (cd "$STACK" && docker compose $DC_E2E down -v) >/dev/null 2>&1 || true
    docker compose -p "$PROJECT" -f "$STACK/docker-compose.yml" \
        -f "$OUT/work/attach-override.yml" down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT
rm -rf "$OUT"; mkdir -p "$OUT"

log "=== attach sleuth-egress e2e 시작 (ROOT=$ROOT) ==="

# ── PRECHECK: docker ──────────────────────────────────────────────────────────
DOCKER_OK=0
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    DOCKER_OK=1
    verdict PASS PRECHECK "docker 가용"
else
    verdict SKIP PRECHECK "docker 미가용 — 이하 모든 docker 의존 단계 SKIP"
fi

# ── BUILD-JAR ─────────────────────────────────────────────────────────────────
HOST_JAR="$STACK/order-web/build/libs/order-web.jar"
JAR_OK=0
if [ "$DOCKER_OK" = 1 ]; then
    log "=== [1/4] order-web 호스트 jar 빌드 (gradle:7.6-jdk8) ==="
    if [ -f "$HOST_JAR" ]; then
        log "[prep] cached jar: $HOST_JAR"
        JAR_OK=1
    else
        docker run --rm -v "$STACK/order-web":/src -w /src gradle:7.6-jdk8 \
            gradle bootJar --no-daemon 2>&1 | tee "$OUT/order-web-jar-build.log"
        [ -f "$HOST_JAR" ] && JAR_OK=1
    fi
    if [ "$JAR_OK" = 1 ]; then
        # 이미지는 반드시 직렬로 빌드한다. compose의 병렬(buildx bake) 빌드는 컨테이너 내 Gradle이
        # plugins.gradle.org 해결을 동시에 시도하다 한쪽이 실패한다(관측됨). 단독 빌드는 안정적이므로
        # 서비스별로 하나씩, 최대 2회 재시도한다.
        build_one() {   # build_one <service>
            local svc="$1" attempt
            for attempt in 1 2; do
                log "[prep] 이미지 빌드 $svc (시도 $attempt/2)..."
                if (cd "$STACK" && docker compose $DC_E2E build "$svc"); then
                    return 0
                fi
                log "[prep] $svc 빌드 실패 — 재시도 대기 5s"
                sleep 5
            done
            return 1
        }
        IMG_OK=1
        for svc in order-web reservation; do
            build_one "$svc" || { IMG_OK=0; break; }
        done
        if [ "$IMG_OK" = 1 ]; then
            verdict PASS BUILD-JAR "order-web.jar + 이미지(order-web,reservation) 직렬 빌드 완료"
        else
            JAR_OK=0
            verdict FAIL BUILD-JAR "compose 이미지 직렬 빌드 실패(2회 재시도 후)"
        fi
    else
        verdict FAIL BUILD-JAR "order-web.jar 생성 실패 ($OUT/order-web-jar-build.log 참조)"
    fi
else
    verdict SKIP BUILD-JAR "docker 미가용"
fi

# ── ATTACH ────────────────────────────────────────────────────────────────────
ATTACH_OK=0
if [ "$JAR_OK" = 1 ]; then
    log "=== [2/4] 빌더 attach 실행 (--trace-mode sleuth) ==="
    "$ROOT/gradlew" -p "$ROOT" -q :graph-rag-builder:run --args="build \
      --attach \
      --sut-compose $STACK/docker-compose.yml \
      --app-service order-web \
      --app-container-port 8080 \
      --app-port $APP_PORT \
      --coverage-port $COVERAGE_PORT \
      --jdbc-url jdbc:mysql://localhost:$DB_PORT/orderdb \
      --kafka-bootstrap localhost:$KAFKA_PORT \
      --db-service mysql \
      --trace-mode sleuth \
      --capture-services order-web,reservation \
      --sut-src $STACK/order-web/src/main/java \
      --sut-jar $HOST_JAR \
      --out $OUT \
      --sut-id legacy-tram-sleuth-egress" 2>&1 | tee "$LOG"
    BUILDER_EXIT=${PIPESTATUS[0]}
    if [ "$BUILDER_EXIT" = 0 ]; then
        ATTACH_OK=1
        verdict PASS ATTACH "빌더 attach 정상 종료 (exit 0)"
    else
        verdict FAIL ATTACH "빌더 attach 비정상 종료 (exit $BUILDER_EXIT, 로그 $LOG)"
    fi
else
    verdict SKIP ATTACH "BUILD-JAR 미완료"
fi

# ── WIRING ────────────────────────────────────────────────────────────────────
if [ "$ATTACH_OK" = 1 ]; then
    if grep -q "sleuth SQL+egress capture (attach): zipkin receiver" "$LOG"; then
        verdict PASS WIRING "zipkin receiver attach 로그 확인 (otel otlp receiver와 대칭)"
    else
        verdict FAIL WIRING "zipkin receiver attach 로그 없음 (sleuth egress 미배선)"
    fi
else
    verdict SKIP WIRING "ATTACH 미완료"
fi

# ── EGRESS ────────────────────────────────────────────────────────────────────
EGRESS_OK=0
if [ "$ATTACH_OK" = 1 ] && [ -f "$OUT/graph.json" ]; then
    EG_MSG="$(python3 - "$OUT" <<'PY'
import json, sys, os
out = sys.argv[1]
g = json.load(open(os.path.join(out, "graph.json")))
calls = g.get("httpCalls", [])   # GraphAsset.httpCalls() → CapturedHttpCall[]
hits = [c for c in calls if "/reservations" in (c.get("urlPath") or "")]
if hits:
    print(f"OK /reservations httpCalls={len(hits)} (total httpCalls={len(calls)})")
    sys.exit(0)
print(f"no /reservations httpCall (total httpCalls={len(calls)})")
sys.exit(1)
PY
)"
    if [ $? = 0 ]; then
        EGRESS_OK=1
        verdict PASS EGRESS "sleuth egress 발견 — $EG_MSG"
    else
        verdict FAIL EGRESS "egress 미발견 — $EG_MSG"
    fi
elif [ "$ATTACH_OK" = 1 ]; then
    verdict FAIL EGRESS "graph.json 미생성"
else
    verdict SKIP EGRESS "ATTACH 미완료"
fi

# ── CLEAN ─────────────────────────────────────────────────────────────────────
if [ "$DOCKER_OK" = 1 ]; then
    log "=== [4/4] teardown 후 잔여 컨테이너 0 검증 ==="
    cleanup
    remaining="$(docker compose -p "$PROJECT" -f "$STACK/docker-compose.yml" \
        -f "$OUT/work/attach-override.yml" ps -q 2>/dev/null | wc -l | tr -d ' ')"
    if [ "$remaining" = "0" ]; then
        verdict PASS CLEAN "잔여 컨테이너 0"
    else
        verdict FAIL CLEAN "잔여 컨테이너 $remaining"
    fi
else
    verdict SKIP CLEAN "docker 미가용"
fi

# ── 요약 ──────────────────────────────────────────────────────────────────────
echo ""
log "=== 판정 요약 (PASS=$PASS_N FAIL=$FAIL_N SKIP=$SKIP_N) ==="
for r in "${RESULTS[@]}"; do echo "  $r"; done

if [ "$FAIL_N" -gt 0 ]; then
    log "RESULT: FAIL"
    exit 1
elif [ "$EGRESS_OK" = 1 ]; then
    log "RESULT: PASS"
    exit 0
else
    log "RESULT: INCONCLUSIVE (실행 안 됨 — SKIP 다수)"
    exit 2
fi
