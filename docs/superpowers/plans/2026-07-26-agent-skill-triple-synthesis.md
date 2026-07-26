# 에이전트 스킬 기반 삼중 합성 (Phase A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 다중 가드(입력·DB·외부응답)를 통과하는 깊은 happy path를 여는 삼중 후보 {body, seed.sql, stubs.json}를 정적 재귀 분석으로 합성하고, trial 루프로 실행-확정해 기존 RestAssured 블랙박스 TC 생성 경로로 승격한다.

**Architecture:** 결정적 코드(빌더 CLI 서브커맨드 `provenance`/`synthesize-triple`/`trial` + T1 로더/검증 게이트 + T3 확정 run)가 탐색·합성·시험을 수행하고, 에이전트 스킬 3종(SKILL.md)은 갭 마커만 채운다. 성공 삼중은 repo 커밋(캐시-커밋)되어 CI가 결정적으로 소비한다.

**Tech Stack:** Java 17, Spoon(정적분석), JSqlParser(신규 의존성), WireMock, JaCoCo, Testcontainers, JUnit 5.

**참조 문서 (구현 전 필독):**
- spec: `docs/superpowers/specs/2026-07-26-agent-skill-triple-synthesis-design.md`
- 요구사항: `docs/superpowers/requirements/2026-07-26-agent-skill-triple-synthesis-requirements.md`
  (**각 task 완료 시 이 문서의 추적 매트릭스 상태를 직접 갱신한다** — 🔴→🟡(red 작성)→🟢(green))

## Global Constraints

- 무-fabrication: TC에 들어가는 것은 확정 run(캡처 on)의 관측물뿐. 삼중은 후보일 뿐이다.
- 회귀 0: `GRB_TRIAL=off` 또는 게이트 미발화 시 산출물은 현행과 정규화-동등(REQ-022).
- 결정성: Random/시간 의존 금지. 모든 정렬·순번은 결정적 기준.
- 갭 마커 계약: body/stubs는 JSON 문자열 `"__AGENT_FILL__{...}"`, seed.sql은 작은따옴표 리터럴 `'__AGENT_FILL__{...}'`.
- seed.sql 검증은 JSqlParser만(정규식 금지, 기존 testlib `SqlTableParser` 재사용 금지).
- 신규 CLI 옵션: `--provenance-depth`(기본 3), `--trial-budget`(기본 8), `--triple-store`, `--triple-candidates`, `--attach-allow-seed`, `--confirm-non-production`. env ablation: `GRB_TRIAL=off`.
- 커밋 메시지는 repo 관례(한국어 요약 + REQ 태그, 예: `feat(provenance): ... [REQ-001]`).
- 각 task 완료 = 해당 unit/IT green + 요구사항명세 매트릭스 갱신 + 커밋.

---

### Task 1: fixture 착륙·보강 + outer red 고정

**REQ-IDs:** REQ-028

**Files:**
- Cherry-pick 대상(원본: `origin/worktree-feat-llm-body-resynthesis` 커밋 b60b9a3):
  `samples/order-service/src/main/java/io/graphrag/sample/orders/{Account.java, AccountRepository.java, FulfillmentController.java, InvoiceController.java, QuotaController.java, TransferController.java}`, `samples/order-service/src/main/resources/application.yml`(추가분), `samples/order-service/src/main/resources/data.sql`(추가분)
- Modify: `samples/order-service/src/main/java/io/graphrag/sample/orders/TransferController.java` (보강)
- Create: `samples/order-service/src/main/java/io/graphrag/sample/orders/FraudClient.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/FixtureBaselineE2E.java`

**Interfaces:**
- Produces: `POST /api/transfers` — body `{fromAccountId: String, amount: long, note: String, items: [{sku: String, qty: int}]}`. 가드: ① `accountRepository.findById(fromAccountId)` 미존재→404, ② `account.getBalance() < amount`→422, ③ `fraudClient.check(...)` 응답 `status != "CLEAR"`→409, ④ note 자유 텍스트, ⑤ items[0].qty ≤ 0→422(중첩 DTO 가드 — REQ-034용). 모두 통과→201.
- Produces: `Account` 엔티티 — `@Table(name = "fund_accounts")` + `@Column(name = "balance_amount") long balance` (REQ-004 오버라이드 검증용).
- Produces: `FraudClient` — `RestTemplate`로 `${external.fraud.url}/fraud/check` POST, 응답 DTO `FraudResult(String status)`.

- [ ] **Step 1: fixture cherry-pick**

```bash
git checkout origin/worktree-feat-llm-body-resynthesis -- \
  samples/order-service/src/main/java/io/graphrag/sample/orders/Account.java \
  samples/order-service/src/main/java/io/graphrag/sample/orders/AccountRepository.java \
  samples/order-service/src/main/java/io/graphrag/sample/orders/FulfillmentController.java \
  samples/order-service/src/main/java/io/graphrag/sample/orders/InvoiceController.java \
  samples/order-service/src/main/java/io/graphrag/sample/orders/QuotaController.java \
  samples/order-service/src/main/java/io/graphrag/sample/orders/TransferController.java
git diff origin/worktree-feat-llm-body-resynthesis~20..b60b9a3 -- samples/order-service/src/main/resources/ | git apply --3way || true
```
(리소스 파일은 충돌 시 수동 병합 — `application.yml`의 신규 키·`data.sql`의 accounts 시드만 가져온다.)

- [ ] **Step 2: TransferController 보강** — 아래 형태로 수정(404/422/409/201 + 중첩 items 가드 + note):

```java
@PostMapping("/api/transfers")
public ResponseEntity<?> create(@RequestBody CreateTransferRequest req) {
    Account account = accountRepository.findById(req.fromAccountId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "account not found"));
    if (account.getBalance() < req.amount()) {
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient balance");
    }
    if (req.items() == null || req.items().isEmpty() || req.items().get(0).qty() <= 0) {
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid items");
    }
    FraudClient.FraudResult fraud = fraudClient.check(req.fromAccountId(), req.amount());
    if (!"CLEAR".equals(fraud.status())) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "fraud check failed");
    }
    return ResponseEntity.status(201).body(Map.of("id", UUID.randomUUID().toString(), "note", req.note()));
}
public record CreateTransferRequest(String fromAccountId, long amount, String note, List<TransferItem> items) {}
public record TransferItem(String sku, int qty) {}
```

`Account`에 `@Table(name = "fund_accounts")`·`@Column(name = "balance_amount")`가 없으면 추가한다. `FraudClient`는 `@Component` + `RestTemplate` + `@Value("${external.fraud.url:http://localhost:9999}")`. `application.yml`에 `external.fraud.url: ${EXTERNAL_FRAUD_URL:http://localhost:9999}` 추가(탐색 환경이 env로 WireMock 주입 — 기존 `EXTERNAL_INVENTORY_URL` 관례와 동일).

- [ ] **Step 3: outer red E2E 작성** — `FixtureBaselineE2E`(기존 `BuilderIntegrationTest` 부팅 패턴 재사용):

```java
@Test @DisplayName("REQ-028: 현행 합성으로 transfers 깊은-happy 2xx 미도달 (outer red 전제)")
void req028_currentSynthesisCannotReachDeepHappy() throws Exception {
    GraphAsset graph = buildOrderServiceGraph();   // 기존 헬퍼: 트리플 미적용 현행 빌드
    List<ExploredPath> transferPaths = graph.paths().stream()
            .filter(p -> p.endpointId().equals("post-api-transfers")).toList();
    assertThat(transferPaths).noneMatch(p -> p.expectedStatus() / 100 == 2
            && p.outcome() == Outcome.Kind.SUCCESS);
}
```

- [ ] **Step 4: 실행 — green 확인** (이 테스트는 "미도달"을 고정하므로 현행 코드에서 즉시 green이어야 한다. red면 fixture 가드가 현행 합성으로 뚫린다는 뜻 — 가드를 강화해 다시 고정)

Run: `./gradlew :graph-rag-builder:test --tests FixtureBaselineE2E`

- [ ] **Step 5: order-service 기존 e2e 무회귀 확인**: `cd e2e && ./run-e2e.sh` (또는 `./gradlew :e2e:test`) — 기존 테스트 green 유지.

- [ ] **Step 6: 매트릭스 REQ-028 🟢 갱신 + Commit**: `feat(fixture): transfers 깊은-happy fixture 착륙·보강 + outer red 고정 [REQ-028]`

---

### Task 2: provenance 리포트 모델

**REQ-IDs:** REQ-001(모델 부분), REQ-003(스키마)

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/provenance/ProvenanceReport.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/provenance/ProvenanceReportTest.java`

**Interfaces:**
- Produces:

```java
public record ProvenanceReport(String endpointId, List<GuardFact> guards,
                               List<UnguardedField> unguarded, List<Unresolved> unresolved) {
    public record GuardFact(String at, String op, List<ValueRef> operands) {}   // at="TransferService.java:44" 형식 "<file>:<line>"
    public record ValueRef(Origin origin, String jsonPath, String table, String column,
                           String callSite, String stubField, String javaType,
                           String semanticHint, String literal) {}
    public enum Origin { INPUT, DB_READ, EXTERNAL_RESPONSE, DERIVED, UNKNOWN }
    public record UnguardedField(String jsonPath, String javaType, String semanticHint) {}
    public record Unresolved(String location, Reason reason, String targetType) {}
    public enum Reason { NO_CLASSPATH, REFLECTION, PROXY, MULTI_IMPL, DEPTH_CAP }
}
```

- JSON 직렬화는 `io.graphrag.model.Json.mapper()` 재사용. null 필드는 직렬화 제외(`@JsonInclude(NON_NULL)`).

- [ ] **Step 1: round-trip 실패 테스트 작성** (`ProvenanceReportTest#roundTrip`) — 위 레코드로 직렬화→역직렬화 동등 단언. **Step 2:** 실행 FAIL(클래스 부재) 확인. **Step 3:** 레코드 구현. **Step 4:** green 확인. **Step 5:** Commit `feat(provenance): 리포트 모델 [REQ-001/003]`

---

### Task 3: 재귀 슬라이서 코어 — 호출그래프·가드 수집·INPUT 태깅

**REQ-IDs:** REQ-002, REQ-001(INPUT 부분)

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/provenance/ProvenanceIndexer.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/provenance/ProvenanceIndexerIT.java`

**Interfaces:**
- Consumes: `SharedSpoonModel.build(SourceRoots)` → `CtModel`(기존), `ConstraintExtractor.reachableMethods`(1-hop 선례 — 코드 참조용, 직접 재사용하지 않고 일반화 재구현), Task 2의 `ProvenanceReport`.
- Produces: `public ProvenanceReport analyze(CtModel model, Endpoint endpoint, int maxDepth)` — 핸들러 `CtMethod`에서 시작해 호출 그래프를 DFS(방문 집합·depth cap). `throw`/`ResponseStatusException`으로 이어지는 `CtIf` 조건식을 GuardFact로 수집. 핸들러 파라미터(@RequestBody 필드 getter 체인)에서 데이터플로 도달하는 피연산자는 `Origin.INPUT` + jsonPath(dot-path).

- [ ] **Step 1: 실패 테스트** — 미니 fixture 소스(테스트 리소스 디렉토리 `src/test/resources/provenance-fixtures/basic/`에 컨트롤러+서비스 2클래스)로:

```java
@Test @DisplayName("REQ-002: 상호 재귀 소스에서 depth cap으로 종료, cap 초과는 UNKNOWN")
void req002_recursionTerminates() {
    ProvenanceReport report = analyzeFixture("recursive", 3);   // a()→b()→a() 순환 + depth 4 체인
    assertThat(report.unresolved()).anyMatch(u -> u.reason() == Reason.DEPTH_CAP);
}
@Test void inputOperandTagged() {
    ProvenanceReport report = analyzeFixture("basic", 3);       // if (req.getAmount() < 1) throw 422
    assertThat(report.guards()).anyMatch(g -> g.operands().stream()
            .anyMatch(v -> v.origin() == Origin.INPUT && v.jsonPath().equals("amount")));
}
```

- [ ] **Step 2:** FAIL 확인 → **Step 3:** `ProvenanceIndexer` 구현 — DFS: `Deque<CtMethod>` + `Set<String>` visited(시그니처 키), depth 추적. `CtIf` 스캔은 then/else 블록에 `CtThrow` 또는 `ResponseEntity.status(4xx|5xx)` 반환이 있는 경우만 가드로 채택. 피연산자 분해는 `CtBinaryOperator` 재귀. INPUT 판정: 피연산자 표현식의 루트 변수가 핸들러 파라미터(또는 그 getter 체인)면 INPUT. → **Step 4:** green → **Step 5:** Commit `feat(provenance): 재귀 슬라이서 코어 + INPUT 태깅 [REQ-002]`

---

### Task 4: DB_READ 태깅 + @Column/@Table 오버라이드 매핑

**REQ-IDs:** REQ-004, REQ-001(DB_READ 부분)

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/provenance/ProvenanceIndexer.java`
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/provenance/JpaColumnResolver.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/provenance/ProvenanceIndexerIT.java` (추가)

**Interfaces:**
- Consumes: 기존 camelToSnake 관례(`ReadInputSynthesizer.camelToSnake` — 로직 참조, 공용 유틸로 추출 금지·중복 구현 허용 범위는 snake 변환 1함수).
- Produces: `JpaColumnResolver.resolve(CtTypeReference<?> entityType, String getterName) → TableColumn(table, column)` — `@Table(name=)`/`@Column(name=)` 어노테이션 우선, 없으면 camelToSnake 폴백. `ProvenanceIndexer`는 repository(`JpaRepository` 서브타입/`@Repository`) 반환값에서 시작하는 getter 체인을 DB_READ로 태깅.

- [ ] **Step 1: 실패 테스트**

```java
@Test @DisplayName("REQ-004: @Table/@Column 오버라이드가 ValueRef.table/column에 반영")
void req004_jpaOverrides() {
    ProvenanceReport report = analyzeFixture("jpa-override", 3);
    // fixture: @Table(name="fund_accounts") + @Column(name="balance_amount") long balance
    assertThat(report.guards()).anyMatch(g -> g.operands().stream().anyMatch(v ->
            v.origin() == Origin.DB_READ
            && v.table().equals("fund_accounts") && v.column().equals("balance_amount")));
}
```

- [ ] **Step 2:** FAIL → **Step 3:** 구현(repository 호출 인식: 선언 타입이 `JpaRepository` 상속이거나 `@Repository`; MyBatis mapper는 기존 `MapperXmlIndexer` 결과의 mapper 인터페이스 FQN 집합으로 인식) → **Step 4:** green → **Step 5:** Commit `feat(provenance): DB_READ + @Column/@Table 매핑 [REQ-004]`

---

### Task 5: EXTERNAL_RESPONSE·DERIVED·UNKNOWN 태깅

**REQ-IDs:** REQ-003, REQ-032, REQ-001(EXTERNAL 부분)

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/provenance/ProvenanceIndexer.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/provenance/ProvenanceIndexerIT.java` (추가)

**Interfaces:**
- Consumes: `ResponseDtoIndexer`가 인덱싱하는 외부 callSite(HTTP 클라이언트 반환 DTO) — callSite id 규칙 재사용.
- Produces: RestTemplate/WebClient/Feign 반환값 getter 체인 → `Origin.EXTERNAL_RESPONSE` + callSite/stubField. 출처값의 산술/문자열 파생(`CtBinaryOperator`로 INPUT/DB_READ 피연산자를 감싼 식) → `Origin.DERIVED`(concolic 위임 표시는 javaType 유지로 충분 — C2가 판단). 인터페이스 다구현체: 호출 대상 선언 타입이 인터페이스이고 모델 내 구현체가 2개 이상이면 UNKNOWN + `Unresolved(location, MULTI_IMPL, targetType)`.

- [ ] **Step 1: 실패 테스트 3건** — external fixture(`fraudClient.check(...).getStatus()` 비교 → EXTERNAL_RESPONSE + stubField="status"), derived fixture(`req.getScore()*2 == 84` → DERIVED), multi-impl fixture(구현체 2개 인터페이스 → UNKNOWN + unresolved `MULTI_IMPL`). 각각 `@DisplayName("REQ-003: …")`/`("REQ-032: …")`.
- [ ] **Step 2:** FAIL → **Step 3:** 구현 → **Step 4:** green → **Step 5:** Commit `feat(provenance): EXTERNAL/DERIVED/UNKNOWN 태깅 [REQ-003/032]`

---

### Task 6: DTO 중첩 재귀 전개

**REQ-IDs:** REQ-034

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/provenance/ProvenanceIndexer.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/provenance/ProvenanceIndexerIT.java` (추가)

- [ ] **Step 1: 실패 테스트**

```java
@Test @DisplayName("REQ-034: 중첩 DTO(List 원소 필드) 가드가 dot-path로 태깅")
void req034_nestedDtoRecursion() {
    ProvenanceReport report = analyzeFixture("nested", 3);
    // fixture: if (req.getItems().get(0).getQty() <= 0) throw 422
    assertThat(report.guards()).anyMatch(g -> g.operands().stream().anyMatch(v ->
            v.origin() == Origin.INPUT && v.jsonPath().equals("items[0].qty")));
}
```

- [ ] **Step 2:** FAIL → **Step 3:** 구현 — getter 체인 → dot-path 변환기에 List 인덱스 접근(`get(0)`/first-element 대표)·Map 키 접근을 추가. 기존 `JsonPaths` dot-path 규약과 동일 표기 사용. → **Step 4:** green → **Step 5:** Commit `feat(provenance): DTO 중첩 재귀 전개 [REQ-034]`

---

### Task 7: `provenance` CLI 서브커맨드 + golden E2E

**REQ-IDs:** REQ-001

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java` (서브커맨드 분기 + `--provenance-depth` 파싱 — 기존 `parseArgs` options 맵 관례)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/ProvenanceCliE2E.java`
- Test resource: `graph-rag-builder/src/test/resources/golden/provenance-post-api-transfers.json`

**Interfaces:**
- Produces: `java -jar builder.jar provenance --sut-src <dir> --endpoint 'POST /api/transfers' --provenance-depth 3 --out <file>` → `provenance-report.json`. (SUT 부팅 불필요 — 순수 정적. 기존 `--sut-src`/`--endpoint` glob 파싱 재사용.)

- [ ] **Step 1: 실패 E2E** — order-service 소스로 CLI 실행(프로세스 or 메인 직접 호출) → golden 파일과 JSON 정규화 비교(`@DisplayName("REQ-001: …")`). golden은 처음엔 기대 수작성(가드 3+items 가드, unguarded note).
- [ ] **Step 2:** FAIL → **Step 3:** BuilderCli 배선(args[0]=="provenance"면 인덱싱 경로만 수행 후 리포트 출력·exit) → **Step 4:** green → **Step 5:** 매트릭스 REQ-001~004/032/034 🟢 갱신 + Commit `feat(cli): provenance 서브커맨드 + golden E2E [REQ-001]`

---

### Task 8: TripleSynthesizer 코어 — 라우팅·공동 배치·경계값

**REQ-IDs:** REQ-005(코어), REQ-006

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/provenance/TripleSynthesizer.java`
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/provenance/TripleCandidate.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/provenance/TripleSynthesizerIT.java`

**Interfaces:**
- Consumes: `ProvenanceReport`(Task 2), `BodyShape`(기존), `TableSchema`(기존), `satisfy(op, literal)` 로직(InputMutator 선례 — 동일 시맨틱 재구현 허용), `ConcolicOracle`(기존 — DERIVED 해).
- Produces:

```java
public record TripleCandidate(ObjectNode body, List<String> seedSqlStatements,
                              List<ObjectNode> stubMappings, String notes) {}
public List<TripleCandidate> synthesize(ProvenanceReport report, BodyShape shape,
                                        List<TableSchema> tables, InputCandidates oracle)
```

- 공동 배치: 존재 가드(`findById(input)`)→ 같은 값 s를 body[jsonPath]와 seed INSERT PK에; 비교 가드(`DB col OP input`)→ OP를 만족하는 (col값, body값) 쌍(GE면 col=input=100 등 동치 우선).

- [ ] **Step 1: 실패 테스트** (`@DisplayName("REQ-006: …")`) — transfers 리포트 입력 → body.fromAccountId == seed INSERT의 id 값, seed balance ≥ body amount 단언. **Step 2:** FAIL → **Step 3:** 구현 → **Step 4:** green → **Step 5:** Commit `feat(synth): 삼중 라우팅 + 공동 배치 [REQ-006]`

---

### Task 9: 갭 마커·cap/정렬·WireMock mapping·notes + `synthesize-triple` CLI

**REQ-IDs:** REQ-005, REQ-007, REQ-008, REQ-033

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/provenance/TripleSynthesizer.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java` (`synthesize-triple` 서브커맨드, `--triple-store`)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/provenance/TripleSynthesizerIT.java` (추가), `graph-rag-builder/src/test/java/io/graphrag/builder/cli/TripleSynthesisE2E.java`

**Interfaces:**
- Produces: 마커 문법 — body/stubs jsonBody: `"__AGENT_FILL__{type:String, semanticHint:free-text, guard:none}"`; seed.sql: `'__AGENT_FILL__{type:long, semanticHint:none, guard:none}'`. stub mapping 형식: `{"request":{"method":"POST","urlPath":"/fraud/check"},"response":{"status":200,"jsonBody":{"status":"CLEAR"}}}` (기존 `StubMapping.buildFrom` 호환 — `HttpCaptureServer.loadStubs` 참조). 후보 cap=4, 정렬: 결정 필드 수 내림차순→사전순(결정적). 디렉토리 산출: `<store>/<endpointId>/cand-01..NN/{body.json, seed.sql, stubs.json, notes.md}`.

- [ ] **Step 1: 실패 테스트 3건** — REQ-007(마커 위치·SQL 파싱 가능), REQ-008(`StubMapping.buildFrom(json)` 예외 없음), REQ-033(cap 4·cand-01 최우선). **Step 2:** FAIL → **Step 3:** 구현 + CLI 배선(`synthesize-triple --report <file> --triple-store <dir>`) → **Step 4:** green + `TripleSynthesisE2E#REQ-005`(CLI로 cand-01 4파일 생성 + notes trace) green → **Step 5:** 매트릭스 REQ-005~008/033 🟢 + Commit `feat(synth): 갭 마커·cap·WireMock mapping + CLI [REQ-005/007/008/033]`

---

### Task 10: T1 검증 게이트 — 마커-diff·JSqlParser 화이트리스트·스키마·PII

**REQ-IDs:** REQ-009, REQ-010, REQ-011, REQ-012

**Files:**
- Modify: `gradle/libs.versions.toml` + `graph-rag-builder/build.gradle.kts` — JSqlParser 의존성 추가 (`com.github.jsqlparser:jsqlparser:5.1`)
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/provenance/TripleValidator.java`
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/provenance/SeedSqlWhitelist.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/provenance/{TripleGateIT.java, SeedSqlWhitelistIT.java}`, `graph-rag-builder/src/test/java/io/graphrag/builder/cli/TripleGateE2E.java`

**Interfaces:**
- Produces:

```java
public record ValidationResult(boolean accepted, boolean needsHumanReview, List<String> reasons) {}
public ValidationResult validate(Path candidateDir, Path toolBaseDir, ProvenanceReport report, BodyShape shape)
```

- 마커-diff: base(도구 생성본, `--triple-store`에 함께 보존되는 `base/` 사본)와 후보를 비교 — body/stubs: JSON 트리 재귀 비교, 마커였던 노드만 값 변경 허용; seed.sql: JSqlParser로 양쪽 파싱→(table, column→value) 맵 비교, 마커(`'__AGENT_FILL__…'`)였던 컬럼만 변경 허용.
- 화이트리스트: `CCJSqlParserUtil.parse` 결과가 `Insert`가 아니면 reject; `Statements` 2개 이상 reject; 테이블이 report의 DB_READ 테이블 집합 밖이면 reject.
- PII: 마커 위치에 채워진 값만 스캔 — 패턴: `01\d-?\d{3,4}-?\d{4}`(휴대전화), `\d{6}-?[1-4]\d{6}`(주민번호), `@(gmail|naver|daum|kakao)\.com`(실도메인 이메일; example.com류 허용). 히트→`needsHumanReview=true`(승격 차단).

- [ ] **Step 1: 실패 테스트** — REQ-009(비마커 body 변경/비마커 seed 값 변경 reject, 마커만 채움 통과), REQ-010(우회 3종: `INSERT ...; -- x\nDELETE ...` reject / `/* DELETE */` 포함 다중문 reject / `VALUES ('DELETE FROM x')` 통과 + 비지목 테이블 reject + Postgres/MySQL/MariaDB 대표 INSERT 3건 판정), REQ-011(BodyShape 외 필드·mapping 외 키 reject), REQ-012(휴대전화 히트→needsHumanReview, `probe@example.com` 통과). 각 `@DisplayName("REQ-0xx: …")`.
- [ ] **Step 2:** FAIL → **Step 3:** 구현 → **Step 4:** green(+`TripleGateE2E#REQ-009` CLI 레벨) → **Step 5:** 매트릭스 REQ-009~012 🟢 + Commit `feat(gate): T1 검증 게이트 [REQ-009/010/011/012]`

---

### Task 11: 저장 레이아웃·T1 로더 + 캡처-off no-op scope

**REQ-IDs:** REQ-031, REQ-015

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/provenance/TripleStore.java` (레이아웃·순번·promoted/failed 이동)
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java` — `doSendWithScope`에 no-op capture scope 오버로드(SQL scope 미개설·JaCoCo dump 스킵·미병합)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/provenance/TripleStoreLayoutIT.java`, `graph-rag-builder/src/test/java/io/graphrag/builder/run/TrialCaptureOffIT.java`

**Interfaces:**
- Produces: `TripleStore.candidates(endpointId) → List<Path>` / `promote(candDir)` / `fail(candDir, digest)` — `<root>/<endpointId>/{cand-NN|promoted/cand-NN|failed/cand-NN}` 순번 유지·덮어쓰기 금지. `EndpointInvoker` 경로에 `invokeTrial(JsonNode body)` — 기존 `doSend` 코어 재사용하되 `sqlCapture.begin()` 미호출·coverage dump 미수행 분기.

- [ ] **Step 1: 실패 테스트** — REQ-031(promote 시 순번 보존·중복 순번 없음·이동 후 원본 부재), REQ-015(trial invoke 후 cumulativeCoverage/캡처 교환에 미반영 — 기존 fake 인프라(`OutcomeGatingTest` 패턴) 재사용). **Step 2:** FAIL → **Step 3:** 구현 → **Step 4:** green → **Step 5:** 매트릭스 REQ-031/015 🟢 + Commit `feat(trial): TripleStore + 캡처-off no-op scope [REQ-031/015]`

---

### Task 12: `trial` CLI(T2) — 시퀀스·digest·역매핑·예산

**REQ-IDs:** REQ-013, REQ-014, REQ-016

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/provenance/{TrialRunner.java, FailureDigest.java}`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java` (`trial` 서브커맨드, `--trial-budget` 기본 8)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/provenance/TrialDigestIT.java`, `graph-rag-builder/src/test/java/io/graphrag/builder/cli/TrialCliE2E.java`

**Interfaces:**
- Produces:

```java
public record FailureDigest(int status, String outcomeKind, JsonNode responseBody,
                            String logExcerpt, String stackExcerpt,
                            String mappedGuard, ObjectNode toolSuggestion) {}
```

- 시퀀스(REQ-013): ① 기존 happy 시드 정리(현행 `resetSeeds` reverse-DELETE 경로 재사용) → ② 후보 seed.sql INSERT(삽입 (table, pk) 추적) → ③ stubs 등록(`HttpCaptureServer.registerStub(StubMapping.buildFrom(...))`, 종료 시 `removeStub`) → ④ `invokeTrial(body)` → 판정.
- 역매핑(REQ-014): SUT 로그 구간(기존 `logOffset`/`readLogRange`)에서 스택 프레임 `at <fqn>.<method>(<File>:<line>)`을 정규식 추출→report 가드 위치와 대조; 실패 시 응답/로그 메시지 문자열과 가드 메시지(throw 인자 리터럴) 부분일치 매칭; 둘 다 실패면 `mappedGuard=null`. toolSuggestion: mappedGuard의 op가 NUMERIC 비교면 경계 만족 패치(`{"seed.sql": {"column": "...", "value": ...}}`) 산출.
- 예산(REQ-016): trial CLI가 후보 목록을 순회하며 budget 소진 시 전체 `failed/` 이동 + 최종 digest 저장 + exit code 3(비-promoted).

- [ ] **Step 1: 실패 테스트** — REQ-014(fake invoker+로그로 mappedGuard/제안/null 폴백 3케이스), REQ-013(`TrialCliE2E`: fixture SUT 부팅 후 유효 후보→promoted 이동·이중 INSERT 없음), REQ-016(전부 실패 후보→failed/+보고서+exit 3). **Step 2:** FAIL → **Step 3:** 구현 → **Step 4:** green → **Step 5:** 매트릭스 REQ-013/014/016 🟢 + Commit `feat(trial): T2 CLI + digest·역매핑·예산 [REQ-013/014/016]`

---

### Task 13: 관측 필드 — EndpointExploration 확장

**REQ-IDs:** REQ-021

**Files:**
- Modify: `shared-model/src/main/java/io/graphrag/model/ExplorationReport.java`
- Test: `shared-model/src/test/java/io/graphrag/model/EndpointExplorationTest.java` (추가)

**Interfaces:**
- Produces: `EndpointExploration`에 필드 추가 — `int trialCount, boolean tripleAdopted, Map<String,Integer> tripleRejected, List<String> staleTriples`. 기존 8-인자 canonical → 12-인자 canonical + **8-인자 backward-compat 생성자**(신규 필드 0/false/빈 컬렉션) — 기존 6/7-인자 생성자 체인 관례 유지(파일 내 기존 패턴 그대로).

- [ ] **Step 1: 실패 테스트** — 신규 필드 round-trip + 구 JSON(신규 필드 부재) 역직렬화 호환(`@DisplayName("REQ-021: …")`). **Step 2:** FAIL → **Step 3:** 구현 → **Step 4:** `./gradlew :shared-model:test :graph-rag-builder:compileJava` green(기존 호출자 무수정 컴파일 확인) → **Step 5:** 매트릭스 REQ-021 🟢 + Commit `feat(model): EndpointExploration trial 관측 필드 [REQ-021]`

---

### Task 14: explore 게이트 통합 — promoted 소비→확정 run→ExploredPath

**REQ-IDs:** REQ-018(빌더 부분), REQ-019, REQ-020, REQ-035, REQ-017

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java` — base happy invoke FAILURE 시: `--triple-candidates`의 promoted 존재→T1 검증→trial 1회 재확인→성공 시 그 삼중을 base로 채택(시드/스텁 적용 상태에서 현행 explore 진행), 실패 시 staleTriples 기록+현행 회귀
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java` — `--triple-candidates` 파싱, 인덱싱에 없는 endpointId 감지(REQ-035), trial 구간 직렬화(fan-out 경로에서 trial 적용 endpoint는 직렬 큐로 처리)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/TriplePromotionIT.java`, `graph-rag-builder/src/test/java/io/graphrag/builder/run/ParallelTrialRegressionIT.java`

- [ ] **Step 1: 실패 테스트** — REQ-019(trial 성공→확정 run 상이 시 후보 미채택+사유), REQ-020(불일치 promoted→staleTriples 기록+산출 동일), REQ-035(미존재 endpointId→trial 없이 stale 기록), REQ-017(parallelism 2 구성에서 산출 동일 — 기존 fan-out 테스트 패턴). **Step 2:** FAIL → **Step 3:** 구현 → **Step 4:** green → **Step 5:** 매트릭스 REQ-017/019/020/035 🟢 + Commit `feat(run): explore 게이트 통합 [REQ-018/019/020/035/017]`

---

### Task 15: attach 안전 게이트

**REQ-IDs:** REQ-023, REQ-024, REQ-025

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/provenance/TrialRunner.java` + `BuilderCli.java` (`--attach-allow-seed`, `--confirm-non-production` 파싱·이중 게이트)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/provenance/{AttachSeedGateIT.java, AttachStubSkipIT.java}`

- [ ] **Step 1: 실패 테스트** — REQ-023(플래그 0/1/2개 → 미적용·미적용·적용 + 사유 기록), REQ-024(역-DELETE 실패 주입(FK 자식 행) → promoted 차단 + 잔존 (table, pk) 리포트), REQ-025(attach 구성 + EXTERNAL_RESPONSE 후보 → registerStub 미호출·skip 사유 기록 — fake HttpCaptureServer로 검증). **Step 2:** FAIL → **Step 3:** 구현(attach 여부는 기존 환경 기술자 — `AttachedComposeEnvironment` 사용 여부 — 로 판정) → **Step 4:** green → **Step 5:** 매트릭스 REQ-023~025 🟢 + Commit `feat(attach): seed 이중 opt-in·역-DELETE 차단·스텁 skip [REQ-023/024/025]`

---

### Task 16: ablation 회귀 0 (정규화-동등)

**REQ-IDs:** REQ-022

**Files:**
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/TrialAblationE2E.java`

- [ ] **Step 1: 실패(또는 즉시 green 목표) E2E** — 동일 fixture SUT로 ① `GRB_TRIAL=off` 빌드 ② 후보 부재 빌드 ③ (기준) 게이트 코드 진입 전 동작. graph.json·exploration-report를 정규화 비교(신규 필드 기본값 키 제외 목록 명시, 기존 Graph set-equiv 비교 유틸 패턴 재사용). `@DisplayName("REQ-022: …")`.
- [ ] **Step 2~4:** 차이 발견 시 게이트 누수 수정 → 차이 0 green. **Step 5:** 매트릭스 REQ-022 🟢 + Commit `test(ablation): GRB_TRIAL=off·미발화 정규화-동등 [REQ-022]`

---

### Task 17: SKILL.md 3종 + 구조 검사

**REQ-IDs:** REQ-026

**Files:**
- Create: `.claude/skills/provenance-analysis/SKILL.md`, `.claude/skills/triple-synthesis/SKILL.md`, `.claude/skills/trial-loop/SKILL.md`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/provenance/SkillPackagingTest.java`

**Interfaces:**
- Produces: 각 SKILL.md frontmatter — `name: <skill-name>`, `description: <영어 트리거 문장>`. 본문(한국어): CLI 실행 명령(정확한 gradle/jar 커맨드), 산출물 경로, **선행 산출물 부재 시 선행 스킬 실행 가드 지시**, **"마커만 채워라 — 마커 외 값 수정 금지"**, PII 금지(합성값만), 채운 값 사유 주석 규칙, trial-loop에는 toolSuggestion 우선 적용·UNKNOWN 실패만 창작·예산 규율.

- [ ] **Step 1: 실패 구조 검사 테스트** — 3파일 존재 + frontmatter `name:`/`description:` + 문구 포함(`"마커만"`, 선행 가드 문구) 단언(`@DisplayName("REQ-026: …")`). **Step 2:** FAIL → **Step 3:** SKILL.md 3종 작성(spec §4.2~4.4의 SKILL.md 절 내용을 실지시문으로) → **Step 4:** green → **Step 5:** 매트릭스 REQ-026 🟢 + Commit `feat(skills): SKILL.md 3종 + 구조 검사 [REQ-026]`

---

### Task 18: promoted 부트스트랩 + 완주 E2E + e2e 스크립트 배선

**REQ-IDs:** REQ-018, REQ-009(E2E 레벨 확인)

**Files:**
- Create: `e2e/triples/post-api-transfers/promoted/cand-01/{body.json, seed.sql, stubs.json, notes.md}` — **사람 갭필로 부트스트랩**(note 필드만 채움; spec §10 허용)
- Modify: `e2e/run-e2e.sh`(또는 해당 gradle 태스크) — 빌더 호출에 `--triple-candidates e2e/triples` 추가
- Test: `e2e` 스위트에 `TriplePromotionE2E`(생성 TC 중 transfers 2xx TC가 라이브 SUT에 green임을 확인하는 기존 e2e 패턴)

- [ ] **Step 1:** Task 9 CLI로 base 삼중 생성 → note 마커만 손으로 채워 promoted로 커밋. **Step 2: 실패 E2E** — 빌더 전체 실행 후 graph.json에 `post-api-transfers` 2xx ExploredPath 존재 + 생성 TC green 단언(`@DisplayName("REQ-018: …")`). **Step 3:** 배선 수정으로 green. **Step 4:** 전체 e2e 스위트 무회귀 + 누수 게이트(`e2e/check-no-leak.sh`) 통과. **Step 5:** 매트릭스 REQ-018/009 🟢 + Commit `feat(e2e): promoted 부트스트랩 + 완주 E2E [REQ-018]`

---

### Task 19: 수동 실증 절차서 + 문서 동기화

**REQ-IDs:** REQ-027, REQ-029, REQ-030 (절차 정의 — 실증 실행은 별도 세션), 문서 게이트

**Files:**
- Create: `docs/superpowers/reports/2026-07-26-triple-synthesis-manual-evidence.md` — E2E-B1(에이전트 완주: 스킬 순서·diff 검사 커맨드·기록 양식), E2E-B2(petclinic A/B: 동일-jar 커맨드·판정 분기), E2E-B3(attach 플래그 조합 체크리스트) 절차서
- Modify: `docs/03-graph-rag-builder.md`(신규 CLI 옵션·서브커맨드 절 추가), `docs/coverage-progress.md`(Phase A 항목 자리), `README.md`(해당 시)

- [ ] **Step 1:** 절차서 작성(각 실증의 실행 커맨드·판정 기준·기록 위치를 요구사항 수용기준 그대로 옮김). **Step 2:** 사용자-대면 문서 갱신. **Step 3:** REQ-027/029/030은 절차서 완료 시점에 매트릭스 **🟡(절차 준비)** 로 표시 — 🟢 전환은 실제 실증 기록 후. **Step 4:** Commit `docs: 수동 실증 절차서 + 사용자 문서 동기화 [REQ-027/029/030]`

---

## 완료 정의 (plan 레벨)

1. 요구사항명세 추적 매트릭스: CI 대상 REQ 전부 🟢, REQ-027/029/030은 실증 기록으로 🟢.
2. 전 회귀 스윕 green: `./gradlew check` + `e2e/run-e2e.sh`(order-service) + petclinic 정적/실측 스윕 + parallelism>1 구성. 컨테이너·프로세스 누수 게이트 통과.
3. PR 전 게이트: spec-compliance 리뷰 → code-quality 리뷰(`pr-review-toolkit:code-reviewer`) → 문서 동기화 확인.
