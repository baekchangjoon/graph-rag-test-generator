package io.graphrag.generator;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import io.graphrag.generator.client.FileGraphRagClient;
import io.graphrag.generator.client.GraphRagClient;
import io.graphrag.generator.compose.ComposedFixture;
import io.graphrag.generator.compose.FixtureComposer;
import io.graphrag.generator.compose.HttpMockComposer;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GeneratedFile;
import io.graphrag.model.GenerationRequest;
import io.graphrag.model.GenerationResult;
import io.graphrag.model.ParallelSafetyReport;

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

    public Generator(Path graphDir) {
        this(new FileGraphRagClient(graphDir));
    }

    public Generator(GraphRagClient client) {
        this.client = client;
        this.template = new DefaultMustacheFactory().compile("templates/test-class.mustache");
    }

    /** pathId 미지정 시 endpoint의 전 path에 대해 path당 테스트 클래스 1개씩 생성 (1.5). */
    public GenerationResult generate(GenerationRequest request) {
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

        ComposedFixture fixture = new FixtureComposer().compose(path, sql, client.tables());
        HttpMockComposer.ComposedMocks mocks =
                new HttpMockComposer().compose(client.httpCallsForPath(pathId));

        Map<String, Object> scope = new HashMap<>();
        scope.put("packageName", request.packageName());
        scope.put("className", className);
        scope.put("httpMethod", endpoint.httpMethod());
        scope.put("httpMethodLower", endpoint.httpMethod().toLowerCase());
        scope.put("endpointPath", endpoint.path());
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
}
