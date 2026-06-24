# P2-1/P2-2 구현 검증 리포트

## 요약

Phase 1 spike 코드(`BuilderCli.explore()` 병렬 브랜치)를 전면 감사한 결과,
REQ-P005 (per-worker Connection) 및 REQ-P006 (post-loop 단일 스레드 merge)이
**이미 올바르게 구현**되어 있음을 확인. 추가 수정 불필요.

---

## 1. 공유 쓰기 누수 감사 결과 (REQ-P006)

### 1-1. workerTask 클로저 내부 (L804-860)

- `workerConn = env.openConnection()` — try-with-resources, 워커 로컬 ✅
- `EndpointExplorationRunner` — 내부 상태(cumulativeCoverage, appClasses)는 인스턴스
  로컬이며 생성자 인자가 동일 공유 객체를 참조하더라도 쓰기는 없음 ✅
- `sharedTraceParent.next()` — `AtomicLong.getAndIncrement()` 기반, thread-safe ✅
- `sharedCoverageClient(CoverageProbe)` — pjacoco 백엔드는 traceId별 격리,
  concurrent dump 없음(주석 L829-831에 명시) ✅
- `receiverToClose(KafkaCaptureReceiver)` — `synchronized` 큐, drain은 atomic ✅
- `result.cumulativeExec().accept(runWideExec)` — **post-loop** merge(L896)에서만
  호출됨. workerTask 내부에서 호출하지 않음 ✅

**공유 쓰기 누수 없음 — 수정 불필요.**

### 1-2. 루프 전 preFilter (L746-783)

- `unsupportedShapes.addAll(preFilterShapes)` (L783) — 루프 시작 전 단일 스레드 실행 ✅
- 워커 내부(`EndpointExplorationRunner`)에서 unsupportedShapes를 추가하지 않음 ✅

---

## 2. post-loop 단일 스레드 merge (L888-897)

```java
// 단일 스레드 merge: 순서·동시성 race 없음
for (EndpointExplorationRunner.EndpointResult result : endpointResults) {
    paths.addAll(result.paths());
    sql.addAll(result.sql());
    httpCalls.addAll(result.httpCalls());
    allSeeds.addAll(result.seeds());
    reportEntries.add(result.report());
    capturedEventEmits.addAll(result.capturedEventEmits());
    result.cumulativeExec().accept(runWideExec);   // OR 병합
}
```

모든 futures가 `f.get()`으로 완료된 후 단일 메인 스레드에서 실행됨 ✅

### 11개 카테고리 merge 현황

| 카테고리 | EndpointResult 포함 | merge 위치 | 비고 |
|---|---|---|---|
| paths | ✅ | L890 post-loop | |
| sql | ✅ | L891 post-loop | |
| httpCalls | ✅ | L892 post-loop | |
| allSeeds | ✅ | L893 post-loop | |
| reportEntries | ✅ | L894 post-loop | |
| capturedEventEmits | ✅ | L895 post-loop | |
| cumulativeExec→runWideExec | ✅ | L896 post-loop | ExecutionDataStore.accept(), NOT thread-safe → 단일 스레드 ✅ |
| wsExchanges | N/A | L915 WS 루프 직접 추가 | HTTP 루프 밖 순차처리 |
| kafkaExchanges | N/A | L724 Kafka 루프 직접 추가 | HTTP 루프 전 순차처리 |
| coveredAppBranches | N/A | L922 `coveredAppBranches.addAll(...)` | runWideExec 전 루프 집계 후 반영 |
| unsupportedShapes | N/A | L783 preFilter 직접 추가 | 루프 전 단일 스레드 처리 |

WS/Kafka/coveredAppBranches/unsupportedShapes는 HTTP 루프 밖에서 단일 스레드로 처리되어
`EndpointResult`에 포함할 필요 없음 — 이 설계가 올바름.

---

## 3. per-worker Connection (REQ-P002, REQ-P005)

- HTTP 워커: `workerConn = env.openConnection()` try-with-resources ✅
- WS 루프: `WsCaptureRunner(env.sut(), connection, ...)` — HTTP 루프 **이후** 순차 실행,
  공유 `connection`을 단일 스레드에서만 사용 ✅
- Kafka 컨슈머 루프: `KafkaCaptureRunner(connection, ...)` — HTTP 루프 **이전** 순차 실행 ✅
- 공유 `connection`은 HTTP 병렬 구간과 겹치지 않음 ✅

---

## 4. EndpointResult 확장 여부

`EndpointResult`(L138-144)는 7개 필드를 보유:
`paths, sql, httpCalls, seeds, report, cumulativeExec, capturedEventEmits`

나머지 4개 카테고리(WS, Kafka, coveredBranches, unsupportedShapes)는 HTTP 루프 외부에서
처리되므로 확장 불필요. 설계가 의도적으로 범위를 분리하고 있음.

---

## 5. 컴파일 결과

```
./gradlew :graph-rag-builder:compileJava :graph-rag-builder:compileTestJava
BUILD SUCCESSFUL — 5 tasks UP-TO-DATE
```

---

## 6. 커밋 정보

- 코드 변경 없음: spike가 이미 P2-1/P2-2를 올바르게 구현
- 커밋: 이 리포트 파일만 포함
- 커밋 메시지: `feat(parallel): P2-1/2 per-worker 누적기+Connection + post-loop 단일스레드 merge (REQ-P005/P006)`
