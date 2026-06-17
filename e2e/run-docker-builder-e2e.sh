#!/usr/bin/env bash
# Phase D-2 수용 테스트(D-E2E-2): builder 이미지가 컨테이너 안에서 Testcontainers + SUT 프로세스로
# graph.json을 생성한다. **Linux 전용**(--network host로 SUT↔Testcontainers DB↔JaCoCo TCP가 localhost
# 공유). macOS/Windows Docker Desktop은 --network host 미지원 → CI(ubuntu)에서 실측.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
E2E="$ROOT/e2e"
IMG="graphrag/graph-rag-builder:docker-e2e"
OUT="$(mktemp -d)"
# builder 컨테이너는 root로 /out에 쓴다 → 호스트(non-root)의 rm이 root 소유 파일을 못 지운다.
# 검증은 cleanup 전에 끝나므로, 임시 디렉터리 정리 실패가 잡을 깨지 않게 한다(CI 러너는 곧 폐기).
trap 'rm -rf "$OUT" 2>/dev/null || true' EXIT

echo "=== [1/3] SUT jar + builder installDist + 이미지 빌드 ==="
"$ROOT/gradlew" -q :samples:order-service:bootJar :graph-rag-builder:installDist
docker build -f "$ROOT/docker/graph-rag-builder.Dockerfile" -t "$IMG" "$ROOT"

echo "=== [2/3] builder 이미지로 분석 (docker.sock + --network host) ==="
docker run --rm \
  --network host \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_RYUK_DISABLED=true \
  -v "$ROOT/samples/order-service:/sut:ro" \
  -v "$E2E/external-stubs:/stubs:ro" \
  -v "$E2E/docker-compose.yml:/compose.yml:ro" \
  -v "$OUT:/out" \
  "$IMG" build \
    --sut-src /sut/src/main/java \
    --sut-resources /sut/src/main/resources \
    --sut-jar /sut/build/libs/order-service.jar \
    --out /out/graph \
    --sut-id order-service \
    --with-kafka \
    --budget-requests 20 \
    --external-stubs /stubs \
    --sut-env "EXTERNAL_INVENTORY_URL={{wiremock}}" \
    --sut-compose /compose.yml \
    --auth-login-path /api/auth/login --auth-user admin --auth-pass password

echo "=== [3/3] graph.json 검증 (엔드포인트가 실제로 인덱싱됐는지) ==="
[ -f "$OUT/graph/graph.json" ] || { echo "❌ D-E2E-2 실패 — graph.json 없음"; exit 1; }
grep -q '"endpoints"' "$OUT/graph/graph.json" || { echo "❌ D-E2E-2 실패 — graph.json에 endpoints 없음"; exit 1; }
echo "✅ DOCKER-BUILDER-E2E (D-2) PASS — builder 이미지가 컨테이너 안에서 graph.json 생성"
