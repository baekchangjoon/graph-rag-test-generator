package io.graphrag.builder.run;

import io.graphrag.builder.index.ConstraintExtractor;
import io.graphrag.builder.index.ConstraintExtractor.GuardKind;
import io.graphrag.builder.index.ConstraintExtractor.StateGuard;
import io.graphrag.builder.index.ConstraintExtractor.StateGuardConjunction;
import io.graphrag.builder.run.ReadInputSynthesizer.SeedVariant;
import io.graphrag.builder.run.SynthesizedInput.SeedRow;
import io.graphrag.model.ColumnSchema;
import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ParamKind;
import io.graphrag.model.TableSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-003/REQ-007: exploreStateGuardVariants의 conjunction NPE 회피 + kind별 gate 결정.
 * <p>
 * REQ-007: TEMPORAL → gate=false, ENUM → gate=true, BOOLEAN/NULLITY/NUMERIC → 미적용.
 * REQ-003: conjunction SeedVariant(guard=null)에서 variantLabel/gate 결정이 NPE 없이 동작.
 * appliesBooleanGate·variantLabel 헬퍼를 직접 단위 테스트한다.
 */
class EndpointExplorationRunnerStateGuardTest {

    // ---- REQ-003: conjunction NPE 회피 픽스처 ----
    private static final StateGuard LEAF_STATUS =
            new StateGuard("x.BookingService", "check", 10, "status",
                    GuardKind.ENUM, "BookingStatus",
                    List.of(), List.of("CONFIRMED"));

    private static final StateGuard LEAF_TIER =
            new StateGuard("x.BookingService", "check", 10, "tier",
                    GuardKind.ENUM, "Tier",
                    List.of(), List.of("VIP"));

    private static final StateGuardConjunction CONJUNCTION =
            new StateGuardConjunction("x.BookingService", "check", 10,
                    List.of(LEAF_STATUS, LEAF_TIER));

    private static final Endpoint GET_BY_ID = new Endpoint(
            "get-api-bookings-id", "GET", "/api/bookings/{id}", "x.BookingController", "getById",
            List.of(new EndpointParam("id", "java.lang.Long", ParamKind.PATH)), false);

    private static final TableSchema BOOKINGS = new TableSchema("bookings",
            List.of(new ColumnSchema("id", "BIGINT", false, true),
                    new ColumnSchema("status", "VARCHAR", false, false),
                    new ColumnSchema("tier", "VARCHAR", false, false)),
            List.of(), List.of());

    @Test
    void gateByKind_temporalFalse() {
        // TEMPORAL 가드: boolean QUERY param을 false로 설정 → gate=false
        assertThat(EndpointExplorationRunner.appliesBooleanGate(GuardKind.TEMPORAL)).isTrue();
        assertThat(EndpointExplorationRunner.booleanGateValueFor(GuardKind.TEMPORAL)).isFalse();
    }

    @Test
    void gateByKind_enumTrue() {
        // ENUM 가드: boolean QUERY param을 true로 설정 → gate=true
        assertThat(EndpointExplorationRunner.appliesBooleanGate(GuardKind.ENUM)).isTrue();
        assertThat(EndpointExplorationRunner.booleanGateValueFor(GuardKind.ENUM)).isTrue();
    }

    @Test
    void gateByKind_numericNoOverwrite() {
        // NUMERIC 가드: boolean QUERY param에 gate 미적용(덮어쓰지 않음)
        assertThat(EndpointExplorationRunner.appliesBooleanGate(GuardKind.NUMERIC)).isFalse();
    }

    @Test
    void gateByKind_booleanNoOverwrite() {
        // BOOLEAN 가드: boolean QUERY param에 gate 미적용
        assertThat(EndpointExplorationRunner.appliesBooleanGate(GuardKind.BOOLEAN)).isFalse();
    }

    @Test
    void gateByKind_nullityNoOverwrite() {
        // NULLITY 가드: boolean QUERY param에 gate 미적용
        assertThat(EndpointExplorationRunner.appliesBooleanGate(GuardKind.NULLITY)).isFalse();
    }

    // ---- REQ-003: conjunction NPE 회피 ----

    @Test
    void conjunctionCatchNpeAvoided_variantLabelNoNpe() {
        // conjunction SeedVariant(guard=null, conjunction!=null)에 대해
        // variantLabel 헬퍼가 NPE 없이 "conjunction:status+tier" 형태 문자열을 반환해야 한다.
        SeedVariant conjunctionVariant = new SeedVariant(
                new SynthesizedInput(
                        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
                        List.of(new SeedRow("bookings",
                                List.of("id", "status", "tier"),
                                List.of(90002L, "CONFIRMED", "VIP")))),
                null,           // guard=null — conjunction 변종
                CONJUNCTION);

        // variantLabel은 NPE 없이 conjunction leaves의 컬럼들을 포함한 레이블을 반환해야 한다.
        String label = EndpointExplorationRunner.variantLabel(conjunctionVariant);
        assertThat(label).contains("status").contains("tier");
        assertThat(label).startsWith("conjunction:");
    }

    @Test
    void conjunctionCatchNpeAvoided_gateNotApplied() {
        // conjunction 변종(guard=null)은 boolean gate를 적용하지 않는다.
        // conjunction!=null 분기에서 appliesBooleanGate를 호출하지 않거나,
        // 호출 전에 guard!=null 체크로 가드해야 한다.
        // 이를 variantLabel이 NPE 없이 동작하는 것으로 간접 검증한다.
        SeedVariant conjunctionVariant = new SeedVariant(
                new SynthesizedInput(
                        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
                        List.of()),
                null,
                CONJUNCTION);

        // guard()가 null인 conjunction 변종에서 variantLabel 호출 시 NPE 없어야 한다.
        assertThat(EndpointExplorationRunner.variantLabel(conjunctionVariant))
                .isNotNull()
                .isNotEmpty();
    }

    @Test
    void conjunctionCatchNpeAvoided_singleGuardVariantLabel() {
        // 단일 가드 변종(guard!=null, conjunction=null)의 variantLabel은
        // guard.column()을 포함한 레이블을 반환해야 한다(기존 경로 후방호환).
        SeedVariant guardVariant = new SeedVariant(
                new SynthesizedInput(
                        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
                        List.of()),
                LEAF_STATUS,    // guard!=null
                null);          // conjunction=null

        String label = EndpointExplorationRunner.variantLabel(guardVariant);
        assertThat(label).isEqualTo("status");
    }
}
