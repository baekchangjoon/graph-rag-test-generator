#!/usr/bin/env bash
# E2E acceptance runbook: legacy-tram sleuth trace propagation
#
# Validates three results:
#   R1 (core, builder-independent): injected B3 trace-id propagates A→B→Tram/Kafka/CDC→C
#        and appears in ledger's Hibernate SQL log for ledger_entries insert.
#   CAP (builder attach): --trace-mode sleuth captures A/B/C SQL into graph.json.
#   NOISE: CDC background SQL (received_messages / message polling) excluded from graph.json.
#
# Exit: 0 only when R1=PASS AND CAP=PASS AND NOISE=PASS.
# Idempotent: cleans up all docker resources on every exit path.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STACK="$ROOT/samples/legacy-tram"
OUT="$(mktemp -d)"
LOG="$OUT/builder.log"
APP_PORT=58080
JACOCO_PORT=56300
DB_PORT=53306
KAFKA_PORT=59092

# Compose command combining base + e2e overlay (publishes order-web:58080, mysql:53306)
DC_BASE="docker compose -f $STACK/docker-compose.yml -f $STACK/docker-compose.e2e.yml"

# Container names (docker compose project = legacy-tram, service names match)
LEDGER_CONTAINER="legacy-tram-ledger-1"
RESERVATION_CONTAINER="legacy-tram-reservation-1"
ORDER_WEB_CONTAINER="legacy-tram-order-web-1"
CDC_CONTAINER="legacy-tram-eventuate-cdc-service-1"
KAFKA_CONTAINER="legacy-tram-kafka-1"

# ── Cleanup: always runs on exit ─────────────────────────────────────────────
cleanup() {
    echo "[cleanup] bringing down stack and removing volumes..."
    (cd "$STACK" && docker compose -f docker-compose.yml -f docker-compose.e2e.yml down -v) \
        >/dev/null 2>&1 || true
    echo "[cleanup] done."
}
trap cleanup EXIT

# ═══════════════════════════════════════════════════════════════════════════════
# STEP 0: Builder fail-fast — confirm --trace-mode sleuth + --capture-services
# ═══════════════════════════════════════════════════════════════════════════════
echo "=== [0/4] builder fail-fast check ==="
if ! grep -qr -- '--trace-mode' "$ROOT/graph-rag-builder/src/main/java" 2>/dev/null; then
    echo "FAIL: builder source lacks --trace-mode (need PR #60 merged)"; exit 2
fi
if ! grep -qr -- '--capture-services' "$ROOT/graph-rag-builder/src/main/java" 2>/dev/null; then
    echo "FAIL: builder source lacks --capture-services (need PR #60)"; exit 2
fi
if ! grep -q "'sleuth'" "$ROOT/graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java" 2>/dev/null; then
    echo "FAIL: builder does not recognise 'sleuth' as a trace-mode value"; exit 2
fi
echo "[step 0] builder flags confirmed (trace-mode sleuth, capture-services)"

# ═══════════════════════════════════════════════════════════════════════════════
# STEP 1: R1 — bring up the stack with E2E overlay and verify B3 propagation
# ═══════════════════════════════════════════════════════════════════════════════
echo "=== [1/4] stack up (E2E overlay — host ports $APP_PORT/$DB_PORT/$KAFKA_PORT) ==="
(cd "$STACK" && eval "$DC_BASE up -d --build --wait order-web reservation ledger eventuate-cdc-service")

# Sanity-check actuator health
echo "[R1] sanity-checking order-web actuator health..."
for i in $(seq 1 12); do
    STATUS=$(curl -fsS "http://localhost:$APP_PORT/actuator/health" 2>/dev/null \
             | grep -o '"status":"[^"]*"' | head -1 || true)
    if echo "$STATUS" | grep -q '"UP"'; then
        echo "[R1] order-web is UP"
        break
    fi
    if [ "$i" -eq 12 ]; then
        echo "[R1] order-web actuator did not return UP after 60s; aborting R1"
        docker logs "$ORDER_WEB_CONTAINER" 2>&1 | tail -50 || true
        exit 1
    fi
    sleep 5
done

# Pre-request time anchor (prevents false positives from leftover log lines)
TRACE="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
SPAN="bbbbbbbbbbbbbbbb"
SINCE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

echo "[R1] injecting B3 trace-id=$TRACE via POST /orders ..."
HTTP_STATUS=$(curl -fsS -o /dev/null -w "%{http_code}" \
    -X POST "http://localhost:$APP_PORT/orders" \
    -H "Content-Type: application/json" \
    -H "X-B3-TraceId: $TRACE" \
    -H "X-B3-SpanId: $SPAN" \
    -H "X-B3-Sampled: 1" \
    -d '{"userId":"u1","amount":100}') || HTTP_STATUS="error"
echo "[R1] POST /orders → HTTP $HTTP_STATUS"

if [ "$HTTP_STATUS" != "202" ] && [ "$HTTP_STATUS" != "200" ]; then
    echo "[R1] unexpected HTTP status — dumping order-web and reservation logs"
    docker logs "$ORDER_WEB_CONTAINER" --since "$SINCE" 2>&1 | tail -50 || true
    docker logs "$RESERVATION_CONTAINER" --since "$SINCE" 2>&1 | tail -50 || true
    exit 1
fi

# Poll ledger logs for the trace-id in an org.hibernate.SQL line (up to 40s)
# Use 'docker logs --since' for accurate post-request log lines (bypasses compose log caching)
echo "[R1] polling ledger logs for trace-id $TRACE (up to 40s)..."
R1=FALSE
R1_LINE=""
for i in $(seq 1 160); do   # 160 * 0.25s = 40s
    # Accept full 32-hex OR right-16-hex (64-bit fallback restoration)
    MATCH=$(docker logs "$LEDGER_CONTAINER" --since "$SINCE" 2>&1 \
            | grep -i 'org.hibernate.SQL' \
            | grep -iE "${TRACE}|${TRACE: -16}" \
            | head -1 || true)
    if [ -n "$MATCH" ]; then
        R1=PASS-primary
        R1_LINE="$MATCH"
        break
    fi
    sleep 0.25
done

echo "[R1] primary result: $R1"

# ── Fallback retry if primary failed ─────────────────────────────────────────
if [ "$R1" = "FALSE" ]; then
    echo "[R1] primary (sleuth-integration) path did not propagate trace."
    echo "[R1] retrying with EVENTUATE_B3_FALLBACK=true (manual B3MessageInterceptor path)..."
    echo "=== R1 primary-fail diagnostic dump ==="
    docker logs "$ORDER_WEB_CONTAINER" --since "$SINCE" 2>&1 | tail -50 || true
    docker logs "$RESERVATION_CONTAINER" --since "$SINCE" 2>&1 | tail -50 || true
    docker logs "$LEDGER_CONTAINER" --since "$SINCE" 2>&1 | tail -100 || true
    docker logs "$CDC_CONTAINER" --since "$SINCE" 2>&1 | tail -50 || true
    docker logs "$KAFKA_CONTAINER" --since "$SINCE" 2>&1 | tail -20 || true
    echo "=== end primary-fail diagnostic ==="

    # Bring down cleanly, then start with fallback env vars
    (cd "$STACK" && docker compose -f docker-compose.yml -f docker-compose.e2e.yml down -v) || true

    FALLBACK_OVERRIDE="$OUT/fallback-override.yml"
    cat > "$FALLBACK_OVERRIDE" <<'OVERRIDE_EOF'
services:
  reservation:
    environment:
      EVENTUATE_B3_FALLBACK: "true"
  ledger:
    environment:
      EVENTUATE_B3_FALLBACK: "true"
OVERRIDE_EOF

    DFC="docker compose -f $STACK/docker-compose.yml -f $STACK/docker-compose.e2e.yml -f $FALLBACK_OVERRIDE"
    echo "[R1-fallback] bringing stack up with EVENTUATE_B3_FALLBACK=true..."
    (cd "$STACK" && eval "$DFC up -d --build --wait order-web reservation ledger eventuate-cdc-service")

    for i in $(seq 1 12); do
        STATUS=$(curl -fsS "http://localhost:$APP_PORT/actuator/health" 2>/dev/null \
                 | grep -o '"status":"[^"]*"' | head -1 || true)
        if echo "$STATUS" | grep -q '"UP"'; then break; fi
        if [ "$i" -eq 12 ]; then
            echo "[R1-fallback] order-web did not come UP; aborting"; exit 1
        fi
        sleep 5
    done

    SINCE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "[R1-fallback] injecting B3 trace-id=$TRACE ..."
    HTTP_STATUS=$(curl -fsS -o /dev/null -w "%{http_code}" \
        -X POST "http://localhost:$APP_PORT/orders" \
        -H "Content-Type: application/json" \
        -H "X-B3-TraceId: $TRACE" \
        -H "X-B3-SpanId: $SPAN" \
        -H "X-B3-Sampled: 1" \
        -d '{"userId":"u1","amount":100}') || HTTP_STATUS="error"
    echo "[R1-fallback] POST /orders → HTTP $HTTP_STATUS"

    for i in $(seq 1 160); do
        MATCH=$(docker logs "$LEDGER_CONTAINER" --since "$SINCE" 2>&1 \
                | grep -i 'org.hibernate.SQL' \
                | grep -iE "${TRACE}|${TRACE: -16}" \
                | head -1 || true)
        if [ -n "$MATCH" ]; then
            R1=PASS-fallback
            R1_LINE="$MATCH"
            break
        fi
        sleep 0.25
    done

    echo "[R1-fallback] result: $R1"

    if [ "$R1" = "FALSE" ]; then
        echo "=== R1 fallback-fail diagnostic dump ==="
        docker logs "$ORDER_WEB_CONTAINER" --since "$SINCE" 2>&1 | tail -100 || true
        docker logs "$RESERVATION_CONTAINER" --since "$SINCE" 2>&1 | tail -100 || true
        docker logs "$LEDGER_CONTAINER" --since "$SINCE" 2>&1 | tail -100 || true
        docker logs "$CDC_CONTAINER" --since "$SINCE" 2>&1 | tail -100 || true
        echo "=== end fallback diagnostic ==="
        echo ""
        echo "[R1] VERDICT: FALSE — trace-id did not appear in ledger SQL log on either path."
    fi
fi

if [ "$R1" != "FALSE" ]; then
    echo "[R1] VERDICT: $R1"
    echo "[R1] Matching log line: $R1_LINE"
fi

# Down the stack before builder attach (port conflict prevention)
echo "[cleanup-pre-builder] downing stack before builder attach..."
(cd "$STACK" && docker compose -f docker-compose.yml -f docker-compose.e2e.yml down -v) 2>/dev/null || true
# Re-arm the trap (cleanup already ran above; must run again on exit for builder stack)
trap - EXIT
trap cleanup EXIT

# ═══════════════════════════════════════════════════════════════════════════════
# STEP 2: Build host-side order-web jar (required for --sut-jar)
# ═══════════════════════════════════════════════════════════════════════════════
echo "=== [2/4] building host-side order-web jar via docker gradle ==="
HOST_JAR="$STACK/order-web/build/libs/order-web.jar"

if [ -f "$HOST_JAR" ]; then
    echo "[builder-prep] cached jar found at $HOST_JAR; skipping docker build"
else
    echo "[builder-prep] running gradle:7.6-jdk8 inside docker to produce order-web.jar..."
    docker run --rm \
        -v "$STACK/order-web":/src \
        -w /src \
        gradle:7.6-jdk8 \
        gradle bootJar --no-daemon 2>&1 | tee "$OUT/order-web-jar-build.log"
    if [ ! -f "$HOST_JAR" ]; then
        echo "FAIL: order-web.jar was not produced at $HOST_JAR"
        echo "      See $OUT/order-web-jar-build.log for details."
        CAP=NOT-DETERMINED; NOISE=NOT-DETERMINED
    fi
fi

# ═══════════════════════════════════════════════════════════════════════════════
# STEP 3: Builder attach — --trace-mode sleuth, multi-service capture
# ═══════════════════════════════════════════════════════════════════════════════
CAP=NOT-DETERMINED
NOISE=NOT-DETERMINED

if [ -f "$HOST_JAR" ]; then
    echo "=== [3/4] builder attach (--trace-mode sleuth, capture-services order-web,reservation,ledger) ==="
    set +e   # builder attach failures are captured, not fatal here
    "$ROOT/gradlew" -p "$ROOT" -q :graph-rag-builder:run --args="build \
      --attach \
      --sut-compose $STACK/docker-compose.yml \
      --app-service order-web \
      --app-container-port 8080 \
      --app-port $APP_PORT \
      --jacoco-port $JACOCO_PORT \
      --jdbc-url jdbc:mysql://localhost:$DB_PORT/orderdb \
      --kafka-bootstrap localhost:$KAFKA_PORT \
      --db-service mysql \
      --trace-mode sleuth \
      --capture-services order-web,reservation,ledger \
      --sut-src $STACK/order-web/src/main/java \
      --sut-jar $HOST_JAR \
      --out $OUT \
      --sut-id legacy-tram-sleuth" 2>&1 | tee "$LOG"
    BUILDER_EXIT=${PIPESTATUS[0]}
    set -e

    if [ "$BUILDER_EXIT" -ne 0 ]; then
        echo "[CAP/NOISE] builder attach exited with code $BUILDER_EXIT — NOT-DETERMINED"
        echo "=== builder log tail ==="
        tail -40 "$LOG" || true
        echo "=== end builder log ==="
    else
        # ── CAP: verify A/B/C SQL present in graph.json ──────────────────────
        GRAPH="$OUT/graph.json"
        if [ ! -f "$GRAPH" ]; then
            echo "[CAP] graph.json not produced — NOT-DETERMINED"
        else
            # Check if the builder skipped all endpoints (0 sql rows)
            # @RequestBody Map<String,Object> is untyped — builder skips exploration for it
            SQL_COUNT=$(python3 -c "import json,sys; g=json.load(open('$GRAPH')); print(len(g.get('sql',[])))" 2>/dev/null || echo "0")
            if [ "$SQL_COUNT" = "0" ]; then
                echo "[CAP] graph.json has 0 SQL entries — builder made no explorations"
                echo "[CAP] FAIL — order-web now uses typed OrderRequest DTO; 0 SQL entries means the builder still failed to explore POST /orders"
                CAP=FAIL
            else
                CAP=PASS
                for needle in 'insert into orders' 'insert into reservations' 'insert into ledger_entries'; do
                    if ! grep -iq "$needle" "$GRAPH"; then
                        echo "[CAP] MISSING: $needle"
                        CAP=FAIL
                    fi
                done
                echo "[CAP] $CAP"
            fi

            # ── NOISE: assert CDC background SQL NOT in graph.json ────────────
            NOISE=PASS
            if grep -iq 'from received_messages' "$GRAPH" || grep -iq 'from message where' "$GRAPH"; then
                echo "[NOISE] background CDC SQL leaked into graph.json — FAIL"
                NOISE=FAIL
            fi
            echo "[NOISE] $NOISE"
        fi
    fi
else
    echo "[step 3] skipped (host jar not available)"
fi

# ═══════════════════════════════════════════════════════════════════════════════
# STEP 4: Summary + exit code
# ═══════════════════════════════════════════════════════════════════════════════
echo ""
echo "=== [4/4] E2E acceptance summary ==="
echo "  R1   : $R1"
echo "  CAP  : $CAP"
echo "  NOISE: $NOISE"
if [ "$R1" != "FALSE" ] && [ "$R1" != "" ]; then
    echo "  R1 match: $R1_LINE"
fi

if [[ "$R1" == PASS* ]] && [ "$CAP" = "PASS" ] && [ "$NOISE" = "PASS" ]; then
    echo "E2E PASS"
    exit 0
else
    echo "E2E FAIL (or NOT-DETERMINED)"
    exit 1
fi
