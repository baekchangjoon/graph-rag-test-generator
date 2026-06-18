#!/usr/bin/env bash
#
# analyze-logs.sh — 레거시(Java8 + Spring Sleuth/Brave + Eventuate/Tram) SUT 로그를
# 분석해 "B3 trace-id 기반 비동기 SQL 캡처"가 가능한지 판단할 정보를 최대한 뽑는다.
#
# 가설(실패해도 정보 확보가 목적):
#   1) A에 주입한 B3 trace-id 가 메시징 너머 C 로그까지 전파되는가
#   2) C 의 SQL/bind 로그 라인에 MDC trace-id 가 박히는가, 키 이름/형식은 무엇인가
#   3) Hibernate 5/6 중 어느 bind 형식인가, 인코딩으로 한글 바인드가 깨지는가,
#      애초에 bind 로그가 출력되는가
#
# 사용법:
#   ./analyze-logs.sh --trace-id <hex> <logfile> [<logfile> ...]
#   docker compose logs --no-color C | ./analyze-logs.sh --trace-id <hex> -
#
# 의존성: bash, grep, sed, awk, perl (전부 표준). docker/java 불필요.

set -u

TRACE_ID=""
PROBE=""
FILES=()

while [ $# -gt 0 ]; do
  case "$1" in
    --trace-id) TRACE_ID="${2:-}"; shift 2 ;;
    --probe)    PROBE="${2:-}"; shift 2 ;;
    -h|--help)
      sed -n '2,22p' "$0"; exit 0 ;;
    *) FILES+=("$1"); shift ;;
  esac
done

if [ "${#FILES[@]}" -eq 0 ]; then
  echo "ERROR: 로그 파일 경로(또는 - for stdin)가 필요합니다. --help 참고." >&2
  exit 2
fi

# stdin(-) 은 임시 파일로 받아 여러 번 스캔 가능하게 한다.
TMP=""
cleanup() { [ -n "$TMP" ] && rm -f "$TMP"; }
trap cleanup EXIT

REAL_FILES=()
for f in "${FILES[@]}"; do
  if [ "$f" = "-" ]; then
    TMP="$(mktemp)"; cat > "$TMP"; REAL_FILES+=("$TMP")
  elif [ -f "$f" ]; then
    REAL_FILES+=("$f")
  else
    echo "WARN: 파일 없음, 건너뜀: $f" >&2
  fi
done

if [ "${#REAL_FILES[@]}" -eq 0 ]; then
  echo "ERROR: 읽을 수 있는 로그가 없습니다." >&2
  exit 2
fi

hr() { printf '%s\n' "------------------------------------------------------------"; }
section() { echo; hr; echo "## $1"; hr; }

# Hibernate/MyBatis 패턴 (H5 + H6 + MyBatis 모두)
# 로거명은 logback %logger{36} 으로 축약될 수 있다(예: o.h.type.descriptor.sql.BasicBinder).
# 풀네임 대신 끝부분 식별자로 매칭해 축약/풀네임 모두 잡는다.
SQL_RE='hibernate\.SQL|==>[[:space:]]*Preparing:'
BIND_RE='BasicBinder.*binding parameter|orm\.jdbc\.bind.*binding parameter|==>[[:space:]]*Parameters:'
H5_BIND_RE='BasicBinder.*binding parameter'
H6_BIND_RE='orm\.jdbc\.bind.*binding parameter'

echo "==================== LEGACY ASYNC SQL CAPTURE — LOG ANALYSIS ===================="
echo "trace-id   : ${TRACE_ID:-(미지정 — 전파 검증 생략)}"
echo "log files  : ${REAL_FILES[*]}"

# ---------------------------------------------------------------------------
section "0. 파일 개요 (라인 수 / 추정 인코딩)"
for f in "${REAL_FILES[@]}"; do
  lines=$(wc -l < "$f" | tr -d ' ')
  utf8=$(perl -MEncode -e 'local $/; my $d=<STDIN>; eval { decode("UTF-8",$d,Encode::FB_CROAK) }; print $@ ? "INVALID_UTF8(비-UTF8, 예: MS949/EUC-KR 가능)" : "VALID_UTF8";' < "$f")
  nonascii=$(perl -ne '$c++ if /[^\x00-\x7F]/; END{print $c+0}' "$f")
  printf '  %-40s lines=%-7s encoding=%s nonASCII_lines=%s\n' "$(basename "$f")" "$lines" "$utf8" "$nonascii"
done

# ---------------------------------------------------------------------------
section "1. 전파 검증 — trace-id 가 이 로그(특히 C)에 나타나는가"
if [ -z "$TRACE_ID" ]; then
  echo "  (--trace-id 미지정으로 생략)"
else
  hits=$(grep -aFc "$TRACE_ID" "${REAL_FILES[@]}" 2>/dev/null | awk -F: '{s+=$NF} END{print s+0}')
  echo "  trace-id '$TRACE_ID' 총 출현 라인 수: $hits"
  if [ "$hits" -eq 0 ]; then
    echo "  >> 전파 안 됨 또는 로그 패턴에 trace-id 미포함. (가설 1 또는 로그패턴 주입 실패)"
  else
    echo "  --- 출현 라인 (최대 40개) ---"
    grep -aF "$TRACE_ID" "${REAL_FILES[@]}" 2>/dev/null | head -40
  fi
fi

# ---------------------------------------------------------------------------
section "1b. payload(probe) 상관 — 요청에 심은 마커가 SQL bind 에 나타나는가"
echo "  (trace-id 가 SQL 라인 MDC 에 안 박힐 때의 대안 상관 키. SUT 무수정.)"
if [ -z "$PROBE" ]; then
  echo "  (--probe 미지정으로 생략. 요청에 심은 유니크 값을 넘기면 상관 가능성을 검증합니다)"
else
  pc=$(grep -aE "$SQL_RE|$BIND_RE" "${REAL_FILES[@]}" 2>/dev/null | grep -aFc "$PROBE")
  echo "  probe '$PROBE' 가 박힌 SQL/bind 라인 수: $pc"
  if [ "$pc" -gt 0 ]; then
    echo "  >> probe 가 C 의 SQL 까지 도달 — payload 기반 상관 가능 (entry SQL 한정)"
    grep -aE "$SQL_RE|$BIND_RE" "${REAL_FILES[@]}" 2>/dev/null | grep -aF "$PROBE" | head -20
    echo
    echo "  --- probe 출현 주변 컨텍스트 (±8라인) — 같은 요청 statement 군집/스레드 여부 확인 ---"
    echo "      (probe 줄 주위가 같은 thread([..]) 의 SQL+bind 묶음이면 (a)정상 footprint,"
    echo "       무관한 SQL 이 섞여 있으면 (b)오염 → 직렬+윈도우 캡처에 추가 격리 필요)"
    for f in "${REAL_FILES[@]}"; do
      if grep -qaF "$PROBE" "$f" 2>/dev/null; then
        echo "  [$f]"
        grep -aF -n -C 8 "$PROBE" "$f" 2>/dev/null | head -60
      fi
    done
  else
    echo "  >> probe 가 SQL bind 에 없음 — 그 값이 파생/변환되었거나 C 까지 미도달"
  fi
fi

# ---------------------------------------------------------------------------
section "2. SQL 로그 출현 여부 + 형식"
sqlcount=$(grep -aEc "$SQL_RE" "${REAL_FILES[@]}" 2>/dev/null | awk -F: '{s+=$NF} END{print s+0}')
echo "  SQL 라인 수: $sqlcount"
echo "  --- 샘플 (최대 15개) ---"
grep -aE "$SQL_RE" "${REAL_FILES[@]}" 2>/dev/null | head -15

# ---------------------------------------------------------------------------
section "3. BIND 로그 출현 여부 + Hibernate 5 vs 6 판별"
h5=$(grep -aEc "$H5_BIND_RE" "${REAL_FILES[@]}" 2>/dev/null | awk -F: '{s+=$NF} END{print s+0}')
h6=$(grep -aEc "$H6_BIND_RE" "${REAL_FILES[@]}" 2>/dev/null | awk -F: '{s+=$NF} END{print s+0}')
mb=$(grep -aEc '==>[[:space:]]*Parameters:' "${REAL_FILES[@]}" 2>/dev/null | awk -F: '{s+=$NF} END{print s+0}')
echo "  Hibernate5 (BasicBinder) bind 라인 : $h5"
echo "  Hibernate6 (orm.jdbc.bind) bind 라인: $h6"
echo "  MyBatis (Parameters:) 라인          : $mb"
if [ "$h5" -gt 0 ]; then echo "  >> 판별: Hibernate 5 형식 — SqlLogParser 에 BasicBinder 패턴 필요"; fi
if [ "$h6" -gt 0 ]; then echo "  >> 판별: Hibernate 6 형식 — 현재 파서로 파싱 가능"; fi
if [ "$h5" -eq 0 ] && [ "$h6" -eq 0 ] && [ "$mb" -eq 0 ]; then
  echo "  >> bind 로그 0건. 로그레벨 주입 누락 가능:"
  echo "     logging.level.org.hibernate.type.descriptor.sql.BasicBinder=TRACE (H5)"
  echo "     logging.level.org.hibernate.orm.jdbc.bind=TRACE (H6)"
fi
echo "  --- bind 샘플 (최대 15개) ---"
grep -aE "$BIND_RE" "${REAL_FILES[@]}" 2>/dev/null | head -15

# ---------------------------------------------------------------------------
section "4. MDC 정체 — SQL/bind 라인의 [ ... ] 부분에서 trace-id 키 노출"
echo "  (로그 패턴에 %X 를 넣었다면 전체 MDC 가 key=value 로 보입니다)"
echo "  --- SQL/bind 라인의 대괄호 토큰 (고유, 최대 25개) ---"
grep -aE "$SQL_RE|$BIND_RE" "${REAL_FILES[@]}" 2>/dev/null \
  | grep -aoE '\[[^][]*\]' | sort -u | head -25
if [ -n "$TRACE_ID" ]; then
  echo "  --- trace-id 를 포함한 SQL/bind 라인 (상관 가능 여부 직접 확인, 최대 20개) ---"
  grep -aE "$SQL_RE|$BIND_RE" "${REAL_FILES[@]}" 2>/dev/null | grep -aF "$TRACE_ID" | head -20
  corr=$(grep -aE "$SQL_RE|$BIND_RE" "${REAL_FILES[@]}" 2>/dev/null | grep -aFc "$TRACE_ID")
  echo "  >> trace-id 가 박힌 SQL/bind 라인 수: $corr"
  if [ "$corr" -eq 0 ]; then
    echo "     (0 이면: trace-id 가 SQL 스레드 MDC 에 없음 → 로그상관 불가. "
    echo "      bind 라인의 위 대괄호 토큰을 보고 다른 키로 박히는지 확인 필요)"
  fi
fi

# ---------------------------------------------------------------------------
section "5. 인코딩 — 한글/멀티바이트 바인드 값 깨짐 점검"
echo "  --- non-ASCII 를 포함한 bind 라인 (최대 10개, 깨짐 육안 확인) ---"
grep -aE "$BIND_RE" "${REAL_FILES[@]}" 2>/dev/null | perl -ne 'print if /[^\x00-\x7F]/' | head -10
echo "  (위가 ?? 또는 ��/모지바케면: SUT stdout 이 비-UTF8. "
echo "   JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 로 SUT 기동 필요)"

# ---------------------------------------------------------------------------
section "6. 요약 판정"
verdict_prop="n/a"
if [ -n "$TRACE_ID" ]; then
  if [ "$(grep -aFc "$TRACE_ID" "${REAL_FILES[@]}" 2>/dev/null | awk -F: '{s+=$NF} END{print s+0}')" -gt 0 ]; then
    verdict_prop="O (trace-id 가 로그에 나타남)"
  else
    verdict_prop="X (전파/패턴 주입 실패)"
  fi
fi
fmt="H5=$h5 / H6=$h6 / MyBatis=$mb"
echo "  - 전파(trace-id 로그 출현)      : $verdict_prop"
echo "  - bind 형식                     : $fmt"
if [ -n "$TRACE_ID" ]; then
  echo "  - SQL/bind 라인 trace-id 상관   : $(grep -aE "$SQL_RE|$BIND_RE" "${REAL_FILES[@]}" 2>/dev/null | grep -aFc "$TRACE_ID") 라인"
fi
if [ -n "$PROBE" ]; then
  echo "  - SQL/bind 라인 probe 상관      : $(grep -aE "$SQL_RE|$BIND_RE" "${REAL_FILES[@]}" 2>/dev/null | grep -aFc "$PROBE") 라인"
fi
echo
echo "다른 PC에서 이 출력 전체를 복사해 보고해 주세요. 위 6개 섹션이면 설계 판단에 충분합니다."
echo "================================================================================"
