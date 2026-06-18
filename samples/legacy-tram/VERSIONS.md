# Pinned versions (검증: gradle dependencies 해소 + docker manifest inspect)

- Java: 8
- Spring Boot: 2.7.18
- Spring Cloud (Sleuth): 2021.0.8  →  spring-cloud-starter-sleuth resolves to 3.1.9
- Eventuate Tram core: 0.35.0.RELEASE
- Eventuate Tram Sleuth integration: `io.eventuate.tram.springcloudsleuth:eventuate-tram-spring-cloud-sleuth-tram-starter:0.5.0.RELEASE`
  - NOTE: `io.eventuate.tram.core:eventuate-tram-spring-cloud-sleuth-integration` was discontinued
    after 0.29.0.RELEASE (targets Boot ≤2.5). The current integration lives in the separate
    `io.eventuate.tram.springcloudsleuth` group and is tested with Tram core 0.35.0.RELEASE.
- eventuate-cdc-service image: `eventuateio/eventuate-cdc-service:0.17.0.RELEASE`
  - Confirmed: docker manifest inspect shows multi-arch (amd64 + arm64) image exists
- MySQL: 8.0 (binlog ROW)  →  mysql:mysql-connector-java resolves to 8.0.28 via Boot 2.7 BOM

## Validation evidence

Resolution tool: `docker run --rm -v "$PWD/samples/legacy-tram/reservation":/src -w /src gradle:7.6-jdk8 gradle dependencies --configuration runtimeClasspath -q --no-daemon`

Result: clean tree, zero FAILED/CONFLICT/unresolved entries. Key resolved versions:
```
+--- org.springframework.boot:spring-boot-starter-web -> 2.7.18
+--- org.springframework.cloud:spring-cloud-starter-sleuth -> 3.1.9
+--- io.eventuate.tram.core:eventuate-tram-spring-jdbc-kafka:0.35.0.RELEASE
+--- io.eventuate.tram.core:eventuate-tram-spring-events:0.35.0.RELEASE
+--- io.eventuate.tram.springcloudsleuth:eventuate-tram-spring-cloud-sleuth-tram-starter:0.5.0.RELEASE
\--- mysql:mysql-connector-java -> 8.0.28
```

## Version adjustment notes (from brief starting points)

| Component | Brief starting point | Confirmed version | Reason |
|---|---|---|---|
| Eventuate Tram core | 0.33.0.RELEASE | **0.35.0.RELEASE** | 0.33.x targets Boot 2.7.14; 0.35.x is the latest stable Boot-2.7 line (2024-09) |
| Sleuth integration | `io.eventuate.tram.core:eventuate-tram-spring-cloud-sleuth-integration:0.33.0.RELEASE` | **`io.eventuate.tram.springcloudsleuth:eventuate-tram-spring-cloud-sleuth-tram-starter:0.5.0.RELEASE`** | Original artifact discontinued after 0.29.0.RELEASE; current module is in a separate group |
| CDC image | (not specified in brief) | **0.17.0.RELEASE** | Corresponds to Tram core 0.35.x (0.17.x CDC schema generation) |
