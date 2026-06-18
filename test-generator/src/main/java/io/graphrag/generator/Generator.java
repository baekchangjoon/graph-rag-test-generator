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
import io.graphrag.model.RequiredSeed;
import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GeneratedFile;
import io.graphrag.model.GenerationRequest;
import io.graphrag.model.GenerationResult;
import io.graphrag.model.ParallelSafetyReport;
import io.graphrag.model.ParamKind;

import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        if (request.pathId() != null) {
            return generateSingle(request, request.testClassName(), request.pathId());
        }
        List<GeneratedFile> files = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> fullyParallel = new ArrayList<>();
        List<io.graphrag.model.SerialRequired> serialRequired = new ArrayList<>();
        for (ExploredPath path : client.pathsForEndpoint(request.endpointId())) {
            if ("negative-auth".equals(path.discoveredBy())
                    || "negative-validation".equals(path.discoveredBy())) {
                continue;   // 부정-인증/부정-검증 커버용 path(거부 arm)는 테스트 생성 대상 아님(커버리지 전용)
            }
            String className = request.testClassName() + classSuffix(endpoint.id(), path.id());
            GenerationResult single = generateSingle(request, className, path.id());
            files.addAll(single.files());
            warnings.addAll(single.warnings());
            fullyParallel.addAll(single.parallelSafety().fullyParallel());
            serialRequired.addAll(single.parallelSafety().serialRequired());
        }
        return new GenerationResult(files, warnings,
                new ParallelSafetyReport(fullyParallel, serialRequired));
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
     * emit key를 consumeNextRecord의 expectedKey 인자 표현식으로 변환한다.
     * 입력 유래 값(예: userId)은 테스트 런타임 변수명으로 치환해 자기 발행 레코드만 소비하게 한다.
     * key가 없거나 DB 시퀀스 PK처럼 비결정 값이면 null(필터 없음)로 둔다 — 캡처 시점의 stale
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
     * emit payload에서 비결정 필드(입력 유래 치환값, DB 시퀀스 PK)를 제거해 결정적 필드만 남긴다.
     * 남은 JSON은 JSONAssert LENIENT로 비교하므로, 환경/테스트마다 달라지는 값에 의한 거짓 실패를 막는다.
     */
    private static String deterministicPayload(com.fasterxml.jackson.databind.JsonNode payload,
                                               ComposedFixture fixture) {
        if (!(payload instanceof com.fasterxml.jackson.databind.node.ObjectNode obj)) {
            return payload.toString();
        }
        com.fasterxml.jackson.databind.node.ObjectNode out = obj.deepCopy();
        List<String> toRemove = new ArrayList<>();
        out.fields().forEachRemaining(e -> {
            if (e.getValue().isTextual()) {
                String v = e.getValue().asText();
                if (fixture.substitutions().containsKey(v) || fixture.nonDeterministicValues().contains(v)) {
                    toRemove.add(e.getKey());
                }
            }
        });
        toRemove.forEach(out::remove);
        return out.toString();
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

    private GenerationResult generateSingle(GenerationRequest request, String className,
                                            String pathId) {
        Endpoint endpoint = client.endpoint(request.endpointId());
        ExploredPath path = client.path(pathId);
        List<CapturedSql> sql = client.sqlForPath(pathId);

        boolean readPath = endpoint.httpMethod().equals("GET");
        // 응답 필드명 → 결정적 기대값(요청 입력 필드 + 시드 컬럼 camelCase). 응답 필드가 같은 이름의
        // 입력/시드 값과 일치하면 equalTo, 서버 생성(시퀀스 id/count/timestamp)은 여기 없어 notNull.
        java.util.Map<String, String> knownByField = new java.util.HashMap<>();
        // collection body(배열)면 원소 객체에서 결정적 필드를 도출(collection-of-DTO). scalar/빈 배열은
        // 객체가 아니라 knownByField 비움 → 응답 단언은 notNullValue로 폴백(허용).
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
        for (RequiredSeed s : client.seedsForPath(pathId)) {
            for (int i = 0; i < s.columns().size() && i < s.values().size(); i++) {
                knownByField.putIfAbsent(snakeToCamel(s.columns().get(i)), s.values().get(i));
            }
        }
        ComposedFixture fixture = new FixtureComposer().compose(path, sql, client.tables(),
                client.seedsForPath(pathId), readPath, knownByField);
        HttpMockComposer.ComposedMocks mocks =
                new HttpMockComposer().compose(client.httpCallsForPath(pathId));

        Map<String, Object> scope = new HashMap<>();
        scope.put("packageName", request.packageName());
        scope.put("className", className);
        scope.put("httpMethod", endpoint.httpMethod());
        scope.put("httpMethodLower", endpoint.httpMethod().toLowerCase());
        scope.put("endpointPath", endpoint.path());
        // 모든 method에서 PATH/QUERY param을 치환한다. write-path(PUT/DELETE /x/{id})도
        // {id}를 빌더 탐색과 동일한 센티널/입력값으로 바인딩해야 RestAssured 미바인딩 오류를 막는다.
        scope.put("requestPath", resolveLiteralPath(endpoint, path.sampleInput()));
        scope.put("readPath", readPath);
        scope.put("endpointId", endpoint.id());
        scope.put("pathId", path.id());
        scope.put("testMethodName", path.id().replace('-', '_'));
        scope.put("expectedStatus", path.expectedStatus());
        scope.put("vars", fixture.vars());
        scope.put("inserts", fixture.inserts());
        scope.put("deletes", fixture.deletes());
        scope.put("assertionsBlock", fixture.assertions().stream()
                .map(a -> "\n            .body(\"" + a.jsonPath() + "\", " + a.matcher() + ")")
                .reduce("", String::concat));
        // seeds-브랜치(by-id 등)는 bodyFormat을 안 채운다. body를 갖는 메서드(POST/PUT/PATCH)면
        // 실제 보낸 body(sampleInput에서 path/query 제외)를 직렬화해 요청 body로 쓴다. 빈 객체 "{}"도
        // 그대로 보내야 컨트롤러 검증(예: "at least one of ...")이 재현된다(빈 문자열 → 일반 400 방지).
        String bodyExpr = bodyExpr(fixture);
        boolean methodHasBody = endpoint.httpMethod().equals("POST")
                || endpoint.httpMethod().equals("PUT") || endpoint.httpMethod().equals("PATCH");
        if (methodHasBody && fixture.bodyFormat().isEmpty()) {
            String json = jsonBodyFromInput(endpoint, path.sampleInput());
            bodyExpr = "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        scope.put("bodyExpr", bodyExpr);
        scope.put("authRequired", endpoint.authRequired());
        scope.put("mocksBlock", mocks.block());
        
        List<io.graphrag.model.CapturedEventEmit> kafkaEvents = client.capturedEventEmitsForPath(pathId);
        if (!kafkaEvents.isEmpty()) {
            scope.put("hasKafkaEmits", true);
            List<Map<String, Object>> kafkaEmits = new ArrayList<>();
            for (io.graphrag.model.CapturedEventEmit emit : kafkaEvents) {
                Map<String, Object> modelEmit = new HashMap<>();
                modelEmit.put("topic", emit.topic());
                // key는 consumeNextRecord의 expectedKey 인자로 쓴다(공유 토픽 오염 격리).
                // 입력 유래 값이면 테스트 런타임 변수로 치환, 일반 리터럴이면 따옴표 문자열.
                modelEmit.put("keyExpr", emitKeyExpr(emit.key(), fixture.substitutions(), fixture.nonDeterministicValues()));
                if (emit.payload() != null) {
                    modelEmit.put("payloadJson", jsonEscape(deterministicPayload(emit.payload(), fixture)));
                }
                kafkaEmits.add(modelEmit);
            }
            scope.put("kafkaEmits", kafkaEmits);
        }

        // 격리 불가(SUT propagation 부재) → 직렬 실행 마크 (docs/04)
        scope.put("serialMark", mocks.propagationMissing()
                ? "@Execution(ExecutionMode.SAME_THREAD)\n" : "");
        scope.put("serialImports", mocks.propagationMissing()
                ? "import org.junit.jupiter.api.parallel.Execution;\n"
                + "import org.junit.jupiter.api.parallel.ExecutionMode;\n" : "");

        StringWriter writer = new StringWriter();
        template.execute(writer, scope);

        String relativePath = request.packageName().replace('.', '/')
                + "/" + className + ".java";
        ParallelSafetyReport safety = mocks.propagationMissing()
                ? new ParallelSafetyReport(List.of(), List.of(new io.graphrag.model.SerialRequired(
                        className, "SUT_PROPAGATION_MISSING",
                        "외부 HTTP 호출에 baggage가 전파되지 않음 — OTEL agent 부착 또는 직렬 실행 필요")))
                : new ParallelSafetyReport(List.of(className), List.of());
        return new GenerationResult(
                List.of(new GeneratedFile(relativePath, writer.toString())),
                path.validationWarnings(),
                safety);
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

    private static String resolveLiteralPath(Endpoint endpoint, JsonNode input) {
        String path = endpoint.path();
        StringBuilder query = new StringBuilder();
        for (EndpointParam p : endpoint.params()) {
            if (p.kind() == ParamKind.PATH) {
                // 404 등 path param이 누락된 read-path도 유효한 URL을 만들어야 한다.
                // 빌더의 buildPathAndQuery와 동일한 센티널로 {id} 미바인딩 오류를 방지한다.
                String v = input.has(p.name()) ? input.get(p.name()).asText() : pathSentinel(p);
                path = path.replace("{" + p.name() + "}", v);
            } else if (p.kind() == ParamKind.QUERY && input.has(p.name())) {
                query.append(query.isEmpty() ? "?" : "&").append(p.name())
                        .append("=").append(input.get(p.name()).asText());
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
}
