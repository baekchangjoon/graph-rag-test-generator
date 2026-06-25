package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.index.ConstraintExtractor;
import io.graphrag.model.ColumnSchema;
import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ForeignKey;
import io.graphrag.model.Json;
import io.graphrag.model.ParamKind;
import io.graphrag.model.TableSchema;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 조회(GET) 엔드포인트의 read-path 입력 + 시드를 결정적으로 합성한다.
 * path/query param을 WHERE 제약으로 보고, 타깃 테이블에 매칭 행을 시드한다.
 */
public class ReadInputSynthesizer {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ReadInputSynthesizer.class);

    private final Map<String, List<String>> enumConstants;
    private final Map<String, List<String>> enumColumns;   // 소문자 컬럼명 → 유효 enum 상수(시드 가독성)

    public ReadInputSynthesizer() {
        this(Map.of(), Map.of());
    }

    public ReadInputSynthesizer(Map<String, List<String>> enumConstants) {
        this(enumConstants, Map.of());
    }

    public ReadInputSynthesizer(Map<String, List<String>> enumConstants,
                                Map<String, List<String>> enumColumns) {
        this.enumConstants = enumConstants;
        this.enumColumns = enumColumns;
    }

    /**
     * 시드 PK/FK에 쓰는 비충돌 정수 id의 기준값. SUT가 data.sql 등으로 미리 시드한 행(보통 1..N)과
     * 겹치지 않도록 충분히 큰 값을 쓴다 → (a) 시드 INSERT가 기존 행과 충돌하지 않고
     * (b) 시드가 실효(GET이 시드한 행을 반환)하여 관측 응답이 시드를 반영하며
     * (c) cleanup이 SUT 기준 데이터가 아닌 자기 행만 삭제한다.
     * 엔드포인트마다 다른 값을 써서, 한 분석 DB를 공유하는 탐색 중 엔드포인트 간 시드 오염
     * (예: list가 심은 owner를 by-id가 ON CONFLICT no-op로 관측)도 방지한다.
     */
    private static final int PROBE_ID_BASE = 90001;

    public SynthesizedInput synthesize(Endpoint endpoint, List<TableSchema> tables) {
        return synthesize(endpoint, tables, null);
    }

    public SynthesizedInput synthesize(Endpoint endpoint, List<TableSchema> tables, ResolutionHint hint) {
        ObjectNode input = Json.mapper().createObjectNode();
        TableSchema target = resolveTargetTable(endpoint, tables, hint);
        int probeId = probeIdFor(endpoint);

        // PK/FK 키 컬럼은 비충돌 probe 값, 일반 NOT NULL 컬럼은 타입 기본값
        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        if (target != null) {
            // PK 컬럼을 먼저 삽입하여 index 0을 보장 (FixtureComposer의 DELETE 키)
            for (ColumnSchema column : target.columns()) {
                if (column.primaryKey()) {
                    columns.add(column.name());
                    values.add(keyProbe(column, probeId));
                }
            }
            for (ColumnSchema column : target.columns()) {
                if (!column.primaryKey() && !column.nullable()) {
                    ForeignKey fk = findFk(column.name(), target);
                    columns.add(column.name());
                    values.add(fk != null ? keyProbe(column, probeId) : defaultFor(column));
                }
            }
        }

        for (EndpointParam param : endpoint.params()) {
            if (param.kind() != ParamKind.PATH && param.kind() != ParamKind.QUERY) {
                continue;
            }
            String value = scalarFor(param, probeId);
            input.put(param.name(), value);
            String column = mapParamToColumn(param, target, hint);
            if (column != null) {
                // seed 값은 컬럼 JDBC 타입에 맞춰야 한다. 입력 JSON엔 문자열로 두되
                // (path/query는 어차피 텍스트), seed row에는 타입 일치 값을 넣는다
                // — bigint 컬럼에 varchar "1"을 넣으면 INSERT가 깨진다.
                Object seedValue = coerceForColumn(value, column, target);
                int idx = columns.indexOf(column);
                if (idx >= 0) {
                    values.set(idx, seedValue);
                } else {
                    columns.add(column);
                    values.add(seedValue);
                }
            }
        }

        List<SynthesizedInput.SeedRow> seeds;
        if (target == null) {
            seeds = List.of();
        } else {
            List<SynthesizedInput.SeedRow> allSeeds = new ArrayList<>();
            // FK 부모 시드를 target 시드보다 먼저 수집 (parent-before-child).
            // 시드 컬럼에 포함된 FK(= NOT NULL/PK)만 부모를 시드한다. nullable FK는
            // 자식 row에서 값이 없으므로(컬럼 미포함) 부모 행이 필요 없다.
            Set<String> visited = new HashSet<>();
            for (ForeignKey fk : target.foreignKeys()) {
                int fkIdx = columns.indexOf(fk.column());
                if (fkIdx < 0) {
                    continue;
                }
                seedParent(fk.referencedTable(), fk.referencedColumn(), values.get(fkIdx),
                        tables, visited, allSeeds, probeId);
            }
            allSeeds.add(new SynthesizedInput.SeedRow(target.name(), columns, values));
            seeds = allSeeds;
        }
        return new SynthesizedInput(input, seeds);
    }

    /**
     * 상태가드/conjunction 변종 시드. guard 또는 conjunction 중 하나만 non-null.
     * <ul>
     *   <li>단일 가드 변종: guard!=null, conjunction==null</li>
     *   <li>conjunction 변종: guard==null, conjunction!=null</li>
     *   <li>base: 둘 다 null</li>
     * </ul>
     */
    public record SeedVariant(SynthesizedInput input, ConstraintExtractor.StateGuard guard,
                              ConstraintExtractor.StateGuardConjunction conjunction) {
        /** XOR 불변식: guard와 conjunction은 동시에 non-null일 수 없다. */
        public SeedVariant {
            if (guard != null && conjunction != null) {
                throw new IllegalArgumentException(
                        "SeedVariant: guard and conjunction are mutually exclusive");
            }
        }

        /** 기존 2-arg 호출부 후방호환 오버로드 (conjunction=null 위임). */
        public SeedVariant(SynthesizedInput input, ConstraintExtractor.StateGuard guard) {
            this(input, guard, null);
        }
    }

    /**
     * 상태 의존 가드(TEMPORAL/ENUM)별 대체 시드 변종 합성 (Stage 4 양-arm 시드).
     * 반환: [base(guard=null)] + 적용 가드별 변종 1개(guard 동봉 — 러너의 게이팅 쿼리 param 결정용).
     * 변종은 (i) 타깃 행만 클론(FK 부모는 공유), (ii) columns[0]=PK 유지하되 offset PK로 충돌 회피,
     * (iii) 가드 컬럼만 결정적 flip 값으로 덮어쓰고 그 변종의 path PK param을 offset PK로 갱신.
     * 적용 가드 없으면 singleton [base].
     */
    public List<SeedVariant> synthesizeVariants(Endpoint endpoint, List<TableSchema> tables,
                                                List<ConstraintExtractor.StateGuard> guards) {
        return synthesizeVariants(endpoint, tables, guards, List.of());
    }

    /**
     * 단일 가드 + conjunction(저장행 복합 AND) 변종 합성 (REQ-004/005).
     * 단일 가드 변종(위 오버로드와 동일) 다음에, conjunction마다 모든 leaf를 동시에 만족하는 시드 1행을
     * variantIdx 연속(offsetPk 충돌 0)으로 추가한다. conjunction 변종의 guard는 null, conjunction 동봉.
     */
    public List<SeedVariant> synthesizeVariants(Endpoint endpoint, List<TableSchema> tables,
                                                List<ConstraintExtractor.StateGuard> guards,
                                                List<ConstraintExtractor.StateGuardConjunction> conjunctions) {
        List<ConstraintExtractor.StateGuard> safeGuards = guards == null ? List.of() : guards;
        List<ConstraintExtractor.StateGuardConjunction> safeConjunctions =
                conjunctions == null ? List.of() : conjunctions;
        SynthesizedInput base = synthesize(endpoint, tables);
        if ((safeGuards.isEmpty() && safeConjunctions.isEmpty()) || base.seeds().isEmpty()) {
            return List.of(new SeedVariant(base, null));
        }
        TableSchema target = resolveTargetTable(endpoint, tables, null);
        if (target == null) {
            return List.of(new SeedVariant(base, null));
        }
        int targetIdx = -1;
        for (int i = 0; i < base.seeds().size(); i++) {
            if (base.seeds().get(i).table().equals(target.name())) {
                targetIdx = i;
                break;
            }
        }
        if (targetIdx < 0) {
            return List.of(new SeedVariant(base, null));
        }

        String pkColumn = target.columns().stream().filter(ColumnSchema::primaryKey)
                .map(ColumnSchema::name).findFirst().orElse(null);
        // base 시드의 타깃 행 — NUMERIC-vs-PARAM 가드별 만족 arm 보정을 누적하기 위한 가변 복사본.
        // 루프 후 보정된 행으로 out[0] (base SeedVariant)를 교체한다.
        SynthesizedInput.SeedRow baseTargetRow = base.seeds().get(targetIdx);
        List<String> baseCols = new ArrayList<>(baseTargetRow.columns());
        List<Object> baseVals = new ArrayList<>(baseTargetRow.values());
        boolean baseModified = false;

        List<SeedVariant> out = new ArrayList<>();
        out.add(new SeedVariant(base, null));   // 플레이스홀더; 루프 후 보정된 base로 교체
        int variantIdx = 0;
        for (ConstraintExtractor.StateGuard guard : safeGuards) {
            // NUMERIC-vs-파라미터: 입력-시드 공동 합성 (§3.3)
            if (guard.kind() == ConstraintExtractor.GuardKind.NUMERIC
                    && guard.comparandKind() == ConstraintExtractor.ComparandKind.PARAM) {
                ColumnSchema col = target.columns().stream()
                        .filter(c -> c.name().equalsIgnoreCase(guard.column())).findFirst().orElse(null);
                if (col == null) {
                    continue;   // per-guard skip: 타깃 컬럼 해소 실패 → 이 가드만 건너뜀
                }
                String paramName = guard.comparand();   // 비교 파라미터명 P
                // REQ-014: comparand(P)가 엔드포인트 QUERY/PATH 파라미터와 매칭될 때만 합성 대상.
                // 직접 동명 또는 camelToSnake 동치로 매칭. 매칭 없으면 per-guard skip.
                boolean matchedEndpointParam = endpoint.params().stream()
                        .anyMatch(p -> (p.kind() == ParamKind.QUERY || p.kind() == ParamKind.PATH)
                                && (p.name().equals(paramName)
                                        || camelToSnake(p.name()).equals(camelToSnake(paramName))));
                if (!matchedEndpointParam) {
                    continue;   // per-guard skip: comparand가 엔드포인트 파라미터와 불일치 → 변종 생성 안 함
                }
                // 입력값 V: base.body()는 path/query param을 flat-merge한 ObjectNode (synthesize 참고)
                com.fasterxml.jackson.databind.JsonNode paramNode = base.body().get(paramName);
                long v;
                if (paramNode != null) {
                    v = paramNode.asLong();
                } else {
                    // 매칭됐으나 body에 없으면 probeId로 결정적 대체 (안전망; 보통 body에 값 있음)
                    v = probeIdFor(endpoint);
                }
                // 변종 시드 컬럼값: 불만족 arm (§3.3 op별 표)
                long variantColValue = numericParamVariantCol(guard.op(), v);
                // JDBC 타입에 맞춰 시드값 결정
                Object variantColObj = coerceToColumnType(variantColValue, col);
                if (variantColObj == null) {
                    continue;   // 비정수 JDBC 타입 → per-guard skip
                }
                // base 시드 가드 컬럼을 만족 arm 값으로 보정 (§3.3 표: base col = numericParamBaseCol(op, V))
                long baseColValue = numericParamBaseCol(guard.op(), v);
                Object baseColObj = coerceToColumnType(baseColValue, col);
                if (baseColObj != null) {
                    int bi = indexOfIgnoreCase(baseCols, guard.column());
                    if (bi >= 0) {
                        baseVals.set(bi, baseColObj);
                    } else {
                        baseCols.add(col.name());
                        baseVals.add(baseColObj);
                    }
                    baseModified = true;
                }
                variantIdx++;
                SynthesizedInput.SeedRow targetRow = base.seeds().get(targetIdx);
                List<String> cols = new ArrayList<>(targetRow.columns());
                List<Object> vals = new ArrayList<>(targetRow.values());
                Object variantPk = offsetPk(vals.get(0), variantIdx);
                vals.set(0, variantPk);
                int gi = indexOfIgnoreCase(cols, guard.column());
                if (gi >= 0) {
                    vals.set(gi, variantColObj);
                } else {
                    cols.add(col.name());
                    vals.add(variantColObj);
                }
                List<SynthesizedInput.SeedRow> variantSeeds = new ArrayList<>();
                for (int i = 0; i < base.seeds().size(); i++) {
                    variantSeeds.add(i == targetIdx
                            ? new SynthesizedInput.SeedRow(targetRow.table(), cols, vals)
                            : base.seeds().get(i));
                }
                // 변종 vbody: 비교 파라미터 P=V 명시 고정 (기준값 V 공유)
                ObjectNode vbody = (ObjectNode) base.body().deepCopy();
                vbody.put(paramName, String.valueOf(v));
                // PK 매핑(기존 공통 로직과 동일)
                if (pkColumn != null) {
                    for (EndpointParam param : endpoint.params()) {
                        if ((param.kind() == ParamKind.PATH || param.kind() == ParamKind.QUERY)
                                && pkColumn.equalsIgnoreCase(mapParamToColumn(param, target, null))) {
                            vbody.put(param.name(), String.valueOf(variantPk));
                        }
                    }
                }
                out.add(new SeedVariant(new SynthesizedInput(vbody, variantSeeds), guard));
                continue;
            }

            // 기존 경로: TEMPORAL / ENUM / BOOLEAN / NULLITY / NUMERIC-상수
            ColumnSchema col = target.columns().stream()
                    .filter(c -> c.name().equalsIgnoreCase(guard.column())).findFirst().orElse(null);
            if (col == null) {
                continue;   // 가드 컬럼이 타깃 테이블에 없으면 skip (스키마 가드, 보수적)
            }
            SynthesizedInput.SeedRow targetRow = base.seeds().get(targetIdx);
            String baseState = stateAt(targetRow, guard.column());   // happy 상태(중복 변종 회피용)
            // 다중 전이 arm: 가드가 구분하는 상태값(EQ 각 상수 + else 잔여 1개, NE 잔여)별로 변종.
            for (Object flip : flipValues(guard, col, baseState)) {
                variantIdx++;   // 전역 — EQ/NE가 같은 상태명을 내도 offset PK가 고유
                List<String> cols = new ArrayList<>(targetRow.columns());
                List<Object> vals = new ArrayList<>(targetRow.values());
                Object variantPk = offsetPk(vals.get(0), variantIdx);
                vals.set(0, variantPk);
                int gi = indexOfIgnoreCase(cols, guard.column());
                if (gi >= 0) {
                    vals.set(gi, flip);
                } else {
                    cols.add(col.name());   // base seed에 없던(nullable) 가드 컬럼 추가 → arm을 연다
                    vals.add(flip);
                }
                List<SynthesizedInput.SeedRow> variantSeeds = new ArrayList<>();
                for (int i = 0; i < base.seeds().size(); i++) {
                    variantSeeds.add(i == targetIdx
                            ? new SynthesizedInput.SeedRow(targetRow.table(), cols, vals)
                            : base.seeds().get(i));   // FK 부모 공유(동일 PK)
                }
                ObjectNode vbody = (ObjectNode) base.body().deepCopy();
                if (pkColumn != null) {
                    for (EndpointParam param : endpoint.params()) {
                        if ((param.kind() == ParamKind.PATH || param.kind() == ParamKind.QUERY)
                                && pkColumn.equalsIgnoreCase(mapParamToColumn(param, target, null))) {
                            vbody.put(param.name(), String.valueOf(variantPk));
                        }
                    }
                }
                out.add(new SeedVariant(new SynthesizedInput(vbody, variantSeeds), guard));
            }
        }
        // conjunction(저장행 복합 AND) 변종: leaf 전부 동시 만족하는 시드 1행. 단일 가드 변종 다음
        // variantIdx부터 연속(offsetPk 충돌 0). 보정된 base 행(baseCols/baseVals)을 출발점으로 복제.
        for (ConstraintExtractor.StateGuardConjunction conjunction : safeConjunctions) {
            // 같은 컬럼 leaf 병합: column(소문자)별로 묶어 단일 만족값 산출.
            LinkedHashMap<String, List<ConstraintExtractor.StateGuard>> byColumn = new LinkedHashMap<>();
            for (ConstraintExtractor.StateGuard leaf : conjunction.leaves()) {
                byColumn.computeIfAbsent(leaf.column().toLowerCase(), k -> new ArrayList<>()).add(leaf);
            }
            LinkedHashMap<String, Object> columnToValue = new LinkedHashMap<>();
            boolean skip = false;
            for (Map.Entry<String, List<ConstraintExtractor.StateGuard>> entry : byColumn.entrySet()) {
                ColumnSchema col = target.columns().stream()
                        .filter(c -> c.name().equalsIgnoreCase(entry.getKey())).findFirst().orElse(null);
                if (col == null) {   // 타깃 테이블에 없는 컬럼 → conjunction skip
                    skip = true;
                    break;
                }
                Object value = mergedSatisfyingValue(entry.getValue(), col);
                if (value == CONJ_SKIP) {   // 모순/만족값 없음/NOT NULL null 등 → conjunction skip
                    skip = true;
                    break;
                }
                columnToValue.put(col.name(), value);
            }
            if (skip || columnToValue.isEmpty()) {
                continue;   // conjunction 변종 미생성 (best-effort)
            }
            variantIdx++;
            // 보정된 base 행 복제 후 각 leaf 컬럼을 만족값으로 동시 설정.
            List<String> cols = new ArrayList<>(baseCols);
            List<Object> vals = new ArrayList<>(baseVals);
            Object variantPk = offsetPk(vals.get(0), variantIdx);
            vals.set(0, variantPk);
            for (Map.Entry<String, Object> cv : columnToValue.entrySet()) {
                int ci = indexOfIgnoreCase(cols, cv.getKey());
                if (ci >= 0) {
                    vals.set(ci, cv.getValue());
                } else {
                    cols.add(cv.getKey());   // base seed에 없던(nullable) 컬럼 추가 → arm을 연다
                    vals.add(cv.getValue());
                }
            }
            List<SynthesizedInput.SeedRow> variantSeeds = new ArrayList<>();
            for (int i = 0; i < base.seeds().size(); i++) {
                variantSeeds.add(i == targetIdx
                        ? new SynthesizedInput.SeedRow(baseTargetRow.table(), cols, vals)
                        : base.seeds().get(i));   // FK 부모 공유
            }
            ObjectNode vbody = (ObjectNode) base.body().deepCopy();
            if (pkColumn != null) {
                for (EndpointParam param : endpoint.params()) {
                    if ((param.kind() == ParamKind.PATH || param.kind() == ParamKind.QUERY)
                            && pkColumn.equalsIgnoreCase(mapParamToColumn(param, target, null))) {
                        vbody.put(param.name(), String.valueOf(variantPk));
                    }
                }
            }
            out.add(new SeedVariant(new SynthesizedInput(vbody, variantSeeds), null, conjunction));
        }
        // NUMERIC-vs-PARAM 가드에서 만족 arm 값으로 보정된 base 시드 행이 있으면 out[0]을 교체한다.
        // 이렇게 해야 base가 실제로 만족 arm을 타고 변종과 양 arm이 갈린다.
        if (baseModified) {
            List<SynthesizedInput.SeedRow> correctedSeeds = new ArrayList<>();
            for (int i = 0; i < base.seeds().size(); i++) {
                correctedSeeds.add(i == targetIdx
                        ? new SynthesizedInput.SeedRow(baseTargetRow.table(), baseCols, baseVals)
                        : base.seeds().get(i));
            }
            out.set(0, new SeedVariant(new SynthesizedInput(base.body(), correctedSeeds), null));
        }
        return out;
    }

    /** 변종 상한(컬럼당) — enum 상수가 많은 경우 변종 폭발 방지. 결정적 정렬 순 앞에서 K개. */
    private static final int VARIANT_CAP = 4;

    /** 시드 행에서 컬럼 값을 case-insensitive로 읽는다(happy 상태값 — 중복 변종 회피). */
    private static String stateAt(SynthesizedInput.SeedRow row, String column) {
        int i = indexOfIgnoreCase(row.columns(), column);
        if (i < 0) {
            return null;
        }
        Object v = row.values().get(i);
        return v == null ? null : v.toString();
    }

    /**
     * 가드별 결정적 대체-상태 값 리스트(다중 전이 arm). TEMPORAL=과거 날짜 1개. ENUM=
     * [EQ positive 각 상수(정렬)] + [EQ else-arm 잔여 1개(positive·negated 밖)] + [NE 잔여 상수(정렬,
     * negated 비어있지 않을 때만)]. BOOLEAN=반대 boolean 1개. NULLITY=nullable 컬럼에서 반대 arm 1개
     * (NOT NULL이면 빈 리스트). NUMERIC 상수=op별 반대 arm 정수 1개.
     * base 상태(happy)는 제외, 컬럼당 최대 VARIANT_CAP개.
     */
    private List<Object> flipValues(ConstraintExtractor.StateGuard guard, ColumnSchema col, String baseState) {
        if (guard.kind() == ConstraintExtractor.GuardKind.TEMPORAL) {
            String type = col.jdbcType().toUpperCase();
            Object v = (type.contains("TIMESTAMP") || type.contains("DATETIME"))
                    ? java.time.LocalDateTime.of(1900, 1, 1, 0, 0)
                    : java.time.LocalDate.of(1900, 1, 1);
            return List.of(v);
        }
        if (guard.kind() == ConstraintExtractor.GuardKind.BOOLEAN) {
            // baseState가 "true"면 반대 arm = false, 그 외(false 또는 null)면 true
            boolean opposite = !"true".equalsIgnoreCase(baseState);
            return List.of(opposite);
        }
        if (guard.kind() == ConstraintExtractor.GuardKind.NULLITY) {
            // NOT NULL 컬럼은 null arm 불가 → 변종 없음
            if (!col.nullable()) {
                return List.of();
            }
            // nullable: baseState=null이면 → defaultFor(col), non-null이면 → null
            if (baseState == null) {
                return List.of(defaultFor(col));
            }
            return java.util.Collections.singletonList(null);
        }
        if (guard.kind() == ConstraintExtractor.GuardKind.NUMERIC
                && guard.comparandKind() == ConstraintExtractor.ComparandKind.LITERAL) {
            long c;
            try {
                c = Long.parseLong(guard.comparand());
            } catch (NumberFormatException e) {
                return List.of();
            }
            long opposite = numericOpposite(guard.op(), c);
            // JDBC 타입이 정수 계열일 때만 숫자 arm 산출
            String jdbcUpper = col.jdbcType().toUpperCase();
            if (jdbcUpper.contains("BIGINT")) {
                return List.of(opposite);
            }
            if (jdbcUpper.contains("INT")) {
                // Integer 범위 내로 clamp
                long clamped = Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, opposite));
                return List.of((int) clamped);
            }
            // 비정수 JDBC 타입(DECIMAL/NUMERIC/REAL/FLOAT 등)은 Long 삽입 시 타입 불일치 위험 → 변종 없음
            return List.of();
        }
        if (guard.kind() == ConstraintExtractor.GuardKind.NUMERIC
                && guard.comparandKind() == ConstraintExtractor.ComparandKind.PARAM) {
            // PARAM 비교: Task8에서 처리 — 이 task에서는 skip
            return List.of();
        }
        List<String> all = enumConstantsForType(guard.enumType());
        if (all == null) {
            return List.of();
        }
        java.util.LinkedHashSet<String> picks = new java.util.LinkedHashSet<>();
        // EQ: positive 각 상수(그 == arm)
        guard.positiveConstants().stream().sorted().forEach(picks::add);
        // EQ else-arm: positive·negated 어디에도 없는 잔여 1개(fallthrough arm)
        if (!guard.positiveConstants().isEmpty()) {
            all.stream().sorted()
                    .filter(c -> !guard.positiveConstants().contains(c) && !guard.negatedConstants().contains(c))
                    .findFirst().ifPresent(picks::add);
        }
        // NE: 잔여 상수 전체 — negated가 비어있지 않을 때만(EQ-only 컬럼 폭발 방지)
        if (!guard.negatedConstants().isEmpty()) {
            all.stream().sorted().filter(c -> !guard.negatedConstants().contains(c)).forEach(picks::add);
        }
        // happy 상태 제외 후의 적격 후보 기준으로 cap 판정·로그(필터 전 picks.size()를 쓰면 false-positive 로그·오집계).
        List<Object> eligible = picks.stream()
                .filter(c -> baseState == null || !c.equalsIgnoreCase(baseState))
                .map(c -> (Object) c)
                .collect(java.util.stream.Collectors.toList());
        if (eligible.size() > VARIANT_CAP) {
            log.info("state-guard {}.{} column {} variants capped at {} (dropped {})",
                    guard.classFqn(), guard.method(), guard.column(), VARIANT_CAP, eligible.size() - VARIANT_CAP);
        }
        return eligible.size() > VARIANT_CAP ? eligible.subList(0, VARIANT_CAP) : eligible;
    }

    /** enumConstants를 FQN 직접 조회 후 simple-name 폴백(scalarFor와 동일 규칙). */
    private List<String> enumConstantsForType(String typeName) {
        if (typeName == null) {
            return null;
        }
        List<String> consts = enumConstants.get(typeName);
        if (consts == null) {
            String simple = typeName.substring(typeName.lastIndexOf('.') + 1);
            consts = enumConstants.entrySet().stream()
                    .filter(e -> e.getKey().substring(e.getKey().lastIndexOf('.') + 1).equals(simple))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
        }
        return consts;
    }

    /**
     * NUMERIC-vs-파라미터 op·입력값 V 기준 base 시드 컬럼값(만족 arm) 결정 (§3.3 표).
     * >=V → V, >V → V+1, <=V → V, <V → V-1, ==V → V, !=V → V+1.
     * Long.MIN/MAX 근처는 범위 내 결정적 대체값으로 보정.
     */
    private static long numericParamBaseCol(String op, long v) {
        return switch (op) {
            case ">=" -> v;
            case ">"  -> v < Long.MAX_VALUE ? v + 1 : Long.MAX_VALUE;
            case "<=" -> v;
            case "<"  -> v > Long.MIN_VALUE ? v - 1 : Long.MIN_VALUE;
            case "==" -> v;
            case "!=" -> v < Long.MAX_VALUE ? v + 1 : v - 1;
            default   -> v;
        };
    }

    /** conjunction leaf 만족값 산출 실패(모순·만족값 없음·NOT NULL null 등)를 나타내는 센티넬. */
    private static final Object CONJ_SKIP = new Object();

    /**
     * 한 컬럼의 leaf(들)을 모두 만족하는 단일 값. 같은 컬럼에 leaf가 여럿이면 제약을 병합한다.
     * ENUM은 positive/negated 집합을 합쳐 병합(2+ 상이 positive=모순 → {@link #CONJ_SKIP});
     * 그 외 kind는 각 leaf 만족값이 일치할 때만 채택, 불일치=모순. 병합 불가면 CONJ_SKIP.
     */
    private Object mergedSatisfyingValue(List<ConstraintExtractor.StateGuard> leaves, ColumnSchema col) {
        if (leaves.size() == 1) {
            return satisfyingValue(leaves.get(0), col);
        }
        boolean allEnum = leaves.stream()
                .allMatch(l -> l.kind() == ConstraintExtractor.GuardKind.ENUM);
        if (allEnum) {
            java.util.LinkedHashSet<String> positives = new java.util.LinkedHashSet<>();
            java.util.LinkedHashSet<String> negated = new java.util.LinkedHashSet<>();
            for (ConstraintExtractor.StateGuard leaf : leaves) {
                positives.addAll(leaf.positiveConstants());
                negated.addAll(leaf.negatedConstants());
            }
            if (!positives.isEmpty()) {
                if (positives.size() > 1) {
                    return CONJ_SKIP;   // status==X && status==Y → 모순
                }
                String only = positives.iterator().next();
                return negated.contains(only) ? CONJ_SKIP : only;
            }
            List<String> all = enumConstantsForType(leaves.get(0).enumType());
            if (all == null) {
                return CONJ_SKIP;
            }
            return all.stream().sorted().filter(c -> !negated.contains(c))
                    .map(c -> (Object) c).findFirst().orElse(CONJ_SKIP);
        }
        // 비-ENUM 다중 leaf: 각 만족값이 동일할 때만 채택(보수적; 불일치=모순 skip).
        Object merged = satisfyingValue(leaves.get(0), col);
        if (merged == CONJ_SKIP) {
            return CONJ_SKIP;
        }
        for (int i = 1; i < leaves.size(); i++) {
            Object v = satisfyingValue(leaves.get(i), col);
            if (v == CONJ_SKIP || !java.util.Objects.equals(v, merged)) {
                return CONJ_SKIP;
            }
        }
        return merged;
    }

    /**
     * leaf kind별 if-true 만족값(§design 3.3 표). {@link #flipValues}(불만족 arm)와 분리.
     * ENUM EQ→positive 첫째(정렬), ENUM NE→negated 밖 첫째(없으면 CONJ_SKIP), BOOLEAN→Boolean,
     * NULLITY ==null→null(NOT NULL이면 skip)/!=null→defaultFor, NUMERIC-상수→numericParamBaseCol,
     * TEMPORAL→방향별 날짜 + 컬럼 JDBC 타입 변환(op=null이면 isBefore 폴백). 만족값 없으면 CONJ_SKIP.
     */
    private Object satisfyingValue(ConstraintExtractor.StateGuard leaf, ColumnSchema col) {
        switch (leaf.kind()) {
            case ENUM -> {
                if (!leaf.positiveConstants().isEmpty()) {
                    return leaf.positiveConstants().stream().sorted().findFirst().orElse(null);
                }
                List<String> all = enumConstantsForType(leaf.enumType());
                if (all == null) {
                    return CONJ_SKIP;
                }
                return all.stream().sorted().filter(c -> !leaf.negatedConstants().contains(c))
                        .map(c -> (Object) c).findFirst().orElse(CONJ_SKIP);
            }
            case BOOLEAN -> {
                return Boolean.valueOf(leaf.comparand());   // "true"/"false" → Boolean
            }
            case NULLITY -> {
                if ("==".equals(leaf.op())) {   // getter()==null → null 필요
                    return col.nullable() ? null : CONJ_SKIP;
                }
                return defaultFor(col);   // !=null → non-null 만족값
            }
            case NUMERIC -> {
                if (leaf.comparandKind() == ConstraintExtractor.ComparandKind.PARAM) {
                    return CONJ_SKIP;   // NUMERIC-param leaf는 검출 단계에서 skip — 방어적
                }
                long c;
                try {
                    c = Long.parseLong(leaf.comparand());
                } catch (NumberFormatException e) {
                    return CONJ_SKIP;
                }
                Object coerced = coerceToColumnType(numericParamBaseCol(leaf.op(), c), col);
                return coerced == null ? CONJ_SKIP : coerced;
            }
            case TEMPORAL -> {
                String op = leaf.op() == null ? "isBefore" : leaf.op();
                String type = col.jdbcType().toUpperCase();
                boolean dateTime = type.contains("TIMESTAMP") || type.contains("DATETIME");
                if ("isAfter".equals(op)) {   // 미래 만족값
                    return dateTime ? java.time.LocalDateTime.of(2037, 1, 1, 0, 0)
                            : java.time.LocalDate.of(2037, 1, 1);
                }
                return dateTime ? java.time.LocalDateTime.of(1900, 1, 1, 0, 0)
                        : java.time.LocalDate.of(1900, 1, 1);   // 과거(isBefore)
            }
            default -> {
                return CONJ_SKIP;
            }
        }
    }

    /**
     * NUMERIC-vs-파라미터 op·입력값 V 기준 변종 시드 컬럼값(불만족 arm) 결정 (§3.3 표).
     * >=V → V-1, >V → V, <=V → V+1, <V → V, ==V → V+1, !=V → V.
     * Long.MIN/MAX 근처는 범위 내 결정적 대체값으로 보정.
     */
    private static long numericParamVariantCol(String op, long v) {
        return switch (op) {
            case ">=" -> v > Long.MIN_VALUE ? v - 1 : Long.MIN_VALUE;
            case ">"  -> v;
            case "<=" -> v < Long.MAX_VALUE ? v + 1 : Long.MAX_VALUE;
            case "<"  -> v;
            case "==" -> v < Long.MAX_VALUE ? v + 1 : v - 1;
            case "!=" -> v;
            default   -> v;
        };
    }

    /**
     * 정수 long 값을 컬럼 JDBC 타입에 맞는 Java 타입으로 변환.
     * 정수 계열(BIGINT/INT)만 지원. 비정수 계열은 null 반환 → per-guard skip.
     */
    private static Object coerceToColumnType(long value, ColumnSchema col) {
        String jdbcUpper = col.jdbcType().toUpperCase();
        if (jdbcUpper.contains("BIGINT")) {
            return value;
        }
        if (jdbcUpper.contains("INT")) {
            long clamped = Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
            return (int) clamped;
        }
        return null;   // 비정수 JDBC 타입 → skip
    }

    /**
     * op·상수 C 기준 반대 arm 정수 계산.
     * >=C → C-1, >C → C, <=C → C+1, <C → C, ==C → C+1, !=C → C.
     * Long.MIN/MAX 근처는 범위 내 결정적 대체값으로 보정.
     */
    private static long numericOpposite(String op, long c) {
        return switch (op) {
            case ">=" -> c > Long.MIN_VALUE ? c - 1 : Long.MIN_VALUE;
            case ">"  -> c;
            case "<=" -> c < Long.MAX_VALUE ? c + 1 : Long.MAX_VALUE;
            case "<"  -> c;
            case "==" -> c < Long.MAX_VALUE ? c + 1 : c - 1;
            case "!=" -> c;
            default   -> c;
        };
    }

    /** PK 값을 변종 인덱스만큼 오프셋(정수=+idx, 문자열="_idx") — 두 행 공존·dedup 회피. */
    private static Object offsetPk(Object base, int idx) {
        if (base instanceof Long l) {
            return l + idx;
        }
        if (base instanceof Integer i) {
            return i + idx;
        }
        if (base instanceof String s) {
            return s + "_" + idx;
        }
        return base;
    }

    private static int indexOfIgnoreCase(List<String> cols, String name) {
        for (int i = 0; i < cols.size(); i++) {
            if (cols.get(i).equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    /** parentTable을 재귀적으로 시드하여 allSeeds에 추가 (사이클 방지: visited). */
    private void seedParent(String parentTable, String parentColumn, Object probeValue,
                            List<TableSchema> tables, Set<String> visited,
                            List<SynthesizedInput.SeedRow> allSeeds, int probeId) {
        if (visited.contains(parentTable)) {
            return;
        }
        visited.add(parentTable);
        TableSchema parent = tables.stream()
                .filter(t -> t.name().equals(parentTable))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("FK parent table not in schema: " + parentTable));

        List<String> cols = new ArrayList<>();
        List<Object> vals = new ArrayList<>();
        // parentColumn(= 참조된 PK)을 먼저 삽입하여 index 0을 보장 (FixtureComposer의 DELETE 키)
        cols.add(parentColumn);
        vals.add(probeValue);
        for (ColumnSchema col : parent.columns()) {
            if (col.name().equals(parentColumn)) {
                continue; // 이미 index 0에 삽입함
            } else if (!col.nullable()) {
                ForeignKey fk = findFk(col.name(), parent);
                if (fk != null) {
                    Object childProbe = keyProbe(col, probeId);
                    seedParent(fk.referencedTable(), fk.referencedColumn(), childProbe, tables, visited, allSeeds, probeId);
                    cols.add(col.name());
                    vals.add(childProbe);
                } else {
                    cols.add(col.name());
                    vals.add(defaultFor(col));
                }
            }
        }
        allSeeds.add(new SynthesizedInput.SeedRow(parent.name(), cols, vals));
    }

    /** target 테이블에서 column 이름에 해당하는 ForeignKey를 찾는다. */
    private static ForeignKey findFk(String columnName, TableSchema table) {
        return table.foreignKeys().stream()
                .filter(fk -> fk.column().equals(columnName))
                .findFirst()
                .orElse(null);
    }

    /**
     * 타깃 테이블 추론. hint(SQL FROM 절)가 있으면 그 테이블을 우선,
     * 없으면 path-string 휴리스틱(경로에 테이블명/단수형이 등장하는 첫 매칭).
     */
    private TableSchema resolveTargetTable(Endpoint endpoint, List<TableSchema> tables, ResolutionHint hint) {
        if (hint != null && hint.table() != null) {
            TableSchema hinted = tables.stream()
                    .filter(t -> t.name().equals(hint.table()))
                    .findFirst().orElse(null);
            if (hinted != null) {
                return hinted;
            }
        }
        String path = endpoint.path().toLowerCase();
        for (TableSchema table : tables) {
            String name = table.name().toLowerCase();
            if (path.contains("/" + name) || path.contains("/" + singular(name))) {
                return table;
            }
        }
        return null;
    }

    /**
     * param → target 컬럼 매핑.
     * - PATH 변수(/{x})는 by-id 셀렉터이므로 target PK에 매핑한다.
     * - QUERY param은 필터이므로 snake_case 동일 컬럼(FK 또는 일반 컬럼)에 매핑한다.
     *   (QUERY xxxId를 PK로 보면 varchar param을 정수 PK에 넣어 INSERT가 깨진다.)
     */
    private String mapParamToColumn(EndpointParam param, TableSchema target, ResolutionHint hint) {
        if (target == null) {
            return null;
        }
        if (hint != null && hint.paramColumn().containsKey(param.name())) {
            String hinted = hint.paramColumn().get(param.name());
            // hint 컬럼이 실제 target 컬럼일 때만 채택 (스키마 부정합 방어)
            if (target.columns().stream().anyMatch(c -> c.name().equals(hinted))) {
                return hinted;
            }
        }
        if (param.kind() == ParamKind.PATH) {
            return target.columns().stream().filter(ColumnSchema::primaryKey)
                    .map(ColumnSchema::name).findFirst().orElse(null);
        }
        String snake = camelToSnake(param.name());
        return target.columns().stream().map(ColumnSchema::name)
                .filter(snake::equals).findFirst().orElse(null);
    }

    /** param의 문자열 값을 target 컬럼의 JDBC 타입에 맞는 seed 값으로 변환. */
    private static Object coerceForColumn(String value, String columnName, TableSchema target) {
        String jdbcType = target.columns().stream()
                .filter(c -> c.name().equals(columnName))
                .map(ColumnSchema::jdbcType)
                .findFirst().orElse("");
        String upper = jdbcType.toUpperCase();
        try {
            if (upper.contains("BIGINT")) return Long.parseLong(value);
            if (upper.contains("INT")) return Integer.parseInt(value);
            if (upper.contains("BOOL")) return Boolean.parseBoolean(value);
            // 십진/부동소수 키 컬럼: 숫자 타입으로 강제(varchar 바인딩 시 INSERT 깨짐 방지).
            if (upper.contains("DECIMAL") || upper.contains("NUMERIC")) return new java.math.BigDecimal(value);
            if (upper.contains("DOUBLE") || upper.contains("FLOAT") || upper.contains("REAL")) {
                return Double.parseDouble(value);
            }
        } catch (NumberFormatException e) {
            return value;
        }
        return value;
    }

    /** 엔드포인트별 비충돌 정수 id (90001..98999, 결정적). 엔드포인트 간 시드 오염 방지. */
    private static int probeIdFor(Endpoint endpoint) {
        return PROBE_ID_BASE + Math.floorMod(endpoint.id().hashCode(), 9000);
    }

    private String scalarFor(EndpointParam param, int probeId) {
        String t = param.javaType();
        switch (t) {
            case "java.lang.Integer", "int", "java.lang.Long", "long",
                 "java.lang.Short", "short" -> { return String.valueOf(probeId); }
            // 십진/부동소수 키 파라미터: 숫자 문자열 URL 값 → coerceForColumn이 BigDecimal/Double로 강제.
            case "java.math.BigDecimal", "java.math.BigInteger",
                 "double", "java.lang.Double", "float", "java.lang.Float" -> { return String.valueOf(probeId); }
            case "boolean", "java.lang.Boolean" -> { return "true"; }   // Bug 2: 유효 boolean 바인딩
            case "java.time.LocalDate" -> { return "2037-01-01"; }
            case "java.time.LocalDateTime" -> { return "2037-01-01T00:00:00"; }
            default -> { }
        }
        List<String> consts = enumConstants.get(t);
        if (consts == null) {   // simple-name 폴백
            String simple = t.substring(t.lastIndexOf('.') + 1);
            consts = enumConstants.entrySet().stream()
                    .filter(e -> e.getKey().substring(e.getKey().lastIndexOf('.') + 1).equals(simple))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
        }
        if (consts != null && !consts.isEmpty()) {
            return consts.get(0);
        }
        // 엔드포인트별 비충돌 문자열 PK: 같은 테이블을 읽는 두 엔드포인트가 동일 PK로
        // 시드해 병렬 실행 시 PK 충돌하는 것을 막는다 (정수 PK의 probeId와 동일한 의도).
        return "probe-" + param.name() + "-" + probeId;
    }

    private Object defaultFor(ColumnSchema column) {
        // Bug 3: enum 컬럼(@Enumerated STRING → VARCHAR)은 유효 상수로 시드(읽기 시 valueOf 500 방지).
        List<String> enumVals = enumColumns.get(column.name().toLowerCase());
        if (enumVals != null && !enumVals.isEmpty()) {
            return enumVals.get(0);
        }
        String type = column.jdbcType().toUpperCase();
        if (type.contains("CHAR") || type.contains("TEXT") || type.contains("CLOB")) return "probe";
        if (type.contains("BOOL")) return true;
        // 시간 타입: setObject가 java.time을 DATE/TIMESTAMP에 바인딩. 2037로 미래 제약 만족.
        // (MySQL TIMESTAMP 상한 2038-01-19 → 2999는 ERROR 1292로 거부됨.)
        if (type.contains("TIMESTAMP") || type.contains("DATETIME")) {
            return java.time.LocalDateTime.of(2037, 1, 1, 0, 0);
        }
        if (type.contains("DATE")) return java.time.LocalDate.of(2037, 1, 1);
        if (type.contains("TIME")) return java.time.LocalTime.of(0, 0);
        if (type.contains("UUID")) {
            return java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
        }
        return 1;   // INT/SERIAL/NUMERIC/DECIMAL/REAL/DOUBLE/FLOAT 등 수치
    }

    /**
     * 키 컬럼(PK 또는 FK)의 JDBC 타입에 맞는 비충돌 probe 값. 자식 FK와 부모 PK는
     * 같은 타입이므로 이 값을 양쪽에 동일하게 써서 FK 무결성을 만족시킨다.
     * 정수 키에는 PROBE_ID(비충돌 정수)를, 문자열 키에는 컬럼명 기반 문자열을 쓴다
     * (정수 PK 컬럼에 "probe-..." varchar를 넣으면 INSERT가 깨진다).
     */
    private static Object keyProbe(ColumnSchema keyColumn, int probeId) {
        String type = keyColumn.jdbcType().toUpperCase();
        if (type.contains("CHAR") || type.contains("TEXT")) return "probe-" + keyColumn.name() + "-" + probeId;
        if (type.contains("BIGINT")) return (long) probeId;
        if (type.contains("INT")) return probeId;
        if (type.contains("BOOL")) return true;
        // 십진/부동소수 키: 숫자 probe를 낸다(문자열 fallback 전에 차단). DECIMAL/NUMERIC은 정밀도
        // 보존상 BigDecimal, DOUBLE/FLOAT/REAL은 Double. FK 부모/자식에 동일 값을 써 무결성 유지.
        if (type.contains("DECIMAL") || type.contains("NUMERIC")) return new java.math.BigDecimal(probeId);
        if (type.contains("DOUBLE") || type.contains("FLOAT") || type.contains("REAL")) return (double) probeId;
        return "probe-" + keyColumn.name() + "-" + probeId;
    }

    /**
     * hint 없는 path-string 휴리스틱 해석 결과를 ResolutionHint 형태로 노출.
     * runner의 pass-2 필요 판정(hint != 휴리스틱)에 쓴다.
     */
    ResolutionHint heuristicResolution(Endpoint endpoint, List<TableSchema> tables) {
        TableSchema target = resolveTargetTable(endpoint, tables, null);
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        for (EndpointParam param : endpoint.params()) {
            if (param.kind() != ParamKind.PATH && param.kind() != ParamKind.QUERY) {
                continue;
            }
            String column = mapParamToColumn(param, target, null);
            if (column != null) {
                map.put(param.name(), column);
            }
        }
        return new ResolutionHint(target == null ? null : target.name(), map);
    }

    private static String singular(String name) {
        return name.endsWith("s") ? name.substring(0, name.length() - 1) : name;
    }

    static String camelToSnake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
