# scout-step-translator

Stage 2 of the 6-stage pipeline. Reads Stage 1's `paths.json` + `endpoints.json`
(shared-model JSON; same shape used everywhere else in graph-rag) and emits a
scout-launcher `config.yml` whose `scout.steps[]` is mechanically derived from
each `ExploredPath`.

The `sut`, `dependencies`, and `output` sections come from a caller-supplied
template — the translator only owns the `scout` section, so environment-specific
knobs stay one place to edit.

## CLI

```bash
./gradlew :scout-step-translator:installDist

./scout-step-translator/build/install/scout-step-translator/bin/scout-step-translator \
  --paths-file          path/to/paths.json \
  --endpoints-file      path/to/endpoints.json \
  --scout-base-url      http://localhost:8084 \
  --sut-config-template path/to/template.yml \
  --out                 generated/config.yml
```

Exit codes:

| code | meaning |
|---|---|
| 0 | success |
| 2 | missing/unknown CLI flag |
| 3 | `paths.json` references an `endpoint_id` not in `endpoints.json` (orphan path — usually a Stage 1 bug) |
| 4 | I/O or unexpected error |

## What gets copied where

| Source field (paths.json) | Destination (config.yml) | Notes |
|---|---|---|
| `id` | `scout.steps[].path-id` | **Verbatim.** Downstream `ArchiveReader` joins `captured_sql.path_id` back to this exact string. Renaming silently breaks the join (R2). |
| `endpoint_id` → `endpoints.json[].method` | `scout.steps[].method` | |
| `endpoint_id` → `endpoints.json[].path` | `scout.steps[].path` | After substituting `path_params` and appending `query_params`. |
| `sample_input.path_params.{k}` | `{k}` placeholder in path | E.g. `/api/owners/{ownerId}` + `{ownerId:"1"}` → `/api/owners/1`. Spaces in values percent-encode to `%20`. |
| `sample_input.query_params` | `?k=v&k2=v2` appended to path | Alphabetically sorted so output is deterministic across `Map` implementations. |
| `sample_input.body` (Map/List/Number/Boolean) | `scout.steps[].body` (JSON string) + `content-type: application/json` | |
| `sample_input.body` (String) | `scout.steps[].body` + `content-type: text/plain` | |
| `sample_input.headers.Content-Type` | `scout.steps[].content-type` | Hoisted from headers so HttpScout doesn't send two copies. |
| `sample_input.headers.*` (other) | `scout.steps[].headers` | |
| `exit_status` | `scout.steps[].expected-status` | T3's `--strict` mode will quarantine paths whose live status differs (R3). |

## Risk mitigation

- **R2 (path-id consistency)** — the converter never rewrites `id`. A unit test
  in `ExploredPathToCaptureStepTest` locks this. Use human-readable slugs
  (`static_list-owners`) rather than ULIDs when authoring `paths.json` by hand
  so debugging stays grep-friendly.
- **R3 (silent expected-status mismatch)** — `expected_status` is set so the
  current `HttpScout` already WARNs. T3 turns those WARNs into a quarantine.

## Determinism

Both `path_params` substitution and `query_params` ordering are deterministic
given the same input — query params are sorted alphabetically, and JSON
serialization uses `JsonMappers.standard()`. Re-running with the same
`paths.json` + `endpoints.json` produces byte-identical config.yml.
