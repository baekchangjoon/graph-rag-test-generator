# 엔드포인트 단위 테스트클래스 그룹화 + 병렬 실행 활성화

- 날짜: 2026-06-18
- 상태: 설계 확정 (3-모델 cross-vendor 리뷰 반영 완료)
- 범위: `test-generator`(HTTP REST 생성 경로) + `testlib`(deferred cleanup) + 생성 산출물의 `junit-platform.properties`

## 1. 문제 정의

현재 `test-generator`는 **생성 단위가 `ExploredPath`(시나리오 1개)** 다. `Generator.generate()`가 한 엔드포인트의 path들을 돌며 path마다 `generateSingle()`을 호출하고, 각 호출이 **클래스 1개 + `@Test` 1개**짜리 파일을 만든다. 결과적으로 같은 엔드포인트·같은 HTTP 메소드인데도 시나리오(happy / 404 / 변종)마다 파일이 쪼개진다.

```
post-api-orders (POST /api/orders) →
  OrdersPostTest_HAPPY.java     (@Test 1개)
  OrdersPostTest_S404_1.java    (@Test 1개)
  OrdersPostTest_S201_2.java    (@Test 1개)
  OrdersPostTest_S201_3.java    (@Test 1개)
```

두 가지 문제:

1. **의미적 구조 불일치.** JUnit 관례상 같은 엔드포인트의 시나리오들은 한 테스트클래스의 여러 `@Test` 메소드로 묶는 것이 자연스럽다. 현재는 파일이 과도하게 분할된다.
2. **병렬 실행이 산출물에 활성화되지 않음.** 생성기는 병렬 안전을 *고려*한다(`ParallelSafetyReport` + 격리 불가 시 클래스에 `@Execution(SAME_THREAD)`). 그러나 JUnit5 concurrent를 실제로 켜는 `junit-platform.properties`는 **생성기 자신의 e2e 하니스(`e2e/src/test/resources/junit-platform.properties`)에만** 존재하고, `GeneratorCli`는 산출물에 그 파일을 내보내지 않는다. 따라서 실소비자가 생성 테스트를 자기 프로젝트에서 돌리면 JUnit5 기본값인 **순차 실행**으로 떨어진다.

## 2. 목표 / 비목표

**목표**
- HTTP REST 엔드포인트 1개의 (테스트 대상) 시나리오를 **클래스 파일에 평탄(flat) `@Test` 메소드 N개**로 묶어 생성.
- 시나리오별 픽스처를 **메소드 단위**로 격리하면서 병렬 실행 안전.
- 생성기가 `junit-platform.properties`를 산출물에 emit하여 병렬 실행이 실제로 켜지도록.
- 병렬 cleanup 안전성(메소드 간 간섭/플래키 없음)을 구조적으로 보장.

**비목표**
- WS(STOMP) / Kafka 생성 경로는 이번 범위 아님 — 현 "exchange당 클래스" 유지. 본 변경 검증 후 동일 패턴으로 후속 확장.
- `@Controller` 폼 엔드포인트(현재 커버리지 전용, 생성 미지원)는 변경 없음.
- 입력 탐색/시드 해석/픽스처 합성 로직(`FixtureComposer`, `HttpMockComposer`, 빌더) 자체는 변경 없음 — 출력 조립 계층만 변경.

## 3. 결정 요약 (브레인스토밍 + 3-모델 리뷰)

| 결정 | 선택 | 근거 |
|---|---|---|
| 묶음 단위(키) | **엔드포인트(메소드+경로)** | HTTP `Endpoint`는 단일 httpMethod라 "같은 엔드포인트 = 같은 메소드+경로". 가장 직관적. |
| 클래스 구조 | **평탄 `@Test` + 메소드별 픽스처 격리** | 사용자가 요구한 "한 클래스 여러 메소드" 그대로. 표준 JUnit 관례. |
| Cleanup | **testlib deferred-cleanup (FIFO)** | 공유 `@AfterEach`가 메소드별 시드를 알 수 없는 문제를, 메소드별 scope에 정리 등록으로 해결. "시드 실패 시에도 정리 보장"이 더 견고. |
| 병렬 설정 | **`junit-platform.properties` emit, strategy=dynamic factor=1** | 소비자 머신 코어 수에 자동 적응. 과도 스레드 방지. |
| 병렬/직렬 분리 | **병렬-안전 시나리오만 병합, propagation-missing(직렬)은 별도 클래스** | "OTEL/Sleuth로 test-id 전파해 격리"가 주 경로. 직렬은 전파 불가 SUT용 안전망. 병합 클래스는 항상 전원 병렬-안전 → 격리 의미·병렬도 **무회귀**. |
| 적용 범위 | **HTTP만** | 스코프·리스크 최소. WS/Kafka는 격리 특성이 달라 후속. |

## 4. 설계

### 4.0 핵심 통찰 — baggage 전파가 병렬 격리의 주 경로 (직렬은 안전망)

`HttpMockComposer.compose()`(`HttpMockComposer.java:25,34`)는 캡처된 외부 HTTP 호출의 `call.baggagePropagated()`로 격리 전략을 정한다:
- **전파됨(`true`)**: 스텁에 `.withBaggageTestId(scope.testId())` → **test-id로 필터** → 동시 실행돼도 각 테스트가 자기 호출만 매칭 → **병렬-안전**. OTEL agent / Spring Cloud Sleuth가 outbound 호출에 baggage를 실어주면 이 경로.
- **미전파(`false`)**: 스텁이 test-id 필터를 못 걸어 공유 스텁이 됨 → `propagationMissing=true` → **직렬 표시**. OTEL agent도 Sleuth도 없어 컨텍스트 전파가 끊긴 SUT의 폴백.

OTEL agent가 기본(default otel)인 현 환경에서는 outbound에 baggage가 실리므로 **대부분 경로가 병렬-안전이고 직렬 케이스는 거의 발생하지 않는다.** 본 설계는 그 드문 직렬 케이스를 **현재와 동일한 메커니즘(별도 클래스 + 클래스레벨 `@Execution(SAME_THREAD)`)** 으로 보존한다(§4.5).

> **JUnit5 직렬 격리 메커니즘 주의:** `@Execution(SAME_THREAD)`는 **상호배제(mutual exclusion)가 아니라 스레드 친화(thread affinity)** 다. SAME_THREAD로 표시된 모든 테스트는 **단일(root) 스레드에 고정되어 서로 순차 실행**된다 — 이것이 격리의 본질이다(미전파 스텁끼리 동시 실행 불가). baggage 필터가 있는 병렬 테스트는 다른 스레드에서 동시 실행되지만, WireMock이 더 구체적인(baggage 매칭) 스텁을 우선 매칭하므로 충돌하지 않는다. 따라서 **메소드레벨 SAME_THREAD는 같은 클래스의 다른 동시 메소드로부터 격리해주지 못하며**, 본 설계는 직렬 시나리오를 병합 클래스에 넣지 않음으로써(§4.5 Option A) 이 함정을 회피한다.

### 4.1 생성 구조 (`Generator`)

`generate()`의 HTTP 경로(현 `Generator.java:69-90`)를 다음으로 바꾼다. `generateSingle()`은 **제거**하고 그 로직을 두 함수로 흡수한다:

- **`buildScenarioMethod(endpoint, request, path)`** (신규) — path 1개를 **`ScenarioMethod`** 중간모델로 변환. 렌더는 하지 않는다. (현 `generateSingle`의 scope 구성 로직이 여기로 이동.)
- **`renderTestClass(packageName, className, methods, classSerial)`** (신규) — 메소드 N개를 가진 클래스 파일 1개를 렌더. `classSerial=true`면 클래스레벨 `@Execution(SAME_THREAD)` + import를 추가.

`generate()` HTTP 경로 알고리즘:
1. 기존 path 필터(`negative-auth` / `negative-validation` 스킵)는 그대로 유지.
2. 각 path를 `buildScenarioMethod`로 `ScenarioMethod`로 변환하고, `m.serial`(= `mocks.propagationMissing()`) 기준으로 **병렬-안전(parallel)** 과 **직렬(serial)** 로 분할.
3. **병렬-안전 메소드들** → `renderTestClass(request.testClassName(), parallelMethods, classSerial=false)` 로 **클래스 1개**.
4. **직렬 메소드들** → `renderTestClass(request.testClassName()+"Serial", serialMethods, classSerial=true)` 로 **클래스 1개**(class-level SAME_THREAD). 직렬 시나리오들은 어차피 전역 단일 스레드에 고정되어 순차 실행되므로 한 클래스로 묶어도 안전하다.
5. 각 클래스는 **메소드가 1개 이상일 때만** emit한다(병렬/직렬 어느 한쪽이 비면 그 파일은 안 만듦).
6. 생성된 테스트 파일이 1개라도 있으면 `junit-platform.properties`를 결과에 **1회** 포함(§4.6).

**단건 지정(`request.pathId() != null`)**: 해당 path 1개만 `buildScenarioMethod`로 만들어 `renderTestClass(request.testClassName(), [method], classSerial = method.serial)` 로 **클래스 1개**(1 메소드). 그 path가 직렬이면 클래스레벨 SAME_THREAD. 현 단건 동작과 동치(후방호환).

**`ScenarioMethod` 중간모델 (신규, record):**
- `methodName` — `path.id()`에서 endpoint 접두어(`endpointId + "-"`)를 떼고 `-`→`_` 치환(예: `post-api-orders-s201-3` → `s201_3`). **접두어로 시작하지 않으면 전체 `path.id()`를 베이스로 사용**(현 `classSuffix()` `Generator.java:261-264` 폴백과 동일). 동일 클래스 내 충돌 시 접미 인덱스(`_2`, `_3`).
- `vars` — 시나리오 지역변수(현 `ComposedFixture.vars()`), **인스턴스 필드 아님**.
- `inserts` — 시드 INSERT 목록(현 `ComposedFixture.inserts()`).
- `deletes` — cleanup DELETE 목록(현 `ComposedFixture.deletes()`, **이미 FK 역순=child-first**). deferDelete로 **그 순서대로** 등록(§4.2, §4.3).
- `mocksBlock` — WireMock 스텁 블록(현 `HttpMockComposer` 출력).
- `requestPath`, `httpMethodLower`, `readPath`, `bodyExpr`, `expectedStatus`, `assertionsBlock`, `authRequired` — 현 `generateSingle` scope 값과 동일.
- `serial` — `mocks.propagationMissing()`.

`vars`가 인스턴스 필드에서 메소드 지역변수로 내려가는 점이 유일한 의미 변화다. `testId` 격리는 그대로: 공유 `@BeforeEach`가 메소드마다 새 `scope`를 발급한다(§4.4 불변식).

### 4.2 템플릿 (`templates/test-class.mustache` 재구성)

클래스 셸 1개 + `{{#methods}}…{{/methods}}` 반복 블록:

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

    private TestScope scope;     // non-static 인스턴스 필드 (불변식 §4.4)

    @BeforeEach
    void setUp() {
        scope = TestScope.create();
    }

    @AfterEach
    void cleanup() {
        scope.cleanup();         // deferred delete(FIFO) + mock 해제 — §4.3. 직접 DELETE 없음.
    }
{{#methods}}

    @Test
    void {{methodName}}() {
{{#vars}}        String {{name}} = {{{valueExpr}}};
{{/vars}}        // 시드 INSERT + 동일 시나리오 cleanup DELETE 등록(FK 역순 그대로)
{{#inserts}}        scope.jdbc().update("{{{sql}}}"{{#argExprs}}, {{{.}}}{{/argExprs}});
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

- `classSerialMark` — 클래스가 직렬 클래스면 `"@Execution(ExecutionMode.SAME_THREAD)\n"`, 아니면 빈 문자열.
- `serialImports` — 클래스가 직렬이면 Execution/ExecutionMode import, 아니면 빈 문자열. (Option A에서 **메소드레벨 SAME_THREAD는 emit하지 않는다** — 직렬 시나리오는 통째로 직렬 클래스에 있으므로 클래스레벨 한 곳만.)
- **mocksBlock 위치 변경 명시:** 현 템플릿은 `mocksBlock`을 `@BeforeEach`에 둔다. 새 템플릿은 **`@Test` 메소드 본문(요청 직전)** 에 둔다. RestAssured 요청은 동기 실행이라 같은 메소드 본문에서 **스텁 등록 → SUT 호출 → (@AfterEach의) 스텁 제거**가 순차 보장된다. `@AfterEach`의 `scope.cleanup()`은 테스트가 예외로 끝나도 JUnit이 항상 실행하므로 `http.removeAllForScope()` 누락 없음.
- **`@AfterEach` 직접 DELETE 제거 명시:** 새 `@AfterEach`는 `scope.cleanup()` **한 줄만**. 현 템플릿의 `scope.jdbc().update("DELETE …")` 직접 호출은 **전부 제거**되고 deferDelete 등록 + `scope.cleanup()` 로 대체된다(이중 삭제 방지).

### 4.3 testlib deferred-cleanup (FIFO)

`JdbcHelper`에 추가:
- `public void deferDelete(String sql, Object... args)` — `(sql, args)`를 인스턴스 **리스트에 append**(등록 순서 보존).
- `void runDeferredDeletes()` — 리스트를 **등록 순서(FIFO)대로** 실행. 실패는 best-effort 로그 후 계속(cleanup이 테스트를 실패시키지 않음 — 현 `close()` 정책과 일관).

**순서 근거(FIFO):** `FixtureComposer`가 만드는 `deletes`는 **이미 FK 역순(child-first)=올바른 DELETE 순서**다(`FixtureComposer.java:79` `Collections.reverse`, `:133-138` fkDepth 정렬). 따라서 `deletes`를 그 순서대로 등록하고 **등록 순서대로(FIFO) 실행**하면 올바른 child→parent 삭제가 된다. (LIFO를 쓰면 순서가 뒤집혀 parent-first가 되어 FK 위반 — 그래서 **LIFO가 아니라 FIFO**.)

`TestScope.cleanup()` 변경:
- mock/연결 해제 **전에** `jdbc.runDeferredDeletes()` 호출.
- 현 주석 "DB row 정리는 테스트 코드가 FK 역순으로 직접 수행" → "scope가 등록된 정리(deferred delete)를 FK 역순으로 실행" 으로 갱신.

**후방호환:** `deferDelete`를 한 번도 호출하지 않으면 리스트가 비어 동작 불변. 기존 WS/Kafka 템플릿(명시적 `@AfterEach` DELETE 유지)은 영향 없음.

### 4.4 병렬 안전 불변식 (메소드 간 cleanup 격리)

병렬 실행 시 cleanup이 메소드 간 간섭하지 않음을 **구조적으로** 보장한다. 핵심은 **JUnit5 기본 인스턴스 생명주기 `PER_METHOD`**:

- JUnit5는 병렬이어도 **테스트 메소드마다 클래스 인스턴스를 새로 생성**한다.
- `scope`는 **non-static 인스턴스 필드** → 메소드마다 별도 인스턴스.
- `@BeforeEach`가 메소드마다 `scope = TestScope.create()` → 메소드마다 **별도 scope, 별도 `JdbcHelper`, 별도 등록 리스트, 별도 testId**.
- deferDelete 리스트는 `JdbcHelper` 인스턴스 필드 → **그 메소드 1개만의 리스트**. 단일 메소드 본문은 단일 스레드 → 동시 접근 없음.
- 각 메소드 scope는 **고유 testId** 기반 unique 값으로 시드 → row가 메소드 간 disjoint. 각 메소드 `@AfterEach`는 자기 리스트의 DELETE만 실행 → 완료 순서가 뒤바뀌어도 서로 간섭 불가.

**불변식 (구현이 반드시 지킬 것):**
1. 생성 클래스는 **절대 `@TestInstance(PER_CLASS)`를 emit하지 않는다** (기본 PER_METHOD 의존).
2. `scope`는 non-static 인스턴스 필드, `@BeforeEach`에서 매 메소드 재할당.
3. deferDelete 리스트는 `JdbcHelper` 인스턴스 필드(static 금지).

이 불변식이 깨지면(예: PER_CLASS) scope·리스트·testId가 메소드 간 공유되어 cleanup 플래키 + 격리 붕괴가 발생한다. §6의 가드 테스트가 이를 회귀로 잡는다.

### 4.5 병렬/직렬 분리 (Option A) 와 `ParallelSafetyReport`

§4.1 알고리즘대로 **병렬-안전 시나리오만 병합 클래스에, propagation-missing(직렬) 시나리오는 별도 직렬 클래스에** 둔다. 결과적으로 **어떤 클래스도 병렬·직렬 메소드를 섞지 않는다** — 병합 클래스는 전원 병렬, 직렬 클래스는 전원 직렬(클래스레벨 SAME_THREAD).

따라서 **`ParallelSafetyReport`는 현행 클래스레벨 식별자를 그대로 유지**한다(`Class#method` 형식 변경 불필요 → 모델 변경 없음, 다운스트림 파급 최소):
- `fullyParallel: List<String>` — 병렬 클래스명들(예: `["OrdersPostTest"]`).
- `serialRequired: List<SerialRequired>` — 직렬 클래스 1개당 1개. `SerialRequired(test="OrdersPostTestSerial", reason="SUT_PROPAGATION_MISSING", details=…)`. (필드명은 `details` — `SerialRequired.java:6` 확인.)
- 직렬 클래스의 여러 시나리오가 같은 `reason`(HTTP는 SUT_PROPAGATION_MISSING 단일)을 공유하므로 클래스 1개 → `SerialRequired` 1개로 집계.

> **블래스트 반경:** 식별자 **형식**은 안 바뀌므로(클래스레벨 유지) `ParallelSafetyReport`/`SerialRequired` 모델 코드 변경은 없다. 다만 **내용**(어떤 클래스명이 나오는지)이 바뀌므로 다음 테스트의 단언을 갱신한다: `GeneratorTest`(`:48-63,97,111-114,145` 등 클래스명 단언), `GeneratorKafkaTest`(`:77`), `JsonRoundTripTest`(`:133`, 단순 round-trip 픽스처 — 형식 불변이라 영향 적음). Kafka/WS 경로(범위 밖)는 식별자 형식이 그대로이므로 변경 없음.

### 4.6 `junit-platform.properties` emit

- `Generator`가 결과에 `GeneratedFile("junit-platform.properties", <내용>)`을 **1회** 포함한다(라이브러리·CLI 양쪽이 받고 `GeneratorTest`로 검증 가능). `GeneratorCli`는 기존 쓰기 루프로 `out/junit-platform.properties`에 기록한다.
- 내용:
  ```
  junit.jupiter.execution.parallel.enabled=true
  junit.jupiter.execution.parallel.mode.default=concurrent
  junit.jupiter.execution.parallel.mode.classes.default=concurrent
  junit.jupiter.execution.parallel.config.strategy=dynamic
  junit.jupiter.execution.parallel.config.dynamic.factor=1
  ```
- 소비자는 이 파일을 자기 프로젝트의 **`src/test/resources/` 루트**에 배치한다(문서에 명시).
- **기존 파일 보호:** `GeneratorCli`가 `out/junit-platform.properties`를 쓸 때 동일 경로에 **다른 내용**의 파일이 이미 있으면 덮어쓰되 **경고를 로그**한다. README 안내문: "이미 `junit-platform.properties`가 있으면 통째로 교체하지 말고 위 5개 property를 병합하라."
- emit은 생성된 테스트 파일이 1개라도 있을 때 항상 포함. 파일이 0개(예: 폼 엔드포인트 차단)면 포함하지 않는다.

## 5. 데이터 흐름

```
generate(request)                        [HTTP 경로]
  └─ for path in pathsForEndpoint(endpointId):
        skip negative-auth/negative-validation
        ScenarioMethod m = buildScenarioMethod(endpoint, request, path)   # 렌더 없음
        (m.serial ? serialMethods : parallelMethods).add(m)
  └─ if parallelMethods nonempty:
        files += renderTestClass(testClassName,         parallelMethods, classSerial=false)
        fullyParallel += testClassName
  └─ if serialMethods nonempty:
        files += renderTestClass(testClassName+"Serial", serialMethods, classSerial=true)
        serialRequired += SerialRequired(testClassName+"Serial", "SUT_PROPAGATION_MISSING", …)
  └─ if files nonempty: files += junitPlatformProperties()        # 1회
  └─ return GenerationResult(files, warnings, ParallelSafetyReport(fullyParallel, serialRequired))
```

런타임(생성된 테스트, 병렬):
```
병합 클래스(병렬)          직렬 클래스(SAME_THREAD)
메소드 A 인스턴스          메소드 S 인스턴스        (PER_METHOD)
  @BeforeEach: scopeA        @BeforeEach: scopeS
  INSERT(A)+deferDelete      INSERT(S)+deferDelete
  baggage 필터 스텁          미전파 스텁 (단일 스레드 고정 → 다른 직렬과 순차)
  요청/단언                  요청/단언
  @AfterEach: scopeA.cleanup() → A 리스트만 FIFO 삭제
                             @AfterEach: scopeS.cleanup() → S 리스트만 FIFO 삭제
  (A는 풀 스레드, S는 root 스레드 — baggage 특이성으로 스텁 충돌 없음)
```

## 6. 테스트 전략 (double-loop TDD)

### 6.1 E2E / 수용 기준 (outer loop — 먼저 작성, 처음엔 red)

(fixture-graph의 `post-api-orders` path 4개: happy / 404 / express-201-2(병렬) / express-201-3(propagation-missing=직렬))

- **AC1 (구조):** `generate(post-api-orders, pathId=null)` → 파일 결과에 **`OrdersPostTest.java`**(@Test 3개: `happy`, `s404_1`, `s201_2`) + **`OrdersPostTestSerial.java`**(@Test 1개: `s201_3`) + **`junit-platform.properties`**. 골든 파일 교체.
- **AC2 (직렬 분리):** `OrdersPostTestSerial`만 **클래스레벨** `@Execution(ExecutionMode.SAME_THREAD)` + Execution import. `OrdersPostTest`에는 `@Execution`도 import도 **없음**. 어떤 메소드에도 메소드레벨 `@Execution` 없음.
- **AC3 (병렬 설정 emit):** `result.files()`에 `junit-platform.properties` 포함, `strategy=dynamic`·`factor=1` 등 내용 일치.
- **AC4 (리포트):** `parallelSafety().fullyParallel()` = `["OrdersPostTest"]`, `serialRequired()`는 `OrdersPostTestSerial`/`SUT_PROPAGATION_MISSING` 1개.
- **AC5 (단건 후방호환):** `pathId` 지정 시 `request.testClassName()` 클래스 1개(@Test 1개) + `junit-platform.properties`. 그 path가 직렬이면 클래스레벨 SAME_THREAD.
- **AC6 (e2e green):** `e2e/run-e2e.sh`가 emit된 `junit-platform.properties`를 e2e 테스트 리소스로 복사한 뒤, 병합된 `OrdersPostTest`가 컴파일·**병렬 실행·green**(기존 SUT). 같은 클래스의 여러 메소드가 병렬로 돌아도 cleanup 격리 유지 확인.
- **AC7 (불변식 가드):** 생성 산출물에 `@TestInstance` 와 `static TestScope` 가 **없고**, 새 `@AfterEach`에 `scope.jdbc().update`(직접 DELETE)가 **없음**(=`scope.cleanup()` 한 줄)을 단언.

### 6.2 단위 TDD (inner loop)
- `JdbcHelper.deferDelete`/`runDeferredDeletes`:
  - **FIFO 순서:** 부모→자식 순으로 등록한 DELETE가 등록 순서대로 실행됨을 단언.
  - **best-effort 실패 격리:** **mock `JdbcAdapter`가 첫 DELETE에서 예외를 던져도** 후속 DELETE가 실행됨을 단언(실infra 불필요).
  - 빈 리스트 no-op.
- `TestScope.cleanup()`이 mock 해제 **전에** deferred delete 실행 → `TestScopeTest`.
- `buildScenarioMethod` 메소드명 도출(접두어 제거·`-`→`_`·비접두어 폴백·충돌 인덱싱) → `GeneratorTest` 단위.
- `renderTestClass` 다중 메소드 렌더(import 1회, classSerialMark 위치, methodCount 주석) → 골든/문자열 단언.
- 병렬/직렬 분할(`buildScenarioMethod.serial` 기준) → 병합 클래스 vs 직렬 클래스 파일 분리 단언.

**Done = AC1–AC7 전부 green + 단위/통합 green + 문서 갱신.**

## 7. 영향받는 파일 (예상)
- `test-generator/src/main/java/io/graphrag/generator/Generator.java` — generate HTTP 경로 재작성, `ScenarioMethod`, `buildScenarioMethod`, `renderTestClass`, `junitPlatformProperties`; `generateSingle` 제거.
- `test-generator/src/main/resources/templates/test-class.mustache` — 다중 메소드 + classSerialMark + deferDelete + mocksBlock 위치 변경.
- `test-generator/src/main/java/io/graphrag/generator/cli/GeneratorCli.java` — properties는 GeneratedFile로 자동 기록(루프 불변). 기존 파일 다른 내용이면 경고 로그 추가.
- `testlib/src/main/java/io/graphrag/testlib/api/JdbcHelper.java` — `deferDelete`/`runDeferredDeletes`(FIFO).
- `testlib/src/main/java/io/graphrag/testlib/api/TestScope.java` — `cleanup()`이 deferred delete 실행.
- `test-generator/src/test/java/io/graphrag/generator/GeneratorTest.java` + 골든 리소스 — AC1–AC5,AC7 갱신(클래스명·메소드 분할).
- `test-generator/src/test/java/io/graphrag/generator/GeneratorKafkaTest.java`(`:77`), `shared-model/src/test/java/io/graphrag/model/JsonRoundTripTest.java`(`:133`) — 영향 시 단언 갱신(식별자 형식 불변이라 경미).
- `testlib/src/test/java/io/graphrag/testlib/api/*` — deferred-cleanup 단위 테스트.
- `e2e/run-e2e.sh` — 생성 후 `$OUT/generated/junit-platform.properties` → `e2e/src/test/resources/junit-platform.properties` 복사 단계 추가(AC6).
- `e2e/src/test/resources/junit-platform.properties` — emit본(strategy=dynamic, factor=1)으로 정렬.
- 문서: README / getting-started — "junit-platform.properties를 test resources에 배치(이미 있으면 병합)", 클래스 구조 변경 반영.

## 8. 리스크 / 완화
- **R1 — 병렬 cleanup 플래키.** §4.4 불변식 + AC6/AC7 가드로 차단. PER_CLASS 금지가 핵심.
- **R2 — deferDelete 실행 순서(FK 위반).** **FIFO + 합성된 FK-역순 `deletes` 그대로 등록**으로 child-first 보장(§4.3). 단위 테스트(부모→자식 등록 → child-first 실행)로 고정.
- **R3 — 같은 클래스 다수 메소드 동시 실행 시 DB 커넥션/락 경합.** dynamic factor=1이 코어 수로 상한. 필요 시 소비자가 factor 하향. e2e(AC6)로 실측.
- **R4 — 직렬 격리 약화 우려(메소드레벨 SAME_THREAD).** Option A로 **직렬 시나리오를 병합 클래스에 넣지 않고 별도 클래스 + 클래스레벨 SAME_THREAD**로 분리 → 현행 격리 메커니즘(전역 단일 스레드 고정) 그대로, 무회귀(§4.0, §4.5).
- **R5 — 메소드명 충돌(다른 path가 같은 접미어).** 충돌 시 접미 인덱스로 해소, 단위 테스트로 고정.
- **R6 — e2e 병렬도 프로파일 변경(fixed/8 → dynamic/factor=1).** 의도된 변경: e2e가 **실제 배포되는 emit 설정**을 그대로 검증하도록 정렬(AC6). CI 머신 코어 수에 따라 병렬도가 달라지나, 테스트는 병렬-안전 전제라 정확성 영향 없음. 타이밍 변동은 dynamic factor 조정으로 흡수 가능.

## 9. 역전파 (2026-06-19) — cross-class absent-id race

R1("병렬 cleanup 플래키")의 완화책(§4.4 불변식 + AC6/AC7)은 **클래스 내** 메소드 간 cleanup 격리만 보장했고, **클래스 간** 공유 SUT-DB 상태 충돌은 포착하지 못했다. PR #62 병렬 e2e에서 `BookingsGetByIdTest.s404`(`GET /api/bookings/1` 기대 404)가 동시 실행되는 `BookingsPostTest`의 성공 POST(IDENTITY `id=1` 생성, 응답 id 미캡처로 잔류)와 race를 일으켜 간헐적으로 200을 받았다(스케줄 의존 flaky).

- **근본원인:** absent-id read가 캡처된 작은 probe id(`1`)로 '부재'를 가정 + 성공 create가 작은 IDENTITY id를 만들어 잔류 + 클래스 간 concurrent + 공유 단일 SUT/Postgres.
- **추가 요구:** REQ-016(absent-id read는 도달불가 id `2000000000` 사용 → race 결정적 제거), REQ-017(성공 create의 autoIncrement 행을 응답 id로 deferDelete 정리, 보조 위생).
- **구현:** `Generator.resolveLiteralPath`(notFoundRead 분기 + `ABSENT_NUMERIC_ID`), `Generator.postCreateCleanup` + 템플릿 `(Object) __resp.path(...)`(varargs+제네릭 CCE 회피).
- **확인:** `GeneratorAbsentIdReadTest`/`GeneratorPostCreateCleanupTest`/`GeneratorFlakyFixIntegrationTest` + e2e 54/54 green + PR #62 CI 전 체크 green.
- **R1 보강:** 병렬-안전 불변식에 "absent-id 가정 negative read는 도달불가 id를 쓴다"와 "성공 create는 자기 생성 행을 정리한다"를 포함한다.
