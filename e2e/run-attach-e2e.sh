#!/usr/bin/env bash
# A-E2E: attach 모드 — 사용자 docker-compose(e2e/docker-compose.yml) + 생성 override 로 SUT를 띄우고
# 빌더가 attach 분석. graph.json 에 핵심 엔드포인트/SQL, exploration-report 에 커버 분기 검증.
# 인증(--auth-*)을 줘서 JwtAuthFilter 통과 → 깊은 탐색(얕으면 전 요청 403/소수 분기에 머무름).
# 임계값(분기>=50, sql>=20)은 깊은 탐색(분석 모드와 동일 ~140 분기/~53 sql)과 얕은 탐색(~4/~3)을 구분.
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
  --db-service postgres \
  --auth-login-path /api/auth/login --auth-user admin --auth-pass password"

echo "=== [3/4] 그래프 검증 ==="
python3 - "$OUT" <<'PY'
import json,sys,os
out=sys.argv[1]
g=json.load(open(os.path.join(out,"graph.json")))
assert any(e["httpMethod"]=="POST" for e in g["endpoints"]), "no POST endpoint"
# 깊은 탐색 확인: 인증 통과 시 ~53 sql / ~140 분기. 얕으면(전 요청 403) ~3/~4 → 회귀 검출.
sql=len(g["sql"])
assert sql>=20, f"shallow exploration: only {sql} sql (auth/탐색 깊이 회귀? 깊으면 ~53)"
r=json.load(open(os.path.join(out,"exploration-report.json")))
br=r["coveredAppBranches"]
assert br>=50, f"shallow exploration: only {br} branches (auth/jacoco attach 회귀? 깊으면 ~140)"
print(f"OK endpoints={len(g['endpoints'])} sql={sql} coveredBranches={br}")
PY

echo "=== [4/4] teardown 후 잔여 컨테이너 0 검증 ==="
cleanup
remaining="$(docker compose -p "$PROJECT" -f "$ROOT/e2e/docker-compose.yml" \
  -f "$OUT/work/attach-override.yml" ps -q | wc -l | tr -d ' ')"
[ "$remaining" = "0" ] || { echo "❌ 잔여 컨테이너 $remaining"; exit 1; }
echo "✅ ATTACH-E2E PASS"
