# Coverage collection (graph-rag-builder)

## pjacoco per-trace flow

1. Builder generates a unique W3C `traceId` per HTTP probe.
2. Request is sent with `traceparent: 00-<traceId>-...-01`.
3. After the response, `PjacocoCoverageProbe.requestDelta(traceId)` collects coverage for that trace.

## Binary stop (pjacoco ≥ 1.4.0)

The hot path uses `POST /__coverage__/test/stop?format=binary` instead of polling
`<traceId>.exec` on disk:

| Response | Meaning |
|----------|---------|
| `200` + `application/octet-stream` | JaCoCo exec bytes in the response body |
| `204` | Empty store (no probes recorded for this trace) |
| `200` + `text/plain` (`stopped …`) | Legacy agent — builder falls back to `awaitExec` file poll |

Query parameters:

- `format=binary` — return exec in the response body
- `persist=true|false` — whether the agent also writes `<traceId>.exec` (+`.json` sidecar) to disk. Priority 1 uses `true` so `coverageTraceIds` resolve to on-disk `.exec` files (REQ-003) and sidecar summaries (REQ-006); reading the exec from the binary body still avoids the `awaitExec` poll race. (A later exploration-only optimization may switch this to `false`.)

Builder logs once per run: `pjacoco binary stop: enabled` or `fallback-to-file-poll`.

## Backward compatibility

- `format=text` (default) on the agent is unchanged.
- New builder + old agent: text stop + file poll fallback.
- Old builder + new agent: text stop still writes files synchronously.
