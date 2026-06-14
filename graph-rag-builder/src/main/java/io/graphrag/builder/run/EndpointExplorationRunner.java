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
import io.graphrag.builder.env.SutProcess;
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

    public record EndpointResult(List<ExploredPath> paths, List<CapturedSql> sql,
                                 List<io.graphrag.model.CapturedHttpCall> httpCalls,
                                 List<RequiredSeed> seeds,
                                 ExplorationReport.EndpointExploration report,
                                 Set<BranchRef> coveredAppBranches) {
    }

    private final SutProcess sut;
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
    // 요청별 dump(reset)을 누적 병합 → arm-level 정확 커버리지. 분기 양쪽(true/false)이
    // 서로 다른 요청에서 찍혀도 probe OR로 합산된다 (count-union 모델의 arm-blind 한계 보완).
    private ExecutionDataStore cumulativeCoverage = new ExecutionDataStore();
    private Set<String> appClasses = Set.of();   // path 지문을 SUT 자체 클래스로 한정

    public EndpointExplorationRunner(SutProcess sut, Connection connection,
                                     DbConfig.Type dbType,
                                     CoverageClient coverage, BranchCoverageAnalyzer analyzer,
                                     int budgetRequests,
                                     io.graphrag.builder.env.HttpCaptureServer httpCapture,
                                     List<Set<String>> responseDtoFieldSets,
                                     List<String> literalCandidates,
                                     AuthTokenProvider authProvider,
                                     AuthConfig authConfig,
                                     Map<String, List<String>> enumConstants,
                                     Map<String, List<String>> enumColumns) {
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
    }

    public EndpointResult run(Endpoint endpoint, BodyShape shape, List<TableSchema> tables,
                              List<ConstraintExtractor.ConditionSpan> conditions,
                              List<ConstraintExtractor.Comparison> comparisons,
                              InputCandidates candidates,
                              Map<String, List<FieldConstraint>> fieldConstraints,
                              List<ConstraintExtractor.Conjunction> conjunctions) throws Exception {
        cumulativeCoverage = new ExecutionDataStore();   // 엔드포인트마다 초기화
        if (appClasses.isEmpty()) {
            appClasses = analyzer.appClassNames();
        }
        boolean readPath = endpoint.httpMethod().equals("GET");
        boolean hasPathParam = endpoint.params().stream().anyMatch(p -> p.kind() == ParamKind.PATH);
        // GET뿐 아니라 비-GET by-id(PUT/DELETE /{id})도 리소스를 미리 시드하고, 생성 테스트가
        // 그 리소스를 재현하도록 requiredSeeds에 등록해야 빈 DB에서도 통과한다(Bug: 비-GET 시드 미재현).
        boolean seedResource = readPath || hasPathParam;
        SynthesizedInput happy = happyInput(endpoint, shape, tables, enumConstants, enumColumns);

        List<RequiredSeed> requiredSeeds = new ArrayList<>();
        int seedSeq = 0;
        for (SynthesizedInput.SeedRow seed : happy.seeds()) {
            Seeds.insert(connection, dbType, seed);
            if (seedResource) {
                // read-path seed는 IDENTITY PK에 명시 id를 넣는다. 이러면 시퀀스가
                // 전진하지 않아 이후 POST 탐색의 auto-INSERT가 같은 id로 충돌(500)한다.
                // 같은 공유 탐색 DB이므로 seed 직후 시퀀스를 재동기화한다.
                resyncIdentitySequence(seed.table(), tables);
                seedSeq++;
                requiredSeeds.add(new RequiredSeed(
                        "seed-" + endpoint.id() + "-" + seedSeq, null, seed.table(),
                        seed.columns(), seed.values().stream().map(String::valueOf).toList()));
            }
        }

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
        ExplorationOrchestrator orchestrator = new ExplorationOrchestrator(
                List.of(new HeuristicExplorer(), new CoverageGuidedFuzzer(FUZZER_SATURATION)),
                budgetRequests);
        EndpointTarget target = new EndpointTarget(endpoint, baseInput, mutableFields, tables,
                httpInvoker(endpoint), literalCandidates,
                fieldConstraints, conditionBounds, stringCandidates, enumConstants, conjunctions);
        ExplorationOutcome outcome = orchestrator.explore(target);
        log.info("explored {}: {} path(s), {} branch(es) covered",
                endpoint.id(), outcome.paths().size(), outcome.coveredBranches().size());

        List<ExploredPath> paths = new ArrayList<>();
        List<CapturedSql> allSql = new ArrayList<>();
        List<io.graphrag.model.CapturedHttpCall> allHttpCalls = new ArrayList<>();
        for (PathCandidate candidate : outcome.paths()) {
            List<CapturedSql> sql = captureSql(candidate);
            allSql.addAll(sql);
            List<io.graphrag.model.CapturedHttpCall> httpCalls = captureHttpCalls(candidate);
            allHttpCalls.addAll(httpCalls);
            // seed는 성공(2xx) path에만 연결 — 아래 후처리 블록에서 채운다
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

        if (seedResource && !requiredSeeds.isEmpty()) {
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
            } else {
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
                requiredSeeds = perPath;
            }
        }

        // app 집계도 누적 exec data 기준(arm-level) — 전 엔드포인트 합집합이 정확해진다.
        Set<BranchRef> appCovered = analyzer.analyze(cumulativeCoverage).covered();
        return new EndpointResult(paths, allSql, allHttpCalls, requiredSeeds,
                report(endpoint, outcome, comparisons), appCovered);
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
                long logStart = sut.logOffset();
                String url = sut.baseUri() + buildPathAndQuery(endpoint, input);
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        // propagation 실측용 (docs/06): outbound로 복사되는지 관찰
                        .header("baggage", "test-id=explore");
                if (authProvider != null && endpoint.authRequired()) {
                    builder.header(authConfig.headerName(),
                            authConfig.headerValue(authProvider.token()));
                }
                String method = endpoint.httpMethod();
                if (method.equals("GET") || method.equals("DELETE")) {
                    builder.method(method, HttpRequest.BodyPublishers.noBody());
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
            } catch (Exception e) {
                throw new IllegalStateException("invocation failed: " + endpoint.path(), e);
            }
        };
    }

    /**
     * happy 입력 합성. GET 또는 비-GET by-id(PATH 파라미터 보유)는 ReadInputSynthesizer로 path/query +
     * 리소스 시드(유효 id)를 만든다(Bug 1: PUT/DELETE {id}가 sentinel "0"이 되어 service 미진입하던 것 해결).
     * 비-GET+PATH+body면 body(SampleInputSynthesizer)를 병합(seed는 table+pk로 dedupe, path/query 우선).
     */
    static SynthesizedInput happyInput(Endpoint endpoint, BodyShape shape, List<TableSchema> tables,
                                       Map<String, List<String>> enumConstants,
                                       Map<String, List<String>> enumColumns) {
        boolean get = endpoint.httpMethod().equals("GET");
        boolean hasPath = endpoint.params().stream().anyMatch(p -> p.kind() == ParamKind.PATH);
        if (get || hasPath) {
            SynthesizedInput pathPart =
                    new ReadInputSynthesizer(enumConstants, enumColumns).synthesize(endpoint, tables);
            if (get || shape == null) {
                return pathPart;
            }
            SynthesizedInput bodyPart = new SampleInputSynthesizer(enumConstants).synthesize(shape, tables);
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
                : new SampleInputSynthesizer(enumConstants).synthesize(shape, tables);
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

    private List<CapturedSql> captureSql(PathCandidate candidate) {
        List<ParsedSql> parsed = SqlLogParser.parse(
                sut.readLogRange(candidate.logStart(), candidate.logEnd()));
        Set<String> apiValues = bodyValues(candidate.body());
        List<CapturedSql> captured = new ArrayList<>();
        int sequence = 0;
        for (ParsedSql statement : parsed) {
            sequence++;
            List<SqlBinding> bindings = new ArrayList<>();
            for (ParsedSql.Binding binding : statement.bindings()) {
                bindings.add(new SqlBinding(
                        binding.position(),
                        statement.columnForPosition(binding.position()),
                        binding.value(),
                        apiValues.contains(binding.value())
                                ? BindingOrigin.API_PARAM
                                : BindingOrigin.LITERAL,
                        statement.bindingTableForPosition(binding.position())));
            }
            captured.add(new CapturedSql(
                    "sql-" + candidate.pathId() + "-" + sequence,
                    candidate.pathId(),
                    statement.kind(), statement.sql(), statement.tableName(), bindings));
        }
        return captured;
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
