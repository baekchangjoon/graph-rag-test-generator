# Progress: Phase 1 종합 검수

**Date**: 2026-05-25
**Tasks**: Phase 1 전체 (#11-#19)

## Phase 1 메트릭 충족 여부

> *"같은 endpoint의 N개 path가 N개 테스트로 합성되고 통과."*

**충족**. `Phase1MultiPathE2eTest`가 다음을 단일 사이클로 검증:

1. 한 endpoint (`POST /api/orders`)에 3개 SampleInput 제안 (ManualPathExplorer)
2. 각 입력별 실 HTTP 호출 + ProxyDataSource(CapturedSqlListener) 캡처
3. 3개의 서로 다른 `ExploredPath` (201/400/404) 영속
4. 멀티-path 합성 → 한 클래스 안에 3개 @Test 메소드
5. 합성된 코드가 javac로 실 컴파일 통과 (`JavaSourceCompilerTest`)

## 완료 task

| # | 산출 |
|---|---|
| 11 | PathExplorer SPI + ManualPathExplorer + ExplorationBudget |
| 12 | GraphArchive 다중 ExploredPath (paths.json + by-endpoint 조회) |
| 13 | Multi-test TestSynthesizer (`synthesizeMulti`, PathContext, MultiPathSynthesisInput) |
| 14 | Phase 1 multi-path E2E |
| 17 | 생성 코드 javac 컴파일 검증 |
| 18 | MyBatis Interceptor SQL 캡처 |

## Phase 1 잔여 (stretch goals)

| # | 항목 | 사유 |
|---|---|---|
| 15 | JaCoCo 커버리지 측정 | Gradle plugin 통한 build-time 리포트는 도입 가능. 분석 시 SUT에 agent 동적 부착 + 프로그래밍 방식 read는 substantial 추가 작업. Phase 1 메트릭에는 비필수 |
| 16 | Coverage-guided fuzzer | #15에 의존. ManualPathExplorer로 Phase 1 메트릭은 충족. fuzzer는 운영 환경에서 입력 자동 탐색 가치가 있으나 Phase 2+로 미룸 |

## 통과한 테스트 (전체 누적)

`./gradlew build`: BUILD SUCCESSFUL, 53 PASSED (변경된 테스트 한정 출력).

Phase 1에서 추가된 테스트:
- `ManualPathExplorerTest` (5)
- `GraphArchiveTest` 신규 케이스 (3): addExploredPathAccumulatesByEndpoint, findPathById, exploredPathsSurviveRoundTrip
- `MultiPathSynthesisTest` (5)
- `JavaSourceCompilerTest` (4)
- `MyBatisCaptureInterceptorTest` (3)
- `Phase1MultiPathE2eTest` (1)

→ **Phase 1 신규 테스트 21개. 모두 GREEN.**

## 설계와의 부합 확인

| 항목 | 상태 |
|---|---|
| docs/04 Multi-path synthesis (한 클래스 N @Test) | OK |
| docs/05 PathExplorer SPI | OK (MANUAL 구현; FUZZER/JDART 인터페이스 명시) |
| docs/03 capture 레이어 — JPA / MyBatis 양쪽 | OK (datasource-proxy + MyBatis Interceptor) |
| 결정적 합성 검증 (TreeMap key sort, javac OK) | OK |
| SCHEMAS.md 0절 `PathExplorerKind.MANUAL` | OK (enum 갱신) |
| SCHEMAS.md 0절 `Endpoint`, `ExploredPath`, `CapturedSql`, `PathContext` 그대로 사용 | OK |

## 누적 자산 (Phase 0 + Phase 1)

```
모듈 8개:
  shared-model, testlib-api, testlib-adapter-noop,
  test-state-dashboard, socket-mock-server,
  graph-rag-builder, test-generator, samples/demo-sut

총 commit ~16개 (main branch)
총 테스트 ~90+개
docs 9 + progress 11
```

## 주요 발견

- **Java records + JdbcTemplate-style accessors → Jackson 호환**: Phase 0에서 발견. 멀티-path E2E의 `ExploredPath`/`SampleInput` 등에서 일관 유지.
- **TreeMap 키 정렬로 결정적 JSON**: 합성 코드가 동일 input → 동일 output 보장.
- **datasource-proxy + MyBatis Interceptor 병행**: JPA는 JDBC 레이어에서, MyBatis는 Executor 레이어에서 캡처. 같은 CaptureContext에 모임.
- **javac in-process**: `System.getProperty("java.class.path")` 그대로 사용하여 외부 의존 라이브러리 해석.

## 다음 단계 (Phase 2 미리보기)

- **외부 HTTP 캡처**: RestTemplate/WebClient/Feign 호출 → 분석용 임베디드 WireMock으로 리디렉트 + 응답 캡처
- **OTEL javaagent 분석/실행 양쪽 적용**: baggage propagation 활성
- **응답 필드 사용 추적**: 합성 시 mock 응답 minimal에 필요한 필드만
- **WireMock stub composer**: 합성된 테스트에 stub 등록 코드 자동 생성
- **테스트 격리 강화**: baggage 헤더 자동 부착 (현재는 미적용)
