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
