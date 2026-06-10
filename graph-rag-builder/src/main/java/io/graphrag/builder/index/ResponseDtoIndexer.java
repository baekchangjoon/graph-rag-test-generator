package io.graphrag.builder.index;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.declaration.CtRecord;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
        Launcher launcher = new Launcher();
        launcher.addInputResource(srcDir.toString());
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setComplianceLevel(17);
        CtModel model = launcher.buildModel();

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
