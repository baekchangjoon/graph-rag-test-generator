#!/usr/bin/env bash
#
# selftest.sh — analyze-logs.sh 가 합성 픽스처에서 기대 정보를 뽑는지 검증한다.
# 실제 SUT 없이(이 세션/CI 포함) 분석기 자체가 동작함을 보장하는 용도.

set -u
cd "$(dirname "$0")"

TRACE="deadbeefdeadbeefdeadbeefdeadbeef"
PASS=0; FAIL=0
check() { # <설명> <기대문자열> <실제출력>
  if printf '%s' "$3" | grep -qaF "$2"; then
    echo "  PASS: $1"; PASS=$((PASS+1))
  else
    echo "  FAIL: $1 (기대: '$2' 없음)"; FAIL=$((FAIL+1))
  fi
}

echo "### H5 픽스처 (축약 로거명 + probe 마커)"
OUT="$(./analyze-logs.sh --trace-id "$TRACE" --probe probe-mrktId-96329 fixtures/sample-h5.log)"
check "축약 로거명(o.h...BasicBinder) H5 감지" "Hibernate5 (BasicBinder) bind 라인 : 2" "$OUT"
check "전파(trace-id 출현) 감지" "O (trace-id 가 로그에 나타남)" "$OUT"
check "trace-id SQL/bind 상관 검출" "SQL/bind 라인 trace-id 상관" "$OUT"
check "probe payload 상관 검출" "probe 'probe-mrktId-96329' 가 박힌 SQL/bind 라인 수: 1" "$OUT"
check "UTF-8 인코딩 판정" "VALID_UTF8" "$OUT"
check "MDC 키 노출(traceId=)" "traceId=deadbeef" "$OUT"

echo "### H6 픽스처"
OUT6="$(./analyze-logs.sh --trace-id "$TRACE" fixtures/sample-h6.log)"
check "H6 bind 형식 감지" "Hibernate6 (orm.jdbc.bind) bind 라인: 2" "$OUT6"
check "Sleuth 기본 패턴 대괄호 토큰 노출" "deadbeefdeadbeefdeadbeefdeadbeef" "$OUT6"

echo "### 인코딩 깨짐 감지 (EUC-KR 변환 가능 시)"
if command -v iconv >/dev/null 2>&1 && iconv -f UTF-8 -t EUC-KR fixtures/sample-h5.log > /tmp/poc-euckr.log 2>/dev/null; then
  OUTE="$(./analyze-logs.sh --trace-id "$TRACE" /tmp/poc-euckr.log)"
  check "비-UTF8 로그 감지" "INVALID_UTF8" "$OUTE"
  rm -f /tmp/poc-euckr.log
else
  echo "  SKIP: iconv EUC-KR 미지원 환경 (이 검증만 생략)"
fi

echo
echo "결과: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
