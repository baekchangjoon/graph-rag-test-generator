package io.graphrag.builder.staticanalysis.ast;

import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Builds a {@link JavaSymbolSolver} backed by:
 * <ul>
 *   <li>{@link ReflectionTypeSolver} — JDK + classpath types reachable from this JVM.</li>
 *   <li>{@link JavaParserTypeSolver} on the SUT source root — in-project type resolution.</li>
 *   <li>{@link JarTypeSolver} per element of {@code classpathJars} — optional, e.g. for
 *       resolving Spring/Jakarta annotation types when we want full qualified-name checks.</li>
 * </ul>
 *
 * <p>Any single solver can fail to resolve; that is normal. Callers catch
 * {@code UnsolvedSymbolException} from {@code Resolvable#resolve()} call sites.
 */
public final class SymbolResolverFactory {

    private SymbolResolverFactory() {}

    public static JavaSymbolSolver create(Path sourceRoot, List<Path> classpathJars) {
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(classpathJars, "classpathJars");

        CombinedTypeSolver combined = new CombinedTypeSolver();
        combined.add(new ReflectionTypeSolver(false));
        if (Files.isDirectory(sourceRoot)) {
            combined.add(new JavaParserTypeSolver(sourceRoot));
        }
        for (Path jar : classpathJars) {
            try {
                combined.add(new JarTypeSolver(jar));
            } catch (IOException ex) {
                // Propagated as IllegalArgumentException — callers are responsible for providing
                // readable jar paths. We do not skip silently because a missing classpath jar will
                // cause downstream symbol-resolution failures that are harder to diagnose later.
                throw new IllegalArgumentException("cannot read jar " + jar + ": " + ex.getMessage(), ex);
            }
        }
        return new JavaSymbolSolver(combined);
    }
}
