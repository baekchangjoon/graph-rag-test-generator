# T1 PoC — manual archive seed → test-generator

This sample proves that the **shared-model JSON schema is the single, stable
contract between Stage 1 (static path discovery) and Stage 4 (test synthesis)**.

A future static analyzer that emits `paths.json` + `endpoints.json` in the shape
documented here will plug straight into `test-generator --archive` with no
test-generator changes required.

---

## Files in this directory

| File | Stage that writes it | Purpose |
|---|---|---|
| `manual-archive-seed/endpoints.json` | Stage 1 (here: hand-authored) | One [`Endpoint`](../../../shared-model/src/main/java/io/graphrag/model/Endpoint.java) per REST handler. Key is `"METHOD:path"`. |
| `manual-archive-seed/paths.json` | Stage 1 (here: hand-authored) | One [`ExploredPath`](../../../shared-model/src/main/java/io/graphrag/model/ExploredPath.java) per exploration outcome. `id` is the path-id; `endpoint_id` joins back to endpoints.json. |
| `manual-archive-seed/captured_sql.json` | Stage 3 (`scout-launcher` + `jdbc-intercept-agent` bridge) | Empty here — only populated when a real SUT runs under scout. |
| `manual-archive-seed/captured_http.json` | Stage 3 (`scout-launcher`'s outbound HTTP listener) | Empty here. |
| `config.yml` | (you) | scout-launcher driver. Uses the same path-ids as the manual seed so a future combined run merges cleanly. |

> **Schema note**: every JSON field is **snake_case** because
> `JsonMappers.standard()` configures `PropertyNamingStrategies.SNAKE_CASE`. So
> Java `handlerClass` ⇄ JSON `handler_class`. Don't camelCase by accident.

---

## Run the PoC

### Quickest path — verify test-generator accepts the seed (~10 s, no SUT needed)

```bash
# from repo root
./gradlew :test-generator:installDist

./test-generator/build/install/test-generator/bin/test-generator \
  --archive samples/scout/petclinic/manual-archive-seed \
  --endpoint "GET:/api/owners" \
  --package com.example.petclinic.tests \
  --out /tmp/t1-poc-out
```

Expected output: `wrote: /tmp/t1-poc-out/com/example/petclinic/tests/OwnersGetTest.java`

Repeat with `"GET:/api/owners/{ownerId}"` and `"GET:/api/vets"` to confirm all
three seeded paths flow through.

The same flow is asserted automatically by
[`ManualArchiveSeedE2eTest`](../../../test-generator/src/test/java/io/graphrag/generator/poc/ManualArchiveSeedE2eTest.java) —
run it with:

```bash
./gradlew :test-generator:test \
  --tests io.graphrag.generator.poc.ManualArchiveSeedE2eTest
```

It compiles the synthesized Java in-memory via `JavaSourceCompiler`, so a green
test proves both **schema acceptance** *and* **javac compilability** in one
shot. This is the gate T1 ships behind.

### Full pipeline (with scout-launcher + Postgres + petclinic SUT)

Pre-requisites are the same as the existing scout-launcher demo (see
`config.yml` header). The interesting change vs the existing demo is

```yaml
output:
  clear-before-run: false
```

…which leaves any pre-existing files in `archive-dir` alone, so the manual seed
survives the `prepareOutputDir()` wipe.

```bash
# copy the manual seed into the archive root that scout-launcher uses
mkdir -p /tmp/graph-rag-scout/petclinic-archive
cp samples/scout/petclinic/manual-archive-seed/*.json \
   /tmp/graph-rag-scout/petclinic-archive/

# run scout-launcher
./scout-launcher/build/install/scout-launcher/bin/scout-launcher \
  samples/scout/petclinic/config.yml
```

**Known gap** (motivates T3): `ScoutMetadataWriter` runs after the SUT shuts
down and overwrites `endpoints.json` + `paths.json` inside each per-path
subdirectory with a simpler scout-derived version. Until T3 adds the
`preserve-files` option, the only way to keep rich Stage 1 fields
(`branches_taken`, `coverage_signature`, ...) intact through a scout run is
to keep the seed in a separate directory and merge after the fact.

---

## Acceptance criteria (T1)

- [x] Hand-authored `paths.json` + `endpoints.json` deserialize via
  `ArchiveReader.load(...)` without any shared-model changes.
- [x] `test-generator --archive --endpoint <id> --package <pkg> --out <dir>`
  succeeds for all three seeded endpoints.
- [x] Synthesized Java compiles under `JavaSourceCompiler` (locked in by
  `ManualArchiveSeedE2eTest`).
- [x] `clear-before-run: false` documented in `config.yml`.
- [x] Procedure captured here in five steps (build → seed → generate → verify →
  document gap).

## Out of scope (covered by later tasks)

- **T2** — programmatic translator that turns `paths.json` into `config.yml`
  `scout.steps[]` automatically (replaces hand-authored config.yml).
- **T3** — `output.preserve-files: [paths.json, endpoints.json]` so the manual
  seed *survives* a scout run end-to-end, and `strict-mode` so silent
  expected-status mismatches don't pollute the archive (risk R3).
- **T4** — the real Stage 1: AST-based discovery that writes paths.json /
  endpoints.json instead of you doing it by hand.

---

## Risk reminders (see `graph-rag-test-generator-risks.md`)

- **R2 (path-id consistency)** — the seed uses human-readable slugs
  (`static_list-owners`, `static_get-owner-1`, `static_list-vets`) rather than
  ULIDs so debugging stays trivial. The T2 translator will copy this id field
  unchanged into `scout.steps[].path-id`, so the entire join chain
  `paths.id → CapturedSql.path_id` stays grep-able.
