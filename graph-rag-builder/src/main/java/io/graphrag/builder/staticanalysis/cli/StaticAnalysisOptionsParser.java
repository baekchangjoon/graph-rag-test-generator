package io.graphrag.builder.staticanalysis.cli;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Pair-walks argv and validates required flags. */
public final class StaticAnalysisOptionsParser {

    private static final Set<String> REQUIRED = Set.of("--sut-source", "--project", "--out");
    private static final Set<String> ALLOWED  = Set.of(
            "--sut-source", "--project", "--out",
            "--code-version", "--max-paths-per-endpoint", "--exclude-paths");

    private StaticAnalysisOptionsParser() {}

    public static StaticAnalysisOptions parse(String[] args) {
        Map<String, String> flags = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--"))
                throw new IllegalArgumentException("unexpected token: " + a);
            if (!ALLOWED.contains(a))
                throw new IllegalArgumentException("unknown flag: " + a);
            if (i + 1 >= args.length || args[i + 1].startsWith("--"))
                throw new IllegalArgumentException("missing value for " + a);
            flags.put(a, args[++i]);
        }
        for (String req : REQUIRED) {
            if (!flags.containsKey(req))
                throw new IllegalArgumentException("missing required flag " + req);
        }
        int maxPaths;
        try {
            maxPaths = Integer.parseInt(flags.getOrDefault("--max-paths-per-endpoint", "10"));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    "--max-paths-per-endpoint must be an integer: " + flags.get("--max-paths-per-endpoint"));
        }
        return new StaticAnalysisOptions(
                Path.of(flags.get("--sut-source")),
                flags.get("--project"),
                Path.of(flags.get("--out")),
                flags.getOrDefault("--code-version", "static-1"),
                maxPaths,
                parseExclude(flags.get("--exclude-paths")));
    }

    private static Set<String> parseExclude(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String item : csv.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    public static String usage() {
        return """
                usage:
                  java -cp graph-rag-builder.jar io.graphrag.builder.staticanalysis.cli.StaticAnalysisCli \\
                    --sut-source <src/main/java root> \\
                    --project    <project name> \\
                    --out        <output dir> \\
                    [--code-version <sha>] \\
                    [--max-paths-per-endpoint <N>] \\
                    [--exclude-paths id1,id2,...]
                """;
    }
}
