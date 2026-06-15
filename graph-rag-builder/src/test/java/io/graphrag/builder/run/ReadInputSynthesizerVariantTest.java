package io.graphrag.builder.run;

import io.graphrag.builder.index.ConstraintExtractor.GuardKind;
import io.graphrag.builder.index.ConstraintExtractor.StateGuard;
import io.graphrag.builder.run.ReadInputSynthesizer.SeedVariant;
import io.graphrag.builder.run.SynthesizedInput.SeedRow;
import io.graphrag.model.ColumnSchema;
import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ParamKind;
import io.graphrag.model.TableSchema;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Stage 4: 상태가드별 대체 시드 변종 합성 (양-arm 시드). */
class ReadInputSynthesizerVariantTest {

    private static final Endpoint GET_BY_ID = new Endpoint(
            "get-api-bookings-id", "GET", "/api/bookings/{id}", "x.BookingController", "getById",
            List.of(new EndpointParam("id", "java.lang.Long", ParamKind.PATH)), false);

    private static final TableSchema BOOKINGS = new TableSchema("bookings",
            List.of(new ColumnSchema("id", "BIGINT", false, true),
                    new ColumnSchema("check_in_date", "DATE", false, false),
                    new ColumnSchema("status", "VARCHAR", false, false),
                    new ColumnSchema("customer_email", "VARCHAR", false, false)),
            List.of(), List.of());

    private static final StateGuard TEMPORAL =
            new StateGuard("x.BookingController", "getById", 76, "check_in_date",
                    GuardKind.TEMPORAL, null, List.of());
    private static final StateGuard ENUM =
            new StateGuard("x.BookingController", "delete", 115, "status",
                    GuardKind.ENUM, "BookingStatus", List.of("CANCELLED", "PENDING"));
    /** EQ 다중 전이: status == PENDING / CONFIRMED / CANCELLED → positive 3개, negated 없음. */
    private static final StateGuard ENUM_EQ_MULTI =
            new StateGuard("x.BookingController", "advance", 130, "status",
                    GuardKind.ENUM, "BookingStatus", List.of(), List.of("PENDING", "CONFIRMED", "CANCELLED"));

    private static ReadInputSynthesizer synth() {
        // enumColumns: status 컬럼의 유효값(가드의 != 상수들) → base status = 사전순 첫째 CANCELLED
        // enumConstants: BookingStatus 전체 상수(FQN 키 — simple-name 폴백으로 해석)
        return new ReadInputSynthesizer(
                Map.of("io.graphrag.sample.orders.BookingController.BookingStatus",
                        List.of("PENDING", "CONFIRMED", "CANCELLED")),
                Map.of("status", List.of("CANCELLED", "PENDING")));
    }

    private static SeedRow bookingsRow(SeedVariant v) {
        return v.input().seeds().stream().filter(s -> s.table().equals("bookings"))
                .findFirst().orElseThrow();
    }

    private static Object col(SeedRow row, String column) {
        return row.values().get(row.columns().indexOf(column));
    }

    @Test
    void synthesizesBasePlusOneVariantPerApplicableGuard() {
        List<SeedVariant> variants =
                synth().synthesizeVariants(GET_BY_ID, List.of(BOOKINGS), List.of(TEMPORAL, ENUM));

        assertThat(variants).hasSize(3);   // base + temporal + enum
        // base: 미래 날짜 + CANCELLED(부정집합 사전순 첫째)
        SeedRow base = bookingsRow(variants.get(0));
        assertThat(col(base, "check_in_date")).isEqualTo(LocalDate.of(2037, 1, 1));
        assertThat(col(base, "status")).isEqualTo("CANCELLED");
    }

    @Test
    void temporalVariantFlipsOnlyDateToPast_enumVariantFlipsOnlyStatus() {
        List<SeedVariant> variants =
                synth().synthesizeVariants(GET_BY_ID, List.of(BOOKINGS), List.of(TEMPORAL, ENUM));

        SeedRow temporal = variants.subList(1, 3).stream().map(ReadInputSynthesizerVariantTest::bookingsRow)
                .filter(r -> col(r, "check_in_date").equals(LocalDate.of(1900, 1, 1))).findFirst().orElseThrow();
        assertThat(col(temporal, "status")).isEqualTo("CANCELLED");   // status는 안 건드림

        SeedRow enumRow = variants.subList(1, 3).stream().map(ReadInputSynthesizerVariantTest::bookingsRow)
                .filter(r -> col(r, "status").equals("CONFIRMED")).findFirst().orElseThrow();
        assertThat(col(enumRow, "check_in_date")).isEqualTo(LocalDate.of(2037, 1, 1));   // 날짜는 안 건드림
    }

    @Test
    void allRowsKeepPkAtIndexZero_withDistinctNonCollidingPks() {
        List<SeedVariant> variants =
                synth().synthesizeVariants(GET_BY_ID, List.of(BOOKINGS), List.of(TEMPORAL, ENUM));

        assertThat(variants).map(ReadInputSynthesizerVariantTest::bookingsRow)
                .allSatisfy(r -> assertThat(r.columns().get(0)).isEqualTo("id"));
        List<Object> pks = variants.stream().map(ReadInputSynthesizerVariantTest::bookingsRow)
                .map(r -> r.values().get(0)).toList();
        assertThat(pks).doesNotHaveDuplicates();
    }

    @Test
    void eqGuardSeedsVariantPerPositiveStateExcludingHappy() {
        // base status=CANCELLED(enumColumns 첫). positive={PENDING,CONFIRMED,CANCELLED} - happy(CANCELLED)
        // → CONFIRMED/PENDING 두 변종(다중 전이 arm). 전체 enum이 positive라 else-arm 잔여 없음.
        List<SeedVariant> variants =
                synth().synthesizeVariants(GET_BY_ID, List.of(BOOKINGS), List.of(ENUM_EQ_MULTI));

        assertThat(variants).hasSize(3);   // base + 2
        List<Object> statuses = variants.subList(1, 3).stream()
                .map(ReadInputSynthesizerVariantTest::bookingsRow).map(r -> col(r, "status")).toList();
        assertThat(statuses).containsExactlyInAnyOrder("CONFIRMED", "PENDING");
        // 변종 PK는 모두 고유(전역 variantIdx offset)
        assertThat(variants.stream().map(ReadInputSynthesizerVariantTest::bookingsRow)
                .map(r -> r.values().get(0)).toList()).doesNotHaveDuplicates();
    }

    @Test
    void neGuardWithMultipleResidualsSeedsVariantPerResidual() {
        // negated={PENDING}만 → 잔여 {CONFIRMED, CANCELLED} 2개. base=CANCELLED(happy) 제외 → CONFIRMED 1개?
        // base가 잔여 중 하나(CANCELLED)면 그것 제외 → CONFIRMED. 잔여 다중 검증 위해 base 밖 enum 사용:
        StateGuard ne1 = new StateGuard("x.B", "m", 1, "status",
                GuardKind.ENUM, "BookingStatus", List.of("PENDING"), List.of());
        // enumColumns base=CANCELLED. 잔여(전체-PENDING)={CONFIRMED,CANCELLED} - happy(CANCELLED) → {CONFIRMED}.
        List<SeedVariant> variants = synth().synthesizeVariants(GET_BY_ID, List.of(BOOKINGS), List.of(ne1));
        assertThat(variants.subList(1, variants.size()).stream()
                .map(ReadInputSynthesizerVariantTest::bookingsRow).map(r -> col(r, "status")))
                .containsExactly("CONFIRMED");   // 잔여에서 happy(CANCELLED) 제외
    }

    @Test
    void variantsCappedAtFourWithDeterministicOrder() {
        // enum 6상수 전부 positive → happy 제외 5개 적격, cap=4로 절단(사전순 앞 4).
        ReadInputSynthesizer s = new ReadInputSynthesizer(
                Map.of("E", List.of("S0", "S1", "S2", "S3", "S4", "S5")),
                Map.of("status", List.of("S5")));   // base=S5
        StateGuard eq6 = new StateGuard("x.B", "m", 1, "status", GuardKind.ENUM, "E",
                List.of(), List.of("S0", "S1", "S2", "S3", "S4", "S5"));
        List<SeedVariant> variants = s.synthesizeVariants(GET_BY_ID, List.of(BOOKINGS), List.of(eq6));
        // base + 4 변종(S5는 happy 제외, 나머지 5 중 사전순 앞 4 = S0..S3)
        assertThat(variants).hasSize(5);
        assertThat(variants.subList(1, 5).stream()
                .map(ReadInputSynthesizerVariantTest::bookingsRow).map(r -> col(r, "status")))
                .containsExactly("S0", "S1", "S2", "S3");
    }

    @Test
    void returnsSingletonWhenGuardColumnAbsentOnTable() {
        StateGuard missing = new StateGuard("x.BookingController", "getById", 76, "no_such_column",
                GuardKind.TEMPORAL, null, List.of());
        List<SeedVariant> variants =
                synth().synthesizeVariants(GET_BY_ID, List.of(BOOKINGS), List.of(missing));

        assertThat(variants).hasSize(1);   // base only
    }
}
