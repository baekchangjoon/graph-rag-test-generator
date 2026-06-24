# 병렬 fan-out 빌더 — Phase 2 구현 계획 (병렬화 + 정확성)

> REQUIRED SUB-SKILL: superpowers:subagent-driven-development.
> 출처: design rev.2 §4.2~4.6/§6 Phase 2, requirements REQ-P004~P009.
> 전제: Phase 1 완료 — pjacoco backend가 `--parallelism 1`에서 JaCoCo와 set-동등(c604783). 공유 `TraceParent`로 전역 유일 traceId 이미 확보(Phase 1 fix). spike가 이미 `--parallelism N` + ExecutorService 골격 배선(BuilderCli.explore L656~).
> **목표**: `--parallelism N`이 `--parallelism 1`과 **set-동등**(순서무관) + 무사고(race/seed충돌 0) + speedup. JaCoCo는 `--coverage-backend jacoco` oracle로 유지(P1-6 제거는 Phase 2 검증 후).

## Global Constraints
- 출력 동등성 = 순서무관 set-동등(엔드포인트별 전 GraphAsset 리스트 필드 + coveredAppBranches).
- 공유 누적기/Connection/dump 금지(Phase 1 분석 §3). per-worker 격리 + post-loop 단일스레드 merge.
- 커밋 author `baekchangjoon <changjoon.baek@icloud.com>`. 비교 도구 = `GraphSetEquivDiffTool`(P1-4).

---

### Task P2-1: per-worker 누적기 + post-loop 단일스레드 merge (REQ-P006)
- spike의 병렬 경로(BuilderCli.explore ExecutorService)에서 공유 ArrayList/LinkedHashSet/ExecutionDataStore 쓰기를 제거. 각 워커가 로컬 `EndpointResult`(paths·sql·httpCalls·ws·kafka·seeds·reportEntries·capturedEventEmits·unsupportedShapes·coveredBranches·cumulativeExec)에 담는다.
- 전 Future 완료 후 단일스레드 merge: 리스트 set-union(+dedupe), coveredAppBranches union, runWideExec는 각 cumulativeExec OR-merge(`ExecutionDataStore.accept`는 merge 단계에서만). graph.json 단일 writer.
- 게이트: 컴파일 + `--parallelism 1` 회귀 불변(set-동등 유지). Commit.

### Task P2-2: per-worker Connection (REQ-P005)
- 공유 단일 Connection 제거. 각 HTTP 워커가 task 시작 시 `env.openConnection()`로 자기 Connection, try-with-resources로 close. Kafka/Ws는 순차(자기 Connection).
- 게이트: 병렬 실행에서 JDBC 예외 0(order-service smoke). Commit.

### Task P2-3: seed probe 키 충돌 해결 (REQ-P007)
- 설계 §4.6: probe 값을 `probe-<endpointId>-<field>` 엔드포인트 스코프(`SampleInputSynthesizer`/`ReadInputSynthesizer`) **또는** seed DELETE post-loop 연기. 택1(인박스 가능). 기존 시드 결정성·검증 회귀 확인.
- 게이트: 같은 필드명 엔드포인트 2개 동시 탐색 → 거짓 404/seed 실패 0. Commit.

### Task P2-4: 비동기 flush 풀 + await 정책 배선 (REQ-P008)
- `PjacocoCoverageBackend`의 async flush 풀을 `--flush-threads M`(기본 parallelism×2, 하한 parallelism)로 사이징. 워커 inner loop는 flush non-blocking + `.exec` await만(타임아웃 시 빈 store, 크래시 금지 — Phase 1 구현 재사용). flush 풀 워커 공유.
- 게이트: 병렬 실행에서 flush 풀 누수/타임아웃 크래시 0, 큐 깊이 로깅. Commit.

### Task P2-5: 병렬 set-동등 하드게이트 + speedup (REQ-P004, P009) — 전 SUT
- 각 SUT(order-service 우선 → petclinic/tainted-spring): `--parallelism N` graph.json vs `--parallelism 1` graph.json을 `GraphSetEquivDiffTool`로 set-비교 + coveredAppBranches + 생성 테스트 pass-rate. 무사고(race/seed충돌 0). speedup 기록(REQ-P001 ~2x 맥락).
- 전 모듈 회귀 green.
- **불일치(병렬에서 누락/오염) 시 fail → P2-1~4 수정**. 통과 후에만 P1-6.
- 게이트: 전 SUT 병렬==순차 set-동등 + 무사고 + 회귀 green. Commit + §11 결과.

### Task P1-6(최후): JaCoCo 전면 제거 (REQ-P010)
- P2-5 통과 후: `--coverage-backend jacoco` 분기·`JacocoAgent`·`CoverageClient`·tcpserver/dump·attach jacoco 배선 제거(`coverage` CLI의 jacoco-core ExecFileLoader는 pjacoco .exec 호환이라 유지). grep 잔존 0 + 회귀 green. Commit.

## DoD (Phase 2 + 마무리)
REQ-P004~P009 green(전 SUT 병렬 set-동등·무사고·speedup), REQ-P010(JaCoCo 제거), REQ-P011(eventuate 조건부). 각 Task spec+quality 리뷰. 전 SUT 회귀 green 후 PR.
