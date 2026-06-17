#!/usr/bin/env bash
# A-E2E: attach 모드 — 사용자 docker-compose(e2e/docker-compose.yml) + 생성 override 로 SUT를 띄우고
# 빌더가 attach 분석. graph.json 에 핵심 엔드포인트/SQL, exploration-report 에 커버 분기 > 0 검증.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/e2e/.attach-out"; PROJECT="grb-attach-order"   # = "grb-attach-" + sutId(order)
cleanup() { docker compose -p "$PROJECT" -f "$ROOT/e2e/docker-compose.yml" \
  -f "$OUT/work/attach-override.yml" down -v >/dev/null 2>&1 || true; }
trap cleanup EXIT
rm -rf "$OUT"; mkdir -p "$OUT"

echo "=== [1/4] order-service jar(인덱싱/분기/지문에 필수) + app 이미지 빌드 ==="
"$ROOT/gradlew" -q :samples:order-service:bootJar
docker compose -p "$PROJECT" -f "$ROOT/e2e/docker-compose.yml" build app

echo "=== [2/4] 빌더 attach 실행 ==="
"$ROOT/gradlew" -q :graph-rag-builder:run --args="build \
  --sut-src $ROOT/samples/order-service/src/main/java \
  --sut-resources $ROOT/samples/order-service/src/main/resources \
  --sut-jar $ROOT/samples/order-service/build/libs/order-service.jar \
  --sut-compose $ROOT/e2e/docker-compose.yml \
  --out $OUT --sut-id order \
  --attach --app-service app --app-port 58080 --jacoco-port 16300 \
  --jdbc-url jdbc:postgresql://localhost:56432/app \
  --db-service postgres"

echo "=== [3/4] 그래프 검증 ==="
python3 - "$OUT" <<'PY'
import json,sys,os
out=sys.argv[1]
g=json.load(open(os.path.join(out,"graph.json")))
assert any(e["httpMethod"]=="POST" for e in g["endpoints"]), "no POST endpoint"
assert len(g["sql"])>0, "no SQL captured (bind-value channel broken)"
r=json.load(open(os.path.join(out,"exploration-report.json")))
assert r["coveredAppBranches"]>0, "no branches covered (jacoco attach broken)"
print(f"OK endpoints={len(g['endpoints'])} sql={len(g['sql'])} coveredBranches={r['coveredAppBranches']}")
PY

echo "=== [4/4] teardown 후 잔여 컨테이너 0 검증 ==="
cleanup
remaining="$(docker compose -p "$PROJECT" -f "$ROOT/e2e/docker-compose.yml" \
  -f "$OUT/work/attach-override.yml" ps -q | wc -l | tr -d ' ')"
[ "$remaining" = "0" ] || { echo "❌ 잔여 컨테이너 $remaining"; exit 1; }
echo "✅ ATTACH-E2E PASS"
