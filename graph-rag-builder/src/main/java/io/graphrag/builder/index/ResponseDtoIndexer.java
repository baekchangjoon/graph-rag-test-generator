package io.graphrag.builder.index;

import spoon.reflect.CtModel;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.declaration.CtRecord;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * SUT가 외부 HTTP 응답을 바인딩하는 DTO 타입의 필드를 수집한다 (roadmap 2.5의 근사).
 * RestTemplate getForObject(..., X.class) 류의 class 리터럴 인자에서 X를 찾는다.
 * "실제 읽은 필드"의 정밀 추적 대신 바인딩 필드 집합을 사용 상한으로 본다.
 */
public class ResponseDtoIndexer {

    private static final Set<String> CLIENT_METHODS = Set.of(
            "getForObject", "postForObject", "getForEntity", "postForEntity", "exchange");

    public List<Set<String>> extract(Path srcDir) {
        return extract(SharedSpoonModel.build(srcDir));
    }

    public List<Set<String>> extract(CtModel model) {
        List<Set<String>> fieldSets = new ArrayList<>();
        for (CtInvocation<?> invocation : model.getRootPackage()
                .getElements(new TypeFilter<>(CtInvocation.class))) {
            if (!CLIENT_METHODS.contains(invocation.getExecutable().getSimpleName())) {
                continue;
            }
            for (var argument : invocation.getArguments()) {
                if (argument instanceof CtFieldRead<?> fieldRead
                        && "class".equals(fieldRead.getVariable().getSimpleName())
                        && fieldRead.getTarget() instanceof CtTypeAccess<?> typeAccess) {
                    String dtoFqn = typeAccess.getAccessedType().getQualifiedName();
                    fieldsOf(model, dtoFqn).ifPresent(fieldSets::add);
                }
            }
        }
        return fieldSets;
    }

    /**
     * 외부 클라이언트 호출 site를 (httpMethod, pathLiteral, responseShape)로 추출한다 (REQ-004, REQ-005).
     * URL 인자에서 '/'로 시작하는 정적 리터럴 path 구간(query 제외)을 가진 호출만 emit한다.
     * 응답 class 리터럴을 못 뽑는 형태(제네릭/변수 인자)는 responseShape=empty.
     */
    public List<ExternalCallSite> extractCallSites(Path srcDir) {
        return extractCallSites(SharedSpoonModel.build(srcDir));
    }

    public List<ExternalCallSite> extractCallSites(CtModel model) {
        List<ExternalCallSite> sites = new ArrayList<>();
        for (CtInvocation<?> invocation : model.getRootPackage()
                .getElements(new TypeFilter<>(CtInvocation.class))) {
            String simpleName = invocation.getExecutable().getSimpleName();
            if (!CLIENT_METHODS.contains(simpleName)) {
                continue;
            }
            List<CtExpression<?>> args = new ArrayList<>(invocation.getArguments());
            if (args.isEmpty()) {
                continue;
            }
            String pathLiteral = pathLiteralOf(args.get(0));
            if (pathLiteral == null) {
                continue;
            }
            String httpMethod = httpMethodOf(simpleName, args);
            Optional<BodyShape> shape = responseShapeOf(model, args);
            sites.add(new ExternalCallSite(httpMethod, pathLiteral, shape));
        }
        return sites;
    }

    /** URL 인자의 정적 문자열 concat에서 '/'로 시작하는 첫 리터럴 토큰의 path(query 제외)를 반환. */
    private String pathLiteralOf(CtExpression<?> urlArg) {
        for (String token : stringLiterals(urlArg)) {
            int slash = token.indexOf('/');
            if (slash < 0) {
                continue;
            }
            String fromSlash = token.substring(slash);
            int query = fromSlash.indexOf('?');
            return query < 0 ? fromSlash : fromSlash.substring(0, query);
        }
        return null;
    }

    /** concat 트리(CtBinaryOperator PLUS)를 좌→우 순회하며 String 리터럴 값을 모은다. */
    private List<String> stringLiterals(CtExpression<?> expr) {
        List<String> out = new ArrayList<>();
        collectStringLiterals(expr, out);
        return out;
    }

    private void collectStringLiterals(CtExpression<?> expr, List<String> out) {
        if (expr instanceof CtBinaryOperator<?> binary) {
            collectStringLiterals(binary.getLeftHandOperand(), out);
            collectStringLiterals(binary.getRightHandOperand(), out);
        } else if (expr instanceof CtLiteral<?> literal
                && literal.getValue() instanceof String value) {
            out.add(value);
        }
    }

    /** get*→GET, post*→POST, exchange→인자의 HttpMethod enum 상수(변수면 빈 문자열). */
    private String httpMethodOf(String simpleName, List<CtExpression<?>> args) {
        if (simpleName.startsWith("get")) {
            return "GET";
        }
        if (simpleName.startsWith("post")) {
            return "POST";
        }
        if ("exchange".equals(simpleName) && args.size() >= 2) {
            return enumConstantName(args.get(1));
        }
        return "";
    }

    /** HttpMethod enum 상수 field-read(HttpMethod.POST)면 상수명, 변수/필드 참조면 빈 문자열. */
    private String enumConstantName(CtExpression<?> arg) {
        if (arg instanceof CtFieldRead<?> fieldRead
                && fieldRead.getTarget() instanceof CtTypeAccess<?>) {
            return fieldRead.getVariable().getSimpleName();
        }
        return "";
    }

    /** 인자 중 `X.class` 리터럴에서 X의 BodyShape를 추출. 배열은 component(collection). */
    private Optional<BodyShape> responseShapeOf(CtModel model, List<CtExpression<?>> args) {
        for (CtExpression<?> arg : args) {
            if (arg instanceof CtFieldRead<?> fieldRead
                    && "class".equals(fieldRead.getVariable().getSimpleName())
                    && fieldRead.getTarget() instanceof CtTypeAccess<?> typeAccess) {
                CtTypeReference<?> accessed = typeAccess.getAccessedType();
                if (accessed instanceof CtArrayTypeReference<?> arrayType) {
                    String componentFqn = arrayType.getComponentType().getQualifiedName();
                    return BodyShapeExtractor.extract(model, componentFqn)
                            .map(s -> new BodyShape(s.javaType(), s.fields(), true));
                }
                return BodyShapeExtractor.extract(model, accessed.getQualifiedName());
            }
        }
        return Optional.empty();
    }

    private java.util.Optional<Set<String>> fieldsOf(CtModel model, String qualifiedName) {
        for (CtType<?> type : model.getAllTypes()) {
            CtType<?> target = findNested(type, qualifiedName);
            if (target == null) {
                continue;
            }
            Set<String> fields = new LinkedHashSet<>();
            if (target instanceof CtRecord record) {
                record.getRecordComponents().forEach(c -> fields.add(c.getSimpleName()));
            } else {
                target.getFields().forEach(f -> fields.add(f.getSimpleName()));
            }
            return java.util.Optional.of(fields);
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
}
