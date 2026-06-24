# Task 7 Report: none 모드 String 변형 순차 교체 (REQ-012)

## 작성 테스트

**파일:** `graph-rag-builder/src/test/java/io/graphrag/builder/run/StringLiteralVariantNoneModeTest.java`

### 테스트 목록 (2개)

#### 1. `buildVariantCandidatesExtractsStringLiterals()`
- `ORDER_SHAPE`(status: java.lang.String 필드)와 `STRING_LITERALS`(dtoFqn→field→리터럴 맵)로 `buildVariantCandidates` 호출.
- 단언: `candidates` 키는 "status"만 존재; 값은 `["PENDING", "SHIPPED", "DELIVERED"]` 포함; baseline `"sample-status"` 제외.
- 목적: production `buildVariantCandidates`의 String 후보 추출 경로(REQ-012 직접 경로)를 명시적으로 검증.

#### 2. `noneModeSequentiallyReplacesStringVariantsAndPreservesGlobal()`
- `NoTraceKey` + `HttpCaptureServer` + `ExternalStubSynthesizer`로 `--trace-mode none` 환경 구성.
- `ORDER_SHAPE`를 전역 stub으로 등록(baseline: `{"orderId":1,"status":"sample-status"}`).
- `buildVariantCandidates`로 추출한 후보로 `VariantPlan` 생성 → `exploreResponseVariants(isolated=false)` 호출.
- 각 invoke 시점에 헤더 없는 HTTP 요청으로 현재 활성 stub 응답 캡처.
- 단언:
  - 각 invoke 응답에 String 리터럴(`PENDING`/`SHIPPED`/`DELIVERED`) 중 하나만 포함; baseline `"sample-status"` 없음.
  - 루프 종료 후 `syn.isVariantRegistered("GET", "/orders/status")` == false(변형 stub 모두 제거).
  - 헤더 없는 요청 → baseline `"sample-status"` 응답(전역 stub 보존 확인).
  - `result.attempted()` == `plan.kept().size()`(모든 후보 시도됨).

## 프로덕션 코드 변경 여부

**없음.** Task 6에서 `exploreResponseVariants`(구 `exploreEnumResponseVariants`)가 enum/String 구분 없이 `VariantPlan`을 돌리도록 통합돼 있으므로, none 모드 String 변형 경로는 이미 정상 작동. 테스트가 즉시 GREEN → REQ-012 확인.

## 테스트 실행 결과

```
./gradlew :graph-rag-builder:test --tests StringLiteralVariantNoneModeTest --tests EnumVariantNoneModeTest

BUILD SUCCESSFUL in 3s
14 actionable tasks: 2 executed, 12 up-to-date
```

### XML 결과

```
StringLiteralVariantNoneModeTest: tests=2, failures=0, errors=0, skipped=0
  - buildVariantCandidatesExtractsStringLiterals()         0.016s PASS
  - noneModeSequentiallyReplacesStringVariantsAndPreservesGlobal() 0.026s PASS

EnumVariantNoneModeTest: tests=1, failures=0, errors=0, skipped=0
  - noneModeSequentiallyReplacesVariantsAndPreservesGlobal() 1.075s PASS
```

## 설계 선택

- `VariantInvoker.invoke()`에서 `servedAtInvoke.size() - 1`로 arm bit를 결정 → 누적 커버리지에 새 arm이 계속 추가되도록 시뮬레이션(각 변형이 서로 다른 probe를 킴).
- plan 순서: `TreeMap(status→[DELIVERED, PENDING, SHIPPED])` — ResponseFieldVariantGenerator가 TreeMap으로 정렬하므로 알파벳순. 단언은 순서 무관(`hasLiteral` 포함 여부만 확인)으로 작성해 구현 결합도를 낮춤.
- enum 테스트와의 일관성: 동일한 `NoTraceKey`/`ExternalStubSynthesizer`/`VariantInvoker` 인터페이스 사용.

## 우려사항

없음. 테스트가 GREEN으로 통과했고 프로덕션 변경이 없으므로 회귀 위험 없음.
