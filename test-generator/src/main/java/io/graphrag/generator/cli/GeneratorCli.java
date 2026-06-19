package io.graphrag.generator.cli;

import io.graphrag.generator.Generator;
import io.graphrag.model.GeneratedFile;
import io.graphrag.model.GenerationRequest;
import io.graphrag.model.GenerationResult;
import io.graphrag.model.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 도구 2 진입점.
 * generate --request <request.json> --graph <graph-dir> --out <out-dir>
 */
public final class GeneratorCli {

    private static final Logger log = LoggerFactory.getLogger(GeneratorCli.class);

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        Path requestFile = Path.of(required(options, "--request"));
        Path graphDir = Path.of(required(options, "--graph"));
        Path out = Path.of(required(options, "--out"));

        GenerationRequest request = Json.mapper()
                .readValue(Files.readString(requestFile), GenerationRequest.class);
        GenerationResult result = new Generator(graphDir).generate(request);

        for (GeneratedFile file : result.files()) {
            Path target = out.resolve(file.relativePath());
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            writeFile(target, file.content(), log);
            log.info("generated: {}", target);
        }
        Files.writeString(out.resolve("generation-result.json"),
                Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
        log.info("result: {} file(s), {} warning(s)", result.files().size(), result.warnings().size());
    }

    /**
     * target에 기존 파일이 있고 내용이 emit 내용과 다르면 경고 로그 후 덮어쓴다.
     *
     * @return true when an existing file with different content was overwritten (warn was issued);
     *         false when the file was absent or the content was identical.
     */
    static boolean writeFile(Path target, String content, Logger log) throws IOException {
        if (Files.exists(target)) {
            String existing = Files.readString(target);
            if (!existing.equals(content)) {
                log.warn("overwriting existing {} with generated content "
                        + "— merge manually if you had custom settings", target);
                Files.writeString(target, content);
                return true;
            }
            // identical — no-op, no warn
            return false;
        }
        Files.writeString(target, content);
        return false;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new HashMap<>();
        int start = args.length > 0 && !args[0].startsWith("--") ? 1 : 0;
        for (int i = start; i + 1 < args.length; i += 2) {
            options.put(args[i], args[i + 1]);
        }
        return options;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing required option: " + key);
        }
        return value;
    }
}
