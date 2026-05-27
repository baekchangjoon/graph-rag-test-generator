# 11 — SUT의 DataSource를 ProxyDataSource로 감싸기

graph-rag-builder의 SQL 캡처는 SUT에 in-process로 부착되는 runtime hook입니다.
JDBC 레이어에서 가로채는 방법(ProxyDataSource)과 MyBatis 전용 SPI에서 가로채는 방법
두 가지가 있고, 본 문서는 전자(범용·1순위 권장)를 정리합니다. MyBatis Interceptor
경로 비교는 마지막 절에 요약합니다.

## 1. 핵심 패턴 — Spring `BeanPostProcessor`로 사후 wrap

샘플: [`samples/demo-sut/src/test/java/io/graphrag/demo/Phase0E2eTest.java`](../samples/demo-sut/src/test/java/io/graphrag/demo/Phase0E2eTest.java) lines 60–78.

```java
@TestConfiguration
static class ProxyDsConfig {
    @Bean
    static BeanPostProcessor dataSourceProxyPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource && !(bean instanceof ProxyDataSource)) {
                    return ProxyDataSourceBuilder.create((DataSource) bean)
                            .name("graph-rag-capture")
                            .listener(new CapturedSqlListener())   // afterQuery 콜백
                            .build();
                }
                return bean;
            }
        };
    }
}
```

이 한 조각이 SUT의 모든 DataSource 빈을 투명하게 ProxyDataSource로 대체합니다.

## 2. 동작 흐름

1. Spring이 SUT의 DataSource 빈을 평소대로 생성 (Hikari/Tomcat/H2/...).
2. `postProcessAfterInitialization()`이 모든 빈에 대해 호출되어 `DataSource` 타입만
   가로챈 뒤 `ProxyDataSourceBuilder.create(원본).listener(...).build()` 결과로 교체.
3. 컨테이너의 DataSource 빈이 ProxyDataSource로 대체됨 — JPA/JdbcTemplate/MyBatis 등
   DataSource를 주입받는 모든 빈은 자동으로 proxy를 받음 (Spring DI는 빈 타입/이름 기반).
4. 재귀 방지 — `!(bean instanceof ProxyDataSource)` 체크로 이미 wrap된 것은 건너뜀.

## 3. 왜 `BeanPostProcessor`인가 (`@Bean DataSource`가 아닌)

원본 DataSource를 `@Bean DataSource dataSource(...)` 로 직접 정의하면 Spring Boot의
`DataSourceAutoConfiguration`과 충돌하고, JPA `EntityManagerFactory` ↔ DataSource
순환 의존성을 유발하기 쉽습니다.

`BeanPostProcessor`는 *기존 빈 생성 직후* 가로채는 hook이라 autoconfig를 건드리지 않고
투명하게 wrap 가능 — SUT의 빈 정의/설정을 0줄 수정합니다.

## 4. `static @Bean` 선언이 중요

`BeanPostProcessor`는 다른 어떤 빈보다 먼저 인스턴스화돼야 하기 때문에 outer
`@Configuration` 인스턴스화 *전에* 만들 수 있어야 합니다. 그래서 `static` 팩토리
메서드여야 합니다.

non-static이면 부팅 로그에:
```
Bean '...' is not eligible for getting processed by all BeanPostProcessors
```
경고가 뜨고, 본인이 BeanPostProcessor임에도 자기 자신이 다른 BPP들의 처리 대상에서
제외되는 어색한 상태가 됩니다. 동작은 하지만 권장되지 않음.

## 5. `CaptureContext`와의 연결

wrap된 ProxyDataSource는 모든 쿼리를 listener의 `afterQuery()`로 흘립니다.

[`graph-rag-builder/.../capture/CapturedSqlListener.java`](../graph-rag-builder/src/main/java/io/graphrag/builder/capture/CapturedSqlListener.java) 핵심:

```java
@Override
public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
    CaptureContext ctx = CaptureContext.current();   // ThreadLocal lookup
    if (ctx == null) return;                          // 활성 컨텍스트 없으면 noop
    for (QueryInfo qi : queryInfoList) {
        List<Object> params = flattenFirstBatch(qi);
        CapturedSql sql = CapturedSqlBuilder.build(
                ctx.pathId(), qi.getQuery(), params, defaultSource);
        ctx.addCapturedSql(sql);
    }
}
```

평소엔 noop, **테스트가 명시적으로 `CaptureContext.set(...)` 한 thread에서만**
SQL을 누적합니다. 그래서:
- 같은 SUT를 여러 path 캡처에 재사용 가능 (path별 ThreadLocal 격리)
- 운영 트래픽이 흘러도 캡처 컨텍스트 없으면 비용 0
- ProxyDataSource는 상시 부착돼도 안전

## 6. 분석 harness의 전형적 사용 시퀀스

```java
// 1. 캡처 컨텍스트 활성화 (현재 thread에만)
CaptureContext ctx = new CaptureContext("path-happy-201");
CaptureContext.set(ctx);
try {
    // 2. SUT의 endpoint 호출 (이 thread에서 발행된 모든 SQL이 ctx에 쌓임)
    mvc.perform(post("/api/orders").content(...))
       .andExpect(status().isCreated());

    // 3. 캡처 결과 회수
    List<CapturedSql> captured = ctx.capturedSql();

    // 4. archive로 영속화 (4-JSON 형식)
    captured.forEach(archive::addCapturedSql);
    archive.save();
} finally {
    CaptureContext.clear();  // ThreadLocal 누수 방지
}
```

## 7. 한계와 주의점

| 상황 | 결과 / 대응 |
|---|---|
| SUT가 자체 `@Bean DataSource` 메서드 *내부에서* 직접 `dataSource.someMethod()`를 호출 | `BeanPostProcessor`는 컨테이너에서 꺼낼 때만 wrap된 것을 줌. 메서드 내부 자기참조는 원본을 봄 — 대부분의 경우 무관 |
| ProxyDataSource로 못 감싸는 DataSource 구현 (`final` 클래스, 구체 타입 강제 캐스팅 의존) | `ProxyDataSourceBuilder`는 JDK proxy 기반 (`DataSource` 인터페이스만 봄) — 대부분 OK. 단, SUT가 `bean.getClass().cast(...)` 같은 구체 타입 가정을 하면 깨질 수 있음 |
| 여러 DataSource 빈 (multi-tenant 등) | `BeanPostProcessor`가 모두 wrap. capture listener도 모든 DataSource에서 동작 |
| 비동기 thread에서 SQL 발행 (`@Async`, `CompletableFuture.supplyAsync`, reactive) | `CaptureContext`는 ThreadLocal이라 자식 thread는 캡처 안 됨 — propagation (예: `TaskDecorator`, `ContextSnapshot`) 필요. **현재 미구현** |
| batch INSERT (`addBatch` × N → `executeBatch`) | 현재 `flattenFirstBatch(qi)` — 첫 batch만 캡처. Phase 1+ TODO |
| 바인딩 origin 분류 (API 파라미터인지 리터럴인지) | 모두 `COMPUTED`로 마킹. Phase 1+ dataflow 분석으로 `API_PARAM/LITERAL` 강화 예정 |

## 8. MyBatis 경로 비교

ProxyDataSource는 JDBC 레이어 hook이므로 **MyBatis가 발행한 SQL도 그대로 잡힙니다.**
그러나 graph-rag는 더 정밀한 메타데이터를 위해 MyBatis 전용 Plugin/Interceptor도
별도 제공합니다 ([`.../capture/mybatis/MyBatisCaptureInterceptor.java`](../graph-rag-builder/src/main/java/io/graphrag/builder/capture/mybatis/MyBatisCaptureInterceptor.java)).

| 측면 | ProxyDataSource (JDBC) | MyBatis Interceptor |
|---|---|---|
| 설치 위치 | `BeanPostProcessor`로 DataSource wrap | `Configuration.addInterceptor()` (MyBatis SPI) |
| Spring Boot 설치 | 위 `@TestConfiguration` 1개 | `@Bean ConfigurationCustomizer cfg -> cfg.addInterceptor(...)` |
| 보이는 SQL | 최종 prepared SQL | 동일 (`BoundSql.getSql()` — 동적 SQL 평가 완료 후) |
| 바인딩 추출 | JDBC `setX` 인자 (`getParametersList`) | `BoundSql.getParameterMappings()` + parameter 객체 reflective 추출 |
| provenance | 발신 코드 알 수 없음 | `MappedStatement.getId()`로 어떤 Mapper / XML statement인지 식별 가능 |
| 범위 | 모든 JDBC 호출 (raw JDBC, JdbcTemplate 포함) | MyBatis가 통과시킨 것만 |

**선택 가이드**:
- MyBatis-heavy 프로젝트 → MyBatis Interceptor (provenance가 풍부)
- 혼재 (MyBatis + JPA + JdbcTemplate) → ProxyDataSource (단일 hook으로 모두 잡음)
- 저전력·최소 변경 → ProxyDataSource (BeanPostProcessor 1개로 끝)

둘 다 등록해도 동작은 하지만 같은 쿼리가 중복 캡처되므로 보통 하나만 선택합니다.

## 9. 외부 프로젝트 적용 체크리스트

1. `testImplementation` (또는 `test scope`)으로 `net.ttddyy:datasource-proxy` +
   `graph-rag-builder` 추가.
2. 테스트 `src/test/java` 어딘가에 `@TestConfiguration` + `BeanPostProcessor`
   (위 §1 코드) 작성. 비즈니스 코드 0줄 수정.
3. 분석 harness에서 `CaptureContext.set(new CaptureContext(pathId))` → endpoint
   호출 → `ctx.capturedSql()` → archive 저장 → `CaptureContext.clear()`.
4. test-generator의 `--archive` 모드로 합성 → 생성된 테스트의 `try {…} finally`
   블록에 INSERT/DELETE가 자동 포함됨.

## 참조

- [`docs/03-graph-rag-builder.md`](03-graph-rag-builder.md) — 캡처/그래프 전반
- [`docs/04-test-generator.md`](04-test-generator.md) — 합성기 (FixtureComposer 포함)
- [`docs/06-test-environment.md`](06-test-environment.md) — 분석/실행 환경
- [`graph-rag-builder/src/main/java/io/graphrag/builder/capture/CapturedSqlListener.java`](../graph-rag-builder/src/main/java/io/graphrag/builder/capture/CapturedSqlListener.java)
- [`graph-rag-builder/src/main/java/io/graphrag/builder/capture/CapturedSqlBuilder.java`](../graph-rag-builder/src/main/java/io/graphrag/builder/capture/CapturedSqlBuilder.java)
- [`graph-rag-builder/src/main/java/io/graphrag/builder/capture/CaptureContext.java`](../graph-rag-builder/src/main/java/io/graphrag/builder/capture/CaptureContext.java)
- [`graph-rag-builder/src/main/java/io/graphrag/builder/capture/mybatis/MyBatisCaptureInterceptor.java`](../graph-rag-builder/src/main/java/io/graphrag/builder/capture/mybatis/MyBatisCaptureInterceptor.java)
- [`samples/demo-sut/src/test/java/io/graphrag/demo/Phase0E2eTest.java`](../samples/demo-sut/src/test/java/io/graphrag/demo/Phase0E2eTest.java) — 실제 wrap 패턴 사용 예
