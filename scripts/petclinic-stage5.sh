#!/usr/bin/env bash
# Stage-5 wrapper for the orchestrator's petclinic E2E. The orchestrator invokes
# this with two positional args:
#   $1 — generatedTestsDir (this iter's iter-N/stage4-tests)
#   $2 — jacocoOut         (where to write the cumulative jacoco.xml)
#
# Cumulative-coverage strategy: we glob every iter-*/stage4-tests under the
# orchestrator outDir and copy ALL of them into petclinic's test source tree
# before running `mvn test jacoco:report`. This way iter-N's reported coverage
# reflects all generated tests so far, not just this iter's subset (which is
# pruned by Stage 6's excludePaths in later iters).
#
# Env:
#   PETCLINIC_DIR — path to spring-petclinic clone (default: ~/github_spring-petclinic/spring-petclinic)
#   TEST_PACKAGE  — destination package for generated tests (default: com.example.petclinic.tests)
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <stage4-tests-dir> <jacoco-out-path>" >&2
  exit 2
fi

TESTS_DIR="$1"
JACOCO_OUT="$2"
PETCLINIC_DIR="${PETCLINIC_DIR:-$HOME/github_spring-petclinic/spring-petclinic}"
TEST_PACKAGE="${TEST_PACKAGE:-com.example.petclinic.tests}"

if [[ ! -f "$PETCLINIC_DIR/pom.xml" ]]; then
  echo "error: PETCLINIC_DIR=$PETCLINIC_DIR does not contain a pom.xml" >&2
  exit 3
fi
if [[ ! -d "$TESTS_DIR" ]]; then
  echo "error: tests-dir does not exist: $TESTS_DIR" >&2
  exit 4
fi

OUT_DIR="$(cd "$(dirname "$TESTS_DIR")/.." && pwd)"
TEST_PACKAGE_PATH="$(echo "$TEST_PACKAGE" | tr . /)"
INJECT_PARENT="$PETCLINIC_DIR/src/test/java"
INJECTED_ROOT="$INJECT_PARENT/$TEST_PACKAGE_PATH"
POM_BACKUP="$PETCLINIC_DIR/pom.xml.stage5-bak"

SUT_JAR="${SUT_JAR:-$PETCLINIC_DIR/target/spring-petclinic-4.0.0-SNAPSHOT.jar}"
SUT_PORT="${SUT_PORT:-8084}"
SUT_HEALTH_URL="${SUT_HEALTH_URL:-http://localhost:$SUT_PORT/actuator/health}"
SUT_HEALTH_TIMEOUT_SECS="${SUT_HEALTH_TIMEOUT_SECS:-60}"
SUT_PID=""
SUT_LOG="$PETCLINIC_DIR/target/stage5-sut.log"

launch_sut() {
  if [[ "${SKIP_SUT_LAUNCH:-}" == "1" ]]; then
    echo "[stage5] SKIP_SUT_LAUNCH=1 — assuming a SUT is already running on $SUT_PORT (or that the caller doesn't need one)"
    return 0
  fi
  if [[ ! -f "$SUT_JAR" ]]; then
    echo "error: SUT jar not found at $SUT_JAR" >&2
    return 6
  fi
  mkdir -p "$(dirname "$SUT_LOG")"
  : > "$SUT_LOG"
  # Prefer $JAVA_HOME/bin/java if set — petclinic targets Java 17 and the system
  # `java` on PATH may be older (orchestrator's parent process exports JAVA_HOME).
  local java_bin="java"
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    java_bin="$JAVA_HOME/bin/java"
  fi
  "$java_bin" -jar "$SUT_JAR" \
    --server.port="$SUT_PORT" \
    --spring.profiles.active=postgres \
    --spring.datasource.url=jdbc:postgresql://localhost:55432/petclinic \
    --spring.datasource.username=appuser \
    --spring.datasource.password=apppass \
    --spring.datasource.driver-class-name=org.postgresql.Driver \
    >> "$SUT_LOG" 2>&1 &
  SUT_PID=$!
  for _ in $(seq 1 "$SUT_HEALTH_TIMEOUT_SECS"); do
    if curl -sf "$SUT_HEALTH_URL" >/dev/null 2>&1; then
      echo "[stage5] SUT healthy at $SUT_HEALTH_URL (pid=$SUT_PID)"
      return 0
    fi
    if ! kill -0 "$SUT_PID" 2>/dev/null; then
      echo "error: SUT process died before becoming healthy; see $SUT_LOG" >&2
      tail -20 "$SUT_LOG" >&2 || true
      SUT_PID=""
      return 7
    fi
    sleep 1
  done
  echo "error: SUT did not become healthy within ${SUT_HEALTH_TIMEOUT_SECS}s" >&2
  tail -20 "$SUT_LOG" >&2 || true
  kill "$SUT_PID" 2>/dev/null || true
  SUT_PID=""
  return 8
}

stop_sut() {
  if [[ -n "$SUT_PID" ]]; then
    kill "$SUT_PID" 2>/dev/null || true
    wait "$SUT_PID" 2>/dev/null || true
    SUT_PID=""
  fi
}

cleanup() {
  stop_sut
  rm -rf "$INJECTED_ROOT"
  if [[ -f "$POM_BACKUP" ]]; then
    mv "$POM_BACKUP" "$PETCLINIC_DIR/pom.xml"
  fi
}
trap cleanup EXIT

# Inject rest-assured as a test-scoped dependency on a backed-up copy of pom.xml.
# The generated tests use io.restassured.RestAssured.given() and ContentType, which
# stock petclinic doesn't ship. The EXIT trap restores the original pom regardless
# of mvn outcome, leaving the user's clone untouched between runs.
if ! grep -q 'rest-assured' "$PETCLINIC_DIR/pom.xml"; then
  cp "$PETCLINIC_DIR/pom.xml" "$POM_BACKUP"
  python3 - "$PETCLINIC_DIR/pom.xml" <<'PY'
import sys
pom = sys.argv[1]
with open(pom) as f: content = f.read()
inject = '''    <dependency>
      <groupId>io.rest-assured</groupId>
      <artifactId>rest-assured</artifactId>
      <version>5.4.0</version>
      <scope>test</scope>
    </dependency>
  </dependencies>'''
content = content.replace('</dependencies>', inject, 1)
with open(pom, 'w') as f: f.write(content)
PY
fi

# iter-N/stage4-tests/ already contains the package directory tree
# (e.g. com/example/petclinic/tests/*.java). Copy its CONTENTS into
# src/test/java/ — not into the package-suffixed INJECTED_ROOT — to avoid
# doubling the package path. Cleanup still targets INJECTED_ROOT, which is
# exactly where the merged Java files end up.
#
# Sort lexicographically so iter-2's outputs win over iter-1's for any
# same-named class (later iters typically regenerate only fewer endpoints,
# so collisions are rare; when they do happen, latest wins is the desired
# semantic).
shopt -s nullglob
for dir in $(printf '%s\n' "$OUT_DIR"/iter-*/stage4-tests | sort); do
  [[ -d "$dir" ]] || continue
  cp -R "$dir"/. "$INJECT_PARENT"/
done
shopt -u nullglob

echo "[stage5] cumulative tests copied to $INJECTED_ROOT"

launch_sut

(
  cd "$PETCLINIC_DIR"
  # Skip spring-javaformat's validate goal — generated tests don't follow
  # Spring's in-tree formatting conventions and that validate would abort
  # the build before reaching the test phase.
  # Ignore test failures so jacoco.xml is still produced.
  # APP_BASE_URI is consumed by TestSynthesizer's @BeforeAll
  #   RestAssured.baseURI = System.getenv("APP_BASE_URI")
  APP_BASE_URI="http://localhost:$SUT_PORT" \
    mvn -q -DskipITs -Dspring-javaformat.skip=true -Dmaven.test.failure.ignore=true \
        test jacoco:report
)

JACOCO_SRC="$PETCLINIC_DIR/target/site/jacoco/jacoco.xml"
if [[ ! -f "$JACOCO_SRC" ]]; then
  echo "error: jacoco.xml not produced at $JACOCO_SRC" >&2
  exit 5
fi

mkdir -p "$(dirname "$JACOCO_OUT")"
# Strip the JaCoCo <!DOCTYPE ...> declaration so JaCoCoXmlParser (XXE-hardened,
# rejects ALL DOCTYPE) can parse the report. JaCoCo emits the whole XML on a
# single line, so use substitution (in-line) instead of line deletion to avoid
# nuking the report. The DOCTYPE is purely documentary in JaCoCo's output and
# removing it doesn't affect the coverage data.
sed -E 's@<!DOCTYPE[[:space:]]+report[^>]*>@@g' "$JACOCO_SRC" > "$JACOCO_OUT"
echo "[stage5] wrote $JACOCO_OUT"
