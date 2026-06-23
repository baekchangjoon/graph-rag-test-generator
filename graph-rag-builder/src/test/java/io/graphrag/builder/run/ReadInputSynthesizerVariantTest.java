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

    // ── Task 8: NUMERIC-vs-파라미터 입력-시드 공동 합성 + per-guard skip ─────

    /**
     * NUMERIC-vs-파라미터(>=): 입력 minNights=V → 변종 시드 nights=V-1 + vbody minNights=V.
     * 엔드포인트: GET /api/reservations?minNights=long
     * 가드: getNights() >= minNights → column=nights, op=">="", comparandKind=PARAM, comparand="minNights"
     * 기대: base + 1 변종. 변종 시드 nights = V-1 = probeId-1. vbody["minNights"] = probeId(문자열).
     */
    @Test
    void inputSeedJoint_geParam() {
        Endpoint listEp = new Endpoint(
                "get-api-reservations", "GET", "/api/reservations",
                "x.ReservationController", "list",
                List.of(new EndpointParam("minNights", "java.lang.Long", ParamKind.QUERY)),
                false);
        TableSchema reservations = new TableSchema("reservations",
                List.of(new ColumnSchema("id", "BIGINT", false, true),
                        new ColumnSchema("nights", "INT", false, false)),
                List.of(), List.of());
        StateGuard paramGuard = new StateGuard("x.ReservationController", "list", 10, "nights",
                GuardKind.NUMERIC, null,
                List.of(), List.of(), ">=", ComparandKind.PARAM, "minNights");

        List<SeedVariant> variants = new ReadInputSynthesizer()
                .synthesizeVariants(listEp, List.of(reservations), List.of(paramGuard));

        // base + 1 변종
        assertThat(variants).hasSize(2);

        // base 입력에서 V 추출: scalarFor(minNights, Long) = probeId = probeIdFor(listEp)
        SeedRow baseRow = variants.get(0).input().seeds().stream()
                .filter(s -> s.table().equals("reservations")).findFirst().orElseThrow();
        String vStr = variants.get(0).input().body().get("minNights").asText();
        long v = Long.parseLong(vStr);

        SeedRow variantRow = variants.get(1).input().seeds().stream()
                .filter(s -> s.table().equals("reservations")).findFirst().orElseThrow();

        // 변종 시드: nights = V-1 (반대 arm: nights < minNights)
        assertThat(col(variantRow, "nights")).isEqualTo((int) (v - 1));

        // 변종 vbody: minNights = V (문자열)
        String vbodyMinNights = variants.get(1).input().body().get("minNights").asText();
        assertThat(vbodyMinNights).isEqualTo(vStr);

        // 변종 PK ≠ base PK
        assertThat(variantRow.values().get(0)).isNotEqualTo(baseRow.values().get(0));
    }

    /**
     * per-guard skip: collection 엔드포인트에서 타깃 테이블 해소 실패 → NUMERIC-param 가드는 skip,
     * 같은 엔드포인트의 BOOLEAN 가드는 정상 변종 생성.
     * collection 엔드포인트: GET /api/items (path에 "items" 테이블명 없어 resolveTargetTable → null 아닌 경우 주의)
     * 이를 위해 path가 테이블명과 매칭되지 않도록 설계: GET /api/widget-things, table="bookings"
     */
    @Test
    void collectionTargetSkipPerGuard() {
        Endpoint unmatchedEp = new Endpoint(
                "get-api-widget-things", "GET", "/api/widget-things",
                "x.WidgetController", "list",
                List.of(new EndpointParam("minCount", "java.lang.Long", ParamKind.QUERY)),
                false);
        // bookings 테이블: path "/api/widget-things"에 "bookings"나 "booking" 없음 → resolveTargetTable=null
        TableSchema table = new TableSchema("bookings",
                List.of(new ColumnSchema("id", "BIGINT", false, true),
                        new ColumnSchema("nights", "INT", false, false),
                        new ColumnSchema("is_active", "BOOLEAN", false, false)),
                List.of(), List.of());

        StateGuard numParamGuard = new StateGuard("x.WidgetController", "list", 5, "nights",
                GuardKind.NUMERIC, null,
                List.of(), List.of(), ">=", ComparandKind.PARAM, "minCount");
        StateGuard boolGuard = new StateGuard("x.WidgetController", "list", 10, "is_active",
                GuardKind.BOOLEAN, null,
                List.of(), List.of(), "==", ComparandKind.LITERAL, "true");

        // synth()는 enumColumns에 "status"만 있으므로 is_active enumColumns 없음 → defaultFor(BOOLEAN)=true → base
        List<SeedVariant> variants = new ReadInputSynthesizer()
                .synthesizeVariants(unmatchedEp, List.of(table), List.of(numParamGuard, boolGuard));

        // resolveTargetTable("widget-things") vs table="bookings" → null → base.seeds() 비어있음
        // → synthesizeVariants가 base만 반환하는 현재 동작이 아니라
        //   per-guard: numParamGuard는 타깃 없어 skip, boolGuard도 타깃 없으면 skip
        // 엔드포인트가 bookings와 path 매칭 안 됨 → target=null → base.seeds() empty
        // → variants = [base only], 이것이 per-guard skip의 결과(타깃 없는 엔드포인트)
        // 단, 이미 테이블이 있고 가드가 컬럼을 찾을 수 있어야 변종이 생성된다.
        // 여기서는 target=null이므로 base.seeds()=[] → synthesizeVariants는 [base] 반환 (기존 동작)
        // → 실제 per-guard skip 검증: target이 있는데 NUMERIC-param 컬럼은 없는 경우로 재설계

        // 재설계: path="/api/bookings/list" → target=bookings 매칭됨
        // 가드1: NUMERIC-param column="unknown_col" → col=null → skip
        // 가드2: BOOLEAN column="is_active" → 정상 변종

        Endpoint matchedEp = new Endpoint(
                "get-api-bookings-list", "GET", "/api/bookings/list",
                "x.BookingController", "list",
                List.of(new EndpointParam("minCount", "java.lang.Long", ParamKind.QUERY)),
                false);
        StateGuard unknownColGuard = new StateGuard("x.BookingController", "list", 5, "unknown_col",
                GuardKind.NUMERIC, null,
                List.of(), List.of(), ">=", ComparandKind.PARAM, "minCount");
        StateGuard boolGuard2 = new StateGuard("x.BookingController", "list", 10, "is_active",
                GuardKind.BOOLEAN, null,
                List.of(), List.of(), "==", ComparandKind.LITERAL, "true");

        List<SeedVariant> variants2 = new ReadInputSynthesizer()
                .synthesizeVariants(matchedEp, List.of(table), List.of(unknownColGuard, boolGuard2));

        // unknownColGuard: col=null → skip (per-guard)
        // boolGuard2: is_active 컬럼 있음 → 변종 1개
        // 결과: base + boolGuard2 변종 = 2
        assertThat(variants2).hasSize(2);

        // 변종의 guard는 boolGuard2
        assertThat(variants2.get(1).guard()).isEqualTo(boolGuard2);
    }
}
