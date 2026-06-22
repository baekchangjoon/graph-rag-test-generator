# 병렬 fan-out 탐색 — pjacoco 통합 PoC 설계

- 작성일: 2026-06-22
- 상태: 설계(brainstorming 산출) — 사용자 검토 대기
- 브랜치/worktree: `feat-fanout-pjacoco-poc`
- 관련: [docs/05-testing.md](../../05-testing.md), [docs/06-test-environment.md](../../06-test-environment.md),
  [2026-06-18-otel-sql-capture-design.md](2026-06-18-otel-sql-capture-design.md),
  [2026-06-18-traceid-log-sql-capture-design.md](2026-06-18-traceid-log-sql-capture-design.md)
- 외부 의존: `parallel-per-test-coverage`(pjacoco, `~/github_parallel-per-test-coverage`)

---

## 1. 배경과 문제

빌더는 엔드포인트마다 고정 예산(`--budget-requests`, 기본 60회 요청)을 들여 분기 탐색을
한다. 현재 `ExplorationOrchestrator`는 엔진을 **순차** 실행하고, 엔드포인트도 **순차**로
처리한다. 예산이 고정이라면 엔드포인트들을 **병렬 fan-out**으로 동시에 소비하면 벽시계
시간이 크게 줄어든다.

병렬화의 진짜 장벽은 **커버리지 수집 모델**이다. `EndpointExplorationRunner`는
`coverage.dump(reset=true)`로 JaCoCo를 **리셋과 함께** 뜨고, 이 per-request dump를
누적 병합해 같은 라인의 true/false arm을 분리한다(arm-accurate `coverageKey` 지문 →
distinct path 보존). 그런데 JaCoCo dump-reset은 **프로세스 전역** 동작이라, 단일 SUT
프로세스에 여러 워커가 동시에 요청하고 dump하면 서로의 커버리지를 **리셋·오염**시킨다.
SQL은 trace-id(OTLP/B3)로 사후 분리되지만 **JaCoCo는 trace 개념이 없어** 분리 불가다.

`parallel-per-test-coverage`(pjacoco)는 정확히 이 문제 전용 해법이다. testId를 인입 요청의
OpenTelemetry Baggage(`baggage: test.id=...`)로 받아, ThreadLocal per-test 스토어에
**추가(additive)** 기록하고 testId별 `.exec`(바닐라 JaCoCo 바이트 호환)를 산출한다.
graph-rag는 이미 `baggage: test-id=...`로 WireMock 격리를 하고 있어 메커니즘이 동형이다.

## 2. 목표 / 비목표

**목표**: 격리 전략 **A**(pjacoco 단일 SUT fan-out)의 **실현성을 pass/fail로 확정**한다.
PoC가 통과하면 본 fan-out 설계로 진행하고, 막히면 **B**(SUT/DB 워커별 복제)로 회귀한다.

**비목표(PoC 범위 밖)**:
- 실제 fan-out 병렬 실행 엔진 구현(본 설계 단계에서).
- 입력(후보) 단위 fan-out — 엔드포인트 단위 1차 확정, 입력 단위는 측정 후 후속.
- DB 동시 seeding 충돌의 완전 해결 — 별도 과제(본 설계에서 회피 전략만 명시).
- `none` 모드 병렬화 — `none`은 로그 byte-offset 직렬이라 병렬에서 제외(otel/sleuth 전제).

## 3. 확정된 설계 결정 (brainstorming 합의)

| 결정 | 값 | 근거 |
|---|---|---|
| 입자도 | 엔드포인트 단위(1차) | 엔드포인트별 probe id 격리 기존재 → 가장 단순·안전. 입력 단위는 후속 |
| 격리 전략 | D → A | pjacoco PoC 선행으로 A 실현성 확정 후 단일 SUT fan-out. 막히면 B 회귀 |
| arm 정확도 | per-request testId 발급 | 요청마다 `/test/start`→요청→`/test/stop` → per-request `.exec` = 현 per-request dump와 등가 |
| `none` 모드 | 병렬 제외 | trace key 부재로 동시 흐름 분리 불가. 병렬은 otel/sleuth 전제 |

## 4. PoC 검증 항목 (수용 기준)

각 항목은 Given-When-Then 수용 기준을 가진다. **V1~V4 전부 pass해야 A로 진행**한다.

### V1 — 에이전트 공존 부팅 + 바닐라 호환 산출 (petclinic)
- **Given** petclinic SUT를, 기존 `jacocoagent.jar` 대신 `-javaagent:otel ... -javaagent:pjacoco-agent.jar=destfile=...,includes=...` 순서로 부착하고
- **When** 단일 엔드포인트를 1회 탐색하면
- **Then** SUT가 정상 부팅하고, 산출된 `.exec`가 바닐라 JaCoCo로 읽혀(`jacococli`/기존 리포트 파이프라인 무수정) 라인·분기 카운트가 나온다.
- **측정**: 부팅 성공 여부, `.exec` 파싱 성공, 단일 엔드포인트 커버리지가 기존(바닐라 dump-reset) 대비 동등(±0 라인 목표, 허용 오차 명시).

### V2 — 동시 2 엔드포인트 커버리지 교차오염 0 (petclinic)
- **Given** pjacoco 부착 SUT와 서로 다른 testId를 든 두 워커를
- **When** 두 엔드포인트를 **동시에** 요청하면(각 요청 `baggage: test.id=<testId>`)
- **Then** 각 testId의 `.exec`에는 **자기 엔드포인트가 실행한 분기만** 들어가고 상대 엔드포인트 전용 분기는 0건이다.
- **측정**: 두 엔드포인트가 서로소인 클래스/분기를 갖도록 선택 → 교차 분기 카운트 == 0 확인. graph-rag `test-id`(대시) ↔ pjacoco `test.id`(닷) **키 정합** 포함.

### V3 — per-request testId로 arm 분리 = 현 지문 등가 + 오버헤드 (petclinic)
- **Given** 같은 엔드포인트의 같은 라인 true/false arm을 여는 두 입력을
- **When** 요청마다 고유 testId로 `/test/start`→요청→`/test/stop`을 돌리면
- **Then** 두 `.exec`의 arm 지문(현 `coverageKey`에 해당하는 probe 단위 지문)이 **서로 다르게** 분리되어, 현 per-request dump-reset 모델과 등가의 path 분리를 얻는다.
- **측정**: (a) arm 분리 정확도 — 현 모델로 얻던 distinct path 수와 일치. (b) **오버헤드** — 요청당 start/stop 왕복 지연(ms), 예산 60요청 기준 `.exec` 파일 개수·총 용량, 탐색 1엔드포인트 벽시계 증가율.

### V4 — 분산 트레이스 귀속 (order-service, Kafka/async)
- **Given** order-service(HTTP→Kafka→consumer, 별도/동일 JVM)에 OTel javaagent + pjacoco 이중주입(`traceKeyAutoCreate=true`)을
- **When** 한 엔드포인트 요청이 Kafka consumer 비동기 SQL/코드를 유발하면
- **Then** **downstream consumer의 커버리지가 동일 testId(또는 traceId 매핑)로 귀속**되어, 비동기 경로 분기가 그 엔드포인트의 `.exec`에 들어간다.
- **측정**: consumer 전용 분기가 해당 testId `.exec`에 귀속되는 바이트 수 > 0. pjacoco의 `tainted-spring-distributed-coverage` 선례와 동형 확인. otel-mode(traceparent)와 sleuth-mode(B3) 각각에서 점검.

## 5. arm 정확도 메커니즘 (per-request testId)

현 모델: 엔드포인트 1개 = 1 `EndpointExplorationRunner` 인스턴스, 요청마다
`coverage.dump(reset=true)` → `cumulativeCoverage`에 누적, probe 단위 지문으로 arm 분리.

PoC/A 모델: 엔드포인트 1개 = 1 워커 = baggage namespace(예: `ep-<endpointId>`).
그 안에서 **요청마다 고유 testId**(`ep-<endpointId>-req-<n>`)로
`POST /__coverage__/test/start?testId=...` → 요청(`baggage: test.id=...`) →
`POST /__coverage__/test/stop` → per-request `.exec`. 이 per-request `.exec`가 현
per-request dump와 1:1 대응한다. 누적·병합·지문 산출 로직은 `.exec` 소스만 바뀌고
동일하게 재사용.

**핵심 리스크**: start/stop 왕복이 요청마다 2회 추가 HTTP. 예산 60요청 × 엔드포인트 수
만큼 발생 → V3에서 오버헤드를 정량 측정해 수용 가능성을 판단한다.

## 6. 통합 지점 (A로 갈 때 손댈 곳 — PoC가 검증할 표면)

1. **`JacocoAgent` 대체** — `javaToolOptions()`/`containerJavaToolOptions()`가 내는
   `-javaagent:jacocoagent.jar=output=tcpserver...`를
   `-javaagent:pjacoco-agent.jar=destfile=...,port=...,includes=...`로 교체. 산출이
   tcpserver dump가 아니라 `destfile` `.exec`라 dump 호출부도 제어 엔드포인트로 전환.
2. **OTel 순서** — `-javaagent:otel` **먼저**, `-javaagent:pjacoco` 나중(README 경고:
   OTel `ContextStorage` weave가 pjacoco 로드 전 설치돼야 scope 훅 정상).
3. **키 정합** — graph-rag `baggage: test-id=`(대시) ↔ pjacoco `test.id`(닷). 둘 중
   하나로 통일하거나 pjacoco 설정으로 키명 수용.
4. **제어 엔드포인트 배선** — `AnalysisEnvironment`/`AttachedComposeEnvironment`가
   per-request `/__coverage__/test/start|stop` 호출. 컨테이너 도달성(host-gateway/포트)
   기존 OTLP receiver 배선 패턴 재사용.
5. **로컬 설치** — pjacoco 미배포(Maven Central X). `./scripts/install-local.sh`로
   mavenLocal 설치 후 agent jar 추출 경로 확보(현 `AgentJar.extractTo` 대응물 필요).

## 7. Pass/Fail 게이트와 fallback

- **PASS(V1~V4 전부)** → 본 fan-out 설계(spec→requirements→plan)로. A 채택.
- **V3 FAIL**(arm 분리 상실 또는 오버헤드 과도, 기준: 벽시계가 병렬 이득을 상쇄) →
  **B 회귀**(SUT/DB 워커별 복제). 본 설계 문서에 fallback 사유 기록.
- **V4 FAIL**(분산 귀속 불가) → 단일 JVM SUT만 A, MSA/Kafka SUT는 B 또는 직렬 유지.
- **V1/V2 FAIL** → pjacoco-graph-rag 부적합 확정 → B 회귀.

## 8. 산출물

- PoC 측정 스크립트(`e2e/` 또는 `poc/fanout-pjacoco/`): V1~V4 각각의 부착·요청·`.exec`
  검증을 자동화(legacy-async-capture PoC의 selftest 패턴 차용).
- 측정 리포트: V3 오버헤드 수치(요청당 ms, `.exec` 수/용량, 벽시계 증가율), V2 교차오염
  카운트, V4 귀속 바이트.
- 본 spec의 "PoC 실측 결과" 절 갱신 + pass/fail 판정.

## 9. 리스크와 반론

- **arm 입자도 불일치(최대 리스크)** — per-request testId가 현 per-request dump와 진짜
  등가인지, start/stop 왕복 오버헤드가 병렬 이득을 깎지 않는지가 A 전체의 사활. V3가 1차 관문.
- **DB 동시 seeding 충돌** — pjacoco가 커버리지는 분리해도 같은 SUT 같은 DB에 동시
  seed INSERT/DELETE는 별개 충돌(06-14 orders 500 사례). 회피: 엔드포인트별 DB schema/
  probe id 격리 or seeding 구간만 직렬화. 본 PoC는 커버리지 격리에 집중하되 V2/V4에서
  동시 요청 시 seeding 실패가 관측되면 별도 과제로 분리 기록.
- **pjacoco 미배포·로컬 설치 의존** — mavenLocal 설치 필요. CI 재현성에 추가 단계.
  배포 로드맵(REQ-D03)은 pjacoco 측 후속.
- **B를 건너뛴 비용** — D를 택해 B 인프라를 안 만들었으므로, PoC가 FAIL이면 B 착수가
  순연된다. 단 B는 헛작업 1회를 보장하는 구조였으므로 D 선행이 기대값 우위(brainstorming 판단).

## 10. PoC 이후 본 fan-out 설계 스케치 (참고, 별도 spec 대상)

A PASS 시: `ExplorationOrchestrator` 상위에 엔드포인트 워커 풀(병렬도 = cap, 단일 SUT
공유). 워커별 testId namespace, per-request testId로 커버리지·SQL 귀속. 예산은 현행대로
**엔드포인트당** 적용(전역 분배 변경 없음 → 엔진 간 "미사용분 양도" 최적화 보존). `none`
모드는 직렬 강등. 이 스케치는 PoC 통과 후 별도 design spec + requirements + plan으로 구체화.
