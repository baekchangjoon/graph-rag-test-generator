# 16 — Option A Phase 5: demo-sut E2E (seeded data 시나리오)

[`docs/12-option-a-row-snapshot-design.md`](12-option-a-row-snapshot-design.md) §7 의 검증 계획대로 capture → composer → synthesizer 전 사이클이 시드 데이터 케이스에서 동작함을 데모 SUT 에서 입증.

## 신규 파일

[`samples/demo-sut/src/test/java/io/graphrag/demo/PhaseSeedDataE2eTest.java`](../samples/demo-sut/src/test/java/io/graphrag/demo/PhaseSeedDataE2eTest.java)

## 시나리오

```
[Phase 5 E2E]                                          [기존 Phase 0 E2E와의 차이]
@BeforeEach: userRepo.save("u-1", "George")           → "data.sql 시드" 시뮬레이션
CaptureContext.set("path-seed") ← 시드 직후, 캡처 시작 → 시드 INSERT 는 capture 안 됨
POST /api/orders {"userId":"u-1", ...}
  ↓ OrdersController
  userRepo.findById("u-1")              ← SELECT users WHERE id=?     ★ Option A 신규
  orderRepo.save(...)                   ← INSERT INTO orders          (기존 Phase 0 동작)
  ↓
captured:
  [SELECT users with readResultRows=[{ID:"u-1", NAME:"George"}],     ★ Option A 신규
   INSERT orders ...]
  ↓
FixtureComposer.fromCapturedSqls():
  → INSERT INTO users (ID, NAME) VALUES (?, ?)   ★ SELECT snapshot → INSERT 합성
  → INSERT INTO orders ...                       (기존 INSERT 그대로)
  ↓
TestSynthesizer.synthesize():
  생성된 테스트의 @BeforeEach 에 두 INSERT 모두 포함
  → 깨끗한 DB 에서도 GET /api/orders 호출 시 user 존재 → 201 통과
```

## 핵심 검증 코드

```java
@Test
void capturesSelectRowSnapshotAndSynthesizesSeedInsert() throws Exception {
    // 1. capture 활성화 후 endpoint 호출
    CaptureContext.set(new CaptureContext("path-seed"));
    mvc.perform(post("/api/orders")
            .contentType(APPLICATION_JSON)
            .content(mapper.writeValueAsString(new CreateOrderRequest("u-1", 100L, "EXPRESS"))))
       .andExpect(status().isCreated());

    // 2. captured 에 SELECT (readResultRows 채워짐) 검증
    List<CapturedSql> captured = ctx.capturedSql();
    CapturedSql userSelect = captured.stream()
            .filter(s -> s.type() == SELECT && s.rawSql().toLowerCase().contains("users"))
            .findFirst().orElseThrow();
    assertThat(userSelect.readResultRows()).hasSize(1);
    Map<String, Object> row = userSelect.readResultRows().get(0);
    assertThat(row).containsEntry("ID", "u-1").containsEntry("NAME", "George");

    // 3. FixtureComposer 가 SELECT 를 INSERT fixture 로 합성
    List<FixtureStatement> fixtures = FixtureComposer.fromCapturedSqls(captured);
    assertThat(fixtures).anyMatch(f -> f.sql().toUpperCase().startsWith("INSERT INTO USERS")
                                    && f.params().contains("u-1"));
    assertThat(fixtures).anyMatch(f -> f.sql().toLowerCase().contains("insert into orders"));

    // 4. TestSynthesizer 출력에 두 INSERT + 두 DELETE 모두 포함
    String generated = TestSynthesizer.synthesize(new SynthesisInput(endpoint, captured, "pkg"));
    assertThat(generated)
        .containsIgnoringCase("INSERT INTO users")
        .contains("\"u-1\"").contains("\"George\"")
        .containsIgnoringCase("INSERT INTO orders")
        .contains(".post(\"/api/orders\")")
        .containsIgnoringCase("DELETE FROM users")
        .containsIgnoringCase("DELETE FROM orders");
}
```

## 실제 생성된 테스트 (발췌)

```java
@BeforeEach
void setUp() throws Exception {
    testId = "t-" + UUID.randomUUID().toString().substring(0, 8);
    try (Connection conn = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASS)) {
        // ★ Option A: SELECT snapshot 으로 합성된 사전 시드 INSERT
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (ID, NAME) VALUES (?, ?)")) {
            ps.setObject(1, "u-1");
            ps.setObject(2, "George");
            ps.executeUpdate();
        }
        // 기존 INSERT (captured INSERT 그대로)
        try (PreparedStatement ps = conn.prepareStatement(
                "insert into orders (amount,status,type,user_id,id) values (?,?,?,?,?)")) {
            ps.setObject(1, 100);
            ps.setObject(2, "PENDING");
            ps.setObject(3, "EXPRESS");
            ps.setObject(4, "u-1");
            ps.setObject(5, "d0d240a3-...");
            ps.executeUpdate();
        }
    }
}

@AfterEach
void cleanup() throws Exception {
    try (Connection conn = ...) {
        // FK 역순 cleanup (orders → users)
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM orders WHERE id = ?")) { ps.setObject(1, 100); ps.executeUpdate(); }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM users WHERE ID = ?")) { ps.setObject(1, "u-1"); ps.executeUpdate(); }
    }
}
```

## 검증 결과

```
$ ./gradlew :samples:demo-sut:test --tests "io.graphrag.demo.PhaseSeedDataE2eTest"
PhaseSeedDataE2eTest > capturesSelectRowSnapshotAndSynthesizesSeedInsert() PASSED
BUILD SUCCESSFUL

$ ./gradlew build
BUILD SUCCESSFUL — 89 actionable tasks, 모든 기존 Phase 0~5 E2E + 단위테스트 GREEN
```

## 관찰된 동작

1. **시드 INSERT 는 capture 안 됨** — `@BeforeEach` 의 `userRepo.save(...)` 는 `CaptureContext.set()` *이전* 이라 listener 가 noop → 의도된 동작. 즉 "운영 시드는 capture 대상이 아니다" 라는 시드 시뮬레이션 성립.

2. **SELECT * 변환 성공** — 컨트롤러가 SELECT 한 컬럼이 부분집합이어도 listener 의 `rebuildAsSelectStar` 가 `SELECT *` 로 바꿔 재실행 → 모든 컬럼 (`ID`, `NAME`) snapshot.

3. **컬럼명 case** — H2 (PostgreSQL 모드) 가 unquoted identifier 를 대문자 정규화 → snapshot key 는 `ID`, `NAME`. 생성된 INSERT SQL 도 그대로 `(ID, NAME)`. DB 에 그대로 전달됨. PostgreSQL 실 환경에서는 lowercase 가 기본 — 운영 DBMS 매칭 시 동작 일관.

4. **dedup 동작 검증** — POST /api/orders 는 한 번만 호출했지만 Hibernate 가 같은 user 를 여러 번 SELECT 할 수 있음. snapshot dedup 로직 (table+ID key) 덕에 INSERT 는 단 1회만 합성됨.

5. **cleanup 자동 합성** — `FixtureComposer.cleanupFor()` 가 SELECT-snapshot row 의 `ID` 컬럼을 PK 로 인식 → `DELETE FROM users WHERE ID = ?` 자동 생성.

## 한계 (관찰됨)

- **기존 INSERT 의 cleanup 오류**: `INSERT INTO orders (amount, status, type, user_id, id)` 에서 `bindings.get(0).value()` = 100 (amount) 을 PK 로 추정 → `DELETE FROM orders WHERE id = ?` + param 100 (잘못된 값). Hibernate 가 컬럼 순서를 알파벳/임의로 정렬한 결과. **Phase 6 또는 별개 PR** 로 PK 인식 강화 필요 (`affected_columns` 채우기 + entity metadata 활용).
- **OrdersController.create 의 자체 INSERT 와 fixture INSERT 충돌**: 합성 테스트 실행 시 fixture 가 orders 를 미리 INSERT → controller 가 새 INSERT 시도 → PK conflict 가능. → 더 정밀히는 "fixture 는 controller 가 SELECT 한 row 만, controller 가 INSERT 한 row 는 fixture 에서 제외" 정책 필요. Phase 2+ 로 deferred.

위 한계는 Option A 의 코어 (SELECT snapshot → INSERT 합성) 와 직교한 문제이며, 본 phase 의 목표 "시드 데이터를 capture 단계에서 확보하여 fixture 로 합성" 은 달성됐다.

## 다음 단계

Phase 6: petclinic 에 capture harness (BeanPostProcessor) 통합 → GET /api/owners/1 자동 캡처 → 수동 archive 제작 없이 깨끗한 Postgres 에서 합성 테스트 통과 검증.
