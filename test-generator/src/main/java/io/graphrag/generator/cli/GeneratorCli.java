package io.graphrag.generator.cli;

import io.graphrag.generator.Generator;
import io.graphrag.model.GeneratedFile;
import io.graphrag.model.GenerationRequest;
import io.graphrag.model.GenerationResult;
import io.graphrag.model.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.content());
            log.info("generated: {}", target);
        }
        Files.writeString(out.resolve("generation-result.json"),
                Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
        log.info("result: {} file(s), {} warning(s)", result.files().size(), result.warnings().size());
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
