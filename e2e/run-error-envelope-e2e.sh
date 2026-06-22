#!/usr/bin/env bash
# Task 14 수용 E2E: 성공 오라클 + 에러 엔벨로프 (AC1~AC4)
#
# error-envelope-service SUT는 BizException 을 HTTP 200 + {errorCode,errorDetail} 엔벨로프로
# 응답한다(논리 404를 200 으로 감싼다). classifier 플래그를 켠 빌더가 이 엔벨로프를 FAILURE 로
# 분류하고, 생성기가 해당 path 에 에러 계약 단언(.statusCode(200)+errorCode equalTo("404")
# +errorDetail containsString("BizException"))을 내는지 검증한다. 시드된 id=1 의 genuine 200 은
# SUCCESS 로 남는지도 확인한다.
#
#   AC1: graph.json 의 not-found path 가 outcome==FAILURE, expectedStatus==200,
#        semanticStatusText=="404".
#   AC2: 생성된 .java 가 .statusCode(200) + .body("errorCode", equalTo("404"))
#        + org.hamcrest.Matchers.containsString("BizException").
#   AC3b: 시드 id 로 적어도 한 path 가 outcome==SUCCESS (genuine 200, 엔벨로프 아님).
#   AC4: 비-엔벨로프 SUT 무영향 — 기본 classifier 가 status-only 라 기존 run-e2e.sh/
#        run-gateway-e2e.sh 스위트가 그대로 커버한다(이 스크립트에서 무거운 스위트를 돌리지 않음).
#
# Exit 0 = AC1 && AC2 && AC3b 모두 PASS. 종료 시 컨테이너 정리(idempotent).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SAMPLE="$ROOT/samples/error-envelope-service"
OUT="$ROOT/e2e/.error-envelope-out"
COMPOSE="$ROOT/e2e/error-envelope-compose.yml"
LOG="$OUT/builder.log"
GW="$ROOT/gradlew"

# 빌더의 build 모드는 SUT 를 Testcontainers 로 띄우므로 호스트 컨테이너는 남지 않지만,
# 실패 경로에서 잔류 가능성에 대비해 라벨 기반 정리를 trap 으로 건다.
cleanup() {
  docker ps -aq --filter "label=org.testcontainers=true" 2>/dev/null \
    | xargs -r docker rm -f >/dev/null 2>&1 || true
}
trap cleanup EXIT
rm -rf "$OUT"; mkdir -p "$OUT"

echo "=== [1/4] error-envelope-service jar 빌드 ==="
"$GW" -q :samples:error-envelope-service:bootJar

JAR="$SAMPLE/build/libs/error-envelope-service.jar"
if [ ! -f "$JAR" ]; then
    echo "ERROR: error-envelope-service.jar not found at $JAR" >&2
    exit 1
fi

echo "=== [2/4] 도구 1: 분기 탐색 + graph 빌드 (classifier 플래그 ON) ==="
"$GW" -q :graph-rag-builder:run --args="build \
  --sut-src $SAMPLE/src/main/java \
  --sut-resources $SAMPLE/src/main/resources \
  --sut-jar $SAMPLE/build/libs/error-envelope-service.jar \
  --sut-compose $COMPOSE \
  --out $OUT \
  --sut-id error-envelope \
  --budget-requests 30 \
  --error-when-present errorCode \
  --semantic-status-field errorCode \
  --error-detail-field errorDetail \
  --error-detail-contains BizException \
  --commit-sha $(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)" 2>&1 | tee "$LOG"

echo "=== [3/4] graph.json 검증 (AC1 + AC3b) ==="
python3 - "$OUT/graph.json" <<'PY'
import json, sys
g = json.load(open(sys.argv[1]))
paths = [p for p in g["paths"] if p["endpointId"] == "get-items-id"]
assert paths, f"no paths for get-items-id (endpoints={[e.get('id') for e in g['endpoints']]})"

# AC1: 엔벨로프 not-found path — outcome FAILURE, 와이어 status 200, 의미 status "404".
ac1 = [p for p in paths
       if p["outcome"] == "FAILURE"
       and p["expectedStatus"] == 200
       and p["semanticStatusText"] == "404"]
assert ac1, ("AC1 FAIL: no get-items-id path with "
             "outcome==FAILURE && expectedStatus==200 && semanticStatusText=='404'. "
             f"paths={[(p['outcome'], p['expectedStatus'], p['semanticStatusText']) for p in paths]}")

# AC3b: 시드 id 로 genuine SUCCESS (엔벨로프 아님) path 가 최소 1개.
ac3b = [p for p in paths if p["outcome"] == "SUCCESS"]
assert ac3b, ("AC3b FAIL: no genuine SUCCESS path for get-items-id. "
              f"paths={[(p['outcome'], p['expectedStatus'], p['semanticStatusText']) for p in paths]}")

print(f"AC1 OK: enveloped FAILURE path id={ac1[0]['id']} "
      f"(status={ac1[0]['expectedStatus']}, semantic={ac1[0]['semanticStatusText']})")
print(f"AC3b OK: genuine SUCCESS path id={ac3b[0]['id']}")
PY

echo "=== [4/4] 도구 2: 테스트 생성 + 에러 계약 단언 검증 (AC2) ==="
"$GW" -q :test-generator:run --args="generate \
  --request $ROOT/e2e/request-items-get-id.json \
  --graph $OUT \
  --out $OUT/generated"

# bash 3.2 호환(macOS 기본 셸): mapfile 대신 while-read 누적
GEN_JAVA_FILES=()
while IFS= read -r f; do GEN_JAVA_FILES+=("$f"); done < <(find "$OUT/generated" -name "*.java")
[ "${#GEN_JAVA_FILES[@]}" -gt 0 ] || { echo "AC2 FAIL: 생성된 .java 없음"; exit 1; }
echo "생성된 테스트: ${GEN_JAVA_FILES[*]}"

ac2_fail=0
for needle in '.statusCode(200)' '.body("errorCode", equalTo("404"))' 'org.hamcrest.Matchers.containsString("BizException")'; do
  if ! grep -qF "$needle" "${GEN_JAVA_FILES[@]}"; then
    echo "AC2 FAIL: 생성 테스트에 누락 → $needle"
    ac2_fail=1
  fi
done
[ "$ac2_fail" -eq 0 ] || { echo "❌ AC2 FAIL"; exit 1; }
echo "AC2 OK: .statusCode(200) + errorCode equalTo(\"404\") + containsString(\"BizException\") 모두 존재"

echo ""
echo "AC4 NOTE: 비-엔벨로프 SUT 무영향은 기본 classifier 가 status-only 라 기존"
echo "          run-e2e.sh / run-gateway-e2e.sh 회귀 스위트가 그대로 커버한다(여기서 미실행)."
echo "✅ ERROR-ENVELOPE-E2E PASS — AC1 + AC2 + AC3b"
