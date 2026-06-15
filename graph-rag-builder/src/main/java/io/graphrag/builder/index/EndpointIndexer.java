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
    private static final String CONTROLLER       = "org.springframework.stereotype.Controller";
    private static final String REQUEST_MAPPING  = "org.springframework.web.bind.annotation.RequestMapping";
    private static final String REQUEST_BODY     = "org.springframework.web.bind.annotation.RequestBody";
    private static final String PATH_VARIABLE    = "org.springframework.web.bind.annotation.PathVariable";
    private static final String REQUEST_PARAM    = "org.springframework.web.bind.annotation.RequestParam";
    private static final String MODEL_ATTRIBUTE  = "org.springframework.web.bind.annotation.ModelAttribute";

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
            boolean rest = findAnnotation(type, REST_CONTROLLER) != null;
            // @Controller(폼/뷰)도 처리 — @RestController는 @Controller의 meta-annotation이지만 noClasspath에서
            // meta-resolution이 불확실하므로 둘 다 직접 본다. rest=true면 JSON 경로(불변), 아니면 폼 경로.
            boolean controller = rest || findAnnotation(type, CONTROLLER) != null;
            if (!controller) {
                continue;
            }
            String basePath = annotationPath(findAnnotation(type, REQUEST_MAPPING));
            // 컨트롤러 타입의 모든 메서드에서 @PathVariable 수집(클래스-레벨/헬퍼-전용 path 변수 역추출용).
            Map<String, String> pathVarTypes = collectPathVarTypes(type);
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
                List<EndpointParam> params = extractParams(method, model, bodyShapes, !rest);
                // 클래스-레벨/헬퍼-전용 path 변수 역추출: path 템플릿의 placeholder 중 핸들러가 PATH로 잡지
                // 못한 것을, 같은 컨트롤러의 @PathVariable 타입 신호(pathVarTypes)로 PATH 파라미터로 추가한다.
                // 타입 신호 없으면 skip(센티널 폴백 유지, 회귀 0).
                java.util.Set<String> alreadyPath = new java.util.HashSet<>();
                for (EndpointParam p : params) {
                    if (p.kind() == ParamKind.PATH) {
                        alreadyPath.add(p.name());
                    }
                }
                for (String placeholder : extractPlaceholders(fullPath)) {
                    if (!alreadyPath.contains(placeholder) && pathVarTypes.containsKey(placeholder)) {
                        params.add(new EndpointParam(placeholder, pathVarTypes.get(placeholder), ParamKind.PATH));
                    }
                }
                // 정렬 규약: PATH → QUERY → FORM → BODY (동일 kind 내 등장 순서 유지, 안정 정렬).
                params.sort(java.util.Comparator.comparingInt(p -> kindOrder(p.kind())));
                // @Controller-only(폼) 핸들러는 FORM 커맨드 객체가 있을 때만 인덱싱(뷰-표시 핸들러는 분기 없음 → skip).
                if (!rest && params.stream().noneMatch(p -> p.kind() == ParamKind.FORM)) {
                    continue;
                }
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
                                              Map<String, BodyShape> bodyShapes, boolean formMode) {
        List<EndpointParam> params = new ArrayList<>();
        boolean formAdded = false;   // 단일 커맨드 객체로 스코프 — 첫 FORM 파라미터만
        for (CtParameter<?> parameter : method.getParameters()) {
            if (findAnnotation(parameter, REQUEST_BODY) != null) {
                String bodyType = parameter.getType().getQualifiedName();
                params.add(new EndpointParam(parameter.getSimpleName(), bodyType, ParamKind.BODY));
                extractBodyShape(model, bodyType).ifPresent(s -> bodyShapes.put(bodyType, s));
            } else if (findAnnotation(parameter, PATH_VARIABLE) != null) {
                // 이름은 정규화(@PathVariable value/name 우선) — path 템플릿 {x}와 일치해야 치환·역추출이 정확.
                params.add(new EndpointParam(
                        pathVarName(parameter),
                        parameter.getType().getQualifiedName(),
                        ParamKind.PATH));
            } else if (findAnnotation(parameter, REQUEST_PARAM) != null) {
                params.add(new EndpointParam(
                        parameter.getSimpleName(),
                        parameter.getType().getQualifiedName(),
                        ParamKind.QUERY));
            } else if (formMode && !formAdded) {
                // @Controller 폼 커맨드 객체: @ModelAttribute 또는 SUT 클래스(필드 해석)인 unannotated POJO.
                // BindingResult/Model 등 프레임워크 타입은 bodyShape 미해석 → 자동 제외.
                String formType = parameter.getType().getQualifiedName();
                java.util.Optional<BodyShape> shape = extractBodyShape(model, formType);
                boolean modelAttr = findAnnotation(parameter, MODEL_ATTRIBUTE) != null;
                if (shape.isPresent() && (modelAttr || !shape.get().fields().isEmpty())) {
                    shape.ifPresent(s -> bodyShapes.put(formType, s));
                    params.add(new EndpointParam(parameter.getSimpleName(), formType, ParamKind.FORM));
                    formAdded = true;
                }
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

    /** path 변수 정렬 순서: PATH(0) → QUERY(1) → FORM(2) → BODY(3). */
    private static int kindOrder(ParamKind kind) {
        return switch (kind) {
            case PATH -> 0;
            case QUERY -> 1;
            case FORM -> 2;
            case BODY -> 3;
            default -> 4;
        };
    }

    /** path 템플릿의 {placeholder} 집합(등장 순서 유지). 슬래시·중괄호 불포함만 — 콜론 정규식은 매칭 안 됨. */
    private static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile("\\{([^/}]+)}");

    static java.util.LinkedHashSet<String> extractPlaceholders(String path) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher matcher = PLACEHOLDER.matcher(path);
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
        return out;
    }

    /** @PathVariable의 정규화 이름: value → name → 파라미터 단순명. */
    private static String pathVarName(CtParameter<?> parameter) {
        CtAnnotation<?> pv = findAnnotation(parameter, PATH_VARIABLE);
        if (pv != null) {
            String explicit = annotationStringValue(pv, "value", "name");
            if (explicit != null) {
                return explicit;
            }
        }
        return parameter.getSimpleName();
    }

    /** @PathVariable required 속성(미지정/명시 true → true). */
    private static boolean pathVarRequired(CtParameter<?> parameter) {
        CtAnnotation<?> pv = findAnnotation(parameter, PATH_VARIABLE);
        if (pv == null) {
            return true;
        }
        CtExpression<?> r = pv.getValues().get("required");
        if (r instanceof CtLiteral<?> literal && literal.getValue() instanceof Boolean b) {
            return b;
        }
        return true;
    }

    /**
     * 컨트롤러 타입의 모든 메서드(@ModelAttribute/@InitBinder/핸들러 무관)에서 @PathVariable을 수집해
     * 정규화이름 → javaType 맵을 만든다. 충돌(동일 이름 타입 2종): required 미지정/true가 required=false보다
     * 우선, 그래도 동률이면 첫 등장 유지(`@PathVariable`은 어느 메서드에 있든 같은 이름=같은 라우트 변수).
     */
    private static Map<String, String> collectPathVarTypes(CtType<?> type) {
        Map<String, String> types = new LinkedHashMap<>();
        Map<String, Boolean> required = new HashMap<>();
        for (CtMethod<?> method : type.getMethods()) {
            for (CtParameter<?> parameter : method.getParameters()) {
                if (findAnnotation(parameter, PATH_VARIABLE) == null) {
                    continue;
                }
                String name = pathVarName(parameter);
                String javaType = parameter.getType().getQualifiedName();
                boolean req = pathVarRequired(parameter);
                if (!types.containsKey(name)) {
                    types.put(name, javaType);
                    required.put(name, req);
                } else if (req && !required.get(name)) {
                    // 기존이 required=false인데 새것이 required=true → 더 강한 신호로 교체.
                    types.put(name, javaType);
                    required.put(name, true);
                }
            }
        }
        return types;
    }

    private static String annotationStringValue(CtAnnotation<?> annotation, String... keys) {
        for (String key : keys) {
            CtExpression<?> v = annotation.getValues().get(key);
            if (v instanceof CtLiteral<?> literal && literal.getValue() instanceof String s && !s.isBlank()) {
                return s;
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
