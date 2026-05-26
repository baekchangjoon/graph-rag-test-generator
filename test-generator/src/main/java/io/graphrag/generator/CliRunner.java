package io.graphrag.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.generator.core.MultiPathSynthesisInput;
import io.graphrag.generator.core.TestSynthesizer;
import io.graphrag.generator.output.TestArtifactWriter;
import io.graphrag.model.JsonMappers;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * graph-rag-test-generator CLI 진입점.
 *
 * <p>사용:
 * <pre>
 * java -jar test-generator.jar --spec spec.json --out out/dir
 * </pre>
 *
 * <p>spec.json은 {@link MultiPathSynthesisInput} JSON 표현.
 */
public final class CliRunner {

    private CliRunner() {}

    public static int run(String[] args) {
        return run(args, System.out, System.err);
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        Map<String, String> flags;
        try {
            flags = parseFlags(args);
        } catch (IllegalArgumentException ex) {
            err.println("error: " + ex.getMessage());
            err.println(usage());
            return 2;
        }

        String specPath = flags.get("--spec");
        String outPath = flags.get("--out");
        if (specPath == null || outPath == null) {
            err.println("error: both --spec and --out are required");
            err.println(usage());
            return 2;
        }

        Path spec = Paths.get(specPath);
        if (!Files.exists(spec)) {
            err.println("error: spec file not found: " + specPath);
            return 3;
        }

        ObjectMapper mapper = JsonMappers.standard();
        MultiPathSynthesisInput input;
        try {
            input = mapper.readValue(Files.readAllBytes(spec), MultiPathSynthesisInput.class);
        } catch (Exception ex) {
            err.println("error: failed to parse spec: " + ex.getMessage());
            return 4;
        }

        String generated = TestSynthesizer.synthesizeMulti(input);
        try {
            TestArtifactWriter writer = new TestArtifactWriter(Paths.get(outPath));
            String className = extractClassName(generated);
            Path written = writer.write(input.testPackage(), className, generated);
            out.println("wrote: " + written);
            return 0;
        } catch (Exception ex) {
            err.println("error: write failed: " + ex.getMessage());
            return 5;
        }
    }

    private static Map<String, String> parseFlags(String[] args) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) {
                throw new IllegalArgumentException("unexpected token: " + a);
            }
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("missing value for " + a);
            }
            if (!a.equals("--spec") && !a.equals("--out")) {
                throw new IllegalArgumentException("unknown flag: " + a);
            }
            out.put(a, args[++i]);
        }
        return out;
    }

    /** 생성된 코드에서 "class XxxTest" 패턴으로 클래스명 추출. */
    private static String extractClassName(String source) {
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("class ") || trimmed.startsWith("public class ")) {
                String tail = trimmed.substring(trimmed.indexOf("class ") + 6);
                int sp = tail.indexOf(' ');
                int br = tail.indexOf('{');
                int end = (sp < 0) ? br : (br < 0 ? sp : Math.min(sp, br));
                if (end > 0) return tail.substring(0, end).trim();
            }
        }
        return "Generated";
    }

    private static String usage() {
        return """
                usage: test-generator --spec <spec.json> --out <output-dir>

                spec.json: io.graphrag.generator.core.MultiPathSynthesisInput JSON form.
                """;
    }
}
