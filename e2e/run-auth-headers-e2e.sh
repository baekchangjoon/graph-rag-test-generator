#!/usr/bin/env bash
# B-E2E-2: 헤더 강제 SUT 전체 파이프라인 — 빌더 탐색 + 생성 테스트 재실행 모두 X-AuthorizationTime 사용.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HDRS="$ROOT/e2e/.auth-headers.txt"
printf 'X-AuthorizationTime: {{now:yyyyMMddHHmmss}}0900\n' > "$HDRS"
export REQUIRE_AUTH_TIME=true            # SUT 인터셉터 활성 (분석 SUT 프로세스 + 생성-테스트 compose 양쪽)
export REQUEST_HEADERS="X-AuthorizationTime: {{now:yyyyMMddHHmmss}}0900"   # 생성 테스트(testlib)용
"$ROOT/e2e/run-e2e.sh" --request-headers-file "$HDRS"
echo "✅ AUTH-HEADERS-E2E PASS"
