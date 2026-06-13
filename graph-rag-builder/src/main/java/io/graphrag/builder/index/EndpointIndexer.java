package io.graphrag.builder.index;

import io.graphrag.builder.run.AuthConfig;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spoon(noClasspath) 기반 L1 구조 인덱싱.
 */
public class EndpointIndexer {

    private static final String REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController";
    private static final String REQUEST_MAPPING  = "org.springframework.web.bind.annotation.RequestMapping";
    private static final String REQUEST_BODY     = "org.springframework.web.bind.annotation.RequestBody";
    private static final String PATH_VARIABLE    = "org.springframework.web.bind.annotation.PathVariable";
    private static final String REQUEST_PARAM    = "org.springframework.web.bind.annotation.RequestParam";

    /** Spring mapping annotation FQN → HTTP method name. */
    private static final Map<String, String> MAPPING_TO_METHOD = new LinkedHashMap<>();
    static {
        MAPPING_TO_METHOD.put("org.springframework.web.bind.annotation.GetMapping",    "GET");
        MAPPING_TO_METHOD.put("org.springframework.web.bind.annotation.PostMapping",   "POST");
        MAPPING_TO_METHOD.put("org.springframework.web.bind.annotation.PutMapping",    "PUT");
        MAPPING_TO_METHOD.put("org.springframework.web.bind.annotation.DeleteMapping", "DELETE");
        MAPPING_TO_METHOD.put("org.springframework.web.bind.annotation.PatchMapping",  "PATCH");
    }

    /** 기존 단일 인자 오버로드 — 기존 호출 사이트와 호환 유지. */
    public IndexResult index(Path sutSrcDir) {
        return index(sutSrcDir, null);
    }

    public IndexResult index(Path sutSrcDir, AuthConfig authConfig) {
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
                String httpMethod = null;
                CtAnnotation<?> mapping = null;
                for (Map.Entry<String, String> entry : MAPPING_TO_METHOD.entrySet()) {
                    CtAnnotation<?> a = findAnnotation(method, entry.getKey());
                    if (a != null) {
                        httpMethod = entry.getValue();
                        mapping = a;
                        break;
                    }
                }
                if (httpMethod == null) continue;

                String fullPath = joinPaths(basePath, annotationPath(mapping));
                List<EndpointParam> params = extractParams(method, model, bodyShapes);
                endpoints.add(new Endpoint(
                        endpointId(httpMethod, fullPath),
                        httpMethod,
                        fullPath,
                        type.getQualifiedName().replace('$', '.'),
                        method.getSimpleName(),
                        params,
                        authRequired(fullPath, authConfig)));
            }
        }
        endpoints.sort((a, b) -> a.id().compareTo(b.id()));
        return new IndexResult(endpoints, bodyShapes);
    }

    private static boolean authRequired(String path, AuthConfig authConfig) {
        return authConfig != null
                && !path.equals(authConfig.loginPath())
                && !authConfig.publicPaths().contains(path);
    }

    private List<EndpointParam> extractParams(CtMethod<?> method, CtModel model,
                                              Map<String, BodyShape> bodyShapes) {
        List<EndpointParam> params = new ArrayList<>();
        for (CtParameter<?> parameter : method.getParameters()) {
            if (findAnnotation(parameter, REQUEST_BODY) != null) {
                String bodyType = parameter.getType().getQualifiedName();
                params.add(new EndpointParam(parameter.getSimpleName(), bodyType, ParamKind.BODY));
                extractBodyShape(model, bodyType).ifPresent(s -> bodyShapes.put(bodyType, s));
            } else if (findAnnotation(parameter, PATH_VARIABLE) != null) {
                params.add(new EndpointParam(
                        parameter.getSimpleName(),
                        parameter.getType().getQualifiedName(),
                        ParamKind.PATH));
            } else if (findAnnotation(parameter, REQUEST_PARAM) != null) {
                params.add(new EndpointParam(
                        parameter.getSimpleName(),
                        parameter.getType().getQualifiedName(),
                        ParamKind.QUERY));
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
        // Spoon in noClasspath mode may resolve wildcard-imported annotations with a wrong
        // package (e.g. "x.GetMapping" instead of the Spring FQN). Fall back to simple-name
        // comparison so both individual imports and wildcard imports work.
        String simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
        for (CtAnnotation<?> annotation : element.getAnnotations()) {
            String fqn = annotation.getAnnotationType().getQualifiedName();
            if (qualifiedName.equals(fqn) || simpleName.equals(annotation.getAnnotationType().getSimpleName())) {
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
