package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.model.ColumnSchema;
import io.graphrag.model.ForeignKey;
import io.graphrag.model.TableSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SampleInputSynthesizerTest {

    private static final List<TableSchema> SCHEMA = List.of(
            new TableSchema("users",
                    List.of(new ColumnSchema("id", "VARCHAR", false, true),
                            new ColumnSchema("name", "VARCHAR", false, false)),
                    List.of(), List.of(List.of("id"))),
            new TableSchema("orders",
                    List.of(new ColumnSchema("id", "BIGSERIAL", false, true),
                            new ColumnSchema("user_id", "VARCHAR", false, false),
                            new ColumnSchema("amount", "INT4", false, false),
                            new ColumnSchema("type", "VARCHAR", false, false),
                            new ColumnSchema("status", "VARCHAR", false, false)),
                    List.of(new ForeignKey("user_id", "users", "id")),
                    List.of(List.of("id"))));

    private static final BodyShape SHAPE = new BodyShape("X", List.of(
            new BodyShape.BodyField("userId", "java.lang.String"),
            new BodyShape.BodyField("amount", "java.lang.Integer"),
            new BodyShape.BodyField("type", "java.lang.String")));

    @Test
    void synthesize_fkField_getsSeedRowAndProbeValue() {
        SynthesizedInput input = new SampleInputSynthesizer().synthesize(SHAPE, SCHEMA);

        assertThat(input.body().get("userId").asText()).isEqualTo("probe-userId");
        assertThat(input.seeds()).hasSize(1);
        SynthesizedInput.SeedRow seed = input.seeds().get(0);
        assertThat(seed.table()).isEqualTo("users");
        assertThat(seed.columns()).containsExactly("id", "name");
        assertThat(seed.values()).containsExactly("probe-userId", "probe");
    }

    @Test
    void synthesize_scalarFields_getDeterministicValues() {
        SynthesizedInput input = new SampleInputSynthesizer().synthesize(SHAPE, SCHEMA);

        assertThat(input.body().get("amount").asInt()).isEqualTo(1);
        assertThat(input.body().get("type").asText()).isEqualTo("sample-type");
    }

    @Test
    void synthesize_isDeterministic() {
        SynthesizedInput a = new SampleInputSynthesizer().synthesize(SHAPE, SCHEMA);
        SynthesizedInput b = new SampleInputSynthesizer().synthesize(SHAPE, SCHEMA);
        assertThat(a.body()).isEqualTo(b.body());
        assertThat(a.seeds()).isEqualTo(b.seeds());
    }

    private static BodyShape shape(BodyShape.BodyField... f) {
        return new BodyShape("T", List.of(f));
    }

    @Test
    void synthesize_enum_date_email_validValues() {
        Map<String, List<String>> enums = Map.of("io.x.PriceTier", List.of("BASIC", "VIP"));
        JsonNode body = new SampleInputSynthesizer(enums).synthesize(shape(
                new BodyShape.BodyField("priceTier", "io.x.PriceTier"),
                new BodyShape.BodyField("checkInDate", "java.time.LocalDate"),
                new BodyShape.BodyField("ownerEmail", "java.lang.String"),
                new BodyShape.BodyField("nights", "int"),
                new BodyShape.BodyField("note", "java.lang.String")
        ), List.of()).body();
        assertThat(body.get("priceTier").asText()).isEqualTo("BASIC");
        assertThat(body.get("checkInDate").asText()).isEqualTo("2037-01-01");
        assertThat(body.get("ownerEmail").asText()).isEqualTo("probe@example.com");
        assertThat(body.get("nights").asInt()).isEqualTo(1);              // 정수 우선
        assertThat(body.get("note").asText()).isEqualTo("sample-note");   // 일반 String default
    }

    @Test
    void synthesize_noArgCtor_defaultsForEnum() {   // 빈 맵 호환
        JsonNode body = new SampleInputSynthesizer().synthesize(shape(
                new BodyShape.BodyField("priceTier", "io.x.PriceTier")), List.of()).body();
        assertThat(body.get("priceTier").asText()).isEqualTo("sample-priceTier");
    }

    // ----- 제약-aware happy (Feature A) -----
    private static io.graphrag.builder.index.ValidationConstraintExtractor.FieldConstraint fc(
            String field, io.graphrag.builder.index.ValidationConstraintExtractor.Kind kind, long n) {
        return new io.graphrag.builder.index.ValidationConstraintExtractor.FieldConstraint(field, kind, n, null);
    }

    @Test
    void synthesize_minConstraint_intAtLeastMin() {
        var fcMap = Map.of("roomNumber", List.of(
                fc("roomNumber", io.graphrag.builder.index.ValidationConstraintExtractor.Kind.MIN, 100)));
        JsonNode body = new SampleInputSynthesizer().synthesize(
                shape(new BodyShape.BodyField("roomNumber", "int")), List.of(), fcMap).body();
        assertThat(body.get("roomNumber").asInt()).isGreaterThanOrEqualTo(100);
    }

    @Test
    void synthesize_minMaxConstraint_intInRange() {
        var fcMap = Map.of("nights", List.of(
                fc("nights", io.graphrag.builder.index.ValidationConstraintExtractor.Kind.MIN, 1),
                fc("nights", io.graphrag.builder.index.ValidationConstraintExtractor.Kind.MAX, 30)));
        JsonNode body = new SampleInputSynthesizer().synthesize(
                shape(new BodyShape.BodyField("nights", "int")), List.of(), fcMap).body();
        assertThat(body.get("nights").asInt()).isBetween(1, 30);
    }

    @Test
    void synthesize_negativeConstraint_intNegative() {
        var fcMap = Map.of("delta", List.of(
                fc("delta", io.graphrag.builder.index.ValidationConstraintExtractor.Kind.NEGATIVE, 0)));
        JsonNode body = new SampleInputSynthesizer().synthesize(
                shape(new BodyShape.BodyField("delta", "int")), List.of(), fcMap).body();
        assertThat(body.get("delta").asInt()).isLessThan(0);
    }

    @Test
    void synthesize_sizeConstraint_stringWithinBounds() {
        var fcMap = Map.of("petName", List.of(
                fc("petName", io.graphrag.builder.index.ValidationConstraintExtractor.Kind.SIZE_MIN, 2),
                fc("petName", io.graphrag.builder.index.ValidationConstraintExtractor.Kind.SIZE_MAX, 5)));
        JsonNode body = new SampleInputSynthesizer().synthesize(
                shape(new BodyShape.BodyField("petName", "java.lang.String")), List.of(), fcMap).body();
        assertThat(body.get("petName").asText().length()).isBetween(2, 5);
    }

    @Test
    void mergeComparisonBounds_imperativeGuards_happyInValidRange() {
        // petclinic Reservation 류: 명령형 if(nights<1 || nights>30) throw → happy nights ∈ [1,30]
        BodyShape shape = shape(new BodyShape.BodyField("nights", "int"),
                new BodyShape.BodyField("roomNumber", "int"));
        var comparisons = List.of(
                new io.graphrag.builder.index.ConstraintExtractor.Comparison("x.Svc", "create", "nights", "<", 1, 10),
                new io.graphrag.builder.index.ConstraintExtractor.Comparison("x.Svc", "create", "nights", ">", 30, 11),
                new io.graphrag.builder.index.ConstraintExtractor.Comparison("x.Svc", "create", "roomNumber", "<", 100, 12),
                new io.graphrag.builder.index.ConstraintExtractor.Comparison("x.Svc", "create", "roomNumber", ">", 499, 13));
        var merged = EndpointExplorationRunner.mergeComparisonBounds(Map.of(), comparisons, shape);
        JsonNode body = new SampleInputSynthesizer().synthesize(shape, List.of(), merged).body();
        assertThat(body.get("nights").asInt()).isBetween(1, 30);
        assertThat(body.get("roomNumber").asInt()).isBetween(100, 499);
    }

    @Test
    void mergeComparisonBounds_oneSidedComparison_skipped() {
        // 단방향 비교(비-가드 비즈니스 분기 위험)는 happy에 반영하지 않는다(안전망).
        BodyShape shape = shape(new BodyShape.BodyField("nights", "int"));
        var comparisons = List.of(
                new io.graphrag.builder.index.ConstraintExtractor.Comparison("x.Svc", "m", "nights", ">", 7, 1));
        var merged = EndpointExplorationRunner.mergeComparisonBounds(Map.of(), comparisons, shape);
        JsonNode body = new SampleInputSynthesizer().synthesize(shape, List.of(), merged).body();
        assertThat(body.get("nights").asInt()).isEqualTo(1);   // 변형 없음
    }

    @Test
    void synthesize_unconstrainedFloat_moderateLargeDefault() {
        JsonNode body = new SampleInputSynthesizer().synthesize(
                shape(new BodyShape.BodyField("depositAmount", "double")), List.of(), Map.of()).body();
        assertThat(body.get("depositAmount").asDouble()).isGreaterThanOrEqualTo(1000.0);
    }

    @Test
    void mergeComparisonBounds_ignoresNonBodyFields() {
        BodyShape shape = shape(new BodyShape.BodyField("nights", "int"));
        var comparisons = List.of(
                new io.graphrag.builder.index.ConstraintExtractor.Comparison("x.Svc", "m", "otherField", "<", 5, 1));
        var merged = EndpointExplorationRunner.mergeComparisonBounds(Map.of(), comparisons, shape);
        assertThat(merged).doesNotContainKey("otherField");
    }

    @Test
    void synthesize_emptyConstraints_unchanged() {
        JsonNode withEmpty = new SampleInputSynthesizer().synthesize(
                shape(new BodyShape.BodyField("amount", "int"),
                        new BodyShape.BodyField("type", "java.lang.String")), List.of(), Map.of()).body();
        JsonNode legacy = new SampleInputSynthesizer().synthesize(
                shape(new BodyShape.BodyField("amount", "int"),
                        new BodyShape.BodyField("type", "java.lang.String")), List.of()).body();
        assertThat(withEmpty).isEqualTo(legacy);
    }

    // ----- REQ-004 guard: List<scalar> synthesize -----

    @Test
    void scalarList_alreadyWorks() {
        // List<String> collection shape with no fields → 1-element array of text
        BodyShape shape = new BodyShape("java.lang.String", List.of(), true);
        JsonNode result = new SampleInputSynthesizer().synthesize(shape, List.of()).body();

        assertThat(result.isArray()).isTrue();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).isTextual()).isTrue();
    }
}
