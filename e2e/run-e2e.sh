#!/usr/bin/env bash
# 전 사이클 E2E: build(분기 탐색) → generate(전 path) → docker-compose 기동 → 생성 테스트 실행
# Phase 1 메트릭: 같은 endpoint의 N개 path가 N개 테스트로 합성되고 전부 통과.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
E2E="$ROOT/e2e"
OUT="$E2E/out"
GW="$ROOT/gradlew"

echo "=== [1/5] SUT/서비스 jar 빌드 ==="
"$GW" -q :samples:order-service:bootJar :test-state-dashboard:bootJar :socket-mock-server:bootJar

echo "=== [2/5] 도구 1: 분기 탐색 + graph 빌드 (분석 환경: Testcontainers + JaCoCo) ==="
rm -rf "$OUT"
"$GW" -q :graph-rag-builder:run --args="build \
  --sut-src $ROOT/samples/order-service/src/main/java \
  --sut-resources $ROOT/samples/order-service/src/main/resources \
  --sut-jar $ROOT/samples/order-service/build/libs/order-service.jar \
  --out $OUT/graph \
  --sut-id order-service \
  --budget-requests 60 \
  --commit-sha $(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"

echo "=== [3/5] 도구 2: 전 path 테스트 생성 ==="
for req in request-orders request-search; do
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

docker compose -f "$E2E/docker-compose.yml" down -v --remove-orphans >/dev/null 2>&1 || true
docker compose -f "$E2E/docker-compose.yml" up -d --build

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
DASHBOARD_URL=http://localhost:58099 \
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
