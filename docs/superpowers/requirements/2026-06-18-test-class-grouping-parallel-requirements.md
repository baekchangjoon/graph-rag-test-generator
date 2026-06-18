# 엔드포인트 단위 테스트클래스 그룹화 + 병렬 실행 요구사항명세
> 출처(design spec): docs/superpowers/specs/2026-06-18-test-class-grouping-parallel-design.md
> 완료 정의(DoD): 커버리지 대상(Must + 미연기 Should) 요구사항이 모두 ≥1개의 통과 수용 테스트를 가짐 (대상 매트릭스 전부 🟢)

검증 레벨 표기:
- **Gen black-box**: 생성기 산출물(`GenerationResult`/생성 파일 문자열)을 검증하는 `GeneratorTest`/골든 — 라이브러리에서 가장 높은 실현 가능 out-of-process 레벨.
- **E2E**: `e2e/run-e2e.sh` — 생성 테스트를 실제 SUT에 컴파일·병렬 실행.
- **Unit**: testlib 단위 테스트(`JdbcHelperTest`/`TestScopeTest`).

## 요구사항 목록

### REQ-001 — 엔드포인트의 병렬-안전 시나리오를 한 클래스 다중 @Test로 병합
- 유형: Functional
- 우선순위: Must
- 설명: HTTP 엔드포인트(`pathId` 미지정)의 테스트 대상 path 중 병렬-안전 시나리오들을 `request.testClassName()` 클래스 1개에 평탄 `@Test` 메소드들로 묶는다. negative-auth/negative-validation path는 제외.
- 수용기준:
  - Given fixture-graph의 `post-api-orders`(병렬 path: happy/404/express-201-2), When `generate(endpointId=post-api-orders, pathId=null)`, Then 파일에 `OrdersPostTest.java` 1개가 있고 그 안에 `@Test void happy()`, `s404_1()`, `s201_2()` 3개가 존재한다(골든 일치).
- 검증 레벨: Gen black-box

### REQ-002 — propagation-missing(직렬) 시나리오를 별도 Serial 클래스로 분리 + 클래스레벨 SAME_THREAD
- 유형: Functional
- 우선순위: Must
- 설명: `mocks.propagationMissing()==true`인 시나리오는 병합 클래스에 넣지 않고 `{testClassName}Serial` 클래스에 모아 클래스레벨 `@Execution(SAME_THREAD)`로 표시한다.
- 수용기준:
  - Given `post-api-orders`(직렬 path: express-201-3), When `generate(pathId=null)`, Then `OrdersPostTestSerial.java` 1개에 `@Test void s201_3()`가 있고, 클래스 선언 위에 `@Execution(ExecutionMode.SAME_THREAD)` 와 Execution/ExecutionMode import가 존재한다.
- 검증 레벨: Gen black-box

### REQ-003 — 병합(병렬) 클래스에는 @Execution과 그 import가 없고, 어떤 메소드에도 메소드레벨 @Execution이 없음
- 유형: Functional
- 우선순위: Must
- 설명: Option A에 따라 메소드레벨 SAME_THREAD는 발행하지 않는다. 병렬 클래스는 동시 실행 기본값에만 의존한다.
- 수용기준:
  - Given REQ-001의 `OrdersPostTest.java`, When 내용 검사, Then `@Execution` 문자열과 `import org.junit.jupiter.api.parallel.Execution` 이 모두 없다.
  - Given 임의의 생성 클래스, When 내용 검사, Then `@Test` 직전/직후 메소드레벨 `@Execution` 이 없다.
- 검증 레벨: Gen black-box

### REQ-004 — junit-platform.properties를 산출물에 1회 emit (strategy=dynamic, factor=1)
- 유형: Functional
- 우선순위: Must
- 설명: 생성 테스트 파일이 1개 이상일 때 결과에 `junit-platform.properties` 파일 1개를 포함한다. 0개면 포함하지 않는다.
- 수용기준:
  - Given REQ-001 생성, When `result.files()` 검사, Then `relativePath=="junit-platform.properties"` 파일이 정확히 1개 있고 내용에 `parallel.enabled=true`, `mode.default=concurrent`, `mode.classes.default=concurrent`, `config.strategy=dynamic`, `config.dynamic.factor=1` 이 모두 포함된다.
  - Given 폼 엔드포인트(생성 0건), When `generate`, Then `junit-platform.properties` 가 결과에 없다.
- 검증 레벨: Gen black-box

### REQ-005 — 기존 junit-platform.properties가 다른 내용이면 덮어쓰되 경고 로그
- 유형: Functional
- 우선순위: Should
- 설명: `GeneratorCli`가 `out/junit-platform.properties`를 쓸 때 동일 경로에 다른 내용의 파일이 이미 있으면 덮어쓰되 경고를 로그한다.
- 수용기준:
  - Given `out`에 다른 내용의 `junit-platform.properties`가 존재, When CLI generate 실행, Then 파일은 emit 내용으로 갱신되고 경고 로그(기존 파일 교체 안내)가 1회 출력된다.
- 검증 레벨: Gen black-box (CLI)

### REQ-006 — ParallelSafetyReport는 클래스레벨 식별자로 병렬/직렬 클래스를 보고
- 유형: Functional
- 우선순위: Must
- 설명: `fullyParallel`은 병렬 클래스명, `serialRequired`는 직렬 클래스 1개당 1개(`reason=SUT_PROPAGATION_MISSING`). 식별자 형식은 클래스명 그대로(모델 변경 없음).
- 수용기준:
  - Given REQ-001/002 생성, When `result.parallelSafety()` 검사, Then `fullyParallel()==["OrdersPostTest"]` 이고 `serialRequired()` 가 `test=="OrdersPostTestSerial"`, `reason=="SUT_PROPAGATION_MISSING"` 인 항목 1개다.
- 검증 레벨: Gen black-box

### REQ-007 — 단건(pathId) 생성 후방호환
- 유형: Functional
- 우선순위: Must
- 설명: `pathId` 지정 시 그 path 1개만 `request.testClassName()` 클래스(@Test 1개)로 생성하고, 직렬 path면 클래스레벨 SAME_THREAD. 산출물에 `junit-platform.properties` 포함.
- 수용기준:
  - Given `generate(pathId=post-api-orders-happy, testClassName=OrdersPostTest)`, When 생성, Then `OrdersPostTest.java` 1개(@Test 1개) + `junit-platform.properties` 가 나온다.
  - Given `pathId`가 직렬 path(express-201-3), When 생성, Then 그 클래스에 클래스레벨 `@Execution(SAME_THREAD)` 가 있다.
- 검증 레벨: Gen black-box

### REQ-008 — deferred-cleanup DELETE를 등록 순서(FIFO)대로 실행
- 유형: Functional
- 우선순위: Must
- 설명: `JdbcHelper.deferDelete(sql,args)`는 등록 순서를 보존하고, `runDeferredDeletes()`는 그 순서(FIFO)대로 실행한다. 합성된 `deletes`(FK 역순=child-first)를 그대로 등록하면 child→parent 삭제가 된다.
- 수용기준:
  - Given parent then child 순으로 deferDelete 2건 등록, When `runDeferredDeletes()`, Then 등록 순서(parent, child)대로 어댑터 호출이 일어난다(= child-first 보장은 호출자가 FK-역순으로 등록함으로써 달성).
- 검증 레벨: Unit

### REQ-009 — deferred-cleanup 실패 격리(best-effort)
- 유형: Non-functional (robustness)
- 우선순위: Must
- 설명: 한 DELETE가 실패해도 나머지 DELETE는 실행되고 cleanup이 테스트를 실패시키지 않는다.
- 수용기준:
  - Given mock `JdbcAdapter`가 첫 DELETE에서 예외를 던지도록 구성, When `runDeferredDeletes()`, Then 후속 DELETE가 실행되고 예외가 밖으로 전파되지 않는다.
- 검증 레벨: Unit

### REQ-010 — TestScope.cleanup()이 mock/연결 해제 전에 deferred delete 실행
- 유형: Functional
- 우선순위: Must
- 설명: `cleanup()`은 `http.removeAllForScope`/`jdbc.close` 등 해제 이전에 `jdbc.runDeferredDeletes()`를 호출한다.
- 수용기준:
  - Given deferDelete 등록 + spy/mock 어댑터, When `scope.cleanup()`, Then deferred delete 실행이 jdbc.close()/mock 제거보다 먼저 일어난다.
- 검증 레벨: Unit

### REQ-011 — 생성 @AfterEach는 scope.cleanup() 한 줄(직접 DELETE 없음)
- 유형: Functional
- 우선순위: Must
- 설명: 새 템플릿의 `@AfterEach`는 `scope.cleanup()`만 호출하고 `scope.jdbc().update("DELETE …")` 직접 호출은 없다(이중 삭제 방지).
- 수용기준:
  - Given 임의 생성 클래스, When `@AfterEach` 본문 검사, Then `scope.cleanup();` 만 있고 `scope.jdbc().update` 형태의 DELETE 직접 호출이 없다.
- 검증 레벨: Gen black-box

### REQ-012 — 불변식 가드: 산출물에 @TestInstance / static TestScope 부재
- 유형: Non-functional (parallel-safety invariant)
- 우선순위: Must
- 설명: 메소드별 격리(PER_METHOD)를 위해 생성 클래스는 `@TestInstance(PER_CLASS)`와 `static TestScope`를 발행하지 않는다.
- 수용기준:
  - Given 임의 생성 클래스, When 내용 검사, Then `@TestInstance` 문자열과 `static TestScope` 선언이 모두 없다.
- 검증 레벨: Gen black-box

### REQ-013 — 병렬 실행 + 메소드 간 cleanup 격리 e2e green
- 유형: Non-functional (correctness under parallelism)
- 우선순위: Must
- 설명: emit된 `junit-platform.properties`로 병렬 실행 시, 병합 클래스의 다수 메소드가 동시에 돌아도 컴파일·통과하며 cleanup이 메소드 간 간섭하지 않는다.
- 수용기준:
  - Given `e2e/run-e2e.sh`가 emit된 properties를 e2e 테스트 리소스로 복사, When SUT에 대해 e2e 실행, Then 병합된 `OrdersPostTest`가 병렬로 실행되어 전부 통과(green)한다.
- 검증 레벨: E2E

### REQ-014 — methodName 도출 규칙
- 유형: Functional
- 우선순위: Must
- 설명: `path.id()`에서 `endpointId + "-"` 접두어 제거 후 `-`→`_`; 접두어로 시작 안 하면 전체 id 사용; 동일 클래스 내 충돌 시 접미 인덱스(`_2`…).
- 수용기준:
  - Given path id `post-api-orders-s201-3`, endpoint `post-api-orders`, When methodName 도출, Then `s201_3`.
  - Given 접두어로 시작하지 않는 path id, When 도출, Then 전체 id 기반 식별자(유효한 Java 식별자)로 폴백한다.
  - Given 같은 클래스에서 동일 접미어를 내는 두 path, When 도출, Then 두 번째는 `_2` 접미로 충돌 회피.
- 검증 레벨: Gen black-box (Unit 수준 단언)

### REQ-015 — WS/Kafka 생성 경로는 이번 범위에서 변경 없음
- 유형: Functional
- 우선순위: Won't (this round)
- 설명: WS(STOMP)/Kafka 경로는 현 "exchange당 클래스" 동작을 유지한다(후속 확장 대상).
- 수용기준:
  - Given 기존 `GeneratorKafkaTest`/WS 관련 테스트, When 본 변경 후 실행, Then 식별자 형식 변경 없이 통과(회귀 없음).
- 검증 레벨: Gen black-box

## 추적 매트릭스

| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| REQ-001 | 병렬 시나리오 병합 클래스 | `GeneratorTest#endpointMergesParallelScenariosIntoOneClass` (+golden) | Gen | 🔴 planned |
| REQ-002 | 직렬 Serial 클래스 분리 | `GeneratorTest#propagationMissingScenarioGoesToSerialClass` | Gen | 🔴 planned |
| REQ-003 | 병렬 클래스 @Execution 부재 | `GeneratorTest#parallelClassHasNoExecutionAnnotation` | Gen | 🔴 planned |
| REQ-004 | junit-platform.properties emit | `GeneratorTest#emitsJunitPlatformPropertiesDynamic` | Gen | 🔴 planned |
| REQ-005 | 기존 properties 경고 | `GeneratorCliTest#warnsWhenOverwritingDifferentProperties` | Gen | 🔴 planned |
| REQ-006 | ParallelSafetyReport 클래스레벨 | `GeneratorTest#reportsParallelAndSerialClasses` | Gen | 🔴 planned |
| REQ-007 | 단건 후방호환 | `GeneratorTest#singlePathBackwardCompatible` | Gen | 🔴 planned |
| REQ-008 | deferDelete FIFO | `JdbcHelperTest#runsDeferredDeletesInRegistrationOrder` | Unit | 🔴 planned |
| REQ-009 | deferDelete 실패 격리 | `JdbcHelperTest#deferredDeleteFailureIsBestEffort` | Unit | 🔴 planned |
| REQ-010 | cleanup이 delete 먼저 | `TestScopeTest#cleanupRunsDeferredDeletesBeforeTeardown` | Unit | 🔴 planned |
| REQ-011 | @AfterEach 직접 DELETE 없음 | `GeneratorTest#afterEachOnlyCallsScopeCleanup` | Gen | 🔴 planned |
| REQ-012 | 불변식 가드 | `GeneratorTest#noPerClassInstanceOrStaticScope` | Gen | 🔴 planned |
| REQ-013 | 병렬+격리 e2e green | `e2e/run-e2e.sh` (parallel run green) | E2E | 🔴 planned |
| REQ-014 | methodName 도출 | `GeneratorTest#methodNameDerivationRules` | Gen | 🔴 planned |
| REQ-015 | WS/Kafka 불변 | `GeneratorKafkaTest` (regression) | Gen | 🔵 out-of-scope |

Coverage: 0/14 green (0%) — target 100% (대상: Must 13 + Should 1[REQ-005]). Won't/out-of-scope: REQ-015(🔵, 회귀만 확인).
