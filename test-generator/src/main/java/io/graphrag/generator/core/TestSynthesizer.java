package io.graphrag.generator.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.generator.compose.FixtureComposer;
import io.graphrag.generator.compose.FixtureStatement;
import io.graphrag.generator.compose.http.HttpStubComposer;
import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.HttpMethod;
import io.graphrag.model.JsonMappers;
import io.graphrag.model.SampleInput;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 결정적 RestAssured 테스트 합성기 (Phase 0).
 *
 * <p>{@link SynthesisInput}을 받아 Java 테스트 클래스 소스 코드를 문자열로 반환.
 * LLM 사용 없음. 입력이 같으면 출력이 같다.
 *
 * <p>Phase 1+ 확장:
 * <ul>
 *   <li>HTTP/Socket mock composer 결과 통합
 *   <li>다양한 메소드 (PUT/PATCH/DELETE) + 다양한 body 형식
 *   <li>baggage propagation 헤더 자동 부착
 *   <li>인증 모드 분기 (real/disabled)
 * </ul>
 */
public final class TestSynthesizer {

    private TestSynthesizer() {}

    public static String synthesize(SynthesisInput input) {
        Endpoint ep = input.endpoint();
        String className = deriveClassName(ep);
        List<FixtureStatement> fixtures = FixtureComposer.fromCapturedSqls(input.capturedSql());
        List<FixtureStatement> cleanup = FixtureComposer.cleanupFor(input.capturedSql());

        StringBuilder sb = new StringBuilder();
        appendHeader(sb, input.testPackage());
        appendClassOpen(sb, className);
        appendStaticFields(sb);
        appendBeforeAll(sb);
        appendBeforeEach(sb, fixtures);
        appendAfterEach(sb, cleanup);
        appendTestMethod(sb, ep);
        appendClassClose(sb);

        return sb.toString();
    }

    /**
     * 멀티-path 합성: 한 클래스에 N개 @Test 메소드. 각 메소드는 자기 path의 fixture/cleanup을
     * try-finally로 인라인 보유. capturedHttpCalls가 있는 path는 WireMock stub 등록도 포함.
     */
    public static String synthesizeMulti(MultiPathSynthesisInput input) {
        Endpoint ep = input.endpoint();
        String className = deriveClassName(ep);
        boolean hasHttp = input.paths().stream()
                .anyMatch(pc -> !pc.capturedHttpCalls().isEmpty());
        boolean hasSocket = input.paths().stream()
                .anyMatch(pc -> !pc.capturedSocketIO().isEmpty());

        StringBuilder sb = new StringBuilder();
        appendHeader(sb, input.testPackage(), hasHttp);
        appendClassOpen(sb, className);
        appendStaticFieldsMulti(sb);
        if (hasSocket) {
            appendSocketHelpers(sb);
        }
        appendBeforeAllMulti(sb, hasHttp);
        for (PathContext pc : input.paths()) {
            appendPathTestMethod(sb, ep, pc);
        }
        appendClassClose(sb);
        return sb.toString();
    }

    private static void appendSocketHelpers(StringBuilder sb) {
        sb.append("    static String SOCKET_MOCK_ADMIN = System.getenv(\"SOCKET_MOCK_ADMIN\");\n\n");
        sb.append("    private static void registerSocketExpectation(int port, String sessionId,\n");
        sb.append("                                                  String onReceiveHex, String respondHex) {\n");
        sb.append("        try {\n");
        sb.append("            String body = String.format(\n");
        sb.append("                \"{\\\"port\\\":%d,\\\"sessionId\\\":\\\"%s\\\",\\\"onReceiveHex\\\":\\\"%s\\\",\\\"respondHex\\\":\\\"%s\\\"}\",\n");
        sb.append("                port, sessionId, onReceiveHex, respondHex);\n");
        sb.append("            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()\n");
        sb.append("                .uri(java.net.URI.create(SOCKET_MOCK_ADMIN + \"/__admin/expectations\"))\n");
        sb.append("                .header(\"Content-Type\", \"application/json\")\n");
        sb.append("                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))\n");
        sb.append("                .build();\n");
        sb.append("            java.net.http.HttpClient.newHttpClient().send(req,\n");
        sb.append("                java.net.http.HttpResponse.BodyHandlers.discarding());\n");
        sb.append("        } catch (Exception e) {\n");
        sb.append("            throw new RuntimeException(\"socket mock setup failed\", e);\n");
        sb.append("        }\n");
        sb.append("    }\n\n");
    }

    private static void appendBeforeAllMulti(StringBuilder sb, boolean hasHttp) {
        sb.append("    @BeforeAll\n");
        sb.append("    static void config() {\n");
        sb.append("        RestAssured.baseURI = System.getenv(\"APP_BASE_URI\");\n");
        if (hasHttp) {
            sb.append("        String wmAdmin = System.getenv(\"HTTP_MOCK_ADMIN\");\n");
            sb.append("        if (wmAdmin != null) {\n");
            sb.append("            java.net.URI u = java.net.URI.create(wmAdmin);\n");
            sb.append("            WireMock.configureFor(u.getHost(), u.getPort());\n");
            sb.append("        }\n");
        }
        sb.append("    }\n\n");
    }

    private static void appendStaticFieldsMulti(StringBuilder sb) {
        sb.append("    static String JDBC_URL = System.getenv(\"JDBC_URL\");\n");
        sb.append("    static String JDBC_USER = System.getenv(\"JDBC_USER\");\n");
        sb.append("    static String JDBC_PASS = System.getenv(\"JDBC_PASS\");\n\n");
    }

    private static void appendPathTestMethod(StringBuilder sb, Endpoint ep, PathContext pc) {
        ExploredPath path = pc.path();
        List<CapturedSql> captured = pc.capturedSql();
        List<CapturedHttpCall> httpCalls = pc.capturedHttpCalls();
        List<io.graphrag.model.CapturedSocketIO> socketIO = pc.capturedSocketIO();
        List<FixtureStatement> fixtures = FixtureComposer.fromCapturedSqls(captured);
        List<FixtureStatement> cleanup = FixtureComposer.cleanupFor(captured);
        String methodName = "path_" + sanitizeMethodId(path.id());

        sb.append("    @Test\n");
        sb.append("    void ").append(methodName).append("() throws Exception {\n");
        sb.append("        String testId = \"t-\" + java.util.UUID.randomUUID().toString().substring(0, 8);\n");

        // WireMock stub 등록 (HTTP capture가 있는 path만)
        if (!httpCalls.isEmpty()) {
            sb.append("        WireMock.reset();\n");
            for (CapturedHttpCall call : httpCalls) {
                String stub = HttpStubComposer.compose(call);
                // 들여쓰기 보정
                for (String line : stub.split("\n")) {
                    sb.append("        ").append(line).append("\n");
                }
            }
        }

        // Socket mock 등록 (socket capture가 있는 path만). OUTBOUND/INBOUND 쌍으로.
        if (!socketIO.isEmpty()) {
            String request = socketIO.stream()
                    .filter(s -> s.direction() == io.graphrag.model.SocketDirection.OUTBOUND)
                    .map(io.graphrag.model.CapturedSocketIO::byteHex)
                    .findFirst().orElse("");
            String response = socketIO.stream()
                    .filter(s -> s.direction() == io.graphrag.model.SocketDirection.INBOUND)
                    .map(io.graphrag.model.CapturedSocketIO::byteHex)
                    .findFirst().orElse("");
            int port = socketIO.stream().findFirst().map(io.graphrag.model.CapturedSocketIO::endpointPort).orElse(0);
            sb.append("        registerSocketExpectation(").append(port).append(", testId, ");
            sb.append(quoteString(request)).append(", ").append(quoteString(response)).append(");\n");
        }

        if (!fixtures.isEmpty()) {
            sb.append("        try (Connection conn = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASS)) {\n");
            for (FixtureStatement fx : fixtures) {
                appendPreparedStatement(sb, "            ", fx);
            }
            sb.append("        }\n");
        }

        String requestBody = renderJsonBody(path.sampleInput());
        sb.append("        try {\n");
        sb.append("            given()\n");
        sb.append("                .contentType(ContentType.JSON)\n");
        sb.append("                .body(").append(quoteString(requestBody)).append(")\n");
        sb.append("            .when()\n");
        sb.append("                .").append(ep.method().name().toLowerCase(Locale.ROOT))
                .append("(\"").append(ep.path()).append("\")\n");
        sb.append("            .then()\n");
        sb.append("                .statusCode(").append(path.exitStatus()).append(");\n");
        sb.append("        } finally {\n");
        if (!cleanup.isEmpty()) {
            sb.append("            try (Connection conn = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASS)) {\n");
            for (FixtureStatement fx : cleanup) {
                appendPreparedStatement(sb, "                ", fx);
            }
            sb.append("            }\n");
        }
        sb.append("        }\n");
        sb.append("    }\n\n");
    }

    private static void appendPreparedStatement(StringBuilder sb, String indent, FixtureStatement fx) {
        sb.append(indent).append("try (PreparedStatement ps = conn.prepareStatement(\n");
        sb.append(indent).append("        ").append(quoteString(fx.sql())).append(")) {\n");
        for (int i = 0; i < fx.params().size(); i++) {
            sb.append(indent).append("    ps.setObject(").append(i + 1).append(", ")
                    .append(quoteValue(fx.params().get(i))).append(");\n");
        }
        sb.append(indent).append("    ps.executeUpdate();\n");
        sb.append(indent).append("}\n");
    }

    private static String sanitizeMethodId(String s) {
        // Java identifier-safe
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isJavaIdentifierPart(c)) out.append(c);
            else out.append('_');
        }
        return out.toString();
    }

    private static final ObjectMapper BODY_MAPPER = JsonMappers.standard();

    private static String renderJsonBody(SampleInput input) {
        Object body = input == null ? null : input.body();
        if (body == null) return "{}";
        try {
            // Map은 키 정렬해서 결정적 출력
            if (body instanceof Map<?, ?> m) {
                Map<String, Object> sorted = new TreeMap<>();
                for (Map.Entry<?, ?> entry : m.entrySet()) {
                    sorted.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return BODY_MAPPER.writeValueAsString(sorted);
            }
            return BODY_MAPPER.writeValueAsString(body);
        } catch (Exception ex) {
            return "{}";
        }
    }

    /**
     * 클래스명 도출: 마지막 path 세그먼트 (kebab/snake-case PascalCase 변환) + HTTP 메소드 + "Test"
     * 예: POST /api/orders → "OrdersPostTest"
     *     GET /api/users → "UsersGetTest"
     *     POST /api/users/{id}/orders → "OrdersPostTest"
     *     POST /api/orders/with-inventory → "WithInventoryPostTest"
     */
    private static String deriveClassName(Endpoint ep) {
        String[] parts = ep.path().split("/");
        String last = "Resource";
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = parts[i];
            if (p.isEmpty()) continue;
            if (p.startsWith("{") && p.endsWith("}")) continue;   // path variable 스킵
            last = toPascalCase(p);
            break;
        }
        return last + capitalize(ep.method().name().toLowerCase(Locale.ROOT)) + "Test";
    }

    /** kebab/snake/공백 구분자를 word boundary로 보고 각 단어를 capitalize. */
    private static String toPascalCase(String s) {
        StringBuilder out = new StringBuilder();
        boolean atWordStart = true;
        for (char c : s.toCharArray()) {
            if (c == '-' || c == '_' || c == ' ' || c == '.') {
                atWordStart = true;
                continue;
            }
            if (!Character.isLetterOrDigit(c)) continue;
            if (atWordStart) {
                out.append(Character.toUpperCase(c));
                atWordStart = false;
            } else {
                out.append(c);
            }
        }
        return out.length() == 0 ? "Resource" : out.toString();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static void appendHeader(StringBuilder sb, String pkg) {
        appendHeader(sb, pkg, false);
    }

    private static void appendHeader(StringBuilder sb, String pkg, boolean withWireMock) {
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("import io.restassured.RestAssured;\n");
        sb.append("import io.restassured.http.ContentType;\n");
        sb.append("import org.junit.jupiter.api.AfterEach;\n");
        sb.append("import org.junit.jupiter.api.BeforeAll;\n");
        sb.append("import org.junit.jupiter.api.BeforeEach;\n");
        sb.append("import org.junit.jupiter.api.Test;\n\n");
        sb.append("import java.sql.Connection;\n");
        sb.append("import java.sql.DriverManager;\n");
        sb.append("import java.sql.PreparedStatement;\n");
        sb.append("import java.util.UUID;\n\n");
        sb.append("import static io.restassured.RestAssured.given;\n");
        if (withWireMock) {
            sb.append("import com.github.tomakehurst.wiremock.client.WireMock;\n");
            sb.append("import static com.github.tomakehurst.wiremock.client.WireMock.*;\n");
        }
        sb.append("\n");
    }

    private static void appendClassOpen(StringBuilder sb, String className) {
        sb.append("class ").append(className).append(" {\n\n");
    }

    private static void appendStaticFields(StringBuilder sb) {
        sb.append("    static String JDBC_URL = System.getenv(\"JDBC_URL\");\n");
        sb.append("    static String JDBC_USER = System.getenv(\"JDBC_USER\");\n");
        sb.append("    static String JDBC_PASS = System.getenv(\"JDBC_PASS\");\n\n");
        sb.append("    String testId;\n\n");
    }

    private static void appendBeforeAll(StringBuilder sb) {
        sb.append("    @BeforeAll\n");
        sb.append("    static void config() {\n");
        sb.append("        RestAssured.baseURI = System.getenv(\"APP_BASE_URI\");\n");
        sb.append("    }\n\n");
    }

    private static void appendBeforeEach(StringBuilder sb, List<FixtureStatement> fixtures) {
        sb.append("    @BeforeEach\n");
        sb.append("    void setUp() throws Exception {\n");
        sb.append("        testId = \"t-\" + UUID.randomUUID().toString().substring(0, 8);\n");
        if (!fixtures.isEmpty()) {
            sb.append("        try (Connection conn = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASS)) {\n");
            for (FixtureStatement fx : fixtures) {
                sb.append("            try (PreparedStatement ps = conn.prepareStatement(\n");
                sb.append("                    ").append(quoteString(fx.sql())).append(")) {\n");
                for (int i = 0; i < fx.params().size(); i++) {
                    sb.append("                ps.setObject(").append(i + 1).append(", ")
                            .append(quoteValue(fx.params().get(i))).append(");\n");
                }
                sb.append("                ps.executeUpdate();\n");
                sb.append("            }\n");
            }
            sb.append("        }\n");
        }
        sb.append("    }\n\n");
    }

    private static void appendAfterEach(StringBuilder sb, List<FixtureStatement> cleanup) {
        sb.append("    @AfterEach\n");
        sb.append("    void cleanup() throws Exception {\n");
        if (!cleanup.isEmpty()) {
            sb.append("        try (Connection conn = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASS)) {\n");
            for (FixtureStatement fx : cleanup) {
                sb.append("            try (PreparedStatement ps = conn.prepareStatement(\n");
                sb.append("                    ").append(quoteString(fx.sql())).append(")) {\n");
                for (int i = 0; i < fx.params().size(); i++) {
                    sb.append("                ps.setObject(").append(i + 1).append(", ")
                            .append(quoteValue(fx.params().get(i))).append(");\n");
                }
                sb.append("                ps.executeUpdate();\n");
                sb.append("            }\n");
            }
            sb.append("        }\n");
        }
        sb.append("    }\n\n");
    }

    private static void appendTestMethod(StringBuilder sb, Endpoint ep) {
        sb.append("    @Test\n");
        sb.append("    void invoke() {\n");
        sb.append("        given()\n");
        sb.append("            .contentType(ContentType.JSON)\n");
        sb.append("            .body(\"{}\")\n");
        sb.append("        .when()\n");
        sb.append("            .").append(ep.method().name().toLowerCase(Locale.ROOT))
                .append("(\"").append(ep.path()).append("\")\n");
        sb.append("        .then()\n");
        sb.append("            .statusCode(").append(defaultExpectedStatus(ep.method())).append(");\n");
        sb.append("    }\n\n");
    }

    private static int defaultExpectedStatus(HttpMethod method) {
        return switch (method) {
            case POST -> 201;
            case DELETE -> 204;
            default -> 200;
        };
    }

    private static void appendClassClose(StringBuilder sb) {
        sb.append("}\n");
    }

    private static String quoteString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String quoteValue(Object v) {
        if (v == null) return "null";
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        return quoteString(String.valueOf(v));
    }
}
