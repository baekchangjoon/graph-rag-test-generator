package io.graphrag.builder.index;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Spring 변환 등록원을 정적 수집한다(Spoon noClasspath, spec §3.1).
 * <ul>
 *   <li>전역(convertedTypes): {@code Formatter<T>} / {@code Converter<S,T>} 구현 → 대상 타입 T FQN.</li>
 *   <li>컨트롤러-local(controllerEditors): {@code @InitBinder} 메서드 body의
 *       {@code registerCustomEditor(T.class, …)} → 그 컨트롤러 FQN별 T 집합.</li>
 * </ul>
 * 폼 커맨드 필드 타입이 여기 수집된 타입이면 REFERENCE 바인딩(런타임 토큰 trial)으로 분류한다.
 */
public class ConverterRegistryIndexer {

    private static final String FORMATTER = "org.springframework.format.Formatter";
    private static final String CONVERTER = "org.springframework.core.convert.converter.Converter";
    private static final String INIT_BINDER = "org.springframework.web.bind.annotation.InitBinder";

    public record Registry(Set<String> convertedTypes, Map<String, Set<String>> controllerEditors) {
    }

    public Registry index(Path sutSrcDir) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(sutSrcDir.toString());
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setComplianceLevel(17);
        return index(launcher.buildModel());
    }

    /** 이미 빌드된 모델 재사용(EndpointIndexer가 중복 파싱 없이 호출). */
    public Registry index(CtModel model) {
        Set<String> convertedTypes = new LinkedHashSet<>();
        Map<String, Set<String>> controllerEditors = new HashMap<>();

        for (CtType<?> type : model.getAllTypes()) {
            for (CtTypeReference<?> iface : type.getSuperInterfaces()) {
                // noClasspath + 와일드카드 import면 iface가 simple-name으로 파싱될 수 있어 둘 다 본다
                // (EndpointIndexer findAnnotation의 simple-name 폴백 패턴 계승).
                String ifaceFqn = iface.getQualifiedName();
                String ifaceSimple = iface.getSimpleName();
                if (FORMATTER.equals(ifaceFqn) || "Formatter".equals(ifaceSimple)) {
                    resolveTypeArg(iface, 0, model).ifPresent(convertedTypes::add);   // Formatter<T>
                } else if (CONVERTER.equals(ifaceFqn) || "Converter".equals(ifaceSimple)) {
                    resolveTypeArg(iface, 1, model).ifPresent(convertedTypes::add);   // Converter<S,T>
                }
            }
            for (CtMethod<?> method : type.getMethods()) {
                if (findInitBinder(method) == null) {
                    continue;
                }
                for (CtInvocation<?> inv : method.getElements(new TypeFilter<>(CtInvocation.class))) {
                    if (!"registerCustomEditor".equals(inv.getExecutable().getSimpleName())
                            || inv.getArguments().isEmpty()) {
                        continue;
                    }
                    resolveClassLiteralType(inv.getArguments().get(0)).ifPresent(t ->
                            controllerEditors.computeIfAbsent(type.getQualifiedName(), k -> new LinkedHashSet<>())
                                    .add(t));
                }
            }
        }
        return new Registry(convertedTypes, controllerEditors);
    }

    /**
     * 제네릭 인자 T의 FQN을 해석한다(noClasspath). getQualifiedName이 FQN이면 그대로, bare simple-name이면
     * 모델 전 타입을 simple-name으로 교차참조(EndpointIndexer simple-name 폴백 패턴 계승). 미해석이면 빈 값.
     */
    private static Optional<String> resolveTypeArg(CtTypeReference<?> iface, int index, CtModel model) {
        List<CtTypeReference<?>> args = iface.getActualTypeArguments();
        if (args.size() <= index) {
            return Optional.empty();
        }
        String qn = args.get(index).getQualifiedName();
        if (qn == null || qn.isBlank()) {
            return Optional.empty();
        }
        if (!qn.contains(".")) {   // bare simple-name → 모델 교차참조
            for (CtType<?> t : model.getAllTypes()) {
                if (t.getSimpleName().equals(qn)) {
                    return Optional.of(t.getQualifiedName());
                }
            }
        }
        return Optional.of(qn);
    }

    /** {@code T.class} 리터럴의 타입 FQN 추출(CtFieldRead "class" on CtTypeAccess). */
    private static Optional<String> resolveClassLiteralType(CtExpression<?> expr) {
        if (expr instanceof CtFieldRead<?> fieldRead
                && fieldRead.getVariable() != null
                && "class".equals(fieldRead.getVariable().getSimpleName())
                && fieldRead.getTarget() instanceof CtTypeAccess<?> typeAccess
                && typeAccess.getAccessedType() != null) {
            return Optional.of(typeAccess.getAccessedType().getQualifiedName());
        }
        return Optional.empty();
    }

    private static CtAnnotation<?> findInitBinder(CtMethod<?> method) {
        for (CtAnnotation<?> annotation : method.getAnnotations()) {
            CtTypeReference<?> annType = annotation.getAnnotationType();
            if (INIT_BINDER.equals(annType.getQualifiedName())
                    || "InitBinder".equals(annType.getSimpleName())) {
                return annotation;
            }
        }
        return null;
    }
}
