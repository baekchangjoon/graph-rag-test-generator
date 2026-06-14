# Stage 3b — mutating by-id 정합성 Implementation Plan

> REQUIRED SUB-SKILL: superpowers:executing-plans. 체크박스 추적.

**Goal:** mutating by-id(PUT/DELETE /{id}) 생성 테스트가 fresh DB에서 통과하도록 (1)탐색 중 요청별 시드
리셋 (2)결정성 인지 구체 어설션. spec: `docs/superpowers/specs/2026-06-15-stage3b-mutating-byid-fidelity-design.md`.

**Architecture:** `Seeds.delete` 신설 + `resetSeeds`로 mutating by-id invoker를 감싸 매 요청 전 리소스를
fresh 시드로 복원. `knownValues`(요청/시드 값)로 `assertionsFromResponse`를 equalTo/notNull 결정.

---

## File Structure
- 수정: `graph-rag-builder/src/main/java/io/graphrag/builder/run/Seeds.java` (delete 신설)
- 수정: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java` (resetSeeds + invoker 래핑)
- 수정: `test-generator/src/main/java/io/graphrag/generator/compose/FixtureComposer.java` (compose 5-arg+knownValues, assertionsFromResponse)
- 수정: `test-generator/src/main/java/io/graphrag/generator/Generator.java` (knownValues 수집 → compose)
- 수정(테스트): `.../generator/compose/FixtureComposerTest.java` (마이그레이션 + 보강)

---

### Task 1: Seeds.delete + resetSeeds (요청별 리셋)

**Files:** Modify `run/Seeds.java`, `run/EndpointExplorationRunner.java`

- [ ] **Step 1:** `Seeds.java` 확인 — `insert(Connection, DbConfig.Type, SeedRow)` 시그니처 + SqlDialect 사용 방식. 동일 패턴으로 `delete` 추가:
```java
/** 시드 행을 PK(columns[0]) 기준으로 삭제. 리셋(요청별 fresh 복원)에 쓰인다. */
public static void delete(Connection connection, DbConfig.Type dbType, SynthesizedInput.SeedRow row) {
    String sql = "DELETE FROM " + row.table() + " WHERE " + row.columns().get(0) + " = ?";
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
        ps.setObject(1, row.values().get(0));
        ps.executeUpdate();
    } catch (SQLException e) {
        throw new IllegalStateException("seed delete failed: " + row.table(), e);
    }
}
```
  (import/예외 스타일은 기존 insert와 동일하게.)

- [ ] **Step 2:** `EndpointExplorationRunner`에 `resetSeeds` 헬퍼:
```java
private void resetSeeds(List<SynthesizedInput.SeedRow> seeds) {
    // reverse-order DELETE(child→parent) 후 정순 INSERT(parent→child) — FK 안전, 멱등-insert no-op 회피.
    for (int i = seeds.size() - 1; i >= 0; i--) {
        Seeds.delete(connection, dbType, seeds.get(i));
    }
    for (SynthesizedInput.SeedRow row : seeds) {
        Seeds.insert(connection, dbType, row);
    }
}
```

- [ ] **Step 3:** `run()`에서 mutating by-id면 invoker를 리셋 데코레이터로 감싼다. 현재
  `EndpointTarget(..., httpInvoker(endpoint), ...)` 생성 직전:
```java
boolean mutatingById = !readPath && hasPathParam && !happy.seeds().isEmpty();
EndpointInvoker invoker = httpInvoker(endpoint);
if (mutatingById) {
    List<SynthesizedInput.SeedRow> resetRows = happy.seeds();
    EndpointInvoker base = invoker;
    invoker = body -> { resetSeeds(resetRows); return base.invoke(body); };
}
```
  그리고 `EndpointTarget` 생성 시 `httpInvoker(endpoint)` 대신 `invoker` 사용.
  (`EndpointInvoker`가 함수형 인터페이스 `InvocationOutcome invoke(ObjectNode)`인지 확인 — 맞으면 람다 가능.
  아니면 익명 클래스로.)

- [ ] **Step 4: 컴파일 + 단위** `./gradlew :graph-rag-builder:test` GREEN.
- [ ] **Step 5: 커밋** `feat(builder): reset by-id resource seed before each mutating request`

---

### Task 2: knownValues 기반 구체 어설션

**Files:** Modify `compose/FixtureComposer.java`, `Generator.java`, test `compose/FixtureComposerTest.java`

- [ ] **Step 1:** `FixtureComposer.compose` 5-arg에 `Set<String> knownValues` 파라미터 추가. 기존 호출
  경로(seeds 브랜치/else 브랜치) 모두 `assertionsFromResponse(path, knownValues)` 호출하도록. WS용
  3-arg `compose(pseudo, sql, tables)`는 내부에서 `compose(..., Set.of())`로 위임(또는 빈 전달).

- [ ] **Step 2:** `assertionsFromResponse` 재작성(knownValues 기반):
```java
private static List<ComposedFixture.Assertion> assertionsFromResponse(ExploredPath path,
                                                                       Set<String> knownValues) {
    List<ComposedFixture.Assertion> assertions = new ArrayList<>();
    if (path.sampleResponse() == null || path.sampleResponse().isNull()) {
        return assertions;
    }
    path.sampleResponse().fields().forEachRemaining(entry -> {
        com.fasterxml.jackson.databind.JsonNode v = entry.getValue();
        if (v.isNull()) {
            return;
        }
        String value = v.asText();
        boolean concrete = knownValues.contains(value) && !looksServerGenerated(value);
        String matcher;
        if (!concrete) {
            matcher = "notNullValue()";
        } else if (v.isIntegralNumber() || v.isBoolean()) {
            matcher = "equalTo(" + value + ")";
        } else if (v.isTextual()) {
            matcher = "equalTo(\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\")";
        } else {
            matcher = "notNullValue()";   // 실수/객체/배열 보수적
        }
        assertions.add(new ComposedFixture.Assertion(entry.getKey(), matcher));
    });
    return assertions;
}
```
  (기존 `literalValues` 계산 블록 제거 — knownValues가 대체. `BindingOrigin` import는 다른 사용처 있으면 유지.)

- [ ] **Step 3:** `Generator.generateSingle`에서 knownValues 수집 후 compose에 전달:
```java
java.util.Set<String> knownValues = new java.util.HashSet<>();
// 요청 body/param 값
if (path.sampleInput() instanceof com.fasterxml.jackson.databind.node.ObjectNode in) {
    in.fields().forEachRemaining(e -> { if (!e.getValue().isNull()) knownValues.add(e.getValue().asText()); });
}
// 시드 행 값 (offset 반영된 perPath)
for (RequiredSeed s : client.seedsForPath(pathId)) {
    knownValues.addAll(s.values());
}
// 기존 SQL LITERAL 바인딩
for (CapturedSql cs : sql) {
    cs.bindings().stream().filter(b -> b.origin() == BindingOrigin.LITERAL)
            .forEach(b -> knownValues.add(b.value()));
}
ComposedFixture fixture = new FixtureComposer().compose(path, sql, client.tables(),
        client.seedsForPath(pathId), readPath, knownValues);
```
  (import: RequiredSeed, BindingOrigin, CapturedSql — Generator에 이미 있나 확인 후 추가.)

- [ ] **Step 4: 테스트 마이그레이션 + 보강** — `FixtureComposerTest`의 기존 compose/assertions 호출을
  새 시그니처로 갱신. `assertions_*` 테스트: knownValues에 든 값 → equalTo(정수/문자열 타입), 미지값/
  서버생성 → notNull 케이스 추가.

- [ ] **Step 5: 컴파일 + 단위** `./gradlew :test-generator:test :graph-rag-builder:test` GREEN.
- [ ] **Step 6: 커밋** `feat(generator): determinism-aware concrete assertions (equalTo for input/seed values)`

---

### Task 3: 검증 (petclinic by-id 라이브 + order-service 회귀)

- [ ] **Step 1:** order-service e2e `./e2e/run-e2e.sh` → 22/22 GREEN.
- [ ] **Step 2:** petclinic 빌더 재실행 → get/put/delete-api-reservations-id 생성 →
  `.work/run-suites.sh petclinic`(fresh 라이브)로 실행. 실패 0 확인(login 플레이크는 1회 재실행으로 구분).
- [ ] **Step 3:** 생성된 PUT/GET 테스트에 `equalTo` 구체 단언 + fixture INSERT(타입 정상) 육안 확인.
- [ ] **Step 4: 커밋(있으면 결과 기록)** — spec에 "실측 결과" 섹션.

---

## Self-Review
- spec 커버: 리셋(T1)/구체어설션(T2)/검증(T3). ✅
- 치명 반영: Seeds.delete 신설(멱등 insert no-op 회피), compose 오버로드(WS 불변), knownValues 단일화·
  출처 seedsForPath(offset 정합). ✅
- 회귀: order-service e2e + 단위 마이그레이션. ✅
- 비목표: 양 arm concolic 변종(Stage 4), 실수 필드 구체단언(보수적 notNull). ✅
