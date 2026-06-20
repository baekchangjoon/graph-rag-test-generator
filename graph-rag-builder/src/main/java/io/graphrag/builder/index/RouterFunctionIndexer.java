package io.graphrag.builder.index;

import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WebFlux 함수형 라우팅(RouterFunctions.route().GET/POST/...(path, handler)) 인덱싱.
 * @RestController/@*Mapping이 없어 EndpointIndexer가 보지 못하는 라우트를 정적으로 발견한다.
 * 별도 indexer 패턴(KafkaListenerIndexer 참조).
 */
public class RouterFunctionIndexer {

    private static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH");

    public IndexResult index(Path sutSrcDir) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(sutSrcDir.toString());
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setComplianceLevel(17);
        CtModel model = launcher.buildModel();

        List<Endpoint> endpoints = new ArrayList<>();
        Map<String, BodyShape> bodyShapes = new HashMap<>();

        for (CtType<?> type : model.getAllTypes()) {
            for (CtMethod<?> method : type.getMethods()) {
                for (CtInvocation<?> inv : method.getElements(new TypeFilter<>(CtInvocation.class))) {
                    String verb = inv.getExecutable().getSimpleName();
                    if (!HTTP_METHODS.contains(verb) || inv.getArguments().isEmpty()) {
                        continue;
                    }
                    if (!(inv.getArguments().get(0) instanceof CtLiteral<?> lit)
                            || !(lit.getValue() instanceof String path)) {
                        continue;
                    }
                    List<EndpointParam> params = new ArrayList<>();
                    endpoints.add(new Endpoint(
                            EndpointIds.of(verb, path),
                            verb,
                            path,
                            type.getQualifiedName().replace('$', '.'),
                            method.getSimpleName(),
                            params,
                            false));
                }
            }
        }
        endpoints.sort(Comparator.comparing(Endpoint::id));
        return new IndexResult(endpoints, bodyShapes, Set.of());
    }
}
