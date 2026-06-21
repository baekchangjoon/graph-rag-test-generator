# REQ-012 — Kafka 캡처-2회 diff Implementation Plan

> REQUIRED SUB-SKILL: superpowers:subagent-driven-development. 런타임 검증(e2e=최종 진실), OS-이식성.

**Goal:** P3의 패턴 휴리스틱(REQ-009/011: UUID/ISO-8601만)이 놓치는 **비-패턴 서버-생성 Kafka payload 필드**(예: 시퀀스 ID, 비표준 토큰)를, **동일 입력 2회 발행 diff**로 검출해 비결정으로 분류한다.

> 출처: RFC §5 P3(REQ-012, Should). P3(REQ-009/010/011)은 이미 머지(main). REQ-012는 그 보강.

## 현황(실코드, off main 176b8a0)
- Kafka 캡처: `kafkaCapture.drainAllByTraceId(300)`(EndpointExplorationRunner L630) → traceId별 `CapturedRecord` 맵. candidate마다 `kafkaTraceId`.
- P3: `Generator.deterministicPayload`가 `fixture.nonDeterministicValues()`(DB-PK) + `ServerGeneratedDetector`(UUID/ISO-8601)로 비결정 분류. REQ-012는 **diff로 검출한 필드를 `nonDeterministicValues`(또는 서버-생성 슬롯)에 추가**.
- write 경로: SUT 아웃-오브-프로세스 → 러너 롤백 불가(P3 Gemini 교훈). 1차 발행 행은 캡처 INSERT의 역(DELETE)으로 정리(`deleteSeeds`/`Seeds.delete` 패턴).

## 핵심 설계
**대상:** Kafka를 발행하는 엔드포인트의 happy path. **2회 발행 diff:**
1. happy 입력으로 1차 발행(기존 탐색에서 이미 1차 캡처됨) → payload_1, traceId_1.
2. (write 경로면) 1차가 만든 행을 캡처 INSERT의 역 DELETE로 정리(유니크 충돌 방지). 부작용 없는(조회) 경로는 정리 불필요.
3. 동일 happy 입력 재발행 → payload_2, traceId_2(drainAllByTraceId로 분리).
4. **field-by-field diff**: payload_1[f] != payload_2[f] 인 필드 f = 서버-생성(비결정) → `ComposedFixture.nonDeterministicValues`에 그 **값**을 추가(P3 deterministicPayload가 제거/형식단언). 입력 유래(substitutions) 값은 제외(불변 — REQ-010).
5. 역연산 불가 부작용(외부 호출 등)·정리 불가 write 경로는 diff 생략(P3 휴리스틱만; 회귀 0).

**비활성 탈출구:** `GRB_KAFKA_DIFF=off`(기본 on, budget/회귀 제어).

## Task 1 — dual-invoke + diff (builder)
- [ ] EndpointExplorationRunner에 "happy 재발행 + 2차 Kafka drain + payload diff" 추가. write 경로는 1차 행 역DELETE 후 재발행. diff 결과 비결정 값을 path/fixture에 전달(`nonDeterministicValues` 경로). 단위/통합 TDD: 두 payload(eventId만 다름)의 diff가 eventId를 비결정으로 검출; 입력유래 동일 필드는 제외.
- **확인 필요(구현):** ① happy 재발행 invoker 재사용 ② traceId 분리(drainAllByTraceId 타이밍) ③ write 경로 역DELETE가 캡처 INSERT로 가능한지(by-id seed 패턴) ④ budget 영향(1회 추가 요청).

## Task 2 — diff 결과를 생성에 연결 (generator)
- [ ] diff로 검출된 비결정 필드가 `Generator.deterministicPayload`에서 P3 경로로 처리(제거 또는 형식단언)되는지 확인. 이미 `nonDeterministicValues` 경로면 추가 변경 최소. 단위: diff-검출 필드가 생성 테스트에서 리터럴로 박히지 않음.

## Task 3 — e2e + 회귀 + 매트릭스
- [ ] 비-패턴 서버-생성 필드(예: 시퀀스 ID)를 emit하는 내부 fixture(또는 order-service 확장)로 diff 검출 실증(런타임). `:graph-rag-builder:test :test-generator:test` + `e2e/run-e2e.sh` green(회귀 0). 매트릭스 REQ-012 🟢, Coverage 18/18(100%).

## Self-Review
- Spec: REQ-012 = diff 검출(T1) → 생성 연결(T2) → 검증(T3).
- 설계: write 경로 역DELETE(롤백 불가 회피), 부작용-불가 경로 생략(보수적), 입력유래 제외(REQ-010 불변).
- **확인 필요(T1)** + 런타임 검증.

## Execution
Subagent-driven, task별 리뷰. **이건 캠페인 최심부(dual-invoke during exploration) — 막히면 BLOCKED 보고, 가짜 green 금지.** CI watch → rebase 머지 → **Coverage 18/18 = 캠페인 완료.**
