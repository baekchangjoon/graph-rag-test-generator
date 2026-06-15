package io.graphrag.builder.index;

import io.graphrag.model.KafkaConsumer;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtNewArray;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;

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
        Launcher launcher = new Launcher();
        launcher.addInputResource(sutSrcDir.toString());
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setComplianceLevel(17);
        CtModel model = launcher.buildModel();

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
                String payloadType = method.getParameters().isEmpty()
                        ? null : method.getParameters().get(0).getType().getQualifiedName();
                if (payloadType != null) {
                    BodyShapeExtractor.extract(model, payloadType)
                            .ifPresent(shape -> shapes.put(payloadType, shape));
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
