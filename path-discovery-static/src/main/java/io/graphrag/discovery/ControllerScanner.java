package io.graphrag.discovery;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import io.graphrag.model.HttpMethod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Scans a directory of {@code .java} files and emits one {@link DiscoveredHandler}
 * per Spring REST-handler method it can recognize.
 *
 * <p>What "can recognize" means is deliberately conservative — see
 * {@code docs/22-static-discovery-limits.md} for the explicit failure modes.
 * High-level rules:
 * <ul>
 *   <li>A class is a controller iff it carries {@code @RestController} or
 *       {@code @Controller} as an annotation simple name.</li>
 *   <li>A method is a handler iff it carries {@code @GetMapping},
 *       {@code @PostMapping}, … <em>or</em> {@code @RequestMapping(method=...)}.</li>
 *   <li>Path = class-level base path (from {@code @RequestMapping} on the class) +
 *       method-level path. Both are read as literal strings — constants pulled from
 *       other files are not resolved.</li>
 *   <li>Method parameters are inspected for {@code @PathVariable},
 *       {@code @RequestParam}, {@code @RequestBody}. Parameter name comes from the
 *       annotation's explicit {@code value} / {@code name} attribute when present,
 *       otherwise from the Java parameter name.</li>
 * </ul>
 */
public final class ControllerScanner {

    /** Scan a single source root recursively, parsing every .java file. */
    public static List<DiscoveredHandler> scan(Path sourceRoot) throws IOException {
        List<DiscoveredHandler> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.toString().endsWith(".java"))
                  .forEach(p -> scanFile(p, out));
        }
        return out;
    }

    private static void scanFile(Path javaFile, List<DiscoveredHandler> sink) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(javaFile);
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                if (!isController(cls)) return;
                String fqClassName = cu.getPackageDeclaration()
                        .map(p -> p.getNameAsString() + "." + cls.getNameAsString())
                        .orElse(cls.getNameAsString());
                String basePath = classBasePath(cls);
                for (MethodDeclaration m : cls.getMethods()) {
                    scanHandler(m, fqClassName, basePath).ifPresent(sink::add);
                }
            });
        } catch (Exception ignored) {
            /* A single un-parseable file should not abort the whole scan. */
        }
    }

    private static boolean isController(ClassOrInterfaceDeclaration cls) {
        for (AnnotationExpr ann : cls.getAnnotations()) {
            if (MappingAnnotation.isControllerAnnotation(ann.getNameAsString())) return true;
        }
        return false;
    }

    private static String classBasePath(ClassOrInterfaceDeclaration cls) {
        for (AnnotationExpr ann : cls.getAnnotations()) {
            if (MappingAnnotation.isRequestMapping(ann.getNameAsString())) {
                List<String> paths = AnnotationValueReader.readPaths(ann);
                if (!paths.isEmpty()) return normalize(paths.get(0));
            }
        }
        return "";
    }

    private static Optional<DiscoveredHandler> scanHandler(MethodDeclaration m,
                                                           String fqClassName,
                                                           String basePath) {
        HandlerMapping mapping = findHandlerMapping(m).orElse(null);
        if (mapping == null) return Optional.empty();

        String methodPath = mapping.path.isEmpty() ? "" : normalize(mapping.path);
        String fullPath = joinPaths(basePath, methodPath);
        if (fullPath.isEmpty()) fullPath = "/";

        List<HandlerParam> path = new ArrayList<>();
        List<HandlerParam> query = new ArrayList<>();
        boolean hasBody = false;
        for (Parameter p : m.getParameters()) {
            HandlerParamKind kind = paramKind(p);
            if (kind == HandlerParamKind.PATH) {
                path.add(new HandlerParam(paramName(p, "PathVariable"),
                        p.getTypeAsString(), HandlerParam.ParamSource.PATH));
            } else if (kind == HandlerParamKind.QUERY) {
                query.add(new HandlerParam(paramName(p, "RequestParam"),
                        p.getTypeAsString(), HandlerParam.ParamSource.QUERY));
            } else if (kind == HandlerParamKind.BODY) {
                hasBody = true;
            }
        }
        return Optional.of(new DiscoveredHandler(
                mapping.method, fullPath, fqClassName, m.getNameAsString(),
                path, query, hasBody));
    }

    private static Optional<HandlerMapping> findHandlerMapping(MethodDeclaration m) {
        for (AnnotationExpr ann : m.getAnnotations()) {
            String name = ann.getNameAsString();
            if (MappingAnnotation.isShorthandMethodMapping(name)) {
                HttpMethod method = MappingAnnotation.METHOD_LEVEL.get(name);
                List<String> paths = AnnotationValueReader.readPaths(ann);
                return Optional.of(new HandlerMapping(method, paths.isEmpty() ? "" : paths.get(0)));
            }
            if (MappingAnnotation.isRequestMapping(name) && ann instanceof NormalAnnotationExpr na) {
                Optional<HttpMethod> method = AnnotationValueReader.readMethodAttribute(na)
                        .flatMap(ControllerScanner::parseHttpMethod);
                if (method.isEmpty()) continue;            // a RequestMapping without method= is non-routing
                List<String> paths = AnnotationValueReader.readPaths(ann);
                return Optional.of(new HandlerMapping(method.get(),
                        paths.isEmpty() ? "" : paths.get(0)));
            }
        }
        return Optional.empty();
    }

    private static Optional<HttpMethod> parseHttpMethod(String name) {
        try {
            return Optional.of(HttpMethod.valueOf(name));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private enum HandlerParamKind { PATH, QUERY, BODY, OTHER }

    private static HandlerParamKind paramKind(Parameter p) {
        for (AnnotationExpr ann : p.getAnnotations()) {
            String name = ann.getNameAsString();
            if ("PathVariable".equals(name))  return HandlerParamKind.PATH;
            if ("RequestParam".equals(name))  return HandlerParamKind.QUERY;
            if ("RequestBody".equals(name))   return HandlerParamKind.BODY;
        }
        return HandlerParamKind.OTHER;
    }

    private static String paramName(Parameter p, String annotationSimpleName) {
        for (AnnotationExpr ann : p.getAnnotations()) {
            if (!annotationSimpleName.equals(ann.getNameAsString())) continue;
            if (ann instanceof MarkerAnnotationExpr) break;       // no explicit name
            // value = "x" or name = "x"
            List<String> values = AnnotationValueReader.readPaths(ann);
            if (!values.isEmpty()) return values.get(0);
            if (ann instanceof NormalAnnotationExpr na) {
                Optional<String> n = na.getPairs().stream()
                        .filter(pair -> "name".equals(pair.getNameAsString()))
                        .findFirst()
                        .map(pair -> pair.getValue().toString().replace("\"", ""));
                if (n.isPresent()) return n.get();
            }
        }
        return p.getNameAsString();
    }

    /** Make sure every recognized path starts with "/" and has no trailing "/". */
    private static String normalize(String s) {
        if (s.isEmpty()) return s;
        String r = s.startsWith("/") ? s : "/" + s;
        while (r.length() > 1 && r.endsWith("/")) r = r.substring(0, r.length() - 1);
        return r;
    }

    private static String joinPaths(String base, String tail) {
        if (base.isEmpty()) return tail;
        if (tail.isEmpty()) return base;
        return base + tail;
    }

    private record HandlerMapping(HttpMethod method, String path) {}

    private ControllerScanner() {}
}
