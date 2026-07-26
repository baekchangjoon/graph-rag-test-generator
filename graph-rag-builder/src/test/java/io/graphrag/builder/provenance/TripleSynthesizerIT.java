package io.graphrag.builder.provenance;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.oracle.InputCandidates;
import io.graphrag.model.ColumnSchema;
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
