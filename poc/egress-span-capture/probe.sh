#!/usr/bin/env bash
# Drive one /orders request through order-web with an injected B3 trace context,
# then inspect the Zipkin spans Brave exported to the host receiver.
set -euo pipefail

SPANS="${1:?spans ndjson path}"
ORDER_WEB="${ORDER_WEB:-http://localhost:18080}"

# Identifiable injected B3 context (mirrors B3TraceId.headers(): 32-hex trace / 16-hex span).
TRACE_ID="1234567890abcdef1234567890abcdef"
SPAN_ID="1234567890abcdef"

echo "== POST /orders with injected B3 trace=$TRACE_ID =="
code=$(curl -s -o /tmp/orders-resp.json -w '%{http_code}' \
  -X POST "$ORDER_WEB/orders" \
  -H 'Content-Type: application/json' \
  -H "X-B3-TraceId: $TRACE_ID" \
  -H "X-B3-SpanId: $SPAN_ID" \
  -H "X-B3-Sampled: 1" \
  -H "b3: $TRACE_ID-$SPAN_ID-1" \
  -d '{"userId":"u1","amount":100}')
echo "HTTP $code  body=$(cat /tmp/orders-resp.json)"

echo "== waiting up to 15s for Brave to flush spans =="
for _ in $(seq 1 30); do
  if grep -q "$TRACE_ID" "$SPANS" 2>/dev/null; then break; fi
  sleep 0.5
done

echo "== spans carrying injected trace $TRACE_ID =="
grep "$TRACE_ID" "$SPANS" 2>/dev/null | python3 -m json.tool --json-lines 2>/dev/null \
  || grep "$TRACE_ID" "$SPANS" 2>/dev/null || echo "(none)"

echo "== analysis =="
python3 - "$SPANS" "$TRACE_ID" <<'PY'
import json, sys
spans_path, trace_id = sys.argv[1], sys.argv[2]
rows = []
with open(spans_path, encoding="utf-8") as f:
    for line in f:
        line = line.strip()
        if line:
            try:
                rows.append(json.loads(line))
            except Exception:
                pass
mine = [s for s in rows if s.get("traceId") == trace_id]
client = [s for s in mine if s.get("kind") == "CLIENT"]
print(f"total spans captured: {len(rows)}")
print(f"spans with injected traceId: {len(mine)}")
print(f"CLIENT (egress) spans with injected traceId: {len(client)}")
for s in client:
    tags = s.get("tags", {})
    print("  --- CLIENT span ---")
    print("  name        :", s.get("name"))
    print("  traceId     :", s.get("traceId"))
    print("  http tags   :", {k: v for k, v in tags.items() if k.startswith("http")})
    print("  remoteEndpoint:", s.get("remoteEndpoint"))
    print("  all tag keys:", sorted(tags.keys()))
PY
