#!/usr/bin/env bash
# gate-p2-5.sh — P2-5 병렬 set-동등 하드게이트 + speedup (REQ-P004, REQ-P009)
#
# 목적:
#   --parallelism 4(와 --parallelism 8)로 생성된 graph.json이
#   --parallelism 1(sequential baseline) graph.json과 SET-EQUIVALENT임을 증명한다.
#   무사고(race/seed충돌 0) 확인 + 벽시계 speedup 기록.
#
# 사전 조건:
#   - order-service.jar 존재 (./gradlew :samples:order-service:bootJar)
#   - graph-rag-builder 빌드/testClasses 완료
#   - Docker 기동 중 (Testcontainers)
#   - pjacoco publishToMavenLocal 완료
#
# 사용법:
#   ./e2e/parallel/gate-p2-5.sh [--skip-seq] [--seq-graph <path>] [--par4 | --par4-and-8]
#
#   --skip-seq        순차 빌드를 생략하고 --seq-graph 경로를 기존 baseline으로 사용
#   --seq-graph <p>   기존 sequential graph.json 경로 (--skip-seq와 함께 사용)
#   --par4            N=4 병렬 게이트만 실행 (기본)
#   --par4-and-8      N=4 + N=8 두 게이트 모두 실행

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
GW="$REPO_ROOT/gradlew"

# ─── 옵션 파싱 ────────────────────────────────────────────────────────────
SKIP_SEQ=false
SEQ_GRAPH_OVERRIDE=""
RUN_PAR8=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-seq)     SKIP_SEQ=true; shift ;;
    --seq-graph)    SEQ_GRAPH_OVERRIDE="$2"; shift 2 ;;
    --par4)         RUN_PAR8=false; shift ;;
    --par4-and-8)   RUN_PAR8=true; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

OUT_ROOT="$REPO_ROOT/e2e/gate-p2-5"
mkdir -p "$OUT_ROOT"

SEQ_OUT="$OUT_ROOT/seq_run"
PAR4_OUT="$OUT_ROOT/par4_run"
PAR8_OUT="$OUT_ROOT/par8_run"

# 공통 빌더 args (--coverage-backend, --parallelism, --out은 각 run에서 지정)
COMMON_ARGS="build \
  --sut-src $REPO_ROOT/samples/order-service/src/main/java \
  --sut-resources $REPO_ROOT/samples/order-service/src/main/resources \
  --sut-jar $REPO_ROOT/samples/order-service/build/libs/order-service.jar \
  --sut-id order-service \
  --with-kafka \
  --budget-requests 60 \
  --external-stubs $REPO_ROOT/e2e/external-stubs \
  --sut-env EXTERNAL_INVENTORY_URL={{wiremock}} \
  --sut-compose $REPO_ROOT/e2e/docker-compose.yml \
  --auth-login-path /api/auth/login \
  --auth-user admin \
  --auth-pass password \
  --commit-sha $(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"

# ─── testClasses 빌드 (GraphSetEquivDiffTool 용) ──────────────────────────
echo "=== [0/4] graph-rag-builder testClasses 빌드 ==="
"$GW" -q -p "$REPO_ROOT" :graph-rag-builder:testClasses

# ─── 순차 baseline 실행 ───────────────────────────────────────────────────
if [[ "$SKIP_SEQ" == true && -n "$SEQ_GRAPH_OVERRIDE" ]]; then
  SEQ_GRAPH="$SEQ_GRAPH_OVERRIDE"
  echo "=== [1/4] 순차 실행 생략 → baseline: $SEQ_GRAPH ==="
else
  echo "=== [1/4] 순차(--parallelism 1) 빌드 시작 ==="
  mkdir -p "$SEQ_OUT"
  SEQ_LOG="$SEQ_OUT/run.log"
  T_SEQ_START=$(date +%s)

  "$GW" -p "$REPO_ROOT" :graph-rag-builder:run \
    --args="$COMMON_ARGS \
      --coverage-backend pjacoco \
      --parallelism 1 \
      --out $SEQ_OUT/graph" \
    2>&1 | tee "$SEQ_LOG"

  T_SEQ_END=$(date +%s)
  T_SEQ=$(( T_SEQ_END - T_SEQ_START ))
  echo "순차 빌드 완료: ${T_SEQ}s"
  echo "$T_SEQ" > "$SEQ_OUT/wall_seconds.txt"

  SEQ_GRAPH="$SEQ_OUT/graph/graph.json"
fi

if [[ ! -f "$SEQ_GRAPH" ]]; then
  echo "ERROR: sequential graph.json not found at $SEQ_GRAPH" >&2
  exit 1
fi

# ─── 병렬 N=4 실행 ────────────────────────────────────────────────────────
echo "=== [2/4] 병렬(--parallelism 4) 빌드 시작 ==="
mkdir -p "$PAR4_OUT"
PAR4_LOG="$PAR4_OUT/run.log"
T_PAR4_START=$(date +%s)

"$GW" -p "$REPO_ROOT" :graph-rag-builder:run \
  --args="$COMMON_ARGS \
    --coverage-backend pjacoco \
    --parallelism 4 \
    --out $PAR4_OUT/graph" \
  2>&1 | tee "$PAR4_LOG"

T_PAR4_END=$(date +%s)
T_PAR4=$(( T_PAR4_END - T_PAR4_START ))
echo "병렬(N=4) 빌드 완료: ${T_PAR4}s"
echo "$T_PAR4" > "$PAR4_OUT/wall_seconds.txt"

PAR4_GRAPH="$PAR4_OUT/graph/graph.json"

# ─── 병렬 N=8 실행 (선택) ─────────────────────────────────────────────────
if [[ "$RUN_PAR8" == true ]]; then
  echo "=== [3/4] 병렬(--parallelism 8) 빌드 시작 ==="
  mkdir -p "$PAR8_OUT"
  PAR8_LOG="$PAR8_OUT/run.log"
  T_PAR8_START=$(date +%s)

  "$GW" -p "$REPO_ROOT" :graph-rag-builder:run \
    --args="$COMMON_ARGS \
      --coverage-backend pjacoco \
      --parallelism 8 \
      --out $PAR8_OUT/graph" \
    2>&1 | tee "$PAR8_LOG"

  T_PAR8_END=$(date +%s)
  T_PAR8=$(( T_PAR8_END - T_PAR8_START ))
  echo "병렬(N=8) 빌드 완료: ${T_PAR8}s"
  echo "$T_PAR8" > "$PAR8_OUT/wall_seconds.txt"

  PAR8_GRAPH="$PAR8_OUT/graph/graph.json"
fi

# ─── Set-동등 비교 ────────────────────────────────────────────────────────
echo ""
echo "=== [4/4] Set-동등 비교 ==="

echo "--- seq vs par4 ---"
DIFF4_EXIT=0
"$SCRIPT_DIR/graph-diff.sh" "$SEQ_GRAPH" "$PAR4_GRAPH" || DIFF4_EXIT=$?

if [[ "$RUN_PAR8" == true && -f "${PAR8_GRAPH:-}" ]]; then
  echo "--- seq vs par8 ---"
  DIFF8_EXIT=0
  "$SCRIPT_DIR/graph-diff.sh" "$SEQ_GRAPH" "$PAR8_GRAPH" || DIFF8_EXIT=$?
fi

# ─── 레이스/시드충돌 로그 검사 ────────────────────────────────────────────
echo ""
echo "=== 레이스/시드충돌 로그 검사 ==="

check_log_errors() {
  local log="$1" label="$2"
  local races=0 jdbc_ex=0 false404=0
  if [[ -f "$log" ]]; then
    races=$(grep -c "ConcurrentModificationException\|race\|corruption\|CORRUPTION" "$log" 2>/dev/null || true)
    jdbc_ex=$(grep -c "JdbcSQLException\|SQLIntegrityConstraintViolation\|Duplicate entry\|constraint violation" "$log" 2>/dev/null || true)
    false404=$(grep -c "seed-collision\|false-404\|probe.*not found" "$log" 2>/dev/null || true)
    echo "$label — races:$races  jdbc_ex:$jdbc_ex  false404:$false404"
    if [[ $races -gt 0 || $jdbc_ex -gt 0 || $false404 -gt 0 ]]; then
      echo "  ⚠️  issues found in $log"
    else
      echo "  ✅ clean"
    fi
  else
    echo "$label — log not found at $log"
  fi
}

check_log_errors "${SEQ_OUT}/run.log" "seq"
check_log_errors "$PAR4_LOG" "par4"
[[ "$RUN_PAR8" == true ]] && check_log_errors "$PAR8_LOG" "par8"

# ─── Speedup 요약 ─────────────────────────────────────────────────────────
echo ""
echo "=== Speedup 요약 ==="
if [[ -f "$SEQ_OUT/wall_seconds.txt" ]]; then
  T_SEQ=$(cat "$SEQ_OUT/wall_seconds.txt")
  echo "T_seq : ${T_SEQ}s"
fi
if [[ -f "$PAR4_OUT/wall_seconds.txt" ]]; then
  T_PAR4=$(cat "$PAR4_OUT/wall_seconds.txt")
  echo "T_par4: ${T_PAR4}s"
  if [[ -n "${T_SEQ:-}" && $T_PAR4 -gt 0 ]]; then
    SPEEDUP4=$(awk "BEGIN{printf \"%.2f\", $T_SEQ / $T_PAR4}")
    echo "speedup(N=4): ${SPEEDUP4}x"
  fi
fi
if [[ "$RUN_PAR8" == true && -f "$PAR8_OUT/wall_seconds.txt" ]]; then
  T_PAR8=$(cat "$PAR8_OUT/wall_seconds.txt")
  echo "T_par8: ${T_PAR8}s"
  if [[ -n "${T_SEQ:-}" && $T_PAR8 -gt 0 ]]; then
    SPEEDUP8=$(awk "BEGIN{printf \"%.2f\", $T_SEQ / $T_PAR8}")
    echo "speedup(N=8): ${SPEEDUP8}x"
  fi
fi

# ─── 최종 판정 ────────────────────────────────────────────────────────────
echo ""
echo "=== 최종 판정 ==="
if [[ ${DIFF4_EXIT:-1} -eq 0 ]]; then
  echo "seq vs par4: SET-EQUIVALENT ✅"
  echo "P2-5 HARD GATE: PASS (REQ-P004, REQ-P009)"
else
  echo "seq vs par4: NON-EQUIVALENT ❌ (exit=$DIFF4_EXIT)"
  echo "P2-5 HARD GATE: FAIL"
  exit 1
fi

if [[ "$RUN_PAR8" == true ]]; then
  if [[ ${DIFF8_EXIT:-1} -eq 0 ]]; then
    echo "seq vs par8: SET-EQUIVALENT ✅"
  else
    echo "seq vs par8: NON-EQUIVALENT ❌ (exit=$DIFF8_EXIT)"
    exit 1
  fi
fi
