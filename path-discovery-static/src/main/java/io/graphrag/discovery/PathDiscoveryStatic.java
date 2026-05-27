package io.graphrag.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.discovery.output.EndpointBuilder;
import io.graphrag.discovery.output.ExploredPathBuilder;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.JsonMappers;

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
 * Stage 1 entry point: scan a Spring source tree and emit shared-model
 * {@code paths.json} + {@code endpoints.json} ready to be consumed by
 * {@code scout-step-translator} (T2) or {@code test-generator --archive} (T1).
 *
 * <pre>
 * path-discovery-static \
 *     --sut-source ~/github_spring-petclinic/spring-petclinic/src/main/java \
 *     --project    petclinic \
 *     --out        /tmp/archive
 * </pre>
 *
 * <p>Optional flags:
 * <ul>
 *   <li>{@code --code-version <sha>} stamped into every ExploredPath. Defaults to
 *       {@code "static-1"} if omitted.</li>
 *   <li>{@code --exclude-paths id1,id2} hint forwarded by Stage 6 coverage-feedback
 *       to avoid re-emitting paths that earlier iterations already saw. Currently
 *       interpreted as "skip any ExploredPath whose endpoint_id matches".</li>
 * </ul>
 *
 * <p>See {@code docs/22-static-discovery-limits.md} for the things this scanner
 * <em>cannot</em> see (R1 mitigation: explicit, not silent).
 */
public final class PathDiscoveryStatic {

    private static final ObjectMapper M = JsonMappers.standard();

    private static final Set<String> REQUIRED_FLAGS = Set.of(
            "--sut-source", "--project", "--out");
    private static final Set<String> ALLOWED_FLAGS = Set.of(
            "--sut-source", "--project", "--out", "--code-version", "--exclude-paths");

    private PathDiscoveryStatic() {}

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
        for (String req : REQUIRED_FLAGS) {
            if (!flags.containsKey(req)) {
                err.println("error: missing required flag " + req);
                err.println(usage());
                return 2;
            }
        }
        try {
            Result r = discover(
                    Paths.get(flags.get("--sut-source")),
                    flags.get("--project"),
                    flags.getOrDefault("--code-version", "static-1"),
                    parseExclude(flags.get("--exclude-paths")));
            Path outDir = Paths.get(flags.get("--out"));
            Files.createDirectories(outDir);
            M.writerWithDefaultPrettyPrinter()
                    .writeValue(outDir.resolve("endpoints.json").toFile(), r.endpoints);
            M.writerWithDefaultPrettyPrinter()
                    .writeValue(outDir.resolve("paths.json").toFile(), r.paths);
            out.println("[static-discovery] " + r.endpoints.size() + " endpoint(s), "
                    + r.paths.size() + " explored path(s) → " + outDir.toAbsolutePath());
            return 0;
        } catch (IOException ex) {
            err.println("error: " + ex.getMessage());
            return 4;
        }
    }

    public static Result discover(Path sutSource, String project, String codeVersion,
                                  Set<String> excludeEndpointIds) throws IOException {
        List<DiscoveredHandler> handlers = ControllerScanner.scan(sutSource);
        List<Endpoint> endpoints = new ArrayList<>();
        List<ExploredPath> paths = new ArrayList<>();
        for (DiscoveredHandler h : handlers) {
            Endpoint ep = EndpointBuilder.build(h, project);
            if (excludeEndpointIds.contains(ep.id())) continue;
            endpoints.add(ep);
            paths.addAll(ExploredPathBuilder.build(h, ep, codeVersion));
        }
        return new Result(endpoints, paths);
    }

    private static Set<String> parseExclude(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Set.of(csv.split(","));
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
                  path-discovery-static \\
                    --sut-source <src/main/java root> \\
                    --project    <project name> \\
                    --out        <archive dir> \\
                    [--code-version <sha>] \\
                    [--exclude-paths id1,id2,...]
                """;
    }

    /** Returned by {@link #discover(Path, String, String, Set)} so callers can chain in-process. */
    public record Result(List<Endpoint> endpoints, List<ExploredPath> paths) {}
}
