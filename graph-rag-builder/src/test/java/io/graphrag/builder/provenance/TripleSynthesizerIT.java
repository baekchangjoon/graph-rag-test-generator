package io.graphrag.builder.provenance;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.oracle.InputCandidates;
import io.graphrag.builder.provenance.ProvenanceReport.GuardFact;
import io.graphrag.builder.provenance.ProvenanceReport.Origin;
import io.graphrag.builder.provenance.ProvenanceReport.ValueRef;
import io.graphrag.model.ColumnSchema;
import io.graphrag.model.ForeignKey;
import io.graphrag.model.Json;
import io.graphrag.model.TableSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
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
        String[] values = insertSql.substring(valuesOpen, insertSql.lastIndexOf(')')).split(",\\s*");
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < columns.length; i++) {
            row.put(columns[i].trim(), values[i].trim());
        }
        return row;
    }
}
