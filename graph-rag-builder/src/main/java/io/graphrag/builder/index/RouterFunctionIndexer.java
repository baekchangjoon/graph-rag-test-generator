package io.graphrag.builder.index;

import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ParamKind;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtExecutableReferenceExpression;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WebFlux 함수형 라우팅(RouterFunctions.route().GET/POST/...(path, handler)) 인덱싱.
 * @RestController/@*Mapping이 없어 EndpointIndexer가 보지 못하는 라우트를 정적으로 발견한다.
 * 별도 indexer 패턴(KafkaListenerIndexer 참조).
 */
public class RouterFunctionIndexer {

    private static final Logger log = LoggerFactory.getLogger(RouterFunctionIndexer.class);
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
                    // Fix 1: conservative receiver-type guard to cut false positives.
                    // When the target type is resolvable, require it to be a RouterFunctions builder.
                    // noClasspath 모드에서는 타입이 해석되지 않는 경우가 많다(빈 문자열);
                    // 그 때는 보수적으로 통과시켜 미탐(false negative)을 막는다.
                    var target = inv.getTarget();
                    String targetType = (target != null && target.getType() != null)
                            ? target.getType().getSimpleName() : "";
                    if (!targetType.isEmpty()
                            && !targetType.contains("RouterFunction")
                            && !targetType.contains("RequestPredicates")) {
                        continue;
                    }
                    List<EndpointParam> params = new ArrayList<>();
                    if (inv.getArguments().size() >= 2) {
                        enrichFromHandler(model, inv.getArguments().get(1), params, bodyShapes);
                    }
                    // Fix B: back-extract path-template placeholders as PATH params.
                    // After handler enrichment, any placeholder not already covered by an explicit
                    // pathVariable("x") call is added as PATH (java.lang.String) — same convention
                    // as EndpointIndexer.extractPlaceholders().
                    Set<String> existingPathNames = new HashSet<>();
                    for (EndpointParam p : params) {
                        if (p.kind() == ParamKind.PATH) {
                            existingPathNames.add(p.name());
                        }
                    }
                    for (String placeholder : EndpointIndexer.extractPlaceholders(path)) {
                        if (!existingPathNames.contains(placeholder)) {
                            params.add(new EndpointParam(placeholder, "java.lang.String", ParamKind.PATH));
                            existingPathNames.add(placeholder);
                        }
                    }
                    // Synthetic-body fallback: non-GET with no PATH or BODY param still gets a
                    // synthetic shape so the explore stage does not skip it. Note: PATH params from
                    // back-extraction above count — so a route with only a path placeholder will
                    // already have a PATH param and will NOT get a synthetic body here.
                    if (!"GET".equals(verb) && params.stream().noneMatch(
                            p -> p.kind() == ParamKind.PATH || p.kind() == ParamKind.BODY)) {
                        BodyShape synthShape = BodyShape.empty();
                        params.add(new EndpointParam("body", synthShape.javaType(), ParamKind.BODY));
                        bodyShapes.putIfAbsent(synthShape.javaType(), synthShape);
                        log.warn("functional route {} {}: body 타입 미해석 → synthetic shape (best-effort)", verb, path);
                    }
                    // Fix C: sort params PATH → QUERY → FORM → BODY (stable, consistent with EndpointIndexer).
                    params.sort(Comparator.comparingInt(p -> switch (p.kind()) {
                        case PATH -> 0;
                        case QUERY -> 1;
                        case FORM -> 2;
                        case BODY -> 3;
                        default -> 4;
                    }));
                    endpoints.add(new Endpoint(
                            EndpointIds.of(verb, path),
                            verb,
                            path,
                            type.getQualifiedName().replace('$', '.'),
                            method.getSimpleName(),
                            List.copyOf(params),
                            false));
                }
            }
        }
        endpoints.sort(Comparator.comparing(Endpoint::id));
        return new IndexResult(endpoints, bodyShapes, Set.of());
    }

    /**
     * 핸들러 인자(h::add 형태의 메서드 참조)에서 PATH/BODY 파라미터를 추출한다 (best-effort).
     * Spoon noClasspath 모드에서 참조 해석에 실패하면 params를 비운 채로 반환한다.
     */
    private static void enrichFromHandler(CtModel model, CtExpression<?> handlerArg,
            List<EndpointParam> params, Map<String, BodyShape> bodyShapes) {
        CtMethod<?> handlerMethod = resolveHandlerMethod(model, handlerArg);
        if (handlerMethod == null || handlerMethod.getBody() == null) {
            return;
        }
        for (CtInvocation<?> inv : handlerMethod.getElements(new TypeFilter<>(CtInvocation.class))) {
            String name = inv.getExecutable().getSimpleName();
            if ("pathVariable".equals(name) && !inv.getArguments().isEmpty()
                    && inv.getArguments().get(0) instanceof CtLiteral<?> lit
                    && lit.getValue() instanceof String varName) {
                params.add(new EndpointParam(varName, "java.lang.String", ParamKind.PATH));
            } else if (("bodyToMono".equals(name) || "bodyToFlux".equals(name)) && !inv.getArguments().isEmpty()
                    && inv.getArguments().get(0) instanceof CtFieldRead<?> fieldRead
                    && fieldRead.getTarget() instanceof CtTypeAccess<?> typeAccess) {
                CtTypeReference<?> accessed = typeAccess.getAccessedType();
                if (accessed != null && !accessed.getQualifiedName().isBlank()) {
                    String fqn = accessed.getQualifiedName();
                    params.add(new EndpointParam("body", fqn, ParamKind.BODY));
                    // Fix A: bodyToFlux receives a JSON array → register collection=true so
                    // SampleInputSynthesizer emits an ArrayNode. bodyToMono keeps collection=false.
                    boolean isFlux = "bodyToFlux".equals(name);
                    BodyShapeExtractor.extract(model, fqn).ifPresent(extracted -> {
                        BodyShape shape = isFlux
                                ? new BodyShape(fqn, extracted.fields(), true)
                                : extracted;
                        // collection=true wins: a later bodyToMono on the same type must not
                        // overwrite a collection shape already registered by bodyToFlux.
                        bodyShapes.merge(fqn, shape, (existing, incoming) ->
                                existing.collection() ? existing : incoming);
                    });
                }
            }
        }
    }

    /**
     * {@code h::add} 형태의 메서드 참조를 Spoon 모델에서 실제 {@link CtMethod}로 해석한다.
     * noClasspath 모드에서는 선언 타입을 FQN으로 해석할 수 없는 경우가 많으므로,
     * 선언 타입 심플 네임 + 메서드 심플 네임으로 매칭한다.
     *
     * <p><strong>First-match limitation:</strong> when multiple classes or overloads share
     * the same simple name, this method returns the first match from
     * {@link spoon.reflect.CtModel#getAllTypes()} (noClasspath best-effort); the result
     * could later be refined by comparing parameter counts or fully-qualified types once
     * classpath information is available.
     */
    private static CtMethod<?> resolveHandlerMethod(CtModel model,
            CtExpression<?> handlerArg) {
        if (!(handlerArg instanceof CtExecutableReferenceExpression<?, ?> methodRef)) {
            return null;
        }
        var execRef = methodRef.getExecutable();
        if (execRef == null) {
            return null;
        }
        String methodName = execRef.getSimpleName();
        CtTypeReference<?> declaringTypeRef = execRef.getDeclaringType();
        String declaringTypeSimpleName = (declaringTypeRef != null)
                ? declaringTypeRef.getSimpleName() : null;

        for (CtType<?> type : model.getAllTypes()) {
            if (declaringTypeSimpleName != null
                    && !type.getSimpleName().equals(declaringTypeSimpleName)) {
                continue;
            }
            for (CtMethod<?> candidate : type.getMethods()) {
                if (candidate.getSimpleName().equals(methodName)) {
                    return candidate;
                }
            }
        }
        return null;
    }
}
