# Progress Ledger

Task 1: complete (commits ef5c66f..7678105, review clean)
- Minor 피드백: JsonRoundTripTest.java의 343라인에서 Json.mapper() 형식으로 통일 권장 (최종 병합 전 수정 예정)

Task 2: complete (commits 7678105..f6a32e2, review clean)
- Minor 피드백 1: FileGraphRagClient.java:113에서 Objects.equals(e.pathId(), pathId)를 적용하여 NPE 방어 권장 (최종 병합 전 수정 예정)
- Minor 피드백 2: IncrementalBuildPlanner.java:22에서 previous가 null일 경우에 대한 방어 로직 추가 권장 (최종 병합 전 수정 예정)

Task 3: complete (commits f6a32e2..1d4bfed, review clean)
- Minor 피드백: KafkaCaptureReceiver.java에서 ConcurrentLinkedQueue 대신 ArrayDeque나 LinkedList를 사용하여 이중 동기화 오버헤드 완화 권장 (최종 병합 전 수정 예정)

Task 4: complete (commits 1d4bfed..786f04d, review clean)
- Minor 피드백 1: OtelHttpCaptureAcceptanceTest에서 private doSend 메서드를 리플렉션 호출하는 대신 public run 호출 형태로 리팩토링 권장 (최종 병합 전 수정 예정)
- Minor 피드백 2: KafkaCaptureReceiver.java의 100ms settle timeout을 생성자 주입 또는 설정 변수로 제어 가능하도록 확장하여 CI 테스트 flakiness 방어 권장 (최종 병합 전 수정 예정)

Task 5: complete (commits 786f04d..abae2a7, review clean)
- Minor 피드백: KafkaHelper.java of close() 시 buffers.clear()도 함께 실행하여 명시적 리소스 비우기 권장 (최종 병합 전 수정 예정)

Task 6: complete (commits abae2a7..929dc2d, review clean)
- Minor 피드백: Kafka 이벤트의 payload가 null일 경우 빈 문자열 ""을 JSONAssert에 넣을 때 검증이 깨질 수 있으므로 null 방어 고려 권장 (최종 병합 전 수정 예정)

Task 7: complete (E2E PASS 53 tests / 0 failures)
- 1차 E2E에서 outbound produce 검증 2건 FAIL → 근본 원인 2겹(공유 토픽 오염 + 비결정 단언값 하드코딩) 수정.
- KafkaHelper.consumeNextRecord(expectedKey) 토픽 격리, ComposedFixture substitutions/nonDeterministicValues 노출, Generator emit 렌더링(key 치환·비결정 필드 제외·LENIENT), TestScope kafka() lazy singleton, FixtureComposer PK literal 제외.
- spec §5 업데이트(토픽 격리·LENIENT·비결정 필드 처리). 상세: task-7-report.md

PR #61 코드리뷰 triage (receiving-code-review):
- spec-compliance: COMPLIANT. code-quality 2건 수정(subscribe 할당 대기 flaky 방지, emitKeyExpr 비결정 key null fallback).
- minor 피드백 처리: Task5(buffers.clear) 반영. 나머지 거부/보류 — Task1 이미 해결(Json.mapper), Task2a 거부(다른 10개 메서드가 .equals 직접, NPE 불가), Task2b 거부(plan() 호출부 previous non-null 보장), Task3 거부(ConcurrentLinkedQueue→ArrayDeque 마이크로 최적화 리스크), Task4a/b 거부(YAGNI), Task6 보류(receiver가 null value를 NullNode 래핑 → payload Java-null 미발생).
- code-quality Finding 3(deterministicPayload가 numeric/중첩 비결정 값 미제거): 후속 — 현 캡처 payload는 textual이라 무해.
