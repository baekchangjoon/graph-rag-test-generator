package io.graphrag.builder.index;

import io.graphrag.model.KafkaConsumer;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtNewArray;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @KafkaListener consumer 인덱싱. topics/groupId 리터럴 + 첫 파라미터(payload) 타입을 정적 수집한다
 * (best-effort — 토픽이 ${prop} 표현식이면 그대로 보관, 해석/skip은 캡처 러너가 결정).
 */
public class KafkaListenerIndexer {

    private static final String KAFKA_LISTENER = "org.springframework.kafka.annotation.KafkaListener";

    public KafkaIndexResult index(Path sutSrcDir) {
        return index(SharedSpoonModel.build(sutSrcDir));
    }

    public KafkaIndexResult index(CtModel model) {
        List<KafkaConsumer> consumers = new ArrayList<>();
        Map<String, BodyShape> shapes = new HashMap<>();
        for (CtType<?> type : model.getAllTypes()) {
            for (CtMethod<?> method : type.getMethods()) {
                CtAnnotation<?> listener = findAnnotation(method, KAFKA_LISTENER);
                if (listener == null) {
                    continue;
                }
                String topic = firstString(listener, "topics");
                if (topic == null) {
                    continue;   // 토픽 없는 리스너(미지원 형태)는 skip
                }
                String groupId = firstString(listener, "groupId");
                CtTypeReference<?> paramType = method.getParameters().isEmpty()
                        ? null : method.getParameters().get(0).getType();
                String payloadType;
                if (paramType != null
                        && (paramType instanceof spoon.reflect.reference.CtArrayTypeReference
                            || BodyShapeExtractor.bodyTypeKey(paramType).contains("<"))) {
                    // 컬렉션/배열 payload: 제네릭 원소 타입을 보존하는 인코딩 키로 잡는다.
                    payloadType = BodyShapeExtractor.bodyTypeKey(paramType);
                    String key = payloadType;
                    BodyShapeExtractor.extractFromType(model, paramType)
                            .ifPresent(shape -> shapes.put(key, shape));
                } else {
                    payloadType = paramType == null ? null : paramType.getQualifiedName();
                    // 핸들러가 raw String을 받아 내부에서 역직렬화하면(@KafkaListener void on(String message) {
                    //   X event = mapper.readValue(message, X.class); ... }) 그 X를 실제 payload 타입으로 본다.
                    if ("java.lang.String".equals(payloadType)) {
                        String inner = readValueTargetType(method);
                        if (inner != null) {
                            payloadType = inner;
                        }
                    }
                    if (payloadType != null) {
                        String resolved = payloadType;
                        BodyShapeExtractor.extract(model, resolved)
                                .ifPresent(shape -> shapes.put(resolved, shape));
                    }
                }
                consumers.add(new KafkaConsumer(
                        "kafka-" + topic.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", ""),
                        topic, groupId,
                        type.getQualifiedName().replace('$', '.'),
                        method.getSimpleName(),
                        payloadType));
            }
        }
        consumers.sort((a, b) -> a.id().compareTo(b.id()));
        return new KafkaIndexResult(consumers, shapes);
    }

    /** 핸들러 본문의 첫 {@code readValue(_, X.class)} 의 X 타입 FQN (raw String payload 역직렬화 타깃). */
    private static String readValueTargetType(CtMethod<?> method) {
        if (method.getBody() == null) {
            return null;
        }
        for (CtInvocation<?> inv : method.getElements(new TypeFilter<>(CtInvocation.class))) {
            if (!"readValue".equals(inv.getExecutable().getSimpleName()) || inv.getArguments().size() < 2) {
                continue;
            }
            // 2번째 인자 X.class → CtFieldRead(variable="class", target=CtTypeAccess(X)).
            if (inv.getArguments().get(1) instanceof CtFieldRead<?> fieldRead
                    && fieldRead.getTarget() instanceof CtTypeAccess<?> typeAccess) {
                CtTypeReference<?> accessed = typeAccess.getAccessedType();
                if (accessed != null && accessed.getQualifiedName() != null
                        && !accessed.getQualifiedName().isBlank()) {
                    return accessed.getQualifiedName();
                }
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

    /** 어노테이션 속성의 첫 문자열 리터럴 (단일 리터럴 또는 배열 첫 원소). */
    private static String firstString(CtAnnotation<?> annotation, String attribute) {
        var value = annotation.getValues().get(attribute);
        if (value instanceof CtLiteral<?> literal && literal.getValue() instanceof String s) {
            return s;
        }
        if (value instanceof CtNewArray<?> array) {
            for (var element : array.getElements()) {
                if (element instanceof CtLiteral<?> literal && literal.getValue() instanceof String s) {
                    return s;
                }
            }
        }
        return null;
    }
}
