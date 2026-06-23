package io.graphrag.builder.run;

import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.run.EnumResponseVariantGenerator.ResponseVariant;
import io.graphrag.builder.run.EnumResponseVariantGenerator.VariantPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** REQ-006, REQ-003: enum 응답 변형 생성 — budget 우선순위·baseline 제외·결정적 label. */
class EnumResponseVariantGeneratorTest {

    @Test
    void singleEnumYieldsConstantPerVariant() {
        BodyShape shape = new BodyShape("Inv",
                List.of(new BodyShape.BodyField("mode", "p.FulfillmentMode")), false);
        VariantPlan p = new EnumResponseVariantGenerator().generate(shape,
                Map.of("p.FulfillmentMode", List.of("STANDARD", "EXPRESS_ONLY", "BACKORDER")), 32);
        // baseline(STANDARD = 선언순 첫 상수) 제외 → 상수 알파벳 정렬: BACKORDER, EXPRESS_ONLY
        assertThat(p.kept()).extracting(ResponseVariant::label)
                .containsExactly("mode=BACKORDER", "mode=EXPRESS_ONLY");
        assertThat(p.kept().get(0).enumOverrides()).containsEntry("mode", "BACKORDER");
        assertThat(p.dropped()).isZero();
    }

    @Test
    void budgetTruncationLoud() {
        BodyShape shape = new BodyShape("Inv",
                List.of(new BodyShape.BodyField("mode", "p.FulfillmentMode")), false);
        VariantPlan p = new EnumResponseVariantGenerator().generate(shape,
                Map.of("p.FulfillmentMode", List.of("STANDARD", "EXPRESS_ONLY", "BACKORDER")), 1);
        assertThat(p.kept()).hasSize(1);
        assertThat(p.dropped()).isEqualTo(1);
    }

    @Test
    void singleFieldVariantsBeforeTwoWay() {
        BodyShape shape = new BodyShape("Inv", List.of(
                new BodyShape.BodyField("mode", "p.FulfillmentMode"),
                new BodyShape.BodyField("ship", "p.ShippingClass")), false);
        VariantPlan p = new EnumResponseVariantGenerator().generate(shape, Map.of(
                "p.FulfillmentMode", List.of("STANDARD", "EXPRESS"),
                "p.ShippingClass", List.of("GROUND", "AIR")), 32);
        List<String> labels = p.kept().stream().map(ResponseVariant::label).toList();
        // 단일 필드 변형(다른 필드는 baseline 고정): mode=EXPRESS, ship=AIR
        // 2-way 카르테시안: mode=EXPRESS,ship=AIR
        // 단일 필드 변형이 조합보다 앞선다.
        int singleMode = labels.indexOf("mode=EXPRESS");
        int singleShip = labels.indexOf("ship=AIR");
        int twoWay = labels.indexOf("mode=EXPRESS,ship=AIR");
        assertThat(singleMode).isGreaterThanOrEqualTo(0);
        assertThat(singleShip).isGreaterThanOrEqualTo(0);
        assertThat(twoWay).isGreaterThanOrEqualTo(0);
        assertThat(twoWay).isGreaterThan(singleMode);
        assertThat(twoWay).isGreaterThan(singleShip);
    }
}
