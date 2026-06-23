#!/usr/bin/env bash
# =============================================================================
# sweep-petclinic-cross-class.sh  —  REQ-013 petclinic cross-class 변종 개방 스윕
#
# 목적:
#   Phase 2(cross-class 귀속) 구현 전·후 각각 builder를 돌려 petclinic의
#   get-api-reservations / get-api-reservations-id 엔드포인트에서
#     (a) list nights NUMERIC 분기 (ReservationService.list)
#     (b) getById check_in_date TEMPORAL 분기 (ReservationService.getById)
#   가 coveredAppBranches 에 들어왔는지를 비교한다.
#
# 사용법:
#   ./e2e/sweep-petclinic-cross-class.sh before   # Phase 2 구현 전
#   ./e2e/sweep-petclinic-cross-class.sh after    # Phase 2 구현 후
#   ./e2e/sweep-petclinic-cross-class.sh diff     # before/after JSON 비교
#
# 전제조건:
#   - ~/github_spring-petclinic/spring-petclinic/ 에 petclinic 소스 체크아웃
#   - petclinic jar 빌드 완료:
#       cd ~/github_spring-petclinic/spring-petclinic
#       ./gradlew bootJar   (또는 mvn package -DskipTests)
#   - Docker 실행 중 (builder가 Testcontainers로 DB 기동)
#   - graph-rag-builder 빌드:
#       ./gradlew :graph-rag-builder:classes  (워크트리 루트에서)
#
# 출력:
#   .work/sweep-before.json  — before 실행 결과
#   .work/sweep-after.json   — after 실행 결과
#   .work/sweep-before-report.json  — exploration-report.json (before)
#   .work/sweep-after-report.json   — exploration-report.json (after)
#
# 설계 참조: docs/superpowers/specs/2026-06-23-input-driven-seed-variants-design.md §6 R4
# REQ-013: 검증 레벨 = local sweep (외부 경로, CI 밖)
# =============================================================================
set -euo pipefail

# ---------------------------------------------------------------------------
# 경로 설정
# ---------------------------------------------------------------------------
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GW="$ROOT/gradlew"
WORK="$ROOT/.work"

PETCLINIC_ROOT="${PETCLINIC_ROOT:-$HOME/github_spring-petclinic/spring-petclinic}"
PETCLINIC_SRC="$PETCLINIC_ROOT/src/main/java"
PETCLINIC_RESOURCES="$PETCLINIC_ROOT/src/main/resources"
# Gradle bootJar 우선, Maven jar 폴백
# Finding 5: jar 버전 하드코딩 제거 — env override + glob 탐색
PETCLINIC_JAR="${PETCLINIC_JAR:-$(ls "$PETCLINIC_ROOT"/build/libs/spring-petclinic-*.jar 2>/dev/null | head -1)}"
if [ -z "$PETCLINIC_JAR" ]; then
  PETCLINIC_JAR="${PETCLINIC_ROOT}/target/$(ls "$PETCLINIC_ROOT"/target/spring-petclinic-*.jar 2>/dev/null | head -1 | xargs -I{} basename {})"
fi

PETCLINIC_COMPOSE="$PETCLINIC_ROOT/docker-compose.yml"

# 탐색 대상 엔드포인트 (쉼표 구분, --endpoint 옵션에 전달)
ENDPOINTS="get-api-reservations,get-api-reservations-id"

# ReservationService FQN (BranchRef.classFqn 필터 기준)
RESERVATION_SERVICE="org.springframework.samples.petclinic.boarding.ReservationService"

# ---------------------------------------------------------------------------
# 사용법
# ---------------------------------------------------------------------------
usage() {
  echo "Usage: $0 <before|after|diff>"
  echo ""
  echo "  before  Phase 2 구현 전 builder 탐색 실행 → .work/sweep-before.json"
  echo "  after   Phase 2 구현 후 builder 탐색 실행 → .work/sweep-after.json"
  echo "  diff    before/after 비교 (Python3 필요)"
  exit 1
}

[ $# -lt 1 ] && usage
PHASE="$1"

# ---------------------------------------------------------------------------
# 전제조건 검증
# ---------------------------------------------------------------------------
check_prereqs() {
  local jar_path="$PETCLINIC_JAR"
  local src_path="$PETCLINIC_SRC"
  local compose_path="$PETCLINIC_COMPOSE"

  if [ ! -f "$jar_path" ]; then
    echo "[ERROR] petclinic jar not found: $jar_path"
    echo "  빌드 방법 (Gradle): cd $PETCLINIC_ROOT && ./gradlew bootJar"
    echo "  빌드 방법 (Maven):  cd $PETCLINIC_ROOT && ./mvnw package -DskipTests"
    exit 1
  fi
  if [ ! -d "$src_path" ]; then
    echo "[ERROR] petclinic src not found: $src_path"
    echo "  PETCLINIC_ROOT env var로 경로 지정 가능: PETCLINIC_ROOT=/path/to/spring-petclinic $0 $PHASE"
    exit 1
  fi
  if [ ! -f "$compose_path" ]; then
    echo "[ERROR] petclinic docker-compose.yml not found: $compose_path"
    exit 1
  fi
  if ! command -v docker &>/dev/null; then
    echo "[ERROR] Docker not found. Docker is required for Testcontainers."
    exit 1
  fi
}

# ---------------------------------------------------------------------------
# builder 탐색 실행
# ---------------------------------------------------------------------------
run_sweep() {
  local label="$1"   # "before" or "after"
  local out_dir="$WORK/sweep-${label}-graph"
  local result_json="$WORK/sweep-${label}.json"
  local report_json="$WORK/sweep-${label}-report.json"

  check_prereqs

  echo "=== [sweep:${label}] graph-rag-builder로 petclinic 탐색 시작 ==="
  echo "  petclinic jar:  $PETCLINIC_JAR"
  echo "  petclinic src:  $PETCLINIC_SRC"
  echo "  endpoints:      $ENDPOINTS"
  echo "  out dir:        $out_dir"
  echo ""

  mkdir -p "$WORK"
  rm -rf "$out_dir"

  # Finding 3: 공백 경로 안전 전달 — Gradle --args 단일 문자열 한계로 경로 내 공백은
  # 인용할 수 없음. 경로에 공백이 있으면 오류 출력 후 중단.
  for _path_check in "$PETCLINIC_SRC" "$PETCLINIC_RESOURCES" "$PETCLINIC_JAR" "$out_dir" "$PETCLINIC_COMPOSE"; do
    if [[ "$_path_check" == *" "* ]]; then
      echo "[ERROR] 경로에 공백이 포함돼 있어 Gradle --args로 전달할 수 없습니다: $_path_check"
      echo "  PETCLINIC_ROOT 경로에 공백이 없도록 심볼릭 링크 등을 활용하세요."
      exit 1
    fi
  done

  _commit_sha="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo "sweep-${label}")"

  # builder 실행
  # --sut-compose: petclinic docker-compose.yml (Postgres DB 서비스 포함)
  # --auth-login-path: petclinic JWT 로그인 엔드포인트
  # --endpoint: 대상 엔드포인트만 탐색 (빠른 스윕용)
  # --budget-requests 30: 탐색 예산 축소 (스윕 특화)
  # --trace-mode none: SQL 캡처 생략, 탐색 속도 우선
  # --db-service postgres: docker-compose.yml에서 postgres 서비스 선택
  "$GW" -q :graph-rag-builder:run --args="build \
    --sut-src $PETCLINIC_SRC \
    --sut-resources $PETCLINIC_RESOURCES \
    --sut-jar $PETCLINIC_JAR \
    --out $out_dir \
    --sut-id petclinic \
    --sut-compose $PETCLINIC_COMPOSE \
    --db-service postgres \
    --auth-login-path /api/auth/login \
    --auth-user admin \
    --auth-pass password \
    --auth-token-field token \
    --endpoint $ENDPOINTS \
    --budget-requests 30 \
    --trace-mode none \
    --commit-sha $_commit_sha"

  echo ""
  echo "=== [sweep:${label}] 탐색 완료 — ReservationService 분기 추출 ==="

  # exploration-report.json에서 ReservationService 분기 추출
  python3 - "$out_dir/exploration-report.json" "$result_json" "$report_json" "$RESERVATION_SERVICE" "$ENDPOINTS" <<'PYEOF'
import json, sys, pathlib

report_path  = sys.argv[1]
result_path  = sys.argv[2]
raw_path     = sys.argv[3]
service_fqn  = sys.argv[4]
endpoint_ids = set(sys.argv[5].split(","))

data = json.loads(pathlib.Path(report_path).read_text())

# exploration-report.json 전체를 raw로 저장
pathlib.Path(raw_path).write_text(json.dumps(data, indent=2, ensure_ascii=False))

# 대상 endpoint 필터링
target_eps = [ep for ep in data.get("endpoints", [])
              if ep.get("endpointId") in endpoint_ids]

# ReservationService 분기 추출 (coveredBranches에는 BranchRef가 없음;
# missedBranches에서 역으로 "covered되지 않은" 분기 확인)
# 스윕 목적: missed 분기 중 ReservationService 분야 라인을 집계
result = {
    "coveredAppBranches": data.get("coveredAppBranches"),
    "totalAppBranches":   data.get("totalAppBranches"),
    "endpoints": {}
}

for ep in target_eps:
    eid = ep["endpointId"]
    missed = ep.get("missedBranches", [])
    svc_missed = [b for b in missed if b.get("classFqn") == service_fqn]
    result["endpoints"][eid] = {
        "coveredBranches": ep.get("coveredBranches", 0),
        "totalBranches":   ep.get("totalBranches", 0),
        "missedBranchesAll": len(missed),
        "missedReservationService": svc_missed,
    }

pathlib.Path(result_path).write_text(json.dumps(result, indent=2, ensure_ascii=False))
print(json.dumps(result, indent=2, ensure_ascii=False))
PYEOF

  echo ""
  echo "결과 저장:"
  echo "  $result_json     (ReservationService 분기 요약)"
  echo "  $report_json     (exploration-report.json 전체)"
}

# ---------------------------------------------------------------------------
# before / after 비교 출력
# ---------------------------------------------------------------------------
run_diff() {
  local before_json="$WORK/sweep-before.json"
  local after_json="$WORK/sweep-after.json"

  if [ ! -f "$before_json" ] || [ ! -f "$after_json" ]; then
    echo "[ERROR] before/after 파일이 없습니다. 먼저 실행하세요:"
    echo "  $0 before"
    echo "  $0 after"
    exit 1
  fi

  python3 - "$before_json" "$after_json" <<'PYEOF'
import json, sys

LIST_EID = "get-api-reservations"
ID_EID   = "get-api-reservations-id"

before = json.loads(open(sys.argv[1]).read())
after  = json.loads(open(sys.argv[2]).read())

print("=== REQ-013 before/after diff ===")
print(f"  coveredAppBranches: {before['coveredAppBranches']} → {after['coveredAppBranches']}")
print(f"  totalAppBranches:   {before['totalAppBranches']} → {after['totalAppBranches']}")
print()

# Finding 2: after에 대상 endpoint 키가 없으면 false-positive PASS 방지
inconclusive_eids = []
for eid in [LIST_EID, ID_EID]:
    if eid not in after.get("endpoints", {}):
        print(f"[WARN] after JSON에 endpoint '{eid}' 없음 — 탐색 실패 또는 endpoint 미매칭. 판정 INCONCLUSIVE.")
        inconclusive_eids.append(eid)

for eid in before.get("endpoints", {}):
    b_ep = before["endpoints"].get(eid, {})
    a_ep = after["endpoints"].get(eid, {})
    print(f"[{eid}]")
    print(f"  coveredBranches:             {b_ep.get('coveredBranches')} → {a_ep.get('coveredBranches')}")
    print(f"  totalBranches:               {b_ep.get('totalBranches')} → {a_ep.get('totalBranches')}")
    print(f"  missedReservationService:    {len(b_ep.get('missedReservationService',[]))} → {len(a_ep.get('missedReservationService',[]))}")

    b_missed = {(m["method"], m["line"], m["branchIndex"])
                for m in b_ep.get("missedReservationService", [])}
    a_missed = {(m["method"], m["line"], m["branchIndex"])
                for m in a_ep.get("missedReservationService", [])}
    newly_covered = b_missed - a_missed

    if newly_covered:
        print(f"  ✅ 새로 커버된 ReservationService 분기 (missed→covered):")
        for m, line, bi in sorted(newly_covered):
            print(f"     method={m}  line={line}  branchIndex={bi}")
    else:
        print(f"  ❌ ReservationService 분기 변화 없음 (before={b_missed})")
    print()

# REQ-013 판정
# Finding 7: passed_list 미사용 변수 제거
get_list_ep = after.get("endpoints", {}).get(LIST_EID, {})
get_id_ep   = after.get("endpoints", {}).get(ID_EID, {})

b_list_missed = {(m["method"], m["line"], m["branchIndex"])
                 for m in before.get("endpoints", {}).get(LIST_EID, {}).get("missedReservationService", [])}
a_list_missed = {(m["method"], m["line"], m["branchIndex"])
                 for m in get_list_ep.get("missedReservationService", [])}
list_newly_covered = b_list_missed - a_list_missed

b_id_missed = {(m["method"], m["line"], m["branchIndex"])
               for m in before.get("endpoints", {}).get(ID_EID, {}).get("missedReservationService", [])}
a_id_missed = {(m["method"], m["line"], m["branchIndex"])
               for m in get_id_ep.get("missedReservationService", [])}
id_newly_covered = b_id_missed - a_id_missed

# Finding 2: inconclusive endpoint가 있으면 해당 판정 FAIL
if LIST_EID in inconclusive_eids:
    list_ok = False
    list_inconclusive = True
else:
    # Finding 4: substring 대신 정확 매칭 (m == "list")
    list_ok = any(m == "list" for (m, _, _) in list_newly_covered)
    list_inconclusive = False

if ID_EID in inconclusive_eids:
    id_ok = False
    id_inconclusive = True
else:
    id_ok = any(m == "getById" for (m, _, _) in id_newly_covered)
    id_inconclusive = False

print("=== REQ-013 판정 ===")
list_label = "INCONCLUSIVE (after에 endpoint 없음)" if list_inconclusive else ("✅ PASS" if list_ok else "❌ FAIL / 변화없음")
id_label   = "INCONCLUSIVE (after에 endpoint 없음)" if id_inconclusive   else ("✅ PASS" if id_ok   else "❌ FAIL / 변화없음")
print(f"  (a) list nights NUMERIC 분기 missed→covered: {list_label}")
print(f"  (b) getById check_in_date TEMPORAL 분기 missed→covered: {id_label}")
print()
if list_ok and id_ok:
    print("REQ-013: 🟢 GREEN (cross-class 귀속으로 petclinic 계층형 SUT 변종 개방 확인)")
else:
    print("REQ-013: 🔴 FAIL (분기 변화 확인 안됨 — 탐색 로그/graph 직접 점검 필요)")
    sys.exit(1)
PYEOF
}

# ---------------------------------------------------------------------------
# 메인
# ---------------------------------------------------------------------------
case "$PHASE" in
  before|after) run_sweep "$PHASE" ;;
  diff)         run_diff ;;
  *)            usage ;;
esac
