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

cleanup() {
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
(
  cd "$PETCLINIC_DIR"
  # Skip spring-javaformat's validate goal — generated tests don't follow
  # Spring's in-tree formatting conventions and that validate would abort
  # the build before reaching the test phase.
  # Ignore test failures so jacoco.xml is still produced when the generated
  # RestAssured tests error (currently expected: scout's SUT is down by the
  # time mvn test runs, and the generated tests don't set baseURI — a
  # known test-generator limitation tracked in the runbook).
  mvn -q -DskipITs -Dspring-javaformat.skip=true -Dmaven.test.failure.ignore=true test jacoco:report
)

JACOCO_SRC="$PETCLINIC_DIR/target/site/jacoco/jacoco.xml"
if [[ ! -f "$JACOCO_SRC" ]]; then
  echo "error: jacoco.xml not produced at $JACOCO_SRC" >&2
  exit 5
fi

mkdir -p "$(dirname "$JACOCO_OUT")"
cp "$JACOCO_SRC" "$JACOCO_OUT"
echo "[stage5] wrote $JACOCO_OUT"
