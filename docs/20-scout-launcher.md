# 20 — `scout-launcher` (CLI orchestrator)

`scout-launcher` is a thin Gradle subproject that wraps graph-rag-builder to take an
external SUT JAR + a YAML config and run a full **deps → SUT → HTTP scout → archive
dump** cycle on the user's behalf. Replaces the per-project boilerplate previously
documented in [`docs/11`](11-datasource-proxy-wrap.md) §6 (scout test pattern) for the
out-of-process / jar-only case.

## 1. What it does (and does not)

| Step | Done by | Notes |
|---|---|---|
| Boot dependencies (Postgres, Redis, Kafka, …) | **launcher** via `docker compose up -d` + healthcheck wait | Skip if no `dependencies.docker-compose` section |
| Boot the SUT JVM | **launcher** via `ProcessBuilder` | Composes `-javaagent`, `-Xbootclasspath/a`, `-D…`, `-jar` or `-cp/main-class` |
| Wait for SUT readiness | **launcher** polls `health-check.url` | Configurable timeout/interval |
| Issue HTTP scout requests | **launcher** with `X-Graphrag-Path-Id` + `baggage` header | Each step in `scout.steps` becomes one request |
| Capture SQL / propagate path-id | **SUT-side wiring** (jdbc-intercept-agent + graph-rag bridge) | Bridge auto-creates `CaptureContext` per path-id |
| Dump per-path archive on shutdown | **SUT-side wiring** ([`ArchiveShutdownWriter`](../graph-rag-builder/src/main/java/io/graphrag/builder/capture/ArchiveShutdownWriter.java)) | Triggered by `-Dgraphrag.archive.output.dir=<dir>` the launcher injects |
| Synthesize test code from archive | **out of scope** — invoke `test-generator --archive …` separately | Each path is its own subdir |

The launcher **does not** instrument or inject anything into the SUT classpath beyond
what's spelled out in the YAML. All capture mechanics still live in
graph-rag-builder + jdbc-intercept-agent.

## 2. CLI

```bash
./gradlew :scout-launcher:installDist
./scout-launcher/build/install/scout-launcher/bin/scout-launcher [path/to/config.yml]
```
- Default config path: `./config.yml`
- Exits non-zero on any stage failure; cleanup always runs (SUT SIGTERM, compose down).

## 3. Config schema (`config.yml`)

```yaml
sut:
  # Pick one launch mode:
  jar: /path/to/sut.jar                     # -jar mode (Spring Boot fat jar)
  # main-class: com.example.Main             # OR -cp + main-class
  # classpath: [/path/app.jar, /path/lib.jar]
  args: ["--server.port=8084", "--spring.profiles.active=postgres"]
  jvm-args: ["-Xmx512m"]
  agents:
    - /path/jdbc-intercept-agent-core.jar
    # Optionally add OTEL agent here for cross-thread baggage propagation:
    # - /path/opentelemetry-javaagent.jar
  boot-classpath:    # -Xbootclasspath/a — needed so agent's ServiceLoader finds the bridge
    - ~/.m2/.../graph-rag-builder.jar
    - ~/.m2/.../shared-model.jar
    - ~/.m2/.../agent-api.jar
    - ~/.m2/.../jackson-databind.jar
    - ~/.m2/.../jackson-core.jar
    - ~/.m2/.../jackson-annotations.jar
  system-properties:                         # passed as -Dkey=value
    spring.datasource.url: jdbc:postgresql://localhost:55432/petclinic
  health-check:
    url: http://localhost:8084/actuator/health
    timeout-seconds: 60
    interval-millis: 1000

dependencies:                                # optional
  docker-compose:
    file: ./docker-compose.yml
    services: [postgres, redis, kafka]       # empty/omitted = all in compose file
    wait-for-healthy: true
    teardown-on-exit: true
    health-timeout-seconds: 120

scout:
  base-url: http://localhost:8084
  steps:
    - path-id: list-owners                   # → CaptureContext key, archive subdir name
      method: GET
      path: /api/owners
    - path-id: create-owner
      method: POST
      path: /api/owners
      content-type: application/json
      body: '{"firstName":"S","lastName":"U","address":"a","city":"b","telephone":"0101111111"}'
      expected-status: 201                    # 0 / omitted = don't assert
      headers:
        X-Custom: scout

output:
  archive-dir: /tmp/graph-rag-scout/petclinic-archive
  clear-before-run: true
```

## 4. Path-id propagation — how the bridge sees it

Every scout request carries two redundant carriers so capture works under either propagation model:

```
GET /api/owners/1 HTTP/1.1
X-Graphrag-Path-Id: get-owner-1
baggage: graphrag.path-id=get-owner-1
```

| Model | Carrier the bridge reads | Requires |
|---|---|---|
| OTEL agent attached | `baggage:` (extracted by OTEL into `Baggage.current()`) | `opentelemetry-javaagent.jar` in `agents` |
| No OTEL — same-thread invoke only | `JdbcCaptureSession.currentId()` (ThreadLocal) | scout test directly invokes repositories (Phase 7 pattern). **Out-of-process scout (this launcher) on Servlet handler thread requires OTEL** |
| Custom Servlet filter (future) | `X-Graphrag-Path-Id` header → ThreadLocal | not yet implemented in bridge |

→ For HTTP-based scout against a real SUT, attach OTEL javaagent as the second
`agents:` entry. See [`docs/19`](19-jdbc-intercept-agent-otel-coexistence.md) §6 for the
exact OTEL options to disable its JDBC instrumentation so it doesn't double up.

## 5. Archive layout produced

```
<output.archive-dir>/
├── list-owners/
│   ├── captured_sql.json     # CapturedSql list (incl. snapshotRows for SELECTs)
│   ├── captured_http.json
│   ├── endpoints.json        # empty stub — fill via test-generator --endpoint flag
│   └── paths.json            # empty stub
├── get-owner-1/...
└── create-owner/...
```

Each subdir is a complete graph-rag archive ready for:
```bash
test-generator --archive <dir>/<path-id> \
               --endpoint "GET:/api/owners/1" \
               --package com.example.gen \
               --out /tmp/gen
```

## 6. Pipeline sequence + cleanup guarantees

```
prepareOutputDir
  ↓
DockerComposeOrchestrator.start()                    ── docker compose up -d + healthcheck
  ↓
  SutProcessOrchestrator.start()                     ── ProcessBuilder + health URL poll
    ↓
    HttpScout.run()                                  ── for each step → HTTP w/ headers
    ↓
  SutProcessOrchestrator.close()  (SIGTERM, 30s wait, then SIGKILL)
    └─ SUT shutdown hook → ArchiveShutdownWriter.dump → per-path JSON files
  ↓
DockerComposeOrchestrator.close()                    ── docker compose down (if teardown-on-exit)
```

Each `AutoCloseable` is a `try-with-resources` so any failure short-circuits
later stages but always triggers earlier-stage cleanup in reverse order.

## 7. What you still need to do manually

| Task | Why |
|---|---|
| Publish `jdbc-intercept-agent` + `graph-rag-builder` to `~/.m2` | Boot classpath entries in `config.yml` point at those artifacts |
| List boot-classpath jars explicitly | We don't ship a single "scout bridge bundle" yet — explicit list is honest about what the SUT JVM loads |
| Provide your own `docker-compose.yml` | Launcher does not generate one. The sample at `samples/scout/petclinic/docker-compose.yml` is a starting point |
| Invoke `test-generator` after capture | Out of scout-launcher scope. Per-path archive is ready |
| Attach OTEL agent if SUT serves HTTP (not invoking repos directly) | Required for path-id propagation across Servlet handler threads |

## 8. Sample

`samples/scout/petclinic/`
- `config.yml` — petclinic SUT on Postgres
- `docker-compose.yml` — Postgres + Redis + Kafka (Redis/Kafka illustrative; petclinic uses Postgres only)
- `README.md` — step-by-step run instructions

## 9. Not in V1 (future)

- Auto-publish graph-rag/agent artifacts on demand (`scout-launcher init` to call publishToMavenLocal)
- Single "scout bridge bundle" shaded jar to replace the multi-line boot-classpath
- Servlet filter in bridge that reads `X-Graphrag-Path-Id` header → ThreadLocal (so OTEL becomes optional)
- Auto-fill endpoints/paths/handler metadata in archive (currently empty stubs)
- Invoke `test-generator` as a pipeline stage (so single command goes scout → synthesize → archive)
- Parallel scout step execution (currently sequential — safe by default)

## References
- [`docs/11`](11-datasource-proxy-wrap.md) — in-process scout pattern (datasource-proxy)
- [`docs/12`](12-option-a-row-snapshot-design.md) — Option A SELECT row snapshot
- [`docs/19`](19-jdbc-intercept-agent-otel-coexistence.md) — agent + OTEL coexistence
- [`samples/scout/petclinic/README.md`](../samples/scout/petclinic/README.md)
