package io.graphrag.translator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.JsonMappers;
import io.graphrag.scout.config.CaptureStep;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CLI: translate Stage 1 output (paths.json + endpoints.json) into a Stage 3
 * scout-launcher {@code config.yml}, merging the generated {@code scout.steps[]}
 * into a caller-supplied template that already has the {@code sut} / {@code
 * dependencies} / {@code output} sections filled in.
 *
 * <pre>
 * scout-step-translator \
 *     --paths-file       path/to/paths.json \
 *     --endpoints-file   path/to/endpoints.json \
 *     --scout-base-url   http://localhost:8084 \
 *     --sut-config-template template.yml \
 *     --out              generated/config.yml
 * </pre>
 *
 * <p>{@code base-url} is taken from {@code --scout-base-url}; if the template's
 * {@code scout.base-url} is present, the CLI flag overrides it (so the template
 * can stay environment-agnostic).
 */
public final class ScoutStepTranslator {

    private static final ObjectMapper JSON = JsonMappers.standard();
    private static final ObjectMapper YAML =
            new ObjectMapper(new YAMLFactory().disable(
                    com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature.WRITE_DOC_START_MARKER));

    private static final Set<String> ALLOWED_FLAGS = Set.of(
            "--paths-file", "--endpoints-file", "--scout-base-url",
            "--sut-config-template", "--out");

    private ScoutStepTranslator() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
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
        for (String required : ALLOWED_FLAGS) {
            if (!flags.containsKey(required)) {
                err.println("error: missing required flag " + required);
                err.println(usage());
                return 2;
            }
        }
        try {
            translate(
                    Paths.get(flags.get("--paths-file")),
                    Paths.get(flags.get("--endpoints-file")),
                    flags.get("--scout-base-url"),
                    Paths.get(flags.get("--sut-config-template")),
                    Paths.get(flags.get("--out")));
            out.println("wrote: " + flags.get("--out"));
            return 0;
        } catch (TranslationException te) {
            err.println("error: " + te.getMessage());
            return te.code;
        } catch (Exception ex) {
            err.println("error: " + ex.getMessage());
            return 4;
        }
    }

    public static void translate(Path pathsFile,
                                 Path endpointsFile,
                                 String scoutBaseUrl,
                                 Path templateFile,
                                 Path outFile) throws IOException {
        List<ExploredPath> paths = JSON.readValue(
                Files.readAllBytes(pathsFile),
                new TypeReference<List<ExploredPath>>() {});
        List<Endpoint> endpoints = JSON.readValue(
                Files.readAllBytes(endpointsFile),
                new TypeReference<List<Endpoint>>() {});
        Map<String, Endpoint> byId = new HashMap<>();
        for (Endpoint ep : endpoints) byId.put(ep.id(), ep);

        List<CaptureStep> steps = new ArrayList<>(paths.size());
        for (ExploredPath p : paths) {
            Endpoint ep = byId.get(p.endpointId());
            if (ep == null) {
                throw new TranslationException(3,
                        "path " + p.id() + " references endpoint_id '" + p.endpointId()
                                + "' which is not in " + endpointsFile);
            }
            steps.add(ExploredPathToCaptureStep.convert(p, ep));
        }

        ObjectNode rootYaml = (ObjectNode) YAML.readTree(Files.readAllBytes(templateFile));
        injectScoutSection(rootYaml, scoutBaseUrl, steps);

        Files.createDirectories(outFile.toAbsolutePath().getParent());
        Files.write(outFile, YAML.writerWithDefaultPrettyPrinter()
                .writeValueAsString(rootYaml).getBytes());
    }

    /**
     * Replace the entire {@code scout:} section (if any) with the generated one,
     * preserving the rest of the template untouched.
     */
    private static void injectScoutSection(ObjectNode root,
                                           String baseUrl,
                                           List<CaptureStep> steps) {
        ObjectNode scout = root.with("scout");
        scout.removeAll();
        scout.put("base-url", baseUrl);

        ArrayNode stepsNode = scout.putArray("steps");
        for (CaptureStep step : steps) {
            ObjectNode s = stepsNode.addObject();
            s.put("path-id", step.pathId());
            s.put("method", step.method());
            s.put("path", step.path());
            if (step.body() != null) s.put("body", step.body());
            if (step.contentType() != null) s.put("content-type", step.contentType());
            if (!step.headers().isEmpty()) {
                ObjectNode h = s.with("headers");
                step.headers().forEach(h::put);
            }
            if (step.expectedStatus() != null && step.expectedStatus() > 0) {
                s.put("expected-status", step.expectedStatus());
            }
        }
    }

    private static Map<String, String> parseFlags(String[] args) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) {
                throw new IllegalArgumentException("unexpected token: " + a);
            }
            if (!ALLOWED_FLAGS.contains(a)) {
                throw new IllegalArgumentException("unknown flag: " + a);
            }
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("missing value for " + a);
            }
            out.put(a, args[++i]);
        }
        return out;
    }

    private static String usage() {
        return """
                usage:
                  scout-step-translator \\
                    --paths-file <paths.json> \\
                    --endpoints-file <endpoints.json> \\
                    --scout-base-url <http://host:port> \\
                    --sut-config-template <template.yml> \\
                    --out <config.yml>
                """;
    }

    /** Thrown to signal a translation-time error with a stable CLI exit code. */
    static final class TranslationException extends RuntimeException {
        final int code;
        TranslationException(int code, String message) {
            super(message);
            this.code = code;
        }
    }
}
