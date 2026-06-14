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

/**
 * 도구 2 본체. LLM 없음 — 동일 입력은 항상 동일 출력 (docs/04).
 * 큰 골격: Mustache 템플릿. 가변 슬롯: FixtureComposer.
 */
public class Generator {

    private final GraphRagClient client;
    private final Mustache template;
    private final Mustache wsTemplate;

    public Generator(Path graphDir) {
        this(new FileGraphRagClient(graphDir));
    }

    public Generator(GraphRagClient client) {
        this.client = client;
        DefaultMustacheFactory factory = new DefaultMustacheFactory();
        this.template = factory.compile("templates/test-class.mustache");
        this.wsTemplate = factory.compile("templates/ws-test-class.mustache");
    }

    /** pathId 미지정 시 endpoint의 전 path에 대해 path당 테스트 클래스 1개씩 생성 (1.5). */
    public GenerationResult generate(GenerationRequest request) {
        if (client.hasWsEndpoint(request.endpointId())) {
            return generateWs(request);
        }
        if (request.pathId() != null) {
            return generateSingle(request, request.testClassName(), request.pathId());
        }
        Endpoint endpoint = client.endpoint(request.endpointId());
        List<GeneratedFile> files = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> fullyParallel = new ArrayList<>();
        List<io.graphrag.model.SerialRequired> serialRequired = new ArrayList<>();
        for (ExploredPath path : client.pathsForEndpoint(request.endpointId())) {
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
        ComposedFixture fixture = new FixtureComposer().compose(path, sql, client.tables(),
                client.seedsForPath(pathId));
        HttpMockComposer.ComposedMocks mocks =
                new HttpMockComposer().compose(client.httpCallsForPath(pathId));

        Map<String, Object> scope = new HashMap<>();
        scope.put("packageName", request.packageName());
        scope.put("className", className);
        scope.put("httpMethod", endpoint.httpMethod());
        scope.put("httpMethodLower", endpoint.httpMethod().toLowerCase());
        scope.put("endpointPath", endpoint.path());
        scope.put("requestPath", readPath ? resolveLiteralPath(endpoint, path.sampleInput()) : endpoint.path());
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
        scope.put("bodyExpr", bodyExpr(fixture));
        scope.put("authRequired", endpoint.authRequired());
        scope.put("mocksBlock", mocks.block());
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
