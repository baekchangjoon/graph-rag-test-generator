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
