package io.graphrag.builder.provenance;

import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.provenance.ProvenanceReport.GuardFact;
import io.graphrag.builder.provenance.ProvenanceReport.Origin;
import io.graphrag.builder.provenance.ProvenanceReport.ValueRef;
import io.graphrag.builder.provenance.TripleValidator.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TripleValidator} 검증 — T1 후보 검증 게이트의 마커-diff(REQ-009), 스키마 검증(REQ-011),
 * PII 휴리스틱 차단(REQ-012). seed.sql 화이트리스트(REQ-010)는 {@link SeedSqlWhitelistIT} 전담.
 */
class TripleGateIT {

    @TempDir
    Path tempDir;

    private static final String GAP = TripleSynthesizer.GAP_MARKER_PREFIX;

    private Path writeArtifacts(String dirName, String bodyJson, String seedSql, String stubsJson)
            throws IOException {
        Path dir = tempDir.resolve(dirName);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("body.json"), bodyJson);
        Files.writeString(dir.resolve("seed.sql"), seedSql);
        Files.writeString(dir.resolve("stubs.json"), stubsJson);
        return dir;
    }

    private static ProvenanceReport reportWithDbReadTable(String table) {
        GuardFact guard = new GuardFact("Fixture.java:1", "EXISTS",
                List.of(new ValueRef(Origin.DB_READ, null, table, "id", null, null, "String", null, null)));
        return new ProvenanceReport("fixture-endpoint", List.of(guard), List.of(), List.of());
    }

    // ---- REQ-009: 마커 계약 강제 ----

    @Test
    @DisplayName("REQ-009: 마커만 채운 후보(body/seed 모두)는 통과한다")
    void req009_markerOnlyFillAccepted() throws IOException {
        String baseBody = "{\"note\":\"" + GAP + "{type:String, semanticHint:free-text, guard:none}\",\"amount\":100}";
        String candBody = "{\"note\":\"hello world\",\"amount\":100}";
        String baseSeed = "INSERT INTO orders (id, risk_score) VALUES "
                + "('seed-orders', '" + GAP + "{type:long, semanticHint:none, guard:none}');";
        String candSeed = "INSERT INTO orders (id, risk_score) VALUES ('seed-orders', '42');";

        Path base = writeArtifacts("base", baseBody, baseSeed, "{}");
        Path cand = writeArtifacts("cand", candBody, candSeed, "{}");

        TripleValidator validator = new TripleValidator(List.of(), DbConfig.Type.POSTGRES);
        ValidationResult result = validator.validate(cand, base, reportWithDbReadTable("orders"), BodyShape.empty());

        assertThat(result.accepted()).as("마커만 채운 후보는 통과해야 한다: " + result.reasons()).isTrue();
        assertThat(result.needsHumanReview()).isFalse();
    }

    @Test
    @DisplayName("REQ-009: 마커 아닌 body 필드를 바꾼 후보는 사유와 함께 reject된다")
    void req009_nonMarkerBodyFieldChangeRejected() throws IOException {
        String baseBody = "{\"note\":\"" + GAP + "{type:String, semanticHint:free-text, guard:none}\",\"amount\":100}";
        String candBody = "{\"note\":\"hello\",\"amount\":999}"; // amount는 마커가 아니었음
        String seed = "INSERT INTO orders (id) VALUES ('seed-orders');";

        Path base = writeArtifacts("base", baseBody, seed, "{}");
        Path cand = writeArtifacts("cand", candBody, seed, "{}");

        TripleValidator validator = new TripleValidator(List.of(), DbConfig.Type.POSTGRES);
        ValidationResult result = validator.validate(cand, base, reportWithDbReadTable("orders"), BodyShape.empty());

        assertThat(result.accepted()).as("비-마커 body 필드 변경은 reject되어야 한다").isFalse();
        assertThat(result.reasons()).isNotEmpty();
    }

    @Test
    @DisplayName("REQ-009: 마커 아닌 seed.sql 컬럼 값을 바꾼 후보는 사유와 함께 reject된다")
    void req009_nonMarkerSeedColumnChangeRejected() throws IOException {
        String body = "{\"note\":\"x\"}";
        String baseSeed = "INSERT INTO fund_accounts (id, balance_amount) VALUES ('seed-x', 100);";
        String candSeed = "INSERT INTO fund_accounts (id, balance_amount) VALUES ('seed-x', 999);"; // 마커 아니었음

        Path base = writeArtifacts("base", body, baseSeed, "{}");
        Path cand = writeArtifacts("cand", body, candSeed, "{}");

        TripleValidator validator = new TripleValidator(List.of(), DbConfig.Type.POSTGRES);
        ValidationResult result =
                validator.validate(cand, base, reportWithDbReadTable("fund_accounts"), BodyShape.empty());

        assertThat(result.accepted()).as("비-마커 seed 컬럼 값 변경은 reject되어야 한다").isFalse();
        assertThat(result.reasons()).isNotEmpty();
    }

    @Test
    @DisplayName("REQ-009: notes.md 존재 여부/내용은 검사하지 않는다 — notes.md 없이도 body/seed/stubs만으로 판정된다")
    void req009_notesMdIsNotInspected() throws IOException {
        String baseBody = "{\"note\":\"" + GAP + "{type:String, semanticHint:free-text, guard:none}\"}";
        String candBody = "{\"note\":\"filled\"}";
        String seed = "INSERT INTO orders (id) VALUES ('seed-orders');";

        Path base = writeArtifacts("base", baseBody, seed, "{}");
        Path cand = writeArtifacts("cand", candBody, seed, "{}");
        // base/cand 모두 notes.md 없음(의도적) — notes.md 부재가 판정에 영향을 주면 안 된다.

        TripleValidator validator = new TripleValidator(List.of(), DbConfig.Type.POSTGRES);
        ValidationResult result = validator.validate(cand, base, reportWithDbReadTable("orders"), BodyShape.empty());

        assertThat(result.accepted()).as("notes.md 부재는 판정에 영향을 주지 않아야 한다").isTrue();
    }

    @Test
    @DisplayName("REQ-009(C4 회귀): base와 동일한 행 '앞에' 추가 INSERT를 끼운 후보는 reject된다 — "
            + "테이블당 마지막 행만 비교하면 앞선 임의 행이 검증에서 통째로 사라진다")
    void req009_extraSeedRowInsertedBeforeMatchingRowRejected() throws IOException {
        String body = "{\"note\":\"x\"}";
        String baseSeed = "INSERT INTO fund_accounts (id, balance_amount) VALUES ('seed-fromaccountid', 1);";
        // 후보는 base와 동일한 행을 뒤에 두고, 그 '앞에' 임의의 행을 하나 더 끼워 넣는다.
        String candSeed = "INSERT INTO fund_accounts (id, balance_amount) VALUES ('x', 999999);\n"
                + "INSERT INTO fund_accounts (id, balance_amount) VALUES ('seed-fromaccountid', 1);";

        Path base = writeArtifacts("base", body, baseSeed, "{}");
        Path cand = writeArtifacts("cand", body, candSeed, "{}");

        TripleValidator validator = new TripleValidator(List.of(), DbConfig.Type.POSTGRES);
        ValidationResult result =
                validator.validate(cand, base, reportWithDbReadTable("fund_accounts"), BodyShape.empty());

        assertThat(result.accepted())
                .as("같은 테이블에 추가된 행은 마커 계약 위반으로 reject되어야 한다").isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("행 수"));
    }

    @Test
    @DisplayName("REQ-009(C4 회귀): base와 동일한 행 '뒤에' 추가 INSERT를 붙인 후보도 reject된다")
    void req009_extraSeedRowAppendedAfterMatchingRowRejected() throws IOException {
        String body = "{\"note\":\"x\"}";
        String baseSeed = "INSERT INTO fund_accounts (id, balance_amount) VALUES ('seed-fromaccountid', 1);";
        String candSeed = "INSERT INTO fund_accounts (id, balance_amount) VALUES ('seed-fromaccountid', 1);\n"
                + "INSERT INTO fund_accounts (id, balance_amount) VALUES ('x', 999999);";

        Path base = writeArtifacts("base", body, baseSeed, "{}");
        Path cand = writeArtifacts("cand", body, candSeed, "{}");

        TripleValidator validator = new TripleValidator(List.of(), DbConfig.Type.POSTGRES);
        ValidationResult result =
                validator.validate(cand, base, reportWithDbReadTable("fund_accounts"), BodyShape.empty());

        assertThat(result.accepted()).as("행 추가는 방향과 무관하게 reject되어야 한다").isFalse();
    }

    @Test
    @DisplayName("REQ-009(C4 회귀): 같은 테이블의 여러 행 순서를 바꾼 후보는 reject된다 — "
            + "행 순서는 역-DELETE 순서(child→parent)를 결정하므로 계약의 일부다")
    void req009_seedRowOrderSwapRejected() throws IOException {
        String body = "{\"note\":\"x\"}";
        String baseSeed = "INSERT INTO fund_accounts (id, balance_amount) VALUES ('a', 1);\n"
                + "INSERT INTO fund_accounts (id, balance_amount) VALUES ('b', 2);";
        String candSeed = "INSERT INTO fund_accounts (id, balance_amount) VALUES ('b', 2);\n"
                + "INSERT INTO fund_accounts (id, balance_amount) VALUES ('a', 1);";

        Path base = writeArtifacts("base", body, baseSeed, "{}");
        Path cand = writeArtifacts("cand", body, candSeed, "{}");

        TripleValidator validator = new TripleValidator(List.of(), DbConfig.Type.POSTGRES);
        ValidationResult result =
                validator.validate(cand, base, reportWithDbReadTable("fund_accounts"), BodyShape.empty());

        assertThat(result.accepted()).as("행 순서 변경은 reject되어야 한다").isFalse();
    }

    @Test
    @DisplayName("REQ-009(C4 회귀): 같은 테이블 다중 행이 순서·개수·값 모두 동일하면 통과한다(회귀 0)")
    void req009_multipleRowsPerTableUnchangedAccepted() throws IOException {
        String body = "{\"note\":\"x\"}";
        String seed = "INSERT INTO fund_accounts (id, balance_amount) VALUES ('a', 1);\n"
                + "INSERT INTO fund_accounts (id, balance_amount) VALUES ('b', 2);";

        Path base = writeArtifacts("base", body, seed, "{}");
        Path cand = writeArtifacts("cand", body, seed, "{}");

        TripleValidator validator = new TripleValidator(List.of(), DbConfig.Type.POSTGRES);
        ValidationResult result =
                validator.validate(cand, base, reportWithDbReadTable("fund_accounts"), BodyShape.empty());

        assertThat(result.accepted())
                .as("동일한 다중 행 seed는 통과해야 한다: " + result.reasons()).isTrue();
    }

    @Test
    @DisplayName("REQ-009(C4 회귀): 컬럼 순서만 뒤바꾼 후보는 reject된다 — "
            + "Set 비교는 순서를 무시하지만 정리 DELETE는 컬럼 순서에 의존한다")
    void req009_seedColumnOrderSwapRejected() throws IOException {
        String body = "{\"note\":\"x\"}";
        String baseSeed = "INSERT INTO fund_accounts (id, balance_amount) VALUES ('seed-x', 1);";
        String candSeed = "INSERT INTO fund_accounts (balance_amount, id) VALUES (1, 'seed-x');";

        Path base = writeArtifacts("base", body, baseSeed, "{}");
        Path cand = writeArtifacts("cand", body, candSeed, "{}");

        TripleValidator validator = new TripleValidator(List.of(), DbConfig.Type.POSTGRES);
        ValidationResult result =
                validator.validate(cand, base, reportWithDbReadTable("fund_accounts"), BodyShape.empty());

        assertThat(result.accepted()).as("컬럼 순서 변경은 reject되어야 한다").isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("컬럼 목록"));
    }

    @Test
    @DisplayName("REQ-010(C4 회귀): 컬럼 목록 없는 INSERT INTO t VALUES (...)는 allowlist에서 reject된다 — "
            + "역-DELETE 추적 가드를 빠져나가 정리 대상에서 누락되는 형태다")
    void req010_columnLessInsertRejected() throws IOException {
        String body = "{\"note\":\"x\"}";
        String seed = "INSERT INTO fund_accounts VALUES ('seed-x', 1);";

        Path base = writeArtifacts("base", body, seed, "{}");
        Path cand = writeArtifacts("cand", body, seed, "{}");

        TripleValidator validator = new TripleValidator(List.of(), DbConfig.Type.POSTGRES);
        ValidationResult result =
                validator.validate(cand, base, reportWithDbReadTable("fund_accounts"), BodyShape.empty());

        assertThat(result.accepted()).as("컬럼 목록 없는 INSERT는 reject되어야 한다").isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("columns"));
    }

    // ---- REQ-011: 스키마 검증(body+stub) ----

    @Test
    @DisplayName("REQ-011: BodyShape에 없는 필드를 추가한 body 후보는 사유와 함께 reject된다")
    void req011_bodyFieldOutsideShapeRejected() throws IOException {
        // base/cand가 "extra" 필드를 동일하게(비-마커, 무변경) 갖고 있으므로 REQ-009 마커-diff는 통과하지만
        // BodyShape가 "note"만 선언하므로 REQ-011 스키마 검증에서 reject되어야 한다.
        String bodyJson = "{\"note\":\"x\",\"extra\":\"not-in-shape\"}";
        String seed = "INSERT INTO orders (id) VALUES ('seed-orders');";

        Path base = writeArtifacts("base", bodyJson, seed, "{}");
        Path cand = writeArtifacts("cand", bodyJson, seed, "{}");

        BodyShape shape = new BodyShape("Req", List.of(new BodyShape.BodyField("note", "String")));
        TripleValidator validator = new TripleValidator(List.of(), DbConfig.Type.POSTGRES);
        ValidationResult result = validator.validate(cand, base, reportWithDbReadTable("orders"), shape);

        assertThat(result.accepted()).as("BodyShape 밖 필드는 reject되어야 한다").isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("extra"));
    }

    @Test
    @DisplayName("REQ-011: BodyShape에 선언된 필드만 있는 body 후보는 스키마 검증을 통과한다")
    void req011_bodyFieldsWithinShapeAccepted() throws IOException {
        String bodyJson = "{\"note\":\"x\"}";
        String seed = "INSERT INTO orders (id) VALUES ('seed-orders');";
        Path base = writeArtifacts("base", bodyJson, seed, "{}");
        Path cand = writeArtifacts("cand", bodyJson, seed, "{}");

        BodyShape shape = new BodyShape("Req", List.of(new BodyShape.BodyField("note", "String")));
        TripleValidator validator = new TripleValidator(List.of(), DbConfig.Type.POSTGRES);
        ValidationResult result = validator.validate(cand, base, reportWithDbReadTable("orders"), shape);

        assertThat(result.accepted()).as("BodyShape 선언 필드만 있으면 통과해야 한다: " + result.reasons()).isTrue();
    }

    @Test
    @DisplayName("REQ-011(보강): List<DTO> 필드(BodyShape에는 top-level 'items'만 있음) 아래 "
            + "items.sku/items.qty 중첩 리프는 스키마 검증을 통과한다")
    void req011_nestedListDotPathAcceptedWhenTopLevelFieldKnown() throws IOException {
        // BodyShapeExtractor.flatten()은 컬렉션 필드를 원소 DTO까지 전개하지 않고 "items" 하나만
        // top-level 리프로 담는다(java.util.List) — 실제 후보 body는 items를 배열-of-객체로 채운다.
        String bodyJson = "{\"fromAccountId\":\"probe-1\",\"amount\":100,"
                + "\"items\":[{\"sku\":\"sku-1\",\"qty\":2}]}";
        String seed = "INSERT INTO fund_accounts (id, balance_amount) VALUES ('probe-1', 100);";

        Path base = writeArtifacts("base", bodyJson, seed, "{}");
        Path cand = writeArtifacts("cand", bodyJson, seed, "{}");

        BodyShape shape = new BodyShape("CreateTransferRequest", List.of(
                new BodyShape.BodyField("fromAccountId", "java.lang.String"),
                new BodyShape.BodyField("amount", "long"),
                new BodyShape.BodyField("note", "java.lang.String"),
                new BodyShape.BodyField("items", "java.util.List")));
        TripleValidator validator = new TripleValidator(List.of(), DbConfig.Type.POSTGRES);
        ValidationResult result =
                validator.validate(cand, base, reportWithDbReadTable("fund_accounts"), shape);

        assertThat(result.accepted())
                .as("List<DTO> top-level 필드 아래 중첩 리프(items.sku/items.qty)는 통과해야 한다: " + result.reasons())
                .isTrue();
    }

    @Test
    @DisplayName("REQ-011(보강 회귀): 완전히 새로운 top-level 필드가 붙은 중첩 경로는 여전히 reject된다 — "
            + "REQ-011의 미지 필드 거부는 top-level 단위로 유지된다")
    void req011_unknownTopLevelPrefixStillRejectedEvenIfNested() throws IOException {
        // "hacked"는 allowed(top-level: fromAccountId/amount/note/items) 어디에도 없으므로,
        // 그 아래 중첩 경로(hacked.x)라도 여전히 reject되어야 한다 — 완화가 top-level 검증을
        // 우회하는 구멍이 되지 않음을 확인한다.
        String bodyJson = "{\"note\":\"x\",\"hacked\":{\"x\":1}}";
        String seed = "INSERT INTO orders (id) VALUES ('seed-orders');";

        Path base = writeArtifacts("base", bodyJson, seed, "{}");
        Path cand = writeArtifacts("cand", bodyJson, seed, "{}");

        BodyShape shape = new BodyShape("Req", List.of(new BodyShape.BodyField("note", "String")));
        TripleValidator validator = new TripleValidator(List.of(), DbConfig.Type.POSTGRES);
        ValidationResult result = validator.validate(cand, base, reportWithDbReadTable("orders"), shape);

        assertThat(result.accepted()).as("알 수 없는 top-level 접두사(hacked)는 중첩이어도 reject되어야 한다").isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("hacked.x"));
    }

    @Test
    @DisplayName("REQ-011: WireMock mapping 스키마에 없는 키를 추가한 stub 후보는 사유와 함께 reject된다")
    void req011_stubKeyOutsideMappingSchemaRejected() throws IOException {
        String body = "{\"note\":\"x\"}";
        String seed = "INSERT INTO orders (id) VALUES ('seed-orders');";
        // base/cand 모두 "priority"라는 mapping 스키마 밖 최상위 키를 동일하게 가짐(비-마커, 무변경)
        // -> REQ-009 마커-diff는 통과하지만 REQ-011 mapping 스키마 검증에서 reject되어야 한다.
        String stub = "{\"request\":{\"method\":\"POST\",\"urlPath\":\"/x\"},"
                + "\"response\":{\"status\":200,\"jsonBody\":{}},\"priority\":5}";

        Path base = writeArtifacts("base", body, seed, stub);
        Path cand = writeArtifacts("cand", body, seed, stub);

        TripleValidator validator = new TripleValidator(List.of(), DbConfig.Type.POSTGRES);
        ValidationResult result =
                validator.validate(cand, base, reportWithDbReadTable("orders"), BodyShape.empty());

        assertThat(result.accepted()).as("mapping 스키마 밖 키(priority)는 reject되어야 한다").isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("priority"));
    }

    @Test
    @DisplayName("REQ-011(보강, Task 18): stub.response에 headers 키를 추가한 후보는 통과한다 — "
            + "jsonBody 전용 stub이 Content-Type 없이 등록되면 실 HTTP 클라이언트의 메시지 컨버터 선택이 "
            + "실패해 SUT가 500을 내므로(완주 E2E에서 실측), 사람 갭필로 명시적 Content-Type을 채울 수 있어야 한다")
    void req011_stubResponseHeadersKeyAccepted() throws IOException {
        String body = "{\"note\":\"x\"}";
        String seed = "INSERT INTO orders (id) VALUES ('seed-orders');";
        String stub = "{\"request\":{\"method\":\"POST\",\"urlPath\":\"/fraud/check\"},"
                + "\"response\":{\"status\":200,\"headers\":{\"Content-Type\":\"application/json\"},"
                + "\"jsonBody\":{\"status\":\"CLEAR\"}}}";

        Path base = writeArtifacts("base", body, seed, stub);
        Path cand = writeArtifacts("cand", body, seed, stub);

        TripleValidator validator = new TripleValidator(List.of(), DbConfig.Type.POSTGRES);
        ValidationResult result =
                validator.validate(cand, base, reportWithDbReadTable("orders"), BodyShape.empty());

        assertThat(result.accepted())
                .as("stub.response.headers는 REQ-011 스키마 내 허용 키여야 한다: " + result.reasons()).isTrue();
    }

    @Test
    @DisplayName("REQ-011(fix): stub jsonBody의 마커 위치를 객체(중첩 구조)로 대체한 후보는 reject된다 — "
            + "마커는 '값 치환'이지 '구조 대체'가 아니다")
    void req011_stubJsonBodyMarkerReplacedWithObjectRejected() throws IOException {
        String body = "{\"note\":\"x\"}";
        String seed = "INSERT INTO orders (id) VALUES ('seed-orders');";
        String baseStub = "{\"request\":{\"method\":\"POST\",\"urlPath\":\"/fraud/check\"},"
                + "\"response\":{\"status\":200,\"jsonBody\":{\"status\":\"" + GAP
                + "{type:String, semanticHint:none, guard:none}\"}}}";
        String candStub = "{\"request\":{\"method\":\"POST\",\"urlPath\":\"/fraud/check\"},"
                + "\"response\":{\"status\":200,\"jsonBody\":{\"status\":{\"nested\":\"escape\"}}}}";

        Path base = writeArtifacts("base", body, seed, baseStub);
        Path cand = writeArtifacts("cand", body, seed, candStub);

        TripleValidator validator = new TripleValidator(List.of(), DbConfig.Type.POSTGRES);
        ValidationResult result =
                validator.validate(cand, base, reportWithDbReadTable("orders"), BodyShape.empty());

        assertThat(result.accepted())
                .as("stub jsonBody 마커 위치를 객체로 대체하면 reject되어야 한다")
                .isFalse();
        assertThat(result.reasons()).isNotEmpty();
    }

    @Test
    @DisplayName("REQ-011(fix): stub jsonBody의 마커 위치를 정상 스칼라 값으로 채운 후보는 통과한다")
    void req011_stubJsonBodyMarkerFilledWithScalarAccepted() throws IOException {
        String body = "{\"note\":\"x\"}";
        String seed = "INSERT INTO orders (id) VALUES ('seed-orders');";
        String baseStub = "{\"request\":{\"method\":\"POST\",\"urlPath\":\"/fraud/check\"},"
                + "\"response\":{\"status\":200,\"jsonBody\":{\"status\":\"" + GAP
                + "{type:String, semanticHint:none, guard:none}\"}}}";
        String candStub = "{\"request\":{\"method\":\"POST\",\"urlPath\":\"/fraud/check\"},"
                + "\"response\":{\"status\":200,\"jsonBody\":{\"status\":\"CLEAR\"}}}";

        Path base = writeArtifacts("base", body, seed, baseStub);
        Path cand = writeArtifacts("cand", body, seed, candStub);

        TripleValidator validator = new TripleValidator(List.of(), DbConfig.Type.POSTGRES);
        ValidationResult result =
                validator.validate(cand, base, reportWithDbReadTable("orders"), BodyShape.empty());

        assertThat(result.accepted())
                .as("stub jsonBody 마커를 스칼라 값으로 채운 정상 후보는 통과해야 한다: " + result.reasons())
                .isTrue();
    }

    // ---- REQ-012: PII 휴리스틱 차단 ----

    @Test
    @DisplayName("REQ-012: 마커에 실존 형식 휴대전화 값을 채운 후보는 needsHumanReview로 승격이 차단된다")
    void req012_phoneNumberPiiBlocksPromotion() throws IOException {
        String baseBody = "{\"phone\":\"" + GAP + "{type:String, semanticHint:none, guard:none}\"}";
        String candBody = "{\"phone\":\"010-1234-5678\"}";
        String seed = "INSERT INTO orders (id) VALUES ('seed-orders');";

        Path base = writeArtifacts("base", baseBody, seed, "{}");
        Path cand = writeArtifacts("cand", candBody, seed, "{}");

        TripleValidator validator = new TripleValidator(List.of(), DbConfig.Type.POSTGRES);
        ValidationResult result = validator.validate(cand, base, reportWithDbReadTable("orders"), BodyShape.empty());

        assertThat(result.needsHumanReview()).as("실존 형식 휴대전화 값은 needsHumanReview여야 한다").isTrue();
        assertThat(result.accepted()).as("PII 히트 시 승격이 차단되어야 한다(accepted=false)").isFalse();
        assertThat(result.reasons()).isNotEmpty();
    }

    @Test
    @DisplayName("REQ-012: 마커에 실존 형식 주민등록번호 값을 채운 seed.sql 후보는 needsHumanReview로 승격이 차단된다")
    void req012_residentRegistrationNumberPiiBlocksPromotion() throws IOException {
        String body = "{\"note\":\"x\"}";
        String baseSeed = "INSERT INTO orders (id, ssn) VALUES "
                + "('seed-orders', '" + GAP + "{type:String, semanticHint:none, guard:none}');";
        String candSeed = "INSERT INTO orders (id, ssn) VALUES ('seed-orders', '900101-1234567');";

        Path base = writeArtifacts("base", body, baseSeed, "{}");
        Path cand = writeArtifacts("cand", body, candSeed, "{}");

        TripleValidator validator = new TripleValidator(List.of(), DbConfig.Type.POSTGRES);
        ValidationResult result = validator.validate(cand, base, reportWithDbReadTable("orders"), BodyShape.empty());

        assertThat(result.needsHumanReview()).as("실존 형식 주민등록번호는 needsHumanReview여야 한다").isTrue();
        assertThat(result.accepted()).isFalse();
    }

    @Test
    @DisplayName("REQ-012: probe@example.com류 합성 이메일 값은 실도메인이 아니므로 통과한다")
    void req012_syntheticExampleDomainEmailPasses() throws IOException {
        String baseBody = "{\"email\":\"" + GAP + "{type:String, semanticHint:email, guard:none}\"}";
        String candBody = "{\"email\":\"probe@example.com\"}";
        String seed = "INSERT INTO orders (id) VALUES ('seed-orders');";

        Path base = writeArtifacts("base", baseBody, seed, "{}");
        Path cand = writeArtifacts("cand", candBody, seed, "{}");

        TripleValidator validator = new TripleValidator(List.of(), DbConfig.Type.POSTGRES);
        ValidationResult result = validator.validate(cand, base, reportWithDbReadTable("orders"), BodyShape.empty());

        assertThat(result.needsHumanReview()).as("example.com 합성 이메일은 PII로 판정되면 안 된다").isFalse();
        assertThat(result.accepted()).as("example.com 합성 이메일은 통과해야 한다: " + result.reasons()).isTrue();
    }

    @Test
    @DisplayName("REQ-012: 실도메인(gmail) 이메일 값은 PII로 판정되어 승격이 차단된다")
    void req012_realGmailDomainEmailBlocksPromotion() throws IOException {
        String baseBody = "{\"email\":\"" + GAP + "{type:String, semanticHint:email, guard:none}\"}";
        String candBody = "{\"email\":\"someone@gmail.com\"}";
        String seed = "INSERT INTO orders (id) VALUES ('seed-orders');";

        Path base = writeArtifacts("base", baseBody, seed, "{}");
        Path cand = writeArtifacts("cand", candBody, seed, "{}");

        TripleValidator validator = new TripleValidator(List.of(), DbConfig.Type.POSTGRES);
        ValidationResult result = validator.validate(cand, base, reportWithDbReadTable("orders"), BodyShape.empty());

        assertThat(result.needsHumanReview()).as("실도메인(gmail.com) 이메일은 PII로 판정되어야 한다").isTrue();
        assertThat(result.accepted()).isFalse();
    }
}
