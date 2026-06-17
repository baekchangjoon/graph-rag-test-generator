#!/usr/bin/env bash
# Phase D 수용 테스트. 현재 D-1(generator 이미지)만 커버한다.
#   D-E2E-1: generator Docker 이미지가 호스트에 Java 없이 graph.json → 테스트 .java 생성.
# graph.json은 이전 run-e2e.sh 산출물(e2e/out/graph)을 재사용한다(생성 자체는 도구 1 몫).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRAPH="$ROOT/e2e/out/graph"
IMG="graphrag/test-generator:docker-e2e"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

[ -f "$GRAPH/graph.json" ] || { echo "❌ graph.json 없음 — 먼저 ./e2e/run-e2e.sh로 생성"; exit 1; }

echo "=== [1/3] installDist + generator 이미지 빌드 ==="
"$ROOT/gradlew" -q :test-generator:installDist
docker build -f "$ROOT/docker/test-generator.Dockerfile" -t "$IMG" "$ROOT"

echo "=== [2/3] generator 이미지로 테스트 생성 (호스트 Java 미사용) ==="
cp -R "$GRAPH" "$STAGE/graph"
cp "$ROOT/e2e/request-orders.json" "$STAGE/req.json"
docker run --rm -v "$STAGE:/work" -w /work "$IMG" \
  generate --request /work/req.json --graph /work/graph --out /work/generated

echo "=== [3/3] 산출 검증 ==="
N=$(find "$STAGE/generated" -name "*.java" | wc -l | tr -d ' ')
[ "$N" -ge 1 ] || { echo "❌ D-E2E-1 실패 — .java 없음"; exit 1; }
echo "✅ DOCKER-E2E (D-1) PASS — generator 이미지가 .java=$N 생성"
