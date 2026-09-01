#!/usr/bin/env bash
# A-EXT-HTTP-E2E: attach 모드 — SUT의 외부 HTTP 호출(EXTERNAL_INVENTORY_URL)이
# 빌더의 capture WireMock 으로 redirect 되어 graph.json httpCalls 에 캡처되는지 검증.
# (outer-loop acceptance test)
#
# 주의: 이 스크립트는 run-attach-e2e.sh / run-attach-otel-e2e.sh 와 동일한
# e2e/docker-compose.yml 의 고정 호스트 포트(postgres 56432, kafka 59092 등)를 공유하므로
# 이들과 동시 실행하면 안 된다 — 반드시 순차(SEQUENTIAL) 실행. (app-port/coverage-port/PROJECT/
# OUT 는 충돌 회피를 위해 다른 attach 스크립트와 다른 값을 쓴다.)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/e2e/.attach-exthttp-out"; PROJECT="grb-attach-order-exthttp"   # = "grb-attach-" + sutId(order-exthttp)
LOG="$OUT/builder.log"
cleanup() { docker compose -p "$PROJECT" -f "$ROOT/e2e/docker-compose.yml" \
  -f "$OUT/work/attach-override.yml" down -v >/dev/null 2>&1 || true; }
trap cleanup EXIT
rm -rf "$OUT"; mkdir -p "$OUT"

echo "=== [1/4] order-service jar + app 이미지 빌드 ==="
"$ROOT/gradlew" -q :samples:order-service:bootJar
docker compose -p "$PROJECT" -f "$ROOT/e2e/docker-compose.yml" build app

echo "=== [2/4] 빌더 attach 실행 (--external-stubs + --sut-env EXTERNAL_INVENTORY_URL={{wiremock}}) ==="
"$ROOT/gradlew" -q :graph-rag-builder:run --args="build \
  --sut-src $ROOT/samples/order-service/src/main/java \
  --sut-resources $ROOT/samples/order-service/src/main/resources \
  --sut-jar $ROOT/samples/order-service/build/libs/order-service.jar \
  --sut-compose $ROOT/e2e/docker-compose.yml \
  --out $OUT --sut-id order-exthttp \
  --attach --app-service app --app-port 58081 --coverage-port 16301 \
  --jdbc-url jdbc:postgresql://localhost:56432/app \
  --db-service postgres \
  --auth-login-path /api/auth/login --auth-user admin --auth-pass password \
  --external-stubs $ROOT/e2e/external-stubs \
  --sut-env EXTERNAL_INVENTORY_URL={{wiremock}}" 2>&1 | tee "$LOG"

echo "=== [3/4] 외부 HTTP 캡처 검증 ==="
python3 - "$OUT" <<'PY'
import json,sys,os,re
out=sys.argv[1]
g=json.load(open(os.path.join(out,"graph.json")))
http=g.get("httpCalls", [])
inv=[c for c in http if "inventory" in (c.get("urlPath") or "")]
assert inv, "no external inventory HTTP captured in attach mode"
# token must NOT leak into captured urlPath (token is 64 hex; order-service has no such segment)
assert not any(re.search(r"/[0-9a-f]{16,}/", c["urlPath"]) for c in inv), "token leaked into captured urlPath"
print("OK external http captured:", [c["urlPath"] for c in inv][:3])
PY

echo "=== [4/4] teardown 후 잔여 컨테이너 0 검증 ==="
cleanup
remaining="$(docker compose -p "$PROJECT" -f "$ROOT/e2e/docker-compose.yml" \
  -f "$OUT/work/attach-override.yml" ps -q | wc -l | tr -d ' ')"
[ "$remaining" = "0" ] || { echo "❌ 잔여 컨테이너 $remaining"; exit 1; }
echo "✅ ATTACH-EXT-HTTP-E2E PASS"
