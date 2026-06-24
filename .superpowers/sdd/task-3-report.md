# Task 3 구현 보고서: ResponseStringLiteralExtractor

## 커밋 해시

`2e5d1a4` (feat-stage2-string-literal-fuzzing 브랜치)

---

## 변경 파일 목록

| 파일 | 변경 유형 | 설명 |
|------|-----------|------|
| `graph-rag-builder/src/main/java/io/graphrag/builder/index/ResponseStringLiteralExtractor.java` | 신규 생성 | 핵심 추출기 구현 |
| `graph-rag-builder/src/test/java/io/graphrag/builder/index/ResponseStringLiteralExtractorTest.java` | 신규 생성 | 6개 단위 테스트 |
| `graph-rag-builder/src/test/resources/sample-src/io/graphrag/sample/orders/OrderController.java` | 수정 | 픽스처 패턴 5종 추가 |

---

## sample-src 픽스처 추가 내역

`OrderController.java`에 `InventoryClient.InventoryResponse`의 `region` 필드 대상으로 아래 패턴을 추가했다.

| 패턴 | 코드 예시 | 기대 추출값 |
|------|-----------|------------|
| 기존 equals(리터럴.equals(accessor)) | `"EMBARGOED".equals(stock.region())` | `"EMBARGOED"` |
| equalsIgnoreCase | `stock.region().equalsIgnoreCase("X1")` | `"X1"` |
| Objects.equals | `Objects.equals(stock.region(), "X2")` | `"X2"` |
| 로컬 변수 바인딩 | `String r = stock.region(); "X3".equals(r);` | `"X3"` |
| static final String 상수 | `CONST_REGION.equals(stock.region())` (`CONST_REGION = "X4"`) | `"X4"` |
| 비동치(loud skip) | `stock.region().startsWith("EMBARGO")` | 미포함, loud-log |

`OrderController` 클래스에 `private static final String CONST_REGION = "X4"` 선언 추가.

---

## 확정된 테스트 케이스와 기대 리스트

### 테스트 1: `extractsRegionEqualsLiteral`
- shape: `InventoryResponse(available:int, mode:FulfillmentMode, region:String)`
- 단언: `out.get(dtoFqn).get("region")` contains `"EMBARGOED"`

### 테스트 2: `extractsAllEqualsFamilyPatternsAndLocalBindingAndConst`
- 5개 패턴 모두 포함
- 단언: `containsExactly("EMBARGOED", "X1", "X2", "X3", "X4")` (TreeSet 알파벳 정렬)

### 테스트 3: `startWithsIsNotIncludedInResult`
- 단언: `doesNotContain("EMBARGO")`

### 테스트 4: `nonequalityLoudLogFiredForStartsWith`
- JUL CapturingHandler로 로그 캡처
- 단언: `string-literal-nonequality-skipped` + `startsWith` 포함 메시지 존재

### 테스트 5: `ambiguousFieldAcrossTwoDtosIsSkipped`
- InventoryResponse(region:String) + SomeOtherResponse(region:String) 두 callSite
- 단언: 어느 DTO 버킷에도 region 없음 + `string-literal-accessor-ambiguous` loud-log

### 테스트 6: `emptyResponseShapeProducesNoResults`
- `Optional.empty()` responseShape callSite
- 단언: `out.isEmpty()`

---

## 테스트 커맨드 및 결과

```
./gradlew :graph-rag-builder:test \
  --tests "io.graphrag.builder.index.ResponseStringLiteralExtractorTest" \
  --tests "io.graphrag.builder.index.SpoonExpressionRefsTest" \
  --rerun-tasks
```

```
BUILD SUCCESSFUL in 4s
14 actionable tasks: 4 executed, 10 up-to-date
```

XML 결과:
```xml
<testsuite name="io.graphrag.builder.index.ResponseStringLiteralExtractorTest"
           tests="6" skipped="0" failures="0" errors="0" time="1.66">
  <testcase name="extractsRegionEqualsLiteral()" time="1.212"/>
  <testcase name="extractsAllEqualsFamilyPatternsAndLocalBindingAndConst()" time="0.12"/>
  <testcase name="ambiguousFieldAcrossTwoDtosIsSkipped()" time="0.094"/>
  <testcase name="startWithsIsNotIncludedInResult()" time="0.082"/>
  <testcase name="nonequalityLoudLogFiredForStartsWith()" time="0.078"/>
  <testcase name="emptyResponseShapeProducesNoResults()" time="0.071"/>
</testsuite>
<testsuite name="io.graphrag.builder.index.SpoonExpressionRefsTest"
           tests="1" skipped="0" failures="0" errors="0" time="0.073">
  <testcase name="recordAccessorAndLiteral()" time="0.073"/>
</testsuite>
```

총 7 tests, 0 failures, 0 errors.

---

## 구현 주요 결정 사항

1. **Objects.equals 양방향**: 브리프 코드에 `fieldLit(arg0, arg1)` 단방향만 있었는데, `fieldLit(arg1, arg0)` 폴백도 추가해 `Objects.equals("X", resp.field())` 역방향도 처리한다.

2. **nonequality loud-skip 구현**: 브리프 코드에는 없었던 로직 — `equals` 패밀리가 아닌 메서드 호출에서 target이 응답 DTO String 필드 접근자이면 `string-literal-nonequality-skipped`를 로그한다. `allResponseStringFields` Set으로 O(1) 판정.

3. **startsWith 픽스처**: sample-src에 `stock.region().startsWith("EMBARGO")` 추가. 이 패턴의 테스트가 인수("EMBARGO") 결과 미포함 + loud-log 발생 두 가지를 단언.

4. **static final 상수 해석**: 동일 소스트리의 `CtField` 에서 `FINAL+STATIC+CtLiteral<String>` 조건으로 인덱스 구성. `CONST_REGION.equals(stock.region())` 에서 `CtFieldRead`로 상수 참조가 들어오면 인덱스에서 `"X4"` 값 해석.

---

## 우려 사항

- **no-classpath 환경 제약**: `CtLocalVariable.getDeclaration()`이 반환하는 객체가 no-classpath 모드에서 항상 작동하는지 Spoon 버전 의존적. 현재 테스트에서는 정상 동작 확인.
- **concurrent Gradle**: 병렬 task 실행 환경에서 "Could not write XML test results" 오류가 드물게 발생할 수 있다(다른 worktree에서 동시 빌드 시). 이번 실행에서는 발생하지 않음.
- **Objects.equals 탐지 fragility**: `CtTypeAccess` target의 `getSimpleName()`이 `"Objects"`임을 보고 판정. `java.util.Objects`를 alias로 쓰는 경우(rare)엔 놓칠 수 있다.

---

## 리뷰 수정 보고 (I1/I2 — 코드 리뷰 반영)

### FIX I1: stringConstants 맵 결정성 (Important)

**변경 내용**: `stringConstants` 맵 선언을 `new HashMap<>()` → `new TreeMap<>()` 변경.

**이유**: no-classpath 환경에서 동일 simple-name을 가진 `static final String` 상수가 서로 다른 클래스에 존재할 경우, `HashMap` 은 `put` 순서 및 이후 `get` 결과가 JVM 실행마다 달라질 수 있다. `TreeMap`은 키를 알파벳 순으로 고정해 결정적 동작을 보장한다.

**trade-off**: `CtFieldRead`는 no-classpath 모드에서 선언 타입을 신뢰할 수 없어 FQN 키로 변경하면 기존 `CONST_REGION` 조회가 깨진다. `TreeMap`을 쓰되 simple-name 키를 유지하는 방식이 안전·최소 변경이라 택했다. 동명 상수가 충돌하면 알파벳 순으로 마지막에 `put`된 값이 이기지만, 그 결과는 실행마다 동일하다(HashMap은 그조차 보장 없음).

**불필요해진 import 제거**: `java.util.HashMap`은 더 이상 사용되지 않으므로 import에서 삭제.

**커버링 테스트 결과**:
```
./gradlew :graph-rag-builder:test --tests ResponseStringLiteralExtractorTest
BUILD SUCCESSFUL in 3s
```
`region → ["EMBARGOED","X1","X2","X3","X4"]` 단언 포함 6 tests, 0 failures.

---

### FIX I2: 중복 equals 가드 제거 (Minor)

**변경 내용**: 비동치 loud-skip 조건에서 `&& !"equals".equals(simple)` 제거.

**이유**: `EQUALITY_METHODS = Set.of("equals", "equalsIgnoreCase")`이므로 `!EQUALITY_METHODS.contains(simple)`가 `!"equals".equals(simple)` 를 이미 포함한다. 뒤의 조건은 항상 참이므로 데드코드.

**안전성 확인**: `EQUALITY_METHODS`에 `"equals"`가 포함돼 있으므로 제거해도 동작이 변하지 않음.

---

### 교차 태스크 영향 조사 (sample-src OrderController 변경 → 기존 단위 테스트)

Task 3이 `graph-rag-builder/src/test/resources/sample-src/io/graphrag/sample/orders/OrderController.java`에 6개 패턴(CONST_REGION 필드, equalsIgnoreCase/Objects.equals/로컬 바인딩/startsWith)을 추가했다. 이 파일을 파싱하는 기존 단위 테스트가 OrderController의 상수·분기 수·라인 등을 하드코딩하면 깨질 수 있다.

**실행한 테스트**:
```
./gradlew :graph-rag-builder:test \
  --tests 'ConstraintExtractor*' \
  --tests 'ResponseStringLiteralExtractorTest' \
  --tests 'ResponseDtoIndexer*' \
  --tests 'EndpointIndexer*'
BUILD SUCCESSFUL in 5s
14 actionable tasks: 1 executed, 13 up-to-date
```

전체 0 failures. 주요 판단:

- **ConstraintExtractorTest**: SQL 제약 추출을 테스트하며 OrderController 분기 수 하드코딩 없음. 신규 `String` 패턴이 SQL 쿼리 제약과 무관하므로 영향 없음.
- **ResponseDtoIndexerTest**: DTO 필드 형상(responseShape) 인덱싱을 테스트. `InventoryResponse` 필드 자체(region:String 등)는 변경 없음 — 영향 없음.
- **EndpointIndexerTest**: 엔드포인트 경로·HTTP 메서드·파라미터 인덱싱. 신규 패턴은 메서드 바디 내부 로직이므로 엔드포인트 시그니처 변경 없음 — 영향 없음.
- **Stage2EnumResponseFuzzingE2E.SWITCH_LINE=58**: 그대로 유지. Task 3이 수정한 것은 Spoon 픽스처용 sample-src OrderController이고, E2E의 `sut.jar` SUT는 `:samples:order-service`의 별도 OrderController(line 58에 `switch (stock.mode())` 위치 변경 없음). 리뷰어 지적(58→79)은 false positive.

**결론**: 교차 태스크 영향으로 깨지는 테스트 없음.
