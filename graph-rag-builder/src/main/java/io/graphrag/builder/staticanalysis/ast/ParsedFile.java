package io.graphrag.builder.staticanalysis.ast;

import com.github.javaparser.ast.CompilationUnit;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A single successfully-parsed Java source file.
 *
 * @param sourcePath  absolute path to the {@code .java} file
 * @param packageName the file's declared package, or {@code ""} for the default package
 * @param className   top-level class/interface/enum/record simple name, or {@code ""}
 *                    for files that only contain a {@code package-info}
 * @param cu          JavaParser AST root, never {@code null}
 */
public record ParsedFile(Path sourcePath, String packageName, String className, CompilationUnit cu) {

    public ParsedFile {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(packageName, "packageName");
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(cu, "cu");
    }
}
