#!/usr/bin/env bash
# REQ-007 E2E: gateway-service + 다운스트림 WireMock 스텁 + 빌더 빌드 + 생성 테스트 실행.
# 흐름:
#   1. gateway-service bootJar 빌드
#   2. 빌더 build: GatewayRouteIndexer → 탐색(WireMock에 catch-all 스텁) → graph.json
#   3. 빌더 generate: 프록시 smoke test 생성
#   4. Docker Compose(gateway + WireMock) 기동 → 생성 테스트 실행
#   5. 정리
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
E2E="$ROOT/e2e"
OUT="$E2E/.gateway-e2e-out"
LOG="$OUT/builder.log"
GW="$ROOT/gradlew"
COMPOSE_PROJECT="grb-gateway-e2e"

# Java 17 필수 (gateway-service = Spring Boot 3.5 / Spring Cloud 2025.0).
# 이식성: JAVA_HOME(CI의 setup-java) → PATH의 java → mac 기본 경로 순으로 해석. 하드코딩 금지(CI=Linux).
if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    JAVA17="${JAVA_HOME}/bin/java"
elif [ -x "/Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home/bin/java" ]; then
    JAVA17="/Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA17="$(command -v java)"
else
    echo "ERROR: Java 17 not found (set JAVA_HOME or put java 17 on PATH)" >&2
    exit 1
fi

cleanup() {
    docker compose -p "$COMPOSE_PROJECT" -f "$E2E/gateway-test-compose.yml" \
        down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT
rm -rf "$OUT"; mkdir -p "$OUT"

echo "=== [1/5] gateway-service bootJar 빌드 ==="
"$GW" -q :samples:gateway-service:bootJar

GATEWAY_JAR="$ROOT/samples/gateway-service/build/libs/gateway-service.jar"
if [ ! -f "$GATEWAY_JAR" ]; then
    echo "ERROR: gateway-service.jar not found at $GATEWAY_JAR" >&2
    exit 1
fi

echo "=== [2/5] 빌더 build: 라우트 발견 + 탐색 (WireMock catch-all 스텁) ==="
"$GW" -q :graph-rag-builder:run --args="build \
  --sut-src $ROOT/samples/gateway-service/src/main/java \
  --sut-jar $GATEWAY_JAR \
  --sut-compose $E2E/gateway-compose.yml \
  --external-stubs $E2E/gateway-stubs \
  --sut-env ORDERS_URI={{wiremock}} \
  --out $OUT/graph \
  --sut-id gateway-service \
  --budget-requests 5" 2>&1 | tee "$LOG"

# 빌드 결과 검증
echo "--- 라우트 발견 검증 ---"
python3 - "$OUT/graph" <<'PY'
import json, sys, os
g = json.load(open(os.path.join(sys.argv[1], "graph.json")))
eps = g.get("endpoints", [])
paths = g.get("paths", [])
assert eps, "gateway route NOT discovered — endpoint list is empty"
gw_ep = [e for e in eps if e.get("path") == "/api/v1/orders/**"]
assert gw_ep, f"route /api/v1/orders/** not found in endpoints: {[e['path'] for e in eps]}"
assert paths, "no ExploredPath produced — explorer returned no paths"
s200 = [p for p in paths if p.get("expectedStatus") == 200]
assert s200, f"no 200 path found in {[p.get('expectedStatus') for p in paths]}"
print(f"OK: {len(eps)} endpoint(s), {len(paths)} path(s) — {gw_ep[0]['id']} explored s200")
PY

echo "=== [3/5] 빌더 generate: 프록시 smoke test 생성 ==="
cat > "$OUT/request-gateway-orders.json" << 'JSON'
{
  "endpointId": "get-api-v1-orders",
  "testClassName": "GatewayOrdersGetTest",
  "packageName": "io.graphrag.generated",
  "authMode": "DISABLED"
}
JSON

"$GW" -q :test-generator:run --args="generate \
  --request $OUT/request-gateway-orders.json \
  --graph $OUT/graph \
  --out $OUT/generated" 2>&1 | tee -a "$LOG"

GENERATED_COUNT=$(find "$OUT/generated" -name "*.java" | wc -l | tr -d ' ')
echo "생성된 테스트 클래스: $GENERATED_COUNT"
if [ "$GENERATED_COUNT" -eq 0 ]; then
    echo "ERROR: no test class generated" >&2
    exit 1
fi

# REQ-007(b): 생성된 소스에 X-Downstream (또는 x-downstream) 헤더 단언이 포함되어 있는지 검증.
# Java HttpClient는 응답 헤더를 소문자로 반환하므로 case-insensitive grep 사용.
echo "--- REQ-007(b): X-Downstream 헤더 단언 검증 ---"
if ! grep -rqi 'header("x-downstream"' "$OUT/generated/"; then
    echo "ERROR: generated tests do NOT contain .header(\"x-downstream\", ...) assertion" >&2
    echo "--- 생성된 소스 목록 ---"
    find "$OUT/generated" -name "*.java"
    exit 1
fi
echo "OK: X-Downstream header assertion found in generated sources"

echo "=== [4/5] Docker Compose 기동 (gateway + WireMock) + 생성 테스트 실행 ==="
# 생성 테스트를 e2e 모듈 소스 디렉터리로 복사
mkdir -p "$E2E/build/generated-tests"
rm -rf "$E2E/build/generated-tests"/*
cp -R "$OUT/generated/io" "$E2E/build/generated-tests/"
if [ -f "$OUT/generated/junit-platform.properties" ]; then
    cp "$OUT/generated/junit-platform.properties" \
       "$E2E/src/test/resources/junit-platform.properties"
fi

# Docker Compose 기동 (gateway + wiremock)
docker compose -p "$COMPOSE_PROJECT" -f "$E2E/gateway-test-compose.yml" down -v >/dev/null 2>&1 || true
docker compose -p "$COMPOSE_PROJECT" -f "$E2E/gateway-test-compose.yml" up -d --build

# gateway SUT 기동 대기
echo "gateway SUT 기동 대기 (port 59180)..."
for i in $(seq 1 30); do
    if curl -fsS http://localhost:59180/actuator/health 2>/dev/null | grep -q UP; then
        echo "gateway healthy"
        break
    fi
    [ "$i" -eq 30 ] && {
        echo "ERROR: gateway did not become healthy in time"
        docker compose -p "$COMPOSE_PROJECT" -f "$E2E/gateway-test-compose.yml" logs gateway
        exit 1
    }
    sleep 2
done

# 생성 테스트 실행
set +e
APP_BASE_URI=http://localhost:59180 \
JDBC_URL=jdbc:postgresql://localhost:5432/nodb \
JDBC_USER=noop \
JDBC_PASS=noop \
HTTP_MOCK_ADMIN=http://localhost:59191/__admin \
AUTH_ADAPTER=noop \
"$GW" :e2e:test
TEST_EXIT=$?
set -e

PASSED=$(grep -rh 'tests="' "$E2E"/build/test-results/test/*.xml 2>/dev/null \
  | sed -E 's/.*tests="([0-9]+)" skipped="([0-9]+)" failures="([0-9]+)" errors="([0-9]+)".*/\1 \2 \3 \4/' \
  | awk '{t+=$1; s+=$2; f+=$3; e+=$4} END {printf "tests=%d skipped=%d failures=%d errors=%d", t, s, f, e}')
echo "생성 테스트 결과: $PASSED (클래스 ${GENERATED_COUNT}개)"

echo "=== [5/5] 정리 ==="
cleanup

if [ $TEST_EXIT -eq 0 ]; then
    echo "✅ GATEWAY-E2E PASS (REQ-007) — $PASSED"
else
    echo "❌ GATEWAY-E2E FAIL (exit=$TEST_EXIT) — $PASSED"
fi
exit $TEST_EXIT
