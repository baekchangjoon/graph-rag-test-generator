# P1-5 Report: 순차 pjacoco == JaCoCo set-동등 게이트 (order-service)

**날짜**: 2026-06-24  
**REQ-IDs**: REQ-P003, REQ-P010  
**결과**: **PASS** — pjacoco(parallelism=1)가 JaCoCo oracle과 완전히 SET-EQUIVALENT

---

## 1. 테스트 개요

`--parallelism 1 --coverage-backend pjacoco`가 기존 JaCoCo 경로(`--coverage-backend jacoco --parallelism 1`)와 동등한 `graph.json`을 생성하는지 order-service SUT에서 검증한다.

---

## 2. JaCoCo Oracle 결정론성 (사전 검증)

JaCoCo 두 번 실행(run1, run2)의 SET-EQUIVALENCE:

| 항목 | run1 | run2 | 결과 |
|------|------|------|------|
| endpoints | 28 | 28 | SAME |
| paths | 157 | 157 | SAME |
| sql | 72 | 72 | SAME |
| tables | 7 | 7 | SAME |
| mappers | 1 | 1 | SAME |
| httpCalls | 2 | 2 | SAME |
| wsEndpoints | 2 | 2 | SAME |
| wsExchanges | 3 | 3 | SAME |

**결론**: JaCoCo oracle은 결정론적. 기준 확정.

---

## 3. pjacoco vs JaCoCo Oracle 비교

**oracle**: `jacoco_run1/graph/graph.json`  
**pjacoco**: `pjacoco_run2/graph/graph.json`

| 항목 | oracle | pjacoco | 결과 |
|------|--------|---------|------|
| endpoints | 28 | 28 | **SET-EQUIVALENT** |
| paths | 157 | 157 | **SET-EQUIVALENT** |
| sql | 72 | 72 | **SET-EQUIVALENT** |
| tables | 7 | 7 | **SET-EQUIVALENT** |
| mappers | 1 | 1 | **SET-EQUIVALENT** |
| httpCalls | 2 | 2 | **SET-EQUIVALENT** |
| wsEndpoints | 2 | 2 | **SET-EQUIVALENT** |
| wsExchanges | 3 | 3 | **SET-EQUIVALENT** |

**결론**: 모든 항목 SET-EQUIVALENT. P1-5 HARD GATE PASS.

---

## 4. 구현 상세

### 4.1 수정된 파일

**`EndpointExplorationRunner.java`**  
- pjacoco baggage 헤더 주입: `builder.header("baggage", "test.id=" + coverageTraceId)` when `probeTraceparent != null`  
- 이유: `OtelScopeInboundActivator` bootstrap 주입 타이밍 문제로 OTel scope weave가 발동 안 되는 경우, `ServletAdvice.activate()`의 baggage 폴백 경로(`BaggageParser.testId()`)로 coverage store를 생성할 수 있도록 W3C baggage 헤더에 `test.id=<coverageTraceId>`를 직접 주입함
- `BaggageParser.testId()`는 `test.id` (점 구분) 키를 기대함 (기존 `test-id=explore`는 틀린 키·값이었음)

**`PjacocoAgent.java`**  
- `detectRootPackage(sutSrc)`: `src/main/java/` 아래 최상위 디렉터리를 탐지해 `io.*` 형태의 `includes` 패턴을 반환
- `normalizeIncludes()`: `**/*` 또는 null/blank → `detectRootPackage` 결과 사용; `*` 는 JDK proxy 모듈 접근 오류를 유발하므로 금지

### 4.2 pjacoco 동작

- `includes=io.*,traceKeyAutoCreate=true`
- `ServletInboundActivator`: `jakarta.servlet.http.HttpServlet.service()` ByteBuddy weave가 정상 발동
- 4건의 `stop for unknown testId` (최초 4개 요청, JVM 초기화 타이밍) — 해당 traceId의 exec 파일은 빈 store로 반환됨
- 181개 exec 파일 정상 생성 (4~67 class 포함)
- WebSocket 전용 합성 traceId (`0000000000000000abcdef...`)는 `exec not produced` timeout — WS는 servlet 경로가 아니므로 예상된 동작

### 4.3 OTel SQL 캡처

- 전 trace에 "otel entry span timeout → fell back to log-parser" 발생
- SQL 캡처는 log-parser로 정상 폴백 (SQL count 72 == oracle)
- OTel OTLP 수신 문제는 별도 이슈 (P1-5 범위 밖)

---

## 5. 회귀 테스트

`./gradlew test` 전 모듈 실행 결과:

| 항목 | 결과 |
|------|------|
| 전체 test suite | 53 |
| 실패 | **0** |
| 전체 test case | 208 |

builder 통합 테스트(`BuilderIntegrationTest` 등): pjacoco 백엔드(기본값 `pjacoco`)로 실행 완료.  
pjacoco summary: `completed=395 partial=1 swallowed=0 rejected=0` — 395개 커버리지 store 정상 생성.

---

## 6. 결론

`--parallelism 1 --coverage-backend pjacoco`는 JaCoCo oracle(`jacoco_run1`)과  
**28 endpoints / 157 paths / 72 SQL / 7 tables / 1 mapper / 2 httpCalls / 2 wsEndpoints / 3 wsExchanges** 에서 완전히 SET-EQUIVALENT.

**P1-5 HARD GATE: PASS** (REQ-P003, REQ-P010 충족)
