package io.graphrag.builder.staticanalysis.ast;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Walks a Spring source directory, parses every {@code .java} file with
 * JavaParser, and returns an {@link AstParseResult} containing the parsed
 * compilation units in deterministic (path-sorted) order plus a separate
 * list of files that failed to parse.
 *
 * <p>Per-file failures are isolated: a broken {@code .java} file is
 * captured in {@code failures} but the remaining files are still parsed.
 * Filesystem errors (e.g. unreadable source root) propagate as {@code IOException}.
 *
 * @see SymbolResolverFactory
 */
public final class AstParser {

    private AstParser() {}

    /** Parse {@code sourceRoot} with no external classpath jars. */
    public static AstParseResult parse(Path sourceRoot) throws IOException {
        return parse(sourceRoot, List.of());
    }

    /**
     * Parse {@code sourceRoot}, configuring the symbol solver with the given jars.
     *
     * @param sourceRoot    directory to walk for {@code .java} files (recursive)
     * @param classpathJars additional jars to feed the {@link SymbolResolverFactory}
     */
    public static AstParseResult parse(Path sourceRoot, List<Path> classpathJars) throws IOException {
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(classpathJars, "classpathJars");

        if (!Files.isDirectory(sourceRoot)) {
            return new AstParseResult(List.of(), List.of());
        }

        List<Path> javaFiles;
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            javaFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }

        ParserConfiguration cfg = new ParserConfiguration()
                .setSymbolResolver(SymbolResolverFactory.create(sourceRoot, classpathJars));
        JavaParser parser = new JavaParser(cfg);

        List<ParsedFile> parsed = new ArrayList<>(javaFiles.size());
        List<ParseFailure> failures = new ArrayList<>();
        for (Path src : javaFiles) {
            parseOne(parser, src, parsed, failures);
        }
        return new AstParseResult(parsed, failures);
    }

    private static void parseOne(JavaParser parser, Path src,
                                 List<ParsedFile> parsed, List<ParseFailure> failures) {
        try {
            ParseResult<CompilationUnit> result = parser.parse(src);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                failures.add(new ParseFailure(src, result.getProblems().isEmpty()
                        ? "unknown parse error"
                        : result.getProblems().get(0).getMessage()));
                return;
            }
            CompilationUnit cu = result.getResult().get();
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            String className = cu.getTypes().stream()
                    .findFirst()
                    .map(TypeDeclaration::getNameAsString)
                    .orElse("");
            parsed.add(new ParsedFile(src, pkg, className, cu));
        } catch (Throwable t) {
            failures.add(new ParseFailure(src, t.getClass().getSimpleName() + ": " + t.getMessage()));
        }
    }
}
