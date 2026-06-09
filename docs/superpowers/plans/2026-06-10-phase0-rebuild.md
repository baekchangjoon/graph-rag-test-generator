# Phase 0 Rebuild Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 샘플 SUT의 JPA-only `POST /api/orders` endpoint에 대해 build → graph → generate → run → pass 전 사이클을 통과시킨다.

**Architecture:** Gradle 멀티모듈(Java 17). 도구 1(graph-rag-builder)은 Spoon 인덱싱 + Testcontainers Postgres + SUT 외부 프로세스 실행 + Hibernate SQL 로그 파싱으로 JSON 그래프를 산출. 도구 2(test-generator)는 그래프 + GenerationRequest를 입력으로 Mustache 템플릿 + Composer로 RestAssured 테스트를 결정적으로 합성. 생성 테스트는 testlib에 의존하고 docker-compose 환경에서 실행.

**Tech Stack:** Java 17, Gradle (Kotlin DSL), Spring Boot 3.3.x, Spoon 10.x, Testcontainers, Jackson, Mustache (com.github.spullara.mustache.java), Netty, RestAssured, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-06-10-phase0-rebuild-design.md`

**진행 규약:** 각 태스크는 TDD(테스트 먼저 → 최소 구현 → 리팩터). 태스크 완료 시 commit + `progress/0-<step>.md` 기록. 주요 의사결정은 `docs/decisions/<topic>.md`.

---

### Task 1 (0.1): Gradle 멀티모듈 골격

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, wrapper
- Create: 각 모듈 디렉터리 + 빈 `build.gradle.kts`

**Steps:**
- [ ] 모듈: `shared-model`, `testlib`, `test-state-dashboard`, `socket-mock-server`, `graph-rag-builder`, `test-generator`, `samples:order-service`, `e2e`
- [ ] 공통 컨벤션: java toolchain 17, JUnit 5 platform, `-Werror` 없음(외부 라이브러리 경고 때문에 보류)
- [ ] version catalog로 의존성 버전 단일화
- [ ] Run: `./gradlew build` → BUILD SUCCESSFUL
- [ ] Commit + `progress/0-1.md`

### Task 2 (0.2): shared-model

**Files:**
- Create: `shared-model/src/main/java/io/graphrag/model/*.java`
- Test: `shared-model/src/test/java/io/graphrag/model/JsonRoundTripTest.java`

**핵심 계약 (record + Jackson):**

```java
// 그래프 사실
record Endpoint(String id, String httpMethod, String path, String handlerClass,
                String handlerMethod, List<EndpointParam> params, boolean authRequired)
record EndpointParam(String name, String javaType, ParamKind kind)   // kind: BODY|PATH|QUERY|HEADER
record ExploredPath(String id, String endpointId, JsonNode sampleInput,
                    int expectedStatus, JsonNode sampleResponse, List<String> capturedSqlIds)
record CapturedSql(String id, String pathId, String sqlKind,         // INSERT|SELECT|UPDATE|DELETE
                   String normalizedSql, String tableName, List<SqlBinding> bindings)
record SqlBinding(int position, String column, String value, BindingOrigin origin) // API_PARAM|LITERAL|COMPUTED
record TableSchema(String name, List<ColumnSchema> columns, List<ForeignKey> foreignKeys,
                   List<List<String>> uniqueKeys)
record ColumnSchema(String name, String jdbcType, boolean nullable, boolean primaryKey)
record ForeignKey(String column, String referencedTable, String referencedColumn)
record GraphAsset(String sutId, String commitSha, List<Endpoint> endpoints,
                  List<ExploredPath> paths, List<CapturedSql> sql, List<TableSchema> tables)

// 도구 2 계약
record GenerationRequest(String endpointId, String pathId, String testClassName,
                         String packageName, AuthMode authMode)      // REAL|DISABLED
record GenerationResult(List<GeneratedFile> files, List<String> warnings,
                        ParallelSafetyReport parallelSafety)
record GeneratedFile(String relativePath, String content)
record ParallelSafetyReport(List<String> fullyParallel, List<SerialRequired> serialRequired)

// 대시보드 계약
record TestEvent(EventType type, String testId, String runId, Instant at, JsonNode detail)
// EventType: SCOPE_CREATED, SCOPE_CLEANED, DB_ROW_INSERTED, DB_ROW_DELETED,
//            HTTP_STUB_REGISTERED, HTTP_STUB_REMOVED, SOCKET_SESSION_OPENED,
//            SOCKET_SESSION_CLOSED, AUTH_TOKEN_ISSUED
```

- [ ] 라운드트립 테스트 먼저 (serialize → deserialize → equals)
- [ ] `Json.mapper()` 정적 유틸 (JavaTimeModule, FAIL_ON_UNKNOWN=false)
- [ ] Run: `./gradlew :shared-model:test` → PASS, Commit + `progress/0-2.md`
- [ ] Decision doc: `docs/decisions/shared-model-schema.md` (SCHEMAS.md 유실 → 재정의 근거)

### Task 3 (0.3): testlib

**Files:**
- Create: `testlib/src/main/java/io/graphrag/testlib/api/{TestScope,JdbcHelper,RestAssuredHelper,HttpMockClient,SocketMockClient,AuthClient}.java`
- Create: `testlib/src/main/java/io/graphrag/testlib/spi/*.java` + `adapter/*`
- Test: `testlib/src/test/java/...`

**계약:**

```java
TestScope.create()           // env 검증(APP_BASE_URI, JDBC_URL 필수; 없으면 IllegalStateException)
scope.testId()               // "t-" + 8 hex (SecureRandom)
scope.jdbc().update(sql, args...)        // insert/delete 시 dashboard 이벤트 발행
scope.rest().given()                     // RestAssured RequestSpecification (baggage 헤더 포함)
scope.cleanup()                          // mock 해제 + SCOPE_CLEANED 발행
```

- SPI: `HttpMockAdapter`, `SocketMockAdapter`, `JdbcAdapter`, `AuthAdapter`, `DashboardReporter` — ServiceLoader + env(`HTTP_MOCK_ADAPTER` 등) 선택
- Phase 0 어댑터: `plain-jdbc`(실동작), `dashboard-http`(fire-and-forget, 200ms timeout) / `dashboard-noop`, `http-mock-noop`, `socket-mock-noop`, `auth-noop`
- [ ] 테스트: env 누락 fail-fast / ServiceLoader 선택 / noop 동작 / dashboard-http는 MockWebServer 또는 자체 HttpServer로 수신 검증
- [ ] Run: `./gradlew :testlib:test` → PASS, Commit + `progress/0-3.md`

### Task 4 (0.4): test-state-dashboard

**Files:**
- Create: `test-state-dashboard/src/main/java/...` (Spring Boot web)
- Test: 단위(상태 머신, 누수 감지) + `@SpringBootTest` 슬라이스
- Create: `test-state-dashboard/Dockerfile`

**API:** `POST /events`(TestEvent), `GET /active`, `GET /test/{testId}`, `GET /leaked`

- [ ] `TestRunStore`(in-memory, ConcurrentHashMap) 단위 테스트: SCOPE_CREATED→ACTIVE, SCOPE_CLEANED→CLEANED, 자원 이벤트 누적
- [ ] `LeakDetector` 단위 테스트: 주입된 Clock으로 TTL 경과 시 ACTIVE→LEAKED
- [ ] Run: `./gradlew :test-state-dashboard:test` → PASS, Commit + `progress/0-4.md`

### Task 5 (0.5): socket-mock-server

**Files:**
- Create: `socket-mock-server/src/main/java/...` (Netty + 경량 admin HTTP)
- Test: 단위 + 통합(로컬 포트 바인딩, 클라이언트 소켓으로 송수신)
- Create: `socket-mock-server/Dockerfile`

**Admin API:** `POST /__admin/expectations` `{listenPort, onReceiveHex, respondWithHex, matchMode: EXACT|PREFIX}`, `DELETE /__admin/expectations`

- [ ] `ExpectationRegistry` + hex 매칭 단위 테스트
- [ ] 통합 테스트: expectation 등록 → TCP connect → bytes 송신 → 응답 bytes 검증
- [ ] Run: `./gradlew :socket-mock-server:test` → PASS, Commit + `progress/0-5.md`

### Task 6: samples/order-service (SUT)

**Files:**
- Create: `samples/order-service/` Spring Boot 3 앱 (`User`, `Order` 엔티티, `OrderController`)
- Test: `@SpringBootTest` + Testcontainers Postgres로 201/404/400 검증
- Create: `samples/order-service/Dockerfile`

**Endpoint:** `POST /api/orders` body `{"userId": str, "amount": int, "type": str}` → 201 `{"id": ..., "status": "PENDING"}` / user 미존재 404 / amount<=0 또는 누락 400

- [ ] DDL: `users(id varchar PK, name varchar not null)`, `orders(id bigserial PK, user_id varchar FK→users not null, amount int not null, type varchar not null, status varchar not null)` — `spring.jpa.hibernate.ddl-auto=create` 기반
- [ ] Run: `./gradlew :samples:order-service:test` → PASS, `bootJar` 산출 확인, Commit + progress 기록(0-6은 builder 단계라 SUT는 `progress/0-sut.md`)

### Task 7 (0.6): graph-rag-builder

서브태스크로 분해. 각각 TDD + commit.

**7a — EndpointIndexer (Spoon):**
- `EndpointIndexer.index(Path sutSrcDir) -> List<Endpoint>`
- 테스트 fixture: 리소스의 샘플 컨트롤러 소스 → `POST /api/orders` 1개, BODY 파라미터 타입 추출
- Spoon noClasspath 모드 (SUT 의존성 해석 없이 어노테이션/시그니처만)

**7b — GraphStore:**
- `interface GraphStore { void save(GraphAsset); GraphAsset load(); }` + `JsonFileGraphStore(dir)` — `graph.json` 단일 파일
- 라운드트립 테스트

**7c — AnalysisEnvironment:**
- Testcontainers `PostgreSQLContainer` 기동 + `SutProcess`: `java -jar <jar>` + env(`SPRING_DATASOURCE_URL` 등, Hibernate SQL/bind 로깅 활성, `SERVER_PORT` 랜덤) + stdout 파일 캡처 + 헬스 폴링(`GET /actuator/health` 또는 TCP)
- 테스트: 실제 order-service jar로 기동/종료 (Docker 필요 태그)

**7d — HibernateSqlLogParser:**
- 입력: SUT stdout 로그 텍스트, 출력: `List<CapturedSql>` (sql + binding 순서)
- 로그 형식: `org.hibernate.SQL` 라인의 SQL + `org.hibernate.orm.jdbc.bind` 라인의 `binding parameter (N:VARCHAR) <- [value]`
- 순수 단위 테스트 (고정 로그 fixture)

**7e — SchemaExtractor:**
- JDBC `DatabaseMetaData` → `List<TableSchema>` (FK, PK, nullable, unique index)
- Testcontainers Postgres에 DDL 적용 후 검증

**7f — PathCaptureRunner:**
- `SampleInputSynthesizer`: Endpoint의 BODY 타입 필드에서 결정적 값 (`String→"sample-<field>"`, `int→1` 등). user FK 사전 데이터는 직접 INSERT 후 그 id 사용
- 흐름: 사전 user INSERT → 로그 마커 → HTTP POST → 응답 기록 → 로그 파싱 → binding origin 판정(요청 값과 일치=API_PARAM, 응답/시퀀스 유래=COMPUTED, 그 외=LITERAL) → `ExploredPath` + `CapturedSql`
- 통합 테스트 (Docker 태그)

**7g — BuilderCli:**
- `build --sut-src <dir> --sut-jar <jar> --out <graph-dir>` → 위 전체 오케스트레이션 → `graph.json`
- E2E-lite 테스트: 실제 SUT로 graph.json 생성 후 내용 단언

- [ ] 각 서브태스크 Run: `./gradlew :graph-rag-builder:test` → PASS, Commit
- [ ] Decision docs: `builder-spoon-only.md`, `builder-external-process.md`, `builder-sql-capture.md`
- [ ] `progress/0-6.md`

### Task 8 (0.7): test-generator

**8a — GraphRagClient:** `interface GraphRagClient { Endpoint endpoint(String id); ExploredPath path(String id); List<CapturedSql> sqlForPath(String id); List<TableSchema> tables(); }` + `FileGraphRagClient(graphDir)`

**8b — FixtureComposer:** captured INSERT 중 사전 데이터(테스트 대상 요청 이전 INSERT) → FK 토폴로지 정렬 → `jdbc.update("INSERT INTO users(id,name) VALUES (?,?)", ...)` 라인 + 역순 DELETE 라인. API_PARAM 값은 `scope.testId()` 기반 치환 변수로.

**8c — 템플릿 + TestClassRenderer:** Mustache(`test-class.mustache`) — @BeforeAll(env config), @BeforeEach(scope+fixture), @AfterEach(cleanup), @Test(RestAssured given/when/then, status + 응답 필드 단언)

**8d — GeneratorCli:** `generate --request <json> --graph <dir> --out <dir>` → `.java` + `generation-result.json`

- [ ] 결정성 테스트: 같은 입력 2회 → 산출물 byte-identical
- [ ] 황금 파일(golden file) 테스트: 고정 graph fixture → 기대 `.java` 정확 일치
- [ ] Run: `./gradlew :test-generator:test` → PASS, Commit
- [ ] Decision doc: `generator-templates.md`, `progress/0-7.md`

### Task 9 (0.8): Phase 0 E2E

**Files:**
- Create: `e2e/docker-compose.yml` (postgres:15, order-service, wiremock, socket-mock-server, test-state-dashboard)
- Create: `e2e/run-phase0.sh` — builder 실행 → generator 실행 → 생성 테스트를 `e2e` 모듈 testSrc로 복사 → compose up → `./gradlew :e2e:test` → compose down
- Create: `e2e/build.gradle.kts` (testlib + RestAssured 의존, 생성 테스트 컴파일/실행)

- [ ] compose 환경에서 생성 테스트 PASS
- [ ] 같은 사이클 재실행 시에도 PASS (결정성/cleanup 검증)
- [ ] Run 기록 + `progress/0-8.md` + 루트 `README.md` (전체 사이클 runbook)
- [ ] Phase 0 성공 기준 체크리스트 (spec 참조) 전부 확인 후 commit

---

## Self-Review 결과

- Spec 커버리지: 0.1~0.8 전 단계 태스크 존재. 의사결정 문서/Progress 기록 단계 포함. OK
- 타입 일관성: shared-model 계약을 7/8에서 그대로 사용. OK
- 보류 항목(scip-java, HTTP query API 등)은 spec의 보류 표와 일치. OK
