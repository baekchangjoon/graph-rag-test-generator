# Task 4 Review Issue Resolution Report

## 1. 개요 및 문제 현상
`KafkaCaptureReceiver.java`의 `drain(...)` 메서드는 Kafka 이벤트를 수집할 때 매칭되는 레코드를 찾으면 즉시 반환하도록 구현되어 있었습니다. 이로 인해 동일한 trace context 하에서 연속적 또는 비동기적으로 여러 Kafka 이벤트가 발행될 경우, 첫 번째 이벤트가 수집되는 즉시 반환되어 이후에 곧바로 도달하는 이벤트를 미처 수집하지 못하는 레이스 컨디션이 발생할 우려가 있었습니다.

## 2. 해결 방법 및 설계
- **Settle/Quiescence Timeout 도입**:
  - `drain` 메서드 내 루프에서 matching record를 최초 또는 추가로 발견할 때마다 `lastMatchedTime`을 현재의 `System.nanoTime()`으로 갱신합니다.
  - 다음 루프의 대기(`wait`) 결정 시, 전체 마감 시간(`deadline`)까지 남은 시간과 settle timeout(100ms)까지 남은 시간 중 더 짧은 시간을 계산하여 대기합니다.
  - 대기 중 `queue.notifyAll()`을 통해 새로운 이벤트가 들어오면 다시 큐를 훑어 matching record를 추가 수집하고, settle timeout을 다시 100ms로 리셋(quiesce 갱신)합니다.
- **안전성 및 견고함 보장 (Robustness)**:
  - `wait` 대기 시간이 0ms 이하인 경우 `wait`을 호출하지 않고 탈출하도록 제어하여 무한 대기 상태(wait(0)은 무한 대기를 의미)에 빠지는 것을 방지합니다.
  - 대기 시간의 정밀도를 높이기 위해 `queue.wait(long timeoutMillis, int nanos)` API를 적용했습니다.
  - 전체 타임아웃(`deadline`)을 초과하여 불필요한 지연이 생기지 않도록 `deadline` 준수 조건을 항시 검사합니다.

## 3. 구현 내용
### KafkaCaptureReceiver.java
`drain` 메서드가 아래와 같이 개선되었습니다:
```java
    public List<CapturedRecord> drain(String traceId, long timeoutMillis) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        List<CapturedRecord> matched = new ArrayList<>();
        long settleTimeoutNanos = 100_000_000L; // 100ms settle timeout
        long lastMatchedTime = 0;

        while (true) {
            synchronized (queue) {
                boolean foundNew = false;
                Iterator<CapturedRecord> it = queue.iterator();
                while (it.hasNext()) {
                    CapturedRecord rec = it.next();
                    String recordTraceId = getTraceIdFromHeaders(rec.headers());
                    if (Objects.equals(traceId, recordTraceId)) {
                        matched.add(rec);
                        it.remove();
                        queueSize--;
                        foundNew = true;
                    }
                }

                long now = System.nanoTime();
                if (foundNew) {
                    lastMatchedTime = now;
                }

                if (now >= deadline) {
                    break;
                }

                if (!matched.isEmpty() && (now - lastMatchedTime >= settleTimeoutNanos)) {
                    break;
                }

                long remainingToDeadlineNanos = deadline - now;
                long waitNanos = remainingToDeadlineNanos;

                if (!matched.isEmpty()) {
                    long remainingToSettleNanos = settleTimeoutNanos - (now - lastMatchedTime);
                    if (remainingToSettleNanos < waitNanos) {
                        waitNanos = remainingToSettleNanos;
                    }
                }

                long waitMillis = waitNanos / 1_000_000L;
                long waitNanosRemaining = waitNanos % 1_000_000L;

                if (waitMillis <= 0 && waitNanosRemaining <= 0) {
                    break;
                }

                try {
                    queue.wait(waitMillis, (int) waitNanosRemaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        return matched;
    }
```

## 4. 검증 결과
1. **JUnit 단위 테스트 작성 및 검증 (`KafkaCaptureReceiverTest.java`)**:
   - `testSettleTimeoutAllowsAdditionalEvents` 테스트 케이스를 새로 추가하여, 첫 번째 이벤트가 수집되는 즉시 drain되지 않고 40ms의 갭을 두고 들어오는 두 번째 비동기 이벤트까지 하나의 trace context 내에서 정상적으로 함께 캡처되는지 검증했습니다.
   - 수정 이전(Red) 단계에서는 크기 1로 실패하였으나, settle timeout 수정 이후(Green) 단계에서는 2개의 이벤트를 누수 없이 정확하게 수집하는 것을 확인했습니다.
   - `./gradlew :graph-rag-builder:cleanTest :graph-rag-builder:test --tests io.graphrag.builder.run.KafkaCaptureReceiverTest` 명령으로 기존 테스트 케이스를 포함한 전체 단위 테스트의 정상 통과(SUCCESS)를 확인하였습니다.
2. **Acceptance Test 검증 (`OtelHttpCaptureAcceptanceTest.java`)**:
   - `./gradlew :graph-rag-builder:cleanTest :graph-rag-builder:test --tests io.graphrag.builder.capture.OtelHttpCaptureAcceptanceTest` 명령을 실행하여 실환경(Docker & Kafka)에서 동작하는 수용성 테스트가 올바르게 작동하고 빌드가 최종적으로 성공함(BUILD SUCCESSFUL)을 입증하였습니다.
