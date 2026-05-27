# 19 — jdbc-intercept-agent × OpenTelemetry 공존 + graph-rag 통합 설계

> **갱신 이력**
> - v1 (Phase 7 시점): agent 가 SPI/dispatch 만 완성, advice/AgentMain 등 미구현 가정.
> - **v2 (현재)**: agent 가 `1.0.0-SNAPSHOT — usable end-to-end` 상태로 진화. PreparedStatement
>   / Connection / MyBatis 의 모든 advice 클래스 + `AgentMain` + `Reentry` 가드 + JPA/Hikari/MyBatis
>   IT 모두 완비. 본 문서는 이 변화를 반영해 §1, §2.3, §6 을 patch 함.
> - `CapturedQuery.snapshotRows` (Option A parity) 는 여전히 미추가 — agent 측 후속 작업으로 유지.

graph-rag-builder 의 SQL 캡처는 현재 SUT 코드(또는 적어도 test scope) 에 의존한다
([`docs/11`](11-datasource-proxy-wrap.md) 의 `BeanPostProcessor + ProxyDataSource` 패턴).
운영 binary / 외부 jar / 5M 라인 레거시 ([`docs/01`](01-overview.md) Phase A) 같이
SUT 코드 수정이 불가능한 환경에서는 javaagent 가 답이다.

별도 repo `~/github_jdbc-intercept-agent` 가 이 빈자리를 채우기 위해 개발 중. 본 문서는:

1. 그 agent 의 **현재 상태** (skeleton/구현/SPI)
2. graph-rag 의 HTTP 캡처 경로가 사용하는 **OpenTelemetry javaagent 와의 공존** 가능성
3. 두 agent + graph-rag 가 **OTEL baggage 를 매개로** 한 thread propagation 해소
4. graph-rag 쪽 어댑터 + agent 쪽 추가 작업 + 검증 체크리스트

를 정의한다.

## 1. 사실 확인 — jdbc-intercept-agent 현재 상태 (v2 갱신)

소스 + 테스트 + README 스캔 결과 (`1.0.0-SNAPSHOT — usable end-to-end`):

### 이미 구현된 것 — agent-api (contract)

| 파일 | 역할 |
|---|---|
| `agent-api/JdbcCaptureListener` | `@FunctionalInterface`, `void afterQuery(CapturedQuery)`. ServiceLoader SPI |
| `agent-api/JdbcCaptureSession` | ThreadLocal `begin(correlationId)/currentId()/end()` |
| `agent-api/CapturedQuery` | record(sql, bindings, mybatisMeta, startNanos, endNanos, error, correlationId) |
| `agent-api/BindingValue` | record(index, value, jdbcType) |
| `agent-api/MyBatisStatementMeta` | record(mapperId, namespace, statementType) |

### 이미 구현된 것 — agent-core (bytecode + dispatch)

| 파일 | 역할 | v1 비고 |
|---|---|---|
| **`agent-core/AgentMain`** | premain + agentmain + 멱등 `install()`. 실패 시 fail-safe (SUT 계속 동작) | v1에선 "미구현" 표기 — **v2 완성** |
| **`agent-core/AgentInstaller`** | ByteBuddy `AgentBuilder` 3 matcher 조립 (PreparedStatement / Connection / MyBatis PreparedStatementHandler), `RETRANSFORMATION` + `REDEFINE` 전략 | v1 "미구현" — **v2 완성** |
| `agent-core/AgentArgs` | premain 인자 파싱 (debug 등) | |
| **`agent-core/jdbc/ConnectionPrepareAdvice`** | `Connection.prepareStatement(String, ...)` 호출 시 SQL 텍스트 stash → `StatementCorrelator.registerSql(ps, sql)` | v1 "미구현" — **v2 완성** |
| **`agent-core/jdbc/SetParameterAdvice`** | `setX(int, ...)` 모든 변형 (set/fetch/escape 등 metadata setter는 제외) → `StatementCorrelator.recordBinding(...)` | v1 "미구현" — **v2 완성** |
| **`agent-core/jdbc/ExecuteAdvice`** | `execute / executeQuery / executeUpdate / executeLargeUpdate` (no-arg variants) → `snapshotAndKeep + EventDispatcher.publish` | v1 "미구현" — **v2 완성** |
| **`agent-core/jdbc/AddBatchAdvice`** | `addBatch()` → `commitCurrentBatchSlot(...)` | v1 "미구현" — **v2 완성** |
| **`agent-core/jdbc/ExecuteBatchAdvice`** | `executeBatch / executeLargeBatch` → `snapshotBatch(...)` → batch slot 별 1 CapturedQuery | v1 "미구현" — **v2 완성** |
| **`agent-core/jdbc/StatementCorrelator`** | `Map<PreparedStatement, StatementState>` (synchronized `WeakHashMap`). `registerSql / recordBinding / snapshotAndKeep / snapshotBatch / commitCurrentBatchSlot` | v1 "미구현" — **v2 완성** |
| `agent-core/jdbc/StatementState` | per-PS mutable bindings + batch slots | v1 부분만 있었음 |
| **`agent-core/jdbc/Reentry`** *(v2 신규 — 본 문서 작성 시점엔 없던 클래스)* | per-thread depth guard. 같은 thread 에서 outer/inner PreparedStatement (Hikari proxy, vendor wrapper, **또는 OTEL javaagent 의 wrapped PS**) 호출 시 outermost 만 publish | **v2 신규** — OTEL 공존에 결정적 |
| **`agent-core/mybatis/MyBatisPrepareAdvice`** | `PreparedStatementHandler.instantiateStatement` 호출 시 mapperId/namespace/statementType 캡처 | v1 "미구현" — **v2 완성** |
| **`agent-core/mybatis/MyBatisMetaStash`** | PS 인스턴스에 MyBatisMeta 결합 (소비는 1회) | v1 "미구현" — **v2 완성** |
| **`agent-core/mybatis/MyBatisReflectionAccess`** | MyBatis 클래스 없는 환경에서 NoClassDefFoundError 회피용 reflection helper | **v2 신규** |
| `agent-core/dispatch/ListenerRegistry` | ServiceLoader + 정적 add/remove + immutable snapshot | |
| `agent-core/dispatch/EventDispatcher` | `publish(CapturedQuery)` → 모든 listener fan-out, 예외 격리 | |
| `agent-core/runtime/AgentLogger` | System.err 기반 (JUL 의존 회피), debug 토글 | |

### 통합 테스트 (`agent-core/src/test/java/.../it/`, v2 신규)

- `PreparedStatementAdviceIT` — 첫 H2 end-to-end
- `JpaCaptureIT` — JPA/Hibernate (EntityManager 경로)
- `MyBatisCaptureIT` — mapper-id 캡처
- `BatchCaptureIT` — addBatch + executeBatch
- `ExecuteExceptionIT` — execute 예외 path
- `HikariDoublePublishIT` — **Hikari proxy + 드라이버 PS 의 double-publish 방어 검증** (Reentry 의 직접적 동기)
- `AgentInstalledExtension` / `TestListener` — IT lifecycle helper

### build.gradle.kts manifest (변함 없음)
```
Premain-Class: io.jdbcintercept.agent.AgentMain
Agent-Class: io.jdbcintercept.agent.AgentMain
Can-Redefine-Classes: true
Can-Retransform-Classes: true
```
ByteBuddy shading: `net.bytebuddy` → `io.jdbcintercept.shaded.bytebuddy`.

### 아직 미구현 (v2 기준 진짜 남은 것)

| 항목 | 영향 | 비고 |
|---|---|---|
| `CapturedQuery.snapshotRows` 필드 | Option A parity 부재 — SELECT 시 ResultSet row 캡처 안 됨 | 본 문서 §4 의 Path A 가 그대로 유효 |
| `SnapshotResultSetWrapper` 류의 ResultSet spy | (같음) | (같음) |
| 명시적 OTEL bridge | 본 문서 §3 의 baggage 매핑 — agent 측이 아니라 graph-rag 측 어댑터에서 처리해도 OK | 굳이 agent 측에 둘 필요는 없음 |

→ **결론(v2)**: agent 의 *contract + bytecode hook + dispatch + 통합 테스트* 가 모두 완성. graph-rag 쪽 어댑터를 작성하면 **즉시 동작** (skeleton 대기 없음). 남은 것은 본 문서 §4 의 `snapshotRows` 확장뿐.

## 2. OpenTelemetry javaagent 와의 공존

### 2.1 JVM 레벨 호환

복수 `-javaagent:` 지원은 JVM 표준. 명시 순서대로 `premain()` 호출, 각 agent 가 자체 `Instrumentation` 인스턴스 받음:
```bash
java \
  -javaagent:opentelemetry-javaagent.jar \
  -javaagent:jdbc-intercept-agent-core.jar \
  -jar petclinic.jar
```

### 2.2 ByteBuddy 충돌 — 둘 다 shaded 했으므로 OK

| Agent | ByteBuddy relocate prefix |
|---|---|
| OTEL javaagent | `io.opentelemetry.javaagent.shaded.net.bytebuddy` |
| jdbc-intercept-agent | `io.jdbcintercept.shaded.bytebuddy` (build.gradle.kts shadowJar 에서 확인) |

다른 패키지로 relocate → ByteBuddy 버전 / 인스턴스 충돌 없음.

### 2.3 진짜 갈등 지점 — 같은 JDBC 메서드를 두 agent 가 instrument

OTEL javaagent 의 기본 JDBC instrumentation 대상:
- `java.sql.Connection.prepareStatement` → DB span 시작
- `java.sql.PreparedStatement.executeUpdate / executeQuery / executeBatch` → DB span 종료 + SQL/timing 속성 기록

jdbc-intercept-agent 도 (**v2 — 이미 구현됨**) 정확히 같은 메서드들에 advice 부착. v2 의 핵심 안전장치:

- **`Reentry` per-thread depth guard** ([`agent-core/jdbc/Reentry.java`](file:///Users/changjoonbaek/github_jdbc-intercept-agent/jdbc-intercept-agent/agent-core/src/main/java/io/jdbcintercept/agent/jdbc/Reentry.java))
- `ExecuteAdvice.onEnter` 는 `Reentry.enter()` 가 `true` (outermost) 일 때만 `nanoTime()` 기록 → 내부 호출은 `-1L` 반환 → publish 안 함
- 이미 `HikariDoublePublishIT` 가 "Hikari proxy + driver PS" 케이스를 검증 — **같은 원리로 OTEL 의 wrapper + 내부 PS 도 outermost 만 publish**

따라서 v2 에선 갈등이 크게 완화됨:

| 증상 | v1 가정 | v2 실 동작 |
|---|---|---|
| 같은 SQL 이 OTEL trace + jdbc-intercept 양쪽에 보임 | 정상 — 각자 독립 관측, overhead 2× | **여전함** (의도된 동작 — 둘 다 자기 책임으로 기록) |
| advice wrap 순서 영향 | 마지막 attach agent 가 outermost → SQL 에 hint comment prefix 가능 | `Reentry` 가 jdbc-intercept 측 중복 publish 차단. SQL 본문은 ConnectionPrepareAdvice 가 prepareStatement 호출 시점에 stash → 이후 wrapper 가 텍스트 변경해도 무관 |
| Option A snapshot 의 SELECT re-execution → 또 wrap | trace 폭주 위험 | snapshot SELECT 가 같은 thread 에서 발행되면 `Reentry.enter()` 가 false → publish 안 됨 (외부 SUT execute 의 inner 로 간주). **재귀 안전.** 단, OTEL 측은 별도 — OTEL JDBC 가 켜져 있으면 snapshot SELECT 도 trace 됨 → §2.4 비활성 권장 그대로 유효 |
| jdbc-intercept 가 OTEL 이 instrument 한 PS 클래스를 추가 transform | ByteBuddy `RETRANSFORMATION` + advice 누적이므로 양립. AgentInstaller 가 `hasSuperType(named("java.sql.PreparedStatement")).and(not(isInterface())).and(not(isAbstract()))` 로 매칭 → OTEL 의 wrap subclass 까지 잡힘 | 정상. wrap subclass 도 PreparedStatement 의 sub type → set/execute advice 모두 적용 |

요약: v2 의 `Reentry` 덕에 같은 JVM 내 중복 instrumentation 의 핵심 문제 (double publish) 가 이미 해결. 남은 비효율은 OTEL trace 폭주 정도 → §2.4 비활성으로 해소.

### 2.4 권장 — 역할 분리 + OTEL JDBC 비활성

OTEL 의 강점은 HTTP/Servlet/Kafka 등 transport-level instrumentation + **baggage propagation**.
jdbc-intercept-agent 의 강점은 JDBC + binding + (확장 시) ResultSet snapshot.

```bash
java \
  -javaagent:opentelemetry-javaagent.jar \
  -Dotel.instrumentation.jdbc.enabled=false \
  -Dotel.instrumentation.jdbc-datasource.enabled=false \
  -Dotel.baggage.propagation=w3cbaggage \
  -javaagent:jdbc-intercept-agent-core.jar \
  -jar sut.jar
```

OTEL JDBC 만 끄면 HTTP/Servlet/Kafka 등 다른 모든 OTEL instrumentation 은 그대로.

## 3. 핵심 통합 아이디어 — OTEL baggage 를 correlationId 매핑 소스로

### 3.1 해결하려는 문제

graph-rag `CaptureContext` 와 agent `JdbcCaptureSession` 둘 다 **ThreadLocal**.

- 분석 harness 가 main thread 에서 `begin(pathId)` → SUT 가 Servlet handler thread (Tomcat) 에서 실제 처리 → ThreadLocal 가 시야 밖 → capture 실패.
- Phase 7 의 petclinic scout 가 이 한계를 만나 OwnerRepository 직접 호출로 우회 ([`docs/18`](18-option-a-petclinic-real-capture.md) §"한계 §1").

### 3.2 OTEL baggage 의 천연 propagation

OTEL javaagent 는 HTTP 요청 헤더 `baggage` 를 자동 파싱 → `Context` 에 `Baggage` 로 저장 → Tomcat handler thread, async executor, ContextStorage 통해 전파. JPA 의 EntityManager 호출, JDBC 의 PreparedStatement.execute 까지 baggage 가 따라옴.

### 3.3 design pattern — 어댑터가 baggage 에서 pathId 읽음

```java
// graph-rag-builder/.../capture/JdbcAgentBaggageBridge.java (Phase 8 신규)
public final class JdbcAgentBaggageBridge implements JdbcCaptureListener {
    @Override
    public void afterQuery(CapturedQuery q) {
        // 우선: agent 의 ThreadLocal correlationId (직접 begin/end 한 경우)
        String pathId = q.correlationId();

        // fallback: OTEL baggage (Servlet thread 처럼 ThreadLocal 못 보는 경우)
        if (pathId == null || pathId.isEmpty()) {
            pathId = io.opentelemetry.api.baggage.Baggage.current()
                    .getEntryValue("graphrag.path-id");
        }
        if (pathId == null) return;

        // CaptureContextRegistry: pathId → ctx (ThreadLocal 대신)
        CaptureContext ctx = CaptureContextRegistry.forPathId(pathId);
        if (ctx == null) return;

        List<Object> vals = q.bindings().stream().map(BindingValue::value).toList();
        CapturedSqlSource src = q.mybatisMeta().isPresent()
                ? CapturedSqlSource.MYBATIS_XML_MAPPER
                : CapturedSqlSource.JDBC_RAW;

        CapturedSql captured = CapturedSqlBuilder.build(
                pathId, q.sql(), vals, src, /* rows */ List.of());
        ctx.addCapturedSql(captured);
    }
}
```

scout 측은 path 시작 시 두 가지를 함께 셋업:

```java
@BeforeEach
void beginPath() {
    String pathId = "path-" + UUID.randomUUID();
    JdbcCaptureSession.begin(pathId);                                       // ① agent ThreadLocal
    Baggage baggage = Baggage.builder().put("graphrag.path-id", pathId).build();
    scope = baggage.makeCurrent();                                          // ② OTEL Context — 자동 전파
    CaptureContextRegistry.register(pathId, new CaptureContext(pathId));    // ③ graph-rag side
}
@AfterEach
void endPath() {
    scope.close();
    JdbcCaptureSession.end();
    CaptureContext ctx = CaptureContextRegistry.unregister(pathId);
    archive.addAll(ctx.capturedSql());
}
```

→ Servlet handler thread 에서 발생한 JPA 쿼리도 OTEL baggage 로 pathId 가 따라가서 capture.

### 3.4 새로 필요한 작은 인프라

| 위치 | 파일 | 역할 |
|---|---|---|
| graph-rag-builder | `CaptureContextRegistry` (신규) | 기존 ThreadLocal `CaptureContext.current()` 의 자매 — `ConcurrentMap<String, CaptureContext>`. 병렬 path 간 격리 |
| graph-rag-builder | `JdbcAgentBaggageBridge` (신규) | 위 §3.3 어댑터 |
| graph-rag-builder/`META-INF/services/io.jdbcintercept.api.JdbcCaptureListener` (신규) | `io.graphrag.builder.capture.JdbcAgentBaggageBridge` |
| graph-rag-builder/build.gradle.kts | `implementation("io.jdbcintercept:agent-api:1.0.0-SNAPSHOT")` + OpenTelemetry API |
| graph-rag-builder/build.gradle.kts | `compileOnly("io.opentelemetry:opentelemetry-api:1.x")` (필수 아니면 noop fallback) |

OTEL API 가 classpath 에 없을 때를 위한 fallback (Baggage lookup 을 reflection 으로):
```java
private String tryReadBaggage(String key) {
    try {
        Class<?> baggage = Class.forName("io.opentelemetry.api.baggage.Baggage");
        Object current = baggage.getMethod("current").invoke(null);
        return (String) current.getClass().getMethod("getEntryValue", String.class).invoke(current, key);
    } catch (Throwable ignored) { return null; }
}
```

## 4. ResultSet snapshot (Option A) 의 agent 측 확장

graph-rag Option A ([`docs/12`](12-option-a-row-snapshot-design.md)) 는 SELECT 의 row snapshot 을 fixture INSERT 로 합성. 현재 agent `CapturedQuery` 에는 **ResultSet 정보가 없음** — record 필드:
```java
record CapturedQuery(sql, bindings, mybatisMeta, startNanos, endNanos, error, correlationId) {}
```

Option A parity 를 위해 두 가지 길:

### Path A — agent 측 확장 (권장)

`CapturedQuery` 에 `Optional<List<Map<String, Object>>> snapshotRows` 추가. 구현은 advice 가 `PreparedStatement.executeQuery` 후 반환된 `ResultSet` 을 wrap 한 spy 로 교체 → consumer 의 `rs.next()` 호출마다 row map 수집. 한계는 consumer 가 ResultSet 을 닫지 않으면 snapshot 무한 누적 (cap 필요).

장점: 단일 hook, 추가 connection 없음.
단점: agent-core 코드 변경. consumer 의 cursor 이동 패턴에 영향 받음.

### Path B — graph-rag 어댑터에서 재실행 (현재 `CapturedSqlListener` 와 동일)

어댑터가 `afterQuery` 콜백 시 별도 Connection 으로 같은 SQL 재실행 → ResultSet 수집.
- agent 의 `CapturedQuery` 에는 Connection 참조 없음 — `DataSource` 를 어댑터가 외부에서 알아야 함
- 또는 `correlationId` 외에 추가 컨텍스트로 Connection 을 같이 넘겨야 함 → agent contract 변경 필요
- transaction 격리 문제 (SUT 트랜잭션과 별도 → 미커밋 변경 못 봄. seed 데이터 시나리오는 안전)

→ **Path A 추천**. agent contract 가 깨끗하고 단일 hook 으로 끝.

### 변경 제안 (agent 측 — 후속 PR)

`agent-api/CapturedQuery` 에 필드 추가:
```java
public record CapturedQuery(
    String sql,
    List<BindingValue> bindings,
    Optional<MyBatisStatementMeta> mybatisMeta,
    long startNanos,
    long endNanos,
    Optional<Throwable> error,
    String correlationId,
    List<Map<String, Object>> snapshotRows   // ← 신규, default List.of()
) { ... }
```

`agent-core/jdbc/PreparedStatementAdvice` (구현 시) `executeQuery` 분기:
```java
@Advice.OnMethodExit
static void onExit(@Advice.This PreparedStatement ps,
                   @Advice.Return(readOnly = false) ResultSet rs) {
    if (JdbcCaptureSession.currentId() == null) return;
    rs = new SnapshotResultSetWrapper(rs, /* maxRows */ 100, /* sink */ ...);
}
```

`SnapshotResultSetWrapper` 는 `rs.next()` 마다 `ResultSetMetaData` 기반으로 row map 누적 → consumer 가 close 할 때 또는 다음 execute 시 flush.

## 5. 통합 전체 아키텍처 (최종 모습)

```
[SUT JVM]
  -javaagent:opentelemetry-javaagent.jar (otel jdbc disabled)
    │
    ├─ HTTP/Servlet instrumentation
    ├─ Baggage propagation (Context, ContextStorage)
    │
  -javaagent:jdbc-intercept-agent-core.jar
    │
    ├─ PreparedStatement.executeUpdate/Query/Batch hook
    ├─ MyBatis Configuration.prepare hook
    ├─ ServiceLoader → CapturedSqlAgentListener (graph-rag-builder)
    │
  [SUT 앱 코드 — 무수정]
    REST → Service → Repository → JPA → JDBC
                                          │
                                          ▼
                              (advice fires → EventDispatcher.publish)
                                          │
                                          ▼
                              CapturedSqlAgentListener.afterQuery(CapturedQuery)
                                          │
                              correlationId = JdbcCaptureSession.currentId()
                                          ?? Baggage.current().getEntryValue("graphrag.path-id")
                                          │
                                          ▼
                              CaptureContextRegistry.forPathId(pathId).addCapturedSql(...)

[graph-rag-builder analysis harness — 같은 JVM 또는 별도 JVM]
  Baggage.builder().put("graphrag.path-id", pathId).build().makeCurrent()
  → mvc.perform(...) / restTemplate.exchange(...)
  → 이후 archive.save(captureCtx.capturedSql())
  → test-generator --archive ... → 합성 테스트 → 깨끗한 DB 에서 PASS
```

## 6. 누가 무엇을 해야 하나

### graph-rag 측 (이 repo, Phase 8 PR)

| 작업 | 위치 |
|---|---|
| `CaptureContextRegistry` 신규 (병렬 path 격리) | `graph-rag-builder/src/main/.../capture/` |
| `JdbcAgentBaggageBridge` 신규 (어댑터) | 같은 패키지 |
| `META-INF/services/io.jdbcintercept.api.JdbcCaptureListener` 1줄 | 같은 모듈 resources |
| `build.gradle.kts` 의존 추가 — `agent-api`, `opentelemetry-api` (compileOnly) | graph-rag-builder |
| E2E 테스트 — agent 모드 baggage 전파 회귀 | `samples/demo-sut` 또는 별도 `samples/demo-sut-agent` |
| 가이드 — `docs/19` (본 문서) + scout 사용 예제 | 본 repo |

### agent 측 (`~/github_jdbc-intercept-agent` repo, 별도 PR) — **v2 갱신**

| 작업 | 위치 | v2 상태 |
|---|---|---|
| ~~PreparedStatementAdvice/AgentMain/AgentInstaller/StatementCorrelator/MyBatis hook 구현~~ | `agent-core/src/main/.../jdbc/` 및 `mybatis/` | ✅ **완료** (1.0.0-SNAPSHOT) |
| ~~Hikari/JPA/MyBatis/batch IT 추가~~ | `agent-core/src/test/.../it/` | ✅ **완료** (7개 IT) |
| ~~`Reentry` 가드 (중복 publish 방지)~~ | `agent-core/jdbc/Reentry.java` | ✅ **완료** — OTEL 공존에도 동일 원리 적용 |
| `CapturedQuery.snapshotRows` 필드 추가 + `SnapshotResultSetWrapper` 구현 | `agent-api` + `agent-core/jdbc/` | 🟡 미구현 — 본 문서 §4 그대로 유효 |
| OTEL coexistence 문서 추가 (`docs/07-otel-coexistence.md`) — 본 문서의 §2/§3 요약 + 권장 JVM args | `~/github_jdbc-intercept-agent/jdbc-intercept-agent/docs/` | 🟡 미구현 |
| OTEL javaagent + jdbc-intercept-agent + baggage propagation 통합 IT | agent-core/src/test/.../it/ | 🟡 미구현 |

### 두 repo 협력

- `agent-api` 가 Maven Local 에 publish → graph-rag-builder 가 `mavenLocal()` 에서 resolve
- 통합 E2E 는 graph-rag 측 `samples/demo-sut-agent` 가 OTEL agent + jdbc-intercept-agent 둘 다 attach 한 상태에서 path 1개 capture → 합성 → 실 SUT PASS 까지

## 7. 검증 체크리스트 (실 통합 시)

| 항목 | 방법 | 기대 결과 |
|---|---|---|
| OTEL + jdbc-intercept 둘 다 attach | 시작 로그 / `jcmd VM.flags` | 두 banner 모두 출력 |
| OTEL JDBC 중복 비활성 | `-Dotel.javaagent.debug=true` instrumented class 목록 | `PreparedStatement` 없음 (OTEL 측) |
| jdbc-intercept advice fire | agent debug 로그 | `prepareStatement` / `executeQuery` instrumented |
| 한 HTTP 요청 → 한 번만 capture | `captured_sql.json` 의 row 수 | SQL 1건당 1 entry |
| Baggage propagation | OTEL root span attributes | `graphrag.path-id` 키 존재 |
| Servlet handler thread 에서 capture | thread name 로깅 후 `archive.capturedSql()` non-empty | OK (Phase 7 한계 해소) |
| Option A SELECT snapshot | `CapturedQuery.snapshotRows` non-empty | OK (agent 확장 후) |
| 성능 overhead | 같은 endpoint 100회 호출 p95 latency | no-agent vs (OTEL+jdbc) ≤ 30% slower (목표) |
| 회귀 — 기존 datasource-proxy 경로 | `Phase0E2eTest` 그대로 GREEN | 영향 없음 |

## 8. 결정 사항 vs 후속

### 결정
- **공존 가능**: JVM 다중 agent 지원 + 둘 다 shaded ByteBuddy → 안전.
- **권장 운영**: OTEL JDBC instrumentation 비활성 + jdbc-intercept-agent 가 SQL 전담.
- **Thread propagation 해소 경로**: OTEL baggage 의 `graphrag.path-id` 키.
- **graph-rag 의 어댑터**는 agent SPI (`JdbcCaptureListener`) 그대로 사용 — agent 의 *contract* 가 이미 안정적이므로 지금 어댑터 작성 가능.
- **Option A parity** 는 agent 측 `CapturedQuery.snapshotRows` 확장 필요 (agent 측 PR).

### 후속 — Phase 8 PR 분할 후보 (v2 갱신)

| # | PR | repo | v2 상태 |
|---|---|---|---|
| 1 | ~~AgentMain / Installer / PreparedStatementAdvice / StatementCorrelator 구현~~ | agent | ✅ **이미 완료** (1.0.0-SNAPSHOT) |
| 2 | `CapturedQuery.snapshotRows` 추가 + `SnapshotResultSetWrapper` | agent | 🟡 **Option A parity 위해 남은 핵심 작업** |
| 3 | `CaptureContextRegistry` + `JdbcAgentBaggageBridge` + ServiceLoader 파일 + build.gradle 의존 | graph-rag | 🟡 **즉시 시작 가능** — agent contract 안정 |
| 4 | `samples/demo-sut-agent` E2E (OTEL + jdbc-intercept 두 agent 동시 attach) | graph-rag | 🟡 PR #3 완료 후 |
| 5 | OTEL coexistence 가이드 문서 (`agent/docs/07-otel-coexistence.md`) | agent | 🟡 본 문서 §2-§3 요약본 |
| 6 | OTEL javaagent + jdbc-intercept-agent 통합 IT | agent | 🟡 PR #5 와 함께 |

PR #3 (graph-rag 쪽 어댑터) 은 agent 의 contract 만 의존하므로 **agent 측 PR #2 와 병렬 진행 가능**. snapshotRows 가 빈 리스트여도 어댑터는 동작 — Option A 가 비활성일 뿐.

## 참조
- [`docs/11-datasource-proxy-wrap.md`](11-datasource-proxy-wrap.md) — 현재 capture 패턴 (대안)
- [`docs/12-option-a-row-snapshot-design.md`](12-option-a-row-snapshot-design.md) — Option A
- [`docs/18-option-a-petclinic-real-capture.md`](18-option-a-petclinic-real-capture.md) — Phase 7 ThreadLocal 한계 사례
- `~/github_jdbc-intercept-agent/jdbc-intercept-agent/docs/00-overview.md` — agent 동기
- `~/github_jdbc-intercept-agent/jdbc-intercept-agent/docs/01-architecture.md` — agent 모듈/패키징
- `~/github_jdbc-intercept-agent/jdbc-intercept-agent/docs/06-graph-rag-integration.md` — agent 측의 graph-rag 통합 (기본 case — OTEL 없이)
