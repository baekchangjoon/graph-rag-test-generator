package io.graphrag.builder.provenance;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.SharedSpoonModel;
import io.graphrag.builder.oracle.ConcolicOracle;
import io.graphrag.builder.oracle.InputCandidates;
import io.graphrag.builder.provenance.ProvenanceReport.GuardFact;
import io.graphrag.builder.provenance.ProvenanceReport.Origin;
import io.graphrag.builder.provenance.ProvenanceReport.UnguardedField;
import io.graphrag.builder.provenance.ProvenanceReport.ValueRef;
import io.graphrag.model.ColumnSchema;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ForeignKey;
import io.graphrag.model.Json;
import io.graphrag.model.TableSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import spoon.reflect.CtModel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TripleSynthesizer 코어(REQ-005) + 공동 배치·경계 만족값(REQ-006) 검증.
 *
 * <p>golden provenance 리포트({@code golden/provenance-post-api-transfers.json}, Task 2~7 산출물)를
 * 그대로 입력으로 써서, EXISTS 가드({@code fromAccountId})와 비교 가드
 * ({@code fund_accounts.balance_amount < amount})가 co-location되는지 확인한다.
 */
class TripleSynthesizerIT {

    private ProvenanceReport loadGoldenReport() throws IOException {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("golden/provenance-post-api-transfers.json")) {
            return Json.mapper().readValue(in, ProvenanceReport.class);
        }
    }

    @Test
    @DisplayName("REQ-006: EXISTS(INPUT)·비교 가드(DB balance < INPUT amount) 공동 배치 — "
            + "seed.id=body.fromAccountId, seed.balance>=body.amount")
    void req006_jointPlacementSatisfiesGuards() throws IOException {
        ProvenanceReport report = loadGoldenReport();

        TableSchema fundAccounts = new TableSchema(
                "fund_accounts",
                List.of(
                        new ColumnSchema("id", "VARCHAR", false, true),
                        new ColumnSchema("balance_amount", "BIGINT", false, false)),
                List.of(),
                List.of());

        TripleSynthesizer synthesizer = new TripleSynthesizer();
        List<TripleCandidate> candidates = synthesizer.synthesize(
                report, BodyShape.empty(), List.of(fundAccounts), InputCandidates.empty());

        assertThat(candidates).as("최소 1개의 후보가 합성되어야 한다").hasSize(1);
        TripleCandidate candidate = candidates.get(0);
        ObjectNode body = candidate.body();

        assertThat(body.has("fromAccountId")).as("EXISTS 가드의 INPUT jsonPath가 body에 배치되어야 한다").isTrue();
        assertThat(body.has("amount")).as("비교 가드의 INPUT jsonPath가 body에 배치되어야 한다").isTrue();

        String bodyFromAccountId = body.get("fromAccountId").asText();
        long bodyAmount = body.get("amount").asLong();

        String fundAccountsInsert = candidate.seedSqlStatements().stream()
                .filter(sql -> sql.startsWith("INSERT INTO fund_accounts"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "fund_accounts seed INSERT이 생성되어야 한다: " + candidate.seedSqlStatements()));

        Map<String, String> row = parseInsertColumnsToValues(fundAccountsInsert);

        assertThat(row.get("id"))
                .as("seed INSERT의 id는 body.fromAccountId와 동일한 값(같은 리터럴)이어야 한다(EXISTS 공동 배치)")
                .isEqualTo("'" + bodyFromAccountId + "'");

        long seedBalance = Long.parseLong(row.get("balance_amount"));
        assertThat(seedBalance)
                .as("seed.balance_amount는 body.amount 이상이어야 한다"
                        + "(원본 가드 op='<'가 throw 조건이므로 negate된 '>=' 관계를 만족)")
                .isGreaterThanOrEqualTo(bodyAmount);
    }

    // ---- E2E-B1 실증(2026-07-28 RED)이 드러낸 body 형상 결함 회귀 ----

    @Test
    @DisplayName("REQ-005: collectionPaths의 접두 경로는 원소 1개짜리 JSON 배열로 합성되고, "
            + "같은 접두사의 여러 리프는 그 대표원소 하나에 병합된다(객체로 나오면 SUT 400)")
    void req005_collectionPathsBecomeJsonArraysWithMergedRepresentativeElement() {
        ProvenanceReport report = new ProvenanceReport(
                "fixture-endpoint", List.of(), List.of(
                        new UnguardedField("lineItems.sku", "String", "free-text"),
                        new UnguardedField("lineItems.amount", "int", "none")),
                List.of(), List.of("lineItems"));

        TripleCandidate candidate = new TripleSynthesizer().synthesize(
                report, BodyShape.empty(), List.of(), InputCandidates.empty()).get(0);
        ObjectNode body = candidate.body();

        assertThat(body.get("lineItems").isArray())
                .as("List<LineItem> 필드는 JSON 배열이어야 한다 — 대표원소 규약(REQ-034)의 dot-path만 보고 "
                        + "객체로 쓰면 SUT의 Jackson 역직렬화가 400으로 실패한다")
                .isTrue();
        assertThat(body.get("lineItems")).as("대표원소 1개").hasSize(1);
        assertThat(body.get("lineItems").get(0).has("sku")).isTrue();
        assertThat(body.get("lineItems").get(0).has("amount"))
                .as("같은 접두사의 두 리프는 배열을 두 번 만들지 않고 같은 대표원소에 쌓여야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("REQ-005: collectionPaths가 비면(구 리포트) 종전대로 중첩 객체로 합성한다(하위호환)")
    void req005_absentCollectionPathsKeepsLegacyNestedObjectBehaviour() {
        ProvenanceReport report = new ProvenanceReport(
                "fixture-endpoint", List.of(),
                List.of(new UnguardedField("lineItems.sku", "String", "free-text")), List.of());

        ObjectNode body = new TripleSynthesizer().synthesize(
                report, BodyShape.empty(), List.of(), InputCandidates.empty()).get(0).body();

        assertThat(body.get("lineItems").isObject()).isTrue();
        assertThat(body.get("lineItems").has("sku")).isTrue();
    }

    @Test
    @DisplayName("REQ-005: 라우팅이 값을 결정하지 못한 가드의 INPUT 피연산자도 body에 갭 마커로 존재한다 "
            + "(컨테이너 타입 피연산자는 스칼라 슬롯을 만들지 않는다)")
    void req005_everyGuardInputOperandGetsABodySlot() {
        // invoices의 실제 가드 형상: `||` 결합 논리(컨테이너 lineItems) + `!=`(UNKNOWN sum vs INPUT total).
        // 두 가드 모두 기존 라우팅으로는 값을 결정하지 못해 total이 body에서 통째로 누락됐다(400).
        GuardFact combined = new GuardFact("InvoiceController.java:18", "||",
                List.of(new ValueRef(Origin.INPUT, "lineItems", null, null, null, null, "List", null, null)));
        GuardFact sumCheck = new GuardFact("InvoiceController.java:28", "!=",
                List.of(new ValueRef(Origin.UNKNOWN, null, null, null, null, null, "int", null, null),
                        new ValueRef(Origin.INPUT, "total", null, null, null, null, "int", null, null)));
        ProvenanceReport report = new ProvenanceReport("post-api-invoices",
                List.of(combined, sumCheck), List.of(), List.of(), List.of("lineItems"));

        TripleCandidate candidate = new TripleSynthesizer().synthesize(
                report, BodyShape.empty(), List.of(), InputCandidates.empty()).get(0);

        assertThat(candidate.body().path("total").asText())
                .as("가드가 읽는 INPUT 피연산자는 결정값이 없으면 갭 마커로라도 body에 있어야 한다")
                .startsWith(TripleSynthesizer.GAP_MARKER_PREFIX);
        assertThat(candidate.body().has("lineItems"))
                .as("컨테이너 타입(List) 피연산자 자신은 스칼라 자리가 아니므로 배치하지 않는다 "
                        + "(원소 필드 경로가 대신 채운다)")
                .isFalse();
        assertThat(candidate.notes()).contains("컨테이너 타입(List)");
    }

    @Test
    @DisplayName("REQ-008: 라우팅 밖(결합 논리 등)에 있는 EXTERNAL_RESPONSE 피연산자도 stub 자리를 갖고, "
            + "같은 callSite의 필드들은 한 mapping으로 병합된다")
    void req008_allExternalOperandsGetMergedStubSlots() throws Exception {
        GuardFact prefixGuard = new GuardFact("FulfillmentController.java:27", "||",
                List.of(new ValueRef(Origin.EXTERNAL_RESPONSE, null, null, null,
                        "GET /carriers/policy", "allowedPrefix", "String", null, null)));
        GuardFact weightGuard = new GuardFact("FulfillmentController.java:32", ">",
                List.of(new ValueRef(Origin.INPUT, "parcelWeight", null, null, null, null, "int", null, null),
                        new ValueRef(Origin.EXTERNAL_RESPONSE, null, null, null,
                                "GET /carriers/policy", "maxWeight", "int", null, null)));
        ProvenanceReport report = new ProvenanceReport("post-api-fulfillment",
                List.of(prefixGuard, weightGuard), List.of(), List.of());

        TripleCandidate candidate = new TripleSynthesizer().synthesize(
                report, BodyShape.empty(), List.of(), InputCandidates.empty()).get(0);

        assertThat(candidate.stubMappings())
                .as("같은 callSite의 두 응답 필드는 mapping 2개가 아니라 1개로 병합돼야 한다 "
                        + "(SUT는 외부 호출을 한 번만 한다)")
                .hasSize(1);
        ObjectNode stub = candidate.stubMappings().get(0);
        ObjectNode jsonBody = (ObjectNode) stub.get("response").get("jsonBody");
        assertThat(jsonBody.path("allowedPrefix").asText())
                .as("결합 논리 안의 EXTERNAL 피연산자는 값을 결정할 수 없으므로 갭 마커 자리가 생겨야 한다")
                .startsWith(TripleSynthesizer.GAP_MARKER_PREFIX);
        assertThat(jsonBody.path("maxWeight").isNumber())
                .as("INPUT×EXTERNAL 비교 가드는 만족 쌍을 body/stub에 공동 배치한다(갭 마커가 아닌 결정값)")
                .isTrue();
        assertThat(candidate.body().path("parcelWeight").asInt())
                .as("negate된 관계(> → <=)를 만족해야 한다")
                .isLessThanOrEqualTo(jsonBody.get("maxWeight").asInt());
        assertThat(stub.path("response").path("headers").path("Content-Type").asText())
                .as("jsonBody 응답에는 Content-Type을 함께 등록해야 한다(없으면 RestTemplate 컨버터 선택 실패 → 500)")
                .isEqualTo("application/json");
        StubMapping.buildFrom(Json.mapper().writeValueAsString(stub));   // REQ-008 로더 호환 유지
    }

    @Test
    @DisplayName("REQ-007: 가드는 있는데 배치 가능한 INPUT dot-path가 하나도 없으면(동적 키 Map body) "
            + "빈 body를 조용히 내보내지 않고 합성 불가 사유를 notes에 남긴다")
    void req007_emptyBodyWithGuardsIsLoudlyReported() {
        GuardFact mapGuard = new GuardFact("QuotaController.java:17", "||",
                List.of(new ValueRef(Origin.INPUT, "quotas", null, null, null, null, "Map", null, null)));
        ProvenanceReport report = new ProvenanceReport(
                "post-api-quotas", List.of(mapGuard), List.of(), List.of());

        TripleCandidate candidate = new TripleSynthesizer().synthesize(
                report, BodyShape.empty(), List.of(), InputCandidates.empty()).get(0);

        assertThat(candidate.body().isEmpty()).isTrue();
        assertThat(candidate.notes())
                .as("조용한 축소 금지 — 채울 자리조차 없다는 사실이 notes에 남아야 한다")
                .contains("경고(합성 불가)")
                .contains("동적 키 Map");
    }

    @Test
    @DisplayName("REQ-006: FK NOT NULL 부모 행 재귀 시딩 — 부모 INSERT가 자식보다 먼저, "
            + "NOT NULL 컬럼은 결정적 기본값으로 채워짐")
    void req006_foreignKeyParentRowSeededBeforeChild() {
        ProvenanceReport report = existsGuardReport("transfers");

        TableSchema transfers = new TableSchema(
                "transfers",
                List.of(
                        new ColumnSchema("id", "VARCHAR", false, true),
                        new ColumnSchema("account_id", "VARCHAR", false, false)),
                List.of(new ForeignKey("account_id", "accounts", "id")),
                List.of());
        TableSchema accounts = new TableSchema(
                "accounts",
                List.of(
                        new ColumnSchema("id", "VARCHAR", false, true),
                        new ColumnSchema("name", "VARCHAR", false, false)),
                List.of(),
                List.of());

        TripleSynthesizer synthesizer = new TripleSynthesizer();
        TripleCandidate candidate = synthesizer.synthesize(
                report, BodyShape.empty(), List.of(transfers, accounts), InputCandidates.empty()).get(0);

        List<String> seedSql = candidate.seedSqlStatements();
        int accountsIdx = indexOfStatementFor(seedSql, "accounts");
        int transfersIdx = indexOfStatementFor(seedSql, "transfers");
        assertThat(accountsIdx).as("accounts(부모) INSERT가 생성되어야 한다").isGreaterThanOrEqualTo(0);
        assertThat(transfersIdx).as("transfers(자식) INSERT가 생성되어야 한다").isGreaterThanOrEqualTo(0);
        assertThat(accountsIdx)
                .as("부모(accounts) INSERT는 자식(transfers) INSERT보다 먼저 나와야 한다(순서 검증)")
                .isLessThan(transfersIdx);

        Map<String, String> transfersRow = parseInsertColumnsToValues(seedSql.get(transfersIdx));
        Map<String, String> accountsRow = parseInsertColumnsToValues(seedSql.get(accountsIdx));

        assertThat(transfersRow.get("account_id"))
                .as("자식의 FK 컬럼 값은 부모 PK 값과 동일해야 한다(FK 무결성)")
                .isEqualTo(accountsRow.get("id"));
        assertThat(accountsRow.get("name"))
                .as("부모의 NOT NULL 비-PK 컬럼은 결정적 기본값으로 채워져야 한다")
                .isNotNull();
    }

    @Test
    @DisplayName("REQ-006: 부모 테이블 스키마 부재 시 FK 컬럼에 null을 침묵 삽입하지 않고 "
            + "unresolved-fk로 기록·해당 컬럼을 INSERT에서 제외")
    void req006_missingParentSchemaExcludesColumnAndRecordsUnresolvedFk() {
        ProvenanceReport report = existsGuardReport("transfers");

        TableSchema transfers = new TableSchema(
                "transfers",
                List.of(
                        new ColumnSchema("id", "VARCHAR", false, true),
                        new ColumnSchema("account_id", "VARCHAR", false, false)),
                List.of(new ForeignKey("account_id", "accounts", "id")),
                List.of());
        // accounts(부모) 스키마를 의도적으로 tables에 포함하지 않는다.

        TripleSynthesizer synthesizer = new TripleSynthesizer();
        TripleCandidate candidate = synthesizer.synthesize(
                report, BodyShape.empty(), List.of(transfers), InputCandidates.empty()).get(0);

        assertThat(candidate.seedSqlStatements())
                .as("부모 스키마가 없으므로 accounts INSERT는 생성되지 않아야 한다")
                .noneMatch(sql -> sql.startsWith("INSERT INTO accounts"));

        String transfersInsert = candidate.seedSqlStatements().stream()
                .filter(sql -> sql.startsWith("INSERT INTO transfers"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "transfers seed INSERT가 생성되어야 한다: " + candidate.seedSqlStatements()));
        Map<String, String> transfersRow = parseInsertColumnsToValues(transfersInsert);

        assertThat(transfersRow)
                .as("미해결 FK 컬럼(account_id)은 INSERT 컬럼 목록에서 제외되어야 한다(침묵 null 금지)")
                .doesNotContainKey("account_id");
        assertThat(candidate.notes())
                .as("notes에 unresolved-fk 근거가 남아야 한다")
                .contains("unresolved-fk: transfers.account_id -> accounts.id");
    }

    @Test
    @DisplayName("REQ-007: 갭 마커 — 가드 없는 free-text 필드는 body에 JSON 문자열 마커, "
            + "가드가 결정 못한 NOT NULL numeric 컬럼은 seed.sql에 작은따옴표 리터럴 마커, "
            + "결정 가능한 값(PK)에는 마커가 없다")
    void req007_gapMarkersOnlyAtUndecidablePositions() {
        TableSchema orders = new TableSchema(
                "orders",
                List.of(
                        new ColumnSchema("id", "VARCHAR", false, true),
                        new ColumnSchema("risk_score", "BIGINT", false, false)),
                List.of(),
                List.of());
        GuardFact existsGuard = new GuardFact("Fixture.java:1", "EXISTS",
                List.of(new ValueRef(Origin.INPUT, "orderId", "orders", null, null, null,
                        "String", null, null)));
        ProvenanceReport report = new ProvenanceReport("fixture-endpoint", List.of(existsGuard),
                List.of(new UnguardedField("note", "String", "free-text")), List.of());

        TripleSynthesizer synthesizer = new TripleSynthesizer();
        List<TripleCandidate> candidates = synthesizer.synthesize(
                report, BodyShape.empty(), List.of(orders), InputCandidates.empty());
        assertThat(candidates).as("오라클 후보가 없으므로 조합은 정확히 1개여야 한다").hasSize(1);
        TripleCandidate candidate = candidates.get(0);

        assertThat(candidate.body().get("note").asText())
                .as("가드 없는 free-text unguarded 필드는 body에 JSON 문자열 갭 마커로 표기되어야 한다")
                .isEqualTo("__AGENT_FILL__{type:String, semanticHint:free-text, guard:none}");

        String ordersInsert = candidate.seedSqlStatements().stream()
                .filter(sql -> sql.startsWith("INSERT INTO orders"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("orders seed INSERT가 생성되어야 한다"));
        assertThat(isWellFormedSingleStatementInsert(ordersInsert))
                .as("seed.sql은 마커를 포함해도 파싱 가능한 단일 INSERT 문장이어야 한다: " + ordersInsert)
                .isTrue();

        Map<String, String> row = parseInsertColumnsToValues(ordersInsert);
        assertThat(row.get("risk_score"))
                .as("어떤 가드도 결정하지 못한 NOT NULL numeric 컬럼은 작은따옴표 문자열 리터럴 갭 마커여야 한다"
                        + "(컬럼 타입 무관 — SQL 파싱 유지)")
                .isEqualTo("'__AGENT_FILL__{type:long, semanticHint:none, guard:none}'");
        assertThat(row.get("id"))
                .as("EXISTS 가드로 결정 가능한 PK 값에는 마커가 없어야 한다")
                .doesNotContain("__AGENT_FILL__");
    }

    @Test
    @DisplayName("REQ-007: 가드가 결정 못한 NOT NULL TEXT 컬럼은 padding이 아니라 갭 마커여야 한다"
            + "(내용에 계약이 있는 자유형 페이로드 — VARCHAR 라벨 컬럼은 종전대로 padding)")
    void req007_freeFormTextColumnsBecomeGapMarkersNotPadding() {
        // 실측 근거(mindgraph): graph_record.nodes_json은 TEXT NOT NULL이고 핸들러가
        // objectMapper.readValue로 읽는다. padding 문자열("seed-nodes_json")을 넣으면 존재 가드는
        // 통과하지만 역직렬화가 던져 500이 된다 — 404가 500이 될 뿐 2xx는 열리지 않는다.
        // NOT NULL을 만족하는 것과 값이 유효한 것은 다르므로, padding은 그 자체가 침묵 삽입이다.
        TableSchema graphRecord = new TableSchema(
                "graph_record",
                List.of(
                        new ColumnSchema("diary_id", "VARCHAR", false, true),
                        new ColumnSchema("user_id", "VARCHAR", false, false),
                        new ColumnSchema("nodes_json", "TEXT", false, false)),
                List.of(),
                List.of());
        GuardFact existsGuard = new GuardFact("GraphService.java:81", "EXISTS",
                List.of(new ValueRef(Origin.INPUT, "diaryId", "graph_record", null, null, null,
                        "String", null, null)));
        ProvenanceReport report = new ProvenanceReport("fixture-endpoint", List.of(existsGuard),
                List.of(), List.of());

        List<TripleCandidate> candidates = new TripleSynthesizer().synthesize(
                report, BodyShape.empty(), List.of(graphRecord), InputCandidates.empty());
        String insert = candidates.get(0).seedSqlStatements().stream()
                .filter(sql -> sql.startsWith("INSERT INTO graph_record"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("graph_record seed INSERT가 생성되어야 한다"));
        assertThat(isWellFormedSingleStatementInsert(insert))
                .as("마커를 포함해도 파싱 가능한 단일 INSERT여야 한다: " + insert)
                .isTrue();

        Map<String, String> row = parseInsertColumnsToValues(insert);
        assertThat(row.get("nodes_json"))
                .as("자유형 TEXT 컬럼은 도구가 내용 계약을 알 수 없으므로 에이전트가 채워야 한다")
                .contains("__AGENT_FILL__")
                .contains("semanticHint:nodes_json");
        assertThat(row.get("user_id"))
                .as("길이 제한이 있는 VARCHAR 라벨 컬럼은 종전대로 padding이다(과잉 마커 방지)")
                .doesNotContain("__AGENT_FILL__");
    }

    @Test
    @DisplayName("REQ-008: stubs.json = WireMock mapping 스키마 — 기존 StubMapping.buildFrom으로 예외 없이 로드된다")
    void req008_stubMappingLoadableByExistingLoader() throws Exception {
        GuardFact negatedEquality = new GuardFact("Controller.java:37", "!",
                List.of(
                        new ValueRef(Origin.UNKNOWN, null, null, null, null, null, "String", null, "CLEAR"),
                        new ValueRef(Origin.EXTERNAL_RESPONSE, null, null, null,
                                "POST /fraud/check", "status", "String", null, null)));
        ProvenanceReport report = new ProvenanceReport("fixture-endpoint", List.of(negatedEquality), List.of(), List.of());

        TripleSynthesizer synthesizer = new TripleSynthesizer();
        TripleCandidate candidate = synthesizer.synthesize(
                report, BodyShape.empty(), List.of(), InputCandidates.empty()).get(0);

        assertThat(candidate.stubMappings()).as("EXTERNAL_RESPONSE 부정 등가 가드는 stub mapping 1개를 만들어야 한다")
                .hasSize(1);
        ObjectNode stub = candidate.stubMappings().get(0);
        String json = Json.mapper().writeValueAsString(stub);

        StubMapping mapping = StubMapping.buildFrom(json);   // 예외 없이 로드되어야 함(REQ-008)
        assertThat(mapping.getRequest().getMethod().getName()).isEqualTo("POST");
        assertThat(mapping.getRequest().getUrlPath()).isEqualTo("/fraud/check");
        assertThat(mapping.getResponse().getStatus()).isEqualTo(200);
        assertThat(mapping.getResponse().getJsonBody().get("status").asText()).isEqualTo("CLEAR");
    }

    @Test
    @DisplayName("REQ-008: callSite가 '<HTTP메서드> <path>' 형식이 아니면(class#method 폴백) stub을 만들지 않고 사유를 notes에 남긴다")
    void req008_nonHttpCallSiteSkipsStubCreationWithNote() {
        GuardFact negatedEquality = new GuardFact("Controller.java:37", "!",
                List.of(
                        new ValueRef(Origin.UNKNOWN, null, null, null, null, null, "String", null, "CLEAR"),
                        new ValueRef(Origin.EXTERNAL_RESPONSE, null, null, null,
                                "TransferService#checkFraud", "status", "String", null, null)));
        ProvenanceReport report = new ProvenanceReport("fixture-endpoint", List.of(negatedEquality), List.of(), List.of());

        TripleSynthesizer synthesizer = new TripleSynthesizer();
        TripleCandidate candidate = synthesizer.synthesize(
                report, BodyShape.empty(), List.of(), InputCandidates.empty()).get(0);

        assertThat(candidate.stubMappings())
                .as("class#method 폴백 형식 callSite는 stub을 만들면 안 된다")
                .isEmpty();
        assertThat(candidate.notes())
                .as("stub 생성 불가 사유가 notes에 남아야 한다")
                .contains("TransferService#checkFraud")
                .contains("stub 생성 불가");
    }

    @Test
    @DisplayName("REQ-033: 후보 수 cap(기본 4)과 우선순위 정렬 — 오라클 후보가 cap을 넘으면 상위 4개만, "
            + "cand-01은 정렬 기준(결정 필드 수 내림차순→사전순)의 최상위 조합이어야 한다")
    void req033_candidateCapAndDeterministicPriorityOrder() {
        ProvenanceReport report = new ProvenanceReport("fixture-endpoint", List.of(),
                List.of(new UnguardedField("status", "String", "enum-ish")), List.of());
        // 5개의 결정 후보(+갭 마커 1개 = 총 6 조합) > cap(4) — REQ-033가 요구하는 "cap을 초과할 만큼
        // 후보 조합이 가능한 리포트" 시나리오.
        InputCandidates oracle = new InputCandidates(
                Map.of(), Map.of("status", new java.util.TreeSet<>(List.of("E", "C", "A", "D", "B"))));

        TripleSynthesizer synthesizer = new TripleSynthesizer();
        List<TripleCandidate> candidates = synthesizer.synthesize(
                report, BodyShape.empty(), List.of(), oracle);

        assertThat(candidates).as("cap(기본 4)을 넘지 않아야 한다").hasSize(4);
        List<String> statuses = candidates.stream()
                .map(c -> c.body().get("status").asText())
                .toList();
        assertThat(statuses)
                .as("모든 결정 후보(5개)가 갭 마커 후보(decidedCount=0)보다 우선순위가 높으므로, "
                        + "cap 이내 4개는 전부 오라클 결정값이어야 하고 사전순으로 A,B,C,D여야 한다(cand-01=A)")
                .containsExactly("A", "B", "C", "D");
        assertThat(candidates.get(0).notes())
                .as("cand-01의 notes에는 순번·결정 필드 수 trace가 남아야 한다")
                .contains("cand-01");
    }

    @Test
    @DisplayName("REQ-033: unguarded 필드 2개 × 옵션 각 3개(cross product 9 > cap 4)에서도 "
            + "cand-01이 결정 필드 수·사전순 규칙의 최상위 조합이고 정확히 4개만 산출되어야 한다 "
            + "(단일 필드 5옵션 케이스는 MAX_OPTIONS_PER_FIELD 사전-절단과 겹쳐 rank+cap 로직을 "
            + "충분히 검증하지 못하므로, 결정 필드 수가 0/1/2로 실제로 갈리는 다중 필드 시나리오로 보강)")
    void req033_multiFieldCrossProductRanksByDecidedCountThenLexOrder() {
        ProvenanceReport report = new ProvenanceReport("fixture-endpoint", List.of(),
                List.of(
                        new UnguardedField("fieldA", "String", "hintA"),
                        new UnguardedField("fieldB", "String", "hintB")),
                List.of());
        // 필드당 옵션 3개(갭 마커 1 + 오라클 결정값 2) × 필드 2개 = cross product 9 > cap(4).
        // 조합별 결정 필드 수가 0(마커,마커)/1(한쪽만 결정)/2(둘 다 결정)로 실제로 갈리므로,
        // "결정 필드 수 내림차순" 정렬이 실제로 동작해야만 상위 4개가 전부 decidedCount=2인 조합이 된다.
        InputCandidates oracle = new InputCandidates(Map.of(), Map.of(
                "fieldA", new java.util.TreeSet<>(List.of("A2", "A1")),
                "fieldB", new java.util.TreeSet<>(List.of("B2", "B1"))));

        TripleSynthesizer synthesizer = new TripleSynthesizer();
        List<TripleCandidate> candidates = synthesizer.synthesize(
                report, BodyShape.empty(), List.of(), oracle);

        assertThat(candidates).as("cross product 9 > cap(4)이므로 정확히 4개만 산출되어야 한다").hasSize(4);
        List<List<String>> pairs = candidates.stream()
                .map(c -> List.of(c.body().get("fieldA").asText(), c.body().get("fieldB").asText()))
                .toList();
        assertThat(pairs)
                .as("결정 필드 수(둘 다 결정=2)가 가장 높은 4개 조합만 살아남아야 하고, 그 안에서는 "
                        + "정규화 키(필드 값을 순서대로 이어붙인 문자열) 사전순이어야 한다 — "
                        + "결정 필드 수가 1이나 0인 조합(마커 포함)은 전부 제외되어야 한다")
                .containsExactly(
                        List.of("A1", "B1"),
                        List.of("A1", "B2"),
                        List.of("A2", "B1"),
                        List.of("A2", "B2"));
        for (TripleCandidate candidate : candidates) {
            assertThat(candidate.body().toString())
                    .as("cap 이내 4개 후보는 전부 결정 필드 수 2(둘 다 오라클 결정값)이어야 하므로 갭 마커가 없어야 한다")
                    .doesNotContain("__AGENT_FILL__");
        }
        assertThat(candidates.get(0).notes())
                .as("cand-01은 결정 필드 2/2(unguarded 기준) 조합이어야 한다")
                .contains("cand-01")
                .contains("결정 필드 2/2");
    }

    @Test
    @DisplayName("REQ-032: DERIVED 파생 루트 필드에 concolic 해가 body 결정값으로 배치된다 "
            + "(score*2==84 → 42, 갭 마커 아님)")
    void req032_derivedConcolicSolutionPlacedAsDecidedBodyValue() throws IOException {
        // provenance는 실제 ProvenanceIndexer가 derived 픽스처에서 산출하고(=DERIVED + derivedFrom=[score]),
        // 오라클 해는 실제 ConcolicOracle이 동일 형상의 바이트코드에서 도출한다 — 두 채널을 모두
        // 실산출로 묶어 REQ-032 수용기준(provenance + synthesize-triple)을 검증한다.
        ProvenanceReport report = analyzeDerivedFixture("create");
        InputCandidates oracle = new ConcolicOracle().analyzeClassBytes(classBytesOf(ScoreGuardSut.class));

        assertThat(oracle.numeric().get("score"))
                .as("전제: concolic 오라클이 소스에 리터럴로 없는 해 42(=84/2)를 도출해야 한다")
                .contains(42L);

        List<TripleCandidate> candidates = new TripleSynthesizer().synthesize(
                report, BodyShape.empty(), List.of(), oracle);

        assertThat(candidates)
                .as("DERIVED 피연산자의 파생 루트(score)에 concolic 해 42가 JSON 숫자 결정값으로 "
                        + "배치된 후보가 있어야 한다(갭 마커 문자열이 아니라)")
                .anyMatch(c -> c.body().path("score").isNumber() && c.body().path("score").asLong() == 42L);

        TripleCandidate decided = candidates.stream()
                .filter(c -> c.body().path("score").isNumber() && c.body().path("score").asLong() == 42L)
                .findFirst()
                .orElseThrow();
        assertThat(decided.body().toString())
                .as("결정값이 배치된 자리에는 갭 마커가 남으면 안 된다")
                .doesNotContain("__AGENT_FILL__");
        assertThat(decided.notes())
                .as("notes에 DERIVED 파생 루트의 오라클 결정값 배치 근거(trace)가 남아야 한다")
                .contains("derived(score) -> 오라클 결정값 42");
    }

    @Test
    @DisplayName("REQ-032: concolic이 못 푸는 파생(비선형 다변수 score*factor)은 그 파생 루트 위치가 갭 마커")
    void req032_unsolvableDerivedFallsBackToGapMarker() throws IOException {
        ProvenanceReport report = analyzeDerivedFixture("createNonlinear");
        InputCandidates oracle = new ConcolicOracle().analyzeClassBytes(classBytesOf(NonlinearGuardSut.class));

        assertThat(oracle.numeric())
                .as("전제: 변수×변수(비선형) 비교는 오라클이 보수적으로 bail해 해를 내지 못한다")
                .doesNotContainKeys("score", "factor");

        List<TripleCandidate> candidates = new TripleSynthesizer().synthesize(
                report, BodyShape.empty(), List.of(), oracle);

        assertThat(candidates).as("결정값 옵션이 없으므로 조합은 정확히 1개여야 한다").hasSize(1);
        ObjectNode body = candidates.get(0).body();
        assertThat(body.get("score").asText())
                .as("concolic이 못 푸는 파생의 루트 필드는 UNKNOWN과 동일하게 갭 마커로 표기되어야 한다")
                .startsWith("__AGENT_FILL__");
        assertThat(body.get("factor").asText())
                .as("다변수 파생의 나머지 루트 필드도 동일하게 갭 마커여야 한다")
                .startsWith("__AGENT_FILL__");
        assertThat(body.get("score").asText())
                .as("갭 마커의 guard 필드에는 어떤 가드에서 파생됐는지 근거가 실려야 한다(REQ-007)")
                .contains("guard:DERIVED ==");
    }

    /** derived 픽스처를 실제 {@link ProvenanceIndexer}로 분석한 리포트(ProvenanceIndexerIT와 동일 관례). */
    private static ProvenanceReport analyzeDerivedFixture(String handlerMethod) {
        Path src = Path.of("src/test/resources/provenance-fixtures/derived");
        Endpoint endpoint = new Endpoint("ep-derived", "POST", "/api/derived",
                "io.graphrag.fixture.derived.DerivedController", handlerMethod, List.of(), false);
        return new ProvenanceIndexer().analyze(SharedSpoonModel.build(src), endpoint, 3);
    }

    /** 테스트 클래스패스에 컴파일되어 있는 클래스의 바이트코드(ConcolicOracle 입력). */
    private static byte[] classBytesOf(Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("class bytes not found on test classpath: " + resource);
            }
            return in.readAllBytes();
        }
    }

    /** derived 픽스처 {@code ScoreRequest}와 동일 형상의 접근자 홀더(concolic 입력 필드 인식용). */
    public static final class ScoreHolder {
        private final Integer score;

        public ScoreHolder(Integer score) {
            this.score = score;
        }

        public Integer getScore() {
            return score;
        }
    }

    /** derived 픽스처 {@code create}와 동일한 선형 파생 가드({@code score * 2 == 84})의 바이트코드 원본. */
    public static final class ScoreGuardSut {
        public static String create(ScoreHolder req) {
            if (req.getScore() * 2 == 84) {
                throw new IllegalStateException("score threshold breached");
            }
            return "OK";
        }
    }

    /** derived 픽스처 {@code PairRequest}와 동일 형상의 2필드 접근자 홀더. */
    public static final class PairHolder {
        private final Integer score;
        private final Integer factor;

        public PairHolder(Integer score, Integer factor) {
            this.score = score;
            this.factor = factor;
        }

        public Integer getScore() {
            return score;
        }

        public Integer getFactor() {
            return factor;
        }
    }

    /** derived 픽스처 {@code createNonlinear}와 동일한 비선형 파생 가드의 바이트코드 원본. */
    public static final class NonlinearGuardSut {
        public static String create(PairHolder req) {
            if (req.getScore() * req.getFactor() == 84) {
                throw new IllegalStateException("product threshold breached");
            }
            return "OK";
        }
    }

    /**
     * {@code "INSERT INTO t (c1, c2) VALUES (v1, v2);"} 형태가 구조적으로 파싱 가능한 단일 문장인지
     * 검증(괄호 균형·세미콜론 종결·따옴표 짝 맞음). 이 모듈은 JSqlParser 의존성이 없으므로(REQ-010/T1
     * 범위에서 도입) 실제 SQL 파서 대신 구조 검증으로 "SQL 파싱 유지"를 확인한다.
     */
    private static boolean isWellFormedSingleStatementInsert(String sql) {
        if (!sql.startsWith("INSERT INTO ") || !sql.endsWith(");")) {
            return false;
        }
        int depth = 0;
        boolean inQuote = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
            } else if (!inQuote && c == '(') {
                depth++;
            } else if (!inQuote && c == ')') {
                depth--;
                if (depth < 0) {
                    return false;
                }
            } else if (!inQuote && c == ';' && i != sql.length() - 1) {
                return false;   // 다중 문장 금지
            }
        }
        return depth == 0 && !inQuote;
    }

    /** table을 명시적으로 지정한 단일 EXISTS 가드(jsonPath="transferId")짜리 최소 리포트(FK 픽스처 전용). */
    private static ProvenanceReport existsGuardReport(String table) {
        GuardFact existsGuard = new GuardFact("Fixture.java:1", "EXISTS",
                List.of(new ValueRef(Origin.INPUT, "transferId", table, null, null, null,
                        "String", null, null)));
        return new ProvenanceReport("fixture-endpoint", List.of(existsGuard), List.of(), List.of());
    }

    /** seedSql에서 {@code "INSERT INTO <table> "}로 시작하는 첫 문장의 인덱스(없으면 -1). */
    private static int indexOfStatementFor(List<String> seedSql, String table) {
        for (int i = 0; i < seedSql.size(); i++) {
            if (seedSql.get(i).startsWith("INSERT INTO " + table + " ")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * {@code "INSERT INTO t (c1, c2) VALUES (v1, v2);"} 형태를 컬럼명→값-리터럴(quote 포함 원문) 맵으로 파싱.
     * 테스트 전용 — 값에 콤마/괄호가 섞이지 않는 단순 seed 리터럴만 다룬다.
     */
    private static Map<String, String> parseInsertColumnsToValues(String insertSql) {
        String columnsPart = insertSql.substring(insertSql.indexOf('(') + 1, insertSql.indexOf(')'));
        String[] columns = columnsPart.split(",\\s*");
        int valuesOpen = insertSql.indexOf("VALUES (") + "VALUES (".length();
        String valuesPart = insertSql.substring(valuesOpen, insertSql.lastIndexOf(')'));
        List<String> values = splitTopLevelCommaRespectingQuotes(valuesPart);
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < columns.length; i++) {
            row.put(columns[i].trim(), values.get(i).trim());
        }
        return row;
    }

    /**
     * 최상위(따옴표 밖) 콤마로만 분리한다 — 갭 마커(REQ-007)처럼 값 문자열 안에 콤마가 포함된 리터럴을
     * (naive split(",")로는 오분할되는 문제) 올바르게 하나의 값으로 유지하기 위함. 이스케이프된 따옴표
     * ('')는 이 테스트 전용 파서 범위에서 다루지 않는다(현재 값들은 내부에 따옴표를 포함하지 않음).
     */
    private static List<String> splitTopLevelCommaRespectingQuotes(String valuesPart) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < valuesPart.length(); i++) {
            char c = valuesPart.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
                current.append(c);
            } else if (c == ',' && !inQuote) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    @Test
    @DisplayName("REQ-006: 인덱서가 실제로 산출한 리포트를 그대로 먹여도 각 EXISTS가 자기 테이블에 시드된다 "
            + "(생산자↔소비자 계약 — 서로 다른 엔티티 2개)")
    void req006_indexerReportRoutesEachExistsGuardToItsOwnTable() {
        // 이 테스트가 없으면 합성기 단위 테스트가 인덱서가 **생산하지 않는** 모양
        // (INPUT 피연산자가 table을 들고 있는 형태)만 검증하게 되어, 생산자↔소비자 계약
        // 불일치가 통과한다. 여기서는 인덱서를 실제로 돌려 나온 리포트를 그대로 넘긴다.
        CtModel model = SharedSpoonModel.build(
                Path.of("src/test/resources/provenance-fixtures/multi-entity"));
        Endpoint endpoint = new Endpoint("post-api-multi-entity", "POST", "/api/multi-entity",
                "io.graphrag.fixture.multientity.MultiEntityController", "create", List.of(), false);
        ProvenanceReport report = new ProvenanceIndexer().analyze(model, endpoint, 3);

        // 전제 확인: 리포트에 서로 다른 DB_READ 테이블이 2개 — 전역 유일성 폴백이 성립하지 않는 조건.
        assertThat(report.guards().stream()
                .flatMap(g -> g.operands().stream())
                .filter(v -> v.origin() == Origin.DB_READ && v.table() != null)
                .map(ValueRef::table)
                .distinct())
                .as("서로 다른 엔티티 2개를 조회하므로 DB_READ 테이블이 2개여야 한다(이 테스트의 전제)")
                .containsExactlyInAnyOrder("app_user", "fund_account");

        TableSchema appUser = new TableSchema("app_user",
                List.of(new ColumnSchema("id", "VARCHAR", false, true)), List.of(), List.of());
        TableSchema fundAccount = new TableSchema("fund_account",
                List.of(new ColumnSchema("id", "VARCHAR", false, true),
                        new ColumnSchema("balance_amount", "BIGINT", false, false)),
                List.of(), List.of());

        List<TripleCandidate> candidates = new TripleSynthesizer().synthesize(
                report, BodyShape.empty(), List.of(appUser, fundAccount), InputCandidates.empty());
        String seedSql = String.join("\n", candidates.get(0).seedSqlStatements());

        assertThat(seedSql)
                .as("두 존재 가드가 각자의 테이블에 시드돼야 한다 — 전역 폴백만 쓰면 둘 다 skip된다: " + seedSql)
                .contains("INSERT INTO app_user")
                .contains("INSERT INTO fund_account");
    }


    @Test
    @DisplayName("REQ-005: 하위 리프가 있는(=객체인) 경로에는 스칼라 갭 마커를 놓지 않는다"
            + "(슬롯 처리 순서에 의존하지 않는다)")
    void req005_objectValuedGuardOperandDoesNotGetScalarMarker() {
        // req.getAddress() == null 같은 가드는 jsonPath="address", javaType="Address"인 INPUT을
        // 만든다. 그 자리에 문자열 갭 마커를 놓으면 SUT Jackson이 객체 자리에 문자열을 받아 400이
        // 된다. 현재는 unguarded 리프가 먼저 처리돼 bodyHasPath로 걸러지는 "순서" 덕에 가려져
        // 있는데, 순서가 뒤집히면 그대로 발현한다 — 데이터로 판정해 순서 의존을 없앤다.
        GuardFact objectGuard = new GuardFact("Fixture.java:10", "==",
                List.of(new ValueRef(Origin.INPUT, "address", null, null, null, null,
                        "Address", null, null)));
        ProvenanceReport report = new ProvenanceReport("fixture-endpoint",
                List.of(objectGuard),
                List.of(new UnguardedField("address.city", "String", "free-text")),
                List.of());

        TripleCandidate candidate = new TripleSynthesizer().synthesize(
                report, BodyShape.empty(), List.of(), InputCandidates.empty()).get(0);

        assertThat(candidate.body().get("address").isObject())
                .as("하위 리프(address.city)가 있으므로 address는 객체여야 한다: " + candidate.body())
                .isTrue();
        assertThat(candidate.notes())
                .as("스칼라 슬롯을 만들지 않은 사유가 기록돼야 한다(조용한 skip 금지)")
                .contains("address")
                .contains("하위 리프");
    }

}
