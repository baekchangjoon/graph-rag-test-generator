#!/usr/bin/env bash
# lib-launch-petclinic.sh — 외부 spring-petclinic SUT 빌드 + jar 기동
# source 해서 launch_petclinic "<JAVA_TOOL_OPTIONS>" 함수를 호출한다.
# 전제: JAVA_HOME이 JDK 17+를 가리킬 것 (petclinic은 Java 17 이상 필요).

PETCLINIC_DIR="${PETCLINIC_DIR:-$HOME/github_spring-petclinic/spring-petclinic}"
# petclinic 4.x requires Java 17+; pick a JDK 17 when available on macOS
PETCLINIC_JAVA="${PETCLINIC_JAVA:-$(/usr/libexec/java_home -v 17 2>/dev/null || echo "${JAVA_HOME:-}")}"

# build petclinic (always run gradlew — Gradle's up-to-date check handles incrementality)
_build_petclinic() {
    local dir="$PETCLINIC_DIR"
    echo "[lib-launch-petclinic] Building petclinic (./gradlew bootJar)..." >&2
    (cd "$dir" && ./gradlew bootJar -q 2>&1) >&2
    # find the fat jar (not plain)
    find "$dir/build/libs" -name '*.jar' ! -name '*-plain.jar' | head -1
}

# resolve classfiles directory for petclinic (gradle build output)
petclinic_classfiles() {
    echo "$PETCLINIC_DIR/build/classes/java/main"
}

# launch_petclinic <JAVA_TOOL_OPTIONS>
# Starts petclinic jar in background with given JAVA_TOOL_OPTIONS.
# Waits up to 60s for port 8080 to accept connections, then returns.
# Sets PETCLINIC_PID for the caller to kill.
launch_petclinic() {
    local jto="$1"
    local jar
    jar=$(_build_petclinic)
    if [[ -z "$jar" ]]; then
        echo "[lib-launch-petclinic] ERROR: could not find/build petclinic jar" >&2
        return 1
    fi
    echo "[lib-launch-petclinic] Launching petclinic: $jar" >&2
    echo "[lib-launch-petclinic] JAVA_TOOL_OPTIONS=$jto" >&2

    local java_bin="${PETCLINIC_JAVA}/bin/java"
    if [[ ! -x "$java_bin" ]]; then
        java_bin="java"
    fi
    echo "[lib-launch-petclinic] Using java: $java_bin" >&2

    JAVA_TOOL_OPTIONS="$jto" "$java_bin" -jar "$jar" \
        --server.port=8080 \
        --spring.datasource.url="jdbc:h2:mem:testdb" \
        > /tmp/petclinic-stdout.log 2>&1 &
    PETCLINIC_PID=$!

    echo "[lib-launch-petclinic] PID=$PETCLINIC_PID, waiting for :8080..." >&2
    local elapsed=0
    while ! curl -fsS http://127.0.0.1:8080/owners?lastName= >/dev/null 2>&1; do
        sleep 2
        elapsed=$((elapsed + 2))
        if [[ $elapsed -ge 90 ]]; then
            echo "[lib-launch-petclinic] ERROR: petclinic did not come up in 90s" >&2
            echo "[lib-launch-petclinic] Last 50 lines of log:" >&2
            tail -50 /tmp/petclinic-stdout.log >&2
            kill "$PETCLINIC_PID" 2>/dev/null || true
            return 1
        fi
        # check if process died
        if ! kill -0 "$PETCLINIC_PID" 2>/dev/null; then
            echo "[lib-launch-petclinic] ERROR: petclinic process exited early" >&2
            tail -50 /tmp/petclinic-stdout.log >&2
            return 1
        fi
    done
    echo "[lib-launch-petclinic] petclinic up (elapsed ${elapsed}s)" >&2
}

# stop_petclinic — kills the SUT process started by launch_petclinic
stop_petclinic() {
    if [[ -n "${PETCLINIC_PID:-}" ]]; then
        echo "[lib-launch-petclinic] Stopping petclinic PID=$PETCLINIC_PID" >&2
        kill "$PETCLINIC_PID" 2>/dev/null || true
        wait "$PETCLINIC_PID" 2>/dev/null || true
        PETCLINIC_PID=""
    fi
}
