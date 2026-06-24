#!/usr/bin/env bash
# REQ-005 E2E: --endpoint glob scopes EXPLORATION; static endpoints list stays FULL.
# REQ-020: unique compose project name; trap cleans ONLY this run's containers.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
E2E="$ROOT/e2e"
OUT="$E2E/out-epglob"
PROJ="grb-epglob-$$"

trap 'docker compose -p "$PROJ" -f "$E2E/docker-compose.yml" down -v --remove-orphans >/dev/null 2>&1 || true' EXIT INT TERM

echo "=== [1/3] SUT jar 빌드 ==="
"$ROOT/gradlew" -q :samples:order-service:bootJar :e2e:copyOtelAgent

echo "=== [2/3] --endpoint glob 탐색 빌드 (POST /api/orders/** 만 탐색) ==="
rm -rf "$OUT"
COMPOSE_PROJECT_NAME="$PROJ" "$ROOT/gradlew" -q :graph-rag-builder:run --args="build \
  --sut-src $ROOT/samples/order-service/src/main/java \
  --sut-resources $ROOT/samples/order-service/src/main/resources \
  --sut-jar $ROOT/samples/order-service/build/libs/order-service.jar \
  --out $OUT/graph \
  --sut-id order-service \
  --budget-requests 25 \
  --sut-compose $E2E/docker-compose.yml \
  --endpoint 'POST /api/orders/**'"

GRAPH="$OUT/graph/graph.json"

echo "=== [3/3] graph.json 어설션 ==="
echo "--- graph.json 최상위 키 ---"
jq 'keys' "$GRAPH"
echo "--- endpoints[0] ---"
jq '.endpoints[0]' "$GRAPH"
echo "--- paths[0] ---"
jq '.paths[0] // "no paths"' "$GRAPH"

# 정적 endpoints 수 (소스 인덱싱 전체 — HTTP + WS; wsEndpoints 포함)
STATIC_HTTP=$(jq '.endpoints | length' "$GRAPH")
STATIC_WS=$(jq '.wsEndpoints | length' "$GRAPH")
STATIC=$((STATIC_HTTP + STATIC_WS))

# 탐색된 path 가 귀속된 고유 endpointId 수 (글로브 매칭 엔드포인트만)
EXPLORED=$(jq '[.paths[].endpointId] | unique | length' "$GRAPH")

echo "정적 HTTP endpoints=$STATIC_HTTP  WS endpoints=$STATIC_WS  합계=$STATIC"
echo "탐색된 endpoint 그룹 수=$EXPLORED"

# REQ-005: 정적 목록(전체 endpoint 수) > 탐색된 endpoint 그룹 수
# (glob 이 일부만 매칭하므로 static이 더 많아야 한다)
[ "$STATIC" -gt "$EXPLORED" ] || {
  echo "FAIL REQ-005: 정적 목록(${STATIC})이 탐색 그룹(${EXPLORED})보다 크지 않음"
  exit 1
}

# 탐색된 path 가 실제로 존재해야 한다 (glob 매칭 endpoint가 적어도 하나 탐색됨)
[ "$EXPLORED" -gt 0 ] || {
  echo "FAIL: 탐색된 endpoint가 없음 — glob 매칭 실패"
  exit 1
}

echo "PASS REQ-005: static=${STATIC} > explored=${EXPLORED}"
