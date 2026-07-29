# 전체 스위트 부하 의존 실패 2건 (관측 기록, 미해결)

> 2026-07-29 `:graph-rag-builder:test` 전체 실행에서 **격리 실행 시에는 통과하는** 실패 2건이
> 관측됐다. 원인 규명과 수정은 이 PR 범위 밖이므로 관측 사실만 남긴다. **"flaky니까 무시"가
> 아니라, 다음에 같은 실패를 본 사람이 처음부터 다시 조사하지 않도록 근거를 남기는 것이다.**

## 관측

| 실행 | 결과 |
|---|---|
| `full9` (리뷰 수정 전) | 1037 tests, **0 failed** (1h47m) |
| `full11` (리뷰 수정 후) | 1040 tests, **2 failed** (1h44m) |
| 두 실패 클래스만 격리 실행 | **BUILD SUCCESSFUL** (26m53s) |

### 실패 1 — `Stage1ExternalStubSynthesisE2E#REQ-002`

```
java.lang.IllegalStateException: SUT did not become healthy in PT1M30S
```

`SutProcess.BOOT_TIMEOUT`이 **90초 하드코딩 상수이고 CLI 오버라이드가 없다**. 전체 스위트가
동시에 Testcontainers·SUT를 돌리는 부하에서 부팅이 데드라인을 넘긴다. 같은 실패 모드가
[E2E-B2 실증 실행 #1](../reports/2026-07-26-triple-synthesis-manual-evidence.md)에서 이미
3회 연속 관측·기록됐다(그때는 codegraph 프로세스 41개로 load 217~410).

**이것은 환경 결함이 아니라 도구 결함이다** — 부팅 데드라인이 조정 불가라 부하가 있는 CI/개발
머신에서 재현 불가능한 실패를 만든다. 착수 시 `--sut-boot-timeout`(또는 env)로 노출할 것.

### 실패 2 — `BuilderIntegrationTest#build_exploresMultiplePathsAndCapturesBothOrms`

```
Expecting [200, 403] to contain [200, 400] — could not find [400]
```

`post-api-orders-search`(MyBatis)의 **400(검증 실패) arm이 발견되지 않고** 403(negative-auth)
arm만 남았다. 같은 테스트가 `full7`·`full9`·격리 실행에서는 통과한다.

**이번 변경이 원인이 아니라는 근거:** 이 실패는 탐색기(`build`)의 arm 발견 결과인데,
이번 PR에서 가드 인식을 바꾼 `ProvenanceIndexer`는 **build 경로에서 인스턴스화되지 않는다** —
`grep -rn "new ProvenanceIndexer(" graph-rag-builder/src/main/java` 결과가
`BuilderCli.runProvenance` 한 곳뿐이다(= `provenance` 서브커맨드 전용). 같은 커밋의 나머지
변경(`TripleSynthesizer`/`TriplePromotionGate`)은 promoted 후보가 있을 때만 발화하는데
이 저장소에 `.graphrag/triples`가 없다(`find` 확인).

**추정 원인(미검증):** 부하 상태에서 탐색 예산·타임아웃이 조기 소진돼 변이 arm 하나를 놓친다.
확증하려면 부하를 준 상태에서 이 클래스만 반복 실행해 재현률을 재고, 탐색 로그에서 400 arm
시도가 있었는지(시도했으나 타임아웃) 아니면 아예 생성되지 않았는지(예산 소진)를 구분해야 한다.

## 왜 지금 고치지 않는가

두 건 다 **부하 조건에서만** 나타나고 격리 실행에서 재현되지 않아, 수정 전에 재현 하네스부터
만들어야 한다(부하 주입 + 반복 실행). 그 자체가 별도 작업이고, 이번 PR의 변경과 인과 관계가
없음이 코드 경로로 확인된다. 다만 **"전체 스위트가 항상 green"이라고 주장할 수 없는 상태**임은
그대로 기록한다.
