#!/usr/bin/env bash
set -euo pipefail
PJ=~/github_parallel-per-test-coverage/parallel-per-test-coverage
JDK="${PJACOCO_BUILD_JDK:-$JAVA_HOME}"
JAVA_HOME="$JDK" "$PJ"/gradlew -p "$PJ" :agent:shadowJar
JAR="$PJ/agent/build/libs/pjacoco-agent.jar"
test -f "$JAR" || { echo "pjacoco agent jar not found: $JAR" >&2; exit 1; }
echo "$JAR"
