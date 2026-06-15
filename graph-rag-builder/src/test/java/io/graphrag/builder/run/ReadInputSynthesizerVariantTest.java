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
    void returnsSingletonWhenGuardColumnAbsentOnTable() {
        StateGuard missing = new StateGuard("x.BookingController", "getById", 76, "no_such_column",
                GuardKind.TEMPORAL, null, List.of());
        List<SeedVariant> variants =
                synth().synthesizeVariants(GET_BY_ID, List.of(BOOKINGS), List.of(missing));

        assertThat(variants).hasSize(1);   // base only
    }
}
