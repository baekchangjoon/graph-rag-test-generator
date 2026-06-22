# 병렬 fan-out pjacoco 통합 PoC 요구사항명세

> 출처(design spec): docs/superpowers/specs/2026-06-22-fanout-pjacoco-poc-design.md
> 완료 정의(DoD): 커버리지 대상 요구사항(Must + 미연기 Should)이 각각 ≥1개의 통과 수용
> 테스트를 가짐 = 추적 매트릭스 대상 전부 green. **단, 본 PoC의 V-게이트(REQ-001~007)는
> "A 실현성 판정"이 산출물이다 — 게이트가 FAIL을 산출해도 그것이 결정적(deterministic)으로
> 측정·기록되면 그 REQ의 수용 테스트는 green이다(판정 자체가 산출물).** A 종합 판정은 REQ-008.

## 비고: 본 명세의 "green"의 의미

이 PoC의 목적은 기능 구현이 아니라 **A(pjacoco 단일 SUT fan-out)의 실현성 확정**이다.
따라서 각 V-게이트 REQ의 수용 테스트는 "측정을 결정적으로 수행하고 pass/fail 판정과 수치를
산출하는가"를 검증한다(🟢 = 측정·판정이 동작). "A가 실제로 적합한가"의 종합 결론은
REQ-008(판정·중단 정책)이 담는다. 이렇게 분리해야 V3(a)/V4가 "A 불가"를 산출하는
정당한 결과도 PoC 자체의 성공으로 기록된다(중단은 정책이지 PoC 실패가 아님).

---

## 요구사항 목록

### REQ-001 — 에이전트 공존 부팅 (OTel→pjacoco) + 바닐라 호환 `.exec` (V1)
- 유형: Functional
- 우선순위: Must
- 설명: petclinic SUT를 OTel javaagent → pjacoco-agent 순서로 부착하고, CoverageClient의
  tcpserver dump를 pjacoco 제어 엔드포인트(`/test/stop` flush)로 대체해도 SUT가 정상 기동하고
  바닐라 JaCoCo 호환 `.exec`를 산출한다.
- 수용기준:
  - Given OTel→pjacoco 순서로 부착하고 dump 호출을 제어 엔드포인트로 교체한 PoC 하니스,
  - When 단일 엔드포인트를 1회 탐색하면,
  - Then SUT가 정상 부팅하고 TCP coverage 포트는 열리지 않으며, `/test/stop` 산출 `.exec`가
    `jacococli`/기존 리포트 파이프라인으로 무수정 파싱되어 라인·분기 카운트가 나온다.
- 검증 레벨: E2E black-box (petclinic 분석환경 기동)

### REQ-002 — 동시 2 엔드포인트 커버리지 교차오염 0 (V2-커버리지)
- 유형: Functional
- 우선순위: Must
- 설명: 서로소인 클래스/분기를 갖는 두 엔드포인트를 단일 SUT에 동시 탐색할 때, 각 testId의
  `.exec`에 상대 엔드포인트 전용 분기가 섞이지 않는다.
- 수용기준:
  - Given pjacoco 부착 SUT와 서로 다른 per-request testId를 든 두 워커,
  - When 서로소 분기의 두 엔드포인트를 동시 요청하면(각 요청 `baggage: test.id=<testId>`),
  - Then 각 testId `.exec`의 상대 엔드포인트 전용 분기 카운트 == 0이다.
- 검증 레벨: E2E black-box

### REQ-003 — 동시 탐색 중 seeding 무사고 + per-worker Connection (V2-안정성)
- 유형: Functional
- 우선순위: Must
- 설명: 동시 2 엔드포인트 탐색 구간에서 각 워커가 자기 `java.sql.Connection`을 사용하며,
  HTTP 5xx·seed INSERT 실패가 발생하지 않는다. 공유 단일 Connection으로 인한 JDBC 레이스가
  없음을 확인한다.
- 수용기준:
  - Given 각 워커가 동일 DataSource에서 자기 Connection을 발급받은 동시 2 엔드포인트 구성,
  - When 두 엔드포인트(seed 필요 포함)를 동시 탐색하면,
  - Then exploration-report 오류 0, HTTP 5xx 0, seed INSERT 실패 0건이다. (실패 관측 시 이
    REQ는 fail이며 per-worker Connection/seeding 직렬화가 A 전제조건임을 §11에 기록한다.)
- 검증 레벨: E2E black-box

### REQ-004 — per-request testId arm 등가 (V3-correctness)
- 유형: Functional
- 우선순위: Must
- 설명: 요청마다 빈 store의 새 고유 testId로 `/test/start`→요청 1건→`/test/stop`해 얻은
  per-request `.exec`를 `ExecFileLoader`로 로드해 `CoverageFingerprint.of(...)`에 넣으면,
  vanilla sequential 탐색이 내던 path key(`coverageKey`) 집합과 일치한다.
- 수용기준:
  - Given 같은 라인 true/false arm을 여는 두 입력과, vanilla 순차 탐색으로 수집한 `coverageKey` 집합,
  - When 동일 입력 시퀀스를 pjacoco per-request `.exec`→`ExecFileLoader.load`→`CoverageFingerprint.of`로 재산출하면,
  - Then 두 집합이 일치한다(같은 distinct path 수·같은 arm 분리). 불일치 시 A architecturally
    incompatible로 판정(REQ-008 중단 트리거).
- 검증 레벨: integration (Fingerprint 파이프라인) + E2E black-box (탐색)

### REQ-005 — per-request 오버헤드 임계 이내 (V3-성능)
- 유형: Non-functional
- 우선순위: Must
- 설명: per-request start/stop 모델의 추가 비용이 정의된 절대 임계값 이내다.
- 수용기준:
  - Given pjacoco 부착 SUT,
  - When 제어 엔드포인트 왕복 지연과 60-요청 1엔드포인트 벽시계를 측정하면,
  - Then ① `/test/start|stop` 왕복 < 5ms(로컬 loopback), ② 60-요청 벽시계가 start/stop 없는
    baseline 대비 < 10% 증가, ③ `.exec` 개수·용량·pjacoco `maxStores`/heap이 한계 내. (초과 시
    REQ-008 성능 판정으로 중단·재논의.)
- 검증 레벨: E2E black-box (측정 하니스)

### REQ-006 — 분산 트레이스 귀속: 단일 JVM consumer (V4-단일)
- 유형: Functional
- 우선순위: Must
- 설명: order-service에서 동일 JVM Kafka consumer가 유발한 비동기 분기 커버리지가 요청
  traceId/testId store에 귀속된다(pjacoco C1 scope 훅).
- 수용기준:
  - Given OTel+pjacoco 이중주입(`traceKeyAutoCreate=true`, `OTEL_PROPAGATORS=tracecontext,baggage`,
    traceparent 주입)된 order-service(consumer 단일 JVM 확정),
  - When 한 엔드포인트 요청이 Kafka consumer 비동기 코드를 유발하면,
  - Then consumer 전용 분기 귀속 바이트 > 0(해당 testId `.exec`).
- 검증 레벨: E2E black-box (order-service OTEL e2e 자산 재사용)

### REQ-007 — 분산 트레이스 귀속: 멀티 JVM consumer (V4-분산, C3)
- 유형: Functional
- 우선순위: Must
- 설명: order-service의 Kafka consumer가 별도 JVM인 경우, pjacoco C3(shared-volume drain 분산
  병합) 워크플로가 graph-rag attach 모드에서 동작해 downstream 커버리지가 동일 testId/traceId로
  귀속된다. (사용자 결정: 분산까지 A 필수 게이트.)
- 수용기준:
  - Given OTel+pjacoco 이중주입 + C3 분산 병합 구성된 멀티 JVM order-service,
  - When 한 엔드포인트 요청이 별도 JVM consumer를 유발하면,
  - Then downstream consumer 분기가 동일 testId/traceId로 귀속(귀속 바이트 > 0). 안 되면 A 불가
    판정(REQ-008 중단 트리거). order-service가 단일 JVM이면 본 REQ는 🔵(해당 없음)으로 표시하고
    분모에서 제외하되, 그 사실을 §11에 명시한다.
- 검증 레벨: E2E black-box

### REQ-008 — A 종합 판정 + 중단·재논의 정책 (no auto-fallback)
- 유형: Functional
- 우선순위: Must
- 설명: V1~V4(REQ-001~007) 결과를 종합해 A pass/fail를 결정적으로 판정·기록하고, **A 불가 시
  B로 자동 회귀하지 않고 PoC를 중단**해 사용자 재논의로 넘긴다.
- 수용기준:
  - Given REQ-001~007의 측정 결과,
  - When 종합 판정을 수행하면,
  - Then spec §11에 V1~V4 각 pass/fail·수치와 최종 A/B 판정이 기록되고, FAIL이면 "중단·재논의"로
    종결한다(B 인프라 자동 착수·코드 변경 없음).
- 검증 레벨: 문서 산출물(§11 갱신) + 정책 점검

### REQ-009 — pjacoco agent 해소·주입 (PoC 재현성)
- 유형: Non-functional
- 우선순위: Should
- 설명: pjacoco-agent.jar를 `install-local.sh`(mavenLocal) 후 시스템 프로퍼티/인자
  (`-Dpjacoco.agent.jar=...`)로 주입해 메인 빌드 파이프라인 변경 없이 PoC가 재현된다.
- 수용기준:
  - Given pjacoco를 로컬 설치한 환경,
  - When PoC 하니스를 실행하면,
  - Then graph-rag-builder 메인 build 의존성 변경 없이 pjacoco agent가 부착되고 PoC가 재현된다.
- 검증 레벨: E2E black-box (PoC 스크립트 재실행)

---

## 추적 매트릭스

| REQ-ID | 요구사항 | 수용 테스트 (이름/경로, 계획) | Level | Status |
|--------|----------|------------------------------|-------|--------|
| REQ-001 | OTel→pjacoco 공존 부팅 + 바닐라 `.exec` | `V1AgentCoexistencePoc` (petclinic) | E2E | 🔴 planned |
| REQ-002 | 동시 2EP 커버리지 교차오염 0 | `V2CrossContaminationPoc` | E2E | 🔴 planned |
| REQ-003 | 동시 seeding 무사고 + per-worker Connection | `V2ConcurrentSeedingPoc` | E2E | 🔴 planned |
| REQ-004 | per-request testId arm 등가 | `V3ArmEquivalencePoc` | int+E2E | 🔴 planned |
| REQ-005 | per-request 오버헤드 임계 이내 | `V3OverheadPoc` | E2E | 🔴 planned |
| REQ-006 | 분산 귀속 단일 JVM | `V4SingleJvmAttributionPoc` (order-service) | E2E | 🔴 planned |
| REQ-007 | 분산 귀속 멀티 JVM (C3) | `V4MultiJvmAttributionPoc` | E2E | 🔴 planned |
| REQ-008 | A 종합 판정 + 중단 정책 | `PocVerdictRecord` (§11 갱신 + 정책 점검) | doc | 🔴 planned |
| REQ-009 | pjacoco agent 해소·주입 재현성 | `PjacocoAgentTest` (unit) | E2E | 🟡 unit-green |

Coverage: 0/9 green (0%) — target 100% (대상: Must REQ-001~008 + Should REQ-009).
REQ-009: unit 테스트 통과(🟡). E2E(PoC 스크립트 재실행)는 후속 Task에서 확정 예정.
REQ-007은 order-service가 단일 JVM으로 확정되면 🔵 처리되어 분모에서 제외될 수 있음(그 경우
8/8 대상). Could/Won't 없음.
