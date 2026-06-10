package io.graphrag.builder.index;

import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtRecord;
import spoon.reflect.declaration.CtRecordComponent;
import spoon.reflect.declaration.CtType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 모델에서 타입을 찾아 필드 구조(BodyShape)를 추출한다. HTTP/WS 인덱서 공용. */
public final class BodyShapeExtractor {

    private BodyShapeExtractor() {
    }

    public static Optional<BodyShape> extract(CtModel model, String qualifiedName) {
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
            return Optional.of(new BodyShape(qualifiedName, fields));
        }
        return Optional.empty();
    }

    static CtType<?> findNested(CtType<?> type, String qualifiedName) {
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
