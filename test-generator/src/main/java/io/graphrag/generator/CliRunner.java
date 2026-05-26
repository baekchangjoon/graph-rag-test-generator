package io.graphrag.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.generator.archive.ArchiveReader;
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
import java.util.Set;

/**
 * graph-rag-test-generator CLI 진입점.
 *
 * <p>두 가지 사용 모드:
 * <pre>
 * # spec 모드: 미리 만든 spec JSON으로 합성
 * java -jar test-generator.jar --spec spec.json --out out/dir
 *
 * # archive 모드: graph-rag-builder가 만든 archive에서 직접 읽음
 * java -jar test-generator.jar --archive cache/dir --endpoint "POST:/api/orders" \
 *      --package com.example.tests --out out/dir
 * </pre>
 */
public final class CliRunner {

    private static final Set<String> ALLOWED_FLAGS = Set.of(
            "--spec", "--archive", "--endpoint", "--package", "--out");

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

        String outPath = flags.get("--out");
        if (outPath == null) {
            err.println("error: --out is required");
            err.println(usage());
            return 2;
        }

        MultiPathSynthesisInput input;
        try {
            input = buildInput(flags);
        } catch (CliError ce) {
            err.println("error: " + ce.getMessage());
            err.println(usage());
            return ce.code;
        } catch (Exception ex) {
            err.println("error: " + ex.getMessage());
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

    private static MultiPathSynthesisInput buildInput(Map<String, String> flags) throws Exception {
        if (flags.containsKey("--spec")) {
            Path spec = Paths.get(flags.get("--spec"));
            if (!Files.exists(spec)) {
                throw new CliError(3, "spec file not found: " + spec);
            }
            ObjectMapper mapper = JsonMappers.standard();
            return mapper.readValue(Files.readAllBytes(spec), MultiPathSynthesisInput.class);
        }
        if (flags.containsKey("--archive")) {
            Path dir = Paths.get(flags.get("--archive"));
            if (!Files.isDirectory(dir)) {
                throw new CliError(3, "archive dir not found: " + dir);
            }
            String endpoint = flags.get("--endpoint");
            String pkg = flags.get("--package");
            if (endpoint == null || pkg == null) {
                throw new CliError(2, "--archive mode requires --endpoint and --package");
            }
            return ArchiveReader.load(dir).buildInput(endpoint, pkg);
        }
        throw new CliError(2, "either --spec or --archive is required");
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
            if (!ALLOWED_FLAGS.contains(a)) {
                throw new IllegalArgumentException("unknown flag: " + a);
            }
            out.put(a, args[++i]);
        }
        return out;
    }

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
                usage:
                  test-generator --spec <spec.json> --out <output-dir>
                  test-generator --archive <archive-dir> --endpoint <id> --package <pkg> --out <dir>
                """;
    }

    private static final class CliError extends RuntimeException {
        final int code;
        CliError(int code, String msg) { super(msg); this.code = code; }
    }
}
