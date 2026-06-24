# Task 10 수정 보고서 — envelope 변형 unconditional keep (REQ-F012-018)

## 문제 요약

`EndpointExplorationRunner`의 `exploreResponseVariants` 루프는 변형을
`mergeAndDetectNewArm` 결과가 `true`일 때만 보존(kept)했다.

envelope 변형(`errorCode=ERROR`)은 합성 baseline body가 이미 `errorCode="sample-errorCode"`
(non-null)로 채워지기 때문에 SUT의 `if (errorCode != null)` 분기가 이미 baseline 탐색으로
커버된다. 따라서 envelope 변형은 새 arm을 열지 못하고(`newArm=false`) → `kept=[]` → egress-assertion
path 없음 → CONTRACT call dangling → E2E red 상태였다.

## 선택한 스레딩 방법 (Option A)

`mergeEnvelopeCandidates`가 병합된 후보 맵과 함께 **envelope 출처 필드 이름 집합**을 반환하도록
반환 타입을 `EnvelopeMergeResult` 레코드로 변경했다. 이 집합을 `exploreResponseVariants`에
추가 파라미터(`envelopeFields`)로 전달해, 변형의 override 키가 그 집합과 교집합이 있으면
`newArm` 여부와 무관하게 무조건 보존(unconditional keep)하도록 했다.

비-envelope 변형의 기존 new-arm 게이팅은 그대로 유지된다. `envelopeFields`가 빈 집합이면
기존 동작과 완전히 동일하다.

## 변경 파일

### 프로덕션 코드
- **`EndpointExplorationRunner.java`**
  1. 신규 `EnvelopeMergeResult` 레코드 추가 (candidates + envelopeFields)
  2. `mergeEnvelopeCandidates` 반환 타입 `Map` → `EnvelopeMergeResult`로 변경. 내부에서 `injected` 집합 수집.
  3. `exploreResponseVariants` 시그니처에 `Set<String> envelopeFields` 파라미터 추가. 루프 내 `envelopeVariant` 판정(`variant.overrides().keySet().stream().anyMatch(envelopeFields::contains)`)으로 unconditional keep.
  4. `runResponseVariantLoops`에서 `mergeEnvelopeCandidates` 호출 결과를 `EnvelopeMergeResult`로 언팩해 `candidates`와 `envelopeFields`를 분리하고, `exploreResponseVariants` 호출 시 전달.

### 테스트 코드
- **`EnumVariantReExploreTest.java`**: 기존 5개 호출에 `Set.of()` 추가 + 신규 테스트 추가
- **`EnumVariantNoneModeTest.java`**: 1개 호출에 `Set.of()` 추가
- **`StringLiteralVariantReExploreTest.java`**: 1개 호출에 `Set.of()` 추가
- **`StringLiteralVariantNoneModeTest.java`**: 1개 호출에 `Set.of()` 추가
- **`EnvelopeVariantCandidateTest.java`**: 반환 타입 변경에 따라 `.candidates()` / `.envelopeFields()` accessor 사용으로 업데이트

## 신규 단위 테스트

**`EnumVariantReExploreTest.envelopeVariantIsKeptEvenWithNoNewArm_nonEnvelopeVariantWithNoNewArmIsDropped()`**

- cumulative에 모든 arm(1~5)이 채워져 `mergeAndDetectNewArm`이 항상 `false` 반환.
- plan: `errorCode=ERROR`(envelope 출처) + `mode=BACKORDER`(non-envelope).
- `envelopeFields = {"errorCode"}` 로 호출.
- **단언 1**: `errorCode=ERROR` 변형 → kept에 포함됨 (unconditional keep).
- **단언 2**: `mode=BACKORDER` 변형 → dropped (new arm 없음, non-envelope).

## 테스트 결과

| 범위 | 결과 |
|---|---|
| `./gradlew :graph-rag-builder:test -PexcludeTags=integration` | **BUILD SUCCESSFUL** (764+ tests) |
| `EnumVariantReExploreTest.*` 전체(신규 포함) | GREEN |
| `EnvelopeVariantCandidateTest.*` | GREEN |
| `ResponseVariantAssertionPathTest.*` | GREEN |
| `EgressStubBodyFidelityEnvelopeE2E` | 실 SUT 환경 필요 (sut.jar/sut.src 시스템 프로퍼티) |

참고: `ExternalStubSynthesizerTest.registerPostUsesPostMapping` 는 포트 연결 리셋으로 인한
기존(pre-existing) 간헐적 실패이며 본 변경과 무관함 — git stash를 통해 내 변경 없이도 동일하게
발생 확인.

## 영향 범위 (blast radius)

- `mergeEnvelopeCandidates` 1 호출자 (`runResponseVariantLoops`) — 업데이트 완료.
- `exploreResponseVariants` 1 프로덕션 호출자 (`runResponseVariantLoops`) + 8개 테스트 호출 — 전부 `Set.of()` 추가로 업데이트.
- non-envelope 변형 동작: 변경 없음 (기존 new-arm 게이팅 보존).
- `buildEgressAssertionPaths`: 변경 없음.
