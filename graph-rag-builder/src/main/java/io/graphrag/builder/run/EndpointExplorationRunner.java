package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.capture.ParsedSql;
import io.graphrag.builder.capture.SqlLogParser;
import io.graphrag.builder.coverage.BranchCoverage;
import io.graphrag.builder.coverage.BranchCoverageAnalyzer;
import io.graphrag.builder.coverage.CoverageClient;
import io.graphrag.builder.coverage.CoverageFingerprint;
import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.env.SutHandle;
import io.graphrag.builder.explore.CoverageGuidedFuzzer;
import io.graphrag.builder.explore.EndpointInvoker;
import io.graphrag.builder.explore.EndpointTarget;
import io.graphrag.builder.explore.ExplorationOrchestrator;
import io.graphrag.builder.explore.ExplorationOutcome;
import io.graphrag.builder.explore.HeuristicExplorer;
import io.graphrag.builder.explore.InvocationOutcome;
import io.graphrag.builder.explore.PathCandidate;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.ConstraintExtractor;
import io.graphrag.builder.index.ValidationConstraintExtractor.FieldConstraint;
import io.graphrag.builder.index.ValidationConstraintExtractor.Kind;
import io.graphrag.builder.oracle.InputCandidates;
import io.graphrag.model.BindingOrigin;
import io.graphrag.model.BranchRef;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ExplorationReport;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.Json;
import io.graphrag.model.ParamKind;
import io.graphrag.model.RequestHeaders;
import io.graphrag.model.RequiredSeed;
import io.graphrag.model.SqlBinding;
import io.graphrag.model.TableSchema;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * endpoint 1개에 대한 분기 탐색 + sink 캡처 (Phase 1, docs/05).
 * 흐름: seed INSERT → 커버리지 baseline reset → 오케스트레이터 탐색
 * (입력별 로그 구간 + 분기 기록) → path별 SQL 파싱/제약 첨부 → 리포트.
 */
public class EndpointExplorationRunner {

    private static final Logger log = LoggerFactory.getLogger(EndpointExplorationRunner.class);
    private static final int FUZZER_SATURATION = 2;   // 연속 dry 시드 패스 수
    private static final int VARIANT_CAP = 4;          // 엔드포인트당 negative-validation 변종 상한(ReadInputSynthesizer와 일치)

    public record EndpointResult(List<ExploredPath> paths, List<CapturedSql> sql,
                                 List<io.graphrag.model.CapturedHttpCall> httpCalls,
                                 List<RequiredSeed> seeds,
                                 ExplorationReport.EndpointExploration report,
                                 ExecutionDataStore cumulativeExec) {
    }

    private final SutHandle sut;
    private final Connection connection;
    private final DbConfig.Type dbType;
    private final CoverageClient coverage;
    private final BranchCoverageAnalyzer analyzer;
    private final int budgetRequests;
    private final io.graphrag.builder.env.HttpCaptureServer httpCapture;
    private final List<Set<String>> responseDtoFieldSets;
    private final List<String> literalCandidates;
    private final AuthTokenProvider authProvider;  // nullable — D5에서 배선
    private final AuthConfig authConfig;           // nullable — authProvider와 쌍
    private final Map<String, List<String>> enumConstants;  // enum FQN → 상수 (유효 happy 입력)
    private final Map<String, List<String>> enumColumns;    // 컬럼(snake) → 유효 enum 상수 (시드 읽기 500 방지)
    private final RequestHeaders extraHeaders;              // 사용자 지정 커스텀 헤더 (B3)
    // 요청별 dump(reset)을 누적 병합 → arm-level 정확 커버리지. 분기 양쪽(true/false)이
    // 서로 다른 요청에서 찍혀도 probe OR로 합산된다 (count-union 모델의 arm-blind 한계 보완).
    private ExecutionDataStore cumulativeCoverage = new ExecutionDataStore();
    private Set<String> appClasses = Set.of();   // path 지문을 SUT 자체 클래스로 한정

    public EndpointExplorationRunner(SutHandle sut, Connection connection,
                                     DbConfig.Type dbType,
                                     CoverageClient coverage, BranchCoverageAnalyzer analyzer,
                                     int budgetRequests,
                                     io.graphrag.builder.env.HttpCaptureServer httpCapture,
                                     List<Set<String>> responseDtoFieldSets,
                                     List<String> literalCandidates,
                                     AuthTokenProvider authProvider,
                                     AuthConfig authConfig,
                                     Map<String, List<String>> enumConstants,
                                     Map<String, List<String>> enumColumns,
                                     RequestHeaders extraHeaders) {
        if ((authProvider == null) != (authConfig == null)) {
            throw new IllegalArgumentException("authProvider and authConfig must be set together");
        }
        this.sut = sut;
        this.connection = connection;
        this.dbType = dbType;
        this.coverage = coverage;
        this.analyzer = analyzer;
        this.budgetRequests = budgetRequests;
        this.httpCapture = httpCapture;
        this.responseDtoFieldSets = responseDtoFieldSets;
        this.literalCandidates = literalCandidates;
        this.authProvider = authProvider;
        this.authConfig = authConfig;
        this.enumConstants = enumConstants;
        this.enumColumns = enumColumns;
        this.extraHeaders = extraHeaders;
    }

    public EndpointResult run(Endpoint endpoint, BodyShape shape, List<TableSchema> tables,
                              List<ConstraintExtractor.ConditionSpan> conditions,
                              List<ConstraintExtractor.Comparison> comparisons,
                              InputCandidates candidates,
                              Map<String, List<FieldConstraint>> fieldConstraints,
                              List<ConstraintExtractor.Conjunction> conjunctions,
                              List<ConstraintExtractor.StateGuard> stateGuards,
                              boolean validBody) throws Exception {
        cumulativeCoverage = new ExecutionDataStore();   // 엔드포인트마다 초기화
        if (appClasses.isEmpty()) {
            appClasses = analyzer.appClassNames();
        }
        boolean readPath = endpoint.httpMethod().equals("GET");
        boolean hasPathParam = endpoint.params().stream().anyMatch(p -> p.kind() == ParamKind.PATH);
        // GET뿐 아니라 비-GET by-id(PUT/DELETE /{id})도 리소스를 미리 시드하고, 생성 테스트가
        // 그 리소스를 재현하도록 requiredSeeds에 등록해야 빈 DB에서도 통과한다(Bug: 비-GET 시드 미재현).
        boolean seedResource = readPath || hasPathParam;
        Map<String, List<FieldConstraint>> happyConstraints =
                mergeComparisonBounds(fieldConstraints, comparisons, shape);
        SynthesizedInput happy = happyInput(endpoint, shape, tables, enumConstants, enumColumns, happyConstraints);

        List<RequiredSeed> requiredSeeds = insertSeeds(happy, endpoint, seedResource, tables);

        coverage.dump(true);   // 부팅/seed 구간을 잘라내고 baseline 확보

        ObjectNode baseInput = happy.body();
        List<BodyShape.BodyField> mutableFields = readPath
                ? endpoint.params().stream()
                      .filter(p -> p.kind() == ParamKind.PATH || p.kind() == ParamKind.QUERY)
                      .map(p -> new BodyShape.BodyField(p.name(), p.javaType()))
                      .toList()
                : (shape == null ? List.of() : shape.fields());

        // 입력 후보는 오라클(static-literal + concolic ASM+Z3)이 이미 합쳐 산출. 필드별 투영.
        Map<String, Set<Long>> conditionBounds = candidates.numeric();
        Map<String, Set<String>> stringCandidates = candidates.strings();
        List<Map<String, Long>> interFieldTuples = candidates.tuples();   // inter-field 동시충족 해
        ExplorationOrchestrator orchestrator = new ExplorationOrchestrator(
                List.of(new HeuristicExplorer(), new CoverageGuidedFuzzer(FUZZER_SATURATION)),
                budgetRequests);
        EndpointInvoker invoker = buildInvoker(endpoint, readPath, hasPathParam, happy);
        EndpointTarget target = new EndpointTarget(endpoint, baseInput, mutableFields, tables,
                invoker, literalCandidates,
                fieldConstraints, conditionBounds, stringCandidates, enumConstants, conjunctions,
                interFieldTuples);
        ExplorationOutcome outcome = orchestrator.explore(target);
        log.info("explored {}: {} path(s), {} branch(es) covered",
                endpoint.id(), outcome.paths().size(), outcome.coveredBranches().size());

        PathsBundle bundle = buildPaths(outcome, endpoint, conditions);

        // ---- SQL-기반 보정 2-pass ----
        // path-string 휴리스틱이 테이블을 **못 찾은** 엔드포인트(resource명≠table명)에 한해,
        // pass-1 캡처 SQL의 FROM/WHERE로 시드를 보정하고 재탐색한다. 휴리스틱이 이미 테이블을
        // 해석한 엔드포인트(petclinic /pets→pets 등)는 건드리지 않는다 — 다중 SELECT(부모 엔티티+
        // 컬렉션 로드)에서 param명이 자식 FK 컬럼명과 우연히 일치해 자식 테이블을 오선택하는
        // 회귀를 원천 차단(회귀 0). 실제 타깃(analytics/mindgraph/diary/auth-user)은 모두 null.
        if (seedResource) {
            ResolutionHint heuristic = new ReadInputSynthesizer(enumConstants, enumColumns)
                    .heuristicResolution(endpoint, tables);
            ResolutionHint hint = heuristic.table() == null
                    ? SqlSeedResolver.resolve(bundle.allSql(),
                          sentParamValues(endpoint, happy.body()), endpoint, tables)
                    : null;
            if (hint != null && hint.table() != null) {
                SynthesizedInput pass1Happy = happy;
                List<RequiredSeed> pass1Seeds = requiredSeeds;
                PathsBundle pass1Bundle = bundle;
                ExecutionDataStore pass1Cumulative = cumulativeCoverage;
                ExplorationOutcome pass1Outcome = outcome;
                try {
                    deleteSeeds(pass1Happy);
                    SynthesizedInput happy2 = happyInput(endpoint, shape, tables,
                            enumConstants, enumColumns, happyConstraints, hint);
                    requiredSeeds = insertSeeds(happy2, endpoint, seedResource, tables);
                    coverage.dump(true);                          // baseline: 부팅+pass-1+시드 구간 컷
                    cumulativeCoverage = new ExecutionDataStore(); // 리포트를 pass-2(시드된 run)만 반영
                    EndpointInvoker invoker2 = buildInvoker(endpoint, readPath, hasPathParam, happy2);
                    EndpointTarget target2 = new EndpointTarget(endpoint, happy2.body(), mutableFields,
                            tables, invoker2, literalCandidates,
                            fieldConstraints, conditionBounds, stringCandidates, enumConstants, conjunctions,
                            interFieldTuples);
                    outcome = orchestrator.explore(target2);
                    log.info("re-explored {} (SQL hint table={}): {} path(s)",
                            endpoint.id(), hint.table(), outcome.paths().size());
                    bundle = buildPaths(outcome, endpoint, conditions);
                    happy = happy2;
                } catch (Exception e) {
                    log.warn("SQL-driven re-seed failed for {} (table={}), keeping pass-1: {}",
                            endpoint.id(), hint.table(), e.getMessage());
                    requiredSeeds = pass1Seeds;
                    bundle = pass1Bundle;
                    cumulativeCoverage = pass1Cumulative;
                    outcome = pass1Outcome;
                    reinsertSeeds(pass1Happy);
                }
            }
        }

        AttachResult attached = attachSeeds(endpoint, readPath, bundle.paths(), requiredSeeds);

        // Stage 4: 상태 의존 가드(저장 행 상태로 분기)의 반대 arm을 대체 시드 변종으로 연다.
        // httpInvoker가 변종 요청의 커버리지를 cumulativeCoverage에 OR-병합하므로 report()의
        // missedBranches에서 그 라인이 사라진다(missed→covered). 변종 path/seed는 결과에 추가.
        List<ExploredPath> finalPaths = new ArrayList<>(attached.paths());
        List<RequiredSeed> finalSeeds = new ArrayList<>(
                attached.requiredSeeds() == null ? List.of() : attached.requiredSeeds());
        List<CapturedSql> finalSql = new ArrayList<>(bundle.allSql());
        if (seedResource && stateGuards != null && !stateGuards.isEmpty()) {
            VariantResult vr = exploreStateGuardVariants(endpoint, tables, stateGuards);
            finalPaths.addAll(vr.paths());
            finalSeeds.addAll(vr.seeds());
            finalSql.addAll(vr.sql());
        }

        // 부정-인증 패스: auth-required 엔드포인트에 무효 토큰 1회 발행 → JWT 필터 거부 arm(validate→false +
        // JwtUtil.validate catch) 커버. doSend의 per-request dump가 cumulativeCoverage에 크레딧(report 전).
        // negative-auth path는 생성에서 제외(discoveredBy 마커). GRB_NEGATIVE_AUTH=off면 skip.
        if (authProvider != null && endpoint.authRequired() && baseInput != null
                && !"off".equalsIgnoreCase(System.getenv("GRB_NEGATIVE_AUTH"))) {
            try {
                InvocationOutcome neg = doSend(HttpClient.newHttpClient(), endpoint, baseInput,
                        authConfig.headerValue("invalid-token-" + endpoint.id()));
                finalPaths.add(new ExploredPath(endpoint.id() + "-negauth", endpoint.id(),
                        baseInput, neg.status(), neg.response(), List.of(), List.of(),
                        List.copyOf(neg.coveredBranches()), "negative-auth", List.of(), List.of(), List.of()));
                log.info("negative-auth {} -> status {}", endpoint.id(), neg.status());
            } catch (Exception e) {   // best-effort: 부정 패스 실패는 회귀 아님
                log.warn("negative-auth pass failed for {}: {}", endpoint.id(), e.getMessage());
            }
        }

        // 부정-검증 패스(B1): @Valid @RequestBody(JSON BODY) 엔드포인트에 어노테이션 제약 위반 변종을 발행 →
        // Spring MethodArgumentNotValidException 거부 arm(4xx) 커버. doSend의 per-request dump가
        // cumulativeCoverage에 크레딧. negative-validation path는 생성 제외(discoveredBy 마커).
        // orchestrator 우회(검증 실패는 컨트롤러 진입 전 동일 400이라 status+coverageKey로 1 path 병합됨).
        // baseInput은 pass-1 happy body다(SQL 2-pass 재시드가 happy를 happy2로 바꿔도 유지 — negative-auth와 동일).
        // 어노테이션 검증엔 무해: 어떤 valid happy 값이든 단일필드 위반이 reject arm을 연다.
        if (validBody && baseInput != null && shape != null
                && !"off".equalsIgnoreCase(System.getenv("GRB_NEGATIVE_VALIDATION"))) {
            finalPaths.addAll(exploreNegativeValidationVariants(endpoint, shape, fieldConstraints, baseInput));
        }

        // app 분기 집계는 BuilderCli가 전 루프(Kafka+HTTP+WS) 종료 후 runWideExec로 1회 산출한다.
        // 여기선 누적 exec만 넘긴다(arm-level OR 병합 근거). report()는 cumulativeCoverage 기준이므로
        // 변종 pass 이후에 호출해야 미커버 전이가 반영된다.
        return new EndpointResult(finalPaths, finalSql, bundle.httpCalls(),
                finalSeeds, report(endpoint, outcome, comparisons), cumulativeCoverage);
    }

    /**
     * 부정-검증 변종 pass(B1): happy body를 복제해 어노테이션 제약을 한 필드만 위반시킨 변종을 각각 1회
     * 발행한다(orchestrator 밖 — negative-auth와 동일). 검증은 컨트롤러 진입 전이라 valid 토큰으로 보낸다.
     * doSend가 per-request 커버리지를 cumulativeCoverage에 OR-병합해 reject arm을 크레딧. 각 변종을
     * 고유·결정적 path-id의 ExploredPath로 등록(생성 제외 마커 discoveredBy="negative-validation").
     */
    private List<ExploredPath> exploreNegativeValidationVariants(
            Endpoint endpoint, BodyShape shape,
            Map<String, List<FieldConstraint>> fieldConstraints, ObjectNode happyBody) {
        List<ExploredPath> paths = new ArrayList<>();
        List<NegativeValidationSynthesizer.NegativeVariant> variants =
                NegativeValidationSynthesizer.synthesizeNegativeValidationVariants(
                        shape, fieldConstraints, happyBody, VARIANT_CAP);
        if (variants.isEmpty()) {
            return paths;
        }
        HttpClient http = HttpClient.newHttpClient();
        String authHeader = (authProvider != null && endpoint.authRequired())
                ? authConfig.headerValue(authProvider.token()) : null;
        for (NegativeValidationSynthesizer.NegativeVariant variant : variants) {
            String pathId = endpoint.id() + "-negval-" + variant.field() + "-"
                    + variant.kind().name().toLowerCase();
            try {
                InvocationOutcome out = doSend(http, endpoint, variant.body(), authHeader);
                if (out.status() / 100 != 4) {
                    // 선행 가드 throw 등으로 4xx가 아니면 reject arm 귀속 불확실(R1) — 캡처하되 경고.
                    log.warn("negative-validation {} ({}={}) -> status {} (expected 4xx, attribution uncertain)",
                            endpoint.id(), variant.field(), variant.kind(), out.status());
                } else if (out.response() != null && !out.response().toString().contains(variant.field())) {
                    // 4xx여도 응답에 기대 필드명이 없으면 귀속 불확실(R1 recommended).
                    log.warn("negative-validation {} -> {} but response omits field '{}' (attribution uncertain)",
                            endpoint.id(), out.status(), variant.field());
                }
                paths.add(new ExploredPath(pathId, endpoint.id(), variant.body(), out.status(),
                        out.response(), List.of(), List.of(),
                        List.copyOf(out.coveredBranches()), "negative-validation",
                        List.of("negative-validation:" + variant.field() + ":" + variant.kind()),
                        List.of(), List.of()));
                log.info("negative-validation {} ({}={}) -> status {}",
                        endpoint.id(), variant.field(), variant.kind(), out.status());
            } catch (Exception e) {   // best-effort: 부정 패스 실패는 회귀 아님
                log.warn("negative-validation variant failed for {} ({}): {}",
                        endpoint.id(), variant.field(), e.getMessage());
            }
        }
        return paths;
    }

    private record VariantResult(List<ExploredPath> paths, List<RequiredSeed> seeds,
                                 List<CapturedSql> sql) {
    }

    /**
     * 상태가드 변종 pass: 가드별 대체 시드 행을 insert하고, 게이팅 boolean 쿼리 param을 설정해
     * by-id 요청을 1회 구동(orchestrator 밖 — 입력 변이·시드 리셋 간섭 회피). httpInvoker가 커버리지를
     * cumulativeCoverage에 OR-병합한다. 각 변종 arm을 distinct ExploredPath + 자기 RequiredSeed로 등록.
     * 게이팅 규칙(검증된 두 family): TEMPORAL(stale)→boolean=false(예: includeStale=false),
     * ENUM(conflict)→boolean=true(예: confirm=true).
     */
    private VariantResult exploreStateGuardVariants(Endpoint endpoint, List<TableSchema> tables,
                                                    List<ConstraintExtractor.StateGuard> stateGuards)
            throws Exception {
        List<ExploredPath> paths = new ArrayList<>();
        List<RequiredSeed> seeds = new ArrayList<>();
        List<CapturedSql> sqls = new ArrayList<>();
        List<ReadInputSynthesizer.SeedVariant> variants =
                new ReadInputSynthesizer(enumConstants, enumColumns)
                        .synthesizeVariants(endpoint, tables, stateGuards);
        if (variants.size() <= 1) {
            return new VariantResult(paths, seeds, sqls);
        }
        EndpointInvoker invoker = httpInvoker(endpoint);   // raw — 시드 리셋 래핑 없음
        int vseq = 0;
        for (int v = 1; v < variants.size(); v++) {
            ReadInputSynthesizer.SeedVariant variant = variants.get(v);
            vseq++;
            try {
                for (SynthesizedInput.SeedRow row : variant.input().seeds()) {
                    Seeds.insert(connection, dbType, row);   // 변종 행(offset PK) — 기존 happy 행과 공존
                }
                boolean gate = variant.guard().kind() != ConstraintExtractor.GuardKind.TEMPORAL;
                ObjectNode body = variant.input().body().deepCopy();
                for (EndpointParam param : endpoint.params()) {
                    if (param.kind() == ParamKind.QUERY && isBooleanType(param.javaType())) {
                        body.put(param.name(), gate);
                    }
                }
                InvocationOutcome out = invoker.invoke(body);
                String pathId = endpoint.id() + "-sg" + vseq;
                List<CapturedSql> sql = captureSqlForRange(pathId, body, out.logStart(), out.logEnd());
                sqls.addAll(sql);
                List<String> seedIds = new ArrayList<>();
                int sj = 0;
                for (SynthesizedInput.SeedRow row : variant.input().seeds()) {
                    sj++;
                    String sid = "seed-" + pathId + "-" + sj;
                    seeds.add(new RequiredSeed(sid, pathId, row.table(), row.columns(),
                            row.values().stream().map(String::valueOf).toList()));
                    seedIds.add(sid);
                }
                paths.add(new ExploredPath(pathId, endpoint.id(), body, out.status(), out.response(),
                        sql.stream().map(CapturedSql::id).toList(), List.of(),
                        List.copyOf(out.coveredBranches()), "state-guard",
                        List.of("state-guard:" + variant.guard().kind() + ":" + variant.guard().column()),
                        List.of(), seedIds));
            } catch (Exception e) {   // best-effort: 변종 실패는 회귀 아님(base 결과 유지)
                log.warn("state-guard variant failed for {} ({}): {}",
                        endpoint.id(), variant.guard().column(), e.getMessage());
            }
        }
        return new VariantResult(paths, seeds, sqls);
    }

    private static boolean isBooleanType(String javaType) {
        return javaType.equals("boolean") || javaType.equals("java.lang.Boolean");
    }

    /** captureSql(PathCandidate)의 임의 로그구간 버전 — 변종 요청의 SQL 캡처용. */
    private List<CapturedSql> captureSqlForRange(String pathId, JsonNode body,
                                                 long logStart, long logEnd) {
        List<ParsedSql> parsed = SqlLogParser.parse(sut.readLogRange(logStart, logEnd));
        Set<String> apiValues = bodyValues(body);
        List<CapturedSql> captured = new ArrayList<>();
        int sequence = 0;
        for (ParsedSql statement : parsed) {
            sequence++;
            List<SqlBinding> bindings = new ArrayList<>();
            for (ParsedSql.Binding binding : statement.bindings()) {
                bindings.add(new SqlBinding(binding.position(),
                        statement.columnForPosition(binding.position()), binding.value(),
                        apiValues.contains(binding.value())
                                ? BindingOrigin.API_PARAM : BindingOrigin.LITERAL,
                        statement.bindingTableForPosition(binding.position())));
            }
            captured.add(new CapturedSql("sql-" + pathId + "-" + sequence, pathId,
                    statement.kind(), statement.sql(), statement.tableName(), bindings));
        }
        return captured;
    }

    private record PathsBundle(List<ExploredPath> paths, List<CapturedSql> allSql,
                               List<io.graphrag.model.CapturedHttpCall> httpCalls) {
    }

    private record AttachResult(List<ExploredPath> paths, List<RequiredSeed> requiredSeeds) {
    }

    /** PATH/QUERY param 이름 → pass-1에서 보낸 문자열 값 (SqlSeedResolver의 값 매칭용). */
    private static Map<String, String> sentParamValues(Endpoint endpoint, JsonNode body) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        for (EndpointParam param : endpoint.params()) {
            if ((param.kind() == ParamKind.PATH || param.kind() == ParamKind.QUERY)
                    && body.has(param.name())) {
                values.put(param.name(), body.get(param.name()).asText());
            }
        }
        return values;
    }

    /** 시드 INSERT + (read/by-id) IDENTITY 시퀀스 재동기화 + RequiredSeed 구성. */
    private List<RequiredSeed> insertSeeds(SynthesizedInput happy, Endpoint endpoint,
                                           boolean seedResource, List<TableSchema> tables) throws Exception {
        List<RequiredSeed> requiredSeeds = new ArrayList<>();
        int seedSeq = 0;
        for (SynthesizedInput.SeedRow seed : happy.seeds()) {
            Seeds.insert(connection, dbType, seed);
            if (seedResource) {
                // read-path seed는 IDENTITY PK에 명시 id를 넣는다. 시퀀스가 전진하지 않아
                // 이후 POST 탐색의 auto-INSERT가 같은 id로 충돌(500)하므로 재동기화한다.
                resyncIdentitySequence(seed.table(), tables);
                seedSeq++;
                requiredSeeds.add(new RequiredSeed(
                        "seed-" + endpoint.id() + "-" + seedSeq, null, seed.table(),
                        seed.columns(), seed.values().stream().map(String::valueOf).toList()));
            }
        }
        return requiredSeeds;
    }

    /** pass-1 시드를 역순(child→parent) DELETE. 시드 없으면 no-op. */
    private void deleteSeeds(SynthesizedInput happy) {
        List<SynthesizedInput.SeedRow> seeds = happy.seeds();
        for (int i = seeds.size() - 1; i >= 0; i--) {
            Seeds.delete(connection, seeds.get(i));
        }
    }

    /** pass-2 실패 시 pass-1 시드를 best-effort로 복원(다운스트림 생성 테스트 정합). */
    private void reinsertSeeds(SynthesizedInput happy) {
        for (SynthesizedInput.SeedRow row : happy.seeds()) {
            try {
                Seeds.insert(connection, dbType, row);
            } catch (Exception e) {
                log.warn("pass-1 seed restore failed for {}: {}", row.table(), e.getMessage());
            }
        }
    }

    private EndpointInvoker buildInvoker(Endpoint endpoint, boolean readPath,
                                         boolean hasPathParam, SynthesizedInput happy) {
        EndpointInvoker invoker = httpInvoker(endpoint);
        // mutating by-id(PUT/DELETE /{id})는 탐색 중 공유 시드 행을 변이·누적한다 → 각 요청 전에
        // 리소스를 fresh 시드로 리셋해 각 path 응답을 (fresh 시드, 그 요청)의 순수 함수로 만든다.
        boolean mutatingById = !readPath && hasPathParam && !happy.seeds().isEmpty();
        if (mutatingById) {
            EndpointInvoker base = invoker;
            List<SynthesizedInput.SeedRow> resetRows = happy.seeds();
            invoker = body -> {
                resetSeeds(resetRows);
                return base.invoke(body);
            };
        }
        return invoker;
    }

    /** outcome → ExploredPath/CapturedSql/CapturedHttpCall 묶음. */
    private PathsBundle buildPaths(ExplorationOutcome outcome, Endpoint endpoint,
                                  List<ConstraintExtractor.ConditionSpan> conditions) {
        List<ExploredPath> paths = new ArrayList<>();
        List<CapturedSql> allSql = new ArrayList<>();
        List<io.graphrag.model.CapturedHttpCall> allHttpCalls = new ArrayList<>();
        for (PathCandidate candidate : outcome.paths()) {
            List<CapturedSql> sql = captureSql(candidate);
            allSql.addAll(sql);
            List<io.graphrag.model.CapturedHttpCall> httpCalls = captureHttpCalls(candidate);
            allHttpCalls.addAll(httpCalls);
            // seed는 성공(2xx) path에만 연결 — attachSeeds에서 채운다
            paths.add(new ExploredPath(
                    candidate.pathId(),
                    endpoint.id(),
                    candidate.body(),
                    candidate.status(),
                    candidate.response(),
                    sql.stream().map(CapturedSql::id).toList(),
                    httpCalls.stream().map(io.graphrag.model.CapturedHttpCall::id).toList(),
                    candidate.branches(),
                    candidate.discoveredBy(),
                    matchConstraints(candidate, conditions, endpoint),
                    validate(sql),
                    List.of()));
        }
        return new PathsBundle(paths, allSql, allHttpCalls);
    }

    /** 시드를 path에 연결: GET은 첫 2xx path, 비-GET by-id는 path별 고유 PK 복제. */
    private AttachResult attachSeeds(Endpoint endpoint, boolean readPath,
                                     List<ExploredPath> paths, List<RequiredSeed> requiredSeeds) {
        if (!(requiredSeeds != null && !requiredSeeds.isEmpty())) {
            return new AttachResult(paths, requiredSeeds);
        }
        if (readPath) {
            // GET: id가 변이되므로(404/400 path 존재) seed는 첫 2xx(존재하는 id) path에만 연결.
            int successIdx = -1;
            for (int i = 0; i < paths.size(); i++) {
                if (paths.get(i).expectedStatus() / 100 == 2) { successIdx = i; break; }
            }
            if (successIdx >= 0) {
                ExploredPath p = paths.get(successIdx);
                List<String> seedIds = requiredSeeds.stream().map(RequiredSeed::id).toList();
                paths.set(successIdx, withSeedIds(p, seedIds));
                String pid = p.id();
                requiredSeeds = requiredSeeds.stream()
                        .map(s -> new RequiredSeed(s.id(), pid, s.table(), s.columns(), s.values()))
                        .toList();
            }
            return new AttachResult(paths, requiredSeeds);
        }
        // 비-GET by-id: id를 변이하지 않으므로 모든 path가 대상 리소스에 의존한다.
        // path마다 리소스 시드를 고유 PK로 복제(병렬 테스트 PK 충돌 방지)하고, 그 path의
        // url path-id를 같은 값으로 재기록한다(응답 단언은 notNullValue라 값 변경 안전).
        String pathParam = endpoint.params().stream()
                .filter(pp -> pp.kind() == ParamKind.PATH)
                .map(io.graphrag.model.EndpointParam::name).findFirst().orElse(null);
        List<RequiredSeed> perPath = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            ExploredPath p = paths.get(i);
            List<String> seedIds = new ArrayList<>();
            String newPk = null;
            for (int j = 0; j < requiredSeeds.size(); j++) {
                RequiredSeed base = requiredSeeds.get(j);
                List<String> vals = new ArrayList<>(base.values());
                String pk = offsetId(vals.get(0), i);
                vals.set(0, pk);
                if (j == 0) {
                    newPk = pk;
                }
                String sid = "seed-" + endpoint.id() + "-p" + i + "-" + j;
                perPath.add(new RequiredSeed(sid, p.id(), base.table(), base.columns(), vals));
                seedIds.add(sid);
            }
            ExploredPath np = withSeedIds(p, seedIds);
            if (pathParam != null && newPk != null && np.sampleInput() instanceof ObjectNode body) {
                ObjectNode nb = body.deepCopy();
                nb.put(pathParam, newPk);
                // 응답에 같은 id가 실리므로(구체값 단언과 정합) sampleResponse의 PK 필드도 갱신.
                JsonNode resp = np.sampleResponse();
                if (resp instanceof ObjectNode ro && ro.has(pathParam)) {
                    ObjectNode rc = ro.deepCopy();
                    try {
                        rc.put(pathParam, Long.parseLong(newPk));   // 응답 id는 숫자 노드(어설션 정합)
                    } catch (NumberFormatException e) {
                        rc.put(pathParam, newPk);
                    }
                    resp = rc;
                }
                np = new ExploredPath(np.id(), np.endpointId(), nb, np.expectedStatus(),
                        resp, np.capturedSqlIds(), np.capturedHttpCallIds(),
                        np.branchesTaken(), np.discoveredBy(), np.constraints(),
                        np.validationWarnings(), np.requiredSeedIds());
            }
            paths.set(i, np);
        }
        return new AttachResult(paths, perPath);
    }

    /** RawHttpExchange → CapturedHttpCall. consumedFields는 응답 ∩ DTO 필드 (2.5 근사). */
    private List<io.graphrag.model.CapturedHttpCall> captureHttpCalls(PathCandidate candidate) {
        List<io.graphrag.model.CapturedHttpCall> calls = new ArrayList<>();
        int sequence = 0;
        for (io.graphrag.builder.explore.RawHttpExchange exchange : candidate.httpExchanges()) {
            sequence++;
            calls.add(new io.graphrag.model.CapturedHttpCall(
                    "http-" + candidate.pathId() + "-" + sequence,
                    candidate.pathId(),
                    exchange.method(),
                    exchange.urlPath(),
                    exchange.query(),
                    exchange.requestBody() == null || exchange.requestBody().isBlank()
                            ? null : exchange.requestBody(),
                    exchange.status(),
                    exchange.responseBody(),
                    consumedFields(exchange.responseBody()),
                    exchange.baggagePresent()));
        }
        return calls;
    }

    private List<String> consumedFields(String responseBody) {
        Set<String> responseFields = new LinkedHashSet<>();
        try {
            Json.mapper().readTree(responseBody).fieldNames()
                    .forEachRemaining(responseFields::add);
        } catch (Exception e) {
            return List.of();
        }
        // 응답 필드와 가장 많이 겹치는 DTO 필드 집합과의 교집합
        List<String> best = List.of();
        for (Set<String> dtoFields : responseDtoFieldSets) {
            List<String> overlap = responseFields.stream()
                    .filter(dtoFields::contains).sorted().toList();
            if (overlap.size() > best.size()) {
                best = overlap;
            }
        }
        return best;
    }

    /** 입력 1회 = HTTP 호출 + 로그 구간 마킹 + 요청 단위 분기 dump. */
    private EndpointInvoker httpInvoker(Endpoint endpoint) {
        HttpClient http = HttpClient.newHttpClient();
        return input -> {
            try {
                String authHeader = (authProvider != null && endpoint.authRequired())
                        ? authConfig.headerValue(authProvider.token()) : null;
                return doSend(http, endpoint, input, authHeader);
            } catch (Exception e) {
                throw new IllegalStateException("invocation failed: " + endpoint.path(), e);
            }
        };
    }

    /**
     * HTTP 요청 1회 전송 + 요청 단위 커버리지 dump. authHeaderValue!=null이면 그 값을 auth 헤더로 설정,
     * null이면 미설정. 부정-인증 패스(무효 토큰)도 이 코어를 재사용한다(per-request dump가 거부 arm을 크레딧).
     */
    private InvocationOutcome doSend(HttpClient http, Endpoint endpoint, JsonNode input,
                                    String authHeaderValue) throws Exception {
        long logStart = sut.logOffset();
        String url = sut.baseUri() + buildPathAndQuery(endpoint, input);
        // @Controller 폼 핸들러는 application/x-www-form-urlencoded, 그 외는 JSON.
        boolean form = endpoint.params().stream().anyMatch(p -> p.kind() == ParamKind.FORM);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", form ? "application/x-www-form-urlencoded" : "application/json")
                // propagation 실측용 (docs/06): outbound로 복사되는지 관찰
                .header("baggage", "test-id=explore");
        if (authHeaderValue != null) {
            builder.header(authConfig.headerName(), authHeaderValue);
        }
        for (Map.Entry<String, String> h : extraHeaders.resolved(Instant.now()).entrySet()) {
            builder.header(h.getKey(), h.getValue());
        }
        String method = endpoint.httpMethod();
        if (method.equals("GET") || method.equals("DELETE")) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else if (form) {
            builder.method(method, HttpRequest.BodyPublishers.ofString(formEncode(bodyOnly(endpoint, input))));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(
                    Json.mapper().writeValueAsString(bodyOnly(endpoint, input))));
        }
        HttpResponse<String> response = http.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        Thread.sleep(150);   // 콘솔 로그 flush 여유
        ExecutionDataStore delta = coverage.dump(true);
        String coverageKey = CoverageFingerprint.of(delta, appClasses);
        for (ExecutionData ed : delta.getContents()) {
            cumulativeCoverage.put(ed);   // probe OR 병합 (arm-level 누적)
        }
        BranchCoverage requestCoverage = analyzer.analyze(delta);
        long logEnd = sut.logOffset();
        return new InvocationOutcome(response.statusCode(),
                parseJsonOrNull(response.body()),
                requestCoverage.covered(), logStart, logEnd,
                httpCapture == null ? List.of() : httpCapture.drainNewExchanges(),
                coverageKey);
    }

    /**
     * happy 입력 합성. GET 또는 비-GET by-id(PATH 파라미터 보유)는 ReadInputSynthesizer로 path/query +
     * 리소스 시드(유효 id)를 만든다(Bug 1: PUT/DELETE {id}가 sentinel "0"이 되어 service 미진입하던 것 해결).
     * 비-GET+PATH+body면 body(SampleInputSynthesizer)를 병합(seed는 table+pk로 dedupe, path/query 우선).
     */
    /**
     * 명령형 검증 가드(`if (field op literal) throw`)를 합성 MIN/MAX 제약으로 변환해 어노테이션
     * 제약과 병합한다(Feature A 확장). `field < L`→MIN(L), `<=`→MIN(L+1), `>`→MAX(L), `>=`→MAX(L-1).
     *
     * 안전망: 비교는 전 계층에서 추출되고 throw/branch 방향 정보가 없으므로(비-가드 비교 오용 위험),
     * **하한(`<`/`<=`)과 상한(`>`/`>=`) 비교를 모두 가진 필드에만** 적용한다 — 양방향 범위는 검증
     * range 가드(`if(x<lo || x>hi) throw`)의 강한 신호이고, 단방향 비교(흔히 비즈니스 분기)는 제외.
     * body 필드명 일치만. 범위 충돌은 boundedInt가 default로 흡수.
     */
    static Map<String, List<FieldConstraint>> mergeComparisonBounds(
            Map<String, List<FieldConstraint>> annotations,
            List<ConstraintExtractor.Comparison> comparisons, BodyShape shape) {
        if (shape == null || comparisons.isEmpty()) {
            return annotations;
        }
        Set<String> bodyFields = shape.fields().stream()
                .map(BodyShape.BodyField::name).collect(Collectors.toSet());
        // 필드별 비교 수집 후, 양방향(하한+상한)을 모두 가진 필드만 채택.
        Map<String, List<ConstraintExtractor.Comparison>> byField = new java.util.LinkedHashMap<>();
        for (ConstraintExtractor.Comparison c : comparisons) {
            if (bodyFields.contains(c.fieldRef())
                    && (c.op().equals("<") || c.op().equals("<=")
                        || c.op().equals(">") || c.op().equals(">="))) {
                byField.computeIfAbsent(c.fieldRef(), x -> new ArrayList<>()).add(c);
            }
        }
        Map<String, List<FieldConstraint>> merged = new java.util.HashMap<>();
        annotations.forEach((k, v) -> merged.put(k, new ArrayList<>(v)));
        byField.forEach((field, comps) -> {
            boolean hasLower = comps.stream().anyMatch(c -> c.op().startsWith("<"));
            boolean hasUpper = comps.stream().anyMatch(c -> c.op().startsWith(">"));
            if (!(hasLower && hasUpper)) {
                return;   // 단방향 비교(비-가드 위험) 제외
            }
            for (ConstraintExtractor.Comparison c : comps) {
                FieldConstraint fc = switch (c.op()) {
                    case "<" -> new FieldConstraint(field, Kind.MIN, c.literal(), null);
                    case "<=" -> new FieldConstraint(field, Kind.MIN, c.literal() + 1, null);
                    case ">" -> new FieldConstraint(field, Kind.MAX, c.literal(), null);
                    case ">=" -> new FieldConstraint(field, Kind.MAX, c.literal() - 1, null);
                    default -> null;
                };
                if (fc != null) {
                    merged.computeIfAbsent(field, x -> new ArrayList<>()).add(fc);
                }
            }
        });
        return merged;
    }

    static SynthesizedInput happyInput(Endpoint endpoint, BodyShape shape, List<TableSchema> tables,
                                       Map<String, List<String>> enumConstants,
                                       Map<String, List<String>> enumColumns,
                                       Map<String, List<FieldConstraint>> fieldConstraints) {
        return happyInput(endpoint, shape, tables, enumConstants, enumColumns, fieldConstraints, null);
    }

    // 제약-aware happy(Feature A: fieldConstraints) + SQL-driven seed 보정(hint)을 모두 받는 정본.
    static SynthesizedInput happyInput(Endpoint endpoint, BodyShape shape, List<TableSchema> tables,
                                       Map<String, List<String>> enumConstants,
                                       Map<String, List<String>> enumColumns,
                                       Map<String, List<FieldConstraint>> fieldConstraints,
                                       ResolutionHint hint) {
        boolean get = endpoint.httpMethod().equals("GET");
        boolean hasPath = endpoint.params().stream().anyMatch(p -> p.kind() == ParamKind.PATH);
        if (get || hasPath) {
            SynthesizedInput pathPart =
                    new ReadInputSynthesizer(enumConstants, enumColumns).synthesize(endpoint, tables, hint);
            if (get || shape == null) {
                return pathPart;
            }
            SynthesizedInput bodyPart = new SampleInputSynthesizer(enumConstants)
                    .synthesize(shape, tables, fieldConstraints);
            ObjectNode merged = bodyPart.body().deepCopy();
            merged.setAll(pathPart.body());   // path/query 우선
            List<SynthesizedInput.SeedRow> seeds = new ArrayList<>(pathPart.seeds());
            for (SynthesizedInput.SeedRow s : bodyPart.seeds()) {
                boolean dup = seeds.stream().anyMatch(e -> e.table().equals(s.table())
                        && !e.values().isEmpty() && !s.values().isEmpty()
                        && e.values().get(0).equals(s.values().get(0)));
                if (!dup) {
                    seeds.add(s);
                }
            }
            return new SynthesizedInput(merged, seeds);
        }
        return shape == null
                ? new SynthesizedInput(Json.mapper().createObjectNode(), List.of())
                : new SampleInputSynthesizer(enumConstants).synthesize(shape, tables, fieldConstraints);
    }

    /** 시드 행들을 fresh 상태로 복원: reverse-order DELETE(child→parent) 후 정순 INSERT(parent→child).
     *  멱등 insert는 변이된 행 위에서 no-op이므로 반드시 DELETE를 먼저 한다. */
    private void resetSeeds(List<SynthesizedInput.SeedRow> seeds) {
        for (int i = seeds.size() - 1; i >= 0; i--) {
            Seeds.delete(connection, seeds.get(i));
        }
        for (SynthesizedInput.SeedRow row : seeds) {
            try {
                Seeds.insert(connection, dbType, row);
            } catch (Exception e) {
                throw new IllegalStateException("seed reset insert failed: " + row.table(), e);
            }
        }
    }

    private static ExploredPath withSeedIds(ExploredPath p, List<String> seedIds) {
        return new ExploredPath(p.id(), p.endpointId(), p.sampleInput(), p.expectedStatus(),
                p.sampleResponse(), p.capturedSqlIds(), p.capturedHttpCallIds(), p.branchesTaken(),
                p.discoveredBy(), p.constraints(), p.validationWarnings(), seedIds);
    }

    /** 정수 PK는 +i 오프셋(비충돌), 문자열 PK는 "_i" 접미사 — path별 고유 시드 id. */
    private static String offsetId(String value, int i) {
        if (i == 0) {
            return value;
        }
        try {
            return Long.toString(Long.parseLong(value) + i);
        } catch (NumberFormatException e) {
            return value + "_" + i;
        }
    }

    /** PATH param은 {name} 치환, QUERY param은 쿼리스트링으로. */
    static String buildPathAndQuery(Endpoint endpoint, JsonNode input) {
        String path = endpoint.path();
        StringBuilder query = new StringBuilder();
        for (EndpointParam param : endpoint.params()) {
            if (param.kind() == ParamKind.PATH) {
                // 변이가 path param을 지웠어도 URL은 항상 유효해야 한다.
                // 누락 시 라우트 모양을 유지하는 센티널을 넣어 SUT가 404/400을
                // 반환하게 한다 ({id}를 남기면 URI.create가 깨진다).
                String value = input.has(param.name())
                        ? input.get(param.name()).asText()
                        : pathSentinel(param);
                path = path.replace("{" + param.name() + "}", value);
            } else if (param.kind() == ParamKind.QUERY) {
                if (!input.has(param.name())) {
                    continue;
                }
                query.append(query.isEmpty() ? "?" : "&")
                        .append(param.name()).append("=").append(input.get(param.name()).asText());
            }
        }
        // 클래스-레벨 path 변수(@RequestMapping("/owners/{ownerId}"))가 @ModelAttribute 헬퍼에서만
        // 해석되어 핸들러 파라미터에 없는 경우(petclinic @Controller 폼 패턴), {placeholder}가 남아
        // URI.create가 깨진다. 매칭 안 된 placeholder는 센티널("0")로 치환해 URL을 항상 유효하게 둔다
        // (해당 리소스 미시드 → SUT가 not-found/4xx arm 반환).
        path = path.replaceAll("\\{[^/}]+}", "0");
        return path + query;
    }

    private static String pathSentinel(EndpointParam param) {
        return switch (param.javaType()) {
            case "java.lang.Integer", "int", "java.lang.Long", "long" -> "0";
            default -> "missing";
        };
    }

    /**
     * IDENTITY/SERIAL PK 테이블에 명시 id를 seed한 뒤 시퀀스를 MAX(id)로 재동기화한다.
     * 시퀀스가 없는 테이블(문자열 PK 등)은 pg_get_serial_sequence가 NULL을 반환해 no-op.
     */
    private void resyncIdentitySequence(String table, List<TableSchema> tables) {
        if (dbType != DbConfig.Type.POSTGRES) {
            return;
        }
        String pk = tables.stream()
                .filter(t -> t.name().equals(table))
                .flatMap(t -> t.columns().stream())
                .filter(io.graphrag.model.ColumnSchema::primaryKey)
                .map(io.graphrag.model.ColumnSchema::name)
                .findFirst().orElse(null);
        if (pk == null) {
            return;
        }
        String sql = "SELECT setval(pg_get_serial_sequence(?, ?), "
                + "GREATEST((SELECT COALESCE(MAX(" + pk + "), 0) FROM " + table + "), 1)) "
                + "WHERE pg_get_serial_sequence(?, ?) IS NOT NULL";
        try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, pk);
            statement.setString(3, table);
            statement.setString(4, pk);
            statement.execute();
        } catch (Exception e) {
            log.warn("identity resync skipped for {}.{}: {}", table, pk, e.getMessage());
        }
    }

    /** BODY param 필드만 남긴 ObjectNode (path/query 필드 제거). */
    static JsonNode bodyOnly(Endpoint endpoint, JsonNode input) {
        if (!(input instanceof ObjectNode objectNode)) {
            return input;
        }
        Set<String> nonBody = new HashSet<>();
        for (EndpointParam param : endpoint.params()) {
            if (param.kind() != ParamKind.BODY) {
                nonBody.add(param.name());
            }
        }
        ObjectNode body = objectNode.deepCopy();
        nonBody.forEach(body::remove);
        return body;
    }

    /** 평면 ObjectNode → application/x-www-form-urlencoded (field=urlencode(value)&...). 스칼라 필드만. */
    static String formEncode(JsonNode body) {
        if (!(body instanceof ObjectNode obj)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        java.util.Iterator<String> names = obj.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            JsonNode v = obj.get(name);
            if (v == null || v.isNull() || v.isContainerNode()) {
                // null/중첩은 폼 필드로 안 보냄(평면 스칼라만, multipart/중첩 폼은 비범위).
                // 묵시적 손실을 관측 가능하게 — 폼 arm이 바인딩 실패하면 이 로그로 추적.
                if (v != null && v.isContainerNode()) {
                    log.debug("formEncode: dropping non-scalar form field '{}' (nested/multipart out of scope)", name);
                }
                continue;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8))
              .append('=')
              .append(java.net.URLEncoder.encode(v.asText(), java.nio.charset.StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private List<CapturedSql> captureSql(PathCandidate candidate) {
        return captureSqlForRange(candidate.pathId(), candidate.body(),
                candidate.logStart(), candidate.logEnd());
    }

    /** path의 도달 분기 라인과 겹치는 handler 분기 조건을 제약으로 첨부 (1.2). */
    private List<String> matchConstraints(PathCandidate candidate,
                                          List<ConstraintExtractor.ConditionSpan> conditions,
                                          Endpoint endpoint) {
        Set<Integer> handlerLines = new HashSet<>();
        for (BranchRef branch : candidate.branches()) {
            if (branch.classFqn().equals(endpoint.handlerClass())) {
                handlerLines.add(branch.line());
            }
        }
        Set<String> matched = new LinkedHashSet<>();
        for (ConstraintExtractor.ConditionSpan span : conditions) {
            for (int line = span.startLine(); line <= span.endLine(); line++) {
                if (handlerLines.contains(line)) {
                    matched.add(span.text());
                    break;
                }
            }
        }
        return List.copyOf(matched);
    }

    /** 경량 self-check (docs/05): placeholder 수와 바인딩 수 일치 확인. */
    private List<String> validate(List<CapturedSql> sql) {
        List<String> warnings = new ArrayList<>();
        for (CapturedSql statement : sql) {
            long placeholders = statement.normalizedSql().chars().filter(c -> c == '?').count();
            if (placeholders != statement.bindings().size()) {
                warnings.add("binding count mismatch in " + statement.id()
                        + ": placeholders=" + placeholders
                        + " bindings=" + statement.bindings().size());
            }
        }
        return warnings;
    }

    private ExplorationReport.EndpointExploration report(Endpoint endpoint,
                                                         ExplorationOutcome outcome,
                                                         List<ConstraintExtractor.Comparison> comparisons) {
        // 누적 exec data 분석 → arm-level 정확 커버리지 (양쪽 arm 합산).
        // 리포트 범위는 handler 메서드의 분기 (형제 메서드 분기 희석 방지).
        BranchCoverage all = analyzer.analyze(cumulativeCoverage);
        List<BranchRef> covered = all.covered().stream()
                .filter(b -> b.classFqn().equals(endpoint.handlerClass())
                        && b.method().equals(endpoint.handlerMethod()))
                .toList();
        List<BranchRef> missed = all.missed().stream()
                .filter(b -> b.classFqn().equals(endpoint.handlerClass())
                        && b.method().equals(endpoint.handlerMethod()))
                .toList();
        int total = covered.size() + missed.size();
        // 권고 1: 미커버 분기 중 비교식(field op literal) 라인과 겹치는 것 = 솔버가 필요할 잔여.
        // 비교식은 전 계층 전역 추출이므로, handler-method 분기와 라인 매칭하려면 같은
        // 클래스·메서드의 비교식만 본다(다른 파일의 동일 라인 번호 오탐 방지).
        Set<Integer> comparisonLines = comparisons.stream()
                .filter(c -> c.classFqn().equals(endpoint.handlerClass())
                        && c.method().equals(endpoint.handlerMethod()))
                .map(ConstraintExtractor.Comparison::line).collect(Collectors.toSet());
        int solverRelevantMissed = (int) missed.stream()
                .filter(b -> comparisonLines.contains(b.line())).count();
        return new ExplorationReport.EndpointExploration(
                endpoint.id(), total, covered.size(), missed,
                outcome.pathsByEngine(), solverRelevantMissed);
    }

    private static Set<String> bodyValues(JsonNode body) {
        Set<String> values = new HashSet<>();
        body.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isNull()) {
                values.add(entry.getValue().asText());
            }
        });
        return values;
    }

    private static JsonNode parseJsonOrNull(String body) {
        try {
            return Json.mapper().readTree(body);
        } catch (UncheckedIOException | java.io.IOException e) {
            return Json.mapper().nullNode();
        }
    }
}
