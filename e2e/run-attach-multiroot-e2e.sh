#!/usr/bin/env bash
# A-MR-E2E: attach 모드 + 멀티 루트 --sut-src.
#
# 목적: PR #94/#97의 멀티 루트 --sut-src 가 attach 경로에서도 동작함을 직접 검증한다.
#   --sut-src 해석(SutSrcResolver)은 분석/attach 공통 경로라 멀티 루트가 attach에서도 동작하지만,
#   요구사항명세 N4(의도적 deferred)로 직접 검증하는 E2E 가 없었다.
#
# 방법: --sut-jar 는 전체 order-service.jar(앱 전부 부팅)인 반면 --sut-src 는 정적 인덱싱만 구동한다
#   (둘은 분리된 경로다 — docs/26-attach-mode.md). 따라서 전체 앱을 부팅하되 --sut-src 로 두 형제
#   컨트롤러만 골라, 산출 graph.json 의 정적 .endpoints 가 선택 루트의 엔드포인트만 담은 "부분 그래프"
#   임을 양성·음성 양쪽으로 단언한다.
#   order-service 의 패키지 트리는 평평(컨트롤러가 orders/ 에 직접)하고 형제 서브패키지가 auth 하나뿐
#   이라, 두 컨트롤러(AuthController, ProfileController)를 각각 임시 소스 루트로 격리해 멀티 루트로
#   선택한다(Spoon noClasspath 라 미해소 참조는 무시되고 @*Mapping 경로만 추출됨).
#
# REQ-020(자원 정리): 고유 compose project(grb-attach-mr-$$) + trap 으로 EXIT/INT/TERM 모두에서
#   down -v --remove-orphans. 무차별 prune/pkill 금지. 스위트 종료 후 자기 컨테이너 잔존 0.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
E2E="$ROOT/e2e"
RUN_ID="mr-$$"                          # builder 의 compose project = "grb-attach-" + sutId
PROJECT="grb-attach-$RUN_ID"
OUT="$E2E/out/attach-multiroot"          # e2e/out/ 은 gitignore(out/ 패턴)
SUT_SRC="$ROOT/samples/order-service/src/main/java"
PKG="io/graphrag/sample/orders"

cleanup() {
  docker compose -p "$PROJECT" -f "$E2E/docker-compose.yml" \
    -f "$OUT/work/attach-override.yml" down -v --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

rm -rf "$OUT"; mkdir -p "$OUT"

echo "=== [1/5] order-service jar + otel agent + app 이미지 빌드 ==="
"$ROOT/gradlew" -q :samples:order-service:bootJar :e2e:copyOtelAgent
docker compose -p "$PROJECT" -f "$E2E/docker-compose.yml" build app

echo "=== [2/5] 멀티 루트 임시 소스 구성 (형제 컨트롤러 2개를 각각 루트로 격리) ==="
# 루트 A: AuthController 만   → POST /api/auth/login
# 루트 B: ProfileController 만 → GET  /api/profiles/by-name/{name}
# orders/ 평면에 있는 다른 형제 컨트롤러(/api/orders, /api/bookings, /api/coupons …)는 어느 루트에도
# 없으므로 정적 그래프에서 부재해야 한다.
ROOT_A="$OUT/mr/a"; ROOT_B="$OUT/mr/b"
mkdir -p "$ROOT_A/$PKG/auth" "$ROOT_B/$PKG"
cp "$SUT_SRC/$PKG/auth/AuthController.java" "$ROOT_A/$PKG/auth/"
cp "$SUT_SRC/$PKG/ProfileController.java"   "$ROOT_B/$PKG/"

echo "=== [3/5] 빌더 attach 실행 (멀티 루트 --sut-src '<A>,<B>') ==="
"$ROOT/gradlew" -q :graph-rag-builder:run --args="build \
  --sut-src $ROOT_A,$ROOT_B \
  --sut-resources $ROOT/samples/order-service/src/main/resources \
  --sut-jar $ROOT/samples/order-service/build/libs/order-service.jar \
  --sut-compose $E2E/docker-compose.yml \
  --out $OUT --sut-id $RUN_ID \
  --budget-requests 6 \
  --attach --app-service app --app-port 58080 --jacoco-port 16300 \
  --jdbc-url jdbc:postgresql://localhost:56432/app \
  --db-service postgres \
  --auth-login-path /api/auth/login --auth-user admin --auth-pass password"

GRAPH="$OUT/graph.json"

echo "=== [4/5] 부분 그래프 어설션 (정적 .endpoints) ==="
[ -f "$GRAPH" ] || { echo "❌ graph.json 없음: $GRAPH"; exit 1; }
echo "--- graph.json keys ---"; jq 'keys' "$GRAPH"
echo "--- endpoints ---"; jq '[.endpoints[] | {httpMethod, path}]' "$GRAPH"

python3 - "$GRAPH" <<'PY'
import json, sys
g = json.load(open(sys.argv[1]))
eps = g.get("endpoints", [])
paths = {e.get("path") for e in eps}

AUTH = "/api/auth/login"
PROFILE = "/api/profiles/by-name/{name}"

# 양성: 선택한 두 루트의 엔드포인트가 모두 존재
assert AUTH in paths,    f"선택 루트 A 엔드포인트 부재: {AUTH} not in {sorted(paths)}"
assert PROFILE in paths, f"선택 루트 B 엔드포인트 부재: {PROFILE} not in {sorted(paths)}"

# 음성: 선택하지 않은 형제 컨트롤러 엔드포인트는 부재(부분 그래프)
FORBIDDEN_PREFIXES = ("/api/orders", "/api/bookings", "/api/coupons",
                      "/api/promo", "/api/pricing", "/api/signups")
leaked = sorted(p for p in paths for pre in FORBIDDEN_PREFIXES if p and p.startswith(pre))
assert not leaked, f"비선택 형제 엔드포인트 누출(부분 그래프 아님): {leaked}"

# 정적 목록이 전체 인덱싱으로 collapse 되지 않았는지(멀티 루트 격리가 실제로 좁혔는지) 확인.
# order-service 전체는 ~40+ 엔드포인트 — 선택 루트 2개면 소수여야 한다.
assert len(eps) <= 4, f"정적 endpoints 가 너무 많음({len(eps)}) — 멀티 루트가 좁히지 못함(전체 인덱싱 회귀?)"

print(f"OK partial graph: endpoints={len(eps)} paths={sorted(paths)}")
PY

echo "=== [5/5] teardown 후 잔여 컨테이너 0 검증 ==="
cleanup
"$E2E/check-no-leak.sh" "$PROJECT"
echo "✅ ATTACH-MULTIROOT-E2E PASS"
