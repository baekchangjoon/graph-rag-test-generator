### Task 6: Test Generator 및 Mustache 템플릿 확장 (`test-generator`)

**Files:**
- Modify: 
  * `test-generator/src/main/java/io/graphrag/generator/Generator.java`
  * `test-generator/src/main/resources/templates/test-class.mustache` (또는 관련 HTTP 테스트 생성 템플릿)
- Test: Modify: `test-generator/src/test/java/io/graphrag/generator/GeneratorTest.java`

**Interfaces:**
- Consumes: `CapturedEventEmit`
- Produces: 
  * `Generator.generate` (카프카 단언문이 포함된 Java 테스트 클래스 코드 파일 합성)

- [ ] **Step 1: Write the failing test**
  `GeneratorTest.java`에 `CapturedEventEmit`이 존재하는 `ExploredPath`를 입력으로 넣어 테스트 소스 코드를 생성했을 때, `kafkaHelper.subscribe`, `kafkaHelper.consumeNextRecord` 및 `JSONAssert` 단언 코드가 격리된 `{ ... }` 중괄호 블록 내에 이스케이프되어 올바르게 합성되는지 단언하는 테스트 케이스를 추가합니다.
- [ ] **Step 2: Run test to verify it fails**
  Run: `./gradlew :test-generator:test --tests io.graphrag.generator.GeneratorTest`
  Expected: FAIL

- [ ] **Step 3: Write minimal implementation**
  * `Generator.java`에서 Mustache 모델 맵에 넘겨줄 JSON payload에 `jsonEscape` 처리를 추가합니다.
  * `test-class.mustache` 템플릿 내에 API 호출(given 블록) 전 `kafkaHelper.subscribe`를 호출하고, API 호출 완료 후 `{ ... }` 스코프 블록을 열어 `consumeNextRecord`와 `JSONAssert`를 통해 검증하는 코드 생성 템플릿 로직을 추가합니다.
- [ ] **Step 4: Run test to verify it passes**
  Run: `./gradlew :test-generator:test --tests io.graphrag.generator.GeneratorTest`
  Expected: PASS

- [ ] **Step 5: Commit**
  ```bash
  git add test-generator/
  git commit -m "feat: implement test assertion generation for captured kafka produce events"
  ```

---

