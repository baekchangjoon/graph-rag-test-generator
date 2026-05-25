# Progress: Phase 1 다중 path → 다중 테스트 메소드

**Date**: 2026-05-25
**Tasks**: #11, #12, #13, #14
**Result**: 한 endpoint의 3개 path → 1 클래스 3 @Test 메소드 합성 E2E 통과

## 산출물

### #11 PathExplorer SPI (graph-rag-builder)

```
exploration/
├── PathExplorer.java          # SPI: name() + proposeInputs(endpoint, budget)
├── ExplorationBudget.java     # maxInputs + timeLimit
└── ManualPathExplorer.java    # 사전 정의 SampleInput 리스트 기반
```

`PathExplorerKind` enum에 `MANUAL` 추가. 기존 테스트 갱신.

### #12 GraphArchive 다중 ExploredPath 지원

- `paths.json` 신규 파일
- `addExploredPath`, `findPath`, `pathsByEndpoint`, `allPaths` API 추가
- save/load 라운드트립 검증

### #13 Multi-test TestSynthesizer

- `MultiPathSynthesisInput` + `PathContext` record
- `TestSynthesizer.synthesizeMulti(input)` — 한 클래스에 N @Test 메소드
- 각 메소드 인라인 setup/cleanup (try-finally)
- 메소드명 컨벤션: `path_{sanitized-id}`
- 요청 body: SampleInput.body를 결정적으로 JSON 직렬화 (TreeMap으로 key 정렬)

### #14 Phase 1 multi-path E2E

`Phase1MultiPathE2eTest`가 다음을 검증:
1. ManualPathExplorer로 3개 SampleInput 제안 (성공, 400, 404 시나리오)
2. 각 입력 → MockMvc 실 호출 → CapturedSqlListener 캡처
3. ExploredPath 3개 영속 (각 path가 다른 exit_status)
4. multi-path 합성 → 1 클래스 3 @Test
5. 각 path의 status code (201, 400, 404)가 단언에 포함
6. captured INSERT가 합성 코드의 fixture에 포함

## 테스트 통과 (전체)

`./gradlew build`: BUILD SUCCESSFUL.

추가된 테스트:
- ManualPathExplorerTest (5)
- GraphArchiveTest 신규 (3)
- MultiPathSynthesisTest (5)
- Phase1MultiPathE2eTest (1)

## 발견 및 수정

1. **PathExplorerKindTest containsExactly 깨짐**: MANUAL 추가 후. `containsExactlyInAnyOrder`로 변경.
2. **Map.of 순서 비결정**: body 직렬화에서 TreeMap으로 키 정렬해 결정적 출력 보장.

## 설계와의 부합 확인

| 항목 | 결과 |
|---|---|
| docs/05-branch-exploration.md의 PathExplorer SPI | OK (Manual + 향후 Fuzzer/JDart) |
| docs/04-test-generator.md의 멀티-path 합성 | OK |
| 각 path가 자기 fixture/cleanup을 인라인 보유 | OK (try-finally) |
| 결정적 합성 | OK (TreeMap + 테스트 검증) |

## 다음 단계

Task #17 (javac 컴파일 검증)을 우선 — 합성 코드가 실제로 valid한지 자동 검증.
이후 #15 (JaCoCo) → #16 (fuzzer) → #18 (MyBatis) 순.
