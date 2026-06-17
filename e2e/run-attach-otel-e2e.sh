#!/usr/bin/env bash
# 수용-4 (plan Phase 6.2): attach 모드 + OTEL SQL 캡처 e2e.
# 사용자 docker-compose(e2e/docker-compose.yml) + 생성 override 로 컨테이너 SUT를 띄우고, 빌더가
# 호스트에 wildcard-bind + per-run secret 인증 OTLP 리시버를 띄운다. 컨테이너의 OTEL agent가
# host.docker.internal:<port> 로 span을 보내 SQL이 OTEL trace-id 귀속으로 캡처되는지(로그 폴백 아님)
# 검증한다. graph.json 에 SQL > 0 + 빌더 로그에 "otlp receiver" 활성 & 폴백 경고 0.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/e2e/.attach-otel-out"; PROJECT="grb-attach-order-otel"   # = "grb-attach-" + sutId(order-otel)
LOG="$OUT/builder.log"
cleanup() { docker compose -p "$PROJECT" -f "$ROOT/e2e/docker-compose.yml" \
  -f "$OUT/work/attach-override.yml" down -v >/dev/null 2>&1 || true; }
trap cleanup EXIT
rm -rf "$OUT"; mkdir -p "$OUT"

echo "=== [1/4] order-service jar + app 이미지 빌드 ==="
"$ROOT/gradlew" -q :samples:order-service:bootJar
docker compose -p "$PROJECT" -f "$ROOT/e2e/docker-compose.yml" build app

echo "=== [2/4] 빌더 attach 실행 (--sql-capture otel) ==="
"$ROOT/gradlew" -q :graph-rag-builder:run --args="build \
  --sut-src $ROOT/samples/order-service/src/main/java \
  --sut-resources $ROOT/samples/order-service/src/main/resources \
  --sut-jar $ROOT/samples/order-service/build/libs/order-service.jar \
  --sut-compose $ROOT/e2e/docker-compose.yml \
  --out $OUT --sut-id order-otel \
  --attach --app-service app --app-port 58080 --jacoco-port 16300 \
  --jdbc-url jdbc:postgresql://localhost:56432/app \
  --db-service postgres \
  --sql-capture otel" 2>&1 | tee "$LOG"

echo "=== [3/4] 그래프 + OTEL 경로 검증 ==="
python3 - "$OUT" <<'PY'
import json,sys,os
out=sys.argv[1]
g=json.load(open(os.path.join(out,"graph.json")))
assert len(g["sql"])>0, "no SQL captured (attach OTEL channel broken)"
r=json.load(open(os.path.join(out,"exploration-report.json")))
assert r["coveredAppBranches"]>0, "no branches covered (jacoco attach broken)"
print(f"OK endpoints={len(g['endpoints'])} sql={len(g['sql'])} coveredBranches={r['coveredAppBranches']}")
PY

# OTEL 경로가 실제로 동작했는지: 리시버 활성 로그 존재 + 폴백 경고 0.
grep -q "OTEL SQL capture (attach): otlp receiver" "$LOG" \
  || { echo "❌ OTLP 리시버 attach 로그 없음 (OTEL 모드 미동작)"; exit 1; }
if grep -qE "fell back to log-parser|entry span timeout" "$LOG"; then
  echo "❌ OTEL 폴백 발생 — 컨테이너→호스트 OTLP 도달 실패 가능"; grep -E "fell back to log-parser|entry span timeout" "$LOG"; exit 1
fi
echo "✅ OTEL 경로 확인 (리시버 활성 + 폴백 0)"

echo "=== [4/4] teardown 후 잔여 컨테이너 0 검증 ==="
cleanup
remaining="$(docker compose -p "$PROJECT" -f "$ROOT/e2e/docker-compose.yml" \
  -f "$OUT/work/attach-override.yml" ps -q | wc -l | tr -d ' ')"
[ "$remaining" = "0" ] || { echo "❌ 잔여 컨테이너 $remaining"; exit 1; }
echo "✅ ATTACH-OTEL-E2E PASS"
