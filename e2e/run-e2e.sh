#!/usr/bin/env bash
# 전 사이클 E2E: build(분기 탐색) → generate(전 path) → docker-compose 기동 → 생성 테스트 실행
# Phase 1 메트릭: 같은 endpoint의 N개 path가 N개 테스트로 합성되고 전부 통과.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
E2E="$ROOT/e2e"
OUT="$E2E/out"
GW="$ROOT/gradlew"

# 고유 compose project name — 병렬 실행·잔류 충돌 방지. EXIT/INT/TERM 시 이 project만 정리.
PROJ="grb-e2e-$$"
export COMPOSE_PROJECT_NAME="$PROJ"
trap 'docker compose -p "$PROJ" -f "$E2E/docker-compose.yml" down -v --remove-orphans >/dev/null 2>&1 || true' EXIT INT TERM

# 선택 인자: --request-headers-file <path>
#  (a) 빌더 탐색(:graph-rag-builder:run --args)에 --request-headers-file 전달
#  (b) 생성 테스트 실행용 REQUEST_HEADERS export (미설정 시 파일 내용으로 채움)
# 인자가 없으면 완전 no-op — 기존 동작 그대로.
REQUEST_HEADERS_FILE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --request-headers-file) REQUEST_HEADERS_FILE="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

BUILDER_HEADER_ARG=""
if [ -n "$REQUEST_HEADERS_FILE" ]; then
  BUILDER_HEADER_ARG="--request-headers-file $REQUEST_HEADERS_FILE"
  if [ -z "${REQUEST_HEADERS:-}" ]; then
    export REQUEST_HEADERS="$(grep -v '^[[:space:]]*#' "$REQUEST_HEADERS_FILE" | grep -v '^[[:space:]]*$')"
  fi
fi

echo "=== [1/5] SUT/서비스 jar 빌드 ==="
"$GW" -q :samples:order-service:bootJar :test-state-dashboard:bootJar :socket-mock-server:bootJar \
  :e2e:copyOtelAgent

echo "=== [2/5] 도구 1: 분기 탐색 + graph 빌드 (분석 환경: Testcontainers + JaCoCo + WireMock) ==="
rm -rf "$OUT"
"$GW" -q :graph-rag-builder:run --args="build \
  --sut-src $ROOT/samples/order-service/src/main/java \
  --sut-resources $ROOT/samples/order-service/src/main/resources \
  --sut-jar $ROOT/samples/order-service/build/libs/order-service.jar \
  --out $OUT/graph \
  --sut-id order-service \
  --with-kafka \
  --budget-requests 60 \
  --external-stubs $E2E/external-stubs \
  --sut-env EXTERNAL_INVENTORY_URL={{wiremock}},EXTERNAL_FRAUD_URL={{wiremock}} \
  --triple-candidates $E2E/triples \
  --sut-compose $E2E/docker-compose.yml \
  --auth-login-path /api/auth/login \
  --auth-user admin \
  --auth-pass password \
  $BUILDER_HEADER_ARG \
  --commit-sha $(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"

echo "=== [3/5] 도구 2: 전 path 테스트 생성 ==="
for req in request-orders request-orders-batch request-orders-ship request-deep request-prefs request-tags request-search request-ws request-orders-get-id request-orders-get-user \
           request-bookings request-bookings-get-id request-bookings-put-id request-bookings-delete-id \
           request-order-events request-profiles-by-name request-profiles-map request-transfers; do
  "$GW" -q :test-generator:run --args="generate \
    --request $E2E/$req.json \
    --graph $OUT/graph \
    --out $OUT/generated"
done
GENERATED_COUNT=$(find "$OUT/generated" -name "*.java" | wc -l | tr -d ' ')
echo "생성된 테스트 클래스: $GENERATED_COUNT"

echo "=== [4/5] docker-compose 기동 + 생성 테스트 실행 ==="
mkdir -p "$E2E/build/generated-tests"
rm -rf "$E2E/build/generated-tests"/*
cp -R "$OUT/generated/io" "$E2E/build/generated-tests/"
# NOTE: this file is overwritten on each run by the generator-emitted junit-platform.properties
# (kept byte-identical to the emitted config so the copy leaves no diff). Do not add comments here.
# 생성기가 emit한 병렬 설정을 e2e 테스트 리소스로 반영(REQ-013): 실제 배포 설정으로 실행
if [ -f "$OUT/generated/junit-platform.properties" ]; then
  cp "$OUT/generated/junit-platform.properties" "$E2E/src/test/resources/junit-platform.properties"
fi

docker compose -f "$E2E/docker-compose.yml" down -v --remove-orphans >/dev/null 2>&1 || true
# 고정 호스트 포트가 직전 down 직후 해제되기 전(race)이거나 잔류 컨테이너로
# "address already in use"가 날 수 있다 → down 후 재시도.
up_ok=false
for attempt in 1 2 3; do
  if docker compose -f "$E2E/docker-compose.yml" up -d --build; then up_ok=true; break; fi
  echo "compose up 실패 (attempt $attempt/3) — 정리 후 재시도"
  docker compose -f "$E2E/docker-compose.yml" down -v --remove-orphans >/dev/null 2>&1 || true
  sleep 5
done
[ "$up_ok" = true ] || { echo "compose up 최종 실패"; exit 1; }

echo "SUT 기동 대기..."
for i in $(seq 1 60); do
  if curl -fsS http://localhost:58080/actuator/health 2>/dev/null | grep -q UP; then
    break
  fi
  [ "$i" = 60 ] && { echo "SUT 기동 실패"; docker compose -f "$E2E/docker-compose.yml" logs app; exit 1; }
  sleep 2
done

set +e
APP_BASE_URI=http://localhost:58080 \
JDBC_URL=jdbc:postgresql://localhost:56432/app \
JDBC_USER=app \
JDBC_PASS=app \
KAFKA_BOOTSTRAP_SERVERS=localhost:59092 \
HTTP_MOCK_ADMIN=http://localhost:59091/__admin \
DASHBOARD_URL=http://localhost:58099 \
AUTH_ADAPTER=real \
AUTH_LOGIN_PATH=/api/auth/login \
AUTH_USER=admin \
AUTH_PASS=password \
REQUEST_HEADERS="${REQUEST_HEADERS:-}" \
"$GW" :e2e:test
TEST_EXIT=$?
set -e

PASSED=$(grep -rh 'tests="' "$E2E"/build/test-results/test/*.xml 2>/dev/null \
  | sed -E 's/.*tests="([0-9]+)" skipped="([0-9]+)" failures="([0-9]+)" errors="([0-9]+)".*/\1 \2 \3 \4/' \
  | awk '{t+=$1; s+=$2; f+=$3; e+=$4} END {printf "tests=%d skipped=%d failures=%d errors=%d", t, s, f, e}')
echo "결과: $PASSED (클래스 ${GENERATED_COUNT}개)"

echo "=== [5/5] 정리 ==="
docker compose -f "$E2E/docker-compose.yml" down -v

if [ $TEST_EXIT -eq 0 ]; then
  echo "✅ E2E PASS — $PASSED"
else
  echo "❌ E2E FAIL (exit=$TEST_EXIT) — $PASSED"
fi
exit $TEST_EXIT
