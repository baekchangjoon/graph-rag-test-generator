package io.graphrag.generator.core;

import io.graphrag.generator.compose.FixtureComposer;
import io.graphrag.generator.compose.FixtureStatement;
import io.graphrag.model.Endpoint;
import io.graphrag.model.HttpMethod;

import java.util.List;
import java.util.Locale;
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
     * 클래스명 도출: 마지막 path 세그먼트 + HTTP 메소드 + "Test"
     * 예: POST /api/orders → "OrdersPostTest"
     *     GET /api/users → "UsersGetTest"
     *     POST /api/users/{id}/orders → "OrdersPostTest"
     */
    private static String deriveClassName(Endpoint ep) {
        String[] parts = ep.path().split("/");
        String last = "Resource";
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = parts[i];
            if (p.isEmpty()) continue;
            if (p.startsWith("{") && p.endsWith("}")) continue;   // path variable 스킵
            last = sanitize(p);
            break;
        }
        return capitalize(last) + capitalize(ep.method().name().toLowerCase(Locale.ROOT)) + "Test";
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9]", "");
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static void appendHeader(StringBuilder sb, String pkg) {
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
        sb.append("import static io.restassured.RestAssured.given;\n\n");
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
