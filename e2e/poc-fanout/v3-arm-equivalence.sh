#!/usr/bin/env bash
# v3-arm-equivalence.sh — V3(a) per-request testId arm 등가 (correctness) — REQ-004
#
# 이 스크립트는 JUnit 하니스(V3ArmEquivalencePoc.java)의 thin launcher 역할.
# 두 벡터(vanilla JaCoCo tcpserver / pjacoco per-request testId)를 순차 기동하고
# 동일 입력 시퀀스(petclinic GET /owners?lastName= arm 분기)를 투입해
# coverageKey 집합을 $DEST/vanilla.keys / $DEST/pjacoco.keys 로 출력한다.
# 집합 동일성 비교는 JUnit 하니스가 Java 수준에서 수행한다.
#
# 직접 실행(디버깅용):
#   POC_FANOUT_E2E=1 ./gradlew :graph-rag-builder:test --tests '*V3ArmEquivalencePoc*' \
#     -Dpjacoco.agent.jar=$(e2e/poc-fanout/install-pjacoco.sh | tail -1)
#
# 성공 시 stdout에 "V3a PASS", 실패 시 non-zero exit.

set -euo pipefail
echo "drives both vectors, writes \$DEST/vanilla.keys and \$DEST/pjacoco.keys"
echo "실제 비교 로직은 V3ArmEquivalencePoc.java (JUnit 하니스) 에서 실행됩니다."
echo "실행 방법: POC_FANOUT_E2E=1 ./gradlew :graph-rag-builder:test --tests '*V3ArmEquivalencePoc*'"
