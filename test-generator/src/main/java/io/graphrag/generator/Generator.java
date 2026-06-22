package io.graphrag.generator;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import io.graphrag.generator.client.FileGraphRagClient;
import io.graphrag.generator.client.GraphRagClient;
import io.graphrag.generator.compose.ComposedFixture;
import io.graphrag.generator.compose.FixtureComposer;
import io.graphrag.generator.compose.HttpMockComposer;
import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.ColumnSchema;
import io.graphrag.model.TableSchema;
import io.graphrag.model.RequiredSeed;
import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GeneratedFile;
import io.graphrag.model.GenerationRequest;
import io.graphrag.model.GenerationResult;
import io.graphrag.model.ParallelSafetyReport;
import io.graphrag.model.Outcome;
import io.graphrag.model.ParamKind;

import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 도구 2 본체. LLM 없음 — 동일 입력은 항상 동일 출력 (docs/04).
 * 큰 골격: Mustache 템플릿. 가변 슬롯: FixtureComposer.
 */
public class Generator {

    private final GraphRagClient client;
    private final Mustache template;
    private final Mustache wsTemplate;
    private final Mustache kafkaTemplate;

    public Generator(Path graphDir) {
        this(new FileGraphRagClient(graphDir));
    }

    public Generator(GraphRagClient client) {
        this.client = client;
        DefaultMustacheFactory factory = new DefaultMustacheFactory();
        this.template = factory.compile("templates/test-class.mustache");
        this.wsTemplate = factory.compile("templates/ws-test-class.mustache");
        this.kafkaTemplate = factory.compile("templates/kafka-test-class.mustache");
    }

    /** pathId 미지정 시 endpoint의 전 path에 대해 path당 테스트 클래스 1개씩 생성 (1.5). */
    public GenerationResult generate(GenerationRequest request) {
        if (client.hasKafkaConsumer(request.endpointId())) {
            return generateKafka(request);
        }
        if (client.hasWsEndpoint(request.endpointId())) {
            return generateWs(request);
        }
        Endpoint endpoint = client.endpoint(request.endpointId());
        // @Controller 폼(form-urlencoded) 엔드포인트는 현재 생성 미지원 — 커버리지 전용(빌더가 탐색·캡처).
        // pathId 지정(path별 생성) 경로보다 먼저 차단해야 generateSingle의 JSON-body 가정이 폼에
        // 깨진 테스트를 내지 않는다.
        if (endpoint.params().stream().anyMatch(p -> p.kind() == io.graphrag.model.ParamKind.FORM)) {
            return new GenerationResult(List.of(),
                    List.of("form endpoint not generated (coverage-only): " + endpoint.id()),
                    new io.graphrag.model.ParallelSafetyReport(List.of(), List.of()));
        }
        List<ExploredPath> paths = new ArrayList<>();
        if (request.pathId() != null) {
            paths.add(client.path(request.pathId()));
        } else {
            for (ExploredPath p : client.pathsForEndpoint(request.endpointId())) {
                if ("negative-auth".equals(p.discoveredBy())
                        || "negative-validation".equals(p.discoveredBy())) {
                    continue;
                }
                paths.add(p);
            }
        }

        List<ScenarioMethod> parallel = new ArrayList<>();
        List<ScenarioMethod> serial = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (ExploredPath path : paths) {
            ScenarioMethod m = buildScenarioMethod(endpoint, request, path);
            warnings.addAll(m.warnings());
            (m.serial() ? serial : parallel).add(m);
        }

        List<GeneratedFile> files = new ArrayList<>();
        List<String> fullyParallel = new ArrayList<>();
        List<io.graphrag.model.SerialRequired> serialRequired = new ArrayList<>();

        if (!parallel.isEmpty()) {
            files.add(renderTestClass(request, endpoint, request.testClassName(), parallel, false));
            fullyParallel.add(request.testClassName());
        }
        if (!serial.isEmpty()) {
            String serialName = request.testClassName() + "Serial";
            if (request.pathId() != null && parallel.isEmpty()) {
                serialName = request.testClassName();
            }
            files.add(renderTestClass(request, endpoint, serialName, serial, true));
            serialRequired.add(new io.graphrag.model.SerialRequired(
                    serialName, SAME_THREAD_REASON,
                    "외부 HTTP 호출에 baggage가 전파되지 않음 — OTEL agent 부착 또는 직렬 실행 필요"));
        }
        if (!files.isEmpty()) {
            files.add(new GeneratedFile("junit-platform.properties", JUNIT_PLATFORM_PROPERTIES));
        }
        return new GenerationResult(files, warnings,
                new ParallelSafetyReport(fullyParallel, serialRequired));
    }

    private static final String JUNIT_PLATFORM_PROPERTIES =
            "junit.jupiter.execution.parallel.enabled=true\n"
            + "junit.jupiter.execution.parallel.mode.default=concurrent\n"
            + "junit.jupiter.execution.parallel.mode.classes.default=concurrent\n"
            + "junit.jupiter.execution.parallel.config.strategy=dynamic\n"
            + "junit.jupiter.execution.parallel.config.dynamic.factor=1\n";

    private static final String SAME_THREAD_REASON = "SUT_PROPAGATION_MISSING";
    private static final String CLASS_SERIAL_MARK = "@Execution(ExecutionMode.SAME_THREAD)\n";
    private static final String SERIAL_IMPORTS =
            "import org.junit.jupiter.api.parallel.Execution;\n"
            + "import org.junit.jupiter.api.parallel.ExecutionMode;\n";

    private record ScenarioMethod(
            String methodName, List<ComposedFixture.Var> vars,
            List<ComposedFixture.Stmt> inserts, List<ComposedFixture.Stmt> deletes,
            String mocksBlock, boolean readPath, String bodyExpr, String httpMethodLower,
            String requestPath, int expectedStatus, String assertionsBlock, boolean authRequired,
            boolean serial, List<String> warnings,
            List<java.util.Map<String, Object>> kafkaEmits,
            Map<String, Object> postCreateCleanup) {
    }

    private ScenarioMethod buildScenarioMethod(Endpoint endpoint, GenerationRequest request,
                                               ExploredPath path) {
        List<CapturedSql> sql = client.sqlForPath(path.id());
        boolean readPath = endpoint.httpMethod().equals("GET");
        java.util.Map<String, String> knownByField = new java.util.HashMap<>();
        // collection body(배열)면 원소 객체에서 결정적 필드를 도출(collection-of-DTO).
        JsonNode knownSrc = path.sampleInput();
        if (knownSrc instanceof com.fasterxml.jackson.databind.node.ArrayNode arr
                && arr.size() > 0 && arr.get(0) instanceof com.fasterxml.jackson.databind.node.ObjectNode) {
            knownSrc = arr.get(0);
        }
        if (knownSrc instanceof com.fasterxml.jackson.databind.node.ObjectNode in) {
            in.fields().forEachRemaining(e -> {
                if (!e.getValue().isNull()) {
                    knownByField.put(e.getKey(), e.getValue().asText());
                }
            });
        }
        for (RequiredSeed s : client.seedsForPath(path.id())) {
            for (int i = 0; i < s.columns().size() && i < s.values().size(); i++) {
                knownByField.putIfAbsent(snakeToCamel(s.columns().get(i)), s.values().get(i));
            }
        }
        // 에러 계약 디스크립터: 영속된 값이 권위. null(비-envelope/legacy graph)이면 그대로 null을 넘겨
        // FixtureComposer가 에러 계약 단언을 생성하지 않게 한다(비-envelope SUT 회귀 방지).
        ComposedFixture fixture = new FixtureComposer().compose(path, sql, client.tables(),
                client.seedsForPath(path.id()), readPath, knownByField,
                client.errorContractStatusField(), client.errorDetailField(), client.errorDetailContains());
        HttpMockComposer.ComposedMocks mocks =
                new HttpMockComposer().compose(client.httpCallsForPath(path.id()));

        String bodyExpr = bodyExpr(fixture);
        boolean methodHasBody = endpoint.httpMethod().equals("POST")
                || endpoint.httpMethod().equals("PUT") || endpoint.httpMethod().equals("PATCH");
        if (methodHasBody && fixture.bodyFormat().isEmpty()) {
            String json = jsonBodyFromInput(endpoint, path.sampleInput());
            bodyExpr = "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        List<String> assertionParts = new ArrayList<>();
        for (var a : fixture.assertions()) {
            assertionParts.add("\n            .body(\"" + a.jsonPath() + "\", " + a.matcher() + ")");
        }
        // 커스텀 응답 헤더: 게이트웨이 프록시가 전파한 헤더가 있으면 notNullValue() 단언 추가.
        // 빈 맵이면 기존 테스트에 영향 없음.
        for (String h : path.responseHeaders().keySet()) {
            assertionParts.add("\n            .header(\"" + h + "\", org.hamcrest.Matchers.notNullValue())");
        }
        String assertionsBlock = String.join("", assertionParts);

        // Kafka outbound-produce 캡처: 이 path가 발행한 이벤트마다 subscribe + consume/assert 블록 모델 생성.
        List<Map<String, Object>> kafkaEmits = new ArrayList<>();
        for (io.graphrag.model.CapturedEventEmit emit : client.capturedEventEmitsForPath(path.id())) {
            Map<String, Object> modelEmit = new HashMap<>();
            modelEmit.put("topic", emit.topic());
            // REQ-012: emit별 diff-검출 비결정 값을 fixture의 DB-PK 기반 집합과 병합한다.
            // 입력 유래 값(substitutions keys)은 이미 REQ-010 불변으로 보장됨(KafkaPayloadDiffer).
            ComposedFixture emitFixture = mergeNonDeterministicValues(fixture, emit.nonDeterministicValues());
            // key는 consumeNextRecord의 expectedKey 인자로 쓴다(공유 토픽 오염 격리).
            // 입력 유래 값이면 테스트 런타임 변수로 치환, 일반 리터럴이면 따옴표 문자열.
            modelEmit.put("keyExpr", emitKeyExpr(emit.key(), emitFixture.substitutions(), emitFixture.nonDeterministicValues()));
            if (emit.payload() != null) {
                KafkaPayloadModel model = deterministicPayload(emit.payload(), emitFixture);
                modelEmit.put("payloadJson", jsonEscape(model.payloadJson()));
                modelEmit.put("serverGeneratedFields", model.serverGeneratedFields());
                modelEmit.put("substitutionFields", model.substitutionFields());
            }
            kafkaEmits.add(modelEmit);
        }

        Map<String, Object> postCreateCleanup = postCreateCleanup(
                endpoint.httpMethod(), path.expectedStatus(), path.outcome(), sql, client.tables(),
                fixture.assertions(), fixture.deletes());
        return new ScenarioMethod(
                deriveMethodName(endpoint.id(), path.id()),
                fixture.vars(), fixture.inserts(), fixture.deletes(),
                mocks.block(), readPath, bodyExpr, endpoint.httpMethod().toLowerCase(),
                resolveLiteralPath(endpoint, path.sampleInput(), readPath && path.expectedStatus() == 404),
                path.expectedStatus(),
                assertionsBlock, endpoint.authRequired(),
                mocks.propagationMissing(), path.validationWarnings(), kafkaEmits, postCreateCleanup);
    }

    static String deriveMethodName(String endpointId, String pathId) {
        String rest = pathId.startsWith(endpointId + "-")
                ? pathId.substring(endpointId.length() + 1) : pathId;
        // 모든 비식별자 문자를 '_'로 (단순히 '-'만이 아니라) → 어떤 path id든 유효한 Java 식별자 보장
        String name = rest.replaceAll("[^A-Za-z0-9]", "_");
        if (name.isEmpty() || Character.isDigit(name.charAt(0))) {
            name = "s_" + name;
        }
        return name;
    }

    private GeneratedFile renderTestClass(GenerationRequest request, Endpoint endpoint,
                                          String className, List<ScenarioMethod> methods,
                                          boolean classSerial) {
        List<String> baseNames = new ArrayList<>();
        for (ScenarioMethod m : methods) {
            baseNames.add(m.methodName());
        }
        List<String> uniqueNames = uniqueMethodNames(baseNames);
        List<Map<String, Object>> methodScopes = new ArrayList<>();
        for (int idx = 0; idx < methods.size(); idx++) {
            ScenarioMethod m = methods.get(idx);
            Map<String, Object> ms = new HashMap<>();
            ms.put("methodName", uniqueNames.get(idx));
            ms.put("vars", m.vars());
            ms.put("inserts", m.inserts());
            ms.put("deletes", m.deletes());
            ms.put("mocksBlock", m.mocksBlock());
            ms.put("readPath", m.readPath());
            ms.put("bodyExpr", m.bodyExpr());
            ms.put("httpMethodLower", m.httpMethodLower());
            ms.put("requestPath", m.requestPath());
            ms.put("expectedStatus", m.expectedStatus());
            ms.put("assertionsBlock", m.assertionsBlock());
            ms.put("authRequired", m.authRequired());
            ms.put("kafkaEmits", m.kafkaEmits());
            ms.put("postCreateCleanup", m.postCreateCleanup());
            methodScopes.add(ms);
        }

        Map<String, Object> scope = new HashMap<>();
        scope.put("packageName", request.packageName());
        scope.put("className", className);
        scope.put("httpMethod", endpoint.httpMethod());
        scope.put("endpointPath", endpoint.path());
        scope.put("endpointId", endpoint.id());
        scope.put("methodCount", methods.size());
        scope.put("methods", methodScopes);
        scope.put("classSerialMark", classSerial ? CLASS_SERIAL_MARK : "");
        scope.put("serialImports", classSerial ? SERIAL_IMPORTS : "");

        StringWriter writer = new StringWriter();
        template.execute(writer, scope);
        return new GeneratedFile(
                request.packageName().replace('.', '/') + "/" + className + ".java",
                writer.toString());
    }

    /** 중복 메서드명을 _2, _3… 접미사로 고유화 (동일 base의 2번째부터). */
    static List<String> uniqueMethodNames(List<String> baseNames) {
        java.util.Set<String> used = new java.util.HashSet<>();
        List<String> result = new ArrayList<>(baseNames.size());
        for (String name : baseNames) {
            String unique = name;
            for (int i = 2; !used.add(unique); i++) {
                unique = name + "_" + i;
            }
            result.add(unique);
        }
        return result;
    }

    /** STOMP exchange → 테스트 클래스 합성 (3.3). */
    private GenerationResult generateWs(GenerationRequest request) {
        io.graphrag.model.WsEndpoint endpoint = client.wsEndpoint(request.endpointId());
        List<io.graphrag.model.WsExchange> exchanges = request.pathId() != null
                ? List.of(client.wsExchange(request.pathId()))
                : client.wsExchangesFor(endpoint.id());

        List<GeneratedFile> files = new ArrayList<>();
        List<String> fullyParallel = new ArrayList<>();
        List<io.graphrag.model.SerialRequired> serialRequired = new ArrayList<>();
        for (io.graphrag.model.WsExchange exchange : exchanges) {
            String className = request.pathId() != null
                    ? request.testClassName()
                    : request.testClassName() + classSuffix(endpoint.id(), exchange.id());
            List<CapturedSql> sql = client.sqlForPath(exchange.id());
            // 픽스처/치환 규칙 재사용을 위한 pseudo-path (응답 수신=200으로 간주)
            ExploredPath pseudo = new ExploredPath(
                    exchange.id(), endpoint.id(), exchange.payload(),
                    exchange.response() != null && !exchange.response().isNull() ? 200 : 0,
                    exchange.response(), exchange.capturedSqlIds(),
                    List.of(), List.of(), "ws-capture", List.of(), List.of(), List.of());
            ComposedFixture fixture = new FixtureComposer().compose(pseudo, sql, client.tables());

            // 상관관계 마커: 응답이 치환 필드의 캡처 값을 echo하면 그 변수로 자기 메시지 식별
            String markerExpr = "\"\"";
            boolean correlated = false;
            String responseText = exchange.response() == null ? "" : exchange.response().toString();
            for (ComposedFixture.Var var : fixture.vars()) {
                var sampleValue = exchange.payload().get(var.name());
                if (sampleValue != null && sampleValue.isTextual()
                        && responseText.contains(sampleValue.asText())) {
                    markerExpr = var.name();
                    correlated = true;
                    break;
                }
            }

            StringBuilder wsAssertions = new StringBuilder();
            if (exchange.response() != null && !exchange.response().isNull()) {
                exchange.response().fieldNames().forEachRemaining(field ->
                        wsAssertions.append("\n        assertTrue(response.has(\"")
                                .append(field).append("\"));"));
            }

            Map<String, Object> scope = new HashMap<>();
            scope.put("packageName", request.packageName());
            scope.put("className", className);
            scope.put("wsEndpointId", endpoint.id());
            scope.put("exchangeId", exchange.id());
            scope.put("wsPath", endpoint.wsPath());
            scope.put("sendDestination", endpoint.appPrefix() + endpoint.destination());
            scope.put("subscribeDestination", endpoint.sendTo());
            scope.put("testMethodName", exchange.id().replace('-', '_'));
            scope.put("vars", fixture.vars());
            scope.put("inserts", fixture.inserts());
            scope.put("deletes", fixture.deletes());
            scope.put("bodyExpr", bodyExpr(fixture));
            scope.put("markerExpr", markerExpr);
            scope.put("wsAssertionsBlock", wsAssertions.toString());
            scope.put("serialMark", correlated ? "" : "@Execution(ExecutionMode.SAME_THREAD)\n");
            scope.put("serialImports", correlated ? ""
                    : "import org.junit.jupiter.api.parallel.Execution;\n"
                    + "import org.junit.jupiter.api.parallel.ExecutionMode;\n");

            StringWriter writer = new StringWriter();
            wsTemplate.execute(writer, scope);
            files.add(new GeneratedFile(
                    request.packageName().replace('.', '/') + "/" + className + ".java",
                    writer.toString()));
            if (correlated) {
                fullyParallel.add(className);
            } else {
                serialRequired.add(new io.graphrag.model.SerialRequired(className,
                        "WS_NO_CORRELATION", "응답이 치환 값을 echo하지 않아 broadcast 메시지를 구분할 수 없음"));
            }
        }
        return new GenerationResult(files, List.of(),
                new ParallelSafetyReport(fullyParallel, serialRequired));
    }

    /** @KafkaListener consumer: 토픽 발행 후 consumer의 INSERT side-effect를 폴링 단언하는 테스트 생성. */
    private GenerationResult generateKafka(GenerationRequest request) {
        io.graphrag.model.KafkaConsumer consumer = client.kafkaConsumer(request.endpointId());
        List<io.graphrag.model.KafkaExchange> exchanges = client.kafkaExchangesFor(consumer.id());

        List<GeneratedFile> files = new ArrayList<>();
        List<io.graphrag.model.SerialRequired> serialRequired = new ArrayList<>();
        for (io.graphrag.model.KafkaExchange exchange : exchanges) {
            if (exchange.variant()) {
                continue;   // 반대-arm 커버용 변종 교환(결측/중복)은 테스트 생성 대상 아님
            }
            String className = request.testClassName() + classSuffix(consumer.id(), exchange.id());

            // consumer가 쓴 INSERT의 키 컬럼으로 side-effect를 단언한다. 키는 **PK 컬럼 우선**
            // (cleanup DELETE 과다 삭제 + 비-고유 컬럼 count>0 false-green 방지), 없으면 첫 API_PARAM.
            String table = null;
            String keyColumn = null;
            String keyValue = null;
            for (CapturedSql s : client.sqlForPath(exchange.id())) {
                if (!s.sqlKind().equals("INSERT")) {
                    continue;
                }
                String pkColumn = primaryKeyColumn(s.tableName());
                io.graphrag.model.SqlBinding chosen = null;
                if (pkColumn != null) {
                    chosen = s.bindings().stream()
                            .filter(b -> pkColumn.equals(b.column())).findFirst().orElse(null);
                }
                if (chosen == null) {
                    chosen = s.bindings().stream()
                            .filter(b -> b.origin() == io.graphrag.model.BindingOrigin.API_PARAM
                                    && b.column() != null && !b.column().isEmpty())
                            .findFirst().orElse(null);
                }
                if (chosen != null) {
                    table = s.tableName();
                    keyColumn = chosen.column();
                    keyValue = chosen.value();
                    break;
                }
            }

            String key = exchange.payload().has("userId")
                    ? exchange.payload().get("userId").asText()
                    : (exchange.payload().has("eventId") ? exchange.payload().get("eventId").asText() : exchange.id());

            Map<String, Object> scope = new HashMap<>();
            scope.put("packageName", request.packageName());
            scope.put("className", className);
            scope.put("consumerId", consumer.id());
            scope.put("topic", consumer.topic());
            scope.put("testMethodName", exchange.id().replace('-', '_'));
            scope.put("key", jsonEscape(key));
            scope.put("payloadJson", jsonEscape(exchange.payload().toString()));
            scope.put("hasAssert", table != null);
            if (table != null) {
                scope.put("table", table);
                scope.put("keyColumn", keyColumn);
                scope.put("keyValueExpr", "\"" + jsonEscape(keyValue) + "\"");
            }

            StringWriter writer = new StringWriter();
            kafkaTemplate.execute(writer, scope);
            files.add(new GeneratedFile(
                    request.packageName().replace('.', '/') + "/" + className + ".java",
                    writer.toString()));
            // Kafka consumer는 공유 토픽 — 격리 위해 직렬 실행.
            serialRequired.add(new io.graphrag.model.SerialRequired(className,
                    "KAFKA_SHARED_TOPIC", "Kafka consumer는 공유 토픽이라 격리 불가 — 직렬 실행"));
        }
        return new GenerationResult(files, List.of(),
                new ParallelSafetyReport(List.of(), serialRequired));
    }

    private static String jsonEscape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * emit key를 consumeNextRecord의 expectedKey 표현식으로 변환한다.
     * 입력 유래 값(치환 대상)이면 테스트 런타임 변수로, 비결정 값이면 null로(필터 없음),
     * 리터럴로 필터링하면 런타임에 생성되는 실제 key와 영영 불일치해 단언이 깨지기 때문이다.
     * 그 외 결정적 리터럴 key만 따옴표 문자열로 필터링한다.
     */
    private static String emitKeyExpr(String key, Map<String, String> substitutions,
                                      Set<String> nonDeterministicValues) {
        if (key == null) {
            return "null";
        }
        String var = substitutions.get(key);
        if (var != null) {
            return var;
        }
        if (nonDeterministicValues.contains(key)) {
            return "null";
        }
        return "\"" + jsonEscape(key) + "\"";
    }

    /**
     * Kafka emit payload 필드 분류 결과를 담는 캐리어.
     * payloadJson: 결정적/입력유래/서버생성 필드만 남긴 JSON (DB-PK는 제거됨).
     * serverGeneratedFields: UUID/타임스탬프로 판단된 필드 목록 (field + regex).
     * substitutionFields: 입력 유래 치환 필드 목록 (field + var).
     */
    private record KafkaPayloadModel(
            String payloadJson,
            List<Map<String, String>> serverGeneratedFields,
            List<Map<String, String>> substitutionFields
    ) {}

    /**
     * REQ-012: fixture의 nonDeterministicValues에 emit별 diff-검출 값을 병합한 새 fixture를 반환한다.
     * additionalValues가 비어 있으면 기존 fixture를 그대로 반환(불변 최적화).
     */
    private static ComposedFixture mergeNonDeterministicValues(ComposedFixture fixture,
                                                               java.util.Set<String> additionalValues) {
        if (additionalValues == null || additionalValues.isEmpty()) {
            return fixture;
        }
        java.util.Set<String> merged = new java.util.HashSet<>(fixture.nonDeterministicValues());
        merged.addAll(additionalValues);
        return new ComposedFixture(fixture.vars(), fixture.inserts(), fixture.deletes(),
                fixture.bodyFormat(), fixture.bodyArgExprs(), fixture.assertions(),
                fixture.substitutions(), java.util.Set.copyOf(merged));
    }

    /**
     * emit payload 필드를 4가지로 분류한다.
     * - input-derived (치환 대상): payloadJson에 유지 + substitutionFields 항목 추가
     * - DB-PK (비결정): payloadJson에서 제거 (기존 동작 유지)
     * - server-generated (UUID/타임스탬프): payloadJson에 유지 + serverGeneratedFields 항목 추가
     * - deterministic: payloadJson에 유지 (JSONAssert LENIENT literal equals)
     */
    private static KafkaPayloadModel deterministicPayload(com.fasterxml.jackson.databind.JsonNode payload,
                                                          ComposedFixture fixture) {
        if (!(payload instanceof com.fasterxml.jackson.databind.node.ObjectNode obj)) {
            return new KafkaPayloadModel(payload.toString(), List.of(), List.of());
        }
        com.fasterxml.jackson.databind.node.ObjectNode out = obj.deepCopy();
        List<String> toRemove = new ArrayList<>();
        List<Map<String, String>> serverGeneratedFields = new ArrayList<>();
        List<Map<String, String>> substitutionFields = new ArrayList<>();

        out.fields().forEachRemaining(e -> {
            if (!e.getValue().isTextual()) {
                return;
            }
            String fieldName = e.getKey();
            String v = e.getValue().asText();

            String substitutionExpr = fixture.substitutions().get(v);
            if (substitutionExpr != null) {
                // input-derived: keep in payloadJson, record substitution entry
                substitutionFields.add(Map.of("field", fieldName, "var", substitutionExpr));
            } else if (fixture.nonDeterministicValues().contains(v)) {
                // DB-PK: remove from payloadJson
                toRemove.add(fieldName);
            } else if (io.graphrag.generator.compose.ServerGeneratedDetector.looksServerGenerated(v)) {
                // server-generated: keep in payloadJson, record regex entry
                String type = io.graphrag.generator.compose.ServerGeneratedDetector.patternType(v);
                String regex = javaSourceRegex(
                        io.graphrag.generator.compose.ServerGeneratedDetector.regexFor(type));
                serverGeneratedFields.add(Map.of("field", fieldName, "regex", regex));
            }
            // else: deterministic — keep in payloadJson as-is
        });
        toRemove.forEach(out::remove);
        return new KafkaPayloadModel(out.toString(), serverGeneratedFields, substitutionFields);
    }

    /**
     * regexFor()가 반환하는 raw 정규식 문자열을 Java 소스 문자열 리터럴로 삽입 가능한 형태로 이스케이프한다.
     * 예: raw "\d{4}" → Java 소스 "\\d{4}" (백슬래시를 이중으로).
     */
    private static String javaSourceRegex(String rawRegex) {
        return rawRegex.replace("\\", "\\\\");
    }

    /** 테이블의 PK 컬럼명 (Kafka side-effect 단언 키 우선순위). 없으면 null. */
    private String primaryKeyColumn(String tableName) {
        return client.tables().stream()
                .filter(t -> t.name().equals(tableName))
                .flatMap(t -> t.columns().stream())
                .filter(io.graphrag.model.ColumnSchema::primaryKey)
                .map(io.graphrag.model.ColumnSchema::name)
                .findFirst().orElse(null);
    }

    private static String classSuffix(String endpointId, String pathId) {
        String rest = pathId.startsWith(endpointId + "-")
                ? pathId.substring(endpointId.length() + 1)
                : pathId;
        return "_" + rest.toUpperCase().replaceAll("[^A-Z0-9]", "_");
    }

    /** sampleInput에서 path/query param을 제외한 나머지를 JSON body로 직렬화. */
    private static String jsonBodyFromInput(Endpoint endpoint, JsonNode input) {
        // collection body(JSON 배열)는 path/query 제거 대상이 아님 → 그대로 직렬화.
        if (input instanceof com.fasterxml.jackson.databind.node.ArrayNode) {
            return input.toString();
        }
        if (!(input instanceof com.fasterxml.jackson.databind.node.ObjectNode obj)) {
            return "{}";
        }
        com.fasterxml.jackson.databind.node.ObjectNode body = obj.deepCopy();
        for (EndpointParam p : endpoint.params()) {
            if (p.kind() == ParamKind.PATH || p.kind() == ParamKind.QUERY) {
                body.remove(p.name());
            }
        }
        return body.toString();
    }

    /** check_in_date → checkInDate (시드 컬럼 → 응답 필드명 매칭용). */
    private static String snakeToCamel(String s) {
        StringBuilder out = new StringBuilder();
        boolean up = false;
        for (char c : s.toCharArray()) {
            if (c == '_') {
                up = true;
            } else {
                out.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return out.toString();
    }

    private static String bodyExpr(ComposedFixture fixture) {
        String literal = "\"" + fixture.bodyFormat().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        if (fixture.bodyArgExprs().isEmpty()) {
            return literal;
        }
        return "String.format(" + literal + ", " + String.join(", ", fixture.bodyArgExprs()) + ")";
    }

    /**
     * IDENTITY/시퀀스 PK가 한 테스트 런에서 도달 불가능한 큰 id (Integer 범위 내라 int path param에도 안전).
     * absent-id read(404)가 이 값을 쓰면, 병렬로 도는 성공 POST가 만드는 작은 IDENTITY id(1,2,3…)와
     * 충돌하지 않아 '부재' 단언이 결정적으로 유지된다 (PR #62 flaky 수정, Fix#1).
     */
    static final String ABSENT_NUMERIC_ID = "2000000000";

    /**
     * notFoundRead = 이 시나리오가 'id 부재'를 전제하는 read(404)인지. true이면 numeric path id를
     * 캡처된 작은 probe 값 대신 ABSENT_NUMERIC_ID로 치환해 병렬 race를 제거한다(테스트 의도=404는 보존).
     */
    static String resolveLiteralPath(Endpoint endpoint, JsonNode input, boolean notFoundRead) {
        String path = endpoint.path();
        StringBuilder query = new StringBuilder();
        for (EndpointParam p : endpoint.params()) {
            if (p.kind() == ParamKind.PATH) {
                String v;
                if (notFoundRead && isNumericParam(p)) {
                    v = ABSENT_NUMERIC_ID;   // 캡처 id 무시: 404는 도달불가 id로 확정 부재 보장
                } else {
                    // 404 등 path param이 누락된 read-path도 유효한 URL을 만들어야 한다.
                    // 빌더의 buildPathAndQuery와 동일한 센티널로 {id} 미바인딩 오류를 방지한다.
                    // 입력값이 없거나 blank(빈 문자열·공백)이면 sentinel을 사용한다.
                    // blank 입력을 그대로 치환하면 double-slash가 생겨 캡처(explorer)와
                    // 재현(generator) 간 경로 불일치가 발생한다(s404_2 버그). REQ-018.
                    String rawV = input.has(p.name()) ? input.get(p.name()).asText() : null;
                    v = (rawV == null || rawV.isBlank()) ? pathSentinel(p, notFoundRead) : rawV;
                }
                path = path.replace("{" + p.name() + "}", v);
            } else if (p.kind() == ParamKind.QUERY && input.has(p.name())) {
                query.append(query.isEmpty() ? "?" : "&").append(p.name())
                        .append("=").append(input.get(p.name()).asText());
            }
        }
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

    private static boolean isNumericParam(EndpointParam param) {
        return switch (param.javaType()) {
            case "java.lang.Integer", "int", "java.lang.Long", "long",
                 "java.math.BigInteger", "short", "java.lang.Short" -> true;
            default -> false;
        };
    }

    private static String pathSentinel(EndpointParam param, boolean notFoundRead) {
        return switch (param.javaType()) {
            // numeric path param 누락 시: 404 read면 도달불가 id, 그 외엔 기존처럼 0.
            case "java.lang.Integer", "int", "java.lang.Long", "long" -> notFoundRead ? ABSENT_NUMERIC_ID : "0";
            default -> "missing";
        };
    }

    /**
     * Fix#3: 성공 POST(2xx)가 IDENTITY(autoIncrement) 단일 PK 행을 만들고, 그 PK가 응답에 돌아오며,
     * 그 테이블에 대한 param-bound cleanup이 아직 없을 때만 — 응답 PK를 캡처해 deferDelete를 등록하기
     * 위한 모델(table/pkColumn/pkField)을 반환한다. 그 외에는 null(템플릿이 정리 블록을 생략).
     *
     * 잔류 IDENTITY row(특히 id=1,2,3…)는 병렬로 도는 absent-id read(404)의 '부재' 가정을 깰 수 있다
     * (Fix#1의 보조 위생). autoIncrement PK가 아니면 트리거하지 않으므로 기존 골든/일반 경로는 불변.
     */
    static Map<String, Object> postCreateCleanup(String httpMethod, int expectedStatus,
            Outcome.Kind outcome,
            List<CapturedSql> sqlList, List<TableSchema> tables,
            List<ComposedFixture.Assertion> assertions, List<ComposedFixture.Stmt> existingDeletes) {
        if (!"POST".equals(httpMethod) || outcome != Outcome.Kind.SUCCESS) {
            return null;
        }
        Map<String, TableSchema> byName = new HashMap<>();
        tables.forEach(t -> byName.put(t.name(), t));
        for (CapturedSql s : sqlList) {
            if (!"INSERT".equals(s.sqlKind())) {
                continue;
            }
            TableSchema t = byName.get(s.tableName());
            if (t == null) {
                continue;
            }
            List<ColumnSchema> pks = t.columns().stream().filter(ColumnSchema::primaryKey).toList();
            if (pks.size() != 1 || !pks.get(0).autoIncrement()) {
                continue;   // 복합 PK 또는 비-IDENTITY PK는 응답 id로 결정적 정리 불가 → 건너뜀
            }
            ColumnSchema pk = pks.get(0);
            String pkField = snakeToCamel(pk.name());
            // 응답이 그 PK를 돌려주지 않으면 런타임에 추출할 값이 없다 → 정리 불가.
            if (assertions.stream().noneMatch(a -> a.jsonPath().equals(pkField))) {
                continue;
            }
            // 이미 이 테이블을 param-bound로 정리하면 IDENTITY row도 함께 지워지므로 보강 불필요.
            if (existingDeletes.stream().anyMatch(d -> d.sql().startsWith("DELETE FROM " + t.name() + " "))) {
                return null;
            }
            Map<String, Object> m = new HashMap<>();
            m.put("table", t.name());
            m.put("pkColumn", pk.name());
            m.put("pkField", pkField);
            return m;
        }
        return null;
    }
}
