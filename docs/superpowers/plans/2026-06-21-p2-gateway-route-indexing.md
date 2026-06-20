# P2 — 선언형 Spring Cloud Gateway 라우트 인덱싱 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]` checkboxes. 생성 코드 변경은 **런타임 실행 테스트**로도 검증한다(P3 교훈: 소스-문자열 검사만으로는 런타임 결함을 놓침).

**Goal:** `RouteLocatorBuilder.routes().route(...).uri(target)...build()` DSL로 선언된 게이트웨이 프록시 라우트를 정적으로 발견·인덱싱하고(REQ-005/006), 얕은 모드 프록시 계약 스모크 테스트를 생성한다(REQ-007). 현재: `@RestController`/`@*Mapping`/`RouterFunction`만 봐서 게이트웨이 프록시 라우트는 0개.

**Architecture:** P1의 `RouterFunctionIndexer` 패턴을 그대로 따른다 — 자체 Spoon Launcher(noClasspath)를 가진 `GatewayRouteIndexer`가 `RouteLocatorBuilder` DSL 호출 체인을 순회해 (path predicate, target uri, path-변환 필터)를 추출한다. 게이트웨이 라우트는 다운스트림 프록시이므로 `Endpoint`로 모델링하되 다운스트림 `targetUri`를 보존한다. `BuilderCli.build()`에서 `IndexResult.merge`로 합류(P1 동형). REQ-007은 별도 proxy-smoke 생성 경로.

**Tech Stack:** Java 17, Spoon(noClasspath), JUnit5+AssertJ+@TempDir(P1 indexer 테스트 패턴), WireMock/`--external-stubs`(e2e), Mustache.

**REQ:** REQ-005(라우트 발견, Must)·REQ-006(필터 파싱, Must)·REQ-007(스모크 생성, Must). REQ-008(깊은 모드)은 🔵 연기.

> 출처: RFC §5 P2, 요구사항 REQ-005~007. 우선순위 단일기준 RFC §7(P2 = 3순위).

## 핵심 설계 결정
1. **targetUri 저장:** `Endpoint` record에 nullable `String targetUri`를 추가하되, **기존 7-arg 호출부 호환 compat-constructor**(`targetUri=null` 기본)를 둬 RouterFunctionIndexer/EndpointIndexer/테스트의 churn을 0으로 한다(shared-model `ExploredPath`의 compat-constructor 선례). 게이트웨이 라우트만 targetUri를 채운다.
2. **게이트웨이 라우트 = Endpoint:** httpMethod는 predicate에 method가 있으면 그 값, 없으면 기본 `GET`(스모크는 프록시 도달만 검증). path = predicate path(필터 변환 적용 후). handlerClass = RouteLocator 선언 클래스, handlerMethod = 선언 메서드.
3. **얕은 모드(REQ-007):** 다운스트림을 `--external-stubs`(WireMock)로 스텁하고, 게이트웨이 path로 요청해 (a) 스텁 반환 status 일치, (b) 전파 헤더 존재를 단언. 빈 단언 테스트 금지.

## 현황(실코드, off main 6961387)
- 인덱서 패턴: `graph-rag-builder/.../index/RouterFunctionIndexer.java`(P1) — Spoon Launcher + `TypeFilter<CtInvocation>` 순회, `EndpointIds.of`, `IndexResult.merge`, receiver-type 가드.
- `Endpoint`(shared-model): 7-arg record(targetUri 없음).
- `BuilderCli.build()` L164~167: RouterFunctionIndexer 배선+merge(미러 대상).

---

## Task 1: `Endpoint`에 nullable `targetUri` + compat-constructor (REQ-005 prep)

**REQ-IDs:** REQ-005

**Files:** Modify `shared-model/src/main/java/io/graphrag/model/Endpoint.java`; Test `shared-model/src/test/java/io/graphrag/model/EndpointTest.java`(없으면 생성).

- [ ] Step 1 — 실패 테스트: 8-arg(targetUri 포함) 생성 + 7-arg compat(targetUri==null) 생성 둘 다 동작 + JSON round-trip(있으면).
```java
@Test void endpoint_targetUri_optional_andCompatCtor() {
    var gw = new io.graphrag.model.Endpoint("g","GET","/api/v1/**","C","m", java.util.List.of(), false, "http://downstream");
    org.assertj.core.api.Assertions.assertThat(gw.targetUri()).isEqualTo("http://downstream");
    var plain = new io.graphrag.model.Endpoint("p","GET","/x","C","m", java.util.List.of(), false);
    org.assertj.core.api.Assertions.assertThat(plain.targetUri()).isNull();
}
```
- [ ] Step 2 실패확인 → Step 3 구현: record에 `String targetUri` 추가 + 7-arg compact compat-constructor(`this(..., null)`). (Jackson round-trip이 있으면 `@JsonInclude`/기본 null 처리 확인.) → Step 4 통과 + **전체 빌드 회귀**: `./gradlew :shared-model:test :graph-rag-builder:test :test-generator:test -q` BUILD SUCCESSFUL(기존 7-arg 호출부 전부 컴파일). → Step 5 commit `feat(model): optional Endpoint.targetUri + compat ctor (REQ-005)`.

---

## Task 2: `GatewayRouteIndexer` — 라우트·targetUri·필터 발견 (REQ-005/006)

**REQ-IDs:** REQ-005, REQ-006

**Files:** Create `graph-rag-builder/.../index/GatewayRouteIndexer.java`; Test `graph-rag-builder/src/test/java/io/graphrag/builder/index/GatewayRouteIndexerTest.java`.

**Spoon 전략(RouterFunctionIndexer 미러):** `RouteLocatorBuilder` Java DSL은
```java
@Bean RouteLocator routes(RouteLocatorBuilder b) {
  return b.routes()
    .route("orders", r -> r.path("/api/v1/orders/**").filters(f -> f.stripPrefix(1)).uri("http://orders"))
    .route(r -> r.path("/api/v1/users/**").uri("lb://users"))
    .build();
}
```
형태다. 순회: `RouteLocator` 반환 메서드 본문의 `CtInvocation` 중 — 같은 route 람다 안에서 (a) `.path("...")` 또는 `.path(...)`의 String 리터럴 인자 → predicate path, (b) `.uri("...")`의 String 리터럴 → targetUri, (c) `.filters(f -> f.stripPrefix(n)/rewritePath(a,b)/setPath(p)...)` → path 변환. route()의 람다(`CtLambda`)별로 묶어 한 라우트로 합성한다(람다 파라미터 스코프로 그룹핑; 람다 해석 실패 시 best-effort, path만이라도 발견).

- [ ] Step 1 — 실패 테스트(@TempDir + DSL 소스 작성, RouterFunctionIndexerTest 패턴): 위 2-route 소스 → 2개 Endpoint 발견, path/targetUri 정확, `stripPrefix(1)` 적용 시 변환된 path 확인.
```java
IndexResult r = new GatewayRouteIndexer().index(dir);
assertThat(r.endpoints()).extracting(Endpoint::path, Endpoint::targetUri)
    .contains(tuple("/orders/**","http://orders"),   // stripPrefix(1) 적용 후
              tuple("/api/v1/users/**","lb://users"));
```
- [ ] Step 2 실패확인 → Step 3 구현(라우트 그룹핑 + path/uri 추출 + StripPrefix/RewritePath/SetPath 변환; 미지원 필터/SpEL predicate 감지 시 `log.warn` + 해당 라우트 제외(REQ-006); `Endpoint`의 targetUri 채움; `EndpointIds.of` 사용) → Step 4 통과 + 미지원 필터 제외 테스트 추가 → Step 5 commit `feat(index): GatewayRouteIndexer discovers proxy routes + path-transform filters (REQ-005,006)`.
  - **확인 필요(구현 시):** noClasspath에서 route 람다 그룹핑 방식(같은 `.route(...)` invocation의 인자 람다 내부 invocations만 한 라우트로). `lb://`(load-balanced) uri도 리터럴로 보존.

---

## Task 3: `BuilderCli` 배선 + merge (REQ-005)

**REQ-IDs:** REQ-005

P1과 동형. `BuilderCli.build()`의 RouterFunction merge 직후:
```java
IndexResult gateway = new GatewayRouteIndexer().index(config.sutSrc());
if (!gateway.endpoints().isEmpty()) {
    log.info("found {} gateway route(s) (RouteLocator)", gateway.endpoints().size());
    index = index.merge(gateway);
}
```
- [ ] 통합 테스트 `GatewayRouteFixtureIT`(어노테이션 0 + 게이트웨이 라우트 → 머지 endpoints ≥1, REQ-005 임계). 전체 `:graph-rag-builder:test` 회귀 green. commit `feat(cli): wire GatewayRouteIndexer + merge (REQ-005)`.

---

## Task 4: 얕은 모드 프록시 스모크 테스트 생성 (REQ-007)

**REQ-IDs:** REQ-007

> 이 task는 generator/e2e 파이프라인 재그라운딩 후 확정한다(Task 1~3 머지 후). 설계 방향:
> - 게이트웨이 Endpoint(targetUri 있음)에 대해, generator가 **proxy-smoke 시나리오**를 emit: WireMock(`--external-stubs`)에 targetUri 경로의 스텁(예: 200 + 헤더)을 두고, 게이트웨이 path로 요청 → status 일치 + 전파 헤더 존재 단언.
> - **확인 필요(그라운딩):** generator가 ExploredPath 없이 Endpoint만으로 스모크를 emit하는 경로(기존 explore 우회) / `--external-stubs` 주입 방식 / e2e 게이트웨이 fixture(`RouteLocator` 미니 SUT) 위치. 기존 attach/external-stubs e2e(`e2e/run-attach-ext-http-e2e.sh` 등) 참조.
> - **런타임 검증**(P3 교훈): 생성 스모크 테스트가 실제 스텁된 게이트웨이에 대해 green인지 e2e로 확인(빈 단언 금지).

- [ ] (상세 TDD는 Task 1~3 완료 후 generator/e2e 재그라운딩하여 이 plan에 추가.)

---

## Task 5: 회귀 + 매트릭스 (REQ-005~007 🟢)

- [ ] `:graph-rag-builder:test :test-generator:test -q` + `./e2e/run-e2e.sh`(+게이트웨이 fixture e2e) green. 매트릭스 REQ-005/006/007 🟢(REQ-007은 Task 4 완료 시), Coverage 갱신. commit.

---

## Self-Review(작성자)
- Spec coverage: REQ-005(T1,T2,T3)·REQ-006(T2)·REQ-007(T4). 매핑 누락 없음.
- 설계 결정: targetUri를 Endpoint compat-ctor로(churn 0), 게이트웨이=Endpoint, 얕은 모드 우선(깊은 모드 REQ-008 🔵).
- **확인 필요(구현 시 grounding):** ① route 람다 그룹핑(noClasspath) ② generator의 Endpoint-only 스모크 emit 경로 ③ e2e external-stubs 게이트웨이 fixture. ①은 Task 2, ②③은 Task 4에서 확정.
- **런타임 검증 의무**(P3 교훈): REQ-007 스모크는 생성 소스 검사 + e2e 실행 둘 다.

## Execution
Subagent-driven, task별 spec+quality 리뷰 + 런타임 검증. CI watch → rebase 머지.
