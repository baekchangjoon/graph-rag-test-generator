#!/usr/bin/env bash
#
# send-request.sh — A 서비스 진입점에 "알려진 B3 trace-id" 를 단 HTTP 요청을 보낸다.
# Sleuth(Brave) 가 이 trace-id 를 A->B->C(Tram 메시지) 로 전파하는지를 C 로그에서 확인하기 위함.
#
# 사용법:
#   ./send-request.sh <URL> [METHOD] [DATA] [CONTENT_TYPE]
# 예:
#   ./send-request.sh http://legacy-a:8080/api/orders POST '{"item":"커피","qty":2}' application/json
#   TRACE_ID=deadbeefdeadbeefdeadbeefdeadbeef ./send-request.sh http://legacy-a:8080/api/orders
#
# 출력 마지막 줄의 trace-id 를 analyze-logs.sh --trace-id 로 그대로 넘기세요.

set -u

URL="${1:?URL 필요. 예: http://legacy-a:8080/api/orders}"
METHOD="${2:-GET}"
DATA="${3:-}"
CTYPE="${4:-application/json}"

# 매우 식별하기 쉬운 기본 trace-id (B3 128-bit = 32 hex). 필요시 TRACE_ID 로 override.
TRACE_ID="${TRACE_ID:-deadbeefdeadbeefdeadbeefdeadbeef}"
SPAN_ID="${SPAN_ID:-deadbeefdeadbeef}"   # 16 hex

echo "== send-request =="
echo "URL        : $METHOD $URL"
echo "B3 trace-id: $TRACE_ID"
echo "B3 span-id : $SPAN_ID"
echo

# B3 멀티 헤더 + 단일 헤더 둘 다 실어, 어느 전파 포맷이든 Sleuth 가 받게 한다.
HDRS=(
  -H "X-B3-TraceId: $TRACE_ID"
  -H "X-B3-SpanId: $SPAN_ID"
  -H "X-B3-Sampled: 1"
  -H "b3: ${TRACE_ID}-${SPAN_ID}-1"
)

if [ "$METHOD" = "GET" ] || [ -z "$DATA" ]; then
  curl -sS -i -X "$METHOD" "${HDRS[@]}" "$URL" || echo "(curl 실패 — URL/네트워크 확인)"
else
  curl -sS -i -X "$METHOD" "${HDRS[@]}" -H "Content-Type: $CTYPE" --data "$DATA" "$URL" \
    || echo "(curl 실패 — URL/네트워크 확인)"
fi

echo
echo "--------------------------------------------------------------"
echo "다음: C 서비스 로그를 수집해 분석하세요. 예)"
echo "  docker compose logs --no-color <C서비스> | ./analyze-logs.sh --trace-id $TRACE_ID -"
echo "TRACE_ID=$TRACE_ID"
