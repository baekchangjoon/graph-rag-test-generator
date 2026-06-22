package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.capture.ParsedSql;
import io.graphrag.builder.capture.SqlCaptureBackend;
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
import io.graphrag.builder.index.FormFieldBinding;
import io.graphrag.builder.index.ValidationConstraintExtractor.FieldConstraint;
import io.graphrag.builder.index.ValidationConstraintExtractor.Kind;
import io.graphrag.builder.oracle.InputCandidates;
import io.graphrag.builder.oracle.ResponseClassifier;
import io.graphrag.builder.oracle.StatusOnlyClassifier;
import io.graphrag.model.BindingOrigin;
import io.graphrag.model.BranchRef;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.ColumnSchema;
import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ForeignKey;
import io.graphrag.model.ExplorationReport;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.Json;
import io.graphrag.model.Outcome;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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

    /** trace-mode가 주입하는 상관 헤더 이름들(case-insensitive). backend 값이 사용자 값을 이긴다. */
    private static final java.util.Set<String> CORRELATION_HEADERS = java.util.Set.of(
            "traceparent", "x-b3-traceid", "x-b3-spanid", "x-b3-sampled", "b3");

    /**
     * 응답 헤더 캡처 차단 목록(case-insensitive). hop-by-hop + 표준 HTTP 헤더 + 불안정·유니버셜 헤더.
     * 이 목록에 없는 헤더(예: X-Downstream)는 커스텀 헤더로 간주해 ExploredPath에 저장된다.
     */
    private static final java.util.Set<String> RESPONSE_HEADER_DENYLIST = java.util.Set.of(
            "content-length", "transfer-encoding", "connection", "keep-alive", "upgrade",
            "te", "trailer", "proxy-authorization", "proxy-authenticate",
            "date", "server", "content-encoding", "vary", "cache-control", "pragma", "expires",
            "content-type", "content-language", "host", "accept-ranges",
            // 민감 헤더(보안 리뷰): 값은 어차피 저장하지 않지만(아래 ""), 이름조차 graph.json에 남기지 않는다.
            "set-cookie", "set-cookie2", "authorization", "www-authenticate",
            "x-csrf-token", "x-xsrf-token", "x-auth-token", "cookie", "x-api-key");

    /** 사용자 헤더에서 상관 헤더를 case-insensitive 제거 후 scope 상관 헤더를 덮어쓴다. */
    static java.util.LinkedHashMap<String, String> applyCorrelationPriority(
            Map<String, String> userHeaders, Map<String, String> scopeHeaders) {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> h : userHeaders.entrySet()) {
            if (!CORRELATION_HEADERS.contains(h.getKey().toLowerCase(java.util.Locale.ROOT))) {
                out.put(h.getKey(), h.getValue());
            }
        }
        out.putAll(scopeHeaders);
        return out;
    }

    private static final int FUZZER_SATURATION = 2;   // 연속 dry 시드 패스 수
    private static final int VARIANT_CAP = 4;          // 엔드포인트당 negative-validation 변종 상한(ReadInputSynthesizer와 일치)
    /** RC-B: GET-by-id happy가 outcome=FAILURE(엔벨로프-200 포함)일 때 pass-2 재시드를 추가로 시도하는 예산. */
    static final int RCB_RETRY_BUDGET = 4;

    /**
     * RC-B 재시도 결정 술어. pass-2 재탐색 결과 번들에 SUCCESS path가 하나도 없고(=happy가 여전히
     * FAILURE: 진짜 4xx 또는 엔벨로프-200) 이미 실행한 pass-2 시도 횟수가 예산 이하이면 또 시도한다.
     * SUCCESS path가 하나라도 있으면 즉시 중단(추가 시도 0 — happy가 이미 SUCCESS면 호출조차 안 됨).
     */
    static boolean shouldRetryPass2(List<ExploredPath> paths, int attemptsSoFar) {
        if (attemptsSoFar > RCB_RETRY_BUDGET) {
            return false;
        }
        return paths.stream().noneMatch(p -> p.outcome() == Outcome.Kind.SUCCESS);
    }

    public record EndpointResult(List<ExploredPath> paths, List<CapturedSql> sql,
                                 List<io.graphrag.model.CapturedHttpCall> httpCalls,
                                 List<RequiredSeed> seeds,
                                 ExplorationReport.EndpointExploration report,
                                 ExecutionDataStore cumulativeExec,
                                 List<io.graphrag.model.CapturedEventEmit> capturedEventEmits) {
    }

    /**
     * REQ-013: non-2xx path의 재현 가능 여부를 판별하는 전략 인터페이스.
     * 실 구현: HTTP clean-replay (httpInvokerForRepro). 테스트: 스텁(mock status).
     * 예외를 던지면 conservative KEEP(억제하지 않음).
     */
    @FunctionalInterface
    public interface ReproVerifier {
        /**
         * path를 클린 DB + 선언 시드 상태에서 재실행하고 HTTP 상태 코드를 반환한다.
         * @throws Exception 재실행 불가(연결 거부, 타임아웃 등)
         */
        int replay(Endpoint endpoint, ExploredPath path, List<RequiredSeed> requiredSeeds)
                throws Exception;
    }

    /** verifyAndFilterNonTwoxx 의 결과: 유지된 경로 + 억제된 경로 기록. */
    public record FilterResult(List<ExploredPath> kept,
                               List<ExplorationReport.DroppedPath> dropped) {
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
    private final SqlCaptureBackend sqlCapture;             // 요청별 SQL 캡처 backend (log 폴백 / OTEL)
    private final KafkaCaptureReceiver kafkaCapture;
    private final ResponseClassifier classifier;   // 성공/실패 판정(기본 StatusOnlyClassifier)
    // 요청별 dump(reset)을 누적 병합 → arm-level 정확 커버리지. 분기 양쪽(true/false)이
    // 서로 다른 요청에서 찍혀도 probe OR로 합산된다 (count-union 모델의 arm-blind 한계 보완).
    private ExecutionDataStore cumulativeCoverage = new ExecutionDataStore();
    private Set<String> appClasses = Set.of();   // path 지문을 SUT 자체 클래스로 한정

    /** classifier 생략 호환 생성자 — 기본 {@link StatusOnlyClassifier} (status/100==2 → 성공). */
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
                                     RequestHeaders extraHeaders,
                                     SqlCaptureBackend sqlCapture,
                                     KafkaCaptureReceiver kafkaCapture) {
        this(sut, connection, dbType, coverage, analyzer, budgetRequests, httpCapture,
                responseDtoFieldSets, literalCandidates, authProvider, authConfig,
                enumConstants, enumColumns, extraHeaders, sqlCapture, kafkaCapture,
                new StatusOnlyClassifier());
    }

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
                                     RequestHeaders extraHeaders,
                                     SqlCaptureBackend sqlCapture,
                                     KafkaCaptureReceiver kafkaCapture,
                                     ResponseClassifier classifier) {
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
        this.sqlCapture = sqlCapture;
        this.kafkaCapture = kafkaCapture;
        this.classifier = classifier == null ? new StatusOnlyClassifier() : classifier;
    }

    public EndpointResult run(Endpoint endpoint, BodyShape shape, List<TableSchema> tables,
                              List<ConstraintExtractor.ConditionSpan> conditions,
                              List<ConstraintExtractor.Comparison> comparisons,
                              InputCandidates candidates,
                              Map<String, List<FieldConstraint>> fieldConstraints,
                              List<ConstraintExtractor.Conjunction> conjunctions,
                              List<ConstraintExtractor.StateGuard> stateGuards,
                              boolean validBody,
                              Map<String, BodyShape> shapesByType,
                              List<FormFieldBinding> formBindings) throws Exception {
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
        // 폼 참조 필드: 백업 행 SELECT/seed → name 1순위 후보로 reference-aware happy base 합성(Phase 3).
        Map<String, RefCandidate> formRefCandidates = resolveFormRefCandidates(formBindings, tables);
        Map<String, String> nameRefValues = nameTokens(formRefCandidates);
        SynthesizedInput happy = happyInput(endpoint, shape, tables, enumConstants, enumColumns, happyConstraints,
                null, shapesByType, formBindings, nameRefValues);

        List<RequiredSeed> requiredSeeds = insertSeeds(happy, endpoint, seedResource, tables);

        coverage.dump(true);   // 부팅/seed 구간을 잘라내고 baseline 확보

        JsonNode baseInput = happy.body();
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
        Map<String, Set<Double>> realBounds = candidates.reals();         // float/double 단일필드 경계(작업 #4)
        List<Map<String, Double>> realInterFieldTuples = candidates.realTuples();   // float inter-field 튜플
        ExplorationOrchestrator orchestrator = new ExplorationOrchestrator(
                List.of(new HeuristicExplorer(classifier), new CoverageGuidedFuzzer(FUZZER_SATURATION, classifier)),
                budgetRequests, classifier);
        EndpointInvoker invoker = buildInvoker(endpoint, readPath, hasPathParam, happy);
        EndpointTarget target = new EndpointTarget(endpoint, baseInput, mutableFields, tables,
                invoker, literalCandidates,
                fieldConstraints, conditionBounds, stringCandidates, enumConstants, conjunctions,
                interFieldTuples, realBounds, realInterFieldTuples);
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
                    // RC-B(REQ-009): pass-2 재시드+재탐색을 예산 내 루프로 감싼다. happy가 여전히
                    // FAILURE(엔벨로프-200 포함)면 최신 캡처 SQL로 hint를 재해석해 다시 시드한다.
                    // SUCCESS path 도달 즉시 중단. budget 소진 시 마지막 결과를 best-effort로 수용.
                    SynthesizedInput prevHappy = pass1Happy;   // 현재 삽입된(=지울) 시드
                    ResolutionHint attemptHint = hint;
                    int attempt = 0;
                    do {
                        deleteSeeds(prevHappy);
                        SynthesizedInput happy2 = happyInput(endpoint, shape, tables,
                                enumConstants, enumColumns, happyConstraints, attemptHint,
                                shapesByType, formBindings, nameRefValues);
                        requiredSeeds = insertSeeds(happy2, endpoint, seedResource, tables);
                        coverage.dump(true);                          // baseline: 부팅+이전 구간 컷
                        cumulativeCoverage = new ExecutionDataStore(); // 리포트를 마지막 pass-2 run만 반영
                        EndpointInvoker invoker2 = buildInvoker(endpoint, readPath, hasPathParam, happy2);
                        EndpointTarget target2 = new EndpointTarget(endpoint, happy2.body(), mutableFields,
                                tables, invoker2, literalCandidates,
                                fieldConstraints, conditionBounds, stringCandidates, enumConstants, conjunctions,
                                interFieldTuples, realBounds, realInterFieldTuples);
                        outcome = orchestrator.explore(target2);
                        bundle = buildPaths(outcome, endpoint, conditions);
                        happy = happy2;
                        prevHappy = happy2;
                        attempt++;
                        log.info("re-explored {} (SQL hint table={}, attempt {}): {} path(s)",
                                endpoint.id(), attemptHint.table(), attempt, outcome.paths().size());
                        if (!shouldRetryPass2(bundle.paths(), attempt)) {
                            break;
                        }
                        // 여전히 FAILURE: 최신 SQL로 hint 재해석(시드된 테이블이 바뀔 수 있음). null이면 중단.
                        ResolutionHint next = SqlSeedResolver.resolve(bundle.allSql(),
                                sentParamValues(endpoint, happy2.body()), endpoint, tables);
                        if (next == null || next.table() == null) {
                            break;
                        }
                        attemptHint = next;
                    } while (true);
                } catch (Exception e) {
                    log.warn("SQL-driven re-seed failed for {} (table={}), keeping pass-1: {}",
                            endpoint.id(), hint.table(), e.getMessage());
                    requiredSeeds = pass1Seeds;
                    bundle = pass1Bundle;
                    cumulativeCoverage = pass1Cumulative;
                    outcome = pass1Outcome;
                    happy = pass1Happy;
                    reinsertSeeds(pass1Happy);
                }
            }
        }

        // REQ-012: Kafka 2회 발행 diff — 비-패턴 서버 생성 필드 검출.
        // 탐색 완료(final bundle 확정) 후, 동일 happy 입력으로 2차 발행을 재현하고 두 payload를
        // field-by-field diff해 비결정 값을 CapturedEventEmit.nonDeterministicValues에 기록한다.
        // GRB_KAFKA_DIFF=off 또는 kafkaCapture 없음이면 skip.
        final SynthesizedInput happyForDiff = happy;
        if (kafkaCapture != null
                && !"off".equalsIgnoreCase(System.getenv("GRB_KAFKA_DIFF"))) {
            bundle = enrichWithKafkaDiff(bundle, outcome, endpoint, readPath, happyForDiff, tables);
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
        if (validBody && baseInput instanceof ObjectNode ob && shape != null && !shape.collection()
                && !"off".equalsIgnoreCase(System.getenv("GRB_NEGATIVE_VALIDATION"))) {
            finalPaths.addAll(exploreNegativeValidationVariants(endpoint, shape, fieldConstraints, ob));
        }

        // 폼 참조-id backtrack 패스(Phase 3): name 1순위로 안 열리는 참조 필드(예: Converter<String,E> PK 조회)를
        // 필드별 PK 후보로 재발행 → bound arm 커버. discoveredBy 마커로 생성 제외, POST라 repro-verify 대상 외.
        if (!formRefCandidates.isEmpty() && shape != null) {
            finalPaths.addAll(exploreFormReferenceTrials(endpoint, shape, tables, formBindings,
                    happyConstraints, shapesByType, formRefCandidates, nameRefValues));
        }

        // REQ-013/014/015: non-2xx path 재현 검증.
        // GET 경로에 대해 클린 DB + 선언 시드 상태로 재실행하고 상태 코드가 일치하는지 확인한다.
        // 불일치(오염된 DB로 인한 500 등) → 억제(drop) + 로그 + DroppedPath 기록.
        // non-GET mutating, negative 마커, 2xx path는 검증 범위 외(conservative KEEP).
        // GRB_REPRO_VERIFY=off면 검증 단계 완전 skip(ablation/회귀 제어용).
        // happy는 이 시점에서 최종 확정된 SynthesizedInput(pass-2 완료 후 또는 pass-1 유지).
        final SynthesizedInput happyFinal = happy;
        final HttpClient reproHttp = HttpClient.newHttpClient();
        final String authHeaderForRepro = (authProvider != null && endpoint.authRequired())
                ? authConfig.headerValue(authProvider.token()) : null;
        final Map<String, RequiredSeed> seedById = finalSeeds.stream()
                .collect(java.util.stream.Collectors.toMap(RequiredSeed::id, s -> s,
                        (a, b) -> a, java.util.LinkedHashMap::new));

        ReproVerifier httpReproVerifier = (ep, path, declaredSeeds) -> {
            // 1. 탐색 중 쌓인 happy 시드를 제거(역순 child→parent)
            deleteSeeds(happyFinal);
            try {
                // 2. 이 path의 선언 시드만 재삽입하고, 삽입된 행을 추적한다.
                List<SynthesizedInput.SeedRow> insertedRows = new ArrayList<>();
                for (RequiredSeed rs : declaredSeeds) {
                    SynthesizedInput.SeedRow row = new SynthesizedInput.SeedRow(
                            rs.table(), rs.columns(),
                            rs.values().stream().map(v -> (Object) v).toList());
                    Seeds.insert(connection, dbType, row);
                    insertedRows.add(row);
                }
                // 3+4. 재실행 후 path 시드 정리 — doSend 예외 시에도 항상 삭제(누수 방지).
                try {
                    return doSend(reproHttp, ep, path.sampleInput(), authHeaderForRepro).status();
                } finally {
                    // 4. 재삽입한 path 시드 정리(역순 child→parent)
                    for (int i = insertedRows.size() - 1; i >= 0; i--) {
                        Seeds.delete(connection, insertedRows.get(i));
                    }
                }
            } finally {
                // 5. happy 시드 복원(best-effort; 이후 탐색 없지만 DB 상태 일관성 유지)
                reinsertSeeds(happyFinal);
            }
        };

        boolean skipReproVerify = "off".equalsIgnoreCase(System.getenv("GRB_REPRO_VERIFY"));
        List<ExplorationReport.DroppedPath> drops;
        if (skipReproVerify) {
            drops = List.of();
        } else {
            // declaredSeeds per path: path.requiredSeedIds()로 조회
            FilterResult filterResult = verifyAndFilterNonTwoxx(endpoint, finalPaths, finalSeeds,
                    (ep, path, ignored) -> {
                        // path 별 선언 시드는 path.requiredSeedIds()로 직접 조회
                        List<RequiredSeed> pathSeeds = path.requiredSeedIds().stream()
                                .map(seedById::get)
                                .filter(java.util.Objects::nonNull)
                                .toList();
                        return httpReproVerifier.replay(ep, path, pathSeeds);
                    });
            finalPaths = new ArrayList<>(filterResult.kept());
            drops = filterResult.dropped();
            if (!drops.isEmpty()) {
                log.warn("endpoint {}: suppressed {} non-reproducible non-2xx path(s) — see exploration-report.json droppedPaths",
                        endpoint.id(), drops.size());
            }
        }

        // app 분기 집계는 BuilderCli가 전 루프(Kafka+HTTP+WS) 종료 후 runWideExec로 1회 산출한다.
        // 여기선 누적 exec만 넘긴다(arm-level OR 병합 근거). report()는 cumulativeCoverage 기준이므로
        // 변종 pass 이후에 호출해야 미커버 전이가 반영된다.
        return new EndpointResult(finalPaths, finalSql, bundle.httpCalls(),
                finalSeeds, report(endpoint, outcome, comparisons, drops, finalPaths), cumulativeCoverage, bundle.capturedEventEmits());
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

    // ===== 폼 참조 토큰 런타임 trial(Phase 3, spec §3.2) =====

    /** 참조 필드의 백업행 후보. */
    private record RefCandidate(String pk, String name) {
    }

    /**
     * 각 REFERENCE 폼 필드의 백업 테이블을 해석하고 행을 SELECT(없으면 default 행 seed 후 재조회)해 후보를
     * 산출한다. 후보 = {PK 값, name-류 컬럼 값}. best-effort(미해석/실패 필드는 skip → 스칼라/skip 폴백).
     */
    private Map<String, RefCandidate> resolveFormRefCandidates(List<FormFieldBinding> formBindings,
                                                               List<TableSchema> tables) {
        Map<String, RefCandidate> out = new HashMap<>();
        if (formBindings == null) {
            return out;
        }
        for (FormFieldBinding binding : formBindings) {
            if (binding.kind() != FormFieldBinding.Kind.REFERENCE) {
                continue;
            }
            String table = resolveBackupTable(binding, tables);
            if (table == null) {
                continue;
            }
            TableSchema schema = tables.stream().filter(t -> t.name().equals(table)).findFirst().orElse(null);
            if (schema == null) {
                continue;
            }
            try {
                RefCandidate candidate = selectOrSeedRefRow(schema);
                if (candidate != null) {
                    out.put(binding.field(), candidate);
                }
            } catch (Exception e) {   // best-effort: 참조 해석 실패는 회귀 아님(스칼라/skip 폴백)
                log.warn("form-ref resolve failed for field {} (table {}): {}",
                        binding.field(), table, e.getMessage());
            }
        }
        return out;
    }

    /** 후보 → name 1순위 토큰 맵(name 없으면 PK). reference-aware happy base 합성에 사용. */
    private static Map<String, String> nameTokens(Map<String, RefCandidate> candidates) {
        Map<String, String> out = new HashMap<>();
        candidates.forEach((field, c) -> out.put(field, c.name() != null ? c.name() : c.pk()));
        return out;
    }

    /**
     * 백업 테이블 해석 우선순위(결정적, spec §3.2): (1) @ManyToOne @JoinColumn FK → 스키마 부모 테이블,
     * (2) 정적 @Table(name), (3) camelToSnake(참조 엔티티 simple-name). 어느 것도 스키마에 없으면 null.
     */
    static String resolveBackupTable(FormFieldBinding binding, List<TableSchema> tables) {
        if (binding.joinColumn() != null) {
            for (TableSchema table : tables) {
                for (ForeignKey fk : table.foreignKeys()) {
                    if (fk.column().equals(binding.joinColumn())) {
                        return fk.referencedTable();
                    }
                }
            }
        }
        if (binding.refTable() != null && hasTable(tables, binding.refTable())) {
            return binding.refTable();
        }
        if (binding.refEntityFqn() != null) {
            String fqn = binding.refEntityFqn();
            String simple = fqn.substring(Math.max(fqn.lastIndexOf('.'), fqn.lastIndexOf('$')) + 1);
            String snake = SampleInputSynthesizer.camelToSnake(simple);
            if (hasTable(tables, snake)) {
                return snake;
            }
        }
        return null;
    }

    private static boolean hasTable(List<TableSchema> tables, String name) {
        return tables.stream().anyMatch(t -> t.name().equals(name));
    }

    /** 백업 테이블의 기존 행 후보 — 없으면 default 행 seed 후 재조회. PK 없는 테이블은 null. */
    private RefCandidate selectOrSeedRefRow(TableSchema schema) throws Exception {
        String pkColumn = schema.columns().stream().filter(ColumnSchema::primaryKey)
                .map(ColumnSchema::name).findFirst().orElse(null);
        if (pkColumn == null) {
            return null;
        }
        String nameColumn = pickNameColumn(schema, pkColumn);
        RefCandidate existing = querySingleRow(schema.name(), pkColumn, nameColumn);
        if (existing != null) {
            return existing;
        }
        seedDefaultRow(schema, pkColumn);
        return querySingleRow(schema.name(), pkColumn, nameColumn);
    }

    /** name-류 컬럼: "name" 우선, 없으면 첫 non-PK 문자열 컬럼(CHAR/TEXT/CLOB). 없으면 null. */
    private static String pickNameColumn(TableSchema schema, String pkColumn) {
        String firstString = null;
        for (ColumnSchema column : schema.columns()) {
            if (column.name().equals(pkColumn) || !isStringType(column.jdbcType())) {
                continue;
            }
            if (column.name().equalsIgnoreCase("name")) {
                return column.name();
            }
            if (firstString == null) {
                firstString = column.name();
            }
        }
        return firstString;
    }

    private static boolean isStringType(String jdbcType) {
        if (jdbcType == null) {
            return false;
        }
        String t = jdbcType.toUpperCase();
        return t.contains("CHAR") || t.contains("TEXT") || t.contains("CLOB");
    }

    private RefCandidate querySingleRow(String table, String pkColumn, String nameColumn) throws SQLException {
        // 식별자(table/pkColumn/nameColumn)는 모두 TableSchema(스키마 introspection)·정적 @Table 유래로
        // HTTP/사용자 입력이 아니다 → 식별자 연결은 안전(값은 없음). Seeds.insert/delete 패턴과 동일.
        String columns = nameColumn == null ? pkColumn : pkColumn + ", " + nameColumn;
        String sql = "SELECT " + columns + " FROM " + table + " LIMIT 1";   // POSTGRES/MYSQL/MARIADB 공통
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) {
                return null;
            }
            return new RefCandidate(rs.getString(1), nameColumn == null ? null : rs.getString(2));
        }
    }

    /** PK + 모든 NOT NULL 컬럼을 채운 default 행을 멱등 INSERT. 토큰은 재조회한 실제 값을 쓰므로 값 자체는 무관. */
    private void seedDefaultRow(TableSchema schema, String pkColumn) throws Exception {
        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (ColumnSchema column : schema.columns()) {
            if (column.name().equals(pkColumn)) {
                columns.add(column.name());
                values.add(defaultColumnValue(column, true));
            } else if (!column.nullable()) {
                columns.add(column.name());
                values.add(defaultColumnValue(column, false));
            }
        }
        Seeds.insert(connection, dbType, new SynthesizedInput.SeedRow(schema.name(), columns, values));
    }

    private static Object defaultColumnValue(ColumnSchema column, boolean pk) {
        String type = column.jdbcType().toUpperCase();
        if (type.contains("CHAR") || type.contains("TEXT") || type.contains("CLOB")) {
            return pk ? "ref-1" : "sample-" + column.name();
        }
        if (type.contains("BOOL")) {
            return true;
        }
        if (type.contains("UUID")) {
            return java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
        }
        if (type.contains("TIMESTAMP") || type.contains("DATETIME")) {
            return java.time.LocalDateTime.of(2037, 1, 1, 0, 0);
        }
        if (type.contains("DATE")) {
            return java.time.LocalDate.of(2037, 1, 1);
        }
        return 1;   // 수치 PK/컬럼
    }

    /**
     * 참조 필드별 PK backtrack trial: 해당 필드를 PK 토큰으로(나머지는 name 1순위) 합성해 1회씩 재발행한다.
     * name 1순위로 안 열리는 참조(예: {@code Converter<String,E>} PK 조회)의 bound arm을 연다. budget ≤
     * min(budgetRequests/2, 10). discoveredBy="form-ref-trial"(생성 제외).
     */
    private List<ExploredPath> exploreFormReferenceTrials(
            Endpoint endpoint, BodyShape shape, List<TableSchema> tables, List<FormFieldBinding> formBindings,
            Map<String, List<FieldConstraint>> fieldConstraints, Map<String, BodyShape> shapesByType,
            Map<String, RefCandidate> candidates, Map<String, String> nameRefValues) {
        List<ExploredPath> paths = new ArrayList<>();
        List<FormFieldBinding> refs = formBindings.stream()
                .filter(b -> b.kind() == FormFieldBinding.Kind.REFERENCE)
                .filter(b -> candidates.containsKey(b.field()) && candidates.get(b.field()).pk() != null)
                .toList();
        if (refs.isEmpty()) {
            return paths;
        }
        HttpClient http = HttpClient.newHttpClient();
        String authHeader = (authProvider != null && endpoint.authRequired())
                ? authConfig.headerValue(authProvider.token()) : null;
        int budget = Math.min(budgetRequests / 2, 10);
        int issued = 0;
        for (FormFieldBinding ref : refs) {
            if (issued >= budget) {
                break;
            }
            Map<String, String> trial = new HashMap<>(nameRefValues);
            trial.put(ref.field(), candidates.get(ref.field()).pk());   // 이 필드 → PK 후보
            try {
                SynthesizedInput input = happyInput(endpoint, shape, tables, enumConstants, enumColumns,
                        fieldConstraints, null, shapesByType, formBindings, trial);
                InvocationOutcome out = doSend(http, endpoint, input.body(), authHeader);
                paths.add(new ExploredPath(endpoint.id() + "-formref-" + ref.field(), endpoint.id(),
                        input.body(), out.status(), out.response(), List.of(), List.of(),
                        List.copyOf(out.coveredBranches()), "form-ref-trial",
                        List.of("form-ref-trial:" + ref.field()), List.of(), List.of()));
                issued++;
                log.info("form-ref-trial {} ({}=pk) -> status {}", endpoint.id(), ref.field(), out.status());
            } catch (Exception e) {   // best-effort: trial 실패는 회귀 아님
                log.warn("form-ref-trial failed for {} ({}): {}", endpoint.id(), ref.field(), e.getMessage());
            }
        }
        return paths;
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
                ObjectNode body = (ObjectNode) variant.input().body().deepCopy();
                for (EndpointParam param : endpoint.params()) {
                    if (param.kind() == ParamKind.QUERY && isBooleanType(param.javaType())) {
                        body.put(param.name(), gate);
                    }
                }
                InvocationOutcome out = invoker.invoke(body);
                String pathId = endpoint.id() + "-sg" + vseq;
                List<CapturedSql> sql = captureSqlFromParsed(pathId, body, out.capturedSql());
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

    /** backend가 drain한 ParsedSql를 CapturedSql로 환원 (모든 캡처 경로 공통). */
    private List<CapturedSql> captureSqlFromParsed(String pathId, JsonNode body,
                                                   List<ParsedSql> parsed) {
        Set<String> apiValues = collectBodyValues(body);
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

    record PathsBundle(List<ExploredPath> paths, List<CapturedSql> allSql,
                               List<io.graphrag.model.CapturedHttpCall> httpCalls,
                               List<io.graphrag.model.CapturedEventEmit> capturedEventEmits) {
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

    /**
     * REQ-012: happy 입력 재발행 + 2차 Kafka drain + field-by-field diff.
     *
     * <p>Kafka를 발행하는 happy(2xx) 경로가 있으면:
     * <ol>
     *   <li>write 경로(non-GET, POST → entity 행 존재)는 1차 발행이 만든 행을
     *       캡처 INSERT의 역 DELETE(스키마 PK 기반)로 정리해 유니크 충돌을 막는다. 정리 불가 시 diff skip.</li>
     *   <li>동일 happy 입력으로 raw HTTP 요청을 1회 재발행 → 2차 traceId 확보.</li>
     *   <li>2차 records를 drainAllByTraceId로 수집 후 1차와 field-by-field diff.</li>
     *   <li>2차 발행이 만든 행도 삭제해 DB를 diff 이전 상태로 완전 복원한다(C1).</li>
     *   <li>diff된 비결정 값을 해당 CapturedEventEmit.nonDeterministicValues에 추가.</li>
     * </ol>
     *
     * <p>best-effort: 2차 invoke 실패나 정리 불가 write 경로는 skip(P3 휴리스틱 유지, 회귀 0).
     */
    private PathsBundle enrichWithKafkaDiff(PathsBundle bundle, ExplorationOutcome outcome,
                                            Endpoint endpoint, boolean readPath,
                                            SynthesizedInput happy,
                                            List<io.graphrag.model.TableSchema> tables) {
        // happy(SUCCESS) 경로 중 Kafka를 발행한 PathCandidate 탐색.
        // 와이어 status가 아닌 분류 outcome으로 판정 → 엔벨로프-200(FAILURE)은 happy로 보지 않는다.
        PathCandidate happyCandidate = null;
        for (PathCandidate c : outcome.paths()) {
            if (classifier.classify(c.status(), c.response()).kind() == Outcome.Kind.SUCCESS
                    && c.kafkaTraceId() != null) {
                happyCandidate = c;
                break;
            }
        }
        if (happyCandidate == null) {
            return bundle;   // Kafka 발행 happy path 없음 → skip
        }

        // 1차 emit 레코드 (bundle에서 pathId로 조회)
        final String happyPathId = happyCandidate.pathId();
        List<io.graphrag.model.CapturedEventEmit> firstEmits = bundle.capturedEventEmits().stream()
                .filter(e -> e.pathId().equals(happyPathId))
                .toList();
        if (firstEmits.isEmpty()) {
            return bundle;   // 1차 emit 없음 → skip
        }

        // write 경로: 1차 발행이 만든 entity 행을 삭제(유니크 충돌 방지).
        // C2 fix: 스키마 PK 기반으로 컬럼을 결정(컬럼 위치 가정 제거).
        if (!readPath) {
            boolean cleaned = tryDeleteInsertRow(happyCandidate.capturedSql(), tables);
            if (!cleaned) {
                // 정리 불가(외부 부작용 또는 INSERT 미캡처) → 보수적 skip
                log.debug("kafka-diff: skipping diff for write-path {} — cannot safely clean up first emit row",
                        endpoint.id());
                return bundle;
            }
        }

        // 2차 invoke: raw httpInvoker(seed-reset 래핑 없음) — diff용 단순 재발행.
        String authHeader = (authProvider != null && endpoint.authRequired())
                ? authConfig.headerValue(authProvider.token()) : null;
        InvocationOutcome second;
        try {
            second = doSend(HttpClient.newHttpClient(), endpoint, happy.body(), authHeader);
            log.debug("kafka-diff: second invoke {} → status={} traceId={}",
                    endpoint.id(), second.status(), second.kafkaTraceId());
        } catch (Exception e) {
            log.warn("kafka-diff: second invoke failed for {}, skipping diff: {}", endpoint.id(), e.getMessage());
            return bundle;   // 2차 invoke 실패 → skip
        }

        String secondTraceId = second.kafkaTraceId();
        if (secondTraceId == null) {
            log.debug("kafka-diff: no traceId from second invoke for {}, skipping diff", endpoint.id());
            return bundle;
        }

        // 2차 drain: 2차 invoke가 발행한 records를 수집
        java.util.Map<String, java.util.List<KafkaCaptureReceiver.CapturedRecord>> secondByTrace =
                kafkaCapture.drainAllByTraceId(300);
        java.util.List<KafkaCaptureReceiver.CapturedRecord> secondRecords =
                secondByTrace.getOrDefault(secondTraceId, java.util.List.of());

        // C1 fix: write 경로에서 2차 invoke가 만든 행도 삭제해 DB를 완전 복원한다.
        // diff 결과 계산 전/후와 무관하게 삭제를 먼저 시도(bestー effort: 실패해도 diff는 진행).
        if (!readPath) {
            boolean secondCleaned = tryDeleteInsertRow(second.capturedSql(), tables);
            if (!secondCleaned) {
                log.warn("kafka-diff: could not delete second-emit row for {} — DB may be in dirty state",
                        endpoint.id());
            }
        }

        if (secondRecords.isEmpty()) {
            log.debug("kafka-diff: no second records for {}, skipping diff", endpoint.id());
            return bundle;
        }

        // 입력 유래 값(substitutions keys) — REQ-010 불변: 절대 비결정 표시 금지.
        // body의 모든 스칼라 값을 input-derived 집합으로 사용(collectBodyValues 재사용).
        Set<String> inputDerived = collectBodyValues(happy.body());

        // field-by-field diff: 1차 emit과 2차 emit을 순서대로 비교(같은 topic 순).
        // 복수 레코드는 순서 매칭(i번째 1차 ↔ i번째 2차).
        java.util.Set<String> allNonDeterministic = new HashSet<>();
        int compareCount = Math.min(firstEmits.size(), secondRecords.size());
        for (int i = 0; i < compareCount; i++) {
            io.graphrag.model.CapturedEventEmit emit1 = firstEmits.get(i);
            KafkaCaptureReceiver.CapturedRecord rec2 = secondRecords.get(i);
            if (emit1.payload() == null) {
                continue;
            }
            Set<String> diff = KafkaPayloadDiffer.diffNonDeterministicValues(
                    emit1.payload(), rec2.value(), inputDerived);
            allNonDeterministic.addAll(diff);
        }

        if (allNonDeterministic.isEmpty()) {
            log.debug("kafka-diff: no non-deterministic fields detected for {}", endpoint.id());
            return bundle;
        }
        log.info("kafka-diff: detected {} non-deterministic value(s) for {}: {}",
                allNonDeterministic.size(), endpoint.id(), allNonDeterministic);

        // CapturedEventEmit에 nonDeterministicValues 주입 (happy path emits만)
        final Set<String> finalNonDet = Set.copyOf(allNonDeterministic);
        List<io.graphrag.model.CapturedEventEmit> updatedEmits = bundle.capturedEventEmits().stream()
                .map(e -> e.pathId().equals(happyPathId)
                        ? new io.graphrag.model.CapturedEventEmit(e.id(), e.pathId(), e.topic(), e.key(),
                              e.payload(), finalNonDet)
                        : e)
                .toList();

        return new PathsBundle(bundle.paths(), bundle.allSql(), bundle.httpCalls(), updatedEmits);
    }

    /**
     * write 경로 발행 정리: SQL 목록에서 첫 INSERT를 찾아 스키마 PK 컬럼 기반으로
     * {@code DELETE FROM <table> WHERE <pk_col> = <pk_val>}을 실행한다.
     *
     * <p>C2 fix:
     * <ul>
     *   <li>PK 컬럼은 {@code TableSchema}에서 조회한다 — 컬럼 위치(position 1) 가정 제거.</li>
     *   <li>테이블 스키마에서 PK를 찾을 수 없으면 false 반환(보수적 skip).</li>
     *   <li>PK가 INSERT 바인딩 목록에 없으면(Hibernate auto-increment: DB가 PK를 발급),
     *       2차 invoke는 새 PK를 받아 충돌 없이 성공하므로 정리 불필요 → true 반환.</li>
     *   <li>{@code executeUpdate() == 0}(삭제 0 행)은 실패로 간주 → false 반환.</li>
     * </ul>
     *
     * @param sqlList capturedSql 목록 (InvocationOutcome 또는 PathCandidate 기원)
     * @param tables  스키마 정보 (PK 컬럼 해석용)
     * @return true이면 정리 성공(또는 불필요), false이면 정리 불가 → diff skip
     */
    boolean tryDeleteInsertRow(List<io.graphrag.builder.capture.ParsedSql> sqlList,
                               List<io.graphrag.model.TableSchema> tables) {
        for (io.graphrag.builder.capture.ParsedSql sql : sqlList) {
            if (!"INSERT".equals(sql.kind())) {
                continue;
            }
            String table = sql.tableName();
            if (table == null || table.isBlank()) {
                continue;
            }
            if (sql.bindings().isEmpty()) {
                continue;
            }
            // C2: 스키마에서 PK 컬럼명 조회
            String pkCol = resolvePkColumn(table, tables);
            if (pkCol == null) {
                log.debug("kafka-diff: no PK found in schema for table={}, skipping cleanup", table);
                return false;
            }
            // INSERT 컬럼 목록에서 PK 컬럼의 위치를 찾아 바인딩 값 조회.
            // PK가 없으면 Hibernate auto-increment(DB가 PK 발급) → 2차 invoke는 새 PK로 충돌 없이 성공.
            // 이 경우 DB 정리 불필요이므로 true 반환(diff 진행).
            String pkVal = findBindingValueForColumn(sql, pkCol);
            if (pkVal == null) {
                log.debug("kafka-diff: PK column {} not in INSERT for table={} — auto-generated PK, no cleanup needed",
                        pkCol, table);
                return true;
            }
            String deleteSql = "DELETE FROM " + table + " WHERE " + pkCol + " = ?";
            try (java.sql.PreparedStatement stmt = connection.prepareStatement(deleteSql)) {
                // PK는 보통 숫자 — Long 변환 시도, 실패 시 String
                try {
                    stmt.setLong(1, Long.parseLong(pkVal));
                } catch (NumberFormatException e) {
                    stmt.setString(1, pkVal);
                }
                int rows = stmt.executeUpdate();
                if (rows == 0) {
                    // C2: 0 행 삭제는 실패 — 행이 없거나 이미 삭제됨
                    log.warn("kafka-diff: DELETE matched 0 rows from {} ({}={}) — treating as cleanup failure",
                            table, pkCol, pkVal);
                    return false;
                }
                log.debug("kafka-diff: deleted {} row(s) from {} ({}={})", rows, table, pkCol, pkVal);
                return true;
            } catch (java.sql.SQLException e) {
                log.warn("kafka-diff: write-path cleanup failed for table={} col={} val={}: {}",
                        table, pkCol, pkVal, e.getMessage());
                return false;
            }
        }
        // INSERT 없음(read-only path이거나 SQL 미캡처) — write 경로에서 여기 도달하면 skip
        return false;
    }

    /**
     * 스키마에서 지정 테이블의 PK 컬럼명을 반환한다. 없으면 null.
     * 테이블명 비교는 대소문자 무시(DB 방언 대응).
     */
    static String resolvePkColumn(String tableName,
                                  List<io.graphrag.model.TableSchema> tables) {
        if (tables == null || tableName == null) {
            return null;
        }
        for (io.graphrag.model.TableSchema schema : tables) {
            if (schema.name().equalsIgnoreCase(tableName)) {
                for (io.graphrag.model.ColumnSchema col : schema.columns()) {
                    if (col.primaryKey()) {
                        return col.name();
                    }
                }
            }
        }
        return null;
    }

    /**
     * INSERT SQL의 컬럼 목록에서 {@code pkCol}과 일치하는 컬럼의 바인딩 값을 반환한다.
     * 컬럼명 비교는 대소문자 무시. 없으면 null.
     */
    static String findBindingValueForColumn(io.graphrag.builder.capture.ParsedSql sql,
                                            String pkCol) {
        // INSERT INTO t (col1, col2, ...) VALUES (?, ?, ...) 형식에서
        // columnForPosition(i)로 컬럼명을 구하고 pkCol과 비교한다.
        for (int i = 1; i <= sql.bindings().size(); i++) {
            String colName = sql.columnForPosition(i);
            if (colName != null && colName.equalsIgnoreCase(pkCol)) {
                return sql.bindings().get(i - 1).value();
            }
        }
        return null;
    }

    /** outcome → ExploredPath/CapturedSql/CapturedHttpCall 묶음. (package-private: classifier 배선 테스트용) */
    PathsBundle buildPaths(ExplorationOutcome outcome, Endpoint endpoint,
                                  List<ConstraintExtractor.ConditionSpan> conditions) {
        List<ExploredPath> paths = new ArrayList<>();
        List<CapturedSql> allSql = new ArrayList<>();
        List<io.graphrag.model.CapturedHttpCall> allHttpCalls = new ArrayList<>();
        List<io.graphrag.model.CapturedEventEmit> allCapturedEventEmits = new ArrayList<>();

        // End-of-endpoint batch drain: Kafka records are buffered continuously by the background
        // consumer; by the time all requests for this endpoint have completed, all emitted records
        // are already buffered. A short settle handles stragglers (records in flight).
        java.util.Map<String, java.util.List<KafkaCaptureReceiver.CapturedRecord>> kafkaByTrace =
                kafkaCapture != null ? kafkaCapture.drainAllByTraceId(300) : java.util.Map.of();

        for (PathCandidate candidate : outcome.paths()) {
            List<CapturedSql> sql = captureSql(candidate);
            allSql.addAll(sql);
            List<io.graphrag.model.CapturedHttpCall> httpCalls = captureHttpCalls(candidate);
            allHttpCalls.addAll(httpCalls);

            List<io.graphrag.model.CapturedEventEmit> pathEventEmits = new ArrayList<>();
            int emitSeq = 1;
            for (KafkaCaptureReceiver.CapturedRecord record :
                    kafkaByTrace.getOrDefault(candidate.kafkaTraceId(), java.util.List.of())) {
                pathEventEmits.add(new io.graphrag.model.CapturedEventEmit(
                        "event-" + candidate.pathId() + "-" + (emitSeq++),
                        candidate.pathId(),
                        record.topic(),
                        record.key(),
                        record.value()
                ));
            }
            allCapturedEventEmits.addAll(pathEventEmits);

            // 와이어 status + 응답 body로 성공/실패 판정. expectedStatus는 와이어 status 그대로 유지하고
            // outcome/semanticStatus/semanticStatusText만 classifier 산출로 기록(엔벨로프-200 → FAILURE).
            Outcome o = classifier.classify(candidate.status(), candidate.response());
            // error-envelope: 와이어 2xx이지만 분류 결과 FAILURE → Task 11 생성기 라우팅용 마커 부여.
            String discoveredBy = (o.kind() == Outcome.Kind.FAILURE && candidate.status() / 100 == 2)
                    ? "error-envelope"
                    : candidate.discoveredBy();
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
                    discoveredBy,
                    matchConstraints(candidate, conditions, endpoint),
                    validate(sql),
                    List.of(),
                    pathEventEmits.stream().map(io.graphrag.model.CapturedEventEmit::id).toList(),
                    candidate.responseHeaders(),
                    o.kind(),
                    o.semanticStatus(),
                    o.semanticStatusText()));
        }
        return new PathsBundle(paths, allSql, allHttpCalls, allCapturedEventEmits);
    }

    /** 시드를 path에 연결: GET은 첫 2xx path, 비-GET by-id는 path별 고유 PK 복제. */
    private AttachResult attachSeeds(Endpoint endpoint, boolean readPath,
                                     List<ExploredPath> paths, List<RequiredSeed> requiredSeeds) {
        if (!(requiredSeeds != null && !requiredSeeds.isEmpty())) {
            return new AttachResult(paths, requiredSeeds);
        }
        if (readPath) {
            // GET: id가 변이되므로(404/400 path 존재) seed는 첫 SUCCESS(존재하는 id) path에만 연결.
            // outcome 기준 — 엔벨로프-200(FAILURE)은 존재하는 리소스가 아니므로 seed를 붙이지 않는다.
            int successIdx = -1;
            for (int i = 0; i < paths.size(); i++) {
                if (paths.get(i).outcome() == Outcome.Kind.SUCCESS) { successIdx = i; break; }
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
                        np.validationWarnings(), np.requiredSeedIds(),
                        np.capturedEventEmitIds(), np.responseHeaders());
            }
            paths.set(i, np);
        }
        return new AttachResult(paths, perPath);
    }

    /**
     * SUT 응답 헤더 맵에서 커스텀 헤더만 추출한다. 다중값 헤더는 첫 번째 값만 취하고(flatten),
     * RESPONSE_HEADER_DENYLIST에 포함된 표준/hop-by-hop 헤더는 제외한다(불안정 값·보일러플레이트 제거).
     */
    static Map<String, String> captureResponseHeaders(Map<String, java.util.List<String>> rawHeaders) {
        java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, java.util.List<String>> entry : rawHeaders.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank()) {
                continue;
            }
            if (RESPONSE_HEADER_DENYLIST.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            // 생성기는 헤더 **이름**만 쓴다(존재 단언 `.header(name, notNullValue())`). 따라서 라이브
            // 헤더 **값**(쿠키·토큰 등 민감값일 수 있음)을 graph.json에 저장하지 않고 빈 sentinel만 남긴다(보안 리뷰).
            java.util.List<String> values = entry.getValue();
            if (values != null && !values.isEmpty()) {
                result.put(name, "");
            }
        }
        return result.isEmpty() ? Map.of() : java.util.Collections.unmodifiableMap(result);
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
        SqlCaptureBackend.Scope sqlScope = sqlCapture.begin();
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
        // 상관 헤더는 활성 trace-mode가 결정(otel: traceparent, sleuth: B3, none: 없음).
        // 사용자 제공 상관 헤더는 제거하고 backend 것만 주입(중복·비결정 전파 방지).
        Map<String, String> userHeaders = extraHeaders.resolved(Instant.now());
        Map<String, String> scopeHeaders = sqlScope.requestHeaders();
        for (String name : userHeaders.keySet()) {
            if (CORRELATION_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))
                    && !scopeHeaders.isEmpty()) {
                log.warn("ignoring user-supplied correlation header '{}' (backend wins)", name);
            }
        }
        for (Map.Entry<String, String> h : applyCorrelationPriority(userHeaders, scopeHeaders).entrySet()) {
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
        List<ParsedSql> drained = sqlScope.drain();   // flush 여유는 backend.drain() 내부로 이동

        String traceId = null;
        for (Map.Entry<String, String> h : sqlScope.requestHeaders().entrySet()) {
            if (h.getKey().equalsIgnoreCase("traceparent")) {
                String tp = h.getValue();
                if (tp != null) {
                    String[] parts = tp.split("-");
                    if (parts.length >= 2) {
                        String candidate = parts[1];
                        if (candidate.length() == 32) {
                            traceId = candidate;
                        }
                    }
                }
                break;
            }
        }

        ExecutionDataStore delta = coverage.dump(true);
        String coverageKey = CoverageFingerprint.of(delta, appClasses);
        for (ExecutionData ed : delta.getContents()) {
            cumulativeCoverage.put(ed);   // probe OR 병합 (arm-level 누적)
        }
        BranchCoverage requestCoverage = analyzer.analyze(delta);
        long logEnd = sut.logOffset();
        Map<String, String> capturedResponseHeaders = captureResponseHeaders(response.headers().map());
        return new InvocationOutcome(response.statusCode(),
                parseJsonOrNull(response.body()),
                requestCoverage.covered(), logStart, logEnd,
                httpCapture == null ? List.of() : httpCapture.drainNewExchanges(),
                coverageKey, drained, java.util.List.of(), traceId, capturedResponseHeaders);
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
        return happyInput(endpoint, shape, tables, enumConstants, enumColumns, fieldConstraints, null,
                Map.of(), List.of(), Map.of());
    }

    // 제약-aware happy(Feature A: fieldConstraints) + SQL-driven seed 보정(hint) + 폼 바인딩 컨텍스트를 받는 정본.
    // 폼 엔드포인트(ParamKind.FORM 보유)는 body를 FormBodySynthesizer로 합성한다(중첩 점-경로 평면화 + 참조
    // 토큰 refValues 주입). refValues는 러너가 백업행에서 산출(Phase 3); 비면 참조 필드 skip(스칼라/skip 폴백).
    static SynthesizedInput happyInput(Endpoint endpoint, BodyShape shape, List<TableSchema> tables,
                                       Map<String, List<String>> enumConstants,
                                       Map<String, List<String>> enumColumns,
                                       Map<String, List<FieldConstraint>> fieldConstraints,
                                       ResolutionHint hint,
                                       Map<String, BodyShape> shapesByType,
                                       List<FormFieldBinding> formBindings,
                                       Map<String, String> refValues) {
        boolean get = endpoint.httpMethod().equals("GET");
        boolean hasPath = endpoint.params().stream().anyMatch(p -> p.kind() == ParamKind.PATH);
        boolean form = endpoint.params().stream().anyMatch(p -> p.kind() == ParamKind.FORM);
        if (get || hasPath) {
            SynthesizedInput pathPart =
                    new ReadInputSynthesizer(enumConstants, enumColumns).synthesize(endpoint, tables, hint);
            if (get || shape == null) {
                return pathPart;
            }
            SynthesizedInput bodyPart = form
                    ? new FormBodySynthesizer(enumConstants)
                            .synthesize(shape, shapesByType, formBindings, refValues, tables, fieldConstraints)
                    : new SampleInputSynthesizer(enumConstants).synthesize(shape, tables, fieldConstraints);
            JsonNode bn = bodyPart.body();
            if (!(bn instanceof ObjectNode bo)) {
                // 컬렉션 body(array)는 path/query와 병합 불가 — body를 그대로 둔다(by-id+컬렉션 body는
                // 드문 조합이며, path param은 URL에서 buildPathAndQuery가 처리). seed는 bodyPart 것을 채택.
                return new SynthesizedInput(bn, bodyPart.seeds());
            }
            ObjectNode merged = bo.deepCopy();
            merged.setAll((ObjectNode) pathPart.body());   // path/query 우선
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
        if (shape == null) {
            return new SynthesizedInput(Json.mapper().createObjectNode(), List.of());
        }
        return form
                ? new FormBodySynthesizer(enumConstants)
                        .synthesize(shape, shapesByType, formBindings, refValues, tables, fieldConstraints)
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
                p.discoveredBy(), p.constraints(), p.validationWarnings(), seedIds,
                p.capturedEventEmitIds(), p.responseHeaders());
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
                // 입력값이 없거나 blank(빈 문자열·공백)이면 sentinel을 사용한다.
                // blank 입력을 그대로 치환하면 double-slash(/x//content)가 생겨
                // 캡처(explorer)와 재현(generator) 간 경로 불일치가 발생한다(s404_2 버그).
                String raw = input.has(param.name()) ? input.get(param.name()).asText() : null;
                String value = (raw == null || raw.isBlank()) ? pathSentinel(param) : raw;
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
        // 게이트웨이 predicate path의 Ant wildcard(**/*) → 구체 probe 세그먼트로 치환해
        // Spring Ant 매처가 실제 요청 경로와 일치하도록 한다.
        path = concretizeAntWildcards(path);
        return path + query;
    }

    /**
     * Ant-style wildcard 세그먼트를 구체 probe 값으로 치환한다.
     * 위임: {@link io.graphrag.model.PathPatterns#concretizeAntWildcards(String)}.
     */
    static String concretizeAntWildcards(String path) {
        return io.graphrag.model.PathPatterns.concretizeAntWildcards(path);
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
        return captureSqlFromParsed(candidate.pathId(), candidate.body(), candidate.capturedSql());
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
        return report(endpoint, outcome, comparisons, List.of(), List.of());
    }

    private ExplorationReport.EndpointExploration report(Endpoint endpoint,
                                                         ExplorationOutcome outcome,
                                                         List<ConstraintExtractor.Comparison> comparisons,
                                                         List<ExplorationReport.DroppedPath> drops) {
        return report(endpoint, outcome, comparisons, drops, List.of());
    }

    private ExplorationReport.EndpointExploration report(Endpoint endpoint,
                                                         ExplorationOutcome outcome,
                                                         List<ConstraintExtractor.Comparison> comparisons,
                                                         List<ExplorationReport.DroppedPath> drops,
                                                         List<ExploredPath> finalPaths) {
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
        // REQ-008: 탐색 경로 중 FAILURE ≥1 이고 SUCCESS = 0이면 사유를 기록한다.
        boolean hasFailure = finalPaths.stream()
                .anyMatch(p -> p.outcome() == Outcome.Kind.FAILURE);
        boolean hasSuccess = finalPaths.stream()
                .anyMatch(p -> p.outcome() == Outcome.Kind.SUCCESS);
        String noHappyPathReason = (hasFailure && !hasSuccess)
                ? "all responses error-enveloped" : null;
        return new ExplorationReport.EndpointExploration(
                endpoint.id(), total, covered.size(), missed,
                outcome.pathsByEngine(), solverRelevantMissed, drops, noHappyPathReason);
    }

    /**
     * body의 스칼라 값을 수집(API_PARAM 분류용). object는 1단 필드값, array는 각 element의 1단 값
     * (scalar element면 그 값, object element면 그 필드값 — 한 단계). 컬렉션 body의 element 값도 SQL
     * 바인딩과 매칭되게 한다. Kafka/WS 캡처 러너와 공용(DRY).
     */
    static Set<String> collectBodyValues(JsonNode body) {
        Set<String> values = new HashSet<>();
        if (body instanceof ArrayNode arr) {
            arr.forEach(e -> addNodeValues(e, values));
        } else {
            addNodeValues(body, values);
        }
        return values;
    }

    private static void addNodeValues(JsonNode node, Set<String> values) {
        if (node.isValueNode()) {
            if (!node.isNull()) {
                values.add(node.asText());
            }
            return;
        }
        node.fields().forEachRemaining(e -> {
            if (!e.getValue().isNull()) {
                values.add(e.getValue().asText());
            }
        });
    }

    /**
     * REQ-013/014/015: non-2xx 경로의 재현 가능 여부를 검증하고, 재현 불가 경로를 억제(drop)한다.
     *
     * <p><b>정책:</b>
     * <ul>
     *   <li>2xx path → 검증 없이 KEEP (attachSeeds가 이미 시드를 붙였으므로 재현 가능).
     *       여기에는 error-envelope path(와이어 2xx, outcome FAILURE)도 포함된다 — 와이어 status가
     *       2xx이므로 이 분기에서 KEEP된다. buildPaths가 discoveredBy="error-envelope"를 부여해
     *       Task 11 생성기가 올바른 검증 전략을 선택할 수 있게 한다.
     *   <li>negative-auth / negative-validation / state-guard 마커 path → 검증 없이 KEEP
     *       (discoveredBy 필드로 식별; 이 경로는 DB 상태와 독립적인 인증/검증 거부).
     *   <li>GET(read) non-2xx → verifier.replay() 호출:
     *       replay 상태 == 캡처 상태 → KEEP; 다르면 → DROP + DroppedPath 기록.
     *   <li>non-GET non-2xx (mutating) → conservative KEEP: 재실행 자체가 DB를 변이시켜
     *       신뢰할 수 없으므로, 검증 불가 = DROP 금지. 진짜 버그가 묻히는 것보다 나쁜 쪽이므로
     *       억제하지 않는다.
     *   <li>verifier 예외 → conservative KEEP (연결 오류 등): DROP 없이 경고만 남긴다.
     * </ul>
     *
     * <p>이 메서드는 패키지-private static으로 단위 테스트 가능하다. 실 HTTP client는 호출자가
     * ReproVerifier 구현으로 주입한다.
     */
    static FilterResult verifyAndFilterNonTwoxx(
            Endpoint endpoint, List<ExploredPath> paths,
            List<RequiredSeed> requiredSeeds, ReproVerifier verifier) {
        List<ExploredPath> kept = new ArrayList<>();
        List<ExplorationReport.DroppedPath> dropped = new ArrayList<>();
        boolean readPath = endpoint.httpMethod().equals("GET");

        for (ExploredPath path : paths) {
            int status = path.expectedStatus();
            // 2xx → 검증 범위 외, 항상 KEEP
            if (status / 100 == 2) {
                kept.add(path);
                continue;
            }
            // 마커 path(negative-auth, negative-validation, state-guard) → 검증 범위 외, 항상 KEEP
            String discoveredBy = path.discoveredBy();
            if (discoveredBy != null
                    && (discoveredBy.startsWith("negative-") || discoveredBy.startsWith("state-guard"))) {
                kept.add(path);
                continue;
            }
            // non-GET mutating non-2xx → conservative KEEP (재실행 자체가 부작용)
            if (!readPath) {
                kept.add(path);
                continue;
            }
            // GET non-2xx → 재현 검증
            try {
                int replayStatus = verifier.replay(endpoint, path, requiredSeeds);
                if (replayStatus == status) {
                    // 재현 가능 → KEEP (결정론적 404/400/5xx = 진짜 경로 또는 진짜 버그)
                    kept.add(path);
                } else {
                    // 재현 불가 → DROP + 기록 (REQ-015: 무음 손실 금지)
                    log.warn("suppressing non-reproducible path {} (endpoint={}): "
                                    + "captured={} replay={} — path excluded from graph",
                            path.id(), endpoint.id(), status, replayStatus);
                    dropped.add(new ExplorationReport.DroppedPath(
                            endpoint.id(), path.id(), status, replayStatus, "status_mismatch"));
                }
            } catch (Exception e) {
                // 검증 자체 실패 → conservative KEEP (버그를 잃는 것이 오탐보다 낫다)
                log.warn("repro-verify failed for path {} (endpoint={}), keeping conservatively: {}",
                        path.id(), endpoint.id(), e.getMessage());
                kept.add(path);
            }
        }
        return new FilterResult(List.copyOf(kept), List.copyOf(dropped));
    }

    private static JsonNode parseJsonOrNull(String body) {
        try {
            return Json.mapper().readTree(body);
        } catch (UncheckedIOException | java.io.IOException e) {
            return Json.mapper().nullNode();
        }
    }
}
