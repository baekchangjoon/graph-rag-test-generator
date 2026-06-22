#!/usr/bin/env bash
# v2-cross-contamination.sh — V2 게이트: 동시 2 엔드포인트 커버리지 교차오염 0
# REQ-002
# 성공 시 stdout에 "V2 PASS", 실패(오염 > 0) 시 non-zero exit.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PETCLINIC_JAVA="${PETCLINIC_JAVA:-$(/usr/libexec/java_home -v 17 2>/dev/null || echo "${JAVA_HOME:-}")}"
export PETCLINIC_JAVA

echo "[V2] resolving pjacoco agent jar..." >&2
PJACOCO_JAR=$(bash "$SCRIPT_DIR/install-pjacoco.sh" | tail -1)
echo "[V2] pjacoco jar: $PJACOCO_JAR" >&2

OTEL_JAR="${OTEL_JAR:-$HOME/github_tainted-spring/tainted-spring-platform/jacoco/opentelemetry-javaagent.jar}"
if [[ ! -f "$OTEL_JAR" ]]; then
    echo "[V2] ERROR: OTel jar not found: $OTEL_JAR" >&2
    exit 1
fi

JACOCOCLI_JAR="${JACOCOCLI_JAR:-$HOME/.m2/repository/org/jacoco/org.jacoco.cli/0.8.11/org.jacoco.cli-0.8.11-nodeps.jar}"
if [[ ! -f "$JACOCOCLI_JAR" ]]; then
    echo "[V2] ERROR: jacococli jar not found: $JACOCOCLI_JAR" >&2
    exit 1
fi

DEST=$(mktemp -d /tmp/v2-pjacoco-XXXXXX)
# shellcheck source=lib-launch-petclinic.sh
source "$SCRIPT_DIR/lib-launch-petclinic.sh"
trap 'stop_petclinic; echo "[V2] cleanup DEST=$DEST" >&2' EXIT

PETCLINIC_PKG="org.springframework.samples.petclinic.*"
JTO="-javaagent:${OTEL_JAR} -javaagent:${PJACOCO_JAR}=destfile=${DEST},port=6310,includes=${PETCLINIC_PKG},traceKeyAutoCreate=true"
echo "[V2] JAVA_TOOL_OPTIONS=$JTO" >&2

launch_petclinic "$JTO"

# traceId for each concurrent worker — deterministic hex strings
TRACE_A="0000000000000001abcdef0123456789"
TRACE_B="0000000000000002abcdef0123456789"
TRACEPARENT_A="00-${TRACE_A}-0000000000000001-01"
TRACEPARENT_B="00-${TRACE_B}-0000000000000001-01"

echo "[V2] firing concurrent requests (A=/owners?lastName= B=/vets.html)..." >&2

# Send A and B concurrently using background subshells
curl -fsS -H "traceparent: ${TRACEPARENT_A}" 'http://127.0.0.1:8080/owners?lastName=' > /dev/null &
PID_A=$!
curl -fsS -H "traceparent: ${TRACEPARENT_B}" 'http://127.0.0.1:8080/vets.html' > /dev/null &
PID_B=$!

wait "$PID_A" && echo "[V2] worker A done" >&2
wait "$PID_B" && echo "[V2] worker B done" >&2

# Flush both stores
echo "[V2] flushing traceId A..." >&2
curl -fsS -X POST "http://127.0.0.1:6310/__coverage__/test/stop?testId=${TRACE_A}&result=passed"
echo "" >&2
echo "[V2] flushing traceId B..." >&2
curl -fsS -X POST "http://127.0.0.1:6310/__coverage__/test/stop?testId=${TRACE_B}&result=passed"
echo "" >&2

# Wait for .exec files
for TRACE in "$TRACE_A" "$TRACE_B"; do
    EXEC_FILE="$DEST/${TRACE}.exec"
    echo "[V2] waiting for $EXEC_FILE..." >&2
    for i in $(seq 1 25); do
        if [[ -f "$EXEC_FILE" && $(wc -c < "$EXEC_FILE") -gt 32 ]]; then
            echo "[V2] $TRACE.exec ready ($(wc -c < "$EXEC_FILE") bytes)" >&2
            break
        fi
        sleep 0.3
        if [[ $i -eq 25 ]]; then
            echo "[V2] ERROR: $TRACE.exec not produced in 7.5s" >&2
            exit 1
        fi
    done
done

CLASSFILES=$(petclinic_classfiles)
JAVA_BIN="${PETCLINIC_JAVA}/bin/java"
if [[ ! -x "$JAVA_BIN" ]]; then JAVA_BIN="java"; fi

# Generate CSV reports for both .exec files
CSV_A="$DEST/a.csv"
CSV_B="$DEST/b.csv"

"$JAVA_BIN" -jar "$JACOCOCLI_JAR" report "$DEST/${TRACE_A}.exec" \
    --classfiles "$CLASSFILES" --csv "$CSV_A" 2>/dev/null
"$JAVA_BIN" -jar "$JACOCOCLI_JAR" report "$DEST/${TRACE_B}.exec" \
    --classfiles "$CLASSFILES" --csv "$CSV_B" 2>/dev/null

echo "[V2] === per-class covered LINE counts ===" >&2

# Extract covered lines for OwnerController and VetController from each CSV
# CSV columns: GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,...,LINE_MISSED,LINE_COVERED,...
extract_covered() {
    local csv="$1" classname="$2"
    awk -F, -v cls="$classname" 'NR>1 && $3==cls {print $0}' "$csv" | awk -F, '{print $7}' | head -1
}

OWNER_IN_A=$(extract_covered "$CSV_A" "OwnerController")
OWNER_IN_B=$(extract_covered "$CSV_B" "OwnerController")
VET_IN_A=$(extract_covered "$CSV_A" "VetController")
VET_IN_B=$(extract_covered "$CSV_B" "VetController")

OWNER_IN_A=${OWNER_IN_A:-0}
OWNER_IN_B=${OWNER_IN_B:-0}
VET_IN_A=${VET_IN_A:-0}
VET_IN_B=${VET_IN_B:-0}

echo "[V2] OwnerController covered lines in A.exec = $OWNER_IN_A" >&2
echo "[V2] OwnerController covered lines in B.exec = $OWNER_IN_B (must be 0)" >&2
echo "[V2] VetController   covered lines in A.exec = $VET_IN_A   (must be 0)" >&2
echo "[V2] VetController   covered lines in B.exec = $VET_IN_B" >&2

FAIL=0

if [[ "$OWNER_IN_A" -le 0 ]]; then
    echo "[V2] ERROR: OwnerController not covered in A.exec — worker A may not have hit /owners" >&2
    FAIL=1
fi
if [[ "$VET_IN_B" -le 0 ]]; then
    echo "[V2] ERROR: VetController not covered in B.exec — worker B may not have hit /vets.html" >&2
    FAIL=1
fi
if [[ "$OWNER_IN_B" -gt 0 ]]; then
    echo "[V2] CONTAMINATION: OwnerController appeared in B.exec (covered=$OWNER_IN_B) — cross-contamination detected!" >&2
    FAIL=1
fi
if [[ "$VET_IN_A" -gt 0 ]]; then
    echo "[V2] CONTAMINATION: VetController appeared in A.exec (covered=$VET_IN_A) — cross-contamination detected!" >&2
    FAIL=1
fi

if [[ "$FAIL" -ne 0 ]]; then
    echo "[V2] V2 FAIL"
    exit 1
fi

echo "V2 PASS (ownA_lines=${OWNER_IN_A} vetB_lines=${VET_IN_B} contamination_ownerInB=${OWNER_IN_B} contamination_vetInA=${VET_IN_A})"
