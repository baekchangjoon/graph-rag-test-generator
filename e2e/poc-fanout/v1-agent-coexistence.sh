#!/usr/bin/env bash
# v1-agent-coexistence.sh — V1 게이트: OTel→pjacoco 공존 부팅 + 바닐라 호환 .exec
# REQ-001
# 성공 시 stdout에 "V1 PASS", 실패 시 non-zero exit.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# petclinic 4.x and jacococli require Java 17+; pick JDK 17 when on macOS
PETCLINIC_JAVA="${PETCLINIC_JAVA:-$(/usr/libexec/java_home -v 17 2>/dev/null || echo "${JAVA_HOME:-}")}"
export PETCLINIC_JAVA

# 1. pjacoco agent jar 경로 확인 (install-pjacoco.sh가 rebuild 후 경로 출력)
# install-pjacoco.sh는 Gradle 빌드 출력도 stdout으로 내보내므로 마지막 줄만 취한다.
echo "[V1] resolving pjacoco agent jar..." >&2
PJACOCO_JAR=$(bash "$SCRIPT_DIR/install-pjacoco.sh" | tail -1)
echo "[V1] pjacoco jar: $PJACOCO_JAR" >&2

# 2. OTel javaagent jar
OTEL_JAR="${OTEL_JAR:-$HOME/github_tainted-spring/tainted-spring-platform/jacoco/opentelemetry-javaagent.jar}"
if [[ ! -f "$OTEL_JAR" ]]; then
    echo "[V1] ERROR: OTel jar not found: $OTEL_JAR" >&2
    exit 1
fi
echo "[V1] OTel jar: $OTEL_JAR" >&2

# 3. JaCoCo CLI jar for .exec parsing
JACOCOCLI_JAR="${JACOCOCLI_JAR:-$HOME/.m2/repository/org/jacoco/org.jacoco.cli/0.8.11/org.jacoco.cli-0.8.11-nodeps.jar}"
if [[ ! -f "$JACOCOCLI_JAR" ]]; then
    echo "[V1] ERROR: jacococli jar not found: $JACOCOCLI_JAR" >&2
    exit 1
fi

# 4. destfile directory (each testId produces <dir>/<testId>.exec)
DEST=$(mktemp -d /tmp/v1-pjacoco-XXXXXX)
trap 'stop_petclinic; echo "[V1] cleanup DEST=$DEST" >&2' EXIT

# 5. JAVA_TOOL_OPTIONS: OTel 먼저, pjacoco 나중 (순서 고정 — Global Constraint)
PETCLINIC_PKG="org.springframework.samples.petclinic.*"
JTO="-javaagent:${OTEL_JAR} -javaagent:${PJACOCO_JAR}=destfile=${DEST},port=6310,includes=${PETCLINIC_PKG},traceKeyAutoCreate=true"
echo "[V1] JAVA_TOOL_OPTIONS=$JTO" >&2

# 6. petclinic 기동
# shellcheck source=lib-launch-petclinic.sh
source "$SCRIPT_DIR/lib-launch-petclinic.sh"
launch_petclinic "$JTO"

# 7. 단일 테스트 경계 시작
echo "[V1] test/start..." >&2
curl -fsS -X POST 'http://127.0.0.1:6310/__coverage__/test/start?testId=v1'

# 8. petclinic 엔드포인트 1회 요청 (OwnerController)
echo "[V1] hitting /owners?lastName= ..." >&2
curl -fsS -H 'baggage: test.id=v1' 'http://127.0.0.1:8080/owners?lastName=' > /dev/null

# 9. 테스트 경계 종료 → v1.exec 산출
echo "[V1] test/stop..." >&2
curl -fsS -X POST 'http://127.0.0.1:6310/__coverage__/test/stop?testId=v1&result=passed'
echo "" >&2

# 10. .exec 존재 확인
EXEC_FILE="$DEST/v1.exec"
echo "[V1] checking .exec: $EXEC_FILE" >&2
if [[ ! -f "$EXEC_FILE" ]]; then
    echo "[V1] ERROR: v1.exec not found in $DEST" >&2
    ls -la "$DEST" >&2
    exit 1
fi
echo "[V1] v1.exec size: $(wc -c < "$EXEC_FILE") bytes" >&2

# 11. jacococli로 파싱 — 비-zero 라인 카운트 확인
CLASSFILES=$(petclinic_classfiles)
CSV="$DEST/v1.csv"
echo "[V1] running jacococli report (classfiles=$CLASSFILES)..." >&2
"${PETCLINIC_JAVA}/bin/java" -jar "$JACOCOCLI_JAR" report "$EXEC_FILE" \
     --classfiles "$CLASSFILES" \
     --csv "$CSV"

# CSV에서 LINE_MISSED + LINE_COVERED > 0 확인
LINE_COUNT=$(awk -F, 'NR>1{c+=$5+$6} END{print (c+0)}' "$CSV")
echo "[V1] jacococli line count (missed+covered): $LINE_COUNT" >&2
if [[ "$LINE_COUNT" -le 0 ]]; then
    echo "[V1] ERROR: jacococli CSV shows 0 lines — .exec may be empty or classfiles mismatched" >&2
    exit 1
fi

# 12. JaCoCo tcpserver 포트 6300이 열려있지 않음을 확인 (pjacoco는 tcpserver를 쓰지 않음)
echo "[V1] checking JaCoCo tcpserver port 6300 is NOT open..." >&2
if nc -z 127.0.0.1 6300 2>/dev/null; then
    echo "[V1] ERROR: port 6300 is open — tcpserver should NOT be running with pjacoco" >&2
    exit 1
fi
echo "[V1] port 6300 not open (correct — pjacoco replaced tcpserver)" >&2

echo "V1 PASS (lines=$LINE_COUNT)"
