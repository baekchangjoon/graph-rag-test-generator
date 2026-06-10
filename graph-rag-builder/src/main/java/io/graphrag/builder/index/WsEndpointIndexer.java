package io.graphrag.builder.index;

import io.graphrag.model.WsEndpoint;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * STOMP endpoint 인덱싱 (roadmap 3.1).
 * @MessageMapping/@SendTo + WS 설정 리터럴(addEndpoint, applicationDestinationPrefixes)을
 * 정적으로 수집한다 (best-effort — 동적 설정은 미지원).
 */
public class WsEndpointIndexer {

    private static final String MESSAGE_MAPPING =
            "org.springframework.messaging.handler.annotation.MessageMapping";
    private static final String SEND_TO =
            "org.springframework.messaging.handler.annotation.SendTo";

    public WsIndexResult index(Path sutSrcDir) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(sutSrcDir.toString());
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setComplianceLevel(17);
        CtModel model = launcher.buildModel();

        String wsPath = configLiteral(model, "addEndpoint", "/ws");
        String appPrefix = configLiteral(model, "setApplicationDestinationPrefixes", "/app");

        List<WsEndpoint> endpoints = new ArrayList<>();
        Map<String, BodyShape> shapes = new HashMap<>();
        for (CtType<?> type : model.getAllTypes()) {
            for (CtMethod<?> method : type.getMethods()) {
                CtAnnotation<?> mapping = findAnnotation(method, MESSAGE_MAPPING);
                if (mapping == null) {
                    continue;
                }
                String destination = annotationValue(mapping);
                String sendTo = annotationValue(findAnnotation(method, SEND_TO));
                String payloadType = method.getParameters().isEmpty()
                        ? null : method.getParameters().get(0).getType().getQualifiedName();
                if (payloadType != null) {
                    BodyShapeExtractor.extract(model, payloadType)
                            .ifPresent(shape -> shapes.put(payloadType, shape));
                }
                endpoints.add(new WsEndpoint(
                        "ws" + destination.toLowerCase().replaceAll("[^a-z0-9]+", "-")
                                .replaceAll("-$", ""),
                        wsPath, appPrefix, destination, sendTo,
                        type.getQualifiedName().replace('$', '.'),
                        method.getSimpleName(),
                        payloadType));
            }
        }
        endpoints.sort((a, b) -> a.id().compareTo(b.id()));
        return new WsIndexResult(endpoints, shapes);
    }

    /** WS 설정 메서드 호출의 첫 문자열 리터럴 인자. 없으면 default. */
    private static String configLiteral(CtModel model, String methodName, String defaultValue) {
        for (CtInvocation<?> invocation : model.getRootPackage()
                .getElements(new TypeFilter<>(CtInvocation.class))) {
            CtExecutableReference<?> executable = invocation.getExecutable();
            if (!methodName.equals(executable.getSimpleName())) {
                continue;
            }
            for (var argument : invocation.getArguments()) {
                if (argument instanceof CtLiteral<?> literal
                        && literal.getValue() instanceof String s) {
                    return s;
                }
            }
        }
        return defaultValue;
    }

    private static CtAnnotation<?> findAnnotation(CtElement element, String qualifiedName) {
        for (CtAnnotation<?> annotation : element.getAnnotations()) {
            if (qualifiedName.equals(annotation.getAnnotationType().getQualifiedName())) {
                return annotation;
            }
        }
        return null;
    }

    private static String annotationValue(CtAnnotation<?> annotation) {
        if (annotation == null) {
            return null;
        }
        var value = annotation.getValues().get("value");
        if (value instanceof CtLiteral<?> literal && literal.getValue() instanceof String s) {
            return s;
        }
        return null;
    }
}
