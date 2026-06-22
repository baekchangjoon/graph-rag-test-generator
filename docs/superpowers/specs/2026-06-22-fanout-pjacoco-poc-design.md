# 병렬 fan-out 탐색 — pjacoco 통합 PoC 설계

- 작성일: 2026-06-22
- 상태: 설계(brainstorming 산출) — 3-벤더 리뷰 반영 rev.2, 사용자 검토 대기
- 브랜치/worktree: `feat-fanout-pjacoco-poc`
- 관련: [docs/05-testing.md](../../05-testing.md), [docs/06-test-environment.md](../../06-test-environment.md),
  [docs/decisions/read-path-seeding.md](../../decisions/read-path-seeding.md),
  [2026-06-18-otel-sql-capture-design.md](2026-06-18-otel-sql-capture-design.md),
  [2026-06-18-traceid-log-sql-capture-design.md](2026-06-18-traceid-log-sql-capture-design.md)
- 외부 의존: `parallel-per-test-coverage`(pjacoco, `~/github_parallel-per-test-coverage`)

---

## 1. 배경과 문제

빌더는 엔드포인트마다 고정 예산(`--budget-requests`, 기본 60회 요청)을 들여 분기 탐색을
한다. 현재 `ExplorationOrchestrator`는 엔진을 **순차** 실행하고, 엔드포인트도 **순차**로
처리한다. 예산이 고정이라면 엔드포인트들을 **병렬 fan-out**으로 동시에 소비하면 벽시계
시간이 크게 줄어든다.

병렬화의 진짜 장벽은 **커버리지 수집 모델**이다. `EndpointExplorationRunner.doSend`는
요청마다 `coverage.dump(reset=true)`(CoverageClient의 tcpserver dump)로 JaCoCo를
**리셋과 함께** 떠 그 요청만의 delta를 얻고, `CoverageFingerprint.of(delta, appClasses)`로
arm-accurate 지문(`coverageKey`)을 만들어 같은 라인의 true/false arm을 distinct path로
분리한다. 그런데 JaCoCo dump-reset은 **프로세스 전역** 동작이라, 단일 SUT 프로세스에
여러 워커가 동시에 요청·dump하면 서로의 커버리지를 **리셋·오염**시킨다. SQL은
trace-id(OTLP/B3)로 사후 분리되지만 **JaCoCo는 trace 개념이 없어** 분리 불가다.

`parallel-per-test-coverage`(pjacoco)는 정확히 이 문제 전용 해법이다. testId를 인입 요청의
OpenTelemetry Baggage(`baggage: test.id=...`)로 받아, ThreadLocal per-test 스토어에
**추가(additive)** 기록하고 testId별 `.exec`(바닐라 JaCoCo 바이트 호환)를 산출한다.

> **주의(리뷰 발견)**: graph-rag의 testlib/생성 테스트는 `scope.testId()` 기반 고유
> baggage로 WireMock을 격리하지만, **빌더 탐색 경로 `doSend`는 `baggage: test-id=explore`
> 상수**(EndpointExplorationRunner L1342)를 보낸다. 즉 현재 탐색 경로엔 per-request 고유
> testId가 없다. PoC/fan-out은 이 값을 per-request 고유값으로 바꾸는 게 선행 작업이다(§6-3).

## 2. 목표 / 비목표

**목표**: 격리 전략 **A**(pjacoco 단일 SUT fan-out)의 **실현성을 pass/fail로 확정**한다.
PoC가 통과하면 본 fan-out 설계로 진행한다. **A가 불가로 판명되면 자동으로 B(SUT/DB 워커별
복제)로 회귀하지 않고 PoC를 중단**하고, B 착수 여부·대안은 사용자와 재논의한다(사용자 결정).

**비목표(PoC 범위 밖)**:
- 실제 fan-out 병렬 실행 엔진 구현(본 설계 단계에서).
- 입력(후보) 단위 fan-out — 엔드포인트 단위 1차 확정, 입력 단위는 측정 후 후속.
- DB 동시 seeding 충돌의 완전 해결 — 본 PoC는 **per-worker Connection 전제**(§6-6)만 검증하고,
  row-level 충돌 회피는 본 fan-out 설계의 별도 과제.
- `none` 모드 병렬화 — `none`은 로그 byte-offset 직렬이라 병렬에서 제외(otel/sleuth 전제).
- **WS/Kafka capture runner 병렬화** — `KafkaCaptureRunner`/`WsCaptureRunner`도 dump를
  쓰지만 본 PoC·1차 fan-out은 `EndpointExplorationRunner`(HTTP 탐색)에 한정. V4는 Kafka
  consumer **커버리지 귀속**만 검증하지 Kafka capture runner 병렬화가 아니다.

## 3. 확정된 설계 결정 (brainstorming 합의)

| 결정 | 값 | 근거 |
|---|---|---|
| 입자도 | 엔드포인트 단위(1차) | 엔드포인트별 probe id 격리 기존재 → 가장 단순·안전. 입력 단위는 후속 |
| 격리 전략 | D → A | pjacoco PoC 선행으로 A 실현성 확정 후 단일 SUT fan-out. A 불가 시 **중단·재논의**(B 자동회귀 안 함) |
| arm 정확도 | **요청마다 새 고유 testId** | 요청마다 빈 store로 `/test/start`→요청 1건→`/test/stop` → per-request `.exec` = 현 dump(reset=true) delta와 등가 (등가성은 V3 correctness 게이트가 검증) |
| `none` 모드 | 병렬 제외 | trace key 부재로 동시 흐름 분리 불가. 병렬은 otel/sleuth 전제 |

## 3.1 SUT 매핑 (확정 — 외부 repo)

| 게이트 | SUT | 경로/자산 |
|---|---|---|
| V1~V3 | **spring-petclinic** (단일 Spring 앱) | `~/github_spring-petclinic/spring-petclinic` |
| V4 단일 JVM(REQ-006) | tainted-spring **diary** (in-process) | `~/github_tainted-spring` diary 서비스 |
| V4 멀티 JVM(REQ-007) | tainted-spring **diary→Kafka→mindgraph** (별도 JVM, OTel) | `~/github_tainted-spring/tainted-spring-platform/docker-compose.pjacoco-otel.yml` |

8개 tainted-spring MSA(analytics·auth-user·bff-gateway·community·counseling·diary·mindgraph·
notification) 전부 OTel 활용 가능. 멀티 JVM OTel 분산 귀속 SUT 갭이 이로써 해소(legacy-tram/
Sleuth 폴백 불요). diary·mindgraph는 `docker-compose.pjacoco-otel.yml`에 OTel→pjacoco 이중주입이
이미 배선돼 있다.

## 4. PoC 검증 항목 (수용 기준)

각 항목은 Given-When-Then 수용 기준을 가진다. **V1~V4 전부 pass해야 A로 진행**한다.

### V1 — 에이전트 공존 부팅 + 바닐라 호환 산출 + dump 경로 전환 (petclinic)
- **Given** petclinic SUT를, 기존 `jacocoagent.jar` 대신 OTel→pjacoco 순서로 부착하고
  (`-javaagent:otel-javaagent.jar ... -javaagent:pjacoco-agent.jar=destfile=...,includes=...`),
  CoverageClient의 tcpserver dump 호출을 pjacoco 제어 엔드포인트 호출로 교체한 PoC 하니스로
- **When** 단일 엔드포인트를 1회 탐색하면
- **Then** SUT가 정상 부팅하고, **TCP coverage 포트는 더 이상 열리지 않으며**, `/test/stop`이
  flush한 `.exec`가 바닐라 JaCoCo로 읽혀(`jacococli`/기존 리포트 파이프라인 무수정)
  라인·분기 카운트가 나온다.
- **측정**: 부팅 성공, `dump(true)`(tcpserver) 경로가 제어 엔드포인트로 대체돼 동작, `.exec`
  파싱 성공, 단일 엔드포인트 라인 커버리지가 기존(바닐라 dump-reset) 대비 **동일 클래스
  집합에서 ±0 라인**(불일치 시 원인 규명, 허용 오차는 PoC에서 측정 후 고정).

### V2 — 동시 2 엔드포인트 커버리지 교차오염 0 + seeding 무사고 (petclinic)
- **Given** pjacoco 부착 SUT와, **각자 자기 `java.sql.Connection`을 든**(§6-6) 두 워커를
  (각 워커는 서로 다른 testId 발급)
- **When** 서로소인 클래스/분기를 갖는 두 엔드포인트를 **동시에** 요청하면
  (각 요청 `baggage: test.id=<per-request testId>`)
- **Then** ① 각 testId의 `.exec`에는 자기 엔드포인트가 실행한 분기만 들어가고 상대 엔드포인트
  전용 분기는 **0건**이며, ② 동시 구간에서 **HTTP 5xx·seed INSERT 실패 0건**(exploration-report
  오류 0).
- **측정**: 교차 분기 카운트 == 0. graph-rag `test-id`(대시) ↔ pjacoco `test.id`(닷) **키 정합**
  + **값 고유화**(§6-3) 포함. seeding 실패가 관측되면 **V2 fail**로 처리하고 per-worker
  Connection·seeding 직렬화를 본 설계 전제조건으로 확정(별도 이슈로 미루지 않음).

### V3 — per-request testId의 arm 등가(correctness) + 오버헤드 (petclinic)
- **Given** 같은 엔드포인트의 같은 라인 true/false arm을 여는 두 입력을
- **When** 요청마다 **빈 store의 새 고유 testId**로 `/test/start`→요청 1건→`/test/stop`을
  돌려 per-request `.exec`를 얻고, `ExecFileLoader`로 로드해 `CoverageFingerprint.of(...)`를
  적용하면
- **Then (correctness)** vanilla sequential 탐색이 내던 path key 집합과 pjacoco per-request
  `.exec`→Fingerprint 집합이 **일치**한다(같은 distinct path 수·같은 arm 분리).
- **Then (성능)** 오버헤드가 절대 임계값 이내다.
- **측정 (a) 등가**: 단계 — ① vanilla 순차 탐색으로 엔드포인트의 `coverageKey` 집합 수집
  → ② 동일 입력 시퀀스를 pjacoco per-request `.exec`→`ExecFileLoader.load`→
  `CoverageFingerprint.of(delta, appClasses)`로 재산출 → ③ 두 집합 동일성 비교.
  **불일치면 V3 correctness FAIL**(아래 §7 (a)).
- **측정 (b) 오버헤드**: ① 제어 엔드포인트(`/__coverage__/test/start|stop`) 왕복 지연을
  단독 측정(목표 **요청당 < 5ms** 로컬 loopback). ② 60-요청 1엔드포인트 벽시계가 start/stop
  없는 baseline 대비 증가율(**목표 < 10%**). ③ `.exec` 파일 개수·총 용량, pjacoco
  `maxStores`/heap 압박(요청마다 새 store 생성·flush가 한계 내인지). 임계 초과 시 §7 (b).

### V4 — 분산 트레이스 귀속 (tainted-spring diary→Kafka→mindgraph, OTel)
- **Given** tainted-spring(`~/github_tainted-spring`)의 diary·mindgraph에 OTel javaagent +
  pjacoco 이중주입(OTel 먼저)을 — 기존 `tainted-spring-platform/docker-compose.pjacoco-otel.yml`
  사용(`traceKeyAutoCreate=true`, `OTEL_PROPAGATORS=tracecontext,baggage`, diary:6310/mindgraph:6311).
- **When** diary `POST /internal/diaries`(traceparent 주입) 요청이 Kafka `diary.created`를
  발행하고 별도 JVM mindgraph `DiaryCreatedConsumer`가 소비하면
- **Then** diary 자체 커버리지(in-process, REQ-006)와 mindgraph consumer 분기 커버리지
  (별도 JVM, REQ-007)가 **동일 traceId/testId store에 귀속**된다.
- **측정**: 귀속 바이트 > 0. **단일 JVM(diary in-process)·멀티 JVM(mindgraph 별도 JVM) 둘 다
  A의 필수 pass 게이트(사용자 결정 — 분산까지 필수).**
  - **선례·기지 사실**: pjacoco가 이 repo로 멀티 JVM OTel Kafka consumer 갭을 이미 재현·수정
    (pjacoco PR #13, `ca3f32c`; mindgraph per-trace exec classCount 0→14 — `DiaryCreatedConsumer`·
    `GraphService`·`RuleBasedGraphExtractor` 귀속). 근인은 비관용 OTel jar 파일명 매칭 실패였고
    jar 내부 shaded `ThreadLocalContextStorage` 식별로 수정됨
    (`tainted-spring-platform/HANDOFF-from-pjacoco-c3-otel-kafka-2026-06-20.md`). 따라서 PoC는
    **이미 동작 입증된 경로를 graph-rag attach 맥락에서 재검증**한다. 재검증 실패 시 V4 FAIL =
    A 불가 → 중단(§7).
  - **재현 전제**: 오버레이의 pjacoco jar 경로가 제거된 `ptc-trace-context` worktree를 가리켜
    깨져 있으니, main 빌드 산출물(`~/github_parallel-per-test-coverage/parallel-per-test-coverage/
    agent/build/libs/`)로 갱신해야 한다(§6-5). otel-mode(traceparent)가 1차 벡터.

## 5. arm 정확도 메커니즘 (요청마다 새 testId)

현 모델: 엔드포인트 1개 = 1 `EndpointExplorationRunner` 인스턴스, 요청마다
`coverage.dump(reset=true)` → 그 요청만의 delta → `CoverageFingerprint.of(delta)` → 누적.

PoC/A 모델 — **명시적 절차**(리뷰 발견 I1/I6 반영):
- 엔드포인트는 testId **prefix 관례**(`ep-<endpointId>-`)일 뿐 pjacoco의 별도 개념이 아니다.
- HTTP 요청 N마다 **고유 testId** `ep-<endpointId>-req-<N>`:
  `POST /__coverage__/test/start?testId=ep-<endpointId>-req-<N>` →
  요청 1건(`baggage: test.id=ep-<endpointId>-req-<N>`) →
  `POST /__coverage__/test/stop?testId=ep-<endpointId>-req-<N>` → `.exec` 1개.
- **핵심 전제(V3가 검증)**: pjacoco는 additive(OR-누적) 모델이라, **새 testId store가
  `/test/start` 시점에 비어 있어야** 그 stop이 flush한 `.exec`가 "요청 N만의 delta"가 되어
  현 dump(reset=true) delta와 등가가 된다. 같은 이름 재사용·carryover가 있으면 등가가 깨진다.
  요청마다 **새 고유 testId**를 쓰는 이유가 이것이다. (per-endpoint 단일 testId면 60요청이
  한 `.exec`로 OR-합쳐져 arm 분리가 사라진다 — 채택 안 함.)

**핵심 리스크**: 요청당 start/stop 왕복 2회 추가 HTTP + 요청마다 store 생성/flush. V3 (b)가
지연·`.exec` 폭발·heap을 정량 측정한다.

## 6. 통합 지점 (A로 갈 때 손댈 곳 — PoC가 검증할 표면)

1. **`JacocoAgent` 대체** — `javaToolOptions()`/`containerJavaToolOptions()`가 내는
   `-javaagent:jacocoagent.jar=output=tcpserver...`를
   `-javaagent:pjacoco-agent.jar=destfile=...,includes=...`로 교체.
2. **OTel 순서 역전(리뷰 발견 I1)** — 현 `BuilderCli`는 **JaCoCo-first**다: 비-attach
   L243 `jacoco.javaToolOptions() + " " + otel.javaToolOptions()`, attach L327
   `jacocoJto + " -javaagent:.../otel-javaagent.jar"`. pjacoco는 **OTel을 먼저** 나열해야
   scope weave가 정상(README 경고). 두 사이트에서 순서를 **OTel→pjacoco로 뒤집어야** 하며,
   비-pjacoco(fallback-B) 경로를 깨지 않는지 확인한다.
3. **키 정합 + 값 고유화(리뷰 발견 I3/I9)** — (키) graph-rag `test-id`(대시) ↔ pjacoco
   `test.id`(닷) 통일. (값) `doSend` L1342의 상수 `"test-id=explore"`를 **per-request 고유
   testId로 동적화**해야 한다. invoker 체인(`EndpointInvoker`→`doSend`)에 testId 인자를
   주입. 키명 정합과 값 고유화는 **별개 작업**이다.
4. **제어 엔드포인트 배선** — `AnalysisEnvironment`/`AttachedComposeEnvironment`가
   per-request `/__coverage__/test/start|stop`를 호출(컨테이너 도달성은 기존 OTLP receiver
   host-gateway/published-port 패턴 재사용).
5. **pjacoco jar 해소·추출(리뷰 발견 I14)** — `JacocoAgent.prepare`의 `AgentJar.extractTo`
   대응으로 **`PjacocoAgent.prepare`**(가칭) 신설: PoC 단계는 **jar 경로를 시스템 프로퍼티/
   인자로 주입**(`-Dpjacoco.agent.jar=...`)해 메인 빌드 파이프라인 변경을 피한다. PoC 스크립트가
   `:agent:shadowJar`로 pjacoco 빌드(`~/github_parallel-per-test-coverage/parallel-per-test-coverage`)
   후 jar 경로를 하니스에 전달. attach는 jar를 `agentsDir`로 복사 + published control port 노출을
   `JacocoAgent.containerJavaToolOptions`와 대칭으로.
   - **jar 이름 스큐 주의**: pjacoco README/v1.3.0 좌표는 `pjacoco-agent.jar`(`io.pjacoco:pjacoco-agent`)
     이나, tainted-spring 오버레이는 구이름 `jacocoagent-parallel.jar`를 참조한다. shadowJar 산출물
     이름을 확인해 V1(petclinic)·V4(tainted-spring) 양쪽 경로를 실제 산출물로 맞춘다.
   - **V4 오버레이 경로 갱신**: `docker-compose.pjacoco-otel.yml`의 pjacoco jar 볼륨 경로가 제거된
     `ptc-trace-context` worktree를 가리켜 깨져 있으니, main 체크아웃의 `agent/build/libs/...`로 갱신.
6. **per-worker `java.sql.Connection`(리뷰 발견 I2/I4 — A의 전제조건)** — `BuilderCli.explore()`
   가 연 **단일 `java.sql.Connection`을 모든 `EndpointExplorationRunner`가 공유**한다(현재
   순차라 안전). `java.sql.Connection`은 thread-safe가 아니므로 fan-out 워커가 공유하면
   `insertSeeds/deleteSeeds/querySingleRow/seedDefaultRow` 등이 JDBC 레벨에서 레이스한다.
   → fan-out은 **워커별 Connection**(동일 DataSource에서 발급) 또는 seeding 직렬화가
   **선결 전제**다. 커버리지 격리와 무관하게 이게 안 되면 A는 불가. V2가 이를 노출한다.

### 6.1 커버리지 수집 경로 전환 (dump → control + Fingerprint) — 신설

현 체인: `doSend` → `coverage.dump(true)`(tcpserver delta) → `CoverageFingerprint.of(delta,
appClasses)` → `coverageKey` → `cumulativeCoverage` OR-병합. pjacoco 전환 시:
- `dump(true)`는 tcpserver가 없어 **동작 불가** → `/test/stop`가 flush한 per-request `.exec`로 대체.
- per-request `.exec`를 `org.jacoco.core.tools.ExecFileLoader`로 로드해 `ExecutionDataStore`를
  얻고, **기존 `CoverageFingerprint.of(store, appClasses)` 파이프라인을 그대로** 적용
  (Fingerprint 입력 소스만 live store → 로드된 .exec store로 교체).
- 누적·OR-병합·지문 산출 로직은 불변 재사용. 이 전환의 정확성이 **V3 (a) correctness**가
  검증하는 대상이다.

## 7. Pass/Fail 게이트 (A 불가 시 중단·재논의)

**원칙(사용자 결정)**: V1~V4 중 하나라도 A를 불가로 만들면 **B로 자동 회귀하지 않고 PoC를
중단**하고, 결과를 §11에 기록해 사용자와 다음 방향(B/대안/포기)을 재논의한다.

- **PASS(V1~V4 전부)** → 본 fan-out 설계(spec→requirements→plan)로. A 채택.
- **V3 FAIL을 두 종류로 구분(리뷰 발견 I4)**:
  - **(a) correctness 실패** — per-request `.exec`→Fingerprint 집합이 vanilla와 불일치(arm
    분리 상실). additive 모델이 구조적으로 안 맞는 경우 → **A는 아키텍처적으로 부적합 →
    PoC 중단**. 결과를 "A architecturally incompatible"로 §11에 기록하고 재논의(자동 B 아님).
  - **(b) 성능 실패** — 등가는 성립하나 오버헤드가 §4 V3(b) 임계 초과 → 완화 시도 가능,
    안 되면 중단·재논의.
- **V4 FAIL**(분산 귀속): 단일 JVM·멀티 JVM(C3 분산 병합) **둘 다 필수**. 어느 쪽이든 귀속이
  안 되면 **A 불가 → 중단**(분산까지 A 필수 — 사용자 결정).
- **V1/V2 FAIL** → pjacoco-graph-rag 부적합 확정 → **중단·재논의**.

## 8. 산출물

- PoC 측정 하니스(`poc/fanout-pjacoco/` 또는 `e2e/`): V1~V4 부착·요청·`.exec` 검증 자동화
  (프레임워크 선택 — JUnit 통합테스트 vs 셸 — 은 plan에서 확정; legacy-async-capture PoC의
  selftest 패턴 차용, CI-runnable 지향). petclinic은 기존 분석환경/compose, order-service는
  기존 OTEL e2e(`e2e/run-attach-otel-e2e.sh`) 자산 재사용.
- 측정 리포트: V3 (a) 등가 집합 비교 결과 + (b) 오버헤드 수치(요청당 ms, 벽시계 증가율,
  `.exec` 수/용량), V2 교차오염·seeding 실패 카운트, V4 귀속 바이트.
- 본 spec **§11 'PoC 실측 결과'** 절 갱신 + pass/fail 판정.

## 9. 리스크와 반론

- **additive 모델 등가성(최대 리스크, correctness)** — per-request testId가 빈 store에서
  시작해 진짜 delta를 주는지가 A 전체의 사활. **측정이 아니라 정확성 문제**라 V3 (a)가 1차 관문.
  실패 시 오버헤드와 무관하게 B.
- **공유 `java.sql.Connection`(A 전제조건)** — 커버리지를 분리해도 단일 Connection 공유가
  JDBC 레이스를 일으켜 A를 막을 수 있다. per-worker Connection 또는 seeding 직렬화가 선결(§6-6).
- **DB row-level seeding 충돌** — 같은 SUT 같은 DB 동시 seed INSERT/DELETE는 별개 충돌
  (06-14 orders 500, `docs/decisions/read-path-seeding.md` 병렬 한계). 본 fan-out 설계의
  과제로 분리하되, V2가 동시 구간 seeding 실패를 pass 기준에 포함해 숨기지 않는다.
- **pjacoco 미배포·로컬 설치 의존** — Maven Central 미배포 → `install-local.sh` mavenLocal
  설치 + jar 경로 주입(§6-5). CI 재현성에 pre-install 단계 추가.
- **A 불가 시 중단 정책의 비용** — A가 불가로 판명되면(특히 V3 (a) correctness 또는 V4 분산
  귀속) **B로 자동 회귀하지 않고 멈춰** 사용자와 재논의한다(사용자 결정). 따라서 fan-out
  병렬 이득은 그 시점까지 미실현이고, B 착수는 별도 결정으로 순연된다. 이를 감수하는 근거는
  pjacoco가 동일 문제 전용이고 분산 선례를 보유해 A 성공 가능성이 낮지 않다는 판단이며,
  V3 (a)·V4 결과로 사후 검증된다.

## 10. PoC 이후 본 fan-out 설계 스케치 (참고, 별도 spec 대상)

A PASS 시: `ExplorationOrchestrator` 상위에 엔드포인트 워커 풀(병렬도 = cap, 단일 SUT
공유). 워커별 **자기 Connection** + per-request testId로 커버리지·SQL 귀속. 예산은 현행대로
**엔드포인트당** 적용(전역 분배 변경 없음 → 엔진 간 "미사용분 양도" 최적화 보존). `none`
모드는 직렬 강등. WS/Kafka capture runner 병렬화는 추가 후속. 이 스케치는 PoC 통과 후
별도 design spec + requirements + plan으로 구체화.

## 11. PoC 실측 결과

> _(PoC 실행 후 채움 — V1~V4 각 pass/fail, V3 (a) 등가 비교·(b) 오버헤드 수치, V2 교차오염·
> seeding 카운트, V4 귀속 바이트, 최종 A/B 판정.)_
