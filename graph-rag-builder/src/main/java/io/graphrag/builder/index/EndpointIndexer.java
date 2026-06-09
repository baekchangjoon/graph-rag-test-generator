package io.graphrag.builder.index;

import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ParamKind;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtRecord;
import spoon.reflect.declaration.CtRecordComponent;
import spoon.reflect.declaration.CtType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spoon(noClasspath) 기반 L1 구조 인덱싱.
 * Phase 0 범위: @RestController + @PostMapping + @RequestBody.
 */
public class EndpointIndexer {

    private static final String REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController";
    private static final String REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping";
    private static final String POST_MAPPING = "org.springframework.web.bind.annotation.PostMapping";
    private static final String REQUEST_BODY = "org.springframework.web.bind.annotation.RequestBody";

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
            if (findAnnotation(type, REST_CONTROLLER) == null) {
                continue;
            }
            String basePath = annotationPath(findAnnotation(type, REQUEST_MAPPING));
            for (CtMethod<?> method : type.getMethods()) {
                CtAnnotation<?> postMapping = findAnnotation(method, POST_MAPPING);
                if (postMapping == null) {
                    continue;
                }
                String fullPath = joinPaths(basePath, annotationPath(postMapping));
                List<EndpointParam> params = extractParams(method, model, bodyShapes);
                endpoints.add(new Endpoint(
                        endpointId("POST", fullPath),
                        "POST",
                        fullPath,
                        type.getQualifiedName().replace('$', '.'),
                        method.getSimpleName(),
                        params,
                        false));
            }
        }
        endpoints.sort((a, b) -> a.id().compareTo(b.id()));
        return new IndexResult(endpoints, bodyShapes);
    }

    private List<EndpointParam> extractParams(CtMethod<?> method, CtModel model,
                                              Map<String, BodyShape> bodyShapes) {
        List<EndpointParam> params = new ArrayList<>();
        for (CtParameter<?> parameter : method.getParameters()) {
            if (findAnnotation(parameter, REQUEST_BODY) != null) {
                String bodyType = parameter.getType().getQualifiedName();
                params.add(new EndpointParam(parameter.getSimpleName(), bodyType, ParamKind.BODY));
                extractBodyShape(model, bodyType).ifPresent(s -> bodyShapes.put(bodyType, s));
            }
        }
        return params;
    }

    private java.util.Optional<BodyShape> extractBodyShape(CtModel model, String qualifiedName) {
        for (CtType<?> type : model.getAllTypes()) {
            CtType<?> target = findNested(type, qualifiedName);
            if (target == null) {
                continue;
            }
            List<BodyShape.BodyField> fields = new ArrayList<>();
            if (target instanceof CtRecord record) {
                for (CtRecordComponent component : record.getRecordComponents()) {
                    fields.add(new BodyShape.BodyField(
                            component.getSimpleName(),
                            component.getType().getQualifiedName()));
                }
            } else {
                target.getFields().forEach(field -> fields.add(new BodyShape.BodyField(
                        field.getSimpleName(), field.getType().getQualifiedName())));
            }
            return java.util.Optional.of(new BodyShape(qualifiedName, fields));
        }
        return java.util.Optional.empty();
    }

    private CtType<?> findNested(CtType<?> type, String qualifiedName) {
        if (type.getQualifiedName().equals(qualifiedName)) {
            return type;
        }
        for (CtType<?> nested : type.getNestedTypes()) {
            CtType<?> found = findNested(nested, qualifiedName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static CtAnnotation<?> findAnnotation(CtElement element, String qualifiedName) {
        for (CtAnnotation<?> annotation : element.getAnnotations()) {
            if (qualifiedName.equals(annotation.getAnnotationType().getQualifiedName())) {
                return annotation;
            }
        }
        return null;
    }

    private static String annotationPath(CtAnnotation<?> annotation) {
        if (annotation == null) {
            return "";
        }
        CtExpression<?> value = annotation.getValues().get("value");
        if (value == null) {
            value = annotation.getValues().get("path");
        }
        if (value instanceof CtLiteral<?> literal && literal.getValue() instanceof String s) {
            return s;
        }
        return "";
    }

    private static String joinPaths(String base, String sub) {
        String joined = (base + "/" + sub).replaceAll("/+", "/");
        if (joined.endsWith("/") && joined.length() > 1) {
            joined = joined.substring(0, joined.length() - 1);
        }
        return joined.startsWith("/") ? joined : "/" + joined;
    }

    static String endpointId(String method, String path) {
        return (method + path).toLowerCase().replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }
}
