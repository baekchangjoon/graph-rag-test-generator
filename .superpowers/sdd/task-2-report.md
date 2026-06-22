# Task 2 Report: MAX_NESTING_DEPTH 2→4 (REQ-001, REQ-010)

## Status
DONE — all tests GREEN, no regression.

## 변경 내역

### 1. `index/BodyShapeExtractor.java`
- `MAX_NESTING_DEPTH` 상수를 `2` → `4`로 변경 (line 27).
- Javadoc 설명을 "최대 5개 dot-세그먼트(depth 4 경로)"로 업데이트.

### 2. `index/BodyShapeExtractorNestedTest.java#nestedDepth_cappedAtMax`
- 기존 A→B→C→D(String x) 4단 체인(depth cap=2 기준)을 A→B→C→D→E→F→G(String x) 7단 체인으로 교체.
- depth=4에서 F 타입이 리프로 emit → `b.c.d.e.f` 존재, `b.c.d.e.f.g` 부재, 최대 dot-segment ≤ 5 검증.

### 3. `index/BodyShapeExtractorGenericTest.java` (신규)
- `deepNested` 테스트: `Root(Level1 l1)` → `Level1(Level2 l2)` → `Level2(String value, int count)` 3단 체인.
- `l1.l2.value`(java.lang.String), `l1.l2.count`(int) 스칼라 리프 존재, `l1.l2` 타입 리프 부재 검증.

## TDD 결과
- RED: `deepNested` — cap=2에서 String/int는 scalar short-circuit이므로 실제 통과(scalars bypass cap). `nestedDepth_cappedAtMax` — cap=2에서 `b.c.d.e.f` 부재로 실패. 1 failure 확인.
- GREEN after cap=4: 전체 5 tests PASSED (`BUILD SUCCESSFUL`).

## Regression
`./gradlew :graph-rag-builder:test --tests '*BodyShapeExtractor*Test' --tests '*SampleInputSynthesizer*Test' --tests '*BodyShapeExtractorNestedTest'` → BUILD SUCCESSFUL.

## 비고
`deepNested`는 cap=2에서도 스칼라 필드(`String`, `int`)가 `isScalar` 단락으로 depth 체크를 우회해 `l1.l2.value`가 이미 emit되므로 RED가 아닌 GREEN이었다. 이는 올바른 동작 — 스칼라 타입은 depth 관계없이 항상 리프 emit된다. 테스트 자체는 cap=4 기준 depth-3 중첩 DTO의 스칼라 전개를 검증하므로 유효하다.
