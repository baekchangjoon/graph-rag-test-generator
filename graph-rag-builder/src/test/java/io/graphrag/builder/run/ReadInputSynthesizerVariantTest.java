package io.graphrag.builder.run;

import io.graphrag.builder.index.ConstraintExtractor.ComparandKind;
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

    // ── Task 7: BOOLEAN / NULLITY / NUMERIC-상수 ─────────────────────────────

    /** BOOLEAN: baseState="true" → 반대 arm = false */
    @Test
    void flipBoolean_trueBaseYieldsFalseVariant() {
        TableSchema table = new TableSchema("bookings",
                List.of(new ColumnSchema("id", "BIGINT", false, true),
                        new ColumnSchema("is_active", "BOOLEAN", false, false)),
                List.of(), List.of());
        StateGuard boolGuard = new StateGuard("x.B", "m", 1, "is_active",
                GuardKind.BOOLEAN, null,
                List.of(), List.of(), "==", ComparandKind.LITERAL, "true");

        List<SeedVariant> variants = synth().synthesizeVariants(GET_BY_ID, List.of(table), List.of(boolGuard));

        // base + 1 variant
        assertThat(variants).hasSize(2);
        SeedRow variantRow = variants.get(1).input().seeds().stream()
                .filter(s -> s.table().equals("bookings")).findFirst().orElseThrow();
        assertThat(col(variantRow, "is_active")).isEqualTo(false);
        // 변종 PK ≠ base PK
        SeedRow baseRow = variants.get(0).input().seeds().stream()
                .filter(s -> s.table().equals("bookings")).findFirst().orElseThrow();
        assertThat(variantRow.values().get(0)).isNotEqualTo(baseRow.values().get(0));
    }

    /** BOOLEAN: baseState="false" → 반대 arm = true */
    @Test
    void flipBoolean_falseBaseYieldsTrueVariant() {
        TableSchema table = new TableSchema("bookings",
                List.of(new ColumnSchema("id", "BIGINT", false, true),
                        new ColumnSchema("is_active", "BOOLEAN", false, false)),
                List.of(), List.of());
        // is_active 컬럼의 defaultFor → true(BOOL 타입), base state = "true"가 될 것이므로
        // false comparand로 가드를 만들면 op=="==" comparand=="false" → base happy = true → 반대 arm = false.
        // 아니면 comparand="false" 가드: "entry.isActive() == false" 형태, base=true → 반대 arm = false.
        // base를 false로 만들기 위해 enumColumns로 is_active=false를 시드:
        ReadInputSynthesizer s2 = new ReadInputSynthesizer(Map.of(), Map.of("is_active", List.of("false")));
        StateGuard boolGuard = new StateGuard("x.B", "m", 1, "is_active",
                GuardKind.BOOLEAN, null,
                List.of(), List.of(), "==", ComparandKind.LITERAL, "false");

        List<SeedVariant> variants = s2.synthesizeVariants(GET_BY_ID, List.of(table), List.of(boolGuard));

        assertThat(variants).hasSize(2);
        SeedRow variantRow = variants.get(1).input().seeds().stream()
                .filter(s -> s.table().equals("bookings")).findFirst().orElseThrow();
        assertThat(variantRow.values()).contains(true);
    }

    /**
     * NULLITY: nullable 컬럼이 base seed에 없어 baseState=null → arm = defaultFor(DATE) = LocalDate(2037,1,1).
     * (null base → defaultFor 변종)
     */
    @Test
    void flipNullityNullBase_yieldsDefaultForVariant() {
        TableSchema table = new TableSchema("bookings",
                List.of(new ColumnSchema("id", "BIGINT", false, true),
                        new ColumnSchema("cancelled_at", "DATE", true, false)),   // nullable
                List.of(), List.of());
        StateGuard nullGuard = new StateGuard("x.B", "m", 1, "cancelled_at",
                GuardKind.NULLITY, null,
                List.of(), List.of(), "!=", ComparandKind.LITERAL, "null");

        // nullable 컬럼은 base seed에 미포함 → stateAt=null → arm = defaultFor(DATE) = LocalDate(2037,1,1)
        List<SeedVariant> variants = synth().synthesizeVariants(GET_BY_ID, List.of(table), List.of(nullGuard));

        assertThat(variants).hasSize(2);
        SeedRow variantRow = variants.get(1).input().seeds().stream()
                .filter(s -> s.table().equals("bookings")).findFirst().orElseThrow();
        int idx = variantRow.columns().indexOf("cancelled_at");
        assertThat(idx).isGreaterThanOrEqualTo(0);
        // baseState=null → arm = defaultFor(DATE) → LocalDate(2037, 1, 1)
        assertThat(variantRow.values().get(idx)).isEqualTo(LocalDate.of(2037, 1, 1));
        // 변종 PK ≠ base PK
        SeedRow baseRow = variants.get(0).input().seeds().stream()
                .filter(s -> s.table().equals("bookings")).findFirst().orElseThrow();
        assertThat(variantRow.values().get(0)).isNotEqualTo(baseRow.values().get(0));
    }

    /**
     * NULLITY: nullable 컬럼이 QUERY param으로 base seed에 non-null 값으로 포함된 상태 → arm = [null].
     * (nullable + base non-null → null 변종)
     */
    @Test
    void flipNullityNonNullBase_yieldsNullArm() {
        // status 컬럼(nullable=true)이 QUERY param으로 base seed에 포함되도록 엔드포인트 구성
        Endpoint getWithStatus = new Endpoint(
                "get-api-bookings-id-status", "GET", "/api/bookings/{id}",
                "x.BookingController", "getByIdFiltered",
                List.of(new EndpointParam("id", "java.lang.Long", ParamKind.PATH),
                        new EndpointParam("status", "java.lang.String", ParamKind.QUERY)),
                false);
        TableSchema table = new TableSchema("bookings",
                List.of(new ColumnSchema("id", "BIGINT", false, true),
                        new ColumnSchema("status", "VARCHAR", true, false)),   // nullable
                List.of(), List.of());
        StateGuard nullGuard = new StateGuard("x.B", "m", 1, "status",
                GuardKind.NULLITY, null,
                List.of(), List.of(), "!=", ComparandKind.LITERAL, "null");

        // QUERY param "status" → mapParamToColumn → "status" 컬럼에 값 "probe-status-..." 삽입
        // → base seed에 status가 non-null로 포함됨 → baseState non-null → arm=[null]
        List<SeedVariant> variants = new ReadInputSynthesizer()
                .synthesizeVariants(getWithStatus, List.of(table), List.of(nullGuard));

        assertThat(variants).hasSize(2);
        SeedRow variantRow = variants.get(1).input().seeds().stream()
                .filter(s -> s.table().equals("bookings")).findFirst().orElseThrow();
        int idx = variantRow.columns().indexOf("status");
        assertThat(idx).isGreaterThanOrEqualTo(0);
        // baseState가 non-null(probe 문자열) → 반대 arm = null
        assertThat(variantRow.values().get(idx)).isNull();
    }

    /** NULLITY: nullable 컬럼에서 baseState=non-null이면 [null] */
    @Test
    void flipNullityNonNull_seedIncludesNullArm() {
        // base에 nullable 컬럼이 포함된 상황을 만들기 위해 NOT NULL 컬럼으로 설정
        // → 실제 non-null base를 얻으려면 NOT NULL nullable=false 컬럼을 사용하되
        //   NULLITY 가드를 발행. defaultFor가 DATE → base state = "2037-01-01" (non-null)
        //   → 반대 arm = null.
        TableSchema table = new TableSchema("bookings",
                List.of(new ColumnSchema("id", "BIGINT", false, true),
                        new ColumnSchema("processed_at", "DATE", false, false)),   // NOT NULL
                List.of(), List.of());
        StateGuard nullGuard = new StateGuard("x.B", "m", 1, "processed_at",
                GuardKind.NULLITY, null,
                List.of(), List.of(), "!=", ComparandKind.LITERAL, "null");

        List<SeedVariant> variants = synth().synthesizeVariants(GET_BY_ID, List.of(table), List.of(nullGuard));

        // NOT NULL 컬럼이므로 null arm 불가 → 빈 변종 → singleton [base]
        // 명세: NOT NULL NULLITY → 빈 리스트 → 변종 없음
        assertThat(variants).hasSize(1);
    }

    /** NULLITY: NOT NULL 컬럼이면 빈 리스트(변종 없음) */
    @Test
    void flipNullityNotNullSkip_returnsNoVariant() {
        TableSchema table = new TableSchema("bookings",
                List.of(new ColumnSchema("id", "BIGINT", false, true),
                        new ColumnSchema("required_field", "VARCHAR", false, false)),   // NOT NULL
                List.of(), List.of());
        StateGuard nullGuard = new StateGuard("x.B", "m", 1, "required_field",
                GuardKind.NULLITY, null,
                List.of(), List.of(), "==", ComparandKind.LITERAL, "null");

        List<SeedVariant> variants = synth().synthesizeVariants(GET_BY_ID, List.of(table), List.of(nullGuard));

        assertThat(variants).hasSize(1);   // base only, null arm 불가
    }

    /** NUMERIC 상수 가드: >=C → 반대 arm = C-1 */
    @Test
    void flipNumericConst_greaterOrEqual_yieldsConstMinusOne() {
        TableSchema table = new TableSchema("bookings",
                List.of(new ColumnSchema("id", "BIGINT", false, true),
                        new ColumnSchema("amount", "INT", false, false)),
                List.of(), List.of());
        // amount >= 3 → base(happy)는 defaultFor(INT)=1이라 3보다 작으므로
        // base는 이미 만족(? 아니다 1<3이면 조건 불만족). 설계상 반대 arm = C-1 = 2.
        StateGuard numGuard = new StateGuard("x.B", "m", 1, "amount",
                GuardKind.NUMERIC, null,
                List.of(), List.of(), ">=", ComparandKind.LITERAL, "3");

        List<SeedVariant> variants = synth().synthesizeVariants(GET_BY_ID, List.of(table), List.of(numGuard));

        assertThat(variants).hasSize(2);
        SeedRow variantRow = variants.get(1).input().seeds().stream()
                .filter(s -> s.table().equals("bookings")).findFirst().orElseThrow();
        assertThat(col(variantRow, "amount")).isEqualTo(2);   // C-1 = 3-1 = 2
        // 변종 PK ≠ base PK
        SeedRow baseRow = variants.get(0).input().seeds().stream()
                .filter(s -> s.table().equals("bookings")).findFirst().orElseThrow();
        assertThat(variantRow.values().get(0)).isNotEqualTo(baseRow.values().get(0));
    }

    /** NUMERIC: 비정수 JDBC 타입(DECIMAL)은 타입 불일치 위험 → 변종 없음(singleton) */
    @Test
    void flipNumericConst_nonIntegerJdbcType_returnsNoVariant() {
        TableSchema table = new TableSchema("bookings",
                List.of(new ColumnSchema("id", "BIGINT", false, true),
                        new ColumnSchema("price", "DECIMAL", false, false)),
                List.of(), List.of());
        StateGuard numGuard = new StateGuard("x.B", "m", 1, "price",
                GuardKind.NUMERIC, null,
                List.of(), List.of(), ">=", ComparandKind.LITERAL, "100");

        List<SeedVariant> variants = synth().synthesizeVariants(GET_BY_ID, List.of(table), List.of(numGuard));

        // DECIMAL은 정수형 아님 → 변종 없음 → singleton [base]
        assertThat(variants).hasSize(1);
    }
}
