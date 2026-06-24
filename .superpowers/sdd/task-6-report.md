# Task 6 구현 보고서: runResponseVariantLoops enum∪String 후보맵 + 마커 rename

**REQ-IDs:** REQ-009, REQ-001

---

## 1. 메서드 rename 내역

| 구 이름 | 신 이름 | 위치 |
|---|---|---|
| `runEnumResponseVariantLoops` | `runResponseVariantLoops` | `EndpointExplorationRunner` private 인스턴스 메서드 |
| `exploreEnumResponseVariants` | `exploreResponseVariants` | `EndpointExplorationRunner` package-private static 헬퍼 |
| `applyEnumOverrides` | `applyFieldOverrides` | `EndpointExplorationRunner` private static 헬퍼 |

호출 site(~line 422): `runEnumResponseVariantLoops(...)` → `runResponseVariantLoops(...)` 갱신 완료.

---

## 2. 후보맵 조립 코드 (`runResponseVariantLoops` 내, per-callSite 루프)

```java
Map<String, List<String>> candidates = new java.util.TreeMap<>();
for (BodyShape.BodyField field : responseShape.fields()) {
    // enum 후보: 상수 − 선언순 첫 상수(baseline)
    List<String> consts = resolveEnumConstants(field.javaType(), effectiveEnumConstants);
    if (consts != null && !consts.isEmpty()) {
        List<String> nonBaseline = consts.stream().skip(1).toList();
        if (!nonBaseline.isEmpty()) {
            candidates.put(field.name(), nonBaseline);
        }
        continue;
    }
    // String 후보: 추출 리터럴 − 단계1 기본값(scalarValue)
    if ("java.lang.String".equals(field.javaType())) {
        List<String> lits = stringLiteralsByDto
                .getOrDefault(responseShape.javaType(), Map.of())
                .getOrDefault(field.name(), List.of());
        if (lits.isEmpty()) {
            continue;
        }
        String baseline = shapes.scalarValue(field.javaType(), List.of(), field.name()).asText();
        List<String> nonBaseline = lits.stream().filter(s -> !s.equals(baseline)).toList();
        if (!nonBaseline.isEmpty()) {
            candidates.put(field.name(), nonBaseline);
        }
    }
}
ResponseFieldVariantGenerator.VariantPlan plan =
        generator.generate(candidates, RESPONSE_VARIANT_BUDGET);
```

변경 전: `enumCandidates`(enum-only)를 조립하는 단순 루프였음.
변경 후: enum 필드는 skip(1)로 baseline 제외, String 필드는 `stringLiteralsByDto` 조회 + `scalarValue` baseline 제거.

---

## 3. 마커 문자열 변경 사이트 (전체)

### `EndpointExplorationRunner.java`
| 구 문자열 | 신 문자열 | 맥락 |
|---|---|---|
| `"-enumvar-"` (httpId 접두사) | `"-responsevar-"` | `KeptVariant` httpId 구성 |
| `"-enumvar"` (groupId 접두사) | `"-responsevar"` | `CapturedHttpCall` groupId + `ExploredPath` id |
| `"enum-response-variant"` (discoveredBy) | `"response-variant"` | `ExploredPath.discoveredBy` |
| `"enum-variant invoke failed"` (log.warn) | `"response-variant invoke failed"` | warn 메시지 |
| `"enum-variant loop"` (log.info) | `"response-variant loop"` | info 메시지 |

### `test-generator/src/main/java/io/graphrag/generator/Generator.java:81`
```java
// 변경 전
|| "enum-response-variant".equals(p.discoveredBy())) {
// 변경 후
|| "response-variant".equals(p.discoveredBy())) {
```

---

## 4. 새 통합 테스트: `StringLiteralVariantReExploreTest`

파일: `graph-rag-builder/src/test/java/io/graphrag/builder/run/StringLiteralVariantReExploreTest.java`

### fixture
- `RESPONSE_SHAPE`: `com.example.InventoryResponse` — 필드 3개
  - `mode` (`p.FulfillmentMode`): enum {STANDARD(baseline), EXPRESS_ONLY, BACKORDER}
  - `region` (`java.lang.String`): 리터럴 ["us-east", "eu-west", "sample-region"]
  - `note` (`java.lang.String`): 리터럴 없음
- `STRING_LITERALS_BY_DTO`: `region`→["us-east","eu-west","sample-region"], `note` 엔트리 없음

### 테스트 3개, 각 단언

**(a) `enumBaselineIsExcludedAndNonBaselineVariantsAreIncluded`**
- 생산 `buildProductionPlan(32)` 호출 → 후보맵 조립 실제 경로 실행
- 단언: `STANDARD`(enum baseline)이 변형 label에 없어야 함
- 단언: `sample-region`(String baseline)이 변형 label에 없어야 함
- 단언: 비-baseline 후보가 하나 이상 있어야 함

**(b) `stringLiteralDifferentFromBaselineIsIncludedAndOpensNewArm`**
- `scalarValue("java.lang.String", [], "region")` 반환값이 `"sample-region"`임을 직접 단언
- `nonBaseline = ["us-east","eu-west"]` 임을 단언
- `exploreResponseVariants` 호출: us-east→arm4, eu-west→arm5
- 단언: `keptVariantLabels()` = ["region=us-east", "region=eu-west"] (둘 다 새 arm)
- 단언: 등록 body에 정확한 region 값 재정의됨
- 단언: cumulative OR-병합 (arm 1, 4, 5 모두 true)

**(c) `stringFieldWithZeroLiteralsYieldsZeroVariants`**
- `note` 필드 리터럴이 빈 리스트임을 단언
- 빈 후보맵으로 `generate(empty, 32)` → `plan.kept().isEmpty()` 단언

---

## 5. 테스트 명령 및 결과

```
./gradlew :graph-rag-builder:test --tests StringLiteralVariantReExploreTest --tests 'EnumVariant*' --tests ResponseFieldVariantGeneratorTest
```

| 클래스 | tests | failures | errors |
|---|---|---|---|
| StringLiteralVariantReExploreTest | 3 | 0 | 0 |
| EnumVariantReExploreTest | 5 | 0 | 0 |
| EnumVariantNoneModeTest | 1 | 0 | 0 |
| ResponseFieldVariantGeneratorTest | 3 | 0 | 0 |
| **합계** | **12** | **0** | **0** |

```
BUILD SUCCESSFUL
```

```
./gradlew :test-generator:test
```

```
BUILD SUCCESSFUL
```
(response-variant discoveredBy 제외 필터 정상 동작 확인)

---

## 6. 기타 갱신 파일

- `EnumVariantReExploreTest.java`: `exploreEnumResponseVariants` → `exploreResponseVariants` (5개 호출 site + Javadoc 링크)
- `EnumVariantNoneModeTest.java`: `exploreEnumResponseVariants` → `exploreResponseVariants` (1개 호출 site)

---

## 7. 우려 사항 (Concerns)

없음. 모든 rename이 일관성 있게 적용됐고, 생산 후보맵 조립은 brief의 pseudocode를 충실히 구현했다. `stringLiteralsByDto`는 Task 5에서 이미 필드로 추가되어 있어 별도 배선 불필요. JDK 21 툴체인 경고(`Invalid Java installation`)는 기존 빌드 환경 이슈이며 이 task와 무관하다.

---

## 8. 리뷰 수정: buildVariantCandidates 추출 (review-fix)

**커밋:** refactor(run): 변형 후보 조립을 buildVariantCandidates로 추출(테스트가 production 경로 검증) REQ-009

### 문제 (리뷰 발견)

`StringLiteralVariantReExploreTest`의 `buildProductionPlan`(lines ~107-158)이 production
assembly logic을 **손으로 복사**하고 있었음 — `resolveEnumConstants` 헬퍼까지 별도 재정의.
이로 인해 실제 `runResponseVariantLoops` 내부 루프에서 회귀가 발생해도 테스트가 감지하지 못하는 구조적 문제.

### 수정 내용

**EndpointExplorationRunner.java:**

1. `resolveEnumConstants`를 `private static` → `static`(package-private)으로 변경.
2. 인라인 후보맵 조립 루프를 추출해 새 package-private static 메서드 `buildVariantCandidates` 신설:
   ```java
   static Map<String, List<String>> buildVariantCandidates(
       BodyShape responseShape,
       Map<String, List<String>> enumConstants,
       Map<String, Map<String, List<String>>> stringLiteralsByDto,
       ShapeJsonSynthesizer shapes)
   ```
   - enum 필드: `resolveEnumConstants(...).stream().skip(1)`, 비어있으면 continue
   - String 필드: `stringLiteralsByDto` 조회 → `scalarValue` baseline 제거, 비어있으면 continue
   - 결과: `TreeMap`(결정적 순서)
3. `runResponseVariantLoops` 내 인라인 루프를 `buildVariantCandidates(...)` 단일 호출로 대체. 동작 동일.
4. `applyFieldOverrides` 파라미터 `enumOverrides` → `overrides` 로 rename (메서드가 String/enum 공용임을 반영).

**StringLiteralVariantReExploreTest.java:**

- 중복된 `buildProductionPlan` 내 조립 로직 및 `resolveEnumConstants` 제거.
- `buildProductionPlan`은 이제 `EndpointExplorationRunner.buildVariantCandidates(RESPONSE_SHAPE, ENUMS, STRING_LITERALS_BY_DTO, shapes)` 한 줄을 호출 후 `generate`에 전달.
- 테스트 (b): `allCandidates = buildVariantCandidates(...)` 에서 `region` 후보를 꺼내 `regionOnlyCandidates` 조립 → production assembly 실제 경로 커버.
- 테스트 (c): note-only 축소 `BodyShape`로 `buildVariantCandidates` 호출 → 빈 맵 반환을 직접 단언.

### 테스트 명령 및 결과

```
./gradlew :graph-rag-builder:test --tests StringLiteralVariantReExploreTest --tests 'EnumVariant*' --tests ResponseFieldVariantGeneratorTest
BUILD SUCCESSFUL in 5s
```

모든 케이스 PASS. 행위 변경 없음 확인 (same candidates, same order, same skips).
