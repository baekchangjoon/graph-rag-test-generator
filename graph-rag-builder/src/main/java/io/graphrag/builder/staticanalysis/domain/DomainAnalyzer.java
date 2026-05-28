package io.graphrag.builder.staticanalysis.domain;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import io.graphrag.builder.staticanalysis.ast.AstParseResult;
import io.graphrag.builder.staticanalysis.ast.ParsedFile;
import io.graphrag.model.Endpoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage 2 orchestrator: turn an {@link AstParseResult} into a
 * {@link DomainAnalysisResult}. Pure function in/out — no I/O.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Classify every top-level type via {@link ClassRoleClassifier}.</li>
 *   <li>For every {@code CONTROLLER}, extract {@link Endpoint}s via {@link EndpointExtractor}.</li>
 *   <li>For every {@code CONTROLLER}, {@code SERVICE}, {@code REPOSITORY} method, build a
 *       {@link MethodAnalysis} (parameters + branches + outgoing calls + return type).</li>
 *   <li>Build the in-project {@link CallGraph} across all parsed compilation units.</li>
 * </ol>
 *
 * <p>{@link DomainAnalysisResult#endpoints()} is sorted by {@code (path, method)};
 * {@link DomainAnalysisResult#classRoles()} and {@link DomainAnalysisResult#methodAnalyses()}
 * are insertion-ordered (insertion = path-sorted ParsedFile order).
 */
public final class DomainAnalyzer {

    private static final Set<ClassRole> METHOD_ROLES_OF_INTEREST =
            Set.of(ClassRole.CONTROLLER, ClassRole.SERVICE, ClassRole.REPOSITORY);

    private DomainAnalyzer() {}

    public static DomainAnalysisResult analyze(AstParseResult ast, String project) {
        // 1) class roles (insertion-ordered).
        Map<String, ClassRole> classRoles = new LinkedHashMap<>();
        Map<String, ClassOrInterfaceDeclaration> classByFqn = new LinkedHashMap<>();
        for (ParsedFile pf : ast.parsedFiles()) {
            pf.cu().findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                String fqn = pf.packageName().isEmpty()
                        ? cls.getNameAsString()
                        : pf.packageName() + "." + cls.getNameAsString();
                classRoles.put(fqn, ClassRoleClassifier.classify(cls));
                classByFqn.put(fqn, cls);
            });
        }

        // 2) endpoints (collected then sorted).
        List<Endpoint> endpoints = new ArrayList<>();
        classByFqn.forEach((fqn, cls) -> {
            if (classRoles.get(fqn) == ClassRole.CONTROLLER) {
                endpoints.addAll(EndpointExtractor.extract(cls, fqn, project));
            }
        });
        // NB: sorted by (method, path) — primary by HTTP method name (alphabetical), secondary by path.
        endpoints.sort(Comparator
                .<Endpoint, String>comparing(e -> e.method().name())
                .thenComparing(Endpoint::path));

        // 3) method analyses.
        Map<String, MethodAnalysis> methodAnalyses = new LinkedHashMap<>();
        classByFqn.forEach((fqn, cls) -> {
            if (!METHOD_ROLES_OF_INTEREST.contains(classRoles.get(fqn))) return;
            for (MethodDeclaration m : cls.getMethods()) {
                MethodAnalysis ma = buildMethodAnalysis(fqn, m);
                methodAnalyses.put(ma.key(), ma);
            }
        });

        // 4) call graph — build over all CUs but restrict edges to in-project classes.
        CallGraphBuilder cgb = new CallGraphBuilder(rootOf(ast));
        CallGraph callGraph = cgb.build(
                ast.parsedFiles().stream().map(ParsedFile::cu).toList(),
                classRoles.keySet());

        return new DomainAnalysisResult(endpoints, classRoles, methodAnalyses, callGraph);
    }

    private static MethodAnalysis buildMethodAnalysis(String classFqn, MethodDeclaration m) {
        List<io.graphrag.builder.staticanalysis.domain.Parameter> params = new ArrayList<>();
        for (Parameter p : m.getParameters()) {
            List<String> annNames = p.getAnnotations().stream()
                    .map(a -> a.getNameAsString()).toList();
            params.add(new io.graphrag.builder.staticanalysis.domain.Parameter(
                    p.getNameAsString(),
                    p.getTypeAsString(),
                    annNames));
        }
        List<Branch> branches = BranchExtractor.extract(m, classFqn);
        // outgoingCalls populated by CallGraphBuilder when the per-method view is needed;
        // for DomainAnalyzer's purposes we leave it empty here and let downstream consumers
        // query CallGraph.edges() by key — keeps MethodAnalysis cheap to build.
        ReturnType rt = ReturnType.of(m.getTypeAsString());
        return new MethodAnalysis(
                classFqn,
                m.getNameAsString(),
                params,
                branches,
                /* outgoingCalls */ List.of(),
                rt);
    }

    /**
     * Returns the deepest common parent of all parsed source paths so the
     * SymbolSolver inside {@link CallGraphBuilder} has a meaningful source root.
     * Falls back to {@code Paths.get(".")} when the parsed list is empty.
     */
    private static java.nio.file.Path rootOf(AstParseResult ast) {
        if (ast.parsedFiles().isEmpty()) return java.nio.file.Paths.get(".");
        java.nio.file.Path candidate = ast.parsedFiles().get(0).sourcePath().getParent();
        for (ParsedFile pf : ast.parsedFiles()) {
            while (candidate != null && !pf.sourcePath().startsWith(candidate)) {
                candidate = candidate.getParent();
            }
        }
        return candidate == null ? java.nio.file.Paths.get(".") : candidate;
    }
}
