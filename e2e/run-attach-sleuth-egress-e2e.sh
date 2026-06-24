#!/usr/bin/env bash
# attach 모드 + sleuth egress 발견 e2e (otel attach egress와 동치 동작 검증).
#
# legacy-tram(order-web: Boot2.7/Sleuth/Brave)을 사용자 compose + 생성 override로 attach하고,
# 빌더가 호스트에 0.0.0.0-bind ZipkinSpanReceiver를 띄운다. 컨테이너 order-web의 Brave가
# host.docker.internal:<port>/api/v2/spans 로 CLIENT span을 보고 → EgressCollector.forMode(env)가
# 이를 집어(otlpReceiver와 동일 경로) order-web→reservation egress(POST /reservations)를 발견,
# graph.json httpCalls에 기록되는지 검증한다.
#
# 검증:
#   EGRESS: graph.json 에 reservation egress(POST /reservations) 호출 존재.
#   WIRING: 빌더 로그에 "sleuth SQL+egress capture (attach): zipkin receiver" (otel의 "otlp receiver"와 대칭).
#   CLEAN : teardown 후 잔여 컨테이너 0.
#
# Docker 필요(gradle:7.6-jdk8로 order-web.jar 빌드 + legacy-tram 스택). 수동/게이트 실행.
set -euo pipefail
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

cleanup() {
    # 스택(빌드용 up)과 빌더 attach 프로젝트 둘 다 정리.
    (cd "$STACK" && docker compose $DC_E2E down -v) >/dev/null 2>&1 || true
    docker compose -p "$PROJECT" -f "$STACK/docker-compose.yml" \
        -f "$OUT/work/attach-override.yml" down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT
rm -rf "$OUT"; mkdir -p "$OUT"

echo "=== [1/4] order-web 호스트 jar 빌드 (gradle:7.6-jdk8) ==="
HOST_JAR="$STACK/order-web/build/libs/order-web.jar"
if [ -f "$HOST_JAR" ]; then
    echo "[prep] cached jar: $HOST_JAR"
else
    docker run --rm -v "$STACK/order-web":/src -w /src gradle:7.6-jdk8 \
        gradle bootJar --no-daemon 2>&1 | tee "$OUT/order-web-jar-build.log"
    [ -f "$HOST_JAR" ] || { echo "❌ order-web.jar 생성 실패"; exit 1; }
fi
# app 이미지 미리 빌드(attach up 시 build 시간 분리).
(cd "$STACK" && docker compose $DC_E2E build order-web reservation)

echo "=== [2/4] 빌더 attach 실행 (--trace-mode sleuth) ==="
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
  --capture-services order-web,reservation,ledger \
  --sut-src $STACK/order-web/src/main/java \
  --sut-jar $HOST_JAR \
  --out $OUT \
  --sut-id legacy-tram-sleuth-egress" 2>&1 | tee "$LOG"

echo "=== [3/4] egress 발견 + 배선 검증 ==="
# WIRING: sleuth egress 리시버가 attach 경로에서 실제로 떴는지(otel의 otlp receiver와 대칭).
grep -q "sleuth SQL+egress capture (attach): zipkin receiver" "$LOG" \
  || { echo "❌ zipkin receiver attach 로그 없음 (sleuth egress 미배선)"; exit 1; }

# EGRESS: graph.json에 reservation egress(POST /reservations)가 기록됐는지.
python3 - "$OUT" <<'PY'
import json, sys, os
out = sys.argv[1]
g = json.load(open(os.path.join(out, "graph.json")))
calls = g.get("httpCalls", [])   # GraphAsset.httpCalls() → CapturedHttpCall[]
hits = [c for c in calls if "/reservations" in (c.get("urlPath") or "")]
assert hits, f"egress not discovered: no /reservations httpCall in graph.json (httpCalls={len(calls)})"
print(f"OK egress discovered: /reservations httpCalls={len(hits)} (total httpCalls={len(calls)})")
PY
echo "✅ sleuth egress(POST /reservations) 발견 — otel attach egress와 동치"

echo "=== [4/4] teardown 후 잔여 컨테이너 0 검증 ==="
cleanup
remaining="$(docker compose -p "$PROJECT" -f "$STACK/docker-compose.yml" \
  -f "$OUT/work/attach-override.yml" ps -q 2>/dev/null | wc -l | tr -d ' ')"
[ "$remaining" = "0" ] || { echo "❌ 잔여 컨테이너 $remaining"; exit 1; }
echo "✅ ATTACH-SLEUTH-EGRESS-E2E PASS"
