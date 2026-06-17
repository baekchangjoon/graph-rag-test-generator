#!/usr/bin/env bash
# 배포(Phase A-1) 수용 테스트: prebuilt distZip 산출물만으로 builder→generator 사이클이 도는가.
#   A-E2E-1: builder distZip 런처로 샘플 SUT 분석 → graph.json
#   A-E2E-2: generator distZip 런처로 테스트 .java 생성
#   A-E2E-3: 생성 테스트를 testlib jar(+전이 의존) classpath로 javac 컴파일
#   + 버전 주입 검증: RELEASE_VERSION을 주면 산출물명에 버전이 붙는다(build.gradle.kts).
# run-e2e.sh와 달리 `gradle :run`이 아니라 압축 해제한 dist의 bin 런처를 직접 호출한다.
# run-e2e.sh와 달리 generate에서 멈춘다(compose 테스트-실행 단계 없음) → dashboard/socket-mock
# bootJar는 불필요. 컴파일 가능성까지만 검증한다.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
E2E="$ROOT/e2e"
GW="$ROOT/gradlew"
VER="9.9.9-dist-e2e"
STAGE="$(mktemp -d)"
trap 'docker compose -f "$E2E/docker-compose.yml" down -v --remove-orphans >/dev/null 2>&1 || true; rm -rf "$STAGE"' EXIT

# dist 런처는 JRE 17이 필요하다(산출물은 17로 컴파일). 사용자가 JRE 17을 가졌다고 보고
# JAVA_HOME을 17로 맞춘다: 현재 JAVA_HOME이 17이면 그대로, 아니면 gradle.properties의 JDK17.
is17() { [ -x "$1/bin/java" ] && "$1/bin/java" -version 2>&1 | grep -q 'version "17'; }
if [ -n "${JAVA_HOME:-}" ] && is17 "$JAVA_HOME"; then :; else
  JDK17="$(sed -n 's/^org.gradle.java.home=//p' "$ROOT/gradle.properties" | head -1)"
  if [ -n "${JDK17:-}" ] && is17 "$JDK17"; then export JAVA_HOME="$JDK17"; else
    echo "❌ JRE 17 필요 — JAVA_HOME 또는 gradle.properties의 org.gradle.java.home를 JDK 17로"; exit 1
  fi
fi
echo "런처 JAVA_HOME=$JAVA_HOME"

echo "=== [1/6] SUT jar + dist 산출물 빌드 (RELEASE_VERSION=$VER) ==="
RELEASE_VERSION="$VER" "$GW" -q \
  :samples:order-service:bootJar :e2e:copyOtelAgent \
  :graph-rag-builder:distZip :test-generator:distZip :testlib:jar

BUILDER_ZIP="$ROOT/graph-rag-builder/build/distributions/graph-rag-builder-$VER.zip"
GENERATOR_ZIP="$ROOT/test-generator/build/distributions/test-generator-$VER.zip"
TESTLIB_JAR="$ROOT/testlib/build/libs/testlib-$VER.jar"

echo "=== [2/6] 버전 주입 검증 (산출물명에 $VER 포함) ==="
fail=0
for f in "$BUILDER_ZIP" "$GENERATOR_ZIP" "$TESTLIB_JAR"; do
  if [ -f "$f" ]; then echo "  OK  $(basename "$f")"; else echo "  MISSING  $(basename "$f")"; fail=1; fi
done
[ "$fail" = 0 ] || { echo "❌ 버전 주입 실패 — 루트 build.gradle.kts가 RELEASE_VERSION을 읽지 않음"; exit 1; }

echo "=== [3/6] dist 압축 해제 ==="
( cd "$STAGE" && unzip -q "$BUILDER_ZIP" && unzip -q "$GENERATOR_ZIP" )
BUILDER_BIN="$STAGE/graph-rag-builder-$VER/bin/graph-rag-builder"
GENERATOR_BIN="$STAGE/test-generator-$VER/bin/test-generator"
chmod +x "$BUILDER_BIN" "$GENERATOR_BIN"

echo "=== [4/6] A-E2E-1: builder 런처로 분석 → graph.json (Testcontainers + JaCoCo) ==="
OUT="$STAGE/out"
docker compose -f "$E2E/docker-compose.yml" down -v --remove-orphans >/dev/null 2>&1 || true
"$BUILDER_BIN" build \
  --sut-src "$ROOT/samples/order-service/src/main/java" \
  --sut-resources "$ROOT/samples/order-service/src/main/resources" \
  --sut-jar "$ROOT/samples/order-service/build/libs/order-service.jar" \
  --out "$OUT/graph" \
  --sut-id order-service \
  --with-kafka \
  --budget-requests 30 \
  --external-stubs "$E2E/external-stubs" \
  --sut-env "EXTERNAL_INVENTORY_URL={{wiremock}}" \
  --sut-compose "$E2E/docker-compose.yml" \
  --auth-login-path /api/auth/login --auth-user admin --auth-pass password
[ -f "$OUT/graph/graph.json" ] || { echo "❌ A-E2E-1 실패 — graph.json 없음"; exit 1; }
echo "  OK  graph.json 생성됨"

echo "=== [5/6] A-E2E-2: generator 런처로 테스트 .java 생성 ==="
"$GENERATOR_BIN" generate \
  --request "$E2E/request-orders.json" --graph "$OUT/graph" --out "$OUT/generated"
JAVA_COUNT=$(find "$OUT/generated" -name "*.java" | wc -l | tr -d ' ')
[ "$JAVA_COUNT" -ge 1 ] || { echo "❌ A-E2E-2 실패 — 생성된 .java 없음"; exit 1; }
echo "  OK  생성된 테스트 .java=$JAVA_COUNT"

echo "=== [6/6] A-E2E-3: 생성 테스트를 testlib jar(+전이 의존) classpath로 javac 컴파일 ==="
# testlib jar를 classpath 맨 앞에 두고(릴리스 자산 검증), 전이 의존은 e2e 테스트 런타임 classpath에서.
DEPS_CP="$("$GW" -q :e2e:printTestRuntimeClasspath | tail -1)"
CP="$TESTLIB_JAR:$DEPS_CP"
mkdir -p "$STAGE/classes"
"$JAVA_HOME/bin/javac" -cp "$CP" -d "$STAGE/classes" $(find "$OUT/generated" -name "*.java")
CLASS_COUNT=$(find "$STAGE/classes" -name "*.class" | wc -l | tr -d ' ')
[ "$CLASS_COUNT" -ge 1 ] || { echo "❌ A-E2E-3 실패 — 컴파일된 .class 없음"; exit 1; }
echo "  OK  컴파일 .class=$CLASS_COUNT (testlib 자산=$(basename "$TESTLIB_JAR"))"

echo "✅ DIST-E2E PASS — distZip 런처로 graph.json + 테스트 .java 생성 + testlib로 컴파일"
