package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.ValidationConstraintExtractor.FieldConstraint;
import io.graphrag.builder.index.ValidationConstraintExtractor.Kind;
import io.graphrag.builder.run.NegativeValidationSynthesizer.NegativeVariant;
import io.graphrag.model.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class NegativeValidationSynthesizerTest {

    private static final int CAP = 4;

    private static final BodyShape SHAPE = new BodyShape("Signup", List.of(
            new BodyShape.BodyField("name", "java.lang.String"),
            new BodyShape.BodyField("email", "java.lang.String"),
            new BodyShape.BodyField("age", "int"),
            new BodyShape.BodyField("password", "java.lang.String"),
            new BodyShape.BodyField("tags", "java.util.List")));

    private static ObjectNode happy() {
        ObjectNode body = Json.mapper().createObjectNode();
        body.put("name", "sample-name");
        body.put("email", "probe@example.com");
        body.put("age", 18);
        body.put("password", "samplepassword");
        body.putArray("tags").add("a");
        return body;
    }

    private static List<NegativeVariant> variants(Map<String, List<FieldConstraint>> cons) {
        return NegativeValidationSynthesizer.synthesizeNegativeValidationVariants(
                SHAPE, cons, happy(), CAP);
    }

    private static NegativeVariant only(Map<String, List<FieldConstraint>> cons) {
        List<NegativeVariant> v = variants(cons);
        assertThat(v).hasSize(1);
        return v.get(0);
    }

    @Test
    void notNull_removesField() {
        NegativeVariant v = only(Map.of("name", List.of(
                new FieldConstraint("name", Kind.NOT_NULL, 0, null))));
        assertThat(v.field()).isEqualTo("name");
        assertThat(v.kind()).isEqualTo(Kind.NOT_NULL);
        assertThat(v.body().has("name")).isFalse();   // null = 필드 제거
    }

    @Test
    void min_usesBoundaryMinusOne() {
        NegativeVariant v = only(Map.of("age", List.of(
                new FieldConstraint("age", Kind.MIN, 18, null))));
        assertThat(v.body().get("age").asLong()).isEqualTo(17);
    }

    @Test
    void max_usesBoundaryPlusOne() {
        NegativeVariant v = only(Map.of("age", List.of(
                new FieldConstraint("age", Kind.MAX, 65, null))));
        assertThat(v.body().get("age").asLong()).isEqualTo(66);
    }

    @Test
    void email_usesInvalidValue() {
        NegativeVariant v = only(Map.of("email", List.of(
                new FieldConstraint("email", Kind.EMAIL, 0, null))));
        assertThat(v.body().get("email").asText()).doesNotContain("@");
    }

    @Test
    void pattern_usesMismatchVerifiedAgainstRegex() {
        String regex = "[0-9]+";
        NegativeVariant v = only(Map.of("name", List.of(
                new FieldConstraint("name", Kind.PATTERN, 0, regex))));
        assertThat(Pattern.matches(regex, v.body().get("name").asText())).isFalse();
    }

    @Test
    void pattern_skippedWhenNoMismatchPossible() {
        // ".*"는 모든 문자열과 매치 → 위반값 합성 불가 → 변종 없음(무회귀).
        assertThat(variants(Map.of("name", List.of(
                new FieldConstraint("name", Kind.PATTERN, 0, ".*"))))).isEmpty();
    }

    @Test
    void sizeMin_usesLengthBelowMin() {
        NegativeVariant v = only(Map.of("password", List.of(
                new FieldConstraint("password", Kind.SIZE_MIN, 8, null))));
        assertThat(v.body().get("password").asText()).hasSize(7);
    }

    @Test
    void notBlank_string_usesEmptyString() {
        // @NotBlank와 @NotEmpty가 NOT_BLANK로 합쳐지므로 둘 다 위반하는 ""여야 한다(공백 "   "는 @NotEmpty 통과).
        NegativeVariant v = only(Map.of("name", List.of(
                new FieldConstraint("name", Kind.NOT_BLANK, 0, null))));
        assertThat(v.body().get("name").asText()).isEmpty();
    }

    @Test
    void notBlank_stringTypeWithCollectionLikeName_notMisclassifiedAsArray() {
        // simple name "Listing"은 컬렉션이 아니다 → String 위반값("")이어야 한다(substring 오탐 방지).
        BodyShape shape = new BodyShape("X", List.of(
                new BodyShape.BodyField("tag", "com.acme.Listing")));
        ObjectNode happy = Json.mapper().createObjectNode();
        happy.put("tag", "ok");
        List<NegativeVariant> v = NegativeValidationSynthesizer.synthesizeNegativeValidationVariants(
                shape, Map.of("tag", List.of(new FieldConstraint("tag", Kind.NOT_BLANK, 0, null))),
                happy, CAP);
        assertThat(v).hasSize(1);
        assertThat(v.get(0).body().get("tag").isTextual()).isTrue();
        assertThat(v.get(0).body().get("tag").asText()).isEmpty();
    }

    @Test
    void notBlank_collection_usesEmptyArray() {
        // Collection 필드면 [](빈 배열) — ""는 Jackson 역직렬화 에러.
        NegativeVariant v = only(Map.of("tags", List.of(
                new FieldConstraint("tags", Kind.NOT_BLANK, 0, null))));
        assertThat(v.body().get("tags").isArray()).isTrue();
        assertThat(v.body().get("tags")).isEmpty();
    }

    @Test
    void singleFieldViolated_restStayHappy() {
        NegativeVariant v = only(Map.of("age", List.of(
                new FieldConstraint("age", Kind.MIN, 18, null))));
        // age만 위반, 나머지 필드는 happy 그대로.
        assertThat(v.body().get("name").asText()).isEqualTo("sample-name");
        assertThat(v.body().get("email").asText()).isEqualTo("probe@example.com");
        assertThat(v.body().get("password").asText()).isEqualTo("samplepassword");
    }

    @Test
    void cap_dropsExcessCandidatesDeterministically() {
        // 5개 (field,kind) 후보 → cap=4 → field명·kind 정렬 후 앞 4개만.
        Map<String, List<FieldConstraint>> cons = Map.of(
                "name", List.of(new FieldConstraint("name", Kind.NOT_BLANK, 0, null)),
                "email", List.of(new FieldConstraint("email", Kind.EMAIL, 0, null)),
                "age", List.of(new FieldConstraint("age", Kind.MIN, 18, null)),
                "password", List.of(new FieldConstraint("password", Kind.SIZE_MIN, 8, null)),
                "tags", List.of(new FieldConstraint("tags", Kind.NOT_BLANK, 0, null)));
        List<NegativeVariant> v = variants(cons);
        assertThat(v).hasSize(CAP);
        // 결정적: field명 사전순 앞 4개(age,email,name,password) — tags 드롭.
        assertThat(v).extracting(NegativeVariant::field)
                .containsExactly("age", "email", "name", "password");
    }

    @Test
    void noConstraints_emptyResult() {
        assertThat(variants(Map.of())).isEmpty();
    }

    @Test
    void fieldNotInShape_ignored() {
        // shape에 없는 필드 제약은 무시(happy body에 없으므로 위반 의미 없음).
        assertThat(variants(Map.of("ghost", List.of(
                new FieldConstraint("ghost", Kind.NOT_NULL, 0, null))))).isEmpty();
    }

    @Test
    void multipleConstraintsOneField_eachBecomesVariant() {
        // 한 필드의 여러 제약은 각각 별도 변종(단, cap 내).
        List<NegativeVariant> v = variants(Map.of("password", List.of(
                new FieldConstraint("password", Kind.NOT_BLANK, 0, null),
                new FieldConstraint("password", Kind.SIZE_MIN, 8, null))));
        assertThat(v).extracting(NegativeVariant::kind)
                .containsExactlyInAnyOrder(Kind.NOT_BLANK, Kind.SIZE_MIN);
    }
}
