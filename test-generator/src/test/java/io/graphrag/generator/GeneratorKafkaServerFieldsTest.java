package io.graphrag.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-009/010/011: Kafka emit payload 필드 분류 및 per-field assertion 생성.
 * - server-generated(UUID/타임스탬프): regex 패턴 Customization
 * - input-derived(substitution): 변수식 Customization
 * - DB-PK(nonDeterministic): 제거(LENIENT이므로 absent도 통과)
 * - deterministic: payloadJson 리터럴에 유지 (JSONAssert LENIENT literal equals)
 */
class GeneratorKafkaServerFieldsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * payload: eventId(UUID=server), occurredAt(timestamp=server), tenantId(input-derived), status(deterministic)
     * nonDeterministicValues: empty
     * substitutions: "probe-tenant" -> tenantId (input-derived via SQL binding)
     */
    @Test
    void kafkaEmitPayload_classifiesAndAsserts_perField() throws Exception {
        // ── fixture ──────────────────────────────────────────────────────────────
        Endpoint endpoint = new Endpoint(
                "post-api-events", "POST", "/api/events",
                "EventController", "create", List.of(), false);

        // sampleInput: tenantId="probe-tenant" → will be recognized as substitution
        // because it appears as API_PARAM binding on a PK column in the SQL below
        ObjectNode sampleInput = MAPPER.createObjectNode().put("tenantId", "probe-tenant");

        ObjectNode payload = MAPPER.createObjectNode()
                .put("eventId",    "550e8400-e29b-41d4-a716-446655440000")  // UUID → server-generated
                .put("occurredAt", "2026-06-21T10:15:30Z")                  // ISO-8601 → server-generated
                .put("tenantId",   "probe-tenant")                           // in substitutions → input-derived
                .put("status",     "ACTIVE");                                 // literal → deterministic

        ExploredPath path = new ExploredPath(
                "post-api-events-happy", "post-api-events",
                sampleInput, 201, MAPPER.createObjectNode(),
                List.of("sql-1"), List.of(), List.of(), "test",
                List.of(), List.of(), List.of(),
                List.of("emit-sg"));

        CapturedEventEmit emit = new CapturedEventEmit(
                "emit-sg", "post-api-events-happy", "events-topic", "probe-tenant", payload);

        // SQL INSERT binding: "probe-tenant" -> tenants.id (PK) → makes FixtureComposer treat it as substitution
        CapturedSql insertSql = new CapturedSql(
                "sql-1", "post-api-events-happy", "INSERT",
                "INSERT INTO tenants (id, name) VALUES (?, ?)",
                "tenants",
                List.of(new SqlBinding(1, "id", "probe-tenant", BindingOrigin.API_PARAM, "tenants"),
                        new SqlBinding(2, "name", "probe", BindingOrigin.LITERAL, "tenants")));

        TableSchema tenantsTable = new TableSchema("tenants",
                List.of(new ColumnSchema("id", "VARCHAR", false, true),
                        new ColumnSchema("name", "VARCHAR", false, false)),
                List.of(), List.of());

        GraphAsset asset = new GraphAsset(
                "sut", "commit",
                List.of(endpoint),
                List.of(path),
                List.of(insertSql),       // sql
                List.of(tenantsTable),     // tables
                List.of(),                 // mappers
                List.of(),                 // httpCalls
                List.of(),                 // wsEndpoints
                List.of(),                 // wsExchanges
                List.of(),                 // kafkaConsumers
                List.of(),                 // kafkaExchanges
                List.of(),                 // seeds
                List.of(emit));            // capturedEventEmits

        GenerationRequest request = new GenerationRequest(
                "post-api-events", "post-api-events-happy",
                "EventsPostTest", "io.graphrag.generated", AuthMode.DISABLED);

        // ── generate ─────────────────────────────────────────────────────────────
        GenerationResult result = new Generator(new FullFakeClient(asset)).generate(request);

        String code = result.files().stream()
                .filter(f -> f.relativePath().endsWith(".java"))
                .map(GeneratedFile::content)
                .findFirst().orElseThrow();

        // REQ-009: server-generated fields → regex Customization
        assertThat(code)
                .as("eventId should have UUID regex Customization")
                .contains("Customization(\"eventId\",");
        assertThat(code)
                .as("eventId regex should match UUID hex pattern")
                .containsPattern("(?s)Customization\\(\"eventId\".*matches\\(.*[0-9a-fA-F].*\\)");

        assertThat(code)
                .as("occurredAt should have timestamp regex Customization")
                .contains("Customization(\"occurredAt\",");
        assertThat(code)
                .as("occurredAt regex should contain digit pattern for year")
                .containsPattern("(?s)Customization\\(\"occurredAt\".*matches\\(.*\\)");

        // REQ-010: input-derived field → substitution variable expression Customization
        assertThat(code)
                .as("tenantId should have substitution variable Customization")
                .contains("Customization(\"tenantId\",");
        assertThat(code)
                .as("tenantId Customization should reference the substitution variable (tenantId)")
                .containsPattern("(?s)Customization\\(\"tenantId\".*tenantId");

        // REQ-011: deterministic field → remains in payloadJson literal
        // In the generated Java source, JSON is Java-escaped: {"status":"ACTIVE"} becomes \"status\":\"ACTIVE\"
        assertThat(code)
                .as("status ACTIVE should be in payloadJson literal (Java-escaped)")
                .contains("\\\"status\\\":\\\"ACTIVE\\\"");

        // server-generated fields are KEPT in payloadJson (regex Customization handles the matching)
        assertThat(code)
                .as("eventId should be in payloadJson")
                .contains("\\\"eventId\\\"");
        assertThat(code)
                .as("occurredAt should be in payloadJson")
                .contains("\\\"occurredAt\\\"");

        // CustomComparator must be used
        assertThat(code)
                .as("Should use CustomComparator for per-field assertions")
                .contains("CustomComparator");
        assertThat(code)
                .as("Should use LENIENT mode in CustomComparator")
                .contains("LENIENT");

        // Compile check: generated source must compile without errors
        compileCheck(code);
    }

    /**
     * Regression: payload with NO server-generated or substitution fields
     * → CustomComparator(LENIENT) with no customizations → behaviorally identical to old false.
     */
    @Test
    void kafkaEmitPayload_neitherServerNorSubstitution_usesCustomComparatorLenient() throws Exception {
        Endpoint endpoint = new Endpoint(
                "post-api-orders", "POST", "/api/orders",
                "OrderController", "create", List.of(), false);

        ObjectNode payload = MAPPER.createObjectNode()
                .put("orderId", "123")
                .put("status", "PENDING");

        ExploredPath path = new ExploredPath(
                "post-api-orders-happy", "post-api-orders",
                MAPPER.createObjectNode(), 200, MAPPER.createObjectNode(),
                List.of(), List.of(), List.of(), "test",
                List.of(), List.of(), List.of(),
                List.of("emit-plain"));

        CapturedEventEmit emit = new CapturedEventEmit(
                "emit-plain", "post-api-orders-happy", "orders-topic", "order-key-123", payload);

        GraphAsset asset = new GraphAsset(
                "sut", "commit",
                List.of(endpoint),
                List.of(path),
                List.of(),          // sql
                List.of(),          // tables
                List.of(),          // mappers
                List.of(),          // httpCalls
                List.of(),          // wsEndpoints
                List.of(),          // wsExchanges
                List.of(),          // kafkaConsumers
                List.of(),          // kafkaExchanges
                List.of(),          // seeds
                List.of(emit));     // capturedEventEmits

        GenerationRequest request = new GenerationRequest(
                "post-api-orders", "post-api-orders-happy",
                "OrdersPostTest", "io.graphrag.generated", AuthMode.DISABLED);

        GenerationResult result = new Generator(new FullFakeClient(asset)).generate(request);

        String code = result.files().stream()
                .filter(f -> f.relativePath().endsWith(".java"))
                .map(GeneratedFile::content)
                .findFirst().orElseThrow();

        // Should still use CustomComparator with LENIENT mode (behaviorally equivalent to old false)
        assertThat(code).contains("CustomComparator");
        assertThat(code).contains("LENIENT");
        // No per-field Customization entries for orderId or status
        assertThat(code).doesNotContain("Customization(\"orderId\"");
        assertThat(code).doesNotContain("Customization(\"status\"");
        // The deterministic payload should be in payloadJson (Java-escaped in generated source)
        assertThat(code).contains("\\\"orderId\\\":\\\"123\\\"");
        assertThat(code).contains("\\\"status\\\":\\\"PENDING\\\"");

        compileCheck(code);
    }

    // ── Full fake client backed by a GraphAsset ──────────────────────────────

    /** Complete GraphRagClient backed by a GraphAsset, routing every lookup from the asset. */
    static class FullFakeClient implements io.graphrag.generator.client.GraphRagClient {
        private final GraphAsset asset;

        FullFakeClient(GraphAsset asset) {
            this.asset = asset;
        }

        @Override
        public Endpoint endpoint(String id) {
            return asset.endpoints().stream().filter(e -> e.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown endpoint: " + id));
        }

        @Override
        public ExploredPath path(String id) {
            return asset.paths().stream().filter(p -> p.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown path: " + id));
        }

        @Override
        public List<ExploredPath> pathsForEndpoint(String endpointId) {
            return asset.paths().stream().filter(p -> p.endpointId().equals(endpointId)).toList();
        }

        @Override
        public List<CapturedSql> sqlForPath(String pathId) {
            return asset.sql().stream().filter(s -> s.pathId().equals(pathId)).toList();
        }

        @Override
        public List<CapturedHttpCall> httpCallsForPath(String pathId) {
            return asset.httpCalls().stream().filter(c -> c.pathId().equals(pathId)).toList();
        }

        @Override
        public boolean hasWsEndpoint(String id) {
            return asset.wsEndpoints().stream().anyMatch(w -> w.id().equals(id));
        }

        @Override
        public WsEndpoint wsEndpoint(String id) {
            return asset.wsEndpoints().stream().filter(w -> w.id().equals(id)).findFirst().orElse(null);
        }

        @Override
        public List<WsExchange> wsExchangesFor(String wsEndpointId) {
            return asset.wsExchanges().stream().filter(w -> w.wsEndpointId().equals(wsEndpointId)).toList();
        }

        @Override
        public WsExchange wsExchange(String exchangeId) {
            return asset.wsExchanges().stream().filter(w -> w.id().equals(exchangeId)).findFirst().orElse(null);
        }

        @Override
        public boolean hasKafkaConsumer(String id) {
            return asset.kafkaConsumers().stream().anyMatch(k -> k.id().equals(id));
        }

        @Override
        public KafkaConsumer kafkaConsumer(String id) {
            return asset.kafkaConsumers().stream().filter(k -> k.id().equals(id)).findFirst().orElse(null);
        }

        @Override
        public List<KafkaExchange> kafkaExchangesFor(String consumerId) {
            return asset.kafkaExchanges().stream().filter(x -> x.kafkaConsumerId().equals(consumerId)).toList();
        }

        @Override
        public List<TableSchema> tables() {
            return asset.tables();
        }

        @Override
        public List<RequiredSeed> seedsForPath(String pathId) {
            return asset.seeds().stream().filter(s -> java.util.Objects.equals(s.pathId(), pathId)).toList();
        }

        @Override
        public List<CapturedEventEmit> capturedEventEmitsForPath(String pathId) {
            return asset.capturedEventEmits().stream().filter(e -> e.pathId().equals(pathId)).toList();
        }
    }

    // ── compile helper ───────────────────────────────────────────────────────

    /**
     * Feeds the generated Java source to javax.tools.JavaCompiler.
     * Fails with a descriptive message if compilation errors occur.
     */
    private static void compileCheck(String javaSource) throws Exception {
        java.nio.file.Path tmpDir = java.nio.file.Files.createTempDirectory("gen-compile-");
        try {
            String className = extractClassName(javaSource);
            java.nio.file.Path srcFile = tmpDir.resolve(className + ".java");
            java.nio.file.Files.writeString(srcFile, javaSource);

            String classpath = System.getProperty("java.class.path");

            javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
            assertThat(compiler).as("javax.tools.JavaCompiler must be available (JDK, not JRE)").isNotNull();

            javax.tools.DiagnosticCollector<javax.tools.JavaFileObject> collector =
                    new javax.tools.DiagnosticCollector<>();

            try (javax.tools.StandardJavaFileManager fm =
                         compiler.getStandardFileManager(collector, null, null)) {
                Iterable<? extends javax.tools.JavaFileObject> units =
                        fm.getJavaFileObjects(srcFile.toFile());

                javax.tools.JavaCompiler.CompilationTask task = compiler.getTask(
                        new java.io.StringWriter(), fm, collector,
                        List.of("-classpath", classpath),
                        null, units);

                boolean success = task.call();
                if (!success) {
                    String errors = collector.getDiagnostics().stream()
                            .filter(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR)
                            .map(d -> d.toString())
                            .collect(Collectors.joining("\n"));
                    assertThat(success).as("Generated source should compile:\n" + errors).isTrue();
                }
            }
        } finally {
            try (var walk = java.nio.file.Files.walk(tmpDir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { java.nio.file.Files.delete(p); } catch (Exception ignored) { }
                        });
            }
        }
    }

    private static String extractClassName(String source) {
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("class ")) {
                return trimmed.substring("class ".length()).split("[\\s{]")[0];
            }
        }
        // 생성 소스에서 class 선언을 못 찾으면 잘못된 파일명으로 컴파일이 모호하게 실패한다.
        // 무음 fallback 대신 명시적으로 실패시켜 원인을 드러낸다 (review Issue 1).
        throw new AssertionError("could not extract class name from generated source");
    }
}
