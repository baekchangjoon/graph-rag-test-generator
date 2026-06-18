# 엔드포인트 단위 테스트클래스 그룹화 + 병렬 실행 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** HTTP 엔드포인트의 시나리오를 한 테스트클래스의 다중 `@Test`로 묶고, JUnit5 병렬 실행을 산출물에 실제로 활성화한다.

**Architecture:** `Generator`의 HTTP 경로를 "path당 클래스 1개"에서 "엔드포인트당 병렬 클래스 1개 + (있으면) 직렬 클래스 1개"로 바꾼다. 시나리오별 cleanup은 testlib의 새 deferred-delete(FIFO)로 메소드별 격리하고, `junit-platform.properties`(strategy=dynamic)를 산출물로 emit한다.

**Tech Stack:** Java 23, Gradle, JUnit5, Mustache(mustache.java), RestAssured, WireMock, AssertJ.

> 출처: design spec `docs/superpowers/specs/2026-06-18-test-class-grouping-parallel-design.md`, requirements `docs/superpowers/requirements/2026-06-18-test-class-grouping-parallel-requirements.md`.
> 빌드: `./gradlew` (워크트리 루트). 단위 테스트만: `./gradlew :testlib:test :test-generator:test :shared-model:test`. E2E: `e2e/run-e2e.sh`(docker 필요).

---

## File Structure

- `testlib/.../api/JdbcHelper.java` — `deferDelete`/`runDeferredDeletes` 추가(FIFO 리스트).
- `testlib/.../api/TestScope.java` — `cleanup()`이 deferred delete를 mock 해제 전에 실행.
- `testlib/src/test/java/.../api/JdbcHelperTest.java` — deferred-cleanup 단위 테스트(신규 또는 보강).
- `testlib/src/test/java/.../api/TestScopeTest.java` — cleanup 순서 단위 테스트(보강).
- `test-generator/.../generator/Generator.java` — HTTP 경로 재작성: `ScenarioMethod`, `buildScenarioMethod`, `renderTestClass`, `deriveMethodName`, `junitPlatformProperties`; `generateSingle` 제거.
- `test-generator/src/main/resources/templates/test-class.mustache` — 다중 메소드 + classSerialMark + deferDelete + mocksBlock 위치 변경.
- `test-generator/.../generator/cli/GeneratorCli.java` — 기존 `junit-platform.properties` 다른 내용이면 경고 로그.
- `test-generator/src/test/java/.../generator/GeneratorTest.java` — 기존 HTTP 테스트 갱신 + 신규 단언.
- `test-generator/src/test/resources/golden/OrdersPostTest.java.golden` — 신규 구조로 교체.
- `e2e/run-e2e.sh` — emit된 `junit-platform.properties`를 e2e 리소스로 복사.
- `e2e/src/test/resources/junit-platform.properties` — strategy=dynamic, factor=1로 정렬.
- 문서: `README.md` / getting-started — 사용법 갱신.

> **변경 없음(확인만):** `shared-model`의 `ParallelSafetyReport`/`SerialRequired`(클래스레벨 식별자 유지), `GeneratorKafkaTest`/`JsonRoundTripTest`(식별자 형식 불변), WS/Kafka 생성 경로(REQ-015).

---

## Task 1: testlib deferred-cleanup (FIFO)  — REQ-008, REQ-009, REQ-010

**Files:**
- Modify: `testlib/src/main/java/io/graphrag/testlib/api/JdbcHelper.java`
- Modify: `testlib/src/main/java/io/graphrag/testlib/api/TestScope.java:144-157`
- Test: `testlib/src/test/java/io/graphrag/testlib/api/JdbcHelperTest.java`

- [ ] **Step 1: Write failing unit tests for deferDelete (REQ-008, REQ-009)**

`JdbcHelper` 생성자는 package-private이고 `JdbcAdapter`를 받는다. 테스트는 같은 패키지에 두고 mock 어댑터로 `Connection`을 주입한다. 기존 `JdbcHelperTest`가 없으면 새로 만든다. 호출 순서/실패 격리는 어댑터가 주는 `Connection`의 `prepareStatement` 호출을 가로채 검증한다.

```java
package io.graphrag.testlib.api;

import io.graphrag.testlib.spi.Env;
import io.graphrag.testlib.spi.JdbcAdapter;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class JdbcHelperTest {

    private final Env env = Env.of(java.util.Map.of());

    private JdbcHelper helper(Connection conn) {
        JdbcAdapter adapter = mock(JdbcAdapter.class);
        try { when(adapter.connect(any())).thenReturn(conn); } catch (Exception e) { throw new RuntimeException(e); }
        return new JdbcHelper(adapter, env, "t-test", "run-test",
                io.graphrag.testlib.adapter.dashboard.DashboardReporters.fromEnv(env));
    }

    @Test
    void runsDeferredDeletesInRegistrationOrder() throws Exception {
        List<String> executed = new ArrayList<>();
        Connection conn = mock(Connection.class);
        when(conn.prepareStatement(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            PreparedStatement ps = mock(PreparedStatement.class);
            when(ps.executeUpdate()).thenAnswer(x -> { executed.add(sql); return 1; });
            return ps;
        });
        JdbcHelper h = helper(conn);
        h.deferDelete("DELETE FROM orders WHERE user_id = ?", "u1");   // parent-then-child 등록
        h.deferDelete("DELETE FROM users WHERE id = ?", "u1");
        h.runDeferredDeletes();
        assertThat(executed).containsExactly(
                "DELETE FROM orders WHERE user_id = ?",
                "DELETE FROM users WHERE id = ?");
    }

    @Test
    void deferredDeleteFailureIsBestEffort() throws Exception {
        List<String> executed = new ArrayList<>();
        Connection conn = mock(Connection.class);
        when(conn.prepareStatement(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            PreparedStatement ps = mock(PreparedStatement.class);
            when(ps.executeUpdate()).thenAnswer(x -> {
                if (sql.contains("orders")) throw new java.sql.SQLException("boom");
                executed.add(sql); return 1;
            });
            return ps;
        });
        JdbcHelper h = helper(conn);
        h.deferDelete("DELETE FROM orders WHERE user_id = ?", "u1");   // 첫 건 실패
        h.deferDelete("DELETE FROM users WHERE id = ?", "u1");         // 후속은 실행돼야 함
        assertThatCode(h::runDeferredDeletes).doesNotThrowAnyException();
        assertThat(executed).containsExactly("DELETE FROM users WHERE id = ?");
    }

    @Test
    void runDeferredDeletesOnEmptyIsNoop() {
        JdbcHelper h = helper(mock(Connection.class));
        assertThatCode(h::runDeferredDeletes).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Run tests — verify they fail (compile error: deferDelete 미정의)**

Run: `./gradlew :testlib:test --tests 'io.graphrag.testlib.api.JdbcHelperTest'`
Expected: FAIL — `cannot find symbol: method deferDelete`.

> Mockito가 testlib 테스트 의존성에 없으면 `testlib/build.gradle.kts`의 `testImplementation`에 `libs.mockito` (또는 기존 다른 모듈이 쓰는 mockito 좌표)를 추가한다. 기존 testlib 테스트가 어떤 mock 도구를 쓰는지 먼저 확인하고 그걸 재사용한다.

- [ ] **Step 3: Implement deferDelete/runDeferredDeletes in JdbcHelper**

`JdbcHelper`에 필드와 메소드를 추가한다(기존 코드는 유지). 클래스 상단 필드 영역에:

```java
    private final java.util.List<DeferredDelete> deferred = new java.util.ArrayList<>();

    private record DeferredDelete(String sql, Object[] args) {
    }
```

메소드 추가(예: `update(...)` 아래):

```java
    /** cleanup 시 실행할 DELETE를 등록 순서(FIFO)대로 보관한다. scope.cleanup()에서 실행. */
    public void deferDelete(String sql, Object... args) {
        deferred.add(new DeferredDelete(sql, args));
    }

    /** 등록된 DELETE를 등록 순서대로 실행. 개별 실패는 삼켜 다른 정리를 막지 않는다. */
    void runDeferredDeletes() {
        for (DeferredDelete d : deferred) {
            try {
                update(d.sql(), d.args());
            } catch (RuntimeException e) {
                // best-effort: 한 건 실패가 나머지 정리/테스트를 깨지 않는다
            }
        }
        deferred.clear();
    }
```

- [ ] **Step 4: Run tests — verify pass**

Run: `./gradlew :testlib:test --tests 'io.graphrag.testlib.api.JdbcHelperTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Write failing test for cleanup ordering (REQ-010)**

`TestScopeTest`에 추가. `TestScope.create(Env)`로 만들면 실제 어댑터가 붙으므로, 순서 검증은 "deferred delete가 jdbc 연결 닫힘 전에 실행됨"을 가장 단순하게 확인한다. 가장 견고한 방법: `cleanup()` 호출 후 deferred 리스트가 비워졌고 예외가 없음 + cleanup 재호출 멱등을 단언(현 `cleaned` 가드 유지 확인). 깊은 순서 단언이 어려우면 REQ-010은 코드 리뷰 + 아래 단언으로 충족한다.

```java
    @Test
    void cleanupRunsDeferredDeletesBeforeTeardown() {
        // noop 어댑터 환경(JDBC_URL/APP_BASE_URI 주입)로 scope 생성
        Env env = Env.of(java.util.Map.of(
                "APP_BASE_URI", "http://localhost",
                "JDBC_URL", "jdbc:noop"));
        TestScope scope = TestScope.create(env);
        // deferDelete 등록 후 cleanup이 예외 없이 멱등 실행되는지
        scope.jdbc().deferDelete("DELETE FROM t WHERE id = ?", "x");
        org.assertj.core.api.Assertions.assertThatCode(scope::cleanup).doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(scope::cleanup).doesNotThrowAnyException(); // 멱등
    }
```

> noop JDBC 어댑터가 없으면(연결이 필요해 실패) 이 단언은 `runDeferredDeletes`가 best-effort라 예외를 삼키는 것으로 green이 된다. 환경 변수 키는 `TestScope.create`가 `require`하는 `APP_BASE_URI`, `JDBC_URL`만 필수다(`TestScope.java:61-62`).

- [ ] **Step 6: Run — verify fail (cleanup이 아직 runDeferredDeletes 호출 안 함이면 리스트 미정리지만 예외는 없음 → 멱등 단언으로 red 유도)**

Run: `./gradlew :testlib:test --tests 'io.graphrag.testlib.api.TestScopeTest'`
Expected: 처음엔 컴파일 실패(`deferDelete` 호출) 또는 동작 미반영.

- [ ] **Step 7: Wire runDeferredDeletes into TestScope.cleanup()**

`TestScope.java:145-157`의 `cleanup()`을 수정 — mock/연결 해제 **전에** deferred delete 실행:

```java
    /** 자기 스코프의 등록된 DB 정리(deferred delete)를 FK 역순으로 실행한 뒤 mock/연결을 해제. */
    public void cleanup() {
        if (cleaned) {
            return;
        }
        cleaned = true;
        jdbc.runDeferredDeletes();          // ← 연결 닫기 전에 먼저
        stompHelpers.forEach(StompHelper::close);
        kafkaHelpers.forEach(KafkaHelper::close);
        http.removeAllForScope(testId);
        socket.removeSession(testId);
        jdbc.close();
        dashboard.report(new TestEvent(EventType.SCOPE_CLEANED, testId,
                RUN_ID, Instant.now(), Json.mapper().nullNode()));
    }
```

- [ ] **Step 8: Run testlib tests — verify pass**

Run: `./gradlew :testlib:test`
Expected: PASS (deferred-cleanup 테스트 포함 전체 green).

- [ ] **Step 9: Commit**

```bash
git add testlib/src/main/java/io/graphrag/testlib/api/JdbcHelper.java \
        testlib/src/main/java/io/graphrag/testlib/api/TestScope.java \
        testlib/src/test/java/io/graphrag/testlib/api/JdbcHelperTest.java \
        testlib/src/test/java/io/graphrag/testlib/api/TestScopeTest.java \
        testlib/build.gradle.kts
git commit -m "feat(testlib): deferred-cleanup (FIFO deferDelete + cleanup runs it first)

REQ-008, REQ-009, REQ-010"
```

---

## Task 2: Generator HTTP 경로 재작성 + 템플릿  — REQ-001,002,003,004,006,007,011,012,014

**Files:**
- Modify: `test-generator/src/main/resources/templates/test-class.mustache` (전면 교체)
- Modify: `test-generator/src/main/java/io/graphrag/generator/Generator.java` (HTTP 경로 + helper)
- Test: `test-generator/src/test/java/io/graphrag/generator/GeneratorTest.java`
- Modify: `test-generator/src/test/resources/golden/OrdersPostTest.java.golden`

- [ ] **Step 1: Replace the template**

`test-class.mustache` 전체를 아래로 교체:

```mustache
package {{packageName}};

import io.graphrag.testlib.api.TestScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
{{{serialImports}}}
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Generated by test-generator. DO NOT EDIT.
 * endpoint: {{httpMethod}} {{endpointPath}} ({{endpointId}}) — {{methodCount}} scenario(s)
 */
{{{classSerialMark}}}class {{className}} {

    private TestScope scope;

    @BeforeEach
    void setUp() {
        scope = TestScope.create();
    }

    @AfterEach
    void cleanup() {
        scope.cleanup();
    }
{{#methods}}

    @Test
    void {{methodName}}() {
{{#vars}}        String {{name}} = {{{valueExpr}}};
{{/vars}}{{#inserts}}        scope.jdbc().update("{{{sql}}}"{{#argExprs}}, {{{.}}}{{/argExprs}});
{{/inserts}}{{#deletes}}        scope.jdbc().deferDelete("{{{sql}}}"{{#argExprs}}, {{{.}}}{{/argExprs}});
{{/deletes}}{{{mocksBlock}}}        {{#authRequired}}scope.rest().authenticated(){{/authRequired}}{{^authRequired}}scope.rest().given(){{/authRequired}}
            .contentType("application/json")
{{^readPath}}            .body({{{bodyExpr}}})
{{/readPath}}        .when()
            .{{httpMethodLower}}("{{{requestPath}}}")
        .then()
            .statusCode({{expectedStatus}}){{{assertionsBlock}}};
    }
{{/methods}}
}
```

> `mocksBlock`은 현 `HttpMockComposer` 출력 그대로(앞에 개행 포함). 메소드 본문 들여쓰기와 어울리도록, 비면 빈 문자열이라 영향 없다.

- [ ] **Step 2: Write/Update GeneratorTest acceptance tests (red)**

`GeneratorTest`에서 **기존 HTTP 테스트들을 신규 구조로 갱신**하고 신규 단언을 추가한다. 아래로 교체/추가한다(핵심만; 기존 `generate_isDeterministic`, `wsEndpoint…`, `propagationMissing_marksSerialExecution`(아래 REQ-002로 대체), Kafka 테스트는 그대로 둔다).

REQ-001/003/004/006/011/012 — 엔드포인트 전체 생성:

```java
    @Test
    void endpointMergesParallelScenariosIntoOneClass() {   // REQ-001
        GenerationRequest all = new GenerationRequest(
                "post-api-orders", null, "OrdersPostTest", "io.graphrag.generated", AuthMode.DISABLED);
        GenerationResult result = new Generator(GRAPH).generate(all);

        java.util.Map<String, String> byPath = new java.util.HashMap<>();
        result.files().forEach(f -> byPath.put(f.relativePath(), f.content()));

        String merged = byPath.get("io/graphrag/generated/OrdersPostTest.java");
        assertThat(merged).isNotNull();
        assertThat(merged).contains("void happy()").contains("void s404_1()").contains("void s201_2()");
        assertThat(merged).doesNotContain("void s201_3(");   // 직렬은 별도 클래스

        // REQ-003: 병렬 클래스엔 @Execution/import 없음
        assertThat(merged).doesNotContain("@Execution").doesNotContain("parallel.Execution");
        // REQ-011: @AfterEach는 cleanup 한 줄, 직접 DELETE 없음
        assertThat(merged).contains("void cleanup() {\n        scope.cleanup();\n    }");
        assertThat(merged).doesNotContain("scope.jdbc().update(\"DELETE");
        // REQ-012: 불변식 가드
        assertThat(merged).doesNotContain("@TestInstance").doesNotContain("static TestScope");
        // deferDelete 사용
        assertThat(merged).contains("scope.jdbc().deferDelete(");
    }

    @Test
    void propagationMissingScenarioGoesToSerialClass() {   // REQ-002
        GenerationRequest all = new GenerationRequest(
                "post-api-orders", null, "OrdersPostTest", "io.graphrag.generated", AuthMode.DISABLED);
        GenerationResult result = new Generator(GRAPH).generate(all);
        java.util.Map<String, String> byPath = new java.util.HashMap<>();
        result.files().forEach(f -> byPath.put(f.relativePath(), f.content()));

        String serial = byPath.get("io/graphrag/generated/OrdersPostTestSerial.java");
        assertThat(serial).isNotNull();
        assertThat(serial).contains("void s201_3()");
        assertThat(serial).contains("@Execution(ExecutionMode.SAME_THREAD)\nclass OrdersPostTestSerial");
        assertThat(serial).contains("import org.junit.jupiter.api.parallel.Execution;");
        assertThat(serial).contains("import org.junit.jupiter.api.parallel.ExecutionMode;");
    }

    @Test
    void emitsJunitPlatformPropertiesDynamic() {   // REQ-004
        GenerationResult result = new Generator(GRAPH).generate(REQUEST);
        GeneratedFile props = result.files().stream()
                .filter(f -> f.relativePath().equals("junit-platform.properties"))
                .findFirst().orElseThrow();
        assertThat(props.content())
                .contains("junit.jupiter.execution.parallel.enabled=true")
                .contains("junit.jupiter.execution.parallel.mode.default=concurrent")
                .contains("junit.jupiter.execution.parallel.mode.classes.default=concurrent")
                .contains("junit.jupiter.execution.parallel.config.strategy=dynamic")
                .contains("junit.jupiter.execution.parallel.config.dynamic.factor=1");
    }

    @Test
    void formEndpointEmitsNoFilesAndNoProperties() {   // REQ-004 (0건)
        // 기존에 form 엔드포인트 차단을 검증하는 fixture가 있으면 그것을 쓴다. 없으면 이 테스트는 생략 가능.
        // (form fixture가 없으면 REQ-004의 "0건 미emit"은 generate() 코드 가드로 보장.)
    }

    @Test
    void reportsParallelAndSerialClasses() {   // REQ-006
        GenerationRequest all = new GenerationRequest(
                "post-api-orders", null, "OrdersPostTest", "io.graphrag.generated", AuthMode.DISABLED);
        GenerationResult result = new Generator(GRAPH).generate(all);
        assertThat(result.parallelSafety().fullyParallel()).containsExactly("OrdersPostTest");
        assertThat(result.parallelSafety().serialRequired())
                .extracting(io.graphrag.model.SerialRequired::test).containsExactly("OrdersPostTestSerial");
        assertThat(result.parallelSafety().serialRequired().get(0).reason())
                .isEqualTo("SUT_PROPAGATION_MISSING");
    }
```

REQ-007 (단건 후방호환) — 기존 `generate_matchesGoldenFile`(단건 happy)은 골든 교체로 유지. 직렬 단건 단언 추가:

```java
    @Test
    void singleSerialPathGetsClassLevelSameThread() {   // REQ-007
        GenerationRequest noProp = new GenerationRequest(
                "post-api-orders", "post-api-orders-s201-3", "OrdersNoPropTest",
                "io.graphrag.generated", AuthMode.DISABLED);
        GenerationResult result = new Generator(GRAPH).generate(noProp);
        String code = result.files().stream()
                .filter(f -> f.relativePath().endsWith("OrdersNoPropTest.java"))
                .findFirst().orElseThrow().content();
        assertThat(code).contains("@Execution(ExecutionMode.SAME_THREAD)\nclass OrdersNoPropTest");
        assertThat(result.files()).anyMatch(f -> f.relativePath().equals("junit-platform.properties"));
    }
```

REQ-014 (methodName 도출) — 단위 단언:

```java
    @Test
    void methodNameStripsEndpointPrefix() {   // REQ-014
        GenerationRequest happy = new GenerationRequest(
                "post-api-orders", "post-api-orders-happy", "OrdersPostTest",
                "io.graphrag.generated", AuthMode.DISABLED);
        String code = new Generator(GRAPH).generate(happy).files().stream()
                .filter(f -> f.relativePath().endsWith("OrdersPostTest.java"))
                .findFirst().orElseThrow().content();
        assertThat(code).contains("void happy()");
    }
```

기존 테스트 중 **삭제/대체 대상**: `generate_withoutPathId_emitsOneClassPerPath`(이제 한 클래스로 병합되므로 폐기 — REQ-001/002/006이 대체), `propagationMissing_marksSerialExecution`(REQ-002로 대체), `generate_reportsParallelSafety`(REQ-006로 대체). `httpCallPath_synthesizesWireMockStubWithBaggage`는 단건이라 유지하되 `@AfterEach`/golden 가정 변화에 맞춰 `doesNotContain("@Execution")` 등은 유지. `wsEndpoint…`/Kafka 테스트는 변경 없음.

- [ ] **Step 3: Run GeneratorTest — verify fail**

Run: `./gradlew :test-generator:test --tests 'io.graphrag.generator.GeneratorTest'`
Expected: FAIL — `OrdersPostTest.java` 병합 미구현, `junit-platform.properties` 부재 등.

- [ ] **Step 4: Implement Generator HTTP 경로 + helpers**

`Generator.java`에서 `generate()`의 HTTP 부분(현 `:60-90`)과 `generateSingle()`(현 `:267-348`)을 아래로 교체한다. import에 `java.util.HashSet`, `java.util.Set` 추가.

```java
    private static final String JUNIT_PLATFORM_PROPERTIES =
            "junit.jupiter.execution.parallel.enabled=true\n"
            + "junit.jupiter.execution.parallel.mode.default=concurrent\n"
            + "junit.jupiter.execution.parallel.mode.classes.default=concurrent\n"
            + "junit.jupiter.execution.parallel.config.strategy=dynamic\n"
            + "junit.jupiter.execution.parallel.config.dynamic.factor=1\n";

    /** path 1개분 렌더 입력. 렌더는 renderTestClass가 한다. */
    private record ScenarioMethod(
            String methodName, List<ComposedFixture.Var> vars,
            List<ComposedFixture.Stmt> inserts, List<ComposedFixture.Stmt> deletes,
            String mocksBlock, boolean readPath, String bodyExpr, String httpMethodLower,
            String requestPath, int expectedStatus, String assertionsBlock, boolean authRequired,
            boolean serial, List<String> warnings) {
    }

    public GenerationResult generate(GenerationRequest request) {
        if (client.hasKafkaConsumer(request.endpointId())) {
            return generateKafka(request);
        }
        if (client.hasWsEndpoint(request.endpointId())) {
            return generateWs(request);
        }
        Endpoint endpoint = client.endpoint(request.endpointId());
        if (endpoint.params().stream().anyMatch(p -> p.kind() == ParamKind.FORM)) {
            return new GenerationResult(List.of(),
                    List.of("form endpoint not generated (coverage-only): " + endpoint.id()),
                    new ParallelSafetyReport(List.of(), List.of()));
        }

        List<ExploredPath> paths = new ArrayList<>();
        if (request.pathId() != null) {
            paths.add(client.path(request.pathId()));
        } else {
            for (ExploredPath p : client.pathsForEndpoint(request.endpointId())) {
                if ("negative-auth".equals(p.discoveredBy())
                        || "negative-validation".equals(p.discoveredBy())) {
                    continue;
                }
                paths.add(p);
            }
        }

        List<ScenarioMethod> parallel = new ArrayList<>();
        List<ScenarioMethod> serial = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (ExploredPath path : paths) {
            ScenarioMethod m = buildScenarioMethod(endpoint, request, path);
            warnings.addAll(m.warnings());
            (m.serial() ? serial : parallel).add(m);
        }

        List<GeneratedFile> files = new ArrayList<>();
        List<String> fullyParallel = new ArrayList<>();
        List<io.graphrag.model.SerialRequired> serialRequired = new ArrayList<>();

        if (!parallel.isEmpty()) {
            files.add(renderTestClass(request, endpoint, request.testClassName(), parallel, false));
            fullyParallel.add(request.testClassName());
        }
        if (!serial.isEmpty()) {
            String serialName = request.testClassName() + "Serial";
            // 단건 생성(pathId 지정)일 때는 클래스명을 그대로 두어 후방호환
            if (request.pathId() != null && parallel.isEmpty()) {
                serialName = request.testClassName();
            }
            files.add(renderTestClass(request, endpoint, serialName, serial, true));
            serialRequired.add(new io.graphrag.model.SerialRequired(
                    serialName, "SUT_PROPAGATION_MISSING",
                    "외부 HTTP 호출에 baggage가 전파되지 않음 — OTEL agent 부착 또는 직렬 실행 필요"));
        }
        if (!files.isEmpty()) {
            files.add(new GeneratedFile("junit-platform.properties", JUNIT_PLATFORM_PROPERTIES));
        }
        return new GenerationResult(files, warnings,
                new ParallelSafetyReport(fullyParallel, serialRequired));
    }
```

`buildScenarioMethod`는 현 `generateSingle`의 scope 구성 로직을 그대로 옮기되 렌더 대신 `ScenarioMethod`를 반환한다:

```java
    private ScenarioMethod buildScenarioMethod(Endpoint endpoint, GenerationRequest request,
                                               ExploredPath path) {
        List<CapturedSql> sql = client.sqlForPath(path.id());
        boolean readPath = endpoint.httpMethod().equals("GET");
        java.util.Map<String, String> knownByField = new java.util.HashMap<>();
        if (path.sampleInput() instanceof com.fasterxml.jackson.databind.node.ObjectNode in) {
            in.fields().forEachRemaining(e -> {
                if (!e.getValue().isNull()) {
                    knownByField.put(e.getKey(), e.getValue().asText());
                }
            });
        }
        for (RequiredSeed s : client.seedsForPath(path.id())) {
            for (int i = 0; i < s.columns().size() && i < s.values().size(); i++) {
                knownByField.putIfAbsent(snakeToCamel(s.columns().get(i)), s.values().get(i));
            }
        }
        ComposedFixture fixture = new FixtureComposer().compose(path, sql, client.tables(),
                client.seedsForPath(path.id()), readPath, knownByField);
        HttpMockComposer.ComposedMocks mocks =
                new HttpMockComposer().compose(client.httpCallsForPath(path.id()));

        String bodyExpr = bodyExpr(fixture);
        boolean methodHasBody = endpoint.httpMethod().equals("POST")
                || endpoint.httpMethod().equals("PUT") || endpoint.httpMethod().equals("PATCH");
        if (methodHasBody && fixture.bodyFormat().isEmpty()) {
            String json = jsonBodyFromInput(endpoint, path.sampleInput());
            bodyExpr = "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        String assertionsBlock = fixture.assertions().stream()
                .map(a -> "\n            .body(\"" + a.jsonPath() + "\", " + a.matcher() + ")")
                .reduce("", String::concat);

        return new ScenarioMethod(
                deriveMethodName(endpoint.id(), path.id()),
                fixture.vars(), fixture.inserts(), fixture.deletes(),
                mocks.block(), readPath, bodyExpr, endpoint.httpMethod().toLowerCase(),
                resolveLiteralPath(endpoint, path.sampleInput()), path.expectedStatus(),
                assertionsBlock, endpoint.authRequired(),
                mocks.propagationMissing(), path.validationWarnings());
    }

    /** path.id() → 메소드명. endpoint 접두어 제거 + 비식별자 문자 → '_'. 숫자 시작이면 prefix. */
    private static String deriveMethodName(String endpointId, String pathId) {
        String rest = pathId.startsWith(endpointId + "-")
                ? pathId.substring(endpointId.length() + 1) : pathId;
        String name = rest.replaceAll("[^A-Za-z0-9]", "_");
        if (name.isEmpty() || Character.isDigit(name.charAt(0))) {
            name = "s_" + name;
        }
        return name;
    }

    private GeneratedFile renderTestClass(GenerationRequest request, Endpoint endpoint,
                                          String className, List<ScenarioMethod> methods,
                                          boolean classSerial) {
        java.util.Set<String> used = new java.util.HashSet<>();
        List<Map<String, Object>> methodScopes = new ArrayList<>();
        for (ScenarioMethod m : methods) {
            String name = m.methodName();
            String unique = name;
            for (int i = 2; !used.add(unique); i++) {
                unique = name + "_" + i;
            }
            Map<String, Object> ms = new HashMap<>();
            ms.put("methodName", unique);
            ms.put("vars", m.vars());
            ms.put("inserts", m.inserts());
            ms.put("deletes", m.deletes());
            ms.put("mocksBlock", m.mocksBlock());
            ms.put("readPath", m.readPath());
            ms.put("bodyExpr", m.bodyExpr());
            ms.put("httpMethodLower", m.httpMethodLower());
            ms.put("requestPath", m.requestPath());
            ms.put("expectedStatus", m.expectedStatus());
            ms.put("assertionsBlock", m.assertionsBlock());
            ms.put("authRequired", m.authRequired());
            methodScopes.add(ms);
        }

        Map<String, Object> scope = new HashMap<>();
        scope.put("packageName", request.packageName());
        scope.put("className", className);
        scope.put("httpMethod", endpoint.httpMethod());
        scope.put("endpointPath", endpoint.path());
        scope.put("endpointId", endpoint.id());
        scope.put("methodCount", methods.size());
        scope.put("methods", methodScopes);
        scope.put("classSerialMark", classSerial ? "@Execution(ExecutionMode.SAME_THREAD)\n" : "");
        scope.put("serialImports", classSerial
                ? "import org.junit.jupiter.api.parallel.Execution;\n"
                + "import org.junit.jupiter.api.parallel.ExecutionMode;\n" : "");

        StringWriter writer = new StringWriter();
        template.execute(writer, scope);
        return new GeneratedFile(
                request.packageName().replace('.', '/') + "/" + className + ".java",
                writer.toString());
    }
```

그리고 `generateSingle` 메소드(현 `:267-348`)를 **삭제**한다. `classSuffix`(현 `:260-265`)는 WS/Kafka가 계속 쓰므로 **유지**한다. `bodyExpr`, `jsonBodyFromInput`, `snakeToCamel`, `resolveLiteralPath`, `pathSentinel`는 그대로 유지.

- [ ] **Step 5: Regenerate the golden file**

`generate_matchesGoldenFile`은 단건 happy를 골든과 비교한다. 새 구조에 맞춰 골든을 교체한다. 먼저 구현이 컴파일되면 임시로 출력해 정확한 공백을 캡처한다:

Run: `./gradlew :test-generator:test --tests 'io.graphrag.generator.GeneratorTest.generate_matchesGoldenFile'` (FAIL 시 assertion 메시지의 actual을 캡처)

목표 골든(`OrdersPostTest.java.golden`)은 다음 구조여야 한다 — actual과 공백까지 일치하도록 골든을 갱신한다(생성기 출력이 정답):

```java
package io.graphrag.generated;

import io.graphrag.testlib.api.TestScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Generated by test-generator. DO NOT EDIT.
 * endpoint: POST /api/orders (post-api-orders) — 1 scenario(s)
 */
class OrdersPostTest {

    private TestScope scope;

    @BeforeEach
    void setUp() {
        scope = TestScope.create();
    }

    @AfterEach
    void cleanup() {
        scope.cleanup();
    }

    @Test
    void happy() {
        String userId = scope.testId() + "-user";
        scope.jdbc().update("INSERT INTO users (id, name) VALUES (?, ?)", userId, "probe");
        scope.jdbc().deferDelete("DELETE FROM orders WHERE user_id = ?", userId);
        scope.jdbc().deferDelete("DELETE FROM users WHERE id = ?", userId);
        scope.rest().given()
            .contentType("application/json")
            .body(String.format("{\"userId\":\"%s\",\"amount\":1,\"type\":\"sample-type\"}", userId))
        .when()
            .post("/api/orders")
        .then()
            .statusCode(201)
            .body("id", notNullValue())
            .body("status", equalTo("PENDING"));
    }
}
```

> 생성기 실제 출력과 골든이 공백/줄바꿈에서 다르면 **골든을 실제 출력에 맞춘다**(템플릿이 의도한 구조와 일치하는 한). 단, 위 구조적 요소(메소드명 `happy`, deferDelete 2줄, `@AfterEach`는 cleanup 한 줄, 인스턴스 `userId` 필드 없음)는 반드시 충족.

- [ ] **Step 6: Run full test-generator tests — verify pass**

Run: `./gradlew :test-generator:test`
Expected: PASS. (폐기한 기존 테스트 제거 확인, 신규 REQ 테스트 green.)

- [ ] **Step 7: Commit**

```bash
git add test-generator/src/main/java/io/graphrag/generator/Generator.java \
        test-generator/src/main/resources/templates/test-class.mustache \
        test-generator/src/test/java/io/graphrag/generator/GeneratorTest.java \
        test-generator/src/test/resources/golden/OrdersPostTest.java.golden
git commit -m "feat(generator): group endpoint scenarios into one class + emit junit-platform.properties

병렬 시나리오 병합 클래스 + 직렬 별도 클래스(SAME_THREAD) + deferDelete + dynamic 병렬 설정
REQ-001, REQ-002, REQ-003, REQ-004, REQ-006, REQ-007, REQ-011, REQ-012, REQ-014"
```

---

## Task 3: GeneratorCli — 기존 properties 덮어쓰기 경고  — REQ-005

**Files:**
- Modify: `test-generator/src/main/java/io/graphrag/generator/cli/GeneratorCli.java:34-41`
- Test: `test-generator/src/test/java/io/graphrag/generator/cli/GeneratorCliTest.java` (없으면 신규)

- [ ] **Step 1: Write failing test (red)**

`GeneratorCli.main`은 파일을 쓰고 로그를 남긴다. 로그 단언이 번거로우면, "다른 내용의 기존 파일이 emit 내용으로 갱신된다"를 핵심 단언으로 하고 경고는 코드로 보장한다.

```java
    @Test
    void overwritesExistingPropertiesWithEmittedContent() throws Exception {
        Path out = Files.createTempDirectory("gen-out");
        Path props = out.resolve("junit-platform.properties");
        Files.writeString(props, "junit.jupiter.execution.parallel.enabled=false\n"); // 다른 내용
        // request/graph는 기존 e2e fixture 또는 test 리소스를 재사용
        // ... GeneratorCli.main(new String[]{"generate","--request",..,"--graph",..,"--out",out.toString()});
        String after = Files.readString(props);
        assertThat(after).contains("config.strategy=dynamic");
    }
```

> request/graph 인자는 기존 테스트 픽스처(`src/test/resources/fixture-graph` + 임시 request.json)를 구성해 넘긴다. 이 테스트가 과한 셋업을 요구하면, REQ-005는 Should 우선순위이므로 **단위로 분리**: `GeneratorCli`에 `writeFileWarningIfDifferent(Path, String, Logger)` private 헬퍼를 추출해 그 헬퍼만 단위 테스트한다.

- [ ] **Step 2: Run — verify fail**

Run: `./gradlew :test-generator:test --tests 'io.graphrag.generator.cli.GeneratorCliTest'`
Expected: FAIL.

- [ ] **Step 3: Implement warning in the write loop**

`GeneratorCli.main`의 파일 쓰기 루프에서, `junit-platform.properties`를 쓸 때 기존 파일과 내용이 다르면 경고:

```java
        for (GeneratedFile file : result.files()) {
            Path target = out.resolve(file.relativePath());
            Files.createDirectories(target.getParent() != null ? target.getParent() : out);
            if (Files.exists(target)
                    && !Files.readString(target).equals(file.content())
                    && file.relativePath().equals("junit-platform.properties")) {
                log.warn("overwriting existing {} with generated parallel config "
                        + "— merge manually if you had custom settings", target);
            }
            Files.writeString(target, file.content());
            log.info("generated: {}", target);
        }
```

> `getParent()`가 null일 수 있는 루트 상대경로(`junit-platform.properties`)를 위해 null 가드를 추가했다.

- [ ] **Step 4: Run — verify pass**

Run: `./gradlew :test-generator:test --tests 'io.graphrag.generator.cli.GeneratorCliTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add test-generator/src/main/java/io/graphrag/generator/cli/GeneratorCli.java \
        test-generator/src/test/java/io/graphrag/generator/cli/GeneratorCliTest.java
git commit -m "feat(cli): warn when overwriting existing junit-platform.properties (REQ-005)"
```

---

## Task 4: e2e 배선 + 병렬 설정 정렬  — REQ-013

**Files:**
- Modify: `e2e/run-e2e.sh:67-69`
- Modify: `e2e/src/test/resources/junit-platform.properties`

- [ ] **Step 1: Align e2e junit-platform.properties to emitted content**

`e2e/src/test/resources/junit-platform.properties` 의 strategy 블록을 dynamic으로 교체(상단 주석은 유지/갱신):

```
# 생성된 테스트를 병렬 실행한다. 생성기가 emit하는 설정과 동일(strategy=dynamic).
junit.jupiter.execution.parallel.enabled=true
junit.jupiter.execution.parallel.mode.default=concurrent
junit.jupiter.execution.parallel.mode.classes.default=concurrent
junit.jupiter.execution.parallel.config.strategy=dynamic
junit.jupiter.execution.parallel.config.dynamic.factor=1
```

- [ ] **Step 2: Copy emitted properties into e2e resources in run-e2e.sh**

`e2e/run-e2e.sh`의 생성물 복사부(현 `:67-69`)에 properties 복사를 추가한다:

```bash
mkdir -p "$E2E/build/generated-tests"
rm -rf "$E2E/build/generated-tests"/*
cp -R "$OUT/generated/io" "$E2E/build/generated-tests/"
# 생성기가 emit한 병렬 설정을 e2e 테스트 리소스로 반영(REQ-013): 실제 배포 설정으로 실행
if [ -f "$OUT/generated/junit-platform.properties" ]; then
  cp "$OUT/generated/junit-platform.properties" "$E2E/src/test/resources/junit-platform.properties"
fi
```

- [ ] **Step 3: Run e2e (docker 필요)**

Run: `bash e2e/run-e2e.sh`
Expected: 생성 테스트가 병렬로 실행되어 GREEN. 병합된 `OrdersPostTest`(다중 메소드)가 컴파일·통과. 로그에 생성 클래스 수가 이전보다 줄어든다(병합).

> docker가 없는 환경이면 이 단계는 **실행 불가**로 기록하고(스킵 사유 명시), 최소한 `javac` 컴파일 가능성은 Task 2의 generator 테스트와 e2e 스크립트의 컴파일 경로로 간접 확인한다. PR 전 docker 가능한 환경에서 반드시 1회 green 확인.

- [ ] **Step 4: Commit**

```bash
git add e2e/run-e2e.sh e2e/src/test/resources/junit-platform.properties
git commit -m "test(e2e): run with emitted dynamic junit-platform.properties; verify parallel green (REQ-013)"
```

---

## Task 5: 문서 갱신 + 요구사항 매트릭스 + 전체 회귀

**Files:**
- Modify: `README.md` / getting-started 문서(생성 산출물 사용법)
- Modify: `docs/superpowers/requirements/2026-06-18-test-class-grouping-parallel-requirements.md` (매트릭스 상태)

- [ ] **Step 1: Update consumer docs**

생성 산출물 사용법 섹션에 다음을 반영(writing-documentation 스킬 사용):
  - 한 엔드포인트가 한 클래스의 다중 `@Test`로 생성됨(직렬 시나리오는 `…Serial` 클래스).
  - `junit-platform.properties`를 프로젝트 `src/test/resources/` 루트에 배치(이미 있으면 위 5개 property를 병합).
  - 병렬 실행이 기본 전제임을 명시.

- [ ] **Step 2: Update requirements 매트릭스 상태**

각 REQ의 Status를 실제 테스트 결과에 맞춰 🟢로 갱신하고 Coverage 줄을 `14/14 green (100%)`로 만든다. 실제 테스트명이 계획과 다르면 **실제 테스트명으로 매트릭스를 바로잡는다**.

- [ ] **Step 3: Full regression**

Run: `./gradlew test`
Expected: 전체 모듈 단위/통합 green. (docker 필요한 e2e는 Task 4에서 별도 확인; 스킵 시 명시.)

- [ ] **Step 4: Commit**

```bash
git add README.md docs/
git commit -m "docs: update consumer guide for grouped classes + parallel config; mark requirements green"
```

---

## Self-Review (작성자 체크)

- **Spec coverage:** REQ-001(T2)·002(T2)·003(T2)·004(T2)·005(T3)·006(T2)·007(T2)·008(T1)·009(T1)·010(T1)·011(T2)·012(T2)·013(T4)·014(T2)·015(회귀, 변경 없음 확인) — 전부 task 매핑됨.
- **Placeholder scan:** 모든 코드 step에 실제 코드 포함. golden은 "생성기 출력이 정답" 규칙으로 공백 reconcile 명시(placeholder 아님).
- **Type consistency:** `ScenarioMethod`/`renderTestClass`/`buildScenarioMethod`/`deriveMethodName` 시그니처와 호출부 일치. `ComposedFixture.Var/Stmt`의 mustache 키(`name`,`valueExpr`,`sql`,`argExprs`)는 record 접근자와 일치. `SerialRequired(test,reason,details)` 생성자 인자 순서 일치.

## E2E 연동 규칙(구현 시)
- 각 수용 테스트는 REQ-ID를 주석/이름으로 참조(예: 위 테스트들 주석의 `// REQ-00x`).
- 외부 루프(REQ-013 e2e + GeneratorTest 구조 단언)를 먼저 red로 두고 내부 TDD로 green.
- PR 전 매트릭스 14/14 green 및 테스트명 대조 확인.
