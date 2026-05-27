# scout-launcher sample — spring-petclinic

End-to-end demo of `scout-launcher` driving a real (private) SUT JAR with Postgres
provided by docker-compose, jdbc-intercept-agent attached for capture, and the
graph-rag bridge writing per-path archive directories on SUT shutdown.

## Run

Prereqs (one-time):
```bash
# Publish jdbc-intercept-agent + graph-rag-builder bridge to ~/.m2
cd ~/github_jdbc-intercept-agent/jdbc-intercept-agent && ./gradlew publishToMavenLocal
cd ~/graph-rag/graph-rag && ./gradlew -Pagent.enabled=true \
    :shared-model:publishToMavenLocal \
    :graph-rag-builder:publishToMavenLocal

# Build SUT
cd ~/github_spring-petclinic/spring-petclinic && mvn -DskipTests package
```

Launch:
```bash
cd ~/graph-rag/graph-rag
./gradlew :scout-launcher:installDist
./scout-launcher/build/install/scout-launcher/bin/scout-launcher \
    samples/scout/petclinic/config.yml
```

Expected output (abbreviated):
```
[scout] config: .../config.yml
[scout] docker compose -f .../docker-compose.yml up -d
[scout] docker compose services healthy: [graphrag-scout-pg graphrag-scout-redis graphrag-scout-kafka]
[scout] launching SUT: /path/java -javaagent:... -Xbootclasspath/a:... -jar petclinic.jar ...
[sut]   . _____ ... Spring Boot startup banner ...
[scout] SUT health check OK (200)
[scout] GET /api/owners        -> 200 (pathId=list-owners)
[scout] GET /api/owners/1      -> 200 (pathId=get-owner-1)
[scout] GET /api/vets          -> 200 (pathId=list-vets)
[scout] all scout steps issued; shutting down SUT to flush archive
[scout] sending SIGTERM to SUT (pid …)
[sut]   [graphrag] dumped 3 archive(s) to /tmp/graph-rag-scout/petclinic-archive
[scout] SUT exited with code 0
[scout] archive written under /tmp/graph-rag-scout/petclinic-archive
[scout] docker compose down
[scout] done
```

Then synthesize tests from each path:
```bash
test-generator --archive /tmp/graph-rag-scout/petclinic-archive/get-owner-1 \
               --endpoint "GET:/api/owners/1" \
               --package com.example.gen.petclinic \
               --out /tmp/gen/petclinic
```

## What the launcher does NOT do (yet)

- Inject endpoint/handler-class metadata into the archive — the scout knows HTTP
  pathIds but not Java handler classes. You provide them via `test-generator --endpoint`.
- Run `test-generator` automatically — separate step.
- Attach OTEL javaagent automatically — add it to `sut.agents` if you need cross-thread
  baggage propagation (see [docs/19](../../../docs/19-jdbc-intercept-agent-otel-coexistence.md)).
