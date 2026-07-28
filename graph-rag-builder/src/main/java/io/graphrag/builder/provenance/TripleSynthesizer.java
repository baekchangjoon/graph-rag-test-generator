package io.graphrag.builder.provenance;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.JsonPaths;
import io.graphrag.builder.oracle.InputCandidates;
import io.graphrag.builder.provenance.ProvenanceReport.GuardFact;
import io.graphrag.builder.provenance.ProvenanceReport.Origin;
import io.graphrag.builder.provenance.ProvenanceReport.UnguardedField;
import io.graphrag.builder.provenance.ProvenanceReport.ValueRef;
import io.graphrag.model.ColumnSchema;
import io.graphrag.model.ForeignKey;
import io.graphrag.model.Json;
import io.graphrag.model.TableSchema;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link ProvenanceReport}의 가드 사실(GuardFact)을 출처별로 라우팅해 {@link TripleCandidate}(요청
 * body + seed SQL + stub mapping)를 합성한다 (REQ-005 코어 + REQ-006).
 *
 * <p><b>라우팅(REQ-005 코어):</b> 가드 피연산자의 {@link Origin}에 따라 INPUT은 {@code body}(JSON)에,
 * DB_READ는 seed {@code INSERT} 문장에, EXTERNAL_RESPONSE는 WireMock 스타일 stub mapping에 배치한다.
 *
 * <p><b>공동 배치·경계 만족값(REQ-006):</b>
 * <ul>
 *   <li>존재 가드({@code EXISTS}, 예: {@code repo.findById(req.id()).orElseThrow(...)}) — 코드 패턴상
 *       "성공하려면 반드시 존재"가 직접적인 요구사항이므로, 같은 값 s를 {@code body[jsonPath]}와 seed
 *       INSERT의 PK 컬럼에 동시 배치한다.</li>
 *   <li>비교 가드(예: {@code if (balance < amount) throw ...}) — {@code ProvenanceIndexer}는 "성공하면
 *       반드시 false여야 하는" throw 분기 조건을 그대로 {@code op}에 담으므로(가드 조건 op="&lt;"이면
 *       그 조건이 참일 때 예외), happy-path 후보는 op를 <b>부정(negate)</b>한 관계를 만족해야 한다
 *       (op="&lt;" → 필요 관계 "&gt;="). 부정 관계가 등가를 포함하면(GE/LE/EQ) 결정적 동치 값(예: 100=100)을
 *       우선한다({@link #satisfyingPair}).</li>
 *   <li>FK NOT NULL 부모 행 — seed 대상 테이블의 {@link TableSchema#foreignKeys()} 중 NOT NULL 컬럼에
 *       매핑된 FK는 부모 테이블 행도 재귀적으로 합성한다({@link #fillTable}).</li>
 * </ul>
 *
 * <p><b>갭 마커(REQ-007):</b> 결정적으로 도출 불가한 위치만 표기한다 — {@code unguarded} 필드는
 * body(JSON 문자열 값), NOT NULL이지만 어떤 가드도 값을 결정하지 못한 numeric 컬럼은 seed.sql(작은따옴표
 * 문자열 리터럴, 컬럼 타입 무관 — SQL 파싱 유지), 만족 리터럴을 찾지 못한 EXTERNAL_RESPONSE는
 * stubs.json의 {@code response.jsonBody} 값. 문법: {@code __AGENT_FILL__{type:<T>, semanticHint:<H>,
 * guard:<G>}} — 미상 필드는 {@code none}.
 *
 * <p><b>stubs.json = WireMock mapping 스키마(REQ-008):</b> {@code {"request":{"method","urlPath"},
 * "response":{"status","headers","jsonBody"}}} — {@code HttpCaptureServer.loadStubs}가 쓰는
 * {@code StubMapping.buildFrom(json)}으로 그대로 로드 가능하다. {@code callSite}가
 * {@code "<HTTP메서드> <path>"} 형식이 아니면(class#method 폴백 등) stub을 만들지 않고 notes에 사유를
 * 남긴다. 같은 {@code callSite}의 여러 응답 필드는 <b>한 mapping</b>으로 병합하고,
 * {@code Content-Type: application/json}을 함께 등록한다.
 *
 * <p><b>body 형상 불변식(E2E-B1 실증 2026-07-28 RED에서 도출):</b>
 * <ul>
 *   <li><b>컬렉션은 배열로</b> — {@link ProvenanceReport#collectionPaths()}에 있는 접두 경로는 원소
 *       1개짜리 JSON 배열로 만들고 그 대표원소 안에 리프를 쌓는다. 대표원소 규약(REQ-034)이
 *       {@code List<LineItem>}의 원소 필드를 {@code "lineItems.sku"}로 평탄화하므로 이 정보 없이는
 *       배열이 객체가 되어 SUT 역직렬화가 400으로 실패한다.</li>
 *   <li><b>가드의 INPUT 피연산자는 반드시 body에</b> — 라우팅이 값을 결정하지 못한 자리(결합 논리
 *       {@code ||}, 상대 피연산자가 DB/EXTERNAL이 아닌 비교 등)도 갭 마커 슬롯으로 남긴다. 컨테이너
 *       타입 피연산자 자신은 스칼라 자리가 아니므로 제외한다(원소 필드가 대신 채운다).</li>
 *   <li><b>가드의 EXTERNAL_RESPONSE 피연산자는 반드시 stub에</b> — 라우팅 밖에 있어도 갭 마커 자리를
 *       확보한다. stub이 없으면 SUT가 실제 외부 호출을 내보내 5xx가 된다.</li>
 * </ul>
 *
 * <p><b>DERIVED concolic 해 배치(REQ-032):</b> 가드 피연산자가 {@link Origin#DERIVED}(예:
 * {@code score * 2 == 84})이면 그 피연산자 자신은 body의 어느 한 필드가 아니므로 직접 배치할 수 없다.
 * 대신 {@link ValueRef#derivedFrom}(그 파생식이 읽는 INPUT 리프의 dot-path 목록 — {@code
 * ProvenanceIndexer}가 태깅)의 각 필드를 아래 {@code unguarded}와 동일한 <b>채움 슬롯</b>으로 취급해,
 * {@link InputCandidates}에 그 필드의 concolic 해가 있으면 결정값으로, 없으면(비선형·다변수 등 오라클이
 * 못 푸는 파생) UNKNOWN과 동일한 갭 마커로 배치한다.
 *
 * <p><b>후보 cap·정렬(REQ-033):</b> 채움 슬롯({@code unguarded} 필드 + 위 DERIVED 파생 루트 필드)마다
 * 갭 마커(미결정) 옵션에 더해 {@link InputCandidates}가 제공하는 결정값 옵션들을 더한 뒤 슬롯별 옵션의
 * cross product로 후보 조합을 만들고, "결정 필드 수 내림차순 → 정규화 문자열 사전순"으로 정렬해 상위
 * {@link #CANDIDATE_CAP}개만 반환한다(cand-01=최우선). 슬롯이 없거나 오라클 후보가 없으면 조합은 정확히
 * 1개(기존 Task 8 동작과 하위호환).
 *
 * <p><b>남은 확장 지점(Task 9+ 백로그):</b> {@code &&}/{@code ||} 등 결합 논리 가드의 다중 피연산자
 * 라우팅.
 * <b>동일 테이블 다중 행(예: from/to 계좌처럼 같은 테이블을 서로 다른 행으로 참조하는 경우)은 현재
 * 테이블당 한 행으로 병합되어 구분되지 않는다 — Task 9+ 백로그(REQ-006 범위에서는 구조 일반화 보류).</b>
 */
public final class TripleSynthesizer {

    /** 비교 가드 boundary 만족값의 결정적 앵커(수치). 브리프 예시("GE면 col=input=100")를 그대로 채택. */
    private static final long NUMERIC_ANCHOR = 100L;

    /** EP당 후보 cap(REQ-033 기본값). */
    private static final int CANDIDATE_CAP = 4;

    /** 필드별 오라클 후보값 중 조합에 포함할 최대 개수(조합 폭발 방지 — 어차피 최종 cap 이내만 살아남는다). */
    private static final int MAX_OPTIONS_PER_FIELD = CANDIDATE_CAP;

    /** 전체 조합 수 안전 상한. 초과 시 오라클 변주를 포기하고 갭 마커 단일 조합으로 폴백한다(회귀·성능 안전판). */
    private static final long SAFE_COMBO_LIMIT = 4096L;

    /**
     * 갭 마커 접두. body/stubs jsonBody는 이 문자열을 JSON 문자열 값으로, seed.sql은 작은따옴표 리터럴로
     * 담는다. {@link TripleValidator}/{@link SeedSqlWhitelist}(T1 검증 게이트, REQ-009/012)가 마커
     * 위치를 판별할 때도 이 상수를 그대로 참조한다(갭 마커 문법의 단일 소스).
     */
    public static final String GAP_MARKER_PREFIX = "__AGENT_FILL__";

    /** EXTERNAL_RESPONSE callSite가 {@code "<HTTP메서드> <path>"} 형식인지 판정할 때 허용하는 메서드 집합. */
    private static final Set<String> HTTP_METHODS =
            Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    private static final Set<String> NUMERIC_JAVA_TYPES = Set.of(
            "byte", "short", "int", "long", "float", "double",
            "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long",
            "java.lang.Float", "java.lang.Double",
            "java.math.BigDecimal", "java.math.BigInteger");

    /** 좌우 피연산자 관계(원본 소스 조건과 동일 극성). {@link #negate()}로 happy-path 관계를 얻는다. */
    private enum Rel {
        LT, LE, GT, GE, EQ, NE;

        static Rel fromSymbol(String op) {
            return switch (op) {
                case "<" -> LT;
                case "<=" -> LE;
                case ">" -> GT;
                case ">=" -> GE;
                case "==" -> EQ;
                case "!=" -> NE;
                default -> null;
            };
        }

        Rel negate() {
            return switch (this) {
                case LT -> GE;
                case LE -> GT;
                case GT -> LE;
                case GE -> LT;
                case EQ -> NE;
                case NE -> EQ;
            };
        }
    }

    /**
     * 리포트로부터 후보 트리플 목록을 합성한다(cap 이내, cand-01=최우선 순서 — REQ-033). guard가 결정한
     * 값은 모든 후보에서 동일하고, {@code unguarded} 필드만 갭 마커/오라클 결정값으로 조합에 따라 달라진다.
     *
     * @param report 분석 대상 엔드포인트의 provenance 리포트(가드/unguarded/unresolved)
     * @param shape  {@code @RequestBody} 타입의 필드 구조 (현재 라우팅은 가드 피연산자의 jsonPath만
     *               사용하므로 직접 참조하지 않지만, unguarded 필드의 body 배치를 추가할 후속 task의
     *               확장 지점으로 시그니처에 유지한다)
     * @param tables seed 대상 물리 스키마 목록(FK 부모 탐색 포함)
     * @param oracle 채움 슬롯(unguarded 필드 + DERIVED 파생 루트 필드)의 결정값 후보 — REQ-033 조합
     *               생성과 REQ-032 DERIVED concolic 해 배치에 소비된다. null이면 전부 갭 마커.
     */
    public List<TripleCandidate> synthesize(ProvenanceReport report, BodyShape shape,
                                            List<TableSchema> tables, InputCandidates oracle) {
        ObjectNode body = Json.mapper().createObjectNode();
        // callSite당 mapping 1개로 병합한다 — 같은 외부 호출의 응답에서 두 필드를 읽는 가드가 둘 있으면
        // (예: policy.allowedPrefix()와 policy.maxWeight()) mapping을 두 개 만들 게 아니라 한 mapping의
        // response.jsonBody에 두 필드를 모두 담아야 SUT가 한 번의 호출로 둘 다 받는다.
        Map<String, ObjectNode> stubsByCallSite = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        Set<String> collectionPaths = new LinkedHashSet<>(report.collectionPaths());
        Map<String, TableSchema> tablesByName = new LinkedHashMap<>();
        for (TableSchema t : tables) {
            tablesByName.put(t.name(), t);
        }
        // seed 대상 테이블별 컬럼→값 배정 (co-location: 같은 테이블에 여러 가드가 값을 보태면 한 행에 합쳐진다).
        Map<String, LinkedHashMap<String, Object>> rowsByTable = new LinkedHashMap<>();
        // 확장 지점 명시(클래스 Javadoc과 동일 내용) — 동일 테이블을 서로 다른 행으로 구분해야 하는
        // 경우(from/to 계좌 등)는 현재 1행으로 병합된다.
        notes.add("확장 지점: 동일 테이블 다중 행(from/to 계좌류)은 현재 1행으로 병합됨(Task 9+ 백로그)");

        String primaryTable = solePrimaryTable(report);

        for (GuardFact guard : report.guards()) {
            switch (guard.op()) {
                case "EXISTS" -> routeExistsGuard(guard, primaryTable, tablesByName, rowsByTable, body,
                        collectionPaths, notes);
                case "<", "<=", ">", ">=", "==", "!=" ->
                        routeComparisonGuard(guard, tablesByName, rowsByTable, body, stubsByCallSite,
                                collectionPaths, notes);
                case "!" -> routeNegatedEqualityGuard(guard, stubsByCallSite, notes);
                default -> notes.add("op '" + guard.op() + "' at " + guard.at()
                        + " — 결합 논리/미지원 가드 라우팅은 후속 task 범위(확장 지점)");
            }
        }

        // 라우팅이 값을 결정하지 못한 EXTERNAL_RESPONSE 피연산자도 stub에 자리를 만든다(REQ-008).
        // 가드가 외부 응답 필드를 읽는데 stubs.json이 비어 있으면, SUT는 stub되지 않은 실제 외부 호출을
        // 내보내고 그 실패(연결 거부/타임아웃)가 5xx로 나타난다 — 후보가 어떤 값을 채우든 검증 불가.
        ensureExternalStubSlots(report, stubsByCallSite, notes);

        List<String> seedSqlStatements = finalizeSeedRows(rowsByTable, tablesByName, notes);

        if (oracle != null && (!oracle.numeric().isEmpty() || !oracle.strings().isEmpty()
                || !oracle.reals().isEmpty())) {
            notes.add("InputCandidates 오라클 "
                    + (oracle.numeric().size() + oracle.strings().size() + oracle.reals().size())
                    + "개 필드 후보 보유 — 채움 슬롯(unguarded + DERIVED 파생 루트) 조합에 소비(REQ-032/REQ-033)");
        }

        List<FillSlot> slots = fillSlots(report, body, collectionPaths, notes);
        if (body.isEmpty() && slots.isEmpty() && !report.guards().isEmpty()) {
            // 조용한 축소 금지: 가드는 있는데 body가 비었다는 건 "채울 자리조차 없는" 후보라는 뜻이다.
            // 대표 원인은 동적 키 Map을 @RequestBody 루트로 받는 핸들러(예: Map<String,Integer> quotas) —
            // 갭 마커 계약(REQ-009: base와 candidate의 키 집합이 같아야 함)은 "에이전트가 키를 고르는"
            // 자리를 표현할 수 없어, 이 형상은 현재 합성 범위 밖이다.
            notes.add("경고(합성 불가): body가 비었고 채움 슬롯도 0개다 — 가드 " + report.guards().size()
                    + "건이 있는데도 배치 가능한 INPUT dot-path가 하나도 없다. 동적 키 Map을 "
                    + "@RequestBody 루트로 받는 핸들러가 대표 원인이며(키를 에이전트가 골라야 하는데 "
                    + "마커 계약은 키 집합 변경을 허용하지 않는다), 이 후보는 그대로는 통과할 수 없다. "
                    + "trial로 넘기지 말고 provenance 단계로 돌아가 판단하라.");
        }
        return buildCandidates(body, seedSqlStatements, new ArrayList<>(stubsByCallSite.values()), notes,
                slots, collectionPaths, oracle);
    }

    /**
     * 후보 조합에서 값이 달라지는 "채움 슬롯" 목록: {@code unguarded} 필드(REQ-001/REQ-007) +
     * DERIVED 피연산자의 파생 루트 INPUT 필드(REQ-032). 가드 라우팅이 이미 {@code body}에 결정값을
     * 배치한 경로와, 앞서 등록된 슬롯과 중복되는 경로는 제외한다(같은 필드에 두 값을 쓰지 않는다).
     */
    private static List<FillSlot> fillSlots(ProvenanceReport report, ObjectNode body,
                                            Set<String> collectionPaths, List<String> notes) {
        List<FillSlot> slots = new ArrayList<>();
        Set<String> claimed = new LinkedHashSet<>();
        for (UnguardedField field : report.unguarded()) {
            if (claimed.add(field.jsonPath())) {
                slots.add(new FillSlot("unguarded", field.jsonPath(), field.javaType(),
                        field.semanticHint(), "none"));
            }
        }
        for (GuardFact guard : report.guards()) {
            for (ValueRef v : guard.operands()) {
                if (v.origin() != Origin.DERIVED || v.derivedFrom() == null) {
                    continue;
                }
                for (String rootPath : v.derivedFrom()) {
                    if (bodyHasPath(body, rootPath, collectionPaths)) {
                        notes.add("derived(" + rootPath + ") at " + guard.at()
                                + " — 다른 가드가 이미 body에 결정값을 배치함, concolic 해 배치 skip");
                        continue;
                    }
                    if (claimed.add(rootPath)) {
                        slots.add(new FillSlot("derived", rootPath, v.javaType(), "none",
                                "DERIVED " + guard.op() + " at " + guard.at()));
                    }
                }
            }
        }
        // REQ-005 불변식: **가드에 등장하는 INPUT 피연산자는 반드시 body에 존재한다.** 라우팅이 값을
        // 결정한 자리는 이미 body에 있고, 결정하지 못한 자리(결합 논리 ||, DB_READ가 아닌 상대 피연산자,
        // 지원 밖 op 등)는 여기서 갭 마커/오라클 결정값 슬롯이 된다. 이 스윕이 없으면 가드가 읽는 필드가
        // body에서 통째로 누락돼(예: invoices의 total, fulfillment의 parcelWeight) SUT가 400/역직렬화
        // 실패로 떨어지고, 에이전트가 채울 자리조차 없다.
        for (GuardFact guard : report.guards()) {
            for (ValueRef v : guard.operands()) {
                if (v.origin() != Origin.INPUT || v.jsonPath() == null || v.jsonPath().isBlank()) {
                    continue;
                }
                String path = v.jsonPath();
                if (isContainerType(v.javaType()) || collectionPaths.contains(path)) {
                    // 컨테이너 자체(예: lineItems, quotas)는 스칼라 값을 놓을 자리가 아니다 — 그 원소
                    // 필드가 별도 슬롯으로 이미 배치되거나(대표원소), body 루트 자신이라 배치 불가하다.
                    notes.add("guard-input(" + path + ") at " + guard.at() + " — 컨테이너 타입("
                            + v.javaType() + ")이라 스칼라 슬롯을 만들지 않음(원소 필드 경로가 대신 배치된다)");
                    continue;
                }
                if (bodyHasPath(body, path, collectionPaths) || !claimed.add(path)) {
                    continue;
                }
                slots.add(new FillSlot("guard-input", path, v.javaType(), "none",
                        guard.op() + " at " + guard.at()));
            }
        }
        return slots;
    }

    /** 컬렉션/Map 타입명(리포트의 {@code javaType}은 단순명) — body에 스칼라로 놓을 수 없는 자리. */
    private static final Set<String> CONTAINER_JAVA_TYPES = Set.of(
            "List", "ArrayList", "LinkedList", "Collection", "Iterable", "Set", "HashSet", "LinkedHashSet",
            "Queue", "Deque", "Map", "HashMap", "LinkedHashMap", "TreeMap", "SortedMap", "ConcurrentHashMap");

    private static boolean isContainerType(String javaType) {
        if (javaType == null) {
            return false;
        }
        String simpleName = javaType.substring(javaType.lastIndexOf('.') + 1);
        int generic = simpleName.indexOf('<');
        return CONTAINER_JAVA_TYPES.contains(generic < 0 ? simpleName : simpleName.substring(0, generic));
    }

    /**
     * 후보마다 값이 달라질 수 있는 body 한 자리. {@code kind}는 notes trace 라벨("unguarded"/"derived"),
     * {@code guard}는 갭 마커의 {@code guard:} 필드에 그대로 실린다(REQ-007).
     */
    private record FillSlot(String kind, String jsonPath, String javaType, String semanticHint, String guard) {
    }

    /**
     * 채움 슬롯(unguarded 필드 + DERIVED 파생 루트 필드)별 옵션(갭 마커 + 오라클 결정값)의 cross
     * product로 후보 조합을 만들고, "결정 필드 수 내림차순 → 정규화 키 사전순"으로 정렬해 상위
     * {@link #CANDIDATE_CAP}개를 반환한다(REQ-032/REQ-033). 슬롯이 비었거나 오라클 후보가 전혀
     * 없으면 조합은 정확히 1개(Task 8과 동일한 단일 후보 동작 — 기존 테스트 하위호환).
     */
    private List<TripleCandidate> buildCandidates(ObjectNode baseBody, List<String> seedSqlStatements,
                                                  List<ObjectNode> stubMappings, List<String> baseNotes,
                                                  List<FillSlot> slots, Set<String> collectionPaths,
                                                  InputCandidates oracle) {
        List<List<FieldOption>> perFieldOptions = new ArrayList<>();
        for (FillSlot slot : slots) {
            perFieldOptions.add(optionsFor(slot, oracle));
        }

        List<List<FieldOption>> combos = crossProductWithSafetyCap(perFieldOptions, baseNotes);

        List<List<FieldOption>> ranked = new ArrayList<>(combos);
        ranked.sort(Comparator
                .comparingInt(TripleSynthesizer::decidedCount).reversed()
                .thenComparing(TripleSynthesizer::comboSortKey));

        List<TripleCandidate> out = new ArrayList<>();
        int limit = Math.min(CANDIDATE_CAP, ranked.size());
        for (int i = 0; i < limit; i++) {
            List<FieldOption> combo = ranked.get(i);
            ObjectNode body = baseBody.deepCopy();
            List<String> notes = new ArrayList<>(baseNotes);
            for (int f = 0; f < slots.size(); f++) {
                FillSlot slot = slots.get(f);
                FieldOption option = combo.get(f);
                putBodyValue(body, slot.jsonPath(), option.value(), collectionPaths);
                notes.add((option.decided()
                        ? slot.kind() + "(" + slot.jsonPath() + ") -> 오라클 결정값 " + option.value()
                        : slot.kind() + "(" + slot.jsonPath() + ") -> 갭 마커(도출 불가, semanticHint="
                                + (slot.semanticHint() == null ? "none" : slot.semanticHint()) + ")"));
            }
            notes.add(0, "cand-" + String.format("%02d", i + 1) + ": 결정 필드 " + decidedCount(combo) + "/"
                    + slots.size() + "(unguarded+derived 채움 슬롯 기준)");
            out.add(new TripleCandidate(body, seedSqlStatements, stubMappings, String.join("\n", notes)));
        }
        return out;
    }

    /** 후보 조합의 필드별 배정 하나: {@code decided}=true면 오라클 결정값, false면 갭 마커. */
    private record FieldOption(Object value, boolean decided) {
    }

    private static int decidedCount(List<FieldOption> combo) {
        int n = 0;
        for (FieldOption o : combo) {
            if (o.decided()) {
                n++;
            }
        }
        return n;
    }

    /** 결정 필드 수가 같은 조합끼리의 결정적 tie-break 키(값들을 필드 순서대로 이어붙인 사전순 문자열). */
    private static String comboSortKey(List<FieldOption> combo) {
        StringBuilder sb = new StringBuilder();
        for (FieldOption o : combo) {
            sb.append(String.valueOf(o.value())).append(';');
        }
        return sb.toString();
    }

    /** 채움 슬롯 하나의 옵션 목록: 항상 갭 마커 1개 + 오라클 결정값(있으면, 필드명은 jsonPath의 마지막 세그먼트). */
    private List<FieldOption> optionsFor(FillSlot slot, InputCandidates oracle) {
        List<FieldOption> options = new ArrayList<>();
        options.add(new FieldOption(gapMarker(slot.javaType(), slot.semanticHint(), slot.guard()), false));
        if (oracle == null) {
            return options;
        }
        String key = simpleFieldName(slot.jsonPath());
        List<Object> decided = new ArrayList<>();
        Set<Long> numeric = oracle.numeric().get(key);
        if (numeric != null) {
            decided.addAll(new java.util.TreeSet<>(numeric));
        }
        Set<String> strings = oracle.strings().get(key);
        if (strings != null) {
            decided.addAll(new java.util.TreeSet<>(strings));
        }
        // float/double 필드의 Real solve 해도 동일한 concolic 결정값 채널이다(REQ-032 "해가 있으면
        // 결정값") — 정수/문자열 해가 이미 cap을 채웠으면 자연히 잘린다.
        Set<Double> reals = oracle.reals().get(key);
        if (reals != null) {
            decided.addAll(new java.util.TreeSet<>(reals));
        }
        int limit = Math.min(decided.size(), MAX_OPTIONS_PER_FIELD);
        for (int i = 0; i < limit; i++) {
            options.add(new FieldOption(decided.get(i), true));
        }
        return options;
    }

    private static String simpleFieldName(String jsonPath) {
        int dot = jsonPath.lastIndexOf('.');
        return dot < 0 ? jsonPath : jsonPath.substring(dot + 1);
    }

    /**
     * 필드별 옵션 목록의 cross product. 전체 조합 수가 {@link #SAFE_COMBO_LIMIT}를 넘으면(오라클 후보가
     * 많은 필드가 다수인 병리적 케이스) 조합 폭발을 막기 위해 오라클 변주를 포기하고 필드마다 갭 마커
     * 단일 옵션으로 폴백한다(notes에 사유 기록) — 안전판, REQ-033 정상 케이스는 영향 없음.
     */
    private static List<List<FieldOption>> crossProductWithSafetyCap(List<List<FieldOption>> perFieldOptions,
                                                                      List<String> notes) {
        long total = 1L;
        boolean overflow = false;
        for (List<FieldOption> options : perFieldOptions) {
            total *= options.size();
            if (total > SAFE_COMBO_LIMIT) {
                overflow = true;
                break;
            }
        }
        List<List<FieldOption>> effective = perFieldOptions;
        if (overflow) {
            notes.add("unguarded 조합 수가 안전 상한(" + SAFE_COMBO_LIMIT + ")을 초과 — 오라클 변주 생략, 갭 마커 단일 조합으로 폴백");
            effective = new ArrayList<>();
            for (List<FieldOption> options : perFieldOptions) {
                effective.add(List.of(options.get(0)));   // index 0 = 항상 갭 마커(옵션 생성 순서 불변식)
            }
        }
        List<List<FieldOption>> combos = new ArrayList<>();
        combos.add(new ArrayList<>());
        for (List<FieldOption> fieldOptions : effective) {
            List<List<FieldOption>> next = new ArrayList<>();
            for (List<FieldOption> combo : combos) {
                for (FieldOption option : fieldOptions) {
                    List<FieldOption> extended = new ArrayList<>(combo);
                    extended.add(option);
                    next.add(extended);
                }
            }
            combos = next;
        }
        return combos;
    }

    /** 갭 마커 문자열: {@code __AGENT_FILL__{type:<T>, semanticHint:<H>, guard:<G>}} (REQ-007). */
    private static String gapMarker(String type, String semanticHint, String guard) {
        return GAP_MARKER_PREFIX + "{type:" + (type == null || type.isBlank() ? "Object" : type)
                + ", semanticHint:" + (semanticHint == null || semanticHint.isBlank() ? "none" : semanticHint)
                + ", guard:" + (guard == null || guard.isBlank() ? "none" : guard) + "}";
    }

    /** 리포트 전체에서 DB_READ 테이블이 정확히 하나뿐이면 그 테이블명을 반환(EXISTS의 table 미기재 시 폴백). */
    private static String solePrimaryTable(ProvenanceReport report) {
        Set<String> dbTables = new LinkedHashSet<>();
        for (GuardFact guard : report.guards()) {
            for (ValueRef v : guard.operands()) {
                if (v.origin() == Origin.DB_READ && v.table() != null) {
                    dbTables.add(v.table());
                }
            }
        }
        return dbTables.size() == 1 ? dbTables.iterator().next() : null;
    }

    /** EXISTS 가드: INPUT jsonPath 값 s를 body와 seed PK에 동시 배치. */
    private void routeExistsGuard(GuardFact guard, String primaryTable, Map<String, TableSchema> tablesByName,
                                  Map<String, LinkedHashMap<String, Object>> rowsByTable, ObjectNode body,
                                  Set<String> collectionPaths, List<String> notes) {
        for (ValueRef v : guard.operands()) {
            if (v.origin() != Origin.INPUT || v.jsonPath() == null) {
                continue;
            }
            String table = v.table() != null ? v.table() : primaryTable;
            TableSchema schema = table == null ? null : tablesByName.get(table);
            ColumnSchema pk = schema == null ? null : findPrimaryKey(schema);
            if (schema == null || pk == null) {
                notes.add("EXISTS(" + v.jsonPath() + ") at " + guard.at()
                        + " — 대상 테이블/PK 미해결, seed 배치 skip(확장 지점)");
                continue;
            }
            Object idValue = deterministicIdValue(v.jsonPath(), v.javaType(), pk);
            putBodyValue(body, v.jsonPath(), idValue, collectionPaths);
            rowFor(rowsByTable, table).put(pk.name(), coerceForColumn(idValue, pk));
            notes.add("EXISTS(" + v.jsonPath() + ") at " + guard.at() + " -> body." + v.jsonPath()
                    + " = seed " + table + "." + pk.name() + " = " + idValue);
        }
    }

    /**
     * 비교 가드(DB_READ col OP INPUT, 또는 그 반대 순서): 추출된 op는 throw 분기의 원본 조건이므로
     * 부정 관계를 만족하는 (col값, body값) 쌍을 co-location한다.
     */
    private void routeComparisonGuard(GuardFact guard, Map<String, TableSchema> tablesByName,
                                      Map<String, LinkedHashMap<String, Object>> rowsByTable, ObjectNode body,
                                      Map<String, ObjectNode> stubsByCallSite, Set<String> collectionPaths,
                                      List<String> notes) {
        List<ValueRef> operands = guard.operands();
        if (operands.size() != 2) {
            notes.add("op '" + guard.op() + "' at " + guard.at()
                    + " — 피연산자 2개가 아닌 비교 가드는 미지원(확장 지점)");
            return;
        }
        ValueRef opA = operands.get(0);
        ValueRef opB = operands.get(1);
        ValueRef sourceRef;   // 값을 SUT 바깥(DB row 또는 외부 응답)에 놓아야 하는 쪽
        ValueRef inputRef;
        if (isRoutableSource(opA.origin()) && opB.origin() == Origin.INPUT) {
            sourceRef = opA;
            inputRef = opB;
        } else if (opA.origin() == Origin.INPUT && isRoutableSource(opB.origin())) {
            sourceRef = opB;
            inputRef = opA;
        } else {
            notes.add("op '" + guard.op() + "' at " + guard.at()
                    + " — INPUT×(DB_READ|EXTERNAL_RESPONSE) 조합이 아닌 비교 가드는 미지원(확장 지점)");
            return;
        }
        Rel rel = Rel.fromSymbol(guard.op());
        boolean sourceResolved = sourceRef.origin() == Origin.DB_READ
                ? sourceRef.table() != null && sourceRef.column() != null
                : sourceRef.callSite() != null && sourceRef.stubField() != null;
        if (rel == null || !sourceResolved || inputRef.jsonPath() == null) {
            notes.add("op '" + guard.op() + "' at " + guard.at()
                    + " — table/column/callSite/stubField/jsonPath 미해결, 비교 가드 skip(확장 지점)");
            return;
        }

        Rel needed = rel.negate();
        boolean numeric = isNumericJavaType(sourceRef.javaType()) || isNumericJavaType(inputRef.javaType());
        Object[] pair = satisfyingPair(needed, numeric);
        Object sourceVal = sourceRef == opA ? pair[0] : pair[1];
        Object inputVal = inputRef == opA ? pair[0] : pair[1];

        putBodyValue(body, inputRef.jsonPath(), inputVal, collectionPaths);
        if (sourceRef.origin() == Origin.DB_READ) {
            TableSchema schema = tablesByName.get(sourceRef.table());
            ColumnSchema column = schema == null ? null : schema.columns().stream()
                    .filter(c -> c.name().equals(sourceRef.column())).findFirst().orElse(null);
            rowFor(rowsByTable, sourceRef.table()).put(sourceRef.column(), coerceForColumn(sourceVal, column));
            notes.add("comparison(" + opA.origin() + " " + guard.op() + " " + opB.origin() + ") at " + guard.at()
                    + " negated-to=" + needed + " -> body." + inputRef.jsonPath() + "=" + inputVal
                    + ", seed " + sourceRef.table() + "." + sourceRef.column() + "=" + sourceVal);
            return;
        }
        // EXTERNAL_RESPONSE 쪽: 만족값을 stub의 response.jsonBody에 놓는다(REQ-008). DB_READ와 완전히
        // 대칭이며, 이 배선이 없으면 body는 결정값을 받는데 상대 피연산자는 stub이 없어 실제 외부 호출로
        // 나가버려 가드를 통과시킬 수 없다.
        boolean placed = putStubField(stubsByCallSite, sourceRef.callSite(), sourceRef.stubField(),
                sourceVal, guard.at(), notes);
        if (placed) {
            notes.add("comparison(" + opA.origin() + " " + guard.op() + " " + opB.origin() + ") at " + guard.at()
                    + " negated-to=" + needed + " -> body." + inputRef.jsonPath() + "=" + inputVal
                    + ", stub " + sourceRef.callSite() + "." + sourceRef.stubField() + "=" + sourceVal);
        }
    }

    /** 비교 가드에서 "SUT 바깥에 값을 놓을 수 있는" 출처인지(DB seed 또는 외부 stub). */
    private static boolean isRoutableSource(Origin origin) {
        return origin == Origin.DB_READ || origin == Origin.EXTERNAL_RESPONSE;
    }

    /**
     * {@code !x.equals(y)} 패턴(리터럴 vs EXTERNAL_RESPONSE)의 부정 등가 가드: happy path는 등가이므로
     * stubField에 만족값(리터럴이 있으면 그 값, 없으면 갭 마커)을 채운 WireMock mapping을 만든다(REQ-008).
     * {@code callSite}가 {@code "<HTTP메서드> <path>"} 형식이 아니면(class#method 폴백 등 미해결
     * 위치) stub을 만들지 않고 notes에 사유를 남긴다 — 형식을 알 수 없는 request.method/urlPath로 잘못된
     * mapping을 만드는 것보다 안전하다.
     */
    private void routeNegatedEqualityGuard(GuardFact guard, Map<String, ObjectNode> stubsByCallSite,
                                           List<String> notes) {
        ValueRef literalRef = null;
        ValueRef externalRef = null;
        for (ValueRef v : guard.operands()) {
            if (v.literal() != null) {
                literalRef = v;
            }
            if (v.origin() == Origin.EXTERNAL_RESPONSE) {
                externalRef = v;
            }
        }
        if (externalRef == null || externalRef.callSite() == null || externalRef.stubField() == null) {
            notes.add("op '!' at " + guard.at() + " — EXTERNAL_RESPONSE 부정 등가 패턴이 아님, stub 라우팅 skip(확장 지점)");
            return;
        }
        boolean decided = literalRef != null && literalRef.literal() != null;
        Object value = decided ? literalRef.literal()
                : gapMarker(externalRef.javaType(), "none", "none");
        if (!putStubField(stubsByCallSite, externalRef.callSite(), externalRef.stubField(),
                value, guard.at(), notes)) {
            return;
        }
        notes.add(decided
                ? "EXTERNAL_RESPONSE(" + externalRef.callSite() + ") at " + guard.at() + " -> stub."
                        + externalRef.stubField() + "=" + value + " (WireMock mapping 스키마, REQ-008)"
                : "EXTERNAL_RESPONSE(" + externalRef.callSite() + ") at " + guard.at()
                        + " — 만족 리터럴 미해결, stub." + externalRef.stubField() + " = 갭 마커(REQ-007)");
    }

    /**
     * 리포트에 등장하는 <b>모든</b> {@link Origin#EXTERNAL_RESPONSE} 피연산자가 stub의
     * {@code response.jsonBody}에 자리를 갖도록 보장한다(REQ-008). 라우팅이 값을 정하지 못한 자리는 갭
     * 마커로 남긴다.
     *
     * <p>이 스윕이 없으면 결합 논리({@code ||})나 미지원 op 안에 든 외부 응답 피연산자는 통째로 무시돼
     * {@code stubs.json}이 빈 채로 나가고(예: fulfillment의 {@code allowedPrefix}), SUT는 stub되지 않은
     * 실제 외부 호출을 시도해 5xx로 떨어진다 — 후보 내용과 무관한 실패라 trial 판정이 무의미해진다.
     */
    private void ensureExternalStubSlots(ProvenanceReport report, Map<String, ObjectNode> stubsByCallSite,
                                         List<String> notes) {
        for (GuardFact guard : report.guards()) {
            for (ValueRef v : guard.operands()) {
                if (v.origin() != Origin.EXTERNAL_RESPONSE || v.callSite() == null || v.stubField() == null) {
                    continue;
                }
                ObjectNode existing = stubsByCallSite.get(v.callSite());
                if (existing != null && existing.path("response").path("jsonBody").has(v.stubField())) {
                    continue;   // 이미 라우팅이 값을 배치함
                }
                if (putStubField(stubsByCallSite, v.callSite(), v.stubField(),
                        gapMarker(v.javaType(), "none", guard.op() + " at " + guard.at()), guard.at(), notes)) {
                    notes.add("EXTERNAL_RESPONSE(" + v.callSite() + "." + v.stubField() + ") at " + guard.at()
                            + " — 가드가 읽지만 라우팅이 값을 결정하지 못함, stub에 갭 마커 자리 확보(REQ-007/008)");
                }
            }
        }
    }

    /**
     * {@code callSite}의 WireMock mapping(없으면 생성)에 {@code response.jsonBody.<field> = value}를
     * 넣는다. 같은 callSite의 여러 필드는 <b>한 mapping</b>에 병합된다(SUT는 한 번만 호출한다).
     *
     * <p>{@code callSite}가 {@code "<HTTP메서드> <path>"} 형식이 아니면(class#method 폴백 등) mapping을
     * 만들지 않고 사유만 남긴다 — 잘못된 request 매칭을 만드는 것보다 안전하다.
     *
     * <p>응답에 {@code Content-Type: application/json}을 함께 등록한다. WireMock은 {@code jsonBody}만으로
     * 헤더를 자동 부여하지 않아, {@code RestTemplate} 같은 클라이언트의 메시지 컨버터 선택이 실패해 SUT가
     * 500을 내는 사례가 있었다(사람이 갭필한 fixture가 손으로 채워 넣던 자리 — 이제 도구가 만든다).
     *
     * @return mapping에 실제로 배치했으면 true
     */
    private static boolean putStubField(Map<String, ObjectNode> stubsByCallSite, String callSite,
                                        String field, Object value, String at, List<String> notes) {
        int sp = httpCallSiteSplit(callSite);
        if (sp < 0) {
            notes.add("op at " + at + " — callSite '" + callSite
                    + "' 가 '<HTTP메서드> <path>' 형식이 아님(class#method 폴백으로 추정), stub 생성 불가");
            return false;
        }
        ObjectNode stub = stubsByCallSite.computeIfAbsent(callSite, cs -> {
            ObjectNode created = Json.mapper().createObjectNode();
            ObjectNode request = created.putObject("request");
            request.put("method", cs.substring(0, sp));
            request.put("urlPath", cs.substring(sp + 1));
            ObjectNode response = created.putObject("response");
            response.put("status", 200);
            response.putObject("headers").put("Content-Type", "application/json");
            response.putObject("jsonBody");
            return created;
        });
        ObjectNode jsonBody = (ObjectNode) stub.get("response").get("jsonBody");
        putScalar(jsonBody, field, value);
        return true;
    }

    /**
     * callSite가 {@code "<HTTP메서드> <path>"} 형식이면 공백 인덱스를, 아니면(class#method 폴백 등)
     * -1을 반환한다. 첫 토큰이 {@link #HTTP_METHODS}에 속하고 나머지가 {@code "/"}로 시작해야 유효하다.
     */
    private static int httpCallSiteSplit(String callSite) {
        int sp = callSite.indexOf(' ');
        if (sp <= 0 || sp == callSite.length() - 1) {
            return -1;
        }
        String method = callSite.substring(0, sp);
        String path = callSite.substring(sp + 1);
        return HTTP_METHODS.contains(method) && path.startsWith("/") ? sp : -1;
    }

    // ---- seed 행 마무리(PK 기본값·FK 부모 재귀·NOT NULL 기본값) ----

    private final LinkedHashSet<String> emitOrder = new LinkedHashSet<>();

    private List<String> finalizeSeedRows(Map<String, LinkedHashMap<String, Object>> rowsByTable,
                                          Map<String, TableSchema> tablesByName, List<String> notes) {
        emitOrder.clear();
        List<String> initialTables = new ArrayList<>(rowsByTable.keySet());
        Set<String> visiting = new LinkedHashSet<>();
        for (String table : initialTables) {
            fillTable(table, tablesByName, rowsByTable, visiting, notes);
        }
        List<String> statements = new ArrayList<>();
        for (String table : emitOrder) {
            statements.add(toInsertStatement(table, rowsByTable.get(table)));
        }
        return statements;
    }

    /**
     * 부모(FK 참조 테이블)를 먼저 재귀적으로 채운 뒤 자신을 {@link #emitOrder}에 등록(부모 선행 emission 보장).
     * 부모 스키마가 {@code tablesByName}에 없거나 부모 PK 해결에 실패하면, 그 NOT NULL FK 컬럼에 null을
     * 침묵 삽입하지 않고 컬럼 자체를 행에서 제외하며 {@code notes}에 {@code "unresolved-fk: ..."}로 남긴다.
     */
    private void fillTable(String tableName, Map<String, TableSchema> tablesByName,
                           Map<String, LinkedHashMap<String, Object>> rowsByTable, Set<String> visiting,
                           List<String> notes) {
        if (emitOrder.contains(tableName) || !visiting.add(tableName)) {
            return; // 이미 채움 완료 또는 순환 참조(방어적 종료)
        }
        LinkedHashMap<String, Object> row = rowFor(rowsByTable, tableName);
        TableSchema schema = tablesByName.get(tableName);
        if (schema == null) {
            notes.add("table '" + tableName + "' — 스키마 미제공, PK/FK 기본값 채움 skip(확장 지점)");
            emitOrder.add(tableName);
            visiting.remove(tableName);
            return;
        }
        ColumnSchema pk = findPrimaryKey(schema);
        if (pk != null && !row.containsKey(pk.name())) {
            row.put(pk.name(), deterministicIdValue(tableName, null, pk));
        }
        for (ColumnSchema column : schema.columns()) {
            if (column.primaryKey() || row.containsKey(column.name()) || column.nullable()) {
                continue;
            }
            ForeignKey fk = findForeignKey(column.name(), schema);
            if (fk == null) {
                row.put(column.name(), defaultValueFor(column, tableName, notes));
                continue;
            }
            if (!tablesByName.containsKey(fk.referencedTable())) {
                // 부모 스키마가 tables에 없음 — null을 침묵 삽입하지 않고 컬럼 자체를 INSERT에서 제외한다
                // (NOT NULL 위반 SQL 생성 방지). 근거는 notes에 "unresolved-fk:"로 남겨 추적 가능하게 한다.
                notes.add("unresolved-fk: " + tableName + "." + column.name() + " -> "
                        + fk.referencedTable() + "." + fk.referencedColumn()
                        + " (부모 스키마가 tables에 없음 — NOT NULL 컬럼을 INSERT에서 제외, 침묵 null 금지)");
                continue;
            }
            fillTable(fk.referencedTable(), tablesByName, rowsByTable, visiting, notes);
            LinkedHashMap<String, Object> parentRow = rowsByTable.get(fk.referencedTable());
            Object parentPk = parentRow == null ? null : parentRow.get(fk.referencedColumn());
            if (parentPk == null) {
                // 방어적 이중 점검: 부모 스키마는 있으나 PK가 referencedColumn과 불일치하는 등 예기치 못한 미해결.
                notes.add("unresolved-fk: " + tableName + "." + column.name() + " -> "
                        + fk.referencedTable() + "." + fk.referencedColumn()
                        + " (부모 PK 해결 실패 — NOT NULL 컬럼을 INSERT에서 제외, 침묵 null 금지)");
                continue;
            }
            row.put(column.name(), parentPk);
        }
        emitOrder.add(tableName);
        visiting.remove(tableName);
    }

    private static LinkedHashMap<String, Object> rowFor(Map<String, LinkedHashMap<String, Object>> rowsByTable,
                                                         String table) {
        return rowsByTable.computeIfAbsent(table, t -> new LinkedHashMap<>());
    }

    private static ColumnSchema findPrimaryKey(TableSchema schema) {
        return schema.columns().stream().filter(ColumnSchema::primaryKey).findFirst().orElse(null);
    }

    private static ForeignKey findForeignKey(String columnName, TableSchema schema) {
        return schema.foreignKeys().stream().filter(fk -> fk.column().equals(columnName)).findFirst().orElse(null);
    }

    private static String toInsertStatement(String table, LinkedHashMap<String, Object> row) {
        String columns = String.join(", ", row.keySet());
        StringBuilder values = new StringBuilder();
        boolean first = true;
        for (Object v : row.values()) {
            if (!first) {
                values.append(", ");
            }
            first = false;
            values.append(sqlLiteral(v));
        }
        return "INSERT INTO " + table + " (" + columns + ") VALUES (" + values + ");";
    }

    private static String sqlLiteral(Object v) {
        if (v == null) {
            return "NULL";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        return "'" + v.toString().replace("'", "''") + "'";
    }

    /**
     * NOT NULL·FK가 아닌 컬럼의 기본값. 어떤 가드도 값을 정하지 못한 자리에 임의값을 침묵 삽입하지
     * 않는다는 것이 원칙이고(REQ-007), 예외는 "제약을 만족하기만 하면 무엇이든 되는" 자리뿐이다.
     *
     * <p>{@code CHAR}/{@code VARCHAR}처럼 길이가 제한된 라벨 컬럼은 그 예외에 해당하므로 padding을
     * 둔다. 반면 {@code TEXT}/{@code CLOB}은 길이 제한이 없는 자유형 페이로드로, 내용에 계약이 붙어
     * 있는 경우가 흔하다 — 실측(mindgraph {@code graph_record.nodes_json})에서 padding 문자열은
     * 존재 가드를 통과시키지만 핸들러의 역직렬화가 던져 500이 됐다. NOT NULL을 만족하는 것과 값이
     * 유효한 것은 다르므로, 여기서 padding은 그 자체가 침묵 삽입이다. 도구는 내용 계약을 알 수
     * 없으므로 컬럼명을 semanticHint로 실어 갭 마커로 남기고 에이전트가 채우게 한다.
     */
    private static Object defaultValueFor(ColumnSchema column, String tableName, List<String> notes) {
        String type = column.jdbcType() == null ? "" : column.jdbcType().toUpperCase();
        if (type.contains("BOOL")) {
            return true;
        }
        if (type.contains("TEXT") || type.contains("CLOB")) {
            notes.add("gap-marker: " + tableName + "." + column.name()
                    + " (NOT NULL 자유형 TEXT, 내용 계약을 도구가 알 수 없음) -> __AGENT_FILL__(REQ-007)");
            return gapMarker("String", column.name(), "none");
        }
        if (type.contains("CHAR")) {
            return "seed-" + column.name();
        }
        if (type.contains("DATE") || type.contains("TIME")) {
            return "2037-01-01"; // seed 문자열 리터럴(방언 무관 최소 표기) — 정밀 타입 변환은 후속 task
        }
        notes.add("gap-marker: " + tableName + "." + column.name()
                + " (NOT NULL numeric, 어떤 가드도 결정하지 못함) -> __AGENT_FILL__(REQ-007)");
        return gapMarker(numericMarkerType(type), "none", "none");
    }

    /** jdbcType(대문자) → 갭 마커의 {@code type} 라벨(java 원시형 이름 근사). 미분류 숫자형은 안전하게 long. */
    private static String numericMarkerType(String upperJdbcType) {
        if (upperJdbcType.contains("BIGINT")) {
            return "long";
        }
        if (upperJdbcType.contains("DECIMAL") || upperJdbcType.contains("NUMERIC")) {
            return "BigDecimal";
        }
        if (upperJdbcType.contains("DOUBLE") || upperJdbcType.contains("FLOAT") || upperJdbcType.contains("REAL")) {
            return "double";
        }
        if (upperJdbcType.contains("INT")) {
            return "int";
        }
        return "long";
    }

    // ---- 값 합성 유틸 ----

    private static boolean isNumericJavaType(String javaType) {
        return javaType != null && NUMERIC_JAVA_TYPES.contains(javaType);
    }

    /**
     * 필요 관계 {@code needed}를 만족하는 (좌, 우) 결정적 값 쌍. 등가를 포함하는 관계(GE/LE/EQ)는
     * 동치 값을 우선한다(브리프: "GE면 col=input=100 등 동치 우선"). 문자열 순서 비교(GT/LT)는
     * 미지원 — 보수적으로 동치 폴백(확장 지점).
     */
    private static Object[] satisfyingPair(Rel needed, boolean numeric) {
        if (numeric) {
            return switch (needed) {
                case GE, LE, EQ -> new Object[]{NUMERIC_ANCHOR, NUMERIC_ANCHOR};
                case GT -> new Object[]{NUMERIC_ANCHOR + 1, NUMERIC_ANCHOR};
                case LT -> new Object[]{NUMERIC_ANCHOR, NUMERIC_ANCHOR + 1};
                case NE -> new Object[]{NUMERIC_ANCHOR + 1, NUMERIC_ANCHOR};
            };
        }
        return switch (needed) {
            case NE -> new Object[]{"match", "mismatch"};
            default -> new Object[]{"match", "match"};
        };
    }

    /** jsonPath/javaType(+PK 컬럼 타입) 기준 결정적 id 값. body와 seed PK에 동일하게 쓰인다. */
    private static Object deterministicIdValue(String seedKey, String javaType, ColumnSchema pkColumn) {
        boolean numeric = isNumericJavaType(javaType)
                || (pkColumn.jdbcType() != null && pkColumn.jdbcType().toUpperCase().contains("INT"));
        if (numeric) {
            return NUMERIC_ANCHOR + Math.floorMod(seedKey.hashCode(), 900);
        }
        return "seed-" + seedKey.replaceAll("[^a-zA-Z0-9]+", "-").toLowerCase();
    }

    /**
     * dot-path에 값을 배치한다. {@code collectionPaths}에 있는 접두 경로는 <b>JSON 배열</b>로 만들고
     * 대표(첫) 원소 안으로 내려간다 — {@code ProvenanceIndexer}의 대표원소 규약(REQ-034)이
     * {@code List<LineItem> lineItems}의 원소 필드를 bracket 없이 {@code "lineItems.sku"}로 평탄화하기
     * 때문에, 이 정보 없이 {@link JsonPaths#putPath}로 쓰면 {@code {"lineItems":{"sku":…}}}처럼 배열이
     * 객체가 되어 SUT의 Jackson 역직렬화가 400으로 실패한다(E2E-B1 실증 차단 원인 2).
     *
     * <p>{@code collectionPaths}가 비었으면(구 리포트 등) 종전과 동일하게 전부 중첩 객체로 쓴다.
     */
    private static void putBodyValue(ObjectNode body, String jsonPath, Object value,
                                     Set<String> collectionPaths) {
        if (collectionPaths.isEmpty()) {
            putScalar(descendObjects(body, jsonPath), leafSegment(jsonPath), value);
            return;
        }
        ObjectNode parent = descendWithCollections(body, jsonPath, collectionPaths);
        String leaf = leafSegment(jsonPath);
        if (collectionPaths.contains(jsonPath)) {
            // 리프 자체가 스칼라 컬렉션(예: List<String> tags) — 원소 1개짜리 배열로 만든다.
            com.fasterxml.jackson.databind.node.ArrayNode array = parent.putArray(leaf);
            ObjectNode holder = Json.mapper().createObjectNode();
            putScalar(holder, leaf, value);
            array.add(holder.get(leaf));
            return;
        }
        putScalar(parent, leaf, value);
    }

    /** dot-path의 마지막 세그먼트. */
    private static String leafSegment(String jsonPath) {
        int dot = jsonPath.lastIndexOf('.');
        return dot < 0 ? jsonPath : jsonPath.substring(dot + 1);
    }

    /** 중간 세그먼트를 전부 객체로 취급해 내려간다(collectionPaths 정보가 없을 때의 종전 동작). */
    private static ObjectNode descendObjects(ObjectNode root, String jsonPath) {
        return descendWithCollections(root, jsonPath, Set.of());
    }

    /**
     * 중간 세그먼트를 차례로 내려가며, 그 시점까지의 접두 경로가 {@code collectionPaths}에 있으면 배열
     * (원소 1개 보장) 안의 대표 원소로, 아니면 중첩 객체로 진입한다. 이미 만들어진 노드는 재사용하므로
     * 같은 배열 원소에 여러 필드({@code lineItems.sku}, {@code lineItems.amount})가 함께 쌓인다.
     */
    private static ObjectNode descendWithCollections(ObjectNode root, String jsonPath,
                                                     Set<String> collectionPaths) {
        String[] segments = jsonPath.split("\\.");
        ObjectNode node = root;
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < segments.length - 1; i++) {
            if (i > 0) {
                prefix.append('.');
            }
            prefix.append(segments[i]);
            node = childContainer(node, segments[i], collectionPaths.contains(prefix.toString()));
        }
        return node;
    }

    private static ObjectNode childContainer(ObjectNode parent, String key, boolean asArray) {
        com.fasterxml.jackson.databind.JsonNode child = parent.get(key);
        if (!asArray) {
            if (child instanceof ObjectNode object) {
                return object;
            }
            ObjectNode created = parent.objectNode();
            parent.set(key, created);
            return created;
        }
        com.fasterxml.jackson.databind.node.ArrayNode array =
                child instanceof com.fasterxml.jackson.databind.node.ArrayNode existing
                        ? existing : parent.putArray(key);
        if (array.isEmpty() || !(array.get(0) instanceof ObjectNode)) {
            array.removeAll();
            array.addObject();
        }
        return (ObjectNode) array.get(0);
    }

    private static void putScalar(ObjectNode node, String key, Object value) {
        if (value instanceof Long l) {
            node.put(key, l);
        } else if (value instanceof Integer i) {
            node.put(key, i);
        } else if (value instanceof Double d) {
            node.put(key, d);
        } else if (value instanceof Boolean b) {
            node.put(key, b);
        } else {
            node.put(key, String.valueOf(value));
        }
    }

    /**
     * dot-path가 이미 body에 배치되어 있는지(가드 라우팅 결과와의 충돌 판별용). 배열 접두 경로는 대표
     * 원소(index 0)를 따라 내려간다 — 그렇지 않으면 {@code lineItems.sku}가 배열 안에 이미 있어도
     * "없음"으로 오판해 중복 슬롯을 만든다.
     */
    private static boolean bodyHasPath(ObjectNode body, String jsonPath, Set<String> collectionPaths) {
        com.fasterxml.jackson.databind.JsonNode node = body;
        StringBuilder prefix = new StringBuilder();
        String[] segments = jsonPath.split("\\.");
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                prefix.append('.');
            }
            prefix.append(segments[i]);
            if (!(node instanceof ObjectNode object)) {
                return false;
            }
            node = object.get(segments[i]);
            if (node == null) {
                return false;
            }
            if (collectionPaths.contains(prefix.toString())
                    && node instanceof com.fasterxml.jackson.databind.node.ArrayNode array) {
                if (array.isEmpty()) {
                    return false;
                }
                node = array.get(0);
            }
        }
        return true;
    }

    private static Object coerceForColumn(Object raw, ColumnSchema column) {
        if (column == null || raw == null) {
            return raw;
        }
        String type = column.jdbcType() == null ? "" : column.jdbcType().toUpperCase();
        if (type.contains("BIGINT")) {
            return toLong(raw);
        }
        if (type.contains("INT")) {
            return (int) toLong(raw);
        }
        if (type.contains("DECIMAL") || type.contains("NUMERIC")) {
            return new BigDecimal(raw.toString());
        }
        if (type.contains("DOUBLE") || type.contains("FLOAT") || type.contains("REAL")) {
            return Double.valueOf(raw.toString());
        }
        if (type.contains("BOOL")) {
            return raw instanceof Boolean b ? b : Boolean.parseBoolean(raw.toString());
        }
        return raw.toString();
    }

    private static long toLong(Object raw) {
        return raw instanceof Number n ? n.longValue() : Long.parseLong(raw.toString());
    }
}
