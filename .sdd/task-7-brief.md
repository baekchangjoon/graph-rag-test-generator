### Task 7: order-service 샘플 확장 및 E2E DoD 검증 (`samples/order-service` / `e2e`)

**Files:**
- Modify: `samples/order-service/src/main/java/io/graphrag/sample/orders/OrderController.java`
- Test: Run E2E: `e2e/` 디렉토리 내의 E2E 테스트 스크립트 실행

**Interfaces:**
- Consumes: SUT HTTP POST `/api/orders`
- Produces: SUT 카프카 레코드 발행 (`order.events` 토픽)

- [ ] **Step 1: Modify SUT for Integration testing (DoD)**
  `OrderController.java`에서 주문 생성(`POST /api/orders`) 시 기존 로직에 카프카로 메시지를 발행하는 `KafkaTemplate` 로직을 임의 추가합니다. (테스트 목적의 로직 기입)
  ```java
  // KafkaTemplate을 주입받아 order-events 토픽으로 주문 완료 JSON 이벤트를 발행하는 코드 작성
  ```
- [ ] **Step 2: Run full build pipeline and verify generated test PASS**
  빌더 실행하여 새로운 `GraphAsset` 캡처 후 테스트 클래스 자동 합성 -> 생성된 E2E JUnit 테스트 클래스를 가동합니다.
  Expected: Generated test compiles successfully and PASS (Green)

- [ ] **Step 3: Commit**
  ```bash
  git add samples/order-service/
  git commit -m "test: modify order-service sample to emit kafka message and verify full pipeline"
  ```
