# Task 7 Report: order-service 샘플 E2E DoD 검증

## 결과: ✅ 완료 (E2E PASS — 53 tests, 0 failures)

## 진행 요약

- **Step 1 (SUT 수정)**: OrderController의 Kafka 발행 로직(`kafkaTemplate.send("order.events", user.getId(), payload)`)은 이미 Task 4(`72e38fe`)에서 들어가 있어 추가 작업 불필요.
- **Step 2 (풀 파이프라인 E2E)**: 1차 실행에서 `OrdersPostTest_S201_1/2`(outbound produce 검증) 2건 FAIL.

## 1차 실패 근본 원인 (2겹)

1. **공유 토픽 오염**: inbound consumer 검증 테스트(`OrderEventConsumerTest`)가 같은 `order.events` 토픽에 직접 produce(key=`probe-userId`, payload=`{"eventId":"sample-eventId","type":"sample-type",...}`). outbound 검증 테스트의 `consumeNextRecord`가 이 오염 레코드를 집어 단언이 깨짐.
2. **비결정 단언값 하드코딩**: 생성 단언이 탐색 캡처값(eventId=DB auto PK `96883`, key/userId=`probe-userId`)을 그대로 박고 strict 비교 → 테스트별 실제값과 불일치. spec은 LENIENT인데 구현은 strict였음.

## 수정 (사용자 결정: "제대로 수정")

| 변경 | 파일 |
|---|---|
| `consumeNextRecord(topic, expectedKey, timeout)` — key 불일치 레코드 skip (토픽 격리) | `KafkaHelper.java` |
| `substitutions`(캡처값→런타임 표현식)·`nonDeterministicValues`(INSERT PK literal) 노출 | `ComposedFixture.java`, `FixtureComposer.java` |
| emit key를 런타임 변수로 치환, payload 비결정 필드 제거, JSONAssert LENIENT | `Generator.java`, `test-class.mustache` |
| `kafka()` lazy singleton (subscribe/consume 동일 인스턴스) | `TestScope.java` |
| INSERT PK literal을 응답 단언에서 제외 | `FixtureComposer.java` |

## 검증

- 단위(inner-loop): `KafkaHelperTest.consumeNextRecord_byKey_skipsRecordsWithOtherKeys` 추가 (green), `GeneratorTest`/golden 새 형식으로 갱신 (green).
- E2E(outer-loop): `tests=53 skipped=0 failures=0 errors=0` — `OrdersPostTest_S201_1/2` 포함 전부 그린.

## DoD (spec §6) 충족

1. ✅ order-service `POST /api/orders` → `order.events` 발행 (Task 4에서 구현)
2. ✅ 빌더 탐색 시 `CapturedEventEmit`가 `GraphAsset`/샤드에 영속
3. ✅ 생성 E2E JUnit에 `subscribe`/`consumeNextRecord`/`JSONAssert` 단언 + 컴파일·전체 그린
