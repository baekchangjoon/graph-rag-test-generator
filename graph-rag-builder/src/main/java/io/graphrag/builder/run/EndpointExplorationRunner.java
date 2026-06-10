package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.builder.capture.ParsedSql;
import io.graphrag.builder.capture.SqlLogParser;
import io.graphrag.builder.coverage.BranchCoverage;
import io.graphrag.builder.coverage.BranchCoverageAnalyzer;
import io.graphrag.builder.coverage.CoverageClient;
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
import io.graphrag.model.ExplorationReport;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.Json;
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
import java.sql.PreparedStatement;
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
                                 ExplorationReport.EndpointExploration report) {
    }

    private final SutProcess sut;
    private final Connection connection;
    private final CoverageClient coverage;
    private final BranchCoverageAnalyzer analyzer;
    private final int budgetRequests;
    private final io.graphrag.builder.env.HttpCaptureServer httpCapture;
    private final List<Set<String>> responseDtoFieldSets;
    private final List<String> literalCandidates;

    public EndpointExplorationRunner(SutProcess sut, Connection connection,
                                     CoverageClient coverage, BranchCoverageAnalyzer analyzer,
                                     int budgetRequests,
                                     io.graphrag.builder.env.HttpCaptureServer httpCapture,
                                     List<Set<String>> responseDtoFieldSets,
                                     List<String> literalCandidates) {
        this.sut = sut;
        this.connection = connection;
        this.coverage = coverage;
        this.analyzer = analyzer;
        this.budgetRequests = budgetRequests;
        this.httpCapture = httpCapture;
        this.responseDtoFieldSets = responseDtoFieldSets;
        this.literalCandidates = literalCandidates;
    }

    public EndpointResult run(Endpoint endpoint, BodyShape shape, List<TableSchema> tables,
                              List<ConstraintExtractor.ConditionSpan> conditions) throws Exception {
        SynthesizedInput happy = new SampleInputSynthesizer().synthesize(shape, tables);
        for (SynthesizedInput.SeedRow seed : happy.seeds()) {
            insertSeed(connection, seed);
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
                    validate(sql)));
        }
        return new EndpointResult(paths, allSql, allHttpCalls, report(endpoint, outcome));
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
        return body -> {
            try {
                long logStart = sut.logOffset();
                HttpResponse<String> response = http.send(
                        HttpRequest.newBuilder(URI.create(sut.baseUri() + endpoint.path()))
                                .timeout(Duration.ofSeconds(30))
                                .header("Content-Type", "application/json")
                                // propagation 실측용 (docs/06): outbound로 복사되는지 관찰
                                .header("baggage", "test-id=explore")
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        Json.mapper().writeValueAsString(body)))
                                .build(),
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
                                : BindingOrigin.LITERAL));
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

    private static void insertSeed(Connection connection, SynthesizedInput.SeedRow seed)
            throws Exception {
        String placeholders = String.join(", ", seed.columns().stream().map(c -> "?").toList());
        // 여러 endpoint가 같은 probe row를 공유할 수 있다 → 멱등 INSERT
        String sql = "INSERT INTO " + seed.table() + " (" + String.join(", ", seed.columns())
                + ") VALUES (" + placeholders + ") ON CONFLICT DO NOTHING";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < seed.values().size(); i++) {
                statement.setObject(i + 1, seed.values().get(i));
            }
            statement.executeUpdate();
        }
        log.info("seeded: {} {}", seed.table(), seed.values());
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
