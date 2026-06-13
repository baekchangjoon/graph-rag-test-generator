package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.capture.ParsedSql;
import io.graphrag.builder.capture.SqlLogParser;
import io.graphrag.builder.coverage.BranchCoverage;
import io.graphrag.builder.coverage.BranchCoverageAnalyzer;
import io.graphrag.builder.coverage.CoverageClient;
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
import java.util.Set;

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
                                 ExplorationReport.EndpointExploration report) {
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

    public EndpointExplorationRunner(SutProcess sut, Connection connection,
                                     DbConfig.Type dbType,
                                     CoverageClient coverage, BranchCoverageAnalyzer analyzer,
                                     int budgetRequests,
                                     io.graphrag.builder.env.HttpCaptureServer httpCapture,
                                     List<Set<String>> responseDtoFieldSets,
                                     List<String> literalCandidates,
                                     AuthTokenProvider authProvider,
                                     AuthConfig authConfig) {
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
    }

    public EndpointResult run(Endpoint endpoint, BodyShape shape, List<TableSchema> tables,
                              List<ConstraintExtractor.ConditionSpan> conditions) throws Exception {
        boolean readPath = endpoint.httpMethod().equals("GET");
        SynthesizedInput happy = readPath
                ? new ReadInputSynthesizer().synthesize(endpoint, tables)
                : new SampleInputSynthesizer().synthesize(shape, tables);

        List<RequiredSeed> requiredSeeds = new ArrayList<>();
        int seedSeq = 0;
        for (SynthesizedInput.SeedRow seed : happy.seeds()) {
            Seeds.insert(connection, dbType, seed);
            if (readPath) {
                seedSeq++;
                requiredSeeds.add(new RequiredSeed(
                        "seed-" + endpoint.id() + "-" + seedSeq, null, seed.table(),
                        seed.columns(), seed.values().stream().map(String::valueOf).toList()));
            }
        }

        coverage.dump(true);   // 부팅/seed 구간을 잘라내고 baseline 확보

        ExplorationOrchestrator orchestrator = new ExplorationOrchestrator(
                List.of(new HeuristicExplorer(), new CoverageGuidedFuzzer(FUZZER_SATURATION)),
                budgetRequests);
        ExplorationOutcome outcome = orchestrator.explore(
                new EndpointTarget(endpoint, shape, tables, httpInvoker(endpoint), literalCandidates));
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

        // read-path: seed는 첫 번째 2xx path의 사전 조건이다.
        // 비-2xx path(404/400 등)는 seed와 무관하므로 requiredSeedIds를 비워 둔다.
        if (readPath && !requiredSeeds.isEmpty()) {
            int successIdx = -1;
            for (int i = 0; i < paths.size(); i++) {
                if (paths.get(i).expectedStatus() / 100 == 2) { successIdx = i; break; }
            }
            if (successIdx >= 0) {
                ExploredPath p = paths.get(successIdx);
                List<String> seedIds = requiredSeeds.stream().map(RequiredSeed::id).toList();
                paths.set(successIdx, new ExploredPath(p.id(), p.endpointId(), p.sampleInput(),
                        p.expectedStatus(), p.sampleResponse(), p.capturedSqlIds(),
                        p.capturedHttpCallIds(), p.branchesTaken(), p.discoveredBy(),
                        p.constraints(), p.validationWarnings(), seedIds));
                String pid = p.id();
                requiredSeeds = requiredSeeds.stream()
                        .map(s -> new RequiredSeed(s.id(), pid, s.table(), s.columns(), s.values()))
                        .toList();
            }
            // 2xx path가 없으면(degenerate) seed는 어떤 path에도 연결하지 않는다
        }

        return new EndpointResult(paths, allSql, allHttpCalls, requiredSeeds, report(endpoint, outcome));
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
                BranchCoverage requestCoverage = analyzer.analyze(coverage.dump(true));
                long logEnd = sut.logOffset();
                return new InvocationOutcome(response.statusCode(),
                        parseJsonOrNull(response.body()),
                        requestCoverage.covered(), logStart, logEnd,
                        httpCapture == null ? List.of() : httpCapture.drainNewExchanges());
            } catch (Exception e) {
                throw new IllegalStateException("invocation failed: " + endpoint.path(), e);
            }
        };
    }

    /** PATH param은 {name} 치환, QUERY param은 쿼리스트링으로. */
    static String buildPathAndQuery(Endpoint endpoint, JsonNode input) {
        String path = endpoint.path();
        StringBuilder query = new StringBuilder();
        for (EndpointParam param : endpoint.params()) {
            if (!input.has(param.name())) continue;
            String value = input.get(param.name()).asText();
            if (param.kind() == ParamKind.PATH) {
                path = path.replace("{" + param.name() + "}", value);
            } else if (param.kind() == ParamKind.QUERY) {
                query.append(query.isEmpty() ? "?" : "&")
                        .append(param.name()).append("=").append(value);
            }
        }
        return path + query;
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
                                                         ExplorationOutcome outcome) {
        // 리포트 범위는 handler 클래스의 분기 (docs/22의 endpoint 귀속 기준)
        BranchCoverage all = analyzer.analyze(new org.jacoco.core.data.ExecutionDataStore());
        List<BranchRef> handlerAll = all.missed().stream()
                .filter(b -> b.classFqn().equals(endpoint.handlerClass()))
                .toList();
        Set<BranchRef> covered = outcome.coveredBranches();
        List<BranchRef> missed = handlerAll.stream()
                .filter(b -> !covered.contains(b))
                .toList();
        int coveredCount = (int) handlerAll.stream().filter(covered::contains).count();
        return new ExplorationReport.EndpointExploration(
                endpoint.id(), handlerAll.size(), coveredCount, missed, outcome.pathsByEngine());
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
