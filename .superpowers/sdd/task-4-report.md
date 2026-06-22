# Task 4 Report: ExploredPath outcome 필드 + compat 생성자

## 상태
완료 (DONE)

## 커밋
`6105c89` — `feat(model): ExploredPath outcome/semanticStatus 필드 + compat 생성자`

## TDD 증거

### Red (컴파일 오류 확인)
`./gradlew :shared-model:test --tests '*ExploredPathCompatTest'` → `cannot find symbol: method outcome()` 등 4개 오류.

### Green
구현 후 동일 명령 → `BUILD SUCCESSFUL in 3s` (2개 테스트 통과)
- `legacyConstructorWith200IsSuccess`: expectedStatus=200 → `outcome=SUCCESS`, `semanticStatus=200`
- `legacyConstructorWith404IsFailure`: expectedStatus=404 → `outcome=FAILURE`, `semanticStatus=404`

### 모듈 전체 컴파일 확인
`./gradlew :shared-model:compileJava :graph-rag-builder:compileJava :test-generator:compileJava` → `BUILD SUCCESSFUL` (기존 call site 모두 컴파일 유지)

## 변경 파일

### `shared-model/src/main/java/io/graphrag/model/ExploredPath.java`
- canonical record에 3개 필드 추가 (맨 끝):
  - `Outcome.Kind outcome`
  - `int semanticStatus`
  - `String semanticStatusText`
- 기존 14-arg canonical을 **14-arg compat 생성자**로 전환:
  - `outcome = (expectedStatus / 100 == 2) ? SUCCESS : FAILURE`
  - `semanticStatus = expectedStatus`
  - `semanticStatusText = String.valueOf(expectedStatus)`
- 기존 13-arg, 12-arg compat 생성자는 14-arg compat으로 체이닝 → 변경 없이 유지

### `shared-model/src/test/java/io/graphrag/model/ExploredPathCompatTest.java` (신규)
- `legacyConstructorWith200IsSuccess`: 200 → SUCCESS
- `legacyConstructorWith404IsFailure`: 404 → FAILURE

## 설계 결정
- **outcome은 항상 non-null**: compat 생성자가 expectedStatus에서 파생. Task 5가 명시 설정하기 전에도 null-safe하게 읽을 수 있음.
- **기존 call site 무수정**: EndpointExplorationRunner의 `withSeedIds`(14-arg), inline 생성 호출(12/13-arg)은 모두 compat 생성자로 자동 처리.
- **JSON 역직렬화 호환**: Jackson은 canonical record constructor를 사용하므로, 기존 JSON(outcome 필드 없음)은 Jackson이 null로 매핑 → compact의 null-normalization이 없어 `outcome=null` 가능. Task 5에서 직렬화/역직렬화 처리 시 주의 필요.

## 후방호환 gap 수정 (커밋 2: compact 생성자 null/zero 가드)

### 문제
Jackson은 legacy graph.json(outcome/semanticStatus/semanticStatusText 필드 없음)을 역직렬화할 때 canonical constructor(compact constructor)를 호출하면서 `outcome=null`, `semanticStatus=0`, `semanticStatusText=null`을 주입. 기존 compact constructor에 이 경우를 처리하는 가드가 없어 downstream(Task 7/8/11)이 null/0을 읽게 됨.

### 수정 내용
`ExploredPath.java` compact constructor에 가드 추가:
- `outcome == null` → `deriveOutcome(expectedStatus)` (private static helper)
- `semanticStatus == 0` → `expectedStatus`
- `semanticStatusText == null || isBlank` → `String.valueOf(expectedStatus)`

기존 14-arg compat constructor도 `deriveOutcome()` 헬퍼로 통일(DRY).

### TDD 증거 (추가 테스트 3개, 총 5개)
`./gradlew :shared-model:test --tests '*ExploredPathCompatTest'` → `BUILD SUCCESSFUL` (5/5 GREEN)
- `jacksonDeserializeLegacyJson200DerivesSuccess`: 구버전 JSON(outcome 필드 없음, expectedStatus=200) → `outcome=SUCCESS`, `semanticStatus=200`, `semanticStatusText="200"`
- `jacksonDeserializeLegacyJson404DerivesFailure`: expectedStatus=404 → `outcome=FAILURE`
- `jacksonDeserializeNewJsonPreservesExplicitOutcome`: 신규 JSON(outcome=FAILURE, semanticStatus=422 명시) → 명시 값 보존(clobbering 없음)

## 자기 리뷰
- 기존 동작 완전 보존: 12/13/14-arg 체이닝 검증 완료.
- 단순함 원칙 준수: 최소한의 변경만 적용.
- Task 7/8/11이 읽는 `outcome()` accessor는 record에서 자동 생성되어 즉시 사용 가능.
