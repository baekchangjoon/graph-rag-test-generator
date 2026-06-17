#!/usr/bin/env bash
# Phase D-3 수용 테스트(D-E2E-3): 생성 테스트 실행 환경의 서비스 이미지(socket-mock-server,
# test-state-dashboard)가 prebuilt 이미지로 부팅·응답하는지 검증한다. 사용자가 빌드 없이
# 실행 환경을 띄울 수 있음을 확인한다(전 생성-테스트 실행은 run-e2e.sh가 동일 이미지로 커버).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DASH_IMG="graphrag/test-state-dashboard:docker-e2e"
SOCK_IMG="graphrag/socket-mock-server:docker-e2e"
DASH_C="grag-dash-e2e"; SOCK_C="grag-sock-e2e"
cleanup() { docker rm -f "$DASH_C" "$SOCK_C" >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup

echo "=== [1/4] bootJar + 서비스 이미지 빌드 ==="
"$ROOT/gradlew" -q :test-state-dashboard:bootJar :socket-mock-server:bootJar
docker build -q -t "$DASH_IMG" "$ROOT/test-state-dashboard" >/dev/null
docker build -q -t "$SOCK_IMG" "$ROOT/socket-mock-server" >/dev/null

echo "=== [2/4] 컨테이너 기동 ==="
docker run -d --name "$DASH_C" -p 18080:8080 "$DASH_IMG" >/dev/null
docker run -d --name "$SOCK_C" -p 19099:9099 "$SOCK_IMG" >/dev/null

wait_http() {  # $1=url, $2=name, $3=container. 어떤 HTTP 응답(2xx/4xx)이든 = 부팅 완료. 000=연결 실패.
  for _ in $(seq 1 60); do
    code="$(curl -s -o /dev/null -w '%{http_code}' "$1" 2>/dev/null)" || code=000
    [ "$code" != "000" ] && { echo "  OK  $2 응답 (HTTP $code)"; return 0; }
    sleep 1
  done
  echo "  ❌ $2 무응답"; docker logs "$3" 2>&1 | tail -15; return 1
}

echo "=== [3/4] dashboard GET /active ==="
wait_http "http://localhost:18080/active" "dashboard" "$DASH_C"

echo "=== [4/4] socket-mock admin /__admin/expectations ==="
wait_http "http://localhost:19099/__admin/expectations" "socket-mock" "$SOCK_C"

echo "✅ DOCKER-SERVICES-E2E (D-3) PASS — 실행 환경 서비스 이미지 부팅·응답 확인"
