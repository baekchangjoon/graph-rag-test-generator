package io.graphrag.generator;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import io.graphrag.generator.client.FileGraphRagClient;
import io.graphrag.generator.client.GraphRagClient;
import io.graphrag.generator.compose.ComposedFixture;
import io.graphrag.generator.compose.FixtureComposer;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GeneratedFile;
import io.graphrag.model.GenerationRequest;
import io.graphrag.model.GenerationResult;
import io.graphrag.model.ParallelSafetyReport;

import java.io.StringWriter;
import java.nio.file.Path;
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

    public GenerationResult generate(GenerationRequest request) {
        Endpoint endpoint = client.endpoint(request.endpointId());
        ExploredPath path = client.path(request.pathId());
        List<CapturedSql> sql = client.sqlForPath(request.pathId());

        ComposedFixture fixture = new FixtureComposer().compose(path, sql, client.tables());

        Map<String, Object> scope = new HashMap<>();
        scope.put("packageName", request.packageName());
        scope.put("className", request.testClassName());
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

        StringWriter writer = new StringWriter();
        template.execute(writer, scope);

        String relativePath = request.packageName().replace('.', '/')
                + "/" + request.testClassName() + ".java";
        return new GenerationResult(
                List.of(new GeneratedFile(relativePath, writer.toString())),
                List.of(),
                // Phase 0: DB는 testId 격리, HTTP/socket mock 미사용 → 완전 병렬 안전
                new ParallelSafetyReport(List.of(request.testClassName()), List.of()));
    }

    private static String bodyExpr(ComposedFixture fixture) {
        String literal = "\"" + fixture.bodyFormat().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        if (fixture.bodyArgExprs().isEmpty()) {
            return literal;
        }
        return "String.format(" + literal + ", " + String.join(", ", fixture.bodyArgExprs()) + ")";
    }
}
