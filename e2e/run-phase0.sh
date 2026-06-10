#!/usr/bin/env bash
# Phase 0 전 사이클: build(도구1) → generate(도구2) → docker-compose 기동 → 생성 테스트 실행
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
E2E="$ROOT/e2e"
OUT="$E2E/out"
GW="$ROOT/gradlew"

echo "=== [1/5] SUT/서비스 jar 빌드 ==="
"$GW" -q :samples:order-service:bootJar :test-state-dashboard:bootJar :socket-mock-server:bootJar

echo "=== [2/5] 도구 1: graph 빌드 (분석 환경: Testcontainers) ==="
rm -rf "$OUT"
"$GW" -q :graph-rag-builder:run --args="build \
  --sut-src $ROOT/samples/order-service/src/main/java \
  --sut-jar $ROOT/samples/order-service/build/libs/order-service.jar \
  --out $OUT/graph \
  --sut-id order-service \
  --commit-sha $(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"

echo "=== [3/5] 도구 2: 테스트 생성 ==="
"$GW" -q :test-generator:run --args="generate \
  --request $E2E/request.json \
  --graph $OUT/graph \
  --out $OUT/generated"

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

echo "=== [5/5] 정리 ==="
docker compose -f "$E2E/docker-compose.yml" down -v

if [ $TEST_EXIT -eq 0 ]; then
  echo "✅ Phase 0 E2E PASS"
else
  echo "❌ Phase 0 E2E FAIL (exit=$TEST_EXIT)"
fi
exit $TEST_EXIT
